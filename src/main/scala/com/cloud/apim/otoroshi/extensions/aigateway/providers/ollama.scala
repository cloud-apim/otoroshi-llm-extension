package com.cloud.apim.otoroshi.extensions.aigateway.providers

import com.cloud.apim.otoroshi.extensions.aigateway._
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import scala.concurrent._
import scala.concurrent.duration._

case class OllamaAiApiResponse(status: Int, headers: Map[String, String], body: JsValue) {
  def json: JsValue = Json.obj(
    "status" -> status,
    "headers" -> headers,
    "body" -> body,
  )
}
object OllamaAiApi {
  val baseUrl = "http://localhost:11434"
}
class OllamaAiApi(baseUrl: String = OllamaAiApi.baseUrl, token: Option[String], timeout: FiniteDuration = 10.seconds, env: Env) {

  def call(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[OllamaAiApiResponse] = {
    println("calling ollama")
    env.Ws
      .url(s"${baseUrl}${path}")
      .withHttpHeaders(
        "Accept" -> "application/json",
      )
      .applyOnWithOpt(token) {
        case (builder, token) => builder
          .addHttpHeaders(
            "Authorization" -> s"Bearer ${token}",
          )
      }
      .applyOnWithOpt(body) {
        case (builder, body) => builder
          .addHttpHeaders("Content-Type" -> "application/json")
          .withBody(body)
      }
      .withMethod(method)
      .withRequestTimeout(timeout)
      .execute()
      .map { resp =>
        OllamaAiApiResponse(resp.status, resp.headers.mapValues(_.last), resp.json)
      }
  }
}

object OllamaAiChatClientOptions {
  def fromJson(json: JsValue): OllamaAiChatClientOptions = {
    OllamaAiChatClientOptions(
      model = json.select("model").asOpt[String].getOrElse("llama2"),
      num_predict = json.select("num_predict").asOpt[Int],
      tfs_z = json.select("tfs_z").asOpt[Double],
      seed = json.select("seed").asOpt[Int],
      temper = json.select("temperature").asOpt[Double].getOrElse(0.7),
      top_p = json.select("top_p").asOpt[Double].getOrElse(0.9),
      top_k = json.select("top_k").asOpt[Int].getOrElse(40),
      repeat_penalty = json.select("repeat_penalty").asOpt[Double],
      repeat_last_n = json.select("repeat_last_n").asOpt[Int],
      num_thread = json.select("num_thread").asOpt[Int],
      num_gpu = json.select("num_gpu").asOpt[Int],
      num_gqa = json.select("num_gqa").asOpt[Int],
      num_ctx = json.select("num_ctx").asOpt[Int],
    )
  }
}

// https://github.com/ollama/ollama/blob/main/docs/modelfile.md#valid-parameters-and-values
case class OllamaAiChatClientOptions(
   model: String = "llama2",
   num_predict: Option[Int] = None,
   tfs_z: Option[Double] = None,
   seed: Option[Int] = None,
   temper: Double = 0.7,
   top_p: Double = 0.9,
   top_k: Int = 40,
   repeat_penalty: Option[Double] = None,
   repeat_last_n: Option[Int] = None,
   num_thread: Option[Int] = None,
   num_gpu: Option[Int] = None,
   num_gqa: Option[Int] = None,
   num_ctx: Option[Int] = None,
 ) extends ChatOptions {

  def temperature: Float = temper.toFloat
  def topP: Float = top_p.toFloat
  def topK: Int = top_k

  override def json: JsObject = Json.obj(
    "model" -> model,
    "num_predict" -> num_predict,
    "seed" -> seed,
    "temperature" -> temper,
    "top_p" -> top_p,
    "top_k" -> top_k,
    "repeat_penalty" -> repeat_penalty,
    "repeat_last_n" -> repeat_last_n,
    "num_thread" -> num_thread,
    "num_gpu" -> num_gpu,
    "num_gqa" -> num_gqa,
    "num_ctx" -> num_ctx,
  )
}

class OllamaAiChatClient(api: OllamaAiApi, options: OllamaAiChatClientOptions, id: String) extends ChatClient {
  override def call(prompt: ChatPrompt, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val mergedOptions = options.json.deepMerge(prompt.options.map(_.json).getOrElse(Json.obj())).applyOn(_ - "model")
    api.call("POST", "/api/chat", Some(Json.obj(
      "model" -> options.model,
      "stream" -> false,
      "messages" -> prompt.json,
      "options" -> mergedOptions
    ))).map { resp =>
      val usage = ChatResponseMetadata(
        ChatResponseMetadataRateLimit(
          requestsLimit = resp.headers.getIgnoreCase("x-ratelimit-limit-requests").map(_.toLong).getOrElse(-1L),
          requestsRemaining = resp.headers.getIgnoreCase("x-ratelimit-remaining-requests").map(_.toLong).getOrElse(-1L),
          tokensLimit = resp.headers.getIgnoreCase("x-ratelimit-limit-tokens").map(_.toLong).getOrElse(-1L),
          tokensRemaining = resp.headers.getIgnoreCase("x-ratelimit-remaining-tokens").map(_.toLong).getOrElse(-1L),
        ),
        ChatResponseMetadataUsage(
          promptTokens = resp.body.select("prompt_eval_count").asOpt[Long].getOrElse(-1L),
          generationTokens = resp.body.select("eval_count").asOpt[Long].getOrElse(-1L),
        ),
      )
      val duration: Long = resp.body.select("total_duration").asOpt[Long].map(_ / 100000).getOrElse(-1L)
      val slug = Json.obj(
        "provider_kind" -> "ollama",
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
      val role = resp.body.select("message").select("role").asOpt[String].getOrElse("user")
      val content = resp.body.select("message").select("content").asOpt[String].getOrElse("")
      val message = ChatGeneration(ChatMessage(role, content))
      Right(ChatResponse(Seq(message), usage))
    }
  }
}
