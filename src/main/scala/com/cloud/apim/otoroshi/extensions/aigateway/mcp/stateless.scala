package com.cloud.apim.otoroshi.extensions.aigateway.mcp

import com.cloud.apim.otoroshi.extensions.aigateway.mcp.McpProtocol.{ErrorCodes, Headers, MetaKeys}
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import dev.langchain4j.mcp.client.McpCallContext
import dev.langchain4j.mcp.client.transport.{McpOperationHandler, McpTransport}
import dev.langchain4j.mcp.protocol.{McpClientMessage, McpInitializeRequest}
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits.*
import play.api.Logger
import play.api.libs.json.*
import play.api.libs.ws.WSBodyWritables.given

import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class McpRpcException(val code: Int, val rpcMessage: String, val data: Option[JsValue])
  extends RuntimeException(s"upstream mcp error $code: $rpcMessage${data.map(d => s" (${d.stringify})").getOrElse("")}")

object McpStatelessHttpClient {
  private val logger: Logger = Logger("cloud-apim-llm-extension-mcp-stateless-http")
  // tool definitions per upstream (url + headers), used to mirror `x-mcp-header` parameters into request headers
  private val toolDefinitions = new TrieMap[String, (Long, Map[String, JsObject])]()
  private val MinToolDefinitionsTtl: Long = 60.seconds.toMillis
  private val MaxInputRequiredRounds: Int = 3
  private val MaxListPages: Int = 100
}

/**
 * Client for MCP servers speaking the stateless Streamable HTTP transport of revision 2026-07-28, on top of
 * Otoroshi's `env.Ws`. Every request is self-contained: protocol version, client identity and capabilities in
 * `_meta`, mirrored `MCP-Protocol-Version` / `Mcp-Method` / `Mcp-Name` / `Mcp-Param-*` headers, no handshake and
 * no session. The connector declares no client capability, so interim `input_required` results can only be
 * followed when they carry a bare `requestState`.
 */
