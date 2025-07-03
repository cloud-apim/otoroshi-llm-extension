package com.cloud.apim.otoroshi.extensions.aigateway.providers

import com.cloud.apim.otoroshi.extensions.aigateway._
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.libs.ws.WSResponse

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}


object HiveApi {
  val baseUrl = "https://api.thehive.ai/api/v3"
}

class HiveApi(baseUrl: String = HiveApi.baseUrl, token: String, timeout: FiniteDuration = 3.minutes, env: Env) {

  def rawCall(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[WSResponse] = {
    val url = s"${baseUrl}${path}"
    ProviderHelpers.logCall("Hive", method, url, body)(env)
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

case class HiveImageModelClientOptions(raw: JsObject) {
  lazy val enabled: Boolean = raw.select("enabled").asOpt[Boolean].getOrElse(true)
  lazy val model: Option[String] = raw.select("model").asOpt[String]
  lazy val num_images: Option[Int] = raw.select("num_images").asOpt[Int]
  lazy val imageWidth: Option[Int] = raw.select("image_size").select("width").asOpt[Int]
  lazy val imageHeight: Option[Int] = raw.select("image_size").select("height").asOpt[Int]
  lazy val num_inference_steps: Option[Int] = raw.select("num_inference_steps").asOpt[Int]
  lazy val seed: Option[Int] = raw.select("seed").asOpt[Int]
  lazy val output_quality: Option[Int] = raw.select("output_quality").asOpt[Int]
  lazy val output_format: Option[String] = raw.select("output_format").asOptString
}

object HiveImageModelClientOptions {
  def fromJson(raw: JsObject): HiveImageModelClientOptions = HiveImageModelClientOptions(raw)
}

class HiveImageModelClient(val api: HiveApi, val genOptions: HiveImageModelClientOptions, id: String) extends ImageModelClient {

  override def supportsGeneration: Boolean = genOptions.enabled
  override def supportsEdit: Boolean = false

  override def generate(opts: ImageModelClientGenerationInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ImagesGenResponse]] = {
    val finalModel: String = opts.model.orElse(genOptions.model).getOrElse("black-forest-labs/flux-schnell")
    val body = Json.obj(
        "prompt" -> opts.prompt,
        "image_size" -> Json.obj(
          "width" -> genOptions.imageWidth.getOrElse(1024).json,
          "height" -> genOptions.imageHeight.getOrElse(1024).json
        )
      )
      .applyOnWithOpt(opts.outputFormat.orElse(genOptions.output_format)) { case (obj, output_format) => obj ++ Json.obj("output_format" -> output_format) }
      .applyOnWithOpt(opts.n.orElse(genOptions.num_images)) { case (obj, num_images) => obj ++ Json.obj("num_images" -> num_images) }
      .applyOnWithOpt(rawBody.select("num_inference_steps").asOptInt.orElse(genOptions.num_inference_steps)) { case (obj, num_inference_steps) => obj ++ Json.obj("num_inference_steps" -> num_inference_steps) }
      .applyOnWithOpt(rawBody.select("seed").asOptInt.orElse(genOptions.seed)) { case (obj, seed) => obj ++ Json.obj("seed" -> seed) }
      .applyOnWithOpt(rawBody.select("output_quality").asOptInt.orElse(genOptions.output_quality)) { case (obj, output_quality) => obj ++ Json.obj("output_quality" -> output_quality) }

    api.rawCall("POST", s"/${finalModel}", body.some).map { resp =>
      if (resp.status == 200) {
        Right(ImagesGenResponse(
          created = resp.json.select("created_at").asOpt[Long].getOrElse(-1L),
          images = resp.json.select("output").as[Seq[JsObject]].map(o => ImagesGen(None, None, o.select("url").asOpt[String])),
          metadata = None
        ))
      } else {
        Left(Json.obj("status" -> resp.status, "body" -> resp.json))
      }
    }
  }
}