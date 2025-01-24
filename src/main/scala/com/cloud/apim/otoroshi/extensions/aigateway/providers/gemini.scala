package com.cloud.apim.otoroshi.extensions.aigateway.providers

import com.cloud.apim.otoroshi.extensions.aigateway._
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}
import play.api.libs.ws.WSResponse

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future, promise}

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
    s"https://generativelanguage.googleapis.com/v1beta${path}?key=${token}"
  }
}

class GeminiApi(val model: String, token: String, timeout: FiniteDuration = 10.seconds, env: Env) extends NoStreamingApiClient[GeminiApiResponse] {

  override def supportsCompletion: Boolean = false

  override def supportsTools: Boolean = false

  def rawCall(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[WSResponse] = {
    val url = GeminiApi.genericUrl(path, token)
    ProviderHelpers.logCall("Gemini", method, url, body)(env)
    env.Ws
      .url(url)
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

  def call(method: String, patkh: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[Either[JsValue, GeminiApiResponse]] = {
    val finalModel = Option(patkh).filter(_.nonEmpty).getOrElse(model)
    val url = s"${GeminiApi.url(finalModel, token)}"
    ProviderHelpers.logCall("Gemini", method, url, body)(env)
    env.Ws
      .url(url)
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
      .map(r => ProviderHelpers.wrapResponse("Gemini", r, env) { resp =>
        GeminiApiResponse(resp.status, resp.headers.mapValues(_.last), resp.json)
      })
  }

}

object GeminiChatClientOptions {
  def fromJson(json: JsValue): GeminiChatClientOptions = {
    GeminiChatClientOptions(
      maxOutputTokens = json.select("maxOutputTokens").asOpt[Int],
      temperature = json.select("temperature").asOpt[Float].getOrElse(1.0f),
      topP = json.select("topP").asOpt[Float].getOrElse(0.95f),
      topK = json.select("topK").asOpt[Int].getOrElse(40),
      candidateCount = json.select("candidateCount").asOpt[Int],
      responseMimeType = json.select("responseMimeType").asOpt[String],
      stopSequences = json.select("stopSequences").asOpt[Seq[String]],
      allowConfigOverride = json.select("allow_config_override").asOptBoolean.getOrElse(true),
    )
  }
}

case class GeminiChatClientOptions(
  maxOutputTokens: Option[Int] = None,
  temperature: Float = 1,
  topP: Float = 0.95f,
  topK: Int = 1,
  candidateCount: Option[Int] = None,
  responseMimeType: Option[String] = None,
  stopSequences: Option[Seq[String]] = None,
  allowConfigOverride: Boolean = true,
) extends ChatOptions {
  override def json: JsObject = Json.obj(
    "maxOutputTokens" -> maxOutputTokens,
    "temperature" -> temperature,
    "topP" -> topP,
    "topK" -> topK,
    "candidateCount" -> candidateCount,
    "responseMimeType" -> responseMimeType,
    "stopSequences" -> stopSequences,
    "responseMimeType" -> responseMimeType,
    "allow_config_override" -> allowConfigOverride,
  )
  def jsonForCall: JsObject = optionsCleanup(json - "wasm_tools" - "allow_config_override")
}

class GeminiChatClient(api: GeminiApi, options: GeminiChatClientOptions, id: String) extends ChatClient {

  // TODO: supports tools: true
  // TODO: supports streaming: true

  override def supportsCompletion: Boolean = api.supportsCompletion

  override def supportsStreaming: Boolean = api.supportsStreaming

  override def supportsTools: Boolean = api.supportsTools

  override def model: Option[String] = api.model.some

  override def listModels()(implicit ec: ExecutionContext): Future[Either[JsValue, List[String]]] = {
    api.rawCall("GET", "/models", None).map { resp =>
      if (resp.status == 200) {
        Right(resp.json.select("models").as[List[JsObject]].map(obj => obj.select("name").asString.replaceFirst("models/", "")))
      } else {
        Left(Json.obj("error" -> s"bad response code: ${resp.status}"))
      }
    }
  }

  override def call(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val obody = originalBody.asObject - "messages" - "provider" - "model"
    val bodyOpts = obody.select("generationConfig").asOpt[JsObject].getOrElse(Json.obj())
    val mergedOptions = if (options.allowConfigOverride) options.jsonForCall.deepMerge(bodyOpts) else options.jsonForCall
    api.call("POST", originalBody.select("model").asOptString.getOrElse(""), Some(Json.obj(
      "contents" -> JsArray(prompt.messages.map(m => Json.obj("role" -> m.role, "parts" -> Json.arr(Json.obj("text" -> m.content))))),
      "generationConfig" -> mergedOptions
    ))).map {
      case Left(err) => err.left
      case Right(resp) =>
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
        val content = obj.select("content").select("parts").asOpt[Seq[JsObject]].map(seq => seq.map(jso => jso.select("text").asString)).getOrElse(Seq.empty).mkString(" ")
        ChatGeneration(ChatMessage.output(role, content, None))
      }
      Right(ChatResponse(messages, usage))
    }
  }
}
