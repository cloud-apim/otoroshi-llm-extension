class OcrModelsPage extends Component {

  state = {};

  columns = [
    {
      title: 'Name',
      filterId: 'name',
      content: (item) => item.name,
    },
    {
      title: 'Provider',
      filterId: 'provider',
      content: (item) => item.provider,
    },
    {
      title: 'Description',
      filterId: 'description',
      content: (item) => item.description,
    },
  ];

  providerModel = (provider) => {
    if (provider === 'alphaedge') {
      return {
        type: 'select',
        props: { label: 'Model', possibleValues: [
          { label: 'alpha-digit-max', value: 'alpha-digit-max' },
          { label: 'alpha-digit-medium', value: 'alpha-digit-medium' },
        ] }
      };
    } else if (provider === 'mistral') {
      return {
        type: 'select',
        props: { label: 'Model', possibleValues: [
          { label: 'mistral-ocr-latest', value: 'mistral-ocr-latest' },
          { label: 'mistral-ocr-2505', value: 'mistral-ocr-2505' },
        ] }
      };
    } else {
      return { type: 'string', props: { label: 'Model' } };
    }
  };

  formSchema = (state) => ({
    _loc: {
      type: 'location',
      props: {},
    },
    id: { type: 'string', disabled: true, props: { label: 'Id', placeholder: '---' } },
    name: {
      type: 'string',
      props: { label: 'Name', placeholder: 'My OCR model' },
    },
    description: {
      type: 'string',
      props: { label: 'Description', placeholder: 'Description of the OCR model' },
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
      props: {
        label: 'Provider', possibleValues: _.sortBy([
          { label: 'AlphaEdge', value: 'alphaedge' },
          { label: 'Mistral', value: 'mistral' },
        ], i => i.label)
      }
    },
    'config.connection.base_url': {
      type: 'string',
      props: { label: 'Base URL' },
    },
    'config.connection.token': {
      type: 'string',
      props: { label: 'Token / API key' },
    },
    'config.connection.timeout': {
      type: 'number',
      props: { label: 'Timeout', suffix: 'millis' },
    },
    'config.options.model': this.providerModel(state.provider),
    'config.options.pdf_password': {
      type: 'string',
      props: { label: 'PDF password', placeholder: 'password for protected PDFs (optional)' },
    },
    'models.include': {
      type: 'array',
      props: { label: 'Include models', placeholder: 'model name', suffix: 'regex' },
    },
    'models.exclude': {
      type: 'array',
      props: { label: 'Exclude models', placeholder: 'model name', suffix: 'regex' },
    },
  });

  formFlow = (state) => {
    if (!state.provider) {
      return ['_loc', 'id', 'name', 'description', 'tags', 'metadata', '---', 'provider'];
    }
    return [
      '_loc', 'id', 'name', 'description', 'tags', 'metadata',
      '<<<Provider',
      'provider',
      '<<<API Connection',
      'config.connection.base_url',
      'config.connection.token',
      'config.connection.timeout',
      '<<<OCR options',
      'config.options.model',
      ...(state.provider === 'alphaedge' ? ['config.options.pdf_password'] : []),
      '>>>Models restriction settings',
      'models.include',
      'models.exclude',
    ];
  };

  componentDidMount() {
    this.props.setTitle(`OCR models`);
  }

  client = BackOfficeServices.apisClient('ai-gateway.extensions.cloud-apim.com', 'v1', 'ocr-models');

  render() {
    const formSchema = this.formSchema(this.state || {});
    const formFlow = this.formFlow(this.state || {});
    return (
      React.createElement(Table, {
        parentProps: this.props,
        selfUrl: "extensions/cloud-apim/ai-gateway/ocr-models",
        defaultTitle: "All OCR models",
        defaultValue: () => ({
          id: 'ocr-model_' + uuid(),
          name: 'OCR model',
          description: 'An OCR model',
          tags: [],
          metadata: {},
          provider: 'alphaedge',
          config: {
            connection: {
              base_url: 'https://api-endpoints.alphaedge-ai.com',
              token: 'xxxxx',
              timeout: 180000,
            },
            options: {
              model: 'alpha-digit-max',
            }
          }
        }),
        onStateChange: (state, oldState, update) => {
          this.setState(state);
          if (!_.isEqual(state.provider, oldState.provider)) {
            if (state.provider === 'alphaedge') {
              update({
                id: state.id,
                name: state.name,
                description: state.description,
                tags: state.tags,
                metadata: state.metadata,
                provider: 'alphaedge',
                config: {
                  connection: {
                    base_url: 'https://api-endpoints.alphaedge-ai.com',
                    token: 'xxxxx',
                    timeout: 180000,
                  },
                  options: {
                    model: 'alpha-digit-max',
                  }
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
                    base_url: 'https://api.mistral.ai/v1',
                    token: 'xxxxx',
                    timeout: 180000,
                  },
                  options: {
                    model: 'mistral-ocr-latest',
                  }
                }
              });
            }
          }
        },
        itemName: "OCR model",
        formSchema: formSchema,
        formFlow: formFlow,
        columns: this.columns,
        stayAfterSave: true,
        fetchItems: (paginationState) => this.client.findAll(),
        updateItem: this.client.update,
        deleteItem: this.client.delete,
        createItem: this.client.create,
        navigateTo: (item) => {
          window.location = `/bo/dashboard/extensions/cloud-apim/ai-gateway/ocr-models/edit/${item.id}`
        },
        itemUrl: (item) => `/bo/dashboard/extensions/cloud-apim/ai-gateway/ocr-models/edit/${item.id}`,
        showActions: true,
        showLink: true,
        rowNavigation: true,
        extractKey: (item) => item.id,
        export: true,
        kubernetesKind: "ai-gateway.extensions.cloud-apim.com/OcrModel"
      }, null)
    );
  }
}
