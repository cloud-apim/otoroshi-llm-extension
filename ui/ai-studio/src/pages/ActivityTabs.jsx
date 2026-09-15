import { useState } from 'react';
import { AreaChart, seriesColor, StackedBars } from '../components/charts';
import { Badge, Empty, ErrorAlert, Loading, Segmented, Select, useAsync } from '../components/ui';
import { Delta, Kpi, METRIC_OPTIONS } from '../components/usage';
import { compareOf, itemsOf, runQuery, scalarOf, seriesOf, totalPoints } from '../lib/analytics';
import { fmtCost, fmtInt, fmtMs, fmtNumber, fmtPercent } from '../lib/format';

// The Trends, Explore and Guardrails tabs of the activity page, all backed by the `cloudapim_llm_explore`
// query: one metric by one dimension, as a ranking or over time.

const fmtRate = (v) => fmtPercent(v);
const fmtSpeed = (v) => `${Number(v || 0).toFixed(1)} tok/s`;

export const EXPLORE_METRICS = [
  { value: 'requests', label: 'Requests', format: fmtNumber, group: 'Volume' },
  { value: 'tokens', label: 'Tokens (total)', format: fmtNumber, group: 'Volume' },
  { value: 'input_tokens', label: 'Tokens (prompt)', format: fmtNumber, group: 'Volume' },
  { value: 'output_tokens', label: 'Tokens (completion)', format: fmtNumber, group: 'Volume' },
  { value: 'reasoning_tokens', label: 'Reasoning tokens', format: fmtNumber, group: 'Volume' },
  { value: 'users', label: 'Distinct users', format: fmtNumber, group: 'Volume' },
  { value: 'sessions', label: 'Distinct sessions', format: fmtNumber, group: 'Volume' },
  { value: 'spend', label: 'Spend ($)', format: fmtCost, group: 'Cost' },
  { value: 'input_spend', label: 'Prompt spend ($)', format: fmtCost, group: 'Cost' },
  { value: 'output_spend', label: 'Completion spend ($)', format: fmtCost, group: 'Cost' },
  { value: 'reasoning_spend', label: 'Reasoning spend ($)', format: fmtCost, group: 'Cost' },
  { value: 'blended_cost', label: 'Blended cost ($ / 1M tokens)', format: fmtCost, group: 'Cost' },
  { value: 'avg_latency', label: 'Avg latency', format: fmtMs, group: 'Performance' },
  { value: 'p50_latency', label: 'P50 latency', format: fmtMs, group: 'Performance' },
  { value: 'p90_latency', label: 'P90 latency', format: fmtMs, group: 'Performance' },
  { value: 'p95_latency', label: 'P95 latency', format: fmtMs, group: 'Performance' },
  { value: 'p99_latency', label: 'P99 latency', format: fmtMs, group: 'Performance' },
  { value: 'avg_ttft', label: 'Avg time to first token', format: fmtMs, group: 'Performance' },
  { value: 'p50_ttft', label: 'P50 time to first token', format: fmtMs, group: 'Performance' },
  { value: 'p95_ttft', label: 'P95 time to first token', format: fmtMs, group: 'Performance' },
  { value: 'speed', label: 'Throughput (tok/s)', format: fmtSpeed, group: 'Performance' },
  { value: 'cache_hits', label: 'Cache hits', format: fmtNumber, group: 'Reliability' },
  { value: 'cache_hit_rate', label: 'Cache hit rate', format: fmtRate, group: 'Reliability' },
  { value: 'errors', label: 'Errors', format: fmtNumber, group: 'Reliability' },
  { value: 'error_rate', label: 'Error rate', format: fmtRate, group: 'Reliability' },
  { value: 'guardrail_denials', label: 'Guardrail denials', format: fmtNumber, group: 'Reliability' },
  { value: 'truncated_rate', label: 'Truncated answers rate', format: fmtRate, group: 'Reliability' },
  { value: 'gco2eq', label: 'Emissions (gCO2eq)', format: (v) => `${Number(v || 0).toFixed(2)} g`, group: 'Ecology' },
  { value: 'energy_wh', label: 'Energy (Wh)', format: (v) => `${Number(v || 0).toFixed(2)} Wh`, group: 'Ecology' },
];

