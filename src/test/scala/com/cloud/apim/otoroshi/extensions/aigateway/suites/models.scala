package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.LlmExtensionOneOtoroshiServerPerSuite
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiProvider, ModelSettings}
import otoroshi.models.EntityLocation
import otoroshi.next.models._
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.OpenAiCompatModels
import play.api.libs.json.{JsObject, Json}

import java.util.UUID
import scala.concurrent.duration.DurationInt

class ProviderModelsSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  def ollamaProvider(name: String, model: String, include: Seq[String], exclude: Seq[String]): AiProvider = {
    AiProvider(
      id = UUID.randomUUID().toString,
      name = name,
      provider = "ollama",
      connection = Json.obj(
        "timeout" -> 30000
      ),
      options = Json.obj(
        "model" -> model,
      ),
      models = ModelSettings(include, exclude)
    )
  }

  test("llm provider can have enforce included models") {
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  setup                                                         ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    val llmProvider1 = ollamaProvider("ollama", "phi4", Seq.empty, Seq.empty)

    val route = NgRoute(
      location = EntityLocation.default,
      id = UUID.randomUUID().toString,
      name = "test route",
      description = "test route",
      tags = Seq.empty,
      metadata = Map.empty,
      enabled = true,
      debugFlow = false,
      capture = false,
      exportReporting = false,
      frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath("test.oto.tools/models"))),
      backend = NgBackend.empty.copy(targets = Seq(NgTarget.default)),
      plugins = NgPlugins(Seq(NgPluginInstance(
        plugin = s"cp:${classOf[OpenAiCompatModels].getName}",
        config = NgPluginInstanceConfig(Json.obj(
          "refs" -> Json.arr(llmProvider1.id)
        ))
      )))
    )

    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").upsertEntity(llmProvider1).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(route).awaitf(10.seconds)
    await(2.seconds)
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  test                                                          ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    {
      val r = client.call("GET", s"http://test.oto.tools:${port}/models", Map.empty, None).awaitf(30.seconds)
      val models = r.body.parseJson.select("data").as[Seq[JsObject]]
      println(s"models: ${models.size}")
      assertEquals(models.size, 13)
    }

    {
      client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers")
        .upsertEntity(llmProvider1.copy(models = llmProvider1.models.copy(include = Seq("qwen2.5-coder:0.5b"))))
        .awaitf(10.seconds)
      await(2.seconds)
      val r = client.call("GET", s"http://test.oto.tools:${port}/models", Map.empty, None).awaitf(30.seconds)
      val models = r.body.parseJson.select("data").as[Seq[JsObject]]
      println(s"models: ${models.size}")
      assertEquals(models.size, 1)
    }

    {
      client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers")
        .upsertEntity(llmProvider1.copy(models = llmProvider1.models.copy(include = Seq("qwen.*"))))
        .awaitf(10.seconds)
      await(2.seconds)
      val r = client.call("GET", s"http://test.oto.tools:${port}/models", Map.empty, None).awaitf(30.seconds)
      val models = r.body.parseJson.select("data").as[Seq[JsObject]]
      println(s"models: ${models.size}")
      assertEquals(models.size, 2)
    }

    {
      client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers")
        .upsertEntity(llmProvider1.copy(models = llmProvider1.models.copy(exclude = Seq("lla.*"))))
        .awaitf(10.seconds)
      await(2.seconds)
      val r = client.call("GET", s"http://test.oto.tools:${port}/models", Map.empty, None).awaitf(30.seconds)
      val models = r.body.parseJson.select("data").as[Seq[JsObject]]
      println(s"models: ${models.size}")
      assertEquals(models.size, 7)
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  teardown                                                      ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").deleteEntity(llmProvider1).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").deleteEntity(route).awaitf(10.seconds)
    await(2.seconds)
  }

}