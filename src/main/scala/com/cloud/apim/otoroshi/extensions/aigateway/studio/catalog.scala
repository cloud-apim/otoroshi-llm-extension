package com.cloud.apim.otoroshi.extensions.aigateway.studio

import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvidersCatalog
import com.cloud.apim.otoroshi.extensions.aigateway.providers.*
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*

/**
 * Provider catalog used by the AI Studio "bring your own key" form. It is derived from
 * [[AiProvidersCatalog]] (provider ids and capabilities, read live from the modality registries) and
 * enriched with what a simplified form needs: the default base url, the extra connection fields and
 * a suggested model per modality.
 */
object AiStudioCatalog {

  final case class Blueprint(
    baseUrl: Option[String] = None,
    models: Map[String, String] = Map.empty,
    fields: Seq[JsObject] = Seq.empty,
    tokenRequired: Boolean = true,
    baseUrlRequired: Boolean = false,
  )

  private def field(name: String, label: String, placeholder: String = "", default: String = ""): JsObject =
    Json.obj("name" -> name, "label" -> label, "placeholder" -> placeholder, "default" -> default)

  // virtual or local providers that make no sense as a BYOK connection
  private val excluded = Set("loadbalancer", "otoroshi", "jlama", "all-minilm-l6-v2")

  private val blueprints: Map[String, Blueprint] = Map(
    "openai" -> Blueprint(OpenAiApi.baseUrl.some, Map(
      "text" -> "gpt-4o-mini", "embedding" -> "text-embedding-3-small", "image" -> "gpt-image-1",
      "audio_tts" -> "gpt-4o-mini-tts", "audio_stt" -> "gpt-4o-mini-transcribe", "moderation" -> "omni-moderation-latest",
    )),
    "openai-compatible" -> Blueprint(None, Map.empty, baseUrlRequired = true),
    "azure-openai" -> Blueprint(None, Map("text" -> "", "embedding" -> "text-embedding-3-small", "image" -> "gpt-image-1", "audio_tts" -> "gpt-4o-mini-tts", "audio_stt" -> "gpt-4o-mini-transcribe"), fields = Seq(
      field("resource_name", "Resource name", "my-aoai-resource"),
      field("deployment_id", "Deployment id", "gpt-4o-mini"),
      field("api_version", "API version", "2024-02-01", "2024-02-01"),
    )),
    "azure-ai-foundry" -> Blueprint(AzureAiFoundry.baseUrl.some, Map("text" -> "mistral-large-2407", "embedding" -> "text-embedding-3-small"), baseUrlRequired = true),
    "mistral" -> Blueprint(MistralAiApi.baseUrl.some, Map(
      "text" -> "mistral-small-latest", "embedding" -> "mistral-embed", "audio_stt" -> "voxtral-mini-latest",
      "moderation" -> "mistral-moderation-latest", "ocr" -> "mistral-ocr-latest",
    )),
    "ovh-ai-endpoints" -> Blueprint(OVHAiEndpointsApi.unifiedUrl.some, Map("text" -> "Meta-Llama-3_3-70B-Instruct", "embedding" -> "bge-multilingual-gemma2", "audio_stt" -> "whisper-large-v3", "moderation" -> "")),
    "ovh-ai-endpoints-unified" -> Blueprint(OVHAiEndpointsApi.unifiedUrl.some, Map("text" -> "Meta-Llama-3_3-70B-Instruct")),
    "cloud-temple" -> Blueprint(CloudTemple.baseUrl.some, Map("text" -> "gpt-oss:120b", "embedding" -> "embeddinggemma:300m", "image" -> "", "audio_stt" -> "")),
    "gemini" -> Blueprint(GeminiApi.baseUrl.some, Map("text" -> "gemini-2.5-flash", "embedding" -> "gemini-embedding-001", "image" -> "imagen-3.0-generate-002")),
    "x-ai" -> Blueprint(XAiApi.baseUrl.some, Map("text" -> "grok-3-mini", "image" -> "grok-2-image", "embedding" -> "v1")),
    "groq" -> Blueprint(GroqApi.baseUrl.some, Map("text" -> "llama-3.3-70b-versatile", "audio_tts" -> "playai-tts", "audio_stt" -> "whisper-large-v3-turbo")),
    "alphaedge" -> Blueprint(AlphaEdgeApi.baseUrl.some, Map("text" -> "alpha-digit-max", "audio_stt" -> "alpha-audio-v1", "ocr" -> "alpha-digit-max")),
    "openrouter" -> Blueprint(OpenRouterApi.baseUrl.some, Map(
      "text" -> "openai/gpt-4o-mini", "embedding" -> "openai/text-embedding-3-small", "image" -> "google/gemini-2.5-flash-image",
      "audio_tts" -> "elevenlabs/eleven-turbo-v2", "audio_stt" -> "openai/whisper-large-v3", "video" -> "google/veo-3.1",
    )),
    "scaleway" -> Blueprint(ScalewayApi.baseUrl.some, Map("text" -> "llama-3.1-8b-instruct", "embedding" -> "qwen3-embedding-8b")),
    "deepseek" -> Blueprint(DeepSeekApi.baseUrl.some, Map("text" -> "deepseek-chat", "embedding" -> "")),
    "huggingface" -> Blueprint(HuggingfaceApi.baseUrl.some, Map("text" -> "google/gemma-2-2b-it", "embedding" -> "Qwen/Qwen3-Embedding-8B")),
    "ollama" -> Blueprint(OllamaAiApi.baseUrl.some, Map("text" -> "llama3.2", "embedding" -> "snowflake-arctic-embed:22m"), tokenRequired = false),
    "ollama-openai" -> Blueprint(OllamaAiApi.baseUrlOAI.some, Map("text" -> "llama3.2"), tokenRequired = false),
    "cohere" -> Blueprint(CohereAiApi.baseUrl.some, Map("text" -> "command-r-plus-08-2024", "embedding" -> "embed-v4.0")),
    "anthropic" -> Blueprint(AnthropicApi.baseUrl.some, Map("text" -> "claude-haiku-4-5")),
    "cloudflare" -> Blueprint(None, Map("text" -> ""), fields = Seq(
      field("account_id", "Account id", "your cloudflare account id"),
      field("model_name", "Model", "@cf/meta/llama-3.1-8b-instruct-fp8", "@cf/meta/llama-3.1-8b-instruct-fp8"),
    )),
    "elevenlabs" -> Blueprint(ElevenLabsApi.baseUrl.some, Map("audio_tts" -> "eleven_multilingual_v2", "audio_stt" -> "scribe_v1")),
    "luma" -> Blueprint(LumaApi.baseUrl.some, Map("image" -> "photon-1", "video" -> "ray-flash-2")),
    "leonardo-ai" -> Blueprint(LeonardoAIApi.baseUrl.some, Map("image" -> "6b645e3a-d64f-4341-a6d8-7a3690fbf042")),
    "hive" -> Blueprint(HiveApi.baseUrl.some, Map("image" -> "black-forest-labs/flux-schnell")),
  )

  private def blueprintFor(id: String): Blueprint = blueprints.get(id).orElse {
    OpenAiLikeProviders.find(id).map(d => Blueprint(d.baseUrl.some, Map("text" -> "")))
  }.getOrElse(Blueprint())

  def json: JsArray = JsArray(AiProvidersCatalog.all.filterNot(e => excluded.contains(e.id)).map { entry =>
    val bp = blueprintFor(entry.id)
    Json.obj(
      "id" -> entry.id,
      "label" -> entry.label,
      "capabilities" -> entry.capabilities,
      "base_url" -> bp.baseUrl.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "base_url_required" -> bp.baseUrlRequired,
      "token_required" -> bp.tokenRequired,
      "models" -> bp.models,
      "fields" -> JsArray(bp.fields),
      "openai_like" -> OpenAiLikeProviders.find(entry.id).isDefined,
    )
  }.sortBy(_.select("label").asOptString.getOrElse("").toLowerCase))
}
