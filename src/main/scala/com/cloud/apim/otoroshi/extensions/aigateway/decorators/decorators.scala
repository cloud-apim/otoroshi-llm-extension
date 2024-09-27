package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import com.cloud.apim.otoroshi.extensions.aigateway.ChatClient
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider

object ChatClientDecorators {

  val possibleDecorators: Seq[Function[(AiProvider, ChatClient), ChatClient]] = Seq(
    ChatClientWithAuditing.applyIfPossible,
    ChatClientWithProviderFallback.applyIfPossible,
    ChatClientWithSimpleCache.applyIfPossible,
    ChatClientWithSemanticCache.applyIfPossible,
    ChatClientWithRegexValidation.applyIfPossible,
    ChatClientWithLlmValidation.applyIfPossible,
    ChatClientWithHttpValidation.applyIfPossible,
    ChatClientWithFencesValidation.applyIfPossible
  )

  def apply(provider: AiProvider, client: ChatClient): ChatClient = {
    possibleDecorators.foldLeft(client) {
      case (client, predicate) => predicate((provider, client))
    }
  }
}
