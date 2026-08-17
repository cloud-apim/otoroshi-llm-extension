package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiBudget, AiBudgetConsumptions, AiBudgetUsageKind, AiBudgetsDataStore, AiProvider, AudioModel, EmbeddingModel, ImageModel, ModerationModel, OcrModel, VideoModel}
import com.cloud.apim.otoroshi.extensions.aigateway.{AudioGenModel, AudioGenVoice, AudioModelClient, AudioModelClientSpeechToTextInputOptions, AudioModelClientTextToSpeechInputOptions, AudioModelClientTranslationInputOptions, AudioTranscriptionResponse, ChatCallKind, ChatClient, ChatGeneration, ChatPrompt, ChatResponse, ChatResponseChunk, ChatResponseChunkChoice, ChatResponseChunkChoiceDelta, ChatResponseMetadata, ChatResponseMetadataRateLimit, ChatResponseMetadataUsage, EmbeddingClientInputOptions, EmbeddingModelClient, EmbeddingResponse, ImageModelClient, ImageModelClientEditionInputOptions, ImageModelClientGenerationInputOptions, ImagesGenResponse, ImagesGenResponseMetadata, ModerationModelClient, ModerationModelClientInputOptions, ModerationResponse, OcrModelClient, OcrModelClientInputOptions, OcrModelClientResponse, OutputChatMessage, VideoModelClient, VideoModelClientTextToVideoInputOptions, VideosGenResponse}
import io.azam.ulidj.ULID
import otoroshi.env.Env
import otoroshi.events.AuditEvent
import otoroshi.models.Entity
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.LlmTokensRateLimitingValidatorConfig
import play.api.libs.json.{JsArray, JsNull, JsObject, JsString, JsValue, Json}
import play.api.libs.typedmap.TypedKey

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

object ChatClientWithAuditing {
  def applyIfPossible(tuple: (AiProvider, ChatClient, Env)): ChatClient = {
    new ChatClientWithAuditing(tuple._1, tuple._2)
  }
}

object ChatClientWithStreamUsage {
  def applyIfPossible(tuple: (AiProvider, ChatClient, Env)): ChatClient = {
    new ChatClientWithStreamUsage(tuple._1, tuple._2)
  }
}

object ChatClientWithAuding {
  val ProviderKey = TypedKey[Entity]("cloud-apim.ai-gateway.Provider")
  val ModelKey = TypedKey[String]("cloud-apim.ai-gateway.Model")
  val BudgetConsumptionKey = TypedKey[(AiBudgetConsumptions, AiBudget)]("cloud-apim.ai-gateway.BudgetConsumption")
}

class ChatClientWithAuditing(originalProvider: AiProvider, val chatClient: ChatClient) extends DecoratorChatClient {

  def consumerRateLimit(attrs: TypedMap, usageSlug: JsObject): JsValue = {
    attrs.get(LlmTokensRateLimitingValidatorConfig.LlmTokensRateLimitingValidatorKey).map { rlt =>
      val prompt_tokens = usageSlug.select("usage").select("prompt_tokens").asOpt[Int].getOrElse(0)
      val generation_tokens = usageSlug.select("usage").select("generation_tokens").asOpt[Int].getOrElse(0)
      val reasoning_tokens = usageSlug.select("usage").select("reasoning_tokens").asOpt[Int].getOrElse(0)
      val consumed = prompt_tokens + generation_tokens + reasoning_tokens
      Json.obj(
        "max_tokens" -> rlt.get("X-Llm-Ratelimit-Max-Tokens").map(_.toInt).getOrElse(-1).json,
        "window_millis" -> rlt.get("X-Llm-Ratelimit-Window-Millis").map(_.toInt).getOrElse(-1).json,
        "consumed_tokens" -> (rlt.get("X-Llm-Ratelimit-Consumed-Tokens").map(_.toInt).getOrElse(-1) + consumed).json,
        "remaining_tokens" -> (rlt.get("X-Llm-Ratelimit-Remaining-Tokens").map(_.toInt).getOrElse(-1) - consumed).json,
      )
    }.getOrElse(JsNull).asValue
  }

