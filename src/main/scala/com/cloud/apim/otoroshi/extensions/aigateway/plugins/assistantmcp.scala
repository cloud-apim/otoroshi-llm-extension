package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import org.apache.pekko.stream.Materializer
import com.cloud.apim.otoroshi.extensions.aigateway.assistant.AssistantConfiguration
import com.cloud.apim.otoroshi.extensions.aigateway.assistant.tools.{ToolCallContext, ToolRegistry}
import com.cloud.apim.otoroshi.extensions.aigateway.mcp.McpProtocol
import com.cloud.apim.otoroshi.extensions.aigateway.mcp.McpProtocol.ErrorCodes
import otoroshi.env.Env
import otoroshi.next.plugins.api.*
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.Logger
import play.api.libs.json.*

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
  // MCP protocol revision served: "2025-11-25" (initialize handshake, default) or "2026-07-28" (stateless, also
  // serves 2025-11-25 clients on the same endpoint)
  protocolVersion: Option[String] = None,
  // 2026-07-28 only: `ttlMs` caching hint returned on cacheable results (discover, tools/list)
  cacheTtlMs: Option[Long] = None,
) extends NgPluginConfig {
  def json: JsValue = AssistantMcpEndpointConfig.format.writes(this)
  def exposedProtocolVersion: String = protocolVersion.filter(McpProtocol.ExposableVersions.contains).getOrElse(McpProtocol.DefaultExposedVersion)
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
      "protocol_version" -> o.protocolVersion,
      "cache_ttl_ms" -> o.cacheTtlMs,
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
        protocolVersion = (json \ "protocol_version").asOpt[String].map(_.trim).filter(McpProtocol.ExposableVersions.contains),
        cacheTtlMs = (json \ "cache_ttl_ms").asOpt[Long].filter(_ >= 0L),
      )
    } match {
      case Success(c) => JsSuccess(c)
      case Failure(e) => JsError(e.getMessage)
    }
  }

  val configFlow: Seq[String] = Seq(
    "name", "version",
    "protocol_version", "cache_ttl_ms",
    "provider", "apikey",
    "max_tool_calls",
    "allow_api_usage", "allow_api_write", "allow_api_delete",
  )

  val configSchema: Option[JsObject] = Some(Json.obj(
    "name" -> Json.obj("type" -> "string", "label" -> "MCP server name"),
    "version" -> Json.obj("type" -> "string", "label" -> "MCP server version"),
    "protocol_version" -> Json.obj(
      "type" -> "select",
      "label" -> "MCP protocol version",
      "props" -> Json.obj(
        "options" -> Json.arr(
          Json.obj("label" -> "2025-11-25 (initialize handshake)", "value" -> McpProtocol.V2025_11_25),
          Json.obj("label" -> "2026-07-28 (stateless, also serves 2025-11-25 clients)", "value" -> McpProtocol.V2026_07_28),
        )
      )
    ),
    "cache_ttl_ms" -> Json.obj("type" -> "number", "label" -> "Cacheable results TTL in ms (2026-07-28)"),
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

// the Otoroshi Assistant tools surface served over Streamable HTTP
case class AssistantMcpBackend(config: AssistantMcpEndpointConfig) extends McpStreamableHttpBackend {

  private val logger = AssistantMcpEndpoint.logger

  override def serverInfo: JsObject = Json.obj(
    "name" -> config.name.getOrElse("otoroshi-assistant-mcp").json,
    "version" -> config.version.getOrElse("1.0.0").json,
  )
  override def exposedProtocolVersion: String = config.exposedProtocolVersion
  override def cacheTtlMs: Long = config.cacheTtlMs.getOrElse(0L)
  override def surfaceKey: String = "otoroshi-assistant-mcp"

  override def capabilities(attrs: TypedMap)(using env: Env, ec: ExecutionContext): Future[JsObject] = Json.obj("tools" -> Json.obj()).vfuture

  override def toolsList(attrs: TypedMap)(using env: Env, ec: ExecutionContext): Future[Seq[JsValue]] = {
    ToolRegistry.default.all.map { t =>
      Json.obj(
        "name" -> t.definition.name,
        "description" -> t.definition.description,
        "inputSchema" -> t.definition.parameters,
      )
    }.vfuture
  }

  override def completed(method: String, id: JsValue, message: JsObject, durationMs: Long, protocolVersion: String, error: Option[String], response: JsValue, attrs: TypedMap)(using env: Env): Unit = {
    if (logger.isDebugEnabled) logger.debug(s"assistant-mcp out: method=$method version=$protocolVersion took=${durationMs}ms error=${error.getOrElse("-")}")
  }

  override def operation(method: String, params: JsObject, modern: Boolean, attrs: TypedMap)(using env: Env, ec: ExecutionContext): Option[Future[Either[McpRpcError, JsObject]]] = method match {
    case "tools/list" => Some(toolsList(attrs).map(tools => Right(Json.obj("tools" -> JsArray(tools)))))
    case "tools/call" => Some(toolsCall(params))
    case _ => None
  }

  private def toolsCall(params: JsObject)(using env: Env, ec: ExecutionContext): Future[Either[McpRpcError, JsObject]] = {
    val toolName = params.select("name").asOpt[String].getOrElse("")
    val arguments = params.select("arguments").asOpt[JsObject].getOrElse(Json.obj())
    if (toolName.isEmpty) {
      Left(McpRpcError(ErrorCodes.InvalidParams, "Missing required parameter: name")).vfuture
    } else {
      ToolRegistry.default.find(toolName) match {
        case None =>
          if (logger.isDebugEnabled) logger.debug(s"assistant-mcp tools/call: unknown tool '$toolName'")
          Left(McpRpcError(ErrorCodes.InvalidParams, s"Unknown tool: $toolName")).vfuture
        case Some(tool) =>
          val ext = env.adminExtensions.extension[AiExtension].get
          val toolCtx = ToolCallContext(env, ext, user = None, config = config.toAssistantConfiguration)
          val started = System.currentTimeMillis()
          tool.call(arguments, toolCtx).map { text =>
            if (logger.isDebugEnabled) logger.debug(s"assistant-mcp tools/call ok: tool=$toolName took=${System.currentTimeMillis() - started}ms outputLen=${text.length}")
            Right(Json.obj(
              "content" -> Json.arr(Json.obj("type" -> "text", "text" -> text)),
              "isError" -> false,
            ))
          }.recover { case t: Throwable =>
            logger.warn(s"assistant-mcp tools/call '$toolName' threw an exception", t)
            Right(Json.obj(
              "content" -> Json.arr(Json.obj("type" -> "text", "text" -> s"Error: ${t.getMessage}")),
              "isError" -> true,
            ))
          }
      }
    }
  }
}

class AssistantMcpEndpoint extends NgBackendCall {

  override def name: String = "Cloud APIM - Otoroshi Assistant MCP Endpoint"
  override def description: Option[String] = "Exposes the Otoroshi Assistant tools (search, execute, doc, doc_search) as an MCP server over Streamable HTTP (MCP 2025-11-25 or stateless 2026-07-28, JSON responses).".some

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

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(AssistantMcpEndpointConfig.format).getOrElse(AssistantMcpEndpointConfig.default)
    McpStreamableHttpServer.handle(ctx, AssistantMcpBackend(config))
  }
}
