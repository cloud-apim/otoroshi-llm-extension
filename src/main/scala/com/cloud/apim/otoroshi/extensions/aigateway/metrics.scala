package com.cloud.apim.otoroshi.extensions.aigateway

import otoroshi.env.Env
import play.api.libs.json.JsValue

import java.util.concurrent.TimeUnit
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

// Real-time metrics for the whole AI gateway, exposed through Otoroshi's metrics endpoint (prometheus / json
// export). Always on when env metrics are enabled, independent of the audit-event opt-ins. Flat metric names
// (Otoroshi's metric API carries no tags), the operation encoded in the name. Mirrors the MCP metrics
// (McpAuditHelper.markMetrics / McpClientAudit.markMetrics) but under the `ai.*` namespace.
//
// `op` is the operation segment, conventionally the `consumed_using` string with `/` replaced by `.`
// (e.g. "chat.completion.blocking", "embedding_model.embedding", "audio_model.stt", "ocr.extract").
object AiMetrics {

  // calls/errors/duration for a single AI operation, plus aggregate and per-provider-kind breakdown.
  def markOperation(op: String, providerKind: Option[String], durationMs: Long, isError: Boolean)(implicit env: Env): Unit = {
    if (!env.metricsEnabled) return
    val kind = providerKind.map(_.trim.toLowerCase).filter(_.nonEmpty)
    env.metrics.counterInc("ai.calls")
    env.metrics.counterInc(s"ai.$op.calls")
    kind.foreach(k => env.metrics.counterInc(s"ai.provider.$k.calls"))
    if (isError) {
      env.metrics.counterInc("ai.errors")
      env.metrics.counterInc(s"ai.$op.errors")
      kind.foreach(k => env.metrics.counterInc(s"ai.provider.$k.errors"))
    }
    env.metrics.timerUpdate(s"ai.$op.duration", durationMs, TimeUnit.MILLISECONDS)
  }

  // cumulative token counters (counterIncOf increments by amount) — gives token-rate / totals in dashboards.
  def markTokens(promptT: Int, genT: Int, reasoningT: Int)(implicit env: Env): Unit = {
    if (!env.metricsEnabled) return
    if (promptT > 0) env.metrics.counterIncOf("ai.tokens.prompt", promptT.toLong)
    if (genT > 0) env.metrics.counterIncOf("ai.tokens.generation", genT.toLong)
    if (reasoningT > 0) env.metrics.counterIncOf("ai.tokens.reasoning", reasoningT.toLong)
    val total = promptT.toLong + genT.toLong + reasoningT.toLong
    if (total > 0) env.metrics.counterIncOf("ai.tokens.total", total)
  }

  // cumulative cost in micro-USD (rounded). Precise accounting stays in LLMUsageAudit / budgets.
  def markCost(total: Double)(implicit env: Env): Unit = {
    if (!env.metricsEnabled) return
    if (total > 0) env.metrics.counterIncOf("ai.cost.micro_usd", Math.round(total * 1000000.0))
  }

  // cache hit/miss; kind = "simple" | "semantic".
  def markCache(kind: String, hit: Boolean)(implicit env: Env): Unit = {
    if (!env.metricsEnabled) return
    val outcome = if (hit) "hit" else "miss"
    env.metrics.counterInc(s"ai.cache.$outcome")
    env.metrics.counterInc(s"ai.cache.$kind.$outcome")
  }

  // per-guardrail outcome; kind = guardrail key (regex, llm, prompt_injection, ...), outcome = pass|deny|error.
  def markGuardrail(kind: String, outcome: String)(implicit env: Env): Unit = {
    if (!env.metricsEnabled) return
    env.metrics.counterInc("ai.guardrail.calls")
    env.metrics.counterInc(s"ai.guardrail.$outcome")
    env.metrics.counterInc(s"ai.guardrail.$kind.$outcome")
  }

  def markFallback()(implicit env: Env): Unit = {
    if (!env.metricsEnabled) return
    env.metrics.counterInc("ai.fallback.calls")
  }

  def markBudgetExceeded()(implicit env: Env): Unit = {
    if (!env.metricsEnabled) return
    env.metrics.counterInc("ai.budget.exceeded")
  }

  def markModelConstraintDenied()(implicit env: Env): Unit = {
    if (!env.metricsEnabled) return
    env.metrics.counterInc("ai.model_constraint.denied")
  }

  // circuit-breaker state transition; state = open|close|half_open.
  def markCircuit(state: String)(implicit env: Env): Unit = {
    if (!env.metricsEnabled) return
    env.metrics.counterInc(s"ai.circuit.$state")
  }

  // Wraps an Either-returning operation future: records calls/errors/duration (and budget-exceeded when the
  // Left carries a "budget exceeded" error), and runs `onSuccess` (e.g. token/cost metrics) only on Right.
  // For streaming ops the Right is the source returned before consumption, so duration is time-to-stream-start
  // and token/cost metrics must be recorded separately at stream completion.
  def around[T](op: String, providerKind: String, start: Long, f: Future[Either[JsValue, T]])(onSuccess: T => Unit)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, T]] = {
    f.andThen {
      case Success(Right(v)) =>
        markOperation(op, Some(providerKind), System.currentTimeMillis() - start, isError = false)
        onSuccess(v)
      case Success(Left(err)) =>
        // a budget-blocked request never hit the provider: count it as budget-exceeded, not an op error
        if ((err \ "error").asOpt[String].exists(_.contains("budget exceeded"))) markBudgetExceeded()
        else markOperation(op, Some(providerKind), System.currentTimeMillis() - start, isError = true)
      case Failure(_) =>
        markOperation(op, Some(providerKind), System.currentTimeMillis() - start, isError = true)
    }
  }
}
