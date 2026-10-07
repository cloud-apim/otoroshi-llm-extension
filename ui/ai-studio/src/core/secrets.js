import { ops } from './ops.js';

// The secrets of the views of the studio api, which AI Studio Enterprise never sends to a browser: a connection
// token, the token and the headers of a tool. They are replaced by this sentinel, and a write sending the
// sentinel back keeps the stored value (the studio api reads it as "unchanged"). The OSS studio shows the views
// as they are, its users being admins of the gateway.
export const SECRET_SENTINEL = '__ai_studio_secret__';

// a vault reference is not a secret, it only names where one is
const isVaultRef = (value) => typeof value === 'string' && value.startsWith('${vault://');

// The secret fields of a view: a dotted path, `*` for every field of an object.
const CONNECTION = ['token'];
const TOOL = ['token', 'headers.*'];
// the secret of an api key is given once, when it is created or reset, and then only by `keys.reveal`
const APIKEY = ['client_secret', 'bearer'];

// the secret fields of the result of each operation, `[]` reading every item of a list
export const SECRET_FIELDS = {
  'providers.list': CONNECTION.map((f) => `[].${f}`),
  'providers.get': CONNECTION,
  'providers.create': CONNECTION,
  'providers.update': CONNECTION,
  'tools.list': TOOL.map((f) => `[].${f}`),
  'tools.listOfKind': TOOL.map((f) => `[].${f}`),
  'tools.get': TOOL,
  'tools.create': TOOL,
  'tools.update': TOOL,
  'keys.list': APIKEY.map((f) => `[].${f}`),
  'keys.get': APIKEY,
  'keys.update': APIKEY,
};

// A second pass after the declared fields, on what reads the configuration: any field whose name says it holds
// a secret. It keeps a field added to a view later from leaking before it is declared.
const SECRET_NAMES = /token|secret|password|api_?key|authorization/i;

function maskPath(value, segments) {
  if (value === null || value === undefined) return value;
  const [head, ...rest] = segments;
  if (head === '[]') return Array.isArray(value) ? value.map((item) => maskPath(item, rest)) : value;
  if (typeof value !== 'object' || Array.isArray(value)) return value;
  const keys = head === '*' ? Object.keys(value) : [head];
  const next = { ...value };
  keys.forEach((key) => {
    if (!(key in next)) return;
    if (rest.length > 0) next[key] = maskPath(next[key], rest);
    else if (next[key] !== null && next[key] !== '' && !isVaultRef(next[key])) next[key] = SECRET_SENTINEL;
  });
  return next;
}

function maskByName(value) {
  if (Array.isArray(value)) return value.map(maskByName);
  if (value === null || typeof value !== 'object') return value;
  return Object.fromEntries(
    Object.entries(value).map(([key, v]) => {
      if (SECRET_NAMES.test(key) && typeof v === 'string' && v !== '' && !isVaultRef(v)) return [key, SECRET_SENTINEL];
      if (SECRET_NAMES.test(key) && v && typeof v === 'object' && !Array.isArray(v)) return [key, maskPath(v, ['*'])];
      return [key, maskByName(v)];
    })
  );
}

/**
 * The result of an operation as it can be sent to a browser: its declared secret fields replaced by the
 * sentinel, then, for what reads the configuration, any field named like a secret. The result of an operation
 * revealing a secret, and the one of a creation or a reset of an api key (its only chance to be shown), are
 * left as they are.
 */
export function maskSecrets(name, result) {
  const op = ops[name];
  if (!op || op.reveals || name === 'keys.create' || name === 'keys.resetSecret') return result;
  const declared = (SECRET_FIELDS[name] || []).reduce((acc, path) => maskPath(acc, path.split('.')), result);
  return op.access === 'config:read' ? maskByName(declared) : declared;
}
