class OtoroshiAssistantMessage extends Component {
  state = { copied: false }
  componentDidMount() {
    if (this.ref && this.props.scrollOnMount) {
      this.ref.scrollIntoView({ behavior: 'smooth', block: 'end' });
    }
  }
  componentWillUnmount() {
    if (this.copiedTimer) clearTimeout(this.copiedTimer);
  }
  copy = () => {
    const text = this.getRawContent();
    const done = () => {
      this.setState({ copied: true });
      if (this.copiedTimer) clearTimeout(this.copiedTimer);
      this.copiedTimer = setTimeout(() => this.setState({ copied: false }), 1500);
    };
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(done).catch(() => this.fallbackCopy(text, done));
    } else {
      this.fallbackCopy(text, done);
    }
  }
  fallbackCopy = (text, done) => {
    try {
      const ta = document.createElement('textarea');
      ta.value = text;
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      document.execCommand('copy');
      document.body.removeChild(ta);
      done();
    } catch (e) {}
  }
  getRawContent = () => {
    let c = this.props.message.content;
    if (!(typeof c === 'string' || c instanceof String)) c = JSON.stringify(c);
    return c;
  }
  retry = () => {
    if (this.props.onRetry) this.props.onRetry();
  }
  render() {
    const isUser = this.props.message.role === 'user';
    const isError = this.props.message.error === true;
    const content = this.getRawContent();
    const userBubble = {
      backgroundColor: 'var(--color-primary)',
      color: '#1a1a1a',
      borderBottomRightRadius: 2,
      borderBottomLeftRadius: 12,
    };
    const assistantBubble = {
      backgroundColor: 'var(--bg-color_level3)',
      color: 'var(--color_level3)',
      borderBottomRightRadius: 12,
      borderBottomLeftRadius: 2,
      border: '1px solid var(--border-color)',
    };
    const errorBubble = {
      backgroundColor: 'var(--color-red)',
      color: '#fff',
      borderBottomRightRadius: 12,
      borderBottomLeftRadius: 2,
    };
    const baseBubble = {
      maxWidth: '85%',
      padding: '8px 12px',
      borderRadius: 12,
      fontSize: 14,
      lineHeight: 1.45,
      wordWrap: 'break-word',
      boxShadow: '0 1px 2px rgba(0,0,0,0.15)',
    };
    const useMarkdown = !isUser && !isError && OtoroshiAssistantMessage.converter;
    const variant = isError ? errorBubble : (isUser ? userBubble : assistantBubble);
    const bubbleStyle = Object.assign({}, baseBubble, variant, useMarkdown ? {} : { whiteSpace: 'pre-wrap' });
    const rowStyle = {
      display: 'flex',
      flexDirection: 'column',
      alignItems: isUser ? 'flex-end' : 'flex-start',
      marginBottom: 8,
      width: '100%',
    };
    const showActions = !isUser && !isError && content && content.length > 0;
    const bubble = useMarkdown
      ? React.createElement('div', {
          className: 'otoroshi-assistant-message-md',
          style: bubbleStyle,
          dangerouslySetInnerHTML: { __html: OtoroshiAssistantMessage.converter.makeHtml(content) }
        })
      : React.createElement('div', { style: bubbleStyle }, content);
    const actions = showActions ? React.createElement('div', {
      className: 'otoroshi-assistant-message-actions',
      style: { display: 'flex', flexDirection: 'row', gap: 4, marginTop: 4, marginLeft: 4 }
    },
      React.createElement('button', {
        type: 'button',
        title: this.state.copied ? 'Copied!' : 'Copy',
        onClick: this.copy,
        className: 'otoroshi-assistant-icon-btn',
        style: { fontSize: 12, padding: '2px 6px' },
      }, React.createElement('i', { className: this.state.copied ? 'fas fa-check' : 'fas fa-copy' })),
      this.props.onRetry ? React.createElement('button', {
        type: 'button',
        title: 'Retry',
        onClick: this.retry,
        disabled: this.props.retryDisabled,
        className: 'otoroshi-assistant-icon-btn',
        style: { fontSize: 12, padding: '2px 6px' },
      }, React.createElement('i', { className: 'fas fa-rotate-right' })) : null,
    ) : null;
    return React.createElement('div', { ref: (r) => this.ref = r, style: rowStyle },
      bubble,
      actions,
    );
  }
}

