package com.cloud.apim.otoroshi.extensions.aigateway.providers

import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.AudioModelClient
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.libs.ws.WSResponse

import java.io.{File, FileOutputStream}
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

class ElevenLabsApi(baseUrl: String = ElevenLabsApi.baseUrl, token: String, timeout: FiniteDuration = 10.seconds, env: Env) {

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

  def rawCallTts(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[File] = {
    val url = s"${baseUrl}${path}"
    ProviderHelpers.logCall("ElevenLabs", method, url, body)(env)

    env.Ws.url(url)
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
      .map { response =>
        if (response.status == 200) {
          val audioBytes: ByteString = response.bodyAsBytes
          val file = new File("speech.mp3")
          val output = new FileOutputStream(file)
          try {
            output.write(audioBytes.toArray)
          } finally {
            output.close()
          }
          file
        } else {
          throw new RuntimeException(s"Failed with status ${response.status}: ${response.body}")
        }
      }
  }
}

case class ElevenLabsAudioModelClientOptions(raw: JsObject) {
  lazy val model: String = raw.select("model_id").asOpt[String].getOrElse(ElevenLabsModels.DEFAULT)
  lazy val voiceId: String = raw.select("voice_id").asOpt[String].getOrElse("21m00Tcm4TlvDq8ikWAM")
  lazy val format: String = raw.select("output_format").asOpt[String].getOrElse("mp3_44100_128")
}

object ElevenLabsAudioModelClientOptions {
  def fromJson(raw: JsObject): ElevenLabsAudioModelClientOptions = ElevenLabsAudioModelClientOptions(raw)
}

class ElevenLabsAudioModelClient(val api: ElevenLabsApi, val options: ElevenLabsAudioModelClientOptions, mode: String, id: String) extends AudioModelClient {

  override def textToSpeech(textInput: String, modelOpt: Option[String], voiceOpt: Option[String])(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, File]] = {
    val finalModel: String = modelOpt.getOrElse(options.model)
    val finalVoice: String = voiceOpt.getOrElse(options.voiceId)
    val finalFormat: String = voiceOpt.getOrElse(options.format)

    api.rawCallTts("POST", s"/v1/text-to-speech/${finalVoice}?output_format=${finalFormat}", (
      options.raw ++
        Json.obj(
          "text" -> textInput,
          "voice" -> finalVoice,
          "model_id" -> finalModel
        )
      ).some).map { resp =>
      if (resp.isFile) {
        Right(
          resp
        )
      } else {
        Left(Json.obj("error" -> "Bad file", "body" -> "Error"))
      }
    }
  }
}