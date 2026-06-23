package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import akka.actor.{Actor, ActorRef, PoisonPill, Props}
import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.{Flow, Sink, Source, SourceQueueWithComplete}
import akka.stream.{Materializer, OverflowStrategy}
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.ChatMessage
import com.cloud.apim.otoroshi.extensions.aigateway.agents.InlineFunction
import com.cloud.apim.otoroshi.extensions.aigateway.decorators.{GuardrailItem, GuardrailResult, Guardrails}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{LlmToolFunction, McpConnector, McpConnectorRules, McpConnectorTransport, McpConnectorTransportKind, McpRegistryConfig, McpSupport, McpVirtualServer}
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.mcp.client.{McpBlobResourceContents, McpResourceContents, McpTextResourceContents}
import dev.langchain4j.model.chat.request.json.JsonSchemaElement
import otoroshi.auth.OAuth2ModuleConfig
import otoroshi.env.Env
import otoroshi.events.AuditEvent
import otoroshi.models.{InHeader, InQueryParam, JWKSAlgoSettings, JwtTokenLocation, LocalJwtVerifier}
import otoroshi.next.plugins.{OIDCAuthToken, OIDCAuthTokenConfig, OIDCJwtVerifierConfig}
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.next.workflow.WorkflowRun
import otoroshi.security.IdGenerator
import otoroshi.utils.TypedMap
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.http.websocket.Message
import play.api.libs.json._
import play.api.libs.streams.ActorFlow
import play.api.libs.ws.DefaultBodyWritables.writeableOf_urlEncodedSimpleForm
import play.api.libs.typedmap.TypedKey
import play.api.mvc.{Result, Results}

import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

object McpOAuthFilterUtils {

  val McpUserAuthTokenKey = TypedKey[String]("cloud-apim.ai-gateway.mcp.McpUserAuthToken")
  val McpGrantedScopesKey = TypedKey[Set[String]]("cloud-apim.ai-gateway.mcp.McpGrantedScopes")
  val sources = Seq(InHeader("Authorization", "Bearer "), InQueryParam("access_token"))

  // Extract granted OAuth scopes from token/introspection claims: `scope` (space-delimited string) and/or
  // `scp` (array). Used for scope→tool authorization (see McpProxyEndpointConfig.toolAllowedForScopes).
  def scopesFromClaims(claims: JsValue): Set[String] = {
    val fromScope = claims.select("scope").asOpt[String].map(_.split("\\s+").toSeq).getOrElse(Seq.empty)
    val fromScp   = claims.select("scp").asOpt[Seq[String]].getOrElse(Seq.empty)
    (fromScope ++ fromScp).map(_.trim).filter(_.nonEmpty).toSet
  }

  def scopesFromJwt(token: String): Set[String] = Try {
    val parts = token.split("\\.")
    if (parts.length < 2) Set.empty[String] else scopesFromClaims(parts(1).decodeBase64.parseJson)
  }.getOrElse(Set.empty)

  def unauthorizedResult(ctx: NgAccessContext, config: McpProxyEndpointConfig, oidcModule: OAuth2ModuleConfig)(implicit env: Env, ec: ExecutionContext): Result = {
    val realmName = oidcModule.cookieSuffix(ctx.route.legacy)
    val authPrmUrl = config.authPrmUrl.getOrElse(s"${ctx.request.theProtocol}://${ctx.request.theHost}/.well-known/oauth-protected-resource")
    Results.Status(401)(Json.obj("error" -> "unauthorized"))
      .withHeaders("WWW-Authenticate"-> s"""Bearer realm="${realmName}", resource_metadata="${authPrmUrl}"""")
      .as("application/json")
  }

  // RFC 7662 token introspection, implemented locally so it works against the published Otoroshi
  // 17.11.0 (no dependency on the core `introspectTokenSafe`). Mirrors the legacy core introspection.
  // TODO(otoroshi with introspectTokenSafe published): delete this and call oidcModule.introspectTokenSafe.
  def introspectToken(oidcModule: OAuth2ModuleConfig, token: String)(implicit env: Env, ec: ExecutionContext): Future[Option[JsValue]] = {
    val clientSecret = Option(oidcModule.clientSecret).filterNot(_.trim.isEmpty)
    val builder      = env.MtlsWs.url(oidcModule.introspectionUrl, oidcModule.mtlsConfig)
    val future       = if (oidcModule.useJson) {
      builder.post(
        Json.obj("token" -> token, "client_id" -> oidcModule.clientId) ++
          clientSecret.map(s => Json.obj("client_secret" -> s)).getOrElse(Json.obj())
      )
    } else {
      builder.post(
        Map("token" -> token, "client_id" -> oidcModule.clientId) ++
          clientSecret.toSeq.map(s => ("client_secret" -> s))
      )(writeableOf_urlEncodedSimpleForm)
    }
    future
      .map(resp => if (resp.status == 200 && (resp.json \ "active").asOpt[Boolean].getOrElse(false)) Some(resp.json) else None)
      .recover { case _ => None }
  }

  // Audience binding (RFC 8707): the token `aud` claim (string OR array) must match this MCP server's
  // URL, mirroring the core getSession check but array-aware. Used locally until the core patch ships.
  // TODO(otoroshi with array-aware getSession published): remove and rely on getSession validateAudience.
  def audienceMatches(token: String, ctx: NgAccessContext)(implicit env: Env): Boolean = {
    Try {
      val parts = token.split("\\.")
      if (parts.length < 2) {
        false
      } else {
        val profile                = parts(1).decodeBase64.parseJson
        val audiences: Seq[String] = profile
          .select("aud")
          .asOpt[String]
          .map(a => Seq(a))
          .orElse(profile.select("aud").asOpt[Seq[String]])
          .getOrElse(Seq.empty)
          .map(_.trim)
          .filter(_.nonEmpty)
        val currentUrl             = s"${ctx.request.theProtocol}://${ctx.request.theDomain}${ctx.request.thePath}"
        audiences.exists(aud => currentUrl.startsWith(aud))
      }
    }.getOrElse(false)
  }

  def access(ctx: NgAccessContext, internalName: String)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config = ctx.cachedConfig(internalName)(McpProxyEndpointConfig.format).getOrElse(McpProxyEndpointConfig.default).resolve()
    config.authModuleRef match {
      case None if !config.enforceOAuth => NgAccess.NgAllowed.vfuture
      case None if config.enforceOAuth  => NgAccess.NgDenied(Results.BadRequest(Json.obj("error" -> "no auth. module setup"))).vfuture
      case Some(_) if !config.enforceOAuth => NgAccess.NgAllowed.vfuture
      case Some(authModuleId) if config.enforceOAuth =>
        env.proxyState.authModule(authModuleId) match {
          case None    => NgAccess.NgDenied(Results.BadRequest(Json.obj("error" -> "auth. module not found"))).vfuture
          case Some(m) =>
            m match {
              case oidcModule: OAuth2ModuleConfig if config.opaqueToken => {
                // opaque (non-JWT) access token: validate remotely via the IdP (userinfo endpoint),
                // no local JWT signature verification. JWT-based audience binding does not apply here.
                val customResult = unauthorizedResult(ctx, config, oidcModule)
                sources.iterator.map(s => s.token(ctx.request).map(t => (s, t))).collectFirst { case Some(tuple) =>
                  tuple
                } match {
                  case None             =>
                    NgAccess.NgDenied(customResult).vfuture
                  case Some((_, token)) =>
                    ctx.attrs.put(McpUserAuthTokenKey -> token)
                    if (config.useIntrospection) {
                      // RFC 7662 introspection, self-contained so it runs on stock Otoroshi 17.11.0.
                      // TODO(otoroshi with introspectTokenSafe published): replace this block by delegating
                      //   to core — add `useIntrospection = true` to OIDCAuthTokenConfig in the userinfo
                      //   branch below and reuse it (getSession then routes to introspection).
                      introspectToken(oidcModule, token).map {
                        case Some(resp) =>
                          ctx.attrs.put(McpGrantedScopesKey -> scopesFromClaims(resp))
                          NgAccess.NgAllowed
                        case None       => NgAccess.NgDenied(customResult)
                      }
                    } else {
                      // opaque token validated via the IdP userinfo endpoint (works on stock Otoroshi)
                      OIDCAuthToken
                        .getSession(
                          ctx,
                          oidcModule,
                          OIDCAuthTokenConfig(
                            ref = authModuleId,
                            opaque = true,
                            fetchUserProfile = true,
                            validateAudience = false,
                            headerName = "Authorization"
                          ),
                          Some(token)
                        )
                        .map {
                          case Left(result) => NgAccess.NgDenied(customResult)
                          case Right(r)     => r
                        }
                    }
                }
              }
              case oidcModule: OAuth2ModuleConfig if oidcModule.jwtVerifier.isDefined => {
                val customResult = unauthorizedResult(ctx, config, oidcModule)
                val verifier     = LocalJwtVerifier()
                  .copy(
                    enabled = true,
                    algoSettings = oidcModule.jwtVerifier.get
                  )
                sources.iterator.map(s => s.token(ctx.request).map(t => (s, t))).collectFirst { case Some(tuple) =>
                  tuple
                } match {
                  case None                  =>
                    NgAccess
                      .NgDenied(customResult)
                      .vfuture
                  case Some((source, token)) =>
                    verifier
                      .copy(source = source)
                      .verifyGen[NgAccess](
                        ctx.request,
                        ctx.route.legacy,
                        ctx.apikey,
                        ctx.user,
                        ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty),
                        ctx.attrs
                      ) { t =>
                        ctx.attrs.put(McpUserAuthTokenKey -> token)
                        ctx.attrs.put(McpGrantedScopesKey -> scopesFromJwt(token))
                        // Audience binding (RFC 8707) done locally so it accepts `aud` as a string OR an
                        // array on stock Otoroshi 17.11.0 (core getSession only checks a single-string aud).
                        // TODO(otoroshi with array-aware getSession published): drop the audienceMatches
                        //   check and pass `validateAudience = config.validateAudience` to getSession below.
                        if (config.validateAudience && !audienceMatches(token, ctx)) {
                          Future.successful[Either[Result, NgAccess]](Left(customResult))
                        } else {
                          OIDCAuthToken.getSession(
                            ctx,
                            oidcModule,
                            OIDCAuthTokenConfig(
                              ref = authModuleId,
                              opaque = false,
                              fetchUserProfile = true,
                              validateAudience = false,
                              headerName = "Authorization"
                            ),
                            Some(token)
                          )
                        }
                      }
                      .map {
                        case Left(result) => NgAccess.NgDenied(customResult)
                        case Right(r)     => r
                      }
                }
              }
              case _                                                                  =>
                NgAccess
                  .NgDenied(
                    Results.BadRequest(
                      Json.obj("error" -> "auth. module not an oidc module or does not have jwt verification settings")
                    )
                  )
                  .vfuture
            }
        }
    }
  }
}

// A resource served directly by an MCP virtual server (as opposed to one aggregated from an upstream
// McpConnector). The content is either inline (text or base64 blob) or fetched on the fly from a URL at
// resources/read time. url/headers/text are EL-evaluated per request, and {input_token} can be injected when
// forwardAuth is set (same convention as McpConnector headers).
case class McpStaticResource(
  uri: String,
  name: String,
  title: Option[String] = None,
  description: Option[String] = None,
  mimeType: Option[String] = None,
  annotations: Option[JsObject] = None, // MCP `annotations`
  meta: Option[JsObject] = None,        // MCP `_meta`
  // content source, by priority: url > blob > text
  text: Option[String] = None,          // inline text content
  blob: Option[String] = None,          // inline binary content (base64)
  url: Option[String] = None,           // content fetched on the fly
  urlAs: String = "text",               // "text" | "blob": how to return the fetched bytes
  headers: Map[String, String] = Map.empty,
  forwardAuth: Boolean = false,         // inject {input_token} into url/headers
  timeout: FiniteDuration = 30.seconds,
) {
  def json: JsValue = McpStaticResource.format.writes(this)

  // entry returned by resources/list (optional fields omitted when absent, per MCP spec)
  def listJson: JsObject = Json.obj("uri" -> uri, "name" -> name) ++
    title.map(t => Json.obj("title" -> t)).getOrElse(Json.obj()) ++
    description.map(d => Json.obj("description" -> d)).getOrElse(Json.obj()) ++
    mimeType.map(m => Json.obj("mimeType" -> m)).getOrElse(Json.obj()) ++
    annotations.map(a => Json.obj("annotations" -> a)).getOrElse(Json.obj()) ++
    meta.map(m => Json.obj("_meta" -> m)).getOrElse(Json.obj())

  private def metaJson: JsObject = meta.map(m => Json.obj("_meta" -> m)).getOrElse(Json.obj())
  private def withMime(o: JsObject, contentType: Option[String]): JsObject =
    mimeType.orElse(contentType.filter(_.nonEmpty)).map(m => o ++ Json.obj("mimeType" -> m)).getOrElse(o)

  private def hostAllowed(u: String, allowedHosts: Seq[String]): Boolean = {
    if (allowedHosts.isEmpty) true else {
      val host = Try(Uri(u).authority.host.address()).getOrElse("")
      allowedHosts.exists(p => otoroshi.utils.RegexPool.apply(p).matches(host))
    }
  }

  // contents entry returned by resources/read for this resource (a single content block).
  // emitAudit gates the McpResourceFetchAudit event for the outbound url fetch (metrics are always recorded).
  def read(attrs: TypedMap, allowedHosts: Seq[String], emitAudit: Boolean = false)(implicit env: Env, ec: ExecutionContext): Future[JsObject] = {
    val base = Json.obj("uri" -> uri)
    url match {
      case Some(rawUrl) =>
        val inputToken: String = if (forwardAuth) {
          attrs.get(McpOAuthFilterUtils.McpUserAuthTokenKey)
            .orElse(attrs.get(otoroshi.plugins.Keys.RequestKey).flatMap(_.headers.get("Authorization")).map(_.replaceFirst("Bearer ", "")))
            .getOrElse("--")
        } else "--"
        def render(s: String): String = {
          val ev = s.evaluateEl(attrs)
          if (forwardAuth) ev.replace("{input_token}", inputToken) else ev
        }
        val finalUrl = render(rawUrl)
        val finalHeaders = headers.mapValues(render).toSeq
        if (!hostAllowed(finalUrl, allowedHosts)) {
          McpStaticResource.markFetchMetrics(0L, isError = true)
          if (emitAudit) McpStaticResource.emitFetch(uri, finalUrl, 0L, None, Some("host not allowed"), attrs)
          Future.successful(withMime(base, None) ++ Json.obj("text" -> s"fetching resource is not allowed for this host") ++ metaJson)
        } else {
          val start = System.currentTimeMillis()
          env.Ws.url(finalUrl).withHttpHeaders(finalHeaders: _*).withRequestTimeout(timeout).get().map { resp =>
            val dur = System.currentTimeMillis() - start
            val isErr = resp.status >= 400
            McpStaticResource.markFetchMetrics(dur, isErr)
            if (emitAudit) McpStaticResource.emitFetch(uri, finalUrl, dur, Some(resp.status), if (isErr) Some(s"status ${resp.status}") else None, attrs)
            val ct = Option(resp.contentType)
            if (urlAs == "blob") withMime(base, ct) ++ Json.obj("blob" -> resp.bodyAsBytes.encodeBase64.utf8String) ++ metaJson
            else withMime(base, ct) ++ Json.obj("text" -> resp.body) ++ metaJson
          }.recover { case e =>
            val dur = System.currentTimeMillis() - start
            McpStaticResource.markFetchMetrics(dur, isError = true)
            if (emitAudit) McpStaticResource.emitFetch(uri, finalUrl, dur, None, Some(e.getMessage), attrs)
            withMime(base, None) ++ Json.obj("text" -> s"error fetching resource: ${e.getMessage}") ++ metaJson
          }
        }
      case None => blob match {
        case Some(b) => Future.successful(withMime(base, None) ++ Json.obj("blob" -> b) ++ metaJson)
        case None =>
          val rendered: String = text.map(_.evaluateEl(attrs)).getOrElse("")
          Future.successful(withMime(base, None) ++ Json.obj("text" -> rendered) ++ metaJson)
      }
    }
  }
}

object McpStaticResource {

  // Real-time metrics for the outbound fetch of a managed resource served from a `url` (always on when env
  // metrics are enabled, independent of the audit flag). Namespaced apart from mcp.client.* (connectors).
  def markFetchMetrics(durationMs: Long, isError: Boolean)(implicit env: Env): Unit = {
    if (!env.metricsEnabled) return
    env.metrics.counterInc("mcp.resource.fetch.calls")
    if (isError) env.metrics.counterInc("mcp.resource.fetch.errors")
    env.metrics.timerUpdate("mcp.resource.fetch.duration", durationMs, java.util.concurrent.TimeUnit.MILLISECONDS)
  }