class OtoroshiAssistantTyping extends Component {
  formatArgs(argsRaw) {
    if (argsRaw == null || argsRaw === '') return '';
    let s = argsRaw;
    if (typeof s !== 'string') {
      try { s = JSON.stringify(s); } catch (e) { s = String(s); }
    } else {
      try {
        const parsed = JSON.parse(s);
        s = JSON.stringify(parsed);
      } catch (e) { /* leave as-is, may be partial JSON during streaming */ }
    }
    s = s.replace(/\s+/g, ' ').trim();
    if (s.length > 80) s = s.slice(0, 77) + '…';
    return s;
  }
  renderToolRow(tool, kind) {
    const argsText = this.formatArgs(tool.arguments);
    let icon, iconColor;
    if (kind === 'active') { icon = 'fas fa-cog fa-spin'; iconColor = 'var(--color-primary)'; }
    else if (kind === 'ok') { icon = 'fas fa-check'; iconColor = 'var(--color-green)'; }
    else { icon = 'fas fa-times'; iconColor = 'var(--color-red)'; }
    const meta = kind === 'active'
      ? null
      : React.createElement('span', { style: { color: 'var(--text-muted)', fontSize: 10, marginLeft: 4 } }, `(${tool.durationMs}ms)`);
    return React.createElement('div', {
      key: tool.id + '-' + kind,
      style: { display: 'flex', flexDirection: 'row', alignItems: 'baseline', gap: 6, fontSize: 11, lineHeight: 1.4 }
    },
      React.createElement('i', { className: icon, style: { fontSize: 10, color: iconColor, width: 12, textAlign: 'center', flexShrink: 0 } }),
      React.createElement('span', { style: { fontWeight: 600, color: 'var(--text)' } }, tool.name || '(unknown)'),
      argsText ? React.createElement('code', {
        style: {
          color: 'var(--text-muted)',
          backgroundColor: 'var(--bg-color_level1)',
          border: '1px solid var(--border-color)',
          borderRadius: 4,
          padding: '1px 5px',
          fontSize: 10,
          whiteSpace: 'nowrap',
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          maxWidth: '100%',
        }
      }, argsText) : null,
      meta,
    );
  }
  render() {
    const activeTools = this.props.activeTools || [];
    const completedTools = this.props.completedTools || [];

    const dotStyle = {
      width: 6,
      height: 6,
      borderRadius: '50%',
      backgroundColor: 'var(--text-muted)',
      margin: '0 2px',
      display: 'inline-block',
      animation: 'otoroshi-assistant-bounce 1.2s infinite ease-in-out',
    };

    const showToolBar = activeTools.length > 0 || completedTools.length > 0;

    return React.createElement('div', {
      style: { display: 'flex', flexDirection: 'column', alignItems: 'flex-start', marginBottom: 8, gap: 6, width: '100%' }
    },
      React.createElement('div', {
        style: {
          padding: '10px 14px',
          borderRadius: 12,
          backgroundColor: 'var(--bg-color_level3)',
          border: '1px solid var(--border-color)',
          borderBottomLeftRadius: 2,
          display: 'flex',
          alignItems: 'center',
        }
      },
        React.createElement('span', { style: Object.assign({}, dotStyle, { animationDelay: '0s' }) }),
        React.createElement('span', { style: Object.assign({}, dotStyle, { animationDelay: '0.15s' }) }),
        React.createElement('span', { style: Object.assign({}, dotStyle, { animationDelay: '0.3s' }) }),
      ),
      showToolBar ? React.createElement('div', {
        style: {
          width: '100%',
          maxWidth: '95%',
          padding: '6px 10px',
          borderRadius: 8,
          backgroundColor: 'var(--bg-color_level2)',
          border: '1px dashed var(--border-color)',
          display: 'flex',
          flexDirection: 'column',
          gap: 4,
        }
      },
        completedTools.map(t => this.renderToolRow(t, t.ok ? 'ok' : 'err')),
        activeTools.map(t => this.renderToolRow(t, 'active')),
        completedTools.length > 0 ? React.createElement('div', {
          style: { fontSize: 10, color: 'var(--text-muted)', marginTop: 2 }
        }, `${completedTools.length} tool call${completedTools.length > 1 ? 's' : ''} done`) : null,
      ) : null,
    );
  }
}

