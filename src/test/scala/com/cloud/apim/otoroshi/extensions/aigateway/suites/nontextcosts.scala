package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.decorators.ChatClientWithCostsTracking
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{EmbeddingModel, ModerationModel}
import com.cloud.apim.otoroshi.extensions.aigateway.{EmbeddingClientInputOptions, LlmExtensionOneOtoroshiServerPerSuite, ModerationInput, ModerationInputKind, ModerationModelClientInputOptions}
import otoroshi.env.Env
import otoroshi.models.EntityLocation
import otoroshi.next.models.*
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.OpenAICompatEmbedding
import play.api.libs.json.Json
import reactor.core.publisher.Mono

import java.util.UUID
import scala.concurrent.duration.DurationInt

// Embeddings and moderation are billed per token, so the price grid applies to them the same way it does to
// text. Before this, no cost was ever computed outside of text and dollar budgets simply never moved.
class NonTextCostsSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  // priced at 2e-8 per input token in the bundled grid
  val embeddingModelName = "text-embedding-3-small"
  val inputTokens = 1000L
  val expectedCost = BigDecimal("0.00002") // 1000 * 2e-8

  // openai moderation is free: the grid carries no cost field for it, so the cost must be a clean zero
  val moderationModelName = "omni-moderation-latest"
  val moderationTokens = 42L

  val (openaiPort, _) = createTestServerWithRoutes("openai-costs", routes => routes
    .post("/embeddings", (req, response) => {
      req.receiveContent().ignoreElements().subscribe()
      response
        .status(200)
        .addHeader("Content-Type", "application/json")
        .sendString(Mono.just(
          s"""{
             |  "object": "list",
             |  "data": [{"object":"embedding","index":0,"embedding":[0.1,0.2]}],
             |  "model": "${embeddingModelName}",
             |  "usage": {"prompt_tokens": ${inputTokens}, "total_tokens": ${inputTokens}}
             |}""".stripMargin))
    })
    .post("/moderations", (req, response) => {
      req.receiveContent().ignoreElements().subscribe()
      response
        .status(200)
        .addHeader("Content-Type", "application/json")
        .sendString(Mono.just(
          s"""{
             |  "id": "modr-1",
             |  "model": "${moderationModelName}",
             |  "results": [{"flagged": false, "categories": {}, "category_scores": {}}],
             |  "usage": {"prompt_tokens": ${moderationTokens}, "total_tokens": ${moderationTokens}}
             |}""".stripMargin))
    })
  )

  def ext: AiExtension = otoroshi.env.adminExtensions.extension[AiExtension].get

  val connection = Json.obj("base_url" -> s"http://localhost:${openaiPort}", "token" -> "xxx", "timeout" -> 30000)

  lazy val embeddingModel: EmbeddingModel = EmbeddingModel(
    EntityLocation.default, UUID.randomUUID().toString, "embedding costs", "", Seq.empty, Map.empty, "openai",
    Json.obj("connection" -> connection, "options" -> Json.obj("model" -> embeddingModelName)),
  )

  lazy val moderationModel: ModerationModel = ModerationModel(
    EntityLocation.default, UUID.randomUUID().toString, "moderation costs", "", Seq.empty, Map.empty, "openai",
    Json.obj("connection" -> connection, "options" -> Json.obj("model" -> moderationModelName)),
  )

  val budgetName = "non text budget"

  lazy val setup: Unit = {
    val route = NgRoute(
      location = EntityLocation.default,
      id = UUID.randomUUID().toString,
      name = "embeddings route",
      description = "embeddings route",
      tags = Seq.empty,
      metadata = Map.empty,
      enabled = true,
      debugFlow = false,
      capture = false,
      exportReporting = false,
      frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath("embeddings.oto.tools/embeddings"))),
      backend = NgBackend.empty.copy(targets = Seq(NgTarget.default)),
      plugins = NgPlugins(Seq(NgPluginInstance(
        plugin = s"cp:${classOf[OpenAICompatEmbedding].getName}",
        config = NgPluginInstanceConfig(Json.obj("refs" -> Json.arr(embeddingModel.id)))
      )))
    )
    val budgetJson = Json.obj(
      "id" -> UUID.randomUUID().toString,
      "name" -> budgetName,
      "description" -> budgetName,
      "enabled" -> true,
      "duration" -> Json.obj("value" -> 1, "unit" -> "year"),
      "limits" -> Json.obj("total_usd" -> 10, "total_tokens" -> 1000000),
      "scope" -> Json.obj("providers" -> Json.arr(embeddingModel.id)),
      "action_on_exceed" -> Json.obj("mode" -> "soft"),
    )
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "embedding-models").upsertEntity(embeddingModel).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "moderation-models").upsertEntity(moderationModel).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "ai-budgets").createRaw(budgetJson).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(route).awaitf(10.seconds)
    await(10.seconds)
  }

  test("an embedding call computes its cost from the price grid") {
    setup
    given env: Env = otoroshi.env
    val attrs = TypedMap.empty
    val resp = ext.states.embeddingModel(embeddingModel.id).flatMap(_.getEmbeddingModelClient()).get
      .embed(EmbeddingClientInputOptions(input = Seq("hey")), Json.obj(), attrs)(using ec, otoroshi.env)
      .awaitf(30.seconds)
    assert(resp.isRight, s"the call should succeed, got ${resp}")
    val costs = attrs.get(ChatClientWithCostsTracking.key)
    assert(costs.isDefined, "a cost should have been computed for the embedding call")
    assertEquals(costs.get.totalCost, expectedCost)
    assertEquals(costs.get.inputCost, expectedCost)
    // embeddings are input only
    assertEquals(costs.get.outputCost, BigDecimal(0))
  }

  test("a free moderation model costs a clean zero, and does not blow up") {
    setup
    given env: Env = otoroshi.env
    val attrs = TypedMap.empty
    val resp = ext.states.moderationModel(moderationModel.id).flatMap(_.getModerationModelClient()).get
      .moderate(ModerationModelClientInputOptions(input = Seq(ModerationInput(ModerationInputKind.Text, "hey"))), Json.obj(), attrs)(using ec, otoroshi.env)
      .awaitf(30.seconds)
    assert(resp.isRight, s"the call should succeed, got ${resp}")
    val costs = attrs.get(ChatClientWithCostsTracking.key)
    assert(costs.isDefined, "a cost should have been computed, even a zero one")
    assertEquals(costs.get.totalCost, BigDecimal(0))
  }

  test("an embedding call decrements a dollar budget, tokens included") {
    setup
    val budget = ext.states.allBudgets().find(_.name == budgetName).get
    val before = budget.getConsumptions()(using ec, otoroshi.env).awaitf(10.seconds)

    val resp = client.call("POST", s"http://embeddings.oto.tools:${port}/embeddings?embed_costs=true", Map.empty, Some(Json.obj(
      "model" -> embeddingModelName,
      "input" -> Json.arr("hey"),
    ))).awaitf(30.seconds)
    assertEquals(resp.status, 200, s"status should be 200, got ${resp.body}")
    assertEquals(resp.json.at("costs.total_cost").asOpt[BigDecimal], expectedCost.some, s"unexpected costs: ${resp.body}")

    await(5.seconds)
    val after = budget.getConsumptions()(using ec, otoroshi.env).awaitf(10.seconds)
    assertEquals(after.totalUsd - before.totalUsd, expectedCost, "the dollar budget should have moved by the exact cost")
    assertEquals(after.embeddingUsd - before.embeddingUsd, expectedCost, "the embedding budget should have moved too")
    // the tokens used to be read from an attr set only further down, so they never reached the budget
    assertEquals(after.totalTokens - before.totalTokens, inputTokens, "the tokens should have been counted")
    assertEquals(after.embeddingTokens - before.embeddingTokens, inputTokens)
  }
}
