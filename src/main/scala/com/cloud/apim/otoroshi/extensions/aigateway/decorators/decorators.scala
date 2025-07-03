package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.{AudioGenModel, AudioGenVoice, AudioModelClient, AudioModelClientSpeechToTextInputOptions, AudioModelClientTextToSpeechInputOptions, AudioModelClientTranslationInputOptions, AudioTranscriptionResponse, ChatClient, ChatPrompt, ChatResponse, ChatResponseChunk, EmbeddingClientInputOptions, EmbeddingModelClient, EmbeddingResponse}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiProvider, AudioModel, EmbeddingModel}
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
  override def model: Option[String] = chatClient.model
  override def supportsStreaming: Boolean = chatClient.supportsStreaming
  override def supportsTools: Boolean = chatClient.supportsTools
  override def supportsCompletion: Boolean = chatClient.supportsCompletion
  override def listModels(raw: Boolean)(implicit ec: ExecutionContext): Future[Either[JsValue, List[String]]] = chatClient.listModels(raw)
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