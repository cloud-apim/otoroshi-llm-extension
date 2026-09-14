package com.cloud.apim.otoroshi.extensions.aigateway.analytics

import io.vertx.sqlclient.{Tuple => VertxTuple}
import otoroshi.next.analytics.exporter.{AnalyticsProjection, UserAnalyticsExporterSettings}
import play.api.libs.json.*

import java.time.{Instant, OffsetDateTime, ZoneOffset}

/**
 * Json plumbing shared by the projections.
 *
 * Every event of the extension embeds the caller as full entities: the api key with its client secret,
 * the user with its session token, the whole route. None of that belongs in an analytics row, so each
 * one is cut down to an explicit allow-list — anything added to those entities later stays out.
 */
private[analytics] object EventJson {

  def path(js: JsValue, p: String): JsLookupResult =
    p.split('.').foldLeft(JsDefined(js): JsLookupResult)((acc, k) => acc \ k)

  def str(js: JsValue, paths: String*): Option[String] =
    paths.iterator.flatMap(p => path(js, p).asOpt[String]).map(_.trim).find(_.nonEmpty)

  def long(js: JsValue, paths: String*): Option[Long] =
    paths.iterator.flatMap(p => path(js, p).asOpt[Long]).find(_ >= 0L)

  def double(js: JsValue, paths: String*): Option[Double] =
    paths.iterator.flatMap(p => path(js, p).asOpt[Double]).find(d => !d.isNaN && !d.isInfinite)

  def strings(js: JsValue, p: String): Array[String] =
    path(js, p).asOpt[Seq[JsValue]].getOrElse(Seq.empty).flatMap(_.asOpt[String]).toArray

  def pick(js: JsValue, keys: String*): JsValue = js match {
    case obj: JsObject => JsObject(obj.fields.filter { case (k, _) => keys.contains(k) })
    case _             => JsNull
  }

  def pickAt(obj: JsObject, key: String, keys: String*): JsObject =
    (obj \ key).toOption match {
      case Some(inner: JsObject) => obj ++ Json.obj(key -> pick(inner, keys*))
      case _                     => obj
    }

  def ts(event: JsValue): OffsetDateTime = (event \ "@timestamp").asOpt[Long] match {
    case Some(millis) => OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC)
    case None         => OffsetDateTime.now(ZoneOffset.UTC)
  }

  def id(event: JsValue): String = str(event, "@id").getOrElse(java.util.UUID.randomUUID().toString)

  /** Caller identity cut down to what a filter or a ranking reads. */
  def stripIdentity(obj: JsObject): JsObject = {
    val withApikey = pickAt(obj, "apikey", "clientId", "clientName", "_loc")
    val withUser   = pickAt(withApikey, "user", "email", "name")
    pickAt(withUser, "route", "id", "name", "_loc", "groups", "api_ref")
  }

  def truncate(s: String, max: Int = 512): String = if (s.length > max) s.take(max) + "…" else s

  def boxLong(v: Option[Long]): java.lang.Long       = v.map(java.lang.Long.valueOf).orNull
  def boxDouble(v: Option[Double]): java.lang.Double = v.map(java.lang.Double.valueOf).orNull
  def boxBool(v: Boolean): java.lang.Boolean         = java.lang.Boolean.valueOf(v)

  /**
   * The values of the column contract, in `AnalyticsProjection.commonColumns` order.
   *
   * `from_ip` stays empty: no event of the extension carries the caller's address, and the gateway
   * event of the same request (joined on `request_id`) already has it.
   */
  def commonValues(event: JsValue): Seq[AnyRef] = Seq(
    id(event),
    ts(event),
    str(event, "@env").getOrElse("prod"),
    str(event, "route._loc.tenant", "apikey._loc.tenant").getOrElse("default"),
    strings(event, "route._loc.teams"),
    str(event, "route.id").orNull,
    str(event, "route.name").orNull,
    str(event, "route.api_ref", "route.api_ref.id").orNull,
    strings(event, "route.groups"),
    str(event, "apikey.clientId").orNull,
    str(event, "user.email").orNull,
    null
  )

  val commonInsertColumns: String =
    "id, ts, env, tenant, teams, route_id, route_name, api_id, group_ids, apikey_id, user_email, from_ip"

  def insert(table: String, columns: Seq[String]): String = {
    val all          = commonInsertColumns.split(",").map(_.trim).toSeq ++ columns
    val placeholders = all.indices.map(i => if (all(i) == "raw") s"$$${i + 1}::jsonb" else s"$$${i + 1}")
    s"INSERT INTO $table (${all.mkString(", ")}) VALUES (${placeholders.mkString(", ")}) ON CONFLICT (id) DO NOTHING"
  }
}

