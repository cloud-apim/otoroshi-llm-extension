package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import org.apache.pekko.stream.scaladsl.{Sink, Source, StreamConverters}
import org.apache.pekko.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.{AudioModelClient, AudioModelClientSpeechToTextInputOptions, AudioModelClientTextToSpeechInputOptions, AudioModelClientTranslationInputOptions, AudioTranscriptionResponse, ChatCallKind, ChatClient, ChatPrompt, ChatResponse, ChatResponseChunk, ChatResponseChunkChoice, ChatResponseChunkChoiceDelta, EmbeddingClientInputOptions, EmbeddingModelClient, EmbeddingResponse, ImageModelClient, ImageModelClientEditionInputOptions, ImageModelClientGenerationInputOptions, ImagesGenResponse, ModerationModelClient, ModerationModelClientInputOptions, ModerationResponse, OcrModelClient, OcrModelClientInputOptions, OcrModelClientResponse, VideoModelClient, VideoModelClientTextToVideoInputOptions, VideosGenResponse}
import com.cloud.apim.otoroshi.extensions.aigateway.AiMetrics
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiProvider, AudioModel, EmbeddingModel, ImageModel, ModelSettings, ModerationModel, OcrModel, VideoModel}
import io.azam.ulidj.ULID
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import play.api.Configuration
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.libs.ws.WSResponse
import play.api.libs.ws.WSBodyReadables.readableAsString
import play.api.libs.typedmap.TypedKey

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.{Failure, Success}
import org.apache.pekko.actor.Cancellable
import org.apache.pekko.stream.Materializer

case class CostsTrackingSettings(configuration: Configuration) {
  val embedDescriptionInJson = configuration.getOptional[Boolean]("embed-description-in-json").getOrElse(true)
  val embedCostsTrackingInResponses = configuration.getOptional[Boolean]("embed-costs-tracking-in-responses").getOrElse(false)
  val enabled = configuration.getOptional[Boolean]("enabled").getOrElse(true)
  // the static price table only knows a subset of what OpenRouter exposes, so its catalog can be synced to
  // price the rest. Opt-in: it is the only outbound call the extension makes on its own, and an instance
  // without egress should not have to discover it through periodic errors in its logs.
  val openRouterCatalogEnabled = configuration.getOptional[Boolean]("openrouter-catalog.enabled").getOrElse(false)
  val openRouterCatalogUrl = configuration.getOptional[String]("openrouter-catalog.url").getOrElse(OpenRouterCatalog.defaultUrl)
  val openRouterCatalogRefreshEvery = configuration.getOptional[FiniteDuration]("openrouter-catalog.refresh-every").getOrElse(6.hours)
}

case class SearchContextCostPerQuery(raw: JsValue) {
  lazy val search_context_size_low = raw.select("search_context_size_low").asOpt[BigDecimal].getOrElse(BigDecimal(0))
  lazy val search_context_size_medium = raw.select("search_context_size_medium").asOpt[BigDecimal].getOrElse(BigDecimal(0))
  lazy val search_context_size_high = raw.select("search_context_size_high").asOpt[BigDecimal].getOrElse(BigDecimal(0))
}