  // Audit event for the outbound fetch of a managed resource (mirrors McpClientAudit but for the env.Ws fetch
  // that does not go through an McpConnector). Gated by the virtual server's emit_audit_events flag.
  def emitFetch(uri: String, url: String, duration: Long, httpStatus: Option[Int], error: Option[String], attrs: TypedMap)(implicit env: Env): Unit = {
    val user = attrs.get(otoroshi.plugins.Keys.UserKey)
    val apikey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey)
    val route = attrs.get(otoroshi.next.plugins.Keys.RouteKey)
    val requestId = attrs.get(otoroshi.plugins.Keys.SnowFlakeKey)
    AuditEvent.generic("McpResourceFetchAudit") {
      Json.obj(
        "request_id" -> requestId.map(JsString.apply).getOrElse(JsNull).asValue,
        "resource_uri" -> uri,
        "fetch_url" -> url,
        "http_status" -> httpStatus.map(s => JsNumber(BigDecimal(s))).getOrElse(JsNull).asValue,
        "duration" -> duration,
        "status" -> (if (error.isEmpty) "success" else "error"),
        "error" -> error.map(_.json).getOrElse(JsNull).asValue,
        "user" -> user.map(_.json).getOrElse(JsNull).asValue,
        "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
        "route" -> route.map(_.json).getOrElse(JsNull).asValue,
      )
    }.toAnalytics()
  }

  val format = new Format[McpStaticResource] {
    override def writes(o: McpStaticResource): JsValue = Json.obj(
      "uri" -> o.uri,
      "name" -> o.name,
      "title" -> o.title.map(_.json).getOrElse(JsNull).asValue,
      "description" -> o.description.map(_.json).getOrElse(JsNull).asValue,
      "mime_type" -> o.mimeType.map(_.json).getOrElse(JsNull).asValue,
      "annotations" -> o.annotations.getOrElse[JsValue](JsNull),
      "meta" -> o.meta.getOrElse[JsValue](JsNull),
      "text" -> o.text.map(_.json).getOrElse(JsNull).asValue,
      "blob" -> o.blob.map(_.json).getOrElse(JsNull).asValue,
      "url" -> o.url.map(_.json).getOrElse(JsNull).asValue,
      "url_as" -> o.urlAs,
      "headers" -> o.headers,
      "forward_auth" -> o.forwardAuth,
      "timeout" -> o.timeout.toMillis,
    )
    override def reads(json: JsValue): JsResult[McpStaticResource] = Try {
      McpStaticResource(
        uri = (json \ "uri").as[String],
        name = (json \ "name").asOpt[String].filter(_.trim.nonEmpty).getOrElse((json \ "uri").as[String]),
        title = (json \ "title").asOpt[String].filter(_.trim.nonEmpty),
        description = (json \ "description").asOpt[String].filter(_.trim.nonEmpty),
        mimeType = (json \ "mime_type").asOpt[String].filter(_.trim.nonEmpty),
        annotations = (json \ "annotations").asOpt[JsObject],
        meta = (json \ "meta").asOpt[JsObject].orElse((json \ "_meta").asOpt[JsObject]),
        text = (json \ "text").asOpt[String],
        blob = (json \ "blob").asOpt[String].filter(_.trim.nonEmpty),
        url = (json \ "url").asOpt[String].filter(_.trim.nonEmpty),
        urlAs = (json \ "url_as").asOpt[String].filter(_.trim.nonEmpty).getOrElse("text"),
        headers = (json \ "headers").asOpt[Map[String, String]].getOrElse(Map.empty),
        forwardAuth = (json \ "forward_auth").asOpt[Boolean].getOrElse(false),
        timeout = (json \ "timeout").asOpt[Long].map(_.millis).getOrElse(30.seconds),
      )
    } match {
      case Failure(ex) => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

case class McpStaticPromptArgument(name: String, description: Option[String] = None, required: Boolean = false) {
  def json: JsObject = Json.obj("name" -> name, "required" -> required) ++
    description.map(d => Json.obj("description" -> d)).getOrElse(Json.obj())
}

object McpStaticPromptArgument {
  val format = new Format[McpStaticPromptArgument] {
    override def writes(o: McpStaticPromptArgument): JsValue = o.json
    override def reads(json: JsValue): JsResult[McpStaticPromptArgument] = Try {
      McpStaticPromptArgument(
        name = (json \ "name").as[String],
        description = (json \ "description").asOpt[String].filter(_.trim.nonEmpty),
        required = (json \ "required").asOpt[Boolean].getOrElse(false),
      )
    } match {
      case Failure(ex) => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

case class McpStaticPromptMessage(role: String = "user", text: String) {
  def json: JsObject = Json.obj("role" -> role, "text" -> text)
}

object McpStaticPromptMessage {
  val format = new Format[McpStaticPromptMessage] {
    override def writes(o: McpStaticPromptMessage): JsValue = o.json
    override def reads(json: JsValue): JsResult[McpStaticPromptMessage] = Try {
      McpStaticPromptMessage(
        role = (json \ "role").asOpt[String].filter(_.trim.nonEmpty).getOrElse("user"),
        text = (json \ "text").asOpt[String].orElse((json \ "content" \ "text").asOpt[String]).getOrElse(""),
      )
    } match {
      case Failure(ex) => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

// A prompt served directly by an MCP virtual server (as opposed to one aggregated from an upstream
// McpConnector). The message templates substitute {{argName}} placeholders with the values provided in the
// prompts/get call, then are EL-evaluated against the request attrs.
case class McpStaticPrompt(
  name: String,
  title: Option[String] = None,
  description: Option[String] = None,
  arguments: Seq[McpStaticPromptArgument] = Seq.empty,
  messages: Seq[McpStaticPromptMessage] = Seq.empty,
  meta: Option[JsObject] = None, // MCP `_meta`
) {
  def json: JsValue = McpStaticPrompt.format.writes(this)

  // entry returned by prompts/list
  def listJson: JsObject = Json.obj("name" -> name) ++
    title.map(t => Json.obj("title" -> t)).getOrElse(Json.obj()) ++
    description.map(d => Json.obj("description" -> d)).getOrElse(Json.obj()) ++
    Json.obj("arguments" -> JsArray(arguments.map(_.json))) ++
    meta.map(m => Json.obj("_meta" -> m)).getOrElse(Json.obj())

  // prompts/get response payload (description + rendered messages)
  def getJson(args: Map[String, String], attrs: TypedMap)(implicit env: Env): JsObject = {
    def render(t: String): String = {
      val withArgs = args.foldLeft(t) { case (acc, (k, v)) => acc.replace("{{" + k + "}}", v) }
      withArgs.evaluateEl(attrs)
    }
    Json.obj(
      "description" -> description.getOrElse("").json,
      "messages" -> JsArray(messages.map(m => Json.obj(
        "role" -> m.role,
        "content" -> Json.obj("type" -> "text", "text" -> render(m.text)),
      ))),
    ) ++ meta.map(m => Json.obj("_meta" -> m)).getOrElse(Json.obj())
  }
}

object McpStaticPrompt {
  val format = new Format[McpStaticPrompt] {
    override def writes(o: McpStaticPrompt): JsValue = Json.obj(
      "name" -> o.name,
      "title" -> o.title.map(_.json).getOrElse(JsNull).asValue,
      "description" -> o.description.map(_.json).getOrElse(JsNull).asValue,
      "arguments" -> JsArray(o.arguments.map(_.json)),
      "messages" -> JsArray(o.messages.map(_.json)),
      "meta" -> o.meta.getOrElse[JsValue](JsNull),
    )
    override def reads(json: JsValue): JsResult[McpStaticPrompt] = Try {
      McpStaticPrompt(
        name = (json \ "name").as[String],
        title = (json \ "title").asOpt[String].filter(_.trim.nonEmpty),
        description = (json \ "description").asOpt[String].filter(_.trim.nonEmpty),
        arguments = (json \ "arguments").asOpt[Seq[JsValue]].getOrElse(Seq.empty).flatMap(v => McpStaticPromptArgument.format.reads(v).asOpt),
        messages = (json \ "messages").asOpt[Seq[JsValue]].getOrElse(Seq.empty).flatMap(v => McpStaticPromptMessage.format.reads(v).asOpt),
        meta = (json \ "meta").asOpt[JsObject].orElse((json \ "_meta").asOpt[JsObject]),
      )
    } match {
      case Failure(ex) => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

// Per-item overlays deep-merged onto the JSON of tools/prompts/resources/resource-templates at list time,
// whether the item comes from a connector or is managed. Keys are the item identity (tool/prompt/resource
// name, resource-template uriTemplate; resources also match by uri). The special key "*" applies to every item
// in the category (merged first, then the specific key on top). Lets you inject _meta/annotations (e.g. an
// mcp-app resource on a tool), tweak CSP, etc. without touching the upstream server. deepMerge semantics:
// nested objects are merged recursively, scalars and arrays are replaced.
case class McpItemOverlays(
  tools: Map[String, JsObject] = Map.empty,
  prompts: Map[String, JsObject] = Map.empty,
  resources: Map[String, JsObject] = Map.empty,
  resourceTemplates: Map[String, JsObject] = Map.empty,
) {
  def isEmpty: Boolean = tools.isEmpty && prompts.isEmpty && resources.isEmpty && resourceTemplates.isEmpty
  def json: JsValue = McpItemOverlays.format.writes(this)

  private def applyOverlay(m: Map[String, JsObject], keys: Seq[String], js: JsObject): JsObject = {
    if (m.isEmpty) js else {
      val patches = m.get("*").toSeq ++ keys.distinct.flatMap(k => m.get(k))
      patches.foldLeft(js)((acc, patch) => acc.deepMerge(patch))
    }
  }
  def applyTool(js: JsObject): JsObject = applyOverlay(tools, (js \ "name").asOpt[String].toSeq, js)
  def applyPrompt(js: JsObject): JsObject = applyOverlay(prompts, (js \ "name").asOpt[String].toSeq, js)
  def applyResource(js: JsObject): JsObject = applyOverlay(resources, Seq((js \ "name").asOpt[String], (js \ "uri").asOpt[String]).flatten, js)
  def applyResourceTemplate(js: JsObject): JsObject = applyOverlay(resourceTemplates, Seq((js \ "name").asOpt[String], (js \ "uriTemplate").asOpt[String]).flatten, js)

  // merge another set of overlays on top of this one (other wins, deep-merged on shared keys)
  def merge(other: McpItemOverlays): McpItemOverlays = {
    def mergeMaps(a: Map[String, JsObject], b: Map[String, JsObject]): Map[String, JsObject] = {
      (a.keySet ++ b.keySet).toSeq.map { k =>
        val merged = (a.get(k), b.get(k)) match {
          case (Some(x), Some(y)) => x.deepMerge(y)
          case (Some(x), None) => x
          case (None, Some(y)) => y
          case (None, None) => Json.obj()
        }
        k -> merged
      }.toMap
    }
    McpItemOverlays(
      tools = mergeMaps(tools, other.tools),
      prompts = mergeMaps(prompts, other.prompts),
      resources = mergeMaps(resources, other.resources),
      resourceTemplates = mergeMaps(resourceTemplates, other.resourceTemplates),
    )
  }
}

object McpItemOverlays {
  val empty = McpItemOverlays()
  private def readMap(json: JsValue, key: String): Map[String, JsObject] =
    (json \ key).asOpt[JsObject].map(_.value.flatMap { case (k, v) => v.asOpt[JsObject].map(o => (k, o)) }.toMap).getOrElse(Map.empty)
  val format = new Format[McpItemOverlays] {
    override def writes(o: McpItemOverlays): JsValue = Json.obj(
      "tools" -> JsObject(o.tools.toSeq),
      "prompts" -> JsObject(o.prompts.toSeq),
      "resources" -> JsObject(o.resources.toSeq),
      "resource_templates" -> JsObject(o.resourceTemplates.toSeq),
    )
    override def reads(json: JsValue): JsResult[McpItemOverlays] = Try {
      McpItemOverlays(
        tools = readMap(json, "tools"),
        prompts = readMap(json, "prompts"),
        resources = readMap(json, "resources"),
        resourceTemplates = readMap(json, "resource_templates"),
      )
    } match {
      case Failure(ex) => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

// A single user-defined redaction rule: every match of `regex` in the targeted text is replaced by
// `replacement`. `name` is only used for documentation/UI. Applied deterministically (no LLM), so it can
// run on every tool argument and result without latency/cost.
case class McpRedactionRule(name: String, regex: String, replacement: String) {
  def json: JsValue = Json.obj("name" -> name, "regex" -> regex, "replacement" -> replacement)
}
object McpRedactionRule {
  val format = new Format[McpRedactionRule] {
    override def writes(o: McpRedactionRule): JsValue = o.json
    override def reads(json: JsValue): JsResult[McpRedactionRule] = Try {
      McpRedactionRule(
        name = json.select("name").asOpt[String].getOrElse(""),
        regex = json.select("regex").asString,
        replacement = json.select("replacement").asOpt[String].getOrElse("«redacted»"),
      )
    } match {
      case Failure(ex) => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

// Zero-Trust MCP gateway controls, all opt-in. Three independent sub-features:
//   A. anti-rug-pull: fingerprint (hash) each tool's description/inputSchema and pin it (Trust-On-First-Use).
//      A later mutation of an already-pinned tool is flagged. `pinningEnforce` switches alert -> block.
//   B. guardrail scanning: run the existing guardrails engine against tool descriptions (at tools/list) and
//      tool results (at tools/call) to catch tool-poisoning / prompt-injection. `guardrailsEnforce` blocks.
//   C. redaction: deterministically mask PII/secrets in tool arguments (inbound) and results (outbound),
//      via built-in patterns and/or user rules.
// Each sub-feature defaults to OFF; blocking is always opt-in (default = monitor: alert + audit, non-breaking).
case class McpZeroTrustConfig(
  // A. anti-rug-pull
  pinningEnabled: Boolean = false,
  pinningEnforce: Boolean = false,
  pinnedHashes: Map[String, String] = Map.empty,
  pinningEpoch: Long = 0L,
  // B. guardrail scanning (reuses decorators.GuardrailItem: {id, config})
  descriptionGuardrails: Seq[GuardrailItem] = Seq.empty,
  resultGuardrails: Seq[GuardrailItem] = Seq.empty,
  guardrailsEnforce: Boolean = false,
  // C. redaction
  redactArguments: Boolean = false,
  redactResults: Boolean = false,
  redactionBuiltins: Seq[String] = Seq.empty,
  redactionRules: Seq[McpRedactionRule] = Seq.empty,
) {
  def isEmpty: Boolean = !pinningEnabled && descriptionGuardrails.isEmpty && resultGuardrails.isEmpty &&
    !redactArguments && !redactResults
  def json: JsValue = McpZeroTrustConfig.format.writes(this)
  def scanDescriptions: Boolean = descriptionGuardrails.nonEmpty
  def scanResults: Boolean = resultGuardrails.nonEmpty
  def redactionActive: Boolean = redactionBuiltins.nonEmpty || redactionRules.nonEmpty

  // merge another config on top of this one (override side wins): bools are OR'd, maps/seqs concatenated,
  // epoch takes the max. Mirrors the additive merge of the surrounding McpProxyEndpointConfig.
  def merge(o: McpZeroTrustConfig): McpZeroTrustConfig = McpZeroTrustConfig(
    pinningEnabled = pinningEnabled || o.pinningEnabled,
    pinningEnforce = pinningEnforce || o.pinningEnforce,
    pinnedHashes = pinnedHashes ++ o.pinnedHashes,
    pinningEpoch = math.max(pinningEpoch, o.pinningEpoch),
    descriptionGuardrails = if (o.descriptionGuardrails.nonEmpty) o.descriptionGuardrails else descriptionGuardrails,
    resultGuardrails = if (o.resultGuardrails.nonEmpty) o.resultGuardrails else resultGuardrails,
    guardrailsEnforce = guardrailsEnforce || o.guardrailsEnforce,
    redactArguments = redactArguments || o.redactArguments,
    redactResults = redactResults || o.redactResults,
    redactionBuiltins = (redactionBuiltins ++ o.redactionBuiltins).distinct,
    redactionRules = if (o.redactionRules.nonEmpty) o.redactionRules else redactionRules,
  )
}

object McpZeroTrustConfig {
  val empty = McpZeroTrustConfig()
  private def readGuardrails(json: JsValue, key: String): Seq[GuardrailItem] =
    json.select(key).asOpt[Seq[JsValue]].getOrElse(Seq.empty).flatMap(v => GuardrailItem.format.reads(v).asOpt)
  val format = new Format[McpZeroTrustConfig] {
    override def writes(o: McpZeroTrustConfig): JsValue = Json.obj(
      "pinning_enabled" -> o.pinningEnabled,
      "pinning_enforce" -> o.pinningEnforce,
      "pinned_hashes" -> JsObject(o.pinnedHashes.toSeq.map { case (k, v) => (k, JsString(v)) }),
      "pinning_epoch" -> o.pinningEpoch,
      "description_guardrails" -> JsArray(o.descriptionGuardrails.map(_.json)),
      "result_guardrails" -> JsArray(o.resultGuardrails.map(_.json)),
      "guardrails_enforce" -> o.guardrailsEnforce,
      "redact_arguments" -> o.redactArguments,
      "redact_results" -> o.redactResults,
      "redaction_builtins" -> o.redactionBuiltins,
      "redaction_rules" -> JsArray(o.redactionRules.map(_.json)),
    )
    override def reads(json: JsValue): JsResult[McpZeroTrustConfig] = Try {
      McpZeroTrustConfig(
        pinningEnabled = json.select("pinning_enabled").asOptBoolean.getOrElse(false),
        pinningEnforce = json.select("pinning_enforce").asOptBoolean.getOrElse(false),
        pinnedHashes = json.select("pinned_hashes").asOpt[Map[String, String]].getOrElse(Map.empty),
        pinningEpoch = json.select("pinning_epoch").asOpt[Long].getOrElse(0L),
        descriptionGuardrails = readGuardrails(json, "description_guardrails"),
        resultGuardrails = readGuardrails(json, "result_guardrails"),
        guardrailsEnforce = json.select("guardrails_enforce").asOptBoolean.getOrElse(false),
        redactArguments = json.select("redact_arguments").asOptBoolean.getOrElse(false),
        redactResults = json.select("redact_results").asOptBoolean.getOrElse(false),
        redactionBuiltins = json.select("redaction_builtins").asOpt[Seq[String]].getOrElse(Seq.empty),
        redactionRules = json.select("redaction_rules").asOpt[Seq[JsValue]].getOrElse(Seq.empty).flatMap(v => McpRedactionRule.format.reads(v).asOpt),
      )
    } match {
      case Failure(ex) => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

case class McpProxyEndpointConfig(
  serverRef: Option[String] = None,
  name: Option[String],
  version: Option[String],
  enforceOAuth: Boolean = false,
  // RFC 8707 audience binding: when true, the token `aud` claim must match this MCP server's URL
  // (the core checks `currentRequestUrl.startsWith(aud)`). Prevents token-passthrough / confused-deputy.
  // Defaults to false for backward compatibility; enable it for MCP-spec-compliant OAuth.
  validateAudience: Boolean = false,
  // Accept opaque (non-JWT) access tokens: validate them remotely (userinfo endpoint by default, or
  // RFC 7662 introspection when `useIntrospection` is set) instead of local JWT signature verification.
  // When set, JWT-based audience binding is skipped (an opaque token carries no readable `aud` claim).
  opaqueToken: Boolean = false,
  // for opaque tokens, validate via the RFC 7662 introspection endpoint instead of userinfo.
  useIntrospection: Boolean = false,
  // scope→tool authorization (RBAC): tool name (or "*" as default) → required OAuth scopes; the caller
  // is allowed iff it was granted ALL of them. Tools with no entry are open. Empty map = no scope checks.
  toolScopes: Map[String, Seq[String]] = Map.empty,
  // per-tool result cache: tool name (or "*") → TTL in seconds. Only cacheable/idempotent tools should be
  // listed (opt-in). 0/absent = no caching. Only successful results are cached.
  toolCacheTtls: Map[String, Long] = Map.empty,
  // per-tool rate limit: tool name (or "*") → max calls per minute per consumer. 0/absent = no limit.
  toolRateLimits: Map[String, Long] = Map.empty,
  authModuleRef: Option[String] = None,
  authPrmUrl: Option[String] = None,
  functionRefs: Seq[String],
  mcpRefs: Seq[String],
  // meta mode: expose the referenced connectors through 5 virtualization tools instead of the full tool
  // list (list_servers / list_tools / get_tool_schema / search_tools / execute) - like the meta connector.
  exposeAsMeta: Boolean = false,
  // when meta mode is on, also enable embedding-based semantic search in `search_tools`
  metaSemanticSearch: Boolean = false,
  emitAuditEvents: Boolean = false,
  includeFunctions: Seq[String] = Seq.empty,
  excludeFunctions: Seq[String] = Seq.empty,
  includeResources: Seq[String] = Seq.empty,
  excludeResources: Seq[String] = Seq.empty,
  includeResourceTemplates: Seq[String] = Seq.empty,
  excludeResourceTemplates: Seq[String] = Seq.empty,
  includeResourceTemplateUris: Seq[String] = Seq.empty,
  excludeResourceTemplateUris: Seq[String] = Seq.empty,
  includePrompts: Seq[String] = Seq.empty,
  excludePrompts: Seq[String] = Seq.empty,
  allowRules: McpConnectorRules = McpConnectorRules.empty,
  disallowRules: McpConnectorRules = McpConnectorRules.empty,
  resources: Seq[McpStaticResource] = Seq.empty,
  resourceFetchAllowedHosts: Seq[String] = Seq.empty,
  prompts: Seq[McpStaticPrompt] = Seq.empty,
  overlays: McpItemOverlays = McpItemOverlays.empty,
  zeroTrust: McpZeroTrustConfig = McpZeroTrustConfig.empty,
) extends NgPluginConfig {
  def json: JsValue = McpProxyEndpointConfig.format.writes(this)

  // Merge this config (treated as the override/plugin side) on top of `base` (the McpVirtualServer entity).
  // Hybrid rule: a field overrides the base only when it carries a meaningful value — Option => Some wins,
  // Seq => non-empty wins, the two enable-flags are OR'd, and allow/disallow rules are merged additively.
  def overriddenBy(o: McpProxyEndpointConfig): McpProxyEndpointConfig = copy(
    serverRef = None,
    name = o.name.orElse(name),
    version = o.version.orElse(version),
    enforceOAuth = enforceOAuth || o.enforceOAuth,
    validateAudience = validateAudience || o.validateAudience,
    opaqueToken = opaqueToken || o.opaqueToken,
    useIntrospection = useIntrospection || o.useIntrospection,
    toolScopes = toolScopes ++ o.toolScopes,
    toolCacheTtls = toolCacheTtls ++ o.toolCacheTtls,
    toolRateLimits = toolRateLimits ++ o.toolRateLimits,
    authModuleRef = o.authModuleRef.orElse(authModuleRef),
    authPrmUrl = o.authPrmUrl.orElse(authPrmUrl),
    functionRefs = if (o.functionRefs.nonEmpty) o.functionRefs else functionRefs,
    mcpRefs = if (o.mcpRefs.nonEmpty) o.mcpRefs else mcpRefs,
    exposeAsMeta = exposeAsMeta || o.exposeAsMeta,
    metaSemanticSearch = metaSemanticSearch || o.metaSemanticSearch,
    emitAuditEvents = emitAuditEvents || o.emitAuditEvents,
    includeFunctions = if (o.includeFunctions.nonEmpty) o.includeFunctions else includeFunctions,
    excludeFunctions = if (o.excludeFunctions.nonEmpty) o.excludeFunctions else excludeFunctions,
    includeResources = if (o.includeResources.nonEmpty) o.includeResources else includeResources,
    excludeResources = if (o.excludeResources.nonEmpty) o.excludeResources else excludeResources,
    includeResourceTemplates = if (o.includeResourceTemplates.nonEmpty) o.includeResourceTemplates else includeResourceTemplates,
    excludeResourceTemplates = if (o.excludeResourceTemplates.nonEmpty) o.excludeResourceTemplates else excludeResourceTemplates,
    includeResourceTemplateUris = if (o.includeResourceTemplateUris.nonEmpty) o.includeResourceTemplateUris else includeResourceTemplateUris,
    excludeResourceTemplateUris = if (o.excludeResourceTemplateUris.nonEmpty) o.excludeResourceTemplateUris else excludeResourceTemplateUris,
    includePrompts = if (o.includePrompts.nonEmpty) o.includePrompts else includePrompts,
    excludePrompts = if (o.excludePrompts.nonEmpty) o.excludePrompts else excludePrompts,
    allowRules = allowRules.merge(o.allowRules),
    disallowRules = disallowRules.merge(o.disallowRules),
    // resources are additive: override entries win, then base entries whose uri isn't overridden
    resources = o.resources ++ resources.filterNot(r => o.resources.exists(_.uri == r.uri)),
    resourceFetchAllowedHosts = if (o.resourceFetchAllowedHosts.nonEmpty) o.resourceFetchAllowedHosts else resourceFetchAllowedHosts,
    // prompts are additive: override entries win, then base entries whose name isn't overridden
    prompts = o.prompts ++ prompts.filterNot(p => o.prompts.exists(_.name == p.name)),
    // overlays are deep-merged (override wins on shared keys)
    overlays = overlays.merge(o.overlays),
    // zero-trust controls are merged additively (bools OR'd, maps/seqs concatenated, epoch maxed)
    zeroTrust = zeroTrust.merge(o.zeroTrust),
  )

  // When `serverRef` points to an existing McpVirtualServer, start from its config and overlay these inline
  // overrides. Otherwise return this config unchanged (full backward compatibility for ref-less routes).
  def resolve()(implicit env: Env): McpProxyEndpointConfig = {
    serverRef.flatMap(r => env.adminExtensions.extension[AiExtension].get.states.mcpVirtualServer(r)) match {
      case Some(server) => server.config.overriddenBy(this)
      case None => this
    }
  }
  private def matchesByName(name: String, include: Seq[String], exclude: Seq[String]): Boolean = {
    if (include.isEmpty && exclude.isEmpty) {
      true
    } else {
      val canpass = if (include.isEmpty) true else include.exists(p => otoroshi.utils.RegexPool.regex(p).matches(name))
      val excluded = if (exclude.isEmpty) false else exclude.exists(p => otoroshi.utils.RegexPool.regex(p).matches(name))
      canpass && !excluded
    }
  }
  def matchesFunction(name: String): Boolean = matchesByName(name, includeFunctions, excludeFunctions)
  // scope→tool authorization: the tool's required scopes (its own entry, or the "*" default) must all be
  // present in the caller's granted scopes. No entry (and no "*") => the tool is open.
  def toolAllowedForScopes(toolName: String, granted: Set[String]): Boolean = {
    val required = toolScopes.getOrElse(toolName, toolScopes.getOrElse("*", Seq.empty))
    required.forall(granted.contains)
  }
  def matchesResource(name: String): Boolean = matchesByName(name, includeResources, excludeResources)
  def matchesResourceTemplate(name: String, uri: String): Boolean = {
    matchesByName(name, includeResourceTemplates, excludeResourceTemplates) &&
      matchesByName(uri, includeResourceTemplateUris, excludeResourceTemplateUris)
  }
  def matchesPrompt(name: String): Boolean = matchesByName(name, includePrompts, excludePrompts)
  def applyFiltersTo(connector: McpConnector): McpConnector = connector.copy(
    includeFunctions = (connector.includeFunctions ++ includeFunctions).distinct,
    excludeFunctions = (connector.excludeFunctions ++ excludeFunctions).distinct,
    includeResources = (connector.includeResources ++ includeResources).distinct,
    excludeResources = (connector.excludeResources ++ excludeResources).distinct,
    includeResourceTemplates = (connector.includeResourceTemplates ++ includeResourceTemplates).distinct,
    excludeResourceTemplates = (connector.excludeResourceTemplates ++ excludeResourceTemplates).distinct,
    includeResourceTemplateUris = (connector.includeResourceTemplateUris ++ includeResourceTemplateUris).distinct,
    excludeResourceTemplateUris = (connector.excludeResourceTemplateUris ++ excludeResourceTemplateUris).distinct,
    includePrompts = (connector.includePrompts ++ includePrompts).distinct,
    excludePrompts = (connector.excludePrompts ++ excludePrompts).distinct,
    allowRules = connector.allowRules.merge(allowRules),
    disallowRules = connector.disallowRules.merge(disallowRules),
  )

  /**
   * Build the `capabilities` block advertised in the MCP `initialize` response based on
   * what's actually configured. Probes the referenced connectors and includes each
   * capability only when at least one connector exposes something in it. Local workflow
   * functions (`functionRefs`) count as tools.
   */
  def computeCapabilities(attrs: TypedMap, includeLogging: Boolean)(implicit env: Env, ec: ExecutionContext): Future[JsObject] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    val hasLocalFunctions = functionRefs.flatMap(r => ext.states.toolFunction(r)).nonEmpty
    val connectors = mcpRefs.flatMap(r => ext.states.mcpConnector(r)).map(applyFiltersTo)

    def anyNonEmpty[T](probe: McpConnector => Future[Seq[T]]): Future[Boolean] =
      Future
        .sequence(connectors.map(c => probe(c).recover { case _ => Seq.empty[T] }))
        .map(_.exists(_.nonEmpty))

    val toolsF     = if (connectors.isEmpty) Future.successful(false) else anyNonEmpty(_.listTools(attrs))
    val resourcesF = if (connectors.isEmpty) Future.successful(false) else anyNonEmpty(_.listResources(attrs))
    val templatesF = if (connectors.isEmpty) Future.successful(false) else anyNonEmpty(_.listResourceTemplates(attrs))
    val promptsF   = if (connectors.isEmpty) Future.successful(false) else anyNonEmpty(_.listPrompts(attrs))

    for {
      hasTools     <- toolsF
      hasResources <- resourcesF
      hasTemplates <- templatesF
      hasPrompts   <- promptsF
    } yield {
      var caps: JsObject = Json.obj()
      if (hasLocalFunctions || hasTools) caps = caps ++ Json.obj("tools" -> Json.obj())
      if (hasResources || hasTemplates || resources.nonEmpty) caps = caps ++ Json.obj("resources" -> Json.obj())
      if (hasPrompts || prompts.nonEmpty) caps = caps ++ Json.obj("prompts" -> Json.obj())
      if (includeLogging)                caps = caps ++ Json.obj("logging" -> Json.obj())
      caps
    }
  }
}

object McpProxyEndpointConfig {
  val configFlow: Seq[String] = Seq(
    "server_ref",
    "name", "version",
    "refs", "mcp_refs",
    "expose_as_meta", "meta_semantic_search",
    "enforce_oauth",
    "validate_audience",
    "opaque_token",
    "use_introspection",
    "tool_scopes",
    "tool_cache_ttls",
    "tool_rate_limits",
    "auth_module_ref",
    "auth_prm_url",
    "emit_audit_events",
    "include_functions", "exclude_functions",
    "include_resources", "exclude_resources",
    "include_resource_templates", "exclude_resource_templates",
    "include_resource_template_uris", "exclude_resource_template_uris",
    "include_prompts", "exclude_prompts",
    "allow_rules", "disallow_rules",
    "resources", "resource_fetch_allowed_hosts",
    "prompts",
    "overlays",
    "zero_trust",
  )
  val configSchema: Option[JsObject] = Some(Json.obj(
    "server_ref" -> Json.obj(
      "type" -> "select",
      "label" -> "MCP Virtual Server",
      "props" -> Json.obj(
        "optionsFrom" -> s"/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/mcp-virtual-servers",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      ),
    ),
    "name" -> Json.obj(
      "type" -> "string",
      "label" -> "MCP server name"
    ),
    "version" -> Json.obj(
      "type" -> "string",
      "label" -> "MCP server version"
    ),
    "enforce_oauth" -> Json.obj(
      "type" -> "bool",
      "label" -> "Enforce OAuth"
    ),
    "validate_audience" -> Json.obj(
      "type" -> "bool",
      "label" -> "Validate token audience (RFC 8707)"
    ),
    "opaque_token" -> Json.obj(
      "type" -> "bool",
      "label" -> "Opaque access token (validate remotely)"
    ),
    "use_introspection" -> Json.obj(
      "type" -> "bool",
      "label" -> "Use RFC 7662 introspection for opaque tokens (else userinfo)"
    ),
    "tool_scopes" -> Json.obj(
      "type" -> "any",
      "label" -> "Tool → required scopes (RBAC)",
      "props" -> Json.obj(
        "language" -> "json",
        "height" -> "150px"
      )
    ),
    "tool_cache_ttls" -> Json.obj(
      "type" -> "any",
      "label" -> "Tool → cache TTL (seconds)",
      "props" -> Json.obj(
        "language" -> "json",
        "height" -> "120px"
      )
    ),
    "tool_rate_limits" -> Json.obj(
      "type" -> "any",
      "label" -> "Tool → rate limit (calls/min per consumer)",
      "props" -> Json.obj(
        "language" -> "json",
        "height" -> "120px"
      )
    ),
    "emit_audit_events" -> Json.obj(
      "type" -> "bool",
      "label" -> "Emit audit events"
    ),
    "auth_prm_url" -> Json.obj(
      "type" -> "string",
      "label" -> "OAuth PRM URL"
    ),
    "auth_module_ref" -> Json.obj(
      "type"  -> "select",
      "label" -> s"Auth. module",
      "props" -> Json.obj(
        "optionsFrom"        -> "/bo/api/proxy/apis/security.otoroshi.io/v1/auth-modules",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id"
        )
      )
    ),
    "refs" -> Json.obj(
      "type" -> "select",
      "array" -> true,
      "label" -> s"LLM Tool Functions",
      "props" -> Json.obj(
        "optionsFrom" -> s"/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/tool-functions",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      ),
    ),
    "mcp_refs" -> Json.obj(
      "type" -> "select",
      "array" -> true,
      "label" -> s"MCP Connectors",
      "props" -> Json.obj(
        "optionsFrom" -> s"/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/mcp-connectors",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      ),
    ),
    "expose_as_meta" -> Json.obj(
      "type" -> "bool",
      "label" -> "Expose as meta (tool virtualization)"
    ),
    "meta_semantic_search" -> Json.obj(
      "type" -> "bool",
      "label" -> "Meta: enable semantic tool search"
    ),
    "include_functions" -> Json.obj(
      "type" -> "string",
      "array" -> true,
      "label" -> "Include functions"
    ),
    "exclude_functions" -> Json.obj(
      "type" -> "string",
      "array" -> true,
      "label" -> "Exclude functions"
    ),
    "include_resources" -> Json.obj(
      "type" -> "string",
      "array" -> true,
      "label" -> "Include resources"
    ),
    "exclude_resources" -> Json.obj(
      "type" -> "string",
      "array" -> true,
      "label" -> "Exclude resources"
    ),
    "include_resource_templates" -> Json.obj(
      "type" -> "string",
      "array" -> true,
      "label" -> "Include resource templates"
    ),
    "exclude_resource_templates" -> Json.obj(
      "type" -> "string",
      "array" -> true,
      "label" -> "Exclude resource templates"
    ),
    "include_resource_template_uris" -> Json.obj(
      "type" -> "string",
      "array" -> true,
      "label" -> "Include resource template URIs"
    ),
    "exclude_resource_template_uris" -> Json.obj(
      "type" -> "string",
      "array" -> true,
      "label" -> "Exclude resource template URIs"
    ),
    "include_prompts" -> Json.obj(
      "type" -> "string",
      "array" -> true,
      "label" -> "Include prompts"
    ),
    "exclude_prompts" -> Json.obj(
      "type" -> "string",
      "array" -> true,
      "label" -> "Exclude prompts"
    ),
    "allow_rules" -> Json.obj(
      "type" -> "any",
      "label" -> "Allow rules",
      "props" -> Json.obj(
        "language" -> "json",
        "height" -> "150px"
      )
    ),
    "disallow_rules" -> Json.obj(
      "type" -> "any",
      "label" -> "Disallow rules",
      "props" -> Json.obj(
        "language" -> "json",
        "height" -> "150px"
      )
    ),
    "resources" -> Json.obj(
      "type" -> "any",
      "label" -> "Managed resources",
      "props" -> Json.obj(
        "language" -> "json",
        "height" -> "300px"
      )
    ),
    "resource_fetch_allowed_hosts" -> Json.obj(
      "type" -> "string",
      "array" -> true,
      "label" -> "Resource fetch allowed hosts"
    ),
    "prompts" -> Json.obj(
      "type" -> "any",
      "label" -> "Managed prompts",
      "props" -> Json.obj(
        "language" -> "json",
        "height" -> "300px"
      )
    ),
    "overlays" -> Json.obj(
      "type" -> "any",
      "label" -> "Item overlays",
      "props" -> Json.obj(
        "language" -> "json",
        "height" -> "300px"
      )
    ),
    "zero_trust" -> Json.obj(
      "type" -> "any",
      "label" -> "Zero-Trust controls (pinning / guardrails / redaction)",
      "props" -> Json.obj(
        "language" -> "json",
        "height" -> "300px"
      )
    )
  ))
  val default = McpProxyEndpointConfig(
    name = None,
    version = None,
    functionRefs = Seq.empty,
    mcpRefs = Seq.empty,
  )
  val format = new Format[McpProxyEndpointConfig] {
    override def writes(o: McpProxyEndpointConfig): JsValue = Json.obj(
      "server_ref" -> o.serverRef.map(_.json).getOrElse(JsNull).asValue,
      "name" -> o.name.map(_.json).getOrElse(JsNull).asValue,
      "version" -> o.version.map(_.json).getOrElse(JsNull).asValue,
      "enforce_oauth" -> o.enforceOAuth,
      "validate_audience" -> o.validateAudience,
      "opaque_token" -> o.opaqueToken,
      "use_introspection" -> o.useIntrospection,
      "tool_scopes" -> JsObject(o.toolScopes.toSeq.map { case (k, v) => (k, JsArray(v.map(JsString.apply))) }),
      "tool_cache_ttls" -> JsObject(o.toolCacheTtls.toSeq.map { case (k, v) => (k, JsNumber(BigDecimal(v))) }),
      "tool_rate_limits" -> JsObject(o.toolRateLimits.toSeq.map { case (k, v) => (k, JsNumber(BigDecimal(v))) }),
      "auth_module_ref" -> o.authModuleRef.map(_.json).getOrElse(JsNull).asValue,
      "auth_prm_url" -> o.authPrmUrl.map(_.json).getOrElse(JsNull).asValue,
      "refs" -> o.functionRefs,
      "mcp_refs" -> o.mcpRefs,
      "expose_as_meta" -> o.exposeAsMeta,
      "meta_semantic_search" -> o.metaSemanticSearch,
      "emit_audit_events" -> o.emitAuditEvents,
      "include_functions" -> o.includeFunctions,
      "exclude_functions" -> o.excludeFunctions,
      "include_resources" -> o.includeResources,
      "exclude_resources" -> o.excludeResources,
      "include_resource_templates" -> o.includeResourceTemplates,
      "exclude_resource_templates" -> o.excludeResourceTemplates,
      "include_resource_template_uris" -> o.includeResourceTemplateUris,
      "exclude_resource_template_uris" -> o.excludeResourceTemplateUris,
      "include_prompts" -> o.includePrompts,
      "exclude_prompts" -> o.excludePrompts,
      "allow_rules" -> o.allowRules.json,
      "disallow_rules" -> o.disallowRules.json,
      "resources" -> JsArray(o.resources.map(_.json)),
      "resource_fetch_allowed_hosts" -> o.resourceFetchAllowedHosts,
      "prompts" -> JsArray(o.prompts.map(_.json)),
      "overlays" -> o.overlays.json,
      "zero_trust" -> o.zeroTrust.json,
    )
    override def reads(json: JsValue): JsResult[McpProxyEndpointConfig] = Try {
      val singleRef = json.select("ref").asOpt[String].map(r => Seq(r)).getOrElse(Seq.empty)
      val refs = json.select("refs").asOpt[Seq[String]].getOrElse(Seq.empty)
      val allRefs = refs ++ singleRef
      McpProxyEndpointConfig(
        serverRef = json.select("server_ref").asOptString.filter(_.trim.nonEmpty),
        name = json.select("name").asOptString.filter(_.trim.nonEmpty),
        version = json.select("version").asOptString.filter(_.trim.nonEmpty),
        enforceOAuth = json.select("enforce_oauth").asOptBoolean.getOrElse(false),
        validateAudience = json.select("validate_audience").asOptBoolean.getOrElse(false),
        opaqueToken = json.select("opaque_token").asOptBoolean.getOrElse(false),
        useIntrospection = json.select("use_introspection").asOptBoolean.getOrElse(false),
        toolScopes = json.select("tool_scopes").asOpt[Map[String, Seq[String]]].orElse(
          json.select("tool_scopes").asOpt[Map[String, String]].map(_.mapValues(_.split(",").map(_.trim).toSeq)),
        ).getOrElse(Map.empty),
        toolCacheTtls = json.select("tool_cache_ttls").asOpt[Map[String, Long]].getOrElse(Map.empty),
        toolRateLimits = json.select("tool_rate_limits").asOpt[Map[String, Long]].getOrElse(Map.empty),
        authModuleRef = json.select("auth_module_ref").asOptString,
        authPrmUrl = json.select("auth_prm_url").asOptString,
        functionRefs = allRefs,
        mcpRefs = json.select("mcp_refs").asOpt[Seq[String]].getOrElse(Seq.empty),
        exposeAsMeta = json.select("expose_as_meta").asOptBoolean.getOrElse(false),
        metaSemanticSearch = json.select("meta_semantic_search").asOptBoolean.getOrElse(false),
        emitAuditEvents = json.select("emit_audit_events").asOptBoolean.getOrElse(false),
        includeFunctions = json.select("include_functions").asOpt[Seq[String]].getOrElse(Seq.empty),
        excludeFunctions = json.select("exclude_functions").asOpt[Seq[String]].getOrElse(Seq.empty),
        includeResources = json.select("include_resources").asOpt[Seq[String]].getOrElse(Seq.empty),
        excludeResources = json.select("exclude_resources").asOpt[Seq[String]].getOrElse(Seq.empty),
        includeResourceTemplates = json.select("include_resource_templates").asOpt[Seq[String]].getOrElse(Seq.empty),
        excludeResourceTemplates = json.select("exclude_resource_templates").asOpt[Seq[String]].getOrElse(Seq.empty),
        includeResourceTemplateUris = json.select("include_resource_template_uris").asOpt[Seq[String]].getOrElse(Seq.empty),
        excludeResourceTemplateUris = json.select("exclude_resource_template_uris").asOpt[Seq[String]].getOrElse(Seq.empty),
        includePrompts = json.select("include_prompts").asOpt[Seq[String]].getOrElse(Seq.empty),
        excludePrompts = json.select("exclude_prompts").asOpt[Seq[String]].getOrElse(Seq.empty),
        allowRules = json.select("allow_rules").asOpt(McpConnectorRules.format).getOrElse(McpConnectorRules.empty),
        disallowRules = json.select("disallow_rules").asOpt(McpConnectorRules.format).getOrElse(McpConnectorRules.empty),
        resources = json.select("resources").asOpt[Seq[JsValue]].getOrElse(Seq.empty).flatMap(v => McpStaticResource.format.reads(v).asOpt),
        resourceFetchAllowedHosts = json.select("resource_fetch_allowed_hosts").asOpt[Seq[String]].getOrElse(Seq.empty),
        prompts = json.select("prompts").asOpt[Seq[JsValue]].getOrElse(Seq.empty).flatMap(v => McpStaticPrompt.format.reads(v).asOpt),
        overlays = json.select("overlays").asOpt(McpItemOverlays.format).getOrElse(McpItemOverlays.empty),
        zeroTrust = json.select("zero_trust").asOpt(McpZeroTrustConfig.format).getOrElse(McpZeroTrustConfig.empty),
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

object McpAuditHelper {

  // per-request holder for the json-rpc response envelope, so the audit event can log the actual
  // response (mcp_response) instead of null. attrs is mutable and shared for the whole request.
  val responseKey: TypedKey[JsValue] = TypedKey[JsValue]("cloud-apim.ai-gateway.mcp.response")
  def record(attrs: TypedMap, response: JsValue): Unit = {
    attrs.put(responseKey -> response)
    ()
  }
  def recorded(attrs: TypedMap): JsValue = attrs.get(responseKey).getOrElse(JsNull)

  // Real-time metrics for the MCP server/exposition side (always on when env metrics are enabled,
  // independent of the opt-in `emit_audit_events`). Flat metric names with the method encoded in the name.
  def markMetrics(method: String, durationMs: Long, isError: Boolean)(implicit env: Env): Unit = {
    if (!env.metricsEnabled) return
    val m = method.replace('/', '.')
    env.metrics.counterInc("mcp.server.calls")
    env.metrics.counterInc(s"mcp.server.$m.calls")
    if (isError) {
      env.metrics.counterInc("mcp.server.errors")
      env.metrics.counterInc(s"mcp.server.$m.errors")
    }
    env.metrics.timerUpdate(s"mcp.server.$m.duration", durationMs, java.util.concurrent.TimeUnit.MILLISECONDS)
  }

  def emit(
    method: String,
    id: Long,
    requestPayload: JsValue,
    duration: Long,
    transport: String,
    error: Option[String],
    attrs: TypedMap,
    response: JsValue = JsNull
  )(implicit env: Env): Unit = {
    val user = attrs.get(otoroshi.plugins.Keys.UserKey)
    val apikey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey)
    val route = attrs.get(otoroshi.next.plugins.Keys.RouteKey)
    // request_id = Otoroshi snowflake of the current request, for correlation across LLMUsageAudit /
    // McpAudit / McpClientAudit events.
    val requestId = attrs.get(otoroshi.plugins.Keys.SnowFlakeKey)
    AuditEvent.generic("McpAudit") {
      Json.obj(
        "request_id" -> requestId.map(JsString.apply).getOrElse(JsNull).asValue,
        "mcp_method" -> method,
        "mcp_id" -> id,
        "mcp_request_payload" -> requestPayload,
        "mcp_response" -> response,
        "transport" -> transport,
        "duration" -> duration,
        "status" -> (if (error.isEmpty) "success" else "error"),
        "error" -> error.map(_.json).getOrElse(JsNull).asValue,
        "user" -> user.map(_.json).getOrElse(JsNull).asValue,
        "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
        "route" -> route.map(_.json).getOrElse(JsNull).asValue,
      )
    }.toAnalytics()
  }
}

// ── Zero-Trust MCP ────────────────────────────────────────────────────────────────────────────────────────
// A. Anti-rug-pull: fingerprint a tool's security-relevant fields and pin them (Trust-On-First-Use), backed by
// the cluster-wide rawDataStore. A later mutation of an already-pinned tool is detected. Self-contained, no LLM.

sealed trait PinVerdict
object PinVerdict {
  case object Ok extends PinVerdict
  case object FirstSeen extends PinVerdict
  case class Mutated(previous: String, current: String) extends PinVerdict
}

object McpToolPinning {

  // Stable, canonical string for a JSON value: object keys are sorted recursively so the fingerprint is
  // insensitive to upstream key ordering (only real content changes flip the hash).
  def canonicalize(v: JsValue): String = v match {
    case o: JsObject => o.fields.sortBy(_._1).map { case (k, vv) => Json.stringify(JsString(k)) + ":" + canonicalize(vv) }.mkString("{", ",", "}")
    case a: JsArray  => a.value.map(canonicalize).mkString("[", ",", "]")
    case other       => Json.stringify(other)
  }

  // Fingerprint only the fields an attacker would mutate in a rug-pull: name, description, inputSchema,
  // annotations. Other volatile fields (e.g. _meta) are intentionally excluded.
  def fingerprint(toolJson: JsObject): String = {
    def field(name: String): JsValue = (toolJson \ name).asOpt[JsValue].getOrElse(JsNull)
    val relevant = Json.obj(
      "name" -> field("name"),
      "description" -> field("description"),
      "inputSchema" -> field("inputSchema"),
      "annotations" -> field("annotations"),
    )
    canonicalize(relevant).sha256
  }

  private def pinKey(ident: String, epoch: Long, tool: String)(implicit env: Env): String =
    s"${env.storageRoot}:llmext:mcp:pin:$epoch:${ident.sha256}:$tool"

  // TOFU check: an explicit pinned hash (config) is authoritative; otherwise the first sighting is pinned in
  // the datastore and subsequent sightings are compared against it.
  def check(z: McpZeroTrustConfig, ident: String, toolJson: JsObject)(implicit env: Env, ec: ExecutionContext): Future[PinVerdict] = {
    val tool = (toolJson \ "name").asOpt[String].getOrElse("")
    val fp = fingerprint(toolJson)
    z.pinnedHashes.get(tool) match {
      case Some(expected) => Future.successful(if (expected == fp) PinVerdict.Ok else PinVerdict.Mutated(expected, fp))
      case None =>
        val key = pinKey(ident, z.pinningEpoch, tool)
        env.datastores.rawDataStore.get(key).flatMap {
          case Some(bs) =>
            val prev = bs.utf8String
            Future.successful(if (prev == fp) PinVerdict.Ok else PinVerdict.Mutated(prev, fp))
          case None =>
            env.datastores.rawDataStore.set(key, ByteString(fp), None).map(_ => PinVerdict.FirstSeen)
        }
    }
  }
}

// B. Guardrail scanning: run the existing guardrails engine against an arbitrary piece of text (a tool
// description or a tool result), sourced from an explicit list of GuardrailItems instead of a provider's
// guardrails. Short-circuits on the first Denied; an erroring guardrail is skipped (never blocks).
object McpZeroTrust {
  def scan(items: Seq[GuardrailItem], text: String, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[GuardrailResult] = {
    val msg = ChatMessage.userStrInput(text)
    def loop(seq: Seq[GuardrailItem]): Future[GuardrailResult] = seq.headOption match {
      case None => Future.successful(GuardrailResult.GuardrailPass)
      case Some(item) if !item.enabled => loop(seq.tail)
      case Some(item) => Guardrails.get(item.guardrailId) match {
        case None => loop(seq.tail) // unknown guardrail id => skip
        case Some(g) => g.pass(Seq(msg), item.config, None, None, attrs).flatMap {
          case GuardrailResult.GuardrailPass => loop(seq.tail)
          case denied @ GuardrailResult.GuardrailDenied(_) => Future.successful(denied)
          case GuardrailResult.GuardrailError(_) => loop(seq.tail) // an internal guardrail error must not block
        }.recoverWith { case _ => loop(seq.tail) }
      }
    }
    loop(items)
  }
}

// C. Redaction: deterministic (no LLM) masking of PII/secrets in tool arguments and results.
object McpRedaction {
  // (kind -> (regex, replacement)). Applied in this fixed precedence order so specific patterns (jwt, keys)
  // run before the greedy generic_api_key one; only the kinds the user enabled are applied.
  private val builtinOrder: Seq[String] = Seq("private_key", "jwt", "aws_key", "email", "credit_card", "ssn", "ipv4", "generic_api_key")
  private val builtins: Map[String, (scala.util.matching.Regex, String)] = Map(
    "private_key" -> ("""-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----""".r, "«redacted:private_key»"),
    "jwt" -> ("""eyJ[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+""".r, "«redacted:jwt»"),
    "aws_key" -> ("""\b(?:AKIA|ASIA)[0-9A-Z]{16}\b""".r, "«redacted:aws_key»"),
    "email" -> ("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""".r, "«redacted:email»"),
    "credit_card" -> ("""\b\d(?:[ -]?\d){12,15}\b""".r, "«redacted:card»"),
    "ssn" -> ("""\b\d{3}-\d{2}-\d{4}\b""".r, "«redacted:ssn»"),
    "ipv4" -> ("""\b(?:\d{1,3}\.){3}\d{1,3}\b""".r, "«redacted:ip»"),
    "generic_api_key" -> ("""\b[A-Za-z0-9_\-]{32,}\b""".r, "«redacted:secret»"),
  )
  def redactText(text: String, z: McpZeroTrustConfig): String = {
    var out = text
    builtinOrder.filter(z.redactionBuiltins.contains).foreach { k =>
      builtins.get(k).foreach { case (rx, rep) => out = rx.replaceAllIn(out, java.util.regex.Matcher.quoteReplacement(rep)) }
    }
    z.redactionRules.foreach { r =>
      Try(r.regex.r).toOption.foreach { rx => out = rx.replaceAllIn(out, java.util.regex.Matcher.quoteReplacement(r.replacement)) }
    }
    out
  }
  // recursively redact every string leaf of a JSON value
  def redactJson(v: JsValue, z: McpZeroTrustConfig): JsValue = v match {
    case JsString(s)      => JsString(redactText(s, z))
    case o: JsObject      => JsObject(o.fields.map { case (k, vv) => (k, redactJson(vv, z)) })
    case a: JsArray       => JsArray(a.value.map(vv => redactJson(vv, z)))
    case other            => other
  }
}

// Emits a correlated audit/analytics event (+ metrics) whenever a zero-trust control fires. Routes through the
// existing data-exporter pipeline (SIEM/Kafka/ES/...) like McpAudit. `blocked` distinguishes monitor vs enforce.
object McpZeroTrustAudit {
  def alert(kind: String, tool: String, detail: JsObject, blocked: Boolean, attrs: TypedMap)(implicit env: Env): Unit = {
    if (env.metricsEnabled) {
      env.metrics.counterInc(s"mcp.zerotrust.$kind.alerts")
      if (blocked) env.metrics.counterInc(s"mcp.zerotrust.$kind.blocks")
    }
    val user = attrs.get(otoroshi.plugins.Keys.UserKey)
    val apikey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey)
    val route = attrs.get(otoroshi.next.plugins.Keys.RouteKey)
    val requestId = attrs.get(otoroshi.plugins.Keys.SnowFlakeKey)
    AuditEvent.generic("McpZeroTrustAlert") {
      Json.obj(
        "request_id" -> requestId.map(JsString.apply).getOrElse(JsNull).asValue,
        "zerotrust_kind" -> kind, // rugpull | guardrail | redaction
        "mcp_tool" -> tool,
        "blocked" -> blocked,
        "detail" -> detail,
        "user" -> user.map(_.json).getOrElse(JsNull).asValue,
        "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
        "route" -> route.map(_.json).getOrElse(JsNull).asValue,
      )
    }.toAnalytics()
  }
}

// Shared tools/list + tools/call logic for the 3 MCP exposure transports (sse / websocket / streamable-http).
// For Http connectors it forwards the raw upstream JSON-RPC payloads (full fidelity: _meta, annotations,
// outputSchema, structuredContent, rich content). For every other connector it falls back to the langchain4j
// ToolSpecification path, enriched with _meta/annotations/title recovered from ToolSpecification.metadata().
object McpProxyLogic {

  private def textPayload(res: String): JsObject = Json.obj("content" -> Json.arr(Json.obj("type" -> "text", "text" -> res)))

  // Full "tools" array: local tool functions + every mcp connector's tools.
  // Tool-surface connectors. In meta mode the referenced connectors are folded into a single synthetic
  // Meta connector exposing the 5 virtualization tools (list_servers / list_tools / get_tool_schema /
  // search_tools / execute) - same behavior as a Meta connector. Local functions stay listed directly,
  // and resources/prompts are not affected.
  private def toolConnectors(config: McpProxyEndpointConfig)(implicit env: Env): Seq[McpConnector] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    if (config.exposeAsMeta) {
      Seq(McpConnector(
        id = s"vs-meta-${(config.mcpRefs.mkString("|") + config.metaSemanticSearch).sha256}",
        enabled = true,
        name = "meta",
        transport = McpConnectorTransport(
          kind = McpConnectorTransportKind.Meta,
          options = Json.obj("connectors" -> config.mcpRefs, "semantic_search_enabled" -> config.metaSemanticSearch)
        )
      ))
    } else {
      config.mcpRefs.flatMap(r => ext.states.mcpConnector(r)).map(config.applyFiltersTo)
    }
  }

  def toolsList(config: McpProxyEndpointConfig, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Seq[JsValue]] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    val functions = config.functionRefs.flatMap(r => ext.states.toolFunction(r))
    val mcpConnectors = toolConnectors(config)
    val localThunks: Seq[JsValue] = functions.map { wf =>
      val required: JsArray = wf.required.map(v => JsArray(v.map(_.json))).getOrElse(JsArray(wf.parameters.value.keySet.toSeq.map(_.json)))
      Json.obj(
        "name" -> wf.name,
        "description" -> wf.description,
        "inputSchema" -> Json.obj("type" -> "object", "properties" -> wf.parameters, "required" -> required),
      ) ++
        wf.outputSchema.map(v => Json.obj("outputSchema" -> v)).getOrElse(Json.obj()) ++
        wf.annotations.map(v => Json.obj("annotations" -> v)).getOrElse(Json.obj()) ++
        wf.meta.map(v => Json.obj("_meta" -> v)).getOrElse(Json.obj())
    }
    Future.sequence(mcpConnectors.map { c =>
      if (c.isRawHttpTransport) {
        c.rawListTools(attrs).map(_.map(_.asInstanceOf[JsValue])).recoverWith { case _ => c.listTools(attrs).map(_.map(McpSupport.toolSpecToJson)) }
      } else {
        c.listTools(attrs).map(_.map(McpSupport.toolSpecToJson))
      }
    }).flatMap { perConnector =>
      val granted = attrs.get(McpOAuthFilterUtils.McpGrantedScopesKey).getOrElse(Set.empty[String])
      val afterScope = (localThunks ++ perConnector.flatten).filter {
        case o: JsObject => config.toolAllowedForScopes((o \ "name").asOpt[String].getOrElse(""), granted)
        case _           => true
      }
      // Zero-Trust: anti-rug-pull pinning + description guardrail scan, before overlays are applied.
      applyZeroTrustToList(config, afterScope, attrs).map { kept =>
        kept.map {
          case o: JsObject => config.overlays.applyTool(o)
          case other       => other
        }
      }
    }
  }

  // Config identity used to namespace per-config datastore keys (pins, cache). Matches the cache `ident`.
  private def ztIdent(config: McpProxyEndpointConfig): String =
    config.serverRef.getOrElse(config.mcpRefs.mkString(",") + "|" + config.functionRefs.mkString(","))

  // Applies the two list-time zero-trust controls (A. pinning, B. description scan) to the tool list. A tool is
  // dropped only when the corresponding `*Enforce` flag is on; otherwise it stays and only an alert is emitted.
  private def applyZeroTrustToList(config: McpProxyEndpointConfig, tools: Seq[JsValue], attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Seq[JsValue]] = {
    val z = config.zeroTrust
    if (!z.pinningEnabled && !z.scanDescriptions) Future.successful(tools)
    else {
      val ident = ztIdent(config)
      Future.traverse(tools) {
        case o: JsObject =>
          val tool = (o \ "name").asOpt[String].getOrElse("")
          val pinF: Future[Boolean] =
            if (z.pinningEnabled) McpToolPinning.check(z, ident, o).map {
              case PinVerdict.Mutated(prev, curr) =>
                McpZeroTrustAudit.alert("rugpull", tool, Json.obj("previous" -> prev, "current" -> curr, "phase" -> "list"), z.pinningEnforce, attrs)
                !z.pinningEnforce
              case _ => true
            } else Future.successful(true)
          pinF.flatMap {
            case false => Future.successful(Option.empty[JsValue]) // dropped by pinning enforce
            case true =>
              if (z.scanDescriptions) {
                val text = Seq((o \ "name").asOpt[String], (o \ "description").asOpt[String]).flatten.mkString("\n")
                McpZeroTrust.scan(z.descriptionGuardrails, text, attrs).map {
                  case GuardrailResult.GuardrailDenied(msg) =>
                    McpZeroTrustAudit.alert("guardrail", tool, Json.obj("phase" -> "description", "reason" -> msg), z.guardrailsEnforce, attrs)
                    if (z.guardrailsEnforce) Option.empty[JsValue] else Some(o: JsValue)
                  case _ => Some(o: JsValue)
                }
              } else Future.successful(Some(o: JsValue))
          }
        case other => Future.successful(Some(other))
      }.map(_.flatten)
    }
  }

  // Identity of the caller for per-tool rate limiting: apikey > user > bearer token > anonymous.
  private def consumerKey(attrs: TypedMap): String = {
    attrs.get(otoroshi.plugins.Keys.ApiKeyKey).map(ak => s"apikey:${ak.clientId}")
      .orElse(attrs.get(otoroshi.plugins.Keys.UserKey).map(u => s"user:${u.email}"))
      .orElse(attrs.get(McpOAuthFilterUtils.McpUserAuthTokenKey).map(t => s"tok:${t.sha256.take(16)}"))
      .getOrElse("anon")
  }

  // Per-tool, per-consumer fixed-window (60s) rate limit, backed by the shared datastore (cluster-wide).
  private def rateLimitAllows(config: McpProxyEndpointConfig, name: String, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Boolean] = {
    val limit = config.toolRateLimits.get(name).orElse(config.toolRateLimits.get("*")).getOrElse(0L)
    if (limit <= 0L) Future.successful(true)
    else {
      val window = System.currentTimeMillis() / 60000L
      val key = s"${env.storageRoot}:llmext:mcp:rl:${consumerKey(attrs)}:$name:$window"
      env.datastores.rawDataStore.incrby(key, 1L).flatMap { count =>
        (if (count == 1L) env.datastores.rawDataStore.pexpire(key, 60000L) else Future.successful(true)).map(_ => count <= limit)
      }
    }
  }

  // Per-tool result cache (opt-in via toolCacheTtls), backed by the shared datastore. Only successful
  // results are cached; the key includes the config identity to avoid cross-server collisions. The cached
  // value is the already-secured payload (post result-scan + redaction), so cache hits stay clean and the
  // guardrails don't re-run on every hit.
  private def cachedCallTool(config: McpProxyEndpointConfig, name: String, arguments: JsObject, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Either[String, JsObject]] = {
    val ttl = config.toolCacheTtls.get(name).orElse(config.toolCacheTtls.get("*")).getOrElse(0L)
    if (ttl <= 0L) securedCallTool(config, name, arguments, attrs)
    else {
      val ident = ztIdent(config)
      val key = s"${env.storageRoot}:llmext:mcp:cache:${(ident + "|" + name + "|" + arguments.stringify).sha256}"
      def computeAndStore(): Future[Either[String, JsObject]] =
        securedCallTool(config, name, arguments, attrs).flatMap {
          case Right(payload) => env.datastores.rawDataStore.set(key, ByteString(payload.stringify), Some(ttl * 1000L)).map(_ => Right(payload))
          case left           => Future.successful(left) // denied/blocked results are never cached
        }
      env.datastores.rawDataStore.get(key).flatMap {
        case Some(bs) => Try(Json.parse(bs.utf8String).as[JsObject]).toOption match {
          case Some(jo) => Future.successful(Right(jo))
          case None     => computeAndStore()
        }
        case None     => computeAndStore()
      }
    }
  }

  // Text content of a tool result payload (the main prompt-injection vector seen by the LLM).
  private def resultText(payload: JsObject): String =
    (payload \ "content").asOpt[Seq[JsValue]].getOrElse(Seq.empty).flatMap(c => (c \ "text").asOpt[String]).mkString("\n")

  // Executes the tool then applies the two result-time zero-trust controls (B. result guardrail scan,
  // C. result redaction). A scan denial under enforce returns Left (blocked, never cached).
  private def securedCallTool(config: McpProxyEndpointConfig, name: String, arguments: JsObject, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Either[String, JsObject]] = {
    val z = config.zeroTrust
    callToolInternal(config, name, arguments, attrs).flatMap {
      case Right(payload) =>
        val scanned: Future[Either[String, JsObject]] =
          if (z.scanResults) McpZeroTrust.scan(z.resultGuardrails, resultText(payload), attrs).map {
            case GuardrailResult.GuardrailDenied(msg) =>
              McpZeroTrustAudit.alert("guardrail", name, Json.obj("phase" -> "result", "reason" -> msg), z.guardrailsEnforce, attrs)
              if (z.guardrailsEnforce) Left(s"tool result blocked: $msg") else Right(payload)
            case _ => Right(payload)
          } else Future.successful(Right(payload))
        scanned.map {
          case Right(p) if z.redactResults && z.redactionActive =>
            val redacted = McpRedaction.redactJson(p, z).asOpt[JsObject].getOrElse(p)
            if (redacted != p) McpZeroTrustAudit.alert("redaction", name, Json.obj("phase" -> "result"), blocked = false, attrs)
            Right(redacted)
          case other => other
        }
      case left => Future.successful(left)
    }
  }

  // At call time (defense in depth): if any list-time control is in enforce mode, the tool must still be a
  // member of the secured tools/list. This blocks direct calls to a tool that pinning or a description
  // guardrail removed — even from a client that never issued tools/list.
  private def pinningCallCheck(config: McpProxyEndpointConfig, name: String, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Option[String]] = {
    val z = config.zeroTrust
    val enforcesListMembership = (z.pinningEnabled && z.pinningEnforce) || (z.scanDescriptions && z.guardrailsEnforce)
    if (!enforcesListMembership) Future.successful(None)
    else toolsList(config, attrs).map { secured =>
      val present = secured.exists { case o: JsObject => (o \ "name").asOpt[String].contains(name); case _ => false }
      if (present) None else Some(s"tool '$name' blocked: failed zero-trust list checks")
    }
  }

  // Resolves and executes a tool call. Left = unknown tool / denied, Right = the result payload. Pipeline:
  // scope→tool authorization → zero-trust list re-check (enforce) → argument redaction → per-tool rate limit
  // → per-tool result cache (wrapping execution + result scan + result redaction).
  def callTool(config: McpProxyEndpointConfig, name: String, arguments: JsObject, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Either[String, JsObject]] = {
    val z = config.zeroTrust
    val granted = attrs.get(McpOAuthFilterUtils.McpGrantedScopesKey).getOrElse(Set.empty[String])
    if (!config.toolAllowedForScopes(name, granted)) Future.successful(Left(name))
    else pinningCallCheck(config, name, attrs).flatMap {
      case Some(deny) => Future.successful(Left(deny))
      case None =>
        val redactedArgs = if (z.redactArguments && z.redactionActive) McpRedaction.redactJson(arguments, z).asOpt[JsObject].getOrElse(arguments) else arguments
        rateLimitAllows(config, name, attrs).flatMap {
          case false => Future.successful(Left(s"rate limit exceeded for tool '$name'"))
          case true  => cachedCallTool(config, name, redactedArgs, attrs)
        }
    }
  }

  private def callToolInternal(config: McpProxyEndpointConfig, name: String, arguments: JsObject, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Either[String, JsObject]] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    val functions = config.functionRefs.flatMap(r => ext.states.toolFunction(r))
    val functionsMap = functions.map(f => (f.name, f)).toMap
    functionsMap.get(name) match {
      case Some(function) => function.call(arguments.stringify, attrs).map(res => Right(textPayload(res)))
      case None =>
        val mcpConnectors = toolConnectors(config)
        mcpConnectors.find(c => c.listToolsBlocking(attrs).exists(_.name() == name)) match {
          case None => Future.successful(Left(name))
          case Some(c) if c.isRawHttpTransport =>
            c.rawCallTool(name, arguments.stringify, attrs).map(Right(_)).recoverWith { case _ =>
              c.call(name, arguments.stringify, attrs).map(res => Right(textPayload(res)))
            }
          case Some(c) => c.call(name, arguments.stringify, attrs).map(res => Right(textPayload(res)))
        }
    }
  }

  // Full "resources" array: this server's managed (static) resources + every connector's resources.
  def resourcesList(config: McpProxyEndpointConfig, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Seq[JsValue]] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    val mcpConnectors = config.mcpRefs.flatMap(r => ext.states.mcpConnector(r)).map(config.applyFiltersTo)
    val staticJson: Seq[JsValue] = config.resources.filter(r => config.matchesResource(r.name)).map(_.listJson)
    // recover per connector: a connector that doesn't support resources/list (-32601) must not break the aggregate
    Future.sequence(mcpConnectors.map(c => c.listResources(attrs).recover { case _ => Nil })).map(perConnector => (staticJson ++ perConnector.flatten.map(McpSupport.resourceToJson)).map {
      case o: JsObject => config.overlays.applyResource(o)
      case other => other
    })
  }

  // Reads a resource by uri. Managed (static) resources take precedence; otherwise it falls back to the
  // upstream connectors. Returns the `contents` array (empty when nothing matches).
  def readResource(config: McpProxyEndpointConfig, uri: String, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Seq[JsObject]] = {
    config.resources.find(_.uri == uri) match {
      case Some(res) => res.read(attrs, config.resourceFetchAllowedHosts, config.emitAuditEvents).map(Seq(_))
      case None =>
        val ext = env.adminExtensions.extension[AiExtension].get
        val mcpConnectors = config.mcpRefs.flatMap(r => ext.states.mcpConnector(r)).map(config.applyFiltersTo)
        Future.sequence(mcpConnectors.map(c => c.listResources(attrs).recover { case _ => Nil }.map(resources => (c, resources)))).flatMap { connectorsWithResources =>
          val matchingConnectors = connectorsWithResources.collect {
            case (connector, resources) if resources.exists(_.uri() == uri) => connector
          }
          Future.sequence(matchingConnectors.map(_.readResource(uri, attrs))).map { results =>
            results.flatten.headOption.map(_.contents().asScala.toSeq.map(McpSupport.resourceContentsToJson)).getOrElse(Seq.empty)
          }
        }
    }
  }

  // Full "resourceTemplates" array: every connector's resource templates (no managed templates), overlaid.
  def templatesList(config: McpProxyEndpointConfig, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Seq[JsValue]] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    val mcpConnectors = config.mcpRefs.flatMap(r => ext.states.mcpConnector(r)).map(config.applyFiltersTo)
    // recover per connector: a connector that doesn't support resources/templates/list (-32601) must not break the aggregate
    Future.sequence(mcpConnectors.map(c => c.listResourceTemplates(attrs).recover { case _ => Nil })).map(perConnector => perConnector.flatten.map(McpSupport.resourceTemplateToJson).map(config.overlays.applyResourceTemplate).map(_.asInstanceOf[JsValue]))
  }

  // Full "prompts" array: this server's managed (static) prompts + every connector's prompts.
  def promptsList(config: McpProxyEndpointConfig, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Seq[JsValue]] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    val mcpConnectors = config.mcpRefs.flatMap(r => ext.states.mcpConnector(r)).map(config.applyFiltersTo)
    val staticJson: Seq[JsValue] = config.prompts.filter(p => config.matchesPrompt(p.name)).map(_.listJson)
    // recover per connector: a connector that doesn't support prompts/list (-32601) must not break the aggregate
    Future.sequence(mcpConnectors.map(c => c.listPrompts(attrs).recover { case _ => Nil })).map(perConnector => (staticJson ++ perConnector.flatten.map(McpSupport.promptToJson)).map {
      case o: JsObject => config.overlays.applyPrompt(o)
      case other => other
    })
  }

  // Resolves a prompts/get. Managed (static) prompts take precedence; otherwise it falls back to the upstream
  // connectors. Returns the `{ description, messages }` payload.
  def getPrompt(config: McpProxyEndpointConfig, name: String, arguments: Map[String, Object], attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[JsObject] = {
    config.prompts.find(_.name == name) match {
      case Some(prompt) => Future.successful(prompt.getJson(arguments.mapValues(_.toString).toMap, attrs))
      case None =>
        val ext = env.adminExtensions.extension[AiExtension].get
        val mcpConnectors = config.mcpRefs.flatMap(r => ext.states.mcpConnector(r)).map(config.applyFiltersTo)
        Future.sequence(mcpConnectors.map(c => c.listPrompts(attrs).recover { case _ => Nil }.map(prompts => (c, prompts)))).flatMap { connectorsWithPrompts =>
          val matchingConnectors = connectorsWithPrompts.collect {
            case (connector, prompts) if prompts.exists(_.name() == name) => connector
          }
          Future.sequence(matchingConnectors.map(_.getPrompt(name, arguments, attrs))).map { results =>
            val result = results.flatten.headOption
            Json.obj(
              "description" -> result.map(_.description()).getOrElse("").json,
              "messages" -> JsArray(result.map(_.messages().asScala).getOrElse(Seq.empty).map { m =>
                Json.obj(
                  "role" -> m.role().name().toLowerCase,
                  "content" -> McpSupport.promptContentToJson(m.content()),
                )
              }),
            )
          }
        }
    }
  }
}

case class SseSession(
  sessionId: String,
  canceledRequests: TrieMap[Long, Unit] = new TrieMap[Long, Unit](),
  ready: AtomicBoolean = new AtomicBoolean(false),
  finished: AtomicBoolean = new AtomicBoolean(false),
  ref: AtomicReference[SourceQueueWithComplete[JsValue]] = new AtomicReference[SourceQueueWithComplete[JsValue]]()
) {

  def init(): Source[JsValue, _] = {
    val stream = Source
      .queue[JsValue](1000, OverflowStrategy.dropHead)
      .takeWhile(_ => !finished.get())
      .mapMaterializedValue { v =>
        ref.set(v)
      }
    stream
  }

  def send(id: Long, payload: JsValue)(implicit ec: ExecutionContext): Unit = {
    if (!canceledRequests.contains(id)) {
      Option(ref.get()).foreach(_.offer(Json.obj(
        "jsonrpc" -> "2.0",
        "id" -> id,
        "result" -> payload
      )))/*.debug(o => println(s"session ${sessionId} send- ${o.stringify}"))).andThen {
        case t => println(t)
      })*/
    }
  }

  def sendError(id: Long, code: Int, message: String, data: JsValue): Unit = {
    if (!canceledRequests.contains(id)) {
      Option(ref.get()).foreach(_.offer(Json.obj(
        "jsonrpc" -> "2.0",
        "id" -> id,
        "error" -> Json.obj(
          "code" -> code,
          "message" -> message,
          "data" -> data,
        )
      )))//.debug(o => println(s"session ${sessionId} error - ${o.stringify}"))))
    }
  }
}

class McpSseEndpoint extends NgBackendCall with NgAccessValidator {

  override def name: String = "Cloud APIM - MCP SSE Endpoint (deprecated)"
  override def description: Option[String] = "Exposes tool functions as an MCP server using the SSE Transport (deprecated)".some

  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))
  override def steps: Seq[NgStep] = Seq(NgStep.ValidateAccess, NgStep.CallBackend)
  override def useDelegates: Boolean = false
  override def defaultConfigObject: Option[NgPluginConfig] = Some(McpProxyEndpointConfig.default)

  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = McpProxyEndpointConfig.configFlow
  override def configSchema: Option[JsObject] = McpProxyEndpointConfig.configSchema

  private val sessions = new TrieMap[String, SseSession]()

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'MCP SSE Endpoint' plugin is available !")
    }
    ().vfuture
  }

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    McpOAuthFilterUtils.access(ctx, internalName)
  }

  def error(status: Int, msg: String): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    // println(s"http error: ${status} - ${msg}")
    NgProxyEngineError.NgResultProxyEngineError(Results.Status(status)(Json.obj("error" -> msg))).leftf
  }

  def jsonRpcResponse(id: Long, payload: JsValue)(implicit attrs: TypedMap): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val envelope = Json.obj(
      "jsonrpc" -> "2.0",
      "id" -> id,
      "result" -> payload
    )
    McpAuditHelper.record(attrs, envelope)
    BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok(envelope)), None).rightf
  }

  def emptyResp(id: Long)(implicit attrs: TypedMap): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    jsonRpcResponse(id, Json.obj())
  }

  def initialize(id: Long, session: SseSession, config: McpProxyEndpointConfig, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    implicit val _attrs: TypedMap = attrs
    config.computeCapabilities(attrs, includeLogging = false).flatMap { capabilities =>
      val response = Json.obj(
        "protocolVersion" -> "2025-06-18", //"2024-11-05",
        "capabilities" -> capabilities,
        "serverInfo" -> Json.obj("name" ->
          config.name.getOrElse("otoroshi-sse-endpoint").json,
          "version" -> config.version.getOrElse("1.0.0").json,
        ),
      )
      session.send(id, response)
      jsonRpcResponse(id, response)
    }
  }

  def getToolList(id: Long, session: SseSession, config: McpProxyEndpointConfig, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    implicit val _attrs: TypedMap = attrs
    McpProxyLogic.toolsList(config, attrs).flatMap { tools =>
      val response = Json.obj("tools" -> JsArray(tools))
      session.send(id, response)
      jsonRpcResponse(id, response)
    }
  }

  def toolsCall(id: Long, session: SseSession, request: JsValue, config: McpProxyEndpointConfig, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    implicit val _attrs: TypedMap = attrs
    val params = request.select("params").asOpt[JsObject].getOrElse(Json.obj())
    val name = params.select("name").asString
    val arguments = params.select("arguments").asOpt[JsObject].getOrElse(Json.obj())
    McpProxyLogic.callTool(config, name, arguments, attrs).flatMap {
      case Left(unknown) =>
        session.sendError(id, 0, s"unknown function ${unknown}", Json.obj("name" -> unknown))
        error(400, s"unknown function ${unknown}")
      case Right(payload) =>
        session.send(id, payload)
        jsonRpcResponse(id, payload)
    }
  }

  def getResourcesList(id: Long, session: SseSession, config: McpProxyEndpointConfig, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    implicit val _attrs: TypedMap = attrs
    McpProxyLogic.resourcesList(config, attrs).flatMap { resources =>
      val payload = Json.obj("resources" -> JsArray(resources))
      session.send(id, payload)
      jsonRpcResponse(id, payload)
    }
  }

  def getPromptsList(id: Long, session: SseSession, config: McpProxyEndpointConfig, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    implicit val _attrs: TypedMap = attrs
    McpProxyLogic.promptsList(config, attrs).flatMap { prompts =>
      val payload = Json.obj("prompts" -> JsArray(prompts))
      session.send(id, payload)
      jsonRpcResponse(id, payload)
    }
  }

  def getTemplatesList(id: Long, session: SseSession, config: McpProxyEndpointConfig, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    implicit val _attrs: TypedMap = attrs
    McpProxyLogic.templatesList(config, attrs).flatMap { templates =>
      val payload = Json.obj("templates" -> JsArray(templates))
      session.send(id, payload)
      jsonRpcResponse(id, payload)
    }
  }

  def readResource(id: Long, json: JsValue, session: SseSession, config: McpProxyEndpointConfig, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    implicit val _attrs: TypedMap = attrs
    json.select("params").select("uri").asOpt[String] match {
      case None => jsonRpcResponse(id, Json.obj("error" -> "missing uri parameter"))
      case Some(uri) =>
        McpProxyLogic.readResource(config, uri, attrs).flatMap { contents =>
          val payload = Json.obj("contents" -> JsArray(contents))
          session.send(id, payload)
          jsonRpcResponse(id, payload)
        }
    }
  }

  def getPromptHandler(id: Long, json: JsValue, session: SseSession, config: McpProxyEndpointConfig, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    implicit val _attrs: TypedMap = attrs
    json.select("params").select("name").asOpt[String] match {
      case None => jsonRpcResponse(id, Json.obj("error" -> "missing name parameter"))
      case Some(name) =>
        val arguments: Map[String, Object] = json.select("params").select("arguments").asOpt[JsObject].map { obj =>
          obj.value.mapValues(v => v.asOpt[String].getOrElse(v.stringify).asInstanceOf[Object]).toMap
        }.getOrElse(Map.empty)
        McpProxyLogic.getPrompt(config, name, arguments, attrs).flatMap { payload =>
          session.send(id, payload)
          jsonRpcResponse(id, payload)
        }
    }
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(McpProxyEndpointConfig.format).getOrElse(McpProxyEndpointConfig.default).resolve()
    if (ctx.request.method.toLowerCase() == "get") {
      val sessionId = if (env.isDev) ctx.request.queryParam("sessionId").getOrElse(IdGenerator.token(16)) else IdGenerator.token(16)
      val session = SseSession(sessionId)
      // println(s"adding session: ${sessionId}")
      sessions.put(sessionId, session)
      val sessionSource: Source[ByteString, _] = session.init().map(v => s"event: message\ndata: ${v.stringify}\n\n".byteString)
      val source: Source[ByteString, _] = Source.single(ByteString(s"event: endpoint\ndata: ${ctx.rawRequest.path}?sessionId=${sessionId}\n\n"))
        .concat(sessionSource)
        .alsoTo(Sink.onComplete {
          case _ =>
            // println(s"removing session: ${sessionId}")
            sessions.remove(sessionId)
        })
      BackendCallResponse.apply(NgPluginHttpResponse(
        status = 200,
        headers = Map(
          "Content-Type" -> "text/event-stream",
          "Transfer-Encoding" -> "chunked",
        ),
        cookies = Seq.empty,
        body = source
      ), None).rightf
    } else if (ctx.request.hasBody && ctx.request.method.toLowerCase() == "post") {
      ctx.request.queryParam("sessionId") match {
        case None => error(400, s"no session not found")
        case Some(sessionId) => {
          sessions.get(sessionId) match {
            case None =>
              val count = ctx.request.queryParam("redirectCount").map(_.toInt).getOrElse(0)
              if (count < 10) {
                // println("redirect")
                val nextCount = count + 1
                NgProxyEngineError.NgResultProxyEngineError(Results.SeeOther(s"${ctx.rawRequest.theProtocol}://${ctx.rawRequest.theHost}${ctx.request.path}?sessionId=${sessionId}&redirectCount=${nextCount}")).leftf
              } else {
                error(400, s"session not found: ${sessionId}")
              }
            case Some(session) => {
              ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
                // println(s"received client command raw ${sessionId} : ${bodyRaw.utf8String}")
                Try(bodyRaw.utf8String.parseJson) match {
                  case Failure(e) => error(400,"error while parsing json-rpc payload")
                  case Success(json) => {
                    implicit val _attrs: TypedMap = ctx.attrs
                    val id = json.select("id").asOpt[Long].getOrElse(0L)
                    val method = json.select("method").asOpt[String].getOrElse("--")
                    val start = System.currentTimeMillis()
                    val result: Future[Either[NgProxyEngineError, BackendCallResponse]] = method match {
                      case "initialize" => initialize(id, session, config, ctx.attrs)
                      case "shutdown" => {
                        session.finished.set(true)
                        session.send(id, Json.obj())
                        emptyResp(id)
                      }
                      case "exit" => {
                        session.finished.set(true)
                        session.send(id, Json.obj())
                        emptyResp(id)
                      }
                      case "ping" => {
                        session.send(id, Json.obj())
                        jsonRpcResponse(id, Json.obj())
                      }
                      case "cancelled" => {
                        session.canceledRequests.put(id, ())
                        emptyResp(id)
                      }
                      case "notifications/cancelled" => {
                        session.canceledRequests.put(id, ())
                        emptyResp(id)
                      }
                      case "notifications/initialized" => {
                        session.ready.set(true)
                        emptyResp(id)
                      }
                      case "tools/list" if session.ready.get() => getToolList(id, session, config, ctx.attrs)
                      case "resources/list" if session.ready.get() => getResourcesList(id, session, config, ctx.attrs)
                      case "resources/read" if session.ready.get() => readResource(id, json, session, config, ctx.attrs)
                      case "resources/templates/list" if session.ready.get() => getTemplatesList(id, session, config, ctx.attrs)
                      case "prompts/list" if session.ready.get() => getPromptsList(id, session, config, ctx.attrs)
                      case "prompts/get" if session.ready.get() => getPromptHandler(id, json, session, config, ctx.attrs)
                      case "tools/call" if session.ready.get() => toolsCall(id, session, json, config, ctx.attrs)
                      case _ => {
                        jsonRpcResponse(id, Json.obj("error" -> "method unsupported", "error_details" -> Json.obj("method" -> method, "ready" -> session.ready.get())))
                      }
                    }
                    result.onComplete { r =>
                      val dur = System.currentTimeMillis() - start
                      McpAuditHelper.markMetrics(method, dur, isError = !r.toOption.exists(_.isRight))
                      if (config.emitAuditEvents) {
                        r match {
                          case Success(Right(_)) =>
                            McpAuditHelper.emit(method, id, json, dur, "sse", None, ctx.attrs, McpAuditHelper.recorded(ctx.attrs))
                          case Success(Left(_)) =>
                            McpAuditHelper.emit(method, id, json, dur, "sse", Some("proxy_engine_error"), ctx.attrs, McpAuditHelper.recorded(ctx.attrs))
                          case Failure(ex) =>
                            McpAuditHelper.emit(method, id, json, dur, "sse", Some(ex.getMessage), ctx.attrs)
                        }
                      }
                    }
                    result
                  }
                }
              }
            }
          }
        }
      }
    } else {
      NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(Json.obj("error" -> "bad request"))).leftf
    }
  }
}

class McpWebsocketEndpoint extends NgWebsocketBackendPlugin with NgAccessValidator {

  override def name: String = "Cloud APIM - MCP WebSocket Endpoint (experimental)"
  override def description: Option[String] = "Exposes tool functions as an MCP server using the (non-official, experimental) WebSocket Transport".some

  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))
  override def steps: Seq[NgStep] = Seq(NgStep.ValidateAccess, NgStep.CallBackend)
  override def defaultConfigObject: Option[NgPluginConfig] = Some(McpProxyEndpointConfig.default)

  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = McpProxyEndpointConfig.configFlow
  override def configSchema: Option[JsObject] = McpProxyEndpointConfig.configSchema

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'MCP WebSocket Endpoint' plugin is available !")
    }
    ().vfuture
  }

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    McpOAuthFilterUtils.access(ctx, internalName)
  }

  override def callBackend(ctx: NgWebsocketPluginContext)(implicit env: Env, ec: ExecutionContext): Flow[Message, Message, _] = {
    val config = ctx.cachedConfig(internalName)(McpProxyEndpointConfig.format).getOrElse(McpProxyEndpointConfig.default).resolve()
    ActorFlow
      .actorRef(out => McpActor.props(out, config, env, ctx.attrs))(env.otoroshiActorSystem, env.otoroshiMaterializer)
  }
}

