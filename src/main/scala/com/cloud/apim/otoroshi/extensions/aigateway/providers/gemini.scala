package com.cloud.apim.otoroshi.extensions.aigateway.providers

import com.cloud.apim.otoroshi.extensions.aigateway._
import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsObject, JsValue, Json}
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel
import dev.langchain4j.store.embedding.EmbeddingSearchRequest

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.asScalaBufferConverter

case class GeminiApiResponse(status: Int, headers: Map[String, String], body: JsValue) {
  def json: JsValue = Json.obj(
    "status" -> status,
    "headers" -> headers,
    "body" -> body,
  )
}
object GeminiModels {
  val GEMINI_1_5_FLASH = "gemini-1.5-flash"
}
object GeminiApi {
  val baseUrl = "https://generativelanguage.googleapis.com"
}
class GeminiApi(baseUrl: String = GeminiApi.baseUrl, token: String, timeout: FiniteDuration = 10.seconds, env: Env) {

  def call(method: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[GeminiApiResponse] = {

    val model = body.orJsNull.select("model").asOpt[String].getOrElse(GeminiModels.GEMINI_1_5_FLASH)
    val finalGeminiUrl = s"${baseUrl}/v1beta/models/${model}:generateContent?key=${token}"

    env.Ws
      .url(finalGeminiUrl)
      .withHttpHeaders(
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
        GeminiApiResponse(resp.status, resp.headers.mapValues(_.last), resp.json)
      }
  }
}

object GeminiChatClientOptions {
  def fromJson(json: JsValue): GeminiChatClientOptions = {
    GeminiChatClientOptions(
      model = json.select("model").asOpt[String].getOrElse(GeminiModels.GEMINI_1_5_FLASH),
      maxOutputTokens = json.select("maxOutputTokens").asOpt[Int],
      temperature = json.select("temperature").asOpt[Float].getOrElse(1.0f),
      topP = json.select("topP").asOpt[Float].getOrElse(0.95f),
      topK = json.select("top_k").asOpt[Int].getOrElse(40),
      stopSequences = json.select("stopSequences").asOpt[Array[String]],
    )
  }
}

case class GeminiChatClientOptions(
  model: String = GeminiModels.GEMINI_1_5_FLASH,
  maxOutputTokens: Option[Int] = None,
  temperature: Float = 1,
  topP: Float = 0.95f,
  topK: Int = 1,
  stopSequences: Option[Array[String]] = None
) extends ChatOptions {
  override def json: JsObject = Json.obj(
    "model" -> model,
    "maxOutputTokens" -> maxOutputTokens,
    "temperature" -> temperature,
    "topP" -> topP,
    "topK" -> topK,
    "stopSequences" -> stopSequences
  )
}

class GeminiChatClient(api: GeminiApi, options: GeminiChatClientOptions, id: String) extends ChatClient {

  override def call(prompt: ChatPrompt, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val mergedOptions = options.json.deepMerge(prompt.options.map(_.json).getOrElse(Json.obj()))
    api.call("POST", Some(mergedOptions ++ Json.obj(
      "contents" -> Json.obj("parts" -> prompt.json),
      "generationConfig" -> options.json
    ))).map { resp =>
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
      )
      val duration: Long = resp.headers.getIgnoreCase("gemini-processing-ms").map(_.toLong).getOrElse(0L)
      val slug = Json.obj(
        "provider_kind" -> "gemini",
        "provider" -> id,
        "duration" -> duration,
        "model" -> options.model.json,
        "rate_limit" -> usage.rateLimit.json,
        "usage" -> usage.usage.json
      )
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
      val messages = resp.body.select("choices").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map { obj =>
        val role = obj.select("message").select("role").asOpt[String].getOrElse("user")
        val content = obj.select("message").select("content").asOpt[String].getOrElse("")
        ChatGeneration(ChatMessage(role, content))
      }
      Right(ChatResponse(messages, usage))
    }
  }
}