export const EXPLORE_DIMENSIONS = [
  { value: 'model', label: 'Model' },
  { value: 'provider', label: 'Provider' },
  { value: 'provider_kind', label: 'Provider type' },
  { value: 'apikey', label: 'API key' },
  { value: 'user', label: 'User' },
  { value: 'key_owner', label: 'Key owner' },
  { value: 'end_user', label: 'End user' },
  { value: 'session', label: 'Session' },
  { value: 'modality', label: 'Modality' },
  { value: 'operation', label: 'Operation' },
  { value: 'streamed', label: 'Streamed' },
  { value: 'finish_reason', label: 'Finish reason' },
  { value: 'status', label: 'Status' },
  { value: 'error_kind', label: 'Error kind' },
  { value: 'cache', label: 'Cache' },
  { value: 'cost_source', label: 'Pricing source' },
];

const ROLLUPS = [
  { value: 'total', label: 'Total' },
  { value: 'hour', label: 'Hourly' },
  { value: 'day', label: 'Daily' },
  { value: 'week', label: 'Weekly' },
  { value: 'month', label: 'Monthly' },
];

const metricOf = (id) => EXPLORE_METRICS.find((m) => m.value === id) || EXPLORE_METRICS[0];
const dimensionLabel = (id) => (EXPLORE_DIMENSIONS.find((d) => d.value === id) || { label: id }).label;
const shortPeriod = (period) => period === '1h' || period === '24h';

function metricOptions() {
  return EXPLORE_METRICS.map((m) => ({ value: m.value, label: `${m.group} · ${m.label}` }));
}

// relative change against the previous period, `null` for a group that did not exist before
function growth(item) {
  const prev = Number(item.previous);
  const value = Number(item.value);
  if (!prev) return value > 0 ? null : 0;
  return (value - prev) / prev;
}

function ShareBar({ items, total, format }) {
  const sum = Number(total) || items.reduce((a, i) => a + (Number(i.value) || 0), 0);
  if (!sum) return null;
  const shown = items.reduce((a, i) => a + (Number(i.value) || 0), 0);
  const segments = [...items.map((i, idx) => ({ name: i.key, value: Number(i.value) || 0, color: seriesColor(idx, i.key) })), ...(sum - shown > 0 ? [{ name: 'Other', value: sum - shown, color: seriesColor(0, 'Other') }] : [])];
  return (
    <div className="stack tight">
      <div className="share-bar">
        {segments.map((s) => (
          <i key={s.name} style={{ width: `${(s.value / sum) * 100}%`, background: s.color }} title={`${s.name}: ${format(s.value)}`} />
        ))}
      </div>
      <div className="chart-legend" style={{ marginTop: 0 }}>
        {segments.map((s) => (
          <span key={s.name}>
            <i style={{ background: s.color }} />
            {s.name}
          </span>
        ))}
      </div>
    </div>
  );
}

