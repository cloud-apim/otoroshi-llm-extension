package com.cloud.apim.otoroshi.extensions.aigateway.suites

import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}

// Unit tests of the responses <-> chat/completions translation. The end to end behaviour is covered
// by ResponsesSuite, this pins the conversion rules themselves.
class OpenAiResponsesConverterSuite extends munit.FunSuite {

  import OpenAiResponsesBodyConverter._

  // ---- responses → chat/completions --------------------------------------------------------

  test("toChatCompletionsBody strips the parameters that only exist on /responses") {
    val body = toChatCompletionsBody(Json.obj(
      "model" -> "gpt-4o",
      "input" -> Json.arr(),
      "instructions" -> "be nice",
      "previous_response_id" -> "resp_1",
      "store" -> true,
      "truncation" -> "auto",
      "metadata" -> Json.obj("a" -> "b"),
      "background" -> false,
      "include" -> Json.arr("reasoning.encrypted_content"),
      "max_tool_calls" -> 3,
      "safety_identifier" -> "user-1",
      "prompt_cache_key" -> "key",
      "stream" -> true,
      "temperature" -> 0.4,
    ))
    Seq("input", "instructions", "previous_response_id", "store", "truncation", "metadata", "background",
      "include", "max_tool_calls", "safety_identifier", "prompt_cache_key", "stream").foreach { param =>
      assert(body.select(param).asOpt[JsValue].isEmpty, s"'${param}' should have been stripped: ${body.stringify}")
    }
    assertEquals(body.select("model").asOptString, Some("gpt-4o"))
    assertEquals(body.select("temperature").asOpt[Double], Some(0.4))
  }

  test("toChatCompletionsBody renames the parameters that have a chat equivalent") {
    val body = toChatCompletionsBody(Json.obj(
      "max_output_tokens" -> 128,
      "reasoning" -> Json.obj("effort" -> "high"),
      "text" -> Json.obj("format" -> Json.obj(
        "type" -> "json_schema",
        "name" -> "answer",
        "strict" -> true,
        "schema" -> Json.obj("type" -> "object"),
      )),
    ))
    assertEquals(body.select("max_tokens").asOpt[Int], Some(128))
    assert(body.select("max_output_tokens").asOpt[JsValue].isEmpty)
    assertEquals(body.select("reasoning_effort").asOptString, Some("high"))
    assertEquals(body.at("response_format.type").asOptString, Some("json_schema"))
    assertEquals(body.at("response_format.json_schema.name").asOptString, Some("answer"))
    assertEquals(body.at("response_format.json_schema.strict").asOptBoolean, Some(true))
    assertEquals(body.at("response_format.json_schema.schema.type").asOptString, Some("object"))
  }

  test("toChatCompletionsBody keeps the gateway parameters, which the decorators consume") {
    val body = toChatCompletionsBody(Json.obj("mock_response" -> "hello", "context" -> "ctx_1", "provider" -> "provider_1"))
    assertEquals(body.select("mock_response").asOptString, Some("hello"))
    assertEquals(body.select("context").asOptString, Some("ctx_1"))
    assertEquals(body.select("provider").asOptString, Some("provider_1"))
  }

  test("toChatCompletionsBody converts flat responses tools and drops an empty tool list") {
    val body = toChatCompletionsBody(Json.obj("tools" -> Json.arr(Json.obj(
      "type" -> "function",
      "name" -> "get_weather",
      "description" -> "get the weather",
      "strict" -> true,
      "parameters" -> Json.obj("type" -> "object"),
    ))))
    assertEquals(body.at("tools.0.type").asOptString, Some("function"))
    assertEquals(body.at("tools.0.function.name").asOptString, Some("get_weather"))
    assertEquals(body.at("tools.0.function.description").asOptString, Some("get the weather"))
    assertEquals(body.at("tools.0.function.strict").asOptBoolean, Some(true))
    assert(toChatCompletionsBody(Json.obj("tools" -> Json.arr())).select("tools").asOpt[JsValue].isEmpty, "an empty tool list should be dropped")
    // a tool already in the chat shape is left alone
    val chatShaped = toChatCompletionsBody(Json.obj("tools" -> Json.arr(Json.obj("type" -> "function", "function" -> Json.obj("name" -> "x")))))
    assertEquals(chatShaped.at("tools.0.function.name").asOptString, Some("x"))
  }

