import { useMemo, useState } from 'react';
import { useWorkspace } from '../App';
import { CopyButton, Drawer, Empty, ErrorAlert, Field, Loading, NumberInput, PageHeader, Segmented, Tabs, useAsync } from '../components/ui';
import { Icon } from '../components/icons';
import { ModelDetails, ModelFacts, ModelLabels } from '../components/modelinfo';
import { listWorkspaceModels } from '../lib/models';
import {
  capabilitiesOf,
  CAPABILITIES,
  chatUnavailableReason,
  completionPrice,
  contextOf,
  countBy,
  ENDPOINT_LABELS,
  endpointsOf,
  estimateCost,
  fmtPrice,
  fmtTokens,
  hasCost,
  KIND_LABELS,
  KIND_ORDER,
  kindsOf,
  promptPrice,
} from '../lib/modelmeta';
import { HealthDetails, HealthDot, HealthSummary } from '../components/health';
import { Playground } from '../components/playground';
import { playgroundsOf } from '../lib/playgrounds';
import { PERIODS } from '../lib/analytics';
import { fmtCost, fmtInt, fmtMs } from '../lib/format';
import { attempted, fmtSpeed, fmtSuccess, HEALTH, healthIndex, healthNumber, healthOfModel, loadHealth, statusOf } from '../lib/health';
import { Link, useRouter } from '../lib/router';

const CONTEXTS = [
  { value: 0, label: 'Any' },
  { value: 32000, label: '32K+' },
  { value: 128000, label: '128K+' },
  { value: 200000, label: '200K+' },
  { value: 1000000, label: '1M+' },
];

const SORTS = [
  { value: 'name', label: 'Name' },
  { value: 'price-asc', label: 'Cheapest first' },
  { value: 'price-desc', label: 'Most expensive first' },
  { value: 'context', label: 'Largest context first' },
];

// typical requests, to start an estimate from
const WORKLOADS = [
  { id: 'chat', label: 'Chat message', input: 1000, output: 400 },
  { id: 'rag', label: 'RAG answer', input: 8000, output: 500 },
  { id: 'agent', label: 'Agent step', input: 30000, output: 1000 },
  { id: 'summary', label: 'Summary', input: 20000, output: 800 },
];

const ESTIMATE_KEY = 'ai-studio.models.estimate';
const DEFAULT_ESTIMATE = { on: false, input: 1000, output: 400, requests: 10000, cached: 0 };

function loadEstimate() {
  try {
    return { ...DEFAULT_ESTIMATE, ...(JSON.parse(window.localStorage.getItem(ESTIMATE_KEY)) || {}) };
  } catch (e) {
    return DEFAULT_ESTIMATE;
  }
}

function EstimatePanel({ value, onChange, cheapest, estimated, total }) {
  return (
    <div className="card estimate-panel">
      <div className="row between wrap top">
        <div>
          <h3>Cost estimate</h3>
          <p className="muted small">What a workload costs on each model at its list price. Reasoning tokens are billed as output tokens.</p>
        </div>
        <div className="picks">
          {WORKLOADS.map((w) => (
            <button
              key={w.id}
              className={`pick ${value.input === w.input && value.output === w.output ? 'active' : ''}`}
              title={`${fmtInt(w.input)} input and ${fmtInt(w.output)} output tokens per request`}
              onClick={() => onChange({ input: w.input, output: w.output })}
            >
              {w.label}
            </button>
          ))}
        </div>
      </div>
      <div className="estimate-grid">
        <Field label="Input tokens per request">
          <NumberInput value={value.input} min="0" step="100" onChange={(v) => onChange({ input: v ?? 0 })} />
        </Field>
        <Field label="Output tokens per request">
          <NumberInput value={value.output} min="0" step="100" onChange={(v) => onChange({ output: v ?? 0 })} />
        </Field>
        <Field label="Requests per month">
          <NumberInput value={value.requests} min="0" step="1000" onChange={(v) => onChange({ requests: v ?? 0 })} />
        </Field>
        <Field label="Cached input (%)" hint="Read from the prompt cache, for the models that price it.">
          <NumberInput value={value.cached} min="0" max="100" step="10" onChange={(v) => onChange({ cached: Math.min(100, Math.max(0, v ?? 0)) })} />
        </Field>
      </div>
      <p className="small muted" style={{ margin: 0 }}>
        {cheapest ? (
          <>
            Cheapest: <b className="mono">{cheapest.model.id}</b> at <b>{fmtCost(cheapest.estimate.total)}</b> a month. {estimated} of {total} models have a token price.
          </>
        ) : (
          'None of these models has a token price.'
        )}
      </p>
    </div>
  );
}

