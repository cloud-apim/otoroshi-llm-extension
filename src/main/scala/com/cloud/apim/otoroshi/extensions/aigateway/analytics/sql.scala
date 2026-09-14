package com.cloud.apim.otoroshi.extensions.aigateway.analytics

import io.vertx.sqlclient.{Pool, Row}
import otoroshi.env.Env
import otoroshi.next.analytics.exporter.UserAnalyticsExporterSettings
import otoroshi.next.analytics.queries.*
import play.api.libs.json.*

import scala.concurrent.{ExecutionContext, Future}

/**
 * Everything a query needs to run, so a catalogue entry can be a single expression.
 */
final case class QueryContext(
    filters: Filters,
    params: JsObject,
    bucket: Bucket,
    settings: UserAnalyticsExporterSettings,
    pool: Pool
)(using val ec: ExecutionContext, val env: Env)

/**
 * A catalogue entry: the metadata the widget wizard shows, and the function that answers it.
 *
 * Declaring the queries as values rather than as one object each keeps a catalogue of a hundred
 * entries readable — and the metadata next to the SQL it describes.
 */
final class CatalogQuery(
    val id: String,
    val name: String,
    val description: String,
    val shape: AnalyticsShape,
    val defaultWidget: String,
    override val supportsCompare: Boolean = false,
    override val params: Seq[QueryParam] = Seq.empty
)(run: QueryContext => Future[QueryResult])
    extends AnalyticsQuery {
  def execute(f: Filters, p: JsObject, b: Bucket, s: UserAnalyticsExporterSettings, pool: Pool)(using
      ec: ExecutionContext,
      env: Env
  ): Future[QueryResult] = run(QueryContext(f, p, b, s, pool))
}

/**
 * SQL building blocks shaped after the platform's own queries, so the results render in the same
 * widgets. Every clause goes through `FilterSql.whereClause`: period, route, api, apikey, group,
 * tenant and error filters behave exactly as they do on gateway traffic.
 */
object AnalyticsSql {

  val TopNParam: QueryParam = QueryParam("top_n", "int", JsNumber(10), "Maximum number of items returned")

  def and(where: String, extra: String): String =
    if (extra.isEmpty) where else if (where.isEmpty) s" WHERE $extra" else s"$where AND $extra"

  private def number(row: Row, idx: Int, asDouble: Boolean): JsNumber =
    if (asDouble) JsNumber(BigDecimal(QueryHelpers.safeDouble(row, idx)))
    else JsNumber(QueryHelpers.safeLong(row, idx))

  /**
   * The chart widgets use a series name as a lookup path, so `gpt-4.1` would be read as `gpt-4` → `1`
   * and draw nothing. A one-dot leader looks the same and is not a separator.
   */
  def seriesName(name: String): String = name.replace('.', '․')

  private def limit(ctx: QueryContext): Int = (ctx.params \ "top_n").asOpt[Int].getOrElse(10).max(1).min(1000)

  /** One number: a count, a sum, an average or a ratio. */
  def scalar(table: String, expr: String, label: String, extra: String = "", asDouble: Boolean = false)(
      ctx: QueryContext
  ): Future[QueryResult] = {
    given ExecutionContext = ctx.ec
    val (where, vals)      = FilterSql.whereClause(ctx.filters)
    val sql                = s"SELECT $expr AS value FROM $table${and(where, extra)}"
    QueryHelpers.runSelect(ctx.pool, sql, vals).map { rows =>
      val value = rows.headOption.map(r => number(r, 0, asDouble)).getOrElse(JsNumber(0))
      QueryResult(AnalyticsShape.Scalar, Json.obj("value" -> value, "label" -> label), JsArray(Seq(Json.obj("value" -> value))))
    }
  }

  /** A distribution: one slice per distinct key. */
  def pie(
      table: String,
      key: String,
      label: String,
      value: String = "COUNT(*)",
      extra: String = "",
      asDouble: Boolean = false
  )(ctx: QueryContext): Future[QueryResult] = {
    given ExecutionContext = ctx.ec
    val (where, vals)      = FilterSql.whereClause(ctx.filters)
    val sql                =
      s"""SELECT $key AS key, $value AS value
         |FROM $table${and(where, extra)}
         |GROUP BY 1 ORDER BY value DESC NULLS LAST""".stripMargin
    QueryHelpers.runSelect(ctx.pool, sql, vals).map { rows =>
      val items = rows.map { r =>
        Json.obj("key" -> QueryHelpers.optString(r, 0).getOrElse("(unknown)"), "value" -> number(r, 1, asDouble))
      }
      QueryResult(AnalyticsShape.Pie, Json.obj("label" -> label, "items" -> JsArray(items)), JsArray(items))
    }
  }

