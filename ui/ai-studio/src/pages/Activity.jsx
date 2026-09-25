import { useState } from 'react';
import { useWorkspace } from '../App';
import { AreaChart, StackedBars } from '../components/charts';
import { Icon } from '../components/icons';
import { Empty, ErrorAlert, Loading, MenuButton, PageHeader, Select, Tabs, useAsync, useToast } from '../components/ui';
import { BudgetsCard, ConsumersCard, Kpi, UsageCard } from '../components/usage';
import { compareOf, itemsOf, NoExporterError, periodSlug, periodSpan, runQuery, scalarOf, seriesOf, totalPoints } from '../lib/analytics';
import { listApikeys } from '../lib/apikeys';
import { downloadCsv, exportName, isoDate } from '../lib/files';
import { fmtCost, fmtInt, fmtMs, fmtNumber, fmtPercent } from '../lib/format';
import { PeriodPicker, RefreshControl, useTimeView } from '../components/timeview';
import { Link, useQueryState } from '../lib/router';
import { ExploreTab, GuardrailsTab, McpTab, TrendsTab } from './ActivityTabs';

const TABS = [
  { value: 'overview', label: 'Overview' },
  { value: 'trends', label: 'Trends' },
  { value: 'explore', label: 'Explore' },
  { value: 'guardrails', label: 'Guardrails' },
  { value: 'mcp', label: 'MCP' },
];

const DEFAULT_PERIOD = '7d';

const num = (field) => (row) => row[field];
// sums of float costs, without their rounding noise
const usd = (value) => Math.round((Number(value) || 0) * 1e10) / 1e10;

// what can be downloaded from the activity, for the period and the filters of the page
const USAGE_EXPORTS = [
  {
    id: 'models',
    label: 'Usage by model',
    query: 'cloudapim_llm_models_table',
    columns: [
      { label: 'model', value: (r) => r.key },
      { label: 'calls', value: num('calls') },
      { label: 'tokens', value: num('tokens') },
      { label: 'spend_usd', value: num('spend_usd') },
      { label: 'usd_per_1k_tokens', value: num('usd_per_1k_tokens') },
      { label: 'avg_duration_ms', value: num('avg_ms') },
      { label: 'error_rate_pct', value: num('error_rate_pct') },
      { label: 'gco2eq_per_1k_tokens', value: num('gco2eq_per_1k_tokens') },
    ],
  },
  ...[
    ['apikeys', 'Usage by API key', 'cloudapim_llm_apikeys_table', 'apikey'],
    ['users', 'Usage by user', 'cloudapim_llm_users_table', 'user'],
  ].map(([id, label, query, key]) => ({
    id,
    label,
    query,
    columns: [
      { label: key, value: (r) => r.key },
      { label: 'calls', value: num('calls') },
      { label: 'tokens', value: num('tokens') },
      { label: 'spend_usd', value: num('spend_usd') },
      { label: 'errors', value: num('errors') },
      { label: 'gco2eq', value: num('gco2eq') },
    ],
  })),
];

// one line per hour or per day, calls, tokens and spend side by side
const TIME_COLUMNS = [
  { label: 'from', value: (r) => isoDate(r.ts) },
  { label: 'calls', value: (r) => r.success + r.error },
  { label: 'errors', value: (r) => r.error },
  { label: 'input_tokens', value: num('input_tokens') },
  { label: 'output_tokens', value: num('output_tokens') },
  { label: 'reasoning_tokens', value: num('reasoning_tokens') },
  { label: 'input_cost_usd', value: (r) => usd(r.input_cost) },
  { label: 'output_cost_usd', value: (r) => usd(r.output_cost) },
  { label: 'reasoning_cost_usd', value: (r) => usd(r.reasoning_cost) },
  { label: 'total_cost_usd', value: (r) => usd(r.input_cost + r.output_cost + r.reasoning_cost) },
];

