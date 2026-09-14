package com.cloud.apim.otoroshi.extensions.aigateway.analytics

import otoroshi.env.Env
import otoroshi.models.EntityLocation
import otoroshi.next.analytics.exporter.UserAnalyticsExporterSettings
import otoroshi.next.analytics.models.{UserDashboard, Widget}
import otoroshi.security.IdGenerator
import play.api.Logger
import play.api.libs.json.*

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.{ExecutionContext, Future}

final case class DashboardSpec(defaultId: String, name: String, description: String, widgets: Seq[Widget])

/**
 * Installs the extension's default dashboards as ordinary user dashboards.
 *
 * Seeded like the platform's own defaults: only the missing ones, matched on a metadata marker rather
 * than on the name, so a renamed dashboard is not brought back and an edited one is never overwritten.
 * A default nobody edited follows the extension's newer versions of it; deleting an edited one is the
 * way to get the current version back.
 */
object DashboardSeeding {

  private val logger = Logger("otoroshi-ai-extension-dashboards")

  val DefaultIdKey: String      = "otoroshi-default-id"
  val DefaultVersionKey: String = "otoroshi-default-version"
  val DefaultVersion: String    = "1"
  val DefaultHashKey: String    = "otoroshi-default-hash"

  private val seeded = new AtomicBoolean(false)

  def widget(
      id: String,
      title: String,
      query: String,
      kind: String,
      width: Int,
      height: Int,
      format: Option[String] = None,
      params: JsObject = Json.obj(),
      options: JsObject = Json.obj()
  ): Widget = Widget(
    id = id,
    title = title,
    query = query,
    params = params,
    `type` = kind,
    width = width,
    height = height,
    options = format.map(f => Json.obj("format" -> f)).getOrElse(Json.obj()) ++ options
  )

  private def build(spec: DashboardSpec)(using env: Env): UserDashboard = UserDashboard(
    location = EntityLocation.default,
    id = IdGenerator.namedId("dashboard", env),
    name = spec.name,
    description = spec.description,
    tags = Seq("cloud-apim", "llm-extension"),
    metadata = Map(DefaultIdKey -> spec.defaultId, DefaultVersionKey -> DefaultVersion, DefaultHashKey -> hash(spec)),
    enabled = true,
    widgets = spec.widgets,
    defaults = Json.obj()
  )

  /** What was installed: the parts a user edits. Anything else on the dashboard is theirs anyway. */
  def hash(name: String, description: String, widgets: Seq[Widget]): String = {
    val content = Json.stringify(Json.obj("name" -> name, "description" -> description, "widgets" -> JsArray(widgets.map(_.json))))
    java.security.MessageDigest.getInstance("SHA-256").digest(content.getBytes("UTF-8")).map("%02x".format(_)).mkString
  }

  def hash(spec: DashboardSpec): String = hash(spec.name, spec.description, spec.widgets)

  /**
   * A newer version of a default replaces the installed one only if nobody touched it: the installed
   * content still hashes to what was installed. An edited dashboard is the user's, and stays as is.
   */
  def upgradable(installed: UserDashboard, spec: DashboardSpec): Boolean =
    installed.metadata.get(DefaultHashKey).exists { installedHash =>
      installedHash == hash(installed.name, installed.description, installed.widgets) && installedHash != hash(spec)
    }

  /**
   * Leader-only (or standalone), and a no-op once done in this process.
   *
   * Nothing is installed until a user-analytics exporter is active, like the platform's own defaults:
   * without one the dashboards could only ever be empty, and an install that does not use analytics
   * should not find a handful of them in its list. Since this runs on every sync, activating an
   * exporter later brings them in on the next one.
   *
   * A dashboard that could not be seeded is a missing convenience, never a reason to fail a sync.
   */
  def seedIfMissing(specs: Seq[DashboardSpec])(using env: Env, ec: ExecutionContext): Future[Unit] = {
    if (seeded.get() || !(env.clusterConfig.mode.isOff || env.clusterConfig.mode.isLeader)) {
      Future.successful(())
    } else {
      UserAnalyticsExporterSettings.findActiveAnalyticsExporter
        .flatMap {
          case None    => Future.successful(())
          case Some(_) =>
            env.datastores.userDashboardDataStore.findAll().flatMap { existing =>
              val present  = existing.flatMap(_.metadata.get(DefaultIdKey)).toSet
              val missing  = specs.filterNot(s => present.contains(s.defaultId))
              val upgrades = for {
                spec      <- specs
                installed <- existing.filter(_.metadata.get(DefaultIdKey).contains(spec.defaultId))
                if upgradable(installed, spec)
              } yield {
                // same id, so links and alerts pointing at it keep working
                build(spec).copy(id = installed.id, location = installed.location, enabled = installed.enabled)
              }
              Future
                .sequence((missing.map(build) ++ upgrades).map(d => env.datastores.userDashboardDataStore.set(d)))
                .map { _ =>
                  seeded.set(true)
                  if (missing.nonEmpty)
                    logger.info(s"seeded ${missing.size} default dashboard(s): ${missing.map(_.name).mkString(", ")}")
                  if (upgrades.nonEmpty)
                    logger.info(s"updated ${upgrades.size} unmodified default dashboard(s): ${upgrades.map(_.name).mkString(", ")}")
                  ()
                }
            }
        }
        .recover { case e: Throwable =>
          logger.warn(s"could not seed the default dashboards: ${e.getMessage}")
          ()
        }
    }
  }
}
