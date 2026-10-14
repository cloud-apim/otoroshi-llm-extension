package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.LlmExtensionOneOtoroshiServerPerSuite
import com.cloud.apim.otoroshi.extensions.aigateway.decorators.{ChatClientWithAuding, ChildCall}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.*
import otoroshi.env.Env
import otoroshi.models.*
import otoroshi.plugins.Keys as OtoroshiKeys
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.*
import play.api.libs.ws.WSResponse
import reactor.core.publisher.Mono

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.{Base64, UUID}
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

// A budget with a `partition_by` counts apart each value its expression takes for a call: each consumer has the whole
// limit for themselves. The expression is one of the otoroshi expression language, and AI Studio counts a person
// (their chats and the calls of the api keys they own) with `${user.email || apikey.metadata.ai_studio_owner}`.
class BudgetPartitionsSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  val (openaiPort, _) = createTestServerWithRoutes("budget-partitions-openai", routes => routes
    .post("/v1/chat/completions", (req, response) => req.receive().aggregate().asString().flatMap { _ =>
      response
        .status(200)
        .addHeader("Content-Type", "application/json")
        .sendString(Mono.just(
          """{"id":"chatcmpl-partitions","object":"chat.completion","created":1700000000,"model":"gpt-4o-mini",
            |"choices":[{"index":0,"message":{"role":"assistant","content":"hello"},"finish_reason":"stop"}],
            |"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}""".stripMargin))
        .`then`()
    })
  )

  def ext: AiExtension = otoroshi.env.adminExtensions.extension[AiExtension].get

  val basic: String = Base64.getEncoder.encodeToString("admin-api-apikey-id:admin-api-apikey-secret".getBytes(StandardCharsets.UTF_8))

  def admin(method: String, path: String, body: JsValue = null, headers: Map[String, String] = Map.empty): WSResponse =
    client.call(method, s"http://otoroshi-api.oto.tools:$port$path", Map("Authorization" -> s"Basic $basic") ++ headers, Option(body)).awaitf(30.seconds)

  def budgetsApi(path: String): String = s"/api/extensions/cloud-apim/extensions/ai-extension/budgets/$path"

  val studioExpression = "${user.email || apikey.metadata.ai_studio_owner}"

  val provider: AiProvider = AiProvider(
    id = s"provider_partitions_${UUID.randomUUID()}",
    name = "budget partitions",
    provider = "openai",
    connection = Json.obj("base_url" -> s"http://localhost:$openaiPort/v1", "token" -> "sk-test", "timeout" -> 30000),
    options = Json.obj("model" -> "gpt-4o-mini"),
  )

  def budget(id: String, model: String, partitionBy: Option[String], tokens: Long = 100L): JsObject = Json.obj(
    "id" -> id,
    "name" -> s"budget $model",
    "description" -> "",
    "enabled" -> true,
    "start_at" -> "2026-01-01T00:00:00.000Z",
    "end_at" -> "2126-01-01T00:00:00.000Z",
    "duration" -> Json.obj("value" -> 30, "unit" -> "day"),
    "limits" -> Json.obj("total_tokens" -> tokens),
    "scope" -> (Json.obj(
      "models" -> Json.arr(model),
      "always_apply_rules" -> true,
      "rules" -> Json.arr(Json.obj("path" -> "$.model", "value" -> model)),
    ) ++ partitionBy.map(p => Json.obj("partition_by" -> p)).getOrElse(Json.obj())),
    "action_on_exceed" -> Json.obj("mode" -> "block", "alert_on_exceed" -> true, "alert_on_almost_exceed" -> true,
      "alert_on_almost_exceed_percentage" -> 80, "error_message" -> "partition blocked"),
  )

  val partitionedId = s"budget_partitioned_${UUID.randomUUID()}"
  val classicId = s"budget_classic_${UUID.randomUUID()}"
  val cleanedId = s"budget_cleaned_${UUID.randomUUID()}"

  val eventsFile: Path = Files.createTempFile("budget-partitions-events", ".ndjson")

  override def beforeAll(): Unit = {
    super.beforeAll()
    client.forLlmEntity("providers").upsertEntity(provider).awaitf(10.seconds)
    Seq(
      budget(partitionedId, "partitioned-model", Some(studioExpression)),
      budget(classicId, "classic-model", None),
      budget(cleanedId, "cleaned-model", None),
    ).foreach(b => client.forLlmEntity("ai-budgets").createRaw(b).awaitf(10.seconds))
    client.forEntity("events.otoroshi.io", "v1", "data-exporters").upsertEntity(DataExporterConfig(
      enabled = true,
      typ = DataExporterConfigTypeFile,
      id = s"exporter_partitions_${UUID.randomUUID()}",
      name = "budget partitions events",
      desc = "budget partitions events",
      bufferSize = 100,
      jsonWorkers = 1,
      sendWorkers = 1,
      groupSize = 1,
      groupDuration = 500.millis,
      filtering = DataExporterConfigFiltering(include = Seq(Json.obj("@type" -> "AlertEvent"))),
      projection = Json.obj(),
      config = FileSettings(eventsFile.toAbsolutePath.toString, None),
    )).awaitf(10.seconds)
    await(10.seconds)
  }

  override def afterAll(): Unit = {
    Files.deleteIfExists(eventsFile)
    super.afterAll()
  }

  def user(email: String): PrivateAppsUser =
    PrivateAppsUser(randomId = s"ai-studio-$email", name = email, email = email, profile = Json.obj(), realm = "ai-studio",
      authConfigId = "ai-studio", otoroshiData = None, tags = Seq.empty, metadata = Map.empty, location = EntityLocation())

  def key(id: String, owner: Option[String], metadata: Map[String, String] = Map.empty): ApiKey =
    ApiKey(clientId = id, clientSecret = "secret", clientName = id, authorizedEntities = Seq.empty,
      metadata = metadata ++ owner.map(o => Map(ApikeyOwner.MetadataKey -> o)).getOrElse(Map.empty))

  def attrsOf(model: String = "partitioned-model", caller: Option[PrivateAppsUser] = None, apikey: Option[ApiKey] = None): TypedMap = {
    val attrs = TypedMap.empty
    attrs.put(ChatClientWithAuding.ProviderKey -> ext.states.provider(provider.id).get)
    attrs.put(ChatClientWithAuding.ModelKey -> model)
    caller.foreach(u => attrs.put(OtoroshiKeys.UserKey -> u))
    apikey.foreach(k => attrs.put(OtoroshiKeys.ApiKeyKey -> k))
    attrs
  }

  def count(attrs: TypedMap, tokens: Long, claims: Option[java.util.Set[String]] = None): Unit = {
    ext.datastores.budgetsDataStore.updateUsage(None, Some(tokens), AiBudgetUsageKind.Inference, attrs, None, claims).awaitf(10.seconds)
    // the counters are increased once the consumption is read, without waiting
    await(1.second)
  }

  def check(attrs: TypedMap): Either[String, Unit] = {
    given env: Env = otoroshi.env
    AiBudgetsDataStore.handleWithinBudget[Either[String, Unit]](attrs)(
      message => Future.successful(Left(message)),
      Future.successful(Right(()))
    ).awaitf(10.seconds)
  }

  def consumed(id: String, partition: Option[String]): Long = {
    given env: Env = otoroshi.env
    ext.states.budget(id).get.getConsumptions(partition).awaitf(10.seconds).totalTokens
  }

  val alice: PrivateAppsUser = user("Alice@Acme.io")
  val bob: PrivateAppsUser = user("bob@acme.io")

  test("the partition of a call is what the expression of the budget finds, in the otoroshi expression language") {
    given env: Env = otoroshi.env
    val entity = ext.states.provider(provider.id)
    def resolve(expression: String, attrs: TypedMap): Option[String] = BudgetPartition.resolve(expression, attrs, entity, Some("partitioned-model"))
    assertEquals(resolve(studioExpression, attrsOf(caller = Some(alice))), Some("alice@acme.io"), "the person chatting, in lower case")
    assertEquals(resolve(studioExpression, attrsOf(apikey = Some(key("key_1", Some("Bob@Acme.io"))))), Some("bob@acme.io"), "the owner of the key")
    assertEquals(resolve(studioExpression, attrsOf(caller = Some(alice), apikey = Some(key("key_1", Some("bob@acme.io"))))), Some("alice@acme.io"), "the person calling, even with the key of another")
    assertEquals(resolve(studioExpression, attrsOf(apikey = Some(key("key_2", None)))), None, "a key without owner has no partition")
    assertEquals(resolve(studioExpression, attrsOf()), None, "nor an anonymous call")
    assertEquals(resolve("${consumer.id}", attrsOf(apikey = Some(key("Key_2", None)))), Some("key_2"), "the consumer of the expression language: the key itself")
    assertEquals(resolve(s"${studioExpression.dropRight(1)} :: shared}", attrsOf(apikey = Some(key("key_2", None)))), Some("shared"), "the default value of a chain")
    assertEquals(resolve("${apikey.metadata.team}", attrsOf(apikey = Some(key("key_3", None)))), None, "a metadata the key does not have")
    assertEquals(resolve("${apikey.metadata.team:none}", attrsOf(apikey = Some(key("key_3", None)))), Some("none"), "its default value")
    assertEquals(resolve("team-${apikey.metadata.team}/${ctx.ai.model}", attrsOf(apikey = Some(key("key_3", None, Map("team" -> "Sales"))))), Some("team-sales/partitioned-model"))
    assertEquals(resolve("${ctx.ai.provider.id}", attrsOf()), Some(provider.id.toLowerCase), "the provider the call counts for")
    assertEquals(resolve("${what.is.this}", attrsOf(caller = Some(alice))), None, "an expression the language does not know")
    val attrs = attrsOf(caller = Some(alice))
    assertEquals(resolve(studioExpression, ChildCall.attrs(attrs)), Some("alice@acme.io"), "a call made on behalf of another counts for the same person")
    resolve("${ctx.ai.model}", attrs)
    assertEquals(attrs.get(OtoroshiKeys.ElCtxKey), None, "the attributes of the call are left untouched")
  }

  test("each consumer has the whole limit, a person sharing it between their chats and the keys they own") {
    given env: Env = otoroshi.env
    count(attrsOf(caller = Some(alice)), 60L)
    count(attrsOf(apikey = Some(key("alice_key", Some("ALICE@acme.io")))), 60L)
    count(attrsOf(caller = Some(bob)), 30L)
    assertEquals((consumed(partitionedId, Some("alice@acme.io")), consumed(partitionedId, Some("bob@acme.io"))), (120L, 30L))
    assertEquals(consumed(partitionedId, None), 0L, "nothing is counted for the budget as a whole")
    assertEquals(check(attrsOf(caller = Some(alice))), Left("partition blocked"), "alice used her 100 tokens")
    assertEquals(check(attrsOf(apikey = Some(key("alice_key", Some("alice@acme.io"))))), Left("partition blocked"), "with her key too")
    assertEquals(check(attrsOf(caller = Some(bob))), Right(()), "bob still has his")
    // a key without owner has no partition: the budget does not apply to its calls
    count(attrsOf(apikey = Some(key("shared_key", None))), 500L)
    assertEquals(check(attrsOf(apikey = Some(key("shared_key", None)))), Right(()))
    // a call handed over to another provider is counted once
    val claims = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()
    count(attrsOf(caller = Some(bob)), 10L, Some(claims))
    count(attrsOf(caller = Some(bob)), 10L, Some(claims))
    assertEquals(consumed(partitionedId, Some("bob@acme.io")), 40L)

    val budget = ext.states.budget(partitionedId).get
    val prefix = s"${AiBudget.countersPrefix}:$partitionedId:${budget.cycleId}"
    val keys = env.datastores.rawDataStore.keys(s"$prefix:*").awaitf(10.seconds).toSet
    assertEquals(keys, Set(
      s"$prefix:partitions",
      s"$prefix:p:${BudgetPartition.segment("alice@acme.io")}:total-tokens",
      s"$prefix:p:${BudgetPartition.segment("alice@acme.io")}:inference-tokens",
      s"$prefix:p:${BudgetPartition.segment("bob@acme.io")}:total-tokens",
      s"$prefix:p:${BudgetPartition.segment("bob@acme.io")}:inference-tokens",
    ))
    keys.foreach(k => assert(env.datastores.rawDataStore.pttl(k).awaitf(10.seconds) > 0L, s"$k should expire"))
    assertEquals(env.datastores.rawDataStore.smembers(s"$prefix:partitions").awaitf(10.seconds).map(_.utf8String).toSet, Set("alice@acme.io", "bob@acme.io"))
  }

  test("the admin api reads, lists and resets the consumers of a budget") {
    val required = admin("GET", budgetsApi(s"$partitionedId/consumption"))
    assertEquals((required.status, required.json.select("error").asOpt[String]), (400, Some("partition_required")))
    val ofAlice = admin("GET", budgetsApi(s"$partitionedId/consumption?partition=ALICE@acme.io"))
    assertEquals(ofAlice.status, 200, ofAlice.body)
    assertEquals(ofAlice.json.select("partition").asOpt[String], Some("alice@acme.io"))
    assertEquals((ofAlice.json.select("consumed_total_tokens").as[Long], ofAlice.json.select("remaining_total_tokens").as[Long], ofAlice.json.select("allowed_total_tokens").as[Long]), (120L, -20L, 100L))
    val notAllowed = admin("GET", budgetsApi(s"$classicId/consumption?partition=alice@acme.io"))
    assertEquals((notAllowed.status, notAllowed.json.select("error").asOpt[String]), (400, Some("partition_not_allowed")))

    val listed = admin("GET", budgetsApi(s"$partitionedId/partitions"))
    assertEquals(listed.status, 200, listed.body)
    assertEquals(listed.json.select("total").as[Int], 2)
    assertEquals(listed.json.select("items").as[Seq[JsObject]].map(i => (i.select("partition").asString, i.select("consumption").select("consumed_total_tokens").as[Long])),
      Seq(("alice@acme.io", 120L), ("bob@acme.io", 40L)), "the ones that consumed most first")
    assertEquals(admin("GET", budgetsApi(s"$partitionedId/partitions?q=BOB")).json.select("items").as[Seq[JsObject]].map(_.select("partition").asString), Seq("bob@acme.io"))
    assertEquals(admin("GET", budgetsApi(s"$partitionedId/partitions?limit=1&offset=1")).json.select("items").as[Seq[JsObject]].map(_.select("partition").asString), Seq("bob@acme.io"))
    assertEquals(admin("GET", budgetsApi(s"$classicId/partitions")).status, 400)

    val resetAlice = admin("POST", budgetsApi(s"$partitionedId/consumption/_reset?partition=alice@acme.io"))
    assertEquals((resetAlice.status, resetAlice.json), (200, Json.obj("done" -> true)))
    assertEquals((consumed(partitionedId, Some("alice@acme.io")), consumed(partitionedId, Some("bob@acme.io"))), (0L, 40L), "only alice starts over")
    assertEquals(admin("GET", budgetsApi(s"$partitionedId/partitions")).json.select("items").as[Seq[JsObject]].map(_.select("partition").asString), Seq("bob@acme.io"), "a partition without counters is not listed")
    assertEquals(check(attrsOf(caller = Some(alice))), Right(()))
    assertEquals(admin("POST", budgetsApi(s"$classicId/consumption/_reset?partition=alice@acme.io")).status, 400)
    assertEquals(admin("POST", budgetsApi(s"$partitionedId/consumption/_reset")).status, 200)
    assertEquals(consumed(partitionedId, Some("bob@acme.io")), 0L, "the reset of the cycle resets every partition")
    assertEquals(admin("GET", budgetsApi(s"$partitionedId/partitions")).json.select("total").as[Int], 0)
  }

  test("the alerts of a budget counted by consumer name the consumer") {
    count(attrsOf(caller = Some(alice)), 90L)
    assertEquals(check(attrsOf(caller = Some(alice))), Right(()))
    count(attrsOf(caller = Some(alice)), 20L)
    assertEquals(check(attrsOf(caller = Some(alice))), Left("partition blocked"))
    await(5.seconds)
    val alerts = new String(Files.readAllBytes(eventsFile), StandardCharsets.UTF_8).split("\r\n").toSeq.map(_.trim).filter(_.nonEmpty).map(Json.parse(_).as[JsObject])
    val almost = alerts.filter(e => e.select("alert").asOpt[String].contains("AiBudgetAlmostExceeded") && e.select("budget").select("id").asOpt[String].contains(partitionedId))
    assert(almost.nonEmpty, s"an AiBudgetAlmostExceeded alert should have been raised: $alerts")
    assertEquals(almost.head.select("partition").asOpt[String], Some("alice@acme.io"))
    assertEquals(almost.head.select("consumption").select("partition").asOpt[String], Some("alice@acme.io"))
    val exceeded = alerts.filter(e => e.select("alert").asOpt[String].contains("AiBudgetExceeded") && e.select("budgets").asOpt[Seq[String]].exists(_.contains(partitionedId)))
    assert(exceeded.nonEmpty, s"an AiBudgetExceeded alert should have been raised: $alerts")
    assertEquals(exceeded.head.select("partitions").asOpt[JsObject], Some(Json.obj(partitionedId -> "alice@acme.io")))
    admin("POST", budgetsApi(s"$partitionedId/consumption/_reset"))
  }

  test("a chat of AI Studio counts in the partition of the person it is made on behalf of") {
    def studio(method: String, path: String, body: JsValue = null, headers: Map[String, String] = Map.empty): WSResponse =
      admin(method, s"/api/extensions/cloud-apim/extensions/ai-extension/studio$path", body, headers)
    val ws = studio("POST", "/workspaces", Json.obj("name" -> "Budget Partitions"))
    assertEquals(ws.status, 201, ws.body)
    val wsId = ws.json.select("id").asString
    val created = studio("POST", s"/workspaces/$wsId/providers", Json.obj("kind" -> "openai", "token" -> "sk-test", "base_url" -> s"http://localhost:$openaiPort/v1",
      "modalities" -> Json.obj("text" -> Json.obj("model" -> "gpt-4o-mini"))))
    assertEquals(created.status, 201, created.body)
    val budgetId = s"budget_studio_${UUID.randomUUID()}"
    client.forLlmEntity("ai-budgets").createRaw(Json.obj(
      "id" -> budgetId,
      "name" -> "by consumer",
      "description" -> "",
      "enabled" -> true,
      "duration" -> Json.obj("value" -> 30, "unit" -> "day"),
      "limits" -> Json.obj("total_tokens" -> 1000),
      "scope" -> Json.obj(
        "always_apply_rules" -> true,
        "rules" -> Json.arr(Json.obj("path" -> "$.provider.metadata.ai_studio_workspace", "value" -> wsId)),
        "partition_by" -> studioExpression,
      ),
      "action_on_exceed" -> Json.obj("mode" -> "block", "alert_on_exceed" -> false, "alert_on_almost_exceed" -> false),
    )).awaitf(10.seconds)
    await(3.seconds)
    val chat = Json.obj("model" -> "gpt-4o-mini", "messages" -> Json.arr(Json.obj("role" -> "user", "content" -> "hello")))
    val answer = studio("POST", s"/workspaces/$wsId/proxy/chat/completions", chat, Map("AI-Studio-User-Email" -> "carol@acme.io"))
    assertEquals(answer.status, 200, answer.body)
    await(2.seconds)
    assertEquals(consumed(budgetId, Some("carol@acme.io")), 15L)
    assertEquals(studio("DELETE", s"/workspaces/$wsId").status, 204)
  }

  test("the counters are cleaned: cycles over removed, expirations set, the counters of a budget gone removed") {
    given env: Env = otoroshi.env
    val budget = ext.states.budget(cleanedId).get
    val over = budget.counterKey("0", "total-tokens")
    val current = budget.counterKey(budget.cycleId, "total-tokens")
    val gone = s"${AiBudget.countersPrefix}:budget_gone_${UUID.randomUUID()}:0:total-tokens"
    Seq(over, current, gone).foreach(k => env.datastores.rawDataStore.incrby(k, 5L).awaitf(10.seconds))
    assertEquals(env.datastores.rawDataStore.pttl(current).awaitf(10.seconds), -1L, "a counter written before they expired")
    AiBudgetCountersCleaner.run(ext).awaitf(30.seconds)
    def exists(key: String): Boolean = env.datastores.rawDataStore.get(key).awaitf(10.seconds).isDefined
    assert(!exists(over), "the cycle 0 ended in january, more than 7 days ago")
    assert(exists(current), "the current cycle is kept")
    assert(env.datastores.rawDataStore.pttl(current).awaitf(10.seconds) > 0L, "and expires with its cycle")
    assert(exists(gone), "a budget gone is only found gone once")
    AiBudgetCountersCleaner.run(ext).awaitf(30.seconds)
    assert(!exists(gone), "found gone twice, its counters are removed")
    assert(exists(current))
  }
}