/**
 * `LLMUsageAudit` — one row per call to a model, whatever the modality.
 *
 * The event's layout differs between chat, embeddings, images, audio, moderation and ocr (the model
 * and the token counts live in different places), and a dashboard should not have to know that: the
 * row normalises it into one set of columns.
 */
object LlmUsageProjection extends AnalyticsProjection {

  import EventJson.*

  override val id: String = "cloud-apim.llm-usage"

  /** Providers that only route to other providers, each of which reports its own call. */
  val RoutingProviderKinds: Set[String] = Set("loadbalancer", "otoroshi")

  override def accepts(event: JsValue): Boolean =
    (event \ "@type").asOpt[String].contains("AuditEvent") && (event \ "audit").asOpt[String].contains("LLMUsageAudit")

  override def table(s: UserAnalyticsExporterSettings): String = s"${s.schema}.${s.table}_cloudapim_llm_usage"

  private def indexPrefix(s: UserAnalyticsExporterSettings): String = s"${s.table}_callm"

  override def createTableSql(s: UserAnalyticsExporterSettings): String =
    s"""CREATE TABLE IF NOT EXISTS ${table(s)} (
       |${AnalyticsProjection.commonColumns}
       |  request_id            TEXT,
       |  consumed_using        TEXT,
       |  modality              TEXT,
       |  streaming             BOOLEAN          NOT NULL DEFAULT false,
       |  delegated             BOOLEAN          NOT NULL DEFAULT false,
       |  provider_kind         TEXT,
       |  provider_id           TEXT,
       |  provider_name         TEXT,
       |  model                 TEXT,
       |  apikey_name           TEXT,
       |  err                   BOOLEAN          NOT NULL DEFAULT false,
       |  error_kind            TEXT,
       |  error_message         TEXT,
       |  duration_ms           BIGINT,
       |  input_tokens          BIGINT           NOT NULL DEFAULT 0,
       |  output_tokens         BIGINT           NOT NULL DEFAULT 0,
       |  reasoning_tokens      BIGINT           NOT NULL DEFAULT 0,
       |  total_tokens          BIGINT           NOT NULL DEFAULT 0,
       |  ocr_pages             BIGINT           NOT NULL DEFAULT 0,
       |  cache_status          TEXT,
       |  input_cost            DOUBLE PRECISION,
       |  output_cost           DOUBLE PRECISION,
       |  reasoning_cost        DOUBLE PRECISION,
       |  total_cost            DOUBLE PRECISION,
       |  cost_source           TEXT,
       |  energy_kwh            DOUBLE PRECISION,
       |  gwp_kgco2eq           DOUBLE PRECISION,
       |  gwp_usage_kgco2eq     DOUBLE PRECISION,
       |  gwp_embodied_kgco2eq  DOUBLE PRECISION,
       |  adpe_kgsbeq           DOUBLE PRECISION,
       |  pe_mj                 DOUBLE PRECISION,
       |  wcf_l                 DOUBLE PRECISION,
       |  budget_ids            TEXT[]           NOT NULL DEFAULT '{}',
       |  ratelimit_tokens_remaining   BIGINT,
       |  ratelimit_requests_remaining BIGINT,
       |  raw                   JSONB            NOT NULL DEFAULT '{}'::jsonb
       |);""".stripMargin

