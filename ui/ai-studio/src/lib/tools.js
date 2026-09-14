import { Resources, randomId } from './entities';
import { workspaceLocation, workspaceMetadata } from './workspaces';

export const TOOL_KINDS = {
  functions: { resource: 'functions', option: 'tool_functions', kind: 'tool-function', prefix: 'tool-function' },
  mcp: { resource: 'mcpConnectors', option: 'mcp_connectors', kind: 'mcp-connector', prefix: 'mcp-connector' },
  search: { resource: 'searchEngines', option: 'search_engines', kind: 'search-engine', prefix: 'search-engine' },
};

export const SEARCH_PROVIDERS = [
  { value: 'tavily', label: 'Tavily', token: true },
  { value: 'brave', label: 'Brave Search', token: true },
  { value: 'exa', label: 'Exa', token: true },
  { value: 'searchapi', label: 'SearchApi', token: true },
  { value: 'google', label: 'Google Custom Search', token: true },
  { value: 'staan', label: 'Staan (Qwant)', token: true },
  { value: 'searxng', label: 'SearXNG (self hosted)', token: false },
  { value: 'duckduckgo', label: 'DuckDuckGo', token: false },
];

// the providers of the workspace a tool is attached to, through the matching list in their options
export function attachedProviders(providers, kind, id) {
  const option = TOOL_KINDS[kind].option;
  return providers.filter((p) => ((p.options && p.options[option]) || []).includes(id));
}

export async function attachTool(providers, kind, id, selectedIds) {
  const option = TOOL_KINDS[kind].option;
  for (const p of providers) {
    const list = (p.options && p.options[option]) || [];
    const has = list.includes(id);
    const wants = selectedIds.includes(p.id);
    if (has === wants) continue;
    const next = wants ? [...list, id] : list.filter((x) => x !== id);
    await Resources.providers.update({ ...p, options: { ...(p.options || {}), [option]: next } });
  }
}

export async function saveTool(wsId, kind, base, patch, existing, providers, selectedProviders) {
  const def = TOOL_KINDS[kind];
  const resource = Resources[def.resource];
  const entity = {
    ...base,
    ...patch,
    _loc: (existing && existing._loc) || workspaceLocation(wsId),
    id: (existing && existing.id) || `${def.prefix}_ais_${randomId(20)}`,
    tags: (existing && existing.tags) || [],
    metadata: { ...((existing && existing.metadata) || {}), ...workspaceMetadata(wsId, def.kind) },
  };
  if (existing) await resource.update(entity);
  else await resource.create(entity);
  await attachTool(providers, kind, entity.id, selectedProviders);
  return entity;
}

export async function deleteTool(kind, entity, providers) {
  await attachTool(providers, kind, entity.id, []);
  await Resources[TOOL_KINDS[kind].resource].delete(entity.id);
}
