import { useMemo, useState } from 'react';
import { HealthSummary } from '../components/health';
import { combine, healthIndex, loadHealth } from '../lib/health';
import { useWorkspace } from '../App';
import {
  Badge,
  Empty,
  ErrorAlert,
  Field,
  JsonInput,
  Loading,
  Modal,
  NumberInput,
  PageHeader,
  SecretInput,
  Select,
  Tabs,
  TextInput,
  Toggle,
  useAsync,
  useConfirm,
  useToast,
} from '../components/ui';
import { Icon } from '../components/icons';
import { Link } from '../lib/router';
import {
  connectionName,
  deleteConnection,
  fetchProviderModels,
  fieldsFromConnection,
  listConnections,
  MODALITY_LABELS,
  newConnection,
  saveConnection,
} from '../lib/connections';
import { listWorkspaceModels, loadCatalog } from '../lib/models';
import { initials } from '../lib/format';
import { ModelLabels, PriceSummary } from '../components/modelinfo';
import { contextOf, fitsCapability, fmtPrice, fmtTokens, hasCost, KIND_LABELS, KIND_ORDER, kindsOf, kindsSummary, metaOf, perMillion } from '../lib/modelmeta';

// a short description of a model, next to its id in the suggestions
function suggestionLabel(model) {
  const parts = [];
  const context = contextOf(model);
  if (context) parts.push(`${fmtTokens(context)} context`);
  const pricing = metaOf(model).pricing;
  if (pricing) parts.push(`${fmtPrice(perMillion(pricing.prompt))} in / ${fmtPrice(perMillion(pricing.completion))} out`);
  return parts.join(' · ');
}

function ModelInput({ value, onChange, models, placeholder, listId }) {
  const known = models && value ? models.find((m) => m.id === value) : null;
  return (
    <>
      <input className="input mono" list={listId} value={value || ''} placeholder={placeholder} onChange={(e) => onChange(e.target.value)} />
      {models && models.length > 0 && (
        <datalist id={listId}>
          {models.map((m) => (
            <option key={m.id} value={m.id} label={suggestionLabel(m)} />
          ))}
        </datalist>
      )}
      {known && known.details && (
        <div className="model-hint">
          <ModelLabels model={known} compact />
          <div className="meta">
            {contextOf(known) && <span>{fmtTokens(contextOf(known))} context</span>}
            {metaOf(known).pricing && <PriceSummary model={known} />}
          </div>
        </div>
      )}
    </>
  );
}

// what the gateway knows of a provider kind (see ProviderInsights in catalog/modelscatalog.scala)
function insightsFacts(entry) {
  const insights = entry.insights || {};
  const catalog = insights.catalog;
  const facts = [];
  if (catalog) {
    facts.push(`${catalog.models} model${catalog.models > 1 ? 's' : ''}`);
    if (catalog.prompt_from) facts.push(`from ${fmtPrice(perMillion(catalog.prompt_from))} / 1M`);
    if (catalog.max_context) facts.push(`up to ${fmtTokens(catalog.max_context)} context`);
  }
  return facts;
}

function ProviderFacts({ entry }) {
  const insights = entry.insights || {};
  const catalog = insights.catalog;
  if (!catalog && insights.openai_compatible === undefined) return null;
  return (
    <div className="provider-facts">
      {insights.openai_compatible === true && <Badge kind="accent">OpenAI compatible API</Badge>}
      {insights.openai_compatible === false && <Badge title="The gateway translates the OpenAI requests for this provider">Native API</Badge>}
      {catalog && (
        <>
          <span>
            {catalog.models} known model{catalog.models > 1 ? 's' : ''}
            {catalog.kinds &&
              ` (${KIND_ORDER.filter((k) => catalog.kinds[k])
                .map((k) => `${catalog.kinds[k]} ${KIND_LABELS[k].toLowerCase()}`)
                .join(', ')})`}
          </span>
          <span>{catalog.priced} with a known price</span>
          {catalog.reasoning > 0 && <span>{catalog.reasoning} reasoning</span>}
          {catalog.max_context && <span>up to {fmtTokens(catalog.max_context)} tokens of context</span>}
          {catalog.doc && (
            <a className="link" href={catalog.doc} target="_blank" rel="noreferrer">
              Documentation
            </a>
          )}
        </>
      )}
    </div>
  );
}

