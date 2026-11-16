package com.cloud.apim.otoroshi.extensions.aigateway.providers

import com.cloud.apim.otoroshi.extensions.aigateway.decorators.{CircuitBreakerSettings, FirstTokenTimeout, ModelConstraints, ProviderCircuitBreaker}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatCallKind, ChatClient, ChatPrompt, ChatResponse, ChatResponseChunk, KindBasedChatClient}
import org.apache.pekko.stream.scaladsl.Source
import otoroshi.env.Env
import otoroshi.events.{AlertEvent, Alerts}
import otoroshi.utils.TypedMap
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.*
import play.api.libs.typedmap.TypedKey

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/** A copy of the server of a provider: same kind, same token, same options, another url. */
final case class ProviderTarget(id: String, baseUrl: String, weight: Int) {
  lazy val endpoint: String = ProviderQuotas.endpointOf(baseUrl)
  def json: JsObject = Json.obj("id" -> id, "base_url" -> baseUrl, "weight" -> weight)
}

final case class ProviderTargetsOptions(strategy: String, maxAttempts: Int, whenNoneAvailable: String) {
  def trySaturated: Boolean = whenNoneAvailable == ProviderTargetsOptions.TrySaturated
}

object ProviderTargetsOptions {

  val RoundRobin = "round_robin"
  val Random = "random"
  val LeastLoaded = "least_loaded"
  val Reject = "reject"
  val TrySaturated = "try_saturated"

  val strategies: Set[String] = Set(RoundRobin, Random, LeastLoaded)
  val whenNoneAvailable: Set[String] = Set(Reject, TrySaturated)

  val default: ProviderTargetsOptions = ProviderTargetsOptions(RoundRobin, 2, Reject)

  // `connection.targets_options`, read on a provider without targets too: `when_none_available` also says whether a
  // saturated provider is still worth a call (fallback, load balancer)
  def from(connection: JsObject): ProviderTargetsOptions = connection.select("targets_options").asOpt[JsObject] match {
    case None => default
    case Some(o) => ProviderTargetsOptions(
      strategy = o.select("strategy").asOpt[String].map(_.trim.toLowerCase).filter(strategies.contains).getOrElse(RoundRobin),
      maxAttempts = o.select("max_attempts").asOpt[Int].getOrElse(2).max(1),
      whenNoneAvailable = o.select("when_none_available").asOpt[String].map(_.trim.toLowerCase).filter(whenNoneAvailable.contains).getOrElse(Reject),
    )
  }
}

object ProviderTargets {

  // the kinds that do not build their client on `base_url`: a resource and a deployment (azure), an account
  // (cloudflare), a local model (jlama), a base domain (ovh), other providers (load balancer, router)
  private val unsupportedKinds: Set[String] = Set("azure-openai", "cloudflare", "jlama", "ovh-ai-endpoints") ++ AiProvider.routingProviders

  def supports(kind: String): Boolean = {
    val k = kind.toLowerCase
    AiProvider.chatClientBuilders.contains(k) && !unsupportedKinds.contains(k)
  }

  // read once per version of the entity: the state keeps the same connection object until the entity changes
  private val cache = new UnboundedTrieMap[String, (JsObject, String, Seq[ProviderTarget])]()

  def has(provider: AiProvider): Boolean = of(provider).nonEmpty

  def of(provider: AiProvider): Seq[ProviderTarget] = cache.get(provider.id) match {
    case Some((connection, kind, targets)) if (connection eq provider.connection) && kind == provider.provider => targets
    case _ =>
      val targets = read(provider.provider, provider.connection)
      cache.put(provider.id, (provider.connection, provider.provider, targets))
      targets
  }

  /**
   * The targets of a connection: its `targets` list, or else the urls of a `base_url` holding commas, each one a
   * target of weight 1 named by its url. A `base_url` without a comma is one url, as it always was.
   */
  def read(kind: String, connection: JsObject): Seq[ProviderTarget] = {
    if (!supports(kind)) Seq.empty
    else connection.select("targets").asOpt[JsArray].map(_.value.toSeq).filter(_.nonEmpty) match {
      case Some(items) => items.flatMap(parse).distinctBy(_.id)
      case None => connection.select("base_url").asOpt[String].filter(_.contains(",")) match {
        case None => Seq.empty
        case Some(raw) => raw.split(",").toSeq.map(_.trim).filter(_.nonEmpty).distinct.map(url => ProviderTarget(url, url, 1))
      }
    }
  }

