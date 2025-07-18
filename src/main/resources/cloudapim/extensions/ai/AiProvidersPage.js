class AiProviderTesterMessage extends Component {
  componentDidMount() {
    this.ref.scrollIntoView({ behavior: "smooth" })
  }
  deleteMessage = () => {
    this.props.deleteMessage();
  }
  render() {
    let content = this.props.message.content;
    if (!(typeof content === 'string' || content instanceof String)) {
      content = JSON.stringify(content);
    }
    return (
      React.createElement('div', { ref: (r) => this.ref = r, style: { display: 'flex', width: '100%', flexDirection: 'row', backgroundColor: '#616060', borderRadius: 3, marginBottom: 3, padding: 5 }},
        React.createElement('div', { style: { width: '20%', fontWeight: 'bold', color: this.props.message.error ? 'red' : 'white' }}, _.capitalize(this.props.message.role)),
        !AiProviderTesterMessage.converter ? React.createElement('p', { style: { width: '80%', marginBottom: 0 }}, content) : null,
        AiProviderTesterMessage.converter ? React.createElement('p', { style: { width: '80%', marginBottom: 0 }, dangerouslySetInnerHTML: { __html: AiProviderTesterMessage.converter.makeHtml(content) }}) : null,
        React.createElement(
          'button',
          {
            type: 'button',
            __disabled: this.props.message.role !== 'user',
            className: 'btn btn-sm btn-danger',
            onClick: this.deleteMessage,
          },
          React.createElement('i', { className: 'fas fa-trash' })
        ),
      )
    );
  }
}

class ModelsReloadButton extends Component {
  render() {
    return React.createElement('div', { className: 'row mb-3' },
      React.createElement('label', { className: 'col-xs-12 col-sm-2 col-form-label' }, ''),
      React.createElement('div', { className: 'col-sm-10', style: { display: 'flex' } },
        React.createElement('button', {
          className: 'btn btn-sm btn-success',
          type: 'button',
          onClick: (e) => this.props.fetchModels(this.props.provider, true)
        },  React.createElement('i', { className: 'fas fa-sync' }, null), ' models')
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
  componentDidMount() {
    if (showdown && !AiProviderTesterMessage.converter) {
      AiProviderTesterMessage.converter = new showdown.Converter({
        omitExtraWLInCodeBlocks: true,
        ghCompatibleHeaderId: true,
        parseImgDimensions: true,
        simplifiedAutoLink: true,
        tables: true,
        tasklists: true,
        requireSpaceBeforeHeadingText: true,
        ghMentions: true,
        emoji: true,
        ghMentionsLink: "/{u}",
        flavor: "github",
      });
    }
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
          history: this.state.messages.slice(-64),
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
  deleteMessage = (idx) => {
    this.state.messages.splice(idx, 1);
    this.setState({ messages: this.state.messages });
  }
  clear = () => {
    this.setState({ messages: [] });
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
              this.state.messages.map((message, idx) => {
                return React.createElement(AiProviderTesterMessage, {
                  key: message.date,
                  message: message,
                  deleteMessage: () => {
                    this.deleteMessage(idx)
                  }
                })
              }),
            ),
            React.createElement('div', { style: { width: '100%' }, className: 'input-group'},
              React.createElement('input', { ref: (r) => this.ref = r, type: 'text', placeholder: 'Your prompt here', className: 'form-control', value: this.state.input, onKeyDown: this.keydown, onChange: (e) => this.setState({ input: e.target.value }) }),
              React.createElement('button', { type: 'button', className: 'btn btn-sm btn-success', onClick: this.send, disabled: this.state.calling },
                React.createElement('i', { className: 'fas fa-play' }),
                React.createElement('span', null, ' Test'),
              ),
              React.createElement('button', { type: 'button', className: 'btn btn-sm btn-danger', onClick: this.clear, disabled: this.state.messages.length === 0 },
                React.createElement('i', { className: 'fas fa-trash' }),
                React.createElement('span', null, ' Clear'),
              ),
            ),
          )
        )
      )
    ];
  }
}

