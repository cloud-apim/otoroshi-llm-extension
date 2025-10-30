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

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

// https://learn.microsoft.com/en-us/azure/ai-services/openai/reference

// TODO: support https://azure.microsoft.com/fr-fr/products/ai-foundry/ - https://learn.microsoft.com/en-us/rest/api/aifoundry/model-inference/get-chat-completions/get-chat-completions?view=rest-aifoundry-model-inference-2024-05-01-preview&tabs=HTTP
// https://learn.microsoft.com/fr-fr/azure/ai-foundry/model-inference/how-to/use-chat-completions?context=%2Fazure%2Fai-foundry%2Fcontext%2Fcontext&pivots=programming-language-rest

case class AzureOpenAiChatResponseChunkUsage(raw: JsValue) {
  lazy val completion_tokens: Long = raw.select("completion_tokens").asLong
  lazy val prompt_tokens: Long = raw.select("prompt_tokens").asLong
  lazy val total_tokens: Long = raw.select("total_tokens").asLong
  lazy val reasoning_tokens: Long = raw.select("reasoning_tokens").asLong
}

case class AzureOpenAiChatResponseChunkChoiceDeltaToolCallFunction(raw: JsValue) {
  lazy val name: String = raw.select("name").asString
  lazy val nameOpt: Option[String] = raw.select("name").asOptString
  lazy val hasName: Boolean = nameOpt.isDefined
  lazy val arguments: String = raw.select("arguments").asString
}

case class AzureOpenAiChatResponseChunkChoiceDeltaToolCall(raw: JsValue) {
  lazy val index: Long = raw.select("index").asInt
  lazy val id: String = raw.select("id").asString
  lazy val typ: String = raw.select("type").asString
  lazy val function: AzureOpenAiChatResponseChunkChoiceDeltaToolCallFunction = AzureOpenAiChatResponseChunkChoiceDeltaToolCallFunction(raw.select("function").asObject)
}

case class AzureOpenAiChatResponseChunkChoiceDelta(raw: JsValue) {
  lazy val content: Option[String] = raw.select("content").asOptString
  lazy val role: String = raw.select("role").asString
  lazy val refusal: Option[String] = raw.select("refusal").asOptString
  lazy val tool_calls: Seq[AzureOpenAiChatResponseChunkChoiceDeltaToolCall] = raw.select("tool_calls").asOpt[Seq[JsObject]].map(_.map(AzureOpenAiChatResponseChunkChoiceDeltaToolCall.apply)).getOrElse(Seq.empty)
}

case class AzureOpenAiChatResponseChunkChoice(raw: JsValue) {
  lazy val finish_reason: Option[String] = raw.select("finish_reason").asOptString
  lazy val index: Option[Int] = raw.select("finish_reason").asOptInt
  lazy val delta: Option[AzureOpenAiChatResponseChunkChoiceDelta] = raw.select("delta").asOpt[JsObject].map(AzureOpenAiChatResponseChunkChoiceDelta.apply)
}

case class AzureOpenAiChatResponseChunk(raw: JsValue) {
  lazy val id: String = raw.select("id").asString
  lazy val obj: String = raw.select("object").asString
  lazy val created: Long = raw.select("created").asLong
  lazy val model: String = raw.select("model").asString
  lazy val system_fingerprint: String = raw.select("system_fingerprint").asString
  lazy val service_tier: Option[String] = raw.select("service_tier").asOptString
  lazy val usage: Option[AzureOpenAiChatResponseChunkUsage] = raw.select("usage").asOpt[JsObject].map { obj =>
    AzureOpenAiChatResponseChunkUsage(obj)
  }
  lazy val choices: Seq[AzureOpenAiChatResponseChunkChoice] = raw.select("choices").asOpt[Seq[JsObject]].map(_.map(i => AzureOpenAiChatResponseChunkChoice(i))).getOrElse(Seq.empty)
}

