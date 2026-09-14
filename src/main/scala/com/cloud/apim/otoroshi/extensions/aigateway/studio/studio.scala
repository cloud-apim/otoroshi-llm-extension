package com.cloud.apim.otoroshi.extensions.aigateway.studio

import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Source, StreamConverters}
import org.apache.pekko.util.ByteString
import otoroshi.env.Env
import otoroshi.models.{BackOfficeUser, EntityLocation}
import otoroshi.next.extensions.*
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.http.HttpEntity
import play.api.libs.json.*
import play.api.libs.ws.WSBodyWritables.given
import play.api.mvc.{RequestHeader, Result, Results}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

/**
 * Global configuration of AI Studio, edited from the Otoroshi danger zone and stored in
 * `globalConfig.extensions.cloud-apim_extensions_LlmExtension.aistudio`.
 */
case class AiStudioConfig(raw: JsObject) {
  def enabled: Boolean = raw.select("enabled").asOptBoolean.getOrElse(true)
  def domain(env: Env): String = raw.select("domain").asOptString.map(_.trim).filter(_.nonEmpty).getOrElse(env.domain)
  def exposure: String = raw.select("exposure").asOptString.filter(v => v == "subdomain" || v == "path").getOrElse("subdomain")
  def routePath: String = raw.select("route_path").asOptString.map(_.trim).filter(_.startsWith("/")).getOrElse("/v1")
  def publicScheme(env: Env): String = raw.select("public_scheme").asOptString.filter(_.nonEmpty).getOrElse(env.exposedRootScheme)
  def publicPort(env: Env): String = raw.select("public_port").asOpt[JsValue] match {
    case Some(JsNumber(v)) if v.toInt == 80 || v.toInt == 443 => ""
    case Some(JsNumber(v))                                    => s":${v.toInt}"
    case Some(JsString(v)) if v.trim.nonEmpty                 => if (v.trim.startsWith(":")) v.trim else s":${v.trim}"
    case _                                                    => env.bestExposedPort
  }
  def conversationsStore: String = raw.select("conversations_store").asOptString.filter(v => v == "redis" || v == "postgresql").getOrElse("redis")
  def conversationsPgUri: Option[String] = raw.select("conversations_postgresql_uri").asOptString.map(_.trim).filter(_.nonEmpty)
  def conversationsPgSchema: String = raw.select("conversations_postgresql_schema").asOptString.map(_.trim).filter(_.matches("^[a-zA-Z_][a-zA-Z0-9_]{0,62}$")).getOrElse("public")
  def conversationsPgPoolSize: Int = raw.select("conversations_postgresql_pool_size").asOpt[Int].filter(_ > 0).getOrElse(5)
  def quota(name: String): Long = raw.select(name).asOpt[Long].getOrElse(10000000L)

  def frontendJson(env: Env): JsObject = Json.obj(
    "enabled" -> enabled,
    "domain" -> domain(env),
    "exposure" -> exposure,
    "route_path" -> routePath,
    "public_scheme" -> publicScheme(env),
    "public_port" -> publicPort(env),
    "default_throttling_quota" -> quota("default_throttling_quota"),
    "default_daily_quota" -> quota("default_daily_quota"),
    "default_monthly_quota" -> quota("default_monthly_quota"),
    "conversations_store" -> conversationsStore,
  )
}

object AiStudioConfig {
  val configKey: String = AiExtension.id.value.replace(".", "_")
  def current(env: Env): AiStudioConfig = {
    val gc = env.datastores.globalConfigDataStore.latest()(using env.otoroshiExecutionContext, env)
    AiStudioConfig(gc.extensions.get(configKey).flatMap(_.select("aistudio").asOpt[JsObject]).getOrElse(Json.obj()))
  }
}

class AiStudio(env: Env, ext: AiExtension) {

  private given ec: ExecutionContext = env.otoroshiExecutionContext
  private given mat: Materializer = env.otoroshiMaterializer
  private given ev: Env = env

  val basePath = "/extensions/cloud-apim/ai-studio"
  val assetsPath = "/extensions/assets/cloud-apim/extensions/ai-extension/studio"
  val apiPath = "/extensions/cloud-apim/extensions/ai-extension/studio"
  private val resourcesRoot = "cloudapim/extensions/ai/studio"

