package com.cloud.apim.otoroshi.extensions.aigateway.providers

import com.cloud.apim.otoroshi.extensions.aigateway._
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.libs.ws.WSResponse

import scala.concurrent._
import scala.concurrent.duration._


case class MistralAiApiResponse(status: Int, headers: Map[String, String], body: JsValue) {
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
class MistralAiApi(baseUrl: String = MistralAiApi.baseUrl, token: String, timeout: FiniteDuration = 10.seconds, env: Env) {

  def rawCall(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[WSResponse] = {
    env.Ws
      .url(s"${baseUrl}${path}")
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

  def call(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[MistralAiApiResponse] = {
    rawCall(method, path, body)
      .map { resp =>
        MistralAiApiResponse(resp.status, resp.headers.mapValues(_.last), resp.json)
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
) extends ChatOptions {
  override def topK: Int = 0

  override def json: JsObject = Json.obj(
    "model" -> model,
    "max_tokens" -> max_tokens,
    "random_seed" -> random_seed,
    "safe_prompt" -> safe_prompt,
    "temperature" -> temperature,
    "top_p" -> topP,
  )
}

class MistralAiChatClient(api: MistralAiApi, options: MistralAiChatClientOptions, id: String) extends ChatClient {

  // openai compat: true
  // supports tools: true
  // supports streaming: true

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

  override def call(prompt: ChatPrompt, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val mergedOptions = options.json.deepMerge(prompt.options.map(_.json).getOrElse(Json.obj()))
    api.call("POST", "/v1/chat/completions", Some(mergedOptions ++ Json.obj("messages" -> prompt.json))).map { resp =>
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
        ChatGeneration(ChatMessage(role, content))
      }
      Right(ChatResponse(messages, usage))
    }
  }
}
