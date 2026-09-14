package com.cloud.apim.otoroshi.extensions.aigateway.analytics

import com.cloud.apim.otoroshi.extensions.aigateway.analytics.AnalyticsSql.*
import otoroshi.env.Env
import otoroshi.next.analytics.queries.*
import otoroshi.next.analytics.exporter.UserAnalyticsExporterSettings
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.*

import scala.concurrent.{ExecutionContext, Future}

/**
 * The queries behind the AI gateway dashboards.
 *
 * They are declared through `analyticsQueries()`, like the platform's own: each one shows up in the
 * widget wizard, composes into any user dashboard, and can be alerted on — a daily spend, an error
 * rate, a tool that started failing — with no UI of the extension's own.
 *
 * Units are chosen for what a request actually weighs, so the numbers read without a calculator:
 * dollars, watt-hours, grams of CO2eq, milligrams of antimony-equivalent.
 */
object AiGatewayQueries {

  private def q(
      id: String,
      name: String,
      description: String,
      shape: AnalyticsShape,
      widget: String,
      compare: Boolean = false,
      params: Seq[QueryParam] = Seq.empty
  )(run: QueryContext => Future[QueryResult]): CatalogQuery =
    new CatalogQuery(id, name, description, shape, widget, compare, params)(run)

  // a deleted budget keeps its rows: it is then shown by id
  private def budgetName(ctx: QueryContext)(id: String): Option[String] =
    Option(ctx.env).flatMap(_.adminExtensions.extension[AiExtension]).flatMap(_.states.budget(id)).map(_.name)

  // =================================================================================================
  // LLM usage
  // =================================================================================================

  object Llm {

    private def t(s: UserAnalyticsExporterSettings): String = LlmUsageProjection.table(s)

    /**
     * Load balancers, routers and fallbacks re-report the call their target already reported: every
     * query reads the calls that were actually made, once.
     */
    private def real(ctx: QueryContext, extra: String = ""): String = {
      val modality = (ctx.params \ "modality").asOpt[String].map(_.trim.toLowerCase).filter(m => ModalityPattern.matches(m))
      // the patterns rule out quotes and anything a literal could escape with, so the values can be inlined.
      // A user that does not look like an email matches nothing rather than everything
      val user     = (ctx.params \ "user").asOpt[String].map(_.trim).filter(_.nonEmpty).map {
        case u if UserPattern.matches(u) => s"user_email = '$u'"
        case _                           => "1 = 0"
      }
      (Seq("delegated = false") ++ modality.map(m => s"modality = '$m'") ++ user ++ Option(extra).filter(_.nonEmpty).map(e => s"($e)"))
        .mkString(" AND ")
    }

    private val ModalityPattern = "^[a-z_]{1,32}$".r
    private val UserPattern     = "^[A-Za-z0-9._%+@-]{1,254}$".r

    private val ModalityParam = QueryParam(
      "modality",
      "string",
      JsNull,
      "Only count calls of one modality: chat, responses, completion, embedding, image, audio, video, moderation or ocr"
    )

    private val UserParam = QueryParam("user", "string", JsNull, "Only count calls of one user (email)")

    // every llm query can be narrowed to one modality — "the image generation spend", "embedding latency" —
    // and to one user
    private def lq(
        id: String,
        name: String,
        description: String,
        shape: AnalyticsShape,
        widget: String,
        compare: Boolean = false,
        params: Seq[QueryParam] = Seq.empty
    )(run: QueryContext => Future[QueryResult]): CatalogQuery =
      q(id, name, description, shape, widget, compare, params :+ ModalityParam :+ UserParam)(run)

    private def metric(id: String, name: String, description: String, expr: String, extra: String = "", asDouble: Boolean = false) =
      lq(s"cloudapim_llm_$id", name, description, AnalyticsShape.Scalar, "metric", compare = true) { ctx =>
        scalar(t(ctx.settings), expr, name, real(ctx, extra), asDouble)(ctx)
      }

    private def pieOf(id: String, name: String, description: String, key: String, value: String = "COUNT(*)", extra: String = "", asDouble: Boolean = false, widget: String = "donut") =
      lq(s"cloudapim_llm_$id", name, description, AnalyticsShape.Pie, widget) { ctx =>
        pie(t(ctx.settings), key, name, value, real(ctx, extra), asDouble)(ctx)
      }

    private def top(
        id: String,
        name: String,
        description: String,
        key: String,
        label: Option[String] = None,
        value: String = "COUNT(*)",
        extra: String = "",
        asDouble: Boolean = false,
        having: String = "",
        ascending: Boolean = false
    ) =
      lq(s"cloudapim_llm_$id", name, description, AnalyticsShape.TopN, "bar", params = Seq(TopNParam)) { ctx =>
        topN(t(ctx.settings), key, label, value, real(ctx, s"$key IS NOT NULL${if (extra.isEmpty) "" else s" AND $extra"}"), asDouble, having, ascending)(ctx)
      }

    private def ts(id: String, name: String, description: String, aggregates: Seq[(String, String)], extra: String = "", asDouble: Boolean = false, widget: String = "area") =
      lq(s"cloudapim_llm_$id", name, description, AnalyticsShape.Timeseries, widget, compare = aggregates.size == 1) { ctx =>
        series(t(ctx.settings), aggregates, real(ctx, extra), asDouble)(ctx)
      }

    private def tsByKey(id: String, name: String, description: String, key: String, value: String = "COUNT(*)", extra: String = "", asDouble: Boolean = false) =
      lq(s"cloudapim_llm_$id", name, description, AnalyticsShape.Timeseries, "line", params = Seq(QueryParam("top_n", "int", JsNumber(5), "Number of series"))) { ctx =>
        seriesByKey(t(ctx.settings), key, value, real(ctx, extra), asDouble)(ctx)
      }

    private val Provider = "COALESCE(provider_name, provider_id)"
    private val ErrorRate = "COALESCE(AVG(CASE WHEN err THEN 1.0 ELSE 0.0 END), 0)"
    private val HasCost   = "total_cost IS NOT NULL"
    private val HasImpact = "gwp_kgco2eq IS NOT NULL"
    private val HasTime   = "duration_ms IS NOT NULL"

    // ---- traffic ----------------------------------------------------------------------------------

