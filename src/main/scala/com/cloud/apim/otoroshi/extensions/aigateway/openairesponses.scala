package com.cloud.apim.otoroshi.extensions.aigateway

import org.apache.pekko.util.ByteString
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*

/**
 * Translation between the OpenAI Responses API shape (`/responses`) and the chat/completions shape
 * used everywhere else in the gateway.
 *
 * Two directions:
 *
 *  - responses → chat/completions (`inputToMessages` + `toChatCompletionsBody`): a `/responses`
 *    request is turned into a chat/completions one so a provider without a native `/responses`
 *    endpoint can serve it. Used by the default `ChatClient.response` / `ChatClient.responseStream`
 *    implementations, so every provider gets a correct body — tools included.
 *
 *  - chat → native `/responses` (`promptToInput` + `toResponsesBody`): a provider with a native path
 *    builds its payload from the (possibly decorator-rewritten) `ChatPrompt` and from chat-shaped
 *    provider options. Chat-only parameters are renamed or dropped instead of being shipped as-is.
 */
object OpenAiResponsesBodyConverter {

  // parameters that only exist on /responses: forwarding them to a /chat/completions provider is
  // never right (anthropic & co reject unknown top-level params outright). `max_output_tokens`,
  // `text` and `reasoning` are handled separately because they have a chat equivalent.
  private val responsesOnlyParams: Seq[String] = Seq(
    "input", "instructions", "previous_response_id", "store", "truncation", "metadata",
    "background", "include", "max_tool_calls", "conversation", "safety_identifier",
    "prompt_cache_key", "prompt", "suffix",
  )

  // chat/completions parameters that have no equivalent on /responses. `max_tokens`,
  // `response_format` and `reasoning_effort` are handled separately (they get renamed).
  private val chatOnlyParams: Seq[String] = Seq(
    "messages", "frequency_penalty", "presence_penalty", "logit_bias", "logprobs", "stop", "n",
    "seed", "stream_options", "prompt", "suffix", "modalities", "audio", "web_search_options",
    // ollama-native generation options, which its /v1/responses endpoint does not take
    "top_k", "tfs_z", "repeat_penalty", "repeat_last_n", "num_thread", "num_gpu", "num_gqa",
    "num_ctx", "num_predict", "keep_alive", "mirostat", "mirostat_eta", "mirostat_tau",
  )

  // gateway-internal parameters, consumed by the decorator chain and never sent to a provider
  private val gatewayParams: Seq[String] = Seq("provider", "context", "mock_response")

  private def remove(obj: JsObject, keys: Seq[String]): JsObject = keys.foldLeft(obj)(_ - _)

  // ------------------------------------------------------------------------------------------
  //  responses → chat/completions
  // ------------------------------------------------------------------------------------------

  /**
   * Turns the `input` array (or plain string) of a `/responses` body, plus its `instructions`, into
   * chat/completions messages.
   */
  def inputToMessages(jsonBody: JsValue): Seq[JsObject] = {
    val instructionMessages: Seq[JsObject] = jsonBody.select("instructions").asOptString.map { instructions =>
      Seq(Json.obj("role" -> "system", "content" -> instructions))
    }.getOrElse(Seq.empty)
    val inputMessages: Seq[JsObject] = jsonBody.select("input").asOptString match {
      case Some(text) => Seq(Json.obj("role" -> "user", "content" -> text))
      case None => withReasoning(jsonBody.select("input").asOpt[Seq[JsObject]].getOrElse(Seq.empty))
    }
    instructionMessages ++ inputMessages
  }

