package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import akka.stream.Materializer
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.assistant.AssistantConfiguration
import com.cloud.apim.otoroshi.extensions.aigateway.assistant.tools.{ToolCallContext, ToolRegistry}
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class AssistantMcpEndpointConfig(
  name: Option[String],
  version: Option[String],
  provider: Option[String],
  apikey: Option[String],
  maxToolCalls: Int,
  allowApiUsage: Boolean,
  allowApiWrite: Boolean,
  allowApiDelete: Boolean,
) extends NgPluginConfig {
  def json: JsValue = AssistantMcpEndpointConfig.format.writes(this)
  def toAssistantConfiguration: AssistantConfiguration = AssistantConfiguration(
    provider = provider,
    apikey = apikey,
    maxToolCalls = maxToolCalls,
    allowApiUsage = allowApiUsage,
    allowApiWrite = allowApiWrite,
    allowApiDelete = allowApiDelete,
    enabled = true,
  )
}

object AssistantMcpEndpointConfig {
  val default: AssistantMcpEndpointConfig = AssistantMcpEndpointConfig(
    name = Some("otoroshi-assistant-mcp"),
    version = Some("1.0.0"),
    provider = None,
    apikey = None,
    maxToolCalls = 30,
    allowApiUsage = false,
    allowApiWrite = false,
    allowApiDelete = false,
  )

  val format: Format[AssistantMcpEndpointConfig] = new Format[AssistantMcpEndpointConfig] {
    override def writes(o: AssistantMcpEndpointConfig): JsValue = Json.obj(
      "name" -> o.name,
      "version" -> o.version,
      "provider" -> o.provider,
      "apikey" -> o.apikey,
      "max_tool_calls" -> o.maxToolCalls,
      "allow_api_usage" -> o.allowApiUsage,
      "allow_api_write" -> o.allowApiWrite,
      "allow_api_delete" -> o.allowApiDelete,
    )
    override def reads(json: JsValue): JsResult[AssistantMcpEndpointConfig] = Try {
      AssistantMcpEndpointConfig(
        name = (json \ "name").asOpt[String],
        version = (json \ "version").asOpt[String],
        provider = (json \ "provider").asOpt[String],
        apikey = (json \ "apikey").asOpt[String],
        maxToolCalls = (json \ "max_tool_calls").asOpt[Int].getOrElse(30),
        allowApiUsage = (json \ "allow_api_usage").asOpt[Boolean].getOrElse(false),
        allowApiWrite = (json \ "allow_api_write").asOpt[Boolean].getOrElse(false),
        allowApiDelete = (json \ "allow_api_delete").asOpt[Boolean].getOrElse(false),
      )
    } match {
      case Success(c) => JsSuccess(c)
      case Failure(e) => JsError(e.getMessage)
    }
  }

  val configFlow: Seq[String] = Seq(
    "name", "version",
    "provider", "apikey",
    "max_tool_calls",
    "allow_api_usage", "allow_api_write", "allow_api_delete",
  )

  val configSchema: Option[JsObject] = Some(Json.obj(
    "name" -> Json.obj("type" -> "string", "label" -> "MCP server name"),
    "version" -> Json.obj("type" -> "string", "label" -> "MCP server version"),
    "provider" -> Json.obj("type" -> "string", "label" -> "LLM provider ref (unused by tools but kept for parity with the assistant config)"),
    "apikey" -> Json.obj("type" -> "string", "label" -> "Admin API key id (used by the 'execute' tool to call Otoroshi admin API)"),
    "max_tool_calls" -> Json.obj("type" -> "number", "label" -> "Max tool calls"),
    "allow_api_usage" -> Json.obj("type" -> "bool", "label" -> "Allow admin API usage (gate the 'execute' tool)"),
    "allow_api_write" -> Json.obj("type" -> "bool", "label" -> "Allow admin API writes (POST/PUT/PATCH)"),
    "allow_api_delete" -> Json.obj("type" -> "bool", "label" -> "Allow admin API DELETE"),
  ))
}

object AssistantMcpEndpoint {
  val logger: Logger = Logger("cloud-apim-llm-extension-assistant-mcp")
}

class AssistantMcpEndpoint extends NgBackendCall {

  private val logger = AssistantMcpEndpoint.logger

