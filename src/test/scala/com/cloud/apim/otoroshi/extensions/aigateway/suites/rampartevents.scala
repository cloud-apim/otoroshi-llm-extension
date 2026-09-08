package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.guardrails.RampartEngine
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.{RampartEventRedactor, RampartEventRedactorConfig}
import play.api.libs.json.{JsString, Json}

import java.nio.file.Paths

// Scrubbing an event is not scrubbing a body: an otoroshi event is mostly technical fields, and the plugin has
// to remove the personal data without eating the identifiers, urls and timestamps the audit exists for.
class RampartEventRedactionSuite extends munit.FunSuite {

  private val modelDir = "src/main/resources/cloudapim/extensions/ai/models/rampart"
  private lazy val engine: RampartEngine = RampartEngine.fromPaths(
    Paths.get(s"$modelDir/model_q4.onnx"),
    Paths.get(s"$modelDir/tokenizer.json"),
    Paths.get(s"$modelDir/config.json"),
  )

  // the shape this extension actually emits, cut down to what matters here
  def auditEvent = Json.obj(
    "@id" -> "1788858331000-abc",
    "@type" -> "AuditEvent",
    "audit" -> "LLMUsageAudit",
    "provider_kind" -> "openai",
    "route" -> Json.obj("id" -> "route_1", "name" -> "chat"),
    "input_prompt" -> Json.arr(
      Json.obj("role" -> "user", "content" -> "write to alex@example.com about invoice 4242 4242 4242 4242"),
    ),
    "output" -> Json.obj(
      "generations" -> Json.arr(Json.obj("message" -> Json.obj("content" -> "Sent a mail to alex@example.com"))),
    ),
    "usage" -> Json.obj("prompt_tokens" -> 12, "generation_tokens" -> 8),
  )

  val deterministic = RampartEventRedactorConfig.default.copy(deterministicOnly = true)

  test("personal data goes, technical fields stay") {
    val scrubbed = RampartEventRedactor.redact(auditEvent, deterministic, engine)
    val asText = scrubbed.toString()
    assert(!asText.contains("alex@example.com"), s"the email should be gone: ${asText}")
    assert(!asText.contains("4242 4242 4242 4242"), s"the card number should be gone: ${asText}")
    // an audit line that lost its identifiers is worthless, so everything outside the configured paths is kept
    assertEquals(scrubbed.\("@id").as[String], "1788858331000-abc")
    assertEquals(scrubbed.\("audit").as[String], "LLMUsageAudit")
    assertEquals(scrubbed.\("provider_kind").as[String], "openai")
    assertEquals(scrubbed.\("route").\("id").as[String], "route_1")
    // numbers are left alone: only string leaves are rewritten
    assertEquals(scrubbed.\("usage").\("prompt_tokens").as[Int], 12)
  }

  test("the same value gets the same placeholder across the whole event") {
    val scrubbed = RampartEventRedactor.redact(auditEvent, deterministic, engine)
    val prompt = scrubbed.\("input_prompt").toString()
    val output = scrubbed.\("output").toString()
    val placeholder = """\[EMAIL_\d+\]""".r
    val fromPrompt = placeholder.findFirstIn(prompt)
    val fromOutput = placeholder.findFirstIn(output)
    assert(fromPrompt.isDefined, s"no email placeholder in the prompt: ${prompt}")
    assert(fromOutput.isDefined, s"no email placeholder in the output: ${output}")
    // the mail appears in both the question and the answer: one placeholder, so the event stays readable
    assertEquals(fromPrompt, fromOutput, "the same email should keep the same placeholder across the event")
  }

  test("a path that is absent from the event is simply skipped") {
    val config = deterministic.copy(paths = Seq("input_prompt", "does.not.exist"))
    val scrubbed = RampartEventRedactor.redact(auditEvent, config, engine)
    assert(!scrubbed.\("input_prompt").toString().contains("alex@example.com"), "the existing path is still scrubbed")
    // the missing path changes nothing, and paths that were not configured keep their content
    assert(scrubbed.\("output").toString().contains("alex@example.com"), "an unconfigured path is left alone")
    assertEquals(scrubbed.\("@id").as[String], "1788858331000-abc")
  }

