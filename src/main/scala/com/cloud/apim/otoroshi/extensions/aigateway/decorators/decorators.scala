package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import com.cloud.apim.otoroshi.extensions.aigateway.ChatClient
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider

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
  override def supportsStreaming: Boolean = chatClient.supportsStreaming
  override def model: Option[String] = chatClient.model
}