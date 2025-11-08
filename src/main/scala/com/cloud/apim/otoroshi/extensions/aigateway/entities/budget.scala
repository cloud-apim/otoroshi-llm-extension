package com.cloud.apim.otoroshi.extensions.aigateway.entities

import akka.actor.Cancellable
import akka.http.scaladsl.util.FastFuture
import com.cloud.apim.otoroshi.extensions.aigateway.decorators.ChatClientWithAuding
import org.joda.time.DateTime
import otoroshi.api._
import otoroshi.cluster.Cluster
import otoroshi.env.Env
import otoroshi.events.AlertEvent
import otoroshi.gateway.Retry
import otoroshi.models._
import otoroshi.next.extensions.AdminExtensionId
import otoroshi.next.utils.JsonHelpers
import otoroshi.security.IdGenerator
import otoroshi.storage._
import otoroshi.utils.syntax.implicits._
import otoroshi.utils.{JsonPathValidator, RegexPool, TypedMap}
import otoroshi_plugins.com.cloud.apim.extensions.aigateway._
import play.api.libs.json._
import play.api.libs.ws.WSAuthScheme

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic._
import scala.collection.concurrent.TrieMap
import scala.concurrent._
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

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

  private val schedulerRef = new AtomicReference[Cancellable]()
  private val counter = new AtomicInteger(0)

  def start(env: Env, ext: AiExtension): Unit = {
    if (env.clusterConfig.mode.isWorker) {
      implicit val ev = env
      implicit val ec = env.otoroshiExecutionContext
      schedulerRef.set(env.otoroshiScheduler.scheduleAtFixedRate(10.seconds, env.clusterConfig.worker.state.pollEvery.millis) { () =>
        sendDeltas(env, ext)
      })
    }
  }

  def stop(): Unit = {
    Option(schedulerRef.get()).foreach(_.cancel())
  }

  private def reset(): Unit = {
    totalUsdCounters.clear()
    totalTokensCounters.clear()
    inferenceUsdCounters.clear()
    inferenceTokensCounters.clear()
    imageUsdCounters.clear()
    imageTokensCounters.clear()
    audioUsdCounters.clear()
    audioTokensCounters.clear()
    videoUsdCounters.clear()
    videoTokensCounters.clear()
    embeddingUsdCounters.clear()
    embeddingTokensCounters.clear()
    moderationUsdCounters.clear()
    moderationTokensCounters.clear()
  }

  private def otoroshiUrl(env: Env): String = {
    val config = env.clusterConfig
    val count = counter.incrementAndGet() % (if (config.leader.urls.nonEmpty) config.leader.urls.size else 1)
    config.leader.urls.zipWithIndex.find(t => t._2 == count).map(_._1).getOrElse(config.leader.urls.head)
  }

  private def sendDeltas(env: Env, ext: AiExtension): Unit = {
    import otoroshi.utils.http.Implicits._
    implicit val ev = env
    implicit val ec = env.otoroshiExecutionContext
    implicit val sc = env.otoroshiScheduler
    implicit val mat = env.otoroshiMaterializer
    if (env.clusterConfig.mode.isWorker) {
      val config = env.clusterConfig
      val payload = Json.obj(
        "total_usd" -> JsObject(totalUsdCounters.toMap.mapValues(_.sum.json)),
        "total_tokens" -> JsObject(totalTokensCounters.toMap.mapValues(_.get.json)),
        "inference_usd" -> JsObject(inferenceUsdCounters.toMap.mapValues(_.sum.json)),
        "inference_tokens" -> JsObject(inferenceTokensCounters.toMap.mapValues(_.get.json)),
        "image_usd" -> JsObject(imageUsdCounters.toMap.mapValues(_.sum.json)),
        "image_tokens" -> JsObject(imageTokensCounters.toMap.mapValues(_.get.json)),
        "audio_usd" -> JsObject(audioUsdCounters.toMap.mapValues(_.sum.json)),
        "audio_tokens" -> JsObject(audioTokensCounters.toMap.mapValues(_.get.json)),
        "video_usd" -> JsObject(videoUsdCounters.toMap.mapValues(_.sum.json)),
        "video_tokens" -> JsObject(videoTokensCounters.toMap.mapValues(_.get.json)),
        "embedding_usd" -> JsObject(embeddingUsdCounters.toMap.mapValues(_.sum.json)),
        "embedding_tokens" -> JsObject(embeddingTokensCounters.toMap.mapValues(_.get.json)),
        "moderation_usd" -> JsObject(moderationUsdCounters.toMap.mapValues(_.sum.json)),
        "moderation_tokens" -> JsObject(moderationTokensCounters.toMap.mapValues(_.get.json)),
      )
      Retry
        .retry(
          times = config.worker.retries,
          delay = config.retryDelay,
          factor = config.retryFactor,
          ctx = "leader-save-workflow-session"
        ) { tryCount =>
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
                reset()
              }
              Some(Json.parse(resp.body))
            }
        }
        .recover { case e =>
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
  def updateUsage(budget: AiBudget, cost: Option[BigDecimal], tokens: Option[Long], attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Unit
}
object AiBudgetUsageKind {
  case object Inference extends AiBudgetUsageKind {
    def updateUsage(budget: AiBudget, cost: Option[BigDecimal], tokens: Option[Long], attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Unit = {
      cost.foreach { c => 
        budget.incrTotalUsd(c)
        budget.incrInferenceUsd(c)
      }
      tokens.foreach { c => 
        budget.incrTotalTokens(c)
        budget.incrInferenceTokens(c)
      }
    }
  }
  case object Image extends AiBudgetUsageKind {
    def updateUsage(budget: AiBudget, cost: Option[BigDecimal], tokens: Option[Long], attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Unit = {
      cost.foreach { c => 
        budget.incrTotalUsd(c)
        budget.incrImageUsd(c)
      }
      tokens.foreach { c => 
        budget.incrTotalTokens(c)
        budget.incrImageTokens(c)
      }
    }
  }
  case object Audio extends AiBudgetUsageKind {
    def updateUsage(budget: AiBudget, cost: Option[BigDecimal], tokens: Option[Long], attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Unit = {
      cost.foreach { c => 
        budget.incrTotalUsd(c)
        budget.incrAudioUsd(c)
      }
      tokens.foreach { c => 
        budget.incrTotalTokens(c)
        budget.incrAudioTokens(c)
      }
    }
  }
  case object Video extends AiBudgetUsageKind {
    def updateUsage(budget: AiBudget, cost: Option[BigDecimal], tokens: Option[Long], attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Unit = {
      cost.foreach { c => 
        budget.incrTotalUsd(c)
        budget.incrVideoUsd(c)
      }
      tokens.foreach { c => 
        budget.incrTotalTokens(c)
        budget.incrVideoTokens(c)
      }
    }
  }
  case object Embedding extends AiBudgetUsageKind {
    def updateUsage(budget: AiBudget, cost: Option[BigDecimal], tokens: Option[Long], attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Unit = {
      cost.foreach { c => 
        budget.incrTotalUsd(c)
        budget.incrEmbeddingUsd(c)
      }
      tokens.foreach { c => 
        budget.incrTotalTokens(c)
        budget.incrEmbeddingTokens(c)
      }
    }
  }
  case object Moderation extends AiBudgetUsageKind {
    def updateUsage(budget: AiBudget, cost: Option[BigDecimal], tokens: Option[Long], attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Unit = {
      cost.foreach { c => 
        budget.incrTotalUsd(c)
        budget.incrModerationUsd(c)
      }
      tokens.foreach { c => 
        budget.incrTotalTokens(c)
        budget.incrModerationTokens(c)
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
) {
  def json: JsValue = AiBudgetLimits.format.writes(this)
}

object AiBudgetLimits {
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
        moderation_usd = (json \ "moderation_usd").asOpt[BigDecimal].filter(_ > -1L)
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
    )
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
      )
    } match {
      case Failure(ex) => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
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

case class AiBudgetActionOnExceed(mode: AiBudgetActionOnExceedMode, alertOnExceed: Boolean, alertOnAlmostExceed: Boolean, alertOnAlmostExceedPercentage: Int) {
  def json: JsValue = AiBudgetActionOnExceed.format.writes(this)
}
object AiBudgetActionOnExceed {
  val format = new Format[AiBudgetActionOnExceed] {
    override def writes(o: AiBudgetActionOnExceed): JsValue = Json.obj(
      "mode" -> o.mode.json,
      "alert_on_exceed" -> o.alertOnExceed,
      "alert_on_almost_exceed" -> o.alertOnAlmostExceed,
      "alert_on_almost_exceed_percentage" -> o.alertOnAlmostExceedPercentage,
    )
    override def reads(json: JsValue): JsResult[AiBudgetActionOnExceed] = Try {
      AiBudgetActionOnExceed(
        mode = AiBudgetActionOnExceedMode.fromString((json \ "mode").asOpt[String].getOrElse("soft")),
        alertOnExceed = (json \ "alert_on_exceed").asOpt[Boolean].getOrElse(true),
        alertOnAlmostExceed = (json \ "alert_on_almost_exceed").asOpt[Boolean].getOrElse(true),
        alertOnAlmostExceedPercentage = (json \ "alert_on_almost_exceed_percentage").asOpt[Int].getOrElse(80),
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
) {
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
  )
  def jsonWithRemaining(budget: AiBudget): JsValue = json.asObject ++ Json.obj(
    "remaining_total_usd" -> JsNumber(budget.limits.total_usd.map(v => v - totalUsd).getOrElse(BigDecimal(0))),
    "remaining_total_tokens" -> JsNumber(BigDecimal(budget.limits.total_tokens.map(_ - totalTokens).getOrElse(0L))),
    "remaining_inference_usd" -> JsNumber(budget.limits.inference_usd.map(_ - inferenceUsd).getOrElse(BigDecimal(0))),
    "remaining_inference_tokens" -> JsNumber(BigDecimal(budget.limits.inference_tokens.map(_ - inferenceTokens).getOrElse(0L))),
    "remaining_image_usd" -> JsNumber(budget.limits.image_usd.map(_ - imageUsd).getOrElse(BigDecimal(0))),
    "remaining_image_tokens" -> JsNumber(BigDecimal(budget.limits.image_tokens.map(_ - imageTokens).getOrElse(0L))),
    "remaining_audio_usd" -> JsNumber(budget.limits.audio_usd.map(_ - audioUsd).getOrElse(BigDecimal(0))),
    "remaining_audio_tokens" -> JsNumber(BigDecimal(budget.limits.audio_tokens.map(_ - audioTokens).getOrElse(0L))),
    "remaining_video_usd" -> JsNumber(budget.limits.video_usd.map(_ - videoUsd).getOrElse(BigDecimal(0))),
    "remaining_video_tokens" -> JsNumber(BigDecimal(budget.limits.video_tokens.map(_ - videoTokens).getOrElse(0L))),
    "remaining_embedding_usd" -> JsNumber(budget.limits.embedding_usd.map(_ - embeddingUsd).getOrElse(BigDecimal(0))),
    "remaining_embedding_tokens" -> JsNumber(BigDecimal(budget.limits.embedding_tokens.map(_ - embeddingTokens).getOrElse(0L))),
    "remaining_moderation_usd" -> JsNumber(budget.limits.moderation_usd.map(_ - moderationUsd).getOrElse(BigDecimal(0))),
    "remaining_moderation_tokens" -> JsNumber(BigDecimal(budget.limits.moderation_tokens.map(_ - moderationTokens).getOrElse(0L))),
    "allowed_total_usd" -> budget.limits.total_usd,
    "allowed_total_tokens" -> budget.limits.total_tokens,
    "allowed_inference_usd" -> budget.limits.inference_usd,
    "allowed_inference_tokens" -> budget.limits.inference_tokens,
    "allowed_image_usd" -> budget.limits.image_usd,
    "allowed_image_tokens" -> budget.limits.image_tokens,
    "allowed_audio_usd" -> budget.limits.audio_usd,
    "allowed_audio_tokens" -> budget.limits.audio_tokens,
    "allowed_video_usd" -> budget.limits.video_usd,
    "allowed_video_tokens" -> budget.limits.video_tokens,
    "allowed_embedding_usd" -> budget.limits.embedding_usd,
    "allowed_embedding_tokens" -> budget.limits.embedding_tokens,
    "allowed_moderation_usd" -> budget.limits.moderation_usd,
    "allowed_moderation_tokens" -> budget.limits.moderation_tokens,
  )
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

  def slugName: String = metadata.getOrElse("endpoint_name", name).slugifyWithSlash.replaceAll("-+", "_")

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

  def totalUsdKey(implicit env: Env): String = s"${env.storageRoot}:extensions:${AiExtension.id.cleanup}:aibudgets-counter:$id:$cycleId:total-usd"
  def totalTokensKey(implicit env: Env): String = s"${env.storageRoot}:extensions:${AiExtension.id.cleanup}:aibudgets-counter:$id:$cycleId:total-tokens"

  def inferenceUsdKey(implicit env: Env): String = s"${env.storageRoot}:extensions:${AiExtension.id.cleanup}:aibudgets-counter:$id:$cycleId:inference-usd"
  def inferenceTokensKey(implicit env: Env): String = s"${env.storageRoot}:extensions:${AiExtension.id.cleanup}:aibudgets-counter:$id:$cycleId:inference-tokens"

  def imageUsdKey(implicit env: Env): String = s"${env.storageRoot}:extensions:${AiExtension.id.cleanup}:aibudgets-counter:$id:$cycleId:image-usd"
  def imageTokensKey(implicit env: Env): String = s"${env.storageRoot}:extensions:${AiExtension.id.cleanup}:aibudgets-counter:$id:$cycleId:image-tokens"

  def audioUsdKey(implicit env: Env): String = s"${env.storageRoot}:extensions:${AiExtension.id.cleanup}:aibudgets-counter:$id:$cycleId:audio-usd"
  def audioTokensKey(implicit env: Env): String = s"${env.storageRoot}:extensions:${AiExtension.id.cleanup}:aibudgets-counter:$id:$cycleId:audio-tokens"

  def videoUsdKey(implicit env: Env): String = s"${env.storageRoot}:extensions:${AiExtension.id.cleanup}:aibudgets-counter:$id:$cycleId:video-usd"
  def videoTokensKey(implicit env: Env): String = s"${env.storageRoot}:extensions:${AiExtension.id.cleanup}:aibudgets-counter:$id:$cycleId:video-tokens"

  def embeddingUsdKey(implicit env: Env): String = s"${env.storageRoot}:extensions:${AiExtension.id.cleanup}:aibudgets-counter:$id:$cycleId:embedding-usd"
  def embeddingTokensKey(implicit env: Env): String = s"${env.storageRoot}:extensions:${AiExtension.id.cleanup}:aibudgets-counter:$id:$cycleId:embedding-tokens"

  def moderationUsdKey(implicit env: Env): String = s"${env.storageRoot}:extensions:${AiExtension.id.cleanup}:aibudgets-counter:$id:$cycleId:moderation-usd"
  def moderationTokensKey(implicit env: Env): String = s"${env.storageRoot}:extensions:${AiExtension.id.cleanup}:aibudgets-counter:$id:$cycleId:moderation-tokens"

  def incrTotalUsd(by: BigDecimal)(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    if (env.clusterConfig.mode.isWorker) {
      AiBudgetClusterAgent.totalUsdCounters.getOrElseUpdate(s"$id:$cycleId", new DoubleAdder()).add(by.toDouble)
    }
    env.datastores.rawDataStore.incrby(totalUsdKey, by.*(BigDecimal(1000000000)).toLong)
  }

  def incrTotalTokens(by: Long)(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    if (env.clusterConfig.mode.isWorker) {
      AiBudgetClusterAgent.totalTokensCounters.getOrElseUpdate(s"$id:$cycleId", new AtomicLong()).addAndGet(by)
    }
    env.datastores.rawDataStore.incrby(totalTokensKey, by)
  }

  def incrInferenceUsd(by: BigDecimal)(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    if (env.clusterConfig.mode.isWorker) {
      AiBudgetClusterAgent.inferenceUsdCounters.getOrElseUpdate(s"$id:$cycleId", new DoubleAdder()).add(by.toDouble)
    }
    env.datastores.rawDataStore.incrby(inferenceUsdKey, by.*(BigDecimal(1000000000)).toLong)
  }

  def incrInferenceTokens(by: Long)(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    if (env.clusterConfig.mode.isWorker) {
      AiBudgetClusterAgent.inferenceTokensCounters.getOrElseUpdate(s"$id:$cycleId", new AtomicLong()).addAndGet(by)
    }
    env.datastores.rawDataStore.incrby(inferenceTokensKey, by)
  }

  def incrImageUsd(by: BigDecimal)(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    if (env.clusterConfig.mode.isWorker) {
      AiBudgetClusterAgent.imageUsdCounters.getOrElseUpdate(s"$id:$cycleId", new DoubleAdder()).add(by.toDouble)
    }
    env.datastores.rawDataStore.incrby(imageUsdKey, by.*(BigDecimal(1000000000)).toLong)
  }

  def incrImageTokens(by: Long)(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    if (env.clusterConfig.mode.isWorker) {
      AiBudgetClusterAgent.imageTokensCounters.getOrElseUpdate(s"$id:$cycleId", new AtomicLong()).addAndGet(by)
    }
    env.datastores.rawDataStore.incrby(imageTokensKey, by)
  }

  def incrAudioUsd(by: BigDecimal)(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    if (env.clusterConfig.mode.isWorker) {
      AiBudgetClusterAgent.audioUsdCounters.getOrElseUpdate(s"$id:$cycleId", new DoubleAdder()).add(by.toDouble)
    }
    env.datastores.rawDataStore.incrby(audioUsdKey, by.*(BigDecimal(1000000000)).toLong)
  }

  def incrAudioTokens(by: Long)(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    if (env.clusterConfig.mode.isWorker) {
      AiBudgetClusterAgent.audioTokensCounters.getOrElseUpdate(s"$id:$cycleId", new AtomicLong()).addAndGet(by)
    }
    env.datastores.rawDataStore.incrby(audioTokensKey, by)
  }

  def incrVideoUsd(by: BigDecimal)(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    if (env.clusterConfig.mode.isWorker) {
      AiBudgetClusterAgent.videoUsdCounters.getOrElseUpdate(s"$id:$cycleId", new DoubleAdder()).add(by.toDouble)
    }
    env.datastores.rawDataStore.incrby(videoUsdKey, by.*(BigDecimal(1000000000)).toLong)
  }

  def incrVideoTokens(by: Long)(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    if (env.clusterConfig.mode.isWorker) {
      AiBudgetClusterAgent.videoTokensCounters.getOrElseUpdate(s"$id:$cycleId", new AtomicLong()).addAndGet(by)
    }
    env.datastores.rawDataStore.incrby(videoTokensKey, by)
  }

  def incrEmbeddingUsd(by: BigDecimal)(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    if (env.clusterConfig.mode.isWorker) {
      AiBudgetClusterAgent.embeddingUsdCounters.getOrElseUpdate(s"$id:$cycleId", new DoubleAdder()).add(by.toDouble)
    }
    env.datastores.rawDataStore.incrby(embeddingUsdKey, by.*(BigDecimal(1000000000)).toLong)
  }

  def incrEmbeddingTokens(by: Long)(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    if (env.clusterConfig.mode.isWorker) {
      AiBudgetClusterAgent.embeddingTokensCounters.getOrElseUpdate(s"$id:$cycleId", new AtomicLong()).addAndGet(by)
    }
    env.datastores.rawDataStore.incrby(embeddingTokensKey, by)
  }

  def incrModerationUsd(by: BigDecimal)(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    if (env.clusterConfig.mode.isWorker) {
      AiBudgetClusterAgent.moderationUsdCounters.getOrElseUpdate(s"$id:$cycleId", new DoubleAdder()).add(by.toDouble)
    }
    env.datastores.rawDataStore.incrby(moderationUsdKey, by.*(BigDecimal(1000000000)).toLong)
  }

  def incrModerationTokens(by: Long)(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    if (env.clusterConfig.mode.isWorker) {
      AiBudgetClusterAgent.moderationTokensCounters.getOrElseUpdate(s"$id:$cycleId", new AtomicLong()).addAndGet(by)
    }
    env.datastores.rawDataStore.incrby(moderationTokensKey, by)
  }

  def getTotalUsd()(implicit ec: ExecutionContext, env: Env): Future[BigDecimal] = {
    env.datastores.rawDataStore.get(totalUsdKey).map(_.map { strValue =>
      val lngValue = strValue.utf8String.toLong
      BigDecimal(lngValue)./(BigDecimal(1000000000))
    }.getOrElse(0L))
  }

  def getTotalTokens()(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    env.datastores.rawDataStore.get(totalTokensKey).map(_.map(_.utf8String.toLong).getOrElse(0L))
  }

  def getTotalInferenceUsd()(implicit ec: ExecutionContext, env: Env): Future[BigDecimal] = {
    env.datastores.rawDataStore.get(inferenceUsdKey).map(_.map { strValue =>
      val lngValue = strValue.utf8String.toLong
      BigDecimal(lngValue)./(BigDecimal(1000000000))
    }.getOrElse(0L))
  }

  def getTotalInferenceTokens()(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    env.datastores.rawDataStore.get(inferenceTokensKey).map(_.map(_.utf8String.toLong).getOrElse(0L))
  }

  def getTotalImageUsd()(implicit ec: ExecutionContext, env: Env): Future[BigDecimal] = {
    env.datastores.rawDataStore.get(imageUsdKey).map(_.map { strValue =>
      val lngValue = strValue.utf8String.toLong
      BigDecimal(lngValue)./(BigDecimal(1000000000))
    }.getOrElse(0L))
  }

  def getTotalImageTokens()(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    env.datastores.rawDataStore.get(imageTokensKey).map(_.map(_.utf8String.toLong).getOrElse(0L))
  }

  def getTotalAudioUsd()(implicit ec: ExecutionContext, env: Env): Future[BigDecimal] = {
    env.datastores.rawDataStore.get(audioUsdKey).map(_.map { strValue =>
      val lngValue = strValue.utf8String.toLong
      BigDecimal(lngValue)./(BigDecimal(1000000000))
    }.getOrElse(0L))
  }

  def getTotalAudioTokens()(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    env.datastores.rawDataStore.get(audioTokensKey).map(_.map(_.utf8String.toLong).getOrElse(0L))
  }

  def getTotalVideoUsd()(implicit ec: ExecutionContext, env: Env): Future[BigDecimal] = {
    env.datastores.rawDataStore.get(videoUsdKey).map(_.map { strValue =>
      val lngValue = strValue.utf8String.toLong
      BigDecimal(lngValue)./(BigDecimal(1000000000))
    }.getOrElse(0L))
  }

  def getTotalVideoTokens()(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    env.datastores.rawDataStore.get(videoTokensKey).map(_.map(_.utf8String.toLong).getOrElse(0L))
  }

  def getTotalEmbeddingUsd()(implicit ec: ExecutionContext, env: Env): Future[BigDecimal] = {
    env.datastores.rawDataStore.get(embeddingUsdKey).map(_.map { strValue =>
      val lngValue = strValue.utf8String.toLong
      BigDecimal(lngValue)./(BigDecimal(1000000000))
    }.getOrElse(0L))
  }

  def getTotalEmbeddingTokens()(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    env.datastores.rawDataStore.get(embeddingTokensKey).map(_.map(_.utf8String.toLong).getOrElse(0L))
  }

  def getTotalModerationUsd()(implicit ec: ExecutionContext, env: Env): Future[BigDecimal] = {
    env.datastores.rawDataStore.get(moderationUsdKey).map(_.map { strValue =>
      val lngValue = strValue.utf8String.toLong
      BigDecimal(lngValue)./(BigDecimal(1000000000))
    }.getOrElse(0L))
  }

  def getTotalModerationTokens()(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    env.datastores.rawDataStore.get(moderationTokensKey).map(_.map(_.utf8String.toLong).getOrElse(0L))
  }

  def resetCurrentCycle()(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    env.datastores.rawDataStore.keys(s"${env.storageRoot}:extensions:${AiExtension.id.cleanup}:aibudgets-counter:$id:$cycleId:*").flatMap { keys =>
      env.datastores.rawDataStore.del(keys).map { _ =>
        ()
      }
    }
  }

  def resetAll()(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    env.datastores.rawDataStore.keys(s"${env.storageRoot}:extensions:${AiExtension.id.cleanup}:aibudgets-counter:$id:*").flatMap { keys =>
      env.datastores.rawDataStore.del(keys).map { _ =>
        ()
      }
    }
  }

  def getConsumptions()(implicit ec: ExecutionContext, env: Env): Future[AiBudgetConsumptions] = {
    env.datastores.rawDataStore.mget(Seq(
      totalUsdKey,
      totalTokensKey,
      inferenceUsdKey,
      inferenceTokensKey,
      imageUsdKey,
      imageTokensKey,
      audioUsdKey,
      audioTokensKey,
      videoUsdKey,
      videoTokensKey,
      embeddingUsdKey,
      embeddingTokensKey,
      moderationUsdKey,
      moderationTokensKey
    )).map {
      case list =>
        AiBudgetConsumptions(
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
          moderationTokens = list(13).map(_.utf8String.toLong).getOrElse(0L)
        )
    }
  }

  def matchesRules(ctx: JsValue)(implicit env: Env): Boolean = {
    if (scope.rules.isEmpty) {
      false
    } else {
      scope.rulesMatchMode match {
        case AiBudgetScopeMode.All => scope.rules.forall(_.validate(ctx))
        case AiBudgetScopeMode.Any => scope.rules.exists(_.validate(ctx))
      }
    }
  }

  def matches(ctx: JsValue, apikey: Option[ApiKey], user: Option[PrivateAppsUser], provider: Option[Entity], model: Option[String])(implicit env: Env): Boolean = {
    if (!enabled) {
      false
    } else if (startAt.isAfterNow) {
      false
    } else if (endAt.isBeforeNow) {
      false
    } else if (scope.apikeys.nonEmpty && apikey.nonEmpty && scope.apikeys.exists(id => RegexPool.regex(id).matches(apikey.get.clientId))) {
      true
    } else if (scope.users.nonEmpty && user.nonEmpty && scope.users.exists(id => RegexPool.regex(id).matches(user.get.email))) {
      true
    } else if (scope.groups.nonEmpty && apikey.nonEmpty && scope.groups.exists(id => apikey.get.authorizedEntities.exists(ae => ae.prefix == "group" && RegexPool.regex(id).matches(ae.id)))) {
      true
    } else if (scope.providers.nonEmpty && provider.nonEmpty && scope.providers.exists(id => RegexPool.regex(id).matches(provider.get.theId))) {
      true
    } else if (scope.models.nonEmpty && model.nonEmpty && scope.models.exists(id => RegexPool.regex(id).matches(model.get))) {
      true
    } else {
      matchesRules(ctx)
    }
  }

  def isNotWithinBudget(attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Boolean] = isNotWithinBudgetWithConsumption(attrs).map(r => r._1)

  def isWithinBudget(attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Boolean] = isWithinBudgetWithConsumption(attrs).map(r => r._1)

  def isNotWithinBudgetWithConsumption(attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[(Boolean, Option[AiBudgetConsumptions])] = isWithinBudgetWithConsumption(attrs).map(r => (!r._1, r._2))

  def isWithinBudgetWithConsumption(attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[(Boolean, Option[AiBudgetConsumptions])] = {
    val cycleValue = cycle.getOrElse(-1)
    if (cycleValue >= (renewals + 1)) {
      (false, None).vfuture
    } else if (limits.total_tokens.isEmpty && limits.total_usd.isEmpty && limits.inference_tokens.isEmpty && limits.inference_usd.isEmpty && limits.image_tokens.isEmpty && limits.image_usd.isEmpty && limits.audio_tokens.isEmpty && limits.audio_usd.isEmpty && limits.video_tokens.isEmpty && limits.video_usd.isEmpty && limits.embedding_tokens.isEmpty && limits.embedding_usd.isEmpty) {
      (true, None).vfuture
    } else {
      getConsumptions().map { consumptions =>
        attrs.put(ChatClientWithAuding.BudgetConsumptionKey -> (consumptions, this))
        if (limits.total_usd.isDefined && consumptions.totalUsd >= limits.total_usd.get) {
          (false, consumptions.some)
        } else if (limits.total_tokens.isDefined && consumptions.totalTokens >= limits.total_tokens.get) {
          (false, consumptions.some)
        } else if (limits.inference_usd.isDefined && consumptions.inferenceUsd >= limits.inference_usd.get) {
          (false, consumptions.some)
        } else if (limits.inference_tokens.isDefined && consumptions.inferenceTokens >= limits.inference_tokens.get) {
          (false, consumptions.some)
        } else if (limits.image_usd.isDefined && consumptions.imageUsd >= limits.image_usd.get) {
          (false, consumptions.some)
        } else if (limits.image_tokens.isDefined && consumptions.imageTokens >= limits.image_tokens.get) {
          (false, consumptions.some)
        } else if (limits.audio_usd.isDefined && consumptions.audioUsd >= limits.audio_usd.get) {
          (false, consumptions.some)
        } else if (limits.audio_tokens.isDefined && consumptions.audioTokens >= limits.audio_tokens.get) {
          (false, consumptions.some)
        } else if (limits.video_usd.isDefined && consumptions.videoUsd >= limits.video_usd.get) {
          (false, consumptions.some)
        } else if (limits.video_tokens.isDefined && consumptions.videoTokens >= limits.video_tokens.get) {
          (false, consumptions.some)
        } else if (limits.embedding_usd.isDefined && consumptions.embeddingUsd >= limits.embedding_usd.get) {
          (false, consumptions.some)
        } else if (limits.embedding_tokens.isDefined && consumptions.embeddingTokens >= limits.embedding_tokens.get) {
          (false, consumptions.some)
        } else if (limits.moderation_usd.isDefined && consumptions.moderationUsd >= limits.moderation_usd.get) {
          (false, consumptions.some)
        } else if (limits.moderation_tokens.isDefined && consumptions.moderationTokens >= limits.moderation_tokens.get) {
          (false, consumptions.some)
        } else {
          val percentageConsumedTotalUsd: Double = limits.total_usd.map(tusd => consumptions.totalUsd.toDouble / tusd.toDouble * 100.0).getOrElse(0.0)
          val percentageConsumedTotalTokens: Double = limits.total_tokens.map(ttokens => consumptions.totalTokens.toDouble / ttokens.toDouble * 100.0).getOrElse(0.0)
          val percentageConsumedInferenceUsd: Double = limits.inference_usd.map(tusd => consumptions.inferenceUsd.toDouble / tusd.toDouble * 100.0).getOrElse(0.0)
          val percentageConsumedInferenceTokens: Double = limits.inference_tokens.map(ttokens => consumptions.inferenceTokens.toDouble / ttokens.toDouble * 100.0).getOrElse(0.0)
          val percentageConsumedImageUsd: Double = limits.image_usd.map(tusd => consumptions.imageUsd.toDouble / tusd.toDouble * 100.0).getOrElse(0.0)
          val percentageConsumedImageTokens: Double = limits.image_tokens.map(ttokens => consumptions.imageTokens.toDouble / ttokens.toDouble * 100.0).getOrElse(0.0)
          val percentageConsumedAudioUsd: Double = limits.audio_usd.map(tusd => consumptions.audioUsd.toDouble / tusd.toDouble * 100.0).getOrElse(0.0)
          val percentageConsumedAudioTokens: Double = limits.audio_tokens.map(ttokens => consumptions.audioTokens.toDouble / ttokens.toDouble * 100.0).getOrElse(0.0)
          val percentageConsumedVideoUsd: Double = limits.video_usd.map(tusd => consumptions.videoUsd.toDouble / tusd.toDouble * 100.0).getOrElse(0.0)
          val percentageConsumedVideoTokens: Double = limits.video_tokens.map(ttokens => consumptions.videoTokens.toDouble / ttokens.toDouble * 100.0).getOrElse(0.0)
          val percentageConsumedEmbeddingUsd: Double = limits.embedding_usd.map(tusd => consumptions.embeddingUsd.toDouble / tusd.toDouble * 100.0).getOrElse(0.0)
          val percentageConsumedEmbeddingTokens: Double = limits.embedding_tokens.map(ttokens => consumptions.embeddingTokens.toDouble / ttokens.toDouble * 100.0).getOrElse(0.0)
          val percentageConsumedModerationUsd: Double = limits.moderation_usd.map(tusd => consumptions.moderationUsd.toDouble / tusd.toDouble * 100.0).getOrElse(0.0)
          val percentageConsumedModerationTokens: Double = limits.moderation_tokens.map(ttokens => consumptions.moderationTokens.toDouble / ttokens.toDouble * 100.0).getOrElse(0.0)
          val maxPercentage = actionOnExceed.alertOnAlmostExceedPercentage.toDouble
          if (actionOnExceed.alertOnAlmostExceed && (percentageConsumedTotalUsd > maxPercentage || percentageConsumedTotalTokens > maxPercentage || percentageConsumedInferenceUsd > maxPercentage || percentageConsumedInferenceTokens > maxPercentage || percentageConsumedImageUsd > maxPercentage || percentageConsumedImageTokens > maxPercentage || percentageConsumedAudioUsd > maxPercentage || percentageConsumedAudioTokens > maxPercentage || percentageConsumedVideoUsd > maxPercentage || percentageConsumedVideoTokens > maxPercentage || percentageConsumedEmbeddingUsd > maxPercentage || percentageConsumedEmbeddingTokens > maxPercentage || percentageConsumedModerationUsd > maxPercentage || percentageConsumedModerationTokens > maxPercentage)) {
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
                "moderation_tokens" -> percentageConsumedModerationTokens
              )
            )).toAnalytics()
          }
          (true, consumptions.some)
        }
      }
    }
  }
}

object AiBudget {
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
    )

    override def reads(json: JsValue): JsResult[AiBudget] = Try {
      val startAt = (json \ "start_at").asOpt[String].map(DateTime.parse).getOrElse(DateTime.now())
      val endAt = (json \ "end_at").asOpt[String].map(DateTime.parse).getOrElse(DateTime.now().plusDays(365))
      // val renewals = (json \ "renewals").asOpt[Int]
      val duration = (json \ "duration").as(AiBudgetDuration.format)
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
        limits = (json \ "limits").as(AiBudgetLimits.format),
        scope = (json \ "scope").as(AiBudgetScope.format),
        actionOnExceed = (json \ "action_on_exceed").as(AiBudgetActionOnExceed.format)
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
        tmpl = (v, p, ctx) => {
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

trait AiBudgetsDataStore extends BasicStore[AiBudget] {
  def findMatchingBudgets(ctx: JsValue, apikey: Option[ApiKey], user: Option[PrivateAppsUser], provider: Option[Entity], model: Option[String]): Future[Seq[AiBudget]]
  def updateUsage(totalCost: Option[BigDecimal], totalTokens: Option[Long], usageKind: AiBudgetUsageKind, attrs: TypedMap): Future[Seq[String]]
}

object AiBudgetsDataStore {
  def handleWithinBudget[A](attrs: TypedMap)(notInBudget: => Future[A], inBudget: => Future[A])(implicit env: Env, ec: ExecutionContext): Future[A] = {
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
      ext.datastores.budgetsDataStore.findMatchingBudgets(ctx, apikey, user, provider, model).flatMap { budgets =>
        budgets.filterAsync(_.isNotWithinBudget(attrs)).flatMap { budgets =>
          if (budgets.isEmpty) {
            inBudget
          } else {
            val soft = budgets.forall(_.actionOnExceed.mode == AiBudgetActionOnExceedMode.Soft)
            val budgetIds = budgets.map(_.id)
            if (budgets.exists(_.actionOnExceed.alertOnExceed)) {
              AlertEvent.generic("AiBudgetExceeded", "otoroshi")(Json.obj(
                "budgets" -> budgetIds,
                "apikey" -> apikey.map(_.lightJson).getOrElse(JsNull).as[JsValue],
                "user" -> user.map(_.lightJson).getOrElse(JsNull).as[JsValue],
                "model" -> user.map(_.json).getOrElse(JsNull).as[JsValue],
                "provider" -> user.map(_.json).getOrElse(JsNull).as[JsValue],
                "route" -> route.map(_.json).getOrElse(JsNull).as[JsValue],
              )).toAnalytics()
            }
            if (soft) {
              inBudget
            } else {
              notInBudget
            }
          }
        }
      }
    } else {
      inBudget
    }
  }
}

class KvAiBudgetsDataStore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
  extends AiBudgetsDataStore
    with RedisLikeStore[AiBudget] {

  override def fmt: Format[AiBudget] = AiBudget.format

  override def redisLike(implicit env: Env): RedisLike = redisCli

  override def key(id: String): String = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:aibudgets:$id"

  override def extractId(value: AiBudget): String = value.id

  def updateUsage(cost: Option[BigDecimal], tokens: Option[Long], usageKind: AiBudgetUsageKind, attrs: TypedMap): Future[Seq[String]] = {
    val ext = _env.adminExtensions.extension[AiExtension].get
    if (ext.budgetsEnabled) {
      if (ext.states.hasBudgets && ((cost.isDefined && cost.get.>(BigDecimal(0))) || (tokens.isDefined && tokens.get > 0L))) {
        implicit val ec = _env.analyticsExecutionContext
        implicit val env = _env
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
        findMatchingBudgets(ctx, apikey, user, provider, model).flatMap { budgets =>
          budgets.foreachAsync { budget =>
            budget.isWithinBudget(attrs).map { withinBudget =>
              if (withinBudget) {
                usageKind.updateUsage(budget, cost, tokens, attrs)
              }
            }
          }.map { _ =>
            budgets.map(_.id)
          }
        }
      } else {
        Seq.empty.vfuture
      }
    } else {
      Seq.empty.vfuture
    }
  }

  def findMatchingBudgets(ctx: JsValue, apikey: Option[ApiKey], user: Option[PrivateAppsUser], provider: Option[Entity], model: Option[String]): Future[Seq[AiBudget]] = {
    implicit val env = _env
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
            case budget if budget.scope.alwaysApplyRules => budget.matchesRules(ctx)
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
