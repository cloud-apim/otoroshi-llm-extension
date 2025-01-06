package com.cloud.apim.otoroshi.extensions.aigateway.providers

import akka.stream.scaladsl.{Framing, Source}
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway._
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{GenericApiResponseChoiceMessageToolCall, LlmFunctions, WasmFunction}
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.libs.ws.WSResponse

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

case class GroqApiResponse(status: Int, headers: Map[String, String], body: JsValue) {
  def toOpenAi: OpenAiApiResponse = OpenAiApiResponse(status, headers, body)
  def json: JsValue = Json.obj(
    "status" -> status,
    "headers" -> headers,
    "body" -> body,
  )
}
object GroqModels {
  val LLAMA_3_DEFAULT_8B = "llama3-8b-8192"
  val DISTIL_WHISPER_LARGE_V3_EN = "distil-whisper-large-v3-en"
  val GEMMA_2_9B = "gemma2-9b-it"
  val GEMMA_7B = "gemma-7b-it"
  val LLAMA_3_GROQ = "llama3-groq-70b-8192-tool-use-preview"
}
object GroqApi {
  val baseUrl = "https://api.groq.com"
}
class GroqApi(baseUrl: String = GroqApi.baseUrl, token: String, timeout: FiniteDuration = 10.seconds, env: Env) extends ApiClient[GroqApiResponse, OpenAiChatResponseChunk] {

  val supportsTools: Boolean = true
  val supportsStreaming: Boolean = true

