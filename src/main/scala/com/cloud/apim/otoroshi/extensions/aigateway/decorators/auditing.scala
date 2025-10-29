package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiProvider, AudioModel, EmbeddingModel, ImageModel, ModerationModel, VideoModel}
import com.cloud.apim.otoroshi.extensions.aigateway.{AudioGenModel, AudioGenVoice, AudioModelClient, AudioModelClientSpeechToTextInputOptions, AudioModelClientTextToSpeechInputOptions, AudioModelClientTranslationInputOptions, AudioTranscriptionResponse, ChatClient, ChatGeneration, ChatPrompt, ChatResponse, ChatResponseChunk, ChatResponseChunkChoice, ChatResponseChunkChoiceDelta, ChatResponseMetadata, ChatResponseMetadataRateLimit, ChatResponseMetadataUsage, EmbeddingClientInputOptions, EmbeddingModelClient, EmbeddingResponse, ImageModelClient, ImageModelClientEditionInputOptions, ImageModelClientGenerationInputOptions, ImagesGenResponse, ImagesGenResponseMetadata, ModerationModelClient, ModerationModelClientInputOptions, ModerationResponse, OutputChatMessage, VideoModelClient, VideoModelClientTextToVideoInputOptions, VideosGenResponse}
import io.azam.ulidj.ULID
import otoroshi.env.Env
import otoroshi.events.AuditEvent
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.{JsArray, JsNull, JsObject, JsValue, Json}

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

class ChatClientWithAuditing(originalProvider: AiProvider, val chatClient: ChatClient) extends DecoratorChatClient {

