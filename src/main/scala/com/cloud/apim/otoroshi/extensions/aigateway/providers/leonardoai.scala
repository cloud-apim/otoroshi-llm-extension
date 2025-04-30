package com.cloud.apim.otoroshi.extensions.aigateway.providers

import com.cloud.apim.otoroshi.extensions.aigateway._
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.libs.ws.WSResponse

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}


object LeonardoAIApi {
  val baseUrl = "https://cloud.leonardo.ai/api/rest/v1"
}

class LeonardoAIApi(baseUrl: String = LeonardoAIApi.baseUrl, token: String, timeout: FiniteDuration = 30.seconds, env: Env) {

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
  lazy val model: String = raw.select("model").asOpt[String].getOrElse("black-forest-labs/flux-schnell")
}

object LeonardoAIImagesGenModelClientOptions {
  def fromJson(raw: JsObject): LeonardoAIImagesGenModelClientOptions = LeonardoAIImagesGenModelClientOptions(raw)
}

class LeonardoAIImagesGenModelClient(val api: LeonardoAIApi, val options: LeonardoAIImagesGenModelClientOptions, id: String) extends ImagesGenModelClient {

  override def generate(promptInput: String, modelOpt: Option[String], imgSizeOpt: Option[String])(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ImagesGenResponse]] = {
    val finalModel: String = modelOpt.getOrElse(options.model)
    api.rawCall("POST", s"/${finalModel}", (options.raw ++
      Json.obj(
        "prompt" -> promptInput,
      )).some).map { resp =>
      if (resp.status == 200) {
        Right(ImagesGenResponse(
          created = resp.json.select("created_at").asOpt[Long].getOrElse(-1L),
          images = resp.json.select("output").as[Seq[JsObject]].map(o => ImagesGen(o.select("b64_json").asOpt[String], o.select("revised_prompt").asOpt[String], o.select("url").asOpt[String])),
          metadata = None
        ))
      } else {
        Left(Json.obj("status" -> resp.status, "body" -> resp.json))
      }
    }
  }
}