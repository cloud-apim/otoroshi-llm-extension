package com.cloud.apim.otoroshi.extensions.aigateway.analytics

import com.cloud.apim.otoroshi.extensions.aigateway.analytics.DashboardSeeding.widget
import play.api.libs.json.Json

/**
 * The dashboards installed with the extension.
 *
 * The widget wizard exposes every query anyway, but a catalogue is not a console: it asks the
 * operator to know what to look at before they have ever looked. Each dashboard answers one
 * question someone actually asks of an AI gateway — what is it costing, is it up, what is it
 * emitting, who is using it, what are the agents calling — and is an ordinary user dashboard,
 * free to rearrange, extend or delete.
 */
object AiGatewayDashboards {

  private val Stacked = Json.obj("stacked" -> true)
  private val Top10   = Json.obj("top_n" -> 10)

  private def m(id: String, title: String, query: String, format: String = "count") =
    widget(id, title, query, "metric", 1, 1, Some(format))

  val Overview = DashboardSpec(
    "cloud-apim-ai-gateway-overview",
    "AI Gateway - Overview",
    "Calls, spend, tokens and the models behind them, at a glance",
    Seq(
      m("calls", "LLM calls", "cloudapim_llm_requests_total"),
      m("error-rate", "Error rate", "cloudapim_llm_error_rate", "percent"),
      widget("spend", "Spend ($)", "cloudapim_llm_cost_total", "metric", 1, 1),
      m("tokens", "Tokens", "cloudapim_llm_tokens_total"),
      widget("calls-ts", "LLM calls over time", "cloudapim_llm_requests_over_time", "area", 4, 2, options = Stacked),
      widget("vendors", "Calls by provider type", "cloudapim_llm_by_provider_kind", "donut", 2, 2),
      widget("models", "Top models", "cloudapim_llm_top_models", "bar", 2, 2, params = Top10),
      widget("spend-ts", "Spend over time ($)", "cloudapim_llm_cost_over_time", "area", 2, 2, options = Stacked),
      widget("tokens-ts", "Tokens over time", "cloudapim_llm_tokens_over_time", "area", 2, 2, options = Stacked),
      m("p95", "p95 latency", "cloudapim_llm_latency_p95", "ms"),
      m("cache", "Cache hit rate", "cloudapim_llm_cache_hit_rate", "percent"),
      widget("gwp", "Emissions (gCO2eq)", "cloudapim_llm_gwp_total", "metric", 1, 1),
      m("mcp", "MCP calls", "cloudapim_mcp_calls_total"),
      widget("modalities", "Calls by modality", "cloudapim_llm_by_modality", "pie", 2, 2),
      widget("apikeys", "Top API keys", "cloudapim_llm_top_apikeys", "bar", 2, 2, params = Top10),
      m("users", "Users", "cloudapim_llm_distinct_users"),
      m("keys", "API keys", "cloudapim_llm_distinct_apikeys"),
      m("model-count", "Models", "cloudapim_llm_distinct_models"),
      m("provider-count", "Providers", "cloudapim_llm_distinct_providers"),
      widget("modalities-ts", "Calls by modality over time", "cloudapim_llm_requests_by_modality_over_time", "area", 4, 2, options = Stacked),
      widget("activity", "Activity by weekday and hour (UTC)", "cloudapim_llm_activity_heatmap", "heatmap", 4, 3),
      widget("recent", "Recent LLM calls", "cloudapim_llm_recent_calls", "table", 4, 3)
    )
  )

