package com.cloud.apim.otoroshi.extensions.aigateway.entities

import com.cloud.apim.otoroshi.extensions.aigateway.providers.OpenAiLikeProviders
import play.api.libs.json._

/**
 * Catalog of every provider type Otoroshi LLM can talk to, together with the modalities
 * (capabilities) each one exposes.
 *
 * This is fully derived from the `getXXXClient()` registries of each model entity
 * ([[AiProvider]], [[AudioModel]], [[ImageModel]], [[OcrModel]], [[EmbeddingModel]],
 * [[ModerationModel]], [[VideoModel]]) through their `supportedProviders` set. Adding a `case`
 * (i.e. a registry entry) to any of those is enough for the provider/capability to show up here:
 * there is no hand-maintained list to keep in sync.
 */
object AiProvidersCatalog {

  // capability identifiers, aligned with the modalities of the unified OpenAI-compatible API
  val Text       = "text"
  val Audio      = "audio"
  val Image      = "image"
  val Ocr        = "ocr"
  val Embedding  = "embedding"
  val Moderation = "moderation"
  val Video      = "video"

  // capability -> provider ids supporting it, read live from each modality registry
  private def capabilityIndex: Seq[(String, Set[String])] = Seq(
    Text       -> AiProvider.supportedProviders,
    Audio      -> AudioModel.supportedProviders,
    Image      -> ImageModel.supportedProviders,
    Ocr        -> OcrModel.supportedProviders,
    Embedding  -> EmbeddingModel.supportedProviders,
    Moderation -> ModerationModel.supportedProviders,
    Video      -> VideoModel.supportedProviders,
  )

  // every capability name this catalog can expose, in display order
  val allCapabilities: Seq[String] = capabilityIndex.map(_._1)

  // human-readable labels for the provider ids that are not OpenAI-like providers
  // (OpenAI-like provider labels come from OpenAiLikeProviders directly)
  private val labels: Map[String, String] = Map(
    "openai"                    -> "OpenAI",
    "openai-compatible"         -> "OpenAI Compatible",
    "azure-ai-foundry"          -> "Azure AI Foundry",
    "azure-openai"              -> "Azure OpenAI",
    "mistral"                   -> "Mistral",
    "ollama"                    -> "Ollama",
    "ollama-openai"             -> "Ollama (OpenAI compatible)",
    "anthropic"                 -> "Anthropic",
    "groq"                      -> "Groq",
    "x-ai"                      -> "X.ai",
    "scaleway"                  -> "Scaleway",
    "cloud-temple"              -> "Cloud Temple",
    "deepseek"                  -> "Deepseek",
    "ovh-ai-endpoints"          -> "OVH AI Endpoints",
    "ovh-ai-endpoints-unified"  -> "OVH AI Endpoints (unified)",
    "huggingface"               -> "HuggingFace",
    "cloudflare"                -> "Cloudflare",
    "cohere"                    -> "Cohere",
    "gemini"                    -> "Gemini",
    "alphaedge"                 -> "AlphaEdge",
    "jlama"                     -> "JLama",
    "loadbalancer"              -> "Loadbalancer",
    "otoroshi"                  -> "Otoroshi (router)",
    "elevenlabs"                -> "ElevenLabs",
    "luma"                      -> "Luma",
    "leonardo-ai"               -> "Leonardo AI",
    "hive"                      -> "Hive",
    "all-minilm-l6-v2"          -> "All MiniLM L6 v2",
  )

  def labelFor(id: String): String =
    labels.getOrElse(id, OpenAiLikeProviders.find(id).map(_.name).getOrElse(id))

  final case class ProviderEntry(id: String, label: String, capabilities: Seq[String]) {
    def json: JsObject = Json.obj(
      "id"           -> id,
      "label"        -> label,
      "capabilities" -> capabilities,
    )
  }

  // every provider id known across all modality registries, with its capabilities
  def all: Seq[ProviderEntry] = {
    val index = capabilityIndex
    val ids = index.flatMap(_._2).distinct.sorted
    ids.map { id =>
      val capabilities = index.collect { case (cap, providers) if providers.contains(id) => cap }
      ProviderEntry(id, labelFor(id), capabilities)
    }
  }

  // keep only providers exposing ALL the requested capabilities (AND semantics); empty filter = all
  def filtered(requested: Seq[String]): Seq[ProviderEntry] = {
    val wanted = requested.map(_.trim.toLowerCase).filter(_.nonEmpty).distinct
    if (wanted.isEmpty) all
    else all.filter(entry => wanted.forall(entry.capabilities.contains))
  }

  def json(requested: Seq[String]): JsArray = JsArray(filtered(requested).map(_.json))
}