case class CostModel(name: String, raw: JsValue) {
  lazy val max_tokens = raw.select("max_tokens").asOptLong.getOrElse(0L)
  lazy val max_input_tokens = raw.select("max_input_tokens").asOptLong.getOrElse(0L)
  lazy val max_output_tokens = raw.select("max_output_tokens").asOptLong.getOrElse(0L)
  lazy val input_cost_per_token = raw.select("input_cost_per_token").asOpt[BigDecimal].getOrElse(BigDecimal(0))
  lazy val output_cost_per_token = raw.select("output_cost_per_token").asOpt[BigDecimal].getOrElse(BigDecimal(0))
  lazy val output_cost_per_reasoning_token = raw.select("output_cost_per_reasoning_token").asOpt[BigDecimal].getOrElse(BigDecimal(0))
  lazy val input_cost_per_token_cache_hit = raw.select("input_cost_per_token_cache_hit").asOpt[BigDecimal].getOrElse(BigDecimal(0))
  lazy val cache_read_input_token_cost = raw.select("cache_read_input_token_cost").asOpt[BigDecimal].getOrElse(BigDecimal(0))
  lazy val cache_creation_input_token_cost = raw.select("cache_creation_input_token_cost").asOpt[BigDecimal].getOrElse(BigDecimal(0))
  lazy val input_cost_per_token_batches = raw.select("input_cost_per_token_batches").asOpt[BigDecimal].getOrElse(BigDecimal(0))
  lazy val output_cost_per_token_batches = raw.select("output_cost_per_token_batches").asOpt[BigDecimal].getOrElse(BigDecimal(0))
  lazy val input_cost_per_audio_token = raw.select("input_cost_per_audio_token").asOpt[BigDecimal].getOrElse(BigDecimal(0))
  lazy val output_cost_per_audio_token = raw.select("output_cost_per_audio_token").asOpt[BigDecimal].getOrElse(BigDecimal(0))
  lazy val input_cost_per_image_token = raw.select("input_cost_per_image_token").asOpt[BigDecimal].getOrElse(BigDecimal(0))
  lazy val input_cost_per_image = raw.select("input_cost_per_image").asOpt[BigDecimal].getOrElse(BigDecimal(0))
  lazy val output_cost_per_image = raw.select("output_cost_per_image").asOpt[BigDecimal].getOrElse(BigDecimal(0))
  lazy val input_cost_per_pixel = raw.select("input_cost_per_pixel").asOpt[BigDecimal].getOrElse(BigDecimal(0))
  lazy val output_cost_per_pixel = raw.select("output_cost_per_pixel").asOpt[BigDecimal].getOrElse(BigDecimal(0))
  lazy val input_cost_per_second = raw.select("input_cost_per_second").asOpt[BigDecimal].getOrElse(BigDecimal(0))
  lazy val output_cost_per_second = raw.select("output_cost_per_second").asOpt[BigDecimal].getOrElse(BigDecimal(0))
  lazy val litellm_provider = raw.select("litellm_provider").asOptString.orElse(raw.select("provider").asOptString).getOrElse("openai")
  lazy val nameWithoutProvider: String = {
    if (name.startsWith(s"${litellm_provider}/")) {
      val v = name.replaceFirst(s"${litellm_provider}/", "")
      v
    } else {
      name
    }
  }
  lazy val mode = raw.select("mode").asOptString.getOrElse("completion")
  lazy val deprecation_date = raw.select("deprecation_date").asOpt[String]
  lazy val supports_function_calling = raw.select("supports_function_calling").asOptBoolean.getOrElse(false)
  lazy val supports_parallel_function_calling = raw.select("supports_parallel_function_calling").asOptBoolean.getOrElse(false)
  lazy val supports_vision = raw.select("supports_vision").asOptBoolean.getOrElse(false)
  lazy val supports_audio_input = raw.select("supports_audio_input").asOptBoolean.getOrElse(false)
  lazy val supports_audio_output = raw.select("supports_audio_output").asOptBoolean.getOrElse(false)
  lazy val supports_prompt_caching = raw.select("supports_prompt_caching").asOptBoolean.getOrElse(false)
  lazy val supports_reasoning = raw.select("supports_reasoning").asOptBoolean.getOrElse(false)
  lazy val supports_response_schema = raw.select("supports_response_schema").asOptBoolean.getOrElse(false)
  lazy val supports_system_messages = raw.select("supports_system_messages").asOptBoolean.getOrElse(false)
  lazy val supports_web_search = raw.select("supports_web_search").asOptBoolean.getOrElse(false)
  lazy val supports_tool_choice = raw.select("supports_tool_choice").asOptBoolean.getOrElse(false)
  lazy val supports_native_streaming = raw.select("supports_native_streaming").asOptBoolean.getOrElse(false)
  lazy val search_context_cost_per_query = raw.select("search_context_cost_per_query").asOpt[JsObject].map { obj =>
    SearchContextCostPerQuery(obj)
  }
  lazy val effectiveReasoningTokenCost: BigDecimal =
    if (output_cost_per_reasoning_token > 0) output_cost_per_reasoning_token else output_cost_per_token
}

object CostsOutput {

  // where the numbers come from, exposed in the json so that a missing/odd cost can be diagnosed
  val sourcePriceTable = "price-table"
  val sourceProvider = "provider"

  def fromJson(raw: JsValue): Option[CostsOutput] = raw.asOpt[JsObject].flatMap { obj =>
    obj.select("total_cost").asOpt[BigDecimal].map { total =>
      CostsOutput(
        inputCost = obj.select("input_cost").asOpt[BigDecimal].getOrElse(BigDecimal(0)),
        outputCost = obj.select("output_cost").asOpt[BigDecimal].getOrElse(BigDecimal(0)),
        reasoningCost = obj.select("reasoning_cost").asOpt[BigDecimal].getOrElse(BigDecimal(0)),
        reportedTotalCost = total.some,
        source = obj.select("source").asOptString.getOrElse(sourceProvider),
      )
    }
  }

  // OpenAI-shaped `usage` object as returned by OpenRouter when `usage.include` is true:
  // `cost` is the authoritative total (in dollars), `cost_details` splits it prompt/completion.
  // Providers that do not report costs simply have no `cost` field and yield None.
  def fromOpenAiLikeUsage(usage: JsValue): Option[CostsOutput] = {
    usage.select("cost").asOpt[BigDecimal].map { total =>
      CostsOutput(
        inputCost = usage.at("cost_details.upstream_inference_prompt_cost").asOpt[BigDecimal].getOrElse(BigDecimal(0)),
        outputCost = usage.at("cost_details.upstream_inference_completions_cost").asOpt[BigDecimal].getOrElse(BigDecimal(0)),
        reasoningCost = BigDecimal(0),
        reportedTotalCost = total.some,
        source = sourceProvider,
      )
    }
  }
}

case class CostsOutput(
  inputCost: BigDecimal,
  outputCost: BigDecimal,
  reasoningCost: BigDecimal,
  // when the provider reports the total itself, it wins over the sum of the parts: the parts can be
  // partial (no reasoning split) or absent while the total is still exact
  reportedTotalCost: Option[BigDecimal] = None,
  source: String = CostsOutput.sourcePriceTable,
) {
  def totalCost: BigDecimal = reportedTotalCost.getOrElse(inputCost + outputCost + reasoningCost)
  def plus(other: CostsOutput): CostsOutput = CostsOutput(
    inputCost = inputCost + other.inputCost,
    outputCost = outputCost + other.outputCost,
    reasoningCost = reasoningCost + other.reasoningCost,
    reportedTotalCost = (reportedTotalCost, other.reportedTotalCost) match {
      case (Some(a), Some(b)) => (a + b).some
      case (a, b) => a.orElse(b)
    },
    source = source,
  )
  def json: JsValue = Json.obj(
    "input_cost" -> inputCost,
    "output_cost" -> outputCost,
    "reasoning_cost" -> reasoningCost,
    "total_cost" -> totalCost,
    "currency" -> "dollar",
    "source" -> source,
  )
}

