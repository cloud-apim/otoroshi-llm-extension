package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import akka.actor.{Actor, ActorRef, PoisonPill, Props}
import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.{Flow, Sink, Source, SourceQueueWithComplete}
import akka.stream.{Materializer, OverflowStrategy}
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.agents.InlineFunction
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{LlmToolFunction, McpConnector, McpConnectorRules, McpSupport}
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.mcp.client.{McpBlobResourceContents, McpResourceContents, McpTextResourceContents}
import dev.langchain4j.model.chat.request.json.JsonSchemaElement
import otoroshi.auth.OAuth2ModuleConfig
import otoroshi.env.Env
import otoroshi.events.AuditEvent
import otoroshi.models.{InHeader, InQueryParam, JWKSAlgoSettings, JwtTokenLocation, LocalJwtVerifier}
import otoroshi.next.plugins.{OIDCAuthToken, OIDCAuthTokenConfig, OIDCJwtVerifierConfig}
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
import play.api.libs.typedmap.TypedKey
import play.api.mvc.{Result, Results}

import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}
import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

object McpOAuthFilterUtils {

  val McpUserAuthTokenKey = TypedKey[String]("cloud-apim.ai-gateway.mcp.McpUserAuthToken")
  val sources = Seq(InHeader("Authorization", "Bearer "), InQueryParam("access_token"))

