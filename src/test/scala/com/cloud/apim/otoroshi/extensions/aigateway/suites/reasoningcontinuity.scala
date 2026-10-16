package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.entities.*
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatPrompt, InputChatMessage, LlmExtensionOneOtoroshiServerPerSuite}
import otoroshi.env.Env
import otoroshi.models.EntityLocation
import otoroshi.next.models.*
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.OpenAiCompatApi
import play.api.libs.json.*
import reactor.core.publisher.{Flux, Mono}
import reactor.netty.http.server.HttpServerResponse

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.DurationInt

// The reasoning of a model goes back to it on the next call (issue #228): the caller sends it on its assistant
// messages, in whatever field it got it, and each provider gets it the way its api takes it, without what that api
// can not read or refuses. The gateway answers it the way OpenRouter does (`reasoning`, `reasoning_details`), or as
// Anthropic blocks on `/messages`, or as reasoning items on `/responses`. When the gateway runs the tools itself, the
// reasoning that led to the calls goes back with them. One fake provider per api: a server of the suite takes about
// four calls.
class ReasoningContinuitySuite extends LlmExtensionOneOtoroshiServerPerSuite {

  def ext: AiExtension = otoroshi.env.adminExtensions.extension[AiExtension].get

  private def send(response: HttpServerResponse, body: JsValue) =
    response.status(200).addHeader("Content-Type", "application/json").sendString(Mono.just(body.stringify)).`then`()

  private def sendEvents(response: HttpServerResponse, events: Seq[String]) =
    response.status(200).addHeader("Content-Type", "text/event-stream").sendString(Flux.fromArray(events.toArray)).`then`()

  private def completion(message: JsObject, finishReason: String = "stop"): JsObject = Json.obj(
    "id" -> "chatcmpl-1", "object" -> "chat.completion", "created" -> 1700000000, "model" -> "a-model",
    "choices" -> Json.arr(Json.obj("index" -> 0, "finish_reason" -> finishReason, "message" -> (Json.obj("role" -> "assistant") ++ message))),
    "usage" -> Json.obj("prompt_tokens" -> 10, "completion_tokens" -> 5, "total_tokens" -> 15),
  )

  private def chunk(delta: JsObject, finishReason: Option[String] = None): String = "data: " + Json.obj(
    "id" -> "chatcmpl-1", "object" -> "chat.completion.chunk", "created" -> 1700000000, "model" -> "a-model",
    "choices" -> Json.arr(Json.obj("index" -> 0, "delta" -> delta, "finish_reason" -> finishReason.map(JsString.apply).getOrElse(JsNull).asValue)),
  ).stringify + "\n\n"

  private val usageChunk = "data: " + Json.obj("id" -> "chatcmpl-1", "object" -> "chat.completion.chunk", "created" -> 1700000000, "model" -> "a-model", "choices" -> Json.arr(),
    "usage" -> Json.obj("prompt_tokens" -> 10, "completion_tokens" -> 5, "total_tokens" -> 15)).stringify + "\n\n"

  private def anthropicEvent(data: JsObject): String = s"event: ${data.select("type").asString}\ndata: ${data.stringify}\n\n"

  // what a provider received on its last call
  class Upstream(name: String, path: String)(answer: (JsValue, HttpServerResponse) => org.reactivestreams.Publisher[Void]) {
    val received = new AtomicReference[JsValue](JsNull)
    val (port, _) = createTestServerWithRoutes(name, routes => routes.post(path, (req, response) => req.receive().aggregate().asString().flatMap { raw =>
      val body = Json.parse(raw)
      received.set(body)
      Mono.from(answer(body, response))
    }))
    def connection: JsObject = Json.obj("base_url" -> s"http://localhost:${port}/v1", "token" -> "sk-test", "timeout" -> 10000)
    def last: JsValue = received.get()
  }

  def streamed(body: JsValue): Boolean = body.select("stream").asOpt[Boolean].contains(true)
  // the name the gateway gave the tool it offers the model
  def toolName(body: JsValue): String = body.select("tools").asOpt[Seq[JsObject]].flatMap(_.headOption).flatMap(t => t.at("function.name").asOptString.orElse(t.select("name").asOptString)).getOrElse("get_time")
  def hasToolResult(body: JsValue): Boolean = body.select("messages").asOpt[Seq[JsObject]].getOrElse(Seq.empty).exists { m =>
    m.select("role").asOptString.contains("tool") || m.select("content").asOpt[Seq[JsObject]].exists(_.exists(_.select("type").asOptString.contains("tool_result")))
  }

