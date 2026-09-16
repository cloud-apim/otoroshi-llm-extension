package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.LlmExtensionOneOtoroshiServerPerSuite
import com.cloud.apim.otoroshi.extensions.aigateway.entities.*
import otoroshi.models.{EntityLocation, WasmPlugin}
import otoroshi.next.models.*
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.{AssistantMcpEndpoint, McpProxyEndpointConfig, McpRespEndpoint, McpSseEndpoint, McpWebsocketEndpoint, OpenAiCompatApi, OpenAiCompatProxy}
import play.api.libs.json.{JsNull, JsObject, Json}
import reactor.core.publisher.Mono

import java.io.File
import java.util.UUID
import scala.concurrent.duration.DurationInt

class McpSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  val (fakeApiServerPort, _) = createTestServerWithRoutes("httpapi", routes => routes.get("/flight", (_, response) => {
    response
      .status(200)
      .addHeader("Content-Type", "application/json")
      .sendString(Mono.just("{ departure: \"08:00 AM\", arrival: \"11:30 AM\", duration: \"13h\" }"))
  }))

  test("llm provider can use an stdio mcp server") {
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  setup                                                         ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    val mcpConnector = McpConnector(
      enabled = true,
      id = UUID.randomUUID().toString,
      name = "test connector",
      transport = McpConnectorTransport(
        kind = McpConnectorTransportKind.Stdio,
        options = Json.obj(
          "command" -> sys.env.getOrElse("NODE_EXEC", "/Users/mathieuancelin/.nvm/versions/node/v18.19.0/bin/node").json,
          "args" -> Json.arr(
            new File("./testserver/test.js").getAbsolutePath
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
        "content" -> "What is the movie currently playing in the kitchen ?"
      ))
    ))).awaitf(30.seconds)
    println(s"resp: ${res.status} - ${res.body}")
    assert(res.status == 200, "status should be 200")
    assert(res.body.contains("Shawshank"))
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  teardown                                                      ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "mcp-connectors").deleteEntity(mcpConnector).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").deleteEntity(llmProvider).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").deleteEntity(route).awaitf(10.seconds)
    await(2.seconds)
  }

  test("llm provider can use an sse mcp server") {
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  setup                                                         ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    val mcpConnectorRun = McpConnector(
      enabled = true,
      id = UUID.randomUUID().toString,
      name = "test connector run",
      transport = McpConnectorTransport(
        kind = McpConnectorTransportKind.Stdio,
        options = Json.obj(
          "command" -> sys.env.getOrElse("NODE_EXEC", "/Users/mathieuancelin/.nvm/versions/node/v18.19.0/bin/node").json,
          "args" -> Json.arr(
            new File("./testserver/test.js").getAbsolutePath
          )
        )
      )
    )
    val mcpConnector = McpConnector(
      enabled = true,
      id = UUID.randomUUID().toString,
      name = "test connector",
      transport = McpConnectorTransport(
        kind = McpConnectorTransportKind.Sse,
        options = Json.obj(
          "url" -> "http://localhost:3001/sse"
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
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "mcp-connectors").upsertEntity(mcpConnectorRun).awaitf(10.seconds)
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
        "content" -> "Can you add those two numbers: 23 + 22 ?"
      ))
    ))).awaitf(30.seconds)
    println(s"resp: ${res.status} - ${res.body}")
    assert(res.status == 200, "status should be 200")
    assert(res.body.contains("45"))
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  teardown                                                      ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "mcp-connectors").deleteEntity(mcpConnectorRun).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "mcp-connectors").deleteEntity(mcpConnector).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").deleteEntity(llmProvider).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").deleteEntity(route).awaitf(10.seconds)
    await(2.seconds)
  }

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
          "url" -> s"http://localhost:${fakeApiServerPort}/flight"
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
    client.forEntity("proxy.otoroshi.io", "v1", "routes").deleteEntity(route).awaitf(10.seconds)
    await(2.seconds)
  }

  test("otoroshi can expose an mcp server using the websocket transport") {
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
          "url" -> s"http://localhost:${fakeApiServerPort}/flight"
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
      frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath("test.oto.tools/ws")), stripPath = false),
      backend = NgBackend.empty.copy(targets = Seq(NgTarget.default)),
      plugins = NgPlugins(Seq(NgPluginInstance(
        plugin = s"cp:${classOf[McpWebsocketEndpoint].getName}",
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
    var messages = Seq.empty[JsObject]
    val (pushRef, cancel) = client.ws(s"ws://test.oto.tools:${port}/ws") { _ =>
      (message: String) => {
        messages = messages :+ message.parseJson.asObject
      }
    }
    await(2.seconds)
    pushRef.tryEmitNext(Json.obj(
      "jsonrpc" -> "2.0",
      "id" -> 0,
      "method" -> "initialize"
    ).stringify)
    pushRef.tryEmitNext(Json.obj(
      "jsonrpc" -> "2.0",
      "id" -> 1,
      "method" -> "notifications/initialized"
    ).stringify)
    pushRef.tryEmitNext(Json.obj(
      "jsonrpc" -> "2.0",
      "id" -> 2,
      "method" -> "tools/list"
    ).stringify)
    pushRef.tryEmitNext(Json.obj(
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
    ).stringify)
    await(2.seconds)
    pushRef.tryEmitNext(Json.obj(
      "jsonrpc" -> "2.0",
      "id" -> 4,
      "method" -> "exit"
    ).stringify)
    await(2.seconds)
    assertEquals(messages.size, 5, "there should be 5 messages")
    assert(messages(2).stringify.contains("get_flight_times"), "there should be a function called get_flight_times")
    assert(messages(3).stringify.contains("13"), "there should be a result containing 13")
    cancel.dispose()
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  teardown                                                      ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "tool-functions").deleteEntity(llmFunction).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").deleteEntity(route).awaitf(10.seconds)
    await(2.seconds)
  }

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
          "url" -> s"http://localhost:${fakeApiServerPort}/flight"
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
    client.forEntity("proxy.otoroshi.io", "v1", "routes").deleteEntity(route).awaitf(10.seconds)
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
    otoroshi.env.datastores.rawDataStore.set(s"otoroshi:wasm-plugins:${LlmToolFunction.wasmPluginId}", payload, None)(using otoroshi.executionContext, otoroshi.env).awaitf(10.seconds)
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
    client.forEntity("proxy.otoroshi.io", "v1", "routes").deleteEntity(route).awaitf(10.seconds)
    await(2.seconds)
  }

  test("otoroshi can expose an mcp server with mcp-server backed functions") {
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  setup                                                         ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    val mcpConnector = McpConnector(
      enabled = true,
      id = UUID.randomUUID().toString,
      name = "test connector",
      transport = McpConnectorTransport(
        kind = McpConnectorTransportKind.Stdio,
        options = Json.obj(
          "command" -> sys.env.getOrElse("NODE_EXEC", "/Users/mathieuancelin/.nvm/versions/node/v18.19.0/bin/node").json,
          "args" -> Json.arr(
            new File("./testserver/test.js").getAbsolutePath
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
    otoroshi.env.datastores.rawDataStore.set(s"otoroshi:wasm-plugins:${LlmToolFunction.wasmPluginId}", payload, None)(using otoroshi.executionContext, otoroshi.env).awaitf(10.seconds)
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
    // println(s"${resListTools.status} - ${resListTools.body}")
    assert(resListTools.status == 200, "status should be 200")
    assert(resListTools.body.contains("what_movie_played"))
    assert(resToolCall.status == 200, "status should be 200")
    assert(resToolCall.body.contains("Shawshank"))
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    /////////                                  teardown                                                      ///////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "mcp-connectors").deleteEntity(mcpConnector).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").deleteEntity(route).awaitf(10.seconds)
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
          "url" -> s"http://localhost:${fakeApiServerPort}/flight"
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
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").deleteEntity(llmProvider).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").deleteEntity(route).awaitf(10.seconds)
    await(2.seconds)
  }

  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  /////////                           MCP 2026-07-28 (stateless streamable http)                           ///////////
  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  private def flightFunction(): LlmToolFunction = LlmToolFunction(
    id = UUID.randomUUID().toString,
    name = "get_flight_times",
    description = "Get the flight times between two cities",
    parameters = Json.parse("""{
                              |  "departure": {
                              |    "type": "string",
                              |    "description": "The departure city (airport code)",
                              |    "x-mcp-header": "Departure"
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
        "url" -> s"http://localhost:${fakeApiServerPort}/flight"
      ))
    )
  )

  private def mcpHttpRoute(path: String, config: JsObject): NgRoute = NgRoute(
    location = EntityLocation.default,
    id = UUID.randomUUID().toString,
    name = s"test route $path",
    description = "test route",
    tags = Seq.empty,
    metadata = Map.empty,
    enabled = true,
    debugFlow = false,
    capture = false,
    exportReporting = false,
    frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath(s"test.oto.tools$path")), stripPath = false),
    backend = NgBackend.empty.copy(targets = Seq(NgTarget.default)),
    plugins = NgPlugins(Seq(NgPluginInstance(
      plugin = s"cp:${classOf[McpRespEndpoint].getName}",
      config = NgPluginInstanceConfig(config)
    )))
  )

  private val modernMeta = Json.obj(
    "io.modelcontextprotocol/protocolVersion" -> "2026-07-28",
    "io.modelcontextprotocol/clientInfo" -> Json.obj("name" -> "test", "version" -> "1.0.0"),
    "io.modelcontextprotocol/clientCapabilities" -> Json.obj(),
  )

  private def modernCall(url: String, id: Int, method: String, params: JsObject = Json.obj(), headers: Map[String, String] = Map.empty, meta: JsObject = modernMeta) = {
    val defaultHeaders = Map("MCP-Protocol-Version" -> "2026-07-28", "Mcp-Method" -> method)
    client.call("POST", url, defaultHeaders ++ headers, Some(Json.obj(
      "jsonrpc" -> "2.0",
      "id" -> id,
      "method" -> method,
      "params" -> (params ++ Json.obj("_meta" -> meta))
    ))).awaitf(30.seconds)
  }

  test("otoroshi can expose a stateless 2026-07-28 mcp server over streamable http") {
    val llmFunction = flightFunction()
    val route = mcpHttpRoute("/modern", Json.obj(
      "refs" -> Json.arr(llmFunction.id),
      "protocol_version" -> "2026-07-28",
      "cache_ttl_ms" -> 60000,
    ))
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "tool-functions").upsertEntity(llmFunction).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(route).awaitf(10.seconds)
    await(2.seconds)
    val url = s"http://test.oto.tools:${port}/modern"
    val callParams = Json.obj("name" -> "get_flight_times", "arguments" -> Json.obj("departure" -> "LAX", "arrival" -> "CDG"))
    val callHeaders = Map("Mcp-Name" -> "get_flight_times", "Mcp-Param-Departure" -> "LAX")

    // no standalone stream, no session termination
    assertEquals(client.call("GET", url, Map.empty, None).awaitf(30.seconds).status, 405)

    val discover = modernCall(url, 1, "server/discover")
    assertEquals(discover.status, 200, discover.body)
    assert((discover.json \ "result" \ "supportedVersions").as[Seq[String]].contains("2026-07-28"))
    assertEquals((discover.json \ "result" \ "resultType").as[String], "complete")
    assertEquals((discover.json \ "result" \ "ttlMs").as[Long], 60000L)
    assertEquals((discover.json \ "result" \ "cacheScope").as[String], "public")
    assert((discover.json \ "result" \ "_meta" \ "io.modelcontextprotocol/serverInfo" \ "name").asOpt[String].isDefined)

    val tools = modernCall(url, 2, "tools/list")
    assertEquals(tools.status, 200, tools.body)
    assertEquals((tools.json \ "result" \ "resultType").as[String], "complete")
    assert(tools.body.contains("get_flight_times"))

    val toolCall = modernCall(url, 3, "tools/call", callParams, callHeaders)
    assertEquals(toolCall.status, 200, toolCall.body)
    assertEquals((toolCall.json \ "result" \ "resultType").as[String], "complete")
    assert(toolCall.body.contains("13h"))

    // x-mcp-header mirrored parameter missing or not matching the body
    val missingParam = modernCall(url, 4, "tools/call", callParams, Map("Mcp-Name" -> "get_flight_times"))
    assertEquals(missingParam.status, 400, missingParam.body)
    assertEquals((missingParam.json \ "error" \ "code").as[Int], -32020)
    val wrongParam = modernCall(url, 5, "tools/call", callParams, Map("Mcp-Name" -> "get_flight_times", "Mcp-Param-Departure" -> "JFK"))
    assertEquals((wrongParam.json \ "error" \ "code").as[Int], -32020)
    val wrongName = modernCall(url, 6, "tools/call", callParams, Map("Mcp-Name" -> "other_tool", "Mcp-Param-Departure" -> "LAX"))
    assertEquals(wrongName.status, 400, wrongName.body)
    assertEquals((wrongName.json \ "error" \ "code").as[Int], -32020)

    // per-request metadata validation
    val missingVersionHeader = client.call("POST", url, Map("Mcp-Method" -> "tools/list"), Some(Json.obj(
      "jsonrpc" -> "2.0", "id" -> 7, "method" -> "tools/list", "params" -> Json.obj("_meta" -> modernMeta)
    ))).awaitf(30.seconds)
    assertEquals(missingVersionHeader.status, 400, missingVersionHeader.body)
    assertEquals((missingVersionHeader.json \ "error" \ "code").as[Int], -32020)
    val unsupported = modernCall(url, 8, "tools/list", headers = Map("MCP-Protocol-Version" -> "2027-01-01"), meta = modernMeta ++ Json.obj("io.modelcontextprotocol/protocolVersion" -> "2027-01-01"))
    assertEquals(unsupported.status, 400, unsupported.body)
    assertEquals((unsupported.json \ "error" \ "code").as[Int], -32022)
    assert((unsupported.json \ "error" \ "data" \ "supported").as[Seq[String]].contains("2026-07-28"))
    val missingCapabilities = modernCall(url, 9, "tools/list", meta = modernMeta - "io.modelcontextprotocol/clientCapabilities")
    assertEquals(missingCapabilities.status, 400, missingCapabilities.body)
    assertEquals((missingCapabilities.json \ "error" \ "code").as[Int], -32602)
    val ping = modernCall(url, 10, "ping")
    assertEquals(ping.status, 404, ping.body)
    assertEquals((ping.json \ "error" \ "code").as[Int], -32601)

    // notifications are accepted without body
    val notification = client.call("POST", url, Map("MCP-Protocol-Version" -> "2026-07-28", "Mcp-Method" -> "notifications/cancelled"), Some(Json.obj(
      "jsonrpc" -> "2.0", "method" -> "notifications/cancelled", "params" -> Json.obj("requestId" -> 3)
    ))).awaitf(30.seconds)
    assertEquals(notification.status, 202)

    // subscriptions/listen opens a long lived stream starting with the acknowledgment
    import play.api.libs.ws.JsonBodyWritables.writeableOf_JsValue
    val listen = client.client.url(url)
      .withMethod("POST")
      .withHttpHeaders("MCP-Protocol-Version" -> "2026-07-28", "Mcp-Method" -> "subscriptions/listen", "Content-Type" -> "application/json")
      .withBody(Json.obj("jsonrpc" -> "2.0", "id" -> 11, "method" -> "subscriptions/listen", "params" -> Json.obj("_meta" -> modernMeta, "notifications" -> Json.obj("toolsListChanged" -> true))): play.api.libs.json.JsValue)
      .stream()
      .awaitf(30.seconds)
    assertEquals(listen.status, 200)
    assert(listen.contentType.contains("text/event-stream"), listen.contentType)
    val firstEvent = listen.bodyAsSource.take(1).runWith(org.apache.pekko.stream.scaladsl.Sink.head).awaitf(30.seconds).utf8String
    assert(firstEvent.contains("notifications/subscriptions/acknowledged"), firstEvent)

    // legacy clients are still served on the same endpoint
    val initialize = client.call("POST", url, Map.empty, Some(Json.obj(
      "jsonrpc" -> "2.0", "id" -> 12, "method" -> "initialize", "params" -> Json.obj("protocolVersion" -> "2025-11-25", "capabilities" -> Json.obj(), "clientInfo" -> Json.obj("name" -> "legacy", "version" -> "1.0"))
    ))).awaitf(30.seconds)
    assertEquals(initialize.status, 200, initialize.body)
    assertEquals((initialize.json \ "result" \ "protocolVersion").as[String], "2025-11-25")
    val legacyTools = client.call("POST", url, Map("MCP-Protocol-Version" -> "2025-11-25"), Some(Json.obj(
      "jsonrpc" -> "2.0", "id" -> "string-id", "method" -> "tools/list"
    ))).awaitf(30.seconds)
    assertEquals(legacyTools.status, 200, legacyTools.body)
    assertEquals((legacyTools.json \ "id").as[String], "string-id")
    assert((legacyTools.json \ "result" \ "resultType").asOpt[String].isEmpty)
    assert(legacyTools.body.contains("get_flight_times"))

    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "tool-functions").deleteEntity(llmFunction).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").deleteEntity(route).awaitf(10.seconds)
    await(2.seconds)
  }

  test("a 2025-11-25 mcp exposition rejects stateless requests with a non modern error") {
    val llmFunction = flightFunction()
    val route = mcpHttpRoute("/legacy", Json.obj("refs" -> Json.arr(llmFunction.id)))
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "tool-functions").upsertEntity(llmFunction).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(route).awaitf(10.seconds)
    await(2.seconds)
    val url = s"http://test.oto.tools:${port}/legacy"
    val res = modernCall(url, 1, "tools/list")
    assertEquals(res.status, 400, res.body)
    assertEquals((res.json \ "error" \ "code").as[Int], -32600)
    val unknown = client.call("POST", url, Map.empty, Some(Json.obj("jsonrpc" -> "2.0", "id" -> 2, "method" -> "tools/call", "params" -> Json.obj("name" -> "nope")))).awaitf(30.seconds)
    assertEquals((unknown.json \ "error" \ "code").as[Int], -32602)
    val templates = client.call("POST", url, Map.empty, Some(Json.obj("jsonrpc" -> "2.0", "id" -> 3, "method" -> "resources/templates/list"))).awaitf(30.seconds)
    assert((templates.json \ "result" \ "resourceTemplates").asOpt[Seq[JsObject]].isDefined, templates.body)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "tool-functions").deleteEntity(llmFunction).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").deleteEntity(route).awaitf(10.seconds)
    await(2.seconds)
  }

  test("an http_2026_07_28 mcp connector can consume a stateless mcp server") {
    val llmFunction = flightFunction()
    val upstream = mcpHttpRoute("/upstream", Json.obj(
      "refs" -> Json.arr(llmFunction.id),
      "protocol_version" -> "2026-07-28",
    ))
    val mcpConnector = McpConnector(
      enabled = true,
      id = UUID.randomUUID().toString,
      name = "stateless connector",
      transport = McpConnectorTransport(
        kind = McpConnectorTransportKind.Http20260728,
        options = Json.obj("url" -> s"http://test.oto.tools:${port}/upstream")
      )
    )
    val aggregate = mcpHttpRoute("/aggregate", Json.obj("mcp_refs" -> Json.arr(mcpConnector.id)))
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "tool-functions").upsertEntity(llmFunction).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "mcp-connectors").upsertEntity(mcpConnector).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(upstream).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(aggregate).awaitf(10.seconds)
    await(2.seconds)
    val url = s"http://test.oto.tools:${port}/aggregate"
    val resListTools = client.call("POST", url, Map.empty, Some(Json.obj(
      "jsonrpc" -> "2.0", "id" -> 1, "method" -> "tools/list"
    ))).awaitf(30.seconds)
    assertEquals(resListTools.status, 200, resListTools.body)
    assert(resListTools.body.contains("get_flight_times"), resListTools.body)
    // the upstream validates the Mcp-Param-Departure header derived from the x-mcp-header annotation
    val resToolCall = client.call("POST", url, Map.empty, Some(Json.obj(
      "jsonrpc" -> "2.0", "id" -> 2, "method" -> "tools/call",
      "params" -> Json.obj("name" -> "get_flight_times", "arguments" -> Json.obj("departure" -> "LAX", "arrival" -> "CDG"))
    ))).awaitf(30.seconds)
    assertEquals(resToolCall.status, 200, resToolCall.body)
    assert(resToolCall.body.contains("13h"), resToolCall.body)
    assert((resToolCall.json \ "result" \ "resultType").asOpt[String].isEmpty, resToolCall.body)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").deleteEntity(aggregate).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").deleteEntity(upstream).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "mcp-connectors").deleteEntity(mcpConnector).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "tool-functions").deleteEntity(llmFunction).awaitf(10.seconds)
    await(2.seconds)
  }

  test("the assistant mcp endpoint can be exposed as a stateless 2026-07-28 mcp server") {
    val route = mcpHttpRoute("/assistant", Json.obj()).copy(plugins = NgPlugins(Seq(NgPluginInstance(
      plugin = s"cp:${classOf[AssistantMcpEndpoint].getName}",
      config = NgPluginInstanceConfig(Json.obj("protocol_version" -> "2026-07-28", "cache_ttl_ms" -> 10000))
    ))))
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(route).awaitf(10.seconds)
    await(2.seconds)
    val url = s"http://test.oto.tools:${port}/assistant"

    val discover = modernCall(url, 1, "server/discover")
    assertEquals(discover.status, 200, discover.body)
    assertEquals((discover.json \ "result" \ "capabilities").as[JsObject], Json.obj("tools" -> Json.obj()))
    assertEquals((discover.json \ "result" \ "ttlMs").as[Long], 10000L)
    assertEquals((discover.json \ "result" \ "_meta" \ "io.modelcontextprotocol/serverInfo" \ "name").as[String], "otoroshi-assistant-mcp")

    val tools = modernCall(url, 2, "tools/list")
    assertEquals(tools.status, 200, tools.body)
    assertEquals((tools.json \ "result" \ "resultType").as[String], "complete")
    assert((tools.json \ "result" \ "tools").as[Seq[JsObject]].nonEmpty, tools.body)

    val unknown = modernCall(url, 3, "tools/call", Json.obj("name" -> "nope", "arguments" -> Json.obj()), Map("Mcp-Name" -> "nope"))
    assertEquals(unknown.status, 400, unknown.body)
    assertEquals((unknown.json \ "error" \ "code").as[Int], -32602)

    val initialize = client.call("POST", url, Map.empty, Some(Json.obj(
      "jsonrpc" -> "2.0", "id" -> 4, "method" -> "initialize", "params" -> Json.obj("protocolVersion" -> "2025-06-18")
    ))).awaitf(30.seconds)
    assertEquals(initialize.status, 200, initialize.body)
    assertEquals((initialize.json \ "result" \ "protocolVersion").as[String], "2025-06-18")

    client.forEntity("proxy.otoroshi.io", "v1", "routes").deleteEntity(route).awaitf(10.seconds)
    await(2.seconds)
  }

  test("the unified llm api serves the virtual server it references on /mcp") {
    val llmFunction = flightFunction()
    val server = McpVirtualServer(
      id = UUID.randomUUID().toString,
      name = "studio server",
      config = McpProxyEndpointConfig.default.copy(name = "studio-server".some, functionRefs = Seq(llmFunction.id)),
    )
    def unifiedRoute(ref: Option[String]) = mcpHttpRoute("/unified", Json.obj()).copy(
      id = "unified-mcp-route",
      plugins = NgPlugins(Seq(NgPluginInstance(
        plugin = s"cp:${classOf[OpenAiCompatApi].getName}",
        config = NgPluginInstanceConfig(Json.obj("mcp_server_ref" -> ref.map(_.json).getOrElse(JsNull).asValue))
      )))
    )
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "tool-functions").upsertEntity(llmFunction).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "mcp-virtual-servers").upsertEntity(server).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(unifiedRoute(server.id.some)).awaitf(10.seconds)
    await(2.seconds)
    val url = s"http://test.oto.tools:${port}/unified/mcp"

    val initialize = client.call("POST", url, Map.empty, Some(Json.obj(
      "jsonrpc" -> "2.0", "id" -> 1, "method" -> "initialize", "params" -> Json.obj("protocolVersion" -> "2025-06-18")
    ))).awaitf(30.seconds)
    assertEquals(initialize.status, 200, initialize.body)
    assertEquals((initialize.json \ "result" \ "serverInfo" \ "name").as[String], "studio-server")

    val tools = client.call("POST", url, Map.empty, Some(Json.obj("jsonrpc" -> "2.0", "id" -> 2, "method" -> "tools/list"))).awaitf(30.seconds)
    assertEquals(tools.status, 200, tools.body)
    assert(tools.body.contains("get_flight_times"), tools.body)

    val call = client.call("POST", url, Map.empty, Some(Json.obj("jsonrpc" -> "2.0", "id" -> 3, "method" -> "tools/call",
      "params" -> Json.obj("name" -> "get_flight_times", "arguments" -> Json.obj("departure" -> "LAX", "arrival" -> "CDG"))))).awaitf(30.seconds)
    assert(call.body.contains("13h"), call.body)

    // the streamable http server answers the probes itself, rather than the plugin's generic 404
    assertEquals(client.call("GET", url, Map.empty, None).awaitf(30.seconds).status, 405)
    val unknownPath = client.call("POST", s"http://test.oto.tools:${port}/unified/nope", Map.empty, Some(Json.obj())).awaitf(30.seconds)
    assertEquals(unknownPath.status, 404, unknownPath.body)

    // a disabled server is no server
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "mcp-virtual-servers").upsertEntity(server.copy(enabled = false)).awaitf(10.seconds)
    await(2.seconds)
    val disabled = client.call("POST", url, Map.empty, Some(Json.obj("jsonrpc" -> "2.0", "id" -> 4, "method" -> "tools/list"))).awaitf(30.seconds)
    assertEquals(disabled.status, 404, disabled.body)

    // and neither is a route that references none
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(unifiedRoute(None)).awaitf(10.seconds)
    await(2.seconds)
    val noRef = client.call("POST", url, Map.empty, Some(Json.obj("jsonrpc" -> "2.0", "id" -> 5, "method" -> "tools/list"))).awaitf(30.seconds)
    assertEquals(noRef.status, 404, noRef.body)

    client.forEntity("proxy.otoroshi.io", "v1", "routes").deleteEntity(unifiedRoute(None)).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "mcp-virtual-servers").deleteEntity(server).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "tool-functions").deleteEntity(llmFunction).awaitf(10.seconds)
    await(2.seconds)
  }
}
