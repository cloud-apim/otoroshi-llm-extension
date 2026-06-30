package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import akka.stream.Materializer
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.guardrails.{PlaceholderAllocator, RampartEngine, RampartPiiGuardrail}
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.Result

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class RampartBodyRedactorConfig(
  minScore: Float = 0.4f,
  entities: Set[String] = RampartPiiGuardrail.defaultEntities,
  contentTypes: Seq[String] = RampartBodyRedactorConfig.defaultContentTypes
) extends NgPluginConfig {
  def json: JsValue = RampartBodyRedactorConfig.format.writes(this)
}

object RampartBodyRedactorConfig {
  // body content-types we redact by default (prefix match). Empty list => redact every content-type.
  val defaultContentTypes: Seq[String] = Seq("application/json", "text/", "application/x-www-form-urlencoded")
  val default = RampartBodyRedactorConfig()
  val format = new Format[RampartBodyRedactorConfig] {
    override def reads(json: JsValue): JsResult[RampartBodyRedactorConfig] = Try {
      RampartBodyRedactorConfig(
        minScore = json.select("min_score").asOpt[Double].orElse(json.select("min_score").asOpt[String].map(_.toDouble)).getOrElse(0.4).toFloat,
        entities = json.select("entities").asOpt[Seq[String]].map(_.toSet).getOrElse(RampartPiiGuardrail.defaultEntities),
        contentTypes = json.select("content_types").asOpt[Seq[String]].getOrElse(defaultContentTypes),
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(v) => JsSuccess(v)
    }
    override def writes(o: RampartBodyRedactorConfig): JsValue = Json.obj(
      "min_score" -> o.minScore.toDouble,
      "entities" -> o.entities.toSeq,
      "content_types" -> o.contentTypes,
    )
  }
  val configFlow: Seq[String] = Seq("min_score", "entities", "content_types")
  val configSchema: Option[JsObject] = Some(Json.obj(
    "min_score" -> Json.obj("type" -> "number", "label" -> "Min score", "props" -> Json.obj("step" -> 0.05)),
    "entities" -> Json.obj("type" -> "array", "label" -> "Entities to redact"),
    "content_types" -> Json.obj("type" -> "array", "label" -> "Only these content-types (prefix match, empty = all)"),
  ))
}

object RampartBody {
  private val logger = play.api.Logger("cloud-apim-rampart-body")

  def matchesContentType(ct: Option[String], allowed: Seq[String]): Boolean = {
    if (allowed.isEmpty) true
    else ct.exists { c =>
      val lc = c.toLowerCase
      allowed.exists(a => lc.startsWith(a.toLowerCase) || lc.contains(a.toLowerCase))
    }
  }

  // redacts PII in raw text. Each call uses a fresh allocator (request and response bodies are independent).
  def redact(text: String, cfg: RampartBodyRedactorConfig)(implicit env: Env): String = {
    val engine = RampartEngine.get
    val alloc = new PlaceholderAllocator()
    engine.redactText(text, engine.detectAll(text, cfg.minScore, cfg.entities), alloc)
  }

  // fail-open: never break the proxy if the model misbehaves, just log and pass the body through unchanged
  def safeRedact(text: String, cfg: RampartBodyRedactorConfig)(implicit env: Env): String = {
    try { redact(text, cfg) } catch {
      case e: Throwable => logger.error(s"[rampart] body redaction failed, passing body through: ${e.getMessage}", e); text
    }
  }
}

// Redacts PII in the RAW REQUEST body (text) before it reaches the upstream, using the local Rampart model.
// Not suitable for streamed request bodies (the body is fully buffered to be rewritten).
class RampartRequestBodyRedactor extends NgRequestTransformer {

  override def name: String = "Cloud APIM - Rampart request body PII redaction"
  override def description: Option[String] = "Redacts personal information in the raw request body using the local Rampart model".some
  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))

  override def steps: Seq[NgStep] = Seq(NgStep.TransformRequest)
  override def defaultConfigObject: Option[NgPluginConfig] = Some(RampartBodyRedactorConfig.default)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = RampartBodyRedactorConfig.configFlow
  override def configSchema: Option[JsObject] = RampartBodyRedactorConfig.configSchema

  override def transformsError: Boolean = false
  override def transformsResponse: Boolean = false
  override def transformsRequest: Boolean = true

  override def transformRequest(ctx: NgTransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val config = ctx.cachedConfig(internalName)(RampartBodyRedactorConfig.format).getOrElse(RampartBodyRedactorConfig.default)
    val contentType = ctx.otoroshiRequest.headers.find(_._1.equalsIgnoreCase("Content-Type")).map(_._2)
    if (!ctx.otoroshiRequest.hasBody || !RampartBody.matchesContentType(contentType, config.contentTypes)) {
      ctx.otoroshiRequest.rightf
    } else {
      ctx.otoroshiRequest.body.runFold(ByteString.empty)(_ ++ _).map { raw =>
        val redacted = RampartBody.safeRedact(raw.utf8String, config)
        Right(ctx.otoroshiRequest.copy(
          headers = ctx.otoroshiRequest.headers - "Content-Length" - "content-length" ++ Map("Transfer-Encoding" -> "chunked"),
          body = redacted.byteString.chunks(32 * 1024)
        ))
      }
    }
  }
}

// Redacts PII in the RAW RESPONSE body (text) coming back from the upstream, using the local Rampart model.
// Not suitable for streamed responses (e.g. SSE): the body is fully buffered to be rewritten.
class RampartResponseBodyRedactor extends NgRequestTransformer {

  override def name: String = "Cloud APIM - Rampart response body PII redaction"
  override def description: Option[String] = "Redacts personal information in the raw response body using the local Rampart model".some
  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))

  override def steps: Seq[NgStep] = Seq(NgStep.TransformResponse)
  override def defaultConfigObject: Option[NgPluginConfig] = Some(RampartBodyRedactorConfig.default)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = RampartBodyRedactorConfig.configFlow
  override def configSchema: Option[JsObject] = RampartBodyRedactorConfig.configSchema

  override def transformsError: Boolean = false
  override def transformsResponse: Boolean = true
  override def transformsRequest: Boolean = false

  override def transformResponse(ctx: NgTransformerResponseContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val config = ctx.cachedConfig(internalName)(RampartBodyRedactorConfig.format).getOrElse(RampartBodyRedactorConfig.default)
    val contentType = ctx.otoroshiResponse.headers.find(_._1.equalsIgnoreCase("Content-Type")).map(_._2)
    if (!RampartBody.matchesContentType(contentType, config.contentTypes)) {
      ctx.otoroshiResponse.rightf
    } else {
      ctx.otoroshiResponse.body.runFold(ByteString.empty)(_ ++ _).map { raw =>
        val redacted = RampartBody.safeRedact(raw.utf8String, config)
        Right(ctx.otoroshiResponse.copy(
          headers = ctx.otoroshiResponse.headers - "Content-Length" - "content-length" ++ Map("Transfer-Encoding" -> "chunked"),
          body = redacted.byteString.chunks(32 * 1024)
        ))
      }
    }
  }
}
