
function tryOrTrue(f) {
  try {
    return f();
  } catch (e) {
    return true;
  }
}

class FunctionTester extends Component {
  state = {
    calling: false,
    input: '{\n  "arg1": "foo",\n  "arg2": "bar"\n}\n',
    result: null,
    error: false,
  }
  send = () => {
    const input = this.state.input;
    if (!this.state.calling) {
      this.setState({ calling: true })
      fetch('/extensions/cloud-apim/extensions/ai-extension/functions/_test', {
        method: 'POST',
        credentials: 'include',
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          function: this.props.rawValue,
          parameters: this.state.input,
        })
      }).then(r => r.json()).then(r => {
        if (r.error) {
          this.setState({ result: r.error, calling: false, error: true })
        } else {
          this.setState({ result: r.result, calling: false })
        }
      }).catch(ex => {
        this.setState({ calling: false, error: true, result: ex.message })
      })
    }
  }
  render() {
    return [
      React.createElement('div', { className: 'row mb-3' },
        React.createElement('label', { className: 'col-xs-12 col-sm-2 col-form-label' }, ''),
        React.createElement('div', { className: 'col-sm-10', style: { display: 'flex' } },
          React.createElement('div', { style: { display: 'flex', width: '100%', flexDirection: 'column' }},

            React.createElement(React.Suspense, { fallback: "Loading..." },
              React.createElement(LazyCodeInput, {
                editorOnly: true,
                value: this.state.input,
                onChange: input => this.setState({ input })
              }),
            ),

            React.createElement('div', { style: { width: '100%', padding: 5, display: 'flex', justifyContent: 'flex-end' }, className: 'input-group'},
              React.createElement('button', { type: 'button', className: 'btn btn-sm btn-success', style: { marginTop: 15 }, onClick: this.send, disabled: this.state.calling },
                React.createElement('i', { className: 'fas fa-play' }),
                React.createElement('span', null, ' Test'),
              ),
            ),

            (this.state.result && React.createElement(React.Suspense, { fallback: "Loading..." },
              React.createElement(LazyCodeInput, {
                editorOnly: true,
                value: this.state.result,
                onChange: input => this.setState({ input })
              })
            ))
          )
        )
      )
    ];
  }
}


class ToolFunctionsPage extends Component {

