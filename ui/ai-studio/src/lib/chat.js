import { currentTenant } from './bootstrap';
import { proxyUrl } from './models';

// Calls the workspace endpoint as the signed-in backoffice user (no api key) and streams the answer. `onDelta(content, reasoning)` receives the text as it arrives.
// `sessionId` groups the calls of one conversation in the logs.
export async function chatCompletion({ workspace, body, stream, signal, onDelta, sessionId }) {
  const started = Date.now();
  const res = await fetch(proxyUrl(workspace, '/chat/completions'), {
    method: 'POST',
    credentials: 'include',
    signal,
    headers: {
      'Content-Type': 'application/json',
      Accept: stream ? 'text/event-stream' : 'application/json',
      'Otoroshi-Tenant': currentTenant(),
      ...(sessionId ? { 'X-Session-Id': sessionId } : {}),
    },
    body: JSON.stringify({ ...body, stream: !!stream, ...(stream ? { stream_options: { include_usage: true } } : {}) }),
  });
  if (!res.ok) {
    const text = await res.text();
    let message = text;
    try {
      const json = JSON.parse(text);
      message = (json.error && (json.error.message || json.error)) || json.error_description || json.message || text;
      if (typeof message !== 'string') message = JSON.stringify(message);
    } catch (e) {}
    throw new Error(`${res.status} - ${message || res.statusText}`);
  }
  if (!stream) {
    const json = await res.json();
    const choice = (json.choices && json.choices[0]) || {};
    const content = (choice.message && choice.message.content) || '';
    const reasoning = (choice.message && (choice.message.reasoning_content || choice.message.reasoning || (typeof choice.message.reasoning_details === 'string' ? choice.message.reasoning_details : ''))) || '';
    onDelta && onDelta(content, reasoning);
    return { content, reasoning, usage: json.usage, model: json.model, duration: Date.now() - started };
  }
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let content = '';
  let reasoning = '';
  let usage = null;
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
        if (chunk.model) model = chunk.model;
        const delta = chunk.choices && chunk.choices[0] && chunk.choices[0].delta;
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
  return { content, reasoning, usage, model, duration: Date.now() - started, ttft: firstTokenAt ? firstTokenAt - started : null };
}
