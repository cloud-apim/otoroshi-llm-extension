package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.decorators.ChatClientWithCostsTracking
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{DecisionModel, ModelSettings}
import com.cloud.apim.otoroshi.extensions.aigateway.{DecisionErrors, DecisionModelClientInputOptions, DecisionResponse, LlmExtensionOneOtoroshiServerPerSuite}
import otoroshi.env.Env
import otoroshi.models.EntityLocation
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.DecisionModels
import play.api.libs.json.*
import reactor.core.publisher.Mono
import reactor.netty.http.server.HttpServerResponse

import java.util.UUID
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.duration.DurationInt

// A decision model that cannot answer hands the call over to another one, on technical failures only, and the
// call is priced and counted against budgets once: by the model that served it.
class DecisionModelsResilienceSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  val jev = "jev-1.13.0"
  val inputTokens = 350L
  val outputTokens = 58L
  val expectedCost = BigDecimal(inputTokens) * BigDecimal("0.000000042")

  val request: JsObject = Json.obj(
    "state" -> "The checkout has been failing for every customer for the last hour.",
    "questions" -> Json.obj("urgent" -> Json.obj("type" -> "noul", "instructions" -> "Is this support request urgent ?")),
  )

  private def send(response: HttpServerResponse, status: Int, body: JsValue) =
    response.status(status).addHeader("Content-Type", "application/json").sendString(Mono.just(body.stringify)).`then`()

  // an overloaded provider, under three base urls to tell who was called
  val overloaded = Json.obj("detail" -> Json.obj("error_type" -> "overloaded_error", "message" -> "overloaded"))
  val primaryCalls = new AtomicInteger(0)
  val aCalls = new AtomicInteger(0)
  val bCalls = new AtomicInteger(0)
  val (failingPort, _) = createTestServerWithRoutes("systemone-overloaded", routes => routes
    .post("/primary/systemone", (req, response) => req.receive().aggregate().asString().flatMap { _ => primaryCalls.incrementAndGet(); send(response, 529, overloaded) })
    .post("/a/systemone", (req, response) => req.receive().aggregate().asString().flatMap { _ => aCalls.incrementAndGet(); send(response, 529, overloaded) })
    .post("/b/systemone", (req, response) => req.receive().aggregate().asString().flatMap { _ => bCalls.incrementAndGet(); send(response, 529, overloaded) })
  )

  val (invalidPort, _) = createTestServerWithRoutes("systemone-invalid", routes => routes
    .post("/v1/systemone", (req, response) => req.receive().aggregate().asString().flatMap { _ =>
      send(response, 422, Json.obj("detail" -> Json.arr(Json.obj("type" -> "too_long", "loc" -> Json.arr("body", "questions"), "msg" -> "too many questions"))))
    })
  )

  val fallbackCalls = new AtomicInteger(0)
  val fallbackBody = new AtomicReference[JsValue](JsNull)
  val (fallbackPort, _) = createTestServerWithRoutes("systemone-fallback", routes => routes
    .post("/v1/systemone", (req, response) => req.receive().aggregate().asString().flatMap { body =>
      fallbackCalls.incrementAndGet()
      fallbackBody.set(Json.parse(body))
      send(response, 200, Json.obj(
        "model" -> Json.parse(body).select("model").asString,
        "answers" -> Json.obj("urgent" -> Json.obj("type" -> "noul", "noul" -> 0.95)),
        "usage" -> Json.obj("input_tokens" -> inputTokens, "output_tokens" -> outputTokens),
      ))
    })
  )

  val unreachablePort: Int = freePort

  def ext: AiExtension = otoroshi.env.adminExtensions.extension[AiExtension].get

  def decisionModel(name: String, baseUrl: String, model: String, fallback: Option[String] = None, fallbackModel: Option[String] = None, models: ModelSettings = ModelSettings.empty, id: String = s"decision-model_${UUID.randomUUID()}"): DecisionModel = DecisionModel(
    EntityLocation.default, id, name, "", Seq.empty, Map.empty, "typesafe",
    Json.obj("connection" -> Json.obj("base_url" -> baseUrl, "token" -> "xxx", "timeout" -> 5000), "options" -> Json.obj("model" -> model)),
    models = models, fallbackRef = fallback, fallbackModel = fallbackModel,
  )

  val fallbackUrl = s"http://localhost:${fallbackPort}/v1"
  val aId = s"decision-model_${UUID.randomUUID()}"
  val bId = s"decision-model_${UUID.randomUUID()}"

  lazy val fallback = decisionModel("fallback", fallbackUrl, jev)
  lazy val primary = decisionModel("primary", s"http://localhost:${failingPort}/primary", "primary-model", fallback.id.some)
  lazy val invalid = decisionModel("invalid", s"http://localhost:${invalidPort}/v1", jev, fallback.id.some)
  lazy val unreachable = decisionModel("unreachable", s"http://localhost:${unreachablePort}/v1", jev, fallback.id.some, "jev-latest".some)
  lazy val cycleA = decisionModel("cycle a", s"http://localhost:${failingPort}/a", jev, bId.some, id = aId)
  lazy val cycleB = decisionModel("cycle b", s"http://localhost:${failingPort}/b", jev, aId.some, id = bId)
  lazy val budgeted = decisionModel("budgeted", s"http://localhost:${failingPort}/primary", "primary-model", fallback.id.some)
  lazy val blocked = decisionModel("blocked", fallbackUrl, jev)
  lazy val restricted = decisionModel("restricted", fallbackUrl, jev, models = ModelSettings(exclude = Seq(".*")))
  lazy val strict = decisionModel("strict", fallbackUrl, "a-model-without-a-price", models = ModelSettings(requireKnownCosts = true))

  val budgetName = "decisions budget"
  val primaryBudgetName = "budget of the model that was asked"

  def budget(name: String, providers: Seq[String], limits: JsObject, mode: String): JsObject = Json.obj(
    "id" -> s"budget_${UUID.randomUUID()}",
    "name" -> name,
    "description" -> name,
    "enabled" -> true,
    "duration" -> Json.obj("value" -> 1, "unit" -> "year"),
    "limits" -> limits,
    "scope" -> Json.obj("providers" -> JsArray(providers.map(JsString.apply))),
    "action_on_exceed" -> Json.obj("mode" -> mode, "alert_on_exceed" -> false, "alert_on_almost_exceed" -> false),
  )

  lazy val setup: Unit = {
    Seq(fallback, primary, invalid, unreachable, cycleA, cycleB, budgeted, blocked, restricted, strict).foreach { m =>
      assert(client.forLlmEntity("decision-models").upsertEntity(m).awaitf(10.seconds).createdOrUpdated, s"${m.name} should be saved")
    }
    // both the model that fails and the one taking over are in the scope: the call must count once all the same
    client.forLlmEntity("ai-budgets").createRaw(budget(budgetName, Seq(budgeted.id, fallback.id), Json.obj("total_usd" -> 10, "decision_usd" -> 10), "soft")).awaitf(10.seconds)
    // the model that fails alone: its budget is the budget of what it was asked, whoever answered
    client.forLlmEntity("ai-budgets").createRaw(budget(primaryBudgetName, Seq(budgeted.id), Json.obj("total_usd" -> 10, "decision_usd" -> 10), "soft")).awaitf(10.seconds)
    // a budget limiting decisions and nothing else, already spent
    client.forLlmEntity("ai-budgets").createRaw(budget("no decision left", Seq(blocked.id), Json.obj("decision_tokens" -> 0), "block")).awaitf(10.seconds)
    await(10.seconds)
  }

  def decide(model: DecisionModel, attrs: TypedMap = TypedMap.empty): Either[JsValue, DecisionResponse] = {
    given env: Env = otoroshi.env
    ext.states.decisionModel(model.id).flatMap(_.getDecisionModelClient()).get
      .decide(DecisionModelClientInputOptions.format.reads(request).get, request, attrs)(using ec, otoroshi.env)
      .awaitf(30.seconds)
  }

  test("an overloaded model hands the call over, with the model of the fallback") {
    setup
    val calls = fallbackCalls.get()
    val resp = decide(primary)
    assert(resp.isRight, s"the fallback should have answered, got ${resp}")
    assertEquals(fallbackCalls.get() - calls, 1)
    // a model id means nothing from a provider to another: the fallback is asked for its own
    assertEquals(fallbackBody.get().select("model").asString, jev)
    assertEquals(resp.toOption.get.model, jev)
    assertEquals(fallbackBody.get().select("questions").asOpt[JsValue], request.select("questions").asOpt[JsValue])
  }

  test("a request the provider refused is not asked to another model") {
    setup
    val calls = fallbackCalls.get()
    val resp = decide(invalid)
    assertEquals(resp.left.toOption.map(e => DecisionErrors.classify(e)).collect { case DecisionErrors.Kind.Upstream(status, _, _) => status }, Some(422))
    assertEquals(fallbackCalls.get(), calls, "a 422 says the request is wrong, it would be wrong anywhere")
  }

  test("a model that cannot be reached hands the call over too, with the model set for the fallback") {
    setup
    val resp = decide(unreachable)
    assert(resp.isRight, s"the fallback should have answered, got ${resp}")
    assertEquals(fallbackBody.get().select("model").asString, "jev-latest")
  }

  test("two models falling back on each other are tried once each") {
    setup
    val resp = decide(cycleA)
    assert(resp.left.toOption.exists(DecisionErrors.retryable), s"the last error should come back, got ${resp}")
    assertEquals(aCalls.get(), 1)
    assertEquals(bCalls.get(), 1)
  }

  test("a call served by a fallback is priced and counted against budgets once, at the price of who served it") {
    setup
    given env: Env = otoroshi.env
    val budget = ext.states.allBudgets().find(_.name == budgetName).get
    val primaryBudget = ext.states.allBudgets().find(_.name == primaryBudgetName).get
    val before = budget.getConsumptions().awaitf(10.seconds)
    val primaryBefore = primaryBudget.getConsumptions().awaitf(10.seconds)
    val attrs = TypedMap.empty
    val resp = decide(budgeted, attrs)
    assert(resp.isRight, s"the fallback should have answered, got ${resp}")
    assertEquals(attrs.get(ChatClientWithCostsTracking.key).map(_.totalCost), expectedCost.some)
    await(5.seconds)
    val after = budget.getConsumptions().awaitf(10.seconds)
    assertEquals(after.decisionUsd - before.decisionUsd, expectedCost, "the decision budget should have moved by the cost of one call")
    assertEquals(after.totalUsd - before.totalUsd, expectedCost)
    assertEquals(after.decisionTokens - before.decisionTokens, inputTokens + outputTokens, "and by the tokens of one call")
    assertEquals(after.totalTokens - before.totalTokens, inputTokens + outputTokens)
    val primaryAfter = primaryBudget.getConsumptions().awaitf(10.seconds)
    assertEquals(primaryAfter.decisionUsd - primaryBefore.decisionUsd, expectedCost, "the budget of the model that was asked counts the call too")
    assertEquals(primaryAfter.decisionTokens - primaryBefore.decisionTokens, inputTokens + outputTokens)
  }

  test("a budget, a model restriction and an unknown price are refused before the provider, each with its status") {
    setup
    val calls = fallbackCalls.get()
    // a budget limiting nothing but decisions does block them
    val overBudget = decide(blocked)
    assert(overBudget.left.toOption.exists(e => DecisionErrors.classify(e).isInstanceOf[DecisionErrors.Kind.Budget]), s"the budget should block, got ${overBudget}")
    assertEquals(DecisionModels.resultOf(overBudget.left.toOption.get).header.status, 402)
    val denied = decide(restricted)
    assertEquals(denied.left.toOption.map(DecisionErrors.classify), Some(DecisionErrors.Kind.Denied))
    assertEquals(DecisionModels.resultOf(denied.left.toOption.get).header.status, 403)
    val unbillable = decide(strict)
    assert(unbillable.left.toOption.exists(e => DecisionErrors.classify(e).isInstanceOf[DecisionErrors.Kind.NotBillable]), s"an unknown price should be refused, got ${unbillable}")
    assertEquals(DecisionModels.resultOf(unbillable.left.toOption.get).header.status, 403)
    assertEquals(fallbackCalls.get(), calls, "none of them should have reached the provider")
  }
}
