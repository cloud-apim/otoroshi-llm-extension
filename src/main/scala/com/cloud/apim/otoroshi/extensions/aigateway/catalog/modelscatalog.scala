package com.cloud.apim.otoroshi.extensions.aigateway.catalog

import com.cloud.apim.otoroshi.extensions.aigateway.decorators.{CostModel, CostsOutput}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiProvider, AiProvidersCatalog}
import com.github.blemale.scaffeine.Scaffeine
import otoroshi.env.Env
import otoroshi.next.plugins.api.NgbBackendCallContext
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.*

import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, Promise, blocking}
import scala.util.{Failure, Success, Try}

// prices of the models.dev catalog are dollars per million tokens
final case class CatalogCost(
  input: BigDecimal,
  output: BigDecimal,
  cacheRead: Option[BigDecimal],
  cacheWrite: Option[BigDecimal],
  reasoning: Option[BigDecimal],
  inputAudio: Option[BigDecimal],
  outputAudio: Option[BigDecimal],
)

/**
 * A model as described by the bundled models.dev catalog (`data/catalog.json`), reduced to what the gateway
 * exposes. The catalog holds thousands of them, so they are kept as compact values rather than as json.
 * `provider` is the models.dev provider serving it, None for the provider agnostic `models` section.
 */
final case class CatalogModel(
  provider: Option[String],
  id: String,
  // only used to tell what the model is for (`text-embedding`, `whisper`)
  family: Option[String],
  // how the provider serves this model when it differs from its other models: the AI SDK package, the api shape
  sdk: Option[String],
  shape: Option[String],
  attachment: Option[Boolean],
  reasoning: Option[Boolean],
  reasoningOptions: Option[JsArray],
  toolCall: Option[Boolean],
  structuredOutput: Option[Boolean],
  temperature: Option[Boolean],
  knowledge: Option[String],
  // only used to pick the most recent snapshot of a model
  lastUpdated: Option[String],
  status: Option[String],
  inputModalities: Seq[String],
  outputModalities: Seq[String],
  limitContext: Option[Long],
  limitInput: Option[Long],
  limitOutput: Option[Long],
  cost: Option[CatalogCost],
) {

  // a price table entry, so that costs tracking can bill with it when its own table does not know the model
  lazy val costModel: Option[CostModel] = cost.map { c =>
    def perToken(value: BigDecimal): JsValue = JsNumber(value / ModelsCatalog.million)
    CostModel(id, ModelsCatalog.obj(
      "litellm_provider" -> provider.map(JsString.apply),
      ModelsCatalog.sourceField -> JsString(ModelsCatalog.source).some,
      "max_input_tokens" -> limitInput.orElse(limitContext).map(v => JsNumber(BigDecimal(v))),
      "max_output_tokens" -> limitOutput.map(v => JsNumber(BigDecimal(v))),
      "max_tokens" -> limitOutput.map(v => JsNumber(BigDecimal(v))),
      "input_cost_per_token" -> perToken(c.input).some,
      "output_cost_per_token" -> perToken(c.output).some,
      "cache_read_input_token_cost" -> c.cacheRead.map(perToken),
      "cache_creation_input_token_cost" -> c.cacheWrite.map(perToken),
      "output_cost_per_reasoning_token" -> c.reasoning.map(perToken),
      "input_cost_per_audio_token" -> c.inputAudio.map(perToken),
      "output_cost_per_audio_token" -> c.outputAudio.map(perToken),
      "supports_reasoning" -> reasoning.map(JsBoolean.apply),
      "supports_function_calling" -> toolCall.map(JsBoolean.apply),
      "supports_response_schema" -> structuredOutput.map(JsBoolean.apply),
      "supports_vision" -> JsBoolean(inputModalities.contains("image")).some,
      "supports_pdf_input" -> JsBoolean(inputModalities.contains("pdf")).some,
      "supports_audio_input" -> JsBoolean(inputModalities.contains("audio")).some,
      "supports_audio_output" -> JsBoolean(outputModalities.contains("audio")).some,
      "supports_prompt_caching" -> JsBoolean(c.cacheRead.isDefined).some,
    ))
  }
}

object CatalogMatch {
  // the id is the one the provider serves
  val Exact = "exact"
  // same id once cosmetic differences are removed: case, `models/` like prefixes, snapshot dates, routing variants
  val Normalized = "normalized"
  // same model family and version for this provider, a short version suffix (`-2411`, `-0613`, `-001`) aside
  val Approximate = "approximate"
  // the same model served by another provider: its capabilities hold, its price does not
  val Global = "global"
  val GlobalApproximate = "global-approximate"
}

// a provider of the models.dev catalog
final case class CatalogProvider(id: String, name: Option[String], doc: Option[String])

final case class CatalogMatch(model: CatalogModel, kind: String) {
  // a price is only trusted when it comes from the provider serving this very model
  def priced: Boolean = kind == CatalogMatch.Exact || kind == CatalogMatch.Normalized
  // what the catalog says of the provider's api only holds for that provider
  def onServingProvider: Boolean = priced || kind == CatalogMatch.Approximate
}

/**
 * The shapes a model id takes from one provider to the other: `models/gemini-2.5-flash`, `@cf/openai/gpt-oss-120b`,
 * `openai/gpt-4o:extended`, `claude-sonnet-4-5-20250929`, `qwen3:8b`, `Meta-Llama-3_3-70B-Instruct`...
 */