const OTOROSHI_ASSISTANT_STREAMING_KEY = 'otoroshi-assistant-streaming';

class OtoroshiAssistant extends Component {
  state = {
    display: false,
    expanded: false,
    calling: false,
    streaming: true,
    activeTools: [],
    completedTools: [],
    input: '',
    messages: [],
  }

  componentDidMount() {
    try {
      const persisted = window.localStorage.getItem(OTOROSHI_ASSISTANT_STREAMING_KEY);
      if (persisted === 'true') this.setState({ streaming: true });
    } catch (e) { /* ignore */ }
    if (!OtoroshiAssistantMessage.converter) {
      OtoroshiAssistantMessage.converter = new showdown.Converter({
        omitExtraWLInCodeBlocks: true,
        ghCompatibleHeaderId: true,
        parseImgDimensions: true,
        simplifiedAutoLink: true,
        tables: true,
        tasklists: true,
        requireSpaceBeforeHeadingText: true,
        ghMentions: true,
        emoji: true,
        ghMentionsLink: '/{u}',
        flavor: 'github',
        openLinksInNewWindow: true
      });
    }
    if (!document.getElementById('otoroshi-assistant-styles')) {
      const style = document.createElement('style');
      style.id = 'otoroshi-assistant-styles';
      style.innerHTML = [
        '@keyframes otoroshi-assistant-bounce {',
        '  0%, 80%, 100% { transform: scale(0.6); opacity: 0.5; }',
        '  40% { transform: scale(1); opacity: 1; }',
        '}',
        '@keyframes otoroshi-assistant-fadein {',
        '  from { opacity: 0; transform: translateY(10px); }',
        '  to   { opacity: 1; transform: translateY(0); }',
        '}',
        '.otoroshi-assistant-message-md { white-space: normal; }',
        '.otoroshi-assistant-message-md > *:last-child { margin-bottom: 0 !important; }',
        '.otoroshi-assistant-message-md p { margin: 0 0 6px 0; }',
        '.otoroshi-assistant-message-md pre { height: auto !important; }',
        '.otoroshi-assistant-message-md p:last-child { margin-bottom: 0; }',
        '.otoroshi-assistant-message-md pre { background: var(--bg-color_level1); padding: 8px; border-radius: 6px; overflow-x: auto; margin: 6px 0; border: 1px solid var(--border-color); }',
        '.otoroshi-assistant-message-md code { background: var(--bg-color_level1); padding: 1px 4px; border-radius: 3px; font-size: 12px; }',
        '.otoroshi-assistant-message-md pre code { background: transparent; padding: 0; border: none; }',
        '.otoroshi-assistant-message-md a { color: var(--color-primary); }',
        '.otoroshi-assistant-message-md ul, .otoroshi-assistant-message-md ol { margin: 4px 0 6px 18px; padding: 0; }',
        '.otoroshi-assistant-message-md table { border-collapse: collapse; margin: 6px 0; }',
        '.otoroshi-assistant-message-md table th, .otoroshi-assistant-message-md table td { border: 1px solid var(--border-color-strong, var(--border-color)); padding: 4px 8px; }',
        '.otoroshi-assistant-button:hover { transform: scale(1.06); box-shadow: 0 6px 18px rgba(0,0,0,0.45); }',
        '.otoroshi-assistant-icon-btn { background: transparent; border: none; cursor: pointer; padding: 4px 8px; border-radius: 4px; color: var(--text-muted); transition: background-color 0.15s, color 0.15s; }',
        '.otoroshi-assistant-icon-btn:hover:not(:disabled) { background: var(--hover-bg); color: var(--text); }',
        '.otoroshi-assistant-icon-btn:disabled { opacity: 0.4; cursor: not-allowed; }',
        '.otoroshi-assistant-input::placeholder { color: var(--text-muted); }',
        '.otoroshi-assistant-input:focus { border-color: var(--color-primary) !important; }',
      ].join('\n');
      document.head.appendChild(style);
    }
  }

