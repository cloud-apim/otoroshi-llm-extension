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
    wasmPlugin: {
      type: 'select',
      props: {
        label: 'Wasm plugin',
        valuesFrom: "/bo/api/proxy/apis/plugins.otoroshi.io/v1/wasm-plugins",
        transformer: (item) => ({ label: item.name, value: item.id }),
      },
    },
    jsPath: {
      type: 'code',
      props: {
        label: 'Javascript path',

      },
    },
    tags: {
      type: 'array',
      props: { label: 'Tags' },
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
    '_loc', 'id', 'name', 'description', 'tags', 'metadata', '---', 'strict', 'wasmPlugin', 'jsPath', '---', 'parameters', 'required'];

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