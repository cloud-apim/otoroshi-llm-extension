package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.a2a._
import com.cloud.apim.otoroshi.extensions.aigateway.agents.{AgentConfig, AgentInput, AgentRunConfig}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.A2AServer
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatMessageContent, ChatResponseChunk, InputChatMessage}
import otoroshi.env.Env
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.next.workflow.{Node, WorkflowAdminExtension}
import otoroshi.utils.TypedMap
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json._
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

// =====================================================================================================================
// A2A Server plugins (decision Q1=B, user note: separate Agent Card plugin + combine via NgPresetPlugin)
//  - A2AServerPlugin     : JSON-RPC binding dispatch (SendMessage sync in phase 1) on the route root
//  - A2AAgentCardPlugin  : serves the Agent Card on /.well-known/agent-card.json
//  - A2AServerPreset     : bundles the two from a single A2AServer reference
// =====================================================================================================================

case class A2AServerPluginConfig(ref: String) extends NgPluginConfig {
  def json: JsValue = A2AServerPluginConfig.format.writes(this)
}
object A2AServerPluginConfig {
  val default = A2AServerPluginConfig("")
  val configFlow: Seq[String] = Seq("ref")
  val configSchema: Option[JsObject] = Some(Json.obj(
    "ref" -> Json.obj(
      "type" -> "select",
      "label" -> "A2A Server",
      "props" -> Json.obj(
        "optionsFrom" -> "/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/a2a-servers",
        "optionsTransformer" -> Json.obj("label" -> "name", "value" -> "id"),
      ),
    )
  ))
  val format = new Format[A2AServerPluginConfig] {
    override def writes(o: A2AServerPluginConfig): JsValue = Json.obj("ref" -> o.ref)
    override def reads(json: JsValue): JsResult[A2AServerPluginConfig] = Try {
      A2AServerPluginConfig(ref = json.select("ref").asOpt[String].getOrElse(""))
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(v) => JsSuccess(v)
    }
  }
}

object A2ASupportServer {

  // base url of the JSON-RPC endpoint (route root), derived from the incoming request
  def interfaceUrl(ctx: NgbBackendCallContext)(implicit env: Env): String = {
    val proto = ctx.rawRequest.theProtocol
    val host = ctx.rawRequest.theHost
    val basePath = ctx.request.path
      .replace(A2A.wellKnownPath, "")
      .replace(A2A.legacyWellKnownPath, "")
    s"$proto://$host$basePath"
  }

  def server(ref: String)(implicit env: Env): Option[A2AServer] =
    env.adminExtensions.extension[AiExtension].flatMap(_.states.a2aServer(ref))

  // map A2A message parts (v1.0 unified part: text|url|raw|data + mediaType) to the extension's chat content parts
  def partsToContent(parts: Seq[A2APart]): Seq[ChatMessageContent] = parts.flatMap { p =>
    if (p.text.isDefined) Some(ChatMessageContent.TextContent(p.text.get))
    else if (p.data.isDefined) Some(ChatMessageContent.TextContent(Json.stringify(p.data.get)))
    else if (p.url.isDefined || p.raw.isDefined) {
      val mt = p.mediaType.getOrElse("application/octet-stream")
      val url = p.url
      val data = p.raw.map(_.byteString.decodeBase64)
      val content: ChatMessageContent =
        if (mt.startsWith("image/")) ChatMessageContent.ImageContent(mt, url, data)
        else if (mt.startsWith("audio/")) ChatMessageContent.AudioContent(mt, url, data)
        else if (mt.startsWith("video/")) ChatMessageContent.VideoContent(mt, url, data)
        else if (mt == "application/pdf") ChatMessageContent.PdfFileContent(url, data, None, None, None)
        else ChatMessageContent.TextFileContent(url, data, None, None, None)
      Some(content)
    } else None
  }

  def buildAgentInput(message: A2AMessage): AgentInput = {
    val parts = partsToContent(message.parts)
    val finalParts = if (parts.isEmpty) Seq(ChatMessageContent.TextContent(message.textContent)) else parts
    AgentInput(Seq(InputChatMessage("user", finalParts, None, None, Json.obj())))
  }