  /**
   * A ranking. `having` filters on the aggregate (a minimum sample size, typically), so a ranking of
   * averages or ratios is not topped by a key seen once.
   */
  def topN(
      table: String,
      key: String,
      labelField: Option[String] = None,
      value: String = "COUNT(*)",
      extra: String = "",
      asDouble: Boolean = false,
      having: String = "",
      ascending: Boolean = false,
      relabel: String => Option[String] = _ => None
  )(ctx: QueryContext): Future[QueryResult] = {
    given ExecutionContext = ctx.ec
    val (where, vals)      = FilterSql.whereClause(ctx.filters)
    val select             = labelField match {
      case Some(l) => s"$key AS key, MAX($l) AS label, $value AS value"
      case None    => s"$key AS key, $value AS value"
    }
    val sql                =
      s"""SELECT $select
         |FROM $table${and(where, extra)}
         |GROUP BY $key${if (having.isEmpty) "" else s" HAVING $having"}
         |ORDER BY value ${if (ascending) "ASC" else "DESC"} NULLS LAST
         |LIMIT ${limit(ctx)}""".stripMargin
    QueryHelpers.runSelect(ctx.pool, sql, vals).map { rows =>
      val items = rows.map { r =>
        val k = QueryHelpers.optString(r, 0).getOrElse("(unknown)")
        val l = labelField.flatMap(_ => QueryHelpers.optString(r, 1)).orElse(relabel(k)).getOrElse(k)
        Json.obj("key" -> k, "label" -> l, "value" -> number(r, if (labelField.isDefined) 2 else 1, asDouble))
      }
      QueryResult(AnalyticsShape.TopN, Json.obj("items" -> JsArray(items)), JsArray(items))
    }
  }

  /**
   * A ranking with several figures per row, for the table widget — which renders every field but
   * `key` as a column, in order. `columns` are (name, expression, isDouble).
   */
  def table(
      table: String,
      key: String,
      keyName: String,
      columns: Seq[(String, String, Boolean)],
      extra: String = "",
      orderBy: String = "2 DESC NULLS LAST"
  )(ctx: QueryContext): Future[QueryResult] = {
    given ExecutionContext = ctx.ec
    val (where, vals)      = FilterSql.whereClause(ctx.filters)
    val select             = (s"$key AS key" +: columns.map { case (n, e, _) => s"$e AS $n" }).mkString(", ")
    val sql                =
      s"""SELECT $select
         |FROM $table${and(where, extra)}
         |GROUP BY $key
         |ORDER BY $orderBy
         |LIMIT ${limit(ctx)}""".stripMargin
    QueryHelpers.runSelect(ctx.pool, sql, vals).map { rows =>
      val items = rows.map { r =>
        val k      = QueryHelpers.optString(r, 0).getOrElse("(unknown)")
        val fields = columns.zipWithIndex.map { case ((n, _, d), i) => n -> (number(r, i + 1, d): JsValue) }
        JsObject(Seq("key" -> JsString(k), keyName -> JsString(k)) ++ fields)
      }
      QueryResult(AnalyticsShape.Table, Json.obj("items" -> JsArray(items)), JsArray(items))
    }
  }

  /**
   * Values over time, empty buckets filled. One expression gives a single `points` series, several
   * give named `series` — the two forms the line and area widgets accept.
   */
  def series(
      table: String,
      aggregates: Seq[(String, String)],
      extra: String = "",
      asDouble: Boolean = false,
      cumulative: Boolean = false
  )(ctx: QueryContext): Future[QueryResult] = {
    given ExecutionContext = ctx.ec
    val (where, vals)      = FilterSql.whereClause(ctx.filters)
    val agg                = aggregates.zipWithIndex.map { case ((_, expr), i) => s"$expr AS v$i" }.mkString(", ")
    val sql                = TimeseriesQueries.buildSeriesQuery(agg, ctx.bucket, and(where, extra), table)
    QueryHelpers.runSelect(ctx.pool, sql, vals).map { rows =>
      // a running total over the period — the burn curve — rather than the value of each bucket
      def points(i: Int): JsArray = {
        var acc = 0.0
        JsArray(rows.map { r =>
          val v = if (cumulative) {
            acc += QueryHelpers.safeDouble(r, 2 + i)
            if (asDouble) JsNumber(BigDecimal(acc)) else JsNumber(acc.toLong)
          } else number(r, 2 + i, asDouble)
          Json.obj("ts" -> QueryHelpers.jsTs(r.getOffsetDateTime(0)), "value" -> v)
        })
      }
      if (aggregates.size == 1) {
        val pts = points(0)
        QueryResult(AnalyticsShape.Timeseries, Json.obj("bucket" -> ctx.bucket.name, "points" -> pts), pts)
      } else {
        val all = aggregates.zipWithIndex.map { case ((name, _), i) => Json.obj("name" -> name, "points" -> points(i)) }
        QueryResult(AnalyticsShape.Timeseries, Json.obj("bucket" -> ctx.bucket.name, "series" -> JsArray(all)), JsArray())
      }
    }
  }

