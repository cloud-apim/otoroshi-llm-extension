package com.cloud.apim.otoroshi.extensions.aigateway.providers

import com.cloud.apim.otoroshi.extensions.aigateway._
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.libs.ws.WSResponse

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

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

class CohereAiApi(baseUrl: String = CohereAiApi.baseUrl, token: String, timeout: FiniteDuration = 10.seconds, env: Env) extends NoStreamingApiClient[CohereAiApiResponse] {

  override def supportsTools: Boolean = false

  override def supportsCompletion: Boolean = false

  def rawCall(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[WSResponse] = {
    val url = s"${baseUrl}${path}"
    ProviderHelpers.logCall("Cohere", method, url, body)(env)
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
  }

  def call(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[Either[JsValue, CohereAiApiResponse]] = {
    rawCall(method, path, body)
      .map(r => ProviderHelpers.wrapResponse("Cohere", r, env) { resp =>
        CohereAiApiResponse(resp.status, resp.headers.mapValues(_.last), resp.json)
      })
  }
}

object CohereAiChatClientOptions {
  def fromJson(json: JsValue): CohereAiChatClientOptions = {
    CohereAiChatClientOptions(
      model = json.select("model").asOpt[String].getOrElse(CohereAiModels.DEFAULT_COHERE_MODEL),
      max_tokens = json.select("max_tokens").asOpt[Int],
      max_input_tokens = json.select("max_input_tokens").asOpt[Int],
      k = json.select("k").asOpt[Int],
      p = json.select("p").asOpt[Double],
      temperature = json.select("temperature").asOpt[Float].getOrElse(0.3f),
      seed = json.select("seed").asOpt[Int],
      allowConfigOverride = json.select("allow_config_override").asOptBoolean.getOrElse(true),
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
  seed: Option[Int] = None,
  allowConfigOverride: Boolean = true,
) extends ChatOptions {

  override def json: JsObject = Json.obj(
    "model" -> model,
    "max_tokens" -> max_tokens,
    "max_input_tokens" -> max_input_tokens,
    "stream" -> stream,
    "temperature" -> temperature,
    "k" -> topK,
    "p" -> topP,
    "frequency_penalty" -> frequency_penalty,
    "seed" -> seed,
    "allow_config_override" -> allowConfigOverride,
  )

  def jsonForCall: JsObject = optionsCleanup(json - "wasm_tools" - "allow_config_override")

  override def topP: Float = p.map(_.toFloat).getOrElse(0.75f)
  override def topK: Int = k.getOrElse(0)
}

class CohereAiChatClient(api: CohereAiApi, options: CohereAiChatClientOptions, id: String) extends ChatClient {

  // TODO: supports tools: true
  // TODO: supports streaming: true

  override def supportsTools: Boolean = api.supportsTools

  override def supportsStreaming: Boolean = api.supportsStreaming

  override def supportsCompletion: Boolean = api.supportsCompletion

  override def model: Option[String] = options.model.some

  override def listModels()(implicit ec: ExecutionContext): Future[Either[JsValue, List[String]]] = {
    api.rawCall("GET", "/v1/models", None).map { resp =>
      if (resp.status == 200) {
        Right(resp.json.select("models").as[List[JsObject]].map(obj => obj.select("name").asString))
      } else {
        Left(Json.obj("error" -> s"bad response code: ${resp.status}"))
      }
    }
  }

  override def call(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val obody = originalBody.asObject - "messages" - "provider"
    val mergedOptions = if (options.allowConfigOverride) options.jsonForCall.deepMerge(obody) else options.jsonForCall
    api.call("POST", "/v2/chat", Some(mergedOptions ++ Json.obj("messages" -> prompt.json))).map {
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
          promptTokens = resp.body.select("usage").select("tokens").select("input_tokens").asOpt[Long].getOrElse(-1L),
          generationTokens = resp.body.select("usage").select("tokens").select("output_tokens").asOpt[Long].getOrElse(-1L),
        ),
        None
      )
      val duration: Long = resp.headers.getIgnoreCase("CohereAi-processing-ms").map(_.toLong).getOrElse(0L)
      val slug = Json.obj(
        "provider_kind" -> "cohere",
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
      val messages = resp.body.select("message").asOpt[JsObject].map { obj =>
        val role = obj.select("role").asOpt[String].getOrElse("assistant")
        obj.select("content").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map { c =>
          val text = c.select("text").asOpt[String].getOrElse("")
          ChatGeneration(ChatMessage(role, text, None))
        }
      }.toSeq.flatten
      Right(ChatResponse(messages, usage))
    }
  }
}
