package com.cloud.apim.otoroshi.extensions.aigateway.providers

import com.cloud.apim.otoroshi.extensions.aigateway._
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsObject, JsValue, Json}

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

case class OVHAiEndpointsApiResponse(status: Int, headers: Map[String, String], body: JsValue) {
  def json: JsValue = Json.obj(
    "status" -> status,
    "headers" -> headers,
    "body" -> body,
  )
}

object OVHAiEndpointsModels {
  val codellama_13b_instruct_hf = "CodeLlama-13b-Instruct-hf"
  val mixtral_8x7b_instruct_v01 = "Mixtral-8x7B-Instruct-v0.1"
  val llama_3_70b_instruct = "Meta-Llama-3-70B-Instruct"
  val llama_2_13b_chat_hf = "Llama-2-13b-chat-hf"
  val mixtral_8x22b_instruct_v01 = "Mixtral-8x22B-Instruct-v0.1"
  val mistral_7b_instruct_v02 = "Mistral-7B-Instruct-v0.2"
  val llama_3_8b_instruct = "Meta-Llama-3-8B-Instruct"
  val modelUrls = Map(
    "CodeLlama-13b-Instruct-hf" -> "codellama-13b-instruct-hf.endpoints.kepler.ai.cloud.ovh.net",
    "Mixtral-8x7B-Instruct-v0.1" -> "mixtral-8x7b-instruct-v01.endpoints.kepler.ai.cloud.ovh.net",
    "Meta-Llama-3-70B-Instruct" -> "llama-3-70b-instruct.endpoints.kepler.ai.cloud.ovh.net",
    "Llama-2-13b-chat-hf" -> "llama-2-13b-chat-hf.endpoints.kepler.ai.cloud.ovh.net",
    "Mixtral-8x22B-Instruct-v0.1" -> "mixtral-8x22b-instruct-v01.endpoints.kepler.ai.cloud.ovh.net",
    "Mistral-7B-Instruct-v0.2" -> "mistral-7b-instruct-v02.endpoints.kepler.ai.cloud.ovh.net",
    "Meta-Llama-3-8B-Instruct" -> "llama-3-8b-instruct.endpoints.kepler.ai.cloud.ovh.net",
  )
}
object OVHAiEndpointsApi {
  val baseDomain = "endpoints.kepler.ai.cloud.ovh.net"
}
class OVHAiEndpointsApi(baseDomain: String = OVHAiEndpointsApi.baseDomain, token: String, timeout: FiniteDuration = 10.seconds, env: Env) {

  def call(model: String, method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[OVHAiEndpointsApiResponse] = {
    // println(s"calling ${method} ${baseUrl}${path}: ${body}")
    val url = OVHAiEndpointsModels.modelUrls.get(model).getOrElse(s"${model.toLowerCase().replaceAll("\\.", "")}.${baseDomain}")
    env.Ws
      .url(s"https://${url}${path}")
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
        // println(s"resp: ${resp.status} - ${resp.body}")
        OVHAiEndpointsApiResponse(resp.status, resp.headers.mapValues(_.last), resp.json)
      }
  }
}

object OVHAiEndpointsChatClientOptions {
  def fromJson(json: JsValue): OVHAiEndpointsChatClientOptions = {
    OVHAiEndpointsChatClientOptions(
      model = json.select("model").asOpt[String].getOrElse(OVHAiEndpointsModels.mixtral_8x22b_instruct_v01),
      max_tokens = json.select("max_tokens").asOpt[Int],
      seed = json.select("seed").asOpt[Int],
      temperature = json.select("temperature").asOpt[Float].getOrElse(1.0f),
      topP = json.select("topP").asOpt[Float].getOrElse(1.0f),
    )
  }
}

case class OVHAiEndpointsChatClientOptions(
                                    model: String = OVHAiEndpointsModels.mixtral_8x22b_instruct_v01,
                                    max_tokens: Option[Int] = None,
                                    seed: Option[Int] = None,
                                    temperature: Float = 1,
                                    topP: Float = 1,
                                  ) extends ChatOptions {
  override def topK: Int = 0

  override def json: JsObject = Json.obj(
    "model" -> model,
    "max_tokens" -> max_tokens,
    "seed" -> seed,
    "stream" -> false,
    "temperature" -> temperature,
    "top_p" -> topP,
  )
}

class OVHAiEndpointsChatClient(api: OVHAiEndpointsApi, options: OVHAiEndpointsChatClientOptions, id: String) extends ChatClient {

  override def model: Option[String] = options.model.some

  override def call(prompt: ChatPrompt, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val mergedOptions = options.json.deepMerge(prompt.options.map(_.json).getOrElse(Json.obj()))
    val body = mergedOptions ++ Json.obj("messages" -> prompt.json)
    api.call(options.model, "POST", "/api/openai_compat/v1/chat/completions", Some(body)).map { resp =>
      val usage = ChatResponseMetadata(
        // no headers for that ... just plain old kong http ratelimiting
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
      val duration: Long = resp.headers.getIgnoreCase("X-Kong-Proxy-Latency").map(_.toLong).getOrElse(0L)
      val slug = Json.obj(
        "provider_kind" -> "ovh-ai-endpoints",
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