  // a reasoning item is the reasoning of the assistant message that follows it (its text, or its tool calls): it
  // becomes the `reasoning_details` of that message, which each provider then reads back its own way. The function
  // calls of one answer make one assistant message, as on /chat/completions
  private def withReasoning(items: Seq[JsObject]): Seq[JsObject] = {
    val result = scala.collection.mutable.ArrayBuffer.empty[JsObject]
    var pending = Seq.empty[JsObject]
    def isAssistant(m: JsObject): Boolean = m.select("role").asOptString.contains("assistant")
    items.foreach { item =>
      val itemType = item.select("type").asOptString
      if (itemType.contains("reasoning")) {
        pending = pending :+ item
      } else {
        inputItemToMessages(item).foreach { message =>
          val calls = message.select("tool_calls").asOpt[Seq[JsValue]].getOrElse(Seq.empty)
          if (itemType.contains("function_call") && pending.isEmpty && result.lastOption.exists(isAssistant)) {
            val last = result.last
            result.update(result.size - 1, last ++ Json.obj("tool_calls" -> JsArray(last.select("tool_calls").asOpt[Seq[JsValue]].getOrElse(Seq.empty) ++ calls)))
          } else if (pending.nonEmpty && isAssistant(message)) {
            result += (message ++ Json.obj("reasoning_details" -> JsArray(Reasoning.indexed(Reasoning.fromResponsesItems(pending)))))
            pending = Seq.empty
          } else {
            result += message
          }
        }
      }
    }
    result.toSeq
  }

  private def inputItemToMessages(item: JsObject): Seq[JsObject] = {
    // an item without an explicit type but with a role is a message (openai accepts both shapes)
    val itemType = item.select("type").asOptString.getOrElse(if (item.select("role").asOptString.isDefined) "message" else "unknown")
    itemType match {
      case "message" =>
        val role = item.select("role").asOptString.getOrElse("user") match {
          case "developer" => "system"
          case other => other
        }
        val content: JsValue = item.select("content").asOptString match {
          case Some(text) => JsString(text)
          case None => JsArray(item.select("content").asOpt[Seq[JsObject]].getOrElse(Seq.empty).flatMap(contentPartToChatPart))
        }
        Seq(Json.obj("role" -> role, "content" -> content))
      case "function_call_output" =>
        val toolCallId = item.select("call_id").asOptString.getOrElse("")
        val output = item.select("output").asOptString.getOrElse("")
        Seq(Json.obj("role" -> "tool", "tool_call_id" -> toolCallId, "content" -> output))
      case "function_call" =>
        val callId = item.select("call_id").asOptString.orElse(item.select("id").asOptString).getOrElse("")
        val fnName = item.select("name").asOptString.getOrElse("")
        val arguments = item.select("arguments").asOptString.getOrElse("{}")
        Seq(Json.obj(
          "role" -> "assistant",
          "tool_calls" -> Json.arr(Json.obj(
            "id" -> callId,
            "type" -> "function",
            "function" -> Json.obj("name" -> fnName, "arguments" -> arguments)
          ))
        ))
      // reasoning items are provider state, they carry nothing a chat/completions provider can use
      case "reasoning" => Seq.empty
      case _ => Seq.empty
    }
  }

  private def contentPartToChatPart(contentItem: JsObject): Option[JsObject] = {
    contentItem.select("type").asOptString match {
      case Some("input_text") | Some("output_text") | Some("text") =>
        val text: String = contentItem.select("text").asOptString.getOrElse("")
        Json.obj("type" -> "text", "text" -> text).some
      case Some("input_image") =>
        val image_url = contentItem.select("image_url").asOptString.orElse(contentItem.at("image_url.url").asOptString)
        val detail = contentItem.select("detail").asOptString
        Json.obj(
          "type" -> "image_url",
          "image_url" -> Json.obj("url" -> image_url, "details" -> detail)
        ).some
      case Some("input_audio") =>
        // the payload sits either inline ({data, format}) or under an `input_audio` object
        val data = contentItem.select("data").asOptString.orElse(contentItem.at("input_audio.data").asOptString)
        val format = contentItem.select("format").asOptString.orElse(contentItem.at("input_audio.format").asOptString)
        Json.obj(
          "type" -> "input_audio",
          "input_audio" -> Json.obj("data" -> data, "format" -> format)
        ).some
      case Some("input_file") =>
        Json.obj(
          "type" -> "file",
          "file" -> Json.obj()
            .applyOnWithOpt(contentItem.select("filename").asOptString) { case (obj, v) => obj ++ Json.obj("filename" -> v) }
            .applyOnWithOpt(contentItem.select("file_data").asOptString) { case (obj, v) => obj ++ Json.obj("file_data" -> v) }
            .applyOnWithOpt(contentItem.select("file_url").asOptString) { case (obj, v) => obj ++ Json.obj("file_url" -> v) }
            .applyOnWithOpt(contentItem.select("file_id").asOptString) { case (obj, v) => obj ++ Json.obj("file_id" -> v) }
        ).some
      case Some("input_video") =>
        val data = contentItem.select("data").asOptString.orElse(contentItem.at("input_video.data").asOptString)
        val format = contentItem.select("format").asOptString.orElse(contentItem.at("input_video.format").asOptString)
        Json.obj(
          "type" -> "input_video",
          "input_video" -> Json.obj("data" -> data, "format" -> format)
        ).some
      case _ => None
    }
  }