object OpenRouterCatalog {

  val defaultUrl = "https://openrouter.ai/api/v1/models"

  // prices in the catalog are already per-token dollar amounts, expressed as strings
  private def price(pricing: JsValue, field: String): BigDecimal =
    pricing.select(field).asOpt[BigDecimal]
      .orElse(pricing.select(field).asOptString.flatMap(str => scala.util.Try(BigDecimal(str)).toOption))
      .getOrElse(BigDecimal(0))

  def toCostModel(model: JsValue): Option[CostModel] = {
    for {
      id <- model.select("id").asOptString
      pricing <- model.select("pricing").asOpt[JsObject]
    } yield {
      val reasoning = price(pricing, "internal_reasoning")
      CostModel(id, Json.obj(
        "litellm_provider" -> "openrouter",
        "mode" -> "chat",
        "max_tokens" -> model.at("top_provider.max_completion_tokens").asOpt[Long].getOrElse(0L),
        "max_input_tokens" -> model.select("context_length").asOpt[Long].getOrElse(0L),
        "max_output_tokens" -> model.at("top_provider.max_completion_tokens").asOpt[Long].getOrElse(0L),
        "input_cost_per_token" -> price(pricing, "prompt"),
        "output_cost_per_token" -> price(pricing, "completion"),
        "output_cost_per_reasoning_token" -> reasoning,
        "cache_read_input_token_cost" -> price(pricing, "input_cache_read"),
        "cache_creation_input_token_cost" -> price(pricing, "input_cache_write"),
        "input_cost_per_audio_token" -> price(pricing, "audio"),
        "output_cost_per_audio_token" -> price(pricing, "audio_output"),
        "input_cost_per_image" -> price(pricing, "image"),
        "output_cost_per_image" -> price(pricing, "image_output"),
        "supports_reasoning" -> (reasoning > 0),
      ))
    }
  }
}

class CostsTracking(settings: CostsTrackingSettings, env: Env) {

  lazy val extension = env.adminExtensions.extension[AiExtension].get

  val litllmModels: Map[String, CostModel] = {
    val json = Json.parse(getResourceCode("data/ltllm-prices.json")).asObject
    json.value.filterNot(_._1 == "sample_spec").map {
      case (name, obj) => CostModel(name, obj)
    }.map {
      case c if c.litellm_provider == "gemini" => (s"${c.litellm_provider}-models/${c.nameWithoutProvider.replaceFirst("models/", "")}", c)
      case c => (s"${c.litellm_provider}-${c.nameWithoutProvider}", c)
    }.toMap
  }

  val customModels: Map[String, CostModel] = {
    val json = Json.parse(getResourceCode("data/custom-ltllm-prices.json")).asObject
    json.value.filterNot(_._1 == "sample_spec").map {
      case (name, obj) => CostModel(name, obj)
    }.map(c => (s"${c.litellm_provider}-${c.nameWithoutProvider}", c)).toMap
  }

  val userProvidedModels: Map[String, CostModel] = {
    val json = settings.configuration.getOptional[String]("custom-prices").getOrElse("{}").parseJson.asObject
    json.value.filterNot(_._1 == "sample_spec").map {
      case (name, obj) => CostModel(name, obj)
    }.map(c => (s"${c.litellm_provider}-${c.nameWithoutProvider}", c)).toMap
  }

  val staticModels: Map[String, CostModel] = litllmModels ++ customModels ++ userProvidedModels

  // models discovered at runtime from a provider catalog (see refreshOpenRouterCatalog). Kept apart from
  // the static ones so that a refresh never drops what the resource files provide, and pre-merged so that
  // reads stay O(1) - `models` is hit on every single call.
  private val dynamicModelsRef = new AtomicReference[Map[String, CostModel]](Map.empty)
  private val allModelsRef = new AtomicReference[Map[String, CostModel]](staticModels)
  private val openRouterSchedulerRef = new AtomicReference[Cancellable]()

  def dynamicModels: Map[String, CostModel] = dynamicModelsRef.get()
  def models: Map[String, CostModel] = allModelsRef.get()

  private def setDynamicModels(newModels: Map[String, CostModel]): Unit = {
    dynamicModelsRef.set(newModels)
    // static entries win: they are curated, and a user provided price must not be overridden by a sync
    allModelsRef.set(newModels ++ staticModels)
  }

  def getResourceCode(path: String): String = {
    given ec: ExecutionContext = env.otoroshiExecutionContext
    given mat: Materializer = env.otoroshiMaterializer
    env.environment.resourceAsStream(path)
      .map(stream => StreamConverters.fromInputStream(() => stream).runFold(ByteString.empty)(_++_).awaitf(10.seconds).utf8String)
      .getOrElse(s"'resource ${path} not found !'")
  }

  // OpenRouter exposes variants of a model (`:free`, `:batch`, `:nitro`, ...) and floating aliases
  // (`~vendor/model-latest`) that are absent from the price table under that exact name while their base
  // model is present. Restricted to openrouter on purpose: Bedrock model names legitimately end with `:0`.
  private def fallbackModelNames(provider: String, modelName: String): Seq[String] = {
    if (provider != "openrouter") Seq.empty else {
      val withoutAlias = modelName.stripPrefix("~")
      val withoutVariant = withoutAlias.takeWhile(_ != ':')
      Seq(withoutAlias, withoutVariant).filter(name => name.nonEmpty && name != modelName).distinct
    }
  }