  def rawCall(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[WSResponse] = {
    env.Ws
      .url(s"${baseUrl}${path}")
      .withHttpHeaders(
        "Authorization" -> s"Bearer ${token}",
        "Accept" -> "application/json",
      ).applyOnWithOpt(body) {
        case (builder, body) => builder
          .addHttpHeaders("Content-Type" -> "application/json")
          .withBody(body)
      }
      .withMethod(method)
      .withRequestTimeout(timeout)
      .execute()
  }

  def call(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[GroqApiResponse] = {
    rawCall(method, path, body)
      .map { resp =>
        GroqApiResponse(resp.status, resp.headers.mapValues(_.last), resp.json)
      }
  }

  override def callWithToolSupport(method: String, path: String, body: Option[JsValue], mcpConnectors: Seq[String])(implicit ec: ExecutionContext): Future[GroqApiResponse] = {
    // TODO: accumulate consumptions ???
    if (body.flatMap(_.select("tools").asOpt[JsArray]).exists(_.value.nonEmpty)) {
      call(method, path, body).map(_.toOpenAi).flatMap {
        case resp if resp.finishBecauseOfToolCalls => {
          body match {
            case None => resp.toGroq.vfuture
            case Some(body) => {
              val messages = body.select("messages").asOpt[Seq[JsObject]].map(v => v.flatMap(o => ChatMessage.format.reads(o).asOpt)).getOrElse(Seq.empty)
              val toolCalls = resp.toolCalls
              LlmFunctions.callToolsOpenai(toolCalls.map(tc => GenericApiResponseChoiceMessageToolCall(tc.raw)), mcpConnectors)(ec, env)
                .flatMap { callResps =>
                  val newMessages: Seq[JsValue] = messages.map(_.json) ++ callResps
                  val newBody = body.asObject ++ Json.obj("messages" -> JsArray(newMessages))
                  callWithToolSupport(method, path, newBody.some, mcpConnectors)
                }
            }
          }
        }
        case resp => resp.toGroq.vfuture
      }
    } else {
      call(method, path, body)
    }
  }

  override def stream(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[(Source[OpenAiChatResponseChunk, _], WSResponse)] = {
    env.Ws
      .url(s"${baseUrl}${path}")
      .withHttpHeaders(
        "Authorization" -> s"Bearer ${token}",
        "Accept" -> "application/json",
      ).applyOnWithOpt(body) {
        case (builder, body) => builder
          .addHttpHeaders("Content-Type" -> "application/json")
          .withBody(body.asObject ++ Json.obj(
            "stream" -> true,
            "stream_options" -> Json.obj("include_usage" -> true)
          ))
      }
      .withMethod(method)
      .withRequestTimeout(timeout)
      .stream()
      .map { resp =>
        (resp.bodyAsSource
          .via(Framing.delimiter(ByteString("\n\n"), Int.MaxValue, false))
          .map(_.utf8String)
          .filter(_.startsWith("data: "))
          .map(_.replaceFirst("data: ", "").trim())
          .filter(_.nonEmpty)
          .takeWhile(_ != "[DONE]")
          .map(str => Json.parse(str))
          .map(json => OpenAiChatResponseChunk(json)), resp)
      }
  }

  override def streamWithToolSupport(method: String, path: String, body: Option[JsValue], mcpConnectors: Seq[String])(implicit ec: ExecutionContext): Future[(Source[OpenAiChatResponseChunk, _], WSResponse)] = {
    if (body.flatMap(_.select("tools").asOpt[JsArray]).exists(_.value.nonEmpty)) {
      val messages = body.get.select("messages").asOpt[Seq[JsObject]].map(v => v.flatMap(o => ChatMessage.format.reads(o).asOpt)).getOrElse(Seq.empty)
      stream(method, path, body).flatMap {
        case res => {
          var isToolCall = false
          var isToolCallEnded = false
          var toolCalls: Seq[OpenAiChatResponseChunkChoiceDeltaToolCall] = Seq.empty
          var toolCallArgs: scala.collection.mutable.ArraySeq[String] = scala.collection.mutable.ArraySeq.empty
          var toolCallUsage: OpenAiChatResponseChunkUsage = null
          val newSource = res._1.flatMapConcat { chunk =>
            if (!isToolCall && chunk.choices.exists(_.delta.exists(_.tool_calls.nonEmpty))) {
              isToolCall = true
              toolCalls = chunk.choices.head.delta.head.tool_calls
              toolCallArgs = scala.collection.mutable.ArraySeq((0 to toolCallArgs.size).map(_ => ""): _*)
              Source.empty
            } else if (isToolCall && !isToolCallEnded) {
              if (chunk.choices.head.finish_reason.contains("tool_calls")) {
                isToolCallEnded = true
              } else {
                chunk.choices.head.delta.head.tool_calls.foreach { tc =>
                  val index = tc.index.toInt
                  val arg = tc.function.arguments
                  if (index >= toolCallArgs.size) {
                    toolCallArgs = toolCallArgs :+ ""
                  }
                  if (tc.function.hasName && !toolCalls.exists(t => t.function.hasName && t.function.name == tc.function.name)) {
                    toolCalls = toolCalls :+ tc
                  }
                  toolCallArgs.update(index, toolCallArgs.apply(index) + arg)
                }}
              Source.empty
            } else if (isToolCall && isToolCallEnded) {
              toolCallUsage = chunk.usage.get
              val calls = toolCalls.zipWithIndex.map {
                case (toolCall, idx) =>
                  GenericApiResponseChoiceMessageToolCall(toolCall.raw.asObject.deepMerge(Json.obj("function" -> Json.obj("arguments" -> toolCallArgs(idx)))))
              }
              val a: Future[(Source[OpenAiChatResponseChunk, _], WSResponse)] = LlmFunctions.callToolsOpenai(calls, mcpConnectors)(ec, env)
                .flatMap { callResps =>
                  val newMessages: Seq[JsValue] = messages.map(_.json) ++ callResps
                  val newBody = body.get.asObject ++ Json.obj("messages" -> JsArray(newMessages))
                  streamWithToolSupport(method, path, newBody.some, mcpConnectors)
                }
              Source.future(a).flatMapConcat(a => a._1)
            } else {
              Source.single(chunk)
            }
          }
          (newSource, res._2).vfuture
        }
      }
    } else {
      stream(method, path, body)
    }
  }
}

object GroqChatClientOptions {
  def fromJson(json: JsValue): GroqChatClientOptions = {
    GroqChatClientOptions(
      model = json.select("model").asOpt[String].getOrElse(GroqModels.LLAMA_3_DEFAULT_8B),
      tools = json.select("tools").asOpt[Seq[JsValue]],
      tool_choice = json.select("tool_choice").asOpt[Seq[JsValue]],
      max_tokens = json.select("max_tokens").asOpt[Int],
      temperature = json.select("temperature").asOpt[Float].getOrElse(1.0f),
      topP = json.select("top_p").asOpt[Float].getOrElse(1.0f),
      n = json.select("n").asOpt[Int].getOrElse(1),
      wasmTools = json.select("wasm_tools").asOpt[Seq[String]].getOrElse(Seq.empty),
      mcpConnectors = json.select("mcp_connectors").asOpt[Seq[String]].getOrElse(Seq.empty),
      allowConfigOverride = json.select("allow_config_override").asOptBoolean.getOrElse(true),
    )
  }
}

case class GroqChatClientOptions(
    model: String = GroqModels.LLAMA_3_DEFAULT_8B,
    max_tokens: Option[Int] = None,
    stream: Option[Boolean] = Some(false),
    temperature: Float = 1,
    tools: Option[Seq[JsValue]] = None,
    tool_choice: Option[Seq[JsValue]] =  None,
    topP: Float = 1,
    n: Int = 1,
    wasmTools: Seq[String] = Seq.empty,
    mcpConnectors: Seq[String] = Seq.empty,
    allowConfigOverride: Boolean = true,
) extends ChatOptions {

  override def json: JsObject = Json.obj(
    "model" -> model,
    "max_tokens" -> max_tokens,
    "stream" -> stream,
    "temperature" -> temperature,
    "top_p" -> topP,
    "tools" -> tools,
    "tool_choice" -> tool_choice,
    "n" -> n,
    "wasm_tools" -> JsArray(wasmTools.map(_.json)),
    "mcp_connectors" -> JsArray(mcpConnectors.map(_.json)),
    "allow_config_override" -> allowConfigOverride,
  )

  def jsonForCall: JsObject = json - "wasm_tools" - "mcp_connectors" - "allow_config_override"

  override def topK: Int = 0
}

class GroqChatClient(api: GroqApi, options: GroqChatClientOptions, id: String) extends ChatClient {

  override def model: Option[String] = options.model.some
  override def supportsTools: Boolean = api.supportsTools
  override def supportsStreaming: Boolean = api.supportsStreaming

  override def listModels()(implicit ec: ExecutionContext): Future[Either[JsValue, List[String]]] = {
    api.rawCall("GET", "/openai/v1/models", None).map { resp =>
      if (resp.status == 200) {
        Right(resp.json.select("data").as[List[JsObject]].map(obj => obj.select("id").asString))
      } else {
        Left(Json.obj("error" -> s"bad response code: ${resp.status}"))
      }
    }
  }

  override def call(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val obody = originalBody.asObject - "messages" - "provider"
    val mergedOptions = if (options.allowConfigOverride) options.jsonForCall.deepMerge(obody) else options.json
    val callF = if (api.supportsTools && options.wasmTools.nonEmpty) {
      val tools = LlmFunctions.tools(options.wasmTools, options.mcpConnectors)
      api.callWithToolSupport("POST", "/openai/v1/chat/completions", Some(mergedOptions ++ tools ++ Json.obj("messages" -> prompt.json)), options.mcpConnectors)
    } else {
      api.call("POST", "/openai/v1/chat/completions", Some(mergedOptions ++ Json.obj("messages" -> prompt.json)))
    }
    callF.map { resp =>
      val usage = ChatResponseMetadata(
        ChatResponseMetadataRateLimit(
          requestsLimit = resp.headers.getIgnoreCase("x-ratelimit-limit-requests").map(_.toLong).getOrElse(-1L),
          requestsRemaining = resp.headers.getIgnoreCase("x-ratelimit-remaining-requests").map(_.toLong).getOrElse(-1L),
          tokensLimit = resp.headers.getIgnoreCase("x-ratelimit-limit-tokens").map(_.toLong).getOrElse(-1L),
          tokensRemaining = resp.headers.getIgnoreCase("x-ratelimit-remaining-tokens").map(_.toLong).getOrElse(-1L),
        ),
        ChatResponseMetadataUsage(
          promptTokens = resp.body.select("usage").select("prompt_tokens").asOpt[Long].getOrElse(-1L),
          generationTokens = resp.body.select("usage").select("completion_tokens").asOpt[Long].getOrElse(-1L),
        ),
        None
      )
      val duration: Long = resp.body.select("total_time").asOpt[Long].getOrElse(0L)
      val slug = Json.obj(
        "provider_kind" -> "groq",
        "provider" -> id,
        "duration" -> duration,
        "model" -> options.model.json,
        "rate_limit" -> usage.rateLimit.json,
        "usage" -> usage.usage.json
      ).applyOnWithOpt(usage.cache) {
        case (obj, cache) => obj ++ Json.obj("cache" -> cache.json)
      }
      attrs.update(ChatClient.ApiUsageKey -> usage)
      attrs.update(otoroshi.plugins.Keys.ExtraAnalyticsDataKey) {
        case Some(obj @ JsObject(_)) => {
          val arr = obj.select("ai").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
          val newArr = arr ++ Seq(slug)
          obj ++ Json.obj("ai" -> newArr)
        }
        case Some(other) => other
        case None => Json.obj("ai" -> Seq(slug))
      }
      val messages = resp.body.select("choices").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map { obj =>
        val role = obj.select("message").select("role").asOpt[String].getOrElse("user")
        val content = obj.select("message").select("content").asOpt[String].getOrElse("")
        ChatGeneration(ChatMessage(role, content, None))
      }
      Right(ChatResponse(messages, usage))
    }
  }

  override def stream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    val obody = originalBody.asObject - "messages" - "provider"
    val mergedOptions = if (options.allowConfigOverride) options.jsonForCall.deepMerge(obody) else options.json
    val callF = if (api.supportsTools && options.wasmTools.nonEmpty) {
      val tools = LlmFunctions.tools(options.wasmTools, options.mcpConnectors)
      api.streamWithToolSupport("POST", "/openai/v1/chat/completions", Some(mergedOptions ++ tools ++ Json.obj("messages" -> prompt.json)), options.mcpConnectors)
    } else {
      api.stream("POST", "/openai/v1/chat/completions", Some(mergedOptions ++ Json.obj("messages" -> prompt.json)))
    }
    callF.map {
      case (source, resp) =>
        source
          .filterNot { chunk =>
            if (chunk.usage.nonEmpty) {
              val usage = ChatResponseMetadata(
                ChatResponseMetadataRateLimit(
                  requestsLimit = resp.header("x-ratelimit-limit-requests").map(_.toLong).getOrElse(-1L),
                  requestsRemaining = resp.header("x-ratelimit-remaining-requests").map(_.toLong).getOrElse(-1L),
                  tokensLimit = resp.header("x-ratelimit-limit-tokens").map(_.toLong).getOrElse(-1L),
                  tokensRemaining = resp.header("x-ratelimit-remaining-tokens").map(_.toLong).getOrElse(-1L),
                ),
                ChatResponseMetadataUsage(
                  promptTokens = chunk.usage.map(_.prompt_tokens).getOrElse(-1L),
                  generationTokens = chunk.usage.map(_.completion_tokens).getOrElse(-1L),
                ),
                None
              )
              val duration: Long = resp.header("total_time").map(_.toLong).getOrElse(0L)
              val slug = Json.obj(
                "provider_kind" -> "groq",
                "provider" -> id,
                "duration" -> duration,
                "model" -> options.model.json,
                "rate_limit" -> usage.rateLimit.json,
                "usage" -> usage.usage.json
              ).applyOnWithOpt(usage.cache) {
                case (obj, cache) => obj ++ Json.obj("cache" -> cache.json)
              }
              attrs.update(ChatClient.ApiUsageKey -> usage)
              attrs.update(otoroshi.plugins.Keys.ExtraAnalyticsDataKey) {
                case Some(obj@JsObject(_)) => {
                  val arr = obj.select("ai").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
                  val newArr = arr ++ Seq(slug)
                  obj ++ Json.obj("ai" -> newArr)
                }
                case Some(other) => other
                case None => Json.obj("ai" -> Seq(slug))
              }
              true
            } else {
              false
            }
          }
          .map { chunk =>
            ChatResponseChunk(
              id = chunk.id,
              created = chunk.created,
              model = chunk.model,
              choices = chunk.choices.map { choice =>
                ChatResponseChunkChoice(
                  index = choice.index.map(_.toLong).getOrElse(0L),
                  delta = ChatResponseChunkChoiceDelta(
                    choice.delta.flatMap(_.content)
                  ),
                  finishReason = choice.finish_reason
                )
              }
            )
          }.right
    }
  }
}
