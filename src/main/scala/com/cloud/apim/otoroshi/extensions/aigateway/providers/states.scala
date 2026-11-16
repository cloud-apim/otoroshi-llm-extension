package com.cloud.apim.otoroshi.extensions.aigateway.providers

import com.cloud.apim.otoroshi.extensions.aigateway.decorators.ProviderCircuitBreaker
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import org.apache.pekko.actor.Cancellable
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import otoroshi.actions.ApiActionContextCapable
import otoroshi.cluster.Cluster
import otoroshi.env.Env
import otoroshi.events.{Audit, AuditEvent}
import otoroshi.models.ApiKey
import otoroshi.next.extensions.{AdminExtensionAdminApiRoute, AdminExtensionRouterContext}
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.*
import play.api.libs.ws.WSAuthScheme
import play.api.mvc.{RequestHeader, Result, Results}

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/** What a monitoring tool says of a server: Otoroshi only knows these, the tool translates its own metrics. */
sealed abstract class TargetStatus(val name: String) {
  def json: JsValue = JsString(name)
}

object TargetStatus {
  case object Available extends TargetStatus("available")
  case object Saturated extends TargetStatus("saturated")
  case object Draining extends TargetStatus("draining")
  case object Down extends TargetStatus("down")
  val all: Seq[TargetStatus] = Seq(Available, Saturated, Draining, Down)
  def fromName(name: String): Option[TargetStatus] = all.find(_.name == name.trim.toLowerCase)
}

/** `pushed_state` of a provider: whether a monitoring tool may push the state of its targets, or its own. */
final case class PushedStateSettings(enabled: Boolean = false, staleAs: TargetStatus = TargetStatus.Available, maxTtl: Long = PushedStateSettings.defaultMaxTtl) {
  def json: JsValue = PushedStateSettings.format.writes(this)
}

object PushedStateSettings {

  val defaultMaxTtl: Long = 600000L
  val minTtl: Long = 1000L
  private val staleStatuses: Set[TargetStatus] = Set(TargetStatus.Available, TargetStatus.Down)

  val format: Format[PushedStateSettings] = new Format[PushedStateSettings] {
    override def writes(o: PushedStateSettings): JsValue = Json.obj("enabled" -> o.enabled, "stale_as" -> o.staleAs.name, "max_ttl" -> o.maxTtl)
    override def reads(json: JsValue): JsResult[PushedStateSettings] = Try {
      PushedStateSettings(
        enabled = json.select("enabled").asOpt[Boolean].getOrElse(false),
        staleAs = json.select("stale_as").asOpt[String].flatMap(TargetStatus.fromName).filter(staleStatuses.contains).getOrElse(TargetStatus.Available),
        maxTtl = json.select("max_ttl").asOpt[Long].getOrElse(defaultMaxTtl).max(minTtl),
      )
    }.fold(e => JsError(e.getMessage), JsSuccess(_))
  }

  def validateJson(json: JsValue): Either[String, Unit] = json.select("pushed_state").asOpt[JsValue] match {
    case None | Some(JsNull) => Right(())
    case Some(o: JsObject) => Seq(
      o.select("stale_as").asOpt[String].filterNot(s => TargetStatus.fromName(s).exists(staleStatuses.contains))
        .map(s => s"pushed_state.stale_as must be 'available' or 'down', not '$s'"),
      o.select("max_ttl").asOpt[JsValue].collect {
        case JsNumber(n) if !(n.isValidLong && n >= minTtl) => s"pushed_state.max_ttl must be a number of ms greater or equal to $minTtl"
      },
    ).flatten.headOption.toLeft(())
    case Some(_) => Left("pushed_state must be an object")
  }
}

