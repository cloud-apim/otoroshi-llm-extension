package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.LlmExtensionOneOtoroshiServerPerSuite
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiBudgetConsumptions
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.*

import java.nio.charset.StandardCharsets
import java.util.{Base64, UUID}
import scala.concurrent.duration.DurationInt

// In a cluster, a worker counts what its calls consume and pushes it to the leaders, one object by counter. The
// counters grew over time: a worker of a previous version does not send the ones it does not know. What it does
// send is counted, or the budgets would miss every call it serves for as long as it is not upgraded. It is counted
// in the cycle of the budget it was consumed in, which may be over by the time the push comes in.
class BudgetClusterDeltasSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  def ext: AiExtension = otoroshi.env.adminExtensions.extension[AiExtension].get

  val budgetId = s"budget_${UUID.randomUUID()}"
  val budget = Json.obj(
    "id" -> budgetId,
    "name" -> "budget of a cluster",
    "description" -> "",
    "enabled" -> true,
    "duration" -> Json.obj("value" -> 1, "unit" -> "year"),
    "limits" -> Json.obj("total_usd" -> 1000, "total_tokens" -> 100000000),
    "scope" -> Json.obj(),
    "action_on_exceed" -> Json.obj("mode" -> "soft", "alert_on_exceed" -> false, "alert_on_almost_exceed" -> false),
  )

  lazy val setup: Unit = {
    client.forLlmEntity("ai-budgets").createRaw(budget).awaitf(10.seconds)
    await(5.seconds)
  }

  def consumptions(): AiBudgetConsumptions = {
    given env: Env = otoroshi.env
    ext.states.budget(budgetId).get.getConsumptions().awaitf(10.seconds)
  }

  def currentCycle: String = ext.states.budget(budgetId).get.cycleId

  // what a worker pushes: for each counter, what was consumed on each budget, in which of its cycles, since its last push
  def deltas(cycle: String, counters: (String, BigDecimal)*): JsObject =
    JsObject(counters.map { case (name, value) => name -> Json.obj(s"${budgetId}:${cycle}" -> JsNumber(value)) })

  def push(payload: JsObject): (Int, JsValue) = {
    val basic = Base64.getEncoder.encodeToString("admin-api-apikey-id:admin-api-apikey-secret".getBytes(StandardCharsets.UTF_8))
    val resp = client.call("PUT", s"http://otoroshi-api.oto.tools:$port/api/extensions/cloud-apim/extensions/ai-extension/cluster/budgets/deltas",
      Map("Authorization" -> s"Basic $basic", "Content-Type" -> "application/json"), Some(payload)).awaitf(30.seconds)
    (resp.status, Json.parse(resp.body))
  }

  // the counters a worker has known since budgets work in a cluster
  val firstCounters: Seq[(String, BigDecimal)] = Seq(
    "total_usd" -> BigDecimal("0.5"), "total_tokens" -> 1500,
    "inference_usd" -> BigDecimal("0.25"), "inference_tokens" -> 1000,
    "image_usd" -> 0, "image_tokens" -> 0,
    "audio_usd" -> 0, "audio_tokens" -> 0,
    "video_usd" -> 0, "video_tokens" -> 0,
    "embedding_usd" -> BigDecimal("0.25"), "embedding_tokens" -> 500,
    "moderation_usd" -> 0, "moderation_tokens" -> 0,
  )

  test("what a worker of the current version pushes is counted") {
    setup
    val before = consumptions()
    val (status, body) = push(deltas(currentCycle, (firstCounters ++ Seq[(String, BigDecimal)](
      "ocr_usd" -> BigDecimal("0.125"), "ocr_pages" -> 3,
      "decision_usd" -> BigDecimal("0.125"), "decision_tokens" -> 200,
    ))*))
    assertEquals((status, body.select("done").asOpt[Boolean]), (200, Some(true)), s"the deltas should be accepted, got ${body}")
    await(2.seconds)
    val after = consumptions()
    assertEquals(after.totalTokens - before.totalTokens, 1500L)
    assertEquals(after.inferenceTokens - before.inferenceTokens, 1000L)
    assertEquals(after.embeddingTokens - before.embeddingTokens, 500L)
    assertEquals(after.ocrPages - before.ocrPages, 3L)
    assertEquals(after.decisionTokens - before.decisionTokens, 200L)
    assertEquals(after.totalUsd - before.totalUsd, BigDecimal("0.5"))
  }

  test("what a worker of a previous version pushes is counted too, without the counters it does not know") {
    setup
    val before = consumptions()
    val (status, body) = push(deltas(currentCycle, firstCounters*))
    assertEquals((status, body.select("done").asOpt[Boolean]), (200, Some(true)), s"the deltas should be accepted, got ${body}")
    await(2.seconds)
    val after = consumptions()
    assertEquals(after.totalTokens - before.totalTokens, 1500L)
    assertEquals(after.inferenceTokens - before.inferenceTokens, 1000L)
    assertEquals(after.inferenceUsd - before.inferenceUsd, BigDecimal("0.25"))
    assertEquals(after.embeddingTokens - before.embeddingTokens, 500L)
    assertEquals(after.totalUsd - before.totalUsd, BigDecimal("0.5"))
    assertEquals(after.ocrPages - before.ocrPages, 0L)
  }

  test("what a worker consumed in a cycle that is over is counted in that cycle, not in the one that followed") {
    setup
    given env: Env = otoroshi.env
    val budget = ext.states.budget(budgetId).get
    val over = "a-cycle-that-is-over"
    def counted(counter: String): Long = env.datastores.rawDataStore.get(budget.counterKey(over, counter)).awaitf(10.seconds).map(_.utf8String.toLong).getOrElse(0L)
    val before = consumptions()
    val (status, body) = push(deltas(over, "total_usd" -> BigDecimal("0.5"), "total_tokens" -> 1500, "inference_usd" -> BigDecimal("0.5"), "inference_tokens" -> 1500))
    assertEquals((status, body.select("done").asOpt[Boolean]), (200, Some(true)), s"the deltas should be accepted, got ${body}")
    await(2.seconds)
    val after = consumptions()
    assertEquals(after.totalTokens - before.totalTokens, 0L, "the current cycle did not consume these tokens")
    assertEquals(after.totalUsd - before.totalUsd, BigDecimal(0), "nor these dollars")
    assertEquals((counted("total-tokens"), counted("inference-tokens")), (1500L, 1500L), "the cycle they were consumed in did")
    assertEquals(counted("total-usd"), 500000000L, "dollars are counted in billionths")
  }
}
