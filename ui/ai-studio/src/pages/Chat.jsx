import { useEffect, useRef, useState } from 'react';
import { useWorkspace } from '../App';
import { Badge, Field, MenuButton, NumberInput, Select, Toggle, useAsync, useConfirm, useToast } from '../components/ui';
import { Icon } from '../components/icons';
import { Markdown } from '../components/Markdown';
import { chatCompletion } from '../lib/chat';
import { Resources, randomId, workspaceFilter } from '../lib/entities';
import { fmtCost, fmtInt, fmtMs } from '../lib/format';
import { deleteConversation, getConversation, listConversations, listWorkspaceModels, saveConversation } from '../lib/models';
import { bootstrap } from '../lib/bootstrap';
import { useRouter } from '../lib/router';
import { capabilitiesOf, chatUsable, contextOf, fmtPrice, fmtTokens, promptPrice } from '../lib/modelmeta';
import { copyOf, costOf, fileNameOf, MAX_IMPORT_BYTES, parseImport, persisted, toExport, toMarkdown, totalsOf } from '../lib/conversations';
import { download } from '../lib/files';

const SUGGESTIONS = [
  { title: 'Strawberry Test', prompt: "How many r's are in the word strawberry?" },
  { title: '9.9 vs 9.11', prompt: 'Which one is larger, 9.9 or 9.11? Explain briefly.' },
  { title: 'Poem Riddle', prompt: 'Compose a 12-line poem where the first letters spell OTOROSHI AI.' },
  { title: 'Car Wash Test', prompt: 'It is raining. Should I walk or drive to the car wash 200 m away?' },
];

const DEFAULT_SETTINGS = { system: '', stream: true, temperature: 0.7, top_p: 1, max_tokens: 1024, preset: '' };

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
  if (capabilitiesOf(model).some((c) => c.id === 'reasoning')) facts.push('reasoning');
  if (capabilitiesOf(model).some((c) => c.id === 'vision')) facts.push('vision');
  if (contextOf(model)) facts.push(fmtTokens(contextOf(model)));
  if (promptPrice(model) !== null) facts.push(`${fmtPrice(promptPrice(model))}/1M`);
  facts.push(model.provider);
  return facts.join(' · ');
}

