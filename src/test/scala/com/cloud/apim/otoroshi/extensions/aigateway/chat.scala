package com.cloud.apim.otoroshi.extensions.aigateway

import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsObject, Json}

import java.util.UUID
import scala.concurrent.duration.DurationInt

class ProvidersSuite extends munit.FunSuite {

  def testProvider(providerName: String, tokenEnv: String, options: JsObject, baseUrl: Option[String] = None, testChat: Boolean = true, testStreaming: Boolean = true, testCompletion: Boolean = true, testModels: Boolean = true): Unit = {
    val token = sys.env(tokenEnv)
    val port = Utils.freePort
    val otoroshi = Utils.startOtoroshi(port)
    val client = Utils.clientFor(port)
    val providerId = s"provider_${UUID.randomUUID().toString}"
    val routeChatId = s"route_${UUID.randomUUID().toString}"
    val routeCompletionId = s"route_${UUID.randomUUID().toString}"
    val routeModelsId = s"route_${UUID.randomUUID().toString}"
    val conn = baseUrl match {
      case None => Json.obj(
        "token" -> token,
        "timeout" -> 30000
      )
      case Some(url) => Json.obj(
        "base_url" -> url,
        "token" -> token,
        "timeout" -> 30000
      )
    }
    val provider = client.forLlmEntity("providers").create(Json.parse(
      s"""{
         |  "id": "${providerId}",
         |  "name": "${providerName} provider",
         |  "description": "${providerName} provider",
         |  "provider": "${providerName}",
         |  "connection": ${conn.stringify},
         |  "options": ${options.stringify}
         |}""".stripMargin)).awaitf(10.seconds)(otoroshi.executionContext)
    val routeChat = client.forEntity("proxy.otoroshi.io", "v1", "routes").create(Json.parse(
      s"""{
         |  "id": "${routeChatId}",
         |  "name": "openai",
         |  "frontend": {
         |    "domains": [
         |      "${providerName}.oto.tools/chat"
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
         |}""".stripMargin)).awaitf(10.seconds)(otoroshi.executionContext)
    val routeCompletion = client.forEntity("proxy.otoroshi.io", "v1", "routes").create(Json.parse(
      s"""{
         |  "id": "${routeCompletionId}",
         |  "name": "openai",
         |  "frontend": {
         |    "domains": [
         |      "${providerName}.oto.tools/completion"
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
         |      "plugin": "cp:otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.OpenAiCompletionProxy",
         |      "config": {
         |        "refs": [
         |          "${providerId}"
         |        ]
         |      }
         |    }
         |  ]
         |}""".stripMargin)).awaitf(10.seconds)(otoroshi.executionContext)
    val routeModels = client.forEntity("proxy.otoroshi.io", "v1", "routes").create(Json.parse(
      s"""{
         |  "id": "${routeModelsId}",
         |  "name": "openai",
         |  "frontend": {
         |    "domains": [
         |      "${providerName}.oto.tools/models"
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
         |      "plugin": "cp:otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.OpenAiCompatProvidersWithModels",
         |      "config": {
         |        "refs": [
         |          "${providerId}"
         |        ]
         |      }
         |    }
         |  ]
         |}""".stripMargin)).awaitf(10.seconds)(otoroshi.executionContext)
    assert(routeChat.created, s"[${providerName}] route chat has not been created")
    assert(routeCompletion.created, s"[${providerName}] route completion has not been created")
    assert(routeModels.created, s"[${providerName}] route models has not been created")
    assert(provider.created, s"[${providerName}] provider has not been created")
    Utils.await(2.seconds)
    if (testChat) {
      val resp = client.call("POST", s"http://${providerName}.oto.tools:${port}/chat", Map.empty, Some(Json.parse(
        s"""{
           |  "messages": [
           |    {
           |      "role": "user",
           |      "content": "hey, how are you ?"
           |    }
           |  ]
           |}""".stripMargin))).awaitf(10.seconds)(otoroshi.executionContext)
      if (resp.status != 200 && resp.status != 201) {
        println(resp.body)
      }
      assertEquals(resp.status, 200, s"[${providerName}] chat route did not respond with 200")
      val pointer = resp.json.at("choices.0.message.content")
      assert(pointer.isDefined, s"[${providerName}] no message content")
      assert(pointer.get.asString.nonEmpty, s"[${providerName}] no message")
      println(s"[${providerName}] message: ${pointer.asString}")
    }
    if (testStreaming) {
      Utils.await(2.seconds)
      val resp = client.stream("POST", s"http://${providerName}.oto.tools:${port}/chat?stream=true", Map.empty, Some(Json.parse(
        s"""{
           |  "messages": [
           |    {
           |      "role": "user",
           |      "content": "hey, how are you ?"
           |    }
           |  ]
           |}""".stripMargin))).awaitf(10.seconds)(otoroshi.executionContext)
      if (resp.status != 200 && resp.status != 201) {
        println(resp.chunks)
      }
      assertEquals(resp.status, 200, s"[${providerName}] chat route did not respond with 200")
      // val pointer = resp.json.at("choices.0.message.content")
      // assert(pointer.isDefined, s"[${providerName}] no message content")
      assert(resp.message.nonEmpty, s"[${providerName}] no message")
      println(s"[${providerName}] message: ${resp.message}")
    }
    if (testCompletion) {
      Utils.await(2.seconds)
      val resp = client.call("POST", s"http://${providerName}.oto.tools:${port}/completion", Map.empty, Some(Json.parse(
        s"""{
           |  "prompt": "hey, how are you ?"
           |}""".stripMargin))).awaitf(10.seconds)(otoroshi.executionContext)
      if (resp.status != 200 && resp.status != 201) {
        println(resp.body)
      }
      assertEquals(resp.status, 200, s"[${providerName}] completion route did not respond with 200")
      val pointer = resp.json.at("choices.0.text")
      assert(pointer.isDefined, s"[${providerName}] no message content")
      assert(pointer.get.asString.nonEmpty, s"[${providerName}] no message")
      println(s"[${providerName}] message: ${pointer.asString}")
    }
    if (testModels) {
      Utils.await(2.seconds)
      val resp = client.call("GET", s"http://${providerName}.oto.tools:${port}/models", Map.empty, None).awaitf(10.seconds)(otoroshi.executionContext)
      if (resp.status != 200 && resp.status != 201) {
        println(resp.body)
      }
      assertEquals(resp.status, 200, s"[${providerName}] models route did not respond with 200")
      val models: Seq[String] = resp.json.select("data").asOpt[Seq[JsObject]].map(_.map(_.select("id").asString)).getOrElse(Seq.empty)
      assert(models.nonEmpty, s"[${providerName}] no models")
      println(s"[${providerName}] models: ${models.mkString(", ")}")
    }
    otoroshi.stop()
  }