  // fields shared by every LLMUsageAudit event, whatever the endpoint and the outcome
  private def commonFields(consumedUsing: String, prompt: ChatPrompt, attrs: TypedMap): JsObject = Json.obj(
    "provider_kind" -> originalProvider.provider.toLowerCase,
    "consumed_using" -> consumedUsing,
    "request_id" -> attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).map(JsString.apply).getOrElse(JsNull).asValue,
    "user" -> attrs.get(otoroshi.plugins.Keys.UserKey).map(_.json).getOrElse(JsNull).asValue,
    "apikey" -> attrs.get(otoroshi.plugins.Keys.ApiKeyKey).map(_.json).getOrElse(JsNull).asValue,
    "route" -> attrs.get(otoroshi.next.plugins.Keys.RouteKey).map(_.json).getOrElse(JsNull).asValue,
    "input_prompt" -> prompt.json,
    "provider_details" -> originalProvider.json,
  )

  private def auditError(consumedUsing: String, prompt: ChatPrompt, attrs: TypedMap, error: JsValue)(implicit env: Env): Unit = {
    AuditEvent.generic("LLMUsageAudit") {
      commonFields(consumedUsing, prompt, attrs) ++ Json.obj("error" -> error, "output" -> JsNull)
    }.toAnalytics()
  }

  // usage/costs/impacts are only known once the inner clients have run, hence the attrs lookups
  private def auditSuccess(consumedUsing: String, prompt: ChatPrompt, attrs: TypedMap, output: JsObject)(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    val usageSlug: JsObject = attrs.get(otoroshi.plugins.Keys.ExtraAnalyticsDataKey).flatMap(_.select("ai").asOpt[Seq[JsObject]]).flatMap(_.lastOption).flatMap(_.asOpt[JsObject]).getOrElse(Json.obj())
    val impacts = attrs.get(ChatClientWithEcoImpact.key)
    val costs = attrs.get(ChatClientWithCostsTracking.key)
    val ext = env.adminExtensions.extension[AiExtension].get
    val totalCost = costs.map(_.totalCost)
    val totalTokens = attrs.get(ChatClient.ApiUsageKey).map(_.usage.totalTokens)
    ext.datastores.budgetsDataStore.updateUsage(totalCost, totalTokens, AiBudgetUsageKind.Inference, attrs).map { budgetIds =>
      AuditEvent.generic("LLMUsageAudit") {
        usageSlug ++ commonFields(consumedUsing, prompt, attrs) ++ output ++ Json.obj(
          "error" -> JsNull,
          "impacts" -> impacts.map(_.json(ext.llmImpactsSettings.embedDescriptionInJson)).getOrElse(JsNull).asValue,
          "costs" -> costs.map(_.json).getOrElse(JsNull).asValue,
          "budgets" -> budgetIds.json,
          "consumer_rate_limit" -> consumerRateLimit(attrs, usageSlug),
        )
      }.toAnalytics()
      ()
    }
  }

  // the budget check needs the provider and the model in the attrs
  private def prepare(attrs: TypedMap, originalBody: JsValue): Unit = {
    attrs.put(ChatClientWithAuding.ProviderKey -> originalProvider)
    attrs.put(ChatClientWithAuding.ModelKey -> originalBody.select("model").asOptString.orElse(originalProvider.options.select("model").asOptString).getOrElse("--"))
  }

  override def invoke(kind: ChatCallKind, prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val consumedUsing = kind.consumedUsing(streaming = false)
    prepare(attrs, originalBody)
    AiBudgetsDataStore.handleWithinBudget(attrs)(
      Json.obj("error" -> "budget exceeded").leftf,
      chatClient.invoke(kind, prompt, attrs, originalBody).andThen {
        case Failure(exception) => auditError(consumedUsing, prompt, attrs, Json.obj("exception" -> exception.getMessage))
        case Success(Left(err)) => auditError(consumedUsing, prompt, attrs, err)
        case Success(Right(value)) => auditSuccess(consumedUsing, prompt, attrs, Json.obj("output" -> value.json(env)))
      }
    )
  }

  override def invokeStream(kind: ChatCallKind, prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    val consumedUsing = kind.consumedUsing(streaming = true)
    prepare(attrs, originalBody)
    AiBudgetsDataStore.handleWithinBudget(attrs)(
      Json.obj("error" -> "budget exceeded").leftf,
      chatClient.invokeStream(kind, prompt, attrs, originalBody).transformWith {
        case Failure(exception) =>
          auditError(consumedUsing, prompt, attrs, Json.obj("exception" -> exception.getMessage))
          FastFuture.failed(exception)
        case Success(Left(err)) =>
          auditError(consumedUsing, prompt, attrs, err)
          FastFuture.successful(Left(err))
        case Success(Right(value)) => {
          var seq = Seq.empty[ChatResponseChunk]
          val source = value
            .alsoTo(Sink.foreach { chunk => seq = seq :+ chunk })
            .alsoTo(Sink.onComplete { _ =>
              // the audited output is rebuilt from the chunks: the individual ones for traceability,
              // the aggregated text + usage as the equivalent of a blocking response
              val usageSlug: JsObject = attrs.get(otoroshi.plugins.Keys.ExtraAnalyticsDataKey).flatMap(_.select("ai").asOpt[Seq[JsObject]]).flatMap(_.lastOption).flatMap(_.asOpt[JsObject]).getOrElse(Json.obj())
              val aggregated = ChatResponse(
                raw = Json.obj(),
                generations = Seq(ChatGeneration(OutputChatMessage("assistant", seq.flatMap(_.choices.flatMap(_.delta.content)).mkString(""), None, Json.obj()))),
                metadata = ChatResponseMetadata(
                  rateLimit = ChatResponseMetadataRateLimit(
                    requestsLimit = usageSlug.select("rate_limit").select("requests_limit").asOptLong.getOrElse(-1L),
                    requestsRemaining = usageSlug.select("rate_limit").select("requests_remaining").asOptLong.getOrElse(-1L),
                    tokensLimit = usageSlug.select("rate_limit").select("tokens_limit").asOptLong.getOrElse(-1L),
                    tokensRemaining = usageSlug.select("rate_limit").select("tokens_remaining").asOptLong.getOrElse(-1L),
                  ),
                  usage = ChatResponseMetadataUsage(
                    promptTokens = usageSlug.select("usage").select("prompt_tokens").asOptLong.getOrElse(-1L),
                    generationTokens = usageSlug.select("usage").select("generation_tokens").asOptLong.getOrElse(-1L),
                    reasoningTokens = usageSlug.select("usage").select("reasoning_tokens").asOptLong.getOrElse(-1L),
                  ),
                  cache = None
                )
              )
              auditSuccess(consumedUsing, prompt, attrs, Json.obj(
                "output_stream" -> JsArray(seq.map(_.json(env))),
                "output" -> aggregated.json(env),
              ))
            })
          FastFuture.successful(Right(source))
        }
      }
    )
  }
}

// Appends a synthetic final chunk carrying the usage a provider only reports at the end of a stream,
// so front-ends can report token counts on any streaming endpoint (`/responses` included).
class ChatClientWithStreamUsage(originalProvider: AiProvider, val chatClient: ChatClient) extends DecoratorChatClient {

  override def invokeStream(kind: ChatCallKind, prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    chatClient.invokeStream(kind, prompt, attrs, originalBody).map {
      case Left(err) => Left(err)
      case Right(resp) => {
        val promise = Promise.apply[Option[ChatResponseChunk]]()
        val ref = new AtomicReference[String](null)
        // The final synthetic chunk carries the finish_reason. We strip it from upstream chunks, so we track
        // whether any tool_calls were streamed to report "tool_calls" instead of the default "stop".
        val hadToolCalls = new java.util.concurrent.atomic.AtomicBoolean(false)
        resp
          .map { chunk =>
            if (ref.get() == null) {
              ref.set(chunk.model)
            }
            if (chunk.choices.exists(_.delta.tool_calls.nonEmpty)) {
              hadToolCalls.set(true)
            }
            chunk
          }
          .map(r => r.copy(choices = r.choices.map(c => c.copy(finishReason = None))))
          .alsoTo(Sink.onComplete { _ =>
            promise.trySuccess(ChatResponseChunk(
              id = s"chatcmpl-${ULID.random().toLowerCase()}",
              created = (System.currentTimeMillis() / 1000L),
              model = ref.get(),
              usage = attrs.get(ChatClient.ApiUsageKey).map(_.usage),
              choices = Seq(ChatResponseChunkChoice(
                index = 0L,
                delta = ChatResponseChunkChoiceDelta(None),
                finishReason = (if (hadToolCalls.get()) "tool_calls" else "stop").some,
              )),
            ).some)
          }).concat(Source.lazyFuture(() => promise.future).flatMapConcat(opt => Source(opt.toList))).right
      }
    }
  }
}

