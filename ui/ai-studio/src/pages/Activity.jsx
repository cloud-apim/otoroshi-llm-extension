import { useState } from 'react';
import { useWorkspace } from '../App';
import { AreaChart, Sparkline, StackedBars } from '../components/charts';
import { Icon } from '../components/icons';
import { Empty, ErrorAlert, Loading, PageHeader, Progress, Segmented, Select, useAsync } from '../components/ui';
import { compareOf, itemsOf, NoExporterError, PERIODS, runQuery, scalarOf, seriesOf, totalPoints } from '../lib/analytics';
import { listApikeys } from '../lib/apikeys';
import { budgetConsumption, listBudgets, periodLabel } from '../lib/budgets';
import { fmtCost, fmtInt, fmtMs, fmtNumber, fmtPercent } from '../lib/format';

function Delta({ value, previous, inverse }) {
  if (previous === null || previous === undefined) return null;
  if (previous === 0) return value > 0 ? <span className="kpi delta up">new</span> : <span className="faint small">—</span>;
  const ratio = (value - previous) / previous;
  const up = ratio >= 0;
  const good = inverse ? !up : up;
  return (
    <span className={`delta ${good ? 'up' : 'down'}`}>
      {up ? '↑' : '↓'} {Math.abs(ratio * 100).toFixed(1)}%
    </span>
  );
}

function Kpi({ label, value, previous, format, points, inverse }) {
  return (
    <div className="card tight kpi">
      <div className="top">
        <div>
          <div className="label">{label}</div>
          <div className="value">{format(value)}</div>
        </div>
        <Sparkline points={points} />
      </div>
      <div className="row between">
        <Delta value={value} previous={previous} inverse={inverse} />
        <span className="vs">vs prev period</span>
      </div>
    </div>
  );
}

