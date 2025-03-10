package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.LlmExtensionOneOtoroshiServerPerSuite
import com.cloud.apim.otoroshi.extensions.aigateway.decorators.{GuardrailItem, Guardrails}
import com.cloud.apim.otoroshi.extensions.aigateway.domains.LlmProviderUtils
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiProvider, LlmToolFunction}
import otoroshi.models.WasmPlugin
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.Json
import reactor.core.publisher.Mono

import java.util.UUID
import scala.concurrent.duration.DurationInt

class QuickJsGuardrailSuite extends LlmExtensionOneOtoroshiServerPerSuite {

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

  test("llm provider can have quickjs guardrails") {
    val providerId = s"provider_${UUID.randomUUID().toString}"
    val routeChatId = s"route_${UUID.randomUUID().toString}"

    val code =
      """'inline module';
        |
        |exports.guardrail_call = function(args) {
        |  const { messages } = args;
        |  if (JSON.stringify(messages).indexOf('dummy') > -1) {
        |    return JSON.stringify({
        |      pass: false,
        |      reason: "you cant say dummy bro !"
        |    });
        |  } else {
        |    return JSON.stringify({
        |      pass: true,
        |      reason: "none"
        |    });
        |  }
        |};
        |""".stripMargin

    val llmprovider = AiProvider(
      id = providerId,
      name = s"test provider",
      provider = "ollama",
      connection = Json.obj(
        "base_url" -> s"http://localhost:${ollama1Port}",
        "timeout" -> 30000
      ),
      options = Json.obj(
        "model" -> "llama3.2",
        "num_predict" -> 256,
      ),
      guardrails = Guardrails(Seq(GuardrailItem(
        enabled = true,
        before = true,
        after = false,
        guardrailId = "quickjs",
        config = Json.obj(
          "quickjs_path" -> code
        )
      )))
    )
    LlmProviderUtils.upsertProvider(client)(llmprovider)
    val routeChat = client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertRaw(routeChatId, Json.parse(
      s"""{
         |  "id": "${routeChatId}",
         |  "name": "openai",
         |  "frontend": {
         |    "domains": [
         |      "ollama.oto.tools/chat"
         |    ]
         |  },
         |  "backend": {
         |    "targets": [
         |      {
         |        "id": "target_1",
         |        "hostname": "request.otoroshi.io",
         |        "port": 443,
         |        "tls": true
         |      }
         |    ],
         |    "root": "/",
         |    "rewrite": false,
         |    "load_balancing": {
         |      "type": "RoundRobin"
         |    }
         |  },
         |  "plugins": [
         |    {
         |      "enabled": true,
         |      "plugin": "cp:otoroshi.next.plugins.OverrideHost"
         |    },
         |    {
         |      "plugin": "cp:otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.OpenAiCompatProxy",
         |      "config": {
         |        "refs": [
         |          "${providerId}"
         |        ]
         |      }
         |    }
         |  ]
         |}""".stripMargin)).awaitf(30.seconds)
    assert(routeChat.created, s"route chat has not been created")
    val payload = WasmPlugin(
      id = LlmToolFunction.wasmPluginId,
      name = "Otoroshi LLM Extension - tool call runtime",
      description = "This plugin provides the runtime for the wasm backed LLM tool calls",
      config = LlmToolFunction.wasmConfig
    ).json.stringify.byteString
    otoroshi.env.datastores.rawDataStore.set(s"otoroshi:wasm-plugins:${LlmToolFunction.wasmPluginId}", payload, None)(otoroshi.executionContext, otoroshi.env).awaitf(10.seconds)
    await(1300.millis)

    {
      val resp1 = client.call("POST", s"http://ollama.oto.tools:${port}/chat", Map.empty, Some(Json.parse(
        s"""{
           |  "messages": [
           |    {
           |      "role": "user",
           |      "content": "hey, how are you ?"
           |    }
           |  ]
           |}""".stripMargin))).awaitf(30.seconds)
      assertEquals(resp1.status, 200, s"chat route did not respond with 200")
      val pointer = resp1.json.at("choices.0.message.content")
      assert(pointer.get.asString.nonEmpty, s"no message")
      val message = pointer.asString
      println(s"message: ${message}")
      assert(message.startsWith("I am the ollama1 with model foo"), "should starts with I am the ollama1 with model foo")
    }

    {
      val resp2 = client.call("POST", s"http://ollama.oto.tools:${port}/chat", Map.empty, Some(Json.parse(
        s"""{
           |  "messages": [
           |    {
           |      "role": "user",
           |      "content": "hey dummy, how are you ?"
           |    }
           |  ]
           |}""".stripMargin))).awaitf(30.seconds)
      assertEquals(resp2.status, 200, s"chat route did not respond with 200")
      val pointer = resp2.json.at("choices.0.message.content")
      assert(pointer.get.asString.nonEmpty, s"no message")
      val message = pointer.asString
      println(s"message: ${message}")
      assertEquals(message, "you cant say dummy bro !", "should be equals to you cant say dummy bro !")
    }

    client.forLlmEntity("providers").deleteEntity(llmprovider)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").deleteRaw(routeChatId)
    await(1300.millis)
  }

}
