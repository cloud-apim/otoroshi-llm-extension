package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.ChatMessageContent
import com.cloud.apim.otoroshi.extensions.aigateway.a2a._
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{A2AConnector, A2AConnectorAuth, A2AServer, A2AServerBackend, A2AServerCard, A2ASkillConfig, AnthropicApiResponseChoiceMessageToolCall, GenericApiResponseChoiceMessageToolCall}
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.A2ASupportServer
import play.api.libs.json._

// Pure unit tests for the A2A v1.0 wire models (no running server required).
class A2ASuite extends munit.FunSuite {

  test("TextPart serializes as a member (no `kind`) with mediaType") {
    val js = A2APart.ofText("hello").json.as[JsObject]
    assertEquals((js \ "text").as[String], "hello")
    assertEquals((js \ "mediaType").as[String], "text/plain")
    assert(!js.keys.contains("kind"), "v1.0 parts must not carry a `kind` discriminator")
    assert(!js.keys.contains("type"), "v1.0 parts must not carry a `type` discriminator")
  }

  test("Part discrimination is by member presence (text|url|raw|data)") {
    val text = A2APart.format.reads(Json.obj("text" -> "hi")).get
    val file = A2APart.format.reads(Json.obj("url" -> "https://x/y.pdf", "mediaType" -> "application/pdf")).get
    val raw  = A2APart.format.reads(Json.obj("raw" -> "QQ==", "mediaType" -> "image/png")).get
    val data = A2APart.format.reads(Json.obj("data" -> Json.obj("k" -> "v"))).get
    assert(text.isText && !text.isFile && !text.isData)
    assert(file.isFile && file.url.contains("https://x/y.pdf"))
    assert(raw.isFile && raw.raw.contains("QQ=="))
    assert(data.isData && data.data.contains(Json.obj("k" -> "v")))
  }

  test("legacy mimeType is read into mediaType (0.3 backward read)") {
    val p = A2APart.format.reads(Json.obj("url" -> "https://x", "mimeType" -> "application/pdf")).get
    assertEquals(p.mediaType, Some("application/pdf"))
  }

  test("Message uses ROLE_* enum and round-trips") {
    val msg = A2AMessage(messageId = "m1", role = A2ARole.User, parts = Seq(A2APart.ofText("yo")), contextId = Some("ctx1"))
    val js = msg.json.as[JsObject]
    assertEquals((js \ "role").as[String], "ROLE_USER")
    assert(!js.keys.contains("kind"))
    val back = A2AMessage.format.reads(js).get
    assertEquals(back.role, A2ARole.User)
    assertEquals(back.textContent, "yo")
    assertEquals(back.contextId, Some("ctx1"))
  }

  test("Message reads legacy lowercase role (compat)") {
    val back = A2AMessage.format.reads(Json.obj("messageId" -> "m", "role" -> "user", "parts" -> Json.arr(Json.obj("text" -> "x")))).get
    assertEquals(back.role, A2ARole.User)
  }

  test("TaskState wire values are SCREAMING_SNAKE_CASE and terminal flags are correct") {
    assertEquals(TaskState.Completed.wire, "TASK_STATE_COMPLETED")
    assert(TaskState.Completed.isTerminal)
    assert(TaskState.Failed.isTerminal)
    assert(TaskState.InputRequired.isInterrupted)
    assert(!TaskState.Working.isTerminal)
    assertEquals(TaskState("TASK_STATE_WORKING"), TaskState.Working)
    // legacy kebab-case read
    assertEquals(TaskState("completed"), TaskState.Completed)
  }

  test("Task round-trips with completed status and agent message") {
    val agent = A2AMessage.agentText("done", Some("ctx"), Some("task"))
    val task = A2ATask("task", "ctx", TaskStatus(TaskState.Completed, Some(agent), Some("2026-03-12T10:00:00.000Z")), history = Seq(agent))
    val js = task.json.as[JsObject]
    assertEquals((js \ "status" \ "state").as[String], "TASK_STATE_COMPLETED")
    assert(!js.keys.contains("kind"))
    val back = A2ATask.format.reads(js).get
    assertEquals(back.id, "task")
    assertEquals(back.status.state, TaskState.Completed)
    assertEquals(back.history.size, 1)
  }