/** A state pushed for a provider (`target` empty) or one of its targets, until `expiresAt` on the local clock. */
final case class PushedState(
  providerId: String,
  target: Option[String],
  status: TargetStatus,
  load: Option[Double],
  reason: Option[String],
  expiresAt: Long,
  author: Option[String],
  receivedAt: Long,
) {
  def expiresIn(now: Long): Long = (expiresAt - now).max(0L)
  // what the read api shows
  def json(now: Long): JsObject = Json.obj(
    "status" -> status.name,
    "load" -> load.map(JsNumber(_)).getOrElse(JsNull).asValue,
    "reason" -> reason.map(JsString.apply).getOrElse(JsNull).asValue,
    "expires_in" -> expiresIn(now),
  )
  // what is stored, and sent from a leader to its workers with what is left of the ttl
  def wire(now: Long): JsObject = Json.obj(
    "provider" -> providerId,
    "target" -> target.map(JsString.apply).getOrElse(JsNull).asValue,
    "status" -> status.name,
    "load" -> load.map(JsNumber(_)).getOrElse(JsNull).asValue,
    "reason" -> reason.map(JsString.apply).getOrElse(JsNull).asValue,
    "author" -> author.map(JsString.apply).getOrElse(JsNull).asValue,
    "ttl" -> expiresIn(now),
  )
}

object PushedState {
  // a state read from a leader or the datastore: it expires `ttl` ms after it was received, on the local clock, so the
  // clocks of the nodes do not have to agree
  def fromWire(json: JsValue, ttl: Long, receivedAt: Long): Option[PushedState] = for {
    provider <- json.select("provider").asOpt[String]
    status <- json.select("status").asOpt[String].flatMap(TargetStatus.fromName)
  } yield PushedState(
    providerId = provider,
    target = json.select("target").asOpt[String],
    status = status,
    load = json.select("load").asOpt[Double],
    reason = json.select("reason").asOpt[String],
    expiresAt = receivedAt + ttl,
    author = json.select("author").asOpt[String],
    receivedAt = receivedAt,
  )
}

/**
 * The states pushed by the monitoring tools, as this node knows them. The selection of a target reads them here,
 * in memory. Leaders refresh the table from the datastore, workers from a leader (`ProviderStatesAgent`).
 */
object ProviderStates {

  val ProviderSlot = "@provider"

  private val table = new UnboundedTrieMap[(String, String), PushedState]()

  private def slot(target: Option[String]): String = target.getOrElse(ProviderSlot)

  def get(providerId: String, target: Option[String], now: Long): Option[PushedState] = {
    if (table.isEmpty) None else table.get((providerId, slot(target))).filter(_.expiresAt > now)
  }

  def all(now: Long): Seq[PushedState] = table.values.toSeq.filter(_.expiresAt > now)

  def put(state: PushedState): Unit = table.put((state.providerId, slot(state.target)), state)

  def remove(providerId: String, target: Option[String]): Unit = table.remove((providerId, slot(target)))

  def removeProvider(providerId: String): Unit = table.keySet.toSeq.filter(_._1 == providerId).foreach(table.remove)

  /** what was read at `startedAt` replaces the table, except what this node received in the meantime */
  def replace(states: Seq[PushedState], startedAt: Long): Unit = {
    val fresh = states.map(s => ((s.providerId, slot(s.target)), s)).toMap
    table.foreach { case (key, current) =>
      if (!fresh.contains(key) && current.receivedAt < startedAt) table.remove(key)
    }
    fresh.foreach { case (key, state) =>
      if (!table.get(key).exists(_.receivedAt >= startedAt)) table.put(key, state)
    }
    val now = System.currentTimeMillis()
    table.foreach { case (key, state) => if (state.expiresAt <= now) table.remove(key) }
  }

  def clear(): Unit = table.clear()

  /**
   * The status the selection goes by: the one pushed while it holds, `stale_as` once it expired or before any push.
   * On a provider with targets, the targets are what is watched: no state for the whole provider means it takes
   * calls, otherwise a tool pushing the state of its targets only would leave it `down` with `stale_as: down`.
   */
  def statusOf(provider: AiProvider, target: Option[String], now: Long): TargetStatus = provider.pushedState.filter(_.enabled) match {
    case None => TargetStatus.Available
    case Some(settings) => get(provider.id, target, now).map(_.status).getOrElse {
      if (target.isEmpty && ProviderTargets.has(provider)) TargetStatus.Available else settings.staleAs
    }
  }

  /** the state of a whole provider, as the fallback and the load balancer read it */
  def providerAccepts(provider: AiProvider, now: Long): Boolean = statusOf(provider, None, now) match {
    case TargetStatus.Available => true
    case TargetStatus.Saturated => ProviderTargetsOptions.from(provider.connection).trySaturated
    case _ => false
  }

  /** whether a provider is worth a call: the provider accepts it and, with targets, one of them is usable */
  def selectable(provider: AiProvider, now: Long): Boolean = {
    providerAccepts(provider, now) && (!ProviderTargets.has(provider) || TargetSelection.anyUsable(provider, now))
  }
}