  private def parse(item: JsValue): Option[ProviderTarget] = for {
    id <- item.select("id").asOpt[String].map(_.trim).filter(_.nonEmpty)
    url <- item.select("base_url").asOpt[String].map(_.trim).filter(_.nonEmpty)
  } yield ProviderTarget(id, url, item.select("weight").asOpt[Int].getOrElse(1).max(0))

  private def absoluteHttpUrl(url: String): Boolean = Try(new java.net.URI(url)).toOption.exists { uri =>
    uri.isAbsolute && Option(uri.getScheme).exists(s => s == "http" || s == "https") && Option(uri.getHost).exists(_.nonEmpty)
  }

  /** what is refused when a provider is written: everything the call path would otherwise read leniently */
  def validate(provider: AiProvider): Either[String, Unit] = {
    val connection = provider.connection
    val targets: Either[String, Unit] = connection.select("targets").asOpt[JsValue] match {
      case None | Some(JsNull) => Right(())
      case Some(JsArray(items)) if items.isEmpty => Right(())
      case Some(JsArray(_)) if !supports(provider.provider) =>
        Left(s"the provider kind '${provider.provider}' does not build its client on a base url, it cannot have targets")
      case Some(JsArray(items)) =>
        val errors = items.toSeq.zipWithIndex.flatMap { case (item, idx) =>
          val id = item.select("id").asOpt[String].map(_.trim).getOrElse("")
          val url = item.select("base_url").asOpt[String].map(_.trim).getOrElse("")
          val weight = item.select("weight").asOpt[JsValue]
          Seq(
            if (id.isEmpty || id.length > 512) Some(s"connection.targets[$idx].id must be a string of 1 to 512 characters") else None,
            if (!absoluteHttpUrl(url)) Some(s"connection.targets[$idx].base_url must be an absolute http or https url") else None,
            weight match {
              case None | Some(JsNull) => None
              case Some(JsNumber(n)) if n.isValidInt && n >= 0 => None
              case _ => Some(s"connection.targets[$idx].weight must be an integer greater or equal to 0")
            },
          ).flatten
        }
        val ids = items.toSeq.flatMap(_.select("id").asOpt[String].map(_.trim))
        val duplicates = ids.diff(ids.distinct).distinct
        (errors ++ duplicates.map(d => s"the target id '$d' is used twice")).headOption.toLeft(())
      case Some(_) => Left("connection.targets must be an array")
    }
    val options: Either[String, Unit] = connection.select("targets_options").asOpt[JsValue] match {
      case None | Some(JsNull) => Right(())
      case Some(o: JsObject) =>
        Seq(
          o.select("strategy").asOpt[String].filterNot(s => ProviderTargetsOptions.strategies.contains(s.trim.toLowerCase))
            .map(s => s"unknown targets strategy '$s'"),
          o.select("when_none_available").asOpt[String].filterNot(s => ProviderTargetsOptions.whenNoneAvailable.contains(s.trim.toLowerCase))
            .map(s => s"unknown when_none_available '$s'"),
          o.select("max_attempts").asOpt[JsValue].collect {
            case JsNumber(n) if !(n.isValidInt && n >= 1) => "connection.targets_options.max_attempts must be an integer greater or equal to 1"
          },
        ).flatten.headOption.toLeft(())
      case Some(_) => Left("connection.targets_options must be an object")
    }
    for {
      _ <- targets
      _ <- options
    } yield ()
  }
}

/** The target that served a call (or the last one tried), and how many were tried: what the audit reports. */
object ProviderTargetAttempts {

  private val Key = TypedKey[java.util.Map[String, JsObject]]("cloud-apim.ai-gateway.ProviderTarget")

  def record(attrs: TypedMap, providerId: String, target: ProviderTarget, attempts: Int): Unit = {
    attrs.putIfAbsent(Key -> new java.util.concurrent.ConcurrentHashMap[String, JsObject]())
    attrs.get(Key).foreach(_.put(providerId, Json.obj("id" -> target.id, "base_url" -> target.baseUrl, "attempts" -> attempts)))
  }

  // kept by provider: the fallback of a provider, served with the same attributes, has targets of its own, or none
  def of(attrs: TypedMap, providerId: String): Option[JsObject] = attrs.get(Key).flatMap(m => Option(m.get(providerId)))

  def clear(attrs: TypedMap, providerId: String): Unit = attrs.get(Key).foreach(_.remove(providerId))
}