object ModelIds {

  private val prefixes = Seq("~", "models/", "hf:", "@cf/", "@hf/")
  // OpenRouter routing variants: the same model, served differently
  private val variants = Set("free", "extended", "nitro", "beta", "floor", "online", "exacto", "latest", "thinking")
  private val vertexDate = "@\\d{8}$".r
  private val snapshotDate = "-(\\d{4}-\\d{2}-\\d{2}|\\d{8})$".r
  private val latest = "-latest$".r
  private val separators = "[._\\s]".r
  private val shortVersion = "-(\\d{2}-\\d{4}|\\d{2}-\\d{2}|\\d{3,4})$".r

  def base(id: String): String = {
    prefixes.foldLeft(id.trim.toLowerCase(Locale.ROOT)) { (current, prefix) =>
      if (current.startsWith(prefix)) current.substring(prefix.length) else current
    }
  }

  private def normalizeName(name: String): String = {
    val untagged = name.indexOf(':') match {
      case -1 => name
      case idx =>
        val tag = name.substring(idx + 1)
        // an ollama tag is the model size (`qwen3:8b`), a routing variant is not part of the model
        if (variants.contains(tag)) name.substring(0, idx) else s"${name.substring(0, idx)}-${tag}"
    }
    val undated = latest.replaceFirstIn(snapshotDate.replaceFirstIn(vertexDate.replaceFirstIn(untagged, ""), ""), "")
    separators.replaceAllIn(undated, "-")
  }

  // the vendor path is kept: it is what tells two models of the same name apart within one provider
  def normalized(id: String): String = {
    val b = base(id)
    b.lastIndexOf('/') match {
      case -1 => normalizeName(b)
      case idx => separators.replaceAllIn(b.substring(0, idx + 1), "-") + normalizeName(b.substring(idx + 1))
    }
  }

  // the model name alone, whoever the vendor is
  def name(id: String): String = {
    val b = base(id)
    normalizeName(b.substring(b.lastIndexOf('/') + 1))
  }

  def approximate(id: String): String = shortVersion.replaceFirstIn(name(id), "")
}

final class ModelsCatalogIndex(
  providers: Map[String, CatalogProvider],
  exact: Map[String, Map[String, CatalogModel]],
  normalized: Map[String, Map[String, CatalogModel]],
  approximate: Map[String, Map[String, CatalogModel]],
  global: Map[String, CatalogModel],
  globalApproximate: Map[String, CatalogModel],
) {

  val size: Int = exact.valuesIterator.map(_.size).sum

  def hasProvider(id: String): Boolean = exact.contains(id)

  def provider(id: String): Option[CatalogProvider] = providers.get(id)

  def models(provider: String): Seq[CatalogModel] = exact.get(provider).map(_.values.toSeq).getOrElse(Seq.empty)

  // models.dev providers matching an entity provider kind ("x-ai") or a price table provider name ("xai")
  def providersFor(name: String): Seq[String] = {
    val lower = name.toLowerCase(Locale.ROOT)
    ModelsCatalog.aliases.get(lower).map(_.filter(hasProvider)).getOrElse {
      Seq(lower, lower.replace('_', '-')).distinct.filter(hasProvider).take(1)
    }
  }

  def find(providers: Seq[String], model: String): Option[CatalogMatch] = {
    def first(index: Map[String, Map[String, CatalogModel]], key: String): Option[CatalogModel] =
      providers.iterator.flatMap(p => index.get(p).flatMap(_.get(key))).nextOption()
    val name = ModelIds.name(model)
    val approx = ModelIds.approximate(model)
    first(exact, ModelIds.base(model)).map(CatalogMatch(_, CatalogMatch.Exact))
      .orElse(first(normalized, ModelIds.normalized(model)).map(CatalogMatch(_, CatalogMatch.Normalized)))
      .orElse(first(approximate, approx).map(CatalogMatch(_, CatalogMatch.Approximate)))
      .orElse(global.get(name).map(CatalogMatch(_, CatalogMatch.Global)))
      .orElse(globalApproximate.get(approx).map(CatalogMatch(_, CatalogMatch.GlobalApproximate)))
  }
}

object ModelsCatalogIndex {

  // several instances of a model collapse on the same normalized key (snapshots of the same model): the most
  // recent one wins, then the shortest id, which is the alias the others are snapshots of
  private def preferred(current: CatalogModel, candidate: CatalogModel): Boolean = {
    val (c, n) = (current.lastUpdated.getOrElse(""), candidate.lastUpdated.getOrElse(""))
    n > c || (n == c && candidate.id.length < current.id.length)
  }

  private def keyed(models: Iterable[CatalogModel], key: String => String): Map[String, CatalogModel] = {
    val result = mutable.HashMap.empty[String, CatalogModel]
    models.foreach { model =>
      val k = key(model.id)
      result.get(k) match {
        case Some(current) if !preferred(current, model) => ()
        case _ => result.put(k, model)
      }
    }
    result.toMap
  }

  // shares the many identical small values (families, dates, modalities, reasoning options) between models
  private final class Interner {
    private val strings = mutable.HashMap.empty[String, String]
    private val lists = mutable.HashMap.empty[Seq[String], Seq[String]]
    private val arrays = mutable.HashMap.empty[JsArray, JsArray]
    def string(value: String): String = strings.getOrElseUpdate(value, value)
    def list(value: Seq[String]): Seq[String] = lists.getOrElseUpdate(value, value.map(string).toList)
    def array(value: JsArray): JsArray = arrays.getOrElseUpdate(value, value)
  }

