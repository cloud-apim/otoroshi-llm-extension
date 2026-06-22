package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import akka.stream.Materializer
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.{AiMetrics, OcrModelClientInputOptions}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.OcrModel
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.http._
import play.api.libs.json._
import play.api.mvc.{RequestHeader, Result, Results}
import play.core.parsers.Multipart

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class OpenAICompatOcrConfig(refs: Seq[String], maxSizeUpload: Long) extends NgPluginConfig {
  def json: JsValue = OpenAICompatOcrConfig.format.writes(this)
}

object OpenAICompatOcrConfig {
  val configFlow: Seq[String] = Seq("refs", "max_size_upload")
  def configSchema: Option[JsObject] = Some(Json.obj(
    "max_size_upload" -> Json.obj(
      "type" -> "number",
      "label" -> "Max document file size"
    ),
    "refs" -> Json.obj(
      "type" -> "select",
      "array" -> true,
      "label" -> s"OCR models",
      "props" -> Json.obj(
        "optionsFrom" -> s"/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/ocr-models",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      ),
    )
  ))
  val default = OpenAICompatOcrConfig(Seq.empty, 100 * 1024 * 1024)
  val format = new Format[OpenAICompatOcrConfig] {
    override def writes(o: OpenAICompatOcrConfig): JsValue = Json.obj("refs" -> o.refs, "max_size_upload" -> o.maxSizeUpload)
    override def reads(json: JsValue): JsResult[OpenAICompatOcrConfig] = Try {
      OpenAICompatOcrConfig(
        refs = json.select("refs").asOpt[Seq[String]].getOrElse(Seq.empty),
        maxSizeUpload = json.select("max_size_upload").asOpt[Long].getOrElse(default.maxSizeUpload),
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }

  def resolveProvider(jsonBody: JsObject, config: OpenAICompatOcrConfig)(implicit ec: ExecutionContext, env: Env): Option[OcrModel] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    jsonBody.select("provider").asOpt[String].filter(v => config.refs.contains(v)).flatMap(r => ext.states.ocrModel(r))
      .orElse(config.refs.headOption.flatMap(r => ext.states.ocrModel(r)))
  }
}

object OpenAICompatOcr {

  def handleRequest(config: OpenAICompatOcrConfig, ctx: NgbBackendCallContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val isMultipart = ctx.rawRequest.contentType.exists(_.toLowerCase.contains("multipart/form-data"))
    if (isMultipart) {
      Multipart.multipartParser(
        config.maxSizeUpload,
        allowEmptyFiles = false,
        filePartHandler = Multipart.handleFilePartAsTemporaryFile(play.api.libs.Files.SingletonTemporaryFileCreator),
        errorHandler = new HttpErrorHandler {
          override def onClientError(request: RequestHeader, statusCode: Int, message: String): Future[Result] = Results.Status(statusCode).apply(message).vfuture
          override def onServerError(request: RequestHeader, exception: Throwable): Future[Result] = Results.InternalServerError(exception.getMessage).vfuture
        }
      ).apply(ctx.rawRequest).run(ctx.request.body).flatMap {
        case Left(err) => NgProxyEngineError.NgResultProxyEngineError.apply(err).leftf
        case Right(form) => {
          var jsonBody = Json.obj()
          form.dataParts.foreach {
            case (key, value) if value.length == 1 => jsonBody = jsonBody ++ Json.obj(key -> value.head.json)
            case (key, value) => jsonBody = jsonBody ++ Json.obj(key -> JsArray(value.map(_.json)))
          }
          form.file("image").orElse(form.file("file")).orElse(form.file("document")) match {
            case None => NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(Json.obj("error" -> "bad_request", "error_details" -> "no file present (field 'image')"))).leftf
            case Some(file) => {
              val bytes = ByteString(java.nio.file.Files.readAllBytes(file.ref.path))
              executeOcr(config, jsonBody, bytes.some, file.contentType, file.filename.some, ctx)
            }
          }
        }
      }
    } else {
      ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
        val jsonBody = bodyRaw.utf8String.parseJson.asObject
        executeOcr(config, jsonBody, None, None, None, ctx)
      }
    }
  }

  private def executeOcr(config: OpenAICompatOcrConfig, jsonBody: JsObject, fileBytes: Option[ByteString], fileContentType: Option[String], fileName: Option[String], ctx: NgbBackendCallContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    OpenAICompatOcrConfig.resolveProvider(jsonBody, config) match {
      case None => NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_error", "error_details" -> "provider not found"))).leftf
      case Some(model) => model.getOcrModelClient() match {
        case None => NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_error", "error_details" -> "failed to create client"))).leftf
        case Some(client) => {
          val baseOptions = OcrModelClientInputOptions.format.reads(jsonBody).getOrElse(OcrModelClientInputOptions())
          val options = fileBytes match {
            case Some(b) => baseOptions.copy(bytes = b.some, fileContentType = fileContentType.orElse(baseOptions.fileContentType), fileName = fileName.orElse(baseOptions.fileName))
            case None => baseOptions
          }
          AiMetrics.around("ocr.extract", model.provider.toLowerCase, System.currentTimeMillis(), client.ocr(options, jsonBody, ctx.attrs)) { _ => () }.map {
            case Left(err) => NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_error", "error_details" -> err))).left
            case Right(response) => Right(BackendCallResponse.apply(NgPluginHttpResponse.fromResult(Results.Status(200).apply(response.toJson)), None))
          }
        }
      }
    }
  }
}

class OpenAICompatOcr extends NgBackendCall {

  override def name: String = "Cloud APIM - OCR backend"
  override def description: Option[String] = "Delegates call to a provider to extract text from images or pdf documents".some
  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))
  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)
  override def useDelegates: Boolean = false
  override def defaultConfigObject: Option[NgPluginConfig] = Some(OpenAICompatOcrConfig.default)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = OpenAICompatOcrConfig.configFlow
  override def configSchema: Option[JsObject] = OpenAICompatOcrConfig.configSchema

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'OCR backend' plugin is available !")
    }
    ().vfuture
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(OpenAICompatOcrConfig.format).getOrElse(OpenAICompatOcrConfig.default)
    OpenAICompatOcr.handleRequest(config, ctx)
  }
}
