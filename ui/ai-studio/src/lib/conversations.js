// Chat conversations of the playground: what they cost, their versions and comparisons, their copies, and the files
// they are exported to and imported from. No dependency on the page, so the rules stay easy to check.
//
// A conversation is a list of messages. An answer is an assistant message; regenerating it keeps every answer it
// had in `versions`, the displayed one being `versions[version]` and copied at the top of the message. In a
// comparison (`conversation.compare`, the models of the columns), each turn of the assistant is one message with
// the answer of every column in `answers`, and each column only follows its own answers.

export const EXPORT_FORMAT = 'ai-studio-conversation';
export const MAX_IMPORT_BYTES = 5 * 1024 * 1024;
export const MAX_COMPARED = 3;
const MAX_IMPORT_MESSAGES = 5000;
const ROLES = ['system', 'user', 'assistant'];

// what an answer is made of, whether it is a message, a version or a column of a comparison
const ANSWER_FIELDS = {
  content: (v) => typeof v === 'string',
  reasoning: (v) => typeof v === 'string',
  // the model the gateway answered with, and the model id the studio asked for
  model: (v) => typeof v === 'string',
  requested: (v) => typeof v === 'string',
  usage: (v) => v !== null && typeof v === 'object' && !Array.isArray(v),
  costs: (v) => v !== null && typeof v === 'object' && !Array.isArray(v),
  duration: (v) => typeof v === 'number',
  ttft: (v) => typeof v === 'number',
  error: (v) => typeof v === 'boolean',
  stopped: (v) => typeof v === 'boolean',
  at: (v) => typeof v === 'number',
};

export function answerOf(message) {
  return Object.fromEntries(Object.keys(ANSWER_FIELDS).filter((k) => message[k] !== undefined).map((k) => [k, message[k]]));
}

// the cost of an answer as billed by the gateway, null when the model has no known price
export function costOf(answer) {
  const value = answer && answer.costs && answer.costs.total_cost;
  return value === null || value === undefined || Number.isNaN(Number(value)) ? null : Number(value);
}

// every answer the gateway was asked for: all the versions, all the columns
function billedAnswers(messages) {
  return (messages || []).flatMap((m) => {
    if (m.answers) return m.answers.flatMap((a) => a.versions || [a]);
    if (m.role === 'assistant') return m.versions || [m];
    return [];
  });
}

export function totalsOf(conversation) {
  const totals = { cost: 0, priced: 0, answers: 0, tokens: 0 };
  for (const a of billedAnswers(conversation && conversation.messages)) {
    if (a.error || a.pending) continue;
    totals.answers++;
    const cost = costOf(a);
    if (cost !== null) {
      totals.cost += cost;
      totals.priced++;
    }
    if (a.usage) totals.tokens += (a.usage.prompt_tokens || 0) + (a.usage.completion_tokens || 0);
  }
  return totals;
}

// the messages a model is sent for the next turn: in a comparison, the answers of its column only
export function historyFor(messages, column = null) {
  const history = [];
  for (const m of messages || []) {
    if (m.role === 'user' || m.role === 'system') {
      history.push({ role: m.role, content: m.content });
    } else if (m.role === 'assistant') {
      const answer = m.answers ? (column === null ? null : m.answers[column]) : m;
      if (answer && !answer.error && !answer.pending) history.push({ role: 'assistant', content: answer.content || '' });
    }
  }
  return history;
}

// a message showing `answer`, nothing left of the answer it showed before
function showing(message, answer) {
  const rest = Object.fromEntries(Object.entries(message).filter(([k]) => !(k in ANSWER_FIELDS) && k !== 'pending'));
  return { ...rest, ...answer };
}

// a new answer for a message, the previous ones kept as versions
export function withVersion(message, answer) {
  const versions = [...(message.versions || [answerOf(message)]), answer];
  return { ...showing(message, answer), versions, version: versions.length - 1 };
}

export function showVersion(message, index) {
  if (!message.versions || !message.versions[index]) return message;
  return { ...showing(message, message.versions[index]), version: index };
}

