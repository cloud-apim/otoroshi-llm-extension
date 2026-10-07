// The operations of an AI Studio workspace. Each one names the access it needs and the route of the studio
// admin api (studio/api.scala) that serves it: that api builds and reads the entities of the workspace, with the
// rights of its caller, and exchanges the views the pages show. The OSS studio calls the route through the
// backoffice (lib/backend.js); AI Studio Enterprise checks the access of its user first, then calls the same
// route. Nothing here depends on the browser: the server of AI Studio Enterprise loads this file as it is.

// where the studio admin api is served
export const STUDIO_ADMIN_PATH = '/api/extensions/cloud-apim/extensions/ai-extension/studio';

// the permissions on a workspace (see the AI Studio Enterprise roles)
export const PERMISSIONS = [
  'workspace:read',
  'chat:use',
  'keys:own',
  'usage:own',
  'config:read',
  'activity:read',
  'config:write',
  'keys:manage',
  'members:read',
  'members:manage',
  'workspace:delete',
];

const METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'];
// what can belong to a person
const OWNED = ['apikeys', 'budgets'];

// An operation without a known access or a route fails the loading of the whole registry: nothing runs
// with an access nobody decided.
function defineOps(ops) {
  Object.entries(ops).forEach(([name, op]) => {
    if (!PERMISSIONS.includes(op.access)) throw new Error(`the operation '${name}' declares no known access`);
    if (!METHODS.includes(op.method) || typeof op.path !== 'string') throw new Error(`the operation '${name}' has no route`);
    if (op.own && !OWNED.includes(op.own)) throw new Error(`the operation '${name}' owns an unknown kind '${op.own}'`);
  });
  return Object.freeze(ops);
}

