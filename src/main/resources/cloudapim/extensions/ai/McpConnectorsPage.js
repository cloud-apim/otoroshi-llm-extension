
class McpConnectorsPage extends Component {

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
    enabled: {
      type: 'bool',
      props: { label: 'Enabled' },
    },
    description: {
      type: 'string',
      props: { label: 'Description', placeholder: 'Description of the Context' },
    },
    metadata: {
      type: 'object',
      props: { label: 'Metadata' },
    },
    tags: {
      type: 'array',
      props: { label: 'Tags' },
    },
    'pool.size': {
      type: 'number',
      props: { label: 'Pool size', placeholder: 'Connector pool size' },
    },
    'transport.kind': {
      'type': 'select',
      props: { label: 'Transport kind', possibleValues: [
          { label: 'SSE', value: "sse" },
          { label: 'Stdio', value: "stdio" },
          { label: 'HTTP', value: "http" },
          { label: 'HTTP (Langchain)', value: "http_langchain" },
          { label: 'WebSocket (not standard, experimental)', value: "ws" },
          { label: 'Meta (aggregate other MCP Connectors)', value: "meta" },
        ] }
    },
    'transport.options': {
      type: "monaco-json",
      props: {
        height: 300,
        label: 'Configuration',
        help: 'Raw transport options (used as a fallback when the kind is unknown).'
      }
    },

    // --- stdio options ---
    'transport.options.command': {
      type: 'string',
      props: { label: 'Command', placeholder: 'node', help: 'Executable used to spawn the MCP server process.' },
    },
    'transport.options.args': {
      type: 'array',
      props: { label: 'Args', placeholder: '/path/to/server.js' },
    },
    'transport.options.env': {
      type: 'object',
      props: { label: 'Environment variables' },
    },

    // --- http / sse / ws / http_langchain options ---
    'transport.options.url': {
      type: 'string',
      props: { label: 'URL', placeholder: 'http://localhost:7001/mcp' },
    },
    'transport.options.headers': {
      type: 'object',
      props: { label: 'Headers', help: 'Custom headers sent on every request. Use {input_token} to inject the forwarded OAuth2 bearer (requires "Forward OAuth2 authentication").' },
    },
    'transport.options.timeout': {
      type: 'number',
      props: { label: 'Timeout (ms)', placeholder: '180000', help: 'Request timeout in milliseconds. Defaults to 180000 (3 minutes).' },
    },

    // --- common transport flag ---
    'transport.options.log': {
      type: 'bool',
      props: { label: 'Log transport traffic', help: 'Log requests/responses (HTTP-style transports) or process events (stdio).' },
    },

