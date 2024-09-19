package com.cloud.apim.otoroshi.extensions.aigateway.providers

import com.cloud.apim.otoroshi.extensions.aigateway._
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsObject, JsValue, Json}

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

case class CloudflareApiResponse(status: Int, headers: Map[String, String], body: JsValue) {
  def json: JsValue = Json.obj(
    "status" -> status,
    "headers" -> headers,
    "body" -> body,
  )
}
object CloudflareApi {
  // POST https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/run/{model_name}
  def url(accountId: String, modelName: String): String = {
    s"https://api.cloudflare.com/client/v4/accounts/${accountId}/ai/run/${modelName}"
  }
}
class CloudflareApi(val accountId: String, val modelName: String, token: String, timeout: FiniteDuration = 10.seconds, env: Env) {

  def call(method: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[CloudflareApiResponse] = {
    env.Ws
      .url(s"${CloudflareApi.url(accountId, modelName)}")
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
        CloudflareApiResponse(resp.status, resp.headers.mapValues(_.last), resp.json)
      }
  }
}

object CloudflareChatClientOptions {
  def fromJson(json: JsValue): CloudflareChatClientOptions = {
    CloudflareChatClientOptions(
      frequency_penalty = json.select("frequency_penalty").asOpt[Float],
      max_tokens = json.select("max_tokens").asOpt[Int],
      prompt = json.select("prompt").as[String],
      seed = json.select("seed").asOpt[Int],
      stream = json.select("stream").asOpt[Boolean],
      temperature = json.select("temperature").asOpt[Float].getOrElse(0.6f),
      topK = json.select("topK").asOpt[Int].getOrElse(1),
      topP = json.select("topP").asOpt[Float].getOrElse(0f),
    )
  }
}

case class CloudflareChatClientOptions(
  frequency_penalty: Option[Float] = Some(0.0f),
  max_tokens: Option[Int] = Some(256),
  prompt: String = "",
  seed: Option[Int] = Some(1),
  stream: Option[Boolean] = Some(false),
  temperature: Float = 0.6f,
  topK: Int = 1,
  topP: Float = 0,
) extends ChatOptions {

  override def json: JsObject = Json.obj(
    "frequency_penalty" -> frequency_penalty,
    "max_tokens" -> max_tokens,
    "prompt" -> prompt,
    "seed" -> seed,
    "stream" -> stream,
    "temperature" -> temperature,
    "top_k" -> topK,
    "top_p" -> topP,
  )
}

class CloudflareChatClient(api: CloudflareApi, options: CloudflareChatClientOptions, id: String) extends ChatClient {

  override def call(prompt: ChatPrompt, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val mergedOptions = options.json.deepMerge(prompt.options.map(_.json).getOrElse(Json.obj()))
    api.call("POST", Some(mergedOptions ++ Json.obj("messages" -> prompt.json))).map { resp =>
      val usage = ChatResponseMetadata(
        ChatResponseMetadataRateLimit(
          requestsLimit = resp.headers.getIgnoreCase("x-ratelimit-requests-limit").map(_.toLong).getOrElse(-1L),
          requestsRemaining = resp.headers.getIgnoreCase("x-ratelimit-requests-remaining").map(_.toLong).getOrElse(-1L),
          tokensLimit = resp.headers.getIgnoreCase("x-ratelimit-tokens-limit").map(_.toLong).getOrElse(-1L),
          tokensRemaining = resp.headers.getIgnoreCase("x-ratelimit-tokens-remaining").map(_.toLong).getOrElse(-1L),
        ),
        ChatResponseMetadataUsage(
          promptTokens = resp.body.select("usage").select("input_tokens").asOpt[Long].getOrElse(-1L),
          generationTokens = resp.body.select("usage").select("output_tokens").asOpt[Long].getOrElse(-1L),
        ),
      )
      val duration: Long = resp.headers.getIgnoreCase("Cloudflare-processing-ms").map(_.toLong).getOrElse(0L)
      val slug = Json.obj(
        "provider_kind" -> "cloudflare",
        "provider" -> id,
        "duration" -> duration,
        "account_id" -> api.accountId,
        "model_name" ->api.modelName,
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
      val messages = resp.body.select("result").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map { obj =>
        val content = obj.select("response").asOpt[String].getOrElse("")
        ChatGeneration(ChatMessage(role, content))
      }
      Right(ChatResponse(messages, usage))
    }
  }
}
