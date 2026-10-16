package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.*
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.{AnthropicCompatProxy, AnthropicStreamWriter}
import play.api.libs.json.*

// The reasoning of a model is read whatever shape the caller sends it back in, kept as `reasoning_details`, and
// written for each provider the way its api takes it back, without what that api can not read. The end to end
// behaviour is covered by ReasoningContinuitySuite, this pins the rules themselves.
class ReasoningDetailsSuite extends munit.FunSuite {

  import Reasoning.*

  val signed: JsObject = textDetail("I think", Formats.Anthropic, Some("sig-1"))
  val redacted: JsObject = encryptedDetail("opaque", Formats.Anthropic)
  val plain: JsObject = textDetail("I also think")
  val summary: JsObject = summaryDetail("I thought", Formats.OpenAiResponses, Some("rs_1"))
  val encrypted: JsObject = encryptedDetail("gAAAA", Formats.OpenAiResponses, Some("rs_1"))

  def withoutIndex(details: Seq[JsObject]): Seq[JsObject] = details.map(_ - "index")

  def assistant(fields: (String, Json.JsValueWrapper)*): InputChatMessage =
    InputChatMessage.fromJson(Json.obj("role" -> "assistant", "content" -> "the answer") ++ Json.obj(fields*))

  // ---- reading -------------------------------------------------------------------------------

  test("the reasoning of a message is read whatever the field it comes in") {
    assertEquals(withoutIndex(of(Json.obj("reasoning_details" -> Json.arr(signed, plain)))), Seq(signed, plain), "reasoning_details")
    assertEquals(withoutIndex(of(Json.obj("reasoning_content" -> "x"))), Seq(textDetail("x")), "reasoning_content")
    assertEquals(withoutIndex(of(Json.obj("reasoning" -> "x"))), Seq(textDetail("x")), "reasoning")
    assertEquals(withoutIndex(of(Json.obj("thinking" -> "x"))), Seq(textDetail("x")), "the thinking of Ollama")
    assertEquals(withoutIndex(of(Json.obj("reasoning_details" -> "x"))), Seq(textDetail("x")), "the string the gateway used to answer with")
    assertEquals(of(Json.obj("reasoning" -> "  ")), Seq.empty, "a blank reasoning is none")
    assertEquals(of(Json.obj("content" -> "plain")), Seq.empty)
    assertEquals(of(Json.obj("reasoning_details" -> Json.arr(plain))).map(_.select("index").asOpt[Int]), Seq(Some(0)), "the details are numbered")
  }

  test("the thinking blocks of an Anthropic message are its reasoning, not its content") {
    val message = Json.obj("role" -> "assistant", "content" -> Json.arr(
      Json.obj("type" -> "thinking", "thinking" -> "I think", "signature" -> "sig-1"),
      Json.obj("type" -> "redacted_thinking", "data" -> "opaque"),
      Json.obj("type" -> "text", "text" -> "the answer"),
    ))
    assertEquals(withoutIndex(of(message)), Seq(signed, redacted))
    val input = InputChatMessage.fromJson(message)
    assertEquals(input.contentParts, Seq(ChatMessageContent.TextContent("the answer")), "only the text is content")
    assertEquals(withoutIndex(input.reasoning), Seq(signed, redacted))
    // the thinking of the models that do not show it (`display: omitted`) is all in its signature
    assertEquals(withoutIndex(of(Json.obj("content" -> Json.arr(Json.obj("type" -> "thinking", "thinking" -> "", "signature" -> "sig-2"))))), Seq(textDetail("", Formats.Anthropic, Some("sig-2"))))
    // the thinking chunks of Mistral nest their text, and may be signed
    assertEquals(withoutIndex(of(Json.obj("content" -> Json.arr(Json.obj("type" -> "thinking", "thinking" -> Json.arr(Json.obj("type" -> "text", "text" -> "hmm")), "signature" -> "m-sig"))))), Seq(textDetail("hmm", Formats.Unknown, Some("m-sig"))))
  }