function ModelPicker({ value, onChange, models }) {
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState('');
  const filtered = models.filter((m) => !q || m.id.toLowerCase().includes(q.toLowerCase())).slice(0, 80);
  return (
    <div style={{ position: 'relative', width: 520, maxWidth: '60vw' }}>
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
        <div className="search-results">
          {filtered.length === 0 && <div className="item muted">No model matches, press enter to use “{q}”</div>}
          {filtered.map((m) => (
            <div key={m.id} className={`item ${m.id === value ? 'active' : ''}`} onMouseDown={() => onChange(m.id)}>
              <span className="grow truncate">{m.id}</span>
              <span className="faint small nowrap">{pickerFacts(m)}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function costTitle(costs) {
  const part = (label, v) => (v === null || v === undefined ? null : `${label}: ${fmtCost(v)}`);
  return [part('input', costs.input_cost), part('output', costs.output_cost), part('reasoning', costs.reasoning_cost)].filter(Boolean).join(' · ');
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

  // the chat talks the chat completions api: image, realtime or responses only models would fail there
  const textModels = ((models.data && models.data.models) || []).filter(chatUsable);

  const [prefs, setPrefs] = useState(() => storageGet(prefKey, { model: '', settings: DEFAULT_SETTINGS, showSettings: true }));
  const settings = { ...DEFAULT_SETTINGS, ...(prefs.settings || {}) };
  const updatePrefs = (patch) =>
    setPrefs((p) => {
      const next = { ...p, ...patch };
      storageSet(prefKey, next);
      return next;
    });
  const setSettings = (patch) => updatePrefs({ settings: { ...settings, ...patch } });

  const [conversation, setConversation] = useState(null);
  // a temporary chat is never stored: it lives in this page only
  const [temporary, setTemporary] = useState(false);
  const [input, setInput] = useState('');
  const fileRef = useRef(null);
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
        setConversation(c);
      })
      .catch(toast.error);
  };

  const newChat = async (temp) => {
    if (!(await leaveTemporary())) return;
    if (busy) abortRef.current && abortRef.current.abort();
    setConversation(null);
    setTemporary(!!temp);
    setInput('');
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
      setConversation(cleared);
      if (!temporary) persist(cleared).catch(toast.error);
    });
  };

  const duplicateChat = async () => {
    const copy = copyOf(conversation, `conv_${randomId(16)}`, `Copy of ${conversation.title || 'Untitled'}`);
    try {
      await persist(copy);
      setConversation(copy);
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
      setConversation(imported);
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
          if (conversation && conversation.id === id) setConversation(null);
          rooms.reload();
        })
        .catch(toast.error);
    });
  };

  const send = async (text) => {
    const content = (text ?? input).trim();
    if (!content || busy) return;
    if (!model) {
      toast.error('Select a model');
      return;
    }
    const base = conversation || { id: `conv_${randomId(16)}`, title: content.substring(0, 60), created_at: Date.now(), messages: [] };
    const keep = !temporary;
    const userMsg = { role: 'user', content, at: Date.now() };
    const pending = { role: 'assistant', content: '', model, pending: true, at: Date.now() };
    let current = { ...base, model, messages: [...base.messages, userMsg, pending] };
    setConversation(current);
    setInput('');
    setBusy(true);
    const controller = new AbortController();
    abortRef.current = controller;
    const history = [...base.messages.filter((m) => !m.error), userMsg].map((m) => ({ role: m.role, content: m.content }));
    const messages = settings.system ? [{ role: 'system', content: settings.system }, ...history] : history;
    const body = {
      model,
      messages,
      temperature: Number(settings.temperature),
      top_p: Number(settings.top_p),
      ...(settings.max_tokens ? { max_tokens: Number(settings.max_tokens) } : {}),
      ...(settings.preset ? { context: settings.preset } : {}),
    };
    const replaceLast = (msg) => {
      current = { ...current, messages: [...current.messages.slice(0, -1), msg] };
      setConversation(current);
    };
    try {
      const res = await chatCompletion({
        workspace,
        body,
        stream: settings.stream,
        signal: controller.signal,
        sessionId: base.id,
        onDelta: (c, r) => replaceLast({ ...pending, content: c, reasoning: r }),
      });
      replaceLast({
        role: 'assistant',
        content: res.content,
        reasoning: res.reasoning || '',
        model: res.model || model,
        at: Date.now(),
        usage: res.usage || null,
        costs: res.costs || null,
        duration: res.duration,
        ttft: res.ttft,
      });
    } catch (e) {
      if (e.name === 'AbortError') {
        const last = current.messages[current.messages.length - 1];
        replaceLast({ ...last, pending: false, stopped: true });
      } else {
        replaceLast({ role: 'assistant', content: e.message, error: true, at: Date.now() });
      }
    } finally {
      setBusy(false);
      abortRef.current = null;
      if (keep) persist(current).catch(() => {});
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
          <ModelPicker value={model} onChange={(m) => updatePrefs({ model: m })} models={textModels} />
          <span className="muted small">{models.loading ? 'loading models…' : `${textModels.length} models`}</span>
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
              <div className="inner">
                {messages.length === 0 && (
                  <>
                    <div className="chat-welcome">
                      <h3>{temporary ? 'Temporary chat' : 'What can I help with?'}</h3>
                      <p className="muted">
                        Chat with the models of <code>{workspace.baseUrl.replace(/^https?:\/\//, '')}</code> as {bootstrap.user.email}.
                        {temporary && ' This chat is not saved, its calls still count in the activity and the budgets.'}
                      </p>
                    </div>
                  </>
                )}
                {messages.map((m, idx) => (
                  <div key={idx} className={`msg ${m.role} ${m.error ? 'error' : ''}`}>
                    <div className="bubble">
                      {m.reasoning && (
                        <details className="thinking" open={m.pending && !m.content}>
                          <summary>{m.pending && !m.content ? 'Thinking…' : 'Thought process'}</summary>
                          <div className="thinking-body">
                            <Markdown text={m.reasoning} />
                          </div>
                        </details>
                      )}
                      {m.role === 'assistant' && !m.error ? <Markdown text={m.content || (m.pending && !m.reasoning ? '…' : '')} /> : m.content}
                    </div>
                    {m.role === 'assistant' && !m.pending && !m.error && (
                      <div className="meta">
                        {m.model && <span>{m.model}</span>}
                        {m.usage && <span>{fmtInt((m.usage.prompt_tokens || 0) + (m.usage.completion_tokens || 0))} tokens</span>}
                        {costOf(m) !== null && <span title={costTitle(m.costs)}>{fmtCost(costOf(m))}</span>}
                        {m.duration && <span>{fmtMs(m.duration)}</span>}
                        {m.ttft && <span>ttft {fmtMs(m.ttft)}</span>}
                        {m.stopped && <span>stopped</span>}
                      </div>
                    )}
                  </div>
                ))}
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
            <div className="chat-input">
              <div className="box">
                <textarea
                  placeholder="Ask anything…"
                  value={input}
                  onChange={(e) => setInput(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && !e.shiftKey) {
                      e.preventDefault();
                      send();
                    }
                  }}
                />
                <div className="row between">
                  <span className="faint small">
                    {settings.stream ? 'Streaming' : 'Blocking'} · temp {settings.temperature} · max {settings.max_tokens || '∞'} tokens
                    {settings.preset ? ` · preset ${settings.preset}` : ''}
                  </span>
                  {busy ? (
                    <button className="send" onClick={() => abortRef.current && abortRef.current.abort()} title="Stop">
                      <Icon name="stop" />
                    </button>
                  ) : (
                    <button className="send" disabled={!input.trim()} onClick={() => send()} title="Send">
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
    </div>
  );
}
