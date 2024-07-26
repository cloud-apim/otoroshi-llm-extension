package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import com.cloud.apim.otoroshi.extensions.aigateway.ChatClient
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider

object ChatClientDecorators {

  val possibleDecorators: Seq[Function[(AiProvider, ChatClient), ChatClient]] = Seq(
    ChatClientWithRegexValidation.applyIfPossible,
    ChatClientWithLlmValidation.applyIfPossible,
    ChatClientWithSimpleCache.applyIfPossible,
    ChatClientWithSemanticCache.applyIfPossible,
    ChatClientWithHttpValidation.applyIfPossible,
    ChatClientWithProviderFallback.applyIfPossible
  )

  def apply(provider: AiProvider, client: ChatClient): ChatClient = {
    possibleDecorators.foldLeft(client) {
      case (client, predicate) => predicate((provider, client))
    }
  }
}