object McpActor {
  def props(out: ActorRef, config: McpProxyEndpointConfig, env: Env, attrs: TypedMap) = Props(new McpActor(out, config, env, attrs))
}

class McpActor(out: ActorRef, config: McpProxyEndpointConfig, env: Env, attrs: TypedMap) extends Actor {

  implicit val ec = env.otoroshiExecutionContext

  val ready = new AtomicBoolean(false)
  val canceledRequests = new TrieMap[Long, Unit]()

  def send(msg: JsValue): Unit = {
    val id = msg.select("id").asLong
    if (!canceledRequests.contains(id)) {
      out ! play.api.http.websocket.TextMessage(msg.stringify)
    }
  }

  def jsonRpcResponse(id: Long, payload: JsValue): JsValue = {
    Json.obj(
      "jsonrpc" -> "2.0",
      "id" -> id,
      "result" -> payload
    )
  }

  def jsonRpcError(id: Long, code: Int, message: String, data: JsValue): JsValue = {
    Json.obj(
      "jsonrpc" -> "2.0",
      "id" -> id,
      "result" -> Json.obj(
        "code" -> code,
        "message" -> message,
        "data" -> data
      )
    )
  }

  def emptyResp(id: Long): JsValue = {
    jsonRpcResponse(id, Json.obj())
  }