  val signed = Json.obj("type" -> "reasoning.text", "text" -> "I think", "signature" -> "sig-1", "format" -> "anthropic-claude-v1", "index" -> 0)
  val plain = Json.obj("type" -> "reasoning.text", "text" -> "I also think", "format" -> "unknown", "index" -> 1)

  // OpenRouter: `reasoning` and `reasoning_details`, the details streamed in pieces
  val openrouter = new Upstream("reasoning-openrouter", "/v1/chat/completions")((body, response) =>
    if (streamed(body)) sendEvents(response, Seq(
      chunk(Json.obj("role" -> "assistant", "reasoning" -> "I ", "reasoning_details" -> Json.arr(Json.obj("type" -> "reasoning.text", "text" -> "I ", "format" -> "anthropic-claude-v1", "index" -> 0)))),
      chunk(Json.obj("reasoning" -> "think", "reasoning_details" -> Json.arr(Json.obj("type" -> "reasoning.text", "text" -> "think", "format" -> "anthropic-claude-v1", "index" -> 0)))),
      chunk(Json.obj("reasoning_details" -> Json.arr(Json.obj("type" -> "reasoning.text", "text" -> "", "signature" -> "sig-or", "format" -> "anthropic-claude-v1", "index" -> 0)))),
      chunk(Json.obj("content" -> "hello")),
      chunk(Json.obj(), Some("stop")),
      usageChunk,
      "data: [DONE]\n\n",
    ))
    else send(response, completion(Json.obj("content" -> "hello", "reasoning" -> "I think",
      "reasoning_details" -> Json.arr(Json.obj("type" -> "reasoning.text", "text" -> "I think", "signature" -> "sig-or", "format" -> "anthropic-claude-v1", "index" -> 0)))))
  )

  // DeepSeek: `reasoning_content`
  val deepseek = new Upstream("reasoning-deepseek", "/v1/chat/completions")((body, response) =>
    if (streamed(body)) sendEvents(response, Seq(
      chunk(Json.obj("role" -> "assistant", "reasoning_content" -> "hm")),
      chunk(Json.obj("reasoning_content" -> "m")),
      chunk(Json.obj("content" -> "hello")),
      chunk(Json.obj(), Some("stop")),
      usageChunk,
      "data: [DONE]\n\n",
    ))
    else send(response, completion(Json.obj("content" -> "hello", "reasoning_content" -> "hmm")))
  )

  val openai = new Upstream("reasoning-openai", "/v1/chat/completions")((_, response) => send(response, completion(Json.obj("content" -> "hello"))))

  // Mistral: `thinking` chunks of the content, signed
  val mistral = new Upstream("reasoning-mistral", "/v1/chat/completions")((_, response) => send(response, completion(Json.obj("content" -> Json.arr(
    Json.obj("type" -> "thinking", "thinking" -> Json.arr(Json.obj("type" -> "text", "text" -> "hmm")), "signature" -> "m-sig"),
    Json.obj("type" -> "text", "text" -> "hello"),
  )))))