  private def parseModel(provider: Option[String], json: JsValue, interner: Interner): Option[CatalogModel] = {
    json.select("id").asOptString.map { id =>
      def str(name: String): Option[String] = json.select(name).asOptString.filter(_.nonEmpty)
      def bool(name: String): Option[Boolean] = json.select(name).asOptBoolean
      def limit(name: String): Option[Long] = json.select("limit").select(name).asOpt[Long].filter(_ > 0)
      def modalities(name: String): Seq[String] = interner.list(json.select("modalities").select(name).asOpt[Seq[String]].getOrElse(Seq.empty))
      val costs = json.select("cost").asOpt[JsObject]
      def price(name: String): Option[BigDecimal] = costs.flatMap(_.select(name).asOpt[BigDecimal])
      CatalogModel(
        provider = provider,
        id = id,
        family = str("family").map(interner.string),
        sdk = json.select("provider").select("npm").asOptString.map(interner.string),
        shape = json.select("provider").select("shape").asOptString.map(interner.string),
        attachment = bool("attachment"),
        reasoning = bool("reasoning"),
        reasoningOptions = json.select("reasoning_options").asOpt[JsArray].filter(_.value.nonEmpty).map(interner.array),
        toolCall = bool("tool_call"),
        structuredOutput = bool("structured_output"),
        temperature = bool("temperature"),
        knowledge = str("knowledge").map(interner.string),
        lastUpdated = str("last_updated").map(interner.string),
        status = str("status").map(interner.string),
        inputModalities = modalities("input"),
        outputModalities = modalities("output"),
        limitContext = limit("context"),
        limitInput = limit("input"),
        limitOutput = limit("output"),
        cost = for {
          input <- price("input")
          output <- price("output")
        } yield CatalogCost(input, output, price("cache_read"), price("cache_write"), price("reasoning"), price("input_audio"), price("output_audio")),
      )
    }
  }

  def parse(json: JsValue): ModelsCatalogIndex = {
    val interner = new Interner()
    val providers: Seq[(String, Seq[CatalogModel])] = json.select("providers").asOpt[JsObject].map(_.value.toSeq).getOrElse(Seq.empty).map {
      case (providerId, provider) =>
        val models = provider.select("models").asOpt[JsObject].map(_.value.values.toSeq).getOrElse(Seq.empty)
        (providerId, models.flatMap(m => parseModel(providerId.some, m, interner)))
    }
    val canonical = json.select("models").asOpt[JsObject].map(_.value.values.toSeq).getOrElse(Seq.empty).flatMap(m => parseModel(None, m, interner))
    val byProvider = providers.toMap
    // the provider agnostic descriptions first, then the vendors describing their own models, then the rest
    val ordered = Seq(canonical) ++
      ModelsCatalog.referenceProviders.flatMap(byProvider.get) ++
      providers.filterNot(p => ModelsCatalog.referenceProviders.contains(p._1)).map(_._2)
    val global = mutable.LinkedHashMap.empty[String, CatalogModel]
    val globalApproximate = mutable.LinkedHashMap.empty[String, CatalogModel]
    ordered.foreach { models =>
      keyed(models, ModelIds.name).foreach { case (k, m) => if (!global.contains(k)) global.put(k, m) }
      keyed(models, ModelIds.approximate).foreach { case (k, m) => if (!globalApproximate.contains(k)) globalApproximate.put(k, m) }
    }
    val infos = json.select("providers").asOpt[JsObject].map(_.value.toSeq).getOrElse(Seq.empty).map {
      case (providerId, provider) => (providerId, CatalogProvider(
        id = providerId,
        name = provider.select("name").asOptString.filter(_.nonEmpty),
        doc = provider.select("doc").asOptString.filter(_.startsWith("http")),
      ))
    }.toMap
    new ModelsCatalogIndex(
      providers = infos,
      exact = providers.map { case (id, models) => (id, models.map(m => (ModelIds.base(m.id), m)).toMap) }.toMap,
      normalized = providers.map { case (id, models) => (id, keyed(models, ModelIds.normalized)) }.toMap,
      approximate = providers.map { case (id, models) => (id, keyed(models, ModelIds.approximate)) }.toMap,
      global = global.toMap,
      globalApproximate = globalApproximate.toMap,
    )
  }
}

object ModelsCatalog {

  val resource = "data/catalog.json"
  val source = "models.dev"
  // the AI SDK packages speaking the OpenAI format
  val openAiSdks = Set("@ai-sdk/openai", "@ai-sdk/openai-compatible", "@ai-sdk/azure")
  // marks the price entries built from the catalog. Not `source`: the price table uses it for documentation links
  val sourceField = "cloud_apim_price_source"
  val million = BigDecimal(1000000)

  // models.dev providers whose description of a model wins when another provider serves it too
  val referenceProviders: Seq[String] = Seq(
    "openai", "anthropic", "google", "mistral", "xai", "deepseek", "cohere", "meta", "llama", "zai", "minimax",
    "xiaomi", "moonshotai", "alibaba", "nvidia", "perplexity", "groq",
  )