  val Costs = DashboardSpec(
    "cloud-apim-ai-gateway-costs",
    "AI Gateway - Costs",
    "What the models cost, who spends it, and what each token is really paid",
    Seq(
      widget("spend", "Spend ($)", "cloudapim_llm_cost_total", "metric", 1, 1),
      widget("projection", "Projected monthly spend ($)", "cloudapim_llm_cost_monthly_projection", "metric", 1, 1),
      widget("per-call", "Cost per call ($)", "cloudapim_llm_cost_per_request", "metric", 1, 1),
      m("unpriced", "Unpriced calls", "cloudapim_llm_unpriced_requests"),
      widget("input", "Input spend ($)", "cloudapim_llm_input_cost_total", "metric", 1, 1),
      widget("output", "Output spend ($)", "cloudapim_llm_output_cost_total", "metric", 1, 1),
      widget("reasoning", "Reasoning spend ($)", "cloudapim_llm_reasoning_cost_total", "metric", 1, 1),
      widget("per-1k", "Cost per 1k tokens ($)", "cloudapim_llm_cost_per_1k_tokens", "metric", 1, 1),
      widget("spend-ts", "Spend over time ($)", "cloudapim_llm_cost_over_time", "area", 2, 2, options = Stacked),
      widget("burn", "Cumulative spend ($)", "cloudapim_llm_cumulative_cost_over_time", "area", 2, 2),
      widget("spend-models-ts", "Spend by model over time ($)", "cloudapim_llm_cost_by_model_over_time", "line", 4, 2),
      widget("by-model", "Spend by model ($)", "cloudapim_llm_cost_by_model", "bar", 2, 2, params = Top10),
      widget("by-provider", "Spend by provider ($)", "cloudapim_llm_cost_by_provider", "donut", 2, 2),
      widget("by-apikey", "Spend by API key ($)", "cloudapim_llm_cost_by_apikey", "bar", 2, 2, params = Top10),
      widget("by-user", "Spend by user ($)", "cloudapim_llm_cost_by_user", "bar", 2, 2, params = Top10),
      widget("per-million", "Cost per million tokens ($)", "cloudapim_llm_cost_per_million_tokens_by_model", "bar", 2, 2, params = Top10),
      widget("by-route", "Spend by route ($)", "cloudapim_llm_cost_by_route", "bar", 2, 2, params = Top10),
      widget("models", "Models", "cloudapim_llm_models_table", "table", 4, 3, params = Json.obj("top_n" -> 25))
    )
  )

  val Tokens = DashboardSpec(
    "cloud-apim-ai-gateway-tokens",
    "AI Gateway - Tokens & cache",
    "Where the tokens go, how heavy the prompts are, and what the cache saves",
    Seq(
      m("total", "Tokens", "cloudapim_llm_tokens_total"),
      m("input", "Input tokens", "cloudapim_llm_input_tokens_total"),
      m("output", "Output tokens", "cloudapim_llm_output_tokens_total"),
      m("reasoning", "Reasoning tokens", "cloudapim_llm_reasoning_tokens_total"),
      widget("tokens-ts", "Tokens over time", "cloudapim_llm_tokens_over_time", "area", 4, 2, options = Stacked),
      widget("models-ts", "Tokens by model over time", "cloudapim_llm_tokens_by_model_over_time", "line", 4, 2),
      widget("by-model", "Tokens by model", "cloudapim_llm_tokens_by_model", "bar", 2, 2, params = Top10),
      widget("by-provider", "Tokens by provider", "cloudapim_llm_tokens_by_provider", "donut", 2, 2),
      widget("by-apikey", "Tokens by API key", "cloudapim_llm_tokens_by_apikey", "bar", 2, 2, params = Top10),
      widget("by-user", "Tokens by user", "cloudapim_llm_tokens_by_user", "bar", 2, 2, params = Top10),
      widget("per-call-model", "Tokens per call by model", "cloudapim_llm_tokens_per_request_by_model", "bar", 2, 2, params = Top10),
      widget("ratio", "Output / input ratio by model", "cloudapim_llm_output_ratio_by_model", "bar", 2, 2, params = Top10),
      m("cache-rate", "Cache hit rate", "cloudapim_llm_cache_hit_rate", "percent"),
      m("cache-hits", "Cache hits", "cloudapim_llm_cache_hits_total"),
      m("per-call", "Tokens per call", "cloudapim_llm_tokens_per_request"),
      widget("prompt-ratio", "Prompt / completion ratio", "cloudapim_llm_prompt_completion_ratio", "metric", 1, 1),
      widget("cache-ts", "Cache hits vs misses", "cloudapim_llm_cache_over_time", "area", 2, 2, options = Stacked),
      widget("streaming", "Streaming vs blocking", "cloudapim_llm_streaming_ratio", "donut", 2, 2),
      widget("by-modality", "Tokens by modality", "cloudapim_llm_tokens_by_modality", "donut", 2, 2),
      widget("by-route", "Tokens by route", "cloudapim_llm_tokens_by_route", "bar", 2, 2, params = Top10)
    )
  )

