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
import scala.util.control.NonFatal

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
      val config = MarkdownAcceptConfig(
        cachingEnabled = (json \ "cachingEnabled").asOpt[Boolean].getOrElse(true),
        maxCacheSize = (json \ "maxCacheSize").asOpt[Int].getOrElse(500),
        cacheTtlMinutes = (json \ "cacheTtlMinutes").asOpt[Int].getOrElse(30)
      )
      require(config.maxCacheSize > 0, "maxCacheSize must be positive")
      require(config.cacheTtlMinutes > 0, "cacheTtlMinutes must be positive")
      config
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
    "Support for llms.txt standard - proxies requests with Accept: text/markdown header to .md, .html.md, or /index.html.md variants".some
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

    // Parse Accept header to check for text/markdown media type
    val acceptsMarkdown = acceptHeader.exists { header =>
      header.split(',').exists { mediaType =>
        mediaType.trim.split(';').head.trim == "text/markdown"
      }
    }

    if (!acceptsMarkdown) {
      delegates()
    } else {
      val originalPath = ctx.request.path
      // Use URL-safe separator to avoid collision (route IDs don't contain ||| )
      val cacheKey = s"${ctx.route.id}|||$originalPath"

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
    if (path.isEmpty || path == "/") {
      Seq(
        "/index.md",
        "/index.html.md"
      )
    } else if (path.endsWith("/")) {
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

  private def buildVariantRequest(
    ctx: NgbBackendCallContext,
    variantPath: String
  )(implicit env: Env): play.api.libs.ws.WSRequest = {
    val url = s"${ctx.backend.baseUrl}$variantPath"
    val headers = ctx.request.headers.toSeq

    env.Ws.url(url)
      .withHttpHeaders(headers: _*)
      .withMethod(ctx.request.method)
      .withRequestTimeout(ctx.route.backend.client.callTimeout.millis)
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
    buildVariantRequest(ctx, variantPath)
      .stream()
      .map { response =>
        Right(createStreamedResponse(response.status, response))
      }
      .recover {
        case NonFatal(e) =>
          env.logger.warn(s"[Cloud APIM - LLMs.txt Accept Markdown] Failed to fetch variant $variantPath: ${e.getMessage}")
          Left(NgProxyEngineError.NgResultProxyEngineError(Results.BadGateway(Json.obj(
            "error" -> "failed to fetch markdown variant",
            "variant" -> variantPath
          ))))
      }
  }

  private def tryVariantsParallel(
    ctx: NgbBackendCallContext,
    delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]],
    variants: Seq[String],
    cacheInfoOpt: Option[(Cache[String, Option[String]], String)]
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {

    // Check if we have a recent server error cached
    // Use cache key from cacheInfoOpt or build it for error cache lookup
    val errorCacheKey = cacheInfoOpt.map(_._2).getOrElse(s"${ctx.route.id}|||${ctx.request.path}")
    if (MarkdownAcceptPlugin.errorCache.getIfPresent(errorCacheKey).isDefined) {
      env.logger.debug(s"[Cloud APIM - LLMs.txt Accept Markdown] Server error cached for $errorCacheKey, using delegates")
      return delegates()
    }

    // Try each variant in parallel
    def tryVariant(variant: String): Future[Option[(String, play.api.libs.ws.WSResponse)]] = {
      buildVariantRequest(ctx, variant)
        .stream()
        .flatMap { response =>
          response.status match {
            case 200 =>
              // Success: return response (body will be streamed to client)
              Future.successful(Some((variant, response)))
            case 404 =>
              // Not found: drain body as we won't use this response
              response.bodyAsSource.runWith(Sink.ignore).map(_ => None)
            case _ =>
              // Other status codes: return response (body will be streamed to client)
              Future.successful(Some((variant, response)))
          }
        }
        .recover {
          case NonFatal(e) =>
            env.logger.debug(s"[Cloud APIM - LLMs.txt Accept Markdown] Failed to fetch $variant: ${e.getMessage}")
            None
        }
    }

    // Launch all variants in parallel and wait for all to complete
    val futureResults = variants.map(tryVariant)

    // Wait for all variants to complete, then pick the first successful one based on priority order
    // Note: All requests complete before selection to avoid thundering herd on cache miss
    Future.sequence(futureResults).flatMap { results =>
      // Extract all successful responses
      val successfulResults = results.flatten

      successfulResults.headOption match {
        case Some((variant, response)) =>
          // Drain all other responses to avoid resource leak
          val otherResponses = successfulResults.tail.map(_._2)
          otherResponses.foreach { resp =>
            resp.bodyAsSource.runWith(Sink.ignore)
          }

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
