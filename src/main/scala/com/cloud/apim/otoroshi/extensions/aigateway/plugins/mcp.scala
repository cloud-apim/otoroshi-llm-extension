package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import akka.actor.{Actor, ActorRef, PoisonPill, Props}
import akka.stream.scaladsl.{Flow, Sink, Source, SourceQueueWithComplete}
import akka.stream.{Materializer, OverflowStrategy}
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{LlmToolFunction, McpConnector, McpSupport}
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.model.chat.request.json.JsonSchemaElement
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.security.IdGenerator
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.http.websocket.Message
import play.api.libs.json._
import play.api.libs.streams.ActorFlow
import play.api.mvc.Results

import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}
import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

case class McpProxyEndpointConfig(name: Option[String], version: Option[String], functionRefs: Seq[String], mcpRefs: Seq[String]) extends NgPluginConfig {
  def json: JsValue = McpProxyEndpointConfig.format.writes(this)
}

object McpProxyEndpointConfig {
  val configFlow: Seq[String] = Seq("name", "version", "refs", "mcp_refs")
  val configSchema: Option[JsObject] = Some(Json.obj(
    "name" -> Json.obj(
      "type" -> "string",
      "label" -> "MCP server name"
    ),
    "version" -> Json.obj(
      "type" -> "string",
      "label" -> "MCP server version"
    ),
    "refs" -> Json.obj(
      "type" -> "select",
      "array" -> true,
      "label" -> s"LLM Tool Functions",
      "props" -> Json.obj(
        "optionsFrom" -> s"/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/tool-functions",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      ),
    ),
    "mcp_refs" -> Json.obj(
      "type" -> "select",
      "array" -> true,
      "label" -> s"MCP Connectors",
      "props" -> Json.obj(
        "optionsFrom" -> s"/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/mcp-connectors",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      ),
    )
  ))
  val default = McpProxyEndpointConfig(None, None, Seq.empty, Seq.empty)
  val format = new Format[McpProxyEndpointConfig] {
    override def writes(o: McpProxyEndpointConfig): JsValue = Json.obj(
      "name" -> o.name.map(_.json).getOrElse(JsNull).asValue,
      "version" -> o.version.map(_.json).getOrElse(JsNull).asValue,
      "refs" -> o.functionRefs,
      "mcp_refs" -> o.mcpRefs,
    )
    override def reads(json: JsValue): JsResult[McpProxyEndpointConfig] = Try {
      val singleRef = json.select("ref").asOpt[String].map(r => Seq(r)).getOrElse(Seq.empty)
      val refs = json.select("refs").asOpt[Seq[String]].getOrElse(Seq.empty)
      val allRefs = refs ++ singleRef
      McpProxyEndpointConfig(
        name = json.select("name").asOptString.filter(_.trim.nonEmpty),
        version = json.select("version").asOptString.filter(_.trim.nonEmpty),
        functionRefs = allRefs,
        mcpRefs = json.select("mcp_refs").asOpt[Seq[String]].getOrElse(Seq.empty)
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

/*
class McpLocalProxyEndpoint extends NgBackendCall {

  override def name: String = "Cloud APIM - MCP Tools Endpoint"
  override def description: Option[String] = "Exposes tool functions as an MCP server with a local proxy provided by npx @cloud-apim/otoroshi-mcp-proxy".some

  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))
  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)
  override def useDelegates: Boolean = false
  override def defaultConfigObject: Option[NgPluginConfig] = Some(McpProxyEndpointConfig.default)

  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = McpProxyEndpointConfig.configFlow
  override def configSchema: Option[JsObject] = McpProxyEndpointConfig.configSchema

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'MCP Tools Endpoint' plugin is available !")
    }
    ().vfuture
  }

  def call(_jsonBody: JsValue, config: McpProxyEndpointConfig, ctx: NgbBackendCallContext)(implicit ec: ExecutionContext, env: Env): Future[Either[NgProxyEngineError, BackendCallResponse]] = {

    val method = _jsonBody.select("method").asOpt[String].getOrElse("tools/get").toLowerCase()
    val params = _jsonBody.select("params").asOpt[JsObject].getOrElse(Json.obj())
    val ext = env.adminExtensions.extension[AiExtension].get
    val functions: Seq[LlmToolFunction] = config.functionRefs.flatMap(r => ext.states.toolFunction(r))
    val functionsMap = functions.map(f => (f.name, f)).toMap
    val mcpConnectors = config.mcpRefs.flatMap(r => ext.states.mcpConnector(r))
    val mcpFunctionsRawMap = mcpConnectors.flatMap(c => c.listToolsBlocking().map(f => (f.name(), (f, c)))).toMap
    val mcpFunctionsMap: Map[String, McpConnector] = mcpFunctionsRawMap.mapValues(_._2)
    val mcpFunctions: Seq[ToolSpecification] = mcpFunctionsRawMap.values.map(_._1).toSeq

    method match {
      case "tools/get" => {
        val thunks: Seq[JsValue] = functions.map { wf =>
          val required: JsArray = wf.required.map(v => JsArray(v.map(_.json))).getOrElse(JsArray(wf.parameters.value.keySet.toSeq.map(_.json)))
          Json.obj(
            "name" -> wf.name,
            "description" -> wf.description,
            "inputSchema" -> Json.obj(
              "type" -> "object",
              "properties" -> wf.parameters,
              "required" -> required,
            ),
          )
        }
        val mcpThunks = mcpFunctions.map { desc =>
          val required: Seq[String] = Option(desc.parameters().required()).map(_.asScala.toSeq).getOrElse(Seq.empty)
          val properties: JsObject = JsObject(Option(desc.parameters().properties()).map(_.asScala).getOrElse(Map.empty[String, JsonSchemaElement]).mapValues { el =>
            McpSupport.schemaToJson(el)
          })
          Json.obj(
            "name" -> desc.name,
            "description" -> desc.description,
            "inputSchema" -> Json.obj(
              "type" -> "object",
              "properties" -> properties,
              "required" -> required,
            ),
          )
        }
        val payload = JsArray(thunks ++ mcpThunks)
        BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok(payload)), None).rightf
      }
      case "tools/call" => {
        val name = params.select("name").asString
        val arguments = params.select("arguments").asOpt[JsObject].getOrElse(Json.obj())
        functionsMap.get(name) match {
          case None => mcpFunctionsMap.get(name) match {
            case None => NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(Json.obj("error" -> "bad_request", "error_details" -> "unknown function"))).leftf
            case Some(function) => {
              function.call(name, arguments.stringify).map { res =>
                BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok(
                  Json.obj(
                    "type" -> "text",
                    "text" -> res
                  )
                )), None).right
              }
            }
          }
          case Some(function) => {
            function.call(arguments.stringify).map { res =>
              BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok(
                Json.obj(
                  "type" -> "text",
                  "text" -> res
                )
              )), None).right
            }
          }
        }
      }
      case _ => NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(Json.obj("error" -> "bad_request", "error_details" -> "unknown method"))).leftf
    }
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    if (ctx.request.hasBody) {
      ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
        try {
          val jsonBody = bodyRaw.utf8String.parseJson
          val config = ctx.cachedConfig(internalName)(McpProxyEndpointConfig.format).getOrElse(McpProxyEndpointConfig.default)
          call(jsonBody, config, ctx)
        } catch {
          case e: Throwable => NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(Json.obj("error" -> "bad_request", "error_details" -> e.getMessage))).leftf
        }
      }
    } else {
      val config = ctx.cachedConfig(internalName)(McpProxyEndpointConfig.format).getOrElse(McpProxyEndpointConfig.default)
      call(Json.obj(
        "method" -> "tools/get",
        "params" -> Json.obj(),
      ), config, ctx)
    }
  }
}
*/

case class SseSession(
  sessionId: String,
  canceledRequests: TrieMap[Long, Unit] = new TrieMap[Long, Unit](),
  ready: AtomicBoolean = new AtomicBoolean(false),
  finished: AtomicBoolean = new AtomicBoolean(false),
  ref: AtomicReference[SourceQueueWithComplete[JsValue]] = new AtomicReference[SourceQueueWithComplete[JsValue]]()
) {

  def init(): Source[JsValue, _] = {
    val stream = Source
      .queue[JsValue](1000, OverflowStrategy.dropHead)
      .takeWhile(_ => !finished.get())
      .mapMaterializedValue { v =>
        ref.set(v)
      }
    stream
  }

  def send(id: Long, payload: JsValue)(implicit ec: ExecutionContext): Unit = {
    if (!canceledRequests.contains(id)) {
      Option(ref.get()).foreach(_.offer(Json.obj(
        "jsonrpc" -> "2.0",
        "id" -> id,
        "result" -> payload
      )))/*.debug(o => println(s"session ${sessionId} send- ${o.stringify}"))).andThen {
        case t => println(t)
      })*/
    }
  }

  def sendError(id: Long, code: Int, message: String, data: JsValue): Unit = {
    if (!canceledRequests.contains(id)) {
      Option(ref.get()).foreach(_.offer(Json.obj(
        "jsonrpc" -> "2.0",
        "id" -> id,
        "error" -> Json.obj(
          "code" -> code,
          "message" -> message,
          "data" -> data,
        )
      )))//.debug(o => println(s"session ${sessionId} error - ${o.stringify}"))))
    }
  }
}

class McpSseEndpoint extends NgBackendCall {

  override def name: String = "Cloud APIM - MCP SSE Endpoint"
  override def description: Option[String] = "Exposes tool functions as an MCP server using the SSE Transport".some

  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))
  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)
  override def useDelegates: Boolean = false
  override def defaultConfigObject: Option[NgPluginConfig] = Some(McpProxyEndpointConfig.default)

  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = McpProxyEndpointConfig.configFlow
  override def configSchema: Option[JsObject] = McpProxyEndpointConfig.configSchema

  private val sessions = new TrieMap[String, SseSession]()

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'MCP SSE Endpoint' plugin is available !")
    }
    ().vfuture
  }

  def error(status: Int, msg: String): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    // println(s"http error: ${status} - ${msg}")
    NgProxyEngineError.NgResultProxyEngineError(Results.Status(status)(Json.obj("error" -> msg))).leftf
  }

  def jsonRpcResponse(id: Long, payload: JsValue): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok(Json.obj(
      "jsonrpc" -> "2.0",
      "id" -> id,
      "result" -> payload
    ))), None).rightf
  }

  def emptyResp(id: Long): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    jsonRpcResponse(id, Json.obj())
  }

  def initialize(id: Long, session: SseSession, config: McpProxyEndpointConfig)(implicit env: Env, ec: ExecutionContext): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val response = Json.obj(
      "protocolVersion" -> "2024-11-05",
      "capabilities" -> Json.obj("tools" -> Json.obj(), "logging" -> Json.obj()),
      "serverInfo" -> Json.obj("name" ->
        config.name.getOrElse("otoroshi-sse-endpoint").json,
        "version" -> config.version.getOrElse("1.0.0").json,
      ),
    )
    session.send(id, response)
    jsonRpcResponse(id, response)
  }

  def getToolList(id: Long, session: SseSession, config: McpProxyEndpointConfig)(implicit env: Env, ec: ExecutionContext): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    val functions = config.functionRefs.flatMap(r => ext.states.toolFunction(r))
    val mcpConnectors = config.mcpRefs.flatMap(r => ext.states.mcpConnector(r))
    val mcpFunctions: Seq[ToolSpecification] = mcpConnectors.flatMap(_.listToolsBlocking())
    val thunks: Seq[JsValue] = functions.map { wf =>
      val required: JsArray = wf.required.map(v => JsArray(v.map(_.json))).getOrElse(JsArray(wf.parameters.value.keySet.toSeq.map(_.json)))
      Json.obj(
        "name" -> wf.name,
        "description" -> wf.description,
        "inputSchema" -> Json.obj(
          "type" -> "object",
          "properties" -> wf.parameters,
          "required" -> required,
        ),
      )
    }
    val mcpThunks = mcpFunctions.map { desc =>
      val required: Seq[String] = Option(desc.parameters().required()).map(_.asScala.toSeq).getOrElse(Seq.empty)
      val properties: JsObject = JsObject(Option(desc.parameters().properties()).map(_.asScala).getOrElse(Map.empty[String, JsonSchemaElement]).mapValues { el =>
        McpSupport.schemaToJson(el)
      })
      Json.obj(
        "name" -> desc.name,
        "description" -> desc.description,
        "inputSchema" -> Json.obj(
          "type" -> "object",
          "properties" -> properties,
          "required" -> required,
        ),
      )
    }
    val allTunks = thunks ++ mcpThunks
    val response = Json.obj("tools" -> JsArray(allTunks))
    session.send(id, response)
    jsonRpcResponse(id, response)
  }

  def toolsCall(id: Long, session: SseSession, request: JsValue, config: McpProxyEndpointConfig)(implicit env: Env, ec: ExecutionContext): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val params = request.select("params").asOpt[JsObject].getOrElse(Json.obj())
    val ext = env.adminExtensions.extension[AiExtension].get
    val functions = config.functionRefs.flatMap(r => ext.states.toolFunction(r))
    val functionsMap = functions.map(f => (f.name, f)).toMap
    val mcpConnectors = config.mcpRefs.flatMap(r => ext.states.mcpConnector(r))
    val mcpFunctionsRawMap = mcpConnectors.flatMap(c => c.listToolsBlocking().map(f => (f.name(), (f, c)))).toMap
    val mcpFunctionsMap: Map[String, McpConnector] = mcpFunctionsRawMap.mapValues(_._2)
    val mcpFunctions: Seq[ToolSpecification] = mcpFunctionsRawMap.values.map(_._1).toSeq
    val name = params.select("name").asString
    val arguments = params.select("arguments").asOpt[JsObject].getOrElse(Json.obj())
    functionsMap.get(name) match {
      case None => mcpFunctionsMap.get(name) match {
        case None => {
          session.sendError(id, 0, s"unknown function ${name}", Json.obj("name" -> name))
          error(400, s"unknown function ${name}")
        }
        case Some(function) => {
          function.call(name, arguments.stringify).flatMap { res =>
            val payload = Json.obj("content" -> Json.arr(Json.obj(
              "type" -> "text",
              "text" -> res
            )))
            session.send(id, payload)
            jsonRpcResponse(id, payload)
          }
        }
      }
      case Some(function) => {
        function.call(arguments.stringify).flatMap { res =>
          val payload = Json.obj("content" -> Json.arr(Json.obj(
            "type" -> "text",
            "text" -> res
          )))
          session.send(id, payload)
          jsonRpcResponse(id, payload)
        }
      }
    }
  }

  def getResourcesList(id: Long, session: SseSession)(implicit env: Env, ec: ExecutionContext): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    session.send(id, Json.obj("resources" -> Json.arr()))
    jsonRpcResponse(id, Json.obj("resources" -> Json.arr()))
  }

  def getPromptsList(id: Long, session: SseSession)(implicit env: Env, ec: ExecutionContext): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    session.send(id, Json.obj("prompts" -> Json.arr()))
    jsonRpcResponse(id, Json.obj("prompts" -> Json.arr()))
  }

  def getTemplatesList(id: Long, session: SseSession)(implicit env: Env, ec: ExecutionContext): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    session.send(id, Json.obj("templates" -> Json.arr()))
    jsonRpcResponse(id, Json.obj("templates" -> Json.arr()))
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(McpProxyEndpointConfig.format).getOrElse(McpProxyEndpointConfig.default)
    if (ctx.request.method.toLowerCase() == "get") {
      val sessionId = if (env.isDev) ctx.request.queryParam("sessionId").getOrElse(IdGenerator.token(16)) else IdGenerator.token(16)
      val session = SseSession(sessionId)
      // println(s"adding session: ${sessionId}")
      sessions.put(sessionId, session)
      val sessionSource: Source[ByteString, _] = session.init().map(v => s"event: message\ndata: ${v.stringify}\n\n".byteString)
      val source: Source[ByteString, _] = Source.single(ByteString(s"event: endpoint\ndata: ${ctx.rawRequest.path}?sessionId=${sessionId}\n\n"))
        .concat(sessionSource)
        .alsoTo(Sink.onComplete {
          case _ =>
            // println(s"removing session: ${sessionId}")
            sessions.remove(sessionId)
        })
      BackendCallResponse.apply(NgPluginHttpResponse(
        status = 200,
        headers = Map(
          "Content-Type" -> "text/event-stream",
          "Transfer-Encoding" -> "chunked",
        ),
        cookies = Seq.empty,
        body = source
      ), None).rightf
    } else if (ctx.request.hasBody && ctx.request.method.toLowerCase() == "post") {
      ctx.request.queryParam("sessionId") match {
        case None => error(400, s"no session not found")
        case Some(sessionId) => {
          sessions.get(sessionId) match {
            case None =>
              val count = ctx.request.queryParam("redirectCount").map(_.toInt).getOrElse(0)
              if (count < 10) {
                // println("redirect")
                val nextCount = count + 1
                NgProxyEngineError.NgResultProxyEngineError(Results.SeeOther(s"${ctx.rawRequest.theProtocol}://${ctx.rawRequest.theHost}${ctx.request.path}?sessionId=${sessionId}&redirectCount=${nextCount}")).leftf
              } else {
                error(400, s"session not found: ${sessionId}")
              }
            case Some(session) => {
              ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
                // println(s"received client command raw ${sessionId} : ${bodyRaw.utf8String}")
                Try(bodyRaw.utf8String.parseJson) match {
                  case Failure(e) => error(400,"error while parsing json-rpc payload")
                  case Success(json) => {
                    val id = json.select("id").asOpt[Long].getOrElse(0L)
                    json.select("method").asOpt[String] match {
                      case Some("initialize") => initialize(id, session, config)
                      case Some("shutdown") => {
                        session.finished.set(true)
                        session.send(id, Json.obj())
                        emptyResp(id)
                      }
                      case Some("exit") => {
                        session.finished.set(true)
                        session.send(id, Json.obj())
                        emptyResp(id)
                      }
                      case Some("ping") => {
                        session.send(id, Json.obj())
                        jsonRpcResponse(id, Json.obj())
                      }
                      case Some("cancelled") => {
                        session.canceledRequests.put(id, ())
                        emptyResp(id)
                      }
                      case Some("notifications/cancelled") => {
                        session.canceledRequests.put(id, ())
                        emptyResp(id)
                      }
                      case Some("notifications/initialized") => {
                        session.ready.set(true)
                        emptyResp(id)
                      }
                      case Some("tools/list") if session.ready.get() => getToolList(id, session, config)
                      case Some("resources/list") if session.ready.get() => getResourcesList(id, session)
                      case Some("resources/read") if session.ready.get() => ???
                      case Some("resources/templates/list") if session.ready.get() => getTemplatesList(id, session)
                      case Some("prompts/list") if session.ready.get() => getPromptsList(id, session)
                      case Some("prompts/get") if session.ready.get() => ???
                      case Some("tools/call") if session.ready.get() => toolsCall(id, session, json, config)
                      case _ => {
                        val method = json.select("method").asOpt[String].getOrElse("--")
                        jsonRpcResponse(id, Json.obj("error" -> "method unsupported", "error_details" -> Json.obj("method" -> method, "ready" -> session.ready.get())))
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    } else {
      NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(Json.obj("error" -> "bad request"))).leftf
    }
  }
}

class McpWebsocketEndpoint extends NgWebsocketBackendPlugin {

  override def name: String = "Cloud APIM - MCP WebSocket Endpoint"
  override def description: Option[String] = "Exposes tool functions as an MCP server using the (non-official) WebSocket Transport".some

  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))
  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)
  override def defaultConfigObject: Option[NgPluginConfig] = Some(McpProxyEndpointConfig.default)

  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = McpProxyEndpointConfig.configFlow
  override def configSchema: Option[JsObject] = McpProxyEndpointConfig.configSchema

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'MCP SSE Endpoint' plugin is available !")
    }
    ().vfuture
  }

  override def callBackend(ctx: NgWebsocketPluginContext)(implicit env: Env, ec: ExecutionContext): Flow[Message, Message, _] = {
    val config = ctx.cachedConfig(internalName)(McpProxyEndpointConfig.format).getOrElse(McpProxyEndpointConfig.default)
    ActorFlow
      .actorRef(out => McpActor.props(out, config, env))(env.otoroshiActorSystem, env.otoroshiMaterializer)
  }
}

