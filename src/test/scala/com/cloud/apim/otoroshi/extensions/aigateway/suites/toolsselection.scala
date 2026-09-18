package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.LlmExtensionOneOtoroshiServerPerSuite
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiProvider, LlmToolFunction, LlmToolFunctionBackend, LlmToolFunctionBackendKind, LlmToolFunctionBackendOptions}
import otoroshi.models.EntityLocation
import otoroshi.next.models.*
// NgPluginInstanceConfig comes with the route model
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.OpenAiCompatProxy
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.libs.ws.WSResponse
import reactor.core.publisher.Mono

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.DurationInt

// The tools a model can use are attached to its provider, for all of its traffic. A call names in
// `allowed_tools` the ones it wants this time: it can only narrow what the provider carries, never reach
// a tool the operator did not attach, and the field never leaves the gateway.
class ToolsSelectionSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  val lastBody = new AtomicReference[JsObject](Json.obj())

  val (openaiPort, _) = createTestServerWithRoutes("openai-tools-selection", routes => routes
    .post("/v1/chat/completions", (req, response) => {
      req.receive().retain().asString().flatMap { body =>
        lastBody.set(body.parseJson.asObject)
        response
          .status(200)
          .addHeader("Content-Type", "application/json")
          .sendString(Mono.just(
            """{
              |  "id": "chatcmpl-1",
              |  "object": "chat.completion",
              |  "created": 1,
              |  "model": "gpt-4o-mini",
              |  "choices": [{ "index": 0, "message": { "role": "assistant", "content": "hello" }, "finish_reason": "stop" }],
              |  "usage": { "prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2 }
              |}""".stripMargin))
      }
    })
  )

  def function(id: String, name: String): LlmToolFunction = LlmToolFunction(
    id = id,
    name = name,
    description = s"the $name tool",
    strict = false,
    parameters = Json.obj("city" -> Json.obj("type" -> "string", "description" -> "the city")),
    backend = LlmToolFunctionBackend(
      kind = LlmToolFunctionBackendKind.QuickJs,
      options = LlmToolFunctionBackendOptions.QuickJs(
        """'inline module';
          |
          |exports.tool_call = function(args) {
          |  return JSON.stringify({ ok: true });
          |}""".stripMargin
      )
    )
  )

  val weather = function("tool-function_selection_weather", "get_weather")
  val flights = function("tool-function_selection_flights", "get_flight_times")
  // a tool of the tenant that nobody attached to the provider: a call must not be able to reach it
  val detached = function("tool-function_selection_detached", "get_secrets")

  val provider = AiProvider(
    id = "provider_tools_selection",
    name = "tools selection",
    provider = "openai",
    connection = Json.obj("base_url" -> s"http://localhost:${openaiPort}/v1", "token" -> "secret", "timeout" -> 30000),
    options = Json.obj("model" -> "gpt-4o-mini", "wasm_tools" -> Json.arr(weather.id, flights.id)),
  )

  val route: NgRoute = NgRoute(
    location = EntityLocation.default,
    id = "route_tools_selection",
    name = "tools selection",
    description = "tools selection",
    tags = Seq.empty,
    metadata = Map.empty,
    enabled = true,
    debugFlow = false,
    capture = false,
    exportReporting = false,
    frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath("tools-selection.oto.tools/chat"))),
    backend = NgBackend.empty.copy(targets = Seq(NgTarget.default)),
    plugins = NgPlugins(Seq(NgPluginInstance(
      plugin = s"cp:${classOf[OpenAiCompatProxy].getName}",
      config = NgPluginInstanceConfig(Json.obj("refs" -> Json.arr(provider.id))),
    ))),
  )

  lazy val setup: Unit = {
    Seq(weather, flights, detached).foreach(f => client.forLlmEntity("tool-functions").upsertEntity(f).awaitf(10.seconds))
    client.forLlmEntity("providers").upsertEntity(provider).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(route).awaitf(10.seconds)
    await(10.seconds)
  }

  // the names of the tools the provider api was offered for a call, `allowed_tools` being what it asked for
  def offeredTools(allowed: Option[Seq[String]]): Seq[String] = {
    setup
    lastBody.set(Json.obj())
    val body = Json.obj("model" -> "gpt-4o-mini", "messages" -> Json.arr(Json.obj("role" -> "user", "content" -> "hey")))
      .applyOnWithOpt(allowed) { case (obj, ids) => obj ++ Json.obj("allowed_tools" -> ids) }
    val resp: WSResponse = client.call("POST", s"http://tools-selection.oto.tools:${port}/chat", Map.empty, Some(body)).awaitf(30.seconds)
    assertEquals(resp.status, 200, s"the call should have been served: ${resp.body}")
    lastBody.get().select("tools").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map(_.at("function.name").asString)
  }

  test("without a selection, a call is offered every tool of its provider") {
    val tools = offeredTools(None)
    assertEquals(tools.size, 2, s"both tools of the provider should be offered: $tools")
  }

  test("a call is offered the tools it asked for, and only those") {
    val tools = offeredTools(Some(Seq(flights.id)))
    assertEquals(tools.size, 1, s"only the selected tool should be offered: $tools")
    assert(tools.head.contains(flights.toolId), s"the selected tool should be the one asked for: $tools")
    // the selection is for the gateway, the provider api never sees it
    assert(lastBody.get().select("allowed_tools").asOpt[JsValue].isEmpty, s"the gateway should have kept the field: ${lastBody.get().stringify}")
  }

  test("a call can ask for no tool at all") {
    assertEquals(offeredTools(Some(Seq.empty)), Seq.empty, "no tool should be offered")
  }

  test("a call cannot reach a tool its provider does not carry") {
    assertEquals(offeredTools(Some(Seq(detached.id))), Seq.empty, "a tool of the tenant that the provider does not carry should stay out of reach")
  }
}