object ProviderStatesStore {

  def prefix(providerId: String)(using env: Env): String = s"${env.storageRoot}:extensions:${AiExtension.id.cleanup}:provider-states:${providerId}"

  // an id can be an url: neither its length nor its characters go in the key, its hash does
  def segment(target: Option[String]): String = target match {
    case None => ProviderStates.ProviderSlot
    case Some(id) =>
      val digest = java.security.MessageDigest.getInstance("SHA-256").digest(id.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      digest.map(b => f"$b%02x").mkString.take(32)
  }

  def key(providerId: String, target: Option[String])(using env: Env): String = s"${prefix(providerId)}:${segment(target)}"

  def write(state: PushedState, ttl: Long)(using ec: ExecutionContext, env: Env): Future[Boolean] = {
    env.datastores.rawDataStore.set(key(state.providerId, state.target), ByteString(state.wire(System.currentTimeMillis()).stringify), Some(ttl))
  }

  def delete(providerId: String, targets: Seq[Option[String]])(using ec: ExecutionContext, env: Env): Future[Long] = {
    env.datastores.rawDataStore.del(targets.map(t => key(providerId, t)))
  }

  // the keys the states of a provider can be at: one per declared target and one for the provider. No scan of the keys
  def slots(provider: AiProvider): Seq[Option[String]] = None +: ProviderTargets.of(provider).map(t => Some(t.id))

  /** the states stored for the providers that accept them, with what is left of their ttl */
  def readAll(providers: Seq[AiProvider])(using ec: ExecutionContext, env: Env): Future[Seq[PushedState]] = {
    val keys = providers.flatMap(p => slots(p).map(t => key(p.id, t)))
    if (keys.isEmpty) Seq.empty.vfuture
    else env.datastores.rawDataStore.mget(keys).flatMap { values =>
      val found = keys.zip(values).collect { case (k, Some(v)) => (k, v) }
      Future.sequence(found.map { case (k, v) =>
        env.datastores.rawDataStore.pttl(k).map { ttl =>
          val now = System.currentTimeMillis()
          Try(Json.parse(v.utf8String)).toOption.filter(_ => ttl > 0L).flatMap(json => PushedState.fromWire(json, ttl, now))
        }
      }).map(_.flatten)
    }
  }
}

/**
 * Refreshes the table of the states. Leaders (and a node alone) read the datastore, workers ask a leader: a state
 * pushed on a leader reaches a worker in a few seconds, instead of the twenty of the cluster state. The keys are
 * part of that state all the same, which is what workers converge on if this stops.
 */
object ProviderStatesAgent {

  private val schedulerRef = new AtomicReference[Cancellable]()
  private val counter = new AtomicInteger(0)

  val clusterPath = "/api/extensions/cloud-apim/extensions/ai-extension/cluster/provider-states"

  def start(env: Env, ext: AiExtension): Unit = {
    given ec: ExecutionContext = env.otoroshiExecutionContext
    val every = ext.providerStatesRefreshEvery
    schedulerRef.set(env.otoroshiScheduler.scheduleAtFixedRate(every, every) { () =>
      Try(refresh(env, ext)).recover {
        case err => AiExtension.logger.error("error while refreshing the provider states", err)
      }
      ()
    })
  }

  def stop(): Unit = Option(schedulerRef.get()).foreach(_.cancel())

  def refresh(env: Env, ext: AiExtension): Future[Unit] = {
    given ev: Env = env
    given ec: ExecutionContext = env.otoroshiExecutionContext
    val providers = ext.states.allProviders().filter(_.pushedState.exists(_.enabled))
    val startedAt = System.currentTimeMillis()
    if (providers.isEmpty) {
      ProviderStates.clear()
      ().vfuture
    } else if (env.clusterConfig.mode.isWorker) {
      fromLeader(env).map {
        case Some(states) => ProviderStates.replace(states, startedAt)
        case None => ()
      }
    } else {
      ProviderStatesStore.readAll(providers).map(states => ProviderStates.replace(states, startedAt))
    }
  }

