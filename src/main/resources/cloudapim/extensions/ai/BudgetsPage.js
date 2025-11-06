function RemainingBudget(props) {

  const [remaining, setRemaining] = React.useState(0);

  const update = () => {
    fetch(`/extensions/cloud-apim/extensions/ai-extension/ai-budgets/_remaining?budget=${props.rawValue.id}`, {
      method: 'GET',
      credentials: 'include',
      headers: {
        'Content-Type': 'application/json',
      },
    }).then(res => res.json()).then(data => {
      setRemaining(data);
    });
  }

  React.useEffect(() => {
    update();
  }, [props.rawValue]);

  return (
    React.createElement('div', null,
      React.createElement("div", { className: "row mb-3" },
        React.createElement("label", { className: "col-xs-12 col-sm-2 col-form-label" }, 'Remaining USD'),
        React.createElement("div", { className: "col-sm-10", style: { lineHeight: '33px' } }, `${remaining.remaining_total_usd} $`),
      ),
      React.createElement("div", { className: "row mb-3" },
        React.createElement("label", { className: "col-xs-12 col-sm-2 col-form-label" }, 'Consumed USD'),
        React.createElement("div", { className: "col-sm-10", style: { lineHeight: '33px' } }, `${remaining.consumed_total_usd} $`),
      ),
      React.createElement("div", { className: "row mb-3" },
        React.createElement("label", { className: "col-xs-12 col-sm-2 col-form-label" }, 'Remaining tokens'),
        React.createElement("div", { className: "col-sm-10", style: { lineHeight: '33px' } }, `${remaining.remaining_total_tokens} toks.`),
      ),
      React.createElement("div", { className: "row mb-3" },
        React.createElement("label", { className: "col-xs-12 col-sm-2 col-form-label" }, 'Consumed tokens'),
        React.createElement("div", { className: "col-sm-10", style: { lineHeight: '33px' } }, `${remaining.consumed_total_tokens} toks.`),
      ),
      React.createElement("div", { className: "row mb-3" },
        React.createElement("label", { className: "col-xs-12 col-sm-2 col-form-label" }, 'Remaining inference USD'),
        React.createElement("div", { className: "col-sm-10", style: { lineHeight: '33px' } }, `${remaining.remaining_inference_usd} $`),
      ),
      React.createElement("div", { className: "row mb-3" },
        React.createElement("label", { className: "col-xs-12 col-sm-2 col-form-label" }, 'Remaining inference tokens'),
        React.createElement("div", { className: "col-sm-10", style: { lineHeight: '33px' } }, `${remaining.remaining_inference_tokens} toks.`),
      ),
      React.createElement("div", { className: "row mb-3" },
        React.createElement("label", { className: "col-xs-12 col-sm-2 col-form-label" }, 'Remaining image USD'),
        React.createElement("div", { className: "col-sm-10", style: { lineHeight: '33px' } }, `${remaining.remaining_image_usd} $`),
      ),
      React.createElement("div", { className: "row mb-3" },
        React.createElement("label", { className: "col-xs-12 col-sm-2 col-form-label" }, 'Remaining image tokens'),
        React.createElement("div", { className: "col-sm-10", style: { lineHeight: '33px' } }, `${remaining.remaining_image_tokens} toks.`),
      ),
      React.createElement("div", { className: "row mb-3" },
        React.createElement("label", { className: "col-xs-12 col-sm-2 col-form-label" }, 'Remaining audio USD'),
        React.createElement("div", { className: "col-sm-10", style: { lineHeight: '33px' } }, `${remaining.remaining_audio_usd} $`),
      ),
      React.createElement("div", { className: "row mb-3" },
        React.createElement("label", { className: "col-xs-12 col-sm-2 col-form-label" }, 'Remaining audio tokens'),
        React.createElement("div", { className: "col-sm-10", style: { lineHeight: '33px' } }, `${remaining.remaining_audio_tokens} toks.`),
      ),
      React.createElement("div", { className: "row mb-3" },
        React.createElement("label", { className: "col-xs-12 col-sm-2 col-form-label" }, 'Remaining video USD'),
        React.createElement("div", { className: "col-sm-10", style: { lineHeight: '33px' } }, `${remaining.remaining_video_usd} $`),
      ),
      React.createElement("div", { className: "row mb-3" },
        React.createElement("label", { className: "col-xs-12 col-sm-2 col-form-label" }, 'Remaining video tokens'),
        React.createElement("div", { className: "col-sm-10", style: { lineHeight: '33px' } }, `${remaining.remaining_video_tokens} toks.`),
      ),
      React.createElement("div", { className: "row mb-3" },
        React.createElement("label", { className: "col-xs-12 col-sm-2 col-form-label" }, 'Remaining embedding USD'),
        React.createElement("div", { className: "col-sm-10", style: { lineHeight: '33px' } }, `${remaining.remaining_embedding_usd} $`),
      ),
      React.createElement("div", { className: "row mb-3" },
        React.createElement("label", { className: "col-xs-12 col-sm-2 col-form-label" }, 'Remaining embedding tokens'),
        React.createElement("div", { className: "col-sm-10", style: { lineHeight: '33px' } }, `${remaining.remaining_embedding_tokens} toks.`),
      ),
      React.createElement("div", { className: "row mb-3" },
        React.createElement("label", { className: "col-xs-12 col-sm-2 col-form-label" }, 'Remaining moderation USD'),
        React.createElement("div", { className: "col-sm-10", style: { lineHeight: '33px' } }, `${remaining.remaining_moderation_usd} $`),
      ),
      React.createElement("div", { className: "row mb-3" },
        React.createElement("label", { className: "col-xs-12 col-sm-2 col-form-label" }, 'Remaining moderation tokens'),
        React.createElement("div", { className: "col-sm-10", style: { lineHeight: '33px' } }, `${remaining.remaining_moderation_tokens} toks.`),
      ),
      React.createElement("div", { className: "row mb-3" },
        React.createElement("label", { className: "col-xs-12 col-sm-2 col-form-label" }, ''),
        React.createElement("div", { className: "col-sm-10" },
          React.createElement('button', { type: 'button', className: "btn btn-sm btn-primary", onClick: update },
            'Refresh'
          )
        ),
      )
    )
  );
}

