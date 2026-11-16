package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.entities.*
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatMessage, ChatPrompt, ChatResponseChunk, LlmExtensionOneOtoroshiServerPerSuite}
import org.apache.pekko.stream.scaladsl.Sink
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.*
import reactor.core.publisher.{Flux, Mono}
import reactor.netty.http.server.HttpServerResponse

import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.concurrent.duration.DurationInt

// `connection.ttft_timeout`: a streamed call whose first token does not come in time is cancelled, and fails with
// `first_token_timeout` while nothing was sent to the caller, so the provider fallback can still answer it.
class FirstTokenTimeoutSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  private def chunk(delta: JsObject, finishReason: JsValue = JsNull): String = "data: " + Json.obj(
    "id" -> "chatcmpl-1", "object" -> "chat.completion.chunk", "created" -> 1700000000, "model" -> "gpt-4o-mini",
    "choices" -> Json.arr(Json.obj("index" -> 0, "delta" -> delta, "finish_reason" -> finishReason)),
  ).stringify + "\n\n"

  private val roleChunk = chunk(Json.obj("role" -> "assistant", "content" -> ""))
  private def contentChunk(text: String) = chunk(Json.obj("content" -> text))
  private val stopChunk = chunk(Json.obj(), JsString("stop"))
  private val done = "data: [DONE]\n\n"

  private def sse(response: HttpServerResponse, body: org.reactivestreams.Publisher[String]) =
    response.status(200).addHeader("Content-Type", "text/event-stream").sendString(body).`then`()

  // a server per case: a server of the suite does not take many calls
  class Upstream(name: String)(answer: (JsValue, HttpServerResponse) => org.reactivestreams.Publisher[Void]) {
    val calls = new AtomicInteger(0)
    val cancelled = new AtomicBoolean(false)
    val (port, _) = createTestServerWithRoutes(name, routes => routes
      .post("/v1/chat/completions", (req, response) => req.receive().aggregate().asString().flatMap { raw =>
        calls.incrementAndGet()
        Mono.from(answer(Json.parse(raw), response))
      })
    )
    def baseUrl: String = s"http://localhost:${port}/v1"
  }

  def answering(name: String): Upstream = new Upstream(name)((_, response) =>
    sse(response, Flux.just(roleChunk, contentChunk("hello"), contentChunk(" world"), stopChunk, done)))

  // headers and an empty `role: assistant` chunk at once, then nothing until the client goes away
  def silent(name: String): Upstream = {
    lazy val upstream: Upstream = new Upstream(name)((_, response) =>
      sse(response, Flux.concat(Flux.just(roleChunk), Flux.never[String]()).doOnCancel(() => upstream.cancelled.set(true))))
    upstream
  }

  def provider(name: String, baseUrl: String, connection: JsObject = Json.obj(), fallback: Option[String] = None, options: JsObject = Json.obj()): AiProvider = AiProvider(
    id = s"provider_${UUID.randomUUID()}",
    name = name,
    provider = "openai",
    connection = Json.obj("base_url" -> baseUrl, "token" -> "sk-test", "timeout" -> 30000) ++ connection,
    options = Json.obj("model" -> "gpt-4o-mini") ++ options,
    providerFallback = fallback,
  )

  def save(providers: AiProvider*): Unit = {
    providers.foreach(p => assert(client.forLlmEntity("providers").upsertEntity(p).awaitf(10.seconds).createdOrUpdated, s"${p.name} should be saved"))
    await(1.second)
  }

  def ext: AiExtension = otoroshi.env.adminExtensions.extension[AiExtension].get

  // the chunks of a streamed call, or its error
  def stream(p: AiProvider, attrs: TypedMap = TypedMap.empty): Either[JsValue, Seq[ChatResponseChunk]] = {
    given env: Env = otoroshi.env
    val chatClient = ext.states.provider(p.id).get.getChatClient().get
    chatClient.stream(ChatPrompt(Seq(ChatMessage.userStrInput("hello"))), attrs, Json.obj())(using otoroshi.executionContext, env).awaitf(30.seconds) match {
      case Left(err) => Left(err)
      case Right(source) => Right(source.runWith(Sink.seq)(using otoroshi.materializer).awaitf(30.seconds))
    }
  }

  def text(chunks: Seq[ChatResponseChunk]): String = chunks.flatMap(_.choices.flatMap(_.delta.content)).mkString

  def isTimeout(err: JsValue): Boolean = err.select("error").select("code").asOpt[String].contains("first_token_timeout")

  test("a provider without ttft_timeout waits for its first token as long as its timeout allows") {
    val slow = new Upstream("ttft-guard")((_, response) =>
      sse(response, Flux.concat(Flux.just(roleChunk), Flux.just(contentChunk("late"), stopChunk, done).delaySequence(Duration.ofMillis(1200)))))
    val p = provider("no first token timeout", slow.baseUrl)
    save(p)
    val result = stream(p)
    assert(result.isRight, s"the call should succeed: ${result}")
    assertEquals(text(result.toOption.get), "late")
  }

  test("a first token in time leaves the stream go on past the deadline") {
    val long = new Upstream("ttft-in-time")((_, response) =>
      sse(response, Flux.concat(
        Flux.just(roleChunk),
        Flux.range(0, 12).map[String](i => contentChunk(s"$i,")).delayElements(Duration.ofMillis(100)),
        Flux.just(stopChunk, done),
      )))
    val p = provider("first token in time", long.baseUrl, Json.obj("ttft_timeout" -> 500))
    save(p)
    val start = System.currentTimeMillis()
    val result = stream(p)
    assert(result.isRight, s"the call should succeed: ${result}")
    assertEquals(text(result.toOption.get), (0 until 12).map(i => s"$i,").mkString)
    assert(System.currentTimeMillis() - start >= 1000L, "the stream lasts longer than the deadline")
  }

  test("an empty role chunk is no token: the call times out and the connection to the provider is closed") {
    val upstream = silent("ttft-silent")
    val p = provider("silent after its role chunk", upstream.baseUrl, Json.obj("ttft_timeout" -> 500))
    save(p)
    val start = System.currentTimeMillis()
    val result = stream(p)
    val elapsed = System.currentTimeMillis() - start
    assert(result.left.exists(isTimeout), s"the call should time out: ${result}")
    assertEquals(result.left.toOption.get.select("status").asOpt[Int], Some(504))
    assert(elapsed < 5000L, s"the call should fail at the deadline, not after ${elapsed} ms")
    await(1.second)
    assert(upstream.cancelled.get(), "the stream of the provider should be cancelled")
  }

  test("the fallback answers when the first token of the primary is late") {
    val primary = silent("ttft-fallback-primary")
    val backup = answering("ttft-fallback-backup")
    val fallback = provider("fallback of a late provider", backup.baseUrl)
    val p = provider("late with a fallback", primary.baseUrl, Json.obj("ttft_timeout" -> 500), Some(fallback.id))
    save(fallback, p)
    val result = stream(p)
    assert(result.isRight, s"the fallback should answer: ${result}")
    assertEquals(text(result.toOption.get), "hello world")
    assertEquals(backup.calls.get(), 1)
    await(1.second)
    assert(primary.cancelled.get(), "the stream of the primary should be cancelled")
  }

  test("headers coming after the deadline: the call fails at the deadline, the body is cancelled when they come") {
    val bodyCancelled = new AtomicBoolean(false)
    val late = new Upstream("ttft-late-headers")((_, response) =>
      Mono.delay(Duration.ofMillis(1500)).flatMap { _ =>
        Mono.from(sse(response, Flux.interval(Duration.ofMillis(100)).map[String](i => contentChunk(s"$i")).take(50).doOnCancel(() => bodyCancelled.set(true))))
      })
    val p = provider("late headers", late.baseUrl, Json.obj("ttft_timeout" -> 500))
    save(p)
    val start = System.currentTimeMillis()
    val result = stream(p)
    val elapsed = System.currentTimeMillis() - start
    assert(result.left.exists(isTimeout), s"the call should time out: ${result}")
    assert(elapsed < 1400L, s"the call should fail before the headers come, not after ${elapsed} ms")
    await(3.seconds)
    assert(bodyCancelled.get(), "the body should be cancelled once the headers came")
  }

  test("a stream failing before any token is an error the fallback takes over") {
    val broken = new Upstream("ttft-broken")((_, response) => sse(response, Flux.just(roleChunk, "data: {broken\n\n")))
    val backup = answering("ttft-broken-backup")
    val fallback = provider("fallback of a broken provider", backup.baseUrl)
    val p = provider("broken before its first token", broken.baseUrl, Json.obj("ttft_timeout" -> 5000), Some(fallback.id))
    save(fallback, p)
    val result = stream(p)
    assert(result.isRight, s"the fallback should answer: ${result}")
    assertEquals(text(result.toOption.get), "hello world")
  }

  test("a tool run by the gateway slower than the deadline is not a late provider") {
    val (toolPort, _) = createTestServer("ttft-slow-tool", (_, response) =>
      Mono.delay(Duration.ofMillis(1500)).flatMap(_ => response.status(200).addHeader("Content-Type", "application/json").sendString(Mono.just("""{"weather":"sunny"}""")).`then`()))
    val tool = LlmToolFunction(
      id = s"tool-function_${UUID.randomUUID()}",
      name = "weather",
      description = "the weather",
      strict = false,
      parameters = Json.obj("city" -> Json.obj("type" -> "string", "description" -> "the city")),
      backend = LlmToolFunctionBackend(
        kind = LlmToolFunctionBackendKind.Http,
        options = LlmToolFunctionBackendOptions.Http(Json.obj("url" -> s"http://localhost:${toolPort}/weather", "method" -> "GET")),
      ),
    )
    val model = new Upstream("ttft-tools")((body, response) => {
      val ranItsTool = body.select("messages").asOpt[Seq[JsObject]].getOrElse(Seq.empty).exists(_.select("role").asOptString.contains("tool"))
      // the tool, as the gateway named it for the model
      val name = body.select("tools").asOpt[Seq[JsObject]].flatMap(_.headOption).map(_.at("function.name").asString).getOrElse("weather")
      if (ranItsTool) sse(response, Flux.just(roleChunk, contentChunk("sunny"), stopChunk, done))
      else sse(response, Flux.just(
        chunk(Json.obj("role" -> "assistant", "tool_calls" -> Json.arr(Json.obj("index" -> 0, "id" -> "call_1", "type" -> "function", "function" -> Json.obj("name" -> name, "arguments" -> ""))))),
        chunk(Json.obj("tool_calls" -> Json.arr(Json.obj("index" -> 0, "function" -> Json.obj("arguments" -> """{"city":"Paris"}"""))))),
        chunk(Json.obj(), JsString("tool_calls")),
        // the usage comes after the last choice, as OpenAI sends it: the tool loop runs the calls on it
        "data: " + Json.obj("id" -> "chatcmpl-1", "object" -> "chat.completion.chunk", "created" -> 1700000000, "model" -> "gpt-4o-mini", "choices" -> Json.arr(),
          "usage" -> Json.obj("prompt_tokens" -> 10, "completion_tokens" -> 5, "total_tokens" -> 15)).stringify + "\n\n",
        done,
      ))
    })
    assert(client.forLlmEntity("tool-functions").upsertEntity(tool).awaitf(10.seconds).createdOrUpdated, "the tool should be saved")
    val p = provider("runs a slow tool", model.baseUrl, Json.obj("ttft_timeout" -> 700), options = Json.obj("wasm_tools" -> Json.arr(tool.id)))
    save(p)
    val result = stream(p)
    assert(result.isRight, s"the call should succeed: ${result}")
    assertEquals(text(result.toOption.get), "sunny")
    assertEquals(model.calls.get(), 2)
  }

  test("late first tokens open the circuit of the primary, which is then skipped") {
    val primary = silent("ttft-circuit-primary")
    val backup = answering("ttft-circuit-backup")
    val fallback = provider("fallback of a provider whose circuit opens", backup.baseUrl)
    val p = provider("late, with a circuit breaker", primary.baseUrl, Json.obj("ttft_timeout" -> 400), Some(fallback.id),
      Json.obj("circuit_breaker" -> Json.obj("enabled" -> true, "consecutive_failures" -> 2, "cooldown" -> 60000)))
    save(fallback, p)
    (1 to 3).foreach { i =>
      val result = stream(p)
      assert(result.isRight, s"call ${i}: the fallback should answer: ${result}")
    }
    assertEquals(primary.calls.get(), 2, "the third call should skip the primary")
    assertEquals(backup.calls.get(), 3)
  }
}
