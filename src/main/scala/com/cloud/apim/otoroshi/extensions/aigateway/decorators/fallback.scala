package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import org.apache.pekko.stream.scaladsl.Source
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.providers.{ProviderQuotas, QuotaEpisode}
import com.cloud.apim.otoroshi.extensions.aigateway.{AiMetrics, ChatCallKind, ChatClient, ChatPrompt, ChatResponse, ChatResponseChunk}
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.{JsObject, JsValue, Json}

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

  // throttling ends when the provider said it would; running out of credit needs a human, so we only let a
  // call through now and then to notice the top up. Never unbounded: the provider is always retried.
  private def quotaBackoffUntil(episode: QuotaEpisode, settings: CircuitBreakerSettings, now: Long): Long = {
    episode.retryAt.filter(_ > now).getOrElse {
      if (episode.kind.transient) now + settings.cooldownMs else now + ProviderQuotas.creditExhaustedBackoffMs
    }
  }

  private def fallbackClient()(using env: Env): Option[(AiProvider, ChatClient)] = {
    env.adminExtensions.extension[AiExtension]
      .flatMap(_.states.provider(originalProvider.providerFallback.get))
      .map(_.withModel(originalProvider.providerFallbackModel))
      .flatMap(p => p.getChatClient().map(c => (p, c)))
  }

  // a model asked for the primary provider means nothing to the fallback when it has its own model configured
  private def fallbackBody(originalBody: JsValue): JsValue = originalBody match {
    case obj: JsObject if originalProvider.providerFallbackModel.isDefined => obj - "model"
    case other => other
  }

  // Runs `op` on the primary client, falling back to the configured fallback provider on error
  // (Left or exception). A model the primary refuses is not a failure: the fallback only serves the calls
  // the primary provider would have accepted, with the model restrictions of its consumers. When the per-provider circuit breaker is enabled and the primary's circuit
  // is open, the primary is skipped entirely and we go straight to the fallback (fail fast). Primary
  // outcomes feed the breaker (success closes the circuit, failures eventually open it).
  private def withFallback[T](originalBody: JsValue, attrs: TypedMap)(op: (ChatClient, JsValue) => Future[Either[JsValue, T]])(using ec: ExecutionContext, env: Env): Future[Either[JsValue, T]] = {
    val settings = CircuitBreakerSettings.fromProvider(originalProvider)

    def callFallback(err: JsValue): Future[Either[JsValue, T]] = {
      AiMetrics.markFallback()
      fallbackClient() match {
        case None => err.leftf
        case Some((fallback, client)) =>
          val body = fallbackBody(originalBody)
          val target = ModelTarget.of(originalProvider)
          val requested = ModelConstraints.requestedModel(chatClient, originalBody)
          // the primary may have been skipped (open circuit): its model restrictions still decide
          ModelConstraints.check(target, requested, attrs) {
            ModelConstraints.delegate(attrs, target, requested, fallback, client, body)
            op(client, body)
          }
      }
    }

    // a provider that just refused a call on quota grounds will refuse the next one too, so the circuit is
    // opened at once instead of after a streak of failures - until the moment the provider itself named when
    // it was throttling, or for a bounded while when the account simply has no credit left.
    def recordFailure(): Unit = {
      val now = System.currentTimeMillis()
      ProviderQuotas.episodeForProvider(originalProvider.id).filter(_ => settings.openOnQuota) match {
        case Some(episode) => ProviderCircuitBreaker.openUntil(originalProvider.id, quotaBackoffUntil(episode, settings, now))
        case None => if (settings.enabled) ProviderCircuitBreaker.recordFailure(originalProvider.id, now, settings)
      }
    }

    if (settings.active && ProviderCircuitBreaker.isOpen(originalProvider.id, System.currentTimeMillis())) {
      callFallback(Json.obj("error" -> "primary provider circuit is open"))
    } else {
      op(chatClient, originalBody).flatMap {
        case Left(err) if ModelConstraints.isDenied(err) => err.leftf
        case Left(err) =>
          recordFailure()
          callFallback(err)
        case Right(resp) =>
          if (settings.active) ProviderCircuitBreaker.recordSuccess(originalProvider.id)
          resp.rightf
      }.recoverWith {
        case _: Throwable =>
          recordFailure()
          callFallback(Json.obj("error" -> "fallback provider not found"))
      }
    }
  }

  override def invoke(kind: ChatCallKind, originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    withFallback(originalBody, attrs)((client, body) => client.invoke(kind, originalPrompt, attrs, body))
  }

  override def invokeStream(kind: ChatCallKind, originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, ?]]] = {
    withFallback(originalBody, attrs)((client, body) => client.invokeStream(kind, originalPrompt, attrs, body))
  }
}