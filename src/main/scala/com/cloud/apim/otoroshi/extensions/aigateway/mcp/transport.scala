package com.cloud.apim.otoroshi.extensions.aigateway.mcp

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import dev.langchain4j.mcp.client.McpCallContext
import dev.langchain4j.mcp.client.transport.{McpOperationHandler, McpTransport}
import dev.langchain4j.mcp.protocol.{McpClientMessage, McpInitializationNotification, McpInitializeRequest}
import otoroshi.env.Env
import play.api.Logger

import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration


object WsMcpTransport {
  private val OBJECT_MAPPER: ObjectMapper = new ObjectMapper()
  private val logger: Logger = Logger("cloud-apim-llm-extension-ws-mcp-transport")
}

/**
 * MCP `Streamable HTTP` transport that uses Otoroshi's `env.Ws` (Play WS) as the HTTP
 * client. Behaviour mirrors `dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport`
 * but every request and response is traced (via `play.api.Logger` and, when `log = true`,
 * also via `println`) so it's easy to debug from inside Otoroshi.
 *
 * Only the main POST channel is supported — there is no subsidiary GET-SSE listener.
 *
 * The class is intentionally placed alongside the other transport types and can be
 * plugged into `McpConnector.buildClient` by switching the `Http` branch:
 *
 * {{{
 *   case McpConnectorTransportKind.Http =>
 *     val opts = transport.sseOptions
 *     new WsMcpTransport(opts.url, headers, opts.timeout, opts.log, name)
 * }}}
 */
class WsMcpTransport(
                      url: String,
                      customHeaders: Map[String, String],
                      timeout: FiniteDuration,
                      log: Boolean,
                      name: String = "ws-mcp",
                    )(implicit ec: ExecutionContext, env: Env) extends McpTransport {

  private val mapper: ObjectMapper = WsMcpTransport.OBJECT_MAPPER
  private val logger: Logger = WsMcpTransport.logger
  private val handlerRef = new AtomicReference[McpOperationHandler]()
  private val sessionId  = new AtomicReference[String]()
  private val onFailureRef = new AtomicReference[Runnable]()
  private val closed = new AtomicBoolean(false)

  private def trace(msg: => String): Unit = {
    if (log) println(s"[$name] $msg")
    if (logger.isDebugEnabled) logger.debug(s"[$name] $msg")
  }

  override def start(handler: McpOperationHandler): Unit = {
    handlerRef.set(handler)
    trace(s"started against $url")
  }

  override def initialize(request: McpInitializeRequest): CompletableFuture[JsonNode] = {
    val out = new CompletableFuture[JsonNode]()
    sendAndDispatch(new McpCallContext(null, request)).whenComplete { (resp, err) =>
      if (err != null) {
        out.completeExceptionally(err)
      } else {
        try {
          sendAndDispatch(new McpCallContext(null, new McpInitializationNotification()))
          out.complete(resp)
        } catch {
          case t: Throwable => out.completeExceptionally(t)
        }
      }
    }
    out
  }

  override def executeOperationWithResponse(operation: McpClientMessage): CompletableFuture[JsonNode] =
    sendAndDispatch(new McpCallContext(null, operation))

  override def executeOperationWithResponse(ctx: McpCallContext): CompletableFuture[JsonNode] =
    sendAndDispatch(ctx)

  override def executeOperationWithoutResponse(operation: McpClientMessage): Unit = {
    sendAndDispatch(new McpCallContext(null, operation))
    ()
  }

  override def executeOperationWithoutResponse(ctx: McpCallContext): Unit = {
    sendAndDispatch(ctx)
    ()
  }

  override def checkHealth(): Unit = ()

  override def onFailure(actionOnFailure: Runnable): Unit = onFailureRef.set(actionOnFailure)

  override def close(): Unit = {
    if (closed.compareAndSet(false, true)) trace("closed")
  }

  private def sendAndDispatch(callCtx: McpCallContext): CompletableFuture[JsonNode] = {
    val handler = handlerRef.get()
    val message = callCtx.message()
    val id: java.lang.Long = message.getId
    val future = new CompletableFuture[JsonNode]()
    if (id != null && handler != null) handler.startOperation(id, future)

    val body: String = try mapper.writeValueAsString(message) catch {
      case t: Throwable =>
        future.completeExceptionally(t)
        return future
    }

    val sid = sessionId.get()
    val headers: Seq[(String, String)] = Seq(
      "Content-Type" -> "application/json",
      "Accept"       -> "application/json, text/event-stream",
    ) ++ customHeaders.toSeq ++ (
      if (sid != null && !message.isInstanceOf[McpInitializeRequest]) Seq("Mcp-Session-Id" -> sid) else Nil
      )

    trace(s"→ POST $url id=$id sid=${Option(sid).getOrElse("-")}\n   body=$body")

    val started = System.currentTimeMillis()
    env.Ws.url(url)
      .withHttpHeaders(headers: _*)
      .withRequestTimeout(timeout)
      .withMethod("POST")
      .withBody(body)
      .execute()
      .map { resp =>
        val took = System.currentTimeMillis() - started
        // Capture session id if returned (case-insensitive header lookup).
        resp.headers.find { case (k, _) => k.equalsIgnoreCase("Mcp-Session-Id") }
          .flatMap(_._2.headOption)
          .foreach { sid =>
            trace(s"assigned Mcp-Session-Id=$sid")
            sessionId.set(sid)
          }
        val status = resp.status
        val ctype  = resp.headers
          .collectFirst { case (k, v) if k.equalsIgnoreCase("Content-Type") => v.headOption.getOrElse("") }
          .getOrElse("")
        val raw    = resp.body
        trace(s"← $status ($ctype) in ${took}ms\n   body=$raw")

        if (status >= 200 && status < 300) {
          val nodes: Seq[JsonNode] =
            if (ctype.contains("text/event-stream")) parseSse(raw)
            else if (raw.nonEmpty) Seq(mapper.readTree(raw))
            else Seq.empty
          nodes.foreach { n =>
            if (handler != null) handler.handle(n)
          }
          // For notifications (id == null) the operation handler won't complete the future — do it here.
          if (id == null && !future.isDone) future.complete(null)
        } else {
          val err = new RuntimeException(s"Unexpected status code $status: ${raw.take(500)}")
          future.completeExceptionally(err)
        }
      }(ec)
      .recover {
        case t: Throwable =>
          trace(s"error: ${t.getClass.getSimpleName}: ${t.getMessage}")
          future.completeExceptionally(t)
          Option(onFailureRef.get()).foreach(_.run())
      }(ec)

    future
  }

  /** Naive SSE parser: events separated by blank lines, only `data:` lines matter. */
  private def parseSse(body: String): Seq[JsonNode] = {
    body.split("\\r?\\n\\r?\\n").toSeq.flatMap { event =>
      val data = event.split("\\r?\\n").iterator
        .filter(_.startsWith("data:"))
        .map(_.stripPrefix("data:").stripPrefix(" "))
        .mkString("\n")
      if (data.isEmpty) None
      else scala.util.Try(mapper.readTree(data)).toOption
    }
  }
}
