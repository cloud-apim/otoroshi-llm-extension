package com.cloud.apim.otoroshi.extensions.aigateway.providers

import akka.stream.scaladsl.{Framing, Source}
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway._
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{A2ASupport, GenericApiResponseChoiceMessageToolCall, LlmFunctions}
import io.azam.ulidj.ULID
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.libs.ws.WSResponse

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}

// The tool related options every provider client shares, so the native /responses path can build its
// tool list without knowing the concrete options class.
case class NativeResponsesToolsOptions(
  wasmToolsNoInline: Seq[String] = Seq.empty,
  wasmToolsInline: Seq[String] = Seq.empty,
  mcpConnectors: Seq[String] = Seq.empty,
  a2aConnectors: Seq[String] = Seq.empty,
  searchEngines: Seq[String] = Seq.empty,
  mcpIncludeFunctions: Seq[String] = Seq.empty,
  mcpExcludeFunctions: Seq[String] = Seq.empty,
  maxFunctionCalls: Int = 10,
) {
  def hasConfiguredTools: Boolean = wasmToolsNoInline.nonEmpty || wasmToolsInline.nonEmpty ||
    mcpConnectors.nonEmpty || a2aConnectors.nonEmpty || searchEngines.nonEmpty
}

/**
 * Native OpenAI Responses API support, shared by every provider exposing a `/responses` endpoint
 * (OpenAI, Azure OpenAI, Groq, x.ai, Ollama). The payload is built from the `ChatPrompt` — never
 * from the caller's own `input` array — so prompt contexts, persistent memory and guardrail
 * rewrites are applied; chat-only provider options are renamed or dropped; `function_call` output
 * items drive the tool loop; and the `response.*` SSE events are mapped to `ChatResponseChunk`s.
 *
 * A client mixes it in and provides the few provider-specific bits below. When `responsesEnabled`
 * is false, `response` / `responseStream` fall back to the default degradation to
 * `/chat/completions` implemented by `ChatClient`.
 */
trait NativeResponsesSupport extends ChatClient {

  // ---- provider specific ------------------------------------------------------------------

  /** whether the native endpoint should be used at all (the `responses` provider option) */
  protected def responsesEnabled: Boolean
  /** name used in logs and in the analytics slug */
  protected def responsesProviderKind: String
  /** id of the provider entity */
  protected def responsesProviderId: String
  /** the provider options, in their chat/completions shape */
  protected def responsesChatOptions: JsObject
  protected def responsesAllowConfigOverride: Boolean
  protected def responsesToolsOptions: NativeResponsesToolsOptions
  protected def responsesSupportsTools: Boolean
  /** parameters the provider documents as unsupported on its `/responses` endpoint */
  protected def responsesUnsupportedParams: Seq[String] = Seq.empty
  /** POST the payload to the provider's `/responses` endpoint */
  protected def responsesRawCall(body: JsValue)(implicit ec: ExecutionContext, env: Env): Future[WSResponse]
  /** same, as a stream of `text/event-stream` bytes */
  protected def responsesRawStream(body: JsValue)(implicit ec: ExecutionContext, env: Env): Future[WSResponse]

  override def supportsResponses: Boolean = responsesEnabled

  // ---- payload ----------------------------------------------------------------------------