    val RequestsTotal   = metric("requests_total", "LLM calls", "Every call made to a model, whatever the modality, cache hits included.", "COUNT(*)")
    val ErrorsTotal     = metric("errors_total", "LLM errors", "Calls that ended in an error: provider failure, guardrail denial, model constraint, open circuit.", "COUNT(*)", "err = true")
    val ErrorRate_      = metric("error_rate", "LLM error rate", "Share of calls that ended in an error.", ErrorRate, asDouble = true)
    val CallsOverTime   = ts("requests_over_time", "LLM calls over time", "Successful and failed calls per bucket.", Seq(
      "success" -> "COUNT(*) FILTER (WHERE NOT err)",
      "error"   -> "COUNT(*) FILTER (WHERE err)"
    ))
    val CallsPerSecond  = lq("cloudapim_llm_requests_per_second", "LLM calls per second", "Call rate per bucket.", AnalyticsShape.Timeseries, "line", compare = true) { ctx =>
      series(t(ctx.settings), Seq("value" -> s"COUNT(*)::float / ${ctx.bucket.seconds.toDouble}"), real(ctx), asDouble = true)(ctx)
    }
    val ByProviderKind  = pieOf("by_provider_kind", "Calls by provider type", "OpenAI, Anthropic, Mistral, Ollama… which vendors the traffic actually goes to.", "provider_kind")
    val ByProvider      = pieOf("by_provider", "Calls by provider", "Distribution across the configured provider entities.", Provider)
    val ByModality      = pieOf("by_modality", "Calls by modality", "Chat, responses, embeddings, images, audio, video, moderation, ocr.", "modality")
    val ByOperation     = pieOf("by_operation", "Calls by operation", "The exact endpoint family (`consumed_using`).", "consumed_using", widget = "pie")
    val StreamingRatio  = pieOf("streaming_ratio", "Streaming vs blocking", "How chat traffic is consumed.", "CASE WHEN streaming THEN 'streaming' ELSE 'blocking' END", extra = "modality IN ('chat', 'completion', 'responses')")
    val TopModels       = top("top_models", "Top models", "Models by number of calls.", "model")
    val TopProviders    = top("top_providers", "Top providers", "Provider entities by number of calls.", "provider_id", Some("provider_name"))
    val TopApikeys      = top("top_apikeys", "Top API keys", "API keys by number of calls.", "apikey_id", Some("apikey_name"))
    val TopUsers        = top("top_users", "Top users", "Users by number of calls.", "user_email")
    val TopRoutes       = top("top_routes", "Top routes", "Routes by number of calls.", "route_id", Some("route_name"))
    val CallsByModelTs  = tsByKey("requests_by_model_over_time", "Calls by model over time", "One series per model, for the most used models of the period.", "model")
    private val ApikeyKey = "COALESCE(apikey_name, apikey_id)"
    val CallsByApikeyTs  = tsByKey("requests_by_apikey_over_time", "Calls by API key over time", "One series per API key, for the most active keys of the period.", ApikeyKey)
    val TokensByApikeyTs = tsByKey("tokens_by_apikey_over_time", "Tokens by API key over time", "One series per API key, for the keys consuming the most tokens.", ApikeyKey, "SUM(total_tokens)")
    val CostByApikeyTs   = tsByKey("cost_by_apikey_over_time", "Spend by API key over time ($)", "One series per API key, for the most expensive keys of the period.", ApikeyKey, "SUM(total_cost)", "total_cost IS NOT NULL", asDouble = true)
    val CallsByUserTs    = tsByKey("requests_by_user_over_time", "Calls by user over time", "One series per user, for the most active users of the period.", "user_email")
    val TokensByUserTs   = tsByKey("tokens_by_user_over_time", "Tokens by user over time", "One series per user, for the users consuming the most tokens.", "user_email", "SUM(total_tokens)")
    val CostByUserTs     = tsByKey("cost_by_user_over_time", "Spend by user over time ($)", "One series per user, for the most expensive users of the period.", "user_email", "SUM(total_cost)", "total_cost IS NOT NULL", asDouble = true)
    val CallsByModalityTs = tsByKey("requests_by_modality_over_time", "Calls by modality over time", "One series per modality: chat, embeddings, images, audio…", "modality")
    val ActivityHeatmap = lq("cloudapim_llm_activity_heatmap", "LLM activity by weekday and hour", "Calls by day of week and hour of day (UTC) over the period: when the gateway is actually used.", AnalyticsShape.Heatmap, "heatmap") { ctx =>
      weekHourHeatmap(t(ctx.settings), real(ctx))(ctx)
    }
    val DistinctUsers     = metric("distinct_users", "Users", "Distinct users who called a model.", "COUNT(DISTINCT user_email)")
    val DistinctApikeys   = metric("distinct_apikeys", "API keys", "Distinct API keys that called a model.", "COUNT(DISTINCT apikey_id)")
    val DistinctModels    = metric("distinct_models", "Models", "Distinct models called.", "COUNT(DISTINCT model)")
    val DistinctProviders = metric("distinct_providers", "Providers", "Distinct provider entities called.", "COUNT(DISTINCT provider_id)")
    val RecentCalls = lq("cloudapim_llm_recent_calls", "Recent LLM calls", "The latest calls: who, which model, how many tokens, how much, how long.", AnalyticsShape.Table, "table", params = Seq(QueryParam("top_n", "int", JsNumber(25), "Number of calls"))) { ctx =>
      latest(t(ctx.settings), Seq(
        "time"     -> "to_char(ts AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS')",
        "route"    -> "COALESCE(route_name, route_id, '—')",
        "consumer" -> "COALESCE(user_email, apikey_name, apikey_id, '—')",
        "model"    -> "COALESCE(model, '—')",
        "tokens"   -> "total_tokens::text",
        "cost_usd" -> "COALESCE(to_char(total_cost, 'FM999999990.000000'), '—')",
        "ms"       -> "COALESCE(duration_ms::text, '—')",
        "status"   -> "CASE WHEN err THEN COALESCE(error_kind, 'error') WHEN cache_status = 'hit' THEN 'cached' ELSE 'ok' END"
      ), real(ctx))(ctx)
    }
    private def consumers(id: String, name: String, description: String, key: String, keyName: String, label: String) =
      lq(s"cloudapim_llm_$id", name, description, AnalyticsShape.Table, "table", params = Seq(TopNParam)) { ctx =>
        table(t(ctx.settings), label, keyName, Seq(
          ("calls", "COUNT(*)", false),
          ("tokens", "COALESCE(SUM(total_tokens), 0)", false),
          ("spend_usd", "ROUND(COALESCE(SUM(total_cost), 0)::numeric, 4)", true),
          ("errors", "COUNT(*) FILTER (WHERE err)", false),
          ("gco2eq", "ROUND((COALESCE(SUM(gwp_kgco2eq), 0) * 1000)::numeric, 3)", true)
        ), real(ctx, s"$key IS NOT NULL"), orderBy = "4 DESC NULLS LAST, 2 DESC")(ctx)
      }
    val RoutesTable  = consumers("routes_table", "Routes", "Calls, tokens, spend, errors and emissions per route.", "route_id", "route", "COALESCE(route_name, route_id)")
    val ApikeysTable = consumers("apikeys_table", "API keys", "Calls, tokens, spend, errors and emissions per API key — the chargeback table.", "apikey_id", "apikey", "COALESCE(apikey_name, apikey_id)")
    val UsersTable   = consumers("users_table", "Users", "Calls, tokens, spend, errors and emissions per user.", "user_email", "user", "user_email")

    // ---- tokens -----------------------------------------------------------------------------------

    val TokensTotal       = metric("tokens_total", "Tokens", "Input, output and reasoning tokens consumed.", "COALESCE(SUM(total_tokens), 0)")
    val InputTokensTotal  = metric("input_tokens_total", "Input tokens", "Prompt tokens sent to the models.", "COALESCE(SUM(input_tokens), 0)")
    val OutputTokensTotal = metric("output_tokens_total", "Output tokens", "Tokens generated by the models.", "COALESCE(SUM(output_tokens), 0)")
    val ReasoningTotal    = metric("reasoning_tokens_total", "Reasoning tokens", "Tokens spent thinking — billed, never shown.", "COALESCE(SUM(reasoning_tokens), 0)")
    val TokensPerCall     = metric("tokens_per_request", "Tokens per call", "Average tokens per call.", "COALESCE(AVG(total_tokens), 0)", asDouble = true)
    val TokensOverTime    = ts("tokens_over_time", "Tokens over time", "Input, output and reasoning tokens per bucket.", Seq(
      "input"     -> "COALESCE(SUM(input_tokens), 0)",
      "output"    -> "COALESCE(SUM(output_tokens), 0)",
      "reasoning" -> "COALESCE(SUM(reasoning_tokens), 0)"
    ))
    val TokensByModel     = top("tokens_by_model", "Tokens by model", "Models by tokens consumed.", "model", value = "SUM(total_tokens)")
    val TokensByProvider  = pieOf("tokens_by_provider", "Tokens by provider", "Tokens consumed per provider entity.", Provider, "SUM(total_tokens)")
    val TokensByApikey    = top("tokens_by_apikey", "Tokens by API key", "API keys by tokens consumed.", "apikey_id", Some("apikey_name"), "SUM(total_tokens)")
    val TokensByUser      = top("tokens_by_user", "Tokens by user", "Users by tokens consumed.", "user_email", value = "SUM(total_tokens)")
    val TokensByRoute     = top("tokens_by_route", "Tokens by route", "Routes by tokens consumed.", "route_id", Some("route_name"), "SUM(total_tokens)")
    val TokensByModality  = pieOf("tokens_by_modality", "Tokens by modality", "Tokens consumed per modality.", "modality", "SUM(total_tokens)")
    val PromptRatio       = metric("prompt_completion_ratio", "Prompt / completion ratio", "Prompt tokens sent for each token generated: how much context each answer carries.", "COALESCE(SUM(input_tokens)::float / NULLIF(SUM(output_tokens + reasoning_tokens), 0), 0)", asDouble = true)
    val TokensByModelTs   = tsByKey("tokens_by_model_over_time", "Tokens by model over time", "One series per model, for the models consuming the most tokens.", "model", "SUM(total_tokens)")
    val TokensPerCallByModel = top("tokens_per_request_by_model", "Tokens per call by model", "Average tokens per call — where prompts or answers are heaviest.", "model", value = "AVG(total_tokens)", asDouble = true)
    val OutputRatioByModel   = top("output_ratio_by_model", "Output / input ratio by model", "Generated tokens per prompt token: chatty models, or prompts carrying too much context.", "model", value = "SUM(output_tokens + reasoning_tokens)::float / NULLIF(SUM(input_tokens), 0)", asDouble = true, having = "SUM(input_tokens) > 0")

