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

case class VideosGenConfig(refs: Seq[String]) extends NgPluginConfig {
  def json: JsValue = VideosGenConfig.format.writes(this)
}

object VideosGenConfig {
  val configFlow: Seq[String] = Seq("refs")
  def configSchema: Option[JsObject] = Some(Json.obj(
    "refs" -> Json.obj(
      "type" -> "select",
      "array" -> true,
      "label" -> s"Images Generation",
      "props" -> Json.obj(
        "optionsFrom" -> s"/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/video-models",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      ),
    )
  ))
  val default = VideosGenConfig(Seq.empty)
  val format = new Format[VideosGenConfig] {
    override def writes(o: VideosGenConfig): JsValue = Json.obj("refs" -> o.refs)
    override def reads(json: JsValue): JsResult[VideosGenConfig] = Try {
      val refs = json.select("refs").asOpt[Seq[String]].getOrElse(Seq.empty)
      VideosGenConfig(
        refs = refs
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

class VideosGen extends NgBackendCall {

  override def name: String = "Cloud APIM - LLM OpenAI Compat. Video Generation"
  override def description: Option[String] = "Delegates call to a LLM provider to generate images".some
  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))
  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)
  override def useDelegates: Boolean = false
  override def defaultConfigObject: Option[NgPluginConfig] = Some(VideosGenConfig.default)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = VideosGenConfig.configFlow
  override def configSchema: Option[JsObject] = VideosGenConfig.configSchema

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'LLM OpenAI Compat. Images generation' plugin is available !")
    }
    ().vfuture
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
      try {
        val jsonBody = bodyRaw.utf8String.parseJson
        val promptFromBody = jsonBody.select("prompt").asOptString
        val modelFromBody = jsonBody.select("model").asOptString
        val encoding_format = jsonBody.select("encoding_format").asOptString.getOrElse("float")
        val sizeFromBody = jsonBody.select("size").asOptString
        val nFromBody = jsonBody.select("n").asOpt[Int].getOrElse(1)

        val config = ctx.cachedConfig(internalName)(VideosGenConfig.format).getOrElse(VideosGenConfig.default)
        val models = config.refs.flatMap(r => ext.states.videoGensModels(r))
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
        model.getVideosGenModelClient() match {
          case None => NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_error", "error_details" -> "failed to create client"))).leftf
          case Some(client) => {
            client.generate(promptFromBody.getOrElse(""), modelStr, sizeFromBody).map {
              case Left(err) => NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_error", "error_details" -> err))).left
              case Right(imageGen) => {
                Right(BackendCallResponse.apply(NgPluginHttpResponse.fromResult(Results.Ok(imageGen.toOpenAiJson)), None))
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