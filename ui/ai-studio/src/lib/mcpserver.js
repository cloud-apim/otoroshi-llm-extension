import { gatewayError } from './api';
import { backend } from './backend';
import { currentTenant } from './bootstrap';

// The MCP server of a workspace is one virtual server entity, referenced by the unified plugin of the
// workspace route, which serves it on `<base url>/mcp`: same endpoint and same API keys as the models,
// so a tool call is attributed to a person exactly like a model call. The studio admin api builds it
// (`mcpServer.*` operations); what is left here talks to the endpoint.

export const MCP_PATH = '/mcp';

export const mcpUrlOf = (workspace) => `${workspace.base_url}${MCP_PATH}`;

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
  const res = await fetch(backend.urls.proxy(workspace.id, MCP_PATH), {
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
