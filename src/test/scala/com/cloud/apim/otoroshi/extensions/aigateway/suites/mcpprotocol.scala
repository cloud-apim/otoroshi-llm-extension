package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.mcp.McpProtocol
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.McpProxyEndpointConfig
import play.api.libs.json.{JsNull, Json}

// Pure unit tests for the MCP protocol helpers shared by the exposition plugins and the connectors: version
// negotiation, Streamable HTTP header value encoding (Base64 sentinel) and the 2026-07-28 `x-mcp-header` tool
// schema annotations. The HTTP flows themselves are exercised by the integration McpSuite.
class McpProtocolSuite extends munit.FunSuite {

  test("legacy negotiation echoes a supported version and falls back to 2025-11-25") {
    assertEquals(McpProtocol.negotiateLegacy(Some("2025-06-18")), "2025-06-18")
    assertEquals(McpProtocol.negotiateLegacy(Some("2026-07-28")), "2025-11-25")
    assertEquals(McpProtocol.negotiateLegacy(Some("1900-01-01")), "2025-11-25")
    assertEquals(McpProtocol.negotiateLegacy(None), "2025-11-25")
  }

  test("header values are plain when safe and base64 encoded otherwise") {
    assertEquals(McpProtocol.encodeHeaderValue("us-west1"), "us-west1")
    assertEquals(McpProtocol.encodeHeaderValue("Hello, 世界"), "=?base64?SGVsbG8sIOS4lueVjA==?=")
    assertEquals(McpProtocol.encodeHeaderValue(" padded "), "=?base64?IHBhZGRlZCA=?=")
    assertEquals(McpProtocol.encodeHeaderValue("line1\nline2"), "=?base64?bGluZTEKbGluZTI=?=")
    assertEquals(McpProtocol.encodeHeaderValue("=?base64?literal?="), "=?base64?PT9iYXNlNjQ/bGl0ZXJhbD89?=")
  }

  test("header values decode back and reject invalid characters") {
    Seq("us-west1", "Hello, 世界", " padded ", "line1\nline2", "=?base64?literal?=").foreach { v =>
      assertEquals(McpProtocol.decodeHeaderValue(McpProtocol.encodeHeaderValue(v)), Some(v))
    }
    assertEquals(McpProtocol.decodeHeaderValue("badvalue"), None)
    assertEquals(McpProtocol.decodeHeaderValue("=?base64?%%%?="), None)
  }

  test("x-mcp-header annotations are extracted with their property path") {
    val schema = Json.obj(
      "type" -> "object",
      "properties" -> Json.obj(
        "region" -> Json.obj("type" -> "string", "x-mcp-header" -> "Region"),
        "query" -> Json.obj("type" -> "string"),
        "options" -> Json.obj(
          "type" -> "object",
          "properties" -> Json.obj("dryRun" -> Json.obj("type" -> "boolean", "x-mcp-header" -> "Dry-Run")),
        ),
        // a property literally named like the annotation is not an annotation
        "x-mcp-header" -> Json.obj("type" -> "string"),
      ),
    )
    val headers = McpProtocol.paramHeaders(schema).toOption.get
    assertEquals(headers.map(h => (h.headerName, h.path, h.tpe)).toSet, Set(
      ("Mcp-Param-Region", Seq("region"), "string"),
      ("Mcp-Param-Dry-Run", Seq("options", "dryRun"), "boolean"),
    ))
    assertEquals(McpProtocol.paramHeaders(Json.obj("type" -> "object")), Right(Seq.empty))
    assertEquals(McpProtocol.paramHeaders(JsNull), Right(Seq.empty))
  }

  test("invalid x-mcp-header annotations invalidate the tool definition") {
    def props(p: (String, play.api.libs.json.JsValue)*) = Json.obj("type" -> "object", "properties" -> play.api.libs.json.JsObject(p))
    // number parameters are not allowed
    assert(McpProtocol.paramHeaders(props("n" -> Json.obj("type" -> "number", "x-mcp-header" -> "N"))).isLeft)
    // not a token
    assert(McpProtocol.paramHeaders(props("a" -> Json.obj("type" -> "string", "x-mcp-header" -> "Bad Name"))).isLeft)
    assert(McpProtocol.paramHeaders(props("a" -> Json.obj("type" -> "string", "x-mcp-header" -> ""))).isLeft)
    // case-insensitive duplicates
    assert(McpProtocol.paramHeaders(props(
      "a" -> Json.obj("type" -> "string", "x-mcp-header" -> "Tenant"),
      "b" -> Json.obj("type" -> "string", "x-mcp-header" -> "tenant"),
    )).isLeft)
    // not statically reachable (through items / composition)
    assert(McpProtocol.paramHeaders(props("list" -> Json.obj("type" -> "array", "items" -> Json.obj("type" -> "string", "x-mcp-header" -> "Item")))).isLeft)
    assert(McpProtocol.paramHeaders(Json.obj("oneOf" -> Json.arr(props("a" -> Json.obj("type" -> "string", "x-mcp-header" -> "A"))))).isLeft)
  }

  test("mirrored parameter values convert and compare like the spec says") {
    assertEquals(McpProtocol.paramValueAsString(Json.toJson(42)), Some("42"))
    assertEquals(McpProtocol.paramValueAsString(Json.toJson(true)), Some("true"))
    assertEquals(McpProtocol.paramValueAsString(Json.toJson("x")), Some("x"))
    assertEquals(McpProtocol.paramValueAsString(Json.toJson(1.5)), None)
    assert(McpProtocol.paramValueMatches("42.0", Json.toJson(42)))
    assert(McpProtocol.paramValueMatches("false", Json.toJson(false)))
    assert(!McpProtocol.paramValueMatches("foo", Json.toJson("bar")))
    assertEquals(McpProtocol.valueAt(Json.obj("options" -> Json.obj("dryRun" -> true)), Seq("options", "dryRun")), Some(Json.toJson(true)))
    assertEquals(McpProtocol.valueAt(Json.obj("region" -> JsNull), Seq("region")), None)
  }

  test("exposed protocol version defaults to 2025-11-25 and survives config round-trip and merge") {
    val base = McpProxyEndpointConfig.default
    assertEquals(base.exposedProtocolVersion, "2025-11-25")
    assert(!base.exposesModernProtocol)
    val modern = McpProxyEndpointConfig.format.reads(base.json.as[play.api.libs.json.JsObject] ++ Json.obj("protocol_version" -> "2026-07-28", "cache_ttl_ms" -> 30000)).get
    assert(modern.exposesModernProtocol)
    assertEquals(modern.cacheTtlMs, Some(30000L))
    assertEquals(McpProxyEndpointConfig.format.reads(modern.json).get, modern)
    // unknown versions are ignored
    assertEquals(McpProxyEndpointConfig.format.reads(base.json.as[play.api.libs.json.JsObject] ++ Json.obj("protocol_version" -> "1900-01-01")).get.protocolVersion, None)
    // the virtual server setting applies unless the plugin overrides it
    assertEquals(modern.overriddenBy(base).exposedProtocolVersion, "2026-07-28")
    assertEquals(base.overriddenBy(modern).exposedProtocolVersion, "2026-07-28")
  }
}
