package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import akka.stream.scaladsl.Source
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatClient, ChatPrompt, ChatResponse, ChatResponseChunk}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import play.api.libs.json.JsValue

import scala.concurrent.{ExecutionContext, Future}

object ChatClientDecorators {

  val possibleDecorators: Seq[Function[(AiProvider, ChatClient, Env), ChatClient]] = Seq(
    ChatClientWithModelConstraints.applyIfPossible,
    ChatClientWithProviderFallback.applyIfPossible,
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