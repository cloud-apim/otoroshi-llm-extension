import { randomId } from './ids';
import { backend } from './backend';

// A "connection" is what the user sees in the Providers page: one provider kind + credentials, materialized
// as one otoroshi entity per enabled capability (text provider, embedding model, image model, ...). The studio
// admin api builds those entities and shows a connection as one view (`connectionJson` in studio/api.scala):
// `entities` gives the id of the entity of each capability, `tools` the tools its text models can call,
// `options` the raw options of its text provider. What is left here helps the provider form.

export const MODALITY_LABELS = {
  text: 'Text',
  embedding: 'Embedding',
  image: 'Image',
  audio: 'Audio',
  moderation: 'Moderation',
  ocr: 'OCR',
  video: 'Video',
  decision: 'Decision',
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

// The models of a connection being edited, each with what the gateway knows of it (`details`, see
// lib/modelmeta.js): from its saved settings for a saved connection, with what the form changed on top. Every
// model is listed, the ones with no known price included: the form tells which ones a strict provider refuses.
export async function fetchProviderModels(wsId, conn, isNew, force = false) {
  const body = { kind: conn.kind, base_url: conn.base_url || '', token: conn.token || '', fields: conn.fields || {}, ...(isNew ? {} : { connection_id: conn.id }) };
  const res = await backend.run('providers.draftModels', wsId, { query: { enriched: true, force }, body });
  const details = res.details || {};
  return (res.models || []).map((id) => ({ id: String(id), model: String(id), provider: conn.name, modality: 'text', details: details[id] }));
}

/**
 * The connection with a model on every capability it has on: a capability saved without one makes an entity
 * with nothing to call (and, for audio, one that is simply off), so the model the catalog documents becomes
 * the value of the field rather than its placeholder.
 */
export function withModelDefaults(conn, catalogEntry) {
  const models = (catalogEntry && catalogEntry.models) || {};
  const modalities = Object.fromEntries(
    Object.entries(conn.modalities || {}).map(([id, mod]) => {
      if (!mod || !mod.enabled) return [id, mod];
      if (id === 'audio') return [id, { ...mod, model: mod.model || models.audio_tts || '', stt_model: mod.stt_model || models.audio_stt || '' }];
      return [id, { ...mod, model: mod.model || models[id] || '' }];
    })
  );
  return { ...conn, modalities };
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
    require_known_costs: false,
    fields: Object.fromEntries((catalogEntry.fields || []).map((f) => [f.name, f.default || ''])),
    modalities,
    entities: {},
  };
}
