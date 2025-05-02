package com.cloud.apim.otoroshi.extensions.aigateway.providers

import otoroshi.env.Env
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
}

case class ElevenLabsAudioModelClientOptions(raw: JsObject) {
  lazy val model: String = raw.select("model_id").asOpt[String].getOrElse(ElevenLabsModels.DEFAULT)
}

object ElevenLabsAudioModelClientOptions {
  def fromJson(raw: JsObject): ElevenLabsAudioModelClientOptions = ElevenLabsAudioModelClientOptions(raw)
}

class ElevenLabsAudioModelClient(val api: ElevenLabsApi, val options: ElevenLabsAudioModelClientOptions, id: String) extends AudioModelClient {

  override def createSpeech(input: String, voiceId: String, modelOpt: Option[String])(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, AudioResponse]] = {
    val finalModel: String = modelOpt.getOrElse(options.model)
    api.rawCall("POST", s"/v1/text-to-speech/${voiceId}", (options.raw ++ Json.obj("text" -> input, "model_id" -> finalModel)).some).map { resp =>
      if (resp.status == 200) {
        Right(AudioResponse(
          model = finalModel,
          Audios = resp.json.select("data").as[Seq[JsObject]].map(o => Audio(o.select("Audio").as[Array[Float]])),
          metadata = None
        ))
      } else {
        Left(Json.obj("status" -> resp.status, "body" -> resp.json))
      }
    }
  }
}
