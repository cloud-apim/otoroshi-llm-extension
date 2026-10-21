package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.LlmExtensionOneOtoroshiServerPerSuite
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.{JsObject, Json}

import java.util.UUID
import scala.concurrent.duration.DurationInt

// the description of an entity is optional: one created without it is accepted, and read back empty
class EntitiesDescriptionSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  val entities = Seq(
    "prompt-contexts",
    "prompt-templates",
    "prompts",
    "persistent-memories",
    "moderation-models",
    "ai-budgets",
    "video-models",
    "search-engines",
    "embedding-stores",
    "embedding-models",
    "ocr-models",
    "image-models",
    "audio-models",
    "mcp-connectors",
  )

  test("an entity created without description is accepted") {
    val failures = entities.flatMap { plural =>
      val api = client.forLlmEntity(plural)
      val template = api.call("GET", Some("_template")).awaitf(10.seconds).bodyJson.as[JsObject]
      val id = s"${plural}_${UUID.randomUUID().toString}"
      val created = api.createRaw(template - "description" ++ Json.obj("id" -> id)).awaitf(10.seconds)
      val failure = if (!created.createdOrUpdated) {
        Some(s"$plural not created: ${created.state}")
      } else {
        val read = api.call("GET", Some(id)).awaitf(10.seconds)
        if (read.status != 200) Some(s"$plural not read back: ${read.state}")
        else if (read.bodyJson.select("description").asOpt[String] != Some("")) Some(s"$plural read back with description ${read.bodyJson.select("description").asOpt[String]}")
        else None
      }
      api.deleteRaw(id).awaitf(10.seconds)
      failure
    }
    assert(failures.isEmpty, failures.mkString("\n"))
  }
}