  formSchema = {
    _loc: {
      type: 'location',
      props: {},
    },
    id: { type: 'string', disabled: true, props: { label: 'Id', placeholder: '---' } },
    name: {
      type: 'string',
      props: { label: 'Name', placeholder: 'My Awesome function' },
    },
    description: {
      type: 'text',
      props: { label: 'Description', placeholder: 'Description of the function' },
    },
    metadata: {
      type: 'object',
      props: { label: 'Metadata' },
    },
    strict: {
      type: 'bool',
      props: { label: 'Strict' },
    },
    parameters: {
      type: 'jsonobjectcode',
      props: { label: 'Parameters spec.', mode: 'javascript' },
    },
    required: {
      type: 'array',
      props: { label: 'Required params.' },
    },
    'backend.options.wasmPlugin': {
      type: 'select',
      props: {
        label: 'Wasm plugin',
        valuesFrom: "/bo/api/proxy/apis/plugins.otoroshi.io/v1/wasm-plugins",
        transformer: (item) => ({ label: item.name, value: item.id }),
      },
    },
    'backend.options.jsPath': {
      type: 'code',
      props: {
        label: 'Javascript path',

      },
    },
    'backend.options.headers': { type: 'object', props: { label: 'Headers' } },
    'backend.options.timeout': {
      type: 'number',
      props: { label: 'Timeout', suffix: 'millis.' },
    },
    'backend.options.method': { type: 'string', props: { label: 'Method' } },
    'backend.options.url': { type: 'string', props: { label: 'URL' } },
    'backend.options.body': { type: 'code', props: { label: 'Body' } },
    'backend.options.followRedirect': { type: 'bool', props: { label: 'Follow redirects' } },
    'backend.options.proxy': { type: Proxy, props: { label: 'Proxy' } },
    'backend.options.response_path': { type: 'string', props: { label: 'Response selection (JSON path)' } },
    'backend.options.response_at': { type: 'string', props: { label: 'Response selection (dotted path)' } },
    'backend.options.tls.enabled': {
      type: 'bool',
      props: { label: 'Custom TLS Settings' },
    },
    'backend.options.tls.loose': {
      type: 'bool',
      display: (v) => tryOrTrue(() => v.mtlsConfig.mtls),
      props: { label: 'TLS loose' },
    },
    'backend.options.tls.trust_all': {
      type: 'bool',
      display: (v) => tryOrTrue(() => v.mtlsConfig.mtls),
      props: { label: 'TrustAll' },
    },
    'backend.options.tls.certs': {
      type: 'array',
      display: (v) => tryOrTrue(() => v.mtlsConfig.mtls),
      props: {
        label: 'Client certificates',
        placeholder: 'Choose a client certificate',
        valuesFrom: '/bo/api/proxy/api/certificates',
        transformer: (a) => ({
          value: a.id,
          label: (
            React.createElement('span', null,
              React.createElement('span', { className: "badge bg-success", style: { minWidth: 63 }},
                a.certType
              ), ` ${a.name} - ${a.description}`,
            )
          ),
        }),
      },
    },
    'backend.options.tls.trusted_certs': {
      type: 'array',
      display: (v) => tryOrTrue(() => v.mtlsConfig.mtls && !v.mtlsConfig.trustAll),
      props: {
        label: 'Trusted certificates',
        placeholder: 'Choose a trusted certificate',
        valuesFrom: '/bo/api/proxy/api/certificates',
        transformer: (a) => ({
          value: a.id,
          label: (
            React.createElement('span', null,
              React.createElement('span', { className: "badge bg-success", style: { minWidth: 63 }},
                a.certType
              ), ` ${a.name} - ${a.description}`,
            )
          ),
        }),
      },
    },
    tags: {
      type: 'array',
      props: { label: 'Tags' },
    },
    'backend.kind': {
      type: "select",
      props: {
        label: 'Kind',
        possibleValues: [
          { label: 'Quick JS (Wasm)', value: 'QuickJs' },
          { label: 'Wasm Plugin', value: 'WasmPlugin' },
          { label: 'Http call', value: 'Http' },
          // { label: 'Route call', value: 'Route' },
        ]
      }
    },
    'backend.options.workflow_id': {
      type: 'select',
      props: {
        label: 'Wasm plugin',
        valuesFrom: "/bo/api/proxy/apis/plugins.otoroshi.io/v1/workflows",
        transformer: (item) => ({ label: item.name, value: item.id }),
      },
    },
    tester: {
      type: FunctionTester,
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

  formFlow = (item) => [
    '_loc', 'id', 'name', 'description', 'tags', 'metadata',
    '<<<Backend',
    'backend.kind',
    (item.backend.kind === 'Workflow') ? 'backend.options.workflow_id' : null,
    (item.backend.kind === 'QuickJs') ? 'backend.options.jsPath' : null,
    (item.backend.kind === 'WasmPlugin') ? 'backend.options.wasmPlugin' : null,
    (item.backend.kind === 'Http') ? 'backend.options.url' : null,
    (item.backend.kind === 'Http') ? 'backend.options.method' : null,
    (item.backend.kind === 'Http') ? 'backend.options.body' : null,
    (item.backend.kind === 'Http') ? 'backend.options.headers' : null,
    (item.backend.kind === 'Http') ? 'backend.options.timeout' : null,
    (item.backend.kind === 'Http') ? 'backend.options.followRedirect' : null,
    // (item.backend.kind === 'Http') ? 'backend.options.proxy' : null,
    (item.backend.kind === 'Http') ? 'backend.options.tls.enabled' : null,
    (item.backend.kind === 'Http') ? 'backend.options.tls.loose' : null,
    (item.backend.kind === 'Http') ? 'backend.options.tls.trust_all' : null,
    (item.backend.kind === 'Http') ? 'backend.options.tls.certs' : null,
    (item.backend.kind === 'Http') ? 'backend.options.tls.trusted_certs' : null,
    (item.backend.kind === 'Http') ? '<<<Response selection' : null,
    (item.backend.kind === 'Http') ? 'backend.options.response_path' : null,
    (item.backend.kind === 'Http') ? 'backend.options.response_at' : null,
    // (item.backend.kind === 'Route') ? 'backend.options.route_id' : null,
    // (item.backend.kind === 'Route') ? 'backend.options.apikey_id' : null,
    // (item.backend.kind === 'Route') ? 'backend.options.cert_id' : null,
    '<<<Function parameters',
    'strict',
    'parameters',
    'required',
    '>>>Tester',
    'tester'

  ].filter(i => !!i);

  componentDidMount() {
    this.props.setTitle(`LLM Tool Functions`);
  }

  client = BackOfficeServices.apisClient('ai-gateway.extensions.cloud-apim.com', 'v1', 'tool-functions');

  render() {
    return (
      React.createElement(Table, {
        parentProps: this.props,
        selfUrl: "extensions/cloud-apim/ai-gateway/tool-functions",
        defaultTitle: "All LLM Tool Functions",
        defaultValue: () => ({
          id: 'tool-function_' + uuid(),
          name: 'Tool Function',
          description: 'A new tool function',
          tags: [],
          metadata: {},
          strict: true,
          parameters: {},
          backend: {
            kind: 'QuickJs',
            options: {
              jsPath: "'inline module';\n\nexports.tool_call = function(args) {\n  return 'hello world !';\n}",
            }
          }
        }),
        itemName: "Tool Function",
        formSchema: this.formSchema,
        formFlow: this.formFlow,
        columns: this.columns,
        stayAfterSave: true,
        fetchItems: (paginationState) => this.client.findAll(),
        updateItem: this.client.update,
        deleteItem: this.client.delete,
        createItem: this.client.create,
        navigateTo: (item) => {
          window.location = `/bo/dashboard/extensions/cloud-apim/ai-gateway/tool-functions/edit/${item.id}`
        },
        itemUrl: (item) => `/bo/dashboard/extensions/cloud-apim/ai-gateway/tool-functions/edit/${item.id}`,
        showActions: true,
        showLink: true,
        rowNavigation: true,
        extractKey: (item) => item.id,
        export: true,
        kubernetesKind: "ai-gateway.extensions.cloud-apim.com/ToolFunction"
      }, null)
    );
  }
}