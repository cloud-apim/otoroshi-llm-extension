package com.cloud.apim.otoroshi.extensions.aigateway.providers

import com.cloud.apim.otoroshi.extensions.aigateway._
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.libs.ws.WSResponse

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

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
  def url(model: String, token: String): String = {
    s"https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${token}"
  }
  def genericUrl(path: String, token: String): String = {
    s"https://generativelanguage.googleapis.com/v1beta/models${path}?key=${token}"
  }
}

class GeminiApi(val model: String, token: String, timeout: FiniteDuration = 10.seconds, env: Env) {

  def rawCall(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[WSResponse] = {
    env.Ws
      .url(s"${GeminiApi.genericUrl(path, token)}")
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
  }

  def call(method: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[GeminiApiResponse] = {
    env.Ws
      .url(s"${GeminiApi.url(model, token)}")
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
      maxOutputTokens = json.select("maxOutputTokens").asOpt[Int],
      temperature = json.select("temperature").asOpt[Float].getOrElse(1.0f),
      topP = json.select("topP").asOpt[Float].getOrElse(0.95f),
      topK = json.select("top_k").asOpt[Int].getOrElse(40),
      stopSequences = json.select("stopSequences").asOpt[Array[String]],
      allowConfigOverride = json.select("allow_config_override").asOptBoolean.getOrElse(true),
    )
  }
}

case class GeminiChatClientOptions(
  maxOutputTokens: Option[Int] = None,
  temperature: Float = 1,
  topP: Float = 0.95f,
  topK: Int = 1,
  stopSequences: Option[Array[String]] = None,
  allowConfigOverride: Boolean = true,
) extends ChatOptions {
  override def json: JsObject = Json.obj(
    "maxOutputTokens" -> maxOutputTokens,
    "temperature" -> temperature,
    "topP" -> topP,
    "topK" -> topK,
    "stopSequences" -> stopSequences,
    "allow_config_override" -> allowConfigOverride,
  )
  def jsonForCall: JsObject = json - "wasm_tools" - "allow_config_override"
}

class GeminiChatClient(api: GeminiApi, options: GeminiChatClientOptions, id: String) extends ChatClient {

  // supports tools: true
  // supports streaming: true

  override def model: Option[String] = api.model.some

  override def listModels()(implicit ec: ExecutionContext): Future[Either[JsValue, List[String]]] = {
    api.rawCall("GET", "/models", None).map { resp =>
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
    api.call("POST", Some(mergedOptions ++ Json.obj(
      "contents" -> Json.obj("parts" -> prompt.json),
      "generationConfig" -> options.json
    ))).map { resp =>
      val usage = ChatResponseMetadata(
        ChatResponseMetadataRateLimit.empty,
        ChatResponseMetadataUsage(
          promptTokens = resp.body.select("usageMetadata").select("promptTokenCount").asOpt[Long].getOrElse(-1L),
          generationTokens = resp.body.select("usageMetadata").select("totalTokenCount").asOpt[Long].getOrElse(-1L),
        ),
        None
      )
      val duration: Long = resp.headers.getIgnoreCase("gemini-processing-ms").map(_.toLong).getOrElse(0L)
      val slug = Json.obj(
        "provider_kind" -> "gemini",
        "provider" -> id,
        "duration" -> duration,
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
      val messages = resp.body.select("candidates").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map { obj =>
        val role = obj.select("content").select("role").asOpt[String].getOrElse("user")
        val content = obj.select("content").select("parts").asOpt[Seq[String]].getOrElse(Seq.empty).mkString(" ")
        ChatGeneration(ChatMessage(role, content, None))
      }
      Right(ChatResponse(messages, usage))
    }
  }
}
