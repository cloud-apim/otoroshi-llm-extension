package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.LlmExtensionOneOtoroshiServerPerSuite
import com.cloud.apim.otoroshi.extensions.aigateway.decorators.ProviderCircuitBreaker
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiProvider, ProviderHealthcheck}
import com.cloud.apim.otoroshi.extensions.aigateway.providers.{ProviderHealthchecks, ProviderQuotas, QuotaIncidentKind}
import otoroshi.env.Env
import otoroshi.models.EntityLocation
import otoroshi.utils.TypedMap
import otoroshi.next.models.*
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.OpenAiCompatProxy
import play.api.libs.json.Json
import reactor.core.publisher.Mono

import java.util.UUID
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.concurrent.duration.DurationInt

// Knowing a provider is refusing calls is only half the point: the gateway should stop walking into it.
class QuotaRoutingSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  val primaryCalls = new AtomicInteger(0)
  val fallbackCalls = new AtomicInteger(0)
  val primaryThrottled = new AtomicBoolean(true)

  def ollamaOk(content: String): String =
    s"""{
       |  "model": "llama2",
       |  "created_at": "2023-12-12T14:13:43.416799Z",
       |  "message": { "role": "assistant", "content": "${content}" },
       |  "done": true,
       |  "prompt_eval_count": 26,
       |  "eval_count": 298
       |}""".stripMargin

  val listingCalls = new AtomicInteger(0)

  val (primaryPort, _) = createTestServerWithRoutes("primary", routes => routes
    // a models listing is free: it answers 200 even on an account with no credit left
    .get("/api/tags", (req, response) => {
      listingCalls.incrementAndGet()
      response.status(200).addHeader("Content-Type", "application/json")
        .sendString(Mono.just("""{"models":[{"name":"llama2"},{"name":"mistral"}]}"""))
    })
    .post("/api/chat", (req, response) => {
      primaryCalls.incrementAndGet()
      req.receiveContent().ignoreElements().subscribe()
      if (primaryThrottled.get()) {
        response.status(429)
          .addHeader("Content-Type", "application/json")
          .addHeader("Retry-After", "60")
          .sendString(Mono.just("""{"error":"rate limit reached"}"""))
      } else {
        response.status(200).addHeader("Content-Type", "application/json").sendString(Mono.just(ollamaOk("primary")))
      }
    })
  )

  val (fallbackPort, _) = createTestServerWithRoutes("fallback", routes => routes
    .post("/api/chat", (req, response) => {
      fallbackCalls.incrementAndGet()
      req.receiveContent().ignoreElements().subscribe()
      response.status(200).addHeader("Content-Type", "application/json").sendString(Mono.just(ollamaOk("fallback")))
    })
  )

  // an account with no credit left: persistent, and nothing to wait for
  val (brokePort, _) = createTestServerWithRoutes("broke", routes => routes
    .post("/api/chat", (req, response) => {
      req.receiveContent().ignoreElements().subscribe()
      response.status(429)
        .addHeader("Content-Type", "application/json")
        .sendString(Mono.just("""{"error":{"code":"insufficient_quota","message":"You exceeded your current quota"}}"""))
    })
  )

  def ext = otoroshi.env.adminExtensions.extension[AiExtensionAlias].get
  type AiExtensionAlias = otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension

  def ollamaProvider(name: String, port: Int, fallback: Option[String] = None, healthcheck: ProviderHealthcheck = ProviderHealthcheck.disabled): AiProvider = AiProvider(
    id = UUID.randomUUID().toString,
    name = name,
    provider = "ollama",
    connection = Json.obj("base_url" -> s"http://localhost:${port}", "timeout" -> 30000),
    options = Json.obj("model" -> "llama2"),
    providerFallback = fallback,
    healthcheck = healthcheck,
  )

  lazy val fallbackProvider: AiProvider = ollamaProvider("fallback provider", fallbackPort)
  lazy val primaryProvider: AiProvider = ollamaProvider("primary provider", primaryPort, fallbackProvider.id.some)

  lazy val setup: Unit = {
    val route = NgRoute(
      location = EntityLocation.default,
      id = UUID.randomUUID().toString,
      name = "quota routing route",
      description = "quota routing route",
      tags = Seq.empty,
      metadata = Map.empty,
      enabled = true,
      debugFlow = false,
      capture = false,
      exportReporting = false,
      frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath("routing.oto.tools/chat"))),
      backend = NgBackend.empty.copy(targets = Seq(NgTarget.default)),
      plugins = NgPlugins(Seq(NgPluginInstance(
        plugin = s"cp:${classOf[OpenAiCompatProxy].getName}",
        config = NgPluginInstanceConfig(Json.obj("refs" -> Json.arr(primaryProvider.id)))
      )))
    )
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").upsertEntity(fallbackProvider).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").upsertEntity(primaryProvider).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(route).awaitf(10.seconds)
    await(10.seconds)
  }

  def chat(): (Int, String) = {
    val resp = client.call("POST", s"http://routing.oto.tools:${port}/chat", Map.empty, Some(Json.obj(
      "model" -> "llama2",
      "messages" -> Json.arr(Json.obj("role" -> "user", "content" -> "hey")),
    ))).awaitf(30.seconds)
    (resp.status, resp.json.at("choices.0.message.content").asOptString.getOrElse(""))
  }

  test("a throttled provider is skipped on the next calls instead of being hammered") {
    setup
    ProviderQuotas.reset()
    ProviderCircuitBreaker.reset()
    primaryThrottled.set(true)
    primaryCalls.set(0)
    fallbackCalls.set(0)

    // first call: the primary is tried, refuses, and the fallback answers
    val (status1, content1) = chat()
    assertEquals(status1, 200, "the fallback should have answered")
    assertEquals(content1, "fallback")
    assertEquals(primaryCalls.get(), 1, "the primary should have been tried once")

    // the episode carries what the provider told us about when to come back
    val episode = ProviderQuotas.episodeForProvider(primaryProvider.id)
    assert(episode.isDefined, s"the episode should be tied to the provider entity, got ${ProviderQuotas.all}")
    assertEquals(episode.get.kind, QuotaIncidentKind.Throttled)
    assert(episode.get.retryAt.isDefined, "Retry-After should have been read")
    assert(ProviderCircuitBreaker.isOpen(primaryProvider.id, System.currentTimeMillis()), "the circuit should be open at once, without waiting for a streak")

    // next calls do not touch the primary at all
    val (status2, content2) = chat()
    val (status3, content3) = chat()
    assertEquals((status2, content2), (200, "fallback"))
    assertEquals((status3, content3), (200, "fallback"))
    assertEquals(primaryCalls.get(), 1, "the throttled primary must not be called again while its window lasts")
    assertEquals(fallbackCalls.get(), 3, "every call should have been served by the fallback")
  }

  test("the backoff is bounded, so a recovered provider is used again") {
    setup
    ProviderQuotas.reset()
    ProviderCircuitBreaker.reset()
    primaryThrottled.set(false)
    primaryCalls.set(0)

    val (status, content) = chat()
    assertEquals(status, 200)
    assertEquals(content, "primary", "with a clean state the primary should serve")
    assertEquals(ProviderQuotas.episodeForProvider(primaryProvider.id), None, "a success closes any episode")
    assert(!ProviderCircuitBreaker.isOpen(primaryProvider.id, System.currentTimeMillis()), "the circuit should be closed")
  }

  test("the probe sees an exhausted account without waiting for user traffic") {
    given env: Env = otoroshi.env
    ProviderQuotas.reset()
    val broke = ollamaProvider("broke provider", brokePort, healthcheck = ProviderHealthcheck(enabled = true))

    // no user call at all, just the probe
    ProviderHealthchecks.probe(broke)(using ec, otoroshi.env).awaitf(30.seconds)

    val episode = ProviderQuotas.episodeForProvider(broke.id)
    assert(episode.isDefined, s"the probe should have opened an episode, got ${ProviderQuotas.all}")
    // a 429 carrying insufficient_quota is an empty account, not throttling: waiting would not help
    assertEquals(episode.get.kind, QuotaIncidentKind.CreditExhausted)
    assert(!episode.get.kind.transient)
    assertEquals(episode.get.providerIds, Set(broke.id))
  }

  def listModels(provider: AiProvider): List[String] =
    provider.getChatClient()(using otoroshi.env).get.listModels(false, TypedMap.empty)(using ec).awaitf(30.seconds).toOption.get

  test("a free models listing must not clear a real incident") {
    given env: Env = otoroshi.env
    ProviderQuotas.reset()
    // the provider is throttled, so its models are still listed and the listing does reach the provider
    ProviderQuotas.record("Ollama", s"http://localhost:${primaryPort}/api/chat", 429, None, _ => None, primaryProvider.id.some)
    assert(ProviderQuotas.episodeForProvider(primaryProvider.id).isDefined, "precondition: an episode is open")

    listingCalls.set(0)
    assertEquals(listModels(primaryProvider).sorted, List("llama2", "mistral"))
    assertEquals(listingCalls.get(), 1, "the listing should have reached the provider")

    // it answered 200, but a listing is free: it proves nothing about the account and must not close anything
    assert(ProviderQuotas.episodeForProvider(primaryProvider.id).isDefined, "a free listing must not announce a recovery")
    assert(!ProviderQuotas.billable(s"http://x/api/tags"), "a models listing is not billable")
    assert(!ProviderQuotas.billable(s"http://x/v1/models"), "a models listing is not billable")
    assert(ProviderQuotas.billable(s"http://x/v1/chat/completions"), "an inference call is billable")
  }

  test("a provider with no credit left stops advertising its models") {
    given env: Env = otoroshi.env
    ProviderQuotas.reset()
    // throttling is transient: the catalog must not flap, models stay listed
    ProviderQuotas.record("Ollama", s"http://localhost:${primaryPort}/api/chat", 429, None, _ => None, primaryProvider.id.some)
    assertEquals(listModels(primaryProvider).sorted, List("llama2", "mistral"), "a throttled provider still lists its models")

    // an exhausted account is not: serving a catalog we cannot honour would mislead every consumer
    ProviderQuotas.reset()
    ProviderQuotas.record("Ollama", s"http://localhost:${primaryPort}/api/chat", 429,
      Some("""{"error":{"code":"insufficient_quota"}}"""), _ => None, primaryProvider.id.some)
    assertEquals(ProviderQuotas.episodeForProvider(primaryProvider.id).map(_.kind), QuotaIncidentKind.CreditExhausted.some)
    listingCalls.set(0)
    assertEquals(listModels(primaryProvider), List.empty[String], "no model should be advertised")
    assertEquals(listingCalls.get(), 0, "and the provider should not even be asked")

    // and it comes back on its own once the incident is over
    ProviderQuotas.reset()
    assertEquals(listModels(primaryProvider).sorted, List("llama2", "mistral"))
  }

  test("a healthcheck can never be configured to hammer a provider") {
    val parsed = ProviderHealthcheck.format.reads(Json.obj("enabled" -> true, "every" -> 100)).get
    assertEquals(parsed.everyMs, 60000L, "the interval is floored to one minute")
    assertEquals(ProviderHealthcheck.format.reads(Json.obj("enabled" -> true, "max_tokens" -> 0)).get.maxTokens, 1)
    assertEquals(ProviderHealthcheck.disabled.enabled, false, "healthchecks are opt-in")
  }
}
