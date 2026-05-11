package com.cloud.apim.otoroshi.extensions.aigateway.assistant.docsearch

import scala.concurrent.duration._

final case class CorpusSource(id: String, url: String, baseUrl: String)

final case class DocSearchConfig(
  enabled: Boolean,
  ttl: FiniteDuration,
  topK: Int,
  rrfK: Int,
  lexicalCandidates: Int,
  semanticCandidates: Int,
  corpora: Seq[CorpusSource]
)

object DocSearchConfig {
  val default: DocSearchConfig = DocSearchConfig(
    enabled = true,
    ttl = 24.hours,
    topK = 8,
    rrfK = 60,
    lexicalCandidates = 30,
    semanticCandidates = 30,
    corpora = Seq(
      // `baseUrl` is the *origin* (scheme + host) only — the index entries' `u` field
      // already contains the full path (including any project sub-path like
      // "/otoroshi-biscuit-studio/...").
      CorpusSource(
        id = "otoroshi",
        url = "https://www.otoroshi.io/search-index.json",
        baseUrl = "https://www.otoroshi.io"
      ),
      CorpusSource(
        id = "llm-extension",
        url = "https://cloud-apim.github.io/otoroshi-llm-extension/search-index.json",
        baseUrl = "https://cloud-apim.github.io"
      ),
      CorpusSource(
        id = "biscuit-studio",
        url = "https://cloud-apim.github.io/otoroshi-biscuit-studio/search-index.json",
        baseUrl = "https://cloud-apim.github.io"
      )
    )
  )
}