  def lookupModel(provider: String, modelName: String): Option[CostModel] = {
    val all = models
    all.get(s"${provider}-${modelName}").orElse {
      fallbackModelNames(provider, modelName).iterator.flatMap(name => all.get(s"${provider}-${name}")).nextOption()
    }
  }

  def canHandle(provider: String, modelName: String): Boolean = {
    lookupModel(provider, modelName).isDefined
  }

  def getModel(provider: String, modelName: String): Option[CostModel] = lookupModel(provider, modelName)
  def findModel(provider: String, name: String): Option[CostModel] = models.values.find(m => m.litellm_provider == provider && m.name == name)
  def searchModel(m: CostModel => Boolean): Option[CostModel] = models.values.find(m)

  def computeCosts(
    provider: String,
    modelName: String,
    inputTokens: Long,
    outputTokens: Long,
    reasoningTokens: Long,
  ): Either[String, CostsOutput] = {
    lookupModel(provider, modelName) match {
      case None =>
        if (extension.logger.isWarnEnabled) extension.logger.warn(s"unable to find costs for model: '${provider}-${modelName}'")
        Left("model not found")
      case Some(model) => {
        Right(CostsOutput(
          inputCost = inputTokens * model.input_cost_per_token,
          outputCost = outputTokens * model.output_cost_per_token,
          reasoningCost = reasoningTokens * model.effectiveReasoningTokenCost,
        ))
      }
    }
  }

  // OpenRouter is the one provider we know reports the exact cost of a call back to us (usage.cost, enabled
  // through `usage.include` in OpenAiLikeProviders). Used to decide, before a stream even starts, whether a
  // cost may still show up for a model the price table does not know.
  def providerReportsCosts(provider: String): Boolean = provider == "openrouter"

  def startOpenRouterCatalogSync(): Unit = {
    if (settings.openRouterCatalogEnabled) {
      given ec: ExecutionContext = env.otoroshiExecutionContext
      openRouterSchedulerRef.set(env.otoroshiScheduler.scheduleAtFixedRate(5.seconds, settings.openRouterCatalogRefreshEvery) { () =>
        refreshOpenRouterCatalog().andThen {
          case Success(count) if extension.logger.isDebugEnabled => extension.logger.debug(s"openrouter catalog synced: ${count} models priced")
          case Failure(err) => extension.logger.error("unable to sync the openrouter models catalog", err)
        }
      })
    }
  }

  def stopOpenRouterCatalogSync(): Unit = {
    Option(openRouterSchedulerRef.get()).foreach(_.cancel())
  }

  // OpenRouter publishes the price of every model it serves on a public endpoint. Syncing it gives a price to
  // the models missing from the static table, which would otherwise be billed at zero and leave budgets untouched.
  def refreshOpenRouterCatalog()(using ec: ExecutionContext): Future[Int] = {
    env.Ws.url(settings.openRouterCatalogUrl)
      .withRequestTimeout(30.seconds)
      .get()
      .map { (resp: WSResponse) =>
        if (resp.status != 200) {
          extension.logger.error(s"unable to fetch the openrouter models catalog: ${resp.status} - ${resp.body[String].take(256)}")
          dynamicModels.size
        } else {
          val models = resp.json.select("data").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
            .flatMap(model => OpenRouterCatalog.toCostModel(model).map(cost => (s"openrouter-${cost.name}", cost)))
            .toMap
          if (models.isEmpty) {
            extension.logger.warn("the openrouter models catalog came back empty, keeping the previous one")
            dynamicModels.size
          } else {
            setDynamicModels(models)
            models.size
          }
        }
      }
  }

  def getProvider(provider: String): Option[String] = {
    provider.toLowerCase() match {
      case "openai" => "openai".some
      case "openai-compatible" => None
      case "scaleway" => None
      case "deepseek" => "deepseek".some
      case "x-ai" => "xai".some
      case "ovh-ai-endpoints" => "ovhcloud".some
      case "ovh-ai-endpoints-unified" => "ovhcloud".some
      case "azure-openai" => "azure".some
      case "azure-ai-foundry" => "azure".some
      case "cloudflare" => "cloudflare".some
      case "gemini" => "gemini".some
      case "huggingface" => None
      case "mistral" => "mistral".some
      case "ollama" => "ollama".some
      case "ollama-openai" => "ollama".some
      case "cohere" => "cohere".some
      case "anthropic" => "anthropic".some
      case "groq" => "groq".some
      case v => v.some
    }
  }
}


// A provider can demand that every model it serves has a known price (`models.require_known_costs`).
// The point is to never run a call that could not be billed, so the check happens before the provider is
// reached at all, and the models whose price is unknown disappear from the listings.
object RequiredCosts {

  val errorMessage = "no known cost for this model"

  def error(model: Option[String]): JsValue = Json.obj(
    "error" -> errorMessage,
    "model" -> model.getOrElse("--").json,
  )

  // maps an entity provider ("x-ai", "ovh-ai-endpoints", ...) to the name the price grid is keyed on.
  // None when the provider cannot be priced at all, in which case nothing it serves has a known cost.
  def pricingProvider(providerKind: String)(using env: Env): Option[String] = {
    env.adminExtensions.extension[AiExtension].flatMap(_.costsTracking.getProvider(providerKind))
  }

  // the price grid is the in-memory one: resource files, user provided prices and the synced catalogs
  def hasKnownCosts(provider: Option[String], model: String)(using env: Env): Boolean = {
    env.adminExtensions.extension[AiExtension].exists { ext =>
      provider.exists(name => ext.costsTracking.canHandle(name, model))
    }
  }

