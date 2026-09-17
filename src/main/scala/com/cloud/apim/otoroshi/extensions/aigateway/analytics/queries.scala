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
        case u if UserPattern.matches(u) => s"$UserKey = '$u'"
        case _                           => "1 = 0"
      }
      (Seq("delegated = false") ++ modality.map(m => s"modality = '$m'") ++ user ++ Option(extra).filter(_.nonEmpty).map(e => s"($e)"))
        .mkString(" AND ")
    }

    private val ModalityPattern = "^[a-z_]{1,32}$".r
    private val UserPattern     = "^[A-Za-z0-9._%+@-]{1,254}$".r

    /**
     * Who a call counts for: the person who made it (the AI Studio chat), otherwise the owner of the api
     * key it was made with. A key shared by a workspace has no owner, and its calls count for no user.
     */
    private val UserKey = "COALESCE(user_email, apikey_owner)"

    private val ModalityParam = QueryParam(
      "modality",
      "string",
      JsNull,
      "Only count calls of one modality: chat, responses, completion, embedding, image, audio, video, moderation or ocr"
    )

    private val UserParam = QueryParam("user", "string", JsNull, "Only count calls of one user (email): the calls they made and the calls of the API keys they own")

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
    val TopUsers        = top("top_users", "Top users", "Users by number of calls.", UserKey)
    val TopRoutes       = top("top_routes", "Top routes", "Routes by number of calls.", "route_id", Some("route_name"))
    val CallsByModelTs  = tsByKey("requests_by_model_over_time", "Calls by model over time", "One series per model, for the most used models of the period.", "model")
    private val ApikeyKey = "COALESCE(apikey_name, apikey_id)"
    val CallsByApikeyTs  = tsByKey("requests_by_apikey_over_time", "Calls by API key over time", "One series per API key, for the most active keys of the period.", ApikeyKey)
    val TokensByApikeyTs = tsByKey("tokens_by_apikey_over_time", "Tokens by API key over time", "One series per API key, for the keys consuming the most tokens.", ApikeyKey, "SUM(total_tokens)")
    val CostByApikeyTs   = tsByKey("cost_by_apikey_over_time", "Spend by API key over time ($)", "One series per API key, for the most expensive keys of the period.", ApikeyKey, "SUM(total_cost)", "total_cost IS NOT NULL", asDouble = true)
    val CallsByUserTs    = tsByKey("requests_by_user_over_time", "Calls by user over time", "One series per user, for the most active users of the period.", UserKey)
    val TokensByUserTs   = tsByKey("tokens_by_user_over_time", "Tokens by user over time", "One series per user, for the users consuming the most tokens.", UserKey, "SUM(total_tokens)")
    val CostByUserTs     = tsByKey("cost_by_user_over_time", "Spend by user over time ($)", "One series per user, for the most expensive users of the period.", UserKey, "SUM(total_cost)", "total_cost IS NOT NULL", asDouble = true)
    val CallsByModalityTs = tsByKey("requests_by_modality_over_time", "Calls by modality over time", "One series per modality: chat, embeddings, images, audio…", "modality")
    val ActivityHeatmap = lq("cloudapim_llm_activity_heatmap", "LLM activity by weekday and hour", "Calls by day of week and hour of day (UTC) over the period: when the gateway is actually used.", AnalyticsShape.Heatmap, "heatmap") { ctx =>
      weekHourHeatmap(t(ctx.settings), real(ctx))(ctx)
    }
    val DistinctUsers     = metric("distinct_users", "Users", "Distinct users who called a model, directly or through an API key they own.", s"COUNT(DISTINCT $UserKey)")
    val DistinctApikeys   = metric("distinct_apikeys", "API keys", "Distinct API keys that called a model.", "COUNT(DISTINCT apikey_id)")
    val DistinctModels    = metric("distinct_models", "Models", "Distinct models called.", "COUNT(DISTINCT model)")
    val DistinctProviders = metric("distinct_providers", "Providers", "Distinct provider entities called.", "COUNT(DISTINCT provider_id)")
    val RecentCalls = lq("cloudapim_llm_recent_calls", "Recent LLM calls", "The latest calls: who, which model, how many tokens, how much, how long.", AnalyticsShape.Table, "table", params = Seq(QueryParam("top_n", "int", JsNumber(25), "Number of calls"))) { ctx =>
      latest(t(ctx.settings), Seq(
        "time"     -> "to_char(ts AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS')",
        "route"    -> "COALESCE(route_name, route_id, '—')",
        "consumer" -> "COALESCE(user_email, apikey_name, apikey_id, '—')",
        "owner"    -> "COALESCE(apikey_owner, '—')",
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
          ("spend_usd", "ROUND(COALESCE(SUM(total_cost), 0)::numeric, 6)", true),
          ("errors", "COUNT(*) FILTER (WHERE err)", false),
          ("gco2eq", "ROUND((COALESCE(SUM(gwp_kgco2eq), 0) * 1000)::numeric, 3)", true)
        ), real(ctx, s"$key IS NOT NULL"), orderBy = "4 DESC NULLS LAST, 2 DESC")(ctx)
      }
    val RoutesTable  = consumers("routes_table", "Routes", "Calls, tokens, spend, errors and emissions per route.", "route_id", "route", "COALESCE(route_name, route_id)")
    val ApikeysTable = consumers("apikeys_table", "API keys", "Calls, tokens, spend, errors and emissions per API key — the chargeback table.", "apikey_id", "apikey", "COALESCE(apikey_name, apikey_id)")
    val EndUsersTable = consumers("end_users_table", "End users", "Calls, tokens, spend, errors and emissions per end user of the calling applications (the `user` field of their requests).", "end_user", "end_user", "end_user")
    val UsersTable   = consumers("users_table", "Users", "Calls, tokens, spend, errors and emissions per user, the calls of the API keys they own included.", UserKey, "user", UserKey)

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
    val TokensByUser      = top("tokens_by_user", "Tokens by user", "Users by tokens consumed.", UserKey, value = "SUM(total_tokens)")
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
    val CostByUser       = top("cost_by_user", "Spend by user ($)", "Users by cost.", UserKey, value = "SUM(total_cost)", extra = HasCost, asDouble = true)
    val CostByRoute      = top("cost_by_route", "Spend by route ($)", "Routes by cost.", "route_id", Some("route_name"), "SUM(total_cost)", HasCost, asDouble = true)
    val CostByModelTs    = tsByKey("cost_by_model_over_time", "Spend by model over time ($)", "One series per model, for the most expensive models of the period.", "model", "SUM(total_cost)", HasCost, asDouble = true)
    val CostPerMillion   = top("cost_per_million_tokens_by_model", "Cost per million tokens by model ($)", "The effective price actually paid, reasoning tokens included — not the list price.", "model", value = "SUM(total_cost) * 1000000.0 / NULLIF(SUM(total_tokens), 0)", extra = HasCost, asDouble = true, having = "SUM(total_tokens) > 0")
    val CostBySource     = pieOf("cost_by_source", "Spend by pricing source", "Costs computed from the price table versus reported by the provider.", "cost_source", "SUM(total_cost)", HasCost, asDouble = true, widget = "pie")
    val UnpricedCalls    = metric("unpriced_requests", "Unpriced calls", "Successful calls with no cost attached: a model missing from the price table, or cost tracking disabled.", "COUNT(*)", "err = false AND total_cost IS NULL AND cache_status IS DISTINCT FROM 'hit'")
    val ModelsTable      = lq("cloudapim_llm_models_table", "Models", "Calls, tokens, spend, latency and errors per model.", AnalyticsShape.Table, "table", params = Seq(TopNParam)) { ctx =>
      table(t(ctx.settings), "model", "model", Seq(
        ("calls", "COUNT(*)", false),
        ("tokens", "COALESCE(SUM(total_tokens), 0)", false),
        ("spend_usd", "ROUND(COALESCE(SUM(total_cost), 0)::numeric, 6)", true),
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
    private val HasTtft   = "ttft_ms IS NOT NULL"
    val TtftP50           = metric("ttft_p50", "Median time to first token", "Median delay before the first token of a streamed answer.", "COALESCE(percentile_cont(0.5) WITHIN GROUP (ORDER BY ttft_ms), 0)", HasTtft, asDouble = true)
    val TtftP95           = metric("ttft_p95", "p95 time to first token", "95th percentile of the delay before the first token of a streamed answer.", "COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY ttft_ms), 0)", HasTtft, asDouble = true)
    val TtftPctTs         = ts("ttft_percentiles_over_time", "Time to first token percentiles", "p50 and p95 of the delay before the first token of streamed answers, per bucket.", Seq(
      "p50" -> "COALESCE(percentile_cont(0.5) WITHIN GROUP (ORDER BY ttft_ms), 0)",
      "p95" -> "COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY ttft_ms), 0)"
    ), HasTtft, asDouble = true, widget = "line")
    val TtftByModel       = top("ttft_p50_by_model", "Time to first token by model (median ms)", "How long each model makes a streaming caller wait before answering.", "model", value = "percentile_cont(0.5) WITHIN GROUP (ORDER BY ttft_ms)", extra = HasTtft, asDouble = true)
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
    val ByFinishReason     = pieOf("by_finish_reason", "Calls by finish reason", "How answers ended: stop, length (truncated), tool_calls, content_filter…", "finish_reason", extra = "finish_reason IS NOT NULL")
    val TruncatedRate      = metric("truncated_rate", "Truncated answers", "Share of answers cut by the maximum output length.", "COALESCE(AVG(CASE WHEN finish_reason = 'length' THEN 1.0 ELSE 0.0 END), 0)", "finish_reason IS NOT NULL", asDouble = true)
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
      "id", "ts", "request_id", "route_id", "route_name", "apikey_id", "apikey_name", "apikey_owner", "user_email", "from_ip",
      "consumed_using", "modality", "streaming", "provider_kind", "provider_id", "provider_name", "model", "err",
      "error_kind", "error_message", "duration_ms", "ttft_ms", "finish_reason", "session_id", "end_user", "input_tokens", "output_tokens", "reasoning_tokens", "total_tokens",
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
      QueryParam("finish_reason", "string", JsNull, "Only calls that ended this way: stop, length, tool_calls, content_filter…"),
      QueryParam("session_id", "string", JsNull, "Only the calls of this session"),
      QueryParam("search", "string", JsNull, "Text searched in the model, the consumer and the error message")
    )) { ctx =>
      given ExecutionContext = ctx.ec
      val (where, vals) = FilterSql.whereClause(ctx.filters)
      val b             = new Binder(vals.size + 1)
      (ctx.params \ "before").asOpt[Long].foreach(v => b.bind("ts < ?", java.time.OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(v), java.time.ZoneOffset.UTC)))
      param(ctx, "model").foreach(v => b.bind("model = ?", v))
      param(ctx, "provider_id").foreach(v => b.bind("provider_id = ?", v))
      param(ctx, "finish_reason").foreach(v => b.bind("finish_reason = ?", v))
      param(ctx, "session_id").foreach(v => b.bind("session_id = ?", v))
      param(ctx, "search").foreach(v => b.bind("(model ILIKE ? OR apikey_name ILIKE ? OR user_email ILIKE ? OR apikey_owner ILIKE ? OR end_user ILIKE ? OR session_id ILIKE ? OR error_message ILIKE ?)", s"%$v%"))
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

    // what the gateway refuses by itself, before any provider is called: not a failure of the provider
    private val GatewayRefusal = "COALESCE(error_kind IN ('guardrail_denied', 'budget_exceeded', 'you_can''t_use_this_model', 'no_known_cost_for_this_model'), false)"
    private val ProviderFailure = s"err AND NOT $GatewayRefusal"

    /**
     * How the providers of the period behave: calls, failures (the refusals of the gateway itself aside),
     * latency and generation speed of the calls they served (cache hits aside), per provider entity or per
     * model of each provider entity.
     */
    val HealthTable = lq("cloudapim_llm_health_table", "Provider and model health", "Calls, failure rate, latency, time to first token and generation speed per provider, or per model of each provider. The refusals of the gateway (guardrails, budgets, model restrictions) are not failures, cache hits are not timed.", AnalyticsShape.Table, "table", params = Seq(
      QueryParam("group_by", "string", JsString("model"), "model (a row per model of each provider) or provider"),
      QueryParam("top_n", "int", JsNumber(200), "Number of rows, the busiest first (max 1000)")
    )) { ctx =>
      given ExecutionContext = ctx.ec
      val byModel       = !param(ctx, "group_by").contains("provider")
      val keys          = if (byModel) "provider_id, model" else "provider_id"
      val attempted     = s"cache_status IS DISTINCT FROM 'hit' AND NOT $GatewayRefusal"
      val timed         = "cache_status IS DISTINCT FROM 'hit' AND NOT err AND duration_ms IS NOT NULL"
      val generating    = s"$timed AND output_tokens + reasoning_tokens > 0 AND duration_ms > COALESCE(ttft_ms, 0)"
      val limit         = (ctx.params \ "top_n").asOpt[Int].getOrElse(200).max(1).min(1000)
      val (where, vals) = FilterSql.whereClause(ctx.filters)
      val sql           =
        s"""SELECT $keys, MAX(provider_name) AS provider_name, MAX(provider_kind) AS provider_kind,
           |  COUNT(*) AS calls,
           |  COUNT(*) FILTER (WHERE $ProviderFailure) AS failures,
           |  COUNT(*) FILTER (WHERE err AND $GatewayRefusal) AS refusals,
           |  COUNT(*) FILTER (WHERE cache_status = 'hit') AS cached,
           |  COALESCE((COUNT(*) FILTER (WHERE $ProviderFailure))::float / NULLIF(COUNT(*) FILTER (WHERE $attempted), 0), 0) AS failure_rate,
           |  percentile_cont(0.5) WITHIN GROUP (ORDER BY duration_ms) FILTER (WHERE $timed) AS p50_ms,
           |  percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms) FILTER (WHERE $timed) AS p95_ms,
           |  percentile_cont(0.5) WITHIN GROUP (ORDER BY ttft_ms) FILTER (WHERE $timed AND ttft_ms IS NOT NULL) AS p50_ttft_ms,
           |  (SUM(output_tokens + reasoning_tokens) FILTER (WHERE $generating))::float * 1000.0 / NULLIF(SUM(duration_ms - COALESCE(ttft_ms, 0)) FILTER (WHERE $generating), 0) AS tokens_per_second,
           |  MAX(ts) AS last_call,
           |  MAX(ts) FILTER (WHERE $ProviderFailure) AS last_failure,
           |  (array_agg(error_message ORDER BY ts DESC) FILTER (WHERE $ProviderFailure))[1] AS last_failure_message
           |FROM ${t(ctx.settings)}${and(where, real(ctx, s"provider_id IS NOT NULL${if (byModel) " AND model IS NOT NULL" else ""}"))}
           |GROUP BY $keys
           |ORDER BY calls DESC, $keys
           |LIMIT $limit""".stripMargin
      QueryHelpers.runSelect(ctx.pool, sql, vals).map { rows =>
        val items = rows.map(rowJson)
        QueryResult(AnalyticsShape.Table, Json.obj("items" -> JsArray(items)), JsArray(items))
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

    // the calls of a period grouped by the session their caller named, most recently active first
    val SessionsTable = lq("cloudapim_llm_sessions_table", "LLM sessions", "Calls grouped by the session id their caller sent (`x-session-id` header, `session_id` or `metadata.session_id` body field): one row per conversation or agent run.", AnalyticsShape.Table, "table", params = Seq(
      QueryParam("limit", "int", JsNumber(50), "Number of sessions (max 200)")
    )) { ctx =>
      given ExecutionContext = ctx.ec
      val (where, vals) = FilterSql.whereClause(ctx.filters)
      val limit = (ctx.params \ "limit").asOpt[Int].getOrElse(50).max(1).min(200)
      val sql =
        s"""SELECT session_id, COUNT(*) AS calls, COALESCE(SUM(total_tokens), 0) AS tokens, SUM(total_cost) AS spend_usd,
           |  COUNT(*) FILTER (WHERE err) AS errors, COUNT(DISTINCT model) AS models, mode() WITHIN GROUP (ORDER BY model) AS primary_model,
           |  MAX($UserKey) AS user_email, MAX(end_user) AS end_user, MIN(ts) AS first_ts, MAX(ts) AS last_ts
           |FROM ${t(ctx.settings)}${and(where, real(ctx, "session_id IS NOT NULL"))}
           |GROUP BY session_id
           |ORDER BY MAX(ts) DESC
           |LIMIT $limit""".stripMargin
      QueryHelpers.runSelect(ctx.pool, sql, vals).map { rows =>
        val items = rows.map(rowJson)
        QueryResult(AnalyticsShape.Table, Json.obj("items" -> JsArray(items)), JsArray(items))
      }
    }

    // every model call of one request, oldest first: guardrail calls, failed attempts before a fallback, and the
    // re-reports of load balancers and routers (flagged `delegated`) — the only query that reads those
    val RequestCalls = q("cloudapim_llm_request_calls", "LLM calls of a request", "Every model call made while serving one request, re-reports of load balancers, routers and fallbacks included, oldest first.", AnalyticsShape.Table, "table", params = Seq(
      QueryParam("request_id", "string", JsNull, "Id of the request (the `request_id` column of the calls log)")
    )) { ctx =>
      given ExecutionContext = ctx.ec
      val (where, vals) = FilterSql.whereClause(ctx.filters)
      param(ctx, "request_id") match {
        case None => Future.successful(QueryResult(AnalyticsShape.Table, Json.obj("items" -> JsArray())))
        case Some(requestId) =>
          val sql = s"SELECT ${(LogColumns :+ "delegated").mkString(", ")} FROM ${t(ctx.settings)}${and(where, s"request_id = $$${vals.size + 1}")} ORDER BY ts ASC LIMIT 50"
          QueryHelpers.runSelect(ctx.pool, sql, vals :+ requestId).map { rows =>
            val items = rows.map(rowJson)
            QueryResult(AnalyticsShape.Table, Json.obj("items" -> JsArray(items)), JsArray(items))
          }
      }
    }

    // ---- explore ----------------------------------------------------------------------------------

    /** A metric of the explorer. `additive` ones can be shown as a share of the total. */
    final case class ExploreMetric(sql: String, asDouble: Boolean, additive: Boolean)

    val ExploreMetrics: Map[String, ExploreMetric] = Map(
      "requests"          -> ExploreMetric("COUNT(*)", asDouble = false, additive = true),
      "spend"             -> ExploreMetric("COALESCE(SUM(total_cost), 0)", asDouble = true, additive = true),
      "input_spend"       -> ExploreMetric("COALESCE(SUM(input_cost), 0)", asDouble = true, additive = true),
      "output_spend"      -> ExploreMetric("COALESCE(SUM(output_cost), 0)", asDouble = true, additive = true),
      "reasoning_spend"   -> ExploreMetric("COALESCE(SUM(reasoning_cost), 0)", asDouble = true, additive = true),
      "tokens"            -> ExploreMetric("COALESCE(SUM(total_tokens), 0)", asDouble = false, additive = true),
      "input_tokens"      -> ExploreMetric("COALESCE(SUM(input_tokens), 0)", asDouble = false, additive = true),
      "output_tokens"     -> ExploreMetric("COALESCE(SUM(output_tokens), 0)", asDouble = false, additive = true),
      "reasoning_tokens"  -> ExploreMetric("COALESCE(SUM(reasoning_tokens), 0)", asDouble = false, additive = true),
      "blended_cost"      -> ExploreMetric("COALESCE(SUM(total_cost) * 1000000.0 / NULLIF(SUM(total_tokens), 0), 0)", asDouble = true, additive = false),
      "avg_latency"       -> ExploreMetric("COALESCE(AVG(duration_ms), 0)", asDouble = true, additive = false),
      "p50_latency"       -> ExploreMetric("COALESCE(percentile_cont(0.5) WITHIN GROUP (ORDER BY duration_ms), 0)", asDouble = true, additive = false),
      "p90_latency"       -> ExploreMetric("COALESCE(percentile_cont(0.9) WITHIN GROUP (ORDER BY duration_ms), 0)", asDouble = true, additive = false),
      "p95_latency"       -> ExploreMetric("COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms), 0)", asDouble = true, additive = false),
      "p99_latency"       -> ExploreMetric("COALESCE(percentile_cont(0.99) WITHIN GROUP (ORDER BY duration_ms), 0)", asDouble = true, additive = false),
      "avg_ttft"          -> ExploreMetric("COALESCE(AVG(ttft_ms), 0)", asDouble = true, additive = false),
      "p50_ttft"          -> ExploreMetric("COALESCE(percentile_cont(0.5) WITHIN GROUP (ORDER BY ttft_ms), 0)", asDouble = true, additive = false),
      "p95_ttft"          -> ExploreMetric("COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY ttft_ms), 0)", asDouble = true, additive = false),
      "speed"             -> ExploreMetric("COALESCE(SUM(output_tokens + reasoning_tokens) * 1000.0 / NULLIF(SUM(CASE WHEN duration_ms > COALESCE(ttft_ms, 0) THEN duration_ms - COALESCE(ttft_ms, 0) END), 0), 0)", asDouble = true, additive = false),
      "cache_hits"        -> ExploreMetric("COUNT(*) FILTER (WHERE cache_status = 'hit')", asDouble = false, additive = true),
      "cache_hit_rate"    -> ExploreMetric("COALESCE(AVG(CASE WHEN cache_status = 'hit' THEN 1.0 ELSE 0.0 END) FILTER (WHERE cache_status IS NOT NULL), 0)", asDouble = true, additive = false),
      "errors"            -> ExploreMetric("COUNT(*) FILTER (WHERE err)", asDouble = false, additive = true),
      "error_rate"        -> ExploreMetric(ErrorRate, asDouble = true, additive = false),
      "guardrail_denials" -> ExploreMetric("COUNT(*) FILTER (WHERE error_kind = 'guardrail_denied')", asDouble = false, additive = true),
      "truncated_rate"    -> ExploreMetric("COALESCE(AVG(CASE WHEN finish_reason = 'length' THEN 1.0 ELSE 0.0 END) FILTER (WHERE finish_reason IS NOT NULL), 0)", asDouble = true, additive = false),
      "gco2eq"            -> ExploreMetric("COALESCE(SUM(gwp_kgco2eq), 0) * 1000", asDouble = true, additive = true),
      "energy_wh"         -> ExploreMetric("COALESCE(SUM(energy_kwh), 0) * 1000", asDouble = true, additive = true),
      "users"             -> ExploreMetric(s"COUNT(DISTINCT $UserKey)", asDouble = false, additive = false),
      "sessions"          -> ExploreMetric("COUNT(DISTINCT session_id)", asDouble = false, additive = false),
    )

    val ExploreDimensions: Map[String, String] = Map(
      "model"         -> "model",
      "provider"      -> Provider,
      "provider_kind" -> "provider_kind",
      "apikey"        -> ApikeyKey,
      "user"          -> UserKey,
      "key_owner"     -> "apikey_owner",
      "end_user"      -> "end_user",
      "modality"      -> "modality",
      "operation"     -> "consumed_using",
      "streamed"      -> "CASE WHEN streaming THEN 'streaming' ELSE 'blocking' END",
      "finish_reason" -> "finish_reason",
      "cache"         -> "cache_status",
      "status"        -> "CASE WHEN err THEN COALESCE(error_kind, 'error') WHEN cache_status = 'hit' THEN 'cached' ELSE 'ok' END",
      "error_kind"    -> "error_kind",
      "error_message" -> "error_message",
      "session"       -> "session_id",
      "cost_source"   -> "cost_source",
      "route"         -> "COALESCE(route_name, route_id)",
    )

    // time buckets aligned on UTC, gaps included
    private val ExploreRollups: Map[String, (String, java.time.temporal.ChronoUnit, String)] = Map(
      "hour"  -> ("to_timestamp(floor(extract(epoch from ts) / 3600) * 3600)", java.time.temporal.ChronoUnit.HOURS, "1h"),
      "day"   -> ("to_timestamp(floor(extract(epoch from ts) / 86400) * 86400)", java.time.temporal.ChronoUnit.DAYS, "1d"),
      "week"  -> ("(date_trunc('week', ts AT TIME ZONE 'UTC') AT TIME ZONE 'UTC')", java.time.temporal.ChronoUnit.WEEKS, "1d"),
      "month" -> ("(date_trunc('month', ts AT TIME ZONE 'UTC') AT TIME ZONE 'UTC')", java.time.temporal.ChronoUnit.MONTHS, "1d"),
    )

    private def bucketStart(instant: java.time.Instant, unit: java.time.temporal.ChronoUnit): java.time.ZonedDateTime = {
      val utc = instant.atZone(java.time.ZoneOffset.UTC)
      unit match {
        case java.time.temporal.ChronoUnit.WEEKS  => utc.truncatedTo(java.time.temporal.ChronoUnit.DAYS).`with`(java.time.DayOfWeek.MONDAY)
        case java.time.temporal.ChronoUnit.MONTHS => utc.truncatedTo(java.time.temporal.ChronoUnit.DAYS).withDayOfMonth(1)
        case other                                => utc.truncatedTo(other)
      }
    }

    private val ExploreStatus: Map[String, String] = Map(
      "ok"        -> "err = false AND cache_status IS DISTINCT FROM 'hit'",
      "error"     -> "err = true",
      "cached"    -> "cache_status = 'hit'",
      "guardrail" -> "error_kind = 'guardrail_denied'",
    )

    /**
     * The explorer: one metric by one dimension, as a ranking (optionally split by a second dimension and
     * compared to the previous period) or over time. Metrics, dimensions and rollups are picked from fixed
     * lists, so nothing the caller sends is ever inlined in the SQL.
     */
    val Explore = lq("cloudapim_llm_explore", "LLM explorer", "Any metric of the calls by any dimension: a ranking, split by a second dimension, compared to the previous period, or over time.", AnalyticsShape.Table, "table", params = Seq(
      QueryParam("metric", "string", JsString("spend"), s"One of ${ExploreMetrics.keys.toSeq.sorted.mkString(", ")}"),
      QueryParam("group_by", "string", JsString("model"), s"One of ${ExploreDimensions.keys.toSeq.sorted.mkString(", ")}"),
      QueryParam("subgroup", "string", JsNull, "A second dimension, rankings only"),
      QueryParam("rollup", "string", JsString("total"), "total, hour, day, week or month"),
      QueryParam("top_n", "int", JsNumber(10), "Number of groups (max 50)"),
      QueryParam("status", "string", JsNull, "ok, error, cached or guardrail"),
      QueryParam("compare", "boolean", JsBoolean(false), "Rankings only: the value of each group over the previous period too")
    )) { ctx =>
      given ExecutionContext = ctx.ec
      val metric    = param(ctx, "metric").flatMap(ExploreMetrics.get).getOrElse(ExploreMetrics("spend"))
      val dimension = param(ctx, "group_by").flatMap(ExploreDimensions.get).getOrElse("model")
      val subgroup  = param(ctx, "subgroup").flatMap(ExploreDimensions.get).filter(_ != dimension)
      val rollup    = param(ctx, "rollup").flatMap(ExploreRollups.get)
      val n         = (ctx.params \ "top_n").asOpt[Int].getOrElse(10).max(1).min(50)
      val status    = param(ctx, "status").flatMap(ExploreStatus.get).getOrElse("")
      val scope     = (filters: Filters) => {
        val (where, vals) = FilterSql.whereClause(filters)
        (and(where, real(ctx, Seq(s"$dimension IS NOT NULL", status).filter(_.nonEmpty).mkString(" AND "))), vals)
      }
      val number    = (r: io.vertx.sqlclient.Row, i: Int) => if (metric.asDouble) JsNumber(BigDecimal(QueryHelpers.safeDouble(r, i))) else JsNumber(QueryHelpers.safeLong(r, i))
      val (where, vals) = scope(ctx.filters)
      val top       = s"SELECT $dimension AS k, ${metric.sql} AS v FROM ${t(ctx.settings)}$where GROUP BY 1 ORDER BY 2 DESC NULLS LAST LIMIT $n"
      rollup match {
        case Some((trunc, unit, bucketName)) =>
          val sql = s"""WITH top AS ($top)
                       |SELECT $trunc AS bucket, $dimension AS k, ${metric.sql} AS v
                       |FROM ${t(ctx.settings)}${and(where, s"$dimension IN (SELECT k FROM top)")}
                       |GROUP BY 1, 2""".stripMargin
          QueryHelpers.runSelect(ctx.pool, sql, vals).map { rows =>
            val cells   = rows.map(r => (r.getOffsetDateTime(0).toInstant.toEpochMilli, QueryHelpers.optString(r, 1).getOrElse("(unknown)")) -> number(r, 2)).toMap
            val end     = ctx.filters.to
            val buckets = Iterator.iterate(bucketStart(ctx.filters.from, unit))(_.plus(1, unit)).takeWhile(_.toInstant.isBefore(end)).map(_.toInstant.toEpochMilli).toSeq
            val keys    = cells.toSeq.groupMapReduce(_._1._2)(_._2.value)(_ + _).toSeq.sortBy(-_._2).map(_._1)
            val series  = keys.map { k =>
              Json.obj("name" -> seriesName(k), "points" -> JsArray(buckets.map(b => Json.obj("ts" -> b, "value" -> cells.getOrElse((b, k), JsNumber(0))))))
            }
            QueryResult(AnalyticsShape.Timeseries, Json.obj("mode" -> "series", "bucket" -> bucketName, "series" -> JsArray(series)), JsArray())
          }
        case None =>
          val (allWhere, allVals) = {
            val (w, v) = FilterSql.whereClause(ctx.filters)
            (and(w, real(ctx, status)), v)
          }
          val totalSql = s"SELECT ${metric.sql} AS v, COUNT(DISTINCT $dimension) AS groups FROM ${t(ctx.settings)}$allWhere"
          val itemsSql = subgroup match {
            case None      => s"$top"
            case Some(sub) =>
              s"""WITH top AS ($top)
                 |SELECT split.k, top.v, split.s, split.v
                 |FROM (
                 |  SELECT $dimension AS k, COALESCE(($sub)::text, '(none)') AS s, ${metric.sql} AS v
                 |  FROM ${t(ctx.settings)}${and(where, s"$dimension IN (SELECT k FROM top)")}
                 |  GROUP BY 1, 2
                 |) split JOIN top ON top.k = split.k
                 |ORDER BY top.v DESC NULLS LAST, split.v DESC NULLS LAST""".stripMargin
          }
          val compare = (ctx.params \ "compare").asOpt[Boolean].contains(true)
          for {
            total <- QueryHelpers.runSelect(ctx.pool, totalSql, allVals)
            rows  <- QueryHelpers.runSelect(ctx.pool, itemsSql, vals)
            keys   = rows.map(r => QueryHelpers.optString(r, 0).getOrElse("(unknown)")).distinct
            previous <- if (!compare || keys.isEmpty) Future.successful(Map.empty[String, JsNumber])
                        else {
                          val span          = java.time.Duration.between(ctx.filters.from, ctx.filters.to)
                          val (pWhere, pVals) = scope(ctx.filters.copy(from = ctx.filters.from.minus(span), to = ctx.filters.from))
                          val sql           = s"SELECT $dimension AS k, ${metric.sql} AS v FROM ${t(ctx.settings)}${and(pWhere, s"$dimension = ANY($$${pVals.size + 1})")} GROUP BY 1"
                          QueryHelpers.runSelect(ctx.pool, sql, pVals :+ keys.toArray).map(_.map(r => QueryHelpers.optString(r, 0).getOrElse("") -> number(r, 1)).toMap)
                        }
          } yield {
            // a group absent from the previous period was at zero then
            val previousOf = (k: String) => if (compare) previous.getOrElse(k, JsNumber(0)) else JsNull
            val items = subgroup match {
              case None    => rows.map { r =>
                val k = QueryHelpers.optString(r, 0).getOrElse("(unknown)")
                Json.obj("key" -> k, "value" -> number(r, 1), "previous" -> previousOf(k))
              }
              case Some(_) => rows.groupBy(r => QueryHelpers.optString(r, 0).getOrElse("(unknown)")).toSeq
                .sortBy { case (k, _) => keys.indexOf(k) }
                .map { case (k, rs) =>
                  Json.obj(
                    "key" -> k,
                    "value" -> number(rs.head, 1),
                    "previous" -> previousOf(k),
                    "subgroups" -> JsArray(rs.map(r => Json.obj("key" -> QueryHelpers.optString(r, 2).getOrElse("(none)"), "value" -> number(r, 3))))
                  )
                }
            }
            val summary = total.headOption
            QueryResult(AnalyticsShape.Table, Json.obj(
              "mode" -> "total",
              "additive" -> metric.additive,
              "total" -> summary.map(r => number(r, 0): JsValue).getOrElse(JsNull),
              "groups" -> summary.map(r => QueryHelpers.safeLong(r, 1)).getOrElse(0L),
              "items" -> JsArray(items)
            ), JsArray(items))
          }
      }
    }

    lazy val all: Seq[AnalyticsQuery] = Seq(
      CallsLog, CallDetail, HealthTable, RequestCalls, Explore, SessionsTable, EndUsersTable, TtftP50, TtftP95, TtftPctTs, TtftByModel, ByFinishReason, TruncatedRate, CallsByApikeyTs, TokensByApikeyTs, CostByApikeyTs, CallsByUserTs, TokensByUserTs, CostByUserTs,
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

    /**
     * The two sides of MCP answer opposite questions and must not be added up: `server` is what the gateway
     * served to MCP clients, `client` what a model called through a connector. No side param = all of them,
     * which is what the cross-side dashboards want.
     */
    private def sided(ctx: QueryContext, extra: String = ""): String = {
      val side = (ctx.params \ "side").asOpt[String].map(_.trim.toLowerCase).filter(SidePattern.matches)
      val user = (ctx.params \ "user").asOpt[String].map(_.trim).filter(_.nonEmpty).map {
        case u if UserPattern.matches(u) => s"$UserKey = '$u'"
        case _                           => "1 = 0"
      }
      val clauses = side.map(s => s"side = '$s'").toSeq ++ user ++ Option(extra).filter(_.nonEmpty).map(e => s"($e)")
      clauses.mkString(" AND ")
    }

    private val SidePattern = "^(server|client|fetch)$".r
    private val UserPattern = "^[A-Za-z0-9._%+@-]{1,254}$".r

    /** Who an MCP call counts for, the same way a model call counts, see [[Llm.UserKey]]. */
    private val UserKey = "COALESCE(user_email, apikey_owner)"

    private val SideParam = QueryParam("side", "string", JsNull, "Only count one side of MCP: `server` (requests Otoroshi served), `client` (calls made through a connector) or `fetch` (resource fetches)")
    private val UserParam = QueryParam("user", "string", JsNull, "Only count the calls of one user (email): the calls they made and the calls of the API keys they own")

    // every mcp query can be narrowed to one side — "the tools we serve", "what the models consume" — and
    // to one user
    private def mq(
        id: String,
        name: String,
        description: String,
        shape: AnalyticsShape,
        widget: String,
        compare: Boolean = false,
        params: Seq[QueryParam] = Seq.empty
    )(run: QueryContext => Future[QueryResult]): CatalogQuery =
      q(id, name, description, shape, widget, compare, params :+ SideParam :+ UserParam)(run)

    private def metric(id: String, name: String, description: String, expr: String, extra: String = "", asDouble: Boolean = false) =
      mq(s"cloudapim_mcp_$id", name, description, AnalyticsShape.Scalar, "metric", compare = true) { ctx =>
        scalar(t(ctx.settings), expr, name, sided(ctx, extra), asDouble)(ctx)
      }

    private def pieOf(id: String, name: String, description: String, key: String, extra: String = "", widget: String = "donut") =
      mq(s"cloudapim_mcp_$id", name, description, AnalyticsShape.Pie, widget) { ctx =>
        pie(t(ctx.settings), key, name, extra = sided(ctx, extra))(ctx)
      }

    private def top(id: String, name: String, description: String, key: String, label: Option[String] = None, value: String = "COUNT(*)", extra: String = "", asDouble: Boolean = false) =
      mq(s"cloudapim_mcp_$id", name, description, AnalyticsShape.TopN, "bar", params = Seq(TopNParam)) { ctx =>
        topN(t(ctx.settings), key, label, value, sided(ctx, s"$key IS NOT NULL${if (extra.isEmpty) "" else s" AND $extra"}"), asDouble)(ctx)
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
    val CallsOverTime  = mq("cloudapim_mcp_calls_over_time", "MCP calls over time", "Successful and failed MCP requests per bucket.", AnalyticsShape.Timeseries, "area") { ctx =>
      series(t(ctx.settings), Seq("success" -> s"COUNT(*) FILTER (WHERE NOT $Failed)", "failure" -> s"COUNT(*) FILTER (WHERE $Failed)"), sided(ctx))(ctx)
    }
    val ErrorRateTs    = mq("cloudapim_mcp_error_rate_over_time", "MCP failure rate over time", "Share of failed MCP requests per bucket.", AnalyticsShape.Timeseries, "line", compare = true) { ctx =>
      series(t(ctx.settings), Seq("failure_rate" -> FailRate), sided(ctx), asDouble = true)(ctx)
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
    val TopUsers       = top("top_users", "Top MCP users", "Users by MCP requests, the requests of the API keys they own included.", UserKey)
    val TopErrors      = top("top_errors", "Top MCP errors", "The errors seen most often.", "error_message", extra = "err = true")
    val FailingFetches = top("failing_resource_fetches", "Failing resource fetches", "Resources whose fetch failed or was refused.", "target", extra = "side = 'fetch' AND err = true")
    val CallsByToolTs  = mq("cloudapim_mcp_calls_by_tool_over_time", "Tool calls by tool over time", "One series per tool, for the most called tools.", AnalyticsShape.Timeseries, "line", params = Seq(QueryParam("top_n", "int", JsNumber(5), "Number of series"))) { ctx =>
      seriesByKey(t(ctx.settings), "tool", extra = sided(ctx, ToolCalls))(ctx)
    }
    val LatencyPctTs   = mq("cloudapim_mcp_latency_percentiles_over_time", "MCP latency percentiles", "p50, p95 and p99 of the MCP request duration per bucket.", AnalyticsShape.Timeseries, "line") { ctx =>
      series(t(ctx.settings), Seq(
        "p50" -> "COALESCE(percentile_cont(0.5) WITHIN GROUP (ORDER BY duration_ms), 0)",
        "p95" -> "COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms), 0)",
        "p99" -> "COALESCE(percentile_cont(0.99) WITHIN GROUP (ORDER BY duration_ms), 0)"
      ), sided(ctx, HasTime), asDouble = true)(ctx)
    }
    val LatencyHeatmap = mq("cloudapim_mcp_latency_heatmap", "MCP latency heatmap", "MCP request durations over time, by band.", AnalyticsShape.Heatmap, "heatmap") { ctx =>
      heatmap(t(ctx.settings), Seq(
        "<50ms"     -> "duration_ms < 50",
        "50-100ms"  -> "duration_ms >= 50 AND duration_ms < 100",
        "100-250ms" -> "duration_ms >= 100 AND duration_ms < 250",
        "250-500ms" -> "duration_ms >= 250 AND duration_ms < 500",
        "0.5-1s"    -> "duration_ms >= 500 AND duration_ms < 1000",
        "1-3s"      -> "duration_ms >= 1000 AND duration_ms < 3000",
        ">=3s"      -> "duration_ms >= 3000"
      ), sided(ctx, HasTime))(ctx)
    }
    val ToolsTable     = mq("cloudapim_mcp_tools_table", "Tools", "Calls, failures and latency per tool.", AnalyticsShape.Table, "table", params = Seq(TopNParam)) { ctx =>
      table(t(ctx.settings), "tool", "tool", Seq(
        ("calls", "COUNT(*)", false),
        ("failures", s"COUNT(*) FILTER (WHERE $Failed)", false),
        ("failure_rate_pct", s"ROUND(($FailRate * 100)::numeric, 2)", true),
        ("avg_ms", "ROUND(COALESCE(AVG(duration_ms), 0))", false),
        ("p95_ms", "ROUND(COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms), 0)::numeric)", false)
      ), sided(ctx, s"$ToolCalls AND tool IS NOT NULL"))(ctx)
    }

    // ---- who ------------------------------------------------------------------------------------------

    val DistinctUsers   = metric("distinct_users", "MCP users", "Distinct users who made an MCP request, directly or through an API key they own.", s"COUNT(DISTINCT $UserKey)")
    val DistinctApikeys = metric("distinct_apikeys", "MCP API keys", "Distinct API keys that made an MCP request.", "COUNT(DISTINCT apikey_id)")
    val DistinctTools   = metric("distinct_tools", "Tools used", "Distinct tools actually called.", "COUNT(DISTINCT tool)", ToolCalls)
    val CallsByUserTs   = mq("cloudapim_mcp_calls_by_user_over_time", "MCP calls by user over time", "One series per user, for the most active users of the period.", AnalyticsShape.Timeseries, "line", params = Seq(QueryParam("top_n", "int", JsNumber(5), "Number of series"))) { ctx =>
      seriesByKey(t(ctx.settings), UserKey, extra = sided(ctx))(ctx)
    }
    private def consumers(id: String, name: String, description: String, key: String, keyName: String, label: String) =
      mq(s"cloudapim_mcp_$id", name, description, AnalyticsShape.Table, "table", params = Seq(TopNParam)) { ctx =>
        table(t(ctx.settings), label, keyName, Seq(
          ("calls", "COUNT(*)", false),
          ("tool_calls", s"COUNT(*) FILTER (WHERE $ToolCalls)", false),
          ("tools", "COUNT(DISTINCT tool)", false),
          ("failures", s"COUNT(*) FILTER (WHERE $Failed)", false),
          ("avg_ms", "ROUND(COALESCE(AVG(duration_ms), 0))", false)
        ), sided(ctx, s"$key IS NOT NULL"))(ctx)
      }
    val UsersTable   = consumers("users_table", "MCP users", "Requests, tools and failures per user, the calls of the API keys they own included.", UserKey, "user", UserKey)
    val ApikeysTable = consumers("apikeys_table", "MCP API keys", "Requests, tools and failures per API key.", "apikey_id", "apikey", "COALESCE(apikey_name, apikey_id)")
    val RecentCalls  = mq("cloudapim_mcp_recent_calls", "Recent MCP calls", "The latest MCP requests: who, which method, which tool, how long.", AnalyticsShape.Table, "table", params = Seq(QueryParam("top_n", "int", JsNumber(25), "Number of calls"))) { ctx =>
      latest(t(ctx.settings), Seq(
        "time"     -> "to_char(ts AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS')",
        "side"     -> "COALESCE(side, '—')",
        "consumer" -> "COALESCE(user_email, apikey_name, apikey_id, '—')",
        "owner"    -> "COALESCE(apikey_owner, '—')",
        "method"   -> "COALESCE(method, '—')",
        "tool"     -> "COALESCE(tool, target, '—')",
        "ms"       -> "COALESCE(duration_ms::text, '—')",
        "status"   -> s"CASE WHEN tool_error THEN 'tool error' WHEN err THEN COALESCE(error_message, 'error') ELSE 'ok' END"
      ), sided(ctx))(ctx)
    }

    lazy val all: Seq[AnalyticsQuery] = Seq(
      CallsTotal, ToolCallsTotal, ErrorsTotal, ErrorRate, LatencyP95, CallsOverTime, ErrorRateTs, ByMethod, BySide,
      ByTransport, ByProtocol, TopTools, FailingTools, SlowestTools, TopServers, TopConnectors, FailingConnectors,
      TopApikeys, TopUsers, TopErrors, FailingFetches, CallsByToolTs, LatencyPctTs, LatencyHeatmap, ToolsTable,
      DistinctUsers, DistinctApikeys, DistinctTools, CallsByUserTs, UsersTable, ApikeysTable, RecentCalls
    )
  }

  lazy val all: Seq[AnalyticsQuery] = Llm.all ++ Budgets.all ++ Alerts.all ++ Mcp.all
}
