package com.cloud.apim.otoroshi.extensions.aigateway.providers

import akka.stream.scaladsl.Source
import com.cloud.apim.otoroshi.extensions.aigateway._
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.libs.ws.WSResponse

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

case class AnthropicApiResponseChunk()

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
class AnthropicApi(baseUrl: String = AnthropicApi.baseUrl, token: String, timeout: FiniteDuration = 10.seconds, env: Env) extends NoStreamingApiClient[AnthropicApiResponse] {

  override def supportsTools: Boolean = false

  override def supportsCompletion: Boolean = true

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
}

object AnthropicChatClientOptions {
  def fromJson(json: JsValue): AnthropicChatClientOptions = {
    AnthropicChatClientOptions(
      model = json.select("model").asOpt[String].getOrElse(OpenAiModels.GPT_3_5_TURBO),
      metadata = json.select("metadata").asOpt[JsObject],
      stop_sequences = json.select("stop_sequences").asOpt[Seq[String]],
      tools = json.select("tools").asOpt[Seq[JsValue]],
      tool_choice = json.select("tool_choice").asOpt[Seq[JsValue]],
      max_tokens = json.select("max_tokens").asOpt[Int],
      temperature = json.select("temperature").asOpt[Float].getOrElse(1.0f),
      topP = json.select("top_p").asOpt[Float].getOrElse(1.0f),
      topK = json.select("top_k").asOpt[Int].getOrElse(0),
      system = json.select("system").asOpt[String],
      allowConfigOverride = json.select("allow_config_override").asOptBoolean.getOrElse(true),
    )
  }
}

case class AnthropicChatClientOptions(
  model: String = AnthropicModels.CLAUDE_3_HAIKU,
  max_tokens: Option[Int] = None,
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
  )

  def jsonForCall: JsObject = optionsCleanup(json - "wasm_tools" - "allow_config_override")
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
    api.call("POST", "/v1/messages", Some(mergedOptions ++ Json.obj("messages" -> prompt.json))).map {
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
          ChatGeneration(ChatMessage(role, content, None))
        }
        Right(ChatResponse(messages, usage))
    }
  }
}
