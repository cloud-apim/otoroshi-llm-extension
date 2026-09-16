import { useMemo, useState } from 'react';
import { useWorkspace } from '../App';
import { CopyButton, Drawer, Empty, ErrorAlert, Loading, PageHeader, Segmented, useAsync } from '../components/ui';
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
  fmtPrice,
  fmtTokens,
  hasCost,
  KIND_LABELS,
  KIND_ORDER,
  kindsOf,
  promptPrice,
} from '../lib/modelmeta';
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

const EMPTY_FILTERS = { q: '', kinds: [], capabilities: [], endpoints: [], cost: '', context: 0, providers: [] };

// a blended price, 1 input token for 3 output tokens, to sort by price
function blended(m) {
  const input = promptPrice(m);
  const output = completionPrice(m);
  if (input === null && output === null) return null;
  return (input || 0) + 3 * (output || 0);
}

// every facet but `except` (the counts of a facet are the ones its other filters leave)
function matches(m, f, except) {
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

function ChatButton({ model, workspace, navigate }) {
  if (model.modality !== 'text') return null;
  const reason = chatUnavailableReason(model);
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
  const [sort, setSort] = useState('name');
  const [view, setView] = useState('cards');
  const [selected, setSelected] = useState(null);
  const data = useAsync(() => listWorkspaceModels(workspace, force > 0), [workspace.id, force]);

  const models = (data.data && data.data.models) || [];
  const infos = (data.data && data.data.providers) || [];
  const errors = infos.filter((p) => p.error);
  const set = (patch) => setFilters((f) => ({ ...f, ...patch }));
  const active = JSON.stringify(filters) !== JSON.stringify(EMPTY_FILTERS);

  const facets = useMemo(() => {
    const keep = (except) => models.filter((m) => matches(m, filters, except));
    const priced = keep('cost');
    return {
      kinds: countBy(keep('kinds'), kindsOf),
      capabilities: countBy(keep('capabilities'), (m) => capabilitiesOf(m).map((c) => c.id)),
      endpoints: countBy(keep('endpoints'), endpointsOf),
      cost: { priced: priced.filter(hasCost).length, unpriced: priced.filter((m) => !hasCost(m)).length },
      providers: countBy(keep('providers'), (m) => [m.provider]),
    };
  }, [models, filters]);

  const kindOptions = KIND_ORDER.filter((k) => models.some((m) => kindsOf(m).includes(k))).map((k) => ({ value: k, label: KIND_LABELS[k] }));
  const capabilityOptions = CAPABILITIES.filter((c) => models.some((m) => c.test(m))).map((c) => ({ value: c.id, label: c.label, title: c.title }));
  const endpointOptions = Object.keys(ENDPOINT_LABELS)
    .filter((e) => models.some((m) => endpointsOf(m).includes(e)))
    .map((e) => ({ value: e, label: ENDPOINT_LABELS[e] }));
  const providerOptions = [...new Set(models.map((m) => m.provider))].sort().map((p) => ({ value: p, label: p }));

  const filtered = useMemo(() => {
    const list = models.filter((m) => matches(m, filters));
    const byName = (a, b) => a.id.localeCompare(b.id);
    const nullsLast = (a, b, fn) => {
      const x = fn(a);
      const y = fn(b);
      if (x === null && y === null) return byName(a, b);
      if (x === null) return 1;
      if (y === null) return -1;
      return x - y || byName(a, b);
    };
    if (sort === 'price-asc') return list.sort((a, b) => nullsLast(a, b, blended));
    if (sort === 'price-desc') return list.sort((a, b) => nullsLast(b, a, blended));
    if (sort === 'context') return list.sort((a, b) => nullsLast(b, a, contextOf));
    return list.sort(byName);
  }, [models, filters, sort]);

  const shown = filtered.slice(0, 500);
  const pricedCount = filtered.filter(hasCost).length;

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
              <select className="sm" value={sort} onChange={(e) => setSort(e.target.value)} aria-label="Sort models">
                {SORTS.map((s) => (
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
                <div key={`${m.modality}-${m.id}`} className="card model-card clickable" onClick={() => setSelected(m)}>
                  <div className="row between">
                    <div className="name truncate">{m.id}</div>
                    <div className="row" onClick={(e) => e.stopPropagation()}>
                      <CopyButton text={m.id} />
                      <ChatButton model={m} workspace={workspace} navigate={navigate} />
                    </div>
                  </div>
                  <div className="labels">
                    <ModelLabels model={m} compact />
                  </div>
                  <ModelFacts model={m} />
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
                      <th>Capabilities</th>
                    </tr>
                  </thead>
                  <tbody>
                    {shown.map((m) => (
                      <tr key={`${m.modality}-${m.id}`} className="clickable" onClick={() => setSelected(m)}>
                        <td className="mono truncate" style={{ maxWidth: 320 }} title={m.id}>
                          {m.id}
                        </td>
                        <td>{m.provider}</td>
                        <td>{kindsOf(m).map((k) => KIND_LABELS[k] || k).join(', ')}</td>
                        <td className="num">{fmtTokens(contextOf(m))}</td>
                        <td className="num">{fmtPrice(promptPrice(m))}</td>
                        <td className="num">{fmtPrice(completionPrice(m))}</td>
                        <td className="faint small">{capabilitiesOf(m).map((c) => c.label).join(', ')}</td>
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
            <ModelDetails model={selected} baseUrl={workspace.baseUrl} />
            {selected.modality === 'text' && !chatUnavailableReason(selected) && (
              <button className="btn primary mt" onClick={() => navigate(`/workspaces/${workspace.id}/chat?model=${encodeURIComponent(selected.id)}`)}>
                <Icon name="message" />
                Chat with this model
              </button>
            )}
          </>
        )}
      </Drawer>
    </div>
  );
}
