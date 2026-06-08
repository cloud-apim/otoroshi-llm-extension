package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import akka.stream.scaladsl.Source
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatClient, ChatPrompt, ChatResponse, ChatResponseChunk}
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.{JsBoolean, JsDefined, JsLookupResult, JsNull, JsNumber, JsObject, JsString, JsUndefined, JsValue, Json}

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

trait LoadBalancing {
  def select(reqId: String, targets: Seq[AiProvider])(implicit env: Env): AiProvider
}

object RoundRobin extends LoadBalancing {
  private val reqCounter                   = new AtomicInteger(0)
  override def select(reqId: String, targets: Seq[AiProvider])(implicit env: Env): AiProvider = {
    val index: Int = reqCounter.incrementAndGet() % (if (targets.nonEmpty) targets.size else 1)
    targets.apply(index)
  }
}

object Random extends LoadBalancing {
  private val random = new scala.util.Random
  override def select(reqId: String, targets: Seq[AiProvider])(implicit env: Env): AiProvider = {
    val index = random.nextInt(targets.length)
    targets.apply(index)
  }
}

// Sliding, time-decaying window of recent response times for a single provider.
// Old samples age out, so a provider that recovers (or degrades) is reflected within the
// window horizon instead of being penalized/favored forever by a cumulative average.
class LatencyWindow(maxAgeMs: Long, maxSamples: Int) {

  private val samples = scala.collection.mutable.ArrayBuffer.empty[(Long, Long)] // (timestampMs, durationMs)

  def record(now: Long, duration: Long): Unit = synchronized {
    samples.append((now, duration))
    evict(now)
  }

  private def evict(now: Long): Unit = {
    while (samples.nonEmpty && (now - samples.head._1) > maxAgeMs) samples.remove(0)
    while (samples.size > maxSamples) samples.remove(0)
  }

  // p-th percentile of the current window, or None when there is no recent data.
  def percentile(now: Long, p: Double): Option[Long] = synchronized {
    evict(now)
    if (samples.isEmpty) {
      None
    } else {
      val sorted = samples.map(_._2).toArray
      java.util.Arrays.sort(sorted)
      val rank = Math.ceil((p / 100.0) * sorted.length).toInt - 1
      val idx  = Math.min(Math.max(rank, 0), sorted.length - 1)
      Some(sorted(idx))
    }
  }
}

object BestResponseTime extends LoadBalancing {

  private val windowMaxAgeMs = 5 * 60 * 1000L // only consider the last 5 minutes of samples
  private val windowMaxSize  = 100            // ... and at most 100 samples per provider
  private val percentile     = 95.0           // route on p95 to be robust to tail latency
  private val responseTimes  = new UnboundedTrieMap[String, LatencyWindow]()

  def record(desc: AiProvider, responseTime: Long): Unit = {
    val window = responseTimes.getOrElseUpdate(desc.id, new LatencyWindow(windowMaxAgeMs, windowMaxSize))
    window.record(System.currentTimeMillis(), responseTime)
  }

  override def select(reqId: String, targets: Seq[AiProvider])(implicit env: Env): AiProvider = {
    if (targets.isEmpty) throw new IllegalArgumentException("no targets to load balance on")
    val now = System.currentTimeMillis()
    val scored: Seq[(AiProvider, Option[Long])] = targets.map { t =>
      (t, responseTimes.get(t.id).flatMap(_.percentile(now, percentile)))
    }
    // providers with no recent data are probed first — this also warms up each provider once and
    // re-probes any provider that has been idle longer than the window horizon.
    scored.collectFirst { case (p, None) => p }.getOrElse {
      scored.collect { case (p, Some(p95)) => (p, p95) }.minBy(_._2)._1
    }
  }
}

object LoadBalancerChatClient {
  val counter = new AtomicLong(0L)
}

case class LoadBalancingTarget(ref: String, weight: Int, selector: Option[String])

class LoadBalancerChatClient(provider: AiProvider) extends ChatClient {

  override def computeModel(payload: JsValue): Option[String] = None

  override def isCohere: Boolean = false
  override def isOpenAi: Boolean = true
  override def isAnthropic: Boolean = false

