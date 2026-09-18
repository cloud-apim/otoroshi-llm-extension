// What the gateway knows of a model (`metadata` of the workspace models listing, `details` of a provider models
// listing): types, cost, api, capabilities, modalities, limits and prices. See `catalog/modelscatalog.scala`.

export const KIND_ORDER = ['text', 'image', 'audio', 'embedding', 'moderation', 'ocr', 'video'];

export const KIND_LABELS = {
  text: 'Text',
  image: 'Image',
  audio: 'Audio',
  embedding: 'Embedding',
  moderation: 'Moderation',
  ocr: 'OCR',
  video: 'Video',
};

export const ENDPOINT_LABELS = {
  chat_completions: 'Chat completions',
  completions: 'Completions',
  responses: 'Responses',
  embeddings: 'Embeddings',
  images_generations: 'Image generation',
  images_edits: 'Image edition',
  images_variations: 'Image variations',
  audio_speech: 'Speech',
  audio_transcriptions: 'Transcription',
  audio_translations: 'Translation',
  moderations: 'Moderation',
  ocr: 'OCR',
  realtime: 'Realtime',
  videos: 'Videos',
};

export const MODALITY_NAMES = { text: 'text', image: 'images', pdf: 'PDF', audio: 'audio', video: 'video' };

export function metaOf(model) {
  return (model && (model.metadata || model.details)) || {};
}

export function kindsOf(model) {
  const meta = model && (model.metadata || model.details);
  if (meta && Array.isArray(meta.kinds)) return meta.kinds;
  return model && model.modality ? [model.modality] : [];
}

export function inputsOf(model) {
  const m = metaOf(model).modalities;
  return (m && m.input) || [];
}

export function outputsOf(model) {
  const m = metaOf(model).modalities;
  return (m && m.output) || [];
}

// a model of the workspace that draws rather than writes: the chat sends it to the images endpoint
export const imageGenerator = (model) => !!model && model.modality === 'image';

// an answer of this model can carry images, so the chat asks for it without streaming (a stream drops them)
export const imageOutput = (model) => outputsOf(model).includes('image');

export function endpointsOf(model) {
  return metaOf(model).endpoints || [];
}

function capability(model, name) {
  const caps = metaOf(model).capabilities;
  return !!(caps && caps[name]);
}

export const CAPABILITIES = [
  { id: 'reasoning', label: 'Reasoning', title: 'Thinks before answering', test: (m) => capability(m, 'reasoning') },
  { id: 'tool_call', label: 'Tools', title: 'Calls tools and functions', test: (m) => capability(m, 'tool_call') },
  { id: 'structured_output', label: 'Structured output', title: 'Answers following a JSON schema', test: (m) => capability(m, 'structured_output') },
  { id: 'vision', label: 'Vision', title: 'Understands images', test: (m) => inputsOf(m).includes('image') },
  { id: 'pdf', label: 'PDF', title: 'Reads PDF documents', test: (m) => inputsOf(m).includes('pdf') },
  { id: 'audio_input', label: 'Audio input', title: 'Understands audio', test: (m) => inputsOf(m).includes('audio') },
  { id: 'video_input', label: 'Video input', title: 'Understands videos', test: (m) => inputsOf(m).includes('video') },
  { id: 'prompt_caching', label: 'Prompt caching', title: 'Repeated prompt prefixes are billed less', test: (m) => capability(m, 'prompt_caching') },
  { id: 'web_search', label: 'Web search', title: 'Searches the web by itself', test: (m) => capability(m, 'web_search') },
];

export function capabilitiesOf(model) {
  return CAPABILITIES.filter((c) => c.test(model));
}

// dollars per token, as the api gives them, to dollars per million tokens
export function perMillion(value) {
  if (value === undefined || value === null || value === '') return null;
  const n = Number(value);
  return Number.isNaN(n) ? null : n * 1000000;
}

export function fmtPrice(value) {
  if (value === null || value === undefined) return '—';
  if (value === 0) return 'Free';
  if (value >= 100) return '$' + Math.round(value);
  if (value >= 0.1) return '$' + value.toFixed(2).replace(/\.00$/, '');
  return '$' + Number(value.toPrecision(2));
}

const tokens = new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 2 });

