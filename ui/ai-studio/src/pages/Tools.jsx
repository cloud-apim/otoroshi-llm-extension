import { useState } from 'react';
import { useCan, useWorkspace } from '../App';
import { Badge, Checks, Empty, ErrorAlert, Field, JsonInput, Loading, Modal, NumberInput, PageHeader, SecretInput, Select, StatusBadge, Tabs, TextInput, Toggle, useAsync, useConfirm, useToast } from '../components/ui';
import { backend } from '../lib/backend';
import { FUNCTION_TEMPLATES, MCP_TRANSPORT, SEARCH_PROVIDERS } from '../lib/tools';

const TABS = {
  functions: { title: 'HTTP functions', add: 'Add function', description: 'Tools the model can call; the gateway performs the HTTP request and feeds the result back.' },
  mcp: { title: 'MCP connectors', add: 'Add MCP connector', description: 'Remote MCP servers whose tools are exposed to the model. Connectors are created on the stateless Streamable HTTP revision of the protocol (2026-07-28).' },
  search: { title: 'Web search', add: 'Add search engine', description: 'Search engines the model can query to ground its answers.' },
};

const DEFAULT_PARAMETERS = { type: 'object', properties: { city: { type: 'string', description: 'The city name' } }, required: ['city'] };

// the form of a tool is the view of the studio api (`toolFormOf` in studio/api.scala): the parameters are a
// whole json schema, the endpoint and the credentials sit at the top level
function ToolModal({ workspace, kind, tool, providers, onClose, onSaved }) {
  const toast = useToast();
  const [form, setForm] = useState({
    name: tool ? tool.name : '',
    description: tool ? tool.description : '',
    parameters: (tool && tool.parameters) || DEFAULT_PARAMETERS,
    url: (tool && tool.url) || '',
    method: (tool && tool.method) || 'GET',
    headers: (tool && tool.headers) || {},
    body: (tool && tool.body) || '',
    timeout: (tool && tool.timeout) || 30000,
    kreuzberg: !!(tool && tool.kreuzberg),
    enabled: tool ? tool.enabled !== false : true,
    search_provider: tool ? tool.search_provider : 'tavily',
    token: (tool && tool.token) || '',
    base_url: (tool && tool.base_url) || '',
    providers: tool ? tool.providers : providers.map((p) => p.id),
  });
  const [saving, setSaving] = useState(false);
  const set = (patch) => setForm((f) => ({ ...f, ...patch }));
  const search = SEARCH_PROVIDERS.find((s) => s.value === form.search_provider) || SEARCH_PROVIDERS[0];

  // what the studio does not show (tls, response selection…) is kept by the api, and so is an empty token
  const save = async () => {
    setSaving(true);
    try {
      const common = { name: form.name, description: form.description, providers: form.providers };
      const body =
        kind === 'functions'
          ? { ...common, parameters: form.parameters, url: form.url, method: form.method, headers: form.headers, body: form.body, timeout: Number(form.timeout), kreuzberg: form.kreuzberg }
          : kind === 'mcp'
            ? { ...common, enabled: form.enabled, url: form.url, headers: form.headers, timeout: Number(form.timeout) }
            : { ...common, search_provider: form.search_provider, token: form.token, base_url: form.base_url };
      if (tool) await backend.run('tools.update', workspace.id, { kind, tid: tool.id, body });
      else await backend.run('tools.create', workspace.id, { kind, body });
      toast.success(tool ? 'Tool saved' : 'Tool added');
      onSaved();
    } catch (e) {
      toast.error(e);
    } finally {
      setSaving(false);
    }
  };

  const valid = form.name.trim() && (kind === 'search' || form.url.trim());

  return (
    <Modal
      open
      size="wide"
      onClose={onClose}
      title={tool ? `Edit ${tool.name}` : TABS[kind].add}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button className="btn primary" disabled={!valid || saving} onClick={save}>
            {saving ? 'Saving…' : tool ? 'Save' : 'Add'}
          </button>
        </>
      }
    >
      <div className="form-grid">
        <Field label="Name" hint={kind === 'functions' ? 'The function name the model sees (letters, digits, underscores).' : null}>
          <TextInput value={form.name} onChange={(v) => set({ name: kind === 'functions' ? v.replace(/[^a-zA-Z0-9_]/g, '_') : v })} placeholder={kind === 'functions' ? 'get_weather' : 'My tools'} />
        </Field>
        {kind === 'search' ? (
          <Field label="Search engine">
            <Select value={form.search_provider} onChange={(v) => set({ search_provider: v })} options={SEARCH_PROVIDERS} />
          </Field>
        ) : (
          <Field label="Timeout (ms)">
            <NumberInput value={form.timeout} onChange={(v) => set({ timeout: v })} />
          </Field>
        )}
        <Field className="full" label="Description" hint={kind === 'functions' ? 'Tells the model when to call the function.' : null}>
          <TextInput value={form.description} onChange={(v) => set({ description: v })} placeholder={kind === 'functions' ? 'Get the current weather of a city' : ''} />
        </Field>
        {kind !== 'search' && (
          <Field className="full" label="URL" hint={kind === 'functions' ? 'Use ${param} to inject the arguments chosen by the model, e.g. https://wttr.in/${city}?format=3' : 'Streamable HTTP endpoint of the MCP server. It must speak the stateless 2026-07-28 revision.'}>
            <TextInput value={form.url} onChange={(v) => set({ url: v })} placeholder={kind === 'functions' ? 'https://api.example.com/weather?city=${city}' : 'https://mcp.example.com/mcp'} />
          </Field>
        )}
        {kind === 'functions' && (
          <>
            <Field label="Method">
              <Select value={form.method} onChange={(v) => set({ method: v })} options={['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].map((m) => ({ value: m, label: m }))} />
            </Field>
            <Field label="Body" hint="Optional, ${param} placeholders allowed.">
              <TextInput value={form.body} onChange={(v) => set({ body: v })} placeholder='{"city": "${city}"}' />
            </Field>
            <Field className="full" label="Response as markdown" hint="Convert the response (html, pdf, docx, images…) to markdown before handing it to the model. Needs JDK 25 or above.">
              <Toggle value={form.kreuzberg} onChange={(v) => set({ kreuzberg: v })} />
            </Field>
            <Field className="full" label="Parameters (JSON schema)">
              <JsonInput value={form.parameters} onChange={(v) => set({ parameters: v })} rows={8} />
            </Field>
          </>
        )}
        {kind === 'search' && (
          <>
            {search.token && (
              <Field label="API key">
                <SecretInput value={form.token} onChange={(v) => set({ token: v })} />
              </Field>
            )}
            <Field label="Base URL" hint="Leave empty for the default endpoint.">
              <TextInput value={form.base_url} onChange={(v) => set({ base_url: v })} />
            </Field>
          </>
        )}
        {kind !== 'search' && (
          <Field className="full" label="Headers">
            <JsonInput value={form.headers} onChange={(v) => set({ headers: v })} rows={3} />
          </Field>
        )}
        {kind === 'mcp' && (
          <Field label="Enabled">
            <Toggle value={form.enabled} onChange={(v) => set({ enabled: v })} />
          </Field>
        )}
        <Field className="full" label="Available on providers" hint="Attached tools are offered to the model on every call of these providers.">
          {providers.length === 0 ? <span className="muted">No text provider in this workspace.</span> : <Checks options={providers.map((p) => ({ value: p.id, label: p.name }))} value={form.providers} onChange={(v) => set({ providers: v })} />}
        </Field>
      </div>
    </Modal>
  );
}

// Ready-made functions: one click instead of a form nobody enjoys filling. Adding one creates an ordinary
// tool function, editable and deletable like any other.
function TemplatesCard({ workspace, functions, onAdded }) {
  const toast = useToast();
  const [adding, setAdding] = useState(null);
  // the api fills the whole function from its template, and attaches it to every provider
  const add = async (template) => {
    setAdding(template.id);
    try {
      await backend.run('tools.create', workspace.id, { kind: 'functions', body: { template: template.id } });
      toast.success(`${template.label} added`);
      onAdded();
    } catch (e) {
      toast.error(e);
    } finally {
      setAdding(null);
    }
  };
  return (
    <div className="card" style={{ marginBottom: 14 }}>
      <h2>Ready-made functions</h2>
      <p className="muted" style={{ margin: '4px 0 14px' }}>
        Add one and it becomes a normal function of this workspace, attached to every provider.
      </p>
      {FUNCTION_TEMPLATES.map((t) => {
        const already = functions.some((f) => f.name === t.name);
        return (
          <div key={t.id} className="row between" style={{ gap: 20 }}>
            <div style={{ minWidth: 0 }}>
              <div className="row" style={{ gap: 8 }}>
                <span>{t.label}</span>
                <code className="mono small">{t.name}</code>
              </div>
              <p className="muted small" style={{ margin: '2px 0 0' }}>{t.summary}</p>
            </div>
            {already ? (
              <Badge kind="accent">Added</Badge>
            ) : (
              <button className="btn sm" onClick={() => add(t)} disabled={adding === t.id}>
                {adding === t.id ? 'Adding…' : 'Add'}
              </button>
            )}
          </div>
        );
      })}
    </div>
  );
}

export function ToolsPage() {
  const { workspace } = useWorkspace();
  const write = useCan()('config:write');
  const toast = useToast();
  const confirm = useConfirm();
  const [tab, setTab] = useState('functions');
  const [editing, setEditing] = useState(null);
  // every tool of the workspace, and the text providers they can be attached to
  const data = useAsync(async () => {
    const [tools, entities] = await Promise.all([backend.run('tools.list', workspace.id), backend.run('modelEntities.list', workspace.id)]);
    const ofKind = (kind) => tools.filter((t) => t.kind === kind);
    return { functions: ofKind('functions'), mcp: ofKind('mcp'), search: ofKind('search'), providers: entities.filter((e) => e.modality === 'text') };
  }, [workspace.id]);
  const providers = (data.data && data.data.providers) || [];
  const items = (data.data && data.data[tab]) || [];

  const remove = (tool) => {
    confirm({ title: `Delete ${tool.name}?`, danger: true, confirmLabel: 'Delete' }).then((ok) => {
      if (!ok) return;
      backend
        .run('tools.delete', workspace.id, { kind: tab, tid: tool.id })
        .then(() => {
          toast.success('Tool deleted');
          data.reload();
        })
        .catch(toast.error);
    });
  };

  const count = (k) => (data.data ? data.data[k].length : 0);

  return (
    <div className="content">
      <PageHeader title="Tools" description="Server-side tools the gateway runs on behalf of the model. Attach each tool to the providers that may use it.">
        {write && (
          <button className="btn primary" onClick={() => setEditing({})}>
            {TABS[tab].add}
          </button>
        )}
      </PageHeader>
      <Tabs
        value={tab}
        onChange={setTab}
        tabs={[
          { value: 'functions', label: `Functions (${count('functions')})` },
          { value: 'mcp', label: `MCP (${count('mcp')})` },
          { value: 'search', label: `Web search (${count('search')})` },
        ]}
      />
      <ErrorAlert error={data.error} />
      {tab === 'functions' && data.data && write && (
        <TemplatesCard workspace={workspace} functions={data.data.functions} onAdded={data.reload} />
      )}
      <div className="card">
        <h2>{TABS[tab].title}</h2>
        <p className="muted" style={{ margin: '4px 0 14px' }}>
          {TABS[tab].description}
        </p>
        {data.loading && !data.data && <Loading />}
        {data.data && items.length === 0 && (
          <Empty
            title={`No ${TABS[tab].title.toLowerCase()} yet`}
            action={
              write ? (
                <button className="btn primary" onClick={() => setEditing({})}>
                  Add one
                </button>
              ) : null
            }
          />
        )}
        {items.length > 0 && (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>Name</th>
                  <th>{tab === 'search' ? 'Engine' : 'Endpoint'}</th>
                  <th>Providers</th>
                  {tab === 'mcp' && <th>Transport</th>}
                  {tab === 'mcp' && <th>Status</th>}
                  <th />
                </tr>
              </thead>
              <tbody>
                {items.map((t) => (
                  <tr key={t.id}>
                    <td>
                      <div>{t.name}</div>
                      {t.description && <div className="muted small truncate" style={{ maxWidth: 320 }}>{t.description}</div>}
                    </td>
                    <td className="mono truncate" style={{ maxWidth: 320 }}>
                      {tab === 'functions' ? `${t.method} ${t.url || '—'}` : tab === 'mcp' ? t.url || '—' : t.search_provider}
                    </td>
                    <td>
                      <div className="badges">
                        {providers.filter((p) => t.providers.includes(p.id)).map((p) => (
                          <Badge key={p.id} kind="accent">
                            {p.name}
                          </Badge>
                        ))}
                      </div>
                    </td>
                    {tab === 'mcp' && (
                      <td>
                        {t.transport === MCP_TRANSPORT ? (
                          <Badge kind="accent" title="Stateless Streamable HTTP">stateless http</Badge>
                        ) : (
                          <Badge title="Created outside the studio: the studio keeps the transport it was given">{t.transport || '—'}</Badge>
                        )}
                      </td>
                    )}
                    {tab === 'mcp' && (
                      <td>
                        <StatusBadge enabled={t.enabled !== false} />
                      </td>
                    )}
                    <td className="actions">
                      {write && (
                        <button className="btn sm" onClick={() => setEditing({ tool: t })}>
                          Edit
                        </button>
                      )}
                      {write && (
                        <button className="btn sm ghost" onClick={() => remove(t)}>
                          Delete
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
      {editing && (
        <ToolModal
          key={tab}
          workspace={workspace}
          kind={tab}
          tool={editing.tool}
          providers={providers}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            data.reload();
          }}
        />
      )}
    </div>
  );
}
