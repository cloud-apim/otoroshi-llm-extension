import { Resources, workspaceFilter } from './entities';
import { backend } from './backend';

// The studio only creates MCP connectors speaking the stateless Streamable HTTP revision: every request is
// self-contained, so no session survives a restart or hops to another Otoroshi instance. A connector created
// elsewhere keeps the transport it was given.
export const MCP_TRANSPORT = 'http_2026_07_28';

// the option of a text provider listing the tools of each kind
export const TOOL_KINDS = {
  functions: { option: 'tool_functions' },
  mcp: { option: 'mcp_connectors' },
  search: { option: 'search_engines' },
};

// Ready-made HTTP functions: the ones every workspace ends up writing by hand, one click away. The studio
// api fills the function from its template (`functionTemplates` in studio/api.scala).
export const FUNCTION_TEMPLATES = [
  {
    id: 'web_fetch',
    name: 'web_fetch',
    label: 'Web fetch',
    summary: 'Give the model a URL to read: the gateway fetches the page and hands it back as markdown, images and pdf included.',
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
