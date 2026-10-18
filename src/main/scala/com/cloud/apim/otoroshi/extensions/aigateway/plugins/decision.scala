package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import com.github.luben.zstd.ZstdInputStream
import org.apache.pekko.http.scaladsl.model.{ContentType, HttpEntity, Multipart}
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.apache.pekko.stream.{Materializer, StreamLimitReachedException}
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.decorators.ModelTarget
import com.cloud.apim.otoroshi.extensions.aigateway.entities.DecisionModel
import com.cloud.apim.otoroshi.extensions.aigateway.providers.OpenAiDecisions
import com.cloud.apim.otoroshi.extensions.aigateway.{DecisionErrors, DecisionImages, DecisionModelClientInputOptions, DecisionRequests, DecisionResponse}
import otoroshi.env.Env
import otoroshi.next.plugins.api.*
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.*
import play.api.mvc.{Result, Results}

import java.io.{ByteArrayInputStream, InputStream}
import java.util.Base64
import java.util.concurrent.TimeoutException
import java.util.zip.GZIPInputStream
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class DecisionModelsConfig(refs: Seq[String]) extends NgPluginConfig {
  def json: JsValue = DecisionModelsConfig.format.writes(this)
}

object DecisionModelsConfig {
  val configFlow: Seq[String] = Seq("refs")
  def configSchema: Option[JsObject] = Some(Json.obj(
    "refs" -> Json.obj(
      "type" -> "select",
      "array" -> true,
      "label" -> s"Decision models",
      "props" -> Json.obj(
        "optionsFrom" -> s"/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/decision-models",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      ),
    )
  ))
  val default = DecisionModelsConfig(Seq.empty)
  val format = new Format[DecisionModelsConfig] {
    override def writes(o: DecisionModelsConfig): JsValue = Json.obj("refs" -> o.refs)
    override def reads(json: JsValue): JsResult[DecisionModelsConfig] = Try {
      DecisionModelsConfig(
        refs = json.select("refs").asOpt[Seq[String]].getOrElse(Seq.empty)
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

/**
 * Which decision model a request is for, and the model it asks it for.
 *
 * The other model types read `<entity>/<model>` and nothing else. That rule alone would misroute decisions:
 * the model ids of these providers have slashes of their own (`typesafe/jev-1.13` on OpenRouter,
 * `telnyx/decision-flash`), and an entity named "TypeSafe" has the slug `typesafe`. So a model that is the very
 * one an entity is configured with goes to that entity as it is, before any prefix is read in it.
 */
object DecisionModelsResolver {

  final case class Resolved(entity: DecisionModel, model: Option[String], body: JsObject)

  private def named(entities: Seq[DecisionModel], name: String): Option[DecisionModel] =
    entities.find(_.id == name).orElse(entities.find(_.slugName == name))

  private def modelOf(value: String): Option[String] =
    Some(value.trim).filter(v => v.nonEmpty && v != ModelTarget.DefaultModel)

  def resolve(body: JsObject, entities: Seq[DecisionModel]): Option[Resolved] = {
    val requested = body.select("model").asOptString.map(_.trim).filter(_.nonEmpty)
    // a string `provider` is the entity to use, the gateway's own way of choosing one. As an object it is the
    // routing preferences of OpenRouter, left in the body for the client to forward or not
    val providerName = body.select("provider").asOptString
    val cleaned = if (providerName.isDefined) body - "provider" else body
    def resolved(entity: DecisionModel, model: Option[String]): Resolved =
      Resolved(entity, model, model.map(m => cleaned ++ Json.obj("model" -> m)).getOrElse(cleaned - "model"))

    // 1. `<entity>###<model>` cannot be read any other way
    val explicit = requested.filter(_.contains("###")).flatMap { value =>
      val idx = value.indexOf("###")
      named(entities, value.substring(0, idx)).map(e => resolved(e, modelOf(value.substring(idx + 3))))
    }
    // 2. the entity named in `provider`
    def byProvider = providerName.flatMap(name => named(entities, name)).map(e => resolved(e, requested))
    // 3. the model an entity is configured with, whatever is in its name
    def byModel = requested.flatMap(m => entities.find(_.defaultModel.contains(m)).map(e => resolved(e, m.some)))
    // 4. `<entity>/<model>`
    def byPrefix = requested.filter(_.contains("/")).flatMap { value =>
      val idx = value.indexOf("/")
      named(entities, value.substring(0, idx)).map(e => resolved(e, modelOf(value.substring(idx + 1))))
    }
    // 5. the first decision model of the route
    def first = entities.headOption.map(e => resolved(e, requested))

    explicit.orElse(byProvider).orElse(byModel).orElse(byPrefix).orElse(first)
  }
}

/**
 * The body of a System One request, as the TypeSafe api takes it: json, compressed or not (`gzip`, `zstd`, which
 * its sdks use for any body of 1 KiB or more), or multipart, the pictures of the assets being parts of their own.
 * It is read as json, the parts of the assets in their `data_base64`, the way the gateway sends them on.
 */
object SystemOneBodies {

  // the limit of the TypeSafe api, compressed or not
  val maxSize: Int = 32 * 1024 * 1024

  // the part of a picture: an image, bytes, or nothing said (`text/plain` by default)
  private val partTypes = Set("image/png", "image/jpeg", "image/jpg", "application/octet-stream", "text/plain")

  val jsonInvalid: JsValue = DecisionErrors.invalid(Seq(Json.obj("type" -> "json_invalid", "loc" -> Seq("body"), "msg" -> "JSON decode error")))

  private def tooLarge: JsValue = DecisionErrors.gateway(413, "invalid_request_error", "the request body is larger than 32 MiB")

  private def invalid(message: String): JsValue = DecisionErrors.gateway(400, "invalid_request_error", message)

  private def header(ctx: NgbBackendCallContext, name: String): Option[String] =
    ctx.request.headers.collectFirst { case (k, v) if k.equalsIgnoreCase(name) => v }

  private def inflated(stream: => InputStream): Either[JsValue, ByteString] = Try {
    val in = stream
    try {
      val out = ByteString.newBuilder
      val buffer = new Array[Byte](64 * 1024)
      var total = 0L
      var read = in.read(buffer)
      while (read >= 0 && total <= maxSize) {
        out.putBytes(buffer, 0, read)
        total += read
        read = in.read(buffer)
      }
      if (total > maxSize) Left(tooLarge) else Right(out.result())
    } finally in.close()
  } match {
    case Success(result) => result
    case Failure(e) => Left(invalid(s"the request body could not be decompressed: ${e.getMessage}"))
  }

  // the first bytes of a gzip and of a zstd frame
  private val gzipMagic = ByteString(Array(0x1f, 0x8b).map(_.toByte))
  private val zstdMagic = ByteString(Array(0x28, 0xb5, 0x2f, 0xfd).map(_.toByte))

  /**
   * The body as it was written. Only bytes that are compressed are decompressed: a body a proxy already
   * decompressed, its header left behind, is read as the plain one it is.
   */
  def decoded(bytes: ByteString, encoding: Option[String]): Either[JsValue, ByteString] =
    encoding.toSeq.flatMap(_.split(",")).map(_.trim.toLowerCase).filter(e => e.nonEmpty && e != "identity") match {
      case Seq() => Right(bytes)
      case Seq("gzip" | "x-gzip") if !bytes.startsWith(gzipMagic) => Right(bytes)
      case Seq("gzip" | "x-gzip") => inflated(new GZIPInputStream(new ByteArrayInputStream(bytes.toArrayUnsafe())))
      case Seq("zstd") if !bytes.startsWith(zstdMagic) => Right(bytes)
      case Seq("zstd") => inflated(new ZstdInputStream(new ByteArrayInputStream(bytes.toArrayUnsafe())))
      case Seq(other) => Left(DecisionErrors.gateway(415, "invalid_request_error", s"the content encoding '${other}' is not supported, gzip and zstd are"))
      case _ => Left(DecisionErrors.gateway(415, "invalid_request_error", "a request body is compressed once at most"))
    }

  // the json of a multipart request (its `request` part), the assets given the bytes of their parts
  def assembled(parts: Seq[(String, String, ByteString)]): Either[JsValue, JsValue] = {
    if (parts.exists(_._1.trim.isEmpty) || parts.map(_._1).distinct.size != parts.size) Left(invalid("the parts of a multipart request have unique names"))
    else parts.find(_._1 == "request") match {
      case None => Left(invalid("the json of a multipart request is its part named 'request'"))
      case Some((_, _, json)) => Try(Json.parse(json.utf8String)).toOption.flatMap(_.asOpt[JsObject]) match {
        case None => Left(jsonInvalid)
        case Some(request) =>
          val files = parts.filterNot(_._1 == "request").map(p => p._1 -> p).toMap
          val assets = request.select("assets").asOpt[JsObject].getOrElse(Json.obj())
          val named = assets.fields.toSeq.flatMap { case (ref, asset) => asset.select("part").asOptString.map(ref -> _) }
          val unused = files.keySet -- named.map(_._2)
          if (named.map(_._2).distinct.size != named.size) Left(invalid("a part is the picture of one asset only"))
          else if (unused.nonEmpty) Left(invalid(s"the part '${unused.head}' is the picture of no asset"))
          else named.foldLeft[Either[JsValue, JsObject]](Right(assets)) {
            case (Right(acc), (ref, name)) => files.get(name) match {
              case None => Left(invalid(s"the part of the asset '${ref}' is not in the request"))
              case Some((_, mediaType, _)) if !partTypes.contains(mediaType) => Left(invalid(s"the part '${name}' is not a PNG or JPEG image (${mediaType})"))
              case Some((_, mediaType, data)) =>
                val base64 = Base64.getEncoder.encodeToString(data.toArrayUnsafe())
                val asset = acc.select(ref).as[JsObject]
                val format = asset.select("format").asOptString
                  .orElse(DecisionImages.formatOf(mediaType))
                  .orElse(DecisionImages.mediaTypeOf(base64).flatMap(DecisionImages.formatOf))
                Right(acc ++ Json.obj(ref -> ((asset - "part" - "format") ++ Json.obj("data_base64" -> base64) ++ format.map(f => Json.obj("format" -> f)).getOrElse(Json.obj()))))
            }
            case (left, _) => left
          }.map(resolved => if (named.isEmpty) request else request ++ Json.obj("assets" -> resolved))
      }
    }
  }

  private def multipart(contentType: String, bytes: ByteString)(using ec: ExecutionContext, mat: Materializer): Future[Either[JsValue, JsValue]] =
    ContentType.parse(contentType) match {
      case Left(_) => Left(invalid("the content type of the multipart request cannot be read")).vfuture
      case Right(ct) =>
        Unmarshal(HttpEntity(ct, bytes)).to[Multipart.FormData]
          .flatMap(_.parts.mapAsync(1)(part => part.entity.toStrict(30.seconds).map(e => (part.name, e.contentType.mediaType.value, e.data))).runWith(Sink.seq))
          .map(assembled)
          .recover { case e: Throwable => Left(invalid(s"the multipart request cannot be read: ${e.getMessage}")) }
    }

  def read(ctx: NgbBackendCallContext)(using ec: ExecutionContext, mat: Materializer): Future[Either[JsValue, JsValue]] = {
    ctx.request.body
      .limitWeighted(maxSize.toLong)(_.size.toLong)
      .runFold(ByteString.empty)(_ ++ _)
      .map(raw => decoded(raw, header(ctx, "Content-Encoding")))
      .recover { case _: StreamLimitReachedException => Left(tooLarge) }
      .flatMap {
        case Left(err) => Left(err).vfuture
        case Right(bytes) => header(ctx, "Content-Type").filter(_.toLowerCase.startsWith("multipart/")) match {
          case Some(contentType) => multipart(contentType, bytes)
          case None => Try(Json.parse(bytes.utf8String)) match {
            case Failure(_) => Left(jsonInvalid).vfuture
            case Success(json) => Right(json).vfuture
          }
        }
      }
  }
}

object DecisionModels {

  private def errorResult(status: Int, body: JsValue, retryAfter: Option[String] = None): Result = {
    val result = Results.Status(status)(body)
    retryAfter.map(v => result.withHeaders("Retry-After" -> v)).getOrElse(result)
  }

  /**
   * The http answer of a failed decision call. What a provider answered is given back as it is, status
   * included: a TypeSafe sdk reads it the way it reads the provider, and retries what can be retried. A
   * budget is a 402 rather than a 429, which a client would keep retrying against a budget that is not
   * going to come back in the next seconds.
   */
  def resultOf(err: JsValue): Result = DecisionErrors.classify(err) match {
    case DecisionErrors.Kind.Upstream(status, body: JsString, retryAfter) => errorResult(status, DecisionErrors.detail("api_error", body.value), retryAfter)
    case DecisionErrors.Kind.Upstream(status, body, retryAfter) => errorResult(status, body, retryAfter)
    case DecisionErrors.Kind.Budget(message) => errorResult(402, DecisionErrors.detail("budget_exceeded", message))
    case DecisionErrors.Kind.Denied => errorResult(403, DecisionErrors.detail("permission_error", "you can't use this model"))
    case DecisionErrors.Kind.NotBillable(message) => errorResult(403, DecisionErrors.detail("permission_error", message))
    case DecisionErrors.Kind.Other(error) => errorResult(500, Json.obj("detail" -> Json.obj("error_type" -> "api_error", "message" -> "the decision model failed to answer", "error" -> error)))
  }

  private def failed(err: JsValue): Either[NgProxyEngineError, BackendCallResponse] =
    Left(NgProxyEngineError.NgResultProxyEngineError(resultOf(err)))

  /**
   * A System One request, asked to the decision model of `refs` it is for. `openai` is the request it was made of,
   * if any, `systemOne` the one written with TypeSafe assets, and `dangerZone` the TypeSafe headers of the caller.
   */
  def decide(
    refs: Seq[String],
    body: JsObject,
    attrs: TypedMap,
    openai: Option[JsObject] = None,
    systemOne: Option[JsObject] = None,
    dangerZone: Map[String, String] = Map.empty,
  )(using env: Env, ec: ExecutionContext): Future[Either[JsValue, DecisionResponse]] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    DecisionModelsResolver.resolve(body, refs.flatMap(ref => ext.states.decisionModel(ref))) match {
      case None => DecisionErrors.gateway(500, "api_error", "no decision model is served here").leftf
      case Some(resolved) => resolved.entity.getDecisionModelClient() match {
        case None => DecisionErrors.gateway(500, "api_error", "failed to create the client of the decision model").leftf
        case Some(client) =>
          val options = DecisionModelClientInputOptions.format.reads(resolved.body).get.copy(openai = openai, systemOne = systemOne, dangerZone = dangerZone)
          client.decide(options, resolved.body, attrs).recover {
            // no answer at all, and nothing took over
            case e: TimeoutException => Left(DecisionErrors.gateway(504, "timeout_error", Option(e.getMessage).getOrElse("the decision model did not answer in time")))
            case e: Throwable => Left(DecisionErrors.gateway(502, "api_connection_error", Option(e.getMessage).getOrElse("the decision model could not be reached")))
          }
      }
    }
  }

  def handleRequest(config: DecisionModelsConfig, ctx: NgbBackendCallContext)(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    SystemOneBodies.read(ctx).flatMap {
      case Left(err) => failed(err).vfuture
      case Right(json) =>
        val issues = DecisionRequests.issues(json)
        if (issues.nonEmpty) {
          failed(DecisionErrors.invalid(issues)).vfuture
        } else {
          val request = json.asObject
          // read in the internal format, every provider takes it; TypeSafe is given the assets back as they were
          DecisionImages.fromAssets(request) match {
            case Left(err) => failed(err).vfuture
            case Right(body) =>
              val original = Some(request).filter(DecisionImages.hasAssets)
              decide(config.refs, body, ctx.attrs, systemOne = original, dangerZone = DecisionImages.dangerZoneOf(ctx.request.headers)).map {
                case Left(err) => failed(err)
                case Right(decision) => Right(BackendCallResponse.apply(NgPluginHttpResponse.fromResult(Results.Ok(decision.toJson(env))), None))
              }
          }
        }
    }
  }
}

class DecisionModels extends NgBackendCall {

  override def name: String = "Cloud APIM - Decision models backend"
  override def description: Option[String] = "Delegates call to a decision model (System One api): typed questions about a state, answered with probabilities".some
  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))
  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)
  override def useDelegates: Boolean = false
  override def defaultConfigObject: Option[NgPluginConfig] = Some(DecisionModelsConfig.default)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = DecisionModelsConfig.configFlow
  override def configSchema: Option[JsObject] = DecisionModelsConfig.configSchema

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'Decision models backend' plugin is available !")
    }
    ().vfuture
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(DecisionModelsConfig.format).getOrElse(DecisionModelsConfig.default)
    DecisionModels.handleRequest(config, ctx)
  }
}

