import { gatewayError, STUDIO_API } from './api';
import { currentTenant } from './bootstrap';
import { Resources } from './entities';
import { findPlugin, OPENAI_COMPAT_PLUGIN, routeIdOf, setOpenAiConfig, updateWorkspaceRoute, workspaceLocation, workspaceMetadata } from './workspaces';

// The MCP server of a workspace is one virtual server entity, referenced by the unified plugin of the
// workspace route, which serves it on `<base url>/mcp`: same endpoint and same API keys as the models,
// so a tool call is attributed to a person exactly like a model call.

export const MCP_PATH = '/mcp';

export const mcpServerIdOf = (wsId) => `mcp-virtual-server_ais_${wsId}`;

export const mcpUrlOf = (workspace) => `${workspace.baseUrl}${MCP_PATH}`;

export function mcpServerRefOf(route) {
  const plugin = findPlugin(route, OPENAI_COMPAT_PLUGIN);
  return (plugin && plugin.config && plugin.config.mcp_server_ref) || null;
}

// always from a fresh route: the workspace of the context still carries the route as it was rendered,
// which is one save behind
export async function loadMcpServer(wsId) {
  const route = await Resources.routes.get(routeIdOf(wsId));
  const ref = mcpServerRefOf(route);
  if (!ref) return null;
  return Resources.mcpVirtualServers.get(ref).catch(() => null);
}

// what the server exposes, among the tools of the workspace
export function toolRefsOf(server) {
  const config = (server && server.config) || {};
  return { functions: config.refs || [], connectors: config.mcp_refs || [] };
}

export async function saveMcpServer(workspace, { name, description, enabled, functions, connectors }) {
  const existing = await loadMcpServer(workspace.id);
  const base = existing || (await Resources.mcpVirtualServers.template());
  const entity = {
    ...base,
    _loc: (existing && existing._loc) || workspaceLocation(workspace.id),
    id: (existing && existing.id) || mcpServerIdOf(workspace.id),
    name,
    description: description || '',
    enabled,
    tags: (existing && existing.tags) || [],
    metadata: { ...((existing && existing.metadata) || {}), ...workspaceMetadata(workspace.id, 'mcp-server') },
    // only the fields the studio owns are rewritten: anything else set on the entity from the otoroshi
    // console (oauth, scopes, zero-trust, overlays, registry publication…) is left as it is
    config: {
      ...(base.config || {}),
      name,
      refs: functions,
      mcp_refs: connectors,
      // what the activity page of the workspace reads
      emit_audit_events: true,
    },
  };
  if (existing) await Resources.mcpVirtualServers.update(entity);
  else await Resources.mcpVirtualServers.create(entity);
  await updateWorkspaceRoute(workspace.id, (route) => setOpenAiConfig(route, { mcp_server_ref: entity.id }));
  return entity;
}

// the route stops serving /mcp, and the server it was serving goes with it
export async function deleteMcpServer(workspace) {
  const existing = await loadMcpServer(workspace.id);
  await updateWorkspaceRoute(workspace.id, (route) => setOpenAiConfig(route, { mcp_server_ref: null }));
  if (existing) await Resources.mcpVirtualServers.delete(existing.id);
}

export function clientConfigOf(workspace, slug) {
  return JSON.stringify(
    {
      mcpServers: {
        [slug || 'otoroshi']: {
          type: 'http',
          url: mcpUrlOf(workspace),
          headers: { Authorization: 'Bearer $API_KEY' },
        },
      },
    },
    null,
    2
  );
}

let rpcId = 0;

/**
 * One JSON-RPC request on the MCP endpoint of the workspace, as the signed-in backoffice user: the endpoint
 * answers a client that never initializes, so a playground needs no session. Returns the `result` of the
 * answer, and throws its error.
 */
export async function mcpCall(workspace, method, params = {}, signal) {
  const res = await fetch(`${STUDIO_API}/workspaces/${workspace.id}/proxy${MCP_PATH}`, {
    method: 'POST',
    credentials: 'include',
    signal,
    headers: { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream', 'Otoroshi-Tenant': currentTenant() },
    body: JSON.stringify({ jsonrpc: '2.0', id: ++rpcId, method, params }),
  });
  const text = await res.text();
  // an answer may come as a stream of events: the last message is the answer
  const raw = (res.headers.get('Content-Type') || '').startsWith('text/event-stream')
    ? text
        .split('\n')
        .filter((l) => l.startsWith('data:'))
        .map((l) => l.slice(5).trim())
        .filter(Boolean)
        .pop()
    : text;
  let json = null;
  try {
    json = JSON.parse(raw || '');
  } catch (e) {
    throw gatewayError(text, res.status, res.statusText);
  }
  if (json.error && json.error.message) throw new Error(`${json.error.code} - ${json.error.message}`);
  if (!res.ok || json.result === undefined) throw gatewayError(text, res.status, res.statusText);
  return json.result;
}

// arguments to start from, written after the input schema of a tool: its defaults, its first allowed values
export function exampleArguments(schema) {
  if (!schema || typeof schema !== 'object') return {};
  if (schema.default !== undefined) return schema.default;
  if (Array.isArray(schema.enum) && schema.enum.length > 0) return schema.enum[0];
  if (Array.isArray(schema.examples) && schema.examples.length > 0) return schema.examples[0];
  const type = Array.isArray(schema.type) ? schema.type[0] : schema.type;
  switch (type) {
    case 'object':
      return Object.fromEntries(Object.entries(schema.properties || {}).map(([k, v]) => [k, exampleArguments(v)]));
    case 'array':
      return [];
    case 'number':
    case 'integer':
      return 0;
    case 'boolean':
      return false;
    case 'string':
      return '';
    default:
      return schema.properties ? exampleArguments({ ...schema, type: 'object' }) : null;
  }
}
