package com.cloud.apim.otoroshi.extensions.aigateway.entities

import otoroshi.api.{GenericResourceAccessApiWithState, Resource, ResourceVersion}
import otoroshi.env.Env
import otoroshi.models.{EntityLocation, EntityLocationSupport}
import otoroshi.next.extensions.AdminExtensionId
import otoroshi.security.IdGenerator
import otoroshi.storage.{BasicStore, RedisLike, RedisLikeStore}
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.{AiGatewayExtensionDatastores, AiGatewayExtensionState}
import play.api.libs.json._

import scala.util.{Failure, Success, Try}


case class Prompt(
                          location: EntityLocation,
                          id: String,
                          name: String,
                          description: String,
                          tags: Seq[String],
                          metadata: Map[String, String],
                          prompt: String,
                        ) extends EntityLocationSupport {
  override def internalId: String               = id
  override def json: JsValue                    = Prompt.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata
}

object Prompt {
  val format = new Format[Prompt] {
    override def writes(o: Prompt): JsValue             = o.location.jsonWithKey ++ Json.obj(
      "id"          -> o.id,
      "name"        -> o.name,
      "description" -> o.description,
      "metadata"    -> o.metadata,
      "tags"        -> JsArray(o.tags.map(JsString.apply)),
      "prompt"      -> o.prompt,
    )
    override def reads(json: JsValue): JsResult[Prompt] = Try {
      Prompt(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = (json \ "id").as[String],
        name = (json \ "name").as[String],
        description = (json \ "description").as[String],
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        prompt = (json \ "prompt").asOpt[String].getOrElse(""),
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
  def resource(env: Env, datastores: AiGatewayExtensionDatastores, states: AiGatewayExtensionState): Resource = {
    Resource(
      "Prompt",
      "prompts",
      "prompt",
      "ai-gateway.extensions.cloud-apim.com",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[Prompt](
        format = Prompt.format,
        clazz = classOf[Prompt],
        keyf = id => datastores.promptsDataStore.key(id),
        extractIdf = c => datastores.promptsDataStore.extractId(c),
        extractIdJsonf = json => json.select("id").asString,
        idFieldNamef = () => "id",
        tmpl = (v, p) => {
          Prompt(
            id = IdGenerator.namedId("prompt", env),
            name = "Prompt",
            description = "A new prompt",
            metadata = Map.empty,
            tags = Seq.empty,
            location = EntityLocation.default,
            prompt = "",
          ).json
        },
        canRead = true,
        canCreate = true,
        canUpdate = true,
        canDelete = true,
        canBulk = true,
        stateAll = () => states.allPrompts(),
        stateOne = id => states.prompt(id),
        stateUpdate = values => states.updatePrompts(values)
      )
    )
  }
}

trait PromptDataStore extends BasicStore[Prompt]

class KvPromptDataStore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
  extends PromptDataStore
    with RedisLikeStore[Prompt] {
  override def fmt: Format[Prompt]                  = Prompt.format
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def key(id: String): String                 = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:prompts:$id"
  override def extractId(value: Prompt): String    = value.id
}
