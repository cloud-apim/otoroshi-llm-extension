package com.cloud.apim.otoroshi.extensions.aigateway.providers

import akka.stream.scaladsl.{Framing, Source}
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway._
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.libs.ws.WSResponse

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
    "mathstral-7B-v0.1" -> "https://mathstral-7B-v01.endpoints.kepler.ai.cloud.ovh.net",
    "mamba-codestral-7B-v0.1" -> "https://mamba-codestral-7b-v0-1.endpoints.kepler.ai.cloud.ovh.net",
    "Meta-Llama-3_1-70B-Instruct" -> "https://llama-3-1-70b-instruct.endpoints.kepler.ai.cloud.ovh.net",
    "llava-next-mistral-7b" -> "https://llava-next-mistral-7b-instruct.endpoints.kepler.ai.cloud.ovh.net",
    "Mistral-Nemo-Instruct-2407" -> "https://mistral-nemo-instruct-2407.endpoints.kepler.ai.cloud.ovh.net",
  )
}
object OVHAiEndpointsApi {
  val baseDomain = "endpoints.kepler.ai.cloud.ovh.net"
}
class OVHAiEndpointsApi(baseDomain: String = OVHAiEndpointsApi.baseDomain, token: String, timeout: FiniteDuration = 10.seconds, env: Env) {

  val supportsTools: Boolean = false
  val supportsCompletion: Boolean = true
  val supportsStreaming: Boolean = true

