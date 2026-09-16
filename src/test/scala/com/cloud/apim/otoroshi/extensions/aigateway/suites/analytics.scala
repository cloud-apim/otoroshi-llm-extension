package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.analytics.*
import io.vertx.pgclient.{PgBuilder, PgConnectOptions}
import io.vertx.sqlclient.{Pool, PoolOptions, Tuple => VertxTuple}
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import otoroshi.env.Env
import otoroshi.next.analytics.exporter.{AnalyticsProjection, UserAnalyticsExporterSettings}
import otoroshi.next.analytics.queries.{AnalyticsQuery, Bucket, Filters, QueryResult}
import play.api.libs.json.*

import java.time.Instant
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters.*

/** Samples shaped after what the extension actually emits, secrets and content included. */
object AnalyticsSamples {

  private def now: Long = System.currentTimeMillis() - 5 * 60 * 1000L

  private val apikey = Json.obj(
    "clientId"     -> "key_1",
    "clientName"   -> "team-a",
    "clientSecret" -> "s3cr3t-never-stored",
    "_loc"         -> Json.obj("tenant" -> "acme", "teams" -> Json.arr("red"))
  )
  private val user   = Json.obj("email" -> "jane@acme.io", "name" -> "Jane", "token" -> Json.obj("access_token" -> "tok3n-never-stored"))
  private val route  = Json.obj(
    "id"       -> "route_1",
    "name"     -> "chat-api",
    "_loc"     -> Json.obj("tenant" -> "acme", "teams" -> Json.arr("red")),
    "groups"   -> Json.arr("group_1"),
    "api_ref"  -> "api_1",
    "backend"  -> Json.obj("targets" -> Json.arr()),
    "plugins"  -> Json.arr()
  )

  private def envelope(id: String, audit: String) = Json.obj(
    "@id"        -> id,
    "@timestamp" -> now,
    "@type"      -> "AuditEvent",
    "@product"   -> "otoroshi",
    "@env"       -> "prod",
    "audit"      -> audit,
    "request_id" -> s"req_$id",
    "apikey"     -> apikey,
    "user"       -> user,
    "route"      -> route
  )

  private def impacts(gwp: Double) = Json.obj(
    "energy"   -> Json.obj("value" -> Json.obj("min" -> 0.001, "max" -> 0.003, "avg" -> 0.002), "unit" -> "kWh", "description" -> "long text"),
    "gwp"      -> Json.obj("value" -> Json.obj("min" -> gwp, "max" -> gwp, "avg" -> gwp), "unit" -> "kgCO2eq"),
    "adpe"     -> Json.obj("value" -> Json.obj("min" -> 1e-8, "max" -> 1e-8, "avg" -> 1e-8), "unit" -> "kgSbeq"),
    "pe"       -> Json.obj("value" -> Json.obj("min" -> 0.02, "max" -> 0.02, "avg" -> 0.02), "unit" -> "MJ"),
    "wcf"      -> Json.obj("value" -> Json.obj("min" -> 0.01, "max" -> 0.01, "avg" -> 0.01), "unit" -> "L"),
    "usage"    -> Json.obj("gwp" -> Json.obj("value" -> Json.obj("avg" -> gwp * 0.75), "unit" -> "kgCO2eq")),
    "embodied" -> Json.obj("gwp" -> Json.obj("value" -> Json.obj("avg" -> gwp * 0.25), "unit" -> "kgCO2eq"))
  )

  private def providerDetails(id: String, kind: String) =
    Json.obj("id" -> id, "name" -> s"$id-name", "provider" -> kind, "connection" -> Json.obj("token" -> "********"), "options" -> Json.obj("model" -> "gpt-4.1-mini"))

  private def slug(provider: String, model: String, prompt: Long, gen: Long, duration: Long = 400L) = Json.obj(
    "provider"   -> provider,
    "duration"   -> duration,
    "model"      -> model,
    "rate_limit" -> Json.obj("requests_limit" -> 100, "requests_remaining" -> 99, "tokens_limit" -> 10000, "tokens_remaining" -> 9000),
    "usage"      -> Json.obj("prompt_tokens" -> prompt, "generation_tokens" -> gen, "reasoning_tokens" -> 0)
  )

  private def output(content: String, cache: Option[String] = None) = Json.obj(
    "generations" -> Json.arr(Json.obj("message" -> Json.obj("role" -> "assistant", "content" -> content))),
    "metadata"    -> (Json.obj("usage" -> Json.obj("prompt_tokens" -> 0, "generation_tokens" -> 0, "reasoning_tokens" -> 0)) ++
      cache.map(s => Json.obj("cache" -> Json.obj("status" -> s, "key" -> "k", "ttl" -> 60, "age" -> 3))).getOrElse(Json.obj()))
  )

  private def costs(total: Double) =
    Json.obj("input_cost" -> total * 0.25, "output_cost" -> total * 0.75, "reasoning_cost" -> 0, "total_cost" -> total, "currency" -> "dollar", "source" -> "price-table")

  // a plain chat call: 100 + 50 tokens, $0.002, 0.8 gCO2eq, one budget, in a session
  val chat: JsObject = envelope("llm_chat", "LLMUsageAudit") ++ slug("provider_1", "gpt-4.1-mini", 100, 50) ++ Json.obj(
    "call_duration"    -> 450,
    "finish_reason"    -> "stop",
    "session_id"       -> "sess_1",
    "end_user"         -> "customer-42",
    "client_ip"        -> "10.0.0.1",
    "provider_kind"    -> "openai",
    "consumed_using"   -> "chat/completion/blocking",
    "input_prompt"     -> Json.arr(Json.obj("role" -> "user", "content" -> "my private question")),
    "output"           -> output("why did the scarecrow win an award"),
    "provider_details" -> providerDetails("provider_1", "openai"),
    "error"            -> JsNull,
    "impacts"          -> impacts(0.0008),
    "costs"            -> costs(0.002),
    "budgets"          -> Json.arr(Json.obj("budget_id" -> "budget_1", "consumption" -> JsNull))
  )