const exportBucket = (period) => (periodSpan(period) <= 3600000 ? '5m' : periodSpan(period) <= 86400000 ? '1h' : '1d');
const EXPORT_LABELS = { '5m': 'Usage every 5 minutes', '1h': 'Usage per hour', '1d': 'Usage per day' };

// the series of several timeseries results, merged bucket by bucket under `<prefix><series name>`
function mergeSeries(results) {
  const rows = new Map();
  results.forEach(([prefix, suffix, res]) =>
    seriesOf(res).forEach((s) =>
      s.points.forEach((p) => {
        const row = rows.get(p.ts) || { ts: p.ts };
        row[`${prefix}${s.name}${suffix}`] = Number(p.value) || 0;
        rows.set(p.ts, row);
      })
    )
  );
  const fields = ['success', 'error', 'input_tokens', 'output_tokens', 'reasoning_tokens', 'input_cost', 'output_cost', 'reasoning_cost'];
  return [...rows.values()].sort((a, b) => a.ts - b.ts).map((row) => Object.fromEntries([['ts', row.ts], ...fields.map((f) => [f, row[f] || 0])]));
}

// impacts are tiny per call: pick the unit that keeps a readable number
const scaled = (units) => (v) => {
  const n = Number(v) || 0;
  if (n === 0) return `0 ${(units.find(([f]) => f === 1) || units[0])[1]}`;
  const [factor, unit] = units.find(([f]) => Math.abs(n) >= f) || units[units.length - 1];
  const x = n / factor;
  return `${x >= 100 ? x.toFixed(0) : x >= 10 ? x.toFixed(1) : x.toFixed(2)} ${unit}`;
};
const fmtGrams = scaled([[1000000, 't'], [1000, 'kg'], [1, 'g'], [0.001, 'mg']]);
const fmtWh = scaled([[1000000, 'MWh'], [1000, 'kWh'], [1, 'Wh'], [0.001, 'mWh']]);
const fmtLiters = scaled([[1000, 'm³'], [1, 'L'], [0.001, 'mL']]);