  def execute[T](prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(f: (AiProvider, ChatClient) => Future[Either[JsValue, T]])(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, T]] = {
    val refs: Seq[LoadBalancingTarget] = provider.options.select("refs")
      .asOpt[Seq[String]].map { seq =>
        seq.map(i => LoadBalancingTarget(i, 1, None))
      }
      .orElse(provider.options.select("refs").asOpt[Seq[JsObject]].map { seq =>
        seq.map { obj =>
          LoadBalancingTarget(
            obj.select("ref").asString,
            obj.select("weight").asOpt[Int].getOrElse(1),
            obj.select("selector_expected").asOptString
          )
        }
      })
      .getOrElse(Seq.empty)
    val loadBalancing: LoadBalancing = provider.options.select("loadbalancing").asOpt[String].map(_.toLowerCase()).getOrElse("round_robin") match {
      case "random" => Random
      case "best_response_time" => BestResponseTime
      case _ => RoundRobin
    }
    if (refs.isEmpty) {
      Json.obj("error" -> "no provider configured").leftf
    } else {
      val selector_expr = provider.options.select("selector_expr").asOpt[String]
      val all_providers: Seq[(AiProvider, Option[String])] = refs
        .flatMap(r => if (r.weight <= 1) Seq(r) else (0 to r.weight).map(_ => r))
        .flatMap(r => env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(r.ref)).map(v => (v, r.selector)))
      // val index = LoadBalancerChatClient.counter.incrementAndGet() % (if (providers.nonEmpty) providers.size else 1)

      def filterAll(body: JsObject, f: (JsObject, Option[String]) => JsLookupResult): Seq[(AiProvider, Option[String])] = {
        all_providers.filter {
          case (p, selector) =>
            val mergedOptions: JsObject = if (p.options.select("allow_config_override").asOptBoolean.getOrElse(true)) p.options.deepMerge(body) else p.options
            f(mergedOptions, selector) match {
              case _ if selector.isEmpty => true
              case JsUndefined() => true
              case JsDefined(JsNull) => true
              case JsDefined(JsString(str)) if selector.contains(str) => true // TODO: handle more complex expressions ?
              case JsDefined(JsBoolean(str)) if selector.contains(str.toString) => true
              case JsDefined(JsNumber(str)) if selector.contains(str.toString) => true
              case _ => false
            }
        }
      }

      val body = originalBody.asObject
      val providers = selector_expr match {
        case None => all_providers
        case Some("*") => all_providers
        case Some(str) if str.startsWith("JsonPointer(") => filterAll(body, (o, _) => o.atPointer(str.substring(12).init))
        case Some(str) if str.startsWith("JsonPath(") => filterAll(body, (o, _) => o.atPath(str.substring(9).init))
        case Some(str) => filterAll(body, (o, _) => o.at(str))
      }
      val settings = CircuitBreakerSettings.fromProvider(provider)
      val allTargets = providers.map(_._1)
      val candidates = if (settings.enabled) {
        val now = System.currentTimeMillis()
        val healthy = allTargets.filterNot(p => ProviderCircuitBreaker.isOpen(p.id, now))
        if (healthy.nonEmpty) healthy else allTargets // every target is cooling down → try anyway rather than fail
      } else allTargets
      val selectedProvider = loadBalancing.select(LoadBalancerChatClient.counter.incrementAndGet().toString, candidates)
      selectedProvider.getChatClient() match {
        case None => Json.obj("error" -> "no client found").leftf
        case Some(client) =>
          val result = f(selectedProvider, client)
          if (settings.enabled) {
            result.andThen {
              case Success(Right(_)) => ProviderCircuitBreaker.recordSuccess(selectedProvider.id)
              case Success(Left(_))  => ProviderCircuitBreaker.recordFailure(selectedProvider.id, System.currentTimeMillis(), settings)
              case Failure(_)        => ProviderCircuitBreaker.recordFailure(selectedProvider.id, System.currentTimeMillis(), settings)
            }
          } else {
            result
          }
      }
    }
  }

  override def call(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    execute(prompt, attrs, originalBody) { (selected, client) =>
      val start = System.currentTimeMillis()
      client.call(prompt, attrs, originalBody).map { resp =>
        BestResponseTime.record(selected, System.currentTimeMillis() - start)
        resp
      }
    }
  }

  override def stream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    execute(prompt, attrs, originalBody) { (selected, client) =>
      val start = System.currentTimeMillis()
      client.stream(prompt, attrs, originalBody).map { resp =>
        BestResponseTime.record(selected, System.currentTimeMillis() - start)
        resp
      }
    }
  }

  override def completion(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    execute(prompt, attrs, originalBody) { (selected, client) =>
      val start = System.currentTimeMillis()
      client.completion(prompt, attrs, originalBody).map { resp =>
        BestResponseTime.record(selected, System.currentTimeMillis() - start)
        resp
      }
    }
  }

  override def completionStream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    execute(prompt, attrs, originalBody) { (selected, client) =>
      val start = System.currentTimeMillis()
      client.completionStream(prompt, attrs, originalBody).map { resp =>
        BestResponseTime.record(selected, System.currentTimeMillis() - start)
        resp
      }
    }
  }
}
