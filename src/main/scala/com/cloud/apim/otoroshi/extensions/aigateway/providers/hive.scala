package com.cloud.apim.otoroshi.extensions.aigateway.providers

import com.cloud.apim.otoroshi.extensions.aigateway._
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.libs.ws.WSResponse

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}


object HiveApi {
  val baseUrl = "https://api.thehive.ai/api/v3"
}

class HiveApi(baseUrl: String = HiveApi.baseUrl, token: String, timeout: FiniteDuration = 30.seconds, env: Env) {

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

case class HiveImagesGenModelClientOptions(raw: JsObject) {
  lazy val model: String = raw.select("model").asOpt[String].getOrElse("black-forest-labs/flux-schnell")
}

object HiveImagesGenModelClientOptions {
  def fromJson(raw: JsObject): HiveImagesGenModelClientOptions = HiveImagesGenModelClientOptions(raw)
}

class HiveImagesGenModelClient(val api: HiveApi, val options: HiveImagesGenModelClientOptions, id: String) extends ImagesGenModelClient {

  override def generate(promptInput: String, modelOpt: Option[String], imgSizeOpt: Option[String])(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ImagesGenResponse]] = {
    val finalModel: String = modelOpt.getOrElse(options.model)
    api.rawCall("POST", s"/${finalModel}", (options.raw ++
      Json.obj(
        "image_size" -> Json.obj(
          "width" -> options.raw.select("width").asOpt[Int].getOrElse(1024).json,
          "height" -> options.raw.select("height").asOpt[Int].getOrElse(1024).json
        ),
        "output_format" -> options.raw.select("output_format").asOptString.getOrElse("jpeg").json,
        "prompt" -> promptInput,
      )).some).map { resp =>
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