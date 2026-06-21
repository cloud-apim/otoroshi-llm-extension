package com.cloud.apim.otoroshi.extensions.aigateway.entities

import otoroshi.api.{GenericResourceAccessApiWithState, Resource, ResourceVersion}
import otoroshi.env.Env
import otoroshi.models.{EntityLocation, EntityLocationSupport}
import otoroshi.next.extensions.AdminExtensionId
import otoroshi.security.IdGenerator
import otoroshi.storage.{BasicStore, RedisLike, RedisLikeStore}
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.{AiGatewayExtensionDatastores, AiGatewayExtensionState}
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.McpProxyEndpointConfig
import play.api.libs.json._

import scala.util.{Failure, Success, Try}

// A reusable, persisted definition of an exposed MCP server. It wraps the same settings as the MCP exposition
// plugins (McpSseEndpoint / McpWebsocketEndpoint / McpRespEndpoint) so a single definition can be referenced
// from many routes. The plugins reference it through `server_ref` and may override individual fields inline
// (see McpProxyEndpointConfig.resolve / overriddenBy).
case class McpVirtualServer(
  location: EntityLocation = EntityLocation.default,
  id: String,
  enabled: Boolean = true,
  name: String,
  description: String = "",
  tags: Seq[String] = Seq.empty,
  metadata: Map[String, String] = Map.empty,
  config: McpProxyEndpointConfig = McpProxyEndpointConfig.default,
) extends EntityLocationSupport {
  override def internalId: String = id
  override def theName: String = name
  override def theDescription: String = description
  override def theTags: Seq[String] = tags
  override def theMetadata: Map[String, String] = metadata
  override def json: JsValue = McpVirtualServer.format.writes(this)
}

object McpVirtualServer {
  val format = new Format[McpVirtualServer] {
    override def writes(o: McpVirtualServer): JsValue = o.location.jsonWithKey ++ Json.obj(
      "id" -> o.id,
      "enabled" -> o.enabled,
      "name" -> o.name,
      "description" -> o.description,
      "metadata" -> o.metadata,
      "tags" -> JsArray(o.tags.map(JsString.apply)),
      "config" -> o.config.json,
    )

    override def reads(json: JsValue): JsResult[McpVirtualServer] = Try {
      McpVirtualServer(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = (json \ "id").as[String],
        enabled = (json \ "enabled").asOpt[Boolean].getOrElse(true),
        name = (json \ "name").as[String],
        description = (json \ "description").asOpt[String].getOrElse(""),
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        config = (json \ "config").asOpt(McpProxyEndpointConfig.format).getOrElse(McpProxyEndpointConfig.default),
      )
    } match {
      case Failure(ex) => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }

  def resource(env: Env, datastores: AiGatewayExtensionDatastores, states: AiGatewayExtensionState): Resource = {
    Resource(
      "McpVirtualServer",
      "mcp-virtual-servers",
      "mcp-virtual-server",
      "ai-gateway.extensions.cloud-apim.com",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[McpVirtualServer](
        format = McpVirtualServer.format,
        clazz = classOf[McpVirtualServer],
        keyf = id => datastores.mcpVirtualServersDataStore.key(id),
        extractIdf = c => datastores.mcpVirtualServersDataStore.extractId(c),
        extractIdJsonf = json => json.select("id").asString,
        idFieldNamef = () => "id",
        tmpl = (v, p, ctx) => {
          McpVirtualServer(
            id = IdGenerator.namedId("mcp-virtual-server", env),
            enabled = true,
            name = "MCP Virtual Server",
            description = "A new MCP Virtual Server",
            metadata = Map.empty,
            tags = Seq.empty,
            location = EntityLocation.default,
            config = McpProxyEndpointConfig.default,
          ).json
        },
        canRead = true,
        canCreate = true,
        canUpdate = true,
        canDelete = true,
        canBulk = true,
        stateAll = () => states.allMcpVirtualServers(),
        stateOne = id => states.mcpVirtualServer(id),
        stateUpdate = values => states.updateMcpVirtualServers(values)
      )
    )
  }
}

trait McpVirtualServersDataStore extends BasicStore[McpVirtualServer]

class KvMcpVirtualServersDataStore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
  extends McpVirtualServersDataStore
    with RedisLikeStore[McpVirtualServer] {
  override def fmt: Format[McpVirtualServer] = McpVirtualServer.format

  override def redisLike(implicit env: Env): RedisLike = redisCli

  override def key(id: String): String = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:mcpvirtsrv:$id"

  override def extractId(value: McpVirtualServer): String = value.id
}
