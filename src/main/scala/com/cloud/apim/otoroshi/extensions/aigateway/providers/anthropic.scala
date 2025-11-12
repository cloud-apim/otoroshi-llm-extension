package com.cloud.apim.otoroshi.extensions.aigateway.providers

import akka.stream.scaladsl.{Framing, Source}
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway._
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AnthropicApiResponseChoiceMessageToolCall, GenericApiResponseChoiceMessageToolCall, LlmFunctions}
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}
import play.api.libs.ws.WSResponse

import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

case class AnthropicChatResponseChunkUsage(raw: JsObject) {
  lazy val input_tokens: Long = raw.select("input_tokens").asOptLong.getOrElse(0L)
  lazy val output_tokens: Long = raw.select("output_tokens").asOptLong.getOrElse(0L)
  lazy val reasoningTokens: Long = raw.at("reasoning_tokens").asOptLong.getOrElse(0L)
}

case class AnthropicChatResponseChunkChoice(raw: JsValue, role: String) {
  lazy val index: Option[Int] = raw.select("index").asOptInt
  lazy val typ: Option[String] = raw.at("content_block.type").asOptString.orElse(raw.at("delta.type").asOptString)
  lazy val content: Option[String] = raw.at("content_block.text").asOptString.orElse(raw.at("delta.text").asOptString)
  lazy val finish_reason: Option[String] = raw.at("delta.stop_reason").asOptString

  lazy val contentBlockObj: JsObject = raw.select("content_block").asObject

  lazy val tool_use = typ.contains("tool_use")
  lazy val input_json_delta = typ.contains("input_json_delta")
  lazy val stop_tool_use = finish_reason.contains("tool_use")

  lazy val partial_json: String = raw.at("delta.partial_json").asString
}

case class AnthropicApiResponseChunk(raw: JsValue, messageRef: Option[JsValue]) {
  lazy val typ: String = messageRef.flatMap(_.select("type").asOptString).getOrElse("--")
  lazy val id: String = messageRef.flatMap(_.select("id").asOptString).getOrElse("--")
  lazy val created: Long = messageRef.flatMap(_.select("created").asOptLong).getOrElse(0L)
  lazy val model: String = messageRef.flatMap(_.select("model").asOptString).getOrElse("--")
  lazy val role: String = messageRef.flatMap(_.select("role").asOptString).getOrElse("--")
  lazy val usage: Option[AnthropicChatResponseChunkUsage] = raw.select("usage").asOpt[JsObject].map { obj =>
    AnthropicChatResponseChunkUsage(obj)
  }
  lazy val choices: Seq[AnthropicChatResponseChunkChoice] = Seq(AnthropicChatResponseChunkChoice(raw, role))

  lazy val ztyp = raw.select("type").asString
  lazy val message_start: Boolean = ztyp == "message_start"
  lazy val content_block_start: Boolean = ztyp == "content_block_start"
  lazy val content_block_delta: Boolean = ztyp == "content_block_delta"
  lazy val content_block_stop: Boolean = ztyp == "content_block_stop"
  lazy val message_delta: Boolean = ztyp == "message_delta"
  lazy val message_stop: Boolean = ztyp == "message_stop"
}

case class AnthropicChatResponseChunkChoiceDeltaToolCall(raw: JsValue) {

}

case class AnthropicApiResponse(status: Int, headers: Map[String, String], body: JsValue) {
  def json: JsValue = Json.obj(
    "status" -> status,
    "headers" -> headers,
    "body" -> body,
  )
  def finishBecauseOfToolCalls: Boolean = {
    body.select("content").asOpt[Seq[JsObject]].exists(seq => seq.exists(o => o.select("type").asOptString.contains("tool_use")))
  }
}
object AnthropicModels {
  val CLAUDE_3_5_SONNET = "claude-3-5-sonnet-20240620"
  val CLAUDE_3_OPUS = "claude-3-opus-20240229"
  val CLAUDE_3_SONNET = "claude-3-sonnet-20240229"
  val CLAUDE_3_HAIKU = "claude-3-haiku-20240307"
}
object AnthropicApi {
  val baseUrl = "https://api.anthropic.com"
}
class AnthropicApi(baseUrl: String = AnthropicApi.baseUrl, token: String, timeout: FiniteDuration = 3.minutes, env: Env) extends ApiClient[AnthropicApiResponse, AnthropicApiResponseChunk] {

