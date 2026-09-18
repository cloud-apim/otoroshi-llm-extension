package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.LlmExtensionOneOtoroshiServerPerSuite
import com.cloud.apim.otoroshi.extensions.aigateway.decorators.{GuardrailFilter, GuardrailItem, Guardrails}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import otoroshi.env.Env
import otoroshi.models.{ApiKey, EntityLocation, PrivateAppsUser, RouteIdentifier}
import otoroshi.next.models.*
import otoroshi.next.plugins.NgApikeyCallsConfig
import otoroshi.plugins.Keys
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.OpenAiCompatProxy
import play.api.libs.json.{JsObject, Json}
import play.api.libs.ws.WSResponse
import reactor.core.publisher.Mono

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.DurationInt

// A guardrail applies to every call of its provider, unless it carries consumer filters: an expression
// read on the call (`${apikey.id}`, `${user.email}`, `${req.ip}`…) and what it must match, with the
// operators of the otoroshi json validators. Every filter must match, and a call the filter cannot read
// (no api key, no user) is left alone. Entities stored before filters existed have none.
class GuardrailFiltersSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  val chatCalls = new AtomicInteger(0)

  val (ollamaPort, _) = createTestServerWithRoutes("ollama-guardrail-filters", routes => routes
    .post("/api/chat", (req, response) => {
      chatCalls.incrementAndGet()
      req.receiveContent().ignoreElements().subscribe()
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
    })
  )

  given env: Env = otoroshi.env

  def attrsOf(apikey: Option[ApiKey] = None, user: Option[PrivateAppsUser] = None): TypedMap = {
    TypedMap.empty
      .applyOnWithOpt(apikey) { case (attrs, key) => attrs.put(Keys.ApiKeyKey -> key) }
      .applyOnWithOpt(user) { case (attrs, u) => attrs.put(Keys.UserKey -> u) }
  }

  def guardrail(filters: GuardrailFilter*): GuardrailItem =
    GuardrailItem(enabled = true, before = true, after = false, guardrailId = "regex", config = Json.obj(), filters = filters)

  val teamKey = ApiKey(clientId = "key_team", clientSecret = "key_team-secret", clientName = "team", authorizedEntities = Seq.empty)
  val otherKey = ApiKey(clientId = "key_other", clientSecret = "key_other-secret", clientName = "other", authorizedEntities = Seq.empty)
  val insider = PrivateAppsUser(randomId = "u1", name = "jane", email = "jane@cloud-apim.com", profile = Json.obj(), realm = "test",
    authConfigId = "test", otoroshiData = None, tags = Seq.empty, metadata = Map.empty, location = EntityLocation())
  val outsider = insider.copy(email = "john@acme.io")

  test("a guardrail without filter applies to every call") {
    assert(guardrail().appliesTo(attrsOf()), "a guardrail with no filter should apply")
    assert(guardrail().appliesTo(attrsOf(apikey = teamKey.some)), "a guardrail with no filter should apply")
  }

  test("a filter picks the api key it names") {
    val onTeam = guardrail(GuardrailFilter("${apikey.id}", teamKey.clientId))
    assert(onTeam.appliesTo(attrsOf(apikey = teamKey.some)), "the guardrail should apply to the key it names")
    assert(!onTeam.appliesTo(attrsOf(apikey = otherKey.some)), "the guardrail should not apply to another key")
    val onSeveral = guardrail(GuardrailFilter("${apikey.id}", s"ContainedIn(${teamKey.clientId}, ${otherKey.clientId})"))
    assert(onSeveral.appliesTo(attrsOf(apikey = otherKey.some)), "the guardrail should apply to every key of the list")
    val onEveryoneElse = guardrail(GuardrailFilter("${apikey.id}", s"Not(${teamKey.clientId})"))
    assert(onEveryoneElse.appliesTo(attrsOf(apikey = otherKey.some)), "the guardrail should apply to the other keys")
    assert(!onEveryoneElse.appliesTo(attrsOf(apikey = teamKey.some)), "the guardrail should not apply to the key it excludes")
  }

  test("a filter reads the user of the call") {
    val outsiders = guardrail(GuardrailFilter("${user.email}", "RegexNot(.*@cloud-apim.com)"))
    assert(outsiders.appliesTo(attrsOf(user = outsider.some)), "the guardrail should apply to a user of another domain")
    assert(!outsiders.appliesTo(attrsOf(user = insider.some)), "the guardrail should not apply to a user of the domain")
  }

  test("a call the filter cannot read is left alone, unless the filter asks for it") {
    val onKeys = guardrail(GuardrailFilter("${apikey.id}", "Regex(.*)"))
    assert(!onKeys.appliesTo(attrsOf()), "a call without api key should not be filtered in")
    val onAnonymous = guardrail(GuardrailFilter("${apikey.id}", "NotDefined()"))
    assert(onAnonymous.appliesTo(attrsOf()), "a call without api key should match NotDefined()")
    assert(!onAnonymous.appliesTo(attrsOf(apikey = teamKey.some)), "a call with an api key should not match NotDefined()")
  }

  test("every filter must match") {
    val both = guardrail(GuardrailFilter("${apikey.id}", teamKey.clientId), GuardrailFilter("${user.email}", "Regex(.*@cloud-apim.com)"))
    assert(both.appliesTo(attrsOf(apikey = teamKey.some, user = insider.some)), "both filters match")
    assert(!both.appliesTo(attrsOf(apikey = teamKey.some, user = outsider.some)), "the user does not match")
    assert(!both.appliesTo(attrsOf(apikey = otherKey.some, user = insider.some)), "the api key does not match")
  }

  test("an item stored before filters existed applies to every call, and filters survive a round trip") {
    val legacy = GuardrailItem.format.reads(Json.obj("enabled" -> true, "before" -> true, "after" -> false, "id" -> "regex", "config" -> Json.obj())).get
    assertEquals(legacy.filters, Seq.empty)
    assert(legacy.appliesTo(attrsOf(apikey = teamKey.some)), "a guardrail stored without filters should apply to every call")
    val filtered = guardrail(GuardrailFilter("${apikey.id}", "ContainedIn(a, b)"))
    assertEquals(GuardrailItem.format.reads(filtered.json).get, filtered)
    assertEquals(filtered.json.select("filters").as[Seq[JsObject]], Seq(Json.obj("from" -> "${apikey.id}", "value" -> "ContainedIn(a, b)")))
  }

  ///////////////////////////////////////////////////////////////////////////////////////////////////
  // the same, on a real call: the guardrail only fires for the key it names
  ///////////////////////////////////////////////////////////////////////////////////////////////////

  def provider(filters: GuardrailFilter*): AiProvider = AiProvider(
    id = "provider_guardrail_filters",
    name = "guardrail filters",
    provider = "ollama",
    connection = Json.obj("base_url" -> s"http://localhost:${ollamaPort}", "timeout" -> 30000),
    options = Json.obj("model" -> "llama2"),
    guardrailsFailOnDeny = true,
    guardrails = Guardrails(Seq(GuardrailItem(
      enabled = true,
      before = true,
      after = false,
      guardrailId = "regex",
      config = Json.obj("deny" -> Json.arr(".*banana.*"), "allow" -> Json.arr()),
      filters = filters,
    ))),
  )

  val route: NgRoute = NgRoute(
    location = EntityLocation.default,
    id = "route_guardrail_filters",
    name = "guardrail filters",
    description = "guardrail filters",
    tags = Seq.empty,
    metadata = Map.empty,
    enabled = true,
    debugFlow = false,
    capture = false,
    exportReporting = false,
    frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath("guardrail-filters.oto.tools/chat"))),
    backend = NgBackend.empty.copy(targets = Seq(NgTarget.default)),
    plugins = NgPlugins(Seq(
      NgPluginInstance(plugin = "cp:otoroshi.next.plugins.ApikeyCalls", config = NgPluginInstanceConfig(NgApikeyCallsConfig().json.asObject)),
      NgPluginInstance(plugin = s"cp:${classOf[OpenAiCompatProxy].getName}", config = NgPluginInstanceConfig(Json.obj("refs" -> Json.arr("provider_guardrail_filters")))),
    )),
  )

  lazy val setup: Unit = {
    Seq(teamKey, otherKey).map(_.copy(authorizedEntities = Seq(RouteIdentifier(route.id))))
      .foreach(k => client.forEntity("apim.otoroshi.io", "v1", "apikeys").upsertEntity(k).awaitf(10.seconds))
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(route).awaitf(10.seconds)
    await(10.seconds)
  }

  def chat(apikey: ApiKey, guardrailFilters: GuardrailFilter*): WSResponse = {
    setup
    client.forLlmEntity("providers").upsertEntity(provider(guardrailFilters*)).awaitf(10.seconds)
    await(2.seconds)
    val basic = Base64.getEncoder.encodeToString(s"${apikey.clientId}:${apikey.clientSecret}".getBytes(StandardCharsets.UTF_8))
    client.call("POST", s"http://guardrail-filters.oto.tools:${port}/chat", Map("Authorization" -> s"Basic ${basic}"), Some(Json.obj(
      "messages" -> Json.arr(Json.obj("role" -> "user", "content" -> "I would like a banana please")),
    ))).awaitf(30.seconds)
  }

  test("a filtered guardrail only blocks the consumers it names") {
    val onTeam = GuardrailFilter("${apikey.id}", teamKey.clientId)
    chatCalls.set(0)
    val denied = chat(teamKey, onTeam)
    assertEquals(denied.status, 400, s"the key named by the filter should have been blocked: ${denied.body}")
    assertEquals(chatCalls.get(), 0, "the provider should not have been called")
    val served = chat(otherKey, onTeam)
    assertEquals(served.status, 200, s"the other key should have been served: ${served.body}")
    assertEquals(chatCalls.get(), 1, "the provider should have served the other key")
  }

  test("a filter reads the request of the call") {
    chatCalls.set(0)
    val denied = chat(otherKey, GuardrailFilter("${req.ip}", "ContainedIn(127.0.0.1, 0:0:0:0:0:0:0:1)"))
    assertEquals(denied.status, 400, s"a call from a listed ip should have been blocked: ${denied.body}")
    assertEquals(chatCalls.get(), 0, "the provider should not have been called")
  }
}
