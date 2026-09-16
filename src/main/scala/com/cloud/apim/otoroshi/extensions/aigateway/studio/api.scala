package com.cloud.apim.otoroshi.extensions.aigateway.studio

import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiProvider, ApikeyOwner}
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import otoroshi.api.{Resource, WriteAction}
import otoroshi.env.Env
import otoroshi.events.{AdminApiEvent, Audit}
import otoroshi.models.ApiKey
import otoroshi.next.analytics.queries.{AnalyticsRuntime, Filters}
import otoroshi.next.extensions.*
import otoroshi.security.IdGenerator
import otoroshi.utils.TypedMap
import otoroshi.utils.http.RequestImplicits.*
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.Logger
import play.api.libs.json.*
import play.api.mvc.{RequestHeader, Result, Results}

import java.text.Normalizer
import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.util.control.NoStackTrace

final case class AiStudioApiError(status: Int, error: String, description: String) extends RuntimeException(description) with NoStackTrace {
  def result: Result = Results.Status(status)(Json.obj("error" -> error, "error_description" -> description))
}

object AiStudioApiError {
  def badRequest(description: String): AiStudioApiError = AiStudioApiError(400, "bad_request", description)
  def notFound(description: String): AiStudioApiError = AiStudioApiError(404, "not_found", description)
  def conflict(description: String): AiStudioApiError = AiStudioApiError(409, "conflict", description)
}

final case class AiStudioApiRequest(ctx: AdminExtensionRouterContext[AdminExtensionAdminApiRoute], req: RequestHeader, apikey: ApiKey, body: JsValue) {
  def param(name: String): String = ctx.named(name).getOrElse("--")
  def flag(name: String): Boolean = req.getQueryString(name).contains("true")
  def form: JsObject = body match {
    case o: JsObject => o
    case JsNull      => Json.obj()
    case _           => throw AiStudioApiError.badRequest("the body must be a json object")
  }
}

/**
 * Reads the fields of an api body the way the studio forms read their inputs: absent means "keep the
 * current value", `null` means "no value".
 */
private object StudioForm {

  import AiStudioApiError.badRequest

  def has(o: JsObject, key: String): Boolean = o.value.contains(key)

  def string(o: JsObject, key: String): Option[String] = o.value.get(key).flatMap {
    case JsString(s)  => Some(s)
    case JsNumber(n)  => Some(n.toString)
    case JsBoolean(b) => Some(b.toString)
    case JsNull       => None
    case _            => throw badRequest(s"'$key' must be a string")
  }

  def boolean(o: JsObject, key: String): Option[Boolean] = o.value.get(key).flatMap {
    case JsBoolean(b)     => Some(b)
    case JsString("true")  => Some(true)
    case JsString("false") => Some(false)
    case JsNull           => None
    case _                => throw badRequest(s"'$key' must be a boolean")
  }

  def number(o: JsObject, key: String): Option[BigDecimal] = o.value.get(key).flatMap {
    case JsNumber(n)                 => Some(n)
    case JsString(s) if s.trim.isEmpty => None
    case JsString(s)                 => Some(Try(BigDecimal(s.trim)).getOrElse(throw badRequest(s"'$key' must be a number")))
    case JsNull                      => None
    case _                           => throw badRequest(s"'$key' must be a number")
  }

  def strings(o: JsObject, key: String): Option[Seq[String]] = o.value.get(key).map {
    case JsArray(values) => values.toSeq.map {
      case JsString(s) => s
      case _           => throw badRequest(s"'$key' must be an array of strings")
    }
    case JsNull          => Seq.empty
    case _               => throw badRequest(s"'$key' must be an array of strings")
  }

  def obj(o: JsObject, key: String): Option[JsObject] = o.value.get(key).flatMap {
    case v: JsObject => Some(v)
    case JsNull      => None
    case _           => throw badRequest(s"'$key' must be an object")
  }
}

/**
 * Admin api of AI Studio, scoped by workspace, served under `/api/extensions/cloud-apim/extensions/ai-extension/studio`.
 *
 * It produces exactly the entities the studio front (`ui/ai-studio/src/lib`) creates through the generic admin
 * api: every write goes through the same resource access (json format, write validation, audit event), with the
 * same ids, metadata, locations and plugin configs. Authentication, tenants and rights are the ones of the
 * Otoroshi admin api serving the extension routes.
 */
class AiStudioApi(env: Env, ext: AiExtension) {

  import AiStudioApiError.*
  import StudioForm.*

  private given ec: ExecutionContext = env.otoroshiExecutionContext
  private given mat: Materializer = env.otoroshiMaterializer
  private given ev: Env = env

  private val logger = Logger("otoroshi-extension-ai-studio-api")

  val apiPath = "/api/extensions/cloud-apim/extensions/ai-extension/studio"

  /////////////////////////////////////////////////////////////////////////////////////////////////
  // entities, through the resource access of the admin api
  /////////////////////////////////////////////////////////////////////////////////////////////////

  private final class Entities(group: String, plural: String) {

    private def lookup: Option[Resource] = env.allResources.resources.find(r => r.group == group && r.pluralName == plural)

    private def resource: Resource = lookup.getOrElse(throw AiStudioApiError(500, "internal_error", s"the resource $group/$plural is not available"))

    def idOf(entity: JsValue): String = entity.select(resource.access.idFieldName()).asOptString.getOrElse("")

    // datastore reads, not the in-memory state, so what was just written is always returned
    def all(): Future[Seq[JsObject]] = lookup match {
      case None    => Seq.empty[JsObject].vfuture
      case Some(r) => r.access.findAll(r.version.name).map(_.collect { case o: JsObject => o })
    }

    def list(wsId: String): Future[Seq[JsObject]] = all().map(_.filter(e => metaOf(e, MetaWorkspace).contains(wsId)))

    def get(id: String): Future[Option[JsObject]] = {
      val r = resource
      r.access.findOne(r.version.name, id).map(_.collect { case o: JsObject => o })
    }

    def template(params: Map[String, String] = Map.empty): JsObject = {
      val r = resource
      r.access.template(r.version.name, params, None).asOpt[JsObject].getOrElse(Json.obj())
    }

    def create(entity: JsObject)(using call: AiStudioApiRequest): Future[JsObject] = write(entity, WriteAction.Create)

    def update(entity: JsObject)(using call: AiStudioApiRequest): Future[JsObject] = write(entity, WriteAction.Update)

    private def write(entity: JsObject, action: WriteAction)(using call: AiStudioApiRequest): Future[JsObject] = {
      val r = resource
      val version = r.version.name
      val id = idOf(entity)
      r.access.findOne(version, id).flatMap { old =>
        (action, old) match {
          case (WriteAction.Create, Some(_)) => Future.failed(conflict(s"${r.singularName} '$id' already exists"))
          case (WriteAction.Update, None)    => Future.failed(notFound(s"${r.singularName} '$id' not found"))
          case _ =>
            r.access.validateToJson(entity, r.singularName, Right(None)) match {
              case JsError(errors) =>
                Future.failed(badRequest(s"invalid ${r.singularName}: ${errors.map { case (path, errs) => s"$path ${errs.flatMap(_.messages).mkString(", ")}" }.mkString(", ")}"))
              case JsSuccess(_, _) =>
                val update = action == WriteAction.Update
                r.access.create(version, r.singularName, if (update) id.some else None, entity, action, old).flatMap {
                  case Left(err) => Future.failed(badRequest(s"invalid ${r.singularName}: ${err.stringify}"))
                  case Right(saved) =>
                    audit(s"${if (update) "UPDATE" else "CREATE"}_${r.singularName.toUpperCase}", s"AI Studio api ${if (update) "updated" else "created"} a ${r.singularName}", entity)
                    saved.asObject.vfuture
                }
            }
        }
      }
    }

    def delete(id: String)(using call: AiStudioApiRequest): Future[Unit] = {
      val r = resource
      r.access.deleteOne(r.version.name, id, r.singularName).flatMap {
        case Left(err) => Future.failed(badRequest(s"unable to delete ${r.singularName} '$id': ${err.stringify}"))
        case Right(_) =>
          audit(s"DELETE_${r.singularName.toUpperCase}", s"AI Studio api deleted a ${r.singularName}", Json.obj("id" -> id))
          ().vfuture
      }
    }
  }

  private def audit(action: String, message: String, meta: JsValue)(using call: AiStudioApiRequest): Unit = {
    Audit.send(AdminApiEvent(
      env.snowflakeGenerator.nextIdStr(),
      env.env,
      Some(call.apikey),
      None,
      action,
      message,
      call.req.theIpAddress,
      call.req.theUserAgent,
      meta
    ))
  }

  private val AiGroup = "ai-gateway.extensions.cloud-apim.com"
  private val Routes = new Entities("proxy.otoroshi.io", "routes")
  private val Apikeys = new Entities("apim.otoroshi.io", "apikeys")
  private val Teams = new Entities("organize.otoroshi.io", "teams")
  private val Providers = new Entities(AiGroup, "providers")
  private val Contexts = new Entities(AiGroup, "prompt-contexts")
  private val Functions = new Entities(AiGroup, "tool-functions")
  private val McpConnectors = new Entities(AiGroup, "mcp-connectors")
  private val McpVirtualServers = new Entities(AiGroup, "mcp-virtual-servers")
  private val SearchEngines = new Entities(AiGroup, "search-engines")
  private val Budgets = new Entities(AiGroup, "ai-budgets")

  private final case class Modality(id: String, entities: Entities, refs: Option[String], kind: String)

  private val modalities: Seq[Modality] = Seq(
    Modality("text", Providers, Some("language_model_refs"), "provider"),
    Modality("embedding", new Entities(AiGroup, "embedding-models"), Some("embedding_model_refs"), "embedding-model"),
    Modality("image", new Entities(AiGroup, "image-models"), Some("image_model_refs"), "image-model"),
    Modality("audio", new Entities(AiGroup, "audio-models"), Some("audio_model_refs"), "audio-model"),
    Modality("moderation", new Entities(AiGroup, "moderation-models"), Some("moderation_model_refs"), "moderation-model"),
    Modality("ocr", new Entities(AiGroup, "ocr-models"), Some("ocr_model_refs"), "ocr-model"),
    Modality("video", new Entities(AiGroup, "video-models"), None, "video-model"),
  )

  /////////////////////////////////////////////////////////////////////////////////////////////////
  // helpers (same as ui/ai-studio/src/lib/entities.js and workspaces.js)
  /////////////////////////////////////////////////////////////////////////////////////////////////

  private val MetaFlag = "ai_studio"
  private val MetaWorkspace = "ai_studio_workspace"
  private val MetaKind = "ai_studio_kind"
  private val MetaConnection = "ai_studio_connection"
  private val MetaDisabled = "ai_studio_disabled"
  private val MetaKeyLimit = "ai_studio_key_limit"
  // the person the usage of an api key counts for, in the analytics and the budgets scoped to users
  private val MetaOwner = ApikeyOwner.MetadataKey
  private val OwnerPattern = "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+$".r

  private val OpenAiCompatPlugin = "cp:otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.OpenAiCompatApi"
  private val ConsumerPresetPlugin = "cp:otoroshi.next.plugins.MandatoryConsumerPreset"
  private val IpAllowPlugin = "cp:otoroshi.next.plugins.IpAddressAllowedList"
  private val IpBlockPlugin = "cp:otoroshi.next.plugins.IpAddressBlockList"
  private val LegacyStudioConsumerPlugin = "cp:otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.AiStudioConsumer"

  private val VirtualKinds = Seq("loadbalancer", "otoroshi")

  private val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray.map(_.toString)

  private def randomId(size: Int): String = IdGenerator.token(alphabet, size)

  private def stripAccents(value: String): String = Normalizer.normalize(value.toLowerCase, Normalizer.Form.NFD).replaceAll("[\\u0300-\\u036f]", "")

