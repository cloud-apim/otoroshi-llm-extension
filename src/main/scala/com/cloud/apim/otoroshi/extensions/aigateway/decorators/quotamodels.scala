package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import com.cloud.apim.otoroshi.extensions.aigateway.ChatClient
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.providers.ProviderQuotas
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import play.api.libs.json.JsValue

import scala.concurrent.{ExecutionContext, Future}

object ChatClientWithQuotaAwareModels {
  def applyIfPossible(tuple: (AiProvider, ChatClient, Env)): ChatClient = {
    new ChatClientWithQuotaAwareModels(tuple._1, tuple._2, tuple._3)
  }
}

/**
 * Advertising models a provider cannot serve is misleading, so a provider whose account has run out of credit
 * stops listing anything until it is topped up.
 *
 * Only the non transient incidents hide models. Throttling lasts seconds and resolves on its own: hiding a
 * catalog that comes back a minute later would make it flap for every consumer reading it.
 */
class ChatClientWithQuotaAwareModels(originalProvider: AiProvider, val chatClient: ChatClient, decoratorEnv: Env) extends DecoratorChatClient {

  override def listModels(raw: Boolean, attrs: TypedMap)(using ec: ExecutionContext): Future[Either[JsValue, List[String]]] = {
    val unusable = ProviderQuotas.episodeForProvider(originalProvider.id).exists(episode => !episode.kind.transient)
    if (unusable) {
      Future.successful(Right(List.empty))
    } else {
      chatClient.listModels(raw, attrs)
    }
  }
}
