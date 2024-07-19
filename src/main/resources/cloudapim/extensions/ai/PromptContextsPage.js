class AiContextTester extends Component {
  state = {
    calling: false,
    input: '',
    messages: [],
    provider: null,
  }
  send = () => {
    const input = this.state.input;
    if (input && !this.state.calling) {
      const messages = this.state.messages;
      messages.push({ role: 'user', content: input, date: Date.now() })
      this.setState({ messages: messages, input: '', calling: true })
      fetch('/extensions/cloud-apim/extensions/ai-extension/contexts/_test', {
        method: 'POST',
        credentials: 'include',
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          provider: this.state.provider,
          context: this.props.rawValue.messages,
          role: 'user',
          content: input,
        })
      }).then(r => r.json()).then(r => {
        const messages = this.state.messages;
        if (r.done) {
          r.response.map(m => {
            messages.push({...m.message, date: Date.now()});
          })
          this.setState({messages: messages, calling: false})
          if (this.ref) {
            this.ref.focus();
          }
        } else {
          messages.push({ role: 'error', content: r.error, error: true, date: Date.now() });
          this.setState({messages: messages, calling: false})
          if (this.ref) {
            this.ref.focus();
          }
        }
      }).catch(ex => {
        const messages = this.state.messages;
        messages.push({ role: 'error', content: ex, error: true, date: Date.now() });
        this.setState({ calling: false, messages: messages })
      })
    }
  }
  keydown = (event) => {
    if (event.keyCode === 13) {
      this.send();
    }
  }
  render() {
    return [
      React.createElement(SelectInput, {
        label: 'Provider',
        value: this.state.provider,
        onChange: (provider) => this.setState({ provider: provider }),
        valuesFrom: "/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/providers",
        transformer: (item) => ({ label: item.name, value: item.id }),
      }),
      React.createElement('div', { className: 'row mb-3' },
        React.createElement('label', { className: 'col-xs-12 col-sm-2 col-form-label' }, ''),
        React.createElement('div', { className: 'col-sm-10', style: { display: 'flex' } },
          React.createElement('div', { style: { display: 'flex', width: '100%', flexDirection: 'column' }},
            React.createElement('div', { ref: (r) => this.messagesRef = r, style: {
                  display: 'flex',
                  width: '100%',
                  flexDirection: 'column',
                  border: '1px solid #505050',
                  backgroundColor: '#424242',
                  borderRadius: 5,
                  padding: 3,
                  marginBottom: 10,
                  height: 300,
                  overflowY: 'scroll'
                } },
              this.state.messages.map(message => React.createElement(AiProviderTesterMessage, { key: message.date, message: message })),
            ),
            React.createElement('div', { style: { width: '100%' }, className: 'input-group'},
              React.createElement('input', { ref: (r) => this.ref = r, type: 'text', placeholder: 'Your prompt here', className: 'form-control', value: this.state.input, onKeyDown: this.keydown, onChange: (e) => this.setState({ input: e.target.value }) }),
              React.createElement('button', { type: 'button', className: 'btn btn-sm btn-success', onClick: this.send, disabled: this.state.calling },
                React.createElement('i', { className: 'fas fa-play' }),
                React.createElement('span', null, ' Test'),
              ),
            ),
          )
        )
      )
    ];
  }
}

class PromptContextsPage extends Component {

  formSchema = {
    _loc: {
      type: 'location',
      props: {},
    },
    id: { type: 'string', disabled: true, props: { label: 'Id', placeholder: '---' } },
    name: {
      type: 'string',
      props: { label: 'Name', placeholder: 'My Awesome Context' },
    },
    description: {
      type: 'string',
      props: { label: 'Description', placeholder: 'Description of the Context' },
    },
    metadata: {
      type: 'object',
      props: { label: 'Metadata' },
    },
    'pre_messages': {
      'type': 'jsonobjectcode',
      props: { label: 'Context pre messages' }
    },
    'post_messages': {
      'type': 'jsonobjectcode',
      props: { label: 'Context post messages' }
    },
    tags: {
      type: 'array',
      props: { label: 'Tags' },
    },
    tester: {
      type: AiContextTester,
    }
  };

  columns = [
    {
      title: 'Name',
      filterId: 'name',
      content: (item) => item.name,
    },
    {
      title: 'Description',
      filterId: 'description',
      content: (item) => item.description,
    },
  ];

  formFlow = [
    '_loc',
    'id',
    'name',
    'description',
    'tags',
    'metadata',
    '---',
    'pre_messages',
    'post_messages',
    '>>>Tester',
    'tester',
  ];

  componentDidMount() {
    this.props.setTitle(`AI Prompt Context`);
  }

  client = BackOfficeServices.apisClient('ai-gateway.extensions.cloud-apim.com', 'v1', 'prompt-contexts');

  render() {
    return (
      React.createElement(Table, {
        parentProps: this.props,
        selfUrl: "extensions/cloud-apim/ai-gateway/contexts",
        defaultTitle: "All AI Prompt Contexts",
        defaultValue: () => ({
          id: 'prompt-context_' + uuid(),
          name: 'Prompt Context',
          description: 'A prompt Context',
          tags: [],
          metadata: {},
          messages: [],
        }),
        itemName: "Prompt Context",
        formSchema: this.formSchema,
        formFlow: this.formFlow,
        columns: this.columns,
        stayAfterSave: true,
        fetchItems: (paginationState) => this.client.findAll(),
        updateItem: this.client.update,
        deleteItem: this.client.delete,
        createItem: this.client.create,
        navigateTo: (item) => {
          window.location = `/bo/dashboard/extensions/cloud-apim/ai-gateway/contexts/edit/${item.id}`
        },
        itemUrl: (item) => `/bo/dashboard/extensions/cloud-apim/ai-gateway/contexts/edit/${item.id}`,
        showActions: true,
        showLink: true,
        rowNavigation: true,
        extractKey: (item) => item.id,
        export: true,
        kubernetesKind: "PromptContext"
      }, null)
    );
  }
}