
class McpVirtualServersPage extends Component {

  formSchema = {
    _loc: {
      type: 'location',
      props: {},
    },
    id: { type: 'string', disabled: true, props: { label: 'Id', placeholder: '---' } },
    name: {
      type: 'string',
      props: { label: 'Name', placeholder: 'My Awesome MCP Virtual Server' },
    },
    enabled: {
      type: 'bool',
      props: { label: 'Enabled' },
    },
    description: {
      type: 'string',
      props: { label: 'Description', placeholder: 'Description of the MCP Virtual Server' },
    },
    metadata: {
      type: 'object',
      props: { label: 'Metadata' },
    },
    tags: {
      type: 'array',
      props: { label: 'Tags' },
    },

    // --- exposed MCP server config (mirrors the MCP exposition plugins config) ---
    'config.name': {
      type: 'string',
      props: { label: 'MCP server name', help: 'Advertised at initialize as serverInfo.name.' },
    },
    'config.version': {
      type: 'string',
      props: { label: 'MCP server version', help: 'Advertised at initialize as serverInfo.version.' },
    },
    'config.refs': {
      type: 'array',
      props: {
        label: 'LLM Tool Functions',
        valuesFrom: '/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/tool-functions',
        transformer: (a) => ({ value: a.id, label: a.name }),
      },
    },
    'config.mcp_refs': {
      type: 'array',
      props: {
        label: 'MCP Connectors',
        valuesFrom: '/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/mcp-connectors',
        transformer: (a) => ({ value: a.id, label: a.name }),
      },
    },
    'config.enforce_oauth': {
      type: 'bool',
      props: { label: 'Enforce OAuth' },
    },
    'config.auth_module_ref': {
      type: 'select',
      props: {
        label: 'Auth. module',
        valuesFrom: '/bo/api/proxy/apis/security.otoroshi.io/v1/auth-modules',
        transformer: (a) => ({ value: a.id, label: a.name }),
      },
    },
    'config.auth_prm_url': {
      type: 'string',
      props: { label: 'OAuth PRM URL' },
    },
    'config.emit_audit_events': {
      type: 'bool',
      props: { label: 'Emit audit events' },
    },
    'config.include_functions': {
      type: 'array',
      props: { label: 'Include functions' },
    },
    'config.exclude_functions': {
      type: 'array',
      props: { label: 'Exclude functions' },
    },
    'config.include_resources': {
      type: 'array',
      props: { label: 'Include resources' },
    },
    'config.exclude_resources': {
      type: 'array',
      props: { label: 'Exclude resources' },
    },
    'config.include_resource_templates': {
      type: 'array',
      props: { label: 'Include resource templates' },
    },
    'config.exclude_resource_templates': {
      type: 'array',
      props: { label: 'Exclude resource templates' },
    },
    'config.include_resource_template_uris': {
      type: 'array',
      props: { label: 'Include resource template URIs' },
    },
    'config.exclude_resource_template_uris': {
      type: 'array',
      props: { label: 'Exclude resource template URIs' },
    },
    'config.include_prompts': {
      type: 'array',
      props: { label: 'Include prompts' },
    },
    'config.exclude_prompts': {
      type: 'array',
      props: { label: 'Exclude prompts' },
    },
    'config.allow_rules': {
      type: 'monaco',
      props: {
        label: 'Allow Rules',
        height: 300,
        help: 'JSON-path validators by category. Shape: { "tool_rules": { "<name>": [ { "path": "$.foo", "value": "bar" } ] }, "prompt_rules": {}, "resource_rules": {}, "resource_templates_rules": {} }',
      },
    },
    'config.disallow_rules': {
      type: 'monaco',
      props: {
        label: 'Disallow Rules',
        height: 300,
        help: 'JSON-path validators by category. Shape: { "tool_rules": { "<name>": [ { "path": "$.foo", "value": "bar" } ] }, "prompt_rules": {}, "resource_rules": {}, "resource_templates_rules": {} }',
      },
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
      title: 'MCP server name',
      filterId: 'config.name',
      content: (item) => item?.config?.name ?? '',
    },
  ];

  formFlow = [
    '_loc', 'id', 'enabled', 'name', 'description', 'tags', 'metadata',
    '---',
    'config.name', 'config.version',
    'config.refs', 'config.mcp_refs',
    '---',
    'config.enforce_oauth', 'config.auth_module_ref', 'config.auth_prm_url',
    '---',
    'config.emit_audit_events',
    '---',
    'config.include_functions', 'config.exclude_functions',
    '---',
    'config.include_resources', 'config.exclude_resources',
    '---',
    'config.include_resource_templates', 'config.exclude_resource_templates',
    'config.include_resource_template_uris', 'config.exclude_resource_template_uris',
    '---',
    'config.include_prompts', 'config.exclude_prompts',
    '---',
    'config.allow_rules', 'config.disallow_rules',
  ];

  componentDidMount() {
    this.props.setTitle(`MCP Virtual Servers`);
  }

  client = BackOfficeServices.apisClient('ai-gateway.extensions.cloud-apim.com', 'v1', 'mcp-virtual-servers');

  render() {
    return (
      React.createElement(Table, {
        parentProps: this.props,
        selfUrl: "extensions/cloud-apim/ai-gateway/mcp-virtual-servers",
        defaultTitle: "All MCP Virtual Servers",
        defaultValue: () => ({
          id: 'mcp-virtual-server_' + uuid(),
          enabled: true,
          name: 'MCP Virtual Server',
          description: 'A new MCP Virtual Server',
          tags: [],
          metadata: {},
          config: {
            name: null,
            version: null,
            enforce_oauth: false,
            auth_module_ref: null,
            auth_prm_url: null,
            refs: [],
            mcp_refs: [],
            emit_audit_events: false,
            include_functions: [],
            exclude_functions: [],
            include_resources: [],
            exclude_resources: [],
            include_resource_templates: [],
            exclude_resource_templates: [],
            include_resource_template_uris: [],
            exclude_resource_template_uris: [],
            include_prompts: [],
            exclude_prompts: [],
            allow_rules: { tool_rules: {}, prompt_rules: {}, resource_rules: {}, resource_templates_rules: {} },
            disallow_rules: { tool_rules: {}, prompt_rules: {}, resource_rules: {}, resource_templates_rules: {} },
          },
        }),
        itemName: "MCP Virtual Server",
        formSchema: this.formSchema,
        formFlow: this.formFlow,
        columns: this.columns,
        stayAfterSave: true,
        fetchItems: (paginationState) => this.client.findAll(),
        updateItem: this.client.update,
        deleteItem: this.client.delete,
        createItem: this.client.create,
        navigateTo: (item) => {
          window.location = `/bo/dashboard/extensions/cloud-apim/ai-gateway/mcp-virtual-servers/edit/${item.id}`
        },
        itemUrl: (item) => `/bo/dashboard/extensions/cloud-apim/ai-gateway/mcp-virtual-servers/edit/${item.id}`,
        showActions: true,
        showLink: true,
        rowNavigation: true,
        extractKey: (item) => item.id,
        export: true,
        kubernetesKind: "ai-gateway.extensions.cloud-apim.com/McpVirtualServer"
      }, null)
    );
  }
}