  // execute the configured backend (inline agent or workflow ref) for an A2A message, return the textual result
  def executeBackend(srv: A2AServer, message: A2AMessage, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Either[JsValue, String]] = {
    val text = message.textContent
    srv.backend.kind.toLowerCase match {
      case "agent" =>
        srv.backend.agent match {
          case None => Json.obj("error" -> "no inline agent configured").leftf
          case Some(agentJson) =>
            Try(AgentConfig.from(agentJson)) match {
              case Failure(e) => Json.obj("error" -> s"invalid agent config: ${e.getMessage}").leftf
              case Success(agent) => agent.run(buildAgentInput(message), AgentRunConfig(), attrs, None)(env).map(_.map(_.wholeTextContent))
            }
        }
      case "workflow" =>
        srv.backend.workflowRef match {
          case None => Json.obj("error" -> "no workflow_ref configured").leftf
          case Some(ref) =>
            env.adminExtensions.extension[WorkflowAdminExtension] match {
              case None => Json.obj("error" -> "workflow extension not found").leftf
              case Some(extension) =>
                extension.workflow(ref) match {
                  case None => Json.obj("error" -> "workflow not found").leftf
                  case Some(workflow) =>
                    val input = Json.obj("input" -> text, "message" -> text)
                    extension.engine.run(ref, Node.from(workflow.config), input, attrs, workflow.functions).map { res =>
                      res.error match {
                        case Some(err) => Left(err.json)
                        case None =>
                          val returned = res.returned.getOrElse(JsString(""))
                          val str = returned match {
                            case JsString(s) => s
                            case other => Json.stringify(other)
                          }
                          Right(str)
                      }
                    }
                }
            }
        }
      case other => Json.obj("error" -> s"unknown backend kind: $other").leftf
    }
  }

  // streaming variant for the inline-agent backend; returns Left for backends/agents that can't stream token-by-token
  // (workflow, handoffs, built-in tools) so the caller falls back to a blocking single-artifact emission.
  def streamBackend(srv: A2AServer, message: A2AMessage, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    srv.backend.kind.toLowerCase match {
      case "agent" =>
        srv.backend.agent match {
          case None => Json.obj("error" -> "no inline agent configured").leftf
          case Some(agentJson) =>
            Try(AgentConfig.from(agentJson)) match {
              case Failure(e) => Json.obj("error" -> s"invalid agent config: ${e.getMessage}").leftf
              case Success(agent) => agent.stream(buildAgentInput(message), AgentRunConfig(), attrs, None)(env)
            }
        }
      case _ => Json.obj("error" -> "backend not streamable").leftf
    }
  }
}

class A2AAgentCardPlugin extends NgBackendCall {

  override def name: String = "Cloud APIM - A2A Agent Card"
  override def description: Option[String] = "Serves the A2A Agent Card (v1.0) at /.well-known/agent-card.json for an A2A Server.".some
  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))
  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)
  override def useDelegates: Boolean = false
  override def defaultConfigObject: Option[NgPluginConfig] = Some(A2AServerPluginConfig.default)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = A2AServerPluginConfig.configFlow
  override def configSchema: Option[JsObject] = A2AServerPluginConfig.configSchema

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(A2AServerPluginConfig.format).getOrElse(A2AServerPluginConfig.default)
    A2ASupportServer.server(config.ref) match {
      case None => NgProxyEngineError.NgResultProxyEngineError(Results.NotFound(Json.obj("error" -> "a2a server not found"))).leftf
      case Some(srv) if !srv.enabled => NgProxyEngineError.NgResultProxyEngineError(Results.NotFound(Json.obj("error" -> "a2a server disabled"))).leftf
      case Some(srv) =>
        val card = srv.toAgentCard(A2ASupportServer.interfaceUrl(ctx))
        BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok(card.json).withHeaders("Cache-Control" -> "max-age=300")), None).rightf
    }
  }
}

class A2AServerPlugin extends NgBackendCall {