/**
 * The decisions api of OpenAI, served by the same decision models: the request is read as a System One one
 * (`OpenAiDecisions`), and the answers, the usage and the errors are given back in the OpenAI format.
 */
object OpenAICompatDecisions {

  private def errorResult(status: Int, body: JsValue, retryAfter: Option[String] = None): Result = {
    val result = Results.Status(status)(body)
    retryAfter.map(v => result.withHeaders("Retry-After" -> v)).getOrElse(result)
  }

  /** The http answer of a failed decision call, with the statuses of `DecisionModels.resultOf` */
  def resultOf(err: JsValue, request: Option[OpenAiDecisions.Request] = None): Result = DecisionErrors.classify(err) match {
    case DecisionErrors.Kind.Upstream(status, body, retryAfter) => errorResult(status, OpenAiDecisions.errorOf(status, body, request), retryAfter)
    case DecisionErrors.Kind.Budget(message) => errorResult(402, OpenAiDecisions.error(message, None, "insufficient_quota", "budget_exceeded".some))
    case DecisionErrors.Kind.Denied => errorResult(403, OpenAiDecisions.error("you can't use this model", "model".some, "permission_error", "model_not_allowed".some))
    case DecisionErrors.Kind.NotBillable(message) => errorResult(403, OpenAiDecisions.error(message, "model".some, "permission_error", "model_not_allowed".some))
    case DecisionErrors.Kind.Other(_) => errorResult(500, OpenAiDecisions.error("the decision model failed to answer", None, "server_error"))
  }

