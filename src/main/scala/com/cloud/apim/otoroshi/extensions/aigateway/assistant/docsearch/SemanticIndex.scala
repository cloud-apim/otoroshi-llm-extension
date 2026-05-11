package com.cloud.apim.otoroshi.extensions.aigateway.assistant.docsearch

import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.model.embedding.EmbeddingModel
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel
import dev.langchain4j.store.embedding.EmbeddingSearchRequest
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore

import scala.collection.JavaConverters._

final class SemanticIndex private (
  store: InMemoryEmbeddingStore[TextSegment],
  model: EmbeddingModel
) {
  def search(rawQuery: String, topK: Int): Seq[(String, Double)] = {
    val q = rawQuery.trim
    if (q.isEmpty) return Seq.empty
    val emb = model.embed(q).content()
    val req = EmbeddingSearchRequest.builder()
      .queryEmbedding(emb)
      .maxResults(topK)
      .build()
    val res = store.search(req)
    res.matches().asScala.map { m =>
      (m.embeddingId(), m.score().doubleValue())
    }.toSeq
  }
}

object SemanticIndex {
  // Shared ONNX model — heavy to load (~25 MB), reuse across rebuilds.
  private lazy val sharedModel: EmbeddingModel = new AllMiniLmL6V2EmbeddingModel()

  // MiniLM-L6-v2 has a 256-token context. Pre-truncate input around that window
  // (rough chars/token ratio) to avoid paying tokenization cost for content the
  // model would discard anyway.
  private val maxInputChars: Int = 2000
  private val embedBatchSize: Int = 32

  def build(chunks: Seq[DocChunk]): SemanticIndex = {
    val store = new InMemoryEmbeddingStore[TextSegment]()
    val model = sharedModel
    val embeddable = chunks.filter(_.hasBody)
    embeddable.grouped(embedBatchSize).foreach { batch =>
      val segments = batch.map(c => TextSegment.from(truncate(c.embeddingInput)))
      val response = model.embedAll(segments.asJava)
      val embeddings = response.content().asScala.toSeq
      batch.iterator.zip(embeddings.iterator).foreach { case (chunk, emb) =>
        store.add(chunk.docId, emb, TextSegment.from(chunk.docId))
      }
    }
    new SemanticIndex(store, model)
  }

  private def truncate(s: String): String =
    if (s.length <= maxInputChars) s else s.substring(0, maxInputChars)
}
