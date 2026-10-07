import { backend } from './backend';

// The studio only creates MCP connectors speaking the stateless Streamable HTTP revision: every request is
// self-contained, so no session survives a restart or hops to another Otoroshi instance. A connector created
// elsewhere keeps the transport it was given.
export const MCP_TRANSPORT = 'http_2026_07_28';

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

// Every tool of the workspace that a provider carries, with the providers it is attached to. A call can
// pick among them (`allowed_tools`): it only narrows what its provider offers, so a tool nobody attached
// stays out of reach.
export async function listAttachedTools(wsId) {
  const tools = await backend.run('tools.list', wsId);
  return tools.filter((t) => t.providers.length > 0);
}
