// Images generated from the chat by the image models of the workspace, on its `/images/generations`
// endpoint. Chat models that draw instead of writing answer on the chat endpoint, with their images in
// the message: see `imagesOf` in `chat.js`.

import { currentTenant } from './bootstrap';
import { blobDataUrl } from './attachments';
import { proxyUrl } from './models';

// the usage of an images call, as the answers of the chat read it
function usageOf(json) {
  const usage = (json.usage && json.usage.usage) || json.usage || null;
  if (!usage) return null;
  return {
    prompt_tokens: usage.input_tokens || usage.prompt_tokens || 0,
    completion_tokens: usage.output_tokens || usage.completion_tokens || 0,
    total_tokens: usage.total_tokens || 0,
  };
}

/**
 * Asks `model` for an image, as the signed-in backoffice user. Returns `{ images, content, usage, costs,
 * duration }`, the images as data urls. A workspace that decodes its images answers with the image itself
 * rather than json, so both shapes are read.
 */
export async function generateImage({ workspace, model, prompt, signal, sessionId }) {
  const started = Date.now();
  const res = await fetch(proxyUrl(workspace, '/images/generations'), {
    method: 'POST',
    credentials: 'include',
    signal,
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
      'Otoroshi-Tenant': currentTenant(),
      ...(sessionId ? { 'X-Session-Id': sessionId } : {}),
    },
    body: JSON.stringify({ model, prompt, n: 1 }),
  });
  if (!res.ok) {
    const text = await res.text();
    let message = text;
    try {
      const json = JSON.parse(text);
      message = (json.error && (json.error.message || json.error)) || json.error_details || json.error_description || text;
      if (typeof message !== 'string') message = JSON.stringify(message);
    } catch (e) {}
    throw new Error(`${res.status} - ${message || res.statusText}`);
  }
  const duration = () => Date.now() - started;
  if ((res.headers.get('Content-Type') || '').startsWith('image/')) {
    return { images: [await blobDataUrl(await res.blob())], content: '', usage: null, costs: null, duration: duration() };
  }
  const json = await res.json();
  const data = json.data || [];
  const images = data.map((d) => (d.b64_json ? `data:image/png;base64,${d.b64_json}` : d.url)).filter(Boolean);
  if (images.length === 0) throw new Error('the provider returned no image');
  return {
    images,
    content: data.map((d) => d.revised_prompt).filter(Boolean).join('\n\n'),
    usage: usageOf(json),
    costs: (json.usage && json.usage.costs) || json.costs || null,
    duration: duration(),
  };
}
