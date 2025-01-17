package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.LlmExtensionOneOtoroshiServerPerSuite
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiProvider, CacheSettings}
import com.google.common.base.Charsets
import otoroshi.events.DataExporter
import otoroshi.models.{DataExporterConfig, DataExporterConfigFiltering, DataExporterConfigTypeWebhook, EntityLocation, Webhook}
import otoroshi.next.models._
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.OpenAiCompatProxy
import play.api.libs.json.Json
import play.api.libs.ws.WSResponse
import reactor.core.publisher.Mono

import java.util.UUID
import scala.concurrent.duration.DurationInt

class AuditingSuite extends LlmExtensionOneOtoroshiServerPerSuite {

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

  var messages = Seq.empty[String]

  val (ingestPort, _) = createTestServerWithRoutes("ingest", routes => routes.post("/ingest", (req, response) => {
    req.receive().retain().asString().flatMap { body =>
      // println(s"ingest body: ${body}")
      messages = messages :+ body
      response
        .status(200)
        .addHeader("Content-Type", "application/json")
        .sendString(Mono.just(
          s"""{"done":true}""".stripMargin))
    }
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

  test("llm provider should generate audit trail") {
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  setup                                                         ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    val llmProvider1 = ollamaProvider("ollama 1", "llama3.2", ollama1Port)
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
    val exporter = DataExporterConfig(
      enabled = true,
      typ = DataExporterConfigTypeWebhook,
      id = UUID.randomUUID().toString,
      name = "exporter",
      desc = "exporter",
      bufferSize = 10,
      jsonWorkers = 1,
      sendWorkers = 1,
      groupSize = 2,
      groupDuration = 2000.millis,
      filtering = DataExporterConfigFiltering(
        include = Seq(Json.obj(
          "@type" -> "AuditEvent",
          "audit" -> "LLMUsageAudit",
        ))
      ),
      projection = Json.obj(),
      config = Webhook(
        url = s"http://localhost:${ingestPort}/ingest"
      )
    )
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").upsertEntity(llmProvider1).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(route).awaitf(10.seconds)
    client.forEntity("events.otoroshi.io", "v1", "data-exporters").upsertEntity(exporter).awaitf(10.seconds)
    await(20.seconds)
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  test                                                          ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    def call(model: String): WSResponse = {
      client.call("POST", s"http://test.oto.tools:${port}/chat", Map.empty, Some(Json.obj(
        "model" -> model,
        "messages" -> Json.arr(Json.obj(
          "role" -> "user",
          "content" -> "whatever"
        ))
      ))).awaitf(30.seconds)
    }

    for (i <- 0 until 20) {
      val res = call("llama3.2")
      assertEquals(res.status, 200, "status should be 200")
    }

    await(8.seconds)
    assertEquals(messages.size, 10, "there should be 10 array of 2 messages")
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  teardown                                                      ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").deleteEntity(llmProvider1).awaitf(10.seconds)
    client.forEntity("events.otoroshi.io", "v1", "data-exporters").deleteEntity(exporter).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").deleteEntity(route).awaitf(10.seconds)
    await(2.seconds)
  }
}