class Guardrail extends Component {
  flow = (id) => {
    const def = ['enabled', 'id', 'before', 'after'];
    const tail = []; // ['config'];
    if (id === 'regex') return [...def, 'config.deny', 'config.allow', ...tail];
    if (id === 'webhook') return [...def, 'config.url', 'config.headers', 'config.ttl', ...tail];
    if (id === 'llm') return [...def, 'config.provider', 'config.prompt', ...tail];
    if (id === 'gibberish') return [...def, 'config.provider', ...tail];
    if (id === 'pif') return [...def, 'config.provider', 'config.pif_items', ...tail];
    if (id === 'moderation') return [...def, 'config.provider', 'config.moderation_items', ...tail];
    if (id === 'secrets_leakage') return [...def, 'config.provider', 'config.secrets_leakage_items', ...tail];
    if (id === 'auto_secrets_leakage') return [...def, 'config.provider', ...tail];
    if (id === 'sentences') return [...def, 'config.min', 'config.max', ...tail];
    if (id === 'words') return [...def, 'config.min', 'config.max', ...tail];
    if (id === 'characters') return [...def, 'config.min', 'config.max', ...tail];
    if (id === 'contains') return [...def, 'config.operation', 'config.values', ...tail];
    if (id === 'semantic_contains') return [...def, 'config.operation', 'config.values', 'config.score', ...tail];
    if (id === 'toxic_language') return [...def, 'config.provider', ...tail];
    if (id === 'racial_bias') return [...def, 'config.provider', ...tail];
    if (id === 'gender_bias') return [...def, 'config.provider', ...tail];
    if (id === 'personal_health_information') return [...def, 'config.provider', ...tail];
    if (id === 'prompt_injection') return [...def, 'config.provider', 'config.max_injection_score', ...tail];
    if (id === 'wasm') return [...def, 'config.plugin_ref', ...tail];
    if (id === 'quickjs') return [...def, 'config.quickjs_path', ...tail];
    if (id === 'moderation_model') return [...def, 'config.moderation_model', ...tail];
    return [...def, ...tail];
  }
  render() {
    //console.log(this.props.value, this.props.idx, this.props.value[this.props.idx].id);
    const flow = this.flow(this.props.value[this.props.idx].id);
    //console.log(flow);
    return (
      React.createElement(Form, {
        flow: flow, //['llm', 'gibberish', 'pif', 'moderation'].indexOf(this.props.itemValue.id) > -1 ? ['enabled', 'id', 'before', 'after', 'provider'] : ['enabled', 'id', 'before', 'after', 'config'],
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
              label: 'Guardrail',
              possibleValues: _.sortBy([
                {label: 'Regex', value: 'regex'},
                {label: 'Webhook', value: 'webhook'},
                {label: 'LLM', value: 'llm'},
                {label: 'Secrets leakage', value: 'secrets_leakage'},
                {label: 'Auto Secrets leakage', value: 'auto_secrets_leakage'},
                {label: 'No gibberish', value: 'gibberish'},
                {label: 'No personal information', value: 'pif'},
                {label: 'Language moderation', value: 'moderation'},
                {label: 'Moderation model', value: 'moderation_model'},
                {label: 'No toxic language', value: 'toxic_language'},
                {label: 'No racial bias', value: 'racial_bias'},
                {label: 'No gender bias', value: 'gender_bias'},
                {label: 'No personal health information', value: 'personal_health_information'},
                {label: 'No prompt injection/prompt jailbreak', value: 'prompt_injection'},
                {label: 'Sentences count', value: 'sentences'},
                {label: 'Words count', value: 'words'},
                {label: 'Characters count', value: 'characters'},
                {label: 'Text contains', value: 'contains'},
                {label: 'Semantic contains', value: 'semantic_contains'},
                {label: 'QuickJS', value: 'quickjs'},
                {label: 'Wasm', value: 'wasm'},
              ], i => i.label)
            }
          },
          config: { type: 'jsonobjectcode', props: { label: 'Config.', height: '150px' } },
          'config.max_injection_score': { type: 'number', props: { label: 'Max injection detection score' } },
          'config.min': { type: 'number', props: { label: 'Minimum' } },
          'config.max': { type: 'number', props: { label: 'Maximum' } },
          'config.deny': { type: 'array', props: { label: 'Denied expressions' } },
          'config.allow': { type: 'array', props: { label: 'Allowed expressions' } },
          'config.values': { type: 'array', props: { label: 'Possible values' } },
          'config.score': { type: 'number', props: { label: 'Match score' } },
          'config.url': { type: 'string', props: { label: 'URL' } },
          'config.headers': { type: 'object', props: { label: 'Headers' } },
          'config.ttl': { type: 'number', props: { label: 'TTL', suffix: 'millis.' } },
          'config.operation': { type: 'select', props: { label: 'Operation', possibleValues: [
            { label: "contains_all", value: 'contains_all' },
            { label: "contains_none", value: 'contains_none' },
            { label: "contains_any", value: 'contains_any' },
          ] } },
          'config.pif_items': { type: 'array', props: { label: 'Personal Informations types', possibleValues: GuardrailsOptions.possiblePersonalInformations.map(i => ({
                label: i, value: i
          })) } },
          'config.moderation_items': { type: 'array', props: { label: 'Moderation types', possibleValues: GuardrailsOptions.possibleModerationCategories.map(i => ({
              label: i, value: i
            })) } },
          'config.secrets_leakage_items': { type: 'array', props: { label: 'Secrets types', possibleValues: GuardrailsOptions.possibleSecretLeakage.map(i => ({
                label: i, value: i
            })) } },
          'config.provider': { type: 'select', props: {
            label: 'LLM Provider',
            placeholder: 'Select a LLM provider',
            valuesFrom: '/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/providers',
            transformer: (a) => ({
              value: a.id,
              label: a.name,
            }),
           } },
          'config.prompt': { type: 'select', props: {
              label: 'LLM Prompt',
              placeholder: 'Select a LLM Prompt',
              valuesFrom: '/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/prompts',
              transformer: (a) => ({
                value: a.id,
                label: a.name,
              }),
            } },
          'config.plugin_ref': { type: 'select', props: {
              label: 'WASM plugin',
              placeholder: 'Select a Wasm plugin',
              valuesFrom: "/bo/api/proxy/apis/plugins.otoroshi.io/v1/wasm-plugins",
              transformer: (item) => ({ label: item.name, value: item.id }),
            } },
          'config.moderation_model': { type: 'select', props: {
              label: 'Moderation model',
              placeholder: 'Select a Moderation model',
              valuesFrom: "/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/moderation-models",
              transformer: (item) => ({ label: item.name, value: item.id }),
            } },
          'config.quickjs_path': {
            type: 'code',
            label: 'QuickJS code path',
          }
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
            if (i.id === 'toxic_language') this.props.value[this.props.idx].config = { provider: null };
            if (i.id === 'racial_bias') this.props.value[this.props.idx].config = { provider: null };
            if (i.id === 'gender_bias') this.props.value[this.props.idx].config = { provider: null };
            if (i.id === 'personal_health_information') this.props.value[this.props.idx].config = { provider: null };
            if (i.id === 'auto_secrets_leakage') this.props.value[this.props.idx].config = { provider: null };
            if (i.id === 'prompt_injection') this.props.value[this.props.idx].config = { provider: null, max_injection_score: 90 };
            if (i.id === 'pif') this.props.value[this.props.idx].config = { provider: null, pif_items: [
                "EMAIL_ADDRESS",
                "PHONE_NUMBER",
                "LOCATION_ADDRESS",
                "NAME",
                "IP_ADDRESS",
                "CREDIT_CARD",
                "SSN",
              ] };
            if (i.id === 'moderation') this.props.value[this.props.idx].config = { provider: null, moderation_items: [
                "hate",
                "hate/threatening",
                "harassment",
                "harassment/threatening",
                "self-harm",
                "self-harm/intent",
                "self-harm/instructions",
                "sexual",
                "sexual/minors",
                "violence",
                "violence/graphic",
              ] };
            if (i.id === 'secrets_leakage') this.props.value[this.props.idx].config = { provider: null, secrets_leakage_items: [
                "APIKEYS",
                "PASSWORDS",
                "TOKENS",
                "JWT_TOKENS",
                "PRIVATE_KEYS",
                "HUGE_RANDOM_VALUES",
              ] };
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
            if (i.id === 'semantic_contains') this.props.value[this.props.idx].config = { operation: 'contains_all', values: [], score: 0.8 };
            if (i.id === 'wasm') this.props.value[this.props.idx].config = { plugin_ref: '' };
            if (i.id === 'quickjs') this.props.value[this.props.idx].config = { quickjs_path: '\'inline module\';\n\nexports.guardrail_call = function(args) {\n  const { messages } = JSON.parse(args);\n  return JSON.stringify({ \n    pass: true, \n    reason: "none" \n  });\n};' };
            if (i.id === 'moderation_model') this.props.value[this.props.idx].config = { ref: '' };
          }
          this.props.onChange(this.props.value)
        }
      }, null)
    )
  }
}