  def unauthorizedResult(ctx: NgAccessContext, config: McpProxyEndpointConfig, oidcModule: OAuth2ModuleConfig)(implicit env: Env, ec: ExecutionContext): Result = {
    val realmName = oidcModule.cookieSuffix(ctx.route.legacy)
    val authPrmUrl = config.authPrmUrl.getOrElse(s"${ctx.request.theProtocol}://${ctx.request.theHost}/.well-known/oauth-protected-resource")
    Results.Status(401)(Json.obj("error" -> "unauthorized"))
      .withHeaders("WWW-Authenticate"-> s"""Bearer realm="${realmName}", resource_metadata="${authPrmUrl}"""")
      .as("application/json")
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

case class McpProxyEndpointConfig(
  serverRef: Option[String] = None,
  name: Option[String],
  version: Option[String],
  enforceOAuth: Boolean = false,
  authModuleRef: Option[String] = None,
  authPrmUrl: Option[String] = None,
  functionRefs: Seq[String],
  mcpRefs: Seq[String],
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
    authModuleRef = o.authModuleRef.orElse(authModuleRef),
    authPrmUrl = o.authPrmUrl.orElse(authPrmUrl),
    functionRefs = if (o.functionRefs.nonEmpty) o.functionRefs else functionRefs,
    mcpRefs = if (o.mcpRefs.nonEmpty) o.mcpRefs else mcpRefs,
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
      if (hasResources || hasTemplates)  caps = caps ++ Json.obj("resources" -> Json.obj())
      if (hasPrompts)                    caps = caps ++ Json.obj("prompts" -> Json.obj())
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
    "enforce_oauth",
    "auth_module_ref",
    "auth_prm_url",
    "emit_audit_events",
    "include_functions", "exclude_functions",
    "include_resources", "exclude_resources",
    "include_resource_templates", "exclude_resource_templates",
    "include_resource_template_uris", "exclude_resource_template_uris",
    "include_prompts", "exclude_prompts",
    "allow_rules", "disallow_rules",
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
      "auth_module_ref" -> o.authModuleRef.map(_.json).getOrElse(JsNull).asValue,
      "auth_prm_url" -> o.authPrmUrl.map(_.json).getOrElse(JsNull).asValue,
      "refs" -> o.functionRefs,
      "mcp_refs" -> o.mcpRefs,
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
        authModuleRef = json.select("auth_module_ref").asOptString,
        authPrmUrl = json.select("auth_prm_url").asOptString,
        functionRefs = allRefs,
        mcpRefs = json.select("mcp_refs").asOpt[Seq[String]].getOrElse(Seq.empty),
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

// Shared tools/list + tools/call logic for the 3 MCP exposure transports (sse / websocket / streamable-http).
// For Http connectors it forwards the raw upstream JSON-RPC payloads (full fidelity: _meta, annotations,
// outputSchema, structuredContent, rich content). For every other connector it falls back to the langchain4j
// ToolSpecification path, enriched with _meta/annotations/title recovered from ToolSpecification.metadata().
object McpProxyLogic {

  private def textPayload(res: String): JsObject = Json.obj("content" -> Json.arr(Json.obj("type" -> "text", "text" -> res)))

  // Full "tools" array: local tool functions + every mcp connector's tools.
  def toolsList(config: McpProxyEndpointConfig, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Seq[JsValue]] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    val functions = config.functionRefs.flatMap(r => ext.states.toolFunction(r))
    val mcpConnectors = config.mcpRefs.flatMap(r => ext.states.mcpConnector(r)).map(config.applyFiltersTo)
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
    }).map(perConnector => localThunks ++ perConnector.flatten)
  }

  // Resolves and executes a tool call. Left = unknown tool name, Right = the result payload.
  def callTool(config: McpProxyEndpointConfig, name: String, arguments: JsObject, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Either[String, JsObject]] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    val functions = config.functionRefs.flatMap(r => ext.states.toolFunction(r))
    val functionsMap = functions.map(f => (f.name, f)).toMap
    functionsMap.get(name) match {
      case Some(function) => function.call(arguments.stringify, attrs).map(res => Right(textPayload(res)))
      case None =>
        val mcpConnectors = config.mcpRefs.flatMap(r => ext.states.mcpConnector(r)).map(config.applyFiltersTo)
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
}

/*
class McpLocalProxyEndpoint extends NgBackendCall {

  override def name: String = "Cloud APIM - MCP Tools Endpoint"
  override def description: Option[String] = "Exposes tool functions as an MCP server with a local proxy provided by npx @cloud-apim/otoroshi-mcp-proxy".some

  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))
  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)
  override def useDelegates: Boolean = false
  override def defaultConfigObject: Option[NgPluginConfig] = Some(McpProxyEndpointConfig.default)

  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = McpProxyEndpointConfig.configFlow
  override def configSchema: Option[JsObject] = McpProxyEndpointConfig.configSchema

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'MCP Tools Endpoint' plugin is available !")
    }
    ().vfuture
  }

  def call(_jsonBody: JsValue, config: McpProxyEndpointConfig, ctx: NgbBackendCallContext)(implicit ec: ExecutionContext, env: Env): Future[Either[NgProxyEngineError, BackendCallResponse]] = {

    val method = _jsonBody.select("method").asOpt[String].getOrElse("tools/get").toLowerCase()
    val params = _jsonBody.select("params").asOpt[JsObject].getOrElse(Json.obj())
    val ext = env.adminExtensions.extension[AiExtension].get
    val functions: Seq[LlmToolFunction] = config.functionRefs.flatMap(r => ext.states.toolFunction(r))
    val functionsMap = functions.map(f => (f.name, f)).toMap
    val mcpConnectors = config.mcpRefs.flatMap(r => ext.states.mcpConnector(r)).map(config.applyFiltersTo)
    val mcpFunctionsRawMap = mcpConnectors.flatMap(c => c.listToolsBlocking().map(f => (f.name(), (f, c)))).toMap
    val mcpFunctionsMap: Map[String, McpConnector] = mcpFunctionsRawMap.mapValues(_._2)
    val mcpFunctions: Seq[ToolSpecification] = mcpFunctionsRawMap.values.map(_._1).toSeq

    method match {
      case "tools/get" => {
        val thunks: Seq[JsValue] = functions.map { wf =>
          val required: JsArray = wf.required.map(v => JsArray(v.map(_.json))).getOrElse(JsArray(wf.parameters.value.keySet.toSeq.map(_.json)))
          Json.obj(
            "name" -> wf.name,
            "description" -> wf.description,
            "inputSchema" -> Json.obj(
              "type" -> "object",
              "properties" -> wf.parameters,
              "required" -> required,
            ),
          )
        }
        val mcpThunks = mcpFunctions.map { desc =>
          val required: Seq[String] = Option(desc.parameters().required()).map(_.asScala.toSeq).getOrElse(Seq.empty)
          val properties: JsObject = JsObject(Option(desc.parameters().properties()).map(_.asScala).getOrElse(Map.empty[String, JsonSchemaElement]).mapValues { el =>
            McpSupport.schemaToJson(el)
          })
          Json.obj(
            "name" -> desc.name,
            "description" -> desc.description,
            "inputSchema" -> Json.obj(
              "type" -> "object",
              "properties" -> properties,
              "required" -> required,
            ),
          )
        }
        val payload = JsArray(thunks ++ mcpThunks)
        BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok(payload)), None).rightf
      }
      case "tools/call" => {
        val name = params.select("name").asString
        val arguments = params.select("arguments").asOpt[JsObject].getOrElse(Json.obj())
        functionsMap.get(name) match {
          case None => mcpFunctionsMap.get(name) match {
            case None => NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(Json.obj("error" -> "bad_request", "error_details" -> "unknown function"))).leftf
            case Some(function) => {
              function.call(name, arguments.stringify).map { res =>
                BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok(
                  Json.obj(
                    "type" -> "text",
                    "text" -> res
                  )
                )), None).right
              }
            }
          }
          case Some(function) => {
            function.call(arguments.stringify).map { res =>
              BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok(
                Json.obj(
                  "type" -> "text",
                  "text" -> res
                )
              )), None).right
            }
          }
        }
      }
      case _ => NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(Json.obj("error" -> "bad_request", "error_details" -> "unknown method"))).leftf
    }
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    if (ctx.request.hasBody) {
      ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
        try {
          val jsonBody = bodyRaw.utf8String.parseJson
          val config = ctx.cachedConfig(internalName)(McpProxyEndpointConfig.format).getOrElse(McpProxyEndpointConfig.default)
          call(jsonBody, config, ctx)
        } catch {
          case e: Throwable => NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(Json.obj("error" -> "bad_request", "error_details" -> e.getMessage))).leftf
        }
      }
    } else {
      val config = ctx.cachedConfig(internalName)(McpProxyEndpointConfig.format).getOrElse(McpProxyEndpointConfig.default)
      call(Json.obj(
        "method" -> "tools/get",
        "params" -> Json.obj(),
      ), config, ctx)
    }
  }
}
*/

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

  override def name: String = "Cloud APIM - MCP SSE Endpoint"
  override def description: Option[String] = "Exposes tool functions as an MCP server using the SSE Transport".some

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
    val ext = env.adminExtensions.extension[AiExtension].get
    val mcpConnectors = config.mcpRefs.flatMap(r => ext.states.mcpConnector(r)).map(config.applyFiltersTo)
    Future.sequence(mcpConnectors.map(_.listResources(attrs))).flatMap { mcpResources =>
      val payload = Json.obj("resources" -> JsArray(mcpResources.flatten.map(McpSupport.resourceToJson)))
      session.send(id, payload)
      jsonRpcResponse(id, payload)
    }
  }

