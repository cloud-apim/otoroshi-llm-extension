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
// send is counted, or the budgets would miss every call it serves for as long as it is not upgraded.
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

  // what a worker pushes: for each counter, what was consumed on each budget since its last push
  def deltas(counters: (String, BigDecimal)*): JsObject =
    JsObject(counters.map { case (name, value) => name -> Json.obj(s"${budgetId}:cycle" -> JsNumber(value)) })

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
    val (status, body) = push(deltas((firstCounters ++ Seq[(String, BigDecimal)](
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
    val (status, body) = push(deltas(firstCounters*))
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
}