  override def indexStatements(s: UserAnalyticsExporterSettings): Seq[String] =
    AnalyticsProjection.commonIndexes(table(s), indexPrefix(s)) ++ Seq(
      s"CREATE INDEX IF NOT EXISTS idx_${indexPrefix(s)}_model_ts    ON ${table(s)} (model, ts DESC);",
      s"CREATE INDEX IF NOT EXISTS idx_${indexPrefix(s)}_provider_ts ON ${table(s)} (provider_id, ts DESC);",
      s"CREATE INDEX IF NOT EXISTS idx_${indexPrefix(s)}_err_ts      ON ${table(s)} (ts DESC) WHERE err = true;",
      s"CREATE INDEX IF NOT EXISTS idx_${indexPrefix(s)}_request     ON ${table(s)} (request_id) WHERE request_id IS NOT NULL;",
      s"CREATE INDEX IF NOT EXISTS idx_${indexPrefix(s)}_budgets_gin ON ${table(s)} USING GIN (budget_ids);"
    )

  private val columns = Seq(
    "request_id", "consumed_using", "modality", "streaming", "delegated", "provider_kind", "provider_id",
    "provider_name", "model", "apikey_name", "err", "error_kind", "error_message", "duration_ms", "input_tokens",
    "output_tokens", "reasoning_tokens", "total_tokens", "ocr_pages", "cache_status", "input_cost", "output_cost",
    "reasoning_cost", "total_cost", "cost_source", "energy_kwh", "gwp_kgco2eq", "gwp_usage_kgco2eq",
    "gwp_embodied_kgco2eq", "adpe_kgsbeq", "pe_mj", "wcf_l", "budget_ids", "ratelimit_tokens_remaining",
    "ratelimit_requests_remaining", "raw"
  )

  override def insertSql(s: UserAnalyticsExporterSettings): String = insert(table(s), columns)

  private def withoutDescriptions(js: JsValue): JsValue = js match {
    case obj: JsObject => JsObject(obj.fields.collect { case (k, v) if k != "description" => k -> withoutDescriptions(v) })
    case other         => other
  }

  /**
   * Prompts, completions, embeddings vectors, generated images and transcripts all go: they are
   * content, unbounded, and frequently personal. What stays is what the columns are derived from —
   * the model, the usage, the cache marker — so a row can always be re-derived from its `raw`.
   */
  override def strip(event: JsValue): JsValue = event match {
    case obj: JsObject =>
      val output   = (obj \ "output").toOption.collect { case o: JsObject =>
        pick(o, "model", "usage", "usage_info", "metadata") match {
          case kept: JsObject => pickAt(kept, "metadata", "usage", "cache", "rate_limit")
          case other          => other
        }
      }
      val lean     = JsObject(obj.fields.filterNot { case (k, _) =>
        Set("input_prompt", "input_body", "output", "output_stream", "provider_details", "impacts").contains(k)
      })
      val extra    = Json.obj(
        "output"           -> output.getOrElse(JsNull).as[JsValue],
        "input_body"       -> pick((obj \ "input_body").asOpt[JsValue].getOrElse(JsNull), "model"),
        "provider_details" -> (obj \ "provider_details").toOption
          .collect { case pd: JsObject =>
            pick(pd, "id", "name", "provider") match {
              case kept: JsObject => kept ++ Json.obj("options" -> pick((pd \ "options").asOpt[JsValue].getOrElse(JsNull), "model"))
              case other          => other
            }
          }
          .getOrElse(JsNull)
          .as[JsValue],
        "impacts"          -> withoutDescriptions((obj \ "impacts").asOpt[JsValue].getOrElse(JsNull))
      )
      stripIdentity(lean ++ extra)
    case other         => other
  }

  def modalityOf(consumedUsing: String): String = consumedUsing.split('/').headOption.getOrElse("") match {
    case "chat" if consumedUsing.startsWith("chat/completion") => "chat"
    case m if m.endsWith("_model")                            => m.stripSuffix("_model")
    case m if m.nonEmpty                                      => m
    case _                                                    => "unknown"
  }

  /** A rough class for the error, so a pie chart has a handful of slices rather than one per message. */
  def errorKind(error: JsValue): Option[String] = error match {
    case JsNull                                                   => None
    case JsString(_)                                              => Some("error")
    case obj: JsObject if (obj \ "exception").isDefined           => Some("exception")
    case obj: JsObject if (obj \ "error").asOpt[String].isDefined =>
      val e = (obj \ "error").as[String]
      Some(if (e.startsWith("bad response code")) "bad_response_code" else if (e.length > 64) "error" else e.replace(' ', '_'))
    case obj: JsObject if (obj \ "status").asOpt[Int].isDefined   => Some(s"status_${(obj \ "status").as[Int]}")
    case _                                                        => Some("error")
  }