  def initialize(id: Long): Future[JsValue] = {
    implicit val e: Env = env
    implicit val ec: ExecutionContext = env.otoroshiExecutionContext
    config.computeCapabilities(attrs, includeLogging = false).map { capabilities =>
      val response = Json.obj(
        "protocolVersion" -> "2025-06-18",//"2024-11-05",
        "capabilities" -> capabilities,
        "serverInfo" -> Json.obj(
          "name" -> config.name.getOrElse("otoroshi-ws-endpoint").json,
          "version" -> config.version.getOrElse("1.0.0").json,
        ),
      )
      jsonRpcResponse(id, response)
    }
  }

  def getToolList(id: Long, config: McpProxyEndpointConfig, attrs: TypedMap): Future[JsValue] = {
    implicit val ec: ExecutionContext = env.otoroshiExecutionContext
    implicit val ev: Env = env
    McpProxyLogic.toolsList(config, attrs).map { tools =>
      jsonRpcResponse(id, Json.obj("tools" -> JsArray(tools)))
    }
  }

  def toolsCall(id: Long, request: JsValue, config: McpProxyEndpointConfig, attrs: TypedMap): Future[JsValue] = {
    implicit val ec: ExecutionContext = env.otoroshiExecutionContext
    implicit val ev: Env = env
    val params = request.select("params").asOpt[JsObject].getOrElse(Json.obj())
    val name = params.select("name").asString
    val arguments = params.select("arguments").asOpt[JsObject].getOrElse(Json.obj())
    McpProxyLogic.callTool(config, name, arguments, attrs).map {
      case Left(unknown) => jsonRpcError(id, 400, s"unknown function ${unknown}", Json.obj())
      case Right(payload) => jsonRpcResponse(id, payload)
    }
  }

