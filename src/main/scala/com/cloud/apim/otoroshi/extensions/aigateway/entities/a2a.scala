package com.cloud.apim.otoroshi.extensions.aigateway.entities

import com.cloud.apim.otoroshi.extensions.aigateway.a2a._
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

// =====================================================================================================================
// A2A Server entity — exposes a local agent (inline AgentConfig or workflow ref, decision Q1=B) as an A2A v1.0 server.
// The plugin (plugins/a2a.scala) derives the `supportedInterfaces` of the Agent Card from the route URL at runtime.
// =====================================================================================================================

// ---- Agent Card config (snake_case persisted shape; the runtime card is assembled by the plugin) --------------------
case class A2ASkillConfig(
  id: String,
  name: String,
  description: String,
  tags: Seq[String] = Seq.empty,
  examples: Seq[String] = Seq.empty,
  inputModes: Seq[String] = Seq.empty,
  outputModes: Seq[String] = Seq.empty,
) {
  def toAgentSkill: AgentSkill = AgentSkill(id, name, description, tags, examples, inputModes, outputModes)
  def json: JsValue = Json.obj(
    "id" -> id, "name" -> name, "description" -> description, "tags" -> tags,
    "examples" -> examples, "input_modes" -> inputModes, "output_modes" -> outputModes,
  )
}
object A2ASkillConfig {
  def from(json: JsValue): A2ASkillConfig = A2ASkillConfig(
    id = json.select("id").asString,
    name = json.select("name").asOpt[String].getOrElse(json.select("id").asString),
    description = json.select("description").asOpt[String].getOrElse(""),
    tags = json.select("tags").asOpt[Seq[String]].getOrElse(Seq.empty),
    examples = json.select("examples").asOpt[Seq[String]].getOrElse(Seq.empty),
    inputModes = json.select("input_modes").asOpt[Seq[String]].getOrElse(Seq.empty),
    outputModes = json.select("output_modes").asOpt[Seq[String]].getOrElse(Seq.empty),
  )
}

case class A2AServerCard(
  version: String = "1.0.0",
  provider: Option[AgentProvider] = None,
  defaultInputModes: Seq[String] = Seq("text/plain", "application/json"),
  defaultOutputModes: Seq[String] = Seq("text/plain", "application/json"),
  capabilities: AgentCapabilities = AgentCapabilities(streaming = true),
  skills: Seq[A2ASkillConfig] = Seq.empty,
  documentationUrl: Option[String] = None,
  iconUrl: Option[String] = None,
) {
  def json: JsValue = {
    var obj = Json.obj(
      "version" -> version,
      "default_input_modes" -> defaultInputModes,
      "default_output_modes" -> defaultOutputModes,
      "capabilities" -> Json.obj(
        "streaming" -> capabilities.streaming,
        "push_notifications" -> capabilities.pushNotifications,
        "extended_agent_card" -> capabilities.extendedAgentCard,
      ),
      "skills" -> JsArray(skills.map(_.json)),
    )
    provider.foreach(p => obj = obj ++ Json.obj("provider" -> p.json))
    documentationUrl.foreach(v => obj = obj ++ Json.obj("documentation_url" -> v))
    iconUrl.foreach(v => obj = obj ++ Json.obj("icon_url" -> v))
    obj
  }
}
object A2AServerCard {
  def from(json: JsValue): A2AServerCard = A2AServerCard(
    version = json.select("version").asOpt[String].getOrElse("1.0.0"),
    provider = json.select("provider").asOpt[JsValue].flatMap(AgentProvider.from),
    defaultInputModes = json.select("default_input_modes").asOpt[Seq[String]].getOrElse(Seq("text/plain", "application/json")),
    defaultOutputModes = json.select("default_output_modes").asOpt[Seq[String]].getOrElse(Seq("text/plain", "application/json")),
    capabilities = AgentCapabilities(
      streaming = json.at("capabilities.streaming").asOpt[Boolean].getOrElse(true),
      pushNotifications = json.at("capabilities.push_notifications").asOpt[Boolean].getOrElse(false),
      extendedAgentCard = json.at("capabilities.extended_agent_card").asOpt[Boolean].getOrElse(false),
    ),
    skills = json.select("skills").asOpt[Seq[JsValue]].getOrElse(Seq.empty).map(A2ASkillConfig.from),
    documentationUrl = json.select("documentation_url").asOpt[String],
    iconUrl = json.select("icon_url").asOpt[String],
  )
}

// ---- Backend (Q1=B: inline agent OR workflow ref) ------------------------------------------------------------------
case class A2AServerBackend(
  kind: String = "agent",                 // "agent" | "workflow"
  agent: Option[JsObject] = None,         // raw AgentConfig json when kind == "agent"
  workflowRef: Option[String] = None,     // workflow id when kind == "workflow"
) {
  def json: JsValue = {
    var obj = Json.obj("kind" -> kind)
    agent.foreach(a => obj = obj ++ Json.obj("agent" -> a))
    workflowRef.foreach(r => obj = obj ++ Json.obj("workflow_ref" -> r))
    obj
  }
}
object A2AServerBackend {
  def from(json: JsValue): A2AServerBackend = A2AServerBackend(
    kind = json.select("kind").asOpt[String].getOrElse("agent"),
    agent = json.select("agent").asOpt[JsObject],
    workflowRef = json.select("workflow_ref").asOpt[String],
  )
}

