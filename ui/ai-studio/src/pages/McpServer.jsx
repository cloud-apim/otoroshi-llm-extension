import { useEffect, useRef, useState } from 'react';
import { useWorkspace } from '../App';
import { Checks, CopyButton, Empty, ErrorAlert, Field, Loading, PageHeader, Readonly, Select, StatusBadge, TextArea, TextInput, Toggle, useAsync, useConfirm, useToast } from '../components/ui';
import { Icon } from '../components/icons';
import { backend } from '../lib/backend';
import { Link } from '../lib/router';
import { clientConfigOf, exampleArguments, mcpCall, mcpUrlOf } from '../lib/mcpserver';
import { fmtMs } from '../lib/format';

const emptyForm = (workspace) => ({
  name: `${workspace.name} tools`,
  description: '',
  enabled: true,
  functions: [],
  connectors: [],
  exposeAsMeta: false,
  metaSemanticSearch: false,
});

// `server` is the view of the studio api, null while the workspace serves no MCP server
function formOf(workspace, server) {
  if (!server) return emptyForm(workspace);
  return {
    name: server.name || '',
    description: server.description || '',
    enabled: server.enabled !== false,
    functions: server.functions,
    connectors: server.connectors,
    exposeAsMeta: !!server.expose_as_meta,
    metaSemanticSearch: !!server.meta_semantic_search,
  };
}

function ConnectCard({ workspace }) {
  const config = clientConfigOf(workspace, workspace.slug);
  return (
    <div className="card">
      <div className="row between">
        <h2>Connect a client</h2>
        <CopyButton text={config} className="btn sm" label="Copy" />
      </div>
      <p className="muted" style={{ margin: '4px 0 14px' }}>
        The endpoint is protected by the workspace API keys, like the models. MCP clients look for an OAuth
        server and will not send a key on their own, so give them one as a header. Replace{' '}
        <span className="mono">$API_KEY</span> with a key from the API Keys page: its owner is who the tool
        calls will count for.
      </p>
      <div className="stack">
        <Field label="Endpoint">
          <Readonly value={mcpUrlOf(workspace)} />
        </Field>
        <pre>
          <code>{config}</code>
        </pre>
      </div>
    </div>
  );
}

function ToolsCard({ workspace, form, set, tools }) {
  const functions = tools.functions || [];
  const connectors = tools.mcp || [];
  const toolsPath = `/workspaces/${workspace.id}/tools`;
  const options = (list) => list.map((t) => ({ value: t.id, label: t.name }));
  return (
    <div className="card">
      <h2>Exposed tools</h2>
      <p className="muted" style={{ margin: '4px 0 14px' }}>
        What clients see in <span className="mono">tools/list</span>. The same tools the models of this
        workspace can call, managed in <Link className="link" to={toolsPath}>Tools</Link>.
      </p>
      {functions.length === 0 && connectors.length === 0 && (
        <Empty title="This workspace has no tools yet" action={<Link className="btn primary" to={toolsPath}>Add a tool</Link>} />
      )}
      {functions.length > 0 && (
        <Field label="HTTP functions" hint="The gateway performs the request and returns the result to the client.">
          <Checks options={options(functions)} value={form.functions} onChange={(functions) => set({ functions })} />
        </Field>
      )}
      {connectors.length > 0 && (
        <Field label="MCP connectors" hint="Remote MCP servers: their tools, resources and prompts are re-exposed here.">
          <Checks options={options(connectors)} value={form.connectors} onChange={(connectors) => set({ connectors })} />
        </Field>
      )}
    </div>
  );
}

// a text the tool answered, shown as indented json when it is some
function pretty(text) {
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch (e) {
    return text;
  }
}

function ToolResult({ result }) {
  const content = result.content || [];
  return (
    <div className="card playground-result">
      {result.isError && (
        <div className="playground-verdict flagged">
          <Icon name="info" />
          The tool answered with an error
        </div>
      )}
      {content.map((c, i) =>
        c.type === 'text' ? (
          <div key={i} className="playground-text">
            <pre>{pretty(c.text)}</pre>
            <CopyButton text={c.text} />
          </div>
        ) : c.type === 'image' ? (
          <img key={i} src={`data:${c.mimeType};base64,${c.data}`} alt={`Image ${i + 1}`} style={{ maxWidth: 320 }} />
        ) : (
          <pre key={i}>{JSON.stringify(c, null, 2)}</pre>
        )
      )}
      {content.length === 0 && !result.structuredContent && <p className="muted">The tool answered with no content.</p>}
      {result.structuredContent && (
        <div className="playground-text">
          <pre>{JSON.stringify(result.structuredContent, null, 2)}</pre>
        </div>
      )}
      <div className="meta small">
        <span>{fmtMs(result.duration)}</span>
      </div>
    </div>
  );
}

