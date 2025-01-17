package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.LlmExtensionOneOtoroshiServerPerSuite
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import otoroshi.models.EntityLocation
import otoroshi.next.models._
import otoroshi.utils.syntax.implicits.BetterFuture
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.OpenAiCompatProxy
import play.api.libs.json.Json
import play.api.libs.ws.WSResponse
import reactor.core.publisher.Mono

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.DurationInt

class LoadBalancerSuite extends LlmExtensionOneOtoroshiServerPerSuite {

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
           |    "content": "I am the ollama1 with model foo"
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

  val (ollama2Port, _) = createTestServerWithRoutes("ollama2", routes => routes.post("/api/chat", (req, response) => {
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
           |    "content": "I am the ollama2 with model foo"
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

  val (ollama3Port, _) = createTestServerWithRoutes("ollama3", routes => routes.post("/api/chat", (req, response) => {
    req.receiveContent().ignoreElements().subscribe()
    response
      .status(200)
      .addHeader("Content-Type", "application/json")
      .sendString(Mono.just(
        s"""{
           |  "model": "bar",
           |  "created_at": "2023-12-12T14:13:43.416799Z",
           |  "message": {
           |    "role": "assistant",
           |    "content": "I am the ollama3 with model bar"
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

  val (ollama4Port, _) = createTestServerWithRoutes("ollama4", routes => routes.post("/api/chat", (req, response) => {
    req.receiveContent().ignoreElements().subscribe()
    response
      .status(200)
      .addHeader("Content-Type", "application/json")
      .sendString(Mono.just(
        s"""{
           |  "model": "bar",
           |  "created_at": "2023-12-12T14:13:43.416799Z",
           |  "message": {
           |    "role": "assistant",
           |    "content": "I am the ollama4 with model bar"
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

  def ollamaProvider(name: String, model: String, port: Int): AiProvider = {
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
      )
    )
  }

  test("llm provider can be loadbalanced") {
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  setup                                                         ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    val llmProvider1 = ollamaProvider("ollama 1", "foo", ollama1Port)
    val llmProvider2 = ollamaProvider("ollama 2", "foo", ollama2Port)
    val llmProviderLb = AiProvider(
      id = UUID.randomUUID().toString,
      name = "lb",
      provider = "loadbalancer",
      connection = Json.obj(),
      options = Json.obj(
        "loadbalancing" -> "round_robin",
        "refs" -> Json.arr(
          Json.obj(
            "ref" -> llmProvider1.id
          ),
          Json.obj(
            "ref" -> llmProvider2.id
          )
        ),
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
          "refs" -> Json.arr(llmProviderLb.id)
        ))
      )))
    )
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").upsertEntity(llmProvider1).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").upsertEntity(llmProvider2).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").upsertEntity(llmProviderLb).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(route).awaitf(10.seconds)
    await(2.seconds)
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  test                                                          ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    val countercalls = new AtomicInteger(0)

    def call(model: String): WSResponse = {
      countercalls.incrementAndGet()
      client.call("POST", s"http://test.oto.tools:${port}/chat", Map.empty, Some(Json.obj(
        "model" -> model,
        "messages" -> Json.arr(Json.obj(
          "role" -> "user",
          "content" -> "whatever"
        ))
      ))).awaitf(30.seconds)
    }
    val counterFoo = new AtomicInteger(0)
    val counterBar = new AtomicInteger(0)
    val counterOllama1 = new AtomicInteger(0)
    val counterOllama2 = new AtomicInteger(0)
    for (i <- 0 until 20) {
      val res = call("foo")
      val body = res.body
      assertEquals(res.status, 200, "status should be 200")
      if (body.contains("ollama1")) counterOllama1.incrementAndGet()
      if (body.contains("ollama2")) counterOllama2.incrementAndGet()
    }

    assertEquals(countercalls.get(), 20, "20 calls")
    assertEquals(counterOllama1.get(), 10, "server ollama1 should have 10 calls")
    assertEquals(counterOllama2.get(), 10, "server ollama2 should have 10 calls")
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  teardown                                                      ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").deleteEntity(llmProvider1).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").deleteEntity(llmProvider2).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").deleteEntity(llmProviderLb).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").deleteEntity(route).awaitf(10.seconds)
    await(2.seconds)
  }

  test("llm provider can be loadbalanced according to a predicate") {
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  setup                                                         ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    val llmProvider1 = ollamaProvider("ollama 1", "foo", ollama1Port)
    val llmProvider2 = ollamaProvider("ollama 2", "foo", ollama2Port)
    val llmProvider3 = ollamaProvider("ollama 3", "bar", ollama3Port)
    val llmProvider4 = ollamaProvider("ollama 4", "bar", ollama4Port)
    val llmProviderLb = AiProvider(
      id = UUID.randomUUID().toString,
      name = "lb",
      provider = "loadbalancer",
      connection = Json.obj(),
      options = Json.obj(
        "loadbalancing" -> "round_robin",
        "selector_expr" -> "model",
        "refs" -> Json.arr(
          Json.obj(
            "ref" -> llmProvider1.id,
            "selector_expected" -> "foo"
          ),
          Json.obj(
            "ref" -> llmProvider2.id,
            "selector_expected" -> "foo"
          ),
          Json.obj(
            "ref" -> llmProvider3.id,
            "selector_expected" -> "bar"
          ),
          Json.obj(
            "ref" -> llmProvider4.id,
            "selector_expected" -> "bar"
          )
        ),
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
          "refs" -> Json.arr(llmProviderLb.id)
        ))
      )))
    )
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").upsertEntity(llmProvider1).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").upsertEntity(llmProvider2).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").upsertEntity(llmProvider3).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").upsertEntity(llmProvider4).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").upsertEntity(llmProviderLb).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(route).awaitf(10.seconds)
    await(2.seconds)
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  test                                                          ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    val countercalls = new AtomicInteger(0)

    def call(model: String): WSResponse = {
      countercalls.incrementAndGet()
      client.call("POST", s"http://test.oto.tools:${port}/chat", Map.empty, Some(Json.obj(
        "model" -> model,
        "messages" -> Json.arr(Json.obj(
          "role" -> "user",
          "content" -> "whatever"
        ))
      ))).awaitf(30.seconds)
    }
    val counterFoo = new AtomicInteger(0)
    val counterBar = new AtomicInteger(0)
    val counterOllama1 = new AtomicInteger(0)
    val counterOllama2 = new AtomicInteger(0)
    val counterOllama3 = new AtomicInteger(0)
    val counterOllama4 = new AtomicInteger(0)
    for (i <- 0 until 6) {
      val res = call("foo")
      val body = res.body
      println(s"res foo: ${i} - ${res.status} - ${body}")
      assertEquals(res.status, 200, "status should be 200")
      if (body.contains("foo")) counterFoo.incrementAndGet()
      if (body.contains("bar")) counterBar.incrementAndGet()
      if (body.contains("ollama1")) counterOllama1.incrementAndGet()
      if (body.contains("ollama2")) counterOllama2.incrementAndGet()
      if (body.contains("ollama3")) counterOllama3.incrementAndGet()
      if (body.contains("ollama4")) counterOllama4.incrementAndGet()
    }
    for (i <- 0 until 4) {
      val res = call("bar")
      val body = res.body
      println(s"res bar: ${i} - ${res.status} - ${body}")
      assertEquals(res.status, 200, "status should be 200")
      if (body.contains("foo")) counterFoo.incrementAndGet()
      if (body.contains("bar")) counterBar.incrementAndGet()
      if (body.contains("ollama1")) counterOllama1.incrementAndGet()
      if (body.contains("ollama2")) counterOllama2.incrementAndGet()
      if (body.contains("ollama3")) counterOllama3.incrementAndGet()
      if (body.contains("ollama4")) counterOllama4.incrementAndGet()
    }

    assertEquals(countercalls.get(), 10, "10 calls")
    assertEquals(counterFoo.get(), 6, "model foo should have 6 call")
    assertEquals(counterBar.get(), 4, "model bar should have 4 call")
    assertEquals(counterOllama1.get(), 3, "server ollama1 should have 3 calls")
    assertEquals(counterOllama2.get(), 3, "server ollama2 should have 3 calls")
    assertEquals(counterOllama3.get(), 2, "server ollama3 should have 2 calls")
    assertEquals(counterOllama4.get(), 2, "server ollama4 should have 2 calls")
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  teardown                                                      ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").deleteEntity(llmProvider1).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").deleteEntity(llmProvider2).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").deleteEntity(llmProvider3).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").deleteEntity(llmProvider4).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").deleteEntity(llmProviderLb).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").deleteEntity(route).awaitf(10.seconds)
    await(2.seconds)
  }
}