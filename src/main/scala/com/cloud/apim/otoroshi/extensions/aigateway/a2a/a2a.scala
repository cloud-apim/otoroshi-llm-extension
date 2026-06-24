package com.cloud.apim.otoroshi.extensions.aigateway.a2a

import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}
import java.util.UUID
import scala.util.{Failure, Success, Try}

// =====================================================================================================================
// A2A (Agent-to-Agent) protocol models — aligned on spec v1.0.1 (https://github.com/a2aproject/A2A, normative a2a.proto)
//
// Wire format notes (v1.0, ProtoJSON):
//  - no `kind` discriminator anywhere: polymorphism is by JSON member presence
//  - Part is a unified oneof: text | raw (base64 bytes) | url | data, plus mediaType/filename/metadata
//  - enums are SCREAMING_SNAKE_CASE: ROLE_USER/ROLE_AGENT, TASK_STATE_*
//  - SendMessageResponse / StreamResponse encapsulate the payload by member: { "task": ... } | { "message": ... } | ...
//  - field names are camelCase
// =====================================================================================================================

object A2A {
  val protocolVersion: String = "1.0"
  val mediaType: String       = "application/a2a+json"
  val wellKnownPath: String   = "/.well-known/agent-card.json"
  val legacyWellKnownPath: String = "/.well-known/agent.json"

  private val tsFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
  def newId(prefix: String): String = s"${prefix}-${UUID.randomUUID().toString}"
  def nowTimestamp(): String        = tsFormatter.format(Instant.now())
}

