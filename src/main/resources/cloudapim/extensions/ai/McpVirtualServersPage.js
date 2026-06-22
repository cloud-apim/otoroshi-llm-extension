
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
    'config.expose_as_meta': {
      type: 'bool',
      props: { label: 'Expose as meta (tool virtualization)', help: 'Expose the referenced connectors via 5 virtualization tools (list_servers, list_tools, get_tool_schema, search_tools, execute) instead of the full tool list - like the meta connector. Local functions stay listed directly.' },
    },
    'config.meta_semantic_search': {
      type: 'bool',
      props: { label: 'Meta: enable semantic tool search', help: 'When meta mode is on, fuse BM25 with embedding-based similarity (MiniLM-L6-v2) in search_tools.' },
    },
    'config.enforce_oauth': {
      type: 'bool',
      props: { label: 'Enforce OAuth' },
    },
    'config.validate_audience': {
      type: 'bool',
      props: { label: 'Validate token audience (RFC 8707)', help: 'Require the token `aud` to match this MCP server URL. Prevents token passthrough / confused-deputy.' },
    },
    'config.opaque_token': {
      type: 'bool',
      props: { label: 'Opaque access token', help: 'Validate non-JWT access tokens remotely (userinfo by default, or RFC 7662 introspection) instead of local JWT verification.' },
    },
    'config.use_introspection': {
      type: 'bool',
      props: { label: 'Use RFC 7662 introspection', help: 'For opaque tokens: validate via the introspection endpoint instead of userinfo.' },
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
    'config.tool_scopes': {
      type: 'monaco-json',
      props: {
        label: 'Tool → required scopes (RBAC)',
        height: 150,
        help: 'Scope-based authorization per tool. Shape: { "<tool-name>": ["scope-a", "scope-b"], "*": ["base-scope"] }. The caller must have ALL listed scopes; tools with no entry are open. Filters tools/list and denies tools/call.',
      },
    },
    'config.tool_cache_ttls': {
      type: 'monaco-json',
      props: {
        label: 'Tool → cache TTL (seconds)',
        height: 120,
        help: 'Per-tool result cache (opt-in, idempotent tools only). Shape: { "<tool-name>": 60, "*": 30 }. 0/absent = no cache. Only successful results are cached, keyed by tool + arguments.',
      },
    },
    'config.tool_rate_limits': {
      type: 'monaco-json',
      props: {
        label: 'Tool → rate limit (calls/min per consumer)',
        height: 120,
        help: 'Per-tool, per-consumer rate limit (fixed 60s window, cluster-wide). Shape: { "<tool-name>": 100, "*": 1000 }. 0/absent = no limit. Consumer = apikey > user > token.',
      },
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
      type: 'monaco-json',
      props: {
        label: 'Allow Rules',
        height: 300,
        help: 'JSON-path validators by category. Shape: { "tool_rules": { "<name>": [ { "path": "$.foo", "value": "bar" } ] }, "prompt_rules": {}, "resource_rules": {}, "resource_templates_rules": {} }',
      },
    },
    'config.disallow_rules': {
      type: 'monaco-json',
      props: {
        label: 'Disallow Rules',
        height: 300,
        help: 'JSON-path validators by category. Shape: { "tool_rules": { "<name>": [ { "path": "$.foo", "value": "bar" } ] }, "prompt_rules": {}, "resource_rules": {}, "resource_templates_rules": {} }',
      },
    },
    'config.resources': {
      type: 'monaco-json',
      props: {
        label: 'Managed resources',
        height: 300,
        help: 'Resources served directly by this virtual server (in addition to the connectors). JSON array of objects: { "uri": "...", "name": "...", "title?": "...", "description?": "...", "mime_type?": "...", "annotations?": {}, "meta?": {}, and one content source: "text" (inline), "blob" (inline base64), or "url" (fetched on the fly with "url_as": "text"|"blob", "headers": {}, "forward_auth": false, "timeout": 30000). url/headers/text support expression language; {input_token} is injected when forward_auth is true.',
      },
    },
    'config.resource_fetch_allowed_hosts': {
      type: 'array',
      props: {
        label: 'Resource fetch allowed hosts',
        help: 'Optional allow-list of hosts (glob) the server may fetch resource URLs from. Empty = no restriction (be careful: SSRF risk).',
      },
    },
    'config.prompts': {
      type: 'monaco-json',
      props: {
        label: 'Managed prompts',
        height: 300,
        help: 'Prompts served directly by this virtual server (in addition to the connectors). JSON array of objects: { "name": "...", "title?": "...", "description?": "...", "arguments?": [ { "name": "...", "description?": "...", "required?": false } ], "messages": [ { "role": "user"|"assistant"|"system", "text": "..." } ], "meta?": {} }. Message text supports {{argName}} substitution from the prompts/get arguments and expression language.',
      },
    },
    'config.overlays': {
      type: 'monaco-json',
      props: {
        label: 'Item overlays',
        height: 300,
        help: 'Per-item JSON patches deep-merged onto tools/prompts/resources/resource-templates at list time (managed items included). Shape: { "tools": { "<name>": { "description": "...", "_meta": {...}, "annotations": {...}, "inputSchema": {...}, "outputSchema": {...}, "title": "..." } }, "prompts": { "<name>": {...} }, "resources": { "<name-or-uri>": { "mimeType": "...", "_meta": {...} } }, "resource_templates": { "<uriTemplate>": {...} } }. Use the key "*" to patch every item in a category. deepMerge: nested objects merged, scalars/arrays replaced.',
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
    'config.expose_as_meta', 'config.meta_semantic_search',
    '---',
    'config.enforce_oauth', 'config.validate_audience', 'config.opaque_token', 'config.use_introspection',
    'config.auth_module_ref', 'config.auth_prm_url', 'config.tool_scopes',
    'config.tool_cache_ttls', 'config.tool_rate_limits',
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
    '---',
    'config.resources', 'config.resource_fetch_allowed_hosts',
    '---',
    'config.prompts',
    '---',
    'config.overlays',
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
            validate_audience: false,
            opaque_token: false,
            use_introspection: false,
            auth_module_ref: null,
            auth_prm_url: null,
            tool_scopes: {},
            tool_cache_ttls: {},
            tool_rate_limits: {},
            refs: [],
            mcp_refs: [],
            expose_as_meta: false,
            meta_semantic_search: false,
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
            resources: [],
            resource_fetch_allowed_hosts: [],
            prompts: [],
            overlays: { tools: {}, prompts: {}, resources: {}, resource_templates: {} },
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
