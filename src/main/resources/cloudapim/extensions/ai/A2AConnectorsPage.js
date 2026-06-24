
class A2AConnectorsPage extends Component {

  formSchema = {
    _loc: { type: 'location', props: {} },
    id: { type: 'string', disabled: true, props: { label: 'Id', placeholder: '---' } },
    name: { type: 'string', props: { label: 'Name', placeholder: 'Remote planning agent' } },
    enabled: { type: 'bool', props: { label: 'Enabled' } },
    description: { type: 'string', props: { label: 'Description', placeholder: 'Connector to a remote A2A agent' } },
    metadata: { type: 'object', props: { label: 'Metadata' } },
    tags: { type: 'array', props: { label: 'Tags' } },
    url: { type: 'string', props: { label: 'URL', placeholder: 'https://remote-agent.example.com' } },
    'agent_card_path': { type: 'string', props: { label: 'Agent Card path', placeholder: '/.well-known/agent-card.json' } },
    'agent_card_fallback_path': { type: 'string', props: { label: 'Agent Card fallback path', help: 'Tried if the primary path 404s (0.3.x servers)', placeholder: '/.well-known/agent.json' } },
    'a2a_version': { type: 'string', props: { label: 'A2A version (override)', help: 'Leave empty to auto-detect from the Agent Card (1.0 / 0.3)' } },
    'authentication': {
      type: 'monaco-json',
      props: {
        height: 200,
        label: 'Authentication',
        help: 'kind: none | bearer | apikey | basic | custom_headers. e.g. { "kind": "bearer", "token": "sk-xxx" }',
      },
    },
    'tls': {
      type: 'monaco-json',
      props: {
        height: 200,
        label: 'TLS',
        help: 'TLS config: { "enabled": false, "loose": false, "trust_all": false, "certs": [], "trusted_certs": [] }',
      },
    },
    'timeout': { type: 'number', props: { label: 'Timeout (ms)', placeholder: '30000' } },
    'streaming': { type: 'bool', props: { label: 'Streaming' } },
    'skills_filter': { type: 'array', props: { label: 'Skills filter', help: 'Only expose these remote skill ids (empty = all)' } },
    'tool_name_overrides': { type: 'object', props: { label: 'Tool label overrides', help: 'Optional skillId -> label map' } },
  };

  columns = [
    { title: 'Name', filterId: 'name', content: (item) => item.name },
    { title: 'URL', filterId: 'url', content: (item) => item.url },
    {
      title: 'Enabled',
      filterId: 'enabled',
      content: (item) => item.enabled ? React.createElement('span', { className: "badge bg-success" }, 'yes') : React.createElement('span', { className: "badge bg-danger" }, 'no'),
    },
  ];

  formFlow = ['_loc', 'id', 'enabled', 'name', 'description', 'tags', 'metadata', '---', 'url', 'agent_card_path', 'agent_card_fallback_path', 'a2a_version', '---', 'authentication', 'tls', '---', 'timeout', 'streaming', 'skills_filter', 'tool_name_overrides'];

  componentDidMount() {
    this.props.setTitle(`A2A Connectors`);
  }

  client = BackOfficeServices.apisClient('ai-gateway.extensions.cloud-apim.com', 'v1', 'a2a-connectors');

  render() {
    return (
      React.createElement(Table, {
        parentProps: this.props,
        selfUrl: "extensions/cloud-apim/ai-gateway/a2a-connectors",
        defaultTitle: "All A2A Connectors",
        defaultValue: () => ({
          id: 'a2a-connector_' + uuid(),
          enabled: true,
          name: 'A2A Connector',
          description: 'A new A2A Connector',
          tags: [],
          metadata: {},
          url: 'https://remote-agent.example.com',
          agent_card_path: '/.well-known/agent-card.json',
          agent_card_fallback_path: '/.well-known/agent.json',
          authentication: { kind: 'none' },
          tls: { enabled: false, loose: false, trust_all: false, certs: [], trusted_certs: [] },
          timeout: 30000,
          streaming: false,
          skills_filter: [],
          tool_name_overrides: {},
        }),
        itemName: "A2A Connector",
        formSchema: this.formSchema,
        formFlow: this.formFlow,
        columns: this.columns,
        stayAfterSave: true,
        fetchItems: (paginationState) => this.client.findAll(),
        updateItem: this.client.update,
        deleteItem: this.client.delete,
        createItem: this.client.create,
        navigateTo: (item) => {
          window.location = `/bo/dashboard/extensions/cloud-apim/ai-gateway/a2a-connectors/edit/${item.id}`
        },
        itemUrl: (item) => `/bo/dashboard/extensions/cloud-apim/ai-gateway/a2a-connectors/edit/${item.id}`,
        showActions: true,
        showLink: true,
        rowNavigation: true,
        extractKey: (item) => item.id,
        export: true,
        kubernetesKind: "ai-gateway.extensions.cloud-apim.com/A2AConnector"
      }, null)
    );
  }
}
