package com.cloud.apim.otoroshi.extensions.aigateway.providers

import akka.http.scaladsl.model.{ContentType, HttpEntity, Multipart, Uri}
import akka.stream.scaladsl.{Framing, Source}
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway._
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{GenericApiResponseChoiceMessageToolCall, LlmFunctions}
import io.azam.ulidj.ULID
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}
import play.api.libs.ws.WSResponse

import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

case class OpenAiChatResponseChunkUsage(raw: JsValue) {
  lazy val completion_tokens: Long = raw.select("completion_tokens").asLong
  lazy val prompt_tokens: Long = raw.select("prompt_tokens").asLong
  lazy val total_tokens: Long = raw.select("total_tokens").asLong
  lazy val reasoningTokens: Long = raw.at("completion_tokens_details.reasoning_tokens").asOptLong.getOrElse(0L)
}

case class OpenAiChatResponseChunkChoiceDeltaToolCallFunction(raw: JsValue) {
  lazy val name: String = raw.select("name").asString
  lazy val nameOpt: Option[String] = raw.select("name").asOptString
  lazy val hasName: Boolean = nameOpt.isDefined
  lazy val arguments: String = raw.select("arguments").asString
}

case class OpenAiChatResponseChunkChoiceDeltaToolCall(raw: JsValue) {
  lazy val index: Long = raw.select("index").asInt
  lazy val id: String = raw.select("id").asString
  lazy val typ: String = raw.select("type").asString
  lazy val function: OpenAiChatResponseChunkChoiceDeltaToolCallFunction = OpenAiChatResponseChunkChoiceDeltaToolCallFunction(raw.select("function").asObject)
}

case class OpenAiChatResponseChunkChoiceDelta(raw: JsValue) {
  lazy val content: Option[String] = raw.select("content").asOptString
  lazy val role: String = raw.select("role").asString
  lazy val refusal: Option[String] = raw.select("refusal").asOptString
  lazy val tool_calls: Seq[OpenAiChatResponseChunkChoiceDeltaToolCall] = raw.select("tool_calls").asOpt[Seq[JsObject]].map(_.map(OpenAiChatResponseChunkChoiceDeltaToolCall.apply)).getOrElse(Seq.empty)
}

case class OpenAiChatResponseChunkChoice(raw: JsValue) {
  lazy val finish_reason: Option[String] = raw.select("finish_reason").asOptString
  lazy val index: Option[Int] = raw.select("index").asOptInt
  lazy val delta: Option[OpenAiChatResponseChunkChoiceDelta] = raw.select("delta").asOpt[JsObject].map(OpenAiChatResponseChunkChoiceDelta.apply)
}

case class OpenAiChatResponseChunk(raw: JsValue) {
  lazy val id: String = raw.select("id").asOptString.getOrElse(s"chatcmpl-${ULID.random().toLowerCase()}")
  lazy val obj: String = raw.select("object").asString
  lazy val created: Long = raw.select("created").asLong
  lazy val model: String = raw.select("model").asString
  lazy val system_fingerprint: String = raw.select("system_fingerprint").asString
  lazy val service_tier: Option[String] = raw.select("service_tier").asOptString
  lazy val usage: Option[OpenAiChatResponseChunkUsage] = raw.select("usage").asOpt[JsObject].map { obj =>
    OpenAiChatResponseChunkUsage(obj)
  }
  lazy val choices: Seq[OpenAiChatResponseChunkChoice] = raw.select("choices").asOpt[Seq[JsObject]].map(_.map(i => OpenAiChatResponseChunkChoice(i))).getOrElse(Seq.empty)
}

case class OpenAiApiResponseChoiceMessageToolCallFunction(raw: JsObject) {
  lazy val name: String = raw.select("name").asString
  lazy val arguments: String = raw.select("arguments").asString
}

case class OpenAiApiResponseChoiceMessageToolCall(raw: JsObject) {
  lazy val id: String = raw.select("id").asString
  lazy val function: OpenAiApiResponseChoiceMessageToolCallFunction = OpenAiApiResponseChoiceMessageToolCallFunction(raw.select("function").asObject)
}

