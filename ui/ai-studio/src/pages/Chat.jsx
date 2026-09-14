import { Fragment, useEffect, useMemo, useRef, useState } from 'react';
import { useWorkspace } from '../App';
import { Field, NumberInput, Select, Toggle, useAsync, useConfirm, useToast } from '../components/ui';
import { Icon } from '../components/icons';
import { listApikeys } from '../lib/apikeys';
import { chatCompletion } from '../lib/chat';
import { Resources, randomId, workspaceFilter } from '../lib/entities';
import { fmtInt, fmtMs } from '../lib/format';
import { deleteConversation, getConversation, listConversations, listWorkspaceModels, saveConversation } from '../lib/models';
import { Link, useRouter } from '../lib/router';

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

// very small markdown renderer: fenced code blocks, inline code and bold, everything else as text
function Markdown({ text }) {
  const parts = useMemo(() => {
    const out = [];
    const re = /```([\w-]*)\n?([\s\S]*?)(```|$)/g;
    let last = 0;
    let m;
    while ((m = re.exec(text))) {
      if (m.index > last) out.push({ kind: 'text', value: text.substring(last, m.index) });
      out.push({ kind: 'code', value: m[2], lang: m[1] });
      last = re.lastIndex;
      if (m[3] === '') break;
    }
    if (last < text.length) out.push({ kind: 'text', value: text.substring(last) });
    return out;
  }, [text]);
  return parts.map((p, i) =>
    p.kind === 'code' ? (
      <pre key={i} style={{ margin: '8px 0', whiteSpace: 'pre' }}>
        <code>{p.value}</code>
      </pre>
    ) : (
      <Fragment key={i}>{inline(p.value)}</Fragment>
    )
  );
}

