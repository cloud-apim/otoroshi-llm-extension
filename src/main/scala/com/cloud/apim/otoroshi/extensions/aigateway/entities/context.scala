package com.cloud.apim.otoroshi.extensions.aigateway.entities

import com.cloud.apim.otoroshi.extensions.aigateway.{ChatMessage, InputChatMessage}
import otoroshi.api.{GenericResourceAccessApiWithState, Resource, ResourceVersion}
import otoroshi.env.Env
import otoroshi.models._
import otoroshi.next.extensions.AdminExtensionId
import otoroshi.security.IdGenerator
import otoroshi.storage._
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.{AiGatewayExtensionDatastores, AiGatewayExtensionState}
import play.api.libs.json._

import scala.util.{Failure, Success, Try}

case class PromptContext(
                       location: EntityLocation,
                       id: String,
                       name: String,
                       description: String,
                       tags: Seq[String],
                       metadata: Map[String, String],
                       preMessages: Seq[JsObject],
                       postMessages: Seq[JsObject],
                     ) extends EntityLocationSupport {
  override def internalId: String               = id
  override def json: JsValue                    = PromptContext.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata
  def preChatMessages(): Seq[InputChatMessage] = {
    preMessages.map { message =>
      val role = message.select("role").asOpt[String].getOrElse("user")
      val content = message.select("content").asOpt[String].getOrElse("no input")
      val prefix = message.select("prefix").asOptBoolean
      ChatMessage.input(role, content, prefix)
    }
  }
  def postChatMessages(): Seq[InputChatMessage] = {
    postMessages.map { message =>
      val role = message.select("role").asOpt[String].getOrElse("user")
      val content = message.select("content").asOpt[String].getOrElse("no input")
      val prefix = message.select("prefix").asOptBoolean
      ChatMessage.input(role, content, prefix)
    }
  }
}

object PromptContext {
  val format = new Format[PromptContext] {
    override def writes(o: PromptContext): JsValue             = o.location.jsonWithKey ++ Json.obj(
      "id"          -> o.id,
      "name"        -> o.name,
      "description" -> o.description,
      "metadata"    -> o.metadata,
      "tags"        -> JsArray(o.tags.map(JsString.apply)),
      "pre_messages"    -> o.preMessages,
      "post_messages"    -> o.postMessages,
    )
    override def reads(json: JsValue): JsResult[PromptContext] = Try {
      PromptContext(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = (json \ "id").as[String],
        name = (json \ "name").as[String],
        description = (json \ "description").as[String],
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        preMessages = (json \ "pre_messages").asOpt[Seq[JsObject]].orElse((json \ "messages").asOpt[Seq[JsObject]]).getOrElse(Seq.empty),
        postMessages = (json \ "post_messages").asOpt[Seq[JsObject]].getOrElse(Seq.empty),
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
  def resource(env: Env, datastores: AiGatewayExtensionDatastores, states: AiGatewayExtensionState): Resource = {
    Resource(
      "PromptContext",
      "prompt-contexts",
      "prompt-context",
      "ai-gateway.extensions.cloud-apim.com",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[PromptContext](
        format = PromptContext.format,
        clazz = classOf[PromptContext],
        keyf = id => datastores.promptContextDataStore.key(id),
        extractIdf = c => datastores.promptContextDataStore.extractId(c),
        extractIdJsonf = json => json.select("id").asString,
        idFieldNamef = () => "id",
        tmpl = (v, p) => {
          PromptContext(
            id = IdGenerator.namedId("prompt-context", env),
            name = "Prompt context",
            description = "A new prompt context",
            metadata = Map.empty,
            tags = Seq.empty,
            location = EntityLocation.default,
            preMessages = Seq.empty,
            postMessages = Seq.empty,
          ).json
        },
        canRead = true,
        canCreate = true,
        canUpdate = true,
        canDelete = true,
        canBulk = true,
        stateAll = () => states.allContexts(),
        stateOne = id => states.context(id),
        stateUpdate = values => states.updateContexts(values)
      )
    )
  }
}

trait PromptContextDataStore extends BasicStore[PromptContext]

class KvPromptContextDataStore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
  extends PromptContextDataStore
    with RedisLikeStore[PromptContext] {
  override def fmt: Format[PromptContext]                  = PromptContext.format
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def key(id: String): String                 = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:contexts:$id"
  override def extractId(value: PromptContext): String    = value.id
}
