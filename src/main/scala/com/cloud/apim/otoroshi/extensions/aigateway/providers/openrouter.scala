package com.cloud.apim.otoroshi.extensions.aigateway.providers

import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway._
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.libs.ws.WSResponse

import scala.concurrent._
import scala.concurrent.duration._

// OpenRouter - https://openrouter.ai/docs/api/api-reference/transcriptions/create-audio-transcriptions
// OpenRouter audio support is speech-to-text only (STT). Unlike OpenAI/Mistral, the transcription
// endpoint takes a JSON body with base64-encoded audio (not multipart/form-data). It is exposed as
// an AudioModel (see OpenRouterAudioModelClient). Chat/completions for OpenRouter go through the
// generic OpenAI-like provider (see openailike.scala).
object OpenRouterApi {
  val baseUrl = "https://openrouter.ai/api/v1"
  val defaultSttModel = "openai/whisper-large-v3"
}

class OpenRouterApi(baseUrl: String = OpenRouterApi.baseUrl, token: String, timeout: FiniteDuration = 3.minutes, env: Env) {

  def rawCall(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[WSResponse] = {
    val url = s"${baseUrl}${path}"
    ProviderHelpers.logCall("OpenRouter", method, url, body)(env)
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
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/////////                                     Audio transcription (STT)                                  ///////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

case class OpenRouterAudioModelClientSttOptions(raw: JsObject) {
  lazy val enabled: Boolean = raw.select("enabled").asOptBoolean.getOrElse(true)
  lazy val model: Option[String] = raw.select("model").asOptString
  lazy val language: Option[String] = raw.select("language").asOptString
  lazy val temperature: Option[Double] = raw.select("temperature").asOpt[Double]
}

object OpenRouterAudioModelClientSttOptions {
  def fromJson(raw: JsObject): OpenRouterAudioModelClientSttOptions = OpenRouterAudioModelClientSttOptions(raw)
}

class OpenRouterAudioModelClient(val api: OpenRouterApi, val sttOptions: OpenRouterAudioModelClientSttOptions, id: String) extends AudioModelClient {

  override def supportsTts: Boolean = false
  override def supportsStt: Boolean = sttOptions.enabled
  override def supportsTranslation: Boolean = false

  // OpenRouter expects the bare audio container format in input_audio.format
  private val supportedFormats: Set[String] = Set("wav", "mp3", "flac", "m4a", "ogg", "webm", "aac")

  private def resolveFormat(opts: AudioModelClientSpeechToTextInputOptions): String = {
    val fromName = opts.fileName.flatMap(_.split("\\.").lastOption).map(_.toLowerCase.trim).filter(supportedFormats.contains)
    val fromContentType = opts.fileContentType.split("/").lastOption.map(_.toLowerCase.split(";").head.trim).map {
      case "mpeg" => "mp3"
      case "x-wav" | "wave" | "vnd.wave" => "wav"
      case "x-flac" => "flac"
      case "mp4" | "x-m4a" => "m4a"
      case other => other
    }.filter(supportedFormats.contains)
    fromName.orElse(fromContentType).getOrElse("mp3")
  }

  override def listModels(raw: Boolean)(implicit ec: ExecutionContext): Future[Either[JsValue, List[AudioGenModel]]] = {
    Right(List(
      AudioGenModel(OpenRouterApi.defaultSttModel, OpenRouterApi.defaultSttModel),
    )).vfuture
  }

  override def listVoices(raw: Boolean)(implicit ec: ExecutionContext): Future[Either[JsValue, List[AudioGenVoice]]] = {
    List.empty.rightf
  }

  override def speechToText(opts: AudioModelClientSpeechToTextInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, AudioTranscriptionResponse]] = {
    val model = opts.model.orElse(sttOptions.model).getOrElse(OpenRouterApi.defaultSttModel)
    val language = opts.language.orElse(sttOptions.language)
    val temperature = opts.temperature.orElse(sttOptions.temperature)
    val format = resolveFormat(opts)
    opts.file.runFold(ByteString.empty)(_ ++ _)(env.otoroshiMaterializer).flatMap { bytes =>
      val body = Json.obj(
        "model" -> model,
        "input_audio" -> Json.obj(
          "data" -> bytes.encodeBase64.utf8String,
          "format" -> format,
        )
      ).applyOnWithOpt(language) {
        case (obj, language) => obj ++ Json.obj("language" -> language)
      }.applyOnWithOpt(temperature) {
        case (obj, temperature) => obj ++ Json.obj("temperature" -> temperature)
      }
      api.rawCall("POST", "/audio/transcriptions", body.some).map { response =>
        if (response.status == 200) {
          val resp = response.json
          AudioTranscriptionResponse(resp.select("text").asString, AudioTranscriptionResponseMetadata.fromOpenAiResponse(resp.asObject, response.headers.mapValues(_.last))).right
        } else {
          Left(Json.obj("error" -> "Bad response", "body" -> s"Failed with status ${response.status}: ${response.body}"))
        }
      }
    }
  }
}