    // ---- costs ------------------------------------------------------------------------------------

    val CostTotal        = metric("cost_total", "Spend ($)", "What the calls cost, from the price table or as reported by the provider.", "COALESCE(SUM(total_cost), 0)", asDouble = true)
    val InputCostTotal   = metric("input_cost_total", "Input spend ($)", "What prompts cost.", "COALESCE(SUM(input_cost), 0)", HasCost, asDouble = true)
    val OutputCostTotal  = metric("output_cost_total", "Output spend ($)", "What generated tokens cost.", "COALESCE(SUM(output_cost), 0)", HasCost, asDouble = true)
    val ReasoningCostTotal = metric("reasoning_cost_total", "Reasoning spend ($)", "What thinking tokens cost.", "COALESCE(SUM(reasoning_cost), 0)", HasCost, asDouble = true)
    val CostPer1kTokens  = metric("cost_per_1k_tokens", "Cost per 1k tokens ($)", "The blended price actually paid per thousand tokens.", "COALESCE(SUM(total_cost) * 1000.0 / NULLIF(SUM(total_tokens), 0), 0)", HasCost, asDouble = true)
    val CumulativeCost   = lq("cloudapim_llm_cumulative_cost_over_time", "Cumulative spend ($)", "Running total of the spend over the period: the burn curve.", AnalyticsShape.Timeseries, "area") { ctx =>
      series(t(ctx.settings), Seq("spend" -> "COALESCE(SUM(total_cost), 0)"), real(ctx, HasCost), asDouble = true, cumulative = true)(ctx)
    }
    val CostPerCall      = metric("cost_per_request", "Cost per call ($)", "Average cost of a priced call.", "COALESCE(AVG(total_cost), 0)", HasCost, asDouble = true)
    val CostProjection   =
      lq("cloudapim_llm_cost_monthly_projection", "Projected monthly spend ($)", "The spend of the selected period, extrapolated to 30 days. Pick a representative period.", AnalyticsShape.Scalar, "metric", compare = true) { ctx =>
        val seconds = math.max(60L, java.time.Duration.between(ctx.filters.from, ctx.filters.to).getSeconds)
        scalar(t(ctx.settings), s"COALESCE(SUM(total_cost), 0) * ${2592000.0 / seconds}", "Projected monthly spend", real(ctx), asDouble = true)(ctx)
      }
    val CostOverTime     = ts("cost_over_time", "Spend over time ($)", "Input, output and reasoning cost per bucket.", Seq(
      "input"     -> "COALESCE(SUM(input_cost), 0)",
      "output"    -> "COALESCE(SUM(output_cost), 0)",
      "reasoning" -> "COALESCE(SUM(reasoning_cost), 0)"
    ), asDouble = true)
    val CostByModel      = top("cost_by_model", "Spend by model ($)", "Models by cost.", "model", value = "SUM(total_cost)", extra = HasCost, asDouble = true)
    val CostByProvider   = pieOf("cost_by_provider", "Spend by provider ($)", "Cost per provider entity.", Provider, "SUM(total_cost)", HasCost, asDouble = true)
    val CostByModality   = pieOf("cost_by_modality", "Spend by modality ($)", "Cost per modality.", "modality", "SUM(total_cost)", HasCost, asDouble = true, widget = "pie")
    val CostByApikey     = top("cost_by_apikey", "Spend by API key ($)", "API keys by cost — the chargeback view.", "apikey_id", Some("apikey_name"), "SUM(total_cost)", HasCost, asDouble = true)
    val CostByUser       = top("cost_by_user", "Spend by user ($)", "Users by cost.", "user_email", value = "SUM(total_cost)", extra = HasCost, asDouble = true)
    val CostByRoute      = top("cost_by_route", "Spend by route ($)", "Routes by cost.", "route_id", Some("route_name"), "SUM(total_cost)", HasCost, asDouble = true)
    val CostByModelTs    = tsByKey("cost_by_model_over_time", "Spend by model over time ($)", "One series per model, for the most expensive models of the period.", "model", "SUM(total_cost)", HasCost, asDouble = true)
    val CostPerMillion   = top("cost_per_million_tokens_by_model", "Cost per million tokens by model ($)", "The effective price actually paid, reasoning tokens included — not the list price.", "model", value = "SUM(total_cost) * 1000000.0 / NULLIF(SUM(total_tokens), 0)", extra = HasCost, asDouble = true, having = "SUM(total_tokens) > 0")
    val CostBySource     = pieOf("cost_by_source", "Spend by pricing source", "Costs computed from the price table versus reported by the provider.", "cost_source", "SUM(total_cost)", HasCost, asDouble = true, widget = "pie")
    val UnpricedCalls    = metric("unpriced_requests", "Unpriced calls", "Successful calls with no cost attached: a model missing from the price table, or cost tracking disabled.", "COUNT(*)", "err = false AND total_cost IS NULL AND cache_status IS DISTINCT FROM 'hit'")
    val ModelsTable      = lq("cloudapim_llm_models_table", "Models", "Calls, tokens, spend, latency and errors per model.", AnalyticsShape.Table, "table", params = Seq(TopNParam)) { ctx =>
      table(t(ctx.settings), "model", "model", Seq(
        ("calls", "COUNT(*)", false),
        ("tokens", "COALESCE(SUM(total_tokens), 0)", false),
        ("spend_usd", "ROUND(COALESCE(SUM(total_cost), 0)::numeric, 4)", true),
        ("usd_per_1k_tokens", "ROUND(COALESCE(SUM(total_cost) * 1000.0 / NULLIF(SUM(total_tokens), 0), 0)::numeric, 6)", true),
        ("avg_ms", "ROUND(COALESCE(AVG(duration_ms), 0))", false),
        ("error_rate_pct", s"ROUND(($ErrorRate * 100)::numeric, 2)", true),
        ("gco2eq_per_1k_tokens", "ROUND(COALESCE(SUM(gwp_kgco2eq) * 1000000.0 / NULLIF(SUM(total_tokens), 0), 0)::numeric, 4)", true)
      ), real(ctx, "model IS NOT NULL"))(ctx)
    }

    // ---- budgets ----------------------------------------------------------------------------------

    val CallsByBudget  = lq("cloudapim_llm_requests_by_budget", "Calls by budget", "Calls counted against each budget.", AnalyticsShape.TopN, "bar", params = Seq(TopNParam)) { ctx =>
      topN(s"${t(ctx.settings)}, unnest(budget_ids) AS budget_id", "budget_id", None, "COUNT(*)", real(ctx), relabel = budgetName(ctx))(ctx)
    }
    val CostByBudget   = lq("cloudapim_llm_cost_by_budget", "Spend by budget ($)", "Cost counted against each budget.", AnalyticsShape.TopN, "bar", params = Seq(TopNParam)) { ctx =>
      topN(s"${t(ctx.settings)}, unnest(budget_ids) AS budget_id", "budget_id", None, "SUM(total_cost)", real(ctx, HasCost), asDouble = true, relabel = budgetName(ctx))(ctx)
    }
    val TokensByBudget = lq("cloudapim_llm_tokens_by_budget", "Tokens by budget", "Tokens counted against each budget.", AnalyticsShape.TopN, "bar", params = Seq(TopNParam)) { ctx =>
      topN(s"${t(ctx.settings)}, unnest(budget_ids) AS budget_id", "budget_id", None, "SUM(total_tokens)", real(ctx), relabel = budgetName(ctx))(ctx)
    }
    val CostByBudgetTs = lq("cloudapim_llm_cost_by_budget_over_time", "Spend by budget over time ($)", "One series per budget.", AnalyticsShape.Timeseries, "line", params = Seq(QueryParam("top_n", "int", JsNumber(5), "Number of series"))) { ctx =>
      seriesByKey(s"${t(ctx.settings)}, unnest(budget_ids) AS budget_id", "budget_id", "SUM(total_cost)", real(ctx, HasCost), asDouble = true, relabel = budgetName(ctx))(ctx)
    }

    // ---- cache ------------------------------------------------------------------------------------

