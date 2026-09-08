package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.LlmExtensionOneOtoroshiServerPerSuite
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.providers.{ProviderQuotas, QuotaIncidentKind}
import otoroshi.env.Env
import otoroshi.models.{DataExporterConfig, DataExporterConfigFiltering, DataExporterConfigTypeWebhook, EntityLocation, Webhook}
import otoroshi.next.models.*
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.OpenAiCompatProxy
import play.api.libs.json.{JsObject, Json}
import reactor.core.publisher.Mono

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.DurationInt

// A throttled provider answers 429 to every call until its window resets, so the alert must be raised once per
// episode, not once per call, and a second one must say when the provider serves again.
class QuotaAlertsSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  val throttled = new AtomicBoolean(false)

  val (ollamaPort, _) = createTestServerWithRoutes("ollama-quota", routes => routes
    .post("/api/chat", (req, response) => {
      req.receiveContent().ignoreElements().subscribe()
      if (throttled.get()) {
        response
          .status(429)
          .addHeader("Content-Type", "application/json")
          .sendString(Mono.just("""{"error":"rate limit reached for this model"}"""))
      } else {
        response
          .status(200)
          .addHeader("Content-Type", "application/json")
          .sendString(Mono.just(
            """{
              |  "model": "llama2",
              |  "created_at": "2023-12-12T14:13:43.416799Z",
              |  "message": { "role": "assistant", "content": "hello" },
              |  "done": true,
              |  "prompt_eval_count": 26,
              |  "eval_count": 298
              |}""".stripMargin))
      }
    })
  )

  var alerts = Seq.empty[JsObject]

  val (ingestPort, _) = createTestServerWithRoutes("alert-ingest", routes => routes.post("/ingest", (req, response) => {
    req.receive().retain().asString().flatMap { body =>
      body.parseJson.asOpt[Seq[JsObject]].getOrElse(Seq.empty).foreach(ev => alerts = alerts :+ ev)
      response.status(200).addHeader("Content-Type", "application/json").sendString(Mono.just("""{"done":true}"""))
    }
  }))

  def alertsNamed(name: String): Seq[JsObject] = alerts.filter(_.select("alert").asOptString.contains(name))

  lazy val endpoint: String = s"http://localhost:${ollamaPort}"

  lazy val setup: Unit = {
    val provider = AiProvider(
      id = UUID.randomUUID().toString,
      name = "quota provider",
      provider = "ollama",
      connection = Json.obj("base_url" -> endpoint, "timeout" -> 30000),
      options = Json.obj("model" -> "llama2"),
    )
    val route = NgRoute(
      location = EntityLocation.default,
      id = UUID.randomUUID().toString,
      name = "quota route",
      description = "quota route",
      tags = Seq.empty,
      metadata = Map.empty,
      enabled = true,
      debugFlow = false,
      capture = false,
      exportReporting = false,
      frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath("quota.oto.tools/chat"))),
      backend = NgBackend.empty.copy(targets = Seq(NgTarget.default)),
      plugins = NgPlugins(Seq(NgPluginInstance(
        plugin = s"cp:${classOf[OpenAiCompatProxy].getName}",
        config = NgPluginInstanceConfig(Json.obj("refs" -> Json.arr(provider.id)))
      )))
    )
    val exporter = DataExporterConfig(
      enabled = true,
      typ = DataExporterConfigTypeWebhook,
      id = UUID.randomUUID().toString,
      name = "alert exporter",
      desc = "alert exporter",
      bufferSize = 100,
      jsonWorkers = 1,
      sendWorkers = 1,
      groupSize = 1,
      groupDuration = 500.millis,
      filtering = DataExporterConfigFiltering(
        include = Seq(
          Json.obj("@type" -> "AlertEvent", "alert" -> ProviderQuotas.throttledAlert),
          Json.obj("@type" -> "AlertEvent", "alert" -> ProviderQuotas.recoveredAlert),
        )
      ),
      projection = Json.obj(),
      config = Webhook(url = s"http://localhost:${ingestPort}/ingest")
    )
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").upsertEntity(provider).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(route).awaitf(10.seconds)
    client.forEntity("events.otoroshi.io", "v1", "data-exporters").upsertEntity(exporter).awaitf(10.seconds)
    await(15.seconds)
  }

  def chat(): Int = client.call("POST", s"http://quota.oto.tools:${port}/chat", Map.empty, Some(Json.obj(
    "model" -> "llama2",
    "messages" -> Json.arr(Json.obj("role" -> "user", "content" -> "hey")),
  ))).awaitf(30.seconds).status

  def kindOf(status: Int, body: Option[String]): Option[QuotaIncidentKind] = ProviderQuotas.classify(status, body).map(_.kind)

  test("throttling and credit exhaustion are told apart, credentials failures raise nothing") {
    // a plain 429 is throttling: transient, it will resolve on its own
    assertEquals(kindOf(429, None), QuotaIncidentKind.Throttled.some)
    assertEquals(kindOf(429, Some("""{"error":"rate limit reached"}""")), QuotaIncidentKind.Throttled.some)
    // but OpenAI answers 429 with insufficient_quota when the account is out of money, which is not throttling
    assertEquals(kindOf(429, Some("""{"error":{"code":"insufficient_quota"}}""")), QuotaIncidentKind.CreditExhausted.some)
    assertEquals(kindOf(429, Some("You exceeded your current quota, please check your plan and billing")), QuotaIncidentKind.CreditExhausted.some)
    // 402 is always about credits
    assertEquals(kindOf(402, None), QuotaIncidentKind.CreditExhausted.some)
    // a 403 is a quota problem only when the body says so, otherwise it is a credentials failure
    assertEquals(kindOf(403, Some("""{"error":"quota exceeded for this org"}""")), QuotaIncidentKind.Throttled.some)
    assertEquals(kindOf(403, Some("""{"error":"no credit balance left"}""")), QuotaIncidentKind.CreditExhausted.some)
    assertEquals(kindOf(403, Some("""{"error":"invalid api key"}""")), None)
    assertEquals(kindOf(403, None), None)
    assertEquals(kindOf(500, Some("quota")), None)
    assertEquals(kindOf(200, None), None)
    // only throttling is worth waiting out
    assert(QuotaIncidentKind.Throttled.transient)
    assert(!QuotaIncidentKind.CreditExhausted.transient)
  }

  test("the provider tells us when to come back, in whichever dialect") {
    val now = 1_000_000L
    def at(headers: (String, String)*): Option[Long] = ProviderQuotas.retryAtFrom(headers.toMap.get, now)
    assertEquals(at("Retry-After" -> "30"), (now + 30000L).some)
    assertEquals(at("Retry-After" -> "Thu, 01 Jan 1970 00:20:00 GMT"), 1200000L.some)
    // openai style durations, on the header that is present
    assertEquals(at("x-ratelimit-reset-requests" -> "6m0s"), (now + 360000L).some)
    assertEquals(at("x-ratelimit-reset-tokens" -> "1.5s"), (now + 1500L).some)
    // Retry-After wins over the reset headers
    assertEquals(at("Retry-After" -> "10", "x-ratelimit-reset-requests" -> "6m0s"), (now + 10000L).some)
    // a reset date already in the past tells us nothing
    assertEquals(at("Retry-After" -> "Thu, 01 Jan 1970 00:00:01 GMT"), None)
    assertEquals(at(), None)
    assertEquals(ProviderQuotas.parseDuration("250ms"), 250L.some)
    assertEquals(ProviderQuotas.parseDuration("1h30m"), 5400000L.some)
    assertEquals(ProviderQuotas.parseDuration("nope"), None)
  }

  test("the endpoint key keeps the port, so two local providers are not conflated") {
    assertEquals(ProviderQuotas.endpointOf("http://localhost:8080/api/chat"), "http://localhost:8080")
    assertEquals(ProviderQuotas.endpointOf("https://api.openai.com/v1/chat/completions"), "https://api.openai.com")
    assertNotEquals(ProviderQuotas.endpointOf("http://localhost:8080/x"), ProviderQuotas.endpointOf("http://localhost:9090/x"))
  }

  test("an episode counts the refused calls and closes on the first success") {
    given env: Env = otoroshi.env
    ProviderQuotas.reset()
    val url = "https://api.example.com/v1/chat"
    ProviderQuotas.record("Example", url, 429, None)
    ProviderQuotas.record("Example", url, 429, None)
    ProviderQuotas.record("Example", url, 429, None)
    val episode = ProviderQuotas.episodeFor("Example", "https://api.example.com")
    assert(episode.isDefined, "an episode should be open")
    assertEquals(episode.get.hits.get(), 3L, "every refused call should be counted")
    // a 500 says nothing about quotas and must not close the episode
    ProviderQuotas.record("Example", url, 500, None)
    assert(ProviderQuotas.episodeFor("Example", "https://api.example.com").isDefined, "a 500 must not close the episode")
    ProviderQuotas.record("Example", url, 200, None)
    assertEquals(ProviderQuotas.episodeFor("Example", "https://api.example.com"), None, "a success should close the episode")
  }

  test("a throttled provider raises one alert, not one per call, then one when it recovers") {
    setup
    ProviderQuotas.reset()
    alerts = Seq.empty

    throttled.set(true)
    val refused = (1 to 4).map(_ => chat())
    assert(refused.forall(_ != 200), s"every call should have been refused, got ${refused}")

    val episode = ProviderQuotas.episodeFor("Ollama", endpoint)
    assert(episode.isDefined, s"an episode should be open, got ${ProviderQuotas.all}")
    assertEquals(episode.get.hits.get(), 4L, "the four refused calls should be counted in a single episode")
    assertEquals(episode.get.status, 429)

    throttled.set(false)
    assertEquals(chat(), 200, "the provider should serve again")
    assertEquals(ProviderQuotas.episodeFor("Ollama", endpoint), None, "the episode should be closed")

    await(8.seconds)
    assertEquals(alertsNamed(ProviderQuotas.throttledAlert).size, 1, s"exactly one alert for the whole episode, got ${alerts.map(_.select("alert").asOptString)}")
    assertEquals(alertsNamed(ProviderQuotas.recoveredAlert).size, 1, s"exactly one recovery alert, got ${alerts.map(_.select("alert").asOptString)}")

    val recovered = alertsNamed(ProviderQuotas.recoveredAlert).head
    assertEquals(recovered.select("refused_calls").asOpt[Long], 4L.some, s"the recovery alert should report the refused calls: ${recovered}")
    assertEquals(recovered.select("endpoint").asOptString, endpoint.some)
    assert(recovered.select("duration_ms").asOpt[Long].exists(_ >= 0L), "the recovery alert should report how long it lasted")
  }
}