  override def name: String = "Cloud APIM - Otoroshi Assistant MCP Endpoint"
  override def description: Option[String] = "Exposes the Otoroshi Assistant tools (search, execute, doc, doc_search) as an MCP server over Streamable HTTP (JSON responses only, no SSE).".some

  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))
  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)
  override def useDelegates: Boolean = false
  override def defaultConfigObject: Option[NgPluginConfig] = Some(AssistantMcpEndpointConfig.default)

  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = AssistantMcpEndpointConfig.configFlow
  override def configSchema: Option[JsObject] = AssistantMcpEndpointConfig.configSchema

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'Otoroshi Assistant MCP Endpoint' plugin is available !")
    }
    ().vfuture
  }

  private def httpError(status: Int, msg: String): Future[Either[NgProxyEngineError, BackendCallResponse]] =
    NgProxyEngineError.NgResultProxyEngineError(Results.Status(status)(Json.obj("error" -> msg))).leftf

  private def jsonRpcOk(id: JsValue, payload: JsValue): Future[Either[NgProxyEngineError, BackendCallResponse]] =
    BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok(Json.obj(
      "jsonrpc" -> "2.0",
      "id" -> id,
      "result" -> payload,
    ))), None).rightf

  private def jsonRpcErr(id: JsValue, code: Int, message: String): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok(Json.obj(
      "jsonrpc" -> "2.0",
      "id" -> id,
      "error" -> Json.obj("code" -> code, "message" -> message),
    ))), None).rightf
  }

  // Notifications / responses without `id` → 202 Accepted with empty body, per JSON-RPC + MCP streamable spec.
  private val accepted: Future[Either[NgProxyEngineError, BackendCallResponse]] =
    BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Accepted), None).rightf

  private def initialize(id: JsValue, config: AssistantMcpEndpointConfig): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    jsonRpcOk(id, Json.obj(
      "protocolVersion" -> "2025-06-18",
      "capabilities" -> Json.obj("tools" -> Json.obj(), "logging" -> Json.obj()),
      "serverInfo" -> Json.obj(
        "name" -> config.name.getOrElse("otoroshi-assistant-mcp").json,
        "version" -> config.version.getOrElse("1.0.0").json,
      ),
    ))
  }

  private def toolsList(id: JsValue): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val tools = ToolRegistry.default.all.map { t =>
      Json.obj(
        "name" -> t.definition.name,
        "description" -> t.definition.description,
        "inputSchema" -> t.definition.parameters,
      )
    }
    jsonRpcOk(id, Json.obj("tools" -> JsArray(tools)))
  }

  private def toolsCall(id: JsValue, request: JsValue, config: AssistantMcpEndpointConfig)(implicit env: Env, ec: ExecutionContext): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val params = request.select("params").asOpt[JsObject].getOrElse(Json.obj())
    val toolName = params.select("name").asOpt[String].getOrElse("")
    val arguments = params.select("arguments").asOpt[JsObject].getOrElse(Json.obj())
    if (toolName.isEmpty) {
      jsonRpcErr(id, -32602, "missing 'name' parameter in tools/call")
    } else {
      ToolRegistry.default.find(toolName) match {
        case None =>
          if (logger.isDebugEnabled) logger.debug(s"assistant-mcp tools/call: unknown tool '$toolName'")
          jsonRpcErr(id, -32602, s"unknown tool: $toolName")
        case Some(tool) =>
          val ext = env.adminExtensions.extension[AiExtension].get
          val toolCtx = ToolCallContext(env, ext, user = None, config = config.toAssistantConfiguration)
          val started = System.currentTimeMillis()
          tool.call(arguments, toolCtx).map { text =>
            if (logger.isDebugEnabled) logger.debug(s"assistant-mcp tools/call ok: tool=$toolName took=${System.currentTimeMillis() - started}ms outputLen=${text.length}")
            Right[NgProxyEngineError, BackendCallResponse](BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok(Json.obj(
              "jsonrpc" -> "2.0",
              "id" -> id,
              "result" -> Json.obj(
                "content" -> Json.arr(Json.obj("type" -> "text", "text" -> text)),
                "isError" -> false,
              ),
            ))), None))
          }.recoverWith { case t: Throwable =>
            logger.warn(s"assistant-mcp tools/call '$toolName' threw an exception", t)
            BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok(Json.obj(
              "jsonrpc" -> "2.0",
              "id" -> id,
              "result" -> Json.obj(
                "content" -> Json.arr(Json.obj("type" -> "text", "text" -> s"Error: ${t.getMessage}")),
                "isError" -> true,
              ),
            ))), None).rightf
          }
      }
    }
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(AssistantMcpEndpointConfig.format).getOrElse(AssistantMcpEndpointConfig.default)
    val method = ctx.request.method.toLowerCase()
    if (method != "post") {
      httpError(405, s"method not allowed: ${ctx.request.method} (this endpoint only accepts POST)")
    } else if (!ctx.request.hasBody) {
      httpError(400, "empty body")
    } else {
      ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
        Try(bodyRaw.utf8String.parseJson) match {
          case Failure(_) => httpError(400, "invalid json-rpc payload")
          case Success(json) if json.isInstanceOf[JsArray] =>
            // JSON-RPC batching not supported in v1.
            httpError(400, "json-rpc batches are not supported")
          case Success(json) =>
            val rpcMethod = json.select("method").asOpt[String].getOrElse("--")
            val rawId: JsValue = (json \ "id").toOption.getOrElse(JsNull)
            val isNotification: Boolean = rawId match { case JsNull => true; case _ => false }
            val id: JsValue = rawId
            val started = System.currentTimeMillis()
            if (logger.isDebugEnabled) logger.debug(s"assistant-mcp in: method=$rpcMethod id=${if (isNotification) "<notification>" else id.toString}")
            val result: Future[Either[NgProxyEngineError, BackendCallResponse]] = if (isNotification) {
              // Server must not respond to notifications (id absent or null per JSON-RPC 2.0).
              accepted
            } else {
              rpcMethod match {
                case "initialize" => initialize(id, config)
                case "ping" => jsonRpcOk(id, Json.obj())
                case "shutdown" => jsonRpcOk(id, Json.obj())
                case "tools/list" => toolsList(id)
                case "tools/call" => toolsCall(id, json, config)
                case other => jsonRpcErr(id, -32601, s"method not found: $other")
              }
            }
            if (logger.isDebugEnabled) result.onComplete { _ =>
              logger.debug(s"assistant-mcp out: method=$rpcMethod took=${System.currentTimeMillis() - started}ms")
            }
            result
        }
      }
    }
  }
}