// What an answer slot (an assistant message, or a column of a comparison turn) holds while `answer` comes in
// (`pending`) and once it is complete. `previous` is what the slot held before a regeneration, null otherwise.
export function answerSlot(previous, answer, pending = false) {
  if (pending) return { ...answer, pending: true, ...(previous ? { versions: previous.versions || [answerOf(previous)] } : {}) };
  return previous ? withVersion(previous, answer) : answer;
}

// one column of a comparison as a conversation of its own, to go on with that model only
export function columnThread(conversation, column) {
  const messages = (conversation.messages || []).flatMap((m) => {
    if (!m.answers) return m.role === 'assistant' ? [] : [m];
    const answer = m.answers[column];
    return answer ? [{ role: 'assistant', ...answerOf(answer), ...(answer.versions ? { versions: answer.versions, version: answer.version } : {}) }] : [];
  });
  const { compare: _c, ...rest } = conversation;
  return { ...rest, model: conversation.compare[column], messages };
}

const withoutPending = ({ pending: _p, ...rest }) => rest;

const finished = (messages) =>
  (messages || []).filter((m) => !m.pending).map((m) => (m.answers ? { ...withoutPending(m), answers: m.answers.map(withoutPending) } : withoutPending(m)));

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
  const answer = (a) => [`## Assistant${a.model ? ` · ${a.model}` : ''}`, '', a.error ? `> Error: ${a.content}` : a.content || '', ''];
  for (const m of finished(conversation.messages)) {
    if (m.answers) m.answers.forEach((a, column) => lines.push(...answer({ model: (conversation.compare || [])[column], ...a })));
    else if (m.role === 'assistant') lines.push(...answer(m));
    else lines.push(`## ${m.role === 'user' ? 'User' : 'System'}`, '', m.content || '', '');
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

// the fields of an answer that have the expected shape, null without a text content
function importedAnswer(raw) {
  const content = raw && typeof raw === 'object' ? textOf(raw.content) : null;
  if (content === null) return null;
  const answer = { content, at: Number(raw.at) || Date.now() };
  Object.entries(ANSWER_FIELDS).forEach(([field, valid]) => {
    if (field !== 'content' && field !== 'at' && valid(raw[field])) answer[field] = raw[field];
  });
  return answer;
}

// the versions of an imported answer, when they are all valid answers
function importedVersions(answer, raw) {
  if (!Array.isArray(raw.versions) || raw.versions.length === 0) return answer;
  const versions = raw.versions.map(importedAnswer);
  if (versions.some((v) => v === null)) return answer;
  const version = Number.isInteger(raw.version) && raw.version >= 0 && raw.version < versions.length ? raw.version : versions.length - 1;
  return { ...answer, ...versions[version], versions, version };
}

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
  const compare =
    Array.isArray(source.compare) && source.compare.length >= 2 && source.compare.length <= MAX_COMPARED && source.compare.every((m) => typeof m === 'string')
      ? source.compare
      : null;
  const invalid = (idx) => new Error(`message ${idx + 1} is not a system, user or assistant message with text content`);
  const messages = source.messages.map((m, idx) => {
    if (!m || typeof m !== 'object' || !ROLES.includes(m.role)) throw invalid(idx);
    if (m.role === 'assistant' && compare && Array.isArray(m.answers)) {
      if (m.answers.length !== compare.length) throw new Error(`message ${idx + 1} does not have an answer for every compared model`);
      const answers = m.answers.map((a) => {
        const answer = importedAnswer(a);
        if (answer === null) throw invalid(idx);
        return importedVersions(answer, a);
      });
      return { role: 'assistant', answers, at: Number(m.at) || Date.now() };
    }
    if (m.role === 'assistant') {
      const answer = importedAnswer(m);
      if (answer === null) throw invalid(idx);
      return { role: 'assistant', ...importedVersions(answer, m) };
    }
    const content = textOf(m.content);
    if (content === null) throw invalid(idx);
    return { role: m.role, content, at: Number(m.at) || Date.now() };
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
    ...(compare ? { compare, model: compare[0] } : {}),
    messages,
  };
}
