package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatErrors, LlmExtensionOneOtoroshiServerPerSuite}
import otoroshi.models.EntityLocation
import otoroshi.next.models.*
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.{AnthropicCompatProxy, OpenAiCompatProxy}
import play.api.libs.json.{JsObject, JsValue, Json}
import reactor.core.publisher.Mono

import java.util.UUID
import scala.concurrent.duration.DurationInt

// Issue #247: an error of the provider reaches the caller with a status that tells whose failure it is. The provider
// refusing the credentials of the gateway or failing is a 502, its 429 goes back with its Retry-After, its other 4xx
// are the mistakes of the request and go back as they are.
class ProviderErrorsSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  val refusedBody: JsObject = Json.obj("error" -> Json.obj("message" -> "Incorrect authentication", "code" -> "E_AUTHENTICATION_FAILED"))
  val limitedBody: JsObject = Json.obj("error" -> Json.obj("message" -> "Rate limit reached", "type" -> "requests", "code" -> "rate_limit_exceeded"))
  val invalidBody: JsObject = Json.obj("error" -> Json.obj("message" -> "This model's maximum context length is 8192 tokens", "type" -> "invalid_request_error", "code" -> "context_length_exceeded"))
  val failingBody: JsObject = Json.obj("error" -> Json.obj("message" -> "The server had an error while processing your request", "type" -> "server_error"))

  // one server per error, each one under a few calls, see the limits of the fake servers
  def errorServer(name: String, status: Int, body: JsObject, headers: Seq[(String, String)] = Seq.empty): Int = {
    val (port, _) = createTestServerWithRoutes(name, routes => routes
      .post("/v1/chat/completions", (req, response) => {
        req.receive().aggregate().asString().flatMap { _ =>
          headers.foldLeft(response.status(status).addHeader("Content-Type", "application/json")) {
            case (resp, (name, value)) => resp.addHeader(name, value)
          }.sendString(Mono.just(body.stringify)).`then`()
        }
      })
    )
    port
  }

  val refusedPort = errorServer("provider-errors-refused", 401, refusedBody)
  val limitedPort = errorServer("provider-errors-limited", 429, limitedBody, Seq("Retry-After" -> "7"))
  val invalidPort = errorServer("provider-errors-invalid", 400, invalidBody)
  val failingPort = errorServer("provider-errors-failing", 500, failingBody)

  // a provider per route: what a provider answered (a 429, a refused key) is remembered for it by the quotas
  def provider(name: String, port: Int): AiProvider = AiProvider(
    id = s"provider_${UUID.randomUUID()}",
    name = name,
    provider = "openai",
    connection = Json.obj("base_url" -> s"http://localhost:${port}/v1", "token" -> "sk-test", "timeout" -> 10000),
    options = Json.obj("model" -> "gpt-4o-mini"),
  )

  def route(domain: String, provider: AiProvider, plugin: String): NgRoute = NgRoute(
    location = EntityLocation.default,
    id = UUID.randomUUID().toString,
    name = s"provider errors ${domain}",
    description = "provider errors",
    tags = Seq.empty,
    metadata = Map.empty,
    enabled = true,
    debugFlow = false,
    capture = false,
    exportReporting = false,
    frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath(domain))),
    backend = NgBackend.empty.copy(targets = Seq(NgTarget.default)),
    plugins = NgPlugins(Seq(NgPluginInstance(
      plugin = s"cp:${plugin}",
      config = NgPluginInstanceConfig(Json.obj("refs" -> Json.arr(provider.id)))
    )))
  )

  val openai = classOf[OpenAiCompatProxy].getName
  val anthropic = classOf[AnthropicCompatProxy].getName

  val refused = provider("refused", refusedPort)
  val refusedStream = provider("refused stream", refusedPort)
  val refusedAnthropic = provider("refused anthropic", refusedPort)
  val limited = provider("limited", limitedPort)
  val limitedAnthropic = provider("limited anthropic", limitedPort)
  val invalid = provider("invalid", invalidPort)
  val failing = provider("failing", failingPort)
  val providers = Seq(refused, refusedStream, refusedAnthropic, limited, limitedAnthropic, invalid, failing)

  val routes = Seq(
    route("refused.errors.oto.tools", refused, openai),
    route("refused-stream.errors.oto.tools", refusedStream, openai),
    route("refused-anthropic.errors.oto.tools", refusedAnthropic, anthropic),
    route("limited.errors.oto.tools", limited, openai),
    route("limited-anthropic.errors.oto.tools", limitedAnthropic, anthropic),
    route("invalid.errors.oto.tools", invalid, openai),
    route("failing.errors.oto.tools", failing, openai),
  )

  override def beforeAll(): Unit = {
    super.beforeAll()
    providers.foreach(p => client.forLlmEntity("providers").upsertEntity(p).awaitf(10.seconds))
    routes.foreach(r => client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(r).awaitf(10.seconds))
    await(2.seconds)
  }

  def chat(domain: String, stream: Boolean = false) = client.call("POST", s"http://${domain}:${port}/v1/chat/completions", Map.empty, Some(Json.obj(
    "model" -> "gpt-4o-mini",
    "messages" -> Json.arr(Json.obj("role" -> "user", "content" -> "hello")),
    "stream" -> stream,
  ))).awaitf(30.seconds)

  def messages(domain: String) = client.call("POST", s"http://${domain}:${port}/v1/messages", Map.empty, Some(Json.obj(
    "model" -> "gpt-4o-mini",
    "max_tokens" -> 16,
    "messages" -> Json.arr(Json.obj("role" -> "user", "content" -> "hello")),
  ))).awaitf(30.seconds)

  def json(body: String): JsValue = Json.parse(body)

  test("the status a caller gets for what a provider answered") {
    assertEquals(Seq(401, 402, 403, 407, 500, 503, 529).map(ChatErrors.callerStatus), Seq(502, 502, 502, 502, 502, 502, 502))
    assertEquals(Seq(400, 404, 413, 422, 429).map(ChatErrors.callerStatus), Seq(400, 404, 413, 422, 429))
    val upstream = ChatErrors.upstream(429, limitedBody, Some("7"))
    assertEquals(ChatErrors.answer(upstream), ChatErrors.Answer(429, limitedBody, Some("7")))
    // a provider answering something that is not json: the caller gets its text with the status, as before
    val html = ChatErrors.upstream(503, play.api.libs.json.JsString("<html>down</html>"), None)
    assertEquals(ChatErrors.answer(html), ChatErrors.Answer(502, html, None))
    // an error of the gateway that tells its status is answered with it, any other one is a 400 as before
    val noTarget = Json.obj("error" -> Json.obj("code" -> "no_target_available"), "status" -> 503)
    assertEquals(ChatErrors.answer(noTarget).status, 503)
    assertEquals(ChatErrors.answer(Json.obj("error" -> "you can't use this model")).status, 400)
  }

  test("a provider refusing the credentials of the gateway is a 502, streamed or not, on the OpenAI and the Anthropic routes") {
    val resp = chat("refused.errors.oto.tools")
    assertEquals(resp.status, 502, resp.body)
    assertEquals(json(resp.body), refusedBody)

    val streamed = chat("refused-stream.errors.oto.tools", stream = true)
    assertEquals(streamed.status, 502, streamed.body)
    assertEquals(json(streamed.body), refusedBody)

    val anthropicResp = messages("refused-anthropic.errors.oto.tools")
    assertEquals(anthropicResp.status, 502, anthropicResp.body)
    assertEquals(json(anthropicResp.body).select("error").select("type").asString, "api_error")
    assert(json(anthropicResp.body).select("error").select("message").asString.contains("Incorrect authentication"), anthropicResp.body)
  }

  test("a 429 of the provider goes back with its Retry-After") {
    val resp = chat("limited.errors.oto.tools")
    assertEquals(resp.status, 429, resp.body)
    assertEquals(resp.header("Retry-After"), Some("7"))
    assertEquals(json(resp.body), limitedBody)

    val anthropicResp = messages("limited-anthropic.errors.oto.tools")
    assertEquals(anthropicResp.status, 429, anthropicResp.body)
    assertEquals(anthropicResp.header("Retry-After"), Some("7"))
    assertEquals(json(anthropicResp.body).select("error").select("type").asString, "rate_limit_error")
  }

  test("a mistake of the request stays the 400 of the provider, a failing provider is a 502") {
    val invalidResp = chat("invalid.errors.oto.tools")
    assertEquals(invalidResp.status, 400, invalidResp.body)
    assertEquals(json(invalidResp.body), invalidBody)

    val failingResp = chat("failing.errors.oto.tools")
    assertEquals(failingResp.status, 502, failingResp.body)
    assertEquals(json(failingResp.body), failingBody)
  }
}
