package com.cloud.apim.otoroshi.extensions.aigateway.entities

import com.cloud.apim.otoroshi.extensions.aigateway.EmbeddingModelClient
import com.cloud.apim.otoroshi.extensions.aigateway.providers._
import otoroshi.api._
import otoroshi.env.Env
import otoroshi.models._
import otoroshi.next.extensions.AdminExtensionId
import otoroshi.security.IdGenerator
import otoroshi.storage._
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway._
import play.api.libs.json._

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.{Failure, Success, Try}

case class EmbeddingModel(
  location: EntityLocation,
  id: String,
  name: String,
  description: String,
  tags: Seq[String],
  metadata: Map[String, String],
  provider: String,
  config: JsObject,
) extends EntityLocationSupport {
  override def internalId: String               = id
  override def json: JsValue                    = EmbeddingModel.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata
  def getEmbeddingModelClient()(implicit env: Env): Option[EmbeddingModelClient] = {
    val connection = config.select("connection").asOpt[JsObject].getOrElse(Json.obj())
    val options = config.select("options").asOpt[JsObject].getOrElse(Json.obj())
    val baseUrl = connection.select("base_url").orElse(connection.select("base_domain")).asOpt[String]
    val token = connection.select("token").asOpt[String].getOrElse("xxx")
    val timeout = connection.select("timeout").asOpt[Long].map(FiniteDuration(_, TimeUnit.MILLISECONDS))
    provider.toLowerCase() match {
      case "openai" => {
        val api = new OpenAiApi(baseUrl.getOrElse(OpenAiApi.baseUrl), token, timeout.getOrElse(30.seconds), providerName = "OpenAI", env = env)
        val opts = OpenAiEmbeddingModelClientOptions.fromJson(options)
        new OpenAiEmbeddingModelClient(api, opts, id).some
      }
      case "mistral" => {
        val api = new MistralAiApi(baseUrl.getOrElse(OpenAiApi.baseUrl), token, timeout.getOrElse(30.seconds), env = env)
        val opts = MistralAiEmbeddingModelClientOptions.fromJson(options)
        new MistralAiEmbeddingModelClient(api, opts, id).some
      }
      case "ollama" => {
        val api = new OllamaAiApi(baseUrl.getOrElse(OllamaAiApi.baseUrl), token.some.filterNot(_ == "xxx"), timeout.getOrElse(10.seconds), env = env)
        val opts = OllamaEmbeddingModelClientOptions.fromJson(options)
        new OllamaEmbeddingModelClient(api, opts, id).some
      }
      case "all-minilm-l6-v2" => new AllMiniLmL6V2EmbeddingModelClient(options, id).some
      case _ => None
    }
  }
}

object EmbeddingModel {
  val format = new Format[EmbeddingModel] {
    override def writes(o: EmbeddingModel): JsValue             = o.location.jsonWithKey ++ Json.obj(
      "id"               -> o.id,
      "name"             -> o.name,
      "description"      -> o.description,
      "metadata"         -> o.metadata,
      "tags"             -> JsArray(o.tags.map(JsString.apply)),
      "provider"         -> o.provider,
      "config"           -> o.config,
    )
    override def reads(json: JsValue): JsResult[EmbeddingModel] = Try {
      EmbeddingModel(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = (json \ "id").as[String],
        name = (json \ "name").as[String],
        description = (json \ "description").as[String],
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        provider = (json \ "provider").as[String],
        config = (json \ "config").asOpt[JsObject].getOrElse(Json.obj()),
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
  def resource(env: Env, datastores: AiGatewayExtensionDatastores, states: AiGatewayExtensionState): Resource = {
    Resource(
      "EmbeddingModel",
      "embedding-models",
      "embedding-models",
      "ai-gateway.extensions.cloud-apim.com",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[EmbeddingModel](
        format = EmbeddingModel.format ,
        clazz = classOf[EmbeddingModel],
        keyf = id => datastores.embeddingModelsDataStore.key(id),
        extractIdf = c => datastores.embeddingModelsDataStore.extractId(c),
        extractIdJsonf = json => json.select("id").asString,
        idFieldNamef = () => "id",
        tmpl = (v, p) => {
          p.get("kind").map(_.toLowerCase()) match {
            case Some("openai") => EmbeddingModel(
              id = IdGenerator.namedId("provider", env),
              name = "OpenAI text-embedding-3-small",
              description = "An OpenAI embedding model",
              metadata = Map.empty,
              tags = Seq.empty,
              location = EntityLocation.default,
              provider = "openai",
              config = Json.obj(
                "connection" -> Json.obj(
                  "base_url" -> OpenAiApi.baseUrl,
                  "token" -> "xxxxx",
                  "timeout" -> 10000,
                ),
                "options" -> Json.obj(
                  "model" -> "text-embedding-3-small"
                )
              ),
            ).json
            case Some("ollama") => EmbeddingModel(
              id = IdGenerator.namedId("provider", env),
              name = "Ollama embedding model",
              description = "An Ollama embedding model",
              metadata = Map.empty,
              tags = Seq.empty,
              location = EntityLocation.default,
              provider = "ollama",
              config = Json.obj(
                "connection" -> Json.obj(
                  "base_url" -> OllamaAiApi.baseUrl,
                  "token" -> "xxxxx",
                  "timeout" -> 10000,
                ),
                "options" -> Json.obj(
                  "model" -> "snowflake-arctic-embed:22m"
                )
              ),
            ).json
            case _ => EmbeddingModel(
              id = IdGenerator.namedId("provider", env),
              name = "Local embedding model",
              description = "A Local embedding model",
              metadata = Map.empty,
              tags = Seq.empty,
              location = EntityLocation.default,
              provider = "all-minilm-l6-v2",
              config = Json.obj(),
            ).json
          }

        },
        canRead = true,
        canCreate = true,
        canUpdate = true,
        canDelete = true,
        canBulk = true,
        stateAll = () => states.allEmbeddingModels(),
        stateOne = id => states.embeddingModel(id),
        stateUpdate = values => states.updateEmbeddingModels(values)
      )
    )
  }
}

trait EmbeddingModelsDataStore extends BasicStore[EmbeddingModel]

class KvEmbeddingModelsDataStore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
  extends EmbeddingModelsDataStore
    with RedisLikeStore[EmbeddingModel] {
  override def fmt: Format[EmbeddingModel]                  = EmbeddingModel.format
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def key(id: String): String                 = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:embmods:$id"
  override def extractId(value: EmbeddingModel): String    = value.id
}