// EcoLogits estimates: emissions (electricity drawn while inferring, and the share of the hardware manufacturing
// the calls used up), energy and water
function ImpactSection({ q, deps, bucket }) {
  const data = useAsync(async () => {
    const [gwp, energy, water, per1k, overTime, byModel, efficiency] = await Promise.all([
      q('cloudapim_llm_gwp_total', { compare: true }),
      q('cloudapim_llm_energy_total', { compare: true }),
      q('cloudapim_llm_wcf_total', { compare: true }),
      q('cloudapim_llm_gwp_per_1k_tokens', { compare: true }),
      q('cloudapim_llm_gwp_over_time'),
      q('cloudapim_llm_gwp_by_model', { params: { top_n: 8 } }),
      q('cloudapim_llm_gwp_per_1k_output_tokens_by_model', { params: { top_n: 50 } }),
    ]);
    return { gwp, energy, water, per1k, overTime, byModel, efficiency };
  }, deps);
  const d = data.data;
  if (!d) return data.error ? <ErrorAlert error={data.error} /> : null;
  const models = itemsOf(d.byModel);
  const efficiencyOf = (model) => (itemsOf(d.efficiency).find((e) => e.key === model) || {}).value;
  const max = Math.max(0, ...models.map((m) => Number(m.value) || 0));
  const points = totalPoints(d.overTime);
  return (
    <div className="stack tight">
      <div>
        <h2>Environmental impact</h2>
        <p className="muted small" style={{ marginTop: 4 }}>
          Estimated with EcoLogits for the models it knows: the electricity drawn while inferring and the share of the hardware manufacturing the calls used up.
        </p>
      </div>
      <div className="grid cols-4">
        <Kpi label="Emissions (CO2eq)" value={scalarOf(d.gwp)} previous={compareOf(d.gwp)} format={fmtGrams} points={points} inverse />
        <Kpi label="Energy" value={scalarOf(d.energy)} previous={compareOf(d.energy)} format={fmtWh} points={[]} inverse />
        <Kpi label="Water" value={scalarOf(d.water)} previous={compareOf(d.water)} format={fmtLiters} points={[]} inverse />
        <Kpi label="CO2eq per 1k tokens" value={scalarOf(d.per1k)} previous={compareOf(d.per1k)} format={fmtGrams} points={[]} inverse />
      </div>
      <div className="grid cols-2">
        <div className="card">
          <h3 style={{ marginBottom: 12 }}>Emissions over time</h3>
          <StackedBars series={seriesOf(d.overTime)} bucket={bucket} format={fmtGrams} empty="No impact estimated in this period" />
        </div>
        <div className="card">
          <h3 style={{ marginBottom: 4 }}>Emissions by model</h3>
          <p className="muted small" style={{ marginBottom: 8 }}>
            With the CO2eq of every thousand generated tokens: the same answer, a very different footprint.
          </p>
          {models.length === 0 && <p className="muted small">No impact estimated in this period.</p>}
          <div className="rank">
            {models.map((m, i) => (
              <div key={m.key} className="item">
                <span className="pos">{i + 1}</span>
                <div className="grow" style={{ minWidth: 0 }}>
                  <div className="row between">
                    <span className="mono truncate small">{m.label || m.key}</span>
                    <span className="small">
                      {fmtGrams(m.value)}
                      {efficiencyOf(m.key) !== undefined && <span className="faint"> · {fmtGrams(efficiencyOf(m.key))} / 1k tok</span>}
                    </span>
                  </div>
                  <div className="bar">
                    <i style={{ width: `${max ? ((Number(m.value) || 0) / max) * 100 : 0}%` }} />
                  </div>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}

export function ActivityPage() {
  const { workspace } = useWorkspace();
  // the filters live in the url (`?apikey=<client id>&user=<email>&period=24h&auto=true&every=30`) so any page can
  // link to the activity of one key or one user, and a view can be shared as it is
  const [query, setFilters] = useQueryState();
  const { period, refresh: reload, setPeriod, setRefresh: setReload } = useTimeView('activity', query, setFilters, DEFAULT_PERIOD);
  const apikey = query.apikey || '';
  const user = query.user || '';
  const tab = TABS.some((t) => t.value === query.tab) ? query.tab : 'overview';
  // a key and a user combine: the keys a user owns count for them. The tabs keep their own settings in the url too
  const [refresh, setRefresh] = useState(0);
  const [exporting, setExporting] = useState(false);
  const toast = useToast();
  const keys = useAsync(() => listApikeys(workspace.id), [workspace.id]);
  // the users that called the workspace in the period, whatever the user filter, to fill the picker
  const users = useAsync(
    () =>
      runQuery(workspace.id, 'cloudapim_llm_users_table', { period, apikey: apikey || undefined, params: { top_n: 100 } })
        .then(itemsOf)
        .catch(() => []),
    [workspace.id, period, apikey, refresh]
  );

  const opts = { period, apikey: apikey || undefined, user: user || undefined, nocache: refresh > 0, refresh };
  const q = (id, extra = {}) => runQuery(workspace.id, id, { ...opts, ...extra });
  const clientIdOf = (name) => ((keys.data || []).find((k) => k.clientName === name) || (keys.data || []).find((k) => k.clientId === name) || {}).clientId;
  const keyName = (clientId) => ((keys.data || []).find((k) => k.clientId === clientId) || { clientName: clientId }).clientName;
  const userOptions = [...new Set([...(users.data || []).map((u) => u.user), ...(user ? [user] : [])])].map((u) => ({ value: u, label: u }));

  const kpis = useAsync(async () => {
    if (tab !== 'overview') return null;
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
  }, [workspace.id, period, apikey, user, refresh, tab]);

  const charts = useAsync(async () => {
    if (tab !== 'overview') return null;
    const [calls, tokensTs, cacheTs, latencyTs, models, apikeysTable, usersTable] = await Promise.all([
      q('cloudapim_llm_requests_over_time'),
      q('cloudapim_llm_tokens_over_time'),
      q('cloudapim_llm_cache_over_time'),
      q('cloudapim_llm_latency_percentiles_over_time'),
      q('cloudapim_llm_models_table', { params: { top_n: 20 } }),
      q('cloudapim_llm_apikeys_table', { params: { top_n: 20 } }),
      q('cloudapim_llm_users_table', { params: { top_n: 20 } }),
    ]);
    return { calls, tokensTs, cacheTs, latencyTs, models, apikeysTable, usersTable };
  }, [workspace.id, period, apikey, user, refresh, tab]);
  const deps = [workspace.id, period, apikey, user, refresh];
  // the metric of each usage card is in the url, `?usage_model=spend`
  const usageMetric = (dimension) => ({ metric: query[`usage_${dimension}`], onMetric: (v) => setFilters({ [`usage_${dimension}`]: v === 'tokens' ? '' : v }) });

  const exportUsage = async (kind) => {
    setExporting(true);
    const name = (what) => exportName('activity', workspace.slug, what, periodSlug(period), apikey && keyName(apikey), user);
    try {
      if (kind === 'time') {
        const bucket = exportBucket(period);
        const [calls, tokens, cost] = await Promise.all([
          q('cloudapim_llm_requests_over_time', { bucket }),
          q('cloudapim_llm_tokens_over_time', { bucket }),
          q('cloudapim_llm_cost_over_time', { bucket }),
        ]);
        downloadCsv(name(`per-${bucket}`), TIME_COLUMNS, mergeSeries([['', '', calls], ['', '_tokens', tokens], ['', '_cost', cost]]));
      } else {
        const exp = USAGE_EXPORTS.find((e) => e.id === kind);
        const rows = itemsOf(await q(exp.query, { params: { top_n: 1000 } }));
        downloadCsv(name(exp.id), exp.columns, rows);
      }
    } catch (e) {
      toast.error(e);
    } finally {
      setExporting(false);
    }
  };

  const noExporter = [kpis.error, charts.error].some((e) => e instanceof NoExporterError);
  const k = kpis.data;
  const c = charts.data;
  const bucket = c && c.calls && c.calls.meta ? c.calls.meta.bucket : undefined;

  return (
    <div className="content wide" style={{ maxWidth: 1400 }}>
      <PageHeader title="Activity" description="Usage of this workspace across models, API keys and users.">
        <Select
          className="sm"
          value={apikey}
          onChange={(v) => setFilters({ apikey: v })}
          placeholder="All API keys"
          options={[
            ...(keys.data || []).map((key) => ({ value: key.clientId, label: key.clientName })),
            ...(apikey && keys.data && !keys.data.some((k) => k.clientId === apikey) ? [{ value: apikey, label: apikey }] : []),
          ]}
        />
        <Select className="sm" value={user} onChange={(v) => setFilters({ user: v })} placeholder="All users" options={userOptions} />
        <PeriodPicker value={period} onChange={setPeriod} />
        <RefreshControl
          {...reload}
          onChange={setReload}
          onRefresh={() => setRefresh((r) => r + 1)}
          busy={tab === 'overview' && (kpis.loading || charts.loading)}
          loadedAt={tab === 'overview' ? charts.loadedAt : null}
        />
        <MenuButton
          icon="download"
          label={exporting ? 'Exporting…' : 'Export CSV'}
          title="Download the usage of the period, with the filters of the page"
          disabled={exporting}
          minWidth={230}
          items={[
            ...USAGE_EXPORTS.map((e) => ({ label: e.label, onClick: () => exportUsage(e.id) })),
            { label: EXPORT_LABELS[exportBucket(period)], onClick: () => exportUsage('time') },
          ]}
        />
      </PageHeader>

      {(apikey || user) && (
        <div className="filter-chips">
          <span className="muted small">Showing the usage of</span>
          {apikey && (
            <button className="chip" onClick={() => setFilters({ apikey: '' })} title="Remove this filter">
              <Icon name="key" />
              {keyName(apikey)}
              <Icon name="x" />
            </button>
          )}
          {user && (
            <>
              <button className="chip" onClick={() => setFilters({ user: '' })} title="Remove this filter">
                {user}
                <Icon name="x" />
              </button>
              <Link className="btn sm ghost" to={`/workspaces/${workspace.id}/users/${encodeURIComponent(user)}?period=${encodeURIComponent(period)}`} title={`The keys, budgets and yearly activity of ${user}`}>
                <Icon name="user" />
                Profile
              </Link>
            </>
          )}
          <button className="btn sm ghost" onClick={() => setFilters({ apikey: '', user: '' })}>
            Clear filters
          </button>
        </div>
      )}

      <Tabs tabs={TABS} value={tab} onChange={(v) => setFilters({ tab: v }, { push: true })} />

      {tab === 'trends' && <TrendsTab workspace={workspace} opts={opts} period={period} user={user} apikey={apikey} query={query} setQuery={setFilters} />}
      {tab === 'explore' && <ExploreTab workspace={workspace} query={query} setQuery={setFilters} opts={opts} />}
      {tab === 'guardrails' && <GuardrailsTab workspace={workspace} opts={opts} />}
      {tab === 'mcp' && <McpTab workspace={workspace} opts={opts} />}
      {tab !== 'overview' ? null : noExporter ? (
        <div className="stack">
          <div className="alert info">
            Usage analytics need an active <b>user analytics exporter</b> (PostgreSQL) in Otoroshi. Create one in{' '}
            <a className="link" href="/bo/dashboard/exporters" target="_blank" rel="noreferrer">
              data exporters
            </a>{' '}
            and every call of this workspace will show up here. Budgets below are live counters and work without it.
          </div>
          <BudgetsCard workspace={workspace} />
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
              <div className="grid cols-2">
                <ConsumersCard
                  title="Usage by API key"
                  description={user ? `The API keys of ${user}. Click a key to only show its usage.` : 'Click a key to only show its usage.'}
                  // the table is keyed by key name, the filter needs the client id
                  items={itemsOf(c.apikeysTable).map((i) => ({ ...i, label: i.apikey || i.key, value: clientIdOf(i.apikey || i.key) }))}
                  active={apikey}
                  onPick={(v) => v && setFilters({ apikey: v === apikey ? '' : v })}
                  empty="No call made with an API key in this period."
                />
                <ConsumersCard
                  title="Usage by user"
                  description="People chatting from AI Studio and owners of API keys. Click a user to only show their usage."
                  items={itemsOf(c.usersTable).map((i) => ({ ...i, label: i.user || i.key, value: i.user || i.key }))}
                  active={user}
                  onPick={(v) => setFilters({ user: v === user ? '' : v })}
                  empty="No call attributed to a user in this period. Calls from the AI Studio chat count for the person chatting, calls made with an API key count for its owner. Workspace keys have no owner."
                />
              </div>

              <UsageCard title="Usage by model" dimension="model" run={q} deps={deps} bucket={bucket} {...usageMetric('model')} />
              <div className="grid cols-2">
                <UsageCard title="Usage by API key over time" dimension="apikey" run={q} deps={deps} bucket={bucket} {...usageMetric('apikey')} />
                <UsageCard title="Usage by user over time" dimension="user" run={q} deps={deps} bucket={bucket} empty="No call attributed to a user in this period" {...usageMetric('user')} />
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

              <ImpactSection q={q} deps={deps} bucket={bucket} />

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
          <BudgetsCard workspace={workspace} />
        </div>
      )}
    </div>
  );
}