  val Budgets = DashboardSpec(
    "cloud-apim-ai-gateway-budgets",
    "AI Gateway - Budgets",
    "Where every budget stands, which ones are under pressure, and what is spent against them",
    Seq(
      m("exceeded", "Budget exceeded", "cloudapim_llm_budget_exceeded_total"),
      m("warnings", "Budget warnings", "cloudapim_llm_budget_almost_exceeded_total"),
      widget("spend", "Spend ($)", "cloudapim_llm_cost_total", "metric", 1, 1),
      widget("projection", "Projected monthly spend ($)", "cloudapim_llm_cost_monthly_projection", "metric", 1, 1),
      widget("budgets", "Budgets (current cycle)", "cloudapim_llm_budgets_consumption", "table", 4, 2),
      widget("usage", "Budget usage (current cycle)", "cloudapim_llm_budgets_usage", "bar", 2, 2, Some("percent"), Top10),
      widget("spend-by-budget", "Spend by budget ($)", "cloudapim_llm_cost_by_budget", "bar", 2, 2, params = Top10),
      widget("spend-budget-ts", "Spend by budget over time ($)", "cloudapim_llm_cost_by_budget_over_time", "line", 4, 2),
      widget("alerts-ts", "Budget alerts over time", "cloudapim_llm_budget_alerts_over_time", "area", 4, 2, options = Stacked),
      widget("tokens-by-budget", "Tokens by budget", "cloudapim_llm_tokens_by_budget", "bar", 2, 2, params = Top10),
      widget("alerts-by-budget", "Budget alerts by budget", "cloudapim_llm_budget_alerts_by_budget", "bar", 2, 2, params = Top10)
    )
  )

  val Ecology = DashboardSpec(
    "cloud-apim-ai-gateway-ecology",
    "AI Gateway - Ecological impact",
    "Energy, emissions, water and minerals behind the calls, and the models that weigh the most",
    Seq(
      widget("energy", "Energy (Wh)", "cloudapim_llm_energy_total", "metric", 1, 1),
      widget("gwp", "Emissions (gCO2eq)", "cloudapim_llm_gwp_total", "metric", 1, 1),
      widget("water", "Water (L)", "cloudapim_llm_wcf_total", "metric", 1, 1),
      widget("gwp-call", "Emissions per call (gCO2eq)", "cloudapim_llm_gwp_per_request", "metric", 1, 1),
      widget("gwp-usage", "Usage emissions (gCO2eq)", "cloudapim_llm_gwp_usage_total", "metric", 1, 1),
      widget("gwp-embodied", "Embodied emissions (gCO2eq)", "cloudapim_llm_gwp_embodied_total", "metric", 1, 1),
      widget("gwp-1k", "Emissions per 1k tokens (gCO2eq)", "cloudapim_llm_gwp_per_1k_tokens", "metric", 1, 1),
      m("calls-top", "LLM calls", "cloudapim_llm_requests_total"),
      widget("gwp-ts", "Emissions over time (gCO2eq)", "cloudapim_llm_gwp_over_time", "area", 2, 2, options = Stacked),
      widget("gwp-cumulative", "Cumulative emissions (gCO2eq)", "cloudapim_llm_cumulative_gwp_over_time", "area", 2, 2),
      widget("gwp-models-ts", "Emissions by model over time (gCO2eq)", "cloudapim_llm_gwp_by_model_over_time", "line", 4, 2),
      widget("gwp-model", "Emissions by model (gCO2eq)", "cloudapim_llm_gwp_by_model", "bar", 2, 2, params = Top10),
      widget("gwp-provider", "Emissions by provider (gCO2eq)", "cloudapim_llm_gwp_by_provider", "donut", 2, 2),
      widget("efficiency", "Emissions per 1k generated tokens (gCO2eq)", "cloudapim_llm_gwp_per_1k_output_tokens_by_model", "bar", 2, 2, params = Top10),
      widget("energy-model", "Energy by model (Wh)", "cloudapim_llm_energy_by_model", "bar", 2, 2, params = Top10),
      widget("pe", "Primary energy (MJ)", "cloudapim_llm_pe_total", "metric", 1, 1),
      widget("adpe", "Abiotic depletion (mgSbeq)", "cloudapim_llm_adpe_total", "metric", 1, 1),
      m("tokens", "Tokens", "cloudapim_llm_tokens_total"),
      m("calls", "LLM calls", "cloudapim_llm_requests_total"),
      widget("energy-ts", "Energy over time (Wh)", "cloudapim_llm_energy_over_time", "area", 2, 2),
      widget("pe-ts", "Primary energy over time (MJ)", "cloudapim_llm_pe_over_time", "area", 2, 2),
      widget("gwp-apikey", "Emissions by API key (gCO2eq)", "cloudapim_llm_gwp_by_apikey", "bar", 4, 2, params = Top10)
    )
  )

