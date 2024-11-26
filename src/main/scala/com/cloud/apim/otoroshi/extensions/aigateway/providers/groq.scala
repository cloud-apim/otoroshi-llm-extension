package com.cloud.apim.otoroshi.extensions.aigateway.providers

import com.cloud.apim.otoroshi.extensions.aigateway._
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.libs.ws.WSResponse

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

case class GroqApiResponse(status: Int, headers: Map[String, String], body: JsValue) {
  def json: JsValue = Json.obj(
    "status" -> status,
    "headers" -> headers,
    "body" -> body,
  )
}
object GroqModels {
  val LLAMA_3_DEFAULT_8B = "llama3-8b-8192"
  val DISTIL_WHISPER_LARGE_V3_EN = "distil-whisper-large-v3-en"
  val GEMMA_2_9B = "gemma2-9b-it"
  val GEMMA_7B = "gemma-7b-it"
  val LLAMA_3_GROQ = "llama3-groq-70b-8192-tool-use-preview"
}
object GroqApi {
  val baseUrl = "https://api.groq.com"
}
class GroqApi(baseUrl: String = GroqApi.baseUrl, token: String, timeout: FiniteDuration = 10.seconds, env: Env) {

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

  def call(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[GroqApiResponse] = {
    rawCall(method, path, body)
      .map { resp =>
        GroqApiResponse(resp.status, resp.headers.mapValues(_.last), resp.json)
      }
  }
}

object GroqChatClientOptions {
  def fromJson(json: JsValue): GroqChatClientOptions = {
    GroqChatClientOptions(
      model = json.select("model").asOpt[String].getOrElse(GroqModels.LLAMA_3_DEFAULT_8B),
      tools = json.select("tools").asOpt[Seq[JsValue]],
      tool_choice = json.select("tool_choice").asOpt[Seq[JsValue]],
      max_tokens = json.select("max_tokens").asOpt[Int],
      temperature = json.select("temperature").asOpt[Float].getOrElse(1.0f),
      topP = json.select("top_p").asOpt[Float].getOrElse(1.0f),
      n = json.select("n").asOpt[Int].getOrElse(1),
    )
  }
}

case class GroqChatClientOptions(
    model: String = GroqModels.LLAMA_3_DEFAULT_8B,
    max_tokens: Option[Int] = None,
    stream: Option[Boolean] = Some(false),
    temperature: Float = 1,
    tools: Option[Seq[JsValue]] = None,
    tool_choice: Option[Seq[JsValue]] =  None,
    topP: Float = 1,
    n: Int = 1,
) extends ChatOptions {

  override def json: JsObject = Json.obj(
    "model" -> model,
    "max_tokens" -> max_tokens,
    "stream" -> stream,
    "temperature" -> temperature,
    "top_p" -> topP,
    "tools" -> tools,
    "tool_choice" -> tool_choice,
    "n" -> n,
  )

  override def topK: Int = 0
}

class GroqChatClient(api: GroqApi, options: GroqChatClientOptions, id: String) extends ChatClient {

  // openai compat: true
  // supports tools: true
  // supports streaming: true

  override def model: Option[String] = options.model.some

  override def listModels()(implicit ec: ExecutionContext): Future[Either[JsValue, List[String]]] = {
    api.rawCall("GET", "/openai/v1/models", None).map { resp =>
      if (resp.status == 200) {
        Right(resp.json.select("data").as[List[JsObject]].map(obj => obj.select("id").asString))
      } else {
        Left(Json.obj("error" -> s"bad response code: ${resp.status}"))
      }
    }
  }

  override def call(prompt: ChatPrompt, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val mergedOptions = options.json.deepMerge(prompt.options.map(_.json).getOrElse(Json.obj()))
    api.call("POST", "/openai/v1/chat/completions", Some(mergedOptions ++ Json.obj("messages" -> prompt.json))).map { resp =>
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
      val duration: Long = resp.body.select("total_time").asOpt[Long].getOrElse(0L)
      val slug = Json.obj(
        "provider_kind" -> "Groq",
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