object McpActor {
  def props(out: ActorRef, config: McpProxyEndpointConfig, env: Env) = Props(new McpActor(out, config, env))
}

class McpActor(out: ActorRef, config: McpProxyEndpointConfig, env: Env) extends Actor {

  val ready = new AtomicBoolean(false)
  val canceledRequests = new TrieMap[Long, Unit]()

  def send(msg: JsValue): Unit = {
    val id = msg.select("id").asLong
    if (!canceledRequests.contains(id)) {
      out ! play.api.http.websocket.TextMessage(msg.stringify)
    }
  }

  def jsonRpcResponse(id: Long, payload: JsValue): JsValue = {
    Json.obj(
      "jsonrpc" -> "2.0",
      "id" -> id,
      "result" -> payload
    )
  }

  def jsonRpcError(id: Long, code: Int, message: String, data: JsValue): JsValue = {
    Json.obj(
      "jsonrpc" -> "2.0",
      "id" -> id,
      "result" -> Json.obj(
        "code" -> code,
        "message" -> message,
        "data" -> data
      )
    )
  }

  def emptyResp(id: Long): JsValue = {
    jsonRpcResponse(id, Json.obj())
  }

  def initialize(id: Long): JsValue = {
    val response = Json.obj(
      "protocolVersion" -> "2024-11-05",
      "capabilities" -> Json.obj("tools" -> Json.obj(), "logging" -> Json.obj()),
      "serverInfo" -> Json.obj(
        "name" -> config.name.getOrElse("otoroshi-ws-endpoint").json,
        "version" -> config.version.getOrElse("1.0.0").json,
      ),
    )
    jsonRpcResponse(id, response)
  }