  // a streaming call on another model: 200 + 100 tokens, $0.01, truncated, in the same session
  val streaming: JsObject = envelope("llm_stream", "LLMUsageAudit") ++ slug("provider_2", "claude-sonnet-5", 200, 100, 1200) ++ Json.obj(
    "time_to_first_token" -> 150,
    "finish_reason"       -> "length",
    "session_id"          -> "sess_1",
    "provider_kind"    -> "anthropic",
    "consumed_using"   -> "chat/completion/streaming",
    "output"           -> output("streamed"),
    "output_stream"    -> Json.arr(Json.obj("content" -> "streamed")),
    "provider_details" -> providerDetails("provider_2", "anthropic"),
    "error"            -> JsNull,
    "impacts"          -> impacts(0.002),
    "costs"            -> costs(0.01),
    "budgets"          -> Json.arr()
  )

  // the load balancer re-reporting the streaming call: must never be counted
  val loadBalancer: JsObject = streaming ++ Json.obj(
    "@id"              -> "llm_lb",
    "provider_kind"    -> "loadbalancer",
    "provider_details" -> providerDetails("provider_lb", "loadbalancer")
  )

  // a primary provider re-reporting what its fallback served: must never be counted either
  val fallback: JsObject = chat ++ Json.obj(
    "@id"              -> "llm_fallback_outer",
    "provider_details" -> providerDetails("provider_primary", "openai")
  )

  val providerError: JsObject = envelope("llm_error", "LLMUsageAudit") ++ Json.obj(
    "provider_kind"    -> "openai",
    "consumed_using"   -> "chat/completion/blocking",
    "input_prompt"     -> Json.arr(),
    "output"           -> JsNull,
    "provider_details" -> providerDetails("provider_1", "openai"),
    "error"            -> Json.obj("status" -> 429, "body" -> Json.obj("error" -> Json.obj("message" -> "Rate limit reached")))
  )

  val guardrailDenied: JsObject = envelope("llm_guardrail", "LLMUsageAudit") ++ Json.obj(
    "provider_kind"    -> "openai",
    "consumed_using"   -> "chat/completion/blocking",
    "output"           -> JsNull,
    "provider_details" -> providerDetails("provider_1", "openai"),
    "error"            -> Json.obj("error" -> "guardrail_denied", "error_description" -> "pif detected", "phase" -> "before")
  )

  // a cache hit after an llm guardrail call: the slug on the event is the guardrail's
  val cacheHit: JsObject = envelope("llm_cache", "LLMUsageAudit") ++ slug("provider_guard", "guard-model", 1000, 1000) ++ Json.obj(
    "provider_kind"    -> "openai",
    "consumed_using"   -> "chat/completion/blocking",
    "output"           -> output("cached answer", Some("Hit")),
    "provider_details" -> providerDetails("provider_1", "openai"),
    "error"            -> JsNull,
    "impacts"          -> JsNull,
    "costs"            -> JsNull,
    "budgets"          -> Json.arr()
  )

  val embedding: JsObject = envelope("llm_embedding", "LLMUsageAudit") ++ Json.obj(
    "provider_kind"    -> "openai",
    "provider"         -> "emb_1",
    "duration"         -> 80,
    "consumed_using"   -> "embedding_model/embedding",
    "input_body"       -> Json.obj("model" -> "text-embedding-3-small", "input" -> "private text"),
    "output"           -> Json.obj("model" -> "text-embedding-3-small", "usage" -> Json.obj("prompt_tokens" -> 12, "total_tokens" -> 12), "data" -> Json.arr(Json.obj("embedding" -> Json.arr(0.1, 0.2)))),
    "provider_details" -> providerDetails("emb_1", "openai"),
    "error"            -> JsNull,
    "impacts"          -> JsNull,
    "costs"            -> costs(0.0001),
    "budgets"          -> Json.arr()
  )

  val image: JsObject = envelope("llm_image", "LLMUsageAudit") ++ Json.obj(
    "provider_kind"    -> "openai",
    "provider"         -> "img_1",
    "duration"         -> 9000,
    "consumed_using"   -> "image_model/generate",
    "input_body"       -> Json.obj("model" -> "gpt-image-1", "prompt" -> "a cat"),
    "output"           -> Json.obj("created" -> 1, "data" -> Json.arr(Json.obj("b64_json" -> "AAAA")), "usage" -> Json.obj("usage" -> Json.obj("input_tokens" -> 10, "output_tokens" -> 20, "total_tokens" -> 40))),
    "provider_details" -> providerDetails("img_1", "openai"),
    "error"            -> JsNull,
    "impacts"          -> JsNull,
    "costs"            -> JsNull,
    "budgets"          -> Json.arr()
  )

  // made by an app, with a key owned by john: the call counts for him
  val audio: JsObject = (envelope("llm_audio", "LLMUsageAudit") - "user") ++ Json.obj(
    "apikey"           -> (apikey ++ Json.obj("metadata" -> Json.obj("ai_studio_owner" -> "john@acme.io", "internal" -> "private-metadata"))),
    "provider_kind"    -> "openai",
    "provider"         -> "audio_1",
    "duration"         -> 700,
    "consumed_using"   -> "audio_model/stt",
    "input_body"       -> Json.obj("model" -> "whisper-1"),
    "output"           -> Json.obj("text" -> "private transcript", "usage" -> Json.obj("type" -> "tokens", "input_tokens" -> 5, "output_tokens" -> 7, "total_tokens" -> 5)),
    "provider_details" -> providerDetails("audio_1", "openai"),
    "error"            -> JsNull,
    "impacts"          -> JsNull,
    "costs"            -> JsNull,
    "budgets"          -> Json.arr()
  )

