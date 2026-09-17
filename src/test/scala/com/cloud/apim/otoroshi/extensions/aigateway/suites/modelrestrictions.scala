package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.LlmExtensionOneOtoroshiServerPerSuite
import com.cloud.apim.otoroshi.extensions.aigateway.decorators.{EmbeddingModelClientWithModels, ModelConstraints, ModelTarget, OcrModelClientWithModels}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiProvider, EmbeddingModel, ModelSettings, OcrModel}
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatMessage, ChatPrompt, Embedding, EmbeddingClientInputOptions, EmbeddingModelClient, EmbeddingResponse, EmbeddingResponseMetadata, OcrModelClient, OcrModelClientInputOptions, OcrModelClientResponse}
import otoroshi.env.Env
import otoroshi.models.{ApiKey, EntityLocation, PrivateAppsUser, RouteIdentifier}
import otoroshi.next.models.*
import otoroshi.next.plugins.NgApikeyCallsConfig
import otoroshi.plugins.Keys
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.OpenAiCompatProxy
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.libs.ws.WSResponse
import reactor.core.publisher.Mono

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.DurationInt

// The models an api key or a user may use (`ai_models_include` / `ai_models_exclude` metadata) are checked
// on top of the models of the provider, for every call: a provider that restricts nothing must not lift
// the restrictions of its consumers. A pattern can target a model alone, or a model of one provider with the
// `<provider>/<model>` and `<provider>###<model>` forms of the unified apis.
class ModelRestrictionsSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  val chatCalls = new AtomicInteger(0)
  val brokenCalls = new AtomicInteger(0)

  val (ollamaPort, _) = createTestServerWithRoutes("ollama-restrictions", routes => routes
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
    .post("/broken/api/chat", (req, response) => {
      brokenCalls.incrementAndGet()
      req.receiveContent().ignoreElements().subscribe()
      response.status(500).addHeader("Content-Type", "application/json").sendString(Mono.just("""{"error":"boom"}"""))
    })
    .get("/api/tags", (req, response) => response
      .status(200)
      .addHeader("Content-Type", "application/json")
      .sendString(Mono.just("""{"models":[{"name":"llama2"},{"name":"qwen3"},{"name":"mistral"}]}"""))
    )
  )

  def ext: AiExtension = otoroshi.env.adminExtensions.extension[AiExtension].get

  def provider(id: String, name: String, models: ModelSettings): AiProvider = AiProvider(
    id = id,
    name = name,
    provider = "ollama",
    connection = Json.obj("base_url" -> s"http://localhost:${ollamaPort}", "timeout" -> 30000),
    options = Json.obj("model" -> "llama2"),
    models = models,
  )

  def routeFor(id: String, host: String, providerIds: String*): NgRoute = NgRoute(
    location = EntityLocation.default,
    id = id,
    name = s"route ${host}",
    description = s"route ${host}",
    tags = Seq.empty,
    metadata = Map.empty,
    enabled = true,
    debugFlow = false,
    capture = false,
    exportReporting = false,
    frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath(s"${host}/chat"))),
    backend = NgBackend.empty.copy(targets = Seq(NgTarget.default)),
    plugins = NgPlugins(Seq(
      NgPluginInstance(
        plugin = "cp:otoroshi.next.plugins.ApikeyCalls",
        config = NgPluginInstanceConfig(NgApikeyCallsConfig().json.asObject),
      ),
      NgPluginInstance(
        plugin = s"cp:${classOf[OpenAiCompatProxy].getName}",
        config = NgPluginInstanceConfig(Json.obj("refs" -> providerIds)),
      ),
    ))
  )

  def key(id: String, metadata: Map[String, String]): ApiKey = ApiKey(
    clientId = id,
    clientSecret = s"${id}-secret",
    clientName = id,
    authorizedEntities = Seq("open", "guarded", "both", "routing").map(r => RouteIdentifier(s"route_restrictions_$r")),
    metadata = metadata,
  )

  def user(metadata: Map[String, String]): PrivateAppsUser =
    PrivateAppsUser(randomId = "restricted-user", name = "jane", email = "jane@acme.io", profile = Json.obj(), realm = "test",
      authConfigId = "test", otoroshiData = None, tags = Seq.empty, metadata = metadata, location = EntityLocation())

  // a provider restricting nothing, and one serving only llama2 and qwen3
  val open = provider("provider_restrictions_open", "open", ModelSettings.empty)
  val guarded = provider("provider_restrictions_guarded", "guarded", ModelSettings(include = Seq("llama2", "qwen3")))
  // an api key limited to llama2, one that may use anything but qwen3, and one with no restriction
  val onlyLlama = key("key_only_llama", Map("ai_models_include" -> "llama2"))
  val noQwen = key("key_no_qwen", Map("ai_models_exclude" -> "qwen3"))
  val free = key("key_free", Map.empty)
  // qwen3 only from the open provider, llama2 from anywhere; and nothing from the guarded provider
  val openQwen = key("key_open_qwen", Map("ai_models_include" -> "open/qwen3, llama2"))
  val notGuarded = key("key_not_guarded", Map("ai_models_exclude" -> "guarded###.*"))

  // routing providers handing calls over to the open provider: a load balancer, a code router, and a
  // provider that always fails, with the open provider as fallback
  val balanced = AiProvider(id = "provider_restrictions_balanced", name = "balanced", provider = "loadbalancer", connection = Json.obj(),
    options = Json.obj("refs" -> Json.arr(open.id), "loadbalancing" -> "round_robin"))
  val routed = AiProvider(id = "provider_restrictions_routed", name = "routed", provider = "otoroshi", connection = Json.obj(),
    options = Json.obj("code_router_refs" -> Json.arr(open.id)))
  val broken = provider("provider_restrictions_broken", "broken", ModelSettings.empty).copy(
    connection = Json.obj("base_url" -> s"http://localhost:${ollamaPort}/broken", "timeout" -> 30000),
    providerFallback = Some(open.id),
  )
  // the consumers of the routing providers only
  val onlyBalanced = key("key_only_balanced", Map("ai_models_include" -> "balanced/_default"))
  val anyBalanced = key("key_any_balanced", Map("ai_models_include" -> "balanced/.*"))
  val onlyRouted = key("key_only_routed", Map("ai_models_include" -> "routed/code-router"))
  val onlyBroken = key("key_only_broken", Map("ai_models_include" -> "broken/llama2"))
  val onlyOpen = key("key_only_open", Map("ai_models_include" -> "open/.*"))

  lazy val setup: Unit = {
    Seq(open, guarded, balanced, routed, broken).foreach(p => client.forLlmEntity("providers").upsertEntity(p).awaitf(10.seconds))
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(routeFor("route_restrictions_open", "open-restrictions.oto.tools", open.id)).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(routeFor("route_restrictions_guarded", "guarded-restrictions.oto.tools", guarded.id)).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(routeFor("route_restrictions_both", "both-restrictions.oto.tools", open.id, guarded.id)).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(routeFor("route_restrictions_routing", "routing-restrictions.oto.tools", open.id, balanced.id, routed.id, broken.id)).awaitf(10.seconds)
    Seq(onlyLlama, noQwen, free, openQwen, notGuarded, onlyBalanced, anyBalanced, onlyRouted, onlyBroken, onlyOpen).foreach(k => client.forEntity("apim.otoroshi.io", "v1", "apikeys").upsertEntity(k).awaitf(10.seconds))
    await(10.seconds)
  }

  def chat(host: String, apikey: ApiKey, model: String): WSResponse = {
    setup
    val basic = Base64.getEncoder.encodeToString(s"${apikey.clientId}:${apikey.clientSecret}".getBytes(StandardCharsets.UTF_8))
    client.call("POST", s"http://${host}:${port}/chat", Map("Authorization" -> s"Basic ${basic}"), Some(Json.obj(
      "model" -> model,
      "messages" -> Json.arr(Json.obj("role" -> "user", "content" -> "hey")),
    ))).awaitf(30.seconds)
  }

  def assertAllowed(host: String, apikey: ApiKey, model: String): Unit = {
    chatCalls.set(0)
    val resp = chat(host, apikey, model)
    assertEquals(resp.status, 200, s"${apikey.clientId} should use ${model} on ${host}: ${resp.body}")
    assertEquals(chatCalls.get(), 1, s"the provider should have served ${model} for ${apikey.clientId}")
  }

  def assertDenied(host: String, apikey: ApiKey, model: String): Unit = {
    chatCalls.set(0)
    val resp = chat(host, apikey, model)
    assertEquals(resp.status, 400, s"${apikey.clientId} should not use ${model} on ${host}: ${resp.body}")
    assert(resp.body.contains("you can't use this model"), s"bad error for ${apikey.clientId} and ${model}: ${resp.body}")
    assertEquals(chatCalls.get(), 0, s"the provider should not have been called for ${apikey.clientId} and ${model}")
  }

  // the chat client of a provider with its whole decorator chain, called in process
  def decorated(p: AiProvider, attrs: TypedMap, model: Option[String]): Either[JsValue, ?] = {
    setup
    given env: Env = otoroshi.env
    val body = model.map(m => Json.obj("model" -> m)).getOrElse(Json.obj())
    ext.states.provider(p.id).get.getChatClient().get
      .call(ChatPrompt(Seq(ChatMessage.userStrInput("hello"))), attrs, body)(using otoroshi.executionContext, env)
      .awaitf(30.seconds)
  }

  def listed(p: AiProvider, attrs: TypedMap): List[String] = {
    setup
    given env: Env = otoroshi.env
    ext.states.provider(p.id).get.getChatClient().get.listModels(false, attrs)(using otoroshi.executionContext).awaitf(30.seconds).toOption.get
  }

  test("an api key restriction applies on a provider that restricts nothing") {
    assertAllowed("open-restrictions.oto.tools", onlyLlama, "llama2")
    assertDenied("open-restrictions.oto.tools", onlyLlama, "qwen3")
    assertAllowed("open-restrictions.oto.tools", noQwen, "mistral")
    assertDenied("open-restrictions.oto.tools", noQwen, "qwen3")
    // the listing already hid them: the call and the listing agree
    assertEquals(listed(open, TypedMap(Keys.ApiKeyKey -> onlyLlama)), List("llama2"))
    assertEquals(listed(open, TypedMap(Keys.ApiKeyKey -> noQwen)).sorted, List("llama2", "mistral"))
  }

  test("an api key restriction adds up with the restriction of the provider") {
    assertAllowed("guarded-restrictions.oto.tools", onlyLlama, "llama2")
    assertDenied("guarded-restrictions.oto.tools", onlyLlama, "qwen3")
    assertDenied("guarded-restrictions.oto.tools", onlyLlama, "mistral")
    assertAllowed("guarded-restrictions.oto.tools", noQwen, "llama2")
    assertDenied("guarded-restrictions.oto.tools", noQwen, "qwen3")
    assertDenied("guarded-restrictions.oto.tools", noQwen, "mistral")
  }

  test("without any restriction, every model is served") {
    assertAllowed("open-restrictions.oto.tools", free, "llama2")
    assertAllowed("open-restrictions.oto.tools", free, "qwen3")
    assertAllowed("guarded-restrictions.oto.tools", free, "qwen3")
    assertDenied("guarded-restrictions.oto.tools", free, "mistral")
  }

  test("a user restriction applies on a provider that restricts nothing") {
    val jane = TypedMap(Keys.UserKey -> user(Map("ai_models_include" -> "llama2")))
    chatCalls.set(0)
    assert(decorated(open, jane, Some("llama2")).isRight, "jane may use llama2")
    assertEquals(chatCalls.get(), 1)
    val denied = decorated(open, jane, Some("qwen3"))
    assert(denied.isLeft, s"jane may not use qwen3: ${denied}")
    assertEquals(chatCalls.get(), 1, "the provider should not have been called for qwen3")
    // a key and a user both restricting: both must allow the model
    val both = TypedMap(
      Keys.UserKey -> user(Map("ai_models_include" -> "llama2|qwen3")),
      Keys.ApiKeyKey -> noQwen,
    )
    assert(decorated(open, both, Some("llama2")).isRight, "llama2 is allowed by the key and the user")
    assert(decorated(open, both, Some("qwen3")).isLeft, "qwen3 is refused by the key")
    assert(decorated(open, both, Some("mistral")).isLeft, "mistral is refused by the user")
  }

  test("a pattern can target the model of one provider") {
    val both = "both-restrictions.oto.tools"
    assertAllowed(both, openQwen, "open/qwen3")
    assertAllowed(both, openQwen, "open###qwen3")
    assertAllowed(both, openQwen, "provider_restrictions_open/qwen3")
    assertDenied(both, openQwen, "guarded/qwen3")
    assertDenied(both, openQwen, "guarded###qwen3")
    assertAllowed(both, openQwen, "guarded/llama2")
    assertAllowed(both, openQwen, "open/llama2")
    assertDenied(both, openQwen, "open/mistral")
    assertDenied(both, notGuarded, "guarded/llama2")
    assertDenied(both, notGuarded, "provider_restrictions_guarded###llama2")
    assertAllowed(both, notGuarded, "open/llama2")
    assertAllowed(both, notGuarded, "open/qwen3")
    // and the listings agree
    assertEquals(listed(open, TypedMap(Keys.ApiKeyKey -> openQwen)), List("llama2", "qwen3"))
    assertEquals(listed(guarded, TypedMap(Keys.ApiKeyKey -> openQwen)), List("llama2"))
    assertEquals(listed(guarded, TypedMap(Keys.ApiKeyKey -> notGuarded)), List())
  }

  test("a load balancer serves what its consumers may ask it, wherever it sends the call") {
    val routing = "routing-restrictions.oto.tools"
    assertAllowed(routing, onlyBalanced, "balanced/_default")
    assertDenied(routing, onlyBalanced, "balanced/qwen3")
    assertDenied(routing, onlyBalanced, "open/llama2")
    // the model asked to the balancer goes to its target, and was allowed
    assertAllowed(routing, anyBalanced, "balanced/qwen3")
    assertDenied(routing, anyBalanced, "open/qwen3")
    // a key allowed on the target only cannot use the balancer
    assertDenied(routing, onlyLlama, "balanced/_default")
  }

  test("a router serves what its consumers may ask it, whatever candidate it picks") {
    val routing = "routing-restrictions.oto.tools"
    assertAllowed(routing, onlyRouted, "routed/code-router")
    assertDenied(routing, onlyRouted, "routed/auto-router")
    assertDenied(routing, onlyRouted, "open/llama2")
    assertDenied(routing, onlyLlama, "routed/code-router")
  }

  test("a fallback serves the calls its consumers were allowed to make to the primary provider, and only those") {
    val routing = "routing-restrictions.oto.tools"
    brokenCalls.set(0)
    assertAllowed(routing, onlyBroken, "broken/llama2")
    assertEquals(brokenCalls.get(), 1, "the primary provider should have been tried first")
    // qwen3 was refused by the primary provider: the fallback must not serve it either
    brokenCalls.set(0)
    assertDenied(routing, onlyBroken, "broken/qwen3")
    assertEquals(brokenCalls.get(), 0, "the primary provider should not have been called")
    assertDenied(routing, onlyBroken, "open/llama2")
    // a key allowed on the fallback provider, not on the primary one
    assertDenied(routing, onlyOpen, "broken/llama2")
    assertEquals(brokenCalls.get(), 0, "the primary provider should not have been called")
    assertAllowed(routing, onlyOpen, "open/qwen3")
  }

  test("a model is allowed only when every level allows it, an unknown one only when nothing restricts") {
    def target(settings: ModelSettings): ModelTarget = ModelTarget("provider_x", "x", settings)
    val none = TypedMap.empty
    val restricted = TypedMap(Keys.ApiKeyKey -> onlyLlama)
    assert(ModelConstraints.allows(target(ModelSettings.empty), Some("qwen3"), none))
    assert(ModelConstraints.allows(target(ModelSettings.empty), None, none))
    assert(ModelConstraints.allows(target(ModelSettings.empty), Some("llama2"), restricted))
    assert(!ModelConstraints.allows(target(ModelSettings.empty), Some("qwen3"), restricted))
    assert(!ModelConstraints.allows(target(ModelSettings.empty), None, restricted), "an unknown model cannot be checked against the key")
    assert(!ModelConstraints.allows(target(ModelSettings(include = Seq("qwen3"))), None, none), "nor against the provider")
    // a blank metadata value restricts nothing
    val blank = TypedMap(Keys.ApiKeyKey -> key("key_blank", Map("ai_models_include" -> " ", "ai_models_exclude" -> "")))
    assert(ModelConstraints.allows(target(ModelSettings.empty), Some("qwen3"), blank))
    assert(ModelConstraints.allows(target(ModelSettings.empty), None, blank))
    // the forms of a provider model
    assertEquals(target(ModelSettings.empty).names(Some("a/b")), Seq("a/b", "x/a/b", "x###a/b", "provider_x/a/b", "provider_x###a/b"))
    assertEquals(target(ModelSettings.empty).names(None), Seq("_default", "x/_default", "x###_default", "provider_x/_default", "provider_x###_default"))
    assert(ModelConstraints.allows(target(ModelSettings.empty), None, TypedMap(Keys.ApiKeyKey -> key("key_default", Map("ai_models_include" -> "x/_default")))))
    assert(ModelConstraints.allows(target(ModelSettings(include = Seq("x###.*"))), Some("qwen3"), none))
    assert(!ModelConstraints.allows(target(ModelSettings(include = Seq("y###.*"))), Some("qwen3"), none))
    assertEquals(ModelConstraints.filter(target(ModelSettings(exclude = Seq("mistral"))), List("llama2", "qwen3", "mistral"), TypedMap(Keys.ApiKeyKey -> noQwen)), List("llama2"))
  }

  test("a restriction applies to every kind of model, even when the model entity restricts nothing") {
    given env: Env = otoroshi.env
    given ec: ExecutionContext = otoroshi.executionContext
    val embeddings = new AtomicInteger(0)
    val raw = new EmbeddingModelClient {
      override def embed(opts: EmbeddingClientInputOptions, rawBody: JsObject, attrs: TypedMap)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, EmbeddingResponse]] = {
        embeddings.incrementAndGet()
        Right(EmbeddingResponse(opts.model.getOrElse("default"), Seq(Embedding(Array(0.1f))), EmbeddingResponseMetadata(1L))).vfuture
      }
    }
    val entity = EmbeddingModel(location = EntityLocation.default, id = "embedding_restrictions", name = "embeddings", description = "",
      tags = Seq.empty, metadata = Map.empty, provider = "ollama", config = Json.obj())
    val constrained = new EmbeddingModelClientWithModels(entity, raw)
    def embed(model: String, apikey: ApiKey): Either[JsValue, EmbeddingResponse] =
      constrained.embed(EmbeddingClientInputOptions(input = Seq("hello"), model = Some(model)), Json.obj(), TypedMap(Keys.ApiKeyKey -> apikey)).awaitf(10.seconds)
    assert(embed("nomic-embed-text", free).isRight, "a free key may embed with any model")
    assert(embed("nomic-embed-text", onlyLlama).isLeft, "a key limited to llama2 may not embed with nomic-embed-text")
    assertEquals(embeddings.get(), 1, "the refused call should not reach the model")
  }

  test("ocr models are restricted too") {
    given env: Env = otoroshi.env
    given ec: ExecutionContext = otoroshi.executionContext
    val reached: JsValue = Json.obj("reached" -> true)
    val raw = new OcrModelClient {
      override def ocr(options: OcrModelClientInputOptions, rawBody: JsObject, attrs: TypedMap)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, OcrModelClientResponse]] =
        Left(reached).vfuture
    }
    def ocr(entityModels: ModelSettings, model: String, apikey: ApiKey): Either[JsValue, OcrModelClientResponse] = {
      val entity = OcrModel(location = EntityLocation.default, id = "ocr_restrictions", name = "ocr", description = "", tags = Seq.empty,
        metadata = Map.empty, provider = "mistral", config = Json.obj(), models = entityModels)
      new OcrModelClientWithModels(entity, raw).ocr(OcrModelClientInputOptions(model = Some(model)), Json.obj(), TypedMap(Keys.ApiKeyKey -> apikey)).awaitf(10.seconds)
    }
    assertEquals(ocr(ModelSettings.empty, "mistral-ocr-latest", free), Left(reached))
    assertEquals(ocr(ModelSettings.empty, "mistral-ocr-latest", onlyLlama), Left(ModelConstraints.denied))
    assertEquals(ocr(ModelSettings(include = Seq("other-ocr")), "mistral-ocr-latest", free), Left(ModelConstraints.denied))
    assertEquals(ocr(ModelSettings.empty, "mistral-ocr-latest", key("key_ocr", Map("ai_models_include" -> "ocr/mistral-ocr-latest"))), Left(reached))
  }
}