  def errorMessage(error: JsValue): Option[String] = error match {
    case JsNull        => None
    case JsString(s)   => Some(truncate(s))
    case obj: JsObject =>
      str(obj, "error_description", "exception", "body.error.message", "body.message", "error.message", "error", "message")
        .orElse(Some(Json.stringify(obj)))
        .map(truncate(_))
    case other         => Some(truncate(Json.stringify(other)))
  }

  override def toTuple(event: JsValue): VertxTuple = {
    val consumedUsing = str(event, "consumed_using").getOrElse("unknown")
    val error         = (event \ "error").asOpt[JsValue].getOrElse(JsNull)
    val providerKind  = str(event, "provider_kind").map(_.toLowerCase)
    val entityId      = str(event, "provider_details.id")
    // the provider that actually served the call writes its own usage slug: when it is not the entity
    // this event is attributed to, a load balancer, a router or a fallback is re-reporting a call that
    // the served provider already reported, and summing both would count it twice
    val cacheStatus   = str(event, "output.metadata.cache.status").map(_.toLowerCase)
    // the top-level usage slug is the last one written during the request: on a cache hit no provider
    // ran, so whatever slug is there belongs to another call (an llm guardrail, typically)
    val slug          = !cacheStatus.contains("hit")
    val top           = (p: String) => if (slug) Seq(p) else Seq.empty[String]
    val servedBy      = if (slug) str(event, "provider") else None
    val delegated     =
      providerKind.exists(RoutingProviderKinds.contains) || servedBy.exists(sb => entityId.exists(_ != sb))

    val input      = long(event, top("usage.prompt_tokens") ++ Seq("output.metadata.usage.prompt_tokens", "output.usage.prompt_tokens",
      "output.usage.input_tokens", "output.usage.usage.input_tokens")*).getOrElse(0L)
    val output     = long(event, top("usage.generation_tokens") ++ Seq("output.metadata.usage.generation_tokens",
      "output.usage.output_tokens", "output.usage.usage.output_tokens")*).getOrElse(0L)
    val reasoning  = long(event, top("usage.reasoning_tokens") ++ Seq("output.metadata.usage.reasoning_tokens")*).getOrElse(0L)
    // a provider-reported total can include tokens not broken down above (images); a wrong one (audio
    // reports the input count as the total) is never larger than the sum
    val total      = math.max(input + output + reasoning, long(event, "output.usage.usage.total_tokens", "output.usage.total_tokens").getOrElse(0L))
    val impact     = (name: String) => double(event, s"impacts.$name.value.avg", s"impacts.$name.value")

    val values: Seq[AnyRef] = commonValues(event) ++ Seq(
      str(event, "request_id").orNull,
      consumedUsing,
      modalityOf(consumedUsing),
      boxBool(consumedUsing.endsWith("/streaming")),
      boxBool(delegated),
      providerKind.orNull,
      entityId.orElse(servedBy).orNull,
      str(event, "provider_details.name").orNull,
      str(event, top("model") ++ Seq("output.model", "input_body.model", "provider_details.options.model")*).orNull,
      str(event, "apikey.clientName").orNull,
      boxBool(error != JsNull),
      errorKind(error).orNull,
      errorMessage(error).orNull,
      boxLong(if (slug) long(event, "duration") else None),
      java.lang.Long.valueOf(input),
      java.lang.Long.valueOf(output),
      java.lang.Long.valueOf(reasoning),
      java.lang.Long.valueOf(total),
      java.lang.Long.valueOf(long(event, "output.usage_info.pages_processed").getOrElse(0L)),
      cacheStatus.orNull,
      boxDouble(double(event, "costs.input_cost")),
      boxDouble(double(event, "costs.output_cost")),
      boxDouble(double(event, "costs.reasoning_cost")),
      boxDouble(double(event, "costs.total_cost")),
      str(event, "costs.source").orNull,
      boxDouble(impact("energy")),
      boxDouble(impact("gwp")),
      boxDouble(double(event, "impacts.usage.gwp.value.avg", "impacts.usage.gwp.value")),
      boxDouble(double(event, "impacts.embodied.gwp.value.avg", "impacts.embodied.gwp.value")),
      boxDouble(impact("adpe")),
      boxDouble(impact("pe")),
      boxDouble(impact("wcf")),
      (event \ "budgets").asOpt[Seq[JsValue]].getOrElse(Seq.empty).flatMap(b => (b \ "budget_id").asOpt[String]).toArray,
      boxLong(if (slug) long(event, "rate_limit.tokens_remaining") else None),
      boxLong(if (slug) long(event, "rate_limit.requests_remaining") else None),
      Json.stringify(event)
    )
    VertxTuple.from(values.toArray)
  }
}

