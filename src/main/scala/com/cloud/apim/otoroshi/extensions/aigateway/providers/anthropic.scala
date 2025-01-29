package com.cloud.apim.otoroshi.extensions.aigateway.providers

import akka.stream.scaladsl.{Framing, Source}
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway._
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{GenericApiResponseChoiceMessageToolCall, LlmFunctions}
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
  lazy val content: Option[String] = raw.at("content_block.text").asOptString.orElse(raw.at("delta.text").asOptString)
  lazy val finish_reason: Option[String] = raw.at("delta.stop_reason").asOptString
}

case class AnthropicApiResponseChunk(raw: JsValue, messageRef: Option[JsValue]) {
  lazy val id: String = messageRef.flatMap(_.select("id").asOptString).getOrElse("--")
  lazy val created: Long = messageRef.flatMap(_.select("created").asOptLong).getOrElse(0L)
  lazy val model: String = messageRef.flatMap(_.select("model").asOptString).getOrElse("--")
  lazy val role: String = messageRef.flatMap(_.select("role").asOptString).getOrElse("--")
  lazy val usage: Option[AnthropicChatResponseChunkUsage] = raw.select("usage").asOpt[JsObject].map { obj =>
    AnthropicChatResponseChunkUsage(obj)
  }
  lazy val choices: Seq[AnthropicChatResponseChunkChoice] = Seq(AnthropicChatResponseChunkChoice(raw, role))
}

case class AnthropicApiResponse(status: Int, headers: Map[String, String], body: JsValue) {
  def json: JsValue = Json.obj(
    "status" -> status,
    "headers" -> headers,
    "body" -> body,
  )
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
class AnthropicApi(baseUrl: String = AnthropicApi.baseUrl, token: String, timeout: FiniteDuration = 10.seconds, env: Env) extends ApiClient[AnthropicApiResponse, AnthropicApiResponseChunk] {

  val providerName = "anthropic"
  override def supportsTools: Boolean = false
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

  override def callWithToolSupport(method: String, path: String, body: Option[JsValue], mcpConnectors: Seq[String])(implicit ec: ExecutionContext): Future[Either[JsValue, AnthropicApiResponse]] = {
    // // TODO: accumulate consumptions ???
    // if (body.flatMap(_.select("tools").asOpt[JsArray]).exists(_.value.nonEmpty)) {
    //   call(method, path, body).flatMap {
    //     case Left(err) => err.leftf
    //     case Right(resp) if resp.finishBecauseOfToolCalls => {
    //       body match {
    //         case None => resp.rightf
    //         case Some(body) => {
    //           val messages = body.select("messages").asOpt[Seq[JsObject]].getOrElse(Seq.empty) //.map(v => v.flatMap(o => ChatMessage.format.reads(o).asOpt)).getOrElse(Seq.empty)
    //           val toolCalls = resp.toolCalls
    //           LlmFunctions.callToolsOpenai(toolCalls.map(tc => GenericApiResponseChoiceMessageToolCall(tc.raw)), mcpConnectors, providerName)(ec, env)
    //             .flatMap { callResps =>
    //               // val newMessages: Seq[JsValue] = messages.map(_.json) ++ callResps
    //               val newMessages: Seq[JsValue] = messages ++ callResps
    //               val newBody = body.asObject ++ Json.obj("messages" -> JsArray(newMessages))
    //               callWithToolSupport(method, path, newBody.some, mcpConnectors)
    //             }
    //         }
    //       }
    //     }
    //     case Right(resp) =>
    //       // println(s"resp: ${resp.status} - ${resp.body.prettify}")
    //       resp.rightf
    //   }
    // } else {
    //   call(method, path, body)
    // }
    ???
  }

  override def stream(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[Either[JsValue, (Source[AnthropicApiResponseChunk, _], WSResponse)]] = {
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
          .filterNot(_.select("type").asOptString.contains("content_block_stop"))
          .takeWhile(!_.select("type").asOptString.contains("message_stop"))
          .map(json => AnthropicApiResponseChunk(json, Option(messageRef.get())))
          , resp)
      })
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
      temperature = json.select("temperature").asOpt[Float].getOrElse(1.0f),
      topP = json.select("top_p").asOpt[Float].getOrElse(1.0f),
      topK = json.select("top_k").asOpt[Int].getOrElse(0),
      system = json.select("system").asOpt[String],
      allowConfigOverride = json.select("allow_config_override").asOptBoolean.getOrElse(true),
      wasmTools = json.select("wasm_tools").asOpt[Seq[String]].getOrElse(Seq.empty),
      mcpConnectors = json.select("mcp_connectors").asOpt[Seq[String]].getOrElse(Seq.empty),
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
  temperature: Float = 1,
  tools: Option[Seq[JsValue]] = None,
  tool_choice: Option[Seq[JsValue]] =  None,
  topK: Int = 0,
  topP: Float = 1,
  allowConfigOverride: Boolean = true,
  wasmTools: Seq[String] = Seq.empty,
  mcpConnectors: Seq[String] = Seq.empty,
) extends ChatOptions {

  override def json: JsObject = Json.obj(
    "model" -> model,
    "max_tokens" -> max_tokens,
    "stream" -> stream,
    "system" -> system,
    "temperature" -> temperature,
    "top_p" -> topP,
    "top_k" -> topK,
    "tools" -> tools,
    "tool_choice" -> tool_choice,
    "metadata" -> metadata,
    "stop_sequences" -> stop_sequences,
    "allow_config_override" -> allowConfigOverride,
    "wasm_tools" -> JsArray(wasmTools.map(_.json)),
    "mcp_connectors" -> JsArray(mcpConnectors.map(_.json)),
  )

  def jsonForCall: JsObject = optionsCleanup(json - "wasm_tools" - "mcp_connectors" - "allow_config_override")
}

