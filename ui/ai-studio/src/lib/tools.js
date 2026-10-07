import { Resources, randomId, workspaceFilter } from './entities';
import { backend } from './backend';
import { workspaceLocation, workspaceMetadata } from './workspaces';

// The studio only creates MCP connectors speaking the stateless Streamable HTTP revision: every request is
// self-contained, so no session survives a restart or hops to another Otoroshi instance. A connector created
// elsewhere keeps the transport it was given.
export const MCP_TRANSPORT = 'http_2026_07_28';

export const TOOL_KINDS = {
  functions: { resource: 'functions', option: 'tool_functions', kind: 'tool-function', prefix: 'tool-function' },
  mcp: { resource: 'mcpConnectors', option: 'mcp_connectors', kind: 'mcp-connector', prefix: 'mcp-connector' },
  search: { resource: 'searchEngines', option: 'search_engines', kind: 'search-engine', prefix: 'search-engine' },
};

// A tool function entity stores the *properties* of its json schema, and the required ones next to them
// (that is what the providers and the MCP endpoint wrap into an `inputSchema`). The form edits the whole
// schema, which is what everybody writes, so it is unwrapped on the way in and rebuilt on the way out.
export function schemaOf(parameters, required) {
  const params = parameters || {};
  if (params.type === 'object' && params.properties) return params;
  return { type: 'object', properties: params, required: required || Object.keys(params) };
}

export function propertiesOf(schema) {
  const s = schema || {};
  return s.type === 'object' && s.properties ? s.properties : s;
}

export function requiredOf(schema) {
  const s = schema || {};
  if (Array.isArray(s.required)) return s.required;
  return Object.keys(propertiesOf(s));
}

// Ready-made HTTP functions: the ones every workspace ends up writing by hand, one click away. `build`
// returns exactly what the tool form would have produced.
export const FUNCTION_TEMPLATES = [
  {
    id: 'web_fetch',
    name: 'web_fetch',
    label: 'Web fetch',
    summary: 'Give the model a URL to read: the gateway fetches the page and hands it back as markdown, images and pdf included.',
    build: () => ({
      name: 'web_fetch',
      description: 'Fetch a web page or a document at a given URL and return its content as markdown',
      strict: false,
      parameters: { url: { type: 'string', description: 'The absolute URL to fetch, for instance https://example.com/article' } },
      required: ['url'],
      backend: {
        kind: 'Http',
        options: { url: '${url}', method: 'GET', headers: {}, timeout: 30000, followRedirect: true, kreuzberg: true },
      },
    }),
  },
];

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

export const TOOL_LABELS = { functions: 'function', mcp: 'MCP', search: 'web search' };

// every tool of the workspace, whatever it is attached to
export async function listWorkspaceTools(wsId) {
  const filter = workspaceFilter(wsId);
  const [functions, mcp, search] = await Promise.all([Resources.functions.list(filter), Resources.mcpConnectors.list(filter), Resources.searchEngines.list(filter)]);
  const listed = (kind, entities) => (entities || []).map((e) => ({ id: e.id, name: e.name, kind }));
  return [...listed('functions', functions), ...listed('mcp', mcp), ...listed('search', search)];
}

// The options of a text provider carrying the `selected` tools among `tools`: the ids the workspace does not
// list (a tool attached from the Otoroshi admin) stay where they are.
export function withTools(options, tools, selected) {
  const next = { ...(options || {}) };
  Object.keys(TOOL_KINDS).forEach((kind) => {
    const option = TOOL_KINDS[kind].option;
    const known = tools.filter((t) => t.kind === kind).map((t) => t.id);
    const kept = (next[option] || []).filter((id) => !known.includes(id));
    next[option] = [...kept, ...known.filter((id) => selected.includes(id))];
  });
  return next;
}

// the tools among `tools` that the options of a text provider carry
export function toolsOf(options, tools) {
  return tools.filter((t) => ((options && options[TOOL_KINDS[t.kind].option]) || []).includes(t.id)).map((t) => t.id);
}

// Every tool of the workspace that a provider carries, with the providers it is attached to. A call can
// pick among them (`allowed_tools`): it only narrows what its provider offers, so a tool nobody attached
// stays out of reach.
export async function listAttachedTools(wsId) {
  const tools = await backend.run('tools.list', wsId);
  return tools.filter((t) => t.providers.length > 0);
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