  test("inputToMessages turns every input shape into chat messages") {
    assertEquals(inputToMessages(Json.obj("input" -> "hello")).head.select("content").asString, "hello")

    val messages = inputToMessages(Json.obj(
      "instructions" -> "be nice",
      "input" -> Json.arr(
        Json.obj("type" -> "message", "role" -> "developer", "content" -> "you are a dev"),
        Json.obj("type" -> "message", "role" -> "user", "content" -> Json.arr(
          Json.obj("type" -> "input_text", "text" -> "what is this ?"),
          Json.obj("type" -> "input_image", "image_url" -> "https://example.com/cat.png"),
          Json.obj("type" -> "input_file", "filename" -> "doc.pdf", "file_data" -> "data:application/pdf;base64,JVBERi0="),
        )),
        Json.obj("type" -> "function_call", "call_id" -> "call_1", "name" -> "get_weather", "arguments" -> "{}"),
        Json.obj("type" -> "function_call_output", "call_id" -> "call_1", "output" -> "sunny"),
        // provider state, nothing a chat/completions provider can do with it
        Json.obj("type" -> "reasoning", "id" -> "rs_1", "summary" -> Json.arr()),
        // an item without a type but with a role is a message too
        Json.obj("role" -> "assistant", "content" -> "ok"),
      ),
    ))
    assertEquals(messages.head.select("role").asString, "system", "instructions become a system message")
    assertEquals(messages(1).select("role").asString, "system", "the developer role becomes system")
    val parts = messages(2).select("content").as[Seq[JsObject]]
    assertEquals(parts.map(_.select("type").asString).toList, List("text", "image_url", "file"))
    assertEquals(parts(1).at("image_url.url").asOptString, Some("https://example.com/cat.png"))
    assertEquals(parts(2).at("file.filename").asOptString, Some("doc.pdf"))
    assertEquals(messages(3).at("tool_calls.0.function.name").asOptString, Some("get_weather"))
    assertEquals(messages(4).select("role").asString, "tool")
    assertEquals(messages(4).select("tool_call_id").asString, "call_1")
    assertEquals(messages.last.select("content").asString, "ok")
    assertEquals(messages.size, 6, s"the reasoning item should have been skipped: ${messages}")
  }

  // ---- chat → native /responses ------------------------------------------------------------

  test("toResponsesBody renames the token budget, whatever the provider calls it") {
    assertEquals(toResponsesBody(Json.obj("max_tokens" -> 12)).select("max_output_tokens").asOpt[Int], Some(12))
    assertEquals(toResponsesBody(Json.obj("max_completion_tokens" -> 13)).select("max_output_tokens").asOpt[Int], Some(13))
    // ollama spelling
    assertEquals(toResponsesBody(Json.obj("num_predict" -> 14)).select("max_output_tokens").asOpt[Int], Some(14))
    // the request wins over the provider option
    assertEquals(toResponsesBody(Json.obj("max_tokens" -> 12, "max_output_tokens" -> 99)).select("max_output_tokens").asOpt[Int], Some(99))
    assert(toResponsesBody(Json.obj("max_tokens" -> 12)).select("max_tokens").asOpt[JsValue].isEmpty)
  }

