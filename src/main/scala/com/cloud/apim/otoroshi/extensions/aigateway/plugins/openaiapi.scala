package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import akka.stream.Materializer
import com.cloud.apim.otoroshi.extensions.aigateway.plugins.AiPluginRefsConfig
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json._
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class OpenAiCompatApiConfig(
  languageModelRefs: Seq[String],
  audioModelRefs: Seq[String],
  imageModelRefs: Seq[String],
  embeddingModelRefs: Seq[String],
  moderationModelRefs: Seq[String],
  contextRefs: Seq[String],
  maxSizeUpload: Long,
  decodeImages: Boolean
) extends NgPluginConfig {
  def json: JsValue = OpenAiCompatApiConfig.format.writes(this)
}

object OpenAiCompatApiConfig {

  val configFlow: Seq[String] = Seq("language_model_refs", "audio_model_refs", "image_model_refs", "embedding_model_refs", "moderation_model_refs", "context_refs", "max_size_upload", "decode_images")

  def configSchema: Option[JsObject] = Some(Json.obj(
    "language_model_refs" -> Json.obj(
      "type" -> "select",
      "array" -> true,
      "label" -> "LLM providers",
      "props" -> Json.obj(
        "optionsFrom" -> "/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/providers",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      ),
    ),
    "audio_model_refs" -> Json.obj(
      "type" -> "select",
      "array" -> true,
      "label" -> "Audio models",
      "props" -> Json.obj(
        "optionsFrom" -> "/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/audio-models",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      ),
    ),
    "image_model_refs" -> Json.obj(
      "type" -> "select",
      "array" -> true,
      "label" -> "Image models",
      "props" -> Json.obj(
        "optionsFrom" -> "/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/image-models",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      ),
    ),
    "embedding_model_refs" -> Json.obj(
      "type" -> "select",
      "array" -> true,
      "label" -> "Embedding models",
      "props" -> Json.obj(
        "optionsFrom" -> "/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/embedding-models",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      ),
    ),
    "moderation_model_refs" -> Json.obj(
      "type" -> "select",
      "array" -> true,
      "label" -> "Moderation models",
      "props" -> Json.obj(
        "optionsFrom" -> "/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/moderation-models",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      ),
    ),
    "context_refs" -> Json.obj(
      "type" -> "select",
      "array" -> true,
      "label" -> "Prompt contexts",
      "props" -> Json.obj(
        "optionsFrom" -> "/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/prompt-contexts",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      ),
    ),
    "max_size_upload" -> Json.obj(
      "type" -> "number",
      "label" -> "Max file upload size (bytes)"
    ),
    "decode_images" -> Json.obj(
      "type" -> "bool",
      "label" -> "Decode base64 images",
      "help" -> "Only work for single image results base64 encoded",
      "props" -> Json.obj(
        "help" -> "Only work for single image results base64 encoded",
      )
    ),
  ))

  val default = OpenAiCompatApiConfig(
    languageModelRefs = Seq.empty,
    audioModelRefs = Seq.empty,
    imageModelRefs = Seq.empty,
    embeddingModelRefs = Seq.empty,
    moderationModelRefs = Seq.empty,
    contextRefs = Seq.empty,
    maxSizeUpload = 100 * 1024 * 1024,
    decodeImages = false
  )