export function ConnectionModal({ workspace, catalog, initial, existingNames, onClose, onSaved }) {
  const toast = useToast();
  const [conn, setConn] = useState(initial);
  const [tab, setTab] = useState('connection');
  const [saving, setSaving] = useState(false);
  const [models, setModels] = useState([]);
  const [loadingModels, setLoadingModels] = useState(false);
  const entry = catalog.find((c) => c.id === conn.kind) || { id: conn.kind, label: conn.kind, capabilities: ['text'], fields: [], models: {} };
  const isNew = !initial.entities || Object.keys(initial.entities).length === 0;
  const set = (patch) => setConn((c) => ({ ...c, ...patch }));
  const setModality = (m, patch) => setConn((c) => ({ ...c, modalities: { ...c.modalities, [m]: { ...(c.modalities[m] || {}), ...patch } } }));
  const nameTaken = existingNames.includes(conn.name) && conn.name !== initial.name;
  const anyModality = Object.values(conn.modalities).some((m) => m.enabled);
  const valid = conn.name && !nameTaken && anyModality && (!entry.token_required || conn.token || !isNew) && (!entry.base_url_required || conn.base_url);

  const changeKind = (kind) => {
    const next = catalog.find((c) => c.id === kind);
    if (!next) return;
    const fresh = newConnection(next, existingNames);
    setConn((c) => ({ ...fresh, id: c.id, entities: c.entities, token: c.token, description: c.description, name: isNew ? fresh.name : c.name }));
    setModels([]);
  };

  const loadModels = (force) => {
    setLoadingModels(true);
    fetchProviderModels(workspace.id, conn, entry, force)
      .then((list) => {
        setModels(list);
        toast.success(`${list.length} models found`);
      })
      .catch(toast.error)
      .finally(() => setLoadingModels(false));
  };

  const save = () => {
    setSaving(true);
    saveConnection(workspace.id, conn, entry)
      .then(() => {
        toast.success(isNew ? 'Provider connected' : 'Provider saved');
        onSaved();
      })
      .catch(toast.error)
      .finally(() => setSaving(false));
  };

  const textEntity = conn.entities && conn.entities.text;

  // without details (an older gateway), every model is a text model suggestion. A provider requiring known
  // costs refuses the others, they are no suggestion
  const suggestions = (cap, audioMode) => {
    if (!models.length) return null;
    if (!models.some((m) => m.details)) return cap === 'text' ? models : null;
    return models.filter((m) => fitsCapability(m, cap, audioMode) && (!conn.require_known_costs || cap !== 'text' || hasCost(m)));
  };
  const unpriced = models.filter((m) => m.details && kindsOf(m).includes('text') && !hasCost(m)).length;

  return (
    <Modal
      open
      size="wide"
      onClose={onClose}
      title={isNew ? `Connect ${entry.label}` : `Edit ${conn.name}`}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button className="btn primary" disabled={!valid || saving} onClick={save}>
            {saving ? 'Saving…' : isNew ? 'Add provider' : 'Save'}
          </button>
        </>
      }
    >
      <Tabs
        value={tab}
        onChange={setTab}
        tabs={[
          { value: 'connection', label: 'Connection' },
          { value: 'models', label: 'Models' },
          ...(textEntity || conn.modalities.text ? [{ value: 'advanced', label: 'Advanced' }] : []),
        ]}
      />
      {tab === 'connection' && <ProviderFacts entry={entry} />}
      {tab === 'connection' && (
        <div className="form-grid">
          <Field label="Provider">
            <Select value={conn.kind} onChange={changeKind} options={catalog.map((c) => ({ value: c.id, label: c.label }))} disabled={!isNew} />
          </Field>
          <Field label="Name" hint="Prefixes model ids when several providers are configured." error={nameTaken ? 'already used in this workspace' : null}>
            <TextInput value={conn.name} onChange={(v) => set({ name: connectionName(v) })} />
          </Field>
          <Field className="full" label="Base URL" hint={entry.base_url_required ? 'Required for this provider.' : 'Leave the default unless you use a proxy or a self-hosted endpoint.'}>
            <TextInput value={conn.base_url} onChange={(v) => set({ base_url: v })} placeholder={entry.base_url || 'https://...'} />
          </Field>
          {(entry.fields || []).map((f) => (
            <Field key={f.name} label={f.label}>
              <TextInput value={(conn.fields || {})[f.name]} placeholder={f.placeholder} onChange={(v) => set({ fields: { ...(conn.fields || {}), [f.name]: v } })} />
            </Field>
          ))}
          <Field label="API key" hint={isNew ? 'Stored in the gateway entity. You can also use a vault reference like ${vault://env/OPENAI_API_KEY}.' : 'A key is stored. Change it to rotate it.'}>
            <SecretInput value={conn.token} onChange={(v) => set({ token: v })} placeholder={entry.token_required ? 'sk-…' : 'optional'} />
          </Field>
          <Field label="Timeout (ms)">
            <NumberInput value={conn.timeout} onChange={(v) => set({ timeout: v })} />
          </Field>
          <Field label="Enabled" hint="Disabled providers stay configured but are not served by the workspace.">
            <Toggle value={conn.enabled !== false} onChange={(v) => set({ enabled: v })} />
          </Field>
          <Field className="full" label="Description">
            <TextInput value={conn.description} onChange={(v) => set({ description: v })} />
          </Field>
        </div>
      )}
      {tab === 'models' && (
        <div className="stack">
          <div className="row between">
            <p className="muted">Pick the capabilities this connection exposes on the workspace endpoint, and the model used when a request omits it.</p>
            {entry.capabilities.includes('text') && (
              <button className="btn sm" disabled={loadingModels || (!conn.token && entry.token_required)} onClick={() => loadModels(models.length > 0)}>
                <Icon name="refresh" />
                {loadingModels ? 'Loading…' : 'Fetch models'}
              </button>
            )}
          </div>
          {models.length > 0 && (
            <div className="alert info">
              {models.length} models found: {kindsSummary(models).join(', ')}. {models.filter(hasCost).length} with a known price. The suggestions of each capability
              only list the models fitting it.
            </div>
          )}
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th style={{ width: 150 }}>Capability</th>
                  <th style={{ width: 70 }}>Enabled</th>
                  <th>Default model</th>
                </tr>
              </thead>
              <tbody>
                {entry.capabilities.map((cap) => {
                  const mod = conn.modalities[cap] || { enabled: false, model: '' };
                  return (
                    <tr key={cap}>
                      <td>{MODALITY_LABELS[cap] || cap}</td>
                      <td>
                        <Toggle value={!!mod.enabled} onChange={(v) => setModality(cap, { enabled: v })} />
                      </td>
                      <td>
                        {cap === 'audio' ? (
                          <div className="grid cols-2">
                            {(entry.audio_modes || ['tts', 'stt']).includes('tts') && (
                              <Field hint="Text to speech">
                                <ModelInput
                                  value={mod.model}
                                  onChange={(v) => setModality(cap, { model: v })}
                                  placeholder={entry.models.audio_tts || 'tts model'}
                                  models={suggestions(cap, 'tts')}
                                  listId={`${cap}-tts`}
                                />
                              </Field>
                            )}
                            {(entry.audio_modes || ['tts', 'stt']).includes('stt') && (
                              <Field hint="Speech to text">
                                <ModelInput
                                  value={mod.stt_model}
                                  onChange={(v) => setModality(cap, { stt_model: v })}
                                  placeholder={entry.models.audio_stt || 'stt model'}
                                  models={suggestions(cap, 'stt')}
                                  listId={`${cap}-stt`}
                                />
                              </Field>
                            )}
                          </div>
                        ) : (
                          <ModelInput
                            value={mod.model}
                            onChange={(v) => setModality(cap, { model: v })}
                            placeholder={entry.models[cap] || 'model id'}
                            models={suggestions(cap)}
                            listId={`${cap}-models`}
                          />
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          <div className="setting-toggle">
            <Toggle value={!!conn.require_known_costs} onChange={(v) => set({ require_known_costs: v })} />
            <div className="grow">
              <div className="title">Require known costs</div>
              <div className="muted small">
                Calls to a model whose price cost tracking does not know are refused before they reach the provider, and such models are not listed: nothing
                escapes your dollar budgets.
                {unpriced > 0 && ` ${unpriced} of the text models found have no known price.`}
              </div>
            </div>
          </div>
        </div>
      )}
      {tab === 'advanced' && (
        <div className="stack">
          <p className="muted">Raw options of the text provider (temperature, max tokens, tools…). They are merged into every request unless the caller overrides them.</p>
          <JsonInput
            rows={14}
            value={(textEntity && textEntity.options) || { model: (conn.modalities.text || {}).model }}
            onChange={(options) =>
              setConn((c) => ({
                ...c,
                entities: { ...c.entities, text: { ...(c.entities.text || {}), options } },
                modalities: { ...c.modalities, text: { ...(c.modalities.text || {}), model: options.model || '' } },
              }))
            }
          />
        </div>
      )}
    </Modal>
  );
}

export function ProvidersPage() {
  const { workspace } = useWorkspace();
  const toast = useToast();
  const confirm = useConfirm();
  const [filter, setFilter] = useState('');
  const [capabilities, setCapabilities] = useState([]);
  const [openAiOnly, setOpenAiOnly] = useState(false);
  const [pricedOnly, setPricedOnly] = useState(false);
  const [editing, setEditing] = useState(null);
  const catalog = useAsync(() => loadCatalog(), []);
  const connections = useAsync(() => listConnections(workspace.id), [workspace.id]);
  // the models each connection serves, refreshed with the connections
  const models = useAsync(() => (connections.data ? listWorkspaceModels(workspace) : Promise.resolve(null)), [workspace.id, connections.data]);
  // how each connection behaved over the last 24 hours, null without analytics
  const health = useAsync(() => loadHealth(workspace.id, '24h', 'provider'), [workspace.id]);
  const healthByProvider = useMemo(() => healthIndex(health.data, false), [health.data]);
  const healthOf = (conn) => combine(Object.values(conn.entities || {}).map((e) => healthByProvider.get(e.id)));

  // load balancers and routers are managed in the routing page
  const list = (connections.data || []).filter((c) => !['loadbalancer', 'otoroshi'].includes(c.kind));
  const names = list.map((c) => c.name);
  const cat = catalog.data || [];

  const counts = useMemo(() => {
    const r = {};
    list.forEach((c) => (r[c.kind] = (r[c.kind] || 0) + 1));
    return r;
  }, [list]);

  const available = cat.filter((c) => {
    const insights = c.insights || {};
    if (filter && !c.label.toLowerCase().includes(filter.toLowerCase()) && !c.id.includes(filter.toLowerCase())) return false;
    if (capabilities.length && !capabilities.every((cap) => c.capabilities.includes(cap))) return false;
    if (openAiOnly && insights.openai_compatible !== true) return false;
    if (pricedOnly && !(insights.catalog && insights.catalog.priced > 0)) return false;
    return true;
  });
  const capabilityOptions = KIND_ORDER.filter((k) => cat.some((c) => c.capabilities.includes(k)));

  const modelsOf = (conn) => {
    const ids = Object.values(conn.entities || {}).map((e) => e.id);
    return ((models.data && models.data.models) || []).filter((m) => ids.includes(m.provider_id));
  };

  const openNew = (entry) => setEditing(newConnection(entry, names));
  const openEdit = (conn) => {
    const entry = cat.find((c) => c.id === conn.kind);
    setEditing({ ...conn, fields: fieldsFromConnection(conn, entry) });
  };

  const remove = (conn) => {
    confirm({ title: `Remove ${conn.name}?`, message: 'Every model entity of this connection is deleted and the workspace stops serving its models.', danger: true, confirmLabel: 'Remove' }).then((ok) => {
      if (!ok) return;
      deleteConnection(workspace.id, conn)
        .then(() => {
          toast.success('Provider removed');
          connections.reload();
        })
        .catch(toast.error);
    });
  };

  const labelOf = (kind) => (cat.find((c) => c.id === kind) || { label: kind }).label;

  return (
    <div className="content">
      <PageHeader title="Providers" description="Bring your own provider keys. Each connected provider exposes its models on this workspace's base URL.">
        <button className="btn primary" disabled={!cat.length} onClick={() => openNew(cat.find((c) => c.id === 'openai') || cat[0])}>
          Add provider
        </button>
      </PageHeader>
      <ErrorAlert error={connections.error || catalog.error} />

      <div className="card mb">
        <h2 style={{ marginBottom: 12 }}>Connected</h2>
        {connections.loading && !connections.data && <Loading />}
        {connections.data && list.length === 0 && <Empty title="No provider connected">Pick a provider below and paste its API key.</Empty>}
        {list.length > 0 && (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Provider</th>
                  <th>Capabilities</th>
                  <th>Default model</th>
                  <th>Models</th>
                  {health.data && <th title="Calls served over the last 24 hours">Health · 24h</th>}
                  <th>Key</th>
                  <th>Status</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {list.map((conn) => (
                  <tr key={conn.id}>
                    <td>{conn.name}</td>
                    <td>{labelOf(conn.kind)}</td>
                    <td>
                      <div className="badges">
                        {Object.keys(conn.modalities).map((m) => (
                          <Badge key={m} kind="accent">
                            {MODALITY_LABELS[m] || m}
                          </Badge>
                        ))}
                      </div>
                    </td>
                    <td className="mono truncate" style={{ maxWidth: 240 }}>
                      {(conn.modalities.text && conn.modalities.text.model) || Object.values(conn.modalities)[0].model || '—'}
                    </td>
                    <td>
                      <ConnectionModels models={modelsOf(conn)} loading={models.loading && !models.data} workspace={workspace} />
                    </td>
                    {health.data && (
                      <td className="small">
                        <HealthSummary health={healthOf(conn)} />
                      </td>
                    )}
                    <td>
                      {conn.token ? (
                        <Badge kind="positive">Configured</Badge>
                      ) : (cat.find((c) => c.id === conn.kind) || {}).token_required === false ? (
                        <Badge>Not required</Badge>
                      ) : (
                        <Badge kind="warning">Missing</Badge>
                      )}
                    </td>
                    <td>
                      <div className="badges">
                        {conn.enabled ? <Badge kind="positive">Enabled</Badge> : <Badge>Disabled</Badge>}
                        {conn.require_known_costs && (
                          <Badge kind="info" title="Models with no known price are refused and not listed">
                            Known costs only
                          </Badge>
                        )}
                      </div>
                    </td>
                    <td className="actions">
                      <button className="btn sm" onClick={() => openEdit(conn)}>
                        Edit
                      </button>
                      <button className="btn sm ghost" onClick={() => remove(conn)}>
                        Remove
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <div className="card">
        <div className="row between" style={{ marginBottom: 14 }}>
          <h2>Available</h2>
          <span className="muted small">
            {available.length} of {cat.length} providers
          </span>
        </div>
        <div className="provider-toolbar">
          <input className="input search" placeholder="Filter providers" value={filter} onChange={(e) => setFilter(e.target.value)} />
          <div className="picks">
            {capabilityOptions.map((k) => (
              <button
                key={k}
                className={`pick ${capabilities.includes(k) ? 'active' : ''}`}
                title={`Providers serving ${KIND_LABELS[k].toLowerCase()} models`}
                onClick={() => setCapabilities(capabilities.includes(k) ? capabilities.filter((c) => c !== k) : [...capabilities, k])}
              >
                {KIND_LABELS[k]}
              </button>
            ))}
            <button className={`pick ${openAiOnly ? 'active' : ''}`} title="The gateway talks to the provider in the OpenAI format" onClick={() => setOpenAiOnly(!openAiOnly)}>
              OpenAI compatible
            </button>
            <button className={`pick ${pricedOnly ? 'active' : ''}`} title="Cost tracking knows the price of its models" onClick={() => setPricedOnly(!pricedOnly)}>
              Known prices
            </button>
          </div>
        </div>
        {catalog.loading && <Loading />}
        {catalog.data && available.length === 0 && <Empty title="No provider matches these filters" />}
        <div className="provider-grid">
          {available.map((entry) => {
            const facts = insightsFacts(entry);
            const insights = entry.insights || {};
            const doc = insights.catalog && insights.catalog.doc;
            return (
              <div key={entry.id} className="card tight clickable provider" onClick={() => openNew(entry)}>
                <div className="head">
                  <span className="logo-chip">{initials(entry.label)}</span>
                  <div className="grow">
                    <div className="truncate" style={{ fontWeight: 500 }}>
                      {entry.label}
                    </div>
                    <div className="muted small truncate">{counts[entry.id] ? `${counts[entry.id]} connection${counts[entry.id] > 1 ? 's' : ''}` : 'Not configured'}</div>
                  </div>
                  {doc && (
                    <a className="doc-link" href={doc} target="_blank" rel="noreferrer" title="Provider documentation" onClick={(e) => e.stopPropagation()}>
                      <Icon name="external" size={14} />
                    </a>
                  )}
                </div>
                <div className="facts truncate" title={facts.join(' · ')}>
                  {facts.length ? facts.join(' · ') : <span className="faint">No catalog information</span>}
                </div>
                <div className="badges">
                  {KIND_ORDER.filter((k) => entry.capabilities.includes(k)).map((k) => (
                    <Badge key={k} kind="accent">
                      {KIND_LABELS[k]}
                    </Badge>
                  ))}
                  {insights.openai_compatible === true && <Badge title="The gateway talks to the provider in the OpenAI format">OpenAI API</Badge>}
                  {insights.catalog && insights.catalog.priced > 0 && (
                    <Badge kind="positive" title={`${insights.catalog.priced} of its known models have a price`}>
                      Priced
                    </Badge>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      </div>

      {editing && (
        <ConnectionModal
          workspace={workspace}
          catalog={cat}
          initial={editing}
          existingNames={names}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            connections.reload();
          }}
        />
      )}
    </div>
  );
}

// the models a connection serves on the workspace endpoint
function ConnectionModels({ models, loading, workspace }) {
  if (loading) return <span className="faint">…</span>;
  if (!models.length) return <span className="faint">—</span>;
  const priced = models.filter(hasCost).length;
  return (
    <Link className="link" to={`/workspaces/${workspace.id}/models`} title={kindsSummary(models).join(', ')}>
      {models.length} model{models.length > 1 ? 's' : ''}
      <span className="faint small"> · {priced} priced</span>
    </Link>
  );
}
