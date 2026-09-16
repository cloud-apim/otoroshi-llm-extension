package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.LlmExtensionOneOtoroshiServerPerSuite
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.{JsNull, JsObject, JsValue, Json}
import play.api.libs.ws.WSResponse
import reactor.core.publisher.Mono

import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.concurrent.duration.DurationInt

// The admin api of AI Studio drives a whole workspace (providers, keys, budgets, guardrails, routing, presets,
// tools) and must leave the same entities the studio front creates.
class StudioApiSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  val (ollamaPort, _) = createTestServerWithRoutes("ollama-studio", routes => routes
    .get("/api/tags", (_, response) => {
      response
        .status(200)
        .addHeader("Content-Type", "application/json")
        .sendString(Mono.just("""{"models":[{"name":"llama3.2"},{"name":"qwen3"}]}"""))
    })
  )

  val basic: String = Base64.getEncoder.encodeToString("admin-api-apikey-id:admin-api-apikey-secret".getBytes(StandardCharsets.UTF_8))

  def studio(method: String, path: String, body: JsValue = null): WSResponse =
    client.call(method, s"http://otoroshi-api.oto.tools:$port/api/extensions/cloud-apim/extensions/ai-extension/studio$path", Map("Authorization" -> s"Basic $basic"), Option(body)).awaitf(30.seconds)

  def entity(group: String, plural: String, id: String): Option[JsObject] = {
    val resp = client.call("GET", s"http://otoroshi-api.oto.tools:$port/apis/$group/v1/$plural/$id", Map("Authorization" -> s"Basic $basic"), None).awaitf(30.seconds)
    if (resp.status == 200) resp.json.asOpt[JsObject] else None
  }

  def aiEntity(plural: String, id: String): Option[JsObject] = entity("ai-gateway.extensions.cloud-apim.com", plural, id)

  def route(wsId: String): JsObject = entity("proxy.otoroshi.io", "routes", s"route_ai_studio_$wsId").get

  def compat(wsId: String): JsValue = route(wsId).select("plugins").as[Seq[JsObject]]
    .find(_.select("plugin").asString.endsWith("OpenAiCompatApi")).get.select("config").as[JsValue]

  def expect(resp: WSResponse, status: Int): JsValue = {
    assertEquals(resp.status, status, resp.body)
    if (resp.body.isEmpty) Json.obj() else resp.json
  }

  test("a workspace is fully managed through the studio admin api") {

    assert(expect(studio("GET", "/catalog"), 200).select("providers").as[Seq[JsObject]].exists(_.select("id").asString == "ollama"))

    // workspace
    val ws = expect(studio("POST", "/workspaces", Json.obj("name" -> "Studio API", "description" -> "created from the api")), 201)
    val wsId = ws.select("id").asString
    assertEquals(ws.select("slug").asString, "studio-api")
    assertEquals(route(wsId).select("frontend").select("domains").as[Seq[String]], Seq("studio-api.oto.tools/v1"))
    assertEquals(route(wsId).select("_loc").select("teams").as[Seq[String]], Seq(s"team_ai_studio_$wsId"))
    assert(entity("organize.otoroshi.io", "teams", s"team_ai_studio_$wsId").isDefined)
    assertEquals(route(wsId).select("plugins").as[Seq[JsObject]].map(_.select("plugin").asString.split("\\.").last), Seq("IpAddressAllowedList", "IpAddressBlockList", "MandatoryConsumerPreset", "OpenAiCompatApi"))
    expect(studio("POST", "/workspaces", Json.obj("name" -> "Studio  API!")), 409)
    expect(studio("GET", "/workspaces/nope"), 404)

    // providers
    expect(studio("POST", s"/workspaces/$wsId/providers", Json.obj("kind" -> "openai")), 400) // token required
    val ollama = expect(studio("POST", s"/workspaces/$wsId/providers", Json.obj(
      "kind" -> "ollama",
      "base_url" -> s"http://localhost:$ollamaPort",
      "modalities" -> Json.obj("text" -> Json.obj("model" -> "llama3.2")),
    )), 201)
    assertEquals(ollama.select("name").asString, "ollama")
    val ollamaText = ollama.select("entities").select("text").asString
    assert(ollamaText.startsWith("provider_ais_"))
    val ollamaEntity = aiEntity("providers", ollamaText).get
    assertEquals(ollamaEntity.select("metadata").select("ai_studio_workspace").asString, wsId)
    assertEquals(ollamaEntity.select("metadata").select("ai_studio_connection").asString, ollama.select("id").asString)
    assertEquals(ollamaEntity.select("options").select("model").asString, "llama3.2")
    assertEquals(compat(wsId).select("language_model_refs").as[Seq[String]], Seq(ollamaText))
    assertEquals(expect(studio("GET", s"/workspaces/$wsId/providers/${ollama.select("id").asString}/models"), 200).select("models").as[Seq[String]], Seq("llama3.2", "qwen3"))
    assertEquals(expect(studio("GET", s"/workspaces/$wsId/models"), 200).select("models").as[Seq[JsObject]].map(_.select("id").asString), Seq("llama3.2", "qwen3"))

    // api key with a credit limit
    val key = expect(studio("POST", s"/workspaces/$wsId/apikeys", Json.obj("name" -> "my app", "credit_limit" -> Json.obj("usd" -> 10, "period" -> "monthly"))), 201)
    val clientId = key.select("client_id").asString
    assertEquals(key.select("uses_workspace_quotas").asBoolean, true)
    val keyBudgetId = key.select("credit_limit").select("budget_id").asString
    val keyEntity = entity("apim.otoroshi.io", "apikeys", clientId).get
    assertEquals(keyEntity.select("authorizations").as[Seq[JsObject]], Seq(Json.obj("kind" -> "route", "id" -> s"route_ai_studio_$wsId")))
    assert(keyEntity.select("tags").as[Seq[String]].contains(s"ai_studio_ws_$wsId"))
    val keyBudget = aiEntity("ai-budgets", keyBudgetId).get
    assertEquals(keyBudget.select("scope").select("apikeys").as[Seq[String]], Seq(clientId))
    assertEquals(keyBudget.select("duration").as[JsValue], Json.obj("value" -> 30, "unit" -> "day"))
    assertEquals(keyBudget.select("metadata").select("ai_studio_key_limit").asString, clientId)
    expect(studio("POST", s"/workspaces/$wsId/apikeys", Json.obj("name" -> "bad", "credit_limit" -> Json.obj("usd" -> 1, "period" -> "custom"))), 400)
    assertEquals(expect(studio("GET", s"/workspaces/$wsId/apikeys"), 200).as[Seq[JsObject]].map(_.select("name").asString), Seq("my app"))
    // the owner of a key: kept when absent, removed by null, never anything but an email
    assertEquals(key.select("owner").asOpt[String], None)
    expect(studio("POST", s"/workspaces/$wsId/apikeys", Json.obj("name" -> "bad", "owner" -> "jane' OR '1'='1")), 400)
    assertEquals(expect(studio("PUT", s"/workspaces/$wsId/apikeys/$clientId", Json.obj("owner" -> "jane@acme.io")), 200).select("owner").asString, "jane@acme.io")
    assertEquals(expect(studio("PUT", s"/workspaces/$wsId/apikeys/$clientId", Json.obj("description" -> "the app")), 200).select("owner").asString, "jane@acme.io")
    assertEquals(entity("apim.otoroshi.io", "apikeys", clientId).get.select("metadata").select("ai_studio_owner").asString, "jane@acme.io")
    assertEquals(expect(studio("PUT", s"/workspaces/$wsId/apikeys/$clientId", Json.obj("owner" -> JsNull)), 200).select("owner").asOpt[String], None)
    assertEquals(entity("apim.otoroshi.io", "apikeys", clientId).get.select("metadata").select("ai_studio_owner").asOpt[String], None)
    assert(key.select("bearer").asString.nonEmpty)

    // a second provider with two capabilities, then one of them disabled
    val openai = expect(studio("POST", s"/workspaces/$wsId/providers", Json.obj(
      "kind" -> "openai",
      "token" -> "sk-test",
      "modalities" -> Json.obj("embedding" -> Json.obj("enabled" -> true)),
    )), 201)
    val openaiText = openai.select("entities").select("text").asString
    val openaiEmbedding = openai.select("entities").select("embedding").asString
    assertEquals(openai.select("modalities").select("embedding").select("model").asString, "text-embedding-3-small")
    assertEquals(compat(wsId).select("language_model_refs").as[Seq[String]], Seq(ollamaText, openaiText))
    assertEquals(compat(wsId).select("embedding_model_refs").as[Seq[String]], Seq(openaiEmbedding))
    expect(studio("POST", s"/workspaces/$wsId/providers", Json.obj("kind" -> "ollama", "name" -> "ollama")), 409)
    val updated = expect(studio("PUT", s"/workspaces/$wsId/providers/${openai.select("id").asString}", Json.obj("modalities" -> Json.obj("embedding" -> Json.obj("enabled" -> false)))), 200)
    assert(updated.select("entities").select("embedding").isEmpty)
    assert(aiEntity("embedding-models", openaiEmbedding).isEmpty)
    assertEquals(compat(wsId).select("embedding_model_refs").as[Seq[String]], Seq.empty[String])
    assertEquals(aiEntity("providers", openaiText).get.select("connection").select("token").asString, "sk-test")

    // budgets
    val budget = expect(studio("POST", s"/workspaces/$wsId/budgets", Json.obj("name" -> "workspace budget", "usd" -> 100)), 201)
    assertEquals(budget.select("scope").asString, "workspace")
    assertEquals(budget.select("period").asString, "monthly")
    val budgetEntity = aiEntity("ai-budgets", budget.select("id").asString).get
    assertEquals(budgetEntity.select("scope").select("rules").as[Seq[JsObject]].map(r => (r.select("path").asString, r.select("value").asString)), Seq(("$.provider.metadata.ai_studio_workspace", wsId)))
    expect(studio("POST", s"/workspaces/$wsId/budgets", Json.obj("name" -> "no limit")), 400)
    expect(studio("POST", s"/workspaces/$wsId/budgets", Json.obj("name" -> "bad key", "usd" -> 1, "scope" -> "apikey", "apikeys" -> Json.arr("unknown"))), 400)
    val narrowed = expect(studio("PUT", s"/workspaces/$wsId/budgets/${budget.select("id").asString}", Json.obj("scope" -> "custom", "users" -> Json.arr("john@oto.tools"), "tokens" -> 1000)), 200)
    assertEquals(narrowed.select("users").as[Seq[String]], Seq("john@oto.tools"))
    assertEquals(narrowed.select("usd").as[BigDecimal], BigDecimal(100))
    assertEquals(narrowed.select("tokens").as[BigDecimal], BigDecimal(1000))
    assertEquals(expect(studio("GET", s"/workspaces/$wsId/budgets"), 200).as[Seq[JsObject]].size, 2)

    // guardrails and model access apply to every provider of the workspace
    val guardrails = expect(studio("PUT", s"/workspaces/$wsId/guardrails", Json.obj("items" -> Json.arr(
      Json.obj("id" -> "regex", "config" -> Json.obj("deny" -> Json.arr(".*forbidden.*"))),
      Json.obj("id" -> "prompt_injection"),
    ))), 200)
    assertEquals(guardrails.select("mixed").asBoolean, false)
    Seq(ollamaText, openaiText).foreach { id =>
      val items = aiEntity("providers", id).get.select("guardrails").as[Seq[JsObject]]
      assertEquals(items.map(_.select("id").asString), Seq("regex", "prompt_injection"))
      assert(Seq(ollamaText, openaiText).contains(items(1).select("config").select("provider").asString))
      assertEquals(items(1).select("config").select("max_injection_score").as[Int], 90)
      assertEquals(items.head.select("before").asBoolean, true)
    }
    expect(studio("PUT", s"/workspaces/$wsId/guardrails", Json.obj("items" -> Json.arr(Json.obj("id" -> "nope")))), 400)
    expect(studio("PUT", s"/workspaces/$wsId/model-access", Json.obj("include" -> Json.arr("llama*"))), 200)
    assertEquals(aiEntity("providers", openaiText).get.select("models").select("include").as[Seq[String]], Seq("llama*"))

    // routing
    val balancer = expect(studio("POST", s"/workspaces/$wsId/load-balancers", Json.obj("name" -> "Balanced Pool")), 201)
    assertEquals(balancer.select("name").asString, "balanced_pool")
    assertEquals(balancer.select("targets").as[Seq[JsObject]].map(_.select("ref").asString).toSet, Set(ollamaText, openaiText))
    expect(studio("POST", s"/workspaces/$wsId/load-balancers", Json.obj("name" -> "balanced_pool")), 409)
    val twoModels = expect(studio("PUT", s"/workspaces/$wsId/load-balancers/${balancer.select("id").asString}", Json.obj("targets" -> Json.arr(
      Json.obj("ref" -> openaiText, "model" -> "gpt-4o-mini"), Json.obj("ref" -> openaiText, "model" -> "gpt-4o"),
    ))), 200)
    assertEquals(twoModels.select("targets").as[Seq[JsObject]].map(_.select("model").asString), Seq("gpt-4o-mini", "gpt-4o"))
    assertEquals(aiEntity("providers", balancer.select("id").asString).get.select("options").select("refs").as[Seq[JsObject]].map(_.select("model").asString), Seq("gpt-4o-mini", "gpt-4o"))
    val router = expect(studio("POST", s"/workspaces/$wsId/routers", Json.obj("auto_router_refs" -> Json.arr(ollamaText, openaiText), "cost_quality_tradeoff" -> 42)), 201)
    assertEquals(router.select("modes").as[Seq[String]], Seq("auto"))
    // a candidate given with a model keeps it when the candidates are later given as ids
    expect(studio("PUT", s"/workspaces/$wsId/routers/${router.select("id").asString}", Json.obj("auto_router_refs" -> Json.arr(Json.obj("ref" -> openaiText, "model" -> "gpt-4o-mini"), ollamaText), "auto_router_classifier_model" -> "gpt-4o-mini")), 200)
    val kept = expect(studio("PUT", s"/workspaces/$wsId/routers/${router.select("id").asString}", Json.obj("auto_router_refs" -> Json.arr(openaiText, ollamaText))), 200)
    assertEquals(kept.select("auto_router_refs").as[Seq[JsValue]], Seq(Json.obj("ref" -> openaiText, "model" -> "gpt-4o-mini"), play.api.libs.json.JsString(ollamaText)))
    assertEquals(kept.select("auto_router_classifier_model").asString, "gpt-4o-mini")
    assertEquals(router.select("cost_quality_tradeoff").as[BigDecimal], BigDecimal(10))
    expect(studio("POST", s"/workspaces/$wsId/routers", Json.obj("name" -> "empty")), 400)
    assertEquals(compat(wsId).select("language_model_refs").as[Seq[String]], Seq(ollamaText, openaiText, balancer.select("id").asString, router.select("id").asString))
    val routing = expect(studio("PUT", s"/workspaces/$wsId/routing", Json.obj("default_provider" -> openaiText, "fallbacks" -> Json.obj(openaiText -> ollamaText))), 200)
    assertEquals(routing.select("default_provider").asString, openaiText)
    assertEquals(routing.select("providers").as[Seq[JsObject]].find(_.select("id").asString == openaiText).get.select("chain").as[Seq[JsObject]].map(_.select("id").asString), Seq(openaiText, ollamaText))
    assertEquals(compat(wsId).select("language_model_refs").as[Seq[String]].head, openaiText)
    assertEquals(aiEntity("providers", openaiText).get.select("provider_fallback").asString, ollamaText)
    expect(studio("DELETE", s"/workspaces/$wsId/routers/${router.select("id").asString}"), 204)
    assert(!compat(wsId).select("language_model_refs").as[Seq[String]].contains(router.select("id").asString))

    // presets
    val preset = expect(studio("POST", s"/workspaces/$wsId/presets", Json.obj("name" -> "support assistant", "system" -> "You are concise")), 201)
    assertEquals(preset.select("name").asString, "support-assistant")
    val presetId = preset.select("id").asString
    assertEquals(aiEntity("prompt-contexts", presetId).get.select("pre_messages").as[Seq[JsObject]], Seq(Json.obj("role" -> "system", "content" -> "You are concise")))
    assert(aiEntity("providers", ollamaText).get.select("context").select("contexts").as[Seq[String]].contains(presetId))
    assertEquals(compat(wsId).select("context_refs").as[Seq[String]], Seq(presetId))
    expect(studio("PUT", s"/workspaces/$wsId/presets/$presetId", Json.obj("providers" -> Json.arr(openaiText))), 200)
    assert(!aiEntity("providers", ollamaText).get.select("context").select("contexts").as[Seq[String]].contains(presetId))

    // tools
    val search = expect(studio("POST", s"/workspaces/$wsId/tools/search", Json.obj("name" -> "web", "search_provider" -> "tavily", "token" -> "tvly-test")), 201)
    val searchEntity = aiEntity("search-engines", search.select("id").asString).get
    assertEquals(searchEntity.select("provider").asString, "tavily")
    assert(searchEntity.select("config").select("connection").select("base_url").asString.contains("tavily"))
    assertEquals(searchEntity.select("config").select("connection").select("token").asString, "tvly-test")
    assert(aiEntity("providers", openaiText).get.select("options").select("search_engines").as[Seq[String]].contains(search.select("id").asString))
    val function = expect(studio("POST", s"/workspaces/$wsId/tools/functions", Json.obj(
      "name" -> "weather",
      "url" -> "https://weather.oto.tools",
      "providers" -> Json.arr(ollamaText),
      "parameters" -> Json.obj("type" -> "object", "properties" -> Json.obj("city" -> Json.obj("type" -> "string")), "required" -> Json.arr("city")),
    )), 201)
    val functionEntity = aiEntity("tool-functions", function.select("id").asString).get
    assertEquals(functionEntity.select("backend").select("options").select("url").asString, "https://weather.oto.tools")
    assertEquals(function.select("providers").as[Seq[String]], Seq(ollamaText))
    // the form speaks json schema, the entity stores the properties and the required ones next to them,
    // so what models and MCP clients are given is a valid schema and not a schema nested in a schema
    assertEquals(functionEntity.select("parameters").as[JsObject], Json.obj("city" -> Json.obj("type" -> "string")))
    assertEquals(functionEntity.select("required").as[Seq[String]], Seq("city"))
    assertEquals(function.select("parameters").select("properties").select("city").select("type").asString, "string")
    expect(studio("POST", s"/workspaces/$wsId/tools/mcp", Json.obj("name" -> "no url")), 400)
    expect(studio("GET", s"/workspaces/$wsId/tools/nope"), 404)
    // mcp connectors are created on the stateless revision of the protocol
    val connector = expect(studio("POST", s"/workspaces/$wsId/tools/mcp", Json.obj("name" -> "github", "url" -> "https://mcp.oto.tools/mcp")), 201)
    assertEquals(connector.select("transport").asString, "http_2026_07_28")
    val connectorId = connector.select("id").asString
    assertEquals(aiEntity("mcp-connectors", connectorId).get.select("transport").select("kind").asString, "http_2026_07_28")
    // and an update does not change the transport of a connector created elsewhere
    expect(studio("PUT", s"/workspaces/$wsId/tools/mcp/$connectorId", Json.obj("name" -> "github renamed")), 200)
    assertEquals(aiEntity("mcp-connectors", connectorId).get.select("transport").select("kind").asString, "http_2026_07_28")
    expect(studio("DELETE", s"/workspaces/$wsId/tools/mcp/$connectorId"), 204)

    // mcp server: a virtual server exposed on /mcp of the workspace route
    val noServer = expect(studio("GET", s"/workspaces/$wsId/mcp-server"), 200)
    assertEquals(noServer.select("served").asBoolean, false)
    assert(noServer.select("url").asString.endsWith("/v1/mcp"), noServer.select("url").asString)
    expect(studio("PUT", s"/workspaces/$wsId/mcp-server", Json.obj("name" -> "")), 400)
    expect(studio("PUT", s"/workspaces/$wsId/mcp-server", Json.obj("name" -> "tools", "functions" -> Json.arr("not-of-this-workspace"))), 400)
    val mcpServer = expect(studio("PUT", s"/workspaces/$wsId/mcp-server", Json.obj(
      "name" -> "Studio tools",
      "functions" -> Json.arr(function.select("id").asString),
    )), 200)
    assertEquals(mcpServer.select("served").asBoolean, true)
    assertEquals(mcpServer.select("functions").as[Seq[String]], Seq(function.select("id").asString))
    val serverId = mcpServer.select("id").asString
    assertEquals(compat(wsId).select("mcp_server_ref").asString, serverId)
    val serverEntity = aiEntity("mcp-virtual-servers", serverId).get
    assertEquals(serverEntity.select("config").select("refs").as[Seq[String]], Seq(function.select("id").asString))
    // the activity of the workspace needs the audit events, whatever the caller asked for
    assertEquals(serverEntity.select("config").select("emit_audit_events").asBoolean, true)
    assertEquals(serverEntity.select("metadata").select("ai_studio_workspace").asString, wsId)
    // a partial update keeps what it does not name, on the entity as on the form
    expect(studio("PUT", s"/workspaces/$wsId/mcp-server", Json.obj("enabled" -> false)), 200)
    val disabledServer = aiEntity("mcp-virtual-servers", serverId).get
    assertEquals(disabledServer.select("enabled").asBoolean, false)
    assertEquals(disabledServer.select("name").asString, "Studio tools")
    assertEquals(disabledServer.select("config").select("refs").as[Seq[String]], Seq(function.select("id").asString))
    expect(studio("DELETE", s"/workspaces/$wsId/mcp-server"), 204)
    assert(aiEntity("mcp-virtual-servers", serverId).isEmpty)
    assertEquals(compat(wsId).select("mcp_server_ref").asOpt[String], None)

    expect(studio("DELETE", s"/workspaces/$wsId/tools/functions/${function.select("id").asString}"), 204)
    assert(!aiEntity("providers", ollamaText).get.select("options").select("tool_functions").asOpt[Seq[String]].getOrElse(Seq.empty).contains(function.select("id").asString))

    // settings
    val settings = expect(studio("PATCH", s"/workspaces/$wsId", Json.obj("slug" -> "renamed", "allowed_ip_addresses" -> Json.arr("10.0.0.1"), "call_timeout" -> 30000)), 200)
    assertEquals(settings.select("slug").asString, "renamed")
    assertEquals(settings.select("name").asString, "Studio API")
    val renamed = route(wsId)
    assertEquals(renamed.select("frontend").select("domains").as[Seq[String]], Seq("renamed.oto.tools/v1"))
    assertEquals(renamed.select("backend").select("client").select("call_and_stream_timeout").as[Long], 30000L)
    assertEquals(renamed.select("plugins").as[Seq[JsObject]].find(_.select("plugin").asString.endsWith("IpAddressAllowedList")).get.select("enabled").asBoolean, true)

    // deleting the key removes its credit limit, deleting the workspace removes everything
    expect(studio("DELETE", s"/workspaces/$wsId/apikeys/$clientId"), 204)
    assert(aiEntity("ai-budgets", keyBudgetId).isEmpty)
    expect(studio("DELETE", s"/workspaces/$wsId"), 204)
    assert(entity("proxy.otoroshi.io", "routes", s"route_ai_studio_$wsId").isEmpty)
    assert(entity("organize.otoroshi.io", "teams", s"team_ai_studio_$wsId").isEmpty)
    assert(aiEntity("providers", ollamaText).isEmpty)
    assert(aiEntity("ai-budgets", budget.select("id").asString).isEmpty)
    assert(aiEntity("search-engines", search.select("id").asString).isEmpty)
    expect(studio("GET", s"/workspaces/$wsId"), 404)
  }
}
