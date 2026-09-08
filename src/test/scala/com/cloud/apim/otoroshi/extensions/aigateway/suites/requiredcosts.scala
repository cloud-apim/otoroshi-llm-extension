package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.LlmExtensionOneOtoroshiServerPerSuite
import com.cloud.apim.otoroshi.extensions.aigateway.decorators.RequiredCosts
import com.cloud.apim.otoroshi.extensions.aigateway.{EmbeddingClientInputOptions, ImageModelClientGenerationInputOptions}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiProvider, EmbeddingModel, ImageModel, ModelSettings}
import otoroshi.env.Env
import otoroshi.models.EntityLocation
import otoroshi.next.models.*
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.OpenAiCompatProxy
import play.api.libs.json.Json
import play.api.libs.ws.WSResponse
import reactor.core.publisher.Mono

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.DurationInt

// `models.require_known_costs` makes a provider refuse anything it cannot price: the model is dropped from
// the listings, and a call using it is rejected before the provider is contacted at all.
class RequiredCostsSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  // a model the bundled price table knows for ollama, and one it will never know
  val pricedModel = "llama2"
  val unpricedModel = "totally-made-up-model"

  val chatCalls = new AtomicInteger(0)

  val (ollamaPort, _) = createTestServerWithRoutes("ollama-costs", routes => routes
    .post("/api/chat", (req, response) => {
      chatCalls.incrementAndGet()
      req.receiveContent().ignoreElements().subscribe()
      response
        .status(200)
        .addHeader("Content-Type", "application/json")
        .sendString(Mono.just(
          s"""{
             |  "model": "${pricedModel}",
             |  "created_at": "2023-12-12T14:13:43.416799Z",
             |  "message": { "role": "assistant", "content": "hello" },
             |  "done": true,
             |  "prompt_eval_count": 26,
             |  "eval_count": 298
             |}""".stripMargin))
    })
    .get("/api/tags", (req, response) => response
      .status(200)
      .addHeader("Content-Type", "application/json")
      .sendString(Mono.just(
        s"""{"models":[{"name":"${pricedModel}"},{"name":"${unpricedModel}"}]}"""))
    )
  )

  def ext: AiExtension = otoroshi.env.adminExtensions.extension[AiExtension].get

  def provider(name: String, host: String, requireKnownCosts: Boolean): AiProvider = AiProvider(
    id = UUID.randomUUID().toString,
    name = name,
    provider = "ollama",
    connection = Json.obj("base_url" -> s"http://localhost:${ollamaPort}", "timeout" -> 30000),
    options = Json.obj("model" -> pricedModel),
    models = ModelSettings(requireKnownCosts = requireKnownCosts),
  )

  def routeFor(host: String, providerId: String): NgRoute = NgRoute(
    location = EntityLocation.default,
    id = UUID.randomUUID().toString,
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
    plugins = NgPlugins(Seq(NgPluginInstance(
      plugin = s"cp:${classOf[OpenAiCompatProxy].getName}",
      config = NgPluginInstanceConfig(Json.obj("refs" -> Json.arr(providerId)))
    )))
  )

  lazy val setup: (AiProvider, AiProvider) = {
    val lenient = provider("costs not required", "lenient.oto.tools", requireKnownCosts = false)
    val strict = provider("costs required", "strict.oto.tools", requireKnownCosts = true)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").upsertEntity(lenient).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").upsertEntity(strict).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(routeFor("lenient.oto.tools", lenient.id)).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(routeFor("strict.oto.tools", strict.id)).awaitf(10.seconds)
    await(10.seconds)
    (lenient, strict)
  }

  def chat(host: String, model: String): WSResponse = {
    client.call("POST", s"http://${host}:${port}/chat", Map.empty, Some(Json.obj(
      "model" -> model,
      "messages" -> Json.arr(Json.obj("role" -> "user", "content" -> "hey")),
    ))).awaitf(30.seconds)
  }

  def listModels(p: AiProvider): List[String] = {
    ext.states.provider(p.id).flatMap(_.getChatClient()(using otoroshi.env)).get
      .listModels(false, TypedMap.empty)(using ec)
      .awaitf(30.seconds)
      .toOption
      .get
  }

  test("the gate is a no-op unless the provider asks for it") {
    given env: Env = otoroshi.env
    val off = ModelSettings(requireKnownCosts = false)
    assertEquals(RequiredCosts.check(Some("ollama"), off, Some(unpricedModel)), Right(()))
    assertEquals(RequiredCosts.check(None, off, None), Right(()))
    assertEquals(RequiredCosts.filterModels(Some("ollama"), off, List(pricedModel, unpricedModel)), List(pricedModel, unpricedModel))
  }

  test("a model with no known price is refused, an unresolvable provider too") {
    given env: Env = otoroshi.env
    val on = ModelSettings(requireKnownCosts = true)
    assertEquals(RequiredCosts.check(Some("ollama"), on, Some(pricedModel)), Right(()))
    assert(RequiredCosts.check(Some("ollama"), on, Some(unpricedModel)).isLeft, "an unpriced model must be refused")
    // no model at all means no price can be guaranteed either
    assert(RequiredCosts.check(Some("ollama"), on, None).isLeft, "an unresolved model must be refused")
    // openai-compatible and friends map to no pricing provider: nothing they serve can be priced
    assert(RequiredCosts.check(None, on, Some(pricedModel)).isLeft, "a provider with no pricing mapping must be refused")
    assertEquals(RequiredCosts.filterModels(Some("ollama"), on, List(pricedModel, unpricedModel)), List(pricedModel))
  }

  test("without the flag, an unpriced model is served as before") {
    val (lenient, _) = setup
    chatCalls.set(0)
    val resp = chat("lenient.oto.tools", unpricedModel)
    assertEquals(resp.status, 200, s"status should be 200, got ${resp.body}")
    assertEquals(chatCalls.get(), 1, "the provider should have been called")
    assertEquals(listModels(lenient).sorted, List(pricedModel, unpricedModel).sorted)
  }

  test("with the flag, an unpriced model is rejected without ever reaching the provider") {
    setup
    chatCalls.set(0)
    val resp = chat("strict.oto.tools", unpricedModel)
    assertEquals(resp.status, 400, s"status should be 400, got ${resp.status} - ${resp.body}")
    assertEquals(resp.json.select("error").asOptString, RequiredCosts.errorMessage.some, s"unexpected error: ${resp.body}")
    assertEquals(resp.json.select("model").asOptString, unpricedModel.some)
    assertEquals(chatCalls.get(), 0, "the provider must not have been called at all")
  }

  test("with the flag, a priced model still goes through") {
    setup
    chatCalls.set(0)
    val resp = chat("strict.oto.tools", pricedModel)
    assertEquals(resp.status, 200, s"status should be 200, got ${resp.body}")
    assertEquals(chatCalls.get(), 1, "the provider should have been called")
  }

  test("with the flag, /models only lists what can be priced") {
    val (_, strict) = setup
    assertEquals(listModels(strict), List(pricedModel))
  }

  // the non-text modalities have no listing endpoint, but the same gate must keep them from calling a
  // provider they could not bill. A rejected call never opens a connection, so no upstream is needed here.
  test("the gate covers the non text modalities too") {
    given env: Env = otoroshi.env
    val loc = EntityLocation.default
    val strictModels = ModelSettings(requireKnownCosts = true)
    val connection = Json.obj("base_url" -> s"http://localhost:${ollamaPort}", "token" -> "xxx")

    val embedding = EmbeddingModel(loc, UUID.randomUUID().toString, "embedding", "", Seq.empty, Map.empty, "openai",
      Json.obj("connection" -> connection, "options" -> Json.obj("model" -> unpricedModel)), strictModels)
    val embeddingResult = embedding.getEmbeddingModelClient().get
      .embed(EmbeddingClientInputOptions(input = Seq("hey"), model = unpricedModel.some), Json.obj(), TypedMap.empty)(using ec, otoroshi.env)
      .awaitf(10.seconds)
    assert(embeddingResult.isLeft, s"an unpriced embedding model must be refused, got ${embeddingResult}")
    assertEquals(embeddingResult.left.toOption.get.select("error").asOptString, RequiredCosts.errorMessage.some)

    val image = ImageModel(loc, UUID.randomUUID().toString, "image", "", Seq.empty, Map.empty, "openai",
      Json.obj("connection" -> connection, "options" -> Json.obj("generation" -> Json.obj("model" -> unpricedModel))), strictModels)
    val imageResult = image.getImageModelClient().get
      .generate(ImageModelClientGenerationInputOptions(prompt = "hey", model = unpricedModel.some), Json.obj(), TypedMap.empty)(using ec, otoroshi.env)
      .awaitf(10.seconds)
    assert(imageResult.isLeft, s"an unpriced image model must be refused, got ${imageResult}")
    assertEquals(imageResult.left.toOption.get.select("error").asOptString, RequiredCosts.errorMessage.some)

    // and the gate stays out of the way when the flag is off
    val lenientImage = image.copy(models = ModelSettings.empty)
    assert(lenientImage.getImageModelClient().isDefined, "the client must still build without the flag")
  }

  test("the flag survives a json round trip on the entity") {
    val strict = provider("round trip", "roundtrip.oto.tools", requireKnownCosts = true)
    assertEquals(strict.json.at("models.require_known_costs").asOptBoolean, true.some)
    val reread = AiProvider.format.reads(strict.json).get
    assertEquals(reread.models.requireKnownCosts, true)
    assertEquals(AiProvider.format.reads(strict.json.asObject - "models").get.models.requireKnownCosts, false)
  }
}
