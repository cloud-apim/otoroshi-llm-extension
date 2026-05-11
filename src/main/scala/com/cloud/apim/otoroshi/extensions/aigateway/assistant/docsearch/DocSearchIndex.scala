package com.cloud.apim.otoroshi.extensions.aigateway.assistant.docsearch

import otoroshi.env.Env
import play.api.Logger

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.concurrent.{ExecutionContext, Future}

private final case class CorpusSnapshot(
  source: CorpusSource,
  chunks: Seq[DocChunk],
  etag: Option[String],
  lastModified: Option[String],
  fetchedAt: Long
)

private final case class IndexState(
  builtAt: Long,
  snapshots: Map[String, CorpusSnapshot],
  chunksById: Map[String, DocChunk],
  lexical: LexicalIndex,
  semantic: SemanticIndex
)

final class DocSearchIndex(val config: DocSearchConfig) {

  private val logger = Logger("cloud-apim-llm-extension-doc-search")
  private val state = new AtomicReference[Option[IndexState]](None)
  private val building = new AtomicBoolean(false)

  def isReady: Boolean = state.get().isDefined
  def isBuilding: Boolean = building.get()

  def invalidate(): Unit = state.set(None)

  def search(query: String, corpus: Option[String])(implicit ec: ExecutionContext, env: Env): Future[Either[String, Seq[DocSearchResult]]] = {
    ensureFresh().map { _ =>
      state.get() match {
        case None =>
          if (building.get()) Left("doc search index is still being built — retry in a few seconds")
          else Left("doc search index is not available")
        case Some(s) =>
          val filterCorpus: ((String, _)) => Boolean = {
            case (id, _) =>
              corpus match {
                case None => true
                case Some(c) => s.chunksById.get(id).exists(_.corpusId == c)
              }
          }
          val lex = s.lexical.search(query, config.lexicalCandidates).filter(filterCorpus)
          val sem = s.semantic.search(query, config.semanticCandidates).filter(filterCorpus)
          val candidates = corpus match {
            case None => s.chunksById
            case Some(c) => s.chunksById.filter { case (_, chunk) => chunk.corpusId == c }
          }
          Right(HybridSearcher.fuse(lex, sem, candidates, config.rrfK, config.topK))
      }
    }
  }

  private def ensureFresh()(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    val now = System.currentTimeMillis()
    state.get() match {
      case Some(s) if now - s.builtAt < config.ttl.toMillis => Future.successful(())
      case Some(_) =>
        // Stale-while-revalidate: serve old data, refresh in background.
        triggerRebuild()
        Future.successful(())
      case None =>
        // First build — wait for it.
        triggerRebuild()
    }
  }

  private def triggerRebuild()(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    if (!building.compareAndSet(false, true)) return Future.successful(())
    val previous: Option[IndexState] = state.get()
    val previousSnapshots: Map[String, CorpusSnapshot] = previous.map(_.snapshots).getOrElse(Map.empty)
    val fetches: Future[Seq[CorpusSnapshot]] = Future.sequence(config.corpora.map { source =>
      val prev = previousSnapshots.get(source.id)
      DocSearchCorpus.fetch(source, prev.flatMap(_.etag), prev.flatMap(_.lastModified)).map {
        case CorpusFetchResult.Fresh(chunks, etag, lm) =>
          CorpusSnapshot(source, chunks, etag, lm, System.currentTimeMillis())
        case CorpusFetchResult.NotModified =>
          prev match {
            case Some(p) => p.copy(fetchedAt = System.currentTimeMillis())
            case None => CorpusSnapshot(source, Seq.empty, None, None, System.currentTimeMillis())
          }
        case CorpusFetchResult.Failed(msg) =>
          logger.warn(s"doc-search fetch failed for ${source.id}: $msg — keeping previous snapshot if any")
          prev.getOrElse(CorpusSnapshot(source, Seq.empty, None, None, System.currentTimeMillis()))
      }
    })
    fetches.map { snapshots =>
      try {
        val allChunks: Seq[DocChunk] = snapshots.flatMap(_.chunks)
        if (allChunks.isEmpty && previous.isEmpty) {
          logger.warn("doc-search: no chunks fetched and no previous state — search will be unavailable")
        } else {
          val chunksById: Map[String, DocChunk] = allChunks.iterator.map(c => c.docId -> c).toMap
          val lex = LexicalIndex.build(allChunks)
          val sem = SemanticIndex.build(allChunks)
          val snapshotMap: Map[String, CorpusSnapshot] = snapshots.map(s => s.source.id -> s).toMap
          val newState = IndexState(System.currentTimeMillis(), snapshotMap, chunksById, lex, sem)
          val old = state.getAndSet(Some(newState))
          old.foreach { o => try o.lexical.close() catch { case _: Throwable => () } }
          logger.info(s"doc-search: indexed ${allChunks.size} chunks across ${snapshots.size} corpora")
        }
      } catch {
        case t: Throwable => logger.error("doc-search: rebuild failed", t)
      } finally {
        building.set(false)
      }
    }.recover {
      case t: Throwable =>
        logger.error("doc-search: rebuild error", t)
        building.set(false)
        ()
    }
  }
}

object DocSearchIndex {
  private val ref = new AtomicReference[Option[DocSearchIndex]](None)

  def get(): DocSearchIndex = ref.get() match {
    case Some(i) => i
    case None =>
      val instance = new DocSearchIndex(DocSearchConfig.default)
      if (ref.compareAndSet(None, Some(instance))) instance else ref.get().get
  }
}