  // Anthropic: `thinking` blocks
  val anthropic = new Upstream("reasoning-anthropic", "/v1/messages")((body, response) =>
    if (streamed(body)) sendEvents(response, Seq(
      anthropicEvent(Json.obj("type" -> "message_start", "message" -> Json.obj("id" -> "msg_1", "type" -> "message", "role" -> "assistant", "model" -> "claude-sonnet-5", "content" -> Json.arr(), "usage" -> Json.obj("input_tokens" -> 10, "output_tokens" -> 1)))),
      anthropicEvent(Json.obj("type" -> "content_block_start", "index" -> 0, "content_block" -> Json.obj("type" -> "thinking", "thinking" -> "", "signature" -> ""))),
      anthropicEvent(Json.obj("type" -> "content_block_delta", "index" -> 0, "delta" -> Json.obj("type" -> "thinking_delta", "thinking" -> "I "))),
      anthropicEvent(Json.obj("type" -> "content_block_delta", "index" -> 0, "delta" -> Json.obj("type" -> "thinking_delta", "thinking" -> "think"))),
      anthropicEvent(Json.obj("type" -> "content_block_delta", "index" -> 0, "delta" -> Json.obj("type" -> "signature_delta", "signature" -> "sig-3"))),
      anthropicEvent(Json.obj("type" -> "content_block_stop", "index" -> 0)),
      anthropicEvent(Json.obj("type" -> "content_block_start", "index" -> 1, "content_block" -> Json.obj("type" -> "text", "text" -> ""))),
      anthropicEvent(Json.obj("type" -> "content_block_delta", "index" -> 1, "delta" -> Json.obj("type" -> "text_delta", "text" -> "hello"))),
      anthropicEvent(Json.obj("type" -> "content_block_stop", "index" -> 1)),
      anthropicEvent(Json.obj("type" -> "message_delta", "delta" -> Json.obj("stop_reason" -> "end_turn"), "usage" -> Json.obj("output_tokens" -> 5))),
      anthropicEvent(Json.obj("type" -> "message_stop")),
    ))
    else send(response, Json.obj("id" -> "msg_1", "type" -> "message", "role" -> "assistant", "model" -> "claude-sonnet-5", "stop_reason" -> "end_turn",
      "content" -> Json.arr(Json.obj("type" -> "thinking", "thinking" -> "I think again", "signature" -> "sig-2"), Json.obj("type" -> "text", "text" -> "it is 10:42")),
      "usage" -> Json.obj("input_tokens" -> 10, "output_tokens" -> 5)))
  )

  // a native /responses api: reasoning items
  val responses = new Upstream("reasoning-responses", "/v1/responses")((_, response) => send(response, Json.obj(
    "id" -> "resp_1", "object" -> "response", "created_at" -> 1700000000L, "model" -> "gpt-5.6", "status" -> "completed",
    "output" -> Json.arr(
      Json.obj("type" -> "reasoning", "id" -> "rs_2", "summary" -> Json.arr(Json.obj("type" -> "summary_text", "text" -> "I thought again")), "encrypted_content" -> "gBBBB"),
      Json.obj("type" -> "message", "id" -> "msg_1", "status" -> "completed", "role" -> "assistant", "content" -> Json.arr(Json.obj("type" -> "output_text", "text" -> "it is 10:42", "annotations" -> Json.arr()))),
    ),
    "usage" -> Json.obj("input_tokens" -> 10, "output_tokens" -> 5, "total_tokens" -> 15),
  )))

  // the gateway runs the tools itself: the model calls one, then answers
  val deepseekTools = new Upstream("reasoning-deepseek-tools", "/v1/chat/completions")((body, response) =>
    if (hasToolResult(body)) send(response, completion(Json.obj("content" -> "it is 10:42", "reasoning_content" -> "now I know")))
    else send(response, completion(Json.obj("content" -> JsNull, "reasoning_content" -> "I need the time", "tool_calls" -> Json.arr(
      Json.obj("id" -> "call_1", "type" -> "function", "function" -> Json.obj("name" -> toolName(body), "arguments" -> "{}")),
    )), "tool_calls"))
  )
  val deepseekToolsStream = new Upstream("reasoning-deepseek-tools-stream", "/v1/chat/completions")((body, response) =>
    if (hasToolResult(body)) sendEvents(response, Seq(chunk(Json.obj("role" -> "assistant", "content" -> "it is 10:42")), chunk(Json.obj(), Some("stop")), usageChunk, "data: [DONE]\n\n"))
    else sendEvents(response, Seq(
      chunk(Json.obj("role" -> "assistant", "reasoning_content" -> "I need ")),
      chunk(Json.obj("reasoning_content" -> "the time")),
      chunk(Json.obj("tool_calls" -> Json.arr(Json.obj("index" -> 0, "id" -> "call_1", "type" -> "function", "function" -> Json.obj("name" -> toolName(body), "arguments" -> ""))))),
      chunk(Json.obj("tool_calls" -> Json.arr(Json.obj("index" -> 0, "function" -> Json.obj("arguments" -> "{}"))))),
      chunk(Json.obj(), Some("tool_calls")),
      usageChunk,
      "data: [DONE]\n\n",
    ))
  )
  val anthropicTools = new Upstream("reasoning-anthropic-tools", "/v1/messages")((body, response) =>
    send(response, Json.obj("id" -> "msg_1", "type" -> "message", "role" -> "assistant", "model" -> "claude-sonnet-5",
      "usage" -> Json.obj("input_tokens" -> 10, "output_tokens" -> 5)) ++ (if (hasToolResult(body)) Json.obj(
      "stop_reason" -> "end_turn", "content" -> Json.arr(Json.obj("type" -> "text", "text" -> "it is 10:42")))
    else Json.obj("stop_reason" -> "tool_use", "content" -> Json.arr(
      Json.obj("type" -> "thinking", "thinking" -> "I need the time", "signature" -> "sig-t"),
      Json.obj("type" -> "tool_use", "id" -> "toolu_1", "name" -> toolName(body), "input" -> Json.obj()),
    ))))
  )

