package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.LlmExtensionOneOtoroshiServerPerSuite
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import otoroshi.env.Env
import otoroshi.next.workflow.{Node, WorkflowError, WorkflowRun}
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*
import reactor.core.publisher.Mono

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.DurationInt

// The agent router asks a model which of its paths to follow: each path is offered to the model as a tool, named
// and described after the path, and the path of the tool the model calls is the one that runs. A path is written
// by hand as a node carrying its own id and description; the workflow designer writes it as `{ node }`, like the
// paths of a switch.
class AgentRouterNodeSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  val routerKind = "extensions.com.cloud-apim.llm-extension.router"

  // a model that calls the tool whose description talks about what the user asks
  val received = new AtomicReference[JsValue](JsNull)
  val (port1, _) = createTestServerWithRoutes("agent-router", routes => routes
    .post("/v1/chat/completions", (req, response) => req.receive().aggregate().asString().flatMap { raw =>
      val body = Json.parse(raw)
      received.set(body)
      val question = body.select("messages").as[Seq[JsObject]].lastOption.flatMap(_.select("content").asOptString).getOrElse("")
      val subject = if (question.contains("equation")) "math" else "history"
      val tool = body.select("tools").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map(_.select("function").asObject)
        .find(_.select("description").asOptString.exists(_.contains(subject))).flatMap(_.select("name").asOptString).getOrElse("nothing")
      response.status(200).addHeader("Content-Type", "application/json").sendString(Mono.just(Json.obj(
        "id" -> "chatcmpl-1", "object" -> "chat.completion", "created" -> 1700000000, "model" -> "a-model",
        "choices" -> Json.arr(Json.obj("index" -> 0, "finish_reason" -> "tool_calls", "message" -> Json.obj("role" -> "assistant", "content" -> JsNull, "tool_calls" -> Json.arr(Json.obj(
          "id" -> "call_1", "type" -> "function", "function" -> Json.obj("name" -> tool, "arguments" -> "{}"),
        ))))),
        "usage" -> Json.obj("prompt_tokens" -> 10, "completion_tokens" -> 5, "total_tokens" -> 15),
      ).stringify)).`then`()
    })
  )

  lazy val provider = AiProvider(
    id = s"provider_${UUID.randomUUID()}", name = "routing model", provider = "openai",
    connection = Json.obj("base_url" -> s"http://localhost:${port1}/v1", "token" -> "sk-test", "timeout" -> 10000),
    options = Json.obj("model" -> "a-model"),
  )

  lazy val setup: Unit = {
    assert(client.forLlmEntity("providers").upsertEntity(provider).awaitf(10.seconds).createdOrUpdated, "the provider should be saved")
    await(5.seconds)
  }

  def router(paths: JsArray, input: String): JsObject = Json.obj(
    "kind" -> routerKind, "provider" -> provider.id, "input" -> input,
    "instructions" -> Json.arr("You determine which tutor to use based on the question"), "paths" -> paths,
  )

  def run(node: JsObject): Either[WorkflowError, JsValue] = {
    given env: Env = otoroshi.env
    val wfr = WorkflowRun(id = "run", attrs = TypedMap.empty, env = otoroshi.env, workflow_ref = "test", workflow = Json.obj())
    Node.from(node).internalRun(wfr, Seq(0), Seq.empty)(using env, ec).awaitf(30.seconds)
  }

  // the tools the model was offered, by name
  def offered: Map[String, String] = received.get().select("tools").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
    .map(_.select("function").asObject).map(f => f.select("name").asString -> f.select("description").asOpt[String].getOrElse("")).toMap

  val math = Json.obj("kind" -> "value", "value" -> "the math tutor")
  val history = Json.obj("kind" -> "value", "value" -> "the history tutor")

  test("a path written by hand is a node carrying its own id and description") {
    setup
    val result = run(router(Json.arr(
      math ++ Json.obj("id" -> "math_tutor", "description" -> "Specialist of math questions"),
      history ++ Json.obj("id" -> "history_tutor", "description" -> "Specialist of history questions"),
    ), "how do I solve this equation ?"))
    assertEquals(result.toOption, Some(JsString("the math tutor")), s"got ${result}")
    assertEquals(offered, Map("math_tutor" -> "Specialist of math questions", "history_tutor" -> "Specialist of history questions"))
  }

  test("a path written by the designer names and describes the node it leads to") {
    setup
    val result = run(router(Json.arr(
      Json.obj("id" -> "math_tutor", "description" -> "Specialist of math questions", "node" -> math),
      Json.obj("id" -> "history_tutor", "description" -> "Specialist of history questions", "node" -> history),
    ), "who was the first emperor of Rome ?"))
    assertEquals(result.toOption, Some(JsString("the history tutor")), s"got ${result}")
    assertEquals(offered, Map("math_tutor" -> "Specialist of math questions", "history_tutor" -> "Specialist of history questions"))
  }

  test("a path without a name of its own takes the id and the description of its node") {
    setup
    val result = run(router(Json.arr(
      Json.obj("node" -> (math ++ Json.obj("id" -> "node_1", "description" -> "Specialist of math questions"))),
      Json.obj("node" -> (history ++ Json.obj("id" -> "node_2", "description" -> "Specialist of history questions"))),
    ), "how do I solve this equation ?"))
    assertEquals(result.toOption, Some(JsString("the math tutor")), s"got ${result}")
    assertEquals(offered, Map("node_1" -> "Specialist of math questions", "node_2" -> "Specialist of history questions"))
  }
}