case class AzureOpenAiApiResponseChoiceMessageToolCallFunction(raw: JsObject) {
  lazy val name: String = raw.select("name").asString
  lazy val arguments: String = raw.select("arguments").asString
}

case class AzureOpenAiApiResponseChoiceMessageToolCall(raw: JsObject) {
  lazy val id: String = raw.select("id").asString
  lazy val function: AzureOpenAiApiResponseChoiceMessageToolCallFunction = AzureOpenAiApiResponseChoiceMessageToolCallFunction(raw.select("function").asObject)
}

case class AzureOpenAiApiResponseChoiceMessage(raw: JsObject) {
  lazy val role: String = raw.select("role").asString
  lazy val content: Option[String] = raw.select("content").asOpt[String]
  lazy val refusal: Option[String] = raw.select("refusal").asOpt[String]
  lazy val toolCalls: Seq[AzureOpenAiApiResponseChoiceMessageToolCall] = raw.select("tool_calls").asOpt[Seq[JsObject]].map(_.map(v => AzureOpenAiApiResponseChoiceMessageToolCall(v))).getOrElse(Seq.empty)
}

case class AzureOpenAiApiResponseChoice(raw: JsObject) {
  lazy val index: Int = raw.select("index").asOpt[Int].getOrElse(-1)
  lazy val finishReason: String = raw.select("finish_reason").asOpt[String].getOrElse("--")
  lazy val finishBecauseOfToolCalls: Boolean = finishReason == "tool_calls"
  lazy val message: AzureOpenAiApiResponseChoiceMessage = raw.select("message").asOpt[JsObject].map(v => AzureOpenAiApiResponseChoiceMessage(v)).get
}

case class AzureOpenAiApiResponse(status: Int, headers: Map[String, String], body: JsValue) {
  lazy val finishBecauseOfToolCalls: Boolean = choices.exists(_.finishBecauseOfToolCalls)
  lazy val toolCalls: Seq[AzureOpenAiApiResponseChoiceMessageToolCall] = choices.map(_.message).flatMap(_.toolCalls)
  lazy val choices: Seq[AzureOpenAiApiResponseChoice] = {
    body.select("choices").asOpt[Seq[JsObject]].map(_.map(v => AzureOpenAiApiResponseChoice(v))).getOrElse(Seq.empty)
  }
  def json: JsValue = Json.obj(
    "status" -> status,
    "headers" -> headers,
    "body" -> body,
  )
}

object AzureOpenAiModels {
  val GPT_4_0125_PREVIEW = "gpt-4-0125-preview"
  val GPT_4_TURBO_PREVIEW = "gpt-4-turbo-preview"
  val GPT_4_VISION_PREVIEW = "gpt-4-vision-preview"
  val GPT_4 = "gpt-4"
  val GPT_4_32K = "gpt-4-32k"
  val GPT_3_5_TURBO = "gpt-3.5-turbo"
  val GPT_3_5_TURBO_0125 = "gpt-3.5-turbo-0125"
  val GPT_3_5_TURBO_1106 = "gpt-3.5-turbo-1106"
}

object AzureAiFoundry {
  val baseUrl = "https://<resource>.services.ai.azure.com/models"
}

object AzureOpenAiApi {
  // POST https://{your-resource-name}.openai.azure.com/openai/deployments/{deployment-id}/completions?api-version={api-version}
  def urlPreV1(resourceName: String, deploymentId: String, version: String, path: String): String = {
    s"https://${resourceName}.openai.azure.com/openai/deployments/${deploymentId}${path}?api-version=${version}"
  }
  def urlPostV1(resourceName: String, deploymentId: String, version: String, path: String): String = {
    s"https://${resourceName}.openai.azure.com/openai/${version}${path}"
  }
  def url(resourceName: String, deploymentId: String, version: String, path: String): String = {
    if (version == "v1") {
      urlPostV1(resourceName, deploymentId, version, path)
    } else {
      urlPreV1(resourceName, deploymentId, version, path)
    }
  }
}
// https://learn.microsoft.com/en-us/azure/ai-services/openai/reference
class AzureOpenAiApi(val resourceName: String, val deploymentId: String, val version: String, apikey: Option[String], bearer: Option[String], timeout: FiniteDuration = 3.minutes, env: Env) extends ApiClient[AzureOpenAiApiResponse, AzureOpenAiChatResponseChunk] {

