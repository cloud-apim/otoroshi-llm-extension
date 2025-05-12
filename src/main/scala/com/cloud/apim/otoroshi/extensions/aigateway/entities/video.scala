package com.cloud.apim.otoroshi.extensions.aigateway.entities

import com.cloud.apim.otoroshi.extensions.aigateway.VideoModelClient
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

case class VideoModel(
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
  override def json: JsValue                    = VideoModel.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata
  def getVideoModelClient()(implicit env: Env): Option[VideoModelClient] = {
    val connection = config.select("connection").asOpt[JsObject].getOrElse(Json.obj())
    val options = config.select("options").asOpt[JsObject].getOrElse(Json.obj())
    // val baseUrl = connection.select("base_url").orElse(connection.select("base_domain")).asOpt[String]
    val token = connection.select("token").asOpt[String].getOrElse("xxx")
    val timeout = connection.select("timeout").asOpt[Long].map(FiniteDuration(_, TimeUnit.MILLISECONDS))
    provider.toLowerCase() match {
      case "luma" => {
        val api = new LumaApi(LumaApi.baseUrl, token, timeout.getOrElse(10.seconds), env = env)
        val opts = LumaVideoModelClientOptions.fromJson(options)
        new LumaVideoModelClient(api, opts, id).some
      }
      case _ => None
    }
  }
}

object VideoModel {
  val format = new Format[VideoModel] {
    override def writes(o: VideoModel): JsValue             = o.location.jsonWithKey ++ Json.obj(
      "id"               -> o.id,
      "name"             -> o.name,
      "description"      -> o.description,
      "metadata"         -> o.metadata,
      "tags"             -> JsArray(o.tags.map(JsString.apply)),
      "provider"         -> o.provider,
      "config"           -> o.config,
    )
    override def reads(json: JsValue): JsResult[VideoModel] = Try {
      VideoModel(
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
      "VideoModel",
      "video-models",
      "video-model",
      "ai-gateway.extensions.cloud-apim.com",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[VideoModel](
        format = VideoModel.format,
        clazz = classOf[VideoModel],
        keyf = id => datastores.videoModelsDataStore.key(id),
        extractIdf = c => datastores.videoModelsDataStore.extractId(c),
        extractIdJsonf = json => json.select("id").asString,
        idFieldNamef = () => "id",
        tmpl = (v, p, ctx) => {
          p.get("kind").map(_.toLowerCase()) match {
            case Some("luma") => VideoModel(
              id = IdGenerator.namedId("video-model", env),
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
            case _ => VideoModel(
              id = IdGenerator.namedId("video-model", env),
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
        stateAll = () => states.allVideoModels(),
        stateOne = id => states.videoModel(id),
        stateUpdate = values => states.updateVideoModels(values)
      )
    )
  }
}

trait VideoModelsDataStore extends BasicStore[VideoModel]

class KvVideoModelsDataStore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
  extends VideoModelsDataStore
    with RedisLikeStore[VideoModel] {
  override def fmt: Format[VideoModel]                  = VideoModel.format
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def key(id: String): String                 = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:videomodels:$id"
  override def extractId(value: VideoModel): String    = value.id
}
