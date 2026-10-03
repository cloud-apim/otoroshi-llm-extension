package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.decorators.OtoroshiRouterChatClient
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiBudgetConsumptions, AiProvider, DecisionModel}
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatMessage, ChatPrompt, LlmExtensionOneOtoroshiServerPerSuite}
import otoroshi.env.Env
import otoroshi.models.{ApiKey, EntityLocation}
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.*
import reactor.core.publisher.Mono
import reactor.netty.http.server.HttpServerResponse

import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

// Two routing models of the otoroshi router have a decision model read the request.
// The smart-router has it rate how demanding the request is, and picks with what the gateway knows of its
// candidates: the cheapest one that is good enough for that request. The intent-router has it pick, among
// candidates described by whoever configured the router, the one whose description fits the request.
class DecisionRoutersSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  private def send(response: HttpServerResponse, status: Int, body: JsValue) =
    response.status(status).addHeader("Content-Type", "application/json").sendString(Mono.just(body.stringify)).`then`()

  // A decision model. It rates a request by what it talks about, and picks the option whose description shares
  // a word with the request; it is not sure of itself when the request says `maybe`.
  // One server for each group of cases: a server of the suite does not take many calls
  class Decisions(name: String) {
    val received = new ConcurrentLinkedQueue[JsValue]()
    val (port, _) = createTestServerWithRoutes(name, routes => routes
      .post("/v1/systemone", (req, response) => req.receive().aggregate().asString().flatMap { raw =>
        val body = Json.parse(raw)
        received.add(body)
        val state = body.select("state").asOpt[JsValue].map(_.stringify.toLowerCase).getOrElse("")
        val answers = body.select("questions").as[JsObject].value.map { case (question, asked) =>
          asked.select("type").asString match {
            case "score" =>
              val levels = asked.select("criteria").as[Seq[String]]
              val level = if (state.contains("hello")) 0 else if (state.contains("research")) levels.size - 1 else levels.size / 2
              question -> Json.obj(
                "type" -> "score", "score" -> level, "confidence" -> 1,
                "legend" -> JsObject(levels.zipWithIndex.map { case (l, i) => i.toString -> JsString(l) }),
                "probabilities" -> JsObject(levels.indices.map(i => i.toString -> JsNumber(if (i == level) 1 else 0))),
              )
            case _ =>
              val options = asked.select("criteria").as[JsObject].value.toSeq.map { case (option, description) => (option, description.as[String].toLowerCase) }
              val fitting = options.find { case (_, description) => description.split("\\W+").exists(word => word.length > 3 && state.contains(word)) }.map(_._1)
              val choice = fitting.getOrElse(options.last._1)
              question -> Json.obj(
                "type" -> "choice", "choice" -> choice, "confidence" -> (if (state.contains("maybe")) 0.2 else 0.9),
                // the options after the chosen one come in the opposite order of the one they were given in
                "probabilities" -> JsObject(options.zipWithIndex.map { case ((option, _), i) => option -> JsNumber(if (option == choice) 0.9 else 0.01 * (i + 1)) }),
              )
          }
        }
        send(response, 200, Json.obj("model" -> "jev-test", "answers" -> JsObject(answers), "usage" -> Json.obj("input_tokens" -> 40, "output_tokens" -> 0)))
      })
    )
    val model = DecisionModel(
      EntityLocation.default, s"decision-model_${UUID.randomUUID()}", name, "", Seq.empty, Map.empty, "typesafe",
      Json.obj("connection" -> Json.obj("base_url" -> s"http://localhost:${port}/v1", "token" -> "xxx", "timeout" -> 30000), "options" -> Json.obj("model" -> "jev-test")),
    )
    def asked: Seq[JsValue] = received.asScala.toSeq
  }

  // A provider serving any model it is asked for, and saying which ones it was asked. A model named `down` fails
  class Chats(name: String) {
    val models = new ConcurrentLinkedQueue[String]()
    val (port, _) = createTestServerWithRoutes(name, routes => routes
      .post("/v1/chat/completions", (req, response) => req.receive().aggregate().asString().flatMap { raw =>
        val model = Json.parse(raw).select("model").asString
        models.add(model)
        if (model == "down") send(response, 500, Json.obj("error" -> Json.obj("message" -> "down")))
        else send(response, 200, Json.obj(
          "id" -> "chatcmpl-1", "object" -> "chat.completion", "created" -> 1700000000, "model" -> model,
          "choices" -> Json.arr(Json.obj("index" -> 0, "finish_reason" -> "stop", "message" -> Json.obj("role" -> "assistant", "content" -> "hello"))),
          "usage" -> Json.obj("prompt_tokens" -> 10, "completion_tokens" -> 5, "total_tokens" -> 15),
        ))
      })
    )
    val provider = AiProvider(
      id = s"provider_${UUID.randomUUID()}", name = name, provider = "openai",
      connection = Json.obj("base_url" -> s"http://localhost:${port}/v1", "token" -> "sk-test", "timeout" -> 10000),
      options = Json.obj("model" -> "a-model"),
    )
    def asked: Seq[String] = models.asScala.toSeq
  }

  val rating = new Decisions("decision-routers-rating")
  val picking = new Decisions("decision-routers-picking")
  val rated = new Chats("decision-routers-rated")
  val unrated = new Chats("decision-routers-unrated")
  val picked = new Chats("decision-routers-picked")
  val cascading = new Chats("decision-routers-cascading")

  def ext: AiExtension = otoroshi.env.adminExtensions.extension[AiExtension].get

  // Three models the gateway knows the price and the coding score of, each one better and more expensive than the
  // one before, the first one scoring less than half of the last one: picked from what the gateway bundles, which
  // changes with every refresh of its data files
  lazy val (cheap, middle, best) = {
    val known = ext.costsTracking.models.values.toSeq.flatMap { m =>
      val name = m.name.substring(m.name.lastIndexOf('/') + 1)
      for {
        priced <- ext.costsTracking.getModel("openai", name)
        score <- OtoroshiRouterChatClient.codingScoreFor(name)
        cost = priced.input_cost_per_token + (priced.output_cost_per_token * 3) if cost > 0
      } yield (name, score, cost)
    }.distinctBy(_._1).sortBy(_._2)
    val triples = for {
      c <- known.reverseIterator
      b <- known.iterator if b._2 < c._2 && b._3 < c._3 && b._2 >= 0.5 * c._2
      a <- known.iterator if a._2 < 0.5 * c._2 && a._3 < b._3
    } yield (a._1, b._1, c._1)
    triples.nextOption().getOrElse(fail(s"no three priced and scored models fit among ${known.size}"))
  }

  def candidates(chats: Chats, models: String*): JsArray = JsArray(models.map(m => Json.obj("ref" -> chats.provider.id, "model" -> m)))

  def router(name: String, options: JsObject): AiProvider = AiProvider(
    id = s"provider_${UUID.randomUUID()}", name = name, provider = "otoroshi", connection = Json.obj(), options = options,
  )

  lazy val smart = router("smart", Json.obj("decision_model_ref" -> rating.model.id, "smart_router_refs" -> candidates(rated, best, cheap, middle)))
  // a router whose decision model is nowhere to be found
  lazy val blind = router("blind", Json.obj("decision_model_ref" -> "decision-model_nowhere", "smart_router_refs" -> candidates(unrated, best, cheap, middle)))
  lazy val demanding = router("demanding", Json.obj("smart_router_refs" -> candidates(unrated, best, cheap, middle), "smart_router_min_score" -> 1))

  def described(chats: Chats, first: String = "generalist"): JsArray = Json.arr(
    Json.obj("ref" -> chats.provider.id, "model" -> first, "description" -> "Everyday questions and conversation"),
    Json.obj("ref" -> chats.provider.id, "model" -> "translator", "description" -> "Translation between languages", "name" -> "translation"),
    Json.obj("ref" -> chats.provider.id, "model" -> "coder", "description" -> "Software, code review and database queries"),
  )
  // a router asking its decision model for another model than the default one of the decision model
  lazy val intent = router("intent", Json.obj(
    "decision_model_ref" -> picking.model.id, "decision_model_model" -> "jev-for-routing", "intent_router_refs" -> described(picked),
    "intent_router_instructions" -> "Which assistant should answer ?", "intent_router_min_confidence" -> 0.5,
  ))
  lazy val failing = router("failing", Json.obj(
    "decision_model_ref" -> picking.model.id,
    "intent_router_refs" -> Json.arr(
      Json.obj("ref" -> cascading.provider.id, "model" -> "generalist", "description" -> "Everyday questions and conversation"),
      Json.obj("ref" -> cascading.provider.id, "model" -> "down", "description" -> "Translation between languages"),
      Json.obj("ref" -> cascading.provider.id, "model" -> "coder", "description" -> "Software, code review and database queries"),
    ),
  ))

  // what an api key may spend, whatever serves it
  val caller = ApiKey(clientId = "key-of-decision-routers", clientSecret = "secret", clientName = "caller", authorizedEntities = Seq.empty)
  val budget = Json.obj(
    "id" -> s"budget_${UUID.randomUUID()}", "name" -> "budget of the caller", "description" -> "", "enabled" -> true,
    "duration" -> Json.obj("value" -> 1, "unit" -> "year"), "limits" -> Json.obj("total_usd" -> 1000, "total_tokens" -> 100000000),
    "scope" -> Json.obj("apikeys" -> Json.arr(caller.clientId)),
    "action_on_exceed" -> Json.obj("mode" -> "soft", "alert_on_exceed" -> false, "alert_on_almost_exceed" -> false),
  )

  lazy val setup: Unit = {
    Seq(rating, picking).foreach(d => assert(client.forLlmEntity("decision-models").upsertEntity(d.model).awaitf(10.seconds).createdOrUpdated, s"${d.model.name} should be saved"))
    (Seq(rated, unrated, picked, cascading).map(_.provider) ++ Seq(smart, blind, demanding, intent, failing)).foreach { p =>
      assert(client.forLlmEntity("providers").upsertEntity(p).awaitf(10.seconds).createdOrUpdated, s"${p.name} should be saved")
    }
    client.forLlmEntity("ai-budgets").createRaw(budget).awaitf(10.seconds)
    await(10.seconds)
  }

  def consumptions(): AiBudgetConsumptions = {
    given env: Env = otoroshi.env
    ext.states.allBudgets().find(_.name == "budget of the caller").get.getConsumptions().awaitf(10.seconds)
  }

  def ask(router: AiProvider, model: String, question: String, attrs: TypedMap = TypedMap.empty): Unit = {
    given env: Env = otoroshi.env
    val resp = ext.states.provider(router.id).flatMap(_.getChatClient()).get
      .call(ChatPrompt(Seq(ChatMessage.userStrInput(question))), attrs, Json.obj("model" -> model))(using ec, env).awaitf(30.seconds)
    assert(resp.isRight, s"the router should answer, got ${resp}")
  }

  test("the router lists its two routing models read by a decision model") {
    setup
    given env: Env = otoroshi.env
    val models = ext.states.provider(smart.id).flatMap(_.getChatClient()).get.listModels(false, TypedMap.empty)(using ec).awaitf(10.seconds)
    assert(models.exists(m => m.contains("smart-router") && m.contains("intent-router")), s"got ${models}")
  }

  test("smart-router: the more demanding the request, the better the model that answers, the cheapest good enough one") {
    setup
    ask(smart, "smart-router", "hello !")
    ask(smart, "smart-router", "summarize this page for me")
    ask(smart, "smart-router", "research the proof of this theorem")
    assertEquals(rated.asked, Seq(cheap, middle, best), "a greeting goes to the cheapest model, a routine task to the middle one, research to the best")
    // what the decision model was asked: how demanding the request is, on the scale of the router
    val asked = rating.asked.head
    assertEquals(asked.at("questions.difficulty.type").asOptString, Some("score"))
    assertEquals(asked.at("questions.difficulty.criteria").asOpt[Seq[String]], Some(OtoroshiRouterChatClient.difficultyLevels))
    assertEquals(asked.select("state").asOpt[Seq[JsObject]].map(_.map(m => (m.select("role").asString, m.select("content").asString))), Some(Seq(("user", "hello !"))))
    assertEquals(rating.asked.size, 3, "one question for each request")
    assertEquals(asked.select("model").asOptString, Some("jev-test"), "the decision model answers with its own model")
  }

  test("smart-router: without an answer of the decision model a request is of average difficulty, and the floors can be moved") {
    setup
    ask(blind, "smart-router", "hello !")
    assertEquals(unrated.asked, Seq(middle), "the router answers all the same, with the model of an average request")
    ask(demanding, "smart-router", "hello !")
    assertEquals(unrated.asked.last, best, "a floor at the top always takes the best model")
  }

  test("intent-router: the candidate whose description fits the request answers, and the decision is counted for the caller") {
    setup
    val before = consumptions()
    val attrs = TypedMap.empty.put(_root_.otoroshi.plugins.Keys.ApiKeyKey -> caller)
    ask(intent, "intent-router", "which database queries are slow ?", attrs)
    ask(intent, "intent-router", "I need a translation of this letter")
    assertEquals(picked.asked, Seq("coder", "translator"))
    // what the decision model was asked: a choice between the candidates as they were described
    val asked = picking.asked.head
    assertEquals(asked.at("questions.intent").asOpt[JsObject], Some(Json.obj(
      "type" -> "choice",
      "instructions" -> "Which assistant should answer ?",
      "criteria" -> Json.obj("option_1" -> "Everyday questions and conversation", "translation" -> "Translation between languages", "option_3" -> "Software, code review and database queries"),
    )))
    assertEquals(asked.select("model").asOptString, Some("jev-for-routing"), "or with the model the router names")
    await(5.seconds)
    val after = consumptions()
    assertEquals(after.decisionTokens - before.decisionTokens, 40L, "the decision of the first request counts for its caller")
    assertEquals(after.inferenceTokens - before.inferenceTokens, 15L, "with the answer")
  }

  test("intent-router: an answer the decision model is not sure of is not followed, and a candidate that fails is replaced") {
    setup
    ask(intent, "intent-router", "maybe a translation, maybe not")
    assertEquals(picked.asked.last, "generalist", "the first candidate is the default one")
    // the translator is down: the next candidate is the most probable of the other ones
    ask(failing, "intent-router", "I need a translation of this letter")
    assertEquals(cascading.asked, Seq("down", "coder"))
    assertEquals(picking.asked.last.select("model").asOptString, Some("jev-test"), "a router naming no model leaves the decision model with its own")
  }
}