  componentDidUpdate(_, prevState) {
    if (this.state.messages.length !== prevState.messages.length || this.state.calling !== prevState.calling) {
      if (this.scrollRef) {
        this.scrollRef.scrollTop = this.scrollRef.scrollHeight;
      }
    }
    if (this.state.display && !prevState.display && this.inputRef) {
      this.inputRef.focus();
    }
  }

  toggle = () => {
    this.setState({ display: !this.state.display });
  }

  toggleExpanded = () => {
    this.setState({ expanded: !this.state.expanded });
  }

  toggleStreaming = () => {
    if (this.state.calling) return;
    const next = !this.state.streaming;
    try { window.localStorage.setItem(OTOROSHI_ASSISTANT_STREAMING_KEY, next ? 'true' : 'false'); } catch (e) { /* ignore */ }
    this.setState({ streaming: next });
  }

  clear = () => {
    this.setState({ messages: [], activeTools: [], completedTools: [] });
  }

  buildApiMessages = (messages) => messages
    .filter(m => !m.error)
    .map(m => ({ role: m.role, content: m.content }));

  callApi = (messages) => {
    this.setState({ calling: true, activeTools: [], completedTools: [] });
    if (this.state.streaming) {
      this.callApiStreaming(messages);
    } else {
      this.callApiBlocking(messages);
    }
  }

