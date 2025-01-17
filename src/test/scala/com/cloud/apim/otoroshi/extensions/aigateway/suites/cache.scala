package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.LlmExtensionOneOtoroshiServerPerSuite
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiProvider, CacheSettings}
import otoroshi.models.EntityLocation
import otoroshi.next.models._
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.OpenAiCompatProxy
import play.api.libs.json.Json
import reactor.core.publisher.Mono

import java.util.UUID
import scala.concurrent.duration.DurationInt

class CacheSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  val (ollama1Port, _) = createTestServerWithRoutes("ollama1", routes => routes.post("/api/chat", (req, response) => {
    req.receiveContent().ignoreElements().subscribe()
    response
      .status(200)
      .addHeader("Content-Type", "application/json")
      .sendString(Mono.just(
        s"""{
           |  "model": "foo",
           |  "created_at": "2023-12-12T14:13:43.416799Z",
           |  "message": {
           |    "role": "assistant",
           |    "content": "I am the ollama1 with model foo ${System.currentTimeMillis}"
           |  },
           |  "done": true,
           |  "total_duration": 5191566416,
           |  "load_duration": 2154458,
           |  "prompt_eval_count": 26,
           |  "prompt_eval_duration": 383809000,
           |  "eval_count": 298,
           |  "eval_duration": 4799921000
           |}""".stripMargin))
  }))

  def ollamaProvider(name: String, model: String, port: Int, cache: CacheSettings): AiProvider = {
    AiProvider(
      id = UUID.randomUUID().toString,
      name = name,
      provider = "ollama",
      connection = Json.obj(
        "base_url" -> s"http://localhost:${port}",
        "timeout" -> 30000
      ),
      options = Json.obj(
        "model" -> model,
      ),
      cache = cache
    )
  }

  test("llm provider can be cached with the simple strategy") {
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  setup                                                         ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    val llmProvider1 = ollamaProvider("ollama 1", "llama3.2", ollama1Port, CacheSettings(strategy = "simple"))

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
      frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath("test.oto.tools/chat"))),
      backend = NgBackend.empty.copy(targets = Seq(NgTarget.default)),
      plugins = NgPlugins(Seq(NgPluginInstance(
        plugin = s"cp:${classOf[OpenAiCompatProxy].getName}",
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

    val res = client.call("POST", s"http://test.oto.tools:${port}/chat", Map.empty, Some(Json.obj(
      "messages" -> Json.arr(Json.obj(
        "role" -> "user",
        "content" -> "How an llm works ?"
      ))
    ))).awaitf(30.seconds)
    assertEquals(res.status, 200, "status should be 200")
    val res1body = res.json.at("choices.0.message.content").asString
    assert(res1body.contains("I am the ollama1"), "body contains I am the ollama1")

    val res2 = client.call("POST", s"http://test.oto.tools:${port}/chat", Map.empty, Some(Json.obj(
      "messages" -> Json.arr(Json.obj(
        "role" -> "user",
        "content" -> "How an llm works ?"
      ))
    ))).awaitf(30.seconds)
    assertEquals(res2.status, 200, "status should be 200")
    val res2body = res2.json.at("choices.0.message.content").asString
    assertEquals(res1body, res2body, "body must be equals because of cache")

    val res3 = client.call("POST", s"http://test.oto.tools:${port}/chat", Map.empty, Some(Json.obj(
      "messages" -> Json.arr(Json.obj(
        "role" -> "user",
        "content" -> "How an LLM works man ?"
      ))
    ))).awaitf(30.seconds)
    assertEquals(res3.status, 200, "status should be 200")
    val res3body = res3.json.at("choices.0.message.content").asString
    assertNotEquals(res1body, res3body, "body must not be equals because of cache")
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  teardown                                                      ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").deleteEntity(llmProvider1).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").deleteEntity(route).awaitf(10.seconds)
    await(2.seconds)
  }

  test("llm provider can be cached with the semantic strategy") {
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  setup                                                         ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    val llmProvider1 = ollamaProvider("ollama 1", "llama3.2", ollama1Port, CacheSettings(strategy = "semantic"))

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
      frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath("test.oto.tools/chat"))),
      backend = NgBackend.empty.copy(targets = Seq(NgTarget.default)),
      plugins = NgPlugins(Seq(NgPluginInstance(
        plugin = s"cp:${classOf[OpenAiCompatProxy].getName}",
        config = NgPluginInstanceConfig(Json.obj(
          "refs" -> Json.arr(llmProvider1.id)
        ))
      )))
    )
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").upsertEntity(llmProvider1).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(route).awaitf(10.seconds)
    await(3.seconds)
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  test                                                          ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    val res = client.call("POST", s"http://test.oto.tools:${port}/chat", Map.empty, Some(Json.obj(
      "messages" -> Json.arr(Json.obj(
        "role" -> "user",
        "content" -> "How an llm works ?"
      ))
    ))).awaitf(30.seconds)
    println(s"---------------> ${res.status} - ${res.body}")
    assertEquals(res.status, 200, "status should be 200")
    val res1body = res.json.at("choices.0.message.content").asString
    assert(res1body.contains("I am the ollama1"), "body contains I am the ollama1")

    val res2 = client.call("POST", s"http://test.oto.tools:${port}/chat", Map.empty, Some(Json.obj(
      "messages" -> Json.arr(Json.obj(
        "role" -> "user",
        "content" -> "How an llm works ?"
      ))
    ))).awaitf(30.seconds)
    assertEquals(res2.status, 200, "status should be 200")
    val res2body = res2.json.at("choices.0.message.content").asString
    assertEquals(res1body, res2body, "body must be equals because of cache")

    val res3 = client.call("POST", s"http://test.oto.tools:${port}/chat", Map.empty, Some(Json.obj(
      "messages" -> Json.arr(Json.obj(
        "role" -> "user",
        "content" -> "How an LLM works man ?"
      ))
    ))).awaitf(30.seconds)
    assertEquals(res3.status, 200, "status should be 200")
    val res3body = res3.json.at("choices.0.message.content").asString
    assertEquals(res1body, res3body, "body must be equals because of cache")
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  teardown                                                      ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").deleteEntity(llmProvider1).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").deleteEntity(route).awaitf(10.seconds)
    await(2.seconds)
  }
}