  test("only an assistant message has a reasoning") {
    assertEquals(InputChatMessage.fromJson(Json.obj("role" -> "user", "content" -> "hi", "reasoning" -> "x")).reasoning, Seq.empty)
    assertEquals(InputChatMessage.fromJson(Json.obj("role" -> "user", "content" -> "hi", "reasoning" -> "x")).json(ChatMessageContentFlavor.OpenAi, ReasoningInput.Content), Json.obj("role" -> "user", "content" -> "hi"))
  }

  // ---- writing -------------------------------------------------------------------------------

  test("an assistant message is written the way each api takes its reasoning back") {
    val message = assistant("reasoning_details" -> Json.arr(signed, plain), "reasoning" -> "I think", "reasoning_content" -> "I think")
    def written(input: ReasoningInput, flavor: ChatMessageContentFlavor = ChatMessageContentFlavor.OpenAi): JsObject = message.json(flavor, input).asObject
    val bare = Json.obj("role" -> "assistant", "content" -> "the answer")
    assertEquals(written(ReasoningInput.Skip), bare, "nothing for an api that does not take it back")
    assertEquals(message.json(ChatMessageContentFlavor.OpenAi).asObject, bare, "nor when nothing is said")
    assertEquals(written(ReasoningInput.Content), bare ++ Json.obj("reasoning_content" -> "I think\n\nI also think"))
    assertEquals(written(ReasoningInput.ContentAlways), bare ++ Json.obj("reasoning_content" -> "I think\n\nI also think"))
    assertEquals(written(ReasoningInput.Field), bare ++ Json.obj("reasoning" -> "I think\n\nI also think"))
    assertEquals(written(ReasoningInput.Thinking), bare ++ Json.obj("thinking" -> "I think\n\nI also think"))
    assertEquals(written(ReasoningInput.AnthropicBlocks, ChatMessageContentFlavor.Anthropic), Json.obj("role" -> "assistant", "content" -> Json.arr(
      Json.obj("type" -> "thinking", "thinking" -> "I think", "signature" -> "sig-1"),
      Json.obj("type" -> "text", "text" -> "the answer"),
    )), "Anthropic only gets back what it signed, before the content")
    assertEquals(written(ReasoningInput.MistralChunks), Json.obj("role" -> "assistant", "content" -> Json.arr(
      Json.obj("type" -> "thinking", "thinking" -> Json.arr(Json.obj("type" -> "text", "text" -> "I think"))),
      Json.obj("type" -> "thinking", "thinking" -> Json.arr(Json.obj("type" -> "text", "text" -> "I also think"))),
      Json.obj("type" -> "text", "text" -> "the answer"),
    )), "Mistral gets the text as thinking chunks, without a signature it did not write")
  }

  test("DeepSeek gets reasoning_content on every assistant message, even one without reasoning") {
    assertEquals(assistant().json(ChatMessageContentFlavor.OpenAi, ReasoningInput.ContentAlways).asObject, Json.obj("role" -> "assistant", "content" -> "the answer", "reasoning_content" -> ""))
    assertEquals(assistant().json(ChatMessageContentFlavor.OpenAi, ReasoningInput.Content).asObject, Json.obj("role" -> "assistant", "content" -> "the answer"))
  }

  test("OpenRouter gets the details a model can read: the ones of its family, and plain text but for Claude and Gemini") {
    val details = Seq(signed, plain, summary, encrypted, encryptedDetail("g-sig", Formats.Gemini))
    def kept(model: String): Seq[JsObject] = forOpenRouterModel(details, Some(model))
    assertEquals(kept("anthropic/claude-sonnet-5"), Seq(signed))
    assertEquals(kept("google/gemini-3-pro"), Seq(encryptedDetail("g-sig", Formats.Gemini)))
    assertEquals(kept("openai/gpt-5.6"), Seq(plain, summary, encrypted))
    assertEquals(kept("moonshotai/kimi-k3"), Seq(plain))
    val written = assistant("reasoning_details" -> Json.arr(signed, plain)).json(ChatMessageContentFlavor.OpenAi, ReasoningInput.Details(Some("anthropic/claude-sonnet-5"))).asObject
    assertEquals(withoutIndex(written.select("reasoning_details").as[Seq[JsObject]]), Seq(signed), "and only them")
    assertEquals(written.select("reasoning").asOpt[String], None, "never the text alone, which OpenRouter would give Claude unsigned")
  }