  override def name: String = "Cloud APIM - A2A Server"
  override def description: Option[String] = "Exposes a local agent (or workflow) as an A2A v1.0 server over the JSON-RPC binding (SendMessage).".some
  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))
  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)
  override def useDelegates: Boolean = false
  override def defaultConfigObject: Option[NgPluginConfig] = Some(A2AServerPluginConfig.default)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = A2AServerPluginConfig.configFlow
  override def configSchema: Option[JsObject] = A2AServerPluginConfig.configSchema

  private def rpcOk(id: JsValue, result: JsValue): Future[Either[NgProxyEngineError, BackendCallResponse]] =
    BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok(A2AJsonRpc.ok(id, result))), None).rightf

  private def rpcErr(id: JsValue, code: Int, message: String, data: Option[JsValue] = None): Future[Either[NgProxyEngineError, BackendCallResponse]] =
    BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok(A2AJsonRpc.err(id, code, message, data))), None).rightf

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(A2AServerPluginConfig.format).getOrElse(A2AServerPluginConfig.default)
    val method = ctx.request.method.toUpperCase()
    val path = ctx.request.path
    A2ASupportServer.server(config.ref) match {
      case None => NgProxyEngineError.NgResultProxyEngineError(Results.NotFound(Json.obj("error" -> "a2a server not found"))).leftf
      case Some(srv) if !srv.enabled => NgProxyEngineError.NgResultProxyEngineError(Results.NotFound(Json.obj("error" -> "a2a server disabled"))).leftf
      case Some(srv) =>
        if (method == "GET" && (path.endsWith("agent-card.json") || path.endsWith("agent.json"))) {
          val card = srv.toAgentCard(A2ASupportServer.interfaceUrl(ctx))
          BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok(card.json).withHeaders("Cache-Control" -> "max-age=300")), None).rightf
        } else if (method != "POST") {
          NgProxyEngineError.NgResultProxyEngineError(Results.MethodNotAllowed(Json.obj("error" -> s"method not allowed: $method"))).leftf
        } else if (!ctx.request.hasBody) {
          NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(Json.obj("error" -> "empty body"))).leftf
        } else {
          ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
            Try(bodyRaw.utf8String.parseJson) match {
              case Failure(_) => rpcErr(JsNull, A2AErrors.ParseError, "invalid json payload")
              case Success(json) =>
                val rpcMethod = json.select("method").asOpt[String].getOrElse("--")
                val id: JsValue = (json \ "id").toOption.getOrElse(JsNull)
                rpcMethod match {
                  case "SendMessage"          => handleSendMessage(id, json, srv, ctx)
                  case "SendStreamingMessage" => handleSendStreamingMessage(id, json, srv, ctx)
                  case "GetTask"              => handleGetTask(id, json)
                  case "CancelTask"           => handleCancelTask(id, json)
                  case "ListTasks"            => rpcErr(id, A2AErrors.UnsupportedOperation, "ListTasks not implemented yet (phase 4)")
                  case other                  => rpcErr(id, A2AErrors.MethodNotFound, s"method not found: $other")
                }
            }
          }
        }
    }
  }

  private def handleSendMessage(id: JsValue, json: JsValue, srv: A2AServer, ctx: NgbBackendCallContext)(implicit env: Env, ec: ExecutionContext): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val params = json.select("params").asOpt[JsObject].getOrElse(Json.obj())
    val msgJson = params.select("message").asOpt[JsValue].getOrElse(Json.obj())
    A2AMessage.format.reads(msgJson).asOpt match {
      case None => rpcErr(id, A2AErrors.InvalidParams, "missing or invalid 'message' param")
      case Some(message) =>
        val text = message.textContent
        if (text.trim.isEmpty) {
          rpcErr(id, A2AErrors.InvalidParams, "empty message content")
        } else {
          val store = new A2ATaskStore(env)
          val contextId = message.contextId.getOrElse(A2A.newId("ctx"))
          val taskId = A2A.newId("task")
          A2ASupportServer.executeBackend(srv, message, ctx.attrs).flatMap {
            case Right(responseText) =>
              val agentMsg = A2AMessage.agentText(responseText, Some(contextId), Some(taskId))
              val task = A2ATask(
                id = taskId,
                contextId = contextId,
                status = TaskStatus(TaskState.Completed, Some(agentMsg), Some(A2A.nowTimestamp())),
                history = Seq(message, agentMsg),
              )
              store.put(task).flatMap(_ => rpcOk(id, Json.obj("task" -> task.json)))
            case Left(err) =>
              val failMsg = A2AMessage.agentText(Json.stringify(err), Some(contextId), Some(taskId))
              val task = A2ATask(
                id = taskId,
                contextId = contextId,
                status = TaskStatus(TaskState.Failed, Some(failMsg), Some(A2A.nowTimestamp())),
                history = Seq(message),
              )
              store.put(task).flatMap(_ => rpcOk(id, Json.obj("task" -> task.json)))
          }.recoverWith { case t: Throwable =>
            rpcErr(id, A2AErrors.InternalError, s"internal error: ${t.getMessage}", Some(A2AErrors.errorInfo("INTERNAL_ERROR")))
          }
        }
    }
  }

  private def handleGetTask(id: JsValue, json: JsValue)(implicit env: Env, ec: ExecutionContext): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val taskId = json.at("params.id").asOpt[String].getOrElse("")
    if (taskId.isEmpty) {
      rpcErr(id, A2AErrors.InvalidParams, "missing 'id' param")
    } else {
      new A2ATaskStore(env).get(taskId).flatMap {
        case None => rpcErr(id, A2AErrors.TaskNotFound, "task not found", Some(A2AErrors.errorInfo("TASK_NOT_FOUND", Json.obj("taskId" -> taskId))))
        case Some(task) => rpcOk(id, Json.obj("task" -> task.json))
      }
    }
  }

  private def handleCancelTask(id: JsValue, json: JsValue)(implicit env: Env, ec: ExecutionContext): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val taskId = json.at("params.id").asOpt[String].getOrElse("")
    if (taskId.isEmpty) {
      rpcErr(id, A2AErrors.InvalidParams, "missing 'id' param")
    } else {
      val store = new A2ATaskStore(env)
      store.get(taskId).flatMap {
        case None => rpcErr(id, A2AErrors.TaskNotFound, "task not found", Some(A2AErrors.errorInfo("TASK_NOT_FOUND", Json.obj("taskId" -> taskId))))
        case Some(task) if task.status.state.isTerminal =>
          rpcErr(id, A2AErrors.TaskNotCancelable, "task is in a terminal state", Some(A2AErrors.errorInfo("TASK_NOT_CANCELABLE", Json.obj("taskId" -> taskId, "state" -> task.status.state.wire))))
        case Some(task) =>
          val canceled = task.copy(status = TaskStatus(TaskState.Canceled, task.status.message, Some(A2A.nowTimestamp())))
          store.put(canceled).flatMap(_ => rpcOk(id, Json.obj("task" -> canceled.json)))
      }
    }
  }

  // one SSE `data:` line wrapping a JSON-RPC response whose result is a StreamResponse (encapsulated by member)
  private def sse(id: JsValue, sr: StreamResponse): ByteString = s"data: ${A2AJsonRpc.ok(id, sr.resultJson).stringify}\n\n".byteString

  private def handleSendStreamingMessage(id: JsValue, json: JsValue, srv: A2AServer, ctx: NgbBackendCallContext)(implicit env: Env, ec: ExecutionContext): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val params = json.select("params").asOpt[JsObject].getOrElse(Json.obj())
    val msgJson = params.select("message").asOpt[JsValue].getOrElse(Json.obj())
    A2AMessage.format.reads(msgJson).asOpt match {
      case None => rpcErr(id, A2AErrors.InvalidParams, "missing or invalid 'message' param")
      case Some(message) if message.textContent.trim.isEmpty && message.parts.isEmpty =>
        rpcErr(id, A2AErrors.InvalidParams, "empty message content")
      case Some(message) =>
        val store = new A2ATaskStore(env)
        val contextId = message.contextId.getOrElse(A2A.newId("ctx"))
        val taskId = A2A.newId("task")
        val artifactId = A2A.newId("art")
        def chunked(source: Source[ByteString, _]): BackendCallResponse =
          BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok.chunked(source).as("text/event-stream")), None)
        val workingEvt = sse(id, StreamResponse.OfStatusUpdate(TaskStatusUpdateEvent(taskId, contextId, TaskStatus(TaskState.Working))))
        val completedEvt = sse(id, StreamResponse.OfStatusUpdate(TaskStatusUpdateEvent(taskId, contextId, TaskStatus(TaskState.Completed))))
        A2ASupportServer.streamBackend(srv, message, ctx.attrs).flatMap {
          case Right(chunkSource) =>
            // mark the task working, then stream deltas; persist the completed task when the stream finishes
            store.put(A2ATask(taskId, contextId, TaskStatus(TaskState.Working, None, Some(A2A.nowTimestamp())), history = Seq(message))).map { _ =>
              val acc = new StringBuilder()
              val deltas: Source[ByteString, _] = chunkSource
                .map(chunk => chunk.choices.headOption.flatMap(_.delta.content).getOrElse(""))
                .filter(_.nonEmpty)
                .map { txt =>
                  acc.append(txt)
                  sse(id, StreamResponse.OfArtifactUpdate(TaskArtifactUpdateEvent(taskId, contextId, Artifact(artifactId, Seq(A2APart.ofText(txt))), append = true, lastChunk = false)))
                }
              val full = (Source.single(workingEvt) ++ deltas ++ Source.single(completedEvt)).watchTermination() { (m, done) =>
                done.onComplete { _ =>
                  val agentMsg = A2AMessage.agentText(acc.toString, Some(contextId), Some(taskId))
                  store.put(A2ATask(taskId, contextId, TaskStatus(TaskState.Completed, Some(agentMsg), Some(A2A.nowTimestamp())), history = Seq(message, agentMsg)))
                }
                m
              }
              Right(chunked(full))
            }
          case Left(_) =>
            // fallback: run the backend in blocking mode and emit a single artifact + completed status
            A2ASupportServer.executeBackend(srv, message, ctx.attrs).flatMap {
              case Right(responseText) =>
                val agentMsg = A2AMessage.agentText(responseText, Some(contextId), Some(taskId))
                val task = A2ATask(taskId, contextId, TaskStatus(TaskState.Completed, Some(agentMsg), Some(A2A.nowTimestamp())), history = Seq(message, agentMsg))
                store.put(task).map { _ =>
                  val src = Source(List(
                    workingEvt,
                    sse(id, StreamResponse.OfArtifactUpdate(TaskArtifactUpdateEvent(taskId, contextId, Artifact(artifactId, Seq(A2APart.ofText(responseText))), append = false, lastChunk = true))),
                    completedEvt,
                  ))
                  Right(chunked(src))
                }
              case Left(err) =>
                val failMsg = A2AMessage.agentText(Json.stringify(err), Some(contextId), Some(taskId))
                val task = A2ATask(taskId, contextId, TaskStatus(TaskState.Failed, Some(failMsg), Some(A2A.nowTimestamp())), history = Seq(message))
                store.put(task).map { _ =>
                  val src = Source.single(sse(id, StreamResponse.OfStatusUpdate(TaskStatusUpdateEvent(taskId, contextId, TaskStatus(TaskState.Failed, Some(failMsg))))))
                  Right(chunked(src))
                }
            }
        }
    }
  }
}

