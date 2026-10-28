package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.decorators.ChatClientWithCostsTracking
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.providers.UsageAccumulator
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatClient, ChatMessage, ChatPrompt, ChatResponseMetadataUsage, LlmExtensionOneOtoroshiServerPerSuite}
import org.apache.pekko.stream.scaladsl.Sink
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.*
import reactor.core.publisher.{Flux, Mono}

import java.util.UUID
import scala.concurrent.duration.DurationInt

// The output of a call includes its reasoning, the way OpenAI counts it: `reasoning_tokens` only says how many of the
// output tokens were spent thinking. Totals, budgets, rate limits and prices count them once. A provider counting the
// reasoning apart from the output (xAI, the OpenAI api of Gemini) says so with its total, and its output gets the
// reasoning back
class ReasoningTokensSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  // a Mistral answer: 92 output tokens, 88 of them thinking
  val inclusive = Json.obj("prompt_tokens" -> 345, "completion_tokens" -> 92, "total_tokens" -> 437, "completion_tokens_details" -> Json.obj("reasoning_tokens" -> 88))
  // an xAI answer: 9 output tokens, and 94 more spent thinking
  val exclusive = Json.obj("prompt_tokens" -> 32, "completion_tokens" -> 9, "total_tokens" -> 135, "completion_tokens_details" -> Json.obj("reasoning_tokens" -> 94))

  private def chunk(choices: JsArray, usage: Option[JsObject] = None): String = "data: " + (Json.obj(
    "id" -> "chatcmpl-1", "object" -> "chat.completion.chunk", "created" -> 1700000000, "model" -> "a-model", "choices" -> choices,
  ) ++ usage.map(u => Json.obj("usage" -> u)).getOrElse(Json.obj())).stringify + "\n\n"

  val (serverPort, _) = createTestServerWithRoutes("reasoning-tokens", routes => routes
    .post("/v1/chat/completions", (req, response) => req.receive().aggregate().asString().flatMap { raw =>
      val body = Json.parse(raw)
      val usage = if (body.select("model").asOptString.contains("exclusive")) exclusive else inclusive
      if (body.select("stream").asOpt[Boolean].contains(true)) {
        response.status(200).addHeader("Content-Type", "text/event-stream").sendString(Flux.just(
          chunk(Json.arr(Json.obj("index" -> 0, "delta" -> Json.obj("role" -> "assistant", "content" -> "hello"), "finish_reason" -> JsNull))),
          chunk(Json.arr(Json.obj("index" -> 0, "delta" -> Json.obj(), "finish_reason" -> "stop"))),
          chunk(Json.arr(), Some(usage)),
          "data: [DONE]\n\n",
        )).`then`()
      } else {
        response.status(200).addHeader("Content-Type", "application/json").sendString(Mono.just(Json.obj(
          "id" -> "chatcmpl-1", "object" -> "chat.completion", "created" -> 1700000000, "model" -> "a-model",
          "choices" -> Json.arr(Json.obj("index" -> 0, "finish_reason" -> "stop", "message" -> Json.obj("role" -> "assistant", "content" -> "hello"))),
          "usage" -> usage,
        ).stringify)).`then`()
      }
    })
  )

  def ext: AiExtension = otoroshi.env.adminExtensions.extension[AiExtension].get

  // priced as a model without a price of its own for reasoning: it is output, priced as such
  def provider(model: String): AiProvider = AiProvider(
    id = s"provider_${UUID.randomUUID()}", name = s"$model provider", provider = "openai-compatible",
    metadata = Map("costs-tracking-provider" -> "openai", "costs-tracking-model" -> "gpt-4o-mini"),
    connection = Json.obj("base_url" -> s"http://localhost:${serverPort}/v1", "token" -> "sk-test", "timeout" -> 10000),
    options = Json.obj("model" -> model),
  )

  lazy val providers: (AiProvider, AiProvider) = {
    val (inc, exc) = (provider("inclusive"), provider("exclusive"))
    Seq(inc, exc).foreach(p => assert(client.forLlmEntity("providers").upsertEntity(p).awaitf(10.seconds).createdOrUpdated, s"${p.name} should be saved"))
    await(5.seconds)
    (inc, exc)
  }

  val prompt = ChatPrompt(Seq(ChatMessage.userStrInput("hello")))

  // what the call costs when every output token is priced once
  def priced(input: Long, output: Long): BigDecimal = {
    val model = ext.costsTracking.getModel("openai", "gpt-4o-mini").get
    assertEquals(model.output_cost_per_reasoning_token, BigDecimal(0), "the model should have no reasoning price")
    input * model.input_cost_per_token + output * model.output_cost_per_token
  }

  def call(p: AiProvider): (ChatResponseMetadataUsage, Option[BigDecimal]) = {
    given env: Env = otoroshi.env
    val attrs = TypedMap.empty
    val resp = ext.states.provider(p.id).flatMap(_.getChatClient()).get.call(prompt, attrs, Json.obj())(using ec, env).awaitf(30.seconds)
    assert(resp.isRight, s"the call should succeed, got ${resp}")
    (resp.toOption.get.metadata.usage, attrs.get(ChatClientWithCostsTracking.key).map(_.totalCost))
  }

  def stream(p: AiProvider): (Option[ChatResponseMetadataUsage], Option[BigDecimal]) = {
    given env: Env = otoroshi.env
    val attrs = TypedMap.empty
    val source = ext.states.provider(p.id).flatMap(_.getChatClient()).get.stream(prompt, attrs, Json.obj())(using ec, env).awaitf(30.seconds)
    assert(source.isRight, s"the stream should start, got ${source}")
    source.toOption.get.runWith(Sink.ignore)(using mat).awaitf(30.seconds)
    await(1.second)
    (attrs.get(ChatClient.ApiUsageKey).map(_.usage), attrs.get(ChatClientWithCostsTracking.key).map(_.totalCost))
  }

  test("the reasoning is a part of the output, unless the total says it is not") {
    assertEquals(ChatResponseMetadataUsage(345, 92, 88).totalTokens, 437L)
    assertEquals(ChatResponseMetadataUsage.outputWithReasoning(345, 92, 88, Some(437)), 92L)
    assertEquals(ChatResponseMetadataUsage.outputWithReasoning(32, 9, 94, Some(135)), 103L)
    assertEquals(ChatResponseMetadataUsage.outputWithReasoning(32, 9, 94, None), 9L, "without a total, the output is taken as it is")
    val acc = new UsageAccumulator()
    acc.updateOpenai(Some(exclusive))
    assertEquals((acc.usage().generationTokens, acc.usage().reasoningTokens, acc.usage().totalTokens), (103L, 94L, 135L))
  }

  test("a reasoning price applies to the reasoning part of the output only") {
    val model = ext.costsTracking.getModel("dashscope", "qwen-turbo").get
    assert(model.output_cost_per_reasoning_token > model.output_cost_per_token, "the model should price its reasoning apart")
    val costs = ext.costsTracking.computeCosts("dashscope", "qwen-turbo", 345, 92, 88).toOption.get
    assertEquals(costs.outputCost, 4 * model.output_cost_per_token)
    assertEquals(costs.reasoningCost, 88 * model.output_cost_per_reasoning_token)
    assertEquals(costs.totalCost, 345 * model.input_cost_per_token + 4 * model.output_cost_per_token + 88 * model.output_cost_per_reasoning_token)
  }

  test("an answer counting its reasoning in its output is counted and priced once") {
    val (inc, _) = providers
    val (usage, cost) = call(inc)
    assertEquals((usage.promptTokens, usage.generationTokens, usage.reasoningTokens, usage.totalTokens), (345L, 92L, 88L, 437L))
    assertEquals(cost, Some(priced(345, 92)))
    val (streamed, streamedCost) = stream(inc)
    assertEquals(streamed.map(u => (u.generationTokens, u.reasoningTokens, u.totalTokens)), Some((92L, 88L, 437L)))
    assertEquals(streamedCost, Some(priced(345, 92)))
  }

  test("an answer counting its reasoning apart gets it back in its output") {
    val (_, exc) = providers
    val (usage, cost) = call(exc)
    assertEquals((usage.promptTokens, usage.generationTokens, usage.reasoningTokens, usage.totalTokens), (32L, 103L, 94L, 135L))
    assertEquals(cost, Some(priced(32, 103)))
    val (streamed, streamedCost) = stream(exc)
    assertEquals(streamed.map(u => (u.generationTokens, u.reasoningTokens, u.totalTokens)), Some((103L, 94L, 135L)))
    assertEquals(streamedCost, Some(priced(32, 103)))
  }
}