  val Performance = DashboardSpec(
    "cloud-apim-ai-gateway-performance",
    "AI Gateway - Performance",
    "How long callers wait, which models make them wait, and how fast tokens come out",
    Seq(
      m("avg", "Average latency", "cloudapim_llm_latency_avg", "ms"),
      m("p50", "Median latency", "cloudapim_llm_latency_p50", "ms"),
      m("p95", "p95 latency", "cloudapim_llm_latency_p95", "ms"),
      m("p99", "p99 latency", "cloudapim_llm_latency_p99", "ms"),
      widget("speed-total", "Generation speed (tokens/s)", "cloudapim_llm_output_tokens_per_second", "metric", 1, 1),
      m("cache", "Cache hit rate", "cloudapim_llm_cache_hit_rate", "percent"),
      m("calls", "LLM calls", "cloudapim_llm_requests_total"),
      m("error-rate", "Error rate", "cloudapim_llm_error_rate", "percent"),
      widget("percentiles", "Latency percentiles", "cloudapim_llm_latency_percentiles_over_time", "line", 4, 2, Some("ms")),
      widget("distribution", "Latency distribution", "cloudapim_llm_latency_distribution", "bar", 2, 2),
      widget("speed-ts", "Generation speed over time (tokens/s)", "cloudapim_llm_output_tokens_per_second_over_time", "line", 2, 2),
      widget("ttft", "Time to first token percentiles", "cloudapim_llm_ttft_percentiles_over_time", "line", 2, 2, Some("ms")),
      widget("ttft-models", "Time to first token by model (median)", "cloudapim_llm_ttft_p50_by_model", "bar", 2, 2, Some("ms"), Top10),
      widget("heatmap", "Latency heatmap", "cloudapim_llm_latency_heatmap", "heatmap", 4, 3),
      widget("slow-models", "Slowest models (avg)", "cloudapim_llm_latency_by_model", "bar", 2, 2, Some("ms"), Top10),
      widget("p95-models", "p95 latency by model", "cloudapim_llm_latency_p95_by_model", "bar", 2, 2, Some("ms"), Top10),
      widget("speed", "Generation speed (tokens/s)", "cloudapim_llm_output_tokens_per_second_by_model", "bar", 2, 2, params = Top10),
      widget("slow-providers", "Slowest providers (avg)", "cloudapim_llm_latency_by_provider", "bar", 2, 2, Some("ms"), Top10),
      widget("rps", "LLM calls per second", "cloudapim_llm_requests_per_second", "line", 4, 2, Some("rps"))
    )
  )

  val Reliability = DashboardSpec(
    "cloud-apim-ai-gateway-reliability",
    "AI Gateway - Reliability",
    "What fails, where, and why: provider errors, guardrails, quotas and rate limits",
    Seq(
      m("errors", "LLM errors", "cloudapim_llm_errors_total"),
      m("error-rate", "Error rate", "cloudapim_llm_error_rate", "percent"),
      m("guardrails", "Guardrail denials", "cloudapim_llm_guardrail_denials_total"),
      m("quotas", "Provider quota incidents", "cloudapim_llm_provider_quota_alerts_total"),
      widget("error-rate-ts", "Error rate over time", "cloudapim_llm_error_rate_over_time", "line", 4, 2, Some("percent")),
      widget("kinds", "Errors by kind", "cloudapim_llm_errors_by_kind", "donut", 2, 2),
      widget("finish-reasons", "Calls by finish reason", "cloudapim_llm_by_finish_reason", "donut", 2, 2),
      widget("messages", "Top error messages", "cloudapim_llm_top_error_messages", "bar", 2, 2, params = Top10),
      widget("by-provider", "Errors by provider", "cloudapim_llm_errors_by_provider", "bar", 2, 2, params = Top10),
      widget("rate-by-provider", "Error rate by provider", "cloudapim_llm_error_rate_by_provider", "bar", 2, 2, Some("percent"), Top10),
      widget("by-model", "Errors by model", "cloudapim_llm_errors_by_model", "bar", 2, 2, params = Top10),
      widget("headroom", "Provider rate limit headroom (tokens)", "cloudapim_llm_ratelimit_headroom_by_provider", "bar", 2, 2, params = Top10),
      widget("providers", "Provider health", "cloudapim_llm_providers_table", "table", 4, 2),
      widget("quotas-ts", "Provider quota incidents over time", "cloudapim_llm_provider_quota_alerts_over_time", "area", 4, 2, options = Stacked),
      widget("guardrails-ts", "Guardrail denials over time", "cloudapim_llm_guardrail_denials_over_time", "area", 2, 2),
      widget("quotas-vendor", "Quota incidents by provider type", "cloudapim_llm_provider_quota_alerts_by_provider", "donut", 2, 2),
      widget("by-route", "Errors by route", "cloudapim_llm_errors_by_route", "bar", 4, 2, params = Top10),
      widget("recent", "Recent LLM errors", "cloudapim_llm_recent_errors", "table", 4, 3)
    )
  )