// ---------------------------------------------------------------------------------------------------------------------
// Protocol version (used by the connector to talk to remote agents, with optional 0.3.0 backward-compat reading)
// ---------------------------------------------------------------------------------------------------------------------
sealed trait A2AVersion {
  def wire: String
  def isLegacy: Boolean
}
object A2AVersion {
  case object V1_0 extends A2AVersion { val wire = "1.0"; val isLegacy = false }
  case object V0_3 extends A2AVersion { val wire = "0.3"; val isLegacy = true  }
  def fromCard(protocolVersion: Option[String]): A2AVersion = protocolVersion.map(_.trim) match {
    case Some(v) if v.startsWith("0.")  => V0_3
    case _                              => V1_0
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// Role
// ---------------------------------------------------------------------------------------------------------------------
sealed trait A2ARole { def wire: String }
object A2ARole {
  case object User        extends A2ARole { val wire = "ROLE_USER" }
  case object Agent       extends A2ARole { val wire = "ROLE_AGENT" }
  case object Unspecified extends A2ARole { val wire = "ROLE_UNSPECIFIED" }
  def apply(str: String): A2ARole = str match {
    case "ROLE_USER" | "user"   => User
    case "ROLE_AGENT" | "agent" => Agent
    case _                      => Unspecified
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// Task state
// ---------------------------------------------------------------------------------------------------------------------
sealed trait TaskState {
  def wire: String
  def isTerminal: Boolean = false
  def isInterrupted: Boolean = false
}
object TaskState {
  case object Submitted     extends TaskState { val wire = "TASK_STATE_SUBMITTED" }
  case object Working       extends TaskState { val wire = "TASK_STATE_WORKING" }
  case object Completed     extends TaskState { val wire = "TASK_STATE_COMPLETED"; override val isTerminal = true }
  case object Failed        extends TaskState { val wire = "TASK_STATE_FAILED"; override val isTerminal = true }
  case object Canceled      extends TaskState { val wire = "TASK_STATE_CANCELED"; override val isTerminal = true }
  case object Rejected      extends TaskState { val wire = "TASK_STATE_REJECTED"; override val isTerminal = true }
  case object InputRequired extends TaskState { val wire = "TASK_STATE_INPUT_REQUIRED"; override val isInterrupted = true }
  case object AuthRequired  extends TaskState { val wire = "TASK_STATE_AUTH_REQUIRED"; override val isInterrupted = true }
  case object Unspecified   extends TaskState { val wire = "TASK_STATE_UNSPECIFIED" }

  val all: Seq[TaskState] = Seq(Submitted, Working, Completed, Failed, Canceled, Rejected, InputRequired, AuthRequired, Unspecified)
  def apply(str: String): TaskState = all.find(s => s.wire == str).orElse(all.find(_.wire.toLowerCase.replace("task_state_", "").replace("_", "-") == str)).getOrElse(Unspecified)
}

// ---------------------------------------------------------------------------------------------------------------------
// Part (unified oneof: text | raw | url | data)
// ---------------------------------------------------------------------------------------------------------------------
case class A2APart(
  text: Option[String] = None,
  raw: Option[String] = None,           // base64-encoded bytes
  url: Option[String] = None,
  data: Option[JsValue] = None,
  mediaType: Option[String] = None,
  filename: Option[String] = None,
  metadata: Option[JsObject] = None,
) {
  def json: JsValue = A2APart.format.writes(this)
  def isText: Boolean = text.isDefined
  def isFile: Boolean = url.isDefined || raw.isDefined
  def isData: Boolean = data.isDefined
  // best-effort textual rendering of a part (used when mapping to a chat message)
  def asText: String = {
    text
      .orElse(data.map(Json.stringify))
      .orElse(url)
      .getOrElse("")
  }
}

object A2APart {
  def ofText(t: String): A2APart = A2APart(text = Some(t), mediaType = Some("text/plain"))
  def ofData(d: JsValue): A2APart = A2APart(data = Some(d), mediaType = Some("application/json"))
  def ofUrl(u: String, mediaType: Option[String] = None, filename: Option[String] = None): A2APart = A2APart(url = Some(u), mediaType = mediaType, filename = filename)

  val format: Format[A2APart] = new Format[A2APart] {
    override def writes(o: A2APart): JsValue = {
      var obj = Json.obj()
      o.text.foreach(v => obj = obj ++ Json.obj("text" -> v))
      o.raw.foreach(v => obj = obj ++ Json.obj("raw" -> v))
      o.url.foreach(v => obj = obj ++ Json.obj("url" -> v))
      o.data.foreach(v => obj = obj ++ Json.obj("data" -> v))
      o.mediaType.foreach(v => obj = obj ++ Json.obj("mediaType" -> v))
      o.filename.foreach(v => obj = obj ++ Json.obj("filename" -> v))
      o.metadata.foreach(v => obj = obj ++ Json.obj("metadata" -> v))
      obj
    }
    override def reads(json: JsValue): JsResult[A2APart] = Try {
      A2APart(
        text = json.select("text").asOpt[String],
        raw = json.select("raw").asOpt[String],
        url = json.select("url").asOpt[String],
        data = (json \ "data").toOption.filterNot(_ == JsNull),
        mediaType = json.select("mediaType").asOpt[String].orElse(json.select("mimeType").asOpt[String]),
        filename = json.select("filename").asOpt[String].orElse(json.select("name").asOpt[String]),
        metadata = json.select("metadata").asOpt[JsObject],
      )
    } match {
      case Failure(ex) => JsError(ex.getMessage)
      case Success(v)  => JsSuccess(v)
    }
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// Message
// ---------------------------------------------------------------------------------------------------------------------
case class A2AMessage(
  messageId: String,
  role: A2ARole,
  parts: Seq[A2APart],
  contextId: Option[String] = None,
  taskId: Option[String] = None,
  metadata: Option[JsObject] = None,
  extensions: Seq[String] = Seq.empty,
  referenceTaskIds: Seq[String] = Seq.empty,
) {
  def json: JsValue = A2AMessage.format.writes(this)
  def textContent: String = parts.map(_.asText).filter(_.nonEmpty).mkString("\n")
}

object A2AMessage {
  def agentText(text: String, contextId: Option[String] = None, taskId: Option[String] = None): A2AMessage = A2AMessage(
    messageId = A2A.newId("msg"),
    role = A2ARole.Agent,
    parts = Seq(A2APart.ofText(text)),
    contextId = contextId,
    taskId = taskId,
  )

  val format: Format[A2AMessage] = new Format[A2AMessage] {
    override def writes(o: A2AMessage): JsValue = {
      var obj = Json.obj(
        "messageId" -> o.messageId,
        "role" -> o.role.wire,
        "parts" -> JsArray(o.parts.map(_.json)),
      )
      o.contextId.foreach(v => obj = obj ++ Json.obj("contextId" -> v))
      o.taskId.foreach(v => obj = obj ++ Json.obj("taskId" -> v))
      o.metadata.foreach(v => obj = obj ++ Json.obj("metadata" -> v))
      if (o.extensions.nonEmpty) obj = obj ++ Json.obj("extensions" -> o.extensions)
      if (o.referenceTaskIds.nonEmpty) obj = obj ++ Json.obj("referenceTaskIds" -> o.referenceTaskIds)
      obj
    }
    override def reads(json: JsValue): JsResult[A2AMessage] = Try {
      A2AMessage(
        messageId = json.select("messageId").asOpt[String].getOrElse(A2A.newId("msg")),
        role = json.select("role").asOpt[String].map(A2ARole.apply).getOrElse(A2ARole.User),
        parts = json.select("parts").asOpt[Seq[JsValue]].getOrElse(Seq.empty).flatMap(p => A2APart.format.reads(p).asOpt),
        contextId = json.select("contextId").asOpt[String],
        taskId = json.select("taskId").asOpt[String],
        metadata = json.select("metadata").asOpt[JsObject],
        extensions = json.select("extensions").asOpt[Seq[String]].getOrElse(Seq.empty),
        referenceTaskIds = json.select("referenceTaskIds").asOpt[Seq[String]].getOrElse(Seq.empty),
      )
    } match {
      case Failure(ex) => JsError(ex.getMessage)
      case Success(v)  => JsSuccess(v)
    }
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// Artifact
// ---------------------------------------------------------------------------------------------------------------------
case class Artifact(
  artifactId: String,
  parts: Seq[A2APart],
  name: Option[String] = None,
  description: Option[String] = None,
  metadata: Option[JsObject] = None,
  extensions: Seq[String] = Seq.empty,
) {
  def json: JsValue = Artifact.format.writes(this)
}

object Artifact {
  val format: Format[Artifact] = new Format[Artifact] {
    override def writes(o: Artifact): JsValue = {
      var obj = Json.obj(
        "artifactId" -> o.artifactId,
        "parts" -> JsArray(o.parts.map(_.json)),
      )
      o.name.foreach(v => obj = obj ++ Json.obj("name" -> v))
      o.description.foreach(v => obj = obj ++ Json.obj("description" -> v))
      o.metadata.foreach(v => obj = obj ++ Json.obj("metadata" -> v))
      if (o.extensions.nonEmpty) obj = obj ++ Json.obj("extensions" -> o.extensions)
      obj
    }
    override def reads(json: JsValue): JsResult[Artifact] = Try {
      Artifact(
        artifactId = json.select("artifactId").asOpt[String].getOrElse(A2A.newId("art")),
        parts = json.select("parts").asOpt[Seq[JsValue]].getOrElse(Seq.empty).flatMap(p => A2APart.format.reads(p).asOpt),
        name = json.select("name").asOpt[String],
        description = json.select("description").asOpt[String],
        metadata = json.select("metadata").asOpt[JsObject],
        extensions = json.select("extensions").asOpt[Seq[String]].getOrElse(Seq.empty),
      )
    } match {
      case Failure(ex) => JsError(ex.getMessage)
      case Success(v)  => JsSuccess(v)
    }
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// Task status + Task
// ---------------------------------------------------------------------------------------------------------------------
case class TaskStatus(
  state: TaskState,
  message: Option[A2AMessage] = None,
  timestamp: Option[String] = None,
) {
  def json: JsValue = TaskStatus.format.writes(this)
}

object TaskStatus {
  val format: Format[TaskStatus] = new Format[TaskStatus] {
    override def writes(o: TaskStatus): JsValue = {
      var obj = Json.obj("state" -> o.state.wire)
      o.message.foreach(v => obj = obj ++ Json.obj("message" -> v.json))
      o.timestamp.foreach(v => obj = obj ++ Json.obj("timestamp" -> v))
      obj
    }
    override def reads(json: JsValue): JsResult[TaskStatus] = Try {
      TaskStatus(
        state = json.select("state").asOpt[String].map(TaskState.apply).getOrElse(TaskState.Unspecified),
        message = json.select("message").asOpt[JsValue].flatMap(m => A2AMessage.format.reads(m).asOpt),
        timestamp = json.select("timestamp").asOpt[String],
      )
    } match {
      case Failure(ex) => JsError(ex.getMessage)
      case Success(v)  => JsSuccess(v)
    }
  }
}

case class A2ATask(
  id: String,
  contextId: String,
  status: TaskStatus,
  artifacts: Seq[Artifact] = Seq.empty,
  history: Seq[A2AMessage] = Seq.empty,
  metadata: Option[JsObject] = None,
) {
  def json: JsValue = A2ATask.format.writes(this)
}

object A2ATask {
  val format: Format[A2ATask] = new Format[A2ATask] {
    override def writes(o: A2ATask): JsValue = {
      var obj = Json.obj(
        "id" -> o.id,
        "contextId" -> o.contextId,
        "status" -> o.status.json,
        "artifacts" -> JsArray(o.artifacts.map(_.json)),
        "history" -> JsArray(o.history.map(_.json)),
      )
      o.metadata.foreach(v => obj = obj ++ Json.obj("metadata" -> v))
      obj
    }
    override def reads(json: JsValue): JsResult[A2ATask] = Try {
      A2ATask(
        id = json.select("id").asOpt[String].getOrElse(A2A.newId("task")),
        contextId = json.select("contextId").asOpt[String].getOrElse(A2A.newId("ctx")),
        status = json.select("status").asOpt[JsValue].flatMap(s => TaskStatus.format.reads(s).asOpt).getOrElse(TaskStatus(TaskState.Unspecified)),
        artifacts = json.select("artifacts").asOpt[Seq[JsValue]].getOrElse(Seq.empty).flatMap(a => Artifact.format.reads(a).asOpt),
        history = json.select("history").asOpt[Seq[JsValue]].getOrElse(Seq.empty).flatMap(m => A2AMessage.format.reads(m).asOpt),
        metadata = json.select("metadata").asOpt[JsObject],
      )
    } match {
      case Failure(ex) => JsError(ex.getMessage)
      case Success(v)  => JsSuccess(v)
    }
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// Streaming events + StreamResponse (oneof: task | message | statusUpdate | artifactUpdate)
// ---------------------------------------------------------------------------------------------------------------------
case class TaskStatusUpdateEvent(taskId: String, contextId: String, status: TaskStatus, metadata: Option[JsObject] = None) {
  def json: JsValue = {
    var obj = Json.obj("taskId" -> taskId, "contextId" -> contextId, "status" -> status.json)
    metadata.foreach(v => obj = obj ++ Json.obj("metadata" -> v))
    obj
  }
}

case class TaskArtifactUpdateEvent(taskId: String, contextId: String, artifact: Artifact, append: Boolean = false, lastChunk: Boolean = false, metadata: Option[JsObject] = None) {
  def json: JsValue = {
    var obj = Json.obj("taskId" -> taskId, "contextId" -> contextId, "artifact" -> artifact.json, "append" -> append, "lastChunk" -> lastChunk)
    metadata.foreach(v => obj = obj ++ Json.obj("metadata" -> v))
    obj
  }
}

sealed trait StreamResponse {
  // the value goes into the JSON-RPC `result` field, encapsulated by member
  def resultJson: JsObject
}
object StreamResponse {
  case class OfTask(task: A2ATask) extends StreamResponse { def resultJson: JsObject = Json.obj("task" -> task.json) }
  case class OfMessage(message: A2AMessage) extends StreamResponse { def resultJson: JsObject = Json.obj("message" -> message.json) }
  case class OfStatusUpdate(event: TaskStatusUpdateEvent) extends StreamResponse { def resultJson: JsObject = Json.obj("statusUpdate" -> event.json) }
  case class OfArtifactUpdate(event: TaskArtifactUpdateEvent) extends StreamResponse { def resultJson: JsObject = Json.obj("artifactUpdate" -> event.json) }

  // reads a StreamResponse from a JSON-RPC `result` object (member-based)
  def fromResult(result: JsValue): Option[StreamResponse] = {
    result.select("statusUpdate").asOpt[JsValue].map(v => OfStatusUpdate(TaskStatusUpdateEvent(
      taskId = v.select("taskId").asString,
      contextId = v.select("contextId").asString,
      status = v.select("status").asOpt[JsValue].flatMap(s => TaskStatus.format.reads(s).asOpt).getOrElse(TaskStatus(TaskState.Unspecified)),
    ))).orElse(
      result.select("artifactUpdate").asOpt[JsValue].map(v => OfArtifactUpdate(TaskArtifactUpdateEvent(
        taskId = v.select("taskId").asString,
        contextId = v.select("contextId").asString,
        artifact = v.select("artifact").asOpt[JsValue].flatMap(a => Artifact.format.reads(a).asOpt).getOrElse(Artifact(A2A.newId("art"), Seq.empty)),
        append = v.select("append").asOpt[Boolean].getOrElse(false),
        lastChunk = v.select("lastChunk").asOpt[Boolean].getOrElse(false),
      )))
    ).orElse(
      result.select("task").asOpt[JsValue].flatMap(t => A2ATask.format.reads(t).asOpt).map(OfTask.apply)
    ).orElse(
      result.select("message").asOpt[JsValue].flatMap(m => A2AMessage.format.reads(m).asOpt).map(OfMessage.apply)
    )
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// Agent Card
// ---------------------------------------------------------------------------------------------------------------------
case class AgentInterface(url: String, protocolBinding: String = "JSONRPC", protocolVersion: String = A2A.protocolVersion, tenant: Option[String] = None) {
  def json: JsValue = {
    var obj = Json.obj("url" -> url, "protocolBinding" -> protocolBinding, "protocolVersion" -> protocolVersion)
    tenant.foreach(v => obj = obj ++ Json.obj("tenant" -> v))
    obj
  }
}
object AgentInterface {
  def from(json: JsValue): AgentInterface = AgentInterface(
    url = json.select("url").asString,
    protocolBinding = json.select("protocolBinding").asOpt[String].getOrElse("JSONRPC"),
    protocolVersion = json.select("protocolVersion").asOpt[String].getOrElse(A2A.protocolVersion),
    tenant = json.select("tenant").asOpt[String],
  )
}

case class AgentCapabilities(streaming: Boolean = false, pushNotifications: Boolean = false, extendedAgentCard: Boolean = false) {
  def json: JsValue = Json.obj("streaming" -> streaming, "pushNotifications" -> pushNotifications, "extendedAgentCard" -> extendedAgentCard)
}
object AgentCapabilities {
  def from(json: JsValue): AgentCapabilities = AgentCapabilities(
    streaming = json.select("streaming").asOpt[Boolean].getOrElse(false),
    pushNotifications = json.select("pushNotifications").asOpt[Boolean].orElse(json.select("push_notifications").asOpt[Boolean]).getOrElse(false),
    extendedAgentCard = json.select("extendedAgentCard").asOpt[Boolean].orElse(json.select("extended_agent_card").asOpt[Boolean]).getOrElse(false),
  )
}

case class AgentProvider(organization: String, url: String) {
  def json: JsValue = Json.obj("organization" -> organization, "url" -> url)
}
object AgentProvider {
  def from(json: JsValue): Option[AgentProvider] = for {
    org <- json.select("organization").asOpt[String]
    url <- json.select("url").asOpt[String]
  } yield AgentProvider(org, url)
}

case class AgentSkill(
  id: String,
  name: String,
  description: String,
  tags: Seq[String] = Seq.empty,
  examples: Seq[String] = Seq.empty,
  inputModes: Seq[String] = Seq.empty,
  outputModes: Seq[String] = Seq.empty,
) {
  def json: JsValue = {
    var obj = Json.obj("id" -> id, "name" -> name, "description" -> description, "tags" -> tags)
    if (examples.nonEmpty) obj = obj ++ Json.obj("examples" -> examples)
    if (inputModes.nonEmpty) obj = obj ++ Json.obj("inputModes" -> inputModes)
    if (outputModes.nonEmpty) obj = obj ++ Json.obj("outputModes" -> outputModes)
    obj
  }
}
object AgentSkill {
  def from(json: JsValue): AgentSkill = AgentSkill(
    id = json.select("id").asString,
    name = json.select("name").asOpt[String].getOrElse(json.select("id").asString),
    description = json.select("description").asOpt[String].getOrElse(""),
    tags = json.select("tags").asOpt[Seq[String]].getOrElse(Seq.empty),
    examples = json.select("examples").asOpt[Seq[String]].getOrElse(Seq.empty),
    inputModes = json.select("inputModes").asOpt[Seq[String]].getOrElse(Seq.empty),
    outputModes = json.select("outputModes").asOpt[Seq[String]].getOrElse(Seq.empty),
  )
}

case class AgentCard(
  name: String,
  description: String,
  version: String,
  supportedInterfaces: Seq[AgentInterface],
  capabilities: AgentCapabilities = AgentCapabilities(),
  defaultInputModes: Seq[String] = Seq("text/plain", "application/json"),
  defaultOutputModes: Seq[String] = Seq("text/plain", "application/json"),
  skills: Seq[AgentSkill] = Seq.empty,
  provider: Option[AgentProvider] = None,
  documentationUrl: Option[String] = None,
  iconUrl: Option[String] = None,
  securitySchemes: Option[JsObject] = None,
  security: Option[JsArray] = None,
) {
  def json: JsValue = {
    var obj = Json.obj(
      "name" -> name,
      "description" -> description,
      "version" -> version,
      "supportedInterfaces" -> JsArray(supportedInterfaces.map(_.json)),
      "capabilities" -> capabilities.json,
      "defaultInputModes" -> defaultInputModes,
      "defaultOutputModes" -> defaultOutputModes,
      "skills" -> JsArray(skills.map(_.json)),
    )
    provider.foreach(v => obj = obj ++ Json.obj("provider" -> v.json))
    documentationUrl.foreach(v => obj = obj ++ Json.obj("documentationUrl" -> v))
    iconUrl.foreach(v => obj = obj ++ Json.obj("iconUrl" -> v))
    securitySchemes.foreach(v => obj = obj ++ Json.obj("securitySchemes" -> v))
    security.foreach(v => obj = obj ++ Json.obj("security" -> v))
    obj
  }
}

object AgentCard {
  def from(json: JsValue): AgentCard = AgentCard(
    name = json.select("name").asOpt[String].getOrElse(""),
    description = json.select("description").asOpt[String].getOrElse(""),
    version = json.select("version").asOpt[String].getOrElse("1.0.0"),
    supportedInterfaces = json.select("supportedInterfaces").asOpt[Seq[JsValue]].getOrElse(Seq.empty).map(AgentInterface.from),
    capabilities = json.select("capabilities").asOpt[JsValue].map(AgentCapabilities.from).getOrElse(AgentCapabilities()),
    defaultInputModes = json.select("defaultInputModes").asOpt[Seq[String]].getOrElse(Seq("text/plain", "application/json")),
    defaultOutputModes = json.select("defaultOutputModes").asOpt[Seq[String]].getOrElse(Seq("text/plain", "application/json")),
    skills = json.select("skills").asOpt[Seq[JsValue]].getOrElse(Seq.empty).map(AgentSkill.from),
    provider = json.select("provider").asOpt[JsValue].flatMap(AgentProvider.from),
    documentationUrl = json.select("documentationUrl").asOpt[String],
    iconUrl = json.select("iconUrl").asOpt[String],
    securitySchemes = json.select("securitySchemes").asOpt[JsObject],
    security = json.select("security").asOpt[JsArray],
  )
}

// ---------------------------------------------------------------------------------------------------------------------
// JSON-RPC error codes (v1.0) + google.rpc.ErrorInfo encoding
// ---------------------------------------------------------------------------------------------------------------------
object A2AErrors {
  // standard JSON-RPC
  val ParseError     = -32700
  val InvalidRequest = -32600
  val MethodNotFound = -32601
  val InvalidParams  = -32602
  val InternalError  = -32603
  // A2A-specific
  val TaskNotFound                   = -32001
  val TaskNotCancelable              = -32002
  val PushNotificationNotSupported   = -32003
  val UnsupportedOperation           = -32004
  val ContentTypeNotSupported        = -32005
  val InvalidAgentResponse           = -32006
  val ExtendedAgentCardNotConfigured = -32007
  val ExtensionSupportRequired       = -32008
  val VersionNotSupported            = -32009

  val domain = "a2a-protocol.org"

  def errorInfo(reason: String, metadata: JsObject = Json.obj()): JsArray = Json.arr(Json.obj(
    "@type" -> "type.googleapis.com/google.rpc.ErrorInfo",
    "reason" -> reason,
    "domain" -> domain,
    "metadata" -> metadata,
  ))
}

// helpers to build JSON-RPC envelopes
object A2AJsonRpc {
  def ok(id: JsValue, result: JsValue): JsObject = Json.obj("jsonrpc" -> "2.0", "id" -> id, "result" -> result)
  def err(id: JsValue, code: Int, message: String, data: Option[JsValue] = None): JsObject = {
    var error = Json.obj("code" -> code, "message" -> message)
    data.foreach(d => error = error ++ Json.obj("data" -> d))
    Json.obj("jsonrpc" -> "2.0", "id" -> id, "error" -> error)
  }
}