function RankCard({ title, items, valueLabel }) {
  const max = Math.max(1, ...items.map((i) => i.value));
  return (
    <div className="card">
      <h2 style={{ marginBottom: 10 }}>{title}</h2>
      {items.length === 0 && <p className="muted small">No usage for this period.</p>}
      <div className="rank">
        {items.map((it, idx) => (
          <div key={it.key} className="item">
            <span className="pos">{idx + 1}</span>
            <div className="grow">
              <div className="row between">
                <span className="truncate">{it.label}</span>
                <span className="small">{valueLabel(it)}</span>
              </div>
              <div className="bar">
                <i style={{ width: `${(it.value / max) * 100}%` }} />
              </div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

function LiveSection({ workspace }) {
  const data = useAsync(async () => {
    const budgets = await listBudgets(workspace.id);
    const consumptions = await Promise.all(budgets.map((b) => budgetConsumption(b.id).catch(() => null)));
    return budgets.map((b, i) => ({ budget: b, consumption: consumptions[i] }));
  }, [workspace.id]);
  const list = data.data || [];
  if (data.loading && !data.data) return null;
  return (
    <div className="card">
      <h2>Budgets</h2>
      <p className="muted" style={{ margin: '4px 0 12px' }}>
        Live consumption of the current window of each budget.
      </p>
      {list.length === 0 && <p className="muted small">No budget in this workspace.</p>}
      <div className="stack">
        {list.map(({ budget, consumption }) => {
          const usd = consumption ? Number(consumption.consumed_total_usd) || 0 : 0;
          const tokens = consumption ? Number(consumption.consumed_total_tokens) || 0 : 0;
          const limitUsd = budget.limits && budget.limits.total_usd;
          const limitTokens = budget.limits && budget.limits.total_tokens;
          return (
            <div key={budget.id} className="stack tight">
              <div className="row between">
                <span>
                  {budget.name} <span className="faint small">· {periodLabel(budget)}</span>
                </span>
                <span className="small">
                  {limitUsd !== undefined && limitUsd !== null ? `${fmtCost(usd)} / ${fmtCost(limitUsd)}` : fmtCost(usd)}
                  {' · '}
                  {limitTokens ? `${fmtNumber(tokens)} / ${fmtNumber(limitTokens)} tokens` : `${fmtNumber(tokens)} tokens`}
                </span>
              </div>
              {(limitUsd || limitTokens) && <Progress value={limitUsd ? usd : tokens} max={limitUsd || limitTokens} />}
            </div>
          );
        })}
      </div>
    </div>
  );
}

const METRICS = {
  spend: { label: 'Spend', query: 'cost', format: fmtCost },
  tokens: { label: 'Tokens', query: 'tokens', format: fmtNumber },
  requests: { label: 'Requests', query: 'requests', format: fmtNumber },
};

// "Usage by <dimension>" card: one stacked bar series per model, api key or user
function UsageCard({ title, description, dimension, run, deps, bucket, empty }) {
  const [metric, setMetric] = useState('tokens');
  const data = useAsync(() => run(`cloudapim_llm_${METRICS[metric].query}_by_${dimension}_over_time`, { params: { top_n: 7 } }), [...deps, metric]);
  return (
    <div className="card">
      <div className="card-head">
        <div>
          <h2>{title}</h2>
          {description && <p>{description}</p>}
        </div>
        <Segmented value={metric} onChange={setMetric} options={Object.entries(METRICS).map(([value, m]) => ({ value, label: m.label }))} />
      </div>
      {data.loading && !data.data ? <Loading /> : <StackedBars series={seriesOf(data.data)} bucket={bucket} format={METRICS[metric].format} height={240} empty={empty} />}
    </div>
  );
}

export function ActivityPage() {
  const { workspace } = useWorkspace();
  const [period, setPeriod] = useState('7d');
  const [apikey, setApikey] = useState('');
  const [refresh, setRefresh] = useState(0);
  const keys = useAsync(() => listApikeys(workspace.id), [workspace.id]);

  const opts = { period, apikey: apikey || undefined, nocache: refresh > 0 };
  const q = (query, extra = {}) => runQuery(workspace.id, query, { ...opts, ...extra });

  const kpis = useAsync(async () => {
    const [spend, spendTs, requests, requestsTs, tokens, tokensTs, cache, cacheTs, per1k, latency] = await Promise.all([
      q('cloudapim_llm_cost_total', { compare: true }),
      q('cloudapim_llm_cost_over_time'),
      q('cloudapim_llm_requests_total', { compare: true }),
      q('cloudapim_llm_requests_over_time'),
      q('cloudapim_llm_tokens_total', { compare: true }),
      q('cloudapim_llm_tokens_over_time'),
      q('cloudapim_llm_cache_hit_rate', { compare: true }),
      q('cloudapim_llm_cache_over_time'),
      q('cloudapim_llm_cost_per_1k_tokens', { compare: true }),
      q('cloudapim_llm_latency_p95', { compare: true }),
    ]);
    return { spend, spendTs, requests, requestsTs, tokens, tokensTs, cache, cacheTs, per1k, latency };
  }, [workspace.id, period, apikey, refresh]);

  const charts = useAsync(async () => {
    const [calls, tokensTs, cacheTs, latencyTs, models, apikeysTable, usersTable] = await Promise.all([
      q('cloudapim_llm_requests_over_time'),
      q('cloudapim_llm_tokens_over_time'),
      q('cloudapim_llm_cache_over_time'),
      q('cloudapim_llm_latency_percentiles_over_time'),
      q('cloudapim_llm_models_table', { params: { top_n: 20 } }),
      q('cloudapim_llm_apikeys_table', { params: { top_n: 5 } }),
      q('cloudapim_llm_users_table', { params: { top_n: 5 } }),
    ]);
    return { calls, tokensTs, cacheTs, latencyTs, models, apikeysTable, usersTable };
  }, [workspace.id, period, apikey, refresh]);
  const deps = [workspace.id, period, apikey, refresh];

  const noExporter = [kpis.error, charts.error].some((e) => e instanceof NoExporterError);
  const k = kpis.data;
  const c = charts.data;
  const bucket = c && c.calls && c.calls.meta ? c.calls.meta.bucket : undefined;

  return (
    <div className="content wide" style={{ maxWidth: 1400 }}>
      <PageHeader title="Activity" description="Usage of this workspace across models and API keys.">
        <Select
          className="sm"
          value={apikey}
          onChange={setApikey}
          placeholder="All API keys"
          options={(keys.data || []).map((key) => ({ value: key.clientId, label: key.clientName }))}
        />
        <Select className="sm" value={period} onChange={setPeriod} options={PERIODS.map((p) => ({ value: p.value, label: p.label }))} />
        <button className="btn sm" onClick={() => setRefresh((r) => r + 1)} title="Refresh">
          <Icon name="refresh" />
        </button>
      </PageHeader>

      {noExporter ? (
        <div className="stack">
          <div className="alert info">
            Usage analytics need an active <b>user analytics exporter</b> (PostgreSQL) in Otoroshi. Create one in{' '}
            <a className="link" href="/bo/dashboard/exporters" target="_blank" rel="noreferrer">
              data exporters
            </a>{' '}
            and every call of this workspace will show up here. Budgets below are live counters and work without it.
          </div>
          <LiveSection workspace={workspace} />
        </div>
      ) : (
        <div className="stack">
          <ErrorAlert error={kpis.error || charts.error} />
          {kpis.loading && !k && <Loading />}
          {k && (
            <div className="grid cols-5">
              <Kpi label="Total spend" value={scalarOf(k.spend)} previous={compareOf(k.spend)} format={fmtCost} points={totalPoints(k.spendTs)} inverse />
              <Kpi label="Requests" value={scalarOf(k.requests)} previous={compareOf(k.requests)} format={fmtNumber} points={totalPoints(k.requestsTs)} />
              <Kpi label="Token volume" value={scalarOf(k.tokens)} previous={compareOf(k.tokens)} format={fmtNumber} points={totalPoints(k.tokensTs)} />
              <Kpi
                label="Cache hit rate"
                value={scalarOf(k.cache)}
                previous={compareOf(k.cache)}
                format={(v) => fmtPercent(v)}
                points={(seriesOf(k.cacheTs).find((s) => s.name === 'hit') || {}).points}
              />
              <Kpi label="p95 latency" value={scalarOf(k.latency)} previous={compareOf(k.latency)} format={fmtMs} points={[]} inverse />
            </div>
          )}

          {c && (
            <>
              <div className="grid cols-3">
                <RankCard
                  title="Top API keys"
                  items={itemsOf(c.apikeysTable).map((i) => ({ key: i.key, label: i.apikey || i.key, value: Number(i.tokens) || 0, spend: i.spend_usd, calls: i.calls }))}
                  valueLabel={(i) => `${fmtNumber(i.value)} tok · ${fmtCost(i.spend)}`}
                />
                <RankCard
                  title="Top users"
                  items={itemsOf(c.usersTable).map((i) => ({ key: i.key, label: i.user || i.key, value: Number(i.tokens) || 0, spend: i.spend_usd }))}
                  valueLabel={(i) => `${fmtNumber(i.value)} tok · ${fmtCost(i.spend)}`}
                />
                <RankCard
                  title="Top models"
                  items={itemsOf(c.models)
                    .slice(0, 5)
                    .map((i) => ({ key: i.key, label: i.model || i.key, value: Number(i.tokens) || 0, spend: i.spend_usd }))}
                  valueLabel={(i) => `${fmtNumber(i.value)} tok · ${fmtCost(i.spend)}`}
                />
              </div>

              <UsageCard title="Usage by model" dimension="model" run={q} deps={deps} bucket={bucket} />
              <div className="grid cols-2">
                <UsageCard title="Usage by API key" dimension="apikey" run={q} deps={deps} bucket={bucket} />
                <UsageCard
                  title="Usage by user"
                  description="Studio users chatting with this workspace."
                  dimension="user"
                  run={q}
                  deps={deps}
                  bucket={bucket}
                  empty="No call attributed to a studio user in this period"
                />
              </div>

              <div className="grid cols-2">
                <div className="card">
                  <h2 style={{ marginBottom: 12 }}>Requests</h2>
                  <StackedBars series={seriesOf(c.calls)} bucket={bucket} format={fmtNumber} />
                </div>
                <div className="card">
                  <h2 style={{ marginBottom: 12 }}>Token breakdown</h2>
                  <StackedBars series={seriesOf(c.tokensTs)} bucket={bucket} format={fmtNumber} />
                </div>
                <div className="card">
                  <h2 style={{ marginBottom: 12 }}>Prompt caching</h2>
                  <StackedBars series={seriesOf(c.cacheTs)} bucket={bucket} format={fmtNumber} empty="No call went through a cache in this period" />
                </div>
                <div className="card">
                  <h2 style={{ marginBottom: 12 }}>Latency</h2>
                  <AreaChart series={seriesOf(c.latencyTs)} bucket={bucket} format={fmtMs} />
                </div>
              </div>

              <div className="card flush">
                <div style={{ padding: '18px 22px 6px' }}>
                  <h2>Models</h2>
                </div>
                {itemsOf(c.models).length === 0 ? (
                  <Empty>No model was called in this period.</Empty>
                ) : (
                  <div className="table-wrap">
                    <table className="table">
                      <thead>
                        <tr>
                          <th>Model</th>
                          <th className="num">Requests</th>
                          <th className="num">Tokens</th>
                          <th className="num">Spend</th>
                          <th className="num">$ / 1k tokens</th>
                          <th className="num">Avg latency</th>
                          <th className="num">Error rate</th>
                        </tr>
                      </thead>
                      <tbody>
                        {itemsOf(c.models).map((m) => (
                          <tr key={m.key}>
                            <td className="mono">{m.model}</td>
                            <td className="num">{fmtInt(m.calls)}</td>
                            <td className="num">{fmtInt(m.tokens)}</td>
                            <td className="num">{fmtCost(m.spend_usd)}</td>
                            <td className="num">{Number(m.usd_per_1k_tokens) ? '$' + Number(m.usd_per_1k_tokens).toFixed(4) : '—'}</td>
                            <td className="num">{fmtMs(m.avg_ms)}</td>
                            <td className="num">{Number(m.error_rate_pct).toFixed(1)}%</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </div>
            </>
          )}
          <LiveSection workspace={workspace} />
        </div>
      )}
    </div>
  );
}
