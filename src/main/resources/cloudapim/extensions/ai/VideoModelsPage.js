class VideoModelsPage extends Component {

  formSchema = {
    _loc: {
      type: 'location',
      props: {},
    },
    id: {type: 'string', disabled: true, props: {label: 'Id', placeholder: '---'}},
    name: {
      type: 'string',
      props: {label: 'Name', placeholder: 'My Awesome Context'},
    },
    description: {
      type: 'string',
      props: {label: 'Description', placeholder: 'Description of the Context'},
    },
    metadata: {
      type: 'object',
      props: {label: 'Metadata'},
    },
    tags: {
      type: 'array',
      props: {label: 'Tags'},
    },
    provider: {
      'type': 'select',
      props: {
        label: 'Provider', possibleValues: _.sortBy([
          {label: 'Luma', value: "luma"}
        ], i => i.label)
      }
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
    this.props.setTitle(`Video models`);
  }

  client = BackOfficeServices.apisClient('ai-gateway.extensions.cloud-apim.com', 'v1', 'video-models');

  render() {
    return (
      React.createElement(Table, {
        parentProps: this.props,
        selfUrl: "extensions/cloud-apim/ai-gateway/video-models",
        defaultTitle: "All Videos generation models",
        defaultValue: () => ({
          id: 'video-model_' + uuid(),
          name: 'Luma video generation models',
          description: 'A video generation model',
          tags: [],
          metadata: {},
          provider: 'luma',
          config: {
            connection: {
              token: 'xxxxxx',
              timeout: 180000
            },
            options: {
              model: 'ray-flash-2',
              aspect_ratio: '16:9',
              resolution: "720p",
              duration: "5s"
            }
          }
        }),
        onStateChange: (state, oldState, update) => {
          this.setState(state)
          if (!_.isEqual(state.provider, oldState.provider)) {
            console.log("set default value", state.provider)
            if (state.provider === 'luma') {
              update({
                id: state.id,
                name: state.name,
                description: state.description,
                tags: state.tags,
                metadata: state.metadata,
                provider: 'luma',
                config: {
                  connection: {
                    token: 'xxxxxx',
                    timeout: 180000
                  },
                  options: {
                    model: 'ray-flash-2',
                    aspect_ratio: '16:9',
                    resolution: "720p",
                    duration: "5s"
                  }
                },
              });
            }
          }
        },
        itemName: "Video model",
        formSchema: this.formSchema,
        formFlow: this.formFlow,
        columns: this.columns,
        stayAfterSave: true,
        fetchItems: (paginationState) => this.client.findAll(),
        updateItem: this.client.update,
        deleteItem: this.client.delete,
        createItem: this.client.create,
        navigateTo: (item) => {
          window.location = `/bo/dashboard/extensions/cloud-apim/ai-gateway/video-models/edit/${item.id}`
        },
        itemUrl: (item) => `/bo/dashboard/extensions/cloud-apim/ai-gateway/video-models/edit/${item.id}`,
        showActions: true,
        showLink: true,
        rowNavigation: true,
        extractKey: (item) => item.id,
        export: true,
        kubernetesKind: "ai-gateway.extensions.cloud-apim.com/VideoModel"
      }, null)
    );
  }
}