class McpStatelessHttpClient(
  url: String,
  headers: Map[String, String],
  timeout: FiniteDuration,
  log: Boolean,
  clientName: String,
  clientVersion: String,
  onNotification: JsObject => Unit = _ => (),
)(using ec: ExecutionContext, env: Env) {

  import McpStatelessHttpClient.*

  private val ids = new AtomicLong(1L)
  private lazy val definitionsKey: String = (url + "|" + headers.toSeq.sortBy(_._1).map { case (k, v) => s"$k=$v" }.mkString("&")).sha256

  private def trace(msg: => String): Unit = {
    if (log) println(s"[mcp-2026-07-28 $clientName] $msg")
    if (logger.isDebugEnabled) logger.debug(s"[$clientName] $msg")
  }

  private def nextId(): JsValue = JsNumber(ids.getAndIncrement())

  def discover(): Future[JsObject] = call("server/discover", Json.obj())

  def listTools(): Future[Seq[JsObject]] = paginate("tools/list", "tools")

  def callTool(name: String, arguments: JsValue): Future[JsObject] = call("tools/call", Json.obj("name" -> name, "arguments" -> arguments))

  // the `result` of a request, or a failed future carrying the JSON-RPC error
  def call(method: String, params: JsObject): Future[JsObject] = exchange(method, params, nextId()).flatMap { envelope =>
    (envelope \ "error").asOpt[JsObject] match {
      case Some(err) => Future.failed(new McpRpcException(
        err.select("code").asOpt[Int].getOrElse(ErrorCodes.InternalError),
        err.select("message").asOpt[String].getOrElse("unknown error"),
        err.select("data").asOpt[JsValue],
      ))
      case None => (envelope \ "result").asOpt[JsObject].getOrElse(Json.obj()).vfuture
    }
  }

  private def paginate(method: String, field: String): Future[Seq[JsObject]] = {
    def loop(cursor: Option[String], acc: Seq[JsObject], page: Int): Future[Seq[JsObject]] = {
      call(method, cursor.map(c => Json.obj("cursor" -> c)).getOrElse(Json.obj())).flatMap { result =>
        val items = acc ++ result.select(field).asOpt[Seq[JsObject]].getOrElse(Seq.empty)
        result.select("nextCursor").asOpt[String].filter(_.nonEmpty) match {
          case Some(next) if page < MaxListPages => loop(Some(next), items, page + 1)
          case _ => items.vfuture
        }
      }
    }
    loop(None, Seq.empty, 1)
  }

  /**
   * Sends a request and returns the final JSON-RPC envelope (result or error) with the given `id`. Transparently
   * handles header mismatches on `tools/call` (tool definitions are refreshed and the request retried once) and
   * `input_required` results carrying only a `requestState` (the request is retried with it).
   */
  def exchange(method: String, params: JsObject, id: JsValue): Future[JsObject] = {
    def attempt(p: JsObject, round: Int, headersRefreshed: Boolean): Future[JsObject] = {
      paramHeaders(method, p).flatMap { extraHeaders =>
        post(method, p, nextId(), extraHeaders).flatMap { envelope =>
          (envelope \ "error").asOpt[JsObject] match {
            case Some(err) if method == "tools/call" && !headersRefreshed && err.select("code").asOpt[Int].contains(ErrorCodes.HeaderMismatch) =>
              trace(s"header mismatch on tools/call, refreshing tool definitions: ${err.stringify}")
              toolDefinitions.remove(definitionsKey)
              attempt(p, round, headersRefreshed = true)
            case Some(_) => envelope.vfuture
            case None =>
              val result = (envelope \ "result").asOpt[JsObject].getOrElse(Json.obj())
              result.select("resultType").asOpt[String].getOrElse("complete") match {
                case "complete" => (envelope ++ Json.obj("result" -> postProcess(method, result))).vfuture
                case "input_required" =>
                  val inputRequests = result.select("inputRequests").asOpt[JsObject].filter(_.value.nonEmpty)
                  val requestState = result.select("requestState").asOpt[String]
                  if (inputRequests.isDefined) {
                    val kinds = inputRequests.get.values.flatMap(r => (r \ "method").asOpt[String]).toSeq.distinct.mkString(", ")
                    Future.failed(new RuntimeException(s"mcp server requires client input ($kinds) that this connector cannot provide"))
                  } else if (requestState.isDefined && round < MaxInputRequiredRounds) {
                    attempt(p ++ Json.obj("requestState" -> requestState.get), round + 1, headersRefreshed)
                  } else {
                    Future.failed(new RuntimeException(s"mcp server did not complete '$method' after $round round trips"))
                  }
                case other => Future.failed(new RuntimeException(s"unsupported mcp resultType '$other' for '$method'"))
              }
          }
        }
      }
    }
    attempt(JsObject(params.fields.filterNot(_._2 == JsNull)), 1, headersRefreshed = false).map(_ ++ Json.obj("id" -> id))
  }

  private def post(method: String, params: JsObject, id: JsValue, extraHeaders: Seq[(String, String)]): Future[JsObject] = {
    val meta = params.select("_meta").asOpt[JsObject].getOrElse(Json.obj()) ++ Json.obj(
      MetaKeys.ProtocolVersion -> McpProtocol.V2026_07_28,
      MetaKeys.ClientInfo -> Json.obj("name" -> clientName, "version" -> clientVersion),
      MetaKeys.ClientCapabilities -> Json.obj(),
    )
    val body = Json.obj("jsonrpc" -> "2.0", "id" -> id, "method" -> method, "params" -> (params ++ Json.obj("_meta" -> meta)))
    val nameHeader = McpProtocol.NamedMethods.get(method).flatMap(field => params.select(field).asOpt[String]).map(v => Headers.Name -> McpProtocol.encodeHeaderValue(v))
    val protocolHeaders: Seq[(String, String)] = Seq(
      "Content-Type" -> "application/json",
      "Accept" -> "application/json, text/event-stream",
      Headers.ProtocolVersion -> McpProtocol.V2026_07_28,
      Headers.Method -> method,
    ) ++ nameHeader.toSeq ++ extraHeaders
    val customHeaders = headers.toSeq.filterNot { case (k, _) => protocolHeaders.exists(_._1.equalsIgnoreCase(k)) }
    trace(s"→ POST $url $method id=${id.stringify}\n   body=${body.stringify}")
    val started = System.currentTimeMillis()
    env.Ws.url(url)
      .withHttpHeaders((customHeaders ++ protocolHeaders)*)
      .withRequestTimeout(timeout)
      .withMethod("POST")
      .withBody(body.stringify)
      .execute()
      .map { resp =>
        val ctype = resp.headers.collectFirst { case (k, v) if k.equalsIgnoreCase("Content-Type") => v.headOption.getOrElse("") }.getOrElse("")
        val raw: String = resp.body
        trace(s"← ${resp.status} ($ctype) in ${System.currentTimeMillis() - started}ms\n   body=$raw")
        val nodes: Seq[JsObject] = {
          if (ctype.contains("text/event-stream")) McpProtocol.parseSse(raw)
          else if (raw.trim.nonEmpty) Try(Json.parse(raw)).toOption.toSeq
          else Seq.empty
        }.collect { case o: JsObject => o }
        nodes.filter(n => n.value.contains("method") && !n.value.contains("id")).foreach(n => Try(onNotification(n)))
        val isResponse = (n: JsObject) => n.value.contains("result") || n.value.contains("error")
        nodes.find(n => isResponse(n) && n.value.get("id").contains(id))
          .orElse(nodes.find(n => n.value.contains("error") && !n.value.contains("id")).map(_ ++ Json.obj("id" -> id)))
          .getOrElse {
            if (resp.status >= 400) {
              throw new RuntimeException(s"mcp server responded with status ${resp.status} and no JSON-RPC response: ${raw.take(500)}. It may not support protocol version ${McpProtocol.V2026_07_28}, use the 'HTTP (2025-11-25)' transport for it")
            } else {
              throw new RuntimeException(s"mcp server responded with status ${resp.status} without a JSON-RPC response for '$method'")
            }
          }
      }
  }

  // `Mcp-Param-*` headers of a `tools/call`, from the `x-mcp-header` annotations of the tool definition
  private def paramHeaders(method: String, params: JsObject): Future[Seq[(String, String)]] = {
    if (method != "tools/call") Seq.empty.vfuture
    else {
      val name = params.select("name").asOpt[String].getOrElse("")
      val arguments = params.select("arguments").asOpt[JsValue].getOrElse(Json.obj())
      toolDefinition(name).map {
        case None => Seq.empty
        case Some(tool) => McpProtocol.paramHeaders(tool.select("inputSchema").asOpt[JsValue].getOrElse(JsNull)).getOrElse(Seq.empty).flatMap { h =>
          McpProtocol.valueAt(arguments, h.path).flatMap(McpProtocol.paramValueAsString).map(v => h.headerName -> McpProtocol.encodeHeaderValue(v))
        }
      }
    }
  }

  private def toolDefinition(name: String): Future[Option[JsObject]] = {
    val now = System.currentTimeMillis()
    toolDefinitions.get(definitionsKey) match {
      case Some((expiresAt, tools)) if expiresAt > now && tools.contains(name) => tools.get(name).vfuture
      case _ => listTools().map(_.find(_.select("name").asOpt[String].contains(name))).recover { case _ => None }
    }
  }

  // tools with invalid `x-mcp-header` annotations are excluded from tools/list; every result loses the
  // protocol-level fields (resultType, caching hints, upstream server identity)
  private def postProcess(method: String, result: JsObject): JsObject = {
    val cleaned = {
      val meta = result.select("_meta").asOpt[JsObject].map(_ - MetaKeys.ServerInfo)
      val base = result - "resultType" - "ttlMs" - "cacheScope" - "_meta"
      meta.filter(_.value.nonEmpty).map(m => base ++ Json.obj("_meta" -> m)).getOrElse(base)
    }
    if (method != "tools/list") cleaned
    else {
      val tools = result.select("tools").asOpt[Seq[JsObject]].getOrElse(Seq.empty).filter { tool =>
        McpProtocol.paramHeaders(tool.select("inputSchema").asOpt[JsValue].getOrElse(JsNull)) match {
          case Left(reason) =>
            logger.warn(s"[$clientName] excluding tool '${tool.select("name").asOpt[String].getOrElse("")}' from $url: $reason")
            false
          case Right(_) => true
        }
      }
      val now = System.currentTimeMillis()
      val ttl = math.max(result.select("ttlMs").asOpt[Long].getOrElse(0L), MinToolDefinitionsTtl)
      val previous = toolDefinitions.get(definitionsKey).filter(_._1 > now).map(_._2).getOrElse(Map.empty)
      toolDefinitions.put(definitionsKey, (now + ttl, previous ++ tools.map(t => (t.select("name").asOpt[String].getOrElse(""), t))))
      cleaned ++ Json.obj("tools" -> JsArray(tools))
    }
  }
}

