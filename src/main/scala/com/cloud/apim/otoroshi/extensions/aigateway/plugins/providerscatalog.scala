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

object LlmProvidersCatalog {

  // Reads the (repeatable and/or comma-separated) `capabilities`/`capability` query params and
  // returns the matching catalog entries. A provider must expose ALL requested capabilities.
  def handleRequest(ctx: NgbBackendCallContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val requested = (ctx.rawRequest.queryString.getOrElse("capabilities", Seq.empty) ++
      ctx.rawRequest.queryString.getOrElse("capability", Seq.empty))
      .flatMap(_.split(",")).map(_.trim).filter(_.nonEmpty)
    Right(BackendCallResponse(NgPluginHttpResponse.fromResult(
      Results.Ok(Json.obj(
        "object" -> "list",
        "data" -> AiProvidersCatalog.json(requested)
      ))
    ), None)).vfuture
  }
}

class LlmProvidersCatalog extends NgBackendCall {

  override def name: String = "Cloud APIM - LLM Providers Catalog"
  override def description: Option[String] = "Exposes the catalog of every provider type Otoroshi LLM supports with their capabilities (text, audio, image, ocr, embedding, moderation, video). Filter with one or more `capabilities` query params.".some
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
      ext.logger.info("the 'LLM Providers Catalog' plugin is available !")
    }
    ().vfuture
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    LlmProvidersCatalog.handleRequest(ctx)
  }
}
