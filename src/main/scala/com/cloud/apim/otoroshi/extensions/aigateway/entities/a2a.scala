package com.cloud.apim.otoroshi.extensions.aigateway.entities

import akka.stream.scaladsl.{Sink, Source}
import com.cloud.apim.otoroshi.extensions.aigateway.a2a._
import otoroshi.api.{GenericResourceAccessApiWithState, Resource, ResourceVersion}
import otoroshi.env.Env
import otoroshi.models.{EntityLocation, EntityLocationSupport}
import otoroshi.next.extensions.AdminExtensionId
import otoroshi.next.models.NgTlsConfig
import otoroshi.security.IdGenerator
import otoroshi.storage.{BasicStore, RedisLike, RedisLikeStore}
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.{AiExtension, AiGatewayExtensionDatastores, AiGatewayExtensionState}
import play.api.libs.json._
import play.api.libs.typedmap.TypedKey

import java.util.Base64
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
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

// =====================================================================================================================
// A2A Connector entity — connects to a remote A2A agent and exposes its skills as tools to local agents (like McpConnector).
// =====================================================================================================================

case class A2AConnectorAuth(
  kind: String = "none",            // none | bearer | apikey | basic | custom_headers | oauth2_client_credentials
  token: Option[String] = None,     // bearer
  headerName: Option[String] = None,// apikey header name
  value: Option[String] = None,     // apikey value
  username: Option[String] = None,  // basic
  password: Option[String] = None,  // basic
  headers: Map[String, String] = Map.empty, // custom_headers
) {
  def toHeaders: Seq[(String, String)] = kind.toLowerCase match {
    case "bearer"         => token.map(t => Seq("Authorization" -> s"Bearer $t")).getOrElse(Seq.empty)
    case "apikey"         => (for { h <- headerName; v <- value } yield Seq(h -> v)).getOrElse(Seq.empty)
    case "basic"          => (for { u <- username; p <- password } yield Seq("Authorization" -> s"Basic ${Base64.getEncoder.encodeToString(s"$u:$p".getBytes("UTF-8"))}")).getOrElse(Seq.empty)
    case "custom_headers" => headers.toSeq
    case _                => Seq.empty
  }
  def json: JsValue = Json.obj(
    "kind" -> kind,
    "token" -> token,
    "header_name" -> headerName,
    "value" -> value,
    "username" -> username,
    "password" -> password,
    "headers" -> headers,
  )
}
object A2AConnectorAuth {
  def from(json: JsValue): A2AConnectorAuth = A2AConnectorAuth(
    kind = json.select("kind").asOpt[String].getOrElse("none"),
    token = json.select("token").asOpt[String],
    headerName = json.select("header_name").asOpt[String],
    value = json.select("value").asOpt[String],
    username = json.select("username").asOpt[String],
    password = json.select("password").asOpt[String],
    headers = json.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty),
  )
}