  callApiBlocking = (messages) => {
    fetch('/extensions/cloud-apim/extensions/ai-extension/assistant/chat/completions', {
      method: 'POST',
      credentials: 'include',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'X-Otoroshi-Assistant-Current-Url': window.location.href,
      },
      body: JSON.stringify({
        messages: this.buildApiMessages(messages),
        stream: false,
      })
    }).then(r => {
      if (!r.ok) {
        return r.text().then(txt => {
          let errMsg = txt;
          try { const j = JSON.parse(txt); errMsg = (j.error && (j.error.message || j.error)) || j.message || txt; } catch (e) {}
          throw new Error(errMsg || ('HTTP ' + r.status));
        });
      }
      return r.json();
    }).then(r => {
      const choice = r && r.choices && r.choices[0];
      const msg = choice && choice.message;
      const content = (msg && msg.content) || '';
      const next = this.state.messages.concat([{
        role: 'assistant',
        content: content,
        date: Date.now(),
      }]);
      this.setState({ messages: next, calling: false }, () => {
        if (this.inputRef) this.inputRef.focus();
      });
    }).catch(ex => {
      const next = this.state.messages.concat([{
        role: 'assistant',
        content: (ex && ex.message) ? ex.message : String(ex),
        error: true,
        date: Date.now(),
      }]);
      this.setState({ messages: next, calling: false }, () => {
        if (this.inputRef) this.inputRef.focus();
      });
    });
  }

  callApiStreaming = (messages) => {
    const placeholder = { role: 'assistant', content: '', date: Date.now(), streaming: true };
    this.setState({ messages: this.state.messages.concat([placeholder]) });

    const finalize = (errorMsg) => {
      const msgs = this.state.messages.slice();
      const last = msgs[msgs.length - 1];
      if (last && last.streaming) {
        if (errorMsg && !last.content) {
          msgs[msgs.length - 1] = Object.assign({}, last, { content: errorMsg, error: true, streaming: false });
        } else if (errorMsg) {
          msgs.push({ role: 'assistant', content: errorMsg, error: true, date: Date.now() });
          msgs[msgs.length - 2] = Object.assign({}, last, { streaming: false });
        } else {
          msgs[msgs.length - 1] = Object.assign({}, last, { streaming: false });
        }
      } else if (errorMsg) {
        msgs.push({ role: 'assistant', content: errorMsg, error: true, date: Date.now() });
      }
      this.setState({ messages: msgs, calling: false, activeTools: [] }, () => {
        if (this.inputRef) this.inputRef.focus();
      });
    };

    const appendDelta = (text) => {
      if (!text) return;
      const msgs = this.state.messages.slice();
      const last = msgs[msgs.length - 1];
      if (last && last.streaming) {
        msgs[msgs.length - 1] = Object.assign({}, last, { content: (last.content || '') + text });
        this.setState({ messages: msgs });
      }
    };

    const handleEvent = (json) => {
      const ev = json.otoroshi_assistant_event;
      if (ev === 'tool_call_started') {
        const tool = { id: json.id, name: json.name, arguments: json.arguments, iteration: json.iteration, startedAt: Date.now() };
        this.setState({ activeTools: this.state.activeTools.concat([tool]) });
      } else if (ev === 'tool_call_finished') {
        const remaining = this.state.activeTools.filter(t => t.id !== json.id);
        const matched = this.state.activeTools.find(t => t.id === json.id) || { name: json.name, arguments: '' };
        const completed = {
          id: json.id,
          name: json.name || matched.name,
          arguments: matched.arguments,
          ok: !!json.ok,
          durationMs: json.duration_ms,
          iteration: json.iteration,
        };
        this.setState({ activeTools: remaining, completedTools: this.state.completedTools.concat([completed]) });
      } else if (json.choices && json.choices[0]) {
        const delta = json.choices[0].delta;
        if (delta && typeof delta.content === 'string') {
          appendDelta(delta.content);
        }
      }
    };

    fetch('/extensions/cloud-apim/extensions/ai-extension/assistant/chat/completions', {
      method: 'POST',
      credentials: 'include',
      headers: {
        Accept: 'text/event-stream',
        'Content-Type': 'application/json',
        'X-Otoroshi-Assistant-Current-Url': window.location.href,
      },
      body: JSON.stringify({
        messages: this.buildApiMessages(messages),
        stream: true,
      })
    }).then(async (r) => {
      if (!r.ok) {
        const txt = await r.text();
        let errMsg = txt;
        try { const j = JSON.parse(txt); errMsg = (j.error && (j.error.message || j.error)) || j.message || txt; } catch (e) {}
        throw new Error(errMsg || ('HTTP ' + r.status));
      }
      if (!r.body || !r.body.getReader) {
        throw new Error('Streaming not supported by this browser');
      }
      const reader = r.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      let done = false;
      while (!done) {
        const { value, done: rdone } = await reader.read();
        done = rdone;
        if (value) buffer += decoder.decode(value, { stream: !done });
        let sepIdx;
        while ((sepIdx = buffer.indexOf('\n\n')) >= 0) {
          const chunk = buffer.slice(0, sepIdx);
          buffer = buffer.slice(sepIdx + 2);
          const lines = chunk.split('\n');
          for (const line of lines) {
            if (!line.startsWith('data:')) continue;
            const data = line.slice(5).trim();
            if (!data) continue;
            if (data === '[DONE]') { done = true; break; }
            try { handleEvent(JSON.parse(data)); } catch (e) { /* skip malformed */ }
          }
        }
      }
      finalize(null);
    }).catch(ex => {
      finalize((ex && ex.message) ? ex.message : String(ex));
    });
  }

  send = () => {
    const input = (this.state.input || '').trim();
    if (!input || this.state.calling) return;
    const newUserMessage = { role: 'user', content: input, date: Date.now() };
    const messages = this.state.messages.concat([newUserMessage]);
    this.setState({ messages, input: '' }, () => this.callApi(messages));
  }

  retryAt = (idx) => {
    if (this.state.calling) return;
    const truncated = this.state.messages.slice(0, idx);
    if (truncated.length === 0) return;
    this.setState({ messages: truncated }, () => this.callApi(truncated));
  }

  keydown = (event) => {
    if (event.keyCode === 13 && !event.shiftKey) {
      event.preventDefault();
      this.send();
    }
  }

  renderButton() {
    return React.createElement('div', {
      className: 'otoroshi-assistant-button',
      onClick: this.toggle,
      title: 'Otoroshi assistant',
      style: {
        position: 'fixed',
        bottom: 20,
        right: 20,
        width: 60,
        height: 60,
        borderRadius: '50%',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        backgroundColor: 'var(--bg-color_level2)',
        border: '2px solid var(--color-primary)',
        cursor: 'pointer',
        boxShadow: '0 4px 14px rgba(0,0,0,0.25)',
        zIndex: 99999,
        transition: 'transform 0.15s ease, box-shadow 0.15s ease',
      }
    },
      React.createElement('img', {
        src: '/assets/images/otoroshi-logo-color.png',
        alt: 'Otoroshi assistant',
        style: { width: 36, height: 36, objectFit: 'contain' }
      })
    );
  }

  renderHeader() {
    return React.createElement('div', {
      style: {
        display: 'flex',
        flexDirection: 'row',
        alignItems: 'center',
        padding: '10px 12px',
        borderBottom: '1px solid var(--border-color)',
        backgroundColor: 'var(--bg-color_level1)',
        borderTopLeftRadius: 12,
        borderTopRightRadius: 12,
      }
    },
      React.createElement('img', {
        src: '/assets/images/otoroshi-logo-color.png',
        alt: 'Otoroshi',
        style: { width: 28, height: 28, objectFit: 'contain', marginRight: 10 }
      }),
      React.createElement('div', { style: { display: 'flex', flexDirection: 'column', flex: 1 } },
        React.createElement('span', { style: { color: 'var(--text)', fontWeight: 600, fontSize: 14 } }, 'Otoroshi assistant'),
        React.createElement('span', { style: { color: 'var(--text-muted)', fontSize: 11 } },
          this.state.calling
            ? (this.state.completedTools.length > 0
                ? `thinking… · ${this.state.completedTools.length} tool call${this.state.completedTools.length > 1 ? 's' : ''}`
                : 'thinking…')
            : (this.state.streaming ? 'online · streaming' : 'online'),
        ),
      ),
      React.createElement('button', {
        type: 'button',
        title: this.state.streaming ? 'Streaming on (click to disable)' : 'Streaming off (click to enable)',
        onClick: this.toggleStreaming,
        disabled: this.state.calling,
        className: 'otoroshi-assistant-icon-btn',
        style: { marginRight: 4, fontSize: 14, color: this.state.streaming ? 'var(--color-primary)' : 'var(--text-muted)' },
      }, React.createElement('i', { className: 'fas fa-bolt' })),
      React.createElement('button', {
        type: 'button',
        title: 'Clear conversation',
        onClick: this.clear,
        disabled: this.state.messages.length === 0 || this.state.calling,
        className: 'otoroshi-assistant-icon-btn',
        style: { marginRight: 4, fontSize: 14 },
      }, React.createElement('i', { className: 'fas fa-broom' })),
      React.createElement('button', {
        type: 'button',
        title: this.state.expanded ? 'Shrink window' : 'Expand window',
        onClick: this.toggleExpanded,
        className: 'otoroshi-assistant-icon-btn',
        style: { marginRight: 4, fontSize: 14 },
      }, React.createElement('i', { className: this.state.expanded ? 'fas fa-compress' : 'fas fa-expand' })),
      React.createElement('button', {
        type: 'button',
        title: 'Close',
        onClick: this.toggle,
        className: 'otoroshi-assistant-icon-btn',
        style: { fontSize: 16 },
      }, React.createElement('i', { className: 'fas fa-times' })),
    );
  }

  renderEmptyState() {
    return React.createElement('div', {
      style: {
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        textAlign: 'center',
        height: '100%',
        color: 'var(--text-muted)',
        padding: 16,
      }
    },
      React.createElement('img', {
        src: '/assets/images/otoroshi-logo-color.png',
        alt: 'Otoroshi',
        style: { width: 64, height: 64, objectFit: 'contain', marginBottom: 12, opacity: 0.85 }
      }),
      React.createElement('div', { style: { color: 'var(--text)', fontWeight: 600, fontSize: 14, marginBottom: 4 } }, 'How can I help?'),
      React.createElement('div', { style: { fontSize: 12 } }, 'Ask me anything about your Otoroshi setup.'),
    );
  }

  renderMessages() {
    return React.createElement('div', {
      ref: (r) => this.scrollRef = r,
      style: {
        flex: 1,
        overflowY: 'auto',
        padding: 12,
        display: 'flex',
        flexDirection: 'column',
        backgroundColor: 'var(--bg-color_level2)',
      }
    },
      this.state.messages.length === 0 && !this.state.calling
        ? this.renderEmptyState()
        : this.state.messages.map((m, idx) => React.createElement(OtoroshiAssistantMessage, {
            key: m.date + '-' + m.role + '-' + idx,
            message: m,
            onRetry: m.role === 'assistant' && !m.error && !m.streaming ? () => this.retryAt(idx) : undefined,
            retryDisabled: this.state.calling,
          })),
      this.state.calling ? React.createElement(OtoroshiAssistantTyping, {
        key: 'typing',
        activeTools: this.state.activeTools,
        completedTools: this.state.completedTools,
      }) : null,
    );
  }

  renderInput() {
    const canSend = !this.state.calling && (this.state.input || '').trim();
    return React.createElement('div', {
      style: {
        display: 'flex',
        flexDirection: 'row',
        alignItems: 'flex-end',
        padding: 10,
        borderTop: '1px solid var(--border-color)',
        backgroundColor: 'var(--bg-color_level1)',
        borderBottomLeftRadius: 12,
        borderBottomRightRadius: 12,
      }
    },
      React.createElement('textarea', {
        ref: (r) => this.inputRef = r,
        className: 'otoroshi-assistant-input',
        value: this.state.input,
        onChange: (e) => this.setState({ input: e.target.value }),
        onKeyDown: this.keydown,
        placeholder: 'Type a message…',
        rows: this.state.expanded ? 4 : 1,
        style: {
          flex: 1,
          resize: 'none',
          maxHeight: this.state.expanded ? 320 : 120,
          minHeight: 36,
          padding: '8px 10px',
          borderRadius: 8,
          border: '1px solid var(--input-border)',
          backgroundColor: 'var(--input-bg)',
          color: 'var(--text)',
          fontSize: 14,
          outline: 'none',
          fontFamily: 'inherit',
        },
      }),
      React.createElement('button', {
        type: 'button',
        onClick: this.send,
        disabled: !canSend,
        title: 'Send',
        style: {
          marginLeft: 8,
          width: 38,
          height: 38,
          borderRadius: '50%',
          border: 'none',
          backgroundColor: 'var(--color-primary)',
          color: '#1a1a1a',
          cursor: canSend ? 'pointer' : 'not-allowed',
          opacity: canSend ? 1 : 0.5,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
        }
      }, React.createElement('i', { className: 'fas fa-paper-plane' })),
    );
  }

  renderChatBox() {
    const expanded = this.state.expanded;
    const sizeStyle = expanded
      ? { width: 'min(900px, calc(100vw - 40px))', height: 'calc(100vh - 40px)', maxHeight: 'none', minHeight: 0 }
      : { width: 380, maxWidth: 'calc(100vw - 40px)', height: '70vh', maxHeight: 700, minHeight: 420 };
    return React.createElement('div', {
      className: 'otoroshi-assistant-chatbox',
      style: Object.assign({
        position: 'fixed',
        bottom: 20,
        right: 20,
        display: 'flex',
        flexDirection: 'column',
        backgroundColor: 'var(--bg-color_level2)',
        borderRadius: 12,
        boxShadow: '0 10px 40px rgba(0,0,0,0.35)',
        border: '1px solid var(--border-color)',
        zIndex: 99999,
        animation: 'otoroshi-assistant-fadein 0.18s ease-out',
        overflow: 'hidden',
        transition: 'width 0.18s ease, height 0.18s ease',
      }, sizeStyle),
    },
      this.renderHeader(),
      this.renderMessages(),
      this.renderInput(),
    );
  }

  render() {
    return React.createElement('div', { className: 'otoroshi-assistant' },
      this.state.display ? this.renderChatBox() : this.renderButton()
    );
  }
}