  val Consumers = DashboardSpec(
    "cloud-apim-ai-gateway-consumers",
    "AI Gateway - Consumers",
    "Who uses the gateway, and what their usage costs and emits — the chargeback view",
    Seq(
      m("calls", "LLM calls", "cloudapim_llm_requests_total"),
      widget("spend", "Spend ($)", "cloudapim_llm_cost_total", "metric", 1, 1),
      m("tokens", "Tokens", "cloudapim_llm_tokens_total"),
      widget("gwp", "Emissions (gCO2eq)", "cloudapim_llm_gwp_total", "metric", 1, 1),
      widget("apikeys-table", "API keys", "cloudapim_llm_apikeys_table", "table", 4, 3, params = Json.obj("top_n" -> 25)),
      widget("users-table", "Users", "cloudapim_llm_users_table", "table", 4, 3, params = Json.obj("top_n" -> 25)),
      widget("end-users-table", "End users", "cloudapim_llm_end_users_table", "table", 4, 3, params = Json.obj("top_n" -> 25)),
      widget("routes-table", "Routes", "cloudapim_llm_routes_table", "table", 4, 3, params = Json.obj("top_n" -> 25)),
      widget("apikeys", "Top API keys", "cloudapim_llm_top_apikeys", "bar", 2, 2, params = Top10),
      widget("apikeys-spend", "Spend by API key ($)", "cloudapim_llm_cost_by_apikey", "bar", 2, 2, params = Top10),
      widget("apikeys-tokens", "Tokens by API key", "cloudapim_llm_tokens_by_apikey", "bar", 2, 2, params = Top10),
      widget("apikeys-gwp", "Emissions by API key (gCO2eq)", "cloudapim_llm_gwp_by_apikey", "bar", 2, 2, params = Top10),
      widget("users", "Top users", "cloudapim_llm_top_users", "bar", 2, 2, params = Top10),
      widget("users-spend", "Spend by user ($)", "cloudapim_llm_cost_by_user", "bar", 2, 2, params = Top10),
      widget("routes", "Top routes", "cloudapim_llm_top_routes", "bar", 2, 2, params = Top10),
      widget("routes-spend", "Spend by route ($)", "cloudapim_llm_cost_by_route", "bar", 2, 2, params = Top10)
    )
  )

  val McpOverview = DashboardSpec(
    "cloud-apim-ai-gateway-mcp-overview",
    "MCP - Overview",
    "What agents call through the gateway, served and consumed, and how it holds up",
    Seq(
      m("calls", "MCP calls", "cloudapim_mcp_calls_total"),
      m("tool-calls", "Tool calls", "cloudapim_mcp_tool_calls_total"),
      m("failure-rate", "Failure rate", "cloudapim_mcp_error_rate", "percent"),
      m("p95", "p95 latency", "cloudapim_mcp_latency_p95", "ms"),
      widget("calls-ts", "MCP calls over time", "cloudapim_mcp_calls_over_time", "area", 4, 2, options = Stacked),
      widget("methods", "Requests by method", "cloudapim_mcp_by_method", "donut", 2, 2),
      widget("sides", "Served vs consumed", "cloudapim_mcp_by_side", "pie", 2, 2),
      widget("tools", "Top tools", "cloudapim_mcp_top_tools", "bar", 2, 2, params = Top10),
      widget("failing-tools", "Failing tools", "cloudapim_mcp_failing_tools", "bar", 2, 2, params = Top10),
      widget("servers", "Top MCP servers", "cloudapim_mcp_top_servers", "bar", 2, 2, params = Top10),
      widget("connectors", "Top MCP connectors", "cloudapim_mcp_top_connectors", "bar", 2, 2, params = Top10),
      widget("transports", "Requests by transport", "cloudapim_mcp_by_transport", "donut", 2, 2),
      widget("protocols", "Clients by protocol version", "cloudapim_mcp_by_protocol_version", "donut", 2, 2)
    )
  )

