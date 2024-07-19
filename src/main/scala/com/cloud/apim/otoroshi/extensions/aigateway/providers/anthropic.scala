package com.cloud.apim.otoroshi.extensions.aigateway.providers

import com.cloud.apim.otoroshi.extensions.aigateway._
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsObject, JsValue, Json}

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

case class AnthropicApiResponse(status: Int, headers: Map[String, String], body: JsValue) {
  def json: JsValue = Json.obj(
    "status" -> status,
    "headers" -> headers,
    "body" -> body,
  )
}
object AnthropicModels {
  val CLAUDE_3_OPUS = "claude-3-opus-20240229"
  val CLAUDE_3_SONNET = "claude-3-sonnet-20240229"
  val CLAUDE_3_HAIKU = "claude-3-haiku-20240307"
}
object AnthropicApi {
  val baseUrl = "https://api.anthropic.com"
}
class AnthropicApi(baseUrl: String = AnthropicApi.baseUrl, token: String, timeout: FiniteDuration = 10.seconds, env: Env) {

  def call(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[AnthropicApiResponse] = {
    env.Ws
      .url(s"${baseUrl}${path}")
      .withHttpHeaders(
        "Authorization" -> s"x-api-key ${token}",
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
      .map { resp =>
        AnthropicApiResponse(resp.status, resp.headers.mapValues(_.last), resp.json)
      }
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
  )
}

class AnthropicChatClient(api: AnthropicApi, options: AnthropicChatClientOptions, id: String) extends ChatClient {

  override def call(prompt: ChatPrompt, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val mergedOptions = options.json.deepMerge(prompt.options.map(_.json).getOrElse(Json.obj()))
    api.call("POST", "/v1/messages", Some(mergedOptions ++ Json.obj("messages" -> prompt.json))).map { resp =>
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
      )
      val duration: Long = resp.headers.getIgnoreCase("anthropic-processing-ms").map(_.toLong).getOrElse(0L)
      val slug = Json.obj(
        "provider_kind" -> "anthropic",
        "provider" -> id,
        "duration" -> duration,
        "model" -> options.model.json,
        "rate_limit" -> usage.rateLimit.json,
        "usage" -> usage.usage.json
      )
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
        ChatGeneration(ChatMessage(role, content))
      }
      Right(ChatResponse(messages, usage))
    }
  }
}
