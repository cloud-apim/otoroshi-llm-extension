package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import akka.stream.Materializer
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.EmbeddingClientInputOptions
import com.cloud.apim.otoroshi.extensions.aigateway.entities.EmbeddingModel
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
  def getProvidersMap(config: OpenAICompatEmbeddingConfig)(implicit ec: ExecutionContext, env: Env): (Map[String, EmbeddingModel], Map[String, EmbeddingModel]) = {
    val ext = env.adminExtensions.extension[AiExtension].get
    val providers = config.refs.flatMap(ref => ext.states.embeddingModel(ref))
    val providersByName = providers.map { provider =>
      val name = provider.slugName
      (name, provider)
    }.toMap
    val providersById = providers.map(p => (p.id, p)).toMap
    (providersById, providersByName)
  }

  def extractProviderFromModelInBody(_jsonBody: JsValue, config: OpenAICompatEmbeddingConfig)(implicit ec: ExecutionContext, env: Env): JsValue = {
    _jsonBody.select("model").asOpt[String] match {
      case Some(value) if value.contains("###") => {
        val parts = value.split("###")
        val name = parts(0)
        val model = parts(1)
        val (providersById, providersByName) = OpenAICompatEmbeddingConfig.getProvidersMap(config)
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
        val (providersById, providersByName) = OpenAICompatEmbeddingConfig.getProvidersMap(config)
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
      val _jsonBody = bodyRaw.utf8String.parseJson
      val config = ctx.cachedConfig(internalName)(OpenAICompatEmbeddingConfig.format).getOrElse(OpenAICompatEmbeddingConfig.default)
      val jsonBody: JsObject = OpenAICompatEmbeddingConfig.extractProviderFromModelInBody(_jsonBody, config).asObject
      val provider: Option[EmbeddingModel] = jsonBody.select("provider").asOpt[String].filter(v => config.refs.contains(v)).flatMap { r =>
        ext.states.embeddingModel(r)
      }.orElse(
        config.refs.headOption.flatMap { r =>
          ext.states.embeddingModel(r)
        }
      )
      provider match {
        case None => NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_error", "error_details" -> "provider not found"))).leftf
        case Some(provider) => {
          provider.getEmbeddingModelClient() match {
            case None => NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_error", "error_details" -> "failed to create client"))).leftf
            case Some(client) => {
              val options = EmbeddingClientInputOptions.format.reads(jsonBody).getOrElse(EmbeddingClientInputOptions(Seq.empty))
              client.embed(options, jsonBody).map {
                case Left(err) => NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_error", "error_details" -> err))).left
                case Right(embedding) => {
                  Right(BackendCallResponse.apply(NgPluginHttpResponse.fromResult(Results.Ok(embedding.toOpenAiJson(options.encoding_format.getOrElse("float")))), None))
                }
              }
            }
          }
        }
      }
    }
  }
}