  // entity provider kinds and price table provider names whose models.dev provider has another id. Any other
  // name is looked up as is, then with dashes instead of underscores (`fireworks_ai`).
  val aliases: Map[String, Seq[String]] = Map(
    "x-ai" -> Seq("xai"),
    "gemini" -> Seq("google"),
    "azure-openai" -> Seq("azure", "azure-cognitive-services"),
    "azure" -> Seq("azure", "azure-cognitive-services"),
    "azure-ai-foundry" -> Seq("azure-cognitive-services", "azure"),
    "azure_ai" -> Seq("azure-cognitive-services", "azure"),
    "ovh-ai-endpoints" -> Seq("ovhcloud"),
    "ovh-ai-endpoints-unified" -> Seq("ovhcloud"),
    "cloudflare" -> Seq("cloudflare-workers-ai"),
    "together-ai" -> Seq("togetherai"),
    "together_ai" -> Seq("togetherai"),
    "novita" -> Seq("novita-ai"),
    "nvidia-nim" -> Seq("nvidia"),
    "nvidia_nim" -> Seq("nvidia"),
    "friendliai" -> Seq("friendli"),
    "gmi" -> Seq("gmicloud"),
    "meta-llama" -> Seq("llama"),
    "meta_llama" -> Seq("llama"),
    "veniceai" -> Seq("venice"),
    "xiaomi-mimo" -> Seq("xiaomi"),
    "abliteration" -> Seq("abliteration-ai"),
    "moonshot" -> Seq("moonshotai"),
    "dashscope" -> Seq("alibaba"),
    "bedrock" -> Seq("amazon-bedrock"),
    "bedrock_converse" -> Seq("amazon-bedrock"),
    "vertex_ai" -> Seq("google-vertex"),
    "vertex_ai-language-models" -> Seq("google-vertex"),
    "vertex_ai-anthropic_models" -> Seq("google-vertex-anthropic"),
    "github_copilot" -> Seq("github-copilot"),
  )

  private[catalog] def obj(fields: (String, Option[JsValue])*): JsObject = JsObject(fields.collect { case (k, Some(v)) => (k, v) })
}

/**
 * The bundled models.dev catalog: capabilities, limits and prices of the models served by hundreds of providers.
 * Loaded once, in the background: at startup when it backs costs tracking, on the first enriched listing otherwise.
 */
class ModelsCatalog(env: Env) {

  private val logger = AiExtension.logger
  private val indexRef = new AtomicReference[Option[ModelsCatalogIndex]](None)
  private val loadingRef = new AtomicReference[Future[Option[ModelsCatalogIndex]]]()
  // lookups are made on every call billed from the catalog, with model names coming from the requests
  private val matches = Scaffeine().maximumSize(20000).build[String, Option[CatalogMatch]]()
  private val insights = Scaffeine().maximumSize(1000).expireAfterWrite(scala.concurrent.duration.Duration(10, "minutes")).build[String, JsObject]()

  def index: Option[ModelsCatalogIndex] = indexRef.get()

  def load(): Future[Option[ModelsCatalogIndex]] = {
    Option(loadingRef.get()).getOrElse {
      val promise = Promise[Option[ModelsCatalogIndex]]()
      if (loadingRef.compareAndSet(null, promise.future)) {
        given ec: ExecutionContext = env.otoroshiExecutionContext
        promise.completeWith(Future(blocking(read())))
        promise.future
      } else {
        loadingRef.get()
      }
    }
  }

  private def read(): Option[ModelsCatalogIndex] = {
    val start = System.currentTimeMillis()
    env.environment.resourceAsStream(ModelsCatalog.resource) match {
      case None =>
        logger.warn(s"resource ${ModelsCatalog.resource} not found, models metadata will only come from the price table")
        None
      case Some(stream) =>
        Try(try ModelsCatalogIndex.parse(Json.parse(stream)) finally stream.close()) match {
          case Failure(e) =>
            logger.error("unable to load the models catalog", e)
            None
          case Success(idx) =>
            indexRef.set(idx.some)
            if (logger.isDebugEnabled) logger.debug(s"models catalog loaded: ${idx.size} models in ${System.currentTimeMillis() - start} ms")
            idx.some
        }
    }
  }

  // `provider` is an entity provider kind or a price table provider name. None until the catalog is loaded.
  def lookup(provider: String, model: String): Option[CatalogMatch] = index.flatMap { idx =>
    matches.get(s"${provider} ${model}", _ => idx.find(idx.providersFor(provider), model))
  }

  def lookupCost(provider: String, model: String): Option[CostModel] = {
    lookup(provider, model).filter(_.priced).flatMap(_.model.costModel)
  }

  // the insights of a provider kind only change with the price table, they are kept a few minutes
  def insightsOf(kind: String)(compute: => JsObject): JsObject = {
    if (index.isEmpty) compute else insights.get(kind, _ => compute)
  }
}

/**
 * What the gateway knows of a provider kind before it is even connected: whether it talks to it in the OpenAI
 * format, and how many of the models the catalog knows for it are of each type, reason, call tools, see images
 * or have a price costs tracking bills with.
 */
object ProviderInsights {

  import ModelsCatalog.obj