// `path` is relative to the workspace (`/workspaces/:ws`), its `:params` come from the input of the call.
// `audit` marks the writes AI Studio Enterprise records. `reveals` gives a secret masked everywhere else,
// `secretOnce` gives the secret of what it creates or resets, the only time it is shown without being revealed.
// `own` reads or writes what belongs to the person calling only (`apikeys`: the keys they own, `budgets`: the
// ones counting their usage), the rule being applied by AI Studio Enterprise; the OSS studio, whose users read
// the whole configuration, never calls these.
export const ops = defineOps({
  // workspace
  'workspace.get': { access: 'workspace:read', method: 'GET', path: '' },
  // an empty body repairs a route created by an earlier studio
  'workspace.update': { access: 'config:write', method: 'PATCH', path: '', audit: true },
  'workspace.delete': { access: 'workspace:delete', method: 'DELETE', path: '', audit: true },
  'models.list': { access: 'workspace:read', method: 'GET', path: '/models' },

  // providers: one connection per provider, with an entity per capability
  'providers.list': { access: 'config:read', method: 'GET', path: '/providers' },
  'providers.get': { access: 'config:read', method: 'GET', path: '/providers/:cid' },
  'providers.models': { access: 'config:read', method: 'GET', path: '/providers/:cid/models' },
  'providers.create': { access: 'config:write', method: 'POST', path: '/providers', audit: true },
  'providers.update': { access: 'config:write', method: 'PUT', path: '/providers/:cid', audit: true },
  'providers.delete': { access: 'config:write', method: 'DELETE', path: '/providers/:cid', audit: true },
  // the models of a provider being edited, from its kind or from a saved connection
  'providers.draftModels': { access: 'config:write', method: 'POST', path: '/providers/_models' },
  // every model entity, load balancers and routers included: what the pickers offer
  'modelEntities.list': { access: 'config:read', method: 'GET', path: '/model-entities' },

  // model access and guardrails, the same on every model of the workspace
  'modelAccess.get': { access: 'config:read', method: 'GET', path: '/model-access' },
  'modelAccess.save': { access: 'config:write', method: 'PUT', path: '/model-access', audit: true },
  'guardrails.get': { access: 'config:read', method: 'GET', path: '/guardrails' },
  'guardrails.save': { access: 'config:write', method: 'PUT', path: '/guardrails', audit: true },

  // routing
  'routing.get': { access: 'config:read', method: 'GET', path: '/routing' },
  'routing.save': { access: 'config:write', method: 'PUT', path: '/routing', audit: true },
  'balancers.list': { access: 'config:read', method: 'GET', path: '/load-balancers' },
  'balancers.get': { access: 'config:read', method: 'GET', path: '/load-balancers/:lid' },
  'balancers.create': { access: 'config:write', method: 'POST', path: '/load-balancers', audit: true },
  'balancers.update': { access: 'config:write', method: 'PUT', path: '/load-balancers/:lid', audit: true },
  'balancers.delete': { access: 'config:write', method: 'DELETE', path: '/load-balancers/:lid', audit: true },
  'routers.list': { access: 'config:read', method: 'GET', path: '/routers' },
  'routers.get': { access: 'config:read', method: 'GET', path: '/routers/:rid' },
  'routers.create': { access: 'config:write', method: 'POST', path: '/routers', audit: true },
  'routers.update': { access: 'config:write', method: 'PUT', path: '/routers/:rid', audit: true },
  'routers.delete': { access: 'config:write', method: 'DELETE', path: '/routers/:rid', audit: true },

  // presets
  'presets.list': { access: 'config:read', method: 'GET', path: '/presets' },
  'presets.get': { access: 'config:read', method: 'GET', path: '/presets/:pid' },
  'presets.create': { access: 'config:write', method: 'POST', path: '/presets', audit: true },
  'presets.update': { access: 'config:write', method: 'PUT', path: '/presets/:pid', audit: true },
  'presets.delete': { access: 'config:write', method: 'DELETE', path: '/presets/:pid', audit: true },
  // the presets a chat can pick, for who chats without reading the configuration (AI Studio Enterprise only
  // gives their id and name)
  'chat.presets': { access: 'chat:use', method: 'GET', path: '/presets' },

  // tools: `kind` is functions, mcp or search
  'tools.list': { access: 'config:read', method: 'GET', path: '/tools' },
  'tools.listOfKind': { access: 'config:read', method: 'GET', path: '/tools/:kind' },
  'tools.get': { access: 'config:read', method: 'GET', path: '/tools/:kind/:tid' },
  'tools.create': { access: 'config:write', method: 'POST', path: '/tools/:kind', audit: true },
  'tools.update': { access: 'config:write', method: 'PUT', path: '/tools/:kind/:tid', audit: true },
  'tools.delete': { access: 'config:write', method: 'DELETE', path: '/tools/:kind/:tid', audit: true },

  // the MCP server of the workspace
  'mcpServer.get': { access: 'config:read', method: 'GET', path: '/mcp-server' },
  'mcpServer.save': { access: 'config:write', method: 'PUT', path: '/mcp-server', audit: true },
  'mcpServer.delete': { access: 'config:write', method: 'DELETE', path: '/mcp-server', audit: true },

  // api keys
  'keys.list': { access: 'config:read', method: 'GET', path: '/apikeys' },
  'keys.get': { access: 'config:read', method: 'GET', path: '/apikeys/:kid' },
  // the same key with its secret: AI Studio Enterprise masks it everywhere else, and records who revealed it
  'keys.reveal': { access: 'keys:manage', method: 'GET', path: '/apikeys/:kid', audit: true, reveals: true },
  'keys.create': { access: 'keys:manage', method: 'POST', path: '/apikeys', audit: true, secretOnce: true },
  'keys.update': { access: 'keys:manage', method: 'PUT', path: '/apikeys/:kid', audit: true },
  'keys.resetSecret': { access: 'keys:manage', method: 'POST', path: '/apikeys/:kid/_reset-secret', audit: true, secretOnce: true },
  'keys.delete': { access: 'keys:manage', method: 'DELETE', path: '/apikeys/:kid', audit: true },
  // the keys of the person calling, for who does not read the configuration: created for them, and once created
  // only renamed, disabled, reset or deleted (what restricts a key is set by who manages the keys)
  'mykeys.list': { access: 'keys:own', method: 'GET', path: '/apikeys', own: 'apikeys' },
  'mykeys.reveal': { access: 'keys:own', method: 'GET', path: '/apikeys/:kid', audit: true, reveals: true, own: 'apikeys' },
  'mykeys.create': { access: 'keys:own', method: 'POST', path: '/apikeys', audit: true, secretOnce: true, own: 'apikeys' },
  'mykeys.update': { access: 'keys:own', method: 'PUT', path: '/apikeys/:kid', audit: true, own: 'apikeys' },
  'mykeys.resetSecret': { access: 'keys:own', method: 'POST', path: '/apikeys/:kid/_reset-secret', audit: true, secretOnce: true, own: 'apikeys' },
  'mykeys.delete': { access: 'keys:own', method: 'DELETE', path: '/apikeys/:kid', audit: true, own: 'apikeys' },

  // budgets
  'budgets.list': { access: 'config:read', method: 'GET', path: '/budgets' },
  'budgets.get': { access: 'config:read', method: 'GET', path: '/budgets/:bid' },
  'budgets.consumption': { access: 'config:read', method: 'GET', path: '/budgets/:bid/consumption' },
  'budgets.create': { access: 'config:write', method: 'POST', path: '/budgets', audit: true },
  'budgets.update': { access: 'config:write', method: 'PUT', path: '/budgets/:bid', audit: true },
  'budgets.delete': { access: 'config:write', method: 'DELETE', path: '/budgets/:bid', audit: true },
  'budgets.reset': { access: 'config:write', method: 'POST', path: '/budgets/:bid/consumption/_reset', audit: true },
  // the budgets counting the usage of the person calling, naming nobody else
  'mybudgets.list': { access: 'usage:own', method: 'GET', path: '/budgets', own: 'budgets' },

  // usage, always narrowed to the workspace by the api
  'analytics.query': { access: 'usage:own', method: 'POST', path: '/analytics/_query' },
});

// One segment of a path. `encodeURIComponent` leaves `.` and `..` as they are, and a url resolves them: an id of
// `..` would call the route above the one of the operation (`/keys/..` is the workspace itself).
function segment(name, param, value) {
  const text = String(value);
  if (text === '.' || text === '..') throw new Error(`the operation '${name}' got an invalid '${param}'`);
  return encodeURIComponent(text);
}

/**
 * The http call of an operation: `input` gives the `:params` of its path by name, `query` (values left out when
 * null, undefined or false) and `body`. Never an entity the api should trust: the api reads what it needs.
 */
export function routeOf(name, wsId, input = {}) {
  const op = Object.hasOwn(ops, name) ? ops[name] : null;
  if (!op) throw new Error(`unknown operation '${name}'`);
  const path = op.path.replace(/:([a-z]+)/g, (_, param) => {
    const value = input[param];
    if (value === undefined || value === null || value === '') throw new Error(`the operation '${name}' needs '${param}'`);
    return segment(name, param, value);
  });
  const query = Object.entries(input.query || {})
    .filter(([, value]) => value !== undefined && value !== null && value !== false)
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
    .join('&');
  return { method: op.method, path: `/workspaces/${segment(name, 'workspace', wsId)}${path}${query ? `?${query}` : ''}`, body: input.body };
}