  private val assetsCache = new UnboundedTrieMap[String, Option[ByteString]]()

  private lazy val redisConversations: ConversationStore = new RedisConversationStore(env, ext)
  private val pgConversations = new UnboundedTrieMap[String, PostgresConversationStore]()

  // resolved on every call so a change in the danger zone applies right away
  def conversations: Future[ConversationStore] = {
    val config = AiStudioConfig.current(env)
    (config.conversationsStore, config.conversationsPgUri) match {
      case ("postgresql", Some(rawUri)) =>
        env.vaults.fillSecretsAsync("ai-studio-conversations", rawUri).map { uri =>
          val key = s"$uri|${config.conversationsPgSchema}|${config.conversationsPgPoolSize}".sha256
          pgConversations.getOrElseUpdate(key, new PostgresConversationStore(env, uri, config.conversationsPgSchema, config.conversationsPgPoolSize))
        }
      case _ => redisConversations.vfuture
    }
  }

  private def readResource(path: String): Option[ByteString] = {
    def read() = env.environment.resourceAsStream(path).map { stream =>
      StreamConverters.fromInputStream(() => stream).runFold(ByteString.empty)(_ ++ _).awaitf(10.seconds)
    }
    if (env.isDev) read() else assetsCache.getOrElseUpdate(path, read())
  }

  private def unauthorized: Future[Result] = Results.Unauthorized(Json.obj("error" -> "unauthorized", "error_description" -> "you're not logged in")).vfuture

  private def canRead(user: BackOfficeUser, location: EntityLocation): Boolean =
    user.rights.canReadTeams(location.tenant, location.teams)

  private def canWrite(user: BackOfficeUser, location: EntityLocation): Boolean =
    user.rights.canWriteTeams(location.tenant, location.teams)

  /////////////////////////////////////////////////////////////////////////////////////////////////
  // page and assets
  /////////////////////////////////////////////////////////////////////////////////////////////////

  private def entryAssets(): (Seq[String], Seq[String]) = {
    readResource(s"$resourcesRoot/manifest.json").map(_.utf8String.parseJson) match {
      case None => (Seq.empty, Seq.empty)
      case Some(manifest) =>
        val entry = manifest.select("src/main.jsx").asOpt[JsObject].getOrElse(Json.obj())
        val js = entry.select("file").asOptString.toSeq.map(f => s"$assetsPath/$f")
        val css = entry.select("css").asOpt[Seq[String]].getOrElse(Seq.empty).map(f => s"$assetsPath/$f")
        (js, css)
    }
  }

  val themePreference = "ai_studio_theme"

  private def themeOf(u: BackOfficeUser): Future[String] =
    env.datastores.adminPreferencesDatastore.getPreference(u.email, themePreference)
      .map(_.flatMap(_.asOpt[String]).filter(t => t == "light" || t == "dark" || t == "system").getOrElse("system"))
      .recover { case _ => "system" }

  private def bootstrapJson(u: BackOfficeUser, config: AiStudioConfig, theme: String): JsObject = Json.obj(
    "theme" -> theme,
    "basePath" -> basePath,
    "adminUrl" -> "/bo/dashboard",
    "apiPath" -> apiPath,
    "extensionId" -> AiExtension.id.value,
    "user" -> Json.obj(
      "email" -> u.email,
      "name" -> u.name,
      "superAdmin" -> u.rights.superAdmin,
      "rights" -> u.rights.json,
    ),
    "config" -> config.frontendJson(env),
    "otoroshi" -> Json.obj("version" -> env.otoroshiVersion, "domain" -> env.domain),
  )

  // used by the vite dev server (see ui/ai-studio/README.md) that cannot get the values injected in the page
  def handleBootstrap(ctx: AdminExtensionRouterContext[AdminExtensionBackofficeAuthRoute], req: RequestHeader, user: Option[BackOfficeUser], body: Option[Source[ByteString, ?]]): Future[Result] = {
    user match {
      case None => unauthorized
      case Some(u) => themeOf(u).map(theme => Results.Ok(bootstrapJson(u, AiStudioConfig.current(env), theme)))
    }
  }

