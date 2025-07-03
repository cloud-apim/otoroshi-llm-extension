package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import akka.stream.Materializer
import akka.stream.scaladsl.FileIO
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AudioModel
import com.cloud.apim.otoroshi.extensions.aigateway.{AudioModelClientSpeechToTextInputOptions, AudioModelClientTextToSpeechInputOptions, AudioModelClientTranslationInputOptions}
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.http._
import play.api.libs.json._
import play.api.mvc.{RequestHeader, Result, Results}
import play.core.parsers.Multipart

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class OpenAICompatTextToSpeechConfig(refs: Seq[String]) extends NgPluginConfig {
  def json: JsValue = OpenAICompatTextToSpeechConfig.format.writes(this)
}

object OpenAICompatTextToSpeechConfig {
  val configFlow: Seq[String] = Seq("refs")
  def configSchema: Option[JsObject] = Some(Json.obj(
    "refs" -> Json.obj(
      "type" -> "select",
      "array" -> true,
      "label" -> s"Audio models",
      "props" -> Json.obj(
        "optionsFrom" -> s"/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/audio-models",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      ),
    )
  ))
  val default = OpenAICompatTextToSpeechConfig(Seq.empty)
  val format = new Format[OpenAICompatTextToSpeechConfig] {
    override def writes(o: OpenAICompatTextToSpeechConfig): JsValue = Json.obj("refs" -> o.refs)
    override def reads(json: JsValue): JsResult[OpenAICompatTextToSpeechConfig] = Try {
      val refs = json.select("refs").asOpt[Seq[String]].getOrElse(Seq.empty)
      OpenAICompatTextToSpeechConfig(
        refs = refs
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }

  def getProvidersMap(config: OpenAICompatTextToSpeechConfig)(implicit ec: ExecutionContext, env: Env): (Map[String, AudioModel], Map[String, AudioModel]) = {
    val ext = env.adminExtensions.extension[AiExtension].get
    val providers = config.refs.flatMap(ref => ext.states.audioModel(ref))
    val providersByName = providers.map { provider =>
      val name = provider.slugName
      (name, provider)
    }.toMap
    val providersById = providers.map(p => (p.id, p)).toMap
    (providersById, providersByName)
  }

  def extractProviderFromModelInBody(_jsonBody: JsValue, config: OpenAICompatTextToSpeechConfig)(implicit ec: ExecutionContext, env: Env): JsValue = {
    _jsonBody.select("model").asOpt[String] match {
      case Some(value) if value.contains("###") => {
        val parts = value.split("###")
        val name = parts(0)
        val model = parts(1)
        val (providersById, providersByName) = OpenAICompatTextToSpeechConfig.getProvidersMap(config)
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
      case Some(value) if value.contains("/")=> {
        val parts = value.split("/")
        val name = parts(0)
        val model = parts.tail.mkString("/")
        val (providersById, providersByName) = OpenAICompatTextToSpeechConfig.getProvidersMap(config)
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

class OpenAICompatTextToSpeech extends NgBackendCall {

  override def name: String = "Cloud APIM - Text to speech backend"
  override def description: Option[String] = "Delegates call to a provider to generate audio files from text".some
  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))
  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)
  override def useDelegates: Boolean = false
  override def defaultConfigObject: Option[NgPluginConfig] = Some(OpenAICompatTextToSpeechConfig.default)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = OpenAICompatTextToSpeechConfig.configFlow
  override def configSchema: Option[JsObject] = OpenAICompatTextToSpeechConfig.configSchema

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'Text to speech backend' plugin is available !")
    }
    ().vfuture
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
      val _jsonBody = bodyRaw.utf8String.parseJson
      val config = ctx.cachedConfig(internalName)(OpenAICompatTextToSpeechConfig.format).getOrElse(OpenAICompatTextToSpeechConfig.default)
      val jsonBody: JsObject = OpenAICompatTextToSpeechConfig.extractProviderFromModelInBody(_jsonBody, config).asObject
      val provider: Option[AudioModel] = jsonBody.select("provider").asOpt[String].filter(v => config.refs.contains(v)).flatMap { r =>
        ext.states.audioModel(r)
      }.orElse(
        config.refs.headOption.flatMap { r =>
          ext.states.audioModel(r)
        }
      )
      provider match {
        case None => NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_error", "error_details" -> "provider not found"))).leftf
        case Some(provider) => {
          provider.getAudioModelClient() match {
            case None => NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_error", "error_details" -> "failed to create client"))).leftf
            case Some(client) if !client.supportsTts => NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_error", "error_details" -> "provider does not support text to speech"))).leftf
            case Some(client) => {
              val options = AudioModelClientTextToSpeechInputOptions.format.reads(jsonBody).getOrElse(AudioModelClientTextToSpeechInputOptions(""))
              client.textToSpeech(options, jsonBody, ctx.attrs).map {
                case Left(err) => NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_error", "error_details" -> err))).left
                case Right((source, contentType)) => {
                  val result = Results.Status(200).streamed(source, None, Some(contentType))
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

case class OpenAICompatSpeechToTextConfig(refs: Seq[String], maxSizeUpload: Long) extends NgPluginConfig {
  def json: JsValue = OpenAICompatSpeechToTextConfig.format.writes(this)
}

object OpenAICompatSpeechToTextConfig {
  val configFlow: Seq[String] = Seq("refs", "max_size_upload")
  def configSchema: Option[JsObject] = Some(Json.obj(
    "max_size_upload" -> Json.obj(
      "type" -> "number",
      "label" -> "Max Audio file size"
    ),
    "refs" -> Json.obj(
      "type" -> "select",
      "array" -> true,
      "label" -> s"Audio models",
      "props" -> Json.obj(
        "optionsFrom" -> s"/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/audio-models",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      ),
    )
  ))
  val default = OpenAICompatSpeechToTextConfig(Seq.empty, 100 * 1024 * 1024)
  val format = new Format[OpenAICompatSpeechToTextConfig] {
    override def writes(o: OpenAICompatSpeechToTextConfig): JsValue = Json.obj("refs" -> o.refs, "max_size_upload" -> o.maxSizeUpload)
    override def reads(json: JsValue): JsResult[OpenAICompatSpeechToTextConfig] = Try {
      OpenAICompatSpeechToTextConfig(
        refs = json.select("refs").asOpt[Seq[String]].getOrElse(Seq.empty),
        maxSizeUpload = json.select("max_size_upload").asOpt[Long].getOrElse(default.maxSizeUpload),
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }

  def getProvidersMap(config: OpenAICompatSpeechToTextConfig)(implicit ec: ExecutionContext, env: Env): (Map[String, AudioModel], Map[String, AudioModel]) = {
    val ext = env.adminExtensions.extension[AiExtension].get
    val providers = config.refs.flatMap(ref => ext.states.audioModel(ref))
    val providersByName = providers.map { provider =>
      val name = provider.slugName
      (name, provider)
    }.toMap
    val providersById = providers.map(p => (p.id, p)).toMap
    (providersById, providersByName)
  }

  def extractProviderFromModelInBody(_jsonBody: JsValue, config: OpenAICompatSpeechToTextConfig)(implicit ec: ExecutionContext, env: Env): JsValue = {
    _jsonBody.select("model").asOpt[String] match {
      case Some(value) if value.contains("###") => {
        val parts = value.split("###")
        val name = parts(0)
        val model = parts(1)
        val (providersById, providersByName) = OpenAICompatSpeechToTextConfig.getProvidersMap(config)
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
      case Some(value) if value.contains("/")=> {
        val parts = value.split("/")
        val name = parts(0)
        val model = parts.tail.mkString("/")
        val (providersById, providersByName) = OpenAICompatSpeechToTextConfig.getProvidersMap(config)
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

class OpenAICompatSpeechToText extends NgBackendCall {

  override def name: String = "Cloud APIM - Speech to text backend"
  override def description: Option[String] = "Delegates call to a provider to generate text from audio files".some
  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))
  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)
  override def useDelegates: Boolean = false
  override def defaultConfigObject: Option[NgPluginConfig] = Some(OpenAICompatSpeechToTextConfig.default)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = OpenAICompatSpeechToTextConfig.configFlow
  override def configSchema: Option[JsObject] = OpenAICompatSpeechToTextConfig.configSchema

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'Speech to text backend' plugin is available !")
    }
    ().vfuture
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    val config = ctx.cachedConfig(internalName)(OpenAICompatSpeechToTextConfig.format).getOrElse(OpenAICompatSpeechToTextConfig.default)
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
          case ("temperature", value) => {
            _jsonBody = _jsonBody ++ Json.obj("temperature" -> value.head.toDouble)
          }
          case (key, value) if value.length == 1 => {
            _jsonBody = _jsonBody ++ Json.obj(key -> value.head.json)
          }
          case (key, value) => {
            _jsonBody = _jsonBody ++ Json.obj(key -> JsArray(value.map(_.json)))
          }
        }

        form.file("file") match {
          case None => NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(Json.obj("error" -> "bad_request", "error_details" -> "no file present"))).leftf
          case Some(file) => {
            val jsonBody: JsObject = OpenAICompatSpeechToTextConfig.extractProviderFromModelInBody(_jsonBody, config).asObject
            val provider: Option[AudioModel] = jsonBody.select("provider").asOpt[String].filter(v => config.refs.contains(v)).flatMap { r =>
              ext.states.audioModel(r)
            }.orElse(
              config.refs.headOption.flatMap { r =>
                ext.states.audioModel(r)
              }
            )
            provider match {
              case None => NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_error", "error_details" -> "provider not found"))).leftf
              case Some(provider) => {
                provider.getAudioModelClient() match {
                  case None => NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_error", "error_details" -> "failed to create client"))).leftf
                  case Some(client) if !client.supportsTts => NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_error", "error_details" -> "provider does not support text to speech"))).leftf
                  case Some(client) => {
                    val _options = AudioModelClientSpeechToTextInputOptions.format.reads(jsonBody).get
                    val options = _options.copy(
                      file = FileIO.fromPath(file.ref.path),
                      fileContentType = file.contentType.getOrElse("audio/mp3"),
                      fileLength = file.fileSize,
                      fileName = file.filename.some,
                    )
                    client.speechToText(options, jsonBody, ctx.attrs).map {
                      case Left(err) => NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_error", "error_details" -> err))).left
                      case Right(transcription) => {
                        val result = Results.Status(200).apply(Json.obj(
                          "text" -> transcription.transcribedText
                        ))
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
  }
}

class OpenAICompatTranslation extends NgBackendCall {

  override def name: String = "Cloud APIM - Audio translation backend"
  override def description: Option[String] = "Delegates call to a provider to generate english text from audio files".some
  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))
  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)
  override def useDelegates: Boolean = false
  override def defaultConfigObject: Option[NgPluginConfig] = Some(OpenAICompatSpeechToTextConfig.default)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = OpenAICompatSpeechToTextConfig.configFlow
  override def configSchema: Option[JsObject] = OpenAICompatSpeechToTextConfig.configSchema

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'Audio translation backend' plugin is available !")
    }
    ().vfuture
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    val config = ctx.cachedConfig(internalName)(OpenAICompatSpeechToTextConfig.format).getOrElse(OpenAICompatSpeechToTextConfig.default)
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
          case ("temperature", value) => {
            _jsonBody = _jsonBody ++ Json.obj("temperature" -> value.head.toDouble)
          }
          case (key, value) if value.length == 1 => {
            _jsonBody = _jsonBody ++ Json.obj(key -> value.head.json)
          }
          case (key, value) => {
            _jsonBody = _jsonBody ++ Json.obj(key -> JsArray(value.map(_.json)))
          }
        }

        form.file("file") match {
          case None => NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(Json.obj("error" -> "bad_request", "error_details" -> "no file present"))).leftf
          case Some(file) => {
            val jsonBody: JsObject = OpenAICompatSpeechToTextConfig.extractProviderFromModelInBody(_jsonBody, config).asObject
            val provider: Option[AudioModel] = jsonBody.select("provider").asOpt[String].filter(v => config.refs.contains(v)).flatMap { r =>
              ext.states.audioModel(r)
            }.orElse(
              config.refs.headOption.flatMap { r =>
                ext.states.audioModel(r)
              }
            )
            provider match {
              case None => NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_error", "error_details" -> "provider not found"))).leftf
              case Some(provider) => {
                provider.getAudioModelClient() match {
                  case None => NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_error", "error_details" -> "failed to create client"))).leftf
                  case Some(client) if !client.supportsTts => NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_error", "error_details" -> "provider does not support text to speech"))).leftf
                  case Some(client) => {
                    val _options = AudioModelClientTranslationInputOptions.format.reads(jsonBody).get
                    val options = _options.copy(
                      file = FileIO.fromPath(file.ref.path),
                      fileContentType = file.contentType.getOrElse("audio/mp3"),
                      fileLength = file.fileSize,
                      fileName = file.filename.some,
                    )
                    client.translate(options, jsonBody, ctx.attrs).map {
                      case Left(err) => NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_error", "error_details" -> err))).left
                      case Right(transcription) => {
                        val result = Results.Status(200).apply(Json.obj(
                          "text" -> transcription.transcribedText
                        ))
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
  }
}