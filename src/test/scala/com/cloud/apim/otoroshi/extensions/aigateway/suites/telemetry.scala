package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.decorators.LlmCallTelemetry
import com.cloud.apim.otoroshi.extensions.aigateway.*
import otoroshi.utils.TypedMap
import play.api.libs.json.*

/** How a model call ended and who it was for, read from what each provider actually returns. */
class LlmCallTelemetrySuite extends munit.FunSuite {

  private def response(messageRaw: JsObject = Json.obj(), raw: JsValue = Json.obj()): ChatResponse =
    ChatResponse(Seq(ChatGeneration(OutputChatMessage("assistant", "hi", None, messageRaw))), ChatResponseMetadata.empty, raw)

  private def chunk(content: Option[String], reason: Option[String] = None): ChatResponseChunk =
    ChatResponseChunk("id", 0L, "model", Seq(ChatResponseChunkChoice(0L, ChatResponseChunkChoiceDelta(content), reason)))

  test("the finish reason of a blocking response is found where each provider puts it") {
    assertEquals(LlmCallTelemetry.finishReasonOf(response(messageRaw = Json.obj("finish_reason" -> "length"))), Some("length"))
    assertEquals(LlmCallTelemetry.finishReasonOf(response(raw = Json.obj("choices" -> Json.arr(Json.obj("finish_reason" -> "content_filter"))))), Some("content_filter"))
    assertEquals(LlmCallTelemetry.finishReasonOf(response(raw = Json.obj("stop_reason" -> "max_tokens"))), Some("length"))
    assertEquals(LlmCallTelemetry.finishReasonOf(response(raw = Json.obj("stop_reason" -> "end_turn"))), Some("stop"))
    assertEquals(LlmCallTelemetry.finishReasonOf(response(messageRaw = Json.obj("done_reason" -> "stop"))), Some("stop"))
    assertEquals(LlmCallTelemetry.finishReasonOf(response(raw = Json.obj("finish_reason" -> "TOOL_CALL"))), Some("tool_calls"))
    assertEquals(LlmCallTelemetry.finishReasonOf(response(raw = Json.obj("status" -> "incomplete"))), Some("length"))
    assertEquals(LlmCallTelemetry.finishReasonOf(response(raw = Json.obj("status" -> "whatever"))), None)
    assertEquals(LlmCallTelemetry.finishReasonOf(response()), None)
  }

  test("a stream reports its last meaningful finish reason") {
    assertEquals(LlmCallTelemetry.finishReasonOf(Seq(chunk(Some("a")), chunk(None, Some("end_turn")))), Some("stop"))
    assertEquals(LlmCallTelemetry.finishReasonOf(Seq(chunk(Some("a"), Some("length")), chunk(None, Some("stop")))), Some("length"))
    assertEquals(LlmCallTelemetry.finishReasonOf(Seq(chunk(Some("a")))), None)
    assert(!LlmCallTelemetry.carriesToken(chunk(Some(""))), "a role only chunk is not a token")
    assert(LlmCallTelemetry.carriesToken(chunk(Some("a"))))
  }

  test("the session and the end user come from the request body when there is no header") {
    val body = Json.obj("user" -> "customer-42", "metadata" -> Json.obj("session_id" -> "sess_1"))
    assertEquals(LlmCallTelemetry.sessionIdOf(TypedMap.empty, body), Some("sess_1"))
    assertEquals(LlmCallTelemetry.sessionIdOf(TypedMap.empty, Json.obj("session_id" -> "  ")), None)
    assertEquals(LlmCallTelemetry.endUserOf(body), Some("customer-42"))
    assertEquals(LlmCallTelemetry.endUserOf(Json.obj("metadata" -> Json.obj("user_id" -> "u_1"))), Some("u_1"))
    assertEquals(LlmCallTelemetry.endUserOf(Json.obj("user" -> ("x" * 1000))).map(_.length), Some(256))
  }
}