object EmbeddingModelClientWithAuditing {
  def applyIfPossible(tuple: (EmbeddingModel, EmbeddingModelClient, Env)): EmbeddingModelClient = {
    new EmbeddingModelClientWithAuditing(tuple._1, tuple._2)
  }
}

class EmbeddingModelClientWithAuditing(originalModel: EmbeddingModel, val embeddingModelClient: EmbeddingModelClient) extends DecoratorEmbeddingModelClient {

  override def embed(opts: EmbeddingClientInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, EmbeddingResponse]] = {
    val startTime = System.currentTimeMillis()
    val user = attrs.get(otoroshi.plugins.Keys.UserKey)
    val apikey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey)
    val route = attrs.get(otoroshi.next.plugins.Keys.RouteKey)
    attrs.put(ChatClientWithAuding.ProviderKey -> originalModel)
    attrs.put(ChatClientWithAuding.ModelKey -> opts.model.getOrElse("--"))
    AiBudgetsDataStore.handleWithinBudget(attrs)(
      Json.obj("error" -> "budget exceeded").leftf,
      embeddingModelClient.embed(opts, rawBody, attrs).andThen {
        case Failure(exception) => {
          AuditEvent.generic("LLMUsageAudit") {
            Json.obj(
              "error" -> Json.obj(
                "exception" -> exception.getMessage
              ),
              "provider_kind" -> originalModel.provider.toLowerCase,
              "consumed_using" -> "embedding_model/embedding",
              "request_id" -> attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).map(JsString.apply).getOrElse(JsNull).asValue,
              "user" -> user.map(_.json).getOrElse(JsNull).asValue,
              "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
              "route" -> route.map(_.json).getOrElse(JsNull).asValue,
              "input_body" -> rawBody,
              "output" -> JsNull,
              "provider_details" -> originalModel.json
            )
          }.toAnalytics()
        }
        case Success(Left(err)) => {
          AuditEvent.generic("LLMUsageAudit") {
            Json.obj(
              "error" -> err,
              "provider_kind" -> originalModel.provider.toLowerCase,
              "consumed_using" -> "embedding_model/embedding",
              "request_id" -> attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).map(JsString.apply).getOrElse(JsNull).asValue,
              "user" -> user.map(_.json).getOrElse(JsNull).asValue,
              "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
              "route" -> route.map(_.json).getOrElse(JsNull).asValue,
              "input_body" -> rawBody,
              "output" -> JsNull,
              "provider_details" -> originalModel.json
            )
          }.toAnalytics()
        }
        case Success(Right(resp)) => {
          val impacts = attrs.get(ChatClientWithEcoImpact.key)
          val costs = attrs.get(ChatClientWithCostsTracking.key)
          val ext = env.adminExtensions.extension[AiExtension].get
          val totalCost = costs.map(_.totalCost)
          val totalTokens = attrs.get(EmbeddingModelClient.ApiUsageKey).map(_.tokenUsage)
          ext.datastores.budgetsDataStore.updateUsage(totalCost, totalTokens, AiBudgetUsageKind.Embedding, attrs).map { budgetIds =>
            val _output = resp.toOpenAiJson("vector").asObject
            val slug = Json.obj(
              "provider_kind" -> originalModel.provider.toLowerCase,
              "provider" -> originalModel.id,
              "duration" -> (System.currentTimeMillis() - startTime),
            ) ++ _output
            attrs.update(EmbeddingModelClient.ApiUsageKey -> resp.metadata)
            attrs.update(otoroshi.plugins.Keys.ExtraAnalyticsDataKey) {
              case Some(obj@JsObject(_)) => {
                val arr = obj.select("ai-embedding").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
                val newArr = arr ++ Seq(slug)
                obj ++ Json.obj("ai-embedding" -> newArr)
              }
              case Some(other) => other
              case None => Json.obj("ai-embedding" -> Seq(slug))
            }
            AuditEvent.generic("LLMUsageAudit") {
              Json.obj(
                "provider_kind" -> originalModel.provider.toLowerCase,
                "provider" -> originalModel.id,
                "duration" -> (System.currentTimeMillis() - startTime),
                "error" -> JsNull,
                "consumed_using" -> "embedding_model/embedding",
                "request_id" -> attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).map(JsString.apply).getOrElse(JsNull).asValue,
                "user" -> user.map(_.json).getOrElse(JsNull).asValue,
                "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
                "route" -> route.map(_.json).getOrElse(JsNull).asValue,
                "input_body" -> rawBody,
                "output" -> _output,
                "provider_details" -> originalModel.json,
                "impacts" -> impacts.map(_.json(ext.llmImpactsSettings.embedDescriptionInJson)).getOrElse(JsNull).asValue,
                "costs" -> costs.map(_.json).getOrElse(JsNull).asValue,
                "budgets" -> budgetIds.json
              )
            }.toAnalytics()
          }
        }
      }
    )
  }
}

object AudioModelClientWithAuditing {
  def applyIfPossible(tuple: (AudioModel, AudioModelClient, Env)): AudioModelClient = {
    new AudioModelClientWithAuditing(tuple._1, tuple._2)
  }
}

class AudioModelClientWithAuditing(originalModel: AudioModel, val audioModelClient: AudioModelClient) extends DecoratorAudioModelClient {

