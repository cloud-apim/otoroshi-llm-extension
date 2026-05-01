class OtoroshiAssistantMessage extends Component {
  componentDidMount() {
    if (this.ref && this.props.scrollOnMount) {
      this.ref.scrollIntoView({ behavior: 'smooth', block: 'end' });
    }
  }
  render() {
    const isUser = this.props.message.role === 'user';
    const isError = this.props.message.error === true;
    let content = this.props.message.content;
    if (!(typeof content === 'string' || content instanceof String)) {
      content = JSON.stringify(content);
    }
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
      whiteSpace: 'pre-wrap',
      boxShadow: '0 1px 2px rgba(0,0,0,0.15)',
    };
    const variant = isError ? errorBubble : (isUser ? userBubble : assistantBubble);
    const bubbleStyle = Object.assign({}, baseBubble, variant);
    const rowStyle = {
      display: 'flex',
      flexDirection: 'row',
      justifyContent: isUser ? 'flex-end' : 'flex-start',
      marginBottom: 8,
      width: '100%',
    };
    const useMarkdown = !isUser && !isError && OtoroshiAssistantMessage.converter;
    return React.createElement('div', { ref: (r) => this.ref = r, style: rowStyle },
      useMarkdown
        ? React.createElement('div', {
            className: 'otoroshi-assistant-message-md',
            style: bubbleStyle,
            dangerouslySetInnerHTML: { __html: OtoroshiAssistantMessage.converter.makeHtml(content) }
          })
        : React.createElement('div', { style: bubbleStyle }, content),
    );
  }
}

class OtoroshiAssistantTyping extends Component {
  render() {
    const dotStyle = {
      width: 6,
      height: 6,
      borderRadius: '50%',
      backgroundColor: 'var(--text-muted)',
      margin: '0 2px',
      display: 'inline-block',
      animation: 'otoroshi-assistant-bounce 1.2s infinite ease-in-out',
    };
    return React.createElement('div', {
      style: { display: 'flex', flexDirection: 'row', justifyContent: 'flex-start', marginBottom: 8 }
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
      )
    );
  }
}

class OtoroshiAssistant extends Component {
  state = {
    display: false,
    calling: false,
    input: '',
    messages: [],
  }

  componentDidMount() {
    if (typeof showdown !== 'undefined' && !OtoroshiAssistantMessage.converter) {
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
        '.otoroshi-assistant-message-md p { margin: 0 0 6px 0; }',
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

  clear = () => {
    this.setState({ messages: [] });
  }

  send = () => {
    const input = (this.state.input || '').trim();
    if (!input || this.state.calling) return;
    const newUserMessage = { role: 'user', content: input, date: Date.now() };
    const messages = this.state.messages.concat([newUserMessage]);
    this.setState({ messages, input: '', calling: true });
    const apiMessages = messages.map(m => ({ role: m.role, content: m.content }));
    fetch('/extensions/cloud-apim/extensions/ai-extension/assistant/chat/completions', {
      method: 'POST',
      credentials: 'include',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        messages: apiMessages,
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
        React.createElement('span', { style: { color: 'var(--text-muted)', fontSize: 11 } }, this.state.calling ? 'thinking…' : 'online'),
      ),
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
        : this.state.messages.map((m) => React.createElement(OtoroshiAssistantMessage, {
            key: m.date + '-' + m.role,
            message: m,
          })),
      this.state.calling ? React.createElement(OtoroshiAssistantTyping, { key: 'typing' }) : null,
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
        rows: 1,
        style: {
          flex: 1,
          resize: 'none',
          maxHeight: 120,
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
    return React.createElement('div', {
      className: 'otoroshi-assistant-chatbox',
      style: {
        position: 'fixed',
        bottom: 20,
        right: 20,
        width: 380,
        maxWidth: 'calc(100vw - 40px)',
        height: '70vh',
        maxHeight: 700,
        minHeight: 420,
        display: 'flex',
        flexDirection: 'column',
        backgroundColor: 'var(--bg-color_level2)',
        borderRadius: 12,
        boxShadow: '0 10px 40px rgba(0,0,0,0.35)',
        border: '1px solid var(--border-color)',
        zIndex: 99999,
        animation: 'otoroshi-assistant-fadein 0.18s ease-out',
        overflow: 'hidden',
      }
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