function RuleComponent(props) {

  const changePath = (e) => {
    const value = props.value;
    value[props.idx] = { ...props.itemValue, path: e.target.value };
    props.onChange(value);
  };

  const changeValue = (e) => {
    const value = props.value;
    value[props.idx] = { ...props.itemValue, value: e.target.value };
    props.onChange(value);
  };

  return (
    React.createElement("div", { className: "row mb-3" },
      React.createElement("div", { className: "col-xs-12 col-sm-2 col-form-label" }, ''),
      React.createElement("div", { className: "col-sm-10" },
        React.createElement("div", { className: "input-group justify-content-between" },
          React.createElement("input", { type: "text", placeholder: 'JSON Path ($.apikey.metadata.budget)', className: "form-control", value: props.itemValue.path, onChange: changePath }),
          React.createElement("input", { type: "text", placeholder: 'Expected value', className: "form-control", value: props.itemValue.value, onChange: changeValue }),
        )
      )
    )
  )
}

class BudgetsPage extends Component {
  state = {
    dynamicVoices: null
  }
  formSchema = (state) => ({
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
    enabled: {
      type: 'bool',
      props: {label: 'Enabled'},
    },
    start_at: {
      type: 'datetime',
      props: {label: 'Start at'},
    },
    end_at: {
      type: 'datetime',
      props: {label: 'End at'},
    },
    'duration.value': {
      type: 'number',
      props: {label: 'Value'},
    },
    'duration.unit': {
      type: 'select',
      props: {
        label: 'Time unit',
        possibleValues: [
          {value: 'hour', label: 'Hour'},
          {value: 'day', label: 'Day'},
          {value: 'year', label: 'Year'},
        ],
      },
    },
    renewals: {
      type: 'number',
      props: {label: 'Renewals'},
    },
    'limits.total_tokens': {
      type: 'number',
      props: {label: 'Total Tokens', suffix: 'tokens' },
    },
    'limits.total_usd': {
      type: 'number',
      props: {label: 'Total USD', suffix: '$' },
    },
    'limits.inference_tokens': {
      type: 'number',
      props: {label: 'Inference Tokens', suffix: 'tokens' },
    },
    'limits.inference_usd': {
      type: 'number',
      props: {label: 'Inference USD', suffix: '$' },
    },
    'limits.image_tokens': {
      type: 'number',
      props: {label: 'Image Tokens', suffix: 'tokens' },
    },
    'limits.image_usd': {
      type: 'number',
      props: {label: 'Image USD', suffix: '$' },
    },
    'limits.audio_tokens': {
      type: 'number',
      props: {label: 'Audio Tokens', suffix: 'tokens' },
    },
    'limits.audio_usd': {
      type: 'number',
      props: {label: 'Audio USD', suffix: '$' },
    },
    'limits.video_tokens': {
      type: 'number',
      props: {label: 'Video Tokens', suffix: 'tokens' },
    },
    'limits.video_usd': {
      type: 'number',
      props: {label: 'Video USD', suffix: '$' },
    },
    'limits.embedding_tokens': {
      type: 'number',
      props: {label: 'Embedding Tokens', suffix: 'tokens' },
    },
    'limits.embedding_usd': {
      type: 'number',
      props: {label: 'Embedding USD', suffix: '$' },
    },
    'limits.moderation_tokens': {
      type: 'number',
      props: {label: 'Moderation Tokens', suffix: 'tokens' },
    },
    'limits.moderation_usd': {
      type: 'number',
      props: {label: 'Moderation USD', suffix: '$' },
    },
    'limits.remaining': {
      type: RemainingBudget,
      props: {label: 'Limits remaining' },
    },
    'scope.extract_from_apikey_meta': {
      type: 'bool',
      props: {label: 'Extract from API key meta' },
    },
    'scope.extract_from_apikey_group_meta': {
      type: 'bool',
      props: {label: 'Extract from API key group meta'},
    },
    'scope.extract_from_user_meta': {
      type: 'bool',
      props: {label: 'Extract from user meta'},
    },
    'scope.extract_from_provider_meta': {
      type: 'bool',
      props: {label: 'Extract from provider meta'},
    },
    'scope.extract_from_user_auth_module_meta': {
      type: 'bool',
      props: {label: 'Extract from user auth module meta'},
    },
    'scope.apikeys': {
      type: 'array',
      props: {label: 'API keys', 'suffix': 'regex'},
    },
    'scope.users': {
      type: 'array',
      props: {label: 'Users', 'suffix': 'regex'},
    },
    'scope.groups': {
      type: 'array',
      props: {label: 'Groups', 'suffix': 'regex'},
    },
    'scope.providers': {
      type: 'array',
      props: {label: 'Providers', 'suffix': 'regex'},
    },
    'scope.models': {
      type: 'array',
      props: {label: 'Models', 'suffix': 'regex'},
    },
    'scope.rules': {
      type: 'array',
      props: {
        label: 'Rules',
        component: RuleComponent,
      },
    },
    'scope.rules_match_mode': {
      type: 'select',
      props: {
        label: 'Rules match mode',
        possibleValues: [
          {value: 'all', label: 'All'},
          {value: 'any', label: 'Any'},
        ],
      },
    },
    'action_on_exceed.mode': {
      type: 'select',
      props: {
        label: 'Action on exceed mode',
        possibleValues: [
          {value: 'soft', label: 'Soft'},
          {value: 'block', label: 'Block'},
        ],
      },
    },
    'action_on_exceed.alert_on_exceed': {
      type: 'bool',
      props: {label: 'Alert on exceed' },
    },
    'action_on_exceed.alert_on_almost_exceed': {
      type: 'bool',
      props: {label: 'Alert on almost exceed' },
    },
    'action_on_exceed.alert_on_almost_exceed_percentage': {
      type: 'number',
      props: {label: 'Alert on almost exceed threshold', suffix: '%' },
    },
  });

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
      title: 'Enabled',
      filterId: 'enabled',
      content: (item) => item.enabled ? React.createElement('span', { className: 'badge bg-xs bg-success' }, 'yes') : React.createElement('span', { className: 'badge bg-xs bg-danger' }, 'no'),
    },
    {
      title: 'Start at',
      filterId: 'start_at',
      content: (item) => item.start_at,
    },
    {
      title: 'End at',
      filterId: 'end_at',
      content: (item) => item.end_at,
    },
  ];


  formFlow = (state) => {
    return [
      '_loc', 
      'id', 
      'name', 
      'description', 
      'tags', 
      'metadata', 
      '---',
      'enabled',
      'start_at',
      'end_at',
      '<<<Duration',
      'duration.value',
      'duration.unit',
      //'renewals',
      '>>>Limits',
      'limits.total_tokens',
      'limits.total_usd',
      'limits.inference_tokens',
      'limits.inference_usd',
      'limits.image_tokens',
      'limits.image_usd',
      'limits.audio_tokens',
      'limits.audio_usd',
      'limits.video_tokens',
      'limits.video_usd',
      'limits.embedding_tokens',
      'limits.embedding_usd',
      'limits.moderation_tokens',
      'limits.moderation_usd',
      '>>>Consumption',
      'limits.remaining',
      '>>>Scope',
      'scope.extract_from_apikey_meta',
      'scope.extract_from_apikey_group_meta',
      'scope.extract_from_user_meta',
      'scope.extract_from_user_auth_module_meta',
      'scope.extract_from_provider_meta',
      'scope.apikeys',
      'scope.users',
      'scope.groups',
      'scope.providers',
      'scope.models',
      'scope.rules_match_mode',
      'scope.rules',
      '>>>Action on exceed',
      'action_on_exceed.mode',
      'action_on_exceed.alert_on_exceed',
      'action_on_exceed.alert_on_almost_exceed',
      'action_on_exceed.alert_on_almost_exceed_percentage',
    ]
  };

  componentDidMount() {
    this.props.setTitle(`AI Budgets`);
  }

  client = BackOfficeServices.apisClient('ai-gateway.extensions.cloud-apim.com', 'v1', 'ai-budgets');

  render() {
    const formSchemaBuilder = this.formSchema(this.state || {});
    const formFlowBuilder = this.formFlow(this.state || {});
    return (
      React.createElement(Table, {
        parentProps: this.props,
        selfUrl: "extensions/cloud-apim/ai-gateway/ai-budgets",
        defaultTitle: "All AI Budgets",
        defaultValue: () => ({
          id: 'budget_' + uuid(),
          name: 'AI Budget',
          description: 'An AI Budget',
          tags: [],
          metadata: {},
          "enabled" : true,
          "start_at" : moment().format('YYYY-MM-DDTHH:mm:ss.SSS'),
          "end_at" : moment().add(365, 'days').format('YYYY-MM-DDTHH:mm:ss.SSS'),
          "duration" : {
            "value" : 30,
            "unit" : "day"
          },
          "renewals" : 11,
          "limits" : {
            "total_tokens" : 10000000,
            "total_usd" : 200,
            "inference_tokens" : null,
            "inference_usd" : null,
            "image_tokens" : null,
            "image_usd" : null,
            "audio_tokens" : null,
            "audio_usd" : null,
            "video_tokens" : null,
            "video_usd" : null,
            "embedding_tokens" : null,
            "embedding_usd" : null,
            "moderation_tokens" : null,
            "moderation_usd" : null,
          },
          "scope" : {
            "extract_from_apikey_meta" : true,
            "extract_from_apikey_group_meta" : true,
            "extract_from_user_meta" : true,
            "extract_from_user_auth_module_meta" : true,
            "apikeys": [],
            "users": [],
            "groups": [],
            "providers": [],
            "models": [],
            "rules" : [ ],
            "rules_match_mode" : "all"
          },
          "action_on_exceed" : {
            "mode" : "block",
            "alert_on_exceed": true,
            "alert_on_almost_exceed" : true,
            "alert_on_almost_exceed_percentage" : 80,
          }
        }),
        itemName: "AI Budget",
        formSchema: formSchemaBuilder,
        formFlow: formFlowBuilder,
        columns: this.columns,
        stayAfterSave: true,
        fetchItems: (paginationState) => this.client.findAll(),
        updateItem: this.client.update,
        deleteItem: this.client.delete,
        createItem: this.client.create,
        navigateTo: (item) => {
          window.location = `/bo/dashboard/extensions/cloud-apim/ai-gateway/ai-budgets/edit/${item.id}`
        },
        itemUrl: (item) => `/bo/dashboard/extensions/cloud-apim/ai-gateway/ai-budgets/edit/${item.id}`,
        showActions: true,
        showLink: true,
        rowNavigation: true,
        extractKey: (item) => item.id,
        export: true,
        kubernetesKind: "ai-gateway.extensions.cloud-apim.com/AiBudget"
      }, null)
    );
  }
}