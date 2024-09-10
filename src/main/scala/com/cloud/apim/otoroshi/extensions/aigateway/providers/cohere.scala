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

case class CohereAiApiResponse(status: Int, headers: Map[String, String], body: JsValue) {
  def json: JsValue = Json.obj(
    "status" -> status,
    "headers" -> headers,
    "body" -> body,
  )
}
object CohereAiModels {
  val DEFAULT_COHERE_MODEL = "command-r-plus-08-2024"
}
object CohereAiApi {
  val baseUrl = "https://api.cohere.com"
}
class CohereAiApi(baseUrl: String = CohereAiApi.baseUrl, token: String, timeout: FiniteDuration = 10.seconds, env: Env) {

  def call(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[CohereAiApiResponse] = {
    println("calling CohereAi")
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
      .map { resp =>
        CohereAiApiResponse(resp.status, resp.headers.mapValues(_.last), resp.json)
      }
  }
}

object CohereAiChatClientOptions {
  def fromJson(json: JsValue): CohereAiChatClientOptions = {
    CohereAiChatClientOptions(
      model = json.select("model").asOpt[String].getOrElse(CohereAiModels.DEFAULT_COHERE_MODEL),
      max_tokens = json.select("max_tokens").asOpt[Int],
      max_input_tokens = json.select("max_input_tokens").asOpt[Int],
      k = json.select("k").asOpt[Int].getOrElse(0),
      p = json.select("p").asOpt[Double].getOrElse(0.75f),
      temperature = json.select("temperature").asOpt[Float].getOrElse(0.3f)
    )
  }
}

case class CohereAiChatClientOptions(
  model: String = CohereAiModels.DEFAULT_COHERE_MODEL,
  max_tokens: Option[Int] = None,
  max_input_tokens: Option[Int] = None,
  stream: Boolean = false,
  temperature: Float = 0.3f,
  k: Option[Int] = Some(0),
  p: Option[Double] = Some(1.0),
  frequency_penalty: Option[Double] = None,
) extends ChatOptions {

  override def json: JsObject = Json.obj(
    "model" -> model,
    "max_tokens" -> max_tokens,
    "max_input_tokens" -> max_input_tokens,
    "stream" -> stream,
    "temperature" -> temperature,
    "k" -> k.getOrElse(0).json,
    "p" -> p.getOrElse(0.75f).json,
    "frequency_penalty" -> frequency_penalty,
  )
}

class CohereAiChatClient(api: CohereAiApi, options: CohereAiChatClientOptions, id: String) extends ChatClient {

  override def call(prompt: ChatPrompt, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val mergedOptions = options.json.deepMerge(prompt.options.map(_.json).getOrElse(Json.obj()))
    api.call("POST", "/v1/chat", Some(mergedOptions ++ Json.obj("messages" -> prompt.json))).map { resp =>
      val usage = ChatResponseMetadata(
        ChatResponseMetadataRateLimit(
          requestsLimit = resp.headers.getIgnoreCase("x-ratelimit-limit-requests").map(_.toLong).getOrElse(-1L),
          requestsRemaining = resp.headers.getIgnoreCase("x-ratelimit-remaining-requests").map(_.toLong).getOrElse(-1L),
          tokensLimit = resp.headers.getIgnoreCase("x-ratelimit-limit-tokens").map(_.toLong).getOrElse(-1L),
          tokensRemaining = resp.headers.getIgnoreCase("x-ratelimit-remaining-tokens").map(_.toLong).getOrElse(-1L),
        ),
        ChatResponseMetadataUsage(
          promptTokens = resp.body.select("meta").select("tokens").select("input_tokens").asOpt[Long].getOrElse(-1L),
          generationTokens = resp.body.select("meta").select("tokens").select("output_tokens").asOpt[Long].getOrElse(-1L),
        ),
      )
      val duration: Long = resp.headers.getIgnoreCase("CohereAi-processing-ms").map(_.toLong).getOrElse(0L)
      val slug = Json.obj(
        "provider_kind" -> "cohere",
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
      val messages = resp.body.select("chat_history").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map { obj =>
        val role = obj.select("role").asOpt[String].getOrElse("CHATBOT")
        val content = obj.select("message").asOpt[String].getOrElse("")
        ChatGeneration(ChatMessage(role, content))
      }
      Right(ChatResponse(messages, usage))
    }
  }
}
