package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import akka.stream.scaladsl.Source
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatClient, ChatPrompt, ChatResponse, ChatResponseChunk}
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.{JsValue, Json}

import scala.concurrent.{ExecutionContext, Future}

object ChatClientWithProviderFallback {
  def applyIfPossible(tuple: (AiProvider, ChatClient, Env)): ChatClient = {
    if (tuple._1.providerFallback.isDefined) {
      new ChatClientWithProviderFallback(tuple._1, tuple._2)
    } else {
      tuple._2
    }
  }
}

class ChatClientWithProviderFallback(originalProvider: AiProvider, val chatClient: ChatClient) extends DecoratorChatClient {

  private def fallbackClient()(implicit env: Env): Option[ChatClient] = {
    env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(originalProvider.providerFallback.get)).flatMap(_.getChatClient())
  }

  // Runs `op` on the primary client, falling back to the configured fallback provider on error
  // (Left or exception). When the per-provider circuit breaker is enabled and the primary's circuit
  // is open, the primary is skipped entirely and we go straight to the fallback (fail fast). Primary
  // outcomes feed the breaker (success closes the circuit, failures eventually open it).
  private def withFallback[T](op: ChatClient => Future[Either[JsValue, T]])(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, T]] = {
    val settings = CircuitBreakerSettings.fromProvider(originalProvider)

    def callFallback(err: JsValue): Future[Either[JsValue, T]] = fallbackClient() match {
      case None => err.leftf
      case Some(client) => op(client)
    }

    if (settings.enabled && ProviderCircuitBreaker.isOpen(originalProvider.id, System.currentTimeMillis())) {
      callFallback(Json.obj("error" -> "primary provider circuit is open"))
    } else {
      op(chatClient).flatMap {
        case Left(err) =>
          if (settings.enabled) ProviderCircuitBreaker.recordFailure(originalProvider.id, System.currentTimeMillis(), settings)
          callFallback(err)
        case Right(resp) =>
          if (settings.enabled) ProviderCircuitBreaker.recordSuccess(originalProvider.id)
          resp.rightf
      }.recoverWith {
        case _: Throwable =>
          if (settings.enabled) ProviderCircuitBreaker.recordFailure(originalProvider.id, System.currentTimeMillis(), settings)
          callFallback(Json.obj("error" -> "fallback provider not found"))
      }
    }
  }

  override def call(originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    withFallback(_.call(originalPrompt, attrs, originalBody))
  }

  override def stream(originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    withFallback(_.stream(originalPrompt, attrs, originalBody))
  }

  override def completion(originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    withFallback(_.completion(originalPrompt, attrs, originalBody))
  }

  override def completionStream(originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    withFallback(_.completionStream(originalPrompt, attrs, originalBody))
  }
}