  val llm: Seq[JsObject] = Seq(chat, streaming, loadBalancer, fallback, providerError, guardrailDenied, cacheHit, embedding, image, audio)

  private def mcpServer(id: String, method: String, tool: Option[String], isError: Boolean = false, status: String = "success") =
    envelope(id, "McpAudit") ++ Json.obj(
      "mcp_method"           -> method,
      "mcp_id"               -> 1,
      "mcp_request_payload"  -> Json.obj("jsonrpc" -> "2.0", "id" -> 1, "method" -> method,
        "params" -> tool.map(t => Json.obj("name" -> t, "arguments" -> Json.obj("secret_arg" -> "private-argument"))).getOrElse(Json.obj())),
      "mcp_response"         -> Json.obj("jsonrpc" -> "2.0", "id" -> 1, "result" -> Json.obj("isError" -> isError, "content" -> Json.arr(Json.obj("text" -> "private-result")))),
      "transport"            -> "http",
      "mcp_protocol_version" -> "2026-07-28",
      "duration"             -> 42,
      "status"               -> status,
      "error"                -> (if (status == "error") JsString("proxy_engine_error") else JsNull)
    )

  val mcpToolCall: JsObject   = mcpServer("mcp_call", "tools/call", Some("get_weather"))
  val mcpToolError: JsObject  = mcpServer("mcp_tool_error", "tools/call", Some("get_weather"), isError = true)
  val mcpInitialize: JsObject = mcpServer("mcp_init", "initialize", None)
  val mcpClient: JsObject     = envelope("mcp_client", "McpClientAudit") ++ Json.obj(
    "mcp_connector_id"   -> "connector_1",
    "mcp_connector_name" -> "github",
    "mcp_connector_kind" -> "http_2026_07_28",
    "mcp_operation"      -> "tools/call",
    "mcp_request"        -> Json.obj("name" -> "search_repos", "arguments" -> Json.obj("q" -> "private-argument")),
    "mcp_response"       -> Json.obj("content" -> Json.arr()),
    "duration"           -> 300,
    "status"             -> "success",
    "error"              -> JsNull
  )
  val mcpFetch: JsObject      = envelope("mcp_fetch", "McpResourceFetchAudit") ++ Json.obj(
    "resource_uri" -> "file://docs/readme",
    "fetch_url"    -> "https://internal/readme",
    "http_status"  -> 404,
    "duration"     -> 12,
    "status"       -> "error",
    "error"        -> "status 404"
  )

  // served to an MCP client authenticating with a key owned by john: the tool call counts for him
  val mcpOwned: JsObject = (mcpServer("mcp_owned", "tools/call", Some("list_incidents")) - "user") ++ Json.obj(
    "apikey" -> (apikey ++ Json.obj("metadata" -> Json.obj("ai_studio_owner" -> "john@acme.io", "internal" -> "private-metadata")))
  )

  val mcp: Seq[JsObject] = Seq(mcpToolCall, mcpToolError, mcpInitialize, mcpClient, mcpFetch, mcpOwned)

  private def alert(id: String, name: String) = Json.obj(
    "@id"        -> id,
    "@timestamp" -> now,
    "@type"      -> "AlertEvent",
    "@env"       -> "prod",
    "alert"      -> name
  )

  val budgetExceeded: JsObject = alert("alert_budget", "AiBudgetExceeded") ++ Json.obj("budgets" -> Json.arr("budget_1"), "apikey" -> Json.obj("clientId" -> "key_1"), "route" -> route)
  val budgetAlmost: JsObject   = alert("alert_budget_almost", "AiBudgetAlmostExceeded") ++ Json.obj(
    "budget"      -> Json.obj("id" -> "budget_1", "name" -> "Team A", "limits" -> Json.obj("total_usd" -> 10)),
    "consumption" -> Json.obj("consumed_total_usd" -> 9),
    "percentages" -> Json.obj("total_usd" -> 90.0, "total_tokens" -> 12.0)
  )
  val quotaExceeded: JsObject  = alert("alert_quota", "LLMProviderQuotaExceededAlert") ++ Json.obj("provider_kind" -> "openai", "kind" -> "throttled", "status" -> 429, "refused_calls" -> 0)
  val quotaRecovered: JsObject = alert("alert_quota_ok", "LLMProviderQuotaRecoveredAlert") ++ Json.obj("provider_kind" -> "openai", "kind" -> "throttled", "refused_calls" -> 17, "duration_ms" -> 60000)
  val zeroTrust: JsObject      = envelope("zt_1", "McpZeroTrustAlert") ++ Json.obj(
    "zerotrust_kind" -> "rugpull",
    "mcp_tool"       -> "get_weather",
    "blocked"        -> true,
    "detail"         -> Json.obj("previous" -> "a very long description", "current" -> "another one", "phase" -> "list")
  )

  val alerts: Seq[JsObject] = Seq(budgetExceeded, budgetAlmost, quotaExceeded, quotaRecovered, zeroTrust)
}

object AnalyticsTestSupport {

  def columns(projection: AnalyticsProjection): Seq[String] = {
    val sql = projection.insertSql(UserAnalyticsExporterSettings())
    sql.split("\\) VALUES").head.split("\\(", 2)(1).split(",").map(_.trim).toSeq
  }

  def row(projection: AnalyticsProjection, event: JsValue): Map[String, Any] = {
    val tuple = projection.toTuple(projection.strip(event))
    columns(projection).zipWithIndex.map { case (c, i) => c -> tuple.getValue(i) }.toMap
  }
}

/** Pure mapping and catalogue checks — no database needed. */
class AnalyticsProjectionsSuite extends munit.FunSuite {

