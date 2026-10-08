package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.decorators.{ChatClientWithCostsTracking, CostsOutput, ModelConstraints, RequiredCosts}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.DecisionModel
import com.cloud.apim.otoroshi.extensions.aigateway.providers.LlmDecisionModelClient
import com.cloud.apim.otoroshi.extensions.aigateway.{DecisionAnswers, DecisionErrors, DecisionModelClientInputOptions, DecisionRequests, LlmExtensionOneOtoroshiServerPerSuite}
import otoroshi.env.Env
import otoroshi.models.EntityLocation
import otoroshi.next.models.*
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.{DecisionModels, DecisionModelsResolver, OpenAiCompatApi}
import play.api.libs.json.*
import play.api.libs.ws.WSBodyWritables.writeableOf_String
import reactor.core.publisher.Mono
import reactor.netty.http.server.HttpServerResponse

import java.util.UUID
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.duration.DurationInt

// Decision models (TypeSafe Jev and the servers speaking its System One api): typed questions about a state,
// answered with probabilities. The api goes through the gateway untouched, errors included.
class DecisionModelsSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  val jev = "jev-1.13.0"
  // priced at 4.2e-8 per input token in the bundled grid, the output being free
  val inputTokens = 350L
  val expectedCost = BigDecimal(inputTokens) * BigDecimal("0.000000042")

  val request: JsObject = Json.obj(
    "state" -> "The checkout has been failing for every customer for the last hour.",
    "questions" -> Json.obj(
      "urgent" -> Json.obj("type" -> "noul", "instructions" -> "Is this support request urgent ?"),
    ),
  )

  def answer(model: String): JsObject = Json.obj(
    "model" -> model,
    "answers" -> Json.obj("urgent" -> Json.obj("type" -> "noul", "noul" -> 0.95)),
    "usage" -> Json.obj("input_tokens" -> inputTokens, "output_tokens" -> 58),
  )

  private def send(response: HttpServerResponse, status: Int, body: String, contentType: String = "application/json", headers: Map[String, String] = Map.empty) = {
    headers.foldLeft(response.status(status).addHeader("Content-Type", contentType)) { case (r, (k, v)) => r.addHeader(k, v) }
      .sendString(Mono.just(body)).`then`()
  }

  // a System One server: what it received is kept, to check the gateway forwards a request as it is
  val okCalls = new AtomicInteger(0)
  val okBody = new AtomicReference[JsValue](JsNull)
  val (okPort, _) = createTestServerWithRoutes("systemone-ok", routes => routes
    .post("/v1/systemone", (req, response) => {
      req.receive().aggregate().asString().flatMap { body =>
        okCalls.incrementAndGet()
        okBody.set(Json.parse(body))
        send(response, 200, answer(jev).stringify)
      }
    })
  )

  // the errors of the TypeSafe api, each under its own base url
  val errCalls = new AtomicInteger(0)
  val (errPort, _) = createTestServerWithRoutes("systemone-errors", routes => routes
    .post("/e401/systemone", (req, response) => {
      req.receive().aggregate().asString().flatMap { _ =>
        errCalls.incrementAndGet()
        send(response, 401, Json.obj("detail" -> Json.obj("error_type" -> "authentication_error", "message" -> "Cannot authenticate with the server.")).stringify)
      }
    })
    .post("/e422/systemone", (req, response) => {
      req.receive().aggregate().asString().flatMap { _ =>
        errCalls.incrementAndGet()
        send(response, 422, Json.obj("detail" -> Json.arr(Json.obj("type" -> "too_long", "loc" -> Json.arr("body", "questions"), "msg" -> "too many questions"))).stringify)
      }
    })
    .post("/e429/systemone", (req, response) => {
      req.receive().aggregate().asString().flatMap { _ =>
        errCalls.incrementAndGet()
        send(response, 429, Json.obj("detail" -> Json.obj("error_type" -> "rate_limit_error", "message" -> "slow down")).stringify, headers = Map("Retry-After" -> "7"))
      }
    })
    .post("/e529/systemone", (req, response) => {
      req.receive().aggregate().asString().flatMap { _ =>
        errCalls.incrementAndGet()
        send(response, 529, "<html>overloaded</html>", contentType = "text/html")
      }
    })
  )

  // OpenRouter adds an id, the provider that served the call and its cost
  val routerBody = new AtomicReference[JsValue](JsNull)
  val (routerPort, _) = createTestServerWithRoutes("systemone-openrouter", routes => routes
    .post("/api/v1/systemone", (req, response) => {
      req.receive().aggregate().asString().flatMap { body =>
        routerBody.set(Json.parse(body))
        send(response, 200, (answer("typesafe/jev-1.13-20260917") ++ Json.obj(
          "id" -> "gen-dec-1",
          "provider" -> "TypeSafe",
          "usage" -> Json.obj("input_tokens" -> 476, "output_tokens" -> 70, "cost" -> 0.000019992),
        )).stringify)
      }
    })
  )

  // Workers AI: the model is in the url, the answer in an envelope
  val cloudflareUri = new AtomicReference[String]("")
  val cloudflareBody = new AtomicReference[JsValue](JsNull)
  val (cloudflarePort, _) = createTestServer("systemone-cloudflare", (req, response) => {
    req.receive().aggregate().asString().flatMap { body =>
      cloudflareUri.set(req.uri())
      cloudflareBody.set(Json.parse(body))
      // the model that answers is the one of the url, which the gateway bills
      send(response, 200, Json.obj("result" -> answer(req.uri().split("/").last), "success" -> true, "errors" -> Json.arr(), "messages" -> Json.arr()).stringify)
    }
  })

  def ext: AiExtension = otoroshi.env.adminExtensions.extension[AiExtension].get

  def decisionModel(name: String, provider: String, connection: JsObject, model: String): DecisionModel = DecisionModel(
    EntityLocation.default, s"decision-model_${UUID.randomUUID()}", name, "", Seq.empty, Map.empty, provider,
    Json.obj("connection" -> (Json.obj("token" -> "xxx", "timeout" -> 30000) ++ connection), "options" -> Json.obj("model" -> model)),
  )

  lazy val typesafe = decisionModel("jev", "typesafe", Json.obj("base_url" -> s"http://localhost:${okPort}/v1"), jev)
  lazy val e401 = decisionModel("e401", "typesafe", Json.obj("base_url" -> s"http://localhost:${errPort}/e401"), jev)
  lazy val e422 = decisionModel("e422", "typesafe", Json.obj("base_url" -> s"http://localhost:${errPort}/e422"), jev)
  lazy val e429 = decisionModel("e429", "typesafe", Json.obj("base_url" -> s"http://localhost:${errPort}/e429"), jev)
  lazy val e529 = decisionModel("e529", "typesafe", Json.obj("base_url" -> s"http://localhost:${errPort}/e529"), jev)
  lazy val openrouter = decisionModel("router", "openrouter", Json.obj("base_url" -> s"http://localhost:${routerPort}/api/v1"), "typesafe/jev-1.13")
  lazy val cloudflare = decisionModel("workers", "cloudflare", Json.obj("base_url" -> s"http://localhost:${cloudflarePort}/client/v4", "account_id" -> "acc123"), "@cf/cloudflare/clef-flash")
  // an entity that keeps its model whatever the client asks for
  lazy val pinned = cloudflare.copy(
    id = s"decision-model_${UUID.randomUUID()}",
    name = "pinned",
    config = cloudflare.config ++ Json.obj("options" -> Json.obj("model" -> "@cf/cloudflare/clef", "allow_config_override" -> false)),
  )

  def route(name: String, domain: String, plugin: Class[?], config: JsObject): NgRoute = NgRoute(
    location = EntityLocation.default,
    id = UUID.randomUUID().toString,
    name = name,
    description = name,
    tags = Seq.empty,
    metadata = Map.empty,
    enabled = true,
    debugFlow = false,
    capture = false,
    exportReporting = false,
    frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath(domain))),
    backend = NgBackend.empty.copy(targets = Seq(NgTarget.default)),
    plugins = NgPlugins(Seq(NgPluginInstance(plugin = s"cp:${plugin.getName}", config = NgPluginInstanceConfig(config)))),
  )

  lazy val setup: Unit = {
    val all = Seq(typesafe, e401, e422, e429, e529, openrouter, cloudflare, pinned)
    all.foreach(m => assert(client.forLlmEntity("decision-models").upsertEntity(m).awaitf(10.seconds).createdOrUpdated, s"${m.name} should be saved"))
    val routes = client.forEntity("proxy.otoroshi.io", "v1", "routes")
    routes.upsertEntity(route("decisions plugin", "decisions.oto.tools", classOf[DecisionModels], Json.obj("refs" -> Json.arr(typesafe.id)))).awaitf(10.seconds)
    routes.upsertEntity(route("decisions unified", "unified-decisions.oto.tools", classOf[OpenAiCompatApi], Json.obj("decision_model_refs" -> JsArray(all.map(m => JsString(m.id)))))).awaitf(10.seconds)
    await(10.seconds)
  }

  def post(url: String, body: JsValue) = client.call("POST", url, Map.empty, Some(body)).awaitf(30.seconds)

  test("a request is checked on its structure, the question types being the provider's business") {
    assertEquals(DecisionRequests.issues(request), Seq.empty)
    assertEquals(DecisionRequests.issues(JsString("nope")).map(_.select("type").asString), Seq("dict_type"))
    val missing = DecisionRequests.issues(Json.obj("model" -> jev))
    assertEquals(missing.map(_.select("loc").as[Seq[String]]), Seq(Seq("body", "state"), Seq("body", "questions")))
    assertEquals(DecisionRequests.issues(Json.obj("state" -> "x", "questions" -> Json.obj())).map(_.select("type").asString), Seq("too_short"))
    val untyped = DecisionRequests.issues(Json.obj("state" -> "x", "questions" -> Json.obj("q" -> Json.obj("criteria" -> Json.obj()))))
    assertEquals(untyped.map(_.select("loc").as[Seq[String]].last), Seq("type", "instructions"))
    // a type the gateway never heard of goes through: the provider may well know it
    val future = Json.obj("state" -> "x", "questions" -> Json.obj("q" -> Json.obj("type" -> "rank", "instructions" -> "order them")))
    assertEquals(DecisionRequests.issues(future), Seq.empty)
    // the emulation computes the answers itself, and only knows the three types
    assertEquals(DecisionRequests.issues(future, strict = true).map(_.select("type").asString), Seq("literal_error"))
    val oneOption = Json.obj("state" -> "x", "questions" -> Json.obj("q" -> Json.obj("type" -> "choice", "instructions" -> "pick", "criteria" -> Json.obj("a" -> "only one"))))
    assertEquals(DecisionRequests.issues(oneOption), Seq.empty)
    assertEquals(DecisionRequests.issues(oneOption, strict = true).size, 1)
  }

  test("answers are computed from probabilities with the formulas of the api") {
    assertEquals(DecisionAnswers.noul(0.953), Json.obj("type" -> "noul", "noul" -> 0.953))
    // the example of the TypeSafe documentation: 0.85 on three options is a confidence of (0.85 - 1/3) / (1 - 1/3)
    val choice = DecisionAnswers.choice(Seq("billing" -> 0.15, "technical" -> 0.85, "sales" -> 0.0))
    assertEquals(choice.select("choice").asString, "technical")
    assertEquals(choice.select("confidence").as[BigDecimal], BigDecimal("0.775"))
    assertEquals(choice.select("probabilities").as[Map[String, BigDecimal]], Map("billing" -> BigDecimal("0.15"), "technical" -> BigDecimal("0.85"), "sales" -> BigDecimal("0")))
    // and its score: 0.57 on level 1 and 0.43 on level 2 is 1.43, with a confidence of 1 - 0.43 / (2/3)
    val score = DecisionAnswers.score(Seq(JsString("Minor"), JsString("Degraded"), JsString("Blocking")), Seq(0.0, 0.57, 0.43))
    assertEquals(score.select("score").as[BigDecimal], BigDecimal("1.43"))
    assertEquals(score.select("confidence").as[BigDecimal], BigDecimal("0.355"))
    assertEquals(score.select("legend").as[Map[String, String]], Map("0" -> "Minor", "1" -> "Degraded", "2" -> "Blocking"))
    // probabilities that do not sum to one are brought back to it, an even split when nothing was stated
    assertEquals(DecisionAnswers.normalized(Seq(2.0, 2.0)), Seq(0.5, 0.5))
    assertEquals(DecisionAnswers.normalized(Seq(0.0, 0.0)), Seq(0.5, 0.5))
    assertEquals(DecisionAnswers.choice(Seq("a" -> 0.0, "b" -> 0.0)).select("confidence").as[BigDecimal], BigDecimal("0"))
  }

  test("what a chat model states becomes System One answers") {
    val questions = Json.obj(
      "urgent" -> Json.obj("type" -> "noul", "instructions" -> "urgent ?"),
      "team" -> Json.obj("type" -> "choice", "instructions" -> "which team ?", "criteria" -> Json.obj("billing" -> "invoices", "technical" -> "bugs")),
      "severity" -> Json.obj("type" -> "score", "instructions" -> "how bad ?", "criteria" -> Json.arr("Minor", "Blocking")),
    )
    // fenced, as models like to answer
    val content = "```json\n{\"urgent\": {\"noul\": 0.9}, \"team\": {\"probabilities\": {\"technical\": 4, \"billing\": 1}}, \"severity\": {\"probabilities\": [0.25, 0.75]}}\n```"
    val answers = LlmDecisionModelClient.answersOf(content, questions)
    assert(answers.isRight, s"the answers should be read, got ${answers}")
    assertEquals(answers.toOption.get.at("urgent.noul").as[BigDecimal], BigDecimal("0.9"))
    assertEquals(answers.toOption.get.at("team.choice").asString, "technical")
    assertEquals(answers.toOption.get.at("team.probabilities.technical").as[BigDecimal], BigDecimal("0.8"))
    assertEquals(answers.toOption.get.at("severity.score").as[BigDecimal], BigDecimal("0.75"))
    assert(LlmDecisionModelClient.answersOf("I can't answer that.", questions).isLeft, "a text that is no json is an error, not a decision")
    assert(LlmDecisionModelClient.answersOf("{\"urgent\": {\"noul\": 0.9}}", questions).isLeft, "a question left unanswered is an error too")
  }

  test("the model of a request picks its decision model, slashes of model ids included") {
    val named = decisionModel("TypeSafe", "typesafe", Json.obj(), "jev-latest")
    val router = decisionModel("router", "openrouter", Json.obj(), "typesafe/jev-1.13")
    val entities = Seq(named, router)
    def resolve(body: JsObject) = DecisionModelsResolver.resolve(request ++ body, entities).map(r => (r.entity.name, r.model, r.body.select("model").asOptString))
    // no model: the first one, with its own model
    assertEquals(resolve(Json.obj()), Some(("TypeSafe", None, None)))
    // the model an entity is configured with wins over the entity its prefix happens to name
    assertEquals(resolve(Json.obj("model" -> "typesafe/jev-1.13")), Some(("router", Some("typesafe/jev-1.13"), Some("typesafe/jev-1.13"))))
    // `<entity>/<model>` otherwise, like the other model types
    assertEquals(resolve(Json.obj("model" -> "typesafe/jev-1.13.0")), Some(("TypeSafe", Some("jev-1.13.0"), Some("jev-1.13.0"))))
    assertEquals(resolve(Json.obj("model" -> "router/inception/mercury-decide")), Some(("router", Some("inception/mercury-decide"), Some("inception/mercury-decide"))))
    // `<entity>###<model>` cannot be read another way
    assertEquals(resolve(Json.obj("model" -> "router###typesafe/jev-1.13.0")), Some(("router", Some("typesafe/jev-1.13.0"), Some("typesafe/jev-1.13.0"))))
    assertEquals(resolve(Json.obj("model" -> s"${router.id}###_default")), Some(("router", None, None)))
    // a model no entity knows goes to the first one, whole
    assertEquals(resolve(Json.obj("model" -> "telnyx/decision-flash")), Some(("TypeSafe", Some("telnyx/decision-flash"), Some("telnyx/decision-flash"))))
    // `provider` as a string picks the entity and never leaves the gateway, as an object it is OpenRouter's
    val byName = DecisionModelsResolver.resolve(request ++ Json.obj("provider" -> "router", "model" -> "x"), entities).get
    assertEquals(byName.entity.name, "router")
    assertEquals(byName.body.select("provider").asOpt[JsValue], None)
    val routing = DecisionModelsResolver.resolve(request ++ Json.obj("provider" -> Json.obj("order" -> Json.arr("TypeSafe"))), entities).get
    assertEquals(routing.body.select("provider").asOpt[JsObject], Some(Json.obj("order" -> Json.arr("TypeSafe"))))
    assertEquals(DecisionModelsResolver.resolve(request, Seq.empty), None)
  }

  test("errors are told apart, and answered with the status a TypeSafe client expects") {
    val upstream = DecisionErrors.upstream(429, Json.obj("detail" -> "slow down"), Some("3"))
    assertEquals(DecisionErrors.classify(upstream), DecisionErrors.Kind.Upstream(429, Json.obj("detail" -> "slow down"), Some("3")))
    val budget = Json.obj("error" -> "no more budget", "budget_exceeded" -> true)
    val notBillable = RequiredCosts.error(Some("some-model"))
    assertEquals(DecisionModels.resultOf(upstream).header.status, 429)
    assertEquals(DecisionModels.resultOf(upstream).header.headers.get("Retry-After"), Some("3"))
    // a budget is not a rate limit: a 429 would be retried against a budget that is not coming back
    assertEquals(DecisionModels.resultOf(budget).header.status, 402)
    assertEquals(DecisionModels.resultOf(ModelConstraints.denied).header.status, 403)
    assertEquals(DecisionModels.resultOf(notBillable).header.status, 403)
    assertEquals(DecisionModels.resultOf(Json.obj("error" -> "boom")).header.status, 500)
    // only a technical failure is worth asking another model
    assert(DecisionErrors.retryable(upstream))
    assert(DecisionErrors.retryable(DecisionErrors.upstream(529, JsString("overloaded"))))
    assert(DecisionErrors.retryable(DecisionErrors.upstream(408, JsNull)))
    assert(!DecisionErrors.retryable(DecisionErrors.upstream(422, Json.obj())))
    assert(!DecisionErrors.retryable(DecisionErrors.upstream(401, Json.obj())))
    assert(!DecisionErrors.retryable(budget))
    assert(!DecisionErrors.retryable(ModelConstraints.denied))
    assert(!DecisionErrors.retryable(notBillable))
  }

  test("an entity survives a json round trip, and every provider has a client") {
    val entity = typesafe.copy(fallbackRef = Some("decision-model_other"), fallbackModel = Some("clef-flash"))
    assertEquals(DecisionModel.format.reads(entity.json).asOpt, Some(entity))
    assertEquals(DecisionModel.format.reads(typesafe.json).get.fallbackRef, None)
    given env: Env = otoroshi.env
    DecisionModel.supportedProviders.foreach { kind =>
      val connection = DecisionModel.defaultConfig(kind).select("connection").as[JsObject] ++ Json.obj("provider" -> "some-text-provider")
      val built = decisionModel(kind, kind, connection, "a-model").getDecisionModelClient()
      assert(built.isDefined, s"'${kind}' should have a client")
    }
    // nothing to call without what the provider needs to be reached
    assert(decisionModel("no account", "cloudflare", Json.obj(), "clef").getDecisionModelClient().isEmpty)
    assert(decisionModel("no url", "systemone-compatible", Json.obj(), "laya").getDecisionModelClient().isEmpty)
    assert(decisionModel("no provider", "llm-emulation", Json.obj(), "").getDecisionModelClient().isEmpty)
    assert(decisionModel("unknown", "nope", Json.obj(), "x").getDecisionModelClient().isEmpty)
  }

  test("the dedicated plugin forwards the request as it is and gives the answers back") {
    setup
    val resp = post(s"http://decisions.oto.tools:${port}/v1/systemone", request)
    assertEquals(resp.status, 200, s"status should be 200, got ${resp.body}")
    assertEquals(resp.json.select("answers").as[JsObject], answer(jev).select("answers").as[JsObject])
    assertEquals(resp.json.select("model").asString, jev)
    assertEquals(resp.json.at("usage.input_tokens").as[Long], inputTokens)
    assertEquals(resp.json.select("costs").asOpt[JsValue], None, "nothing but the System One body unless costs are asked for")
    // the provider got the state and the questions of the caller, and the model of the entity
    assertEquals(okBody.get().select("state").asOpt[JsValue], request.select("state").asOpt[JsValue])
    assertEquals(okBody.get().select("questions").asOpt[JsValue], request.select("questions").asOpt[JsValue])
    assertEquals(okBody.get().select("model").asString, jev)
  }

  // `/decisions` is the decisions api of OpenAI (suite `OpenAiDecisionsSuite`)
  test("the unified api serves decisions on /systemone, and bills them per input token") {
    setup
    val systemone = post(s"http://unified-decisions.oto.tools:${port}/v1/systemone?embed_costs=true", request ++ Json.obj("model" -> "jev-latest"))
    assertEquals(systemone.status, 200, s"status should be 200, got ${systemone.body}")
    assertEquals(systemone.json.at("answers.urgent.noul").as[BigDecimal], BigDecimal("0.95"))
    assertEquals(okBody.get().select("model").asString, "jev-latest", "the model of the request is the one asked to the provider")
    assertEquals(systemone.json.at("costs.total_cost").asOpt[BigDecimal], expectedCost.some, s"unexpected costs: ${systemone.body}")
  }

  test("a request that is not a System One one is refused with a 422, before any provider is called") {
    setup
    val before = okCalls.get()
    val resp = post(s"http://decisions.oto.tools:${port}/v1/systemone", Json.obj("model" -> jev, "state" -> "x"))
    assertEquals(resp.status, 422, s"status should be 422, got ${resp.body}")
    assertEquals(resp.json.select("detail").as[Seq[JsObject]].map(_.select("loc").as[Seq[String]]), Seq(Seq("body", "questions")))
    val notJson = client.client.url(s"http://decisions.oto.tools:${port}/v1/systemone").withHttpHeaders("Content-Type" -> "application/json").post("not json").awaitf(30.seconds)
    assertEquals(notJson.status, 422)
    assertEquals(notJson.json.at("detail").as[Seq[JsObject]].head.select("type").asString, "json_invalid")
    assertEquals(okCalls.get(), before, "the provider should not have been called")
  }

  test("what the provider refuses comes back as it said it: status, body and retry-after") {
    setup
    def call(model: DecisionModel) = post(s"http://unified-decisions.oto.tools:${port}/v1/systemone", request ++ Json.obj("model" -> s"${model.id}###${jev}"))
    val unauthorized = call(e401)
    assertEquals(unauthorized.status, 401)
    assertEquals(unauthorized.json.at("detail.error_type").asString, "authentication_error")
    val invalid = call(e422)
    assertEquals(invalid.status, 422)
    assertEquals(invalid.json.select("detail").as[Seq[JsObject]].head.select("type").asString, "too_long")
    val limited = call(e429)
    assertEquals(limited.status, 429)
    assertEquals(limited.header("Retry-After"), Some("7"), "a client has to know when to come back")
    assertEquals(limited.json.at("detail.error_type").asString, "rate_limit_error")
    // an html page is still an error the client can read
    val overloaded = call(e529)
    assertEquals(overloaded.status, 529)
    assertEquals(overloaded.json.at("detail.message").asString, "<html>overloaded</html>")
    assertEquals(errCalls.get(), 4)
  }

  test("OpenRouter gets its routing preferences, and the cost it reports is the one billed") {
    setup
    val routing = Json.obj("order" -> Json.arr("TypeSafe"), "allow_fallbacks" -> false)
    val resp = post(s"http://unified-decisions.oto.tools:${port}/v1/systemone?embed_costs=true", request ++ Json.obj("model" -> "typesafe/jev-1.13", "provider" -> routing))
    assertEquals(resp.status, 200, s"status should be 200, got ${resp.body}")
    assertEquals(routerBody.get().select("provider").asOpt[JsObject], Some(routing))
    assertEquals(routerBody.get().select("model").asString, "typesafe/jev-1.13")
    // what OpenRouter adds to the api is kept
    assertEquals(resp.json.select("id").asString, "gen-dec-1")
    assertEquals(resp.json.select("provider").asString, "TypeSafe")
    assertEquals(resp.json.at("usage.cost").as[BigDecimal], BigDecimal("0.000019992"))
    assertEquals(resp.json.at("costs.total_cost").as[BigDecimal], BigDecimal("0.000019992"))
    assertEquals(resp.json.at("costs.source").asString, CostsOutput.sourceProvider)
  }

  test("Cloudflare is called with the model in the url, and its envelope is opened") {
    setup
    given env: Env = otoroshi.env
    val attrs = TypedMap.empty
    val body = request ++ Json.obj("provider" -> Json.obj("order" -> Json.arr("x")), "images" -> Json.arr("aGV5"))
    val resp = ext.states.decisionModel(cloudflare.id).flatMap(_.getDecisionModelClient()).get
      .decide(DecisionModelClientInputOptions.format.reads(body).get, body, attrs)(using ec, otoroshi.env)
      .awaitf(30.seconds)
    assert(resp.isRight, s"the call should succeed, got ${resp}")
    assertEquals(cloudflareUri.get(), "/client/v4/accounts/acc123/ai/run/@cf/cloudflare/clef-flash")
    assertEquals(cloudflareBody.get().select("model").asString, "clef-flash")
    assertEquals(cloudflareBody.get().select("images").asOpt[JsArray], Some(Json.arr("aGV5")))
    assertEquals(cloudflareBody.get().select("provider").asOpt[JsValue], None, "the routing preferences of OpenRouter mean nothing there")
    assertEquals(resp.toOption.get.answers, answer("clef-flash").select("answers").as[JsObject])
    assertEquals(resp.toOption.get.metadata.usage.input, inputTokens)
    // priced from the custom grid
    assert(attrs.get(ChatClientWithCostsTracking.key).exists(_.totalCost > 0), "a Clef call should have a cost")
  }

  test("an entity can keep its model whatever the client asks for, so a model is swapped without touching the clients") {
    setup
    // what a TypeSafe sdk sends when nothing is said: its own default model
    val resp = post(s"http://unified-decisions.oto.tools:${port}/v1/systemone?embed_costs=true", request ++ Json.obj("model" -> s"${pinned.id}###jev-latest"))
    assertEquals(resp.status, 200, s"status should be 200, got ${resp.body}")
    assertEquals(cloudflareUri.get(), "/client/v4/accounts/acc123/ai/run/@cf/cloudflare/clef")
    assertEquals(cloudflareBody.get().select("model").asString, "clef")
    // billed as the model that was served: 350 input tokens of Clef
    assertEquals(resp.json.at("costs.total_cost").as[BigDecimal], BigDecimal(inputTokens) * BigDecimal("0.00000024"))
  }
}