  private def leaderUrl(env: Env): String = {
    val config = env.clusterConfig
    val count = counter.incrementAndGet() % (if (config.leader.urls.nonEmpty) config.leader.urls.size else 1)
    config.leader.urls.zipWithIndex.find(t => t._2 == count).map(_._1).getOrElse(config.leader.urls.head)
  }

  private def fromLeader(env: Env): Future[Option[Seq[PushedState]]] = {
    import otoroshi.utils.http.Implicits.*
    given ec: ExecutionContext = env.otoroshiExecutionContext
    val config = env.clusterConfig
    env.MtlsWs
      .url(leaderUrl(env) + clusterPath, config.mtlsConfig)
      .withHttpHeaders("Host" -> config.leader.host)
      .withAuth(config.leader.clientId, config.leader.clientSecret, WSAuthScheme.BASIC)
      .withRequestTimeout(Duration(config.worker.timeout, TimeUnit.MILLISECONDS))
      .withMaybeProxyServer(config.proxy)
      .get()
      .map { resp =>
        if (resp.status == 200) {
          val receivedAt = System.currentTimeMillis()
          Some(Json.parse(resp.body).select("states").asOpt[Seq[JsObject]].getOrElse(Seq.empty).flatMap { json =>
            json.select("ttl").asOpt[Long].filter(_ > 0L).flatMap(ttl => PushedState.fromWire(json, ttl, receivedAt))
          })
        } else {
          if (Cluster.logger.isDebugEnabled) Cluster.logger.debug(s"unable to read the provider states from a leader: ${resp.status}")
          None
        }
      }
      .recover { case e =>
        if (Cluster.logger.isDebugEnabled) Cluster.logger.debug(s"unable to read the provider states from a leader: ${e.getMessage}")
        None
      }
  }
}

/** A call of the provider states api, with the rights of its caller, read the way the admin api reads them. */
final case class ProviderStatesApiRequest(req: RequestHeader, apikey: ApiKey) extends ApiActionContextCapable {
  override def apiKey: ApiKey = apikey
  override def request: RequestHeader = req
}

/**
 * The admin api a monitoring tool pushes the state of the targets of a provider with. A push changes the state of
 * declared targets and nothing else: no url, no weight, no token. Whoever could push an url would receive the
 * prompts and the token of the provider.
 */
class ProviderStatesApi(env: Env, ext: AiExtension) {

  private given ev: Env = env
  private given ec: ExecutionContext = env.otoroshiExecutionContext
  private given mat: Materializer = env.otoroshiMaterializer

  private def readBody(body: Option[Source[ByteString, ?]]): Future[Option[JsValue]] = body match {
    case None => Future.successful(None)
    case Some(source) => source.runFold(ByteString.empty)(_ ++ _).map(bs => Try(Json.parse(bs.utf8String)).toOption)
  }

  private def nodeName: String = {
    if (env.clusterConfig.mode.isWorker) env.clusterConfig.worker.name
    else if (env.clusterConfig.mode.isLeader) env.clusterConfig.leader.name
    else "standalone"
  }

  private def author(call: ProviderStatesApiRequest): Option[String] = {
    call.user.flatMap(_.select("email").asOpt[String]).orElse(Option(call.apikey.clientName).filter(_.nonEmpty)).orElse(Some(call.apikey.clientId))
  }

  // a provider the caller cannot write does not exist for a push: nothing says whether it does
  private def writable(providerId: String, call: ProviderStatesApiRequest): Option[AiProvider] = {
    ext.states.provider(providerId).filter(p => call.canUserWriteJson(p.json))
  }

  private def readable(providerId: String, call: ProviderStatesApiRequest): Option[AiProvider] = {
    ext.states.provider(providerId).filter(p => call.canUserReadJson(p.json))
  }

