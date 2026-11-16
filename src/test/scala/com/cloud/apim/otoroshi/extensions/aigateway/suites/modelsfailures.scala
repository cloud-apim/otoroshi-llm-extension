package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.LlmExtensionOneOtoroshiServerPerSuite
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import otoroshi.models.EntityLocation
import otoroshi.next.models.*
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.{LlmProviderModels, OpenAiCompatModels}
import play.api.libs.json.{JsArray, JsObject, JsString, Json}
import reactor.core.publisher.Mono

import java.util.UUID
import scala.concurrent.duration.DurationInt

// A provider that cannot list its models must not look like a provider that serves none.
class ProviderModelsFailuresSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  val (refusingPort, _) = createTestServerWithRoutes("openai-models-refusing", routes => routes
    .get("/v1/models", (_, response) => {
      response
        .status(401)
        .addHeader("Content-Type", "application/json")
        .sendString(Mono.just("""{"error":{"message":"Incorrect API key provided"}}"""))
    })
  )

  val (servingPort, _) = createTestServerWithRoutes("openai-models-serving", routes => routes
    .get("/v1/models", (_, response) => {
      response
        .status(200)
        .addHeader("Content-Type", "application/json")
        .sendString(Mono.just("""{"object":"list","data":[{"id":"model-a","object":"model"}]}"""))
    })
  )

  def openaiProvider(name: String, port: Int): AiProvider = AiProvider(
    id = UUID.randomUUID().toString,
    name = name,
    provider = "openai",
    connection = Json.obj(
      "base_url" -> s"http://localhost:$port/v1",
      "token" -> "sk-test",
      "timeout" -> 10000
    ),
    options = Json.obj("model" -> "model-a")
  )

  def modelsRoute(domain: String, refs: Seq[String], plugin: String = classOf[OpenAiCompatModels].getName): NgRoute = NgRoute(
    location = EntityLocation.default,
    id = UUID.randomUUID().toString,
    name = s"models route $domain",
    description = "models route",
    tags = Seq.empty,
    metadata = Map.empty,
    enabled = true,
    debugFlow = false,
    capture = false,
    exportReporting = false,
    frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath(s"$domain/models"))),
    backend = NgBackend.empty.copy(targets = Seq(NgTarget.default)),
    plugins = NgPlugins(Seq(NgPluginInstance(
      plugin = s"cp:${plugin}",
      config = NgPluginInstanceConfig(Json.obj("refs" -> JsArray(refs.map(JsString.apply))))
    )))
  )

  test("a models listing every provider failed answers 502, one where a provider answered lists its models") {
    val refusing = openaiProvider("refusing", refusingPort)
    val serving = openaiProvider("serving", servingPort)
    val failingRoute = modelsRoute("models-failing.oto.tools", Seq(refusing.id))
    val partialRoute = modelsRoute("models-partial.oto.tools", Seq(refusing.id, serving.id))
    val llmFailingRoute = modelsRoute("llm-models-failing.oto.tools", Seq(refusing.id), classOf[LlmProviderModels].getName)
    val providers = client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers")
    val routes = client.forEntity("proxy.otoroshi.io", "v1", "routes")
    providers.upsertEntity(refusing).awaitf(10.seconds)
    providers.upsertEntity(serving).awaitf(10.seconds)
    routes.upsertEntity(failingRoute).awaitf(10.seconds)
    routes.upsertEntity(partialRoute).awaitf(10.seconds)
    routes.upsertEntity(llmFailingRoute).awaitf(10.seconds)
    await(2.seconds)

    {
      val r = client.call("GET", s"http://models-failing.oto.tools:${port}/models", Map.empty, None).awaitf(30.seconds)
      assertEquals(r.status, 502)
      assertEquals((r.body: String).parseJson.select("error").select("code").asString, "provider_models_unavailable")
    }

    {
      val r = client.call("GET", s"http://models-partial.oto.tools:${port}/models", Map.empty, None).awaitf(30.seconds)
      assertEquals(r.status, 200)
      val ids = (r.body: String).parseJson.select("data").as[Seq[JsObject]].map(_.select("id").asString)
      assertEquals(ids, Seq("serving/model-a"))
    }

    {
      val r = client.call("GET", s"http://llm-models-failing.oto.tools:${port}/models", Map.empty, None).awaitf(30.seconds)
      assertEquals(r.status, 502)
    }

    routes.deleteEntity(failingRoute).awaitf(10.seconds)
    routes.deleteEntity(partialRoute).awaitf(10.seconds)
    routes.deleteEntity(llmFailingRoute).awaitf(10.seconds)
    providers.deleteEntity(refusing).awaitf(10.seconds)
    providers.deleteEntity(serving).awaitf(10.seconds)
    await(2.seconds)
  }
}
