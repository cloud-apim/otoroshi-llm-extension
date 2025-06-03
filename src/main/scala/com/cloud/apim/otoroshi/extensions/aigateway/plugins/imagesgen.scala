package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import akka.stream.Materializer
import akka.stream.scaladsl.FileIO
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AudioModel, ImageModel}
import com.cloud.apim.otoroshi.extensions.aigateway.{ImageFile, ImageModelClientEditionInputOptions, ImageModelClientGenerationInputOptions}
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.http.HttpErrorHandler
import play.api.libs.json._
import play.api.mvc.{RequestHeader, Result, Results}
import play.core.parsers.Multipart

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class OpenAiCompatImagesGenConfig(refs: Seq[String], decode: Boolean) extends NgPluginConfig {
  def json: JsValue = OpenAiCompatImagesGenConfig.format.writes(this)
}

object OpenAiCompatImagesGenConfig {
  val configFlow: Seq[String] = Seq("refs", "decode")

  def configSchema: Option[JsObject] = Some(Json.obj(
    "decode" -> Json.obj(
      "type" -> "bool",
      "label" -> "Decode base64",
      "help" -> "Only work for single image results base64 encoded",
      "props" -> Json.obj(
        "help" -> "Only work for single image results base64 encoded",
      )
    ),
    "refs" -> Json.obj(
      "type" -> "select",
      "array" -> true,
      "label" -> s"Images Generation",
      "props" -> Json.obj(
        "optionsFrom" -> s"/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/image-models",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      ),
    )
  ))

