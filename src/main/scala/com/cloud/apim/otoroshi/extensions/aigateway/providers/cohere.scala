package com.cloud.apim.otoroshi.extensions.aigateway.providers

import akka.stream.scaladsl.{Framing, Source}
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway._
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{GenericApiResponseChoiceMessageToolCall, LlmFunctions}
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}
import play.api.libs.ws.WSResponse

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}


case class CohereChatResponseChunkUsage(raw: JsObject) {
  lazy val input_tokens: Long = raw.select("input_tokens").asOptLong.getOrElse(0L)
  lazy val output_tokens: Long = raw.select("output_tokens").asOptLong.getOrElse(0L)
  lazy val reasoningTokens: Long = raw.select("reasoning_tokens").asOptLong.getOrElse(0L)
}

case class CohereChatResponseChunkChoiceDeltaMessageContentFunction(raw: JsValue) {
  lazy val name: String = raw.select("name").asString
  lazy val arguments: String = raw.select("arguments").asString
}

case class CohereChatResponseChunkChoiceDeltaMessageToolCall(raw: JsValue) {
  lazy val id = raw.select("id").asString
  lazy val typ = raw.select("type").asString
  lazy val function = CohereChatResponseChunkChoiceDeltaMessageContentFunction(raw.select("function").asObject)
}

case class CohereChatResponseChunkChoiceDeltaMessageContent(raw: JsValue) {
  lazy val typ = raw.select("type").asString
  lazy val text = raw.select("text").asString
}

case class CohereChatResponseChunkChoiceDeltaMessage(raw: JsValue) {
  lazy val content: CohereChatResponseChunkChoiceDeltaMessageContent = CohereChatResponseChunkChoiceDeltaMessageContent(raw.select("content").asObject)
  lazy val tool_calls: CohereChatResponseChunkChoiceDeltaMessageToolCall = CohereChatResponseChunkChoiceDeltaMessageToolCall(raw.select("tool_calls").asObject)
}

case class CohereChatResponseChunkChoiceDelta(raw: JsValue) {
  lazy val message: CohereChatResponseChunkChoiceDeltaMessage = CohereChatResponseChunkChoiceDeltaMessage(raw.select("message").asObject)
}

case class CohereChatResponseChunkChoice(raw: JsValue, role: String) {
  lazy val index: Option[Int] = raw.select("index").asOptInt
  lazy val content: Option[String] = raw.select("delta").select("message").select("content").select("text").asOptString
  lazy val finish_reason: Option[String] = raw.at("delta.finish_reason").asOptString
  lazy val delta: Option[CohereChatResponseChunkChoiceDelta] = raw.select("delta").asOpt[JsObject].map(CohereChatResponseChunkChoiceDelta.apply)
}

case class CohereAiApiResponseChunk(raw: JsValue, messageStartRef: Option[JsValue], messageEndRef: Option[JsValue]) {
  lazy val id: String = messageStartRef.flatMap(_.select("id").asOptString).getOrElse("--")
  lazy val role: String = messageStartRef.flatMap(_.at("delta.message.role").asOptString).getOrElse("--")
  lazy val usage: Option[CohereChatResponseChunkUsage] = messageEndRef.flatMap(_.at("delta.usage").asOpt[JsObject].map { obj =>
    CohereChatResponseChunkUsage(obj)
  })
  lazy val choices: Seq[CohereChatResponseChunkChoice] = Seq(CohereChatResponseChunkChoice(raw, role))
  lazy val typ: String = raw.select("type").asString

  lazy val tool_call_start = typ == "tool-call-start"
  lazy val tool_call_end = typ == "tool-call-end"
  lazy val tool_call_delta = typ == "tool-call-delta"
  lazy val message_start = typ == "message-start"
  lazy val message_end = typ == "message-end"
}

case class CohereAiApiResponse(status: Int, headers: Map[String, String], body: JsValue) {
  def json: JsValue = Json.obj(
    "status" -> status,
    "headers" -> headers,
    "body" -> body,
  )
  lazy val finishBecauseOfToolCalls: Boolean = body.select("finish_reason").asOptString.contains("TOOL_CALL")
  lazy val toolCalls: Seq[OpenAiApiResponseChoiceMessageToolCall] = body.at("message.tool_calls").asOpt[Seq[JsObject]].map(seq => seq.map(o => OpenAiApiResponseChoiceMessageToolCall(o))).getOrElse(Seq.empty)
}
object CohereAiModels {
  val DEFAULT_COHERE_MODEL = "command-r-plus-08-2024"
}