  test("a provider of the OpenAI client takes reasoning back the way its kind and its host say") {
    def input(kind: String, url: String, model: String = "a-model"): ReasoningInput = ReasoningInput.forOpenAiApi(kind, url, Some(model))
    assertEquals(input("openrouter", "https://openrouter.ai/api/v1", "moonshotai/kimi-k3"), ReasoningInput.Details(Some("moonshotai/kimi-k3")))
    assertEquals(input("openai", "https://openrouter.ai/api/v1"), ReasoningInput.Details(Some("a-model")), "a provider of kind openai pointed at OpenRouter")
    assertEquals(input("openai-compatible", "https://openrouter.ai/api/v1"), ReasoningInput.Details(Some("a-model")))
    assertEquals(input("openai", "https://api.openai.com/v1"), ReasoningInput.Skip, "OpenAI refuses the fields it does not know")
    assertEquals(input("deepseek", "https://api.deepseek.com"), ReasoningInput.ContentAlways)
    assertEquals(input("openai-compatible", "https://api.mistral.ai/v1"), ReasoningInput.Skip, "Mistral refuses them too")
    assertEquals(input("openai-compatible", "https://api.groq.com/openai/v1"), ReasoningInput.Field)
    assertEquals(input("gemini", "https://generativelanguage.googleapis.com/v1beta/openai"), ReasoningInput.Skip, "the signatures of Gemini go with its tool calls")
    assertEquals(input("ollama", "http://localhost:11434/v1"), ReasoningInput.Field)
    assertEquals(input("zai", "https://api.z.ai/api/paas/v4"), ReasoningInput.Content)
    assertEquals(input("openai-compatible", "http://vllm.local:8000/v1"), ReasoningInput.Content, "the field vLLM and SGLang read")
    assertEquals(ReasoningInput.fromName("reasoning", None), Some(ReasoningInput.Field))
    assertEquals(ReasoningInput.fromName("none", None), Some(ReasoningInput.Skip))
    assertEquals(ReasoningInput.fromName("whatever", None), None)
  }

  // ---- other formats -------------------------------------------------------------------------

  test("the reasoning of another format goes through the Anthropic format and back") {
    val details = Seq(plain, summary, encrypted, signed, redacted)
    val blocks = toAnthropicBlocks(details, forAnthropicApi = false)
    assertEquals(blocks.map(_.select("type").asString), Seq("thinking", "thinking", "redacted_thinking", "thinking", "redacted_thinking"))
    assertEquals(blocks.head.select("thinking").asString, "I also think", "a caller reads the text of the others")
    assertEquals(blocks(3), Json.obj("type" -> "thinking", "thinking" -> "I think", "signature" -> "sig-1"), "and gets the blocks of Anthropic as they were")
    assertEquals(withoutIndex(fromContentBlocks(blocks)), details, "the gateway reads them all back")
    assertEquals(toAnthropicBlocks(details, forAnthropicApi = true), Seq(
      Json.obj("type" -> "thinking", "thinking" -> "I think", "signature" -> "sig-1"),
      Json.obj("type" -> "redacted_thinking", "data" -> "opaque"),
    ), "the Anthropic api only gets back what it signed")
  }

