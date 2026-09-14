import { api, EXT_BO_API } from './api';
import { META, Resources, randomId, workspaceFilter } from './entities';
import { MODALITIES, syncWorkspaceRefs, workspaceLocation, workspaceMetadata } from './workspaces';

// A "connection" is what the user sees in the Providers page: one provider kind + credentials,
// materialized as one otoroshi entity per enabled modality (text provider, embedding model, image
// model, ...). Entities of the same connection share `metadata.ai_studio_connection`.

export const DISABLED_META = 'ai_studio_disabled';

export const MODALITY_LABELS = {
  text: 'Text',
  embedding: 'Embedding',
  image: 'Image',
  audio: 'Audio',
  moderation: 'Moderation',
  ocr: 'OCR',
  video: 'Video',
};

// connection names become the model prefix (`<name>/<model>`), keep them simple
export function connectionName(value) {
  return (value || '')
    .toLowerCase()
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/[^a-z0-9_]+/g, '_')
    .replace(/_+/g, '_')
    .replace(/^_+|_+$/g, '')
    .substring(0, 40);
}

function modelOfEntity(modality, entity) {
  if (modality === 'text') return entity.options && entity.options.model;
  const config = entity.config || {};
  const options = config.options || {};
  if (modality === 'image') return (options.generation && options.generation.model) || options.model;
  if (modality === 'audio') return (config.tts && config.tts.model) || (options.tts && options.tts.model);
  return options.model;
}

function sttModelOfEntity(entity) {
  const config = entity.config || {};
  const options = config.options || {};
  return (config.stt && (config.stt.model || config.stt.model_id)) || (options.stt && options.stt.model);
}

function connectionOfEntity(modality, entity) {
  return (modality === 'text' ? entity.connection : entity.config && entity.config.connection) || {};
}

export async function listConnections(wsId) {
  const filter = workspaceFilter(wsId);
  const lists = await Promise.all(MODALITIES.map((m) => Resources[m.resource].list(filter)));
  const byId = {};
  MODALITIES.forEach((m, idx) => {
    lists[idx].forEach((entity) => {
      const connId = (entity.metadata && entity.metadata[META.connection]) || entity.id;
      if (!byId[connId]) {
        byId[connId] = { id: connId, name: entity.name, kind: entity.provider, description: entity.description || '', modalities: {}, entities: {} };
      }
      const conn = byId[connId];
      conn.entities[m.id] = entity;
      conn.modalities[m.id] = {
        enabled: true,
        model: modelOfEntity(m.id, entity) || '',
        stt_model: m.id === 'audio' ? sttModelOfEntity(entity) || '' : undefined,
      };
      // the text provider (or the first entity found) carries the connection settings
      if (m.id === 'text' || !conn.connection) {
        const c = connectionOfEntity(m.id, entity);
        conn.connection = c;
        conn.base_url = c.base_url || c.base_domain || '';
        conn.token = c.token || c.api_key || '';
        conn.timeout = c.timeout || 180000;
        conn.enabled = !(entity.metadata && entity.metadata[DISABLED_META] === 'true');
      }
    });
  });
  return Object.values(byId).sort((a, b) => a.name.localeCompare(b.name));
}

function buildConnection(kind, conn, catalogEntry) {
  const c = { timeout: Number(conn.timeout) || 180000 };
  if (conn.base_url) c.base_url = conn.base_url;
  else if (catalogEntry && catalogEntry.base_url) c.base_url = catalogEntry.base_url;
  if (conn.token) c.token = conn.token;
  (catalogEntry && catalogEntry.fields ? catalogEntry.fields : []).forEach((f) => {
    const v = conn.fields && conn.fields[f.name];
    if (v !== undefined && v !== '') c[f.name] = v;
    else if (f.default) c[f.name] = f.default;
  });
  if (kind === 'azure-openai') {
    // pre-v1 azure apis use the `api-key` header, v1 uses a bearer token
    c.api_key = conn.token;
    c.resource_name = c.resource_name || '';
    c.deployment_id = c.deployment_id || '';
  }
  if (kind === 'anthropic') c.version = (conn.connection && conn.connection.version) || '2023-06-01';
  if (kind === 'openai-compatible') {
    Object.assign(c, {
      supports_completion: true,
      supports_tools: true,
      supports_streaming: true,
      models_path: '/models',
      headers: { Authorization: 'Bearer {api_key}' },
      ...((conn.connection && pick(conn.connection, ['supports_completion', 'supports_tools', 'supports_streaming', 'models_path', 'headers', 'param_mappings', 'additional_body_params'])) || {}),
    });
  }
  return c;
}

function pick(obj, keys) {
  const r = {};
  keys.forEach((k) => {
    if (obj[k] !== undefined) r[k] = obj[k];
  });
  return r;
}

const ID_PREFIX = {
  text: 'provider',
  embedding: 'embedding-model',
  image: 'image-model',
  audio: 'audio-model',
  moderation: 'moderation-model',
  ocr: 'ocr-model',
  video: 'video-model',
};

