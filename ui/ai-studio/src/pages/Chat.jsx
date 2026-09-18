import { useEffect, useRef, useState } from 'react';
import { useWorkspace } from '../App';
import { Badge, CopyButton, Field, MenuButton, Modal, NumberInput, Select, Toggle, useAsync, useConfirm, useToast } from '../components/ui';
import { Icon } from '../components/icons';
import { Markdown } from '../components/Markdown';
import { chatCompletion } from '../lib/chat';
import { generateImage } from '../lib/images';
import { Resources, randomId, workspaceFilter } from '../lib/entities';
import { listAttachedTools, TOOL_LABELS } from '../lib/tools';
import { fmtCost, fmtInt, fmtMs } from '../lib/format';
import { deleteConversation, getConversation, listConversations, listWorkspaceModels, saveConversation } from '../lib/models';
import { bootstrap } from '../lib/bootstrap';
import { useRouter } from '../lib/router';
import { capabilitiesOf, chatUsable, contextOf, fmtPrice, fmtTokens, imageGenerator, imageOutput, promptPrice } from '../lib/modelmeta';
import {
  answerSlot,
  columnThread,
  copyOf,
  costOf,
  fileNameOf,
  historyFor,
  MAX_COMPARED,
  MAX_IMPORT_BYTES,
  parseImport,
  persisted,
  showVersion,
  toExport,
  toMarkdown,
  totalsOf,
} from '../lib/conversations';
import { download, downloadDataUrl } from '../lib/files';
import {
  ACCEPT,
  acceptedKinds,
  attachmentsBytes,
  carriedKinds,
  bytesOf,
  conversationBytes,
  fmtBytes,
  KIND_LABELS,
  MAX_CONVERSATION_BYTES,
  readAttachment,
  thumbnailOf,
} from '../lib/attachments';

const SUGGESTIONS = [
  { title: 'Strawberry Test', prompt: "How many r's are in the word strawberry?" },
  { title: '9.9 vs 9.11', prompt: 'Which one is larger, 9.9 or 9.11? Explain briefly.' },
  { title: 'Poem Riddle', prompt: 'Compose a 12-line poem where the first letters spell OTOROSHI AI.' },
  { title: 'Car Wash Test', prompt: 'It is raining. Should I walk or drive to the car wash 200 m away?' },
];

// `tools`: null when every tool of the provider is offered, the ids to offer otherwise
const DEFAULT_SETTINGS = { system: '', stream: true, temperature: 0.7, top_p: 1, max_tokens: 1024, preset: '', tools: null };

function storageGet(key, fallback) {
  try {
    const v = window.localStorage.getItem(key);
    return v === null ? fallback : JSON.parse(v);
  } catch (e) {
    return fallback;
  }
}

function storageSet(key, value) {
  try {
    window.localStorage.setItem(key, JSON.stringify(value));
  } catch (e) {}
}

// what tells two chat models apart at a glance
function pickerFacts(model) {
  const facts = [];
  if (imageGenerator(model)) facts.push('draws images');
  if (capabilitiesOf(model).some((c) => c.id === 'reasoning')) facts.push('reasoning');
  if (capabilitiesOf(model).some((c) => c.id === 'vision')) facts.push('vision');
  if (contextOf(model)) facts.push(fmtTokens(contextOf(model)));
  if (promptPrice(model) !== null) facts.push(`${fmtPrice(promptPrice(model))}/1M`);
  facts.push(model.provider);
  return facts.join(' · ');
}