  override def translate(opts: AudioModelClientTranslationInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, AudioTranscriptionResponse]] = {
    val startTime = System.currentTimeMillis()
    val user = attrs.get(otoroshi.plugins.Keys.UserKey)
    val apikey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey)
    val route = attrs.get(otoroshi.next.plugins.Keys.RouteKey)
    attrs.put(ChatClientWithAuding.ProviderKey -> originalModel)
    attrs.put(ChatClientWithAuding.ModelKey -> opts.model.getOrElse("--"))
    AiBudgetsDataStore.handleWithinBudget(attrs)(
      Json.obj("error" -> "budget exceeded").leftf,
      audioModelClient.translate(opts, rawBody, attrs).andThen {
        case Failure(exception) => {
          AuditEvent.generic("LLMUsageAudit") {
            Json.obj(
              "error" -> Json.obj(
                "exception" -> exception.getMessage
              ),
              "provider_kind" -> originalModel.provider.toLowerCase,
              "consumed_using" -> "audio_model/translate",
              "request_id" -> attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).map(JsString.apply).getOrElse(JsNull).asValue,
              "user" -> user.map(_.json).getOrElse(JsNull).asValue,
              "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
              "route" -> route.map(_.json).getOrElse(JsNull).asValue,
              "input_body" -> rawBody,
              "output" -> JsNull,
              "provider_details" -> originalModel.json
            )
          }.toAnalytics()
        }
        case Success(Left(err)) => {
          AuditEvent.generic("LLMUsageAudit") {
            Json.obj(
              "error" -> err,
              "provider_kind" -> originalModel.provider.toLowerCase,
              "consumed_using" -> "audio_model/translate",
              "request_id" -> attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).map(JsString.apply).getOrElse(JsNull).asValue,
              "user" -> user.map(_.json).getOrElse(JsNull).asValue,
              "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
              "route" -> route.map(_.json).getOrElse(JsNull).asValue,
              "input_body" -> rawBody,
              "output" -> JsNull,
              "provider_details" -> originalModel.json
            )
          }.toAnalytics()
        }
        case Success(Right(resp)) => {
          val impacts = attrs.get(ChatClientWithEcoImpact.key)
          val costs = attrs.get(ChatClientWithCostsTracking.key)
          val ext = env.adminExtensions.extension[AiExtension].get
          val totalCost = costs.map(_.totalCost)
          val totalTokens = attrs.get(AudioModelClient.ApiUsageKey).map(_.usage.total)
          ext.datastores.budgetsDataStore.updateUsage(totalCost, totalTokens, AiBudgetUsageKind.Audio, attrs).map { budgetIds =>
            val _output = resp.toOpenAiJson(env).asObject
            val slug = Json.obj(
              "provider_kind" -> originalModel.provider.toLowerCase,
              "provider" -> originalModel.id,
              "duration" -> (System.currentTimeMillis() - startTime),
            ) ++ _output
            attrs.update(AudioModelClient.ApiUsageKey -> resp.metadata)
            attrs.update(otoroshi.plugins.Keys.ExtraAnalyticsDataKey) {
              case Some(obj@JsObject(_)) => {
                val arr = obj.select("ai-audio").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
                val newArr = arr ++ Seq(slug)
                obj ++ Json.obj("ai-audio" -> newArr)
              }
              case Some(other) => other
              case None => Json.obj("ai-audio" -> Seq(slug))
            }
            AuditEvent.generic("LLMUsageAudit") {
              Json.obj(
                "provider_kind" -> originalModel.provider.toLowerCase,
                "provider" -> originalModel.id,
                "duration" -> (System.currentTimeMillis() - startTime),
                "error" -> JsNull,
                "consumed_using" -> "audio_model/translate",
                "request_id" -> attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).map(JsString.apply).getOrElse(JsNull).asValue,
                "user" -> user.map(_.json).getOrElse(JsNull).asValue,
                "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
                "route" -> route.map(_.json).getOrElse(JsNull).asValue,
                "input_body" -> rawBody,
                "output" -> _output,
                "provider_details" -> originalModel.json,
                "impacts" -> impacts.map(_.json(ext.llmImpactsSettings.embedDescriptionInJson)).getOrElse(JsNull).asValue,
                "costs" -> costs.map(_.json).getOrElse(JsNull).asValue,
                "budgets" -> budgetIds.json
              )
            }.toAnalytics()
          }
        }
      }
    )
  }

  override def speechToText(opts: AudioModelClientSpeechToTextInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, AudioTranscriptionResponse]] = {
    val startTime = System.currentTimeMillis()
    val user = attrs.get(otoroshi.plugins.Keys.UserKey)
    val apikey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey)
    val route = attrs.get(otoroshi.next.plugins.Keys.RouteKey)
    attrs.put(ChatClientWithAuding.ProviderKey -> originalModel)
    attrs.put(ChatClientWithAuding.ModelKey -> opts.model.getOrElse("--"))
    AiBudgetsDataStore.handleWithinBudget(attrs)(
      Json.obj("error" -> "budget exceeded").leftf,
      audioModelClient.speechToText(opts, rawBody, attrs).andThen {
        case Failure(exception) => {
          AuditEvent.generic("LLMUsageAudit") {
            Json.obj(
              "error" -> Json.obj(
                "exception" -> exception.getMessage
              ),
              "provider_kind" -> originalModel.provider.toLowerCase,
              "consumed_using" -> "audio_model/stt",
              "request_id" -> attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).map(JsString.apply).getOrElse(JsNull).asValue,
              "user" -> user.map(_.json).getOrElse(JsNull).asValue,
              "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
              "route" -> route.map(_.json).getOrElse(JsNull).asValue,
              "input_body" -> rawBody,
              "output" -> JsNull,
              "provider_details" -> originalModel.json
            )
          }.toAnalytics()
        }
        case Success(Left(err)) => {
          AuditEvent.generic("LLMUsageAudit") {
            Json.obj(
              "error" -> err,
              "provider_kind" -> originalModel.provider.toLowerCase,
              "consumed_using" -> "audio_model/stt",
              "request_id" -> attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).map(JsString.apply).getOrElse(JsNull).asValue,
              "user" -> user.map(_.json).getOrElse(JsNull).asValue,
              "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
              "route" -> route.map(_.json).getOrElse(JsNull).asValue,
              "input_body" -> rawBody,
              "output" -> JsNull,
              "provider_details" -> originalModel.json
            )
          }.toAnalytics()
        }
        case Success(Right(resp)) => {
          val impacts = attrs.get(ChatClientWithEcoImpact.key)
          val costs = attrs.get(ChatClientWithCostsTracking.key)
          val ext = env.adminExtensions.extension[AiExtension].get
          val totalCost = costs.map(_.totalCost)
          val totalTokens = attrs.get(AudioModelClient.ApiUsageKey).map(_.usage.total)
          ext.datastores.budgetsDataStore.updateUsage(totalCost, totalTokens, AiBudgetUsageKind.Audio, attrs).map { budgetIds =>
            val _output = resp.toOpenAiJson(env).asObject
            val slug = Json.obj(
              "provider_kind" -> originalModel.provider.toLowerCase,
              "provider" -> originalModel.id,
              "duration" -> (System.currentTimeMillis() - startTime),
            ) ++ _output
            attrs.update(AudioModelClient.ApiUsageKey -> resp.metadata)
            attrs.update(otoroshi.plugins.Keys.ExtraAnalyticsDataKey) {
              case Some(obj@JsObject(_)) => {
                val arr = obj.select("ai-audio").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
                val newArr = arr ++ Seq(slug)
                obj ++ Json.obj("ai-audio" -> newArr)
              }
              case Some(other) => other
              case None => Json.obj("ai-audio" -> Seq(slug))
            }
            AuditEvent.generic("LLMUsageAudit") {
              Json.obj(
                "provider_kind" -> originalModel.provider.toLowerCase,
                "provider" -> originalModel.id,
                "duration" -> (System.currentTimeMillis() - startTime),
                "error" -> JsNull,
                "consumed_using" -> "audio_model/stt",
                "request_id" -> attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).map(JsString.apply).getOrElse(JsNull).asValue,
                "user" -> user.map(_.json).getOrElse(JsNull).asValue,
                "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
                "route" -> route.map(_.json).getOrElse(JsNull).asValue,
                "input_body" -> rawBody,
                "output" -> _output,
                "provider_details" -> originalModel.json,
                "impacts" -> impacts.map(_.json(ext.llmImpactsSettings.embedDescriptionInJson)).getOrElse(JsNull).asValue,
                "costs" -> costs.map(_.json).getOrElse(JsNull).asValue,
                "budgets" -> budgetIds.json
              )
            }.toAnalytics()
          }
        }
      }
    )
  }

  // no metrics right now !!!
  override def textToSpeech(options: AudioModelClientTextToSpeechInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, (Source[ByteString, _], String)]] = super.textToSpeech(options, rawBody, attrs)
}

