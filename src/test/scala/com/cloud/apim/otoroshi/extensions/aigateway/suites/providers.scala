package com.cloud.apim.otoroshi.extensions.aigateway.suites

import akka.stream.Materializer
import com.cloud.apim.otoroshi.extensions.aigateway.{LLmExtensionSuite, LlmExtensionOneOtoroshiServerPerSuite, LlmProviders, OtoroshiClient, TestLlmProviderSettings}
import com.cloud.apim.otoroshi.extensions.aigateway.domains.LlmProviderUtils
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiProvider, LlmToolFunction, LlmToolFunctionBackend, LlmToolFunctionBackendKind, LlmToolFunctionBackendOptions}
import otoroshi.api.Otoroshi
import otoroshi.models.WasmPlugin
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsObject, Json}

import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{DurationInt, FiniteDuration}

class ProvidersSuite extends LLmExtensionSuite {

  def testChatCompletionWith(provider: TestLlmProviderSettings, client: OtoroshiClient, awaitFor: FiniteDuration)(implicit ec: ExecutionContext, mat: Materializer): Unit = {
    val token = sys.env(provider.envToken)
    val port = client.port
    val providerId = s"provider_${UUID.randomUUID().toString}"
    val routeChatId = s"route_${UUID.randomUUID().toString}"

    val llmprovider = AiProvider(
      id = providerId,
      name = s"${provider.name} provider",
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
    )
    LlmProviderUtils.upsertProvider(client)(llmprovider)
    val routeChat = client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertRaw(routeChatId, Json.parse(
      s"""{
         |  "id": "${routeChatId}",
         |  "name": "openai",
         |  "frontend": {
         |    "domains": [
         |      "${provider.name}.oto.tools/chat"
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
         |}""".stripMargin)).awaitf(awaitFor)
    assert(routeChat.created, s"[${provider.name}] route chat has not been created")
    await(1300.millis)
    val resp = client.call("POST", s"http://${provider.name}.oto.tools:${port}/chat", Map.empty, Some(Json.parse(
      s"""{
         |  "messages": [
         |    {
         |      "role": "user",
         |      "content": "hey, how are you ?"
         |    }
         |  ]
         |}""".stripMargin))).awaitf(awaitFor)
    if (resp.status != 200 && resp.status != 201) {
      println(resp.body)
    }
    assertEquals(resp.status, 200, s"[${provider.name}] chat route did not respond with 200")
    val pointer = resp.json.at("choices.0.message.content")
    assert(pointer.isDefined, s"[${provider.name}] no message content")
    assert(pointer.get.asString.nonEmpty, s"[${provider.name}] no message")
    println(s"[${provider.name}] message: ${pointer.asString}")
    client.forLlmEntity("providers").deleteEntity(llmprovider)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").deleteRaw(routeChatId)
    await(1300.millis)
  }

  def testChatCompletionStreamingWith(provider: TestLlmProviderSettings, client: OtoroshiClient, awaitFor: FiniteDuration)(implicit ec: ExecutionContext, mat: Materializer): Unit = {
    val token = sys.env(provider.envToken)
    val port = client.port
    val providerId = s"provider_${UUID.randomUUID().toString}"
    val routeChatId = s"route_${UUID.randomUUID().toString}"

    val llmprovider = AiProvider(
      id = providerId,
      name = s"${provider.name} provider",
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
    )
    LlmProviderUtils.upsertProvider(client)(llmprovider)
    val routeChat = client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertRaw(routeChatId, Json.parse(
      s"""{
         |  "id": "${routeChatId}",
         |  "name": "openai",
         |  "frontend": {
         |    "domains": [
         |      "${provider.name}.oto.tools/chat"
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
         |}""".stripMargin)).awaitf(awaitFor)
    assert(routeChat.created, s"[${provider.name}] route chat has not been created")
    await(1300.millis)
    val resp = client.stream("POST", s"http://${provider.name}.oto.tools:${port}/chat?stream=true", Map.empty, Some(Json.parse(
      s"""{
         |  "messages": [
         |    {
         |      "role": "user",
         |      "content": "hey, how are you ?"
         |    }
         |  ]
         |}""".stripMargin))).awaitf(awaitFor)
    if (resp.status != 200 && resp.status != 201) {
      println(resp.chunks)
    }
    assertEquals(resp.status, 200, s"[${provider.name}] chat route did not respond with 200")
    assert(resp.chunks.nonEmpty, s"[${provider.name}] no chunks")
    assert(resp.message.nonEmpty, s"[${provider.name}] no message")
    println(s"[${provider.name}] message: ${resp.message}")
    client.forLlmEntity("providers").deleteEntity(llmprovider)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").deleteRaw(routeChatId)
    await(1300.millis)
  }