  /**
   * One series per key, for the `top_n` keys with the largest total over the period — the "which model
   * is driving the curve" view. Keys are ranked over the whole period rather than per bucket, so a
   * series does not appear and vanish from one bucket to the next; empty buckets are filled with 0.
   */
  def seriesByKey(
      table: String,
      key: String,
      value: String = "COUNT(*)",
      extra: String = "",
      asDouble: Boolean = false,
      defaultTopN: Int = 5,
      relabel: String => Option[String] = _ => None
  )(ctx: QueryContext): Future[QueryResult] = {
    given ExecutionContext = ctx.ec
    val (where, vals)      = FilterSql.whereClause(ctx.filters)
    val filtered           = and(and(where, s"$key IS NOT NULL"), extra)
    val n                  = (ctx.params \ "top_n").asOpt[Int].getOrElse(defaultTopN).max(1).min(20)
    val sql                =
      s"""WITH top AS (
         |  SELECT $key AS k FROM $table$filtered GROUP BY 1 ORDER BY $value DESC NULLS LAST LIMIT $n
         |)
         |SELECT ${ctx.bucket.truncSql("ts")} AS bucket, $key AS k, $value AS v
         |FROM $table${and(filtered, s"$key IN (SELECT k FROM top)")}
         |GROUP BY 1, 2""".stripMargin
    QueryHelpers.runSelect(ctx.pool, sql, vals).map { rows =>
      val step    = ctx.bucket.seconds
      val first   = Math.floorDiv(ctx.filters.from.getEpochSecond, step) * step
      val last    = Math.floorDiv(ctx.filters.to.getEpochSecond, step) * step
      val buckets = (first to last by step).map(_ * 1000L)
      val cells   = rows.map { r =>
        (r.getOffsetDateTime(0).toInstant.toEpochMilli, QueryHelpers.optString(r, 1).getOrElse("(unknown)")) ->
          (if (asDouble) QueryHelpers.safeDouble(r, 2) else QueryHelpers.safeLong(r, 2).toDouble)
      }.toMap
      val keys    = cells.toSeq.groupMapReduce(_._1._2)(_._2)(_ + _).toSeq.sortBy(-_._2).map(_._1)
      val all     = keys.map { k =>
        val pts = buckets.map { b =>
          val v = cells.getOrElse((b, k), 0.0)
          Json.obj("ts" -> b, "value" -> (if (asDouble) JsNumber(BigDecimal(v)) else JsNumber(v.toLong)))
        }
        Json.obj("name" -> seriesName(relabel(k).getOrElse(k)), "points" -> JsArray(pts))
      }
      QueryResult(AnalyticsShape.Timeseries, Json.obj("bucket" -> ctx.bucket.name, "series" -> JsArray(all)), JsArray())
    }
  }

  /** A distribution over fixed bands, kept in band order rather than sorted by count: a histogram. */
  def bands(table: String, bands: Seq[(String, String)], extra: String = "")(ctx: QueryContext): Future[QueryResult] = {
    given ExecutionContext = ctx.ec
    val (where, vals)      = FilterSql.whereClause(ctx.filters)
    val counts             = bands.zipWithIndex.map { case ((_, cond), i) => s"COUNT(*) FILTER (WHERE $cond) AS b$i" }.mkString(", ")
    QueryHelpers.runSelect(ctx.pool, s"SELECT $counts FROM $table${and(where, extra)}", vals).map { rows =>
      val items = bands.zipWithIndex.map { case ((label, _), i) =>
        Json.obj("key" -> label, "label" -> label, "value" -> rows.headOption.map(r => QueryHelpers.safeLong(r, i)).getOrElse(0L))
      }
      QueryResult(AnalyticsShape.TopN, Json.obj("items" -> JsArray(items)), JsArray(items))
    }
  }