    val CacheHitRate  = metric("cache_hit_rate", "Cache hit rate", "Share of blocking chat calls answered by the simple or semantic cache.", "COALESCE(AVG(CASE WHEN cache_status = 'hit' THEN 1.0 ELSE 0.0 END), 0)", "cache_status IS NOT NULL", asDouble = true)
    val CacheHits     = metric("cache_hits_total", "Cache hits", "Calls answered from cache — no provider call, no cost.", "COUNT(*)", "cache_status = 'hit'")
    val CacheOverTime = ts("cache_over_time", "Cache hits vs misses", "Cache outcome per bucket, on calls going through a cache.", Seq(
      "hit"  -> "COUNT(*) FILTER (WHERE cache_status = 'hit')",
      "miss" -> "COUNT(*) FILTER (WHERE cache_status <> 'hit')"
    ), "cache_status IS NOT NULL")
    val CacheByStatus = pieOf("cache_by_status", "Cache outcomes", "Hit, miss, refresh, bypass.", "cache_status", extra = "cache_status IS NOT NULL")

    // ---- ecological impact ------------------------------------------------------------------------

    val EnergyTotal   = metric("energy_total", "Energy (Wh)", "Electricity drawn by inference, as estimated by EcoLogits.", "COALESCE(SUM(energy_kwh), 0) * 1000", HasImpact, asDouble = true)
    val GwpTotal      = metric("gwp_total", "Emissions (gCO2eq)", "Global warming potential of the calls: usage and embodied (hardware manufacturing).", "COALESCE(SUM(gwp_kgco2eq), 0) * 1000", HasImpact, asDouble = true)
    val AdpeTotal     = metric("adpe_total", "Abiotic depletion (mgSbeq)", "Consumption of non-renewable minerals and metals.", "COALESCE(SUM(adpe_kgsbeq), 0) * 1000000", HasImpact, asDouble = true)
    val PeTotal       = metric("pe_total", "Primary energy (MJ)", "Primary energy consumed, all sources.", "COALESCE(SUM(pe_mj), 0)", HasImpact, asDouble = true)
    val WcfTotal      = metric("wcf_total", "Water (L)", "Water consumption footprint.", "COALESCE(SUM(wcf_l), 0)", HasImpact, asDouble = true)
    val GwpUsageTotal    = metric("gwp_usage_total", "Usage emissions (gCO2eq)", "Emissions from the electricity drawn while inferring.", "COALESCE(SUM(gwp_usage_kgco2eq), 0) * 1000", HasImpact, asDouble = true)
    val GwpEmbodiedTotal = metric("gwp_embodied_total", "Embodied emissions (gCO2eq)", "The share of the hardware's manufacturing footprint the calls used up.", "COALESCE(SUM(gwp_embodied_kgco2eq), 0) * 1000", HasImpact, asDouble = true)
    val GwpPer1kTokensTotal = metric("gwp_per_1k_tokens", "Emissions per 1k tokens (gCO2eq)", "Grams of CO2eq per thousand tokens, all models together.", "COALESCE(SUM(gwp_kgco2eq) * 1000000.0 / NULLIF(SUM(total_tokens), 0), 0)", HasImpact, asDouble = true)
    val CumulativeGwp = lq("cloudapim_llm_cumulative_gwp_over_time", "Cumulative emissions (gCO2eq)", "Running total of the emissions over the period.", AnalyticsShape.Timeseries, "area") { ctx =>
      series(t(ctx.settings), Seq("gco2eq" -> "COALESCE(SUM(gwp_kgco2eq), 0) * 1000"), real(ctx, HasImpact), asDouble = true, cumulative = true)(ctx)
    }
    val PeOverTime    = ts("pe_over_time", "Primary energy over time (MJ)", "Primary energy consumed per bucket.", Seq("primary_energy" -> "COALESCE(SUM(pe_mj), 0)"), HasImpact, asDouble = true)
    val GwpPerCall    = metric("gwp_per_request", "Emissions per call (gCO2eq)", "Average emissions of a call.", "COALESCE(AVG(gwp_kgco2eq), 0) * 1000", HasImpact, asDouble = true)
    val GwpOverTime   = ts("gwp_over_time", "Emissions over time (gCO2eq)", "Usage and embodied emissions per bucket.", Seq(
      "usage"    -> "COALESCE(SUM(gwp_usage_kgco2eq), 0) * 1000",
      "embodied" -> "COALESCE(SUM(gwp_embodied_kgco2eq), 0) * 1000"
    ), HasImpact, asDouble = true)
    val EnergyOverTime = ts("energy_over_time", "Energy over time (Wh)", "Estimated electricity drawn per bucket.", Seq("energy" -> "COALESCE(SUM(energy_kwh), 0) * 1000"), HasImpact, asDouble = true)
    val GwpByModel    = top("gwp_by_model", "Emissions by model (gCO2eq)", "Models by emissions.", "model", value = "SUM(gwp_kgco2eq) * 1000", extra = HasImpact, asDouble = true)
    val GwpByProvider = pieOf("gwp_by_provider", "Emissions by provider (gCO2eq)", "Emissions per provider entity.", Provider, "SUM(gwp_kgco2eq) * 1000", HasImpact, asDouble = true)
    val GwpByApikey   = top("gwp_by_apikey", "Emissions by API key (gCO2eq)", "API keys by emissions.", "apikey_id", Some("apikey_name"), "SUM(gwp_kgco2eq) * 1000", HasImpact, asDouble = true)
    val EnergyByModel = top("energy_by_model", "Energy by model (Wh)", "Models by estimated electricity drawn.", "model", value = "SUM(energy_kwh) * 1000", extra = HasImpact, asDouble = true)
    val GwpPer1kTokens = top("gwp_per_1k_output_tokens_by_model", "Emissions per 1k generated tokens by model (gCO2eq)", "The carbon efficiency of each model: the same answer, a very different footprint.", "model", value = "SUM(gwp_kgco2eq) * 1000000.0 / NULLIF(SUM(output_tokens + reasoning_tokens), 0)", extra = HasImpact, asDouble = true, having = "SUM(output_tokens + reasoning_tokens) > 0")
    val GwpByModelTs  = tsByKey("gwp_by_model_over_time", "Emissions by model over time (gCO2eq)", "One series per model, for the most emitting models.", "model", "SUM(gwp_kgco2eq) * 1000", HasImpact, asDouble = true)

    // ---- performance ------------------------------------------------------------------------------