  def of(kind: String)(using env: Env): JsObject = env.adminExtensions.extension[AiExtension].map { ext =>
    val lower = kind.toLowerCase(Locale.ROOT)
    ext.modelsCatalog.insightsOf(lower) {
      val provider = AiProvider(id = s"insights-$lower", name = lower, provider = lower, connection = Json.obj(), options = Json.obj())
      val catalog = ext.modelsCatalog.index.flatMap { idx =>
        val ids = idx.providersFor(lower)
        val models = ids.flatMap(idx.models).distinctBy(m => ModelIds.base(m.id))
        Option.when(models.nonEmpty) {
          val kindsOf = models.map(m => m -> ModelKinds.of(lower, Seq(m.id) ++ m.family, None, m.inputModalities, m.outputModalities))
          val kinds = kindsOf.flatMap(_._2).groupBy(identity)
          // the token prices of the text models: speech, image or embedding models are priced by other units
          val prices = kindsOf.collect { case (m, k) if k.contains(AiProvidersCatalog.Text) => m }.flatMap { m =>
            ext.costsTracking.billedAs(provider, m.id).flatMap { case (p, billed) => ext.costsTracking.lookupModel(p, billed) }
          }
          val prompts = prices.map(_.input_cost_per_token).filter(_ > 0)
          val info = ids.headOption.flatMap(idx.provider)
          obj(
            "provider" -> ids.headOption.map(JsString.apply),
            "name" -> info.flatMap(_.name).map(JsString.apply),
            "doc" -> info.flatMap(_.doc).map(JsString.apply),
            "models" -> JsNumber(models.size).some,
            "kinds" -> JsObject(AiProvidersCatalog.allCapabilities.flatMap(k => kinds.get(k).map(list => k -> JsNumber(list.size)))).some,
            "reasoning" -> JsNumber(models.count(_.reasoning.contains(true))).some,
            "tool_call" -> JsNumber(models.count(_.toolCall.contains(true))).some,
            "vision" -> JsNumber(models.count(_.inputModalities.contains("image"))).some,
            "priced" -> JsNumber(models.count(m => ext.costsTracking.hasCost(provider, m.id))).some,
            "prompt_from" -> prompts.minOption.map(ModelsMetadata.price),
            "prompt_to" -> prompts.maxOption.map(ModelsMetadata.price),
            "max_context" -> models.flatMap(_.limitContext).maxOption.map(v => JsNumber(BigDecimal(v))),
          )
        }
      }
      obj(
        "openai_compatible" -> Option.when(!AiProvider.routingProviders.contains(lower))(JsBoolean(AiProvider.openAiCompatibleProviders.contains(lower))),
        "catalog" -> catalog,
      )
    }
  }.getOrElse(Json.obj())
}

/**
 * The Otoroshi model types (text, audio, image, ocr, embedding, moderation, video) a listed model belongs to. A
 * model can have several: an omni model chats and speaks, an image model may answer with text too.
 */
object ModelKinds {

  import AiProvidersCatalog.{Audio, Embedding, Image, Moderation, Ocr, Text, Video}

  // providers whose chat client only extracts text from documents
  private val ocrProviders = Set("alphaedge")

  private val embeddingName = "embed".r
  private val moderationName = "moderation|guard".r
  private val ocrName = "ocr".r
  private val rankingName = "rerank".r
  private val audioName = "whisper|transcri|(^|[^a-z])(asr|stt|tts)([^a-z]|$)".r
  private val imageName = "dall-e|gpt-image|imagen|flux|stable-diffusion|(^|[^a-z])image([^a-z]|$)".r
  private val videoName = "sora|(^|[^a-z])(veo|video)([^a-z]|$)".r

  private def purposeOfMode(mode: String): Option[Seq[String]] = mode match {
    case "embedding" => Seq(Embedding).some
    case "moderation" | "guardrail" => Seq(Moderation).some
    case "ocr" => Seq(Ocr).some
    case "audio_transcription" | "audio_speech" => Seq(Audio).some
    // rankers and search engines are none of the model types
    case "rerank" | "search" | "vector_store" => Seq.empty.some
    case _ => None
  }

  // the price table labels some of these `chat`, their name never lies
  private def purposeOfName(name: String): Option[Seq[String]] = {
    if (embeddingName.findFirstIn(name).isDefined) Seq(Embedding).some
    else if (moderationName.findFirstIn(name).isDefined) Seq(Moderation).some
    else if (ocrName.findFirstIn(name).isDefined) Seq(Ocr).some
    else if (rankingName.findFirstIn(name).isDefined) Seq.empty.some
    else if (audioName.findFirstIn(name).isDefined) Seq(Audio).some
    else None
  }

