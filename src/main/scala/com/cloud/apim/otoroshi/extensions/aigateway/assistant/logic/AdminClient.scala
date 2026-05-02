package com.cloud.apim.otoroshi.extensions.aigateway.assistant.logic

import otoroshi.env.Env
import otoroshi.models.{ApiKey, BackOfficeUser}
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json._

import java.net.URI
import java.util.Base64
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

case class AdminCredentials(baseUrl: String, apikey: ApiKey) {
  def bearer: String = apikey.toBearer()
  def bearerHeader: String = "Bearer " + apikey.toBearer()
  def origin: String = new URI(baseUrl).resolve("/").toString.stripSuffix("/")
}

object AdminCredentials {

  val metadataKey: String = "otoroshi_assistant_admin_apikey_id"

  def fetch(env: Env, ext: AiExtension, user: Option[BackOfficeUser]): Option[AdminCredentials] = {
    for {
      provider <- ext.states.allProviders().find(_.isOtoroshiAssistant)
      apikeyId <- provider.metadata.get(metadataKey).map(_.trim).filter(_.nonEmpty).orElse(env.backOfficeApiKey.clientId.some)
      apikey <- env.proxyState.apikey(apikeyId)
    } yield AdminCredentials(
      baseUrl = s"${env.exposedRootScheme}://${env.adminApiExposedHost}${env.bestExposedPort}",
      apikey = apikey,
    )
  }
}

case class AdminResponse(status: Int, ok: Boolean, headers: Map[String, String], data: JsValue)

object AdminClient {

  private val forbiddenRequestHeaders: Set[String] = Set("authorization", "cookie", "host", "proxy-authorization")
  private val sensitiveResponseHeaders: Set[String] = Set("authorization", "proxy-authorization", "set-cookie", "www-authenticate", "proxy-authenticate")
  private val defaultRequestTimeout: FiniteDuration = 30.seconds

  case class CallOptions(
    pathParams: Map[String, String] = Map.empty,
    query: Map[String, String] = Map.empty,
    body: Option[JsValue] = None,
    headers: Map[String, String] = Map.empty,
  ) {
    def json: JsValue = Json.obj(
      "pathParams" -> pathParams,
      "queryParams" -> query,
      "body" -> body,
      "headers" -> headers,
    )
  }

  private def filterHeaders(h: Map[String, String]): Map[String, String] =
    h.filterNot { case (k, _) => forbiddenRequestHeaders.contains(k.toLowerCase) }

  private def interpolatePath(path: String, params: Map[String, String]): String = {
    """\{([^}]+)\}""".r.replaceAllIn(path, m => {
      val key = m.group(1)
      val value = params.getOrElse(key, throw new IllegalArgumentException(s"Missing path parameter '$key' for $path"))
      java.net.URLEncoder.encode(value, "UTF-8")
    })
  }

  private def buildQuery(q: Map[String, String]): String = {
    val parts = q.collect { case (k, v) if v != null => s"${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}" }
    if (parts.isEmpty) "" else parts.mkString("?", "&", "")
  }

  def request(creds: AdminCredentials, method: String, path: String, opts: CallOptions = CallOptions())(implicit ec: ExecutionContext, env: Env): Future[Either[String, AdminResponse]] = {
    val resolvedPath = try interpolatePath(path, opts.pathParams) catch { case e: IllegalArgumentException => return Left(e.getMessage).vfuture }
    if (resolvedPath.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) return Left(s"Refusing absolute URL in path: $resolvedPath").vfuture
    if (resolvedPath.startsWith("//")) return Left(s"Refusing protocol-relative path: $resolvedPath").vfuture
    val withSlash = if (resolvedPath.startsWith("/")) resolvedPath else s"/$resolvedPath"
    val finalUrl = s"${creds.baseUrl.stripSuffix("/")}$withSlash${buildQuery(opts.query)}"

    val targetOrigin = try {
      val u = new URI(finalUrl)
      s"${u.getScheme}://${u.getHost}${if (u.getPort > 0) s":${u.getPort}" else ""}"
    } catch { case _: Throwable => "" }
    if (targetOrigin != creds.origin) return Left(s"Refusing cross-origin URL $targetOrigin (expected ${creds.origin})").vfuture

    val userHeaders = filterHeaders(opts.headers)
    val baseHeaders = Map("Accept" -> "application/json") ++ userHeaders + ("Authorization" -> creds.bearerHeader)
    val httpMethod = method.toUpperCase
    val hasBody = opts.body.isDefined && httpMethod != "GET" && httpMethod != "HEAD"
    val finalHeaders = if (hasBody) baseHeaders + ("Content-Type" -> "application/json") else baseHeaders

    var builder = env.Ws.url(finalUrl)
      .withHttpHeaders(finalHeaders.toSeq: _*)
      .withMethod(httpMethod)
      .withRequestTimeout(defaultRequestTimeout)
      .withFollowRedirects(false)
    if (hasBody) builder = builder.withBody(opts.body.get)

    builder.execute().map { resp =>
      val respHeaders = resp.headers.collect {
        case (k, v) if !sensitiveResponseHeaders.contains(k.toLowerCase) => k -> v.headOption.getOrElse("")
      }
      val data: JsValue = {
        val ct = resp.contentType
        if (resp.status == 204 || resp.body.isEmpty) JsNull
        else if (ct.contains("application/json")) {
          scala.util.Try(Json.parse(resp.body)).getOrElse(JsString(resp.body))
        } else JsString(resp.body)
      }
      Right(AdminResponse(resp.status, resp.status >= 200 && resp.status < 300, respHeaders, data))
    }.recover { case t: Throwable => Left(s"http call failed: ${t.getMessage}") }
  }

  def call(creds: AdminCredentials, catalog: Catalog.Document, operationId: String, opts: CallOptions = CallOptions())(implicit ec: ExecutionContext, env: Env): Future[Either[String, AdminResponse]] = {
    catalog.byOperationId.get(operationId) match {
      case None => Left(s"Unknown operationId '$operationId'. Use the 'search' tool to discover operations.").vfuture
      case Some(op) => request(creds, op.method, op.path, opts)
    }
  }
}