    val LatencyAvg     = metric("latency_avg", "Average latency", "Average call duration.", "COALESCE(AVG(duration_ms), 0)", HasTime, asDouble = true)
    val LatencyP95     = metric("latency_p95", "p95 latency", "95th percentile of the call duration.", "COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms), 0)", HasTime, asDouble = true)
    val LatencyP50     = metric("latency_p50", "Median latency", "Median call duration.", "COALESCE(percentile_cont(0.5) WITHIN GROUP (ORDER BY duration_ms), 0)", HasTime, asDouble = true)
    val LatencyP99     = metric("latency_p99", "p99 latency", "99th percentile of the call duration.", "COALESCE(percentile_cont(0.99) WITHIN GROUP (ORDER BY duration_ms), 0)", HasTime, asDouble = true)
    private val Speed  = "COALESCE(SUM(output_tokens + reasoning_tokens) * 1000.0 / NULLIF(SUM(duration_ms), 0), 0)"
    val TokensPerSecondTotal = metric("output_tokens_per_second", "Generation speed (tokens/s)", "Generated tokens per second of call duration, all models together.", Speed, "duration_ms > 0", asDouble = true)
    val TokensPerSecondTs    = ts("output_tokens_per_second_over_time", "Generation speed over time (tokens/s)", "Generated tokens per second of call duration, per bucket.", Seq("tokens_per_second" -> Speed), "duration_ms > 0", asDouble = true, widget = "line")
    val LatencyDistribution  = lq("cloudapim_llm_latency_distribution", "LLM latency distribution", "Calls by duration band.", AnalyticsShape.TopN, "bar") { ctx =>
      bands(t(ctx.settings), Seq(
        "<100ms"    -> "duration_ms < 100",
        "100-250ms" -> "duration_ms >= 100 AND duration_ms < 250",
        "250-500ms" -> "duration_ms >= 250 AND duration_ms < 500",
        "0.5-1s"    -> "duration_ms >= 500 AND duration_ms < 1000",
        "1-2s"      -> "duration_ms >= 1000 AND duration_ms < 2000",
        "2-5s"      -> "duration_ms >= 2000 AND duration_ms < 5000",
        "5-10s"     -> "duration_ms >= 5000 AND duration_ms < 10000",
        ">=10s"     -> "duration_ms >= 10000"
      ), real(ctx, HasTime))(ctx)
    }
    val LatencyAvgTs   = ts("latency_avg_over_time", "Average latency over time", "Average call duration per bucket.", Seq("avg" -> "COALESCE(AVG(duration_ms), 0)"), HasTime, asDouble = true, widget = "line")
    val LatencyPctTs   = ts("latency_percentiles_over_time", "Latency percentiles", "p50, p95 and p99 of the call duration per bucket.", Seq(
      "p50" -> "COALESCE(percentile_cont(0.5) WITHIN GROUP (ORDER BY duration_ms), 0)",
      "p95" -> "COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms), 0)",
      "p99" -> "COALESCE(percentile_cont(0.99) WITHIN GROUP (ORDER BY duration_ms), 0)"
    ), HasTime, asDouble = true, widget = "line")
    val LatencyByModel    = top("latency_by_model", "Slowest models (avg ms)", "Models by average call duration.", "model", value = "AVG(duration_ms)", extra = HasTime, asDouble = true)
    val LatencyByProvider = top("latency_by_provider", "Slowest providers (avg ms)", "Provider entities by average call duration.", "provider_id", Some("provider_name"), "AVG(duration_ms)", HasTime, asDouble = true)
    val P95ByModel        = top("latency_p95_by_model", "p95 latency by model (ms)", "The tail each model makes callers wait for.", "model", value = "percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms)", extra = HasTime, asDouble = true)
    val TokensPerSecond   = top("output_tokens_per_second_by_model", "Generation speed by model (tokens/s)", "Generated tokens per second of call duration — the throughput a user feels.", "model", value = "SUM(output_tokens + reasoning_tokens) * 1000.0 / NULLIF(SUM(duration_ms), 0)", extra = "duration_ms > 0 AND output_tokens > 0", asDouble = true)
    val LatencyHeatmap    = lq("cloudapim_llm_latency_heatmap", "LLM latency heatmap", "Call durations over time, by band.", AnalyticsShape.Heatmap, "heatmap") { ctx =>
      heatmap(t(ctx.settings), Seq(
        "<500ms"   -> "duration_ms < 500",
        "0.5-1s"   -> "duration_ms >= 500 AND duration_ms < 1000",
        "1-2s"     -> "duration_ms >= 1000 AND duration_ms < 2000",
        "2-5s"     -> "duration_ms >= 2000 AND duration_ms < 5000",
        "5-10s"    -> "duration_ms >= 5000 AND duration_ms < 10000",
        "10-30s"   -> "duration_ms >= 10000 AND duration_ms < 30000",
        ">=30s"    -> "duration_ms >= 30000"
      ), real(ctx, HasTime))(ctx)
    }

    // ---- reliability ------------------------------------------------------------------------------

    val ErrorRateTs        = ts("error_rate_over_time", "LLM error rate over time", "Share of failed calls per bucket.", Seq("error_rate" -> ErrorRate), asDouble = true, widget = "line")
    val ErrorsByKind       = pieOf("errors_by_kind", "Errors by kind", "Provider status codes, exceptions, guardrail denials, open circuits…", "error_kind", extra = "err = true")
    val TopErrorMessages   = top("top_error_messages", "Top error messages", "The errors seen most often.", "error_message", extra = "err = true")
    val ErrorsByProvider   = top("errors_by_provider", "Errors by provider", "Provider entities by number of failed calls.", "provider_id", Some("provider_name"), extra = "err = true")
    val ErrorsByRoute      = top("errors_by_route", "Errors by route", "Routes by number of failed calls.", "route_id", Some("route_name"), extra = "err = true")
    val RecentErrors       = lq("cloudapim_llm_recent_errors", "Recent LLM errors", "The latest failed calls, with their error.", AnalyticsShape.Table, "table", params = Seq(QueryParam("top_n", "int", JsNumber(25), "Number of errors"))) { ctx =>
      latest(t(ctx.settings), Seq(
        "time"     -> "to_char(ts AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS')",
        "route"    -> "COALESCE(route_name, route_id, '—')",
        "provider" -> "COALESCE(provider_name, provider_id, provider_kind, '—')",
        "model"    -> "COALESCE(model, '—')",
        "kind"     -> "COALESCE(error_kind, '—')",
        "message"  -> "COALESCE(error_message, '—')"
      ), real(ctx, "err = true"))(ctx)
    }
    val ErrorsByModel      = top("errors_by_model", "Errors by model", "Models by number of failed calls.", "model", extra = "err = true")
    val ErrorRateByProvider = top("error_rate_by_provider", "Error rate by provider", "Share of failed calls per provider entity.", "provider_id", Some("provider_name"), ErrorRate, asDouble = true)
    val GuardrailDenials   = metric("guardrail_denials_total", "Guardrail denials", "Calls refused by a guardrail configured to fail on deny.", "COUNT(*)", "error_kind = 'guardrail_denied'")
    val GuardrailDenialsTs = ts("guardrail_denials_over_time", "Guardrail denials over time", "Calls refused by a guardrail, per bucket.", Seq("denied" -> "COUNT(*)"), "error_kind = 'guardrail_denied'")
    val RateLimitHeadroom  = top("ratelimit_headroom_by_provider", "Provider rate limit headroom (tokens)", "The lowest remaining token allowance each provider reported: the closest to being throttled first.", "provider_id", Some("provider_name"), "MIN(ratelimit_tokens_remaining)", "ratelimit_tokens_remaining IS NOT NULL", ascending = true)
    val ProvidersTable     = lq("cloudapim_llm_providers_table", "Provider health", "Calls, error rate and latency per provider entity.", AnalyticsShape.Table, "table", params = Seq(TopNParam)) { ctx =>
      table(t(ctx.settings), "COALESCE(provider_name, provider_id)", "provider", Seq(
        ("calls", "COUNT(*)", false),
        ("errors", "COUNT(*) FILTER (WHERE err)", false),
        ("error_rate_pct", s"ROUND(($ErrorRate * 100)::numeric, 2)", true),
        ("avg_ms", "ROUND(COALESCE(AVG(duration_ms), 0))", false),
        ("p95_ms", "ROUND(COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms), 0)::numeric)", false)
      ), real(ctx, "provider_id IS NOT NULL"))(ctx)
    }

    // ---- calls log --------------------------------------------------------------------------------

    private val LogColumns = Seq(
      "id", "ts", "request_id", "route_id", "route_name", "apikey_id", "apikey_name", "user_email", "from_ip",
      "consumed_using", "modality", "streaming", "provider_kind", "provider_id", "provider_name", "model", "err",
      "error_kind", "error_message", "duration_ms", "input_tokens", "output_tokens", "reasoning_tokens", "total_tokens",
      "cache_status", "input_cost", "output_cost", "reasoning_cost", "total_cost", "cost_source", "energy_kwh",
      "gwp_kgco2eq", "budget_ids"
    )

    // every column as json, typed: numbers stay numbers, timestamps become epoch millis
    private def rowJson(row: io.vertx.sqlclient.Row): JsObject = JsObject((0 until row.size()).map { i =>
      val value: JsValue = row.getValue(i) match {
        case null                              => JsNull
        case v: java.time.OffsetDateTime       => QueryHelpers.jsTs(v)
        case v: java.lang.Boolean              => JsBoolean(v)
        case v: java.lang.Number               => JsNumber(BigDecimal(v.toString))
        case v: io.vertx.core.json.JsonObject  => Json.parse(v.encode())
        case v: Array[?]                       => JsArray(v.toSeq.map(x => JsString(String.valueOf(x))))
        case v                                 => JsString(v.toString)
      }
      // jsonb columns may come back as their text form
      val parsed = value match {
        case JsString(str) if row.getColumnName(i) == "raw" => scala.util.Try(Json.parse(str)).getOrElse(value)
        case other                                          => other
      }
      row.getColumnName(i) -> parsed
    })

    private class Binder(start: Int) {
      val clauses = scala.collection.mutable.ListBuffer[String]()
      val values  = scala.collection.mutable.ListBuffer[AnyRef]()
      private var idx = start
      // every `?` of the clause is bound to the same value
      def bind(clause: String, value: AnyRef): Unit = {
        clauses += clause.replace("?", s"$$$idx")
        values += value
        idx += 1
      }
      def sql: String = clauses.mkString(" AND ")
    }

    private def param(ctx: QueryContext, name: String): Option[String] =
      (ctx.params \ name).asOpt[String].map(_.trim).filter(_.nonEmpty)

