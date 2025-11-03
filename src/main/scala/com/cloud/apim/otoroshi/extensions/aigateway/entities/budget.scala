package com.cloud.apim.otoroshi.extensions.aigateway.entities

import org.joda.time.DateTime
import otoroshi.api._
import otoroshi.env.Env
import otoroshi.models._
import otoroshi.next.extensions.AdminExtensionId
import otoroshi.next.utils.JsonHelpers
import otoroshi.security.IdGenerator
import otoroshi.storage._
import otoroshi.utils.syntax.implicits._
import otoroshi.utils.{JsonPathValidator, TypedMap}
import otoroshi_plugins.com.cloud.apim.extensions.aigateway._
import play.api.libs.json._

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

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

case class AiBudgetLimits(tokens: Option[Long], usd: Option[BigDecimal]) {
  def json: JsValue = AiBudgetLimits.format.writes(this)
}

object AiBudgetLimits {
  val format = new Format[AiBudgetLimits] {
    override def writes(o: AiBudgetLimits): JsValue = Json.obj(
      "tokens" -> o.tokens,
      "usd" -> o.usd
    )
    override def reads(json: JsValue): JsResult[AiBudgetLimits] = Try {
      AiBudgetLimits(
        tokens = (json \ "tokens").asOpt[Long],
        usd = (json \ "usd").asOpt[BigDecimal]
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
      "rules" -> o.rules.map(JsonPathValidator.format.writes),
      "rules_match_mode" -> o.rulesMatchMode.json
    )
    override def reads(json: JsValue): JsResult[AiBudgetScope] = Try {
      AiBudgetScope(
        extractFromApikeyMeta = (json \ "extract_from_apikey_meta").asOpt[Boolean].getOrElse(false),
        extractFromApikeyGroupMeta = (json \ "extract_from_apikey_group_meta").asOpt[Boolean].getOrElse(false),
        extractFromUserMeta = (json \ "extract_from_user_meta").asOpt[Boolean].getOrElse(false),
        extractFromUserAuthModuleMeta = (json \ "extract_from_user_auth_module_meta").asOpt[Boolean].getOrElse(false),
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

case class AiBudget(
                       location: EntityLocation,
                       id: String,
                       name: String,
                       description: String,
                       tags: Seq[String],
                       metadata: Map[String, String],
                       /////////////////////////////////////////////////////////////////////////////////////////////////
                       enabled: Boolean,
                       startAt: Option[DateTime],
                       endAt: Option[DateTime],
                       duration: AiBudgetDuration,
                       renewals: Option[Int],
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

  def slugName: String = metadata.getOrElse("endpoint_name", name).slugifyWithSlash.replaceAll("-+", "_")

  def cycle: Option[Int] = {
    val now = Instant.now()
    val dur = duration.toDuration
    if (startAt.isEmpty) {
      None
    } else {
      val k = java.time.Duration.between(startAt.get.toGregorianCalendar.toZonedDateTime, now).dividedBy(dur).toInt
      val rn = renewals.getOrElse(0)
      if (rn >= 0 && k > rn) None else Some(k)
    }
  }

  def cycleId: String = cycle.map(_.toString).getOrElse("single")

  def usdKey(implicit env: Env): String = s"${env.storageRoot}:extensions:${AiExtension.id.cleanup}:aibudgets-counter:$id:$cycleId:usd"

  def tokensKey(implicit env: Env): String = s"${env.storageRoot}:extensions:${AiExtension.id.cleanup}:aibudgets-counter:$id:$cycleId:tokens"

  def incrUsd(by: BigDecimal)(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    env.datastores.rawDataStore.incrby(usdKey, by.*(BigDecimal(1000000000)).toLong)
  }

  def incrTokens(by: Long)(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    env.datastores.rawDataStore.incrby(tokensKey, by)
  }

  def getUsd()(implicit ec: ExecutionContext, env: Env): Future[BigDecimal] = {
    env.datastores.rawDataStore.get(usdKey).map(_.map { strValue =>
      val lngValue = strValue.utf8String.toLong
      BigDecimal(lngValue)./(BigDecimal(1000000000))
    }.getOrElse(0L))
  }

  def getTokens()(implicit ec: ExecutionContext, env: Env): Future[Long] = {
    env.datastores.rawDataStore.get(usdKey).map(_.map(_.utf8String.toLong).getOrElse(0L))
  }

  def matches(ctx: JsValue)(implicit env: Env): Boolean = {
    if (!enabled) {
      false
    } else if (startAt.isDefined && startAt.get.isAfterNow) {
      false
    } else if (endAt.isDefined && endAt.get.isBeforeNow) {
      false
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
    if (renewals.isDefined && renewals.get > cycleValue) {
      false.vfuture
    } else if (limits.tokens.isEmpty && limits.usd.isEmpty) {
      true.vfuture
    } else {
      getUsd().flatMap { usd =>
        getTokens().flatMap { tokens =>
          if (limits.usd.isDefined && usd >= limits.usd.get) {
            false.vfuture
          } else if (limits.tokens.isDefined && tokens >= limits.tokens.get) {
            false.vfuture
          } else {
            true.vfuture
          }
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
      "start_at" -> o.startAt.map(_.getMillis),
      "end_at" -> o.endAt.map(_.getMillis),
      "duration" -> o.duration.json,
      "renewals" -> o.renewals.map(_.json).getOrElse(JsNull).asValue,
      "limits" -> o.limits.json,
      "scope" -> o.scope.json,
      "action_on_exceed" -> o.actionOnExceed.json
    )

    override def reads(json: JsValue): JsResult[AiBudget] = Try {
      AiBudget(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = (json \ "id").as[String],
        name = (json \ "name").as[String],
        description = (json \ "description").as[String],
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        enabled = (json \ "enabled").asOpt[Boolean].getOrElse(false),
        startAt = (json \ "start_at").asOpt[String].map(DateTime.parse),
        endAt = (json \ "end_at").asOpt[String].map(DateTime.parse),
        duration = (json \ "duration").as(AiBudgetDuration.format),
        renewals = (json \ "renewals").asOpt[Int],
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
            startAt = None,
            endAt = None,
            duration = AiBudgetDuration(30, AiBudgetDurationUnit.Day),
            renewals = None,
            limits = AiBudgetLimits(10000000L.some, BigDecimal(200.0).some),
            scope = AiBudgetScope(true, true, true, true, Seq.empty, AiBudgetScopeMode.All),
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
  def findMatchingBudgets(ctx: JsValue, apikey: Option[ApiKey], user: Option[PrivateAppsUser]): Future[Seq[AiBudget]]
  def updateUsage(totalCost: Option[BigDecimal], totalTokens: Option[Long], attrs: TypedMap): Unit
}

class KvAiBudgetsDataStore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
  extends AiBudgetsDataStore
    with RedisLikeStore[AiBudget] {

  override def fmt: Format[AiBudget] = AiBudget.format

  override def redisLike(implicit env: Env): RedisLike = redisCli

  override def key(id: String): String = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:aibudgets:$id"

  override def extractId(value: AiBudget): String = value.id

  def updateUsage(totalCost: Option[BigDecimal], totalTokens: Option[Long], attrs: TypedMap): Unit = {
    println("llm - updateUsage")
    try {
      val ext = _env.adminExtensions.extension[AiExtension].get
      if (ext.states.hasBudgets && ((totalCost.isDefined && totalCost.get.>(BigDecimal(0))) || (totalTokens.isDefined && totalTokens.get > 0L))) {
        implicit val ec = _env.analyticsExecutionContext
        implicit val env = _env
        val apikey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey)
        val user = attrs.get(otoroshi.plugins.Keys.UserKey)
        val snowflake = attrs.get(otoroshi.plugins.Keys.SnowFlakeKey)
        val request = attrs.get(otoroshi.plugins.Keys.RequestKey).map(r => JsonHelpers.requestToJson(r, attrs))
        val ctx = Json.obj(
          "foo" -> "bar",
          "snowflake" -> snowflake,
          "apikey" -> apikey.map(_.lightJson).getOrElse(JsNull).as[JsValue],
          "user" -> user.map(_.lightJson).getOrElse(JsNull).as[JsValue],
          // "route" -> route.json,
          "request" -> request,
          "config" -> Json.obj(),
          "global_config" -> Json.obj(),
          //"attrs" -> attrs.json
        )
        findMatchingBudgets(ctx, apikey, user).map { budgets =>
          budgets.foreachAsync { budget =>
            budget.isWithinBudget().map { withinBudget =>
              if (withinBudget) {
                totalCost.foreach(c => budget.incrUsd(c))
                totalTokens.foreach(c => budget.incrTokens(c))
              }
            }
          }
        }
      }
    } catch {
      case err: Throwable => err.printStackTrace()
    }
  }

  def findMatchingBudgets(ctx: JsValue, apikey: Option[ApiKey], user: Option[PrivateAppsUser]): Future[Seq[AiBudget]] = {
    implicit val env = _env
    val ext = _env.adminExtensions.extension[AiExtension].get
    if (ext.states.hasBudgets) {
      val authModule = user.map(_.authConfigId).flatMap(id => _env.proxyState.authModule(id))
      val groups = apikey.toSeq.flatMap(_.authorizedEntities.collect {
        case ServiceGroupIdentifier(id) => id
      }).flatMap(id => _env.proxyState.serviceGroup(id))
      val apikeyRef = apikey.flatMap(_.metadata.get("ai_budget_ref"))
      val userRef = user.flatMap(_.metadata.get("ai_budget_ref"))
      val authModuleRef = authModule.flatMap(_.metadata.get("ai_budget_ref"))
      val groupsRef = groups.flatMap(_.metadata.get("ai_budget_ref"))
      ext.states
        .allBudgets()
        .filter(_.enabled)
        .filter {
          case budget if apikeyRef.contains(budget.id) => true
          case budget if userRef.contains(budget.id) => true
          case budget if authModuleRef.contains(budget.id) => true
          case budget if groupsRef.contains(budget.id) => true
          case budget if budget.matches(ctx) => true
          case _ => false
        }
        .vfuture
    } else {
      Seq.empty[AiBudget].vfuture
    }
  }
}