  test("nested paths are supported and nothing outside them is touched") {
    val event = Json.obj(
      "keep" -> "call me at alex@example.com",
      "nested" -> Json.obj("scrub" -> "call me at alex@example.com", "keep" -> "alex@example.com"),
    )
    val scrubbed = RampartEventRedactor.redact(event, deterministic.copy(paths = Seq("nested.scrub")), engine)
    assert(!scrubbed.\("nested").\("scrub").as[String].contains("alex@example.com"), "the targeted path should be scrubbed")
    assertEquals(scrubbed.\("keep").as[String], "call me at alex@example.com", "a sibling path is left alone")
    assertEquals(scrubbed.\("nested").\("keep").as[String], "alex@example.com", "a sibling inside the same object too")
  }

  test("a path that cannot be scrubbed is blanked, never passed through") {
    // stands in for a model misbehaving at runtime, without needing a model at all
    val scrubbed = RampartEventRedactor.redactWith(auditEvent, deterministic.paths, _ => throw new RuntimeException("boom"))
    val prompt = scrubbed.\("input_prompt").toString()
    assert(!prompt.contains("alex@example.com"), s"failing must never leak the data: ${prompt}")
    assertEquals(scrubbed.\("input_prompt").as[String], RampartEventRedactor.redactionFailed)
    // and the event still goes out with everything that was not at risk
    assertEquals(scrubbed.\("@id").as[String], "1788858331000-abc")
  }

  test("each exporter scrubs according to its own config") {
    // what otoroshi hands the plugin: the exporter's customTransform.config, verbatim
    val strict = RampartEventRedactorConfig.format.reads(Json.obj(
      "paths" -> Json.arr("input_prompt", "output"), "deterministic_only" -> true,
    )).get
    val lighter = RampartEventRedactorConfig.format.reads(Json.obj(
      "paths" -> Json.arr("output"), "deterministic_only" -> true,
    )).get

    val scrubbedStrict = RampartEventRedactor.redact(auditEvent, strict, engine)
    assert(!scrubbedStrict.\("input_prompt").toString().contains("alex@example.com"))
    assert(!scrubbedStrict.\("output").toString().contains("alex@example.com"))

    // the same event through an exporter configured differently keeps the prompt as is
    val scrubbedLighter = RampartEventRedactor.redact(auditEvent, lighter, engine)
    assert(scrubbedLighter.\("input_prompt").toString().contains("alex@example.com"), "this exporter was not asked to scrub the prompt")
    assert(!scrubbedLighter.\("output").toString().contains("alex@example.com"), "but it was asked to scrub the output")
  }

  test("a malformed config falls back to the defaults rather than scrubbing nothing") {
    val broken = RampartEventRedactorConfig.format.reads(Json.obj("paths" -> "not-an-array")).asOpt
      .getOrElse(RampartEventRedactorConfig.default)
    assertEquals(broken.paths, RampartEventRedactorConfig.defaultPaths)
  }

  test("config round trip keeps the defaults meaningful") {
    val parsed = RampartEventRedactorConfig.format.reads(Json.obj()).get
    assertEquals(parsed.paths, RampartEventRedactorConfig.defaultPaths)
    assertEquals(parsed.deterministicOnly, false, "the model is used unless it is explicitly turned off")
    val custom = RampartEventRedactorConfig.format.reads(Json.obj(
      "paths" -> Json.arr("a.b"), "min_score" -> 0.7, "deterministic_only" -> true, "entities" -> Json.arr("EMAIL"),
    )).get
    assertEquals(custom.paths, Seq("a.b"))
    assertEquals(custom.minScore, 0.7f)
    assertEquals(custom.deterministicOnly, true)
    assertEquals(custom.entities, Set("EMAIL"))
    // an empty path list would silently scrub nothing, which is never what someone means
    assertEquals(RampartEventRedactorConfig.format.reads(Json.obj("paths" -> Json.arr())).get.paths, RampartEventRedactorConfig.defaultPaths)
  }
}
