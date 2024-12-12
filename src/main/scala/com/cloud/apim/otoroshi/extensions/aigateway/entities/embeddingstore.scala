package com.cloud.apim.otoroshi.extensions.aigateway.entities

import com.cloud.apim.otoroshi.extensions.aigateway.EmbeddingStoreClient
import com.cloud.apim.otoroshi.extensions.aigateway.providers.LocalEmbeddingStoreClient
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
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}

case class EmbeddingStore(
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
  override def json: JsValue                    = EmbeddingStore.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata
  def getEmbeddingStoreClient()(implicit env: Env): Option[EmbeddingStoreClient] = {
    val connection = config.select("connection").asOpt[JsObject].getOrElse(Json.obj())
    val options = config.select("options").asOpt[JsObject].getOrElse(Json.obj())
    val baseUrl = connection.select("base_url").orElse(connection.select("base_domain")).asOpt[String]
    val token = connection.select("token").asOpt[String].getOrElse("xxx")
    val timeout = connection.select("timeout").asOpt[Long].map(FiniteDuration(_, TimeUnit.MILLISECONDS))
    provider.toLowerCase() match {
      case "local" => new LocalEmbeddingStoreClient(config, id).some
      case _ => None
    }
  }
}

object EmbeddingStore {
  val format = new Format[EmbeddingStore] {
    override def writes(o: EmbeddingStore): JsValue             = o.location.jsonWithKey ++ Json.obj(
      "id"               -> o.id,
      "name"             -> o.name,
      "description"      -> o.description,
      "metadata"         -> o.metadata,
      "tags"             -> JsArray(o.tags.map(JsString.apply)),
      "provider"         -> o.provider,
      "config"           -> o.config,
    )
    override def reads(json: JsValue): JsResult[EmbeddingStore] = Try {
      EmbeddingStore(
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
      "EmbeddingStore",
      "embedding-stores",
      "embedding-store",
      "ai-gateway.extensions.cloud-apim.com",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[EmbeddingStore](
        format = EmbeddingStore.format ,
        clazz = classOf[EmbeddingStore],
        keyf = id => datastores.embeddingStoresDataStore.key(id),
        extractIdf = c => datastores.embeddingStoresDataStore.extractId(c),
        extractIdJsonf = json => json.select("id").asString,
        idFieldNamef = () => "id",
        tmpl = (v, p) => {
          p.get("kind").map(_.toLowerCase()) match {
            case _ => EmbeddingStore(
              id = IdGenerator.namedId("provider", env),
              name = "Local embedding store",
              description = "A Local embedding store",
              metadata = Map.empty,
              tags = Seq.empty,
              location = EntityLocation.default,
              provider = "local",
              config = Json.obj(
                "connection" -> Json.obj(
                  "name" -> "local"
                ),
                "options" -> Json.obj(
                  "max_results" -> 3,
                  "min_score" -> 0.7
                )
              ),
            ).json
          }

        },
        canRead = true,
        canCreate = true,
        canUpdate = true,
        canDelete = true,
        canBulk = true,
        stateAll = () => states.allEmbeddingStores(),
        stateOne = id => states.embeddingStore(id),
        stateUpdate = values => states.updateEmbeddingStores(values)
      )
    )
  }
}

trait EmbeddingStoresDataStore extends BasicStore[EmbeddingStore]

class KvEmbeddingStoresDataStore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
  extends EmbeddingStoresDataStore
    with RedisLikeStore[EmbeddingStore] {
  override def fmt: Format[EmbeddingStore]                  = EmbeddingStore.format
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def key(id: String): String                 = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:embstores:$id"
  override def extractId(value: EmbeddingStore): String    = value.id
}

