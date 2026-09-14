package com.cloud.apim.otoroshi.extensions.aigateway.mcp

import play.api.libs.json.*

import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.util.Try

/**
 * Protocol-level constants and helpers shared by the MCP exposition plugins (server side) and the MCP connectors
 * (client side): protocol versions, `_meta` keys, error codes, the Streamable HTTP request metadata headers
 * (`MCP-Protocol-Version`, `Mcp-Method`, `Mcp-Name`, `Mcp-Param-*`) with their Base64 sentinel encoding, and the
 * `x-mcp-header` tool schema annotations introduced by revision 2026-07-28.
 */
object McpProtocol {

  val V2026_07_28: String = "2026-07-28"
  val V2025_11_25: String = "2025-11-25"

  // "modern" = per-request metadata, no initialize handshake. "legacy" = initialize handshake based revisions.
  val ModernVersions: Seq[String] = Seq(V2026_07_28)
  val LegacyVersions: Seq[String] = Seq(V2025_11_25, "2025-06-18", "2025-03-26", "2024-11-05")
  // the versions an MCP exposition can be configured to serve
  val ExposableVersions: Seq[String] = Seq(V2025_11_25, V2026_07_28)
  val DefaultExposedVersion: String = V2025_11_25

  def isModern(version: String): Boolean = ModernVersions.contains(version)
  def isLegacy(version: String): Boolean = LegacyVersions.contains(version)

  // legacy negotiation: answer with the version requested by the client when we support it, else our latest one
  def negotiateLegacy(requested: Option[String]): String = requested.filter(isLegacy).getOrElse(V2025_11_25)

  object MetaKeys {
    val ProtocolVersion: String = "io.modelcontextprotocol/protocolVersion"
    val ClientInfo: String = "io.modelcontextprotocol/clientInfo"
    val ClientCapabilities: String = "io.modelcontextprotocol/clientCapabilities"
    val LogLevel: String = "io.modelcontextprotocol/logLevel"
    val ServerInfo: String = "io.modelcontextprotocol/serverInfo"
    val SubscriptionId: String = "io.modelcontextprotocol/subscriptionId"
  }

  object Headers {
    val ProtocolVersion: String = "MCP-Protocol-Version"
    val Method: String = "Mcp-Method"
    val Name: String = "Mcp-Name"
    val ParamPrefix: String = "Mcp-Param-"
    val SessionId: String = "Mcp-Session-Id"
  }

  object ErrorCodes {
    val ParseError: Int = -32700
    val InvalidRequest: Int = -32600
    val MethodNotFound: Int = -32601
    val InvalidParams: Int = -32602
    val InternalError: Int = -32603
    // 2025-11-25 and earlier only
    val LegacyResourceNotFound: Int = -32002
    // reserved MCP sub-range (2026-07-28)
    val HeaderMismatch: Int = -32020
    val MissingRequiredClientCapability: Int = -32021
    val UnsupportedProtocolVersion: Int = -32022
  }

  // MCP methods whose `Mcp-Name` header is required, with the params field it mirrors
  val NamedMethods: Map[String, String] = Map(
    "tools/call" -> "name",
    "prompts/get" -> "name",
    "resources/read" -> "uri",
  )

  // methods whose complete results carry `ttlMs` / `cacheScope` caching hints
  val CacheableMethods: Set[String] = Set(
    "server/discover",
    "tools/list",
    "prompts/list",
    "resources/list",
    "resources/templates/list",
    "resources/read",
  )

  def jsonRpcError(id: JsValue, code: Int, message: String, data: Option[JsValue] = None): JsObject = {
    val error = Json.obj("code" -> code, "message" -> message) ++ data.map(d => Json.obj("data" -> d)).getOrElse(Json.obj())
    id match {
      case JsNull => Json.obj("jsonrpc" -> "2.0", "error" -> error)
      case _ => Json.obj("jsonrpc" -> "2.0", "id" -> id, "error" -> error)
    }
  }

  def jsonRpcResult(id: JsValue, result: JsValue): JsObject = Json.obj("jsonrpc" -> "2.0", "id" -> id, "result" -> result)

  // ── header values ──────────────────────────────────────────────────────────────────────────────────────

  private val SentinelPrefix = "=?base64?"
  private val SentinelSuffix = "?="

  private def isHeaderChar(c: Char): Boolean = (c >= 0x20 && c <= 0x7e) || c == '\t'

  private def isPlainSafe(value: String): Boolean = {
    value.forall(isHeaderChar) &&
      !value.headOption.exists(c => c == ' ' || c == '\t') &&
      !value.lastOption.exists(c => c == ' ' || c == '\t') &&
      !(value.startsWith(SentinelPrefix) && value.endsWith(SentinelSuffix))
  }

  // encodes a value for `Mcp-Name` / `Mcp-Param-*`: plain when header safe, Base64 sentinel otherwise
  def encodeHeaderValue(value: String): String = {
    if (isPlainSafe(value)) value
    else SentinelPrefix + Base64.getEncoder.encodeToString(value.getBytes(StandardCharsets.UTF_8)) + SentinelSuffix
  }

  // decodes a `Mcp-Name` / `Mcp-Param-*` value. None when the value contains invalid characters or is a
  // malformed Base64 sentinel.
  def decodeHeaderValue(value: String): Option[String] = {
    if (!value.forall(isHeaderChar)) None
    else if (value.length >= SentinelPrefix.length + SentinelSuffix.length && value.startsWith(SentinelPrefix) && value.endsWith(SentinelSuffix)) {
      val encoded = value.substring(SentinelPrefix.length, value.length - SentinelSuffix.length)
      Try(new String(Base64.getDecoder.decode(encoded), StandardCharsets.UTF_8)).toOption
    } else Some(value)
  }

