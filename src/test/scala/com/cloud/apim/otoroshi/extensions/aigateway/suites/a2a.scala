package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.a2a._
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{A2AServer, A2AServerBackend, A2AServerCard, A2ASkillConfig}
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
