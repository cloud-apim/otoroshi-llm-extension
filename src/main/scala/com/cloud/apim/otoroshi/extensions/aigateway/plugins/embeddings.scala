package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

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

case class OpenAICompatEmbeddingConfig(refs: Seq[String]) extends NgPluginConfig {
  def json: JsValue = OpenAICompatEmbeddingConfig.format.writes(this)
}

object OpenAICompatEmbeddingConfig {
  val configFlow: Seq[String] = Seq("refs")
  def configSchema: Option[JsObject] = Some(Json.obj(
    "refs" -> Json.obj(
      "type" -> "select",
      "array" -> true,
      "label" -> s"Embedding models",
      "props" -> Json.obj(
        "optionsFrom" -> s"/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/embedding-models",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      ),
    )
  ))
  val default = OpenAICompatEmbeddingConfig(Seq.empty)
  val format = new Format[OpenAICompatEmbeddingConfig] {
    override def writes(o: OpenAICompatEmbeddingConfig): JsValue = Json.obj("refs" -> o.refs)
    override def reads(json: JsValue): JsResult[OpenAICompatEmbeddingConfig] = Try {
      val refs = json.select("refs").asOpt[Seq[String]].getOrElse(Seq.empty)
      OpenAICompatEmbeddingConfig(
        refs = refs
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

class OpenAICompatEmbedding extends NgBackendCall {

  override def name: String = "Cloud APIM - LLM OpenAI Compat. Embeddings"
  override def description: Option[String] = "Delegates call to a LLM provider to generate embeddings".some
  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))
  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)
  override def useDelegates: Boolean = false
  override def defaultConfigObject: Option[NgPluginConfig] = Some(OpenAICompatEmbeddingConfig.default)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = OpenAICompatEmbeddingConfig.configFlow
  override def configSchema: Option[JsObject] = OpenAICompatEmbeddingConfig.configSchema

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'LLM OpenAI Compat. Embeddings' plugin is available !")
    }
    ().vfuture
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
      try {
        val jsonBody = bodyRaw.utf8String.parseJson
        val inputFromBody: Seq[String] = jsonBody.select("input").asOptString.map(s => Seq(s)).orElse(jsonBody.select("input").asOpt[Seq[String]]).getOrElse(Seq.empty)
        val modelFromBody = jsonBody.select("model").asOptString
        val encoding_format = jsonBody.select("encoding_format").asOptString.getOrElse("float")
        val config = ctx.cachedConfig(internalName)(OpenAICompatEmbeddingConfig.format).getOrElse(OpenAICompatEmbeddingConfig.default)
        val models = config.refs.flatMap(r => ext.states.embeddingModel(r))
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
        model.getEmbeddingModelClient() match {
          case None => NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_error", "error_details" -> "failed to create client"))).leftf
          case Some(client) => {
            client.embed(inputFromBody, modelStr).map {
              case Left(err) => NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_error", "error_details" -> err))).left
              case Right(embedding) => {
                Right(BackendCallResponse.apply(NgPluginHttpResponse.fromResult(Results.Ok(embedding.toOpenAiJson(encoding_format))), None))
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