package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.entities._
import com.cloud.apim.otoroshi.extensions.aigateway._
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import play.api.libs.json.{JsObject, JsValue}

import scala.concurrent.{ExecutionContext, Future}

object ChatClientDecorators {

  val possibleDecorators: Seq[Function[(AiProvider, ChatClient, Env), ChatClient]] = Seq(
    ChatClientWithModelConstraints.applyIfPossible,
    ChatClientWithProviderFallback.applyIfPossible,
    ChatClientWithPersistentMemory.applyIfPossible,
    ChatClientWithSemanticCache.applyIfPossible,
    ChatClientWithSimpleCache.applyIfPossible,
    ChatClientWithGuardrailsValidation.applyIfPossible,
    ChatClientWithCostsTracking.applyIfPossible,
    ChatClientWithEcoImpact.applyIfPossible,
    ChatClientWithAuditing.applyIfPossible,
    ChatClientWithContext.applyIfPossible,
    ChatClientWithStreamUsage.applyIfPossible,
  )

  def apply(provider: AiProvider, client: ChatClient, env: Env): ChatClient = {
    possibleDecorators.foldLeft(client) {
      case (client, predicate) => predicate((provider, client, env))
    }
  }
}

trait DecoratorChatClient extends ChatClient {
  def chatClient: ChatClient
  override def computeModel(payload: JsValue): Option[String] = chatClient.computeModel(payload)
  override def supportsStreaming: Boolean = chatClient.supportsStreaming
  override def supportsTools: Boolean = chatClient.supportsTools
  override def supportsCompletion: Boolean = chatClient.supportsCompletion
  override def listModels(raw: Boolean, attrs: TypedMap)(implicit ec: ExecutionContext): Future[Either[JsValue, List[String]]] = chatClient.listModels(raw, attrs)
  override def completion(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = chatClient.completion(prompt, attrs, originalBody)
  override def completionStream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = chatClient.completionStream(prompt, attrs, originalBody)
}

object EmbeddingModelClientDecorators {

  val possibleDecorators: Seq[Function[(EmbeddingModel, EmbeddingModelClient, Env), EmbeddingModelClient]] = Seq(
    EmbeddingModelClientWithAuditing.applyIfPossible,
  )

  def apply(provider: EmbeddingModel, client: EmbeddingModelClient, env: Env): EmbeddingModelClient = {
    possibleDecorators.foldLeft(client) {
      case (client, predicate) => predicate((provider, client, env))
    }
  }
}

trait DecoratorEmbeddingModelClient extends EmbeddingModelClient {
  def embeddingModelClient: EmbeddingModelClient
  override def embed(opts: EmbeddingClientInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, EmbeddingResponse]] = {
    embeddingModelClient.embed(opts, rawBody, attrs)
  }
}

object AudioModelClientDecorators {
  val possibleDecorators: Seq[Function[(AudioModel, AudioModelClient, Env), AudioModelClient]] = Seq(
    AudioModelClientWithAuditing.applyIfPossible,
  )

  def apply(provider: AudioModel, client: AudioModelClient, env: Env): AudioModelClient = {
    possibleDecorators.foldLeft(client) {
      case (client, predicate) => predicate((provider, client, env))
    }
  }
}

trait DecoratorAudioModelClient extends AudioModelClient {
  def audioModelClient: AudioModelClient
  override def supportsStt: Boolean = audioModelClient.supportsStt
  override def supportsTts: Boolean = audioModelClient.supportsTts
  override def supportsTranslation: Boolean = audioModelClient.supportsTranslation
  override def translate(options: AudioModelClientTranslationInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, AudioTranscriptionResponse]] = audioModelClient.translate(options, rawBody, attrs)
  override def speechToText(options: AudioModelClientSpeechToTextInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, AudioTranscriptionResponse]] = audioModelClient.speechToText(options, rawBody, attrs)
  override def textToSpeech(options: AudioModelClientTextToSpeechInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, (Source[ByteString, _], String)]] = audioModelClient.textToSpeech(options, rawBody, attrs)
  override def listModels(raw: Boolean)(implicit ec: ExecutionContext): Future[Either[JsValue, List[AudioGenModel]]] = audioModelClient.listModels(raw)
  override def listVoices(raw: Boolean)(implicit ec: ExecutionContext): Future[Either[JsValue, List[AudioGenVoice]]] = audioModelClient.listVoices(raw)
}

object ImageModelClientDecorators {
  val possibleDecorators: Seq[Function[(ImageModel, ImageModelClient, Env), ImageModelClient]] = Seq(
    ImageModelClientWithAuditing.applyIfPossible,
  )

  def apply(provider: ImageModel, client: ImageModelClient, env: Env): ImageModelClient = {
    possibleDecorators.foldLeft(client) {
      case (client, predicate) => predicate((provider, client, env))
    }
  }
}

trait DecoratorImageModelClient extends ImageModelClient {
  def imageModelClient: ImageModelClient
  override def supportsEdit: Boolean = imageModelClient.supportsEdit
  override def supportsGeneration: Boolean = imageModelClient.supportsGeneration
  override def generate(opts: ImageModelClientGenerationInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ImagesGenResponse]] = super.generate(opts, rawBody, attrs)
  override def edit(opts: ImageModelClientEditionInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ImagesGenResponse]] = super.edit(opts, rawBody, attrs)
}

trait DecoratorModerationModelClient extends ModerationModelClient {
  def moderationModelClient: ModerationModelClient
  override def moderate(opts: ModerationModelClientInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ModerationResponse]] = moderationModelClient.moderate(opts, rawBody, attrs)
}

object ModerationModelClientDecorators {
  val possibleDecorators: Seq[Function[(ModerationModel, ModerationModelClient, Env), ModerationModelClient]] = Seq(
    ModerationModelClientWithAuditing.applyIfPossible,
  )

  def apply(provider: ModerationModel, client: ModerationModelClient, env: Env): ModerationModelClient = {
    possibleDecorators.foldLeft(client) {
      case (client, predicate) => predicate((provider, client, env))
    }
  }
}

trait DecoratorVideoModelClient extends VideoModelClient {
  def videoModelClient: VideoModelClient
  override def supportsTextToVideo: Boolean = videoModelClient.supportsTextToVideo
  override def generate(opts: VideoModelClientTextToVideoInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, VideosGenResponse]] = videoModelClient.generate(opts, rawBody, attrs)
}

object VideosGenModelClientDecorators {
  val possibleDecorators: Seq[Function[(VideoModel, VideoModelClient, Env), VideoModelClient]] = Seq(
    VideoModelClientWithAuditing.applyIfPossible,
  )

  def apply(provider: VideoModel, client: VideoModelClient, env: Env): VideoModelClient = {
    possibleDecorators.foldLeft(client) {
      case (client, predicate) => predicate((provider, client, env))
    }
  }
}