// The tools of the saved server, called the way a client calls them on the workspace endpoint: what a tool
// answers here is what Claude or Cursor gets, audited the same way
function PlaygroundCard({ workspace }) {
  const tools = useAsync(() => mcpCall(workspace, 'tools/list').then((r) => (r && r.tools) || []), [workspace.id]);
  const [name, setName] = useState('');
  const [args, setArgs] = useState('{}');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [result, setResult] = useState(null);
  const abort = useRef(null);
  const list = tools.data || [];
  const tool = list.find((t) => t.name === name) || null;

  const pick = (toolName) => {
    const picked = list.find((t) => t.name === toolName);
    setName(toolName);
    setArgs(JSON.stringify(exampleArguments(picked && picked.inputSchema), null, 2));
    setResult(null);
    setError(null);
  };

  // the first tool is ready to be called as soon as the list is in
  useEffect(() => {
    if (list.length > 0 && !list.some((t) => t.name === name)) pick(list[0].name);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tools.data]);

  let parsed = null;
  try {
    parsed = JSON.parse(args.trim() || '{}');
  } catch (e) {
    parsed = undefined;
  }
  const valid = parsed !== undefined && parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed);

  const run = () => {
    setBusy(true);
    setError(null);
    setResult(null);
    const controller = new AbortController();
    abort.current = controller;
    const started = Date.now();
    mcpCall(workspace, 'tools/call', { name, arguments: parsed }, controller.signal)
      .then((r) => setResult({ ...(r || {}), duration: Date.now() - started }))
      .catch((e) => {
        if (e.name !== 'AbortError') setError(e);
      })
      .finally(() => {
        abort.current = null;
        setBusy(false);
      });
  };

  return (
    <div className="card">
      <div className="row between">
        <h2>Playground</h2>
        <button className="btn sm ghost" onClick={tools.reload} disabled={tools.loading}>
          <Icon name="refresh" />
          Reload tools
        </button>
      </div>
      <p className="muted" style={{ margin: '4px 0 14px' }}>
        Call a tool the way a client does, on the endpoint of the workspace and as it is saved: what it answers here
        is what your agents get, and the call shows up in the MCP activity.
      </p>
      <ErrorAlert error={tools.error} />
      {tools.loading && !tools.data && <Loading />}
      {tools.data && list.length === 0 && <p className="muted">The server exposes no tool yet.</p>}
      {list.length > 0 && (
        <div className="playground stack">
          <Field label="Tool" hint={tool && tool.description}>
            <Select value={name} onChange={pick} options={list.map((t) => ({ value: t.name, label: t.title || t.name }))} />
          </Field>
          <Field label="Arguments" hint="A JSON object, written after the input schema of the tool.">
            <TextArea className="mono" rows={6} value={args} onChange={setArgs} />
          </Field>
          <div className="row between">
            <span className="faint small">{valid ? `Sent to ${mcpUrlOf(workspace)}` : 'The arguments are not a JSON object'}</span>
            <div className="row">
              {busy && (
                <button className="btn sm" onClick={() => abort.current && abort.current.abort()}>
                  <Icon name="stop" />
                  Stop
                </button>
              )}
              <button className="btn primary" disabled={busy || !valid || !name} onClick={run}>
                {busy ? 'Running…' : 'Call'}
              </button>
            </div>
          </div>
          <ErrorAlert error={error} />
          {result && <ToolResult result={result} />}
        </div>
      )}
    </div>
  );
}

