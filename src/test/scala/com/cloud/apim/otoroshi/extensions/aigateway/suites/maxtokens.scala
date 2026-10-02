package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.providers.AzureOpenAiApi
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatMessage, ChatPrompt, LlmExtensionOneOtoroshiServerPerSuite}
import otoroshi.env.Env
import otoroshi.models.EntityLocation
import otoroshi.next.models.*
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.AnthropicCompatProxy
import play.api.libs.json.*
import reactor.core.publisher.Mono

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.DurationInt

// How long an answer may be is `max_tokens` for about every OpenAI compatible api, and `max_completion_tokens` for
// OpenAI, whose reasoning models refuse the first name. OpenAI is sent the name it wants; the other providers
// served by the same client are sent what the caller, or their own configuration, said. A request in the Anthropic
// format always carries a `max_tokens`: it reaches each provider under the name that provider knows. Azure OpenAI
// serves the models of OpenAI behind two api surfaces: `v1` is the one of OpenAI, the dated versions are older.
class MaxTokensSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  val received = new AtomicReference[JsValue](JsNull)
  val (openaiPort, _) = createTestServerWithRoutes("max-tokens", routes => routes
    .post("/v1/chat/completions", (req, response) => req.receive().aggregate().asString().flatMap { body =>
      received.set(Json.parse(body))
      response.status(200).addHeader("Content-Type", "application/json").sendString(Mono.just(Json.obj(
        "id" -> "chatcmpl-1", "object" -> "chat.completion", "created" -> 1700000000, "model" -> "a-model",
        "choices" -> Json.arr(Json.obj("index" -> 0, "finish_reason" -> "stop", "message" -> Json.obj("role" -> "assistant", "content" -> "hello"))),
        "usage" -> Json.obj("prompt_tokens" -> 1, "completion_tokens" -> 1, "total_tokens" -> 2),
      ).stringify)).`then`()
    })
  )

  def ext: AiExtension = otoroshi.env.adminExtensions.extension[AiExtension].get

  def provider(kind: String, options: JsObject = Json.obj()): AiProvider = AiProvider(
    id = s"provider_${UUID.randomUUID()}",
    name = s"max tokens ${kind}",
    provider = kind,
    connection = Json.obj("base_url" -> s"http://localhost:${openaiPort}/v1", "token" -> "sk-test", "timeout" -> 10000),
    options = Json.obj("model" -> "a-model") ++ options,
  )

  lazy val openai = provider("openai")
  lazy val compatible = provider("openai-compatible")
  lazy val deepseek = provider("deepseek")
  lazy val scaleway = provider("scaleway")
  // the limit set on the provider itself, the way an operator caps what a provider may answer
  lazy val capped = provider("deepseek", Json.obj("max_tokens" -> 64))

  lazy val mistral = provider("mistral")

  // the same providers behind the Anthropic messages api
  def messagesRoute(provider: AiProvider): NgRoute = NgRoute(
    location = EntityLocation.default,
    id = UUID.randomUUID().toString,
    name = s"messages on ${provider.provider}",
    description = s"messages on ${provider.provider}",
    tags = Seq.empty,
    metadata = Map.empty,
    enabled = true,
    debugFlow = false,
    capture = false,
    exportReporting = false,
    frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath(s"${provider.provider}.maxtokens.oto.tools/messages"))),
    backend = NgBackend.empty.copy(targets = Seq(NgTarget.default)),
    plugins = NgPlugins(Seq(NgPluginInstance(
      plugin = s"cp:${classOf[AnthropicCompatProxy].getName}",
      config = NgPluginInstanceConfig(Json.obj("refs" -> Json.arr(provider.id)))
    )))
  )

  lazy val setup: Unit = {
    Seq(openai, compatible, deepseek, scaleway, capped, mistral).foreach { p =>
      assert(client.forLlmEntity("providers").upsertEntity(p).awaitf(10.seconds).createdOrUpdated, s"${p.name} should be saved")
    }
    Seq(openai, compatible, deepseek, mistral).foreach { p =>
      client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(messagesRoute(p)).awaitf(10.seconds)
    }
    await(5.seconds)
  }

  private def limits(body: JsValue): JsObject =
    JsObject(body.as[JsObject].value.filter { case (name, _) => name == "max_tokens" || name == "max_completion_tokens" }.toSeq)

  // the limits of the body the provider received for a call made with `body`
  def limitsSent(provider: AiProvider, body: JsObject): JsObject = {
    given env: Env = otoroshi.env
    received.set(JsNull)
    val resp = ext.states.provider(provider.id).flatMap(_.getChatClient()).get
      .call(ChatPrompt(Seq(ChatMessage.userStrInput("hello"))), TypedMap.empty, body)(using ec, env)
      .awaitf(30.seconds)
    assert(resp.isRight, s"the call should succeed, got ${resp}")
    limits(received.get())
  }

  // the limits of the body the provider received for a request in the Anthropic format
  def limitsSentForMessages(provider: AiProvider): JsObject = {
    received.set(JsNull)
    val resp = client.call("POST", s"http://${provider.provider}.maxtokens.oto.tools:${port}/messages", Map.empty, Some(Json.obj(
      "model" -> "a-model",
      "max_tokens" -> 50,
      "messages" -> Json.arr(Json.obj("role" -> "user", "content" -> "hello")),
    ))).awaitf(30.seconds)
    assertEquals(resp.status, 200, s"the call should succeed, got ${resp.body}")
    limits(received.get())
  }

  test("OpenAI is sent max_completion_tokens, whatever name the caller used") {
    setup
    assertEquals(limitsSent(openai, Json.obj("max_tokens" -> 50)), Json.obj("max_completion_tokens" -> 50))
    assertEquals(limitsSent(openai, Json.obj("max_completion_tokens" -> 50)), Json.obj("max_completion_tokens" -> 50))
  }

  test("the other providers of the OpenAI client are sent the limit under the name the caller used") {
    setup
    Seq(compatible, deepseek, scaleway).foreach { p =>
      assertEquals(limitsSent(p, Json.obj("max_tokens" -> 50)), Json.obj("max_tokens" -> 50), s"max_tokens sent to ${p.provider}")
    }
    assertEquals(limitsSent(deepseek, Json.obj("max_completion_tokens" -> 50)), Json.obj("max_completion_tokens" -> 50))
  }

  test("the limit set on a provider reaches it under the name it was given") {
    setup
    assertEquals(limitsSent(capped, Json.obj()), Json.obj("max_tokens" -> 64))
  }

  test("a request in the Anthropic format reaches each provider with the limit under the name it knows") {
    setup
    assertEquals(limitsSentForMessages(openai), Json.obj("max_completion_tokens" -> 50), "OpenAI")
    Seq(compatible, deepseek, mistral).foreach { p =>
      assertEquals(limitsSentForMessages(p), Json.obj("max_tokens" -> 50), s"max_tokens sent to ${p.provider}")
    }
  }

  // the url of an Azure OpenAI resource is made of its name: no call can be sent to a server of the suite
  test("Azure OpenAI is sent max_completion_tokens on its v1 api, the name the caller used on a dated api version") {
    assertEquals(AzureOpenAiApi.withTokenLimit("v1", Json.obj("max_tokens" -> 50)), Json.obj("max_completion_tokens" -> 50))
    assertEquals(AzureOpenAiApi.withTokenLimit("v1", Json.obj("max_completion_tokens" -> 50)), Json.obj("max_completion_tokens" -> 50))
    assertEquals(AzureOpenAiApi.withTokenLimit("v1", Json.obj("max_tokens" -> 64, "max_completion_tokens" -> 50)), Json.obj("max_completion_tokens" -> 50), "what the caller asked for wins over the limit of the provider")
    assertEquals(AzureOpenAiApi.withTokenLimit("v1", Json.obj("temperature" -> 1)), Json.obj("temperature" -> 1))
    assertEquals(AzureOpenAiApi.withTokenLimit("2024-06-01", Json.obj("max_tokens" -> 50)), Json.obj("max_tokens" -> 50))
    assertEquals(AzureOpenAiApi.withTokenLimit("2025-04-01-preview", Json.obj("max_completion_tokens" -> 50)), Json.obj("max_completion_tokens" -> 50))
  }
}
