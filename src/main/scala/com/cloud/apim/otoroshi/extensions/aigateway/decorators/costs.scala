package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import akka.stream.scaladsl.{Sink, Source, StreamConverters}
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatClient, ChatPrompt, ChatResponse, ChatResponseChunk, ChatResponseChunkChoice, ChatResponseChunkChoiceDelta}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import io.azam.ulidj.ULID
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import play.api.Configuration
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.libs.typedmap.TypedKey

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration.DurationInt

case class CostsTrackingSettings(configuration: Configuration) {
  val embedDescriptionInJson = configuration.getOptional[Boolean]("embed-description-in-json").getOrElse(true)
  val embedCostsTrackingInResponses = configuration.getOptional[Boolean]("embed-costs-tracking-in-responses").getOrElse(false)
  val enabled = configuration.getOptional[Boolean]("enabled").getOrElse(true)
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
  lazy val input_cost_per_token_cache_hit = raw.select("input_cost_per_token_cache_hit").asOpt[BigDecimal].getOrElse(BigDecimal(0))
  lazy val cache_read_input_token_cost = raw.select("cache_read_input_token_cost").asOpt[BigDecimal].getOrElse(BigDecimal(0))
  lazy val cache_creation_input_token_cost = raw.select("cache_creation_input_token_cost").asOpt[BigDecimal].getOrElse(BigDecimal(0))
  lazy val input_cost_per_token_batches = raw.select("input_cost_per_token_batches").asOpt[BigDecimal].getOrElse(BigDecimal(0))
  lazy val output_cost_per_token_batches = raw.select("output_cost_per_token_batches").asOpt[BigDecimal].getOrElse(BigDecimal(0))
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
  lazy val supports_response_schema = raw.select("supports_response_schema").asOptBoolean.getOrElse(false)
  lazy val supports_system_messages = raw.select("supports_system_messages").asOptBoolean.getOrElse(false)
  lazy val supports_web_search = raw.select("supports_web_search").asOptBoolean.getOrElse(false)
  lazy val search_context_cost_per_query = raw.select("search_context_cost_per_query").asOpt[JsObject].map { obj =>
    SearchContextCostPerQuery(obj)
  }
}

case class CostsOutput(inputCost: BigDecimal, outputCost: BigDecimal, reasoningCost: BigDecimal) {
  def json: JsValue = Json.obj(
    "input_cost" -> inputCost,
    "output_cost" -> outputCost,
    "reasoning_cost" -> reasoningCost,
    "total_cost" -> (inputCost + outputCost + reasoningCost),
    "currency" -> "dollar"
  )
}

class CostsTracking(settings: CostsTrackingSettings, env: Env) {