  val default = OpenAiCompatImagesGenConfig(Seq.empty, false)
  val format = new Format[OpenAiCompatImagesGenConfig] {
    override def writes(o: OpenAiCompatImagesGenConfig): JsValue = Json.obj("refs" -> o.refs, "decode" -> o.decode)

    override def reads(json: JsValue): JsResult[OpenAiCompatImagesGenConfig] = Try {
      val refs = json.select("refs").asOpt[Seq[String]].getOrElse(Seq.empty)
      OpenAiCompatImagesGenConfig(
        refs = refs,
        decode = json.select("decode").asOpt[Boolean].getOrElse(false)
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }

  def getProvidersMap(config: OpenAiCompatImagesGenConfig)(implicit ec: ExecutionContext, env: Env): (Map[String, ImageModel], Map[String, ImageModel]) = {
    val ext = env.adminExtensions.extension[AiExtension].get
    val providers = config.refs.flatMap(ref => ext.states.imageModel(ref))
    val providersByName = providers.map { provider =>
      val name = provider.slugName
      (name, provider)
    }.toMap
    val providersById = providers.map(p => (p.id, p)).toMap
    (providersById, providersByName)
  }

  def extractProviderFromModelInBody(_jsonBody: JsValue, config: OpenAiCompatImagesGenConfig)(implicit ec: ExecutionContext, env: Env): JsValue = {
    _jsonBody.select("model").asOpt[String] match {
      case Some(value) if value.contains("###") => {
        val parts = value.split("###")
        val name = parts(0)
        val model = parts(1)
        val (providersById, providersByName) = OpenAiCompatImagesGenConfig.getProvidersMap(config)
        providersById.get(name) match {
          case Some(prov) => _jsonBody.asObject ++ Json.obj("provider" -> prov.id, "model" -> model)
          case None => {
            providersByName.get(name) match {
              case None => _jsonBody
              case Some(prov) => _jsonBody.asObject ++ Json.obj("provider" -> prov.id, "model" -> model)
            }
          }
        }
      }
      case Some(value) if value.contains("/") => {
        val parts = value.split("/")
        val name = parts(0)
        val model = parts.tail.mkString("/")
        val (providersById, providersByName) = OpenAiCompatImagesGenConfig.getProvidersMap(config)
        providersById.get(name) match {
          case Some(prov) => _jsonBody.asObject ++ Json.obj("provider" -> prov.id, "model" -> model)
          case None => {
            providersByName.get(name) match {
              case None => _jsonBody
              case Some(prov) => _jsonBody.asObject ++ Json.obj("provider" -> prov.id, "model" -> model)
            }
          }
        }
      }
      case _ => _jsonBody
    }
  }
}

class OpenAICompatImagesGen extends NgBackendCall {

  override def name: String = "Cloud APIM - Image generation backend"

  override def description: Option[String] = "Delegates call to a LLM provider to generate images".some

  override def core: Boolean = false

  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand

  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))

  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)

  override def useDelegates: Boolean = false

  override def defaultConfigObject: Option[NgPluginConfig] = Some(OpenAiCompatImagesGenConfig.default)

  override def noJsForm: Boolean = true

  override def configFlow: Seq[String] = OpenAiCompatImagesGenConfig.configFlow

  override def configSchema: Option[JsObject] = OpenAiCompatImagesGenConfig.configSchema

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'Image generation backend' plugin is available !")
    }
    ().vfuture
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    val config = ctx.cachedConfig(internalName)(OpenAiCompatImagesGenConfig.format).getOrElse(OpenAiCompatImagesGenConfig.default)
    ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
      val _jsonBody = bodyRaw.utf8String.parseJson
      val jsonBody: JsObject = OpenAiCompatImagesGenConfig.extractProviderFromModelInBody(_jsonBody, config).asObject
      val provider: Option[ImageModel] = jsonBody.select("provider").asOpt[String].filter(v => config.refs.contains(v)).flatMap { r =>
        ext.states.imageModel(r)
      }.orElse(
        config.refs.headOption.flatMap { r =>
          ext.states.imageModel(r)
        }
      )
      provider match {
        case None => NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_error", "error_details" -> "provider not found"))).leftf
        case Some(provider) => {
          provider.getImageModelClient() match {
            case None => NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_error", "error_details" -> "failed to create client"))).leftf
            case Some(client) if !client.supportsGeneration => NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_error", "error_details" -> "provider does not support image generation"))).leftf
            case Some(client) => {
              val options = ImageModelClientGenerationInputOptions.format.reads(jsonBody).getOrElse(ImageModelClientGenerationInputOptions(""))
              client.generate(options, jsonBody).map {
                case Left(err) => NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_error", "error_details" -> err))).left
                case Right(imageGen) => {
                  if (config.decode && imageGen.images.length == 1 && imageGen.images.head.b64Json.isDefined) {
                    val bytes = imageGen.images.head.b64Json.get.byteString.decodeBase64
                    val ctype = options.outputFormat.getOrElse("image/png")
                    Right(BackendCallResponse.apply(NgPluginHttpResponse.fromResult(Results.Ok(bytes).as(ctype)), None))
                  } else {
                    Right(BackendCallResponse.apply(NgPluginHttpResponse.fromResult(Results.Ok(imageGen.toOpenAiJson)), None))
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

case class OpenAICompatImagesEditConfig(refs: Seq[String], maxSizeUpload: Long) extends NgPluginConfig {
  def json: JsValue = OpenAICompatImagesEditConfig.format.writes(this)
}

object OpenAICompatImagesEditConfig {
  val configFlow: Seq[String] = Seq("refs", "max_size_upload")
  def configSchema: Option[JsObject] = Some(Json.obj(
    "max_size_upload" -> Json.obj(
      "type" -> "number",
      "label" -> "Max Audio file size"
    ),
    "refs" -> Json.obj(
      "type" -> "select",
      "array" -> true,
      "label" -> s"Images Generation",
      "props" -> Json.obj(
        "optionsFrom" -> s"/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/image-models",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      ),
    )
  ))

  val default = OpenAICompatImagesEditConfig(Seq.empty, 100 * 1024 * 1024)
  val format = new Format[OpenAICompatImagesEditConfig] {
    override def writes(o: OpenAICompatImagesEditConfig): JsValue = Json.obj("refs" -> o.refs, "max_size_upload" -> o.maxSizeUpload)
    override def reads(json: JsValue): JsResult[OpenAICompatImagesEditConfig] = Try {
      OpenAICompatImagesEditConfig(
        refs = json.select("refs").asOpt[Seq[String]].getOrElse(Seq.empty),
        maxSizeUpload = json.select("max_size_upload").asOpt[Long].getOrElse(default.maxSizeUpload),
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }

  def getProvidersMap(config: OpenAICompatImagesEditConfig)(implicit ec: ExecutionContext, env: Env): (Map[String, ImageModel], Map[String, ImageModel]) = {
    val ext = env.adminExtensions.extension[AiExtension].get
    val providers = config.refs.flatMap(ref => ext.states.imageModel(ref))
    val providersByName = providers.map { provider =>
      val name = provider.slugName
      (name, provider)
    }.toMap
    val providersById = providers.map(p => (p.id, p)).toMap
    (providersById, providersByName)
  }

  def extractProviderFromModelInBody(_jsonBody: JsValue, config: OpenAICompatImagesEditConfig)(implicit ec: ExecutionContext, env: Env): JsValue = {
    _jsonBody.select("model").asOpt[String] match {
      case Some(value) if value.contains("###") => {
        val parts = value.split("###")
        val name = parts(0)
        val model = parts(1)
        val (providersById, providersByName) = OpenAICompatImagesEditConfig.getProvidersMap(config)
        providersById.get(name) match {
          case Some(prov) => _jsonBody.asObject ++ Json.obj("provider" -> prov.id, "model" -> model)
          case None => {
            providersByName.get(name) match {
              case None => _jsonBody
              case Some(prov) => _jsonBody.asObject ++ Json.obj("provider" -> prov.id, "model" -> model)
            }
          }
        }
      }
      case Some(value) if value.contains("/") => {
        val parts = value.split("/")
        val name = parts(0)
        val model = parts.tail.mkString("/")
        val (providersById, providersByName) = OpenAICompatImagesEditConfig.getProvidersMap(config)
        providersById.get(name) match {
          case Some(prov) => _jsonBody.asObject ++ Json.obj("provider" -> prov.id, "model" -> model)
          case None => {
            providersByName.get(name) match {
              case None => _jsonBody
              case Some(prov) => _jsonBody.asObject ++ Json.obj("provider" -> prov.id, "model" -> model)
            }
          }
        }
      }
      case _ => _jsonBody
    }
  }
}


class OpenAICompatImagesEdit extends NgBackendCall {

  override def name: String = "Cloud APIM - Image edition backend"

  override def description: Option[String] = "Delegates call to a LLM provider to edit images".some

  override def core: Boolean = false

  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand

  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))

  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)

  override def useDelegates: Boolean = false

  override def defaultConfigObject: Option[NgPluginConfig] = Some(OpenAiCompatImagesGenConfig.default)

  override def noJsForm: Boolean = true

  override def configFlow: Seq[String] = OpenAiCompatImagesGenConfig.configFlow

  override def configSchema: Option[JsObject] = OpenAiCompatImagesGenConfig.configSchema

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'Image edition backend' plugin is available !")
    }
    ().vfuture
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    val config = ctx.cachedConfig(internalName)(OpenAICompatImagesEditConfig.format).getOrElse(OpenAICompatImagesEditConfig.default)
    Multipart.multipartParser(
      config.maxSizeUpload,
      allowEmptyFiles = false,
      filePartHandler = Multipart.handleFilePartAsTemporaryFile(play.api.libs.Files.SingletonTemporaryFileCreator),
      errorHandler = new HttpErrorHandler {
        override def onClientError(request: RequestHeader, statusCode: Int, message: String): Future[Result] = Results.Status(statusCode).apply(message).vfuture
        override def onServerError(request: RequestHeader, exception: Throwable): Future[Result] = Results.InternalServerError(exception.getMessage).vfuture
      }
    ).apply(ctx.rawRequest).run(ctx.request.body).flatMap {
      case Left(err) => {
        NgProxyEngineError.NgResultProxyEngineError.apply(err).leftf
      }
      case Right(form) => {
        var _jsonBody = Json.obj()
        form.dataParts.foreach {
          case (key, value) if value.length == 1 => {
            _jsonBody = _jsonBody ++ Json.obj(key -> value.head.json)
          }
          case (key, value) => {
            _jsonBody = _jsonBody ++ Json.obj(key -> JsArray(value.map(_.json)))
          }
        }
        val jsonBody: JsObject = OpenAICompatImagesEditConfig.extractProviderFromModelInBody(_jsonBody, config).asObject
        val provider: Option[ImageModel] = jsonBody.select("provider").asOpt[String].filter(v => config.refs.contains(v)).flatMap { r =>
          ext.states.imageModel(r)
        }.orElse(
          config.refs.headOption.flatMap { r =>
            ext.states.imageModel(r)
          }
        )
        provider match {
          case None => NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_error", "error_details" -> "provider not found"))).leftf
          case Some(provider) => {
            provider.getImageModelClient() match {
              case None => NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_error", "error_details" -> "failed to create client"))).leftf
              case Some(client) if !client.supportsEdit => NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_error", "error_details" -> "provider does not support text to speech"))).leftf
              case Some(client) => {
                val _options = ImageModelClientEditionInputOptions.format.reads(jsonBody).get
                val options = _options.copy(
                  images = form.files.filter(_.key == "image").map(file => ImageFile(
                    bytes = FileIO.fromPath(file.ref.path),
                    name = file.filename.some,
                    contentType = file.contentType.getOrElse("audio/mp3"),
                    length = file.fileSize,
                  )).toList
                )
                client.edit(options, jsonBody).map {
                  case Left(err) => NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_error", "error_details" -> err))).left
                  case Right(edition) => {
                    val result = Results.Status(200).apply(edition.toOpenAiJson)
                    Right(BackendCallResponse.apply(NgPluginHttpResponse.fromResult(result), None))
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}