  /** checks one element of a push, in the context of its provider */
  private def accept(item: JsValue, providerOf: Option[String] => Either[String, AiProvider], call: ProviderStatesApiRequest): Either[String, (AiProvider, PushedState, Long)] = {
    for {
      obj <- item.asOpt[JsObject].toRight("a state must be an object")
      provider <- providerOf(obj.select("provider").asOpt[String])
      settings <- provider.pushedState.filter(_.enabled).toRight("pushed state is not enabled on this provider")
      target <- obj.select("target").asOpt[JsValue] match {
        case None | Some(JsNull) => Right(None)
        case Some(JsString(id)) if ProviderTargets.of(provider).exists(_.id == id) => Right(Some(id))
        case Some(JsString(id)) => Left(s"unknown target '$id'")
        case Some(_) => Left("target must be a string")
      }
      status <- obj.select("status").asOpt[String].flatMap(TargetStatus.fromName).toRight("status must be one of available, saturated, draining, down")
      load <- obj.select("load").asOpt[JsValue] match {
        case None | Some(JsNull) => Right(None)
        case Some(JsNumber(n)) if n >= 0 && n <= 1 => Right(Some(n.toDouble))
        case Some(_) => Left("load must be a number between 0 and 1")
      }
      ttl <- obj.select("ttl").asOpt[JsValue] match {
        case Some(JsNumber(n)) if n.isValidLong && n >= PushedStateSettings.minTtl && n <= settings.maxTtl => Right(n.toLong)
        case _ => Left(s"ttl must be a number of ms between ${PushedStateSettings.minTtl} and ${settings.maxTtl}")
      }
      reason <- obj.select("reason").asOpt[JsValue] match {
        case None | Some(JsNull) => Right(None)
        case Some(JsString(r)) if r.length <= 256 => Right(Some(r))
        case Some(_) => Left("reason must be a string of 256 characters at most")
      }
    } yield {
      val now = System.currentTimeMillis()
      (provider, PushedState(provider.id, target, status, load, reason, now + ttl, author(call), now), ttl)
    }
  }

  private def push(items: Seq[JsValue], providerOf: Option[String] => Either[String, AiProvider], call: ProviderStatesApiRequest): Future[Result] = {
    val results = items.zipWithIndex.map { case (item, index) => (index, accept(item, providerOf, call)) }
    val accepted = results.collect { case (_, Right(v)) => v }
    val rejected = results.collect { case (index, Left(error)) => Json.obj("index" -> index, "error" -> error) }
    Future.sequence(accepted.map { case (provider, state, ttl) =>
      val now = System.currentTimeMillis()
      val previous = ProviderStates.get(state.providerId, state.target, now).map(_.status)
      ProviderStates.put(state)
      if (!previous.contains(state.status)) {
        Audit.send(AuditEvent.generic("ProviderStateChanged")(Json.obj(
          "provider_id" -> provider.id,
          "provider_name" -> provider.name,
          "target" -> state.target.map(JsString.apply).getOrElse(JsNull).asValue,
          "previous_status" -> previous.map(_.json).getOrElse(JsNull).asValue,
          "status" -> state.status.name,
          "reason" -> state.reason.map(JsString.apply).getOrElse(JsNull).asValue,
          "author" -> state.author.map(JsString.apply).getOrElse(JsNull).asValue,
        )))
      }
      ProviderStatesStore.write(state, ttl)
    }).map { _ =>
      Results.Ok(Json.obj("accepted" -> accepted.size, "rejected" -> JsArray(rejected)))
    }
  }

  def handlePushOne(ctx: AdminExtensionRouterContext[AdminExtensionAdminApiRoute], req: RequestHeader, apikey: ApiKey, body: Option[Source[ByteString, ?]]): Future[Result] = {
    val call = ProviderStatesApiRequest(req, apikey)
    val providerId = ctx.named("id").getOrElse("--")
    readBody(body).flatMap {
      case Some(json) if json.select("states").asOpt[JsArray].isDefined =>
        writable(providerId, call) match {
          case None => Results.Forbidden(Json.obj("error" -> "forbidden")).vfuture
          case Some(provider) => push(json.select("states").as[JsArray].value.toSeq, _ => Right(provider), call)
        }
      case _ => Results.BadRequest(Json.obj("error" -> "the body must be a json object with a 'states' array")).vfuture
    }
  }

  def handlePushMany(ctx: AdminExtensionRouterContext[AdminExtensionAdminApiRoute], req: RequestHeader, apikey: ApiKey, body: Option[Source[ByteString, ?]]): Future[Result] = {
    val call = ProviderStatesApiRequest(req, apikey)
    readBody(body).flatMap {
      case Some(json) if json.select("states").asOpt[JsArray].isDefined =>
        push(json.select("states").as[JsArray].value.toSeq, {
          case None => Left("provider is missing")
          case Some(id) => writable(id, call).toRight("forbidden")
        }, call)
      case _ => Results.BadRequest(Json.obj("error" -> "the body must be a json object with a 'states' array")).vfuture
    }
  }