/** Which target serves a call: every read here is in memory, nothing goes to the datastore on the path of a call. */
object TargetSelection {

  private val counters = new UnboundedTrieMap[String, AtomicLong]()
  private val inFlight = new UnboundedTrieMap[String, AtomicInteger]()

  def breakerKey(providerId: String, targetId: String): String = s"${providerId}#${targetId}"

  def inFlightOf(provider: AiProvider, target: ProviderTarget): Int = inFlight.get(breakerKey(provider.id, target.id)).map(_.get()).getOrElse(0)

  def started(provider: AiProvider, target: ProviderTarget): Unit = {
    val key = breakerKey(provider.id, target.id)
    inFlight.getOrElseUpdate(key, new AtomicInteger(0)).incrementAndGet()
  }

  def ended(provider: AiProvider, target: ProviderTarget): Unit = {
    inFlight.get(breakerKey(provider.id, target.id)).foreach(c => if (c.decrementAndGet() < 0) c.set(0))
  }

  // a quota episode on the endpoint of a target keeps it out while the provider said not to come back. Without a
  // date, the circuit of the target, opened on the refusal, keeps it out for a bounded time
  def quotaOf(provider: AiProvider, target: ProviderTarget): Option[QuotaEpisode] = {
    ProviderQuotas.all.find(e => e.endpoint == target.endpoint && e.providerIds.contains(provider.id))
  }

  private def quotaHolds(provider: AiProvider, target: ProviderTarget, now: Long): Boolean = {
    quotaOf(provider, target).exists(_.retryAt.exists(_ > now))
  }

  /** usable for a call: in rotation, `available` (or `saturated` when allowed), circuit closed, no quota refusal */
  def usable(provider: AiProvider, target: ProviderTarget, now: Long, allowSaturated: Boolean = false): Boolean = {
    def accepted(status: TargetStatus): Boolean = status == TargetStatus.Available || (allowSaturated && status == TargetStatus.Saturated)
    target.weight > 0 &&
      accepted(ProviderStates.statusOf(provider, Some(target.id), now)) &&
      accepted(ProviderStates.statusOf(provider, None, now)) &&
      !ProviderCircuitBreaker.isOpen(breakerKey(provider.id, target.id), now) &&
      !quotaHolds(provider, target, now)
  }

  def anyUsable(provider: AiProvider, now: Long): Boolean = {
    val targets = ProviderTargets.of(provider)
    val trySaturated = ProviderTargetsOptions.from(provider.connection).trySaturated
    targets.exists(t => usable(provider, t, now)) || (trySaturated && targets.exists(t => usable(provider, t, now, allowSaturated = true)))
  }

  def loadOf(provider: AiProvider, target: ProviderTarget, now: Long): Double = {
    ProviderStates.get(provider.id, Some(target.id), now).flatMap(_.load)
      .getOrElse(inFlightOf(provider, target).toDouble / target.weight.max(1).toDouble)
  }

  // weighted: a target of weight 2 is picked twice as often as one of weight 1
  def pick(provider: AiProvider, candidates: Seq[ProviderTarget], strategy: String, now: Long): Option[ProviderTarget] = {
    val total = candidates.map(_.weight.toLong).sum
    if (candidates.isEmpty || total <= 0L) None
    else strategy match {
      case ProviderTargetsOptions.LeastLoaded =>
        val loads = candidates.map(t => (t, loadOf(provider, t, now)))
        val min = loads.map(_._2).min
        val lightest = loads.collect { case (t, l) if l <= min => t }
        weighted(lightest, counters.getOrElseUpdate(provider.id, new AtomicLong(0L)).getAndIncrement())
      case ProviderTargetsOptions.Random => weighted(candidates, scala.util.Random.nextLong(total))
      case _ => weighted(candidates, counters.getOrElseUpdate(provider.id, new AtomicLong(0L)).getAndIncrement())
    }
  }

  private def weighted(candidates: Seq[ProviderTarget], n: Long): Option[ProviderTarget] = {
    val total = candidates.map(_.weight.toLong).sum
    if (total <= 0L) candidates.headOption
    else {
      val position = Math.floorMod(n, total)
      var acc = 0L
      candidates.find { t =>
        acc = acc + t.weight
        position < acc
      }
    }
  }

  def reset(): Unit = {
    counters.clear()
    inFlight.clear()
  }
}

/**
 * One alert when no target of a provider can take a call any more, one when a call is served again. Per node, like
 * the quota episodes.
 */
object ProviderTargetsAlerts {