    val CallsLog = lq("cloudapim_llm_calls_log", "LLM calls log", "The calls of the period one by one, newest first, filterable by model, provider, consumer and status. Page with `before` (epoch millis of the last row).", AnalyticsShape.Table, "table", params = Seq(
      QueryParam("limit", "int", JsNumber(50), "Number of calls (max 200)"),
      QueryParam("before", "int", JsNull, "Only calls older than this instant (epoch millis), to fetch the next page"),
      QueryParam("model", "string", JsNull, "Only calls to this model"),
      QueryParam("provider_id", "string", JsNull, "Only calls served by this provider entity"),
      QueryParam("status", "string", JsNull, "ok, error or cached"),
      QueryParam("search", "string", JsNull, "Text searched in the model, the consumer and the error message")
    )) { ctx =>
      given ExecutionContext = ctx.ec
      val (where, vals) = FilterSql.whereClause(ctx.filters)
      val b             = new Binder(vals.size + 1)
      (ctx.params \ "before").asOpt[Long].foreach(v => b.bind("ts < ?", java.time.OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(v), java.time.ZoneOffset.UTC)))
      param(ctx, "model").foreach(v => b.bind("model = ?", v))
      param(ctx, "provider_id").foreach(v => b.bind("provider_id = ?", v))
      param(ctx, "search").foreach(v => b.bind("(model ILIKE ? OR apikey_name ILIKE ? OR user_email ILIKE ? OR error_message ILIKE ?)", s"%$v%"))
      param(ctx, "status").collect {
        case "ok"     => b.clauses += "err = false AND cache_status IS DISTINCT FROM 'hit'"
        case "error"  => b.clauses += "err = true"
        case "cached" => b.clauses += "cache_status = 'hit'"
      }
      val limit = (ctx.params \ "limit").asOpt[Int].getOrElse(50).max(1).min(200)
      val sql   = s"SELECT ${LogColumns.mkString(", ")} FROM ${t(ctx.settings)}${and(where, real(ctx, b.sql))} ORDER BY ts DESC LIMIT $limit"
      QueryHelpers.runSelect(ctx.pool, sql, vals ++ b.values).map { rows =>
        val items = rows.map(rowJson)
        val next  = if (rows.size < limit) JsNull else items.lastOption.flatMap(i => (i \ "ts").asOpt[JsValue]).getOrElse(JsNull)
        QueryResult(AnalyticsShape.Table, Json.obj("items" -> JsArray(items), "next_before" -> next), JsArray(items))
      }
    }

    val CallDetail = lq("cloudapim_llm_call_detail", "LLM call detail", "Every recorded field of one call, prompts and outputs excluded.", AnalyticsShape.Table, "table", params = Seq(
      QueryParam("id", "string", JsNull, "Id of the call (the `id` column of the calls log)")
    )) { ctx =>
      given ExecutionContext = ctx.ec
      val (where, vals) = FilterSql.whereClause(ctx.filters)
      param(ctx, "id") match {
        case None => Future.successful(QueryResult(AnalyticsShape.Table, Json.obj("items" -> JsArray())))
        case Some(id) =>
          val sql = s"SELECT * FROM ${t(ctx.settings)}${and(where, s"id = $$${vals.size + 1}")} LIMIT 1"
          QueryHelpers.runSelect(ctx.pool, sql, vals :+ id).map { rows =>
            val items = rows.map(rowJson)
            QueryResult(AnalyticsShape.Table, Json.obj("items" -> JsArray(items)), JsArray(items))
          }
      }
    }