  import AnalyticsSamples.*
  import AnalyticsTestSupport.*

  private val projections = Seq(LlmUsageProjection -> llm, McpCallsProjection -> mcp, AiAlertsProjection -> alerts)

  test("each projection claims its own events and nothing else") {
    projections.foreach { case (p, own) =>
      own.foreach(e => assert(p.accepts(e), s"${p.id} should accept ${(e \ "@id").as[String]}"))
      projections.filterNot(_._1 == p).flatMap(_._2).foreach(e => assert(!p.accepts(e), s"${p.id} should not accept ${(e \ "@id").as[String]}"))
      assert(!p.accepts(Json.obj("@type" -> "GatewayEvent")))
    }
  }

  test("the insert lists as many columns as every tuple provides values") {
    projections.foreach { case (p, own) =>
      own.foreach(e => assertEquals(p.toTuple(p.strip(e)).size(), columns(p).size, s"${p.id} / ${(e \ "@id").as[String]}"))
    }
  }

  test("secrets and content never reach a row") {
    projections.foreach { case (p, own) =>
      own.foreach { e =>
        val stored = Json.stringify(p.strip(e))
        Seq("s3cr3t-never-stored", "tok3n-never-stored", "my private question", "scarecrow", "private text",
          "private transcript", "private-argument", "private-result", "private-metadata", "AAAA", "a very long description", "long text")
          .foreach(s => assert(!stored.contains(s), s"'$s' leaked into ${p.id} / ${(e \ "@id").as[String]}"))
      }
    }
  }

  test("calls re-reported by a load balancer, a router or a fallback are flagged") {
    assertEquals(row(LlmUsageProjection, chat)("delegated"), false)
    assertEquals(row(LlmUsageProjection, streaming)("delegated"), false)
    assertEquals(row(LlmUsageProjection, loadBalancer)("delegated"), true)
    assertEquals(row(LlmUsageProjection, fallback)("delegated"), true)
    assertEquals(row(LlmUsageProjection, providerError)("delegated"), false)
  }

  test("a cache hit ignores the usage slug another call left on the request") {
    val r = row(LlmUsageProjection, cacheHit)
    assertEquals(r("delegated"), false)
    assertEquals(r("cache_status"), "hit")
    assertEquals(r("total_tokens"), 0L)
    assertEquals(r("model"), "gpt-4.1-mini")
    assertEquals(r("duration_ms"), null)
  }

  test("chat rows carry identity, usage, costs, impacts and budgets") {
    val r = row(LlmUsageProjection, chat)
    assertEquals(r("tenant"), "acme")
    assertEquals(r("route_id"), "route_1")
    assertEquals(r("api_id"), "api_1")
    assertEquals(r("apikey_id"), "key_1")
    assertEquals(r("apikey_name"), "team-a")
    assertEquals(r("user_email"), "jane@acme.io")
    assertEquals(r("apikey_owner"), null)
    assertEquals(r("from_ip"), "10.0.0.1")
    // measured around the call, over the provider's own figure
    assertEquals(r("duration_ms"), 450L)
    assertEquals((r("finish_reason"), r("session_id"), r("end_user"), r("ttft_ms")), ("stop", "sess_1", "customer-42", null))
    assertEquals(row(LlmUsageProjection, streaming)("ttft_ms"), 150L)
    assertEquals(r("modality"), "chat")
    assertEquals(r("streaming"), false)
    assertEquals(r("model"), "gpt-4.1-mini")
    assertEquals(r("provider_name"), "provider_1-name")
    assertEquals(r("input_tokens"), 100L)
    assertEquals(r("output_tokens"), 50L)
    assertEquals(r("total_tokens"), 150L)
    assertEquals(r("total_cost"), 0.002)
    assertEquals(r("gwp_kgco2eq"), 0.0008)
    assertEquals(r("energy_kwh"), 0.002)
    assertEquals(r("ratelimit_tokens_remaining"), 9000L)
    assertEquals(r("budget_ids").asInstanceOf[Array[String]].toSeq, Seq("budget_1"))
    assertEquals(r("err"), false)
  }

  test("every modality's model and tokens are found where that modality puts them") {
    val e = row(LlmUsageProjection, embedding)
    assertEquals((e("modality"), e("model"), e("total_tokens")), ("embedding", "text-embedding-3-small", 12L))
    val i = row(LlmUsageProjection, image)
    assertEquals((i("modality"), i("model"), i("input_tokens"), i("output_tokens"), i("total_tokens")), ("image", "gpt-image-1", 10L, 20L, 40L))
    val a = row(LlmUsageProjection, audio)
    assertEquals((a("modality"), a("model"), a("total_tokens")), ("audio", "whisper-1", 12L))
    assertEquals(row(LlmUsageProjection, streaming)("streaming"), true)
  }

  test("a call made with an owned api key carries its owner, and nothing else of the key metadata") {
    val r = row(LlmUsageProjection, audio)
    assertEquals((r("apikey_id"), r("apikey_owner"), r("user_email")), ("key_1", "john@acme.io", null))
    assertEquals((LlmUsageProjection.strip(audio) \ "apikey" \ "metadata").as[JsObject], Json.obj("ai_studio_owner" -> "john@acme.io"))
  }