  /**
   * `names` are the ids and families known for the model, `mode` is the one of the price table, `endpoints` the api
   * endpoints the provider serves it on, when known. The purpose of a specialized model (embedding, moderation,
   * ocr, speech) wins over its modalities: an embedding model "outputs" text.
   */
  def of(providerKind: String, names: Seq[String], mode: Option[String], input: Seq[String], output: Seq[String], endpoints: Seq[String] = Seq.empty): Seq[String] = {
    val name = names.mkString(" ").toLowerCase(Locale.ROOT)
    val purpose = if (ocrProviders.contains(providerKind.toLowerCase(Locale.ROOT))) Seq(Ocr).some
      else mode.flatMap(purposeOfMode).orElse(purposeOfName(name))
    purpose.getOrElse {
      // speech recognition takes audio and gives text back, it does not chat
      val listening = input.contains("audio") && !input.contains("text")
      val fromModalities = Seq(
        Text -> (output.contains("text") && !listening),
        Audio -> (output.contains("audio") || listening),
        Image -> output.contains("image"),
        Video -> output.contains("video"),
      ).collect { case (kind, true) => kind }
      val fromMode = mode.collect {
        case "chat" | "completion" | "responses" if !listening => Text
        case "realtime" => Audio
        case "image_generation" | "image_edit" => Image
        case "video_generation" => Video
      }
      val all = (fromModalities ++ fromMode).toSet
      // a model only served on image or realtime endpoints does not chat, whatever text it gives back
      val chats = endpoints.isEmpty || endpoints.exists(ModelEndpoints.conversational.contains)
      val found = if (chats || all == Set(Text)) all else all - Text
      if (found.nonEmpty) AiProvidersCatalog.allCapabilities.filter(found.contains)
      else if (imageName.findFirstIn(name).isDefined) Seq(Image)
      else if (videoName.findFirstIn(name).isDefined) Seq(Video)
      // nothing tells otherwise, and the listings expose the models of text providers
      else Seq(Text)
    }
  }
}

/**
 * The api endpoints a provider serves a model on, named after the OpenAI api paths (`chat_completions`,
 * `images_generations`...). `messages` is the one non OpenAI endpoint known, it tells a model is only served
 * with the Anthropic api.
 */
object ModelEndpoints {

  import AiProvidersCatalog.{Audio, Embedding, Image, Moderation, Ocr, Text, Video}

  val Messages = "messages"
  val conversational = Set("chat_completions", "completions", "responses", Messages)

  private val paths = Map(
    "/v1/chat/completions" -> "chat_completions",
    "/v1/completions" -> "completions",
    "/v1/responses" -> "responses",
    "/v1/embeddings" -> "embeddings",
    "/v1/images/generations" -> "images_generations",
    "/v1/images/edits" -> "images_edits",
    "/v1/images/variations" -> "images_variations",
    "/v1/audio/speech" -> "audio_speech",
    "/v1/audio/transcriptions" -> "audio_transcriptions",
    "/v1/audio/translations" -> "audio_translations",
    "/v1/moderations" -> "moderations",
    "/v1/ocr" -> "ocr",
    "/v1/realtime" -> "realtime",
    "/v1/realtime/transcription_sessions" -> "realtime",
    "/v1/videos" -> "videos",
    "/v1/messages" -> Messages,
  )

  private val modes = Map(
    "chat" -> "chat_completions",
    "completion" -> "completions",
    "responses" -> "responses",
    "embedding" -> "embeddings",
    "image_generation" -> "images_generations",
    "image_edit" -> "images_edits",
    "audio_transcription" -> "audio_transcriptions",
    "audio_speech" -> "audio_speech",
    "moderation" -> "moderations",
    "ocr" -> "ocr",
    "realtime" -> "realtime",
    "video_generation" -> "videos",
  )

  private val speechName = "(^|[^a-z])tts([^a-z]|$)".r

  // what the data says: the price table endpoints (batch and vendor specific ones left out), else its mode, else
  // the api shape of the catalog
  def known(supported: Seq[String], mode: Option[String], shape: Option[String]): Seq[String] = {
    val fromPaths = supported.flatMap(paths.get).distinct
    if (fromPaths.nonEmpty) fromPaths
    else mode.flatMap(modes.get).orElse(shape.collect {
      case "responses" => "responses"
      case "completions" => "chat_completions"
    }).toSeq
  }

  // what a model of these types is called with, when nothing else is known
  def ofKinds(kinds: Seq[String], names: Seq[String], input: Seq[String]): Seq[String] = kinds.flatMap {
    case Text => Seq("chat_completions")
    case Embedding => Seq("embeddings")
    case Image => Seq("images_generations")
    case Audio if input.contains("audio") && !input.contains("text") => Seq("audio_transcriptions")
    case Audio if kinds.contains(Text) => Seq.empty
    case Audio if input.nonEmpty || speechName.findFirstIn(names.mkString(" ").toLowerCase(Locale.ROOT)).isDefined => Seq("audio_speech")
    case Audio => Seq("audio_transcriptions")
    case Moderation => Seq("moderations")
    case Ocr => Seq("ocr")
    case Video => Seq("videos")
    case _ => Seq.empty
  }.distinct
}

final case class ModelDescription(kinds: Seq[String], hasCost: Boolean, endpoints: Seq[String], metadata: JsObject)

/**
 * The `_metadata` object of a model in the `/models` listings. It merges the models.dev catalog (capabilities,
 * modalities, limits) with the price table costs tracking bills with, so the prices shown are the ones budgets
 * are charged with.
 */
object ModelsMetadata {

  import ModelsCatalog.obj

  def price(value: BigDecimal): JsString = JsString(value.bigDecimal.stripTrailingZeros().toPlainString)

  private def nonZero(value: BigDecimal): Option[JsString] = Option.when(value > 0)(price(value))

