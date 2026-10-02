package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import org.apache.pekko.stream.scaladsl.Source
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiProvider, DecisionModel}
import com.cloud.apim.otoroshi.extensions.aigateway.providers.{ProviderQuotas, QuotaEpisode}
import com.cloud.apim.otoroshi.extensions.aigateway.{AiMetrics, ChatCallKind, ChatClient, ChatPrompt, ChatResponse, ChatResponseChunk, DecisionErrors, DecisionModelClient, DecisionModelClientInputOptions, DecisionResponse}
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.libs.typedmap.TypedKey

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object ChatClientWithProviderFallback {

  private val WaitingKey = TypedKey[java.util.Set[String]]("cloud-apim.ai-gateway.FallbackWaiting")

  def applyIfPossible(tuple: (AiProvider, ChatClient, Env)): ChatClient = {
    if (tuple._1.providerFallback.isDefined) {
      new ChatClientWithProviderFallback(tuple._1, tuple._2)
    } else {
      tuple._2
    }
  }

  // the providers that failed for the call at hand, and are waiting for their fallback to answer it
  private def waiting(attrs: TypedMap): java.util.Set[String] = {
    attrs.putIfAbsent(WaitingKey -> java.util.concurrent.ConcurrentHashMap.newKeySet[String]())
    attrs.get(WaitingKey).get
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

    def handOver(err: JsValue): Future[Either[JsValue, T]] = {
      fallbackClient() match {
        case None => err.leftf
        case Some((fallback, client)) =>
          val body = fallbackBody(originalBody)
          val target = ModelTarget.of(originalProvider)
          val requested = ModelConstraints.requestedModel(chatClient, originalBody)
          // the primary may have been skipped (open circuit): its model restrictions still decide
          ModelConstraints.check(target, requested, attrs) {
            ModelConstraints.delegate(attrs, target, requested, fallback, client, body)
            HandOver.mark(attrs, originalProvider)
            op(client, body)
          }
      }
    }

    def callFallback(err: JsValue): Future[Either[JsValue, T]] = {
      AiMetrics.markFallback()
      val waiting = ChatClientWithProviderFallback.waiting(attrs)
      val fallbackRef = originalProvider.providerFallback.get
      // A provider waiting for its fallback has already failed for this call. Asking it again, or letting it
      // fall back a second time, is how providers falling back on each other would call each other for ever:
      // the error at hand is the answer
      if (fallbackRef == originalProvider.id || waiting.contains(fallbackRef) || !waiting.add(originalProvider.id)) {
        err.leftf
      } else {
        Try(handOver(err)).fold(e => Future.failed(e), identity).andThen { case _ => waiting.remove(originalProvider.id) }
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
object DecisionModelClientWithFallback {
  // no fallback towards a model already tried for this call: two models falling back on each other stop there
  def applyIfPossible(model: DecisionModel, client: DecisionModelClient, visited: Set[String]): DecisionModelClient = {
    model.fallbackRef.filterNot(ref => ref == model.id || visited.contains(ref)) match {
      case Some(ref) => new DecisionModelClientWithFallback(model, client, ref, visited + model.id)
      case None => client
    }
  }
}

/**
 * Another decision model takes over when this one cannot answer for a technical reason: no answer at all
 * (timeout, connection), 408, 429 or a server error. A request the provider refused (4xx), a budget, a model
 * restriction are no reason to ask elsewhere, and neither is an answer the model is not sure of: probabilities
 * are not comparable from a model to another, so a low confidence is an answer like any other.
 */
class DecisionModelClientWithFallback(originalModel: DecisionModel, val decisionModelClient: DecisionModelClient, fallbackRef: String, visited: Set[String]) extends DecoratorDecisionModelClient {

  private def fallbackClient()(using env: Env): Option[(DecisionModel, DecisionModelClient)] = {
    env.adminExtensions.extension[AiExtension]
      .flatMap(_.states.decisionModel(fallbackRef))
      .flatMap(m => m.getDecisionModelClient(visited).map(c => (m, c)))
  }

  override def decide(opts: DecisionModelClientInputOptions, rawBody: JsObject, attrs: TypedMap)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, DecisionResponse]] = {

    def callFallback(otherwise: => Future[Either[JsValue, DecisionResponse]]): Future[Either[JsValue, DecisionResponse]] = {
      fallbackClient() match {
        case None => otherwise
        case Some((fallback, client)) =>
          AiMetrics.markFallback()
          // the model that was asked for is the one of the primary: a model id means nothing from a provider to another
          val model = originalModel.fallbackModel
          val body = (rawBody - "model") ++ model.map(m => Json.obj("model" -> m)).getOrElse(Json.obj())
          // the caller chose the primary model, where the call then goes is the choice of the operator. A
          // fallback that keeps its own model is checked for that one
          val served = if (fallback.allowConfigOverride) model else fallback.defaultModel
          ModelConstraints.delegate(attrs, originalModel.target, opts.model, fallback.target, served)
          client.decide(opts.copy(model = model), body, attrs)
      }
    }

    decisionModelClient.decide(opts, rawBody, attrs).transformWith {
      case Success(Left(err)) if DecisionErrors.retryable(err) => callFallback(err.leftf)
      case Success(result) => result.vfuture
      case Failure(exception) => callFallback(Future.failed(exception))
    }
  }
}
