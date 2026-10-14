package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.LlmExtensionOneOtoroshiServerPerSuite
import com.cloud.apim.otoroshi.extensions.aigateway.decorators.ChatClientWithAuding
import com.cloud.apim.otoroshi.extensions.aigateway.entities.*
import otoroshi.env.Env
import otoroshi.models.*
import otoroshi.plugins.Keys as OtoroshiKeys
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.*
import play.api.libs.ws.WSResponse

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.{Base64, UUID}
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

// An administrator lets the people a budget counts extend their limit: what an extension adds and how many can be
// taken in a cycle. Michel used his 200 tokens of the month and must finish something: he takes his one extension of
// 10 tokens, and is blocked again past 210. The consumption stays the one that was counted.
class BudgetExtensionsSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  def ext: AiExtension = otoroshi.env.adminExtensions.extension[AiExtension].get

  val basic: String = Base64.getEncoder.encodeToString("admin-api-apikey-id:admin-api-apikey-secret".getBytes(StandardCharsets.UTF_8))

  def admin(method: String, path: String): WSResponse =
    client.call(method, s"http://otoroshi-api.oto.tools:$port/api/extensions/cloud-apim/extensions/ai-extension/budgets/$path", Map("Authorization" -> s"Basic $basic"), None).awaitf(30.seconds)

  def extend(id: String, partition: Option[String]): WSResponse = admin("POST", s"$id/consumption/_extend${partition.map(p => s"?partition=$p").getOrElse("")}")

  def consumption(id: String, partition: Option[String]): JsValue = admin("GET", s"$id/consumption${partition.map(p => s"?partition=$p").getOrElse("")}").json

  val studioExpression = "${user.email || apikey.metadata.ai_studio_owner}"

  val provider: AiProvider = AiProvider(
    id = s"provider_extensions_${UUID.randomUUID()}",
    name = "budget extensions",
    provider = "openai",
    connection = Json.obj("base_url" -> "http://localhost:1/v1", "token" -> "sk-test", "timeout" -> 30000),
    options = Json.obj("model" -> "gpt-4o-mini"),
  )

  def budget(id: String, model: String, tokens: Long, partitioned: Boolean, extensions: Option[JsObject]): JsObject = Json.obj(
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
    ) ++ (if (partitioned) Json.obj("partition_by" -> studioExpression) else Json.obj())),
    "action_on_exceed" -> Json.obj("mode" -> "block", "alert_on_exceed" -> false, "alert_on_almost_exceed" -> false, "error_message" -> "out of budget"),
  ) ++ extensions.map(e => Json.obj("extensions" -> e)).getOrElse(Json.obj())

  def allowing(max: Int, limits: JsObject): JsObject = Json.obj("enabled" -> true, "max_per_cycle" -> max, "limits" -> limits)

  val michelBudget: JsObject = budget(s"budget_michel_${UUID.randomUUID()}", "ext-michel", 200L, partitioned = true, Some(allowing(1, Json.obj("total_tokens" -> 10))))
  val twiceBudget: JsObject = budget(s"budget_twice_${UUID.randomUUID()}", "ext-twice", 100L, partitioned = true, Some(allowing(2, Json.obj("total_tokens" -> 10))))
  // `total_usd` is not a limit of this budget: an extension does not add to it
  val wholeBudget: JsObject = budget(s"budget_whole_${UUID.randomUUID()}", "ext-whole", 100L, partitioned = false, Some(allowing(1, Json.obj("total_tokens" -> 10, "total_usd" -> 5))))
  val disabledBudget: JsObject = budget(s"budget_disabled_${UUID.randomUUID()}", "ext-disabled", 100L, partitioned = true, Some(Json.obj("enabled" -> false, "limits" -> Json.obj("total_tokens" -> 10))))
  val noneBudget: JsObject = budget(s"budget_none_${UUID.randomUUID()}", "ext-none", 100L, partitioned = true, None)

  def idOf(budget: JsObject): String = budget.select("id").asString

  val eventsFile: Path = Files.createTempFile("budget-extensions-events", ".ndjson")

  override def beforeAll(): Unit = {
    super.beforeAll()
    client.forLlmEntity("providers").upsertEntity(provider).awaitf(10.seconds)
    Seq(michelBudget, twiceBudget, wholeBudget, disabledBudget, noneBudget).foreach(b => client.forLlmEntity("ai-budgets").createRaw(b).awaitf(10.seconds))
    client.forEntity("events.otoroshi.io", "v1", "data-exporters").upsertEntity(DataExporterConfig(
      enabled = true,
      typ = DataExporterConfigTypeFile,
      id = s"exporter_extensions_${UUID.randomUUID()}",
      name = "budget extensions events",
      desc = "budget extensions events",
      bufferSize = 100,
      jsonWorkers = 1,
      sendWorkers = 1,
      groupSize = 1,
      groupDuration = 500.millis,
      filtering = DataExporterConfigFiltering(include = Seq(
        Json.obj("@type" -> "AuditEvent", "audit" -> "AiBudgetExtended"),
        Json.obj("@type" -> "AuditEvent", "audit" -> "AiBudgetExtensionCancelled"),
      )),
      projection = Json.obj(),
      config = FileSettings(eventsFile.toAbsolutePath.toString, None),
    )).awaitf(10.seconds)
    await(10.seconds)
  }

  override def afterAll(): Unit = {
    Files.deleteIfExists(eventsFile)
    super.afterAll()
  }

  def person(email: String): PrivateAppsUser =
    PrivateAppsUser(randomId = s"ai-studio-$email", name = email, email = email, profile = Json.obj(), realm = "ai-studio",
      authConfigId = "ai-studio", otoroshiData = None, tags = Seq.empty, metadata = Map.empty, location = EntityLocation())

  def attrsOf(model: String, email: Option[String]): TypedMap = {
    val attrs = TypedMap.empty
    attrs.put(ChatClientWithAuding.ProviderKey -> ext.states.provider(provider.id).get)
    attrs.put(ChatClientWithAuding.ModelKey -> model)
    email.foreach(e => attrs.put(OtoroshiKeys.UserKey -> person(e)))
    attrs
  }

  def count(model: String, email: Option[String], tokens: Long): Unit = {
    ext.datastores.budgetsDataStore.updateUsage(None, Some(tokens), AiBudgetUsageKind.Inference, attrsOf(model, email)).awaitf(10.seconds)
    await(1.second)
  }

  def check(model: String, email: Option[String]): Either[String, Unit] = {
    given env: Env = otoroshi.env
    AiBudgetsDataStore.handleWithinBudget[Either[String, Unit]](attrsOf(model, email))(
      message => Future.successful(Left(message)),
      Future.successful(Right(()))
    ).awaitf(10.seconds)
  }

  def tokens(c: JsValue): (Long, Long, Long) =
    (c.select("consumed_total_tokens").as[Long], c.select("allowed_total_tokens").as[Long], c.select("remaining_total_tokens").as[Long])

  test("michel takes his one extension of the month, and is blocked again past it") {
    val id = idOf(michelBudget)
    count("ext-michel", Some("michel@acme.io"), 200L)
    assertEquals(check("ext-michel", Some("michel@acme.io")), Left("out of budget"))
    val taken = extend(id, Some("Michel@acme.io"))
    assertEquals(taken.status, 200, taken.body)
    val after = taken.json.select("consumption").as[JsValue]
    assertEquals(tokens(after), (200L, 210L, 10L), "the consumption is the one counted, the limit is extended")
    assertEquals((after.select("extra_total_tokens").asOpt[Long], after.select("extensions_taken").asOpt[Long], after.select("extensions_max").asOpt[Int]), (Some(10L), Some(1L), Some(1)))
    assertEquals(after.select("extension").asOpt[JsObject], Some(Json.obj("total_tokens" -> 10)))
    assertEquals(after.select("partition").asOpt[String], Some("michel@acme.io"))
    assertEquals(check("ext-michel", Some("michel@acme.io")), Right(()), "he can finish his work")
    count("ext-michel", Some("michel@acme.io"), 10L)
    assertEquals(check("ext-michel", Some("michel@acme.io")), Left("out of budget"), "but no more")
    val again = extend(id, Some("michel@acme.io"))
    assertEquals((again.status, again.json.select("error").asOpt[String], again.json.select("extensions_max").asOpt[Int]), (409, Some("extensions_exhausted"), Some(1)))

    // the others keep their own limit, and their own extension
    count("ext-michel", Some("lea@acme.io"), 50L)
    val lea = consumption(id, Some("lea@acme.io"))
    assertEquals(tokens(lea), (50L, 200L, 150L))
    assertEquals((lea.select("extra_total_tokens").asOpt[Long], lea.select("extensions_taken").asOpt[Long], lea.select("extensions_max").asOpt[Int]), (None, None, Some(1)), "what she could take, nothing she took")
    val nobody = extend(id, Some("nobody@acme.io"))
    assertEquals((nobody.status, nobody.json.select("error").asOpt[String]), (409, Some("no_consumption")), "a person this budget never counted")
    assertEquals(extend(id, None).json.select("error").asOpt[String], Some("partition_required"))
  }

  test("extensions add up, to the number allowed in a cycle, even when taken at the same time") {
    given env: Env = otoroshi.env
    val id = idOf(twiceBudget)
    count("ext-twice", Some("julie@acme.io"), 100L)
    assertEquals(extend(id, Some("julie@acme.io")).status, 200)
    // the amount of the next extensions changes, the ones taken stay
    client.forLlmEntity("ai-budgets").call("PUT", Some(id), Some(twiceBudget ++ Json.obj("extensions" -> allowing(2, Json.obj("total_tokens" -> 25))))).awaitf(10.seconds)
    await(3.seconds)
    val second = extend(id, Some("julie@acme.io"))
    assertEquals(second.status, 200, second.body)
    assertEquals((second.json.select("consumption").select("extra_total_tokens").asOpt[Long], second.json.select("consumption").select("allowed_total_tokens").asOpt[Long]), (Some(35L), Some(135L)))
    assertEquals(extend(id, Some("julie@acme.io")).status, 409, "two in a cycle")

    count("ext-twice", Some("paul@acme.io"), 100L)
    assertEquals(extend(id, Some("paul@acme.io")).status, 200)
    val racing = Future.sequence(Seq(1, 2).map(_ => client.call("POST",
      s"http://otoroshi-api.oto.tools:$port/api/extensions/cloud-apim/extensions/ai-extension/budgets/$id/consumption/_extend?partition=paul@acme.io",
      Map("Authorization" -> s"Basic $basic"), None))).awaitf(30.seconds)
    assertEquals(racing.map(_.status).sorted, Seq(200, 409), "one of the two takes the last extension")
    assertEquals(ext.states.budget(id).get.getConsumptions(Some("paul@acme.io")).awaitf(10.seconds).extras, Map("total_tokens" -> BigDecimal(50)))

    // once they are not allowed anymore, the ones taken stay
    client.forLlmEntity("ai-budgets").call("PUT", Some(id), Some(twiceBudget ++ Json.obj("extensions" -> Json.obj("enabled" -> false, "max_per_cycle" -> 2, "limits" -> Json.obj("total_tokens" -> 25))))).awaitf(10.seconds)
    await(3.seconds)
    assertEquals(tokens(consumption(id, Some("julie@acme.io"))), (100L, 135L, 35L))
    count("ext-twice", Some("anna@acme.io"), 10L)
    assertEquals(extend(id, Some("anna@acme.io")).json.select("error").asOpt[String], Some("extensions_disabled"))
  }

  test("cancelling removes what extensions added without giving them back, a reset starts over") {
    val id = idOf(michelBudget)
    val cancelled = admin("DELETE", s"$id/consumption/_extend?partition=michel@acme.io")
    assertEquals((cancelled.status, cancelled.json), (200, Json.obj("done" -> true)))
    val after = consumption(id, Some("michel@acme.io"))
    assertEquals(tokens(after), (210L, 200L, -10L))
    assertEquals((after.select("extra_total_tokens").asOpt[Long], after.select("extensions_taken").asOpt[Long]), (None, Some(1L)), "the extension is still taken")
    assertEquals(extend(id, Some("michel@acme.io")).status, 409, "cancelling does not give it back")
    assertEquals(admin("DELETE", s"$id/consumption/_extend").json.select("error").asOpt[String], Some("partition_required"))
    assertEquals(admin("POST", s"$id/consumption/_reset?partition=michel@acme.io").status, 200)
    val reset = consumption(id, Some("michel@acme.io"))
    assertEquals((tokens(reset), reset.select("extensions_taken").asOpt[Long]), ((0L, 200L, 200L), None), "the reset starts over, the extensions taken included")
    count("ext-michel", Some("michel@acme.io"), 5L)
    assertEquals(extend(id, Some("michel@acme.io")).status, 200)
  }

  test("a budget counted as a whole is extended by its administrators, on the limits it has") {
    val id = idOf(wholeBudget)
    count("ext-whole", None, 100L)
    assertEquals(check("ext-whole", None), Left("out of budget"))
    val taken = extend(id, None)
    assertEquals(taken.status, 200, taken.body)
    assertEquals(tokens(taken.json.select("consumption").as[JsValue]), (100L, 110L, 10L))
    assertEquals(taken.json.select("consumption").select("extension").asOpt[JsObject], Some(Json.obj("total_tokens" -> 10)), "nothing for a limit the budget does not have")
    assertEquals(taken.json.select("consumption").select("extra_total_usd").asOpt[BigDecimal], None)
    assertEquals(check("ext-whole", None), Right(()))
    assertEquals(extend(id, Some("someone@acme.io")).json.select("error").asOpt[String], Some("partition_not_allowed"))
  }

  test("a budget that allows no extension refuses them, and reads what it is given") {
    count("ext-disabled", Some("zoe@acme.io"), 10L)
    count("ext-none", Some("zoe@acme.io"), 10L)
    Seq(idOf(disabledBudget), idOf(noneBudget)).foreach { id =>
      val refused = extend(id, Some("zoe@acme.io"))
      assertEquals((refused.status, refused.json.select("error").asOpt[String]), (409, Some("extensions_disabled")), id)
    }
    assertEquals(ext.states.budget(idOf(noneBudget)).get.json.asObject.keys.contains("extensions"), false, "a budget without extensions has none in its json")
    assertEquals(consumption(idOf(noneBudget), Some("zoe@acme.io")).asObject.keys.filter(k => k.startsWith("extra") || k.startsWith("extension")), Set.empty[String])
    val read = AiBudget.format.reads(budget("budget_read", "ext-read", 100L, partitioned = false, Some(Json.obj("enabled" -> true, "max_per_cycle" -> 0, "limits" -> Json.obj("image_tokens" -> 5, "total_tokens" -> -3, "something" -> 2))))).get
    assertEquals(read.extensions, Some(AiBudgetExtensions(enabled = true, maxPerCycle = 1, limits = Map("image_tokens" -> BigDecimal(5)))))
    assertEquals(read.extensionAmounts, None, "image tokens are not a limit of the budget")
  }

  test("extensions taken and cancelled are audited, with who they are for and who took them") {
    await(5.seconds)
    val audits = new String(Files.readAllBytes(eventsFile), StandardCharsets.UTF_8).split("\r\n").toSeq.map(_.trim).filter(_.nonEmpty).map(Json.parse(_).as[JsObject])
    val michel = audits.filter(_.select("budget").select("id").asOpt[String].contains(idOf(michelBudget)))
    val extended = michel.filter(_.select("audit").asOpt[String].contains("AiBudgetExtended"))
    assert(extended.nonEmpty, s"the extensions should have been audited: $audits")
    val first = extended.head
    assertEquals(first.select("partition").asOpt[String], Some("michel@acme.io"))
    assertEquals(first.select("added").asOpt[JsObject], Some(Json.obj("total_tokens" -> 10)))
    assertEquals(first.select("extras").asOpt[JsObject], Some(Json.obj("total_tokens" -> 10)))
    assertEquals((first.select("extensions_taken").asOpt[Long], first.select("extensions_max").asOpt[Int]), (Some(1L), Some(1)))
    assertEquals(first.select("by").select("kind").asOpt[String], Some("apikey"))
    assertEquals(first.select("by").select("client_id").asOpt[String], Some("admin-api-apikey-id"))
    val cancelled = michel.filter(_.select("audit").asOpt[String].contains("AiBudgetExtensionCancelled"))
    assertEquals(cancelled.map(_.select("partition").asOpt[String]), Seq(Some("michel@acme.io")))
    assertEquals(cancelled.head.select("added").asOpt[JsValue], Some(JsNull))
  }
}