  def handleRead(ctx: AdminExtensionRouterContext[AdminExtensionAdminApiRoute], req: RequestHeader, apikey: ApiKey, body: Option[Source[ByteString, ?]]): Future[Result] = {
    val call = ProviderStatesApiRequest(req, apikey)
    readable(ctx.named("id").getOrElse("--"), call) match {
      case None => Results.NotFound(Json.obj("error" -> "provider not found")).vfuture
      case Some(provider) => Results.Ok(view(provider, System.currentTimeMillis())).vfuture
    }
  }

  def handleDelete(ctx: AdminExtensionRouterContext[AdminExtensionAdminApiRoute], req: RequestHeader, apikey: ApiKey, body: Option[Source[ByteString, ?]]): Future[Result] = {
    val call = ProviderStatesApiRequest(req, apikey)
    val providerId = ctx.named("id").getOrElse("--")
    readable(providerId, call) match {
      case None => Results.NotFound(Json.obj("error" -> "provider not found")).vfuture
      case Some(provider) if !call.canUserWriteJson(provider.json) => Results.Forbidden(Json.obj("error" -> "forbidden")).vfuture
      case Some(provider) =>
        val slots = req.getQueryString("target") match {
          case Some(target) => Seq(Some(target))
          case None => ProviderStatesStore.slots(provider)
        }
        slots.foreach(t => ProviderStates.remove(provider.id, t))
        ProviderStatesStore.delete(provider.id, slots).map(count => Results.Ok(Json.obj("deleted" -> count)))
    }
  }

  // what decides the selection on the node that answers
  def view(provider: AiProvider, now: Long): JsObject = {
    val settings = provider.pushedState.filter(_.enabled)
    def pushed(target: Option[String]): JsValue = settings match {
      case None => JsNull
      case Some(_) => ProviderStates.get(provider.id, target, now).map(_.json(now))
        .getOrElse(Json.obj("status" -> ProviderStates.statusOf(provider, target, now).name, "stale" -> true))
    }
    Json.obj(
      "provider" -> Json.obj(
        "pushed" -> pushed(None),
        "circuit" -> (if (ProviderCircuitBreaker.isOpen(provider.id, now)) "open" else "closed"),
        "usable" -> ProviderStates.selectable(provider, now),
      ),
      "targets" -> JsArray(ProviderTargets.of(provider).map { target =>
        target.json ++ Json.obj(
          "pushed" -> pushed(Some(target.id)),
          "circuit" -> (if (ProviderCircuitBreaker.isOpen(TargetSelection.breakerKey(provider.id, target.id), now)) "open" else "closed"),
          "quota" -> TargetSelection.quotaOf(provider, target).map(_.json).getOrElse(JsNull).asValue,
          "in_flight" -> TargetSelection.inFlightOf(provider, target),
          "usable" -> TargetSelection.usable(provider, target, now),
        )
      }),
      "node" -> nodeName,
    )
  }

  // what a leader gives its workers: every state it knows, with what is left of its ttl
  def handleCluster(ctx: AdminExtensionRouterContext[AdminExtensionAdminApiRoute], req: RequestHeader, apikey: ApiKey, body: Option[Source[ByteString, ?]]): Future[Result] = {
    val now = System.currentTimeMillis()
    Results.Ok(Json.obj("states" -> JsArray(ProviderStates.all(now).map(_.wire(now))))).vfuture
  }

  def routes: Seq[AdminExtensionAdminApiRoute] = {
    val base = "/api/extensions/cloud-apim/extensions/ai-extension"
    Seq(
      AdminExtensionAdminApiRoute(method = "PUT", path = s"$base/providers/:id/states", wantsBody = true, handle = handlePushOne),
      AdminExtensionAdminApiRoute(method = "GET", path = s"$base/providers/:id/states", wantsBody = false, handle = handleRead),
      AdminExtensionAdminApiRoute(method = "DELETE", path = s"$base/providers/:id/states", wantsBody = false, handle = handleDelete),
      AdminExtensionAdminApiRoute(method = "PUT", path = s"$base/providers-states", wantsBody = true, handle = handlePushMany),
      AdminExtensionAdminApiRoute(method = "GET", path = ProviderStatesAgent.clusterPath, wantsBody = false, handle = handleCluster),
    )
  }
}
