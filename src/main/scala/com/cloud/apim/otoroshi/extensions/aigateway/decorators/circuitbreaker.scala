package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.syntax.implicits._

// Per-provider circuit breaker settings, read from the provider options under `circuit_breaker`.
// Opt-in: disabled unless `circuit_breaker.enabled = true`, so existing providers are unaffected.
//
//   "circuit_breaker": { "enabled": true, "consecutive_failures": 5, "cooldown": 30000 }
//
case class CircuitBreakerSettings(enabled: Boolean, consecutiveFailures: Int, cooldownMs: Long)

object CircuitBreakerSettings {
  val disabled: CircuitBreakerSettings = CircuitBreakerSettings(enabled = false, consecutiveFailures = 5, cooldownMs = 30000L)
  def fromProvider(provider: AiProvider): CircuitBreakerSettings = {
    provider.options.select("circuit_breaker").asOpt[play.api.libs.json.JsObject] match {
      case None => disabled
      case Some(cb) => CircuitBreakerSettings(
        enabled = cb.select("enabled").asOpt[Boolean].getOrElse(false),
        consecutiveFailures = cb.select("consecutive_failures").asOpt[Int].getOrElse(5).max(1),
        cooldownMs = cb.select("cooldown").asOpt[Long].getOrElse(30000L).max(1L),
      )
    }
  }
}

// In-memory (per node) circuit breaker keyed by provider id. A run of `consecutiveFailures` failures
// (with no success in between — any success resets the streak) opens the circuit for `cooldownMs`.
// While open, callers skip the provider (fail fast / route elsewhere). Once the cooldown elapses the
// circuit is half-open: the next call is allowed through; a success closes it, a failure re-opens it.
//
// Not cluster-wide (each node keeps its own view) — a Redis-backed shared state is a possible follow-up.
object ProviderCircuitBreaker {

  private case class State(failures: Int, openUntil: Long)

  private val states = new UnboundedTrieMap[String, State]()

  def isOpen(providerId: String, now: Long): Boolean = {
    states.get(providerId).exists(_.openUntil > now)
  }

  def recordSuccess(providerId: String): Unit = states.synchronized {
    states.remove(providerId)
  }

  def recordFailure(providerId: String, now: Long, settings: CircuitBreakerSettings): Unit = states.synchronized {
    val failures = states.get(providerId).map(_.failures).getOrElse(0) + 1
    val openUntil = if (failures >= settings.consecutiveFailures) now + settings.cooldownMs else 0L
    states.update(providerId, State(failures, openUntil))
  }

  // visible for tests / observability
  def reset(): Unit = states.synchronized { states.clear() }
  def openProviderIds(now: Long): Set[String] = states.toSeq.collect { case (id, s) if s.openUntil > now => id }.toSet
}
