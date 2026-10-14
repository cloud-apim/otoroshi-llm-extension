package com.cloud.apim.otoroshi.extensions.aigateway.entities

import org.apache.pekko.actor.Cancellable
import org.apache.pekko.http.scaladsl.util.FastFuture
import com.cloud.apim.otoroshi.extensions.aigateway.decorators.ChatClientWithAuding
import org.joda.time.DateTime
import otoroshi.api.*
import otoroshi.cluster.Cluster
import otoroshi.env.Env
import otoroshi.events.AlertEvent
import otoroshi.gateway.Retry
import otoroshi.models.*
import otoroshi.next.extensions.AdminExtensionId
import otoroshi.next.utils.JsonHelpers
import otoroshi.security.IdGenerator
import otoroshi.storage.*
import otoroshi.utils.syntax.implicits.*
import otoroshi.utils.{JsonPathValidator, RegexPool, TypedMap}
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.*
import play.api.libs.json.*
import play.api.libs.ws.WSAuthScheme
import play.api.libs.ws.WSBodyWritables.given

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.*
import scala.collection.concurrent.TrieMap
import scala.concurrent.*
import scala.concurrent.duration.*
import scala.util.{Failure, Success, Try}
import org.apache.pekko.actor.Scheduler
import org.apache.pekko.util.ByteString
import play.api.libs.typedmap.{TypedEntry, TypedKey}

object AiBudgetClusterAgent {
  val totalUsdCounters = new TrieMap[String, DoubleAdder]()
  val totalTokensCounters = new TrieMap[String, AtomicLong]()

  val inferenceUsdCounters = new TrieMap[String, DoubleAdder]()
  val inferenceTokensCounters = new TrieMap[String, AtomicLong]()

  val imageUsdCounters = new TrieMap[String, DoubleAdder]()
  val imageTokensCounters = new TrieMap[String, AtomicLong]()

  val audioUsdCounters = new TrieMap[String, DoubleAdder]()
  val audioTokensCounters = new TrieMap[String, AtomicLong]()

  val videoUsdCounters = new TrieMap[String, DoubleAdder]()
  val videoTokensCounters = new TrieMap[String, AtomicLong]()

  val embeddingUsdCounters = new TrieMap[String, DoubleAdder]()
  val embeddingTokensCounters = new TrieMap[String, AtomicLong]()

  val moderationUsdCounters = new TrieMap[String, DoubleAdder]()
  val moderationTokensCounters = new TrieMap[String, AtomicLong]()

  val ocrUsdCounters = new TrieMap[String, DoubleAdder]()
  val ocrPagesCounters = new TrieMap[String, AtomicLong]()

  val decisionUsdCounters = new TrieMap[String, DoubleAdder]()
  val decisionTokensCounters = new TrieMap[String, AtomicLong]()

  // what the budgets counting their consumers apart consumed here, by budget, cycle, partition and counter (`total_usd`...)
  val partitionedUsdCounters = new TrieMap[(String, String, String, String), DoubleAdder]()
  val partitionedUnitCounters = new TrieMap[(String, String, String, String), AtomicLong]()

  private val schedulerRef = new AtomicReference[Cancellable]()
  private val counter = new AtomicInteger(0)

  def start(env: Env, ext: AiExtension): Unit = {
    if (env.clusterConfig.mode.isWorker) {
      given ec: ExecutionContext = env.otoroshiExecutionContext
      schedulerRef.set(env.otoroshiScheduler.scheduleAtFixedRate(10.seconds, env.clusterConfig.worker.state.pollEvery.millis) { () =>
        sendDeltas(env, ext)
      })
    }
  }

  def stop(): Unit = {
    Option(schedulerRef.get()).foreach(_.cancel())
  }

  private val usdCounters: Seq[(String, TrieMap[String, DoubleAdder])] = Seq(
    "total_usd" -> totalUsdCounters, "inference_usd" -> inferenceUsdCounters, "image_usd" -> imageUsdCounters,
    "audio_usd" -> audioUsdCounters, "video_usd" -> videoUsdCounters, "embedding_usd" -> embeddingUsdCounters,
    "moderation_usd" -> moderationUsdCounters, "ocr_usd" -> ocrUsdCounters, "decision_usd" -> decisionUsdCounters,
  )
  private val unitCounters: Seq[(String, TrieMap[String, AtomicLong])] = Seq(
    "total_tokens" -> totalTokensCounters, "inference_tokens" -> inferenceTokensCounters, "image_tokens" -> imageTokensCounters,
    "audio_tokens" -> audioTokensCounters, "video_tokens" -> videoTokensCounters, "embedding_tokens" -> embeddingTokensCounters,
    "moderation_tokens" -> moderationTokensCounters, "ocr_pages" -> ocrPagesCounters, "decision_tokens" -> decisionTokensCounters,
  )

  // the counters a worker pushes, as a push names them
  val counters: Seq[String] = usdCounters.map(_._1) ++ unitCounters.map(_._1)

  // a counter the leaders are up to date with. Dollars are added as doubles: taking off what was pushed may leave
  // dust, far below the billionth of a dollar the leaders count in
  private def settled(usd: Double): Boolean = math.abs(usd) < 1e-10

  // what was consumed here and the leaders do not know yet: for each counter, by budget and cycle. What the budgets
  // counting their consumers apart consumed goes in a section of its own, only there when there is something in it
  def deltas(): JsObject = JsObject(
    usdCounters.map { case (name, counters) => name -> JsObject(counters.toMap.view.mapValues(_.sum).filterNot(c => settled(c._2)).mapValues(_.json).toMap) } ++
    unitCounters.map { case (name, counters) => name -> JsObject(counters.toMap.view.mapValues(_.get).filter(_._2 != 0L).mapValues(_.json).toMap) }
  ) ++ (partitionedDeltas() match {
    case entries if entries.isEmpty => Json.obj()
    case entries                    => Json.obj("partitioned" -> JsArray(entries))
  })

  // one entry by budget, cycle and partition, with what each of its counters consumed: the partition is never read
  // out of a key, whatever it holds
  def partitionedDeltas(): Seq[JsObject] = {
    val usd = partitionedUsdCounters.toSeq.map { case (key, adder) => (key, adder.sum) }.filterNot(c => settled(c._2)).map { case (key, value) => (key, value.json) }
    val units = partitionedUnitCounters.toSeq.map { case (key, counter) => (key, counter.get) }.filter(_._2 != 0L).map { case (key, value) => (key, value.json) }
    (usd ++ units).groupBy { case ((budget, cycle, partition, _), _) => (budget, cycle, partition) }.toSeq.sortBy(_._1).map { case ((budget, cycle, partition), values) =>
      Json.obj(
        "budget" -> budget,
        "cycle" -> cycle,
        "partition" -> partition,
        "counters" -> JsObject(values.map { case ((_, _, _, counter), value) => counter -> value }),
      )
    }
  }

  // The leaders counted what was pushed: it is taken off the counters, which keep what calls consumed while the push
  // was on its way. Clearing them here would lose it. A counter back to zero is dropped once its cycle is over
  // (`current` tells it, from its `<budget>:<cycle>` key): nothing is added to it any more.
  def acknowledge(pushed: JsObject, current: String => Boolean): Unit = {
    def counted(name: String): scala.collection.Map[String, JsValue] = pushed.select(name).asOpt[JsObject].map(_.value).getOrElse(Map.empty[String, JsValue])
    usdCounters.foreach { case (name, counters) =>
      counted(name).foreach { case (key, value) =>
        counters.get(key).foreach { counter =>
          counter.add(-value.as[Double])
          if (settled(counter.sum) && !current(key)) counters.remove(key, counter)
        }
      }
    }
    unitCounters.foreach { case (name, counters) =>
      counted(name).foreach { case (key, value) =>
        counters.get(key).foreach { counter =>
          if (counter.addAndGet(-value.as[Long]) == 0L && !current(key)) counters.remove(key, counter)
        }
      }
    }
  }

  // What the leaders counted of the `partitioned` section, once they said they read it: a leader of a previous version
  // ignores it, and what it held is pushed again until a leader reads it
  def acknowledgePartitioned(pushed: JsObject, current: (String, String) => Boolean): Unit = {
    pushed.select("partitioned").asOpt[Seq[JsObject]].getOrElse(Seq.empty).foreach { entry =>
      val budget = entry.select("budget").asOpt[String].getOrElse("")
      val cycle = entry.select("cycle").asOpt[String].getOrElse("")
      val partition = entry.select("partition").asOpt[String].getOrElse("")
      entry.select("counters").asOpt[JsObject].map(_.value).getOrElse(Map.empty[String, JsValue]).foreach { case (counter, value) =>
        val key = (budget, cycle, partition, counter)
        partitionedUsdCounters.get(key).foreach { adder =>
          adder.add(-value.as[Double])
          if (settled(adder.sum) && !current(budget, cycle)) partitionedUsdCounters.remove(key, adder)
        }
        partitionedUnitCounters.get(key).foreach { units =>
          if (units.addAndGet(-value.as[Long]) == 0L && !current(budget, cycle)) partitionedUnitCounters.remove(key, units)
        }
      }
    }
    // a partition stops consuming without its cycle being over: once that cycle is, its counters are dropped too
    partitionedUsdCounters.foreach { case (key, adder) => if (settled(adder.sum) && !current(key._1, key._2)) partitionedUsdCounters.remove(key, adder) }
    partitionedUnitCounters.foreach { case (key, units) => if (units.get == 0L && !current(key._1, key._2)) partitionedUnitCounters.remove(key, units) }
  }

  private def otoroshiUrl(env: Env): String = {
    val config = env.clusterConfig
    val count = counter.incrementAndGet() % (if (config.leader.urls.nonEmpty) config.leader.urls.size else 1)
    config.leader.urls.zipWithIndex.find(t => t._2 == count).map(_._1).getOrElse(config.leader.urls.head)
  }

  private def sendDeltas(env: Env, ext: AiExtension): Unit = {
    import otoroshi.utils.http.Implicits.*
    given ec: ExecutionContext = env.otoroshiExecutionContext
    given sc: Scheduler = env.otoroshiScheduler
    if (env.clusterConfig.mode.isWorker) {
      val config = env.clusterConfig
      val payload = deltas()
      Retry
        .retry(
          times = config.worker.retries,
          delay = config.retryDelay,
          factor = config.retryFactor,
          ctx = "leader-pushing-budgets-delta"
        ) { _ =>
          if (Cluster.logger.isDebugEnabled)
            Cluster.logger.debug(s"Pushing ai budgets deltas to leaders")
          env.MtlsWs
            .url(
              otoroshiUrl(env) + s"/api/extensions/cloud-apim/extensions/ai-extension/cluster/budgets/deltas",
              config.mtlsConfig
            )
            .withHttpHeaders(
              "Host"                                             -> config.leader.host,
            )
            .withAuth(config.leader.clientId, config.leader.clientSecret, WSAuthScheme.BASIC)
            .withRequestTimeout(Duration(config.worker.timeout, TimeUnit.MILLISECONDS))
            .withMaybeProxyServer(config.proxy)
            .put(payload)
            .map { resp =>
              if (resp.status == 200 && Cluster.logger.isDebugEnabled)
                Cluster.logger.debug(s"Ai budgets deltas has been pushed")
              if (resp.status == 200) {
                acknowledge(payload, key => ext.states.budget(key.take(key.lastIndexOf(":"))).exists(_.cycleId == key.drop(key.lastIndexOf(":") + 1)))
                if (Try(Json.parse(resp.body)).toOption.exists(_.select("partitioned").asOpt[Boolean].contains(true))) {
                  acknowledgePartitioned(payload, (budget, cycle) => ext.states.budget(budget).exists(_.cycleId == cycle))
                }
              }
              Some(Json.parse(resp.body))
            }
        }
        .recover { case _ =>
          if (Cluster.logger.isDebugEnabled)
            Cluster.logger.debug(
              s"[${env.clusterConfig.mode.name}] Error while pushing ai budgets deltas Otoroshi leader cluster"
            )
          None
        }
    } else {
      FastFuture.successful(None)
    }
  }
}