  test("the reasoning goes through the /responses format and back") {
    var ids = 0
    val newId = () => { ids += 1; s"rs_new_${ids}" }
    val items = toResponsesItems(Seq(summary, encrypted, signed, plain), forResponsesApi = false, newId)
    assertEquals(items.head, Json.obj("type" -> "reasoning", "id" -> "rs_1", "summary" -> Json.arr(Json.obj("type" -> "summary_text", "text" -> "I thought")), "encrypted_content" -> "gAAAA"), "the items of OpenAI as it wrote them")
    assertEquals(items(1).select("content").as[Seq[JsObject]].map(_.select("text").asString), Seq("I think", "I also think"), "the others readable")
    assertEquals(withoutIndex(fromResponsesItems(items)), Seq(summary, encrypted, signed, plain), "and all read back")
    assertEquals(toResponsesItems(Seq(summary, encrypted, signed, plain), forResponsesApi = true, newId), Seq(items.head), "a /responses api only gets back its own items")
    // plain text needs no envelope
    assertEquals(toResponsesItems(Seq(plain), forResponsesApi = false, newId).head.select("encrypted_content").asOpt[String], None)
    // an item replayed by its id alone keeps its id
    assertEquals(withoutIndex(fromResponsesItems(Seq(Json.obj("type" -> "reasoning", "id" -> "rs_2", "summary" -> Json.arr())))), Seq(summaryDetail("", Formats.OpenAiResponses, Some("rs_2"))))
  }

  test("a /responses input keeps the reasoning with the assistant message it led to") {
    val messages = OpenAiResponsesBodyConverter.inputToMessages(Json.obj("input" -> Json.arr(
      Json.obj("role" -> "user", "content" -> "what time is it ?"),
      Json.obj("type" -> "reasoning", "id" -> "rs_1", "summary" -> Json.arr(Json.obj("type" -> "summary_text", "text" -> "I thought")), "encrypted_content" -> "gAAAA"),
      Json.obj("type" -> "function_call", "call_id" -> "call_1", "name" -> "get_time", "arguments" -> "{}"),
      Json.obj("type" -> "function_call", "call_id" -> "call_2", "name" -> "get_date", "arguments" -> "{}"),
      Json.obj("type" -> "function_call_output", "call_id" -> "call_1", "output" -> "10:42"),
      Json.obj("type" -> "function_call_output", "call_id" -> "call_2", "output" -> "today"),
    )))
    assertEquals(messages.map(_.select("role").asString), Seq("user", "assistant", "tool", "tool"), "the calls of one answer make one assistant message")
    assertEquals(messages(1).select("tool_calls").as[Seq[JsObject]].map(_.select("id").asString), Seq("call_1", "call_2"))
    assertEquals(withoutIndex(messages(1).select("reasoning_details").as[Seq[JsObject]]), Seq(summary, encrypted))
    // and a native /responses api gets the item back before the calls
    val input = OpenAiResponsesBodyConverter.promptToInput(ChatPrompt(messages.map(InputChatMessage.fromJson))).value.toSeq.map(_.asObject)
    assertEquals(input.map(i => i.select("type").asOptString.getOrElse("")), Seq("message", "reasoning", "function_call", "function_call", "function_call_output", "function_call_output"))
    assertEquals(input(1), Json.obj("type" -> "reasoning", "id" -> "rs_1", "summary" -> Json.arr(Json.obj("type" -> "summary_text", "text" -> "I thought")), "encrypted_content" -> "gAAAA"))
    // never alone: a reasoning item without what it led to is refused
    val alone = OpenAiResponsesBodyConverter.promptToInput(ChatPrompt(Seq(InputChatMessage.fromJson(Json.obj("role" -> "assistant", "reasoning_details" -> Json.arr(summary)))))).value.toSeq
    assertEquals(alone, Seq.empty[JsValue])
  }