  def handlePage(ctx: AdminExtensionRouterContext[AdminExtensionBackofficeAuthRoute], req: RequestHeader, user: Option[BackOfficeUser], body: Option[Source[ByteString, ?]]): Future[Result] = {
    user match {
      case None =>
        Results.Redirect("/bo/dashboard")
          .addingToSession("bo-redirect-after-login" -> s"${env.rootScheme}${req.host}${req.uri}")(using req)
          .vfuture
      case Some(u) =>
        val config = AiStudioConfig.current(env)
        if (!config.enabled) {
          Results.NotFound(Json.obj("error" -> "not_found", "error_description" -> "AI Studio is disabled")).vfuture
        } else themeOf(u).map { theme =>
          val bootstrap = bootstrapJson(u, config, theme).stringify.replace("<", "\\u003c")
          val (scripts, styles) = entryAssets()
          val html =
            s"""<!doctype html>
               |<html lang="en">
               |<head>
               |  <meta charset="utf-8" />
               |  <meta name="viewport" content="width=device-width, initial-scale=1" />
               |  <title>AI Studio - Otoroshi</title>
               |  <link rel="icon" type="image/png" href="/__otoroshi_assets/images/otoroshi-logo-color.png" />
               |  <script>window.__AI_STUDIO__ = $bootstrap;(function(){try{var t=window.__AI_STUDIO__.theme;if(t!=='dark'&&t!=='light'){t=window.matchMedia&&window.matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light'}document.documentElement.setAttribute('data-theme',t)}catch(e){}})();</script>
               |  ${styles.map(s => s"""<link rel="stylesheet" href="$s" />""").mkString("\n  ")}
               |  ${scripts.map(s => s"""<script type="module" src="$s"></script>""").mkString("\n  ")}
               |</head>
               |<body>
               |  <div id="root"></div>
               |</body>
               |</html>""".stripMargin
          Results.Ok(html).as("text/html; charset=utf-8").withHeaders("Cache-Control" -> "no-cache, no-store")
        }
    }
  }

  private def contentTypeOf(path: String): String = path.split("\\.").lastOption.map(_.toLowerCase) match {
    case Some("js")    => "application/javascript"
    case Some("css")   => "text/css"
    case Some("json")  => "application/json"
    case Some("svg")   => "image/svg+xml"
    case Some("png")   => "image/png"
    case Some("woff2") => "font/woff2"
    case Some("woff")  => "font/woff"
    case _             => "application/octet-stream"
  }

  def handleAsset(ctx: AdminExtensionRouterContext[AdminExtensionAssetRoute], req: RequestHeader): Future[Result] = {
    val path = req.path.stripPrefix(assetsPath).stripPrefix("/")
    if (path.isEmpty || path.contains("..") || path == "manifest.json") {
      Results.NotFound("not found").vfuture
    } else {
      readResource(s"$resourcesRoot/$path") match {
        case None => Results.NotFound("not found").vfuture
        case Some(bytes) =>
          val immutable = path.startsWith("assets/")
          Results.Ok(bytes)
            .as(contentTypeOf(path))
            .withHeaders("Cache-Control" -> (if (immutable && !env.isDev) "public, max-age=31536000, immutable" else "no-cache"))
            .vfuture
      }
    }
  }

  /////////////////////////////////////////////////////////////////////////////////////////////////
  // api
  /////////////////////////////////////////////////////////////////////////////////////////////////

  def handleCatalog(ctx: AdminExtensionRouterContext[AdminExtensionBackofficeAuthRoute], req: RequestHeader, user: Option[BackOfficeUser], body: Option[Source[ByteString, ?]]): Future[Result] = {
    user match {
      case None => unauthorized
      case Some(_) => Results.Ok(Json.obj("providers" -> AiStudioCatalog.json)).vfuture
    }
  }