  // ── x-mcp-header ───────────────────────────────────────────────────────────────────────────────────────

  // `name` is the `x-mcp-header` value (header `Mcp-Param-{name}`), `path` the chain of `properties` keys
  // leading to the annotated property, `tpe` its primitive type (string, integer or boolean)
  case class McpParamHeader(name: String, path: Seq[String], tpe: String) {
    lazy val headerName: String = Headers.ParamPrefix + name
  }

  private val MaxSafeInteger = BigDecimal("9007199254740991")
  private val tchars: Set[Char] = "!#$%&'*+-.^_`|~".toSet
  private def isToken(s: String): Boolean = s.nonEmpty && s.forall(c => c.isLetterOrDigit && c < 128 || tchars.contains(c))

  // schema keywords whose object value is a map of names -> subschemas (keys are not keywords)
  private val nameMapKeywords: Set[String] = Set("properties", "patternProperties", "$defs", "definitions", "dependentSchemas")

  private def primitiveType(schema: JsObject): Option[String] = {
    val allowed = Set("string", "integer", "boolean")
    (schema \ "type").toOption match {
      case Some(JsString(t)) if allowed.contains(t) => Some(t)
      case Some(JsArray(values)) =>
        val types = values.collect { case JsString(t) => t }.filterNot(_ == "null")
        if (types.size == 1 && allowed.contains(types.head)) Some(types.head) else None
      case _ => None
    }
  }

  /**
   * Extracts the `x-mcp-header` annotations of a tool `inputSchema`. Returns Left(reason) when any annotation
   * violates the 2026-07-28 constraints (in which case the whole tool definition is invalid).
   */
  def paramHeaders(inputSchema: JsValue): Either[String, Seq[McpParamHeader]] = {
    val found = scala.collection.mutable.ListBuffer.empty[(Seq[String], JsObject, JsValue)]
    def walk(value: JsValue, path: Seq[String], isNameMap: Boolean): Unit = value match {
      case obj: JsObject =>
        obj.fields.foreach { case (key, child) =>
          if (!isNameMap && key == "x-mcp-header") found += ((path, obj, child))
          else walk(child, path :+ key, !isNameMap && nameMapKeywords.contains(key))
        }
      case arr: JsArray => arr.value.zipWithIndex.foreach { case (child, idx) => walk(child, path :+ idx.toString, isNameMap = false) }
      case _ => ()
    }
    walk(inputSchema, Seq.empty, isNameMap = false)
    val results = found.toSeq.map { case (path, schema, rawName) =>
      val reachable = path.nonEmpty && path.length % 2 == 0 && path.indices.filter(_ % 2 == 0).forall(i => path(i) == "properties")
      rawName match {
        case JsString(name) if !isToken(name) => Left(s"invalid x-mcp-header value '$name'")
        case JsString(_) if !reachable => Left(s"x-mcp-header at '${path.mkString("/")}' is not statically reachable through properties")
        case JsString(name) => primitiveType(schema) match {
          case None => Left(s"x-mcp-header '$name' is not applied to a string, integer or boolean parameter")
          case Some(tpe) => Right(McpParamHeader(name, path.indices.filter(_ % 2 == 1).map(path), tpe))
        }
        case _ => Left(s"x-mcp-header at '${path.mkString("/")}' must be a string")
      }
    }
    results.collectFirst { case Left(err) => err } match {
      case Some(err) => Left(err)
      case None =>
        val headers = results.collect { case Right(h) => h }
        val duplicated = headers.groupBy(_.name.toLowerCase).collectFirst { case (n, hs) if hs.size > 1 => n }
        duplicated match {
          case Some(n) => Left(s"x-mcp-header '$n' is not unique")
          case None => Right(headers)
        }
    }
  }

  def valueAt(arguments: JsValue, path: Seq[String]): Option[JsValue] = {
    path.foldLeft(Option(arguments)) {
      case (Some(obj: JsObject), key) => obj.value.get(key)
      case _ => None
    }.filterNot(_ == JsNull)
  }

  // string representation of a mirrored parameter value (before header encoding)
  def paramValueAsString(value: JsValue): Option[String] = value match {
    case JsString(s) => Some(s)
    case JsBoolean(b) => Some(b.toString)
    case JsNumber(n) if n.isWhole && n.abs <= MaxSafeInteger => Some(n.toBigInt.toString)
    case _ => None
  }

  // compares a decoded header value with the body value it mirrors (integers compared numerically)
  def paramValueMatches(headerValue: String, bodyValue: JsValue): Boolean = bodyValue match {
    case JsString(s) => s == headerValue
    case JsBoolean(b) => headerValue == b.toString
    case JsNumber(n) => Try(BigDecimal(headerValue)).toOption.contains(n)
    case _ => false
  }

  // ── SSE ────────────────────────────────────────────────────────────────────────────────────────────────

  // naive SSE parser: events separated by blank lines, only `data:` lines matter, comments are ignored
  def parseSse(body: String): Seq[JsValue] = {
    body.split("\\r?\\n\\r?\\n").toSeq.flatMap { event =>
      val data = event.split("\\r?\\n").iterator
        .filter(_.startsWith("data:"))
        .map(_.stripPrefix("data:").stripPrefix(" "))
        .mkString("\n")
      if (data.isEmpty) None else Try(Json.parse(data)).toOption
    }
  }
}
