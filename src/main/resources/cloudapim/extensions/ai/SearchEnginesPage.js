class SearchEnginesPage extends Component {

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

  providerDefaults = (provider) => {
    switch (provider) {
      case 'tavily':
        return {
          base_url: 'https://api.tavily.com',
          options: { max_results: 5, search_depth: 'basic', include_answer: false },
        };
      case 'brave':
        return {
          base_url: 'https://api.search.brave.com',
          options: {},
        };
      case 'searxng':
        return {
          base_url: 'http://localhost:8080',
          options: {},
        };
      case 'google':
        return {
          base_url: 'https://www.googleapis.com',
          options: { cx: '' },
        };
      case 'searchapi':
        return {
          base_url: 'https://www.searchapi.io',
          options: { engine: 'google' },
        };
      case 'duckduckgo':
        return {
          base_url: 'https://api.duckduckgo.com',
          options: {},
        };
      case 'staan':
      default:
        return {
          base_url: 'https://api.staan.ai',
          options: { market: 'fr-FR', count: 10 },
        };
    }
  };

  configFor = (provider) => {
    const defs = this.providerDefaults(provider);
    return {
      connection: {
        base_url: defs.base_url,
        token: 'xxxxx',
        timeout: 30000,
      },
      options: defs.options,
    };
  };

  formSchema = (state) => ({
    _loc: {
      type: 'location',
      props: {},
    },
    id: { type: 'string', disabled: true, props: { label: 'Id', placeholder: '---' } },
    name: {
      type: 'string',
      props: { label: 'Name', placeholder: 'My search engine' },
    },
    description: {
      type: 'string',
      props: { label: 'Description', placeholder: 'Description of the search engine' },
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
          { label: 'Staan.ai (Qwant)', value: 'staan' },
          { label: 'Tavily', value: 'tavily' },
          { label: 'Brave Search', value: 'brave' },
          { label: 'SearXNG', value: 'searxng' },
          { label: 'Google Custom Search', value: 'google' },
          { label: 'SearchApi', value: 'searchapi' },
          { label: 'DuckDuckGo', value: 'duckduckgo' },
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
    // Staan.ai options
    'config.options.market': {
      type: 'string',
      props: { label: 'Market', placeholder: 'fr-FR' },
    },
    'config.options.count': {
      type: 'number',
      props: { label: 'Results count' },
    },
    'config.options.min_score': {
      type: 'number',
      props: { label: 'Min score', placeholder: '0.10' },
    },
    // Tavily options
    'config.options.max_results': {
      type: 'number',
      props: { label: 'Max results' },
    },
    'config.options.search_depth': {
      type: 'select',
      props: {
        label: 'Search depth', possibleValues: [
          { label: 'basic', value: 'basic' },
          { label: 'advanced', value: 'advanced' },
        ]
      },
    },
    'config.options.include_answer': {
      type: 'bool',
      props: { label: 'Include answer' },
    },
    // Brave options
    'config.options.country': {
      type: 'string',
      props: { label: 'Country', placeholder: 'fr' },
    },
    'config.options.search_lang': {
      type: 'string',
      props: { label: 'Search language', placeholder: 'fr' },
    },
    'config.options.safesearch': {
      type: 'select',
      props: {
        label: 'Safe search', possibleValues: [
          { label: 'off', value: 'off' },
          { label: 'moderate', value: 'moderate' },
          { label: 'strict', value: 'strict' },
        ]
      },
    },
    // SearXNG options
    'config.options.engines': {
      type: 'string',
      props: { label: 'Engines', placeholder: 'google,bing,duckduckgo' },
    },
    'config.options.categories': {
      type: 'string',
      props: { label: 'Categories', placeholder: 'general' },
    },
    'config.options.language': {
      type: 'string',
      props: { label: 'Language', placeholder: 'fr' },
    },
    // Google Custom Search options
    'config.options.cx': {
      type: 'string',
      props: { label: 'Search engine id (cx)' },
    },
    'config.options.lr': {
      type: 'string',
      props: { label: 'Language restrict (lr)', placeholder: 'lang_fr' },
    },
    // SearchApi options
    'config.options.engine': {
      type: 'string',
      props: { label: 'Engine', placeholder: 'google' },
    },
    'config.options.gl': {
      type: 'string',
      props: { label: 'Country (gl)', placeholder: 'fr' },
    },
    'config.options.hl': {
      type: 'string',
      props: { label: 'Language (hl)', placeholder: 'fr' },
    },
  });

  optionsFlow = (provider) => {
    switch (provider) {
      case 'tavily':
        return ['config.options.max_results', 'config.options.search_depth', 'config.options.include_answer'];
      case 'brave':
        return ['config.options.country', 'config.options.search_lang', 'config.options.safesearch'];
      case 'searxng':
        return ['config.options.engines', 'config.options.categories', 'config.options.language'];
      case 'google':
        return ['config.options.cx', 'config.options.lr'];
      case 'searchapi':
        return ['config.options.engine', 'config.options.gl', 'config.options.hl'];
      case 'duckduckgo':
        return [];
      case 'staan':
      default:
        return ['config.options.market', 'config.options.count', 'config.options.min_score'];
    }
  };

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
      '<<<Search options',
      ...this.optionsFlow(state.provider),
    ];
  };

  componentDidMount() {
    this.props.setTitle(`Search engines`);
  }

  client = BackOfficeServices.apisClient('ai-gateway.extensions.cloud-apim.com', 'v1', 'search-engines');

  render() {
    const formSchema = this.formSchema(this.state || {});
    const formFlow = this.formFlow(this.state || {});
    return (
      React.createElement(Table, {
        parentProps: this.props,
        selfUrl: "extensions/cloud-apim/ai-gateway/search-engines",
        defaultTitle: "All search engines",
        defaultValue: () => ({
          id: 'search-engine_' + uuid(),
          name: 'Search engine',
          description: 'A search engine',
          tags: [],
          metadata: {},
          provider: 'staan',
          config: this.configFor('staan'),
        }),
        onStateChange: (state, oldState, update) => {
          this.setState(state);
          if (!_.isEqual(state.provider, oldState.provider)) {
            update({
              id: state.id,
              name: state.name,
              description: state.description,
              tags: state.tags,
              metadata: state.metadata,
              provider: state.provider,
              config: this.configFor(state.provider),
            });
          }
        },
        itemName: "Search engine",
        formSchema: formSchema,
        formFlow: formFlow,
        columns: this.columns,
        stayAfterSave: true,
        fetchItems: (paginationState) => this.client.findAll(),
        updateItem: this.client.update,
        deleteItem: this.client.delete,
        createItem: this.client.create,
        navigateTo: (item) => {
          window.location = `/bo/dashboard/extensions/cloud-apim/ai-gateway/search-engines/edit/${item.id}`
        },
        itemUrl: (item) => `/bo/dashboard/extensions/cloud-apim/ai-gateway/search-engines/edit/${item.id}`,
        showActions: true,
        showLink: true,
        rowNavigation: true,
        extractKey: (item) => item.id,
        export: true,
        kubernetesKind: "ai-gateway.extensions.cloud-apim.com/SearchEngine"
      }, null)
    );
  }
}
