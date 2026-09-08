package com.cloud.apim.otoroshi.extensions.aigateway.providers

import org.apache.pekko.http.scaladsl.model.Uri
import otoroshi.env.Env
import otoroshi.events.{AlertEvent, Alerts}
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.{JsValue, Json}
import play.api.libs.ws.WSResponse

import java.util.concurrent.atomic.AtomicLong
import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success

/**
 * A provider that starts refusing calls on quota grounds keeps doing it for the whole rate limit window, so
 * alerting per call would flood the alert stream with hundreds of copies of the same information. Instead an
 * episode is opened on the first refusal and kept in memory: one alert when a provider endpoint starts being
 * throttled, one when it serves again, carrying how long it lasted and how many calls were refused.
 *
 * State is per node and per provider endpoint (kind + scheme://host). It is deliberately not persisted: it
 * describes what this node is observing right now, and a restart legitimately starts from a clean slate.
 */
case class QuotaEpisode(
  providerKind: String,
  endpoint: String,
  status: Int,
  reason: String,
  startedAt: Long,
  hits: AtomicLong,
) {
  def durationMs: Long = System.currentTimeMillis() - startedAt
  def json: JsValue = Json.obj(
    "provider_kind" -> providerKind,
    "endpoint" -> endpoint,
    "status" -> status,
    "reason" -> reason,
    "started_at" -> startedAt,
    "refused_calls" -> hits.get(),
  )
}

object ProviderQuotas {

  val exceededAlert = "LLMProviderQuotaExceededAlert"
  val recoveredAlert = "LLMProviderQuotaRecoveredAlert"

  // words a provider uses when a 403 is about quota rather than about credentials
  private val quotaWords = Seq("quota", "rate limit", "rate_limit", "ratelimit", "insufficient_quota", "billing", "credit", "exceeded", "too many requests")

  private val episodes = new TrieMap[String, QuotaEpisode]()

  def all: Seq[QuotaEpisode] = episodes.values.toSeq
  def episodeFor(providerKind: String, endpoint: String): Option[QuotaEpisode] = episodes.get(key(providerKind, endpoint))
  def reset(): Unit = episodes.clear()

  def key(providerKind: String, endpoint: String): String = s"${providerKind}###${endpoint}"

  /** the stable part of a call url: two providers of the same kind on the same endpoint share a quota.
    * The port is part of it, otherwise two local providers would be conflated. */
  def endpointOf(url: String): String = {
    scala.util.Try {
      val uri = Uri(url)
      s"${uri.scheme}://${uri.authority}"
    }.filter(_.nonEmpty).getOrElse(url)
  }

  /**
   * 429 is the standard rate limit / quota exceeded answer. 402 means the account ran out of credit, which is
   * the same problem seen from the billing side. 403 is only a quota problem when the body says so, otherwise
   * it is a plain authentication failure and must not raise a quota alert.
   */
  def quotaReason(status: Int, body: => Option[String]): Option[String] = status match {
    case 429 => "rate limit or quota exceeded".some
    case 402 => "out of credits".some
    case 403 => body.filter(mentionsQuota).map(_ => "quota exceeded")
    case _ => None
  }

  private def mentionsQuota(body: String): Boolean = {
    val lower = body.toLowerCase
    quotaWords.exists(lower.contains)
  }

  private def alertsEnabled(using env: Env): Boolean = {
    env.adminExtensions.extension[AiExtension].forall(_.quotaAlertsEnabled)
  }

  /**
   * Records the outcome of one provider call. `body` is a thunk and is only forced for the statuses that need
   * it, so a streamed response - whose body must never be consumed here - can safely pass None.
   */
  def record(providerKind: String, url: String, status: Int, body: => Option[String])(using env: Env): Unit = {
    val endpoint = endpointOf(url)
    quotaReason(status, body) match {
      case Some(reason) => open(providerKind, endpoint, status, reason)
      case None if status > 199 && status < 400 => close(providerKind, endpoint)
      // any other error (500, timeouts, auth failures) says nothing about quotas: leave the episode as is
      case None => ()
    }
  }

  private def open(providerKind: String, endpoint: String, status: Int, reason: String)(using env: Env): Unit = {
    val k = key(providerKind, endpoint)
    val episode = QuotaEpisode(providerKind, endpoint, status, reason, System.currentTimeMillis(), new AtomicLong(1L))
    episodes.putIfAbsent(k, episode) match {
      // already throttled: just count the refused call, the alert has been sent when the episode opened
      case Some(existing) => existing.hits.incrementAndGet()
      case None => {
        AiExtension.logger.warn(s"provider '${providerKind}' on ${endpoint} is refusing calls: ${reason} (HTTP ${status})")
        if (alertsEnabled) {
          Alerts.send(AlertEvent.generic(exceededAlert, providerKind, endpoint)(episode.json.asObject))
        }
      }
    }
  }

  private def close(providerKind: String, endpoint: String)(using env: Env): Unit = {
    episodes.remove(key(providerKind, endpoint)).foreach { episode =>
      AiExtension.logger.info(s"provider '${providerKind}' on ${endpoint} serves again after ${episode.durationMs}ms and ${episode.hits.get()} refused calls")
      if (alertsEnabled) {
        Alerts.send(AlertEvent.generic(recoveredAlert, providerKind, endpoint)(
          episode.json.asObject ++ Json.obj("duration_ms" -> episode.durationMs)
        ))
      }
    }
  }
}

extension (f: Future[WSResponse]) {

  /** for materialized responses: the body can be read, so a 403 can be classified */
  def observeQuotas(providerKind: String, url: String)(using ec: ExecutionContext, env: Env): Future[WSResponse] = {
    f.andThen {
      case Success(resp) => ProviderQuotas.record(providerKind, url, resp.status, Option(resp.body).map(_.take(2048)))
    }
  }

  /** for streamed responses: the body belongs to the stream and must not be consumed here */
  def observeStreamQuotas(providerKind: String, url: String)(using ec: ExecutionContext, env: Env): Future[WSResponse] = {
    f.andThen {
      case Success(resp) => ProviderQuotas.record(providerKind, url, resp.status, None)
    }
  }
}
