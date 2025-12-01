package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import akka.stream.Materializer
import com.cloud.apim.otoroshi.extensions.aigateway.ChatClient
import otoroshi.el.GlobalExpressionLanguage
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.next.plugins.NgCustomThrottling
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json._
import play.api.mvc.{Result, Results}

import scala.concurrent.{ExecutionContext, Future}
import scala.util._

case class LlmTokensRateLimitingValidatorConfig(windowMillis: String, groupExpr: String, _throttlingQuota: String) extends NgPluginConfig {
  def json: JsValue = LlmTokensRateLimitingValidatorConfig.format.writes(this)
  def throttlingQuota(ctx: NgAccessContext, env: Env): Long = {
    _throttlingQuota.trim match {
      case expr if expr.contains("${") && expr.contains("}") => {
        GlobalExpressionLanguage.apply(
          value = expr,
          req = ctx.request.some,
          service = ctx.route.legacy.some,
          route = ctx.route.some,
          apiKey = ctx.apikey,
          user = ctx.user,
          context = Map.empty,
          attrs = ctx.attrs,
          env = env
        ).toLong
      }
      case value => value.trim.toLong
    }
  }
}

object LlmTokensRateLimitingValidatorConfig {
  val LlmTokensRateLimitingValidatorKey = play.api.libs.typedmap.TypedKey[Map[String, String]]("LlmTokensRateLimitingValidatorKey")
  val format = new Format[LlmTokensRateLimitingValidatorConfig] {
    override def reads(json: JsValue): JsResult[LlmTokensRateLimitingValidatorConfig] = Try {
      LlmTokensRateLimitingValidatorConfig(
        windowMillis = json.select("window_millis").asOpt[String].filterNot(_.isBlank).getOrElse(LlmTokensRateLimitingValidatorConfig.default.windowMillis),
        _throttlingQuota = json.select("throttling_quota").asOpt[String].filterNot(_.isBlank).getOrElse(LlmTokensRateLimitingValidatorConfig.default._throttlingQuota),
        groupExpr = json.select("group_expr").asOpt[String].filterNot(_.isBlank).getOrElse(LlmTokensRateLimitingValidatorConfig.default.groupExpr)
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(value) => JsSuccess(value)
    }
    override def writes(o: LlmTokensRateLimitingValidatorConfig): JsValue = Json.obj(
      "window_millis" -> o.windowMillis,
      "throttling_quota" -> o._throttlingQuota,
      "group_expr" -> o.groupExpr,
    )
  }
  val default = LlmTokensRateLimitingValidatorConfig("10000", "${route.id}", "1000")
  val configFlow = Seq(
    "window_millis",
    "throttling_quota",
    "group_expr",
  )
  val configSchema = Some(Json.obj(
    "window_millis" -> Json.obj(
      "type" -> "string",
      "suffix" -> "millis.",
      "label" -> "Time window"
    ),
    "throttling_quota" -> Json.obj(
      "type" -> "string",
      "suffix" -> "tokens",
      "label" -> "Max consumption"
    ),
    "group_expr" -> Json.obj(
      "type" -> "string",
      "label" -> "Group by"
    ),
  ))
}

class LlmTokensRateLimitingValidator extends NgAccessValidator with NgRequestTransformer {

  override def steps: Seq[NgStep]                = Seq(NgStep.ValidateAccess, NgStep.TransformResponse)
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"), NgPluginCategory.AccessControl)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = LlmTokensRateLimitingValidatorConfig.configFlow
  override def configSchema: Option[JsObject] = LlmTokensRateLimitingValidatorConfig.configSchema
  override def name: String = "Cloud APIM - LLM Tokens rate limiting"
  override def description: Option[String] = """This plugin limits the number of LLM used on a period of time.""".stripMargin.some
  override def defaultConfigObject: Option[NgPluginConfig] = LlmTokensRateLimitingValidatorConfig.default.some

  override def transformsError: Boolean = true
  override def transformsRequest: Boolean = true
  override def transformsResponse: Boolean = true