  override def call(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val user = attrs.get(otoroshi.plugins.Keys.UserKey)
    val apikey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey)
    val route = attrs.get(otoroshi.next.plugins.Keys.RouteKey)
    // val request = attrs.get(otoroshi.plugins.Keys.RequestKey)
    chatClient.call(prompt, attrs, originalBody).andThen {
      case Failure(exception) => {
        AuditEvent.generic("LLMUsageAudit") {
          Json.obj(
            "error" -> Json.obj(
              "exception" -> exception.getMessage
            ),
            "provider_kind" -> originalProvider.provider.toLowerCase,
            "consumed_using" -> "chat/completion/blocking",
            "user" -> user.map(_.json).getOrElse(JsNull).asValue,
            "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
            "route" -> route.map(_.json).getOrElse(JsNull).asValue,
            "input_prompt" -> prompt.json,
            "output" -> JsNull,
            "provider_details" -> originalProvider.json
            //"request" -> request.map(_.json).getOrElse(JsNull).asValue,
          )
        }.toAnalytics()
      }
      case Success(value) => value match {
        case Left(err) => {
          AuditEvent.generic("LLMUsageAudit") {
            Json.obj(
              "error" -> err,
              "provider_kind" -> originalProvider.provider.toLowerCase,
              "consumed_using" -> "chat/completion/blocking",
              "user" -> user.map(_.json).getOrElse(JsNull).asValue,
              "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
              "route" -> route.map(_.json).getOrElse(JsNull).asValue,
              "input_prompt" -> prompt.json,
              "output" -> JsNull,
              "provider_details" -> originalProvider.json
              //"request" -> request.map(_.json).getOrElse(JsNull).asValue,
            )
          }.toAnalytics()
        }
        case Right(value) => {
          val usageSlug: JsObject = attrs.get(otoroshi.plugins.Keys.ExtraAnalyticsDataKey).flatMap(_.select("ai").asOpt[Seq[JsObject]]).flatMap(_.headOption).flatMap(_.asOpt[JsObject]).getOrElse(Json.obj())
          val impacts = attrs.get(ChatClientWithEcoImpact.key)
          val costs = attrs.get(ChatClientWithCostsTracking.key)
          val ext = env.adminExtensions.extension[AiExtension].get
          val provider = usageSlug.select("provider").asOpt[String].flatMap(id => env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(id)))
          AuditEvent.generic("LLMUsageAudit") {
            usageSlug ++ Json.obj(
              "error" -> JsNull,
              "consumed_using" -> "chat/completion/blocking",
              "user" -> user.map(_.json).getOrElse(JsNull).asValue,
              "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
              "route" -> route.map(_.json).getOrElse(JsNull).asValue,
              "input_prompt" -> prompt.json,
              "output" -> value.json(env),
              "provider_details" -> originalProvider.json, //provider.map(_.json).getOrElse(JsNull).asValue,
              "impacts" -> impacts.map(_.json(ext.llmImpactsSettings.embedDescriptionInJson)).getOrElse(JsNull).asValue,
              "costs" -> costs.map(_.json).getOrElse(JsNull).asValue,
              //"request" -> request.map(_.json).getOrElse(JsNull).asValue,
            )
          }.toAnalytics()
        }
      }
    }
  }

  override def stream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    val user = attrs.get(otoroshi.plugins.Keys.UserKey)
    val apikey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey)
    val route = attrs.get(otoroshi.next.plugins.Keys.RouteKey)
    // val request = attrs.get(otoroshi.plugins.Keys.RequestKey)
    chatClient.stream(prompt, attrs, originalBody).transformWith {
      case Failure(exception) => {
        AuditEvent.generic("LLMUsageAudit") {
          Json.obj(
            "error" -> Json.obj(
              "exception" -> exception.getMessage
            ),
            "provider_kind" -> originalProvider.provider.toLowerCase,
            "consumed_using" -> "chat/completion/streaming",
            "user" -> user.map(_.json).getOrElse(JsNull).asValue,
            "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
            "route" -> route.map(_.json).getOrElse(JsNull).asValue,
            "input_prompt" -> prompt.json,
            "output" -> JsNull,
            "provider_details" -> originalProvider.json
            //"request" -> request.map(_.json).getOrElse(JsNull).asValue,
          )
        }.toAnalytics()
        FastFuture.failed(exception)
      }
      case Success(value) => value match {
        case Left(err) => {
          AuditEvent.generic("LLMUsageAudit") {
            Json.obj(
              "error" -> err,
              "provider_kind" -> originalProvider.provider.toLowerCase,
              "consumed_using" -> "chat/completion/streaming",
              "user" -> user.map(_.json).getOrElse(JsNull).asValue,
              "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
              "route" -> route.map(_.json).getOrElse(JsNull).asValue,
              "input_prompt" -> prompt.json,
              "output" -> JsNull,
              "provider_details" -> originalProvider.json
              //"request" -> request.map(_.json).getOrElse(JsNull).asValue,
            )
          }.toAnalytics()
          FastFuture.successful(Left(err))
        }
        case Right(value) => {
          var seq = Seq.empty[ChatResponseChunk]
          val source = value
            .alsoTo(Sink.foreach { chunk =>
              seq = seq :+ chunk
            })
            .alsoTo(Sink.onComplete { _ =>
              val usageSlug: JsObject = attrs.get(otoroshi.plugins.Keys.ExtraAnalyticsDataKey).flatMap(_.select("ai").asOpt[Seq[JsObject]]).flatMap(_.headOption).flatMap(_.asOpt[JsObject]).getOrElse(Json.obj())
              val impacts = attrs.get(ChatClientWithEcoImpact.key)
              val costs = attrs.get(ChatClientWithCostsTracking.key)
              val ext = env.adminExtensions.extension[AiExtension].get
              val provider = usageSlug.select("provider").asOpt[String].flatMap(id => env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(id)))
              AuditEvent.generic("LLMUsageAudit") {
                usageSlug ++ Json.obj(
                  "error" -> JsNull,
                  "consumed_using" -> "chat/completion/streaming",
                  "user" -> user.map(_.json).getOrElse(JsNull).asValue,
                  "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
                  "route" -> route.map(_.json).getOrElse(JsNull).asValue,
                  "input_prompt" -> prompt.json,
                  "output_stream" -> JsArray(seq.map(_.json(env))),
                  "output" -> ChatResponse(
                    raw = Json.obj(),
                    generations = Seq(ChatGeneration(OutputChatMessage("assistant", seq.flatMap(_.choices.flatMap(_.delta.content)).mkString(""), None, Json.obj()))),
                    metadata = ChatResponseMetadata(
                      rateLimit =  ChatResponseMetadataRateLimit(
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
                  ).json(env),
                  "provider_details" -> originalProvider.json, //provider.map(_.json).getOrElse(JsNull).asValue,
                  "impacts" -> impacts.map(_.json(ext.llmImpactsSettings.embedDescriptionInJson)).getOrElse(JsNull).asValue,
                  "costs" -> costs.map(_.json).getOrElse(JsNull).asValue,
                  //"request" -> request.map(_.json).getOrElse(JsNull).asValue,
                )
              }.toAnalytics()
            })
          FastFuture.successful(Right(source))
        }
      }
    }
  }

  override def completion(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val user = attrs.get(otoroshi.plugins.Keys.UserKey)
    val apikey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey)
    val route = attrs.get(otoroshi.next.plugins.Keys.RouteKey)
    chatClient.completion(prompt, attrs, originalBody).andThen {
      case Failure(exception) => {
        AuditEvent.generic("LLMUsageAudit") {
          Json.obj(
            "error" -> Json.obj(
              "exception" -> exception.getMessage
            ),
            "provider_kind" -> originalProvider.provider.toLowerCase,
            "consumed_using" -> "completion/blocking",
            "user" -> user.map(_.json).getOrElse(JsNull).asValue,
            "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
            "route" -> route.map(_.json).getOrElse(JsNull).asValue,
            "input_prompt" -> prompt.json,
            "output" -> JsNull,
            "provider_details" -> originalProvider.json
            //"request" -> request.map(_.json).getOrElse(JsNull).asValue,
          )
        }.toAnalytics()
      }
      case Success(value) => value match {
        case Left(err) => {
          AuditEvent.generic("LLMUsageAudit") {
            Json.obj(
              "error" -> err,
              "provider_kind" -> originalProvider.provider.toLowerCase,
              "consumed_using" -> "completion/blocking",
              "user" -> user.map(_.json).getOrElse(JsNull).asValue,
              "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
              "route" -> route.map(_.json).getOrElse(JsNull).asValue,
              "input_prompt" -> prompt.json,
              "output" -> JsNull,
              "provider_details" -> originalProvider.json
              //"request" -> request.map(_.json).getOrElse(JsNull).asValue,
            )
          }.toAnalytics()
        }
        case Right(value) => {
          val usageSlug: JsObject = attrs.get(otoroshi.plugins.Keys.ExtraAnalyticsDataKey).flatMap(_.select("ai").asOpt[Seq[JsObject]]).flatMap(_.headOption).flatMap(_.asOpt[JsObject]).getOrElse(Json.obj())
          val impacts = attrs.get(ChatClientWithEcoImpact.key)
          val costs = attrs.get(ChatClientWithCostsTracking.key)
          val ext = env.adminExtensions.extension[AiExtension].get
          val provider = usageSlug.select("provider").asOpt[String].flatMap(id => env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(id)))
          AuditEvent.generic("LLMUsageAudit") {
            usageSlug ++ Json.obj(
              "error" -> JsNull,
              "consumed_using" -> "completion/blocking",
              "user" -> user.map(_.json).getOrElse(JsNull).asValue,
              "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
              "route" -> route.map(_.json).getOrElse(JsNull).asValue,
              "input_prompt" -> prompt.json,
              "output" -> value.json(env),
              "provider_details" -> originalProvider.json, //provider.map(_.json).getOrElse(JsNull).asValue,
              "impacts" -> impacts.map(_.json(ext.llmImpactsSettings.embedDescriptionInJson)).getOrElse(JsNull).asValue,
              "costs" -> costs.map(_.json).getOrElse(JsNull).asValue,
            //"request" -> request.map(_.json).getOrElse(JsNull).asValue,
            )
          }.toAnalytics()
        }
      }
    }
  }

  override def completionStream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    val user = attrs.get(otoroshi.plugins.Keys.UserKey)
    val apikey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey)
    val route = attrs.get(otoroshi.next.plugins.Keys.RouteKey)
    chatClient.completionStream(prompt, attrs, originalBody).andThen {
      case Failure(exception) => {
        AuditEvent.generic("LLMUsageAudit") {
          Json.obj(
            "error" -> Json.obj(
              "exception" -> exception.getMessage
            ),
            "provider_kind" -> originalProvider.provider.toLowerCase,
            "consumed_using" -> "completion/streaming",
            "user" -> user.map(_.json).getOrElse(JsNull).asValue,
            "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
            "route" -> route.map(_.json).getOrElse(JsNull).asValue,
            "input_prompt" -> prompt.json,
            "output" -> JsNull,
            "provider_details" -> originalProvider.json
            //"request" -> request.map(_.json).getOrElse(JsNull).asValue,
          )
        }.toAnalytics()
      }
      case Success(value) => value match {
        case Left(err) => {
          AuditEvent.generic("LLMUsageAudit") {
            Json.obj(
              "error" -> err,
              "provider_kind" -> originalProvider.provider.toLowerCase,
              "consumed_using" -> "completion/streaming",
              "user" -> user.map(_.json).getOrElse(JsNull).asValue,
              "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
              "route" -> route.map(_.json).getOrElse(JsNull).asValue,
              "input_prompt" -> prompt.json,
              "output" -> JsNull,
              "provider_details" -> originalProvider.json
              //"request" -> request.map(_.json).getOrElse(JsNull).asValue,
            )
          }.toAnalytics()
        }
        case Right(value) => {
          var seq = Seq.empty[ChatResponseChunk]
          value
            .alsoTo(Sink.foreach { chunk =>
              seq = seq :+ chunk
            })
            .alsoTo(Sink.onComplete { _ =>
              val usageSlug: JsObject = attrs.get(otoroshi.plugins.Keys.ExtraAnalyticsDataKey).flatMap(_.select("ai").asOpt[Seq[JsObject]]).flatMap(_.headOption).flatMap(_.asOpt[JsObject]).getOrElse(Json.obj())
              val impacts = attrs.get(ChatClientWithEcoImpact.key)
              val costs = attrs.get(ChatClientWithCostsTracking.key)
              val ext = env.adminExtensions.extension[AiExtension].get
              val provider = usageSlug.select("provider").asOpt[String].flatMap(id => env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(id)))
              AuditEvent.generic("LLMUsageAudit") {
                usageSlug ++ Json.obj(
                  "error" -> JsNull,
                  "consumed_using" -> "completion/streaming",
                  "user" -> user.map(_.json).getOrElse(JsNull).asValue,
                  "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
                  "route" -> route.map(_.json).getOrElse(JsNull).asValue,
                  "input_prompt" -> prompt.json,
                  "output" -> JsArray(seq.map(_.json(env))),
                  "provider_details" -> originalProvider.json, //provider.map(_.json).getOrElse(JsNull).asValue,
                  "impacts" -> impacts.map(_.json(ext.llmImpactsSettings.embedDescriptionInJson)).getOrElse(JsNull).asValue,
                  "costs" -> costs.map(_.json).getOrElse(JsNull).asValue,
                  //"request" -> request.map(_.json).getOrElse(JsNull).asValue,
                )
              }.toAnalytics()
            })
        }
      }
    }
  }
}