  val noTargetAlert = "LLMProviderNoTargetAvailable"
  val recoveredAlert = "LLMProviderTargetsRecovered"

  private val since = new UnboundedTrieMap[String, Long]()

  private def alertsEnabled(using env: Env): Boolean = env.adminExtensions.extension[AiExtension].forall(_.quotaAlertsEnabled)

  private def payload(provider: AiProvider)(using env: Env): JsObject = Json.obj(
    "provider_id" -> provider.id,
    "provider_name" -> provider.name,
    "provider_kind" -> provider.provider,
    "targets" -> JsArray(ProviderTargets.of(provider).map(_.json)),
  )

  def noneAvailable(provider: AiProvider)(using env: Env): Unit = {
    if (since.putIfAbsent(provider.id, System.currentTimeMillis()).isEmpty) {
      AiExtension.logger.warn(s"no target of provider '${provider.name}' is available")
      if (alertsEnabled) Alerts.send(AlertEvent.generic(noTargetAlert, provider.provider, provider.id)(payload(provider)))
    }
  }

  def served(provider: AiProvider)(using env: Env): Unit = {
    if (since.nonEmpty) since.remove(provider.id).foreach { start =>
      val duration = System.currentTimeMillis() - start
      AiExtension.logger.info(s"a target of provider '${provider.name}' serves again after ${duration}ms")
      if (alertsEnabled) Alerts.send(AlertEvent.generic(recoveredAlert, provider.provider, provider.id)(payload(provider) ++ Json.obj("duration_ms" -> duration)))
    }
  }

  def reset(): Unit = since.clear()
}

object TargetsChatClient {

  def noTargetAvailable(provider: AiProvider): JsObject = Json.obj(
    "error" -> Json.obj(
      "type" -> "no_target_available",
      "code" -> "no_target_available",
      "message" -> s"no target of provider '${provider.name}' is available",
    ),
    "status" -> 503,
  )

  def isNoTargetAvailable(err: JsValue): Boolean = err.select("error").select("code").asOpt[String].contains("no_target_available")
}

/**
 * The raw client of a provider with targets. It picks a target for each call among those it can use, calls the
 * client built for it, and tries another one when it fails, up to `max_attempts` targets. Every decorator of the
 * provider wraps it: they see one provider.
 */