  def getResourcesList(id: Long, attrs: TypedMap): Future[JsValue] = {
    McpProxyLogic.resourcesList(config, attrs)(env, ec).map { resources =>
      jsonRpcResponse(id, Json.obj("resources" -> JsArray(resources)))
    }(ec)
  }

  def getPromptsList(id: Long, attrs: TypedMap): Future[JsValue] = {
    McpProxyLogic.promptsList(config, attrs)(env, ec).map { prompts =>
      jsonRpcResponse(id, Json.obj("prompts" -> JsArray(prompts)))
    }(ec)
  }

  def getTemplatesList(id: Long, attrs: TypedMap): Future[JsValue] = {
    McpProxyLogic.templatesList(config, attrs)(env, ec).map { templates =>
      jsonRpcResponse(id, Json.obj("templates" -> JsArray(templates)))
    }(ec)
  }

  def readResource(id: Long, json: JsValue, attrs: TypedMap): Future[JsValue] = {
    json.select("params").select("uri").asOpt[String] match {
      case None => jsonRpcResponse(id, Json.obj("error" -> "missing uri parameter")).vfuture
      case Some(uri) =>
        McpProxyLogic.readResource(config, uri, attrs)(env, ec).map { contents =>
          jsonRpcResponse(id, Json.obj("contents" -> JsArray(contents)))
        }(ec)
    }
  }

