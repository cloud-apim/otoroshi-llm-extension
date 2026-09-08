package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import com.cloud.apim.otoroshi.extensions.aigateway.AiMetrics
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import otoroshi.env.Env
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.syntax.implicits.*

// Per-provider circuit breaker settings, read from the provider options under `circuit_breaker`.
// Opt-in: disabled unless `circuit_breaker.enabled = true`, so existing providers are unaffected.
//
//   "circuit_breaker": { "enabled": true, "consecutive_failures": 5, "cooldown": 30000 }
//
case class CircuitBreakerSettings(enabled: Boolean, consecutiveFailures: Int, cooldownMs: Long, openOnQuota: Boolean) {
  // a quota refusal alone is enough to skip the provider, even when the failure-streak breaker is off
  def active: Boolean = enabled || openOnQuota
}

object CircuitBreakerSettings {
  // `open_on_quota` defaults to true: unlike a failure streak, a quota refusal is a certainty that the next
  // call will be refused too, so there is nothing to gain from trying. It stays bounded in time, so a
  // provider is never skipped forever.
  val disabled: CircuitBreakerSettings = CircuitBreakerSettings(enabled = false, consecutiveFailures = 5, cooldownMs = 30000L, openOnQuota = true)
  def fromProvider(provider: AiProvider): CircuitBreakerSettings = {
    provider.options.select("circuit_breaker").asOpt[play.api.libs.json.JsObject] match {
      case None => disabled
      case Some(cb) => CircuitBreakerSettings(
        enabled = cb.select("enabled").asOpt[Boolean].getOrElse(false),
        consecutiveFailures = cb.select("consecutive_failures").asOpt[Int].getOrElse(5).max(1),
        cooldownMs = cb.select("cooldown").asOpt[Long].getOrElse(30000L).max(1L),
        openOnQuota = cb.select("open_on_quota").asOpt[Boolean].getOrElse(true),
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

  def recordSuccess(providerId: String)(using env: Env): Unit = states.synchronized {
    val wasOpen = states.get(providerId).exists(_.openUntil > 0L)
    states.remove(providerId)
    if (wasOpen) AiMetrics.markCircuit("close")
  }

  /** opens the circuit right away until `until`, whatever the failure streak: used when the provider told us
    * it will refuse the next calls anyway. Never unbounded, so the provider is always retried eventually. */
  def openUntil(providerId: String, until: Long)(using env: Env): Unit = states.synchronized {
    val wasOpen = states.get(providerId).exists(_.openUntil > System.currentTimeMillis())
    states.update(providerId, State(states.get(providerId).map(_.failures).getOrElse(0) + 1, until))
    if (!wasOpen) AiMetrics.markCircuit("open")
  }

  def recordFailure(providerId: String, now: Long, settings: CircuitBreakerSettings)(using env: Env): Unit = states.synchronized {
    val failures = states.get(providerId).map(_.failures).getOrElse(0) + 1
    val openUntil = if (failures >= settings.consecutiveFailures) now + settings.cooldownMs else 0L
    states.update(providerId, State(failures, openUntil))
    if (openUntil > 0L) AiMetrics.markCircuit("open")
  }

  // visible for tests / observability
  def reset(): Unit = states.synchronized { states.clear() }
  def openProviderIds(now: Long): Set[String] = states.toSeq.collect { case (id, s) if s.openUntil > now => id }.toSet
}
