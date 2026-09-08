package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiProvider, EmbeddingModel, EntitySecrets, redactedJson}
import otoroshi.models.EntityLocation
import play.api.libs.json.Json

import java.util.UUID

// Provider entities are serialized whole into the audit events, so their credentials must never make it into
// an event at all - redacting them further down the pipeline would already be too late.
class EntitySecretsSuite extends munit.FunSuite {

  val apiKey = "sk-super-secret-value"

  test("credentials are masked wherever they sit, the rest is untouched") {
    val json = Json.obj(
      "name" -> "prod",
      "connection" -> Json.obj("base_url" -> "https://api.openai.com", "token" -> apiKey, "timeout" -> 30000),
      "nested" -> Json.arr(Json.obj("api_key" -> apiKey), Json.obj("API_KEY" -> apiKey)),
    )
    val redacted = EntitySecrets.redact(json)
    assert(!redacted.toString().contains(apiKey), s"no credential should survive: ${redacted}")
    assertEquals(redacted.\("connection").\("token").as[String], EntitySecrets.marker)
    // matched case insensitively and at any depth, because the shape differs between entity kinds
    assertEquals(redacted.\("nested").\(0).\("api_key").as[String], EntitySecrets.marker)
    assertEquals(redacted.\("nested").\(1).\("API_KEY").as[String], EntitySecrets.marker)
    // everything an audit event is actually for is preserved
    assertEquals(redacted.\("name").as[String], "prod")
    assertEquals(redacted.\("connection").\("base_url").as[String], "https://api.openai.com")
    assertEquals(redacted.\("connection").\("timeout").as[Int], 30000)
  }

  test("a chat provider can be put in an event without leaking its key") {
    val provider = AiProvider(
      id = UUID.randomUUID().toString,
      name = "openai prod",
      provider = "openai",
      connection = Json.obj("base_url" -> "https://api.openai.com", "token" -> apiKey),
      options = Json.obj("model" -> "gpt-4.1-nano"),
    )
    assert(provider.json.toString().contains(apiKey), "precondition: the raw entity does carry the key")
    val redacted = provider.redactedJson
    assert(!redacted.toString().contains(apiKey), s"the key must not reach an event: ${redacted}")
    assertEquals(redacted.\("name").as[String], "openai prod")
    assertEquals(redacted.\("provider").as[String], "openai")
    assertEquals(redacted.\("options").\("model").as[String], "gpt-4.1-nano")
  }

  test("the same holds for the other model kinds, whose credentials sit one level deeper") {
    val model = EmbeddingModel(
      EntityLocation.default, UUID.randomUUID().toString, "embeddings", "", Seq.empty, Map.empty, "openai",
      Json.obj("connection" -> Json.obj("token" -> apiKey, "base_url" -> "https://api.openai.com")),
    )
    assert(model.json.toString().contains(apiKey), "precondition: the raw entity does carry the key")
    val redacted = model.redactedJson
    assert(!redacted.toString().contains(apiKey), s"the key must not reach an event: ${redacted}")
    assertEquals(redacted.\("config").\("connection").\("base_url").as[String], "https://api.openai.com")
  }

  test("a comma separated key rotation pool is redacted as a whole") {
    val pool = "sk-one,sk-two,sk-three"
    val provider = AiProvider(
      id = UUID.randomUUID().toString, name = "rotating", provider = "openai",
      connection = Json.obj("token" -> pool), options = Json.obj(),
    )
    val redacted = provider.redactedJson.toString()
    assert(!redacted.contains("sk-one") && !redacted.contains("sk-two") && !redacted.contains("sk-three"), redacted)
  }
}