  private def slugify(value: String): String = stripAccents(value).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "").take(48)

  // connection names become the model prefix (`<name>/<model>`)
  private def connectionName(value: String): String =
    stripAccents(value).replaceAll("[^a-z0-9_]+", "_").replaceAll("_+", "_").replaceAll("^_+|_+$", "").take(40)

  private def teamIdOf(wsId: String): String = s"team_ai_studio_$wsId"
  private def routeIdOf(wsId: String): String = s"route_ai_studio_$wsId"
  private def consumerTagOf(wsId: String): String = s"ai_studio_ws_$wsId"

  private def metaOf(entity: JsValue, key: String): Option[String] = entity.select("metadata").select(key).asOptString

  private def objOf(value: JsLookupResult): JsObject = value.asOpt[JsObject].getOrElse(Json.obj())

  private def stringsOf(value: JsLookupResult): Seq[String] = value.asOpt[Seq[String]].getOrElse(Seq.empty)

  private def optString(value: Option[String]): JsValue = value.map(JsString.apply).getOrElse(JsNull)

  private def nonEmptyString(value: JsLookupResult): Option[String] = value.asOptString.filter(_.nonEmpty)

  private def workspaceMetadata(wsId: String, kind: String, extra: (String, String)*): JsObject =
    Json.obj(MetaFlag -> "true", MetaWorkspace -> wsId, MetaKind -> kind) ++ JsObject(extra.map { case (k, v) => k -> JsString(v) })

  private def sequentially[A](items: Seq[A])(f: A => Future[Any]): Future[Unit] =
    items.foldLeft(().vfuture) { (acc, item) => acc.flatMap(_ => f(item).map(_ => ())) }

  private def nowIso(): String = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString

  /////////////////////////////////////////////////////////////////////////////////////////////////
  // workspaces
  /////////////////////////////////////////////////////////////////////////////////////////////////

  private final case class Workspace(id: String, route: JsObject) {
    // entities of the workspace get the location of its route (the workspace team and the teams of its creator)
    def location: JsObject = route.select("_loc").asOpt[JsObject].getOrElse(Json.obj("tenant" -> "default", "teams" -> Json.arr(teamIdOf(id))))
    def tenant: String = location.select("tenant").asOptString.getOrElse("default")
    def compat: JsObject = compatConfigOf(route)
  }

  private def workspace(wsId: String): Future[Workspace] = Routes.get(routeIdOf(wsId)).flatMap {
    case Some(route) if metaOf(route, MetaWorkspace).contains(wsId) => Workspace(wsId, route).vfuture
    case _ => Future.failed(notFound("workspace not found"))
  }

  private def workspaceRoutes(): Future[Seq[JsObject]] = Routes.all().map(_.filter(r => metaOf(r, MetaKind).contains("workspace")))

  private def pluginsOf(route: JsObject): Seq[JsObject] = route.select("plugins").asOpt[Seq[JsObject]].getOrElse(Seq.empty)

  private def findPlugin(route: JsObject, plugin: String): Option[JsObject] = pluginsOf(route).find(_.select("plugin").asOptString.contains(plugin))

  private def compatConfigOf(route: JsObject): JsObject = findPlugin(route, OpenAiCompatPlugin).map(p => objOf(p.select("config"))).getOrElse(Json.obj())

  private def pluginInstance(plugin: String, config: JsObject, index: JsObject = Json.obj(), enabled: Boolean = true): JsObject = Json.obj(
    "enabled" -> enabled,
    "debug" -> false,
    "plugin" -> plugin,
    "include" -> Json.arr(),
    "exclude" -> Json.arr(),
    "config" -> config,
    "bound_listeners" -> Json.arr(),
    "plugin_index" -> index,
  )

  private def updatePlugin(route: JsObject, plugin: String)(f: JsObject => JsObject): JsObject =
    route ++ Json.obj("plugins" -> pluginsOf(route).map(p => if (p.select("plugin").asOptString.contains(plugin)) f(p) else p))

  // the plugins every workspace route carries, the ip lists being only enabled with at least one address
  private def ensureStudioPlugins(route: JsObject): JsObject = {
    val plugins = pluginsOf(route).filterNot(_.select("plugin").asOptString.contains(LegacyStudioConsumerPlugin))
    def has(plugin: String) = plugins.exists(_.select("plugin").asOptString.contains(plugin))
    val head = Seq(
      Option.when(!has(IpAllowPlugin))(pluginInstance(IpAllowPlugin, Json.obj("addresses" -> Json.arr()), Json.obj("validate_access" -> 0), enabled = false)),
      Option.when(!has(IpBlockPlugin))(pluginInstance(IpBlockPlugin, Json.obj("addresses" -> Json.arr()), Json.obj("validate_access" -> 1), enabled = false)),
    ).flatten
    route ++ Json.obj("plugins" -> (head ++ plugins))
  }

  private def ipAddressesOf(route: JsObject, plugin: String): Seq[String] = findPlugin(route, plugin).map(p => stringsOf(p.select("config").select("addresses"))).getOrElse(Seq.empty)

  private def setIpAddresses(route: JsObject, plugin: String, addresses: Seq[String]): JsObject =
    updatePlugin(ensureStudioPlugins(route), plugin) { p =>
      p ++ Json.obj("config" -> (objOf(p.select("config")) ++ Json.obj("addresses" -> addresses)), "enabled" -> addresses.nonEmpty)
    }

  private def setOpenAiConfig(route: JsObject, patch: JsObject): JsObject =
    updatePlugin(route, OpenAiCompatPlugin)(p => p ++ Json.obj("config" -> (objOf(p.select("config")) ++ patch)))

  private def exposureFor(slug: String, config: AiStudioConfig): (String, String) =
    if (config.exposure == "path") (config.domain(env), s"/$slug${config.routePath}")
    else (s"$slug.${config.domain(env)}", config.routePath)

  private def domainOf(route: JsObject): String = route.select("frontend").select("domains").asOpt[Seq[String]].flatMap(_.headOption).getOrElse("")

  private def baseUrlOf(route: JsObject, config: AiStudioConfig): String = {
    val domain = domainOf(route)
    val idx = domain.indexOf('/')
    val (host, path) = if (idx > -1) (domain.substring(0, idx), domain.substring(idx)) else (domain, "")
    s"${config.publicScheme(env)}://$host${config.publicPort(env)}$path"
  }

  private def slugOf(route: JsObject, config: AiStudioConfig): String = {
    val domain = domainOf(route)
    if (config.exposure == "path") domain.split("/").lift(1).getOrElse("")
    else domain.split("/").headOption.getOrElse("").split("\\.").headOption.getOrElse("")
  }

  private def workspaceJson(route: JsObject, config: AiStudioConfig): JsObject = {
    val wsId = metaOf(route, MetaWorkspace).getOrElse("")
    val compat = compatConfigOf(route)
    val client = route.select("backend").select("client")
    Json.obj(
      "id" -> wsId,
      "name" -> route.select("name").asOptString.getOrElse(wsId),
      "description" -> route.select("description").asOptString.getOrElse(""),
      "enabled" -> route.select("enabled").asOptBoolean.getOrElse(true),
      "slug" -> slugOf(route, config),
      "base_url" -> baseUrlOf(route, config),
      "tenant" -> route.select("_loc").select("tenant").asOptString.getOrElse("default"),
      "route_id" -> routeIdOf(wsId),
      "team_id" -> teamIdOf(wsId),
      "settings" -> Json.obj(
        "call_timeout" -> client.select("call_timeout").asOpt[Long].filter(_ > 0).getOrElse(600000L),
        "global_timeout" -> client.select("global_timeout").asOpt[Long].filter(_ > 0).getOrElse(600000L),
        "max_size_upload" -> compat.select("max_size_upload").asOpt[Long].filter(_ > 0).getOrElse(104857600L),
        "decode_images" -> compat.select("decode_images").asOpt[Boolean].getOrElse(false),
        "allowed_ip_addresses" -> ipAddressesOf(route, IpAllowPlugin),
        "blocked_ip_addresses" -> ipAddressesOf(route, IpBlockPlugin),
      ),
    )
  }

  private def createWorkspace(form: JsObject)(using call: AiStudioApiRequest): Future[JsObject] = {
    val config = AiStudioConfig.current(env)
    val name = string(form, "name").map(_.trim).filter(_.nonEmpty).getOrElse(throw badRequest("'name' is required"))
    val description = string(form, "description").getOrElse("")
    val wsId = randomId(12)
    val finalSlug = Some(slugify(string(form, "slug").map(_.toLowerCase).filter(_.nonEmpty).getOrElse(name))).filter(_.nonEmpty).getOrElse(wsId)
    val tenant = call.req.headers.get("Otoroshi-Tenant").map(_.trim).filter(_.nonEmpty).getOrElse("default")
    val (host, path) = exposureFor(finalSlug, config)
    workspaceRoutes().flatMap { existing =>
      if (existing.exists(r => slugOf(r, config) == finalSlug)) {
        Future.failed(conflict(s"a workspace already uses '$finalSlug'"))
      } else {
        Teams.create(Json.obj(
          "id" -> teamIdOf(wsId),
          "tenant" -> tenant,
          "name" -> s"AI Studio - $name",
          "description" -> description,
          "tags" -> Json.arr(),
          "metadata" -> workspaceMetadata(wsId, "team"),
        )).flatMap { _ =>
          val template = Routes.template()
          val frontend = objOf(template.select("frontend"))
          val backend = objOf(template.select("backend"))
          val route = template ++ Json.obj(
            "_loc" -> Json.obj("tenant" -> tenant, "teams" -> Json.arr(teamIdOf(wsId))),
            "id" -> routeIdOf(wsId),
            "name" -> name,
            "description" -> description,
            "tags" -> Json.arr(),
            "metadata" -> workspaceMetadata(wsId, "workspace"),
            "enabled" -> true,
            "debug_flow" -> false,
            "capture" -> false,
            "export_reporting" -> false,
            "groups" -> Json.arr("default"),
            "frontend" -> (frontend ++ Json.obj("domains" -> Json.arr(s"$host$path"), "strip_path" -> true, "exact" -> false)),
            "backend" -> (backend ++ Json.obj(
              "targets" -> Json.arr(Json.obj(
                "id" -> "target_1",
                "hostname" -> "request.otoroshi.io",
                "port" -> 443,
                "tls" -> true,
                "weight" -> 1,
                "backup" -> false,
                "predicate" -> Json.obj("type" -> "AlwaysMatch"),
                "protocol" -> "HTTP/1.1",
                "ip_address" -> JsNull,
                "tls_config" -> Json.obj("certs" -> Json.arr(), "trusted_certs" -> Json.arr(), "enabled" -> false, "loose" -> false, "trust_all" -> false),
              )),
              "root" -> "/",
              "rewrite" -> false,
              "load_balancing" -> Json.obj("type" -> "RoundRobin"),
              "client" -> (objOf(backend.select("client")) ++ Json.obj(
                "call_timeout" -> 600000,
                "call_and_stream_timeout" -> 600000,
                "global_timeout" -> 600000,
                "idle_timeout" -> 600000,
              )),
            )),
            "plugins" -> Json.arr(
              pluginInstance(IpAllowPlugin, Json.obj("addresses" -> Json.arr()), Json.obj("validate_access" -> 0), enabled = false),
              pluginInstance(IpBlockPlugin, Json.obj("addresses" -> Json.arr()), Json.obj("validate_access" -> 1), enabled = false),
              pluginInstance(ConsumerPresetPlugin, Json.obj("ref" -> JsNull, "tags" -> Json.arr(consumerTagOf(wsId)))),
              pluginInstance(OpenAiCompatPlugin, Json.obj(
                "language_model_refs" -> Json.arr(),
                "audio_model_refs" -> Json.arr(),
                "image_model_refs" -> Json.arr(),
                "ocr_model_refs" -> Json.arr(),
                "embedding_model_refs" -> Json.arr(),
                "moderation_model_refs" -> Json.arr(),
                "context_refs" -> Json.arr(),
                "max_size_upload" -> 104857600,
                "decode_images" -> false,
                "use_open_response_for_responses" -> false,
                "response_headers" -> false,
                "response_headers_include_costs" -> true,
              )),
            ),
          )
          Routes.create(route).recoverWith { case e: Throwable =>
            Teams.delete(teamIdOf(wsId)).recover { case _ => () }.flatMap(_ => Future.failed(e))
          }.map(created => workspaceJson(created, config))
        }
      }
    }
  }

  private def updateWorkspaceRoute(wsId: String)(mutate: JsObject => JsObject)(using call: AiStudioApiRequest): Future[JsObject] =
    workspace(wsId).flatMap(ws => Routes.update(ensureStudioPlugins(mutate(ws.route))))

  private def updateWorkspace(wsId: String, form: JsObject)(using call: AiStudioApiRequest): Future[JsObject] = {
    val config = AiStudioConfig.current(env)
    for {
      ws <- workspace(wsId)
      others <- workspaceRoutes().map(_.filterNot(r => metaOf(r, MetaWorkspace).contains(wsId)))
      current = workspaceJson(ws.route, config)
      settings = current.select("settings")
      name = string(form, "name").getOrElse(current.select("name").asString).trim
      _ = if (name.isEmpty) throw badRequest("'name' cannot be empty")
      description = if (has(form, "description")) string(form, "description").getOrElse("") else current.select("description").asString
      currentSlug = slugOf(ws.route, config)
      slug = Some(slugify(string(form, "slug").getOrElse(currentSlug))).filter(_.nonEmpty).getOrElse(currentSlug)
      _ = if (slug != currentSlug && others.exists(r => slugOf(r, config) == slug)) throw conflict(s"a workspace already uses '$slug'")
      callTimeout = number(form, "call_timeout").map(_.toLong).getOrElse(settings.select("call_timeout").as[Long])
      globalTimeout = number(form, "global_timeout").map(_.toLong).getOrElse(settings.select("global_timeout").as[Long])
      maxSizeUpload = number(form, "max_size_upload").map(_.toLong).getOrElse(settings.select("max_size_upload").as[Long])
      decodeImages = boolean(form, "decode_images").getOrElse(settings.select("decode_images").as[Boolean])
      allowed = strings(form, "allowed_ip_addresses").getOrElse(stringsOf(settings.select("allowed_ip_addresses")))
      blocked = strings(form, "blocked_ip_addresses").getOrElse(stringsOf(settings.select("blocked_ip_addresses")))
      (host, path) = exposureFor(slug, config)
      route <- updateWorkspaceRoute(wsId) { r =>
        val backend = objOf(r.select("backend"))
        val updated = r ++ Json.obj(
          "name" -> name,
          "description" -> description,
          "enabled" -> boolean(form, "enabled").getOrElse(current.select("enabled").as[Boolean]),
          "frontend" -> (objOf(r.select("frontend")) ++ Json.obj("domains" -> Json.arr(s"$host$path"))),
          "backend" -> (backend ++ Json.obj("client" -> (objOf(backend.select("client")) ++ Json.obj(
            "call_timeout" -> callTimeout,
            "call_and_stream_timeout" -> callTimeout,
            "global_timeout" -> globalTimeout,
          )))),
        )
        val withCompat = setOpenAiConfig(updated, Json.obj("max_size_upload" -> maxSizeUpload, "decode_images" -> decodeImages))
        setIpAddresses(setIpAddresses(withCompat, IpAllowPlugin, allowed), IpBlockPlugin, blocked)
      }
      team <- Teams.get(teamIdOf(wsId)).recover { case _ => None }
      _ <- team match {
        case None => ().vfuture
        case Some(t) => Teams.update(t ++ Json.obj("name" -> s"AI Studio - $name", "description" -> description))
      }
    } yield workspaceJson(route, config)
  }

  private def deleteWorkspace(wsId: String)(using call: AiStudioApiRequest): Future[Unit] = {
    val kinds = Seq(Apikeys, Budgets) ++ modalities.map(_.entities) ++ Seq(Contexts, Functions, McpConnectors, SearchEngines)
    for {
      _ <- workspace(wsId)
      // the route first so nothing can be served while the rest is removed
      _ <- Routes.delete(routeIdOf(wsId)).recover { case _ => () }
      _ <- sequentially(kinds) { kind =>
        kind.list(wsId).flatMap(items => Future.sequence(items.map(item => kind.delete(kind.idOf(item)))))
      }
      _ <- Teams.delete(teamIdOf(wsId)).recover { case _ => () }
    } yield ()
  }

  // recompute the refs of the OpenAI compatible plugin from the entities tagged for this workspace
  private def syncWorkspaceRefs(wsId: String)(using call: AiStudioApiRequest): Future[JsObject] = {
    val withRefs = modalities.filter(_.refs.isDefined)
    for {
      lists <- Future.sequence(withRefs.map(_.entities.list(wsId)))
      contexts <- Contexts.list(wsId)
      route <- updateWorkspaceRoute(wsId) { route =>
        val current = compatConfigOf(route)
        // keep the existing order (the first text provider serves requests without an explicit model)
        def merge(existing: Seq[String], ids: Seq[String]): Seq[String] = existing.filter(ids.contains) ++ ids.filterNot(existing.contains)
        val refs = withRefs.zip(lists).map { case (m, list) =>
          val enabled = list.filterNot(e => metaOf(e, MetaDisabled).contains("true"))
          m.refs.get -> Json.toJson(merge(stringsOf(current.select(m.refs.get)), enabled.map(m.entities.idOf)))
        }
        setOpenAiConfig(route, JsObject(refs) ++ Json.obj("context_refs" -> merge(stringsOf(current.select("context_refs")), contexts.map(Contexts.idOf))))
      }
    } yield route
  }

  /////////////////////////////////////////////////////////////////////////////////////////////////
  // providers (connections, see ui/ai-studio/src/lib/connections.js)
  /////////////////////////////////////////////////////////////////////////////////////////////////

  private final case class ModalityConf(enabled: Boolean, model: String, sttModel: Option[String])

  private final case class Connection(
    id: String,
    name: String,
    kind: String,
    description: String,
    connection: JsObject,
    baseUrl: String,
    token: String,
    timeout: BigDecimal,
    enabled: Boolean,
    fields: Map[String, JsValue],
    modalities: Map[String, ModalityConf],
    entities: Map[String, JsObject],
  )

  private def catalogEntry(kind: String): Option[JsObject] = AiStudioCatalog.json.value.collectFirst {
    case o: JsObject if o.select("id").asOptString.contains(kind) => o
  }

  private def capabilitiesOf(entry: JsObject): Seq[String] = stringsOf(entry.select("capabilities"))

  private def catalogFields(entry: Option[JsObject]): Seq[JsObject] = entry.flatMap(_.select("fields").asOpt[Seq[JsObject]]).getOrElse(Seq.empty)

  private def modelOfEntity(modality: String, entity: JsObject): Option[String] = {
    val config = entity.select("config")
    val options = config.select("options")
    modality match {
      case "text"  => nonEmptyString(entity.select("options").select("model"))
      case "image" => nonEmptyString(options.select("generation").select("model")).orElse(nonEmptyString(options.select("model")))
      case "audio" => nonEmptyString(config.select("tts").select("model")).orElse(nonEmptyString(options.select("tts").select("model")))
      case _       => nonEmptyString(options.select("model"))
    }
  }

  private def sttModelOfEntity(entity: JsObject): Option[String] = {
    val config = entity.select("config")
    nonEmptyString(config.select("stt").select("model"))
      .orElse(nonEmptyString(config.select("stt").select("model_id")))
      .orElse(nonEmptyString(config.select("options").select("stt").select("model")))
  }

  private def connectionOfEntity(modality: String, entity: JsObject): JsObject =
    if (modality == "text") objOf(entity.select("connection")) else objOf(entity.select("config").select("connection"))

  private def listConnections(wsId: String): Future[Seq[Connection]] =
    Future.sequence(modalities.map(m => m.entities.list(wsId).map(list => (m, list)))).map { lists =>
      val byId = scala.collection.mutable.LinkedHashMap.empty[String, Connection]
      lists.foreach { case (m, list) =>
        list.foreach { entity =>
          val id = metaOf(entity, MetaConnection).getOrElse(m.entities.idOf(entity))
          val base = byId.getOrElse(id, Connection(
            id = id,
            name = entity.select("name").asOptString.getOrElse(id),
            kind = entity.select("provider").asOptString.getOrElse("--"),
            description = entity.select("description").asOptString.getOrElse(""),
            connection = Json.obj(),
            baseUrl = "",
            token = "",
            timeout = BigDecimal(180000),
            enabled = true,
            fields = Map.empty,
            modalities = Map.empty,
            entities = Map.empty,
          ))
          val conf = ModalityConf(true, modelOfEntity(m.id, entity).getOrElse(""), Option.when(m.id == "audio")(sttModelOfEntity(entity).getOrElse("")))
          val withModality = base.copy(entities = base.entities + (m.id -> entity), modalities = base.modalities + (m.id -> conf))
          // the text provider (or the first entity found) carries the connection settings
          val conn = if (m.id == "text" || base.entities.isEmpty) {
            val c = connectionOfEntity(m.id, entity)
            withModality.copy(
              connection = c,
              baseUrl = nonEmptyString(c.select("base_url")).orElse(nonEmptyString(c.select("base_domain"))).getOrElse(""),
              token = nonEmptyString(c.select("token")).orElse(nonEmptyString(c.select("api_key"))).getOrElse(""),
              timeout = c.select("timeout").asOpt[BigDecimal].filter(_ != 0).getOrElse(BigDecimal(180000)),
              enabled = !metaOf(entity, MetaDisabled).contains("true"),
            )
          } else withModality
          byId.put(id, conn)
        }
      }
      byId.values.toSeq.map { conn =>
        val entry = catalogEntry(conn.kind)
        conn.copy(fields = catalogFields(entry).map { f =>
          val name = f.select("name").asString
          name -> conn.connection.select(name).asOpt[JsValue].filter(v => v != JsNull && v != JsString("")).getOrElse(JsString(f.select("default").asOptString.getOrElse("")))
        }.toMap)
      }.sortBy(_.name)
    }

  private def connectionJson(conn: Connection): JsObject = Json.obj(
    "id" -> conn.id,
    "name" -> conn.name,
    "kind" -> conn.kind,
    "description" -> conn.description,
    "enabled" -> conn.enabled,
    "base_url" -> conn.baseUrl,
    "token" -> conn.token,
    "timeout" -> conn.timeout,
    "fields" -> JsObject(conn.fields),
    "modalities" -> JsObject(conn.modalities.toSeq.sortBy(m => modalities.indexWhere(_.id == m._1)).map { case (id, m) =>
      id -> (Json.obj("enabled" -> m.enabled, "model" -> m.model) ++ m.sttModel.map(s => Json.obj("stt_model" -> s)).getOrElse(Json.obj()))
    }),
    "entities" -> JsObject(conn.entities.toSeq.map { case (id, entity) => id -> JsString(modalities.find(_.id == id).get.entities.idOf(entity)) }),
  )

  private def buildConnection(conn: Connection, entry: Option[JsObject]): JsObject = {
    val kind = conn.kind
    var c = Json.obj("timeout" -> (if (conn.timeout != 0) conn.timeout else BigDecimal(180000)))
    if (conn.baseUrl.nonEmpty) c = c ++ Json.obj("base_url" -> conn.baseUrl)
    else entry.flatMap(e => nonEmptyString(e.select("base_url"))).foreach(url => c = c ++ Json.obj("base_url" -> url))
    if (conn.token.nonEmpty) c = c ++ Json.obj("token" -> conn.token)
    catalogFields(entry).foreach { f =>
      val name = f.select("name").asString
      conn.fields.get(name).filter(v => v != JsNull && v != JsString("")) match {
        case Some(v) => c = c ++ Json.obj(name -> v)
        case None => nonEmptyString(f.select("default")).foreach(d => c = c ++ Json.obj(name -> d))
      }
    }
    if (kind == "azure-openai") {
      // pre-v1 azure apis use the `api-key` header, v1 uses a bearer token
      c = c ++ Json.obj(
        "api_key" -> conn.token,
        "resource_name" -> c.select("resource_name").asOpt[JsValue].getOrElse(JsString("")).as[JsValue],
        "deployment_id" -> c.select("deployment_id").asOpt[JsValue].getOrElse(JsString("")).as[JsValue],
      )
    }
    if (kind == "anthropic") c = c ++ Json.obj("version" -> nonEmptyString(conn.connection.select("version")).getOrElse("2023-06-01"))
    if (kind == "openai-compatible") {
      val kept = Seq("supports_completion", "supports_tools", "supports_streaming", "models_path", "headers", "param_mappings", "additional_body_params")
        .flatMap(k => conn.connection.value.get(k).map(k -> _))
      c = c ++ Json.obj(
        "supports_completion" -> true,
        "supports_tools" -> true,
        "supports_streaming" -> true,
        "models_path" -> "/models",
        "headers" -> Json.obj("Authorization" -> "Bearer {api_key}"),
      ) ++ JsObject(kept)
    }
    c
  }

  private def buildEntity(modality: String, ws: Workspace, conn: Connection, entry: Option[JsObject], existing: Option[JsObject]): JsObject = {
    val kind = conn.kind
    val mod = conn.modalities.getOrElse(modality, ModalityConf(false, "", None))
    val model = mod.model.trim
    val connection = buildConnection(conn, entry)
    val prev = existing.getOrElse(Json.obj())
    val metadata = {
      val m = objOf(prev.select("metadata")) ++ workspaceMetadata(ws.id, modalities.find(_.id == modality).get.kind, MetaConnection -> conn.id)
      if (!conn.enabled) m ++ Json.obj(MetaDisabled -> "true") else m - MetaDisabled
    }
    val base = prev ++ Json.obj(
      "_loc" -> prev.select("_loc").asOpt[JsObject].getOrElse(ws.location).as[JsObject],
      "id" -> prev.select("id").asOptString.getOrElse(s"${modalities.find(_.id == modality).get.kind}_ais_${randomId(20)}"),
      "name" -> conn.name,
      "description" -> conn.description,
      "tags" -> prev.select("tags").asOpt[JsArray].getOrElse(Json.arr()).as[JsArray],
      "metadata" -> metadata,
      "provider" -> kind,
    )
    val noConstraints = Json.obj("models" -> Json.obj("include" -> Json.arr(), "exclude" -> Json.arr()))
    if (modality == "text") {
      val options = if (model.nonEmpty) objOf(prev.select("options")) ++ Json.obj("model" -> model) else objOf(prev.select("options")) - "model"
      noConstraints ++ Json.obj("guardrails" -> Json.arr(), "guardrails_fail_on_deny" -> false) ++ base ++ Json.obj(
        "connection" -> (objOf(prev.select("connection")) ++ connection),
        "options" -> options,
      )
    } else {
      val prevConfig = objOf(prev.select("config"))
      val prevOptions = objOf(prevConfig.select("options"))
      val config = modality match {
        case "image" =>
          prevConfig ++ Json.obj(
            "connection" -> connection,
            "options" -> (prevOptions ++ Json.obj(
              "generation" -> (Json.obj("enabled" -> true) ++ objOf(prevOptions.select("generation")) ++ Json.obj("model" -> model)),
              "edition" -> (Json.obj("enabled" -> false) ++ objOf(prevOptions.select("edition")) ++ Json.obj("model" -> model)),
            )),
          )
        case "audio" =>
          val tts = mod.model.trim
          val stt = mod.sttModel.getOrElse("").trim
          val ttsConf =
            if (kind == "elevenlabs") Json.obj("enabled" -> tts.nonEmpty, "model_id" -> tts, "voice_id" -> "21m00Tcm4TlvDq8ikWAM", "output_format" -> "mp3_44100_128")
            else Json.obj("enabled" -> tts.nonEmpty, "model" -> tts, "voice" -> "alloy", "response_format" -> "mp3")
          val sttConf = if (kind == "elevenlabs") Json.obj("enabled" -> stt.nonEmpty, "model_id" -> stt) else Json.obj("enabled" -> stt.nonEmpty, "model" -> stt)
          val translate = Json.obj("enabled" -> false)
          // the audio client reads tts/stt at the root of the config, the admin ui writes them in options: write both
          prevConfig ++ Json.obj(
            "connection" -> connection,
            "tts" -> (objOf(prevConfig.select("tts")) ++ ttsConf),
            "stt" -> (objOf(prevConfig.select("stt")) ++ sttConf),
            "translate" -> (translate ++ objOf(prevConfig.select("translate"))),
            "options" -> (prevOptions ++ Json.obj(
              "tts" -> (objOf(prevOptions.select("tts")) ++ ttsConf),
              "stt" -> (objOf(prevOptions.select("stt")) ++ sttConf),
              "translation" -> (translate ++ objOf(prevOptions.select("translation"))),
            )),
          )
        case "video" =>
          prevConfig ++ Json.obj("connection" -> connection, "options" -> (Json.obj("enabled" -> true) ++ prevOptions ++ Json.obj("model" -> model)))
        case _ =>
          prevConfig ++ Json.obj("connection" -> connection, "options" -> (prevOptions ++ Json.obj("model" -> model)))
      }
      noConstraints ++ base ++ Json.obj("config" -> config)
    }
  }

  private def saveConnection(ws: Workspace, conn: Connection, entry: Option[JsObject])(using call: AiStudioApiRequest): Future[Unit] = {
    val capabilities = entry.map(capabilitiesOf).getOrElse(conn.modalities.keys.toSeq)
    sequentially(modalities) { m =>
      val existing = conn.entities.get(m.id)
      val wanted = capabilities.contains(m.id) && conn.modalities.get(m.id).exists(_.enabled)
      if (wanted) {
        val entity = buildEntity(m.id, ws, conn, entry, existing)
        if (existing.isDefined) m.entities.update(entity) else m.entities.create(entity)
      } else existing match {
        case Some(e) => m.entities.delete(m.entities.idOf(e))
        case None    => ().vfuture
      }
    }.flatMap(_ => syncWorkspaceRefs(ws.id)).map(_ => ())
  }

  private def defaultModalityConf(entry: JsObject, capability: String): ModalityConf = {
    val models = entry.select("models")
    if (capability == "audio") ModalityConf(false, models.select("audio_tts").asOptString.getOrElse(""), Some(models.select("audio_stt").asOptString.getOrElse("")))
    else ModalityConf(false, models.select(capability).asOptString.getOrElse(""), None)
  }

  private def newConnection(entry: JsObject, existingNames: Seq[String]): Connection = {
    val kind = entry.select("id").asString
    var name = connectionName(kind)
    var i = 2
    while (existingNames.contains(name)) {
      name = s"${connectionName(kind)}_$i"
      i = i + 1
    }
    val capabilities = capabilitiesOf(entry)
    val confs = capabilities.map(cap => cap -> defaultModalityConf(entry, cap).copy(enabled = cap == "text")).toMap
    // providers without text capability: enable their first modality
    val withDefault = if (confs.contains("text")) confs else capabilities.headOption.map(first => confs + (first -> confs(first).copy(enabled = true))).getOrElse(confs)
    Connection(
      id = s"conn_${randomId(12)}",
      name = name,
      kind = kind,
      description = "",
      connection = Json.obj(),
      baseUrl = entry.select("base_url").asOptString.getOrElse(""),
      token = "",
      timeout = BigDecimal(180000),
      enabled = true,
      fields = catalogFields(Some(entry)).map(f => f.select("name").asString -> JsString(f.select("default").asOptString.getOrElse(""))).toMap,
      modalities = withDefault,
      entities = Map.empty,
    )
  }

  // applies an api body on a connection, then checks it the way the provider form does
  private def connectionFromForm(base: Connection, form: JsObject, entry: Option[JsObject], initialName: Option[String], existingNames: Seq[String]): Connection = {
    val isNew = base.entities.isEmpty
    val capabilities = entry.map(capabilitiesOf).getOrElse(Seq("text", "embedding", "image", "audio", "moderation", "ocr", "video"))
    val formModalities = obj(form, "modalities").getOrElse(Json.obj())
    val mods = formModalities.value.foldLeft(base.modalities) { case (acc, (id, value)) =>
      if (!capabilities.contains(id)) throw badRequest(s"the provider '${base.kind}' does not support the '$id' capability")
      val patch = value match {
        case o: JsObject => o
        case JsBoolean(b) => Json.obj("enabled" -> b)
        case _ => throw badRequest(s"'modalities.$id' must be an object")
      }
      val current = acc.get(id).orElse(entry.map(e => defaultModalityConf(e, id))).getOrElse(ModalityConf(false, "", Option.when(id == "audio")("")))
      // a capability listed in the body is enabled unless it says `enabled: false`
      acc + (id -> current.copy(
        enabled = boolean(patch, "enabled").getOrElse(true),
        model = if (has(patch, "model")) string(patch, "model").getOrElse("") else current.model,
        sttModel = if (id == "audio") Some(if (has(patch, "stt_model")) string(patch, "stt_model").getOrElse("") else current.sttModel.getOrElse("")) else None,
      ))
    }
    val conn = base.copy(
      name = string(form, "name").map(connectionName).getOrElse(base.name),
      description = if (has(form, "description")) string(form, "description").getOrElse("") else base.description,
      baseUrl = if (has(form, "base_url")) string(form, "base_url").getOrElse("") else base.baseUrl,
      token = if (has(form, "token")) string(form, "token").getOrElse("") else base.token,
      timeout = number(form, "timeout").getOrElse(base.timeout),
      enabled = boolean(form, "enabled").getOrElse(base.enabled),
      fields = base.fields ++ obj(form, "fields").map(_.value.toMap).getOrElse(Map.empty),
      modalities = mods,
    )
    val nameTaken = existingNames.contains(conn.name) && !initialName.contains(conn.name)
    if (conn.name.isEmpty) throw badRequest("'name' is required")
    if (nameTaken) throw conflict(s"the name '${conn.name}' is already used in this workspace")
    if (!conn.modalities.values.exists(_.enabled)) throw badRequest("at least one capability must be enabled")
    if (entry.exists(_.select("token_required").asOpt[Boolean].contains(true)) && conn.token.isEmpty && isNew) throw badRequest("'token' is required for this provider")
    if (entry.exists(_.select("base_url_required").asOpt[Boolean].contains(true)) && conn.baseUrl.isEmpty) throw badRequest("'base_url' is required for this provider")
    conn
  }

  private def realConnections(wsId: String): Future[Seq[Connection]] = listConnections(wsId).map(_.filterNot(c => VirtualKinds.contains(c.kind)))

  private def connection(wsId: String, connId: String): Future[Connection] =
    realConnections(wsId).map(_.find(_.id == connId).getOrElse(throw notFound("provider not found")))

  private def createConnection(ws: Workspace, form: JsObject)(using call: AiStudioApiRequest): Future[JsObject] = {
    val kind = string(form, "kind").getOrElse(throw badRequest("'kind' is required"))
    val entry = catalogEntry(kind).getOrElse(throw badRequest(s"unknown provider kind '$kind', see the catalog"))
    realConnections(ws.id).flatMap { existing =>
      val names = existing.map(_.name)
      val fresh = newConnection(entry, names)
      val conn = connectionFromForm(fresh, form, Some(entry), Some(fresh.name), names)
      saveConnection(ws, conn, Some(entry)).flatMap(_ => connection(ws.id, conn.id)).map(connectionJson)
    }
  }

  private def updateConnection(ws: Workspace, connId: String, form: JsObject)(using call: AiStudioApiRequest): Future[JsObject] =
    realConnections(ws.id).flatMap { existing =>
      val current = existing.find(_.id == connId).getOrElse(throw notFound("provider not found"))
      if (string(form, "kind").exists(_ != current.kind)) throw badRequest("the kind of a provider cannot be changed")
      val entry = catalogEntry(current.kind)
      val conn = connectionFromForm(current, form, entry, Some(current.name), existing.map(_.name))
      saveConnection(ws, conn, entry).flatMap(_ => connection(ws.id, conn.id)).map(connectionJson)
    }

  private def deleteConnection(ws: Workspace, connId: String)(using call: AiStudioApiRequest): Future[Unit] =
    connection(ws.id, connId).flatMap { conn =>
      sequentially(modalities) { m =>
        conn.entities.get(m.id).map(e => m.entities.delete(m.entities.idOf(e))).getOrElse(().vfuture)
      }
    }.flatMap(_ => syncWorkspaceRefs(ws.id)).map(_ => ())

  // asks the provider for its models, using the draft text provider built from the connection
  private def fetchProviderModels(ws: Workspace, conn: Connection, force: Boolean): Future[Result] = {
    val entry = catalogEntry(conn.kind)
    val draftConn = conn.copy(modalities = conn.modalities + ("text" -> ModalityConf(true, "x", None)))
    val draft = buildEntity("text", ws, draftConn, entry, conn.entities.get("text"))
    val providerId = draft.select("id").asString
    env.vaults.fillSecretsAsync(providerId, draft.stringify).flatMap { filled =>
      AiProvider.format.reads(filled.parseJson) match {
        case JsError(_) => Results.BadRequest(Json.obj("error" -> "bad_request", "error_description" -> "bad provider format")).vfuture
        case JsSuccess(provider, _) =>
          val token = provider.connection.select("token").asOptString.getOrElse("--")
          val key = s"${provider.id}-$token".sha256
          ext.modelsCache.getIfPresent(key).filterNot(_ => force) match {
            case Some(models) => Results.Ok(Json.obj("from_cache" -> true, "models" -> JsArray(models.map(JsString.apply)))).vfuture
            case None =>
              provider.getChatClient() match {
                case None => Results.BadRequest(Json.obj("error" -> "bad_request", "error_description" -> "no client for this provider")).vfuture
                case Some(client) =>
                  client.listModels(false, TypedMap.empty).map {
                    case Left(err) => Results.BadGateway(Json.obj("error" -> "bad_gateway", "error_description" -> "error fetching models", "error_details" -> err))
                    case Right(models) =>
                      ext.modelsCache.put(key, models)
                      Results.Ok(Json.obj("from_cache" -> false, "models" -> JsArray(models.map(JsString.apply))))
                  }
              }
          }
      }
    }
  }

  /////////////////////////////////////////////////////////////////////////////////////////////////
  // budgets (see ui/ai-studio/src/lib/budgets.js)
  /////////////////////////////////////////////////////////////////////////////////////////////////

  private final case class Period(value: String, amount: Int, unit: String) {
    def duration: JsObject = Json.obj("value" -> amount, "unit" -> unit)
  }

  private val periods = Seq(
    Period("lifetime", 100, "year"),
    Period("daily", 1, "day"),
    Period("weekly", 7, "day"),
    Period("monthly", 30, "day"),
    Period("yearly", 1, "year"),
  )

  private val WorkspaceRulePath = "$.provider.metadata.ai_studio_workspace"

  private def periodOf(budget: JsObject): String = {
    val d = budget.select("duration")
    periods.find(p => d.select("value").asOpt[Int].contains(p.amount) && d.select("unit").asOptString.contains(p.unit)).map(_.value).getOrElse("custom")
  }

  private def extraRulesOf(budget: JsObject): Seq[JsObject] =
    budget.select("scope").select("rules").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
      .filterNot(_.select("path").asOptString.contains(WorkspaceRulePath))
      .map(r => Json.obj("path" -> r.select("path").asOpt[JsValue].getOrElse(JsNull).as[JsValue], "value" -> r.select("value").asOpt[JsValue].getOrElse(JsNull).as[JsValue]))

  private def scopeModeOf(budget: JsObject): String = {
    val keys = stringsOf(budget.select("scope").select("apikeys"))
    val users = stringsOf(budget.select("scope").select("users"))
    if (users.isEmpty && extraRulesOf(budget).isEmpty && keys.size <= 1) (if (keys.isEmpty) "workspace" else "apikey") else "custom"
  }

  private final case class BudgetForm(
    name: String,
    description: String,
    enabled: Boolean,
    usd: Option[BigDecimal],
    tokens: Option[BigDecimal],
    period: String,
    mode: String,
    alert: BigDecimal,
    apikeys: Seq[String],
    // None: keep the users / extra rules of the existing budget
    users: Option[Seq[String]],
    models: Seq[String],
    rules: Option[Seq[JsObject]],
    metadata: JsObject = Json.obj(),
  )

  // every budget of a workspace is scoped to the workspace first (a rule the user cannot remove), then optionally
  // narrowed with consumers (api keys, studio users), models and extra json path conditions
  private def buildBudget(ws: Workspace, form: BudgetForm, existing: Option[JsObject]): JsObject = {
    val prev = existing.getOrElse(Json.obj())
    val period = periods.find(_.value == form.period).getOrElse(periods.head)
    val limits = (objOf(prev.select("limits")) - "total_usd" - "total_tokens") ++
      form.usd.map(v => Json.obj("total_usd" -> v)).getOrElse(Json.obj()) ++
      form.tokens.map(v => Json.obj("total_tokens" -> v)).getOrElse(Json.obj())
    val prevScope = objOf(prev.select("scope"))
    val rules = form.rules.getOrElse(existing.map(extraRulesOf).getOrElse(Seq.empty))
      .filter(r => r.select("path").asOptString.exists(p => p.trim.nonEmpty && p.trim != WorkspaceRulePath))
      .map(r => Json.obj("path" -> r.select("path").asString.trim, "value" -> r.select("value").asOpt[JsValue].getOrElse(JsNull).as[JsValue]))
    prev ++ Json.obj(
      "_loc" -> prev.select("_loc").asOpt[JsObject].getOrElse(ws.location).as[JsObject],
      "id" -> prev.select("id").asOptString.getOrElse(s"ai-budget_ais_${randomId(20)}"),
      "name" -> form.name,
      "description" -> form.description,
      "tags" -> prev.select("tags").asOpt[JsArray].getOrElse(Json.arr()).as[JsArray],
      "metadata" -> (objOf(prev.select("metadata")) ++ form.metadata ++ workspaceMetadata(ws.id, "budget")),
      "enabled" -> form.enabled,
      "start_at" -> prev.select("start_at").asOptString.getOrElse(nowIso()),
      "end_at" -> prev.select("end_at").asOptString.filter(_ => existing.exists(e => periodOf(e) == form.period))
        .getOrElse(Instant.now().plus(100L * 365, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS).toString),
      "duration" -> (if (form.period == "custom" && existing.isDefined) prev.select("duration").asOpt[JsValue].getOrElse(period.duration).as[JsValue] else period.duration),
      "limits" -> limits,
      "scope" -> (Json.obj(
        "extract_from_apikey_meta" -> false,
        "extract_from_apikey_group_meta" -> false,
        "extract_from_user_meta" -> false,
        "extract_from_user_auth_module_meta" -> false,
        "extract_from_provider_meta" -> false,
        "groups" -> Json.arr(),
        "providers" -> Json.arr(),
      ) ++ prevScope ++ Json.obj(
        "apikeys" -> form.apikeys,
        "users" -> form.users.getOrElse(stringsOf(prevScope.select("users"))),
        "models" -> form.models,
        "always_apply_rules" -> true,
        "rules" -> (Json.obj("path" -> WorkspaceRulePath, "value" -> ws.id) +: rules),
        "rules_match_mode" -> "all",
      )),
      "action_on_exceed" -> (objOf(prev.select("action_on_exceed")) ++ Json.obj(
        "mode" -> form.mode,
        "alert_on_exceed" -> true,
        "alert_on_almost_exceed" -> true,
        "alert_on_almost_exceed_percentage" -> (if (form.alert != 0) form.alert else BigDecimal(80)),
      )),
    )
  }

  private def saveBudget(ws: Workspace, form: BudgetForm, existing: Option[JsObject])(using call: AiStudioApiRequest): Future[JsObject] = {
    val budget = buildBudget(ws, form, existing)
    if (existing.isDefined) Budgets.update(budget) else Budgets.create(budget)
  }

  private def keyBudgetOf(budgets: Seq[JsObject], clientId: String): Option[JsObject] = budgets.find(b => metaOf(b, MetaKeyLimit).contains(clientId))

  private def consumptionOf(budgetId: String): Future[Option[JsValue]] =
    ext.datastores.budgetsDataStore.findById(budgetId).flatMap {
      case None => None.vfuture
      case Some(budget) => budget.getConsumptions().map(c => Some(c.jsonWithRemaining(budget)))
    }.recover { case _ => None }

  private def budgetJson(budget: JsObject, consumption: Option[JsValue]): JsObject = {
    val scope = budget.select("scope")
    val action = budget.select("action_on_exceed")
    Json.obj(
      "id" -> Budgets.idOf(budget),
      "name" -> budget.select("name").asOptString.getOrElse(""),
      "description" -> budget.select("description").asOptString.getOrElse(""),
      "enabled" -> budget.select("enabled").asOptBoolean.getOrElse(true),
      "usd" -> budget.select("limits").select("total_usd").asOpt[JsValue].getOrElse(JsNull).as[JsValue],
      "tokens" -> budget.select("limits").select("total_tokens").asOpt[JsValue].getOrElse(JsNull).as[JsValue],
      "period" -> periodOf(budget),
      "duration" -> budget.select("duration").asOpt[JsValue].getOrElse(JsNull).as[JsValue],
      "mode" -> action.select("mode").asOptString.getOrElse("block"),
      "alert" -> action.select("alert_on_almost_exceed_percentage").asOpt[JsValue].getOrElse(JsNumber(80)).as[JsValue],
      "scope" -> scopeModeOf(budget),
      "apikeys" -> stringsOf(scope.select("apikeys")),
      "users" -> stringsOf(scope.select("users")),
      "models" -> stringsOf(scope.select("models")),
      "rules" -> extraRulesOf(budget),
      "key_limit" -> optString(metaOf(budget, MetaKeyLimit)),
      "start_at" -> budget.select("start_at").asOpt[JsValue].getOrElse(JsNull).as[JsValue],
      "end_at" -> budget.select("end_at").asOpt[JsValue].getOrElse(JsNull).as[JsValue],
    ) ++ consumption.map(c => Json.obj("consumption" -> c)).getOrElse(Json.obj())
  }

  private def rulesFromForm(form: JsObject): Option[Seq[JsObject]] = form.value.get("rules").map {
    case JsArray(values) => values.toSeq.map {
      case o: JsObject => Json.obj("path" -> o.select("path").asOptString.getOrElse(""), "value" -> o.select("value").asOpt[JsValue].getOrElse(JsNull).as[JsValue])
      case _ => throw badRequest("'rules' must be an array of { path, value } objects")
    }
    case JsNull => Seq.empty
    case _ => throw badRequest("'rules' must be an array of { path, value } objects")
  }

  // the budget form (components/BudgetModal.jsx): the scope mode decides which consumers are kept
  private def budgetFromForm(form: JsObject, existing: Option[JsObject], workspaceKeys: Seq[String]): BudgetForm = {
    val prev = existing.getOrElse(Json.obj())
    val scope = prev.select("scope")
    val action = prev.select("action_on_exceed")
    def limit(key: String, current: JsLookupResult): Option[BigDecimal] = if (has(form, key)) number(form, key) else current.asOpt[BigDecimal]
    val apikeys = strings(form, "apikeys").getOrElse(stringsOf(scope.select("apikeys")))
    val users = strings(form, "users").getOrElse(stringsOf(scope.select("users")))
    val rules = rulesFromForm(form).getOrElse(existing.map(extraRulesOf).getOrElse(Seq.empty))
    val mode = string(form, "scope").getOrElse {
      if (existing.isDefined && !has(form, "apikeys") && !has(form, "users") && !has(form, "rules")) scopeModeOf(prev)
      else if (users.isEmpty && rules.isEmpty && apikeys.size <= 1) (if (apikeys.isEmpty) "workspace" else "apikey")
      else "custom"
    }
    val (scopedKeys, scopedUsers, scopedRules) = mode match {
      case "workspace" => (Seq.empty, Seq.empty, Seq.empty)
      case "apikey" =>
        if (apikeys.size != 1) throw badRequest("a budget scoped to an api key needs exactly one api key in 'apikeys'")
        (apikeys.take(1), Seq.empty, Seq.empty)
      case "custom" => (apikeys, users, rules)
      case other => throw badRequest(s"unknown scope '$other', expected workspace, apikey or custom")
    }
    scopedKeys.filterNot(workspaceKeys.contains).headOption.foreach(k => throw badRequest(s"the api key '$k' does not belong to this workspace"))
    val period = string(form, "period").getOrElse(if (existing.isDefined) periodOf(prev) else "monthly")
    if (period == "custom" && !existing.exists(e => periodOf(e) == "custom")) throw badRequest(s"unknown period 'custom', expected ${periods.map(_.value).mkString(", ")}")
    if (period != "custom" && !periods.exists(_.value == period)) throw badRequest(s"unknown period '$period', expected ${periods.map(_.value).mkString(", ")}")
    val budgetMode = string(form, "mode").getOrElse(action.select("mode").asOptString.getOrElse("block"))
    if (budgetMode != "block" && budgetMode != "soft") throw badRequest("'mode' must be block or soft")
    val result = BudgetForm(
      name = string(form, "name").getOrElse(prev.select("name").asOptString.getOrElse("")).trim,
      description = if (has(form, "description")) string(form, "description").getOrElse("") else prev.select("description").asOptString.getOrElse(""),
      enabled = boolean(form, "enabled").getOrElse(prev.select("enabled").asOptBoolean.getOrElse(true)),
      usd = limit("usd", prev.select("limits").select("total_usd")),
      tokens = limit("tokens", prev.select("limits").select("total_tokens")),
      period = period,
      mode = budgetMode,
      alert = number(form, "alert").getOrElse(action.select("alert_on_almost_exceed_percentage").asOpt[BigDecimal].getOrElse(BigDecimal(80))),
      apikeys = scopedKeys,
      users = Some(scopedUsers),
      models = strings(form, "models").getOrElse(stringsOf(scope.select("models"))),
      rules = Some(scopedRules),
    )
    if (result.name.isEmpty) throw badRequest("'name' is required")
    if (result.usd.isEmpty && result.tokens.isEmpty) throw badRequest("a budget needs a 'usd' or a 'tokens' limit")
    result
  }

  private def workspaceBudget(ws: Workspace, budgetId: String): Future[JsObject] =
    Budgets.get(budgetId).map {
      case Some(b) if metaOf(b, MetaWorkspace).contains(ws.id) => b
      case _ => throw notFound("budget not found")
    }

  /////////////////////////////////////////////////////////////////////////////////////////////////
  // api keys (see ui/ai-studio/src/lib/apikeys.js and pages/Keys.jsx)
  /////////////////////////////////////////////////////////////////////////////////////////////////

  private def usesWorkspaceQuotas(apikey: JsObject, config: AiStudioConfig): Boolean =
    apikey.select("throttlingQuota").asOpt[Long].contains(config.quota("default_throttling_quota")) &&
      apikey.select("dailyQuota").asOpt[Long].contains(config.quota("default_daily_quota")) &&
      apikey.select("monthlyQuota").asOpt[Long].contains(config.quota("default_monthly_quota"))

  private def apikeyJson(apikey: JsObject, budgets: Seq[JsObject], config: AiStudioConfig): JsObject = {
    val clientId = apikey.select("clientId").asString
    Json.obj(
      "client_id" -> clientId,
      "client_secret" -> apikey.select("clientSecret").asOpt[JsValue].getOrElse(JsNull).as[JsValue],
      "bearer" -> apikey.select("bearer").asOpt[JsValue].getOrElse(JsNull).as[JsValue],
      "name" -> apikey.select("clientName").asOptString.getOrElse(clientId),
      "description" -> apikey.select("description").asOptString.getOrElse(""),
      "enabled" -> apikey.select("enabled").asOptBoolean.getOrElse(true),
      "owner" -> optString(metaOf(apikey, MetaOwner)),
      "uses_workspace_quotas" -> usesWorkspaceQuotas(apikey, config),
      "quotas" -> Json.obj(
        "throttling_quota" -> apikey.select("throttlingQuota").asOpt[JsValue].getOrElse(JsNull).as[JsValue],
        "daily_quota" -> apikey.select("dailyQuota").asOpt[JsValue].getOrElse(JsNull).as[JsValue],
        "monthly_quota" -> apikey.select("monthlyQuota").asOpt[JsValue].getOrElse(JsNull).as[JsValue],
      ),
      "credit_limit" -> keyBudgetOf(budgets, clientId).map { b =>
        Json.obj("budget_id" -> Budgets.idOf(b), "usd" -> b.select("limits").select("total_usd").asOpt[JsValue].getOrElse(JsNull).as[JsValue], "period" -> periodOf(b))
      }.getOrElse(JsNull).as[JsValue],
    )
  }

  private def workspaceApikey(ws: Workspace, clientId: String): Future[JsObject] =
    Apikeys.get(clientId).map {
      case Some(k) if metaOf(k, MetaWorkspace).contains(ws.id) => k
      case _ => throw notFound("api key not found")
    }

  private def saveApikey(ws: Workspace, form: JsObject, existing: Option[JsObject])(using call: AiStudioApiRequest): Future[JsObject] = {
    val config = AiStudioConfig.current(env)
    val prev = existing.getOrElse(Json.obj())
    val name = string(form, "name").getOrElse(prev.select("clientName").asOptString.getOrElse("")).trim
    if (name.isEmpty) throw badRequest("'name' is required")
    val defaults = (config.quota("default_throttling_quota"), config.quota("default_daily_quota"), config.quota("default_monthly_quota"))
    val current = existing.filterNot(k => usesWorkspaceQuotas(k, config)).map(k => (
      k.select("throttlingQuota").asOpt[Long].getOrElse(defaults._1),
      k.select("dailyQuota").asOpt[Long].getOrElse(defaults._2),
      k.select("monthlyQuota").asOpt[Long].getOrElse(defaults._3),
    ))
    // `quotas`: an object overrides the workspace defaults, null goes back to them, absent keeps the current ones
    val quotas = form.value.get("quotas") match {
      case None => current.getOrElse(defaults)
      case Some(JsNull) => defaults
      case Some(q: JsObject) =>
        val base = current.getOrElse(defaults)
        (number(q, "throttling_quota").map(_.toLong).getOrElse(base._1), number(q, "daily_quota").map(_.toLong).getOrElse(base._2), number(q, "monthly_quota").map(_.toLong).getOrElse(base._3))
      case Some(_) => throw badRequest("'quotas' must be an object or null")
    }
    // `owner`: the email the usage of the key is attributed to, null makes it a workspace key, absent keeps the current one
    val owner = if (has(form, "owner")) string(form, "owner").map(_.trim).filter(_.nonEmpty) else metaOf(prev, MetaOwner)
    if (owner.exists(o => !OwnerPattern.matches(o))) throw badRequest("'owner' must be an email")
    val tag = consumerTagOf(ws.id)
    for {
      budgets <- Budgets.list(ws.id)
      keyBudget = existing.flatMap(k => keyBudgetOf(budgets, Apikeys.idOf(k)))
      // `credit_limit`: { usd, period } sets the credit limit budget of the key, null removes it, absent keeps it
      credit = form.value.get("credit_limit") match {
        case None => None
        case Some(JsNull) => Some(None)
        case Some(c: JsObject) =>
          val usd = if (has(c, "usd")) number(c, "usd") else keyBudget.flatMap(_.select("limits").select("total_usd").asOpt[BigDecimal])
          val period = string(c, "period").orElse(keyBudget.map(periodOf)).getOrElse("lifetime")
          if (period != "custom" && !periods.exists(_.value == period)) throw badRequest(s"unknown period '$period', expected ${periods.map(_.value).mkString(", ")}")
          if (period == "custom" && !keyBudget.exists(b => periodOf(b) == "custom")) throw badRequest(s"unknown period 'custom', expected ${periods.map(_.value).mkString(", ")}")
          Some(usd.map(v => (v, period)))
        case Some(_) => throw badRequest("'credit_limit' must be an object or null")
      }
      base = existing.getOrElse(Apikeys.template())
      apikey = (base ++ Json.obj(
        "_loc" -> prev.select("_loc").asOpt[JsObject].getOrElse(ws.location).as[JsObject],
        "clientName" -> name,
        "description" -> (if (has(form, "description")) string(form, "description").getOrElse("") else prev.select("description").asOptString.getOrElse("")),
        "enabled" -> boolean(form, "enabled").getOrElse(prev.select("enabled").asOptBoolean.getOrElse(true)),
        // only this workspace route, never the defaults of the template
        "authorizations" -> Json.arr(Json.obj("kind" -> "route", "id" -> routeIdOf(ws.id))),
        "authorizedEntities" -> Json.arr(),
        "authorizedGroup" -> JsNull,
        "tags" -> (stringsOf(prev.select("tags")) :+ tag).distinct,
        "metadata" -> ((objOf(prev.select("metadata")) - MetaOwner) ++ workspaceMetadata(ws.id, "apikey") ++ JsObject(owner.map(o => MetaOwner -> JsString(o)).toSeq)),
        "throttlingQuota" -> quotas._1,
        "dailyQuota" -> quotas._2,
        "monthlyQuota" -> quotas._3,
        "readOnly" -> false,
        "allowClientIdOnly" -> false,
      )) - "bearer"
      saved <- if (existing.isDefined) Apikeys.update(apikey) else Apikeys.create(apikey)
      clientId = Apikeys.idOf(saved)
      _ <- credit match {
        case None => ().vfuture
        case Some(None) => keyBudget.map(b => Budgets.delete(Budgets.idOf(b))).getOrElse(().vfuture)
        case Some(Some((usd, period))) =>
          saveBudget(ws, BudgetForm(
            name = s"$name limit",
            description = s"Credit limit of the api key $name",
            enabled = true,
            usd = Some(usd),
            tokens = None,
            period = period,
            mode = "block",
            alert = BigDecimal(80),
            apikeys = Seq(clientId),
            users = None,
            models = Seq.empty,
            rules = None,
            metadata = Json.obj(MetaKeyLimit -> clientId),
          ), keyBudget)
      }
      fresh <- Apikeys.get(clientId).map(_.getOrElse(saved))
      budgetsAfter <- Budgets.list(ws.id)
    } yield apikeyJson(fresh, budgetsAfter, config)
  }

  /////////////////////////////////////////////////////////////////////////////////////////////////
  // guardrails and model access (see pages/Guardrails.jsx and lib/guardrails.js)
  /////////////////////////////////////////////////////////////////////////////////////////////////

  private val moderationCategories = Seq("hate", "hate/threatening", "harassment", "harassment/threatening", "self-harm", "self-harm/intent", "self-harm/instructions", "sexual", "sexual/minors", "violence", "violence/graphic", "profanity")
  private val personalInformations = Seq("EMAIL_ADDRESS", "PHONE_NUMBER", "LOCATION_ADDRESS", "NAME", "IP_ADDRESS", "CREDIT_CARD", "SSN")
  private val secrets = Seq("APIKEYS", "PASSWORDS", "TOKENS", "JWT_TOKENS", "PRIVATE_KEYS", "HUGE_RANDOM_VALUES")
  private val rampartEntities = Seq("GIVEN_NAME", "SURNAME", "PHONE", "TAX_ID", "BANK_ACCOUNT", "ROUTING_NUMBER", "GOVERNMENT_ID", "PASSPORT", "DRIVERS_LICENSE", "BUILDING_NUMBER", "STREET_NAME", "SECONDARY_ADDRESS", "EMAIL", "URL", "SSN", "CREDIT_CARD", "IP_ADDRESS")

  // guardrail kinds offered by the studio: (defaults of the config, judged by a text provider of the workspace)
  private val guardrailKinds: Map[String, (JsObject, Boolean)] = Map(
    "regex" -> (Json.obj(), false),
    "contains" -> (Json.obj("operation" -> "contains_none", "values" -> Json.arr()), false),
    "characters" -> (Json.obj(), false),
    "rampart" -> (Json.obj("action" -> "redact", "min_score" -> 0.4, "entities" -> rampartEntities), false),
    "prompt_injection" -> (Json.obj("max_injection_score" -> 90), true),
    "pif" -> (Json.obj("pif_items" -> personalInformations), true),
    "secrets_leakage" -> (Json.obj("secrets_leakage_items" -> secrets), true),
    "moderation" -> (Json.obj("moderation_items" -> moderationCategories), true),
    "toxic_language" -> (Json.obj(), true),
    "gibberish" -> (Json.obj(), true),
    "moderation_model" -> (Json.obj(), false),
    "webhook" -> (Json.obj(), false),
  )

  private def guardrailsJson(providers: Seq[JsObject]): JsObject = {
    val first = providers.headOption
    Json.obj(
      "items" -> first.flatMap(_.select("guardrails").asOpt[JsArray]).getOrElse(Json.arr()).as[JsArray],
      "fail_on_deny" -> first.forall(p => !p.select("guardrails_fail_on_deny").asOpt[Boolean].contains(false)),
      "mixed" -> providers.exists(p => p.select("guardrails").asOpt[JsValue].getOrElse(Json.arr()) != first.flatMap(_.select("guardrails").asOpt[JsValue]).getOrElse(Json.arr())),
    )
  }

  private def saveGuardrails(ws: Workspace, form: JsObject)(using call: AiStudioApiRequest): Future[JsObject] =
    for {
      providers <- Providers.list(ws.id)
      moderationModels <- modalities.find(_.id == "moderation").get.entities.list(ws.id)
      current = guardrailsJson(providers)
      providerIds = providers.map(Providers.idOf)
      items = form.value.get("items") match {
        case None => current.select("items").as[Seq[JsObject]]
        case Some(JsArray(values)) => values.toSeq.zipWithIndex.map {
          case (item: JsObject, idx) =>
            val id = item.select("id").asOptString.getOrElse(throw badRequest(s"'items[$idx].id' is required"))
            val (defaults, llm) = guardrailKinds.getOrElse(id, throw badRequest(s"unknown guardrail '$id', expected ${guardrailKinds.keys.toSeq.sorted.mkString(", ")}"))
            val provided = obj(item, "config").getOrElse(Json.obj())
            val judge = if (llm && !has(provided, "provider")) providerIds.headOption.map(p => Json.obj("provider" -> p)).getOrElse(Json.obj()) else Json.obj()
            val config = defaults ++ judge ++ provided
            if (llm) config.select("provider").asOptString.filterNot(providerIds.contains).foreach(p => throw badRequest(s"the provider '$p' of 'items[$idx]' does not belong to this workspace"))
            if (id == "moderation_model") config.select("moderation_model").asOptString.filterNot(moderationModels.map(_.select("id").asString).contains).foreach(m => throw badRequest(s"the moderation model '$m' of 'items[$idx]' does not belong to this workspace"))
            Json.obj("enabled" -> true, "before" -> true, "after" -> false) ++ item ++ Json.obj("config" -> config)
          case (_, idx) => throw badRequest(s"'items[$idx]' must be an object")
        }
        case Some(_) => throw badRequest("'items' must be an array")
      }
      failOnDeny = boolean(form, "fail_on_deny").getOrElse(current.select("fail_on_deny").as[Boolean])
      _ <- sequentially(providers)(p => Providers.update(p ++ Json.obj("guardrails" -> items, "guardrails_fail_on_deny" -> failOnDeny)))
      updated <- Providers.list(ws.id)
    } yield guardrailsJson(updated)

  private def allModelEntities(wsId: String): Future[Seq[(Modality, JsObject)]] =
    Future.sequence(modalities.map(m => m.entities.list(wsId).map(_.map(e => (m, e))))).map(_.flatten)

  private def modelAccessJson(entities: Seq[(Modality, JsObject)]): JsObject = {
    def access(e: JsObject) = (e.select("models").select("include").asOpt[JsValue].getOrElse(Json.arr()), e.select("models").select("exclude").asOpt[JsValue].getOrElse(Json.arr()))
    val first = entities.headOption.map(e => access(e._2)).getOrElse((Json.arr(), Json.arr()))
    Json.obj("include" -> first._1, "exclude" -> first._2, "mixed" -> entities.exists(e => access(e._2) != first))
  }

  private def saveModelAccess(ws: Workspace, form: JsObject)(using call: AiStudioApiRequest): Future[JsObject] =
    for {
      entities <- allModelEntities(ws.id)
      current = modelAccessJson(entities)
      include = strings(form, "include").getOrElse(stringsOf(current.select("include")))
      exclude = strings(form, "exclude").getOrElse(stringsOf(current.select("exclude")))
      _ <- sequentially(entities) { case (m, e) =>
        m.entities.update(e ++ Json.obj("models" -> (objOf(e.select("models")) ++ Json.obj("include" -> include, "exclude" -> exclude))))
      }
      updated <- allModelEntities(ws.id)
    } yield modelAccessJson(updated)

  /////////////////////////////////////////////////////////////////////////////////////////////////
  // routing (see pages/Routing.jsx)
  /////////////////////////////////////////////////////////////////////////////////////////////////

  private val strategies = Seq("round_robin", "random", "best_response_time")

  private final case class RouterMode(id: String, refs: String)

  private val routerModes = Seq(RouterMode("code", "code_router_refs"), RouterMode("auto", "auto_router_refs"), RouterMode("fusion", "fusion_router_refs"))

  private def refsOf(value: JsLookupResult): Seq[String] = value.asOpt[Seq[JsValue]].getOrElse(Seq.empty).flatMap {
    case JsString(s) => Some(s)
    case o: JsObject => o.select("ref").asOptString
    case _ => None
  }.filter(_.nonEmpty)

  // router candidates as stored: a provider id, or { ref, model } when the candidate uses another model than the
  // default model of its provider
  private def candidateEntries(value: JsLookupResult): Seq[JsValue] = value.asOpt[Seq[JsValue]].getOrElse(Seq.empty).flatMap {
    case JsString(s) if s.nonEmpty => Some(JsString(s))
    case o: JsObject => o.select("ref").asOptString.filter(_.nonEmpty).map { ref =>
      o.select("model").asOptString.map(_.trim).filter(_.nonEmpty).map(m => Json.obj("ref" -> ref, "model" -> m)).getOrElse(JsString(ref))
    }
    case _ => None
  }

  private def entryRef(entry: JsValue): String = entry match {
    case JsString(s) => s
    case o => o.select("ref").asOptString.getOrElse("")
  }

  private def chainOf(provider: JsObject, byId: Map[String, JsObject]): Seq[JsObject] = {
    var chain = Seq(Json.obj("id" -> Providers.idOf(provider), "name" -> provider.select("name").asOptString.getOrElse("")))
    var seen = Set(Providers.idOf(provider))
    var current = provider
    var next = nonEmptyString(current.select("provider_fallback"))
    while (next.exists(id => byId.contains(id) && !seen.contains(id))) {
      current = byId(next.get)
      seen = seen + next.get
      chain = chain :+ Json.obj("id" -> next.get, "name" -> current.select("name").asOptString.getOrElse(""))
      next = nonEmptyString(current.select("provider_fallback"))
    }
    chain
  }

  private def balancerJson(b: JsObject): JsObject = Json.obj(
    "id" -> Providers.idOf(b),
    "name" -> b.select("name").asOptString.getOrElse(""),
    "strategy" -> b.select("options").select("loadbalancing").asOptString.getOrElse("round_robin"),
    "targets" -> b.select("options").select("refs").asOpt[Seq[JsValue]].getOrElse(Seq.empty).map {
      case JsString(ref) => Json.obj("ref" -> ref, "weight" -> 1, "model" -> JsNull)
      case o => Json.obj(
        "ref" -> o.select("ref").asOpt[JsValue].getOrElse(JsNull).as[JsValue],
        "weight" -> o.select("weight").asOpt[BigDecimal].filter(_ != 0).getOrElse(BigDecimal(1)),
        "model" -> optString(nonEmptyString(o.select("model"))),
      )
    },
  )

  private def routerJson(r: JsObject): JsObject = {
    val o = r.select("options")
    Json.obj(
      "id" -> Providers.idOf(r),
      "name" -> r.select("name").asOptString.getOrElse(""),
      "modes" -> routerModes.filter(m => refsOf(o.select(m.refs)).nonEmpty).map(_.id),
      "code_router_refs" -> candidateEntries(o.select("code_router_refs")),
      "min_coding_score" -> o.select("min_coding_score").asOpt[BigDecimal].getOrElse(BigDecimal(0.5)),
      "auto_router_refs" -> candidateEntries(o.select("auto_router_refs")),
      "auto_router_classifier_ref" -> optString(nonEmptyString(o.select("auto_router_classifier_ref"))),
      "auto_router_classifier_model" -> optString(nonEmptyString(o.select("auto_router_classifier_model"))),
      "cost_quality_tradeoff" -> o.select("cost_quality_tradeoff").asOpt[BigDecimal].getOrElse(BigDecimal(7)),
      "allowed_models" -> stringsOf(o.select("allowed_models")),
      "fusion_router_refs" -> candidateEntries(o.select("fusion_router_refs")),
      "fusion_router_judge_ref" -> optString(nonEmptyString(o.select("fusion_router_judge_ref"))),
      "fusion_router_judge_model" -> optString(nonEmptyString(o.select("fusion_router_judge_model"))),
      "fusion_router_synthesizer_ref" -> optString(nonEmptyString(o.select("fusion_router_synthesizer_ref"))),
      "fusion_router_synthesizer_model" -> optString(nonEmptyString(o.select("fusion_router_synthesizer_model"))),
    )
  }

  private def routingJson(ws: Workspace, providers: Seq[JsObject]): JsObject = {
    val byId = providers.map(p => Providers.idOf(p) -> p).toMap
    val real = providers.filterNot(p => p.select("provider").asOptString.exists(VirtualKinds.contains))
    val order = stringsOf(ws.compat.select("language_model_refs")).filter(byId.contains)
    Json.obj(
      "default_provider" -> optString(order.headOption),
      "order" -> order,
      "providers" -> real.map { p =>
        Json.obj(
          "id" -> Providers.idOf(p),
          "name" -> p.select("name").asOptString.getOrElse(""),
          "kind" -> p.select("provider").asOptString.getOrElse(""),
          "model" -> optString(nonEmptyString(p.select("options").select("model"))),
          "fallback" -> optString(nonEmptyString(p.select("provider_fallback"))),
          "chain" -> chainOf(p, byId),
        )
      },
      "load_balancers" -> providers.filter(_.select("provider").asOptString.contains("loadbalancer")).map(balancerJson),
      "routers" -> providers.filter(_.select("provider").asOptString.contains("otoroshi")).map(routerJson),
    )
  }

  private def saveRouting(ws: Workspace, form: JsObject)(using call: AiStudioApiRequest): Future[JsObject] =
    for {
      providers <- Providers.list(ws.id)
      byId = providers.map(p => Providers.idOf(p) -> p).toMap
      real = providers.filterNot(p => p.select("provider").asOptString.exists(VirtualKinds.contains))
      fallbacks = obj(form, "fallbacks").getOrElse(Json.obj()).value.toSeq.map { case (providerId, value) =>
        val provider = real.find(p => Providers.idOf(p) == providerId).getOrElse(throw badRequest(s"the provider '$providerId' does not belong to this workspace"))
        val fallback = value match {
          case JsString(s) if s.nonEmpty => Some(s)
          case JsString(_) | JsNull => None
          case _ => throw badRequest(s"'fallbacks.$providerId' must be a provider id or null")
        }
        fallback.foreach { f =>
          if (f == providerId || !byId.contains(f)) throw badRequest(s"'$f' cannot be the fallback of '$providerId'")
        }
        (provider, fallback)
      }
      defaultProvider = string(form, "default_provider")
      order = stringsOf(ws.compat.select("language_model_refs")).filter(byId.contains)
      _ = defaultProvider.filterNot(order.contains).foreach(p => throw badRequest(s"the provider '$p' is not served by this workspace"))
      _ <- sequentially(fallbacks) { case (provider, fallback) =>
        Providers.update(provider ++ Json.obj("provider_fallback" -> optString(fallback)))
      }
      route <- defaultProvider match {
        case None => ws.route.vfuture
        case Some(id) => updateWorkspaceRoute(ws.id) { route =>
          val current = stringsOf(compatConfigOf(route).select("language_model_refs"))
          setOpenAiConfig(route, Json.obj("language_model_refs" -> (id +: current.filterNot(_ == id))))
        }
      }
      updated <- Providers.list(ws.id)
    } yield routingJson(ws.copy(route = route), updated)

  private def virtualProvider(ws: Workspace, id: String, kind: String): Future[JsObject] =
    Providers.get(id).map {
      case Some(p) if metaOf(p, MetaWorkspace).contains(ws.id) && p.select("provider").asOptString.contains(kind) => p
      case _ => throw notFound(if (kind == "loadbalancer") "load balancer not found" else "router not found")
    }

  private def virtualEntity(ws: Workspace, existing: Option[JsObject], name: String, description: String, kind: String, options: JsObject): JsObject = {
    val prev = existing.getOrElse(Json.obj())
    val id = prev.select("id").asOptString.getOrElse(s"provider_ais_${randomId(20)}")
    Json.obj("models" -> Json.obj("include" -> Json.arr(), "exclude" -> Json.arr()), "guardrails" -> Json.arr(), "guardrails_fail_on_deny" -> false) ++ prev ++ Json.obj(
      "_loc" -> prev.select("_loc").asOpt[JsObject].getOrElse(ws.location).as[JsObject],
      "id" -> id,
      "name" -> name,
      "description" -> description,
      "tags" -> prev.select("tags").asOpt[JsArray].getOrElse(Json.arr()).as[JsArray],
      "metadata" -> (objOf(prev.select("metadata")) ++ workspaceMetadata(ws.id, "provider", MetaConnection -> id)),
      "provider" -> kind,
      "connection" -> Json.obj(),
      "options" -> (objOf(prev.select("options")) ++ options),
    )
  }

  private def checkVirtualName(name: String, providers: Seq[JsObject], existing: Option[JsObject]): Unit = {
    if (name.isEmpty) throw badRequest("'name' is required")
    val taken = providers.exists(_.select("name").asOptString.contains(name)) && !existing.exists(_.select("name").asOptString.contains(name))
    if (taken) throw conflict(s"the name '$name' is already used in this workspace")
  }

  private def saveBalancer(ws: Workspace, form: JsObject, existing: Option[JsObject])(using call: AiStudioApiRequest): Future[JsObject] =
    Providers.list(ws.id).flatMap { providers =>
      val real = providers.filterNot(p => p.select("provider").asOptString.exists(VirtualKinds.contains)).map(Providers.idOf)
      val current = existing.map(balancerJson)
      val name = string(form, "name").map(connectionName).orElse(current.map(_.select("name").asString)).getOrElse("balanced")
      val strategy = string(form, "strategy").orElse(current.map(_.select("strategy").asString)).getOrElse("round_robin")
      val targets: Seq[(String, BigDecimal, Option[String])] = form.value.get("targets") match {
        case None => current.map(_.select("targets").as[Seq[JsObject]].map(t => (t.select("ref").asOptString.getOrElse(""), t.select("weight").asOpt[BigDecimal].getOrElse(BigDecimal(1)), nonEmptyString(t.select("model"))))).getOrElse(real.take(2).map(r => (r, BigDecimal(1), None)))
        case Some(JsArray(values)) => values.toSeq.map {
          case JsString(ref) => (ref, BigDecimal(1), None)
          case t: JsObject => (t.select("ref").asOptString.getOrElse(""), number(t, "weight").filter(_ != 0).getOrElse(BigDecimal(1)), string(t, "model").map(_.trim).filter(_.nonEmpty))
          case _ => throw badRequest("'targets' must be an array of { ref, weight, model } objects")
        }
        case Some(_) => throw badRequest("'targets' must be an array of { ref, weight, model } objects")
      }
      checkVirtualName(name, providers, existing)
      if (!strategies.contains(strategy)) throw badRequest(s"'strategy' must be one of ${strategies.mkString(", ")}")
      val valid = targets.filter(_._1.nonEmpty)
      if (valid.isEmpty) throw badRequest("a load balancer needs at least one target")
      valid.map(_._1).filterNot(real.contains).headOption.foreach(r => throw badRequest(s"the target '$r' is not a provider of this workspace"))
      val entity = virtualEntity(ws, existing, name, "Load balancer", "loadbalancer", Json.obj(
        "refs" -> valid.map { case (ref, weight, model) => Json.obj("ref" -> ref, "weight" -> weight) ++ model.map(m => Json.obj("model" -> m)).getOrElse(Json.obj()) },
        "loadbalancing" -> strategy,
      ))
      (if (existing.isDefined) Providers.update(entity) else Providers.create(entity))
        .flatMap(saved => syncWorkspaceRefs(ws.id).map(_ => balancerJson(saved)))
    }

  private def saveRouter(ws: Workspace, form: JsObject, existing: Option[JsObject])(using call: AiStudioApiRequest): Future[JsObject] =
    Providers.list(ws.id).flatMap { providers =>
      val real = providers.filterNot(p => p.select("provider").asOptString.exists(VirtualKinds.contains)).map(Providers.idOf)
      val current = existing.map(routerJson).getOrElse(Json.obj(
        "name" -> "router", "code_router_refs" -> Json.arr(), "min_coding_score" -> 0.5, "auto_router_refs" -> Json.arr(), "auto_router_classifier_ref" -> JsNull,
        "cost_quality_tradeoff" -> 7, "allowed_models" -> Json.arr(), "fusion_router_refs" -> Json.arr(), "fusion_router_judge_ref" -> JsNull, "fusion_router_synthesizer_ref" -> JsNull,
      ))
      // candidates given as ids keep the model already configured for them, { ref, model } objects set it
      def refs(key: String): Seq[JsValue] = {
        val previous = current.select(key).asOpt[Seq[JsValue]].getOrElse(Seq.empty)
        form.value.get(key) match {
          case None => previous
          case Some(JsNull) => Seq.empty
          case Some(JsArray(values)) => values.toSeq.map {
            case JsString(id) => previous.find(e => e.isInstanceOf[JsObject] && entryRef(e) == id).getOrElse(JsString(id))
            case o: JsObject => candidateEntries(JsDefined(Json.arr(o))).headOption.getOrElse(throw badRequest(s"'$key' entries need a 'ref'"))
            case _ => throw badRequest(s"'$key' must be an array of provider ids or { ref, model } objects")
          }
          case Some(_) => throw badRequest(s"'$key' must be an array of provider ids or { ref, model } objects")
        }
      }
      def ref(key: String): Option[String] = if (has(form, key)) string(form, key).filter(_.nonEmpty) else current.select(key).asOptString
      def clamped(key: String, min: Int, max: Int, default: BigDecimal): BigDecimal =
        number(form, key).orElse(current.select(key).asOpt[BigDecimal]).getOrElse(default).max(BigDecimal(min)).min(BigDecimal(max))
      val name = string(form, "name").map(connectionName).getOrElse(current.select("name").asString)
      val code = refs("code_router_refs")
      val auto = refs("auto_router_refs")
      val fusion = refs("fusion_router_refs").take(8)
      val singles = Seq("auto_router_classifier_ref", "fusion_router_judge_ref", "fusion_router_synthesizer_ref").map(k => k -> ref(k))
      checkVirtualName(name, providers, existing)
      if (code.isEmpty && auto.isEmpty && fusion.isEmpty) throw badRequest("a router needs candidates in 'code_router_refs', 'auto_router_refs' or 'fusion_router_refs'")
      ((code ++ auto ++ fusion).map(entryRef) ++ singles.flatMap(_._2)).filterNot(real.contains).headOption.foreach(r => throw badRequest(s"'$r' is not a provider of this workspace"))
      val entity = virtualEntity(ws, existing, name, "Otoroshi router", "otoroshi", Json.obj(
        "code_router_refs" -> code,
        "min_coding_score" -> clamped("min_coding_score", 0, 1, BigDecimal(0.5)),
        "auto_router_refs" -> auto,
        "auto_router_classifier_ref" -> optString(singles(0)._2),
        "auto_router_classifier_model" -> optString(ref("auto_router_classifier_model")),
        "cost_quality_tradeoff" -> clamped("cost_quality_tradeoff", 0, 10, BigDecimal(7)),
        "allowed_models" -> strings(form, "allowed_models").getOrElse(stringsOf(current.select("allowed_models"))).map(_.trim).filter(_.nonEmpty),
        "fusion_router_refs" -> fusion,
        "fusion_router_judge_ref" -> optString(singles(1)._2),
        "fusion_router_judge_model" -> optString(ref("fusion_router_judge_model")),
        "fusion_router_synthesizer_ref" -> optString(singles(2)._2),
        "fusion_router_synthesizer_model" -> optString(ref("fusion_router_synthesizer_model")),
      ))
      (if (existing.isDefined) Providers.update(entity) else Providers.create(entity))
        .flatMap(saved => syncWorkspaceRefs(ws.id).map(_ => routerJson(saved)))
    }

  private def deleteVirtual(ws: Workspace, id: String, kind: String)(using call: AiStudioApiRequest): Future[Unit] =
    virtualProvider(ws, id, kind).flatMap(_ => Providers.delete(id)).flatMap(_ => syncWorkspaceRefs(ws.id)).map(_ => ())

  /////////////////////////////////////////////////////////////////////////////////////////////////
  // presets (see pages/Presets.jsx)
  /////////////////////////////////////////////////////////////////////////////////////////////////

  private def messageOf(list: JsLookupResult, role: String): String =
    list.asOpt[Seq[JsObject]].getOrElse(Seq.empty).find(_.select("role").asOptString.contains(role)).flatMap(_.select("content").asOptString).getOrElse("")

  private def presetJson(preset: JsObject, providers: Seq[JsObject]): JsObject = {
    val id = Contexts.idOf(preset)
    Json.obj(
      "id" -> id,
      "name" -> preset.select("name").asOptString.getOrElse(""),
      "description" -> preset.select("description").asOptString.getOrElse(""),
      "system" -> messageOf(preset.select("pre_messages"), "system"),
      "trailing" -> messageOf(preset.select("post_messages"), "user"),
      "providers" -> providers.filter(p => stringsOf(p.select("context").select("contexts")).contains(id)).map(Providers.idOf),
    )
  }

  private def attachPreset(providers: Seq[JsObject], presetId: String, selected: Seq[String])(using call: AiStudioApiRequest): Future[Unit] =
    sequentially(providers) { p =>
      val contexts = stringsOf(p.select("context").select("contexts"))
      val has = contexts.contains(presetId)
      val wants = selected.contains(Providers.idOf(p))
      if (has == wants) ().vfuture
      else {
        val current = objOf(p.select("context"))
        val next = if (wants) contexts :+ presetId else contexts.filterNot(_ == presetId)
        val default = current.select("default").asOptString.filterNot(d => !wants && d == presetId)
        Providers.update(p ++ Json.obj("context" -> (current ++ Json.obj("default" -> optString(default), "contexts" -> next))))
      }
    }

  private def workspacePreset(ws: Workspace, id: String): Future[JsObject] =
    Contexts.get(id).map {
      case Some(c) if metaOf(c, MetaWorkspace).contains(ws.id) => c
      case _ => throw notFound("preset not found")
    }

  private def savePreset(ws: Workspace, form: JsObject, existing: Option[JsObject])(using call: AiStudioApiRequest): Future[JsObject] =
    Providers.list(ws.id).flatMap { providers =>
      val providerIds = providers.map(Providers.idOf)
      val current = existing.map(e => presetJson(e, providers))
      val prev = existing.getOrElse(Json.obj())
      val name = string(form, "name").map(_.replaceAll("\\s+", "-")).orElse(current.map(_.select("name").asString)).getOrElse("")
      if (name.trim.isEmpty) throw badRequest("'name' is required")
      val system = if (has(form, "system")) string(form, "system").getOrElse("") else current.map(_.select("system").asString).getOrElse("")
      val trailing = if (has(form, "trailing")) string(form, "trailing").getOrElse("") else current.map(_.select("trailing").asString).getOrElse("")
      val selected = strings(form, "providers").getOrElse(current.map(c => stringsOf(c.select("providers"))).getOrElse(providerIds))
      selected.filterNot(providerIds.contains).headOption.foreach(p => throw badRequest(s"the provider '$p' does not belong to this workspace"))
      val entity = prev ++ Json.obj(
        "_loc" -> prev.select("_loc").asOpt[JsObject].getOrElse(ws.location).as[JsObject],
        "id" -> prev.select("id").asOptString.getOrElse(s"context_ais_${randomId(20)}"),
        "name" -> name,
        "description" -> (if (has(form, "description")) string(form, "description").getOrElse("") else current.map(_.select("description").asString).getOrElse("")),
        "tags" -> prev.select("tags").asOpt[JsArray].getOrElse(Json.arr()).as[JsArray],
        "metadata" -> (objOf(prev.select("metadata")) ++ workspaceMetadata(ws.id, "context")),
        "pre_messages" -> (if (system.nonEmpty) Json.arr(Json.obj("role" -> "system", "content" -> system)) else Json.arr()),
        "post_messages" -> (if (trailing.nonEmpty) Json.arr(Json.obj("role" -> "user", "content" -> trailing)) else Json.arr()),
      )
      for {
        saved <- if (existing.isDefined) Contexts.update(entity) else Contexts.create(entity)
        _ <- attachPreset(providers, Contexts.idOf(saved), selected)
        _ <- syncWorkspaceRefs(ws.id)
        updated <- Providers.list(ws.id)
      } yield presetJson(saved, updated)
    }

  private def deletePreset(ws: Workspace, id: String)(using call: AiStudioApiRequest): Future[Unit] =
    for {
      _ <- workspacePreset(ws, id)
      providers <- Providers.list(ws.id)
      _ <- attachPreset(providers, id, Seq.empty)
      _ <- Contexts.delete(id)
      _ <- syncWorkspaceRefs(ws.id)
    } yield ()

  /////////////////////////////////////////////////////////////////////////////////////////////////
  // tools (see lib/tools.js and pages/Tools.jsx)
  /////////////////////////////////////////////////////////////////////////////////////////////////

  private final case class ToolKind(id: String, entities: Entities, option: String, kind: String)

  private val toolKinds = Seq(
    ToolKind("functions", Functions, "tool_functions", "tool-function"),
    ToolKind("mcp", McpConnectors, "mcp_connectors", "mcp-connector"),
    ToolKind("search", SearchEngines, "search_engines", "search-engine"),
  )

  private val searchProviders = Seq("tavily", "brave", "exa", "searchapi", "google", "staan", "searxng", "duckduckgo")

  // The studio only creates MCP connectors speaking the stateless Streamable HTTP revision: every request is
  // self-contained, so no session survives a restart or hops to another Otoroshi instance.
  private val McpTransportKind = "http_2026_07_28"

  private val defaultParameters = Json.obj("type" -> "object", "properties" -> Json.obj("city" -> Json.obj("type" -> "string", "description" -> "The city name")), "required" -> Json.arr("city"))

  // A tool function entity stores the *properties* of its json schema, and the required ones next to them
  // (that is what the providers and the MCP endpoint wrap into an `inputSchema`). The form speaks the whole
  // schema, which is what everybody writes, so it is unwrapped on the way in and rebuilt on the way out.
  private def schemaOf(parameters: JsObject, required: Seq[String]): JsObject =
    if (parameters.select("type").asOptString.contains("object") && parameters.select("properties").asOpt[JsObject].isDefined) parameters
    else Json.obj("type" -> "object", "properties" -> parameters, "required" -> (if (required.nonEmpty) required else parameters.keys.toSeq))

  private def propertiesOf(schema: JsObject): JsObject =
    if (schema.select("type").asOptString.contains("object")) schema.select("properties").asOpt[JsObject].getOrElse(schema) else schema

  private def requiredOf(schema: JsObject): Seq[String] =
    schema.select("required").asOpt[Seq[String]].getOrElse(propertiesOf(schema).keys.toSeq)

  private def toolKind(id: String): ToolKind = toolKinds.find(_.id == id).getOrElse(throw notFound(s"unknown tool kind '$id', expected ${toolKinds.map(_.id).mkString(", ")}"))

  private def attachedProviders(providers: Seq[JsObject], kind: ToolKind, id: String): Seq[String] =
    providers.filter(p => stringsOf(p.select("options").select(kind.option)).contains(id)).map(Providers.idOf)

  private def attachTool(providers: Seq[JsObject], kind: ToolKind, id: String, selected: Seq[String])(using call: AiStudioApiRequest): Future[Unit] =
    sequentially(providers) { p =>
      val list = stringsOf(p.select("options").select(kind.option))
      val has = list.contains(id)
      val wants = selected.contains(Providers.idOf(p))
      if (has == wants) ().vfuture
      else Providers.update(p ++ Json.obj("options" -> (objOf(p.select("options")) ++ Json.obj(kind.option -> (if (wants) list :+ id else list.filterNot(_ == id))))))
    }

  // the tool form of the studio, read from an existing entity
  private def toolFormOf(kind: ToolKind, tool: JsObject, providers: Seq[JsObject]): JsObject = {
    val common = Json.obj(
      "id" -> kind.entities.idOf(tool),
      "name" -> tool.select("name").asOptString.getOrElse(""),
      "description" -> tool.select("description").asOptString.getOrElse(""),
      "providers" -> attachedProviders(providers, kind, kind.entities.idOf(tool)),
    )
    kind.id match {
      case "functions" =>
        val backend = tool.select("backend").select("options")
        common ++ Json.obj(
          "parameters" -> tool.select("parameters").asOpt[JsObject].map(p => schemaOf(p, stringsOf(tool.select("required")))).getOrElse(defaultParameters),
          "url" -> backend.select("url").asOptString.getOrElse(""),
          "method" -> nonEmptyString(backend.select("method")).getOrElse("GET"),
          "headers" -> objOf(backend.select("headers")),
          "body" -> backend.select("body").asOptString.getOrElse(""),
          "timeout" -> backend.select("timeout").asOpt[BigDecimal].filter(_ != 0).getOrElse(BigDecimal(30000)),
        )
      case "mcp" =>
        val transport = tool.select("transport").select("options")
        common ++ Json.obj(
          "transport" -> nonEmptyString(tool.select("transport").select("kind")).getOrElse(McpTransportKind),
          "enabled" -> !tool.select("enabled").asOpt[Boolean].contains(false),
          "url" -> transport.select("url").asOptString.getOrElse(""),
          "headers" -> objOf(transport.select("headers")),
          "timeout" -> transport.select("timeout").asOpt[BigDecimal].filter(_ != 0).getOrElse(BigDecimal(30000)),
        )
      case _ =>
        val connection = tool.select("config").select("connection")
        common ++ Json.obj(
          "search_provider" -> tool.select("provider").asOptString.getOrElse(""),
          "token" -> connection.select("token").asOptString.getOrElse(""),
          "base_url" -> connection.select("base_url").asOptString.getOrElse(""),
        )
    }
  }

  private def workspaceTool(ws: Workspace, kind: ToolKind, id: String): Future[JsObject] =
    kind.entities.get(id).map {
      case Some(t) if metaOf(t, MetaWorkspace).contains(ws.id) => t
      case _ => throw notFound("tool not found")
    }

  private def saveTool(ws: Workspace, kind: ToolKind, form: JsObject, existing: Option[JsObject])(using call: AiStudioApiRequest): Future[JsObject] =
    Providers.list(ws.id).flatMap { providers =>
      val providerIds = providers.map(Providers.idOf)
      val current = existing.map(t => toolFormOf(kind, t, providers))
      def text(key: String, default: String = ""): String =
        if (has(form, key)) string(form, key).getOrElse("") else current.flatMap(_.select(key).asOptString).getOrElse(default)
      def headers: JsObject = if (has(form, "headers")) obj(form, "headers").getOrElse(Json.obj()) else current.map(c => objOf(c.select("headers"))).getOrElse(Json.obj())
      def timeout: BigDecimal = number(form, "timeout").orElse(current.flatMap(_.select("timeout").asOpt[BigDecimal])).getOrElse(BigDecimal(30000))
      val name = text("name")
      val description = text("description")
      if (name.trim.isEmpty) throw badRequest("'name' is required")
      val selected = strings(form, "providers").getOrElse(current.map(c => stringsOf(c.select("providers"))).getOrElse(providerIds))
      selected.filterNot(providerIds.contains).headOption.foreach(p => throw badRequest(s"the provider '$p' does not belong to this workspace"))
      val (base, patch) = kind.id match {
        case "functions" =>
          val url = text("url")
          if (url.trim.isEmpty) throw badRequest("'url' is required")
          val parameters = if (has(form, "parameters")) obj(form, "parameters").getOrElse(defaultParameters) else current.map(c => objOf(c.select("parameters"))).getOrElse(defaultParameters)
          val body = text("body")
          (existing.getOrElse(Functions.template()), Json.obj(
            "name" -> name,
            "description" -> description,
            "strict" -> false,
            "parameters" -> propertiesOf(parameters),
            "required" -> requiredOf(parameters),
            "backend" -> Json.obj(
              "kind" -> "Http",
              "options" -> (Json.obj("url" -> url, "method" -> text("method", "GET"), "headers" -> headers, "timeout" -> timeout) ++ (if (body.nonEmpty) Json.obj("body" -> body) else Json.obj())),
            ),
          ))
        case "mcp" =>
          val url = text("url")
          if (url.trim.isEmpty) throw badRequest("'url' is required")
          (existing.getOrElse(McpConnectors.template()), Json.obj(
            "name" -> name,
            "description" -> description,
            "enabled" -> boolean(form, "enabled").orElse(current.flatMap(_.select("enabled").asOpt[Boolean])).getOrElse(true),
            // an existing connector keeps the transport it was given, whoever created it
            "transport" -> Json.obj(
              "kind" -> existing.flatMap(t => nonEmptyString(t.select("transport").select("kind"))).getOrElse(McpTransportKind),
              "options" -> Json.obj("url" -> url, "headers" -> headers, "timeout" -> timeout),
            ),
          ))
        case _ =>
          val provider = text("search_provider", "tavily")
          if (!searchProviders.contains(provider)) throw badRequest(s"'search_provider' must be one of ${searchProviders.mkString(", ")}")
          val base = existing.filter(_.select("provider").asOptString.contains(provider)).getOrElse(SearchEngines.template(Map("kind" -> provider)))
          val prev = objOf(base.select("config").select("connection"))
          val baseUrl = text("base_url")
          val token = text("token")
          (base, Json.obj(
            "name" -> name,
            "description" -> description,
            "provider" -> provider,
            "config" -> (objOf(base.select("config")) ++ Json.obj("connection" -> (prev ++
              (if (baseUrl.nonEmpty) Json.obj("base_url" -> baseUrl) else Json.obj()) ++
              (if (token.nonEmpty) Json.obj("token" -> token) else Json.obj())))),
          ))
      }
      val prev = existing.getOrElse(Json.obj())
      val entity = base ++ patch ++ Json.obj(
        "_loc" -> prev.select("_loc").asOpt[JsObject].getOrElse(ws.location).as[JsObject],
        "id" -> prev.select("id").asOptString.getOrElse(s"${kind.kind}_ais_${randomId(20)}"),
        "tags" -> prev.select("tags").asOpt[JsArray].getOrElse(Json.arr()).as[JsArray],
        "metadata" -> (objOf(prev.select("metadata")) ++ workspaceMetadata(ws.id, kind.kind)),
      )
      for {
        saved <- if (existing.isDefined) kind.entities.update(entity) else kind.entities.create(entity)
        _ <- attachTool(providers, kind, kind.entities.idOf(saved), selected)
        updated <- Providers.list(ws.id)
      } yield toolFormOf(kind, saved, updated)
    }

  private def deleteTool(ws: Workspace, kind: ToolKind, id: String)(using call: AiStudioApiRequest): Future[Unit] =
    for {
      _ <- workspaceTool(ws, kind, id)
      providers <- Providers.list(ws.id)
      _ <- attachTool(providers, kind, id, Seq.empty)
      _ <- kind.entities.delete(id)
    } yield ()

  /////////////////////////////////////////////////////////////////////////////////////////////////
  // mcp server (see lib/mcpserver.js and pages/McpServer.jsx)
  /////////////////////////////////////////////////////////////////////////////////////////////////

  // The MCP server of a workspace is one virtual server entity, referenced by the unified plugin of the
  // workspace route, which serves it on `<base url>/mcp`: same endpoint and same api keys as the models.

  private val McpPath = "/mcp"
  private val MetaMcpServer = "mcp-server"

  private def mcpServerIdOf(wsId: String): String = s"mcp-virtual-server_ais_$wsId"

  private def mcpServerRefOf(route: JsObject): Option[String] =
    nonEmptyString(compatConfigOf(route).select("mcp_server_ref"))

  private def mcpServer(ws: Workspace): Future[Option[JsObject]] = mcpServerRefOf(ws.route) match {
    case None      => None.vfuture
    case Some(ref) => McpVirtualServers.get(ref)
  }

  private def mcpServerJson(ws: Workspace, server: Option[JsObject]): JsObject = {
    val config = server.map(s => objOf(s.select("config"))).getOrElse(Json.obj())
    Json.obj(
      "served" -> server.isDefined,
      "url" -> s"${baseUrlOf(ws.route, AiStudioConfig.current(env))}$McpPath",
      "id" -> optString(server.map(McpVirtualServers.idOf)),
      "name" -> server.flatMap(s => s.select("name").asOptString).getOrElse(""),
      "description" -> server.flatMap(s => s.select("description").asOptString).getOrElse(""),
      "enabled" -> !server.exists(_.select("enabled").asOpt[Boolean].contains(false)),
      "functions" -> stringsOf(config.select("refs")),
      "connectors" -> stringsOf(config.select("mcp_refs")),
    )
  }

  private def saveMcpServer(ws: Workspace, form: JsObject)(using call: AiStudioApiRequest): Future[JsObject] = {
    for {
      existing <- mcpServer(ws)
      functions <- Functions.list(ws.id)
      connectors <- McpConnectors.list(ws.id)
      current = mcpServerJson(ws, existing)
      base = existing.getOrElse(McpVirtualServers.template())
      name = (if (has(form, "name")) string(form, "name") else current.select("name").asOptString).getOrElse("")
      _ = if (name.trim.isEmpty) throw badRequest("'name' is required")
      selectedFunctions = strings(form, "functions").getOrElse(stringsOf(current.select("functions")))
      selectedConnectors = strings(form, "connectors").getOrElse(stringsOf(current.select("connectors")))
      _ = selectedFunctions.filterNot(functions.map(Functions.idOf).contains).headOption
        .foreach(f => throw badRequest(s"the tool function '$f' does not belong to this workspace"))
      _ = selectedConnectors.filterNot(connectors.map(McpConnectors.idOf).contains).headOption
        .foreach(c => throw badRequest(s"the mcp connector '$c' does not belong to this workspace"))
      entity = base ++ Json.obj(
        "_loc" -> existing.flatMap(_.select("_loc").asOpt[JsObject]).getOrElse(ws.location).as[JsValue],
        "id" -> existing.map(McpVirtualServers.idOf).getOrElse(mcpServerIdOf(ws.id)),
        "name" -> name,
        "description" -> (if (has(form, "description")) string(form, "description").getOrElse("") else current.select("description").asOptString.getOrElse("")),
        "enabled" -> boolean(form, "enabled").getOrElse(current.select("enabled").asOpt[Boolean].getOrElse(true)),
        "tags" -> existing.flatMap(_.select("tags").asOpt[JsArray]).getOrElse(Json.arr()).as[JsValue],
        "metadata" -> (existing.map(e => objOf(e.select("metadata"))).getOrElse(Json.obj()) ++ workspaceMetadata(ws.id, MetaMcpServer)),
        // only the fields the studio owns are rewritten: anything else set on the entity from the otoroshi
        // console (oauth, scopes, zero-trust, overlays, registry publication…) is left as it is
        "config" -> (objOf(base.select("config")) ++ Json.obj(
          "name" -> name,
          "refs" -> selectedFunctions,
          "mcp_refs" -> selectedConnectors,
          // what the activity of the workspace reads
          "emit_audit_events" -> true,
        )),
      )
      saved <- if (existing.isDefined) McpVirtualServers.update(entity) else McpVirtualServers.create(entity)
      _ <- updateWorkspaceRoute(ws.id)(route => setOpenAiConfig(route, Json.obj("mcp_server_ref" -> McpVirtualServers.idOf(saved))))
      route <- workspace(ws.id)
    } yield mcpServerJson(route, Some(saved))
  }

  private def deleteMcpServer(ws: Workspace)(using call: AiStudioApiRequest): Future[Unit] =
    for {
      existing <- mcpServer(ws)
      _ <- updateWorkspaceRoute(ws.id)(route => setOpenAiConfig(route, Json.obj("mcp_server_ref" -> JsNull)))
      _ <- existing.map(s => McpVirtualServers.delete(McpVirtualServers.idOf(s))).getOrElse(().vfuture)
    } yield ()

  /////////////////////////////////////////////////////////////////////////////////////////////////
  // analytics (see lib/analytics.js)
  /////////////////////////////////////////////////////////////////////////////////////////////////

  // the llm queries of the extension, always narrowed to the route and the tenant of the workspace
  private def runAnalyticsQuery(ws: Workspace, form: JsObject): Future[Result] = {
    val queryId = string(form, "query").getOrElse(throw badRequest("'query' is required"))
    if (!queryId.startsWith("cloudapim_llm_") && !queryId.startsWith("cloudapim_mcp_")) throw badRequest("only the llm and mcp queries of the extension (cloudapim_llm_*, cloudapim_mcp_*) can be run on a workspace")
    if (!(env.clusterConfig.mode.isOff || env.clusterConfig.mode.isLeader)) throw notFound("leader-only endpoint")
    val filters = Filters.fromJson(obj(form, "filters").getOrElse(Json.obj()) ++ Json.obj("route_id" -> routeIdOf(ws.id))).copy(tenant = Some(ws.tenant))
    AnalyticsRuntime.executor match {
      case None => Results.InternalServerError(Json.obj("error" -> "internal_error", "error_description" -> "analytics runtime not initialized")).vfuture
      case Some(executor) =>
        executor.run(queryId, filters, obj(form, "params").getOrElse(Json.obj()), string(form, "bucket"), boolean(form, "compare").getOrElse(false), boolean(form, "nocache").getOrElse(false)).map {
          case Left(err) if err.contains("no active") => Results.PreconditionFailed(Json.obj("error" -> "precondition_failed", "error_description" -> err))
          case Left(err) if err.startsWith("unknown") => Results.NotFound(Json.obj("error" -> "not_found", "error_description" -> err))
          case Left(err) => Results.BadRequest(Json.obj("error" -> "bad_request", "error_description" -> err))
          case Right(res) => Results.Ok(res)
        }
    }
  }

  /////////////////////////////////////////////////////////////////////////////////////////////////
  // routes
  /////////////////////////////////////////////////////////////////////////////////////////////////

  private def route(method: String, path: String, wantsBody: Boolean = false)(handle: AiStudioApiRequest ?=> Future[Result]): AdminExtensionAdminApiRoute =
    AdminExtensionAdminApiRoute(
      method = method,
      path = s"$apiPath$path",
      wantsBody = wantsBody,
      handle = (ctx: AdminExtensionRouterContext[AdminExtensionAdminApiRoute], req: RequestHeader, apikey: ApiKey, body: Option[Source[ByteString, ?]]) => {
        val fuBody: Future[JsValue] = body match {
          case None => JsNull.vfuture
          case Some(src) => src.runFold(ByteString.empty)(_ ++ _).map { bytes =>
            if (bytes.isEmpty) JsNull else Try(Json.parse(bytes.utf8String)).getOrElse(throw badRequest("the body is not valid json"))
          }
        }
        fuBody.flatMap(json => handle(using AiStudioApiRequest(ctx, req, apikey, json))).recover {
          case e: AiStudioApiError => e.result
          case e: Throwable =>
            logger.error(s"error while handling ai studio api call $method ${req.path}", e)
            Results.InternalServerError(Json.obj("error" -> "internal_error", "error_description" -> e.getMessage))
        }
      }
    )

  private def call(using r: AiStudioApiRequest): AiStudioApiRequest = r

  private def withWorkspace(f: Workspace => Future[Result])(using r: AiStudioApiRequest): Future[Result] = workspace(r.param("id")).flatMap(f)

  private def ok(json: JsValue): Result = Results.Ok(json)

  val routes: Seq[AdminExtensionAdminApiRoute] = Seq(

    route("GET", "/catalog") {
      ok(Json.obj("providers" -> AiStudioCatalog.json)).vfuture
    },

    // workspaces

    route("GET", "/workspaces") {
      val config = AiStudioConfig.current(env)
      workspaceRoutes().map(routes => ok(JsArray(routes.map(r => workspaceJson(r, config)).sortBy(_.select("name").asString.toLowerCase))))
    },
    route("POST", "/workspaces", wantsBody = true) {
      createWorkspace(call.form).map(ws => Results.Created(ws))
    },
    route("GET", "/workspaces/:id") {
      withWorkspace(ws => ok(workspaceJson(ws.route, AiStudioConfig.current(env))).vfuture)
    },
    route("PUT", "/workspaces/:id", wantsBody = true) {
      updateWorkspace(call.param("id"), call.form).map(ok)
    },
    route("PATCH", "/workspaces/:id", wantsBody = true) {
      updateWorkspace(call.param("id"), call.form).map(ok)
    },
    route("DELETE", "/workspaces/:id") {
      deleteWorkspace(call.param("id")).map(_ => Results.NoContent)
    },
    route("GET", "/workspaces/:id/models") {
      withWorkspace(ws => ext.studio.workspaceModels(ws.compat, call.flag("force")).map(ok))
    },

    // providers

    route("GET", "/workspaces/:id/providers") {
      withWorkspace(ws => realConnections(ws.id).map(list => ok(JsArray(list.map(connectionJson)))))
    },
    route("POST", "/workspaces/:id/providers", wantsBody = true) {
      withWorkspace(ws => createConnection(ws, call.form).map(c => Results.Created(c)))
    },
    route("POST", "/workspaces/:id/providers/_models", wantsBody = true) {
      withWorkspace { ws =>
        val form = call.form
        val kind = string(form, "kind").getOrElse(throw badRequest("'kind' is required"))
        val entry = catalogEntry(kind).getOrElse(throw badRequest(s"unknown provider kind '$kind', see the catalog"))
        val fresh = newConnection(entry, Seq.empty)
        val conn = fresh.copy(
          baseUrl = string(form, "base_url").getOrElse(fresh.baseUrl),
          token = string(form, "token").getOrElse(""),
          fields = fresh.fields ++ obj(form, "fields").map(_.value.toMap).getOrElse(Map.empty),
        )
        fetchProviderModels(ws, conn, call.flag("force"))
      }
    },
    route("GET", "/workspaces/:id/providers/:cid") {
      withWorkspace(ws => connection(ws.id, call.param("cid")).map(c => ok(connectionJson(c))))
    },
    route("PUT", "/workspaces/:id/providers/:cid", wantsBody = true) {
      withWorkspace(ws => updateConnection(ws, call.param("cid"), call.form).map(ok))
    },
    route("DELETE", "/workspaces/:id/providers/:cid") {
      withWorkspace(ws => deleteConnection(ws, call.param("cid")).map(_ => Results.NoContent))
    },
    route("GET", "/workspaces/:id/providers/:cid/models") {
      withWorkspace(ws => connection(ws.id, call.param("cid")).flatMap(c => fetchProviderModels(ws, c, call.flag("force"))))
    },

    // api keys

    route("GET", "/workspaces/:id/apikeys") {
      withWorkspace { ws =>
        val config = AiStudioConfig.current(env)
        for {
          keys <- Apikeys.list(ws.id)
          budgets <- Budgets.list(ws.id)
        } yield ok(JsArray(keys.sortBy(_.select("clientName").asOptString.getOrElse("")).map(k => apikeyJson(k, budgets, config))))
      }
    },
    route("POST", "/workspaces/:id/apikeys", wantsBody = true) {
      withWorkspace(ws => saveApikey(ws, call.form, None).map(k => Results.Created(k)))
    },
    route("GET", "/workspaces/:id/apikeys/:kid") {
      withWorkspace { ws =>
        for {
          key <- workspaceApikey(ws, call.param("kid"))
          budgets <- Budgets.list(ws.id)
        } yield ok(apikeyJson(key, budgets, AiStudioConfig.current(env)))
      }
    },
    route("PUT", "/workspaces/:id/apikeys/:kid", wantsBody = true) {
      withWorkspace(ws => workspaceApikey(ws, call.param("kid")).flatMap(key => saveApikey(ws, call.form, Some(key))).map(ok))
    },
    route("DELETE", "/workspaces/:id/apikeys/:kid") {
      withWorkspace { ws =>
        for {
          key <- workspaceApikey(ws, call.param("kid"))
          budgets <- Budgets.list(ws.id)
          _ <- keyBudgetOf(budgets, Apikeys.idOf(key)).map(b => Budgets.delete(Budgets.idOf(b))).getOrElse(().vfuture)
          _ <- Apikeys.delete(Apikeys.idOf(key))
        } yield Results.NoContent
      }
    },

    // budgets

    route("GET", "/workspaces/:id/budgets") {
      withWorkspace { ws =>
        Budgets.list(ws.id).flatMap { budgets =>
          Future.sequence(budgets.sortBy(_.select("name").asOptString.getOrElse("")).map { b =>
            (if (call.flag("consumption")) consumptionOf(Budgets.idOf(b)) else None.vfuture).map(c => budgetJson(b, c))
          })
        }.map(list => ok(JsArray(list)))
      }
    },
    route("POST", "/workspaces/:id/budgets", wantsBody = true) {
      withWorkspace { ws =>
        Apikeys.list(ws.id).flatMap { keys =>
          saveBudget(ws, budgetFromForm(call.form, None, keys.map(Apikeys.idOf)), None)
        }.map(b => Results.Created(budgetJson(b, None)))
      }
    },
    route("GET", "/workspaces/:id/budgets/:bid") {
      withWorkspace(ws => workspaceBudget(ws, call.param("bid")).flatMap(b => consumptionOf(Budgets.idOf(b)).map(c => ok(budgetJson(b, c)))))
    },
    route("PUT", "/workspaces/:id/budgets/:bid", wantsBody = true) {
      withWorkspace { ws =>
        for {
          existing <- workspaceBudget(ws, call.param("bid"))
          keys <- Apikeys.list(ws.id)
          saved <- saveBudget(ws, budgetFromForm(call.form, Some(existing), keys.map(Apikeys.idOf)), Some(existing))
        } yield ok(budgetJson(saved, None))
      }
    },
    route("DELETE", "/workspaces/:id/budgets/:bid") {
      withWorkspace(ws => workspaceBudget(ws, call.param("bid")).flatMap(b => Budgets.delete(Budgets.idOf(b))).map(_ => Results.NoContent))
    },
    route("GET", "/workspaces/:id/budgets/:bid/consumption") {
      withWorkspace { ws =>
        workspaceBudget(ws, call.param("bid")).flatMap(b => consumptionOf(Budgets.idOf(b))).map {
          case None => Results.NotFound(Json.obj("error" -> "not_found", "error_description" -> "no consumption for this budget yet"))
          case Some(c) => ok(c)
        }
      }
    },
    route("POST", "/workspaces/:id/budgets/:bid/consumption/_reset") {
      withWorkspace { ws =>
        workspaceBudget(ws, call.param("bid")).flatMap { b =>
          ext.datastores.budgetsDataStore.findById(Budgets.idOf(b)).flatMap {
            case None => Results.NotFound(Json.obj("error" -> "not_found", "error_description" -> "budget not found")).vfuture
            case Some(budget) if call.flag("all") => budget.resetAll().map(_ => ok(Json.obj("done" -> true)))
            case Some(budget) => budget.resetCurrentCycle().map(_ => ok(Json.obj("done" -> true)))
          }
        }
      }
    },

    // guardrails and model access

    route("GET", "/workspaces/:id/guardrails") {
      withWorkspace(ws => Providers.list(ws.id).map(p => ok(guardrailsJson(p))))
    },
    route("PUT", "/workspaces/:id/guardrails", wantsBody = true) {
      withWorkspace(ws => saveGuardrails(ws, call.form).map(ok))
    },
    route("GET", "/workspaces/:id/model-access") {
      withWorkspace(ws => allModelEntities(ws.id).map(e => ok(modelAccessJson(e))))
    },
    route("PUT", "/workspaces/:id/model-access", wantsBody = true) {
      withWorkspace(ws => saveModelAccess(ws, call.form).map(ok))
    },

    // routing

    route("GET", "/workspaces/:id/routing") {
      withWorkspace(ws => Providers.list(ws.id).map(p => ok(routingJson(ws, p))))
    },
    route("PUT", "/workspaces/:id/routing", wantsBody = true) {
      withWorkspace(ws => saveRouting(ws, call.form).map(ok))
    },
    route("GET", "/workspaces/:id/load-balancers") {
      withWorkspace(ws => Providers.list(ws.id).map(p => ok(JsArray(p.filter(_.select("provider").asOptString.contains("loadbalancer")).map(balancerJson)))))
    },
    route("POST", "/workspaces/:id/load-balancers", wantsBody = true) {
      withWorkspace(ws => saveBalancer(ws, call.form, None).map(b => Results.Created(b)))
    },
    route("GET", "/workspaces/:id/load-balancers/:lid") {
      withWorkspace(ws => virtualProvider(ws, call.param("lid"), "loadbalancer").map(b => ok(balancerJson(b))))
    },
    route("PUT", "/workspaces/:id/load-balancers/:lid", wantsBody = true) {
      withWorkspace(ws => virtualProvider(ws, call.param("lid"), "loadbalancer").flatMap(b => saveBalancer(ws, call.form, Some(b))).map(ok))
    },
    route("DELETE", "/workspaces/:id/load-balancers/:lid") {
      withWorkspace(ws => deleteVirtual(ws, call.param("lid"), "loadbalancer").map(_ => Results.NoContent))
    },
    route("GET", "/workspaces/:id/routers") {
      withWorkspace(ws => Providers.list(ws.id).map(p => ok(JsArray(p.filter(_.select("provider").asOptString.contains("otoroshi")).map(routerJson)))))
    },
    route("POST", "/workspaces/:id/routers", wantsBody = true) {
      withWorkspace(ws => saveRouter(ws, call.form, None).map(r => Results.Created(r)))
    },
    route("GET", "/workspaces/:id/routers/:rid") {
      withWorkspace(ws => virtualProvider(ws, call.param("rid"), "otoroshi").map(r => ok(routerJson(r))))
    },
    route("PUT", "/workspaces/:id/routers/:rid", wantsBody = true) {
      withWorkspace(ws => virtualProvider(ws, call.param("rid"), "otoroshi").flatMap(r => saveRouter(ws, call.form, Some(r))).map(ok))
    },
    route("DELETE", "/workspaces/:id/routers/:rid") {
      withWorkspace(ws => deleteVirtual(ws, call.param("rid"), "otoroshi").map(_ => Results.NoContent))
    },

    // presets

    route("GET", "/workspaces/:id/presets") {
      withWorkspace { ws =>
        for {
          presets <- Contexts.list(ws.id)
          providers <- Providers.list(ws.id)
        } yield ok(JsArray(presets.sortBy(_.select("name").asOptString.getOrElse("")).map(p => presetJson(p, providers))))
      }
    },
    route("POST", "/workspaces/:id/presets", wantsBody = true) {
      withWorkspace(ws => savePreset(ws, call.form, None).map(p => Results.Created(p)))
    },
    route("GET", "/workspaces/:id/presets/:pid") {
      withWorkspace { ws =>
        for {
          preset <- workspacePreset(ws, call.param("pid"))
          providers <- Providers.list(ws.id)
        } yield ok(presetJson(preset, providers))
      }
    },
    route("PUT", "/workspaces/:id/presets/:pid", wantsBody = true) {
      withWorkspace(ws => workspacePreset(ws, call.param("pid")).flatMap(p => savePreset(ws, call.form, Some(p))).map(ok))
    },
    route("DELETE", "/workspaces/:id/presets/:pid") {
      withWorkspace(ws => deletePreset(ws, call.param("pid")).map(_ => Results.NoContent))
    },

    // tools

    route("GET", "/workspaces/:id/tools/:kind") {
      withWorkspace { ws =>
        val kind = toolKind(call.param("kind"))
        for {
          tools <- kind.entities.list(ws.id)
          providers <- Providers.list(ws.id)
        } yield ok(JsArray(tools.sortBy(_.select("name").asOptString.getOrElse("")).map(t => toolFormOf(kind, t, providers))))
      }
    },
    route("POST", "/workspaces/:id/tools/:kind", wantsBody = true) {
      withWorkspace(ws => saveTool(ws, toolKind(call.param("kind")), call.form, None).map(t => Results.Created(t)))
    },
    route("GET", "/workspaces/:id/tools/:kind/:tid") {
      withWorkspace { ws =>
        val kind = toolKind(call.param("kind"))
        for {
          tool <- workspaceTool(ws, kind, call.param("tid"))
          providers <- Providers.list(ws.id)
        } yield ok(toolFormOf(kind, tool, providers))
      }
    },
    route("PUT", "/workspaces/:id/tools/:kind/:tid", wantsBody = true) {
      withWorkspace { ws =>
        val kind = toolKind(call.param("kind"))
        workspaceTool(ws, kind, call.param("tid")).flatMap(t => saveTool(ws, kind, call.form, Some(t))).map(ok)
      }
    },
    route("DELETE", "/workspaces/:id/tools/:kind/:tid") {
      withWorkspace(ws => deleteTool(ws, toolKind(call.param("kind")), call.param("tid")).map(_ => Results.NoContent))
    },

    // mcp server

    route("GET", "/workspaces/:id/mcp-server") {
      withWorkspace(ws => mcpServer(ws).map(server => ok(mcpServerJson(ws, server))))
    },
    route("PUT", "/workspaces/:id/mcp-server", wantsBody = true) {
      withWorkspace(ws => saveMcpServer(ws, call.form).map(ok))
    },
    route("DELETE", "/workspaces/:id/mcp-server") {
      withWorkspace(ws => deleteMcpServer(ws).map(_ => Results.NoContent))
    },

    // analytics

    route("POST", "/workspaces/:id/analytics/_query", wantsBody = true) {
      withWorkspace(ws => runAnalyticsQuery(ws, call.form))
    },
  )
}