  override def supportsTools: Boolean = true
  override def supportsStreaming: Boolean = true
  override def supportsCompletion: Boolean = false

  def rawCall(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[WSResponse] = {
    val url = s"${AzureOpenAiApi.url(resourceName, deploymentId, version, path)}"
    ProviderHelpers.logCall("AzureOpenai", method, url, body)(env)
    env.Ws
      .url(url)
      .withHttpHeaders(
        "Accept" -> "application/json",
      )
      .applyOnWithOpt(apikey) {
        case (builder, apikey) => builder.addHttpHeaders("api-key" -> apikey)
      }
      .applyOnWithOpt(bearer) {
        case (builder, bearer) => builder.addHttpHeaders("Authorization" -> s"Bearer ${bearer}")
      }
      .applyOnWithOpt(body) {
        case (builder, body) => builder
          .addHttpHeaders("Content-Type" -> "application/json")
          .withBody(body)
      }
      .withMethod(method)
      .withRequestTimeout(timeout)
      .execute()
  }

  def call(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[Either[JsValue, AzureOpenAiApiResponse]] = {
    rawCall(method, path, body)
      .map(r => ProviderHelpers.wrapResponse("AzureOpenai", r, env) { resp =>
        AzureOpenAiApiResponse(resp.status, resp.headers.mapValues(_.last), resp.json)
      })
  }

  override def callWithToolSupport(method: String, path: String, body: Option[JsValue], mcpConnectors: Seq[String], attrs: TypedMap)(implicit ec: ExecutionContext): Future[Either[JsValue, AzureOpenAiApiResponse]] = {
    // TODO: accumulate consumptions ???
    if (body.flatMap(_.select("tools").asOpt[JsArray]).exists(_.value.nonEmpty)) {
      call(method, path, body).flatMap {
        case Left(err) => err.leftf
        case Right(resp) if resp.finishBecauseOfToolCalls => {
          body match {
            case None => resp.rightf
            case Some(body) => {
              val messages = body.select("messages").asOpt[Seq[JsObject]].getOrElse(Seq.empty) // .map(v => v.flatMap(o => ChatMessage.format.reads(o).asOpt)).getOrElse(Seq.empty)
              val toolCalls = resp.toolCalls
              LlmFunctions.callToolsOpenai(toolCalls.map(tc => GenericApiResponseChoiceMessageToolCall(tc.raw)), mcpConnectors, "azure", attrs)(ec, env)
                .flatMap { callResps =>
                  // val newMessages: Seq[JsValue] = messages.map(_.json) ++ callResps
                  val newMessages: Seq[JsValue] = messages ++ callResps
                  val newBody = body.asObject ++ Json.obj("messages" -> JsArray(newMessages))
                  callWithToolSupport(method, path, newBody.some, mcpConnectors, attrs)
                }
            }
          }
        }
        case Right(resp) => resp.rightf
      }
    } else {
      call(method, path, body)
    }
  }

  override def stream(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[Either[JsValue, (Source[AzureOpenAiChatResponseChunk, _], WSResponse)]] = {
    val url = s"${AzureOpenAiApi.url(resourceName, deploymentId, version, path)}"
    ProviderHelpers.logStream("AzureOpenai", method, url, body)(env)
    env.Ws
      .url(url)
      .withHttpHeaders(
        "Accept" -> "application/json",
      )
      .applyOnWithOpt(apikey) {
        case (builder, apikey) => builder.addHttpHeaders("api-key" -> apikey)
      }
      .applyOnWithOpt(bearer) {
        case (builder, bearer) => builder.addHttpHeaders("Authorization" -> s"Bearer ${bearer}")
      }
      .applyOnWithOpt(body) {
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
      .map(r => ProviderHelpers.wrapStreamResponse("AzureOpenai", r, env) { resp =>
        (resp.bodyAsSource
          .via(Framing.delimiter(ByteString("\n\n"), Int.MaxValue, false))
          .map(_.utf8String)
          .filter(_.startsWith("data: "))
          .map(_.replaceFirst("data: ", "").trim())
          .filter(_.nonEmpty)
          .takeWhile(_ != "[DONE]")
          .map(str => Json.parse(str))
          .map(json => AzureOpenAiChatResponseChunk(json)), resp)
      })
  }

  override def streamWithToolSupport(method: String, path: String, body: Option[JsValue], mcpConnectors: Seq[String], attrs: TypedMap)(implicit ec: ExecutionContext): Future[Either[JsValue, (Source[AzureOpenAiChatResponseChunk, _], WSResponse)]] = {
    if (body.flatMap(_.select("tools").asOpt[JsArray]).exists(_.value.nonEmpty)) {
      val messages = body.get.select("messages").asOpt[Seq[JsObject]].getOrElse(Seq.empty) //.map(v => v.flatMap(o => ChatMessage.format.reads(o).asOpt)).getOrElse(Seq.empty)
      stream(method, path, body).flatMap {
        case Left(err) => err.leftf
        case Right(res) => {
          var isToolCall = false
          var isToolCallEnded = false
          var toolCalls: Seq[AzureOpenAiChatResponseChunkChoiceDeltaToolCall] = Seq.empty
          var toolCallArgs: scala.collection.mutable.ArraySeq[String] = scala.collection.mutable.ArraySeq.empty
          var toolCallUsage: AzureOpenAiChatResponseChunkUsage = null
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
              val a: Future[Either[JsValue, (Source[AzureOpenAiChatResponseChunk, _], WSResponse)]] = LlmFunctions.callToolsOpenai(calls, mcpConnectors, "azure", attrs)(ec, env)
                .flatMap { callResps =>
                  // val newMessages: Seq[JsValue] = messages.map(_.json) ++ callResps
                  val newMessages: Seq[JsValue] = messages ++ callResps
                  val newBody = body.get.asObject ++ Json.obj("messages" -> JsArray(newMessages))
                  streamWithToolSupport(method, path, newBody.some, mcpConnectors, attrs)
                }
              Source.future(a)
                .flatMapConcat {
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

object AzureOpenAiChatClientOptions {
  def fromJson(json: JsValue): AzureOpenAiChatClientOptions = {
    AzureOpenAiChatClientOptions(
      max_tokens = json.select("max_tokens").asOpt[Int],
      n = json.select("n").asOpt[Int],
      temperature = json.select("temperature").asOpt[Float].getOrElse(1.0f),
      topP = json.select("topP").asOpt[Float].orElse(json.select("top_p").asOpt[Float]).getOrElse(1.0f),
      wasmTools = json.select("wasm_tools").asOpt[Seq[String]].filter(_.nonEmpty).orElse(json.select("tool_functions").asOpt[Seq[String]]).getOrElse(Seq.empty),
      mcpConnectors = json.select("mcp_connectors").asOpt[Seq[String]].getOrElse(Seq.empty),
      frequency_penalty = json.select("frequency_penalty").asOpt[Double],
      logprobs = json.select("logprobs").asOpt[Boolean],
      top_logprobs = json.select("top_logprobs").asOpt[Int],
      seed = json.select("seed").asOpt[Int],
      presence_penalty = json.select("presence_penalty").asOpt[Double],
      allowConfigOverride = json.select("allow_config_override").asOptBoolean.getOrElse(true),
      mcpIncludeFunctions = json.select("mcp_include_functions").asOpt[Seq[String]].getOrElse(Seq.empty),
      mcpExcludeFunctions = json.select("mcp_exclude_functions").asOpt[Seq[String]].getOrElse(Seq.empty),
    )
  }
}

case class AzureOpenAiChatClientOptions(
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
  tools: Option[Seq[JsValue]] = None,
  tool_choice: Option[Seq[JsValue]] =  None,
  user: Option[String] = None,
  wasmTools: Seq[String] = Seq.empty,
  mcpConnectors: Seq[String] = Seq.empty,
  allowConfigOverride: Boolean = true,
  mcpIncludeFunctions: Seq[String] = Seq.empty,
  mcpExcludeFunctions: Seq[String] = Seq.empty,
) extends ChatOptions {
  override def topK: Int = 0

  override def json: JsObject = Json.obj(
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
    "tool_functions" -> JsArray(wasmTools.map(_.json)),
    "mcp_connectors" -> JsArray(mcpConnectors.map(_.json)),
    "mcp_include_functions" -> JsArray(mcpIncludeFunctions.map(_.json)),
    "mcp_exclude_functions" -> JsArray(mcpExcludeFunctions.map(_.json)),
    "allow_config_override" -> allowConfigOverride,
  )

  def jsonForCall: JsObject = optionsCleanup(json - "wasm_tools" - "tool_functions" - "mcp_connectors" - "allow_config_override" - "mcp_include_functions" - "mcp_exclude_functions")
}

class AzureOpenAiChatClient(api: AzureOpenAiApi, options: AzureOpenAiChatClientOptions, id: String) extends ChatClient {

  override def model: Option[String] = s"${api.resourceName}-${api.deploymentId}".some
  override def supportsTools: Boolean = api.supportsTools
  override def supportsStreaming: Boolean = api.supportsStreaming
  override def supportsCompletion: Boolean = api.supportsCompletion

  override def listModels(raw: Boolean)(implicit ec: ExecutionContext): Future[Either[JsValue, List[String]]] = {
    api.rawCall("GET", "/models", None).map { resp =>
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
    val finalModel = mergedOptions.select("model").asOptString.orElse(model).getOrElse("--")
    val startTime = System.currentTimeMillis()
    val callF = if (api.supportsTools && (options.wasmTools.nonEmpty || options.mcpConnectors.nonEmpty)) {
      val tools = LlmFunctions.tools(options.wasmTools, options.mcpConnectors, options.mcpIncludeFunctions, options.mcpExcludeFunctions)
      api.callWithToolSupport("POST", "/chat/completions", Some(mergedOptions ++ tools ++ Json.obj("messages" -> prompt.jsonWithFlavor(ChatMessageContentFlavor.OpenAi))), options.mcpConnectors, attrs)
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
      val duration: Long = System.currentTimeMillis() - startTime //resp.headers.getIgnoreCase("AzureOpenAi-processing-ms").map(_.toLong).getOrElse(0L)
      val slug = Json.obj(
        "provider_kind" -> "AzureOpenAi",
        "provider" -> id,
        "duration" -> duration,
        "model" -> finalModel.json,
        "deployment_id" -> api.deploymentId,
        "resource_name" -> api.resourceName,
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
      Right(ChatResponse(messages, usage, resp.body))
    }
  }

  override def stream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    val obody = originalBody.asObject - "messages" - "provider"
    val mergedOptions = if (options.allowConfigOverride) options.jsonForCall.deepMerge(obody) else options.jsonForCall
    val finalModel = mergedOptions.select("model").asOptString.orElse(model).getOrElse("--")
    val startTime = System.currentTimeMillis()
    val callF = if (api.supportsTools && (options.wasmTools.nonEmpty || options.mcpConnectors.nonEmpty)) {
      val tools = LlmFunctions.tools(options.wasmTools, options.mcpConnectors, options.mcpIncludeFunctions, options.mcpExcludeFunctions)
      api.streamWithToolSupport("POST", "/chat/completions", Some(mergedOptions ++ tools ++ Json.obj("messages" -> prompt.jsonWithFlavor(ChatMessageContentFlavor.OpenAi))), options.mcpConnectors, attrs)
    } else {
      api.stream("POST", "/chat/completions", Some(mergedOptions ++ Json.obj("messages" -> prompt.jsonWithFlavor(ChatMessageContentFlavor.OpenAi))))
    }
    callF.map {
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
                  reasoningTokens = chunk.usage.map(_.reasoning_tokens).getOrElse(-1L),
                ),
                None
              )
              val duration: Long = System.currentTimeMillis() - startTime //resp.header("AzureOpenAi-processing-ms").map(_.toLong).getOrElse(0L)
              val slug = Json.obj(
                "provider_kind" -> "AzureOpenAi",
                "provider" -> id,
                "duration" -> duration,
                "model" -> finalModel.json,
                "deployment_id" -> api.deploymentId,
                "resource_name" -> api.resourceName,
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
    val body = originalBody.asObject - "messages" - "provider" - "prompt"
    val mergedOptions = if (options.allowConfigOverride) options.jsonForCall.deepMerge(body) else options.jsonForCall
    val startTime = System.currentTimeMillis()
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
      val duration: Long = System.currentTimeMillis() - startTime //resp.headers.getIgnoreCase("AzureOpenAi-processing-ms").map(_.toLong).getOrElse(0L)
      val slug = Json.obj(
        "provider_kind" -> "AzureOpenAi",
        "provider" -> id,
        "duration" -> duration,
        "deployment_id" -> api.deploymentId,
        "resource_name" -> api.resourceName,
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
        val content = obj.select("text").asString
        ChatGeneration(ChatMessage.output("assistant", content, None, obj))
      }
      Right(ChatResponse(messages, usage, resp.body))
    }
  }
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/////////                                     Images Generation                                          ///////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

case class AzureOpenAiImageModelClientOptions(raw: JsObject) {
  lazy val enabled: Boolean = raw.select("enabled").asOptBoolean.getOrElse(true)
  lazy val background: Option[String] = raw.select("background").asOptString
  lazy val model: Option[String] = raw.select("model").asOptString
  lazy val moderation: Option[String] = raw.select("moderation").asOptString
  lazy val n: Option[Int] = raw.select("n").asOptInt
  lazy val outputCompression: Option[Int] = raw.select("output_compression").asOptInt
  lazy val outputFormat: Option[String] = raw.select("output_format").asOptString
  lazy val responseFormat: Option[String] = raw.select("response_format").asOptString
  lazy val quality: Option[String] = raw.select("quality").asOptString
  lazy val size: Option[String] = raw.select("size").asOptString
  lazy val style: Option[String] = raw.select("style").asOptString
}

object AzureOpenAiImageModelClientOptions {
  def fromJson(raw: JsObject): AzureOpenAiImageModelClientOptions = AzureOpenAiImageModelClientOptions(raw)
}

class AzureOpenAiImageModelClient(val api: AzureOpenAiApi, val genOptions: AzureOpenAiImageModelClientOptions, id: String) extends ImageModelClient {

  override def supportsGeneration: Boolean = genOptions.enabled
  override def supportsEdit: Boolean = false

  override def generate(opts: ImageModelClientGenerationInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ImagesGenResponse]] = {
    val finalModel = opts.model.orElse(genOptions.model).getOrElse("gpt-image-1")
    val finalSize: String = opts.size.orElse(genOptions.size).getOrElse("1024x1024").toLowerCase match {
      case "1024x1024" => "1024x1024"
      case "1792x1024" => "1792x1024"
      case "1024x1792" => "1024x1792"
      case _ => "1024x1024"
    }
    val body = Json.obj(
        "prompt" -> opts.prompt,
        "size" -> finalSize,
        "model" -> finalModel,
        "quality" -> "auto",
      )
      .applyOnWithOpt(opts.background.orElse(genOptions.background)) { case (obj, background) => obj ++ Json.obj("background" -> background) }
      .applyOnWithOpt(opts.model.orElse(genOptions.model)) { case (obj, model) => obj ++ Json.obj("model" -> model) }
      .applyOnWithOpt(opts.moderation.orElse(genOptions.moderation)) { case (obj, moderation) => obj ++ Json.obj("moderation" -> moderation) }
      .applyOnWithOpt(opts.n.orElse(genOptions.n)) { case (obj, n) => obj ++ Json.obj("n" -> n) }
      .applyOnWithOpt(opts.outputCompression.orElse(genOptions.outputCompression)) { case (obj, outputCompression) => obj ++ Json.obj("output_compression" -> outputCompression) }
      .applyOnWithOpt(opts.outputFormat.orElse(genOptions.outputFormat)) { case (obj, outputFormat) => obj ++ Json.obj("output_format" -> outputFormat) }
      .applyOnWithOpt(opts.responseFormat.orElse(genOptions.responseFormat)) { case (obj, responseFormat) => obj ++ Json.obj("response_format" -> responseFormat) }
      .applyOnWithOpt(opts.quality.orElse(genOptions.quality)) { case (obj, quality) => obj ++ Json.obj("quality" -> quality) }
      .applyOnWithOpt(opts.size.orElse(genOptions.size)) { case (obj, size) => obj ++ Json.obj("size" -> size) }
      .applyOnWithOpt(opts.style.orElse(genOptions.style)) { case (obj, style) => obj ++ Json.obj("style" -> style) }

    api.rawCall("POST", "/images/generations", body.some).map { resp =>
      if (resp.status == 200) {
        val headers = resp.headers.mapValues(_.last)
        Right(ImagesGenResponse(
          created = resp.json.select("created").asOpt[Long].getOrElse(-1L),
          images = resp.json.select("data").as[Seq[JsObject]].map(o => ImagesGen(o.select("b64_json").asOpt[String], o.select("revised_prompt").asOpt[String], o.select("url").asOpt[String])),
          metadata = finalModel.toLowerCase match {
            case "gpt-image-1" => ImagesGenResponseMetadata(
              rateLimit = ChatResponseMetadataRateLimit(
                requestsLimit = headers.getIgnoreCase("x-ratelimit-limit-requests").map(_.toLong).getOrElse(-1L),
                requestsRemaining = headers.getIgnoreCase("x-ratelimit-remaining-requests").map(_.toLong).getOrElse(-1L),
                tokensLimit = headers.getIgnoreCase("x-ratelimit-limit-tokens").map(_.toLong).getOrElse(-1L),
                tokensRemaining = headers.getIgnoreCase("x-ratelimit-remaining-tokens").map(_.toLong).getOrElse(-1L),
              ), impacts = None, costs = None,
              usage = ImagesGenResponseMetadataUsage(
                totalTokens = resp.json.at("usage.total_tokens").asOpt[Long].getOrElse(-1L),
                tokenInput = resp.json.at("usage.input_tokens").asOpt[Long].getOrElse(-1L),
                tokenOutput = resp.json.at("usage.output_tokens").asOpt[Long].getOrElse(-1L),
                tokenText = resp.json.at("usage.input_tokens_details.text_tokens").asOpt[Long].getOrElse(-1L),
                tokenImage = resp.json.at("usage.input_tokens_details.image_tokens").asOpt[Long].getOrElse(-1L),
              )
            )
            case _ => ImagesGenResponseMetadata(
              rateLimit = ChatResponseMetadataRateLimit(
                requestsLimit = headers.getIgnoreCase("x-ratelimit-limit-requests").map(_.toLong).getOrElse(-1L),
                requestsRemaining = headers.getIgnoreCase("x-ratelimit-remaining-requests").map(_.toLong).getOrElse(-1L),
                tokensLimit = headers.getIgnoreCase("x-ratelimit-limit-tokens").map(_.toLong).getOrElse(-1L),
                tokensRemaining = headers.getIgnoreCase("x-ratelimit-remaining-tokens").map(_.toLong).getOrElse(-1L),
              ), impacts = None, costs = None,
              usage = ImagesGenResponseMetadataUsage.empty
            )
          }
        ))
      } else {
        Left(Json.obj("status" -> resp.status, "body" -> resp.json))
      }
    }
  }
}