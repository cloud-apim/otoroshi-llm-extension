
class EmbeddingStoresPage extends Component {

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
    provider: {
      'type': 'select',
      props: { label: 'Provider', possibleValues: _.sortBy([
          { label: 'Local store (embedded)', value: "local" },
          { label: 'ChromaDB', value: "chromadb" },
          { label: 'Elasticsearch', value: "elasticsearch" },
          { label: 'OpenSearch', value: "opensearch" },
          { label: 'Qdrant', value: "qdrant" },
          { label: 'Weaviate', value: "weaviate" },
          { label: 'Pinecone', value: "pinecone" },
          { label: 'Redis (Redis Stack)', value: "redis" },
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
    '_loc', 'id', 'name', 'description', 'tags', 'metadata', '---', 'provider', 'config'];

  componentDidMount() {
    this.props.setTitle(`Embedding store`);
  }

  client = BackOfficeServices.apisClient('ai-gateway.extensions.cloud-apim.com', 'v1', 'embedding-stores');

  render() {
    return (
      React.createElement(Table, {
        parentProps: this.props,
        selfUrl: "extensions/cloud-apim/ai-gateway/embedding-stores",
        defaultTitle: "All Embedding stores",
        defaultValue: () => ({
          id: 'embedding-store_' + uuid(),
          name: 'Embedding store',
          description: 'An embedding store',
          tags: [],
          metadata: {},
          provider: 'local',
          config:{
            connection: {
              name: "local",
            },
            options: {
              max_results: 3,
              min_score: 0.7
            }
          }
        }),
        onStateChange: (state, oldState, update) => {
          this.setState(state)
          if (!_.isEqual(state.provider, oldState.provider)) {
            const base = { id: state.id, name: state.name, description: state.description, tags: state.tags, metadata: state.metadata, provider: state.provider };
            const defaultOptions = { max_results: 3, min_score: 0.7 };
            if (state.provider === 'local') {
              update({ ...base, config: { connection: { name: 'local' }, options: defaultOptions } });
            } else if (state.provider === 'chromadb') {
              update({ ...base, config: { connection: { url: 'http://localhost:8000', collection: 'default' }, options: defaultOptions } });
            } else if (state.provider === 'elasticsearch') {
              update({ ...base, config: { connection: { url: 'http://localhost:9200', index: 'embeddings', dims: 384, similarity: 'cosine' }, options: defaultOptions } });
            } else if (state.provider === 'opensearch') {
              update({ ...base, config: { connection: { url: 'http://localhost:9200', index: 'embeddings', dims: 384, engine: 'lucene', space_type: 'cosinesimil' }, options: defaultOptions } });
            } else if (state.provider === 'qdrant') {
              update({ ...base, config: { connection: { url: 'http://localhost:6333', collection: 'default', dims: 384, distance: 'Cosine' }, options: defaultOptions } });
            } else if (state.provider === 'weaviate') {
              update({ ...base, config: { connection: { url: 'http://localhost:8080', class_name: 'Embedding' }, options: defaultOptions } });
            } else if (state.provider === 'pinecone') {
              update({ ...base, config: { connection: { url: 'https://index-xxxxx.svc.environment.pinecone.io', api_key: '' }, options: defaultOptions } });
            } else if (state.provider === 'redis') {
              update({ ...base, config: { connection: { url: 'redis://localhost:6379', prefix: 'otoroshi:ai:emb', dims: 384, distance_metric: 'COSINE' }, options: defaultOptions } });
            }
          }
        },
        itemName: "Embedding Store",
        formSchema: this.formSchema,
        formFlow: this.formFlow,
        columns: this.columns,
        stayAfterSave: true,
        fetchItems: (paginationState) => this.client.findAll(),
        updateItem: this.client.update,
        deleteItem: this.client.delete,
        createItem: this.client.create,
        navigateTo: (item) => {
          window.location = `/bo/dashboard/extensions/cloud-apim/ai-gateway/embedding-stores/edit/${item.id}`
        },
        itemUrl: (item) => `/bo/dashboard/extensions/cloud-apim/ai-gateway/embedding-stores/edit/${item.id}`,
        showActions: true,
        showLink: true,
        rowNavigation: true,
        extractKey: (item) => item.id,
        export: true,
        kubernetesKind: "ai-gateway.extensions.cloud-apim.com/EmbeddingStore"
      }, null)
    );
  }
}