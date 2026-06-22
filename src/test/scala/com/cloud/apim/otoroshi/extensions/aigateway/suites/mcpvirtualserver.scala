package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.entities.McpConnectorRules
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.{McpItemOverlays, McpProxyEndpointConfig, McpStaticPrompt, McpStaticPromptArgument, McpStaticPromptMessage, McpStaticResource}
import play.api.libs.json.{JsObject, Json}

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

  test("overriddenBy concatenates resources and dedups by uri (override wins)") {
    val baseRes = Seq(
      McpStaticResource(uri = "res://a", name = "a", text = Some("base-a")),
      McpStaticResource(uri = "res://b", name = "b", text = Some("base-b")),
    )
    val overRes = Seq(
      McpStaticResource(uri = "res://a", name = "a", text = Some("override-a")),
      McpStaticResource(uri = "res://c", name = "c", text = Some("override-c")),
    )
    val merged = base.copy(resources = baseRes).overriddenBy(McpProxyEndpointConfig.default.copy(resources = overRes))
    assertEquals(merged.resources.map(_.uri).toSet, Set("res://a", "res://b", "res://c"))
    assertEquals(merged.resources.find(_.uri == "res://a").flatMap(_.text), Some("override-a"))
  }

  test("McpStaticResource.listJson includes annotations and _meta when present") {
    val r = McpStaticResource(
      uri = "res://x", name = "x", mimeType = Some("text/plain"),
      annotations = Some(Json.obj("audience" -> Json.arr("user"))),
      meta = Some(Json.obj("k" -> "v")),
    )
    val js = r.listJson
    assertEquals((js \ "uri").as[String], "res://x")
    assertEquals((js \ "mimeType").as[String], "text/plain")
    assert((js \ "annotations").asOpt[JsObject].isDefined)
    assert((js \ "_meta").asOpt[JsObject].isDefined)
  }

  test("McpStaticResource format round-trips") {
    val r = McpStaticResource(uri = "res://u", name = "u", url = Some("https://h/x"), urlAs = "blob",
      headers = Map("X" -> "1"), forwardAuth = true)
    val parsed = McpStaticResource.format.reads(r.json).get
    assertEquals(parsed.url, Some("https://h/x"))
    assertEquals(parsed.urlAs, "blob")
    assertEquals(parsed.headers, Map("X" -> "1"))
    assertEquals(parsed.forwardAuth, true)
  }

  test("overriddenBy concatenates prompts and dedups by name (override wins)") {
    val baseP = Seq(
      McpStaticPrompt(name = "greet", messages = Seq(McpStaticPromptMessage(text = "base-greet"))),
      McpStaticPrompt(name = "bye", messages = Seq(McpStaticPromptMessage(text = "base-bye"))),
    )
    val overP = Seq(
      McpStaticPrompt(name = "greet", messages = Seq(McpStaticPromptMessage(text = "override-greet"))),
      McpStaticPrompt(name = "welcome", messages = Seq(McpStaticPromptMessage(text = "override-welcome"))),
    )
    val merged = base.copy(prompts = baseP).overriddenBy(McpProxyEndpointConfig.default.copy(prompts = overP))
    assertEquals(merged.prompts.map(_.name).toSet, Set("greet", "bye", "welcome"))
    assertEquals(merged.prompts.find(_.name == "greet").map(_.messages.head.text), Some("override-greet"))
  }

  test("McpStaticPrompt.listJson includes arguments and _meta") {
    val p = McpStaticPrompt(
      name = "greet", description = Some("greets the user"),
      arguments = Seq(McpStaticPromptArgument(name = "who", required = true)),
      meta = Some(Json.obj("k" -> "v")),
    )
    val js = p.listJson
    val args = (js \ "arguments").as[Seq[JsObject]]
    assertEquals((js \ "name").as[String], "greet")
    assertEquals((args.head \ "name").as[String], "who")
    assertEquals((args.head \ "required").as[Boolean], true)
    assert((js \ "_meta").asOpt[JsObject].isDefined)
  }

  test("McpStaticPrompt format round-trips (arguments + messages)") {
    val p = McpStaticPrompt(name = "greet", title = Some("Greeting"),
      arguments = Seq(McpStaticPromptArgument(name = "who", description = Some("the name"), required = true)),
      messages = Seq(McpStaticPromptMessage(role = "user", text = "Hello {{who}}")))
    val parsed = McpStaticPrompt.format.reads(p.json).get
    assertEquals(parsed.name, "greet")
    assertEquals(parsed.arguments.map(_.name), Seq("who"))
    assertEquals(parsed.messages.map(_.text), Seq("Hello {{who}}"))
  }

  test("overlays deep-merge onto item json (all fields), '*' applies to every item") {
    val overlays = McpItemOverlays(
      tools = Map(
        "*" -> Json.obj("_meta" -> Json.obj("mcp-app" -> "shared")),
        "calc" -> Json.obj(
          "description" -> "patched",
          "_meta" -> Json.obj("x" -> 1),
          "annotations" -> Json.obj("readOnlyHint" -> true),
          "outputSchema" -> Json.obj("type" -> "number"),
        ),
      )
    )
    val tool = Json.obj("name" -> "calc", "description" -> "orig", "inputSchema" -> Json.obj("type" -> "object"))
    val merged = overlays.applyTool(tool)
    assertEquals((merged \ "description").as[String], "patched")              // replaced
    assertEquals((merged \ "_meta" \ "mcp-app").as[String], "shared")         // from '*'
    assertEquals((merged \ "_meta" \ "x").as[Int], 1)                         // from specific (merged)
    assertEquals((merged \ "annotations" \ "readOnlyHint").as[Boolean], true)
    assertEquals((merged \ "outputSchema" \ "type").as[String], "number")
    assertEquals((merged \ "inputSchema" \ "type").as[String], "object")      // untouched field preserved
  }

  test("overlays apply to resources by name or uri") {
    val overlays = McpItemOverlays(resources = Map("file://a" -> Json.obj("mimeType" -> "text/plain")))
    val res = Json.obj("uri" -> "file://a", "name" -> "a")
    assertEquals((overlays.applyResource(res) \ "mimeType").as[String], "text/plain")
  }

  test("overlays merge (override wins, deep-merged)") {
    val baseO = McpItemOverlays(tools = Map("calc" -> Json.obj("_meta" -> Json.obj("a" -> 1, "b" -> 1))))
    val overO = McpItemOverlays(tools = Map("calc" -> Json.obj("_meta" -> Json.obj("b" -> 2, "c" -> 3)), "other" -> Json.obj("x" -> 1)))
    val merged = baseO.merge(overO)
    val calc = merged.tools("calc")
    assertEquals((calc \ "_meta" \ "a").as[Int], 1)
    assertEquals((calc \ "_meta" \ "b").as[Int], 2) // override wins
    assertEquals((calc \ "_meta" \ "c").as[Int], 3)
    assert(merged.tools.contains("other"))
  }

  test("overriddenBy merges overlays") {
    val baseC = base.copy(overlays = McpItemOverlays(tools = Map("t" -> Json.obj("description" -> "base"))))
    val overC = McpProxyEndpointConfig.default.copy(overlays = McpItemOverlays(tools = Map("t" -> Json.obj("title" -> "T"))))
    val merged = baseC.overriddenBy(overC)
    assertEquals((merged.overlays.tools("t") \ "description").as[String], "base")
    assertEquals((merged.overlays.tools("t") \ "title").as[String], "T")
  }

  test("McpItemOverlays format round-trips") {
    val o = McpItemOverlays(resources = Map("file://a" -> Json.obj("mimeType" -> "x")))
    val parsed = McpItemOverlays.format.reads(o.json).get
    assertEquals((parsed.resources("file://a") \ "mimeType").as[String], "x")
  }
}
