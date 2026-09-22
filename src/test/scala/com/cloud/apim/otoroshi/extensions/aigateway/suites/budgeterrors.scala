package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.analytics.LlmUsageProjection
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatMessage, ChatPrompt, LlmExtensionOneOtoroshiServerPerSuite}
import otoroshi.models.EntityLocation
import otoroshi.next.models.*
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.OpenAiCompatProxy
import play.api.libs.json.{JsObject, Json}
import reactor.core.publisher.Mono

import java.util.UUID
import scala.concurrent.duration.DurationInt

// Issue #196: a budget in `block` mode returns its own error message to the calls it blocks. The call stays
// flagged as blocked by a budget whatever the message, for the metrics and the analytics.
class BudgetErrorsSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  val customMessage = "Your AI budget is exhausted, please contact your administrator"

  val (openaiPort, _) = createTestServerWithRoutes("budget-errors-openai", routes => routes
    .post("/v1/chat/completions", (req, response) => {
      req.receive().aggregate().asString().flatMap { _ =>
        response.status(200).addHeader("Content-Type", "application/json").sendString(Mono.just(Json.obj(
          "id" -> "chatcmpl-1",
          "object" -> "chat.completion",
          "created" -> 1700000000,
          "model" -> "gpt-4o-mini",
          "choices" -> Json.arr(Json.obj("index" -> 0, "finish_reason" -> "stop", "message" -> Json.obj("role" -> "assistant", "content" -> "hello"))),
          "usage" -> Json.obj("prompt_tokens" -> 1, "completion_tokens" -> 1, "total_tokens" -> 2),
        ).stringify)).`then`()
      }
    })
  )

  def provider(name: String): AiProvider = AiProvider(
    id = s"provider_${UUID.randomUUID()}",
    name = name,
    provider = "openai",
    connection = Json.obj("base_url" -> s"http://localhost:${openaiPort}/v1", "token" -> "sk-test", "timeout" -> 30000),
    options = Json.obj("model" -> "gpt-4o-mini"),
  )

  val custom = provider("budget with a custom message")
  val default = provider("budget with an emptied message")

  // no token allowed: the budget is exceeded from the first call
  def budget(name: String, providerId: String, mode: String, errorMessage: String): JsObject = Json.obj(
    "id" -> s"budget_${UUID.randomUUID()}",
    "name" -> name,
    "description" -> name,
    "enabled" -> true,
    "duration" -> Json.obj("value" -> 1, "unit" -> "year"),
    "limits" -> Json.obj("total_tokens" -> 0),
    "scope" -> Json.obj("providers" -> Json.arr(providerId)),
    "action_on_exceed" -> Json.obj("mode" -> mode, "alert_on_exceed" -> false, "alert_on_almost_exceed" -> false, "error_message" -> errorMessage),
  )

  val route = NgRoute(
    location = EntityLocation.default,
    id = UUID.randomUUID().toString,
    name = "budget errors route",
    description = "budget errors route",
    tags = Seq.empty,
    metadata = Map.empty,
    enabled = true,
    debugFlow = false,
    capture = false,
    exportReporting = false,
    frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath("budgeterrors.oto.tools/v1/chat/completions"))),
    backend = NgBackend.empty.copy(targets = Seq(NgTarget.default)),
    plugins = NgPlugins(Seq(NgPluginInstance(
      plugin = s"cp:${classOf[OpenAiCompatProxy].getName}",
      config = NgPluginInstanceConfig(Json.obj("refs" -> Json.arr(custom.id)))
    )))
  )

  override def beforeAll(): Unit = {
    super.beforeAll()
    Seq(custom, default).foreach(p => client.forLlmEntity("providers").upsertEntity(p).awaitf(10.seconds))
    Seq(
      budget("blocking budget", custom.id, "block", customMessage),
      // exceeded at the same time but only alerting: its message must not be the one returned
      budget("soft budget", custom.id, "soft", "a message that must not be returned"),
      budget("blocking budget without message", default.id, "block", "   "),
    ).foreach(b => client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "ai-budgets").createRaw(b).awaitf(10.seconds))
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(route).awaitf(10.seconds)
    await(10.seconds)
  }

  def ext: AiExtension = otoroshi.env.adminExtensions.extension[AiExtension].get

  test("a blocking budget returns its own error message") {
    val resp = client.call("POST", s"http://budgeterrors.oto.tools:${port}/v1/chat/completions", Map.empty, Some(Json.obj(
      "messages" -> Json.arr(Json.obj("role" -> "user", "content" -> "hello")),
    ))).awaitf(30.seconds)
    val body = resp.bodyAsBytes.utf8String
    assertEquals(Json.parse(body), Json.obj("error" -> customMessage, "budget_exceeded" -> true), s"status ${resp.status}: ${body}")
  }

  test("a blocking budget with an emptied message returns the default one") {
    given env: _root_.otoroshi.env.Env = otoroshi.env
    val chatClient = ext.states.provider(default.id).get.getChatClient().get
    val result = chatClient.call(ChatPrompt(Seq(ChatMessage.userStrInput("hello"))), TypedMap.empty, Json.obj())(using otoroshi.executionContext, env).awaitf(30.seconds)
    assertEquals(result.map(_ => ()), Left(Json.obj("error" -> "budget exceeded", "budget_exceeded" -> true)))
  }

  test("a call blocked by a budget keeps its error kind whatever the message") {
    assertEquals(LlmUsageProjection.errorKind(Json.obj("error" -> customMessage, "budget_exceeded" -> true)), Some("budget_exceeded"))
  }
}