  test("errors are classified") {
    val p = row(LlmUsageProjection, providerError)
    assertEquals((p("err"), p("error_kind"), p("error_message")), (true, "status_429", "Rate limit reached"))
    val g = row(LlmUsageProjection, guardrailDenied)
    assertEquals((g("err"), g("error_kind"), g("error_message")), (true, "guardrail_denied", "pif detected"))
    // what openai and anthropic actually send back: the provider's own classification wins
    val openai = providerError ++ Json.obj("error" -> Json.obj("error" -> Json.obj(
      "message" -> "Unsupported parameter: 'max_tokens' is not supported with this model.", "type" -> "invalid_request_error", "code" -> "unsupported_parameter")))
    val o = row(LlmUsageProjection, openai)
    assertEquals((o("error_kind"), o("error_message")), ("unsupported_parameter", "Unsupported parameter: 'max_tokens' is not supported with this model."))
    val anthropic = providerError ++ Json.obj("error" -> Json.obj("status" -> 400, "body" -> Json.obj("type" -> "error",
      "error" -> Json.obj("type" -> "invalid_request_error", "message" -> "Your credit balance is too low"))))
    val a = row(LlmUsageProjection, anthropic)
    assertEquals((a("error_kind"), a("error_message")), ("invalid_request_error", "Your credit balance is too low"))
  }

  test("mcp rows tell the tool, the side and the failure apart") {
    val call = row(McpCallsProjection, mcpToolCall)
    assertEquals((call("side"), call("method"), call("tool"), call("err"), call("tool_error"), call("protocol_version")), ("server", "tools/call", "get_weather", false, false, "2026-07-28"))
    assertEquals(row(McpCallsProjection, mcpToolError)("tool_error"), true)
    assertEquals(row(McpCallsProjection, mcpInitialize)("tool"), null)
    val client = row(McpCallsProjection, mcpClient)
    assertEquals((client("side"), client("tool"), client("connector_name"), client("transport")), ("client", "search_repos", "github", "http_2026_07_28"))
    val fetch = row(McpCallsProjection, mcpFetch)
    assertEquals((fetch("side"), fetch("target"), fetch("err"), fetch("http_status")), ("fetch", "file://docs/readme", true, 404))
    // a call made with someone's key counts for them, as a model call does
    val owned = row(McpCallsProjection, mcpOwned)
    assertEquals((owned("apikey_owner"), owned("user_email"), owned("tool")), ("john@acme.io", null, "list_incidents"))
    assertEquals(call("apikey_owner"), null)
  }

  test("alert rows are categorised") {
    val b = row(AiAlertsProjection, budgetAlmost)
    assertEquals((b("category"), b("kind"), b("budget_name"), b("percentage")), ("budget", "almost_exceeded", "Team A", 0.9))
    val r = row(AiAlertsProjection, quotaRecovered)
    assertEquals((r("category"), r("kind"), r("refused_calls"), r("err")), ("provider_quota", "recovered", 17L, false))
    val z = row(AiAlertsProjection, zeroTrust)
    assertEquals((z("category"), z("kind"), z("tool"), z("blocked")), ("mcp_zero_trust", "rugpull", "get_weather", true))
  }

  test("query ids are unique and namespaced") {
    val ids = AiGatewayQueries.all.map(_.id)
    assertEquals(ids.distinct.size, ids.size, ids.diff(ids.distinct).mkString(", "))
    ids.foreach(id => assert(id.startsWith("cloudapim_llm_") || id.startsWith("cloudapim_mcp_"), id))
  }

  test("an installed default follows newer versions only while nobody edited it") {
    import otoroshi.models.EntityLocation
    import otoroshi.next.analytics.models.UserDashboard
    val v1        = AiGatewayDashboards.Overview.copy(widgets = AiGatewayDashboards.Overview.widgets.take(3))
    val v2        = AiGatewayDashboards.Overview
    def installed(spec: DashboardSpec, metadata: Map[String, String]) =
      UserDashboard(EntityLocation.default, "dashboard_1", spec.name, spec.description, Seq.empty, metadata, true, spec.widgets, Json.obj())
    val marker    = Map(DashboardSeeding.DefaultIdKey -> v1.defaultId, DashboardSeeding.DefaultHashKey -> DashboardSeeding.hash(v1))
    assert(DashboardSeeding.upgradable(installed(v1, marker), v2), "an untouched v1 moves to v2")
    assert(!DashboardSeeding.upgradable(installed(v1, marker), v1), "nothing to do when already current")
    val edited    = installed(v1, marker).copy(widgets = v1.widgets.drop(1))
    assert(!DashboardSeeding.upgradable(edited, v2), "an edited dashboard is the user's")
    assert(!DashboardSeeding.upgradable(installed(v1, marker).copy(name = "Mine"), v2), "a renamed dashboard is the user's")
    assert(!DashboardSeeding.upgradable(installed(v1, marker - DashboardSeeding.DefaultHashKey), v2), "without a hash, nothing is known")
  }

  test("every dashboard widget reads a query that exists, and fits the grid") {
    val ids = AiGatewayQueries.all.map(_.id).toSet
    assertEquals(AiGatewayDashboards.all.map(_.defaultId).distinct.size, AiGatewayDashboards.all.size)
    AiGatewayDashboards.all.foreach { d =>
      assertEquals(d.widgets.map(_.id).distinct.size, d.widgets.size, d.name)
      d.widgets.foreach { w =>
        assert(ids.contains(w.query), s"${d.name} / ${w.id} reads unknown query ${w.query}")
        assert(w.width >= 1 && w.width <= 4, s"${d.name} / ${w.id} is ${w.width} wide")
      }
    }
  }
}

final class LlmAnalyticsPgContainer extends GenericContainer[LlmAnalyticsPgContainer](DockerImageName.parse("postgres:16-alpine"))

/**
 * The catalogue, executed.
 *
 * The SQL is assembled from fragments at runtime and filtered through the platform's shared clause:
 * compiling proves nothing about it. Every query runs against the tables the projections create,
 * with every filter the console can set, and the headline numbers are checked against the samples.
 */
class AnalyticsQueriesSuite extends munit.FunSuite {

  import AnalyticsSamples.*