  /** flat `/responses` tools (`{type, name, parameters}`) → chat tools (`{type, function: {…}}`) */
  def toolsToChatTools(tools: Seq[JsObject]): JsArray = {
    JsArray(tools.map { tool =>
      val toolType: String = tool.select("type").asOptString.getOrElse("function")
      if (toolType == "function" && tool.select("function").asOpt[JsObject].isEmpty) {
        val name: String = tool.select("name").asOptString.getOrElse("")
        val description: String = tool.select("description").asOptString.getOrElse("")
        val parameters: JsObject = tool.select("parameters").asOpt[JsObject].getOrElse(Json.obj())
        val fn: JsObject = Json.obj(
          "name" -> name,
          "description" -> description,
          "parameters" -> parameters,
        ).applyOnWithOpt(tool.select("strict").asOptBoolean) { case (o, s) => o ++ Json.obj("strict" -> s) }
        Json.obj("type" -> "function", "function" -> fn)
      } else {
        // already in chat shape, or a server-side tool we cannot express as a chat function
        tool
      }
    })
  }

  /** `text.format` (structured outputs on /responses) → chat `response_format` */
  private def textFormatToResponseFormat(text: JsValue): Option[JsObject] = {
    text.select("format").asOpt[JsObject].map { format =>
      format.select("type").asOptString match {
        case Some("json_schema") =>
          val name: String = format.select("name").asOptString.getOrElse("response")
          val schema: JsObject = format.select("schema").asOpt[JsObject].getOrElse(Json.obj())
          val jsonSchema: JsObject = Json.obj("name" -> name, "schema" -> schema)
            .applyOnWithOpt(format.select("strict").asOptBoolean) { case (o, s) => o ++ Json.obj("strict" -> s) }
          Json.obj("type" -> "json_schema", "json_schema" -> jsonSchema)
        case Some(other) => Json.obj("type" -> other)
        case None => Json.obj("type" -> "text")
      }
    }
  }

  /**
   * Turns a `/responses` body into a chat/completions one. Messages are NOT part of the result:
   * `prompt` is the source of truth for them (see `ChatClient.response`), this only deals with
   * parameters.
   */
  def toChatCompletionsBody(responsesBody: JsValue): JsObject = {
    val body = responsesBody.asObject
    // `tools` is removed then re-added only when non-empty: some providers reject an empty array
    remove(body, responsesOnlyParams ++ Seq("text", "reasoning", "max_output_tokens", "stream", "tools"))
      .applyOnWithOpt(body.select("max_output_tokens").asOpt[JsValue]) {
        case (obj, maxTokens) => obj ++ Json.obj("max_tokens" -> maxTokens)
      }
      .applyOnWithOpt(body.select("text").asOpt[JsValue].flatMap(textFormatToResponseFormat)) {
        case (obj, responseFormat) => obj ++ Json.obj("response_format" -> responseFormat)
      }
      .applyOnWithOpt(body.at("reasoning.effort").asOptString) {
        case (obj, effort) => obj ++ Json.obj("reasoning_effort" -> effort)
      }
      .applyOnWithOpt(body.select("tools").asOpt[Seq[JsObject]].filter(_.nonEmpty)) {
        case (obj, tools) => obj ++ Json.obj("tools" -> toolsToChatTools(tools))
      }
  }

  // ------------------------------------------------------------------------------------------
  //  chat → native /responses
  // ------------------------------------------------------------------------------------------