object ImageModelClientWithAuditing {
  def applyIfPossible(tuple: (ImageModel, ImageModelClient, Env)): ImageModelClient = {
    new ImageModelClientWithAuditing(tuple._1, tuple._2)
  }
}

class ImageModelClientWithAuditing(originalModel: ImageModel, val imageModelClient: ImageModelClient) extends DecoratorImageModelClient {

  override def edit(opts: ImageModelClientEditionInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ImagesGenResponse]] = {
    val startTime = System.currentTimeMillis()
    val user = attrs.get(otoroshi.plugins.Keys.UserKey)
    val apikey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey)
    val route = attrs.get(otoroshi.next.plugins.Keys.RouteKey)
    attrs.put(ChatClientWithAuding.ProviderKey -> originalModel)
    attrs.put(ChatClientWithAuding.ModelKey -> opts.model.getOrElse("--"))
    AiBudgetsDataStore.handleWithinBudget(attrs)(
      Json.obj("error" -> "budget exceeded").leftf,
      imageModelClient.edit(opts, rawBody, attrs).andThen {
        case Failure(exception) => {
          AuditEvent.generic("LLMUsageAudit") {
            Json.obj(
              "error" -> Json.obj(
                "exception" -> exception.getMessage
              ),
              "provider_kind" -> originalModel.provider.toLowerCase,
              "consumed_using" -> "image_model/edit",
              "request_id" -> attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).map(JsString.apply).getOrElse(JsNull).asValue,
              "user" -> user.map(_.json).getOrElse(JsNull).asValue,
              "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
              "route" -> route.map(_.json).getOrElse(JsNull).asValue,
              "input_body" -> rawBody,
              "output" -> JsNull,
              "provider_details" -> originalModel.json
            )
          }.toAnalytics()
        }
        case Success(Left(err)) => {
          AuditEvent.generic("LLMUsageAudit") {
            Json.obj(
              "error" -> err,
              "provider_kind" -> originalModel.provider.toLowerCase,
              "consumed_using" -> "image_model/edit",
              "request_id" -> attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).map(JsString.apply).getOrElse(JsNull).asValue,
              "user" -> user.map(_.json).getOrElse(JsNull).asValue,
              "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
              "route" -> route.map(_.json).getOrElse(JsNull).asValue,
              "input_body" -> rawBody,
              "output" -> JsNull,
              "provider_details" -> originalModel.json
            )
          }.toAnalytics()
        }
        case Success(Right(resp)) => {
          val impacts = attrs.get(ChatClientWithEcoImpact.key)
          val costs = attrs.get(ChatClientWithCostsTracking.key)
          val ext = env.adminExtensions.extension[AiExtension].get
          val totalCost = costs.map(_.totalCost)
          val totalTokens = attrs.get(ImageModelClient.ApiUsageKey).map(_.usage.totalTokens)
          ext.datastores.budgetsDataStore.updateUsage(totalCost, totalTokens, AiBudgetUsageKind.Image, attrs).map { budgetIds =>
            val _output = resp.toOpenAiJson(env).asObject
            val slug = Json.obj(
              "provider_kind" -> originalModel.provider.toLowerCase,
              "provider" -> originalModel.id,
              "duration" -> (System.currentTimeMillis() - startTime),
            ) ++ _output
            attrs.update(ImageModelClient.ApiUsageKey -> resp.metadata)
            attrs.update(otoroshi.plugins.Keys.ExtraAnalyticsDataKey) {
              case Some(obj@JsObject(_)) => {
                val arr = obj.select("ai-image").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
                val newArr = arr ++ Seq(slug)
                obj ++ Json.obj("ai-image" -> newArr)
              }
              case Some(other) => other
              case None => Json.obj("ai-image" -> Seq(slug))
            }
            AuditEvent.generic("LLMUsageAudit") {
              Json.obj(
                "provider_kind" -> originalModel.provider.toLowerCase,
                "provider" -> originalModel.id,
                "duration" -> (System.currentTimeMillis() - startTime),
                "error" -> JsNull,
                "consumed_using" -> "image_model/edit",
                "request_id" -> attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).map(JsString.apply).getOrElse(JsNull).asValue,
                "user" -> user.map(_.json).getOrElse(JsNull).asValue,
                "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
                "route" -> route.map(_.json).getOrElse(JsNull).asValue,
                "input_body" -> rawBody,
                "output" -> _output,
                "provider_details" -> originalModel.json,
                "impacts" -> impacts.map(_.json(ext.llmImpactsSettings.embedDescriptionInJson)).getOrElse(JsNull).asValue,
                "costs" -> costs.map(_.json).getOrElse(JsNull).asValue,
                "budgets" -> budgetIds.json
              )
            }.toAnalytics()
          }
        }
      }
    )
  }

  override def generate(opts: ImageModelClientGenerationInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ImagesGenResponse]] = {
    val startTime = System.currentTimeMillis()
    val user = attrs.get(otoroshi.plugins.Keys.UserKey)
    val apikey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey)
    val route = attrs.get(otoroshi.next.plugins.Keys.RouteKey)
    attrs.put(ChatClientWithAuding.ProviderKey -> originalModel)
    attrs.put(ChatClientWithAuding.ModelKey -> opts.model.getOrElse("--"))
    AiBudgetsDataStore.handleWithinBudget(attrs)(
      Json.obj("error" -> "budget exceeded").leftf,
      imageModelClient.generate(opts, rawBody, attrs).andThen {
        case Failure(exception) => {
          AuditEvent.generic("LLMUsageAudit") {
            Json.obj(
              "error" -> Json.obj(
                "exception" -> exception.getMessage
              ),
              "provider_kind" -> originalModel.provider.toLowerCase,
              "consumed_using" -> "image_model/generate",
              "request_id" -> attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).map(JsString.apply).getOrElse(JsNull).asValue,
              "user" -> user.map(_.json).getOrElse(JsNull).asValue,
              "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
              "route" -> route.map(_.json).getOrElse(JsNull).asValue,
              "input_body" -> rawBody,
              "output" -> JsNull,
              "provider_details" -> originalModel.json
            )
          }.toAnalytics()
        }
        case Success(Left(err)) => {
          AuditEvent.generic("LLMUsageAudit") {
            Json.obj(
              "error" -> err,
              "provider_kind" -> originalModel.provider.toLowerCase,
              "consumed_using" -> "image_model/generate",
              "request_id" -> attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).map(JsString.apply).getOrElse(JsNull).asValue,
              "user" -> user.map(_.json).getOrElse(JsNull).asValue,
              "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
              "route" -> route.map(_.json).getOrElse(JsNull).asValue,
              "input_body" -> rawBody,
              "output" -> JsNull,
              "provider_details" -> originalModel.json
            )
          }.toAnalytics()
        }
        case Success(Right(resp)) => {
          val impacts = attrs.get(ChatClientWithEcoImpact.key)
          val costs = attrs.get(ChatClientWithCostsTracking.key)
          val ext = env.adminExtensions.extension[AiExtension].get
          val totalCost = costs.map(_.totalCost)
          val totalTokens = attrs.get(ImageModelClient.ApiUsageKey).map(_.usage.totalTokens)
          ext.datastores.budgetsDataStore.updateUsage(totalCost, totalTokens, AiBudgetUsageKind.Image, attrs).map { budgetIds =>
            val _output = resp.toOpenAiJson(env).asObject
            val slug = Json.obj(
              "provider_kind" -> originalModel.provider.toLowerCase,
              "provider" -> originalModel.id,
              "duration" -> (System.currentTimeMillis() - startTime),
            ) ++ _output
            attrs.update(ImageModelClient.ApiUsageKey -> resp.metadata)
            attrs.update(otoroshi.plugins.Keys.ExtraAnalyticsDataKey) {
              case Some(obj@JsObject(_)) => {
                val arr = obj.select("ai-image").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
                val newArr = arr ++ Seq(slug)
                obj ++ Json.obj("ai-image" -> newArr)
              }
              case Some(other) => other
              case None => Json.obj("ai-image" -> Seq(slug))
            }
            AuditEvent.generic("LLMUsageAudit") {
              Json.obj(
                "provider_kind" -> originalModel.provider.toLowerCase,
                "provider" -> originalModel.id,
                "duration" -> (System.currentTimeMillis() - startTime),
                "error" -> JsNull,
                "consumed_using" -> "image_model/generate",
                "request_id" -> attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).map(JsString.apply).getOrElse(JsNull).asValue,
                "user" -> user.map(_.json).getOrElse(JsNull).asValue,
                "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
                "route" -> route.map(_.json).getOrElse(JsNull).asValue,
                "input_body" -> rawBody,
                "output" -> _output,
                "provider_details" -> originalModel.json,
                "impacts" -> impacts.map(_.json(ext.llmImpactsSettings.embedDescriptionInJson)).getOrElse(JsNull).asValue,
                "costs" -> costs.map(_.json).getOrElse(JsNull).asValue,
                "budgets" -> budgetIds.json
              )
            }.toAnalytics()
          }
        }
      }
    )
  }
}

