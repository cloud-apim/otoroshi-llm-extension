package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.providers.{OpenRouterApi, OpenRouterAudioModelClient, OpenRouterAudioModelClientSttOptions, OpenRouterAudioModelClientTtsOptions, ProviderHelpers}
import com.cloud.apim.otoroshi.extensions.aigateway.{AudioModelClientTextToSpeechInputOptions, ChatMessage, ChatPrompt, LlmExtensionOneOtoroshiServerPerSuite}
import otoroshi.models.EntityLocation
import otoroshi.next.models.*
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.OpenAiResponsesProxy
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.libs.ws.JsonBodyWritables.writeableOf_JsValue
import reactor.core.publisher.{Flux, Mono}

import java.time.Duration
import java.util.UUID
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

// Issue #195: the error body of a provider answering a streamed call with a non-200 status must reach the client,
// whatever its size, its pace or its format. play-ws cannot read the body of a response from `.stream()` in a
// blocking way past 13 chunks or 50 ms, and a body that is not json must not crash the call either.
class StreamErrorsSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  // a validation error echoing a long input, about 2 MB, sent in many chunks
  val largeError: JsObject = Json.obj(
    "object" -> "error",
    "message" -> "input validation failed",
    "type" -> "invalid_request_error",
    "detail" -> Json.arr(Json.obj("msg" -> "unsupported content", "input" -> ("x" * (2 * 1024 * 1024)))),
  )
  val largeErrorChunks: java.lang.Iterable[String] = largeError.stringify.grouped(16 * 1024).toSeq.asJava

  val slowError: JsObject = Json.obj("error" -> Json.obj("message" -> "slow validation error", "type" -> "invalid_request_error"))
  val htmlError: String = "<html><body><h1>503 Service Unavailable</h1></body></html>"

  // each server stays under a few calls, see the limits of the fake servers
  val (largePort, _) = createTestServerWithRoutes("stream-errors-large", routes => routes
    .post("/large/v1/chat/completions", (req, response) => {
      req.receive().aggregate().asString().flatMap { _ =>
        response.status(422).addHeader("Content-Type", "application/json").sendString(Flux.fromIterable(largeErrorChunks)).`then`()
      }
    })
    .post("/large/v1/audio/speech", (req, response) => {
      req.receive().aggregate().asString().flatMap { _ =>
        response.status(422).addHeader("Content-Type", "application/json").sendString(Flux.fromIterable(largeErrorChunks)).`then`()
      }
    })
  )

  val (smallPort, _) = createTestServerWithRoutes("stream-errors-small", routes => routes
    .post("/slow/v1/chat/completions", (req, response) => {
      req.receive().aggregate().asString().flatMap { _ =>
        // the body ends well after the 50 ms play-ws allows to a blocking read
        val (first, second) = slowError.stringify.splitAt(10)
        response.status(400).addHeader("Content-Type", "application/json").sendString(Flux.just(first, second).delayElements(Duration.ofMillis(300))).`then`()
      }
    })
    .post("/html/v1/chat/completions", (req, response) => {
      req.receive().aggregate().asString().flatMap { _ =>
        response.status(503).addHeader("Content-Type", "text/html").sendString(Mono.just(htmlError)).`then`()
      }
    })
  )

  def provider(kind: String, baseUrl: String): AiProvider = AiProvider(
    id = s"provider_${UUID.randomUUID()}",
    name = s"stream errors ${kind}",
    provider = kind,
    connection = Json.obj("base_url" -> baseUrl, "token" -> "secret", "timeout" -> 30000),
    options = Json.obj("model" -> "mistral-medium-latest"),
  )

  val mistral = provider("mistral", s"http://localhost:${largePort}/large/v1")
  val slow = provider("openai", s"http://localhost:${smallPort}/slow/v1")
  val html = provider("openai", s"http://localhost:${smallPort}/html/v1")

  val mistralRoute = NgRoute(
    location = EntityLocation.default,
    id = UUID.randomUUID().toString,
    name = "stream errors route",
    description = "stream errors route",
    tags = Seq.empty,
    metadata = Map.empty,
    enabled = true,
    debugFlow = false,
    capture = false,
    exportReporting = false,
    frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath("streamerrors.oto.tools/responses"))),
    backend = NgBackend.empty.copy(targets = Seq(NgTarget.default)),
    plugins = NgPlugins(Seq(NgPluginInstance(
      plugin = s"cp:${classOf[OpenAiResponsesProxy].getName}",
      config = NgPluginInstanceConfig(Json.obj("refs" -> Json.arr(mistral.id)))
    )))
  )

  override def beforeAll(): Unit = {
    super.beforeAll()
    Seq(mistral, slow, html).foreach(p => client.forLlmEntity("providers").upsertEntity(p).awaitf(10.seconds))
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(mistralRoute).awaitf(10.seconds)
    await(2.seconds)
  }

  def ext: AiExtension = otoroshi.env.adminExtensions.extension[AiExtension].get

  def stream(p: AiProvider): Either[JsValue, ?] = {
    given env: _root_.otoroshi.env.Env = otoroshi.env
    val chatClient = ext.states.provider(p.id).get.getChatClient().get
    chatClient.stream(ChatPrompt(Seq(ChatMessage.userStrInput("hello"))), TypedMap.empty, Json.obj())(using otoroshi.executionContext, env).awaitf(30.seconds)
  }

  def call(p: AiProvider): Either[JsValue, ?] = {
    given env: _root_.otoroshi.env.Env = otoroshi.env
    val chatClient = ext.states.provider(p.id).get.getChatClient().get
    chatClient.call(ChatPrompt(Seq(ChatMessage.userStrInput("hello"))), TypedMap.empty, Json.obj())(using otoroshi.executionContext, env).awaitf(30.seconds)
  }

  test("a large error body of a streamed call reaches the client instead of a 502") {
    val resp = client.call("POST", s"http://streamerrors.oto.tools:${port}/responses", Map.empty, Some(Json.obj(
      "model" -> "mistral-medium-latest",
      "input" -> "hello",
      "stream" -> true,
    ))).awaitf(30.seconds)
    assertEquals(resp.status, 400, resp.bodyAsBytes.utf8String.take(500))
    assertEquals(Json.parse(resp.bodyAsBytes.utf8String), largeError)
  }

  test("an error body sent slowly on a streamed call is read") {
    assertEquals(stream(slow), Left(slowError))
  }

  test("an error body that is not json is kept with the status") {
    val expected = Json.obj("status" -> 503, "body" -> htmlError)
    assertEquals(stream(html), Left(expected))
    assertEquals(call(html), Left(expected))
  }

  test("the error body of a streamed text to speech call is read") {
    given env: _root_.otoroshi.env.Env = otoroshi.env
    val audio = new OpenRouterAudioModelClient(
      new OpenRouterApi(s"http://localhost:${largePort}/large/v1", "secret", env = env),
      OpenRouterAudioModelClientTtsOptions(Json.obj()),
      OpenRouterAudioModelClientSttOptions(Json.obj()),
      "openrouter-tts",
    )
    val result = audio.textToSpeech(AudioModelClientTextToSpeechInputOptions("hello"), Json.obj(), TypedMap.empty)(using otoroshi.executionContext, env).awaitf(30.seconds)
    assertEquals(result.map(_ => ()), Left(Json.obj("error" -> "Bad response", "body" -> s"Failed with status 422: ${largeError.stringify}")))
  }

  test("an error body larger than the limit is truncated") {
    given env: _root_.otoroshi.env.Env = otoroshi.env
    val resp = env.Ws.url(s"http://localhost:${largePort}/large/v1/chat/completions")
      .withMethod("POST")
      .withBody(Json.obj("model" -> "mistral-medium-latest"): JsValue)
      .stream()
      .awaitf(30.seconds)
    val body = ProviderHelpers.readErrorBody("test", resp, env, 1024)(using otoroshi.executionContext).awaitf(30.seconds)
    assertEquals(body, largeError.stringify.take(1024))
  }
}