class ChatClientWithStreamUsage(originalProvider: AiProvider, val chatClient: ChatClient) extends DecoratorChatClient {
  override def call(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = chatClient.call(prompt, attrs, originalBody)
  override def completion(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = chatClient.completion(prompt, attrs, originalBody)
  override def stream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    chatClient.stream(prompt, attrs, originalBody).map {
      case Left(err) => Left(err)
      case Right(resp) => {
        val promise = Promise.apply[Option[ChatResponseChunk]]()
        val ref = new AtomicReference[String](null)
        resp
          .map { chunk =>
            if (ref.get() == null) {
              ref.set(chunk.model)
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
                finishReason = "stop".some,
              )),
            ).some)
          }).concat(Source.lazyFuture(() => promise.future).flatMapConcat(opt => Source(opt.toList))).right
      }
    }
  }

  override def completionStream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    chatClient.completionStream(prompt, attrs, originalBody).map {
      case Left(err) => Left(err)
      case Right(resp) => {
        val promise = Promise.apply[Option[ChatResponseChunk]]()
        val ref = new AtomicReference[String](null)
        resp
          .map { chunk =>
            if (ref.get() == null) {
              ref.set(chunk.model)
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
                finishReason = "stop".some,
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
    embeddingModelClient.embed(opts, rawBody, attrs).andThen {
      case Failure(exception) => {
        AuditEvent.generic("LLMUsageAudit") {
          Json.obj(
            "error" -> Json.obj(
              "exception" -> exception.getMessage
            ),
            "provider_kind" -> originalModel.provider.toLowerCase,
            "consumed_using" -> "embedding_model/embedding",
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
            "user" -> user.map(_.json).getOrElse(JsNull).asValue,
            "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
            "route" -> route.map(_.json).getOrElse(JsNull).asValue,
            "input_body" -> rawBody,
            "output" -> _output,
            "provider_details" -> originalModel.json,
            "impacts" -> impacts.map(_.json(ext.llmImpactsSettings.embedDescriptionInJson)).getOrElse(JsNull).asValue,
            "costs" -> costs.map(_.json).getOrElse(JsNull).asValue,
          )
        }.toAnalytics()
      }
    }
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
    audioModelClient.translate(opts, rawBody, attrs).andThen {
      case Failure(exception) => {
        AuditEvent.generic("LLMUsageAudit") {
          Json.obj(
            "error" -> Json.obj(
              "exception" -> exception.getMessage
            ),
            "provider_kind" -> originalModel.provider.toLowerCase,
            "consumed_using" -> "audio_model/translate",
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
        val _output = resp.toOpenAiJson(env).asObject
        val slug = Json.obj(
          "provider_kind" -> originalModel.provider.toLowerCase,
          "provider" -> originalModel.id,
          "duration" -> (System.currentTimeMillis() - startTime),
        ) ++ _output
        attrs.update(AudioModelClient.ApiUsageKey -> resp.metadata)
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
            "consumed_using" -> "audio_model/translate",
            "user" -> user.map(_.json).getOrElse(JsNull).asValue,
            "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
            "route" -> route.map(_.json).getOrElse(JsNull).asValue,
            "input_body" -> rawBody,
            "output" -> _output,
            "provider_details" -> originalModel.json,
            "impacts" -> impacts.map(_.json(ext.llmImpactsSettings.embedDescriptionInJson)).getOrElse(JsNull).asValue,
            "costs" -> costs.map(_.json).getOrElse(JsNull).asValue,
          )
        }.toAnalytics()
      }
    }
  }

  override def speechToText(opts: AudioModelClientSpeechToTextInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, AudioTranscriptionResponse]] = {
    val startTime = System.currentTimeMillis()
    val user = attrs.get(otoroshi.plugins.Keys.UserKey)
    val apikey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey)
    val route = attrs.get(otoroshi.next.plugins.Keys.RouteKey)
    audioModelClient.speechToText(opts, rawBody, attrs).andThen {
      case Failure(exception) => {
        AuditEvent.generic("LLMUsageAudit") {
          Json.obj(
            "error" -> Json.obj(
              "exception" -> exception.getMessage
            ),
            "provider_kind" -> originalModel.provider.toLowerCase,
            "consumed_using" -> "audio_model/stt",
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
        val _output = resp.toOpenAiJson(env).asObject
        val slug = Json.obj(
          "provider_kind" -> originalModel.provider.toLowerCase,
          "provider" -> originalModel.id,
          "duration" -> (System.currentTimeMillis() - startTime),
        ) ++ _output
        attrs.update(AudioModelClient.ApiUsageKey -> resp.metadata)
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
            "consumed_using" -> "audio_model/stt",
            "user" -> user.map(_.json).getOrElse(JsNull).asValue,
            "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
            "route" -> route.map(_.json).getOrElse(JsNull).asValue,
            "input_body" -> rawBody,
            "output" -> _output,
            "provider_details" -> originalModel.json,
            "impacts" -> impacts.map(_.json(ext.llmImpactsSettings.embedDescriptionInJson)).getOrElse(JsNull).asValue,
            "costs" -> costs.map(_.json).getOrElse(JsNull).asValue,
          )
        }.toAnalytics()
      }
    }
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
    imageModelClient.edit(opts, rawBody, attrs).andThen {
      case Failure(exception) => {
        AuditEvent.generic("LLMUsageAudit") {
          Json.obj(
            "error" -> Json.obj(
              "exception" -> exception.getMessage
            ),
            "provider_kind" -> originalModel.provider.toLowerCase,
            "consumed_using" -> "image_model/edit",
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
        val _output = resp.toOpenAiJson(env).asObject
        val slug = Json.obj(
          "provider_kind" -> originalModel.provider.toLowerCase,
          "provider" -> originalModel.id,
          "duration" -> (System.currentTimeMillis() - startTime),
        ) ++ _output
        attrs.update(ImageModelClient.ApiUsageKey -> resp.metadata)
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
            "consumed_using" -> "image_model/edit",
            "user" -> user.map(_.json).getOrElse(JsNull).asValue,
            "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
            "route" -> route.map(_.json).getOrElse(JsNull).asValue,
            "input_body" -> rawBody,
            "output" -> _output,
            "provider_details" -> originalModel.json,
            "impacts" -> impacts.map(_.json(ext.llmImpactsSettings.embedDescriptionInJson)).getOrElse(JsNull).asValue,
            "costs" -> costs.map(_.json).getOrElse(JsNull).asValue,
          )
        }.toAnalytics()
      }
    }
  }

  override def generate(opts: ImageModelClientGenerationInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ImagesGenResponse]] = {
    val startTime = System.currentTimeMillis()
    val user = attrs.get(otoroshi.plugins.Keys.UserKey)
    val apikey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey)
    val route = attrs.get(otoroshi.next.plugins.Keys.RouteKey)
    imageModelClient.generate(opts, rawBody, attrs).andThen {
      case Failure(exception) => {
        AuditEvent.generic("LLMUsageAudit") {
          Json.obj(
            "error" -> Json.obj(
              "exception" -> exception.getMessage
            ),
            "provider_kind" -> originalModel.provider.toLowerCase,
            "consumed_using" -> "image_model/generate",
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
        val _output = resp.toOpenAiJson(env).asObject
        val slug = Json.obj(
          "provider_kind" -> originalModel.provider.toLowerCase,
          "provider" -> originalModel.id,
          "duration" -> (System.currentTimeMillis() - startTime),
        ) ++ _output
        attrs.update(ImageModelClient.ApiUsageKey -> resp.metadata)
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
            "consumed_using" -> "image_model/generate",
            "user" -> user.map(_.json).getOrElse(JsNull).asValue,
            "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
            "route" -> route.map(_.json).getOrElse(JsNull).asValue,
            "input_body" -> rawBody,
            "output" -> _output,
            "provider_details" -> originalModel.json,
            "impacts" -> impacts.map(_.json(ext.llmImpactsSettings.embedDescriptionInJson)).getOrElse(JsNull).asValue,
            "costs" -> costs.map(_.json).getOrElse(JsNull).asValue,
          )
        }.toAnalytics()
      }
    }
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
    moderationModelClient.moderate(opts, rawBody, attrs).andThen {
      case Failure(exception) => {
        AuditEvent.generic("LLMUsageAudit") {
          Json.obj(
            "error" -> Json.obj(
              "exception" -> exception.getMessage
            ),
            "provider_kind" -> originalModel.provider.toLowerCase,
            "consumed_using" -> "moderation_model/moderate",
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
        val _output = resp.toOpenAiJson(env).asObject
        val slug = Json.obj(
          "provider_kind" -> originalModel.provider.toLowerCase,
          "provider" -> originalModel.id,
          "duration" -> (System.currentTimeMillis() - startTime),
        ) ++ _output
        attrs.update(ModerationModelClient.ApiUsageKey -> resp.metadata)
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
            "consumed_using" -> "moderation_model/moderate",
            "user" -> user.map(_.json).getOrElse(JsNull).asValue,
            "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
            "route" -> route.map(_.json).getOrElse(JsNull).asValue,
            "input_body" -> rawBody,
            "output" -> _output,
            "provider_details" -> originalModel.json,
            "impacts" -> impacts.map(_.json(ext.llmImpactsSettings.embedDescriptionInJson)).getOrElse(JsNull).asValue,
            "costs" -> costs.map(_.json).getOrElse(JsNull).asValue,
          )
        }.toAnalytics()
      }
    }
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
    videoModelClient.generate(opts, rawBody, attrs).andThen {
      case Failure(exception) => {
        AuditEvent.generic("LLMUsageAudit") {
          Json.obj(
            "error" -> Json.obj(
              "exception" -> exception.getMessage
            ),
            "provider_kind" -> originalModel.provider.toLowerCase,
            "consumed_using" -> "video_model/generate",
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
        val _output = resp.toOpenAiJson(env).asObject
        val slug = Json.obj(
          "provider_kind" -> originalModel.provider.toLowerCase,
          "provider" -> originalModel.id,
          "duration" -> (System.currentTimeMillis() - startTime),
        ) ++ _output
        attrs.update(VideoModelClient.ApiUsageKey -> resp.metadata)
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
            "consumed_using" -> "video_model/generate",
            "user" -> user.map(_.json).getOrElse(JsNull).asValue,
            "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
            "route" -> route.map(_.json).getOrElse(JsNull).asValue,
            "input_body" -> rawBody,
            "output" -> _output,
            "provider_details" -> originalModel.json,
            "impacts" -> impacts.map(_.json(ext.llmImpactsSettings.embedDescriptionInJson)).getOrElse(JsNull).asValue,
            "costs" -> costs.map(_.json).getOrElse(JsNull).asValue,
          )
        }.toAnalytics()
      }
    }
  }
}