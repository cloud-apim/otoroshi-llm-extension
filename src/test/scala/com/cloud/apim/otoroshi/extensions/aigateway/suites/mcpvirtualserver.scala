package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.entities.McpConnectorRules
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.McpProxyEndpointConfig
import play.api.libs.json.Json

// Pure unit tests for the entity-ref override semantics (McpProxyEndpointConfig.overriddenBy). No Otoroshi
// server is booted: overriddenBy is a pure function. resolve() (which needs the extension state) is covered
// by the integration McpSuite.
class McpVirtualServerMergeSuite extends munit.FunSuite {

  private val base = McpProxyEndpointConfig.default.copy(
    name = Some("base-server"),
    version = Some("1.0.0"),
    enforceOAuth = true,
    emitAuditEvents = false,
    mcpRefs = Seq("conn-1"),
    includeFunctions = Seq("a"),
  )

  test("empty override leaves the base config untouched") {
    val merged = base.overriddenBy(McpProxyEndpointConfig.default)
    assertEquals(merged.name, Some("base-server"))
    assertEquals(merged.version, Some("1.0.0"))
    assertEquals(merged.enforceOAuth, true)
    assertEquals(merged.mcpRefs, Seq("conn-1"))
    assertEquals(merged.includeFunctions, Seq("a"))
    assertEquals(merged.serverRef, None)
  }

  test("meaningful override fields win, empty ones inherit") {
    val over = McpProxyEndpointConfig.default.copy(
      name = Some("prod-server"), // Some => wins
      mcpRefs = Seq("conn-2"),    // non-empty => wins
      includeFunctions = Seq.empty, // empty => inherit base
    )
    val merged = base.overriddenBy(over)
    assertEquals(merged.name, Some("prod-server"))
    assertEquals(merged.version, Some("1.0.0")) // inherited (override had None)
    assertEquals(merged.mcpRefs, Seq("conn-2"))
    assertEquals(merged.includeFunctions, Seq("a"))
  }

  test("the two enable-flags are OR'd") {
    val over = McpProxyEndpointConfig.default.copy(enforceOAuth = false, emitAuditEvents = true)
    val merged = base.overriddenBy(over)
    assertEquals(merged.enforceOAuth, true)     // base true || false
    assertEquals(merged.emitAuditEvents, true)  // base false || true
  }

  test("allow/disallow rules are merged additively") {
    val r1 = McpConnectorRules.format.reads(Json.obj(
      "tool_rules" -> Json.obj("toolA" -> Json.arr(Json.obj("path" -> "$.x", "value" -> "1")))
    )).get
    val r2 = McpConnectorRules.format.reads(Json.obj(
      "tool_rules" -> Json.obj("toolB" -> Json.arr(Json.obj("path" -> "$.y", "value" -> "2")))
    )).get
    val merged = base.copy(allowRules = r1).overriddenBy(McpProxyEndpointConfig.default.copy(allowRules = r2))
    assert(merged.allowRules.toolRules.rules.contains("toolA"))
    assert(merged.allowRules.toolRules.rules.contains("toolB"))
  }
}
