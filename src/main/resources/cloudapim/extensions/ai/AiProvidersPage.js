class AiProviderTesterMessage extends Component {
  componentDidMount() {
    this.ref.scrollIntoView({ behavior: "smooth" })
  }
  render() {
    return (
      React.createElement('div', { ref: (r) => this.ref = r, style: { display: 'flex', width: '100%', flexDirection: 'row', backgroundColor: '#616060', borderRadius: 3, marginBottom: 3, padding: 5 }},
        React.createElement('div', { style: { width: '20%', fontWeight: 'bold', color: this.props.message.error ? 'red' : 'white' }}, _.capitalize(this.props.message.role)),
        React.createElement('p', { style: { width: '80%', marginBottom: 0 }}, this.props.message.content),
      )
    );
  }
}
class AiProviderTester extends Component {
  state = {
    calling: false,
    input: '',
    messages: [],
  }
  send = () => {
    const input = this.state.input;
    if (input && !this.state.calling) {
      const messages = this.state.messages;
      messages.push({ role: 'user', content: input, date: Date.now() })
      this.setState({ messages: messages, input: '', calling: true })
      fetch('/extensions/cloud-apim/extensions/ai-extension/providers/_test', {
        method: 'POST',
        credentials: 'include',
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          provider: this.props.rawValue.id,
          role: 'user',
          content: input,
          edited: this.props.rawValue,
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

class AiProvidersPage extends Component {

  formSchema = {
    _loc: {
      type: 'location',
      props: {},
    },
    id: { type: 'string', disabled: true, props: { label: 'Id', placeholder: '---' } },
    name: {
      type: 'string',
      props: { label: 'Name', placeholder: 'My Awesome Provider' },
    },
    description: {
      type: 'string',
      props: { label: 'Description', placeholder: 'Description of the Provider' },
    },
    metadata: {
      type: 'object',
      props: { label: 'Metadata' },
    },
    'provider': {
      'type': 'select',
      props: { label: 'Description', possibleValues: [
          { 'label': 'OpenAI', value: 'openai' },
          { 'label': 'Azure OpenAI', value: 'azure-openai' },
          { 'label': 'Mistral', value: 'mistral' },
          { 'label': 'Ollama', value: 'ollama' },
          { 'label': 'Anthropic', value: 'anthropic' },
          { 'label': 'Loadbalancer', value: 'loadbalancer' },
        ] }
    },
    'connection.resource_name': {
      type: 'string',
      props: { label: 'Resource name' },
    },
    'connection.deployment_id': {
      type: 'string',
      props: { label: 'Deployment id of your model' },
    },
    'connection.api_key': {
      type: 'string',
      props: { label: 'Apikey' },
    },
    'connection.base_url': {
      type: 'string',
      props: { label: 'Base URL' },
    },
    'connection.token': {
      type: 'string',
      props: { label: 'API Token' },
    },
    'connection.timeout': {
      type: 'number',
      props: { label: 'Timeout', suffix: 'ms.' },
    },
    'options.model': {
      type: 'string',
      props: { label: 'Model' },
    },
    'options.max_tokens': {
      type: 'string',
      props: { label: 'Max. tokens' },
    },
    'options.temperature': {
      type: 'number',
      props: { label: 'Temperature', step: "0.01" },
    },
    'options.topP': {
      type: 'number',
      props: { label: 'Top P', step: "0.01" },
    },
    'options.n': {
      type: 'number',
      props: { label: 'N' },
    },
    tags: {
      type: 'array',
      props: { label: 'Tags' },
    },
    tester: {
      type: AiProviderTester,
    },
    'options.random_seed': {
      type: 'number',
      props: { label: 'Random seed' },
    },
    'options.safe_prompt': {
      type: 'bool',
      props: { label: 'Safe prompt' },
    },
    'options.top_p': {
      type: 'number',
      props: { label: 'Top P', step: "0.01" },
    },
    'options.num_predict': {
      type: 'number',
      props: { label: 'Max token number' },
    },
    'options.tfs_z': {
      type: 'number',
      props: { label: 'Tail free sampling', step: "0.01" },
    },
    'options.seed': {
      type: 'number',
      props: { label: 'Seed' },
    },
    'options.top_k': {
      type: 'number',
      props: { label: 'Top K' },
    },
    'options.repeat_penalty': {
      type: 'number',
      props: { label: 'Repeat penalty', step: "0.01" },
    },
    'options.repeat_last_n': {
      type: 'number',
      props: { label: 'Loop back', step: "0.01" },
    },
    'options.num_thread': {
      type: 'number',
      props: { label: 'Number of thread' },
    },
    'options.num_gpu': {
      type: 'number',
      props: { label: 'Number of GPU layers' },
    },
    'options.num_gqa': {
      type: 'number',
      props: { label: 'Number of GQA groups' },
    },
    'options.num_ctx': {
      type: 'number',
      props: { label: 'Context size' },
    },
    'regex_validation.deny': {
      type: 'array',
      props: { label: 'Deny', suffix: 'regex' },
    },
    'regex_validation.allow': {
      type: 'array',
      props: { label: 'Allow', suffix: 'regex' },
    },
    'http_validation.url': {
      type: 'string',
      props: { label: 'URL' },
    },
    'http_validation.headers': {
      type: 'object',
      props: { label: 'Headers' },
    },
    'http_validation.ttl': {
      type: 'object',
      props: { label: 'TTL', suffix: 'millis.' },
    },
    'cache.ttl': {
      type: 'number',
      props: { label: 'TTL', suffix: 'millis.' },
    },
    'cache.strategy': {
      type: 'select',
      props: { label: 'Cache strategy', possibleValues: [
          { label: 'None', value: 'none' },
          { label: 'Simple', value: 'simple' },
          { label: 'Semantic', value: 'semantic' },
      ] },
    },
    'options.loadbalancing': {
      type: 'select',
      props: {
        label: 'Load Balancing strategy',
        possibleValues: [
          { label: 'Round robin', value: 'round_robin' },
          { label: 'Random', value: 'random' },
          { label: 'Best response time', value: 'best_response_time' },
        ]
      },
    },
    'options.ratio': {
      type: 'number',
      props: { label: 'TTL', suffix: 'millis.' },
    },
    'llm_validation.provider': {
      type: 'select',
      props: {
        label: 'Validator provider',
        placeholder: 'Select a validator provider',
        isClearable: true,
        valuesFrom: '/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/providers',
        transformer: (a) => ({
          value: a.id,
          label: a.name,
        }),
      }
    },
    'provider_fallback': {
      type: 'select',
      props: {
        label: 'Provider fallback provider',
        placeholder: 'Select a fallback',
        isClearable: true,
        valuesFrom: '/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/providers',
        transformer: (a) => ({
          value: a.id,
          label: a.name,
        }),
      }
    },
    'llm_validation.prompt': {
      type: 'select',
      props: {
        label: 'Validator prompt',
        placeholder: 'Select a validator prompt',
        isClearable: true,
        valuesFrom: '/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/prompts',
        transformer: (a) => ({
          value: a.id,
          label: a.name,
        }),
      }
    }
  };

  columns = [
    {
      title: 'Name',
      filterId: 'name',
      content: (item) => item.name,
    },
    { title: 'Provider', filterId: 'provider', content: (item) => item.provider },
  ];

  formFlow = (state) => {
    if (!state.provider) {
      return [
        '_loc',
        'id',
        'name',
        'description',
        '<<<Provider',
        'provider',
        '>>>Provider fallback',
        'provider_fallback',
        '>>>Cache',
        'cache.strategy',
        'cache.ttl',
        '>>>Regex validation',
        'regex_validation.allow',
        'regex_validation.deny',
        '>>>LLM Based validation',
        'llm_validation.provider',
        'llm_validation.prompt',
        '>>>External validation',
        'http_validation.url',
        'http_validation.headers',
        'http_validation.ttl',
        '>>>Metadata and tags',
        'tags',
        'metadata',
      ]
    }
    if (state.provider === "loadbalancer") {
      return [
        '_loc', 'id', 'name', 'description',
        '<<<Provider',
        'provider',
        '<<<Load balancing',
        'options.loadbalancing',
        'options.ratio',
        '>>>Tester',
        'tester',
        '>>>Metadata and tags',
        'tags',
        'metadata',
      ];
    }
    if (state.provider === "ollama") {
      return [
        '_loc', 'id', 'name', 'description',
        '<<<Provider',
        'provider',
        '<<<API Connection',
        'connection.base_url',
        'connection.timeout',
        '<<<Connection options',
        'options.model',
        'options.num_predict',
        'options.tfs_z',
        'options.seed',
        'options.temperature',
        'options.top_p',
        'options.top_k',
        'options.repeat_penalty',
        'options.repeat_last_n',
        'options.num_thread',
        'options.num_gpu',
        'options.num_gqa',
        'options.num_ctx',
        '>>>Provider fallback',
        'provider_fallback',
        '>>>Cache',
        'cache.strategy',
        'cache.ttl',
        '>>>Regex validation',
        'regex_validation.allow',
        'regex_validation.deny',
        '>>>LLM Based validation',
        'llm_validation.provider',
        'llm_validation.prompt',
        '>>>External validation',
        'http_validation.url',
        'http_validation.headers',
        'http_validation.ttl',
        '>>>Tester',
        'tester',
        '>>>Metadata and tags',
        'tags',
        'metadata',
      ];
    }
    if (state.provider === "mistral") {
      return [
        '_loc', 'id', 'name', 'description',
        '<<<Provider',
        'provider',
        '<<<API Connection',
        'connection.base_url',
        'connection.token',
        'connection.timeout',
        '<<<Connection options',
        'options.model',
        'options.max_tokens',
        'options.random_seed',
        'options.safe_prompt',
        'options.temperature',
        'options.top_p',
        '>>>Provider fallback',
        'provider_fallback',
        '>>>Cache',
        'cache.strategy',
        'cache.ttl',
        '>>>Regex validation',
        'regex_validation.allow',
        'regex_validation.deny',
        '>>>LLM Based validation',
        'llm_validation.provider',
        'llm_validation.prompt',
        '>>>External validation',
        'http_validation.url',
        'http_validation.headers',
        'http_validation.ttl',
        '>>>Tester',
        'tester',
        '>>>Metadata and tags',
        'tags',
        'metadata',
      ];
    }
    if (state.provider === "anthropic") {
      return [
        '_loc', 'id', 'name', 'description',
        '<<<Provider',
        'provider',
        '<<<API Connection',
        'connection.base_url',
        'connection.token',
        'connection.timeout',
        '<<<Connection options',
        'options.model',
        'options.max_tokens',
        'options.temperature',
        'options.top_p',
        'options.top_k',
        '>>>Provider fallback',
        'provider_fallback',
        '>>>Cache',
        'cache.strategy',
        'cache.ttl',
        '>>>Regex validation',
        'regex_validation.allow',
        'regex_validation.deny',
        '>>>LLM Based validation',
        'llm_validation.provider',
        'llm_validation.prompt',
        '>>>External validation',
        'http_validation.url',
        'http_validation.headers',
        'http_validation.ttl',
        '>>>Tester',
        'tester',
        '>>>Metadata and tags',
        'tags',
        'metadata',
      ];
    }
    if (state.provider === "azure-openai") {
      return [
        '_loc', 'id', 'name', 'description',
        '<<<Provider',
        'provider',
        '<<<API Connection',
        'connection.resource_name',
        'connection.deployment_id',
        'connection.api_key',
        'connection.timeout',
        '<<<Connection options',
        'options.max_tokens',
        'options.n',
        'options.temperature',
        'options.topP',
        '>>>Provider fallback',
        'provider_fallback',
        '>>>Cache',
        'cache.strategy',
        'cache.ttl',
        '>>>Regex validation',
        'regex_validation.allow',
        'regex_validation.deny',
        '>>>LLM Based validation',
        'llm_validation.provider',
        'llm_validation.prompt',
        '>>>External validation',
        'http_validation.url',
        'http_validation.headers',
        'http_validation.ttl',
        '>>>Tester',
        'tester',
        '>>>Metadata and tags',
        'tags',
        'metadata',
      ];
    }
    return [
      '_loc', 'id', 'name', 'description',
      '<<<Provider',
      'provider',
      '<<<API Connection',
      'connection.base_url',
      'connection.token',
      'connection.timeout',
      '<<<Connection options',
      'options.model',
      'options.max_tokens',
      'options.n',
      'options.temperature',
      'options.topP',
      '>>>Provider fallback',
      'provider_fallback',
      '>>>Cache',
      'cache.strategy',
      'cache.ttl',
      '>>>Regex validation',
      'regex_validation.allow',
      'regex_validation.deny',
      '>>>LLM Based validation',
      'llm_validation.provider',
      'llm_validation.prompt',
      '>>>External validation',
      'http_validation.url',
      'http_validation.headers',
      'http_validation.ttl',
      '>>>Tester',
      'tester',
      '>>>Metadata and tags',
      'tags',
      'metadata',
    ];
  }

  componentDidMount() {
    this.props.setTitle(`LLM Providers`);
  }

  client = BackOfficeServices.apisClient('ai-gateway.extensions.cloud-apim.com', 'v1', 'providers');

  render() {
    return (
      React.createElement(Table, {
        parentProps: this.props,
        selfUrl: "extensions/cloud-apim/ai-gateway/providers",
        defaultTitle: "All LLM Providers",
        defaultValue: () => {
          return {
            id: 'provider_' + uuid(),
            name: 'OpenAI provider',
            description: 'An OpenAI LLM api provider',
            tags: [],
            metadata: {},
            provider: 'openai',
            connection: {
              base_url: BaseUrls.openai,
              token: 'xxxx',
              timeout: 10000,
            },
            options: ClientOptions.openai,
          }
        },
        itemName: "Provider",
        formSchema: this.formSchema,
        formFlow: this.formFlow,
        columns: this.columns,
        stayAfterSave: true,
        fetchItems: (paginationState) => this.client.findAll(),
        updateItem: this.client.update,
        deleteItem: this.client.delete,
        createItem: this.client.create,
        navigateTo: (item) => {
          window.location = `/bo/dashboard/extensions/cloud-apim/ai-gateway/providers/edit/${item.id}`
        },
        itemUrl: (item) => `/bo/dashboard/extensions/cloud-apim/ai-gateway/providers/edit/${item.id}`,
        showActions: true,
        showLink: true,
        rowNavigation: true,
        extractKey: (item) => item.id,
        export: true,
        kubernetesKind: "Provider",
        onStateChange: (state, oldState, update) => {
          if (!_.isEqual(state.provider, oldState.provider)) {
            console.log("set default value")
            if (state.provider === 'ollama') {
              update({
                id: state.id,
                name: state.name,
                description: state.description,
                tags: state.tags,
                metadata: state.metadata,
                provider: 'ollama',
                connection: {
                  base_url: BaseUrls.ollama,
                  timeout: 30000,
                },
                options: ClientOptions.ollama,
              });
            } else if (state.provider === 'anthropic') {
              update({
                id: state.id,
                name: state.name,
                description: state.description,
                tags: state.tags,
                metadata: state.metadata,
                provider: 'ollama',
                connection: {
                  base_url: BaseUrls.anthropic,
                  timeout: 30000,
                },
                options: ClientOptions.anthropic,
              });
            } else if (state.provider === 'mistral') {
              update({
                id: state.id,
                name: state.name,
                description: state.description,
                tags: state.tags,
                metadata: state.metadata,
                provider: 'mistral',
                connection: {
                  base_url: BaseUrls.mistral,
                  token: 'xxx',
                  timeout: 10000,
                },
                options: ClientOptions.mistral,
              });
            } else if (state.provider === 'openai') {
              update({
                id: state.id,
                name: state.name,
                description: state.description,
                tags: state.tags,
                metadata: state.metadata,
                provider: 'openai',
                connection: {
                  base_url: BaseUrls.openai,
                  token: 'xxx',
                  timeout: 10000,
                },
                options: ClientOptions.openai,
              });
            } else if (state.provider === 'azure-openai') {
              update({
                id: state.id,
                name: state.name,
                description: state.description,
                tags: state.tags,
                metadata: state.metadata,
                provider: 'azure-openai',
                connection: {
                  resource_name: "resource name",
                  deployment_id: "model id",
                  api_key: 'xxx',
                  timeout: 10000,
                },
                options: ClientOptions.azureOpenai,
              });
            }
          }
        }
      }, null)
    );
  }
}