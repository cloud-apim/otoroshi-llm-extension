package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import com.cloud.apim.otoroshi.extensions.aigateway.entities.McpConnectorRules
import com.cloud.apim.otoroshi.extensions.aigateway.mcp.McpProtocol
import com.cloud.apim.otoroshi.extensions.aigateway.mcp.McpProtocol.{ErrorCodes, Headers, MetaKeys}
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import otoroshi.env.Env
import otoroshi.next.plugins.api.*
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*
import play.api.mvc.Results

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class McpRpcError(code: Int, message: String, data: Option[JsValue] = None)

// what a Streamable HTTP MCP endpoint serves: the protocol handling lives in McpStreamableHttpServer
trait McpStreamableHttpBackend {
  def serverInfo: JsObject
  def exposedProtocolVersion: String
  def cacheTtlMs: Long
  // identity of the served surface, used to cache the `x-mcp-header` annotations of its tools
  def surfaceKey: String
  def capabilities(attrs: TypedMap)(using env: Env, ec: ExecutionContext): Future[JsObject]
  def toolsList(attrs: TypedMap)(using env: Env, ec: ExecutionContext): Future[Seq[JsValue]]
  // None when the method is not served. `modern` tells the operation which error codes to use.
  def operation(method: String, params: JsObject, modern: Boolean, attrs: TypedMap)(using env: Env, ec: ExecutionContext): Option[Future[Either[McpRpcError, JsObject]]]
  // `private` as soon as results may depend on the caller
  def cacheScope(attrs: TypedMap): String = if (McpStreamableHttpBackend.authenticated(attrs)) "private" else "public"
  def completed(method: String, id: JsValue, message: JsObject, durationMs: Long, protocolVersion: String, error: Option[String], response: JsValue, attrs: TypedMap)(using env: Env): Unit = ()
}

object McpStreamableHttpBackend {
  def authenticated(attrs: TypedMap): Boolean = {
    attrs.get(otoroshi.plugins.Keys.ApiKeyKey).isDefined ||
      attrs.get(otoroshi.plugins.Keys.UserKey).isDefined ||
      attrs.get(McpOAuthFilterUtils.McpUserAuthTokenKey).isDefined
  }
}

