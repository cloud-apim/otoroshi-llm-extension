import { useEffect, useState } from 'react';
import { useWorkspace } from '../App';
import { Checks, CopyButton, Empty, ErrorAlert, Field, Loading, PageHeader, Readonly, StatusBadge, TextArea, TextInput, Toggle, useAsync, useConfirm, useToast } from '../components/ui';
import { Icon } from '../components/icons';
import { Resources, workspaceFilter } from '../lib/entities';
import { Link } from '../lib/router';
import { clientConfigOf, deleteMcpServer, loadMcpServer, mcpUrlOf, saveMcpServer, toolRefsOf } from '../lib/mcpserver';

const emptyForm = (workspace) => ({ name: `${workspace.name} tools`, description: '', enabled: true, functions: [], connectors: [] });

function formOf(workspace, server) {
  if (!server) return emptyForm(workspace);
  const refs = toolRefsOf(server);
  return {
    name: server.name || '',
    description: server.description || '',
    enabled: server.enabled !== false,
    functions: refs.functions,
    connectors: refs.connectors,
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
      <Field label="Endpoint">
        <Readonly value={mcpUrlOf(workspace)} />
      </Field>
      <pre>
        <code>{config}</code>
      </pre>
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

export function McpServerPage() {
  const { workspace, reload } = useWorkspace();
  const toast = useToast();
  const confirm = useConfirm();
  const [form, setForm] = useState(null);
  const [saving, setSaving] = useState(false);
  const set = (patch) => setForm((f) => ({ ...f, ...patch }));

  const data = useAsync(async () => {
    const filter = workspaceFilter(workspace.id);
    const [server, functions, mcp] = await Promise.all([
      loadMcpServer(workspace.id),
      Resources.functions.list(filter),
      Resources.mcpConnectors.list(filter),
    ]);
    return { server, functions, mcp };
  }, [workspace.id]);

  const server = data.data && data.data.server;
  useEffect(() => {
    if (data.data) setForm(formOf(workspace, data.data.server));
  }, [data.data]);

  const save = async () => {
    setSaving(true);
    try {
      await saveMcpServer(workspace, form);
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
      await deleteMcpServer(workspace);
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
            </div>
          </div>
          <ToolsCard workspace={workspace} form={form} set={set} tools={data.data} />
          {server && <ConnectCard workspace={workspace} />}
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
