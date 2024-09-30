class AiProviderTesterMessage extends Component {
  componentDidMount() {
    this.ref.scrollIntoView({ behavior: "smooth" })
  }
  render() {
    let content = this.props.message.content;
    if (!(typeof content === 'string' || content instanceof String)) {
      content = JSON.stringify(content);
    }
    return (
      React.createElement('div', { ref: (r) => this.ref = r, style: { display: 'flex', width: '100%', flexDirection: 'row', backgroundColor: '#616060', borderRadius: 3, marginBottom: 3, padding: 5 }},
        React.createElement('div', { style: { width: '20%', fontWeight: 'bold', color: this.props.message.error ? 'red' : 'white' }}, _.capitalize(this.props.message.role)),
        React.createElement('p', { style: { width: '80%', marginBottom: 0 }}, content),
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

class Fence extends Component {
  render() {
    return (
      React.createElement(Form, {
        flow: ['llm', 'gibberish', 'pif', 'moderation'].indexOf(this.props.itemValue.id) > -1 ? ['enabled', 'id', 'before', 'after', 'provider'] : ['enabled', 'id', 'before', 'after', 'config'],
        schema: {
          enabled: { type: 'bool', props: { label: 'Enabled' } },
          before: { type: 'bool', props: { label: 'Apply before' } },
          after: { type: 'bool', props: { label: 'Apply after' } },
          provider: { type: 'select', props: {
            label: 'LLM Provider',
            valuesFrom: '/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/providers',
            transformer: i => ({ label: i.name, value: i.id }),
            value: this.props.itemValue.config.provider,
            overrideOnChange: (provider) => {
              console.log('overrideOnChange', provider)
              this.props.value[this.props.idx].config.provider = provider;
              this.props.onChange(this.props.value)
            },
          } },
          id: {
            type: 'select',
            props: {
              label: 'Fence',
              possibleValues: [
                {label: 'Regex', value: 'regex'},
                {label: 'Webhook', value: 'webhook'},
                {label: 'LLM', value: 'llm'},
                {label: 'No gibberish', value: 'gibberish'},
                {label: 'No personal informations', value: 'pif'},
                {label: 'Language moderation', value: 'moderation'},
                {label: 'Sentences count', value: 'sentences'},
                {label: 'Words count', value: 'words'},
                {label: 'Characters count', value: 'characters'},
                {label: 'Text contains', value: 'contains'},
              ]
            }
          },
          config: { type: 'jsonobjectcode', props: { label: 'Config.', height: '150px' } },
        },
        value: this.props.itemValue,
        onChange: i => {
          const oldId = this.props.value[this.props.idx].id;
          this.props.value[this.props.idx] = i;
          if (oldId !== i.id) {
            if (i.id === 'regex') this.props.value[this.props.idx].config = { deny: [], allow: [] };
            if (i.id === 'webhook') this.props.value[this.props.idx].config = { url: 'https://webhook.foo.bar/path', headers: {}, ttl: 10000 };
            if (i.id === 'llm') this.props.value[this.props.idx].config = { provider: null, prompt: null };
            if (i.id === 'gibberish') this.props.value[this.props.idx].config = { provider: null };
            if (i.id === 'pif') this.props.value[this.props.idx].config = { provider: null };
            if (i.id === 'moderation') this.props.value[this.props.idx].config = { provider: null };
            if (['llm', 'gibberish', 'pif', 'moderation'].indexOf(i.id) > -1) {
              fetch("/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/providers", {
                "headers": {
                  "accept": "application/json",
                },
                "method": "GET",
                "credentials": "include"
              }).then(r => r.json()).then(providers => {
                if (providers.length > 1) {
                  this.props.value[this.props.idx].config.provider = providers[0].id;
                  this.props.onChange(this.props.value)
                }
              });
            }
            if (i.id === 'sentences') this.props.value[this.props.idx].config = { min: 1, max: 3 };
            if (i.id === 'words') this.props.value[this.props.idx].config = { min: 10, max: 30 };
            if (i.id === 'characters') this.props.value[this.props.idx].config = { min: 20, max: 300 };
            if (i.id === 'contains') this.props.value[this.props.idx].config = { operation: 'contains_all', values: [] };
          }
          this.props.onChange(this.props.value)
        }
      }, null)
    )
  }
}

class AiProvidersPage extends Component {

  state = {}

  providerModels = (provider) => {
    if (provider === "openai") {
      return {
        'type': 'select',
        props: { label: 'Description', possibleValues: [
            { label: 'gpt-4o', value: 'gpt-4o' },
            { label: 'gpt-4o-mini', value: 'gpt-4o-mini' },
            { label: 'gpt-4-turbo-preview', value: 'gpt-4-turbo-preview' },
            { label: 'gpt-4-turbo', value: 'gpt-4-turbo' },
            { label: 'gpt-4', value: 'gpt-4' },
            { label: 'gpt-3.5-turbo', value: 'gpt-3.5-turbo' },
            { label: 'o1-preview', value: 'o1-preview' },
            { label: 'o1-mini', value: 'o1-mini' },
          ] }
      }
    } else if (provider === "anthropic") {
      return {
        'type': 'select',
        props: { label: 'Description', possibleValues: [
            { label: "google/gemma-2-2b-it", value: "google/gemma-2-2b-it" },
            { label: "bigcode/starcoder", value: "bigcode/starcoder" },
            { label: "meta-llama/Meta-Llama-3.1-8B-Instruct", value: "meta-llama/Meta-Llama-3.1-8B-Instruct" },
            { label: "microsoft/Phi-3-mini-4k-instruct", value: "microsoft/Phi-3-mini-4k-instruct" },
            { label: "HuggingFaceH4/starchat2-15b-v0.1", value: "HuggingFaceH4/starchat2-15b-v0.1" },
            { label: "mistralai/Mistral-Nemo-Instruct-2407", value: "mistralai/Mistral-Nemo-Instruct-2407" },
          ] }
      }
    } else if (provider === "huggingface") {
       return {
         'type': 'select',
         props: { label: 'Description', possibleValues: [
             { label: "claude-3-5-sonnet-20240620", value: "claude-3-5-sonnet-20240620" },
             { label: "claude-3-opus-20240229", value: "claude-3-opus-20240229" },
             { label: "claude-3-sonnet-20240229", value: "claude-3-sonnet-20240229" },
             { label: "claude-3-haiku-20240307", value: "claude-3-haiku-20240307" },
           ] }
       }
     } else if (provider === "mistral") {
      return {
        'type': 'select',
        props: { label: 'Description', possibleValues: [
            { label: "open-mistral-7b", value: "open-mistral-7b" },
            { label: "open-mixtral-8x7b", value: "open-mixtral-8x7b" },
            { label: "open-mixtral-8x22b", value: "open-mixtral-8x22b" },
            { label: "open-codestral-mamba", value: "open-codestral-mamba" },
            { label: "mistral-embed", value: "mistral-embed" },
            { label: "codestral-latest", value: "codestral-latest" },
            { label: "open-mistral-nemo", value: "open-mistral-nemo" },
            { label: "mistral-large-latest", value: "mistral-large-latest" },
            { label: "mistral-small-latest", value: "mistral-small-latest" },
            { label: "mistral-medium-latest", value: "mistral-medium-latest" },
            { label: "mistral-large-latest", value: "mistral-large-latest" },
          ] }
      }
    } else if (provider === "ovh-ai-endpoints") {
      return {
        'type': 'select',
        props: { label: 'Description', possibleValues: [
            { 'label': "CodeLlama-13b-Instruct-hf", value: 'CodeLlama-13b-Instruct-hf' },
            { 'label': "Mixtral-8x7B-Instruct-v0.1", value: 'Mixtral-8x7B-Instruct-v0.1' },
            { 'label': "Meta-Llama-3-70B-Instruct", value: 'Meta-Llama-3-70B-Instruct' },
            { 'label': "Llama-2-13b-chat-hf", value: 'Llama-2-13b-chat-hf' },
            { 'label': "Mixtral-8x22B-Instruct-v0.1", value: 'Mixtral-8x22B-Instruct-v0.1' },
            { 'label': "Mistral-7B-Instruct-v0.2", value: 'Mistral-7B-Instruct-v0.2' },
            { 'label': "Meta-Llama-3-8B-Instruct", value: 'Meta-Llama-3-8B-Instruct' },
          ] }
      }
    } else if (provider === "hugging-face") {
      return {
        'type': 'select',
        props: { label: 'Description', possibleValues: [
            { 'label': "tiiuae/falcon-7b-instruct", value: 'tiiuae/falcon-7b-instruct' },
          ] }
      }
    } else {
      return {
        type: 'string',
        props: { label: 'Model' },
      }
    }
  }

  formSchema = (state) => ({
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
          { 'label': 'Groq', value: 'groq' },
          { 'label': 'Hugging Face', value: 'hugging-face' },
          { 'label': 'OVH AI Endpoints', value: 'ovh-ai-endpoints' },
          { 'label': 'Huggingface', value: 'huggingface' },
          { 'label': 'Loadbalancer', value: 'loadbalancer' },
        ] }
    },
    'connection.model_name': {
      type: 'string',
      props: { label: 'Model name' },
    },
    'connection.account_id': {
      type: 'string',
      props: { label: 'Account ID' },
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
    'connection.base_domain': {
      type: 'string',
      props: { label: 'Base domain' },
    },
    'connection.token': {
      type: 'string',
      props: { label: 'API Token' },
    },
    'connection.timeout': {
      type: 'number',
      props: { label: 'Timeout', suffix: 'ms.' },
    },
    'options.model': this.providerModels(state.provider || 'none'),
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
    'cache.score': {
      type: 'number',
      props: { label: 'Score', min: 0.1, max: 0.1, step: 0.1 },
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
    'options.refs': {
      type: 'array',
      props: {
        label: 'Providers',
        placeholder: 'Select a provider',
        valuesFrom: '/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/providers',
        transformer: (a) => ({
          value: a.id,
          label: a.name,
        }),
      }
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
    },
    _fences: {
      type: 'jsonobjectcode',
      props: { label: 'Fences config.' }
    },
    fences: {
      type: 'array',
      props: {
        label: '',
        defaultValue: {
          enabled: true,
          before: true,
          after: true,
          id: 'regex',
          config: {
            allow: [],
            deny: [],
          }
        },
        component: Fence
      }
    }
  });

  columns = [
    {
      title: 'Name',
      filterId: 'name',
      content: (item) => item.name,
    },
    { title: 'Provider', filterId: 'provider', content: (item) => item.provider },
  ];

  formFlow = (state) => {
    state.cache = state.cache || 'none'
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
        state.cache.strategy === 'semantic' ? 'cache.score' : null,
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
        '<<<Providers',
        'options.refs',
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
        state.cache.strategy === 'semantic' ? 'cache.score' : null,
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
        state.cache.strategy === 'semantic' ? 'cache.score' : null,
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
    if (state.provider === "cohere") {
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
        'options.k',
        'options.p',
        '>>>Provider fallback',
        'provider_fallback',
        '>>>Cache',
        'cache.strategy',
        'cache.ttl',
        state.cache.strategy === 'semantic' ? 'cache.score' : null,
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
        state.cache.strategy === 'semantic' ? 'cache.score' : null,
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
    if (state.provider === "groq") {
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
        'options.n',
        '>>>Provider fallback',
        'provider_fallback',
        '>>>Cache',
        'cache.strategy',
        'cache.ttl',
        state.cache.strategy === 'semantic' ? 'cache.score' : null,
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
    if (state.provider === "gemini") {
      return [
        '_loc', 'id', 'name', 'description',
        '<<<Provider',
        'provider',
        '<<<API Connection',
        'connection.model',
        'connection.token',
        'connection.timeout',
        '<<<Connection options',
        'options.maxOutputTokens',
        'options.temperature',
        'options.topP',
        'options.topK',
        'options.stopSequences',
        '>>>Provider fallback',
        'provider_fallback',
        '>>>Cache',
        'cache.strategy',
        'cache.ttl',
        state.cache.strategy === 'semantic' ? 'cache.score' : null,
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
        state.cache.strategy === 'semantic' ? 'cache.score' : null,
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
    if (state.provider === "ovh-ai-endpoints") {
      return [
        '_loc', 'id', 'name', 'description',
        '<<<Provider',
        'provider',
        '<<<API Connection',
        'connection.base_domain',
        'connection.token',
        'connection.timeout',
        '<<<Connection options',
        'options.model',
        'options.max_tokens',
        'options.temperature',
        'options.topP',
        'options.seed',
        '>>>Provider fallback',
        'provider_fallback',
        '>>>Cache',
        'cache.strategy',
        'cache.ttl',
        state.cache.strategy === 'semantic' ? 'cache.score' : null,
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
    if (state.provider === "cloudflare") {
      return [
        '_loc', 'id', 'name', 'description',
        '<<<Provider',
        'provider',
        '<<<API Connection',
        'connection.token',
        'connection.timeout',
        '<<<Cloudflare options',
        'connection.account_id',
        'connection.model_name',
        '<<<Connection options',
        'options.max_tokens',
        'options.temperature',
        'options.topK',
        'options.topP',
        '>>>Provider fallback',
        'provider_fallback',
        '>>>Cache',
        'cache.strategy',
        'cache.ttl',
        state.cache.strategy === 'semantic' ? 'cache.score' : null,
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
    if (state.provider === "huggingface") {
      return [
        '_loc', 'id', 'name', 'description',
        '<<<Provider',
        'provider',
        '<<<API Connection',
        'connection.model_name',
        'connection.token',
        'connection.timeout',
        '<<<Connection options',
        'options.max_tokens',
        'options.temperature',
        'options.top_p',
        'options.seed',
        '>>>Provider fallback',
        'provider_fallback',
        '>>>Cache',
        'cache.strategy',
        'cache.ttl',
        state.cache.strategy === 'semantic' ? 'cache.score' : null,
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
      state.cache.strategy === 'semantic' ? 'cache.score' : null,
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
      '>>>Fences validation',
      'fences',
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
    const formSchema = this.formSchema(this.state || {})
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
            cache: {
              strategy: 'none',
              ttl: 5 * 60 * 1000,
              score: 0.8
            }
          }
        },
        itemName: "Provider",
        formSchema: formSchema,
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
          this.setState(state)
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
            } else if (state.provider === 'groq') {
              update({
                id: state.id,
                name: state.name,
                description: state.description,
                tags: state.tags,
                metadata: state.metadata,
                provider: 'groq',
                connection: {
                  base_url: BaseUrls.groq,
                  timeout: 30000,
                },
                options: ClientOptions.groq,
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
            }else if (state.provider === 'cohere') {
              update({
                id: state.id,
                name: state.name,
                description: state.description,
                tags: state.tags,
                metadata: state.metadata,
                provider: 'cohere',
                connection: {
                  base_url: BaseUrls.cohere,
                  token: 'xxx',
                  timeout: 10000,
                },
                options: ClientOptions.cohere,
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
            } else if (state.provider === 'ovh-ai-endpoints') {
              update({
                id: state.id,
                name: state.name,
                description: state.description,
                tags: state.tags,
                metadata: state.metadata,
                provider: 'ovh-ai-endpoints',
                connection: {
                  base_domain: BaseUrls.ovh,
                  token: 'xxx',
                  timeout: 10000,
                },
                options: ClientOptions.ovh,
              });
            } else if (state.provider === 'hugging-face') {
              update({
                id: state.id,
                name: state.name,
                description: state.description,
                tags: state.tags,
                metadata: state.metadata,
                provider: 'hugging-face',
                connection: {
                  token: 'xxx',
                  timeout: 10000,
                },
                options: ClientOptions.hugging,
              });
            } else if (state.provider === 'gemini') {
              update({
                id: state.id,
                name: state.name,
                description: state.description,
                tags: state.tags,
                metadata: state.metadata,
                provider: 'gemini',
                connection: {
                  base_url: BaseUrls.gemini,
                  model: 'model name',
                  token: 'xxx',
                  timeout: 10000,
                },
                options: ClientOptions.gemini,
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
            } else if (state.provider === 'cloudflare') {
              update({
                id: state.id,
                name: state.name,
                description: state.description,
                tags: state.tags,
                metadata: state.metadata,
                provider: 'cloudflare',
                connection: {
                  account_id: "YOUR ACCOUNT ID",
                  model_name: "@cf/meta/llama-3.1-8b-instruct-fp8",
                  token: 'xxx',
                  timeout: 10000,
                },
                options: ClientOptions.azureOpenai,
              });
            }
            else if (state.provider === 'huggingface') {
              update({
                id: state.id,
                name: state.name,
                description: state.description,
                tags: state.tags,
                metadata: state.metadata,
                provider: 'huggingface',
                connection: {
                  model_name: "google/gemma-2-2b-it:",
                  token: 'xxx',
                  timeout: 10000,
                },
                options: ClientOptions.huggingface,
              });
            }
          }
        }
      }, null)
    );
  }
}