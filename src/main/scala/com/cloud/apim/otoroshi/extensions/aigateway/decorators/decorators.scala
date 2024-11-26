package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import com.cloud.apim.otoroshi.extensions.aigateway.ChatClient
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import play.api.libs.json.JsValue

import scala.concurrent.{ExecutionContext, Future}

object ChatClientDecorators {

  val possibleDecorators: Seq[Function[(AiProvider, ChatClient), ChatClient]] = Seq(
    ChatClientWithProviderFallback.applyIfPossible,
    ChatClientWithSemanticCache.applyIfPossible,
    ChatClientWithSimpleCache.applyIfPossible,
    ChatClientWithGuardrailsValidation.applyIfPossible,
    ChatClientWithAuditing.applyIfPossible,
  )

  def apply(provider: AiProvider, client: ChatClient): ChatClient = {
    possibleDecorators.foldLeft(client) {
      case (client, predicate) => predicate((provider, client))
    }
  }
}

trait DecoratorChatClient extends ChatClient {
  def chatClient: ChatClient
  override def model: Option[String] = chatClient.model
  override def supportsStreaming: Boolean = chatClient.supportsStreaming
  override def supportsTools: Boolean = chatClient.supportsTools
  override def listModels()(implicit ec: ExecutionContext): Future[Either[JsValue, List[String]]] = chatClient.listModels()
}