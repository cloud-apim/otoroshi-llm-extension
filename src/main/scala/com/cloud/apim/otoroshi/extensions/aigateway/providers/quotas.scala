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
 * Two very different things hide behind "the provider refused my call":
 *
 *  - being throttled: transient, lasts seconds to minutes, resolves on its own, and the provider usually tells
 *    us when to come back. Nothing to do but wait, and probing it would only add load to something already
 *    rate limited.
 *  - running out of credit: persistent, every single call fails until a human tops the account up. Waiting
 *    achieves nothing, and it is worth finding out before the users do.
 *
 * They are tracked as the same kind of episode but never conflated: the kind drives which alert is raised and,
 * later, what the routing should do about it.
 */
sealed trait QuotaIncidentKind {
  def name: String
  def transient: Boolean
}

object QuotaIncidentKind {

  case object Throttled extends QuotaIncidentKind {
    def name: String = "throttled"
    def transient: Boolean = true
  }

  case object CreditExhausted extends QuotaIncidentKind {
    def name: String = "credit_exhausted"
    def transient: Boolean = false
  }

  val all: Seq[QuotaIncidentKind] = Seq(Throttled, CreditExhausted)
  def fromName(name: String): Option[QuotaIncidentKind] = all.find(_.name == name)
}

case class QuotaIncident(kind: QuotaIncidentKind, status: Int, reason: String)

case class QuotaEpisode(
  providerKind: String,
  endpoint: String,
  kind: QuotaIncidentKind,
  status: Int,
  reason: String,
  startedAt: Long,
  hits: AtomicLong,
  // when the provider told us to come back, from Retry-After or a rate limit reset header
  retryAt: Option[Long],
  // every provider entity observed hitting this endpoint, so the routing can ask about one of them
  providerIds: Set[String],
) {
  def durationMs: Long = System.currentTimeMillis() - startedAt
  def json: JsValue = Json.obj(
    "provider_kind" -> providerKind,
    "endpoint" -> endpoint,
    "kind" -> kind.name,
    "transient" -> kind.transient,
    "status" -> status,
    "reason" -> reason,
    "started_at" -> startedAt,
    "refused_calls" -> hits.get(),
    "provider_ids" -> providerIds,
  ).asObject.applyOnWithOpt(retryAt) {
    case (obj, at) => obj ++ Json.obj("retry_at" -> at, "retry_in_ms" -> (at - System.currentTimeMillis()))
  }
}

object ProviderQuotas {

  val throttledAlert = "LLMProviderQuotaExceededAlert"
  val creditExhaustedAlert = "LLMProviderCreditExhaustedAlert"
  val recoveredAlert = "LLMProviderQuotaRecoveredAlert"

  // how long a provider with no credit left is skipped before one call is let through to check for a top up
  val creditExhaustedBackoffMs: Long = 5 * 60 * 1000L

  // a body saying this means the account has no money left, whatever the status code carrying it. OpenAI
  // notably answers 429 with an `insufficient_quota` code when the credit is gone, which is NOT throttling.
  private val creditWords = Seq("insufficient_quota", "insufficient quota", "out of credit", "no credit", "credit balance", "billing", "payment", "exceeded your current quota", "top up", "recharge")
  // a body saying this means a quota problem, used to tell a quota 403 from a credentials 403
  private val quotaWords = Seq("quota", "rate limit", "rate_limit", "ratelimit", "too many requests", "exceeded")

  private val episodes = new TrieMap[String, QuotaEpisode]()

  def all: Seq[QuotaEpisode] = episodes.values.toSeq
  def episodeFor(providerKind: String, endpoint: String): Option[QuotaEpisode] = episodes.get(key(providerKind, endpoint))
  def episodeForProvider(providerId: String): Option[QuotaEpisode] = episodes.values.find(_.providerIds.contains(providerId))
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

  private def mentions(body: String, words: Seq[String]): Boolean = {
    val lower = body.toLowerCase
    words.exists(lower.contains)
  }

  /**
   * 402 always means the account ran out of credit. 429 is the standard throttling answer, unless the body says
   * the credit is gone. 403 is only a quota problem when the body says so, otherwise it is a plain credentials
   * failure and must not raise anything.
   */
  def classify(status: Int, body: => Option[String]): Option[QuotaIncident] = status match {
    case 402 => QuotaIncident(QuotaIncidentKind.CreditExhausted, status, "out of credits").some
    case 429 if body.exists(mentions(_, creditWords)) =>
      QuotaIncident(QuotaIncidentKind.CreditExhausted, status, "quota exhausted for the account").some
    case 429 => QuotaIncident(QuotaIncidentKind.Throttled, status, "rate limit exceeded").some
    case 403 if body.exists(mentions(_, creditWords)) =>
      QuotaIncident(QuotaIncidentKind.CreditExhausted, status, "out of credits").some
    case 403 if body.exists(mentions(_, quotaWords)) =>
      QuotaIncident(QuotaIncidentKind.Throttled, status, "quota exceeded").some
    case _ => None
  }

  /**
   * When to come back, as told by the provider. `Retry-After` is either a number of seconds or an HTTP date;
   * OpenAI-style reset headers use durations like `6m0s` or `1.5s`.
   */
  def retryAtFrom(header: String => Option[String], now: Long): Option[Long] = {
    def fromRetryAfter(raw: String): Option[Long] = {
      raw.trim.toLongOption.map(seconds => now + (seconds * 1000L)).orElse {
        scala.util.Try(org.joda.time.format.DateTimeFormat.forPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'")
          .withZoneUTC().withLocale(java.util.Locale.ENGLISH).parseDateTime(raw.trim).getMillis).toOption
      }
    }
    header("Retry-After").flatMap(fromRetryAfter)
      .orElse(header("x-ratelimit-reset-requests").flatMap(parseDuration).map(now + _))
      .orElse(header("x-ratelimit-reset-tokens").flatMap(parseDuration).map(now + _))
      .filter(_ > now)
  }

