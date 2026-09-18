// The playgrounds of the Models page: one small form for each kind of model that is not a chat model, calling
// the workspace endpoint exactly the way an application would (same path, same body, same api key rules).
// Nothing is stored: a run is one call, shown and forgotten.

import { gatewayError } from './api';
import { currentTenant } from './bootstrap';
import { generateImage } from './images';
import { endpointsOf, KIND_LABELS, kindsOf } from './modelmeta';
import { billedProxyUrl } from './models';

// a file sent to a playground: large enough for a song or a scanned contract
export const MAX_UPLOAD_BYTES = 20 * 1024 * 1024;

/**
 * What a model can be tried with. `endpoint` is the OpenAI endpoint of the metadata, `modality` the kind of
 * entity serving it when the metadata says nothing.
 */
export const PLAYGROUNDS = [
  {
    id: 'image',
    label: 'Image',
    endpoint: 'images_generations',
    modality: 'image',
    input: 'text',
    placeholder: 'A red panda coding on a laptop, watercolor',
    action: 'Generate',
    hint: 'Describes what the model draws.',
  },
  {
    id: 'tts',
    label: 'Speech',
    endpoint: 'audio_speech',
    modality: 'audio',
    input: 'text',
    placeholder: 'Hello, this voice comes from the AI gateway.',
    action: 'Speak',
    hint: 'The text the model reads out loud, with the voice of its connection.',
  },
  {
    id: 'stt',
    label: 'Transcription',
    endpoint: 'audio_transcriptions',
    modality: 'audio',
    input: 'file',
    accept: 'audio/*,video/mp4,.mp3,.wav,.m4a,.ogg,.webm,.flac',
    action: 'Transcribe',
    hint: 'Drop an audio file, mp3, wav, m4a, ogg or flac.',
  },
  {
    id: 'embedding',
    label: 'Embedding',
    endpoint: 'embeddings',
    modality: 'embedding',
    input: 'text',
    placeholder: 'The quick brown fox jumps over the lazy dog',
    action: 'Embed',
    hint: 'One text per line: each one gets its own vector.',
  },
  {
    id: 'moderation',
    label: 'Moderation',
    endpoint: 'moderations',
    modality: 'moderation',
    input: 'text',
    placeholder: 'I want to hurt them all',
    action: 'Moderate',
    hint: 'The text the model rates against its categories.',
  },
  {
    id: 'ocr',
    label: 'OCR',
    endpoint: 'ocr',
    modality: 'ocr',
    input: 'file',
    accept: 'application/pdf,image/*,.pdf,.png,.jpg,.jpeg,.webp,.tiff',
    action: 'Extract',
    hint: 'Drop a PDF or a picture of a document.',
  },
];

export const playgroundById = (id) => PLAYGROUNDS.find((p) => p.id === id) || null;

// how the gateway is asked for one model of a connection: the same `<connection>/<model>` ids as everywhere
const modelId = (slug, model) => (model.includes('/') ? `${slug}###${model}` : `${slug}/${model}`);

/**
 * The playgrounds a model can be tried with, each with the id to call it by.
 *
 * Two things have to agree. What the model is: the endpoints the gateway knows it is served on, or the kind
 * of model it is when it knows nothing more (an audio model then offers both ways). And what the connection
 * serving it can be asked for: a connection exposes one entity per modality, and that entity serves every
 * model of its kind — an image entity draws `gpt-image-2` as well as the model it carries as a default — so
 * the image models an LLM connection lists are playable as soon as that connection has its image capability
 * on. Without it the gateway has nothing to route the call to, and no playground is offered.
 */
// what this model is: the endpoints the gateway knows it is served on, or the kind of model it is
function waysOf(model) {
  const endpoints = endpointsOf(model);
  const kinds = kindsOf(model);
  return PLAYGROUNDS.filter((p) => (endpoints.length > 0 ? endpoints.includes(p.endpoint) : kinds.includes(p.modality)));
}

// the connection serving `model` for this playground, when it can be asked for it
const servingOf = (model, providers, p) =>
  providers.find((i) => i.modality === p.modality && i.slug === model.provider && !i.error && (i.endpoints || []).includes(p.endpoint));

export function playgroundsOf(model, providers = []) {
  if (!model) return [];
  return waysOf(model)
    .map((p) => {
      const serving = servingOf(model, providers, p);
      return serving ? { ...p, model: modelId(serving.slug, model.model || model.id) } : null;
    })
    .filter(Boolean);
}