  val format = new Format[OpenAiCompatApiConfig] {
    override def writes(o: OpenAiCompatApiConfig): JsValue = Json.obj(
      "language_model_refs" -> o.languageModelRefs,
      "audio_model_refs" -> o.audioModelRefs,
      "image_model_refs" -> o.imageModelRefs,
      "embedding_model_refs" -> o.embeddingModelRefs,
      "moderation_model_refs" -> o.moderationModelRefs,
      "context_refs" -> o.contextRefs,
      "max_size_upload" -> o.maxSizeUpload,
      "decode_images" -> o.decodeImages,
    )
    override def reads(json: JsValue): JsResult[OpenAiCompatApiConfig] = Try {
      OpenAiCompatApiConfig(
        languageModelRefs = json.select("language_model_refs").asOpt[Seq[String]].getOrElse(Seq.empty),
        audioModelRefs = json.select("audio_model_refs").asOpt[Seq[String]].getOrElse(Seq.empty),
        imageModelRefs = json.select("image_model_refs").asOpt[Seq[String]].getOrElse(Seq.empty),
        embeddingModelRefs = json.select("embedding_model_refs").asOpt[Seq[String]].getOrElse(Seq.empty),
        moderationModelRefs = json.select("moderation_model_refs").asOpt[Seq[String]].getOrElse(Seq.empty),
        contextRefs = json.select("context_refs").asOpt[Seq[String]].getOrElse(Seq.empty),
        maxSizeUpload = json.select("max_size_upload").asOpt[Long].getOrElse(default.maxSizeUpload),
        decodeImages = json.select("decode_images").asOpt[Boolean].getOrElse(false),
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

class OpenAiCompatApi extends NgBackendCall {

  override def name: String = "Cloud APIM - LLM OpenAI Compatible API"
  override def description: Option[String] = "Unified OpenAI compatible API backend that routes to the appropriate handler based on the request path".some
  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))
  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)
  override def useDelegates: Boolean = false
  override def defaultConfigObject: Option[NgPluginConfig] = Some(OpenAiCompatApiConfig.default)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = OpenAiCompatApiConfig.configFlow
  override def configSchema: Option[JsObject] = OpenAiCompatApiConfig.configSchema

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'LLM OpenAI Compatible API' plugin is available !")
    }
    ().vfuture
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(OpenAiCompatApiConfig.format).getOrElse(OpenAiCompatApiConfig.default)
    val path = ctx.request.path
    val method = ctx.request.method.toUpperCase()
    if (method == "GET" && path.endsWith("/contexts")) {
      val contexts = env.adminExtensions.extension[AiExtension].map(_.states.allContexts()).getOrElse(Seq.empty)
        .filter(c => config.contextRefs.contains(c.id))
        .map(c => Json.obj("id" -> c.id, "name" -> c.name))
      Right(BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok(JsArray(contexts))), None)).vfuture

    } else if (method == "GET" && path.endsWith("/models")) {
      val providerConfig = AiPluginRefsConfig(config.languageModelRefs)
      OpenAiCompatProvidersWithModels.handleRequest(providerConfig, ctx)

    } else if (method == "POST" && path.endsWith("/audio/speech")) {
      val audioConfig = OpenAICompatTextToSpeechConfig(config.audioModelRefs)
      OpenAICompatTextToSpeech.handleRequest(audioConfig, ctx)

    } else if (method == "POST" && path.endsWith("/audio/transcriptions")) {
      val audioConfig = OpenAICompatSpeechToTextConfig(config.audioModelRefs, config.maxSizeUpload)
      OpenAICompatSpeechToText.handleRequest(audioConfig, ctx)

    } else if (method == "POST" && path.endsWith("/audio/translations")) {
      val audioConfig = OpenAICompatSpeechToTextConfig(config.audioModelRefs, config.maxSizeUpload)
      OpenAICompatTranslation.handleRequest(audioConfig, ctx)

    } else if (method == "POST" && path.endsWith("/images/generations")) {
      val imageConfig = OpenAiCompatImagesGenConfig(config.imageModelRefs, config.decodeImages)
      OpenAICompatImagesGen.handleRequest(imageConfig, ctx)

    } else if (method == "POST" && path.endsWith("/images/edits")) {
      val imageConfig = OpenAICompatImagesEditConfig(config.imageModelRefs, config.maxSizeUpload)
      OpenAICompatImagesEdit.handleRequest(imageConfig, ctx)

    } else if (method == "POST" && path.endsWith("/embeddings")) {
      val embeddingConfig = OpenAICompatEmbeddingConfig(config.embeddingModelRefs)
      OpenAICompatEmbedding.handleRequest(embeddingConfig, ctx)

    } else if (method == "POST" && path.endsWith("/moderations")) {
      val moderationConfig = OpenAICompatModerationConfig(config.moderationModelRefs)
      OpenAICompatModeration.handleRequest(moderationConfig, ctx)

    } else if (method == "POST" && path.endsWith("/responses")) {
      val providerConfig = AiPluginRefsConfig(config.languageModelRefs)
      OpenAiResponsesProxy.handleRequest(providerConfig, ctx)

    } else if (method == "POST" && path.endsWith("/chat/completions")) {
      val providerConfig = AiPluginRefsConfig(config.languageModelRefs)
      OpenAiCompatProxy.handleRequest(providerConfig, ctx)

    } else if (method == "POST" && path.endsWith("/messages")) {
      val providerConfig = AiPluginRefsConfig(config.languageModelRefs)
      AnthropicCompatProxy.handleRequest(providerConfig, ctx)

    } else {
      Left(NgProxyEngineError.NgResultProxyEngineError(Results.NotFound(Json.obj("error" -> "not_found", "error_details" -> s"no handler found for ${method} ${path}")))).vfuture
    }
  }
}
