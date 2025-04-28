class ImagesGenModelsPage extends Component {

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
          { label: 'OpenAI', value: "openai" },
          { label: 'X-AI', value: "x-ai" }
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
    this.props.setTitle(`Images Generation models`);
  }

  client = BackOfficeServices.apisClient('ai-gateway.extensions.cloud-apim.com', 'v1', 'images-gen');

  render() {
    return (
      React.createElement(Table, {
        parentProps: this.props,
        selfUrl: "extensions/cloud-apim/ai-gateway/images-gen",
        defaultTitle: "All Images generation models",
        defaultValue: () => ({
          id: 'images-gen-model_' + uuid(),
          name: 'Images generation models',
          description: 'An image generation model',
          tags: [],
          metadata: {},
          provider: 'openai',
          config:{
            connection: {
              base_url: "https://api.openai.com/v1",
              token: 'xxxxxx',
              timeout: 30000
            },
            options: {
              model: 'gpt-image-1',
              size: "1024x1024"
            }
          }
        }),
        itemName: "Images Generation model",
        formSchema: this.formSchema,
        formFlow: this.formFlow,
        columns: this.columns,
        stayAfterSave: true,
        fetchItems: (paginationState) => this.client.findAll(),
        updateItem: this.client.update,
        deleteItem: this.client.delete,
        createItem: this.client.create,
        navigateTo: (item) => {
          window.location = `/bo/dashboard/extensions/cloud-apim/ai-gateway/images-gen/edit/${item.id}`
        },
        itemUrl: (item) => `/bo/dashboard/extensions/cloud-apim/ai-gateway/images-gen/edit/${item.id}`,
        showActions: true,
        showLink: true,
        rowNavigation: true,
        extractKey: (item) => item.id,
        export: true,
        kubernetesKind: "ai-gateway.extensions.cloud-apim.com/ImagesGenModel"
      }, null)
    );
  }
}