function inline(text) {
  // headings and horizontal rules, line by line
  return text.split(/(\n)/).map((line, idx) => {
    if (/^\s*(\*\*\*|---|___)\s*$/.test(line)) return <hr key={idx} />;
    const h = line.match(/^\s*#{1,6}\s+(.*)$/);
    if (h) return <span key={idx} className="md-h">{inlineSpans(h[1])}</span>;
    return <Fragment key={idx}>{inlineSpans(line)}</Fragment>;
  });
}

function inlineSpans(text) {
  const tokens = text.split(/(`[^`\n]+`|\*\*[^*\n]+\*\*)/g);
  return tokens.map((t, i) => {
    if (t.startsWith('`') && t.endsWith('`') && t.length > 2) return <code key={i}>{t.substring(1, t.length - 1)}</code>;
    if (t.startsWith('**') && t.endsWith('**') && t.length > 4) return <b key={i}>{t.substring(2, t.length - 2)}</b>;
    return <Fragment key={i}>{t}</Fragment>;
  });
}

function ModelPicker({ value, onChange, models }) {
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState('');
  const filtered = models.filter((m) => !q || m.id.toLowerCase().includes(q.toLowerCase())).slice(0, 80);
  return (
    <div style={{ position: 'relative', width: 420, maxWidth: '50vw' }}>
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
              <span className="faint small">{m.provider}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

export function ChatPage() {
  const { workspace } = useWorkspace();
  const toast = useToast();
  const confirm = useConfirm();
  const { query } = useRouter();
  const prefKey = `ai-studio-chat-${workspace.id}`;

  const models = useAsync(() => listWorkspaceModels(workspace), [workspace.id]);
  const keys = useAsync(() => listApikeys(workspace.id), [workspace.id]);
  const presets = useAsync(() => Resources.contexts.list(workspaceFilter(workspace.id)), [workspace.id]);
  const rooms = useAsync(() => listConversations(workspace), [workspace.id]);

  const textModels = ((models.data && models.data.models) || []).filter((m) => m.modality === 'text');
  const enabledKeys = (keys.data || []).filter((k) => k.enabled);

  const [prefs, setPrefs] = useState(() => storageGet(prefKey, { model: '', apikey: '', settings: DEFAULT_SETTINGS, showSettings: true }));
  const settings = { ...DEFAULT_SETTINGS, ...(prefs.settings || {}) };
  const updatePrefs = (patch) =>
    setPrefs((p) => {
      const next = { ...p, ...patch };
      storageSet(prefKey, next);
      return next;
    });
  const setSettings = (patch) => updatePrefs({ settings: { ...settings, ...patch } });

  const [conversation, setConversation] = useState(null);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const abortRef = useRef(null);
  const endRef = useRef(null);

  const model = query.model || prefs.model || (textModels[0] && textModels[0].id) || '';
  const apikey = enabledKeys.find((k) => k.clientId === prefs.apikey) ? prefs.apikey : (enabledKeys[0] && enabledKeys[0].clientId) || '';

  useEffect(() => {
    if (query.model && query.model !== prefs.model) updatePrefs({ model: query.model });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query.model]);

  useEffect(() => {
    endRef.current && endRef.current.scrollIntoView({ block: 'end' });
  }, [conversation]);

  const openRoom = (id) => {
    if (busy) return;
    getConversation(workspace, id)
      .then((c) => setConversation(c))
      .catch(toast.error);
  };

  const newChat = () => {
    if (busy) abortRef.current && abortRef.current.abort();
    setConversation(null);
    setInput('');
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
    if (!apikey) {
      toast.error('Create an API key in this workspace to use the chat');
      return;
    }
    if (!model) {
      toast.error('Select a model');
      return;
    }
    const base = conversation || { id: `conv_${randomId(16)}`, title: content.substring(0, 60), created_at: Date.now(), messages: [] };
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
        apikey,
        body,
        stream: settings.stream,
        signal: controller.signal,
        onDelta: (c, r) => replaceLast({ ...pending, content: c, reasoning: r }),
      });
      replaceLast({
        role: 'assistant',
        content: res.content,
        reasoning: res.reasoning || '',
        model: res.model || model,
        at: Date.now(),
        usage: res.usage || null,
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
      saveConversation(workspace, { ...current, messages: current.messages.map(({ pending: _p, ...m }) => m) })
        .then(() => rooms.reload())
        .catch(() => {});
    }
  };

  const messages = (conversation && conversation.messages) || [];
  const roomList = rooms.data || [];

  return (
    <div className="chat grow" style={{ gridTemplateColumns: '260px minmax(0, 1fr)' }}>
      <div className="chat-side">
        <button className="btn primary" onClick={newChat}>
          <Icon name="plus" />
          New chat
        </button>
        <div className="faint small" style={{ padding: '2px 6px' }}>
          {roomList.length} room{roomList.length === 1 ? '' : 's'} · stored in Otoroshi
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
                      <h3>What can I help with?</h3>
                      <p className="muted">
                        Requests go through <code>{workspace.baseUrl.replace(/^https?:\/\//, '')}</code> with your API key.
                      </p>
                      {keys.data && enabledKeys.length === 0 && (
                        <p className="alert warning mt">
                          No enabled API key in this workspace. <Link className="link" to={`/workspaces/${workspace.id}/keys`}>Create one</Link> to start chatting.
                        </p>
                      )}
                    </div>
                  </>
                )}
                {messages.map((m, idx) => (
                  <div key={idx} className={`msg ${m.role} ${m.error ? 'error' : ''}`}>
                    <div className="bubble">
                      {m.reasoning && (
                        <details className="thinking" open={m.pending && !m.content}>
                          <summary>{m.pending && !m.content ? 'Thinking…' : 'Thought process'}</summary>
                          <div className="thinking-body">{m.reasoning}</div>
                        </details>
                      )}
                      {m.role === 'assistant' && !m.error ? <Markdown text={m.content || (m.pending && !m.reasoning ? '…' : '')} /> : m.content}
                    </div>
                    {m.role === 'assistant' && !m.pending && !m.error && (
                      <div className="meta">
                        {m.model && <span>{m.model}</span>}
                        {m.usage && <span>{fmtInt((m.usage.prompt_tokens || 0) + (m.usage.completion_tokens || 0))} tokens</span>}
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
              <Field label="API key" hint="Key used to call the workspace.">
                <Select value={apikey} onChange={(v) => updatePrefs({ apikey: v })} options={enabledKeys.map((k) => ({ value: k.clientId, label: k.clientName }))} placeholder={enabledKeys.length ? undefined : 'No key'} />
              </Field>
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