  def check(provider: Option[String], settings: ModelSettings, model: Option[String])(using env: Env): Either[JsValue, Unit] = {
    if (!settings.requireKnownCosts) {
      Right(())
    } else model match {
      case Some(name) if hasKnownCosts(provider, name) => Right(())
      // an unresolved model is refused too: without knowing which model runs, no price can be guaranteed
      case _ => AiMetrics.markModelConstraintDenied(); Left(error(model))
    }
  }

  // same rule applied to a listing: what cannot be billed is not offered
  def filterModels(provider: Option[String], settings: ModelSettings, models: List[String])(using env: Env): List[String] = {
    if (!settings.requireKnownCosts) models else models.filter(model => hasKnownCosts(provider, model))
  }
}

// The same gate for the non-text modalities. They have no cost decorator of their own, so these only
// enforce `models.require_known_costs`: a model with no price is refused before the provider is called.
// Listing is not covered here on purpose - only the text `/models` endpoint lists models.
private def requiredCostsOf(provider: String, settings: ModelSettings, model: Option[String], configured: => Option[String])(using env: Env): Either[JsValue, Unit] = {
  RequiredCosts.check(RequiredCosts.pricingProvider(provider), settings, model.orElse(configured))
}

object EmbeddingModelClientWithRequiredCosts {
  def applyIfPossible(tuple: (EmbeddingModel, EmbeddingModelClient, Env)): EmbeddingModelClient =
    if (tuple._1.models.requireKnownCosts) new EmbeddingModelClientWithRequiredCosts(tuple._1, tuple._2) else tuple._2
}

class EmbeddingModelClientWithRequiredCosts(originalModel: EmbeddingModel, val embeddingModelClient: EmbeddingModelClient) extends DecoratorEmbeddingModelClient {
  override def embed(opts: EmbeddingClientInputOptions, rawBody: JsObject, attrs: TypedMap)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, EmbeddingResponse]] = {
    requiredCostsOf(originalModel.provider, originalModel.models, opts.model, originalModel.config.at("options.model").asOptString) match {
      case Left(err) => err.leftf
      case Right(_) => embeddingModelClient.embed(opts, rawBody, attrs)
    }
  }
}

object AudioModelClientWithRequiredCosts {
  def applyIfPossible(tuple: (AudioModel, AudioModelClient, Env)): AudioModelClient =
    if (tuple._1.models.requireKnownCosts) new AudioModelClientWithRequiredCosts(tuple._1, tuple._2) else tuple._2
}

class AudioModelClientWithRequiredCosts(originalModel: AudioModel, val audioModelClient: AudioModelClient) extends DecoratorAudioModelClient {

  private def check(model: Option[String], slot: String)(using env: Env): Either[JsValue, Unit] =
    requiredCostsOf(originalModel.provider, originalModel.models, model, originalModel.config.at(slot).asOptString)

  override def speechToText(opts: AudioModelClientSpeechToTextInputOptions, rawBody: JsObject, attrs: TypedMap)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, AudioTranscriptionResponse]] = {
    check(opts.model, "stt.model") match {
      case Left(err) => err.leftf
      case Right(_) => audioModelClient.speechToText(opts, rawBody, attrs)
    }
  }

  override def textToSpeech(opts: AudioModelClientTextToSpeechInputOptions, rawBody: JsObject, attrs: TypedMap)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, (Source[ByteString, ?], String)]] = {
    check(opts.model, "tts.model") match {
      case Left(err) => err.leftf
      case Right(_) => audioModelClient.textToSpeech(opts, rawBody, attrs)
    }
  }

  override def translate(opts: AudioModelClientTranslationInputOptions, rawBody: JsObject, attrs: TypedMap)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, AudioTranscriptionResponse]] = {
    check(opts.model, "translate.model") match {
      case Left(err) => err.leftf
      case Right(_) => audioModelClient.translate(opts, rawBody, attrs)
    }
  }
}

object ImageModelClientWithRequiredCosts {
  def applyIfPossible(tuple: (ImageModel, ImageModelClient, Env)): ImageModelClient =
    if (tuple._1.models.requireKnownCosts) new ImageModelClientWithRequiredCosts(tuple._1, tuple._2) else tuple._2
}

class ImageModelClientWithRequiredCosts(originalModel: ImageModel, val imageModelClient: ImageModelClient) extends DecoratorImageModelClient {

  private def check(model: Option[String], slot: String)(using env: Env): Either[JsValue, Unit] =
    requiredCostsOf(originalModel.provider, originalModel.models, model, originalModel.config.at(slot).asOptString)

  override def generate(opts: ImageModelClientGenerationInputOptions, rawBody: JsObject, attrs: TypedMap)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, ImagesGenResponse]] = {
    check(opts.model, "options.generation.model") match {
      case Left(err) => err.leftf
      case Right(_) => imageModelClient.generate(opts, rawBody, attrs)
    }
  }

  override def edit(opts: ImageModelClientEditionInputOptions, rawBody: JsObject, attrs: TypedMap)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, ImagesGenResponse]] = {
    check(opts.model, "options.edition.model") match {
      case Left(err) => err.leftf
      case Right(_) => imageModelClient.edit(opts, rawBody, attrs)
    }
  }
}

object VideoModelClientWithRequiredCosts {
  def applyIfPossible(tuple: (VideoModel, VideoModelClient, Env)): VideoModelClient =
    if (tuple._1.models.requireKnownCosts) new VideoModelClientWithRequiredCosts(tuple._1, tuple._2) else tuple._2
}