  test("StreamResponse encapsulates by member and parses back") {
    val ev = TaskStatusUpdateEvent("t", "c", TaskStatus(TaskState.Working))
    val result = StreamResponse.OfStatusUpdate(ev).resultJson
    assert(result.keys.contains("statusUpdate"))
    assert(!result.keys.contains("kind"))
    StreamResponse.fromResult(result) match {
      case Some(StreamResponse.OfStatusUpdate(e)) =>
        assertEquals(e.taskId, "t")
        assertEquals(e.status.state, TaskState.Working)
      case other => fail(s"expected OfStatusUpdate, got $other")
    }
  }

  test("SendMessageResponse encapsulates the task by member") {
    val task = A2ATask("t", "c", TaskStatus(TaskState.Completed))
    val rpc = A2AJsonRpc.ok(JsNumber(1), Json.obj("task" -> task.json))
    assertEquals((rpc \ "jsonrpc").as[String], "2.0")
    assertEquals((rpc \ "result" \ "task" \ "id").as[String], "t")
  }

  test("error envelope carries google.rpc.ErrorInfo") {
    val rpc = A2AJsonRpc.err(JsNumber(1), A2AErrors.TaskNotFound, "task not found", Some(A2AErrors.errorInfo("TASK_NOT_FOUND", Json.obj("taskId" -> "x"))))
    assertEquals((rpc \ "error" \ "code").as[Int], -32001)
    val info = (rpc \ "error" \ "data").as[JsArray].value.head
    assertEquals((info \ "reason").as[String], "TASK_NOT_FOUND")
    assertEquals((info \ "domain").as[String], "a2a-protocol.org")
    assertEquals((info \ "@type").as[String], "type.googleapis.com/google.rpc.ErrorInfo")
  }

  test("AgentCard generated from A2AServer uses supportedInterfaces with protocolVersion 1.0") {
    val server = A2AServer(
      id = "a2a-server_test",
      name = "Math Tutor",
      description = "Helps with math",
      agentCard = A2AServerCard(skills = Seq(A2ASkillConfig("math", "Math", "Math help", tags = Seq("math")))),
      backend = A2AServerBackend(kind = "agent", agent = Some(Json.obj("name" -> "a", "instructions" -> Json.arr("x"), "provider" -> "p"))),
    )
    val card = server.toAgentCard("https://otoroshi.example.com/math").json.as[JsObject]
    assert(!card.keys.contains("url"), "v1.0 AgentCard has no top-level url")
    assert(!card.keys.contains("preferredTransport"), "v1.0 dropped preferredTransport")
    val ifaces = (card \ "supportedInterfaces").as[JsArray].value
    assertEquals(ifaces.size, 1)
    assertEquals((ifaces.head \ "url").as[String], "https://otoroshi.example.com/math")
    assertEquals((ifaces.head \ "protocolBinding").as[String], "JSONRPC")
    assertEquals((ifaces.head \ "protocolVersion").as[String], "1.0")
    assertEquals((card \ "skills").as[JsArray].value.size, 1)
    assertEquals((card \ "capabilities" \ "extendedAgentCard").as[Boolean], false)
  }

  test("partsToContent maps each A2A part variant to the right ChatMessageContent") {
    val parts = Seq(
      A2APart.ofText("hello"),
      A2APart(url = Some("https://x/y.png"), mediaType = Some("image/png")),
      A2APart(raw = Some("QQ=="), mediaType = Some("image/png")),
      A2APart(url = Some("https://x/d.pdf"), mediaType = Some("application/pdf")),
      A2APart(data = Some(Json.obj("k" -> "v"))),
    )
    val content = A2ASupportServer.partsToContent(parts)
    assertEquals(content.size, 5)
    content(0) match { case ChatMessageContent.TextContent(t) => assertEquals(t, "hello"); case o => fail(s"expected TextContent, got $o") }
    content(1) match { case ChatMessageContent.ImageContent(mt, url, data) => assertEquals(mt, "image/png"); assertEquals(url, Some("https://x/y.png")); assert(data.isEmpty); case o => fail(s"expected ImageContent(url), got $o") }
    content(2) match { case ChatMessageContent.ImageContent(mt, url, data) => assertEquals(mt, "image/png"); assert(url.isEmpty); assert(data.isDefined); case o => fail(s"expected ImageContent(raw), got $o") }
    content(3) match { case ChatMessageContent.PdfFileContent(url, _, _, _, _) => assertEquals(url, Some("https://x/d.pdf")); case o => fail(s"expected PdfFileContent, got $o") }
    content(4) match { case ChatMessageContent.TextContent(t) => assert(t.contains("\"k\"")); case o => fail(s"expected TextContent(json), got $o") }
  }