  private def withWorkspaceRoute(wsId: String, user: BackOfficeUser, write: Boolean)(f: otoroshi.next.models.NgRoute => Future[Result]): Future[Result] = {
    env.datastores.routeDataStore.findById(s"route_ai_studio_$wsId").flatMap {
      case None => Results.NotFound(Json.obj("error" -> "not_found", "error_description" -> "workspace not found")).vfuture
      case Some(route) if !route.metadata.get("ai_studio_workspace").contains(wsId) =>
        Results.NotFound(Json.obj("error" -> "not_found", "error_description" -> "workspace not found")).vfuture
      case Some(route) if (write && !canWrite(user, route.location)) || !canRead(user, route.location) =>
        Results.Forbidden(Json.obj("error" -> "forbidden", "error_description" -> "you cannot access this workspace")).vfuture
      case Some(route) => f(route)
    }
  }

  // Forwards a call to the OpenAI compatible route of the workspace, authenticated with one of the
  // workspace api keys, so quotas, budgets, guardrails and audit events apply for real.
  def handleWorkspaceProxy(ctx: AdminExtensionRouterContext[AdminExtensionBackofficeAuthRoute], req: RequestHeader, user: Option[BackOfficeUser], body: Option[Source[ByteString, ?]]): Future[Result] = {
    user match {
      case None => unauthorized
      case Some(u) =>
        val wsId = ctx.named("id").getOrElse("--")
        withWorkspaceRoute(wsId, u, write = false) { route =>
          req.headers.get("X-Ai-Studio-Apikey") match {
            case None => Results.BadRequest(Json.obj("error" -> "bad_request", "error_description" -> "no api key selected")).vfuture
            case Some(clientId) =>
              env.datastores.apiKeyDataStore.findById(clientId).flatMap {
                case Some(apikey) if apikey.metadata.get("ai_studio_workspace").contains(wsId) && canRead(u, apikey.location) =>
                  val domain = route.frontend.domains.head
                  val path = req.path.split(s"/workspaces/$wsId/proxy", 2).lastOption.getOrElse("/")
                  val basePath = domain.path.stripSuffix("/")
                  val query = req.rawQueryString match {
                    case "" => ""
                    case q  => s"?$q"
                  }
                  val url = s"http://127.0.0.1:${env.httpPort}$basePath$path$query"
                  val headers = Seq(
                    "Host" -> domain.domain,
                    "Authorization" -> s"Bearer ${apikey.toBearer()}",
                    // lets the workspace route report this call as made by the studio user
                    otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.AiStudioUserHeader.name ->
                      otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.AiStudioUserHeader.sign(u.email, u.name, env),
                    "Accept" -> req.headers.get("Accept").getOrElse("application/json"),
                  ) ++ req.headers.get("Content-Type").map(ct => "Content-Type" -> ct).toSeq
                  val builder = env.Ws
                    .url(url)
                    .withHttpHeaders(headers*)
                    .withMethod(req.method)
                    .withFollowRedirects(false)
                    .withRequestTimeout(10.minutes)
                  body.filter(_ => req.method != "GET" && req.method != "HEAD")
                    .map(b => builder.withBody(b))
                    .getOrElse(builder)
                    .stream()
                    .map { resp =>
                      val contentType = resp.headers.get("Content-Type").flatMap(_.headOption).getOrElse("application/json")
                      val forwarded = resp.headers.collect {
                        case (k, v) if k.toLowerCase.startsWith("x-otoroshi-llm") || k.toLowerCase.startsWith("x-ratelimit") => k -> v.mkString(",")
                      }.toSeq
                      Results.Status(resp.status)
                        .sendEntity(HttpEntity.Streamed(resp.bodyAsSource, None, Some(contentType)))
                        .withHeaders(forwarded*)
                    }
                    .recover { case e: Throwable =>
                      Results.BadGateway(Json.obj("error" -> "bad_gateway", "error_description" -> e.getMessage))
                    }
                case _ => Results.Forbidden(Json.obj("error" -> "forbidden", "error_description" -> "api key not usable in this workspace")).vfuture
              }
          }
        }
    }
  }

  private val openAiCompatPlugin = "cp:otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.OpenAiCompatApi"