sealed trait AiBudgetUsageKind {
  def updateUsage(budget: AiBudget, cost: Option[BigDecimal], tokens: Option[Long], attrs: TypedMap, partition: Option[String] = None)(using ec: ExecutionContext, env: Env): Unit
}
object AiBudgetUsageKind {
  case object Inference extends AiBudgetUsageKind {
    def updateUsage(budget: AiBudget, cost: Option[BigDecimal], tokens: Option[Long], attrs: TypedMap, partition: Option[String] = None)(using ec: ExecutionContext, env: Env): Unit = {
      cost.foreach { c => 
        budget.incrTotalUsd(c, partition)
        budget.incrInferenceUsd(c, partition)
      }
      tokens.foreach { c => 
        budget.incrTotalTokens(c, partition)
        budget.incrInferenceTokens(c, partition)
      }
    }
  }
  case object Image extends AiBudgetUsageKind {
    def updateUsage(budget: AiBudget, cost: Option[BigDecimal], tokens: Option[Long], attrs: TypedMap, partition: Option[String] = None)(using ec: ExecutionContext, env: Env): Unit = {
      cost.foreach { c => 
        budget.incrTotalUsd(c, partition)
        budget.incrImageUsd(c, partition)
      }
      tokens.foreach { c => 
        budget.incrTotalTokens(c, partition)
        budget.incrImageTokens(c, partition)
      }
    }
  }
  case object Audio extends AiBudgetUsageKind {
    def updateUsage(budget: AiBudget, cost: Option[BigDecimal], tokens: Option[Long], attrs: TypedMap, partition: Option[String] = None)(using ec: ExecutionContext, env: Env): Unit = {
      cost.foreach { c => 
        budget.incrTotalUsd(c, partition)
        budget.incrAudioUsd(c, partition)
      }
      tokens.foreach { c => 
        budget.incrTotalTokens(c, partition)
        budget.incrAudioTokens(c, partition)
      }
    }
  }
  case object Video extends AiBudgetUsageKind {
    def updateUsage(budget: AiBudget, cost: Option[BigDecimal], tokens: Option[Long], attrs: TypedMap, partition: Option[String] = None)(using ec: ExecutionContext, env: Env): Unit = {
      cost.foreach { c => 
        budget.incrTotalUsd(c, partition)
        budget.incrVideoUsd(c, partition)
      }
      tokens.foreach { c => 
        budget.incrTotalTokens(c, partition)
        budget.incrVideoTokens(c, partition)
      }
    }
  }
  case object Embedding extends AiBudgetUsageKind {
    def updateUsage(budget: AiBudget, cost: Option[BigDecimal], tokens: Option[Long], attrs: TypedMap, partition: Option[String] = None)(using ec: ExecutionContext, env: Env): Unit = {
      cost.foreach { c => 
        budget.incrTotalUsd(c, partition)
        budget.incrEmbeddingUsd(c, partition)
      }
      tokens.foreach { c => 
        budget.incrTotalTokens(c, partition)
        budget.incrEmbeddingTokens(c, partition)
      }
    }
  }
  case object Moderation extends AiBudgetUsageKind {
    def updateUsage(budget: AiBudget, cost: Option[BigDecimal], tokens: Option[Long], attrs: TypedMap, partition: Option[String] = None)(using ec: ExecutionContext, env: Env): Unit = {
      cost.foreach { c => 
        budget.incrTotalUsd(c, partition)
        budget.incrModerationUsd(c, partition)
      }
      tokens.foreach { c => 
        budget.incrTotalTokens(c, partition)
        budget.incrModerationTokens(c, partition)
      }
    }
  }
  case object Ocr extends AiBudgetUsageKind {
    def updateUsage(budget: AiBudget, cost: Option[BigDecimal], tokens: Option[Long], attrs: TypedMap, partition: Option[String] = None)(using ec: ExecutionContext, env: Env): Unit = {
      cost.foreach { c =>
        budget.incrTotalUsd(c, partition)
        budget.incrOcrUsd(c, partition)
      }
      tokens.foreach { c =>
        budget.incrOcrPages(c, partition)
      }
    }
  }
  case object Decision extends AiBudgetUsageKind {
    def updateUsage(budget: AiBudget, cost: Option[BigDecimal], tokens: Option[Long], attrs: TypedMap, partition: Option[String] = None)(using ec: ExecutionContext, env: Env): Unit = {
      cost.foreach { c =>
        budget.incrTotalUsd(c, partition)
        budget.incrDecisionUsd(c, partition)
      }
      tokens.foreach { c =>
        budget.incrTotalTokens(c, partition)
        budget.incrDecisionTokens(c, partition)
      }
    }
  }
}

sealed trait AiBudgetDurationUnit {
  def json: JsValue = AiBudgetDurationUnit.format.writes(this)
}
object AiBudgetDurationUnit {

  case object Hour extends AiBudgetDurationUnit
  case object Day extends AiBudgetDurationUnit
  case object Year extends AiBudgetDurationUnit

  def fromString(s: String): AiBudgetDurationUnit = s match {
    case "hour" => Hour
    case "day" => Day
    case "year" => Year
    case _ => throw new IllegalArgumentException(s"Invalid AiBudgetDurationUnit: $s")
  }

  val format = new Format[AiBudgetDurationUnit] {
    override def writes(o: AiBudgetDurationUnit): JsValue = o match {
      case AiBudgetDurationUnit.Hour => JsString("hour")
      case AiBudgetDurationUnit.Day => JsString("day")
      case AiBudgetDurationUnit.Year => JsString("year")
    }
    override def reads(json: JsValue): JsResult[AiBudgetDurationUnit] = json match {
      case JsString("hour") => JsSuccess(AiBudgetDurationUnit.Hour)
      case JsString("day") => JsSuccess(AiBudgetDurationUnit.Day)
      case JsString("year") => JsSuccess(AiBudgetDurationUnit.Year)
      case _ => JsError("Invalid AiBudgetDurationUnit")
    }
  }
}

case class AiBudgetDuration(value: Long, unit: AiBudgetDurationUnit) {
  def json: JsValue = AiBudgetDuration.format.writes(this)
  def toDuration: java.time.Duration = unit match {
    case AiBudgetDurationUnit.Hour => java.time.Duration.ofHours(value.toLong)
    case AiBudgetDurationUnit.Day => java.time.Duration.ofDays(value.toLong)
    case AiBudgetDurationUnit.Year => java.time.Duration.ofDays((value * 365).toLong)
  }
}

