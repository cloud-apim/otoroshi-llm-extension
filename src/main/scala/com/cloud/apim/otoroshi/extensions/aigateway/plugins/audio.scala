package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.AudioModelClientTextToSpeechInputOptions
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiProvider, AudioModel}
import com.cloud.apim.otoroshi.extensions.aigateway.plugins.AiPluginRefsConfig
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.http._
import play.api.libs.json._
import play.api.mvc.Results

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
              client.textToSpeech(options, jsonBody).map {
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