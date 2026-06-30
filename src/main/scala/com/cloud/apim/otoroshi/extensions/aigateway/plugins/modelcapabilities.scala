package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import akka.stream.Materializer
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvidersCatalog
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json._
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}

object LlmModelCapabilities {

  // Returns every model type (modality) Otoroshi LLM supports — text, audio, image, ocr,
  // embedding, moderation, video — each with the provider ids exposing it.
  def handleRequest(ctx: NgbBackendCallContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    Right(BackendCallResponse(NgPluginHttpResponse.fromResult(
      Results.Ok(Json.obj(
        "object" -> "list",
        "data" -> AiProvidersCatalog.capabilitiesJson
      ))
    ), None)).vfuture
  }
}

class LlmModelCapabilities extends NgBackendCall {

  override def name: String = "Cloud APIM - LLM Model Capabilities"
  override def description: Option[String] = "Exposes the list of model types (modalities) Otoroshi LLM supports (text, audio, image, ocr, embedding, moderation, video), each with the providers exposing it.".some
  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))
  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)
  override def useDelegates: Boolean = false
  override def defaultConfigObject: Option[NgPluginConfig] = None
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = Seq.empty
  override def configSchema: Option[JsObject] = None

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'LLM Model Capabilities' plugin is available !")
    }
    ().vfuture
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    LlmModelCapabilities.handleRequest(ctx)
  }
}