class TargetsChatClient(
  val provider: AiProvider,
  val targets: Seq[ProviderTarget],
  options: ProviderTargetsOptions,
  build: ProviderTarget => Option[ChatClient],
) extends KindBasedChatClient {

  private val clients = new scala.collection.concurrent.TrieMap[String, Option[ChatClient]]()

  def clientOf(target: ProviderTarget): Option[ChatClient] = clients.getOrElseUpdate(target.id, build(target))

  // one client per target, for the probe of the health check
  def targetClients: Seq[(ProviderTarget, ChatClient)] = targets.flatMap(t => clientOf(t).map(c => (t, c)))

  // what does not call the provider is the same for every target
  private def first: Option[ChatClient] = targets.headOption.flatMap(clientOf)

  override def isAnthropic: Boolean = first.exists(_.isAnthropic)
  override def isCohere: Boolean = first.exists(_.isCohere)
  override def isOpenAi: Boolean = first.forall(_.isOpenAi)
  override def supportsStreaming: Boolean = first.exists(_.supportsStreaming)
  override def supportsTools: Boolean = first.exists(_.supportsTools)
  override def supportsCompletion: Boolean = first.exists(_.supportsCompletion)
  override def supportsResponses: Boolean = first.exists(_.supportsResponses)
  override def computeModel(payload: JsValue): Option[String] = first.flatMap(_.computeModel(payload))
  override def transformOpenAIInputBodyToProviderInputBody(inputBody: JsObject): JsObject = first.map(_.transformOpenAIInputBodyToProviderInputBody(inputBody)).getOrElse(inputBody)

  override def listModels(raw: Boolean, attrs: TypedMap)(using ec: ExecutionContext): Future[Either[JsValue, List[String]]] = {
    val now = System.currentTimeMillis()
    targets.find(t => TargetSelection.usable(provider, t, now)).orElse(targets.headOption).flatMap(clientOf) match {
      case None => Left(Json.obj("error" -> "no target client")).vfuture
      case Some(client) => client.listModels(raw, attrs)
    }
  }

  override def invoke(kind: ChatCallKind, prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    attempt[ChatResponse](attrs, streaming = false) { (target, client) =>
      client.invoke(kind, prompt, attrs, originalBody).andThen { case _ => TargetSelection.ended(provider, target) }
    }
  }

  override def invokeStream(kind: ChatCallKind, prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, ?]]] = {
    attempt[Source[ChatResponseChunk, ?]](attrs, streaming = true) { (target, client) =>
      // the deadline of the first token is per target: one that does not answer in time leaves the call to the next
      val call = FirstTokenTimeout.of(provider.connection) match {
        case Some(ttft) => FirstTokenTimeout.guard(s"target '${target.id}' of provider '${provider.name}'", ttft, attrs)(client.invokeStream(kind, prompt, attrs, originalBody))
        case None => client.invokeStream(kind, prompt, attrs, originalBody)
      }
      call.transform {
        // a stream is in progress until it ends, whoever ends it
        case Success(Right(source)) => Success(Right(source.watchTermination() { (mat, done) =>
          done.onComplete(_ => TargetSelection.ended(provider, target))
          mat
        }))
        case other =>
          TargetSelection.ended(provider, target)
          other
      }
    }
  }

  private def choose(tried: Set[String], now: Long): Option[ProviderTarget] = {
    val remaining = targets.filterNot(t => tried.contains(t.id))
    val available = remaining.filter(t => TargetSelection.usable(provider, t, now))
    if (available.nonEmpty) TargetSelection.pick(provider, available, options.strategy, now)
    else if (options.trySaturated) TargetSelection.pick(provider, remaining.filter(t => TargetSelection.usable(provider, t, now, allowSaturated = true)), options.strategy, now)
    else None
  }

  private def recordFailure(target: ProviderTarget, settings: CircuitBreakerSettings)(using env: Env): Unit = {
    val now = System.currentTimeMillis()
    val key = TargetSelection.breakerKey(provider.id, target.id)
    TargetSelection.quotaOf(provider, target).filter(_ => settings.openOnQuota) match {
      case Some(episode) => ProviderCircuitBreaker.openUntil(key, ProviderCircuitBreaker.quotaBackoffUntil(episode, settings, now))
      case None => if (settings.enabled) ProviderCircuitBreaker.recordFailure(key, now, settings)
    }
  }

  // The same rule as the fallback: any error but a model the provider refuses, and any exception, leads to the next
  // target. A stream is only taken over before anything was sent to the caller: when its future failed
  private def attempt[T](attrs: TypedMap, streaming: Boolean)(op: (ProviderTarget, ChatClient) => Future[Either[JsValue, T]])(using ec: ExecutionContext, env: Env): Future[Either[JsValue, T]] = {
    val settings = CircuitBreakerSettings.fromProvider(provider)
    ProviderTargetAttempts.clear(attrs, provider.id)

    def loop(tried: Set[String], attempts: Int, last: Option[Either[Throwable, JsValue]]): Future[Either[JsValue, T]] = {
      def giveUp(): Future[Either[JsValue, T]] = last match {
        case Some(Left(e)) => Future.failed(e)
        case Some(Right(err)) => err.leftf
        case None =>
          ProviderTargetsAlerts.noneAvailable(provider)
          TargetsChatClient.noTargetAvailable(provider).leftf
      }
      if (attempts >= options.maxAttempts) giveUp()
      else choose(tried, System.currentTimeMillis()) match {
        case None => giveUp()
        case Some(target) => clientOf(target) match {
          case None =>
            loop(tried + target.id, attempts + 1, Some(Right(Json.obj("error" -> s"unable to build the client of target '${target.id}'"))))
          case Some(client) =>
            ProviderTargetAttempts.record(attrs, provider.id, target, attempts + 1)
            TargetSelection.started(provider, target)
            Try(op(target, client)).fold(e => {
              TargetSelection.ended(provider, target)
              Future.failed(e)
            }, identity).transformWith {
              case Success(Left(err)) if ModelConstraints.isDenied(err) => err.leftf
              case Success(Left(err)) =>
                recordFailure(target, settings)
                loop(tried + target.id, attempts + 1, Some(Right(err)))
              case Success(Right(value)) =>
                if (settings.active) ProviderCircuitBreaker.recordSuccess(TargetSelection.breakerKey(provider.id, target.id))
                ProviderTargetsAlerts.served(provider)
                value.rightf
              case Failure(e) =>
                recordFailure(target, settings)
                loop(tried + target.id, attempts + 1, Some(Left(e)))
            }
        }
      }
    }

    loop(Set.empty, 0, None)
  }
}