object ModerationModelClientWithAuditing {
  def applyIfPossible(tuple: (ModerationModel, ModerationModelClient, Env)): ModerationModelClient = {
    new ModerationModelClientWithAuditing(tuple._1, tuple._2)
  }
}

class ModerationModelClientWithAuditing(originalModel: ModerationModel, val moderationModelClient: ModerationModelClient) extends DecoratorModerationModelClient {

  override def moderate(opts: ModerationModelClientInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ModerationResponse]] = {
    val startTime = System.currentTimeMillis()
    val user = attrs.get(otoroshi.plugins.Keys.UserKey)
    val apikey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey)
    val route = attrs.get(otoroshi.next.plugins.Keys.RouteKey)
    attrs.put(ChatClientWithAuding.ProviderKey -> originalModel)
    attrs.put(ChatClientWithAuding.ModelKey -> opts.model.getOrElse("--"))
    AiBudgetsDataStore.handleWithinBudget(attrs)(
      Json.obj("error" -> "budget exceeded").leftf,
      moderationModelClient.moderate(opts, rawBody, attrs).andThen {
        case Failure(exception) => {
          AuditEvent.generic("LLMUsageAudit") {
            Json.obj(
              "error" -> Json.obj(
                "exception" -> exception.getMessage
              ),
              "provider_kind" -> originalModel.provider.toLowerCase,
              "consumed_using" -> "moderation_model/moderate",
              "request_id" -> attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).map(JsString.apply).getOrElse(JsNull).asValue,
              "user" -> user.map(_.json).getOrElse(JsNull).asValue,
              "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
              "route" -> route.map(_.json).getOrElse(JsNull).asValue,
              "input_body" -> rawBody,
              "output" -> JsNull,
              "provider_details" -> originalModel.json
            )
          }.toAnalytics()
        }
        case Success(Left(err)) => {
          AuditEvent.generic("LLMUsageAudit") {
            Json.obj(
              "error" -> err,
              "provider_kind" -> originalModel.provider.toLowerCase,
              "consumed_using" -> "moderation_model/moderate",
              "request_id" -> attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).map(JsString.apply).getOrElse(JsNull).asValue,
              "user" -> user.map(_.json).getOrElse(JsNull).asValue,
              "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
              "route" -> route.map(_.json).getOrElse(JsNull).asValue,
              "input_body" -> rawBody,
              "output" -> JsNull,
              "provider_details" -> originalModel.json
            )
          }.toAnalytics()
        }
        case Success(Right(resp)) => {
          val impacts = attrs.get(ChatClientWithEcoImpact.key)
          val costs = attrs.get(ChatClientWithCostsTracking.key)
          val ext = env.adminExtensions.extension[AiExtension].get
          val totalCost = costs.map(_.totalCost)
          val totalTokens = attrs.get(ModerationModelClient.ApiUsageKey).map(_.usage.total)
          ext.datastores.budgetsDataStore.updateUsage(totalCost, totalTokens, AiBudgetUsageKind.Moderation, attrs).map { budgetIds =>
            val _output = resp.toOpenAiJson(env).asObject
            val slug = Json.obj(
              "provider_kind" -> originalModel.provider.toLowerCase,
              "provider" -> originalModel.id,
              "duration" -> (System.currentTimeMillis() - startTime),
            ) ++ _output
            attrs.update(ModerationModelClient.ApiUsageKey -> resp.metadata)
            attrs.update(otoroshi.plugins.Keys.ExtraAnalyticsDataKey) {
              case Some(obj@JsObject(_)) => {
                val arr = obj.select("ai-moderation").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
                val newArr = arr ++ Seq(slug)
                obj ++ Json.obj("ai-moderation" -> newArr)
              }
              case Some(other) => other
              case None => Json.obj("ai-moderation" -> Seq(slug))
            }
            AuditEvent.generic("LLMUsageAudit") {
              Json.obj(
                "provider_kind" -> originalModel.provider.toLowerCase,
                "provider" -> originalModel.id,
                "duration" -> (System.currentTimeMillis() - startTime),
                "error" -> JsNull,
                "consumed_using" -> "moderation_model/moderate",
                "request_id" -> attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).map(JsString.apply).getOrElse(JsNull).asValue,
                "user" -> user.map(_.json).getOrElse(JsNull).asValue,
                "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
                "route" -> route.map(_.json).getOrElse(JsNull).asValue,
                "input_body" -> rawBody,
                "output" -> _output,
                "provider_details" -> originalModel.json,
                "impacts" -> impacts.map(_.json(ext.llmImpactsSettings.embedDescriptionInJson)).getOrElse(JsNull).asValue,
                "costs" -> costs.map(_.json).getOrElse(JsNull).asValue,
                "budgets" -> budgetIds.json
              )
            }.toAnalytics()
          }
        }
      }
    )
  }
}

