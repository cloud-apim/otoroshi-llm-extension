
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
    '_loc', 'id', 'name', 'description', 'tags', 'metadata', '---', 'provider', 'config', '>>>Models restriction settings',
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
        kubernetesKind: "ai-gateway.extensions.cloud-apim.com/EmbeddingModel",
        onStateChange: (state, oldState, update) => {
          this.setState(state)
          if (!_.isEqual(state.provider, oldState.provider) || !_.isEqual(state?.config?.connection?.api_version, oldState?.config?.connection?.api_version)) {
            console.log("set default value")
            if (state.provider === 'ollama') {
              update({
                id: state.id,
                name: state.name,
                description: state.description,
                tags: state.tags,
                metadata: state.metadata,
                provider: 'ollama',
                config: {
                  connection: {
                    base_url: BaseUrls.ollama,
                    token: 'xxx',
                    timeout: 180000,
                  },
                  options: {
                    model: 'snowflake-arctic-embed:22m'
                  },
                }
              });
            } else if (state.provider === 'openai') {
              update({
                id: state.id,
                name: state.name,
                description: state.description,
                tags: state.tags,
                metadata: state.metadata,
                provider: 'openai',
                config: {
                  connection: {
                    base_url: BaseUrls.openai,
                    token: 'xxx',
                    timeout: 180000,
                  },
                  options: {
                    model: 'text-embedding-3-small'
                  },
                }
              });
            } else if (state.provider === 'azure-openai' && state?.config?.connection?.api_version !== 'v1') {
              update({
                id: state.id,
                name: state.name,
                description: state.description,
                tags: state.tags,
                metadata: state.metadata,
                provider: 'azure-openai',
                config: {
                  connection: {
                    resource_name: 'resource_name',
                    deployment_id: 'deployment_id',
                    api_version: '2024-02-01',
                    api_key: 'xxx',
                    token: null,
                    timeout: 180000,
                  },
                  options: {},
                }
              });
            } else if (state.provider === 'azure-openai' && state?.config?.connection?.api_version === 'v1') {
              update({
                id: state.id,
                name: state.name,
                description: state.description,
                tags: state.tags,
                metadata: state.metadata,
                provider: 'azure-openai',
                config: {
                  connection: {
                    base_url: "https://<aoairesource>.openai.azure.com/openai/v1",
                    token: 'xxx',
                    timeout: 180000,
                    api_version: 'v1',
                  },
                  options: {
                    model: 'text-embedding-3-small'
                  },
                }
              });
            } else if (state.provider === 'x-ai') {
              update({
                id: state.id,
                name: state.name,
                description: state.description,
                tags: state.tags,
                metadata: state.metadata,
                provider: 'x-ai',
                config: {
                  connection: {
                    base_url: BaseUrls.xai,
                    token: 'xxx',
                    timeout: 180000,
                  },
                  options: {
                    model: 'v1'
                  },
                }
              });
            } else if (state.provider === 'cloud-temple') {
              update({
                id: state.id,
                name: state.name,
                description: state.description,
                tags: state.tags,
                metadata: state.metadata,
                provider: 'cloud-temple',
                config: {
                  connection: {
                    base_url: BaseUrls.cloudTemple,
                    token: 'xxx',
                    timeout: 180000,
                  },
                  options: {
                    model: 'embeddinggemma:300m'
                  },
                }
              });
            } else if (state.provider === 'huggingface') {
              update({
                id: state.id,
                name: state.name,
                description: state.description,
                tags: state.tags,
                metadata: state.metadata,
                provider: 'huggingface',
                config: {
                  connection: {
                    base_url: BaseUrls.huggingface,
                    token: 'xxx',
                    timeout: 180000,
                  },
                  options: {
                    model: 'Qwen/Qwen3-Embedding-8B'
                  },
                }
              });
            } else if (state.provider === 'cohere') {
              update({
                id: state.id,
                name: state.name,
                description: state.description,
                tags: state.tags,
                metadata: state.metadata,
                provider: 'cohere',
                config: {
                  connection: {
                    base_url: BaseUrls.cohere,
                    token: 'xxx',
                    timeout: 180000,
                  },
                  options: {
                    model: 'embed-v4.0'
                  },
                }
              });
            } else if (state.provider === 'gemini') {
              update({
                id: state.id,
                name: state.name,
                description: state.description,
                tags: state.tags,
                metadata: state.metadata,
                provider: 'gemini',
                config: {
                  connection: {
                    base_url: BaseUrls.gemini,
                    token: 'xxx',
                    timeout: 180000,
                  },
                  options: {
                    model: 'gemini-embedding-001'
                  },
                }
              });
            } else if (state.provider === 'deepseek') {
              update({
                id: state.id,
                name: state.name,
                description: state.description,
                tags: state.tags,
                metadata: state.metadata,
                provider: 'deepseek',
                config: {
                  connection: {
                    base_url: BaseUrls.deepseek,
                    token: 'xxx',
                    timeout: 180000,
                  },
                  options: {
                    model: 'deepseek-r1'
                  },
                }
              });
            } else if (state.provider === 'scaleway') {
              update({
                id: state.id,
                name: state.name,
                description: state.description,
                tags: state.tags,
                metadata: state.metadata,
                provider: 'scaleway',
                config: {
                  connection: {
                    base_url: BaseUrls.scaleway,
                    token: 'xxx',
                    timeout: 180000,
                  },
                  options: {
                    model: 'qwen3-embedding-8b'
                  },
                }
              });
            }  else if (state.provider === 'azure-ai-foundry') {
              update({
                id: state.id,
                name: state.name,
                description: state.description,
                tags: state.tags,
                metadata: state.metadata,
                provider: 'azure-ai-foundry',
                config: {
                  connection: {
                    base_url: BaseUrls.azureAiFoundry,
                    token: 'xxx',
                    timeout: 180000,
                  },
                  options: {
                    model: 'text-embedding-3-small'
                  },
                }
              });
            } else if (state.provider === 'mistral') {
              update({
                id: state.id,
                name: state.name,
                description: state.description,
                tags: state.tags,
                metadata: state.metadata,
                provider: 'mistral',
                config: {
                  connection: {
                    base_url: BaseUrls.mistral,
                    token: 'xxx',
                    timeout: 180000,
                  },
                  options: {
                    model: 'mistral-embed'
                  },
                }
              });
            } else if (state.provider === 'all-minilm-l6-v2') {
              update({
                id: state.id,
                name: state.name,
                description: state.description,
                tags: state.tags,
                metadata: state.metadata,
                provider: 'all-minilm-l6-v2',
                config: {},
              });
            }
          }
        }
      }, null)
    );
  }
}