/**
 * Why a model that could be tried cannot be, in words: its connection does not expose that capability, so
 * the gateway has no entity to route the call to. Null when the model has a playground, or none to have.
 */
export function playgroundHint(model, providers = []) {
  if (!model) return null;
  const ways = waysOf(model);
  if (ways.length === 0 || ways.some((p) => servingOf(model, providers, p))) return null;
  const capabilities = [...new Set(ways.map((p) => KIND_LABELS[p.modality] || p.modality))];
  return `${capabilities.join(' / ')} is off on the ${model.provider} connection: turn it on to call this model, here and from your applications.`;
}

// one call to the workspace endpoint, as the signed-in backoffice user
async function call(workspace, path, { body, form, signal }) {
  const started = Date.now();
  const res = await fetch(billedProxyUrl(workspace, path), {
    method: 'POST',
    credentials: 'include',
    signal,
    headers: {
      Accept: 'application/json',
      'Otoroshi-Tenant': currentTenant(),
      // a FormData body carries its own content type, with the boundary the gateway parses
      ...(form ? {} : { 'Content-Type': 'application/json' }),
    },
    body: form || JSON.stringify(body),
  });
  if (!res.ok) throw gatewayError(await res.text(), res.status, res.statusText);
  return { res, duration: Date.now() - started };
}

const usageOf = (json) => (json && json.usage) || null;
const costsOf = (json) => (json && (json.costs || (json.usage && json.usage.costs))) || null;

function checkFile(file) {
  if (!file) throw new Error('pick a file first');
  if (file.size > MAX_UPLOAD_BYTES) throw new Error(`${file.name} is too large, ${Math.round(MAX_UPLOAD_BYTES / (1024 * 1024))} MB at most`);
}

async function runImage({ workspace, model, text, signal }) {
  const result = await generateImage({ workspace, model, prompt: text, signal });
  return { images: result.images, text: result.content, usage: result.usage, costs: result.costs, duration: result.duration };
}

async function runSpeech({ workspace, model, text, signal }) {
  const { res, duration } = await call(workspace, '/audio/speech', { body: { model, input: text }, signal });
  const blob = await res.blob();
  return { audio: { url: URL.createObjectURL(blob), type: blob.type || 'audio/mpeg', size: blob.size }, duration };
}

async function runTranscription({ workspace, model, file, signal }) {
  checkFile(file);
  const form = new FormData();
  form.append('model', model);
  form.append('file', file, file.name);
  const { res, duration } = await call(workspace, '/audio/transcriptions', { form, signal });
  const json = await res.json();
  return { text: json.text || '', usage: usageOf(json), costs: costsOf(json), duration, raw: json };
}

async function runEmbedding({ workspace, model, text, signal }) {
  const input = text.split('\n').map((l) => l.trim()).filter(Boolean);
  const { res, duration } = await call(workspace, '/embeddings', { body: { model, input: input.length > 1 ? input : input[0] || '' }, signal });
  const json = await res.json();
  return { vectors: (json.data || []).map((d) => d.embedding || []), inputs: input, usage: usageOf(json), costs: costsOf(json), duration, raw: json };
}

async function runModeration({ workspace, model, text, signal }) {
  const { res, duration } = await call(workspace, '/moderations', { body: { model, input: text }, signal });
  const json = await res.json();
  return { moderation: (json.results || [])[0] || null, usage: usageOf(json), costs: costsOf(json), duration, raw: json };
}

async function runOcr({ workspace, model, file, signal }) {
  checkFile(file);
  const form = new FormData();
  form.append('model', model);
  form.append('file', file, file.name);
  const { res, duration } = await call(workspace, '/ocr', { form, signal });
  const json = await res.json();
  const pages = json.pages || [];
  return {
    text: json.text || pages.map((p) => p.markdown).filter(Boolean).join('\n\n'),
    pages: pages.length,
    usage: json.usage_info || null,
    duration,
    raw: json,
  };
}

const RUNNERS = { image: runImage, tts: runSpeech, stt: runTranscription, embedding: runEmbedding, moderation: runModeration, ocr: runOcr };

/** Runs one playground, `input` being `{ text }` or `{ file }`. Throws what to show the user. */
export function runPlayground(id, { workspace, model, text = '', file = null, signal }) {
  const runner = RUNNERS[id];
  if (!runner) return Promise.reject(new Error('unknown playground'));
  return runner({ workspace, model, text, file, signal });
}
