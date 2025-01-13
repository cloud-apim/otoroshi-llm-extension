package com.cloud.apim.otoroshi.extensions.aigateway.suites

import akka.stream.Materializer
import com.cloud.apim.otoroshi.extensions.aigateway.{LLmExtensionSuite, LlmProviders, OtoroshiClient, TestLlmProviderSettings}
import com.cloud.apim.otoroshi.extensions.aigateway.domains.LlmProviderUtils
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiProvider, WasmFunction}
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
        "max_tokens" -> 128,
        "wasm_tools" -> Json.arr(functionId)
      ).applyOnIf(provider.name == "ollama") { obj =>
        obj ++ Json.obj("num_predict" -> 128)
      },
    )
    LlmProviderUtils.upsertProvider(client)(llmprovider)
    val function = WasmFunction(
      id = functionId,
      name = "get_flight_times",
      description = "Get the flight times between two cities",
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
      jsPath = """'inline module';
                 |
                 |exports.tool_call = function(args) {
                 |  return JSON.stringify({ departure: "08:00 AM", arrival: "11:30 AM", duration: "5h 30m" });
                 |}""".stripMargin.some,
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
      id = WasmFunction.wasmPluginId,
      name = "Otoroshi LLM Extension - tool call runtime",
      description = "This plugin provides the runtime for the wasm backed LLM tool calls",
      config = WasmFunction.wasmConfig
    ).json.stringify.byteString
    otoroshi.env.datastores.rawDataStore.set(s"otoroshi:wasm-plugins:${WasmFunction.wasmPluginId}", payload, None)(otoroshi.executionContext, otoroshi.env).awaitf(10.seconds)
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
        "max_tokens" -> 128,
        "wasm_tools" -> Json.arr(functionId)
      ).applyOnIf(provider.name == "ollama") { obj =>
        obj ++ Json.obj("num_predict" -> 128)
      },
    )
    LlmProviderUtils.upsertProvider(client)(llmprovider)
    val function = WasmFunction(
      id = functionId,
      name = "get_flight_times",
      description = "Get the flight times between two cities",
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
      jsPath = """'inline module';
                 |
                 |exports.tool_call = function(args) {
                 |  return JSON.stringify({ departure: "08:00 AM", arrival: "11:30 AM", duration: "5h 30m" });
                 |}""".stripMargin.some,
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
      id = WasmFunction.wasmPluginId,
      name = "Otoroshi LLM Extension - tool call runtime",
      description = "This plugin provides the runtime for the wasm backed LLM tool calls",
      config = WasmFunction.wasmConfig
    ).json.stringify.byteString
    otoroshi.env.datastores.rawDataStore.set(s"otoroshi:wasm-plugins:${WasmFunction.wasmPluginId}", payload, None)(otoroshi.executionContext, otoroshi.env).awaitf(10.seconds)
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
      testChatCompletionWith(provider, client, 30.seconds)
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
  }

  // test("providers should support tools call no streaming")
  LlmProviders.listOfProvidersSupportingTools.foreach { provider =>
    test(s"provider '${provider.name}' should support tools_call sync") {
      printHeader(provider.name, "tools_call sync")
      testToolsCallWith(provider, client, 30.seconds)
    }
  }

  // test("providers should support tools call with streaming")
  LlmProviders.listOfProvidersSupportingToolsStream.foreach { provider =>
    test(s"provider '${provider.name}' should support tools_call stream") {
      printHeader(provider.name, "tools_call stream")
      testToolsCallStreamWith(provider, client, 30.seconds)
    }
  }
}