export function buildEntity(modality, wsId, conn, catalogEntry, existing) {
  const kind = conn.kind;
  const mod = conn.modalities[modality] || {};
  const model = (mod.model || '').trim();
  const connection = buildConnection(kind, conn, catalogEntry);
  const metadata = {
    ...((existing && existing.metadata) || {}),
    ...workspaceMetadata(wsId, MODALITIES.find((m) => m.id === modality).kind, { [META.connection]: conn.id }),
  };
  if (conn.enabled === false) metadata[DISABLED_META] = 'true';
  else delete metadata[DISABLED_META];
  const base = {
    ...(existing || {}),
    _loc: (existing && existing._loc) || workspaceLocation(wsId),
    id: (existing && existing.id) || `${ID_PREFIX[modality]}_ais_${randomId(20)}`,
    name: conn.name,
    description: conn.description || '',
    tags: (existing && existing.tags) || [],
    metadata,
    provider: kind,
  };
  if (modality === 'text') {
    const options = { ...((existing && existing.options) || {}) };
    if (model) options.model = model;
    else delete options.model;
    return {
      models: { include: [], exclude: [] },
      guardrails: [],
      guardrails_fail_on_deny: false,
      ...base,
      connection: { ...((existing && existing.connection) || {}), ...connection },
      options,
    };
  }
  const prevConfig = (existing && existing.config) || {};
  const prevOptions = prevConfig.options || {};
  let config;
  if (modality === 'image') {
    config = {
      ...prevConfig,
      connection,
      options: {
        ...prevOptions,
        generation: { enabled: true, ...(prevOptions.generation || {}), model },
        edition: { enabled: false, ...(prevOptions.edition || {}), model },
      },
    };
  } else if (modality === 'audio') {
    const tts = (mod.model || '').trim();
    const stt = (mod.stt_model || '').trim();
    const ttsConf =
      kind === 'elevenlabs'
        ? { enabled: !!tts, model_id: tts, voice_id: '21m00Tcm4TlvDq8ikWAM', output_format: 'mp3_44100_128' }
        : { enabled: !!tts, model: tts, voice: 'alloy', response_format: 'mp3' };
    const sttConf = kind === 'elevenlabs' ? { enabled: !!stt, model_id: stt } : { enabled: !!stt, model: stt };
    const translate = { enabled: false };
    // the audio client reads tts/stt at the root of the config, the admin ui writes them in options:
    // write both so the entity behaves the same in both places
    config = {
      ...prevConfig,
      connection,
      tts: { ...(prevConfig.tts || {}), ...ttsConf },
      stt: { ...(prevConfig.stt || {}), ...sttConf },
      translate: { ...translate, ...(prevConfig.translate || {}) },
      options: {
        ...prevOptions,
        tts: { ...(prevOptions.tts || {}), ...ttsConf },
        stt: { ...(prevOptions.stt || {}), ...sttConf },
        translation: { ...translate, ...(prevOptions.translation || {}) },
      },
    };
  } else if (modality === 'video') {
    config = { ...prevConfig, connection, options: { enabled: true, ...prevOptions, model } };
  } else {
    config = { ...prevConfig, connection, options: { ...prevOptions, model } };
  }
  return { models: { include: [], exclude: [] }, ...base, config };
}

export async function saveConnection(wsId, conn, catalogEntry) {
  const capabilities = (catalogEntry && catalogEntry.capabilities) || Object.keys(conn.modalities);
  for (const m of MODALITIES) {
    const resource = Resources[m.resource];
    const existing = conn.entities && conn.entities[m.id];
    const wanted = capabilities.includes(m.id) && conn.modalities[m.id] && conn.modalities[m.id].enabled;
    if (wanted) {
      const entity = buildEntity(m.id, wsId, conn, catalogEntry, existing);
      if (existing && existing.id) await resource.update(entity);
      else await resource.create(entity);
    } else if (existing && existing.id) {
      await resource.delete(existing.id);
    }
  }
  await syncWorkspaceRefs(wsId);
}

export async function deleteConnection(wsId, conn) {
  for (const m of MODALITIES) {
    const existing = conn.entities && conn.entities[m.id];
    if (existing) await Resources[m.resource].delete(existing.id);
  }
  await syncWorkspaceRefs(wsId);
}

// asks the provider for its models, using the draft text provider built from the form
export async function fetchProviderModels(wsId, conn, catalogEntry, force = false) {
  const draft = buildEntity('text', wsId, { ...conn, modalities: { ...conn.modalities, text: { enabled: true, model: 'x' } } }, catalogEntry, conn.entities && conn.entities.text);
  const res = await api.post(`${EXT_BO_API}/providers/_models${force ? '?force=true' : ''}`, draft);
  if (!res || !res.done) throw new Error((res && (typeof res.error === 'string' ? res.error : JSON.stringify(res.error))) || 'unable to fetch models');
  return (res.models || []).map(String);
}

export function newConnection(catalogEntry, existingNames = []) {
  let name = connectionName(catalogEntry.id);
  let i = 2;
  while (existingNames.includes(name)) name = `${connectionName(catalogEntry.id)}_${i++}`;
  const modalities = {};
  (catalogEntry.capabilities || []).forEach((cap) => {
    const models = catalogEntry.models || {};
    if (cap === 'audio') {
      modalities.audio = { enabled: false, model: models.audio_tts || '', stt_model: models.audio_stt || '' };
    } else {
      modalities[cap] = { enabled: cap === 'text', model: models[cap] || '' };
    }
  });
  // providers without text capability: enable their first modality
  if (!modalities.text) {
    const first = Object.keys(modalities)[0];
    if (first) modalities[first].enabled = true;
  }
  return {
    id: `conn_${randomId(12)}`,
    name,
    kind: catalogEntry.id,
    description: '',
    base_url: catalogEntry.base_url || '',
    token: '',
    timeout: 180000,
    enabled: true,
    fields: Object.fromEntries((catalogEntry.fields || []).map((f) => [f.name, f.default || ''])),
    modalities,
    entities: {},
  };
}

export function fieldsFromConnection(conn, catalogEntry) {
  return Object.fromEntries(((catalogEntry && catalogEntry.fields) || []).map((f) => [f.name, (conn.connection && conn.connection[f.name]) || f.default || '']));
}
