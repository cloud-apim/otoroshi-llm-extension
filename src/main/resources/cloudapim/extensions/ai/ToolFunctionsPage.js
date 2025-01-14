
function tryOrTrue(f) {
  try {
    return f();
  } catch (e) {
    return true;
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
    'backend.wasmPlugin': {
      type: 'select',
      props: {
        label: 'Wasm plugin',
        valuesFrom: "/bo/api/proxy/apis/plugins.otoroshi.io/v1/wasm-plugins",
        transformer: (item) => ({ label: item.name, value: item.id }),
      },
    },
    'backend.jsPath': {
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
    'backend.options.body': { type: 'text', props: { label: 'Body' } },
    'backend.options.followRedirect': { type: 'bool', props: { label: 'Follow redirects' } },
    'backend.options.proxy': { type: Proxy, props: { label: 'Proxy' } },
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
    (item.backend.kind === 'QuickJs') ? 'backend.jsPath' : null,
    (item.backend.kind === 'WasmPlugin') ? 'backend.wasmPlugin' : null,
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
    // (item.backend.kind === 'Route') ? 'backend.options.route_id' : null,
    // (item.backend.kind === 'Route') ? 'backend.options.apikey_id' : null,
    // (item.backend.kind === 'Route') ? 'backend.options.cert_id' : null,
    '<<<Function parameters',
    'strict',
    'parameters',
    'required',
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
          wasmPlugin: null,
          jsPath: null
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
        kubernetesKind: "PromptTemplate"
      }, null)
    );
  }
}