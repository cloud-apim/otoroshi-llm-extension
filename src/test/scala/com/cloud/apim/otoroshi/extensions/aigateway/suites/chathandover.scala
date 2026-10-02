package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.decorators.ChatClientWithCostsTracking
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiBudgetConsumptions, AiProvider}
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatMessage, ChatPrompt, ChatResponse, LlmExtensionOneOtoroshiServerPerSuite}
import org.apache.pekko.stream.scaladsl.Sink
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.*
import reactor.core.publisher.{Flux, Mono}
import reactor.netty.http.server.HttpServerResponse

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.DurationInt
import scala.util.Try

// A provider that hands the call it received over to another one (a fallback, the target of a load balancer, the
// candidate of a router) runs the whole chain of that provider inside its own. The call is priced and counted
// against budgets once, by the provider that served it, and two providers falling back on each other do not call
// each other for ever.
class ChatHandOverSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  val servedModel = "gpt-4o-mini"
  val promptTokens = 100L
  val completionTokens = 50L
  val tokens = promptTokens + completionTokens

  private def send(response: HttpServerResponse, status: Int, body: JsValue) =
    response.status(status).addHeader("Content-Type", "application/json").sendString(Mono.just(body.stringify)).`then`()

  val usage = Json.obj("prompt_tokens" -> promptTokens, "completion_tokens" -> completionTokens, "total_tokens" -> tokens)

  private def completion: JsObject = Json.obj(
    "id" -> "chatcmpl-1", "object" -> "chat.completion", "created" -> 1700000000, "model" -> servedModel,
    "choices" -> Json.arr(Json.obj("index" -> 0, "finish_reason" -> "stop", "message" -> Json.obj("role" -> "assistant", "content" -> "hello"))),
    "usage" -> usage,
  )

  private def chunk(choices: JsArray, usage: Option[JsObject] = None): String = "data: " + (Json.obj(
    "id" -> "chatcmpl-1", "object" -> "chat.completion.chunk", "created" -> 1700000000, "model" -> servedModel, "choices" -> choices,
  ) ++ usage.map(u => Json.obj("usage" -> u)).getOrElse(Json.obj())).stringify + "\n\n"

  // the provider that answers, blocking or streaming
  val servingCalls = new AtomicInteger(0)
  val (servingPort, _) = createTestServerWithRoutes("chat-handover-serving", routes => routes
    .post("/v1/chat/completions", (req, response) => req.receive().aggregate().asString().flatMap { body =>
      servingCalls.incrementAndGet()
      if (Json.parse(body).select("stream").asOpt[Boolean].contains(true)) {
        response.status(200).addHeader("Content-Type", "text/event-stream").sendString(Flux.just(
          chunk(Json.arr(Json.obj("index" -> 0, "delta" -> Json.obj("role" -> "assistant", "content" -> "hello"), "finish_reason" -> JsNull))),
          chunk(Json.arr(Json.obj("index" -> 0, "delta" -> Json.obj(), "finish_reason" -> "stop"))),
          chunk(Json.arr(), Some(usage)),
          "data: [DONE]\n\n",
        )).`then`()
      } else {
        send(response, 200, completion)
      }
    })
  )

  // a provider that fails, under three base urls to tell who was called
  val failure = Json.obj("error" -> Json.obj("message" -> "the server had an error", "type" -> "server_error"))
  val primaryCalls = new AtomicInteger(0)
  val aCalls = new AtomicInteger(0)
  val bCalls = new AtomicInteger(0)
  val (failingPort, _) = createTestServerWithRoutes("chat-handover-failing", routes => routes
    .post("/primary/v1/chat/completions", (req, response) => req.receive().aggregate().asString().flatMap { _ => primaryCalls.incrementAndGet(); send(response, 500, failure) })
    .post("/a/v1/chat/completions", (req, response) => req.receive().aggregate().asString().flatMap { _ => aCalls.incrementAndGet(); send(response, 500, failure) })
    .post("/b/v1/chat/completions", (req, response) => req.receive().aggregate().asString().flatMap { _ => bCalls.incrementAndGet(); send(response, 500, failure) })
  )

  // two more failing providers, one falling back on itself, one falling back on a load balancer it is a target
  // of, and a provider that fails its first call only
  val selfCalls = new AtomicInteger(0)
  val pooledCalls = new AtomicInteger(0)
  val flakyCalls = new AtomicInteger(0)
  val shakyCalls = new AtomicInteger(0)
  val brokenCalls = new AtomicInteger(0)
  val (roundsPort, _) = createTestServerWithRoutes("chat-handover-rounds", routes => routes
    .post("/flaky/v1/chat/completions", (req, response) => req.receive().aggregate().asString().flatMap { _ =>
      if (flakyCalls.incrementAndGet() == 1) send(response, 500, failure) else send(response, 200, completion)
    })
    // a provider that fails, and the fallback it is given: one that answers something no client can read
    .post("/shaky/v1/chat/completions", (req, response) => req.receive().aggregate().asString().flatMap { _ => shakyCalls.incrementAndGet(); send(response, 500, failure) })
    .post("/broken/v1/chat/completions", (req, response) => req.receive().aggregate().asString().flatMap { _ =>
      brokenCalls.incrementAndGet()
      response.status(200).addHeader("Content-Type", "application/json").sendString(Mono.just("this is not json")).`then`()
    })
    .post("/self/v1/chat/completions", (req, response) => req.receive().aggregate().asString().flatMap { _ => selfCalls.incrementAndGet(); send(response, 500, failure) })
    .post("/pooled/v1/chat/completions", (req, response) => req.receive().aggregate().asString().flatMap { _ => pooledCalls.incrementAndGet(); send(response, 500, failure) })
  )

  def ext: AiExtension = otoroshi.env.adminExtensions.extension[AiExtension].get

  def openai(name: String, baseUrl: String, model: String, fallback: Option[String] = None, id: String = s"provider_${UUID.randomUUID()}"): AiProvider = AiProvider(
    id = id,
    name = name,
    provider = "openai",
    connection = Json.obj("base_url" -> baseUrl, "token" -> "sk-test", "timeout" -> 10000),
    options = Json.obj("model" -> model),
    providerFallback = fallback,
  )

  val servingUrl = s"http://localhost:${servingPort}/v1"
  val aId = s"provider_${UUID.randomUUID()}"
  val bId = s"provider_${UUID.randomUUID()}"
  val selfId = s"provider_${UUID.randomUUID()}"
  val pooledId = s"provider_${UUID.randomUUID()}"
  val poolId = s"provider_${UUID.randomUUID()}"

  lazy val fallback = openai("fallback", servingUrl, servedModel)
  // a more expensive model than the one of the fallback: a call priced as the primary would cost more
  lazy val primary = openai("primary", s"http://localhost:${failingPort}/primary/v1", "gpt-4o", fallback.id.some)
  lazy val target = openai("routing target", servingUrl, servedModel)
  lazy val balancer = AiProvider(
    id = s"provider_${UUID.randomUUID()}",
    name = "load balancer",
    provider = "loadbalancer",
    connection = Json.obj(),
    options = Json.obj("refs" -> Json.arr(target.id)),
  )
  lazy val router = AiProvider(
    id = s"provider_${UUID.randomUUID()}",
    name = "router",
    provider = "otoroshi",
    connection = Json.obj(),
    options = Json.obj("code_router_refs" -> Json.arr(target.id)),
  )
  lazy val cycleA = openai("cycle a", s"http://localhost:${failingPort}/a/v1", servedModel, bId.some, id = aId)
  lazy val cycleB = openai("cycle b", s"http://localhost:${failingPort}/b/v1", servedModel, aId.some, id = bId)
  lazy val flaky = openai("flaky", s"http://localhost:${roundsPort}/flaky/v1", servedModel, fallback.id.some)
  lazy val broken = openai("broken", s"http://localhost:${roundsPort}/broken/v1", servedModel)
  lazy val shaky = openai("shaky", s"http://localhost:${roundsPort}/shaky/v1", servedModel, broken.id.some)
  lazy val selfish = openai("its own fallback", s"http://localhost:${roundsPort}/self/v1", servedModel, selfId.some, id = selfId)
  lazy val pooled = openai("pooled", s"http://localhost:${roundsPort}/pooled/v1", servedModel, poolId.some, id = pooledId)
  lazy val pool = AiProvider(
    id = poolId,
    name = "pool",
    provider = "loadbalancer",
    connection = Json.obj(),
    options = Json.obj("refs" -> Json.arr(pooledId)),
  )

  val fallbackBudget = "chat fallback budget"
  val routingBudget = "chat routing budget"

  // both the provider that received the call and the one that served it are in the scope: it counts once all the same
  def budget(name: String, providers: Seq[String]): JsObject = Json.obj(
    "id" -> s"budget_${UUID.randomUUID()}",
    "name" -> name,
    "description" -> name,
    "enabled" -> true,
    "duration" -> Json.obj("value" -> 1, "unit" -> "year"),
    "limits" -> Json.obj("total_usd" -> 1000, "total_tokens" -> 100000000),
    "scope" -> Json.obj("providers" -> JsArray(providers.map(JsString.apply))),
    "action_on_exceed" -> Json.obj("mode" -> "soft", "alert_on_exceed" -> false, "alert_on_almost_exceed" -> false),
  )

  lazy val setup: Unit = {
    Seq(fallback, primary, target, balancer, router, cycleA, cycleB, flaky, broken, shaky, selfish, pooled, pool).foreach { p =>
      assert(client.forLlmEntity("providers").upsertEntity(p).awaitf(10.seconds).createdOrUpdated, s"${p.name} should be saved")
    }
    client.forLlmEntity("ai-budgets").createRaw(budget(fallbackBudget, Seq(primary.id, flaky.id, fallback.id))).awaitf(10.seconds)
    client.forLlmEntity("ai-budgets").createRaw(budget(routingBudget, Seq(balancer.id, router.id, target.id))).awaitf(10.seconds)
    await(10.seconds)
  }

  val prompt = ChatPrompt(Seq(ChatMessage.userStrInput("hello")))

  // the price of the call for the provider that served it
  def servedCost: BigDecimal = ext.costsTracking.computeCosts("openai", servedModel, promptTokens, completionTokens, 0L).toOption.get.totalCost

  def consumptions(name: String): AiBudgetConsumptions = {
    given env: Env = otoroshi.env
    ext.states.allBudgets().find(_.name == name).get.getConsumptions().awaitf(10.seconds)
  }

  def call(provider: AiProvider, attrs: TypedMap = TypedMap.empty): Either[JsValue, ChatResponse] = {
    given env: Env = otoroshi.env
    ext.states.provider(provider.id).flatMap(_.getChatClient()).get.call(prompt, attrs, Json.obj())(using ec, env).awaitf(30.seconds)
  }

  // A call that must end with the error of the last provider tried. On a gateway where providers call each
  // other for ever it never ends: `breaking` is then deleted, which is what stops them
  def assertEnds(provider: AiProvider, breaking: AiProvider): Unit = {
    val resp = Try(call(provider))
    client.forLlmEntity("providers").deleteEntity(breaking).awaitf(10.seconds)
    assert(resp.isSuccess, s"the call should have ended, got ${resp}")
    assert(resp.get.isLeft, s"the last error should come back, got ${resp}")
  }

  // the stream is run to its end: budgets are counted once it completes
  def stream(provider: AiProvider, attrs: TypedMap = TypedMap.empty): Either[JsValue, Int] = {
    given env: Env = otoroshi.env
    ext.states.provider(provider.id).flatMap(_.getChatClient()).get.stream(prompt, attrs, Json.obj())(using ec, env).awaitf(30.seconds)
      .map(_.runWith(Sink.seq)(using mat).awaitf(30.seconds).size)
  }

  def assertCountedOnce(name: String, before: AiBudgetConsumptions, attrs: TypedMap): Unit = {
    await(5.seconds)
    val after = consumptions(name)
    assertEquals(after.inferenceTokens - before.inferenceTokens, tokens, "the budget should have moved by the tokens of one call")
    assertEquals(after.totalTokens - before.totalTokens, tokens)
    assertEquals(after.inferenceUsd - before.inferenceUsd, servedCost, "and by the cost of one call")
    assertEquals(after.totalUsd - before.totalUsd, servedCost)
    // what the caller is told the call cost, in the response and its headers
    assertEquals(attrs.get(ChatClientWithCostsTracking.key).map(_.totalCost), servedCost.some, "the call is priced as the provider that served it prices it")
  }

  test("a call served by a fallback is priced and counted against budgets once, at the price of who served it") {
    setup
    val before = consumptions(fallbackBudget)
    val attrs = TypedMap.empty
    val resp = call(primary, attrs)
    assert(resp.isRight, s"the fallback should have answered, got ${resp}")
    assertEquals(primaryCalls.get(), 1, "the primary provider was tried first")
    assertCountedOnce(fallbackBudget, before, attrs)
  }

  test("a stream served by a fallback is priced and counted against budgets once too") {
    setup
    val before = consumptions(fallbackBudget)
    val attrs = TypedMap.empty
    val resp = stream(primary, attrs)
    assert(resp.isRight, s"the fallback should have answered, got ${resp}")
    assertCountedOnce(fallbackBudget, before, attrs)
  }

  test("a provider that handed a call over accounts for the next one, which it serves itself") {
    setup
    val before = consumptions(fallbackBudget)
    // a workflow, an agent: several calls can share their attributes
    val attrs = TypedMap.empty
    val handedOver = call(flaky, attrs)
    assert(handedOver.isRight, s"the fallback should have answered, got ${handedOver}")
    val served = call(flaky, attrs)
    assert(served.isRight, s"the provider should have answered, got ${served}")
    assertEquals(flakyCalls.get(), 2)
    await(5.seconds)
    val after = consumptions(fallbackBudget)
    assertEquals(after.inferenceTokens - before.inferenceTokens, tokens * 2, "each call counts once, whoever served it")
    assertEquals(after.inferenceUsd - before.inferenceUsd, servedCost * 2)
  }

  test("a call served through a load balancer is priced and counted against budgets once") {
    setup
    val before = consumptions(routingBudget)
    val attrs = TypedMap.empty
    val resp = call(balancer, attrs)
    assert(resp.isRight, s"the target should have answered, got ${resp}")
    assertCountedOnce(routingBudget, before, attrs)
  }

  test("a call served through a router is priced and counted against budgets once") {
    setup
    val before = consumptions(routingBudget)
    val attrs = TypedMap.empty
    val resp = call(router, attrs)
    assert(resp.isRight, s"the candidate should have answered, got ${resp}")
    assertCountedOnce(routingBudget, before, attrs)
  }

  test("a fallback that fails is asked once") {
    setup
    val resp = Try(call(shaky))
    assert(resp.isFailure || resp.get.isLeft, s"the call cannot succeed, got ${resp}")
    assertEquals(shakyCalls.get(), 1)
    assertEquals(brokenCalls.get(), 1, "the fallback failed: asking it again would only fail again")
  }

  test("two providers falling back on each other are tried once each") {
    setup
    assertEnds(cycleA, breaking = cycleB)
    assertEquals(aCalls.get(), 1)
    assertEquals(bCalls.get(), 1)
  }

  test("a provider that is its own fallback is tried once") {
    setup
    assertEnds(selfish, breaking = selfish)
    assertEquals(selfCalls.get(), 1)
  }

  test("a provider falling back on a load balancer it is a target of is not asked for ever") {
    setup
    assertEnds(pooled, breaking = pool)
    // once for the call, once as the target of the load balancer: it does not fall back a second time
    assertEquals(pooledCalls.get(), 2)
  }
}
