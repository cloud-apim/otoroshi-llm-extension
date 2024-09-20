package com.cloud.apim.otoroshi.extensions.aigateway.providers


import com.cloud.apim.otoroshi.extensions.aigateway._
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsObject, JsValue, Json}

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

// https://learn.microsoft.com/en-us/azure/ai-services/openai/reference
case class AzureOpenAiApiResponse(status: Int, headers: Map[String, String], body: JsValue) {
  def json: JsValue = Json.obj(
    "status" -> status,
    "headers" -> headers,
    "body" -> body,
  )
}
object AzureOpenAiModels {
  val GPT_4_0125_PREVIEW = "gpt-4-0125-preview"
  val GPT_4_TURBO_PREVIEW = "gpt-4-turbo-preview"
  val GPT_4_VISION_PREVIEW = "gpt-4-vision-preview"
  val GPT_4 = "gpt-4"
  val GPT_4_32K = "gpt-4-32k"
  val GPT_3_5_TURBO = "gpt-3.5-turbo"
  val GPT_3_5_TURBO_0125 = "gpt-3.5-turbo-0125"
  val GPT_3_5_TURBO_1106 = "gpt-3.5-turbo-1106"
}
object AzureOpenAiApi {
  // POST https://{your-resource-name}.openai.azure.com/openai/deployments/{deployment-id}/completions?api-version={api-version}
  def url(resourceName: String, deploymentId: String, path: String): String = {
    s"https://${resourceName}.openai.azure.com/openai/deployments/${deploymentId}${path}?api-version=2024-02-01"
  }
}
class AzureOpenAiApi(val resourceName: String, val deploymentId: String, apikey: String, timeout: FiniteDuration = 10.seconds, env: Env) {

  def call(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[AzureOpenAiApiResponse] = {
    env.Ws
      .url(s"${AzureOpenAiApi.url(resourceName, deploymentId, path)}")
      .withHttpHeaders(
        "Authorization" -> s"api-key ${apikey}",
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
        AzureOpenAiApiResponse(resp.status, resp.headers.mapValues(_.last), resp.json)
      }
  }
}

object AzureOpenAiChatClientOptions {
  def fromJson(json: JsValue): AzureOpenAiChatClientOptions = {
    AzureOpenAiChatClientOptions(
      max_tokens = json.select("max_tokens").asOpt[Int],
      n = json.select("n").asOpt[Int],
      temperature = json.select("temperature").asOpt[Float].getOrElse(1.0f),
      topP = json.select("topP").asOpt[Float].getOrElse(1.0f),
    )
  }
}

case class AzureOpenAiChatClientOptions(
                                    frequency_penalty: Option[Double] = None,
                                    logit_bias: Option[Map[String, Int]] = None,
                                    logprobs: Option[Boolean] = None,
                                    stream: Option[Boolean] = Some(false),
                                    top_logprobs: Option[Int] = None,
                                    max_tokens: Option[Int] = None,
                                    n: Option[Int] = Some(1),
                                    seed: Option[Int] = None,
                                    presence_penalty: Option[Double] = None,
                                    response_format: Option[String] = None,
                                    stop: Option[String] = None,
                                    temperature: Float = 1,
                                    topP: Float = 1,
                                    tools: Option[Seq[JsValue]] = None,
                                    tool_choice: Option[Seq[JsValue]] =  None,
                                    user: Option[String] = None,
                                  ) extends ChatOptions {
  override def topK: Int = 0

  override def json: JsObject = Json.obj(
    "frequency_penalty" -> frequency_penalty,
    "logit_bias" -> logit_bias,
    "logprobs" -> logprobs,
    "top_logprobs" -> top_logprobs,
    "max_tokens" -> max_tokens,
    "n" -> n.getOrElse(1).json,
    "presence_penalty" -> presence_penalty,
    "response_format" -> response_format,
    "seed" -> seed,
    "stop" -> stop,
    "stream" -> stream,
    "temperature" -> temperature,
    "top_p" -> topP,
    "tools" -> tools,
    "tool_choice" -> tool_choice,
    "user" -> user,
  )
}

class AzureOpenAiChatClient(api: AzureOpenAiApi, options: AzureOpenAiChatClientOptions, id: String) extends ChatClient {

  override def model: Option[String] = s"${api.resourceName}-${api.deploymentId}".some

  override def call(prompt: ChatPrompt, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val mergedOptions = options.json.deepMerge(prompt.options.map(_.json).getOrElse(Json.obj()))
    api.call("POST", "/completions", Some(mergedOptions ++ Json.obj("messages" -> prompt.json))).map { resp =>
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
      val duration: Long = resp.headers.getIgnoreCase("AzureOpenAi-processing-ms").map(_.toLong).getOrElse(0L)
      val slug = Json.obj(
        "provider_kind" -> "AzureOpenAi",
        "provider" -> id,
        "duration" -> duration,
        "deployment_id" -> api.deploymentId,
        "resource_name" -> api.resourceName,
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
      val messages = resp.body.select("choices").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map { obj =>
        val role = obj.select("message").select("role").asOpt[String].getOrElse("user")
        val content = obj.select("message").select("content").asOpt[String].getOrElse("")
        ChatGeneration(ChatMessage(role, content))
      }
      Right(ChatResponse(messages, usage))
    }
  }
}