  val providerName = "anthropic"
  override def supportsTools: Boolean = true
  override def supportsCompletion: Boolean = true
  override def supportsStreaming: Boolean = true

  def rawCall(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[WSResponse] = {
    val url = s"${baseUrl}${path}"
    ProviderHelpers.logCall("Anthropic", method, url, body)(env)
    env.Ws
      .url(url)
      .withHttpHeaders(
        s"x-api-key" -> token,
        "anthropic-version" -> "2023-06-01",
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

  override def call(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[Either[JsValue, AnthropicApiResponse]] = {
    rawCall(method, path, body)
      .map(r => ProviderHelpers.wrapResponse("Anthropic", r, env) { resp =>
        AnthropicApiResponse(resp.status, resp.headers.mapValues(_.last), resp.json)
      })
  }

  def raw_stream(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[Either[JsValue, (Source[AnthropicApiResponseChunk, _], WSResponse)]] = {
    val url = s"${baseUrl}${path}"
    ProviderHelpers.logStream(providerName, method, url, body)(env)
    val messageRef = new AtomicReference[JsValue]()
    env.Ws
      .url(s"${baseUrl}${path}")
      .withHttpHeaders(
        s"x-api-key" -> token,
        "anthropic-version" -> "2023-06-01",
        "Accept" -> "application/json",
      ).applyOnWithOpt(body) {
        case (builder, body) => builder
          .addHttpHeaders("Content-Type" -> "application/json")
          .withBody(body.asObject ++ Json.obj(
            "stream" -> true,
          ))
      }
      .withMethod(method)
      .withRequestTimeout(timeout)
      .stream()
      .map(r => ProviderHelpers.wrapStreamResponse(providerName, r, env) { resp =>
        (resp.bodyAsSource
          .via(Framing.delimiter(ByteString("\n\n"), Int.MaxValue, false))
          .map(_.utf8String)
          .flatMapConcat { str =>
            Source(str.split("\n").toList)
          }
          .filter(_.startsWith("data: "))
          .map(_.replaceFirst("data: ", "").trim())
          .filter(_.nonEmpty)
          .map(str => Json.parse(str))
          .map { json =>
            if (json.select("type").asOptString.contains("message_start")) {
              messageRef.set(json)
            }
            json
          }
          .filterNot(_.select("type").asOptString.contains("ping"))
          // .filterNot(_.select("type").asOptString.contains("content_block_stop"))
          .takeWhile(!_.select("type").asOptString.contains("message_stop"), true)
          .map(json => AnthropicApiResponseChunk(json, Option(messageRef.get())))
          , resp)
      })
  }

  override def stream(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[Either[JsValue, (Source[AnthropicApiResponseChunk, _], WSResponse)]] = {
    raw_stream(method, path, body)
      .map {
        case Left(e) => Left(e)
        case Right((source, resp)) => {
          val nSource = source
            .filterNot(_.content_block_stop)
            .filterNot(_.message_stop)
          Right((nSource, resp))
        }
      }
  }

  override def callWithToolSupport(method: String, path: String, body: Option[JsValue], mcpConnectors: Seq[String], attrs: TypedMap, nameToFunction: Map[String, String], maxCalls: Int, currentCallCounter: Int)(implicit ec: ExecutionContext): Future[Either[JsValue, AnthropicApiResponse]] = {
    // TODO: accumulate consumptions ???
    if (body.flatMap(_.select("tools").asOpt[JsArray]).exists(_.value.nonEmpty)) {
      call(method, path, body).flatMap {
        case Left(err) => err.leftf
        case Right(resp) if resp.finishBecauseOfToolCalls => {
          body match {
            case None => resp.rightf
            case Some(body) => {
              val messages = body.select("messages").asOpt[Seq[JsObject]].getOrElse(Seq.empty) //.map(v => v.flatMap(o => ChatMessage.format.reads(o).asOpt)).getOrElse(Seq.empty)
              val toolCalls = resp.body.select("content").as[Seq[JsObject]].filter(o => o.select("type").asOptString.contains("tool_use"))
              LlmFunctions.callToolsAnthropic(toolCalls.map(tc => AnthropicApiResponseChoiceMessageToolCall(tc)), mcpConnectors, providerName, attrs, nameToFunction)(ec, env)
                .flatMap { callResps =>
                  val newMessages: Seq[JsValue] = messages ++ callResps
                  val newBody = body.asObject ++ Json.obj("messages" -> JsArray(newMessages))
                  callWithToolSupport(method, path, newBody.some, mcpConnectors, attrs, nameToFunction, maxCalls, currentCallCounter + 1)
                }
            }
          }
        }
        case Right(resp) =>
          resp.rightf
      }
    } else {
      call(method, path, body)
    }
  }

  override def streamWithToolSupport(method: String, path: String, body: Option[JsValue], mcpConnectors: Seq[String], attrs: TypedMap, nameToFunction: Map[String, String], maxCalls: Int, currentCallCounter: Int)(implicit ec: ExecutionContext): Future[Either[JsValue, (Source[AnthropicApiResponseChunk, _], WSResponse)]] = {
    if (body.flatMap(_.select("tools").asOpt[JsArray]).exists(_.value.nonEmpty)) {
      val messages = body.get.select("messages").asOpt[Seq[JsObject]].getOrElse(Seq.empty) //.map(v => v.flatMap(o => ChatMessage.format.reads(o).asOpt)).getOrElse(Seq.empty)
      raw_stream(method, path, body).flatMap {
        case Left(err) => err.leftf
        case Right(res) => {
          var isToolCall = false
          var isToolCallEnded = false
          var toolCalls: Seq[AnthropicChatResponseChunkChoiceDeltaToolCall] = Seq.empty
          var toolCallArgs: scala.collection.mutable.ArraySeq[String] = scala.collection.mutable.ArraySeq.empty
          // var toolCallUsage: AnthropicChatResponseChunkUsage = null
          var index: Int = 0
          val newSource = res._1.flatMapConcat { chunk =>
            // println(s"chunk: ${chunk.raw.prettify}")
            if (!isToolCall &&  chunk.choices.exists(_.tool_use)) {
              // println("start tool_user")
              isToolCall = true
              toolCalls = toolCalls :+ AnthropicChatResponseChunkChoiceDeltaToolCall(chunk.choices.head.contentBlockObj)
              toolCallArgs = scala.collection.mutable.ArraySeq((0 to toolCallArgs.size).map(_ => ""): _*)
              Source.empty
            } else if (isToolCall && !isToolCallEnded) {
              if (chunk.choices.head.stop_tool_use) {
                // println("stopping tool_user")
                isToolCallEnded = true
              } else {
                if (chunk.choices.head.input_json_delta) {
                  // println("agg input")
                  val tc = chunk.choices.head
                  // val index: Int = tc.index.getOrElse(-1)
                  val arg = tc.partial_json
                  if (index >= toolCallArgs.size) {
                    toolCallArgs = toolCallArgs :+ ""
                  }
                  // if (tc.function.hasName && !toolCalls.exists(t => t.function.hasName && t.function.name == tc.function.name)) {
                  //   toolCalls = toolCalls :+ tc
                  // }
                  toolCallArgs.update(index, toolCallArgs.apply(index) + arg)
                } else {
                  //println("no agg")
                  if (chunk.content_block_stop) {
                    index = index + 1
                  }
                }
              }
              Source.empty
            } else if (isToolCall && isToolCallEnded && chunk.message_stop) {
              //println("end tool_use 1")
              //toolCallUsage = chunk.usage.get
              val calls = toolCalls.zipWithIndex.map {
                case (toolCall, idx) =>
                  //println(s"prep call ${idx} - ${toolCall.raw.prettify} - ${toolCallArgs(idx)}")
                  val a = AnthropicApiResponseChoiceMessageToolCall(toolCall.raw.asObject.deepMerge(Json.obj("input" -> toolCallArgs(idx).parseJson)))
                  //println(s"call ${idx}: ${a.raw.prettify}")
                  a
              }
              //println("calling !!!")
              val a: Future[Either[JsValue, (Source[AnthropicApiResponseChunk, _], WSResponse)]] = LlmFunctions.callToolsAnthropic(calls, mcpConnectors, providerName, attrs, nameToFunction)(ec, env)
                .flatMap { callResps =>
                  // val newMessages: Seq[JsValue] = messages.map(_.json) ++ callResps
                  val newMessages: Seq[JsValue] = messages ++ callResps
                  val newBody = body.get.asObject ++ Json.obj("messages" -> JsArray(newMessages))
                  streamWithToolSupport(method, path, newBody.some, mcpConnectors, attrs, nameToFunction, maxCalls, currentCallCounter + 1)
                }
              Source.future(a).flatMapConcat {
                case Left(err) => Source.failed(new Throwable(err.stringify))
                case Right(tuple) => tuple._1
              }
            } else {
              // println(s"nothing: ${chunk.typ} ${chunk.raw.select("type").asString} ${isToolCall} && ${isToolCallEnded} && ${chunk.message_stop} - ${isToolCall && isToolCallEnded && chunk.message_stop}")
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

object AnthropicChatClientOptions {
  def fromJson(json: JsValue): AnthropicChatClientOptions = {
    AnthropicChatClientOptions(
      stream = json.select("stream").asOptBoolean,
      model = json.select("model").asOpt[String].getOrElse(OpenAiModels.GPT_3_5_TURBO),
      metadata = json.select("metadata").asOpt[JsObject],
      stop_sequences = json.select("stop_sequences").asOpt[Seq[String]],
      tools = json.select("tools").asOpt[Seq[JsValue]],
      tool_choice = json.select("tool_choice").asOpt[Seq[JsValue]],
      max_tokens = json.select("max_tokens").asOpt[Int].getOrElse(1024),
      temperature = json.select("temperature").asOpt[Float],
      topP = json.select("top_p").asOpt[Float],
      topK = json.select("top_k").asOpt[Int],
      system = json.select("system").asOpt[String],
      allowConfigOverride = json.select("allow_config_override").asOptBoolean.getOrElse(true),
      wasmTools = json.select("wasm_tools").asOpt[Seq[String]].filter(_.nonEmpty).orElse(json.select("tool_functions").asOpt[Seq[String]]).getOrElse(Seq.empty),
      mcpConnectors = json.select("mcp_connectors").asOpt[Seq[String]].getOrElse(Seq.empty),
      mcpIncludeFunctions = json.select("mcp_include_functions").asOpt[Seq[String]].getOrElse(Seq.empty),
      mcpExcludeFunctions = json.select("mcp_exclude_functions").asOpt[Seq[String]].getOrElse(Seq.empty),
    )
  }
}

case class AnthropicChatClientOptions(
  model: String = AnthropicModels.CLAUDE_3_HAIKU,
  max_tokens: Int = 1024,
  metadata: Option[JsObject] = None,
  stop_sequences: Option[Seq[String]] = None,
  stream: Option[Boolean] = Some(false),
  system: Option[String] = None,
  temperature: Option[Float] = None,
  tools: Option[Seq[JsValue]] = None,
  tool_choice: Option[Seq[JsValue]] =  None,
  topK: Option[Int] = None,
  topP: Option[Float] = None,
  allowConfigOverride: Boolean = true,
  wasmTools: Seq[String] = Seq.empty,
  mcpConnectors: Seq[String] = Seq.empty,
  mcpIncludeFunctions: Seq[String] = Seq.empty,
  mcpExcludeFunctions: Seq[String] = Seq.empty,
  
) extends ChatOptions {

  lazy val wasmToolsNoInline: Seq[String] = wasmTools.filterNot(_.startsWith("__inline_"))

  lazy val wasmToolsInline: Seq[String] = wasmTools.filter(_.startsWith("__inline_"))

  override def json: JsObject = Json.obj(
    "model" -> model,
    "max_tokens" -> max_tokens,
    "stream" -> stream,
    "system" -> system,
    "tools" -> tools,
    "tool_choice" -> tool_choice,
    "metadata" -> metadata,
    "stop_sequences" -> stop_sequences,
    "allow_config_override" -> allowConfigOverride,
    "tool_functions" -> JsArray(wasmTools.map(_.json)),
    "mcp_connectors" -> JsArray(mcpConnectors.map(_.json)),
    "mcp_include_functions" -> JsArray(mcpIncludeFunctions.map(_.json)),
    "mcp_exclude_functions" -> JsArray(mcpExcludeFunctions.map(_.json)),
  )
  .applyOnWithOpt(temperature) {
    case (obj, _) if topK.isDefined || topP.isDefined => obj
    case (obj, temperature) => obj ++ Json.obj("temperature" -> temperature)
  }
  .applyOnWithOpt(topK) {
    case (obj, k) => obj ++ Json.obj("top_k" -> k)
  }
  .applyOnWithOpt(topP) {
    case (obj, p) => obj ++ Json.obj("top_p" -> p)
  }

  def jsonForCall: JsObject = optionsCleanup(json - "wasm_tools" - "tool_functions" - "mcp_connectors" - "allow_config_override" - "mcp_include_functions" - "mcp_exclude_functions")
}

class AnthropicChatClient(api: AnthropicApi, options: AnthropicChatClientOptions, id: String) extends ChatClient {

  // supports tools: true
  // supports streaming: true

  override def supportsTools: Boolean = api.supportsTools
  override def supportsStreaming: Boolean = api.supportsStreaming
  override def supportsCompletion: Boolean = false

  override def computeModel(payload: JsValue): Option[String] = payload.select("model").asOpt[String].orElse(options.model.some)

  override def listModels(raw: Boolean, attrs: TypedMap)(implicit ec: ExecutionContext): Future[Either[JsValue, List[String]]] = {
    api.rawCall("GET", "/v1/models", None).map { resp =>
      if (resp.status == 200) {
        Right(resp.json.select("data").as[List[JsObject]].map(obj => obj.select("id").asString))
      } else {
        Left(Json.obj("error" -> s"bad response code: ${resp.status}"))
      }
    }
  }

  override def call(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val obody = originalBody.asObject - "messages" - "provider"
    val mergedOptions = if (options.allowConfigOverride) options.jsonForCall.deepMerge(obody) else options.jsonForCall
    val finalModel = mergedOptions.select("model").asOptString.orElse(computeModel(mergedOptions)).getOrElse("--")
    val (system, otherMessages) = prompt.messages.partition(_.isSystem)
    val messages = prompt.copy(messages = otherMessages).jsonWithFlavor(ChatMessageContentFlavor.Anthropic)
    val systemMessages = JsArray(system.map(_.json(ChatMessageContentFlavor.Anthropic)))
    val startTime = System.currentTimeMillis()
    val hasToolsInRequest = obody.select("tools").asOpt[JsArray].exists(_.value.nonEmpty)
    val callF = if (!hasToolsInRequest && api.supportsTools && (options.wasmTools.nonEmpty || options.mcpConnectors.nonEmpty)) {
      val tools = LlmFunctions.toolsAnthropicWithInline(options.wasmToolsNoInline, options.wasmToolsInline, options.mcpConnectors, options.mcpIncludeFunctions, options.mcpExcludeFunctions, attrs)
      val nameToFunction = LlmFunctions.nameToFunction(options.wasmToolsNoInline)
      api.callWithToolSupport("POST", "/v1/messages", Some(mergedOptions ++ tools ++ Json.obj("messages" -> messages, "system" -> systemMessages)), options.mcpConnectors, attrs, nameToFunction, options.maxFunctionCalls, 0)
    } else {
      api.call("POST", "/v1/messages", Some(mergedOptions ++ Json.obj("messages" -> messages, "system" -> systemMessages)))
    }
    callF.map {
      case Left(err) => err.left
      case Right(resp) =>
        val usage = ChatResponseMetadata(
          ChatResponseMetadataRateLimit(
            requestsLimit = resp.headers.getIgnoreCase("anthropic-ratelimit-requests-limit").map(_.toLong).getOrElse(-1L),
            requestsRemaining = resp.headers.getIgnoreCase("anthropic-ratelimit-requests-remaining").map(_.toLong).getOrElse(-1L),
            tokensLimit = resp.headers.getIgnoreCase("anthropic-ratelimit-tokens-limit").map(_.toLong).getOrElse(-1L),
            tokensRemaining = resp.headers.getIgnoreCase("anthropic-ratelimit-tokens-remaining").map(_.toLong).getOrElse(-1L),
          ),
          ChatResponseMetadataUsage(
            promptTokens = resp.body.select("usage").select("input_tokens").asOpt[Long].getOrElse(-1L),
            generationTokens = resp.body.select("usage").select("output_tokens").asOpt[Long].getOrElse(-1L),
            reasoningTokens = resp.body.at("usage.reasoning_tokens").asOpt[Long].getOrElse(-1L),
          ),
          None
        )
        val duration: Long = System.currentTimeMillis() - startTime //resp.headers.getIgnoreCase("anthropic-processing-ms").map(_.toLong).getOrElse(0L)
        val slug = Json.obj(
          "provider_kind" -> "anthropic",
          "provider" -> id,
          "duration" -> duration,
          "model" -> finalModel.json,
          "rate_limit" -> usage.rateLimit.json,
          "usage" -> usage.usage.json,
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
        val role = resp.body.select("role").asOpt[String].getOrElse("assistant")
        val messages = resp.body.select("content").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map { obj =>
          val content = obj.select("text").asOpt[String].getOrElse("")
          ChatGeneration(ChatMessage.output(role, content, None, obj))
        }
        Right(ChatResponse(messages, usage, resp.body))
    }
  }

  override def stream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    val body = originalBody.asObject - "messages" - "provider"
    val mergedOptions = if (options.allowConfigOverride) options.jsonForCall.deepMerge(body) else options.jsonForCall
    val finalModel = mergedOptions.select("model").asOptString.orElse(computeModel(mergedOptions)).getOrElse("--")
    val (system, otherMessages) = prompt.messages.partition(_.isSystem)
    val messages = prompt.copy(messages = otherMessages).jsonWithFlavor(ChatMessageContentFlavor.Anthropic)
    val systemMessages = JsArray(system.map(_.json(ChatMessageContentFlavor.Anthropic)))
    val startTime = System.currentTimeMillis()
    val hasToolsInRequest = body.select("tools").asOpt[JsArray].exists(_.value.nonEmpty)
    val callF = if (!hasToolsInRequest && api.supportsTools && (options.wasmTools.nonEmpty || options.mcpConnectors.nonEmpty)) {
      val tools = LlmFunctions.toolsAnthropic(options.wasmTools, options.mcpConnectors, options.mcpIncludeFunctions, options.mcpExcludeFunctions)
      val nameToFunction = LlmFunctions.nameToFunction(options.wasmToolsNoInline)
      api.streamWithToolSupport("POST", "/v1/messages", Some(mergedOptions ++ tools ++ Json.obj("messages" -> messages, "system" -> systemMessages)), options.mcpConnectors, attrs, nameToFunction, options.maxFunctionCalls, 0)
    } else {
      api.stream("POST", "/v1/messages", Some(mergedOptions ++ Json.obj("messages" -> messages, "system" -> systemMessages)))
    }
    callF.map {
      case Left(err) => err.left
      case Right((source, resp)) =>
        source.filterNot { chunk =>
          if (chunk.usage.nonEmpty) {
            val usage = ChatResponseMetadata(
              ChatResponseMetadataRateLimit(
                requestsLimit = resp.header("x-ratelimit-limit-requests").map(_.toLong).getOrElse(-1L),
                requestsRemaining = resp.header("x-ratelimit-remaining-requests").map(_.toLong).getOrElse(-1L),
                tokensLimit = resp.header("x-ratelimit-limit-tokens").map(_.toLong).getOrElse(-1L),
                tokensRemaining = resp.header("x-ratelimit-remaining-tokens").map(_.toLong).getOrElse(-1L),
              ),
              ChatResponseMetadataUsage(
                promptTokens = chunk.usage.map(_.input_tokens).getOrElse(-1L),
                generationTokens = chunk.usage.map(_.output_tokens).getOrElse(-1L),
                reasoningTokens = chunk.usage.map(_.reasoningTokens).getOrElse(-1L),
              ),
              None
            )
            val duration: Long = System.currentTimeMillis() - startTime //resp.header("openai-processing-ms").map(_.toLong).getOrElse(0L)
            val slug = Json.obj(
              "provider_kind" -> "anthropic",
              "provider" -> id,
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
                  choice.content
                ),
                finishReason = choice.finish_reason
              )
            }
          )
        }.right
    }
  }
}

