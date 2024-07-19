package com.cloud.apim.otoroshi.extensions.aigateway.entities

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

case class PromptTemplate(
                          location: EntityLocation,
                          id: String,
                          name: String,
                          description: String,
                          tags: Seq[String],
                          metadata: Map[String, String],
                          template: String,
                        ) extends EntityLocationSupport {
  override def internalId: String               = id
  override def json: JsValue                    = PromptTemplate.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata
}

object PromptTemplate {
  val format = new Format[PromptTemplate] {
    override def writes(o: PromptTemplate): JsValue             = o.location.jsonWithKey ++ Json.obj(
      "id"          -> o.id,
      "name"        -> o.name,
      "description" -> o.description,
      "metadata"    -> o.metadata,
      "tags"        -> JsArray(o.tags.map(JsString.apply)),
      "template"    -> o.template,
    )
    override def reads(json: JsValue): JsResult[PromptTemplate] = Try {
      PromptTemplate(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = (json \ "id").as[String],
        name = (json \ "name").as[String],
        description = (json \ "description").as[String],
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        template = (json \ "template").asOpt[String].getOrElse("[]"),
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
  def resource(env: Env, datastores: AiGatewayExtensionDatastores, states: AiGatewayExtensionState): Resource = {
    Resource(
      "PromptTemplate",
      "prompt-templates",
      "prompt-template",
      "ai-gateway.extensions.cloud-apim.com",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[PromptTemplate](
        format = PromptTemplate.format,
        clazz = classOf[PromptTemplate],
        keyf = id => datastores.promptTemplatesDatastore.key(id),
        extractIdf = c => datastores.promptTemplatesDatastore.extractId(c),
        extractIdJsonf = json => json.select("id").asString,
        idFieldNamef = () => "id",
        tmpl = (v, p) => {
          PromptTemplate(
            id = IdGenerator.namedId("prompt-template", env),
            name = "Prompt template",
            description = "A new prompt template",
            metadata = Map.empty,
            tags = Seq.empty,
            location = EntityLocation.default,
            template = "[]"
          ).json
        },
        canRead = true,
        canCreate = true,
        canUpdate = true,
        canDelete = true,
        canBulk = true,
        stateAll = () => states.allTemplates(),
        stateOne = id => states.template(id),
        stateUpdate = values => states.updateTemplates(values)
      )
    )
  }
}

trait PromptTemplateDataStore extends BasicStore[PromptTemplate]

class KvPromptTemplateDataStore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
  extends PromptTemplateDataStore
    with RedisLikeStore[PromptTemplate] {
  override def fmt: Format[PromptTemplate]                  = PromptTemplate.format
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def key(id: String): String                 = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:templates:$id"
  override def extractId(value: PromptTemplate): String    = value.id
}