  /** chat tools (`{type, function: {…}}`) → flat `/responses` tools (`{type, name, parameters}`) */
  def chatToolsToResponsesTools(tools: Seq[JsObject]): JsArray = {
    JsArray(tools.map { tool =>
      tool.select("function").asOpt[JsObject] match {
        case Some(fn) =>
          val toolType: String = tool.select("type").asOptString.getOrElse("function")
          val name: String = fn.select("name").asOptString.getOrElse("")
          val parameters: JsObject = fn.select("parameters").asOpt[JsObject].getOrElse(Json.obj())
          Json.obj("type" -> toolType, "name" -> name, "parameters" -> parameters)
            .applyOnWithOpt(fn.select("description").asOptString) { case (o, d) => o ++ Json.obj("description" -> d) }
            .applyOnWithOpt(fn.select("strict").asOptBoolean) { case (o, s) => o ++ Json.obj("strict" -> s) }
        case None => tool
      }
    })
  }

  /** normalizes flat `/responses` tools so they can be echoed back in a response envelope */
  def normalizeResponsesTools(tools: Seq[JsObject]): Seq[JsObject] = {
    tools.map { tool =>
      val toolType: String = tool.select("type").asOptString.getOrElse("function")
      val parameters: JsObject = tool.select("parameters").asOpt[JsObject].orElse(tool.at("function.parameters").asOpt[JsObject]).getOrElse(Json.obj())
      Json.obj(
        "type" -> toolType,
        "name" -> tool.select("name").asOptString.orElse(tool.at("function.name").asOptString),
        "description" -> tool.select("description").asOptString.orElse(tool.at("function.description").asOptString),
        "parameters" -> parameters,
        "strict" -> tool.select("strict").asOptBoolean.orElse(tool.at("function.strict").asOptBoolean),
      )
    }
  }

  /**
   * Serializes a `ChatPrompt` into the `input` array of a native `/responses` call. This is what
   * makes decorator prompt rewriting (prompt contexts, persistent memory, guardrail transforms)
   * effective on the native path: the caller's own `input` array is never forwarded.
   */
  def promptToInput(prompt: ChatPrompt): JsArray = {
    JsArray(prompt.messages.flatMap(messageToInputItems))
  }

  /** same, for raw chat messages (tool-call results come back in that shape) */
  def chatMessagesToInput(messages: Seq[JsObject]): JsArray = {
    JsArray(messages.flatMap(m => InputChatMessage.fromJsonSafe(m).toSeq.flatMap(messageToInputItems)))
  }

  private def messageToInputItems(message: InputChatMessage): Seq[JsObject] = {
    val role = message.role match {
      case "developer" => "system"
      case other => other
    }
    val toolCallItems: Seq[JsObject] = message.tool_calls.getOrElse(Seq.empty).map { tc =>
      val callId: String = tc.select("id").asOptString.getOrElse("")
      val name: String = tc.at("function.name").asOptString.orElse(tc.select("name").asOptString).getOrElse("")
      val arguments: String = tc.at("function.arguments").asOptString.orElse(tc.select("arguments").asOptString).getOrElse("{}")
      Json.obj(
        "type" -> "function_call",
        "call_id" -> callId,
        "name" -> name,
        "arguments" -> arguments,
      )
    }
    message.tool_call_id match {
      case Some(callId) => Seq(Json.obj(
        "type" -> "function_call_output",
        "call_id" -> callId,
        "output" -> message.wholeTextContent,
      ))
      case None =>
        val contentParts: Seq[JsValue] = message.contentParts.map(_.json(ChatMessageContentFlavor.OpenAiResponses)).map {
          // an assistant message replayed as input carries `output_text`, not `input_text`
          case part if role == "assistant" && part.select("type").asOptString.contains("input_text") =>
            part.asObject ++ Json.obj("type" -> "output_text")
          case part => part
        }
        val messageItems: Seq[JsObject] = if (contentParts.isEmpty) Seq.empty else Seq(Json.obj(
          "type" -> "message",
          "role" -> role,
          "content" -> JsArray(contentParts),
        ))
        // the reasoning items this api wrote come back before what they led to, never alone: a reasoning item
        // without the item it led to is refused
        val reasoningItems: Seq[JsObject] = if (messageItems.isEmpty && toolCallItems.isEmpty) Seq.empty
          else Reasoning.toResponsesItems(message.reasoning, forResponsesApi = true, () => "")
        reasoningItems ++ messageItems ++ toolCallItems
    }
  }