function EstimateLine({ estimate }) {
  if (!estimate) return <div className="estimate-line muted small">No token price to estimate</div>;
  return (
    <div className="estimate-line small">
      ≈ <b>{fmtCost(estimate.total)}</b> a month · {fmtCost(estimate.perRequest)} a request
      {estimate.inputOnly && <span className="faint"> · input only</span>}
    </div>
  );
}

const EMPTY_FILTERS = { q: '', kinds: [], capabilities: [], endpoints: [], cost: '', context: 0, providers: [], health: [] };

// sorts on the calls of the period, when the workspace has analytics
const HEALTH_SORTS = [
  { value: 'calls', label: 'Most used first' },
  { value: 'latency', label: 'Lowest latency first' },
  { value: 'speed', label: 'Fastest generation first' },
];

const HEALTH_PERIODS = PERIODS.filter((p) => ['24h', '7d', '30d'].includes(p.value));

// a blended price, 1 input token for 3 output tokens, to sort by price
function blended(m) {
  const input = promptPrice(m);
  const output = completionPrice(m);
  if (input === null && output === null) return null;
  return (input || 0) + 3 * (output || 0);
}

// every facet but `except` (the counts of a facet are the ones its other filters leave)
function matches(m, f, except, healthOf) {
  const needle = f.q.trim().toLowerCase();
  if (needle && !m.id.toLowerCase().includes(needle) && !(m.model || '').toLowerCase().includes(needle)) return false;
  if (except !== 'kinds' && f.kinds.length && !kindsOf(m).some((k) => f.kinds.includes(k))) return false;
  if (except !== 'capabilities' && f.capabilities.length) {
    const caps = capabilitiesOf(m).map((c) => c.id);
    if (!f.capabilities.every((c) => caps.includes(c))) return false;
  }
  if (except !== 'endpoints' && f.endpoints.length && !endpointsOf(m).some((e) => f.endpoints.includes(e))) return false;
  if (except !== 'cost' && f.cost && (f.cost === 'priced') !== hasCost(m)) return false;
  if (except !== 'context' && f.context && (contextOf(m) || 0) < f.context) return false;
  if (except !== 'providers' && f.providers.length && !f.providers.includes(m.provider)) return false;
  if (except !== 'health' && f.health.length && !f.health.includes(statusOf(healthOf(m)))) return false;
  return true;
}

function toggle(list, value, on) {
  return on ? [...list, value] : list.filter((v) => v !== value);
}

function FacetChecks({ options, value, counts, onChange }) {
  return options.map((o) => (
    <label key={o.value} className={`check facet ${counts[o.value] ? '' : 'empty-facet'}`} title={o.title}>
      <input type="checkbox" checked={value.includes(o.value)} onChange={(e) => onChange(toggle(value, o.value, e.target.checked))} />
      <span className="grow">{o.label}</span>
      <span className="count">{counts[o.value] || 0}</span>
    </label>
  ));
}

// Chat for a chat model, and for the others the playground of what they do: an image model draws, an
// embedding model vectorizes, an audio model speaks or transcribes… A model that can do neither says why.
function TryButton({ model, workspace, providers, navigate, onPlayground }) {
  const reason = chatUnavailableReason(model);
  const playgrounds = reason ? playgroundsOf(model, providers) : [];
  if (playgrounds.length > 0) {
    return (
      <button
        className="btn sm"
        title={`Try this model: ${playgrounds.map((p) => p.label.toLowerCase()).join(', ')}`}
        onClick={(e) => {
          e.stopPropagation();
          onPlayground(model);
        }}
      >
        <Icon name="play" size={13} />
        Playground
      </button>
    );
  }
  // a model the studio cannot use says why, unless it is not a chat model at all and has nothing to try
  if (reason && model.modality !== 'text') return null;
  return (
    <button
      className="btn sm"
      disabled={!!reason}
      title={reason || 'Chat with this model'}
      onClick={(e) => {
        e.stopPropagation();
        navigate(`/workspaces/${workspace.id}/chat?model=${encodeURIComponent(model.id)}`);
      }}
    >
      Chat
    </button>
  );
}

