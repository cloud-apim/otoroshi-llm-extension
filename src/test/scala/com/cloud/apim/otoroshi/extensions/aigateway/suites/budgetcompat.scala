package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.decorators.ChatClientWithAuding
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiBudget, AiBudgetClusterAgent, AiBudgetUsageKind, AiBudgetsDataStore, AiProvider}
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatMessage, ChatPrompt, LlmExtensionOneOtoroshiServerPerSuite}
import otoroshi.env.Env
import otoroshi.models.{DataExporterConfig, DataExporterConfigFiltering, DataExporterConfigTypeFile, FileSettings}
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.*
import play.api.libs.ws.WSResponse
import reactor.core.publisher.Mono

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.{AtomicLong, DoubleAdder}
import java.util.{Base64, UUID}
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

// What a budget looks like from the outside, frozen: the json of the entity, the keys of its counters in the
// datastore, its consumption, the deltas a worker pushes, its alerts, the audit of a call it counts and its views in
// AI Studio. A budget that uses none of the features added after it must keep behaving exactly like this.
class BudgetCompatSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  val (openaiPort, _) = createTestServerWithRoutes("budget-compat-openai", routes => routes
    .post("/v1/chat/completions", (req, response) => req.receive().aggregate().asString().flatMap { _ =>
      response
        .status(200)
        .addHeader("Content-Type", "application/json")
        .sendString(Mono.just(
          """{"id":"chatcmpl-compat","object":"chat.completion","created":1700000000,"model":"compat-audit-model",
            |"choices":[{"index":0,"message":{"role":"assistant","content":"hello"},"finish_reason":"stop"}],
            |"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}""".stripMargin))
        .`then`()
    })
  )

  def ext: AiExtension = otoroshi.env.adminExtensions.extension[AiExtension].get

  val basic: String = Base64.getEncoder.encodeToString("admin-api-apikey-id:admin-api-apikey-secret".getBytes(StandardCharsets.UTF_8))

  def admin(method: String, path: String, body: JsValue = null): WSResponse =
    client.call(method, s"http://otoroshi-api.oto.tools:$port$path", Map("Authorization" -> s"Basic $basic"), Option(body)).awaitf(30.seconds)

  val provider: AiProvider = AiProvider(
    id = s"provider_compat_${UUID.randomUUID()}",
    name = "budget compat",
    provider = "openai",
    connection = Json.obj("base_url" -> s"http://localhost:$openaiPort/v1", "token" -> "sk-test", "timeout" -> 30000),
    options = Json.obj("model" -> "compat-audit-model"),
  )

  // every budget of the suite is scoped on a model of its own, so that each one only counts what its test does
  def budget(id: String, model: String, limits: JsObject, alerts: Boolean = false): JsObject = Json.obj(
    "_loc" -> Json.obj("tenant" -> "default", "teams" -> Json.arr("default")),
    "id" -> id,
    "name" -> s"compat budget $model",
    "description" -> "frozen",
    "metadata" -> Json.obj("k" -> "v"),
    "tags" -> Json.arr("t"),
    "enabled" -> true,
    "start_at" -> "2026-01-01T00:00:00.000Z",
    "end_at" -> "2126-01-01T00:00:00.000Z",
    "duration" -> Json.obj("value" -> 30, "unit" -> "day"),
    "limits" -> limits,
    "scope" -> Json.obj(
      "models" -> Json.arr(model),
      "always_apply_rules" -> true,
      "rules" -> Json.arr(Json.obj("path" -> "$.model", "value" -> model)),
      "rules_match_mode" -> "all",
    ),
    "action_on_exceed" -> Json.obj(
      "mode" -> "block",
      "alert_on_exceed" -> alerts,
      "alert_on_almost_exceed" -> alerts,
      "alert_on_almost_exceed_percentage" -> 80,
      "error_message" -> "frozen message",
    ),
  )

  val countersId = s"budget_compat_counters_${UUID.randomUUID()}"
  val alertsId = s"budget_compat_alerts_${UUID.randomUUID()}"
  val auditId = s"budget_compat_audit_${UUID.randomUUID()}"
  val countersBudget: JsObject = budget(countersId, "compat-model-counters", Json.obj("total_usd" -> 1, "total_tokens" -> 1000, "inference_tokens" -> 500))
  val alertsBudget: JsObject = budget(alertsId, "compat-model-alerts", Json.obj("total_tokens" -> 100), alerts = true)
  val auditBudget: JsObject = budget(auditId, "compat-audit-model", Json.obj("total_tokens" -> 100000))

  val eventsFile: Path = Files.createTempFile("budget-compat-events", ".ndjson")

  override def beforeAll(): Unit = {
    super.beforeAll()
    client.forLlmEntity("providers").upsertEntity(provider).awaitf(10.seconds)
    Seq(countersBudget, alertsBudget, auditBudget).foreach(b => client.forLlmEntity("ai-budgets").createRaw(b).awaitf(10.seconds))
    client.forEntity("events.otoroshi.io", "v1", "data-exporters").upsertEntity(DataExporterConfig(
      enabled = true,
      typ = DataExporterConfigTypeFile,
      id = s"exporter_compat_${UUID.randomUUID()}",
      name = "budget compat events",
      desc = "budget compat events",
      bufferSize = 100,
      jsonWorkers = 1,
      sendWorkers = 1,
      groupSize = 1,
      groupDuration = 500.millis,
      filtering = DataExporterConfigFiltering(include = Seq(
        Json.obj("@type" -> "AlertEvent"),
        Json.obj("@type" -> "AuditEvent", "audit" -> "LLMUsageAudit"),
      )),
      projection = Json.obj(),
      config = FileSettings(eventsFile.toAbsolutePath.toString, None),
    )).awaitf(10.seconds)
    await(10.seconds)
  }

  def events(): Seq[JsObject] = new String(Files.readAllBytes(eventsFile), StandardCharsets.UTF_8)
    .split("\r\n").toSeq.map(_.trim).filter(_.nonEmpty).map(Json.parse(_).as[JsObject])

  // the attributes of a call on the provider of the suite, for one model
  def attrsFor(model: String): TypedMap = {
    val attrs = TypedMap.empty
    attrs.put(ChatClientWithAuding.ProviderKey -> ext.states.provider(provider.id).get)
    attrs.put(ChatClientWithAuding.ModelKey -> model)
    attrs
  }

  def count(model: String, cost: Option[BigDecimal], tokens: Option[Long]): Unit = {
    ext.datastores.budgetsDataStore.updateUsage(cost, tokens, AiBudgetUsageKind.Inference, attrsFor(model)).awaitf(10.seconds)
    // the counters are increased once the consumption is read, without waiting
    await(1.second)
  }

  val usdNames: Seq[String] = Seq("total", "inference", "image", "audio", "video", "embedding", "moderation", "ocr", "decision").map(n => s"${n}_usd")
  val unitNames: Seq[String] = Seq("total", "inference", "image", "audio", "video", "embedding", "moderation").map(n => s"${n}_tokens") ++
    Seq("ocr_pages", "decision_tokens")
  val counterNames: Seq[String] = usdNames ++ unitNames

  // the consumption of a budget with what is not given at zero, and what remains of the limits that are given
  def consumptionJson(consumed: Map[String, BigDecimal], allowed: Map[String, BigDecimal]): JsObject = JsObject(
    counterNames.map(n => s"consumed_$n" -> JsNumber(consumed.getOrElse(n, BigDecimal(0)))) ++
    counterNames.map(n => s"remaining_$n" -> JsNumber(allowed.get(n).map(_ - consumed.getOrElse(n, BigDecimal(0))).getOrElse(BigDecimal(0)))) ++
    counterNames.map(n => s"allowed_$n" -> allowed.get(n).map(v => JsNumber(v)).getOrElse(JsNull))
  )

  test("the json of a budget is unchanged") {
    val read = AiBudget.format.reads(countersBudget).get
    assertEquals(read.json, Json.obj(
      "_loc" -> Json.obj("tenant" -> "default", "teams" -> Json.arr("default")),
      "id" -> countersId,
      "name" -> "compat budget compat-model-counters",
      "description" -> "frozen",
      "metadata" -> Json.obj("k" -> "v"),
      "tags" -> Json.arr("t"),
      "enabled" -> true,
      "start_at" -> "2026-01-01T00:00:00.000Z",
      "end_at" -> "2126-01-01T00:00:00.000Z",
      "duration" -> Json.obj("value" -> 30, "unit" -> "day"),
      "limits" -> JsObject(Seq(
        "total_tokens" -> JsNumber(1000), "total_usd" -> JsNumber(1),
        "inference_tokens" -> JsNumber(500), "inference_usd" -> JsNull,
        "image_tokens" -> JsNull, "image_usd" -> JsNull,
        "audio_tokens" -> JsNull, "audio_usd" -> JsNull,
        "video_tokens" -> JsNull, "video_usd" -> JsNull,
        "embedding_tokens" -> JsNull, "embedding_usd" -> JsNull,
        "moderation_tokens" -> JsNull, "moderation_usd" -> JsNull,
        "ocr_pages" -> JsNull, "ocr_usd" -> JsNull,
        "decision_tokens" -> JsNull, "decision_usd" -> JsNull,
      )),
      "scope" -> Json.obj(
        "extract_from_apikey_meta" -> false,
        "extract_from_apikey_group_meta" -> false,
        "extract_from_user_meta" -> false,
        "extract_from_user_auth_module_meta" -> false,
        "extract_from_provider_meta" -> false,
        "apikeys" -> Json.arr(),
        "users" -> Json.arr(),
        "groups" -> Json.arr(),
        "providers" -> Json.arr(),
        "models" -> Json.arr("compat-model-counters"),
        "always_apply_rules" -> true,
        "rules" -> Json.arr(Json.obj("kind" -> "json-path-validator", "path" -> "$.model", "value" -> "compat-model-counters", "error" -> JsNull)),
        "rules_match_mode" -> "all",
      ),
      "action_on_exceed" -> Json.obj(
        "mode" -> "block",
        "alert_on_exceed" -> false,
        "alert_on_almost_exceed" -> false,
        "alert_on_almost_exceed_percentage" -> 80,
        "error_message" -> "frozen message",
      ),
    ))
    assertEquals(AiBudget.format.reads(read.json).get, read, "a budget reads back from its own json")
  }

  test("a budget counts in the same keys, without expiration, and reads the same consumption") {
    given env: Env = otoroshi.env
    count("compat-model-counters", Some(BigDecimal("0.25")), Some(150L))
    val budget = ext.states.budget(countersId).get
    val prefix = s"${env.storageRoot}:extensions:${AiExtension.id.cleanup}:aibudgets-counter:$countersId:"
    val keys = env.datastores.rawDataStore.keys(s"$prefix*").awaitf(10.seconds).sorted
    assertEquals(keys, Seq("inference-tokens", "inference-usd", "total-tokens", "total-usd").map(c => s"$prefix${budget.cycleId}:$c"))
    keys.foreach(k => assertEquals(env.datastores.rawDataStore.pttl(k).awaitf(10.seconds), -1L, s"$k should not expire"))
    def raw(counter: String): Option[String] = env.datastores.rawDataStore.get(s"$prefix${budget.cycleId}:$counter").awaitf(10.seconds).map(_.utf8String)
    assertEquals((raw("total-usd"), raw("inference-usd"), raw("total-tokens"), raw("inference-tokens")), (Some("250000000"), Some("250000000"), Some("150"), Some("150")), "dollars are counted in billionths")

    val consumed = Map("total_usd" -> BigDecimal("0.25"), "inference_usd" -> BigDecimal("0.25"), "total_tokens" -> BigDecimal(150), "inference_tokens" -> BigDecimal(150))
    val allowed = Map("total_usd" -> BigDecimal(1), "total_tokens" -> BigDecimal(1000), "inference_tokens" -> BigDecimal(500))
    val consumptions = budget.getConsumptions().awaitf(10.seconds)
    assertEquals(consumptions.json, JsObject(counterNames.map(n => s"consumed_$n" -> JsNumber(consumed.getOrElse(n, BigDecimal(0))))))
    assertEquals(consumptions.jsonWithRemaining(budget), consumptionJson(consumed, allowed))

    // the admin api serves the same consumption, and resets it
    val served = admin("GET", s"/api/extensions/cloud-apim/extensions/ai-extension/budgets/$countersId/consumption")
    assertEquals(served.status, 200, served.body)
    assertEquals(served.json, consumptionJson(consumed, allowed))
    val reset = admin("POST", s"/api/extensions/cloud-apim/extensions/ai-extension/budgets/$countersId/consumption/_reset")
    assertEquals((reset.status, reset.json), (200, Json.obj("done" -> true)))
    assertEquals(env.datastores.rawDataStore.keys(s"$prefix*").awaitf(10.seconds), Seq.empty[String])
    val missing = admin("GET", s"/api/extensions/cloud-apim/extensions/ai-extension/budgets/budget_that_does_not_exist/consumption")
    assertEquals((missing.status, missing.json), (404, Json.obj("error" -> "budget not found")))
  }

  test("a worker pushes its deltas in the same shape, and the leader answers the same") {
    val key = s"$countersId:3"
    AiBudgetClusterAgent.totalUsdCounters.getOrElseUpdate(key, new DoubleAdder()).add(0.5)
    AiBudgetClusterAgent.totalTokensCounters.getOrElseUpdate(key, new AtomicLong()).addAndGet(1500L)
    val deltas = AiBudgetClusterAgent.deltas()
    assertEquals(deltas.keys.toSet, counterNames.toSet, "one object by counter, nothing else")
    assertEquals(deltas.select("total_usd").select(key).asOpt[BigDecimal], Some(BigDecimal("0.5")))
    assertEquals(deltas.select("total_tokens").select(key).asOpt[Long], Some(1500L))
    assertEquals(deltas.select("inference_usd").asOpt[JsObject].flatMap(_.value.get(key)), None, "a counter at zero is not pushed")
    // the leader counts it in the cycle it names
    val pushed = Json.obj("total_usd" -> Json.obj(key -> 0.5), "total_tokens" -> Json.obj(key -> 1500))
    val resp = client.call("PUT", s"http://otoroshi-api.oto.tools:$port/api/extensions/cloud-apim/extensions/ai-extension/cluster/budgets/deltas",
      Map("Authorization" -> s"Basic $basic", "Content-Type" -> "application/json"), Some(pushed)).awaitf(30.seconds)
    assertEquals((resp.status, resp.json), (200, Json.obj("done" -> true)))
    AiBudgetClusterAgent.acknowledge(deltas, _ => false)
    assertEquals(AiBudgetClusterAgent.deltas().select("total_usd").asOpt[JsObject].flatMap(_.value.get(key)), None, "what was acknowledged is taken off")
    given env: Env = otoroshi.env
    val budget = ext.states.budget(countersId).get
    assertEquals(env.datastores.rawDataStore.get(budget.counterKey("3", "total-tokens")).awaitf(10.seconds).map(_.utf8String), Some("1500"))
    budget.resetAll()(using otoroshi.executionContext, env).awaitf(10.seconds)
  }

  test("a budget about to be exceeded, then exceeded, raises the same alerts") {
    given env: Env = otoroshi.env
    def check(): Either[String, Unit] = AiBudgetsDataStore.handleWithinBudget[Either[String, Unit]](attrsFor("compat-model-alerts"))(
      message => Future.successful(Left(message)),
      Future.successful(Right(()))
    ).awaitf(10.seconds)
    count("compat-model-alerts", None, Some(90L))
    assertEquals(check(), Right(()), "90 tokens of 100: still within the budget")
    count("compat-model-alerts", None, Some(20L))
    assertEquals(check(), Left("frozen message"), "110 tokens of 100: blocked, with the message of the budget")
    await(5.seconds)
    // what every alert carries, and what the exporter adds about the instance
    val header = Set("@id", "@timestamp", "@type", "@product", "@serviceId", "@service", "@env", "alert", "user-agent-details", "origin-details",
      "instance-number", "instance-name", "instance-zone", "instance-region", "instance-dc", "instance-provider", "instance-rack", "cluster-mode", "cluster-name")
    val alerts = events().filter(_.select("@type").asOpt[String].contains("AlertEvent"))
    val almost = alerts.filter(e => e.select("alert").asOpt[String].contains("AiBudgetAlmostExceeded") && e.select("budget").select("id").asOpt[String].contains(alertsId))
    assert(almost.nonEmpty, s"an AiBudgetAlmostExceeded alert should have been raised: ${alerts}")
    assertEquals(almost.head.keys.toSet -- header, Set("budget", "consumption", "percentages"))
    assertEquals(almost.head.select("budget").as[JsObject], ext.states.budget(alertsId).get.json)
    assertEquals(almost.head.select("consumption").as[JsObject].keys.toSet, counterNames.map(n => s"consumed_$n").toSet)
    assertEquals(almost.head.select("percentages").as[JsObject].keys.toSet, counterNames.toSet)
    assertEquals(almost.head.select("percentages").select("total_tokens").asOpt[Double], Some(90.0))
    val exceeded = alerts.filter(e => e.select("alert").asOpt[String].contains("AiBudgetExceeded") && e.select("budgets").asOpt[Seq[String]].exists(_.contains(alertsId)))
    assert(exceeded.nonEmpty, s"an AiBudgetExceeded alert should have been raised: ${alerts}")
    assertEquals(exceeded.head.keys.toSet -- header, Set("budgets", "apikey", "user", "model", "provider", "route"))
    assertEquals(exceeded.head.select("budgets").as[Seq[String]], Seq(alertsId))
    assertEquals(exceeded.head.select("model").asOpt[String], Some("compat-model-alerts"))
    assertEquals(exceeded.head.select("provider").as[JsObject], Json.obj("id" -> provider.id, "name" -> provider.name))
    assertEquals((exceeded.head.select("apikey").asOpt[JsValue], exceeded.head.select("user").asOpt[JsValue], exceeded.head.select("route").asOpt[JsValue]), (Some(JsNull), Some(JsNull), Some(JsNull)))
  }

  test("the audit of a call lists the budgets that counted it, in the same shape") {
    given env: Env = otoroshi.env
    val chatClient = ext.states.provider(provider.id).get.getChatClient().get
    val result = chatClient.call(ChatPrompt(Seq(ChatMessage.userStrInput("hello"))), TypedMap.empty, Json.obj())(using otoroshi.executionContext, env).awaitf(30.seconds)
    assert(result.isRight, s"the call should succeed: $result")
    await(5.seconds)
    val audits = events().filter(e => e.select("audit").asOpt[String].contains("LLMUsageAudit") && e.select("budgets").asOpt[Seq[JsObject]].exists(_.exists(_.select("budget_id").asOpt[String].contains(auditId))))
    assert(audits.nonEmpty, s"the call should have been audited: ${events()}")
    val counted = audits.head.select("budgets").as[Seq[JsObject]]
    assertEquals(counted.map(_.select("budget_id").asString), Seq(auditId))
    assertEquals(counted.head.keys.toSet, Set("budget_id", "consumption"))
    assertEquals(counted.head.select("consumption").as[JsObject], consumptionJson(Map.empty, Map("total_tokens" -> BigDecimal(100000))), "the consumption before the call")
    await(1.second)
    assertEquals(ext.states.budget(auditId).get.getConsumptions()(using otoroshi.executionContext, env).awaitf(10.seconds).totalTokens, 15L)
  }

  test("the budgets of AI Studio keep their views") {
    def studio(method: String, path: String, body: JsValue = null): WSResponse = admin(method, s"/api/extensions/cloud-apim/extensions/ai-extension/studio$path", body)
    val created = studio("POST", "/workspaces", Json.obj("name" -> "Budget Compat"))
    assertEquals(created.status, 201, created.body)
    val wsId = created.json.select("id").asString
    val saved = studio("POST", s"/workspaces/$wsId/budgets", Json.obj("name" -> "compat", "usd" -> 100, "tokens" -> 1000))
    assertEquals(saved.status, 201, saved.body)
    val viewKeys = Set("id", "name", "description", "enabled", "usd", "tokens", "period", "duration", "mode", "alert", "scope", "apikeys", "users", "models", "rules", "key_limit", "start_at", "end_at")
    assertEquals(saved.json.as[JsObject].keys.toSet, viewKeys)
    val bid = saved.json.select("id").asString
    assertEquals(saved.json.as[JsObject] - "id" - "start_at" - "end_at", Json.obj(
      "name" -> "compat", "description" -> "", "enabled" -> true, "usd" -> 100, "tokens" -> 1000, "period" -> "monthly",
      "duration" -> Json.obj("value" -> 30, "unit" -> "day"), "mode" -> "block", "alert" -> 80, "scope" -> "workspace",
      "apikeys" -> Json.arr(), "users" -> Json.arr(), "models" -> Json.arr(), "rules" -> Json.arr(), "key_limit" -> JsNull,
    ))
    val listed = studio("GET", s"/workspaces/$wsId/budgets?consumption=true")
    assertEquals(listed.status, 200, listed.body)
    val view = listed.json.as[Seq[JsObject]].find(_.select("id").asString == bid).get
    assertEquals(view.keys.toSet, viewKeys + "consumption")
    val consumption = consumptionJson(Map.empty, Map("total_usd" -> BigDecimal(100), "total_tokens" -> BigDecimal(1000)))
    assertEquals(view.select("consumption").as[JsObject], consumption)
    assertEquals(studio("GET", s"/workspaces/$wsId/budgets/$bid").json.as[JsObject].keys.toSet, viewKeys + "consumption")
    assertEquals(studio("GET", s"/workspaces/$wsId/budgets/$bid/consumption").json, consumption)
    val reset = studio("POST", s"/workspaces/$wsId/budgets/$bid/consumption/_reset")
    assertEquals((reset.status, reset.json), (200, Json.obj("done" -> true)))
    assertEquals(studio("DELETE", s"/workspaces/$wsId").status, 204)
  }

  override def afterAll(): Unit = {
    Files.deleteIfExists(eventsFile)
    super.afterAll()
  }
}
