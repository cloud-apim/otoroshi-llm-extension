class DecisionModelsPage extends Component {

    // `DecisionModelProviders` comes from the gateway: every provider a decision model can be served by, with
    // the configuration a new one starts from
    defaultConfigOf = (provider) => {
        const found = DecisionModelProviders.find(p => p.id === provider) || DecisionModelProviders[0];
        return _.cloneDeep(found.config);
    }

    formSchema = {

      ...aiStudioOriginField,
        _loc: {
            type: 'location',
            props: {},
        },
        id: {type: 'string', disabled: true, props: {label: 'Id', placeholder: '---'}},
        name: {
            type: 'string',
            props: {label: 'Name', placeholder: 'My Awesome Decision model'},
        },
        description: {
            type: 'string',
            props: {label: 'Description', placeholder: 'Description of the Decision model'},
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
        'models.require_known_costs': {
          type: 'bool',
          props: {
            label: 'Require known costs',
            help: 'if enabled, a model the gateway cannot bill - no known price, or a price in a unit it cannot measure - is not listed and any call using it is rejected before reaching the provider',
          },
        },
        provider: {
            'type': 'select',
            props: {
                label: 'Provider',
                possibleValues: _.sortBy(DecisionModelProviders.map(p => ({ label: p.label, value: p.id })), i => i.label)
            }
        },
        config: {
            type: "monaco-json",
            props: {
                label: 'Configuration',
                height: 300,
            }
        },
        fallback_ref: {
            type: 'select',
            props: {
                label: 'Fallback decision model',
                placeholder: 'Select a fallback',
                help: 'Takes over when this model cannot answer: no answer at all, 408, 429 or a server error. A request the provider refused is not retried elsewhere',
                isClearable: true,
                valuesFrom: '/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/decision-models',
                transformer: (a) => ({
                    value: a.id,
                    label: a.name,
                }),
            }
        },
        fallback_model: {
            type: 'string',
            props: { label: 'Fallback model', placeholder: 'Default model of the fallback decision model' },
        },
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
        {
            title: 'Provider',
            filterId: 'provider',
            content: (item) => item.provider,
        },
      aiStudioColumn,
    ];

    formFlow = [
        'ai_studio_origin', '_loc', 'id', 'name', 'description', 'tags', 'metadata', '---', 'provider', 'config',
        '>>>Fallback',
        'fallback_ref',
        'fallback_model',
        '>>>Models restriction settings',
        'models.include',
        'models.exclude',
        'models.require_known_costs',];

    componentDidMount() {
        this.props.setTitle(`Decision models`);
    }

    client = BackOfficeServices.apisClient('ai-gateway.extensions.cloud-apim.com', 'v1', 'decision-models');

    render() {
        return (
            React.createElement(Table, {
                parentProps: this.props,
                selfUrl: "extensions/cloud-apim/ai-gateway/decision-models",
                defaultTitle: "All Decision models",
                defaultValue: () => ({
                    id: 'decision-model_' + uuid(),
                    name: 'Decision model',
                    description: 'A decision model',
                    tags: [],
                    metadata: {},
                    provider: 'typesafe',
                    config: this.defaultConfigOf('typesafe'),
                }),
                onStateChange: (state, oldState, update) => {
                    this.setState(state)
                    if (!_.isEqual(state.provider, oldState.provider)) {
                        update({ ...state, config: this.defaultConfigOf(state.provider) });
                    }
                },
                itemName: "Decision Models",
                formSchema: this.formSchema,
                formFlow: this.formFlow,
                columns: this.columns,
                stayAfterSave: true,
                fetchItems: (paginationState) => this.client.findAll(),
                updateItem: this.client.update,
                deleteItem: this.client.delete,
                createItem: this.client.create,
                navigateTo: (item) => {
                    window.location = `/bo/dashboard/extensions/cloud-apim/ai-gateway/decision-models/edit/${item.id}`
                },
                itemUrl: (item) => `/bo/dashboard/extensions/cloud-apim/ai-gateway/decision-models/edit/${item.id}`,
                showActions: true,
                showLink: true,
                rowNavigation: true,
                extractKey: (item) => item.id,
                export: true,
                kubernetesKind: "ai-gateway.extensions.cloud-apim.com/DecisionModel"
            }, null)
        );
    }
}