  /**
   * Turns a chat-shaped body (provider options merged with the request) into native `/responses`
   * parameters: chat-only params are dropped, the ones with an equivalent are renamed.
   */
  def toResponsesBody(chatBody: JsValue): JsObject = {
    val body = chatBody.asObject
    remove(body, chatOnlyParams ++ gatewayParams ++ Seq("max_tokens", "max_completion_tokens", "response_format", "reasoning_effort", "stream", "input", "instructions", "tools"))
      // `num_predict` is the ollama spelling of the same thing
      .applyOnWithOpt(body.select("max_output_tokens").asOpt[JsValue].orElse(body.select("max_completion_tokens").asOpt[JsValue]).orElse(body.select("max_tokens").asOpt[JsValue]).orElse(body.select("num_predict").asOpt[JsValue])) {
        case (obj, maxTokens) => obj ++ Json.obj("max_output_tokens" -> maxTokens)
      }
      .applyOnWithOpt(responseFormatToTextFormat(body.select("response_format").asOpt[JsValue])) {
        case (obj, text) => if (obj.select("text").asOpt[JsObject].isDefined) obj else obj ++ Json.obj("text" -> text)
      }
      .applyOnWithOpt(body.select("reasoning_effort").asOptString) {
        case (obj, effort) => if (obj.select("reasoning").asOpt[JsObject].isDefined) obj else obj ++ Json.obj("reasoning" -> Json.obj("effort" -> effort))
      }
      .applyOnWithOpt(body.select("tools").asOpt[Seq[JsObject]].filter(_.nonEmpty)) {
        case (obj, tools) => obj ++ Json.obj("tools" -> chatToolsToResponsesTools(tools))
      }
  }

  /** chat `response_format` → `text.format`. Providers take it either as an object or as a bare type */
  private def responseFormatToTextFormat(responseFormat: Option[JsValue]): Option[JsObject] = responseFormat match {
    case Some(JsString(typ)) => Json.obj("format" -> Json.obj("type" -> typ)).some
    case Some(obj: JsObject) => responseFormatObjectToTextFormat(obj)
    case _ => None
  }

  private def responseFormatObjectToTextFormat(responseFormat: JsObject): Option[JsObject] = {
    responseFormat.select("type").asOptString.map {
      case "json_schema" =>
        val jsonSchema: JsObject = responseFormat.select("json_schema").asOpt[JsObject].getOrElse(Json.obj())
        val name: String = jsonSchema.select("name").asOptString.getOrElse("response")
        val schema: JsObject = jsonSchema.select("schema").asOpt[JsObject].getOrElse(Json.obj())
        val format: JsObject = Json.obj("type" -> "json_schema", "name" -> name, "schema" -> schema)
          .applyOnWithOpt(jsonSchema.select("strict").asOptBoolean) { case (o, s) => o ++ Json.obj("strict" -> s) }
        Json.obj("format" -> format)
      case other => Json.obj("format" -> Json.obj("type" -> other))
    }
  }

  // ------------------------------------------------------------------------------------------
  //  native /responses output → gateway model
  // ------------------------------------------------------------------------------------------

