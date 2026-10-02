package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.decorators.ChatClientWithCostsTracking
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiBudgetConsumptions, ModerationModel}
import com.cloud.apim.otoroshi.extensions.aigateway.{LlmExtensionOneOtoroshiServerPerSuite, ModerationInput, ModerationInputKind, ModerationModelClientInputOptions, ModerationResponse}
import otoroshi.env.Env
import otoroshi.models.EntityLocation
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.*
import reactor.core.publisher.Mono
import reactor.netty.http.server.HttpServerResponse

import java.util.UUID
import scala.concurrent.duration.DurationInt

// The tokens a moderation provider says it used are the tokens of the call: in the response, in its price when
// the model has one, and against the budgets of the moderation model. A provider that reports nothing counts
// nothing.
class ModerationUsageSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  val tokens = 42L

  // answers as the model it was asked for
  private def send(response: HttpServerResponse, request: String, usage: Option[JsObject]) = {
    val body = Json.obj(
      "id" -> "modr-1", "model" -> Json.parse(request).select("model").asString,
      "results" -> Json.arr(Json.obj("flagged" -> false, "categories" -> Json.obj(), "category_scores" -> Json.obj())),
    ) ++ usage.map(u => Json.obj("usage" -> u)).getOrElse(Json.obj())
    response.status(200).addHeader("Content-Type", "application/json").sendString(Mono.just(body.stringify)).`then`()
  }

  val (port1, _) = createTestServerWithRoutes("moderation-usage", routes => routes
    // the usage of an openai compatible provider
    .post("/reported/moderations", (req, response) => req.receive().aggregate().asString().flatMap { body =>
      send(response, body, Some(Json.obj("prompt_tokens" -> tokens, "total_tokens" -> tokens)))
    })
    // a provider counting input and output
    .post("/mistral/moderations", (req, response) => req.receive().aggregate().asString().flatMap { body =>
      send(response, body, Some(Json.obj("input_tokens" -> tokens, "output_tokens" -> 0)))
    })
    // what the moderation endpoints of OpenAI and Mistral answer: no usage at all
    .post("/silent/moderations", (req, response) => req.receive().aggregate().asString().flatMap { body =>
      send(response, body, None)
    })
  )

  def ext: AiExtension = otoroshi.env.adminExtensions.extension[AiExtension].get

  def moderationModel(name: String, provider: String, path: String, model: String): ModerationModel = ModerationModel(
    EntityLocation.default, s"moderation-model_${UUID.randomUUID()}", name, "", Seq.empty, Map.empty, provider,
    Json.obj("connection" -> Json.obj("base_url" -> s"http://localhost:${port1}${path}", "token" -> "sk-test", "timeout" -> 10000), "options" -> Json.obj("model" -> model)),
  )

  // a model billed per token, as the chat models the aggregators moderate with are
  val pricedModel = "gpt-4o-mini"

  lazy val free = moderationModel("free", "openai", "/reported", "omni-moderation-latest")
  lazy val priced = moderationModel("priced", "openai", "/reported", pricedModel)
  lazy val mistral = moderationModel("mistral", "mistral", "/mistral", "mistral-moderation-latest")
  lazy val silent = moderationModel("silent", "openai", "/silent", "omni-moderation-latest")

  def budget(scoped: String): JsObject = Json.obj(
    "id" -> s"budget_${UUID.randomUUID()}",
    "name" -> s"budget of ${scoped}",
    "description" -> "",
    "enabled" -> true,
    "duration" -> Json.obj("value" -> 1, "unit" -> "year"),
    "limits" -> Json.obj("total_usd" -> 1000, "moderation_tokens" -> 100000000),
    "scope" -> Json.obj("providers" -> Json.arr(scoped)),
    "action_on_exceed" -> Json.obj("mode" -> "soft", "alert_on_exceed" -> false, "alert_on_almost_exceed" -> false),
  )

  lazy val setup: Unit = {
    Seq(free, priced, mistral, silent).foreach { m =>
      assert(client.forLlmEntity("moderation-models").upsertEntity(m).awaitf(10.seconds).createdOrUpdated, s"${m.name} should be saved")
      client.forLlmEntity("ai-budgets").createRaw(budget(m.id)).awaitf(10.seconds)
    }
    await(10.seconds)
  }

  def consumptions(model: ModerationModel): AiBudgetConsumptions = {
    given env: Env = otoroshi.env
    ext.states.allBudgets().find(_.name == s"budget of ${model.id}").get.getConsumptions().awaitf(10.seconds)
  }

  def moderate(model: ModerationModel, attrs: TypedMap = TypedMap.empty): ModerationResponse = {
    given env: Env = otoroshi.env
    val resp = ext.states.moderationModel(model.id).flatMap(_.getModerationModelClient()).get
      .moderate(ModerationModelClientInputOptions(input = Seq(ModerationInput(ModerationInputKind.Text, "hey"))), Json.obj(), attrs)(using ec, env)
      .awaitf(30.seconds)
    assert(resp.isRight, s"the call should succeed, got ${resp}")
    resp.toOption.get
  }

  test("the tokens a moderation provider reports are in the response and count against the budget of the model") {
    setup
    given env: Env = otoroshi.env
    val before = consumptions(free)
    val resp = moderate(free)
    assertEquals(resp.metadata.usage.total, tokens)
    assertEquals(resp.toOpenAiJson(env).select("usage").select("total_tokens").asOpt[Long], Some(tokens), "the caller is told what the call used")
    await(5.seconds)
    val after = consumptions(free)
    assertEquals(after.moderationTokens - before.moderationTokens, tokens, "the budget should have moved by the tokens of the call")
    assertEquals(after.moderationUsd - before.moderationUsd, BigDecimal(0), "a free model costs nothing")
  }

  test("a moderation model with a price is billed on the tokens its provider reports") {
    setup
    val expected = ext.costsTracking.computeCosts("openai", pricedModel, tokens, 0L, 0L).toOption.get.totalCost
    assert(expected > 0, "precondition: the model has a price")
    val before = consumptions(priced)
    val attrs = TypedMap.empty
    moderate(priced, attrs)
    assertEquals(attrs.get(ChatClientWithCostsTracking.key).map(_.totalCost), Some(expected))
    await(5.seconds)
    val after = consumptions(priced)
    assertEquals(after.moderationUsd - before.moderationUsd, expected, "the budget should have moved by the cost of the call")
    assertEquals(after.moderationTokens - before.moderationTokens, tokens)
  }

  test("the usage of a provider counting input and output tokens is read too") {
    setup
    val before = consumptions(mistral)
    val resp = moderate(mistral)
    assertEquals(resp.metadata.usage.input, tokens)
    assertEquals(resp.metadata.usage.total, tokens)
    await(5.seconds)
    assertEquals(consumptions(mistral).moderationTokens - before.moderationTokens, tokens)
  }

  test("a provider that reports no usage counts nothing, and the call goes through") {
    setup
    val before = consumptions(silent)
    val resp = moderate(silent)
    assertEquals(resp.metadata.usage.total, 0L)
    await(5.seconds)
    assertEquals(consumptions(silent).moderationTokens - before.moderationTokens, 0L)
  }
}