// the MCP virtual server surface (tool functions, connectors, managed resources and prompts)
case class McpProxyEndpointBackend(config: McpProxyEndpointConfig) extends McpStreamableHttpBackend {

  override def serverInfo: JsObject = Json.obj(
    "name" -> config.name.getOrElse("otoroshi-http-endpoint").json,
    "version" -> config.version.getOrElse("1.0.0").json,
  )
  override def exposedProtocolVersion: String = config.exposedProtocolVersion
  override def cacheTtlMs: Long = config.cacheTtlMs.getOrElse(0L)
  override lazy val surfaceKey: String = config.json.stringify.sha256

  override def capabilities(attrs: TypedMap)(using env: Env, ec: ExecutionContext): Future[JsObject] = config.computeCapabilities(attrs, includeLogging = false)

  override def toolsList(attrs: TypedMap)(using env: Env, ec: ExecutionContext): Future[Seq[JsValue]] = McpProxyLogic.toolsList(config, attrs)

  override def cacheScope(attrs: TypedMap): String = {
    val perConsumer = config.enforceOAuth || config.toolScopes.nonEmpty || config.toolRateLimits.nonEmpty ||
      config.allowRules != McpConnectorRules.empty || config.disallowRules != McpConnectorRules.empty
    if (McpStreamableHttpBackend.authenticated(attrs) || perConsumer) "private" else "public"
  }

  override def completed(method: String, id: JsValue, message: JsObject, durationMs: Long, protocolVersion: String, error: Option[String], response: JsValue, attrs: TypedMap)(using env: Env): Unit = {
    McpAuditHelper.markMetrics(method, durationMs, isError = error.isDefined)
    McpAuditHelper.record(attrs, response)
    if (config.emitAuditEvents) {
      McpAuditHelper.emit(method, id, message, durationMs, "http", error, attrs, response, Some(protocolVersion))
    }
  }

  override def operation(method: String, p: JsObject, modern: Boolean, attrs: TypedMap)(using env: Env, ec: ExecutionContext): Option[Future[Either[McpRpcError, JsObject]]] = {
    method match {
      case "tools/list" => Some(McpProxyLogic.toolsList(config, attrs).map(tools => Right(Json.obj("tools" -> JsArray(tools)))))
      case "resources/list" => Some(McpProxyLogic.resourcesList(config, attrs).map(resources => Right(Json.obj("resources" -> JsArray(resources)))))
      case "resources/templates/list" => Some(McpProxyLogic.templatesList(config, attrs).map(templates => Right(Json.obj("resourceTemplates" -> JsArray(templates)))))
      case "prompts/list" => Some(McpProxyLogic.promptsList(config, attrs).map(prompts => Right(Json.obj("prompts" -> JsArray(prompts)))))
      case "resources/read" => Some(p.select("uri").asOpt[String] match {
        case None => Left(McpRpcError(ErrorCodes.InvalidParams, "Missing required parameter: uri")).vfuture
        case Some(uri) => McpProxyLogic.readResource(config, uri, attrs).map {
          case contents if contents.isEmpty =>
            Left(McpRpcError(if (modern) ErrorCodes.InvalidParams else ErrorCodes.LegacyResourceNotFound, "Resource not found", Some(Json.obj("uri" -> uri))))
          case contents => Right(Json.obj("contents" -> JsArray(contents)))
        }
      })
      case "prompts/get" => Some(p.select("name").asOpt[String] match {
        case None => Left(McpRpcError(ErrorCodes.InvalidParams, "Missing required parameter: name")).vfuture
        case Some(name) =>
          val arguments: Map[String, Object] = p.select("arguments").asOpt[JsObject].map { obj =>
            obj.value.view.mapValues(v => v.asOpt[String].getOrElse(v.stringify).asInstanceOf[Object]).toMap
          }.getOrElse(Map.empty)
          McpProxyLogic.getPrompt(config, name, arguments, attrs).map(Right(_))
      })
      case "tools/call" => Some(p.select("name").asOpt[String] match {
        case None => Left(McpRpcError(ErrorCodes.InvalidParams, "Missing required parameter: name")).vfuture
        case Some(name) =>
          val arguments = p.select("arguments").asOpt[JsObject].getOrElse(Json.obj())
          McpProxyLogic.callTool(config, name, arguments, attrs).map {
            // unknown tools and tools hidden by scopes are protocol errors, other denials (rate limit, zero-trust
            // blocks) are tool execution errors the model can see
            case Left(unknown) if unknown == name => Left(McpRpcError(ErrorCodes.InvalidParams, s"Unknown tool: $name"))
            case Left(reason) => Right(Json.obj("isError" -> true, "content" -> Json.arr(Json.obj("type" -> "text", "text" -> reason))))
            case Right(payload) => Right(payload)
          }
      })
      case _ => None
    }
  }
}

/**
 * MCP Streamable HTTP server shared by the Streamable HTTP exposition plugins. Serves two protocol eras on the same
 * endpoint:
 *  - legacy (2025-11-25 and earlier): `initialize` handshake, no session id (every request is self-contained anyway)
 *  - modern (2026-07-28, when the backend exposes it): no handshake, per-request `_meta` protocol fields, mirrored
 *    request headers validation, `server/discover`, `resultType` and caching hints on results, `subscriptions/listen`
 *    streams.
 */
object McpStreamableHttpServer {

  private case class Reply(
    status: Int,
    body: Option[JsValue],
    stream: Option[Source[ByteString, ?]] = None,
    headers: Map[String, String] = Map.empty,
  ) {
    def error: Option[String] = body.flatMap(b => (b \ "error" \ "message").asOpt[String])
  }

  private def noContent(status: Int, headers: Map[String, String] = Map.empty): Reply = Reply(status, None, headers = headers)