/**
 * The three MCP audit events — what Otoroshi serves (`McpAudit`), what it fetches for a resource
 * (`McpResourceFetchAudit`) and what it calls through a connector (`McpClientAudit`) — in one table.
 *
 * They answer the same questions (which tool, how long, did it fail) from two sides of the gateway,
 * and a single table lets one widget compare them; `side` tells them apart.
 */
object McpCallsProjection extends AnalyticsProjection {

  import EventJson.*

  override val id: String = "cloud-apim.mcp-calls"

  private val audits = Map("McpAudit" -> "server", "McpClientAudit" -> "client", "McpResourceFetchAudit" -> "fetch")

  override def accepts(event: JsValue): Boolean =
    (event \ "@type").asOpt[String].contains("AuditEvent") && (event \ "audit").asOpt[String].exists(audits.contains)

  override def table(s: UserAnalyticsExporterSettings): String = s"${s.schema}.${s.table}_cloudapim_mcp_calls"

  private def indexPrefix(s: UserAnalyticsExporterSettings): String = s"${s.table}_camcp"

  override def createTableSql(s: UserAnalyticsExporterSettings): String =
    s"""CREATE TABLE IF NOT EXISTS ${table(s)} (
       |${AnalyticsProjection.commonColumns}
       |  request_id        TEXT,
       |  side              TEXT,
       |  method            TEXT,
       |  tool              TEXT,
       |  target            TEXT,
       |  transport         TEXT,
       |  protocol_version  TEXT,
       |  connector_id      TEXT,
       |  connector_name    TEXT,
       |  apikey_name       TEXT,
       |  err               BOOLEAN     NOT NULL DEFAULT false,
       |  tool_error        BOOLEAN     NOT NULL DEFAULT false,
       |  error_message     TEXT,
       |  http_status       INTEGER,
       |  duration_ms       BIGINT,
       |  raw               JSONB       NOT NULL DEFAULT '{}'::jsonb
       |);""".stripMargin

  override def indexStatements(s: UserAnalyticsExporterSettings): Seq[String] =
    AnalyticsProjection.commonIndexes(table(s), indexPrefix(s)) ++ Seq(
      s"CREATE INDEX IF NOT EXISTS idx_${indexPrefix(s)}_tool_ts      ON ${table(s)} (tool, ts DESC) WHERE tool IS NOT NULL;",
      s"CREATE INDEX IF NOT EXISTS idx_${indexPrefix(s)}_method_ts    ON ${table(s)} (method, ts DESC);",
      s"CREATE INDEX IF NOT EXISTS idx_${indexPrefix(s)}_connector_ts ON ${table(s)} (connector_id, ts DESC) WHERE connector_id IS NOT NULL;",
      s"CREATE INDEX IF NOT EXISTS idx_${indexPrefix(s)}_request      ON ${table(s)} (request_id) WHERE request_id IS NOT NULL;"
    )

  private val columns = Seq(
    "request_id", "side", "method", "tool", "target", "transport", "protocol_version", "connector_id",
    "connector_name", "apikey_name", "err", "tool_error", "error_message", "http_status", "duration_ms", "raw"
  )

  override def insertSql(s: UserAnalyticsExporterSettings): String = insert(table(s), columns)

