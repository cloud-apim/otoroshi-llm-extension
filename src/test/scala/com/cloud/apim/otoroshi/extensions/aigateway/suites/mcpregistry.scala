package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.entities.{McpRegistryConfig, McpVirtualServer}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.{McpExpositionScanner, McpRegistry, McpRespEndpoint, ProtectedMcpStreamableHttpPreset}
import play.api.libs.json.{Json, JsObject}

// Pure unit tests for the MCP registry projection (publish-only). No Otoroshi server is booted: the server.json
// projection, the name slugging and the listing/lookup are pure functions over McpVirtualServer entities. The
// two plugins (well-known + REST API) are thin wrappers over these helpers.
class McpRegistrySuite extends munit.FunSuite {

  private def vs(
    id: String,
    name: String,
    enabled: Boolean = true,
    registry: McpRegistryConfig = McpRegistryConfig.empty,
    description: String = "",
    tags: Seq[String] = Seq.empty,
  ): McpVirtualServer = McpVirtualServer(id = id, name = name, enabled = enabled, description = description, tags = tags, registry = registry)

  // ── config + entity round-trip ───────────────────────────────────────────────────────────────────────────

  test("McpRegistryConfig round-trips") {
    val cfg = McpRegistryConfig(published = true, name = Some("io.acme/gh"), version = "2.0.0", deprecated = true, url = Some("https://gw/mcp/gh"), title = Some("GitHub"))
    assertEquals(McpRegistryConfig.format.reads(cfg.json).get, cfg)
  }

  test("McpRegistryConfig.empty round-trips and is unpublished") {
    assertEquals(McpRegistryConfig.format.reads(McpRegistryConfig.empty.json).get, McpRegistryConfig.empty)
    assertEquals(McpRegistryConfig.empty.published, false)
    assertEquals(McpRegistryConfig.empty.version, "1.0.0")
  }

  test("McpVirtualServer round-trips with the registry block, and tolerates its absence") {
    val server = vs("vs1", "GitHub", registry = McpRegistryConfig(published = true, version = "1.2.0"))
    assertEquals(McpVirtualServer.format.reads(server.json).get.registry, server.registry)
    // back-compat: an entity persisted before this feature has no "registry" key -> empty
    val without = McpVirtualServer.format.reads(server.json.as[JsObject] - "registry").get
    assertEquals(without.registry, McpRegistryConfig.empty)
  }

  // ── server.json projection ───────────────────────────────────────────────────────────────────────────────

  test("serverJson name falls back to a reverse-DNS slug of the entity name") {
    val js = McpRegistry.serverJson(vs("vs1", "GitHub Tools!"))
    assertEquals((js \ "name").as[String], "io.cloud-apim/github-tools")
    assertEquals((js \ "$schema").as[String], McpRegistry.schemaUrl)
  }

  test("serverJson uses the explicit registry name when set") {
    val js = McpRegistry.serverJson(vs("vs1", "X", registry = McpRegistryConfig(name = Some("io.acme/github"))))
    assertEquals((js \ "name").as[String], "io.acme/github")
  }

  test("serverJson title falls back to the entity name") {
    assertEquals((McpRegistry.serverJson(vs("vs1", "My Server")) \ "title").as[String], "My Server")
    assertEquals((McpRegistry.serverJson(vs("vs1", "X", registry = McpRegistryConfig(title = Some("Pretty")))) \ "title").as[String], "Pretty")
  }

  test("serverJson includes a streamable-http remote iff a url is set") {
    val withUrl = McpRegistry.serverJson(vs("1", "A", registry = McpRegistryConfig(url = Some("https://gw/mcp/a"), version = "3.0.0")))
    assertEquals((withUrl \ "version").as[String], "3.0.0")
    val remotes = (withUrl \ "remotes").as[Seq[JsObject]]
    assertEquals(remotes.size, 1)
    assertEquals((remotes.head \ "type").as[String], "streamable-http")
    assertEquals((remotes.head \ "url").as[String], "https://gw/mcp/a")

    val noUrl = McpRegistry.serverJson(vs("2", "B"))
    assert((noUrl \ "remotes").asOpt[Seq[JsObject]].isEmpty)
  }

  test("serverJson maps deprecated to the registry-official status") {
    def status(server: McpVirtualServer): String =
      (McpRegistry.serverJson(server) \ "_meta" \ "io.modelcontextprotocol.registry/official" \ "status").as[String]
    assertEquals(status(vs("1", "A")), "active")
    assertEquals(status(vs("1", "A", registry = McpRegistryConfig(deprecated = true))), "deprecated")
  }

  test("serverJson governance _meta carries the entity id and tags") {
    val gov = McpRegistry.serverJson(vs("vs-42", "A", tags = Seq("team-x", "prod"))) \ "_meta" \ "com.cloud-apim.otoroshi/governance"
    assertEquals((gov \ "id").as[String], "vs-42")
    assertEquals((gov \ "tags").as[Seq[String]], Seq("team-x", "prod"))
  }

  // ── listing + lookup (publish-only) ──────────────────────────────────────────────────────────────────────

