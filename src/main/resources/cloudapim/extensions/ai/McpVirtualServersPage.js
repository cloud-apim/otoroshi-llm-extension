
// Array-item widget for a single Zero-Trust redaction rule. Mirrors the AiProvidersPage `Guardrail`
// component contract (props.value = whole array, props.idx, props.itemValue, props.onChange).
class McpRedactionRule extends Component {
  render() {
    return (
      React.createElement(Form, {
        flow: ['name', 'regex', 'replacement'],
        schema: {
          name: { type: 'string', props: { label: 'Name', placeholder: 'internal-ticket' } },
          regex: { type: 'string', props: { label: 'Regex', placeholder: 'TICKET-\\d+' } },
          replacement: { type: 'string', props: { label: 'Replacement', placeholder: '«redacted»' } },
        },
        value: this.props.itemValue,
        onChange: (i) => {
          this.props.value[this.props.idx] = i;
          this.props.onChange(this.props.value);
        }
      }, null)
    );
  }
}

// Form widget bound to `registry.url`: a manual text input PLUS an assistant that scans the gateway routes
// exposing this virtual server, derives the public streamable-http URL and detects the auth, so the admin can
// fill the URL in one click (and see OAuth/apikey/mTLS validation). Receives props.value (current url),
// props.onChange (sets registry.url) and props.rawValue (the whole entity, for its id).
class McpRegistryUrlAssistant extends Component {
  state = { loading: false, candidates: null, error: null };
  componentDidMount() { this.scan(); }
  scan = () => {
    const id = (this.props.rawValue || {}).id;
    if (!id) { this.setState({ candidates: [], error: null, loading: false }); return; }
    this.setState({ loading: true, error: null });
    fetch('/extensions/cloud-apim/extensions/ai-extension/mcp-virtual-servers/_exposition-routes?server=' + encodeURIComponent(id), {
      method: 'GET', credentials: 'include', headers: { Accept: 'application/json' }
    }).then(r => r.json())
      .then(body => this.setState({ loading: false, candidates: body.candidates || [] }))
      .catch(e => this.setState({ loading: false, error: String(e) }));
  };
  badge = (auth) => {
    const cls = auth === 'oauth' ? 'bg-success' : (auth === 'none' ? 'bg-danger' : 'bg-warning');
    return React.createElement('span', { className: 'badge ' + cls, style: { marginLeft: 6 } }, auth);
  };
  render() {
    const value = this.props.value || '';
    const { loading, candidates, error } = this.state;
    return React.createElement('div', { className: 'row mb-3' },
      React.createElement('label', { className: 'col-xs-12 col-sm-2 col-form-label' }, 'Public streamable-http URL'),
      React.createElement('div', { className: 'col-sm-10' },
        React.createElement('div', { className: 'input-group' },
          React.createElement('input', {
            type: 'text', className: 'form-control', placeholder: 'https://gw.acme/mcp/github',
            value: value, onChange: (e) => this.props.onChange(e.target.value)
          }),
          React.createElement('button', { type: 'button', className: 'btn btn-sm btn-secondary', onClick: this.scan, disabled: loading },
            React.createElement('i', { className: 'fas fa-sync' + (loading ? ' fa-spin' : '') }), ' Detect from route')
        ),
        React.createElement('div', { style: { fontSize: 12, opacity: 0.7, marginTop: 4 } },
          'The public URL of the exposed server (the server.json remote). Use “Detect from route” to suggest it from the route(s) that expose this server, then confirm. If empty, no remote is published (clients still discover auth via the server’s own .well-known).'),
        error ? React.createElement('div', { className: 'mt-2 text-danger' }, 'Scan error: ' + error) : null,
        (candidates && candidates.length === 0 && !loading)
          ? React.createElement('div', { className: 'mt-2', style: { opacity: 0.7 } }, 'No route exposes this server yet — expose it on a route (an MCP endpoint plugin or the protected preset), or type the URL above.')
          : null,
        (candidates && candidates.length > 0)
          ? React.createElement('div', { className: 'mt-2', style: { display: 'flex', flexDirection: 'column', gap: 6 } },
              candidates.map((c, i) => React.createElement('div', {
                key: i, style: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', border: '1px solid #505050', borderRadius: 4, padding: '6px 10px' }
              },
                React.createElement('div', null,
                  React.createElement('code', null, c.url), this.badge(c.auth),
                  React.createElement('div', { style: { fontSize: 12, opacity: 0.7 } }, c.route_name),
                  (c.warnings || []).map((w, j) => React.createElement('div', { key: j, className: 'text-warning', style: { fontSize: 12 } }, '⚠ ' + w))
                ),
                React.createElement('button', { type: 'button', className: 'btn btn-sm btn-success', onClick: () => this.props.onChange(c.url) }, 'Use')
              ))
            )
          : null
      )
    );
  }
}

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
      type: 'object',
      props: {
        label: 'Tool → required scopes (RBAC)',
        height: 150,
        help: 'Scope-based authorization per tool. The caller must have ALL listed scopes; tools with no entry are open. Filters tools/list and denies tools/call.',
      },
    },
    'config.tool_cache_ttls': {
      type: 'object',
      props: {
        label: 'Tool → cache TTL (seconds)',
        height: 120,
        help: 'Per-tool result cache (opt-in, idempotent tools only). 0/absent = no cache. Only successful results are cached, keyed by tool + arguments.',
        valueRenderer: (key, value, idx, onChange) => {
          return React.createElement('input', { type: "number", className: "form-control", value, onChange: (e) => {
              typeof e.target.value === 'number' ? onChange(e) : onChange({ target: { value: parseInt(e.target.value, 10) } });
            }}, null);
        }
      },
    },
    'config.tool_rate_limits': {
      type: 'object',
      props: {
        label: 'Tool → rate limit (calls/min per consumer)',
        height: 120,
        help: 'Per-tool, per-consumer rate limit (fixed 60s window, cluster-wide). 0/absent = no limit. Consumer = apikey > user > token.',
        valueRenderer: (key, value, idx, onChange) => {
          return React.createElement('input', { type: "number", className: "form-control", value, onChange: (e) => {
            typeof e.target.value === 'number' ? onChange(e) : onChange({ target: { value: parseInt(e.target.value, 10) } });
          }}, null);
        }
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
    // ── Zero-Trust: A. anti-rug-pull (fingerprint + pinning) ──────────────────────────────────────────────
    'config.zero_trust.pinning_enabled': {
      type: 'bool',
      props: { label: 'Anti-rug-pull: enable pinning', help: 'Fingerprint (sha256 of name + description + inputSchema + annotations) and pin each tool the first time it is seen (Trust-On-First-Use). A later mutation of an already-pinned tool is then detected.' },
    },
    'config.zero_trust.pinning_enforce': {
      type: 'bool',
      props: { label: 'Anti-rug-pull: block on mutation', help: 'Off = monitor (alert + audit only, tool still served). On = drop the mutated tool from tools/list and deny tools/call.' },
    },
    'config.zero_trust.pinning_epoch': {
      type: 'number',
      props: { label: 'Pinning epoch', help: 'Bump this to re-pin every tool after a legitimate description change (it namespaces the stored pins).' },
    },
    'config.zero_trust.pinned_hashes': {
      type: 'object',
      props: { label: 'Explicit pinned hashes', help: 'Optional map tool name → expected sha256 fingerprint. An entry is authoritative and overrides Trust-On-First-Use.' },
    },
    // ── Zero-Trust: B. tool-poisoning / prompt-injection scanning ─────────────────────────────────────────
    'config.zero_trust.description_guardrails': {
      type: 'array',
      props: {
        label: 'Description guardrails',
        help: 'Guardrails run against each tool description at tools/list (catches tool-poisoning / injected instructions). Reuses the same guardrails as the LLM providers (before/after are ignored here).',
        defaultValue: { enabled: true, before: true, after: true, id: 'prompt_injection', config: { provider: null, max_injection_score: 90 } },
        component: Guardrail,
      },
    },
    'config.zero_trust.result_guardrails': {
      type: 'array',
      props: {
        label: 'Result guardrails',
        help: 'Guardrails run against each tool result at tools/call (catches injection smuggled back through tool output).',
        defaultValue: { enabled: true, before: true, after: true, id: 'prompt_injection', config: { provider: null, max_injection_score: 90 } },
        component: Guardrail,
      },
    },
    'config.zero_trust.guardrails_enforce': {
      type: 'bool',
      props: { label: 'Guardrails: block on denial', help: 'Off = monitor (alert only). On = drop the offending tool (description scan) / block the result (result scan).' },
    },
    // ── Zero-Trust: C. PII / secrets redaction ────────────────────────────────────────────────────────────
    'config.zero_trust.redact_arguments': {
      type: 'bool',
      props: { label: 'Redact tool arguments', help: 'Deterministically mask PII/secrets in tool arguments before they are forwarded upstream.' },
    },
    'config.zero_trust.redact_results': {
      type: 'bool',
      props: { label: 'Redact tool results', help: 'Deterministically mask PII/secrets in tool results before they are returned to the model.' },
    },
    'config.zero_trust.redaction_builtins': {
      type: 'array',
      props: {
        label: 'Built-in redaction patterns',
        help: 'Built-in patterns to mask (no LLM, no added latency). Applied in a fixed precedence so specific patterns run before the greedy generic one.',
        possibleValues: [
          { label: 'Email', value: 'email' },
          { label: 'Credit card', value: 'credit_card' },
          { label: 'SSN', value: 'ssn' },
          { label: 'IPv4 address', value: 'ipv4' },
          { label: 'JWT', value: 'jwt' },
          { label: 'AWS access key', value: 'aws_key' },
          { label: 'Private key block', value: 'private_key' },
          { label: 'Generic API key / high-entropy', value: 'generic_api_key' },
        ],
      },
    },
    'config.zero_trust.redaction_rules': {
      type: 'array',
      props: {
        label: 'Custom redaction rules',
        help: 'Custom regex → replacement rules, applied after the built-ins.',
        defaultValue: { name: '', regex: '', replacement: '«redacted»' },
        component: McpRedactionRule,
      },
    },
    // ── Registry / publication (entity-level, projected to standard server.json) ──────────────────────────
    'registry.published': {
      type: 'bool',
      props: { label: 'Publish to MCP registry', help: 'When on, this server is listed by the MCP registry endpoints (GET /v0/servers) so registry-aware MCP clients can discover it. Flip it as the approval gate (admin role).' },
    },
    'registry.name': {
      type: 'string',
      props: { label: 'Registry name (namespaced)', placeholder: 'io.acme/github', help: 'Reverse-DNS namespaced name in the registry. Defaults to a slug of the server name (io.cloud-apim/<slug>).' },
    },
    'registry.version': {
      type: 'string',
      props: { label: 'Version', placeholder: '1.0.0', help: 'Semantic version advertised in server.json.' },
    },
    'registry.title': {
      type: 'string',
      props: { label: 'Title', help: 'Human-readable title. Defaults to the server name.' },
    },
    'registry.url': {
      type: McpRegistryUrlAssistant,
    },
    'registry.deprecated': {
      type: 'bool',
      props: { label: 'Deprecated', help: 'Marks the entry status as "deprecated" in the registry while keeping it listed.' },
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
    '>>> Authentication',
    'config.enforce_oauth', 'config.validate_audience', 'config.opaque_token', 'config.use_introspection',
    'config.auth_module_ref', 'config.auth_prm_url',
    '>>> Tools',
    'config.tool_scopes', 'config.tool_cache_ttls', 'config.tool_rate_limits',
    '---',
    'config.emit_audit_events',
    '>>> Filtering',
    'config.include_functions', 'config.exclude_functions',
    '---',
    'config.include_resources', 'config.exclude_resources',
    '---',
    'config.include_resource_templates', 'config.exclude_resource_templates',
    'config.include_resource_template_uris', 'config.exclude_resource_template_uris',
    '---',
    'config.include_prompts', 'config.exclude_prompts',
    '>>> Rules',
    'config.allow_rules', 'config.disallow_rules',
    '>>> Additions and overlays',
    'config.resources', 'config.resource_fetch_allowed_hosts',
    '---',
    'config.prompts',
    '---',
    'config.overlays',
    '>>> Zero trust',
    'config.zero_trust.pinning_enabled', 'config.zero_trust.pinning_enforce', 'config.zero_trust.pinning_epoch', 'config.zero_trust.pinned_hashes',
    '---',
    'config.zero_trust.description_guardrails', 'config.zero_trust.result_guardrails', 'config.zero_trust.guardrails_enforce',
    '---',
    'config.zero_trust.redact_arguments', 'config.zero_trust.redact_results', 'config.zero_trust.redaction_builtins', 'config.zero_trust.redaction_rules',
    '>>>MCP Registry',
    'registry.published', 'registry.name', 'registry.version', 'registry.title', 'registry.url', 'registry.deprecated',
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
            emit_audit_events: true,
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
            zero_trust: {
              pinning_enabled: false,
              pinning_enforce: false,
              pinned_hashes: {},
              pinning_epoch: 0,
              description_guardrails: [],
              result_guardrails: [],
              guardrails_enforce: false,
              redact_arguments: false,
              redact_results: false,
              redaction_builtins: [],
              redaction_rules: [],
            },
          },
          registry: { published: false, name: null, version: '1.0.0', deprecated: false, url: null, title: null },
        }),
        itemName: "MCP Virtual Server",
        formSchema: this.formSchema,
        formFlow: this.formFlow,
        columns: this.columns,
        stayAfterSave: true,
        fetchItems: (paginationState) => this.client.findAll().then(all => {
          return all.map(i => {
            const scopes = {};
            i.config = i.config || {};
            i.config.tool_scopes = i.config.tool_scopes || {};
            Object.keys(i.config.tool_scopes).map(key => {
              scopes[key] = i.config.tool_scopes[key].join(', ')
            });
            i.config.tool_scopes = scopes;
            // defensively default the zero_trust block so its widgets bind to [] / {} (not undefined) on
            // entities created before this feature existed.
            i.config.zero_trust = Object.assign({
              pinning_enabled: false, pinning_enforce: false, pinned_hashes: {}, pinning_epoch: 0,
              description_guardrails: [], result_guardrails: [], guardrails_enforce: false,
              redact_arguments: false, redact_results: false, redaction_builtins: [], redaction_rules: [],
            }, i.config.zero_trust || {});
            // registry block lives at the entity level (not under config); default it for older entities.
            i.registry = Object.assign({
              published: false, name: null, version: '1.0.0', deprecated: false, url: null, title: null,
            }, i.registry || {});
            return i;
          })
        }),
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