export function ExploreTab({ workspace, query, setQuery, opts }) {
  const metric = metricOf(query.metric || 'spend');
  const groupBy = EXPLORE_DIMENSIONS.some((d) => d.value === query.by) ? query.by : 'model';
  const subgroup = EXPLORE_DIMENSIONS.some((d) => d.value === query.sub) && query.sub !== groupBy ? query.sub : '';
  const rollup = ROLLUPS.some((r) => r.value === query.rollup) ? query.rollup : 'total';
  const top = ['5', '10', '20', '50'].includes(query.top) ? query.top : '10';
  const compare = query.compare === 'true';
  const data = useAsync(
    () =>
      runQuery(workspace.id, 'cloudapim_llm_explore', {
        ...opts,
        params: { metric: metric.value, group_by: groupBy, subgroup: rollup === 'total' ? subgroup || undefined : undefined, rollup, top_n: Number(top), compare: rollup === 'total' && compare },
      }),
    [workspace.id, JSON.stringify(opts), metric.value, groupBy, subgroup, rollup, top, compare]
  );
  const d = data.data && data.data.data;
  const items = d && d.items ? d.items : [];
  const additive = d ? d.additive : true;

  return (
    <div className="stack">
      <div className="row wrap explore-controls">
        <Select className="sm" style={{ width: 'auto' }} value={metric.value} onChange={(v) => setQuery({ metric: v })} options={metricOptions()} />
        <span className="muted small">by</span>
        <Select className="sm" style={{ width: 'auto' }} value={groupBy} onChange={(v) => setQuery({ by: v })} options={EXPLORE_DIMENSIONS} />
        {rollup === 'total' && (
          <Select className="sm" style={{ width: 'auto' }} value={subgroup} onChange={(v) => setQuery({ sub: v })} placeholder="No subgroup" options={EXPLORE_DIMENSIONS.filter((x) => x.value !== groupBy)} />
        )}
        <Select className="sm" style={{ width: 'auto' }} value={rollup} onChange={(v) => setQuery({ rollup: v })} options={ROLLUPS.map((r) => ({ value: r.value, label: `Rollup: ${r.label}` }))} />
        <Select className="sm" style={{ width: 'auto' }} value={top} onChange={(v) => setQuery({ top: v })} options={['5', '10', '20', '50'].map((n) => ({ value: n, label: `Top ${n}` }))} />
        {rollup === 'total' && (
          <label className="check small">
            <input type="checkbox" checked={compare} onChange={(e) => setQuery({ compare: e.target.checked ? 'true' : '' })} />
            vs previous period
          </label>
        )}
      </div>
      <ErrorAlert error={data.error} />
      <div className="card">
        {data.loading && !d && <Loading />}
        {d && d.mode === 'series' &&
          (additive ? (
            <StackedBars series={seriesOf(data.data)} bucket={d.bucket} format={metric.format} height={320} />
          ) : (
            <AreaChart series={seriesOf(data.data)} bucket={d.bucket} format={metric.format} height={320} />
          ))}
        {d && d.mode === 'total' && items.length === 0 && <Empty>No call in this period.</Empty>}
        {d && d.mode === 'total' && items.length > 0 && (
          <div className="stack">
            {additive && !subgroup && <ShareBar items={items} total={d.total} format={metric.format} />}
            <div className="table-wrap">
              <table className="table">
                <thead>
                  <tr>
                    <th>{dimensionLabel(groupBy)}</th>
                    {subgroup && <th>By {dimensionLabel(subgroup).toLowerCase()}</th>}
                    <th className="num">{metric.label}</th>
                    {additive && <th className="num">% of total</th>}
                    {compare && <th className="num">Previous period</th>}
                    {compare && <th className="num">Change</th>}
                  </tr>
                </thead>
                <tbody>
                  {items.map((it, idx) => {
                    const share = additive && Number(d.total) ? (Number(it.value) || 0) / Number(d.total) : null;
                    const subTotal = (it.subgroups || []).reduce((a, s) => a + (Number(s.value) || 0), 0);
                    return (
                      <tr key={it.key}>
                        <td className="truncate" style={{ maxWidth: 320 }}>
                          <span className="row" style={{ gap: 8 }}>
                            <i className="dot" style={{ background: seriesColor(idx, it.key) }} />
                            <span className="truncate">{it.key}</span>
                          </span>
                        </td>
                        {subgroup && (
                          <td style={{ minWidth: 220 }}>
                            <div className="share-bar sm">
                              {(it.subgroups || []).map((s, j) => (
                                <i key={s.key} style={{ width: `${subTotal ? ((Number(s.value) || 0) / subTotal) * 100 : 0}%`, background: seriesColor(j, s.key) }} title={`${s.key}: ${metric.format(s.value)}`} />
                              ))}
                            </div>
                            <div className="faint small truncate" style={{ maxWidth: 320 }}>
                              {(it.subgroups || [])
                                .slice(0, 4)
                                .map((s) => `${s.key} ${metric.format(s.value)}`)
                                .join(' · ')}
                            </div>
                          </td>
                        )}
                        <td className="num">{metric.format(it.value)}</td>
                        {additive && (
                          <td className="num">
                            <span className="row" style={{ gap: 8, justifyContent: 'flex-end' }}>
                              <span className="mini-bar">
                                <i style={{ width: `${(share || 0) * 100}%`, background: seriesColor(idx, it.key) }} />
                              </span>
                              {fmtPercent(share || 0)}
                            </span>
                          </td>
                        )}
                        {compare && <td className="num">{metric.format(it.previous)}</td>}
                        {compare && (
                          <td className="num">
                            <Delta value={Number(it.value) || 0} previous={Number(it.previous) || 0} />
                          </td>
                        )}
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
            <p className="faint small">
              {fmtInt(items.length)} of {fmtInt(d.groups)} {dimensionLabel(groupBy).toLowerCase()} values
              {additive ? ` · total ${metric.format(d.total)}` : ''}
            </p>
          </div>
        )}
      </div>
    </div>
  );
}

const TREND_METRICS = { spend: 'spend', tokens: 'tokens', requests: 'requests' };

function TrendSection({ workspace, opts, period, dimension, title }) {
  const [metricId, setMetricId] = useState('spend');
  const metric = metricOf(TREND_METRICS[metricId]);
  const data = useAsync(async () => {
    const run = (params) => runQuery(workspace.id, 'cloudapim_llm_explore', { ...opts, params: { metric: metric.value, group_by: dimension, ...params } });
    const [series, ranking] = await Promise.all([run({ rollup: shortPeriod(period) ? 'hour' : 'day', top_n: 6 }), run({ rollup: 'total', top_n: 50, compare: true })]);
    return { series, ranking };
  }, [workspace.id, JSON.stringify(opts), metric.value, dimension]);
  const d = data.data;
  const trending = d
    ? itemsOf(d.ranking)
        .filter((i) => Number(i.value) > 0)
        .map((i) => ({ ...i, change: growth(i) }))
        .sort((a, b) => (a.change === null ? -1 : b.change === null ? 1 : Math.abs(b.change) - Math.abs(a.change)))
        .slice(0, 6)
    : [];
  return (
    <div className="stack tight">
      <div className="row between">
        <h2>{title}</h2>
        <Segmented value={metricId} onChange={setMetricId} options={METRIC_OPTIONS} />
      </div>
      <ErrorAlert error={data.error} />
      <div className="grid split">
        <div className="card">
          <h3 style={{ marginBottom: 12 }}>{metric.label} over time</h3>
          {data.loading && !d ? <Loading /> : <StackedBars series={d ? seriesOf(d.series) : []} bucket={d && d.series.data.bucket} format={metric.format} height={240} />}
        </div>
        <div className="card">
          <h3 style={{ marginBottom: 4 }}>Trending</h3>
          <p className="muted small" style={{ marginBottom: 8 }}>
            The biggest moves against the previous period.
          </p>
          {data.loading && !d && <Loading />}
          {d && trending.length === 0 && <p className="muted small">No usage in this period.</p>}
          <div className="rank">
            {trending.map((t) => (
              <div key={t.key} className="item">
                <div className="grow" style={{ minWidth: 0 }}>
                  <div className="truncate">{t.key}</div>
                  <div className="faint small">{metric.format(t.value)}</div>
                </div>
                {t.change === null ? <Badge kind="info">new</Badge> : <Delta value={Number(t.value)} previous={Number(t.previous)} inverse={metric.value === 'spend'} />}
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}

export function TrendsTab({ workspace, opts, period, user, apikey }) {
  return (
    <div className="stack" style={{ gap: 28 }}>
      <TrendSection workspace={workspace} opts={opts} period={period} dimension="model" title="Models" />
      {!user && <TrendSection workspace={workspace} opts={opts} period={period} dimension="user" title="Users" />}
      {!apikey && <TrendSection workspace={workspace} opts={opts} period={period} dimension="apikey" title="API keys" />}
      <TrendSection workspace={workspace} opts={opts} period={period} dimension="end_user" title="End users" />
    </div>
  );
}

function RankingCard({ workspace, opts, title, dimension, empty }) {
  const data = useAsync(() => runQuery(workspace.id, 'cloudapim_llm_explore', { ...opts, params: { metric: 'guardrail_denials', group_by: dimension, status: 'guardrail', top_n: 8 } }).then(itemsOf), [workspace.id, JSON.stringify(opts), dimension]);
  const items = data.data || [];
  const max = Math.max(1, ...items.map((i) => Number(i.value) || 0));
  return (
    <div className="card">
      <h3 style={{ marginBottom: 8 }}>{title}</h3>
      {data.loading && !data.data && <Loading />}
      {data.data && items.length === 0 && <p className="muted small">{empty}</p>}
      <div className="rank">
        {items.map((it, i) => (
          <div key={it.key} className="item">
            <span className="pos">{i + 1}</span>
            <div className="grow" style={{ minWidth: 0 }}>
              <div className="row between">
                <span className="truncate small">{it.key}</span>
                <span className="small">{fmtInt(it.value)}</span>
              </div>
              <div className="bar">
                <i style={{ width: `${((Number(it.value) || 0) / max) * 100}%` }} />
              </div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

export function GuardrailsTab({ workspace, opts }) {
  const data = useAsync(async () => {
    const q = (id, extra = {}) => runQuery(workspace.id, id, { ...opts, ...extra });
    const [denials, requests, overTime, truncated] = await Promise.all([
      q('cloudapim_llm_guardrail_denials_total', { compare: true }),
      q('cloudapim_llm_requests_total', { compare: true }),
      q('cloudapim_llm_guardrail_denials_over_time'),
      q('cloudapim_llm_by_finish_reason'),
    ]);
    return { denials, requests, overTime, truncated };
  }, [workspace.id, JSON.stringify(opts)]);
  const d = data.data;
  const rate = d && scalarOf(d.requests) ? scalarOf(d.denials) / scalarOf(d.requests) : 0;
  const previousRate = d && compareOf(d.requests) ? (compareOf(d.denials) || 0) / compareOf(d.requests) : null;
  const filtered = d ? itemsOf(d.truncated).find((i) => i.key === 'content_filter') : null;
  return (
    <div className="stack">
      <ErrorAlert error={data.error} />
      {data.loading && !d && <Loading />}
      {d && (
        <>
          <div className="grid cols-3">
            <Kpi label="Blocked requests" value={scalarOf(d.denials)} previous={compareOf(d.denials)} format={fmtNumber} points={totalPoints(d.overTime)} inverse />
            <Kpi label="Block rate" value={rate} previous={previousRate} format={fmtRate} points={[]} inverse />
            <div className="card tight kpi">
              <div className="label">Filtered by the provider</div>
              <div className="value">{fmtNumber(filtered ? filtered.value : 0)}</div>
              <span className="vs">answers ended by a content filter</span>
            </div>
          </div>
          <div className="card">
            <h3 style={{ marginBottom: 12 }}>Blocked requests over time</h3>
            <StackedBars series={seriesOf(d.overTime)} bucket={d.overTime.meta && d.overTime.meta.bucket} format={fmtNumber} empty="No request was blocked by a guardrail in this period" />
          </div>
          <div className="grid cols-2">
            <RankingCard workspace={workspace} opts={opts} title="Top reasons" dimension="error_message" empty="No request was blocked." />
            <RankingCard workspace={workspace} opts={opts} title="By model" dimension="model" empty="No request was blocked." />
            <RankingCard workspace={workspace} opts={opts} title="By API key" dimension="apikey" empty="No request made with an API key was blocked." />
            <RankingCard workspace={workspace} opts={opts} title="By user" dimension="user" empty="No request of a user was blocked." />
          </div>
        </>
      )}
    </div>
  );
}