  test("toResponsesBody drops the parameters that do not exist on /responses") {
    val body = toResponsesBody(Json.obj(
      "model" -> "gpt-4o",
      "messages" -> Json.arr(),
      "frequency_penalty" -> 1, "presence_penalty" -> 1, "logit_bias" -> Json.obj(), "logprobs" -> true,
      "stop" -> ".", "n" -> 2, "seed" -> 3, "stream" -> true, "stream_options" -> Json.obj(),
      // ollama-native generation options
      "top_k" -> 40, "num_ctx" -> 4096, "repeat_penalty" -> 1.1, "tfs_z" -> 1, "num_gpu" -> 1,
      // gateway-internal
      "mock_response" -> "x", "context" -> "ctx", "provider" -> "p",
      "temperature" -> 0.2, "top_p" -> 0.9,
    ))
    Seq("messages", "frequency_penalty", "presence_penalty", "logit_bias", "logprobs", "stop", "n", "seed",
      "stream", "stream_options", "top_k", "num_ctx", "repeat_penalty", "tfs_z", "num_gpu",
      "mock_response", "context", "provider").foreach { param =>
      assert(body.select(param).asOpt[JsValue].isEmpty, s"'${param}' should have been dropped: ${body.stringify}")
    }
    assertEquals(body.select("model").asOptString, Some("gpt-4o"))
    assertEquals(body.select("temperature").asOpt[Double], Some(0.2))
    assertEquals(body.select("top_p").asOpt[Double], Some(0.9))
  }

  test("toResponsesBody maps response_format and reasoning_effort, in both shapes") {
    assertEquals(toResponsesBody(Json.obj("response_format" -> "json_object")).at("text.format.type").asOptString, Some("json_object"))
    val schema = toResponsesBody(Json.obj("response_format" -> Json.obj(
      "type" -> "json_schema",
      "json_schema" -> Json.obj("name" -> "answer", "strict" -> true, "schema" -> Json.obj("type" -> "object")),
    )))
    assertEquals(schema.at("text.format.type").asOptString, Some("json_schema"))
    assertEquals(schema.at("text.format.name").asOptString, Some("answer"))
    assertEquals(schema.at("text.format.strict").asOptBoolean, Some(true))
    assertEquals(schema.at("text.format.schema.type").asOptString, Some("object"))
    assertEquals(toResponsesBody(Json.obj("reasoning_effort" -> "low")).at("reasoning.effort").asOptString, Some("low"))
    // an explicit responses-shaped value wins over the renamed chat one
    val both = toResponsesBody(Json.obj("reasoning_effort" -> "low", "reasoning" -> Json.obj("effort" -> "high")))
    assertEquals(both.at("reasoning.effort").asOptString, Some("high"))
  }

  test("toResponsesBody flattens chat tools and drops an empty tool list") {
    val body = toResponsesBody(Json.obj("tools" -> Json.arr(Json.obj(
      "type" -> "function",
      "function" -> Json.obj("name" -> "get_weather", "description" -> "d", "strict" -> false, "parameters" -> Json.obj("type" -> "object")),
    ))))
    assertEquals(body.at("tools.0.name").asOptString, Some("get_weather"))
    assertEquals(body.at("tools.0.description").asOptString, Some("d"))
    assertEquals(body.at("tools.0.strict").asOptBoolean, Some(false))
    assert(body.at("tools.0.function").asOpt[JsObject].isEmpty, "responses tools are flat")
    assert(toResponsesBody(Json.obj("tools" -> Json.arr())).select("tools").asOpt[JsValue].isEmpty)
  }