  // OpenRouter's pricing shape: dollars per token (per image for images), as strings
  def pricing(cost: CostModel): JsObject = obj(
    "prompt" -> price(cost.input_cost_per_token).some,
    "completion" -> price(cost.output_cost_per_token).some,
    "input_cache_read" -> nonZero(cost.cache_read_input_token_cost).orElse(nonZero(cost.input_cost_per_token_cache_hit)),
    "input_cache_write" -> nonZero(cost.cache_creation_input_token_cost),
    "internal_reasoning" -> nonZero(cost.output_cost_per_reasoning_token),
    "audio" -> nonZero(cost.input_cost_per_audio_token),
    "audio_output" -> nonZero(cost.output_cost_per_audio_token),
    "image" -> nonZero(cost.input_cost_per_image),
    "image_output" -> nonZero(cost.output_cost_per_image),
  )

  /**
   * `modality` is the entity type serving the model: an embedding, image, audio, moderation, ocr or video model
   * entity is of that type whatever the catalog says, and is billed on its own terms.
   */
  def describe(provider: AiProvider, model: String, modality: String = AiProvidersCatalog.Text)(using env: Env): ModelDescription = {
    val ext = env.adminExtensions.extension[AiExtension]
    val found = ext.flatMap(_.modelsCatalog.lookup(provider.provider, model))
    val catalog = found.map(_.model)
    // the very lookup the costs decorator bills with
    val billed = ext.flatMap(_.costsTracking.billedAs(provider, model))
    val priceTable = for {
      e <- ext
      (pricingProvider, billedModel) <- billed
      cost <- e.costsTracking.lookupModel(pricingProvider, billedModel)
    } yield cost
    // only what is billed, in dollars: the catalog prices of a provider costs tracking does not bill are not shown
    val prices = priceTable
    // a provider can be billed as another model (`costs-tracking-model`): its price entry says nothing of this one
    val priced = prices.filter(_ => billed.forall(_._2 == model))
    val raw = priced.map(_.raw).getOrElse(JsObject.empty)
    def flag(name: String): Option[Boolean] = raw.select(name).asOptBoolean
    val mode = raw.select("mode").asOptString
    val conversational = priced.isDefined && mode.forall(m => m == "chat" || m == "responses" || m == "completion")
    def modalities(field: String, flags: (String, String)*): Option[Seq[String]] = {
      raw.select(field).asOpt[Seq[String]].filter(_.nonEmpty).orElse(Option.when(conversational) {
        "text" +: flags.collect { case (name, modality) if flag(name).contains(true) => modality }
      })
    }
    val input = catalog.map(_.inputModalities).filter(_.nonEmpty)
      .orElse(modalities("supported_modalities", "supports_vision" -> "image", "supports_pdf_input" -> "pdf", "supports_audio_input" -> "audio", "supports_video_input" -> "video"))
    val output = catalog.map(_.outputModalities).filter(_.nonEmpty)
      .orElse(modalities("supported_output_modalities", "supports_audio_output" -> "audio"))
    // how the provider serves this model, when the catalog knows the model for this very provider
    val served = found.filter(_.onServingProvider).map(_.model)
    val names = Seq(model) ++ catalog.map(_.id) ++ catalog.flatMap(_.family) ++ priced.map(_.name)
    val knownEndpoints = ModelEndpoints.known(raw.select("supported_endpoints").asOpt[Seq[String]].getOrElse(Seq.empty), mode, served.flatMap(_.shape))
    val kinds = if (modality != AiProvidersCatalog.Text) Seq(modality) else ModelKinds.of(
      providerKind = provider.provider,
      names = names,
      mode = mode,
      input = input.getOrElse(Seq.empty),
      output = output.getOrElse(Seq.empty),
      endpoints = knownEndpoints,
    )
    // the gateway talks to the provider in the OpenAI format, the provider serves this model the same way (not
    // through another sdk) and on an OpenAI endpoint. A router serves nothing itself.
    val kind = provider.provider.toLowerCase(Locale.ROOT)
    val servedByOpenAiSdk = served.forall(m => m.shape.isDefined || m.sdk.forall(ModelsCatalog.openAiSdks.contains))
    val openAiCompatible = Option.when(!AiProvider.routingProviders.contains(kind)) {
      AiProvider.openAiCompatibleProviders.contains(kind) && servedByOpenAiSdk &&
        (knownEndpoints.isEmpty || knownEndpoints.exists(_ != ModelEndpoints.Messages))
    }
    val endpoints = if (!openAiCompatible.contains(true)) Seq.empty
      else if (knownEndpoints.nonEmpty) knownEndpoints.filterNot(_ == ModelEndpoints.Messages)
      else ModelEndpoints.ofKinds(kinds, names, input.getOrElse(Seq.empty))
    val cacheRead = catalog.flatMap(_.cost).map(_.cacheRead.isDefined).filter(identity)
    val hasCost = ext.exists { e =>
      modality match {
        case AiProvidersCatalog.Text => e.costsTracking.hasCost(provider, model)
        case AiProvidersCatalog.Embedding | AiProvidersCatalog.Moderation => e.costsTracking.hasTokenCost(provider.provider, model)
        // images, sounds, videos and pages are not billed per token
        case _ => false
      }
    }
    ModelDescription(kinds, hasCost, endpoints, obj(
      "kinds" -> JsArray(kinds.map(JsString.apply)).some,
      "has_cost" -> JsBoolean(hasCost).some,
      "openai_compatible" -> openAiCompatible.map(JsBoolean.apply),
      "endpoints" -> Option.when(endpoints.nonEmpty)(JsArray(endpoints.map(JsString.apply))),
      "mode" -> mode.map(JsString.apply),
      "capabilities" -> Some(obj(
        "reasoning" -> catalog.flatMap(_.reasoning).orElse(flag("supports_reasoning")).map(JsBoolean.apply),
        "reasoning_options" -> catalog.flatMap(_.reasoningOptions),
        "tool_call" -> catalog.flatMap(_.toolCall).orElse(flag("supports_function_calling")).map(JsBoolean.apply),
        "structured_output" -> catalog.flatMap(_.structuredOutput).orElse(flag("supports_response_schema")).map(JsBoolean.apply),
        "temperature" -> catalog.flatMap(_.temperature).map(JsBoolean.apply),
        "attachment" -> catalog.flatMap(_.attachment).map(JsBoolean.apply),
        "prompt_caching" -> cacheRead.orElse(flag("supports_prompt_caching")).map(JsBoolean.apply),
        "web_search" -> flag("supports_web_search").map(JsBoolean.apply),
      )).filter(_.value.nonEmpty),
      "modalities" -> Option.when(input.isDefined || output.isDefined)(obj(
        "input" -> input.map(v => JsArray(v.map(JsString.apply))),
        "output" -> output.map(v => JsArray(v.map(JsString.apply))),
      )),
      "limits" -> Some(obj(
        "context" -> catalog.flatMap(_.limitContext).orElse(priced.map(_.max_input_tokens).filter(_ > 0)).map(v => JsNumber(BigDecimal(v))),
        "input" -> catalog.flatMap(_.limitInput).map(v => JsNumber(BigDecimal(v))),
        "output" -> catalog.flatMap(_.limitOutput).orElse(priced.map(_.max_output_tokens).filter(_ > 0)).map(v => JsNumber(BigDecimal(v))),
      )).filter(_.value.nonEmpty),
      "pricing" -> prices.map(pricing),
      "knowledge" -> catalog.flatMap(_.knowledge).map(JsString.apply),
      "status" -> catalog.flatMap(_.status).map(JsString.apply),
      "deprecation_date" -> raw.select("deprecation_date").asOptString.map(JsString.apply),
      "sources" -> Some(obj(
        "catalog" -> found.map(m => obj(
          "provider" -> m.model.provider.map(JsString.apply),
          "model" -> JsString(m.model.id).some,
          "match" -> JsString(m.kind).some,
        )),
        "pricing" -> prices.map(c => obj(
          "source" -> JsString(c.raw.select(ModelsCatalog.sourceField).asOptString.getOrElse(CostsOutput.sourcePriceTable)).some,
          "model" -> JsString(c.name).some,
          // prices published in another currency, converted to dollars
          "currency" -> c.raw.select(CostModel.currencyField).asOptString.map(JsString.apply),
          "exchange_rate" -> c.raw.select(CostModel.exchangeRateField).asOpt[BigDecimal].map(JsNumber.apply),
        )),
      )).filter(_.value.nonEmpty),
    ))
  }
}

