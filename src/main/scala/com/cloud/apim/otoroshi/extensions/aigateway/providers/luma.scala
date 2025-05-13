package com.cloud.apim.otoroshi.extensions.aigateway.providers

import com.cloud.apim.otoroshi.extensions.aigateway._
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.libs.ws.WSResponse

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}


object LumaApi {
  val baseUrl = "https://api.lumalabs.ai"
}

class LumaApi(baseUrl: String = LumaApi.baseUrl, token: String, timeout: FiniteDuration = 30.seconds, env: Env) {

  def rawCall(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[WSResponse] = {
    val url = s"${baseUrl}${path}"
    ProviderHelpers.logCall("Luma", method, url, body)(env)
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

case class LumaImageModelClientOptions(raw: JsObject) {
  lazy val enabled: Boolean = raw.select("enabled").asOpt[Boolean].getOrElse(true)
  lazy val model: Option[String] = raw.select("model").asOpt[String]
}

object LumaImageModelClientOptions {
  def fromJson(raw: JsObject): LumaImageModelClientOptions = LumaImageModelClientOptions(raw)
}

class LumaImageModelClient(val api: LumaApi, val genOptions: LumaImageModelClientOptions, id: String) extends ImageModelClient {

  override def supportsGeneration: Boolean = genOptions.enabled
  override def supportsEdit: Boolean = false

  override def generate(opts: ImageModelClientGenerationInputOptions, rawBody: JsObject)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ImagesGenResponse]] = {
    val finalModel: String = opts.model.orElse(genOptions.model).getOrElse("photon-1")
    val body = Json.obj(
      "prompt" -> opts.prompt,
      "model" -> finalModel,
    )
    api.rawCall("POST", "/dream-machine/v1/generations/image", body.some).map { resp =>
      if (resp.status == 200) {
        val imageUrl = resp.json.at("assets.image").asOptString
        Right(ImagesGenResponse(
          created = resp.json.select("created_at").asOpt[Long].getOrElse(-1L),
          images = Seq(ImagesGen(None, None, imageUrl)),
          metadata = None
        ))
      } else {
        Left(Json.obj("status" -> resp.status, "body" -> resp.json))
      }
    }
  }
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/////////                                     Videos Generation                                          ///////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

case class LumaVideoModelClientOptions(raw: JsObject) {
  lazy val model: String = raw.select("model").asOpt[String].getOrElse("photon-1")
}

object LumaVideoModelClientOptions {
  def fromJson(raw: JsObject): LumaVideoModelClientOptions = LumaVideoModelClientOptions(raw)
}

class LumaVideoModelClient(val api: LumaApi, val options: LumaVideoModelClientOptions, id: String) extends VideoModelClient {

  override def generate(promptInput: String, modelOpt: Option[String], imgSizeOpt: Option[String])(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, VideosGenResponse]] = {
    val finalModel: String = modelOpt.getOrElse(options.model)
    api.rawCall("POST", "/dream-machine/v1/generations", (options.raw ++
      Json.obj(
        "prompt" -> promptInput,
        "model" -> finalModel,
        "generation_type" -> "video" // default value set as video
      )).some).map { resp =>
      if (resp.status == 200) {
        val imageUrl = resp.json.at("assets.video").asOptString

        Right(VideosGenResponse(
          created = resp.json.select("created_at").asOpt[Long].getOrElse(-1L),
          videos = Seq(VideosGen(None, None, imageUrl)),
          metadata = None
        ))
      } else {
        Left(Json.obj("status" -> resp.status, "body" -> resp.json))
      }
    }
  }
}