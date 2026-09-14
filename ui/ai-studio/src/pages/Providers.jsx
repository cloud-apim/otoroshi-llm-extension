import { useMemo, useState } from 'react';
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
import { loadCatalog } from '../lib/models';
import { initials } from '../lib/format';

function ModelInput({ value, onChange, models, placeholder, listId }) {
  return (
    <>
      <input className="input mono" list={listId} value={value || ''} placeholder={placeholder} onChange={(e) => onChange(e.target.value)} />
      {models && models.length > 0 && (
        <datalist id={listId}>
          {models.map((m) => (
            <option key={m} value={m} />
          ))}
        </datalist>
      )}
    </>
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
                                <ModelInput value={mod.model} onChange={(v) => setModality(cap, { model: v })} placeholder={entry.models.audio_tts || 'tts model'} listId={`${cap}-tts`} />
                              </Field>
                            )}
                            {(entry.audio_modes || ['tts', 'stt']).includes('stt') && (
                              <Field hint="Speech to text">
                                <ModelInput value={mod.stt_model} onChange={(v) => setModality(cap, { stt_model: v })} placeholder={entry.models.audio_stt || 'stt model'} listId={`${cap}-stt`} />
                              </Field>
                            )}
                          </div>
                        ) : (
                          <ModelInput
                            value={mod.model}
                            onChange={(v) => setModality(cap, { model: v })}
                            placeholder={entry.models[cap] || 'model id'}
                            models={cap === 'text' ? models : null}
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
  const [editing, setEditing] = useState(null);
  const catalog = useAsync(() => loadCatalog(), []);
  const connections = useAsync(() => listConnections(workspace.id), [workspace.id]);

  // load balancers and routers are managed in the routing page
  const list = (connections.data || []).filter((c) => !['loadbalancer', 'otoroshi'].includes(c.kind));
  const names = list.map((c) => c.name);
  const cat = catalog.data || [];

  const counts = useMemo(() => {
    const r = {};
    list.forEach((c) => (r[c.kind] = (r[c.kind] || 0) + 1));
    return r;
  }, [list]);

  const available = cat.filter((c) => !filter || c.label.toLowerCase().includes(filter.toLowerCase()) || c.id.includes(filter.toLowerCase()));

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
                      {conn.token ? (
                        <Badge kind="positive">Configured</Badge>
                      ) : (cat.find((c) => c.id === conn.kind) || {}).token_required === false ? (
                        <Badge>Not required</Badge>
                      ) : (
                        <Badge kind="warning">Missing</Badge>
                      )}
                    </td>
                    <td>{conn.enabled ? <Badge kind="positive">Enabled</Badge> : <Badge>Disabled</Badge>}</td>
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
          <input className="input search" style={{ maxWidth: 240 }} placeholder="Filter providers" value={filter} onChange={(e) => setFilter(e.target.value)} />
        </div>
        {catalog.loading && <Loading />}
        <div className="provider-grid">
          {available.map((entry) => (
            <div key={entry.id} className="card tight clickable provider" onClick={() => openNew(entry)}>
              <span className="logo-chip">{initials(entry.label)}</span>
              <div className="grow">
                <div className="truncate" style={{ fontWeight: 500 }}>
                  {entry.label}
                </div>
                <div className="muted small truncate">
                  {counts[entry.id] ? `${counts[entry.id]} connection${counts[entry.id] > 1 ? 's' : ''}` : 'Not configured'}
                  {entry.capabilities.filter((c) => c !== 'text').length > 0 && ` · ${entry.capabilities.filter((c) => c !== 'text').join(', ')}`}
                </div>
              </div>
            </div>
          ))}
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
