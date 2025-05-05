class ModerationModelsPage extends Component {

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
                    {label: 'OpenAI', value: "openai"},
                    {label: 'Mistral', value: "mistral"},
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
        this.props.setTitle(`Moderation model`);
    }

    client = BackOfficeServices.apisClient('ai-gateway.extensions.cloud-apim.com', 'v1', 'moderation-models');

    render() {
        return (
            React.createElement(Table, {
                parentProps: this.props,
                selfUrl: "extensions/cloud-apim/ai-gateway/moderation-models",
                defaultTitle: "All Moderation models",
                defaultValue: () => ({
                    id: 'embedding-model_' + uuid(),
                    name: 'Moderation model',
                    description: 'An moderation model',
                    tags: [],
                    metadata: {},
                    provider: 'openai',
                    config: {
                        connection: {
                            token: 'xxxxxx',
                            timeout: 30000
                        },
                        options: {
                            model: 'omni-moderation-latest'
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
                                        timeout: 30000
                                    },
                                    options: {
                                        model: 'omni-moderation-latest'
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
                                        token: 'xxxxxx',
                                        timeout: 30000
                                    },
                                    options: {
                                        model: 'mistral-moderation-latest'
                                    }
                                }
                            });
                        }
                    }
                },
                itemName: "Moderation Model",
                formSchema: this.formSchema,
                formFlow: this.formFlow,
                columns: this.columns,
                stayAfterSave: true,
                fetchItems: (paginationState) => this.client.findAll(),
                updateItem: this.client.update,
                deleteItem: this.client.delete,
                createItem: this.client.create,
                navigateTo: (item) => {
                    window.location = `/bo/dashboard/extensions/cloud-apim/ai-gateway/moderation-models/edit/${item.id}`
                },
                itemUrl: (item) => `/bo/dashboard/extensions/cloud-apim/ai-gateway/moderation-models/edit/${item.id}`,
                showActions: true,
                showLink: true,
                rowNavigation: true,
                extractKey: (item) => item.id,
                export: true,
                kubernetesKind: "ai-gateway.extensions.cloud-apim.com/ModerationModel"
            }, null)
        );
    }
}