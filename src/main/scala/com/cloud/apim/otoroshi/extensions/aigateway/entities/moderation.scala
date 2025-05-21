package com.cloud.apim.otoroshi.extensions.aigateway.entities

import com.cloud.apim.otoroshi.extensions.aigateway.ModerationModelClient
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

case class ModerationModel(
                            location: EntityLocation,
                            id: String,
                            name: String,
                            description: String,
                            tags: Seq[String],
                            metadata: Map[String, String],
                            provider: String,
                            config: JsObject,
                          ) extends EntityLocationSupport {
  override def internalId: String = id

  override def json: JsValue = ModerationModel.format.writes(this)

  override def theName: String = name

  override def theDescription: String = description

  override def theTags: Seq[String] = tags

  override def theMetadata: Map[String, String] = metadata

  def slugName: String = metadata.getOrElse("endpoint_name", name).slugifyWithSlash.replaceAll("-+", "_")

  def getModerationModelClient()(implicit env: Env): Option[ModerationModelClient] = {
    val connection = config.select("connection").asOpt[JsObject].getOrElse(Json.obj())
    val options = config.select("options").asOpt[JsObject].getOrElse(Json.obj())
    val _token = connection.select("token").asOpt[String].getOrElse("xxx")
    val token = if (_token.contains(",")) {
      val parts = _token.split(",").map(_.trim)
      val index = AiProvider.tokenCounter.incrementAndGet() % (if (parts.nonEmpty) parts.length else 1)
      parts(index)
    } else {
      _token
    }
    val timeout = connection.select("timeout").asOpt[Long].map(FiniteDuration(_, TimeUnit.MILLISECONDS))
    provider.toLowerCase() match {
      case "openai" => {
        val api = new OpenAiApi(OpenAiApi.baseUrl, token, timeout.getOrElse(3.minutes), providerName = "OpenAI", env = env)
        val opts = OpenAiModerationModelClientOptions.fromJson(options)
        new OpenAiModerationModelClient(api, opts, id).some
      }
      case "mistral" => {
        val api = new MistralAiApi(MistralAiApi.baseUrl, token, timeout.getOrElse(3.minutes), env = env)
        val opts = MistralAiModerationModelClientOptions.fromJson(options)
        new MistralAiModerationModelClient(api, opts, id).some
      }
      case _ => None
    }
  }
}

object ModerationModel {
  val format = new Format[ModerationModel] {
    override def writes(o: ModerationModel): JsValue = o.location.jsonWithKey ++ Json.obj(
      "id" -> o.id,
      "name" -> o.name,
      "description" -> o.description,
      "metadata" -> o.metadata,
      "tags" -> JsArray(o.tags.map(JsString.apply)),
      "provider" -> o.provider,
      "config" -> o.config,
    )

    override def reads(json: JsValue): JsResult[ModerationModel] = Try {
      ModerationModel(
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
      case Failure(ex) => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }

  def resource(env: Env, datastores: AiGatewayExtensionDatastores, states: AiGatewayExtensionState): Resource = {
    Resource(
      "ModerationModel",
      "moderation-models",
      "moderation-model",
      "ai-gateway.extensions.cloud-apim.com",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[ModerationModel](
        format = ModerationModel.format,
        clazz = classOf[ModerationModel],
        keyf = id => datastores.moderationModelsDataStore.key(id),
        extractIdf = c => datastores.moderationModelsDataStore.extractId(c),
        extractIdJsonf = json => json.select("id").asString,
        idFieldNamef = () => "id",
        tmpl = (v, p, ctx) => {
          p.get("kind").map(_.toLowerCase()) match {
            case Some("openai") => ModerationModel(
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
                  "timeout" -> 3.minutes.toMillis,
                ),
                "options" -> Json.obj(
                  "model" -> "text-embedding-3-small"
                )
              ),
            ).json
            case Some("ollama") => ModerationModel(
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
                  "timeout" -> 3.minutes.toMillis,
                ),
                "options" -> Json.obj(
                  "model" -> "snowflake-arctic-embed:22m"
                )
              ),
            ).json
            case _ => ModerationModel(
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
        stateAll = () => states.allModerationModels(),
        stateOne = id => states.moderationModel(id),
        stateUpdate = values => states.updateModerationModels(values)
      )
    )
  }
}

trait ModerationModelsDataStore extends BasicStore[ModerationModel]

class KvModerationModelsDataStore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
  extends ModerationModelsDataStore
    with RedisLikeStore[ModerationModel] {
  override def fmt: Format[ModerationModel] = ModerationModel.format

  override def redisLike(implicit env: Env): RedisLike = redisCli

  override def key(id: String): String = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:moderationmodels:$id"

  override def extractId(value: ModerationModel): String = value.id
}
