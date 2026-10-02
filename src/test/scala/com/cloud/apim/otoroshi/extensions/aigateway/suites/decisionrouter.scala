package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.decorators.ChatClientWithAuding
import com.cloud.apim.otoroshi.extensions.aigateway.entities.DecisionModel
import com.cloud.apim.otoroshi.extensions.aigateway.{DecisionRouterNode, LlmExtensionOneOtoroshiServerPerSuite}
import otoroshi.env.Env
import otoroshi.models.EntityLocation
import otoroshi.next.workflow.{Node, WorkflowError, WorkflowRun}
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*
import reactor.core.publisher.Mono

import java.util.UUID
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.duration.DurationInt

// A workflow can be routed to one of its paths by a decision model: each path is an option of a `choice` question
// about a state, and the path the model names is the one that runs. The model says how sure it is, so a workflow
// can refuse to follow an answer it is not sure of.
class DecisionRouterSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  val jev = "jev-test"

  // A decision model choosing by what the state talks about. It is sure of itself, unless the state says it is not;
  // a state it knows nothing about makes it name an option nobody gave it.
  // One server for each group of cases: a server of the suite does not take many calls
  class Decisions(name: String) {
    val calls = new AtomicInteger(0)
    val received = new AtomicReference[JsValue](JsNull)
    val (port, _) = createTestServerWithRoutes(name, routes => routes
      .post("/v1/systemone", (req, response) => req.receive().aggregate().asString().flatMap { body =>
        calls.incrementAndGet()
        val json = Json.parse(body)
        received.set(json)
        val state = json.select("state").asOpt[JsValue].map(_.stringify).getOrElse("")
        val options = json.at("questions.route.criteria").asOpt[JsObject].map(_.keys.toSeq).getOrElse(Seq.empty)
        val choice = if (state.contains("invoice")) "billing" else if (state.contains("outage")) "technical" else "nobody"
        val confidence = if (state.contains("maybe")) 0.2 else 0.9
        response.status(200).addHeader("Content-Type", "application/json").sendString(Mono.just(Json.obj(
          "model" -> json.select("model").asOptString.getOrElse(jev).json,
          "answers" -> Json.obj("route" -> Json.obj(
            "type" -> "choice", "choice" -> choice, "confidence" -> confidence,
            "probabilities" -> JsObject(options.map(option => option -> JsNumber(if (option == choice) 0.9 else 0.1))),
          )),
          "usage" -> Json.obj("input_tokens" -> 40, "output_tokens" -> 0),
        ).stringify)).`then`()
      })
    )
    val model = DecisionModel(
      EntityLocation.default, s"decision-model_${UUID.randomUUID()}", name, "", Seq.empty, Map.empty, "typesafe",
      Json.obj("connection" -> Json.obj("base_url" -> s"http://localhost:${port}/v1", "token" -> "xxx", "timeout" -> 30000), "options" -> Json.obj("model" -> jev)),
    )
  }

  val sure = new Decisions("decision-router-sure")
  val hesitant = new Decisions("decision-router-hesitant")

  lazy val setup: Unit = {
    Seq(sure, hesitant).foreach { decisions =>
      assert(client.forLlmEntity("decision-models").upsertEntity(decisions.model).awaitf(10.seconds).createdOrUpdated, s"${decisions.model.name} should be saved")
    }
    await(5.seconds)
  }

  // the paths, the way the designer writes them: what the path stands for, and the node it leads to
  val paths = Json.arr(
    Json.obj("id" -> "billing", "description" -> "Invoices and payments", "node" -> Json.obj("kind" -> "value", "value" -> "the billing team")),
    Json.obj("id" -> "technical", "description" -> "Outages, bugs and integrations", "node" -> Json.obj("kind" -> "value", "value" -> "the technical team")),
  )

  def router(decisions: Decisions, config: JsObject): JsObject =
    Json.obj("kind" -> DecisionRouterNode.nodeKind, "provider" -> decisions.model.id, "paths" -> paths) ++ config

  def newRun(): WorkflowRun = WorkflowRun(id = "run", attrs = TypedMap.empty, env = otoroshi.env, workflow_ref = "test", workflow = Json.obj())

  def run(node: JsObject, wfr: WorkflowRun = newRun()): Either[WorkflowError, JsValue] = {
    given env: Env = otoroshi.env
    Node.from(node).internalRun(wfr, Seq(0), Seq.empty)(using env, ec).awaitf(30.seconds)
  }

  test("the path the decision model names is the one that runs") {
    setup
    val wfr = newRun()
    val result = run(router(sure, Json.obj(
      "state" -> "I was charged twice on my last invoice",
      "instructions" -> "Which team should handle this support request ?",
      "decision_result" -> "routing",
      "result" -> "team",
    )), wfr)
    assertEquals(result.toOption, Some(JsString("the billing team")), s"got ${result}")
    assertEquals(wfr.memory.get("team"), Some(JsString("the billing team")), "the result of the path is the one of the router")
    // what the decision model was asked: one question, whose options are the paths
    val asked = sure.received.get()
    assertEquals(asked.select("state").asOptString, Some("I was charged twice on my last invoice"))
    assertEquals(asked.at("questions.route").asOpt[JsObject], Some(Json.obj(
      "type" -> "choice",
      "instructions" -> "Which team should handle this support request ?",
      "criteria" -> Json.obj("billing" -> "Invoices and payments", "technical" -> "Outages, bugs and integrations"),
    )))
    // what it answered is kept for the rest of the workflow
    assertEquals(wfr.memory.get("routing").flatMap(_.select("choice").asOptString), Some("billing"))
    assertEquals(wfr.memory.get("routing").flatMap(_.at("probabilities.billing").asOpt[BigDecimal]), Some(BigDecimal("0.9")))
    // and the call to the decision model is a call of its own: it leaves nothing in the attributes of the workflow
    assertEquals(wfr.attrs.get(ChatClientWithAuding.ProviderKey).map(_.theId), None)
  }

  test("the state is the input of the workflow when the node names none, and may be computed") {
    setup
    val fromInput = newRun()
    fromInput.memory.set("input", JsString("there is an outage on the checkout"))
    assertEquals(run(router(sure, Json.obj()), fromInput).toOption, Some(JsString("the technical team")))
    assertEquals(sure.received.get().at("questions.route.instructions").asOptString, Some(DecisionRouterNode.defaultInstructions), "a question is asked even when the node gives none")
    val computed = newRun()
    computed.memory.set("input", Json.obj("request" -> "an invoice is missing"))
    assertEquals(run(router(sure, Json.obj("state" -> "${input.request}", "model" -> "another-model")), computed).toOption, Some(JsString("the billing team")))
    assertEquals(sure.received.get().select("state").asOptString, Some("an invoice is missing"))
    assertEquals(sure.received.get().select("model").asOptString, Some("another-model"), "the model of the node is the one asked")
  }

  test("a path can be a node carrying its own id and description") {
    setup
    val asNodes = Json.arr(
      Json.obj("id" -> "billing", "description" -> "Invoices and payments", "kind" -> "value", "value" -> "the billing team"),
      Json.obj("id" -> "technical", "description" -> "Outages, bugs and integrations", "kind" -> "value", "value" -> "the technical team"),
    )
    val result = run(router(hesitant, Json.obj("state" -> "there is an outage", "paths" -> asNodes)))
    assertEquals(result.toOption, Some(JsString("the technical team")), s"got ${result}")
    assertEquals(hesitant.received.get().at("questions.route.criteria").asOpt[JsObject], Some(Json.obj("billing" -> "Invoices and payments", "technical" -> "Outages, bugs and integrations")))
  }

  test("an answer the decision model is not sure of is not followed") {
    setup
    val unsure = Json.obj("state" -> "maybe something about an invoice", "min_confidence" -> 0.5)
    assertEquals(run(router(hesitant, unsure ++ Json.obj("default_path" -> "technical"))).toOption, Some(JsString("the technical team")), "the default path runs")
    assertEquals(run(router(hesitant, unsure)).toOption, Some(JsNull), "without a default path, no path runs")
    // the designer writes the confidence as a text, and nothing at all when the field is left empty
    Seq[JsValue](JsNumber(0.5), JsString("0.5"), JsString(" 0,5 ")).foreach(written => assertEquals(DecisionRouterNode.minConfidence(Json.obj("min_confidence" -> written)), Some(0.5), written.toString))
    Seq[JsValue](JsString(""), JsNull, JsString("high")).foreach(written => assertEquals(DecisionRouterNode.minConfidence(Json.obj("min_confidence" -> written)), None, written.toString))
  }

  test("the designer can list the node: it is described without a configuration") {
    setup
    // the documentation of the nodes is built from each node made of an empty json: one that throws there
    // takes the whole designer down
    val blank = Node.nodes(DecisionRouterNode.nodeKind)(Json.obj())
    assertEquals(blank.documentationName, DecisionRouterNode.nodeKind)
    assert(blank.documentationDisplayName.nonEmpty && blank.documentationDescription.nonEmpty && blank.documentationIcon.nonEmpty)
    assert(blank.documentationInputSchema.isDefined)
    assertEquals(blank.documentationExample.flatMap(_.select("kind").asOptString), Some(DecisionRouterNode.nodeKind))
    assertEquals(blank.subNodes.size, 0)
    // and the example it gives is a node that runs
    val example = blank.documentationExample.get ++ Json.obj("provider" -> sure.model.id, "state" -> "an invoice is missing")
    assertEquals(run(example).toOption, Some(JsString("billing team")))
    // the documentation the designer loads, of every node at once
    val documented = _root_.otoroshi.next.workflow.WorkflowGenerators.generateJsonDescriptor()
    assert(documented.select("nodes").as[Seq[JsObject]].exists(_.select("name").asOptString.contains(DecisionRouterNode.nodeKind)), "the decision router is in it")
  }

  test("a decision router that cannot decide says why") {
    setup
    val before = sure.calls.get() + hesitant.calls.get()
    val onePath = run(router(sure, Json.obj("state" -> "an invoice", "paths" -> Json.arr(paths.value.head))))
    assert(onePath.left.exists(_.message.contains("at least two paths")), s"got ${onePath}")
    val sameIds = run(router(sure, Json.obj("state" -> "an invoice", "paths" -> Json.arr(paths.value.head, paths.value.head))))
    assert(sameIds.left.exists(_.message.contains("distinct ids")), s"got ${sameIds}")
    val noState = run(router(sure, Json.obj()))
    assert(noState.left.exists(_.message.contains("needs a state")), s"got ${noState}")
    val noModel = run(router(sure, Json.obj("state" -> "an invoice", "provider" -> "decision-model_nowhere")))
    assert(noModel.left.exists(_.message.contains("decision model not found")), s"got ${noModel}")
    assertEquals(sure.calls.get() + hesitant.calls.get(), before, "none of them asked the decision model")
    // an option nobody gave it
    val stranger = run(router(hesitant, Json.obj("state" -> "what is the weather like ?")))
    assert(stranger.left.exists(_.message.contains("named none of the paths")), s"got ${stranger}")
  }
}