  // the payload to POST, the model it targets, and whether the gateway injected its own tools —
  // only then does it execute the tool calls itself. Tools declared by the caller are its own
  // business: their calls are returned to it, exactly like on the chat/completions path.
  private def payload(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): (JsObject, String, Boolean) = {
    val body = originalBody.asObject - "messages" - "provider" - "input" - "instructions"
    val merged = if (responsesAllowConfigOverride) responsesChatOptions.deepMerge(body) else responsesChatOptions
    val params = responsesUnsupportedParams.foldLeft(OpenAiResponsesBodyConverter.toResponsesBody(merged))(_ - _)
    val finalModel = params.select("model").asOptString.orElse(computeModel(params)).getOrElse("--")
    val toolsOptions = responsesToolsOptions
    val hasToolsInRequest = body.select("tools").asOpt[JsArray].exists(_.value.nonEmpty)
    val gatewayTools = !hasToolsInRequest && responsesSupportsTools && toolsOptions.hasConfiguredTools
    val withTools = if (gatewayTools) {
      attrs.put(A2ASupport.A2AConnectorsKey -> toolsOptions.a2aConnectors)
      val tools = LlmFunctions.toolsWithInline(toolsOptions.wasmToolsNoInline, toolsOptions.wasmToolsInline, toolsOptions.mcpConnectors, toolsOptions.mcpIncludeFunctions, toolsOptions.mcpExcludeFunctions, attrs, toolsOptions.searchEngines)
      params ++ Json.obj("tools" -> OpenAiResponsesBodyConverter.chatToolsToResponsesTools(tools.select("tools").asOpt[Seq[JsObject]].getOrElse(Seq.empty)))
    } else {
      params
    }
    (withTools ++ Json.obj("input" -> OpenAiResponsesBodyConverter.promptToInput(prompt)), finalModel, gatewayTools)
  }

  // publishes usage in the attrs so the decorators (costs, budgets, auditing, metrics, stream
  // usage) pick it up, exactly like the chat/completions path does
  private def registerUsage(usage: ChatResponseMetadata, finalModel: String, duration: Long, attrs: TypedMap): Unit = {
    val slug = Json.obj(
      "provider_kind" -> responsesProviderKind,
      "provider" -> responsesProviderId,
      "duration" -> duration,
      "model" -> finalModel.json,
      "rate_limit" -> usage.rateLimit.json,
      "usage" -> usage.usage.json
    ).applyOnWithOpt(usage.cache) {
      case (obj, cache) => obj ++ Json.obj("cache" -> cache.json)
    }
    attrs.update(ChatClient.ApiUsageKey -> usage)
    attrs.update(otoroshi.plugins.Keys.ExtraAnalyticsDataKey) {
      case Some(obj@JsObject(_)) => {
        val arr = obj.select("ai").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
        obj ++ Json.obj("ai" -> (arr ++ Seq(slug)))
      }
      case Some(other) => other
      case None => Json.obj("ai" -> Seq(slug))
    }
  }

  private def metadataFrom(headers: Map[String, String], usage: ChatResponseMetadataUsage): ChatResponseMetadata = {
    ChatResponseMetadata(
      ChatResponseMetadataRateLimit(
        requestsLimit = headers.getIgnoreCase("x-ratelimit-limit-requests").map(_.toLong).getOrElse(-1L),
        requestsRemaining = headers.getIgnoreCase("x-ratelimit-remaining-requests").map(_.toLong).getOrElse(-1L),
        tokensLimit = headers.getIgnoreCase("x-ratelimit-limit-tokens").map(_.toLong).getOrElse(-1L),
        tokensRemaining = headers.getIgnoreCase("x-ratelimit-remaining-tokens").map(_.toLong).getOrElse(-1L),
      ),
      usage,
      None
    )
  }

  // ---- blocking ---------------------------------------------------------------------------

