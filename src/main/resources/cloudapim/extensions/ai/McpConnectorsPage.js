
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
          { label: 'HTTP (not standard, experimental)', value: "http" },
          { label: 'WebSocket (not standard, experimental)', value: "ws" },
        ] }
    },
    'transport.options': {
      type: "jsonobjectcode",
      props: {
        label: 'Configuration'
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

  formFlow = [
    '_loc', 'id', 'name', 'description', 'tags', 'metadata', '---', 'pool.size', '---', 'transport.kind', 'transport.options'];

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
          }
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