  test("buildAgentInput produces one user message with the mapped parts") {
    val msg = A2AMessage("m", A2ARole.User, Seq(A2APart.ofText("a"), A2APart(url = Some("https://x/y.png"), mediaType = Some("image/png"))))
    val input = A2ASupportServer.buildAgentInput(msg)
    assertEquals(input.messages.size, 1)
    assertEquals(input.messages.head.role, "user")
    assertEquals(input.messages.head.contentParts.size, 2)
  }

  test("buildAgentInput falls back to textContent when no mappable parts") {
    val msg = A2AMessage("m", A2ARole.User, Seq(A2APart.ofText("just text")))
    val input = A2ASupportServer.buildAgentInput(msg)
    input.messages.head.contentParts.head match {
      case ChatMessageContent.TextContent(t) => assertEquals(t, "just text")
      case o => fail(s"expected TextContent, got $o")
    }
  }

  test("artifactUpdate stream event encapsulates by member without kind") {
    val ev = TaskArtifactUpdateEvent("t", "c", Artifact("art-1", Seq(A2APart.ofText("chunk"))), append = true, lastChunk = false)
    val result = StreamResponse.OfArtifactUpdate(ev).resultJson
    assert(result.keys.contains("artifactUpdate"))
    assert(!result.keys.contains("kind"))
    assertEquals((result \ "artifactUpdate" \ "append").as[Boolean], true)
    assertEquals((result \ "artifactUpdate" \ "artifact" \ "parts").as[JsArray].value.size, 1)
    // round-trips back through fromResult
    StreamResponse.fromResult(result) match {
      case Some(StreamResponse.OfArtifactUpdate(e)) => assertEquals(e.taskId, "t"); assert(e.append)
      case other => fail(s"expected OfArtifactUpdate, got $other")
    }
  }

  test("A2A tool-call is classified by the a2a___ prefix (idx + skill id)") {
    val tc = GenericApiResponseChoiceMessageToolCall(Json.obj("id" -> "c1", "function" -> Json.obj("name" -> "a2a___2___route-optimizer", "arguments" -> Json.obj("message" -> "hi"))))
    assert(tc.isA2A)
    assert(!tc.isMcp && !tc.isWasm && !tc.isSearchEngine)
    assertEquals(tc.function.a2aConnectorId, 2)
    assertEquals(tc.function.a2aFunctionName, "route-optimizer")
  }

  test("A2A skill id containing ___ is preserved in a2aFunctionName") {
    val tc = GenericApiResponseChoiceMessageToolCall(Json.obj("function" -> Json.obj("name" -> "a2a___0___weird___skill")))
    assertEquals(tc.function.a2aConnectorId, 0)
    assertEquals(tc.function.a2aFunctionName, "weird___skill")
  }

  test("Anthropic A2A tool-call classification") {
    val tc = AnthropicApiResponseChoiceMessageToolCall(Json.obj("id" -> "t1", "name" -> "a2a___1___math", "input" -> Json.obj("message" -> "2+2")))
    assert(tc.isA2A)
    assertEquals(tc.a2aConnectorId, 1)
    assertEquals(tc.a2aFunctionName, "math")
    assertEquals(tc.arguments, Json.stringify(Json.obj("message" -> "2+2")))
  }

  test("mcp___ tool-call is NOT classified as A2A") {
    val tc = GenericApiResponseChoiceMessageToolCall(Json.obj("function" -> Json.obj("name" -> "mcp___0___do_thing")))
    assert(tc.isMcp)
    assert(!tc.isA2A)
  }

  test("A2AConnectorAuth.toHeaders builds the right headers per kind") {
    assertEquals(A2AConnectorAuth(kind = "bearer", token = Some("sk-1")).toHeaders, Seq("Authorization" -> "Bearer sk-1"))
    assertEquals(A2AConnectorAuth(kind = "apikey", headerName = Some("X-Api-Key"), value = Some("v")).toHeaders, Seq("X-Api-Key" -> "v"))
    assertEquals(A2AConnectorAuth(kind = "custom_headers", headers = Map("H" -> "V")).toHeaders, Seq("H" -> "V"))
    assertEquals(A2AConnectorAuth(kind = "none").toHeaders, Seq.empty)
    val basic = A2AConnectorAuth(kind = "basic", username = Some("u"), password = Some("p")).toHeaders
    assertEquals(basic.head._1, "Authorization")
    assert(basic.head._2.startsWith("Basic "))
  }

