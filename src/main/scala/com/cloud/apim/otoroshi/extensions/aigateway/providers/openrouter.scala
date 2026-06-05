package com.cloud.apim.otoroshi.extensions.aigateway.providers

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

// OpenRouter - https://openrouter.ai/docs/api/api-reference
// OpenRouter audio support is speech-to-text (STT, /audio/transcriptions) and text-to-speech
// (TTS, /audio/speech). Unlike OpenAI/Mistral, the transcription endpoint takes a JSON body with
// base64-encoded audio (not multipart/form-data); the speech endpoint returns a raw audio
// bytestream. Both are exposed as an AudioModel (see OpenRouterAudioModelClient). Chat/completions
// for OpenRouter go through the generic OpenAI-like provider (see openailike.scala).
object OpenRouterApi {
  val baseUrl = "https://openrouter.ai/api/v1"
  val defaultSttModel = "openai/whisper-large-v3"
  val defaultTtsModel = "elevenlabs/eleven-turbo-v2"
  val defaultTtsVoice = "alloy"
  val defaultImageModel = "google/gemini-2.5-flash-image"
  val defaultVideoModel = "google/veo-3.1"
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

  def rawCallStream(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[WSResponse] = {
    val url = s"${baseUrl}${path}"
    ProviderHelpers.logCall("OpenRouter", method, url, body)(env)
    env.Ws
      .url(url)
      .withHttpHeaders(
        "Authorization" -> s"Bearer ${token}",
      ).applyOnWithOpt(body) {
        case (builder, body) => builder
          .addHttpHeaders("Content-Type" -> "application/json")
          .withBody(body)
      }
      .withMethod(method)
      .withRequestTimeout(timeout)
      .stream()
  }
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/////////                                          Options                                               ///////////
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

case class OpenRouterAudioModelClientTtsOptions(raw: JsObject) {
  lazy val enabled: Boolean = raw.select("enabled").asOptBoolean.getOrElse(true)
  lazy val model: String = raw.select("model").asOptString.getOrElse(OpenRouterApi.defaultTtsModel)
  lazy val voice: String = raw.select("voice").asOptString.getOrElse(OpenRouterApi.defaultTtsVoice)
  lazy val responseFormat: Option[String] = raw.select("response_format").asOptString
  lazy val speed: Option[Double] = raw.select("speed").asOpt[Double]
}

object OpenRouterAudioModelClientTtsOptions {
  def fromJson(raw: JsObject): OpenRouterAudioModelClientTtsOptions = OpenRouterAudioModelClientTtsOptions(raw)
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/////////                                Audio transcription (STT) + speech (TTS)                         ///////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

class OpenRouterAudioModelClient(val api: OpenRouterApi, val ttsOptions: OpenRouterAudioModelClientTtsOptions, val sttOptions: OpenRouterAudioModelClientSttOptions, id: String) extends AudioModelClient {

  override def supportsTts: Boolean = ttsOptions.enabled
  override def supportsStt: Boolean = sttOptions.enabled
  override def supportsTranslation: Boolean = false

  // OpenRouter expects the bare audio container format in input_audio.format (STT)
  private val supportedSttFormats: Set[String] = Set("wav", "mp3", "flac", "m4a", "ogg", "webm", "aac")

  private def resolveSttFormat(opts: AudioModelClientSpeechToTextInputOptions): String = {
    val fromName = opts.fileName.flatMap(_.split("\\.").lastOption).map(_.toLowerCase.trim).filter(supportedSttFormats.contains)
    val fromContentType = opts.fileContentType.split("/").lastOption.map(_.toLowerCase.split(";").head.trim).map {
      case "mpeg" => "mp3"
      case "x-wav" | "wave" | "vnd.wave" => "wav"
      case "x-flac" => "flac"
      case "mp4" | "x-m4a" => "m4a"
      case other => other
    }.filter(supportedSttFormats.contains)
    fromName.orElse(fromContentType).getOrElse("mp3")
  }

  // OpenRouter /audio/speech returns application/octet-stream, so derive a meaningful content-type
  // from the requested response_format (mp3 or pcm, defaults to pcm).
  private def ttsContentType(responseFormat: Option[String]): String = responseFormat.map(_.toLowerCase) match {
    case Some("mp3") => "audio/mpeg"
    case Some("pcm") => "audio/pcm"
    case Some(other) => s"audio/${other}"
    case None => "audio/pcm"
  }

  override def listModels(raw: Boolean)(implicit ec: ExecutionContext): Future[Either[JsValue, List[AudioGenModel]]] = {
    Right(List(
      AudioGenModel(OpenRouterApi.defaultSttModel, OpenRouterApi.defaultSttModel),
      AudioGenModel(OpenRouterApi.defaultTtsModel, OpenRouterApi.defaultTtsModel),
    )).vfuture
  }

  override def listVoices(raw: Boolean)(implicit ec: ExecutionContext): Future[Either[JsValue, List[AudioGenVoice]]] = {
    // OpenRouter voices are provider-specific (depend on the selected TTS model), no enumeration endpoint.
    List.empty.rightf
  }

  override def textToSpeech(opts: AudioModelClientTextToSpeechInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, (Source[ByteString, _], String)]] = {
    val responseFormatOpt: Option[String] = opts.responseFormat.orElse(ttsOptions.responseFormat)
    val speedOpt: Option[Double] = opts.speed.orElse(ttsOptions.speed)
    val body = Json.obj(
      "input" -> opts.input,
      "model" -> opts.model.getOrElse(ttsOptions.model).json,
      "voice" -> opts.voice.getOrElse(ttsOptions.voice).json,
    ).applyOnWithOpt(responseFormatOpt) {
      case (obj, responseFormat) => obj ++ Json.obj("response_format" -> responseFormat)
    }.applyOnWithOpt(speedOpt) {
      case (obj, speed) => obj ++ Json.obj("speed" -> speed)
    }
    api.rawCallStream("POST", "/audio/speech", body.some).map { response =>
      if (response.status == 200) {
        (response.bodyAsSource, ttsContentType(responseFormatOpt)).right
      } else {
        Left(Json.obj("error" -> "Bad response", "body" -> s"Failed with status ${response.status}: ${response.body}"))
      }
    }
  }

  override def speechToText(opts: AudioModelClientSpeechToTextInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, AudioTranscriptionResponse]] = {
    val model = opts.model.orElse(sttOptions.model).getOrElse(OpenRouterApi.defaultSttModel)
    val language = opts.language.orElse(sttOptions.language)
    val temperature = opts.temperature.orElse(sttOptions.temperature)
    val format = resolveSttFormat(opts)
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

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/////////                                  Image generation + edition                                    ///////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

// OpenRouter does not have a dedicated images endpoint: image generation AND editing both go through
// /chat/completions. A request with only a text prompt does generation; adding image_url content parts
// does editing. Output images are returned in choices[0].message.images[].image_url.url as base64 data
// URLs. The "modalities" field (["image", "text"]) tells OpenRouter to enable image output.

case class OpenRouterImageModelClientOptions(raw: JsObject) {
  lazy val enabled: Boolean = raw.select("enabled").asOptBoolean.getOrElse(true)
  lazy val model: Option[String] = raw.select("model").asOptString
  lazy val modalities: Seq[String] = raw.select("modalities").asOpt[Seq[String]].filter(_.nonEmpty).getOrElse(Seq("image", "text"))
}

object OpenRouterImageModelClientOptions {
  def fromJson(raw: JsObject): OpenRouterImageModelClientOptions = OpenRouterImageModelClientOptions(raw)
}

class OpenRouterImageModelClient(val api: OpenRouterApi, val genOptions: OpenRouterImageModelClientOptions, val editOptions: OpenRouterImageModelClientOptions, id: String) extends ImageModelClient {

  override def supportsGeneration: Boolean = genOptions.enabled
  override def supportsEdit: Boolean = editOptions.enabled

  // turn an uploaded image (a streamed source of bytes) into the base64 data URL OpenRouter expects
  private def imageToDataUrl(img: ImageFile)(implicit ec: ExecutionContext, env: Env): Future[String] = {
    img.bytes.runFold(ByteString.empty)(_ ++ _)(env.otoroshiMaterializer).map { bytes =>
      val ct = Option(img.contentType).filter(_.startsWith("image/")).getOrElse("image/png")
      s"data:${ct};base64,${bytes.encodeBase64.utf8String}"
    }
  }

  private def chatBody(model: String, prompt: String, imageDataUrls: Seq[String], modalities: Seq[String]): JsObject = {
    val parts: Seq[JsValue] = Json.obj("type" -> "text", "text" -> prompt) +:
      imageDataUrls.map(u => Json.obj("type" -> "image_url", "image_url" -> Json.obj("url" -> u)))
    Json.obj(
      "model" -> model,
      "messages" -> Json.arr(Json.obj("role" -> "user", "content" -> JsArray(parts))),
      "modalities" -> modalities,
    )
  }

  // OpenRouter returns generated images inside the assistant chat message (choices[0].message.images),
  // each as a base64 data URL. Normalize them to the gateway's OpenAI-images shape (b64_json / url).
  private def parseResponse(resp: WSResponse): Either[JsValue, ImagesGenResponse] = {
    if (resp.status == 200) {
      val json = resp.json
      val headers = resp.headers.mapValues(_.last)
      val message = json.select("choices").asOpt[Seq[JsObject]].flatMap(_.headOption)
        .flatMap(_.select("message").asOpt[JsObject]).getOrElse(Json.obj())
      val text = message.select("content").asOptString.filter(_.nonEmpty)
      val images = message.select("images").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map { img =>
        img.at("image_url.url").asOptString match {
          case Some(u) if u.startsWith("data:") && u.contains("base64,") =>
            ImagesGen(b64Json = Some(u.substring(u.indexOf("base64,") + "base64,".length)), revisedPrompt = text, url = None)
          case other =>
            ImagesGen(b64Json = None, revisedPrompt = text, url = other)
        }
      }
      if (images.isEmpty) {
        Left(Json.obj("error" -> "no image in response", "body" -> json))
      } else {
        Right(ImagesGenResponse(
          created = json.select("created").asOpt[Long].getOrElse(-1L),
          images = images,
          metadata = ImagesGenResponseMetadata(
            rateLimit = ChatResponseMetadataRateLimit(
              requestsLimit = headers.getIgnoreCase("x-ratelimit-limit-requests").map(_.toLong).getOrElse(-1L),
              requestsRemaining = headers.getIgnoreCase("x-ratelimit-remaining-requests").map(_.toLong).getOrElse(-1L),
              tokensLimit = headers.getIgnoreCase("x-ratelimit-limit-tokens").map(_.toLong).getOrElse(-1L),
              tokensRemaining = headers.getIgnoreCase("x-ratelimit-remaining-tokens").map(_.toLong).getOrElse(-1L),
            ), impacts = None, costs = None,
            usage = ImagesGenResponseMetadataUsage(
              totalTokens = json.at("usage.total_tokens").asOpt[Long].getOrElse(-1L),
              tokenInput = json.at("usage.prompt_tokens").asOpt[Long].getOrElse(-1L),
              tokenOutput = json.at("usage.completion_tokens").asOpt[Long].getOrElse(-1L),
              tokenText = json.at("usage.prompt_tokens_details.text_tokens").asOpt[Long].getOrElse(-1L),
              tokenImage = json.at("usage.prompt_tokens_details.image_tokens").asOpt[Long].getOrElse(-1L),
            )
          )
        ))
      }
    } else {
      Left(Json.obj("status" -> resp.status, "body" -> resp.json))
    }
  }

  override def generate(opts: ImageModelClientGenerationInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ImagesGenResponse]] = {
    val finalModel = opts.model.orElse(genOptions.model).getOrElse(OpenRouterApi.defaultImageModel)
    val body = chatBody(finalModel, opts.prompt, Seq.empty, genOptions.modalities)
    api.rawCall("POST", "/chat/completions", body.some).map(parseResponse)
  }

  override def edit(opts: ImageModelClientEditionInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ImagesGenResponse]] = {
    val finalModel = opts.model.orElse(editOptions.model).getOrElse(OpenRouterApi.defaultImageModel)
    Future.sequence(opts.images.map(imageToDataUrl)).flatMap { dataUrls =>
      val body = chatBody(finalModel, opts.prompt, dataUrls, editOptions.modalities)
      api.rawCall("POST", "/chat/completions", body.some).map(parseResponse)
    }
  }
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/////////                                       Video generation                                         ///////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

// OpenRouter video generation is asynchronous: POST /videos returns 202 with a job id and a "pending"
// status, and the result (unsigned_urls) only becomes available once the job reaches "completed". The
// gateway's video abstraction is synchronous (generate must return the video urls), so this client polls
// GET /videos/{id} until the job is done (bounded by max_poll_attempts * poll_interval).

case class OpenRouterVideoModelClientOptions(raw: JsObject) {
  lazy val enabled: Boolean = raw.select("enabled").asOptBoolean.getOrElse(true)
  lazy val model: Option[String] = raw.select("model").asOptString
  lazy val aspectRatio: Option[String] = raw.select("aspect_ratio").asOptString
  lazy val resolution: Option[String] = raw.select("resolution").asOptString
  lazy val duration: Option[Int] = raw.select("duration").asOptInt
  lazy val seed: Option[Int] = raw.select("seed").asOptInt
  lazy val generateAudio: Option[Boolean] = raw.select("generate_audio").asOptBoolean
  lazy val pollInterval: FiniteDuration = raw.select("poll_interval").asOpt[Long].map(_.millis).getOrElse(5.seconds)
  lazy val maxPollAttempts: Int = raw.select("max_poll_attempts").asOptInt.getOrElse(60)
}

object OpenRouterVideoModelClientOptions {
  def fromJson(raw: JsObject): OpenRouterVideoModelClientOptions = OpenRouterVideoModelClientOptions(raw)
}

class OpenRouterVideoModelClient(val api: OpenRouterApi, val genOptions: OpenRouterVideoModelClientOptions, id: String) extends VideoModelClient {

  private val terminalFailures = Set("failed", "cancelled", "expired")

  override def supportsTextToVideo: Boolean = genOptions.enabled

  private def buildResponse(json: JsValue, headers: Map[String, String]): Either[JsValue, VideosGenResponse] = {
    val urls = json.select("unsigned_urls").asOpt[Seq[String]].getOrElse(Seq.empty).filter(_.nonEmpty)
    val videos = if (urls.nonEmpty) urls.map(u => VideosGen(None, None, Some(u))) else Seq(VideosGen(None, None, None))
    Right(VideosGenResponse(
      created = json.select("created_at").asOpt[Long].getOrElse(-1L),
      videos = videos,
      metadata = VideosGenResponseMetadata(
        rateLimit = ChatResponseMetadataRateLimit(
          requestsLimit = headers.getIgnoreCase("x-ratelimit-limit-requests").map(_.toLong).getOrElse(-1L),
          requestsRemaining = headers.getIgnoreCase("x-ratelimit-remaining-requests").map(_.toLong).getOrElse(-1L),
          tokensLimit = headers.getIgnoreCase("x-ratelimit-limit-tokens").map(_.toLong).getOrElse(-1L),
          tokensRemaining = headers.getIgnoreCase("x-ratelimit-remaining-tokens").map(_.toLong).getOrElse(-1L),
        ), impacts = None, costs = None,
        usage = VideosGenResponseMetadataUsage(
          totalTokens = -1L, tokenInput = -1L, tokenOutput = -1L, tokenText = -1L, tokenImage = -1L
        )
      )
    ))
  }

  // poll GET /videos/{id} until the job completes, fails, or we run out of attempts
  private def pollUntilDone(jobId: String, attemptsLeft: Int)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, VideosGenResponse]] = {
    if (attemptsLeft <= 0) {
      Left(Json.obj("error" -> "video generation timed out while polling", "job_id" -> jobId)).vfuture
    } else {
      akka.pattern.after(genOptions.pollInterval, env.otoroshiScheduler)(Future.successful(()))(ec).flatMap { _ =>
        api.rawCall("GET", s"/videos/${jobId}", None).flatMap { resp =>
          if (resp.status == 200) {
            val json = resp.json
            json.select("status").asOptString.getOrElse("pending").toLowerCase match {
              case "completed" => buildResponse(json, resp.headers.mapValues(_.last)).vfuture
              case s if terminalFailures.contains(s) => Left(Json.obj("error" -> s"video generation $s", "body" -> json)).vfuture
              case _ => pollUntilDone(jobId, attemptsLeft - 1)
            }
          } else {
            Left(Json.obj("error" -> "Bad response while polling", "status" -> resp.status, "body" -> resp.json)).vfuture
          }
        }
      }
    }
  }

