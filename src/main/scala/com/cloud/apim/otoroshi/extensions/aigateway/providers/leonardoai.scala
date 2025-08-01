package com.cloud.apim.otoroshi.extensions.aigateway.providers

import com.cloud.apim.otoroshi.extensions.aigateway._
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.libs.ws.WSResponse

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}


object LeonardoAIApi {
  val baseUrl = "https://cloud.leonardo.ai/api/rest/v1"
}

class LeonardoAIApi(baseUrl: String = LeonardoAIApi.baseUrl, token: String, timeout: FiniteDuration = 3.minutes, env: Env) {

  def rawCall(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[WSResponse] = {
    val url = s"${baseUrl}${path}"
    ProviderHelpers.logCall("LeonardoAI", method, url, body)(env)
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
      .map { resp =>
        resp
      }
  }
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/////////                                     Images Generation                                          ///////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

case class LeonardoAIImagesGenModelClientOptions(raw: JsObject) {
  lazy val enabled: Boolean = raw.select("enabled").asOpt[Boolean].getOrElse(true)
  lazy val model: Option[String] = raw.select("model").asOpt[String]
  lazy val num_images: Option[Int] = raw.select("num_images").asOpt[Int]
  lazy val num_inference_steps: Option[Int] = raw.select("num_inference_steps").asOpt[Int]
  lazy val photoReal: Option[Boolean] = raw.select("photoReal").asOpt[Boolean]
}

object LeonardoAIImagesGenModelClientOptions {
  def fromJson(raw: JsObject): LeonardoAIImagesGenModelClientOptions = LeonardoAIImagesGenModelClientOptions(raw)
}

class LeonardoAIImageModelClient(val api: LeonardoAIApi, val genOptions: LeonardoAIImagesGenModelClientOptions, id: String) extends ImageModelClient {

  override def supportsGeneration: Boolean = genOptions.enabled
  override def supportsEdit: Boolean = false

  override def generate(opts: ImageModelClientGenerationInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ImagesGenResponse]] = {
    val finalModel: String = opts.model.orElse(genOptions.model).getOrElse("6b645e3a-d64f-4341-a6d8-7a3690fbf042")
    val body = Json.obj(
      "prompt" -> opts.prompt,
      "modelId" -> finalModel,
      "negative_prompt" -> (rawBody.select("negative_prompt").asOpt[JsString].getOrElse(JsNull).asValue)
    )
    .applyOnWithOpt(opts.n.orElse(genOptions.num_images)) { case (obj, num_images) => obj ++ Json.obj("num_images" -> num_images) }
    .applyOnWithOpt(rawBody.select("num_inference_steps").asOptInt.orElse(genOptions.num_inference_steps)) { case (obj, num_inference_steps) => obj ++ Json.obj("num_inference_steps" -> num_inference_steps) }
    .applyOnWithOpt(rawBody.select("photoReal").asOptBoolean.orElse(genOptions.photoReal)) { case (obj, photoReal) => obj ++ Json.obj("photoReal" -> photoReal) }

    api.rawCall("POST", s"/generations", body.some).map { resp =>
      if (resp.status == 200) {
        val headers = resp.headers.mapValues(_.last)
        Right(ImagesGenResponse(
          created = System.currentTimeMillis(),
          images = Seq(ImagesGen(None, None, resp.json.select("sdGenerationJob").select("generationId").asOpt[String].map(id => s"https://cloud.leonardo.ai/api/rest/v1/generations/${id}"))),
          metadata = ImagesGenResponseMetadata(
            rateLimit = ChatResponseMetadataRateLimit(
              requestsLimit = headers.getIgnoreCase("x-ratelimit-limit-requests").map(_.toLong).getOrElse(-1L),
              requestsRemaining = headers.getIgnoreCase("x-ratelimit-remaining-requests").map(_.toLong).getOrElse(-1L),
              tokensLimit = headers.getIgnoreCase("x-ratelimit-limit-tokens").map(_.toLong).getOrElse(-1L),
              tokensRemaining = headers.getIgnoreCase("x-ratelimit-remaining-tokens").map(_.toLong).getOrElse(-1L),
            ), impacts = None, costs = None,
            usage = ImagesGenResponseMetadataUsage(
              totalTokens = resp.json.at("usage.total_tokens").asOpt[Long].getOrElse(-1L),
              tokenInput = resp.json.at("usage.input_tokens").asOpt[Long].getOrElse(-1L),
              tokenOutput = resp.json.at("usage.output_tokens").asOpt[Long].getOrElse(-1L),
              tokenText = resp.json.at("usage.input_tokens_details.text_tokens").asOpt[Long].getOrElse(-1L),
              tokenImage = resp.json.at("usage.input_tokens_details.image_tokens").asOpt[Long].getOrElse(-1L),
            )
          )
        ))
      } else {
        Left(Json.obj("status" -> resp.status, "body" -> resp.json))
      }
    }
  }
}