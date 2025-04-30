package com.cloud.apim.otoroshi.extensions.aigateway.entities

import com.cloud.apim.otoroshi.extensions.aigateway.VideosGenModelClient
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

case class VideosGenModel(
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
  override def json: JsValue                    = VideosGenModel.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata
  def getVideosGenModelClient()(implicit env: Env): Option[VideosGenModelClient] = {
    val connection = config.select("connection").asOpt[JsObject].getOrElse(Json.obj())
    val options = config.select("options").asOpt[JsObject].getOrElse(Json.obj())
    // val baseUrl = connection.select("base_url").orElse(connection.select("base_domain")).asOpt[String]
    val token = connection.select("token").asOpt[String].getOrElse("xxx")
    val timeout = connection.select("timeout").asOpt[Long].map(FiniteDuration(_, TimeUnit.MILLISECONDS))
    provider.toLowerCase() match {
      case "luma" => {
        val api = new LumaApi(LumaApi.baseUrl, token, timeout.getOrElse(10.seconds), env = env)
        val opts = LumaVideosGenModelClientOptions.fromJson(options)
        new LumaVideosGenModelClient(api, opts, id).some
      }
      case _ => None
    }
  }
}

object VideosGenModel {
  val format = new Format[VideosGenModel] {
    override def writes(o: VideosGenModel): JsValue             = o.location.jsonWithKey ++ Json.obj(
      "id"               -> o.id,
      "name"             -> o.name,
      "description"      -> o.description,
      "metadata"         -> o.metadata,
      "tags"             -> JsArray(o.tags.map(JsString.apply)),
      "provider"         -> o.provider,
      "config"           -> o.config,
    )
    override def reads(json: JsValue): JsResult[VideosGenModel] = Try {
      VideosGenModel(
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
      "VideosGenModel",
      "videos-gen",
      "videos-gen",
      "ai-gateway.extensions.cloud-apim.com",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[VideosGenModel](
        format = VideosGenModel.format,
        clazz = classOf[VideosGenModel],
        keyf = id => datastores.videosGenModelsDataStore.key(id),
        extractIdf = c => datastores.videosGenModelsDataStore.extractId(c),
        extractIdJsonf = json => json.select("id").asString,
        idFieldNamef = () => "id",
        tmpl = (v, p, ctx) => {
          p.get("kind").map(_.toLowerCase()) match {
            case Some("luma") => VideosGenModel(
              id = IdGenerator.namedId("provider", env),
              name = "OpenAI gpt-image-1",
              description = "An OpenAI videos generation model",
              metadata = Map.empty,
              tags = Seq.empty,
              location = EntityLocation.default,
              provider = "luma",
              config = Json.obj(
                "connection" -> Json.obj(
                  "base_url" -> OpenAiApi.baseUrl,
                  "token" -> "xxxxx",
                  "timeout" -> 10000,
                ),
                "options" -> Json.obj(
                  "model" -> "gpt-image-1"
                )
              ),
            ).json
            case _ => VideosGenModel(
              id = IdGenerator.namedId("provider", env),
              name = "Local videos generation model",
              description = "A Local videos generation model",
              metadata = Map.empty,
              tags = Seq.empty,
              location = EntityLocation.default,
              provider = "local-videos-gen-model",
              config = Json.obj(),
            ).json
          }
        },
        canRead = true,
        canCreate = true,
        canUpdate = true,
        canDelete = true,
        canBulk = true,
        stateAll = () => states.allVideosGensModels(),
        stateOne = id => states.videoGensModels(id),
        stateUpdate = values => states.updateVideosGensModels(values)
      )
    )
  }
}

trait VideosGenModelsDataStore extends BasicStore[VideosGenModel]

class KvVideosGenModelsDataStore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
  extends VideosGenModelsDataStore
    with RedisLikeStore[VideosGenModel] {
  override def fmt: Format[VideosGenModel]                  = VideosGenModel.format
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def key(id: String): String                 = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:videosgen:$id"
  override def extractId(value: VideosGenModel): String    = value.id
}