  val litllmModels: Map[String, CostModel] = {
    val json = Json.parse(getResourceCode("data/ltllm-prices.json")).asObject
    json.value.filterNot(_._1 == "sample_spec").map {
      case (name, obj) => CostModel(name, obj)
    }.map(c => (s"${c.litellm_provider}-${c.nameWithoutProvider}", c)).toMap
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

  val models: Map[String, CostModel] = litllmModels ++ customModels ++ userProvidedModels

  def getResourceCode(path: String): String = {
    implicit val ec = env.otoroshiExecutionContext
    implicit val mat = env.otoroshiMaterializer
    env.environment.resourceAsStream(path)
      .map(stream => StreamConverters.fromInputStream(() => stream).runFold(ByteString.empty)(_++_).awaitf(10.seconds).utf8String)
      .getOrElse(s"'resource ${path} not found !'")
  }

  def canHandle(provider: String, modelName: String): Boolean = {
    models.contains(s"${provider}-${modelName}")
  }

  def computeCosts(
    provider: String,
    modelName: String,
    inputTokens: Long,
    outputTokens: Long,
    reasoningTokens: Long,
  ): Either[String, CostsOutput] = {
    models.get(s"${provider}-${modelName}") match {
      case None => Left("model not found")
      case Some(model) => {
        // println(s"using cost model ${model.litellm_provider} - ${model.nameWithoutProvider}")
        Right(CostsOutput(
          inputCost = inputTokens * model.input_cost_per_token,
          outputCost = outputTokens * model.output_cost_per_token,
          reasoningCost = reasoningTokens * model.output_cost_per_token,
        ))
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
    if (enabledRef.get().get) {
      new ChatClientWithCostsTracking(tuple._1, tuple._2)
    } else {
      tuple._2
    }
  }
}

class ChatClientWithCostsTracking(originalProvider: AiProvider, val chatClient: ChatClient) extends DecoratorChatClient {

  def getModel(originalBody: JsValue): String = {
    val allowConfigOverride = originalProvider.options.select("allow_config_override").asOptBoolean.getOrElse(true)
    if (allowConfigOverride) originalBody.select("model").asOptString.getOrElse(chatClient.model.get) else chatClient.model.get
  }

  def getProvider(): Option[String] = {
    originalProvider.provider.toLowerCase() match {
      case "openai" => "openai".some
      case "scaleway" => None
      case "deepseek" => "deepseek".some
      case "x-ai" => "xai".some
      case "ovh-ai-endpoints" => None
      case "ovh-ai-endpoints-unified" => None
      case "azure-openai" => "azure".some
      case "azure-ai-foundry" => "azure".some
      case "cloudflare" => "cloudflare".some
      case "gemini" => "gemini".some
      case "huggingface" => None
      case "mistral" => "mistral".some
      case "ollama" => "ollama".some
      case "cohere" => "cohere".some
      case "anthropic" => "anthropic".some
      case "groq" => "groq".some
      case _ => None
    }
  }

  private def handleStream(attrs: TypedMap, originalBody: JsValue)(f: => Future[Either[JsValue, Source[ChatResponseChunk, _]]])(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
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
            val addCostsInResp = ext.costsTrackingSettings.embedCostsTrackingInResponses || enableInRequest
            if (ext.costsTracking.canHandle(finalProvider, model)) {
              resp.applyOnIf(addCostsInResp) { src =>
                src.map(r => r.copy(choices = r.choices.map(c => c.copy(finishReason = None))))
              }.alsoTo(Sink.onComplete { _ =>
                val usageSlug: JsObject = attrs.get(otoroshi.plugins.Keys.ExtraAnalyticsDataKey).flatMap(_.select("ai").asOpt[Seq[JsObject]]).flatMap(_.headOption).flatMap(_.asOpt[JsObject]).getOrElse(Json.obj())
                val inputTokens = usageSlug.select("usage").select("prompt_tokens").asOptLong.getOrElse(-1L)
                val outputTokens = usageSlug.select("usage").select("generation_tokens").asOptLong.getOrElse(-1L)
                val reasoningTokens = usageSlug.select("usage").select("reasoning_tokens").asOptLong.getOrElse(-1L)
                ext.costsTracking.computeCosts(
                  provider = finalProvider,
                  modelName = model,
                  inputTokens = inputTokens,
                  outputTokens = outputTokens,
                  reasoningTokens = reasoningTokens
                ) match {
                  case Left(_) => promise.trySuccess(None)
                  case Right(costs) if !addCostsInResp =>
                    attrs.put(ChatClientWithCostsTracking.key -> costs)
                    promise.trySuccess(None)
                  case Right(costs) =>
                    attrs.put(ChatClientWithCostsTracking.key -> costs)
                    promise.trySuccess(ChatResponseChunk(
                      id = s"chatcmpl-${ULID.random().toLowerCase()}",
                      created = (System.currentTimeMillis() / 1000L),
                      model = model,
                      choices = Seq(ChatResponseChunkChoice(
                        index = 0L,
                        delta = ChatResponseChunkChoiceDelta(None),
                        finishReason = "stop".some,
                      )),
                      costs = costs.some
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

  override def call(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    getProvider() match {
      case None => chatClient.call(prompt, attrs, originalBody) // unsupported provider
      case Some(provider) => {
        chatClient.call(prompt, attrs, originalBody).map {
          case Left(err) => Left(err)
          case Right(resp) => {
            val usage = resp.metadata.usage
            val ext = env.adminExtensions.extension[AiExtension].get
            ext.costsTracking.computeCosts(
              provider = originalProvider.metadata.getOrElse("costs-tracking-provider", provider),
              modelName = originalProvider.metadata.getOrElse("costs-tracking-model", getModel(originalBody)),
              inputTokens = usage.promptTokens,
              outputTokens = usage.generationTokens,
              reasoningTokens = usage.reasoningTokens
            ) match {
              case Left(err) => Right(resp)
              case Right(costs) => {
                attrs.put(ChatClientWithCostsTracking.key -> costs)
                val enableInRequest = attrs.get(otoroshi.plugins.Keys.RequestKey).flatMap(_.getQueryString("embed_costs")).contains("true")
                if (ext.costsTrackingSettings.embedCostsTrackingInResponses || enableInRequest) {
                  Right(resp.copy(metadata = resp.metadata.copy(costs = costs.some)))
                } else {
                  Right(resp)
                }
              }
            }
          }
        }
      }
    }
  }

  override def stream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    handleStream(attrs, originalBody) {
      chatClient.stream(prompt, attrs, originalBody)
    }
  }

  override def completion(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    getProvider() match {
      case None => chatClient.completion(prompt, attrs, originalBody) // unsupported provider
      case Some(provider) => {
        chatClient.completion(prompt, attrs, originalBody).map {
          case Left(err) => Left(err)
          case Right(resp) => {
            val usage = resp.metadata.usage
            val ext = env.adminExtensions.extension[AiExtension].get
            ext.costsTracking.computeCosts(
              provider = originalProvider.metadata.getOrElse("costs-tracking-provider", provider),
              modelName = originalProvider.metadata.getOrElse("costs-tracking-model", getModel(originalBody)),
              inputTokens = usage.promptTokens,
              outputTokens = usage.generationTokens,
              reasoningTokens = usage.reasoningTokens
            ) match {
              case Left(err) => Right(resp)
              case Right(costs) => {
                attrs.put(ChatClientWithCostsTracking.key -> costs)
                val enableInRequest = attrs.get(otoroshi.plugins.Keys.RequestKey).flatMap(_.getQueryString("embed_costs")).contains("true")
                if (ext.costsTrackingSettings.embedCostsTrackingInResponses || enableInRequest) {
                  Right(resp.copy(metadata = resp.metadata.copy(costs = costs.some)))
                } else {
                  Right(resp)
                }
              }
            }
          }
        }
      }
    }
  }

  override def completionStream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    handleStream(attrs, originalBody) {
      chatClient.completionStream(prompt, attrs, originalBody)
    }
  }
}