case class A2AServerPresetConfig(serverRef: Option[String] = None, jsonRpcPath: String = "/") extends NgPluginConfig {
  def json: JsValue = A2AServerPresetConfig.format.writes(this)
}
object A2AServerPresetConfig {
  val default = A2AServerPresetConfig()
  val configFlow: Seq[String] = Seq("server_ref", "json_rpc_path")
  val configSchema: Option[JsObject] = Some(Json.obj(
    "server_ref" -> Json.obj(
      "type" -> "select",
      "label" -> "A2A Server",
      "props" -> Json.obj(
        "optionsFrom" -> "/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/a2a-servers",
        "optionsTransformer" -> Json.obj("label" -> "name", "value" -> "id"),
      ),
    ),
    "json_rpc_path" -> Json.obj("type" -> "string", "label" -> "JSON-RPC endpoint path"),
  ))
  val format = new Format[A2AServerPresetConfig] {
    override def writes(o: A2AServerPresetConfig): JsValue = Json.obj(
      "server_ref" -> o.serverRef.map(JsString.apply).getOrElse(JsNull).asInstanceOf[JsValue],
      "json_rpc_path" -> o.jsonRpcPath,
    )
    override def reads(json: JsValue): JsResult[A2AServerPresetConfig] = Try {
      A2AServerPresetConfig(
        serverRef = json.select("server_ref").asOpt[String].filter(_.trim.nonEmpty),
        jsonRpcPath = json.select("json_rpc_path").asOpt[String].filter(_.trim.nonEmpty).getOrElse("/"),
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(v) => JsSuccess(v)
    }
  }
}

class A2AServerPreset extends NgPresetPlugin {

  override def name: String = "Cloud APIM - A2A Server (preset)"
  override def description: Option[String] = "Preset: exposes an A2A Server over JSON-RPC and serves its Agent Card on /.well-known/agent-card.json - from a single A2A Server reference.".some
  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"), NgPluginCategory.Custom("Presets"))
  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)
  override def defaultConfigObject: Option[NgPluginConfig] = Some(A2AServerPresetConfig.default)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = A2AServerPresetConfig.configFlow
  override def configSchema: Option[JsObject] = A2AServerPresetConfig.configSchema

  override def expand(ctx: NgPresetPluginContext): Seq[NgPluginInstance] = {
    val config = A2AServerPresetConfig.format.reads(ctx.config).getOrElse(A2AServerPresetConfig.default)
    val refConfig = A2AServerPluginConfig(config.serverRef.getOrElse("")).json.asObject
    Seq(
      // Agent Card on the well-known path
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[A2AAgentCardPlugin],
        include = Seq(A2A.wellKnownPath),
        config = NgPluginInstanceConfig(refConfig)
      ),
      // JSON-RPC binding on the configured path
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[A2AServerPlugin],
        include = Seq(config.jsonRpcPath),
        config = NgPluginInstanceConfig(refConfig)
      ),
    )
  }
}