  def getPromptHandler(id: Long, json: JsValue, attrs: TypedMap): Future[JsValue] = {
    json.select("params").select("name").asOpt[String] match {
      case None => jsonRpcResponse(id, Json.obj("error" -> "missing name parameter")).vfuture
      case Some(name) =>
        val arguments: Map[String, Object] = json.select("params").select("arguments").asOpt[JsObject].map { obj =>
          obj.value.mapValues(v => v.asOpt[String].getOrElse(v.stringify).asInstanceOf[Object]).toMap
        }.getOrElse(Map.empty)
        McpProxyLogic.getPrompt(config, name, arguments, attrs)(env, ec).map { payload =>
          jsonRpcResponse(id, payload)
        }(ec)
    }
  }

  def handle(data: String): Unit = {
    // println(s"handle ws raw message: ${data}")
    Try(data.parseJson) match {
      case Failure(e) => send(jsonRpcError(0, 400, "error while parsing json-rpc payload", Json.obj()))
      case Success(json) => {
        val id = json.select("id").asOpt[Long].getOrElse(0L)
        val method = json.select("method").asOpt[String].getOrElse("--")
        val start = System.currentTimeMillis()
        val resp: Future[JsValue] = method match {
          case "initialize" => initialize(id)
          case "shutdown" => {
            self ! PoisonPill
            emptyResp(id).vfuture
          }
          case "exit" => {
            self ! PoisonPill
            emptyResp(id).vfuture
          }
          case "ping" => jsonRpcResponse(id, Json.obj()).vfuture
          case "cancelled" => {
            canceledRequests.put(id, ())
            emptyResp(id).vfuture
          }
          case "notifications/cancelled" => {
            canceledRequests.put(id, ())
            emptyResp(id).vfuture
          }
          case "notifications/initialized" => {
            ready.set(true)
            emptyResp(id).vfuture
          }
          case "tools/list" if ready.get() => getToolList(id, config, attrs)
          case "resources/list" if ready.get() => getResourcesList(id, attrs)
          case "resources/read" if ready.get() => readResource(id, json, attrs)
          case "resources/templates/list" if ready.get() => getTemplatesList(id, attrs)
          case "prompts/list" if ready.get() => getPromptsList(id, attrs)
          case "prompts/get" if ready.get() => getPromptHandler(id, json, attrs)
          case "tools/call" if ready.get() => toolsCall(id, json, config, attrs)
          case _ => {
            jsonRpcResponse(id, Json.obj("error" -> "method unsupported", "error_details" -> Json.obj("method" -> method, "ready" -> ready.get()))).vfuture
          }
        }
        resp.onComplete { r =>
          val dur = System.currentTimeMillis() - start
          McpAuditHelper.markMetrics(method, dur, isError = r.isFailure)(env)
          if (config.emitAuditEvents) {
            r match {
              case Success(response) =>
                McpAuditHelper.emit(method, id, json, dur, "websocket", None, attrs, response)(env)
              case Failure(ex) =>
                McpAuditHelper.emit(method, id, json, dur, "websocket", Some(ex.getMessage), attrs)(env)
            }
          }
        }
        resp.map(r => send(r))(env.otoroshiExecutionContext)
      }
    }
  }

