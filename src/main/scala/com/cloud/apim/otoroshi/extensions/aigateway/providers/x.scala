package com.cloud.apim.otoroshi.extensions.aigateway.providers

import akka.stream.scaladsl._
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway._
import com.cloud.apim.otoroshi.extensions.aigateway.entities._
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.libs.ws.WSResponse

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.{DurationInt, FiniteDuration}


object XAiApi {
  val baseUrl = "https://api.x.ai"
}
class XAiApi(baseUrl: String = XAiApi.baseUrl, token: String, timeout: FiniteDuration = 30.seconds, env: Env) extends ApiClient[OpenAiApiResponse, OpenAiChatResponseChunk] {

  val supportsTools: Boolean = true
  val supportsStreaming: Boolean = true

  def rawCall(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[WSResponse] = {
    // println("\n\n================================\n")
    // println(s"calling ${method} ${baseUrl}${path}: ${body.getOrElse(Json.obj()).prettify}")
    // println("calling x.ai")
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
      .map { resp =>
        // println(s"resp: ${resp.status} - ${resp.body}")
        // println("\n\n================================\n")
        resp
      }
  }

  override def call(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[OpenAiApiResponse] = {
    rawCall(method, path, body).map { resp =>
      OpenAiApiResponse(resp.status, resp.headers.mapValues(_.last), resp.json)
    }
  }

  override def callWithToolSupport(method: String, path: String, body: Option[JsValue], mcpConnectors: Seq[String])(implicit ec: ExecutionContext): Future[OpenAiApiResponse] = {
    // TODO: accumulate consumptions ???
    if (body.flatMap(_.select("tools").asOpt[JsArray]).exists(_.value.nonEmpty)) {
      call(method, path, body).flatMap {
        case resp if resp.finishBecauseOfToolCalls => {
          body match {
            case None => resp.vfuture
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
        case resp => resp.vfuture
      }
    } else {
      call(method, path, body)
    }
  }

  override def stream(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[(Source[OpenAiChatResponseChunk, _], WSResponse)] = {
    // println("\n\n================================\n")
    // println(s"calling ${method} ${baseUrl}${path}: ${body.getOrElse(Json.obj()).prettify}")
    // println("calling x.ai")
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

object XAiChatClientOptions {
  def fromJson(json: JsValue): XAiChatClientOptions = {
    XAiChatClientOptions(
      model = json.select("model").asOpt[String].getOrElse("grok-beta"),
      max_tokens = json.select("max_tokens").asOpt[Int],
      n = json.select("n").asOpt[Int],
      temperature = json.select("temperature").asOpt[Float].getOrElse(1.0f),
      topP = json.select("topP").asOpt[Float].orElse(json.select("top_p").asOpt[Float]).getOrElse(1.0f),
      wasmTools = json.select("wasm_tools").asOpt[Seq[String]].getOrElse(Seq.empty),
      mcpConnectors = json.select("mcp_connectors").asOpt[Seq[String]].getOrElse(Seq.empty),
      frequency_penalty = json.select("frequency_penalty").asOpt[Double],
      logprobs = json.select("logprobs").asOpt[Boolean],
      top_logprobs = json.select("top_logprobs").asOpt[Int],
      seed = json.select("seed").asOpt[Int],
      presence_penalty = json.select("presence_penalty").asOpt[Double],
      tools = json.select("tools").asOpt[Seq[JsValue]],
      tool_choice = json.select("tools").asOpt[Seq[JsValue]],
      allowConfigOverride = json.select("allow_config_override").asOptBoolean.getOrElse(true),
    )
  }
}

case class XAiChatClientOptions(
                                    model: String = "grok-beta",
                                    frequency_penalty: Option[Double] = None,
                                    logit_bias: Option[Map[String, Int]] = None,
                                    logprobs: Option[Boolean] = None,
                                    stream: Option[Boolean] = Some(false),
                                    top_logprobs: Option[Int] = None,
                                    max_tokens: Option[Int] = None,
                                    n: Option[Int] = Some(1),
                                    seed: Option[Int] = None,
                                    presence_penalty: Option[Double] = None,
                                    response_format: Option[String] = None,
                                    stop: Option[String] = None,
                                    temperature: Float = 1,
                                    topP: Float = 1,
                                    user: Option[String] = None,
                                    tools: Option[Seq[JsValue]] = None,
                                    tool_choice: Option[Seq[JsValue]] =  None,
                                    wasmTools: Seq[String] = Seq.empty,
                                    mcpConnectors: Seq[String] = Seq.empty,
                                    allowConfigOverride: Boolean = true,
                                  ) extends ChatOptions {

  override def topK: Int = 0

  override def json: JsObject = Json.obj(
    "model" -> model,
    "frequency_penalty" -> frequency_penalty,
    "logit_bias" -> logit_bias,
    "logprobs" -> logprobs,
    "top_logprobs" -> top_logprobs,
    "max_tokens" -> max_tokens,
    "n" -> n.getOrElse(1).json,
    "presence_penalty" -> presence_penalty,
    "response_format" -> response_format,
    "seed" -> seed,
    "stop" -> stop,
    "stream" -> stream,
    "temperature" -> temperature,
    "top_p" -> topP,
    "tools" -> tools,
    "tool_choice" -> tool_choice,
    "user" -> user,
    "wasm_tools" -> JsArray(wasmTools.map(_.json)),
    "mcp_connectors" -> JsArray(mcpConnectors.map(_.json)),
    "allow_config_override" -> allowConfigOverride,
  )

  def jsonForCall: JsObject = json - "wasm_tools" - "mcp_connectors" - "allow_config_override"
}

class XAiChatClient(val api: XAiApi, val options: XAiChatClientOptions, id: String) extends ChatClient {

  override def model: Option[String] = options.model.some
  override def supportsTools: Boolean = api.supportsTools
  override def supportsStreaming: Boolean = api.supportsStreaming

  override def stream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    val body = originalBody.asObject - "messages" - "provider"
    val mergedOptions = if (options.allowConfigOverride) options.jsonForCall.deepMerge(body) else options.json
    val callF = if (api.supportsTools && options.wasmTools.nonEmpty) {
      val tools = LlmFunctions.tools(options.wasmTools, options.mcpConnectors)
      api.streamWithToolSupport("POST", "/v1/chat/completions", Some(mergedOptions ++ tools ++ Json.obj("messages" -> prompt.json)), options.mcpConnectors)
    } else {
      api.stream("POST", "/v1/chat/completions", Some(mergedOptions ++ Json.obj("messages" -> prompt.json)))
    }
    callF.map {
      case (source, resp) =>
        var last: OpenAiChatResponseChunk = OpenAiChatResponseChunk(Json.obj())
        source
          .alsoTo(Sink.onComplete { _ =>
            if (last.usage.nonEmpty) {
              val usage = ChatResponseMetadata(
                ChatResponseMetadataRateLimit(
                  requestsLimit = resp.header("x-ratelimit-limit-requests").map(_.toLong).getOrElse(-1L),
                  requestsRemaining = resp.header("x-ratelimit-remaining-requests").map(_.toLong).getOrElse(-1L),
                  tokensLimit = resp.header("x-ratelimit-limit-tokens").map(_.toLong).getOrElse(-1L),
                  tokensRemaining = resp.header("x-ratelimit-remaining-tokens").map(_.toLong).getOrElse(-1L),
                ),
                ChatResponseMetadataUsage(
                  promptTokens = last.usage.map(_.prompt_tokens).getOrElse(-1L),
                  generationTokens = last.usage.map(_.completion_tokens).getOrElse(-1L),
                ),
                None
              )
              val duration: Long = resp.header("xai-processing-ms").map(_.toLong).getOrElse(0L)
              val slug = Json.obj(
                "provider_kind" -> "x.ai",
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
            }
          })
          .map { chunk =>
            last = chunk
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

  override def call(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val body = originalBody.asObject - "messages" - "provider"
    val mergedOptions = if (options.allowConfigOverride) options.jsonForCall.deepMerge(body) else options.json
    val callF = if (api.supportsTools && options.wasmTools.nonEmpty) {
      val tools = LlmFunctions.tools(options.wasmTools, options.mcpConnectors)
      api.callWithToolSupport("POST", "/v1/chat/completions", Some(mergedOptions ++ tools ++ Json.obj("messages" -> prompt.json)), options.mcpConnectors)
    } else {
      api.call("POST", "/v1/chat/completions", Some(mergedOptions ++ Json.obj("messages" -> prompt.json)))
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
      val duration: Long = resp.headers.getIgnoreCase("xai-processing-ms").map(_.toLong).getOrElse(0L)
      val slug = Json.obj(
        "provider_kind" -> "x.ai",
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
      val messages = resp.body.select("choices").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map { obj =>
        val role = obj.select("message").select("role").asOpt[String].getOrElse("user")
        val content = obj.select("message").select("content").asOpt[String].getOrElse("")
        ChatGeneration(ChatMessage(role, content, None))
      }
      Right(ChatResponse(messages, usage))
    }
  }

  override def listModels()(implicit ec: ExecutionContext): Future[Either[JsValue, List[String]]] = {
    api.rawCall("GET", "/v1/models", None).map { resp =>
      if (resp.status == 200) {
        Right(resp.json.select("data").as[List[JsObject]].map(obj => obj.select("id").asString))
      } else {
        Left(Json.obj("error" -> s"bad response code: ${resp.status}"))
      }
    }
  }
}