  private def modelOf(config: JsValue, modality: String): Option[String] = {
    val options = config.select("options")
    modality match {
      case "image" => options.select("generation").select("model").asOptString.orElse(options.select("model").asOptString)
      case "audio" => config.select("tts").select("model").asOptString
        .orElse(options.select("tts").select("model").asOptString)
        .orElse(config.select("stt").select("model").asOptString)
        .orElse(options.select("stt").select("model").asOptString)
      case _ => options.select("model").asOptString
    }
  }

  // Lists the models reachable through the workspace endpoint, with the same ids as the
  // `/models` endpoint of the OpenAI compatible plugin (`<provider>/<model>` when several providers).
  def handleWorkspaceModels(ctx: AdminExtensionRouterContext[AdminExtensionBackofficeAuthRoute], req: RequestHeader, user: Option[BackOfficeUser], body: Option[Source[ByteString, ?]]): Future[Result] = {
    user match {
      case None => unauthorized
      case Some(u) =>
        val wsId = ctx.named("id").getOrElse("--")
        val force = req.getQueryString("force").contains("true")
        withWorkspaceRoute(wsId, u, write = false) { route =>
          val config = route.plugins.slots.find(_.plugin == openAiCompatPlugin).map(_.config.raw).getOrElse(Json.obj())
          def refs(key: String): Seq[String] = config.select(key).asOpt[Seq[String]].getOrElse(Seq.empty)
          val textRefs = refs("language_model_refs")
          val now = System.currentTimeMillis() / 1000
          val fuText: Future[Seq[(JsObject, Seq[JsObject])]] = Future.sequence(textRefs.map { ref =>
            ext.datastores.providersDatastore.findById(ref).flatMap {
              case None => (Json.obj("id" -> ref, "error" -> "provider not found"), Seq.empty[JsObject]).vfuture
              case Some(provider) =>
                val info = Json.obj(
                  "id" -> provider.id,
                  "name" -> provider.name,
                  "slug" -> provider.slugName,
                  "kind" -> provider.provider,
                  "modality" -> "text",
                  "default_model" -> provider.options.select("model").asOptString.map(JsString.apply).getOrElse(JsNull).as[JsValue],
                )
                def toModels(models: Seq[String]): Seq[JsObject] = models.map { model =>
                  val id = if (textRefs.size == 1) model else if (model.contains("/")) s"${provider.slugName}###$model" else s"${provider.slugName}/$model"
                  Json.obj("id" -> id, "model" -> model, "provider" -> provider.slugName, "provider_id" -> provider.id, "provider_kind" -> provider.provider, "modality" -> "text", "created" -> now)
                }
                val token = provider.connection.select("token").asOptString.getOrElse("--")
                val key = s"${provider.id}-$token".sha256
                ext.modelsCache.getIfPresent(key).filterNot(_ => force) match {
                  case Some(models) => (info, toModels(models)).vfuture
                  case None =>
                    provider.getChatClient() match {
                      case None => (info ++ Json.obj("error" -> "no client"), Seq.empty[JsObject]).vfuture
                      case Some(client) =>
                        client.listModels(false, otoroshi.utils.TypedMap.empty).map {
                          case Left(err) =>
                            val fallback = provider.options.select("model").asOptString.toSeq
                            (info ++ Json.obj("error" -> err), toModels(fallback))
                          case Right(models) =>
                            ext.modelsCache.put(key, models)
                            (info, toModels(models))
                        }.recover { case e: Throwable =>
                          (info ++ Json.obj("error" -> e.getMessage), toModels(provider.options.select("model").asOptString.toSeq))
                        }
                    }
                }
            }
          })
          val others: Seq[(String, String, String => Future[Option[JsValue]])] = Seq(
            ("embedding", "embedding_model_refs", id => ext.datastores.embeddingModelsDataStore.findById(id).map(_.map(_.json))),
            ("image", "image_model_refs", id => ext.datastores.imageModelsDataStore.findById(id).map(_.map(_.json))),
            ("audio", "audio_model_refs", id => ext.datastores.AudioModelsDataStore.findById(id).map(_.map(_.json))),
            ("moderation", "moderation_model_refs", id => ext.datastores.moderationModelsDataStore.findById(id).map(_.map(_.json))),
            ("ocr", "ocr_model_refs", id => ext.datastores.ocrModelsDataStore.findById(id).map(_.map(_.json))),
          )
          val fuOthers: Future[Seq[(JsObject, Seq[JsObject])]] = Future.sequence(others.flatMap { case (modality, key, find) =>
            val all = refs(key)
            all.map { ref =>
              find(ref).map {
                case None => (Json.obj("id" -> ref, "modality" -> modality, "error" -> "entity not found"), Seq.empty[JsObject])
                case Some(entity) =>
                  val name = entity.select("name").asOptString.getOrElse(ref)
                  val slug = entity.select("metadata").select("endpoint_name").asOptString
                    .orElse(entity.select("metadata").select("provider_name").asOptString)
                    .getOrElse(name).slugifyWithSlash.replaceAll("-+", "_")
                  val model = modelOf(entity.select("config").asOpt[JsValue].getOrElse(Json.obj()), modality)
                  val info = Json.obj("id" -> ref, "name" -> name, "slug" -> slug, "kind" -> entity.select("provider").asOptString.getOrElse("--").json, "modality" -> modality, "default_model" -> model.map(JsString.apply).getOrElse(JsNull).as[JsValue])
                  val models = model.toSeq.map { m =>
                    val id = if (all.size == 1) m else if (m.contains("/")) s"$slug###$m" else s"$slug/$m"
                    Json.obj("id" -> id, "model" -> m, "provider" -> slug, "provider_id" -> ref, "provider_kind" -> entity.select("provider").asOptString.getOrElse("--").json, "modality" -> modality, "created" -> now)
                  }
                  (info, models)
              }
            }
          })
          for {
            text <- fuText
            other <- fuOthers
          } yield {
            val all = text ++ other
            Results.Ok(Json.obj(
              "providers" -> JsArray(all.map(_._1)),
              "models" -> JsArray(all.flatMap(_._2)),
            ))
          }
        }
    }
  }