  override def receive: Receive = {
    case play.api.http.websocket.TextMessage(data)                => handle(data)
    case play.api.http.websocket.BinaryMessage(data)              => handle(data.utf8String)
    case play.api.http.websocket.PingMessage(_)                   => out ! play.api.http.websocket.PongMessage(ByteString.empty)
    case play.api.http.websocket.CloseMessage(statusCode, reason) => self ! PoisonPill
    case play.api.http.websocket.PongMessage(_)                   => ()
    case m                                                        =>  {
      println(s"unhandled m: ${m.getClass.getName} - ${m}")
    }
  }
}

class McpRespEndpoint extends NgBackendCall with NgAccessValidator {

  override def name: String = "Cloud APIM - MCP Streamable HTTP Endpoint"
  override def description: Option[String] = "Exposes tool functions as an MCP server using the streamable HTTP Transport".some

  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))
  override def steps: Seq[NgStep] = Seq(NgStep.ValidateAccess, NgStep.CallBackend)
  override def useDelegates: Boolean = false
  override def defaultConfigObject: Option[NgPluginConfig] = Some(McpProxyEndpointConfig.default)

  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = McpProxyEndpointConfig.configFlow
  override def configSchema: Option[JsObject] = McpProxyEndpointConfig.configSchema

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'MCP Streamable HTTP Endpoint' plugin is available !")
    }
    ().vfuture
  }

  def error(status: Int, msg: String): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    // println(s"http error: ${status} - ${msg}")
    NgProxyEngineError.NgResultProxyEngineError(Results.Status(status)(Json.obj("error" -> msg))).leftf
  }

  def jsonRpcResponse(id: Long, payload: JsValue)(implicit attrs: TypedMap): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val envelope = Json.obj(
      "jsonrpc" -> "2.0",
      "id" -> id,
      "result" -> payload
    )
    McpAuditHelper.record(attrs, envelope)
    BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok(envelope)), None).rightf
  }

  def emptyResp(id: Long)(implicit attrs: TypedMap): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    jsonRpcResponse(id, Json.obj())
  }

  def initialize(id: Long, config: McpProxyEndpointConfig, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    implicit val _attrs: TypedMap = attrs
    config.computeCapabilities(attrs, includeLogging = false).flatMap { capabilities =>
      val response = Json.obj(
        "protocolVersion" -> "2025-06-18", //"2024-11-05",
        "capabilities" -> capabilities,
        "serverInfo" -> Json.obj(
          "name" -> config.name.getOrElse("otoroshi-http-endpoint").json,
          "version" -> config.version.getOrElse("1.0.0").json,
        ),
      )
      jsonRpcResponse(id, response)
    }
  }

  def getToolList(id: Long, config: McpProxyEndpointConfig, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    implicit val _attrs: TypedMap = attrs
    McpProxyLogic.toolsList(config, attrs).flatMap { tools =>
      jsonRpcResponse(id, Json.obj("tools" -> JsArray(tools)))
    }
  }

  def toolsCall(id: Long, request: JsValue, config: McpProxyEndpointConfig, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    implicit val _attrs: TypedMap = attrs
    val params = request.select("params").asOpt[JsObject].getOrElse(Json.obj())
    val name = params.select("name").asString
    val arguments = params.select("arguments").asOpt[JsObject].getOrElse(Json.obj())
    McpProxyLogic.callTool(config, name, arguments, attrs).flatMap {
      case Left(unknown) => error(400, s"unknown function ${unknown}")
      case Right(payload) => jsonRpcResponse(id, payload)
    }
  }

  def getResourcesList(id: Long, config: McpProxyEndpointConfig, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    implicit val _attrs: TypedMap = attrs
    McpProxyLogic.resourcesList(config, attrs).flatMap { resources =>
      jsonRpcResponse(id, Json.obj("resources" -> JsArray(resources)))
    }
  }

  def getPromptsList(id: Long, config: McpProxyEndpointConfig, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    implicit val _attrs: TypedMap = attrs
    McpProxyLogic.promptsList(config, attrs).flatMap { prompts =>
      jsonRpcResponse(id, Json.obj("prompts" -> JsArray(prompts)))
    }
  }

  def getTemplatesList(id: Long, config: McpProxyEndpointConfig, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    implicit val _attrs: TypedMap = attrs
    McpProxyLogic.templatesList(config, attrs).flatMap { templates =>
      jsonRpcResponse(id, Json.obj("templates" -> JsArray(templates)))
    }
  }

  def readResource(id: Long, json: JsValue, config: McpProxyEndpointConfig, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    implicit val _attrs: TypedMap = attrs
    json.select("params").select("uri").asOpt[String] match {
      case None => jsonRpcResponse(id, Json.obj("error" -> "missing uri parameter"))
      case Some(uri) =>
        McpProxyLogic.readResource(config, uri, attrs).flatMap { contents =>
          jsonRpcResponse(id, Json.obj("contents" -> JsArray(contents)))
        }
    }
  }

  def getPromptHandler(id: Long, json: JsValue, config: McpProxyEndpointConfig, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    implicit val _attrs: TypedMap = attrs
    json.select("params").select("name").asOpt[String] match {
      case None => jsonRpcResponse(id, Json.obj("error" -> "missing name parameter"))
      case Some(name) =>
        val arguments: Map[String, Object] = json.select("params").select("arguments").asOpt[JsObject].map { obj =>
          obj.value.mapValues(v => v.asOpt[String].getOrElse(v.stringify).asInstanceOf[Object]).toMap
        }.getOrElse(Map.empty)
        McpProxyLogic.getPrompt(config, name, arguments, attrs).flatMap { payload =>
          jsonRpcResponse(id, payload)
        }
    }
  }

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    McpOAuthFilterUtils.access(ctx, internalName)
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(McpProxyEndpointConfig.format).getOrElse(McpProxyEndpointConfig.default).resolve()
    if (ctx.request.hasBody && ctx.request.method.toLowerCase() == "post") {
      ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
        Try(bodyRaw.utf8String.parseJson) match {
          case Failure(e) => error(400,"error while parsing json-rpc payload")
          case Success(json) => {
            implicit val _attrs: TypedMap = ctx.attrs
            // println(s"mcp in >>> ${json.prettify}")
            val id = json.select("id").asOpt[Long].getOrElse(0L)
            val method = json.select("method").asOpt[String].getOrElse("--")
            val start = System.currentTimeMillis()
            val result: Future[Either[NgProxyEngineError, BackendCallResponse]] = method match {
              case "initialize" => initialize(id, config, ctx.attrs)
              case "shutdown" => emptyResp(id)
              case "exit" => emptyResp(id)
              case "ping" => jsonRpcResponse(id, Json.obj())
              case "cancelled" => emptyResp(id)
              case "notifications/cancelled" => emptyResp(id)
              case "notifications/initialized" => emptyResp(id)
              case "tools/list" => getToolList(id, config, ctx.attrs)
              case "resources/list" => getResourcesList(id, config, ctx.attrs)
              case "resources/read" => readResource(id, json, config, ctx.attrs)
              case "resources/templates/list" => getTemplatesList(id, config, ctx.attrs)
              case "prompts/list" => getPromptsList(id, config, ctx.attrs)
              case "prompts/get" => getPromptHandler(id, json, config, ctx.attrs)
              case "tools/call" => toolsCall(id, json, config, ctx.attrs)
              case _ => {
                jsonRpcResponse(id, Json.obj("error" -> "method unsupported", "error_details" -> Json.obj("method" -> method)))
              }
            }
            result.onComplete { r =>
              val dur = System.currentTimeMillis() - start
              McpAuditHelper.markMetrics(method, dur, isError = !r.toOption.exists(_.isRight))
              if (config.emitAuditEvents) {
                r match {
                  case Success(Right(_)) =>
                    McpAuditHelper.emit(method, id, json, dur, "http", None, ctx.attrs, McpAuditHelper.recorded(ctx.attrs))
                  case Success(Left(_)) =>
                    McpAuditHelper.emit(method, id, json, dur, "http", Some("proxy_engine_error"), ctx.attrs, McpAuditHelper.recorded(ctx.attrs))
                  case Failure(ex) =>
                    McpAuditHelper.emit(method, id, json, dur, "http", Some(ex.getMessage), ctx.attrs)
                }
              }
            }
            result
          }
        }
      }
    } else {
      NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(Json.obj("error" -> "bad request"))).leftf
    }
  }
}

case class McpProtectedResourceMetadataConfig(
  serverRef: Option[String] = None,
  authModuleRef: Option[String] = None,
  resource: Option[String] = None,
  scopesSupported: Option[Seq[String]] = None,
  bearerMethodsSupported: Seq[String] = Seq("header", "query"),
  resourceDocumentation: Option[String] = None,
) extends NgPluginConfig {
  def json: JsValue = McpProtectedResourceMetadataConfig.format.writes(this)
  // effective auth module: the explicit authModuleRef, else the one configured on the referenced MCP
  // virtual server (so the preset can drive both plugins from just a server ref).
  def effectiveAuthModuleRef(implicit env: Env): Option[String] = authModuleRef.orElse {
    serverRef
      .flatMap(r => env.adminExtensions.extension[AiExtension].flatMap(_.states.mcpVirtualServer(r)))
      .flatMap(_.config.authModuleRef)
  }
}

object McpProtectedResourceMetadataConfig {
  val configFlow: Seq[String] = Seq(
    "server_ref",
    "auth_module_ref",
    "resource",
    "scopes_supported",
    "bearer_methods_supported",
    "resource_documentation",
  )
  val configSchema: Option[JsObject] = Some(Json.obj(
    "server_ref" -> Json.obj(
      "type"  -> "select",
      "label" -> "MCP Virtual Server",
      "props" -> Json.obj(
        "optionsFrom"        -> "/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/mcp-virtual-servers",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id"
        )
      )
    ),
    "auth_module_ref" -> Json.obj(
      "type"  -> "select",
      "label" -> "Auth. module",
      "props" -> Json.obj(
        "optionsFrom"        -> "/bo/api/proxy/apis/security.otoroshi.io/v1/auth-modules",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id"
        )
      )
    ),
    "resource" -> Json.obj(
      "type"  -> "string",
      "label" -> "Resource identifier"
    ),
    "scopes_supported" -> Json.obj(
      "type"  -> "string",
      "array" -> true,
      "label" -> "Scopes supported (override)"
    ),
    "bearer_methods_supported" -> Json.obj(
      "type"  -> "string",
      "array" -> true,
      "label" -> "Bearer methods supported"
    ),
    "resource_documentation" -> Json.obj(
      "type"  -> "string",
      "label" -> "Resource documentation URL"
    ),
  ))
  val default = McpProtectedResourceMetadataConfig()
  val format = new Format[McpProtectedResourceMetadataConfig] {
    override def writes(o: McpProtectedResourceMetadataConfig): JsValue = Json.obj(
      "server_ref" -> o.serverRef.map(_.json).getOrElse(JsNull).asValue,
      "auth_module_ref" -> o.authModuleRef.map(_.json).getOrElse(JsNull).asValue,
      "resource" -> o.resource.map(_.json).getOrElse(JsNull).asValue,
      "scopes_supported" -> o.scopesSupported.map(s => JsArray(s.map(_.json))).getOrElse(JsNull).asValue,
      "bearer_methods_supported" -> o.bearerMethodsSupported,
      "resource_documentation" -> o.resourceDocumentation.map(_.json).getOrElse(JsNull).asValue,
    )
    override def reads(json: JsValue): JsResult[McpProtectedResourceMetadataConfig] = Try {
      McpProtectedResourceMetadataConfig(
        serverRef = json.select("server_ref").asOptString.filter(_.trim.nonEmpty),
        authModuleRef = json.select("auth_module_ref").asOptString,
        resource = json.select("resource").asOptString.filter(_.trim.nonEmpty),
        scopesSupported = json.select("scopes_supported").asOpt[Seq[String]].filter(_.nonEmpty),
        bearerMethodsSupported = json.select("bearer_methods_supported").asOpt[Seq[String]].getOrElse(Seq("header", "query")),
        resourceDocumentation = json.select("resource_documentation").asOptString.filter(_.trim.nonEmpty),
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

class McpProtectedResourceMetadata extends NgBackendCall {

  override def name: String = "Cloud APIM - MCP Protected Resource Metadata document"
  override def description: Option[String] = "Exposes the OAuth 2.0 Protected Resource Metadata (RFC 9728) document for MCP endpoints".some

  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))
  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)
  override def useDelegates: Boolean = false
  override def defaultConfigObject: Option[NgPluginConfig] = Some(McpProtectedResourceMetadataConfig.default)

  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = McpProtectedResourceMetadataConfig.configFlow
  override def configSchema: Option[JsObject] = McpProtectedResourceMetadataConfig.configSchema

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'MCP Protected Resource Metadata' plugin is available !")
    }
    ().vfuture
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(McpProtectedResourceMetadataConfig.format).getOrElse(McpProtectedResourceMetadataConfig.default)
    config.effectiveAuthModuleRef match {
      case None =>
        NgProxyEngineError.NgResultProxyEngineError(
          Results.BadRequest(Json.obj("error" -> "no auth. module configured"))
        ).leftf
      case Some(authModuleId) =>
        env.proxyState.authModule(authModuleId) match {
          case None =>
            NgProxyEngineError.NgResultProxyEngineError(
              Results.BadRequest(Json.obj("error" -> "auth. module not found"))
            ).leftf
          case Some(m) =>
            m match {
              case oidcModule: OAuth2ModuleConfig => {
                val resource = config.resource.getOrElse(s"${ctx.rawRequest.theProtocol}://${ctx.rawRequest.theHost}")
                val authorizationServer = oidcModule
                    .oidConfig
                    .filter(_.trim.nonEmpty)
                    .filter(_.endsWith("/.well-known/openid-configuration"))
                    .map(_.replace("/.well-known/openid-configuration", ""))
                    .getOrElse(Uri(oidcModule.authorizeUrl).copy(path = Uri.Path./).toString())
                val scopes = config.scopesSupported.getOrElse {
                  oidcModule.scope.split("\\s+").filter(_.nonEmpty).toSeq
                }
                val jwksUri = oidcModule.jwtVerifier.flatMap {
                  case jwks: JWKSAlgoSettings => Some(jwks.url)
                  case _ => None
                }
                var prmJson = Json.obj(
                  "resource" -> resource,
                  "bearer_methods_supported" -> config.bearerMethodsSupported,
                )
                prmJson = prmJson ++ Json.obj("authorization_servers" -> Json.arr(authorizationServer))
                if (scopes.nonEmpty) {
                  prmJson = prmJson ++ Json.obj("scopes_supported" -> scopes)
                }
                if (jwksUri.isDefined) {
                  prmJson = prmJson ++ Json.obj("jwks_uri" -> jwksUri.get)
                }
                if (config.resourceDocumentation.isDefined) {
                  prmJson = prmJson ++ Json.obj("resource_documentation" -> config.resourceDocumentation.get)
                }
                BackendCallResponse(
                  NgPluginHttpResponse.fromResult(Results.Ok(prmJson).as("application/json")),
                  None
                ).rightf
              }
              case _ =>
                NgProxyEngineError.NgResultProxyEngineError(
                  Results.BadRequest(Json.obj("error" -> "auth. module is not an OAuth2/OIDC module"))
                ).leftf
            }
        }
    }
  }
}

