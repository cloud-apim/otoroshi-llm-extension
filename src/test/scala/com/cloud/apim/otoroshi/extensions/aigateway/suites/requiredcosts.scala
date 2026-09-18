package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.LlmExtensionOneOtoroshiServerPerSuite
import com.cloud.apim.otoroshi.extensions.aigateway.catalog.ModelEndpoints
import com.cloud.apim.otoroshi.extensions.aigateway.decorators.RequiredCosts
import com.cloud.apim.otoroshi.extensions.aigateway.{AudioModelClientSpeechToTextInputOptions, AudioModelClientTextToSpeechInputOptions, EmbeddingClientInputOptions, ImageModelClientGenerationInputOptions}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiProvider, AiProvidersCatalog, AudioModel, EmbeddingModel, ImageModel, ModelSettings}
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import otoroshi.env.Env
import otoroshi.models.EntityLocation
import otoroshi.next.models.*
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.OpenAiCompatProxy
import play.api.libs.json.{JsObject, Json}
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
    assertEquals(RequiredCosts.filterModels(Some("ollama"), "ollama", off, List(pricedModel, unpricedModel)), List(pricedModel, unpricedModel))
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
    assertEquals(RequiredCosts.filterModels(Some("ollama"), "ollama", on, List(pricedModel, unpricedModel)), List(pricedModel))
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

  // the second half of the promise: a price the gateway cannot turn into an amount is not a price it can
  // bill. These models are exactly the ones the listings mark `has_cost: false` and the studio "Not billed".
  test("a price in a unit the gateway cannot measure is refused too") {
    given env: Env = otoroshi.env
    val on = ModelSettings(requireKnownCosts = true)
    val audio = Seq(AiProvidersCatalog.Audio)
    val openai = Some("openai")

    // whisper is billed per second of audio read, a duration nothing in the call reports back
    val whisper = RequiredCosts.check(openai, on, Some("whisper-1"), audio, Seq(ModelEndpoints.AudioTranscriptions))
    assert(whisper.isLeft, "a model priced per second of audio must be refused")
    assertEquals(whisper.left.toOption.get.select("error").asOptString, RequiredCosts.unbillableMessage.some)
    // and the refusal says which of the two it is: the grid knows this model, it just cannot bill it
    assert(RequiredCosts.hasKnownCosts(openai, "whisper-1"), "the grid does know whisper-1")

    // the same voice model answers differently per endpoint: read the characters, bill them
    assertEquals(RequiredCosts.check(openai, on, Some("tts-1"), audio, Seq(ModelEndpoints.AudioSpeech)), Right(()))
    // this one is billed on the tokens of the text it reads and the seconds it speaks, neither of them counted
    assert(RequiredCosts.check(openai, on, Some("gpt-4o-mini-tts"), audio, Seq(ModelEndpoints.AudioSpeech)).isLeft)
    // transcriptions the provider counts in tokens are billed as usual
    assertEquals(RequiredCosts.check(openai, on, Some("gpt-4o-transcribe"), audio, Seq(ModelEndpoints.AudioTranscriptions)), Right(()))
    // images are billed on their tokens, ocr on the pages read
    assertEquals(RequiredCosts.check(openai, on, Some("gpt-image-1"), Seq(AiProvidersCatalog.Image)), Right(()))
    assertEquals(RequiredCosts.check(Some("mistral"), on, Some("mistral-ocr-latest"), Seq(AiProvidersCatalog.Ocr)), Right(()))

    // without a modality the rule is the per token one of text, embeddings and moderations: unchanged
    assertEquals(RequiredCosts.check(openai, on, Some("whisper-1")), Right(()))
  }

  test("a listing offers nothing a call would refuse") {
    given env: Env = otoroshi.env
    val on = ModelSettings(requireKnownCosts = true)
    val models = List("gpt-4o-mini", "gpt-image-1", "tts-1", "whisper-1", "gpt-4o-mini-tts", unpricedModel)
    // the listing knows nothing but names: the price entry says what each model is and where it is served
    assertEquals(RequiredCosts.filterModels(Some("openai"), "openai", on, models), List("gpt-4o-mini", "gpt-image-1", "tts-1"))
  }

  test("an audio model priced in an unmeasurable unit never reaches the provider") {
    given env: Env = otoroshi.env
    val connection = Json.obj("base_url" -> s"http://localhost:${ollamaPort}", "token" -> "xxx")
    def audioModel(config: JsObject) = AudioModel(EntityLocation.default, UUID.randomUUID().toString, "audio", "",
      Seq.empty, Map.empty, "openai", Json.obj("connection" -> connection) ++ config,
      ModelSettings(requireKnownCosts = true))

    // the admin ui writes the models of an audio entity under `options`, the client reads them at the root:
    // the gate must find them wherever they are, exactly like the decorator that bills the call
    val stt = audioModel(Json.obj("options" -> Json.obj("stt" -> Json.obj("model" -> "whisper-1"))))
      .getAudioModelClient().get
      .speechToText(AudioModelClientSpeechToTextInputOptions(Source.single(ByteString("x")), "a.mp3".some, "audio/mpeg", 1L), Json.obj(), TypedMap.empty)(using ec, otoroshi.env)
      .awaitf(10.seconds)
    assert(stt.isLeft, s"a transcription billed per second of audio must be refused, got ${stt}")
    assertEquals(stt.left.toOption.get.select("error").asOptString, RequiredCosts.unbillableMessage.some)

    // a voice billed on the characters it reads goes through the gate and reaches the provider, which is the
    // fake ollama of this suite: it answers anything but the gate's own refusal
    val tts = audioModel(Json.obj("tts" -> Json.obj("model" -> "tts-1")))
      .getAudioModelClient().get
      .textToSpeech(AudioModelClientTextToSpeechInputOptions(input = "hello", model = "tts-1".some), Json.obj(), TypedMap.empty)(using ec, otoroshi.env)
      .awaitf(10.seconds)
    val refusals = Set(RequiredCosts.errorMessage, RequiredCosts.unbillableMessage)
    assert(!tts.left.toOption.flatMap(_.select("error").asOptString).exists(refusals.contains), s"a billable voice must not be refused by the gate, got ${tts}")
  }

  test("the flag survives a json round trip on the entity") {
    val strict = provider("round trip", "roundtrip.oto.tools", requireKnownCosts = true)
    assertEquals(strict.json.at("models.require_known_costs").asOptBoolean, true.some)
    val reread = AiProvider.format.reads(strict.json).get
    assertEquals(reread.models.requireKnownCosts, true)
    assertEquals(AiProvider.format.reads(strict.json.asObject - "models").get.models.requireKnownCosts, false)
  }
}