  test("listing only includes enabled + published servers") {
    val a = vs("a", "A", registry = McpRegistryConfig(published = true))
    val b = vs("b", "B", registry = McpRegistryConfig(published = false))
    val c = vs("c", "C", enabled = false, registry = McpRegistryConfig(published = true))
    val listing = McpRegistry.listing(Seq(a, b, c))
    assertEquals((listing \ "metadata" \ "count").as[Int], 1)
    val names = (listing \ "servers").as[Seq[JsObject]].map(s => (s \ "name").as[String])
    assertEquals(names, Seq("io.cloud-apim/a"))
  }

  test("findByName respects publication and matches the namespaced name") {
    val a = vs("a", "A", registry = McpRegistryConfig(published = true, name = Some("io.acme/a")))
    val b = vs("b", "B", registry = McpRegistryConfig(published = false, name = Some("io.acme/b")))
    assert(McpRegistry.findByName(Seq(a, b), "io.acme/a").isDefined)
    assert(McpRegistry.findByName(Seq(a, b), "io.acme/b").isEmpty) // exists but not published
    assert(McpRegistry.findByName(Seq(a, b), "io.acme/zzz").isEmpty)
  }

  test("slugifyName produces a reverse-DNS name and never an empty slug") {
    assertEquals(McpRegistry.slugifyName("Hello World"), "io.cloud-apim/hello-world")
    assertEquals(McpRegistry.slugifyName("a__b--c"), "io.cloud-apim/a-b-c")
    assertEquals(McpRegistry.slugifyName("  !!!  "), "io.cloud-apim/server")
  }

  // ── exposition scanner (pure helpers behind the registry.url assistant) ──────────────────────────────────

  private val mcpId = NgPluginHelper.pluginId[McpRespEndpoint]
  private val presetId = NgPluginHelper.pluginId[ProtectedMcpStreamableHttpPreset]

  test("deriveUrl assumes https and normalizes slashes between host and exposition path") {
    assertEquals(McpExpositionScanner.deriveUrl("gw.acme", Some("/mcp")), "https://gw.acme/mcp")
    assertEquals(McpExpositionScanner.deriveUrl("gw.acme/", Some("mcp")), "https://gw.acme/mcp")
    assertEquals(McpExpositionScanner.deriveUrl("gw.acme/base", Some("/mcp")), "https://gw.acme/base/mcp")
    assertEquals(McpExpositionScanner.deriveUrl("gw.acme", None), "https://gw.acme")
    assertEquals(McpExpositionScanner.deriveUrl("gw.acme", Some("")), "https://gw.acme")
  }

  test("detectAuth maps oauth/apikey/mtls/none and warns when not oauth") {
    assertEquals(McpExpositionScanner.detectAuth(enforceOauth = true, isProtectedPreset = false, hasApikey = false, hasMtls = false)._1, "oauth")
    assertEquals(McpExpositionScanner.detectAuth(enforceOauth = false, isProtectedPreset = true, hasApikey = false, hasMtls = false)._1, "oauth")
    // oauth wins over a co-present apikey plugin
    assertEquals(McpExpositionScanner.detectAuth(enforceOauth = true, isProtectedPreset = false, hasApikey = true, hasMtls = false)._1, "oauth")
    assert(McpExpositionScanner.detectAuth(enforceOauth = true, isProtectedPreset = false, hasApikey = false, hasMtls = false)._2.isEmpty)

    val (apikey, w1) = McpExpositionScanner.detectAuth(enforceOauth = false, isProtectedPreset = false, hasApikey = true, hasMtls = false)
    assertEquals(apikey, "apikey"); assert(w1.nonEmpty)
    val (mtls, w2) = McpExpositionScanner.detectAuth(enforceOauth = false, isProtectedPreset = false, hasApikey = false, hasMtls = true)
    assertEquals(mtls, "mtls"); assert(w2.nonEmpty)
    val (none, w3) = McpExpositionScanner.detectAuth(enforceOauth = false, isProtectedPreset = false, hasApikey = false, hasMtls = false)
    assertEquals(none, "none"); assert(w3.nonEmpty)
  }

  test("slotReferences matches only MCP exposition/preset plugins that point at the server") {
    assert(McpExpositionScanner.slotReferences(mcpId, Json.obj("server_ref" -> "vs1"), "vs1"))
    assert(McpExpositionScanner.slotReferences(presetId, Json.obj("server_ref" -> "vs1"), "vs1"))
    assert(!McpExpositionScanner.slotReferences(mcpId, Json.obj("server_ref" -> "vs2"), "vs1")) // other server
    assert(!McpExpositionScanner.slotReferences("cp:otoroshi.next.plugins.OverrideHost", Json.obj("server_ref" -> "vs1"), "vs1")) // not an MCP plugin
    assert(!McpExpositionScanner.slotReferences(mcpId, Json.obj(), "vs1")) // no server_ref
  }

  test("slotPath reads mcp_path for the preset and the include head for a raw endpoint") {
    assertEquals(McpExpositionScanner.slotPath(presetId, Json.obj("mcp_path" -> "/custom"), Seq.empty), Some("/custom"))
    assertEquals(McpExpositionScanner.slotPath(presetId, Json.obj(), Seq.empty), Some("/mcp"))
    assertEquals(McpExpositionScanner.slotPath(mcpId, Json.obj(), Seq("/mcp/gh")), Some("/mcp/gh"))
    assertEquals(McpExpositionScanner.slotPath(mcpId, Json.obj(), Seq.empty), Some("/mcp"))
  }
}
