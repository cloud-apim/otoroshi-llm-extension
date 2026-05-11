package com.cloud.apim.otoroshi.extensions.aigateway.assistant.docsearch

final case class DocChunk(
  corpusId: String,
  docId: String,
  title: String,
  breadcrumb: Seq[String],
  heading: Option[String],
  url: String,
  text: String
) {
  def hasBody: Boolean = text.nonEmpty && text != title
  def embeddingInput: String = {
    val crumb = if (breadcrumb.nonEmpty) breadcrumb.mkString(" > ") + "\n" else ""
    s"$title\n$crumb$text"
  }
}

final case class DocSearchResult(
  chunk: DocChunk,
  score: Double,
  lexicalRank: Option[Int],
  semanticRank: Option[Int]
)