object CohereAiApi {
  val baseUrl = "https://api.cohere.com"
}

class CohereAiApi(baseUrl: String = CohereAiApi.baseUrl, token: String, timeout: FiniteDuration = 3.minutes, env: Env) extends ApiClient[CohereAiApiResponse, CohereAiApiResponseChunk] {

  val providerName = "cohere"
  override def supportsTools: Boolean = true
  override def supportsCompletion: Boolean = false
  override def supportsStreaming: Boolean = true

  def rawCall(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[WSResponse] = {
    val url = s"${baseUrl}${path}"
    ProviderHelpers.logCall("Cohere", method, url, body)(env)
    env.Ws
      .url(url)
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

  def call(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[Either[JsValue, CohereAiApiResponse]] = {
    rawCall(method, path, body)
      .map(r => ProviderHelpers.wrapResponse("Cohere", r, env) { resp =>
        CohereAiApiResponse(resp.status, resp.headers.mapValues(_.last), resp.json)
      })
  }

  override def stream(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[Either[JsValue, (Source[CohereAiApiResponseChunk, _], WSResponse)]] = {
    val url = s"${baseUrl}${path}"
    ProviderHelpers.logStream(providerName, method, url, body)(env)
    val messageStartRef = new AtomicReference[JsValue]()
    val messageEndRef = new AtomicReference[JsValue]()
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
          ))
      }
      .withMethod(method)
      .withRequestTimeout(timeout)
      .stream()
      .map(r => ProviderHelpers.wrapStreamResponse(providerName, r, env) { resp =>
        (resp.bodyAsSource
          .via(Framing.delimiter(ByteString("\n"), Int.MaxValue, false))
          .map(_.utf8String)
          .map(_.trim())
          .filter(_.nonEmpty)
          .map(str => Json.parse(str))
          .map { json =>
            if (json.select("type").asOptString.contains("message-start")) {
              messageStartRef.set(json)
            }
            if (json.select("type").asOptString.contains("message-end")) {
              messageEndRef.set(json)
            }
            json
          }
          .filterNot(_.select("type").asOptString.contains("tool-plan-delta"))
          .takeWhile(!_.select("type").asOptString.contains("message-end"), inclusive = true)
          .map(json => CohereAiApiResponseChunk(json, Option(messageStartRef.get()), Option(messageEndRef.get())))
          , resp)
      })
  }

  override def callWithToolSupport(method: String, path: String, body: Option[JsValue], mcpConnectors: Seq[String], attrs: TypedMap)(implicit ec: ExecutionContext): Future[Either[JsValue, CohereAiApiResponse]] = {
    // TODO: accumulate consumptions ???
    if (body.flatMap(_.select("tools").asOpt[JsArray]).exists(_.value.nonEmpty)) {
      val fmap: Map[String, String] = body.flatMap(_.select("fmap").asOpt[Map[String, String]]).getOrElse(Map.empty)
      call(method, path, body.map(_.asObject - "fmap")).flatMap {
        case Left(err) => err.leftf
        case Right(resp) if resp.finishBecauseOfToolCalls => {
          body match {
            case None => resp.rightf
            case Some(body) => {
              val messages = body.select("messages").asOpt[Seq[JsObject]].getOrElse(Seq.empty) //.map(v => v.flatMap(o => ChatMessage.format.reads(o).asOpt)).getOrElse(Seq.empty)
              val toolCalls = resp.toolCalls
              LlmFunctions.callToolsCohere(toolCalls.map(tc => GenericApiResponseChoiceMessageToolCall(tc.raw)), mcpConnectors, providerName, fmap, attrs)(ec, env)
                .flatMap { callResps =>
                  // val newMessages: Seq[JsValue] = messages.map(_.json) ++ callResps
                  val newMessages: Seq[JsValue] = messages ++ callResps
                  val newBody = body.asObject ++ Json.obj("messages" -> JsArray(newMessages))
                  callWithToolSupport(method, path, newBody.some, mcpConnectors, attrs)
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

  override def streamWithToolSupport(method: String, path: String, body: Option[JsValue], mcpConnectors: Seq[String], attrs: TypedMap)(implicit ec: ExecutionContext): Future[Either[JsValue, (Source[CohereAiApiResponseChunk, _], WSResponse)]] = {
    if (body.flatMap(_.select("tools").asOpt[JsArray]).exists(_.value.nonEmpty)) {
      val fmap: Map[String, String] = body.flatMap(_.select("fmap").asOpt[Map[String, String]]).getOrElse(Map.empty)
      val messages = body.get.select("messages").asOpt[Seq[JsObject]].getOrElse(Seq.empty) //.map(v => v.flatMap(o => ChatMessage.format.reads(o).asOpt)).getOrElse(Seq.empty)
      stream(method, path, body.map(_.asObject - "fmap")).flatMap {
        case Left(err) => err.leftf
        case Right(res) => {
          var index: Int = 0
          var isToolCall = false
          var isToolCallEnded = false
          var toolCalls: Seq[CohereChatResponseChunkChoiceDeltaMessageToolCall] = Seq.empty
          var toolCallArgs: scala.collection.mutable.ArraySeq[String] = scala.collection.mutable.ArraySeq.empty
          // var toolCallUsage: OpenAiChatResponseChunkUsage = null
          val newSource = res._1.flatMapConcat { chunk =>
            Source.single(chunk)
            if (!isToolCall && chunk.tool_call_start) {
              isToolCall = true
              toolCalls = toolCalls :+ chunk.choices.head.delta.get.message.tool_calls
              toolCallArgs = scala.collection.mutable.ArraySeq((0 to toolCallArgs.size).map(_ => ""): _*)
              Source.empty
            } else if (isToolCall && !isToolCallEnded) {
              if (chunk.tool_call_end) { //chunk.choices.head.finish_reason.contains("TOOL_CALL")) {
                isToolCallEnded = true
              } else {
                if (chunk.tool_call_delta) {
                  val tc = chunk.choices.head.delta.head.message.tool_calls
                  val arg = tc.function.arguments
                  if (index >= toolCallArgs.size) {
                    toolCallArgs = toolCallArgs :+ ""
                  }
                  // if (tc.function.hasName && !toolCalls.exists(t => t.function.hasName && t.function.name == tc.function.name)) {
                  //   toolCalls = toolCalls :+ tc
                  // }
                  toolCallArgs.update(index, toolCallArgs.apply(index) + arg)
                } else {
                  index = index + 1
                }
              }
              Source.empty
            } else if (isToolCall && isToolCallEnded) {
              // toolCallUsage = chunk.usage.get
              val calls = toolCalls.zipWithIndex.map {
                case (toolCall, idx) =>
                  GenericApiResponseChoiceMessageToolCall(toolCall.raw.asObject.deepMerge(Json.obj("function" -> Json.obj("arguments" -> toolCallArgs(idx)))))
              }
              val a: Future[Either[JsValue, (Source[CohereAiApiResponseChunk, _], WSResponse)]] = LlmFunctions.callToolsCohere(calls, mcpConnectors, providerName, fmap, attrs)(ec, env)
                .flatMap { callResps =>
                  // val newMessages: Seq[JsValue] = messages.map(_.json) ++ callResps
                  val newMessages: Seq[JsValue] = messages ++ callResps
                  val newBody = body.get.asObject ++ Json.obj("messages" -> JsArray(newMessages))
                  streamWithToolSupport(method, path, newBody.some, mcpConnectors, attrs)
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

object CohereAiChatClientOptions {
  def fromJson(json: JsValue): CohereAiChatClientOptions = {
    CohereAiChatClientOptions(
      model = json.select("model").asOpt[String].getOrElse(CohereAiModels.DEFAULT_COHERE_MODEL),
      max_tokens = json.select("max_tokens").asOpt[Int],
      max_input_tokens = json.select("max_input_tokens").asOpt[Int],
      k = json.select("k").asOpt[Int],
      p = json.select("p").asOpt[Double],
      temperature = json.select("temperature").asOpt[Float].getOrElse(0.3f),
      seed = json.select("seed").asOpt[Int],
      allowConfigOverride = json.select("allow_config_override").asOptBoolean.getOrElse(true),
      tools = json.select("tools").asOpt[Seq[JsValue]],
      tool_choice = json.select("tool_choice").asOpt[Seq[JsValue]],
      wasmTools = json.select("wasm_tools").asOpt[Seq[String]].filter(_.nonEmpty).orElse(json.select("tool_functions").asOpt[Seq[String]]).getOrElse(Seq.empty),
      mcpConnectors = json.select("mcp_connectors").asOpt[Seq[String]].getOrElse(Seq.empty),
      mcpIncludeFunctions = json.select("mcp_include_functions").asOpt[Seq[String]].getOrElse(Seq.empty),
      mcpExcludeFunctions = json.select("mcp_exclude_functions").asOpt[Seq[String]].getOrElse(Seq.empty),
    )
  }
}

case class CohereAiChatClientOptions(
  model: String = CohereAiModels.DEFAULT_COHERE_MODEL,
  max_tokens: Option[Int] = None,
  max_input_tokens: Option[Int] = None,
  stream: Boolean = false,
  temperature: Float = 0.3f,
  k: Option[Int] = Some(0),
  p: Option[Double] = Some(1.0),
  frequency_penalty: Option[Double] = None,
  seed: Option[Int] = None,
  allowConfigOverride: Boolean = true,
  tools: Option[Seq[JsValue]] = None,
  tool_choice: Option[Seq[JsValue]] =  None,
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
    "max_input_tokens" -> max_input_tokens,
    "stream" -> stream,
    "temperature" -> temperature,
    "frequency_penalty" -> frequency_penalty,
    "seed" -> seed,
    "allow_config_override" -> allowConfigOverride,
    "tools" -> tools,
    "tool_choice" -> tool_choice,
    "tool_functions" -> JsArray(wasmTools.map(_.json)),
    "mcp_connectors" -> JsArray(mcpConnectors.map(_.json)),
    "mcp_include_functions" -> JsArray(mcpIncludeFunctions.map(_.json)),
    "mcp_exclude_functions" -> JsArray(mcpExcludeFunctions.map(_.json)),
  )
  .applyOnWithOpt(k) {
    case (obj, k) => obj ++ Json.obj("k" -> k)
  }
  .applyOnWithOpt(p) {
    case (obj, p) => obj ++ Json.obj("p" -> p)
  }

  def jsonForCall: JsObject = optionsCleanup(json - "wasm_tools" - "tool_functions" - "mcp_connectors" - "allow_config_override" - "mcp_include_functions" - "mcp_exclude_functions")
}

class CohereAiChatClient(api: CohereAiApi, options: CohereAiChatClientOptions, id: String) extends ChatClient {

  // TODO: supports tools: true
  // TODO: supports streaming: true

  override def supportsTools: Boolean = api.supportsTools
  override def supportsStreaming: Boolean = api.supportsStreaming
  override def supportsCompletion: Boolean = api.supportsCompletion

  override def computeModel(payload: JsValue): Option[String] = payload.select("model").asOpt[String].orElse(options.model.some)

  override def listModels(raw: Boolean, attrs: TypedMap)(implicit ec: ExecutionContext): Future[Either[JsValue, List[String]]] = {
    api.rawCall("GET", "/v1/models", None).map { resp =>
      if (resp.status == 200) {
        Right(resp.json.select("models").as[List[JsObject]].map(obj => obj.select("name").asString))
      } else {
        Left(Json.obj("error" -> s"bad response code: ${resp.status}"))
      }
    }
  }

  override def call(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val obody = originalBody.asObject - "messages" - "provider"
    val mergedOptions = if (options.allowConfigOverride) options.jsonForCall.deepMerge(obody) else options.jsonForCall
    val finalModel = mergedOptions.select("model").asOptString.orElse(computeModel(mergedOptions)).getOrElse("--")
    val startTime = System.currentTimeMillis()
    val callF = if (api.supportsTools && (options.wasmTools.nonEmpty || options.mcpConnectors.nonEmpty)) {
      val (tools, map) = LlmFunctions.toolsCohereWithInline(options.wasmToolsNoInline, options.wasmToolsInline, options.mcpConnectors, options.mcpIncludeFunctions, options.mcpExcludeFunctions, attrs)
      api.callWithToolSupport("POST", "/v2/chat", Some(mergedOptions ++ tools ++ Json.obj("fmap" -> map) ++ Json.obj("messages" -> prompt.jsonWithFlavor(ChatMessageContentFlavor.OpenAi))), options.mcpConnectors, attrs)
    } else {
      api.call("POST", "/v2/chat", Some(mergedOptions ++ Json.obj("messages" -> prompt.jsonWithFlavor(ChatMessageContentFlavor.OpenAi))))
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
          promptTokens = resp.body.select("usage").select("tokens").select("input_tokens").asOpt[Long].getOrElse(-1L),
          generationTokens = resp.body.select("usage").select("tokens").select("output_tokens").asOpt[Long].getOrElse(-1L),
          reasoningTokens = resp.body.at("usage.tokens.reasoning_tokens").asOpt[Long].getOrElse(-1L),
        ),
        None
      )
      val duration: Long = System.currentTimeMillis() - startTime //resp.headers.getIgnoreCase("CohereAi-processing-ms").map(_.toLong).getOrElse(0L)
      val slug = Json.obj(
        "provider_kind" -> "cohere",
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
      val messages = resp.body.select("message").asOpt[JsObject].map { obj =>
        val role = obj.select("role").asOpt[String].getOrElse("assistant")
        obj.select("content").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map { c =>
          val text = c.select("text").asOpt[String].getOrElse("")
          ChatGeneration(ChatMessage.output(role, text, None, c))
        }
      }.toSeq.flatten
      Right(ChatResponse(messages, usage, resp.body))
    }
  }

  override def stream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    val body = originalBody.asObject - "messages" - "provider"
    val mergedOptions = if (options.allowConfigOverride) options.jsonForCall.deepMerge(body) else options.jsonForCall
    val finalModel = mergedOptions.select("model").asOptString.orElse(computeModel(mergedOptions)).getOrElse("--")
    val startTime = System.currentTimeMillis()
    val callF = if (api.supportsTools && (options.wasmTools.nonEmpty || options.mcpConnectors.nonEmpty)) {
      val (tools, map) = LlmFunctions.toolsCohere(options.wasmTools, options.mcpConnectors, options.mcpIncludeFunctions, options.mcpExcludeFunctions)
      api.streamWithToolSupport("POST", "/v2/chat", Some(mergedOptions ++ tools ++ Json.obj("fmap" -> map) ++ Json.obj("messages" -> prompt.jsonWithFlavor(ChatMessageContentFlavor.Anthropic))), options.mcpConnectors, attrs)
    } else {
      api.stream("POST", "/v2/chat", Some(mergedOptions ++ Json.obj("messages" -> prompt.jsonWithFlavor(ChatMessageContentFlavor.Anthropic))))
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
              val duration: Long = System.currentTimeMillis() - startTime //resp.header("cohere-processing-ms").map(_.toLong).getOrElse(0L)
              val slug = Json.obj(
                "provider_kind" -> "cohere",
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
              created = System.currentTimeMillis(),
              model = mergedOptions.select("model").asOptString.getOrElse(options.model),
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

case class CohereAiEmbeddingModelClientOptions(raw: JsObject) {
  lazy val model: String = raw.select("model").asOpt[String].getOrElse("embed-multilingual-v3.0")
}

object CohereAiEmbeddingModelClientOptions {
  def fromJson(raw: JsObject): CohereAiEmbeddingModelClientOptions = CohereAiEmbeddingModelClientOptions(raw)
}

class CohereAiEmbeddingModelClient(val api: CohereAiApi, val options: CohereAiEmbeddingModelClientOptions, id: String) extends EmbeddingModelClient {

  override def embed(opts: EmbeddingClientInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, EmbeddingResponse]] = {
    val finalModel: String = opts.model.getOrElse(options.model)
    api.rawCall("POST", "/v2/embed", (options.raw ++ Json.obj("texts" -> opts.input, "model" -> finalModel, "input_type" -> "classification", "embedding_types" -> Json.arr("float"))).some).map { resp =>
      if (resp.status == 200) {
        Right(EmbeddingResponse(
          model = finalModel,
          embeddings = resp.json.select("embeddings").select("floats").as[Seq[JsArray]].map(arr => Embedding(arr.as[Array[Float]])),
          metadata = EmbeddingResponseMetadata(
            resp.json.select("meta").select("billed_units").select("input_tokens").asOpt[Long].getOrElse(-1L)
          ),
        ))
      } else {
        Left(Json.obj("status" -> resp.status, "body" -> resp.json))
      }
    }
  }
}