export function McpServerPage() {
  const { workspace, reload } = useWorkspace();
  const toast = useToast();
  const confirm = useConfirm();
  const [form, setForm] = useState(null);
  const [saving, setSaving] = useState(false);
  const set = (patch) => setForm((f) => ({ ...f, ...patch }));

  const data = useAsync(async () => {
    const [server, tools] = await Promise.all([backend.run('mcpServer.get', workspace.id), backend.run('tools.list', workspace.id)]);
    return { server: server.served ? server : null, functions: tools.filter((t) => t.kind === 'functions'), mcp: tools.filter((t) => t.kind === 'mcp') };
  }, [workspace.id]);

  const server = data.data && data.data.server;
  useEffect(() => {
    if (data.data) setForm(formOf(workspace, data.data.server));
  }, [data.data]);

  const save = async () => {
    setSaving(true);
    try {
      await backend.run('mcpServer.save', workspace.id, {
        body: {
          name: form.name,
          description: form.description,
          enabled: form.enabled,
          functions: form.functions,
          connectors: form.connectors,
          expose_as_meta: form.exposeAsMeta,
          meta_semantic_search: form.metaSemanticSearch,
        },
      });
      toast.success(server ? 'MCP server saved' : 'MCP server enabled');
      data.reload();
      reload();
    } catch (e) {
      toast.error(e);
    } finally {
      setSaving(false);
    }
  };

  const remove = async () => {
    const ok = await confirm({
      title: 'Stop serving MCP?',
      message: 'The /mcp endpoint returns 404 again and the server definition is deleted. The tools themselves are kept.',
      danger: true,
      confirmLabel: 'Stop serving',
    });
    if (!ok) return;
    try {
      await backend.run('mcpServer.delete', workspace.id);
      toast.success('MCP server removed');
      data.reload();
      reload();
    } catch (e) {
      toast.error(e);
    }
  };

  return (
    <div className="content">
      <PageHeader
        title="MCP server"
        description="Expose the tools of this workspace to MCP clients — Claude, Cursor, your own agents — on the same endpoint and with the same API keys as the models."
      >
        {server && (
          <button className="btn ghost" onClick={remove}>
            <Icon name="trash" />
            Stop serving
          </button>
        )}
        {form && (
          <button className="btn primary" onClick={save} disabled={saving || !form.name}>
            {saving ? 'Saving…' : server ? 'Save' : 'Enable MCP server'}
          </button>
        )}
      </PageHeader>
      <ErrorAlert error={data.error} />
      {data.loading && !data.data && <Loading />}
      {form && (
        <div className="stack">
          <div className="card">
            <div className="row between center">
              <div>
                <h2 style={{ margin: 0 }}>
                  {server ? 'Serving' : 'Not served yet'}{' '}
                  {server && <StatusBadge enabled={form.enabled} on="Enabled" off="Disabled" />}
                </h2>
                <p className="muted" style={{ margin: '4px 0 0' }}>
                  {server
                    ? `Exposed on ${mcpUrlOf(workspace)}`
                    : 'Pick the tools to expose, then enable the server: the endpoint returns 404 until then.'}
                </p>
              </div>
              {server && <Toggle value={form.enabled} onChange={(enabled) => set({ enabled })} title="Serve this MCP server" />}
            </div>
            <div className="grid cols-2" style={{ marginTop: 16 }}>
              <Field label="Name" hint="What clients display for this server.">
                <TextInput value={form.name} onChange={(name) => set({ name })} placeholder="Acme tools" />
              </Field>
              <Field label="Description">
                <TextArea rows={2} value={form.description} onChange={(description) => set({ description })} placeholder="The tools of the Acme workspace" />
              </Field>
              <Field
                label="Meta mode"
                hint="The MCP connectors are exposed through five tools — list_servers, list_tools, get_tool_schema, search_tools and execute — instead of their full tool list."
              >
                <Toggle value={form.exposeAsMeta} onChange={(exposeAsMeta) => set({ exposeAsMeta })} title="Expose as meta" />
              </Field>
              <Field label="Semantic tool search" hint="In meta mode, search_tools also ranks the tools by meaning, on top of their keywords.">
                <Toggle value={form.metaSemanticSearch} onChange={(metaSemanticSearch) => set({ metaSemanticSearch })} title="Semantic tool search" />
              </Field>
            </div>
          </div>
          <ToolsCard workspace={workspace} form={form} set={set} tools={data.data} />
          {server && <ConnectCard workspace={workspace} />}
          {server && server.enabled !== false && <PlaygroundCard key={JSON.stringify([server.functions, server.connectors, server.expose_as_meta])} workspace={workspace} />}
          {server && (
            <div className="card">
              <h2>Activity</h2>
              <p className="muted" style={{ margin: '4px 0 0' }}>
                Every request served here is audited: which tool, for whom, how long, and what failed.{' '}
                <Link className="link" to={`/workspaces/${workspace.id}/activity?tab=mcp`}>See the MCP activity</Link>. A key owned by
                someone makes their tool calls show up under their name, exactly like their model calls.
              </p>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
