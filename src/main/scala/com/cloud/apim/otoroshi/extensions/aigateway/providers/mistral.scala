package com.cloud.apim.otoroshi.extensions.aigateway.providers

import akka.stream.scaladsl.{Framing, Source}
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway._
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{GenericApiResponseChoiceMessageToolCall, LlmFunctions}
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.libs.ws.WSResponse

import scala.concurrent._
import scala.concurrent.duration._


case class MistralAiApiResponse(status: Int, headers: Map[String, String], body: JsValue) {
  def toOpenAi: OpenAiApiResponse = OpenAiApiResponse(status, headers, body)
  def json: JsValue = Json.obj(
    "status" -> status,
    "headers" -> headers,
    "body" -> body,
  )
}
object MistralAiModels {
  val TINY = "open-mistral-7b"
  val MIXTRAL_7B = "open-mixtral-8x7b"
  val MIXTRAL_22B = "open-mixtral-8x22b"
  val CODESTRAL_MAMBA = "open-codestral-mamba"
  val MISTRAL_EMBED = "mistral-embed"
  val CODESTRAL_LATEST = "codestral-latest"
  val OPEN_MISTRAL_NEMO = "open-mistral-nemo"
  val MIXTRAL = "mistral-large-latest"
  val SMALL = "mistral-small-latest"
  val MEDIUM = "mistral-medium-latest"
  val LARGE = "mistral-large-latest"
}
object MistralAiApi {
  val baseUrl = "https://api.mistral.ai"
}
class MistralAiApi(baseUrl: String = MistralAiApi.baseUrl, token: String, timeout: FiniteDuration = 3.minutes, env: Env) extends ApiClient[MistralAiApiResponse, OpenAiChatResponseChunk] {

  val supportsTools: Boolean = true
  val supportsStreaming: Boolean = true
  override def supportsCompletion: Boolean = false