  /**
   * Maps the `output` array of a native `/responses` payload back into chat-shaped assistant
   * messages: `message` items become text, `function_call` items become `tool_calls`, and reasoning
   * items / annotations are kept in the raw message so nothing is lost downstream.
   */
  def outputToChatMessages(responsePayload: JsValue): Seq[JsObject] = {
    val output = responsePayload.select("output").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
    val texts = output.filter(_.select("type").asOptString.contains("message")).flatMap { item =>
      item.select("content").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
        .filter(p => p.select("type").asOptString.contains("output_text"))
        .flatMap(_.select("text").asOptString)
    }
    val annotations = output.filter(_.select("type").asOptString.contains("message")).flatMap { item =>
      item.select("content").asOpt[Seq[JsObject]].getOrElse(Seq.empty).flatMap(p => p.select("annotations").asOpt[Seq[JsObject]].getOrElse(Seq.empty))
    }
    val refusals = output.filter(_.select("type").asOptString.contains("message")).flatMap { item =>
      item.select("content").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
        .filter(p => p.select("type").asOptString.contains("refusal"))
        .flatMap(_.select("refusal").asOptString)
    }
    val toolCalls = output.filter(_.select("type").asOptString.contains("function_call")).map { item =>
      val callId: String = item.select("call_id").asOptString.orElse(item.select("id").asOptString).getOrElse("")
      val name: String = item.select("name").asOptString.getOrElse("")
      val arguments: String = item.select("arguments").asOptString.getOrElse("{}")
      Json.obj(
        "id" -> callId,
        "type" -> "function",
        "function" -> Json.obj("name" -> name, "arguments" -> arguments)
      )
    }
    // the reasoning items (summary, encrypted content, id) are what the api needs back to keep on thinking
    val reasoning = Reasoning.indexed(Reasoning.fromResponsesItems(output))
    val message = Json.obj(
      "role" -> "assistant",
      "content" -> texts.mkString(""),
    ).applyOnIf(toolCalls.nonEmpty) { obj => obj ++ Json.obj("tool_calls" -> JsArray(toolCalls)) }
      .applyOnIf(annotations.nonEmpty) { obj => obj ++ Json.obj("annotations" -> JsArray(annotations)) }
      .applyOnIf(refusals.nonEmpty) { obj => obj ++ Json.obj("refusal" -> refusals.mkString("")) }
      .applyOnIf(reasoning.nonEmpty) { obj => obj ++ Json.obj("reasoning_details" -> JsArray(reasoning)) }
    Seq(Json.obj(
      "index" -> 0,
      "finish_reason" -> (if (toolCalls.nonEmpty) "tool_calls" else "stop"),
      "message" -> message,
    ))
  }

  /** gateway usage → `/responses` usage object */
  def usageJson(usage: ChatResponseMetadataUsage): JsObject = Json.obj(
    "input_tokens" -> usage.promptTokens,
    "output_tokens" -> usage.generationTokens,
    "total_tokens" -> usage.totalTokens,
    "input_tokens_details" -> Json.obj("cached_tokens" -> 0),
    "output_tokens_details" -> Json.obj("reasoning_tokens" -> usage.reasoningTokens),
  )

  /** `/responses` usage (`input_tokens`/`output_tokens`) → gateway usage */
  def usageFromResponsePayload(responsePayload: JsValue): Option[ChatResponseMetadataUsage] = {
    // `response.created` / `response.in_progress` carry `"usage": null`, which reads as
    // Some(JsNull) — only a real object is usage
    responsePayload.select("usage").asOpt[JsObject].map { usage =>
      ChatResponseMetadataUsage(
        promptTokens = usage.select("input_tokens").asOptLong.getOrElse(0L),
        generationTokens = usage.select("output_tokens").asOptLong.getOrElse(0L),
        reasoningTokens = usage.at("output_tokens_details.reasoning_tokens").asOptLong.getOrElse(0L),
      )
    }
  }
}

/**
 * Accumulates the chunks of a streamed answer into what a `/responses` front-end has to emit at the
 * end of the stream: the whole text, the tool calls (whose id, name and arguments arrive in pieces)
 * and the usage carried by the final chunk. Shared by the `/responses` front-ends so tool calls and
 * token counts are reported the same way on both.
 */
class ResponsesStreamAccumulator {

  private val text = new StringBuilder()
  private val reasoning = new StringBuilder()
  private val reasoningDetails = new ReasoningAccumulator()
  // index → (call id, function name, arguments so far)
  private val toolCalls = scala.collection.mutable.LinkedHashMap.empty[Long, (Option[String], Option[String], StringBuilder)]
  private var usageOpt: Option[ChatResponseMetadataUsage] = None

  def accumulate(chunk: ChatResponseChunk): Unit = {
    chunk.choices.foreach { choice =>
      choice.delta.content.foreach(text.append)
      choice.delta.reasoning.foreach(reasoning.append)
      reasoningDetails.add(choice.delta.reasoningDetailsOrText)
      choice.delta.tool_calls.foreach { tc =>
        val (id, name, args) = toolCalls.getOrElseUpdate(tc.index, (None, None, new StringBuilder()))
        args.append(tc.function.arguments)
        toolCalls.update(tc.index, (tc.id.orElse(id), tc.function.nameOpt.orElse(name), args))
      }
    }
    // only a chunk that actually carries usage counts (the earlier ones have none)
    chunk.usage.foreach(u => usageOpt = Some(u))
  }

