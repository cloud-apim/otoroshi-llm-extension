package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.decorators.{ChatClientWithAuding, GuardrailItem, Guardrails}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiBudgetConsumptions, AiProvider, ModerationModel, Prompt}
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatClient, ChatMessage, ChatPrompt, LlmExtensionOneOtoroshiServerPerSuite}
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

// A guardrail can ask another model what it thinks of the messages: a moderation model, a chat model judging
// them. That call is a call of its own, counted for the model that judged. The call being guarded stays the call
// of its provider: it is counted against the budgets of that provider, with its own tokens and its own price.
class GuardrailsAccountingSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  val chatModel = "gpt-4o"
  val judgeModel = "gpt-4o-mini"
  val chatTokens = (100L, 50L)
  val judgeTokens = (10L, 1L)
  val moderationTokens = 20L

  private def send(response: HttpServerResponse, body: JsValue) =
    response.status(200).addHeader("Content-Type", "application/json").sendString(Mono.just(body.stringify)).`then`()

  private def completion(model: String, content: String, tokens: (Long, Long)): JsObject = Json.obj(
    "id" -> "chatcmpl-1", "object" -> "chat.completion", "created" -> 1700000000, "model" -> model,
    "choices" -> Json.arr(Json.obj("index" -> 0, "finish_reason" -> "stop", "message" -> Json.obj("role" -> "assistant", "content" -> content))),
    "usage" -> Json.obj("prompt_tokens" -> tokens._1, "completion_tokens" -> tokens._2, "total_tokens" -> (tokens._1 + tokens._2)),
  )

  // the provider being guarded
  val chatCalls = new AtomicInteger(0)
  val (chatPort, _) = createTestServerWithRoutes("guardrails-accounting-chat", routes => routes
    .post("/v1/chat/completions", (req, response) => req.receive().aggregate().asString().flatMap { _ =>
      chatCalls.incrementAndGet()
      send(response, completion(chatModel, "hello", chatTokens))
    })
  )

  // a chat model that finds every message fine, and the same one judging faithfulness: it is asked for the
  // statements of the messages, then for a verdict on each of them
  val judgeCalls = new AtomicInteger(0)
  val faithfulnessCalls = new AtomicInteger(0)
  val (judgePort, _) = createTestServerWithRoutes("guardrails-accounting-judge", routes => routes
    .post("/v1/chat/completions", (req, response) => req.receive().aggregate().asString().flatMap { _ =>
      judgeCalls.incrementAndGet()
      send(response, completion(judgeModel, "true", judgeTokens))
    })
    .post("/faithfulness/v1/chat/completions", (req, response) => req.receive().aggregate().asString().flatMap { body =>
      faithfulnessCalls.incrementAndGet()
      val answer = if (body.contains("judge the faithfulness")) Json.arr(Json.obj("statement" -> "someone says hello", "reason" -> "it is in the context", "verdict" -> 1)) else Json.arr("someone says hello")
      send(response, completion(judgeModel, answer.stringify, judgeTokens))
    })
  )

  // a moderation model that flags nothing
  val moderationCalls = new AtomicInteger(0)
  val (moderationPort, _) = createTestServerWithRoutes("guardrails-accounting-moderation", routes => routes
    .post("/moderations", (req, response) => req.receive().aggregate().asString().flatMap { _ =>
      moderationCalls.incrementAndGet()
      send(response, Json.obj(
        "id" -> "modr-1", "model" -> "omni-moderation-latest",
        "results" -> Json.arr(Json.obj("flagged" -> false, "categories" -> Json.obj(), "category_scores" -> Json.obj())),
        "usage" -> Json.obj("prompt_tokens" -> moderationTokens, "total_tokens" -> moderationTokens),
      ))
    })
  )

  def ext: AiExtension = otoroshi.env.adminExtensions.extension[AiExtension].get

  def openai(name: String, port: Int, model: String, guardrails: Guardrails = Guardrails.empty, path: String = "/v1"): AiProvider = AiProvider(
    id = s"provider_${UUID.randomUUID()}",
    name = name,
    provider = "openai",
    connection = Json.obj("base_url" -> s"http://localhost:${port}${path}", "token" -> "sk-test", "timeout" -> 10000),
    options = Json.obj("model" -> model),
    guardrails = guardrails,
  )

  def guardedBy(id: String, config: JsObject, before: Boolean): Guardrails =
    Guardrails(Seq(GuardrailItem(enabled = true, before = before, after = !before, guardrailId = id, config = config)))

  lazy val moderation = ModerationModel(
    EntityLocation.default, s"moderation-model_${UUID.randomUUID()}", "moderation", "", Seq.empty, Map.empty, "openai",
    Json.obj("connection" -> Json.obj("base_url" -> s"http://localhost:${moderationPort}", "token" -> "sk-test", "timeout" -> 10000), "options" -> Json.obj("model" -> "omni-moderation-latest")),
  )
  lazy val judge = openai("judge", judgePort, judgeModel)
  lazy val faithfulnessJudge = openai("faithfulness judge", judgePort, judgeModel, path = "/faithfulness/v1")
  lazy val judgePrompt = Prompt(EntityLocation.default, s"prompt_${UUID.randomUUID()}", "judge prompt", "", Seq.empty, Map.empty, "Answer true when the messages are fine, false otherwise.")

  lazy val moderated = openai("moderated", chatPort, chatModel, guardedBy("moderation_model", Json.obj("moderation_model" -> moderation.id), before = true))
  // the two ways of having a chat model judge: one of the built-in judgements, before the call, and a prompt of
  // one's own, on the answer
  lazy val judgedBefore = openai("judged before", chatPort, chatModel, guardedBy("prompt_injection", Json.obj("provider" -> judge.id), before = true))
  lazy val judgedAfter = openai("judged after", chatPort, chatModel, guardedBy("llm", Json.obj("provider" -> judge.id, "prompt" -> judgePrompt.id), before = false))

  lazy val faithful = openai("faithful", chatPort, chatModel, guardedBy("faithfulness", Json.obj("ref" -> faithfulnessJudge.id, "context" -> Json.arr("Someone says hello."), "threshold" -> 0.5), before = true))

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
    assert(client.forLlmEntity("moderation-models").upsertEntity(moderation).awaitf(10.seconds).createdOrUpdated, "the moderation model should be saved")
    assert(client.forLlmEntity("prompts").upsertEntity(judgePrompt).awaitf(10.seconds).createdOrUpdated, "the prompt should be saved")
    Seq(judge, faithfulnessJudge, moderated, judgedBefore, judgedAfter, faithful).foreach { p =>
      assert(client.forLlmEntity("providers").upsertEntity(p).awaitf(10.seconds).createdOrUpdated, s"${p.name} should be saved")
    }
    Seq(moderation.id, judge.id, faithfulnessJudge.id, moderated.id, judgedBefore.id, judgedAfter.id, faithful.id).foreach { id =>
      client.forLlmEntity("ai-budgets").createRaw(budget(id)).awaitf(10.seconds)
    }
    await(10.seconds)
  }

  def cost(model: String, tokens: (Long, Long)): BigDecimal = ext.costsTracking.computeCosts("openai", model, tokens._1, tokens._2, 0L).toOption.get.totalCost

  def consumptions(scoped: String): AiBudgetConsumptions = {
    given env: Env = otoroshi.env
    ext.states.allBudgets().find(_.name == s"budget of ${scoped}").get.getConsumptions().awaitf(10.seconds)
  }

  // the attributes of the call are those of the request: the plugins read them once it is answered
  def chat(provider: AiProvider): TypedMap = {
    given env: Env = otoroshi.env
    val attrs = TypedMap.empty
    val resp = ext.states.provider(provider.id).flatMap(_.getChatClient()).get
      .call(ChatPrompt(Seq(ChatMessage.userStrInput("hello there"))), attrs, Json.obj())(using ec, env)
      .awaitf(30.seconds)
    assertEquals(resp.map(_.headGeneration.message.wholeTextContent), Right("hello"), s"the guarded provider should have answered")
    attrs
  }

  def assertChatCounted(provider: AiProvider, before: AiBudgetConsumptions, attrs: TypedMap): Unit = {
    val after = consumptions(provider.id)
    assertEquals(after.inferenceTokens - before.inferenceTokens, chatTokens._1 + chatTokens._2, s"the call should count for ${provider.name}, with its own tokens")
    assertEquals(after.inferenceUsd - before.inferenceUsd, cost(chatModel, chatTokens), "and its own price")
    // what the rest of the request reads: the token rate limit of the consumer, the response headers
    assertEquals(attrs.get(ChatClientWithAuding.ProviderKey).map(_.internalId), Some(provider.id), "the call is still the one of its provider")
    assertEquals(attrs.get(ChatClient.ApiUsageKey).map(_.usage.totalTokens), Some(chatTokens._1 + chatTokens._2), "with the usage of its own model")
  }

  test("a call guarded by a moderation model counts for its provider, the moderation for the moderation model") {
    setup
    val guarded = consumptions(moderated.id)
    val moderating = consumptions(moderation.id)
    val attrs = chat(moderated)
    assertEquals(moderationCalls.get(), 1)
    await(5.seconds)
    assertChatCounted(moderated, guarded, attrs)
    val after = consumptions(moderation.id)
    assertEquals(after.moderationTokens - moderating.moderationTokens, moderationTokens, "the moderation counts for the moderation model")
    assertEquals(after.inferenceTokens - moderating.inferenceTokens, 0L, "the chat call does not")
  }

  test("a call judged by a chat model before it is made counts for its provider, the judgement for the judge") {
    setup
    val guarded = consumptions(judgedBefore.id)
    val judging = consumptions(judge.id)
    val attrs = chat(judgedBefore)
    assertEquals(judgeCalls.get(), 1)
    await(5.seconds)
    assertChatCounted(judgedBefore, guarded, attrs)
    val after = consumptions(judge.id)
    assertEquals(after.inferenceTokens - judging.inferenceTokens, judgeTokens._1 + judgeTokens._2, "the judge is counted its own call, and nothing else")
    assertEquals(after.inferenceUsd - judging.inferenceUsd, cost(judgeModel, judgeTokens))
  }

  test("a call whose answer is judged by a chat model counts for its provider too") {
    setup
    val guarded = consumptions(judgedAfter.id)
    val judging = consumptions(judge.id)
    val attrs = chat(judgedAfter)
    assertEquals(judgeCalls.get(), 2)
    await(5.seconds)
    assertChatCounted(judgedAfter, guarded, attrs)
    val after = consumptions(judge.id)
    assertEquals(after.inferenceTokens - judging.inferenceTokens, judgeTokens._1 + judgeTokens._2, "the judge is counted its own call, and nothing else")
    assertEquals(after.inferenceUsd - judging.inferenceUsd, cost(judgeModel, judgeTokens))
  }

  test("a call whose faithfulness is judged by a chat model counts for its provider, the two judgements for the judge") {
    setup
    val guarded = consumptions(faithful.id)
    val judging = consumptions(faithfulnessJudge.id)
    val attrs = chat(faithful)
    assertEquals(faithfulnessCalls.get(), 2)
    await(5.seconds)
    assertChatCounted(faithful, guarded, attrs)
    val after = consumptions(faithfulnessJudge.id)
    assertEquals(after.inferenceTokens - judging.inferenceTokens, (judgeTokens._1 + judgeTokens._2) * 2, "the judge is counted its two calls, and nothing else")
  }
}