  def getToolList(id: Long, config: McpProxyEndpointConfig): JsValue = {
    val ext = env.adminExtensions.extension[AiExtension].get
    val functions = config.functionRefs.flatMap(r => ext.states.toolFunction(r))
    val mcpConnectors = config.mcpRefs.flatMap(r => ext.states.mcpConnector(r))
    val mcpFunctionsRawMap = mcpConnectors.flatMap(c => c.listToolsBlocking()(env.otoroshiExecutionContext, env).map(f => (f.name(), (f, c)))).toMap
    val mcpFunctions: Seq[ToolSpecification] = mcpFunctionsRawMap.values.map(_._1).toSeq
    val thunks = functions.map { wf =>
      val required: JsArray = wf.required.map(v => JsArray(v.map(_.json))).getOrElse(JsArray(wf.parameters.value.keySet.toSeq.map(_.json)))
      Json.obj(
        "name" -> wf.name,
        "description" -> wf.description,
        "inputSchema" -> Json.obj(
          "type" -> "object",
          "properties" -> wf.parameters,
          "required" -> required,
        ),
      )
    }
    val mcpThunks = mcpFunctions.map { desc =>
      val required: Seq[String] = Option(desc.parameters().required()).map(_.asScala.toSeq).getOrElse(Seq.empty)
      val properties: JsObject = JsObject(Option(desc.parameters().properties()).map(_.asScala).getOrElse(Map.empty[String, JsonSchemaElement]).mapValues { el =>
        McpSupport.schemaToJson(el)
      })
      Json.obj(
        "name" -> desc.name,
        "description" -> desc.description,
        "inputSchema" -> Json.obj(
          "type" -> "object",
          "properties" -> properties,
          "required" -> required,
        ),
      )
    }
    val response = Json.obj("tools" -> JsArray(thunks ++ mcpThunks))
    jsonRpcResponse(id, response)
  }