  def wholeText: String = text.toString()
  def wholeReasoning: String = reasoning.toString()
  def reasoning_details: Seq[JsObject] = reasoningDetails.result
  def usage: ChatResponseMetadataUsage = usageOpt.getOrElse(ChatResponseMetadataUsage.empty)
  def usageJson: JsObject = OpenAiResponsesBodyConverter.usageJson(usage)
  def hasToolCalls: Boolean = toolCalls.nonEmpty

  /** the answer as a chat assistant message: its text, its tool calls and its reasoning */
  def chatMessage: JsObject = {
    val calls = toolCalls.toSeq.map { case (_, (id, name, args)) =>
      Json.obj("id" -> id.getOrElse(""), "type" -> "function", "function" -> Json.obj("name" -> name.getOrElse(""), "arguments" -> args.toString()))
    }
    val reasoning = reasoning_details
    Json.obj("role" -> "assistant", "content" -> text.toString())
      .applyOnIf(calls.nonEmpty)(_ ++ Json.obj("tool_calls" -> JsArray(calls)))
      .applyOnIf(reasoning.nonEmpty)(_ ++ Json.obj("reasoning_details" -> JsArray(reasoning)))
  }

  /** the accumulated tool calls as `function_call` output items */
  def functionCallItems(idFor: Int => String): Seq[JsObject] = {
    toolCalls.toSeq.zipWithIndex.map { case ((_, (id, name, args)), idx) =>
      val itemId: String = idFor(idx)
      val callId: String = id.getOrElse(itemId)
      val fnName: String = name.getOrElse("")
      Json.obj(
        "type" -> "function_call",
        "id" -> itemId,
        "call_id" -> callId,
        "name" -> fnName,
        "arguments" -> args.toString(),
        "status" -> "completed",
      )
    }
  }
}

/**
 * Writes a streamed answer as the output items of a `/responses` stream, in the order the model produced them: a
 * `reasoning` item while it thinks, a `message` item while it writes, then its `function_call` items. Each item is
 * announced (`response.output_item.added`) when it starts and completed (`response.output_item.done`) before the
 * next one starts, which is how a caller keeps the reasoning with what it led to. Shared by the `/responses`
 * front-ends; `sequenced` numbers the events (`sequence_number`), `logprobs` adds the empty `logprobs` of the Open
 * Responses format. The chunks come from one stream only: the state needs no synchronization.
 */
class ResponsesStreamWriter(sequenced: Boolean, logprobs: Boolean, nextSequence: () => Int, newId: String => String) {

  val acc = new ResponsesStreamAccumulator()

  private sealed trait Item
  private case class ReasoningItem(id: String, index: Int, details: ReasoningAccumulator) extends Item
  private case class MessageItem(id: String, index: Int, text: StringBuilder) extends Item

  private var current: Option[Item] = None
  private var nextIndex = 0
  private val done = scala.collection.mutable.ArrayBuffer.empty[JsObject]

  private def sse(eventType: String, data: JsObject): ByteString = {
    val payload = Json.obj("type" -> eventType) ++ data ++ (if (sequenced) Json.obj("sequence_number" -> nextSequence()) else Json.obj())
    s"event: ${eventType}\ndata: ${payload.stringify}\n\n".byteString
  }

  private def withLogprobs(obj: JsObject): JsObject = if (logprobs) obj ++ Json.obj("logprobs" -> Json.arr()) else obj

  private def textPart(text: String): JsObject = withLogprobs(Json.obj("type" -> "output_text", "text" -> text, "annotations" -> Json.arr()))

  private def messageItem(id: String, status: String, text: Option[String]): JsObject = Json.obj(
    "type" -> "message",
    "id" -> id,
    "status" -> status,
    "role" -> "assistant",
    "content" -> JsArray(text.map(t => textPart(t)).toSeq),
  )