  test("the reasoning of a native /responses answer is kept with its id and its encrypted content") {
    val messages = OpenAiResponsesBodyConverter.outputToChatMessages(Json.obj("output" -> Json.arr(
      Json.obj("type" -> "reasoning", "id" -> "rs_1", "summary" -> Json.arr(Json.obj("type" -> "summary_text", "text" -> "I thought")), "encrypted_content" -> "gAAAA"),
      Json.obj("type" -> "message", "role" -> "assistant", "content" -> Json.arr(Json.obj("type" -> "output_text", "text" -> "hello"))),
    )))
    val message = OutputChatMessage("assistant", "hello", None, messages.head)
    assertEquals(withoutIndex(message.reasoning), Seq(summary, encrypted))
    assertEquals(message.reasoningText, Some("I thought"))
  }

  // ---- answers -------------------------------------------------------------------------------

  test("an answer carries its reasoning the way OpenRouter does, and reasoning_content as the gateway always did") {
    val message = OutputChatMessage("assistant", "the answer", None, Json.obj("message" -> Json.obj("role" -> "assistant", "content" -> "the answer", "reasoning_details" -> Json.arr(signed, redacted))))
    val json = message.json.asObject
    assertEquals(json.select("reasoning").asOpt[String], Some("I think"))
    assertEquals(json.select("reasoning_content").asOpt[String], Some("I think"))
    assertEquals(withoutIndex(json.select("reasoning_details").as[Seq[JsObject]]), Seq(signed, redacted))
    // DeepSeek answers with a plain text
    val deepseek = OutputChatMessage("assistant", "the answer", None, Json.obj("message" -> Json.obj("role" -> "assistant", "content" -> "the answer", "reasoning_content" -> "hmm")))
    assertEquals(deepseek.json.select("reasoning_details").as[Seq[JsObject]], Seq(textDetail("hmm") ++ Json.obj("index" -> 0)))
    assertEquals(OutputChatMessage("assistant", "the answer", None, Json.obj("message" -> Json.obj("role" -> "assistant", "content" -> "the answer"))).json.asObject.keys.contains("reasoning_details"), false, "nothing when there is no reasoning")
  }

  test("a streamed piece of reasoning carries its text and its details") {
    val delta = ChatResponseChunkChoiceDelta(None, reasoning = Some("I"))
    assertEquals(delta.json.select("reasoning").asOpt[String], Some("I"))
    assertEquals(delta.json.select("reasoning_content").asOpt[String], Some("I"))
    assertEquals(delta.json.select("reasoning_details").as[Seq[JsObject]], Seq(textDetail("I") ++ Json.obj("index" -> 0)))
    val signature = ChatResponseChunkChoiceDelta(None, reasoning_details = Seq(textDetail("", Formats.Anthropic, Some("sig-1")) ++ Json.obj("index" -> 0)))
    assertEquals(signature.json.select("reasoning").asOpt[String], None, "a signature has no text")
    assertEquals(ChatResponseChunkChoiceDelta.fromJson(signature.json.asObject), signature, "a cached stream reads it back")
    assertEquals(ChatResponseChunkChoiceDelta(Some("hi")).json.asObject.keys.contains("reasoning_details"), false)
  }

  test("a streamed reasoning is put back together") {
    val acc = new ReasoningAccumulator()
    acc.add(Seq(textDetail("I ", Formats.Anthropic) ++ Json.obj("index" -> 0)))
    acc.add(Seq(textDetail("think", Formats.Anthropic) ++ Json.obj("index" -> 0)))
    acc.add(Seq(textDetail("", Formats.Anthropic, Some("sig-1")) ++ Json.obj("index" -> 0)))
    acc.add(Seq(redacted ++ Json.obj("index" -> 1)))
    acc.add(Seq(redacted ++ Json.obj("index" -> 1)))
    assertEquals(withoutIndex(acc.result), Seq(signed, redacted, redacted), "two encrypted reasonings are two")
    val text = new ReasoningAccumulator()
    text.addText("I ")
    text.addText("think")
    assertEquals(withoutIndex(text.result), Seq(textDetail("I think")))
  }