  def toolsCall(id: Long, request: JsValue, config: McpProxyEndpointConfig): Future[JsValue] = {
    implicit val ec = env.otoroshiExecutionContext
    implicit val ev = env
    val params = request.select("params").asOpt[JsObject].getOrElse(Json.obj())
    val ext = env.adminExtensions.extension[AiExtension].get
    val functions = config.functionRefs.flatMap(r => ext.states.toolFunction(r))
    val functionsMap = functions.map(f => (f.name, f)).toMap
    val mcpConnectors = config.mcpRefs.flatMap(r => ext.states.mcpConnector(r))
    val mcpFunctionsRawMap = mcpConnectors.flatMap(c => c.listToolsBlocking().map(f => (f.name(), (f, c)))).toMap
    val mcpFunctionsMap: Map[String, McpConnector] = mcpFunctionsRawMap.mapValues(_._2)
    val name = params.select("name").asString
    val arguments = params.select("arguments").asOpt[JsObject].getOrElse(Json.obj())
    // println(s"ws tool call: ${name} - ${arguments.stringify}")
    functionsMap.get(name) match {
      case None => mcpFunctionsMap.get(name) match {
        case None => jsonRpcError(id, 400, s"unknown function ${name}", Json.obj()).vfuture
        case Some(function) => {
          function.call(name, arguments.stringify).flatMap { res =>
            val payload = Json.obj("content" -> Json.arr(Json.obj(
              "type" -> "text",
              "text" -> res
            )))
            jsonRpcResponse(id, payload).vfuture
          }
        }
      }
      case Some(function) => {
        function.call(arguments.stringify).flatMap { res =>
          val payload = Json.obj("content" -> Json.arr(Json.obj(
            "type" -> "text",
            "text" -> res
          )))
          jsonRpcResponse(id, payload).vfuture
        }
      }
    }
  }