  private def close(): Seq[ByteString] = {
    val events = current.toSeq.flatMap {
      case ReasoningItem(id, index, details) =>
        val items = Reasoning.toResponsesItems(details.result, forResponsesApi = false, () => id)
        // the item announced keeps its id: the id of the provider when it gave one
        val completed = items.headOption.map(_ ++ Json.obj("id" -> id)).toSeq ++ items.drop(1)
        done ++= completed
        completed.map(item => sse("response.output_item.done", Json.obj("output_index" -> index, "item" -> item)))
      case MessageItem(id, index, text) =>
        val item = messageItem(id, "completed", text.toString().some)
        done += item
        Seq(
          sse("response.output_text.done", withLogprobs(Json.obj("item_id" -> id, "output_index" -> index, "content_index" -> 0, "text" -> text.toString()))),
          sse("response.content_part.done", Json.obj("item_id" -> id, "output_index" -> index, "content_index" -> 0, "part" -> textPart(text.toString()))),
          sse("response.output_item.done", Json.obj("output_index" -> index, "item" -> item)),
        )
    }
    current = None
    events
  }

  private def openReasoning(id: Option[String]): Seq[ByteString] = {
    val item = ReasoningItem(id.getOrElse(newId("rs")), nextIndex, new ReasoningAccumulator())
    nextIndex = nextIndex + 1
    current = Some(item)
    Seq(sse("response.output_item.added", Json.obj("output_index" -> item.index, "item" -> Json.obj("type" -> "reasoning", "id" -> item.id, "summary" -> Json.arr()))))
  }

  private def openMessage(): Seq[ByteString] = {
    val item = MessageItem(newId("msg"), nextIndex, new StringBuilder())
    nextIndex = nextIndex + 1
    current = Some(item)
    Seq(
      sse("response.output_item.added", Json.obj("output_index" -> item.index, "item" -> messageItem(item.id, "in_progress", None))),
      sse("response.content_part.added", Json.obj("item_id" -> item.id, "output_index" -> item.index, "content_index" -> 0, "part" -> textPart(""))),
    )
  }

  def events(chunk: ChatResponseChunk): Seq[ByteString] = {
    acc.accumulate(chunk)
    chunk.choices.headOption.toSeq.flatMap { choice =>
      val reasoning = choice.delta.reasoningDetailsOrText.flatMap { detail =>
        // a reasoning item of the provider is a new item when its id changes
        val opening = current match {
          case Some(ReasoningItem(id, _, _)) if Reasoning.idOf(detail).forall(_ == id) => Seq.empty
          case Some(r: ReasoningItem) if Reasoning.formatOf(detail) != Reasoning.Formats.OpenAiResponses => Seq.empty
          case _ => close() ++ openReasoning(Reasoning.idOf(detail).filter(_ => Reasoning.formatOf(detail) == Reasoning.Formats.OpenAiResponses))
        }
        val item = current.collect { case r: ReasoningItem => r }.get
        item.details.add(Seq(detail))
        val text = Reasoning.readableOf(detail).filter(_.nonEmpty).map { text =>
          if (Reasoning.isSummary(detail)) sse("response.reasoning_summary_text.delta", Json.obj("item_id" -> item.id, "output_index" -> item.index, "summary_index" -> 0, "delta" -> text))
          else sse("response.reasoning_text.delta", Json.obj("item_id" -> item.id, "output_index" -> item.index, "content_index" -> 0, "delta" -> text))
        }.toSeq
        opening ++ text
      }
      val text = choice.delta.content.filter(_.nonEmpty).toSeq.flatMap { text =>
        val opening = current match {
          case Some(_: MessageItem) => Seq.empty
          case _ => close() ++ openMessage()
        }
        val item = current.collect { case m: MessageItem => m }.get
        item.text.append(text)
        opening :+ sse("response.output_text.delta", withLogprobs(Json.obj("item_id" -> item.id, "output_index" -> item.index, "content_index" -> 0, "delta" -> text)))
      }
      reasoning ++ text
    }
  }

  /** the events that end the output, once the answer is over: the last item, then the function calls */
  def end(): Seq[ByteString] = {
    // an answer says something, or calls a tool
    val empty = if (nextIndex == 0 && !acc.hasToolCalls) openMessage() else Seq.empty
    val closing = empty ++ close()
    val calls = acc.functionCallItems(_ => newId("fc")).map { item =>
      val index = nextIndex
      nextIndex = nextIndex + 1
      done += item
      sse("response.output_item.done", Json.obj("output_index" -> index, "item" -> item))
    }
    closing ++ calls
  }

  /** every output item, in order, once `end` was called */
  def output: Seq[JsObject] = done.toSeq
}