  private val dockerThere: Boolean = {
    // docker-java defaults to an api version recent daemons refuse
    if (Option(System.getenv("DOCKER_API_VERSION")).forall(_.trim.isEmpty) && Option(System.getProperty("api.version")).forall(_.trim.isEmpty))
      System.setProperty("api.version", "1.41")
    try org.testcontainers.DockerClientFactory.instance().isDockerAvailable
    catch { case _: Throwable => false }
  }

  override def munitIgnore: Boolean = !dockerThere
  override val munitTimeout         = Duration(5, "min")

  private given ec: ExecutionContext = ExecutionContext.global
  // only the live budget queries read the environment, and they are not run here
  private given Env                  = null

  private var container: LlmAnalyticsPgContainer = null
  private var pool: Pool                         = null
  private lazy val settings                      = UserAnalyticsExporterSettings(
    host = container.getHost,
    port = container.getMappedPort(5432),
    database = "otoroshi",
    user = "otoroshi",
    password = "otoroshi"
  )

  private def await[A](f: Future[A]): A = Await.result(f, 60.seconds)

  private def exec(sql: String): Unit = {
    val p = Promise[Unit]()
    pool.query(sql).execute().onComplete(ar => if (ar.succeeded()) p.trySuccess(()) else p.tryFailure(ar.cause()))
    await(p.future)
  }

  private def insert(projection: AnalyticsProjection, events: Seq[JsObject]): Unit = {
    val rows: java.util.List[VertxTuple] = events.map(e => projection.toTuple(projection.strip(e))).asJava
    val p                                = Promise[Unit]()
    pool.preparedQuery(projection.insertSql(settings)).executeBatch(rows)
      .onComplete(ar => if (ar.succeeded()) p.trySuccess(()) else p.tryFailure(ar.cause()))
    await(p.future)
  }

