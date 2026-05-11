package com.cloud.apim.otoroshi.extensions.aigateway.assistant.docsearch

object HybridSearcher {

  /** Reciprocal Rank Fusion. `rrfK` is the smoothing constant (60 is the standard default). */
  def fuse(
    lexicalHits: Seq[(String, Float)],
    semanticHits: Seq[(String, Double)],
    chunks: Map[String, DocChunk],
    rrfK: Int,
    topK: Int
  ): Seq[DocSearchResult] = {
    val lexicalRanks: Map[String, Int] =
      lexicalHits.iterator.zipWithIndex.map { case ((id, _), idx) => id -> (idx + 1) }.toMap
    val semanticRanks: Map[String, Int] =
      semanticHits.iterator.zipWithIndex.map { case ((id, _), idx) => id -> (idx + 1) }.toMap
    val candidateIds: Set[String] = lexicalRanks.keySet ++ semanticRanks.keySet
    val scored: Seq[DocSearchResult] = candidateIds.iterator.flatMap { id =>
      chunks.get(id).map { chunk =>
        val lr = lexicalRanks.get(id)
        val sr = semanticRanks.get(id)
        val score =
          lr.map(r => 1.0 / (rrfK + r)).getOrElse(0.0) +
          sr.map(r => 1.0 / (rrfK + r)).getOrElse(0.0)
        DocSearchResult(chunk, score, lr, sr)
      }
    }.toSeq
    scored.sortBy(-_.score).take(topK)
  }
}
