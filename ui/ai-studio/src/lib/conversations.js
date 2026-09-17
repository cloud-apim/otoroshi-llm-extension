// Chat conversations of the playground: what they cost, their copies, and the files they are exported to and
// imported from. No dependency on the page, so the rules stay easy to check.

export const EXPORT_FORMAT = 'ai-studio-conversation';
export const MAX_IMPORT_BYTES = 5 * 1024 * 1024;
const MAX_IMPORT_MESSAGES = 5000;
const ROLES = ['system', 'user', 'assistant'];

// the cost of an answer as billed by the gateway, null when the model has no known price
export function costOf(message) {
  const value = message && message.costs && message.costs.total_cost;
  return value === null || value === undefined || Number.isNaN(Number(value)) ? null : Number(value);
}

export function totalsOf(conversation) {
  const totals = { cost: 0, priced: 0, answers: 0, tokens: 0 };
  for (const m of (conversation && conversation.messages) || []) {
    if (m.role !== 'assistant' || m.error || m.pending) continue;
    totals.answers++;
    const cost = costOf(m);
    if (cost !== null) {
      totals.cost += cost;
      totals.priced++;
    }
    if (m.usage) totals.tokens += (m.usage.prompt_tokens || 0) + (m.usage.completion_tokens || 0);
  }
  return totals;
}

const finished = (messages) => (messages || []).filter((m) => !m.pending).map(({ pending: _p, ...m }) => m);

// what is stored or exported: never a half received answer, never the temporary marker
export function persisted(conversation) {
  const { temporary: _t, ...rest } = conversation;
  return { ...rest, messages: finished(rest.messages) };
}

export function copyOf(conversation, id, title) {
  const { updated_at: _u, ...rest } = persisted(conversation);
  return { ...rest, id, title, created_at: Date.now() };
}

export function toExport(conversation) {
  return { format: EXPORT_FORMAT, version: 1, exported_at: new Date().toISOString(), conversation: persisted(conversation) };
}

export function toMarkdown(conversation) {
  const lines = [`# ${conversation.title || 'Conversation'}`, ''];
  for (const m of finished(conversation.messages)) {
    const who = m.role === 'user' ? 'User' : m.role === 'system' ? 'System' : `Assistant${m.model ? ` · ${m.model}` : ''}`;
    lines.push(`## ${who}`, '', m.error ? `> Error: ${m.content}` : m.content || '', '');
  }
  return lines.join('\n');
}

export function fileNameOf(conversation, extension) {
  const base = (conversation.title || '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .substring(0, 60);
  return `${base || 'conversation'}.${extension}`;
}

// the text of a message: a string, or the text parts of an OpenAI content array
function textOf(content) {
  if (typeof content === 'string') return content;
  if (!Array.isArray(content)) return null;
  const parts = content.map((p) => (typeof p === 'string' ? p : p && typeof p.text === 'string' ? p.text : null)).filter((p) => p !== null);
  return parts.length ? parts.join('\n') : null;
}

const ANSWER_FIELDS = {
  model: (v) => typeof v === 'string',
  reasoning: (v) => typeof v === 'string',
  usage: (v) => v !== null && typeof v === 'object' && !Array.isArray(v),
  costs: (v) => v !== null && typeof v === 'object' && !Array.isArray(v),
  duration: (v) => typeof v === 'number',
  ttft: (v) => typeof v === 'number',
  error: (v) => typeof v === 'boolean',
  stopped: (v) => typeof v === 'boolean',
};

// A conversation exported by the studio, a bare conversation, or an OpenAI chat request (`{ messages }`).
// Only text messages are kept, with the details of the answers when they have the expected shape.
export function parseImport(text, fileName) {
  let json;
  try {
    json = JSON.parse(text);
  } catch (e) {
    throw new Error('the file is not valid json');
  }
  const source = json && json.format === EXPORT_FORMAT ? json.conversation : json;
  if (!source || typeof source !== 'object' || !Array.isArray(source.messages) || source.messages.length === 0) {
    throw new Error('the file has no messages');
  }
  if (source.messages.length > MAX_IMPORT_MESSAGES) throw new Error(`the file has more than ${MAX_IMPORT_MESSAGES} messages`);
  const messages = source.messages.map((m, idx) => {
    const content = m && typeof m === 'object' ? textOf(m.content) : null;
    if (content === null || !ROLES.includes(m.role)) {
      throw new Error(`message ${idx + 1} is not a system, user or assistant message with text content`);
    }
    const kept = { role: m.role, content, at: Number(m.at) || Date.now() };
    if (m.role === 'assistant') {
      Object.entries(ANSWER_FIELDS).forEach(([field, valid]) => {
        if (valid(m[field])) kept[field] = m[field];
      });
    }
    return kept;
  });
  const firstQuestion = messages.find((m) => m.role === 'user');
  const title =
    (typeof source.title === 'string' && source.title.trim()) ||
    (firstQuestion && firstQuestion.content.replace(/\s+/g, ' ').trim().substring(0, 60)) ||
    (fileName || '').replace(/\.json$/i, '') ||
    'Imported chat';
  return {
    title: title.replace(/\s+/g, ' ').trim().substring(0, 120),
    ...(typeof source.model === 'string' ? { model: source.model } : {}),
    messages,
  };
}
