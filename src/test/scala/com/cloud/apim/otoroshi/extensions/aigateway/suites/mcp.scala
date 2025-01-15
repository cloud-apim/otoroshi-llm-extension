package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.LlmExtensionOneOtoroshiServerPerSuite
import com.cloud.apim.otoroshi.extensions.aigateway.entities._
import otoroshi.models.{EntityLocation, WasmPlugin}
import otoroshi.next.models._
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.{McpRespEndpoint, McpSseEndpoint, OpenAiCompatProxy}
import play.api.libs.json.Json

import java.util.UUID
import scala.concurrent.duration.DurationInt

class McpSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  test("llm provider can use an stdio mcp server") {
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  setup                                                         ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    val mcpConnector = McpConnector(
      id = UUID.randomUUID().toString,
      name = "test connector",
      transport = McpConnectorTransport(
        kind = McpConnectorTransportKind.Stdio,
        options = Json.obj(
          "command" -> "/Users/mathieuancelin/.nvm/versions/node/v18.19.0/bin/node",
          "args" -> Json.arr(
            "/Users/mathieuancelin/projects/clever-ai/mpc-test/mcp-otoroshi-proxy/src/test.js"
          )
        )
      )
    )
    val llmProvider = AiProvider(
      id = UUID.randomUUID().toString,
      name = "test provider",
      provider = "ollama",
      connection = Json.obj(
        "timeout" -> 30000
      ),
      options = Json.obj(
        "model" -> "llama3.2",
        "mcp_connectors" -> Json.arr(mcpConnector.id)
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
          "refs" -> Json.arr(llmProvider.id)
        ))
      )))
    )
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "mcp-connectors").upsertEntity(mcpConnector).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").upsertEntity(llmProvider).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(route).awaitf(10.seconds)
    await(2.seconds)
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  test                                                          ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    val res = client.call("POST", s"http://test.oto.tools:${port}/chat", Map.empty, Some(Json.obj(
      "messages" -> Json.arr(Json.obj(
        "role" -> "user",
        "content" -> "What movie is playing in the kitchen ?"
      ))
    ))).awaitf(30.seconds)
    println(s"resp: ${res.status} - ${res.body}")
    assert(res.status == 200, "status should be 200")
    assert(res.body.contains("Shawshank"))
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  teardown                                                      ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "mcp-connectors").deleteEntity(mcpConnector).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "provider").deleteEntity(llmProvider).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "route").deleteEntity(route).awaitf(10.seconds)
    await(2.seconds)
  }

  test("llm provider can use an sse mcp server".ignore) {}

  test("otoroshi can expose an mcp server using the sse transport") {
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  setup                                                         ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    val llmFunction = LlmToolFunction(
      id = UUID.randomUUID().toString,
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
      backend = LlmToolFunctionBackend(
        kind = LlmToolFunctionBackendKind.Http,
        options = LlmToolFunctionBackendOptions.Http(Json.obj(
          "method" -> "GET",
          "url" -> "https://demo-sandbox-01j301vp84vwf6xjah3k2tryqa.cloud-apim.dev/flight"
        ))
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
      frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath("test.oto.tools/sse")), stripPath = false),
      backend = NgBackend.empty.copy(targets = Seq(NgTarget.default)),
      plugins = NgPlugins(Seq(NgPluginInstance(
        plugin = s"cp:${classOf[McpSseEndpoint].getName}",
        config = NgPluginInstanceConfig(Json.obj(
          "refs" -> Json.arr(llmFunction.id)
        ))
      )))
    )
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "tool-functions").upsertEntity(llmFunction).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(route).awaitf(10.seconds)
    await(2.seconds)
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  test                                                          ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    var messages = Seq.empty[String]
    val resSseF = client.stream("GET", s"http://test.oto.tools:${port}/sse?sessionId=1", Map.empty, None, 10.seconds, (json) => {
      messages = messages :+ json
    })
    await(1.seconds)
    client.call("POST", s"http://test.oto.tools:${port}/sse?sessionId=1", Map.empty, Some(Json.obj(
      "jsonrpc" -> "2.0",
      "id" -> 0,
      "method" -> "initialize"
    ))).awaitf(30.seconds)
    client.call("POST", s"http://test.oto.tools:${port}/sse?sessionId=1", Map.empty, Some(Json.obj(
      "jsonrpc" -> "2.0",
      "id" -> 1,
      "method" -> "notifications/initialized"
    ))).awaitf(30.seconds)
    val resListTools = client.call("POST", s"http://test.oto.tools:${port}/sse?sessionId=1", Map.empty, Some(Json.obj(
      "jsonrpc" -> "2.0",
      "id" -> 2,
      "method" -> "tools/list"
    ))).awaitf(30.seconds)
    val resToolCall = client.call("POST", s"http://test.oto.tools:${port}/sse?sessionId=1", Map.empty, Some(Json.obj(
      "jsonrpc" -> "2.0",
      "id" -> 3,
      "method" -> "tools/call",
      "params" -> Json.obj(
        "name" -> "get_flight_times",
        "arguments" -> Json.obj(
          "departure" -> "LAX",
          "arrival" -> "CDG"
        )
      )
    ))).awaitf(30.seconds)
    client.call("POST", s"http://test.oto.tools:${port}/sse?sessionId=1", Map.empty, Some(Json.obj(
      "jsonrpc" -> "2.0",
      "id" -> 0,
      "method" -> "exit"
    ))).awaitf(30.seconds)
    assert(resListTools.status == 200, "status should be 200")
    assert(resListTools.body.contains("get_flight_times"))
    assert(resToolCall.status == 200, "status should be 200")
    assert(resToolCall.body.contains("13h"))
    resSseF.awaitf(10.seconds)
    assert(messages.size == 4, "there should be 4 messages")
    assert(messages.tail.tail.head.contains("get_flight_times"), "list should contains get_flight_times")
    assert(messages.last.contains("13h"), "last should contains 13h")
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  teardown                                                      ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "tool-functions").deleteEntity(llmFunction).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "route").deleteEntity(route).awaitf(10.seconds)
    await(2.seconds)
  }

  test("otoroshi can expose an mcp server using the websocket transport".ignore) {}

  test("otoroshi can expose an mcp server using the http transport") {
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  setup                                                         ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    val llmFunction = LlmToolFunction(
      id = UUID.randomUUID().toString,
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
      backend = LlmToolFunctionBackend(
        kind = LlmToolFunctionBackendKind.Http,
        options = LlmToolFunctionBackendOptions.Http(Json.obj(
          "method" -> "GET",
          "url" -> "https://demo-sandbox-01j301vp84vwf6xjah3k2tryqa.cloud-apim.dev/flight"
        ))
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
      frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath("test.oto.tools/rest")), stripPath = false),
      backend = NgBackend.empty.copy(targets = Seq(NgTarget.default)),
      plugins = NgPlugins(Seq(NgPluginInstance(
        plugin = s"cp:${classOf[McpRespEndpoint].getName}",
        config = NgPluginInstanceConfig(Json.obj(
          "refs" -> Json.arr(llmFunction.id)
        ))
      )))
    )
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "tool-functions").upsertEntity(llmFunction).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(route).awaitf(10.seconds)
    await(2.seconds)
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  test                                                          ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    client.call("POST", s"http://test.oto.tools:${port}/rest", Map.empty, Some(Json.obj(
      "jsonrpc" -> "2.0",
      "id" -> 0,
      "method" -> "initialize"
    ))).awaitf(30.seconds)
    client.call("POST", s"http://test.oto.tools:${port}/rest", Map.empty, Some(Json.obj(
      "jsonrpc" -> "2.0",
      "id" -> 1,
      "method" -> "notifications/initialized"
    ))).awaitf(30.seconds)
    val resListTools = client.call("POST", s"http://test.oto.tools:${port}/rest", Map.empty, Some(Json.obj(
      "jsonrpc" -> "2.0",
      "id" -> 2,
      "method" -> "tools/list"
    ))).awaitf(30.seconds)
    val resToolCall = client.call("POST", s"http://test.oto.tools:${port}/rest", Map.empty, Some(Json.obj(
      "jsonrpc" -> "2.0",
      "id" -> 3,
      "method" -> "tools/call",
      "params" -> Json.obj(
        "name" -> "get_flight_times",
        "arguments" -> Json.obj(
          "departure" -> "LAX",
          "arrival" -> "CDG"
        )
      )
    ))).awaitf(30.seconds)
    assert(resListTools.status == 200, "status should be 200")
    assert(resListTools.body.contains("get_flight_times"))
    assert(resToolCall.status == 200, "status should be 200")
    assert(resToolCall.body.contains("13h"))
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  teardown                                                      ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "tool-functions").deleteEntity(llmFunction).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "route").deleteEntity(route).awaitf(10.seconds)
    await(2.seconds)
  }

  test("otoroshi can expose an mcp server with wasm backed functions") {
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  setup                                                         ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    val llmFunction = LlmToolFunction(
      id = UUID.randomUUID().toString,
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
      backend = LlmToolFunctionBackend(
        kind = LlmToolFunctionBackendKind.QuickJs,
        options = LlmToolFunctionBackendOptions.QuickJs(
          """'inline module';
            |
            |exports.tool_call = function(args) {
            |  return JSON.stringify({ departure: "08:00 AM", arrival: "11:30 AM", duration: "13h" });
            |}""".stripMargin
        )
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
      frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath("test.oto.tools/rest")), stripPath = false),
      backend = NgBackend.empty.copy(targets = Seq(NgTarget.default)),
      plugins = NgPlugins(Seq(NgPluginInstance(
        plugin = s"cp:${classOf[McpRespEndpoint].getName}",
        config = NgPluginInstanceConfig(Json.obj(
          "refs" -> Json.arr(llmFunction.id)
        ))
      )))
    )
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "tool-functions").upsertEntity(llmFunction).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(route).awaitf(10.seconds)
    val payload = WasmPlugin(
      id = LlmToolFunction.wasmPluginId,
      name = "Otoroshi LLM Extension - tool call runtime",
      description = "This plugin provides the runtime for the wasm backed LLM tool calls",
      config = LlmToolFunction.wasmConfig
    ).json.stringify.byteString
    otoroshi.env.datastores.rawDataStore.set(s"otoroshi:wasm-plugins:${LlmToolFunction.wasmPluginId}", payload, None)(otoroshi.executionContext, otoroshi.env).awaitf(10.seconds)
    await(2.seconds)
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  test                                                          ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    client.call("POST", s"http://test.oto.tools:${port}/rest", Map.empty, Some(Json.obj(
      "jsonrpc" -> "2.0",
      "id" -> 0,
      "method" -> "initialize"
    ))).awaitf(30.seconds)
    client.call("POST", s"http://test.oto.tools:${port}/rest", Map.empty, Some(Json.obj(
      "jsonrpc" -> "2.0",
      "id" -> 1,
      "method" -> "notifications/initialized"
    ))).awaitf(30.seconds)
    val resListTools = client.call("POST", s"http://test.oto.tools:${port}/rest", Map.empty, Some(Json.obj(
      "jsonrpc" -> "2.0",
      "id" -> 2,
      "method" -> "tools/list"
    ))).awaitf(30.seconds)
    val resToolCall = client.call("POST", s"http://test.oto.tools:${port}/rest", Map.empty, Some(Json.obj(
      "jsonrpc" -> "2.0",
      "id" -> 3,
      "method" -> "tools/call",
      "params" -> Json.obj(
        "name" -> "get_flight_times",
        "arguments" -> Json.obj(
          "departure" -> "LAX",
          "arrival" -> "CDG"
        )
      )
    ))).awaitf(30.seconds)
    assert(resListTools.status == 200, "status should be 200")
    assert(resListTools.body.contains("get_flight_times"))
    assert(resToolCall.status == 200, "status should be 200")
    assert(resToolCall.body.contains("13h"))
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  teardown                                                      ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "tool-functions").deleteEntity(llmFunction).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "route").deleteEntity(route).awaitf(10.seconds)
    await(2.seconds)
  }

  test("otoroshi can expose an mcp server with mcp-server backed functions") {
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  setup                                                         ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    val mcpConnector = McpConnector(
      id = UUID.randomUUID().toString,
      name = "test connector",
      transport = McpConnectorTransport(
        kind = McpConnectorTransportKind.Stdio,
        options = Json.obj(
          "command" -> "/Users/mathieuancelin/.nvm/versions/node/v18.19.0/bin/node",
          "args" -> Json.arr(
            "/Users/mathieuancelin/projects/clever-ai/mpc-test/mcp-otoroshi-proxy/src/test.js"
          )
        )
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
      frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath("test.oto.tools/rest")), stripPath = false),
      backend = NgBackend.empty.copy(targets = Seq(NgTarget.default)),
      plugins = NgPlugins(Seq(NgPluginInstance(
        plugin = s"cp:${classOf[McpRespEndpoint].getName}",
        config = NgPluginInstanceConfig(Json.obj(
          "mcp_refs" -> Json.arr(mcpConnector.id)
        ))
      )))
    )
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "mcp-connectors").upsertEntity(mcpConnector).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(route).awaitf(10.seconds)
    val payload = WasmPlugin(
      id = LlmToolFunction.wasmPluginId,
      name = "Otoroshi LLM Extension - tool call runtime",
      description = "This plugin provides the runtime for the wasm backed LLM tool calls",
      config = LlmToolFunction.wasmConfig
    ).json.stringify.byteString
    otoroshi.env.datastores.rawDataStore.set(s"otoroshi:wasm-plugins:${LlmToolFunction.wasmPluginId}", payload, None)(otoroshi.executionContext, otoroshi.env).awaitf(10.seconds)
    await(2.seconds)
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  test                                                          ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    client.call("POST", s"http://test.oto.tools:${port}/rest", Map.empty, Some(Json.obj(
      "jsonrpc" -> "2.0",
      "id" -> 0,
      "method" -> "initialize"
    ))).awaitf(30.seconds)
    client.call("POST", s"http://test.oto.tools:${port}/rest", Map.empty, Some(Json.obj(
      "jsonrpc" -> "2.0",
      "id" -> 1,
      "method" -> "notifications/initialized"
    ))).awaitf(30.seconds)
    val resListTools = client.call("POST", s"http://test.oto.tools:${port}/rest", Map.empty, Some(Json.obj(
      "jsonrpc" -> "2.0",
      "id" -> 2,
      "method" -> "tools/list"
    ))).awaitf(30.seconds)
    val resToolCall = client.call("POST", s"http://test.oto.tools:${port}/rest", Map.empty, Some(Json.obj(
      "jsonrpc" -> "2.0",
      "id" -> 3,
      "method" -> "tools/call",
      "params" -> Json.obj(
        "name" -> "what_movie_played",
        "arguments" -> Json.obj(
          "room" -> "kitchen"
        )
      )
    ))).awaitf(30.seconds)
    println(s"${resListTools.status} - ${resListTools.body}")
    assert(resListTools.status == 200, "status should be 200")
    assert(resListTools.body.contains("what_movie_played"))
    assert(resToolCall.status == 200, "status should be 200")
    assert(resToolCall.body.contains("Shawshank"))
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  teardown                                                      ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "mcp-connectors").deleteEntity(mcpConnector).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "route").deleteEntity(route).awaitf(10.seconds)
    await(2.seconds)
  }

  test("an llm tool function can be backed by an http call") {
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  setup                                                         ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    val llmFunction = LlmToolFunction(
      id = UUID.randomUUID().toString,
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
      backend = LlmToolFunctionBackend(
        kind = LlmToolFunctionBackendKind.Http,
        options = LlmToolFunctionBackendOptions.Http(Json.obj(
          "method" -> "GET",
          "url" -> "https://demo-sandbox-01j301vp84vwf6xjah3k2tryqa.cloud-apim.dev/flight"
        ))
      )
    )
    val llmProvider = AiProvider(
      id = UUID.randomUUID().toString,
      name = "test provider",
      provider = "ollama",
      connection = Json.obj(
        "timeout" -> 30000
      ),
      options = Json.obj(
        "model" -> "llama3.2",
        "wasm_tools" -> Json.arr(llmFunction.id)
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
          "refs" -> Json.arr(llmProvider.id)
        ))
      )))
    )
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "tool-functions").upsertEntity(llmFunction).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").upsertEntity(llmProvider).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(route).awaitf(10.seconds)
    await(2.seconds)
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  test                                                          ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    val res = client.call("POST", s"http://test.oto.tools:${port}/chat", Map.empty, Some(Json.obj(
      "messages" -> Json.arr(Json.obj(
        "role" -> "user",
        "content" -> "how long is the flight between LAX and CDG ?"
      ))
    ))).awaitf(30.seconds)
    println(s"resp: ${res.status} - ${res.body}")
    assert(res.status == 200, "status should be 200")
    assert(res.body.contains("13"))
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  teardown                                                      ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "tool-functions").deleteEntity(llmFunction).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "provider").deleteEntity(llmProvider).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "route").deleteEntity(route).awaitf(10.seconds)
    await(2.seconds)
  }
}
