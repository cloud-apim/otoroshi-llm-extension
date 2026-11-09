package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.*
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.providers.UsageAccumulator
import otoroshi.models.EntityLocation
import otoroshi.next.models.*
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.{AnthropicCompatProxy, AnthropicStreamWriter, OpenAiCompatProxy, OpenAiResponsesProxy}
import play.api.libs.json.*
import reactor.core.publisher.{Flux, Mono}
import reactor.netty.http.server.HttpServerResponse

import java.util.UUID
import scala.concurrent.duration.DurationInt

// A provider that caches prompts reads the start of a prompt it saw recently from its cache, and bills those tokens
// much less. It says how many: OpenAI and the compatible apis in the prompt tokens (`prompt_tokens_details`),
// Anthropic apart from its `input_tokens`, with the tokens it wrote to its cache, which it bills more. Inside the
// gateway they are a part of the prompt tokens, given back to the caller in the words of its api, audited, and
// priced each at its own price.
class PromptCacheSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  def ext: AiExtension = otoroshi.env.adminExtensions.extension[AiExtension].get

  private def send(response: HttpServerResponse, body: JsValue) =
    response.status(200).addHeader("Content-Type", "application/json").sendString(Mono.just(body.stringify)).`then`()

  // 10 000 prompt tokens, 9 000 of them read from the cache of the provider
  val openaiUsage: JsObject = Json.obj(
    "prompt_tokens" -> 10000, "completion_tokens" -> 100, "total_tokens" -> 10100,
    "prompt_tokens_details" -> Json.obj("cached_tokens" -> 9000),
  )

  private def chunk(choices: JsArray, usage: Option[JsObject] = None): String = "data: " + (Json.obj(
    "id" -> "chatcmpl-1", "object" -> "chat.completion.chunk", "created" -> 1700000000, "model" -> "gpt-4.1", "choices" -> choices,
  ) ++ usage.map(u => Json.obj("usage" -> u)).getOrElse(Json.obj())).stringify + "\n\n"

  val (openaiPort, _) = createTestServerWithRoutes("prompt-cache-openai", routes => routes
    .post("/v1/chat/completions", (req, response) => req.receive().aggregate().asString().flatMap { body =>
      if (Json.parse(body).select("stream").asOpt[Boolean].contains(true)) {
        response.status(200).addHeader("Content-Type", "text/event-stream").sendString(Flux.just(
          chunk(Json.arr(Json.obj("index" -> 0, "delta" -> Json.obj("role" -> "assistant", "content" -> "hello"), "finish_reason" -> JsNull))),
          chunk(Json.arr(Json.obj("index" -> 0, "delta" -> Json.obj(), "finish_reason" -> "stop"))),
          chunk(Json.arr(), Some(openaiUsage)),
          "data: [DONE]\n\n",
        )).`then`()
      } else send(response, Json.obj(
        "id" -> "chatcmpl-1", "object" -> "chat.completion", "created" -> 1700000000, "model" -> "gpt-4.1",
        "choices" -> Json.arr(Json.obj("index" -> 0, "finish_reason" -> "stop", "message" -> Json.obj("role" -> "assistant", "content" -> "hello"))),
        "usage" -> openaiUsage,
      ))
    })
  )

  // 1 000 input tokens, 8 000 read from the cache and 1 000 written to it: 10 000 prompt tokens
  val (anthropicPort, _) = createTestServerWithRoutes("prompt-cache-anthropic", routes => routes
    .post("/v1/messages", (req, response) => req.receive().aggregate().asString().flatMap { _ =>
      send(response, Json.obj(
        "id" -> "msg_1", "type" -> "message", "role" -> "assistant", "model" -> "claude-sonnet-4-5", "stop_reason" -> "end_turn",
        "content" -> Json.arr(Json.obj("type" -> "text", "text" -> "hello")),
        "usage" -> Json.obj("input_tokens" -> 1000, "cache_read_input_tokens" -> 8000, "cache_creation_input_tokens" -> 1000, "output_tokens" -> 100),
      ))
    })
  )

  lazy val openai: AiProvider = AiProvider(
    id = s"provider_${UUID.randomUUID()}", name = "prompt cache openai", provider = "openai",
    connection = Json.obj("base_url" -> s"http://localhost:${openaiPort}/v1", "token" -> "sk-test", "timeout" -> 10000),
    options = Json.obj("model" -> "gpt-4.1"),
  )
  lazy val anthropic: AiProvider = AiProvider(
    id = s"provider_${UUID.randomUUID()}", name = "prompt cache anthropic", provider = "anthropic",
    connection = Json.obj("base_url" -> s"http://localhost:${anthropicPort}", "token" -> "sk-test", "timeout" -> 10000),
    options = Json.obj("model" -> "claude-sonnet-4-5", "max_tokens" -> 1024),
  )

  def route(name: String, plugin: Class[?], provider: AiProvider): NgRoute = NgRoute(
    location = EntityLocation.default,
    id = UUID.randomUUID().toString,
    name = s"prompt cache ${name}",
    description = s"prompt cache ${name}",
    tags = Seq.empty,
    metadata = Map.empty,
    enabled = true,
    debugFlow = false,
    capture = false,
    exportReporting = false,
    frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath(s"${name}.promptcache.oto.tools"))),
    backend = NgBackend.empty.copy(targets = Seq(NgTarget.default)),
    plugins = NgPlugins(Seq(NgPluginInstance(
      plugin = s"cp:${plugin.getName}",
      config = NgPluginInstanceConfig(Json.obj("refs" -> Json.arr(provider.id)))
    )))
  )

  lazy val setup: Unit = {
    Seq(openai, anthropic).foreach { p =>
      assert(client.forLlmEntity("providers").upsertEntity(p).awaitf(10.seconds).createdOrUpdated, s"${p.name} should be saved")
    }
    Seq(
      route("chat", classOf[OpenAiCompatProxy], openai),
      route("messages", classOf[AnthropicCompatProxy], openai),
      route("responses", classOf[OpenAiResponsesProxy], openai),
      route("anthropic", classOf[AnthropicCompatProxy], anthropic),
    ).foreach(r => client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(r).awaitf(10.seconds))
    await(5.seconds)
  }

  def post(name: String, body: JsObject): JsValue = {
    val resp = client.call("POST", s"http://${name}.promptcache.oto.tools:${port}/?embed_costs=true", Map.empty, Some(body)).awaitf(30.seconds)
    assertEquals(resp.status, 200, s"the call to ${name} should succeed, got ${resp.body}")
    resp.json
  }

  def chatBody(stream: Boolean = false): JsObject =
    Json.obj("model" -> "gpt-4.1", "messages" -> Json.arr(Json.obj("role" -> "user", "content" -> "hello"))) ++ (if (stream) Json.obj("stream" -> true) else Json.obj())
  def messagesBody(model: String): JsObject =
    Json.obj("model" -> model, "max_tokens" -> 1024, "messages" -> Json.arr(Json.obj("role" -> "user", "content" -> "hello")))

  // the price of a call, each kind of prompt token at its own price
  def priced(provider: String, model: String, uncached: Long, cached: Long, written: Long, output: Long): BigDecimal = {
    val m = ext.costsTracking.lookupModel(provider, model).get
    uncached * m.input_cost_per_token + cached * m.cache_read_input_token_cost + written * m.cache_creation_input_token_cost + output * m.output_cost_per_token
  }

  test("the usage of every api says how many prompt tokens the provider read from its cache, or wrote to it") {
    def usage(f: UsageAccumulator => Unit): ChatResponseMetadataUsage = {
      val acc = new UsageAccumulator()
      f(acc)
      acc.usage()
    }
    val openaiLike = usage(_.updateOpenai(Some(openaiUsage)))
    assertEquals((openaiLike.promptTokens, openaiLike.cachedTokens, openaiLike.cacheWriteTokens), (10000L, 9000L, 0L))
    // DeepSeek says it its own way
    val deepseek = usage(_.updateOpenai(Some(Json.obj("prompt_tokens" -> 100, "completion_tokens" -> 1, "prompt_cache_hit_tokens" -> 60, "prompt_cache_miss_tokens" -> 40))))
    assertEquals((deepseek.promptTokens, deepseek.cachedTokens), (100L, 60L))
    // OpenRouter reports what was written to the cache of the providers that charge for it
    val openrouter = usage(_.updateOpenai(Some(Json.obj("prompt_tokens" -> 100, "completion_tokens" -> 1, "prompt_tokens_details" -> Json.obj("cached_tokens" -> 50, "cache_write_tokens" -> 30)))))
    assertEquals((openrouter.cachedTokens, openrouter.cacheWriteTokens), (50L, 30L))
    // Anthropic counts them apart from the input: they are prompt tokens all the same
    val claude = usage(_.updateAnthropic(Some(Json.obj("input_tokens" -> 1000, "cache_read_input_tokens" -> 8000, "cache_creation_input_tokens" -> 1000, "output_tokens" -> 100))))
    assertEquals((claude.promptTokens, claude.cachedTokens, claude.cacheWriteTokens, claude.uncachedPromptTokens), (10000L, 8000L, 1000L, 1000L))
    val responses = usage(_.updateOpenaiResponses(Some(Json.obj("input_tokens" -> 100, "output_tokens" -> 1, "input_tokens_details" -> Json.obj("cached_tokens" -> 70)))))
    assertEquals((responses.promptTokens, responses.cachedTokens), (100L, 70L))
    // a provider that says nothing read nothing from its cache
    val none = usage(_.updateOpenai(Some(Json.obj("prompt_tokens" -> 100, "completion_tokens" -> 1))))
    assertEquals((none.cachedTokens, none.cacheWriteTokens), (0L, 0L))
  }

  test("each api gives them back in its own words") {
    val usage = ChatResponseMetadataUsage(promptTokens = 10000L, generationTokens = 100L, reasoningTokens = 0L, cachedTokens = 8000L, cacheWriteTokens = 1000L)
    assertEquals(usage.openaiJson.at("prompt_tokens_details.cached_tokens").asOpt[Long], Some(8000L))
    assertEquals(usage.openaiJson.at("prompt_tokens_details.cache_write_tokens").asOpt[Long], Some(1000L))
    assertEquals(usage.openaiJson.select("prompt_tokens").asOpt[Long], Some(10000L), "OpenAI counts them in the prompt tokens")
    assertEquals(usage.anthropicJson, Json.obj("input_tokens" -> 1000L, "cache_creation_input_tokens" -> 1000L, "cache_read_input_tokens" -> 8000L, "output_tokens" -> 100L))
    assertEquals(usage.openaiResponsesJson.at("input_tokens_details.cached_tokens").asOpt[Long], Some(8000L))
    assertEquals(OpenAiResponsesBodyConverter.usageJson(usage).at("input_tokens_details.cached_tokens").asOpt[Long], Some(8000L))
    assertEquals(usage.json.select("cached_tokens").asOpt[Long], Some(8000L), "what is audited")
    assertEquals(usage.json.select("cache_write_tokens").asOpt[Long], Some(1000L))
    // what was written is not sent back to an OpenAI caller that had none
    assertEquals(ChatResponseMetadataUsage(100L, 1L, 0L, cachedTokens = 10L).openaiJson.at("prompt_tokens_details.cache_write_tokens").asOpt[Long], None)
    // the end of a stream on the messages api
    val writer = new AnthropicStreamWriter()
    val chunk = ChatResponseChunk("id", 0L, "model", Seq(ChatResponseChunkChoice(0L, ChatResponseChunkChoiceDelta(Some("hi")), Some("stop"))), usage = Some(usage))
    val events = (writer.events(chunk) ++ writer.end()).map(_.utf8String)
    val delta = events.find(_.startsWith("event: message_delta")).map(e => Json.parse(e.linesIterator.find(_.startsWith("data: ")).get.stripPrefix("data: "))).get
    assertEquals(delta.select("usage").as[JsObject], usage.anthropicJson.as[JsObject])
  }

  test("a prompt token read from the cache, or written to it, is priced at its own price") {
    val costs = ext.costsTracking
    val gpt = costs.lookupModel("openai", "gpt-4.1").get
    assert(gpt.cache_read_input_token_cost > 0 && gpt.cache_read_input_token_cost < gpt.input_cost_per_token, "gpt-4.1 reads its cache for less")
    val read = costs.computeCosts("openai", "gpt-4.1", 10000L, 100L, 0L, cachedTokens = 9000L).toOption.get
    assertEquals(read.inputCost, 1000 * gpt.input_cost_per_token + 9000 * gpt.cache_read_input_token_cost)
    assert(read.inputCost < costs.computeCosts("openai", "gpt-4.1", 10000L, 100L, 0L).toOption.get.inputCost, "cheaper than the same prompt read again")
    val claude = costs.lookupModel("anthropic", "claude-sonnet-4-5").get
    assert(claude.cache_creation_input_token_cost > claude.input_cost_per_token, "Anthropic writes its cache for more")
    val written = costs.computeCosts("anthropic", "claude-sonnet-4-5", 10000L, 100L, 0L, cachedTokens = 8000L, cacheWriteTokens = 1000L).toOption.get
    assertEquals(written.inputCost, 1000 * claude.input_cost_per_token + 8000 * claude.cache_read_input_token_cost + 1000 * claude.cache_creation_input_token_cost)
    // a model without a price for its cache: an input token, never a free one
    val old = costs.lookupModel("openai", "gpt-4").get
    assertEquals(old.cache_read_input_token_cost, BigDecimal(0))
    assertEquals(costs.computeCosts("openai", "gpt-4", 10000L, 100L, 0L, cachedTokens = 9000L).toOption.get.inputCost, 10000 * old.input_cost_per_token)
    // counts that do not add up never price more tokens than the prompt has
    assertEquals(costs.computeCosts("openai", "gpt-4.1", 100L, 1L, 0L, cachedTokens = 500L).toOption.get.inputCost, 100 * gpt.cache_read_input_token_cost)
  }

  test("an OpenAI caller is told what was read from the cache, and pays for it at the price of the cache") {
    setup
    val resp = post("chat", chatBody())
    assertEquals(resp.at("usage.prompt_tokens").asOpt[Long], Some(10000L))
    assertEquals(resp.at("usage.prompt_tokens_details.cached_tokens").asOpt[Long], Some(9000L), s"${resp}")
    assertEquals(resp.at("costs.input_cost").asOpt[BigDecimal], Some(priced("openai", "gpt-4.1", 1000L, 9000L, 0L, 0L)), s"${resp}")
    // streamed, the last chunk says it
    val streamed = client.call("POST", s"http://chat.promptcache.oto.tools:${port}/", Map.empty, Some(chatBody(stream = true))).awaitf(30.seconds)
    assertEquals(streamed.status, 200)
    val usages = streamed.body.split("\n").toSeq.filter(_.startsWith("data: {")).map(l => Json.parse(l.stripPrefix("data: "))).flatMap(_.select("usage").asOpt[JsObject])
    assertEquals(usages.lastOption.flatMap(_.at("prompt_tokens_details.cached_tokens").asOpt[Long]), Some(9000L), s"${streamed.body}")
  }

  test("a caller of the messages and responses apis is told in the words of its api") {
    setup
    val messages = post("messages", messagesBody("gpt-4.1"))
    assertEquals(messages.at("usage.input_tokens").asOpt[Long], Some(1000L), s"${messages}")
    assertEquals(messages.at("usage.cache_read_input_tokens").asOpt[Long], Some(9000L))
    val responses = post("responses", Json.obj("model" -> "gpt-4.1", "input" -> "hello"))
    assertEquals(responses.at("usage.input_tokens").asOpt[Long], Some(10000L), s"${responses}")
    assertEquals(responses.at("usage.input_tokens_details.cached_tokens").asOpt[Long], Some(9000L))
  }

  test("Anthropic counts the tokens of its cache in the prompt, and prices them") {
    setup
    val resp = post("anthropic", messagesBody("claude-sonnet-4-5"))
    assertEquals(resp.select("usage").asOpt[JsObject], Some(Json.obj("input_tokens" -> 1000L, "cache_creation_input_tokens" -> 1000L, "cache_read_input_tokens" -> 8000L, "output_tokens" -> 100L)), s"${resp}")
    assertEquals(resp.at("costs.input_cost").asOpt[BigDecimal], Some(priced("anthropic", "claude-sonnet-4-5", 1000L, 8000L, 1000L, 0L)), s"${resp}")
  }
}
