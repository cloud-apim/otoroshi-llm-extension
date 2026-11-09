package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.decorators.LlmCallTelemetry
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.*
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.util.ByteString
import otoroshi.models.EntityLocation
import otoroshi.next.models.*
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.{AnthropicCompatProxy, AnthropicStreamWriter, OpenAiCompatProxy, OpenAiResponsesProxy, OpenResponseCompatProxy}
import play.api.libs.json.*
import reactor.core.publisher.{Flux, Mono}

import java.util.UUID
import scala.concurrent.duration.DurationInt

// Why an answer ended is what tells a caller it may trust it: `length` says it was cut, `content_filter` that it
// was filtered. The provider says it in its own words, for each generation or for the whole answer; the caller is
// answered in the words of the format it called, whether the answer was streamed or not.
class FinishReasonSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  // the last user message says how the answer ends: `length`, `content_filter`, `stop`, or `tools`, a tool call
  // a server says ended on `stop`
  val (providerPort, _) = createTestServerWithRoutes("finish-reason", routes => routes
    .post("/v1/chat/completions", (req, response) => req.receive().aggregate().asString().flatMap { body =>
      val last = Json.parse(body).select("messages").asOpt[Seq[JsObject]].flatMap(_.lastOption).getOrElse(Json.obj())
      val asked = last.select("content").asOpt[String]
        .orElse(last.select("content").asOpt[Seq[JsObject]].map(_.flatMap(_.select("text").asOpt[String]).mkString))
        .getOrElse("stop").trim
      val (reason, message) = asked match {
        case "tools" => ("stop", Json.obj("role" -> "assistant", "content" -> "", "tool_calls" -> Json.arr(Json.obj(
          "id" -> "call_1", "type" -> "function", "function" -> Json.obj("name" -> "get_weather", "arguments" -> """{"city":"Paris"}""")
        ))))
        case other => (other, Json.obj("role" -> "assistant", "content" -> "an answer cut sh"))
      }
      response.status(200).addHeader("Content-Type", "application/json").sendString(Mono.just(Json.obj(
        "id" -> "chatcmpl-1", "object" -> "chat.completion", "created" -> 1700000000, "model" -> "a-model",
        "choices" -> Json.arr(Json.obj("index" -> 0, "finish_reason" -> reason, "message" -> message)),
        "usage" -> Json.obj("prompt_tokens" -> 1, "completion_tokens" -> 20, "total_tokens" -> 21),
      ).stringify)).`then`()
    })
  )

  // a provider answering natively on `/responses`: an answer cut by `max_output_tokens` is `incomplete`
  val cutResponse: JsObject = Json.obj(
    "id" -> "resp_1", "object" -> "response", "created_at" -> 1700000000L, "model" -> "a-model",
    "status" -> "incomplete", "incomplete_details" -> Json.obj("reason" -> "max_output_tokens"),
    "output" -> Json.arr(Json.obj("type" -> "message", "id" -> "msg_1", "status" -> "incomplete", "role" -> "assistant",
      "content" -> Json.arr(Json.obj("type" -> "output_text", "text" -> "an answer cut sh", "annotations" -> Json.arr())))),
    "usage" -> Json.obj("input_tokens" -> 1, "output_tokens" -> 20, "total_tokens" -> 21),
  )
  private def responsesEvent(data: JsObject): String = s"event: ${data.select("type").asString}\ndata: ${data.stringify}\n\n"
  val (nativePort, _) = createTestServerWithRoutes("finish-reason-native", routes => routes
    .post("/v1/responses", (req, response) => req.receive().aggregate().asString().flatMap { body =>
      if (Json.parse(body).select("stream").asOpt[Boolean].contains(true)) {
        response.status(200).addHeader("Content-Type", "text/event-stream").sendString(Flux.just(
          responsesEvent(Json.obj("type" -> "response.created", "response" -> (cutResponse ++ Json.obj("status" -> "in_progress", "output" -> Json.arr(), "usage" -> JsNull)))),
          responsesEvent(Json.obj("type" -> "response.output_text.delta", "item_id" -> "msg_1", "output_index" -> 0, "content_index" -> 0, "delta" -> "an answer cut sh")),
          responsesEvent(Json.obj("type" -> "response.incomplete", "response" -> cutResponse)),
        )).`then`()
      } else response.status(200).addHeader("Content-Type", "application/json").sendString(Mono.just(cutResponse.stringify)).`then`()
    })
  )
  lazy val nativeProvider: AiProvider = AiProvider(
    id = s"provider_${UUID.randomUUID()}",
    name = "finish reason native responses",
    provider = "openai",
    connection = Json.obj("base_url" -> s"http://localhost:${nativePort}/v1", "token" -> "sk-test", "timeout" -> 10000),
    options = Json.obj("model" -> "a-model", "responses" -> true),
  )

  // a self hosted OpenAI compatible server, as the `openai` provider with a base url of its own
  lazy val provider: AiProvider = AiProvider(
    id = s"provider_${UUID.randomUUID()}",
    name = "finish reason",
    provider = "openai",
    connection = Json.obj("base_url" -> s"http://localhost:${providerPort}/v1", "token" -> "sk-test", "timeout" -> 10000),
    options = Json.obj("model" -> "a-model"),
  )

  def route(name: String, plugin: Class[?], target: AiProvider = provider): NgRoute = NgRoute(
    location = EntityLocation.default,
    id = UUID.randomUUID().toString,
    name = s"finish reason ${name}",
    description = s"finish reason ${name}",
    tags = Seq.empty,
    metadata = Map.empty,
    enabled = true,
    debugFlow = false,
    capture = false,
    exportReporting = false,
    frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath(s"${name}.finishreason.oto.tools"))),
    backend = NgBackend.empty.copy(targets = Seq(NgTarget.default)),
    plugins = NgPlugins(Seq(NgPluginInstance(
      plugin = s"cp:${plugin.getName}",
      config = NgPluginInstanceConfig(Json.obj("refs" -> Json.arr(target.id)))
    )))
  )

  lazy val setup: Unit = {
    Seq(provider, nativeProvider).foreach { p =>
      assert(client.forLlmEntity("providers").upsertEntity(p).awaitf(10.seconds).createdOrUpdated, s"${p.name} should be saved")
    }
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(route("native", classOf[OpenAiResponsesProxy], nativeProvider)).awaitf(10.seconds)
    Seq(
      "chat" -> classOf[OpenAiCompatProxy],
      "messages" -> classOf[AnthropicCompatProxy],
      "responses" -> classOf[OpenAiResponsesProxy],
      "openresponses" -> classOf[OpenResponseCompatProxy],
    ).foreach { case (name, plugin) =>
      client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(route(name, plugin)).awaitf(10.seconds)
    }
    await(5.seconds)
  }

  def post(name: String, body: JsObject): JsValue = {
    val resp = client.call("POST", s"http://${name}.finishreason.oto.tools:${port}/", Map.empty, Some(body)).awaitf(30.seconds)
    assertEquals(resp.status, 200, s"the call to ${name} should succeed, got ${resp.body}")
    resp.json
  }

  def chat(asked: String): JsValue = post("chat", Json.obj("model" -> "a-model", "max_tokens" -> 20, "messages" -> Json.arr(Json.obj("role" -> "user", "content" -> asked))))
  def messages(asked: String): JsValue = post("messages", Json.obj("model" -> "a-model", "max_tokens" -> 20, "messages" -> Json.arr(Json.obj("role" -> "user", "content" -> asked))))

  // an answer built the way the clients build it: `generationRaw` is the raw of the message, `raw` the payload
  private def answer(generationRaw: JsObject = Json.obj(), raw: JsValue = Json.obj(), content: String = "hi"): ChatResponse =
    ChatResponse(Seq(ChatGeneration(OutputChatMessage("assistant", content, None, generationRaw))), ChatResponseMetadata.empty, raw)

  private val toolCall = Json.obj("id" -> "call_1", "type" -> "function", "function" -> Json.obj("name" -> "get_weather", "arguments" -> "{}"))
  private def withToolCall(reason: String): JsObject = Json.obj(
    "finish_reason" -> reason,
    "message" -> Json.obj("role" -> "assistant", "content" -> "", "tool_calls" -> Json.arr(toolCall)),
  )

  private def chunk(content: Option[String], reason: Option[String] = None): ChatResponseChunk =
    ChatResponseChunk("id", 0L, "model", Seq(ChatResponseChunkChoice(0L, ChatResponseChunkChoiceDelta(content), reason)))

  // the data of the events of a stream, by event type
  private def events(bytes: Seq[ByteString]): Seq[(String, JsValue)] = bytes.map(_.utf8String).flatMap { event =>
    val lines = event.split("\n").toSeq
    for {
      typ <- lines.find(_.startsWith("event: ")).map(_.stripPrefix("event: ").trim)
      data <- lines.find(_.startsWith("data: ")).map(_.stripPrefix("data: ").trim)
    } yield (typ, Json.parse(data))
  }

  test("each format has its own word for why an answer ended") {
    Seq("length" -> "length", "max_tokens" -> "length", "MAX_TOKENS" -> "length", "model_length" -> "length", "end_turn" -> "stop",
      "content_filter" -> "content_filter", "SAFETY" -> "content_filter", "refusal" -> "content_filter", "tool_use" -> "tool_calls",
      "error" -> "error").foreach { case (said, openai) =>
      assertEquals(FinishReasons.openai(Some(said), hasToolCalls = false), openai, said)
    }
    assertEquals(FinishReasons.openai(None, hasToolCalls = false), "stop", "a provider that says nothing stopped")
    Seq("length" -> "max_tokens", "content_filter" -> "refusal", "stop" -> "end_turn", "end_turn" -> "end_turn",
      "stop_sequence" -> "stop_sequence", "pause_turn" -> "pause_turn", "error" -> "end_turn").foreach { case (said, anthropic) =>
      assertEquals(FinishReasons.anthropic(Some(said), hasToolCalls = false), anthropic, said)
    }
    assertEquals(FinishReasons.incomplete(Some("length")), Some("max_output_tokens"))
    assertEquals(FinishReasons.incomplete(Some("max_tokens")), Some("max_output_tokens"))
    assertEquals(FinishReasons.incomplete(Some("content_filter")), Some("content_filter"))
    assertEquals(FinishReasons.incomplete(Some("stop")), None)
    assertEquals(FinishReasons.incomplete(None), None)
  }

  test("a message that calls tools ends on them, unless it was cut") {
    assertEquals(FinishReasons.openai(Some("stop"), hasToolCalls = true), "tool_calls", "a server may call a tool call a stop")
    assertEquals(FinishReasons.openai(None, hasToolCalls = true), "tool_calls")
    assertEquals(FinishReasons.anthropic(Some("stop"), hasToolCalls = true), "tool_use")
    assertEquals(FinishReasons.openai(Some("length"), hasToolCalls = true), "length", "the arguments of a cut tool call are likely incomplete")
    assertEquals(FinishReasons.anthropic(Some("length"), hasToolCalls = true), "max_tokens")
    assertEquals(FinishReasons.anthropic(Some("tool_use"), hasToolCalls = false), "end_turn", "no tool_use block to look for")
  }

  test("a non streamed answer keeps the reason of its provider, said by the generation or by the answer") {
    val env = otoroshi.env
    // an OpenAI compatible api says it in the choice, the raw of the message
    val cut = answer(generationRaw = Json.obj("finish_reason" -> "length"))
    assertEquals(cut.openaiJson("a-model", env).select("choices").as[Seq[JsValue]].head.select("finish_reason").asString, "length")
    assertEquals(cut.openaiCompletionJson("a-model", echo = false, "hello", env).select("choices").as[Seq[JsValue]].head.select("finish_reason").asString, "length")
    assertEquals(cut.anthropicJson("a-model", env).select("stop_reason").asString, "max_tokens")
    val filtered = answer(generationRaw = Json.obj("finish_reason" -> "content_filter"))
    assertEquals(filtered.openaiJson("a-model", env).select("choices").as[Seq[JsValue]].head.select("finish_reason").asString, "content_filter")
    assertEquals(filtered.anthropicJson("a-model", env).select("stop_reason").asString, "refusal")
    // Anthropic says it once, for the whole answer
    val anthropic = answer(generationRaw = Json.obj("message" -> Json.obj("role" -> "assistant", "content" -> "hi")), raw = Json.obj("stop_reason" -> "max_tokens"))
    assertEquals(anthropic.openaiJson("a-model", env).select("choices").as[Seq[JsValue]].head.select("finish_reason").asString, "length")
    assertEquals(anthropic.anthropicJson("a-model", env).select("stop_reason").asString, "max_tokens")
    assertEquals(answer(raw = Json.obj("stop_reason" -> "stop_sequence")).anthropicJson("a-model", env).select("stop_reason").asString, "stop_sequence")
    // a provider that says nothing stopped, as before
    assertEquals(answer().openaiJson("a-model", env).select("choices").as[Seq[JsValue]].head.select("finish_reason").asString, "stop")
    assertEquals(answer().anthropicJson("a-model", env).select("stop_reason").asString, "end_turn")
    // a tool call said `stop` ends on the tool call
    val tools = answer(generationRaw = withToolCall("stop"), content = "")
    assertEquals(tools.openaiJson("a-model", env).select("choices").as[Seq[JsValue]].head.select("finish_reason").asString, "tool_calls")
    assertEquals(tools.anthropicJson("a-model", env).select("stop_reason").asString, "tool_use")
    // what is audited is what the caller is answered
    assertEquals(LlmCallTelemetry.finishReasonOf(cut), Some("length"))
    assertEquals(LlmCallTelemetry.finishReasonOf(tools), Some("tool_calls"))
  }

  test("a cut or filtered answer is incomplete on /responses") {
    val env = otoroshi.env
    val cut = answer(generationRaw = Json.obj("finish_reason" -> "length")).openaiResponseJson("a-model", env)
    assertEquals(cut.select("status").asString, "incomplete")
    assertEquals(cut.select("incomplete_details").select("reason").asString, "max_output_tokens")
    assertEquals(cut.select("output").as[Seq[JsValue]].head.select("status").asString, "incomplete", "the message of a cut answer is incomplete")
    val filtered = answer(generationRaw = Json.obj("finish_reason" -> "content_filter")).openaiResponseJson("a-model", env)
    assertEquals(filtered.select("incomplete_details").select("reason").asString, "content_filter")
    val done = answer(generationRaw = Json.obj("finish_reason" -> "stop")).openaiResponseJson("a-model", env)
    assertEquals(done.select("status").asString, "completed")
    assertEquals(done.select("incomplete_details").asOpt[JsValue], Some(JsNull))
    assertEquals(done.select("output").as[Seq[JsValue]].head.select("status").asString, "completed")
  }

  test("an answer streamed by a provider that does not stream ends the way it did") {
    given org.apache.pekko.stream.Materializer = otoroshi.env.otoroshiMaterializer
    def lastReason(response: ChatResponse): Option[String] =
      response.toSource("a-model").runWith(Sink.seq).awaitf(10.seconds).flatMap(_.choices.flatMap(_.finishReason)).lastOption
    assertEquals(lastReason(answer(generationRaw = Json.obj("finish_reason" -> "length"))), Some("length"))
    assertEquals(lastReason(answer(raw = Json.obj("stop_reason" -> "max_tokens"))), Some("length"))
    assertEquals(lastReason(answer()), Some("stop"))
    // the tool calls are not streamed: the stream does not end on them
    assertEquals(lastReason(answer(generationRaw = withToolCall("tool_calls"), content = "")), Some("stop"))
  }

  test("a filtered stream is a refusal on /messages") {
    val writer = new AnthropicStreamWriter()
    val bytes = writer.events(chunk(Some("an answer"))) ++ writer.events(chunk(None, Some("content_filter"))) ++ writer.end()
    val delta = events(bytes).collectFirst { case ("message_delta", data) => data }.get
    assertEquals(delta.select("delta").select("stop_reason").asString, "refusal")
    val cut = new AnthropicStreamWriter()
    val cutBytes = cut.events(chunk(Some("an answer"))) ++ cut.events(chunk(None, Some("MAX_TOKENS"))) ++ cut.end()
    assertEquals(events(cutBytes).collectFirst { case ("message_delta", data) => data }.get.select("delta").select("stop_reason").asString, "max_tokens")
  }

  test("a cut stream is incomplete on /responses") {
    def writerFor(reasons: Option[String]*): ResponsesStreamWriter = {
      val writer = new ResponsesStreamWriter(sequenced = false, logprobs = false, () => 0, prefix => s"${prefix}_1")
      writer.events(chunk(Some("an answer")))
      reasons.foreach(reason => writer.events(chunk(None, reason)))
      writer.end()
      writer
    }
    val cut = writerFor(Some("length"), Some("stop"))
    assertEquals(cut.acc.status, "incomplete", "a meaningful reason wins over a plain stop")
    assertEquals(cut.acc.incompleteDetails, Json.obj("reason" -> "max_output_tokens"))
    assertEquals(cut.output.head.select("status").asString, "incomplete")
    val done = writerFor(Some("stop"))
    assertEquals(done.acc.status, "completed")
    assertEquals(done.acc.incompleteDetails, JsNull)
    assertEquals(done.output.head.select("status").asString, "completed")
  }

  test("a cut answer reaches the caller cut, through every api the gateway exposes") {
    setup
    assertEquals(chat("length").select("choices").as[Seq[JsValue]].head.select("finish_reason").asString, "length", "/chat/completions")
    assertEquals(messages("length").select("stop_reason").asString, "max_tokens", "/messages")
    val responses = post("responses", Json.obj("model" -> "a-model", "max_output_tokens" -> 20, "input" -> "length"))
    assertEquals(responses.select("status").asString, "incomplete", s"/responses: ${responses}")
    assertEquals(responses.select("incomplete_details").select("reason").asString, "max_output_tokens", "/responses")
    val openResponses = post("openresponses", Json.obj("model" -> "a-model", "max_output_tokens" -> 20, "input" -> "length"))
    assertEquals(openResponses.select("status").asString, "incomplete", s"open responses: ${openResponses}")
    assertEquals(openResponses.select("incomplete_details").select("reason").asString, "max_output_tokens", "open responses")
  }

  test("a filtered answer and a tool call said stop reach the caller as such") {
    setup
    assertEquals(messages("content_filter").select("stop_reason").asString, "refusal", "/messages")
    val tools = chat("tools").select("choices").as[Seq[JsValue]].head
    assertEquals(tools.select("finish_reason").asString, "tool_calls", s"/chat/completions: ${tools}")
    assertEquals(chat("stop").select("choices").as[Seq[JsValue]].head.select("finish_reason").asString, "stop", "/chat/completions")
  }

  test("an answer a native /responses provider cut is audited as cut") {
    // the generations of the answer are made of its output, its payload is the raw of the answer
    val generations = OpenAiResponsesBodyConverter.outputToChatMessages(cutResponse).map(obj => ChatGeneration(ChatMessage.output("assistant", "an answer cut sh", None, obj)))
    assertEquals(generations.head.message.raw.select("finish_reason").asOpt[String], Some("length"))
    val cut = ChatResponse(generations, ChatResponseMetadata.empty, cutResponse)
    assertEquals(cut.openaiJson("a-model", otoroshi.env).select("choices").as[Seq[JsValue]].head.select("finish_reason").asString, "length")
    assertEquals(cut.anthropicJson("a-model", otoroshi.env).select("stop_reason").asString, "max_tokens")
    assertEquals(LlmCallTelemetry.finishReasonOf(cut), Some("length"), "what is audited")
    val filtered = cutResponse ++ Json.obj("incomplete_details" -> Json.obj("reason" -> "content_filter"))
    assertEquals(OpenAiResponsesBodyConverter.outputToChatMessages(filtered).head.select("finish_reason").asOpt[String], Some("content_filter"))
    val done = cutResponse ++ Json.obj("status" -> "completed", "incomplete_details" -> JsNull)
    assertEquals(OpenAiResponsesBodyConverter.outputToChatMessages(done).head.select("finish_reason").asOpt[String], Some("stop"))
    val calling = done ++ Json.obj("output" -> Json.arr(Json.obj("type" -> "function_call", "id" -> "fc_1", "call_id" -> "call_1", "name" -> "get_weather", "arguments" -> "{}")))
    assertEquals(OpenAiResponsesBodyConverter.outputToChatMessages(calling).head.select("finish_reason").asOpt[String], Some("tool_calls"))
  }

  test("a native /responses provider that cuts its answer ends it incomplete, streamed or not") {
    setup
    val body = Json.obj("model" -> "a-model", "max_output_tokens" -> 20, "input" -> "hello")
    val passed = post("native", body)
    assertEquals(passed.select("status").asString, "incomplete", s"${passed}")
    // streamed, `response.incomplete` ends the answer instead of failing it
    val streamed = client.call("POST", s"http://native.finishreason.oto.tools:${port}/", Map.empty, Some(body ++ Json.obj("stream" -> true))).awaitf(30.seconds)
    assertEquals(streamed.status, 200)
    val events = streamed.body.split("\n").toSeq.filter(_.startsWith("data: {")).map(l => Json.parse(l.stripPrefix("data: ")))
    assert(events.exists(e => e.select("type").asOpt[String].contains("response.output_text.delta") && e.select("delta").asOpt[String].contains("an answer cut sh")), s"the text is streamed: ${streamed.body}")
    val last = events.last
    assertEquals(last.select("type").asOpt[String], Some("response.incomplete"), streamed.body)
    assertEquals(last.at("response.incomplete_details.reason").asOpt[String], Some("max_output_tokens"), streamed.body)
  }
}
