package com.cloud.apim.otoroshi.extensions.aigateway.entities

import com.cloud.apim.otoroshi.extensions.aigateway.decorators.ChatClientWithAuding
import org.joda.time.DateTime
import otoroshi.api._
import otoroshi.env.Env
import otoroshi.models._
import otoroshi.next.extensions.AdminExtensionId
import otoroshi.next.utils.JsonHelpers
import otoroshi.security.IdGenerator
import otoroshi.storage._
import otoroshi.utils.syntax.implicits._
import otoroshi.utils.{JsonPathValidator, RegexPool, TypedMap}
import otoroshi_plugins.com.cloud.apim.extensions.aigateway._
import play.api.libs.json._

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

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

case class AiBudgetActionOnExceed(mode: AiBudgetActionOnExceedMode) {
  def json: JsValue = AiBudgetActionOnExceed.format.writes(this)
}
object AiBudgetActionOnExceed {
  val format = new Format[AiBudgetActionOnExceed] {
    override def writes(o: AiBudgetActionOnExceed): JsValue = Json.obj(
      "mode" -> o.mode.json,
    )
    override def reads(json: JsValue): JsResult[AiBudgetActionOnExceed] = Try {
      AiBudgetActionOnExceed(
        mode = AiBudgetActionOnExceedMode.fromString((json \ "mode").asOpt[String].getOrElse("soft")),
      )
    } match {
      case Failure(ex) => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

case class AiBudgetMetrics(
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
)

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
    env.datastores.rawDataStore.incrby(totalUsdKey, by.*(BigDecimal(1000000000)).toLong)
  }

  def incrTotalTokens(by: Long)(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    env.datastores.rawDataStore.incrby(totalTokensKey, by)
  }

  def incrInferenceUsd(by: BigDecimal)(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    env.datastores.rawDataStore.incrby(inferenceUsdKey, by.*(BigDecimal(1000000000)).toLong)
  }

  def incrInferenceTokens(by: Long)(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    env.datastores.rawDataStore.incrby(inferenceTokensKey, by)
  }

  def incrImageUsd(by: BigDecimal)(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    env.datastores.rawDataStore.incrby(imageUsdKey, by.*(BigDecimal(1000000000)).toLong)
  }

  def incrImageTokens(by: Long)(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    env.datastores.rawDataStore.incrby(imageTokensKey, by)
  }

  def incrAudioUsd(by: BigDecimal)(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    env.datastores.rawDataStore.incrby(audioUsdKey, by.*(BigDecimal(1000000000)).toLong)
  }

  def incrAudioTokens(by: Long)(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    env.datastores.rawDataStore.incrby(audioTokensKey, by)
  }

  def incrVideoUsd(by: BigDecimal)(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    env.datastores.rawDataStore.incrby(videoUsdKey, by.*(BigDecimal(1000000000)).toLong)
  }

  def incrVideoTokens(by: Long)(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    env.datastores.rawDataStore.incrby(videoTokensKey, by)
  }

  def incrEmbeddingUsd(by: BigDecimal)(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    env.datastores.rawDataStore.incrby(embeddingUsdKey, by.*(BigDecimal(1000000000)).toLong)
  }

  def incrEmbeddingTokens(by: Long)(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    env.datastores.rawDataStore.incrby(embeddingTokensKey, by)
  }

  def incrModerationUsd(by: BigDecimal)(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    env.datastores.rawDataStore.incrby(moderationUsdKey, by.*(BigDecimal(1000000000)).toLong)
  }

  def incrModerationTokens(by: Long)(implicit ec: ExecutionContext, env: Env): Future[Long] = {
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

  def getAllMetrics()(implicit ec: ExecutionContext, env: Env): Future[AiBudgetMetrics] = {
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
        AiBudgetMetrics(
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
      if (scope.rules.isEmpty) {
        false
      } else {
        scope.rulesMatchMode match {
          case AiBudgetScopeMode.All => scope.rules.forall(_.validate(ctx))
          case AiBudgetScopeMode.Any => scope.rules.exists(_.validate(ctx))
        }
      }
    }
  }

  def isNotWithinBudget()(implicit ec: ExecutionContext, env: Env): Future[Boolean] = isWithinBudget().map(r => !r)

  def isWithinBudget()(implicit ec: ExecutionContext, env: Env): Future[Boolean] = {
    val cycleValue = cycle.getOrElse(-1)
    if (cycleValue >= (renewals + 1)) {
      false.vfuture
    } else if (limits.total_tokens.isEmpty && limits.total_usd.isEmpty && limits.inference_tokens.isEmpty && limits.inference_usd.isEmpty && limits.image_tokens.isEmpty && limits.image_usd.isEmpty && limits.audio_tokens.isEmpty && limits.audio_usd.isEmpty && limits.video_tokens.isEmpty && limits.video_usd.isEmpty && limits.embedding_tokens.isEmpty && limits.embedding_usd.isEmpty) {
      true.vfuture
    } else {
      getAllMetrics().map { metrics =>
        if (limits.total_usd.isDefined && metrics.totalUsd >= limits.total_usd.get) {
          false
        } else if (limits.total_tokens.isDefined && metrics.totalTokens >= limits.total_tokens.get) {
          false
        } else if (limits.inference_usd.isDefined && metrics.inferenceUsd >= limits.inference_usd.get) {
          false
        } else if (limits.inference_tokens.isDefined && metrics.inferenceTokens >= limits.inference_tokens.get) {
          false
        } else if (limits.image_usd.isDefined && metrics.imageUsd >= limits.image_usd.get) {
          false
        } else if (limits.image_tokens.isDefined && metrics.imageTokens >= limits.image_tokens.get) {
          false
        } else if (limits.audio_usd.isDefined && metrics.audioUsd >= limits.audio_usd.get) {
          false
        } else if (limits.audio_tokens.isDefined && metrics.audioTokens >= limits.audio_tokens.get) {
          false
        } else if (limits.video_usd.isDefined && metrics.videoUsd >= limits.video_usd.get) {
          false
        } else if (limits.video_tokens.isDefined && metrics.videoTokens >= limits.video_tokens.get) {
          false
        } else if (limits.embedding_usd.isDefined && metrics.embeddingUsd >= limits.embedding_usd.get) {
          false
        } else if (limits.embedding_tokens.isDefined && metrics.embeddingTokens >= limits.embedding_tokens.get) {
          false
        } else if (limits.moderation_usd.isDefined && metrics.moderationUsd >= limits.moderation_usd.get) {
          false
        } else if (limits.moderation_tokens.isDefined && metrics.moderationTokens >= limits.moderation_tokens.get) {
          false
        } else {
          true
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
            scope = AiBudgetScope(true, true, true, true, true, Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq.empty, Seq.empty, AiBudgetScopeMode.All),
            actionOnExceed = AiBudgetActionOnExceed(AiBudgetActionOnExceedMode.Block)
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
    val apikey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey)
    val user = attrs.get(otoroshi.plugins.Keys.UserKey)
    val snowflake = attrs.get(otoroshi.plugins.Keys.SnowFlakeKey)
    val request = attrs.get(otoroshi.plugins.Keys.RequestKey).map(r => JsonHelpers.requestToJson(r, attrs))
    val route = attrs.get(otoroshi.next.plugins.Keys.RouteKey)
    val provider = attrs.get(ChatClientWithAuding.ProviderKey)
    val model = attrs.get(ChatClientWithAuding.ModelKey)
    val ctx = Json.obj(
      "foo" -> "bar",
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
      budgets.filterAsync(_.isNotWithinBudget()).flatMap { budgets =>
        if (budgets.isEmpty) {
          inBudget
        } else {
          notInBudget
        }
      }
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
        "foo" -> "bar",
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
          budget.isWithinBudget().map { withinBudget =>
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
  }

  def findMatchingBudgets(ctx: JsValue, apikey: Option[ApiKey], user: Option[PrivateAppsUser], provider: Option[Entity], model: Option[String]): Future[Seq[AiBudget]] = {
    implicit val env = _env
    val ext = _env.adminExtensions.extension[AiExtension].get
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
          case budget if budget.scope.extractFromProviderMeta && providerRef.contains(budget.id) => true
          case budget if budget.scope.extractFromApikeyMeta && apikeyRef.contains(budget.id) => true
          case budget if budget.scope.extractFromUserMeta && userRef.contains(budget.id) => true
          case budget if budget.scope.extractFromUserAuthModuleMeta && authModuleRef.contains(budget.id) => true
          case budget if budget.scope.extractFromApikeyGroupMeta && groupsRef.contains(budget.id) => true
          case budget if budget.matches(ctx, apikey, user, provider, model) => true
          case _ => false
        }
        .vfuture
    } else {
      Seq.empty[AiBudget].vfuture
    }
  }
}