  // 2026-07-28 prescribes the HTTP status of protocol errors. Legacy clients (2025-11-25 SDKs) treat any non 2xx
  // status as a transport failure, so JSON-RPC errors stay in a 200 response for them (malformed payloads apart).
  private def errorReply(id: JsValue, err: McpRpcError, modern: Boolean): Reply = {
    val status = err.code match {
      case ErrorCodes.ParseError | ErrorCodes.InvalidRequest => 400
      case _ if !modern => 200
      case ErrorCodes.MethodNotFound => 404
      case ErrorCodes.InternalError => 500
      case _ => 400
    }
    Reply(status, Some(McpProtocol.jsonRpcError(id, err.code, err.message, err.data)))
  }

  private def header(ctx: NgbBackendCallContext, name: String): Option[String] =
    ctx.request.headers.collectFirst { case (k, v) if k.equalsIgnoreCase(name) => v }

  def handle(ctx: NgbBackendCallContext, backend: McpStreamableHttpBackend)(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    given attrs: TypedMap = ctx.attrs
    if (ctx.request.method.toUpperCase != "POST") {
      // no standalone SSE stream (GET) and no session to terminate (DELETE) on this endpoint
      respond(noContent(405, Map("Allow" -> "POST")))
    } else {
      ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
        Try(Json.parse(bodyRaw.utf8String)) match {
          case Failure(_) => respond(errorReply(JsNull, McpRpcError(ErrorCodes.ParseError, "Parse error"), modern = false))
          case Success(_: JsArray) => respond(errorReply(JsNull, McpRpcError(ErrorCodes.InvalidRequest, "JSON-RPC batching is not supported"), modern = false))
          case Success(obj: JsObject) => handleMessage(ctx, backend, obj)
          case Success(_) => respond(errorReply(JsNull, McpRpcError(ErrorCodes.InvalidRequest, "Invalid Request"), modern = false))
        }
      }
    }
  }

  private def handleMessage(ctx: NgbBackendCallContext, backend: McpStreamableHttpBackend, message: JsObject)(using env: Env, ec: ExecutionContext, attrs: TypedMap): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val id: JsValue = message.value.get("id").getOrElse(JsNull)
    message.select("method").asOpt[String] match {
      case None if message.value.contains("result") || message.value.contains("error") =>
        // a client response (to a server initiated request, which we never send): accepted and ignored
        respond(noContent(202))
      case None =>
        respond(errorReply(id, McpRpcError(ErrorCodes.InvalidRequest, "Invalid Request: missing method"), modern = false))
      case Some(method) =>
        val modern = McpProtocol.isModern(backend.exposedProtocolVersion) && isModernRequest(ctx, method, message)
        val start = System.currentTimeMillis()
        val replyF: Future[Reply] = (if (modern) modernDispatch(ctx, backend, method, id, message) else legacyDispatch(ctx, backend, method, id, message))
          .recover { case t: Throwable => errorReply(id, McpRpcError(ErrorCodes.InternalError, s"Internal error: ${t.getMessage}"), modern) }
        replyF.flatMap { reply =>
          val version = if (modern) McpProtocol.V2026_07_28 else McpProtocol.negotiateLegacy(header(ctx, Headers.ProtocolVersion).orElse((message \ "params" \ "protocolVersion").asOpt[String]))
          val error = reply.error.orElse(if (reply.status >= 400) Some(s"http status ${reply.status}") else None)
          Try(backend.completed(method, id, message, System.currentTimeMillis() - start, version, error, reply.body.getOrElse(JsNull), attrs))
          respond(reply)
        }
    }
  }

  // a dual-era endpoint serves legacy semantics to `initialize` and to requests that carry a legacy (or no)
  // protocol version, and modern semantics to everything else.
  private def isModernRequest(ctx: NgbBackendCallContext, method: String, message: JsObject): Boolean = {
    val headerVersion = header(ctx, Headers.ProtocolVersion)
    val metaVersion = (message \ "params" \ "_meta" \ MetaKeys.ProtocolVersion).asOpt[String]
    if (method == "initialize") false
    else if (headerVersion.exists(McpProtocol.isLegacy)) false
    else if (headerVersion.isEmpty && metaVersion.isEmpty) false
    else true
  }

  private def respond(reply: Reply): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val response = reply.stream match {
      case Some(stream) =>
        NgPluginHttpResponse(
          status = reply.status,
          headers = Map(
            "Content-Type" -> "text/event-stream",
            "Cache-Control" -> "no-cache",
            "X-Accel-Buffering" -> "no",
            "Transfer-Encoding" -> "chunked",
          ) ++ reply.headers,
          cookies = Seq.empty,
          body = stream
        )
      case None => reply.body match {
        case Some(body) => NgPluginHttpResponse.fromResult(Results.Status(reply.status)(body).withHeaders(reply.headers.toSeq*))
        case None => NgPluginHttpResponse.fromResult(Results.Status(reply.status).withHeaders(reply.headers.toSeq*))
      }
    }
    BackendCallResponse(response, None).rightf
  }

  private def params(message: JsObject): JsObject = message.select("params").asOpt[JsObject].getOrElse(Json.obj())

  // ── legacy era ────────────────────────────────────────────────────────────────────────────────────────

  private def legacyDispatch(ctx: NgbBackendCallContext, backend: McpStreamableHttpBackend, method: String, id: JsValue, message: JsObject)(using env: Env, ec: ExecutionContext, attrs: TypedMap): Future[Reply] = {
    val unsupportedVersion = header(ctx, Headers.ProtocolVersion).filterNot(McpProtocol.isLegacy)
    if (unsupportedVersion.isDefined) {
      // not a modern error code on purpose: dual-era clients fall back to `initialize` on it
      val supported = McpProtocol.LegacyVersions.mkString(", ")
      Reply(400, Some(McpProtocol.jsonRpcError(id, ErrorCodes.InvalidRequest, s"Unsupported protocol version: ${unsupportedVersion.get} (supported: $supported)"))).vfuture
    } else if (id == JsNull) {
      noContent(202).vfuture
    } else {
      method match {
        case "initialize" =>
          val requested = (message \ "params" \ "protocolVersion").asOpt[String]
          backend.capabilities(attrs).map { capabilities =>
            Reply(200, Some(McpProtocol.jsonRpcResult(id, Json.obj(
              "protocolVersion" -> McpProtocol.negotiateLegacy(requested),
              "capabilities" -> capabilities,
              "serverInfo" -> backend.serverInfo,
            ))))
          }
        case "ping" => Reply(200, Some(McpProtocol.jsonRpcResult(id, Json.obj()))).vfuture
        case _ => backend.operation(method, params(message), modern = false, attrs) match {
          case None => errorReply(id, McpRpcError(ErrorCodes.MethodNotFound, s"Method not found: $method"), modern = false).vfuture
          case Some(op) => op.map {
            case Left(err) => errorReply(id, err, modern = false)
            case Right(result) => Reply(200, Some(McpProtocol.jsonRpcResult(id, result)))
          }
        }
      }
    }
  }

  // ── modern era (2026-07-28) ───────────────────────────────────────────────────────────────────────────

  private def validateModernRequest(ctx: NgbBackendCallContext, method: String, message: JsObject): Option[McpRpcError] = {
    val meta = (message \ "params" \ "_meta").asOpt[JsObject].getOrElse(Json.obj())
    val headerVersion = header(ctx, Headers.ProtocolVersion)
    val metaVersion = meta.select(MetaKeys.ProtocolVersion).asOpt[String]
    def mismatch(msg: String): Option[McpRpcError] = Some(McpRpcError(ErrorCodes.HeaderMismatch, s"Header mismatch: $msg"))
    if (headerVersion.isEmpty) mismatch(s"missing required ${Headers.ProtocolVersion} header")
    else if (metaVersion.isEmpty) Some(McpRpcError(ErrorCodes.InvalidParams, s"Invalid params: missing required _meta field ${MetaKeys.ProtocolVersion}"))
    else if (headerVersion != metaVersion) mismatch(s"${Headers.ProtocolVersion} header value '${headerVersion.get}' does not match body value '${metaVersion.get}'")
    else if (!McpProtocol.isModern(metaVersion.get)) Some(McpRpcError(
      ErrorCodes.UnsupportedProtocolVersion,
      "Unsupported protocol version",
      Some(Json.obj("supported" -> (McpProtocol.ModernVersions ++ McpProtocol.LegacyVersions), "requested" -> metaVersion.get))
    ))
    else if (!meta.value.get(MetaKeys.ClientCapabilities).exists(_.isInstanceOf[JsObject])) Some(McpRpcError(ErrorCodes.InvalidParams, s"Invalid params: missing required _meta field ${MetaKeys.ClientCapabilities}"))
    else header(ctx, Headers.Method) match {
      case None => mismatch(s"missing required ${Headers.Method} header")
      case Some(m) if m != method => mismatch(s"${Headers.Method} header value '$m' does not match body value '$method'")
      case Some(_) => McpProtocol.NamedMethods.get(method).flatMap { field =>
        val bodyValue = (message \ "params" \ field).asOpt[String]
        header(ctx, Headers.Name) match {
          case None => mismatch(s"missing required ${Headers.Name} header")
          case Some(raw) => McpProtocol.decodeHeaderValue(raw) match {
            case None => mismatch(s"${Headers.Name} header value contains invalid characters")
            case Some(_) if bodyValue.isEmpty => Some(McpRpcError(ErrorCodes.InvalidParams, s"Invalid params: missing required parameter $field"))
            case Some(decoded) if !bodyValue.contains(decoded) => mismatch(s"${Headers.Name} header value '$decoded' does not match body value '${bodyValue.get}'")
            case Some(_) => None
          }
        }
      }
    }
  }

  private def modernDispatch(ctx: NgbBackendCallContext, backend: McpStreamableHttpBackend, method: String, id: JsValue, message: JsObject)(using env: Env, ec: ExecutionContext, attrs: TypedMap): Future[Reply] = {
    if (id == JsNull) {
      // this revision defines no client notification over Streamable HTTP: accepted and ignored
      noContent(202).vfuture
    } else validateModernRequest(ctx, method, message) match {
      case Some(err) => errorReply(id, err, modern = true).vfuture
      case None => method match {
        case "server/discover" =>
          backend.capabilities(attrs).map { capabilities =>
            completeReply(backend, method, id, Json.obj(
              "supportedVersions" -> (McpProtocol.ModernVersions ++ McpProtocol.LegacyVersions),
              "capabilities" -> capabilities,
            ))
          }
        case "subscriptions/listen" => subscriptionStream(id).vfuture
        case "tools/call" =>
          val p = params(message)
          val name = p.select("name").asString
          val arguments = p.select("arguments").asOpt[JsObject].getOrElse(Json.obj())
          validateParamHeaders(ctx, backend, name, arguments).flatMap {
            case Some(err) => errorReply(id, err, modern = true).vfuture
            case None => runModernOperation(backend, method, id, message)
          }
        case _ => runModernOperation(backend, method, id, message)
      }
    }
  }

  private def runModernOperation(backend: McpStreamableHttpBackend, method: String, id: JsValue, message: JsObject)(using env: Env, ec: ExecutionContext, attrs: TypedMap): Future[Reply] = {
    backend.operation(method, params(message), modern = true, attrs) match {
      case None => errorReply(id, McpRpcError(ErrorCodes.MethodNotFound, s"Method not found: $method"), modern = true).vfuture
      case Some(op) => op.map {
        case Left(err) => errorReply(id, err, modern = true)
        case Right(result) => completeReply(backend, method, id, result)
      }
    }
  }

  // complete result: `resultType`, server identity in `_meta` and, for cacheable operations, `ttlMs` + `cacheScope`
  private def completeReply(backend: McpStreamableHttpBackend, method: String, id: JsValue, result: JsObject)(using attrs: TypedMap): Reply = {
    val meta = result.select("_meta").asOpt[JsObject].getOrElse(Json.obj()) ++ Json.obj(MetaKeys.ServerInfo -> backend.serverInfo)
    val cacheHints = if (McpProtocol.CacheableMethods.contains(method)) {
      Json.obj("ttlMs" -> backend.cacheTtlMs, "cacheScope" -> backend.cacheScope(attrs))
    } else Json.obj()
    Reply(200, Some(McpProtocol.jsonRpcResult(id, (result - "resultType" - "ttlMs" - "cacheScope") ++ Json.obj("resultType" -> "complete", "_meta" -> meta) ++ cacheHints)))
  }

  // the endpoint emits no change notification (no `listChanged` capability): the acknowledgment honors no
  // notification type, and the stream is kept open with SSE comments until the client closes it.
  private def subscriptionStream(id: JsValue): Reply = {
    val ack = Json.obj(
      "jsonrpc" -> "2.0",
      "method" -> "notifications/subscriptions/acknowledged",
      "params" -> Json.obj(
        "_meta" -> Json.obj(MetaKeys.SubscriptionId -> id),
        "notifications" -> Json.obj(),
      ),
    )
    val stream: Source[ByteString, ?] = Source.single(ByteString(s"event: message\ndata: ${ack.stringify}\n\n"))
      .concat(Source.tick(15.seconds, 15.seconds, ByteString(":\n\n")))
    Reply(200, Some(ack), stream = Some(stream))
  }

  // `x-mcp-header` annotations of the exposed tools, cached briefly to avoid listing tools on every call
  private val paramHeadersCache = new TrieMap[String, (Long, Map[String, Seq[McpProtocol.McpParamHeader]])]()
  private val paramHeadersCacheTtl = 10.seconds.toMillis

  private def paramHeadersOf(backend: McpStreamableHttpBackend, tool: String)(using env: Env, ec: ExecutionContext, attrs: TypedMap): Future[Seq[McpProtocol.McpParamHeader]] = {
    val key = backend.surfaceKey
    val now = System.currentTimeMillis()
    paramHeadersCache.get(key) match {
      case Some((at, byTool)) if now - at < paramHeadersCacheTtl && byTool.contains(tool) => byTool(tool).vfuture
      case _ => backend.toolsList(attrs).map { tools =>
        val byTool = tools.collect {
          case o: JsObject => (o.select("name").asOpt[String].getOrElse(""), McpProtocol.paramHeaders(o.select("inputSchema").asOpt[JsValue].getOrElse(JsNull)).getOrElse(Seq.empty))
        }.toMap
        paramHeadersCache.put(key, (now, byTool))
        byTool.getOrElse(tool, Seq.empty)
      }
    }
  }

  private def validateParamHeaders(ctx: NgbBackendCallContext, backend: McpStreamableHttpBackend, tool: String, arguments: JsObject)(using env: Env, ec: ExecutionContext, attrs: TypedMap): Future[Option[McpRpcError]] = {
    paramHeadersOf(backend, tool).map { annotated =>
      annotated.iterator.flatMap { h =>
        McpProtocol.valueAt(arguments, h.path).flatMap { bodyValue =>
          header(ctx, h.headerName) match {
            case None => Some(McpRpcError(ErrorCodes.HeaderMismatch, s"Header mismatch: missing required ${h.headerName} header"))
            case Some(raw) => McpProtocol.decodeHeaderValue(raw) match {
              case None => Some(McpRpcError(ErrorCodes.HeaderMismatch, s"Header mismatch: ${h.headerName} header value contains invalid characters"))
              case Some(decoded) if !McpProtocol.paramValueMatches(decoded, bodyValue) =>
                Some(McpRpcError(ErrorCodes.HeaderMismatch, s"Header mismatch: ${h.headerName} header value '$decoded' does not match body value '${bodyValue.stringify}'"))
              case Some(_) => None
            }
          }
        }
      }.nextOption()
    }
  }
}
