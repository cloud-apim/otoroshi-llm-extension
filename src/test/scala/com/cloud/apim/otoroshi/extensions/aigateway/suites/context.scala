package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.LlmExtensionOneOtoroshiServerPerSuite
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiProvider, CacheSettings, ContextSettings, PromptContext}
import otoroshi.models.EntityLocation
import otoroshi.next.models._
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.OpenAiCompatProxy
import play.api.libs.json.{JsObject, Json}
import reactor.core.publisher.Mono

import java.util.UUID
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.DurationInt

class ProviderContextSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  val responses = new TrieMap[String, Seq[JsObject]]()

  val (ollama1Port, _) = createTestServerWithRoutes("ollama1", routes => routes.post("/api/chat", (req, response) => {
    req.receive().retain().asString().flatMap { body =>
      val json = body.parseJson
      val messages = json.select("messages").as[Seq[JsObject]]
      responses.put("latest", messages)
      // println("received: -----------------------------------" + json.prettify)
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
    }
  }))

  def ollamaProvider(name: String, model: String, port: Int, current: Option[String], contexts: Seq[String]): AiProvider = {
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
      context = ContextSettings(current, contexts)
    )
  }

  test("llm provider can have multiple possible contexts") {
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  setup                                                         ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    val context1 = PromptContext(
      location = EntityLocation.default,
      id = UUID.randomUUID().toString,
      name = "test ctx",
      description = "test ctx",
      tags = Seq.empty,
      metadata = Map.empty,
      preMessages = Seq(Json.obj("role" -> "system", "content" -> "You are an english butler, and you will respond to any question as such")),
      postMessages = Seq.empty,
    )

    val context2 = PromptContext(
      location = EntityLocation.default,
      id = UUID.randomUUID().toString,
      name = "test ctx",
      description = "test ctx",
      tags = Seq.empty,
      metadata = Map.empty,
      preMessages = Seq(Json.obj("role" -> "system", "content" -> "You are an engineer, and you will respond to any question as such")),
      postMessages = Seq.empty,
    )

    val llmProvider1 = ollamaProvider("ollama 1", "llama3.2", ollama1Port, None, Seq(context1.id, context2.id))

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
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "prompt-contexts").upsertEntity(context1).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "prompt-contexts").upsertEntity(context2).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(route).awaitf(10.seconds)
    await(2.seconds)
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  test                                                          ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    responses.clear()

    client.call("POST", s"http://test.oto.tools:${port}/chat", Map.empty, Some(Json.obj(
      "messages" -> Json.arr(Json.obj(
        "role" -> "user",
        "content" -> "How an llm works ?"
      ))
    ))).awaitf(30.seconds)
    assertEquals(responses("latest").size, 1, "there should be only one message")
    responses.clear()

    client.call("POST", s"http://test.oto.tools:${port}/chat", Map.empty, Some(Json.obj(
      "context" -> context1.id,
      "messages" -> Json.arr(Json.obj(
        "role" -> "user",
        "content" -> "How an llm works ?"
      ))
    ))).awaitf(30.seconds)
    assertEquals(responses("latest").size, 2, "there should be 2 messages")
    assert(responses("latest").head.stringify.contains("butler"), "there should be a butler")
    responses.clear()

    client.call("POST", s"http://test.oto.tools:${port}/chat", Map.empty, Some(Json.obj(
      "context" -> context2.id,
      "messages" -> Json.arr(Json.obj(
        "role" -> "user",
        "content" -> "How an llm works ?"
      ))
    ))).awaitf(30.seconds)
    assertEquals(responses("latest").size, 2, "there should be 2 messages")
    assert(responses("latest").head.stringify.contains("engineer"), "there should be a engineer")
    responses.clear()
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  teardown                                                      ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").deleteEntity(llmProvider1).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "prompt-contexts").deleteEntity(context1).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "prompt-contexts").deleteEntity(context2).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").deleteEntity(route).awaitf(10.seconds)
    await(2.seconds)
  }

  test("llm provider can have a default context that can be override") {
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  setup                                                         ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    val context1 = PromptContext(
      location = EntityLocation.default,
      id = UUID.randomUUID().toString,
      name = "test ctx",
      description = "test ctx",
      tags = Seq.empty,
      metadata = Map.empty,
      preMessages = Seq(Json.obj("role" -> "system", "content" -> "You are an english butler, and you will respond to any question as such")),
      postMessages = Seq.empty,
    )

    val context2 = PromptContext(
      location = EntityLocation.default,
      id = UUID.randomUUID().toString,
      name = "test ctx",
      description = "test ctx",
      tags = Seq.empty,
      metadata = Map.empty,
      preMessages = Seq(Json.obj("role" -> "system", "content" -> "You are an engineer, and you will respond to any question as such")),
      postMessages = Seq.empty,
    )

    val llmProvider1 = ollamaProvider("ollama 1", "llama3.2", ollama1Port, Some(context1.id), Seq(context2.id))

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
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "prompt-contexts").upsertEntity(context1).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "prompt-contexts").upsertEntity(context2).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(route).awaitf(10.seconds)
    await(2.seconds)
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  test                                                          ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    responses.clear()

    client.call("POST", s"http://test.oto.tools:${port}/chat", Map.empty, Some(Json.obj(
      "messages" -> Json.arr(Json.obj(
        "role" -> "user",
        "content" -> "How an llm works ?"
      ))
    ))).awaitf(30.seconds)
    assertEquals(responses("latest").size, 2, "there should be 2 messages")
    assert(responses("latest").head.stringify.contains("butler"), "there should be a butler")
    responses.clear()

    client.call("POST", s"http://test.oto.tools:${port}/chat", Map.empty, Some(Json.obj(
      "context" -> context2.id,
      "messages" -> Json.arr(Json.obj(
        "role" -> "user",
        "content" -> "How an llm works ?"
      ))
    ))).awaitf(30.seconds)
    assertEquals(responses("latest").size, 2, "there should be 2 messages")
    assert(responses("latest").head.stringify.contains("engineer"), "there should be a engineer")
    responses.clear()
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  teardown                                                      ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").deleteEntity(llmProvider1).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "prompt-contexts").deleteEntity(context1).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "prompt-contexts").deleteEntity(context2).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").deleteEntity(route).awaitf(10.seconds)
    await(2.seconds)
  }

}