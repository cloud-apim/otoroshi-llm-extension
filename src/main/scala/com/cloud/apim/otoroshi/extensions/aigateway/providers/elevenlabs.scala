package com.cloud.apim.otoroshi.extensions.aigateway.providers

import akka.http.scaladsl.model.{ContentType, HttpEntity, Multipart, Uri}
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.{AudioGenVoice, AudioModelClient, AudioModelClientSpeechToTextInputOptions, AudioModelClientTextToSpeechInputOptions, AudioModelClientTranslationInputOptions, AudioTranscriptionResponse, AudioTranscriptionResponseMetadata}
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.libs.ws.WSResponse

import scala.concurrent._
import scala.concurrent.duration._

object ElevenLabsModels {
  val DEFAULT = "eleven_monolingual_v1"
  val MULTILINGUAL_V2 = "eleven_multilingual_v2"
}

object ElevenLabsApi {
  // https://api.elevenlabs.io/v1/text-to-speech/:voice_id
  val baseUrl = "https://api.elevenlabs.io"
}

class ElevenLabsApi(baseUrl: String = ElevenLabsApi.baseUrl, token: String, timeout: FiniteDuration = 3.minutes, env: Env) {
  def rawCall(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[WSResponse] = {
    val url = s"${baseUrl}${path}"
    ProviderHelpers.logCall("ElevenLabs", method, url, body)(env)
    env.Ws
      .url(url)
      .withHttpHeaders(
        "xi-api-key" -> token,
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
  def rawCallForm(method: String, path: String, body: Multipart)(implicit ec: ExecutionContext): Future[WSResponse] = {
    val url = s"${baseUrl}${path}"
    ProviderHelpers.logCall("ElevenLabs", method, url, None)(env)
    val entity = body.toEntity()
    env.Ws
      .url(url)
      .withHttpHeaders(
        "xi-api-key" -> token,
        "Accept" -> "application/json",
        "Content-Type" -> entity.contentType.toString()
      )
      .withBody(entity.dataBytes)
      .withMethod(method)
      .withRequestTimeout(timeout)
      .execute()
  }
}

case class ElevenLabsAudioModelClientTtsOptions(raw: JsObject) {
  lazy val enabled: Boolean = raw.select("enabled").asOptBoolean.getOrElse(true)
  lazy val model: String = raw.select("model_id").asOpt[String].getOrElse(ElevenLabsModels.DEFAULT)
  lazy val voice: String = raw.select("voice_id").asOpt[String].getOrElse("21m00Tcm4TlvDq8ikWAM")
  lazy val format: String = raw.select("output_format").asOpt[String].getOrElse("mp3_44100_128")
}

object ElevenLabsAudioModelClientTtsOptions {
  def fromJson(raw: JsObject): ElevenLabsAudioModelClientTtsOptions = ElevenLabsAudioModelClientTtsOptions(raw)
}

case class ElevenLabsAudioModelClientSttOptions(raw: JsObject) {
  lazy val enabled: Boolean = raw.select("enabled").asOptBoolean.getOrElse(true)
  lazy val model: Option[String] = raw.select("model_id").asOptString
  lazy val language: Option[String] = raw.select("language").asOptString
}

object ElevenLabsAudioModelClientSttOptions {
  def fromJson(raw: JsObject): ElevenLabsAudioModelClientSttOptions = ElevenLabsAudioModelClientSttOptions(raw)
}

class ElevenLabsAudioModelClient(val api: ElevenLabsApi, val ttsOptions: ElevenLabsAudioModelClientTtsOptions, val sttOptions: ElevenLabsAudioModelClientSttOptions, id: String) extends AudioModelClient {

  override def supportsTts: Boolean = ttsOptions.enabled
  override def supportsStt: Boolean = sttOptions.enabled
  override def supportsTranslation: Boolean = false

  override def listVoices(raw: Boolean)(implicit ec: ExecutionContext): Future[Either[JsValue, List[AudioGenVoice]]] = {
    api.rawCall("GET", "/v2/voices?include_total_count=true", None).map { resp =>
      if (resp.status == 200) {
        Right(resp.json.select("voices").as[List[JsObject]].map(obj => AudioGenVoice(obj.select("voice_id").asString, obj.select("name").asString))
        )
      } else {
        Left(Json.obj("error" -> s"bad response code: ${resp.status}"))
      }
    }
  }

  override def textToSpeech(opts: AudioModelClientTextToSpeechInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, (Source[ByteString, _], String)]] = {
    val finalModel: String = opts.model.getOrElse(ttsOptions.model)
    val finalVoice: String = opts.voice.getOrElse(ttsOptions.voice)
    val finalFormat: String = opts.responseFormat.getOrElse(ttsOptions.format)

    val body = Json.obj(
      "text" -> opts.input,
      "model_id" -> finalModel.json,
    )

    api.rawCall("POST", s"/v1/text-to-speech/${finalVoice}?output_format=${finalFormat}", body.some).map { response =>
      if (response.status == 200) {
        val contentType = response.contentType
        (response.bodyAsSource, contentType).right
      } else {
        Left(Json.obj("error" -> "Bad response", "body" -> s"Failed with status ${response.status}: ${response.body}"))
      }
    }
  }

  override def speechToText(opts: AudioModelClientSpeechToTextInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, AudioTranscriptionResponse]] = {
    val model = opts.model.orElse(sttOptions.model).getOrElse("scribe_v1")
    val language = opts.language.orElse(sttOptions.language)
    val parts = List(
      Multipart.FormData.BodyPart(
        "file",
        HttpEntity(ContentType.parse(opts.fileContentType).toOption.get, opts.fileLength, opts.file),
        Map("filename" -> opts.fileName.getOrElse("audio.mp3"))
      ),
      Multipart.FormData.BodyPart(
        "model_id",
        HttpEntity(model.byteString),
      )
    ).applyOnWithOpt(language) {
      case (list, language) => list :+ Multipart.FormData.BodyPart(
        "language_code",
        HttpEntity(language.byteString),
      )
    }
    val form = Multipart.FormData(parts: _*)
    api.rawCallForm("POST", "/v1/speech-to-text", form).map { response =>
      if (response.status == 200) {
        AudioTranscriptionResponse(response.json.select("text").asString, AudioTranscriptionResponseMetadata.empty).right
      } else {
        Left(Json.obj("error" -> "Bad response", "body" -> s"Failed with status ${response.status}: ${response.body}"))
      }
    }
  }
}