  test("basic chat/streaming/completions/models with providers") {

    testProvider("openai", "OPENAI_TOKEN", Json.obj("model" -> "gpt-4o-mini", "max_tokens" -> 128), testCompletion = false)
    testProvider("openai", "OPENAI_TOKEN", Json.obj("model" -> "gpt-3.5-turbo-instruct", "max_tokens" -> 128), testChat = false, testStreaming = false)
    testProvider("ollama", "OLLAMA_TOKEN", Json.obj("model" -> "llama3.2:latest", "num_predict" -> 128))
    testProvider("deepseek", "DEEPSEEK_TOKEN", Json.obj("model" -> "deepseek-chat", "max_tokens" -> 128), testCompletion = false)
    testProvider("mistral", "MISTRAL_TOKEN", Json.obj("model" -> "mistral-large-latest", "max_tokens" -> 128), testCompletion = false)
    testProvider("mistral", "MISTRAL_TOKEN", Json.obj("model" -> "codestral-latest", "max_tokens" -> 128), testChat = false)
    testProvider("gemini", "GEMINI_API_KEY", Json.obj("maxOutputTokens" -> 128))
    testProvider("scaleway", "SCALEWAY_TOKEN", Json.obj("model" -> "llama-3.1-8b-instruct", "max_tokens" -> 128))
    testProvider("huggingface", "HUGGINGFACE_TOKEN", Json.obj("model" -> "google/gemma-2-2b-it", "max_tokens" -> 128, "top_p" -> 0.9), testModels = false)
    testProvider("groq", "GROQ_TOKEN", Json.obj("model" -> "llama3-8b-8192", "max_tokens" -> 128))
    testProvider("cohere", "COHERE_TOKEN", Json.obj("model" -> "command-r7b-12-2024", "max_tokens" -> 128))

    // testProvider("azure-openai", "AZURE_OPENAI_TOKEN", Json.obj("model" -> "claude-3-5-sonnet-latest", "max_tokens" -> 128)) // TODO: no account
    // testProvider("cloudflare", "CLOUDFLARE_TOKEN", Json.obj("model" -> "claude-3-5-sonnet-latest", "max_tokens" -> 128))     // TODO: no account

    // testProvider("ovh-ai-endpoints", "OVH_AI_TOKEN", Json.obj("model" -> "llama_3_70b_instruct", "max_tokens" -> 128))       // TODO: need to find how to retrieve new token automatically
    // testProvider("anthropic", "ANTHROPIC_TOKEN", Json.obj("model" -> "claude-3-5-sonnet-latest", "max_tokens" -> 128))       // TODO: need a creditcard
    // testProvider("x-ai", "XAI_TOKEN", Json.obj("model" -> "grok-beta", "max_tokens" -> 128))                                 // TODO: need a creditcard
  }
}