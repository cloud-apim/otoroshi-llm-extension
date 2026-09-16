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