class AiProvidersPage extends Component {

  state = {
    dynamicModels: null
  }

  fetchModels = (provider, force) => {
    fetch(`/extensions/cloud-apim/extensions/ai-extension/providers/_models?force=${!!force}`, {
      method: 'POST',
      credentials: 'include',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(provider)
    }).then(r => {
      if (r.status === 200) {
        r.json().then(body => {
          if (body.done) {
            this.setState({ dynamicModels: body.models })
          }
        })
      }
    })
  }

  providerModels = (provider, s) => {
    if (this.state.dynamicModels === null) {
      this.fetchModels(s);
    }
    if (this.state.dynamicModels && this.state.dynamicModels.length > 0) {
      return {
        'type': 'select',
        props: { label: 'Model', possibleValues: this.state.dynamicModels.map(mod => ({ label: mod, value: mod })) }
      }
    }
    if (provider === "openai") {
      return {
        'type': 'select',
        props: { label: 'Model', possibleValues: [
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
    } else if (provider === "x-ai") {
      return {
        'type': 'select',
        props: { label: 'Model', possibleValues: [
            { label: 'grok-beta', value: 'grok-beta' },
          ] }
      }
    } else if (provider === "anthropic") {
      return {
        'type': 'select',
        props: { label: 'Model', possibleValues: [
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
         props: { label: 'Model', possibleValues: [
             { label: "claude-3-5-sonnet-20240620", value: "claude-3-5-sonnet-20240620" },
             { label: "claude-3-opus-20240229", value: "claude-3-opus-20240229" },
             { label: "claude-3-sonnet-20240229", value: "claude-3-sonnet-20240229" },
             { label: "claude-3-haiku-20240307", value: "claude-3-haiku-20240307" },
           ] }
       }
     } else if (provider === "mistral") {
      return {
        'type': 'select',
        props: { label: 'Model', possibleValues: [
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
        props: { label: 'Model', possibleValues: [
            { 'label': "CodeLlama-13b-Instruct-hf", value: 'CodeLlama-13b-Instruct-hf' },
            { 'label': "Mixtral-8x7B-Instruct-v0.1", value: 'Mixtral-8x7B-Instruct-v0.1' },
            { 'label': "Meta-Llama-3-70B-Instruct", value: 'Meta-Llama-3-70B-Instruct' },
            { 'label': "Llama-2-13b-chat-hf", value: 'Llama-2-13b-chat-hf' },
            { 'label': "Mixtral-8x22B-Instruct-v0.1", value: 'Mixtral-8x22B-Instruct-v0.1' },
            { 'label': "Mistral-7B-Instruct-v0.2", value: 'Mistral-7B-Instruct-v0.2' },
            { 'label': "Meta-Llama-3-8B-Instruct", value: 'Meta-Llama-3-8B-Instruct' },
            { 'label': "mathstral-7B-v0.1", value: "mathstral-7B-v0.1" },
            { 'label': "mamba-codestral-7B-v0.1", value: "mamba-codestral-7B-v0.1" },
            { 'label': "Meta-Llama-3_1-70B-Instruct", value: "Meta-Llama-3_1-70B-Instruct" },
            { 'label': "llava-next-mistral-7b", value: "llava-next-mistral-7b" },
            { 'label': "Mistral-Nemo-Instruct-2407", value: "Mistral-Nemo-Instruct-2407" },
          ] }
      }
    } else if (provider === "ovh-ai-endpoints-unified") {
      return {
        'type': 'select',
        props: { label: 'Model', possibleValues: [
            { 'label': "CodeLlama-13b-Instruct-hf", value: 'CodeLlama-13b-Instruct-hf' },
            { 'label': "Mixtral-8x7B-Instruct-v0.1", value: 'Mixtral-8x7B-Instruct-v0.1' },
            { 'label': "Meta-Llama-3-70B-Instruct", value: 'Meta-Llama-3-70B-Instruct' },
            { 'label': "Llama-2-13b-chat-hf", value: 'Llama-2-13b-chat-hf' },
            { 'label': "Mixtral-8x22B-Instruct-v0.1", value: 'Mixtral-8x22B-Instruct-v0.1' },
            { 'label': "Mistral-7B-Instruct-v0.2", value: 'Mistral-7B-Instruct-v0.2' },
            { 'label': "Meta-Llama-3-8B-Instruct", value: 'Meta-Llama-3-8B-Instruct' },
            { 'label': "mathstral-7B-v0.1", value: "mathstral-7B-v0.1" },
            { 'label': "mamba-codestral-7B-v0.1", value: "mamba-codestral-7B-v0.1" },
            { 'label': "Meta-Llama-3_1-70B-Instruct", value: "Meta-Llama-3_1-70B-Instruct" },
            { 'label': "llava-next-mistral-7b", value: "llava-next-mistral-7b" },
            { 'label': "Mistral-Nemo-Instruct-2407", value: "Mistral-Nemo-Instruct-2407" },
          ] }
      }
    } else if (provider === "deepseek") {
      return {
        'type': 'select',
        props: { label: 'Model', possibleValues: [
            { 'label': "deepseek-chat", value: 'deepseek-chat' },
          ] }
      }
    } else {
      return {
        type: 'string',
        props: { label: 'Model' },
      }
    }
  }

  providerList = _.sortBy([
    { 'label': 'OpenAI', value: 'openai' },
    { 'label': 'Azure AI Foundry', value: 'azure-ai-foundry' },
    { 'label': 'Azure OpenAI', value: 'azure-openai' },
    { 'label': 'Mistral', value: 'mistral' },
    { 'label': 'Ollama', value: 'ollama' },
    { 'label': 'Anthropic', value: 'anthropic' },
    { 'label': 'Groq', value: 'groq' },
    { 'label': 'X.ai', value: 'x-ai' },
    { 'label': 'Scaleway', value: 'scaleway' },
    { 'label': 'Deepseek', value: 'deepseek' },
    { 'label': 'OVH AI Endpoints', value: 'ovh-ai-endpoints' },
    // { 'label': 'OVH AI Endpoints (unified)', value: 'ovh-ai-endpoints-unified' },
    { 'label': 'HuggingFace', value: 'huggingface' },
    { 'label': 'Cloudflare', value: 'cloudflare' },
    { 'label': 'Cohere', value: 'cohere' },
    { 'label': 'Gemini', value: 'gemini' },
    { 'label': 'JLama', value: 'jlama' },
    { 'label': 'Loadbalancer', value: 'loadbalancer' },
  ], a => a.label)

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
      props: { label: ' ', possibleValues: this.providerList }
    },
    'provider_error': {
      'type': 'display',
      props: { label: '', value: canExecuteJlamaMsg }
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
    'options.model': this.providerModels(state.provider || 'none', state),
    'options.provider_models_reload': {
      type: ModelsReloadButton,
      props: { fetchModels: this.fetchModels, provider: this.state }
    },
    'options.max_tokens': {
      type: 'string',
      props: { label: 'Max. tokens' },
    },
    'options.max_completion_tokens': {
      type: 'string',
      props: { label: 'Max. tokens' },
    },
    'options.kind': {
      type: 'select',
      props: { label: 'Kind', possibleValues: [
        { 'label': 'Huggingface', 'value': 'hf' },
        { 'label': 'File', 'value': 'file' },
      ] },
    },
    'options.file_path': {
      type: 'string',
      props: { label: 'Model path' },
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
    connection: {
      type: "jsonobjectcode",
      props: {
        label: ''
      }
    },
    options: {
      type: "jsonobjectcode",
      props: {
        label: ''
      }
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
    'options.allow_config_override': {
      type: 'bool',
      props: { label: 'Allow options override' },
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
    'models.include': {
      type: 'array',
      props: { label: 'Include models', placeholder: 'model name', suffix: 'regex' },
    },
    'models.exclude': {
      type: 'array',
      props: { label: 'Exclude models', placeholder: 'model name', suffix: 'regex' },
    },
    'context.default': {
      type: 'select',
      props: {
        label: 'Default context',
        placeholder: 'Select a prompt context',
        isClearable: true,
        valuesFrom: '/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/prompt-contexts',
        transformer: (a) => ({
          value: a.id,
          label: a.name,
        }),
      }
    },
    'context.contexts': {
      type: 'array',
      props: {
        label: 'Possible contexts',
        placeholder: 'Select a prompt context',
        isClearable: true,
        valuesFrom: '/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/prompt-contexts',
        transformer: (a) => ({
          value: a.id,
          label: a.name,
        }),
      }
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
    'memory': {
      type: 'select',
      props: {
        label: 'Persistent memory',
        placeholder: 'Select a persistent memory provider',
        isClearable: true,
        valuesFrom: '/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/persistent-memories',
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
    'options.tool_functions': {
      type: 'array',
      props: {
        label: 'Tool Functions',
        placeholder: 'Select a tool function',
        valuesFrom: '/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/tool-functions',
        transformer: (a) => ({
          value: a.id,
          label: a.name,
        }),
      }
    },
    'options.mcp_connectors': {
      type: 'array',
      props: {
        label: 'MCP Connectors',
        placeholder: 'Select an MCP Connector',
        valuesFrom: '/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/mcp-connectors',
        transformer: (a) => ({
          value: a.id,
          label: a.name,
        }),
      }
    },
    'options.mcp_include_functions': {
      type: 'array',
      props: {
        label: 'MCP Included Functions',
        placeholder: 'Name of the functions included from MCP Connectors (optional)',
      }
    },
    'options.mcp_exclude_functions': {
      type: 'array',
      props: {
        label: 'MCP Excluded Functions',
        placeholder: 'Name of the functions excluded from MCP Connectors (optional)',
      }
    },
    _guardrails: {
      type: 'jsonobjectcode',
      props: { label: 'Guardrails config.' }
    },
    guardrails_fail_on_deny: {
      type: 'bool',
      props: {
        label: 'Fail request on deny',
        help: 'if enabled, then the request to the API will return an error instead of an error message in the conversation'
      }
    },
    guardrails: {
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
        component: Guardrail
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
        '>>>Context settings',
        'context.default',
        'context.contexts',
        '>>>Models restriction settings',
        'models.include',
        'models.exclude',
        '>>>Provider fallback',
        'provider_fallback', '>>> Persistent memory', 'memory',
        '>>>Cache',
        'cache.strategy',
        'cache.ttl',
        state.cache.strategy === 'semantic' ? 'cache.score' : null,
        '>>>Guardrails validation',
        'guardrails_fail_on_deny',
        'guardrails',
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
        '>>>API Connection raw',
        'connection',
        '<<<Connection options',
        'options.allow_config_override',
        'options.model',
        'options.provider_models_reload',
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
        '>>>Connection options raw',
        'options',
        '>>>Context settings',
        'context.default',
        'context.contexts',
        '>>>Models restriction settings',
        'models.include',
        'models.exclude',
        '>>>Provider fallback',
        'provider_fallback', '>>> Persistent memory', 'memory',
        '>>>Cache',
        'cache.strategy',
        'cache.ttl',
        state.cache.strategy === 'semantic' ? 'cache.score' : null,
        '>>>Guardrails validation',
        'guardrails_fail_on_deny',
        'guardrails',
        '>>>Tools',
        'options.tool_functions',
        'options.mcp_connectors',
        'options.mcp_include_functions',
        'options.mcp_exclude_functions',
        '>>>Tester',
        'tester',
        '>>>Metadata and tags',
        'tags',
        'metadata',
      ];
    }
    if (state.provider === "jlama") {
      return [
        '_loc', 'id', 'name', 'description',
        '<<<Provider',
        'provider',
        canExecuteJlama ? null : 'provider_error',
        '<<<Connection options',
        'options.model',
        'options.max_completion_token',
        'options.temperature',
        'options.kind',
        'options.file_path',
        '>>>Connection options raw',
        'options',
        '>>>Context settings',
        'context.default',
        'context.contexts',
        '>>>Models restriction settings',
        'models.include',
        'models.exclude',
        '>>>Provider fallback',
        'provider_fallback', '>>> Persistent memory', 'memory',
        '>>>Cache',
        'cache.strategy',
        'cache.ttl',
        state.cache.strategy === 'semantic' ? 'cache.score' : null,
        '>>>Guardrails validation',
        'guardrails_fail_on_deny',
        'guardrails',
        '>>>Tester',
        'tester',
        '>>>Metadata and tags',
        'tags',
        'metadata',
      ].filter(i => !!i);
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
        '>>>API Connection raw',
        'connection',
        '<<<Connection options',
        'options.allow_config_override',
        'options.model',
        'options.provider_models_reload',
        'options.max_tokens',
        'options.random_seed',
        'options.safe_prompt',
        'options.temperature',
        'options.top_p',
        '>>>Connection options raw',
        'options',
        '>>>Context settings',
        'context.default',
        'context.contexts',
        '>>>Models restriction settings',
        'models.include',
        'models.exclude',
        '>>>Provider fallback',
        'provider_fallback', '>>> Persistent memory', 'memory',
        '>>>Cache',
        'cache.strategy',
        'cache.ttl',
        state.cache.strategy === 'semantic' ? 'cache.score' : null,
        '>>>Guardrails validation',
        'guardrails_fail_on_deny',
        'guardrails',
        '>>>Tools',
        'options.tool_functions',
        'options.mcp_connectors',
        'options.mcp_include_functions',
        'options.mcp_exclude_functions',
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
        '>>>API Connection raw',
        'connection',
        '<<<Connection options',
        'options.allow_config_override',
        'options.model',
        'options.provider_models_reload',
        'options.max_tokens',
        'options.random_seed',
        'options.safe_prompt',
        'options.temperature',
        'options.k',
        'options.p',
        '>>>Connection options raw',
        'options',
        '>>>Context settings',
        'context.default',
        'context.contexts',
        '>>>Models restriction settings',
        'models.include',
        'models.exclude',
        '>>>Provider fallback',
        'provider_fallback', '>>> Persistent memory', 'memory',
        '>>>Cache',
        'cache.strategy',
        'cache.ttl',
        state.cache.strategy === 'semantic' ? 'cache.score' : null,
        '>>>Guardrails validation',
        'guardrails_fail_on_deny',
        'guardrails',
        '>>>Tools',
        'options.tool_functions',
        'options.mcp_connectors',
        'options.mcp_include_functions',
        'options.mcp_exclude_functions',
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
        '>>>API Connection raw',
        'connection',
        '<<<Connection options',
        'options.allow_config_override',
        'options.model',
        'options.provider_models_reload',
        'options.max_tokens',
        'options.temperature',
        'options.top_p',
        'options.top_k',
        '>>>Connection options raw',
        'options',
        '>>>Context settings',
        'context.default',
        'context.contexts',
        '>>>Models restriction settings',
        'models.include',
        'models.exclude',
        '>>>Provider fallback',
        'provider_fallback', '>>> Persistent memory', 'memory',
        '>>>Cache',
        'cache.strategy',
        'cache.ttl',
        state.cache.strategy === 'semantic' ? 'cache.score' : null,
        '>>>Guardrails validation',
        'guardrails_fail_on_deny',
        'guardrails',
        '>>>Tools',
        'options.tool_functions',
        'options.mcp_connectors',
        'options.mcp_include_functions',
        'options.mcp_exclude_functions',
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
        '>>>API Connection raw',
        'connection',
        '<<<Connection options',
        'options.allow_config_override',
        'options.model',
        'options.provider_models_reload',
        'options.max_tokens',
        'options.temperature',
        'options.top_p',
        'options.n',
        '>>>Connection options raw',
        'options',
        '>>>Context settings',
        'context.default',
        'context.contexts',
        '>>>Models restriction settings',
        'models.include',
        'models.exclude',
        '>>>Provider fallback',
        'provider_fallback', '>>> Persistent memory', 'memory',
        '>>>Cache',
        'cache.strategy',
        'cache.ttl',
        state.cache.strategy === 'semantic' ? 'cache.score' : null,
        '>>>Guardrails validation',
        'guardrails_fail_on_deny',
        'guardrails',
        '>>>Tools',
        'options.tool_functions',
        'options.mcp_connectors',
        'options.mcp_include_functions',
        'options.mcp_exclude_functions',
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
        'options.provider_models_reload',
        'connection.token',
        'connection.timeout',
        '>>>API Connection raw',
        'connection',
        '<<<Connection options',
        'options.allow_config_override',
        'options.maxOutputTokens',
        'options.temperature',
        'options.topP',
        'options.topK',
        'options.stopSequences',
        '>>>Connection options raw',
        'options',
        '>>>Context settings',
        'context.default',
        'context.contexts',
        '>>>Models restriction settings',
        'models.include',
        'models.exclude',
        '>>>Provider fallback',
        'provider_fallback', '>>> Persistent memory', 'memory',
        '>>>Cache',
        'cache.strategy',
        'cache.ttl',
        state.cache.strategy === 'semantic' ? 'cache.score' : null,
        '>>>Guardrails validation',
        'guardrails_fail_on_deny',
        'guardrails',
        '>>>Tools',
        'options.tool_functions',
        'options.mcp_connectors',
        'options.mcp_include_functions',
        'options.mcp_exclude_functions',
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
        '>>>API Connection raw',
        'connection',
        '<<<Connection options',
        'options.allow_config_override',
        'options.max_tokens',
        'options.n',
        'options.temperature',
        'options.topP',
        '>>>Connection options raw',
        'options',
        '>>>Context settings',
        'context.default',
        'context.contexts',
        '>>>Models restriction settings',
        'models.include',
        'models.exclude',
        '>>>Provider fallback',
        'provider_fallback', '>>> Persistent memory', 'memory',
        '>>>Cache',
        'cache.strategy',
        'cache.ttl',
        state.cache.strategy === 'semantic' ? 'cache.score' : null,
        '>>>Guardrails validation',
        'guardrails_fail_on_deny',
        'guardrails',
        '>>>Tools',
        'options.tool_functions',
        'options.mcp_connectors',
        'options.mcp_include_functions',
        'options.mcp_exclude_functions',
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
        '>>>API Connection raw',
        'connection',
        '<<<Connection options',
        'options.allow_config_override',
        'options.model',
        'options.provider_models_reload',
        'options.max_tokens',
        'options.temperature',
        'options.topP',
        'options.seed',
        '>>>Connection options raw',
        'options',
        '>>>Context settings',
        'context.default',
        'context.contexts',
        '>>>Models restriction settings',
        'models.include',
        'models.exclude',
        '>>>Provider fallback',
        'provider_fallback', '>>> Persistent memory', 'memory',
        '>>>Cache',
        'cache.strategy',
        'cache.ttl',
        state.cache.strategy === 'semantic' ? 'cache.score' : null,
        '>>>Guardrails validation',
        'guardrails_fail_on_deny',
        'guardrails',
        // Tools not supported
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
        '>>>API Connection raw',
        'connection',
        '<<<Connection options',
        'options.allow_config_override',
        'options.max_tokens',
        'options.temperature',
        'options.topK',
        'options.topP',
        '>>>Connection options raw',
        'options',
        '>>>Context settings',
        'context.default',
        'context.contexts',
        '>>>Models restriction settings',
        'models.include',
        'models.exclude',
        '>>>Provider fallback',
        'provider_fallback', '>>> Persistent memory', 'memory',
        '>>>Cache',
        'cache.strategy',
        'cache.ttl',
        state.cache.strategy === 'semantic' ? 'cache.score' : null,
        '>>>Guardrails validation',
        'guardrails_fail_on_deny',
        'guardrails',
        // tools not supported
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
        '>>>API Connection raw',
        'connection',
        '<<<Connection options',
        'options.allow_config_override',
        'options.max_tokens',
        'options.temperature',
        'options.top_p',
        'options.seed',
        '>>>Connection options raw',
        'options',
        '>>>Context settings',
        'context.default',
        'context.contexts',
        '>>>Models restriction settings',
        'models.include',
        'models.exclude',
        '>>>Provider fallback',
        'provider_fallback', '>>> Persistent memory', 'memory',
        '>>>Cache',
        'cache.strategy',
        'cache.ttl',
        state.cache.strategy === 'semantic' ? 'cache.score' : null,
        '>>>Guardrails validation',
        'guardrails_fail_on_deny',
        'guardrails',
        // tools not supported
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
      '>>>API Connection raw',
      'connection',
      '<<<Connection options',
      'options.allow_config_override',
      'options.model',
      'options.provider_models_reload',
      'options.max_tokens',
      'options.n',
      'options.temperature',
      'options.topP',
      '>>>Connection options raw',
      'options',
      '>>>Context settings',
      'context.default',
      'context.contexts',
      '>>>Models restriction settings',
      'models.include',
      'models.exclude',
      '>>>Provider fallback',
      'provider_fallback', '>>> Persistent memory', 'memory',
      '>>>Cache',
      'cache.strategy',
      'cache.ttl',
      state.cache.strategy === 'semantic' ? 'cache.score' : null,
      '>>>Guardrails validation',
      'guardrails_fail_on_deny',
      'guardrails',
      '>>>Tools',
      'options.tool_functions',
      'options.mcp_connectors',
      'options.mcp_include_functions',
      'options.mcp_exclude_functions',
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
              timeout: 180000,
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
        kubernetesKind: "ai-gateway.extensions.cloud-apim.com/Provider",
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
                  token: null,
                  timeout: 180000,
                },
                options: ClientOptions.ollama,
              });
            } else if (state.provider === 'jlama') {
              update({
                id: state.id,
                name: state.name,
                description: state.description,
                tags: state.tags,
                metadata: state.metadata,
                provider: 'jlama',
                connection: {},
                options: {
                  model: 'tjake/Llama-3.2-1B-Instruct-JQ4',
                  temperature: 0.5,
                  max_completion_tokens: 256,
                  kind: "hf",
                  file_path: './jlama-models'
                },
              });
            } else if (state.provider === 'anthropic') {
              update({
                id: state.id,
                name: state.name,
                description: state.description,
                tags: state.tags,
                metadata: state.metadata,
                provider: 'anthropic',
                connection: {
                  base_url: BaseUrls.anthropic,
                  token: "xxx",
                  timeout: 180000,
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
                  token: "xxx",
                  timeout: 180000,
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
                  timeout: 180000,
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
                  timeout: 180000,
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
                  timeout: 180000,
                },
                options: ClientOptions.openai,
              });
            } else if (state.provider === 'azure-ai-foundry') {
              update({
                id: state.id,
                name: state.name,
                description: state.description,
                tags: state.tags,
                metadata: state.metadata,
                provider: 'azure-ai-foundry',
                connection: {
                  base_url: BaseUrls.azureAiFoundry,
                  token: 'xxx',
                  timeout: 180000,
                },
                options: ClientOptions.azureAiFoundry,
              });
            } else if (state.provider === 'deepseek') {
              update({
                id: state.id,
                name: state.name,
                description: state.description,
                tags: state.tags,
                metadata: state.metadata,
                provider: 'deepseek',
                connection: {
                  base_url: BaseUrls.deepseek,
                  token: 'xxx',
                  timeout: 180000,
                },
                options: ClientOptions.deepseek,
              });
            } else if (state.provider === 'scaleway') {
              update({
                id: state.id,
                name: state.name,
                description: state.description,
                tags: state.tags,
                metadata: state.metadata,
                provider: 'scaleway',
                connection: {
                  base_url: BaseUrls.scaleway,
                  token: 'xxx',
                  timeout: 180000,
                },
                options: ClientOptions.scaleway,
              });
            } else if (state.provider === 'x-ai') {
              update({
                id: state.id,
                name: state.name,
                description: state.description,
                tags: state.tags,
                metadata: state.metadata,
                provider: 'x-ai',
                connection: {
                  base_url: BaseUrls.xai,
                  token: 'xxx',
                  timeout: 180000,
                },
                options: ClientOptions.xai,
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
                  timeout: 180000,
                },
                options: ClientOptions.ovh,
              });
            } else if (state.provider === 'ovh-ai-endpoints-unified') {
              update({
                id: state.id,
                name: state.name,
                description: state.description,
                tags: state.tags,
                metadata: state.metadata,
                provider: 'ovh-ai-endpoints-unified',
                connection: {
                  base_url: BaseUrls.ovhUnified,
                  token: 'xxx',
                  timeout: 180000,
                },
                options: ClientOptions.ovhUnified,
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
                  timeout: 180000,
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
                  timeout: 180000,
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
                  timeout: 180000,
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
                  timeout: 180000,
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