class VideoModelClientWithRequiredCosts(originalModel: VideoModel, val videoModelClient: VideoModelClient) extends DecoratorVideoModelClient {
  override def generate(opts: VideoModelClientTextToVideoInputOptions, rawBody: JsObject, attrs: TypedMap)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, VideosGenResponse]] = {
    requiredCostsOf(originalModel.provider, originalModel.models, opts.model, originalModel.config.at("options.model").asOptString) match {
      case Left(err) => err.leftf
      case Right(_) => videoModelClient.generate(opts, rawBody, attrs)
    }
  }
}

object ModerationModelClientWithRequiredCosts {
  def applyIfPossible(tuple: (ModerationModel, ModerationModelClient, Env)): ModerationModelClient =
    if (tuple._1.models.requireKnownCosts) new ModerationModelClientWithRequiredCosts(tuple._1, tuple._2) else tuple._2
}

class ModerationModelClientWithRequiredCosts(originalModel: ModerationModel, val moderationModelClient: ModerationModelClient) extends DecoratorModerationModelClient {
  override def moderate(opts: ModerationModelClientInputOptions, rawBody: JsObject, attrs: TypedMap)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, ModerationResponse]] = {
    requiredCostsOf(originalModel.provider, originalModel.models, opts.model, originalModel.config.at("options.model").asOptString) match {
      case Left(err) => err.leftf
      case Right(_) => moderationModelClient.moderate(opts, rawBody, attrs)
    }
  }
}

object OcrModelClientWithRequiredCosts {
  def applyIfPossible(tuple: (OcrModel, OcrModelClient, Env)): OcrModelClient =
    if (tuple._1.models.requireKnownCosts) new OcrModelClientWithRequiredCosts(tuple._1, tuple._2) else tuple._2
}

class OcrModelClientWithRequiredCosts(originalModel: OcrModel, val ocrModelClient: OcrModelClient) extends DecoratorOcrModelClient {
  override def ocr(opts: OcrModelClientInputOptions, rawBody: JsObject, attrs: TypedMap)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, OcrModelClientResponse]] = {
    requiredCostsOf(originalModel.provider, originalModel.models, opts.model, originalModel.config.at("options.model").asOptString) match {
      case Left(err) => err.leftf
      case Right(_) => ocrModelClient.ocr(opts, rawBody, attrs)
    }
  }
}

// Embedding and moderation are billed per token, exactly like text, so the price grid applies as is.
// The other modalities are not: the grid bills them per image, pixel, second, character or page, units
// `computeCosts` does not know about, and a token based computation would silently return zero for them.
object TokenBasedCosts {

  def settings(using env: Env): CostsTrackingSettings =
    env.adminExtensions.extension[AiExtension].get.costsTrackingSettings

  def embedInResponse(attrs: TypedMap)(using env: Env): Boolean = {
    settings.embedCostsTrackingInResponses ||
      attrs.get(otoroshi.plugins.Keys.RequestKey).flatMap(_.getQueryString("embed_costs")).contains("true")
  }

  // stores the cost where auditing reads it, so budgets, audit events and metrics all pick it up
  def track(providerKind: String, model: Option[String], inputTokens: Long, outputTokens: Long, attrs: TypedMap)(using env: Env): Option[CostsOutput] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    for {
      provider <- RequiredCosts.pricingProvider(providerKind)
      name <- model.filter(_.nonEmpty)
      costs <- ext.costsTracking.computeCosts(provider, name, inputTokens, outputTokens, 0L).toOption
    } yield {
      attrs.put(ChatClientWithCostsTracking.key -> costs)
      costs
    }
  }
}

object EmbeddingModelClientWithCostsTracking {
  def applyIfPossible(tuple: (EmbeddingModel, EmbeddingModelClient, Env)): EmbeddingModelClient = {
    if (TokenBasedCosts.settings(using tuple._3).enabled) new EmbeddingModelClientWithCostsTracking(tuple._1, tuple._2) else tuple._2
  }
}

class EmbeddingModelClientWithCostsTracking(originalModel: EmbeddingModel, val embeddingModelClient: EmbeddingModelClient) extends DecoratorEmbeddingModelClient {
  override def embed(opts: EmbeddingClientInputOptions, rawBody: JsObject, attrs: TypedMap)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, EmbeddingResponse]] = {
    embeddingModelClient.embed(opts, rawBody, attrs).map {
      case Left(err) => Left(err)
      case Right(resp) => {
        // the model the provider actually answered with is the most accurate one to price
        val model = Some(resp.model).filter(_.nonEmpty).orElse(opts.model).orElse(originalModel.config.at("options.model").asOptString)
        // embeddings are input only, and a provider that reports no usage yields -1: nothing to bill
        val tokens = math.max(0L, resp.metadata.tokenUsage)
        val costs = TokenBasedCosts.track(originalModel.provider, model, tokens, 0L, attrs)
        if (TokenBasedCosts.embedInResponse(attrs)) {
          Right(resp.copy(metadata = resp.metadata.copy(costs = costs)))
        } else {
          Right(resp)
        }
      }
    }
  }
}

object ModerationModelClientWithCostsTracking {
  def applyIfPossible(tuple: (ModerationModel, ModerationModelClient, Env)): ModerationModelClient = {
    if (TokenBasedCosts.settings(using tuple._3).enabled) new ModerationModelClientWithCostsTracking(tuple._1, tuple._2) else tuple._2
  }
}