/**
 * langchain4j `McpTransport` backed by [[McpStatelessHttpClient]], so a `DefaultMcpClient` can drive a 2026-07-28
 * server: the `initialize` handshake is answered locally from `server/discover`, `ping` is mapped to
 * `server/discover` and client notifications are dropped (none exist over stateless HTTP).
 */
class McpStatelessHttpTransport(
  url: String,
  customHeaders: Map[String, String],
  timeout: FiniteDuration,
  log: Boolean,
  clientName: String,
  clientVersion: String,
)(using ec: ExecutionContext, env: Env) extends McpTransport {

  private val mapper: ObjectMapper = new ObjectMapper()
  private val handlerRef = new AtomicReference[McpOperationHandler]()

  private val client = new McpStatelessHttpClient(url, customHeaders, timeout, log, clientName, clientVersion, onNotification = notification => {
    Option(handlerRef.get()).foreach(_.handle(mapper.readTree(notification.stringify)))
  })

  override def start(handler: McpOperationHandler): Unit = handlerRef.set(handler)

  override def initialize(request: McpInitializeRequest): CompletableFuture[JsonNode] = {
    val id = JsNumber(BigDecimal(request.getId))
    complete(client.discover().map { result =>
      val versions = result.select("supportedVersions").asOpt[Seq[String]].getOrElse(Seq.empty)
      if (!versions.contains(McpProtocol.V2026_07_28)) {
        throw new RuntimeException(s"mcp server at $url does not support protocol version ${McpProtocol.V2026_07_28} (supported: ${versions.mkString(", ")})")
      }
      McpProtocol.jsonRpcResult(id, Json.obj(
        "protocolVersion" -> McpProtocol.V2026_07_28,
        "capabilities" -> result.select("capabilities").asOpt[JsObject].getOrElse(Json.obj()),
      ) ++ (result \ "_meta" \ MetaKeys.ServerInfo).asOpt[JsObject].map(i => Json.obj("serverInfo" -> i)).getOrElse(Json.obj())
        ++ result.select("instructions").asOpt[String].map(i => Json.obj("instructions" -> i)).getOrElse(Json.obj()))
    })
  }

  override def executeOperationWithResponse(operation: McpClientMessage): CompletableFuture[JsonNode] = send(operation)

  override def executeOperationWithResponse(ctx: McpCallContext): CompletableFuture[JsonNode] = send(ctx.message())

  override def executeOperationWithoutResponse(operation: McpClientMessage): Unit = ()

  override def executeOperationWithoutResponse(ctx: McpCallContext): Unit = ()

  override def checkHealth(): Unit = ()

  override def onFailure(actionOnFailure: Runnable): Unit = ()

  override def close(): Unit = ()

  private def send(message: McpClientMessage): CompletableFuture[JsonNode] = {
    Try(Json.parse(mapper.writeValueAsString(message)).as[JsObject]) match {
      case Failure(e) => CompletableFuture.failedFuture(e)
      case Success(json) =>
        val id = json.value.getOrElse("id", JsNull)
        val params = json.select("params").asOpt[JsObject].getOrElse(Json.obj())
        json.select("method").asOpt[String].getOrElse("") match {
          case "ping" => complete(client.discover().map(_ => McpProtocol.jsonRpcResult(id, Json.obj())))
          case method => complete(client.exchange(method, params, id))
        }
    }
  }

  private def complete(future: Future[JsObject]): CompletableFuture[JsonNode] = {
    val out = new CompletableFuture[JsonNode]()
    future.onComplete {
      case Success(envelope) => out.complete(mapper.readTree(envelope.stringify))
      // nothing to reconnect on a stateless transport: failures are reported to the caller only
      case Failure(e) => out.completeExceptionally(e)
    }
    out
  }
}
