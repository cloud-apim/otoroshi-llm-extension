package com.cloud.apim.otoroshi.extensions.aigateway.providers

import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatMessage, ChatPrompt}
import org.apache.pekko.actor.Cancellable
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.Json

import java.util.concurrent.atomic.AtomicReference
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

/**
 * Finding out that an account has no credit left when the first user call fails is finding out too late: every
 * call fails until someone tops it up. A cheap inference call, run on a slow schedule, sees it coming.
 *
 * The probe deliberately makes a real - if minimal - inference call rather than listing models: a models
 * listing answers 200 on an account at zero, so it would report a healthy provider right up to the outage.
 *
 * The probe does not interpret anything: it goes through the same provider api as user traffic, so the quota
 * observation already wired there records the outcome and raises the alerts.
 */
object ProviderHealthchecks {

  private val schedulerRef = new AtomicReference[Cancellable]()
  private val lastRunAt = new TrieMap[String, Long]()

  // how often the scheduler looks for providers due to be probed, not how often a provider is probed
  private val tick = 30.seconds

  def start(env: Env, ext: AiExtension): Unit = {
    given ec: ExecutionContext = env.otoroshiExecutionContext
    schedulerRef.set(env.otoroshiScheduler.scheduleAtFixedRate(tick, tick) { () =>
      scala.util.Try(runDue(env, ext)).recover {
        case err => AiExtension.logger.error("error while running provider healthchecks", err)
      }
      ()
    })
  }

  def stop(): Unit = {
    Option(schedulerRef.get()).foreach(_.cancel())
    lastRunAt.clear()
  }

  def runDue(env: Env, ext: AiExtension): Unit = {
    given ev: Env = env
    given ec: ExecutionContext = env.otoroshiExecutionContext
    val now = System.currentTimeMillis()
    ext.states.allProviders().filter(_.healthcheck.enabled).foreach { provider =>
      val due = lastRunAt.get(provider.id).forall(last => (now - last) >= provider.healthcheck.everyMs)
      if (due) {
        lastRunAt.update(provider.id, now)
        probe(provider)
      }
    }
  }

  /** one minimal inference call. The result is ignored on purpose: what matters is the http status, which the
    * provider api records into the quota state on its way back. */
  def probe(provider: AiProvider)(using ec: ExecutionContext, env: Env): Future[Unit] = {
    provider.getRawChatClient() match {
      case None =>
        AiExtension.logger.warn(s"unable to build a client to probe provider '${provider.name}'")
        ().vfuture
      case Some(client) => {
        val settings = provider.healthcheck
        val body = Json.obj("max_tokens" -> settings.maxTokens, "max_completion_tokens" -> settings.maxTokens)
        val prompt = ChatPrompt(Seq(ChatMessage.input("user", settings.prompt, None, Json.obj())))
        client.call(prompt, TypedMap.empty, body).map {
          case Left(err) => AiExtension.logger.debug(s"healthcheck probe for '${provider.name}' failed: ${err}")
          case Right(_) => AiExtension.logger.debug(s"healthcheck probe for '${provider.name}' succeeded")
        }.recover {
          case err => AiExtension.logger.debug(s"healthcheck probe for '${provider.name}' threw: ${err.getMessage}")
        }
      }
    }
  }
}