case class A2AConnector(
  location: EntityLocation = EntityLocation.default,
  id: String,
  enabled: Boolean = true,
  name: String,
  description: String = "",
  tags: Seq[String] = Seq.empty,
  metadata: Map[String, String] = Map.empty,
  url: String = "",
  agentCardPath: String = A2A.wellKnownPath,
  agentCardFallbackPath: String = A2A.legacyWellKnownPath,
  a2aVersion: Option[String] = None,   // forces the protocol version; otherwise detected from the Agent Card
  authentication: A2AConnectorAuth = A2AConnectorAuth(),
  tls: NgTlsConfig = NgTlsConfig(),
  timeout: Long = 30000,
  streaming: Boolean = false,
  skillsFilter: Seq[String] = Seq.empty,
  toolNameOverrides: Map[String, String] = Map.empty, // optional skillId -> label (Q6=C)
) extends EntityLocationSupport {
  override def internalId: String = id
  override def theName: String = name
  override def theDescription: String = description
  override def theTags: Seq[String] = tags
  override def theMetadata: Map[String, String] = metadata
  override def json: JsValue = A2AConnector.format.writes(this)

  private def timeoutDuration: FiniteDuration = timeout.millis

  def toolDescription(skill: AgentSkill): String =
    toolNameOverrides.get(skill.id).map(lbl => s"$lbl: ${skill.description}").getOrElse {
      if (skill.name.nonEmpty) s"${skill.name}: ${skill.description}" else skill.description
    }

  // resolve the Agent Card (cached) and apply the skills_filter
  def listSkills(attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Seq[AgentSkill]] = {
    A2AClient.fetchAgentCard(id, url, agentCardPath, agentCardFallbackPath, authentication.toHeaders, tls, timeoutDuration).map {
      case Left(_) => Seq.empty
      case Right((card, _)) =>
        val skills = card.skills
        if (skillsFilter.isEmpty) skills else skills.filter(s => skillsFilter.contains(s.id))
    }
  }

  def listSkillsBlocking(attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Seq[AgentSkill] = {
    Try(Await.result(listSkills(attrs), timeoutDuration + 5.seconds)).getOrElse(Seq.empty)
  }

  // execute a skill: extract the `message` argument, send an A2A SendMessage to the remote agent, return its text
  def call(skillId: String, args: String, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[String] = {
    val message: String = Try(Json.parse(args)).toOption.flatMap(_.select("message").asOpt[String]).getOrElse(args)
    A2AClient.fetchAgentCard(id, url, agentCardPath, agentCardFallbackPath, authentication.toHeaders, tls, timeoutDuration).flatMap {
      case Left(err) => s"error: unable to reach remote a2a agent: ${Json.stringify(err)}".vfuture
      case Right((card, detectedVersion)) =>
        val version = a2aVersion.map(v => A2AVersion.fromCard(Some(v))).getOrElse(detectedVersion)
        val endpoint = A2AClient.jsonRpcEndpoint(card, url)
        val msg = A2AMessage(messageId = A2A.newId("msg"), role = A2ARole.User, parts = Seq(A2APart.ofText(message)))
        A2AClient.sendMessage(endpoint, version, authentication.toHeaders, tls, timeoutDuration, msg).map {
          case Left(err)   => s"error: ${Json.stringify(err)}"
          case Right(text) => text
        }
    }
  }
}

object A2AConnector {
  val format = new Format[A2AConnector] {
    override def writes(o: A2AConnector): JsValue = o.location.jsonWithKey ++ Json.obj(
      "id" -> o.id,
      "enabled" -> o.enabled,
      "name" -> o.name,
      "description" -> o.description,
      "metadata" -> o.metadata,
      "tags" -> JsArray(o.tags.map(JsString.apply)),
      "url" -> o.url,
      "agent_card_path" -> o.agentCardPath,
      "agent_card_fallback_path" -> o.agentCardFallbackPath,
      "a2a_version" -> o.a2aVersion,
      "authentication" -> o.authentication.json,
      "tls" -> o.tls.json,
      "timeout" -> o.timeout,
      "streaming" -> o.streaming,
      "skills_filter" -> o.skillsFilter,
      "tool_name_overrides" -> o.toolNameOverrides,
    )
    override def reads(json: JsValue): JsResult[A2AConnector] = Try {
      A2AConnector(
        location = EntityLocation.readFromKey(json),
        id = (json \ "id").as[String],
        enabled = (json \ "enabled").asOpt[Boolean].getOrElse(true),
        name = (json \ "name").as[String],
        description = (json \ "description").asOpt[String].getOrElse(""),
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        url = json.select("url").asOpt[String].getOrElse(""),
        agentCardPath = json.select("agent_card_path").asOpt[String].getOrElse(A2A.wellKnownPath),
        agentCardFallbackPath = json.select("agent_card_fallback_path").asOpt[String].getOrElse(A2A.legacyWellKnownPath),
        a2aVersion = json.select("a2a_version").asOpt[String],
        authentication = json.select("authentication").asOpt[JsValue].map(A2AConnectorAuth.from).getOrElse(A2AConnectorAuth()),
        tls = json.select("tls").asOpt(NgTlsConfig.format).getOrElse(NgTlsConfig()),
        timeout = json.select("timeout").asOpt[Long].getOrElse(30000L),
        streaming = json.select("streaming").asOpt[Boolean].getOrElse(false),
        skillsFilter = json.select("skills_filter").asOpt[Seq[String]].getOrElse(Seq.empty),
        toolNameOverrides = json.select("tool_name_overrides").asOpt[Map[String, String]].getOrElse(Map.empty),
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }

  def resource(env: Env, datastores: AiGatewayExtensionDatastores, states: AiGatewayExtensionState): Resource = {
    Resource(
      "A2AConnector",
      "a2a-connectors",
      "a2a-connector",
      "ai-gateway.extensions.cloud-apim.com",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[A2AConnector](
        format = A2AConnector.format,
        clazz = classOf[A2AConnector],
        keyf = id => datastores.a2aConnectorsDatastore.key(id),
        extractIdf = c => datastores.a2aConnectorsDatastore.extractId(c),
        extractIdJsonf = json => json.select("id").asString,
        idFieldNamef = () => "id",
        tmpl = (v, p, ctx) => {
          A2AConnector(
            id = IdGenerator.namedId("a2a-connector", env),
            enabled = true,
            name = "A2A Connector",
            description = "A new A2A Connector",
            metadata = Map.empty,
            tags = Seq.empty,
            location = EntityLocation.default,
            url = "https://remote-agent.example.com",
            authentication = A2AConnectorAuth(kind = "none"),
          ).json
        },
        canRead = true,
        canCreate = true,
        canUpdate = true,
        canDelete = true,
        canBulk = true,
        stateAll = () => states.allA2AConnectors(),
        stateOne = id => states.a2aConnector(id),
        stateUpdate = values => states.updateA2AConnectors(values)
      )
    )
  }
}

trait A2AConnectorsDataStore extends BasicStore[A2AConnector]

class KvA2AConnectorsDataStore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
  extends A2AConnectorsDataStore
    with RedisLikeStore[A2AConnector] {
  override def fmt: Format[A2AConnector] = A2AConnector.format
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def key(id: String): String = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:a2aconntr:$id"
  override def extractId(value: A2AConnector): String = value.id
}

// =====================================================================================================================
// A2ASupport — mirrors McpSupport: builds tool defs for remote A2A skills and dispatches tool calls back to them.
// Tool name encoding: a2a___${idx}___${skillId} (idx into the request's a2a connector list, kept short for the 64-char
// function-name limit). The connector list is passed to the dispatcher via attrs (A2AConnectorsKey) so we don't have to
// thread a new param through callWithToolSupport/streamWithToolSupport in every provider.
// =====================================================================================================================
object A2ASupport {

  // the per-request a2a connector id list, set by the provider before tool dispatch, read back by the dispatchers
  val A2AConnectorsKey: TypedKey[Seq[String]] = TypedKey[Seq[String]]("cloud-apim.ai-gateway.A2AConnectors")

  private val messageParams: JsObject = Json.obj(
    "type" -> "object",
    "required" -> Json.arr("message"),
    "additionalProperties" -> false,
    "properties" -> Json.obj(
      "message" -> Json.obj("type" -> "string", "description" -> "The message to send to the remote A2A agent")
    )
  )

  // the connector list comes from attrs (set by the provider before tool build/dispatch) — keeps build & dispatch in sync
  private def connectorsWithSkills(attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Seq[(A2AConnector, Int, AgentSkill)] = {
    val connectors = attrs.get(A2AConnectorsKey).getOrElse(Seq.empty)
    val ext = env.adminExtensions.extension[AiExtension].get
    connectors.zipWithIndex.flatMap { case (cid, idx) => ext.states.a2aConnector(cid).filter(_.enabled).map(c => (c, idx)) }.flatMap {
      case (connector, idx) => connector.listSkillsBlocking(attrs).map(skill => (connector, idx, skill))
    }
  }

  def tools(attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Seq[JsObject] = {
    connectorsWithSkills(attrs).map { case (connector, idx, skill) =>
      Json.obj("type" -> "function", "function" -> Json.obj(
        "name" -> s"a2a___${idx}___${skill.id}",
        "description" -> connector.toolDescription(skill),
        "strict" -> false,
        "parameters" -> messageParams,
      ))
    }
  }

  def toolsAnthropic(attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Seq[JsObject] = {
    connectorsWithSkills(attrs).map { case (connector, idx, skill) =>
      Json.obj(
        "name" -> s"a2a___${idx}___${skill.id}",
        "description" -> connector.toolDescription(skill),
        "input_schema" -> messageParams,
      )
    }
  }

  def toolsCohere(attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): (Seq[JsObject], Map[String, String]) = {
    val map = scala.collection.mutable.Map.empty[String, String]
    val tools = connectorsWithSkills(attrs).map { case (connector, idx, skill) =>
      val key = s"${idx}___${skill.id}".sha256
      map.put(key, s"${idx}___${skill.id}")
      Json.obj("type" -> "function", "function" -> Json.obj(
        "name" -> ("a2a___" + key),
        "description" -> connector.toolDescription(skill),
        "strict" -> false,
        "parameters" -> messageParams,
      ))
    }
    (tools, map.toMap)
  }

  def callToolsOpenai(functions: Seq[GenericApiResponseChoiceMessageToolCall], providerName: String, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    callTool(functions, attrs) { (resp, tc) =>
      Source(List(Json.obj("role" -> "assistant", "tool_calls" -> Json.arr(tc.raw)), Json.obj(
        "role" -> "tool", "content" -> resp, "tool_call_id" -> tc.id
      )))
    }
  }

  def callToolsOllama(functions: Seq[GenericApiResponseChoiceMessageToolCall], attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    callTool(functions, attrs) { (resp, tc) =>
      Source(List(Json.obj("role" -> "assistant", "content" -> "", "tool_calls" -> Json.arr(tc.raw)), Json.obj(
        "role" -> "tool", "content" -> resp
      )))
    }
  }

  def callToolsAnthropic(functions: Seq[AnthropicApiResponseChoiceMessageToolCall], attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    val connectors = attrs.get(A2AConnectorsKey).getOrElse(Seq.empty)
    Source(functions.toList)
      .mapAsync(1) { toolCall =>
        execute(connectors, toolCall.a2aConnectorId, toolCall.a2aFunctionName, toolCall.arguments, attrs).map(r => (r, toolCall))
      }
      .flatMapConcat { case (resp, tc) =>
        Source(List(
          Json.obj("role" -> "assistant", "content" -> Json.arr(Json.obj("type" -> "tool_use", "id" -> tc.id, "name" -> tc.name, "input" -> tc.input))),
          Json.obj("role" -> "user", "content" -> Json.arr(Json.obj("type" -> "tool_result", "tool_use_id" -> tc.id, "content" -> resp)))
        ))
      }
      .runWith(Sink.seq)(env.otoroshiMaterializer)
  }

  def callToolsCohere(functions: Seq[GenericApiResponseChoiceMessageToolCall], fmap: Map[String, String], providerName: String, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    val connectors = attrs.get(A2AConnectorsKey).getOrElse(Seq.empty)
    Source(functions.toList)
      .mapAsync(1) { _toolCall =>
        val fn = _toolCall.raw.select("function").asObject
        val sha = fn.select("name").asString.stripPrefix("a2a___")
        val original = fmap.getOrElse(sha, "0___" + sha)
        val idx = Try(original.split("___")(0).toInt).getOrElse(0)
        val skillId = original.split("___").drop(1).mkString("___")
        execute(connectors, idx, skillId, GenericApiResponseChoiceMessageToolCall(_toolCall.raw).function.arguments, attrs).map(r => (r, _toolCall))
      }
      .flatMapConcat { case (resp, tc) =>
        Source(List(Json.obj("role" -> "assistant", "tool_calls" -> Json.arr(tc.raw)), Json.obj(
          "role" -> "tool", "content" -> resp, "tool_call_id" -> tc.id
        )))
      }
      .runWith(Sink.seq)(env.otoroshiMaterializer)
  }

  private def callTool(functions: Seq[GenericApiResponseChoiceMessageToolCall], attrs: TypedMap)(f: (String, GenericApiResponseChoiceMessageToolCall) => Source[JsValue, _])(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    val connectors = attrs.get(A2AConnectorsKey).getOrElse(Seq.empty)
    Source(functions.toList)
      .mapAsync(1) { toolCall =>
        execute(connectors, toolCall.function.a2aConnectorId, toolCall.function.a2aFunctionName, toolCall.function.arguments, attrs).map(r => (r, toolCall))
      }
      .flatMapConcat { case (resp, tc) => f(resp, tc) }
      .runWith(Sink.seq)(env.otoroshiMaterializer)
  }

  private def execute(connectors: Seq[String], idx: Int, skillId: String, args: String, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[String] = {
    if (idx < 0 || idx >= connectors.size) {
      s"error: unknown a2a connector index ${idx}".vfuture
    } else {
      val ext = env.adminExtensions.extension[AiExtension].get
      ext.states.a2aConnector(connectors(idx)) match {
        case None            => s"error: undefined a2a connector ${connectors(idx)}".vfuture
        case Some(connector) => connector.call(skillId, args, attrs)
      }
    }
  }
}
