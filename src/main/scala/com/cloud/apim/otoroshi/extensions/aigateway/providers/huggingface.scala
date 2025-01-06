package com.cloud.apim.otoroshi.extensions.aigateway.providers

import com.cloud.apim.otoroshi.extensions.aigateway._
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsObject, JsValue, Json}

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

case class HuggingfaceApiResponse(status: Int, headers: Map[String, String], body: JsValue) {
  def json: JsValue = Json.obj(
    "status" -> status,
    "headers" -> headers,
    "body" -> body,
  )
}
object HuggingfaceModels {
  val GOOGLE_GEMMA_2_2B = "google/gemma-2-2b-it"
  val STARCODER = "bigcode/starcoder"
}
object HuggingfaceApi {
  // POST https://api-inference.huggingface.co/models/google/gemma-2-2b-it
  def url(modelName: String): String = {
    s"https://api-inference.huggingface.co/models/${modelName}/v1/chat/completions"
  }
}
class HuggingfaceApi(val modelName: String, token: String, timeout: FiniteDuration = 10.seconds, env: Env) {

  def call(method: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[HuggingfaceApiResponse] = {
    env.Ws
      .url(s"${HuggingfaceApi.url(modelName)}")
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
        HuggingfaceApiResponse(resp.status, resp.headers.mapValues(_.last), resp.json)
      }
  }
}

object HuggingfaceChatClientOptions {
  def fromJson(json: JsValue): HuggingfaceChatClientOptions = {
    HuggingfaceChatClientOptions(
      max_tokens = json.select("max_tokens").asOpt[Int],
      temperature = json.select("temperature").asOpt[Float].getOrElse(1.0f),
      topP = json.select("top_p").asOpt[Float].getOrElse(1.0f),
      allowConfigOverride = json.select("allow_config_override").asOptBoolean.getOrElse(true),
    )
  }
}

case class HuggingfaceChatClientOptions(
  frequency_penalty: Option[Double] = None,
  stream: Option[Boolean] = Some(false),
  max_tokens: Option[Int] = None,
  seed: Option[Int] = None,
  stop: Option[Seq[String]] = None,
  temperature: Float = 1,
  topP: Float = 1,
  allowConfigOverride: Boolean = true,
) extends ChatOptions {

  override def json: JsObject = Json.obj(
    "frequency_penalty" -> frequency_penalty,
    "stream" -> stream,
    "max_tokens" -> max_tokens,
    "seed" -> seed,
    "stop" -> stop,
    "temperature" -> temperature,
    "top_p" -> topP,
    "allow_config_override" -> allowConfigOverride,
  )

  def jsonForCall: JsObject = json - "wasm_tools" - "allow_config_override"

  override def topK: Int = 0
}

class HuggingfaceChatClient(api: HuggingfaceApi, options: HuggingfaceChatClientOptions, id: String) extends ChatClient {

  // openai compat: false
  // supports tools: false
  // supports streaming: true

  override def model: Option[String] = api.modelName.some

  override def call(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val obody = originalBody.asObject - "messages" - "provider"
    val mergedOptions = if (options.allowConfigOverride) options.jsonForCall.deepMerge(obody) else options.json
    api.call("POST", Some(mergedOptions ++ Json.obj("messages" -> prompt.json))).map { resp =>
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
        ),
        None
      )
      val duration: Long = resp.headers.getIgnoreCase("Huggingface-processing-ms").map(_.toLong).getOrElse(0L)
      val slug = Json.obj(
        "provider_kind" -> "huggingface",
        "provider" -> id,
        "duration" -> duration,
        "model" -> api.modelName,
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
        ChatGeneration(ChatMessage(role, content, None))
      }

      Right(ChatResponse(messages, usage))
    }
  }
}