  test("A2AConnector entity JSON round-trips (auth + tls + paths)") {
    val conn = A2AConnector(
      id = "a2a-connector_test",
      name = "Remote planner",
      description = "d",
      url = "https://remote.example.com",
      authentication = A2AConnectorAuth(kind = "bearer", token = Some("sk-xxx")),
      skillsFilter = Seq("plan"),
    )
    val back = A2AConnector.format.reads(conn.json).get
    assertEquals(back.id, "a2a-connector_test")
    assertEquals(back.url, "https://remote.example.com")
    assertEquals(back.agentCardPath, "/.well-known/agent-card.json")
    assertEquals(back.agentCardFallbackPath, "/.well-known/agent.json")
    assertEquals(back.authentication.kind, "bearer")
    assertEquals(back.authentication.token, Some("sk-xxx"))
    assertEquals(back.skillsFilter, Seq("plan"))
  }

  test("A2APushConfig round-trips and AuthenticationInfo builds an Authorization header") {
    val cfg = A2APushConfig(id = "p1", taskId = "t1", url = "https://hook.example.com", token = Some("tok"), authentication = Some(AuthenticationInfo("Bearer", Some("sek"))))
    val back = A2APushConfig.from(cfg.json)
    assertEquals(back.id, "p1")
    assertEquals(back.taskId, "t1")
    assertEquals(back.url, "https://hook.example.com")
    assertEquals(back.token, Some("tok"))
    assertEquals(back.authentication.flatMap(_.header), Some("Authorization" -> "Bearer sek"))
  }

  test("push webhook payload is a StreamResponse {task} (no kind)") {
    val task = A2ATask("t", "c", TaskStatus(TaskState.Completed))
    val payload = StreamResponse.OfTask(task).resultJson
    assert(payload.keys.contains("task"))
    assert(!payload.keys.contains("kind"))
    assertEquals((payload \ "task" \ "id").as[String], "t")
  }

  test("A2AConnectorAuth oauth2_client_credentials round-trips") {
    val auth = A2AConnectorAuth(kind = "oauth2_client_credentials", tokenUrl = Some("https://idp/token"), clientId = Some("cid"), clientSecret = Some("sec"), scope = Some("a2a"))
    val back = A2AConnectorAuth.from(auth.json)
    assertEquals(back.kind, "oauth2_client_credentials")
    assertEquals(back.tokenUrl, Some("https://idp/token"))
    assertEquals(back.clientId, Some("cid"))
    assertEquals(back.clientSecret, Some("sec"))
    assertEquals(back.scope, Some("a2a"))
    // oauth2 yields no synchronous headers (resolved async)
    assertEquals(back.toHeaders, Seq.empty)
  }

  test("tenant propagates to AgentInterface and round-trips on both entities") {
    val server = A2AServer(id = "a2a-server_t", name = "srv", tenant = Some("acme"),
      backend = A2AServerBackend(kind = "agent", agent = Some(Json.obj("name" -> "a", "instructions" -> Json.arr("x"), "provider" -> "p"))))
    val card = server.toAgentCard("https://x/a2a").json.as[JsObject]
    assertEquals((card \ "supportedInterfaces" \ 0 \ "tenant").as[String], "acme")
    assertEquals(A2AServer.format.reads(server.json).get.tenant, Some("acme"))

    val conn = A2AConnector(id = "a2a-connector_t", name = "c", url = "https://r", tenant = Some("acme"))
    assertEquals(A2AConnector.format.reads(conn.json).get.tenant, Some("acme"))
  }

  test("A2AServer entity JSON round-trips") {
    val server = A2AServer(
      id = "a2a-server_test",
      name = "srv",
      description = "d",
      agentCard = A2AServerCard(skills = Seq(A2ASkillConfig("main", "Main", "main skill"))),
      backend = A2AServerBackend(kind = "workflow", workflowRef = Some("workflow_123")),
    )
    val back = A2AServer.format.reads(server.json).get
    assertEquals(back.id, "a2a-server_test")
    assertEquals(back.name, "srv")
    assertEquals(back.backend.kind, "workflow")
    assertEquals(back.backend.workflowRef, Some("workflow_123"))
    assertEquals(back.agentCard.skills.size, 1)
    assertEquals(back.agentCard.skills.head.id, "main")
  }
}
