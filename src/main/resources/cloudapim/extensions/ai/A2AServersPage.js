
class A2AServersPage extends Component {

  formSchema = {
    _loc: {
      type: 'location',
      props: {},
    },
    id: { type: 'string', disabled: true, props: { label: 'Id', placeholder: '---' } },
    name: {
      type: 'string',
      props: { label: 'Name', placeholder: 'My A2A Agent' },
    },
    enabled: {
      type: 'bool',
      props: { label: 'Enabled' },
    },
    description: {
      type: 'string',
      props: { label: 'Description', placeholder: 'An agent exposed via the A2A protocol' },
    },
    metadata: {
      type: 'object',
      props: { label: 'Metadata' },
    },
    tags: {
      type: 'array',
      props: { label: 'Tags' },
    },
    'agent_card': {
      type: 'monaco-json',
      props: {
        height: 320,
        label: 'Agent Card',
        help: 'A2A v1.0 Agent Card config. The plugin derives `supportedInterfaces` from the route URL at runtime. Shape: { version, provider, default_input_modes, default_output_modes, capabilities, skills }',
      },
    },
    'backend.kind': {
      type: 'select',
      props: {
        label: 'Backend kind',
        possibleValues: [
          { label: 'Inline agent', value: 'agent' },
          { label: 'Workflow', value: 'workflow' },
        ],
      },
    },
    'backend.agent': {
      type: 'monaco-json',
      props: {
        height: 280,
        label: 'Agent (inline AgentConfig)',
        help: 'Inline AgentConfig: { name, description, instructions: [...], provider, model?, mcp_connectors?, a2a_connectors?, tools? }',
      },
    },
    'backend.workflow_ref': {
      type: 'select',
      props: {
        label: 'Workflow',
        optionsFrom: '/bo/api/proxy/apis/plugins.otoroshi.io/v1/workflows',
        optionsTransformer: { label: 'name', value: 'id' },
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
      title: 'Backend',
      filterId: 'backend.kind',
      content: (item) => {
        const kind = item?.backend?.kind ?? 'agent';
        const cls = ({ agent: 'bg-primary', workflow: 'bg-info' })[kind] ?? 'bg-secondary';
        return React.createElement('span', { className: `badge ${cls}` }, kind);
      },
    }
  ];

  formFlow = (state) => {
    const head = ['_loc', 'id', 'enabled', 'name', 'description', 'tags', 'metadata', '---', 'agent_card', '---', 'backend.kind'];
    const kind = state?.backend?.kind ?? 'agent';
    const backendFields = kind === 'workflow' ? ['backend.workflow_ref'] : ['backend.agent'];
    return [...head, ...backendFields];
  };

  componentDidMount() {
    this.props.setTitle(`A2A Servers`);
  }

  client = BackOfficeServices.apisClient('ai-gateway.extensions.cloud-apim.com', 'v1', 'a2a-servers');

  render() {
    return (
      React.createElement(Table, {
        parentProps: this.props,
        selfUrl: "extensions/cloud-apim/ai-gateway/a2a-servers",
        defaultTitle: "All A2A Servers",
        defaultValue: () => ({
          id: 'a2a-server_' + uuid(),
          enabled: true,
          name: 'A2A Server',
          description: 'A new A2A Server',
          tags: [],
          metadata: {},
          agent_card: {
            version: '1.0.0',
            default_input_modes: ['text/plain', 'application/json'],
            default_output_modes: ['text/plain', 'application/json'],
            capabilities: { streaming: true, push_notifications: false, extended_agent_card: false },
            skills: [
              { id: 'main', name: 'Main Skill', description: "The agent's main skill", tags: ['general'], examples: ['Example request'] }
            ]
          },
          backend: {
            kind: 'agent',
            agent: {
              name: 'My Agent',
              description: '',
              instructions: ['You are a helpful assistant.'],
              provider: 'provider_xxx'
            },
            workflow_ref: null
          }
        }),
        itemName: "A2A Server",
        formSchema: this.formSchema,
        formFlow: this.formFlow,
        columns: this.columns,
        stayAfterSave: true,
        fetchItems: (paginationState) => this.client.findAll(),
        updateItem: this.client.update,
        deleteItem: this.client.delete,
        createItem: this.client.create,
        navigateTo: (item) => {
          window.location = `/bo/dashboard/extensions/cloud-apim/ai-gateway/a2a-servers/edit/${item.id}`
        },
        itemUrl: (item) => `/bo/dashboard/extensions/cloud-apim/ai-gateway/a2a-servers/edit/${item.id}`,
        showActions: true,
        showLink: true,
        rowNavigation: true,
        extractKey: (item) => item.id,
        export: true,
        kubernetesKind: "ai-gateway.extensions.cloud-apim.com/A2AServer"
      }, null)
    );
  }
}