  private val defaultExpr = "LlmTokensRateLimitingValidator-usage"

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'LLM Tokens rate limiting' plugin is available !")
    }
    ().vfuture
  }

  private def throttlingKey(name: String, group: String, ctx: NgAccessContext)(implicit env: Env): String = {
    NgCustomThrottling.throttlingKey(computeExpr(name, ctx, env), computeExpr(group, ctx, env))
  }

  private def computeExpr(expr: String, ctx: NgAccessContext, env: Env): String = {
    GlobalExpressionLanguage.apply(
      value = expr,
      req = ctx.request.some,
      service = ctx.route.legacy.some,
      route = ctx.route.some,
      apiKey = ctx.apikey,
      user = ctx.user,
      context = Map.empty,
      attrs = ctx.attrs,
      env = env
    )
  }

  private def computeExprAfter(expr: String, ctx: NgAfterRequestContext, env: Env): String = {
    GlobalExpressionLanguage.apply(
      value = expr,
      req = ctx.request.some,
      service = ctx.route.legacy.some,
      route = ctx.route.some,
      user = ctx.attrs.get(otoroshi.plugins.Keys.UserKey),
      apiKey = ctx.attrs.get(otoroshi.plugins.Keys.ApiKeyKey),
      context = Map.empty,
      attrs = ctx.attrs,
      env = env
    )
  }

  private def computeExprAfter(expr: String, ctx: NgTransformerResponseContext, env: Env): String = {
    GlobalExpressionLanguage.apply(
      value = expr,
      req = ctx.request.some,
      service = ctx.route.legacy.some,
      route = ctx.route.some,
      user = ctx.attrs.get(otoroshi.plugins.Keys.UserKey),
      apiKey = ctx.attrs.get(otoroshi.plugins.Keys.ApiKeyKey),
      context = Map.empty,
      attrs = ctx.attrs,
      env = env
    )
  }

  private def computeExprAfter(expr: String, ctx: NgTransformerErrorContext, env: Env): String = {
    GlobalExpressionLanguage.apply(
      value = expr,
      req = ctx.request.some,
      service = ctx.route.legacy.some,
      route = ctx.route.some,
      user = ctx.attrs.get(otoroshi.plugins.Keys.UserKey),
      apiKey = ctx.attrs.get(otoroshi.plugins.Keys.ApiKeyKey),
      context = Map.empty,
      attrs = ctx.attrs,
      env = env
    )
  }

  private def updateQuotas(ctx: NgTransformerResponseContext, qconf: LlmTokensRateLimitingValidatorConfig)(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    val group = computeExprAfter(qconf.groupExpr, ctx, env)
    val expr  = computeExprAfter(defaultExpr, ctx, env)
    val windowMillis = computeExprAfter(qconf.windowMillis, ctx, env).trim.toLong
    ctx.attrs.get(ChatClient.ApiUsageKey).map { usage =>
      val increment = usage.usage.promptTokens + usage.usage.generationTokens + usage.usage.reasoningTokens
      //println(s"incrementing '${env.storageRoot}:plugins:custom-throttling:${group}:second:$expr' of ${increment} in ${windowMillis} ms")
      ctx.attrs.update(LlmTokensRateLimitingValidatorConfig.LlmTokensRateLimitingValidatorKey) { map =>
        val max = map.flatMap(_.get("X-Llm-Ratelimit-Max-Tokens")).getOrElse("0").toLong
        val past_consumed = map.flatMap(_.get("X-Llm-Ratelimit-Consumed-Tokens")).getOrElse("0").toLong
        val consumed = (past_consumed + increment)
        val remaining = max - consumed
        val remaining_max =  Math.max(remaining, 0).toString
        val consumed_str = consumed.toString
        map.getOrElse(Map.empty) ++ Map(
          "X-Llm-Ratelimit-Remaining-Tokens" -> remaining_max,
          "X-Llm-Ratelimit-Consumed-Tokens" -> consumed_str,
        )

      }
      env.clusterAgent.incrementCustomThrottling(expr, group, increment, windowMillis)
      val max = ctx.attrs.get(LlmTokensRateLimitingValidatorConfig.LlmTokensRateLimitingValidatorKey).flatMap(_.get("X-Llm-Ratelimit-Max-Tokens")).getOrElse("0").toLong
      NgCustomThrottling.updateQuotas(expr, group, increment, max, windowMillis).map(_ => ())
    }.getOrElse(().vfuture)
  }

  private def updateQuotas(ctx: NgTransformerErrorContext, qconf: LlmTokensRateLimitingValidatorConfig)(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    val group = computeExprAfter(qconf.groupExpr, ctx, env)
    val expr  = computeExprAfter(defaultExpr, ctx, env)
    val windowMillis = computeExprAfter(qconf.windowMillis, ctx, env).trim.toLong
    ctx.attrs.get(ChatClient.ApiUsageKey).map { usage =>
      val increment = usage.usage.promptTokens + usage.usage.generationTokens + usage.usage.reasoningTokens
      //println(s"incrementing '${env.storageRoot}:plugins:custom-throttling:${group}:second:$expr' of ${increment} in ${windowMillis} ms")
      ctx.attrs.update(LlmTokensRateLimitingValidatorConfig.LlmTokensRateLimitingValidatorKey) { map =>
        val max = map.flatMap(_.get("X-Llm-Ratelimit-Max-Tokens")).getOrElse("0").toLong
        val past_consumed = map.flatMap(_.get("X-Llm-Ratelimit-Consumed-Tokens")).getOrElse("0").toLong
        val consumed = (past_consumed + increment)
        val remaining = max - consumed
        val remaining_max =  Math.max(remaining, 0).toString
        val consumed_str = consumed.toString
        map.getOrElse(Map.empty) ++ Map(
          "X-Llm-Ratelimit-Remaining-Tokens" -> remaining_max,
          "X-Llm-Ratelimit-Consumed-Tokens" -> consumed_str,
        )
      }
      env.clusterAgent.incrementCustomThrottling(expr, group, increment, windowMillis)
      val max = ctx.attrs.get(LlmTokensRateLimitingValidatorConfig.LlmTokensRateLimitingValidatorKey).flatMap(_.get("X-Llm-Ratelimit-Max-Tokens")).getOrElse("0").toLong
      NgCustomThrottling.updateQuotas(expr, group, increment, max, windowMillis).map(_ => ())
    }.getOrElse(().vfuture)
  }

  private def withingQuotas(
                             ctx: NgAccessContext,
                             qconf: LlmTokensRateLimitingValidatorConfig
                           )(implicit ec: ExecutionContext, env: Env): Future[Boolean] = {
    val value = qconf.throttlingQuota(ctx, env)
    val key = throttlingKey(computeExpr(defaultExpr, ctx, env), computeExpr(qconf.groupExpr, ctx, env), ctx)
    // println(s"checking '${key}' under ${value}")
    env.datastores.rawDataStore
      .get(key)
      .map { opt =>
        val current = opt.map(_.utf8String.toLong).getOrElse(0L)
        ctx.attrs.update(LlmTokensRateLimitingValidatorConfig.LlmTokensRateLimitingValidatorKey) { map =>
          val max = value.toString
          val remaining = value - current
          val remaining_max = Math.max(remaining, 0).toString
          val consumed = current
          val consumed_str = consumed.toString
          map.getOrElse(Map.empty) ++ Map(
            "X-Llm-Ratelimit-Max-Tokens" -> max,
            "X-Llm-Ratelimit-Remaining-Tokens" -> remaining_max,
            "X-Llm-Ratelimit-Consumed-Tokens" -> consumed_str,
          )
        }
        current <= value
      }
  }

  private def tooMuchTokens(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    Errors
      .craftResponseResult(
        "too many tokens used",
        Results.TooManyRequests,
        ctx.request,
        None,
        None,
        duration = ctx.report.getDurationNow(),
        overhead = ctx.report.getOverheadInNow(),
        attrs = ctx.attrs,
        maybeRoute = ctx.route.some
      )
      .map(r => NgAccess.NgDenied(r))
  }

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config = ctx.cachedConfig(internalName)(LlmTokensRateLimitingValidatorConfig.format).getOrElse(LlmTokensRateLimitingValidatorConfig.default)
    val windowMillis = computeExpr(config.windowMillis, ctx, env).trim
    ctx.attrs.put(LlmTokensRateLimitingValidatorConfig.LlmTokensRateLimitingValidatorKey -> Map(
      "X-Llm-Ratelimit-Window-Millis" -> windowMillis
    ))
    withingQuotas(ctx, config) flatMap {
      case true => NgAccess.NgAllowed.vfuture
      case false => tooMuchTokens(ctx)
    }
  }

  override def transformResponse(ctx: NgTransformerResponseContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val config = ctx.cachedConfig(internalName)(LlmTokensRateLimitingValidatorConfig.format).getOrElse(LlmTokensRateLimitingValidatorConfig.default)
    updateQuotas(ctx, config).map { _ =>
      val headers = ctx.attrs.get(LlmTokensRateLimitingValidatorConfig.LlmTokensRateLimitingValidatorKey).getOrElse(Map.empty[String, String])
      ctx.otoroshiResponse.copy(
        headers = ctx.otoroshiResponse.headers ++ headers,
      ).right
    }
  }

  override def transformError(ctx: NgTransformerErrorContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[NgPluginHttpResponse] = {
    val config = ctx.cachedConfig(internalName)(LlmTokensRateLimitingValidatorConfig.format).getOrElse(LlmTokensRateLimitingValidatorConfig.default)
    updateQuotas(ctx, config).map { _ =>
      val headers = ctx.attrs.get(LlmTokensRateLimitingValidatorConfig.LlmTokensRateLimitingValidatorKey).getOrElse(Map.empty[String, String])
      ctx.otoroshiResponse.copy(
        headers = ctx.otoroshiResponse.headers ++ headers,
      )
    }
  }

  override def afterRequest(ctx: NgAfterRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    // val config = ctx.cachedConfig(internalName)(LlmTokensRateLimitingValidatorConfig.format).getOrElse(LlmTokensRateLimitingValidatorConfig.default)
    // updateQuotas(ctx, config)
    ().vfuture
  }
}