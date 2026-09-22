package com.cloud.apim.otoroshi.extensions.aigateway.providers

import com.cloud.apim.otoroshi.extensions.aigateway.*
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

// Arkane Cloud chat completions are served by the generic OpenAI client (see `OpenAiLikeProviders`),
// only its image generation needs a dedicated client
object ArkaneCloudApi {
  val baseUrl = "https://console.arkanecloud.com/api/v2"
  val defaultImageModel = "stability-ai/sdxl"
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/////////                                     Images Generation                                          ///////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

// `/images/generations` is not OpenAI-shaped: `width` and `height` are required instead of `size`, diffusion
// settings are at the root of the body, and unknown fields (`n`, `quality`, `style`...) are rejected
case class ArkaneCloudImageModelClientOptions(raw: JsObject) {
  lazy val enabled: Boolean = raw.select("enabled").asOptBoolean.getOrElse(true)
  lazy val model: Option[String] = raw.select("model").asOptString
  lazy val size: Option[String] = raw.select("size").asOptString
  lazy val width: Option[Int] = raw.select("width").asOptInt
  lazy val height: Option[Int] = raw.select("height").asOptInt
}

object ArkaneCloudImageModelClientOptions {
  def fromJson(raw: JsObject): ArkaneCloudImageModelClientOptions = ArkaneCloudImageModelClientOptions(raw)
}

class ArkaneCloudImageModelClient(val api: OpenAiApi, val genOptions: ArkaneCloudImageModelClientOptions, id: String) extends ImageModelClient {

  private val passThroughFields = Seq("num_inference_steps", "seed", "guidance_scale", "negative_prompt", "response_extension")

  override def supportsGeneration: Boolean = genOptions.enabled
  override def supportsEdit: Boolean = false

  // `1024x768` -> (1024, 768), anything else (`auto`, missing) -> None
  private def parseSize(size: Option[String]): Option[(Int, Int)] = size.flatMap { s =>
    s.toLowerCase.split("x").toList match {
      case w :: h :: Nil => Try((w.trim.toInt, h.trim.toInt)).toOption
      case _             => None
    }
  }

  override def generate(opts: ImageModelClientGenerationInputOptions, rawBody: JsObject, attrs: TypedMap)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, ImagesGenResponse]] = {
    val finalModel = opts.model.orElse(genOptions.model).getOrElse(ArkaneCloudApi.defaultImageModel)
    // the request wins over the config, and on each side `width` / `height` win over `size`
    val requestSize = parseSize(opts.size)
    val configSize = parseSize(genOptions.size)
    val width = rawBody.select("width").asOptInt.orElse(requestSize.map(_._1)).orElse(genOptions.width).orElse(configSize.map(_._1)).getOrElse(1024)
    val height = rawBody.select("height").asOptInt.orElse(requestSize.map(_._2)).orElse(genOptions.height).orElse(configSize.map(_._2)).getOrElse(1024)
    val body = Json.obj(
        "model" -> finalModel,
        "prompt" -> opts.prompt,
        "width" -> width,
        "height" -> height,
      )
      .applyOnWithOpt(opts.responseFormat.orElse(genOptions.raw.select("response_format").asOptString)) { case (obj, responseFormat) => obj ++ Json.obj("response_format" -> responseFormat) }
      // the OpenAI `output_format` is the Arkane `response_extension`
      .applyOnWithOpt(opts.outputFormat) { case (obj, outputFormat) => obj ++ Json.obj("response_extension" -> outputFormat) }
      .applyOn { obj =>
        passThroughFields.foldLeft(obj) { case (acc, field) =>
          rawBody.select(field).asOpt[JsValue].orElse(genOptions.raw.select(field).asOpt[JsValue]) match {
            case Some(value) => acc ++ Json.obj(field -> value)
            case None        => acc
          }
        }
      }

    api.rawCall("POST", "/images/generations", body.some).map { resp =>
      if (resp.status == 200) {
        Right(ImagesGenResponse(
          created = resp.json.select("created").asOpt[Long].getOrElse(System.currentTimeMillis() / 1000),
          images = resp.json.select("data").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map(o => ImagesGen(o.select("b64_json").asOpt[String], None, o.select("url").asOpt[String])),
          metadata = ImagesGenResponseMetadata(
            rateLimit = ChatResponseMetadataRateLimit.empty,
            usage = ImagesGenResponseMetadataUsage.empty,
          )
        ))
      } else {
        Left(Json.obj("status" -> resp.status, "body" -> resp.json))
      }
    }
  }
}