  private def failed(result: Result): Either[NgProxyEngineError, BackendCallResponse] =
    Left(NgProxyEngineError.NgResultProxyEngineError(result))

  def handleRequest(config: DecisionModelsConfig, ctx: NgbBackendCallContext)(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
      val request = Try(Json.parse(bodyRaw.utf8String)) match {
        case Failure(_) => Left(OpenAiDecisions.error("We could not parse the JSON body of your request."))
        case Success(json) => OpenAiDecisions.read(json)
      }
      request match {
        case Left(error) => failed(Results.BadRequest(error)).vfuture
        case Right(request) =>
          DecisionModels.decide(config.refs, request.systemOne, ctx.attrs, request.raw.some).map {
            case Left(err) => failed(resultOf(err, request.some))
            case Right(decision) => Right(BackendCallResponse.apply(NgPluginHttpResponse.fromResult(Results.Ok(OpenAiDecisions.response(request, decision, env))), None))
          }
      }
    }
  }
}

class OpenAICompatDecisions extends NgBackendCall {

  override def name: String = "Cloud APIM - OpenAI decisions backend"
  override def description: Option[String] = "Delegates call to a decision model with the decisions api of OpenAI: typed questions about an input, answered with probabilities".some
  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))
  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)
  override def useDelegates: Boolean = false
  override def defaultConfigObject: Option[NgPluginConfig] = Some(DecisionModelsConfig.default)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = DecisionModelsConfig.configFlow
  override def configSchema: Option[JsObject] = DecisionModelsConfig.configSchema

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'OpenAI decisions backend' plugin is available !")
    }
    ().vfuture
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(DecisionModelsConfig.format).getOrElse(DecisionModelsConfig.default)
    OpenAICompatDecisions.handleRequest(config, ctx)
  }
}
