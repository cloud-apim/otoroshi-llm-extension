package com.cloud.apim.otoroshi.extensions.aigateway.studio

import com.cloud.apim.otoroshi.extensions.aigateway.catalog.ProviderInsights
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvidersCatalog
import com.cloud.apim.otoroshi.extensions.aigateway.providers.*
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.*

import scala.concurrent.{ExecutionContext, Future}

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
      "text" -> "gpt-4o-mini", "embedding" -> "text-embedding-3-small", "image" -> "gpt-image-2",
      "audio_tts" -> "gpt-4o-mini-tts", "audio_stt" -> "gpt-transcribe", "moderation" -> "omni-moderation-latest",
    )),
    "openai-compatible" -> Blueprint(None, Map.empty, baseUrlRequired = true),
    "azure-openai" -> Blueprint(None, Map("text" -> "", "embedding" -> "text-embedding-3-large", "image" -> "gpt-image-2", "audio_tts" -> "gpt-4o-mini-tts", "audio_stt" -> "gpt-4o-transcribe"), fields = Seq(
      field("resource_name", "Resource name", "my-aoai-resource"),
      field("deployment_id", "Deployment id", "gpt-4o-mini"),
      field("api_version", "API version", "2024-02-01", "2024-02-01"),
    )),
    "azure-ai-foundry" -> Blueprint(AzureAiFoundry.baseUrl.some, Map("text" -> "mistral-large-2407", "embedding" -> "text-embedding-3-small"), baseUrlRequired = true),
    "mistral" -> Blueprint(MistralAiApi.baseUrl.some, Map(
      "text" -> "mistral-small-latest", "embedding" -> "mistral-embed", "audio_stt" -> "voxtral-mini-latest",
      "moderation" -> "mistral-moderation-latest", "ocr" -> "mistral-ocr-latest",
    )),
    "ovh-ai-endpoints" -> Blueprint(OVHAiEndpointsApi.unifiedUrl.some, Map("text" -> "Meta-Llama-3_3-70B-Instruct", "embedding" -> "bge-m3", "image" -> "stable-diffusion-xl-base-v10", "audio_stt" -> "whisper-large-v3")),
    "ovh-ai-endpoints-unified" -> Blueprint(OVHAiEndpointsApi.unifiedUrl.some, Map("text" -> "Meta-Llama-3_3-70B-Instruct")),
    "cloud-temple" -> Blueprint(CloudTemple.baseUrl.some, Map("text" -> "gpt-oss:120b", "embedding" -> "bge-m3:567m", "image" -> "x/z-image-turbo:latest", "audio_tts" -> "tts-1", "audio_stt" -> "")),
    "gemini" -> Blueprint(GeminiApi.baseUrl.some, Map("text" -> "gemini-2.5-flash", "embedding" -> "gemini-embedding-001", "image" -> "gemini-2.5-flash-image")),
    "x-ai" -> Blueprint(XAiApi.baseUrl.some, Map("text" -> "grok-3-mini", "image" -> "grok-imagine-image-2.0", "embedding" -> "")),
    "groq" -> Blueprint(GroqApi.baseUrl.some, Map("text" -> "llama-3.3-70b-versatile", "audio_tts" -> "canopylabs/orpheus-v1-english", "audio_stt" -> "whisper-large-v3-turbo")),
    "alphaedge" -> Blueprint(AlphaEdgeApi.baseUrl.some, Map("text" -> "alpha-digit-max", "audio_stt" -> "alpha-audio-v1", "ocr" -> "alpha-digit-max")),
    "openrouter" -> Blueprint(OpenRouterApi.baseUrl.some, Map(
      "text" -> "openai/gpt-4o-mini", "embedding" -> "openai/text-embedding-3-small", "image" -> "google/gemini-2.5-flash-image",
      "audio_tts" -> "openai/gpt-4o-mini-tts-2025-12-15", "audio_stt" -> "openai/whisper-1", "video" -> "google/veo-3.1",
    )),
    "scaleway" -> Blueprint(ScalewayApi.baseUrl.some, Map("text" -> "llama-3.1-8b-instruct", "embedding" -> "qwen3-embedding-8b", "audio_stt" -> "whisper-large-v3")),
    "deepseek" -> Blueprint(DeepSeekApi.baseUrl.some, Map("text" -> "deepseek-chat")),
    "huggingface" -> Blueprint(HuggingfaceApi.baseUrl.some, Map("text" -> "google/gemma-2-2b-it", "embedding" -> "Qwen/Qwen3-Embedding-8B")),
    "ollama" -> Blueprint(OllamaAiApi.baseUrl.some, Map("text" -> "llama3.2", "embedding" -> "snowflake-arctic-embed:22m"), tokenRequired = false),
    "ollama-openai" -> Blueprint(OllamaAiApi.baseUrlOAI.some, Map("text" -> "llama3.2", "embedding" -> "nomic-embed-text"), tokenRequired = false),
    "cohere" -> Blueprint(CohereAiApi.baseUrl.some, Map("text" -> "command-r-plus-08-2024", "embedding" -> "embed-v4.0", "audio_stt" -> "cohere-transcribe-03-2026")),
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

  // suggested models of the non text capabilities of OpenAI-like providers (from their documentation)
  private val likeModels: Map[String, Map[String, String]] = Map(
    "veniceai" -> Map("embedding" -> "text-embedding-bge-m3", "image" -> "grok-imagine-image", "audio_stt" -> "openai/whisper-large-v3", "audio_tts" -> "tts-kokoro"),
    "synthetic" -> Map("embedding" -> "hf:nomic-ai/nomic-embed-text-v1.5"),
    "apertis" -> Map("embedding" -> "text-embedding-3-small", "audio_stt" -> "whisper-1", "audio_tts" -> "tts-1"),
    "nano-gpt" -> Map("embedding" -> "text-embedding-3-small", "audio_stt" -> "Whisper-Large-V3", "audio_tts" -> "gpt-4o-mini-tts", "moderation" -> "omni-moderation-latest"),
    "aiml" -> Map("embedding" -> "text-embedding-3-small", "image" -> "openai/gpt-image-1"),
    "arkane-cloud" -> Map("image" -> ArkaneCloudApi.defaultImageModel),
    "cometapi" -> Map("embedding" -> "text-embedding-3-small", "image" -> "gpt-image-2", "audio_stt" -> "whisper-1", "audio_tts" -> "tts-1", "moderation" -> "omni-moderation-latest"),
    "compactifai" -> Map("audio_stt" -> "cai-whisper-large-v3-turbo-slim"),
    "deepinfra" -> Map("embedding" -> "Qwen/Qwen3-Embedding-8B", "image" -> "black-forest-labs/FLUX-1-schnell", "audio_stt" -> "openai/whisper-large-v3-turbo", "audio_tts" -> "hexgrad/Kokoro-82M"),
    "featherless-ai" -> Map("embedding" -> "Qwen/Qwen3-Embedding-8B", "audio_tts" -> "hexgrad/Kokoro-82M"),
    "fireworks-ai" -> Map("embedding" -> "nomic-ai/nomic-embed-text-v1.5"),
    "friendliai" -> Map("audio_stt" -> "openai/whisper-large-v3"),
    "nebius" -> Map("embedding" -> "BAAI/bge-en-icl"),
    "nscale" -> Map("embedding" -> "Qwen3-Embedding-8B", "image" -> "black-forest-labs/FLUX.1-schnell"),
    "nvidia-nim" -> Map("embedding" -> "nvidia/nemotron-3-embed-1b"),
    "together-ai" -> Map("embedding" -> "intfloat/multilingual-e5-large-instruct", "image" -> "black-forest-labs/FLUX.2-pro", "audio_stt" -> "openai/whisper-large-v3", "audio_tts" -> "cartesia/sonic-3"),
    "zai" -> Map("image" -> "glm-image", "audio_stt" -> "glm-asr-2512"),
    "openrouter" -> Map("embedding" -> "openai/text-embedding-3-small"),
  )

  // which side of the audio capability each provider serves
  private val audioModes: Map[String, Seq[String]] = Map(
    "openai" -> Seq("tts", "stt"), "azure-openai" -> Seq("tts", "stt"), "cloud-temple" -> Seq("tts", "stt"), "groq" -> Seq("tts", "stt"),
    "elevenlabs" -> Seq("tts", "stt"), "openrouter" -> Seq("tts", "stt"), "openai-compatible" -> Seq("tts", "stt"),
    "mistral" -> Seq("stt"), "alphaedge" -> Seq("stt"), "ovh-ai-endpoints" -> Seq("stt"), "scaleway" -> Seq("stt"), "cohere" -> Seq("stt"),
  )

  private def audioModesOf(id: String): Seq[String] = audioModes.get(id).orElse {
    OpenAiLikeProviders.find(id).map(d => Seq("tts").filter(_ => d.supportsTextToSpeech) ++ Seq("stt").filter(_ => d.supportsSpeechToText))
  }.getOrElse(Seq.empty)

  private def blueprintFor(id: String): Blueprint = blueprints.get(id).orElse {
    OpenAiLikeProviders.find(id).map(d => Blueprint(d.baseUrl.some, Map("text" -> "") ++ likeModels.getOrElse(id, Map.empty)))
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
      "audio_modes" -> audioModesOf(entry.id),
      "fields" -> JsArray(bp.fields),
      "openai_like" -> OpenAiLikeProviders.find(entry.id).isDefined,
    )
  }.sortBy(_.select("label").asOptString.getOrElse("").toLowerCase))

  // the catalog with what the gateway knows of each provider: api, models of the models.dev catalog, prices
  def enrichedJson(using env: Env, ec: ExecutionContext): Future[JsArray] = {
    env.adminExtensions.extension[AiExtension].map(_.modelsCatalog.load()).getOrElse(None.vfuture).map { _ =>
      JsArray(json.value.map(entry => entry.asObject ++ Json.obj("insights" -> ProviderInsights.of(entry.select("id").asString))))
    }
  }
}