// Preset that wires a full "protected MCP server over Streamable HTTP" on a single route, driven by one
// MCP virtual server reference:
//  - the MCP Streamable HTTP endpoint (McpRespEndpoint) on a customizable path (default /mcp)
//  - the OAuth Protected Resource Metadata document (RFC 9728) on the standard well-known path
// The virtual server carries the OAuth settings (enforce_oauth, auth module, audience binding, scopes, ...),
// so both child plugins are fully configured from just the server ref.
case class ProtectedMcpStreamableHttpPresetConfig(
  serverRef: Option[String] = None,
  mcpPath: String = "/mcp",
) extends NgPluginConfig {
  def json: JsValue = ProtectedMcpStreamableHttpPresetConfig.format.writes(this)
}

object ProtectedMcpStreamableHttpPresetConfig {
  val default = ProtectedMcpStreamableHttpPresetConfig()
  val configFlow: Seq[String] = Seq("server_ref", "mcp_path", "well_known_path")
  val configSchema: Option[JsObject] = Some(Json.obj(
    "server_ref" -> Json.obj(
      "type"  -> "select",
      "label" -> "MCP Virtual Server",
      "props" -> Json.obj(
        "optionsFrom"        -> "/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/mcp-virtual-servers",
        "optionsTransformer" -> Json.obj("label" -> "name", "value" -> "id")
      )
    ),
    "mcp_path" -> Json.obj("type" -> "string", "label" -> "MCP endpoint path")))
  val format = new Format[ProtectedMcpStreamableHttpPresetConfig] {
    override def writes(o: ProtectedMcpStreamableHttpPresetConfig): JsValue = Json.obj(
      "server_ref" -> o.serverRef.map(_.json).getOrElse(JsNull).asValue,
      "mcp_path" -> o.mcpPath,
    )
    override def reads(json: JsValue): JsResult[ProtectedMcpStreamableHttpPresetConfig] = Try {
      ProtectedMcpStreamableHttpPresetConfig(
        serverRef = json.select("server_ref").asOptString.filter(_.trim.nonEmpty),
        mcpPath = json.select("mcp_path").asOptString.filter(_.trim.nonEmpty).getOrElse("/mcp"),
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(v) => JsSuccess(v)
    }
  }
}

class ProtectedMcpStreamableHttpPreset extends NgPresetPlugin {

  override def name: String = "Cloud APIM - Protected MCP Streamable HTTP"
  override def description: Option[String] =
    "Preset: exposes an MCP virtual server over Streamable HTTP on a custom path (default /mcp), protected by OAuth, and serves the OAuth Protected Resource Metadata (RFC 9728) document on the well-known path - all driven by a single MCP virtual server reference.".some
  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"), NgPluginCategory.Custom("Presets"))
  override def steps: Seq[NgStep] = Seq(NgStep.ValidateAccess, NgStep.CallBackend)
  override def defaultConfigObject: Option[NgPluginConfig] = Some(ProtectedMcpStreamableHttpPresetConfig.default)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = ProtectedMcpStreamableHttpPresetConfig.configFlow
  override def configSchema: Option[JsObject] = ProtectedMcpStreamableHttpPresetConfig.configSchema

  override def expand(ctx: NgPresetPluginContext): Seq[NgPluginInstance] = {
    val config = ProtectedMcpStreamableHttpPresetConfig.format.reads(ctx.config).getOrElse(ProtectedMcpStreamableHttpPresetConfig.default)
    Seq(
      // 1. MCP Streamable HTTP endpoint on the (customizable) MCP path, configured from the virtual server
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[McpRespEndpoint],
        include = Seq(config.mcpPath),
        config = NgPluginInstanceConfig(McpProxyEndpointConfig.default.copy(serverRef = config.serverRef).json.asObject)
      ),
      // 2. OAuth Protected Resource Metadata (RFC 9728) on the standard well-known path
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[McpProtectedResourceMetadata],
        config = NgPluginInstanceConfig(McpProtectedResourceMetadataConfig.default.copy(serverRef = config.serverRef).json.asObject)
      ),
    )
  }
}

// ── MCP Registry (publish-only) ───────────────────────────────────────────────────────────────────────────
// Projects governed MCP virtual servers to the official MCP registry `server.json` format, so registry-aware
// MCP clients can discover the sanctioned servers and connect through the gateway. Publish-only: a virtual
// server is listed iff it is enabled and `registry.published`. The `remotes.url` is taken from the manually-set
// `registry.url` (auto-deriving it by scanning the routes that expose the server is deferred).
object McpRegistry {

  val schemaUrl = "https://static.modelcontextprotocol.io/schemas/2025-12-11/server.schema.json"
  val defaultNamespace = "io.cloud-apim"

  // reverse-DNS-ish name "<namespace>/<slug>": keep [a-z0-9], collapse the rest to '-', trim leading/trailing '-'.
  def slugifyName(name: String): String = {
    val slug = name.trim.toLowerCase.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "")
    s"$defaultNamespace/${if (slug.isEmpty) "server" else slug}"
  }

  def registryName(vs: McpVirtualServer): String =
    vs.registry.name.filter(_.trim.nonEmpty).getOrElse(slugifyName(vs.name))

  // Standard server.json document (+ the registry-official `_meta` envelope that carries the status).
  def serverJson(vs: McpVirtualServer): JsObject = {
    val r = vs.registry
    val title: String = r.title.filter(_.trim.nonEmpty).getOrElse(vs.name)
    var js = Json.obj(
      "$schema" -> schemaUrl,
      "name" -> registryName(vs),
      "description" -> vs.description,
      "title" -> title,
      "version" -> r.version,
    )
    r.url.filter(_.trim.nonEmpty).foreach { url =>
      js = js ++ Json.obj("remotes" -> Json.arr(Json.obj("type" -> "streamable-http", "url" -> url)))
    }
    js ++ Json.obj("_meta" -> Json.obj(
      // registry-managed metadata (server.json itself has no status field)
      "io.modelcontextprotocol.registry/official" -> Json.obj(
        "status" -> (if (r.deprecated) "deprecated" else "active"),
        "isLatest" -> true,
      ),
      // our own governance extension (reverse-DNS namespaced, does not break the standard)
      "com.cloud-apim.otoroshi/governance" -> Json.obj(
        "id" -> vs.id,
        "tags" -> JsArray(vs.tags.map(JsString.apply)),
      ),
    ))
  }

  private def published(servers: Seq[McpVirtualServer]): Seq[McpVirtualServer] =
    servers.filter(s => s.enabled && s.registry.published)

  // `GET /v0/servers` body: the published servers wrapped with a cursor-less metadata block.
  def listing(servers: Seq[McpVirtualServer]): JsObject = {
    val pub = published(servers)
    Json.obj(
      "servers" -> JsArray(pub.map(serverJson)),
      "metadata" -> Json.obj("count" -> pub.size),
    )
  }

  // `GET /v0/servers/{name}` body, by the (possibly namespaced) registry name.
  def findByName(servers: Seq[McpVirtualServer], name: String): Option[JsObject] =
    published(servers).find(s => registryName(s) == name).map(serverJson)
}

case class McpRegistryWellKnownConfig(
  registryUrl: Option[String] = None,
  schemaVersion: String = "2025-12-11",
) extends NgPluginConfig {
  def json: JsValue = McpRegistryWellKnownConfig.format.writes(this)
}

object McpRegistryWellKnownConfig {
  val default = McpRegistryWellKnownConfig()
  val configFlow: Seq[String] = Seq("registry_url", "schema_version")
  val configSchema: Option[JsObject] = Some(Json.obj(
    "registry_url" -> Json.obj("type" -> "string", "label" -> "Registry API base URL (default: this host + /v0)"),
    "schema_version" -> Json.obj("type" -> "string", "label" -> "server.json schema version"),
  ))
  val format = new Format[McpRegistryWellKnownConfig] {
    override def writes(o: McpRegistryWellKnownConfig): JsValue = Json.obj(
      "registry_url" -> o.registryUrl.map(_.json).getOrElse(JsNull).asValue,
      "schema_version" -> o.schemaVersion,
    )
    override def reads(json: JsValue): JsResult[McpRegistryWellKnownConfig] = Try {
      McpRegistryWellKnownConfig(
        registryUrl = json.select("registry_url").asOptString.filter(_.trim.nonEmpty),
        schemaVersion = json.select("schema_version").asOptString.filter(_.trim.nonEmpty).getOrElse("2025-12-11"),
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(v) => JsSuccess(v)
    }
  }
}

// Discovery pointer: advertises where the registry REST API lives. The exact `.well-known` path/format for an
// MCP *registry* is not strongly standardized yet, so the path is set on the route and the document is kept
// minimal & configurable; we will align it if/when the spec firms up.
class McpRegistryWellKnown extends NgBackendCall {
  override def name: String = "Cloud APIM - MCP Registry discovery (.well-known)"
  override def description: Option[String] = "Advertises the MCP registry REST API endpoint (discovery document).".some
  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))
  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)
  override def useDelegates: Boolean = false
  override def defaultConfigObject: Option[NgPluginConfig] = Some(McpRegistryWellKnownConfig.default)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = McpRegistryWellKnownConfig.configFlow
  override def configSchema: Option[JsObject] = McpRegistryWellKnownConfig.configSchema

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(McpRegistryWellKnownConfig.format).getOrElse(McpRegistryWellKnownConfig.default)
    val registryUrl = config.registryUrl.filter(_.trim.nonEmpty).getOrElse(s"${ctx.rawRequest.theProtocol}://${ctx.rawRequest.theHost}/v0")
    val doc = Json.obj(
      "registry" -> registryUrl,
      "servers_endpoint" -> s"$registryUrl/servers",
      "schema_version" -> config.schemaVersion,
      "server_json_schema" -> McpRegistry.schemaUrl,
    )
    BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok(doc).as("application/json")), None).rightf
  }
}

case class McpRegistryApiConfig(basePath: String = "/v0") extends NgPluginConfig {
  def json: JsValue = McpRegistryApiConfig.format.writes(this)
}

object McpRegistryApiConfig {
  val default = McpRegistryApiConfig()
  val configFlow: Seq[String] = Seq("base_path")
  val configSchema: Option[JsObject] = Some(Json.obj(
    "base_path" -> Json.obj("type" -> "string", "label" -> "API base path (default /v0)")))
  val format = new Format[McpRegistryApiConfig] {
    override def writes(o: McpRegistryApiConfig): JsValue = Json.obj("base_path" -> o.basePath)
    override def reads(json: JsValue): JsResult[McpRegistryApiConfig] = Try {
      McpRegistryApiConfig(basePath = json.select("base_path").asOptString.filter(_.trim.nonEmpty).getOrElse("/v0"))
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(v) => JsSuccess(v)
    }
  }
}

// Standard MCP registry REST API (publish-only): `GET …/servers` (list) and `GET …/servers/{name}` (one),
// projecting the published MCP virtual servers to server.json. The `{name}` segment may be namespaced (it can
// contain a '/'), so everything after the last `/servers/` is taken as the name.
class McpRegistryApi extends NgBackendCall {
  override def name: String = "Cloud APIM - MCP Registry API"
  override def description: Option[String] = "Serves the published MCP virtual servers as a standard MCP registry (server.json) over /v0/servers.".some
  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))
  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)
  override def useDelegates: Boolean = false
  override def defaultConfigObject: Option[NgPluginConfig] = Some(McpRegistryApiConfig.default)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = McpRegistryApiConfig.configFlow
  override def configSchema: Option[JsObject] = McpRegistryApiConfig.configSchema

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val servers = env.adminExtensions.extension[AiExtension].get.states.allMcpVirtualServers()
    val path = ctx.rawRequest.thePath
    val marker = "/servers"
    val i = path.lastIndexOf(marker)
    val rest = (if (i < 0) "" else path.substring(i + marker.length)).stripSuffix("/")
    val result = rest match {
      case "" => Results.Ok(McpRegistry.listing(servers)).as("application/json")
      case s  =>
        val nm = java.net.URLDecoder.decode(s.stripPrefix("/"), "UTF-8")
        McpRegistry.findByName(servers, nm) match {
          case Some(js) => Results.Ok(js).as("application/json")
          case None     => Results.NotFound(Json.obj("error" -> s"server '$nm' not found")).as("application/json")
        }
    }
    BackendCallResponse(NgPluginHttpResponse.fromResult(result), None).rightf
  }
}

// Preset that wires the full MCP registry on a single route in one click:
//  - the discovery document (McpRegistryWellKnown) on the .well-known path (default /.well-known/mcp-registry)
//  - the standard registry REST API (McpRegistryApi) on the API base path (default /v0, matching /v0/.*)
// Both serve the published MCP virtual servers as standard server.json.
case class McpRegistryPresetConfig(
  wellKnownPath: String = "/.well-known/mcp-registry",
  apiBasePath: String = "/v0",
  registryUrl: Option[String] = None,
  schemaVersion: String = "2025-12-11",
) extends NgPluginConfig {
  def json: JsValue = McpRegistryPresetConfig.format.writes(this)
}

object McpRegistryPresetConfig {
  val default = McpRegistryPresetConfig()
  val configFlow: Seq[String] = Seq("well_known_path", "api_base_path", "registry_url", "schema_version")
  val configSchema: Option[JsObject] = Some(Json.obj(
    "well_known_path" -> Json.obj("type" -> "string", "label" -> "Discovery .well-known path"),
    "api_base_path" -> Json.obj("type" -> "string", "label" -> "Registry API base path"),
    "registry_url" -> Json.obj("type" -> "string", "label" -> "Registry API base URL (optional, default: this host + base path)"),
    "schema_version" -> Json.obj("type" -> "string", "label" -> "server.json schema version"),
  ))
  val format = new Format[McpRegistryPresetConfig] {
    override def writes(o: McpRegistryPresetConfig): JsValue = Json.obj(
      "well_known_path" -> o.wellKnownPath,
      "api_base_path" -> o.apiBasePath,
      "registry_url" -> o.registryUrl.map(_.json).getOrElse(JsNull).asValue,
      "schema_version" -> o.schemaVersion,
    )
    override def reads(json: JsValue): JsResult[McpRegistryPresetConfig] = Try {
      McpRegistryPresetConfig(
        wellKnownPath = json.select("well_known_path").asOptString.filter(_.trim.nonEmpty).getOrElse("/.well-known/mcp-registry"),
        apiBasePath = json.select("api_base_path").asOptString.filter(_.trim.nonEmpty).getOrElse("/v0"),
        registryUrl = json.select("registry_url").asOptString.filter(_.trim.nonEmpty),
        schemaVersion = json.select("schema_version").asOptString.filter(_.trim.nonEmpty).getOrElse("2025-12-11"),
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(v) => JsSuccess(v)
    }
  }
}

class McpRegistryPreset extends NgPresetPlugin {

  override def name: String = "Cloud APIM - MCP Registry"
  override def description: Option[String] =
    "Preset: serves the MCP registry in one click - the discovery .well-known document and the standard /v0/servers API - projecting the published MCP virtual servers to server.json.".some
  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"), NgPluginCategory.Custom("Presets"))
  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)
  override def defaultConfigObject: Option[NgPluginConfig] = Some(McpRegistryPresetConfig.default)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = McpRegistryPresetConfig.configFlow
  override def configSchema: Option[JsObject] = McpRegistryPresetConfig.configSchema

  override def expand(ctx: NgPresetPluginContext): Seq[NgPluginInstance] = {
    val config = McpRegistryPresetConfig.format.reads(ctx.config).getOrElse(McpRegistryPresetConfig.default)
    Seq(
      // 1. discovery document on the .well-known path
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[McpRegistryWellKnown],
        include = Seq(config.wellKnownPath),
        config = NgPluginInstanceConfig(McpRegistryWellKnownConfig.default.copy(registryUrl = config.registryUrl, schemaVersion = config.schemaVersion).json.asObject)
      ),
      // 2. standard registry REST API on the base path (matches /<base>/.*)
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[McpRegistryApi],
        include = Seq(s"${config.apiBasePath}/.*"),
        config = NgPluginInstanceConfig(McpRegistryApiConfig.default.copy(basePath = config.apiBasePath).json.asObject)
      ),
    )
  }
}
