package com.cloud.apim.otoroshi.extensions.aigateway.providers

import akka.http.scaladsl.model.{ContentType, ContentTypes, HttpEntity, Multipart}
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway._
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.libs.ws.WSResponse

import scala.concurrent._
import scala.concurrent.duration._

// AlphaEdge AI - https://api-docs.alphaedge-ai.com
// AlphaEdge only exposes "file -> text" models:
//   - audio transcription (STT)  -> exposed as an AudioModel  (see AlphaEdgeAudioModelClient)
//   - OCR (image/pdf -> text)     -> exposed as a chat provider that requires a file content part
//                                    (see AlphaEdgeChatClient)
object AlphaEdgeApi {
  val baseUrl = "https://api-endpoints.alphaedge-ai.com"
  val defaultAudioModel = "alpha-audio-v1"
  val defaultOcrModel = "alpha-digit-max"
}

class AlphaEdgeApi(baseUrl: String = AlphaEdgeApi.baseUrl, token: String, timeout: FiniteDuration = 3.minutes, env: Env) {

  def rawCall(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[WSResponse] = {
    val url = s"${baseUrl}${path}"
    ProviderHelpers.logCall("AlphaEdge", method, url, body)(env)
    env.Ws
      .url(url)
      .withHttpHeaders(
        "X-API-Key" -> token,
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
    ProviderHelpers.logCall("AlphaEdge", method, url, None)(env)
    val entity = body.toEntity()
    env.Ws
      .url(url)
      .withHttpHeaders(
        "X-API-Key" -> token,
        "Accept" -> "application/json",
        "Content-Type" -> entity.contentType.toString()
      )
      .withBody(entity.dataBytes)
      .withMethod(method)
      .withRequestTimeout(timeout)
      .execute()
  }
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/////////                                     Audio transcription (STT)                                  ///////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

case class AlphaEdgeAudioModelClientSttOptions(raw: JsObject) {
  lazy val enabled: Boolean = raw.select("enabled").asOptBoolean.getOrElse(true)
  lazy val model: Option[String] = raw.select("model").asOptString
  lazy val enableDiarization: Option[Boolean] = raw.select("enable_diarization").asOptBoolean
  lazy val enablePostcorrect: Option[Boolean] = raw.select("enable_postcorrect").asOptBoolean
}

object AlphaEdgeAudioModelClientSttOptions {
  def fromJson(raw: JsObject): AlphaEdgeAudioModelClientSttOptions = AlphaEdgeAudioModelClientSttOptions(raw)
}

class AlphaEdgeAudioModelClient(val api: AlphaEdgeApi, val sttOptions: AlphaEdgeAudioModelClientSttOptions, id: String) extends AudioModelClient {

  override def supportsTts: Boolean = false
  override def supportsStt: Boolean = sttOptions.enabled
  override def supportsTranslation: Boolean = false

  override def listModels(raw: Boolean)(implicit ec: ExecutionContext): Future[Either[JsValue, List[AudioGenModel]]] = {
    api.rawCall("GET", "/models", None).map { resp =>
      if (resp.status == 200) {
        Right(resp.json.asOpt[Seq[JsObject]].getOrElse(Seq.empty)
          .filter(o => o.select("type").asOpt[String].contains("audio"))
          .map { o =>
            val slug = o.select("model_slug").asString
            AudioGenModel(slug, slug)
          }.toList)
      } else {
        Left(Json.obj("error" -> s"bad response code: ${resp.status}"))
      }
    }
  }

  override def listVoices(raw: Boolean)(implicit ec: ExecutionContext): Future[Either[JsValue, List[AudioGenVoice]]] = {
    List.empty.rightf
  }

  override def speechToText(opts: AudioModelClientSpeechToTextInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, AudioTranscriptionResponse]] = {
    val model = opts.model.orElse(sttOptions.model).getOrElse(AlphaEdgeApi.defaultAudioModel)
    val diarization = rawBody.select("enable_diarization").asOptBoolean.orElse(sttOptions.enableDiarization)
    val postcorrect = rawBody.select("enable_postcorrect").asOptBoolean.orElse(sttOptions.enablePostcorrect)
    val parts = List(
      Multipart.FormData.BodyPart(
        "audio",
        HttpEntity(ContentType.parse(opts.fileContentType).toOption.getOrElse(ContentTypes.`application/octet-stream`), opts.fileLength, opts.file),
        Map("filename" -> opts.fileName.getOrElse("audio.wav"))
      )
    ).applyOnWithOpt(diarization) {
      case (list, diarization) => list :+ Multipart.FormData.BodyPart(
        "enable_diarization",
        HttpEntity(diarization.toString.byteString),
      )
    }.applyOnWithOpt(postcorrect) {
      case (list, postcorrect) => list :+ Multipart.FormData.BodyPart(
        "enable_postcorrect",
        HttpEntity(postcorrect.toString.byteString),
      )
    }
    val form = Multipart.FormData(parts: _*)
    api.rawCallForm("POST", s"/models/${model}/transcript", form).map { response =>
      if (response.status == 200) {
        AudioTranscriptionResponse(response.json.select("text").asString, AudioTranscriptionResponseMetadata.empty).right
      } else {
        Left(Json.obj("error" -> "Bad response", "body" -> s"Failed with status ${response.status}: ${response.body}"))
      }
    }
  }
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/////////                                              OCR (chat)                                        ///////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

case class AlphaEdgeChatClientOptions(raw: JsObject) {
  lazy val model: String = raw.select("model").asOpt[String].getOrElse(AlphaEdgeApi.defaultOcrModel)
  lazy val pdfPassword: Option[String] = raw.select("pdf_password").asOptString
  lazy val allowConfigOverride: Boolean = raw.select("allow_config_override").asOptBoolean.getOrElse(true)
}

object AlphaEdgeChatClientOptions {
  def fromJson(json: JsValue): AlphaEdgeChatClientOptions = AlphaEdgeChatClientOptions(json.asObject)
}

class AlphaEdgeChatClient(api: AlphaEdgeApi, options: AlphaEdgeChatClientOptions, id: String) extends ChatClient {

  override def supportsStreaming: Boolean = false
  override def supportsTools: Boolean = false
  override def supportsCompletion: Boolean = false

  override def computeModel(payload: JsValue): Option[String] = payload.select("model").asOpt[String].orElse(options.model.some)

  override def listModels(raw: Boolean, attrs: TypedMap)(implicit ec: ExecutionContext): Future[Either[JsValue, List[String]]] = {
    api.rawCall("GET", "/models", None).map { resp =>
      if (resp.status == 200) {
        Right(resp.json.asOpt[Seq[JsObject]].getOrElse(Seq.empty)
          .filter(o => o.select("type").asOpt[String].contains("ocr"))
          .map(o => o.select("model_slug").asString).toList)
      } else {
        Left(Json.obj("error" -> s"bad response code: ${resp.status}"))
      }
    }
  }

  private def fileExtension(mediaType: String): String =
    mediaType.split("/").lastOption.map(_.split(";").head.trim).filter(_.nonEmpty).getOrElse("bin")

  // OCR needs a single image/pdf file. We extract it from the first image or pdf content part of the prompt.
  private def resolveOcrFile(prompt: ChatPrompt)(implicit ec: ExecutionContext, env: Env): Future[Option[(ByteString, String, String)]] = {
    val parts = prompt.messages.flatMap(_.contentParts)
    parts.collectFirst {
      case img: ChatMessageContent.ImageContent => (img.data, img.url, img.mediaType, s"image.${fileExtension(img.mediaType)}")
      case pdf: ChatMessageContent.PdfFileContent => (pdf.data, pdf.url, "application/pdf", "document.pdf")
    } match {
      case None => Future.successful(None)
      case Some((Some(bytes), _, ct, name)) => Future.successful(Some((bytes, ct, name)))
      case Some((None, Some(url), ct, name)) => env.Ws.url(url).withRequestTimeout(2.minutes).get().map(r => Some((r.bodyAsBytes, ct, name)))
      case Some((None, None, _, _)) => Future.successful(None)
    }
  }

  override def call(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val obody = originalBody.asObject
    val finalModel = computeModel(originalBody).getOrElse(options.model)
    val pdfPassword = (if (options.allowConfigOverride) obody.select("pdf_password").asOptString else None).orElse(options.pdfPassword)
    val startTime = System.currentTimeMillis()
    resolveOcrFile(prompt).flatMap {
      case None => Left(Json.obj("error" -> "bad_request", "error_details" -> "a file content part (image or pdf) is required for OCR")).vfuture
      case Some((bytes, contentType, filename)) => {
        val ct = ContentType.parse(contentType).toOption.getOrElse(ContentTypes.`application/octet-stream`)
        val parts = List(
          Multipart.FormData.BodyPart(
            "image",
            HttpEntity(ct, bytes),
            Map("filename" -> filename)
          )
        ).applyOnWithOpt(pdfPassword) {
          case (list, pwd) => list :+ Multipart.FormData.BodyPart("pdf_password", HttpEntity(pwd.byteString))
        }
        val form = Multipart.FormData(parts: _*)
        api.rawCallForm("POST", s"/models/${finalModel}/ocr", form).map { resp =>
          if (resp.status == 200) {
            val body = resp.json
            val text = body.select("text").asString
            val usage = ChatResponseMetadata.empty
            val duration: Long = System.currentTimeMillis() - startTime
            val slug = Json.obj(
              "provider_kind" -> "alphaedge",
              "provider" -> id,
              "duration" -> duration,
              "model" -> finalModel.json,
              "rate_limit" -> usage.rateLimit.json,
              "usage" -> usage.usage.json
            )
            attrs.update(ChatClient.ApiUsageKey -> usage)
            attrs.update(otoroshi.plugins.Keys.ExtraAnalyticsDataKey) {
              case Some(obj @ JsObject(_)) => {
                val arr = obj.select("ai").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
                obj ++ Json.obj("ai" -> (arr ++ Seq(slug)))
              }
              case Some(other) => other
              case None => Json.obj("ai" -> Seq(slug))
            }
            val gen = ChatGeneration(ChatMessage.output("assistant", text, None, Json.obj("role" -> "assistant", "content" -> text)))
            Right(ChatResponse(Seq(gen), usage, body))
          } else {
            Left(Json.obj("error" -> "Bad response", "body" -> s"Failed with status ${resp.status}: ${resp.body}"))
          }
        }
      }
    }
  }
}
