package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiBudgetConsumptions, AiProvider, EmbeddingModel}
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatMessage, ChatPrompt, LlmExtensionOneOtoroshiServerPerSuite}
import otoroshi.env.Env
import otoroshi.models.{ApiKey, EntityLocation}
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.*
import reactor.core.publisher.Mono
import reactor.netty.http.server.HttpServerResponse

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.DurationInt

// Answering a call may take more calls than the one that was asked: a semantic cache embeds the question, a router
// asks a model which one to pick, a fusion asks a panel and a judge before the last model answers. They are made for
// the caller: what an api key is allowed to spend counts all of them, not the last one alone.
class AuxiliaryCallsSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  val chatTokens = 15L
  val embeddingTokens = 7L

  private def send(response: HttpServerResponse, body: JsValue) =
    response.status(200).addHeader("Content-Type", "application/json").sendString(Mono.just(body.stringify)).`then`()

  // One server for each case: a server of the suite does not take many calls. A model answers `0`, which is also
  // the candidate a classifier is asked to name
  class Models(name: String) {
    val chatCalls = new AtomicInteger(0)
    val embeddingCalls = new AtomicInteger(0)
    val (port, _) = createTestServerWithRoutes(name, routes => routes
      .post("/v1/chat/completions", (req, response) => req.receive().aggregate().asString().flatMap { raw =>
        chatCalls.incrementAndGet()
        send(response, Json.obj(
          "id" -> "chatcmpl-1", "object" -> "chat.completion", "created" -> 1700000000, "model" -> Json.parse(raw).select("model").asString,
          "choices" -> Json.arr(Json.obj("index" -> 0, "finish_reason" -> "stop", "message" -> Json.obj("role" -> "assistant", "content" -> "0"))),
          "usage" -> Json.obj("prompt_tokens" -> 10, "completion_tokens" -> 5, "total_tokens" -> chatTokens),
        ))
      })
      .post("/v1/embeddings", (req, response) => req.receive().aggregate().asString().flatMap { raw =>
        embeddingCalls.incrementAndGet()
        send(response, Json.obj(
          "object" -> "list", "model" -> Json.parse(raw).select("model").asString,
          "data" -> Json.arr(Json.obj("object" -> "embedding", "index" -> 0, "embedding" -> Json.arr(0.1, 0.2, 0.3))),
          "usage" -> Json.obj("prompt_tokens" -> embeddingTokens, "total_tokens" -> embeddingTokens),
        ))
      })
    )
    val connection = Json.obj("base_url" -> s"http://localhost:${port}/v1", "token" -> "sk-test", "timeout" -> 10000)

    def provider(name: String): AiProvider = AiProvider(
      id = s"provider_${UUID.randomUUID()}", name = name, provider = "openai", connection = connection, options = Json.obj("model" -> s"model-of-${name}"),
    )
  }

  val caching = new Models("auxiliary-calls-cache")
  val routing = new Models("auxiliary-calls-router")
  val fusing = new Models("auxiliary-calls-fusion")

  def ext: AiExtension = otoroshi.env.adminExtensions.extension[AiExtension].get

  // a provider answering through a semantic cache, whose embeddings come from a model of the gateway
  lazy val embeddingModel = EmbeddingModel(
    EntityLocation.default, s"embedding-model_${UUID.randomUUID()}", "cache embeddings", "", Seq.empty, Map.empty, "openai",
    Json.obj("connection" -> caching.connection, "options" -> Json.obj("model" -> "an-embedding-model")),
  )
  lazy val cached = {
    val provider = caching.provider("cached")
    provider.copy(cache = provider.cache.copy(strategy = "semantic", embeddingRef = Some(embeddingModel.id)))
  }

  // a router asking a classifier which of its candidates should answer
  lazy val classifier = routing.provider("classifier")
  lazy val candidate = routing.provider("candidate")
  lazy val autoRouter = AiProvider(
    id = s"provider_${UUID.randomUUID()}", name = "auto router", provider = "otoroshi", connection = Json.obj(),
    options = Json.obj("auto_router_refs" -> Json.arr(candidate.id), "auto_router_classifier_ref" -> classifier.id),
  )

  // a fusion: a panel answers, a judge compares, a synthesizer writes the answer
  lazy val panelist = fusing.provider("panelist")
  lazy val judge = fusing.provider("judge")
  lazy val synthesizer = fusing.provider("synthesizer")
  lazy val fusionRouter = AiProvider(
    id = s"provider_${UUID.randomUUID()}", name = "fusion router", provider = "otoroshi", connection = Json.obj(),
    options = Json.obj("fusion_router_refs" -> Json.arr(panelist.id), "fusion_router_judge_ref" -> judge.id, "fusion_router_synthesizer_ref" -> synthesizer.id),
  )

  // what an api key may spend, whatever the providers that serve it
  def apikey(name: String): ApiKey = ApiKey(clientId = s"key-of-${name}", clientSecret = "secret", clientName = name, authorizedEntities = Seq.empty)
  def budget(key: ApiKey): JsObject = Json.obj(
    "id" -> s"budget_${UUID.randomUUID()}",
    "name" -> s"budget of ${key.clientId}",
    "description" -> "",
    "enabled" -> true,
    "duration" -> Json.obj("value" -> 1, "unit" -> "year"),
    "limits" -> Json.obj("total_usd" -> 1000, "total_tokens" -> 100000000),
    "scope" -> Json.obj("apikeys" -> Json.arr(key.clientId)),
    "action_on_exceed" -> Json.obj("mode" -> "soft", "alert_on_exceed" -> false, "alert_on_almost_exceed" -> false),
  )
  val cacheKey = apikey("cache")
  val routerKey = apikey("router")
  val fusionKey = apikey("fusion")

  lazy val setup: Unit = {
    assert(client.forLlmEntity("embedding-models").upsertEntity(embeddingModel).awaitf(10.seconds).createdOrUpdated, "the embedding model should be saved")
    Seq(cached, classifier, candidate, autoRouter, panelist, judge, synthesizer, fusionRouter).foreach { p =>
      assert(client.forLlmEntity("providers").upsertEntity(p).awaitf(10.seconds).createdOrUpdated, s"${p.name} should be saved")
    }
    Seq(cacheKey, routerKey, fusionKey).foreach(key => client.forLlmEntity("ai-budgets").createRaw(budget(key)).awaitf(10.seconds))
    await(10.seconds)
  }

  def consumptions(key: ApiKey): AiBudgetConsumptions = {
    given env: Env = otoroshi.env
    ext.states.allBudgets().find(_.name == s"budget of ${key.clientId}").get.getConsumptions().awaitf(10.seconds)
  }

  // a call made with an api key, and what its budget counted for it
  def spentBy(key: ApiKey, provider: AiProvider, body: JsObject = Json.obj()): AiBudgetConsumptions = {
    given env: Env = otoroshi.env
    val before = consumptions(key)
    val attrs = TypedMap.empty.put(_root_.otoroshi.plugins.Keys.ApiKeyKey -> key)
    val resp = ext.states.provider(provider.id).flatMap(_.getChatClient()).get
      .call(ChatPrompt(Seq(ChatMessage.userStrInput("what is otoroshi ?"))), attrs, body)(using ec, env).awaitf(30.seconds)
    assert(resp.isRight, s"the call should succeed, got ${resp}")
    await(5.seconds)
    val after = consumptions(key)
    after.copy(
      totalTokens = after.totalTokens - before.totalTokens,
      inferenceTokens = after.inferenceTokens - before.inferenceTokens,
      embeddingTokens = after.embeddingTokens - before.embeddingTokens,
    )
  }

  test("the embeddings of a semantic cache count for the caller") {
    setup
    val spent = spentBy(cacheKey, cached)
    assertEquals(caching.chatCalls.get(), 1, "the question was not in the cache: the model answered")
    assert(caching.embeddingCalls.get() > 0, "the cache embedded the question with the embedding model")
    assertEquals(spent.inferenceTokens, chatTokens, "the answer counts for the caller")
    assertEquals(spent.embeddingTokens, caching.embeddingCalls.get() * embeddingTokens, "and so does every embedding the cache asked for it")
  }

  test("the model a router asks to pick a candidate counts for the caller") {
    setup
    val spent = spentBy(routerKey, autoRouter, Json.obj("model" -> "auto-router"))
    assertEquals(routing.chatCalls.get(), 2, "the classifier picked a candidate, the candidate answered")
    assertEquals(spent.inferenceTokens, 2 * chatTokens, "both count for the caller")
  }

  test("the panel and the judge of a fusion count for the caller") {
    setup
    val spent = spentBy(fusionKey, fusionRouter, Json.obj("model" -> "fusion-router"))
    assertEquals(fusing.chatCalls.get(), 3, "the panel answered, the judge compared, the synthesizer wrote the answer")
    assertEquals(spent.inferenceTokens, 3 * chatTokens, "the three count for the caller")
  }
}