function ModelPicker({ value, onChange, models, width = 520 }) {
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState('');
  const filtered = models.filter((m) => !q || m.id.toLowerCase().includes(q.toLowerCase())).slice(0, 80);
  return (
    <div style={{ position: 'relative', width, maxWidth: width === '100%' ? undefined : '60vw' }}>
      <input
        className="input"
        value={open ? q : value}
        placeholder={value || 'Select a model'}
        onFocus={() => {
          setQ('');
          setOpen(true);
        }}
        onBlur={() => setTimeout(() => setOpen(false), 150)}
        onChange={(e) => setQ(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' && q) {
            onChange(filtered[0] ? filtered[0].id : q);
            e.target.blur();
          }
        }}
      />
      {open && (
        <div className="search-results model-results">
          {filtered.length === 0 && <div className="item muted">No model matches, press enter to use “{q}”</div>}
          {filtered.map((m) => (
            <div key={m.id} className={`item ${m.id === value ? 'active' : ''}`} title={`${m.id}\n${pickerFacts(m)}`} onMouseDown={() => onChange(m.id)}>
              <span className="model-name">{m.id}</span>
              <span className="model-facts faint small">{pickerFacts(m)}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

// what an image model is asked to draw: the last question, its files aside
function promptOf(history) {
  const last = [...history].reverse().find((m) => m.role === 'user');
  if (!last) return '';
  return typeof last.content === 'string' ? last.content : last.content.filter((p) => p.type === 'text').map((p) => p.text).join('\n');
}

function costTitle(costs) {
  const part = (label, v) => (v === null || v === undefined ? null : `${label}: ${fmtCost(v)}`);
  return [part('input', costs.input_cost), part('output', costs.output_cost), part('reasoning', costs.reasoning_cost)].filter(Boolean).join(' · ');
}

// the model the gateway answered with, when it says more than the model that was asked for
function servedModel(answer) {
  if (!answer.model) return null;
  return answer.requested && (answer.requested === answer.model || answer.requested.endsWith(`/${answer.model}`) || answer.requested.endsWith(`###${answer.model}`)) ? null : answer.model;
}

function AnswerMeta({ answer, compact }) {
  const model = compact ? servedModel(answer) : answer.model;
  return (
    <div className="meta">
      {model && <span title={compact ? 'The model that answered' : undefined}>{model}</span>}
      {answer.usage && <span>{fmtInt((answer.usage.prompt_tokens || 0) + (answer.usage.completion_tokens || 0))} tokens</span>}
      {costOf(answer) !== null && <span title={costTitle(answer.costs)}>{fmtCost(costOf(answer))}</span>}
      {answer.duration && <span>{fmtMs(answer.duration)}</span>}
      {answer.ttft && <span title="Time to the first token">ttft {fmtMs(answer.ttft)}</span>}
      {answer.stopped && <span>stopped</span>}
    </div>
  );
}

// the answers a message had, the displayed one first; `onPick` is missing when the version cannot change anymore
function Versions({ answer, onPick }) {
  if (!answer.versions || answer.versions.length < 2 || answer.pending) return null;
  const count = answer.versions.length;
  const current = answer.version ?? count - 1;
  return (
    <span className="versions" title={onPick ? 'Answers generated for this message' : 'Answers generated for this message, the later messages follow this one'}>
      {onPick && (
        <button className="copy-btn" disabled={current === 0} onClick={() => onPick(current - 1)} title="Previous answer">
          ‹
        </button>
      )}
      {current + 1}/{count}
      {onPick && (
        <button className="copy-btn" disabled={current === count - 1} onClick={() => onPick(current + 1)} title="Next answer">
          ›
        </button>
      )}
    </span>
  );
}

// an answer of the assistant: its text, what it cost, and what can be done with it
function Answer({ answer, busy, compact, onRegenerate, onRegenerateWith, onVersion, onContinue, onOpenImage }) {
  return (
    <>
      <div className="bubble">
        {answer.reasoning && (
          <details className="thinking" open={answer.pending && !answer.content}>
            <summary>{answer.pending && !answer.content ? 'Thinking…' : 'Thought process'}</summary>
            <div className="thinking-body">
              <Markdown text={answer.reasoning} />
            </div>
          </details>
        )}
        {answer.error ? answer.content : <Markdown text={answer.content || (answer.pending && !answer.reasoning ? '…' : '')} />}
        {(answer.images || []).length > 0 && (
          <div className="answer-images">
            {answer.images.map((src, i) => (
              <button key={i} className="answer-image" onClick={() => onOpenImage && onOpenImage(src, i)} title="See this image full size">
                <img src={src} alt={`Generated ${i + 1}`} />
              </button>
            ))}
          </div>
        )}
      </div>
      {!answer.pending && (
        <div className="answer-foot">
          {answer.error ? <span /> : <AnswerMeta answer={answer} compact={compact} />}
          <div className="answer-actions">
            <Versions answer={answer} onPick={onVersion} />
            {!answer.error && <CopyButton text={answer.content || ''} />}
            {onRegenerate &&
              (onRegenerateWith ? (
                <MenuButton
                  className="copy-btn"
                  icon="refresh"
                  title="Regenerate this answer"
                  disabled={busy}
                  minWidth={280}
                  items={[
                    { label: 'Regenerate', onClick: onRegenerate },
                    { label: 'Regenerate with another model…', onClick: onRegenerateWith },
                  ]}
                />
              ) : (
                <button className="copy-btn" disabled={busy} onClick={onRegenerate} title="Regenerate this answer">
                  <Icon name="refresh" size={14} />
                </button>
              ))}
            {onContinue && (
              <button className="btn sm ghost" disabled={busy} onClick={onContinue} title="Go on with this model only, in a new conversation">
                Continue with this model
              </button>
            )}
          </div>
        </div>
      )}
    </>
  );
}

// the files of a question, in the composer and above the message once it is sent
function Attachments({ list, onRemove, onOpen, className = '' }) {
  if (!list || list.length === 0) return null;
  return (
    <div className={`attachments ${className}`}>
      {list.map((a) => {
        const thumb = thumbnailOf(a);
        return (
          <div key={a.id} className="attachment" title={`${a.name} · ${fmtBytes(a.size)}${a.truncated ? ' · truncated' : ''}`}>
            <button className="attachment-open" onClick={() => onOpen && onOpen(a)} disabled={a.kind === 'pdf'}>
              {thumb ? <img src={thumb} alt={a.name} /> : <Icon name="file" size={14} />}
              <span className="truncate">{a.name}</span>
            </button>
            <span className="faint small nowrap">{fmtBytes(a.size)}</span>
            {onRemove && (
              <button className="copy-btn" onClick={() => onRemove(a.id)} title="Remove this file">
                <Icon name="x" size={12} />
              </button>
            )}
          </div>
        );
      })}
    </div>
  );
}

function AttachmentModal({ attachment, onClose }) {
  // a file keeps its name, an image a model drew gets one
  const fileName = /\.[a-z0-9]{2,4}$/i.test(attachment.name) ? attachment.name : `${attachment.name.replace(/[^a-z0-9.-]+/gi, '-')}.png`;
  return (
    <Modal
      open
      onClose={onClose}
      title={attachment.name}
      size="wide"
      footer={
        <>
          {attachment.kind === 'image' && (
            <button className="btn" onClick={() => downloadDataUrl(fileName, attachment.data)}>
              <Icon name="download" />
              Download
            </button>
          )}
          <button className="btn primary" onClick={onClose}>
            Close
          </button>
        </>
      }
    >
      {attachment.kind === 'image' ? (
        <img className="attachment-preview" src={attachment.data} alt={attachment.name} />
      ) : (
        <pre className="attachment-text">{attachment.data}</pre>
      )}
    </Modal>
  );
}

function RegenerateModal({ models, initial, onClose, onPick }) {
  const [value, setValue] = useState(initial || '');
  return (
    <Modal
      open
      onClose={onClose}
      title="Regenerate with another model"
      footer={
        <>
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button className="btn primary" disabled={!value} onClick={() => onPick(value)}>
            Regenerate
          </button>
        </>
      }
    >
      <p className="muted">The answer is generated again, from the same messages, by the model you pick. The current answer stays one click away.</p>
      <div style={{ minHeight: 300 }}>
        <ModelPicker value={value} onChange={setValue} models={models} width="100%" />
      </div>
    </Modal>
  );
}

export function ChatPage() {
  const { workspace } = useWorkspace();
  const toast = useToast();
  const confirm = useConfirm();
  const { query } = useRouter();
  const prefKey = `ai-studio-chat-${workspace.id}`;

  const models = useAsync(() => listWorkspaceModels(workspace), [workspace.id]);
  const presets = useAsync(() => Resources.contexts.list(workspaceFilter(workspace.id)), [workspace.id]);
  const rooms = useAsync(() => listConversations(workspace), [workspace.id]);
  const tools = useAsync(() => listAttachedTools(workspace.id), [workspace.id]);

  // the chat talks the chat completions api: realtime or responses only models would fail there. The image
  // models of the workspace are served on its images endpoint, and draw instead of answering.
  const textModels = ((models.data && models.data.models) || []).filter((m) => chatUsable(m) || imageGenerator(m));

  const [prefs, setPrefs] = useState(() => storageGet(prefKey, { model: '', settings: DEFAULT_SETTINGS, showSettings: true }));
  const settings = { ...DEFAULT_SETTINGS, ...(prefs.settings || {}) };
  const updatePrefs = (patch) =>
    setPrefs((p) => {
      const next = { ...p, ...patch };
      storageSet(prefKey, next);
      return next;
    });
  const setSettings = (patch) => updatePrefs({ settings: { ...settings, ...patch } });

  // the displayed conversation, also kept in a ref so the answers coming in find it
  const [conversation, setConversation] = useState(null);
  const conversationRef = useRef(null);
  const commit = (next) => {
    conversationRef.current = next;
    setConversation(next);
  };
  // a temporary chat is never stored: it lives in this page only
  const [temporary, setTemporary] = useState(false);
  // the models of a comparison about to start
  const [draft, setDraft] = useState(null);
  const [regenerateAt, setRegenerateAt] = useState(null);
  const [input, setInput] = useState('');
  // the files of the question being written, and the one shown full size
  const [attachments, setAttachments] = useState([]);
  const [preview, setPreview] = useState(null);
  const [dragging, setDragging] = useState(false);
  const fileRef = useRef(null);
  const attachRef = useRef(null);
  const [busy, setBusy] = useState(false);
  const abortRef = useRef(null);
  const endRef = useRef(null);

  const model = query.model || prefs.model || (textModels[0] && textModels[0].id) || '';

  useEffect(() => {
    if (query.model && query.model !== prefs.model) updatePrefs({ model: query.model });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query.model]);

  useEffect(() => {
    endRef.current && endRef.current.scrollIntoView({ block: 'end' });
  }, [conversation]);

  const messages = (conversation && conversation.messages) || [];
  const totals = totalsOf(conversation);
  // the columns of a comparison, set once it has started
  const columns = (conversation && conversation.compare) || (messages.length === 0 ? draft : null);
  const locked = messages.length > 0;

  const modelById = (id) => textModels.find((m) => m.id === id) || null;
  // the kinds of files every model of the conversation reads: text always, images and PDF when they all do
  const targeted = columns ? columns.map(modelById) : [modelById(model)];
  // the tools the models of this conversation can call, and the ones this chat offers them
  const providerIds = [...new Set(targeted.filter(Boolean).map((m) => m.provider_id))];
  const availableTools = (tools.data || []).filter((t) => t.providers.some((p) => providerIds.includes(p)));
  const toolIsOn = (id) => settings.tools === null || settings.tools.includes(id);
  const toggleTool = (id, on) => {
    const current = availableTools.filter((t) => toolIsOn(t.id)).map((t) => t.id);
    const next = on ? [...current, id] : current.filter((x) => x !== id);
    setSettings({ tools: next.length === availableTools.length ? null : next });
  };
  const offeredTools = availableTools.filter((t) => toolIsOn(t.id));
  const kinds = targeted.some(imageGenerator) ? [] : acceptedKinds(targeted);
  const carried = conversationBytes(messages) + attachmentsBytes(attachments);
  const unreadable = ['image', 'pdf'].filter((k) => !kinds.includes(k));
  const attachTitle =
    kinds.length === 0
      ? 'An image model takes a prompt only'
      : `Attach files — ${kinds.map((k) => KIND_LABELS[k]).join(', ')}` +
        (unreadable.length > 0 ? `. Pick a model that reads ${unreadable.map((k) => KIND_LABELS[k]).join(' or ')} to send them.` : '');

  // reads the files into the question being written, telling what it does not take
  const addFiles = async (files) => {
    let room = MAX_CONVERSATION_BYTES - carried;
    const added = [];
    for (const file of [...(files || [])]) {
      try {
        const attachment = await readAttachment(file, kinds);
        if (bytesOf(attachment) > room) {
          toast.error(`${file.name}: a conversation carries ${fmtBytes(MAX_CONVERSATION_BYTES)} of files at most, and every question sends them again`);
          break;
        }
        room -= bytesOf(attachment);
        added.push(attachment);
      } catch (e) {
        toast.error(e.message);
      }
    }
    if (added.length > 0) setAttachments((prev) => [...prev, ...added]);
  };

  const persist = (conv) => saveConversation(workspace, persisted(conv)).then(() => rooms.reload());

  // leaving a temporary chat loses it
  const leaveTemporary = () =>
    !temporary || messages.length === 0
      ? Promise.resolve(true)
      : confirm({ title: 'Leave this temporary chat?', message: 'It is not saved: its messages will be lost.', danger: true, confirmLabel: 'Leave' });

  const openRoom = async (id) => {
    if (busy || !(await leaveTemporary())) return;
    getConversation(workspace, id)
      .then((c) => {
        setTemporary(false);
        setDraft(null);
        setAttachments([]);
        commit(c);
      })
      .catch(toast.error);
  };

  const newChat = async (temp) => {
    if (!(await leaveTemporary())) return false;
    if (busy) abortRef.current && abortRef.current.abort();
    commit(null);
    setDraft(null);
    setTemporary(!!temp);
    setInput('');
    setAttachments([]);
    return true;
  };

  const setColumns = (next) => {
    if (conversation && conversation.compare) commit({ ...conversation, compare: next, model: next[0] });
    else setDraft(next);
  };

  const toggleCompare = async () => {
    if (columns) {
      if (locked) await newChat(temporary);
      else if (conversation && conversation.compare) {
        const { compare: _c, ...plain } = conversation;
        commit(plain);
      }
      setDraft(null);
      return;
    }
    // a comparison starts in a new conversation
    if (locked && !(await newChat(temporary))) return;
    const others = textModels.map((m) => m.id).filter((id) => id !== model);
    setDraft([model || '', others[0] || '']);
  };

  const clearChat = () => {
    confirm({
      title: 'Clear this conversation?',
      message: temporary ? 'All its messages will be removed.' : 'All its messages will be removed, the conversation stays in your chats.',
      danger: true,
      confirmLabel: 'Clear',
    }).then((ok) => {
      if (!ok) return;
      const cleared = { ...conversation, messages: [] };
      commit(cleared);
      if (!temporary) persist(cleared).catch(toast.error);
    });
  };

  const duplicateChat = async () => {
    const copy = copyOf(conversation, `conv_${randomId(16)}`, `Copy of ${conversation.title || 'Untitled'}`);
    try {
      await persist(copy);
      commit(copy);
      toast.success('Conversation duplicated');
    } catch (e) {
      toast.error(e);
    }
  };

  const keepTemporary = async () => {
    try {
      await persist(conversation);
      setTemporary(false);
      toast.success('Chat saved');
    } catch (e) {
      toast.error(e);
    }
  };

  const exportChat = (kind) => {
    if (kind === 'json') download(fileNameOf(conversation, 'json'), JSON.stringify(toExport(conversation), null, 2), 'application/json');
    else download(fileNameOf(conversation, 'md'), toMarkdown(conversation), 'text/markdown');
  };

  const importChat = async (file) => {
    if (!file) return;
    if (file.size > MAX_IMPORT_BYTES) {
      toast.error('The file is too large, 5 MB at most');
      return;
    }
    try {
      const parsed = parseImport(await file.text(), file.name);
      if (!(await leaveTemporary())) return;
      const imported = { id: `conv_${randomId(16)}`, created_at: Date.now(), imported_at: Date.now(), ...parsed };
      await persist(imported);
      setTemporary(false);
      setDraft(null);
      commit(imported);
      toast.success(`${parsed.messages.length} message${parsed.messages.length === 1 ? '' : 's'} imported`);
    } catch (e) {
      toast.error(`Import failed: ${e.message}`);
    }
  };

  const removeRoom = (id) => {
    confirm({ title: 'Delete this conversation?', danger: true, confirmLabel: 'Delete' }).then((ok) => {
      if (!ok) return;
      deleteConversation(workspace, id)
        .then(() => {
          if (conversation && conversation.id === id) commit(null);
          rooms.reload();
        })
        .catch(toast.error);
    });
  };

  const requestBody = (requested, history) => ({
    model: requested,
    messages: settings.system ? [{ role: 'system', content: settings.system }, ...history] : history,
    temperature: Number(settings.temperature),
    top_p: Number(settings.top_p),
    ...(settings.max_tokens ? { max_tokens: Number(settings.max_tokens) } : {}),
    ...(settings.preset ? { context: settings.preset } : {}),
    // the gateway narrows the tools of the provider to these ones; nothing is sent when they are all offered
    ...(settings.tools === null ? {} : { allowed_tools: offeredTools.map((t) => t.id) }),
  });

  // Streams the answers of `jobs` ({ index, column, requested, history, previous }) into `start`, all at once, then
  // saves the conversation. The conversation is followed by id: the page may have moved to another one meanwhile.
  const run = async (start, jobs) => {
    const keep = !temporary;
    let current = start;
    commit(current);
    const place = (index, column, slot) => {
      current = {
        ...current,
        messages: current.messages.map((m, i) => {
          if (i !== index) return m;
          if (column === null) return { role: 'assistant', ...slot };
          return { ...m, answers: m.answers.map((a, c) => (c === column ? slot : a)) };
        }),
      };
      if (conversationRef.current && conversationRef.current.id === current.id) commit(current);
    };
    const answer = async ({ index, column, requested, history, previous }, signal) => {
      const target = modelById(requested);
      let partial = { model: requested, requested, content: '', at: Date.now() };
      place(index, column, answerSlot(previous, partial, true));
      let complete;
      try {
        const res = imageGenerator(target)
          ? await generateImage({ workspace, model: requested, prompt: promptOf(history), signal, sessionId: start.id })
          : await chatCompletion({
              workspace,
              body: requestBody(requested, history),
              // a streamed answer carries no image: a model that draws is asked in one go
              stream: settings.stream && !imageOutput(target),
              signal,
              sessionId: start.id,
              onDelta: (content, reasoning) => {
                partial = { ...partial, content, reasoning };
                place(index, column, answerSlot(previous, partial, true));
              },
            });
        complete = {
          content: res.content,
          reasoning: res.reasoning || '',
          ...((res.images || []).length > 0 ? { images: res.images } : {}),
          model: res.model || requested,
          requested,
          at: Date.now(),
          usage: res.usage || null,
          costs: res.costs || null,
          duration: res.duration,
          ttft: res.ttft,
        };
      } catch (e) {
        complete = e.name === 'AbortError' ? { ...partial, stopped: true } : { content: e.message, error: true, model: requested, requested, at: Date.now() };
      }
      place(index, column, answerSlot(previous, complete));
    };
    setBusy(true);
    const controller = new AbortController();
    abortRef.current = controller;
    try {
      await Promise.all(jobs.map((job) => answer(job, controller.signal)));
    } finally {
      setBusy(false);
      abortRef.current = null;
      if (keep) persist(current).catch(() => {});
    }
  };

  const send = async (text) => {
    const content = (text ?? input).trim();
    const files = attachments;
    if ((!content && files.length === 0) || busy) return;
    if (columns ? columns.some((c) => !c) : !model) {
      toast.error(columns ? 'Select a model for every column' : 'Select a model');
      return;
    }
    // the model may have changed since the files were attached, and every turn sends them again
    const unread = carriedKinds([...messages, { attachments: files }]).filter((k) => !kinds.includes(k));
    if (unread.length > 0) {
      toast.error(`This conversation has ${unread.map((k) => KIND_LABELS[k]).join(' and ')} that ${columns ? 'one of the models' : model} does not read: pick another model, or start a new chat`);
      return;
    }
    const title = content.substring(0, 60) || (files[0] && files[0].name) || 'Untitled';
    const base = conversation || { id: `conv_${randomId(16)}`, title, created_at: Date.now(), messages: [] };
    const asked = [...base.messages, { role: 'user', content, at: Date.now(), ...(files.length > 0 ? { attachments: files } : {}) }];
    const index = asked.length;
    const turn = columns ? { role: 'assistant', answers: columns.map((c) => ({ model: c, requested: c, content: '', pending: true })), at: Date.now() } : { role: 'assistant', model, requested: model, content: '', pending: true, at: Date.now() };
    setInput('');
    setAttachments([]);
    setDraft(null);
    await run(
      { ...base, ...(columns ? { compare: columns, model: columns[0] } : { model }), messages: [...asked, turn] },
      columns
        ? columns.map((requested, column) => ({ index, column, requested, history: historyFor(asked, column), previous: null }))
        : [{ index, column: null, requested: model, history: historyFor(asked), previous: null }]
    );
  };

  // a new answer for the message at `index`, by `requested` or the model that gave the current one
  const regenerate = async (index, requested) => {
    if (busy) return;
    const conv = conversationRef.current;
    const previous = conv.messages[index];
    const later = conv.messages.length - index - 1;
    if (later > 0 && !(await confirm({ title: 'Regenerate this answer?', message: `The ${later} message${later > 1 ? 's' : ''} after it will be removed.`, confirmLabel: 'Regenerate' }))) return;
    const target = requested || previous.requested || model;
    await run({ ...conv, messages: conv.messages.slice(0, index + 1) }, [{ index, column: null, requested: target, history: historyFor(conv.messages.slice(0, index)), previous }]);
  };

  const regenerateColumn = (index, column) => {
    const conv = conversationRef.current;
    run(conv, [{ index, column, requested: conv.compare[column], history: historyFor(conv.messages.slice(0, index), column), previous: conv.messages[index].answers[column] }]);
  };

  const switchVersion = (index, column, version) => {
    const conv = conversationRef.current;
    const next = {
      ...conv,
      messages: conv.messages.map((m, i) => {
        if (i !== index) return m;
        return column === null ? showVersion(m, version) : { ...m, answers: m.answers.map((a, c) => (c === column ? showVersion(a, version) : a)) };
      }),
    };
    commit(next);
    if (!temporary) persist(next).catch(() => {});
  };

  // the winner of a comparison goes on alone, in a new conversation
  const continueWith = async (column) => {
    const conv = conversationRef.current;
    const requested = conv.compare[column];
    const thread = { ...columnThread(conv, column), id: `conv_${randomId(16)}`, title: `${conv.title || 'Untitled'} · ${requested}`, created_at: Date.now() };
    try {
      if (!temporary) await persist(thread);
      setDraft(null);
      commit(thread);
      updatePrefs({ model: requested });
    } catch (e) {
      toast.error(e);
    }
  };

  const roomList = rooms.data || [];

  return (
    <div className="chat grow" style={{ gridTemplateColumns: '260px minmax(0, 1fr)' }}>
      <div className="chat-side">
        <div className="row" style={{ gap: 6 }}>
          <button className="btn primary grow" onClick={() => newChat(false)}>
            <Icon name="plus" />
            New chat
          </button>
          <button className={`btn icon ${temporary ? 'active' : ''}`} onClick={() => newChat(true)} title="New temporary chat, never saved">
            <Icon name="ghost" />
          </button>
          <button className="btn icon" disabled={busy} onClick={() => fileRef.current && fileRef.current.click()} title="Import a conversation (json)">
            <Icon name="upload" />
          </button>
          <input
            ref={fileRef}
            type="file"
            accept="application/json,.json"
            hidden
            onChange={(e) => {
              importChat(e.target.files && e.target.files[0]);
              e.target.value = '';
            }}
          />
        </div>
        <div className="faint small" style={{ padding: '2px 6px' }}>
          {rooms.loading && !rooms.data ? 'loading rooms…' : `${roomList.length} room${roomList.length === 1 ? '' : 's'} · stored in Otoroshi`}
        </div>
        <div>
          {roomList.map((r) => (
            <div key={r.id} className={`room ${conversation && conversation.id === r.id ? 'active' : ''}`} onClick={() => openRoom(r.id)}>
              <span className="title truncate">{r.title || 'Untitled'}</span>
              <button
                className="copy-btn del"
                onClick={(e) => {
                  e.stopPropagation();
                  removeRoom(r.id);
                }}
              >
                <Icon name="trash" size={14} />
              </button>
            </div>
          ))}
        </div>
      </div>
      <div className="chat-main">
        <div className="chat-head">
          {columns ? (
            <div className="compare-models">
              {columns.map((c, i) =>
                locked ? (
                  <Badge key={i} kind="accent">
                    {c}
                  </Badge>
                ) : (
                  <div key={i} className="row" style={{ gap: 2 }}>
                    <ModelPicker value={c} onChange={(v) => setColumns(columns.map((x, j) => (j === i ? v : x)))} models={textModels} width={240} />
                    {columns.length > 2 && (
                      <button className="copy-btn" onClick={() => setColumns(columns.filter((_, j) => j !== i))} title="Remove this model">
                        <Icon name="x" size={14} />
                      </button>
                    )}
                  </div>
                )
              )}
              {!locked && columns.length < MAX_COMPARED && (
                <button className="btn sm ghost" onClick={() => setColumns([...columns, textModels.map((m) => m.id).find((id) => !columns.includes(id)) || ''])}>
                  <Icon name="plus" />
                  Add a model
                </button>
              )}
            </div>
          ) : (
            <>
              <ModelPicker value={model} onChange={(m) => updatePrefs({ model: m })} models={textModels} />
              <span className="muted small nowrap">{models.loading ? 'loading models…' : `${textModels.length} models`}</span>
            </>
          )}
          <div className="grow" />
          {temporary && (
            <Badge kind="warning" title="Not saved in your chats. Its calls still count in the activity and the budgets.">
              Temporary
            </Badge>
          )}
          {totals.answers > 0 && (
            <span
              className="muted small nowrap"
              title={totals.priced < totals.answers ? `${totals.answers - totals.priced} answer(s) from models with no known price are not counted` : 'Billed by the gateway for this conversation'}
            >
              {totals.priced > 0 ? fmtCost(totals.cost) : 'no known cost'}
              {totals.priced > 0 && totals.priced < totals.answers ? '+' : ''} · {fmtInt(totals.tokens)} tokens
            </span>
          )}
          <button className={`btn sm ${columns ? 'active-toggle' : ''}`} disabled={busy} onClick={toggleCompare} title={columns ? 'Back to a single model' : 'Ask up to 3 models the same questions, side by side'}>
            <Icon name="columns" />
            Compare
          </button>
          {messages.length > 0 && (
            <>
              {temporary ? (
                <button className="btn sm" disabled={busy} onClick={keepTemporary} title="Keep this chat in your chats">
                  Save chat
                </button>
              ) : (
                <button className="btn sm icon" disabled={busy} onClick={duplicateChat} title="Duplicate this conversation">
                  <Icon name="copy" />
                </button>
              )}
              <button className="btn sm icon" disabled={busy} onClick={clearChat} title="Clear this conversation">
                <Icon name="eraser" />
              </button>
              <MenuButton
                className="btn sm icon"
                icon="download"
                title="Export this conversation"
                minWidth={250}
                items={[
                  { label: 'JSON, to import it again', onClick: () => exportChat('json') },
                  { label: 'Markdown, to read or share', onClick: () => exportChat('markdown') },
                ]}
              />
            </>
          )}
          <button className="btn sm" onClick={() => updatePrefs({ showSettings: !prefs.showSettings })}>
            <Icon name="sliders" />
            Settings
          </button>
        </div>
        <div className="chat-body">
          <div className="chat-main">
            <div className="chat-msgs">
              <div className={`inner ${columns ? 'wide' : ''}`}>
                {messages.length === 0 && (
                  <div className="chat-welcome">
                    <h3>{columns ? 'Compare models side by side' : temporary ? 'Temporary chat' : 'What can I help with?'}</h3>
                    <p className="muted">
                      {columns ? (
                        'Every question goes to each model at once, and each model keeps its own thread. Compare the answers, their cost and their speed.'
                      ) : (
                        <>
                          Chat with the models of <code>{workspace.baseUrl.replace(/^https?:\/\//, '')}</code> as {bootstrap.user.email}.
                        </>
                      )}
                      {temporary && ' This chat is not saved, its calls still count in the activity and the budgets.'}
                    </p>
                  </div>
                )}
                {messages.map((m, idx) => {
                  const last = idx === messages.length - 1;
                  if (m.role !== 'assistant') {
                    return (
                      <div key={idx} className={`msg ${m.role}`}>
                        <Attachments list={m.attachments} onOpen={setPreview} className="sent" />
                        {m.content && <div className="bubble">{m.content}</div>}
                      </div>
                    );
                  }
                  if (m.answers) {
                    return (
                      <div key={idx} className="compare-grid" style={{ gridTemplateColumns: `repeat(${m.answers.length}, minmax(0, 1fr))` }}>
                        {m.answers.map((a, col) => (
                          <div key={col} className={`msg assistant ${a.error ? 'error' : ''}`}>
                            <div className="compare-title truncate" title={conversation.compare[col]}>
                              {conversation.compare[col]}
                            </div>
                            <Answer
                              answer={a}
                              busy={busy}
                              compact
                              onOpenImage={(src, i) => setPreview({ kind: 'image', name: `${conversation.compare[col]} ${i + 1}`, data: src })}
                              onRegenerate={last ? () => regenerateColumn(idx, col) : null}
                              onVersion={last ? (v) => switchVersion(idx, col, v) : null}
                              onContinue={last ? () => continueWith(col) : null}
                            />
                          </div>
                        ))}
                      </div>
                    );
                  }
                  return (
                    <div key={idx} className={`msg assistant ${m.error ? 'error' : ''}`}>
                      <Answer
                        answer={m}
                        busy={busy}
                        onOpenImage={(src, i) => setPreview({ kind: 'image', name: `${m.model || 'image'} ${i + 1}`, data: src })}
                        onRegenerate={() => regenerate(idx)}
                        onRegenerateWith={() => setRegenerateAt(idx)}
                        onVersion={last ? (v) => switchVersion(idx, null, v) : null}
                      />
                    </div>
                  );
                })}
                <div ref={endRef} />
              </div>
            </div>
            {messages.length === 0 && (
              <div className="suggestions">
                {SUGGESTIONS.map((s) => (
                  <button key={s.title} onClick={() => send(s.prompt)}>
                    <b>{s.title}</b>
                    <span>{s.prompt}</span>
                  </button>
                ))}
              </div>
            )}
            <div
              className={`chat-input ${dragging ? 'dropping' : ''}`}
              onDragOver={(e) => {
                if (!busy && [...e.dataTransfer.types].includes('Files')) {
                  e.preventDefault();
                  setDragging(true);
                }
              }}
              onDragLeave={(e) => {
                if (!e.currentTarget.contains(e.relatedTarget)) setDragging(false);
              }}
              onDrop={(e) => {
                e.preventDefault();
                setDragging(false);
                if (!busy) addFiles(e.dataTransfer.files);
              }}
            >
              <div className="box">
                <Attachments list={attachments} onRemove={(id) => setAttachments((prev) => prev.filter((a) => a.id !== id))} onOpen={setPreview} />
                <textarea
                  placeholder={dragging ? 'Drop the files here…' : columns ? `Ask ${columns.length} models…` : 'Ask anything…'}
                  value={input}
                  onChange={(e) => setInput(e.target.value)}
                  onPaste={(e) => {
                    if (!busy && e.clipboardData.files.length > 0) {
                      e.preventDefault();
                      addFiles(e.clipboardData.files);
                    }
                  }}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && !e.shiftKey) {
                      e.preventDefault();
                      send();
                    }
                  }}
                />
                <div className="row between">
                  <div className="row" style={{ gap: 8, minWidth: 0 }}>
                    <button
                      className="copy-btn"
                      disabled={busy || kinds.length === 0}
                      onClick={() => attachRef.current && attachRef.current.click()}
                      title={attachTitle}
                    >
                      <Icon name="paperclip" />
                    </button>
                    <input
                      ref={attachRef}
                      type="file"
                      accept={ACCEPT}
                      multiple
                      hidden
                      onChange={(e) => {
                        addFiles(e.target.files);
                        e.target.value = '';
                      }}
                    />
                    <span className="faint small truncate">
                      {settings.stream ? 'Streaming' : 'Blocking'} · temp {settings.temperature} · max {settings.max_tokens || '∞'} tokens
                      {settings.preset ? ` · preset ${settings.preset}` : ''}
                      {availableTools.length > 0 && settings.tools !== null ? ` · ${offeredTools.length}/${availableTools.length} tools` : ''}
                      {carried > 0 ? ` · ${fmtBytes(carried)} of files` : ''}
                    </span>
                  </div>
                  {busy ? (
                    <button className="send" onClick={() => abortRef.current && abortRef.current.abort()} title="Stop">
                      <Icon name="stop" />
                    </button>
                  ) : (
                    <button className="send" disabled={!input.trim() && attachments.length === 0} onClick={() => send()} title="Send">
                      <Icon name="send" />
                    </button>
                  )}
                </div>
              </div>
              <div className="disclaimer">Responses are AI-generated and can be inaccurate.</div>
            </div>
          </div>
          {prefs.showSettings && (
            <div className="chat-settings">
              <h3>Request settings</h3>
              <Field label="Preset" hint="Adds the preset's messages around the conversation.">
                <Select value={settings.preset} onChange={(v) => setSettings({ preset: v })} placeholder="None" options={(presets.data || []).map((p) => ({ value: p.name, label: p.name }))} />
              </Field>
              <Field label="System prompt">
                <textarea value={settings.system} placeholder="You are a helpful assistant." onChange={(e) => setSettings({ system: e.target.value })} />
              </Field>
              {availableTools.length > 0 && (
                <Field label="Tools" hint="What the models may call in this chat. A tool stays attached to its provider: unchecking it only keeps it out of these calls.">
                  <div className="tool-picks">
                    {availableTools.map((t) => (
                      <label key={t.id} className="check" title={`${t.name} · ${TOOL_LABELS[t.kind]}`}>
                        <input type="checkbox" checked={toolIsOn(t.id)} onChange={(e) => toggleTool(t.id, e.target.checked)} />
                        <span className="truncate">{t.name}</span>
                        <span className="faint small nowrap">{TOOL_LABELS[t.kind]}</span>
                      </label>
                    ))}
                  </div>
                </Field>
              )}
              <Field label="Streaming">
                <Toggle value={settings.stream} onChange={(v) => setSettings({ stream: v })} />
              </Field>
              <Field label={`Temperature · ${settings.temperature}`}>
                <input type="range" min="0" max="2" step="0.1" value={settings.temperature} onChange={(e) => setSettings({ temperature: Number(e.target.value) })} />
              </Field>
              <Field label={`Top P · ${settings.top_p}`}>
                <input type="range" min="0" max="1" step="0.05" value={settings.top_p} onChange={(e) => setSettings({ top_p: Number(e.target.value) })} />
              </Field>
              <Field label="Max tokens">
                <NumberInput value={settings.max_tokens} onChange={(v) => setSettings({ max_tokens: v })} />
              </Field>
              <button className="btn ghost" onClick={() => setSettings(DEFAULT_SETTINGS)}>
                Reset
              </button>
            </div>
          )}
        </div>
      </div>
      {preview && <AttachmentModal attachment={preview} onClose={() => setPreview(null)} />}
      {regenerateAt !== null && (
        <RegenerateModal
          models={textModels}
          initial={(messages[regenerateAt] && messages[regenerateAt].requested) || model}
          onClose={() => setRegenerateAt(null)}
          onPick={(picked) => {
            const index = regenerateAt;
            setRegenerateAt(null);
            regenerate(index, picked);
          }}
        />
      )}
    </div>
  );
}
