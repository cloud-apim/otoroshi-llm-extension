package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway._
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiProvider, AudioModel, EmbeddingModel, ImageModel, ModerationModel, VideoModel}
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import play.api.libs.json.{JsObject, JsValue}

import scala.concurrent.{ExecutionContext, Future}

// Real-time `ai.*` metrics decorators. Placed just outside the auditing decorator in each chain so they see the
// final result (incl. budget-exceeded short-circuits) and the usage/cost attrs set by inner decorators. All
// emission is gated by env.metricsEnabled inside AiMetrics; these decorators only time + dispatch.

class ChatClientWithMetrics(originalProvider: AiProvider, val chatClient: ChatClient) extends DecoratorChatClient {

  private def markChatTokensAndCost(attrs: TypedMap)(implicit env: Env): Unit = {
    attrs.get(ChatClient.ApiUsageKey).foreach(m => AiMetrics.markTokens(m.usage.promptTokens.toInt, m.usage.generationTokens.toInt, m.usage.reasoningTokens.toInt))
    attrs.get(ChatClientWithCostsTracking.key).foreach(c => AiMetrics.markCost(c.totalCost.toDouble))
  }

  private def meterBlocking(op: String, attrs: TypedMap, f: Future[Either[JsValue, ChatResponse]])(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val start = System.currentTimeMillis()
    AiMetrics.around(op, originalProvider.provider.toLowerCase, start, f) { resp =>
      markChatTokensAndCost(attrs)
      // cache hit/miss comes from the inner cache decorator's metadata; strategy from the provider config
      resp.metadata.cache.foreach(c => AiMetrics.markCache(originalProvider.cache.strategy, c.status == ChatResponseCacheStatus.Hit))
    }
  }

  private def meterStreaming(op: String, attrs: TypedMap, f: Future[Either[JsValue, Source[ChatResponseChunk, _]]])(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    val start = System.currentTimeMillis()
    // token/cost are only known once the stream completes; duration recorded by around is time-to-stream-start
    val wrapped = f.map {
      case Right(src) => Right(src.alsoTo(Sink.onComplete { _ => markChatTokensAndCost(attrs) }))
      case other => other
    }
    AiMetrics.around(op, originalProvider.provider.toLowerCase, start, wrapped) { _ => () }
  }

  override def call(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] =
    meterBlocking("chat.completion.blocking", attrs, chatClient.call(prompt, attrs, originalBody))

  override def completion(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] =
    meterBlocking("completion.blocking", attrs, chatClient.completion(prompt, attrs, originalBody))

  override def stream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] =
    meterStreaming("chat.completion.streaming", attrs, chatClient.stream(prompt, attrs, originalBody))

  override def completionStream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] =
    meterStreaming("completion.streaming", attrs, chatClient.completionStream(prompt, attrs, originalBody))
}

object ChatClientWithMetrics {
  def applyIfPossible(tuple: (AiProvider, ChatClient, Env)): ChatClient = new ChatClientWithMetrics(tuple._1, tuple._2)
}

class EmbeddingModelClientWithMetrics(originalModel: EmbeddingModel, val embeddingModelClient: EmbeddingModelClient) extends DecoratorEmbeddingModelClient {
  override def embed(opts: EmbeddingClientInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, EmbeddingResponse]] = {
    val start = System.currentTimeMillis()
    AiMetrics.around("embedding_model.embedding", originalModel.provider.toLowerCase, start, embeddingModelClient.embed(opts, rawBody, attrs)) { _ =>
      attrs.get(EmbeddingModelClient.ApiUsageKey).foreach(m => AiMetrics.markTokens(m.tokenUsage.toInt, 0, 0))
    }
  }
}

object EmbeddingModelClientWithMetrics {
  def applyIfPossible(tuple: (EmbeddingModel, EmbeddingModelClient, Env)): EmbeddingModelClient = new EmbeddingModelClientWithMetrics(tuple._1, tuple._2)
}

class AudioModelClientWithMetrics(originalModel: AudioModel, val audioModelClient: AudioModelClient) extends DecoratorAudioModelClient {
  private val kind = originalModel.provider.toLowerCase
  override def translate(options: AudioModelClientTranslationInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, AudioTranscriptionResponse]] = {
    val start = System.currentTimeMillis()
    AiMetrics.around("audio_model.translate", kind, start, audioModelClient.translate(options, rawBody, attrs)) { _ => () }
  }
  override def speechToText(options: AudioModelClientSpeechToTextInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, AudioTranscriptionResponse]] = {
    val start = System.currentTimeMillis()
    AiMetrics.around("audio_model.stt", kind, start, audioModelClient.speechToText(options, rawBody, attrs)) { _ => () }
  }
  override def textToSpeech(options: AudioModelClientTextToSpeechInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, (Source[ByteString, _], String)]] = {
    val start = System.currentTimeMillis()
    AiMetrics.around("audio_model.tts", kind, start, audioModelClient.textToSpeech(options, rawBody, attrs)) { _ => () }
  }
}

object AudioModelClientWithMetrics {
  def applyIfPossible(tuple: (AudioModel, AudioModelClient, Env)): AudioModelClient = new AudioModelClientWithMetrics(tuple._1, tuple._2)
}

class ImageModelClientWithMetrics(originalModel: ImageModel, val imageModelClient: ImageModelClient) extends DecoratorImageModelClient {
  private val kind = originalModel.provider.toLowerCase
  override def generate(opts: ImageModelClientGenerationInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ImagesGenResponse]] = {
    val start = System.currentTimeMillis()
    AiMetrics.around("image_model.generate", kind, start, imageModelClient.generate(opts, rawBody, attrs)) { _ => () }
  }
  override def edit(opts: ImageModelClientEditionInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ImagesGenResponse]] = {
    val start = System.currentTimeMillis()
    AiMetrics.around("image_model.edit", kind, start, imageModelClient.edit(opts, rawBody, attrs)) { _ => () }
  }
}

object ImageModelClientWithMetrics {
  def applyIfPossible(tuple: (ImageModel, ImageModelClient, Env)): ImageModelClient = new ImageModelClientWithMetrics(tuple._1, tuple._2)
}

class ModerationModelClientWithMetrics(originalModel: ModerationModel, val moderationModelClient: ModerationModelClient) extends DecoratorModerationModelClient {
  override def moderate(opts: ModerationModelClientInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ModerationResponse]] = {
    val start = System.currentTimeMillis()
    AiMetrics.around("moderation_model.moderate", originalModel.provider.toLowerCase, start, moderationModelClient.moderate(opts, rawBody, attrs)) { _ => () }
  }
}

object ModerationModelClientWithMetrics {
  def applyIfPossible(tuple: (ModerationModel, ModerationModelClient, Env)): ModerationModelClient = new ModerationModelClientWithMetrics(tuple._1, tuple._2)
}

class VideoModelClientWithMetrics(originalModel: VideoModel, val videoModelClient: VideoModelClient) extends DecoratorVideoModelClient {
  override def generate(opts: VideoModelClientTextToVideoInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, VideosGenResponse]] = {
    val start = System.currentTimeMillis()
    AiMetrics.around("video_model.generate", originalModel.provider.toLowerCase, start, videoModelClient.generate(opts, rawBody, attrs)) { _ => () }
  }
}

object VideoModelClientWithMetrics {
  def applyIfPossible(tuple: (VideoModel, VideoModelClient, Env)): VideoModelClient = new VideoModelClientWithMetrics(tuple._1, tuple._2)
}