  test("a guardrail that rewrites the messages rewrites their readable reasoning, and drops a signed one it changes") {
    val message = assistant("reasoning_details" -> Json.arr(textDetail("John said hi", Formats.Anthropic, Some("sig-1")), textDetail("John again"), textDetail("nobody", Formats.Anthropic, Some("sig-2"))))
    val redactedMessage = message.transformContent(_.replace("John", "<PERSON>"))
    assertEquals(withoutIndex(redactedMessage.reasoning), Seq(textDetail("<PERSON> again"), textDetail("nobody", Formats.Anthropic, Some("sig-2"))))
    assertEquals(withoutIndex(message.transformContent(_ => "").reasoning), Seq(textDetail("")), "a signed one is never sent changed")
  }

  // ---- the Anthropic format ------------------------------------------------------------------

  test("a request in the Anthropic format keeps the reasoning, the text and the results of each turn") {
    val messages = AnthropicCompatProxy.messagesToOpenAi(Seq(
      Json.obj("role" -> "user", "content" -> "what time is it ?"),
      Json.obj("role" -> "assistant", "content" -> Json.arr(
        Json.obj("type" -> "thinking", "thinking" -> "I think", "signature" -> "sig-1"),
        Json.obj("type" -> "text", "text" -> "let me check"),
        Json.obj("type" -> "tool_use", "id" -> "toolu_1", "name" -> "get_time", "input" -> Json.obj()),
      )),
      Json.obj("role" -> "user", "content" -> Json.arr(
        Json.obj("type" -> "tool_result", "tool_use_id" -> "toolu_1", "content" -> Json.arr(Json.obj("type" -> "text", "text" -> "10:42"))),
        Json.obj("type" -> "text", "text" -> "thanks"),
      )),
    ))
    assertEquals(messages(1).select("content").asOpt[String], Some("let me check"))
    assertEquals(messages(1).select("tool_calls").as[Seq[JsObject]].map(_.at("function.name").asString), Seq("get_time"))
    assertEquals(withoutIndex(messages(1).select("reasoning_details").as[Seq[JsObject]]), Seq(signed))
    assertEquals(messages(2), Json.obj("role" -> "tool", "tool_call_id" -> "toolu_1", "content" -> "10:42"), "the text of a result made of blocks")
    assertEquals(messages(3), Json.obj("role" -> "user", "content" -> Json.arr(Json.obj("type" -> "text", "text" -> "thanks"))), "what the user said along with it")
  }

  def events(writer: AnthropicStreamWriter, deltas: ChatResponseChunkChoiceDelta*): Seq[JsObject] = {
    val chunks = deltas.map(d => ChatResponseChunk("id", 0L, "model", Seq(ChatResponseChunkChoice(0, d, None))))
    (chunks.flatMap(writer.events) ++ writer.end()).map { bytes =>
      Json.parse(bytes.utf8String.split("\n").find(_.startsWith("data: ")).get.substring(6)).asObject
    }
  }

  test("a streamed answer is written as Anthropic blocks: the thinking, the text, then the tool calls") {
    val all = events(new AnthropicStreamWriter(),
      ChatResponseChunkChoiceDelta(None, reasoning = Some("I think"), reasoning_details = Seq(textDetail("I think", Formats.Anthropic) ++ Json.obj("index" -> 0))),
      ChatResponseChunkChoiceDelta(None, reasoning_details = Seq(textDetail("", Formats.Anthropic, Some("sig-1")) ++ Json.obj("index" -> 0))),
      ChatResponseChunkChoiceDelta(Some("let me check")),
      ChatResponseChunkChoiceDelta(None, tool_calls = Seq(ChatResponseChunkChoiceDeltaToolCall(0, Some("toolu_1"), Some("function"), ChatResponseChunkChoiceDeltaToolCallFunction(Some("get_time"), "")))),
      ChatResponseChunkChoiceDelta(None, tool_calls = Seq(ChatResponseChunkChoiceDeltaToolCall(0, None, None, ChatResponseChunkChoiceDeltaToolCallFunction(None, "{}")))),
    )
    def describe(e: JsObject): String = e.select("type").asString + e.at("content_block.type").asOptString.map(t => s":${t}").getOrElse("") +
      e.at("delta.type").asOptString.map(t => s":${t}").getOrElse("") + e.select("index").asOpt[Int].map(i => s"@${i}").getOrElse("")
    assertEquals(all.map(describe), Seq(
      "content_block_start:thinking@0", "content_block_delta:thinking_delta@0", "content_block_delta:signature_delta@0", "content_block_stop@0",
      "content_block_start:text@1", "content_block_delta:text_delta@1", "content_block_stop@1",
      "content_block_start:tool_use@2", "content_block_delta:input_json_delta@2", "content_block_stop@2",
      "message_delta", "message_stop",
    ))
    assertEquals(all(2).at("delta.signature").asOpt[String], Some("sig-1"))
    assertEquals(all.find(_.select("type").asOptString.contains("message_delta")).flatMap(_.at("delta.stop_reason").asOptString), Some("tool_use"))
  }

