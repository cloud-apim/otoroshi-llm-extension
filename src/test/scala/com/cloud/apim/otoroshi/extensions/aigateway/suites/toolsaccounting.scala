package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.decorators.{ChatClientWithCostsTracking, ChatClientWithEcoImpact}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.*
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatMessage, ChatPrompt, LlmExtensionOneOtoroshiServerPerSuite}
import org.apache.pekko.stream.scaladsl.Sink
import otoroshi.env.Env
import otoroshi.models.EntityLocation
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.*
import reactor.core.publisher.{Flux, Mono}
import reactor.netty.http.server.HttpServerResponse

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.DurationInt

// A tool of a chat call may be a workflow, and a workflow may call the models of the gateway. What such a model
// costs and consumes is its own business: it is counted for that model. The chat call that ran the tool stays
// what it is, with the cost and the footprint of its own model, and none when nobody knows them.
class ToolsAccountingSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  val embeddingTokens = 1000L

  private def send(response: HttpServerResponse, body: JsValue) =
    response.status(200).addHeader("Content-Type", "application/json").sendString(Mono.just(body.stringify)).`then`()

  private def completion(model: String, message: JsObject, finishReason: String, promptTokens: Long, completionTokens: Long): JsObject = Json.obj(
    "id" -> "chatcmpl-1", "object" -> "chat.completion", "created" -> 1700000000, "model" -> model,
    "choices" -> Json.arr(Json.obj("index" -> 0, "finish_reason" -> finishReason, "message" -> message)),
    "usage" -> Json.obj("prompt_tokens" -> promptTokens, "completion_tokens" -> completionTokens, "total_tokens" -> (promptTokens + completionTokens)),
  )

  private def chunk(model: String, choices: JsArray, usage: Option[(Long, Long)] = None): String = "data: " + (Json.obj(
    "id" -> "chatcmpl-1", "object" -> "chat.completion.chunk", "created" -> 1700000000, "model" -> model, "choices" -> choices,
  ) ++ usage.map { case (prompt, completion) => Json.obj("usage" -> Json.obj("prompt_tokens" -> prompt, "completion_tokens" -> completion, "total_tokens" -> (prompt + completion))) }.getOrElse(Json.obj())).stringify + "\n\n"

  private def sendChunks(response: HttpServerResponse, chunks: String*) =
    response.status(200).addHeader("Content-Type", "text/event-stream").sendString(Flux.fromArray((chunks :+ "data: [DONE]\n\n").toArray)).`then`()

  // Models that call the tool they were given, then answer; a model that was given none answers at once.
  // One server for each case: a server of the suite does not take many calls
  class Models(name: String) {
    val chatCalls = new AtomicInteger(0)
    val embeddingCalls = new AtomicInteger(0)
    val (port, _) = createTestServerWithRoutes(name, routes => routes
      .post("/v1/chat/completions", (req, response) => req.receive().aggregate().asString().flatMap { raw =>
        chatCalls.incrementAndGet()
        val body = Json.parse(raw)
        val model = body.select("model").asString
        val streamed = body.select("stream").asOpt[Boolean].contains(true)
        val ranItsTool = body.select("messages").asOpt[Seq[JsObject]].getOrElse(Seq.empty).exists(_.select("role").asOptString.contains("tool"))
        val tool = body.select("tools").asOpt[Seq[JsObject]].flatMap(_.headOption).map(_.at("function.name").asString)
        val arguments = Json.obj("question" -> "what is otoroshi ?").stringify
        (tool, ranItsTool, streamed) match {
          case (Some(name), false, false) =>
            send(response, completion(model, Json.obj("role" -> "assistant", "content" -> JsNull, "tool_calls" -> Json.arr(Json.obj(
              "id" -> "call_1", "type" -> "function", "function" -> Json.obj("name" -> name, "arguments" -> arguments),
            ))), "tool_calls", 10, 5))
          case (Some(name), false, true) =>
            sendChunks(response,
              chunk(model, Json.arr(Json.obj("index" -> 0, "finish_reason" -> JsNull, "delta" -> Json.obj("role" -> "assistant", "tool_calls" -> Json.arr(Json.obj(
                "index" -> 0, "id" -> "call_1", "type" -> "function", "function" -> Json.obj("name" -> name, "arguments" -> ""),
              )))))),
              chunk(model, Json.arr(Json.obj("index" -> 0, "finish_reason" -> JsNull, "delta" -> Json.obj("tool_calls" -> Json.arr(Json.obj(
                "index" -> 0, "function" -> Json.obj("arguments" -> arguments),
              )))))),
              chunk(model, Json.arr(Json.obj("index" -> 0, "finish_reason" -> "tool_calls", "delta" -> Json.obj()))),
              chunk(model, Json.arr(), Some((10L, 5L))),
            )
          case (_, true, true) =>
            sendChunks(response,
              chunk(model, Json.arr(Json.obj("index" -> 0, "finish_reason" -> JsNull, "delta" -> Json.obj("role" -> "assistant", "content" -> "hello")))),
              chunk(model, Json.arr(Json.obj("index" -> 0, "finish_reason" -> "stop", "delta" -> Json.obj()))),
              chunk(model, Json.arr(), Some((100L, 50L))),
            )
          case (_, true, false) => send(response, completion(model, Json.obj("role" -> "assistant", "content" -> "hello"), "stop", 100, 50))
          case _ => send(response, completion(model, Json.obj("role" -> "assistant", "content" -> "an answer for the tool"), "stop", 20, 10))
        }
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
  }

  val embedding = new Models("tools-accounting-embedding")
  val chatting = new Models("tools-accounting-chat")
  val streaming = new Models("tools-accounting-stream")

  def ext: AiExtension = otoroshi.env.adminExtensions.extension[AiExtension].get

  // the models a tool asks: both have a price, and the footprint of the chat one is known
  lazy val embeddingModel = EmbeddingModel(
    EntityLocation.default, s"embedding-model_${UUID.randomUUID()}", "priced embeddings", "", Seq.empty, Map.empty, "openai",
    Json.obj("connection" -> embedding.connection, "options" -> Json.obj("model" -> "text-embedding-3-small")),
  )
  lazy val asked = AiProvider(
    id = s"provider_${UUID.randomUUID()}", name = "asked by a tool", provider = "openai",
    connection = chatting.connection, options = Json.obj("model" -> "gpt-4o"),
  )

  // a tool that is a workflow of one step: a call to a model of the gateway
  def workflow(function: String, args: JsObject): JsObject = Json.obj(
    "id" -> s"workflow_${UUID.randomUUID()}",
    "name" -> s"asks ${function}",
    "description" -> s"asks ${function}",
    "config" -> Json.obj(
      "id" -> "main",
      "kind" -> "workflow",
      "steps" -> Json.arr(Json.obj("id" -> "ask", "kind" -> "call", "function" -> function, "args" -> args, "result" -> "asked")),
      "returned" -> Json.obj("done" -> true),
    ),
  )
  def tool(workflow: JsObject): LlmToolFunction = LlmToolFunction(
    id = s"tool-function_${UUID.randomUUID()}",
    name = "lookup",
    description = "looks a question up",
    strict = false,
    parameters = Json.obj("question" -> Json.obj("type" -> "string", "description" -> "the question")),
    backend = LlmToolFunctionBackend(
      kind = LlmToolFunctionBackendKind.Workflow,
      options = LlmToolFunctionBackendOptions.Workflow(Json.obj("workflow_id" -> workflow.select("id").asString), Json.obj()),
    ),
  )
  lazy val embeds = workflow("extensions.com.cloud-apim.llm-extension.compute_embedding",
    Json.obj("provider" -> embeddingModel.id, "payload" -> Json.obj("input" -> Json.arr("what is otoroshi ?"))))
  lazy val asks = workflow("extensions.com.cloud-apim.llm-extension.llm_call",
    Json.obj("provider" -> asked.id, "payload" -> Json.obj("messages" -> Json.arr(Json.obj("role" -> "user", "content" -> "what is otoroshi ?")))))
  lazy val embeddingTool = tool(embeds)
  lazy val askingTool = tool(asks)

  // the chat models that run these tools: nobody knows what they cost, nor what their footprint is
  val unknownModel = "a-model-nobody-knows"
  lazy val selfHosted = AiProvider(
    id = s"provider_${UUID.randomUUID()}", name = "self hosted, embeds", provider = "openai-compatible",
    connection = embedding.connection, options = Json.obj("model" -> unknownModel, "wasm_tools" -> Json.arr(embeddingTool.id)),
  )
  lazy val selfHostedStreaming = selfHosted.copy(id = s"provider_${UUID.randomUUID()}", name = "self hosted, embeds, streams", connection = streaming.connection)
  lazy val unknown = AiProvider(
    id = s"provider_${UUID.randomUUID()}", name = "unknown model, asks", provider = "openai",
    connection = chatting.connection, options = Json.obj("model" -> unknownModel, "wasm_tools" -> Json.arr(askingTool.id)),
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
    Seq(embeds, asks).foreach(w => assert(client.forEntity("plugins.otoroshi.io", "v1", "workflows").createRaw(w).awaitf(10.seconds).createdOrUpdated, "the workflow should be saved"))
    Seq(embeddingTool, askingTool).foreach(t => assert(client.forLlmEntity("tool-functions").upsertEntity(t).awaitf(10.seconds).createdOrUpdated, "the tool should be saved"))
    Seq(asked, selfHosted, selfHostedStreaming, unknown).foreach { p =>
      assert(client.forLlmEntity("providers").upsertEntity(p).awaitf(10.seconds).createdOrUpdated, s"${p.name} should be saved")
    }
    Seq(embeddingModel.id, asked.id, selfHosted.id, selfHostedStreaming.id, unknown.id).foreach(id => client.forLlmEntity("ai-budgets").createRaw(budget(id)).awaitf(10.seconds))
    await(10.seconds)
  }

  def consumptions(scoped: String): AiBudgetConsumptions = {
    given env: Env = otoroshi.env
    ext.states.allBudgets().find(_.name == s"budget of ${scoped}").get.getConsumptions().awaitf(10.seconds)
  }

  val prompt = ChatPrompt(Seq(ChatMessage.userStrInput("what is otoroshi ?")))

  // the chat call kept nothing of what its tool spent: no cost, no footprint, and its provider was charged nothing
  def assertLeftAlone(provider: AiProvider, before: AiBudgetConsumptions, attrs: TypedMap, chatTokens: Long): Unit = {
    val after = consumptions(provider.id)
    assertEquals(after.inferenceTokens - before.inferenceTokens, chatTokens, "the chat call counts for its provider")
    assertEquals(after.totalTokens - before.totalTokens, chatTokens, "and nothing else does")
    assertEquals(after.totalUsd - before.totalUsd, BigDecimal(0), "its provider is not charged for what the tool spent")
    assertEquals(attrs.get(ChatClientWithCostsTracking.key).map(_.totalCost), None, "a chat model without a price has no cost, whatever its tool spent")
    assertEquals(attrs.get(ChatClientWithEcoImpact.key).map(_.json(false)), None, "nor a footprint")
  }

  test("an embedding asked by the tool of a chat call is counted for its model, and leaves the chat call without a cost") {
    setup
    given env: Env = otoroshi.env
    val before = consumptions(selfHosted.id)
    val embeddingBefore = consumptions(embeddingModel.id)
    val attrs = TypedMap.empty
    val resp = ext.states.provider(selfHosted.id).flatMap(_.getChatClient()).get.call(prompt, attrs, Json.obj())(using ec, env).awaitf(30.seconds)
    assertEquals(resp.map(_.headGeneration.message.wholeTextContent), Right("hello"), "the model should have answered after its tool")
    assertEquals((embedding.chatCalls.get(), embedding.embeddingCalls.get()), (2, 1), "the model was called, its tool embedded the question, and it was called again")
    await(5.seconds)
    val embedded = consumptions(embeddingModel.id)
    assertEquals(embedded.embeddingTokens - embeddingBefore.embeddingTokens, embeddingTokens, "the embedding counts for the embedding model")
    assert(embedded.embeddingUsd > embeddingBefore.embeddingUsd, "with its price")
    assertEquals(embedded.inferenceTokens - embeddingBefore.inferenceTokens, 0L, "the chat call does not")
    assertLeftAlone(selfHosted, before, attrs, resp.toOption.get.metadata.usage.totalTokens)
  }

  test("a chat model asked by the tool of a chat call is counted for its provider, and leaves the chat call without a cost nor a footprint") {
    setup
    given env: Env = otoroshi.env
    assert(ext.llmImpacts.canHandle("openai", "gpt-4o"), "precondition: the footprint of the model the tool asks is known")
    assert(!ext.llmImpacts.canHandle("openai", unknownModel), "precondition: the one of the model that runs the tool is not")
    val before = consumptions(unknown.id)
    val askedBefore = consumptions(asked.id)
    val attrs = TypedMap.empty
    val resp = ext.states.provider(unknown.id).flatMap(_.getChatClient()).get.call(prompt, attrs, Json.obj())(using ec, env).awaitf(30.seconds)
    assertEquals(resp.map(_.headGeneration.message.wholeTextContent), Right("hello"), "the model should have answered after its tool")
    assertEquals(chatting.chatCalls.get(), 3, "the model was called, its tool asked another model, and it was called again")
    await(5.seconds)
    val askedAfter = consumptions(asked.id)
    assertEquals(askedAfter.inferenceTokens - askedBefore.inferenceTokens, 30L, "the model the tool asked counts for its provider")
    assert(askedAfter.inferenceUsd > askedBefore.inferenceUsd, "with its price")
    assertLeftAlone(unknown, before, attrs, resp.toOption.get.metadata.usage.totalTokens)
  }

  test("a stream that runs such a tool ends without a cost either") {
    setup
    given env: Env = otoroshi.env
    val before = consumptions(selfHostedStreaming.id)
    val embeddingBefore = consumptions(embeddingModel.id)
    val embeddedBefore = embedding.embeddingCalls.get()
    val attrs = TypedMap.empty
    val chunks = ext.states.provider(selfHostedStreaming.id).flatMap(_.getChatClient()).get.stream(prompt, attrs, Json.obj())(using ec, env).awaitf(30.seconds)
      .map(_.runWith(Sink.seq)(using mat).awaitf(30.seconds))
    assert(chunks.isRight, s"the stream should have run, got ${chunks}")
    assertEquals(chunks.toOption.get.flatMap(_.choices.flatMap(_.delta.content)).mkString, "hello", "the model should have answered after its tool")
    assertEquals((streaming.chatCalls.get(), embedding.embeddingCalls.get() - embeddedBefore), (2, 1), "the model was called, its tool embedded the question, and it was called again")
    await(5.seconds)
    assertEquals(consumptions(embeddingModel.id).embeddingTokens - embeddingBefore.embeddingTokens, embeddingTokens, "the embedding counts for the embedding model")
    // what the two calls of the stream consumed: 10 + 5, then 100 + 50
    assertLeftAlone(selfHostedStreaming, before, attrs, 165L)
  }
}
