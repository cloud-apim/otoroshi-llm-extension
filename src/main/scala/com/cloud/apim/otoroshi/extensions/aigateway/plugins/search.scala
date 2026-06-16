package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import akka.stream.Materializer
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.SearchEngineSearchOptions
import com.cloud.apim.otoroshi.extensions.aigateway.entities.SearchEngine
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json._
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class SearchEngineProxyConfig(refs: Seq[String]) extends NgPluginConfig {
  def json: JsValue = SearchEngineProxyConfig.format.writes(this)
}

object SearchEngineProxyConfig {
  val configFlow: Seq[String] = Seq("refs")

  def configSchema: Option[JsObject] = Some(Json.obj(
    "refs" -> Json.obj(
      "type" -> "select",
      "array" -> true,
      "label" -> s"Search engines",
      "props" -> Json.obj(
        "optionsFrom" -> s"/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/search-engines",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      ),
    )
  ))

  val default = SearchEngineProxyConfig(Seq.empty)
  val format = new Format[SearchEngineProxyConfig] {
    override def writes(o: SearchEngineProxyConfig): JsValue = Json.obj("refs" -> o.refs)

    override def reads(json: JsValue): JsResult[SearchEngineProxyConfig] = Try {
      SearchEngineProxyConfig(
        refs = json.select("refs").asOpt[Seq[String]].getOrElse(Seq.empty)
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }

  def resolveProvider(jsonBody: JsObject, config: SearchEngineProxyConfig)(implicit ec: ExecutionContext, env: Env): Option[SearchEngine] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    jsonBody.select("provider").asOpt[String]
      .filter(v => config.refs.contains(v))
      .flatMap(r => ext.states.searchEngine(r))
      .orElse(config.refs.headOption.flatMap(r => ext.states.searchEngine(r)))
  }
}

object SearchEngineProxy {
  def handleRequest(config: SearchEngineProxyConfig, ctx: NgbBackendCallContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
      val jsonBody: JsObject = (if (bodyRaw.isEmpty) Json.obj() else bodyRaw.utf8String.parseJson).asObject
      SearchEngineProxyConfig.resolveProvider(jsonBody, config) match {
        case None => NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_error", "error_details" -> "provider not found"))).leftf
        case Some(searchEngine) => searchEngine.getSearchEngineClient() match {
          case None => NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_error", "error_details" -> "failed to create client"))).leftf
          case Some(client) => {
            val options = SearchEngineSearchOptions.format.reads(jsonBody).getOrElse(SearchEngineSearchOptions(jsonBody.select("query").asOptString.getOrElse("")))
            if (options.query.trim.isEmpty) {
              NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(Json.obj("error" -> "bad_request", "error_details" -> "a 'query' is required"))).leftf
            } else {
              client.search(options, jsonBody, ctx.attrs).map {
                case Left(err) => NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_error", "error_details" -> err))).left
                case Right(response) => Right(BackendCallResponse.apply(NgPluginHttpResponse.fromResult(Results.Ok(response.toJson)), None))
              }
            }
          }
        }
      }
    }
  }
}

class SearchEngineProxy extends NgBackendCall {

  override def name: String = "Cloud APIM - Search engine backend"
  override def description: Option[String] = "Delegates call to a search engine provider to perform a web search and return normalized results".some
  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))
  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)
  override def useDelegates: Boolean = false
  override def defaultConfigObject: Option[NgPluginConfig] = Some(SearchEngineProxyConfig.default)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = SearchEngineProxyConfig.configFlow
  override def configSchema: Option[JsObject] = SearchEngineProxyConfig.configSchema

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'Search engine backend' plugin is available !")
    }
    ().vfuture
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(SearchEngineProxyConfig.format).getOrElse(SearchEngineProxyConfig.default)
    SearchEngineProxy.handleRequest(config, ctx)
  }
}
