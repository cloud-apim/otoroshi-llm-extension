package com.cloud.apim.otoroshi.extensions.aigateway.plugins

import akka.stream.Materializer
import akka.util.ByteString
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json._
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class OpenAICompatAudioModelConfig(refs: Seq[String]) extends NgPluginConfig {
  def json: JsValue = OpenAICompatAudioModelConfig.format.writes(this)
}

object OpenAICompatAudioModelConfig {
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
  val default = OpenAICompatAudioModelConfig(Seq.empty)
  val format = new Format[OpenAICompatAudioModelConfig] {
    override def writes(o: OpenAICompatAudioModelConfig): JsValue = Json.obj("refs" -> o.refs)
    override def reads(json: JsValue): JsResult[OpenAICompatAudioModelConfig] = Try {
      val refs = json.select("refs").asOpt[Seq[String]].getOrElse(Seq.empty)
      OpenAICompatAudioModelConfig(
        refs = refs
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

class OpenAICompatAudioModel extends NgBackendCall {

  override def name: String = "Cloud APIM - LLM OpenAI Compat. AudioModels"
  override def description: Option[String] = "Delegates call to a LLM provider to generate AudioModels".some
  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))
  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)
  override def useDelegates: Boolean = false
  override def defaultConfigObject: Option[NgPluginConfig] = Some(OpenAICompatAudioModelConfig.default)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = OpenAICompatAudioModelConfig.configFlow
  override def configSchema: Option[JsObject] = OpenAICompatAudioModelConfig.configSchema

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'LLM OpenAI Compat. AudioModels' plugin is available !")
    }
    ().vfuture
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
      try {
        val jsonBody = bodyRaw.utf8String.parseJson
        val inputFromBody: String = jsonBody.select("text").asOptString.getOrElse("")
        val voiceFromBody: Option[String] = jsonBody.select("voice").asOptString
        val modelFromBody = jsonBody.select("model").asOptString
        val config = ctx.cachedConfig(internalName)(OpenAICompatAudioModelConfig.format).getOrElse(OpenAICompatAudioModelConfig.default)
        val models = config.refs.flatMap(r => ext.states.AudioModel(r))
        val model = modelFromBody.flatMap { m =>
          if (m.contains("/")) {
            val parts = m.split("/")
            models.find(_.name == parts.head)
          } else {
            models.find(_.name == m)
          }
        }.getOrElse(models.head)
        val modelStr: Option[String] = modelFromBody.flatMap { m =>
          if (m.contains("/")) {
            m.split("/").last.some
          } else {
            m.some
          }
        }
        model.getAudioModelClient() match {
          case None => NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_error", "error_details" -> "failed to create client"))).leftf
          case Some(client) => {
            client.transcribe(modelStr).map {
              case Left(err) => NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_error", "error_details" -> err))).left
              case Right(speech) => {
                Right(BackendCallResponse.apply(NgPluginHttpResponse.fromResult(Results.Ok(speech.toOpenAiJson)), None))
              }
            }
          }
        }
      } catch {
        case e: Throwable => NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(Json.obj("error" -> "bad_request", "error_details" -> e.getMessage))).leftf
      }
    }
  }
}