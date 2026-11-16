package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.LlmExtensionOneOtoroshiServerPerSuite
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import otoroshi.models.EntityLocation
import otoroshi.next.models.*
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.{AnthropicCompatProxy, OpenAiCompatApi}
import play.api.libs.json.*
import play.api.libs.ws.WSResponse
import reactor.core.publisher.Mono
import reactor.netty.http.server.HttpServerResponse

import java.util.UUID
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.duration.DurationInt

// Issue #237: `POST /v1/messages/count_tokens` of the Anthropic messages api, answered by the provider when it has a
// route for it, or else as the route says: not at all, measured with a one token call, or estimated.
class CountTokensSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  private def json(response: HttpServerResponse, status: Int, body: JsValue) =
    response.status(status).addHeader("Content-Type", "application/json").sendString(Mono.just(body.stringify)).`then`()

  private def completion(promptTokens: Long): JsObject = Json.obj(
    "id" -> "chatcmpl-1", "object" -> "chat.completion", "created" -> 1700000000, "model" -> "gpt-4o-mini",
    "choices" -> Json.arr(Json.obj("index" -> 0, "finish_reason" -> "length", "message" -> Json.obj("role" -> "assistant", "content" -> "Hi"))),
    "usage" -> Json.obj("prompt_tokens" -> promptTokens, "completion_tokens" -> 1, "total_tokens" -> (promptTokens + 1)),
  )

  // a provider server, which keeps what it was sent. One per case: a server of the suite does not take many calls
  class Upstream(name: String) {
    val counted = new AtomicReference[JsValue](JsNull)
    val countCalls = new AtomicInteger(0)
    val chatCalls = new AtomicInteger(0)
    val chatBody = new AtomicReference[JsValue](JsNull)
    val (port, _) = createTestServerWithRoutes(s"count-tokens-$name", routes => routes
      .post("/v1/messages/count_tokens", (req, response) => req.receive().aggregate().asString().flatMap { raw =>
        countCalls.incrementAndGet()
        counted.set(Json.parse(raw))
        json(response, 200, Json.obj("input_tokens" -> 42))
      })
      .post("/v1/messages", (req, response) => req.receive().aggregate().asString().flatMap { _ =>
        chatCalls.incrementAndGet()
        json(response, 500, Json.obj("error" -> "a count of tokens must not call the model"))
      })
      .post("/tokenize", (req, response) => req.receive().aggregate().asString().flatMap { raw =>
        countCalls.incrementAndGet()
        counted.set(Json.parse(raw))
        json(response, 200, Json.obj("count" -> 17, "max_model_len" -> 8192, "tokens" -> Json.arr(1, 2, 3)))
      })
      .post("/v1/chat/completions", (req, response) => req.receive().aggregate().asString().flatMap { raw =>
        chatCalls.incrementAndGet()
        chatBody.set(Json.parse(raw))
        json(response, 200, completion(123))
      })
    )
  }

  def provider(name: String, kind: String, connection: JsObject, model: String, extra: JsObject = Json.obj()): AiProvider = {
    AiProvider.format.reads(AiProvider(
      id = s"provider_${UUID.randomUUID()}",
      name = name,
      provider = kind,
      connection = Json.obj("token" -> "sk-test", "timeout" -> 10000) ++ connection,
      options = Json.obj("model" -> model),
    ).json.asObject ++ extra).get
  }

  def route(host: String, plugin: NgPluginInstance): NgRoute = NgRoute(
    location = EntityLocation.default,
    id = UUID.randomUUID().toString,
    name = s"count tokens $host",
    description = "count tokens",
    tags = Seq.empty,
    metadata = Map.empty,
    enabled = true,
    debugFlow = false,
    capture = false,
    exportReporting = false,
    frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath(host))),
    backend = NgBackend.empty.copy(targets = Seq(NgTarget.default)),
    plugins = NgPlugins(Seq(plugin)),
  )

  def anthropicRoute(host: String, p: AiProvider, fallback: String = "none"): NgRoute = route(host, NgPluginInstance(
    plugin = s"cp:${classOf[AnthropicCompatProxy].getName}",
    config = NgPluginInstanceConfig(Json.obj("refs" -> Json.arr(p.id), "count_tokens_fallback" -> fallback)),
  ))

  def save(providers: Seq[AiProvider], routes: Seq[NgRoute]): Unit = {
    providers.foreach(p => assert(client.forLlmEntity("providers").upsertEntity(p).awaitf(10.seconds).createdOrUpdated, s"${p.name} should be saved"))
    routes.foreach(r => assert(client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(r).awaitf(10.seconds).createdOrUpdated, s"${r.name} should be saved"))
    await(2.seconds)
  }

  val request: JsObject = Json.obj(
    "model" -> "claude-sonnet-4-5",
    "system" -> "Answer briefly.",
    "messages" -> Json.arr(Json.obj("role" -> "user", "content" -> "What is the weather like in Paris today?")),
    "tools" -> Json.arr(Json.obj(
      "name" -> "weather",
      "description" -> "the weather of a city",
      "input_schema" -> Json.obj("type" -> "object", "properties" -> Json.obj("city" -> Json.obj("type" -> "string"))),
    )),
  )

  def count(host: String, path: String = "/v1/messages/count_tokens", body: JsObject = request): WSResponse =
    client.call("POST", s"http://$host:$port$path", Map("Content-Type" -> "application/json"), Some(body)).awaitf(30.seconds)

  test("an Anthropic provider counts the tokens itself, the model is not called") {
    val upstream = new Upstream("anthropic")
    val p = provider("anthropic counts", "anthropic", Json.obj("base_url" -> s"http://localhost:${upstream.port}"), "claude-sonnet-4-5")
    val compat = route("counttokens-unified.oto.tools", NgPluginInstance(
      plugin = s"cp:${classOf[OpenAiCompatApi].getName}",
      config = NgPluginInstanceConfig(Json.obj("language_model_refs" -> Json.arr(p.id))),
    ))
    save(Seq(p), Seq(anthropicRoute("counttokens-anthropic.oto.tools", p), compat))

    val resp = count("counttokens-anthropic.oto.tools")
    assertEquals(resp.status, 200, resp.body)
    assertEquals(resp.json, Json.obj("input_tokens" -> 42))
    assertEquals(resp.header("x-otoroshi-llm-tokens-count-method"), Some("provider"))
    val sent = upstream.counted.get()
    assertEquals(sent.select("model").asOpt[String], Some("claude-sonnet-4-5"))
    assertEquals(sent.select("system").as[Seq[JsObject]].map(_.select("text").asString), Seq("Answer briefly."))
    assertEquals(sent.select("tools").as[Seq[JsObject]].map(_.select("name").asString), Seq("weather"))
    assert(sent.select("messages").as[Seq[JsObject]].nonEmpty)
    assert(sent.select("max_tokens").isEmpty, s"only what the count route takes is sent: ${sent}")

    // the unified api of a workspace serves the route too
    val unified = count("counttokens-unified.oto.tools")
    assertEquals(unified.status, 200, unified.body)
    assertEquals(unified.json, Json.obj("input_tokens" -> 42))
    assertEquals(upstream.countCalls.get(), 2)
    assertEquals(upstream.chatCalls.get(), 0)
  }

  test("an openai compatible server with a tokenize route counts the tokens itself") {
    val upstream = new Upstream("vllm")
    val p = provider("vllm counts", "openai-compatible", Json.obj("base_url" -> s"http://localhost:${upstream.port}/v1", "tokenize_path" -> "/tokenize"), "mistral-medium")
    save(Seq(p), Seq(anthropicRoute("counttokens-vllm.oto.tools", p)))
    val resp = count("counttokens-vllm.oto.tools")
    assertEquals(resp.status, 200, resp.body)
    assertEquals(resp.json, Json.obj("input_tokens" -> 17))
    val sent = upstream.counted.get()
    assertEquals(sent.select("add_generation_prompt").asOpt[Boolean], Some(true))
    assertEquals(sent.select("messages").as[Seq[JsObject]].map(_.select("role").asString), Seq("system", "user"))
    assertEquals(sent.select("tools").as[Seq[JsObject]].map(_.at("function.name").asString), Seq("weather"))
    assertEquals(upstream.chatCalls.get(), 0)
  }

  test("without a provider route: not answered, measured with a one token call, or estimated") {
    val upstream = new Upstream("openai")
    val p = provider("no count route", "openai", Json.obj("base_url" -> s"http://localhost:${upstream.port}/v1"), "gpt-4o-mini",
      Json.obj("cache" -> Json.obj("strategy" -> "simple", "ttl" -> 60000)))
    save(Seq(p), Seq(
      anthropicRoute("counttokens-none.oto.tools", p),
      anthropicRoute("counttokens-measure.oto.tools", p, "measure"),
      anthropicRoute("counttokens-estimate.oto.tools", p, "estimate"),
    ))

    val refused = count("counttokens-none.oto.tools")
    assertEquals(refused.status, 501, refused.body)
    assertEquals(refused.json.select("type").asOpt[String], Some("error"))
    assertEquals(refused.json.at("error.type").asOpt[String], Some("api_error"))
    assertEquals(upstream.chatCalls.get(), 0)

    val measured = count("counttokens-measure.oto.tools")
    assertEquals(measured.status, 200, measured.body)
    assertEquals(measured.json, Json.obj("input_tokens" -> 123))
    assertEquals(measured.header("x-otoroshi-llm-tokens-count-method"), Some("measure"))
    val sent = upstream.chatBody.get()
    assertEquals(sent.select("max_completion_tokens").asOpt[Int].orElse(sent.select("max_tokens").asOpt[Int]), Some(1), s"${sent}")
    assert(!sent.select("stream").asOpt[Boolean].contains(true))
    // a measure is never served from the cache: it would not count what is sent now
    assertEquals(count("counttokens-measure.oto.tools").json, Json.obj("input_tokens" -> 123))
    assertEquals(upstream.chatCalls.get(), 2)

    val estimated = count("counttokens-estimate.oto.tools")
    assertEquals(estimated.status, 200, estimated.body)
    assertEquals(estimated.header("x-otoroshi-llm-tokens-count-method"), Some("estimate"))
    val tokens = estimated.json.select("input_tokens").as[Long]
    assert(tokens > 10 && tokens < 100, s"a rough figure for a short request: ${tokens}")
    assertEquals(upstream.chatCalls.get(), 2)
  }
}