  override def beforeAll(): Unit = if (dockerThere) {
    container = new LlmAnalyticsPgContainer()
      .withExposedPorts(Integer.valueOf(5432))
      .withEnv("POSTGRES_USER", "otoroshi")
      .withEnv("POSTGRES_PASSWORD", "otoroshi")
      .withEnv("POSTGRES_DB", "otoroshi")
      .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))
    container.start()
    pool = PgBuilder.pool()
      .connectingTo(new PgConnectOptions().setHost(settings.host).setPort(settings.port).setDatabase(settings.database)
        .setUser(settings.user).setPassword(settings.password))
      .`with`(new PoolOptions().setMaxSize(4))
      .build()
    exec(s"CREATE SCHEMA IF NOT EXISTS ${settings.schema};")
    Seq(LlmUsageProjection, McpCallsProjection, AiAlertsProjection).foreach { p =>
      exec(p.createTableSql(settings))
      p.indexStatements(settings).foreach(exec)
    }
    insert(LlmUsageProjection, llm)
    insert(McpCallsProjection, mcp)
    insert(AiAlertsProjection, alerts)
  }

  override def afterAll(): Unit = {
    if (pool != null) pool.close()
    if (container != null) container.stop()
  }

  private def period = Filters(from = Instant.now().minusSeconds(3600), to = Instant.now().plusSeconds(60))

  private def run(id: String, filters: Filters = period, params: JsObject = Json.obj()): QueryResult = {
    val q = AiGatewayQueries.all.find(_.id == id).getOrElse(fail(s"no query $id"))
    await(q.execute(filters, params, Bucket.OneMinute, settings, pool))
  }

  private def value(r: QueryResult): Double = (r.data \ "value").as[Double]

  private val sqlQueries: Seq[AnalyticsQuery] = AiGatewayQueries.Llm.all ++ AiGatewayQueries.Alerts.all ++ AiGatewayQueries.Mcp.all

  test("every query runs, under every filter the console can set") {
    val variants = Seq(
      period,
      period.copy(tenant = Some("acme")),
      period.copy(routeId = Some("route_1"), apiId = Some("api_1"), groupId = Some("group_1")),
      period.copy(apikeyId = Some("key_1"), err = Some(true)),
      period.copy(err = Some(false))
    )
    for {
      q <- sqlQueries
      f <- variants
      p <- Seq(Json.obj("top_n" -> 5), Json.obj("modality" -> "chat"), Json.obj("user" -> "jane@acme.io"), Json.obj("side" -> "server"))
    } {
      val result = scala.util.Try(await(q.execute(f, p, Bucket.OneMinute, settings, pool)))
      assert(result.isSuccess, s"${q.id} failed with $f / $p: ${result.failed.map(_.getMessage).getOrElse("")}")
      assertEquals(result.get.shape, q.shape, q.id)
    }
  }

  test("llm queries can be narrowed to one modality, and only to a real one") {
    assertEquals(value(run("cloudapim_llm_requests_total", params = Json.obj("modality" -> "chat"))), 5.0)
    assertEquals(value(run("cloudapim_llm_tokens_total", params = Json.obj("modality" -> "image"))), 40.0)
    // anything but a bare identifier is ignored rather than inlined
    assertEquals(value(run("cloudapim_llm_requests_total", params = Json.obj("modality" -> "chat' OR '1'='1"))), 8.0)
  }

  test("llm queries can be narrowed to one user, and a user that is not an email matches nothing") {
    assertEquals(value(run("cloudapim_llm_requests_total", params = Json.obj("user" -> "jane@acme.io"))), 7.0)
    // john made no call himself, one of his api keys did
    assertEquals(value(run("cloudapim_llm_requests_total", params = Json.obj("user" -> "john@acme.io"))), 1.0)
    assertEquals(value(run("cloudapim_llm_requests_total", params = Json.obj("user" -> "paul@acme.io"))), 0.0)
    assertEquals(value(run("cloudapim_llm_requests_total", params = Json.obj("user" -> "jane@acme.io' OR '1'='1"))), 0.0)
    assertEquals(value(run("cloudapim_llm_requests_total", params = Json.obj("user" -> "  "))), 8.0)
  }

  test("burn curves, histograms, heatmaps and latest rows") {
    val burn = (run("cloudapim_llm_cumulative_cost_over_time").data \ "points").as[Seq[JsObject]].map(p => (p \ "value").as[Double])
    assert(burn.sliding(2).forall { case Seq(a, b) => b >= a; case _ => true }, "a cumulative series never decreases")
    assertEquals(BigDecimal(burn.last).setScale(6, BigDecimal.RoundingMode.HALF_UP), BigDecimal("0.012100"))
    val bands = (run("cloudapim_llm_latency_distribution").data \ "items").as[Seq[JsObject]]
    assertEquals(bands.map(b => (b \ "key").as[String]).head, "<100ms")
    assertEquals(bands.map(b => (b \ "value").as[Long]).sum, 5L)
    val heat = run("cloudapim_llm_activity_heatmap").data
    assertEquals((heat \ "yBuckets").as[Seq[String]].size, 7)
    assertEquals((heat \ "xLabels").as[Seq[String]].take(2), Seq("00:00", "01:00"))
    assertEquals((heat \ "values").as[Seq[Seq[Long]]].map(_.size).distinct, Seq(24))
    assertEquals((heat \ "values").as[Seq[Seq[Long]]].flatten.sum, 8L)
    val recent = (run("cloudapim_llm_recent_errors").data \ "items").as[Seq[JsObject]]
    assertEquals(recent.map(r => (r \ "kind").as[String]).toSet, Set("status_429", "guardrail_denied"))
    val keys = (run("cloudapim_llm_apikeys_table").data \ "items").as[Seq[JsObject]]
    assertEquals(keys.map(k => ((k \ "apikey").as[String], (k \ "calls").as[Long])), Seq("team-a" -> 8L))
    val users = (run("cloudapim_llm_users_table").data \ "items").as[Seq[JsObject]]
    assertEquals(users.map(u => ((u \ "user").as[String], (u \ "calls").as[Long])).toSet, Set("jane@acme.io" -> 7L, "john@acme.io" -> 1L))
    val models = (run("cloudapim_llm_models_table").data \ "items").as[Seq[JsObject]]
    assert(models.forall(m => (m \ "usd_per_1k_tokens").isDefined && (m \ "gco2eq_per_1k_tokens").isDefined))
  }

  test("delegated calls are never counted") {
    // chat, streaming, error, guardrail, cache hit, embedding, image, audio — not the load balancer nor the fallback
    assertEquals(value(run("cloudapim_llm_requests_total")), 8.0)
    assertEquals(value(run("cloudapim_llm_tokens_total")), 150.0 + 300.0 + 12.0 + 40.0 + 12.0)
    assertEquals(BigDecimal(value(run("cloudapim_llm_cost_total"))).setScale(6, BigDecimal.RoundingMode.HALF_UP), BigDecimal("0.012100"))
    assertEquals(BigDecimal(value(run("cloudapim_llm_gwp_total"))).setScale(3, BigDecimal.RoundingMode.HALF_UP), BigDecimal("2.800"))
  }

  test("the calls of a request include the re-reports the other queries skip") {
    // the fallback sample re-reports the chat call, on the same request
    val calls = (run("cloudapim_llm_request_calls", params = Json.obj("request_id" -> "req_llm_chat")).data \ "items").as[Seq[JsObject]]
    assertEquals(calls.map(c => ((c \ "id").as[String], (c \ "delegated").as[Boolean])).toSet, Set("llm_chat" -> false, "llm_fallback_outer" -> true))
    assertEquals((run("cloudapim_llm_request_calls").data \ "items").as[Seq[JsObject]], Seq.empty)
  }

  test("sessions, end users, finish reasons and time to first token") {
    val sessions = (run("cloudapim_llm_sessions_table").data \ "items").as[Seq[JsObject]]
    assertEquals(sessions.map(s => ((s \ "session_id").as[String], (s \ "calls").as[Long], (s \ "models").as[Long])), Seq(("sess_1", 2L, 2L)))
    val endUsers = (run("cloudapim_llm_end_users_table").data \ "items").as[Seq[JsObject]]
    assertEquals(endUsers.map(u => ((u \ "end_user").as[String], (u \ "calls").as[Long])), Seq("customer-42" -> 1L))
    val reasons = (run("cloudapim_llm_by_finish_reason").data \ "items").as[Seq[JsObject]]
    assertEquals(reasons.map(r => ((r \ "key").asOpt[String].orElse((r \ "label").asOpt[String]).getOrElse(""), (r \ "value").as[Long])).toSet, Set("stop" -> 1L, "length" -> 1L))
    assertEquals(value(run("cloudapim_llm_truncated_rate")), 0.5)
    assertEquals(value(run("cloudapim_llm_ttft_p50")), 150.0)
    val log = (run("cloudapim_llm_calls_log", params = Json.obj("session_id" -> "sess_1", "finish_reason" -> "length")).data \ "items").as[Seq[JsObject]]
    assertEquals(log.map(c => (c \ "id").as[String]), Seq("llm_stream"))
  }

  test("the explorer ranks, splits, compares and rolls up any metric by any dimension") {
    val ranking = run("cloudapim_llm_explore", params = Json.obj("metric" -> "tokens", "group_by" -> "model", "compare" -> true)).data
    val models  = (ranking \ "items").as[Seq[JsObject]]
    assertEquals((models.head \ "key").as[String], "claude-sonnet-5")
    assertEquals((models.head \ "value").as[Long], 300L)
    // nothing happened in the previous period
    assertEquals((models.head \ "previous").as[Long], 0L)
    assertEquals((ranking \ "total").as[Long], 514L)
    assertEquals((ranking \ "additive").as[Boolean], true)
    val split   = (run("cloudapim_llm_explore", params = Json.obj("metric" -> "requests", "group_by" -> "user", "subgroup" -> "modality")).data \ "items").as[Seq[JsObject]]
    assertEquals(split.map(i => ((i \ "key").as[String], (i \ "value").as[Long])), Seq("jane@acme.io" -> 7L, "john@acme.io" -> 1L))
    assertEquals((split.head \ "subgroups").as[Seq[JsObject]].map(i => (i \ "key").as[String]).toSet, Set("chat", "embedding", "image"))
    val series  = run("cloudapim_llm_explore", params = Json.obj("metric" -> "requests", "group_by" -> "model", "rollup" -> "hour")).data
    assertEquals((series \ "bucket").as[String], "1h")
    assert((series \ "series").as[Seq[JsObject]].forall(s => (s \ "points").as[Seq[JsObject]].nonEmpty))
    assertEquals((series \ "series").as[Seq[JsObject]].flatMap(s => (s \ "points").as[Seq[JsObject]].map(p => (p \ "value").as[Long])).sum, 8L)
    // an unknown metric or dimension falls back to the defaults, never reaches the SQL
    assert((run("cloudapim_llm_explore", params = Json.obj("metric" -> "COUNT(*); DROP TABLE x", "group_by" -> "1; --")).data \ "items").isDefined)
    assertEquals((run("cloudapim_llm_explore", params = Json.obj("metric" -> "requests", "group_by" -> "status", "status" -> "error")).data \ "items").as[Seq[JsObject]].map(i => (i \ "key").as[String]).toSet, Set("status_429", "guardrail_denied"))
  }

  test("errors, guardrails and cache are counted") {
    assertEquals(value(run("cloudapim_llm_errors_total")), 2.0)
    assertEquals(value(run("cloudapim_llm_guardrail_denials_total")), 1.0)
    assertEquals(value(run("cloudapim_llm_cache_hits_total")), 1.0)
    assertEquals(value(run("cloudapim_llm_errors_total", period.copy(err = Some(false)))), 0.0)
  }

  test("rankings and series read the right keys") {
    val models = (run("cloudapim_llm_cost_by_model").data \ "items").as[Seq[JsObject]]
    assertEquals(models.map(m => (m \ "key").as[String]).headOption, Some("claude-sonnet-5"))
    val budgets = (run("cloudapim_llm_requests_by_budget").data \ "items").as[Seq[JsObject]]
    assertEquals(budgets.map(b => ((b \ "key").as[String], (b \ "value").as[Long])), Seq("budget_1" -> 1L))
    val series = (run("cloudapim_llm_tokens_by_model_over_time").data \ "series").as[Seq[JsObject]]
    assert(series.exists(s => (s \ "name").as[String] == "gpt-4․1-mini"), series.map(s => (s \ "name").as[String]).mkString(", "))
    assert(series.forall(s => (s \ "points").as[Seq[JsObject]].size >= 60))
  }

  test("mcp and alert queries") {
    assertEquals(value(run("cloudapim_mcp_calls_total")), 6.0)
    assertEquals(value(run("cloudapim_mcp_tool_calls_total")), 4.0)
    assertEquals(value(run("cloudapim_mcp_errors_total")), 2.0)
    val tools = (run("cloudapim_mcp_tools_table").data \ "items").as[Seq[JsObject]]
    assertEquals(tools.map(t => ((t \ "tool").as[String], (t \ "calls").as[Long], (t \ "failures").as[Long])).toSet, Set(("get_weather", 2L, 1L), ("search_repos", 1L, 0L), ("list_incidents", 1L, 0L)))
    assertEquals(value(run("cloudapim_mcp_zero_trust_blocked_total")), 1.0)
    assertEquals(value(run("cloudapim_llm_budget_exceeded_total")), 1.0)
    assertEquals(value(run("cloudapim_llm_provider_quota_refused_calls")), 17.0)
  }

  test("mcp usage is told apart by side and attributed to a person") {
    // what the gateway served to MCP clients, and what the models consumed through connectors, never add up
    assertEquals(value(run("cloudapim_mcp_calls_total", params = Json.obj("side" -> "server"))), 4.0)
    assertEquals(value(run("cloudapim_mcp_calls_total", params = Json.obj("side" -> "client"))), 1.0)
    assertEquals(value(run("cloudapim_mcp_calls_total", params = Json.obj("side" -> "fetch"))), 1.0)
    assertEquals(value(run("cloudapim_mcp_distinct_users")), 2.0)
    assertEquals(value(run("cloudapim_mcp_distinct_tools", params = Json.obj("side" -> "server"))), 2.0)
    // the call made with john's key counts for him, the rest for the user who made them
    assertEquals(value(run("cloudapim_mcp_calls_total", params = Json.obj("user" -> "john@acme.io"))), 1.0)
    assertEquals(value(run("cloudapim_mcp_calls_total", params = Json.obj("user" -> "jane@acme.io"))), 5.0)
    assertEquals(value(run("cloudapim_mcp_calls_total", params = Json.obj("user" -> "not an email; DROP"))), 0.0)
    val users = (run("cloudapim_mcp_users_table").data \ "items").as[Seq[JsObject]]
    assertEquals(users.map(u => ((u \ "user").as[String], (u \ "calls").as[Long], (u \ "tool_calls").as[Long])).toSet,
      Set(("jane@acme.io", 5L, 3L), ("john@acme.io", 1L, 1L)))
    val recent = (run("cloudapim_mcp_recent_calls").data \ "items").as[Seq[JsObject]]
    assertEquals(recent.size, 6)
    assertEquals(recent.count(r => (r \ "owner").as[String] == "john@acme.io"), 1)
  }
}
