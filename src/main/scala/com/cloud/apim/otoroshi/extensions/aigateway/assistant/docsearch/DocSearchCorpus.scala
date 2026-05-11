package com.cloud.apim.otoroshi.extensions.aigateway.assistant.docsearch

import otoroshi.env.Env
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

private object DocSearchCorpusLog {
  val logger: play.api.Logger = play.api.Logger("cloud-apim-llm-extension-doc-search")
}

sealed trait CorpusFetchResult
object CorpusFetchResult {
  final case class Fresh(chunks: Seq[DocChunk], etag: Option[String], lastModified: Option[String]) extends CorpusFetchResult
  case object NotModified extends CorpusFetchResult
  final case class Failed(message: String) extends CorpusFetchResult
}

object DocSearchCorpus {

  private val fetchTimeout: FiniteDuration = 30.seconds

  def fetch(
    source: CorpusSource,
    previousEtag: Option[String],
    previousLastModified: Option[String]
  )(implicit ec: ExecutionContext, env: Env): Future[CorpusFetchResult] = {
    val logger = DocSearchCorpusLog.logger
    val baseHeaders: Seq[(String, String)] = Seq(
      "Accept" -> "application/json",
      "User-Agent" -> "otoroshi-llm-extension/doc-search"
    )
    val conditional: Seq[(String, String)] = Seq(
      previousEtag.map("If-None-Match" -> _),
      previousLastModified.map("If-Modified-Since" -> _)
    ).flatten
    val startedAt = System.currentTimeMillis()
    val conditionalNote = if (conditional.isEmpty) "" else s" (conditional: ${conditional.map(_._1).mkString(",")})"
    logger.info(s"doc-search fetch start: corpus=${source.id} url=${source.url}$conditionalNote")
    env.Ws.url(source.url)
      .withHttpHeaders((baseHeaders ++ conditional): _*)
      .withFollowRedirects(true)
      .withRequestTimeout(fetchTimeout)
      .get()
      .map { resp =>
        val took = System.currentTimeMillis() - startedAt
        if (resp.status == 304) {
          logger.info(s"doc-search fetch 304 Not Modified: corpus=${source.id} took=${took}ms")
          CorpusFetchResult.NotModified
        } else if (resp.status < 200 || resp.status >= 300) {
          logger.warn(s"doc-search fetch failed: corpus=${source.id} status=${resp.status} took=${took}ms")
          CorpusFetchResult.Failed(s"HTTP ${resp.status} fetching ${source.url}")
        } else {
          val bytes = resp.body.length
          val parseStart = System.currentTimeMillis()
          val json = Json.parse(resp.body)
          val chunks = parse(source.id, source.baseUrl, json)
          val parseTook = System.currentTimeMillis() - parseStart
          val etag = resp.header("ETag").orElse(resp.header("Etag"))
          val lastModified = resp.header("Last-Modified")
          logger.info(s"doc-search fetch ok: corpus=${source.id} status=${resp.status} bytes=$bytes chunks=${chunks.size} httpTook=${took - parseTook}ms parseTook=${parseTook}ms etag=${etag.getOrElse("-")} lastModified=${lastModified.getOrElse("-")}")
          CorpusFetchResult.Fresh(chunks, etag, lastModified)
        }
      }
      .recover { case t: Throwable =>
        val took = System.currentTimeMillis() - startedAt
        logger.warn(s"doc-search fetch error: corpus=${source.id} url=${source.url} took=${took}ms error=${t.getMessage}")
        CorpusFetchResult.Failed(s"fetch failed for ${source.url}: ${t.getMessage}")
      }
  }

  def parse(corpusId: String, baseUrl: String, root: JsValue): Seq[DocChunk] = {
    val subIndices: Seq[JsValue] = root match {
      case JsArray(items) => items.toSeq
      case _ => Seq.empty
    }
    val buf = scala.collection.mutable.ArrayBuffer.empty[DocChunk]
    subIndices.zipWithIndex.foreach { case (sub, subIdx) =>
      val documents: Seq[JsValue] = (sub \ "documents").asOpt[JsArray].map(_.value.toSeq).getOrElse(Seq.empty)
      val byId: Map[Int, JsValue] = documents.flatMap(d => (d \ "i").asOpt[Int].map(_ -> d)).toMap
      documents.foreach { d =>
        parseDoc(corpusId, subIdx, baseUrl, d, byId).foreach(buf.+=)
      }
    }
    buf.toSeq
  }

  private def parseDoc(corpusId: String, subIdx: Int, baseUrl: String, doc: JsValue, byId: Map[Int, JsValue]): Option[DocChunk] = {
    val iOpt = (doc \ "i").asOpt[Int]
    val tOpt = (doc \ "t").asOpt[String].map(_.trim).filter(_.nonEmpty)
    val uOpt = (doc \ "u").asOpt[String].map(_.trim).filter(_.nonEmpty)
    (iOpt, tOpt, uOpt) match {
      case (Some(i), Some(t), Some(u)) =>
        val s = (doc \ "s").asOpt[String].map(_.trim).filter(_.nonEmpty)
        val h = (doc \ "h").asOpt[String].map(_.trim).filter(_.nonEmpty)
        val ownBreadcrumb = (doc \ "b").asOpt[JsArray].map(_.value.toSeq.flatMap(_.asOpt[String])).getOrElse(Seq.empty)
        val parentId = (doc \ "p").asOpt[Int]
        val breadcrumb: Seq[String] = if (ownBreadcrumb.nonEmpty) ownBreadcrumb
        else parentId.flatMap(byId.get) match {
          case Some(parent) =>
            val parentCrumb = (parent \ "b").asOpt[JsArray].map(_.value.toSeq.flatMap(_.asOpt[String])).getOrElse(Seq.empty)
            val parentTitle = (parent \ "t").asOpt[String].toSeq
            parentCrumb ++ parentTitle
          case None => Seq.empty
        }
        val absoluteUrl: String = {
          val origin = baseUrl.stripSuffix("/")
          val path = if (u.startsWith("http://") || u.startsWith("https://")) u
          else if (u.startsWith("/")) origin + u
          else s"$origin/$u"
          h match {
            case Some(anchor) if !path.contains("#") =>
              if (anchor.startsWith("#")) path + anchor else s"$path#$anchor"
            case _ => path
          }
        }
        val headingClean = h.map(_.stripPrefix("#"))
        val text = s.getOrElse(t)
        DocChunk(
          corpusId = corpusId,
          docId = s"$corpusId:$subIdx:$i",
          title = t,
          breadcrumb = breadcrumb,
          heading = headingClean,
          url = absoluteUrl,
          text = text
        ).some
      case _ => None
    }
  }
}
