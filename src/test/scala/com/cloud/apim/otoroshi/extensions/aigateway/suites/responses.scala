package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.LlmExtensionOneOtoroshiServerPerSuite
import com.cloud.apim.otoroshi.extensions.aigateway.decorators.{GuardrailItem, Guardrails}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiProvider, ContextSettings, ModelSettings, PersistentMemory, PromptContext, SearchEngine}
import com.cloud.apim.otoroshi.extensions.aigateway.providers.{AzureOpenAiApi, AzureOpenAiChatClient, AzureOpenAiChatClientOptions}
import otoroshi.models.EntityLocation
import otoroshi.next.models._
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.{OpenAiResponsesProxy, OpenResponseCompatProxy}
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}
import org.reactivestreams.Publisher
import reactor.core.publisher.{Flux, Mono}
import reactor.netty.http.server.{HttpServerRequest, HttpServerResponse}

import java.util.UUID
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.DurationInt

// End to end tests of the `/responses` path (issue #189): a /responses request must go through the
// whole decorator chain (guardrails, model constraints, mock, prompt contexts, usage) whether the
// provider serves it natively or by degrading to /chat/completions, and the provider payload must
// always be rebuilt from the ChatPrompt.
class ResponsesSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  val bodies = new TrieMap[String, JsValue]()

  private def sse(events: Seq[(String, JsValue)]): Flux[String] = {
    Flux.fromIterable(events.map { case (typ, data) => s"event: ${typ}\ndata: ${data.stringify}\n\n" }.asJava)
  }

  private def chatCompletionsBody(content: String): String =
    s"""{
       |  "id": "chatcmpl-123",
       |  "object": "chat.completion",
       |  "created": 1700000000,
       |  "model": "gpt-4.1",
       |  "choices": [{
       |    "index": 0,
       |    "message": { "role": "assistant", "content": "${content}" },
       |    "finish_reason": "stop"
       |  }],
       |  "usage": { "prompt_tokens": 12, "completion_tokens": 5, "total_tokens": 17 }
       |}""".stripMargin

  // a native /responses payload, with a reasoning item to check it survives the round trip
  private val nativeResponseBody: JsObject = Json.obj(
    "id" -> "resp_native_123",
    "object" -> "response",
    "created_at" -> 1700000000L,
    "model" -> "gpt-4.1",
    "status" -> "completed",
    "store" -> true,
    "output" -> Json.arr(
      Json.obj("type" -> "reasoning", "id" -> "rs_1", "summary" -> Json.arr(Json.obj("type" -> "summary_text", "text" -> "thinking hard"))),
      Json.obj(
        "type" -> "message",
        "id" -> "msg_1",
        "status" -> "completed",
        "role" -> "assistant",
        "content" -> Json.arr(Json.obj("type" -> "output_text", "text" -> "native hello", "annotations" -> Json.arr())),
      ),
    ),
    "usage" -> Json.obj(
      "input_tokens" -> 11,
      "output_tokens" -> 7,
      "total_tokens" -> 18,
      "output_tokens_details" -> Json.obj("reasoning_tokens" -> 3),
    ),
  )

  val (openaiPort, _) = createTestServerWithRoutes("openai", routes => routes
    .post("/chat/completions", (req, response) => {
      req.receive().aggregate().asString().flatMapMany { rawBody =>
        val body = rawBody.parseJson
        bodies.put("chat", body)
        if (body.select("stream").asOpt[Boolean].contains(true)) {
          val chunk = Json.obj(
            "id" -> "chatcmpl-123", "object" -> "chat.completion.chunk", "created" -> 1700000000L, "model" -> "gpt-4.1",
            "choices" -> Json.arr(Json.obj("index" -> 0, "delta" -> Json.obj("role" -> "assistant", "content" -> "degraded stream"))),
          )
          // the usage of a streamed chat/completions answer only comes with the last chunk
          val usageChunk = Json.obj(
            "id" -> "chatcmpl-123", "object" -> "chat.completion.chunk", "created" -> 1700000000L, "model" -> "gpt-4.1",
            "choices" -> Json.arr(),
            "usage" -> Json.obj("prompt_tokens" -> 21, "completion_tokens" -> 3, "total_tokens" -> 24),
          )
          response
            .status(200)
            .addHeader("Content-Type", "text/event-stream")
            .sendString(Flux.fromIterable(Seq(
              s"data: ${chunk.stringify}\n\n",
              s"data: ${usageChunk.stringify}\n\n",
              "data: [DONE]\n\n",
            ).asJava))
        } else {
          response
            .status(200)
            .addHeader("Content-Type", "application/json")
            .sendString(Mono.just(chatCompletionsBody("degraded hello")))
        }
      }
    })
    // the native endpoint of each provider: openai/azure `/responses`, groq `/openai/v1/responses`,
    // x.ai and ollama `/v1/responses`
    .post("/responses", (req, response) => nativeResponses("responses", req, response))
    .post("/openai/v1/responses", (req, response) => nativeResponses("groq", req, response))
    .post("/v1/responses", (req, response) => nativeResponses("v1", req, response))
    // the search engine used as a gateway tool
    .post("/search", (req, response) => {
      req.receive().aggregate().asString().flatMapMany { rawBody =>
        bodies.put("search", rawBody.parseJson)
        response
          .status(200)
          .addHeader("Content-Type", "application/json")
          .sendString(Mono.just(Json.obj(
            "answer" -> "it is sunny",
            "results" -> Json.arr(Json.obj("title" -> "weather", "url" -> "https://example.com", "content" -> "sunny")),
          ).stringify))
      }
    })
  )

  // when set, the native endpoint answers with a `function_call` output item on its first call only,
  // which is what makes the provider drive a tool loop
  private val functionCallOnFirstCall = new java.util.concurrent.atomic.AtomicReference[Option[(String, String)]](None)
  private val nativeCalls = new java.util.concurrent.atomic.AtomicInteger(0)

  private def nativeResponses(key: String, req: HttpServerRequest, response: HttpServerResponse): Publisher[Void] = {
    req.receive().aggregate().asString().flatMapMany { rawBody =>
      val body = rawBody.parseJson
      val callIndex = nativeCalls.getAndIncrement()
      bodies.put(key, body)
      bodies.put(s"${key}-${callIndex}", body)
      if (callIndex == 0 && functionCallOnFirstCall.get().isDefined) {
        val (name, arguments) = functionCallOnFirstCall.get().get
        response
          .status(200)
          .addHeader("Content-Type", "application/json")
          .sendString(Mono.just(nativeResponseBody.deepMerge(Json.obj(
            "output" -> Json.arr(Json.obj(
              "type" -> "function_call",
              "id" -> "fc_1",
              "call_id" -> "call_1",
              "name" -> name,
              "arguments" -> arguments,
            ))
          )).stringify))
      } else if (body.select("stream").asOpt[Boolean].contains(true)) {
        val inProgress = Json.obj("id" -> "resp_stream_1", "object" -> "response", "model" -> "gpt-4.1", "status" -> "in_progress", "output" -> JsArray(), "usage" -> play.api.libs.json.JsNull)
        response
          .status(200)
          .addHeader("Content-Type", "text/event-stream")
          .sendString(sse(Seq(
            "response.created" -> Json.obj("type" -> "response.created", "response" -> inProgress),
            // usage is null on this one: reading it here would report zero tokens
            "response.in_progress" -> Json.obj("type" -> "response.in_progress", "response" -> inProgress),
            "response.output_text.delta" -> Json.obj("type" -> "response.output_text.delta", "item_id" -> "msg_1", "output_index" -> 0, "content_index" -> 0, "delta" -> "native "),
            "response.output_text.delta" -> Json.obj("type" -> "response.output_text.delta", "item_id" -> "msg_1", "output_index" -> 0, "content_index" -> 0, "delta" -> "stream"),
            "response.completed" -> Json.obj("type" -> "response.completed", "response" -> nativeResponseBody.deepMerge(Json.obj(
              "id" -> "resp_stream_1",
              "usage" -> Json.obj("input_tokens" -> 31, "output_tokens" -> 4, "total_tokens" -> 35),
            ))),
          )))
      } else {
        response
          .status(200)
          .addHeader("Content-Type", "application/json")
          .sendString(Mono.just(nativeResponseBody.stringify))
      }
    }
  }

  def provider(
    name: String,
    options: JsObject,
    guardrails: Guardrails = Guardrails.empty,
    models: ModelSettings = ModelSettings.empty,
    context: ContextSettings = ContextSettings.empty,
    memory: Option[String] = None,
  ): AiProvider = AiProvider(
    id = UUID.randomUUID().toString,
    name = name,
    provider = "openai",
    connection = Json.obj(
      "base_url" -> s"http://localhost:${openaiPort}",
      "token" -> "xxx",
      "timeout" -> 30000,
    ),
    options = Json.obj("model" -> "gpt-4.1") ++ options,
    guardrails = guardrails,
    models = models,
    context = context,
    memory = memory,
  )

  def routeWith(plugin: String, domain: String, providerId: String): NgRoute = NgRoute(
    location = EntityLocation.default,
    id = UUID.randomUUID().toString,
    name = s"test route ${domain}",
    description = s"test route ${domain}",
    tags = Seq.empty,
    metadata = Map.empty,
    enabled = true,
    debugFlow = false,
    capture = false,
    exportReporting = false,
    frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath(s"${domain}/responses"))),
    backend = NgBackend.empty.copy(targets = Seq(NgTarget.default)),
    plugins = NgPlugins(Seq(NgPluginInstance(
      plugin = s"cp:${plugin}",
      config = NgPluginInstanceConfig(Json.obj("refs" -> Json.arr(providerId)))
    )))
  )

  val denyGuardrails = Guardrails(Seq(GuardrailItem(
    enabled = true,
    before = true,
    after = false,
    guardrailId = "regex",
    config = Json.obj("deny" -> Json.arr(".*forbidden.*")),
  )))

  // a search engine acts as a gateway-provided tool, so the native tool loop can be exercised
  // end to end without a wasm runtime
  val searchEngine = SearchEngine(
    location = EntityLocation.default,
    id = s"search-engine_${UUID.randomUUID().toString}",
    name = "fake search",
    description = "fake search",
    tags = Seq.empty,
    metadata = Map.empty,
    provider = "tavily",
    config = Json.obj(
      "connection" -> Json.obj("base_url" -> s"http://localhost:${openaiPort}", "token" -> "xxx", "timeout" -> 30000),
      "options" -> Json.obj(),
    ),
  )

  def memoryEntity(name: String, session: String): PersistentMemory = PersistentMemory(
    location = EntityLocation.default,
    id = UUID.randomUUID().toString,
    name = name,
    description = name,
    tags = Seq.empty,
    metadata = Map.empty,
    provider = "local",
    config = Json.obj("options" -> Json.obj("session_id" -> session)),
  )

  val streamMemory = memoryEntity("responses stream memory", "responses-test-stream-session")

  val memory = PersistentMemory(
    location = EntityLocation.default,
    id = UUID.randomUUID().toString,
    name = "responses memory",
    description = "responses memory",
    tags = Seq.empty,
    metadata = Map.empty,
    provider = "local",
    config = Json.obj("options" -> Json.obj("session_id" -> "responses-test-session")),
  )

  val context = PromptContext(
    location = EntityLocation.default,
    id = UUID.randomUUID().toString,
    name = "responses ctx",
    description = "responses ctx",
    tags = Seq.empty,
    metadata = Map.empty,
    preMessages = Seq(Json.obj("role" -> "system", "content" -> "you are a context injected butler")),
    postMessages = Seq.empty,
  )

  // provider without the `responses` option: /responses degrades to /chat/completions
  val degraded = provider("degraded", Json.obj("max_tokens" -> 512))
  // same, but with a native /responses path
  val native = provider("native", Json.obj("responses" -> true, "max_tokens" -> 512))
  val guarded = provider("guarded", Json.obj(), guardrails = denyGuardrails)
  val guardedNative = provider("guarded native", Json.obj("responses" -> true), guardrails = denyGuardrails)
  val constrained = provider("constrained", Json.obj(), models = ModelSettings(include = Seq("gpt-4.1")))
  val contextual = provider("contextual", Json.obj(), context = ContextSettings(Some(context.id), Seq(context.id)))
  val remembering = provider("remembering", Json.obj(), memory = Some(memory.id))
  val rememberingStream = provider("remembering stream", Json.obj(), memory = Some(streamMemory.id))

  // the other providers exposing a native /responses endpoint, all pointed at the same fake server
  def nativeProviderOf(name: String, kind: String, options: JsObject): AiProvider = AiProvider(
    id = UUID.randomUUID().toString,
    name = name,
    provider = kind,
    connection = Json.obj(
      "base_url" -> s"http://localhost:${openaiPort}",
      "token" -> "xxx",
      "timeout" -> 30000,
    ),
    options = options,
  )

  val toolingNative = nativeProviderOf("tooling native", "openai", Json.obj("model" -> "gpt-4.1", "responses" -> true, "search_engines" -> Json.arr(searchEngine.id)))
  val groqNative = nativeProviderOf("groq native", "groq", Json.obj("model" -> "gpt-4.1", "max_tokens" -> 512, "responses" -> true))
  val xaiNative = nativeProviderOf("xai native", "x-ai", Json.obj("model" -> "gpt-4.1", "max_tokens" -> 512, "responses" -> true))
  val ollamaNative = nativeProviderOf("ollama native", "ollama", Json.obj("model" -> "gpt-4.1", "num_predict" -> 512, "num_ctx" -> 4096, "top_k" -> 40, "responses" -> true))

  val allProviders = Seq(degraded, native, guarded, guardedNative, constrained, contextual, remembering,
    rememberingStream, groqNative, xaiNative, ollamaNative, toolingNative)

  val degradedRoute = routeWith(classOf[OpenAiResponsesProxy].getName, "degraded.oto.tools", degraded.id)
  val nativeRoute = routeWith(classOf[OpenAiResponsesProxy].getName, "native.oto.tools", native.id)
  val guardedRoute = routeWith(classOf[OpenAiResponsesProxy].getName, "guarded.oto.tools", guarded.id)
  val guardedNativeRoute = routeWith(classOf[OpenAiResponsesProxy].getName, "guardednative.oto.tools", guardedNative.id)
  val constrainedRoute = routeWith(classOf[OpenAiResponsesProxy].getName, "constrained.oto.tools", constrained.id)
  val contextualRoute = routeWith(classOf[OpenAiResponsesProxy].getName, "contextual.oto.tools", contextual.id)
  val rememberingRoute = routeWith(classOf[OpenAiResponsesProxy].getName, "remembering.oto.tools", remembering.id)
  val rememberingStreamRoute = routeWith(classOf[OpenAiResponsesProxy].getName, "rememberingstream.oto.tools", rememberingStream.id)
  // the other /responses front-end, on the same providers
  val openDegradedRoute = routeWith(classOf[OpenResponseCompatProxy].getName, "opendegraded.oto.tools", degraded.id)
  val openNativeRoute = routeWith(classOf[OpenResponseCompatProxy].getName, "opennative.oto.tools", native.id)
  val openGuardedRoute = routeWith(classOf[OpenResponseCompatProxy].getName, "openguarded.oto.tools", guarded.id)

  val groqRoute = routeWith(classOf[OpenAiResponsesProxy].getName, "groqnative.oto.tools", groqNative.id)
  val xaiRoute = routeWith(classOf[OpenAiResponsesProxy].getName, "xainative.oto.tools", xaiNative.id)
  val ollamaRoute = routeWith(classOf[OpenAiResponsesProxy].getName, "ollamanative.oto.tools", ollamaNative.id)
  val toolingRoute = routeWith(classOf[OpenAiResponsesProxy].getName, "toolingnative.oto.tools", toolingNative.id)

  val allRoutes = Seq(degradedRoute, nativeRoute, guardedRoute, guardedNativeRoute, constrainedRoute, contextualRoute,
    rememberingRoute, openDegradedRoute, openNativeRoute, openGuardedRoute, groqRoute, xaiRoute, ollamaRoute,
    toolingRoute, rememberingStreamRoute)

  override def beforeAll(): Unit = {
    super.beforeAll()
    allProviders.foreach { p =>
      client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").upsertEntity(p).awaitf(10.seconds)
    }
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "prompt-contexts").upsertEntity(context).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "persistent-memories").upsertEntity(memory).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "persistent-memories").upsertEntity(streamMemory).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "search-engines").upsertEntity(searchEngine).awaitf(10.seconds)
    allRoutes.foreach { r =>
      client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(r).awaitf(10.seconds)
    }
    await(2.seconds)
  }

  private def responsesCall(domain: String, body: JsObject) = {
    bodies.clear()
    nativeCalls.set(0)
    client.call("POST", s"http://${domain}:${port}/responses", Map.empty, Some(body)).awaitf(30.seconds)
  }

  // collects the SSE blocks of a streamed answer and returns the parsed `data:` payloads
  private def responsesStream(domain: String, body: JsObject): Seq[JsValue] = {
    bodies.clear()
    nativeCalls.set(0)
    val blocks = scala.collection.mutable.ArrayBuffer.empty[String]
    client.stream("POST", s"http://${domain}:${port}/responses", Map.empty, Some(body), 20.seconds, block => blocks.synchronized(blocks += block)).awaitf(30.seconds)
    blocks.synchronized(blocks.toList).flatMap { block =>
      block.split("\n").toSeq.filter(_.startsWith("data:")).map(_.replaceFirst("data:", "").trim)
    }.filter(d => d.nonEmpty && d != "[DONE]").map(_.parseJson)
  }

  private def outputText(json: JsValue): String = {
    json.select("output").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
      .filter(_.select("type").asOptString.contains("message"))
      .flatMap(_.select("content").asOpt[Seq[JsObject]].getOrElse(Seq.empty).flatMap(_.select("text").asOptString))
      .mkString("")
  }

  test("a /responses request on a provider without a native path becomes a valid /chat/completions one") {
    val res = responsesCall("degraded.oto.tools", Json.obj(
      "model" -> "gpt-4.1",
      "instructions" -> "you are a helpful assistant",
      "input" -> Json.arr(Json.obj("role" -> "user", "content" -> "hello")),
      "max_output_tokens" -> 100,
      "store" -> true,
      "truncation" -> "disabled",
      "reasoning" -> Json.obj("effort" -> "low"),
    ))
    assertEquals(res.status, 200, s"bad status: ${res.body}")
    assertEquals(outputText(res.json), "degraded hello")
    val sent = bodies("chat")
    // responses-only params never reach a /chat/completions provider
    Seq("input", "instructions", "previous_response_id", "store", "truncation", "max_output_tokens").foreach { param =>
      assert(sent.select(param).asOpt[JsValue].isEmpty, s"'${param}' should not have been forwarded: ${sent.stringify}")
    }
    assertEquals(sent.select("max_tokens").asOpt[Int], Some(100), s"max_output_tokens should become max_tokens: ${sent.stringify}")
    assertEquals(sent.select("reasoning_effort").asOptString, Some("low"), s"reasoning.effort should become reasoning_effort: ${sent.stringify}")
    // messages are rebuilt from the prompt: instructions first, then the input items
    val messages = sent.select("messages").as[Seq[JsObject]]
    assertEquals(messages.head.select("role").asString, "system")
    assertEquals(messages.head.select("content").asString, "you are a helpful assistant")
    assertEquals(messages.last.select("content").asString, "hello")
  }

  test("tools declared in responses format work on a provider with no native responses path") {
    val res = responsesCall("degraded.oto.tools", Json.obj(
      "model" -> "gpt-4.1",
      "input" -> "what is the weather ?",
      "tools" -> Json.arr(Json.obj(
        "type" -> "function",
        "name" -> "get_weather",
        "description" -> "get the weather",
        "parameters" -> Json.obj("type" -> "object", "properties" -> Json.obj()),
        "strict" -> true,
      )),
    ))
    assertEquals(res.status, 200, s"bad status: ${res.body}")
    val tool = bodies("chat").select("tools").as[Seq[JsObject]].head
    assertEquals(tool.select("type").asString, "function")
    assertEquals(tool.at("function.name").asOptString, Some("get_weather"), s"tool was not converted to the chat shape: ${tool.stringify}")
    assertEquals(tool.at("function.strict").asOptBoolean, Some(true))
    assert(tool.at("function.parameters").asOpt[JsObject].isDefined, "tool parameters are missing")
  }

  test("a mock provider still mocks on /responses") {
    val res = responsesCall("degraded.oto.tools", Json.obj(
      "model" -> "gpt-4.1",
      "input" -> "hello",
      "mock_response" -> "mocked answer",
    ))
    assertEquals(res.status, 200, s"bad status: ${res.body}")
    assertEquals(outputText(res.json), "mocked answer")
    assert(bodies.get("chat").isEmpty, "the provider should not have been called at all")
  }

  test("guardrails run on /responses, natively or not") {
    Seq("guarded.oto.tools", "guardednative.oto.tools", "openguarded.oto.tools").foreach { domain =>
      val res = responsesCall(domain, Json.obj("model" -> "gpt-4.1", "input" -> "this is forbidden content"))
      assertEquals(res.status, 200, s"[${domain}] bad status: ${res.body}")
      assert(res.body.contains("does not match regex"), s"[${domain}] the guardrail did not deny the prompt: ${res.body}")
      assert(bodies.isEmpty, s"[${domain}] the provider should not have been called: ${bodies}")
    }
  }

  test("model constraints apply on /responses") {
    val allowed = responsesCall("constrained.oto.tools", Json.obj("model" -> "gpt-4.1", "input" -> "hello"))
    assertEquals(allowed.status, 200, s"an allowed model should pass: ${allowed.body}")
    val denied = responsesCall("constrained.oto.tools", Json.obj("model" -> "gpt-5-turbo", "input" -> "hello"))
    assertEquals(denied.status, 400, s"a model outside the allow-list should be denied: ${denied.body}")
    assert(denied.body.contains("you can't use this model"), s"bad error: ${denied.body}")
    assert(bodies.isEmpty, "the provider should not have been called")
  }

  test("prompt contexts are applied on /responses") {
    val res = responsesCall("contextual.oto.tools", Json.obj("model" -> "gpt-4.1", "input" -> "hello"))
    assertEquals(res.status, 200, s"bad status: ${res.body}")
    val messages = bodies("chat").select("messages").as[Seq[JsObject]]
    assertEquals(messages.head.select("content").asString, "you are a context injected butler", s"the context was not injected: ${messages}")
  }

  test("persistent memory is applied on /responses") {
    val first = responsesCall("remembering.oto.tools", Json.obj("model" -> "gpt-4.1", "input" -> "my name is bob"))
    assertEquals(first.status, 200, s"bad status: ${first.body}")
    assertEquals(bodies("chat").select("messages").as[Seq[JsObject]].size, 1, "the first call has no history yet")
    val second = responsesCall("remembering.oto.tools", Json.obj("model" -> "gpt-4.1", "input" -> "what is my name ?"))
    assertEquals(second.status, 200, s"bad status: ${second.body}")
    val messages = bodies("chat").select("messages").as[Seq[JsObject]]
    // the memorized exchange is prepended to the new question
    assertEquals(messages.size, 3, s"the conversation history was not injected: ${messages}")
    assertEquals(messages.head.select("content").asString, "my name is bob")
    assertEquals(messages(1).select("content").asString, "degraded hello")
    assertEquals(messages.last.select("content").asString, "what is my name ?")
  }

  test("persistent memory is applied on /responses in streaming too") {
    val events = responsesStream("rememberingstream.oto.tools", Json.obj("model" -> "gpt-4.1", "input" -> "my name is bob", "stream" -> true))
    assert(events.nonEmpty, "no event received")
    assertEquals(bodies("chat").select("messages").as[Seq[JsObject]].size, 1, "the first call has no history yet")
    val second = responsesCall("rememberingstream.oto.tools", Json.obj("model" -> "gpt-4.1", "input" -> "what is my name ?"))
    assertEquals(second.status, 200, s"bad status: ${second.body}")
    val messages = bodies("chat").select("messages").as[Seq[JsObject]]
    // the answer streamed by the first call has been memorized as an assistant message
    assertEquals(messages.size, 3, s"the streamed exchange was not memorized: ${messages}")
    assertEquals(messages.head.select("content").asString, "my name is bob")
    assertEquals(messages(1).select("content").asString, "degraded stream")
    assertEquals(messages.last.select("content").asString, "what is my name ?")
  }

  test("a chat parameter sent by the caller is renamed instead of being forwarded as is") {
    val res = responsesCall("native.oto.tools", Json.obj(
      "model" -> "gpt-4.1",
      "input" -> "hello",
      // a caller may override provider options from the body: chat-only ones must be mapped
      "response_format" -> Json.obj("type" -> "json_object"),
      "reasoning_effort" -> "low",
    ))
    assertEquals(res.status, 200, s"bad status: ${res.body}")
    val sent = bodies("responses")
    assert(sent.select("response_format").asOpt[JsValue].isEmpty, s"response_format does not exist on /responses: ${sent.stringify}")
    assert(sent.select("reasoning_effort").asOpt[JsValue].isEmpty, s"reasoning_effort does not exist on /responses: ${sent.stringify}")
    assertEquals(sent.at("text.format.type").asOptString, Some("json_object"), s"response_format was not mapped: ${sent.stringify}")
    assertEquals(sent.at("reasoning.effort").asOptString, Some("low"), s"reasoning_effort was not mapped: ${sent.stringify}")
  }

  test("the native path builds its payload from the prompt and drops chat-only params") {
    val res = responsesCall("native.oto.tools", Json.obj(
      "model" -> "gpt-4.1",
      "instructions" -> "you are a helpful assistant",
      "input" -> Json.arr(Json.obj("role" -> "user", "content" -> "hello")),
    ))
    assertEquals(res.status, 200, s"bad status: ${res.body}")
    val sent = bodies("responses")
    // the provider is configured with max_tokens, which does not exist on /responses
    assert(sent.select("max_tokens").asOpt[JsValue].isEmpty, s"max_tokens must not be sent to /responses: ${sent.stringify}")
    assertEquals(sent.select("max_output_tokens").asOpt[Int], Some(512), s"max_tokens should become max_output_tokens: ${sent.stringify}")
    Seq("n", "stop", "frequency_penalty", "presence_penalty", "logit_bias", "logprobs", "response_format", "messages").foreach { param =>
      assert(sent.select(param).asOpt[JsValue].isEmpty, s"chat-only param '${param}' must not be sent to /responses: ${sent.stringify}")
    }
    // `input` comes from the prompt, not from the caller's own input array
    val input = sent.select("input").as[Seq[JsObject]]
    assertEquals(input.size, 2, s"bad input items: ${input}")
    assertEquals(input.head.select("type").asString, "message")
    assertEquals(input.head.select("role").asString, "system")
    assertEquals(input.head.at("content.0.type").asString, "input_text")
    assertEquals(input.head.at("content.0.text").asString, "you are a helpful assistant")
    assertEquals(input.last.at("content.0.text").asString, "hello")
  }

  test("the native path answer is passed through, reasoning items included") {
    val res = responsesCall("native.oto.tools", Json.obj("model" -> "gpt-4.1", "input" -> "hello"))
    assertEquals(res.status, 200, s"bad status: ${res.body}")
    assertEquals(res.json.select("id").asString, "resp_native_123", s"the provider response id should be kept: ${res.body}")
    assertEquals(res.json.at("usage.input_tokens").asOpt[Int], Some(11))
    assertEquals(outputText(res.json), "native hello")
    val types = res.json.select("output").as[Seq[JsObject]].map(_.select("type").asString)
    assert(types.contains("reasoning"), s"the reasoning item should have been kept: ${res.body}")
  }

  test("a native provider still uses /chat/completions when the responses option is off") {
    val res = responsesCall("degraded.oto.tools", Json.obj("model" -> "gpt-4.1", "input" -> "hello"))
    assertEquals(res.status, 200, s"bad status: ${res.body}")
    assert(bodies.get("responses").isEmpty, "the native endpoint should not have been called")
    assert(bodies.get("chat").isDefined, "the chat/completions endpoint should have been called")
  }

  test("groq, x.ai and ollama call their own native /responses endpoint") {
    Seq(
      ("groqnative.oto.tools", "groq"),
      ("xainative.oto.tools", "v1"),
      ("ollamanative.oto.tools", "v1"),
    ).foreach { case (domain, key) =>
      val res = responsesCall(domain, Json.obj(
        "model" -> "gpt-4.1",
        "instructions" -> "you are a helpful assistant",
        "input" -> Json.arr(Json.obj("role" -> "user", "content" -> "hello")),
      ))
      assertEquals(res.status, 200, s"[${domain}] bad status: ${res.body}")
      assertEquals(outputText(res.json), "native hello", s"[${domain}] bad answer")
      assert(bodies.get("chat").isEmpty, s"[${domain}] the chat/completions endpoint should not have been called")
      val sent = bodies.getOrElse(key, fail(s"[${domain}] the native endpoint has not been called: ${bodies.keys}"))
      // `input` is built from the prompt, and the configured token budget is renamed
      assertEquals(sent.select("input").as[Seq[JsObject]].size, 2, s"[${domain}] bad input items: ${sent.stringify}")
      assertEquals(sent.at("input.0.content.0.text").asOptString, Some("you are a helpful assistant"), s"[${domain}] instructions are missing from the input")
      assertEquals(sent.select("max_output_tokens").asOpt[Int], Some(512), s"[${domain}] token budget not mapped: ${sent.stringify}")
      Seq("max_tokens", "num_predict", "num_ctx", "top_k", "n", "messages").foreach { param =>
        assert(sent.select(param).asOpt[JsValue].isEmpty, s"[${domain}] '${param}' must not be sent to /responses: ${sent.stringify}")
      }
    }
  }

  test("parameters a provider documents as unsupported are not forwarded to its native endpoint") {
    // groq 400s on these instead of ignoring them
    val res = responsesCall("groqnative.oto.tools", Json.obj(
      "model" -> "gpt-4.1",
      "input" -> "hello",
      "store" -> true,
      "previous_response_id" -> "resp_previous",
      "truncation" -> "auto",
    ))
    assertEquals(res.status, 200, s"bad status: ${res.body}")
    val sent = bodies("groq")
    Seq("store", "previous_response_id", "truncation").foreach { param =>
      assert(sent.select(param).asOpt[JsValue].isEmpty, s"'${param}' is unsupported by groq and must be dropped: ${sent.stringify}")
    }
  }

  test("the native path runs the tool loop for the tools the gateway provides") {
    functionCallOnFirstCall.set(Some(("search___" + searchEngine.id, """{"query":"weather in paris"}""")))
    try {
      val res = responsesCall("toolingnative.oto.tools", Json.obj("model" -> "gpt-4.1", "input" -> "what is the weather ?"))
      assertEquals(res.status, 200, s"bad status: ${res.body}")
      // the tool call is executed by the gateway, not returned to the caller
      assertEquals(outputText(res.json), "native hello", s"the loop should have produced the final answer: ${res.body}")
      assertEquals(bodies("search").select("query").asOptString, Some("weather in paris"), "the search engine has not been called")
      // the first call declares the tool, the second one replays the call and its result
      val first = bodies("responses-0")
      assertEquals(first.at("tools.0.name").asOptString, Some("search___" + searchEngine.id), s"the tool was not declared: ${first.stringify}")
      assert(first.at("tools.0.function").asOpt[JsObject].isEmpty, "responses tools are flat, not wrapped in a `function` object")
      val secondInput = bodies("responses-1").select("input").as[Seq[JsObject]]
      assert(secondInput.exists(_.select("type").asOptString.contains("function_call")), s"the function call is missing from the second input: ${secondInput}")
      val output = secondInput.find(_.select("type").asOptString.contains("function_call_output"))
        .getOrElse(fail(s"the tool result is missing from the second input: ${secondInput}"))
      assertEquals(output.select("call_id").asOptString, Some("call_1"))
      assert(output.select("output").asString.contains("it is sunny"), s"bad tool result: ${output.stringify}")
    } finally {
      functionCallOnFirstCall.set(None)
    }
  }

  test("tool calls of tools declared by the caller are returned to it, not executed by the gateway") {
    functionCallOnFirstCall.set(Some(("get_weather", """{"location":"paris"}""")))
    try {
      val res = responsesCall("native.oto.tools", Json.obj(
        "model" -> "gpt-4.1",
        "input" -> "what is the weather ?",
        "tools" -> Json.arr(Json.obj("type" -> "function", "name" -> "get_weather", "parameters" -> Json.obj("type" -> "object"))),
      ))
      assertEquals(res.status, 200, s"bad status: ${res.body}")
      assertEquals(nativeCalls.get(), 1, "the gateway must not loop on a tool it does not own")
      assert(bodies.get("search").isEmpty, "no gateway tool should have run")
      val functionCall = res.json.select("output").as[Seq[JsObject]].find(_.select("type").asOptString.contains("function_call"))
        .getOrElse(fail(s"the function call should have been returned to the caller: ${res.body}"))
      assertEquals(functionCall.select("name").asOptString, Some("get_weather"))
      assertEquals(functionCall.select("arguments").asOptString, Some("""{"location":"paris"}"""))
    } finally {
      functionCallOnFirstCall.set(None)
    }
  }

  test("the native input keeps tool items and file parts of the conversation") {
    val res = responsesCall("native.oto.tools", Json.obj(
      "model" -> "gpt-4.1",
      "input" -> Json.arr(
        Json.obj("type" -> "message", "role" -> "user", "content" -> Json.arr(
          Json.obj("type" -> "input_text", "text" -> "summarize this"),
          Json.obj("type" -> "input_file", "filename" -> "doc.pdf", "file_data" -> "data:application/pdf;base64,JVBERi0="),
        )),
        Json.obj("type" -> "function_call", "call_id" -> "call_42", "name" -> "get_weather", "arguments" -> """{"location":"paris"}"""),
        Json.obj("type" -> "function_call_output", "call_id" -> "call_42", "output" -> """{"temperature":18}"""),
      ),
    ))
    assertEquals(res.status, 200, s"bad status: ${res.body}")
    val input = bodies("responses").select("input").as[Seq[JsObject]]
    val message = input.head
    assertEquals(message.select("type").asString, "message")
    assertEquals(message.at("content.0.type").asString, "input_text")
    assertEquals(message.at("content.1.type").asString, "input_file", s"the file part was lost: ${message.stringify}")
    assertEquals(message.at("content.1.filename").asString, "doc.pdf")
    assert(message.at("content.1.file_data").asString.startsWith("data:application/pdf;base64,"), s"bad file payload: ${message.stringify}")
    val call = input.find(_.select("type").asOptString.contains("function_call")).getOrElse(fail(s"function call lost: ${input}"))
    assertEquals(call.select("call_id").asOptString, Some("call_42"))
    assertEquals(call.select("name").asOptString, Some("get_weather"))
    val output = input.find(_.select("type").asOptString.contains("function_call_output")).getOrElse(fail(s"function call output lost: ${input}"))
    assertEquals(output.select("call_id").asOptString, Some("call_42"))
    assertEquals(output.select("output").asOptString, Some("""{"temperature":18}"""))
  }

  test("the azure provider only takes the native path on the v1 api surface") {
    val options = AzureOpenAiChatClientOptions(model = Some("gpt-4.1"), responses = true)
    def clientWith(version: String, opts: AzureOpenAiChatClientOptions): AzureOpenAiChatClient = {
      new AzureOpenAiChatClient(new AzureOpenAiApi("res", "dep", version, Some("key"), None, 30.seconds, otoroshi.env), opts, "provider_azure")
    }
    assert(clientWith("v1", options).supportsResponses, "the native path should be used on the v1 api surface")
    // a dated api version is deployment-scoped and has no /responses endpoint
    assert(!clientWith("2024-02-01", options).supportsResponses, "a dated api version has no native /responses endpoint")
    assert(!clientWith("v1", options.copy(responses = false)).supportsResponses, "the option is off by default")
  }

  test("usage reported in the final response.completed event is non-zero, natively or not") {
    Seq(
      ("degraded.oto.tools", "degraded stream", 21),
      ("native.oto.tools", "native stream", 31),
      ("opendegraded.oto.tools", "degraded stream", 21),
      ("opennative.oto.tools", "native stream", 31),
      ("groqnative.oto.tools", "native stream", 31),
      ("xainative.oto.tools", "native stream", 31),
      ("ollamanative.oto.tools", "native stream", 31),
    ).foreach { case (domain, expectedText, expectedInputTokens) =>
      val events = responsesStream(domain, Json.obj("model" -> "gpt-4.1", "input" -> "hello", "stream" -> true))
      val completed = events.filter(_.select("type").asOptString.contains("response.completed"))
      assertEquals(completed.size, 1, s"[${domain}] there should be exactly one response.completed event: ${events.map(_.stringify)}")
      val response = completed.head.select("response").as[JsObject]
      assertEquals(response.at("usage.input_tokens").asOpt[Int], Some(expectedInputTokens), s"[${domain}] bad input tokens: ${response.stringify}")
      assert(response.at("usage.output_tokens").asOpt[Int].exists(_ > 0), s"[${domain}] output tokens should not be zero: ${response.stringify}")
      val deltas = events.filter(_.select("type").asOptString.contains("response.output_text.delta"))
        .flatMap(_.select("delta").asOptString).mkString("")
      assertEquals(deltas, expectedText, s"[${domain}] bad streamed text")
      assertEquals(outputText(response), expectedText, s"[${domain}] bad text in the completed event")
    }
  }
}