  def testCompletionWith(provider: TestLlmProviderSettings, client: OtoroshiClient, awaitFor: FiniteDuration)(implicit ec: ExecutionContext, mat: Materializer): Unit = {
    val port = client.port
    val token = sys.env(provider.envToken)
    val providerId = s"provider_${UUID.randomUUID().toString}"
    val routeCompletionId = s"route_${UUID.randomUUID().toString}"

    val llmprovider = AiProvider(
      id = providerId,
      name = s"${provider.name} provider",
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
    )
    LlmProviderUtils.createProvider(client)(llmprovider)
    val routeCompletion = client.forEntity("proxy.otoroshi.io", "v1", "routes").createRaw(Json.parse(
      s"""{
         |  "id": "${routeCompletionId}",
         |  "name": "openai",
         |  "frontend": {
         |    "domains": [
         |      "${provider.name}.oto.tools/completion"
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
         |}""".stripMargin)).awaitf(awaitFor)
    assert(routeCompletion.created, s"[${provider.name}] route completion has not been created")
    await(1300.millis)
    val resp = client.call("POST", s"http://${provider.name}.oto.tools:${port}/completion", Map.empty, Some(Json.parse(
      s"""{
         |  "prompt": "hey, how are you ?"
         |}""".stripMargin))).awaitf(awaitFor)
    if (resp.status != 200 && resp.status != 201) {
      println(resp.body)
    }
    assertEquals(resp.status, 200, s"[${provider.name}] completion route did not respond with 200")
    val pointer = resp.json.at("choices.0.text")
    assert(pointer.isDefined, s"[${provider.name}] no message content")
    assert(pointer.get.asString.nonEmpty, s"[${provider.name}] no message")
    println(s"[${provider.name}] message: ${pointer.asString}")
    client.forLlmEntity("providers").deleteEntity(llmprovider)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").deleteRaw(routeCompletionId)
    await(1300.millis)
  }