  // a tool that tells the time, run by the gateway
  lazy val timeWorkflow = Json.obj(
    "id" -> s"workflow_${UUID.randomUUID()}", "name" -> "tells the time", "description" -> "tells the time",
    "config" -> Json.obj("id" -> "main", "kind" -> "workflow", "steps" -> Json.arr(), "returned" -> Json.obj("time" -> "10:42")),
  )
  lazy val timeTool = LlmToolFunction(
    id = s"tool-function_${UUID.randomUUID()}", name = "get_time", description = "tells the time", strict = false,
    parameters = Json.obj(),
    backend = LlmToolFunctionBackend(LlmToolFunctionBackendKind.Workflow, LlmToolFunctionBackendOptions.Workflow(Json.obj("workflow_id" -> timeWorkflow.select("id").asString), Json.obj())),
  )

  def provider(kind: String, upstream: Upstream, model: String, options: JsObject = Json.obj()): AiProvider = AiProvider(
    id = s"provider_${UUID.randomUUID()}", name = s"reasoning ${kind} ${model}", provider = kind,
    connection = upstream.connection, options = Json.obj("model" -> model) ++ options,
  )

  lazy val openrouterClaude = provider("openrouter", openrouter, "anthropic/claude-sonnet-5")
  lazy val openrouterKimi = provider("openrouter", openrouter, "moonshotai/kimi-k3")
  lazy val deepseekProvider = provider("deepseek", deepseek, "deepseek-v4-pro")
  lazy val openaiProvider = provider("openai", openai, "gpt-5.6")
  lazy val mistralProvider = provider("mistral", mistral, "mistral-medium-3-5")
  lazy val anthropicProvider = AiProvider(id = s"provider_${UUID.randomUUID()}", name = "reasoning anthropic", provider = "anthropic",
    connection = Json.obj("base_url" -> s"http://localhost:${anthropic.port}", "token" -> "sk-test", "timeout" -> 10000), options = Json.obj("model" -> "claude-sonnet-5", "max_tokens" -> 2048))
  lazy val responsesProvider = provider("openai", responses, "gpt-5.6", Json.obj("responses" -> true))
  lazy val deepseekToolsProvider = provider("deepseek", deepseekTools, "deepseek-v4-pro", Json.obj("wasm_tools" -> Json.arr(timeTool.id)))
  lazy val deepseekToolsStreamProvider = provider("deepseek", deepseekToolsStream, "deepseek-v4-pro", Json.obj("wasm_tools" -> Json.arr(timeTool.id)))
  lazy val anthropicToolsProvider = AiProvider(id = s"provider_${UUID.randomUUID()}", name = "reasoning anthropic tools", provider = "anthropic",
    connection = Json.obj("base_url" -> s"http://localhost:${anthropicTools.port}", "token" -> "sk-test", "timeout" -> 10000), options = Json.obj("model" -> "claude-sonnet-5", "max_tokens" -> 2048, "wasm_tools" -> Json.arr(timeTool.id)))

