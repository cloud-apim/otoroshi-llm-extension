import { gatewayError, studioHeaders } from './api';
import { billedProxyUrl } from './models';

// The request settings of the chat. A sampling setting left to `null` is not sent, the model uses its own: a
// reasoning model, which takes no temperature, no top p and no `max_tokens`, answers like any other.
// `tools`: null when every tool of the provider is offered, the ids to offer otherwise
export const DEFAULT_SETTINGS = { system: '', stream: true, temperature: null, top_p: null, max_tokens: null, preset: '', tools: null };

// What the chat used to send when nothing was set. A browser kept them with its preferences, as if they had been
// chosen: they are forgotten the first time these preferences are read again.
const FORMER_DEFAULTS = { temperature: 0.7, top_p: 1, max_tokens: 1024 };
const PREFS_VERSION = 2;

export function chatPrefs(stored) {
  const prefs = stored || {};
  if (prefs.version === PREFS_VERSION) return prefs;
  const settings = { ...(prefs.settings || {}) };
  Object.keys(FORMER_DEFAULTS).forEach((name) => {
    if (settings[name] === FORMER_DEFAULTS[name]) settings[name] = null;
  });
  return { ...prefs, settings, version: PREFS_VERSION };
}

// the sampling parameters of a request: the ones that were set, and only them
export function samplingOf(settings) {
  const set = (v) => v !== null && v !== undefined && v !== '' && Number.isFinite(Number(v));
  return {
    ...(set(settings.temperature) ? { temperature: Number(settings.temperature) } : {}),
    ...(set(settings.top_p) ? { top_p: Number(settings.top_p) } : {}),
    ...(set(settings.max_tokens) && Number(settings.max_tokens) > 0 ? { max_tokens: Number(settings.max_tokens) } : {}),
  };
}

// Calls the workspace endpoint as the signed-in backoffice user (no api key) and streams the answer. `onDelta(content, reasoning)` receives the text as it arrives.
// `sessionId` groups the calls of one conversation in the logs. `costs` is what the gateway billed for the answer, when the model has a known price.
// the images an answer carries: the gateway puts what a model drew in `message.images`, openai style
export function imagesOf(message) {
  return ((message && message.images) || [])
    .map((img) => (typeof img === 'string' ? img : (img && img.image_url && img.image_url.url) || (img && img.url) || null))
    .filter(Boolean);
}

export async function chatCompletion({ workspace, body, stream, signal, onDelta, sessionId }) {
  const started = Date.now();
  const res = await fetch(billedProxyUrl(workspace, '/chat/completions'), {
    method: 'POST',
    credentials: 'include',
    signal,
    headers: {
      'Content-Type': 'application/json',
      Accept: stream ? 'text/event-stream' : 'application/json',
      ...studioHeaders(),
      ...(sessionId ? { 'X-Session-Id': sessionId } : {}),
    },
    body: JSON.stringify({ ...body, stream: !!stream, ...(stream ? { stream_options: { include_usage: true } } : {}) }),
  });
  if (!res.ok) throw gatewayError(await res.text(), res.status, res.statusText);
  if (!stream) {
    const json = await res.json();
    const choice = (json.choices && json.choices[0]) || {};
    const content = (choice.message && choice.message.content) || '';
    const reasoning = (choice.message && (choice.message.reasoning_content || choice.message.reasoning || (typeof choice.message.reasoning_details === 'string' ? choice.message.reasoning_details : ''))) || '';
    onDelta && onDelta(content, reasoning);
    return { content, reasoning, images: imagesOf(choice.message), usage: json.usage, costs: json.costs || null, model: json.model, duration: Date.now() - started };
  }
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let content = '';
  let reasoning = '';
  let usage = null;
  let costs = null;
  let images = [];
  let model = null;
  let firstTokenAt = null;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let idx;
    while ((idx = buffer.indexOf('\n')) > -1) {
      const line = buffer.substring(0, idx).trim();
      buffer = buffer.substring(idx + 1);
      if (!line.startsWith('data:')) continue;
      const data = line.substring(5).trim();
      if (!data || data === '[DONE]') continue;
      try {
        const chunk = JSON.parse(data);
        if (chunk.error) throw new Error(chunk.error.message || JSON.stringify(chunk.error));
        if (chunk.usage) usage = chunk.usage;
        if (chunk.costs) costs = chunk.costs;
        if (chunk.model) model = chunk.model;
        const delta = chunk.choices && chunk.choices[0] && chunk.choices[0].delta;
        if (delta && delta.images) images = [...images, ...imagesOf(delta)];
        const thinking = delta && (delta.reasoning_content || delta.reasoning);
        if (delta && (delta.content || thinking)) {
          if (!firstTokenAt) firstTokenAt = Date.now();
          if (delta.content) content += delta.content;
          if (thinking) reasoning += thinking;
          onDelta && onDelta(content, reasoning);
        }
      } catch (e) {
        if (e instanceof SyntaxError) continue;
        throw e;
      }
    }
  }
  return { content, reasoning, images, usage, costs, model, duration: Date.now() - started, ttft: firstTokenAt ? firstTokenAt - started : null };
}