  def testModelsWith(provider: TestLlmProviderSettings, client: OtoroshiClient, awaitFor: FiniteDuration)(implicit ec: ExecutionContext, mat: Materializer): Unit = {

    val port = client.port
    val providerId = s"provider_${UUID.randomUUID().toString}"
    val routeModelsId = s"route_${UUID.randomUUID().toString}"
    val providerName = provider.name
    val token = sys.env(provider.envToken)

    val llmprovider = AiProvider(
      id = providerId,
      name = s"${provider.name} provider",
      provider = providerName,
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
    )
    LlmProviderUtils.upsertProvider(client)(llmprovider)
    val routeModels = client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertRaw(routeModelsId, Json.parse(
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
         |}""".stripMargin)).awaitf(awaitFor)
    assert(routeModels.created, s"[${providerName}] route models has not been created")
    await(1300.millis)
    val resp = client.call("GET", s"http://${providerName}.oto.tools:${port}/models", Map.empty, None).awaitf(awaitFor)
    if (resp.status != 200 && resp.status != 201) {
      println(resp.body)
    }
    assertEquals(resp.status, 200, s"[${providerName}] models route did not respond with 200")
    val models: Seq[String] = resp.json.select("data").asOpt[Seq[JsObject]].map(_.map(_.select("id").asString)).getOrElse(Seq.empty)
    assert(models.nonEmpty, s"[${providerName}] no models")
    println(s"[${providerName}] models: ${models.mkString(", ")}")
    client.forLlmEntity("providers").deleteEntity(llmprovider)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").deleteRaw(routeModelsId)
    await(1300.millis)
  }

  def testToolsCallWith(provider: TestLlmProviderSettings, client: OtoroshiClient, awaitFor: FiniteDuration)(implicit ec: ExecutionContext, mat: Materializer): Unit = {
    val token = sys.env(provider.envToken)
    val port = client.port
    val providerId = s"provider_${UUID.randomUUID().toString}"
    val routeChatId = s"route_${UUID.randomUUID().toString}"
    val functionId = s"tool-function_${UUID.randomUUID().toString}"

    val llmprovider = AiProvider(
      id = providerId,
      name = s"${provider.name} provider",
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
      options = provider.options ++ Json.obj(
        "max_tokens" -> 256,
        "wasm_tools" -> Json.arr(functionId)
      ).applyOnIf(provider.name == "ollama") { obj =>
        obj ++ Json.obj("num_predict" -> 128)
      },
    )
    LlmProviderUtils.upsertProvider(client)(llmprovider)
    val function = LlmToolFunction(
      id = functionId,
      name = "get_flight_times",
      description = "Get the flight times between two cities",
      strict = false,
      parameters = Json.parse("""{
                                |  "departure": {
                                |    "type": "string",
                                |    "description": "The departure city (airport code)"
                                |  },
                                |  "arrival": {
                                |    "type": "string",
                                |    "description": "The arrival city (airport code)"
                                |  }
                                |}""".stripMargin).asObject,
      backend = LlmToolFunctionBackend(
        kind = LlmToolFunctionBackendKind.QuickJs,
        options = LlmToolFunctionBackendOptions.QuickJs(
          """'inline module';
            |
            |exports.tool_call = function(args) {
            |  return JSON.stringify({ departure: "08:00 AM", arrival: "11:30 AM", duration: "5h 30m" });
            |}""".stripMargin
        )
      )
    )
    val fr = client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "tool-functions").upsertEntity(function).awaitf(awaitFor)
    assert(fr.createdOrUpdated, s"[${provider.name}] function has not been created")
    val routeChat = client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertRaw(routeChatId, Json.parse(
      s"""{
         |  "id": "${routeChatId}",
         |  "name": "openai",
         |  "frontend": {
         |    "domains": [
         |      "${provider.name}.oto.tools/chat"
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
         |}""".stripMargin)).awaitf(awaitFor)
    assert(routeChat.created, s"[${provider.name}] route chat has not been created")
    val payload = WasmPlugin(
      id = LlmToolFunction.wasmPluginId,
      name = "Otoroshi LLM Extension - tool call runtime",
      description = "This plugin provides the runtime for the wasm backed LLM tool calls",
      config = LlmToolFunction.wasmConfig
    ).json.stringify.byteString
    otoroshi.env.datastores.rawDataStore.set(s"otoroshi:wasm-plugins:${LlmToolFunction.wasmPluginId}", payload, None)(otoroshi.executionContext, otoroshi.env).awaitf(10.seconds)
    await(1300.millis)
    val resp = client.call("POST", s"http://${provider.name}.oto.tools:${port}/chat", Map.empty, Some(Json.parse(
      s"""{
         |  "messages": [
         |    {
         |      "role": "user",
         |      "content": "how long is the flight between LAX and CDG ?"
         |    }
         |  ]
         |}""".stripMargin))).awaitf(awaitFor)
    if (resp.status != 200 && resp.status != 201) {
      println(resp.body)
    }
    assertEquals(resp.status, 200, s"[${provider.name}] chat route did not respond with 200")
    val pointer = resp.json.at("choices.0.message.content")
    assert(pointer.isDefined, s"[${provider.name}] no message content")
    assert(pointer.get.asString.nonEmpty, s"[${provider.name}] no message")
    val message = pointer.asString
    println(s"[${provider.name}] message: ${message}")
    assert(message.contains("5"), "contains number of hours")
    assert(message.contains("30"), "contains number of minutes")
    client.forLlmEntity("providers").deleteEntity(llmprovider)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").deleteRaw(routeChatId)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "tool-functions").deleteEntity(function)
    await(1300.millis)
  }

  def testToolsCallStreamWith(provider: TestLlmProviderSettings, client: OtoroshiClient, awaitFor: FiniteDuration)(implicit ec: ExecutionContext, mat: Materializer): Unit = {
    val token = sys.env(provider.envToken)
    val port = client.port
    val providerId = s"provider_${UUID.randomUUID().toString}"
    val routeChatId = s"route_${UUID.randomUUID().toString}"
    val functionId = s"tool-function_${UUID.randomUUID().toString}"

    val llmprovider = AiProvider(
      id = providerId,
      name = s"${provider.name} provider",
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
      options = provider.options ++ Json.obj(
        "max_tokens" -> 256,
        "wasm_tools" -> Json.arr(functionId)
      ).applyOnIf(provider.name == "ollama") { obj =>
        obj ++ Json.obj("num_predict" -> 128)
      },
    )
    LlmProviderUtils.upsertProvider(client)(llmprovider)
    val function = LlmToolFunction(
      id = functionId,
      name = "get_flight_times",
      description = "Get the flight times between two cities",
      strict = false,
      parameters = Json.parse("""{
                                |  "departure": {
                                |    "type": "string",
                                |    "description": "The departure city (airport code)"
                                |  },
                                |  "arrival": {
                                |    "type": "string",
                                |    "description": "The arrival city (airport code)"
                                |  }
                                |}""".stripMargin).asObject,
      backend = LlmToolFunctionBackend(
        kind = LlmToolFunctionBackendKind.QuickJs,
        options = LlmToolFunctionBackendOptions.QuickJs(
          """'inline module';
            |
            |exports.tool_call = function(args) {
            |  return JSON.stringify({ departure: "08:00 AM", arrival: "11:30 AM", duration: "5h 30m" });
            |}""".stripMargin
        )
      )
    )
    val fr = client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "tool-functions").upsertEntity(function).awaitf(awaitFor)
    assert(fr.createdOrUpdated, s"[${provider.name}] function has not been created")
    val routeChat = client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertRaw(routeChatId, Json.parse(
      s"""{
         |  "id": "${routeChatId}",
         |  "name": "openai",
         |  "frontend": {
         |    "domains": [
         |      "${provider.name}.oto.tools/chat"
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
         |}""".stripMargin)).awaitf(awaitFor)
    assert(routeChat.created, s"[${provider.name}] route chat has not been created")
    val payload = WasmPlugin(
      id = LlmToolFunction.wasmPluginId,
      name = "Otoroshi LLM Extension - tool call runtime",
      description = "This plugin provides the runtime for the wasm backed LLM tool calls",
      config = LlmToolFunction.wasmConfig
    ).json.stringify.byteString
    otoroshi.env.datastores.rawDataStore.set(s"otoroshi:wasm-plugins:${LlmToolFunction.wasmPluginId}", payload, None)(otoroshi.executionContext, otoroshi.env).awaitf(10.seconds)
    await(1300.millis)
    val resp = client.stream("POST", s"http://${provider.name}.oto.tools:${port}/chat?stream=true", Map.empty, Some(Json.parse(
      s"""{
         |  "messages": [
         |    {
         |      "role": "user",
         |      "content": "how long is the flight between LAX and CDG ?"
         |    }
         |  ]
         |}""".stripMargin))).awaitf(awaitFor)
    println(resp.state)
    assertEquals(resp.status, 200, s"[${provider.name}] chat route did not respond with 200")
    assert(resp.chunks.nonEmpty, s"[${provider.name}] no chunk")
    assert(resp.message.nonEmpty, s"[${provider.name}] no message")
    println(s"[${provider.name}] message: ${resp.message}")
    assert(resp.message.contains("5"), "contains number of hours")
    assert(resp.message.contains("30"), "contains number of minutes")
    client.forLlmEntity("providers").deleteEntity(llmprovider)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").deleteRaw(routeChatId)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "tool-functions").deleteEntity(function)
    await(1300.millis)
  }

  def printHeader(str: String, what: String): Unit = {
    println("\n\n-----------------------------------------")
    println(s"  [${str}] - ${what}")
    println("-----------------------------------------\n\n")
  }

  val port: Int = freePort
  var otoroshi: Otoroshi = _
  var client: OtoroshiClient = _
  implicit var ec: ExecutionContext = _
  implicit var mat: Materializer = _

  override def beforeAll(): Unit = {
    otoroshi = startOtoroshiServer(port)
    client = clientFor(port)
    ec = otoroshi.executionContext
    mat = otoroshi.materializer
  }

  override def afterAll(): Unit = {
    otoroshi.stop()
  }
/*
  // test("providers should support chat no streaming")
  LlmProviders.listOfProvidersSupportingChatCompletions.foreach { provider =>
    test(s"provider '${provider.name}' should support chat/completions sync") {
      printHeader(provider.name, "chat/completions sync")
      testChatCompletionWith(provider, client, 30.seconds)
    }
  }

  // test("providers should support chat with streaming")
  LlmProviders.listOfProvidersSupportingChatCompletionsStream.foreach { provider =>
    test(s"provider '${provider.name}' should support chat/completions stream") {
      printHeader(provider.name, "chat/completions stream")
      testChatCompletionStreamingWith(provider, client, 30.seconds)
    }
  }

  // test("providers should support simple completions")
  LlmProviders.listOfProvidersSupportingCompletions.foreach { provider =>
    test(s"provider '${provider.name}' should support simple completions") {
      printHeader(provider.name, "completions")
      testCompletionWith(provider, client, 30.seconds)
    }
  }

  // test("providers should support models listing")
  LlmProviders.listOfProvidersSupportingModels.foreach { provider =>
    test(s"provider '${provider.name}' should support models listing") {
      printHeader(provider.name, "models listing")
      testModelsWith(provider, client, 30.seconds)
    }
  }*/

  // test("providers should support tools call no streaming")
  LlmProviders.listOfProvidersSupportingTools.foreach { provider =>
    test(s"provider '${provider.name}' should support tools_call sync") {
      printHeader(provider.name, "tools_call sync")
      testToolsCallWith(provider, client, 30.seconds)
    }
  }

/*
  // test("providers should support tools call with streaming")
  LlmProviders.listOfProvidersSupportingToolsStream.foreach { provider =>
    test(s"provider '${provider.name}' should support tools_call stream") {
      printHeader(provider.name, "tools_call stream")
      testToolsCallStreamWith(provider, client, 30.seconds)
    }
  }*/

}

class ImageContentSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  test("provider openai should be able to handle image content in openai format") {
    val provider = LlmProviders.openai
    val token = sys.env(provider.envToken)
    val port = client.port
    val providerId = s"provider_${UUID.randomUUID().toString}"
    val routeChatId = s"route_${UUID.randomUUID().toString}"
    val awaitFor = 30.seconds

    val llmprovider = AiProvider(
      id = providerId,
      name = s"${provider.name} provider",
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
    )
    LlmProviderUtils.upsertProvider(client)(llmprovider)
    val routeChat = client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertRaw(routeChatId, Json.parse(
      s"""{
         |  "id": "${routeChatId}",
         |  "name": "openai",
         |  "frontend": {
         |    "domains": [
         |      "${provider.name}.oto.tools/chat_img"
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
         |}""".stripMargin)).awaitf(awaitFor)
    assert(routeChat.created, s"[${provider.name}] route chat has not been created")
    await(1300.millis)
    val resp = client.call("POST", s"http://${provider.name}.oto.tools:${port}/chat_img", Map.empty, Some(Json.parse(
      s"""{
         |  "model": "gpt-4o",
         |  "messages": [
         |    {
         |      "role": "user",
         |      "content": [
         |        {
         |          "type": "text",
         |          "text": "Whats in this image?"
         |        },
         |        {
         |          "type": "image_url",
         |          "image_url": {
         |            "url": "https://upload.wikimedia.org/wikipedia/commons/thumb/8/85/Orange_tabby_cat_sitting_on_fallen_leaves-Hisashi-01.jpg/398px-Orange_tabby_cat_sitting_on_fallen_leaves-Hisashi-01.jpg"
         |          }
         |        }
         |      ]
         |    }
         |  ],
         |  "max_tokens": 300
         |}""".stripMargin))).awaitf(awaitFor)
    if (resp.status != 200 && resp.status != 201) {
      println(resp.body)
    }
    // assertEquals(resp.status, 200, s"[${provider.name}] chat route did not respond with 200")
    val pointer = resp.json.at("choices.0.message.content")
    // assert(pointer.isDefined, s"[${provider.name}] no message content")
    // assert(pointer.get.asString.nonEmpty, s"[${provider.name}] no message")
    println(s"[${provider.name}] message: ${pointer.asString}")
    client.forLlmEntity("providers").deleteEntity(llmprovider)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").deleteRaw(routeChatId)
    await(1300.millis)
  }

  test("provider anthropic should be able to handle image content in anthropic format") {
    val provider = LlmProviders.anthropic
    val token = sys.env(provider.envToken)
    val port = client.port
    val providerId = s"provider_${UUID.randomUUID().toString}"
    val routeChatId = s"route_${UUID.randomUUID().toString}"
    val awaitFor = 30.seconds

    val llmprovider = AiProvider(
      id = providerId,
      name = s"${provider.name} provider",
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
    )
    LlmProviderUtils.upsertProvider(client)(llmprovider)
    val routeChat = client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertRaw(routeChatId, Json.parse(
      s"""{
         |  "id": "${routeChatId}",
         |  "name": "openai",
         |  "frontend": {
         |    "domains": [
         |      "${provider.name}.oto.tools/chat_img"
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
         |}""".stripMargin)).awaitf(awaitFor)
    assert(routeChat.created, s"[${provider.name}] route chat has not been created")
    await(1300.millis)
    val url = "https://upload.wikimedia.org/wikipedia/commons/thumb/8/85/Orange_tabby_cat_sitting_on_fallen_leaves-Hisashi-01.jpg/398px-Orange_tabby_cat_sitting_on_fallen_leaves-Hisashi-01.jpg"
    val imgResp = client.call("GET", url, Map.empty, None).awaitf(awaitFor)
    val base64 = imgResp.bodyAsBytes.encodeBase64.utf8String
    val resp = client.call("POST", s"http://${provider.name}.oto.tools:${port}/chat_img", Map.empty, Some(
      Json.obj(
        "model" -> "claude-3-5-sonnet-20241022",
        "max_tokens" -> 300,
        "messages" -> Json.arr(
          Json.obj(
            "role" -> "user",
            "content" -> Json.arr(
              Json.obj(
                "type" -> "text",
                "text" -> "Whats in this image?"
              ),
              Json.obj(
                "type" -> "image",
                "source" -> Json.obj(
                  "type" -> "base64",
                  "media_type" -> "image/jpeg",
                  "data" -> base64,
                )
              )
            )
          )
        )
      )
    )).awaitf(awaitFor)
    if (resp.status != 200 && resp.status != 201) {
      println(resp.body)
    }
    // assertEquals(resp.status, 200, s"[${provider.name}] chat route did not respond with 200")
    val pointer = resp.json.at("choices.0.message.content")
    // assert(pointer.isDefined, s"[${provider.name}] no message content")
    // assert(pointer.get.asString.nonEmpty, s"[${provider.name}] no message")
    println(s"[${provider.name}] message: ${pointer.asString}")
    client.forLlmEntity("providers").deleteEntity(llmprovider)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").deleteRaw(routeChatId)
    await(1300.millis)
  }

  test("provider anthropic should be able to handle image content in open format") {
    val provider = LlmProviders.anthropic
    val token = sys.env(provider.envToken)
    val port = client.port
    val providerId = s"provider_${UUID.randomUUID().toString}"
    val routeChatId = s"route_${UUID.randomUUID().toString}"
    val awaitFor = 30.seconds

    val llmprovider = AiProvider(
      id = providerId,
      name = s"${provider.name} provider",
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
    )
    LlmProviderUtils.upsertProvider(client)(llmprovider)
    val routeChat = client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertRaw(routeChatId, Json.parse(
      s"""{
         |  "id": "${routeChatId}",
         |  "name": "openai",
         |  "frontend": {
         |    "domains": [
         |      "${provider.name}.oto.tools/chat_img"
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
         |}""".stripMargin)).awaitf(awaitFor)
    assert(routeChat.created, s"[${provider.name}] route chat has not been created")
    await(1300.millis)
    val url = "https://upload.wikimedia.org/wikipedia/commons/thumb/8/85/Orange_tabby_cat_sitting_on_fallen_leaves-Hisashi-01.jpg/398px-Orange_tabby_cat_sitting_on_fallen_leaves-Hisashi-01.jpg"
    val imgResp = client.call("GET", url, Map.empty, None).awaitf(awaitFor)
    val base64 = imgResp.bodyAsBytes.encodeBase64.utf8String
    val resp = client.call("POST", s"http://${provider.name}.oto.tools:${port}/chat_img", Map.empty, Some(
      Json.obj(
        "model" -> "claude-3-5-sonnet-20241022",
        "max_tokens" -> 300,
        "messages" -> Json.arr(
          Json.obj(
            "role" -> "user",
            "content" -> Json.arr(
              Json.obj(
                "type" -> "text",
                "text" -> "Whats in this image?"
              ),
              Json.obj(
                "type" -> "image_url",
                "image_url" -> Json.obj(
                  "url" -> s"data:image/jpeg;base64,${base64}",
                )
              )
            )
          )
        )
      )
    )).awaitf(awaitFor)
    if (resp.status != 200 && resp.status != 201) {
      //println(resp.body)
    }
    assertEquals(resp.status, 200, s"[${provider.name}] chat route did not respond with 200")
    val pointer = resp.json.at("choices.0.message.content")
    assert(pointer.isDefined, s"[${provider.name}] no message content")
    assert(pointer.get.asString.nonEmpty, s"[${provider.name}] no message")
    assert(pointer.get.asString.contains("cat"), s"[${provider.name}] no cat")
    println(s"[${provider.name}] message: ${pointer.asString}")
    client.forLlmEntity("providers").deleteEntity(llmprovider)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").deleteRaw(routeChatId)
    await(1300.millis)
  }

}

class DocumentCitationSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  test("provider anthropic should be able to handle citations from documents") {
    val provider = LlmProviders.anthropic
    val token = sys.env(provider.envToken)
    val port = client.port
    val providerId = s"provider_${UUID.randomUUID().toString}"
    val routeChatId = s"route_${UUID.randomUUID().toString}"
    val awaitFor = 30.seconds

    val llmprovider = AiProvider(
      id = providerId,
      name = s"${provider.name} provider",
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
    )
    LlmProviderUtils.upsertProvider(client)(llmprovider)
    val routeChat = client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertRaw(routeChatId, Json.parse(
      s"""{
         |  "id": "${routeChatId}",
         |  "name": "openai",
         |  "frontend": {
         |    "domains": [
         |      "${provider.name}.oto.tools/chat_doc"
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
         |}""".stripMargin)).awaitf(awaitFor)
    assert(routeChat.created, s"[${provider.name}] route chat has not been created")
    await(1300.millis)
    val resp = client.call("POST", s"http://${provider.name}.oto.tools:${port}/chat_doc", Map.empty, Some(
      Json.obj(
        "model" -> "claude-3-5-sonnet-20241022",
        "max_tokens" -> 300,
        "messages" -> Json.arr(
          Json.obj(
            "role" -> "user",
            "content" -> Json.arr(
              Json.obj(
                "type" -> "document",
                "title" -> "My Document",
                "context" -> "This is a trustworthy document",
                "citations" -> Json.obj("enabled" -> true),
                "source" -> Json.obj(
                  "type" -> "text",
                  "media_type" -> "text/plain",
                  "data" -> "The grass is yellow. The sky is grey.",
                )
              ),
              Json.obj(
                "type" -> "text",
                "text" -> "What color is the grass and sky?"
              ),

            )
          )
        )
      )
    )).awaitf(awaitFor)
    //if (resp.status != 200 && resp.status != 201) {
      println(resp.json.prettify)
    //}
    // assertEquals(resp.status, 200, s"[${provider.name}] chat route did not respond with 200")
    val message = resp.json.at("choices").as[Seq[JsObject]].map(_.at("message.content").asString).mkString(" ")
    // assert(pointer.isDefined, s"[${provider.name}] no message content")
    assert(message.nonEmpty, s"[${provider.name}] no message")
    println(s"[${provider.name}] message: ${message}")
    client.forLlmEntity("providers").deleteEntity(llmprovider)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").deleteRaw(routeChatId)
    await(1300.millis)
  }

}

class ReasoningSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  test("should be able to access reasoning hints") {
    val provider = LlmProviders.deepseek
    val token = sys.env(provider.envToken)
    val port = client.port
    val providerId = s"provider_${UUID.randomUUID().toString}"
    val routeChatId = s"route_${UUID.randomUUID().toString}"
    val awaitFor = 70.seconds

    val llmprovider = AiProvider(
      id = providerId,
      name = s"${provider.name} provider",
      provider = provider.name,
      connection = Json.obj(
        "base_url" -> "https://api.deepseek.com",
        "token" -> token,
        "timeout" -> 60000
      ),
      options = (provider.options - "max_tokens"),
    )
    LlmProviderUtils.upsertProvider(client)(llmprovider)
    val routeChat = client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertRaw(routeChatId, Json.parse(
      s"""{
         |  "id": "${routeChatId}",
         |  "name": "openai",
         |  "frontend": {
         |    "domains": [
         |      "${provider.name}.oto.tools/chat_reason"
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
         |    },
         |    "client": {
         |      "call_timeout": 70000,
         |      "call_and_stream_timeout": 120000,
         |      "connection_timeout": 10000,
         |      "idle_timeout": 70000
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
         |}""".stripMargin)).awaitf(awaitFor)
    assert(routeChat.created, s"[${provider.name}] route chat has not been created")
    await(1300.millis)
    val resp = client.call("POST", s"http://${provider.name}.oto.tools:${port}/chat_reason", Map.empty, Some(
      Json.obj(
        "model" -> "deepseek-reasoner",
        "messages" -> Json.arr(
          Json.obj(
            "role" -> "user",
            "content" -> "9.11 and 9.8, which is greater?"
          )
        )
      )
    )).awaitf(awaitFor)
    if (resp.status != 200 && resp.status != 201) {
      println(resp.json.prettify)
    }
    assertEquals(resp.status, 200, s"[${provider.name}] chat route did not respond with 200")
    val reasoning = resp.json.at("choices.0.message.reasoning_details").asOptString
    assert(reasoning.isDefined, s"[${provider.name}] no reasoning content")
    assert(reasoning.get.nonEmpty, s"[${provider.name}] no reasoning")
    //println(s"[${provider.name}] message: ${message}")
    client.forLlmEntity("providers").deleteEntity(llmprovider)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").deleteRaw(routeChatId)
    await(1300.millis)
  }

}