export function fmtTokens(value) {
  if (!value) return '—';
  return tokens.format(value);
}

const perToken = (value) => {
  if (value === undefined || value === null || value === '') return null;
  const n = Number(value);
  return Number.isNaN(n) ? null : n;
};

// What a workload costs on a model at its list prices, null when the model has no token price. `cached` is the
// share (0 to 1) of the input read from the prompt cache, billed at the cache price when the model has one.
// Embedding and moderation models only bill their input.
export function estimateCost(model, { input = 0, output = 0, cached = 0, requests = 1 }) {
  const pricing = metaOf(model).pricing;
  if (!pricing) return null;
  const prompt = perToken(pricing.prompt);
  if (prompt === null) return null;
  const kinds = kindsOf(model);
  const inputOnly = kinds.length > 0 && kinds.every((k) => k === 'embedding' || k === 'moderation');
  const completion = inputOnly ? 0 : perToken(pricing.completion);
  if (completion === null) return null;
  const cacheRead = perToken(pricing.input_cache_read);
  const inputTokens = Math.max(0, Number(input) || 0);
  const cachedTokens = cacheRead === null ? 0 : inputTokens * Math.min(1, Math.max(0, Number(cached) || 0));
  const perRequest = (inputTokens - cachedTokens) * prompt + cachedTokens * cacheRead + (inputOnly ? 0 : Math.max(0, Number(output) || 0)) * completion;
  return { perRequest, total: perRequest * Math.max(0, Number(requests) || 0), cached: cachedTokens > 0, inputOnly };
}

export function promptPrice(model) {
  const p = metaOf(model).pricing;
  return p ? perMillion(p.prompt) : null;
}

export function completionPrice(model) {
  const p = metaOf(model).pricing;
  return p ? perMillion(p.completion) : null;
}

export function contextOf(model) {
  const l = metaOf(model).limits;
  return (l && (l.context || l.input)) || null;
}

export function hasCost(model) {
  return metaOf(model).has_cost === true;
}

// why the chat of the studio cannot use a model, null when it can
export function chatUnavailableReason(model) {
  if (model.modality && model.modality !== 'text') return `A ${KIND_LABELS[model.modality] || model.modality} model, not a chat model`;
  const meta = model.metadata || model.details;
  if (!meta) return null;
  const kinds = kindsOf(model);
  if (!kinds.includes('text')) {
    return kinds.length ? `A ${kinds.map((k) => KIND_LABELS[k] || k).join(' / ').toLowerCase()} model, not a chat model` : 'Not a chat model';
  }
  const endpoints = meta.endpoints || [];
  if (meta.openai_compatible === true && endpoints.length > 0 && !endpoints.includes('chat_completions')) {
    return `Only served on the ${endpoints.map((e) => ENDPOINT_LABELS[e] || e).join(', ')} API`;
  }
  return null;
}

export function chatUsable(model) {
  return chatUnavailableReason(model) === null;
}

// the models of a provider listing fitting a capability of a connection (the default model pickers)
export function fitsCapability(model, capability, audioMode) {
  const kinds = kindsOf(model);
  if (capability === 'text') return kinds.includes('text') && chatUsable(model);
  if (capability !== 'audio') return kinds.includes(capability);
  if (!kinds.includes('audio')) return false;
  const endpoints = endpointsOf(model);
  const inputs = inputsOf(model);
  const outputs = outputsOf(model);
  if (audioMode === 'tts') return endpoints.length ? endpoints.includes('audio_speech') : outputs.includes('audio') && !inputs.includes('audio');
  return endpoints.length
    ? endpoints.includes('audio_transcriptions') || endpoints.includes('audio_translations')
    : inputs.includes('audio') && !inputs.includes('text');
}

export function countBy(items, keysOf) {
  const counts = {};
  items.forEach((item) => keysOf(item).forEach((k) => (counts[k] = (counts[k] || 0) + 1)));
  return counts;
}

// "81 text, 9 image" style summary of a models list
export function kindsSummary(models) {
  const counts = countBy(models, kindsOf);
  return KIND_ORDER.filter((k) => counts[k]).map((k) => `${counts[k]} ${(KIND_LABELS[k] || k).toLowerCase()}`);
}