class ModerationModelClientWithCostsTracking(originalModel: ModerationModel, val moderationModelClient: ModerationModelClient) extends DecoratorModerationModelClient {
  override def moderate(opts: ModerationModelClientInputOptions, rawBody: JsObject, attrs: TypedMap)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, ModerationResponse]] = {
    moderationModelClient.moderate(opts, rawBody, attrs).map {
      case Left(err) => Left(err)
      case Right(resp) => {
        val model = Some(resp.model).filter(_.nonEmpty).orElse(opts.model).orElse(originalModel.config.at("options.model").asOptString)
        val usage = resp.metadata.usage
        // providers that only report a total put everything on the input side, which is how moderation bills
        val inputTokens = if (usage.input > 0 || usage.output > 0) math.max(0L, usage.input) else math.max(0L, usage.total)
        val outputTokens = math.max(0L, usage.output)
        val costs = TokenBasedCosts.track(originalModel.provider, model, inputTokens, outputTokens, attrs)
        if (TokenBasedCosts.embedInResponse(attrs)) {
          Right(resp.copy(metadata = resp.metadata.copy(costs = costs)))
        } else {
          Right(resp)
        }
      }
    }
  }
}

object ChatClientWithCostsTracking {
  val key = TypedKey[CostsOutput]("cloud-apim.ai-gateway.CostsOutputKey")
  val enabledRef = new AtomicReference[Option[Boolean]](None)
  def applyIfPossible(tuple: (AiProvider, ChatClient, Env)): ChatClient = {
    if (enabledRef.get().isEmpty) {
      enabledRef.set(Some(tuple._3.adminExtensions.extension[AiExtension].get.costsTrackingSettings.enabled))
    }
    // a provider demanding known costs still needs this decorator when tracking is globally off: the
    // whole point of the flag is to refuse calls it cannot price
    if (enabledRef.get().get || tuple._1.models.requireKnownCosts) {
      new ChatClientWithCostsTracking(tuple._1, tuple._2, tuple._3)
    } else {
      tuple._2
    }
  }
}

class ChatClientWithCostsTracking(originalProvider: AiProvider, val chatClient: ChatClient, decoratorEnv: Env) extends DecoratorChatClient {

  // the provider name the price grid is keyed on, honouring the same metadata override as the cost computation
  private def pricingProvider()(using env: Env): Option[String] = {
    RequiredCosts.pricingProvider(originalProvider.provider)
      .map(provider => originalProvider.metadata.getOrElse("costs-tracking-provider", provider))
      .orElse(originalProvider.metadata.get("costs-tracking-provider"))
  }

  private def pricedModel(originalBody: JsValue): Option[String] = {
    Some(originalProvider.metadata.getOrElse("costs-tracking-model", getModel(originalBody))).filterNot(_ == "--")
  }

  // refuses the call before the provider is reached when the provider demands a price we do not have
  private def checkRequiredCosts(originalBody: JsValue)(using env: Env): Either[JsValue, Unit] = {
    RequiredCosts.check(pricingProvider(), originalProvider.models, pricedModel(originalBody))
  }

  def getModel(originalBody: JsValue): String = {
    val allowConfigOverride = originalProvider.options.select("allow_config_override").asOptBoolean.getOrElse(true)
    if (allowConfigOverride) originalBody.select("model").asOptString.getOrElse(chatClient.computeModel(originalBody).getOrElse("--")) else chatClient.computeModel(originalBody).getOrElse("--")
  }

  def getProvider()(using env: Env): Option[String] = {
    env.adminExtensions.extension[AiExtension].flatMap(ext => ext.costsTracking.getProvider(originalProvider.provider))
  }

  // A cost the provider itself reported for the call always wins over what we would derive from the price
  // table: it is exact, and it is the only thing available for the many models the table does not know.
  private def resolveCosts(
    ext: AiExtension,
    providerCosts: Option[CostsOutput],
    provider: String,
    model: String,
    inputTokens: Long,
    outputTokens: Long,
    reasoningTokens: Long,
  ): Option[CostsOutput] = {
    providerCosts.orElse {
      ext.costsTracking.computeCosts(
        provider = provider,
        modelName = model,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        reasoningTokens = reasoningTokens,
      ).toOption
    }
  }

