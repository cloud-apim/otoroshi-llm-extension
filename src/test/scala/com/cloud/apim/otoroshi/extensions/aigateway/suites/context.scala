package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiProvider, ContextSettings, PromptContext}
import com.cloud.apim.otoroshi.extensions.aigateway.{LlmExtensionOneOtoroshiServerPerSuite, LlmProviders}
import otoroshi.models.EntityLocation
import otoroshi.next.models.*
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.OpenAiCompatProxy
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.libs.ws.WSResponse
import reactor.core.publisher.Mono

import java.util.UUID
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.DurationInt

class ProviderContextSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  val responses = new TrieMap[String, Seq[JsObject]]()

  val (ollama1Port, _) = createTestServerWithRoutes("ollama1", routes => routes.post("/api/chat", (req, response) => {
    req.receive().retain().asString().flatMap { body =>
      val json = body.parseJson
      println(("____received", json.select("context").asOpt[JsValue]))
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
      name = "test ctx 1",
      description = "test ctx",
      tags = Seq.empty,
      metadata = Map.empty,
      preMessages = Seq(Json.obj("role" -> "system", "content" -> "You are an english butler, and you will respond to any question as such")),
      postMessages = Seq.empty,
    )

    val context2 = PromptContext(
      location = EntityLocation.default,
      id = UUID.randomUUID().toString,
      name = "test ctx 2",
      description = "test ctx",
      tags = Seq.empty,
      metadata = Map.empty,
      preMessages = Seq(Json.obj("role" -> "system", "content" -> "You are an engineer, and you will respond to any question as such")),
      postMessages = Seq.empty,
    )

    val context3 = PromptContext(
      location = EntityLocation.default,
      id = UUID.randomUUID().toString,
      name = "test ctx 3",
      description = "test ctx",
      tags = Seq.empty,
      metadata = Map.empty,
      preMessages = Seq(Json.obj("role" -> "system", "content" -> "You are a mathematician, and you will respond to any question as such")),
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
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "prompt-contexts").upsertEntity(context3).awaitf(10.seconds)
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

    client.call("POST", s"http://test.oto.tools:${port}/chat", Map.empty, Some(Json.obj(
      "context" -> context2.name,
      "messages" -> Json.arr(Json.obj(
        "role" -> "user",
        "content" -> "How an llm works ?"
      ))
    ))).awaitf(30.seconds)
    assertEquals(responses("latest").size, 2, "there should be 2 messages")
    assert(responses("latest").head.stringify.contains("engineer"), "there should be a engineer")
    responses.clear()

    client.call("POST", s"http://test.oto.tools:${port}/chat", Map.empty, Some(Json.obj(
      "context" -> context3.id,
      "messages" -> Json.arr(Json.obj(
        "role" -> "user",
        "content" -> "How an llm works ?"
      ))
    ))).awaitf(30.seconds)
    assertEquals(responses("latest").size, 1, "there should be 1 messages")
    assert(!responses("latest").head.stringify.contains("mathematician"), "there should not be a mathematician")
    responses.clear()

    client.call("POST", s"http://test.oto.tools:${port}/chat", Map.empty, Some(Json.obj(
      "context" -> context3.name,
      "messages" -> Json.arr(Json.obj(
        "role" -> "user",
        "content" -> "How an llm works ?"
      ))
    ))).awaitf(30.seconds)
    assertEquals(responses("latest").size, 1, "there should be 1 messages")
    assert(!responses("latest").head.stringify.contains("mathematician"), "there should not be a mathematician")
    responses.clear()

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  teardown                                                      ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").deleteEntity(llmProvider1).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "prompt-contexts").deleteEntity(context1).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "prompt-contexts").deleteEntity(context2).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "prompt-contexts").deleteEntity(context3).awaitf(10.seconds)
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

  test("a prompt context created without description is the default context of a provider") {
    val contextId = UUID.randomUUID().toString
    val created = client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "prompt-contexts").createRaw(Json.obj(
      "id" -> contextId,
      "name" -> "butler",
      "pre_messages" -> Json.arr(Json.obj("role" -> "system", "content" -> "You are an english butler")),
    )).awaitf(10.seconds)
    assert(created.createdOrUpdated, s"the prompt context should be created: ${created.state}")

    val llmProvider1 = ollamaProvider("ollama 1", "llama3.2", ollama1Port, Some(contextId), Seq.empty)
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

    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").deleteEntity(llmProvider1).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "prompt-contexts").deleteRaw(contextId).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").deleteEntity(route).awaitf(10.seconds)
    await(2.seconds)
  }

  test("llm provider can have multiple possible contexts openai") {
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  setup                                                         ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    val context1 = PromptContext(
      location = EntityLocation.default,
      id = UUID.randomUUID().toString,
      name = "test ctx 1",
      description = "test ctx",
      tags = Seq.empty,
      metadata = Map.empty,
      preMessages = Seq(Json.obj("role" -> "system", "content" -> "You are an english butler, and you will respond to any question as such")),
      postMessages = Seq.empty,
    )

    val context2 = PromptContext(
      location = EntityLocation.default,
      id = UUID.randomUUID().toString,
      name = "test ctx 2",
      description = "test ctx",
      tags = Seq.empty,
      metadata = Map.empty,
      preMessages = Seq(Json.obj("role" -> "system", "content" -> "You are an engineer, and you will respond to any question as such")),
      postMessages = Seq.empty,
    )

    val context3 = PromptContext(
      location = EntityLocation.default,
      id = UUID.randomUUID().toString,
      name = "test ctx 3",
      description = "test ctx",
      tags = Seq.empty,
      metadata = Map.empty,
      preMessages = Seq(Json.obj("role" -> "system", "content" -> "You are a mathematician, and you will respond to any question as such")),
      postMessages = Seq.empty,
    )

    val provider = LlmProviders.openai
    val token = sys.env(provider.envToken)
    val llmProvider1 = AiProvider(
      id = UUID.randomUUID().toString,
      name = s"provider",
      provider = provider.name,
      connection = provider.baseUrl match {
        case None => Json.obj(
          "token" -> token,
          "timeout" -> 30000
        )
        case Some(url) => Json.obj(
          "base_url" -> url,
          "token" -> token,
          "timeout" -> 30000
        )
      },
      options = provider.options,
      context = ContextSettings(
        contexts = Seq(context1.id, context2.id, context3.id)
      )
    )

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
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "prompt-contexts").upsertEntity(context3).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(route).awaitf(10.seconds)
    await(2.seconds)
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  test                                                          ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    def checkResp(resp: WSResponse): Unit = {
      if (resp.status != 200) {
        println(resp.json)
      }
      assertEquals(resp.status, 200, "resp should be 200")
    }

    checkResp(client.call("POST", s"http://test.oto.tools:${port}/chat", Map.empty, Some(Json.obj(
      "messages" -> Json.arr(Json.obj(
        "role" -> "user",
        "content" -> "How an llm works ?"
      ))
    ))).awaitf(30.seconds))

    checkResp(client.call("POST", s"http://test.oto.tools:${port}/chat", Map.empty, Some(Json.obj(
      "context" -> context1.id,
      "messages" -> Json.arr(Json.obj(
        "role" -> "user",
        "content" -> "How an llm works ?"
      ))
    ))).awaitf(30.seconds))

      checkResp(client.call("POST", s"http://test.oto.tools:${port}/chat", Map.empty, Some(Json.obj(
      "context" -> context2.id,
      "messages" -> Json.arr(Json.obj(
        "role" -> "user",
        "content" -> "How an llm works ?"
      ))
    ))).awaitf(30.seconds))

      checkResp(client.call("POST", s"http://test.oto.tools:${port}/chat", Map.empty, Some(Json.obj(
      "context" -> context2.name,
      "messages" -> Json.arr(Json.obj(
        "role" -> "user",
        "content" -> "How an llm works ?"
      ))
    ))).awaitf(30.seconds))

      checkResp(client.call("POST", s"http://test.oto.tools:${port}/chat", Map.empty, Some(Json.obj(
      "context" -> context3.id,
      "messages" -> Json.arr(Json.obj(
        "role" -> "user",
        "content" -> "How an llm works ?"
      ))
    ))).awaitf(30.seconds))

      checkResp(client.call("POST", s"http://test.oto.tools:${port}/chat", Map.empty, Some(Json.obj(
      "context" -> context3.name,
      "messages" -> Json.arr(Json.obj(
        "role" -> "user",
        "content" -> "How an llm works ?"
      ))
    ))).awaitf(30.seconds))

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  teardown                                                      ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").deleteEntity(llmProvider1).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "prompt-contexts").deleteEntity(context1).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "prompt-contexts").deleteEntity(context2).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "prompt-contexts").deleteEntity(context3).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").deleteEntity(route).awaitf(10.seconds)
    await(2.seconds)
  }

  // an api that refuses a field it does not know, the way OpenAI does
  val strictReceived = new TrieMap[String, JsValue]()
  val (strictPort, _) = createTestServerWithRoutes("strict-openai", routes => routes.post("/v1/chat/completions", (req, response) =>
    req.receive().aggregate().asString().flatMap { body =>
      val json = body.parseJson
      strictReceived.put("latest", json)
      if (json.select("context").isDefined) {
        response.status(400).addHeader("Content-Type", "application/json").sendString(Mono.just(Json.obj("error" -> Json.obj(
          "message" -> "Unknown parameter: 'context'.", "type" -> "invalid_request_error", "param" -> "context"
        )).stringify)).`then`()
      } else {
        response.status(200).addHeader("Content-Type", "application/json").sendString(Mono.just(Json.obj(
          "id" -> "chatcmpl-1", "object" -> "chat.completion", "created" -> 1700000000, "model" -> "a-model",
          "choices" -> Json.arr(Json.obj("index" -> 0, "finish_reason" -> "stop", "message" -> Json.obj("role" -> "assistant", "content" -> "hello"))),
          "usage" -> Json.obj("prompt_tokens" -> 1, "completion_tokens" -> 1, "total_tokens" -> 2),
        ).stringify)).`then`()
      }
    }
  ))

  test("the context a caller names never reaches the provider, whether it applies or not") {
    given env: _root_.otoroshi.env.Env = otoroshi.env
    val ext = env.adminExtensions.extension[otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension].get
    val pirate = PromptContext(EntityLocation.default, UUID.randomUUID().toString, "pirate", "pirate", Seq.empty, Map.empty,
      preMessages = Seq(Json.obj("role" -> "system", "content" -> "You are a pirate")), postMessages = Seq.empty)
    val butler = PromptContext(EntityLocation.default, UUID.randomUUID().toString, "butler", "butler", Seq.empty, Map.empty,
      preMessages = Seq(Json.obj("role" -> "system", "content" -> "You are a butler")), postMessages = Seq.empty)
    def strict(name: String, contexts: Seq[String]) = AiProvider(
      id = UUID.randomUUID().toString, name = name, provider = "openai",
      connection = Json.obj("base_url" -> s"http://localhost:${strictPort}/v1", "token" -> "sk-test", "timeout" -> 10000),
      options = Json.obj("model" -> "a-model"), context = ContextSettings(None, contexts),
    )
    // no context at all, another context than the one asked for, and the one asked for
    val bare = strict("strict bare", Seq.empty)
    val other = strict("strict other", Seq(butler.id))
    val offering = strict("strict offering", Seq(pirate.id))
    Seq(pirate, butler).foreach(c => client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "prompt-contexts").upsertEntity(c).awaitf(10.seconds))
    Seq(bare, other, offering).foreach(p => client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").upsertEntity(p).awaitf(10.seconds))
    await(2.seconds)

    def call(provider: AiProvider): Seq[JsObject] = {
      strictReceived.clear()
      val resp = ext.states.provider(provider.id).flatMap(_.getChatClient()).get
        .call(com.cloud.apim.otoroshi.extensions.aigateway.ChatPrompt(Seq(com.cloud.apim.otoroshi.extensions.aigateway.ChatMessage.userStrInput("hello"))),
          _root_.otoroshi.utils.TypedMap.empty, Json.obj("context" -> "pirate"))(using otoroshi.env.otoroshiExecutionContext, env)
        .awaitf(30.seconds)
      assert(resp.isRight, s"the call on ${provider.name} should succeed, got ${resp}")
      assertEquals(strictReceived("latest").select("context").asOpt[JsValue], None, s"${provider.name} received the context")
      strictReceived("latest").select("messages").as[Seq[JsObject]]
    }
    assertEquals(call(bare).size, 1, "a provider without contexts applies none")
    assertEquals(call(other).size, 1, "a context the provider does not offer is ignored")
    val applied = call(offering)
    assertEquals(applied.size, 2, "the context the provider offers is applied")
    assert(applied.head.stringify.contains("pirate"), applied.head.stringify)

    Seq(bare, other, offering).foreach(p => client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").deleteEntity(p).awaitf(10.seconds))
    Seq(pirate, butler).foreach(c => client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "prompt-contexts").deleteEntity(c).awaitf(10.seconds))
  }
}