case class OpenAiApiResponseChoiceMessage(raw: JsObject) {
  lazy val role: String = raw.select("role").asString
  lazy val content: Option[String] = raw.select("content").asOpt[String]
  lazy val refusal: Option[String] = raw.select("refusal").asOpt[String]
  lazy val toolCalls: Seq[OpenAiApiResponseChoiceMessageToolCall] = raw.select("tool_calls").asOpt[Seq[JsObject]].map(_.map(v => OpenAiApiResponseChoiceMessageToolCall(v))).getOrElse(Seq.empty)
}

case class OpenAiApiResponseChoice(raw: JsObject) {
  lazy val index: Int = raw.select("index").asOpt[Int].getOrElse(-1)
  lazy val finishReason: String = raw.select("finish_reason").asOpt[String].getOrElse("--")
  lazy val finishBecauseOfToolCalls: Boolean = finishReason == "tool_calls"
  lazy val message: OpenAiApiResponseChoiceMessage = raw.select("message").asOpt[JsObject].map(v => OpenAiApiResponseChoiceMessage(v)).get
}

case class OpenAiApiResponse(status: Int, headers: Map[String, String], body: JsValue) {
  lazy val finishBecauseOfToolCalls: Boolean = choices.exists(_.finishBecauseOfToolCalls)
  lazy val toolCalls: Seq[OpenAiApiResponseChoiceMessageToolCall] = choices.map(_.message).flatMap(_.toolCalls)
  lazy val choices: Seq[OpenAiApiResponseChoice] = {
    body.select("choices").asOpt[Seq[JsObject]].map(_.map(v => OpenAiApiResponseChoice(v))).getOrElse(Seq.empty)
  }
  def toMistral: MistralAiApiResponse = MistralAiApiResponse(status, headers, body)
  def toGroq: GroqApiResponse = GroqApiResponse(status, headers, body)
  def json: JsValue = Json.obj(
    "status" -> status,
    "headers" -> headers,
    "body" -> body,
  )
}
object OpenAiModels {
  val GPT_4_0125_PREVIEW = "gpt-4-0125-preview"
  val GPT_4_TURBO_PREVIEW = "gpt-4-turbo-preview"
  val GPT_4_VISION_PREVIEW = "gpt-4-vision-preview"
  val GPT_4 = "gpt-4"
  val GPT_4_O = "gpt-4o"
  val GPT_4_O_MINI = "gpt-4o-mini"
  val GPT_4_32K = "gpt-4-32k"
  val GPT_3_5_TURBO = "gpt-3.5-turbo"
  val GPT_3_5_TURBO_0125 = "gpt-3.5-turbo-0125"
  val GPT_3_5_TURBO_1106 = "gpt-3.5-turbo-1106"
}

object OpenAiApi {
  val baseUrl = "https://api.openai.com/v1"
}

class OpenAiApi(_baseUrl: String = OpenAiApi.baseUrl, token: String, timeout: FiniteDuration = 10.seconds, providerName: String, env: Env) extends ApiClient[OpenAiApiResponse, OpenAiChatResponseChunk] {

  val supportsTools: Boolean = true
  val supportsStreaming: Boolean = true
  val supportsCompletion: Boolean = true

  lazy val baseUrl: String = {
    if (_baseUrl.startsWith("https://api.openai.com") && !_baseUrl.startsWith("https://api.openai.com/v1")) {
      "https://api.openai.com/v1"
    } else {
      _baseUrl
    }
  }

