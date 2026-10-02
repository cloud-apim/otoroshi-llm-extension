package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.LlmExtensionOneOtoroshiServerPerSuite
import com.cloud.apim.otoroshi.extensions.aigateway.decorators.{CostsOutput, CostsTracking, CostsTrackingSettings, OpenRouterCatalog}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiBudgetConsumptions, AiProvider}
import otoroshi.models.EntityLocation
import otoroshi.next.models.*
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.OpenAiCompatProxy
import play.api.Configuration
import play.api.libs.json.{JsObject, Json}
import play.api.libs.ws.WSResponse
import reactor.core.publisher.Mono

import java.util.UUID
import scala.concurrent.duration.DurationInt

// OpenRouter serves many more models than the static price table knows about. When a model is missing from it,
// no cost is computed at all and dollar-denominated budgets silently stay put. These tests cover the three ways
// out: the cost OpenRouter reports itself, the variant/alias fallback on the table, and the catalog sync.
class OpenRouterCostsSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  // a real usage payload as returned by OpenRouter for `openai/gpt-4.1-nano` with `usage.include`
  val realOpenRouterUsage: JsObject = Json.obj(
    "prompt_tokens" -> 8,
    "completion_tokens" -> 11,
    "total_tokens" -> 19,
    "cost" -> 0.0000052,
    "is_byok" -> false,
    "cost_details" -> Json.obj(
      "upstream_inference_cost" -> 0.0000052,
      "upstream_inference_prompt_cost" -> 0.0000008,
      "upstream_inference_completions_cost" -> 0.0000044,
    ),
    "completion_tokens_details" -> Json.obj("reasoning_tokens" -> 0),
  )

  // A cheap model OpenRouter serves, for the real calls. The static table did not know it when these tests were
  // written and has caught up since: the cost OpenRouter reports for a call is the one billed either way
  val realModel = "upstage/solar-pro4"
  // a model no price table can know
  val modelMissingFromPriceTable = "made-up-vendor/made-up-model"
  // a model the static price table knows. The variant suffix is made up on purpose: a real one would also be
  // in the synced catalog, and the test would no longer exercise the fallback.
  val staticModel = "mistralai/mistral-7b-instruct"
  val modelVariant = s"${staticModel}:made-up-variant"

  lazy val apiKey: Option[String] = sys.env.get("OPENROUTER_API_KEY").filter(_.trim.nonEmpty)

  // The catalog OpenRouter publishes, as the sync reads it: a model the static table does not know, and one it
  // curates, with a price of its own. The real catalog cannot say which models the table misses: every refresh
  // of the table catches up with it
  val (catalogPort, _) = createTestServerWithRoutes("openrouter-catalog", routes => routes.get("/api/v1/models", (_, response) => {
    response.status(200).addHeader("Content-Type", "application/json").sendString(Mono.just(Json.obj("data" -> Json.arr(
      Json.obj("id" -> modelMissingFromPriceTable, "context_length" -> 131072, "pricing" -> Json.obj("prompt" -> "0.000001", "completion" -> "0.000002")),
      Json.obj("id" -> staticModel, "context_length" -> 32768, "pricing" -> Json.obj("prompt" -> "0.5", "completion" -> "0.5")),
    )).stringify))
  }))

  def ext: AiExtension = otoroshi.env.adminExtensions.extension[AiExtension].get

  test("provider reported cost is parsed from an openai-like usage payload") {
    val costs = CostsOutput.fromOpenAiLikeUsage(realOpenRouterUsage)
    assert(costs.isDefined, "a usage payload carrying a cost should yield costs")
    assertEquals(costs.get.totalCost, BigDecimal(0.0000052))
    assertEquals(costs.get.inputCost, BigDecimal(0.0000008))
    assertEquals(costs.get.outputCost, BigDecimal(0.0000044))
    assertEquals(costs.get.source, CostsOutput.sourceProvider)
    // providers that do not report a cost must stay on the price table path
    assertEquals(CostsOutput.fromOpenAiLikeUsage(Json.obj("prompt_tokens" -> 8, "completion_tokens" -> 11)), None)
  }

  test("provider reported cost survives a json round trip (the streaming path goes through analytics)") {
    val costs = CostsOutput.fromOpenAiLikeUsage(realOpenRouterUsage).get
    val decoded = CostsOutput.fromJson(costs.json)
    assertEquals(decoded.map(_.totalCost), costs.totalCost.some)
    assertEquals(decoded.map(_.source), CostsOutput.sourceProvider.some)
  }

  test("openrouter model variants and aliases fall back to the price of their base model") {
    val base = ext.costsTracking.lookupModel("openrouter", staticModel)
    assert(base.isDefined, s"${staticModel} should be in the price table")
    assertEquals(ext.costsTracking.lookupModel("openrouter", modelVariant).map(_.name), base.map(_.name))
    assertEquals(ext.costsTracking.lookupModel("openrouter", s"~${staticModel}").map(_.name), base.map(_.name))
    assert(ext.costsTracking.canHandle("openrouter", modelVariant), "a variant should be priceable")
    assert(ext.costsTracking.computeCosts("openrouter", modelVariant, 1000L, 1000L, 0L).toOption.exists(_.totalCost > 0), "a variant should be billed like its base model")
    // the fallback must not leak to other providers: bedrock model names legitimately end with `:0`
    assertEquals(ext.costsTracking.lookupModel("bedrock", "ai21.jamba-1-5-large-v1:0").map(_.name), "ai21.jamba-1-5-large-v1:0".some)
    assertEquals(ext.costsTracking.lookupModel("bedrock", "made.up.model:0"), None)
  }

  test("the openrouter catalog sync prices models the static table is missing") {
    val settings = CostsTrackingSettings(Configuration("openrouter-catalog.url" -> s"http://localhost:${catalogPort}/api/v1/models"))
    val tracking = new CostsTracking(settings, otoroshi.env, ext.modelsCatalog)
    assertEquals(tracking.lookupModel("openrouter", modelMissingFromPriceTable), None, "precondition: nothing prices the model before the sync")
    val count = tracking.refreshOpenRouterCatalog()(using ec).awaitf(30.seconds)
    assertEquals(count, 2, "every model of the catalog with a pricing is kept")
    val model = tracking.lookupModel("openrouter", modelMissingFromPriceTable)
    assert(model.isDefined, s"${modelMissingFromPriceTable} should be priced after the sync")
    assertEquals(model.get.input_cost_per_token, BigDecimal("0.000001"))
    assertEquals(model.get.output_cost_per_token, BigDecimal("0.000002"))
    // a synced price must never override a curated one
    val curatedKey = s"openrouter-${staticModel}"
    assert(tracking.staticModels.contains(curatedKey), "precondition: the model is curated")
    assertEquals(tracking.lookupModel("openrouter", staticModel).map(_.input_cost_per_token), tracking.staticModels.get(curatedKey).map(_.input_cost_per_token), "curated entries must win over synced ones")
    val costs = tracking.computeCosts("openrouter", modelMissingFromPriceTable, 1000L, 1000L, 0L)
    assertEquals(costs.toOption.map(_.totalCost), Some(BigDecimal("0.003")), "a synced model is billed the prices of the catalog")
  }

  test("a catalog entry keeps the prices openrouter publishes") {
    val entry = OpenRouterCatalog.toCostModel(Json.obj(
      "id" -> "vendor/some-model",
      "context_length" -> 524288,
      "top_provider" -> Json.obj("max_completion_tokens" -> 131072),
      "pricing" -> Json.obj("prompt" -> "0.00000003", "completion" -> "0.00000012", "input_cache_read" -> "0.000000006"),
    ))
    assert(entry.isDefined)
    assertEquals(entry.get.name, "vendor/some-model")
    assertEquals(entry.get.litellm_provider, "openrouter")
    assertEquals(entry.get.input_cost_per_token, BigDecimal("0.00000003"))
    assertEquals(entry.get.output_cost_per_token, BigDecimal("0.00000012"))
    assertEquals(entry.get.cache_read_input_token_cost, BigDecimal("0.000000006"))
    assertEquals(entry.get.max_input_tokens, 524288L)
    assertEquals(OpenRouterCatalog.toCostModel(Json.obj("id" -> "no/pricing")), None)
  }

  // provider + route + budget, created once and shared by the real call tests
  lazy val realSetup: Option[(AiProvider, String)] = apiKey.map { key =>
    val provider = AiProvider(
      id = UUID.randomUUID().toString,
      name = "openrouter test provider",
      provider = "openrouter",
      connection = Json.obj("token" -> key, "timeout" -> 60000),
      options = Json.obj("model" -> realModel, "max_tokens" -> 5),
    )
    val route = NgRoute(
      location = EntityLocation.default,
      id = UUID.randomUUID().toString,
      name = "openrouter test route",
      description = "openrouter test route",
      tags = Seq.empty,
      metadata = Map.empty,
      enabled = true,
      debugFlow = false,
      capture = false,
      exportReporting = false,
      frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath("openrouter.oto.tools/chat"))),
      backend = NgBackend.empty.copy(targets = Seq(NgTarget.default)),
      plugins = NgPlugins(Seq(NgPluginInstance(
        plugin = s"cp:${classOf[OpenAiCompatProxy].getName}",
        config = NgPluginInstanceConfig(Json.obj("refs" -> Json.arr(provider.id)))
      )))
    )
    val budgetName = "openrouter test budget"
    val budgetJson = Json.obj(
      "id" -> UUID.randomUUID().toString,
      "name" -> budgetName,
      "description" -> budgetName,
      "enabled" -> true,
      "duration" -> Json.obj("value" -> 1, "unit" -> "year"),
      "limits" -> Json.obj("total_usd" -> 10, "total_tokens" -> 1000000),
      "scope" -> Json.obj("providers" -> Json.arr(provider.id)),
      "action_on_exceed" -> Json.obj("mode" -> "soft"),
    )
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").upsertEntity(provider).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "ai-budgets").createRaw(budgetJson).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(route).awaitf(10.seconds)
    await(10.seconds)
    (provider, budgetName)
  }

  def consumptions(budgetName: String): AiBudgetConsumptions = {
    val budget = ext.states.allBudgets().find(_.name == budgetName).get
    budget.getConsumptions()(using ec, otoroshi.env).awaitf(10.seconds)
  }

  test("a real openrouter call is billed the cost openrouter reports, and decrements the budget") {
    realSetup match {
      case None => println("[openrouter] OPENROUTER_API_KEY is not set, skipping the real call test")
      case Some((_, budgetName)) => {
        val before = consumptions(budgetName)
        val resp: WSResponse = client.call("POST", s"http://openrouter.oto.tools:${port}/chat?embed_costs=true", Map.empty, Some(Json.obj(
          "model" -> realModel,
          "max_tokens" -> 5,
          "messages" -> Json.arr(Json.obj("role" -> "user", "content" -> "hi")),
        ))).awaitf(60.seconds)
        assertEquals(resp.status, 200, s"status should be 200, got ${resp.body}")

        val costs = resp.json.select("costs")
        assertEquals(costs.select("source").asOptString, CostsOutput.sourceProvider.some, s"the cost should come from openrouter, got ${costs}")
        assert(costs.select("total_cost").asOpt[BigDecimal].getOrElse(BigDecimal(0)) > 0, s"a paid model should report a non zero cost, got ${costs}")

        await(5.seconds)
        val after = consumptions(budgetName)
        assert(after.totalUsd > before.totalUsd, s"the budget should have been decremented, went from ${before.totalUsd} to ${after.totalUsd}")
        assert(after.inferenceUsd > before.inferenceUsd, s"the inference budget should have been decremented, went from ${before.inferenceUsd} to ${after.inferenceUsd}")
        assert(after.totalTokens > before.totalTokens, "tokens should have been counted too")
      }
    }
  }

  test("a real streamed openrouter call is billed the cost openrouter reports, and decrements the budget") {
    realSetup match {
      case None => println("[openrouter] OPENROUTER_API_KEY is not set, skipping the real streaming test")
      case Some((_, budgetName)) => {
        val before = consumptions(budgetName)
        val resp = client.stream("POST", s"http://openrouter.oto.tools:${port}/chat?stream=true&embed_costs=true", Map.empty, Some(Json.obj(
          "model" -> realModel,
          "max_tokens" -> 5,
          "messages" -> Json.arr(Json.obj("role" -> "user", "content" -> "hi")),
        ))).awaitf(60.seconds)
        assertEquals(resp.status, 200, s"status should be 200, got ${resp.chunks}")
        assert(resp.chunks.nonEmpty, "no chunks")

        // the cost is only known once the stream is over, so it rides on a terminal chunk
        val costs = resp.chunks.flatMap(_.select("costs").asOpt[JsObject]).lastOption
        assert(costs.isDefined, s"a terminal chunk should carry the costs, got ${resp.chunks}")
        assertEquals(costs.get.select("source").asOptString, CostsOutput.sourceProvider.some, s"the cost should come from openrouter, got ${costs}")
        assert(costs.get.select("total_cost").asOpt[BigDecimal].getOrElse(BigDecimal(0)) > 0, s"a paid model should report a non zero cost, got ${costs}")
        // stripping the finish reasons must not lose the end of the stream, and must report the real one:
        // max_tokens is small enough that openrouter answers "length", never "stop"
        val finishReasons = resp.chunks.flatMap(_.at("choices.0.finish_reason").asOptString)
        assertEquals(finishReasons, Seq("length"), s"the stream should end with the real finish reason, got ${resp.chunks}")

        await(5.seconds)
        val after = consumptions(budgetName)
        assert(after.totalUsd > before.totalUsd, s"the budget should have been decremented, went from ${before.totalUsd} to ${after.totalUsd}")
      }
    }
  }
}