  def getResourcesList(id: Long): JsValue = {
    jsonRpcResponse(id, Json.obj("resources" -> Json.arr()))
  }

  def getPromptsList(id: Long): JsValue = {
    jsonRpcResponse(id, Json.obj("prompts" -> Json.arr()))
  }

  def getTemplatesList(id: Long): JsValue = {
    jsonRpcResponse(id, Json.obj("templates" -> Json.arr()))
  }

  def handle(data: String): Unit = {
    // println(s"handle ws raw message: ${data}")
    Try(data.parseJson) match {
      case Failure(e) => send(jsonRpcError(0, 400, "error while parsing json-rpc payload", Json.obj()))
      case Success(json) => {
        val id = json.select("id").asOpt[Long].getOrElse(0L)
        val resp: Future[JsValue] = json.select("method").asOpt[String] match {
          case Some("initialize") => initialize(id).vfuture
          case Some("shutdown") => {
            self ! PoisonPill
            emptyResp(id).vfuture
          }
          case Some("exit") => {
            self ! PoisonPill
            emptyResp(id).vfuture
          }
          case Some("ping") => jsonRpcResponse(id, Json.obj()).vfuture
          case Some("cancelled") => {
            canceledRequests.put(id, ())
            emptyResp(id).vfuture
          }
          case Some("notifications/cancelled") => {
            canceledRequests.put(id, ())
            emptyResp(id).vfuture
          }
          case Some("notifications/initialized") => {
            ready.set(true)
            emptyResp(id).vfuture
          }
          case Some("tools/list") if ready.get() => getToolList(id, config).vfuture
          case Some("resources/list") if ready.get() => getResourcesList(id).vfuture
          case Some("resources/read") if ready.get() => emptyResp(id).vfuture // TODO: support ?
          case Some("resources/templates/list") if ready.get() => getTemplatesList(id).vfuture
          case Some("prompts/list") if ready.get() => getPromptsList(id).vfuture
          case Some("prompts/get") if ready.get() => emptyResp(id).vfuture // TODO: support ?
          case Some("tools/call") if ready.get() => toolsCall(id, json, config)
          case _ => {
            val method = json.select("method").asOpt[String].getOrElse("--")
            jsonRpcResponse(id, Json.obj("error" -> "method unsupported", "error_details" -> Json.obj("method" -> method, "ready" -> ready.get()))).vfuture
          }
        }
        resp.map(r => send(r))(env.otoroshiExecutionContext)
      }
    }
  }

