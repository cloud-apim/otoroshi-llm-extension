package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.catalog.CodingIndex
import com.cloud.apim.otoroshi.extensions.aigateway.decorators.OtoroshiRouterChatClient
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*

// The code router ranks its candidates on how good they are at writing code: the Coding Index of Artificial
// Analysis, bundled with the gateway and keyed by the name Artificial Analysis gives a model. A provider names
// the same model in its own way, and a model must never be given the score of another one.
class CodingIndexSuite extends munit.FunSuite {

  // an index of invented models: what is tested is how a model is found, not what the bundled file holds
  val index = CodingIndex.parse(Json.obj("models" -> Json.arr(
    Json.obj("slug" -> "acme-5", "coding_index" -> 40.0),
    Json.obj("slug" -> "acme-5-5", "coding_index" -> 60.0),
    Json.obj("slug" -> "acme-5-5-mini", "coding_index" -> 30.0),
    Json.obj("slug" -> "acme-5-5-low", "coding_index" -> 45.0),
    Json.obj("slug" -> "x3", "coding_index" -> 20.0),
    Json.obj("slug" -> "bolt3-7-max", "coding_index" -> 50.0),
    Json.obj("slug" -> "muse-4-5-small", "coding_index" -> 35.0),
    Json.obj("slug" -> "no-score"),
  )))

  test("a model is found under the name its provider gives it") {
    assertEquals(index.size, 7, "a model without a score is not in the index")
    Seq("acme-5-5", "acme-5.5", "ACME-5.5", "vendor/acme-5.5", "acme-5.5:free", "acme-5.5-2026-04-01", "acme-5.5-20260401", "acme-5.5-latest", "my-acme-5-5-deployment").foreach { name =>
      assertEquals(index.scoreFor(name), Some(60.0), name)
    }
  }

  test("the most specific name wins") {
    assertEquals(index.scoreFor("acme-5.5-mini"), Some(30.0))
    assertEquals(index.scoreFor("vendor/acme-5.5-mini-2026-04-01"), Some(30.0))
    assertEquals(index.scoreFor("acme-5.5-low"), Some(45.0))
  }

  test("a model is not given the score of an earlier version, nor of a name it only looks like") {
    assertEquals(index.scoreFor("acme-5"), Some(40.0))
    assertEquals(index.scoreFor("acme-5-2026-04-01"), Some(40.0), "a snapshot date is not a version")
    assertEquals(index.scoreFor("acme-5.7"), None, "5.7 is not 5")
    assertEquals(index.scoreFor("acme-5.5.1"), None, "5.5.1 is not 5.5")
    assertEquals(index.scoreFor("acme-6"), None)
    // `x3` is a model: it is not found in a name that merely ends the same way
    assertEquals(index.scoreFor("x3"), Some(20.0))
    assertEquals(index.scoreFor("vortex-3"), None)
    assertEquals(index.scoreFor("vortex3"), None)
    assertEquals(index.scoreFor(""), None)
    assertEquals(index.scoreFor("a-model-nobody-scored"), None)
  }

  test("a name cut differently, or written in another order, is the same model") {
    assertEquals(index.scoreFor("bolt-3.7-max"), Some(50.0), "bolt-3.7-max is bolt3-7-max")
    assertEquals(index.scoreFor("muse-small-4-5"), Some(35.0), "the provider puts the size before the version")
    assertEquals(index.scoreFor("muse-small-4-5-20260401"), Some(35.0))
    assertEquals(index.scoreFor("vendor.muse-small-4-5-20260401-v1:0"), Some(35.0), "inside the id of a cloud provider too")
    assertEquals(index.scoreFor("muse-small-4-6"), None)
  }

  test("the index bundled with the gateway is the one the router reads") {
    val bundled = Json.parse(getClass.getClassLoader.getResourceAsStream(CodingIndex.resource))
    assert(bundled.select("origin").asOptString.exists(_.contains("artificialanalysis.ai")), "the file says where it comes from")
    val models = bundled.select("models").as[Seq[JsObject]]
    assert(models.nonEmpty, "the bundled index has models")
    assertEquals(CodingIndex.bundled.size, models.size, "every model of the file is in the index")
    models.foreach { model =>
      val (slug, score) = (model.select("slug").asString, model.select("coding_index").as[Double])
      assert(score > 0 && score <= 100, s"${slug}: ${score} is not a coding index")
      assertEquals(OtoroshiRouterChatClient.codingScoreFor(slug), Some(score), s"${slug} should be scored as the file says")
    }
  }
}
