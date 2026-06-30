package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.guardrails.{PlaceholderAllocator, RampartEngine, RampartPiiGuardrail}

import java.nio.file.Paths

// Smoke test for the bundled Rampart ONNX model. Validates that:
//  - model_q4.onnx (4-bit MatMulNBits) loads and runs in ONNX Runtime on the JVM
//  - the tokenizer + BIO decoding produce sensible spans
//  - deterministic recognizers (SSN/EMAIL/...) and stable placeholder redaction work
class RampartEngineSuite extends munit.FunSuite {

  private val modelDir = "src/main/resources/cloudapim/extensions/ai/models/rampart"
  private lazy val engine: RampartEngine = RampartEngine.fromPaths(
    Paths.get(s"$modelDir/model_q4.onnx"),
    Paths.get(s"$modelDir/tokenizer.json"),
    Paths.get(s"$modelDir/config.json"),
  )
  private val enabled: Set[String] = RampartPiiGuardrail.defaultEntities

  test("model loads (q4 / MatMulNBits) and detects a name + an SSN") {
    val spans = engine.detectAll("My name is Alex Rivera and my SSN is 472-81-0094.", 0.4f, enabled)
    val entities = spans.map(_.entity).toSet
    assert(spans.nonEmpty, "expected at least one span")
    assert(entities.contains("SSN"), s"expected SSN (deterministic), got $entities")
    assert(entities.contains("GIVEN_NAME") || entities.contains("SURNAME"), s"expected a name from the model, got $entities")
  }

  test("deterministic recognizers catch email and credit card (Luhn)") {
    val spans = engine.detectAll("mail me at alex@example.com, card 4242 4242 4242 4242", 0.4f, enabled)
    val entities = spans.map(_.entity).toSet
    assert(entities.contains("EMAIL"), s"expected EMAIL, got $entities")
    assert(entities.contains("CREDIT_CARD"), s"expected CREDIT_CARD (valid Luhn), got $entities")
  }

  test("redaction replaces values with stable placeholders") {
    val alloc = new PlaceholderAllocator()
    val text = "Email alex@example.com. Again: alex@example.com."
    val redacted = engine.redactText(text, engine.detectAll(text, 0.4f, enabled), alloc)
    assert(!redacted.contains("alex@example.com"), s"email not redacted: $redacted")
    assert(redacted.contains("[EMAIL_1]"), s"expected [EMAIL_1] in: $redacted")
    // same value => same placeholder (no [EMAIL_2])
    assert(!redacted.contains("[EMAIL_2]"), s"placeholder not stable: $redacted")
  }

  test("clean text yields no spans") {
    assertEquals(engine.detectAll("the weather is nice today", 0.4f, enabled), Seq.empty)
  }

  test("redact -> reinflate round-trips to the original text") {
    val alloc = new PlaceholderAllocator()
    val text = "Email alex@example.com and my SSN is 472-81-0094."
    val redacted = engine.redactText(text, engine.detectAll(text, 0.4f, enabled), alloc)
    val mapping = alloc.mapping
    assert(mapping.nonEmpty, "expected a non-empty mapping")
    // this is exactly what the guardrail's After phase does to re-inflate
    val restored = mapping.foldLeft(redacted) { case (acc, (ph, orig)) => acc.replace(ph, orig) }
    assertEquals(restored, text)
  }
}