  test("promptToInput serializes messages, tool calls and content parts") {
    val prompt = ChatPrompt(Seq(
      InputChatMessage.fromJson(Json.obj("role" -> "system", "content" -> "be nice")),
      InputChatMessage.fromJson(Json.obj("role" -> "user", "content" -> Json.arr(
        Json.obj("type" -> "text", "text" -> "what is this ?"),
        Json.obj("type" -> "file", "file" -> Json.obj("filename" -> "doc.pdf", "file_data" -> "data:application/pdf;base64,JVBERi0=")),
      ))),
      InputChatMessage.fromJson(Json.obj("role" -> "assistant", "content" -> "a document")),
      InputChatMessage.fromJson(Json.obj("role" -> "assistant", "tool_calls" -> Json.arr(Json.obj(
        "id" -> "call_1", "type" -> "function", "function" -> Json.obj("name" -> "get_weather", "arguments" -> """{"a":1}"""),
      )))),
      InputChatMessage.fromJson(Json.obj("role" -> "tool", "tool_call_id" -> "call_1", "content" -> "sunny")),
    ))
    val input = promptToInput(prompt).value.map(_.asObject)
    assertEquals(input.head.select("role").asString, "system")
    assertEquals(input.head.at("content.0.type").asString, "input_text")
    assertEquals(input(1).at("content.0.type").asString, "input_text")
    assertEquals(input(1).at("content.1.type").asString, "input_file")
    assertEquals(input(1).at("content.1.filename").asString, "doc.pdf")
    // an assistant message replayed as input carries output_text
    assertEquals(input(2).at("content.0.type").asString, "output_text")
    assertEquals(input(3).select("type").asString, "function_call")
    assertEquals(input(3).select("call_id").asString, "call_1")
    assertEquals(input(3).select("arguments").asString, """{"a":1}""")
    assertEquals(input(4).select("type").asString, "function_call_output")
    assertEquals(input(4).select("call_id").asString, "call_1")
    assertEquals(input(4).select("output").asString, "sunny")
  }

  test("promptToInput serializes an image as an input_image data uri") {
    val image = ChatMessageContent.ImageContent("image/png", None, Some(ByteString("not-an-image")))
    val prompt = ChatPrompt(Seq(InputChatMessage("user", Seq(image), None, None, Json.obj("role" -> "user"))))
    val part = promptToInput(prompt).value.head.at("content.0")
    assertEquals(part.select("type").asOptString, Some("input_image"))
    assert(part.select("image_url").asString.startsWith("data:image/png;base64,"), s"bad image part: ${part}")
  }

  test("chatMessagesToInput turns tool results back into responses items") {
    val items = chatMessagesToInput(Seq(
      Json.obj("role" -> "assistant", "tool_calls" -> Json.arr(Json.obj("id" -> "call_1", "function" -> Json.obj("name" -> "f", "arguments" -> "{}")))),
      Json.obj("role" -> "tool", "tool_call_id" -> "call_1", "content" -> "result"),
    )).value.map(_.asObject)
    assertEquals(items.map(_.select("type").asString).toList, List("function_call", "function_call_output"))
    assertEquals(items.last.select("output").asString, "result")
  }

  // ---- native output → gateway model -------------------------------------------------------

  test("outputToChatMessages maps text, tool calls and reasoning") {
    val choices = outputToChatMessages(Json.obj("output" -> Json.arr(
      Json.obj("type" -> "reasoning", "id" -> "rs_1", "summary" -> Json.arr(Json.obj("type" -> "summary_text", "text" -> "thinking"))),
      Json.obj("type" -> "message", "role" -> "assistant", "content" -> Json.arr(
        Json.obj("type" -> "output_text", "text" -> "hello ", "annotations" -> Json.arr(Json.obj("type" -> "url_citation"))),
        Json.obj("type" -> "output_text", "text" -> "world"),
      )),
      Json.obj("type" -> "function_call", "call_id" -> "call_1", "name" -> "get_weather", "arguments" -> """{"a":1}"""),
    )))
    assertEquals(choices.size, 1)
    val choice = choices.head
    assertEquals(choice.select("finish_reason").asString, "tool_calls")
    assertEquals(choice.at("message.content").asString, "hello world")
    assertEquals(choice.at("message.tool_calls.0.id").asOptString, Some("call_1"))
    assertEquals(choice.at("message.tool_calls.0.function.name").asOptString, Some("get_weather"))
    assertEquals(choice.at("message.tool_calls.0.function.arguments").asOptString, Some("""{"a":1}"""))
    assertEquals(choice.at("message.reasoning_content").asOptString, Some("thinking"))
    assertEquals(choice.at("message.annotations.0.type").asOptString, Some("url_citation"))
    assertEquals(choice.at("message.reasoning_items.0.id").asOptString, Some("rs_1"))
  }