  private def bodyJson(body: Option[Source[ByteString, ?]]): Future[JsValue] = body match {
    case None => JsNull.vfuture
    case Some(src) => src.runFold(ByteString.empty)(_ ++ _).map(b => if (b.isEmpty) JsNull else b.utf8String.parseJson)
  }

  private def userKey(user: BackOfficeUser): String = user.email.sha256.take(24)

  def handleConversations(ctx: AdminExtensionRouterContext[AdminExtensionBackofficeAuthRoute], req: RequestHeader, user: Option[BackOfficeUser], body: Option[Source[ByteString, ?]]): Future[Result] = {
    user match {
      case None => unauthorized
      case Some(u) =>
        val wsId = ctx.named("id").getOrElse("--")
        val convId = ctx.named("cid")
        withWorkspaceRoute(wsId, u, write = false) { _ =>
          conversations.flatMap { store =>
            (req.method, convId) match {
              case ("GET", None) =>
                store.list(wsId, userKey(u)).map(list => Results.Ok(JsArray(list)))
              case ("GET", Some(cid)) =>
                store.get(wsId, userKey(u), cid).map {
                  case None => Results.NotFound(Json.obj("error" -> "not_found"))
                  case Some(c) => Results.Ok(c)
                }
              case (method, Some(cid)) if method == "PUT" || method == "POST" =>
                bodyJson(body).flatMap {
                  case obj: JsObject =>
                    val conv = obj ++ Json.obj("id" -> cid, "updated_at" -> System.currentTimeMillis())
                    store.save(wsId, userKey(u), cid, conv).map(_ => Results.Ok(conv))
                  case _ => Results.BadRequest(Json.obj("error" -> "bad_request", "error_description" -> "conversation must be a json object")).vfuture
                }
              case ("DELETE", Some(cid)) =>
                store.delete(wsId, userKey(u), cid).map(_ => Results.NoContent)
              case _ => Results.MethodNotAllowed(Json.obj("error" -> "method_not_allowed")).vfuture
            }
          }.recover { case e: Throwable =>
            Results.InternalServerError(Json.obj("error" -> "conversations_store_error", "error_description" -> e.getMessage))
          }
        }
    }
  }