    lazy val all: Seq[AnalyticsQuery] = Seq(
      CallsLog, CallDetail, CallsByApikeyTs, TokensByApikeyTs, CostByApikeyTs, CallsByUserTs, TokensByUserTs, CostByUserTs,
      RequestsTotal, ErrorsTotal, ErrorRate_, CallsOverTime, CallsPerSecond, ByProviderKind, ByProvider, ByModality,
      ByOperation, StreamingRatio, TopModels, TopProviders, TopApikeys, TopUsers, TopRoutes, CallsByModelTs,
      CallsByModalityTs, ActivityHeatmap, DistinctUsers, DistinctApikeys, DistinctModels, DistinctProviders, RecentCalls,
      RoutesTable, ApikeysTable, UsersTable,
      TokensTotal, InputTokensTotal, OutputTokensTotal, ReasoningTotal, TokensPerCall, TokensOverTime, TokensByModel,
      TokensByProvider, TokensByApikey, TokensByUser, TokensByRoute, TokensByModality, PromptRatio, TokensByModelTs,
      TokensPerCallByModel, OutputRatioByModel,
      CostTotal, InputCostTotal, OutputCostTotal, ReasoningCostTotal, CostPer1kTokens, CumulativeCost, CostPerCall,
      CostProjection, CostOverTime, CostByModel, CostByProvider, CostByModality, CostByApikey, CostByUser, CostByRoute,
      CostByModelTs, CostPerMillion, CostBySource, UnpricedCalls, ModelsTable,
      CallsByBudget, CostByBudget, TokensByBudget, CostByBudgetTs,
      CacheHitRate, CacheHits, CacheOverTime, CacheByStatus,
      EnergyTotal, GwpTotal, GwpUsageTotal, GwpEmbodiedTotal, GwpPer1kTokensTotal, CumulativeGwp, PeOverTime, AdpeTotal,
      PeTotal, WcfTotal, GwpPerCall, GwpOverTime, EnergyOverTime, GwpByModel, GwpByProvider, GwpByApikey, EnergyByModel,
      GwpPer1kTokens, GwpByModelTs,
      LatencyAvg, LatencyP50, LatencyP95, LatencyP99, TokensPerSecondTotal, TokensPerSecondTs, LatencyDistribution,
      LatencyAvgTs, LatencyPctTs, LatencyByModel, LatencyByProvider, P95ByModel, TokensPerSecond, LatencyHeatmap,
      ErrorRateTs, ErrorsByKind, TopErrorMessages, ErrorsByProvider, ErrorsByRoute, RecentErrors, ErrorsByModel,
      ErrorRateByProvider, GuardrailDenials, GuardrailDenialsTs, RateLimitHeadroom, ProvidersTable
    )
  }

  // =================================================================================================
  // Budgets, live
  // =================================================================================================

  /**
   * Where each budget stands in its current cycle.
   *
   * Read from the budgets' own counters, not from the analytics table: the counters are what enforces
   * the limit, and they are exact even when the exporter was set up after the cycle began. The period
   * filter does not apply — a budget cycle has its own.
   */
  object Budgets {

    private def usage(ctx: QueryContext): Future[Seq[(String, String, Option[Double], Option[Double], BigDecimal, Option[BigDecimal], Long, Option[Long])]] = {
      given ExecutionContext = ctx.ec
      given Env              = ctx.env
      val budgets            = ctx.env.adminExtensions
        .extension[AiExtension]
        .map(_.states.allBudgets())
        .getOrElse(Seq.empty)
        .filter(_.enabled)
        .filter(b => ctx.filters.tenant.forall(_.toLowerCase == b.location.tenant.value))
      Future.sequence(budgets.map { b =>
        b.getConsumptions().map { c =>
          val usdPct    = b.limits.total_usd.filter(_ > 0).map(l => (c.totalUsd / l).toDouble)
          val tokensPct = b.limits.total_tokens.filter(_ > 0).map(l => c.totalTokens.toDouble / l.toDouble)
          (b.id, b.name, usdPct, tokensPct, c.totalUsd, b.limits.total_usd, c.totalTokens, b.limits.total_tokens)
        }
      })
    }

    val UsagePercent = q("cloudapim_llm_budgets_usage", "Budget usage", "How much of each budget's current cycle is consumed, in dollars or tokens, whichever is closer to its limit. Ignores the period filter.", AnalyticsShape.TopN, "bar", params = Seq(TopNParam)) { ctx =>
      given ExecutionContext = ctx.ec
      val limit = (ctx.params \ "top_n").asOpt[Int].getOrElse(10).max(1).min(1000)
      usage(ctx).map { rows =>
        val items = rows
          .map { case (id, name, usd, tokens, _, _, _, _) => (id, name, (usd.toSeq ++ tokens.toSeq).maxOption.getOrElse(0.0)) }
          .sortBy(-_._3)
          .take(limit)
          .map { case (id, name, pct) => Json.obj("key" -> id, "label" -> name, "value" -> pct) }
        QueryResult(AnalyticsShape.TopN, Json.obj("items" -> JsArray(items)), JsArray(items))
      }
    }

    val Consumption = q("cloudapim_llm_budgets_consumption", "Budgets", "Consumed and allowed dollars and tokens for each budget's current cycle. Ignores the period filter.", AnalyticsShape.Table, "table") { ctx =>
      given ExecutionContext = ctx.ec
      def pct(v: Option[Double]): String = v.map(p => f"${p * 100}%.1f %%").getOrElse("—")
      usage(ctx).map { rows =>
        val items = rows
          .sortBy { case (_, _, usd, tokens, _, _, _, _) => -(usd.toSeq ++ tokens.toSeq).maxOption.getOrElse(0.0) }
          .map { case (id, name, usdPct, tokensPct, usd, usdLimit, tokens, tokensLimit) =>
            Json.obj(
              "key"          -> id,
              "budget"       -> name,
              "spent_usd"    -> f"${usd.toDouble}%.4f",
              "limit_usd"    -> usdLimit.map(l => f"${l.toDouble}%.2f").getOrElse("—"),
              "usd_used"     -> pct(usdPct),
              "tokens"       -> tokens.toString,
              "limit_tokens" -> tokensLimit.map(_.toString).getOrElse("—"),
              "tokens_used"  -> pct(tokensPct)
            )
          }
        QueryResult(AnalyticsShape.Table, Json.obj("items" -> JsArray(items)), JsArray(items))
      }
    }

    lazy val all: Seq[AnalyticsQuery] = Seq(UsagePercent, Consumption)
  }

  // =================================================================================================
  // Alerts: budgets, provider quotas, MCP zero-trust
  // =================================================================================================

  object Alerts {

    private def t(s: UserAnalyticsExporterSettings): String = AiAlertsProjection.table(s)

    val BudgetExceeded = q("cloudapim_llm_budget_exceeded_total", "Budget exceeded", "Calls that hit an exhausted budget with an alert configured.", AnalyticsShape.Scalar, "metric", compare = true) { ctx =>
      scalar(t(ctx.settings), "COUNT(*)", "Budget exceeded", "name = 'AiBudgetExceeded'")(ctx)
    }
    val BudgetAlmostExceeded = q("cloudapim_llm_budget_almost_exceeded_total", "Budget warnings", "Calls made while a budget was past its warning threshold.", AnalyticsShape.Scalar, "metric", compare = true) { ctx =>
      scalar(t(ctx.settings), "COUNT(*)", "Budget warnings", "name = 'AiBudgetAlmostExceeded'")(ctx)
    }
    val BudgetAlertsTs = q("cloudapim_llm_budget_alerts_over_time", "Budget alerts over time", "Budget warnings and exceeded budgets per bucket.", AnalyticsShape.Timeseries, "area") { ctx =>
      series(t(ctx.settings), Seq(
        "warning"  -> "COUNT(*) FILTER (WHERE name = 'AiBudgetAlmostExceeded')",
        "exceeded" -> "COUNT(*) FILTER (WHERE name = 'AiBudgetExceeded')"
      ), "category = 'budget'")(ctx)
    }
    val AlertsByBudget = q("cloudapim_llm_budget_alerts_by_budget", "Budget alerts by budget", "Which budgets are under pressure.", AnalyticsShape.TopN, "bar", params = Seq(TopNParam)) { ctx =>
      topN(s"${t(ctx.settings)}, unnest(budget_ids) AS budget_id", "budget_id", Some("budget_name"), "COUNT(*)", "category = 'budget'", relabel = budgetName(ctx))(ctx)
    }
    val QuotaAlerts = q("cloudapim_llm_provider_quota_alerts_total", "Provider quota incidents", "Times a provider throttled the gateway or ran out of credit.", AnalyticsShape.Scalar, "metric", compare = true) { ctx =>
      scalar(t(ctx.settings), "COUNT(*)", "Provider quota incidents", "name IN ('LLMProviderQuotaExceededAlert', 'LLMProviderCreditExhaustedAlert')")(ctx)
    }
    val QuotaAlertsTs = q("cloudapim_llm_provider_quota_alerts_over_time", "Provider quota incidents over time", "Throttling, exhausted credit and recoveries per bucket.", AnalyticsShape.Timeseries, "area") { ctx =>
      series(t(ctx.settings), Seq(
        "throttled"        -> "COUNT(*) FILTER (WHERE name = 'LLMProviderQuotaExceededAlert')",
        "credit_exhausted" -> "COUNT(*) FILTER (WHERE name = 'LLMProviderCreditExhaustedAlert')",
        "recovered"        -> "COUNT(*) FILTER (WHERE name = 'LLMProviderQuotaRecoveredAlert')"
      ), "category = 'provider_quota'")(ctx)
    }
    val QuotaAlertsByProvider = q("cloudapim_llm_provider_quota_alerts_by_provider", "Quota incidents by provider type", "Which vendors throttle or run dry.", AnalyticsShape.Pie, "donut") { ctx =>
      pie(t(ctx.settings), "provider_kind", "Provider", extra = "name IN ('LLMProviderQuotaExceededAlert', 'LLMProviderCreditExhaustedAlert')")(ctx)
    }
    val RefusedByQuota = q("cloudapim_llm_provider_quota_refused_calls", "Calls refused during quota incidents", "Calls the gateway refused while a provider was throttled or out of credit, as counted at recovery.", AnalyticsShape.Scalar, "metric", compare = true) { ctx =>
      scalar(t(ctx.settings), "COALESCE(SUM(refused_calls), 0)", "Refused calls", "name = 'LLMProviderQuotaRecoveredAlert'")(ctx)
    }
    val ZeroTrustTotal = q("cloudapim_mcp_zero_trust_events_total", "MCP zero-trust events", "Rug-pulls, guardrail hits and redactions on MCP tools.", AnalyticsShape.Scalar, "metric", compare = true) { ctx =>
      scalar(t(ctx.settings), "COUNT(*)", "Zero-trust events", "category = 'mcp_zero_trust'")(ctx)
    }
    val ZeroTrustBlocked = q("cloudapim_mcp_zero_trust_blocked_total", "MCP tools blocked", "Zero-trust decisions that blocked a tool.", AnalyticsShape.Scalar, "metric", compare = true) { ctx =>
      scalar(t(ctx.settings), "COUNT(*)", "Blocked", "category = 'mcp_zero_trust' AND blocked = true")(ctx)
    }
    val ZeroTrustByKind = q("cloudapim_mcp_zero_trust_by_kind", "Zero-trust events by kind", "Rug-pull, guardrail or redaction.", AnalyticsShape.Pie, "donut") { ctx =>
      pie(t(ctx.settings), "kind", "Kind", extra = "category = 'mcp_zero_trust'")(ctx)
    }
    val ZeroTrustByTool = q("cloudapim_mcp_zero_trust_by_tool", "Zero-trust events by tool", "The tools that changed under the gateway's feet or returned something they should not.", AnalyticsShape.TopN, "bar", params = Seq(TopNParam)) { ctx =>
      topN(t(ctx.settings), "tool", None, "COUNT(*)", "category = 'mcp_zero_trust' AND tool IS NOT NULL")(ctx)
    }
    val ZeroTrustTs = q("cloudapim_mcp_zero_trust_over_time", "Zero-trust events over time", "Blocked and observed zero-trust events per bucket.", AnalyticsShape.Timeseries, "area") { ctx =>
      series(t(ctx.settings), Seq(
        "blocked"  -> "COUNT(*) FILTER (WHERE blocked)",
        "observed" -> "COUNT(*) FILTER (WHERE NOT blocked)"
      ), "category = 'mcp_zero_trust'")(ctx)
    }

    lazy val all: Seq[AnalyticsQuery] = Seq(
      BudgetExceeded, BudgetAlmostExceeded, BudgetAlertsTs, AlertsByBudget, QuotaAlerts, QuotaAlertsTs,
      QuotaAlertsByProvider, RefusedByQuota, ZeroTrustTotal, ZeroTrustBlocked, ZeroTrustByKind, ZeroTrustByTool, ZeroTrustTs
    )
  }

  // =================================================================================================
  // MCP
  // =================================================================================================

  object Mcp {

    private def t(s: UserAnalyticsExporterSettings): String = McpCallsProjection.table(s)

    private def metric(id: String, name: String, description: String, expr: String, extra: String = "", asDouble: Boolean = false) =
      q(s"cloudapim_mcp_$id", name, description, AnalyticsShape.Scalar, "metric", compare = true) { ctx =>
        scalar(t(ctx.settings), expr, name, extra, asDouble)(ctx)
      }

    private def pieOf(id: String, name: String, description: String, key: String, extra: String = "", widget: String = "donut") =
      q(s"cloudapim_mcp_$id", name, description, AnalyticsShape.Pie, widget) { ctx =>
        pie(t(ctx.settings), key, name, extra = extra)(ctx)
      }

    private def top(id: String, name: String, description: String, key: String, label: Option[String] = None, value: String = "COUNT(*)", extra: String = "", asDouble: Boolean = false) =
      q(s"cloudapim_mcp_$id", name, description, AnalyticsShape.TopN, "bar", params = Seq(TopNParam)) { ctx =>
        topN(t(ctx.settings), key, label, value, s"$key IS NOT NULL${if (extra.isEmpty) "" else s" AND $extra"}", asDouble)(ctx)
      }

    private val Failed    = "(err OR tool_error)"
    private val FailRate  = s"COALESCE(AVG(CASE WHEN $Failed THEN 1.0 ELSE 0.0 END), 0)"
    private val ToolCalls = "method = 'tools/call'"
    private val HasTime   = "duration_ms IS NOT NULL"

    val CallsTotal     = metric("calls_total", "MCP calls", "Every MCP request audited, served or sent through a connector.", "COUNT(*)")
    val ToolCallsTotal = metric("tool_calls_total", "Tool calls", "`tools/call` requests.", "COUNT(*)", ToolCalls)
    val ErrorsTotal    = metric("errors_total", "MCP failures", "Protocol errors, transport failures, and tools that reported an error.", "COUNT(*)", Failed)
    val ErrorRate      = metric("error_rate", "MCP failure rate", "Share of MCP requests that failed.", FailRate, asDouble = true)
    val LatencyP95     = metric("latency_p95", "MCP p95 latency", "95th percentile of the MCP request duration.", "COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms), 0)", HasTime, asDouble = true)
    val CallsOverTime  = q("cloudapim_mcp_calls_over_time", "MCP calls over time", "Successful and failed MCP requests per bucket.", AnalyticsShape.Timeseries, "area") { ctx =>
      series(t(ctx.settings), Seq("success" -> s"COUNT(*) FILTER (WHERE NOT $Failed)", "failure" -> s"COUNT(*) FILTER (WHERE $Failed)"))(ctx)
    }
    val ErrorRateTs    = q("cloudapim_mcp_error_rate_over_time", "MCP failure rate over time", "Share of failed MCP requests per bucket.", AnalyticsShape.Timeseries, "line", compare = true) { ctx =>
      series(t(ctx.settings), Seq("failure_rate" -> FailRate), asDouble = true)(ctx)
    }
    val ByMethod       = pieOf("by_method", "MCP requests by method", "initialize, tools/list, tools/call, resources/read, prompts/get…", "method")
    val BySide         = pieOf("by_side", "Served vs consumed", "Requests Otoroshi served as an MCP server, versus calls it made through connectors.", "side", widget = "pie")
    val ByTransport    = pieOf("by_transport", "MCP requests by transport", "Streamable http, sse, websocket, stdio connectors…", "transport")
    val ByProtocol     = pieOf("by_protocol_version", "MCP clients by protocol version", "Which protocol revisions clients negotiate — what an upgrade can drop.", "protocol_version", "protocol_version IS NOT NULL")
    val TopTools       = top("top_tools", "Top tools", "The most called tools.", "tool", extra = ToolCalls)
    val FailingTools   = top("failing_tools", "Failing tools", "Tools by number of failed calls, protocol and tool errors alike.", "tool", extra = s"$ToolCalls AND $Failed")
    val SlowestTools   = top("slowest_tools", "Slowest tools (avg ms)", "Tools by average call duration.", "tool", value = "AVG(duration_ms)", extra = s"$ToolCalls AND $HasTime", asDouble = true)
    val TopServers     = top("top_servers", "Top MCP servers", "Routes exposing MCP, by requests served.", "route_id", Some("route_name"), extra = "side = 'server'")
    val TopConnectors  = top("top_connectors", "Top MCP connectors", "Upstream MCP servers by calls made through them.", "connector_id", Some("connector_name"), extra = "side = 'client'")
    val FailingConnectors = top("failing_connectors", "Failing MCP connectors", "Upstream MCP servers by failed calls.", "connector_id", Some("connector_name"), extra = s"side = 'client' AND $Failed")
    val TopApikeys     = top("top_apikeys", "Top MCP API keys", "API keys by MCP requests.", "apikey_id", Some("apikey_name"))
    val TopUsers       = top("top_users", "Top MCP users", "Users by MCP requests.", "user_email")
    val TopErrors      = top("top_errors", "Top MCP errors", "The errors seen most often.", "error_message", extra = "err = true")
    val FailingFetches = top("failing_resource_fetches", "Failing resource fetches", "Resources whose fetch failed or was refused.", "target", extra = "side = 'fetch' AND err = true")
    val CallsByToolTs  = q("cloudapim_mcp_calls_by_tool_over_time", "Tool calls by tool over time", "One series per tool, for the most called tools.", AnalyticsShape.Timeseries, "line", params = Seq(QueryParam("top_n", "int", JsNumber(5), "Number of series"))) { ctx =>
      seriesByKey(t(ctx.settings), "tool", extra = ToolCalls)(ctx)
    }
    val LatencyPctTs   = q("cloudapim_mcp_latency_percentiles_over_time", "MCP latency percentiles", "p50, p95 and p99 of the MCP request duration per bucket.", AnalyticsShape.Timeseries, "line") { ctx =>
      series(t(ctx.settings), Seq(
        "p50" -> "COALESCE(percentile_cont(0.5) WITHIN GROUP (ORDER BY duration_ms), 0)",
        "p95" -> "COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms), 0)",
        "p99" -> "COALESCE(percentile_cont(0.99) WITHIN GROUP (ORDER BY duration_ms), 0)"
      ), HasTime, asDouble = true)(ctx)
    }
    val LatencyHeatmap = q("cloudapim_mcp_latency_heatmap", "MCP latency heatmap", "MCP request durations over time, by band.", AnalyticsShape.Heatmap, "heatmap") { ctx =>
      heatmap(t(ctx.settings), Seq(
        "<50ms"     -> "duration_ms < 50",
        "50-100ms"  -> "duration_ms >= 50 AND duration_ms < 100",
        "100-250ms" -> "duration_ms >= 100 AND duration_ms < 250",
        "250-500ms" -> "duration_ms >= 250 AND duration_ms < 500",
        "0.5-1s"    -> "duration_ms >= 500 AND duration_ms < 1000",
        "1-3s"      -> "duration_ms >= 1000 AND duration_ms < 3000",
        ">=3s"      -> "duration_ms >= 3000"
      ), HasTime)(ctx)
    }
    val ToolsTable     = q("cloudapim_mcp_tools_table", "Tools", "Calls, failures and latency per tool.", AnalyticsShape.Table, "table", params = Seq(TopNParam)) { ctx =>
      table(t(ctx.settings), "tool", "tool", Seq(
        ("calls", "COUNT(*)", false),
        ("failures", s"COUNT(*) FILTER (WHERE $Failed)", false),
        ("failure_rate_pct", s"ROUND(($FailRate * 100)::numeric, 2)", true),
        ("avg_ms", "ROUND(COALESCE(AVG(duration_ms), 0))", false),
        ("p95_ms", "ROUND(COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms), 0)::numeric)", false)
      ), s"$ToolCalls AND tool IS NOT NULL")(ctx)
    }

    lazy val all: Seq[AnalyticsQuery] = Seq(
      CallsTotal, ToolCallsTotal, ErrorsTotal, ErrorRate, LatencyP95, CallsOverTime, ErrorRateTs, ByMethod, BySide,
      ByTransport, ByProtocol, TopTools, FailingTools, SlowestTools, TopServers, TopConnectors, FailingConnectors,
      TopApikeys, TopUsers, TopErrors, FailingFetches, CallsByToolTs, LatencyPctTs, LatencyHeatmap, ToolsTable
    )
  }

  lazy val all: Seq[AnalyticsQuery] = Llm.all ++ Budgets.all ++ Alerts.all ++ Mcp.all
}