object VideoModelClientWithAuditing {
  def applyIfPossible(tuple: (VideoModel, VideoModelClient, Env)): VideoModelClient = {
    new VideoModelClientWithAuditing(tuple._1, tuple._2)
  }
}

class VideoModelClientWithAuditing(originalModel: VideoModel, val videoModelClient: VideoModelClient) extends DecoratorVideoModelClient {

  override def generate(opts: VideoModelClientTextToVideoInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, VideosGenResponse]] = {
    val startTime = System.currentTimeMillis()
    val user = attrs.get(otoroshi.plugins.Keys.UserKey)
    val apikey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey)
    val route = attrs.get(otoroshi.next.plugins.Keys.RouteKey)
    attrs.put(ChatClientWithAuding.ProviderKey -> originalModel)
    attrs.put(ChatClientWithAuding.ModelKey -> opts.model.getOrElse("--"))
    AiBudgetsDataStore.handleWithinBudget(attrs)(
      Json.obj("error" -> "budget exceeded").leftf,
      videoModelClient.generate(opts, rawBody, attrs).andThen {
        case Failure(exception) => {
          AuditEvent.generic("LLMUsageAudit") {
            Json.obj(
              "error" -> Json.obj(
                "exception" -> exception.getMessage
              ),
              "provider_kind" -> originalModel.provider.toLowerCase,
              "consumed_using" -> "video_model/generate",
              "request_id" -> attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).map(JsString.apply).getOrElse(JsNull).asValue,
              "user" -> user.map(_.json).getOrElse(JsNull).asValue,
              "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
              "route" -> route.map(_.json).getOrElse(JsNull).asValue,
              "input_body" -> rawBody,
              "output" -> JsNull,
              "provider_details" -> originalModel.json
            )
          }.toAnalytics()
        }
        case Success(Left(err)) => {
          AuditEvent.generic("LLMUsageAudit") {
            Json.obj(
              "error" -> err,
              "provider_kind" -> originalModel.provider.toLowerCase,
              "consumed_using" -> "video_model/generate",
              "request_id" -> attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).map(JsString.apply).getOrElse(JsNull).asValue,
              "user" -> user.map(_.json).getOrElse(JsNull).asValue,
              "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
              "route" -> route.map(_.json).getOrElse(JsNull).asValue,
              "input_body" -> rawBody,
              "output" -> JsNull,
              "provider_details" -> originalModel.json
            )
          }.toAnalytics()
        }
        case Success(Right(resp)) => {
          val impacts = attrs.get(ChatClientWithEcoImpact.key)
          val costs = attrs.get(ChatClientWithCostsTracking.key)
          val ext = env.adminExtensions.extension[AiExtension].get
          val totalCost = costs.map(_.totalCost)
          val totalTokens = attrs.get(VideoModelClient.ApiUsageKey).map(_.usage.totalTokens)
          ext.datastores.budgetsDataStore.updateUsage(totalCost, totalTokens, AiBudgetUsageKind.Video, attrs).map { budgetIds =>
            val _output = resp.toOpenAiJson(env).asObject
            val slug = Json.obj(
              "provider_kind" -> originalModel.provider.toLowerCase,
              "provider" -> originalModel.id,
              "duration" -> (System.currentTimeMillis() - startTime),
            ) ++ _output
            attrs.update(VideoModelClient.ApiUsageKey -> resp.metadata)
            attrs.update(otoroshi.plugins.Keys.ExtraAnalyticsDataKey) {
              case Some(obj@JsObject(_)) => {
                val arr = obj.select("ai-video").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
                val newArr = arr ++ Seq(slug)
                obj ++ Json.obj("ai-video" -> newArr)
              }
              case Some(other) => other
              case None => Json.obj("ai-video" -> Seq(slug))
            }
            AuditEvent.generic("LLMUsageAudit") {
              Json.obj(
                "provider_kind" -> originalModel.provider.toLowerCase,
                "provider" -> originalModel.id,
                "duration" -> (System.currentTimeMillis() - startTime),
                "error" -> JsNull,
                "consumed_using" -> "video_model/generate",
                "request_id" -> attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).map(JsString.apply).getOrElse(JsNull).asValue,
                "user" -> user.map(_.json).getOrElse(JsNull).asValue,
                "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
                "route" -> route.map(_.json).getOrElse(JsNull).asValue,
                "input_body" -> rawBody,
                "output" -> _output,
                "provider_details" -> originalModel.json,
                "impacts" -> impacts.map(_.json(ext.llmImpactsSettings.embedDescriptionInJson)).getOrElse(JsNull).asValue,
                "costs" -> costs.map(_.json).getOrElse(JsNull).asValue,
                "budgets" -> budgetIds.json
              )
            }.toAnalytics()
          }
        }
      }
    )
  }
}