  /** `6m0s`, `1.5s`, `250ms` or a bare number of seconds */
  def parseDuration(raw: String): Option[Long] = {
    val value = raw.trim.toLowerCase
    if (value.isEmpty) None
    else value.toDoubleOption.map(seconds => (seconds * 1000).toLong).orElse {
      val pattern = """(\d+(?:\.\d+)?)(ms|s|m|h)""".r
      val matches = pattern.findAllMatchIn(value).toSeq
      if (matches.isEmpty) None else Some(matches.map { m =>
        val amount = m.group(1).toDouble
        m.group(2) match {
          case "ms" => amount
          case "s" => amount * 1000
          case "m" => amount * 60000
          case "h" => amount * 3600000
        }
      }.sum.toLong)
    }
  }

  private def alertsEnabled(using env: Env): Boolean = {
    env.adminExtensions.extension[AiExtension].forall(_.quotaAlertsEnabled)
  }

  /**
   * Records the outcome of one provider call or probe. `body` is a thunk and is only forced for the statuses
   * that need it, so a streamed response - whose body must never be consumed here - can safely pass None.
   */
  def record(
    providerKind: String,
    url: String,
    status: Int,
    body: => Option[String],
    header: String => Option[String] = _ => None,
    providerId: Option[String] = None,
  )(using env: Env): Unit = {
    val endpoint = endpointOf(url)
    classify(status, body) match {
      case Some(incident) => open(providerKind, endpoint, incident, header, providerId)
      // only a call that actually bills proves the account is healthy again: a models listing answers 200 on
      // an account with no credit left, and letting it close the episode would clear a real incident and
      // announce a recovery that did not happen
      case None if status > 199 && status < 400 && billable(url) => close(providerKind, endpoint)
      // any other error (500, timeouts, credentials failures) says nothing about quotas: leave the episode as is
      case None => ()
    }
  }

  // metadata endpoints: reachable and authenticated, but free, so their success proves nothing about credit.
  // A quota refusal on them is still meaningful and still opens an episode.
  private val freeEndpointSuffixes = Seq("models", "tags")

  def billable(url: String): Boolean = {
    val path = scala.util.Try(Uri(url).path.toString()).getOrElse(url)
    val lastSegment = path.split('/').filter(_.nonEmpty).lastOption.map(_.toLowerCase).getOrElse("")
    !freeEndpointSuffixes.contains(lastSegment)
  }

  private def open(providerKind: String, endpoint: String, incident: QuotaIncident, header: String => Option[String], providerId: Option[String])(using env: Env): Unit = {
    val k = key(providerKind, endpoint)
    val now = System.currentTimeMillis()
    val fresh = QuotaEpisode(
      providerKind = providerKind,
      endpoint = endpoint,
      kind = incident.kind,
      status = incident.status,
      reason = incident.reason,
      startedAt = now,
      hits = new AtomicLong(1L),
      retryAt = retryAtFrom(header, now),
      providerIds = providerId.toSet,
    )
    episodes.putIfAbsent(k, fresh) match {
      case None => {
        AiExtension.logger.warn(s"provider '${providerKind}' on ${endpoint} is refusing calls: ${incident.reason} (HTTP ${incident.status})")
        if (alertsEnabled) {
          val alert = if (incident.kind == QuotaIncidentKind.CreditExhausted) creditExhaustedAlert else throttledAlert
          Alerts.send(AlertEvent.generic(alert, providerKind, endpoint)(fresh.json.asObject))
        }
      }
      case Some(existing) => {
        existing.hits.incrementAndGet()
        // a throttling episode that turns out to be a credit problem must be re-qualified: it stops being
        // something we can wait out, and deserves the louder alert
        val escalates = existing.kind == QuotaIncidentKind.Throttled && incident.kind == QuotaIncidentKind.CreditExhausted
        val escalated = existing.copy(
          kind = incident.kind,
          status = incident.status,
          reason = incident.reason,
          retryAt = retryAtFrom(header, now).orElse(existing.retryAt),
          providerIds = existing.providerIds ++ providerId.toSet,
        )
        episodes.update(k, escalated)
        if (escalates) {
          AiExtension.logger.warn(s"provider '${providerKind}' on ${endpoint} is not throttled but out of credits")
          if (alertsEnabled) Alerts.send(AlertEvent.generic(creditExhaustedAlert, providerKind, endpoint)(escalated.json.asObject))
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

  /** for materialized responses: the body can be read, so the incident can be qualified precisely */
  def observeQuotas(providerKind: String, url: String, providerId: Option[String] = None)(using ec: ExecutionContext, env: Env): Future[WSResponse] = {
    f.andThen {
      case Success(resp) => ProviderQuotas.record(providerKind, url, resp.status, Option(resp.body).map(_.take(2048)), name => resp.header(name), providerId)
    }
  }

  /** for streamed responses: the body belongs to the stream and must not be consumed here, headers are fine */
  def observeStreamQuotas(providerKind: String, url: String, providerId: Option[String] = None)(using ec: ExecutionContext, env: Env): Future[WSResponse] = {
    f.andThen {
      case Success(resp) => ProviderQuotas.record(providerKind, url, resp.status, None, name => resp.header(name), providerId)
    }
  }
}
