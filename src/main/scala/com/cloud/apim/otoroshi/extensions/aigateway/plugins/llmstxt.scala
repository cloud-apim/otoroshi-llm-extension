package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import com.github.blemale.scaffeine.{Cache, Scaffeine}
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json._
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

case class MarkdownAcceptConfig(
  cachingEnabled: Boolean = true,
  maxCacheSize: Int = 500,
  cacheTtlMinutes: Int = 30
) extends NgPluginConfig {
  def json: JsValue = MarkdownAcceptConfig.format.writes(this)
}

object MarkdownAcceptConfig {
  val default: MarkdownAcceptConfig = MarkdownAcceptConfig(
    cachingEnabled = true,
    maxCacheSize = 500,
    cacheTtlMinutes = 30
  )

  val format = new Format[MarkdownAcceptConfig] {
    override def writes(o: MarkdownAcceptConfig): JsValue = Json.obj(
      "cachingEnabled" -> o.cachingEnabled,
      "maxCacheSize" -> o.maxCacheSize,
      "cacheTtlMinutes" -> o.cacheTtlMinutes
    )

    override def reads(json: JsValue): JsResult[MarkdownAcceptConfig] = Try {
      MarkdownAcceptConfig(
        cachingEnabled = (json \ "cachingEnabled").asOpt[Boolean].getOrElse(true),
        maxCacheSize = (json \ "maxCacheSize").asOpt[Int].getOrElse(500),
        cacheTtlMinutes = (json \ "cacheTtlMinutes").asOpt[Int].getOrElse(30)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(c) => JsSuccess(c)
    }
  }

  val configFlow: Seq[String] = Seq("cachingEnabled", "maxCacheSize", "cacheTtlMinutes")

  val configSchema: Option[JsObject] = Some(Json.obj(
    "cachingEnabled" -> Json.obj(
      "type" -> "bool",
      "label" -> "Caching",
      "help" -> "Enable or disable variant path caching"
    ),
    "maxCacheSize" -> Json.obj(
      "type" -> "number",
      "label" -> "Cache size",
      "help" -> "Maximum number of cached variant paths"
    ),
    "cacheTtlMinutes" -> Json.obj(
      "type" -> "number",
      "label" -> "TTL (minutes)",
      "help" -> "Time-to-live for cached variant paths in minutes"
    )
  ))
}

object MarkdownAcceptPlugin {
  // Cache factory: creates a cache per configuration for proper TTL and size management
  // Uses the config object itself as key to avoid hash collisions
  private val caches = new java.util.concurrent.ConcurrentHashMap[MarkdownAcceptConfig, Cache[String, Option[String]]]()

  def getOrCreateCache(config: MarkdownAcceptConfig): Cache[String, Option[String]] = {
    caches.computeIfAbsent(config, _ =>
      Scaffeine()
        .expireAfterWrite(config.cacheTtlMinutes.minutes)
        .maximumSize(config.maxCacheSize)
        .build[String, Option[String]]()
    )
  }

  // Separate cache for server errors (5xx) with shorter TTL (1 minute)
  private val errorCache = Scaffeine()
    .expireAfterWrite(1.minute)
    .maximumSize(1000)
    .build[String, Boolean]()
}

class MarkdownAcceptPlugin extends NgBackendCall {

