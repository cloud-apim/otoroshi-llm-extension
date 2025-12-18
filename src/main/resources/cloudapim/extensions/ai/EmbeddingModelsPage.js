
class EmbeddingModelsPage extends Component {

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
    'models.include': {
      type: 'array',
      props: { label: 'Include models', placeholder: 'model name', suffix: 'regex' },
    },
    'models.exclude': {
      type: 'array',
      props: { label: 'Exclude models', placeholder: 'model name', suffix: 'regex' },
    },
    provider: {
      'type': 'select',
      props: { label: 'Provider', possibleValues: _.sortBy([
          { label: 'OpenAI', value: "openai" },
          { label: 'Azure OpenAI', value: "azure-openai" },
          { label: 'Azure AI Foundry', value: "azure-ai-foundry" },
          { label: 'Ollama', value: "ollama" },
          { label: 'Mistral', value: "mistral" },
          { label: 'Scaleway', value: "scaleway" },
          { label: 'Cloud Temple', value: "cloud-temple" },
          { label: 'Deepseek', value: "deepseek" },
          { label: 'X.AI', value: "x-ai" },
          { label: 'Gemini', value: "gemini" },
          { label: 'Cohere', value: "cohere" },
          { label: 'Huggingface', value: "huggingface" },
          { label: 'All MiniLM L6 V2 (embedded)', value: "all-minilm-l6-v2" },
      ], i => i.label) }
    },
    config: {
      type: "jsonobjectcode",
      props: {
        label: 'Configuration'
      }
    },
    'config.options.api_version': {
      type: 'string',
      props: { label: 'Embeddings API version (Azure OpenAI)', placeholder: '2024-02-01' }
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
    '_loc', 'id', 'name', 'description', 'tags', 'metadata', '---', 'provider', 'config', 'config.options.api_version', '>>>Models restriction settings',
        'models.include',
        'models.exclude',
    ];

  componentDidMount() {
    this.props.setTitle(`Embedding model`);
  }

  client = BackOfficeServices.apisClient('ai-gateway.extensions.cloud-apim.com', 'v1', 'embedding-models');

  render() {
    return (
      React.createElement(Table, {
        parentProps: this.props,
        selfUrl: "extensions/cloud-apim/ai-gateway/embedding-models",
        defaultTitle: "All Embedding models",
        defaultValue: () => ({
          id: 'embedding-model_' + uuid(),
          name: 'Embedding model',
          description: 'An embedding model',
          tags: [],
          metadata: {},
          provider: 'ollama',
          config:{
            connection: {
              base_url: "http://localhost:11434",
              token: 'xxxxxx',
              timeout: 30000
            },
            options: {
              model: 'snowflake-arctic-embed:22m'
            }
          }
        }),
        itemName: "Embedding Model",
        formSchema: this.formSchema,
        formFlow: this.formFlow,
        columns: this.columns,
        stayAfterSave: true,
        fetchItems: (paginationState) => this.client.findAll(),
        updateItem: this.client.update,
        deleteItem: this.client.delete,
        createItem: this.client.create,
        navigateTo: (item) => {
          window.location = `/bo/dashboard/extensions/cloud-apim/ai-gateway/embedding-models/edit/${item.id}`
        },
        itemUrl: (item) => `/bo/dashboard/extensions/cloud-apim/ai-gateway/embedding-models/edit/${item.id}`,
        showActions: true,
        showLink: true,
        rowNavigation: true,
        extractKey: (item) => item.id,
        export: true,
        kubernetesKind: "ai-gateway.extensions.cloud-apim.com/EmbeddingModel"
      }, null)
    );
  }
}