export function ModelsPage() {
  const { workspace } = useWorkspace();
  const { navigate } = useRouter();
  const [force, setForce] = useState(0);
  const [filters, setFilters] = useState(EMPTY_FILTERS);
  const [estimate, setEstimateState] = useState(loadEstimate);
  const [sort, setSort] = useState(() => (estimate.on ? 'estimate' : 'name'));
  const [view, setView] = useState('cards');
  const [selected, setSelected] = useState(null);
  // the drawer of a model opens on its details, or straight on its playground
  const [tab, setTab] = useState('details');
  const openModel = (model) => {
    setTab('details');
    setSelected(model);
  };
  const openPlayground = (model) => {
    setTab('playground');
    setSelected(model);
  };
  const setEstimate = (patch) =>
    setEstimateState((e) => {
      const next = { ...e, ...patch };
      try {
        window.localStorage.setItem(ESTIMATE_KEY, JSON.stringify(next));
      } catch (err) {}
      return next;
    });
  const toggleEstimate = () => {
    setEstimate({ on: !estimate.on });
    if (!estimate.on) setSort('estimate');
    else if (sort === 'estimate') setSort('name');
  };
  const estimateOf = (m) => (estimate.on ? estimateCost(m, { ...estimate, cached: estimate.cached / 100 }) : null);
  const data = useAsync(() => listWorkspaceModels(workspace, force > 0), [workspace.id, force]);
  const [healthPeriod, setHealthPeriod] = useState('7d');
  // null when the workspace has no analytics
  const health = useAsync(() => loadHealth(workspace.id, healthPeriod, 'model'), [workspace.id, healthPeriod, force]);
  const hasHealth = !!health.data;
  const healthByModel = useMemo(() => healthIndex(health.data), [health.data]);
  const healthOf = (m) => healthOfModel(healthByModel, m);

  const models = (data.data && data.data.models) || [];
  const infos = (data.data && data.data.providers) || [];
  const errors = infos.filter((p) => p.error);
  const set = (patch) => setFilters((f) => ({ ...f, ...patch }));
  const active = JSON.stringify(filters) !== JSON.stringify(EMPTY_FILTERS);

  const facets = useMemo(() => {
    const keep = (except) => models.filter((m) => matches(m, filters, except, healthOf));
    const priced = keep('cost');
    return {
      kinds: countBy(keep('kinds'), kindsOf),
      capabilities: countBy(keep('capabilities'), (m) => capabilitiesOf(m).map((c) => c.id)),
      endpoints: countBy(keep('endpoints'), endpointsOf),
      cost: { priced: priced.filter(hasCost).length, unpriced: priced.filter((m) => !hasCost(m)).length },
      providers: countBy(keep('providers'), (m) => [m.provider]),
      health: countBy(keep('health'), (m) => [statusOf(healthOf(m))]),
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [models, filters, healthByModel]);

  const kindOptions = KIND_ORDER.filter((k) => models.some((m) => kindsOf(m).includes(k))).map((k) => ({ value: k, label: KIND_LABELS[k] }));
  const capabilityOptions = CAPABILITIES.filter((c) => models.some((m) => c.test(m))).map((c) => ({ value: c.id, label: c.label, title: c.title }));
  const endpointOptions = Object.keys(ENDPOINT_LABELS)
    .filter((e) => models.some((m) => endpointsOf(m).includes(e)))
    .map((e) => ({ value: e, label: ENDPOINT_LABELS[e] }));
  const providerOptions = [...new Set(models.map((m) => m.provider))].sort().map((p) => ({ value: p, label: p }));

  const filtered = useMemo(() => {
    const list = models.filter((m) => matches(m, filters, undefined, healthOf));
    const byName = (a, b) => a.id.localeCompare(b.id);
    // the models without a value last, whatever the direction
    const nullsLast = (a, b, fn, desc = false) => {
      const x = fn(a);
      const y = fn(b);
      if (x === null && y === null) return byName(a, b);
      if (x === null) return 1;
      if (y === null) return -1;
      return (desc ? y - x : x - y) || byName(a, b);
    };
    if (sort === 'price-asc') return list.sort((a, b) => nullsLast(a, b, blended));
    if (sort === 'price-desc') return list.sort((a, b) => nullsLast(a, b, blended, true));
    if (sort === 'context') return list.sort((a, b) => nullsLast(a, b, contextOf, true));
    const measured = (fn) => (m) => {
      const h = healthOf(m);
      return h && attempted(h) > 0 ? fn(h) : null;
    };
    if (sort === 'calls') return list.sort((a, b) => nullsLast(a, b, measured((h) => healthNumber(h.calls)), true));
    if (sort === 'latency') return list.sort((a, b) => nullsLast(a, b, measured((h) => healthNumber(h.p50_ms))));
    if (sort === 'speed') return list.sort((a, b) => nullsLast(a, b, measured((h) => healthNumber(h.tokens_per_second)), true));
    if (sort === 'estimate') {
      const totals = new Map(list.map((m) => [m, estimateOf(m)]));
      // a workload that generates text: the models that only read their input (embeddings, moderation) come after
      const secondary = (m) => Number(!!(estimate.output > 0 && totals.get(m) && totals.get(m).inputOnly));
      return list.sort((a, b) => secondary(a) - secondary(b) || nullsLast(a, b, (m) => (totals.get(m) ? totals.get(m).total : null)));
    }
    return list.sort(byName);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [models, filters, sort, estimate, healthByModel]);

  const shown = filtered.slice(0, 500);
  const pricedCount = filtered.filter(hasCost).length;
  const estimates = useMemo(() => new Map(filtered.map((m) => [m, estimateOf(m)])), [filtered, estimate]); // eslint-disable-line react-hooks/exhaustive-deps
  const estimatedModels = filtered.filter((m) => estimates.get(m));
  const generating = estimatedModels.filter((m) => !(estimate.output > 0 && estimates.get(m).inputOnly));
  const cheapest = (generating.length ? generating : estimatedModels).reduce((best, m) => (!best || estimates.get(m).total < best.estimate.total ? { model: m, estimate: estimates.get(m) } : best), null);
  const sorts = [...SORTS, ...(estimate.on ? [{ value: 'estimate', label: 'Cheapest for this workload' }] : []), ...(hasHealth ? HEALTH_SORTS : [])];

  return (
    <div className="content wide">
      <PageHeader title="Models" description={`Every model reachable through ${workspace.baseUrl}. Use the id as the \`model\` field of your requests.`}>
        <button className="btn" onClick={() => setForce((f) => f + 1)} disabled={data.loading}>
          <Icon name="refresh" />
          Refresh
        </button>
      </PageHeader>
      <ErrorAlert error={data.error} />
      {errors.length > 0 && (
        <div className="alert warning mb">
          {errors.map((e) => (
            <div key={e.id}>
              <b>{e.name || e.id}</b> could not list its models: {typeof e.error === 'string' ? e.error : 'unknown error'}.
              {e.default_model ? (
                <>
                  {' '}
                  Only its default model, <span className="mono">{e.default_model}</span>, is listed below.
                </>
              ) : null}
            </div>
          ))}
          <div style={{ marginTop: 6 }}>
            Check the connection in <Link className="link" to={`/workspaces/${workspace.id}/providers`}>Providers (BYOK)</Link>.
          </div>
        </div>
      )}
      <div className="catalog">
        <div className="filters">
          <div className="field">
            <input className="input search" placeholder="Search models" value={filters.q} onChange={(e) => set({ q: e.target.value })} />
          </div>
          {active && (
            <button className="btn sm ghost clear-filters" onClick={() => setFilters(EMPTY_FILTERS)}>
              <Icon name="x" size={13} />
              Clear filters
            </button>
          )}
          {kindOptions.length > 0 && (
            <div className="field">
              <label>Type</label>
              <FacetChecks options={kindOptions} value={filters.kinds} counts={facets.kinds} onChange={(kinds) => set({ kinds })} />
            </div>
          )}
          {capabilityOptions.length > 0 && (
            <div className="field">
              <label>Capabilities</label>
              <FacetChecks options={capabilityOptions} value={filters.capabilities} counts={facets.capabilities} onChange={(capabilities) => set({ capabilities })} />
            </div>
          )}
          {endpointOptions.length > 0 && (
            <div className="field">
              <label title="The OpenAI API endpoints the provider serves the model on">API</label>
              <FacetChecks options={endpointOptions} value={filters.endpoints} counts={facets.endpoints} onChange={(endpoints) => set({ endpoints })} />
            </div>
          )}
          <div className="field">
            <label>Pricing</label>
            {[
              { value: '', label: 'All', count: facets.cost.priced + facets.cost.unpriced },
              { value: 'priced', label: 'Known price', count: facets.cost.priced, title: 'Calls are priced and count against dollar budgets' },
              { value: 'unpriced', label: 'No known price', count: facets.cost.unpriced },
            ].map((o) => (
              <label key={o.value} className="check facet" title={o.title}>
                <input type="radio" checked={filters.cost === o.value} onChange={() => set({ cost: o.value })} />
                <span className="grow">{o.label}</span>
                <span className="count">{o.count}</span>
              </label>
            ))}
          </div>
          <div className="field">
            <label>Context window</label>
            <div className="picks">
              {CONTEXTS.map((c) => (
                <button key={c.value} className={`pick ${filters.context === c.value ? 'active' : ''}`} onClick={() => set({ context: c.value })}>
                  {c.label}
                </button>
              ))}
            </div>
          </div>
          {hasHealth && (
            <div className="field">
              <label title="How the models behaved in the period">Health</label>
              <select className="sm" value={healthPeriod} onChange={(e) => setHealthPeriod(e.target.value)} aria-label="Health period" style={{ marginBottom: 6 }}>
                {HEALTH_PERIODS.map((p) => (
                  <option key={p.value} value={p.value}>
                    {p.label}
                  </option>
                ))}
              </select>
              <FacetChecks options={HEALTH} value={filters.health} counts={facets.health} onChange={(v) => set({ health: v })} />
            </div>
          )}
          {providerOptions.length > 1 && (
            <div className="field">
              <label>Providers</label>
              <FacetChecks options={providerOptions} value={filters.providers} counts={facets.providers} onChange={(providers) => set({ providers })} />
            </div>
          )}
        </div>
        <div>
          <div className="row between wrap list-toolbar">
            <p className="muted small">
              {filtered.length} model{filtered.length === 1 ? '' : 's'}
              {filtered.length > 0 && ` · ${pricedCount} with a known price`}
            </p>
            <div className="row">
              <button className={`btn sm ${estimate.on ? 'active-toggle' : ''}`} onClick={toggleEstimate} title="Estimate what a workload costs on each model">
                <Icon name="wallet" />
                Estimate costs
              </button>
              <select className="sm" value={sort} onChange={(e) => setSort(e.target.value)} aria-label="Sort models">
                {sorts.map((s) => (
                  <option key={s.value} value={s.value}>
                    {s.label}
                  </option>
                ))}
              </select>
              <Segmented
                value={view}
                onChange={setView}
                options={[
                  { value: 'cards', label: 'Cards' },
                  { value: 'table', label: 'Table' },
                ]}
              />
            </div>
          </div>
          {estimate.on && data.data && models.length > 0 && (
            <EstimatePanel value={estimate} onChange={setEstimate} cheapest={cheapest} estimated={estimatedModels.length} total={filtered.length} />
          )}
          {data.loading && !data.data && <Loading label="Asking providers for their models…" />}
          {data.data && models.length === 0 && (
            <div className="card">
              <Empty title="No model yet">
                <Link className="link" to={`/workspaces/${workspace.id}/providers`}>
                  Connect a provider
                </Link>{' '}
                to expose its models here.
              </Empty>
            </div>
          )}
          {data.data && models.length > 0 && filtered.length === 0 && (
            <div className="card">
              <Empty title="No model matches these filters" action={<button className="btn" onClick={() => setFilters(EMPTY_FILTERS)}>Clear filters</button>} />
            </div>
          )}
          {view === 'cards' && (
            <div className="stack tight">
              {shown.map((m) => (
                <div key={`${m.modality}-${m.id}`} className="card model-card clickable" onClick={() => openModel(m)}>
                  <div className="row between">
                    <div className="name truncate">{m.id}</div>
                    <div className="row" onClick={(e) => e.stopPropagation()}>
                      <CopyButton text={m.id} />
                      <TryButton model={m} workspace={workspace} providers={infos} navigate={navigate} onPlayground={openPlayground} />
                    </div>
                  </div>
                  <div className="labels">
                    <ModelLabels model={m} compact />
                  </div>
                  <ModelFacts model={m} />
                  {hasHealth && attempted(healthOf(m)) > 0 && (
                    <div className="small" style={{ marginTop: 8 }}>
                      <HealthSummary health={healthOf(m)} />
                    </div>
                  )}
                  {estimate.on && <EstimateLine estimate={estimates.get(m)} />}
                </div>
              ))}
            </div>
          )}
          {view === 'table' && filtered.length > 0 && (
            <div className="card flush">
              <div className="table-wrap">
                <table className="table compact models-table">
                  <thead>
                    <tr>
                      <th>Model</th>
                      <th>Provider</th>
                      <th>Type</th>
                      <th className="num">Context</th>
                      <th className="num">Input / 1M</th>
                      <th className="num">Output / 1M</th>
                      {hasHealth && <th title="Share of the calls of the period that succeeded">Health</th>}
                      {hasHealth && <th className="num">p50</th>}
                      {hasHealth && <th className="num">Speed</th>}
                      {estimate.on && <th className="num">Per request</th>}
                      {estimate.on && <th className="num">Per month</th>}
                      <th>Capabilities</th>
                    </tr>
                  </thead>
                  <tbody>
                    {shown.map((m) => (
                      <tr key={`${m.modality}-${m.id}`} className="clickable" onClick={() => openModel(m)}>
                        <td className="mono truncate" style={{ maxWidth: 320 }} title={m.id}>
                          {m.id}
                        </td>
                        <td>{m.provider}</td>
                        <td>{kindsOf(m).map((k) => KIND_LABELS[k] || k).join(', ')}</td>
                        <td className="num">{fmtTokens(contextOf(m))}</td>
                        <td className="num">{fmtPrice(promptPrice(m))}</td>
                        <td className="num">{fmtPrice(completionPrice(m))}</td>
                        {hasHealth && (
                          <td className="nowrap">
                            {attempted(healthOf(m)) > 0 ? (
                              <span className="health-summary">
                                <HealthDot health={healthOf(m)} />
                                {fmtSuccess(healthOf(m))}
                              </span>
                            ) : (
                              <span className="faint">—</span>
                            )}
                          </td>
                        )}
                        {hasHealth && <td className="num">{attempted(healthOf(m)) > 0 && healthNumber(healthOf(m).p50_ms) !== null ? fmtMs(healthOf(m).p50_ms) : '—'}</td>}
                        {hasHealth && <td className="num nowrap">{attempted(healthOf(m)) > 0 ? fmtSpeed(healthOf(m).tokens_per_second) : '—'}</td>}
                        {estimate.on && <td className="num">{estimates.get(m) ? fmtCost(estimates.get(m).perRequest) : '—'}</td>}
                        {estimate.on && <td className="num">{estimates.get(m) ? <b>{fmtCost(estimates.get(m).total)}</b> : '—'}</td>}
                        <td className="faint small capabilities">{capabilitiesOf(m).map((c) => c.label).join(', ')}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}
          {filtered.length > 500 && <p className="muted mt">Showing the first 500 models, refine the filters to see more.</p>}
        </div>
      </div>
      <Drawer title="Model" open={!!selected} onClose={() => setSelected(null)}>
        {selected && (
          <>
            {playgroundsOf(selected, infos).length > 0 && (
              <Tabs
                value={tab}
                onChange={setTab}
                tabs={[
                  { value: 'details', label: 'Details' },
                  { value: 'playground', label: 'Playground' },
                ]}
              />
            )}
            {tab === 'playground' && playgroundsOf(selected, infos).length > 0 ? (
              <Playground model={selected} workspace={workspace} providers={infos} />
            ) : (
              <>
                <ModelDetails model={selected} baseUrl={workspace.baseUrl} />
                {hasHealth && (
                  <HealthDetails
                    health={healthOf(selected)}
                    periodLabel={(HEALTH_PERIODS.find((p) => p.value === healthPeriod) || {}).label}
                    logsUrl={`/workspaces/${workspace.id}/logs?model=${encodeURIComponent(selected.model)}&period=${healthPeriod}`}
                  />
                )}
                {selected.modality === 'text' && !chatUnavailableReason(selected) && (
                  <button className="btn primary mt" onClick={() => navigate(`/workspaces/${workspace.id}/chat?model=${encodeURIComponent(selected.id)}`)}>
                    <Icon name="message" />
                    Chat with this model
                  </button>
                )}
              </>
            )}
          </>
        )}
      </Drawer>
    </div>
  );
}