  def backofficeRoutes: Seq[AdminExtensionBackofficeAuthRoute] = Seq(
    AdminExtensionBackofficeAuthRoute("GET", basePath, wantsBody = false, handle = handlePage),
    AdminExtensionBackofficeAuthRoute("GET", s"$basePath/*", wantsBody = false, handle = handlePage),
    AdminExtensionBackofficeAuthRoute("GET", s"$apiPath/bootstrap", wantsBody = false, handle = handleBootstrap),
    AdminExtensionBackofficeAuthRoute("GET", s"$apiPath/catalog", wantsBody = false, handle = handleCatalog),
    AdminExtensionBackofficeAuthRoute("GET", s"$apiPath/workspaces/:id/models", wantsBody = false, handle = handleWorkspaceModels),
    AdminExtensionBackofficeAuthRoute("GET", s"$apiPath/workspaces/:id/proxy/*", wantsBody = false, handle = handleWorkspaceProxy),
    AdminExtensionBackofficeAuthRoute("POST", s"$apiPath/workspaces/:id/proxy/*", wantsBody = true, handle = handleWorkspaceProxy),
    AdminExtensionBackofficeAuthRoute("GET", s"$apiPath/workspaces/:id/conversations", wantsBody = false, handle = handleConversations),
    AdminExtensionBackofficeAuthRoute("GET", s"$apiPath/workspaces/:id/conversations/:cid", wantsBody = false, handle = handleConversations),
    AdminExtensionBackofficeAuthRoute("PUT", s"$apiPath/workspaces/:id/conversations/:cid", wantsBody = true, handle = handleConversations),
    AdminExtensionBackofficeAuthRoute("DELETE", s"$apiPath/workspaces/:id/conversations/:cid", wantsBody = false, handle = handleConversations),
  )

  def assetRoutes: Seq[AdminExtensionAssetRoute] = Seq(
    AdminExtensionAssetRoute(s"$assetsPath/*", handle = handleAsset)
  )
}

/**
 * Chat conversations of the AI Studio playground, scoped by workspace and by backoffice user.
 */
trait ConversationStore {
  def list(workspace: String, user: String): Future[Seq[JsObject]]
  def get(workspace: String, user: String, id: String): Future[Option[JsObject]]
  def save(workspace: String, user: String, id: String, conversation: JsObject): Future[Unit]
  def delete(workspace: String, user: String, id: String): Future[Unit]
}

class RedisConversationStore(env: Env, ext: AiExtension) extends ConversationStore {

  private given ec: ExecutionContext = env.otoroshiExecutionContext
  private given ev: Env = env

  private def prefix(workspace: String, user: String) =
    s"${env.storageRoot}:extensions:${ext.id.cleanup}:ai-studio:conversations:$workspace:$user"

  override def list(workspace: String, user: String): Future[Seq[JsObject]] = {
    env.datastores.rawDataStore.keys(s"${prefix(workspace, user)}:*").flatMap { keys =>
      if (keys.isEmpty) Seq.empty[JsObject].vfuture
      else env.datastores.rawDataStore.mget(keys).map { values =>
        values.flatten
          .flatMap(v => scala.util.Try(v.utf8String.parseJson.asObject).toOption)
          .map(c => Json.obj(
            "id" -> c.select("id").asOpt[JsValue].getOrElse(JsNull).as[JsValue],
            "title" -> c.select("title").asOpt[JsValue].getOrElse(JsNull).as[JsValue],
            "model" -> c.select("model").asOpt[JsValue].getOrElse(JsNull).as[JsValue],
            "updated_at" -> c.select("updated_at").asOpt[JsValue].getOrElse(JsNull).as[JsValue],
          ))
          .sortBy(c => -c.select("updated_at").asOpt[Long].getOrElse(0L))
      }
    }
  }

  override def get(workspace: String, user: String, id: String): Future[Option[JsObject]] =
    env.datastores.rawDataStore.get(s"${prefix(workspace, user)}:$id").map(_.flatMap(v => scala.util.Try(v.utf8String.parseJson.asObject).toOption))

  override def save(workspace: String, user: String, id: String, conversation: JsObject): Future[Unit] =
    env.datastores.rawDataStore.set(s"${prefix(workspace, user)}:$id", conversation.stringify.byteString, None).map(_ => ())