  def rawCall(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[WSResponse] = {
    val url = s"${baseUrl}${path}"
    ProviderHelpers.logCall("Mistral", method, url, body)(env)
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

  def call(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[Either[JsValue, MistralAiApiResponse]] = {
    rawCall(method, path, body)
      .map(r => ProviderHelpers.wrapResponse("Mistral", r, env) { resp =>
        // println(s"mistral resp: ${resp.status} - ${resp.body}")
        MistralAiApiResponse(resp.status, resp.headers.mapValues(_.last), resp.json)
      })
  }

  override def callWithToolSupport(method: String, path: String, body: Option[JsValue], mcpConnectors: Seq[String], attrs: TypedMap)(implicit ec: ExecutionContext): Future[Either[JsValue, MistralAiApiResponse]] = {
    // TODO: accumulate consumptions ???
    if (body.flatMap(_.select("tools").asOpt[JsArray]).exists(_.value.nonEmpty)) {
      call(method, path, body).map(_.map(_.toOpenAi)).flatMap {
        case Left(err) => err.leftf
        case Right(resp) if resp.finishBecauseOfToolCalls => {
          body match {
            case None => resp.toMistral.rightf
            case Some(body) => {
              val messages = body.select("messages").asOpt[Seq[JsObject]].getOrElse(Seq.empty) //.map(v => v.flatMap(o => ChatMessage.format.reads(o).asOpt)).getOrElse(Seq.empty)
              val toolCalls = resp.toolCalls
              LlmFunctions.callToolsOpenai(toolCalls.map(tc => GenericApiResponseChoiceMessageToolCall(tc.raw)), mcpConnectors, "mistral", attrs)(ec, env)
                .flatMap { callResps =>
                  // val newMessages: Seq[JsValue] = messages.map(_.json) ++ callResps
                  val newMessages: Seq[JsValue] = messages ++ callResps
                  val newBody = body.asObject ++ Json.obj("messages" -> JsArray(newMessages))
                  callWithToolSupport(method, path, newBody.some, mcpConnectors, attrs)
                }
            }
          }
        }
        case Right(resp) => resp.toMistral.rightf
      }
    } else {
      call(method, path, body)
    }
  }


  override def stream(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[Either[JsValue, (Source[OpenAiChatResponseChunk, _], WSResponse)]] = {
    val url = s"${baseUrl}${path}"
    ProviderHelpers.logStream("Mistral", method, url, body)(env)
    env.Ws
      .url(url)
      .withHttpHeaders(
        "Authorization" -> s"Bearer ${token}",
        "Accept" -> "application/json",
      ).applyOnWithOpt(body) {
        case (builder, body) => builder
          .addHttpHeaders("Content-Type" -> "application/json")
          .withBody(body.asObject ++ Json.obj(
            "stream" -> true,
            // "stream_options" -> Json.obj("include_usage" -> true)
          ))
      }
      .withMethod(method)
      .withRequestTimeout(timeout)
      .stream()
      .map(r => ProviderHelpers.wrapStreamResponse("Mistral", r, env) { resp =>
        (resp.bodyAsSource
          .via(Framing.delimiter(ByteString("\n\n"), Int.MaxValue, false))
          .map(_.utf8String)
          .filter(_.startsWith("data: "))
          .map(_.replaceFirst("data: ", "").trim())
          .filter(_.nonEmpty)
          .takeWhile(_ != "[DONE]")
          .map(str => Json.parse(str))
          .map(json => OpenAiChatResponseChunk(json)), resp)
      })
  }

  override def streamWithToolSupport(method: String, path: String, body: Option[JsValue], mcpConnectors: Seq[String], attrs: TypedMap)(implicit ec: ExecutionContext): Future[Either[JsValue, (Source[OpenAiChatResponseChunk, _], WSResponse)]] = {
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
              val a: Future[Either[JsValue, (Source[OpenAiChatResponseChunk, _], WSResponse)]] = LlmFunctions.callToolsOpenai(calls, mcpConnectors, "mistral", attrs)(ec, env)
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

object MistralAiChatClientOptions {
  def fromJson(json: JsValue): MistralAiChatClientOptions = {
    MistralAiChatClientOptions(
      model = json.select("model").asOpt[String].getOrElse(MistralAiModels.TINY),
      max_tokens = json.select("max_tokens").asOpt[Int],
      safe_prompt = json.select("safe_prompt").asOpt[Boolean],
      random_seed = json.select("random_seed").asOpt[Int],
      temperature = json.select("temperature").asOpt[Float].getOrElse(1.0f),
      topP = json.select("topP").asOpt[Float].orElse(json.select("top_p").asOpt[Float]).getOrElse(1.0f),
      tools = json.select("tools").asOpt[Seq[JsValue]],
      tool_choice = json.select("tool_choice").asOpt[Seq[JsValue]],
      wasmTools = json.select("wasm_tools").asOpt[Seq[String]].getOrElse(Seq.empty),
      mcpConnectors = json.select("mcp_connectors").asOpt[Seq[String]].getOrElse(Seq.empty),
      allowConfigOverride = json.select("allow_config_override").asOptBoolean.getOrElse(true),
      mcpIncludeFunctions = json.select("mcp_include_functions").asOpt[Seq[String]].getOrElse(Seq.empty),
      mcpExcludeFunctions = json.select("mcp_exclude_functions").asOpt[Seq[String]].getOrElse(Seq.empty),
    )
  }
}

case class MistralAiChatClientOptions(
  model: String = MistralAiModels.TINY,
  safe_prompt: Option[Boolean] = Some(false),
  max_tokens: Option[Int] = None,
  random_seed: Option[Int] = None,
  temperature: Float = 1,
  topP: Float = 1,
  tools: Option[Seq[JsValue]] = None,
  tool_choice: Option[Seq[JsValue]] =  None,
  wasmTools: Seq[String] = Seq.empty,
  mcpConnectors: Seq[String] = Seq.empty,
  allowConfigOverride: Boolean = true,
  mcpIncludeFunctions: Seq[String] = Seq.empty,
  mcpExcludeFunctions: Seq[String] = Seq.empty,
) extends ChatOptions {
  override def topK: Int = 0

  override def json: JsObject = Json.obj(
    "model" -> model,
    "max_tokens" -> max_tokens,
    "random_seed" -> random_seed,
    "safe_prompt" -> safe_prompt,
    "temperature" -> temperature,
    "top_p" -> topP,
    "wasm_tools" -> JsArray(wasmTools.map(_.json)),
    "mcp_connectors" -> JsArray(mcpConnectors.map(_.json)),
    "allow_config_override" -> allowConfigOverride,
    "mcp_include_functions" -> JsArray(mcpIncludeFunctions.map(_.json)),
    "mcp_exclude_functions" -> JsArray(mcpExcludeFunctions.map(_.json)),
  ).applyOnWithOpt(tool_choice) {
    case (obj, tc) => obj ++ Json.obj("tool_choice" -> tc)
  }.applyOnWithOpt(tools) {
    case (obj, tc) => obj ++ Json.obj("tools" -> tc)
  }

  def jsonForCall: JsObject = optionsCleanup(json - "wasm_tools" - "mcp_connectors" - "allow_config_override" - "mcp_include_functions" - "mcp_exclude_functions")
}

class MistralAiChatClient(api: MistralAiApi, options: MistralAiChatClientOptions, id: String) extends ChatClient {

  override def supportsTools: Boolean = api.supportsTools
  override def supportsStreaming: Boolean = api.supportsStreaming
  override def supportsCompletion: Boolean = true
  override def model: Option[String] = options.model.some

  override def listModels(raw: Boolean)(implicit ec: ExecutionContext): Future[Either[JsValue, List[String]]] = {
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
    val callF = if (api.supportsTools && (options.wasmTools.nonEmpty || options.mcpConnectors.nonEmpty)) {
      val tools = LlmFunctions.tools(options.wasmTools, options.mcpConnectors, options.mcpIncludeFunctions, options.mcpExcludeFunctions)
      api.callWithToolSupport("POST", "/v1/chat/completions", Some(mergedOptions ++ tools ++ Json.obj("messages" -> prompt.json)), options.mcpConnectors, attrs)
    } else {
      api.call("POST", "/v1/chat/completions", Some(mergedOptions ++ Json.obj("messages" -> prompt.json)))
    }
    callF.map {
      case Left(err) => err.left
      case Right(resp) =>
        //api.call("POST", "/v1/chat/completions", Some(mergedOptions ++ Json.obj("messages" -> prompt.json))).map { resp =>
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
      val duration: Long = resp.headers.getIgnoreCase("mistral-processing-ms").map(_.toLong).getOrElse(0L)
      val slug = Json.obj(
        "provider_kind" -> "mistral",
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
        ChatGeneration(ChatMessage.output(role, content, None, obj))
      }
      Right(ChatResponse(messages, usage))
    }
  }

  override def stream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    val obody = originalBody.asObject - "messages" - "provider"
    val mergedOptions = if (options.allowConfigOverride) options.jsonForCall.deepMerge(obody) else options.jsonForCall
    val callF = if (api.supportsTools && (options.wasmTools.nonEmpty || options.mcpConnectors.nonEmpty)) {
      val tools = LlmFunctions.tools(options.wasmTools, options.mcpConnectors, options.mcpIncludeFunctions, options.mcpExcludeFunctions)
      api.streamWithToolSupport("POST", "/v1/chat/completions", Some(mergedOptions ++ tools ++ Json.obj("messages" -> prompt.json)), options.mcpConnectors, attrs)
    } else {
      api.stream("POST", "/v1/chat/completions", Some(mergedOptions ++ Json.obj("messages" -> prompt.json)))
    }
    callF.map {
    // api.stream("POST", "/v1/chat/completions", Some(mergedOptions ++ Json.obj("messages" -> prompt.json))).map {
      case Left(err) => err.left
      case Right((source, resp)) =>
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
                  reasoningTokens = chunk.usage.map(_.reasoningTokens).getOrElse(-1L),
                ),
                None
              )
              val duration: Long = resp.header("mistral-processing-ms").map(_.toLong).getOrElse(0L)
              val slug = Json.obj(
                "provider_kind" -> "mistral",
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

  override def completion(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val obody = originalBody.asObject - "messages" - "provider" - "prompt"
    val mergedOptions = if (options.allowConfigOverride) options.jsonForCall.deepMerge(obody) else options.jsonForCall
    val callF = api.call("POST", "/v1/fim/completions", Some(mergedOptions ++ Json.obj("prompt" -> prompt.messages.head.content)))
    callF.map {
      case Left(err) => err.left
      case Right(resp) =>
        //api.call("POST", "/v1/chat/completions", Some(mergedOptions ++ Json.obj("messages" -> prompt.json))).map { resp =>
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
      val duration: Long = resp.headers.getIgnoreCase("mistral-processing-ms").map(_.toLong).getOrElse(0L)
      val slug = Json.obj(
        "provider_kind" -> "mistral",
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
        ChatGeneration(ChatMessage.output(role, content, None, obj))
      }
      Right(ChatResponse(messages, usage))
    }
  }

}

case class MistralAiEmbeddingModelClientOptions(raw: JsObject) {
  lazy val model: String = raw.select("model").asOpt[String].getOrElse("mistral-embed")
}

object MistralAiEmbeddingModelClientOptions {
  def fromJson(raw: JsObject): MistralAiEmbeddingModelClientOptions = MistralAiEmbeddingModelClientOptions(raw)
}

class MistralAiEmbeddingModelClient(val api: MistralAiApi, val options: MistralAiEmbeddingModelClientOptions, id: String) extends EmbeddingModelClient {

  override def embed(opts: EmbeddingClientInputOptions, rawBody: JsObject)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, EmbeddingResponse]] = {
    val finalModel: String = opts.model.getOrElse(options.model)
    api.rawCall("POST", "/v1/embeddings", (options.raw ++ Json.obj("input" -> opts.input, "model" -> finalModel)).some).map { resp =>
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
/////////                                    Mistral Moderation Models                                    ///////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

case class MistralAiModerationModelClientOptions(raw: JsObject) {
  lazy val model: String = raw.select("model").asOpt[String].getOrElse("mistral-moderation-latest")
}

object MistralAiModerationModelClientOptions {
  def fromJson(raw: JsObject): MistralAiModerationModelClientOptions = MistralAiModerationModelClientOptions(raw)
}

class MistralAiModerationModelClient(val api: MistralAiApi, val options: MistralAiModerationModelClientOptions, id: String) extends ModerationModelClient {

  override def moderate(opts: ModerationModelClientInputOptions, rawBody: JsObject)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ModerationResponse]] = {
    val finalModel: String = opts.model.getOrElse(options.model)
    val body = Json.obj(
      "input" -> opts.input,
      "model" -> finalModel
    )
    api.rawCall("POST", "/v1/moderations", body.some).map { resp =>
      if (resp.status == 200) {
        Right(ModerationResponse(
          model = resp.json.select("model").asString,
          moderationResults = resp.json.select("results").as[Seq[JsObject]].map(o => ModerationResult(
            o.select("flagged").asOpt[Boolean].getOrElse(false),
            o.select("categories").asOpt[JsObject].getOrElse(Json.obj()),
            o.select("category_scores").asOpt[JsObject].getOrElse(Json.obj())
          )),
        ))
      } else {
        Left(Json.obj("status" -> resp.status, "body" -> resp.json))
      }
    }
  }
}