  def rawCall(model: String, method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[WSResponse] = {
    val url = OVHAiEndpointsModels.modelUrls.get(model).getOrElse(s"${model.toLowerCase().replaceAll("\\.", "")}.${baseDomain}")
    val furl = s"https://${url}${path}"
    ProviderHelpers.logCall("OVH", method, furl, body)(env)
    env.Ws
      .url(furl)
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

  def call(model: String, method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[Either[JsValue, OVHAiEndpointsApiResponse]] = {
    rawCall(model, method, path, body)
      .map(r => ProviderHelpers.wrapResponse("OVH", r, env) { resp =>
        OVHAiEndpointsApiResponse(resp.status, resp.headers.mapValues(_.last), resp.json)
      })
  }

  def stream(model: String, method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[Either[JsValue, (Source[OpenAiChatResponseChunk, _], WSResponse)]] = {
    val url = OVHAiEndpointsModels.modelUrls.get(model).getOrElse(s"${model.toLowerCase().replaceAll("\\.", "")}.${baseDomain}")
    val furl = s"https://${url}${path}"
    ProviderHelpers.logStream("OVH", method, furl, body)(env)
    env.Ws
      .url(s"https://${url}${path}")
      .withHttpHeaders(
        "Authorization" -> s"Bearer ${token}",
        "Accept" -> "application/json",
      ).applyOnWithOpt(body) {
        case (builder, body) => builder
          .addHttpHeaders("Content-Type" -> "application/json")
          .withBody(body.asObject ++ Json.obj(
            "stream" -> true,
            "stream_options" -> Json.obj("include_usage" -> true)
          ))
      }
      .withMethod(method)
      .withRequestTimeout(timeout)
      .stream()
      .map(r => ProviderHelpers.wrapStreamResponse("OVH", r, env) { resp =>
        (resp.bodyAsSource
          .via(Framing.delimiter(ByteString("\n\n"), Int.MaxValue, false))
          .map(_.utf8String)
          .filter(_.startsWith("data: "))
          .map(_.replaceFirst("data: ", "").trim())
          .filter(_.nonEmpty)
          .takeWhile(_ != "[DONE]")
          .map(str => Json.parse(str))
          .map(json => OpenAiChatResponseChunk(json)), resp)
      })
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
      allowConfigOverride = json.select("allow_config_override").asOptBoolean.getOrElse(true),
    )
  }
}

case class OVHAiEndpointsChatClientOptions(
  model: String = OVHAiEndpointsModels.mixtral_8x22b_instruct_v01,
  max_tokens: Option[Int] = None,
  seed: Option[Int] = None,
  temperature: Float = 1,
  topP: Float = 1,
  allowConfigOverride: Boolean = true,
) extends ChatOptions {
  override def topK: Int = 0

  override def json: JsObject = Json.obj(
    "model" -> model,
    "max_tokens" -> max_tokens,
    "seed" -> seed,
    "stream" -> false,
    "temperature" -> temperature,
    "top_p" -> topP,
    "allow_config_override" -> allowConfigOverride,
  )

  def jsonForCall: JsObject = optionsCleanup(json - "wasm_tools" - "allow_config_override")
}

class OVHAiEndpointsChatClient(api: OVHAiEndpointsApi, options: OVHAiEndpointsChatClientOptions, id: String) extends ChatClient {

  override def supportsTools: Boolean = api.supportsTools
  override def supportsStreaming: Boolean = api.supportsStreaming
  override def supportsCompletion: Boolean = api.supportsCompletion

  override def model: Option[String] = options.model.some

  override def listModels(raw: Boolean)(implicit ec: ExecutionContext): Future[Either[JsValue, List[String]]] = {
    if (raw) {
      api.rawCall(options.model, "GET", "/api/openai_compat/v1/models", None).map { resp =>
        if (resp.status == 200) {
          Right(resp.json.select("data").as[List[JsObject]].map(obj => obj.select("id").asString))
        } else {
          Left(Json.obj("error" -> s"bad response code: ${resp.status}"))
        }
      }
    } else {
      Right(OVHAiEndpointsModels.modelUrls.keys.toList).vfuture
    }
  }

  override def call(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val obody = originalBody.asObject - "messages" - "provider"
    val mergedOptions = if (options.allowConfigOverride) options.jsonForCall.deepMerge(obody) else options.jsonForCall
    val body = mergedOptions ++ Json.obj("messages" -> prompt.json)
    api.call(options.model, "POST", "/api/openai_compat/v1/chat/completions", Some(body)).map {
      case Left(err) => err.left
      case Right(resp) =>
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
            reasoningTokens = resp.body.at("usage.completion_tokens_details.reasoning_tokens").asOpt[Long].getOrElse(-1L),
          ),
          None
        )
        val duration: Long = resp.headers.getIgnoreCase("X-Kong-Proxy-Latency").map(_.toLong).getOrElse(0L)
        val slug = Json.obj(
          "provider_kind" -> "ovh-ai-endpoints",
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
          ChatGeneration(ChatMessage.output(role, content, None, obj))
        }
        Right(ChatResponse(messages, usage))
    }
  }

  override def stream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    val obody = originalBody.asObject - "messages" - "provider"
    val mergedOptions = if (options.allowConfigOverride) options.jsonForCall.deepMerge(obody) else options.jsonForCall
    api.stream(options.model, "POST", "/api/openai_compat/v1/chat/completions", Some(mergedOptions ++ Json.obj("messages" -> prompt.jsonWithFlavor(ChatMessageContentFlavor.OpenAi)))).map {
      case Left(err) => err.left
      case Right((source, resp)) =>
        source
          .filterNot { chunk =>
            if (chunk.usage.nonEmpty) {
              val usage = ChatResponseMetadata(
                ChatResponseMetadataRateLimit(
                  requestsLimit = resp.header("x-ratelimit-limit-requests").map(_.toLong).getOrElse(-1L),
                  requestsRemaining = resp.header("x-ratelimit-remaining-requests").map(_.toLong).getOrElse(-1L),
                  tokensLimit = resp.header("x-ratelimit-limit-tokens").map(_.toLong).getOrElse(-1L),
                  tokensRemaining = resp.header("x-ratelimit-remaining-tokens").map(_.toLong).getOrElse(-1L),
                ),
                ChatResponseMetadataUsage(
                  promptTokens = chunk.usage.map(_.prompt_tokens).getOrElse(-1L),
                  generationTokens = chunk.usage.map(_.completion_tokens).getOrElse(-1L),
                  reasoningTokens = chunk.usage.map(_.reasoningTokens).getOrElse(-1L),
                ),
                None
              )
              val duration: Long = resp.header("X-Kong-Proxy-Latency").map(_.toLong).getOrElse(0L)
              val slug = Json.obj(
                "provider_kind" -> "ovh-ai-endpoints",
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
                case Some(obj@JsObject(_)) => {
                  val arr = obj.select("ai").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
                  val newArr = arr ++ Seq(slug)
                  obj ++ Json.obj("ai" -> newArr)
                }
                case Some(other) => other
                case None => Json.obj("ai" -> Seq(slug))
              }
              true
            } else {
              false
            }
          }
          .map { chunk =>
            ChatResponseChunk(
              id = chunk.id,
              created = chunk.created,
              model = chunk.model,
              choices = chunk.choices.map { choice =>
                ChatResponseChunkChoice(
                  index = choice.index.map(_.toLong).getOrElse(0L),
                  delta = ChatResponseChunkChoiceDelta(
                    choice.delta.flatMap(_.content)
                  ),
                  finishReason = choice.finish_reason
                )
              }
            )
          }.right
    }
  }

  override def completion(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val body = originalBody.asObject - "messages" - "provider" - "prompt"
    val mergedOptions = if (options.allowConfigOverride) options.jsonForCall.deepMerge(body) else options.jsonForCall
    val callF = api.call(options.model, "POST", "/api/openai_compat/v1/completions", Some(mergedOptions ++ Json.obj("prompt" -> prompt.messages.head.content)))
    callF.map {
      case Left(err) => err.left
      case Right(resp) =>
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
            reasoningTokens = resp.body.at("usage.completion_tokens_details.reasoning_tokens").asOpt[Long].getOrElse(-1L),
          ),
          None
        )
        val duration: Long = resp.headers.getIgnoreCase("X-Kong-Proxy-Latency").map(_.toLong).getOrElse(0L)
        val slug = Json.obj(
          "provider_kind" -> "ovh-ai-endpoints",
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
          case Some(obj@JsObject(_)) => {
            val arr = obj.select("ai").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
            val newArr = arr ++ Seq(slug)
            obj ++ Json.obj("ai" -> newArr)
          }
          case Some(other) => other
          case None => Json.obj("ai" -> Seq(slug))
        }
        val messages = resp.body.select("choices").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map { obj =>
          val content = obj.select("text").asString
          ChatGeneration(ChatMessage.output("assistant", content, None, obj))
        }
        Right(ChatResponse(messages, usage))
    }
  }
}