  test("the reasoning of another provider streamed in the Anthropic format is signed with what the gateway reads back") {
    val all = events(new AnthropicStreamWriter(), ChatResponseChunkChoiceDelta(None, reasoning = Some("hmm")), ChatResponseChunkChoiceDelta(Some("hello")))
    val signature = all.find(_.at("delta.type").asOptString.contains("signature_delta")).flatMap(_.at("delta.signature").asOptString).get
    val block = Json.obj("type" -> "thinking", "thinking" -> "hmm", "signature" -> signature)
    assertEquals(withoutIndex(fromContentBlocks(Seq(block))), Seq(textDetail("hmm")))
    assertEquals(toAnthropicBlocks(fromContentBlocks(Seq(block)), forAnthropicApi = true), Seq.empty, "and never sends it to Anthropic")
  }

  // ---- the /responses format -----------------------------------------------------------------

  test("a streamed answer is written as /responses items: the reasoning item, then the message") {
    var ids = 0
    val writer = new ResponsesStreamWriter(sequenced = false, logprobs = false, () => 0, prefix => { ids += 1; s"${prefix}_${ids}" })
    val chunks = Seq(
      ChatResponseChunkChoiceDelta(None, reasoning = Some("I thought"), reasoning_details = Seq(summaryDetail("I thought", Formats.OpenAiResponses, Some("rs_1")) ++ Json.obj("index" -> 0))),
      ChatResponseChunkChoiceDelta(None, reasoning_details = Seq(encryptedDetail("gAAAA", Formats.OpenAiResponses, Some("rs_1")) ++ Json.obj("index" -> 0))),
      ChatResponseChunkChoiceDelta(Some("hello")),
    ).map(d => ChatResponseChunk("id", 0L, "model", Seq(ChatResponseChunkChoice(0, d, None))))
    val all = (chunks.flatMap(writer.events) ++ writer.end()).map(b => Json.parse(b.utf8String.split("\n").find(_.startsWith("data: ")).get.substring(6)).asObject)
    assertEquals(all.map(e => e.select("type").asString + e.at("item.type").asOptString.map(t => s":${t}").getOrElse("")), Seq(
      "response.output_item.added:reasoning", "response.reasoning_summary_text.delta", "response.output_item.done:reasoning",
      "response.output_item.added:message", "response.content_part.added", "response.output_text.delta",
      "response.output_text.done", "response.content_part.done", "response.output_item.done:message",
    ))
    assertEquals(writer.output.head, Json.obj("type" -> "reasoning", "id" -> "rs_1", "summary" -> Json.arr(Json.obj("type" -> "summary_text", "text" -> "I thought")), "encrypted_content" -> "gAAAA"), "the item of the provider, with its id")
    assertEquals(writer.output.map(_.select("type").asString), Seq("reasoning", "message"))
  }