  def route(provider: AiProvider): NgRoute = NgRoute(
    location = EntityLocation.default,
    id = UUID.randomUUID().toString,
    name = s"reasoning ${provider.name}",
    description = "reasoning",
    tags = Seq.empty,
    metadata = Map.empty,
    enabled = true,
    debugFlow = false,
    capture = false,
    exportReporting = false,
    frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath(s"${provider.id.replace("_", "-")}.reasoning.oto.tools"))),
    backend = NgBackend.empty.copy(targets = Seq(NgTarget.default)),
    plugins = NgPlugins(Seq(NgPluginInstance(plugin = s"cp:${classOf[OpenAiCompatApi].getName}", config = NgPluginInstanceConfig(Json.obj("language_model_refs" -> Json.arr(provider.id)))))),
  )

  def url(provider: AiProvider, path: String): String = s"http://${provider.id.replace("_", "-")}.reasoning.oto.tools:${port}/v1${path}"

  lazy val setup: Unit = {
    assert(client.forEntity("plugins.otoroshi.io", "v1", "workflows").createRaw(timeWorkflow).awaitf(10.seconds).createdOrUpdated, "the workflow should be saved")
    assert(client.forLlmEntity("tool-functions").upsertEntity(timeTool).awaitf(10.seconds).createdOrUpdated, "the tool should be saved")
    val all = Seq(openrouterClaude, openrouterKimi, deepseekProvider, openaiProvider, mistralProvider, anthropicProvider, responsesProvider, deepseekToolsProvider, deepseekToolsStreamProvider, anthropicToolsProvider)
    all.foreach(p => assert(client.forLlmEntity("providers").upsertEntity(p).awaitf(10.seconds).createdOrUpdated, s"${p.name} should be saved"))
    all.foreach(p => client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(route(p)).awaitf(10.seconds))
    await(5.seconds)
  }

  def post(url: String, body: JsValue): (Int, JsValue) = {
    val resp = client.call("POST", url, Map.empty, Some(body)).awaitf(30.seconds)
    (resp.status, scala.util.Try(Json.parse(resp.body)).getOrElse(JsString(resp.body)))
  }

  def postEvents(url: String, body: JsValue): Seq[JsObject] = {
    val resp = client.call("POST", url, Map.empty, Some(body)).awaitf(30.seconds)
    assertEquals(resp.status, 200, resp.body)
    resp.body.split("\n").toSeq.filter(_.startsWith("data: ")).map(_.substring(6).trim).filter(d => d.nonEmpty && d != "[DONE]").map(d => Json.parse(d).asObject)
  }

  def call(provider: AiProvider, messages: JsObject*): JsValue = {
    given env: Env = otoroshi.env
    val resp = ext.states.provider(provider.id).flatMap(_.getChatClient()).get
      .call(ChatPrompt(messages.map(InputChatMessage.fromJson)), TypedMap.empty, Json.obj())(using ec, env)
      .awaitf(30.seconds)
    assert(resp.isRight, s"the call should succeed, got ${resp}")
    resp.toOption.get.headGeneration.message.json
  }

  def assistantMessages(body: JsValue): Seq[JsObject] = body.select("messages").as[Seq[JsObject]].filter(_.select("role").asOptString.contains("assistant"))
  def withoutIndex(details: Seq[JsObject]): Seq[JsObject] = details.map(_ - "index")

  val question = Json.obj("role" -> "user", "content" -> "what time is it ?")
  val again = Json.obj("role" -> "user", "content" -> "and now ?")

  // ---- OpenRouter ----------------------------------------------------------------------------

  test("OpenRouter gets the reasoning_details its model can read, and the caller gets them back the way OpenRouter answers") {
    setup
    val (status, answer) = post(url(openrouterClaude, "/chat/completions"), Json.obj("messages" -> Json.arr(
      question,
      Json.obj("role" -> "assistant", "content" -> "it is 10:41", "reasoning" -> "I think", "reasoning_details" -> Json.arr(signed, plain)),
      again,
    )))
    assertEquals(status, 200, answer.stringify)
    val sent = assistantMessages(openrouter.last).head
    assertEquals(withoutIndex(sent.select("reasoning_details").as[Seq[JsObject]]), Seq(signed - "index"), "Claude only reads what Anthropic signed")
    assertEquals(sent.keys.intersect(Set("reasoning", "reasoning_content")), Set.empty[String], "never a text OpenRouter would give Claude unsigned")
    val message = answer.at("choices.0.message").as[JsObject]
    assertEquals(message.select("content").asOpt[String], Some("hello"))
    assertEquals(message.select("reasoning").asOpt[String], Some("I think"))
    assertEquals(message.select("reasoning_content").asOpt[String], Some("I think"))
    assertEquals(message.at("reasoning_details.0.signature").asOpt[String], Some("sig-or"), "with what the provider needs back")
  }

  test("an open model behind OpenRouter gets the plain reasoning, not the one Anthropic signed") {
    setup
    call(openrouterKimi, question, Json.obj("role" -> "assistant", "content" -> "it is 10:41", "reasoning_details" -> Json.arr(signed, plain)), again)
    assertEquals(withoutIndex(assistantMessages(openrouter.last).head.select("reasoning_details").as[Seq[JsObject]]), Seq(plain - "index"))
  }

  test("a streamed answer carries its reasoning in pieces, with the signature the provider streamed last") {
    setup
    val chunks = postEvents(url(openrouterClaude, "/chat/completions"), Json.obj("stream" -> true, "messages" -> Json.arr(question)))
    val deltas = chunks.flatMap(_.select("choices").asOpt[Seq[JsObject]].getOrElse(Seq.empty)).map(_.select("delta").as[JsObject])
    assertEquals(deltas.flatMap(_.select("reasoning").asOpt[String]).mkString, "I think")
    assertEquals(deltas.flatMap(_.select("reasoning_content").asOpt[String]).mkString, "I think", "where the gateway always streamed it")
    assertEquals(deltas.flatMap(_.select("reasoning_details").asOpt[Seq[JsObject]].getOrElse(Seq.empty)).flatMap(_.select("signature").asOpt[String]), Seq("sig-or"))
    assertEquals(deltas.flatMap(_.select("content").asOpt[String]).mkString, "hello")
  }

  // ---- the other apis ------------------------------------------------------------------------

  test("DeepSeek gets reasoning_content on every assistant message, and its reasoning comes back as the others") {
    setup
    val answer = call(deepseekProvider,
      question, Json.obj("role" -> "assistant", "content" -> "it is 10:41", "reasoning_details" -> Json.arr(signed, plain)),
      again, Json.obj("role" -> "assistant", "content" -> "it is 10:42"),
      again,
    )
    val sent = assistantMessages(deepseek.last)
    assertEquals(sent.map(_.select("reasoning_content").asOpt[String]), Seq(Some("I think\n\nI also think"), Some("")), "the text of the reasoning, and an empty one rather than none")
    assertEquals(sent.flatMap(_.keys.intersect(Set("reasoning", "reasoning_details"))), Seq.empty)
    assertEquals(answer.select("reasoning").asOpt[String], Some("hmm"))
    assertEquals(withoutIndex(answer.select("reasoning_details").as[Seq[JsObject]]), Seq(Json.obj("type" -> "reasoning.text", "text" -> "hmm", "format" -> "unknown")))
  }

  test("OpenAI gets no reasoning on its chat api, which refuses the fields it does not know") {
    setup
    call(openaiProvider, question, Json.obj("role" -> "assistant", "content" -> "it is 10:41", "reasoning" -> "I think", "reasoning_content" -> "I think", "reasoning_details" -> Json.arr(signed, plain)), again)
    assertEquals(assistantMessages(openai.last), Seq(Json.obj("role" -> "assistant", "content" -> "it is 10:41")))
  }

  test("Mistral gets the reasoning as thinking chunks, and its signed thinking comes back with its signature") {
    setup
    val answer = call(mistralProvider, question, Json.obj("role" -> "assistant", "content" -> "it is 10:41", "reasoning_content" -> "I think"), again)
    assertEquals(assistantMessages(mistral.last), Seq(Json.obj("role" -> "assistant", "content" -> Json.arr(
      Json.obj("type" -> "thinking", "thinking" -> Json.arr(Json.obj("type" -> "text", "text" -> "I think"))),
      Json.obj("type" -> "text", "text" -> "it is 10:41"),
    ))), "Mistral refuses the fields it does not know")
    assertEquals(answer.select("content").asOpt[String], Some("hello"))
    assertEquals(withoutIndex(answer.select("reasoning_details").as[Seq[JsObject]]), Seq(Json.obj("type" -> "reasoning.text", "text" -> "hmm", "format" -> "unknown", "signature" -> "m-sig")))
  }

  // ---- Anthropic -----------------------------------------------------------------------------

  test("a conversation in the Anthropic format reaches Anthropic with its thinking blocks, and gets the new ones") {
    setup
    val (status, answer) = post(url(anthropicProvider, "/messages"), Json.obj(
      "max_tokens" -> 2048,
      "thinking" -> Json.obj("type" -> "adaptive"),
      "messages" -> Json.arr(
        question,
        Json.obj("role" -> "assistant", "content" -> Json.arr(
          Json.obj("type" -> "thinking", "thinking" -> "I think", "signature" -> "sig-1"),
          Json.obj("type" -> "text", "text" -> "let me check"),
          Json.obj("type" -> "tool_use", "id" -> "toolu_1", "name" -> "get_time", "input" -> Json.obj()),
        )),
        Json.obj("role" -> "user", "content" -> Json.arr(Json.obj("type" -> "tool_result", "tool_use_id" -> "toolu_1", "content" -> Json.arr(Json.obj("type" -> "text", "text" -> "10:42"))))),
      ),
    ))
    assertEquals(status, 200, answer.stringify)
    val sent = anthropic.last.select("messages").as[Seq[JsObject]]
    assertEquals(sent(1).select("content").as[Seq[JsObject]], Seq(
      Json.obj("type" -> "thinking", "thinking" -> "I think", "signature" -> "sig-1"),
      Json.obj("type" -> "text", "text" -> "let me check"),
      Json.obj("type" -> "tool_use", "id" -> "toolu_1", "name" -> "get_time", "input" -> Json.obj()),
    ), "the turn as Anthropic wrote it")
    assertEquals(sent(2).select("content").as[Seq[JsObject]], Seq(Json.obj("type" -> "tool_result", "tool_use_id" -> "toolu_1", "content" -> "10:42")))
    assertEquals(answer.select("content").as[Seq[JsObject]], Seq(
      Json.obj("type" -> "thinking", "thinking" -> "I think again", "signature" -> "sig-2"),
      Json.obj("type" -> "text", "text" -> "it is 10:42"),
    ))
  }

  test("a streamed answer of Anthropic reaches the caller as thinking, then text blocks") {
    setup
    val events = postEvents(url(anthropicProvider, "/messages"), Json.obj("max_tokens" -> 2048, "stream" -> true, "messages" -> Json.arr(question)))
    def describe(e: JsObject): String = e.select("type").asString + e.at("content_block.type").asOptString.map(t => s":${t}").getOrElse("") +
      e.at("delta.type").asOptString.map(t => s":${t}").getOrElse("") + e.select("index").asOpt[Int].map(i => s"@${i}").getOrElse("")
    assertEquals(events.map(describe), Seq(
      "message_start",
      "content_block_start:thinking@0", "content_block_delta:thinking_delta@0", "content_block_delta:thinking_delta@0", "content_block_delta:signature_delta@0", "content_block_stop@0",
      "content_block_start:text@1", "content_block_delta:text_delta@1", "content_block_stop@1",
      "message_delta", "message_stop",
    ))
    assertEquals(events.flatMap(_.at("delta.signature").asOpt[String]), Seq("sig-3"))
  }

  test("Anthropic gets back what it signed of a conversation in the OpenAI format") {
    setup
    val (status, answer) = post(url(anthropicProvider, "/chat/completions"), Json.obj("messages" -> Json.arr(
      question,
      Json.obj("role" -> "assistant", "content" -> "it is 10:41", "reasoning_details" -> Json.arr(signed, plain)),
      again,
    )))
    assertEquals(status, 200, answer.stringify)
    assertEquals(anthropic.last.select("messages").as[Seq[JsObject]].apply(1), Json.obj("role" -> "assistant", "content" -> Json.arr(
      Json.obj("type" -> "thinking", "thinking" -> "I think", "signature" -> "sig-1"),
      Json.obj("type" -> "text", "text" -> "it is 10:41"),
    )))
    assertEquals(answer.select("choices").as[Seq[JsObject]].size, 1, "one answer, its thinking included")
    assertEquals(answer.at("choices.0.message.reasoning_details.0.signature").asOpt[String], Some("sig-2"))
    assertEquals(answer.at("choices.0.message.reasoning").asOpt[String], Some("I think again"))
  }

  // ---- /responses ----------------------------------------------------------------------------

  test("a native /responses api gets back its reasoning items before the calls they led to") {
    setup
    val (status, answer) = post(url(responsesProvider, "/responses"), Json.obj("input" -> Json.arr(
      question,
      Json.obj("type" -> "reasoning", "id" -> "rs_1", "summary" -> Json.arr(Json.obj("type" -> "summary_text", "text" -> "I thought")), "encrypted_content" -> "gAAAA"),
      Json.obj("type" -> "function_call", "call_id" -> "call_1", "name" -> "get_time", "arguments" -> "{}"),
      Json.obj("type" -> "function_call_output", "call_id" -> "call_1", "output" -> "10:42"),
    )))
    assertEquals(status, 200, answer.stringify)
    val input = responses.last.select("input").as[Seq[JsObject]]
    assertEquals(input.map(_.select("type").asOptString.getOrElse("")), Seq("message", "reasoning", "function_call", "function_call_output"))
    assertEquals(input(1), Json.obj("type" -> "reasoning", "id" -> "rs_1", "summary" -> Json.arr(Json.obj("type" -> "summary_text", "text" -> "I thought")), "encrypted_content" -> "gAAAA"))
    assertEquals(answer.at("output.0.encrypted_content").asOpt[String], Some("gBBBB"), "and the caller gets the new ones")
  }

  test("a streamed answer on /responses is written as a reasoning item, then the message") {
    setup
    val events = postEvents(url(deepseekProvider, "/responses"), Json.obj("stream" -> true, "input" -> "what time is it ?"))
    val items = events.filter(_.select("type").asOptString.contains("response.output_item.done")).map(_.select("item").as[JsObject])
    assertEquals(items.map(_.select("type").asString), Seq("reasoning", "message"))
    assertEquals(items.head.select("content").as[Seq[JsObject]].map(_.select("text").asString), Seq("hmm"))
    assertEquals(events.filter(_.select("type").asOptString.contains("response.reasoning_text.delta")).map(_.select("delta").asString).mkString, "hmm")
    val completed = events.find(_.select("type").asOptString.contains("response.completed")).get
    assertEquals(completed.at("response.output").as[Seq[JsObject]].map(_.select("type").asString), Seq("reasoning", "message"))
  }

  // ---- tools run by the gateway --------------------------------------------------------------

  test("the tool calls the gateway runs go back to DeepSeek with the reasoning that led to them") {
    setup
    val answer = call(deepseekToolsProvider, question)
    assertEquals(answer.select("content").asOpt[String], Some("it is 10:42"))
    val sent = deepseekTools.last.select("messages").as[Seq[JsObject]]
    assertEquals(sent.map(_.select("role").asString), Seq("user", "assistant", "tool", "user"), "the call, its result (and the user message DeepSeek is given it again in)")
    assertEquals(sent(1).select("reasoning_content").asOpt[String], Some("I need the time"))
    assertEquals(sent(1).at("tool_calls.0.id").asOpt[String], Some("call_1"))
  }

  test("a streamed answer too") {
    setup
    given env: Env = otoroshi.env
    val source = ext.states.provider(deepseekToolsStreamProvider.id).flatMap(_.getChatClient()).get
      .stream(ChatPrompt(Seq(InputChatMessage.fromJson(question))), TypedMap.empty, Json.obj())(using ec, env)
      .awaitf(30.seconds).toOption.get
    val text = source.runWith(org.apache.pekko.stream.scaladsl.Sink.seq)(using otoroshi.env.otoroshiMaterializer).awaitf(30.seconds)
      .flatMap(_.choices.flatMap(_.delta.content)).mkString
    assertEquals(text, "it is 10:42")
    assertEquals(assistantMessages(deepseekToolsStream.last).head.select("reasoning_content").asOpt[String], Some("I need the time"))
  }

  test("the tool calls the gateway runs go back to Anthropic after the thinking that led to them") {
    setup
    val answer = call(anthropicToolsProvider, question)
    assertEquals(answer.select("content").asOpt[String], Some("it is 10:42"))
    val sent = anthropicTools.last.select("messages").as[Seq[JsObject]]
    assertEquals(sent.map(_.select("role").asString), Seq("user", "assistant", "user"))
    assertEquals(sent(1).select("content").as[Seq[JsObject]].map(_.select("type").asString), Seq("thinking", "tool_use"), "Anthropic refuses the call otherwise")
    assertEquals(sent(1).at("content.0.signature").asOpt[String], Some("sig-t"))
    assertEquals(sent(2).at("content.0.type").asOpt[String], Some("tool_result"))
  }
}