  /**
   * Tool arguments and results go: they are whatever the tool was given and gave back, which is
   * content, not telemetry. The tool name, the resource uri and the error marker stay.
   */
  override def strip(event: JsValue): JsValue = event match {
    case obj: JsObject =>
      val payload  = (obj \ "mcp_request_payload").toOption.collect { case p: JsObject =>
        pick(p, "jsonrpc", "id", "method") match {
          case kept: JsObject => kept ++ Json.obj("params" -> pick((p \ "params").asOpt[JsValue].getOrElse(JsNull), "name", "uri"))
          case other          => other
        }
      }
      val response = (resp: JsValue) =>
        Json.obj(
          "isError" -> (resp \ "isError").asOpt[Boolean].orElse((resp \ "result" \ "isError").asOpt[Boolean]),
          "error"   -> pick((resp \ "error").asOpt[JsValue].getOrElse(JsNull), "code", "message")
        )
      val lean     = JsObject(obj.fields.filterNot { case (k, _) =>
        Set("mcp_request_payload", "mcp_request", "mcp_response").contains(k)
      })
      stripIdentity(
        lean ++ Json.obj(
          "mcp_request_payload" -> payload.getOrElse(JsNull).as[JsValue],
          "mcp_request"         -> pick((obj \ "mcp_request").asOpt[JsValue].getOrElse(JsNull), "name", "uri"),
          "mcp_response"        -> (obj \ "mcp_response").asOpt[JsObject].map(response).getOrElse(JsNull).as[JsValue]
        )
      )
    case other         => other
  }

  override def toTuple(event: JsValue): VertxTuple = {
    val side      = audits.getOrElse(str(event, "audit").getOrElse(""), "server")
    val method    = side match {
      case "client" => str(event, "mcp_operation")
      case "fetch"  => Some("resources/fetch")
      case _        => str(event, "mcp_method", "mcp_request_payload.method")
    }
    val tool      = method.filter(_ == "tools/call").flatMap(_ => str(event, "mcp_request_payload.params.name", "mcp_request.name"))
    val target    = str(event, "mcp_request_payload.params.name", "mcp_request_payload.params.uri", "mcp_request.name",
      "mcp_request.uri", "resource_uri")
    val rpcError  = (event \ "mcp_response" \ "error").toOption.exists(_ != JsNull)
    val err       = str(event, "status").contains("error") || rpcError
    val values: Seq[AnyRef] = commonValues(event) ++ Seq(
      str(event, "request_id").orNull,
      side,
      method.orNull,
      tool.orNull,
      target.map(truncate(_)).orNull,
      str(event, "transport", "mcp_connector_kind").orNull,
      str(event, "mcp_protocol_version").orNull,
      str(event, "mcp_connector_id").orNull,
      str(event, "mcp_connector_name").orNull,
      str(event, "apikey.clientName").orNull,
      boxBool(err),
      boxBool((event \ "mcp_response" \ "isError").asOpt[Boolean].contains(true)),
      str(event, "error", "mcp_response.error.message").map(truncate(_)).orNull,
      (event \ "http_status").asOpt[Int].map(Integer.valueOf).orNull,
      boxLong(long(event, "duration")),
      Json.stringify(event)
    )
    VertxTuple.from(values.toArray)
  }
}

/**
 * The extension's alerts: budgets exceeded or about to be, provider quotas hit and recovered, and MCP
 * zero-trust decisions. Rare, but each one is the explanation for a curve bending elsewhere.
 */
object AiAlertsProjection extends AnalyticsProjection {

  import EventJson.*

  override val id: String = "cloud-apim.ai-alerts"

  private val alerts = Map(
    "AiBudgetExceeded"                -> "budget",
    "AiBudgetAlmostExceeded"          -> "budget",
    "LLMProviderQuotaExceededAlert"   -> "provider_quota",
    "LLMProviderCreditExhaustedAlert" -> "provider_quota",
    "LLMProviderQuotaRecoveredAlert"  -> "provider_quota"
  )

  private def nameOf(event: JsValue): Option[String] = (event \ "@type").asOpt[String] match {
    case Some("AlertEvent") => str(event, "alert").filter(alerts.contains)
    case Some("AuditEvent") => str(event, "audit").filter(_ == "McpZeroTrustAlert")
    case _                  => None
  }