  test("a call that makes Claude think is not sent the sampling it refuses then") {
    import com.cloud.apim.otoroshi.extensions.aigateway.providers.AnthropicModels.cleanupSamplingParamsWhenThinking
    val sampled = Json.obj("model" -> "claude-haiku-4-5", "temperature" -> 0, "top_k" -> 5, "top_p" -> 0.5)
    assertEquals(cleanupSamplingParamsWhenThinking(sampled ++ Json.obj("thinking" -> Json.obj("type" -> "enabled", "budget_tokens" -> 1024))),
      Json.obj("model" -> "claude-haiku-4-5", "thinking" -> Json.obj("type" -> "enabled", "budget_tokens" -> 1024)))
    assertEquals(cleanupSamplingParamsWhenThinking(Json.obj("temperature" -> 1, "top_p" -> 0.98, "thinking" -> Json.obj("type" -> "adaptive"))),
      Json.obj("temperature" -> 1, "top_p" -> 0.98, "thinking" -> Json.obj("type" -> "adaptive")), "what it takes stays")
    assertEquals(cleanupSamplingParamsWhenThinking(sampled), sampled, "nothing changes when it does not think")
    assertEquals(cleanupSamplingParamsWhenThinking(sampled ++ Json.obj("thinking" -> Json.obj("type" -> "disabled"))), sampled ++ Json.obj("thinking" -> Json.obj("type" -> "disabled")))
  }

  // ---- tool calls the gateway runs -----------------------------------------------------------

  test("the tool calls the gateway runs go back with the reasoning that led to them") {
    val callResps: Seq[JsValue] = Seq(
      Json.obj("role" -> "assistant", "tool_calls" -> Json.arr(Json.obj("id" -> "call_1"))),
      Json.obj("role" -> "tool", "tool_call_id" -> "call_1", "content" -> "10:42"),
      Json.obj("role" -> "assistant", "tool_calls" -> Json.arr(Json.obj("id" -> "call_2"))),
      Json.obj("role" -> "tool", "tool_call_id" -> "call_2", "content" -> "today"),
    )
    assertEquals(ReasoningToolLoop.openAi(callResps, Json.obj("reasoning_content" -> "I need both")), Seq(
      Json.obj("role" -> "assistant", "tool_calls" -> Json.arr(Json.obj("id" -> "call_1"), Json.obj("id" -> "call_2")), "reasoning_content" -> "I need both"),
      Json.obj("role" -> "tool", "tool_call_id" -> "call_1", "content" -> "10:42"),
      Json.obj("role" -> "tool", "tool_call_id" -> "call_2", "content" -> "today"),
    ), "one assistant message with every call, then the results")
    val anthropic: Seq[JsValue] = Seq(
      Json.obj("role" -> "assistant", "content" -> Json.arr(Json.obj("type" -> "tool_use", "id" -> "toolu_1"))),
      Json.obj("role" -> "user", "content" -> Json.arr(Json.obj("type" -> "tool_result", "tool_use_id" -> "toolu_1"))),
      Json.obj("role" -> "assistant", "content" -> Json.arr(Json.obj("type" -> "tool_use", "id" -> "toolu_2"))),
      Json.obj("role" -> "user", "content" -> Json.arr(Json.obj("type" -> "tool_result", "tool_use_id" -> "toolu_2"))),
    )
    val thinking = Json.obj("type" -> "thinking", "thinking" -> "I think", "signature" -> "sig-1")
    assertEquals(ReasoningToolLoop.anthropic(anthropic, Seq(thinking)), Seq(
      Json.obj("role" -> "assistant", "content" -> Json.arr(thinking, Json.obj("type" -> "tool_use", "id" -> "toolu_1"), Json.obj("type" -> "tool_use", "id" -> "toolu_2"))),
      Json.obj("role" -> "user", "content" -> Json.arr(Json.obj("type" -> "tool_result", "tool_use_id" -> "toolu_1"), Json.obj("type" -> "tool_result", "tool_use_id" -> "toolu_2"))),
    ), "the thinking first, every call in one turn, every result in the next")
  }
}
