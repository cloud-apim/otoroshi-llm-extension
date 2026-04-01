package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import akka.stream.Materializer
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.KreuzbergHelper
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

case class ContentToMarkdownConfig(
  maxBodySize: Long = 10L * 1024L * 1024L
) extends NgPluginConfig {
  def json: JsValue = ContentToMarkdownConfig.format.writes(this)
}

object ContentToMarkdownConfig {
  val default: ContentToMarkdownConfig = ContentToMarkdownConfig()
  val configFlow: Seq[String] = Seq("maxBodySize")
  val configSchema: Option[JsObject] = Some(Json.obj(
    "maxBodySize" -> Json.obj(
      "type" -> "number",
      "label" -> "Max body size (bytes)",
      "props" -> Json.obj(
        "description" -> "Maximum size of the body to process (default: 10MB)"
      )
    )
  ))
  val format = new Format[ContentToMarkdownConfig] {
    override def writes(o: ContentToMarkdownConfig): JsValue = Json.obj(
      "maxBodySize" -> o.maxBodySize
    )
    override def reads(json: JsValue): JsResult[ContentToMarkdownConfig] = Try {
      ContentToMarkdownConfig(
        maxBodySize = (json \ "maxBodySize").asOpt[Long].getOrElse(10L * 1024L * 1024L)
      )
    } match {
      case scala.util.Success(c) => JsSuccess(c)
      case scala.util.Failure(e) => JsError(e.getMessage)
    }
  }
}

class ContentToMarkdownPlugin extends NgBackendCall {

  override def name: String = "Cloud APIM - Content to Markdown"
  override def description: Option[String] = "Converts document content (from URL or body) to markdown using Kreuzberg (requires JDK 25+)".some
  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))
  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)
  override def useDelegates: Boolean = false
  override def defaultConfigObject: Option[NgPluginConfig] = Some(ContentToMarkdownConfig.default)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = ContentToMarkdownConfig.configFlow
  override def configSchema: Option[JsObject] = ContentToMarkdownConfig.configSchema

  override def start(env: Env): Future[Unit] = {
    env.logger.info("the 'Content to Markdown' plugin is available !")
    ().vfuture
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    if (!KreuzbergHelper.canExecuteKreuzberg) {
      return Left(NgProxyEngineError.NgResultProxyEngineError(
        Results.InternalServerError(Json.obj("error" -> KreuzbergHelper.errorMsg))
      )).vfuture
    }
    val config = ctx.cachedConfig(internalName)(ContentToMarkdownConfig.format).getOrElse(ContentToMarkdownConfig.default)
    val urlOpt = ctx.request.queryParam("url")

    urlOpt match {
      case Some(encodedUrl) =>
        val url = java.net.URLDecoder.decode(encodedUrl, "UTF-8")
        KreuzbergHelper.extractFromUrl(url).map { case (markdown, sourceType) =>
          Right(BackendCallResponse(NgPluginHttpResponse.fromResult(
            Results.Ok(markdown).as("text/markdown; charset=utf-8")
              .withHeaders("X-Source-Content-Type" -> sourceType)
          ), None))
        }.recover {
          case e: Exception =>
            Left(NgProxyEngineError.NgResultProxyEngineError(
              Results.InternalServerError(Json.obj("error" -> s"conversion failed: ${e.getMessage}"))
            ))
        }
      case None if ctx.request.hasBody =>
        ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
          if (bodyRaw.length > config.maxBodySize) {
            Left(NgProxyEngineError.NgResultProxyEngineError(
              Results.EntityTooLarge(Json.obj("error" -> s"body exceeds max size of ${config.maxBodySize} bytes"))
            )).vfuture
          } else {
            Try(bodyRaw.utf8String.parseJson) match {
              case scala.util.Success(json) =>
                val bodyUrl = json.select("url").asOpt[String]
                val method = json.select("method").asOpt[String].getOrElse("GET").toUpperCase
                val headers = json.select("headers").asOpt[JsObject].map(_.fields.map { case (k, v) => k -> v.asOpt[String].getOrElse(v.toString()) }.toMap).getOrElse(Map.empty)
                val content = json.select("content").asOpt[String]
                val contentType = json.select("content_type").asOpt[String]

                (bodyUrl, content, contentType) match {
                  case (Some(url), _, _) =>
                    KreuzbergHelper.extractFromUrl(url, method, headers).map { case (markdown, sourceType) =>
                      Right(BackendCallResponse(NgPluginHttpResponse.fromResult(
                        Results.Ok(markdown).as("text/markdown; charset=utf-8")
                          .withHeaders("X-Source-Content-Type" -> sourceType)
                      ), None))
                    }.recover {
                      case e: Exception =>
                        Left(NgProxyEngineError.NgResultProxyEngineError(
                          Results.InternalServerError(Json.obj("error" -> s"conversion failed: ${e.getMessage}"))
                        ))
                    }
                  case (_, Some(b64Content), Some(ct)) =>
                    val bytes = java.util.Base64.getDecoder.decode(b64Content)
                    KreuzbergHelper.extractFromBytes(bytes, ct).map { markdown =>
                      Right(BackendCallResponse(NgPluginHttpResponse.fromResult(
                        Results.Ok(markdown).as("text/markdown; charset=utf-8")
                          .withHeaders("X-Source-Content-Type" -> ct)
                      ), None))
                    }.recover {
                      case e: Exception =>
                        Left(NgProxyEngineError.NgResultProxyEngineError(
                          Results.InternalServerError(Json.obj("error" -> s"conversion failed: ${e.getMessage}"))
                        ))
                    }
                  case _ =>
                    Left(NgProxyEngineError.NgResultProxyEngineError(
                      Results.BadRequest(Json.obj("error" -> "provide 'url' or 'content' + 'content_type' in JSON body"))
                    )).vfuture
                }
              case scala.util.Failure(_) =>
                // Body is not JSON, treat as raw document content
                val contentType = ctx.request.contentType.getOrElse("application/octet-stream").split(";").head.trim
                KreuzbergHelper.extractFromBytes(bodyRaw.toArray, contentType).map { markdown =>
                  Right(BackendCallResponse(NgPluginHttpResponse.fromResult(
                    Results.Ok(markdown).as("text/markdown; charset=utf-8")
                      .withHeaders("X-Source-Content-Type" -> contentType)
                  ), None))
                }.recover {
                  case e: Exception =>
                    Left(NgProxyEngineError.NgResultProxyEngineError(
                      Results.InternalServerError(Json.obj("error" -> s"conversion failed: ${e.getMessage}"))
                    ))
                }
            }
          }
        }
      case None =>
        Left(NgProxyEngineError.NgResultProxyEngineError(
          Results.BadRequest(Json.obj("error" -> "provide a 'url' query parameter or a request body"))
        )).vfuture
    }
  }
}