  override def receive: Receive = {
    case play.api.http.websocket.TextMessage(data)                => handle(data)
    case play.api.http.websocket.BinaryMessage(data)              => handle(data.utf8String)
    case play.api.http.websocket.PingMessage(_)                   => out ! play.api.http.websocket.PongMessage(ByteString.empty)
    case play.api.http.websocket.CloseMessage(statusCode, reason) => self ! PoisonPill
    case play.api.http.websocket.PongMessage(_)                   => ()
    case m                                                        =>  {
      println(s"unhandled m: ${m.getClass.getName} - ${m}")
    }
  }
}

class McpRespEndpoint extends NgBackendCall {

  override def name: String = "Cloud APIM - MCP HTTP Endpoint"
  override def description: Option[String] = "Exposes tool functions as an MCP server using the (non-official) HTTP Transport".some

  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))
  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)
  override def useDelegates: Boolean = false
  override def defaultConfigObject: Option[NgPluginConfig] = Some(McpProxyEndpointConfig.default)

  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = McpProxyEndpointConfig.configFlow
  override def configSchema: Option[JsObject] = McpProxyEndpointConfig.configSchema

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'MCP HTTP Endpoint' plugin is available !")
    }
    ().vfuture
  }

  def error(status: Int, msg: String): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    // println(s"http error: ${status} - ${msg}")
    NgProxyEngineError.NgResultProxyEngineError(Results.Status(status)(Json.obj("error" -> msg))).leftf
  }

  def jsonRpcResponse(id: Long, payload: JsValue): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok(Json.obj(
      "jsonrpc" -> "2.0",
      "id" -> id,
      "result" -> payload
    ))), None).rightf
  }

  def emptyResp(id: Long): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    jsonRpcResponse(id, Json.obj())
  }

  def initialize(id: Long, config: McpProxyEndpointConfig)(implicit env: Env, ec: ExecutionContext): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val response = Json.obj(
      "protocolVersion" -> "2024-11-05",
      "capabilities" -> Json.obj("tools" -> Json.obj(), "logging" -> Json.obj()),
      "serverInfo" -> Json.obj(
        "name" -> config.name.getOrElse("otoroshi-http-endpoint").json,
        "version" -> config.version.getOrElse("1.0.0").json,
      ),
    )
    jsonRpcResponse(id, response)
  }

  def getToolList(id: Long, config: McpProxyEndpointConfig)(implicit env: Env, ec: ExecutionContext): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    val functions = config.functionRefs.flatMap(r => ext.states.toolFunction(r))
    val mcpConnectors = config.mcpRefs.flatMap(r => ext.states.mcpConnector(r))
    val mcpFunctionsRawMap = mcpConnectors.flatMap(c => c.listToolsBlocking().map(f => (f.name(), (f, c)))).toMap
    val mcpFunctions: Seq[ToolSpecification] = mcpFunctionsRawMap.values.map(_._1).toSeq
    val thunks = functions.map { wf =>
      val required: JsArray = wf.required.map(v => JsArray(v.map(_.json))).getOrElse(JsArray(wf.parameters.value.keySet.toSeq.map(_.json)))
      Json.obj(
        "name" -> wf.name,
        "description" -> wf.description,
        "inputSchema" -> Json.obj(
          "type" -> "object",
          "properties" -> wf.parameters,
          "required" -> required,
        ),
      )
    }
    val mcpThunks = mcpFunctions.map { desc =>
      val required: Seq[String] = Option(desc.parameters().required()).map(_.asScala.toSeq).getOrElse(Seq.empty)
      val properties: JsObject = JsObject(Option(desc.parameters().properties()).map(_.asScala).getOrElse(Map.empty[String, JsonSchemaElement]).mapValues { el =>
        McpSupport.schemaToJson(el)
      })
      Json.obj(
        "name" -> desc.name,
        "description" -> desc.description,
        "inputSchema" -> Json.obj(
          "type" -> "object",
          "properties" -> properties,
          "required" -> required,
        ),
      )
    }
    val response = Json.obj("tools" -> JsArray(thunks ++ mcpThunks))
    jsonRpcResponse(id, response)
  }

  def toolsCall(id: Long, request: JsValue, config: McpProxyEndpointConfig)(implicit env: Env, ec: ExecutionContext): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val params = request.select("params").asOpt[JsObject].getOrElse(Json.obj())
    val ext = env.adminExtensions.extension[AiExtension].get
    val functions = config.functionRefs.flatMap(r => ext.states.toolFunction(r))
    val functionsMap = functions.map(f => (f.name, f)).toMap
    val mcpConnectors = config.mcpRefs.flatMap(r => ext.states.mcpConnector(r))
    val mcpFunctionsRawMap = mcpConnectors.flatMap(c => c.listToolsBlocking().map(f => (f.name(), (f, c)))).toMap
    val mcpFunctionsMap: Map[String, McpConnector] = mcpFunctionsRawMap.mapValues(_._2)
    val mcpFunctions: Seq[ToolSpecification] = mcpFunctionsRawMap.values.map(_._1).toSeq

    val name = params.select("name").asString
    val arguments = params.select("arguments").asOpt[JsObject].getOrElse(Json.obj())
    functionsMap.get(name) match {
      case None => mcpFunctionsMap.get(name) match {
        case None => error(400, s"unknown function ${name}")
        case Some(function) => {
          function.call(name, arguments.stringify).flatMap { res =>
            val payload = Json.obj("content" -> Json.arr(Json.obj(
              "type" -> "text",
              "text" -> res
            )))
            jsonRpcResponse(id, payload)
          }
        }
      }
      case Some(function) => {
        function.call(arguments.stringify).flatMap { res =>
          val payload = Json.obj("content" -> Json.arr(Json.obj(
            "type" -> "text",
            "text" -> res
          )))
          jsonRpcResponse(id, payload)
        }
      }
    }
  }

  def getResourcesList(id: Long)(implicit env: Env, ec: ExecutionContext): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    jsonRpcResponse(id, Json.obj("resources" -> Json.arr()))
  }

  def getPromptsList(id: Long)(implicit env: Env, ec: ExecutionContext): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    jsonRpcResponse(id, Json.obj("prompts" -> Json.arr()))
  }

  def getTemplatesList(id: Long)(implicit env: Env, ec: ExecutionContext): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    jsonRpcResponse(id, Json.obj("templates" -> Json.arr()))
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(McpProxyEndpointConfig.format).getOrElse(McpProxyEndpointConfig.default)
    if (ctx.request.hasBody && ctx.request.method.toLowerCase() == "post") {
      ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
        Try(bodyRaw.utf8String.parseJson) match {
          case Failure(e) => error(400,"error while parsing json-rpc payload")
          case Success(json) => {
            val id = json.select("id").asOpt[Long].getOrElse(0L)
            json.select("method").asOpt[String] match {
              case Some("initialize") => initialize(id, config)
              case Some("shutdown") => emptyResp(id)
              case Some("exit") => emptyResp(id)
              case Some("ping") => jsonRpcResponse(id, Json.obj())
              case Some("cancelled") => emptyResp(id)
              case Some("notifications/cancelled") => emptyResp(id)
              case Some("notifications/initialized") => emptyResp(id)
              case Some("tools/list") => getToolList(id, config)
              case Some("resources/list") => getResourcesList(id)
              case Some("resources/read") => emptyResp(id)
              case Some("resources/templates/list") => getTemplatesList(id)
              case Some("prompts/list") => getPromptsList(id)
              case Some("prompts/get") => emptyResp(id)
              case Some("tools/call") => toolsCall(id, json, config)
              case _ => {
                val method = json.select("method").asOpt[String].getOrElse("--")
                jsonRpcResponse(id, Json.obj("error" -> "method unsupported", "error_details" -> Json.obj("method" -> method)))
              }
            }
          }
        }
      }
    } else {
      NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(Json.obj("error" -> "bad request"))).leftf
    }
  }
}

