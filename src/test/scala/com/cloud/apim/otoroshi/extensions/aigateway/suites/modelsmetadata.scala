package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.LlmExtensionOneOtoroshiServerPerSuite
import com.cloud.apim.otoroshi.extensions.aigateway.catalog.{CatalogMatch, ModelIds, ModelKinds, ModelsCatalog, ModelsCatalogIndex, ModelsMetadata}
import com.cloud.apim.otoroshi.extensions.aigateway.decorators.{CostModel, CostsTracking, CostsTrackingSettings}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import otoroshi.env.Env
import otoroshi.models.EntityLocation
import otoroshi.next.models.*
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.{OpenAiCompatApi, OpenAiCompatModels}
import play.api.Configuration
import play.api.libs.json.{JsObject, JsValue, Json}
import reactor.core.publisher.Mono

import java.util.UUID
import scala.concurrent.duration.DurationInt

// `?enriched=true` on the `/models` listings adds a `_metadata` object to each model, built from the bundled
// models.dev catalog and the price table. The catalog also prices what the price table does not know.
class ModelsMetadataSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  def ext: AiExtension = otoroshi.env.adminExtensions.extension[AiExtension].get

  def model(id: String, extra: JsObject = Json.obj()): (String, JsValue) = id -> (Json.obj(
    "id" -> id,
    "reasoning" -> false,
    "tool_call" -> true,
    "modalities" -> Json.obj("input" -> Json.arr("text"), "output" -> Json.arr("text")),
    "limit" -> Json.obj("context" -> 1000, "output" -> 100),
  ) ++ extra)

  def modelsOf(entries: (String, JsValue)*): JsObject = JsObject(entries)

  val syntheticIndex: ModelsCatalogIndex = ModelsCatalogIndex.parse(Json.obj(
    "models" -> modelsOf(
      model("acme/shared-model"),
    ),
    "providers" -> Json.obj(
      "google" -> Json.obj("models" -> modelsOf(
        model("gemini-9-flash", Json.obj("cost" -> Json.obj("input" -> 0.3, "output" -> 2.5))),
      )),
      "vendor" -> Json.obj("models" -> modelsOf(
        model("big-model", Json.obj("last_updated" -> "2025-01-01", "cost" -> Json.obj("input" -> 1, "output" -> 2))),
        model("big-model-2025-06-01", Json.obj("last_updated" -> "2025-06-01", "cost" -> Json.obj("input" -> 3, "output" -> 4))),
        model("mid-model-latest", Json.obj("cost" -> Json.obj("input" -> 1, "output" -> 1))),
      )),
      "router" -> Json.obj("npm" -> "@ai-sdk/openai-compatible", "models" -> modelsOf(
        model("acme/tiny-model", Json.obj("cost" -> Json.obj("input" -> 0.5, "output" -> 1), "provider" -> Json.obj("npm" -> "@ai-sdk/anthropic"))),
        model("other/tiny-model", Json.obj("cost" -> Json.obj("input" -> 9, "output" -> 9), "provider" -> Json.obj("shape" -> "responses"))),
      )),
      "reseller" -> Json.obj("models" -> modelsOf(
        model("shared-model", Json.obj("tool_call" -> false)),
      )),
    ),
  ))

  test("model ids are normalized the way providers spell them") {
    assertEquals(ModelIds.base("models/Gemini-2.5-Flash"), "gemini-2.5-flash")
    assertEquals(ModelIds.base("~anthropic/claude-sonnet-latest"), "anthropic/claude-sonnet-latest")
    assertEquals(ModelIds.normalized("claude-sonnet-4-5-20250929"), "claude-sonnet-4-5")
    assertEquals(ModelIds.normalized("gpt-4o-2024-08-06"), "gpt-4o")
    assertEquals(ModelIds.normalized("openai/gpt-4.1:free"), "openai/gpt-4-1")
    assertEquals(ModelIds.normalized("claude-sonnet-4@20250514"), "claude-sonnet-4")
    assertEquals(ModelIds.normalized("Meta-Llama-3_3-70B-Instruct"), "meta-llama-3-3-70b-instruct")
    assertEquals(ModelIds.normalized("qwen3:8b"), "qwen3-8b")
    assertEquals(ModelIds.name("@cf/meta/llama-3.1-8b-instruct"), "llama-3-1-8b-instruct")
    assertEquals(ModelIds.approximate("mistral-large-2411"), "mistral-large")
    assertEquals(ModelIds.approximate("models/gemini-2.0-flash-001"), "gemini-2-0-flash")
    assertEquals(ModelIds.approximate("command-r-plus-08-2024"), "command-r-plus")
  }

  test("provider kinds and price table names resolve to models.dev providers") {
    assertEquals(syntheticIndex.providersFor("gemini"), Seq("google"))
    assertEquals(syntheticIndex.providersFor("vendor"), Seq("vendor"))
    assertEquals(syntheticIndex.providersFor("VENDOR"), Seq("vendor"))
    assertEquals(syntheticIndex.providersFor("openai-compatible"), Seq.empty)
    val real = ext.modelsCatalog.load().awaitf(30.seconds).get
    assertEquals(real.providersFor("x-ai"), Seq("xai"))
    assertEquals(real.providersFor("together_ai"), Seq("togetherai"))
    assertEquals(real.providersFor("fireworks_ai"), Seq("fireworks-ai"))
    assertEquals(real.providersFor("ollama"), Seq.empty)
  }

  test("the closest match wins, and only a match on the serving provider is priced") {
    def find(providers: Seq[String], id: String): Option[(String, String)] =
      syntheticIndex.find(providers, id).map(m => (m.model.id, m.kind))

    assertEquals(find(Seq("vendor"), "big-model"), Some(("big-model", CatalogMatch.Exact)))
    assertEquals(find(Seq("vendor"), "BIG-MODEL-2025-06-01"), Some(("big-model-2025-06-01", CatalogMatch.Exact)))
    // an unknown snapshot takes the most recent one
    assertEquals(find(Seq("vendor"), "big-model-2026-01-01"), Some(("big-model-2025-06-01", CatalogMatch.Normalized)))
    assertEquals(find(Seq("vendor"), "mid-model"), Some(("mid-model-latest", CatalogMatch.Normalized)))
    assertEquals(find(Seq("vendor"), "mid-model-2411"), Some(("mid-model-latest", CatalogMatch.Approximate)))
    assertEquals(find(Seq("google"), "models/gemini-9-flash"), Some(("gemini-9-flash", CatalogMatch.Exact)))
    // routing variants are the model they route to, and the vendor tells same-named models apart
    assertEquals(find(Seq("router"), "acme/tiny-model:free"), Some(("acme/tiny-model", CatalogMatch.Normalized)))
    assertEquals(find(Seq("router"), "other/tiny-model:nitro"), Some(("other/tiny-model", CatalogMatch.Normalized)))
    // another provider's description: the provider agnostic one first
    assertEquals(find(Seq.empty, "shared-model"), Some(("acme/shared-model", CatalogMatch.Global)))
    assertEquals(find(Seq("unknown"), "big-model-2026-01-01"), Some(("big-model-2025-06-01", CatalogMatch.Global)))
    assertEquals(find(Seq("vendor"), "nothing-like-it"), None)

    assert(syntheticIndex.find(Seq("vendor"), "big-model").exists(_.priced))
    assert(syntheticIndex.find(Seq("vendor"), "mid-model").exists(_.priced))
    assert(!syntheticIndex.find(Seq("vendor"), "mid-model-2411").exists(_.priced))
    assert(!syntheticIndex.find(Seq.empty, "shared-model").exists(_.priced))

    // how a provider serves some of its models differently
    assertEquals(syntheticIndex.find(Seq("router"), "acme/tiny-model").flatMap(_.model.sdk), Some("@ai-sdk/anthropic"))
    assertEquals(syntheticIndex.find(Seq("router"), "other/tiny-model").flatMap(_.model.shape), Some("responses"))
    assertEquals(syntheticIndex.find(Seq("router"), "other/tiny-model").flatMap(_.model.sdk), None)
    assert(syntheticIndex.find(Seq("vendor"), "mid-model-2411").exists(_.onServingProvider))
    assert(!syntheticIndex.find(Seq.empty, "shared-model").exists(_.onServingProvider))

    val cost = syntheticIndex.find(Seq("google"), "gemini-9-flash").flatMap(_.model.costModel).get
    assertEquals(cost.input_cost_per_token, BigDecimal("0.0000003"))
    assertEquals(cost.output_cost_per_token, BigDecimal("0.0000025"))
  }

  test("the catalog prices what the price table misses, for the serving provider only") {
    ext.modelsCatalog.load().awaitf(30.seconds)
    // known by the price table: it wins
    val table = ext.costsTracking.lookupModel("openai", "gpt-4o").get
    assertEquals(table.raw.select(ModelsCatalog.sourceField).asOptString, None)
    // an unknown snapshot of a model the catalog knows for openai
    val snapshot = ext.costsTracking.lookupModel("openai", "gpt-4o-2099-01-01")
    val expected = ext.modelsCatalog.lookup("openai", "gpt-4o-2099-01-01").get
    assertEquals(expected.kind, CatalogMatch.Normalized)
    assertEquals(snapshot.flatMap(_.raw.select(ModelsCatalog.sourceField).asOptString), Some("models.dev"))
    assertEquals(snapshot.map(_.input_cost_per_token), expected.model.cost.map(_.input / BigDecimal(1000000)))
    assert(ext.costsTracking.canHandle("openai", "gpt-4o-2099-01-01"))
    // capabilities borrowed from another provider never make a price
    assert(ext.modelsCatalog.lookup("ollama", "qwen3:8b").exists(_.kind == CatalogMatch.Global))
    assertEquals(ext.costsTracking.lookupModel("ollama", "qwen3:8b"), None)
    assertEquals(ext.costsTracking.lookupModel("openai", "gpt-totally-made-up"), None)
  }

  test("models are sorted into the otoroshi model types") {
    def kinds(names: Seq[String], mode: Option[String] = None, input: Seq[String] = Seq.empty, output: Seq[String] = Seq.empty, provider: String = "openai", endpoints: Seq[String] = Seq.empty) =
      ModelKinds.of(provider, names, mode, input, output, endpoints)
    val text = Seq("text")
    assertEquals(kinds(Seq("gpt-4o"), input = Seq("text", "image"), output = text), Seq("text"))
    assertEquals(kinds(Seq("gpt-4o-audio"), input = Seq("text", "audio"), output = Seq("text", "audio")), Seq("text", "audio"))
    assertEquals(kinds(Seq("gemini-2.5-flash-image"), output = Seq("text", "image")), Seq("text", "image"))
    // a model only served on image or realtime endpoints does not chat
    assertEquals(kinds(Seq("gpt-image-1.5"), mode = Some("image_generation"), output = Seq("text", "image"), endpoints = Seq("images_generations")), Seq("image"))
    assertEquals(kinds(Seq("gpt-realtime"), mode = Some("realtime"), output = Seq("text", "audio"), endpoints = Seq("realtime")), Seq("audio"))
    assertEquals(kinds(Seq("gpt-audio"), mode = Some("chat"), output = Seq("text", "audio"), endpoints = Seq("chat_completions", "realtime")), Seq("text", "audio"))
    assertEquals(kinds(Seq("odd-model"), output = text, endpoints = Seq("realtime")), Seq("text"))
    assertEquals(kinds(Seq("veo-3"), output = Seq("video")), Seq("video"))
    // a chat model that listens is still a chat model, a model that only listens transcribes
    assertEquals(kinds(Seq("gemini-2.5-flash"), input = Seq("text", "audio", "video"), output = text), Seq("text"))
    assertEquals(kinds(Seq("qwen3-asr-flash"), input = Seq("audio"), output = text), Seq("audio"))
    assertEquals(kinds(Seq("some-speech-model"), mode = Some("audio_transcription")), Seq("audio"))
    // the purpose wins over the modalities, and over a `chat` mode
    assertEquals(kinds(Seq("e5-large-v2", "text-embedding"), output = text), Seq("embedding"))
    assertEquals(kinds(Seq("mistral/mistral-embed"), mode = Some("chat"), output = text), Seq("embedding"))
    assertEquals(kinds(Seq("bge-m3"), mode = Some("embedding")), Seq("embedding"))
    assertEquals(kinds(Seq("meta-llama/llama-guard-4-12b"), mode = Some("chat"), output = text), Seq("moderation"))
    assertEquals(kinds(Seq("omni-moderation-latest")), Seq("moderation"))
    assertEquals(kinds(Seq("deepseek-ocr-2"), input = Seq("text", "image"), output = text), Seq("ocr"))
    assertEquals(kinds(Seq("parse-v5"), mode = Some("ocr")), Seq("ocr"))
    assertEquals(kinds(Seq("any-model"), output = text, provider = "alphaedge"), Seq("ocr"))
    assertEquals(kinds(Seq("gpt-4o-mini-tts"), mode = Some("chat")), Seq("audio"))
    assertEquals(kinds(Seq("tts-1")), Seq("audio"))
    // rankers are none of them
    assertEquals(kinds(Seq("bge-reranker-v2-m3"), output = text), Seq.empty)
    assertEquals(kinds(Seq("some-ranker"), mode = Some("rerank")), Seq.empty)
    // nothing known: the name, then text
    assertEquals(kinds(Seq("dall-e-3")), Seq("image"))
    assertEquals(kinds(Seq("sora-2")), Seq("video"))
    assertEquals(kinds(Seq("llama3.2:latest")), Seq("text"))
  }

  test("a model has a cost when the costs decorator would bill it") {
    given env: Env = otoroshi.env
    ext.modelsCatalog.load().awaitf(30.seconds)
    def provider(kind: String, metadata: Map[String, String] = Map.empty) =
      AiProvider(id = UUID.randomUUID().toString, name = kind, provider = kind, metadata = metadata, connection = Json.obj(), options = Json.obj())
    val openai = provider("openai")
    assert(ModelsMetadata.describe(openai, "gpt-4o").hasCost)
    // priced by the catalog when the price table misses it
    assert(ModelsMetadata.describe(openai, "gpt-4o-2099-01-01").hasCost)
    assert(!ModelsMetadata.describe(openai, "gpt-totally-made-up").hasCost)
    // no pricing provider, no cost, whatever the model
    assert(!ModelsMetadata.describe(provider("openai-compatible"), "gpt-4o").hasCost)
    // openrouter reports the cost of every call itself
    val openrouter = ModelsMetadata.describe(provider("openrouter"), "acme/totally-made-up")
    assert(openrouter.hasCost)
    assert(openrouter.metadata.select("pricing").isEmpty, "no price is known before the call")
    // a provider billed as another model has that model's cost, and nothing else of it
    val billedAs = ModelsMetadata.describe(provider("openai", Map("costs-tracking-model" -> "gpt-4o")), "gpt-totally-made-up")
    assert(billedAs.hasCost)
    assertEquals(billedAs.metadata.select("pricing").asOpt[JsObject], ModelsMetadata.describe(openai, "gpt-4o").metadata.select("pricing").asOpt[JsObject])
    assert(billedAs.metadata.select("mode").isEmpty, "the billed model's mode is not this model's")
    assert(!ModelsMetadata.describe(provider("openai", Map("costs-tracking-provider" -> "nowhere")), "gpt-4o").hasCost)
  }

  test("prices published in euros are billed in dollars") {
    given env: Env = otoroshi.env
    ext.modelsCatalog.load().awaitf(30.seconds)
    val rate = ext.costsTrackingSettings.exchangeRates("eur")
    // the models.dev prices of Scaleway are in euros
    val scaleway = ext.costsTracking.lookupModel("scaleway", "gpt-oss-120b").get
    val scalewayEuros = ext.modelsCatalog.lookup("scaleway", "gpt-oss-120b").flatMap(_.model.cost).get
    assertEquals(scaleway.input_cost_per_token, scalewayEuros.input / BigDecimal(1000000) * rate)
    assertEquals(scaleway.output_cost_per_token, scalewayEuros.output / BigDecimal(1000000) * rate)
    // the price table prices of OVHcloud too
    val ovh = ext.costsTracking.lookupModel("ovhcloud", "gpt-oss-120b").get
    assertEquals(ovh.raw.select(CostModel.currencyField).asOptString, Some("eur"))
    assertEquals(ovh.input_cost_per_token, ext.costsTracking.models("ovhcloud-gpt-oss-120b").input_cost_per_token * rate)
    // the models.dev prices of OVHcloud are already in dollars
    val ovhCatalog = ext.costsTracking.lookupModel("ovhcloud", "qwen3-32b").get
    assertEquals(ovhCatalog.raw.select(CostModel.currencyField).asOptString, None)
    assertEquals(ovhCatalog.input_cost_per_token, ext.modelsCatalog.lookup("ovhcloud", "qwen3-32b").flatMap(_.model.cost).get.input / BigDecimal(1000000))
    // what the listings show is what is billed, and they say so
    val listed = ModelsMetadata.describe(AiProvider(id = "scw", name = "scw", provider = "scaleway", connection = Json.obj(), options = Json.obj()), "gpt-oss-120b")
    assert(listed.hasCost)
    assertEquals(listed.metadata.select("pricing").select("prompt").asOpt[String], Some(ModelsMetadata.price(scaleway.input_cost_per_token).value))
    assertEquals(listed.metadata.select("sources").select("pricing").select("currency").asOpt[String], Some("eur"))
    assertEquals(listed.metadata.select("sources").select("pricing").select("exchange_rate").asOpt[BigDecimal], Some(rate))
    // rates and currencies come from the configuration, a currency with no rate is no price
    val custom = CostsTrackingSettings(Configuration("exchange-rates.eur" -> "1.5", "price-currencies.ovhcloud" -> "usd", "price-currencies.openai" -> "gbp"))
    assertEquals(custom.exchangeRates("eur"), BigDecimal("1.5"))
    val tracking = new CostsTracking(custom, otoroshi.env, ext.modelsCatalog)
    assertEquals(tracking.lookupModel("ovhcloud", "gpt-oss-120b").map(_.input_cost_per_token), Some(ext.costsTracking.models("ovhcloud-gpt-oss-120b").input_cost_per_token))
    assertEquals(tracking.lookupModel("scaleway", "gpt-oss-120b").map(_.input_cost_per_token), Some(scalewayEuros.input / BigDecimal(1000000) * BigDecimal("1.5")))
    assertEquals(tracking.lookupModel("openai", "gpt-4o"), None)
  }

  test("hugging face models are billed at their models.dev price, whatever inference provider serves them") {
    given env: Env = otoroshi.env
    ext.modelsCatalog.load().awaitf(30.seconds)
    val hf = ext.costsTracking.lookupModel("huggingface", "deepseek-ai/DeepSeek-V3").get
    assertEquals(hf.raw.select(ModelsCatalog.sourceField).asOptString, Some("models.dev"))
    assertEquals(ext.costsTracking.lookupModel("huggingface", "deepseek-ai/DeepSeek-V3:together").map(_.input_cost_per_token), Some(hf.input_cost_per_token))
    assertEquals(ext.costsTracking.lookupModel("huggingface", "deepseek-ai/DeepSeek-V3:fastest").map(_.input_cost_per_token), Some(hf.input_cost_per_token))
    val provider = AiProvider(id = "hf", name = "hf", provider = "huggingface", connection = Json.obj(), options = Json.obj())
    assert(ModelsMetadata.describe(provider, "deepseek-ai/DeepSeek-V3:cheapest").hasCost)
    assert(!ModelsMetadata.describe(provider, "acme/unknown-model").hasCost)
  }

  test("the api a model is served with is described in OpenAI terms") {
    given env: Env = otoroshi.env
    ext.modelsCatalog.load().awaitf(30.seconds)
    def describe(kind: String, model: String) = ModelsMetadata.describe(
      AiProvider(id = UUID.randomUUID().toString, name = kind, provider = kind, connection = Json.obj(), options = Json.obj()), model
    )
    def flag(kind: String, model: String): Option[Boolean] = describe(kind, model).metadata.select("openai_compatible").asOpt[Boolean]
    def endpoints(kind: String, model: String): Seq[String] = describe(kind, model).endpoints
    // the gateway talks OpenAI to these providers
    assertEquals(flag("openai", "gpt-4o"), Some(true))
    assertEquals(flag("ovh-ai-endpoints", "gpt-oss-120b"), Some(true))
    assertEquals(flag("ollama-openai", "my-local-model:latest"), Some(true))
    // and translates for these ones
    assertEquals(flag("anthropic", "claude-opus-5"), Some(false))
    assertEquals(flag("mistral", "mistral-large-latest"), Some(false))
    assertEquals(endpoints("anthropic", "claude-opus-5"), Seq.empty)
    assert(describe("anthropic", "claude-opus-5").metadata.select("endpoints").isEmpty)
    // a provider can serve some of its models with another api
    assertEquals(flag("azure-ai-foundry", "claude-sonnet-4-6"), Some(false))
    // a router serves nothing itself
    assertEquals(flag("loadbalancer", "gpt-4o"), None)
    // the endpoints the price table lists, batch aside
    assertEquals(endpoints("openai", "gpt-5.5"), Seq("chat_completions", "responses"))
    assertEquals(endpoints("openai", "gpt-5-pro"), Seq("responses"))
    assertEquals(endpoints("openai", "gpt-image-1"), Seq("images_generations", "images_edits"))
    assertEquals(endpoints("openai", "gpt-realtime"), Seq("realtime"))
    // else its mode
    assertEquals(endpoints("openai", "gpt-4o"), Seq("chat_completions"))
    // else the model types
    assertEquals(endpoints("ollama-openai", "my-local-model:latest"), Seq("chat_completions"))
    assertEquals(endpoints("openai-compatible", "text-embedding-3-small"), Seq("embeddings"))
    assertEquals(endpoints("openai-compatible", "whisper-1"), Seq("audio_transcriptions"))
    assertEquals(endpoints("openai-compatible", "tts-1"), Seq("audio_speech"))
    // the endpoints tell what a model is for
    assertEquals(describe("openai", "gpt-realtime").kinds, Seq("audio"))
    assertEquals(describe("openai", "gpt-image-1.5").kinds, Seq("image"))
    assertEquals(describe("openai", "gpt-audio").kinds, Seq("text", "audio"))
  }

  val listedModels = Seq(
    "gpt-4o", "gpt-4o-2099-01-01", "gpt-totally-made-up", "gpt-image-1", "text-embedding-3-small", "whisper-1",
    "tts-1", "omni-moderation-latest", "mistral-ocr-latest", "sora-2", "bge-reranker-v2-m3", "gpt-realtime-whisper",
  )
  // the openai client lists every model of the provider
  val openaiModels = listedModels

  val (openaiPort, _) = createTestServerWithRoutes("openai-models", routes => routes
    .get("/v1/models", (req, response) => response
      .status(200)
      .addHeader("Content-Type", "application/json")
      .sendString(Mono.just(Json.obj(
        "object" -> "list",
        "data" -> listedModels.map(id => Json.obj("id" -> id, "object" -> "model", "owned_by" -> "openai")),
      ).stringify))
    )
  )

  def route(host: String, plugin: NgPluginInstance): NgRoute = NgRoute(
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
    frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath(s"${host}/v1"))),
    backend = NgBackend.empty.copy(targets = Seq(NgTarget.default)),
    plugins = NgPlugins(Seq(plugin)),
  )

  lazy val setup: Unit = {
    val provider = AiProvider(
      id = UUID.randomUUID().toString,
      name = "openai models",
      provider = "openai",
      connection = Json.obj("base_url" -> s"http://localhost:${openaiPort}/v1", "token" -> "secret", "timeout" -> 30000),
      options = Json.obj("model" -> "gpt-4o"),
    )
    val solo = route("solo.oto.tools", NgPluginInstance(
      plugin = s"cp:${classOf[OpenAiCompatModels].getName}",
      config = NgPluginInstanceConfig(Json.obj("refs" -> Json.arr(provider.id))),
    ))
    val unified = route("unified.oto.tools", NgPluginInstance(
      plugin = s"cp:${classOf[OpenAiCompatApi].getName}",
      config = NgPluginInstanceConfig(Json.obj("language_model_refs" -> Json.arr(provider.id))),
    ))
    // no price table and no catalog provider for it: only the other providers' descriptions and the names help
    val compatible = provider.copy(id = UUID.randomUUID().toString, name = "compatible models", provider = "openai-compatible")
    val kinds = route("kinds.oto.tools", NgPluginInstance(
      plugin = s"cp:${classOf[OpenAiCompatApi].getName}",
      config = NgPluginInstanceConfig(Json.obj("language_model_refs" -> Json.arr(compatible.id))),
    ))
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").upsertEntity(provider).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").upsertEntity(compatible).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(kinds).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(solo).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(unified).awaitf(10.seconds)
    await(3.seconds)
  }

  def list(host: String, query: String): Map[String, JsObject] = {
    setup
    val resp = client.call("GET", s"http://${host}:${port}/v1/models${query}", Map.empty, None).awaitf(30.seconds)
    assertEquals(resp.status, 200, s"status should be 200, got ${resp.body}")
    (resp.body: String).parseJson.select("data").as[Seq[JsObject]].map(m => (m.select("id").asString, m)).toMap
  }

  Seq("solo.oto.tools", "unified.oto.tools").foreach { host =>

    test(s"${host}: models are listed as before unless enriched") {
      val models = list(host, "")
      assertEquals(models.keySet, openaiModels.toSet)
      assert(models.values.forall(_.select("_metadata").isEmpty), "no metadata should be added")
      assert(list(host, "?enriched=false").values.forall(_.select("_metadata").isEmpty), "no metadata should be added")
    }

    test(s"${host}: enriched models carry their capabilities, limits and prices") {
      val models = list(host, "?enriched=true")
      assertEquals(models.keySet, openaiModels.toSet)

      val gpt4o = models("gpt-4o").select("_metadata").as[JsObject]
      assertEquals(gpt4o.select("capabilities").select("tool_call").asOpt[Boolean], Some(true))
      assertEquals(gpt4o.select("capabilities").select("reasoning").asOpt[Boolean], Some(false))
      Seq("name", "description", "family", "release_date", "last_updated", "open_weights", "tool_call").foreach { field =>
        assert(gpt4o.select(field).isEmpty, s"${field} should not be exposed at the top level")
      }
      assert(gpt4o.select("modalities").select("input").as[Seq[String]].contains("image"))
      assert(gpt4o.select("limits").select("context").as[Long] > 0L)
      // the price costs tracking bills with, in the OpenRouter per token string format
      val table = ext.costsTracking.lookupModel("openai", "gpt-4o").get
      assertEquals(gpt4o.select("pricing").select("prompt").asOptString, Some(table.input_cost_per_token.bigDecimal.stripTrailingZeros().toPlainString))
      assertEquals(gpt4o.select("pricing").select("completion").asOptString, Some(table.output_cost_per_token.bigDecimal.stripTrailingZeros().toPlainString))
      assert(!gpt4o.select("pricing").select("prompt").asString.contains("E"), "prices must not use the scientific notation")
      assertEquals(gpt4o.select("sources").select("pricing").select("source").asOptString, Some("price-table"))
      // the price table has its own `source` field, a documentation link
      assert(models.values.flatMap(_.select("_metadata").select("sources").select("pricing").select("source").asOpt[String])
        .forall(s => s == "price-table" || s == "models.dev"))
      assertEquals(gpt4o.select("sources").select("catalog").select("provider").asOptString, Some("openai"))
      assertEquals(gpt4o.select("sources").select("catalog").select("match").asOptString, Some(CatalogMatch.Exact))

      // a model billed per second, per character or per second of video has no token price, not a free one
      def pricingOf(model: String): JsObject = models(model).select("_metadata").select("pricing").asOpt[JsObject].getOrElse(Json.obj())
      val whisper = ext.costsTracking.lookupModel("openai", "whisper-1").get
      assertEquals(pricingOf("whisper-1").keys, Set("input_second", "output_second"))
      assertEquals(pricingOf("whisper-1").select("input_second").asOptString, Some(ModelsMetadata.price(whisper.input_cost_per_second).value))
      assertEquals(pricingOf("tts-1").keys, Set("input_character"))
      assertEquals(pricingOf("sora-2").keys, Set("video_second"))
      // a price of zero the price table does give is kept
      assertEquals(pricingOf("omni-moderation-latest").select("prompt").asOptString, Some("0"))

      val snapshot = models("gpt-4o-2099-01-01").select("_metadata").as[JsObject]
      assertEquals(snapshot.select("sources").select("pricing").select("source").asOptString, Some("models.dev"))
      assertEquals(snapshot.select("sources").select("catalog").select("match").asOptString, Some(CatalogMatch.Normalized))
      assert(snapshot.select("pricing").select("prompt").isDefined)

      assertEquals(gpt4o.select("kinds").asOpt[Seq[String]], Some(Seq("text")))
      assertEquals(models("gpt-image-1").select("_metadata").select("kinds").asOpt[Seq[String]], Some(Seq("image")))
      assertEquals(gpt4o.select("has_cost").asOpt[Boolean], Some(true))
      // nobody knows it: its type is all there is to say
      assertEquals(models("gpt-totally-made-up").select("_metadata").asOpt[JsObject], Some(Json.obj(
        "kinds" -> Json.arr("text"),
        "has_cost" -> false,
        "openai_compatible" -> true,
        "endpoints" -> Json.arr("chat_completions"),
      )))

      // the bare flag works too
      assert(list(host, "?enriched").values.forall(_.select("_metadata").isDefined))
    }

    test(s"${host}: models can be filtered by type") {
      assertEquals(list(host, "?kind=image").keySet, Set("gpt-image-1"))
      assertEquals(list(host, "?kind=text").keySet, Set("gpt-4o", "gpt-4o-2099-01-01", "gpt-totally-made-up"))
      assert(list(host, "?kind=image").values.forall(_.select("_metadata").isEmpty), "the filter alone adds no metadata")
    }

    test(s"${host}: models can be filtered by endpoint") {
      assertEquals(list(host, "?endpoint=chat_completions").keySet, Set("gpt-4o", "gpt-4o-2099-01-01", "gpt-totally-made-up"))
      assertEquals(list(host, "?endpoint=images_edits").keySet, Set("gpt-image-1"))
      assertEquals(list(host, "?endpoints=realtime,IMAGES_GENERATIONS").keySet, Set("gpt-realtime-whisper", "gpt-image-1"))
      assertEquals(list(host, "?endpoint=chat_completions&has_cost=false").keySet, Set("gpt-totally-made-up"))
      assert(list(host, "?endpoint=responses").isEmpty)
    }

    test(s"${host}: models can be filtered on their cost") {
      // `has_cost` is "the gateway can bill a call on this model": nobody prices the first two, this provider
      // does not serve the mistral ocr entry, and the two last ones are billed per second of audio — a unit
      // the gateway never measures, so their calls carry no cost rather than a made up one
      val unpriced = Set("gpt-totally-made-up", "bge-reranker-v2-m3", "mistral-ocr-latest", "whisper-1", "gpt-realtime-whisper")
      assertEquals(list(host, "?has_cost=true").keySet, listedModels.toSet -- unpriced)
      assertEquals(list(host, "?has_cost").keySet, list(host, "?has_cost=true").keySet)
      assertEquals(list(host, "?has_cost=false").keySet, unpriced)
      assertEquals(list(host, "?has_cost=true&kind=text").keySet, Set("gpt-4o", "gpt-4o-2099-01-01"))
      assert(list(host, "?has_cost=false").values.forall(_.select("_metadata").isEmpty), "the filter alone adds no metadata")
    }
  }

  test("models of every type are recognized, even without provider data") {
    def ids(query: String): Set[String] = list("kinds.oto.tools", query).keySet
    assertEquals(ids(""), listedModels.toSet)
    assertEquals(ids("?kind=text"), Set("gpt-4o", "gpt-4o-2099-01-01", "gpt-totally-made-up"))
    assertEquals(ids("?kind=embedding"), Set("text-embedding-3-small"))
    assertEquals(ids("?kind=audio"), Set("whisper-1", "tts-1", "gpt-realtime-whisper"))
    assertEquals(ids("?kind=moderation"), Set("omni-moderation-latest"))
    assertEquals(ids("?kind=ocr"), Set("mistral-ocr-latest"))
    // any of the requested types, repeated or comma separated
    assertEquals(ids("?kind=image&kind=video"), Set("gpt-image-1", "sora-2"))
    assertEquals(ids("?kinds=IMAGE,video"), Set("gpt-image-1", "sora-2"))
    assertEquals(ids("?kind=nothing"), Set.empty[String])
    assertEquals(ids("?endpoint=embeddings"), Set("text-embedding-3-small"))
    assertEquals(ids("?endpoint=audio_transcriptions,audio_speech"), Set("whisper-1", "tts-1", "gpt-realtime-whisper"))
    // an openai compatible provider cannot be billed
    assertEquals(ids("?has_cost=true"), Set.empty[String])
    assertEquals(ids("?has_cost=false"), listedModels.toSet)
    val enriched = list("kinds.oto.tools", "?kind=audio&enriched=true")
    assert(enriched.values.forall(_.select("_metadata").select("kinds").asOpt[Seq[String]].contains(Seq("audio"))))
    // a ranker is listed, but it is none of the types
    assertEquals(list("kinds.oto.tools", "?enriched=true")("bge-reranker-v2-m3").select("_metadata").select("kinds").asOpt[Seq[String]], Some(Seq.empty))
  }
  // `has_cost` answers "the gateway can bill a call on this model", which is what decides whether its calls
  // move a dollar budget. Images and pages are billed by their own units, a voice priced per second of audio
  // is not billable at all, and the answer no longer depends on which entity lists the model.
  test("a model that is not text is billed when the table holds a unit the gateway can measure") {
    given env: Env = otoroshi.env
    val openai = AiProvider(id = "provider_costs", name = "openai", provider = "openai", connection = Json.obj(), options = Json.obj())
    val mistral = AiProvider(id = "provider_costs_mistral", name = "mistral", provider = "mistral", connection = Json.obj(), options = Json.obj())
    def billed(provider: AiProvider, model: String, modality: String): Boolean = ModelsMetadata.describe(provider, model, modality).hasCost
    assert(billed(openai, "gpt-image-2", "image"), "an image model counting its tokens is billed")
    assert(billed(openai, "dall-e-3", "image"), "an image model billed by the image is billed")
    assert(billed(openai, "tts-1", "audio"), "a voice billed per character is billed")
    assert(!billed(openai, "gpt-4o-mini-tts", "audio"), "a voice billed per second of audio cannot be measured")
    assert(billed(openai, "gpt-4o-mini-transcribe", "audio"), "a transcription counting its tokens is billed")
    assert(!billed(openai, "whisper-1", "audio"), "a transcription billed per second of audio cannot be measured")
    assert(billed(mistral, "mistral-ocr-latest", "ocr"), "an ocr model billed by the page is billed")
    // the same models, listed by the LLM connection that exposes them, answer the same thing
    assert(billed(openai, "gpt-image-2", "text"), "an image model listed by a text provider is billed the same way")
    assert(!billed(openai, "whisper-1", "text"), "and so is one that cannot be billed")
  }
}
