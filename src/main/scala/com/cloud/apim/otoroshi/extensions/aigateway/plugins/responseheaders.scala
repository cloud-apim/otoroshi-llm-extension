package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import akka.stream.Materializer
import com.cloud.apim.otoroshi.extensions.aigateway.ChatClient
import com.cloud.apim.otoroshi.extensions.aigateway.decorators.ChatClientWithCostsTracking
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.Result

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class AiLlmResponseHeadersConfig(prefix: String = "x-otoroshi-llm-", includeCosts: Boolean = true) extends NgPluginConfig {
  def json: JsValue = AiLlmResponseHeadersConfig.format.writes(this)
}

object AiLlmResponseHeadersConfig {
  val default = AiLlmResponseHeadersConfig()
  val format = new Format[AiLlmResponseHeadersConfig] {
    override def reads(json: JsValue): JsResult[AiLlmResponseHeadersConfig] = Try {
      AiLlmResponseHeadersConfig(
        prefix = json.select("prefix").asOpt[String].filterNot(_.isBlank).getOrElse("x-otoroshi-llm-"),
        includeCosts = json.select("include_costs").asOpt[Boolean].getOrElse(true),
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }
    override def writes(o: AiLlmResponseHeadersConfig): JsValue = Json.obj(
      "prefix" -> o.prefix,
      "include_costs" -> o.includeCosts,
    )
  }
  val configFlow: Seq[String] = Seq("prefix", "include_costs")
  val configSchema: Option[JsObject] = Some(Json.obj(
    "prefix" -> Json.obj(
      "type" -> "string",
      "label" -> "Headers prefix"
    ),
    "include_costs" -> Json.obj(
      "type" -> "bool",
      "label" -> "Include costs headers"
    ),
  ))
}

// Adds x-otoroshi-llm-* response headers exposing the LLM call metadata (model, provider, token
// usage, latency and cost) that the gateway already computes. Handy for client-side observability
// (curl, dashboards, logs) without parsing the response body. Inspired by litellm's x-litellm-* headers.
//
// Note: for streaming responses, usage/cost are only known once the stream ends — after the response
// headers have been sent — so those headers are populated for non-streaming responses only.
class AiLlmResponseHeaders extends NgRequestTransformer {

  override def name: String = "Cloud APIM - LLM response headers"
  override def description: Option[String] = "Exposes LLM model, token usage, latency and cost as x-otoroshi-llm-* response headers".some
  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))

  override def steps: Seq[NgStep] = Seq(NgStep.TransformResponse)
  override def defaultConfigObject: Option[NgPluginConfig] = Some(AiLlmResponseHeadersConfig.default)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = AiLlmResponseHeadersConfig.configFlow
  override def configSchema: Option[JsObject] = AiLlmResponseHeadersConfig.configSchema

  override def transformsError: Boolean = false
  override def transformsResponse: Boolean = true
  override def transformsRequest: Boolean = false

  override def transformResponse(ctx: NgTransformerResponseContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val config = ctx.cachedConfig(internalName)(AiLlmResponseHeadersConfig.format).getOrElse(AiLlmResponseHeadersConfig.default)
    val prefix = config.prefix

    val aiData = ctx.attrs.get(otoroshi.plugins.Keys.ExtraAnalyticsDataKey)
      .flatMap(_.select("ai").asOpt[Seq[JsObject]])
      .getOrElse(Seq.empty)
    val lastAi = aiData.lastOption
    val usage = ctx.attrs.get(ChatClient.ApiUsageKey).map(_.usage)
    val costs = ctx.attrs.get(ChatClientWithCostsTracking.key)
    val durationMs = if (aiData.nonEmpty) Some(aiData.flatMap(_.select("duration").asOpt[Long]).sum) else None

    val headers: Map[String, String] = Seq(
      lastAi.flatMap(_.select("model").asOptString).map(v => s"${prefix}model" -> v),
      lastAi.flatMap(_.select("provider").asOptString).map(v => s"${prefix}provider" -> v),
      lastAi.flatMap(_.select("provider_kind").asOptString).map(v => s"${prefix}provider-kind" -> v),
      usage.map(u => s"${prefix}prompt-tokens" -> u.promptTokens.toString),
      usage.map(u => s"${prefix}generation-tokens" -> u.generationTokens.toString),
      usage.map(u => s"${prefix}reasoning-tokens" -> u.reasoningTokens.toString),
      usage.map(u => s"${prefix}total-tokens" -> u.totalTokens.toString),
      durationMs.map(d => s"${prefix}duration-ms" -> d.toString),
      costs.filter(_ => config.includeCosts).map(c => s"${prefix}cost" -> c.totalCost.bigDecimal.toPlainString),
      costs.filter(_ => config.includeCosts).map(c => s"${prefix}input-cost" -> c.inputCost.bigDecimal.toPlainString),
      costs.filter(_ => config.includeCosts).map(c => s"${prefix}output-cost" -> c.outputCost.bigDecimal.toPlainString),
      costs.filter(_ => config.includeCosts).map(c => s"${prefix}reasoning-cost" -> c.reasoningCost.bigDecimal.toPlainString),
    ).flatten.toMap

    if (headers.isEmpty) {
      ctx.otoroshiResponse.right.vfuture
    } else {
      ctx.otoroshiResponse.copy(headers = ctx.otoroshiResponse.headers ++ headers).right.vfuture
    }
  }
}