  val McpTools = DashboardSpec(
    "cloud-apim-ai-gateway-mcp-tools",
    "MCP - Tools & security",
    "Tool by tool: usage, failures, latency, and what zero-trust caught",
    Seq(
      widget("tools", "Tools", "cloudapim_mcp_tools_table", "table", 4, 3, params = Json.obj("top_n" -> 25)),
      widget("tools-ts", "Tool calls by tool over time", "cloudapim_mcp_calls_by_tool_over_time", "line", 4, 2),
      widget("slowest", "Slowest tools (avg)", "cloudapim_mcp_slowest_tools", "bar", 2, 2, Some("ms"), Top10),
      widget("failing-connectors", "Failing MCP connectors", "cloudapim_mcp_failing_connectors", "bar", 2, 2, params = Top10),
      widget("percentiles", "MCP latency percentiles", "cloudapim_mcp_latency_percentiles_over_time", "line", 4, 2, Some("ms")),
      widget("heatmap", "MCP latency heatmap", "cloudapim_mcp_latency_heatmap", "heatmap", 4, 3),
      m("zt-events", "Zero-trust events", "cloudapim_mcp_zero_trust_events_total"),
      m("zt-blocked", "Tools blocked", "cloudapim_mcp_zero_trust_blocked_total"),
      m("failures", "MCP failures", "cloudapim_mcp_errors_total"),
      m("failure-rate", "Failure rate", "cloudapim_mcp_error_rate", "percent"),
      widget("zt-ts", "Zero-trust events over time", "cloudapim_mcp_zero_trust_over_time", "area", 4, 2, options = Stacked),
      widget("zt-kinds", "Zero-trust events by kind", "cloudapim_mcp_zero_trust_by_kind", "donut", 2, 2),
      widget("zt-tools", "Zero-trust events by tool", "cloudapim_mcp_zero_trust_by_tool", "bar", 2, 2, params = Top10),
      widget("errors", "Top MCP errors", "cloudapim_mcp_top_errors", "bar", 2, 2, params = Top10),
      widget("fetches", "Failing resource fetches", "cloudapim_mcp_failing_resource_fetches", "bar", 2, 2, params = Top10),
      widget("failure-rate-ts", "Failure rate over time", "cloudapim_mcp_error_rate_over_time", "line", 4, 2, Some("percent")),
      widget("apikeys", "Top MCP API keys", "cloudapim_mcp_top_apikeys", "bar", 2, 2, params = Top10),
      widget("users", "Top MCP users", "cloudapim_mcp_top_users", "bar", 2, 2, params = Top10)
    )
  )

  /**
   * Widgets whose title carries a unit and that were given no format get the matching one: dollars
   * as a currency (a call costs a fraction of a cent, which a plain count rounds to 0), physical
   * quantities as numbers whose precision follows their magnitude. A viewer that does not know a
   * format falls back to plain numbers.
   */
  private def withUnitFormats(spec: DashboardSpec): DashboardSpec = spec.copy(widgets = spec.widgets.map { w =>
    val hasFormat = (w.options \ "format").isDefined
    val physical  = Seq("(Wh)", "(gCO2eq)", "(MJ)", "(L)", "(mgSbeq)", "(tokens/s)", "ratio")
    if (hasFormat) w
    else if (w.title.contains("($)")) w.copy(options = w.options ++ Json.obj("format" -> "currency"))
    else if (physical.exists(w.title.contains)) w.copy(options = w.options ++ Json.obj("format" -> "number"))
    else w
  })

  val all: Seq[DashboardSpec] =
    Seq(Overview, Costs, Tokens, Budgets, Ecology, Performance, Reliability, Consumers, McpOverview, McpTools).map(withUnitFormats)
}
