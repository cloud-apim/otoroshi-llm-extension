package com.cloud.apim.otoroshi.extensions.aigateway.entities

import com.cloud.apim.otoroshi.extensions.aigateway.PersistentMemoryClient
import com.cloud.apim.otoroshi.extensions.aigateway.providers.LocalPersistentMemoryClient
import otoroshi.api._
import otoroshi.env.Env
import otoroshi.models._
import otoroshi.next.extensions.AdminExtensionId
import otoroshi.security.IdGenerator
import otoroshi.storage._
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway._
import play.api.libs.json._

import scala.util.{Failure, Success, Try}

case class PersistentMemory(
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
  override def json: JsValue                    = PersistentMemory.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata
  def getPersistentMemoryClient()(implicit env: Env): Option[PersistentMemoryClient] = {
    provider.toLowerCase() match {
      case "local" => new LocalPersistentMemoryClient(config, id).some
      case _ => None
    }
  }
}

object PersistentMemory {
  val format = new Format[PersistentMemory] {
    override def writes(o: PersistentMemory): JsValue             = o.location.jsonWithKey ++ Json.obj(
      "id"               -> o.id,
      "name"             -> o.name,
      "description"      -> o.description,
      "metadata"         -> o.metadata,
      "tags"             -> JsArray(o.tags.map(JsString.apply)),
      "provider"         -> o.provider,
      "config"           -> o.config,
    )
    override def reads(json: JsValue): JsResult[PersistentMemory] = Try {
      PersistentMemory(
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
      "PersistentMemory",
      "persistent-memories",
      "persistent-memory",
      "ai-gateway.extensions.cloud-apim.com",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[PersistentMemory](
        format = PersistentMemory.format,
        clazz = classOf[PersistentMemory],
        keyf = id => datastores.persistentMemoriesDataStore.key(id),
        extractIdf = c => datastores.persistentMemoriesDataStore.extractId(c),
        extractIdJsonf = json => json.select("id").asString,
        idFieldNamef = () => "id",
        tmpl = (v, p, ctx) => {
          p.get("kind").map(_.toLowerCase()) match {
            case _ => EmbeddingStore(
              id = IdGenerator.namedId("persistent-memory", env),
              name = "Local persistent memory",
              description = "A Local persistent memory",
              metadata = Map.empty,
              tags = Seq.empty,
              location = EntityLocation.default,
              provider = "local",
              config = Json.obj(
                "connection" -> Json.obj(
                  "name" -> "local"
                ),
                "options" -> Json.obj(
                  "kind" -> "MessagesSlidingWindow",
                  "max_messages" -> 100
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
        stateAll = () => states.allPersistentMemories(),
        stateOne = id => states.persistentMemory(id),
        stateUpdate = values => states.updatePersistentMemories(values)
      )
    )
  }
}

trait PersistentMemoryDataStore extends BasicStore[PersistentMemory]

class KvPersistentMemoryDataStore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
  extends PersistentMemoryDataStore
    with RedisLikeStore[PersistentMemory] {
  override def fmt: Format[PersistentMemory]                  = PersistentMemory.format
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def key(id: String): String                 = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:persistentmems:$id"
  override def extractId(value: PersistentMemory): String    = value.id
}

