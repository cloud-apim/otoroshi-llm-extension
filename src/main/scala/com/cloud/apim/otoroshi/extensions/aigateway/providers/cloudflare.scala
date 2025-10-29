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
class CloudflareApi(val accountId: String, val modelName: String, token: String, timeout: FiniteDuration = 3.minutes, env: Env) extends NoStreamingApiClient[CloudflareApiResponse] {

  override def supportsTools: Boolean = false
  override def supportsCompletion: Boolean = false

  override def call(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[Either[JsValue, CloudflareApiResponse]] = {
    val url = s"${CloudflareApi.url(accountId, modelName)}"
    ProviderHelpers.logCall("Cloudflare", method, url, body)(env)
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
      .map(r => ProviderHelpers.wrapResponse("Cloudflare", r, env) { resp =>
        CloudflareApiResponse(resp.status, resp.headers.mapValues(_.last), resp.json)
      })
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
      allowConfigOverride = json.select("allow_config_override").asOptBoolean.getOrElse(true),
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
  allowConfigOverride: Boolean = true,
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
    "allow_config_override" -> allowConfigOverride,
  )

  def jsonForCall: JsObject = optionsCleanup(json - "wasm_tools" - "tool_functions" - "allow_config_override")
}

class CloudflareChatClient(api: CloudflareApi, options: CloudflareChatClientOptions, id: String) extends ChatClient {

  override def supportsTools: Boolean = api.supportsTools
  override def supportsStreaming: Boolean = api.supportsStreaming
  override def supportsCompletion: Boolean = false

  override def model: Option[String] = api.modelName.some

  override def call(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val obody = originalBody.asObject - "messages" - "provider"
    val mergedOptions = if (options.allowConfigOverride) options.jsonForCall.deepMerge(obody) else options.jsonForCall
    val startTime = System.currentTimeMillis()
    api.call("POST", "", Some(mergedOptions ++ Json.obj("messages" -> prompt.json))).map {
      case Left(err) => err.left
      case Right(resp) =>
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
            reasoningTokens = resp.body.at("usage.completion_tokens_details.reasoning_tokens").asOpt[Long].getOrElse(-1L),
          ),
          None
        )
        val duration: Long = System.currentTimeMillis() - startTime //resp.headers.getIgnoreCase("Cloudflare-processing-ms").map(_.toLong).getOrElse(0L)
        val slug = Json.obj(
          "provider_kind" -> "cloudflare",
          "provider" -> id,
          "duration" -> duration,
          "account_id" -> api.accountId,
          "model_name" ->api.modelName,
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
        val role = resp.body.select("role").asOpt[String].getOrElse("assistant")
        val messages = resp.body.select("result").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map { obj =>
          val content = obj.select("response").asOpt[String].getOrElse("")
          ChatGeneration(ChatMessage.output(role, content, None, obj))
        }
        Right(ChatResponse(messages, usage, resp.body))
    }
  }
}