object AiBudgetDuration {
  val format = new Format[AiBudgetDuration] {
    override def writes(o: AiBudgetDuration): JsValue = Json.obj(
      "value" -> o.value,
      "unit" -> o.unit.json
    )
    override def reads(json: JsValue): JsResult[AiBudgetDuration] = Try {
      AiBudgetDuration(
        value = (json \ "value").asOpt[Long].getOrElse(0L),
        unit = AiBudgetDurationUnit.fromString((json \ "unit").asOpt[String].getOrElse("hour"))
      )
    } match {
      case Failure(ex) => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

case class AiBudgetLimits(
  total_tokens: Option[Long], 
  total_usd: Option[BigDecimal],

  inference_tokens: Option[Long],
  inference_usd: Option[BigDecimal],

  image_tokens: Option[Long],
  image_usd: Option[BigDecimal],

  audio_tokens: Option[Long],
  audio_usd: Option[BigDecimal],

  video_tokens: Option[Long],
  video_usd: Option[BigDecimal],

  embedding_tokens: Option[Long],
  embedding_usd: Option[BigDecimal],

  moderation_tokens: Option[Long],
  moderation_usd: Option[BigDecimal],

  ocr_pages: Option[Long],
  ocr_usd: Option[BigDecimal],

  decision_tokens: Option[Long] = None,
  decision_usd: Option[BigDecimal] = None,
) {
  def json: JsValue = AiBudgetLimits.format.writes(this)

  def get(name: String): Option[BigDecimal] = name match {
    case "total_usd"         => total_usd
    case "total_tokens"      => total_tokens.map(BigDecimal(_))
    case "inference_usd"     => inference_usd
    case "inference_tokens"  => inference_tokens.map(BigDecimal(_))
    case "image_usd"         => image_usd
    case "image_tokens"      => image_tokens.map(BigDecimal(_))
    case "audio_usd"         => audio_usd
    case "audio_tokens"      => audio_tokens.map(BigDecimal(_))
    case "video_usd"         => video_usd
    case "video_tokens"      => video_tokens.map(BigDecimal(_))
    case "embedding_usd"     => embedding_usd
    case "embedding_tokens"  => embedding_tokens.map(BigDecimal(_))
    case "moderation_usd"    => moderation_usd
    case "moderation_tokens" => moderation_tokens.map(BigDecimal(_))
    case "ocr_usd"           => ocr_usd
    case "ocr_pages"         => ocr_pages.map(BigDecimal(_))
    case "decision_usd"      => decision_usd
    case "decision_tokens"   => decision_tokens.map(BigDecimal(_))
    case _                   => None
  }

  // whether the budget limits anything at all
  def isEmpty: Boolean = Seq(
    total_tokens, total_usd, inference_tokens, inference_usd, image_tokens, image_usd, audio_tokens, audio_usd,
    video_tokens, video_usd, embedding_tokens, embedding_usd, moderation_tokens, moderation_usd, ocr_pages, ocr_usd,
    decision_tokens, decision_usd,
  ).forall(_.isEmpty)
}

object AiBudgetLimits {
  // the names of the limits, as the json of a budget and its extensions name them
  val names: Seq[String] = Seq(
    "total_usd", "total_tokens", "inference_usd", "inference_tokens", "image_usd", "image_tokens", "audio_usd",
    "audio_tokens", "video_usd", "video_tokens", "embedding_usd", "embedding_tokens", "moderation_usd",
    "moderation_tokens", "ocr_usd", "ocr_pages", "decision_usd", "decision_tokens",
  )
  val format = new Format[AiBudgetLimits] {
    override def writes(o: AiBudgetLimits): JsValue = Json.obj(
      "total_tokens" -> o.total_tokens,
      "total_usd" -> o.total_usd,
      "inference_tokens" -> o.inference_tokens,
      "inference_usd" -> o.inference_usd,
      "image_tokens" -> o.image_tokens,
      "image_usd" -> o.image_usd,
      "audio_tokens" -> o.audio_tokens,
      "audio_usd" -> o.audio_usd,
      "video_tokens" -> o.video_tokens,
      "video_usd" -> o.video_usd,
      "embedding_tokens" -> o.embedding_tokens,
      "embedding_usd" -> o.embedding_usd,
      "moderation_tokens" -> o.moderation_tokens,
      "moderation_usd" -> o.moderation_usd,
      "ocr_pages" -> o.ocr_pages,
      "ocr_usd" -> o.ocr_usd,
      "decision_tokens" -> o.decision_tokens,
      "decision_usd" -> o.decision_usd,
    )
    override def reads(json: JsValue): JsResult[AiBudgetLimits] = Try {
      AiBudgetLimits(
        total_tokens = (json \ "total_tokens").asOpt[Long].filter(_ > -1L),
        total_usd = (json \ "total_usd").asOpt[BigDecimal].filter(_ > -1L),
        inference_tokens = (json \ "inference_tokens").asOpt[Long].filter(_ > -1L),
        inference_usd = (json \ "inference_usd").asOpt[BigDecimal].filter(_ > -1L),
        image_tokens = (json \ "image_tokens").asOpt[Long].filter(_ > -1L),
        image_usd = (json \ "image_usd").asOpt[BigDecimal].filter(_ > -1L),
        audio_tokens = (json \ "audio_tokens").asOpt[Long].filter(_ > -1L),
        audio_usd = (json \ "audio_usd").asOpt[BigDecimal].filter(_ > -1L),
        video_tokens = (json \ "video_tokens").asOpt[Long].filter(_ > -1L),
        video_usd = (json \ "video_usd").asOpt[BigDecimal].filter(_ > -1L),
        embedding_tokens = (json \ "embedding_tokens").asOpt[Long].filter(_ > -1L),
        embedding_usd = (json \ "embedding_usd").asOpt[BigDecimal].filter(_ > -1L),
        moderation_tokens = (json \ "moderation_tokens").asOpt[Long].filter(_ > -1L),
        moderation_usd = (json \ "moderation_usd").asOpt[BigDecimal].filter(_ > -1L),
        ocr_pages = (json \ "ocr_pages").asOpt[Long].filter(_ > -1L),
        ocr_usd = (json \ "ocr_usd").asOpt[BigDecimal].filter(_ > -1L),
        decision_tokens = (json \ "decision_tokens").asOpt[Long].filter(_ > -1L),
        decision_usd = (json \ "decision_usd").asOpt[BigDecimal].filter(_ > -1L),
      )
    } match {
      case Failure(ex) => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  } 
}

sealed trait AiBudgetScopeMode {
  def json: JsValue = AiBudgetScopeMode.format.writes(this)
}
object AiBudgetScopeMode {
  
  case object All extends AiBudgetScopeMode
  case object Any extends AiBudgetScopeMode

  def fromString(str: String): AiBudgetScopeMode = str match {
    case "all" => AiBudgetScopeMode.All
    case "any" => AiBudgetScopeMode.Any
    case _ => throw new IllegalArgumentException(s"Invalid AiBudgetScopeMode: $str")
  }

  val format = new Format[AiBudgetScopeMode] {
    override def writes(o: AiBudgetScopeMode): JsValue = o match {
      case AiBudgetScopeMode.All => JsString("all")
      case AiBudgetScopeMode.Any => JsString("any")
    }
    override def reads(json: JsValue): JsResult[AiBudgetScopeMode] = json match {
      case JsString("all") => JsSuccess(AiBudgetScopeMode.All)
      case JsString("any") => JsSuccess(AiBudgetScopeMode.Any)
      case _ => JsError("Invalid AiBudgetScopeMode")
    }
  }
}

case class AiBudgetScope(
  extractFromApikeyMeta: Boolean,
  extractFromApikeyGroupMeta: Boolean,
  extractFromUserMeta: Boolean,
  extractFromUserAuthModuleMeta: Boolean,
  extractFromProviderMeta: Boolean,
  apikeys: Seq[String],
  users: Seq[String],
  groups: Seq[String],
  providers: Seq[String],
  models: Seq[String],
  alwaysApplyRules: Boolean,
  rules: Seq[JsonPathValidator],
  rulesMatchMode: AiBudgetScopeMode,
  // an expression of the otoroshi expression language: the budget counts apart each value it takes (see BudgetPartition)
  partitionBy: Option[String] = None,
) {
  def json: JsValue = AiBudgetScope.format.writes(this)
}

object AiBudgetScope {
  val format = new Format[AiBudgetScope] {
    override def writes(o: AiBudgetScope): JsValue = Json.obj(
      "extract_from_apikey_meta" -> o.extractFromApikeyMeta,
      "extract_from_apikey_group_meta" -> o.extractFromApikeyGroupMeta,
      "extract_from_user_meta" -> o.extractFromUserMeta,
      "extract_from_user_auth_module_meta" -> o.extractFromUserAuthModuleMeta,
      "extract_from_provider_meta" -> o.extractFromProviderMeta,
      "apikeys" -> o.apikeys,
      "users" -> o.users,
      "groups" -> o.groups,
      "providers" -> o.providers,
      "models" -> o.models,
      "always_apply_rules" -> o.alwaysApplyRules,
      "rules" -> o.rules.map(JsonPathValidator.format.writes),
      "rules_match_mode" -> o.rulesMatchMode.json
    ) ++ o.partitionBy.map(p => Json.obj("partition_by" -> p)).getOrElse(Json.obj())
    override def reads(json: JsValue): JsResult[AiBudgetScope] = Try {
      AiBudgetScope(
        extractFromApikeyMeta = (json \ "extract_from_apikey_meta").asOpt[Boolean].getOrElse(false),
        extractFromApikeyGroupMeta = (json \ "extract_from_apikey_group_meta").asOpt[Boolean].getOrElse(false),
        extractFromUserMeta = (json \ "extract_from_user_meta").asOpt[Boolean].getOrElse(false),
        extractFromUserAuthModuleMeta = (json \ "extract_from_user_auth_module_meta").asOpt[Boolean].getOrElse(false),
        extractFromProviderMeta = (json \ "extract_from_provider_meta").asOpt[Boolean].getOrElse(false),
        apikeys = (json \ "apikeys").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        users = (json \ "users").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        groups = (json \ "groups").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        providers = (json \ "providers").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        models = (json \ "models").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        alwaysApplyRules = (json \ "always_apply_rules").asOpt[Boolean].getOrElse(false),
        rules = (json \ "rules").asOpt[Seq[JsObject]].getOrElse(Seq.empty[JsObject]).flatMap(obj => JsonPathValidator.format.reads(obj).asOpt),
        rulesMatchMode = json.select("rules_match_mode").asOptString.map(s => AiBudgetScopeMode.fromString(s)).getOrElse(AiBudgetScopeMode.All),
        partitionBy = (json \ "partition_by").asOpt[String].map(_.trim).filter(_.nonEmpty),
      )
    } match {
      case Failure(ex) => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

/**
 * The extensions a budget allows: what one adds to its limits (`total_usd` -> 10), and how many can be taken in a cycle,
 * by partition for a budget counting its consumers apart. The person a budget counts takes one when they need it, the
 * amount being the one of the budget (see AiBudget.extend).
 */
case class AiBudgetExtensions(enabled: Boolean, maxPerCycle: Int, limits: Map[String, BigDecimal]) {
  def json: JsValue = Json.obj(
    "enabled" -> enabled,
    "max_per_cycle" -> maxPerCycle,
    "limits" -> JsObject(limits.toSeq.sortBy(_._1).map { case (name, amount) => name -> JsNumber(amount) }),
  )
}

object AiBudgetExtensions {
  // what is not a limit, or not a positive amount, is left aside: a budget always reads
  def read(json: JsValue): AiBudgetExtensions = AiBudgetExtensions(
    enabled = json.select("enabled").asOpt[Boolean].getOrElse(false),
    maxPerCycle = json.select("max_per_cycle").asOpt[Int].getOrElse(1).max(1),
    limits = json.select("limits").asOpt[JsObject].map(_.value.toSeq.collect {
      case (name, JsNumber(amount)) if AiBudgetLimits.names.contains(name) && amount > 0 => name -> amount
    }.toMap).getOrElse(Map.empty),
  )
}

sealed trait AiBudgetActionOnExceedMode {
  def json: JsValue = AiBudgetActionOnExceedMode.format.writes(this)
}
object AiBudgetActionOnExceedMode {

  case object Soft extends AiBudgetActionOnExceedMode
  case object Block extends AiBudgetActionOnExceedMode

  def fromString(s: String): AiBudgetActionOnExceedMode = s match {
    case "soft" => Soft
    case "block" => Block
    case _ => throw new IllegalArgumentException(s"Invalid AiBudgetActionOnExceedMode: $s")
  }

  val format = new Format[AiBudgetActionOnExceedMode] {
    override def writes(o: AiBudgetActionOnExceedMode): JsValue = o match {
      case AiBudgetActionOnExceedMode.Soft => JsString("soft")
      case AiBudgetActionOnExceedMode.Block => JsString("block")
    }
    override def reads(json: JsValue): JsResult[AiBudgetActionOnExceedMode] = json match {
      case JsString("soft") => JsSuccess(AiBudgetActionOnExceedMode.Soft)
      case JsString("block") => JsSuccess(AiBudgetActionOnExceedMode.Block)
      case _ => JsError("Invalid AiBudgetActionOnExceedMode")
    }
  }
}

// `errorMessage` is the error returned to the client of a call blocked by the budget (`block` mode only)
case class AiBudgetActionOnExceed(mode: AiBudgetActionOnExceedMode, alertOnExceed: Boolean, alertOnAlmostExceed: Boolean, alertOnAlmostExceedPercentage: Int, errorMessage: Option[String] = None) {
  def json: JsValue = AiBudgetActionOnExceed.format.writes(this)
}
object AiBudgetActionOnExceed {
  val defaultErrorMessage = "budget exceeded"
  val format = new Format[AiBudgetActionOnExceed] {
    override def writes(o: AiBudgetActionOnExceed): JsValue = Json.obj(
      "mode" -> o.mode.json,
      "alert_on_exceed" -> o.alertOnExceed,
      "alert_on_almost_exceed" -> o.alertOnAlmostExceed,
      "alert_on_almost_exceed_percentage" -> o.alertOnAlmostExceedPercentage,
      "error_message" -> o.errorMessage,
    )
    override def reads(json: JsValue): JsResult[AiBudgetActionOnExceed] = Try {
      AiBudgetActionOnExceed(
        mode = AiBudgetActionOnExceedMode.fromString((json \ "mode").asOpt[String].getOrElse("soft")),
        alertOnExceed = (json \ "alert_on_exceed").asOpt[Boolean].getOrElse(true),
        alertOnAlmostExceed = (json \ "alert_on_almost_exceed").asOpt[Boolean].getOrElse(true),
        alertOnAlmostExceedPercentage = (json \ "alert_on_almost_exceed_percentage").asOpt[Int].getOrElse(80),
        // a field emptied in the form comes back as an empty string
        errorMessage = (json \ "error_message").asOpt[String].map(_.trim).filter(_.nonEmpty),
      )
    } match {
      case Failure(ex) => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

case class AiBudgetConsumptions(
  totalUsd: BigDecimal,
  totalTokens: Long,
  inferenceUsd: BigDecimal,
  inferenceTokens: Long,
  imageUsd: BigDecimal,
  imageTokens: Long,
  audioUsd: BigDecimal,
  audioTokens: Long,
  videoUsd: BigDecimal,
  videoTokens: Long,
  embeddingUsd: BigDecimal,
  embeddingTokens: Long,
  moderationUsd: BigDecimal,
  moderationTokens: Long,
  ocrUsd: BigDecimal,
  ocrPages: Long,
  decisionUsd: BigDecimal = BigDecimal(0),
  decisionTokens: Long = 0L,
  // the partition this consumption is the one of, when the budget counts consumers apart
  partition: Option[String] = None,
  // what the extensions taken in the cycle add to the limits (`total_usd` -> 10), and how many were taken
  extras: Map[String, BigDecimal] = Map.empty,
  extensionsTaken: Long = 0L,
) {
  // a limit with the extensions taken in the cycle
  def allowedUsd(name: String, limit: Option[BigDecimal]): Option[BigDecimal] = limit.map(_ + extras.getOrElse(name, BigDecimal(0)))
  def allowedUnits(name: String, limit: Option[Long]): Option[Long] = limit.map(_ + extras.get(name).map(_.toLong).getOrElse(0L))

  def json: JsValue = Json.obj(
    "consumed_total_usd" -> totalUsd,
    "consumed_total_tokens" -> totalTokens,
    "consumed_inference_usd" -> inferenceUsd,
    "consumed_inference_tokens" -> inferenceTokens,
    "consumed_image_usd" -> imageUsd,
    "consumed_image_tokens" -> imageTokens,
    "consumed_audio_usd" -> audioUsd,
    "consumed_audio_tokens" -> audioTokens,
    "consumed_video_usd" -> videoUsd,
    "consumed_video_tokens" -> videoTokens,
    "consumed_embedding_usd" -> embeddingUsd,
    "consumed_embedding_tokens" -> embeddingTokens,
    "consumed_moderation_usd" -> moderationUsd,
    "consumed_moderation_tokens" -> moderationTokens,
    "consumed_ocr_usd" -> ocrUsd,
    "consumed_ocr_pages" -> ocrPages,
    "consumed_decision_usd" -> decisionUsd,
    "consumed_decision_tokens" -> decisionTokens,
  ) ++ partition.map(p => Json.obj("partition" -> p)).getOrElse(Json.obj()) ++
    JsObject(extras.toSeq.filter(_._2 != 0).sortBy(_._1).map { case (name, amount) => s"extra_$name" -> JsNumber(amount) }) ++
    (if (extensionsTaken > 0L) Json.obj("extensions_taken" -> extensionsTaken) else Json.obj())
  def jsonWithRemaining(budget: AiBudget): JsValue = json.asObject ++ Json.obj(
    "remaining_total_usd" -> JsNumber(allowedUsd("total_usd", budget.limits.total_usd).map(v => v - totalUsd).getOrElse(BigDecimal(0))),
    "remaining_total_tokens" -> JsNumber(BigDecimal(allowedUnits("total_tokens", budget.limits.total_tokens).map(_ - totalTokens).getOrElse(0L))),
    "remaining_inference_usd" -> JsNumber(allowedUsd("inference_usd", budget.limits.inference_usd).map(_ - inferenceUsd).getOrElse(BigDecimal(0))),
    "remaining_inference_tokens" -> JsNumber(BigDecimal(allowedUnits("inference_tokens", budget.limits.inference_tokens).map(_ - inferenceTokens).getOrElse(0L))),
    "remaining_image_usd" -> JsNumber(allowedUsd("image_usd", budget.limits.image_usd).map(_ - imageUsd).getOrElse(BigDecimal(0))),
    "remaining_image_tokens" -> JsNumber(BigDecimal(allowedUnits("image_tokens", budget.limits.image_tokens).map(_ - imageTokens).getOrElse(0L))),
    "remaining_audio_usd" -> JsNumber(allowedUsd("audio_usd", budget.limits.audio_usd).map(_ - audioUsd).getOrElse(BigDecimal(0))),
    "remaining_audio_tokens" -> JsNumber(BigDecimal(allowedUnits("audio_tokens", budget.limits.audio_tokens).map(_ - audioTokens).getOrElse(0L))),
    "remaining_video_usd" -> JsNumber(allowedUsd("video_usd", budget.limits.video_usd).map(_ - videoUsd).getOrElse(BigDecimal(0))),
    "remaining_video_tokens" -> JsNumber(BigDecimal(allowedUnits("video_tokens", budget.limits.video_tokens).map(_ - videoTokens).getOrElse(0L))),
    "remaining_embedding_usd" -> JsNumber(allowedUsd("embedding_usd", budget.limits.embedding_usd).map(_ - embeddingUsd).getOrElse(BigDecimal(0))),
    "remaining_embedding_tokens" -> JsNumber(BigDecimal(allowedUnits("embedding_tokens", budget.limits.embedding_tokens).map(_ - embeddingTokens).getOrElse(0L))),
    "remaining_moderation_usd" -> JsNumber(allowedUsd("moderation_usd", budget.limits.moderation_usd).map(_ - moderationUsd).getOrElse(BigDecimal(0))),
    "remaining_moderation_tokens" -> JsNumber(BigDecimal(allowedUnits("moderation_tokens", budget.limits.moderation_tokens).map(_ - moderationTokens).getOrElse(0L))),
    "remaining_ocr_usd" -> JsNumber(allowedUsd("ocr_usd", budget.limits.ocr_usd).map(_ - ocrUsd).getOrElse(BigDecimal(0))),
    "remaining_ocr_pages" -> JsNumber(BigDecimal(allowedUnits("ocr_pages", budget.limits.ocr_pages).map(_ - ocrPages).getOrElse(0L))),
    "remaining_decision_usd" -> JsNumber(allowedUsd("decision_usd", budget.limits.decision_usd).map(_ - decisionUsd).getOrElse(BigDecimal(0))),
    "remaining_decision_tokens" -> JsNumber(BigDecimal(allowedUnits("decision_tokens", budget.limits.decision_tokens).map(_ - decisionTokens).getOrElse(0L))),
    "allowed_total_usd" -> allowedUsd("total_usd", budget.limits.total_usd),
    "allowed_total_tokens" -> allowedUnits("total_tokens", budget.limits.total_tokens),
    "allowed_inference_usd" -> allowedUsd("inference_usd", budget.limits.inference_usd),
    "allowed_inference_tokens" -> allowedUnits("inference_tokens", budget.limits.inference_tokens),
    "allowed_image_usd" -> allowedUsd("image_usd", budget.limits.image_usd),
    "allowed_image_tokens" -> allowedUnits("image_tokens", budget.limits.image_tokens),
    "allowed_audio_usd" -> allowedUsd("audio_usd", budget.limits.audio_usd),
    "allowed_audio_tokens" -> allowedUnits("audio_tokens", budget.limits.audio_tokens),
    "allowed_video_usd" -> allowedUsd("video_usd", budget.limits.video_usd),
    "allowed_video_tokens" -> allowedUnits("video_tokens", budget.limits.video_tokens),
    "allowed_embedding_usd" -> allowedUsd("embedding_usd", budget.limits.embedding_usd),
    "allowed_embedding_tokens" -> allowedUnits("embedding_tokens", budget.limits.embedding_tokens),
    "allowed_moderation_usd" -> allowedUsd("moderation_usd", budget.limits.moderation_usd),
    "allowed_moderation_tokens" -> allowedUnits("moderation_tokens", budget.limits.moderation_tokens),
    "allowed_ocr_usd" -> allowedUsd("ocr_usd", budget.limits.ocr_usd),
    "allowed_ocr_pages" -> allowedUnits("ocr_pages", budget.limits.ocr_pages),
    "allowed_decision_usd" -> allowedUsd("decision_usd", budget.limits.decision_usd),
    "allowed_decision_tokens" -> allowedUnits("decision_tokens", budget.limits.decision_tokens),
  ) ++ budget.extensionsJson
}

/**
 * The owner of an api key: the email the usage of the key counts for, in the budgets scoped to users and
 * in the analytics. Set from the AI Studio keys page, absent on a key shared by a whole workspace.
 */
object ApikeyOwner {
  val MetadataKey: String = "ai_studio_owner"

  def of(apikey: ApiKey): Option[String] = apikey.metadata.get(MetadataKey).map(_.trim).filter(_.nonEmpty)
}

case class AiBudget(
                       location: EntityLocation,
                       id: String,
                       name: String,
                       description: String,
                       tags: Seq[String],
                       metadata: Map[String, String],
                       /////////////////////////////////////////////////////////////////////////////////////////////////
                       enabled: Boolean,
                       startAt: DateTime,
                       endAt: DateTime,
                       duration: AiBudgetDuration,
                       // renewals: Option[Int],
                       limits: AiBudgetLimits,
                       scope: AiBudgetScope,
                       actionOnExceed: AiBudgetActionOnExceed,
                       // the extensions the people this budget counts can take, none when absent
                       extensions: Option[AiBudgetExtensions] = None,
                     ) extends EntityLocationSupport {
  override def internalId: String = id

  override def json: JsValue = AiBudget.format.writes(this)

  override def theName: String = name

  override def theDescription: String = description

  override def theTags: Seq[String] = tags

  override def theMetadata: Map[String, String] = metadata

  lazy val renewals: Int = {
    if (!endAt.isAfter(startAt)) {
      0
    } else {
      val includeInitial = false
      val totalMillis = new org.joda.time.Duration(startAt, endAt).getMillis
      val sliceMillis = duration.toDuration.toHours * 3600000L
      val totalSlicesCeil =
        math.ceil(totalMillis.toDouble / sliceMillis.toDouble).toLong
      (if (includeInitial) totalSlicesCeil else math.max(0L, totalSlicesCeil - 1L)).toInt
    }
  }

  def slugName: String = metadata.get("endpoint_name").orElse(metadata.get("provider_name")).getOrElse(name).slugifyWithSlash.replaceAll("-+", "_")

  def cycle: Option[Int] = {
    // val now = Instant.now()
    // val dur = duration.toDuration
    // val k = java.time.Duration.between(startAt.toGregorianCalendar.toInstant, now).dividedBy(dur).toInt
    // val rn = renewals
    // if (rn >= 0 && k > rn) None else Some(k)
    val now = DateTime.now()
    if (!now.isAfter(startAt)) {
      0.some
    } else {
      val includeInitial = false
      val totalMillis = new org.joda.time.Duration(startAt, now).getMillis
      val sliceMillis = duration.toDuration.toHours * 3600000L
      val totalSlicesCeil =
        math.ceil(totalMillis.toDouble / sliceMillis.toDouble).toLong
      Some((if (includeInitial) totalSlicesCeil else math.max(0L, totalSlicesCeil - 1L)).toInt)
    }
  }

  def cycleId: String = cycle.map(_.toString).getOrElse("single")

  // whether the budget counts each of its consumers apart (see BudgetPartition)
  def partitioned: Boolean = scope.partitionBy.isDefined

  // a counter of this budget, in one of its cycles, and in one of its partitions when it counts consumers apart
  def counterKey(cycle: String, counter: String, partition: Option[String] = None)(using env: Env): String = partition match {
    case None    => s"${AiBudget.countersPrefix}:$id:$cycle:$counter"
    case Some(p) => s"${AiBudget.countersPrefix}:$id:$cycle:p:${BudgetPartition.segment(p)}:$counter"
  }

  // the partitions that consumed in a cycle
  def partitionsKey(cycle: String)(using env: Env): String = s"${AiBudget.countersPrefix}:$id:$cycle:partitions"

  // when a cycle of the budget ends, as the cycles are computed (see `cycle`)
  def cycleEnd(cycle: String): Option[Long] = cycle.toLongOption.filter(_ >= 0L).map { c =>
    startAt.getMillis + (c + 1L) * duration.toDuration.toHours * 3600000L
  }

  // The counters of a cycle expire some days after it ends: nothing reads a cycle that is over, the days left are the
  // ones a worker may take to push what it counted in it
  def expireCounter(key: String, cycle: String)(using ec: ExecutionContext, env: Env): Unit = cycleEnd(cycle).foreach { end =>
    env.datastores.rawDataStore.pexpire(key, math.max(end + AiBudget.countersRetentionMillis - System.currentTimeMillis(), 1000L))
  }

  // What a worker pushed for one of its counters (`total_usd`, `ocr_pages`...), counted in the cycle it was consumed
  // in: a push that comes in once that cycle is over is not what the next one consumed
  def countFromWorker(counter: String, cycle: String, by: BigDecimal, partition: Option[String] = None)(using ec: ExecutionContext, env: Env): Future[Long] = {
    val amount = if (counter.endsWith("_usd")) by.*(BigDecimal(1000000000)).toLong else by.toLong
    count(cycle, counter.replace('_', '-'), amount, partition)
  }

  // adds to a counter, which expires with its cycle, the partition being kept among the ones of the cycle
  private def count(cycle: String, counter: String, amount: Long, partition: Option[String])(using ec: ExecutionContext, env: Env): Future[Long] = {
    val key = counterKey(cycle, counter, partition)
    partition.foreach(p => indexPartition(cycle, p))
    env.datastores.rawDataStore.incrby(key, amount).andThen {
      case Success(value) if value == amount => expireCounter(key, cycle)
    }
  }

  // once by partition and cycle on a node: the set does not change when the partition is added again
  def indexPartition(cycle: String, partition: String)(using ec: ExecutionContext, env: Env): Unit = {
    if (AiBudget.indexedPartitions.putIfAbsent(s"$id:$cycle:$partition", ()).isEmpty) {
      val key = partitionsKey(cycle)
      env.datastores.rawDataStore.sadd(key, Seq(ByteString(partition))).andThen {
        case Success(_) => expireCounter(key, cycle)
      }
    }
  }

  private def incrUsd(counter: String, counters: TrieMap[String, DoubleAdder], by: BigDecimal, partition: Option[String])(using ec: ExecutionContext, env: Env): Future[Long] = {
    val cycle = cycleId
    if (env.clusterConfig.mode.isWorker) {
      partition match {
        case None    => counters.getOrElseUpdate(s"$id:$cycle", new DoubleAdder()).add(by.toDouble)
        case Some(p) => AiBudgetClusterAgent.partitionedUsdCounters.getOrElseUpdate((id, cycle, p, counter.replace('-', '_')), new DoubleAdder()).add(by.toDouble)
      }
    }
    count(cycle, counter, by.*(BigDecimal(1000000000)).toLong, partition)
  }

  private def incrUnits(counter: String, counters: TrieMap[String, AtomicLong], by: Long, partition: Option[String])(using ec: ExecutionContext, env: Env): Future[Long] = {
    val cycle = cycleId
    if (env.clusterConfig.mode.isWorker) {
      partition match {
        case None    => counters.getOrElseUpdate(s"$id:$cycle", new AtomicLong()).addAndGet(by)
        case Some(p) => AiBudgetClusterAgent.partitionedUnitCounters.getOrElseUpdate((id, cycle, p, counter.replace('-', '_')), new AtomicLong()).addAndGet(by)
      }
    }
    count(cycle, counter, by, partition)
  }

  def incrTotalUsd(by: BigDecimal, partition: Option[String] = None)(using ec: ExecutionContext, env: Env): Future[Long] = incrUsd("total-usd", AiBudgetClusterAgent.totalUsdCounters, by, partition)
  def incrTotalTokens(by: Long, partition: Option[String] = None)(using ec: ExecutionContext, env: Env): Future[Long] = incrUnits("total-tokens", AiBudgetClusterAgent.totalTokensCounters, by, partition)
  def incrInferenceUsd(by: BigDecimal, partition: Option[String] = None)(using ec: ExecutionContext, env: Env): Future[Long] = incrUsd("inference-usd", AiBudgetClusterAgent.inferenceUsdCounters, by, partition)
  def incrInferenceTokens(by: Long, partition: Option[String] = None)(using ec: ExecutionContext, env: Env): Future[Long] = incrUnits("inference-tokens", AiBudgetClusterAgent.inferenceTokensCounters, by, partition)
  def incrImageUsd(by: BigDecimal, partition: Option[String] = None)(using ec: ExecutionContext, env: Env): Future[Long] = incrUsd("image-usd", AiBudgetClusterAgent.imageUsdCounters, by, partition)
  def incrImageTokens(by: Long, partition: Option[String] = None)(using ec: ExecutionContext, env: Env): Future[Long] = incrUnits("image-tokens", AiBudgetClusterAgent.imageTokensCounters, by, partition)
  def incrAudioUsd(by: BigDecimal, partition: Option[String] = None)(using ec: ExecutionContext, env: Env): Future[Long] = incrUsd("audio-usd", AiBudgetClusterAgent.audioUsdCounters, by, partition)
  def incrAudioTokens(by: Long, partition: Option[String] = None)(using ec: ExecutionContext, env: Env): Future[Long] = incrUnits("audio-tokens", AiBudgetClusterAgent.audioTokensCounters, by, partition)
  def incrVideoUsd(by: BigDecimal, partition: Option[String] = None)(using ec: ExecutionContext, env: Env): Future[Long] = incrUsd("video-usd", AiBudgetClusterAgent.videoUsdCounters, by, partition)
  def incrVideoTokens(by: Long, partition: Option[String] = None)(using ec: ExecutionContext, env: Env): Future[Long] = incrUnits("video-tokens", AiBudgetClusterAgent.videoTokensCounters, by, partition)
  def incrEmbeddingUsd(by: BigDecimal, partition: Option[String] = None)(using ec: ExecutionContext, env: Env): Future[Long] = incrUsd("embedding-usd", AiBudgetClusterAgent.embeddingUsdCounters, by, partition)
  def incrEmbeddingTokens(by: Long, partition: Option[String] = None)(using ec: ExecutionContext, env: Env): Future[Long] = incrUnits("embedding-tokens", AiBudgetClusterAgent.embeddingTokensCounters, by, partition)
  def incrModerationUsd(by: BigDecimal, partition: Option[String] = None)(using ec: ExecutionContext, env: Env): Future[Long] = incrUsd("moderation-usd", AiBudgetClusterAgent.moderationUsdCounters, by, partition)
  def incrModerationTokens(by: Long, partition: Option[String] = None)(using ec: ExecutionContext, env: Env): Future[Long] = incrUnits("moderation-tokens", AiBudgetClusterAgent.moderationTokensCounters, by, partition)
  def incrOcrUsd(by: BigDecimal, partition: Option[String] = None)(using ec: ExecutionContext, env: Env): Future[Long] = incrUsd("ocr-usd", AiBudgetClusterAgent.ocrUsdCounters, by, partition)
  def incrOcrPages(by: Long, partition: Option[String] = None)(using ec: ExecutionContext, env: Env): Future[Long] = incrUnits("ocr-pages", AiBudgetClusterAgent.ocrPagesCounters, by, partition)
  def incrDecisionUsd(by: BigDecimal, partition: Option[String] = None)(using ec: ExecutionContext, env: Env): Future[Long] = incrUsd("decision-usd", AiBudgetClusterAgent.decisionUsdCounters, by, partition)
  def incrDecisionTokens(by: Long, partition: Option[String] = None)(using ec: ExecutionContext, env: Env): Future[Long] = incrUnits("decision-tokens", AiBudgetClusterAgent.decisionTokensCounters, by, partition)

  def resetCurrentCycle()(using ec: ExecutionContext, env: Env): Future[Unit] = {
    env.datastores.rawDataStore.keys(s"${AiBudget.countersPrefix}:$id:$cycleId:*").flatMap { keys =>
      env.datastores.rawDataStore.del(keys).map { _ =>
        ()
      }
    }
  }

  // the counters of one partition in the current cycle; the partition stays in the index, without counters
  def resetPartition(partition: String)(using ec: ExecutionContext, env: Env): Future[Unit] = {
    env.datastores.rawDataStore.keys(s"${AiBudget.countersPrefix}:$id:$cycleId:p:${BudgetPartition.segment(partition)}:*").flatMap {
      case keys if keys.isEmpty => ().vfuture
      case keys                 => env.datastores.rawDataStore.del(keys).map(_ => ())
    }
  }

  def resetAll()(using ec: ExecutionContext, env: Env): Future[Unit] = {
    env.datastores.rawDataStore.keys(s"${AiBudget.countersPrefix}:$id:*").flatMap { keys =>
      env.datastores.rawDataStore.del(keys).map { _ =>
        ()
      }
    }
  }

  def getConsumptions(partition: Option[String] = None)(using ec: ExecutionContext, env: Env): Future[AiBudgetConsumptions] =
    readConsumptions(partition).map(_._2)

  // the consumption of the current cycle, and whether anything was counted in it at all
  private def readConsumptions(partition: Option[String])(using ec: ExecutionContext, env: Env): Future[(Boolean, AiBudgetConsumptions)] = {
    val cycle = cycleId
    // the extensions taken are only read on a budget that has any, after the counters
    val extraNames = if (extensions.isDefined) AiBudgetLimits.names.filter(n => limits.get(n).isDefined) else Seq.empty
    val extraKeys = extraNames.map(n => counterKey(cycle, s"extra-${n.replace('_', '-')}", partition)) ++
      (if (extensions.isDefined) Seq(counterKey(cycle, "extensions-count", partition)) else Seq.empty)
    // new counters go last: the values are read back by position
    env.datastores.rawDataStore.mget(AiBudget.consumptionCounters.map(c => counterKey(cycle, c, partition)) ++ extraKeys).map { list =>
      val extras = extraNames.zip(list.drop(AiBudget.consumptionCounters.size)).collect {
        case (name, Some(value)) => name -> (if (name.endsWith("_usd")) BigDecimal(value.utf8String.toLong)./(BigDecimal(1000000000)) else BigDecimal(value.utf8String.toLong))
      }.toMap
      val taken = if (extensions.isDefined) list.lastOption.flatten.map(_.utf8String.toLong).getOrElse(0L) else 0L
      (list.take(AiBudget.consumptionCounters.size).exists(_.isDefined), AiBudgetConsumptions(
        totalUsd = list(0).map(_.utf8String.toLong).getOrElse(0L)./(BigDecimal(1000000000)),
        totalTokens = list(1).map(_.utf8String.toLong).getOrElse(0L),
        inferenceUsd = list(2).map(_.utf8String.toLong).getOrElse(0L)./(BigDecimal(1000000000)),
        inferenceTokens = list(3).map(_.utf8String.toLong).getOrElse(0L),
        imageUsd = list(4).map(_.utf8String.toLong).getOrElse(0L)./(BigDecimal(1000000000)),
        imageTokens = list(5).map(_.utf8String.toLong).getOrElse(0L),
        audioUsd = list(6).map(_.utf8String.toLong).getOrElse(0L)./(BigDecimal(1000000000)),
        audioTokens = list(7).map(_.utf8String.toLong).getOrElse(0L),
        videoUsd = list(8).map(_.utf8String.toLong).getOrElse(0L)./(BigDecimal(1000000000)),
        videoTokens = list(9).map(_.utf8String.toLong).getOrElse(0L),
        embeddingUsd = list(10).map(_.utf8String.toLong).getOrElse(0L)./(BigDecimal(1000000000)),
        embeddingTokens = list(11).map(_.utf8String.toLong).getOrElse(0L),
        moderationUsd = list(12).map(_.utf8String.toLong).getOrElse(0L)./(BigDecimal(1000000000)),
        moderationTokens = list(13).map(_.utf8String.toLong).getOrElse(0L),
        ocrUsd = list(14).map(_.utf8String.toLong).getOrElse(0L)./(BigDecimal(1000000000)),
        ocrPages = list(15).map(_.utf8String.toLong).getOrElse(0L),
        decisionUsd = list(16).map(_.utf8String.toLong).getOrElse(0L)./(BigDecimal(1000000000)),
        decisionTokens = list(17).map(_.utf8String.toLong).getOrElse(0L),
        partition = partition,
        extras = extras,
        extensionsTaken = taken,
      ))
    }
  }

  // what one extension adds to the limits, when the budget allows any: only the limits it has
  def extensionAmounts: Option[Map[String, BigDecimal]] =
    extensions.filter(_.enabled).map(_.limits.filter { case (name, _) => limits.get(name).isDefined }).filter(_.nonEmpty)

  // the extensions a consumption can still take, with what one adds
  def extensionsJson: JsObject = (extensions, extensionAmounts) match {
    case (Some(e), Some(amounts)) => Json.obj(
      "extensions_max" -> e.maxPerCycle,
      "extension" -> JsObject(amounts.toSeq.sortBy(_._1).map { case (name, amount) => name -> JsNumber(amount) }),
    )
    case _ => Json.obj()
  }

  // Takes one extension in the current cycle, for a partition when the budget counts its consumers apart. What it adds
  // and how many can be taken are set by the budget. A partition takes one once it consumed in the cycle, which keeps a
  // person from taking one on a budget that does not count them. The number taken is kept apart from the amounts: the
  // cancellation removes the amounts and keeps the number, or cancelling then taking again would go past it
  def extend(partition: Option[String])(using ec: ExecutionContext, env: Env): Future[Either[(Int, JsObject), AiBudgetConsumptions]] = {
    val cycle = cycleId
    def refused(status: Int, error: String, description: String, more: JsObject = Json.obj()): Future[Either[(Int, JsObject), AiBudgetConsumptions]] =
      Left((status, Json.obj("error" -> error, "error_description" -> description) ++ more)).vfuture
    (extensionAmounts, partition) match {
      case (None, _) => refused(409, "extensions_disabled", "this budget allows no extension")
      case (_, None) if partitioned => refused(400, "partition_required", "this budget counts its consumers apart: name one with the 'partition' query param")
      case (_, Some(_)) if !partitioned => refused(400, "partition_not_allowed", "this budget does not count its consumers apart")
      case (Some(amounts), _) =>
        val consumed = partition match {
          case Some(p) => env.datastores.rawDataStore.sismember(partitionsKey(cycle), ByteString(p))
          case None    => true.vfuture
        }
        consumed.flatMap {
          case false => refused(409, "no_consumption", "nothing was consumed on this budget in the current cycle")
          case true =>
            val max = extensions.map(_.maxPerCycle).getOrElse(1)
            val takenKey = counterKey(cycle, "extensions-count", partition)
            env.datastores.rawDataStore.incrby(takenKey, 1L).flatMap {
              case taken if taken > max =>
                env.datastores.rawDataStore.incrby(takenKey, -1L).flatMap { _ =>
                  refused(409, "extensions_exhausted", s"the extensions of the current cycle were all taken", Json.obj("extensions_max" -> max))
                }
              case taken =>
                if (taken == 1L) expireCounter(takenKey, cycle)
                Future.sequence(amounts.toSeq.map { case (name, amount) =>
                  val units = if (name.endsWith("_usd")) amount.*(BigDecimal(1000000000)).toLong else amount.toLong
                  count(cycle, s"extra-${name.replace('_', '-')}", units, partition)
                }).flatMap(_ => getConsumptions(partition)).map(Right(_))
            }
        }
    }
  }

  // removes the extensions taken in the current cycle, without giving them back
  def cancelExtensions(partition: Option[String])(using ec: ExecutionContext, env: Env): Future[Unit] = {
    val pattern = partition match {
      case None    => s"${AiBudget.countersPrefix}:$id:$cycleId:extra-*"
      case Some(p) => s"${AiBudget.countersPrefix}:$id:$cycleId:p:${BudgetPartition.segment(p)}:extra-*"
    }
    env.datastores.rawDataStore.keys(pattern).flatMap {
      case keys if keys.isEmpty => ().vfuture
      case keys                 => env.datastores.rawDataStore.del(keys).map(_ => ())
    }
  }

  // the partitions that consumed in the current cycle, the ones that consumed most first
  def partitionConsumptions()(using ec: ExecutionContext, env: Env): Future[Seq[AiBudgetConsumptions]] = {
    env.datastores.rawDataStore.smembers(partitionsKey(cycleId)).flatMap { members =>
      Future.sequence(members.map(_.utf8String).distinct.map(p => readConsumptions(Some(p))))
    }.map { all =>
      all.collect { case (true, consumptions) => consumptions }.sortBy(c => (-c.totalUsd, -c.totalTokens, c.partition.getOrElse("")))
    }
  }

  def matchesRules(ctx: JsValue)(using env: Env): Boolean = {
    if (scope.rules.isEmpty) {
      false
    } else {
      scope.rulesMatchMode match {
        case AiBudgetScopeMode.All => scope.rules.forall(_.validate(ctx))
        case AiBudgetScopeMode.Any => scope.rules.exists(_.validate(ctx))
      }
    }
  }

  // a call counts for the person who made it, otherwise for the owner of the api key it was made with,
  // the same way the analytics attribute it
  private def matchesUser(apikey: Option[ApiKey], user: Option[PrivateAppsUser]): Boolean = user match {
    case Some(u) => scope.users.exists(id => RegexPool.regex(id).matches(u.email)) || scope.users.exists(id => RegexPool.regex(id).matches(u.internalId))
    case None    => apikey.flatMap(ApikeyOwner.of).exists(owner => scope.users.exists(id => RegexPool.regex(id).matches(owner)))
  }

  def matches(ctx: JsValue, apikey: Option[ApiKey], user: Option[PrivateAppsUser], provider: Option[Entity], model: Option[String])(using env: Env): Boolean = {
    if (!enabled) {
      false
    } else if (startAt.isAfterNow) {
      false
    } else if (endAt.isBeforeNow) {
      false
    } else if (scope.apikeys.nonEmpty && apikey.nonEmpty && scope.apikeys.exists(id => RegexPool.regex(id).matches(apikey.get.clientId))) {
      true
    } else if (scope.users.nonEmpty && matchesUser(apikey, user)) {
      true
    } else if (scope.groups.nonEmpty && apikey.nonEmpty && scope.groups.exists(id => apikey.get.authorizedEntities.exists(ae => ae.prefix == "group" && RegexPool.regex(id).matches(ae.id)))) {
      true
    } else if (scope.providers.nonEmpty && provider.nonEmpty && scope.providers.exists(id => RegexPool.regex(id).matches(provider.get.theId))) {
      true
    } else if (scope.models.nonEmpty && model.nonEmpty && scope.models.exists(id => RegexPool.regex(id).matches(model.get))) {
      true
    } else {
      if (scope.alwaysApplyRules) {
        // rules are already enforced as a mandatory AND gate upstream (findMatchingBudgets).
        // reaching this else branch means no selector matched, so we must NOT fall back to
        // matchesRules here (that would double-count the rules and silently ignore the selectors).
        // if selectors are defined but none matched, the budget does not match;
        // if no selector is defined, the rules alone define the scope.
        val noSelector = scope.apikeys.isEmpty && scope.users.isEmpty && scope.groups.isEmpty && scope.providers.isEmpty && scope.models.isEmpty
        noSelector
      } else {
        matchesRules(ctx)
      }
    }
  }

  // `partition`: the partition of the call, for a budget that counts its consumers apart
  def isNotWithinBudget(attrs: TypedMap, partition: Option[String] = None)(using ec: ExecutionContext, env: Env): Future[Boolean] = isNotWithinBudgetWithConsumption(attrs, partition).map(r => r._1)

  def isWithinBudget(attrs: TypedMap, partition: Option[String] = None)(using ec: ExecutionContext, env: Env): Future[Boolean] = isWithinBudgetWithConsumption(attrs, partition).map(r => r._1)

  def isNotWithinBudgetWithConsumption(attrs: TypedMap, partition: Option[String] = None)(using ec: ExecutionContext, env: Env): Future[(Boolean, Option[AiBudgetConsumptions])] = isWithinBudgetWithConsumption(attrs, partition).map(r => (!r._1, r._2))

  def isWithinBudgetWithConsumption(attrs: TypedMap, partition: Option[String] = None)(using ec: ExecutionContext, env: Env): Future[(Boolean, Option[AiBudgetConsumptions])] = {
    val cycleValue = cycle.getOrElse(-1)
    if (cycleValue >= (renewals + 1)) {
      (false, None).vfuture
    } else if (limits.isEmpty) {
      (true, None).vfuture
    } else {
      getConsumptions(partition).map { consumptions =>
        attrs.put(ChatClientWithAuding.BudgetConsumptionKey -> (consumptions, this))
        if (consumptions.allowedUsd("total_usd", limits.total_usd).exists(consumptions.totalUsd >= _)) {
          (false, consumptions.some)
        } else if (consumptions.allowedUnits("total_tokens", limits.total_tokens).exists(consumptions.totalTokens >= _)) {
          (false, consumptions.some)
        } else if (consumptions.allowedUsd("inference_usd", limits.inference_usd).exists(consumptions.inferenceUsd >= _)) {
          (false, consumptions.some)
        } else if (consumptions.allowedUnits("inference_tokens", limits.inference_tokens).exists(consumptions.inferenceTokens >= _)) {
          (false, consumptions.some)
        } else if (consumptions.allowedUsd("image_usd", limits.image_usd).exists(consumptions.imageUsd >= _)) {
          (false, consumptions.some)
        } else if (consumptions.allowedUnits("image_tokens", limits.image_tokens).exists(consumptions.imageTokens >= _)) {
          (false, consumptions.some)
        } else if (consumptions.allowedUsd("audio_usd", limits.audio_usd).exists(consumptions.audioUsd >= _)) {
          (false, consumptions.some)
        } else if (consumptions.allowedUnits("audio_tokens", limits.audio_tokens).exists(consumptions.audioTokens >= _)) {
          (false, consumptions.some)
        } else if (consumptions.allowedUsd("video_usd", limits.video_usd).exists(consumptions.videoUsd >= _)) {
          (false, consumptions.some)
        } else if (consumptions.allowedUnits("video_tokens", limits.video_tokens).exists(consumptions.videoTokens >= _)) {
          (false, consumptions.some)
        } else if (consumptions.allowedUsd("embedding_usd", limits.embedding_usd).exists(consumptions.embeddingUsd >= _)) {
          (false, consumptions.some)
        } else if (consumptions.allowedUnits("embedding_tokens", limits.embedding_tokens).exists(consumptions.embeddingTokens >= _)) {
          (false, consumptions.some)
        } else if (consumptions.allowedUsd("moderation_usd", limits.moderation_usd).exists(consumptions.moderationUsd >= _)) {
          (false, consumptions.some)
        } else if (consumptions.allowedUnits("moderation_tokens", limits.moderation_tokens).exists(consumptions.moderationTokens >= _)) {
          (false, consumptions.some)
        } else if (consumptions.allowedUsd("ocr_usd", limits.ocr_usd).exists(consumptions.ocrUsd >= _)) {
          (false, consumptions.some)
        } else if (consumptions.allowedUnits("ocr_pages", limits.ocr_pages).exists(consumptions.ocrPages >= _)) {
          (false, consumptions.some)
        } else if (consumptions.allowedUsd("decision_usd", limits.decision_usd).exists(consumptions.decisionUsd >= _)) {
          (false, consumptions.some)
        } else if (consumptions.allowedUnits("decision_tokens", limits.decision_tokens).exists(consumptions.decisionTokens >= _)) {
          (false, consumptions.some)
        } else {
          val percentageConsumedTotalUsd: Double = consumptions.allowedUsd("total_usd", limits.total_usd).map(tusd => consumptions.totalUsd.toDouble / tusd.toDouble * 100.0).getOrElse(0.0)
          val percentageConsumedTotalTokens: Double = consumptions.allowedUnits("total_tokens", limits.total_tokens).map(ttokens => consumptions.totalTokens.toDouble / ttokens.toDouble * 100.0).getOrElse(0.0)
          val percentageConsumedInferenceUsd: Double = consumptions.allowedUsd("inference_usd", limits.inference_usd).map(tusd => consumptions.inferenceUsd.toDouble / tusd.toDouble * 100.0).getOrElse(0.0)
          val percentageConsumedInferenceTokens: Double = consumptions.allowedUnits("inference_tokens", limits.inference_tokens).map(ttokens => consumptions.inferenceTokens.toDouble / ttokens.toDouble * 100.0).getOrElse(0.0)
          val percentageConsumedImageUsd: Double = consumptions.allowedUsd("image_usd", limits.image_usd).map(tusd => consumptions.imageUsd.toDouble / tusd.toDouble * 100.0).getOrElse(0.0)
          val percentageConsumedImageTokens: Double = consumptions.allowedUnits("image_tokens", limits.image_tokens).map(ttokens => consumptions.imageTokens.toDouble / ttokens.toDouble * 100.0).getOrElse(0.0)
          val percentageConsumedAudioUsd: Double = consumptions.allowedUsd("audio_usd", limits.audio_usd).map(tusd => consumptions.audioUsd.toDouble / tusd.toDouble * 100.0).getOrElse(0.0)
          val percentageConsumedAudioTokens: Double = consumptions.allowedUnits("audio_tokens", limits.audio_tokens).map(ttokens => consumptions.audioTokens.toDouble / ttokens.toDouble * 100.0).getOrElse(0.0)
          val percentageConsumedVideoUsd: Double = consumptions.allowedUsd("video_usd", limits.video_usd).map(tusd => consumptions.videoUsd.toDouble / tusd.toDouble * 100.0).getOrElse(0.0)
          val percentageConsumedVideoTokens: Double = consumptions.allowedUnits("video_tokens", limits.video_tokens).map(ttokens => consumptions.videoTokens.toDouble / ttokens.toDouble * 100.0).getOrElse(0.0)
          val percentageConsumedEmbeddingUsd: Double = consumptions.allowedUsd("embedding_usd", limits.embedding_usd).map(tusd => consumptions.embeddingUsd.toDouble / tusd.toDouble * 100.0).getOrElse(0.0)
          val percentageConsumedEmbeddingTokens: Double = consumptions.allowedUnits("embedding_tokens", limits.embedding_tokens).map(ttokens => consumptions.embeddingTokens.toDouble / ttokens.toDouble * 100.0).getOrElse(0.0)
          val percentageConsumedModerationUsd: Double = consumptions.allowedUsd("moderation_usd", limits.moderation_usd).map(tusd => consumptions.moderationUsd.toDouble / tusd.toDouble * 100.0).getOrElse(0.0)
          val percentageConsumedModerationTokens: Double = consumptions.allowedUnits("moderation_tokens", limits.moderation_tokens).map(ttokens => consumptions.moderationTokens.toDouble / ttokens.toDouble * 100.0).getOrElse(0.0)
          val percentageConsumedOcrUsd: Double = consumptions.allowedUsd("ocr_usd", limits.ocr_usd).map(tusd => consumptions.ocrUsd.toDouble / tusd.toDouble * 100.0).getOrElse(0.0)
          val percentageConsumedOcrPages: Double = consumptions.allowedUnits("ocr_pages", limits.ocr_pages).map(tpages => consumptions.ocrPages.toDouble / tpages.toDouble * 100.0).getOrElse(0.0)
          val percentageConsumedDecisionUsd: Double = consumptions.allowedUsd("decision_usd", limits.decision_usd).map(tusd => consumptions.decisionUsd.toDouble / tusd.toDouble * 100.0).getOrElse(0.0)
          val percentageConsumedDecisionTokens: Double = consumptions.allowedUnits("decision_tokens", limits.decision_tokens).map(ttokens => consumptions.decisionTokens.toDouble / ttokens.toDouble * 100.0).getOrElse(0.0)
          val maxPercentage = actionOnExceed.alertOnAlmostExceedPercentage.toDouble
          if (actionOnExceed.alertOnAlmostExceed && (percentageConsumedTotalUsd > maxPercentage || percentageConsumedTotalTokens > maxPercentage || percentageConsumedInferenceUsd > maxPercentage || percentageConsumedInferenceTokens > maxPercentage || percentageConsumedImageUsd > maxPercentage || percentageConsumedImageTokens > maxPercentage || percentageConsumedAudioUsd > maxPercentage || percentageConsumedAudioTokens > maxPercentage || percentageConsumedVideoUsd > maxPercentage || percentageConsumedVideoTokens > maxPercentage || percentageConsumedEmbeddingUsd > maxPercentage || percentageConsumedEmbeddingTokens > maxPercentage || percentageConsumedModerationUsd > maxPercentage || percentageConsumedModerationTokens > maxPercentage || percentageConsumedOcrUsd > maxPercentage || percentageConsumedOcrPages > maxPercentage || percentageConsumedDecisionUsd > maxPercentage || percentageConsumedDecisionTokens > maxPercentage)) {
             AlertEvent.generic("AiBudgetAlmostExceeded", "otoroshi")(Json.obj(
              "budget" -> json,
              "consumption" -> consumptions.json,
              "percentages" -> Json.obj(
                "total_usd" -> percentageConsumedTotalUsd,
                "total_tokens" -> percentageConsumedTotalTokens,
                "inference_usd" -> percentageConsumedInferenceUsd,
                "inference_tokens" -> percentageConsumedInferenceTokens,
                "image_usd" -> percentageConsumedImageUsd,
                "image_tokens" -> percentageConsumedImageTokens,
                "audio_usd" -> percentageConsumedAudioUsd,
                "audio_tokens" -> percentageConsumedAudioTokens,
                "video_usd" -> percentageConsumedVideoUsd,
                "video_tokens" -> percentageConsumedVideoTokens,
                "embedding_usd" -> percentageConsumedEmbeddingUsd,
                "embedding_tokens" -> percentageConsumedEmbeddingTokens,
                "moderation_usd" -> percentageConsumedModerationUsd,
                "moderation_tokens" -> percentageConsumedModerationTokens,
                "ocr_usd" -> percentageConsumedOcrUsd,
                "ocr_pages" -> percentageConsumedOcrPages,
                "decision_usd" -> percentageConsumedDecisionUsd,
                "decision_tokens" -> percentageConsumedDecisionTokens
              )
            ) ++ partition.map(p => Json.obj("partition" -> p)).getOrElse(Json.obj())).toAnalytics()
          }
          (true, consumptions.some)
        }
      }
    }
  }
}

object AiBudget {

  def countersPrefix(using env: Env): String = s"${env.storageRoot}:extensions:${AiExtension.id.cleanup}:aibudgets-counter"

  // how long the counters of a cycle are kept once it is over
  val countersRetentionMillis: Long = 7L * 24L * 3600L * 1000L

  // the counters of a consumption, in the order they are read back
  val consumptionCounters: Seq[String] = Seq(
    "total-usd", "total-tokens", "inference-usd", "inference-tokens", "image-usd", "image-tokens", "audio-usd",
    "audio-tokens", "video-usd", "video-tokens", "embedding-usd", "embedding-tokens", "moderation-usd",
    "moderation-tokens", "ocr-usd", "ocr-pages", "decision-usd", "decision-tokens",
  )

  // the partitions this node already added to the index of their cycle
  val indexedPartitions = new TrieMap[String, Unit]()

  // An extension taken or cancelled, and by whom (`by`): the backoffice user, the api key of the admin api, or the person
  // AI Studio names. The partition is the person the limit is extended for
  def auditExtension(audit: String, budget: AiBudget, partition: Option[String], consumption: Option[AiBudgetConsumptions], by: JsObject)(using env: Env): Unit = {
    otoroshi.events.AuditEvent.generic(audit)(Json.obj(
      "budget" -> Json.obj("id" -> budget.id, "name" -> budget.name),
      "partition" -> partition.map(JsString.apply).getOrElse(JsNull).as[JsValue],
      "cycle" -> budget.cycleId,
      "added" -> budget.extensionAmounts.filter(_ => audit == "AiBudgetExtended").map(a => JsObject(a.toSeq.sortBy(_._1).map { case (k, v) => k -> JsNumber(v) })).getOrElse(JsNull).as[JsValue],
      "extras" -> consumption.map(c => JsObject(c.extras.toSeq.sortBy(_._1).map { case (k, v) => k -> JsNumber(v) })).getOrElse(JsNull).as[JsValue],
      "extensions_taken" -> consumption.map(c => JsNumber(c.extensionsTaken)).getOrElse(JsNull).as[JsValue],
      "extensions_max" -> budget.extensions.map(e => JsNumber(e.maxPerCycle)).getOrElse(JsNull).as[JsValue],
      "by" -> by,
    )).toAnalytics()
  }

  val format = new Format[AiBudget] {
    override def writes(o: AiBudget): JsValue = o.location.jsonWithKey ++ Json.obj(
      "id" -> o.id,
      "name" -> o.name,
      "description" -> o.description,
      "metadata" -> o.metadata,
      "tags" -> JsArray(o.tags.map(JsString.apply)),
      "enabled" -> o.enabled,
      "start_at" -> o.startAt.toString(),
      "end_at" -> o.endAt.toString(),
      "duration" -> o.duration.json,
      //"renewals" -> o.renewals.map(_.json).getOrElse(JsNull).asValue,
      "limits" -> o.limits.json,
      "scope" -> o.scope.json,
      "action_on_exceed" -> o.actionOnExceed.json
    ) ++ o.extensions.map(e => Json.obj("extensions" -> e.json)).getOrElse(Json.obj())

    override def reads(json: JsValue): JsResult[AiBudget] = Try {
      val startAt = (json \ "start_at").asOpt[String].map(DateTime.parse).getOrElse(DateTime.now())
      val endAt = (json \ "end_at").asOpt[String].map(DateTime.parse).getOrElse(DateTime.now().plusDays(365))
      // val renewals = (json \ "renewals").asOpt[Int]
      val duration = (json \ "duration").as(using AiBudgetDuration.format)
      AiBudget(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = (json \ "id").as[String],
        name = (json \ "name").as[String],
        description = (json \ "description").as[String],
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
        startAt = startAt,
        endAt = endAt,
        // renewals = finalRenewals.some,
        duration = duration,
        limits = (json \ "limits").as(using AiBudgetLimits.format),
        scope = (json \ "scope").as(using AiBudgetScope.format),
        actionOnExceed = (json \ "action_on_exceed").as(using AiBudgetActionOnExceed.format),
        extensions = (json \ "extensions").asOpt[JsObject].map(AiBudgetExtensions.read),
      )
    } match {
      case Failure(ex) =>
        ex.printStackTrace()
        JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }

  def resource(env: Env, datastores: AiGatewayExtensionDatastores, states: AiGatewayExtensionState): Resource = {
    Resource(
      "AiBudget",
      "ai-budgets",
      "ai-budget",
      "ai-gateway.extensions.cloud-apim.com",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[AiBudget](
        format = AiBudget.format,
        clazz = classOf[AiBudget],
        keyf = id => datastores.budgetsDataStore.key(id),
        extractIdf = c => datastores.budgetsDataStore.extractId(c),
        extractIdJsonf = json => json.select("id").asString,
        idFieldNamef = () => "id",
        tmpl = (_, _, _) => {
          AiBudget(
            id = IdGenerator.namedId("budget", env),
            name = "AI Budget",
            description = "An AI Budget",
            metadata = Map.empty,
            tags = Seq.empty,
            location = EntityLocation.default,
            enabled = true,
            startAt = DateTime.now(),
            endAt = DateTime.now().plusDays(365),
            //renewals = Some(11),
            duration = AiBudgetDuration(30, AiBudgetDurationUnit.Day),
            limits = AiBudgetLimits(
              total_tokens = 10000000L.some, 
              total_usd = BigDecimal(200.0).some,
              inference_tokens = None,
              inference_usd = None,
              image_tokens = None,
              image_usd = None,
              audio_tokens = None,
              audio_usd = None,
              video_tokens = None,
              video_usd = None,
              embedding_tokens = None,
              embedding_usd = None,
              moderation_tokens = None,
              moderation_usd = None,
              ocr_pages = None,
              ocr_usd = None,
            ),
            scope = AiBudgetScope(
              extractFromApikeyMeta = true,
              extractFromUserMeta = true,
              extractFromUserAuthModuleMeta = true,
              extractFromApikeyGroupMeta = true, 
              extractFromProviderMeta = true, 
              apikeys = Seq.empty, 
              users = Seq.empty, 
              groups = Seq.empty, 
              providers = Seq.empty, 
              models = Seq.empty,
              alwaysApplyRules = false,
              rules = Seq.empty, 
              rulesMatchMode = AiBudgetScopeMode.All
            ),
            actionOnExceed = AiBudgetActionOnExceed(
              AiBudgetActionOnExceedMode.Block,
              alertOnExceed = true,
              alertOnAlmostExceed = true,
              alertOnAlmostExceedPercentage = 80
            )
          ).json
        },
        canRead = true,
        canCreate = true,
        canUpdate = true,
        canDelete = true,
        canBulk = true,
        stateAll = () => states.allBudgets(),
        stateOne = id => states.budget(id),
        stateUpdate = values => states.updateBudgets(values)
      )
    )
  }
}

/**
 * Keeps the counters of the budgets from piling up, on the leaders only (a worker gets them with the state). The
 * counters of a cycle over for more than `AiBudget.countersRetentionMillis` are removed, the ones without an expiration
 * get one (counters created before they expired), and the ones of a budget that is gone are removed once it was found
 * gone on two runs in a row, a budget being created when a run starts not being gone.
 */
object AiBudgetCountersCleaner {

  private val schedulerRef = new AtomicReference[Cancellable]()
  // the budgets found gone on the last run
  private val gone = new AtomicReference[Set[String]](Set.empty)

  def start(env: Env, ext: AiExtension): Unit = {
    if (!env.clusterConfig.mode.isWorker) {
      given ec: ExecutionContext = env.otoroshiExecutionContext
      given ev: Env = env
      schedulerRef.set(env.otoroshiScheduler.scheduleAtFixedRate(1.minute, 24.hours) { () =>
        run(ext)
      })
    }
  }

  def stop(): Unit = {
    Option(schedulerRef.get()).foreach(_.cancel())
  }

  def run(ext: AiExtension)(using ec: ExecutionContext, env: Env): Future[Unit] = {
    AiBudget.indexedPartitions.clear()
    val prefix = s"${AiBudget.countersPrefix}:"
    val budgets = ext.states.allBudgets().map(b => b.id -> b).toMap
    val now = System.currentTimeMillis()
    // a counter is `<budget>:<cycle>:...`, and a budget id may hold a colon
    def parse(key: String): Option[(String, String)] = {
      val rest = key.drop(prefix.length)
      val id = budgets.keys.find(id => rest.startsWith(s"$id:")).getOrElse(rest.takeWhile(_ != ':'))
      rest.drop(id.length + 1).split(":").headOption.filter(_.nonEmpty).map(cycle => (id, cycle))
    }
    env.datastores.rawDataStore.keys(s"$prefix*").flatMap { keys =>
      val counters = keys.flatMap(key => parse(key).map { case (id, cycle) => (key, id, cycle) })
      val goneNow = counters.map(_._2).filterNot(budgets.contains).toSet
      val goneTwice = goneNow.intersect(gone.getAndSet(goneNow))
      val removed = counters.collect {
        case (key, id, _) if goneTwice.contains(id) => key
        case (key, id, cycle) if budgets.get(id).flatMap(_.cycleEnd(cycle)).exists(_ + AiBudget.countersRetentionMillis < now) => key
      }
      val kept = counters.collect { case (key, id, cycle) if !removed.contains(key) && budgets.get(id).flatMap(_.cycleEnd(cycle)).isDefined => (key, budgets(id), cycle) }
      for {
        _ <- if (removed.isEmpty) ().vfuture else env.datastores.rawDataStore.del(removed).map(_ => ())
        _ <- Future.sequence(kept.map { case (key, budget, cycle) =>
          env.datastores.rawDataStore.pttl(key).map(ttl => if (ttl == -1L) budget.expireCounter(key, cycle))
        })
      } yield ()
    }.recover { case e =>
      ext.logger.error("error while cleaning the counters of the budgets", e)
      ()
    }
  }
}

/**
 * The partition of a call, for a budget that counts its consumers apart: `scope.partition_by` is an expression of the
 * otoroshi expression language, `${user.email || apikey.metadata.ai_studio_owner}` for the budgets "by consumer" of
 * AI Studio, `${consumer.id}` for one partition by user and one by api key. The provider and the model the call counts
 * for are read with `${ctx.ai.provider.id}`, `${ctx.ai.provider.name}` and `${ctx.ai.model}`.
 *
 * The expression language answers `bad-expr`, or a value starting with `no-`, for what it does not find, which is what
 * its chains skip: a placeholder that finds nothing leaves the call without a partition, and the budget does not apply
 * to it. A default value (`${apikey.metadata.team:none}`, `${a || b :: none}`) makes such calls count together.
 */
object BudgetPartition {

  private val placeholder = "\\$\\{([^}]*)\\}".r

  // the text around the placeholders (Left) and the expressions of the placeholders (Right)
  private val parsed = new TrieMap[String, Seq[Either[String, String]]]()

  private def parts(template: String): Seq[Either[String, String]] = parsed.getOrElseUpdate(template, {
    val matches = placeholder.findAllMatchIn(template).toSeq
    val starts = 0 +: matches.map(_.end)
    val ends = matches.map(_.start) :+ template.length
    val texts = starts.zip(ends).map { case (from, to) => template.substring(from, to) }
    texts.zipAll(matches.map(m => Some(m.group(1))), "", None).flatMap { case (text, expr) =>
      Seq(Left(text)).filter(_.value.nonEmpty) ++ expr.map(Right(_)).toSeq
    }
  })

  // what the expression language answers when it finds nothing, or when it fails on an expression
  def found(value: String): Boolean = value != "bad-expr" && !value.startsWith("no-") && !value.contains("${")

  // partitions are compared without their case: an email does not tell its case apart
  def normalize(value: String): String = value.trim.toLowerCase

  // the partition in the keys of the counters: a fixed length, without anything a key pattern would read
  def segment(partition: String): String = partition.sha256.take(32)

  def resolve(template: String, attrs: TypedMap, provider: Option[Entity], model: Option[String])(using env: Env): Option[String] = {
    // the attributes of the call are never changed: the expression reads a copy, with the provider and the model in its context
    val copy = TypedMap.empty
    attrs.toMap.foreach { case (key, value) => copy.put(TypedEntry(key.asInstanceOf[TypedKey[Any]], value)) }
    val ai = provider.map(p => Map("ai.provider.id" -> p.theId, "ai.provider.name" -> p.theName)).getOrElse(Map.empty[String, String]) ++
      model.map(m => Map("ai.model" -> m)).getOrElse(Map.empty[String, String])
    copy.put(otoroshi.plugins.Keys.ElCtxKey -> (attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty[String, String]) ++ ai))
    val values = parts(template).map {
      case Left(text)  => Some(text)
      case Right(expr) => Some(s"$${$expr}".evaluateEl(copy)).filter(found)
    }
    if (values.exists(_.isEmpty)) None else Some(normalize(values.flatten.mkString)).filter(_.nonEmpty)
  }
}

trait AiBudgetsDataStore extends BasicStore[AiBudget] {
  def findMatchingBudgets(ctx: JsValue, apikey: Option[ApiKey], user: Option[PrivateAppsUser], provider: Option[Entity], model: Option[String]): Future[Seq[AiBudget]]
  // the matching budgets with the partition of the call for those that count their consumers apart, the ones the call
  // has no partition for left aside
  def findMatchingBudgetsWithPartitions(ctx: JsValue, apikey: Option[ApiKey], user: Option[PrivateAppsUser], provider: Option[Entity], model: Option[String], attrs: TypedMap): Future[Seq[(AiBudget, Option[String])]]
  // `scope` is the provider and the model the usage counts for, the ones of the attributes otherwise. `claims` is
  // the budgets the usage was already counted against: a budget in it is left alone, the others are added to it
  def updateUsage(totalCost: Option[BigDecimal], totalTokens: Option[Long], usageKind: AiBudgetUsageKind, attrs: TypedMap, scope: Option[(Entity, String)] = None, claims: Option[java.util.Set[String]] = None): Future[WithingBudgetConsumptions]
}

object AiBudgetsDataStore {
  def handleWithinBudget[A](attrs: TypedMap)(notInBudget: String => Future[A], inBudget: => Future[A])(using env: Env, ec: ExecutionContext): Future[A] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    if (ext.budgetsEnabled) {
      val apikey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey)
      val user = attrs.get(otoroshi.plugins.Keys.UserKey)
      val snowflake = attrs.get(otoroshi.plugins.Keys.SnowFlakeKey)
      val request = attrs.get(otoroshi.plugins.Keys.RequestKey).map(r => JsonHelpers.requestToJson(r, attrs))
      val route = attrs.get(otoroshi.next.plugins.Keys.RouteKey)
      val provider = attrs.get(ChatClientWithAuding.ProviderKey)
      val model = attrs.get(ChatClientWithAuding.ModelKey)
      val ctx = Json.obj(
        "match" -> "all",
        "snowflake" -> snowflake,
        "apikey" -> apikey.map(_.lightJson).getOrElse(JsNull).as[JsValue],
        "user" -> user.map(_.lightJson).getOrElse(JsNull).as[JsValue],
        "route" -> route.map(_.json).getOrElse(JsNull).as[JsValue],
        "request" -> request,
        "config" -> Json.obj(),
        "global_config" -> Json.obj(),
        "provider" -> provider.map(_.json).getOrElse(JsNull).as[JsValue],
        "model" -> model.map(_.json).getOrElse(JsNull).as[JsValue],
        //"attrs" -> attrs.json
      )
      ext.datastores.budgetsDataStore.findMatchingBudgetsWithPartitions(ctx, apikey, user, provider, model, attrs).flatMap { matching =>
        matching.filterAsync { case (budget, partition) => budget.isNotWithinBudget(attrs, partition) }.flatMap { exceeded =>
          val budgets = exceeded.map(_._1)
          if (budgets.isEmpty) {
            inBudget
          } else {
            val soft = budgets.forall(_.actionOnExceed.mode == AiBudgetActionOnExceedMode.Soft)
            val budgetIds = budgets.map(_.id)
            val partitions = exceeded.collect { case (budget, Some(partition)) => budget.id -> JsString(partition) }
            if (budgets.exists(_.actionOnExceed.alertOnExceed)) {
              AlertEvent.generic("AiBudgetExceeded", "otoroshi")(Json.obj(
                "budgets" -> budgetIds,
                "apikey" -> apikey.map(_.lightJson).getOrElse(JsNull).as[JsValue],
                "user" -> user.map(_.lightJson).getOrElse(JsNull).as[JsValue],
                "model" -> model.map(JsString.apply).getOrElse(JsNull).as[JsValue],
                // id and name only: the provider entity carries its connection credentials
                "provider" -> provider.map(p => Json.obj("id" -> p.internalId, "name" -> p.theName)).getOrElse(JsNull).as[JsValue],
                "route" -> route.map(_.json).getOrElse(JsNull).as[JsValue],
              ) ++ (if (partitions.isEmpty) Json.obj() else Json.obj("partitions" -> JsObject(partitions)))).toAnalytics()
            }
            if (soft) {
              inBudget
            } else {
              // the message of a budget that actually blocks the call, not of a soft one exceeded at the same time
              val message = budgets
                .filter(_.actionOnExceed.mode != AiBudgetActionOnExceedMode.Soft)
                .flatMap(_.actionOnExceed.errorMessage)
                .headOption
                .getOrElse(AiBudgetActionOnExceed.defaultErrorMessage)
              notInBudget(message)
            }
          }
        }
      }
    } else {
      inBudget
    }
  }
}

case class WithingBudgetConsumption(budget: AiBudget, consumption: Option[AiBudgetConsumptions]) {
  def json: JsValue = Json.obj(
    "budget_id" -> budget.id,
    "consumption" -> consumption.map(_.jsonWithRemaining(budget)).getOrElse(JsNull).asValue,
  )
}

case class WithingBudgetConsumptions(budgets: Seq[WithingBudgetConsumption] = Seq.empty) {
  def json: JsValue = JsArray(budgets.map(_.json))
}

class KvAiBudgetsDataStore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
  extends AiBudgetsDataStore
    with RedisLikeStore[AiBudget] {

  override def fmt: Format[AiBudget] = AiBudget.format

  override def redisLike(using env: Env): RedisLike = redisCli

  override def key(id: String): String = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:aibudgets:$id"

  override def extractId(value: AiBudget): String = value.id

  def updateUsage(cost: Option[BigDecimal], tokens: Option[Long], usageKind: AiBudgetUsageKind, attrs: TypedMap, scope: Option[(Entity, String)] = None, claims: Option[java.util.Set[String]] = None): Future[WithingBudgetConsumptions] = {
    val ext = _env.adminExtensions.extension[AiExtension].get
    if (ext.budgetsEnabled) {
      if (ext.states.hasBudgets && ((cost.isDefined && cost.get.>(BigDecimal(0))) || (tokens.isDefined && tokens.get > 0L))) {
        given ec: ExecutionContext = _env.analyticsExecutionContext
        given env: Env = _env
        val apikey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey)
        val user = attrs.get(otoroshi.plugins.Keys.UserKey)
        val snowflake = attrs.get(otoroshi.plugins.Keys.SnowFlakeKey)
        val request = attrs.get(otoroshi.plugins.Keys.RequestKey).map(r => JsonHelpers.requestToJson(r, attrs))
        val route = attrs.get(otoroshi.next.plugins.Keys.RouteKey)
        val provider = scope.map(_._1).orElse(attrs.get(ChatClientWithAuding.ProviderKey))
        val model = scope.map(_._2).orElse(attrs.get(ChatClientWithAuding.ModelKey))
        val ctx = Json.obj(
          "match" -> "all",
          "snowflake" -> snowflake,
          "apikey" -> apikey.map(_.lightJson).getOrElse(JsNull).as[JsValue],
          "user" -> user.map(_.lightJson).getOrElse(JsNull).as[JsValue],
          "route" -> route.map(_.json).getOrElse(JsNull).as[JsValue],
          "request" -> request,
          "config" -> Json.obj(),
          "global_config" -> Json.obj(),
          "provider" -> provider.map(_.json).getOrElse(JsNull).as[JsValue],
          "model" -> model.map(_.json).getOrElse(JsNull).as[JsValue],
          //"attrs" -> attrs.json
        )
        findMatchingBudgetsWithPartitions(ctx, apikey, user, provider, model, attrs).flatMap { matching =>
          // claimed here, as soon as the budgets are known: whoever else counts the same usage finds them taken
          val budgets = matching.filter { case (budget, _) => claims.forall(_.add(budget.id)) }
          budgets.mapAsync { case (budget, partition) =>
            budget.isWithinBudgetWithConsumption(attrs, partition).map {
              case (withinBudget, consumption) =>
                if (withinBudget) {
                  usageKind.updateUsage(budget, cost, tokens, attrs, partition)
                }
                WithingBudgetConsumption(budget, consumption)
            }
          }.map(budgets => WithingBudgetConsumptions(budgets))
        }
      } else {
        WithingBudgetConsumptions().vfuture
      }
    } else {
      WithingBudgetConsumptions().vfuture
    }
  }

  def findMatchingBudgetsWithPartitions(ctx: JsValue, apikey: Option[ApiKey], user: Option[PrivateAppsUser], provider: Option[Entity], model: Option[String], attrs: TypedMap): Future[Seq[(AiBudget, Option[String])]] = {
    given env: Env = _env
    given ec: ExecutionContext = _env.otoroshiExecutionContext
    findMatchingBudgets(ctx, apikey, user, provider, model).map { budgets =>
      budgets.flatMap { budget =>
        budget.scope.partitionBy match {
          case None             => Seq((budget, None))
          case Some(expression) => BudgetPartition.resolve(expression, attrs, provider, model).map(p => (budget, Some(p))).toSeq
        }
      }
    }
  }

  def findMatchingBudgets(ctx: JsValue, apikey: Option[ApiKey], user: Option[PrivateAppsUser], provider: Option[Entity], model: Option[String]): Future[Seq[AiBudget]] = {
    given env: Env = _env
    val ext = _env.adminExtensions.extension[AiExtension].get
    if (ext.budgetsEnabled) {
      if (ext.states.hasBudgets) {
        val authModule = user.map(_.authConfigId).flatMap(id => _env.proxyState.authModule(id))
        val groups = apikey.toSeq.flatMap(_.authorizedEntities.collect {
          case ServiceGroupIdentifier(id) => id
        }).flatMap(id => _env.proxyState.serviceGroup(id))
        val apikeyRef = apikey.flatMap(_.metadata.get("ai_budget_ref"))
        val userRef = user.flatMap(_.metadata.get("ai_budget_ref"))
        val providerRef = provider.flatMap(_.theMetadata.get("ai_budget_ref"))
        val authModuleRef = authModule.flatMap(_.metadata.get("ai_budget_ref"))
        val groupsRef = groups.flatMap(_.metadata.get("ai_budget_ref"))
        ext.states
          .allBudgets()
          .filter(b => b.enabled && b.startAt.isBeforeNow && b.endAt.isAfterNow)
          .filter {
            case budget if budget.scope.alwaysApplyRules =>
              val res = budget.matchesRules(ctx)
              if (ext.logger.isDebugEnabled) ext.logger.debug(s"apply rules first: ${res}")
              res
            case _ => true
          }
          .filter {
            case budget if budget.scope.extractFromProviderMeta && (providerRef.contains(budget.id) || providerRef.contains(budget.slugName)) => true
            case budget if budget.scope.extractFromApikeyMeta && (apikeyRef.contains(budget.id) || apikeyRef.contains(budget.slugName)) => true
            case budget if budget.scope.extractFromUserMeta && (userRef.contains(budget.id) || userRef.contains(budget.slugName)) => true
            case budget if budget.scope.extractFromUserAuthModuleMeta && (authModuleRef.contains(budget.id) || authModuleRef.contains(budget.slugName)) => true
            case budget if budget.scope.extractFromApikeyGroupMeta && (groupsRef.contains(budget.id) || groupsRef.contains(budget.slugName)) => true
            case budget if budget.matches(ctx, apikey, user, provider, model) => true
            case _ => false
          }
          .vfuture
      } else {
        Seq.empty[AiBudget].vfuture
      }
    } else {
      Seq.empty[AiBudget].vfuture
    }
  }
}
