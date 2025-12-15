class ImageModelsPage extends Component {

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
      props: {
        label: 'Provider', possibleValues: _.sortBy([
          {label: 'OpenAI', value: "openai"},
          {label: 'Gemini', value: "gemini"},
          {label: 'Grok (X-AI)', value: "x-ai"},
          {label: 'Azure OpenAI', value: "azure-openai"},
          {label: 'Luma', value: "luma"},
          // {label: 'Leonardo AI', value: "leonardo-ai"},
          {label: 'Hive', value: "hive"}
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
    '_loc', 'id', 'name', 'description', 'tags', 'metadata', '---', 'provider', 'config', '>>>Models restriction settings',
        'models.include',
        'models.exclude',];

  componentDidMount() {
    this.props.setTitle(`Image models`);
  }

  client = BackOfficeServices.apisClient('ai-gateway.extensions.cloud-apim.com', 'v1', 'image-models');

  render() {
    return (
      React.createElement(Table, {
        parentProps: this.props,
        selfUrl: "extensions/cloud-apim/ai-gateway/image-models",
        defaultTitle: "All Images generation models",
        defaultValue: () => ({
          id: 'image-model_' + uuid(),
          name: 'Image model',
          description: 'An image model',
          tags: [],
          metadata: {},
          provider: 'openai',
          config: {
            connection: {
              token: 'xxxxxx',
              timeout: 180000
            },
            options: {
              generation: {
                enabled: true,
                model: 'gpt-image-1',
                size: "auto"
              },
              edition: {
                enabled: true,
                model: 'gpt-image-1',
                size: "auto"
              }
            }
          }
        }),
        onStateChange: (state, oldState, update) => {
          this.setState(state)
          if (!_.isEqual(state.provider, oldState.provider)) {
            console.log("set default value", state.provider)
            if (state.provider === 'openai') {
              update({
                id: state.id,
                name: state.name,
                description: state.description,
                tags: state.tags,
                metadata: state.metadata,
                provider: 'openai',
                config: {
                  connection: {
                    token: 'xxxxxx',
                    timeout: 180000
                  },
                  options: {
                    generation: {
                      enabled: true,
                      model: 'gpt-image-1',
                      size: "auto",
                      n: 1,
                    },
                    edition: {
                      enabled: false
                    }
                  }
                },
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
                    token: 'xxxxxx',
                    timeout: 180000
                  },
                  options: {
                    generation: {
                      enabled: true,
                      model: 'imagen-3.0-generate-002',
                      size: "auto",
                      n: 1,
                    },
                    edition: {
                      enabled: false
                    }
                  }
                },
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
                    token: 'xxxxxx',
                    timeout: 180000
                  },
                  options: {
                    generation: {
                      model: 'grok-2-image',
                      n: 1,
                      response_format: "url"
                    },
                    edition: {
                      enabled: false
                    }
                  }
                },
              });
            } else if (state.provider === 'azure-openai') {
              update({
                id: state.id,
                name: state.name,
                description: state.description,
                tags: state.tags,
                metadata: state.metadata,
                provider: 'azure-openai',
                config: {
                  connection: {
                    resource_name: "resource name",
                    deployment_id: "model id",
                    api_key: 'xxxxxx',
                    timeout: 180000
                  },
                  options: {
                    generation: {
                      model: 'gpt-image-1',
                      size: "auto",
                      n: 1,
                      quality: "hd",
                      style: "vivid"
                    },
                    edition: {
                      enabled: false
                    }
                  }
                },
              });
            } else if (state.provider === 'luma') {
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
                    generation: {
                      model: 'photon-1',
                      aspect_ratio: '16:9'
                    },
                    edition: {
                      enabled: false
                    }
                  }
                },
              });
              // } else if (state.provider === 'leonardo-ai') {
              //     update({
              //         id: state.id,
              //         name: state.name,
              //         description: state.description,
              //         tags: state.tags,
              //         metadata: state.metadata,
              //         provider: 'leonardo-ai',
              //         config: {
              //             connection: {
              //                 token: 'xxxxxx',
              //                 timeout: 180000
              //             },
              //             options: {
              //                 modelId: "6bef9f1b-29cb-40c7-b9df-32b51c1f67d3",
              //                 width: 512,
              //                 height: 512,
              //                 ultra: false,
              //                 enhancePrompt: true
              //             }
              //         },
              //     });
            } else if (state.provider === 'hive') {
              update({
                id: state.id,
                name: state.name,
                description: state.description,
                tags: state.tags,
                metadata: state.metadata,
                provider: 'hive',
                config: {
                  connection: {
                    token: 'xxxxxx',
                    timeout: 180000
                  },
                  options: {
                    generation: {
                      model: 'black-forest-labs/flux-schnell',
                      width: 1024,
                      height: 1024,
                      output_format: "jpeg",
                      output_quality: 90
                    },
                    edition: {
                      enabled: false
                    }
                  }
                },
              });
            }
          }
        },
        itemName: "Image model",
        formSchema: this.formSchema,
        formFlow: this.formFlow,
        columns: this.columns,
        stayAfterSave: true,
        fetchItems: (paginationState) => this.client.findAll(),
        updateItem: this.client.update,
        deleteItem: this.client.delete,
        createItem: this.client.create,
        navigateTo: (item) => {
          window.location = `/bo/dashboard/extensions/cloud-apim/ai-gateway/image-models/edit/${item.id}`
        },
        itemUrl: (item) => `/bo/dashboard/extensions/cloud-apim/ai-gateway/image-models/edit/${item.id}`,
        showActions: true,
        showLink: true,
        rowNavigation: true,
        extractKey: (item) => item.id,
        export: true,
        kubernetesKind: "ai-gateway.extensions.cloud-apim.com/ImageModel"
      }, null)
    );
  }
}