class AnthropicChatClient(api: AnthropicApi, options: AnthropicChatClientOptions, id: String) extends ChatClient {

  // supports tools: true
  // supports streaming: true

  override def supportsTools: Boolean = api.supportsTools
  override def supportsStreaming: Boolean = api.supportsStreaming
  override def supportsCompletion: Boolean = false

  override def model: Option[String] = options.model.some

  override def listModels()(implicit ec: ExecutionContext): Future[Either[JsValue, List[String]]] = {
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
    api.call("POST", "/v1/messages", Some(mergedOptions ++ Json.obj("messages" -> prompt.jsonWithFlavor(ChatMessageContentFlavor.Anthropic)))).map {
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
        val duration: Long = resp.headers.getIgnoreCase("anthropic-processing-ms").map(_.toLong).getOrElse(0L)
        val slug = Json.obj(
          "provider_kind" -> "anthropic",
          "provider" -> id,
          "duration" -> duration,
          "model" -> options.model.json,
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
        Right(ChatResponse(messages, usage))
    }
  }

  override def stream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    val body = originalBody.asObject - "messages" - "provider"
    val mergedOptions = if (options.allowConfigOverride) options.jsonForCall.deepMerge(body) else options.jsonForCall
    val callF = if (api.supportsTools && (options.wasmTools.nonEmpty || options.mcpConnectors.nonEmpty)) {
      val tools = LlmFunctions.tools(options.wasmTools, options.mcpConnectors)
      api.streamWithToolSupport("POST", "/v1/messages", Some(mergedOptions ++ tools ++ Json.obj("messages" -> prompt.jsonWithFlavor(ChatMessageContentFlavor.Anthropic))), options.mcpConnectors)
    } else {
      api.stream("POST", "/v1/messages", Some(mergedOptions ++ Json.obj("messages" -> prompt.jsonWithFlavor(ChatMessageContentFlavor.Anthropic))))
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
            val duration: Long = resp.header("openai-processing-ms").map(_.toLong).getOrElse(0L)
            val slug = Json.obj(
              "provider_kind" -> "anthropic",
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

