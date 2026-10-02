package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.decorators.ChatClientWithAuding
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiBudgetConsumptions, AiProvider, EmbeddingModel, EmbeddingStore, SearchEngine}
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatMessage, ChatPrompt, LlmExtensionOneOtoroshiServerPerSuite}
import otoroshi.env.Env
import otoroshi.models.EntityLocation
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.*
import reactor.core.publisher.Mono
import reactor.netty.http.server.HttpServerResponse

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.DurationInt

// A model searching a knowledge base makes the gateway embed its query with an embedding model, in the middle of
// the chat call. The embedding is a call of its own, counted for the embedding model. The chat call stays the
// call of its provider: it is counted against the budgets of that provider.
class RagSearchAccountingSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  val engineId = s"search-engine_${UUID.randomUUID().toString}"
  val embeddingTokens = 7L

  private def send(response: HttpServerResponse, body: JsValue) =
    response.status(200).addHeader("Content-Type", "application/json").sendString(Mono.just(body.stringify)).`then`()

  private def completion(message: JsObject, finishReason: String, promptTokens: Long, completionTokens: Long): JsObject = Json.obj(
    "id" -> "chatcmpl-1", "object" -> "chat.completion", "created" -> 1700000000, "model" -> "gpt-4o-mini",
    "choices" -> Json.arr(Json.obj("index" -> 0, "finish_reason" -> finishReason, "message" -> message)),
    "usage" -> Json.obj("prompt_tokens" -> promptTokens, "completion_tokens" -> completionTokens, "total_tokens" -> (promptTokens + completionTokens)),
  )

  // a model that searches the knowledge base first, then answers with what it was given
  val chatCalls = new AtomicInteger(0)
  val embeddingCalls = new AtomicInteger(0)
  val (openaiPort, _) = createTestServerWithRoutes("rag-search-accounting", routes => routes
    .post("/v1/chat/completions", (req, response) => req.receive().aggregate().asString().flatMap { body =>
      chatCalls.incrementAndGet()
      val searched = Json.parse(body).select("messages").asOpt[Seq[JsObject]].getOrElse(Seq.empty).exists(_.select("role").asOptString.contains("tool"))
      if (searched) {
        send(response, completion(Json.obj("role" -> "assistant", "content" -> "hello"), "stop", 100, 50))
      } else {
        send(response, completion(Json.obj("role" -> "assistant", "content" -> JsNull, "tool_calls" -> Json.arr(Json.obj(
          "id" -> "call_1", "type" -> "function",
          "function" -> Json.obj("name" -> s"search___${engineId}", "arguments" -> Json.obj("query" -> "what is otoroshi ?").stringify),
        ))), "tool_calls", 10, 5))
      }
    })
    .post("/v1/embeddings", (req, response) => req.receive().aggregate().asString().flatMap { _ =>
      embeddingCalls.incrementAndGet()
      send(response, Json.obj(
        "object" -> "list", "model" -> "text-embedding-3-small",
        "data" -> Json.arr(Json.obj("object" -> "embedding", "index" -> 0, "embedding" -> Json.arr(0.1, 0.2, 0.3))),
        "usage" -> Json.obj("prompt_tokens" -> embeddingTokens, "total_tokens" -> embeddingTokens),
      ))
    })
  )

  def ext: AiExtension = otoroshi.env.adminExtensions.extension[AiExtension].get

  val connection = Json.obj("base_url" -> s"http://localhost:${openaiPort}/v1", "token" -> "sk-test", "timeout" -> 10000)

  lazy val embeddingModel = EmbeddingModel(
    EntityLocation.default, s"embedding-model_${UUID.randomUUID()}", "knowledge base embeddings", "", Seq.empty, Map.empty, "openai",
    Json.obj("connection" -> connection, "options" -> Json.obj("model" -> "text-embedding-3-small")),
  )
  lazy val store = EmbeddingStore(
    EntityLocation.default, s"embedding-store_${UUID.randomUUID()}", "knowledge base", "", Seq.empty, Map.empty, "local",
    Json.obj("connection" -> Json.obj(), "options" -> Json.obj()),
  )
  lazy val knowledgeBase = SearchEngine(
    location = EntityLocation.default,
    id = engineId,
    name = "knowledge base search",
    description = "knowledge base search",
    tags = Seq.empty,
    metadata = Map.empty,
    provider = "rag",
    config = Json.obj("embedding_store" -> store.id, "embedding_model" -> embeddingModel.id, "options" -> Json.obj("max_results" -> 5, "min_score" -> 0.5)),
  )
  lazy val provider = AiProvider(
    id = s"provider_${UUID.randomUUID()}",
    name = "searching",
    provider = "openai",
    connection = connection,
    options = Json.obj("model" -> "gpt-4o-mini", "search_engines" -> Json.arr(engineId)),
  )

  // one budget for each of them: what a call counts for is told by whose budget moves
  def budget(scoped: String): JsObject = Json.obj(
    "id" -> s"budget_${UUID.randomUUID()}",
    "name" -> s"budget of ${scoped}",
    "description" -> "",
    "enabled" -> true,
    "duration" -> Json.obj("value" -> 1, "unit" -> "year"),
    "limits" -> Json.obj("total_usd" -> 1000, "total_tokens" -> 100000000),
    "scope" -> Json.obj("providers" -> Json.arr(scoped)),
    "action_on_exceed" -> Json.obj("mode" -> "soft", "alert_on_exceed" -> false, "alert_on_almost_exceed" -> false),
  )

  lazy val setup: Unit = {
    assert(client.forLlmEntity("embedding-models").upsertEntity(embeddingModel).awaitf(10.seconds).createdOrUpdated, "the embedding model should be saved")
    assert(client.forLlmEntity("embedding-stores").upsertEntity(store).awaitf(10.seconds).createdOrUpdated, "the embedding store should be saved")
    assert(client.forLlmEntity("search-engines").upsertEntity(knowledgeBase).awaitf(10.seconds).createdOrUpdated, "the search engine should be saved")
    assert(client.forLlmEntity("providers").upsertEntity(provider).awaitf(10.seconds).createdOrUpdated, "the provider should be saved")
    Seq(provider.id, embeddingModel.id).foreach(id => client.forLlmEntity("ai-budgets").createRaw(budget(id)).awaitf(10.seconds))
    await(10.seconds)
  }

  def consumptions(scoped: String): AiBudgetConsumptions = {
    given env: Env = otoroshi.env
    ext.states.allBudgets().find(_.name == s"budget of ${scoped}").get.getConsumptions().awaitf(10.seconds)
  }

  test("a chat call searching a knowledge base counts for its provider, the embedding of the query for the embedding model") {
    setup
    given env: Env = otoroshi.env
    val searching = consumptions(provider.id)
    val embedding = consumptions(embeddingModel.id)
    val attrs = TypedMap.empty
    val resp = ext.states.provider(provider.id).flatMap(_.getChatClient()).get
      .call(ChatPrompt(Seq(ChatMessage.userStrInput("what is otoroshi ?"))), attrs, Json.obj())(using ec, env)
      .awaitf(30.seconds)
    assertEquals(resp.map(_.headGeneration.message.wholeTextContent), Right("hello"), "the model should have answered after its search")
    assertEquals(chatCalls.get(), 2, "the model was called, searched, and was called again with what it found")
    assertEquals(embeddingCalls.get(), 1, "the query of the search was embedded")
    val chatTokens = resp.toOption.get.metadata.usage.totalTokens
    assert(chatTokens > 0, "the chat call reports its tokens")
    await(5.seconds)
    val searched = consumptions(provider.id)
    assertEquals(searched.inferenceTokens - searching.inferenceTokens, chatTokens, "the chat call should count for its provider")
    assert(searched.inferenceUsd > searching.inferenceUsd, "with its price")
    assertEquals(attrs.get(ChatClientWithAuding.ProviderKey).map(_.internalId), Some(provider.id), "the call is still the one of its provider")
    val embedded = consumptions(embeddingModel.id)
    assertEquals(embedded.embeddingTokens - embedding.embeddingTokens, embeddingTokens, "the embedding counts for the embedding model")
    assertEquals(embedded.inferenceTokens - embedding.inferenceTokens, 0L, "the chat call does not")
  }
}