  /**
   * The latest rows, most recent first, for the table widget. Every column is rendered as text, so
   * expressions should already produce what a reader wants to see.
   */
  def latest(table: String, columns: Seq[(String, String)], extra: String = "", defaultLimit: Int = 25)(
      ctx: QueryContext
  ): Future[QueryResult] = {
    given ExecutionContext = ctx.ec
    val (where, vals)      = FilterSql.whereClause(ctx.filters)
    val n                  = (ctx.params \ "top_n").asOpt[Int].getOrElse(defaultLimit).max(1).min(200)
    val select             = ("id AS key" +: columns.map { case (name, expr) => s"$expr AS $name" }).mkString(", ")
    val sql                = s"SELECT $select FROM $table${and(where, extra)} ORDER BY ts DESC LIMIT $n"
    QueryHelpers.runSelect(ctx.pool, sql, vals).map { rows =>
      val items = rows.map { r =>
        JsObject(("key" -> JsString(QueryHelpers.optString(r, 0).getOrElse(""))) +: columns.zipWithIndex.map { case ((name, _), i) =>
          name -> JsString(QueryHelpers.optString(r, i + 1).getOrElse("—"))
        })
      }
      QueryResult(AnalyticsShape.Table, Json.obj("items" -> JsArray(items)), JsArray(items))
    }
  }

  /**
   * Activity by day of week × hour of day (UTC), over the whole period: when the gateway is used.
   *
   * Columns are named through `xLabels`; they are also sent as the matching instants of the epoch
   * day in `xBuckets`, which a heatmap widget that only knows time columns renders as the same hours.
   * Rows are the days of the week, Monday first.
   */
  def weekHourHeatmap(table: String, extra: String = "")(ctx: QueryContext): Future[QueryResult] = {
    given ExecutionContext = ctx.ec
    val (where, vals)      = FilterSql.whereClause(ctx.filters)
    val sql                =
      s"""SELECT extract(isodow FROM ts AT TIME ZONE 'UTC')::int AS dow, extract(hour FROM ts AT TIME ZONE 'UTC')::int AS hour, COUNT(*) AS value
         |FROM $table${and(where, extra)}
         |GROUP BY 1, 2""".stripMargin
    QueryHelpers.runSelect(ctx.pool, sql, vals).map { rows =>
      val cells = rows.map(r => (QueryHelpers.safeLong(r, 0).toInt, QueryHelpers.safeLong(r, 1).toInt) -> QueryHelpers.safeLong(r, 2)).toMap
      val days  = Seq("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
      QueryResult(
        AnalyticsShape.Heatmap,
        Json.obj(
          "bucket"   -> "1h",
          "xBuckets" -> JsArray((0 until 24).map(h => JsNumber(h * 3600000L))),
          "xLabels"  -> JsArray((0 until 24).map(h => JsString(f"$h%02d:00"))),
          "yBuckets" -> JsArray(days.map(JsString.apply)),
          "values"   -> JsArray(days.indices.map(d => JsArray((0 until 24).map(h => JsNumber(cells.getOrElse((d + 1, h), 0L))))))
        ),
        JsArray()
      )
    }
  }

  /** Counts per (time bucket × band), bands being SQL conditions listed in display order. */
  def heatmap(table: String, bands: Seq[(String, String)], extra: String = "")(ctx: QueryContext): Future[QueryResult] = {
    given ExecutionContext = ctx.ec
    val (where, vals)      = FilterSql.whereClause(ctx.filters)
    val counts             = bands.zipWithIndex.map { case ((_, cond), i) => s"COUNT(*) FILTER (WHERE $cond) AS y$i" }.mkString(", ")
    val sql                = TimeseriesQueries.buildSeriesQuery(counts, ctx.bucket, and(where, extra), table)
    QueryHelpers.runSelect(ctx.pool, sql, vals).map { rows =>
      QueryResult(
        AnalyticsShape.Heatmap,
        Json.obj(
          "bucket"   -> ctx.bucket.name,
          "xBuckets" -> JsArray(rows.map(r => QueryHelpers.jsTs(r.getOffsetDateTime(0)))),
          "yBuckets" -> JsArray(bands.map { case (l, _) => JsString(l) }),
          "values"   -> JsArray(bands.indices.map(i => JsArray(rows.map(r => JsNumber(QueryHelpers.safeLong(r, 2 + i))))))
        ),
        JsArray()
      )
    }
  }
}