    // --- meta options ---
    'transport.options.connectors': {
      type: 'array',
      props: {
        label: 'MCP Connectors',
        description: 'Sub-connectors aggregated by this meta MCP connector. The meta connector exposes 5 tools to the LLM: list_servers, list_tools, get_tool_schema, execute, search_tools.',
        valuesFrom: '/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/mcp-connectors',
        transformer: (a) => ({
          value: a.id,
          label: a.name,
        }),
      }
    },
    'transport.options.semantic_search_enabled': {
      type: 'bool',
      props: {
        label: 'Semantic search',
        help: 'When enabled, search_tools fuses BM25 with embedding-based similarity (MiniLM-L6-v2). Loads a ~25MB ONNX model on first use.',
      }
    },
    'strict': {
      type: 'bool',
      props: { label: 'Strict tool calls', placeholder: 'Strict tool calls' },
    },
    'forward_auth': {
      type: 'bool',
      props: { label: 'Forward OAuth2 authentication', help: 'When enabled, the incoming OAuth2 Bearer token will be forwarded to the MCP server by replacing {input_token} placeholders in transport headers' },
    },
    'include_functions': {
      type: 'array',
      props: {
        label: 'Included Functions',
        placeholder: 'Name of the functions included from MCP Connectors (optional)',
      }
    },
    'exclude_functions': {
      type: 'array',
      props: {
        label: 'Excluded Functions',
        placeholder: 'Name of the functions excluded from MCP Connectors (optional)',
      }
    },
    'include_resources': {
      type: 'array',
      props: {
        label: 'Included Resources',
        placeholder: 'Name of the resources included from MCP Connectors (optional)',
      }
    },
    'exclude_resources': {
      type: 'array',
      props: {
        label: 'Excluded Resources',
        placeholder: 'Name of the resources excluded from MCP Connectors (optional)',
      }
    },
    'include_resource_templates': {
      type: 'array',
      props: {
        label: 'Included Resource Templates',
        placeholder: 'Name of the resource templates included from MCP Connectors (optional)',
      }
    },
    'exclude_resource_templates': {
      type: 'array',
      props: {
        label: 'Excluded Resource Templates',
        placeholder: 'Name of the resource templates excluded from MCP Connectors (optional)',
      }
    },
    'include_resource_template_uris': {
      type: 'array',
      props: {
        label: 'Included Resource Template URIs',
        placeholder: 'URI of the resource templates included from MCP Connectors (optional)',
      }
    },
    'exclude_resource_template_uris': {
      type: 'array',
      props: {
        label: 'Excluded Resource Template URIs',
        placeholder: 'URI of the resource templates excluded from MCP Connectors (optional)',
      }
    },
    'include_prompts': {
      type: 'array',
      props: {
        label: 'Included Prompts',
        placeholder: 'Name of the prompts included from MCP Connectors (optional)',
      }
    },
    'exclude_prompts': {
      type: 'array',
      props: {
        label: 'Excluded Prompts',
        placeholder: 'Name of the prompts excluded from MCP Connectors (optional)',
      }
    },
    'allow_rules': {
      type: 'monaco',
      props: {
        label: 'Allow Rules',
        height: 300,
        help: 'JSON-path validators by category. Shape: { "tool_rules": { "<name>": [ { "path": "$.foo", "value": "bar" } ] }, "prompt_rules": {}, "resource_rules": {}, "resource_templates_rules": {} }',
      }
    },
    'disallow_rules': {
      type: 'monaco',
      props: {
        label: 'Disallow Rules',
        height: 300,
        help: 'JSON-path validators by category. Shape: { "tool_rules": { "<name>": [ { "path": "$.foo", "value": "bar" } ] }, "prompt_rules": {}, "resource_rules": {}, "resource_templates_rules": {} }',
      }
    },
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
    {
      title: 'Enabled',
      filterId: 'enabled',
      content: (item) => item.enabled ? React.createElement('span', { className: "badge bg-success" }, 'yes') : React.createElement('span', { className: "badge bg-danger" }, 'no'),
    },
    {
      title: 'Transport',
      filterId: 'transport.kind',
      content: (item) => {
        const kind = item?.transport?.kind ?? 'stdio';
        const cls = ({
          http: 'bg-primary',
          http_langchain: 'bg-danger',
          sse: 'bg-info',
          ws: 'bg-dark',
          stdio: 'bg-secondary',
          meta: 'bg-warning',
        })[kind] ?? 'bg-secondary';
        return React.createElement('span', { className: `badge ${cls}` }, kind);
      },
    }
  ];

  commonFlowTail = [
    'strict', 'forward_auth',
    '---',
    'include_functions', 'exclude_functions',
    '---',
    'include_resources', 'exclude_resources',
    '---',
    'include_resource_templates', 'exclude_resource_templates',
    'include_resource_template_uris', 'exclude_resource_template_uris',
    '---',
    'include_prompts', 'exclude_prompts',
    '---',
    'allow_rules', 'disallow_rules',
  ];

  transportFieldsFor = (kind) => {
    switch (kind) {
      case 'stdio':
        return ['transport.options.command', 'transport.options.args', 'transport.options.env', 'transport.options.log'];
      case 'sse':
      case 'http':
      case 'http_langchain':
      case 'ws':
        return ['transport.options.url', 'transport.options.headers', 'transport.options.timeout', 'transport.options.log'];
      case 'meta':
        return ['transport.options.connectors', 'transport.options.semantic_search_enabled'];
      default:
        return ['transport.options'];
    }
  }

  formFlow = (state) => {
    const head = ['_loc', 'id', 'enabled', 'name', 'description', 'tags', 'metadata', '---', 'pool.size', '---', 'transport.kind'];
    const transportFields = this.transportFieldsFor(state?.transport?.kind);
    return [...head, ...transportFields, ...this.commonFlowTail];
  }


  componentDidMount() {
    this.props.setTitle(`MCP Connectors`);
  }

  client = BackOfficeServices.apisClient('ai-gateway.extensions.cloud-apim.com', 'v1', 'mcp-connectors');

  render() {
    return (
      React.createElement(Table, {
        parentProps: this.props,
        selfUrl: "extensions/cloud-apim/ai-gateway/mcp-connectors",
        defaultTitle: "All MCP Connectors",
        defaultValue: () => ({
          id: 'mcp-connector_' + uuid(),
          enabled: true,
          name: 'MCP Connector',
          description: 'An new MCP Connector',
          tags: [],
          metadata: {},
          pool: {
            size: 1
          },
          transport: {
            kind: "stdio",
            options: {
              command: "node",
              args: ['/foo/bar/server.js'],
              env: {
                TOKEN: "secret"
              }
            }
          },
          strict: true,
          forward_auth: false
        }),
        itemName: "MCP Connector",
        formSchema: this.formSchema,
        formFlow: this.formFlow,
        columns: this.columns,
        stayAfterSave: true,
        fetchItems: (paginationState) => this.client.findAll(),
        updateItem: this.client.update,
        deleteItem: this.client.delete,
        createItem: this.client.create,
        navigateTo: (item) => {
          window.location = `/bo/dashboard/extensions/cloud-apim/ai-gateway/mcp-connectors/edit/${item.id}`
        },
        itemUrl: (item) => `/bo/dashboard/extensions/cloud-apim/ai-gateway/mcp-connectors/edit/${item.id}`,
        showActions: true,
        showLink: true,
        rowNavigation: true,
        extractKey: (item) => item.id,
        export: true,
        kubernetesKind: "ai-gateway.extensions.cloud-apim.com/McpConnector"
      }, null)
    );
  }
}