  def rawCall(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[WSResponse] = {
    val url = s"${baseUrl}${path}"
    val uri = Uri(url)
    ProviderHelpers.logCall(providerName, method, url, body)(env)
    env.Ws
      .url(url)
      .withHttpHeaders(
        "Authorization" -> s"Bearer ${token}",
        "Accept" -> "application/json",
        "Host" -> uri.authority.host.toString(),
      ).applyOnWithOpt(body) {
        case (builder, body) => builder
          .addHttpHeaders("Content-Type" -> "application/json")
          .withBody(body)
      }
      .withMethod(method)
      .withRequestTimeout(timeout)
      .execute()
      .map { resp =>
        println(s"resp: ${resp.status} - ${resp.body}")
        println("\n\n================================\n")
        resp
      }
  }

  def rawCallForm(method: String, path: String, body: Multipart)(implicit ec: ExecutionContext): Future[WSResponse] = {
    val url = s"${baseUrl}${path}"
    val uri = Uri(url)
    ProviderHelpers.logCall(providerName, method, url, None)(env)
    val entity = body.toEntity()
    env.Ws
      .url(url)
      .withHttpHeaders(
        "Authorization" -> s"Bearer ${token}",
        "Accept" -> "application/json",
        "Host" -> uri.authority.host.toString(),
        "Content-Type" -> entity.contentType.toString()
      )
      .withBody(entity.dataBytes)
      .withMethod(method)
      .withRequestTimeout(timeout)
      .execute()
      .map { resp =>
        println(s"form resp: ${resp.status} - ${resp.body}")
        println("\n\n================================\n")
        resp
      }
  }

  override def call(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[Either[JsValue, OpenAiApiResponse]] = {
    rawCall(method, path, body).map(r => ProviderHelpers.wrapResponse(providerName, r, env) { resp =>
      OpenAiApiResponse(resp.status, resp.headers.mapValues(_.last), resp.json)
    })
  }

  override def callWithToolSupport(method: String, path: String, body: Option[JsValue], mcpConnectors: Seq[String])(implicit ec: ExecutionContext): Future[Either[JsValue, OpenAiApiResponse]] = {
    // TODO: accumulate consumptions ???
    if (body.flatMap(_.select("tools").asOpt[JsArray]).exists(_.value.nonEmpty)) {
      call(method, path, body).flatMap {
        case Left(err) => err.leftf
        case Right(resp) if resp.finishBecauseOfToolCalls => {
          body match {
            case None => resp.rightf
            case Some(body) => {
              val messages = body.select("messages").asOpt[Seq[JsObject]].getOrElse(Seq.empty) //.map(v => v.flatMap(o => ChatMessage.format.reads(o).asOpt)).getOrElse(Seq.empty)
              val toolCalls = resp.toolCalls
              LlmFunctions.callToolsOpenai(toolCalls.map(tc => GenericApiResponseChoiceMessageToolCall(tc.raw)), mcpConnectors, providerName)(ec, env)
                .flatMap { callResps =>
                  // val newMessages: Seq[JsValue] = messages.map(_.json) ++ callResps
                  val newMessages: Seq[JsValue] = messages ++ callResps
                  val newBody = body.asObject ++ Json.obj("messages" -> JsArray(newMessages))
                  callWithToolSupport(method, path, newBody.some, mcpConnectors)
                }
            }
          }
        }
        case Right(resp) =>
          // println(s"resp: ${resp.status} - ${resp.body.prettify}")
          resp.rightf
      }
    } else {
      call(method, path, body)
    }
  }

  override def stream(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[Either[JsValue, (Source[OpenAiChatResponseChunk, _], WSResponse)]] = {
    val url = s"${baseUrl}${path}"
    val uri = Uri(url)
    ProviderHelpers.logStream(providerName, method, url, body)(env)
    env.Ws
      .url(s"${baseUrl}${path}")
      .withHttpHeaders(
        "Authorization" -> s"Bearer ${token}",
        "Accept" -> "application/json",
        "Host" -> uri.authority.host.toString(),
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
      .map(r => ProviderHelpers.wrapStreamResponse(providerName, r, env) { resp =>
        (resp.bodyAsSource
          .via(Framing.delimiter(ByteString("\n\n"), Int.MaxValue, false))
          .map(_.utf8String)
          .filter(_.startsWith("data: "))
          .map(_.replaceFirst("data: ", "").trim())
          .filter(_.nonEmpty)
          .takeWhile(_ != "[DONE]")
          .map(str => Json.parse(str))
          .map(json => OpenAiChatResponseChunk(json))
        , resp)
      })
  }

  override def streamWithToolSupport(method: String, path: String, body: Option[JsValue], mcpConnectors: Seq[String])(implicit ec: ExecutionContext): Future[Either[JsValue, (Source[OpenAiChatResponseChunk, _], WSResponse)]] = {
    if (body.flatMap(_.select("tools").asOpt[JsArray]).exists(_.value.nonEmpty)) {
      val messages = body.get.select("messages").asOpt[Seq[JsObject]].getOrElse(Seq.empty) //.map(v => v.flatMap(o => ChatMessage.format.reads(o).asOpt)).getOrElse(Seq.empty)
      stream(method, path, body).flatMap {
        case Left(err) => err.leftf
        case Right(res) => {
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
              val a: Future[Either[JsValue, (Source[OpenAiChatResponseChunk, _], WSResponse)]] = LlmFunctions.callToolsOpenai(calls, mcpConnectors, providerName)(ec, env)
                .flatMap { callResps =>
                  // val newMessages: Seq[JsValue] = messages.map(_.json) ++ callResps
                  val newMessages: Seq[JsValue] = messages ++ callResps
                  val newBody = body.get.asObject ++ Json.obj("messages" -> JsArray(newMessages))
                  streamWithToolSupport(method, path, newBody.some, mcpConnectors)
                }
              Source.future(a).flatMapConcat {
                case Left(err) => Source.failed(new Throwable(err.stringify))
                case Right(tuple) => tuple._1
              }
            } else {
              Source.single(chunk)
            }
          }
          (newSource, res._2).rightf
        }
      }
    } else {
      stream(method, path, body)
    }
  }
}

object OpenAiChatClientOptions {
  def fromJson(json: JsValue): OpenAiChatClientOptions = {
    OpenAiChatClientOptions(
      model = json.select("model").asOpt[String].getOrElse(OpenAiModels.GPT_4_O_MINI),
      max_tokens = json.select("max_tokens").asOpt[Int],
      n = json.select("n").asOpt[Int],
      _temperature = json.select("temperature").asOpt[Float],
      _topP = json.select("topP").asOpt[Float].orElse(json.select("top_p").asOpt[Float]),
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

case class OpenAiChatClientOptions(
  model: String = OpenAiModels.GPT_4_O_MINI,
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
  _temperature: Option[Float] = 1.0f.some,
  _topP: Option[Float] = 1.0f.some,
  user: Option[String] = None,
  tools: Option[Seq[JsValue]] = None,
  tool_choice: Option[Seq[JsValue]] =  None,
  wasmTools: Seq[String] = Seq.empty,
  mcpConnectors: Seq[String] = Seq.empty,
  allowConfigOverride: Boolean = true,
) extends ChatOptions {

  override def temperature: Float = _temperature.getOrElse(1.0f)

  override def topP: Float = _topP.getOrElse(1.0f)

  override def topK: Int = 0

  override def json: JsObject = Json.obj(
    "model" -> model,
    "frequency_penalty" -> frequency_penalty,
    "logit_bias" -> logit_bias,
    "logprobs" -> logprobs,
    "top_logprobs" -> top_logprobs,
    "max_tokens" -> max_tokens,
    "n" -> n,
    "presence_penalty" -> presence_penalty,
    "response_format" -> response_format,
    "seed" -> seed,
    "stop" -> stop,
    "stream" -> stream,
    "temperature" -> _temperature,
    "top_p" -> _topP,
    "tools" -> tools,
    "tool_choice" -> tool_choice,
    "user" -> user,
    "wasm_tools" -> JsArray(wasmTools.map(_.json)),
    "mcp_connectors" -> JsArray(mcpConnectors.map(_.json)),
    "allow_config_override" -> allowConfigOverride,
  )

  def jsonForCall: JsObject = optionsCleanup(json - "wasm_tools" - "mcp_connectors" - "allow_config_override")
}

class OpenAiChatClient(val api: OpenAiApi, val options: OpenAiChatClientOptions, id: String, providerName: String, modelsPath: String = "/models", completion: Boolean = true, accumulateStreamConsumptions: Boolean = false) extends ChatClient {

  override def model: Option[String] = options.model.some
  override def supportsTools: Boolean = api.supportsTools
  override def supportsStreaming: Boolean = api.supportsStreaming
  override def supportsCompletion: Boolean = completion //api.supportsCompletion

  override def stream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    val body = originalBody.asObject - "messages" - "provider"
    val _mergedOptions = if (options.allowConfigOverride) options.jsonForCall.deepMerge(body) else options.jsonForCall
    val finalModel = _mergedOptions.select("model").asString
    val mergedOptions = if (finalModel.contains("search-preview")) (_mergedOptions - "n" - "top_p" - "temperature" - "stop" - "presence_penalty" - "frequency_penalty" - "logprobs" - "top_logprobs" - "max_completion_tokens" - "logit_bias" - "seed") else _mergedOptions
    val callF = if (api.supportsTools && (options.wasmTools.nonEmpty || options.mcpConnectors.nonEmpty)) {
      val tools = LlmFunctions.tools(options.wasmTools, options.mcpConnectors)
      api.streamWithToolSupport("POST", "/chat/completions", Some(mergedOptions ++ tools ++ Json.obj("messages" -> prompt.jsonWithFlavor(ChatMessageContentFlavor.OpenAi))), options.mcpConnectors)
    } else {
      api.stream("POST", "/chat/completions", Some(mergedOptions ++ Json.obj("messages" -> prompt.jsonWithFlavor(ChatMessageContentFlavor.OpenAi))))
    }
    callF.map {
      case Left(err) => err.left
      case Right((source, resp)) =>
        val promptTokensCounter = new AtomicLong(0L)
        val generationTokensCounter = new AtomicLong(0L)
        val reasoningTokensCounter = new AtomicLong(0L)
        source
          .applyOnIf(accumulateStreamConsumptions)(
            _.map { chunk =>
              promptTokensCounter.addAndGet(chunk.usage.map(_.prompt_tokens).getOrElse(-1L))
              generationTokensCounter.addAndGet(chunk.usage.map(_.completion_tokens).getOrElse(-1L))
              reasoningTokensCounter.addAndGet(chunk.usage.map(_.reasoningTokens).getOrElse(-1L))
              if (chunk.choices.exists(_.finish_reason.contains("stop"))) {
                val usage = ChatResponseMetadata(
                  ChatResponseMetadataRateLimit(
                    requestsLimit = resp.header("x-ratelimit-limit-requests").map(_.toLong).getOrElse(-1L),
                    requestsRemaining = resp.header("x-ratelimit-remaining-requests").map(_.toLong).getOrElse(-1L),
                    tokensLimit = resp.header("x-ratelimit-limit-tokens").map(_.toLong).getOrElse(-1L),
                    tokensRemaining = resp.header("x-ratelimit-remaining-tokens").map(_.toLong).getOrElse(-1L),
                  ),
                  ChatResponseMetadataUsage(
                    promptTokens = promptTokensCounter.get(),
                    generationTokens = generationTokensCounter.get(),
                    reasoningTokens = reasoningTokensCounter.get(),
                  ),
                  None
                )
                val duration: Long = resp.header("openai-processing-ms").map(_.toLong).getOrElse(0L)
                val slug = Json.obj(
                  "provider_kind" -> providerName,
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
              chunk
            }
          )
          .applyOnIf(!accumulateStreamConsumptions)(
            _.filterNot { chunk =>
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
                    reasoningTokens = chunk.usage.map(_.reasoningTokens).getOrElse(-1L),
                  ),
                  None
                )
                val duration: Long = resp.header("openai-processing-ms").map(_.toLong).getOrElse(0L)
                val slug = Json.obj(
                  "provider_kind" -> providerName,
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
          )
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

  override def call(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val body = originalBody.asObject - "messages" - "provider"
    val _mergedOptions = if (options.allowConfigOverride) options.jsonForCall.deepMerge(body) else options.jsonForCall
    val finalModel = _mergedOptions.select("model").asString
    val mergedOptions = if (finalModel.contains("search-preview")) (_mergedOptions - "n" - "top_p" - "temperature" - "stop" - "presence_penalty" - "frequency_penalty" - "logprobs" - "top_logprobs" - "max_completion_tokens" - "logit_bias" - "seed") else _mergedOptions
    val callF = if (api.supportsTools && (options.wasmTools.nonEmpty || options.mcpConnectors.nonEmpty)) {
      val tools = LlmFunctions.tools(options.wasmTools, options.mcpConnectors)
      // println(s"tools added: ${tools.prettify}")
      api.callWithToolSupport("POST", "/chat/completions", Some(mergedOptions ++ tools ++ Json.obj("messages" -> prompt.jsonWithFlavor(ChatMessageContentFlavor.OpenAi))), options.mcpConnectors)
    } else {
      api.call("POST", "/chat/completions", Some(mergedOptions ++ Json.obj("messages" -> prompt.jsonWithFlavor(ChatMessageContentFlavor.OpenAi))))
    }
    callF.map {
      case Left(err) => err.left
      case Right(resp) =>
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
          reasoningTokens = resp.body.at("usage.completion_tokens_details.reasoning_tokens").asOpt[Long].getOrElse(-1L),
        ),
        None
      )
      val duration: Long = resp.headers.getIgnoreCase("openai-processing-ms").map(_.toLong).getOrElse(0L)
      val slug = Json.obj(
        "provider_kind" -> providerName,
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
        ChatGeneration(ChatMessage.output(role, content, None, obj))
      }
      Right(ChatResponse(messages, usage))
    }
  }

  override def completion(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val body = originalBody.asObject - "messages" - "provider" - "prompt"
    val _mergedOptions = if (options.allowConfigOverride) options.jsonForCall.deepMerge(body) else options.jsonForCall
    val finalModel = _mergedOptions.select("model").asString
    val mergedOptions = if (finalModel.contains("search-preview")) (_mergedOptions - "n" - "top_p" - "temperature" - "stop" - "presence_penalty" - "frequency_penalty" - "logprobs" - "top_logprobs" - "max_completion_tokens" - "logit_bias" - "seed") else _mergedOptions
    val callF = api.call("POST", "/completions", Some(mergedOptions ++ Json.obj("prompt" -> prompt.messages.head.content)))
    callF.map {
      case Left(err) => err.left
      case Right(resp) =>
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
          reasoningTokens = resp.body.at("usage.completion_tokens_details.reasoning_tokens").asOpt[Long].getOrElse(-1L),
        ),
        None
      )
      val duration: Long = resp.headers.getIgnoreCase("openai-processing-ms").map(_.toLong).getOrElse(0L)
      val slug = Json.obj(
        "provider_kind" -> providerName,
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
        val content = obj.select("text").asString
        ChatGeneration(ChatMessage.output("assistant", content, None, obj))
      }
      Right(ChatResponse(messages, usage))
    }
  }

  override def listModels(raw: Boolean)(implicit ec: ExecutionContext): Future[Either[JsValue, List[String]]] = {
    api.rawCall("GET", modelsPath, None).map { resp =>
      if (resp.status == 200) {
        Right(resp.json.select("data").as[List[JsObject]].map(obj => obj.select("id").asString)
          .applyOnIf(providerName.toLowerCase() == "openai")(_.filter(v => v.toLowerCase.startsWith("gpt") || v.toLowerCase.startsWith("o1")))
        )
      } else {
        Left(Json.obj("error" -> s"bad response code: ${resp.status}"))
      }
    }
  }
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/////////                                       OpenAI Embedding                                         ///////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

case class OpenAiEmbeddingModelClientOptions(raw: JsObject) {
  lazy val model: String = raw.select("model").asOpt[String].getOrElse("text-embedding-3-small")
}

object OpenAiEmbeddingModelClientOptions {
  def fromJson(raw: JsObject): OpenAiEmbeddingModelClientOptions = OpenAiEmbeddingModelClientOptions(raw)
}

class OpenAiEmbeddingModelClient(val api: OpenAiApi, val options: OpenAiEmbeddingModelClientOptions, id: String) extends EmbeddingModelClient {

  override def embed(input: Seq[String], modelOpt: Option[String])(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, EmbeddingResponse]] = {
    val finalModel: String = modelOpt.getOrElse(options.model)
    api.rawCall("POST", "/embeddings", (options.raw ++ Json.obj("input" -> input, "model" -> finalModel)).some).map { resp =>
      if (resp.status == 200) {
        Right(EmbeddingResponse(
          model = finalModel,
          embeddings = resp.json.select("data").as[Seq[JsObject]].map(o => Embedding(o.select("embedding").as[Array[Float]])),
          metadata = EmbeddingResponseMetadata(
            resp.json.select("usage").select("prompt_tokens").asOpt[Long].getOrElse(-1L)
          ),
        ))
      } else {
        Left(Json.obj("status" -> resp.status, "body" -> resp.json))
      }
    }
  }
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/////////                                    OpenAI Moderation Models                                    ///////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

case class OpenAiModerationModelClientOptions(raw: JsObject) {
  lazy val model: String = raw.select("model").asOpt[String].getOrElse("omni-moderation-latest")
}

object OpenAiModerationModelClientOptions {
  def fromJson(raw: JsObject): OpenAiModerationModelClientOptions = OpenAiModerationModelClientOptions(raw)
}

class OpenAiModerationModelClient(val api: OpenAiApi, val options: OpenAiModerationModelClientOptions, id: String) extends ModerationModelClient {

  override def moderate(promptInput: String, modelOpt: Option[String])(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ModerationResponse]] = {
    val finalModel: String = modelOpt.getOrElse(options.model)
    api.rawCall("POST", "/moderations", (options.raw ++
      Json.obj(
        "input" -> promptInput,
        "model" -> finalModel
      )).some).map { resp =>
      if (resp.status == 200) {
        Right(ModerationResponse(
          model = resp.json.select("model").asString,
          moderationResults = resp.json.select("results").as[Seq[JsObject]].map(o => ModerationResult(o.select("flagged").asOpt[Boolean].getOrElse(false), o.select("categories").asOpt[JsObject].getOrElse(Json.obj()), o.select("category_scores").asOpt[JsObject].getOrElse(Json.obj()))),
        ))
      } else {
        Left(Json.obj("status" -> resp.status, "body" -> resp.json))
      }
    }
  }
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/////////                             Audio generation and transcription                                 ///////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
case class OpenAIAudioModelClientTtsOptions(raw: JsObject) {
  lazy val model: String = raw.select("model").asOptString.getOrElse("gpt-4o-mini-tts")
  lazy val voice: String = raw.select("voice").asOptString.getOrElse("alloy")
  lazy val instructions: Option[String] = raw.select("instructions").asOptString
  lazy val responseFormat: Option[String] = raw.select("response_format").asOptString
  lazy val speed: Option[Double] = raw.select("speed").asOpt[Double]
}

object OpenAIAudioModelClientTtsOptions {
  def fromJson(raw: JsObject): OpenAIAudioModelClientTtsOptions = OpenAIAudioModelClientTtsOptions(raw)
}

case class OpenAIAudioModelClientSttOptions(raw: JsObject) {
  lazy val model: Option[String] = raw.select("model").asOptString
  lazy val language: Option[String] = raw.select("language").asOptString
  lazy val prompt: Option[String] = raw.select("prompt").asOptString
  lazy val responseFormat: Option[String] = raw.select("response_format").asOptString
  lazy val temperature: Option[Double] = raw.select("temperature").asOpt[Double]
}

object OpenAIAudioModelClientSttOptions {
  def fromJson(raw: JsObject): OpenAIAudioModelClientSttOptions = OpenAIAudioModelClientSttOptions(raw)
}

class OpenAIAudioModelClient(val api: OpenAiApi, val ttsOptions: OpenAIAudioModelClientTtsOptions, val sttOptions: OpenAIAudioModelClientSttOptions, id: String) extends AudioModelClient {

  override def listVoices(raw: Boolean)(implicit ec: ExecutionContext): Future[Either[JsValue, List[AudioGenVoice]]] = {
    // TODO: those are not voices here, those are models
    Right(
      List(
        AudioGenVoice("tts-1", "tts-1"),
        AudioGenVoice("tts-1-hd", "tts-1-hd"),
        AudioGenVoice("gpt-4o-mini-tts", "gpt-4o-mini-tts")
      )
    ).vfuture
  }

  override def textToSpeech(opts: AudioModelClientTextToSpeechInputOptions, rawBody: JsObject)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, (Source[ByteString, _], String)]] = {
    val instructionsOpt: Option[String] = opts.instructions.orElse(ttsOptions.instructions)
    val responseFormatOpt: Option[String] = opts.responseFormat.orElse(ttsOptions.responseFormat)
    val speedOpt: Option[Double] = opts.speed.orElse(ttsOptions.speed)

    val body = Json.obj(
      "input" -> opts.input,
      "model" -> opts.model.getOrElse(ttsOptions.model).json,
      "voice" -> opts.voice.getOrElse(ttsOptions.voice).json,
    ).applyOnWithOpt(instructionsOpt) {
      case (obj, instructions) => obj ++ Json.obj("instructions" -> instructions)
    }.applyOnWithOpt(responseFormatOpt) {
      case (obj, responseFormat) => obj ++ Json.obj("response_format" -> responseFormat)
    }.applyOnWithOpt(speedOpt) {
      case (obj, speed) => obj ++ Json.obj("speed" -> speed)
    }

    api.rawCall("POST", "/audio/speech", body.some).map { response =>
      if (response.status == 200) {
        val contentType = response.contentType
        (response.bodyAsSource, contentType).right
      } else {
        Left(Json.obj("error" -> "Bad response", "body" -> s"Failed with status ${response.status}: ${response.body}"))
      }
    }
  }

  override def speechToText(opts: AudioModelClientSpeechToTextInputOptions, rawBody: JsObject)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, AudioTranscriptionResponse]] = {
    val model = opts.model.orElse(sttOptions.model)
    val language = opts.language.orElse(sttOptions.language)
    val prompt = opts.prompt.orElse(sttOptions.prompt)
    val responseFormat = opts.responseFormat.orElse(sttOptions.responseFormat)
    val temperature = opts.responseFormat.orElse(sttOptions.temperature)
    val parts = List(
        Multipart.FormData.BodyPart(
          "file",
          HttpEntity(ContentType.parse(opts.fileContentType).toOption.get, opts.fileLength, opts.file),
          Map("filename" -> opts.fileName.getOrElse("audio.mp3"))
        )
      ).applyOnWithOpt(model) {
        case (list, model) => list :+ Multipart.FormData.BodyPart(
          "model",
          HttpEntity(model.byteString),
        )
      }.applyOnWithOpt(language) {
        case (list, language) => list :+ Multipart.FormData.BodyPart(
          "language",
          HttpEntity(language.byteString),
        )
      }
      .applyOnWithOpt(responseFormat) {
        case (list, responseFormat) => list :+ Multipart.FormData.BodyPart(
          "response_format",
          HttpEntity(responseFormat.byteString),
        )
      }
      .applyOnWithOpt(prompt) {
        case (list, prompt) => list :+ Multipart.FormData.BodyPart(
          "prompt",
          HttpEntity(prompt.byteString),
        )
      }
      .applyOnWithOpt(temperature) {
        case (list, temperature) => list :+ Multipart.FormData.BodyPart(
          "temperature",
          HttpEntity(temperature.toString.byteString),
        )
      }
    val form = Multipart.FormData(parts: _*)
    api.rawCallForm("POST", "/audio/transcriptions", form).map { response =>
      if (response.status == 200) {
        AudioTranscriptionResponse(response.json.select("text").asString).right
      } else {
        Left(Json.obj("error" -> "Bad response", "body" -> s"Failed with status ${response.status}: ${response.body}"))
      }
    }
  }
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/////////                                     OpenAI Images Gen                                          ///////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

case class OpenAiImageModelClientOptions(raw: JsObject) {
  lazy val model: String = raw.select("model").asOpt[String].getOrElse("gpt-image-1")
  lazy val size: String = raw.select("size").asOpt[String].getOrElse("auto")
}

object OpenAiImageModelClientOptions {
  def fromJson(raw: JsObject): OpenAiImageModelClientOptions = OpenAiImageModelClientOptions(raw)
}

class OpenAiImageModelClient(val api: OpenAiApi, val options: OpenAiImageModelClientOptions, id: String) extends ImageModelClient {

  override def generate(promptInput: String, modelOpt: Option[String], imgSizeOpt: Option[String])(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ImagesGenResponse]] = {
    val finalModel: String = modelOpt.getOrElse(options.model)
    val finalSize: String = imgSizeOpt.getOrElse(options.size)
    api.rawCall("POST", "/images/generations", (options.raw ++
      Json.obj(
        "prompt" -> promptInput,
        "size" -> finalSize,
        "model" -> finalModel
      )).some).map { resp =>
      if (resp.status == 200) {
        Right(ImagesGenResponse(
          created = resp.json.select("created").asOpt[Long].getOrElse(-1L),
          images = resp.json.select("data").as[Seq[JsObject]].map(o => ImagesGen(o.select("b64_json").asOpt[String], o.select("revised_prompt").asOpt[String], o.select("url").asOpt[String])),
          metadata = finalModel.toLowerCase match {
            case "gpt-image-1" => ImagesGenResponseMetadata(
              totalTokens = resp.json.at("usage.total_tokens").asOpt[Long].getOrElse(-1L),
              tokenInput = resp.json.at("usage.input_tokens").asOpt[Long].getOrElse(-1L),
              tokenOutput = resp.json.at("usage.output_tokens").asOpt[Long].getOrElse(-1L),
              tokenText = resp.json.at("usage.input_tokens_details.text_tokens").asOpt[Long].getOrElse(-1L),
              tokenImage = resp.json.at("usage.input_tokens_details.image_tokens").asOpt[Long].getOrElse(-1L),
            ).some
            case _ => None
          }
        ))
      } else {
        Left(Json.obj("status" -> resp.status, "body" -> resp.json))
      }
    }
  }
}