  override def useDelegates: Boolean = true
  override def multiInstance: Boolean = true
  override def core: Boolean = false
  override def name: String = "Cloud APIM - LLMs.txt Accept Markdown"
  override def description: Option[String] =
    "Support for llms.txt standard - redirects requests with Accept: text/markdown header to .md, .html.md, or /index.html.md variants".some
  override def defaultConfigObject: Option[NgPluginConfig] = MarkdownAcceptConfig.default.some
  override def noJsForm: Boolean = true

  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))
  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)

  override def configFlow: Seq[String] = MarkdownAcceptConfig.configFlow
  override def configSchema: Option[JsObject] = MarkdownAcceptConfig.configSchema

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("[Cloud APIM - LLMs.txt Accept Markdown] plugin is now available!")
    }
    ().vfuture
  }

  override def callBackend(
    ctx: NgbBackendCallContext,
    delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]]
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {

    val config = ctx.cachedConfig(internalName)(MarkdownAcceptConfig.format).getOrElse(MarkdownAcceptConfig.default)
    val acceptHeader = ctx.request.headers.get("Accept")

    if (!acceptHeader.exists(_.contains("text/markdown"))) {
      delegates()
    } else {
      val originalPath = ctx.request.path
      val cacheKey = s"${ctx.route.id}:$originalPath"

      if (config.cachingEnabled) {
        val cache = MarkdownAcceptPlugin.getOrCreateCache(config)
        cache.getIfPresent(cacheKey) match {
          case Some(Some(cachedVariantPath)) =>
            env.logger.debug(s"[Cloud APIM - LLMs.txt Accept Markdown] Cache hit for $originalPath -> $cachedVariantPath")
            fetchVariant(ctx, cachedVariantPath)
          case Some(None) =>
            env.logger.debug(s"[Cloud APIM - LLMs.txt Accept Markdown] Cache hit for $originalPath -> no variant")
            delegates()
          case None =>
            env.logger.debug(s"[Cloud APIM - LLMs.txt Accept Markdown] Cache miss for $originalPath")
            val variants = generatePathVariants(originalPath)
            tryVariantsParallel(ctx, delegates, variants, Some((cache, cacheKey)))
        }
      } else {
        val variants = generatePathVariants(originalPath)
        tryVariantsParallel(ctx, delegates, variants, None)
      }
    }
  }

  private def generatePathVariants(path: String): Seq[String] = {
    if (path.endsWith("/")) {
      // Path ends with /, try variations
      Seq(
        s"${path}index.md",
        s"${path}index.html.md",
        s"${path.dropRight(1)}.md",
        s"${path.dropRight(1)}.html.md"
      )
    } else {
      // Regular path
      Seq(
        s"$path.md",
        s"$path.html.md",
        s"$path/index.html.md"
      )
    }
  }

  private def createStreamedResponse(
    status: Int,
    response: play.api.libs.ws.WSResponse
  ): BackendCallResponse = {
    val contentLength = response.header("Content-Length").map(_.toLong)
    val result = Results.Status(status).sendEntity(
      play.api.http.HttpEntity.Streamed(
        response.bodyAsSource,
        contentLength,
        response.contentType.some
      )
    ).withHeaders(response.headers.flatMap { case (k, vs) => vs.map(v => (k, v)) }.toSeq: _*)

    BackendCallResponse(NgPluginHttpResponse.fromResult(result), None)
  }

  private def fetchVariant(
    ctx: NgbBackendCallContext,
    variantPath: String
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val url = s"${ctx.backend.baseUrl}$variantPath"
    val headers = ctx.request.headers.toSeq

    env.Ws.url(url)
      .withHttpHeaders(headers: _*)
      .withMethod(ctx.request.method)
      .withRequestTimeout(ctx.route.backend.client.callTimeout.millis)
      .stream()
      .map { response =>
        Right(createStreamedResponse(response.status, response))
      }
  }

  private def tryVariantsParallel(
    ctx: NgbBackendCallContext,
    delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]],
    variants: Seq[String],
    cacheInfoOpt: Option[(Cache[String, Option[String]], String)]
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {

    val cacheKey = cacheInfoOpt.map(_._2).getOrElse("")

    // Check if we have a recent server error cached
    if (MarkdownAcceptPlugin.errorCache.getIfPresent(cacheKey).isDefined) {
      env.logger.debug(s"[Cloud APIM - LLMs.txt Accept Markdown] Server error cached for $cacheKey, using delegates")
      return delegates()
    }

    // Try each variant in parallel
    def tryVariant(variant: String): Future[Option[(String, play.api.libs.ws.WSResponse)]] = {
      val url = s"${ctx.backend.baseUrl}$variant"
      val headers = ctx.request.headers.toSeq

      env.Ws.url(url)
        .withHttpHeaders(headers: _*)
        .withMethod(ctx.request.method)
        .withRequestTimeout(ctx.route.backend.client.callTimeout.millis)
        .stream()
        .map { response =>
          response.status match {
            case 200 => Some((variant, response))
            case 404 =>
              // Note: We don't await the stream consumption here for performance reasons.
              // The stream will be consumed in the background and resources will be released.
              // This is acceptable as 404 responses are typically small and fast to consume.
              response.bodyAsSource.runWith(Sink.ignore)
              None
            case _ => Some((variant, response))
          }
        }
        .recover {
          case e: Exception =>
            env.logger.debug(s"[Cloud APIM - LLMs.txt Accept Markdown] Failed to fetch $variant: ${e.getMessage}")
            None
        }
    }

    // Launch all variants in parallel and wait for all to complete
    val futureResults = variants.map(tryVariant)

    // Use Future.sequence to wait for all results, then pick the first successful one in order
    // This ensures we respect the priority order defined in the variants list
    Future.sequence(futureResults).flatMap { results =>
      results.collectFirst { case Some(result) => result } match {
        case Some((variant, response)) =>
          cacheInfoOpt.foreach { case (cache, cacheKey) =>
            if (response.status == 200) {
              cache.put(cacheKey, Some(variant))
            } else if (response.status >= 500) {
              // Cache server errors to avoid hammering a failing backend
              MarkdownAcceptPlugin.errorCache.put(cacheKey, true)
            }
          }
          Future.successful(Right(createStreamedResponse(response.status, response)))

        case None =>
          cacheInfoOpt.foreach { case (cache, cacheKey) =>
            cache.put(cacheKey, None)
          }
          delegates()
      }
    }
  }
}