  override def accepts(event: JsValue): Boolean = nameOf(event).isDefined

  override def table(s: UserAnalyticsExporterSettings): String = s"${s.schema}.${s.table}_cloudapim_ai_alerts"

  private def indexPrefix(s: UserAnalyticsExporterSettings): String = s"${s.table}_caal"

  override def createTableSql(s: UserAnalyticsExporterSettings): String =
    s"""CREATE TABLE IF NOT EXISTS ${table(s)} (
       |${AnalyticsProjection.commonColumns}
       |  request_id     TEXT,
       |  name           TEXT,
       |  category       TEXT,
       |  kind           TEXT,
       |  provider_kind  TEXT,
       |  budget_ids     TEXT[]           NOT NULL DEFAULT '{}',
       |  budget_name    TEXT,
       |  tool           TEXT,
       |  blocked        BOOLEAN          NOT NULL DEFAULT false,
       |  percentage     DOUBLE PRECISION,
       |  refused_calls  BIGINT,
       |  duration_ms    BIGINT,
       |  err            BOOLEAN          NOT NULL DEFAULT false,
       |  raw            JSONB            NOT NULL DEFAULT '{}'::jsonb
       |);""".stripMargin

  override def indexStatements(s: UserAnalyticsExporterSettings): Seq[String] =
    AnalyticsProjection.commonIndexes(table(s), indexPrefix(s)) ++ Seq(
      s"CREATE INDEX IF NOT EXISTS idx_${indexPrefix(s)}_name_ts ON ${table(s)} (name, ts DESC);",
      s"CREATE INDEX IF NOT EXISTS idx_${indexPrefix(s)}_budgets_gin ON ${table(s)} USING GIN (budget_ids);"
    )

  private val columns = Seq(
    "request_id", "name", "category", "kind", "provider_kind", "budget_ids", "budget_name", "tool", "blocked",
    "percentage", "refused_calls", "duration_ms", "err", "raw"
  )

  override def insertSql(s: UserAnalyticsExporterSettings): String = insert(table(s), columns)

  /** A rug-pull detail carries two full tool descriptions; the phase and the reason are what is read. */
  override def strip(event: JsValue): JsValue = event match {
    case obj: JsObject =>
      val budget = (obj \ "budget").toOption.map(b => pick(b, "id", "name", "limits"))
      val detail = (obj \ "detail").toOption.map(d => pick(d, "phase", "reason"))
      stripIdentity(obj ++ JsObject(Seq("budget" -> budget, "detail" -> detail).collect { case (k, Some(v)) => k -> v }))
    case other         => other
  }

  override def toTuple(event: JsValue): VertxTuple = {
    val name      = nameOf(event).getOrElse("unknown")
    val category  = alerts.getOrElse(name, "mcp_zero_trust")
    val kind      = name match {
      case "AiBudgetExceeded"               => Some("exceeded")
      case "AiBudgetAlmostExceeded"         => Some("almost_exceeded")
      case "LLMProviderQuotaRecoveredAlert" => Some("recovered")
      case _                                => str(event, "kind", "zerotrust_kind")
    }
    val budgetIds = (event \ "budgets").asOpt[Seq[String]].getOrElse(Seq.empty) ++ str(event, "budget.id").toSeq
    val pct       = (event \ "percentages").asOpt[Map[String, Double]].filter(_.nonEmpty).map(_.values.max / 100.0)
    val values: Seq[AnyRef] = commonValues(event) ++ Seq(
      str(event, "request_id").orNull,
      name,
      category,
      kind.orNull,
      str(event, "provider_kind").map(_.toLowerCase).orNull,
      budgetIds.distinct.toArray,
      str(event, "budget.name").orNull,
      str(event, "mcp_tool").orNull,
      boxBool((event \ "blocked").asOpt[Boolean].contains(true)),
      boxDouble(pct),
      boxLong(long(event, "refused_calls")),
      boxLong(long(event, "duration_ms")),
      boxBool(name != "LLMProviderQuotaRecoveredAlert"),
      Json.stringify(event)
    )
    VertxTuple.from(values.toArray)
  }
}
