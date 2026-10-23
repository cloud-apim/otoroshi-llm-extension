package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.catalog.{ModelEndpoints, ModelsMetadata}
import com.cloud.apim.otoroshi.extensions.aigateway.decorators.ModelConstraints
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiProvider, DecisionModel}
import com.cloud.apim.otoroshi.extensions.aigateway.guardrails.DecisionGuardrail
import com.cloud.apim.otoroshi.extensions.aigateway.providers.{OpenAiDecisionRequests, OpenAiDecisions}
import com.cloud.apim.otoroshi.extensions.aigateway.{DecisionAnswers, DecisionErrors, DecisionModelClientInputOptions, DecisionResponse, DecisionResponseMetadata, DecisionResponseMetadataUsage, LlmExtensionOneOtoroshiServerPerSuite}
import otoroshi.models.EntityLocation
import otoroshi.next.models.*
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.{OpenAICompatDecisions, OpenAiCompatApi}
import play.api.libs.json.*
import reactor.core.publisher.Mono
import reactor.netty.http.server.HttpServerResponse

import java.util.UUID
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.duration.DurationInt

// The decisions api of OpenAI, served by the decision models of the gateway: a request is asked as a System One
// one, and its answers, usage and errors come back in the OpenAI format.
class OpenAiDecisionsSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  val jev = "jev-1.13.0"
  // priced at 4.2e-8 per input token in the bundled grid, the output being free
  val inputTokens = 350L
  val expectedCost = BigDecimal(inputTokens) * BigDecimal("0.000000042")
  val image = "data:image/png;base64,aGV5"

  val request: JsObject = Json.obj(
    "model" -> jev,
    "input" -> Json.arr(Json.obj("role" -> "user", "content" -> Json.arr(
      Json.obj("type" -> "input_text", "text" -> "The package arrived with a broken screen."),
      Json.obj("type" -> "input_text", "text" -> "I want my money back."),
    ))),
    "questions" -> Json.arr(
      Json.obj("type" -> "predicate", "name" -> "damaged", "instructions" -> "Does the customer report a damaged item?"),
      Json.obj("type" -> "choice", "name" -> "refund", "instructions" -> "Should we refund?", "choices" -> Json.arr(
        Json.obj("value" -> true, "description" -> "Refund the order"),
        Json.obj("value" -> false),
        Json.obj("value" -> "escalate", "description" -> "Ask a human"),
      )),
      Json.obj("type" -> "score", "instructions" -> "How angry is the customer?", "levels" -> Json.arr(
        Json.obj("label" -> "calm"),
        Json.obj("label" -> "upset", "description" -> "complains but stays polite"),
        Json.obj("label" -> "furious"),
      )),
    ),
  )

  private def send(response: HttpServerResponse, status: Int, body: String, headers: Map[String, String] = Map.empty) = {
    headers.foldLeft(response.status(status).addHeader("Content-Type", "application/json")) { case (r, (k, v)) => r.addHeader(k, v) }
      .sendString(Mono.just(body)).`then`()
  }

  // a System One server answering whatever it is asked: the most likely outcome is always the last one
  def answersTo(body: JsValue): JsObject = JsObject(body.select("questions").as[JsObject].fields.map {
    case (name, question) => name -> (question.select("type").asString match {
      case "noul" => DecisionAnswers.noul(0.9)
      case "choice" =>
        val options = question.select("criteria").as[JsObject].keys.toSeq
        DecisionAnswers.choice(options.zipWithIndex.map { case (o, i) => o -> (if (i == options.size - 1) 0.7 else 0.3 / (options.size - 1)) })
      case _ =>
        val levels = question.select("criteria").as[JsArray].value.toSeq
        DecisionAnswers.score(levels, levels.indices.map(i => if (i == levels.size - 1) 0.8 else 0.2 / (levels.size - 1)))
    })
  })

  def answer(body: JsValue, model: String): JsObject = Json.obj(
    "model" -> model,
    "answers" -> answersTo(body),
    "usage" -> Json.obj("input_tokens" -> inputTokens, "output_tokens" -> 0),
  )

  val okCalls = new AtomicInteger(0)
  val okBody = new AtomicReference[JsValue](JsNull)
  val (okPort, _) = createTestServerWithRoutes("openai-decisions-systemone", routes => routes
    .post("/v1/systemone", (req, response) => {
      req.receive().aggregate().asString().flatMap { body =>
        okCalls.incrementAndGet()
        okBody.set(Json.parse(body))
        send(response, 200, answer(Json.parse(body), Json.parse(body).select("model").asString).stringify)
      }
    })
  )

  val errCalls = new AtomicInteger(0)
  val (errPort, _) = createTestServerWithRoutes("openai-decisions-errors", routes => routes
    .post("/e429/systemone", (req, response) => {
      req.receive().aggregate().asString().flatMap { _ =>
        errCalls.incrementAndGet()
        send(response, 429, Json.obj("detail" -> Json.obj("error_type" -> "rate_limit_error", "message" -> "slow down")).stringify, Map("Retry-After" -> "7"))
      }
    })
  )

  // Workers AI looks at pictures, given as data urls
  val cloudflareBody = new AtomicReference[JsValue](JsNull)
  val (cloudflarePort, _) = createTestServer("openai-decisions-cloudflare", (req, response) => {
    req.receive().aggregate().asString().flatMap { body =>
      cloudflareBody.set(Json.parse(body))
      send(response, 200, Json.obj("result" -> answer(Json.parse(body), "clef-flash"), "success" -> true, "errors" -> Json.arr()).stringify)
    }
  })

  // the decisions api of OpenAI: the last outcome is the most likely, and the question named `refuse` is refused
  def openAiAnswersTo(body: JsValue): Seq[JsObject] = body.select("questions").as[Seq[JsObject]].map { question =>
    val name = question.select("name").asOpt[String].map(JsString.apply).getOrElse(JsNull).asValue
    question.select("type").asString match {
      case _ if question.select("name").asOpt[String].contains("refuse") => Json.obj("type" -> "refusal", "name" -> name)
      case "predicate" => Json.obj("type" -> "predicate", "name" -> name, "probability" -> 0.9)
      case "choice" =>
        val values = question.select("choices").as[Seq[JsObject]].map(_.select("value").as[JsValue])
        Json.obj("type" -> "choice", "name" -> name, "choice" -> values.last, "confidence" -> 0.55,
          "probabilities" -> values.zipWithIndex.map { case (v, i) => Json.obj("value" -> v, "probability" -> (if (i == values.size - 1) 0.7 else 0.3 / (values.size - 1))) })
      case _ =>
        val labels = question.select("levels").as[Seq[JsObject]].map(_.select("label").asString)
        Json.obj("type" -> "score", "name" -> name, "score" -> 1.7, "confidence" -> 0.4,
          "probabilities" -> labels.zipWithIndex.map { case (l, i) => Json.obj("label" -> l, "value" -> i, "probability" -> (if (i == labels.size - 1) 0.8 else 0.2 / (labels.size - 1))) })
    }
  }

  val openaiBody = new AtomicReference[JsValue](JsNull)
  val (openaiPort, _) = createTestServerWithRoutes("openai-decisions-openai", routes => routes
    .post("/v1/decisions", (req, response) => {
      req.receive().aggregate().asString().flatMap { body =>
        openaiBody.set(Json.parse(body))
        send(response, 200, Json.obj(
          "model" -> Json.parse(body).select("model").asString,
          "answers" -> openAiAnswersTo(Json.parse(body)),
          "usage" -> Json.obj(
            "input_tokens" -> inputTokens,
            "input_tokens_details" -> Json.obj("cached_tokens" -> 12, "cache_write_tokens" -> 0),
            "output_tokens" -> 0,
            "output_tokens_details" -> Json.obj("reasoning_tokens" -> 0),
            "total_tokens" -> inputTokens,
          ),
        ).stringify)
      }
    })
  )

  def decisionModel(name: String, provider: String, connection: JsObject, model: String): DecisionModel = DecisionModel(
    EntityLocation.default, s"decision-model_${UUID.randomUUID()}", name, "", Seq.empty, Map.empty, provider,
    Json.obj("connection" -> (Json.obj("token" -> "xxx", "timeout" -> 30000) ++ connection), "options" -> Json.obj("model" -> model)),
  )

  lazy val typesafe = decisionModel("jev", "typesafe", Json.obj("base_url" -> s"http://localhost:${okPort}/v1"), jev)
  lazy val e429 = decisionModel("e429", "typesafe", Json.obj("base_url" -> s"http://localhost:${errPort}/e429"), jev)
  lazy val cloudflare = decisionModel("workers", "cloudflare", Json.obj("base_url" -> s"http://localhost:${cloudflarePort}/client/v4", "account_id" -> "acc123"), "@cf/cloudflare/clef-flash")
  lazy val openai = decisionModel("luna", "openai", Json.obj("base_url" -> s"http://localhost:${openaiPort}/v1"), "gpt-6-luna")
  // a relay of Jev, which is not known to take pictures
  lazy val router = decisionModel("router", "openrouter", Json.obj("base_url" -> s"http://localhost:${okPort}/v1"), "typesafe/jev-1.13")

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
    val all = Seq(typesafe, e429, cloudflare, openai, router)
    all.foreach(m => assert(client.forLlmEntity("decision-models").upsertEntity(m).awaitf(10.seconds).createdOrUpdated, s"${m.name} should be saved"))
    val routes = client.forEntity("proxy.otoroshi.io", "v1", "routes")
    routes.upsertEntity(route("openai decisions plugin", "openai-decisions.oto.tools", classOf[OpenAICompatDecisions], Json.obj("refs" -> Json.arr(typesafe.id)))).awaitf(10.seconds)
    routes.upsertEntity(route("openai decisions unified", "unified-openai-decisions.oto.tools", classOf[OpenAiCompatApi], Json.obj("decision_model_refs" -> JsArray(all.map(m => JsString(m.id)))))).awaitf(10.seconds)
    await(10.seconds)
  }

  def post(url: String, body: JsValue) = client.call("POST", url, Map.empty, Some(body)).awaitf(30.seconds)

  def read(body: JsObject): OpenAiDecisions.Request = {
    val read = OpenAiDecisions.read(body)
    assert(read.isRight, s"the request should be read, got ${read}")
    read.toOption.get
  }

  test("an OpenAI request becomes a System One one") {
    val systemOne = read(request).systemOne
    assertEquals(systemOne.select("model").asString, jev)
    // the texts of the input are the state, in their order
    assertEquals(systemOne.select("state").asString, "The package arrived with a broken screen.\n\nI want my money back.")
    assertEquals(systemOne.select("images").asOpt[JsArray], None)
    // a question keeps its name, one without a name gets one
    assertEquals(systemOne.select("questions").as[JsObject].keys.toSeq, Seq("damaged", "refund", "question_2"))
    assertEquals(systemOne.at("questions.damaged").as[JsObject], Json.obj("type" -> "noul", "instructions" -> "Does the customer report a damaged item?"))
    // the choices are the options, described by their description or by themselves
    assertEquals(systemOne.at("questions.refund.type").asString, "choice")
    assertEquals(systemOne.at("questions.refund.criteria").as[JsObject], Json.obj("true" -> "Refund the order", "false" -> "false", "escalate" -> "Ask a human"))
    // the levels are the criteria of a score, from the lowest to the highest
    assertEquals(systemOne.at("questions.question_2.criteria").as[Seq[String]], Seq("calm", "upset: complains but stays polite", "furious"))
    // a string input is the state as it is, the pictures go along as data urls, and a name no System One server
    // takes, or taken already, is replaced
    val other = read(Json.obj(
      "input" -> Json.arr(Json.obj("role" -> "user", "type" -> "message", "content" -> Json.arr(Json.obj("type" -> "input_image", "image_url" -> image, "detail" -> "low")))),
      "questions" -> Json.arr(
        Json.obj("type" -> "predicate", "name" -> "is it broken ?", "instructions" -> "broken ?"),
        Json.obj("type" -> "predicate", "name" -> "same", "instructions" -> "one"),
        Json.obj("type" -> "predicate", "name" -> "same", "instructions" -> "two"),
      ),
    )).systemOne
    assertEquals(other.select("images").as[Seq[String]], Seq(image))
    assertEquals(other.select("model").asOpt[String], None, "no model: the one of the decision model")
    assertEquals(other.select("questions").as[JsObject].keys.toSeq, Seq("question_0", "same", "question_2"))
    assertEquals(read(Json.obj("input" -> "hello", "questions" -> Json.arr(Json.obj("type" -> "predicate", "instructions" -> "x")))).systemOne.select("state").asString, "hello")
    // the json of a user is read as text, as OpenAI reads it, 32 levels deep at most
    val deepest = (1 to 31).foldLeft[JsValue](Json.obj()) { case (acc, _) => Json.obj("a" -> acc) }
    val json = read(Json.obj(
      "input" -> Json.arr(Json.obj("role" -> "user", "content" -> Json.arr(
        Json.obj("type" -> "input_text", "text" -> "Our orders:"),
        Json.obj("type" -> "input_json", "data" -> Json.arr(Json.obj("id" -> 42, "status" -> "lost"))),
        Json.obj("type" -> "input_json", "data" -> deepest),
      ))),
      "questions" -> Json.arr(Json.obj("type" -> "predicate", "instructions" -> "x")),
    )).systemOne
    assertEquals(json.select("state").asString, s"Our orders:\n\n[{\"id\":42,\"status\":\"lost\"}]\n\n${Json.stringify(deepest)}")
    // a transcript says who wrote what, and where each picture was: the ones of the instructions come after
    val transcript = read(Json.obj(
      "input" -> Json.arr(
        Json.obj("role" -> "developer", "content" -> "You sort the tickets of a shop."),
        Json.obj("role" -> "user", "content" -> Json.arr(
          Json.obj("type" -> "input_text", "text" -> "Where is my order?"),
          Json.obj("type" -> "input_json", "data" -> Json.obj("order" -> 42)),
        )),
        Json.obj("type" -> "function_call", "call_id" -> "c1", "namespace" -> "shop", "name" -> "find_order", "arguments" -> "{\"id\":42}", "status" -> "completed"),
        Json.obj("type" -> "function_call_output", "call_id" -> "c1", "output" -> Json.arr(
          Json.obj("type" -> "input_text", "text" -> "lost"),
          Json.obj("type" -> "input_image", "image_url" -> "https://shop.tld/42.png"),
        )),
        Json.obj("type" -> "custom_tool_call", "call_id" -> "c2", "name" -> "grep", "input" -> "order 42"),
        Json.obj("type" -> "custom_tool_call_output", "call_id" -> "c2", "output" -> "no match"),
        Json.obj("role" -> "assistant", "type" -> "message", "phase" -> "final_answer", "content" -> Json.arr(
          Json.obj("type" -> "output_text", "text" -> "Let me check."),
          Json.obj("type" -> "refusal", "refusal" -> "I cannot say more."),
        )),
        Json.obj("role" -> "system", "content" -> Json.arr(Json.obj("type" -> "input_text", "text" -> "Be brief.", "prompt_cache_breakpoint" -> Json.obj("mode" -> "explicit")))),
      ),
      "questions" -> Json.arr(
        Json.obj("type" -> "predicate", "name" -> "lost", "instructions" -> "Is the order lost?"),
        Json.obj("type" -> "predicate", "name" -> "same", "instructions" -> Json.arr(
          Json.obj("type" -> "input_text", "text" -> "Is it this one?"),
          Json.obj("type" -> "input_image", "image_url" -> image),
          Json.obj("type" -> "input_json", "data" -> Json.obj("ref" -> "A-42")),
        )),
      ),
      "prompt_cache_key" -> "shop",
      "prompt_cache_options" -> Json.obj("mode" -> "explicit", "ttl" -> "30m"),
    )).systemOne
    assertEquals(transcript.select("state").asString, Seq(
      "[developer]\nYou sort the tickets of a shop.",
      "[user]\nWhere is my order?\n\n{\"order\":42}",
      "[function call shop.find_order (c1)]\n{\"id\":42}",
      "[function call output (c1)]\nlost\n\n[image 1]",
      "[custom tool call grep (c2)]\norder 42",
      "[custom tool call output (c2)]\nno match",
      "[assistant]\nLet me check.\n\nI cannot say more.",
      "[system]\nBe brief.",
    ).mkString("\n\n"))
    assertEquals(transcript.select("images").as[Seq[String]], Seq("https://shop.tld/42.png", image))
    assertEquals(transcript.at("questions.lost.instructions").asString, "Is the order lost?")
    assertEquals(transcript.at("questions.same.instructions").asString, "Is it this one?\n\n[image 2]\n\n{\"ref\":\"A-42\"}")
  }

  test("a request that cannot be read is refused, with the parameter at fault") {
    def param(body: JsValue): Option[String] = {
      val read = OpenAiDecisions.read(body)
      assert(read.isLeft, s"the request should be refused: ${body}")
      assertEquals(read.left.toOption.get.at("error.type").asString, "invalid_request_error")
      read.left.toOption.get.at("error.param").asOpt[String]
    }
    val question = Json.obj("type" -> "predicate", "instructions" -> "x")
    def withQuestion(q: JsObject) = Json.obj("input" -> "x", "questions" -> Json.arr(question, q))
    assertEquals(param(JsString("nope")), None)
    assertEquals(param(Json.obj("questions" -> Json.arr(question))), Some("input"))
    def withInput(items: JsValue*) = Json.obj("input" -> JsArray(items), "questions" -> Json.arr(question))
    def user(parts: JsValue*) = Json.obj("role" -> "user", "content" -> JsArray(parts))
    assertEquals(param(withInput(Json.obj("role" -> "tool", "content" -> "x"))), Some("input[0].role"))
    assertEquals(param(withInput(Json.obj("type" -> "reasoning", "summary" -> Json.arr()))), Some("input[0].type"))
    // a developer writes texts, an assistant answers texts or refusals
    assertEquals(param(withInput(Json.obj("role" -> "system", "content" -> Json.arr(Json.obj("type" -> "input_image", "image_url" -> image))))), Some("input[0].content[0].type"))
    assertEquals(param(withInput(Json.obj("role" -> "assistant", "content" -> Json.arr(Json.obj("type" -> "input_text", "text" -> "x"))))), Some("input[0].content[0].type"))
    assertEquals(param(withInput(user(Json.obj("type" -> "input_file", "file_id" -> "f")))), Some("input[0].content[0].type"))
    // a picture is a data url or an address, never a file
    assertEquals(param(withInput(user(Json.obj("type" -> "input_image", "file_id" -> "f")))), Some("input[0].content[0].image_url"))
    assertEquals(param(withInput(user(Json.obj("type" -> "input_image", "image_url" -> "file-123")))), Some("input[0].content[0].image_url"))
    assertEquals(param(withInput(user(Json.obj("type" -> "input_image", "image_url" -> image, "detail" -> "medium")))), Some("input[0].content[0].detail"))
    // json is an object or an array of objects, 32 levels deep at most
    assertEquals(param(withInput(user(Json.obj("type" -> "input_json", "data" -> "x")))), Some("input[0].content[0].data"))
    assertEquals(param(withInput(user(Json.obj("type" -> "input_json", "data" -> Json.arr(1, 2))))), Some("input[0].content[0].data"))
    val tooDeep = (1 to 32).foldLeft[JsValue](Json.obj()) { case (acc, _) => Json.obj("a" -> acc) }
    assertEquals(param(withInput(user(Json.obj("type" -> "input_json", "data" -> tooDeep)))), Some("input[0].content[0].data"))
    // a tool output answers a tool call of the input, wherever it is and whatever its kind, in the words of OpenAI
    assertEquals(param(withInput(Json.obj("type" -> "function_call_output", "call_id" -> "c1", "output" -> "x"))), Some("input[0].call_id"))
    assertEquals(OpenAiDecisions.read(withInput(Json.obj("type" -> "function_call_output", "call_id" -> "c1", "output" -> "x"))).left.toOption.map(_.at("error.message").asString),
      Some("A tool call output must reference a tool call in the input."))
    read(withInput(
      Json.obj("type" -> "function_call_output", "call_id" -> "c1", "output" -> "x"),
      Json.obj("type" -> "custom_tool_call", "call_id" -> "c1", "name" -> "grep", "input" -> "x"),
    ))
    assertEquals(param(withInput(Json.obj("type" -> "function_call", "call_id" -> "c1", "name" -> "f"))), Some("input[0].arguments"))
    assertEquals(param(withInput(Json.obj("type" -> "function_call", "call_id" -> "", "name" -> "f", "arguments" -> "{}"))), Some("input[0].call_id"))
    assertEquals(param(Json.obj("input" -> "x")), Some("questions"))
    assertEquals(param(Json.obj("input" -> "x", "questions" -> Json.arr())), Some("questions"))
    assertEquals(param(Json.obj("input" -> "x", "questions" -> JsArray((0 to OpenAiDecisions.MaxQuestions).map(_ => question)))), Some("questions"))
    assertEquals(param(withQuestion(Json.obj("type" -> "predicate", "name" -> 1, "instructions" -> "x"))), Some("questions[1].name"))
    assertEquals(param(withQuestion(Json.obj("type" -> "predicate", "instructions" -> Json.arr(Json.obj("type" -> "output_text", "text" -> "x"))))), Some("questions[1].instructions[0].type"))
    // two choices at least, said the way OpenAI says it
    assertEquals(param(withQuestion(Json.obj("type" -> "choice", "instructions" -> "x", "choices" -> Json.arr(Json.obj("value" -> "a"))))), Some("questions[1].choices"))
    def errorOf(body: JsValue): Option[(String, String)] = OpenAiDecisions.read(body).left.toOption.map(e => (e.at("error.message").asString, e.at("error.code").asString))
    assertEquals(errorOf(withQuestion(Json.obj("type" -> "choice", "instructions" -> "x", "choices" -> Json.arr(Json.obj("value" -> "a"))))), Some((
      "Invalid 'questions[1].choices': array too short. Expected an array with minimum length 2, but got an array with length 1 instead.", "array_below_min_length")))
    assertEquals(errorOf(Json.obj("input" -> "x", "questions" -> Json.arr())), Some((
      "Invalid 'questions': empty array. Expected an array with minimum length 1, but got an empty array instead.", "empty_array")))
    assertEquals(errorOf(Json.obj("input" -> "x", "questions" -> JsArray((0 to OpenAiDecisions.MaxQuestions).map(_ => question)))), Some((
      "Invalid 'questions': array too long. Expected an array with maximum length 400, but got an array with length 401 instead.", "array_above_max_length")))
    assertEquals(param(Json.obj("input" -> "x", "questions" -> Json.obj("q" -> question))), Some("questions"))
    assertEquals(param(withQuestion(Json.obj("type" -> "rank", "instructions" -> "x"))), Some("questions[1].type"))
    assertEquals(param(withQuestion(Json.obj("type" -> "predicate"))), Some("questions[1].instructions"))
    assertEquals(param(withQuestion(Json.obj("type" -> "choice", "instructions" -> "x"))), Some("questions[1].choices"))
    assertEquals(param(withQuestion(Json.obj("type" -> "choice", "instructions" -> "x", "choices" -> Json.arr(Json.obj("value" -> 1))))), Some("questions[1].choices[0].value"))
    assertEquals(param(withQuestion(Json.obj("type" -> "choice", "instructions" -> "x", "choices" -> Json.arr(Json.obj("value" -> "a"), Json.obj("value" -> "a"))))), Some("questions[1].choices"))
    // two choices for OpenAI, one option name for a System One server
    assertEquals(param(withQuestion(Json.obj("type" -> "choice", "instructions" -> "x", "choices" -> Json.arr(Json.obj("value" -> "true"), Json.obj("value" -> true))))), Some("questions[1].choices"))
    assertEquals(param(withQuestion(Json.obj("type" -> "score", "instructions" -> "x", "levels" -> Json.arr(Json.obj("description" -> "no label"))))), Some("questions[1].levels[0].label"))
  }

  test("the answers come back in the order of the questions, with the values they were asked with") {
    val asked = read(request)
    val systemOne = asked.systemOne
    val decision = DecisionResponse(
      model = jev,
      answers = answersTo(systemOne),
      metadata = DecisionResponseMetadata(DecisionResponseMetadataUsage(inputTokens, 0L)),
      raw = Json.obj("id" -> "dec-1", "usage" -> Json.obj("input_tokens" -> inputTokens, "output_tokens" -> 0, "cost" -> 0.001)),
    )
    val response = OpenAiDecisions.response(asked, decision, otoroshi.env)
    assertEquals(response.select("id").asString, "dec-1")
    assertEquals(response.select("model").asString, jev)
    val answers = response.select("answers").as[Seq[JsObject]]
    assertEquals(answers.map(_.select("type").asString), Seq("predicate", "choice", "score"))
    assertEquals(answers.head, Json.obj("type" -> "predicate", "name" -> "damaged", "probability" -> 0.9))
    // a boolean choice is a boolean again, the options are listed in the order of the request
    assertEquals(answers(1).select("name").asString, "refund")
    assertEquals(answers(1).select("choice").as[JsValue], JsString("escalate"))
    assertEquals(answers(1).select("probabilities").as[Seq[JsObject]].map(_.select("value").as[JsValue]), Seq(JsBoolean(true), JsBoolean(false), JsString("escalate")))
    assertEquals(answers(1).select("probabilities").as[Seq[JsObject]].map(_.select("probability").as[BigDecimal]), Seq(BigDecimal("0.15"), BigDecimal("0.15"), BigDecimal("0.7")))
    assert(answers(1).select("confidence").as[BigDecimal] > BigDecimal(0))
    // a score names its levels by their label, and their position
    assertEquals(answers(2).select("name").as[JsValue], JsNull)
    assertEquals(answers(2).select("score").as[BigDecimal], BigDecimal("1.7"))
    assertEquals(answers(2).select("probabilities").as[Seq[JsObject]], Seq(
      Json.obj("label" -> "calm", "value" -> 0, "probability" -> 0.1),
      Json.obj("label" -> "upset", "value" -> 1, "probability" -> 0.1),
      Json.obj("label" -> "furious", "value" -> 2, "probability" -> 0.8),
    ))
    // the usage of OpenAI, with what the provider added
    assertEquals(response.select("usage").as[JsObject] - "cost", Json.obj(
      "input_tokens" -> inputTokens,
      "input_tokens_details" -> Json.obj("cached_tokens" -> 0, "cache_write_tokens" -> 0),
      "output_tokens" -> 0,
      "output_tokens_details" -> Json.obj("reasoning_tokens" -> 0),
      "total_tokens" -> inputTokens,
    ))
    assertEquals(response.at("usage.cost").as[BigDecimal], BigDecimal("0.001"))
    // a question the model did not answer, or refused, is a refusal: the others are answered all the same
    val partial = OpenAiDecisions.response(asked, decision.copy(answers = decision.answers - "damaged" ++ Json.obj("refund" -> Json.obj("type" -> "refusal"))), otoroshi.env)
    assertEquals(partial.select("answers").as[Seq[JsObject]].map(_.select("type").asString), Seq("refusal", "refusal", "score"))
    assertEquals(partial.select("answers").as[Seq[JsObject]].head, Json.obj("type" -> "refusal", "name" -> "damaged"))
  }

  test("the errors of the decision models are said in the words of OpenAI") {
    val asked = read(request)
    val detail = OpenAiDecisions.errorOf(401, Json.obj("detail" -> Json.obj("error_type" -> "authentication_error", "message" -> "Cannot authenticate")), None)
    assertEquals(detail, OpenAiDecisions.error("Cannot authenticate", None, "authentication_error", Some("authentication_error")))
    // a request a System One server refused names the parameter of the OpenAI request
    val issues = OpenAiDecisions.errorOf(422, Json.obj("detail" -> Json.arr(Json.obj("type" -> "too_short", "loc" -> Json.arr("body", "questions", "question_2", "criteria"), "msg" -> "A score needs two to ten levels"))), asked.some)
    assertEquals(issues.at("error.param").asString, "questions[2].levels")
    assertEquals(issues.at("error.message").asString, "questions[2].levels: A score needs two to ten levels")
    assertEquals(issues.at("error.type").asString, "invalid_request_error")
    assertEquals(OpenAiDecisions.errorOf(529, JsString("<html>overloaded</html>"), None).at("error.message").asString, "<html>overloaded</html>")
    assertEquals(OpenAiDecisions.errorOf(529, JsString("x"), None).at("error.type").asString, "server_error")
    // an OpenAI server already says it the right way
    val openai = OpenAiDecisions.error("Incorrect API key provided", None, "invalid_request_error", Some("invalid_api_key"))
    assertEquals(OpenAiDecisions.errorOf(401, openai, None), openai)
    // with the statuses of the System One endpoint
    val limited = OpenAICompatDecisions.resultOf(DecisionErrors.upstream(429, Json.obj("detail" -> Json.obj("message" -> "slow down")), Some("3")))
    assertEquals(limited.header.status, 429)
    assertEquals(limited.header.headers.get("Retry-After"), Some("3"))
    assertEquals(OpenAICompatDecisions.resultOf(Json.obj("error" -> "no more budget", "budget_exceeded" -> true)).header.status, 402)
    assertEquals(OpenAICompatDecisions.resultOf(ModelConstraints.denied).header.status, 403)
    assertEquals(OpenAICompatDecisions.resultOf(Json.obj("error" -> "boom")).header.status, 500)
  }

  test("the unified api serves the OpenAI decisions api on /decisions, billed per input token") {
    setup
    val resp = post(s"http://unified-openai-decisions.oto.tools:${port}/v1/decisions?embed_costs=true", request)
    assertEquals(resp.status, 200, s"status should be 200, got ${resp.body}")
    // the provider was asked a System One request
    assertEquals(okBody.get().select("state").asString, "The package arrived with a broken screen.\n\nI want my money back.")
    assertEquals(okBody.get().select("questions").as[JsObject].keys.toSeq, Seq("damaged", "refund", "question_2"))
    assertEquals(okBody.get().select("model").asString, jev)
    // and its answers came back in the OpenAI format
    assertEquals(resp.json.select("model").asString, jev)
    assertEquals(resp.json.select("answers").as[Seq[JsObject]].map(a => (a.select("type").asString, a.select("name").asOpt[String])), Seq(
      ("predicate", Some("damaged")), ("choice", Some("refund")), ("score", None),
    ))
    assertEquals(resp.json.at("answers").as[Seq[JsObject]].apply(1).select("probabilities").as[Seq[JsObject]].head.select("value").as[JsValue], JsBoolean(true))
    assertEquals(resp.json.at("usage.total_tokens").as[Long], inputTokens)
    assertEquals(resp.json.at("usage.output_tokens").as[Long], 0L)
    assertEquals(resp.json.at("costs.total_cost").asOpt[BigDecimal], expectedCost.some, s"unexpected costs: ${resp.body}")
    // a request it cannot read never reaches a provider
    val before = okCalls.get()
    val invalid = post(s"http://unified-openai-decisions.oto.tools:${port}/v1/decisions", request ++ Json.obj("questions" -> Json.arr(Json.obj("type" -> "rank", "instructions" -> "x"))))
    assertEquals(invalid.status, 400)
    assertEquals(invalid.json.at("error.param").asString, "questions[0].type")
    assertEquals(okCalls.get(), before, "the provider should not have been called")
  }

  test("the dedicated plugin serves the same api") {
    setup
    val resp = post(s"http://openai-decisions.oto.tools:${port}/v1/decisions", request - "model")
    assertEquals(resp.status, 200, s"status should be 200, got ${resp.body}")
    assertEquals(resp.json.select("answers").as[Seq[JsObject]].size, 3)
    assertEquals(okBody.get().select("model").asString, jev, "the model of the decision model")
  }

  test("what the provider refuses comes back in the OpenAI format, retry-after included") {
    setup
    val resp = post(s"http://unified-openai-decisions.oto.tools:${port}/v1/decisions", request ++ Json.obj("model" -> s"${e429.id}###${jev}"))
    assertEquals(resp.status, 429)
    assertEquals(resp.header("Retry-After"), Some("7"))
    assertEquals(resp.json.at("error.message").asString, "slow down")
    assertEquals(resp.json.at("error.type").asString, "rate_limit_error")
    assertEquals(errCalls.get(), 1)
  }

  test("pictures go to the decision models that look at them, and are refused by the others") {
    setup
    val withImage = request ++ Json.obj("input" -> Json.arr(Json.obj("role" -> "user", "content" -> Json.arr(
      Json.obj("type" -> "input_text", "text" -> "Is the screen broken?"),
      Json.obj("type" -> "input_image", "image_url" -> image),
    ))))
    val workers = post(s"http://unified-openai-decisions.oto.tools:${port}/v1/decisions", withImage ++ Json.obj("model" -> s"${cloudflare.id}###_default"))
    assertEquals(workers.status, 200, s"status should be 200, got ${workers.body}")
    assertEquals(cloudflareBody.get().select("images").as[Seq[String]], Seq(image))
    assertEquals(cloudflareBody.get().select("state").asString, "Is the screen broken?")
    assertEquals(workers.json.select("model").asString, "clef-flash")
    // Jev takes them as assets of its own (suite `DecisionImagesSuite`)
    val jevResp = post(s"http://unified-openai-decisions.oto.tools:${port}/v1/decisions", withImage ++ Json.obj("model" -> s"${typesafe.id}###${jev}"))
    assertEquals(jevResp.status, 200, s"status should be 200, got ${jevResp.body}")
    assertEquals(okBody.get().select("assets").as[JsObject].values.toSeq, Seq(Json.obj("type" -> "image", "data_base64" -> "aGV5", "format" -> "png")))
    // a relay of Jev is not known to look at pictures: deciding without them would be deciding about something else
    val before = okCalls.get()
    val relayed = post(s"http://unified-openai-decisions.oto.tools:${port}/v1/decisions", withImage ++ Json.obj("model" -> s"${router.id}###typesafe/jev-1.13"))
    assertEquals(relayed.status, 400, s"status should be 400, got ${relayed.body}")
    assertEquals(relayed.json.at("error.type").asString, "invalid_request_error")
    assert(relayed.json.at("error.message").asString.contains("images"), relayed.body)
    assertEquals(okCalls.get(), before, "the provider should not have been called")
    // a picture given by its address is fetched by OpenAI only: Workers AI and Jev take the picture itself
    val linked = request ++ Json.obj("input" -> Json.arr(Json.obj("role" -> "user", "content" -> Json.arr(
      Json.obj("type" -> "input_text", "text" -> "Is the screen broken?"),
      Json.obj("type" -> "input_image", "image_url" -> "https://shop.tld/screen.png"),
    ))))
    cloudflareBody.set(JsNull)
    Seq(s"${cloudflare.id}###_default", s"${typesafe.id}###${jev}").foreach { model =>
      val refused = post(s"http://unified-openai-decisions.oto.tools:${port}/v1/decisions", linked ++ Json.obj("model" -> model))
      assertEquals(refused.status, 400, s"status should be 400, got ${refused.body}")
      assertEquals(refused.json.at("error.type").asString, "invalid_request_error")
      assert(refused.json.at("error.message").asString.contains("data URL"), refused.body)
    }
    assertEquals(okCalls.get(), before, "Jev should not have been called")
    assertEquals(cloudflareBody.get(), JsNull, "Workers AI should not have been called")
  }

  test("a System One request is asked to OpenAI in its own words") {
    val questions = Json.obj(
      "urgent" -> Json.obj("type" -> "noul", "instructions" -> "Is it urgent?", "criteria" -> Json.obj("true" -> "someone is blocked", "false" -> "it can wait")),
      "team" -> Json.obj("type" -> "choice", "instructions" -> "Which team?", "criteria" -> Json.obj("billing" -> "Invoices", "technical" -> "technical")),
      "severity" -> Json.obj("type" -> "score", "instructions" -> "How severe?", "criteria" -> Json.arr("Minor", "Blocking")),
    )
    val state = Json.obj("ticket" -> "The checkout fails")
    val body = OpenAiDecisionRequests.bodyOf(DecisionModelClientInputOptions(state, questions), Json.obj()).toOption.get
    // a structured state is given as the json it is, a state OpenAI does not take as json as text
    assertEquals(body.select("input").as[JsValue], Json.arr(Json.obj("role" -> "user", "content" -> Json.arr(Json.obj("type" -> "input_json", "data" -> state)))))
    val listed = OpenAiDecisionRequests.bodyOf(DecisionModelClientInputOptions(Json.arr("a", "b"), questions), Json.obj()).toOption.get
    assertEquals(listed.select("input").asString, Json.prettyPrint(Json.arr("a", "b")))
    assertEquals(body.select("questions").as[Seq[JsObject]], Seq(
      Json.obj("type" -> "predicate", "name" -> "urgent", "instructions" -> "Is it urgent?\nYes means: someone is blocked\nNo means: it can wait"),
      Json.obj("type" -> "choice", "name" -> "team", "instructions" -> "Which team?", "choices" -> Json.arr(
        Json.obj("value" -> "billing", "description" -> "Invoices"),
        Json.obj("value" -> "technical"),
      )),
      Json.obj("type" -> "score", "name" -> "severity", "instructions" -> "How severe?", "levels" -> Json.arr(Json.obj("label" -> "Minor"), Json.obj("label" -> "Blocking"))),
    ))
    // the pictures of a state go before it, data urls, the objects Cloudflare also takes or the addresses OpenAI fetches
    val withImages = OpenAiDecisionRequests.bodyOf(DecisionModelClientInputOptions(JsString("Is it broken?"), questions), Json.obj("images" -> Json.arr(image, Json.obj("content_type" -> "image/jpeg", "base64" -> "aGV5"), "https://shop.tld/a.png"))).toOption.get
    assertEquals(withImages.select("input").as[JsValue], Json.arr(Json.obj("role" -> "user", "content" -> Json.arr(
      Json.obj("type" -> "input_image", "image_url" -> image),
      Json.obj("type" -> "input_image", "image_url" -> "data:image/jpeg;base64,aGV5"),
      Json.obj("type" -> "input_image", "image_url" -> "https://shop.tld/a.png"),
      Json.obj("type" -> "input_text", "text" -> "Is it broken?"),
    ))))
    val pictured = OpenAiDecisionRequests.bodyOf(DecisionModelClientInputOptions(state, questions), Json.obj("images" -> Json.arr(image))).toOption.get
    assertEquals(pictured.at("input.0.content").as[Seq[JsObject]].map(_.select("type").asString), Seq("input_image", "input_json"))
    // OpenAI answers three types of questions
    val rank = OpenAiDecisionRequests.bodyOf(DecisionModelClientInputOptions(state, Json.obj("order" -> Json.obj("type" -> "rank", "instructions" -> "x"))), Json.obj())
    assertEquals(rank.left.toOption.flatMap(e => DecisionErrors.classify(e) match {
      case DecisionErrors.Kind.Upstream(status, _, _) => status.some
      case _ => None
    }), Some(422))
    // a request that came in the OpenAI format goes as it was written, for the model of the decision model
    val original = request ++ Json.obj("safety_identifier" -> "user-42", "provider" -> "luna")
    assertEquals(OpenAiDecisionRequests.bodyOf(DecisionModelClientInputOptions(state, questions, openai = original.some), Json.obj()).toOption.get, original - "model" - "provider")
  }

  test("a decision model of OpenAI answers the System One api, billed at its price") {
    setup
    val systemOne = Json.obj(
      "model" -> s"${openai.id}###gpt-6-luna",
      "state" -> "The checkout has been failing for every customer for the last hour.",
      "questions" -> Json.obj(
        "urgent" -> Json.obj("type" -> "noul", "instructions" -> "Is it urgent?"),
        "team" -> Json.obj("type" -> "choice", "instructions" -> "Which team?", "criteria" -> Json.obj("billing" -> "Invoices", "technical" -> "Outages")),
        "severity" -> Json.obj("type" -> "score", "instructions" -> "How severe?", "criteria" -> Json.arr("Minor", "Degraded", "Blocking")),
        "refuse" -> Json.obj("type" -> "noul", "instructions" -> "Something it will not answer"),
      ),
    )
    val resp = post(s"http://unified-openai-decisions.oto.tools:${port}/v1/systemone?embed_costs=true", systemOne)
    assertEquals(resp.status, 200, s"status should be 200, got ${resp.body}")
    // OpenAI was asked in its format, each question under its name
    assertEquals(openaiBody.get().select("model").asString, "gpt-6-luna")
    assertEquals(openaiBody.get().select("input").asString, "The checkout has been failing for every customer for the last hour.")
    assertEquals(openaiBody.get().select("questions").as[Seq[JsObject]].map(_.select("name").asString), Seq("urgent", "team", "severity", "refuse"))
    // and its answers are System One ones
    assertEquals(resp.json.at("answers.urgent").as[JsObject], Json.obj("type" -> "noul", "noul" -> 0.9))
    assertEquals(resp.json.at("answers.team.choice").asString, "technical")
    assertEquals(resp.json.at("answers.team.probabilities.technical").as[BigDecimal], BigDecimal("0.7"))
    assertEquals(resp.json.at("answers.severity.score").as[BigDecimal], BigDecimal("1.7"))
    assertEquals(resp.json.at("answers.severity.legend").as[Map[String, String]], Map("0" -> "Minor", "1" -> "Degraded", "2" -> "Blocking"))
    assertEquals(resp.json.at("answers.severity.probabilities.2").as[BigDecimal], BigDecimal("0.8"))
    assertEquals(resp.json.at("answers.refuse").as[JsObject], Json.obj("type" -> "refusal"))
    // 350 input tokens of gpt-6-luna, its output being always empty
    assertEquals(resp.json.at("costs.total_cost").asOpt[BigDecimal], (BigDecimal(inputTokens) * BigDecimal("0.0000001")).some, s"unexpected costs: ${resp.body}")
    // at the prices of its decisions api, where an output costs nothing, unlike a chat one
    val costs = otoroshi.env.adminExtensions.extension[AiExtension].get.costsTracking
    assertEquals(costs.computeCosts("openai", "gpt-6-luna", 1000L, 1000L, 0L, api = "decisions".some).toOption.map(c => (c.inputCost, c.outputCost)), Some((BigDecimal("0.0001"), BigDecimal(0))))
    assertEquals(costs.computeCosts("openai", "gpt-6-luna", 1000L, 1000L, 0L).toOption.map(_.outputCost), Some(BigDecimal("0.0005")))
  }

  test("a request in the OpenAI format reaches a decision model of OpenAI as it was written") {
    setup
    val asked = request ++ Json.obj(
      "model" -> s"${openai.id}###_default",
      "safety_identifier" -> "user-42",
      "prompt_cache_key" -> "shop",
      "prompt_cache_options" -> Json.obj("mode" -> "explicit", "ttl" -> "30m"),
      "input" -> Json.arr(
        Json.obj("role" -> "developer", "content" -> Json.arr(Json.obj("type" -> "input_text", "text" -> "You sort the tickets of a shop.", "prompt_cache_breakpoint" -> Json.obj("mode" -> "explicit")))),
        Json.obj("role" -> "user", "content" -> Json.arr(
          Json.obj("type" -> "input_text", "text" -> "Is the screen broken?"),
          Json.obj("type" -> "input_image", "image_url" -> image, "detail" -> "high"),
          Json.obj("type" -> "input_image", "image_url" -> "https://shop.tld/screen.png"),
        )),
        Json.obj("type" -> "function_call", "call_id" -> "c1", "name" -> "find_order", "arguments" -> "{\"id\":42}"),
        Json.obj("type" -> "function_call_output", "call_id" -> "c1", "output" -> "delivered yesterday"),
      ),
      "questions" -> ((request.select("questions").as[JsArray]) :+ Json.obj("type" -> "predicate", "name" -> "refuse", "instructions" -> Json.arr(
        Json.obj("type" -> "input_text", "text" -> "Something it will not answer about"),
        Json.obj("type" -> "input_json", "data" -> Json.obj("order" -> 42)),
      ))),
    )
    val resp = post(s"http://unified-openai-decisions.oto.tools:${port}/v1/decisions", asked)
    assertEquals(resp.status, 200, s"status should be 200, got ${resp.body}")
    // what only OpenAI knows of goes along: the end user, the prompt cache, the detail and the address of a
    // picture, the authors of a transcript, the parts of the instructions, a question without a name
    assertEquals(openaiBody.get().select("safety_identifier").asString, "user-42")
    assertEquals(openaiBody.get().select("prompt_cache_key").asString, "shop")
    assertEquals(openaiBody.get().select("prompt_cache_options").as[JsObject], Json.obj("mode" -> "explicit", "ttl" -> "30m"))
    assertEquals(openaiBody.get().select("input").as[JsArray], asked.select("input").as[JsArray])
    assertEquals(openaiBody.get().select("questions").as[JsArray], asked.select("questions").as[JsArray])
    assertEquals(openaiBody.get().select("model").asString, "gpt-6-luna")
    assertEquals(openaiBody.get().select("provider").asOpt[JsValue], None)
    // and its answers come back as it gave them
    val answers = resp.json.select("answers").as[Seq[JsObject]]
    assertEquals(answers.map(_.select("type").asString), Seq("predicate", "choice", "score", "refusal"))
    assertEquals(answers(1).select("choice").as[JsValue], JsString("escalate"))
    assertEquals(answers(1).select("probabilities").as[Seq[JsObject]].map(_.select("value").as[JsValue]), Seq(JsBoolean(true), JsBoolean(false), JsString("escalate")))
    assertEquals(answers(2).select("name").as[JsValue], JsNull)
    assertEquals(answers(3), Json.obj("type" -> "refusal", "name" -> "refuse"))
    assertEquals(resp.json.at("usage.input_tokens_details.cached_tokens").as[Long], 12L)
  }

  test("a guardrail does not let through what a model refused to judge") {
    assert(DecisionGuardrail.denies(Json.obj(), DecisionAnswers.refusal))
    assert(!DecisionGuardrail.denies(Json.obj("threshold" -> 0.5), DecisionAnswers.noul(0.2)))
  }

  test("a decision model is listed on both apis, the one its provider speaks first") {
    assertEquals(ModelEndpoints.decisions("openai"), Seq("decisions", "systemone"))
    assertEquals(ModelEndpoints.decisions("typesafe"), Seq("systemone", "decisions"))
    def endpoints(kind: String, model: String) =
      ModelsMetadata.describe(AiProvider(id = "x", name = "x", provider = kind, connection = Json.obj(), options = Json.obj()), model, "decision")(using otoroshi.env).endpoints
    assertEquals(endpoints("openai", "gpt-6-luna"), Seq("decisions", "systemone"))
    assertEquals(endpoints("typesafe", jev), Seq("systemone", "decisions"))
  }
}