  def getPromptsList(id: Long, session: SseSession, config: McpProxyEndpointConfig, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    implicit val _attrs: TypedMap = attrs
    val ext = env.adminExtensions.extension[AiExtension].get
    val mcpConnectors = config.mcpRefs.flatMap(r => ext.states.mcpConnector(r)).map(config.applyFiltersTo)
    Future.sequence(mcpConnectors.map(_.listPrompts(attrs))).flatMap { mcpPrompts =>
      val payload = Json.obj("prompts" -> JsArray(mcpPrompts.flatten.map(McpSupport.promptToJson)))
      session.send(id, payload)
      jsonRpcResponse(id, payload)
    }
  }

  def getTemplatesList(id: Long, session: SseSession, config: McpProxyEndpointConfig, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    implicit val _attrs: TypedMap = attrs
    val ext = env.adminExtensions.extension[AiExtension].get
    val mcpConnectors = config.mcpRefs.flatMap(r => ext.states.mcpConnector(r)).map(config.applyFiltersTo)
    Future.sequence(mcpConnectors.map(_.listResourceTemplates(attrs))).flatMap { mcpResources =>
      val payload = Json.obj("templates" -> JsArray(mcpResources.flatten.map(McpSupport.resourceTemplateToJson)))
      session.send(id, payload)
      jsonRpcResponse(id, payload)
    }
  }