/**
 * How a `/models` listing is asked for: `?enriched=true` adds a `_metadata` object to each model, `?kind=image,video`
 * (repeatable) only keeps the models of at least one of these types, `?endpoint=chat_completions,responses` the ones
 * served on at least one of these endpoints, `?has_cost=true|false` the models costs tracking can (or cannot) bill.
 */
final case class ModelsListing(enriched: Boolean, kinds: Set[String], endpoints: Set[String], hasCost: Option[Boolean]) {

  private val described = enriched || kinds.nonEmpty || endpoints.nonEmpty || hasCost.isDefined

  // the catalog is only loaded eagerly when it backs costs tracking
  def prepare()(using env: Env, ec: ExecutionContext): Future[Unit] = {
    env.adminExtensions.extension[AiExtension] match {
      case Some(ext) if described => ext.modelsCatalog.load().map(_ => ())
      case _ => ().vfuture
    }
  }

  // the listing entry of a model, None when the requested filters leave it out
  def entry(provider: AiProvider, model: String, base: JsObject)(using env: Env): Option[JsObject] = {
    if (!described) base.some else {
      val description = ModelsMetadata.describe(provider, model)
      val kept = (kinds.isEmpty || description.kinds.exists(kinds.contains)) &&
        (endpoints.isEmpty || description.endpoints.exists(endpoints.contains)) &&
        hasCost.forall(_ == description.hasCost)
      Option.when(kept) {
        if (enriched) base ++ Json.obj("_metadata" -> description.metadata) else base
      }
    }
  }
}

object ModelsListing {

  def apply(ctx: NgbBackendCallContext): ModelsListing = {
    val query = ctx.rawRequest.queryString
    def flag(name: String): Option[Boolean] = ctx.request.queryParam(name).map(_.trim.toLowerCase(Locale.ROOT)).collect {
      case "" | "true" | "1" => true
      case "false" | "0" => false
    }
    def values(names: String*): Set[String] = names.flatMap(name => query.getOrElse(name, Seq.empty))
      .flatMap(_.split(","))
      .map(_.trim.toLowerCase(Locale.ROOT))
      .filter(_.nonEmpty)
      .toSet
    ModelsListing(
      enriched = flag("enriched").contains(true),
      hasCost = flag("has_cost"),
      kinds = values("kind", "kinds"),
      endpoints = values("endpoint", "endpoints"),
    )
  }
}