  private def rawJsonCall(body: JsObject, acc: UsageAccumulator)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, (JsValue, Map[String, String])]] = {
    responsesRawCall(body).map(r => ProviderHelpers.wrapResponse(responsesProviderKind, r, env) { resp =>
      acc.updateOpenaiResponses(resp.json.select("usage").asOpt[JsObject])
      (resp.json, resp.headers.mapValues(_.last))
    })
  }

  // Tool loop of the responses API: `function_call` output items are executed and appended back to
  // `input` as `function_call` + `function_call_output` items (the chat/completions loop appends
  // assistant/tool messages instead).
  private def callWithToolSupport(body: JsObject, attrs: TypedMap, nameToFunction: Map[String, String], currentCallCounter: Int, acc: UsageAccumulator)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, (JsValue, Map[String, String])]] = {
    if (currentCallCounter >= responsesToolsOptions.maxFunctionCalls) {
      return rawJsonCall(body, acc)
    }
    rawJsonCall(body, acc).flatMap {
      case Left(err) => err.leftf
      case Right(res) => {
        val functionCalls = res._1.select("output").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
          .filter(_.select("type").asOptString.contains("function_call"))
        if (functionCalls.isEmpty) {
          res.rightf
        } else {
          val calls = functionCalls.map { item =>
            val callId: String = item.select("call_id").asOptString.orElse(item.select("id").asOptString).getOrElse("")
            val name: String = item.select("name").asOptString.getOrElse("")
            val arguments: String = item.select("arguments").asOptString.getOrElse("{}")
            GenericApiResponseChoiceMessageToolCall(Json.obj(
              "id" -> callId,
              "type" -> "function",
              "function" -> Json.obj("name" -> name, "arguments" -> arguments),
            ))
          }
          LlmFunctions.callToolsOpenai(calls, responsesToolsOptions.mcpConnectors, responsesProviderKind, attrs, nameToFunction)(ec, env).flatMap { callResps =>
            val previousInput = body.select("input").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
            // the tool results come back as chat messages, turn them into responses input items
            val resultItems = OpenAiResponsesBodyConverter.chatMessagesToInput(callResps.map(_.asObject)).value.map(_.asObject)
            val newInput = previousInput ++ functionCalls ++ resultItems.filterNot(_.select("type").asOptString.contains("function_call"))
            callWithToolSupport(body ++ Json.obj("input" -> JsArray(newInput)), attrs, nameToFunction, currentCallCounter + 1, acc)
          }
        }
      }
    }
  }

  final override def response(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    if (!responsesEnabled) {
      return super.response(prompt, attrs, originalBody)
    }
    val (body, finalModel, gatewayTools) = payload(prompt, attrs, originalBody)
    val acc = new UsageAccumulator()
    val nameToFunction = LlmFunctions.nameToFunction(responsesToolsOptions.wasmToolsNoInline)
    val callF = if (gatewayTools) callWithToolSupport(body, attrs, nameToFunction, 0, acc) else rawJsonCall(body, acc)
    callF.map {
      case Left(err) => err.left
      case Right((payload, headers)) => {
        val usage = metadataFrom(headers, acc.usage())
        registerUsage(usage, finalModel, headers.getIgnoreCase("openai-processing-ms").map(_.toLong).getOrElse(0L), attrs)
        // `output` items become chat-shaped generations (text, tool_calls, reasoning, annotations),
        // while `raw` keeps the native payload for pass-through (response id, store continuity)
        val messages = OpenAiResponsesBodyConverter.outputToChatMessages(payload).map { obj =>
          val role: String = obj.at("message.role").asOptString.getOrElse("assistant")
          val content: String = obj.at("message.content").asOptString.getOrElse("")
          ChatGeneration(ChatMessage.output(role, content, None, obj))
        }
        Right(ChatResponse(messages, usage, payload))
      }
    }
  }

  // ---- streaming --------------------------------------------------------------------------

  final override def responseStream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    if (!responsesEnabled) {
      return super.responseStream(prompt, attrs, originalBody)
    }
    val (body, finalModel, _) = payload(prompt, attrs, originalBody)
    val acc = new UsageAccumulator()
    responsesRawStream(body).map(r => ProviderHelpers.wrapStreamResponse(responsesProviderKind, r, env) { resp =>
      val responseId = new AtomicReference[String](s"resp-${ULID.random().toLowerCase()}")
      resp.bodyAsSource
        .via(Framing.delimiter(ByteString("\n\n"), Int.MaxValue, true))
        .map(_.utf8String)
        // an SSE block is "event: <type>\ndata: <json>", keep the data lines only
        .map(block => block.split("\n").toSeq.filter(_.startsWith("data:")).map(_.replaceFirst("data:", "").trim).mkString(""))
        .filter(_.nonEmpty)
        .takeWhile(_ != "[DONE]")
        .map(str => Json.parse(str))
        .flatMapConcat(event => eventToChunks(event, responseId, finalModel, resp, acc, attrs))
    })
  }

  private def eventToChunks(event: JsValue, responseId: AtomicReference[String], finalModel: String, resp: WSResponse, acc: UsageAccumulator, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Source[ChatResponseChunk, _] = {
    val typ = event.select("type").asOptString.getOrElse("")
    val created = System.currentTimeMillis() / 1000L
    def chunk(delta: ChatResponseChunkChoiceDelta, finishReason: Option[String] = None, usage: Option[ChatResponseMetadataUsage] = None): ChatResponseChunk = {
      ChatResponseChunk(
        id = responseId.get(),
        created = created,
        model = event.at("response.model").asOptString.getOrElse(finalModel),
        choices = Seq(ChatResponseChunkChoice(0L, delta, finishReason)),
        usage = usage,
      )
    }
    typ match {
      case "response.created" =>
        event.at("response.id").asOptString.foreach(responseId.set)
        Source.empty
      case "response.output_text.delta" =>
        Source.single(chunk(ChatResponseChunkChoiceDelta(event.select("delta").asOptString)))
      case "response.reasoning_summary_text.delta" | "response.reasoning_text.delta" =>
        Source.single(chunk(ChatResponseChunkChoiceDelta(None, reasoning = event.select("delta").asOptString)))
      case "response.refusal.delta" =>
        Source.single(chunk(ChatResponseChunkChoiceDelta(None, refusal = event.select("delta").asOptString)))
      // a tool call is announced as an output item, then its arguments are streamed
      case "response.output_item.added" if event.at("item.type").asOptString.contains("function_call") =>
        val callId: String = event.at("item.call_id").asOptString.orElse(event.at("item.id").asOptString).getOrElse("")
        Source.single(chunk(ChatResponseChunkChoiceDelta(None, tool_calls = Seq(ChatResponseChunkChoiceDeltaToolCall(
          index = event.select("output_index").asOptLong.getOrElse(0L),
          id = callId.some,
          typ = "function".some,
          function = ChatResponseChunkChoiceDeltaToolCallFunction(event.at("item.name").asOptString, ""),
        )))))
      case "response.function_call_arguments.delta" =>
        Source.single(chunk(ChatResponseChunkChoiceDelta(None, tool_calls = Seq(ChatResponseChunkChoiceDeltaToolCall(
          index = event.select("output_index").asOptLong.getOrElse(0L),
          id = None,
          typ = "function".some,
          function = ChatResponseChunkChoiceDeltaToolCallFunction(None, event.select("delta").asOptString.getOrElse("")),
        )))))
      case "response.completed" => {
        // usage is only read here: `response.created` / `response.in_progress` carry "usage": null
        acc.updateOpenaiResponses(event.at("response.usage").asOpt[JsObject])
        val usage = metadataFrom(resp.headers.mapValues(_.last), acc.usage())
        registerUsage(usage, finalModel, resp.header("openai-processing-ms").map(_.toLong).getOrElse(0L), attrs)
        val hadToolCalls = event.at("response.output").asOpt[Seq[JsObject]].getOrElse(Seq.empty).exists(_.select("type").asOptString.contains("function_call"))
        Source.single(chunk(
          ChatResponseChunkChoiceDelta(None),
          finishReason = (if (hadToolCalls) "tool_calls" else "stop").some,
          usage = usage.usage.some,
        ))
      }
      case "response.failed" | "response.incomplete" =>
        val message = event.at("response.error.message").asOptString
          .orElse(event.at("response.incomplete_details.reason").asOptString)
          .getOrElse(s"the provider ended the response with '${typ}'")
        Source.failed(new Throwable(message))
      case "error" =>
        Source.failed(new Throwable(event.select("message").asOptString.getOrElse("streaming error")))
      case _ => Source.empty
    }
  }
}