  def readResource(id: Long, json: JsValue, session: SseSession, config: McpProxyEndpointConfig, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    implicit val _attrs: TypedMap = attrs
    json.select("params").select("uri").asOpt[String] match {
      case None => jsonRpcResponse(id, Json.obj("error" -> "missing uri parameter"))
      case Some(uri) =>
        val ext = env.adminExtensions.extension[AiExtension].get
        val mcpConnectors = config.mcpRefs.flatMap(r => ext.states.mcpConnector(r)).map(config.applyFiltersTo)
        Future.sequence(mcpConnectors.map(c => c.listResources(attrs).map(resources => (c, resources)))).flatMap { connectorsWithResources =>
          val matchingConnectors = connectorsWithResources.collect {
            case (connector, resources) if resources.exists(_.uri() == uri) => connector
          }
          Future.sequence(matchingConnectors.map(_.readResource(uri, attrs))).flatMap { results =>
            val contents = results.flatten.headOption.map(_.contents().asScala).getOrElse(Seq.empty)
            val payload = Json.obj("contents" -> JsArray(contents.map(McpSupport.resourceContentsToJson)))
            session.send(id, payload)
            jsonRpcResponse(id, payload)
          }
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
        val ext = env.adminExtensions.extension[AiExtension].get
        val mcpConnectors = config.mcpRefs.flatMap(r => ext.states.mcpConnector(r)).map(config.applyFiltersTo)
        Future.sequence(mcpConnectors.map(c => c.listPrompts(attrs).map(prompts => (c, prompts)))).flatMap { connectorsWithPrompts =>
          val matchingConnectors = connectorsWithPrompts.collect {
            case (connector, prompts) if prompts.exists(_.name() == name) => connector
          }
          Future.sequence(matchingConnectors.map(_.getPrompt(name, arguments, attrs))).flatMap { results =>
            val result = results.flatten.headOption
            val payload = Json.obj(
              "description" -> result.map(_.description()).getOrElse("").json,
              "messages" -> JsArray(result.map(_.messages().asScala).getOrElse(Seq.empty).map { m =>
                Json.obj(
                  "role" -> m.role().name().toLowerCase,
                  "content" -> McpSupport.promptContentToJson(m.content()),
                )
              }),
            )
            session.send(id, payload)
            jsonRpcResponse(id, payload)
          }
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

  override def name: String = "Cloud APIM - MCP WebSocket Endpoint"
  override def description: Option[String] = "Exposes tool functions as an MCP server using the (non-official) WebSocket Transport".some

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
    val ext = env.adminExtensions.extension[AiExtension].get
    val mcpConnectors = config.mcpRefs.flatMap(r => ext.states.mcpConnector(r)).map(config.applyFiltersTo)
    Future.sequence(mcpConnectors.map(_.listResources(attrs)(ec, env))).map { all =>
      jsonRpcResponse(id, Json.obj("resources" -> JsArray(all.flatten.map(McpSupport.resourceToJson))))
    }(ec)
  }

  def getPromptsList(id: Long, attrs: TypedMap): Future[JsValue] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    val mcpConnectors = config.mcpRefs.flatMap(r => ext.states.mcpConnector(r)).map(config.applyFiltersTo)
    Future.sequence(mcpConnectors.map(_.listPrompts(attrs)(ec, env))).map { all =>
      jsonRpcResponse(id, Json.obj("prompts" -> JsArray(all.flatten.map(McpSupport.promptToJson))))
    }(ec)
  }

  def getTemplatesList(id: Long, attrs: TypedMap): Future[JsValue] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    val mcpConnectors = config.mcpRefs.flatMap(r => ext.states.mcpConnector(r)).map(config.applyFiltersTo)
    Future.sequence(mcpConnectors.map(_.listResourceTemplates(attrs)(ec, env))).map { all =>
      jsonRpcResponse(id, Json.obj("templates" -> JsArray(all.flatten.map(McpSupport.resourceTemplateToJson))))
    }(ec)
  }

  def readResource(id: Long, json: JsValue, attrs: TypedMap): Future[JsValue] = {
    json.select("params").select("uri").asOpt[String] match {
      case None => jsonRpcResponse(id, Json.obj("error" -> "missing uri parameter")).vfuture
      case Some(uri) =>
        val ext = env.adminExtensions.extension[AiExtension].get
        val mcpConnectors = config.mcpRefs.flatMap(r => ext.states.mcpConnector(r)).map(config.applyFiltersTo)
        Future.sequence(mcpConnectors.map(c => c.listResources(attrs)(ec, env).map(resources => (c, resources))(ec))).flatMap { connectorsWithResources =>
          val matchingConnectors = connectorsWithResources.collect {
            case (connector, resources) if resources.exists(_.uri() == uri) => connector
          }
          Future.sequence(matchingConnectors.map(_.readResource(uri, attrs)(ec, env))).map { results =>
            val contents = results.flatten.headOption.map(_.contents().asScala).getOrElse(Seq.empty)
            jsonRpcResponse(id, Json.obj("contents" -> JsArray(contents.map(McpSupport.resourceContentsToJson))))
          }(ec)
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
        val ext = env.adminExtensions.extension[AiExtension].get
        val mcpConnectors = config.mcpRefs.flatMap(r => ext.states.mcpConnector(r)).map(config.applyFiltersTo)
        Future.sequence(mcpConnectors.map(c => c.listPrompts(attrs)(ec, env).map(prompts => (c, prompts))(ec))).flatMap { connectorsWithPrompts =>
          val matchingConnectors = connectorsWithPrompts.collect {
            case (connector, prompts) if prompts.exists(_.name() == name) => connector
          }
          Future.sequence(matchingConnectors.map(_.getPrompt(name, arguments, attrs)(ec, env))).map { results =>
            val result = results.flatten.headOption
            jsonRpcResponse(id, Json.obj(
              "description" -> result.map(_.description()).getOrElse("").json,
              "messages" -> JsArray(result.map(_.messages().asScala).getOrElse(Seq.empty).map { m =>
                Json.obj(
                  "role" -> m.role().name().toLowerCase,
                  "content" -> McpSupport.promptContentToJson(m.content()),
                )
              }),
            ))
          }(ec)
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

  override def name: String = "Cloud APIM - MCP HTTP Endpoint"
  override def description: Option[String] = "Exposes tool functions as an MCP server using the (non-official) HTTP Transport".some

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
      ext.logger.info("the 'MCP HTTP Endpoint' plugin is available !")
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
    val ext = env.adminExtensions.extension[AiExtension].get
    val mcpConnectors = config.mcpRefs.flatMap(r => ext.states.mcpConnector(r)).map(config.applyFiltersTo)
    Future.sequence(mcpConnectors.map(_.listResources(attrs))).flatMap { mcpResources =>
      jsonRpcResponse(id, Json.obj("resources" -> JsArray(mcpResources.flatten.map(McpSupport.resourceToJson))))
    }
  }

  def getPromptsList(id: Long, config: McpProxyEndpointConfig, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    implicit val _attrs: TypedMap = attrs
    val ext = env.adminExtensions.extension[AiExtension].get
    val mcpConnectors = config.mcpRefs.flatMap(r => ext.states.mcpConnector(r)).map(config.applyFiltersTo)
    Future.sequence(mcpConnectors.map(_.listPrompts(attrs))).flatMap { mcpPrompts =>
      jsonRpcResponse(id, Json.obj("prompts" -> JsArray(mcpPrompts.flatten.map(McpSupport.promptToJson))))
    }
  }

  def getTemplatesList(id: Long, config: McpProxyEndpointConfig, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    implicit val _attrs: TypedMap = attrs
    val ext = env.adminExtensions.extension[AiExtension].get
    val mcpConnectors = config.mcpRefs.flatMap(r => ext.states.mcpConnector(r)).map(config.applyFiltersTo)
    Future.sequence(mcpConnectors.map(_.listResourceTemplates(attrs))).flatMap { mcpResources =>
      jsonRpcResponse(id, Json.obj("templates" -> JsArray(mcpResources.flatten.map(McpSupport.resourceTemplateToJson))))
    }
  }

  def readResource(id: Long, json: JsValue, config: McpProxyEndpointConfig, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    implicit val _attrs: TypedMap = attrs
    json.select("params").select("uri").asOpt[String] match {
      case None => jsonRpcResponse(id, Json.obj("error" -> "missing uri parameter"))
      case Some(uri) =>
        val ext = env.adminExtensions.extension[AiExtension].get
        val mcpConnectors = config.mcpRefs.flatMap(r => ext.states.mcpConnector(r)).map(config.applyFiltersTo)
        Future.sequence(mcpConnectors.map(c => c.listResources(attrs).map(resources => (c, resources)))).flatMap { connectorsWithResources =>
          val matchingConnectors = connectorsWithResources.collect {
            case (connector, resources) if resources.exists(_.uri() == uri) => connector
          }
          Future.sequence(matchingConnectors.map(_.readResource(uri, attrs))).flatMap { results =>
            val contents = results.flatten.headOption.map(_.contents().asScala).getOrElse(Seq.empty)
            jsonRpcResponse(id, Json.obj("contents" -> JsArray(contents.map(McpSupport.resourceContentsToJson))))
          }
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
        val ext = env.adminExtensions.extension[AiExtension].get
        val mcpConnectors = config.mcpRefs.flatMap(r => ext.states.mcpConnector(r)).map(config.applyFiltersTo)
        Future.sequence(mcpConnectors.map(c => c.listPrompts(attrs).map(prompts => (c, prompts)))).flatMap { connectorsWithPrompts =>
          val matchingConnectors = connectorsWithPrompts.collect {
            case (connector, prompts) if prompts.exists(_.name() == name) => connector
          }
          Future.sequence(matchingConnectors.map(_.getPrompt(name, arguments, attrs))).flatMap { results =>
            val result = results.flatten.headOption
            jsonRpcResponse(id, Json.obj(
              "description" -> result.map(_.description()).getOrElse("").json,
              "messages" -> JsArray(result.map(_.messages().asScala).getOrElse(Seq.empty).map { m =>
                Json.obj(
                  "role" -> m.role().name().toLowerCase,
                  "content" -> McpSupport.promptContentToJson(m.content()),
                )
              }),
            ))
          }
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
  authModuleRef: Option[String] = None,
  resource: Option[String] = None,
  scopesSupported: Option[Seq[String]] = None,
  bearerMethodsSupported: Seq[String] = Seq("header", "query"),
  resourceDocumentation: Option[String] = None,
) extends NgPluginConfig {
  def json: JsValue = McpProtectedResourceMetadataConfig.format.writes(this)
}

object McpProtectedResourceMetadataConfig {
  val configFlow: Seq[String] = Seq(
    "auth_module_ref",
    "resource",
    "scopes_supported",
    "bearer_methods_supported",
    "resource_documentation",
  )
  val configSchema: Option[JsObject] = Some(Json.obj(
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
      "auth_module_ref" -> o.authModuleRef.map(_.json).getOrElse(JsNull).asValue,
      "resource" -> o.resource.map(_.json).getOrElse(JsNull).asValue,
      "scopes_supported" -> o.scopesSupported.map(s => JsArray(s.map(_.json))).getOrElse(JsNull).asValue,
      "bearer_methods_supported" -> o.bearerMethodsSupported,
      "resource_documentation" -> o.resourceDocumentation.map(_.json).getOrElse(JsNull).asValue,
    )
    override def reads(json: JsValue): JsResult[McpProtectedResourceMetadataConfig] = Try {
      McpProtectedResourceMetadataConfig(
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
    config.authModuleRef match {
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