object OcrModelClientWithAuditing {
  def applyIfPossible(tuple: (OcrModel, OcrModelClient, Env)): OcrModelClient = {
    new OcrModelClientWithAuditing(tuple._1, tuple._2)
  }
}

class OcrModelClientWithAuditing(originalModel: OcrModel, val ocrModelClient: OcrModelClient) extends DecoratorOcrModelClient {

  override def ocr(opts: OcrModelClientInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, OcrModelClientResponse]] = {
    val startTime = System.currentTimeMillis()
    val user = attrs.get(otoroshi.plugins.Keys.UserKey)
    val apikey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey)
    val route = attrs.get(otoroshi.next.plugins.Keys.RouteKey)
    attrs.put(ChatClientWithAuding.ProviderKey -> originalModel)
    attrs.put(ChatClientWithAuding.ModelKey -> opts.model.getOrElse("--"))
    AiBudgetsDataStore.handleWithinBudget(attrs)(
      Json.obj("error" -> "budget exceeded").leftf,
      ocrModelClient.ocr(opts, rawBody, attrs).andThen {
        case Failure(exception) => {
          AuditEvent.generic("LLMUsageAudit") {
            Json.obj(
              "error" -> Json.obj(
                "exception" -> exception.getMessage
              ),
              "provider_kind" -> originalModel.provider.toLowerCase,
              "consumed_using" -> "ocr_model/ocr",
              "request_id" -> attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).map(JsString.apply).getOrElse(JsNull).asValue,
              "user" -> user.map(_.json).getOrElse(JsNull).asValue,
              "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
              "route" -> route.map(_.json).getOrElse(JsNull).asValue,
              "input_body" -> rawBody,
              "output" -> JsNull,
              "provider_details" -> originalModel.json
            )
          }.toAnalytics()
        }
        case Success(Left(err)) => {
          AuditEvent.generic("LLMUsageAudit") {
            Json.obj(
              "error" -> err,
              "provider_kind" -> originalModel.provider.toLowerCase,
              "consumed_using" -> "ocr_model/ocr",
              "request_id" -> attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).map(JsString.apply).getOrElse(JsNull).asValue,
              "user" -> user.map(_.json).getOrElse(JsNull).asValue,
              "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
              "route" -> route.map(_.json).getOrElse(JsNull).asValue,
              "input_body" -> rawBody,
              "output" -> JsNull,
              "provider_details" -> originalModel.json
            )
          }.toAnalytics()
        }
        case Success(Right(resp)) => {
          val impacts = attrs.get(ChatClientWithEcoImpact.key)
          val costs = attrs.get(ChatClientWithCostsTracking.key)
          val ext = env.adminExtensions.extension[AiExtension].get
          val totalCost = costs.map(_.totalCost)
          val totalPages: Option[Long] = Some(resp.usage.pagesProcessed.toLong).filter(_ > 0L) // OCR is billed per page, not per token
          ext.datastores.budgetsDataStore.updateUsage(totalCost, totalPages, AiBudgetUsageKind.Ocr, attrs).map { budgetIds =>
            val _output = resp.toJson.asObject
            val slug = Json.obj(
              "provider_kind" -> originalModel.provider.toLowerCase,
              "provider" -> originalModel.id,
              "duration" -> (System.currentTimeMillis() - startTime),
            ) ++ _output
            attrs.update(OcrModelClient.ApiUsageKey -> resp.usage)
            attrs.update(otoroshi.plugins.Keys.ExtraAnalyticsDataKey) {
              case Some(obj@JsObject(_)) => {
                val arr = obj.select("ai-ocr").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
                val newArr = arr ++ Seq(slug)
                obj ++ Json.obj("ai-ocr" -> newArr)
              }
              case Some(other) => other
              case None => Json.obj("ai-ocr" -> Seq(slug))
            }
            AuditEvent.generic("LLMUsageAudit") {
              Json.obj(
                "provider_kind" -> originalModel.provider.toLowerCase,
                "provider" -> originalModel.id,
                "duration" -> (System.currentTimeMillis() - startTime),
                "error" -> JsNull,
                "consumed_using" -> "ocr_model/ocr",
                "request_id" -> attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).map(JsString.apply).getOrElse(JsNull).asValue,
                "user" -> user.map(_.json).getOrElse(JsNull).asValue,
                "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
                "route" -> route.map(_.json).getOrElse(JsNull).asValue,
                "input_body" -> rawBody,
                "output" -> _output,
                "provider_details" -> originalModel.json,
                "impacts" -> impacts.map(_.json(ext.llmImpactsSettings.embedDescriptionInJson)).getOrElse(JsNull).asValue,
                "costs" -> costs.map(_.json).getOrElse(JsNull).asValue,
                "budgets" -> budgetIds.json
              )
            }.toAnalytics()
          }
        }
      }
    )
  }
}