// ---- Entity --------------------------------------------------------------------------------------------------------
case class A2AServer(
  location: EntityLocation = EntityLocation.default,
  id: String,
  enabled: Boolean = true,
  name: String,
  description: String = "",
  tags: Seq[String] = Seq.empty,
  metadata: Map[String, String] = Map.empty,
  agentCard: A2AServerCard = A2AServerCard(),
  backend: A2AServerBackend = A2AServerBackend(),
) extends EntityLocationSupport {
  override def internalId: String = id
  override def theName: String = name
  override def theDescription: String = description
  override def theTags: Seq[String] = tags
  override def theMetadata: Map[String, String] = metadata
  override def json: JsValue = A2AServer.format.writes(this)

  // assemble the runtime Agent Card from config + the route-derived interface url
  def toAgentCard(interfaceUrl: String): AgentCard = AgentCard(
    name = name,
    description = description,
    version = agentCard.version,
    supportedInterfaces = Seq(AgentInterface(url = interfaceUrl, protocolBinding = "JSONRPC", protocolVersion = A2A.protocolVersion)),
    capabilities = agentCard.capabilities,
    defaultInputModes = agentCard.defaultInputModes,
    defaultOutputModes = agentCard.defaultOutputModes,
    skills = agentCard.skills.map(_.toAgentSkill),
    provider = agentCard.provider,
    documentationUrl = agentCard.documentationUrl,
    iconUrl = agentCard.iconUrl,
  )
}

object A2AServer {
  val format = new Format[A2AServer] {
    override def writes(o: A2AServer): JsValue = o.location.jsonWithKey ++ Json.obj(
      "id" -> o.id,
      "enabled" -> o.enabled,
      "name" -> o.name,
      "description" -> o.description,
      "metadata" -> o.metadata,
      "tags" -> JsArray(o.tags.map(JsString.apply)),
      "agent_card" -> o.agentCard.json,
      "backend" -> o.backend.json,
    )
    override def reads(json: JsValue): JsResult[A2AServer] = Try {
      A2AServer(
        location = EntityLocation.readFromKey(json),
        id = (json \ "id").as[String],
        enabled = (json \ "enabled").asOpt[Boolean].getOrElse(true),
        name = (json \ "name").as[String],
        description = (json \ "description").asOpt[String].getOrElse(""),
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        agentCard = json.select("agent_card").asOpt[JsValue].map(A2AServerCard.from).getOrElse(A2AServerCard()),
        backend = json.select("backend").asOpt[JsValue].map(A2AServerBackend.from).getOrElse(A2AServerBackend()),
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }

  def resource(env: Env, datastores: AiGatewayExtensionDatastores, states: AiGatewayExtensionState): Resource = {
    Resource(
      "A2AServer",
      "a2a-servers",
      "a2a-server",
      "ai-gateway.extensions.cloud-apim.com",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[A2AServer](
        format = A2AServer.format,
        clazz = classOf[A2AServer],
        keyf = id => datastores.a2aServersDatastore.key(id),
        extractIdf = c => datastores.a2aServersDatastore.extractId(c),
        extractIdJsonf = json => json.select("id").asString,
        idFieldNamef = () => "id",
        tmpl = (v, p, ctx) => {
          A2AServer(
            id = IdGenerator.namedId("a2a-server", env),
            enabled = true,
            name = "A2A Server",
            description = "A new A2A Server",
            metadata = Map.empty,
            tags = Seq.empty,
            location = EntityLocation.default,
            agentCard = A2AServerCard(
              skills = Seq(A2ASkillConfig(id = "main", name = "Main Skill", description = "The agent's main skill", tags = Seq("general"), examples = Seq("Example request")))
            ),
            backend = A2AServerBackend(
              kind = "agent",
              agent = Some(Json.obj(
                "name" -> "My Agent",
                "description" -> "",
                "instructions" -> Json.arr("You are a helpful assistant."),
                "provider" -> "provider_xxx",
              )),
            ),
          ).json
        },
        canRead = true,
        canCreate = true,
        canUpdate = true,
        canDelete = true,
        canBulk = true,
        stateAll = () => states.allA2AServers(),
        stateOne = id => states.a2aServer(id),
        stateUpdate = values => states.updateA2AServers(values)
      )
    )
  }
}

trait A2AServersDataStore extends BasicStore[A2AServer]

class KvA2AServersDataStore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
  extends A2AServersDataStore
    with RedisLikeStore[A2AServer] {
  override def fmt: Format[A2AServer] = A2AServer.format
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def key(id: String): String = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:a2asrv:$id"
  override def extractId(value: A2AServer): String = value.id
}