  override def delete(workspace: String, user: String, id: String): Future[Unit] =
    env.datastores.rawDataStore.del(Seq(s"${prefix(workspace, user)}:$id")).map(_ => ())
}

/**
 * Conversations stored in PostgreSQL, through an otoroshi stateful client (one pool per database uri).
 */
class PostgresConversationStore(env: Env, uri: String, schema: String, poolSize: Int) extends ConversationStore {

  import otoroshi.storage.drivers.reactivepg.pgimplicits.*
  import io.vertx.sqlclient.{Pool, Row, Tuple => VertxTuple}
  import scala.jdk.CollectionConverters.*

  private given ec: ExecutionContext = env.otoroshiExecutionContext

  private val table = s"$schema.ai_studio_conversations"

  private def pool: Pool = env.statefulClientsManager.client(s"ai-studio-conversations-${uri.sha256.take(12)}", otoroshi.statefulclients.PgStatefulClientConfig(uri, poolSize))

  private lazy val ready: Future[Unit] = {
    val ddl = Seq(
      s"CREATE SCHEMA IF NOT EXISTS $schema",
      s"""CREATE TABLE IF NOT EXISTS $table (
         |  workspace  TEXT        NOT NULL,
         |  user_key   TEXT        NOT NULL,
         |  id         TEXT        NOT NULL,
         |  title      TEXT,
         |  model      TEXT,
         |  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
         |  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
         |  data       JSONB       NOT NULL,
         |  PRIMARY KEY (workspace, user_key, id)
         |)""".stripMargin,
      s"CREATE INDEX IF NOT EXISTS idx_ai_studio_conversations_updated ON $table (workspace, user_key, updated_at DESC)",
    )
    ddl.foldLeft(Future.successful(())) { (acc, sql) => acc.flatMap(_ => pool.query(sql).executeAsync().map(_ => ())) }
  }

  private def run(sql: String, values: AnyRef*): Future[Seq[Row]] = ready.flatMap { _ =>
    pool.preparedQuery(sql).execute(VertxTuple.from(values.toArray)).scala.map(_.iterator().asScala.toList)
  }

  override def list(workspace: String, user: String): Future[Seq[JsObject]] =
    run(s"SELECT id, title, model, (extract(epoch from updated_at) * 1000)::bigint FROM $table WHERE workspace = $$1 AND user_key = $$2 ORDER BY updated_at DESC LIMIT 500", workspace, user).map { rows =>
      rows.map { r =>
        Json.obj(
          "id" -> r.getString(0),
          "title" -> Option(r.getString(1)).map(JsString.apply).getOrElse(JsNull).as[JsValue],
          "model" -> Option(r.getString(2)).map(JsString.apply).getOrElse(JsNull).as[JsValue],
          "updated_at" -> r.getLong(3).longValue(),
        )
      }
    }

  override def get(workspace: String, user: String, id: String): Future[Option[JsObject]] =
    run(s"SELECT data::text FROM $table WHERE workspace = $$1 AND user_key = $$2 AND id = $$3", workspace, user, id).map { rows =>
      rows.headOption.flatMap(r => scala.util.Try(Json.parse(r.getString(0)).asObject).toOption)
    }

  override def save(workspace: String, user: String, id: String, conversation: JsObject): Future[Unit] =
    run(
      s"""INSERT INTO $table (workspace, user_key, id, title, model, data, updated_at) VALUES ($$1, $$2, $$3, $$4, $$5, $$6::jsonb, now())
         |ON CONFLICT (workspace, user_key, id) DO UPDATE SET title = EXCLUDED.title, model = EXCLUDED.model, data = EXCLUDED.data, updated_at = now()""".stripMargin,
      workspace, user, id, conversation.select("title").asOptString.orNull, conversation.select("model").asOptString.orNull, conversation.stringify
    ).map(_ => ())

  override def delete(workspace: String, user: String, id: String): Future[Unit] =
    run(s"DELETE FROM $table WHERE workspace = $$1 AND user_key = $$2 AND id = $$3", workspace, user, id).map(_ => ())
}