  test("outputToChatMessages maps a refusal and reports a plain stop otherwise") {
    val choice = outputToChatMessages(Json.obj("output" -> Json.arr(
      Json.obj("type" -> "message", "role" -> "assistant", "content" -> Json.arr(Json.obj("type" -> "refusal", "refusal" -> "I cannot"))),
    ))).head
    assertEquals(choice.select("finish_reason").asString, "stop")
    assertEquals(choice.at("message.refusal").asOptString, Some("I cannot"))
  }

  test("usage is only read from a real usage object") {
    // response.created and response.in_progress carry "usage": null
    assertEquals(usageFromResponsePayload(Json.obj("usage" -> play.api.libs.json.JsNull)), None)
    assertEquals(usageFromResponsePayload(Json.obj()), None)
    val usage = usageFromResponsePayload(Json.obj("usage" -> Json.obj(
      "input_tokens" -> 11, "output_tokens" -> 7, "output_tokens_details" -> Json.obj("reasoning_tokens" -> 3),
    ))).get
    assertEquals(usage.promptTokens, 11L)
    assertEquals(usage.generationTokens, 7L)
    assertEquals(usage.reasoningTokens, 3L)
  }

  test("the stream accumulator rebuilds the text, the tool calls and the usage") {
    val acc = new ResponsesStreamAccumulator()
    def chunk(delta: ChatResponseChunkChoiceDelta, usage: Option[ChatResponseMetadataUsage] = None): ChatResponseChunk =
      ChatResponseChunk("id", 0L, "model", Seq(ChatResponseChunkChoice(0L, delta, None)), usage = usage)
    acc.accumulate(chunk(ChatResponseChunkChoiceDelta(Some("hello "))))
    acc.accumulate(chunk(ChatResponseChunkChoiceDelta(Some("world"))))
    acc.accumulate(chunk(ChatResponseChunkChoiceDelta(None, tool_calls = Seq(ChatResponseChunkChoiceDeltaToolCall(
      0L, Some("call_1"), Some("function"), ChatResponseChunkChoiceDeltaToolCallFunction(Some("get_weather"), ""))))))
    acc.accumulate(chunk(ChatResponseChunkChoiceDelta(None, tool_calls = Seq(ChatResponseChunkChoiceDeltaToolCall(
      0L, None, Some("function"), ChatResponseChunkChoiceDeltaToolCallFunction(None, """{"a":"""))))))
    acc.accumulate(chunk(ChatResponseChunkChoiceDelta(None, tool_calls = Seq(ChatResponseChunkChoiceDeltaToolCall(
      0L, None, Some("function"), ChatResponseChunkChoiceDeltaToolCallFunction(None, "1}"))))))
    acc.accumulate(chunk(ChatResponseChunkChoiceDelta(None), usage = Some(ChatResponseMetadataUsage(21, 3, 0))))
    assertEquals(acc.wholeText, "hello world")
    assert(acc.hasToolCalls)
    val call = acc.functionCallItems(idx => s"fc_${idx}").head
    assertEquals(call.select("type").asString, "function_call")
    assertEquals(call.select("call_id").asString, "call_1")
    assertEquals(call.select("name").asString, "get_weather")
    assertEquals(call.select("arguments").asString, """{"a":1}""")
    assertEquals(acc.usage.promptTokens, 21L)
    assertEquals(acc.usageJson.select("input_tokens").asOpt[Long], Some(21L))
    assertEquals(acc.usageJson.select("total_tokens").asOpt[Long], Some(24L))
  }
}