  private def handleStream(attrs: TypedMap, originalBody: JsValue)(f: => Future[Either[JsValue, Source[ChatResponseChunk, ?]]])(using ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, ?]]] = {
    getProvider() match {
      case None => f // unsupported provider
      case Some(provider) => {
        f.map {
          case Left(err) => Left(err)
          case Right(resp) => {
            val promise = Promise.apply[Option[ChatResponseChunk]]()
            val ext = env.adminExtensions.extension[AiExtension].get
            val finalProvider = originalProvider.metadata.getOrElse("costs-tracking-provider", provider)
            val model = originalProvider.metadata.getOrElse("costs-tracking-model", getModel(originalBody))
            val enableInRequest = attrs.get(otoroshi.plugins.Keys.RequestKey).flatMap(_.getQueryString("embed_costs")).contains("true")
            val budgetInRequest = attrs.get(otoroshi.plugins.Keys.RequestKey).flatMap(_.getQueryString("embed_budget")).contains("true")
            val addCostsInResp = ext.costsTrackingSettings.embedCostsTrackingInResponses || enableInRequest
            // the real finish reason is stripped from the chunks below and restored on the terminal chunk:
            // reporting "stop" for a stream that was actually truncated would mislead the caller
            val lastFinishReason = new AtomicReference[Option[String]](None)
            // the model may be missing from the price table and still get a cost, when the provider reports one
            // at the end of the stream - which we cannot know before the stream has run
            if (ext.costsTracking.canHandle(finalProvider, model) || ext.costsTracking.providerReportsCosts(finalProvider)) {
              (resp: Source[ChatResponseChunk, Any]).applyOnIf(addCostsInResp) { src =>
                src.map { r =>
                  r.choices.flatMap(_.finishReason).lastOption.foreach(reason => lastFinishReason.set(reason.some))
                  r.copy(choices = r.choices.map(c => c.copy(finishReason = None)))
                }
              }.alsoTo(Sink.onComplete { _ =>
                val usageSlug: JsObject = attrs.get(otoroshi.plugins.Keys.ExtraAnalyticsDataKey).flatMap(_.select("ai").asOpt[Seq[JsObject]]).flatMap(_.lastOption).flatMap(_.asOpt[JsObject]).getOrElse(Json.obj())
                val inputTokens = usageSlug.select("usage").select("prompt_tokens").asOptLong.getOrElse(-1L)
                val outputTokens = usageSlug.select("usage").select("generation_tokens").asOptLong.getOrElse(-1L)
                val reasoningTokens = usageSlug.select("usage").select("reasoning_tokens").asOptLong.getOrElse(-1L)
                val providerCosts = CostsOutput.fromJson(usageSlug.select("usage").select("provider_costs").asOpt[JsObject].getOrElse(JsObject.empty))
                val costsOpt = resolveCosts(ext, providerCosts, finalProvider, model, inputTokens, outputTokens, reasoningTokens)
                costsOpt.foreach(costs => attrs.put(ChatClientWithCostsTracking.key -> costs))
                if (!addCostsInResp) {
                  promise.trySuccess(None)
                } else {
                  // finish reasons were stripped above, so a terminal chunk must be emitted even when no cost
                  // could be determined, otherwise the caller never sees the stream end
                  promise.trySuccess(ChatResponseChunk(
                    id = s"chatcmpl-${ULID.random().toLowerCase()}",
                    created = (System.currentTimeMillis() / 1000L),
                    model = model,
                    choices = Seq(ChatResponseChunkChoice(
                      index = 0L,
                      delta = ChatResponseChunkChoiceDelta(None),
                      finishReason = lastFinishReason.get().orElse("stop".some),
                    )),
                    costs = costsOpt,
                    budget = attrs.get(ChatClientWithAuding.BudgetConsumptionKey).filter(_ => ext.embedBudgetsInResponses || budgetInRequest),
                  ).some)
                }
              }).concat(Source.lazyFuture(() => promise.future).flatMapConcat(opt => Source(opt.toList))).right
            } else {
              resp.right
            }
          }
        }
      }
    }
  }

  override def invoke(kind: ChatCallKind, prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    checkRequiredCosts(originalBody) match {
      case Left(err) => err.leftf
      case Right(_) => doInvoke(kind, prompt, attrs, originalBody)
    }
  }

  private def doInvoke(kind: ChatCallKind, prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val budgetInRequest = attrs.get(otoroshi.plugins.Keys.RequestKey).flatMap(_.getQueryString("embed_budget")).contains("true")
    getProvider() match {
      case None => chatClient.invoke(kind, prompt, attrs, originalBody) // unsupported provider
      case Some(provider) => {
        chatClient.invoke(kind, prompt, attrs, originalBody).map {
          case Left(err) => Left(err)
          case Right(resp) => {
            val usage = resp.metadata.usage
            val ext = env.adminExtensions.extension[AiExtension].get
            val budget = attrs.get(ChatClientWithAuding.BudgetConsumptionKey).filter(_ => ext.embedBudgetsInResponses || budgetInRequest)
            resolveCosts(
              ext = ext,
              providerCosts = usage.providerCosts,
              provider = originalProvider.metadata.getOrElse("costs-tracking-provider", provider),
              model = originalProvider.metadata.getOrElse("costs-tracking-model", getModel(originalBody)),
              inputTokens = usage.promptTokens,
              outputTokens = usage.generationTokens,
              reasoningTokens = usage.reasoningTokens,
            ) match {
              case None =>
                Right(resp.copy(metadata = resp.metadata.copy(budget = budget)))
              case Some(costs) => {
                attrs.put(ChatClientWithCostsTracking.key -> costs)
                val enableInRequest = attrs.get(otoroshi.plugins.Keys.RequestKey).flatMap(_.getQueryString("embed_costs")).contains("true")
                if (ext.costsTrackingSettings.embedCostsTrackingInResponses || enableInRequest) {
                  Right(resp.copy(metadata = resp.metadata.copy(costs = costs.some, budget = budget)))
                } else {
                  Right(resp.copy(metadata = resp.metadata.copy(budget = budget)))
                }
              }
            }
          }
        }
      }
    }
  }

  override def invokeStream(kind: ChatCallKind, prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, ?]]] = {
    checkRequiredCosts(originalBody) match {
      case Left(err) => err.leftf
      case Right(_) => handleStream(attrs, originalBody) {
        chatClient.invokeStream(kind, prompt, attrs, originalBody)
      }
    }
  }

  // /models must not advertise what the provider would then refuse to serve
  override def listModels(raw: Boolean, attrs: TypedMap)(using ec: ExecutionContext): Future[Either[JsValue, List[String]]] = {
    given env: Env = decoratorEnv
    chatClient.listModels(raw, attrs).map {
      case Left(err) => Left(err)
      case Right(models) => Right(RequiredCosts.filterModels(pricingProvider(), originalProvider.models, models))
    }
  }
}