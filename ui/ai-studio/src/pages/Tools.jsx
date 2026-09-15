import { useState } from 'react';
import { useWorkspace } from '../App';
import { Badge, Checks, Empty, ErrorAlert, Field, JsonInput, Loading, Modal, NumberInput, PageHeader, SecretInput, Select, StatusBadge, Tabs, TextInput, Toggle, useAsync, useConfirm, useToast } from '../components/ui';
import { Resources, workspaceFilter } from '../lib/entities';
import { attachedProviders, deleteTool, saveTool, SEARCH_PROVIDERS } from '../lib/tools';

const TABS = {
  functions: { title: 'HTTP functions', add: 'Add function', description: 'Tools the model can call; the gateway performs the HTTP request and feeds the result back.' },
  mcp: { title: 'MCP connectors', add: 'Add MCP connector', description: 'Remote MCP servers (HTTP transport) whose tools are exposed to the model.' },
  search: { title: 'Web search', add: 'Add search engine', description: 'Search engines the model can query to ground its answers.' },
};

const DEFAULT_PARAMETERS = { type: 'object', properties: { city: { type: 'string', description: 'The city name' } }, required: ['city'] };

function ToolModal({ workspace, kind, tool, providers, onClose, onSaved }) {
  const toast = useToast();
  const backend = (tool && tool.backend && tool.backend.options) || {};
  const transport = (tool && tool.transport && tool.transport.options) || {};
  const connection = (tool && tool.config && tool.config.connection) || {};
  const [form, setForm] = useState({
    name: tool ? tool.name : '',
    description: tool ? tool.description : '',
    parameters: tool ? tool.parameters || DEFAULT_PARAMETERS : DEFAULT_PARAMETERS,
    url: kind === 'functions' ? backend.url || '' : transport.url || '',
    method: backend.method || 'GET',
    headers: (kind === 'functions' ? backend.headers : transport.headers) || {},
    body: backend.body || '',
    timeout: (kind === 'functions' ? backend.timeout : transport.timeout) || 30000,
    enabled: tool ? tool.enabled !== false : true,
    search_provider: tool ? tool.provider : 'tavily',
    token: connection.token || '',
    base_url: connection.base_url || '',
    providers: tool ? attachedProviders(providers, kind, tool.id).map((p) => p.id) : providers.map((p) => p.id),
  });
  const [saving, setSaving] = useState(false);
  const set = (patch) => setForm((f) => ({ ...f, ...patch }));
  const search = SEARCH_PROVIDERS.find((s) => s.value === form.search_provider) || SEARCH_PROVIDERS[0];

  const save = async () => {
    setSaving(true);
    try {
      let base;
      let patch;
      if (kind === 'functions') {
        base = tool || (await Resources.functions.template());
        patch = {
          name: form.name,
          description: form.description,
          strict: false,
          parameters: form.parameters,
          required: (form.parameters && form.parameters.required) || [],
          backend: {
            kind: 'Http',
            options: { url: form.url, method: form.method, headers: form.headers, timeout: Number(form.timeout), ...(form.body ? { body: form.body } : {}) },
          },
        };
      } else if (kind === 'mcp') {
        base = tool || (await Resources.mcpConnectors.template());
        patch = {
          name: form.name,
          description: form.description,
          enabled: form.enabled,
          transport: { kind: 'http', options: { url: form.url, headers: form.headers, timeout: Number(form.timeout) } },
        };
      } else {
        base = tool && tool.provider === form.search_provider ? tool : await Resources.searchEngines.template({ kind: form.search_provider });
        const prev = (base.config && base.config.connection) || {};
        patch = {
          name: form.name,
          description: form.description,
          provider: form.search_provider,
          config: { ...(base.config || {}), connection: { ...prev, ...(form.base_url ? { base_url: form.base_url } : {}), ...(form.token ? { token: form.token } : {}) } },
        };
      }
      await saveTool(workspace.id, kind, base, patch, tool, providers, form.providers);
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
          <Field className="full" label="URL" hint={kind === 'functions' ? 'Use ${param} to inject the arguments chosen by the model, e.g. https://wttr.in/${city}?format=3' : 'Streamable HTTP endpoint of the MCP server.'}>
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

export function ToolsPage() {
  const { workspace } = useWorkspace();
  const toast = useToast();
  const confirm = useConfirm();
  const [tab, setTab] = useState('functions');
  const [editing, setEditing] = useState(null);
  const data = useAsync(async () => {
    const filter = workspaceFilter(workspace.id);
    const [functions, mcp, search, providers] = await Promise.all([
      Resources.functions.list(filter),
      Resources.mcpConnectors.list(filter),
      Resources.searchEngines.list(filter).catch(() => []),
      Resources.providers.list(filter),
    ]);
    return { functions, mcp, search, providers };
  }, [workspace.id]);
  const providers = (data.data && data.data.providers) || [];
  const items = (data.data && data.data[tab]) || [];

  const remove = (tool) => {
    confirm({ title: `Delete ${tool.name}?`, danger: true, confirmLabel: 'Delete' }).then((ok) => {
      if (!ok) return;
      deleteTool(tab, tool, providers)
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
        <button className="btn primary" onClick={() => setEditing({})}>
          {TABS[tab].add}
        </button>
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
              <button className="btn primary" onClick={() => setEditing({})}>
                Add one
              </button>
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
                      {tab === 'functions' ? `${(t.backend && t.backend.options && t.backend.options.method) || 'POST'} ${(t.backend && t.backend.options && t.backend.options.url) || '—'}` : tab === 'mcp' ? (t.transport && t.transport.options && t.transport.options.url) || '—' : t.provider}
                    </td>
                    <td>
                      <div className="badges">
                        {attachedProviders(providers, tab, t.id).map((p) => (
                          <Badge key={p.id} kind="accent">
                            {p.name}
                          </Badge>
                        ))}
                      </div>
                    </td>
                    {tab === 'mcp' && (
                      <td>
                        <StatusBadge enabled={t.enabled !== false} />
                      </td>
                    )}
                    <td className="actions">
                      <button className="btn sm" onClick={() => setEditing({ tool: t })}>
                        Edit
                      </button>
                      <button className="btn sm ghost" onClick={() => remove(t)}>
                        Delete
                      </button>
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