  override def generate(opts: VideoModelClientTextToVideoInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, VideosGenResponse]] = {
    val finalModel: String = opts.model.orElse(genOptions.model).getOrElse(OpenRouterApi.defaultVideoModel)
    // gateway duration is a string (e.g. "8" or "5s"); OpenRouter wants an integer number of seconds
    val durationOpt: Option[Int] = opts.duration.flatMap(d => "\\d+".r.findFirstIn(d.trim)).flatMap(s => scala.util.Try(s.toInt).toOption).orElse(genOptions.duration)
    val body = Json.obj(
      "model" -> finalModel,
      "prompt" -> opts.prompt,
    )
      .applyOnWithOpt(opts.aspect_ratio.orElse(genOptions.aspectRatio)) { case (obj, ar) => obj ++ Json.obj("aspect_ratio" -> ar) }
      .applyOnWithOpt(opts.resolution.orElse(genOptions.resolution)) { case (obj, res) => obj ++ Json.obj("resolution" -> res) }
      .applyOnWithOpt(durationOpt) { case (obj, d) => obj ++ Json.obj("duration" -> d) }
      .applyOnWithOpt(genOptions.seed) { case (obj, seed) => obj ++ Json.obj("seed" -> seed) }
      .applyOnWithOpt(genOptions.generateAudio) { case (obj, ga) => obj ++ Json.obj("generate_audio" -> ga) }
    api.rawCall("POST", "/videos", body.some).flatMap { resp =>
      if (resp.status == 200 || resp.status == 202) {
        val json = resp.json
        json.select("id").asOptString match {
          case None => Left(Json.obj("error" -> "no job id in response", "body" -> json)).vfuture
          case Some(jobId) =>
            json.select("status").asOptString.getOrElse("pending").toLowerCase match {
              case "completed" => buildResponse(json, resp.headers.mapValues(_.last)).vfuture
              case s if terminalFailures.contains(s) => Left(Json.obj("error" -> s"video generation $s", "body" -> json)).vfuture
              case _ => pollUntilDone(jobId, genOptions.maxPollAttempts)
            }
        }
      } else {
        Left(Json.obj("error" -> "Bad response", "status" -> resp.status, "body" -> resp.body)).vfuture
      }
    }
  }
}
