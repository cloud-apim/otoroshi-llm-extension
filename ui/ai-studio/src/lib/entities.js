import { api } from './api';

// Everything AI Studio creates is a plain otoroshi entity. They are all tagged with the following
// metadata so the studio can find them back using the in-memory filters of the admin api.
export const META = {
  flag: 'ai_studio',
  workspace: 'ai_studio_workspace',
  kind: 'ai_studio_kind',
  connection: 'ai_studio_connection',
  // the person the usage of an api key is attributed to in activity and logs
  owner: 'ai_studio_owner',
};

export const Groups = {
  ai: 'ai-gateway.extensions.cloud-apim.com',
  proxy: 'proxy.otoroshi.io',
  apim: 'apim.otoroshi.io',
  organize: 'organize.otoroshi.io',
};

// Reads use the in-memory state of otoroshi (`in_mem=true`). That state is refreshed periodically, so
// right after a write the studio reads the datastore instead to show what was just created.
const FRESH_READS_MS = 20000;
let lastWrite = 0;
const inMem = () => (Date.now() - lastWrite > FRESH_READS_MS ? 'true' : 'false');
const written = (p) => {
  lastWrite = Date.now();
  return p.finally(() => (lastWrite = Date.now()));
};

function resource(group, plural, idField = 'id') {
  const base = `/bo/api/proxy/apis/${group}/v1/${plural}`;
  return {
    idField,
    // filters is a map of json path -> value, for instance { 'metadata.ai_studio_workspace': 'xxx' }
    list(filters = {}) {
      const params = new URLSearchParams({ in_mem: inMem() });
      Object.entries(filters).forEach(([k, v]) => params.append(`filter.${k}`, String(v)));
      return api.get(`${base}?${params.toString()}`).then((r) => (Array.isArray(r) ? r : []));
    },
    get(id) {
      return api.get(`${base}/${encodeURIComponent(id)}?in_mem=${inMem()}`);
    },
    template(params = {}) {
      const qs = new URLSearchParams(params).toString();
      return api.get(`${base}/_template${qs ? '?' + qs : ''}`);
    },
    create(entity) {
      return written(api.post(base, entity));
    },
    update(entity) {
      return written(api.put(`${base}/${encodeURIComponent(entity[idField])}`, entity));
    },
    delete(id) {
      return written(api.delete(`${base}/${encodeURIComponent(id)}`));
    },
  };
}

export const Resources = {
  routes: resource(Groups.proxy, 'routes'),
  apikeys: resource(Groups.apim, 'apikeys', 'clientId'),
  teams: resource(Groups.organize, 'teams'),
  providers: resource(Groups.ai, 'providers'),
  embeddingModels: resource(Groups.ai, 'embedding-models'),
  imageModels: resource(Groups.ai, 'image-models'),
  audioModels: resource(Groups.ai, 'audio-models'),
  moderationModels: resource(Groups.ai, 'moderation-models'),
  ocrModels: resource(Groups.ai, 'ocr-models'),
  videoModels: resource(Groups.ai, 'video-models'),
  contexts: resource(Groups.ai, 'prompt-contexts'),
  functions: resource(Groups.ai, 'tool-functions'),
  mcpConnectors: resource(Groups.ai, 'mcp-connectors'),
  searchEngines: resource(Groups.ai, 'search-engines'),
  budgets: resource(Groups.ai, 'ai-budgets'),
};

export function workspaceFilter(workspaceId, kind) {
  const f = { [`metadata.${META.workspace}`]: workspaceId };
  if (kind) f[`metadata.${META.kind}`] = kind;
  return f;
}

export function randomId(size = 16) {
  const alphabet = 'abcdefghijklmnopqrstuvwxyz0123456789';
  const bytes = new Uint8Array(size);
  window.crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => alphabet[b % alphabet.length]).join('');
}

export function slugify(value) {
  return (value || '')
    .toLowerCase()
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .substring(0, 48);
}
