// the counters of a consumption, as `remaining_<name>` / `consumed_<name>` / `extra_<name>` name them
const BUDGET_COUNTERS = [
  ['total_usd', 'USD', '$'], ['total_tokens', 'tokens', 'toks.'],
  ['inference_usd', 'inference USD', '$'], ['inference_tokens', 'inference tokens', 'toks.'],
  ['image_usd', 'image USD', '$'], ['image_tokens', 'image tokens', 'toks.'],
  ['audio_usd', 'audio USD', '$'], ['audio_tokens', 'audio tokens', 'toks.'],
  ['video_usd', 'video USD', '$'], ['video_tokens', 'video tokens', 'toks.'],
  ['embedding_usd', 'embedding USD', '$'], ['embedding_tokens', 'embedding tokens', 'toks.'],
  ['moderation_usd', 'moderation USD', '$'], ['moderation_tokens', 'moderation tokens', 'toks.'],
  ['ocr_usd', 'OCR USD', '$'], ['ocr_pages', 'OCR pages', 'pages'],
  ['decision_usd', 'decision USD', '$'], ['decision_tokens', 'decision tokens', 'toks.'],
];

function budgetsCall(path, method) {
  return fetch(`/extensions/cloud-apim/extensions/ai-extension/ai-budgets/${path}`, {
    method: method || 'GET',
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
    },
  }).then(res => res.json().then(body => ({ status: res.status, body })));
}

function budgetRow(label, value) {
  return React.createElement("div", { className: "row mb-3", key: label },
    React.createElement("label", { className: "col-xs-12 col-sm-2 col-form-label" }, label),
    React.createElement("div", { className: "col-sm-10", style: { lineHeight: '33px' } }, value),
  );
}

// The consumption of the current cycle: of the budget, or of one of its partitions when it counts its consumers apart
function RemainingBudget(props) {

  const budget = props.rawValue;
  const partitioned = !!(budget.scope && budget.scope.partition_by);
  const [remaining, setRemaining] = React.useState({});
  const [partitions, setPartitions] = React.useState({ total: 0, items: [] });
  const [partition, setPartition] = React.useState(null);
  const [query, setQuery] = React.useState('');

  const confirmFn = window.newConfirm || ((m) => Promise.resolve(window.confirm(m)));
  const alertFn = window.newAlert || ((m) => Promise.resolve(window.alert(m)));
  const partitionParam = (p) => p ? `&partition=${encodeURIComponent(p)}` : '';

  const update = () => {
    if (partitioned) {
      budgetsCall(`_partitions?budget=${budget.id}&limit=20&q=${encodeURIComponent(query)}`).then(r => {
        if (r.status === 200) setPartitions(r.body);
      });
      if (partition) {
        budgetsCall(`_remaining?budget=${budget.id}${partitionParam(partition)}`).then(r => setRemaining(r.status === 200 ? r.body : {}));
      } else {
        setRemaining({});
      }
    } else {
      budgetsCall(`_remaining?budget=${budget.id}`).then(r => setRemaining(r.status === 200 ? r.body : {}));
    }
  };

  const reset = (all) => {
    const what = all ? 'all cycles' : 'the current cycle';
    const whom = partitioned ? ', for every consumer,' : '';
    confirmFn(`Are you sure you want to reset the consumption for ${what}${whom} of this budget ?`).then(ok => {
      if (ok) budgetsCall(`_reset?budget=${budget.id}&all=${all}`, 'POST').then(update);
    });
  };

  const resetPartition = () => {
    confirmFn(`Are you sure you want to reset the consumption of ${partition} for the current cycle ?`).then(ok => {
      if (ok) budgetsCall(`_reset?budget=${budget.id}${partitionParam(partition)}`, 'POST').then(update);
    });
  };

  const extend = () => {
    const extension = Object.keys(remaining.extension || {}).map(k => `${remaining.extension[k]} ${k.replace('_', ' ')}`).join(', ');
    const whom = partition ? ` for ${partition}` : '';
    confirmFn(`Take one extension${whom} (${extension}) for the current cycle ?`).then(ok => {
      if (ok) budgetsCall(`_extend?budget=${budget.id}${partitionParam(partition)}`, 'POST').then(r => {
        if (r.status !== 200) alertFn(r.body.error_description || r.body.error || 'the extension was refused');
        update();
      });
    });
  };

  const cancelExtensions = () => {
    confirmFn('Cancel the extensions of the current cycle ? They will not be given back.').then(ok => {
      if (ok) budgetsCall(`_extend?budget=${budget.id}${partitionParam(partition)}`, 'DELETE').then(update);
    });
  };

  React.useEffect(() => {
    update();
  }, [props.rawValue, partition, query]);

  const button = (className, icon, label, onClick, disabled) =>
    React.createElement('button', { type: 'button', className: `btn btn-sm ${className}`, onClick, disabled: !!disabled, key: label },
      React.createElement('i', { className: `fas ${icon}` }), ` ${label}`
    );

  const allowsExtensions = remaining.extensions_max !== undefined;
  const taken = remaining.extensions_taken || 0;
  const extras = BUDGET_COUNTERS.filter(([name]) => remaining[`extra_${name}`] !== undefined);

  const consumption = [
    budgetRow('Remaining USD', `${remaining.remaining_total_usd} $`),
    budgetRow('Consumed USD', `${remaining.consumed_total_usd} $`),
    budgetRow('Remaining tokens', `${remaining.remaining_total_tokens} toks.`),
    budgetRow('Consumed tokens', `${remaining.consumed_total_tokens} toks.`),
    ...BUDGET_COUNTERS.slice(2).map(([name, label, unit]) => budgetRow(`Remaining ${label}`, `${remaining[`remaining_${name}`]} ${unit}`)),
    ...(allowsExtensions ? [budgetRow('Extensions taken', `${taken} / ${remaining.extensions_max}`)] : []),
    ...extras.map(([name, label, unit]) => budgetRow(`Extension of ${label}`, `+${remaining[`extra_${name}`]} ${unit}`)),
  ];

  const extensionButtons = [
    ...(allowsExtensions ? [button('btn-success', 'fa-plus', 'Extend', extend, taken >= remaining.extensions_max)] : []),
    ...(extras.length > 0 ? [button('btn-warning', 'fa-undo', 'Cancel extensions', cancelExtensions)] : []),
  ];

  if (!partitioned) {
    return React.createElement('div', null,
      ...consumption,
      React.createElement("div", { className: "row mb-3" },
        React.createElement("label", { className: "col-xs-12 col-sm-2 col-form-label" }, ''),
        React.createElement("div", { className: "col-sm-10", style: { display: 'flex', gap: '10px' } },
          button('btn-primary', 'fa-sync', 'Refresh', update),
          ...extensionButtons,
          button('btn-warning', 'fa-eraser', 'Reset current cycle', () => reset(false)),
          button('btn-danger', 'fa-trash', 'Reset all cycles', () => reset(true)),
        ),
      )
    );
  }

  return React.createElement('div', null,
    budgetRow('Consumers', React.createElement('div', null,
      React.createElement('input', { type: 'text', className: 'form-control', placeholder: 'Search a consumer', value: query, onChange: (e) => setQuery(e.target.value) }),
      // the colors of the page, the bootstrap table ones are the light theme ones
      React.createElement('table', { className: 'table table-sm', style: { marginTop: 10, color: 'inherit', '--bs-table-bg': 'transparent', '--bs-table-color': 'inherit' } },
        React.createElement('thead', null, React.createElement('tr', null,
          React.createElement('th', null, `Consumer (${partitions.total} this cycle)`),
          React.createElement('th', null, 'Consumed USD'),
          React.createElement('th', null, 'Consumed tokens'),
          React.createElement('th', null, 'Extensions'),
          React.createElement('th', null, ''),
        )),
        React.createElement('tbody', null, ...partitions.items.map(item =>
          React.createElement('tr', { key: item.partition },
            React.createElement('td', null, item.partition),
            React.createElement('td', null, `${item.consumption.consumed_total_usd} $`),
            React.createElement('td', null, `${item.consumption.consumed_total_tokens} toks.`),
            React.createElement('td', null, item.consumption.extensions_max !== undefined ? `${item.consumption.extensions_taken || 0} / ${item.consumption.extensions_max}` : '-'),
            React.createElement('td', null, button(item.partition === partition ? 'btn-primary' : 'btn-secondary', 'fa-eye', 'Details', () => setPartition(item.partition))),
          )
        )),
      ),
    )),
    ...(partition ? [
      budgetRow('Consumer', partition),
      ...consumption,
      React.createElement("div", { className: "row mb-3", key: 'partition-actions' },
        React.createElement("label", { className: "col-xs-12 col-sm-2 col-form-label" }, ''),
        React.createElement("div", { className: "col-sm-10", style: { display: 'flex', gap: '10px' } },
          ...extensionButtons,
          button('btn-warning', 'fa-eraser', `Reset ${partition}`, resetPartition),
        ),
      ),
    ] : []),
    React.createElement("div", { className: "row mb-3" },
      React.createElement("label", { className: "col-xs-12 col-sm-2 col-form-label" }, ''),
      React.createElement("div", { className: "col-sm-10", style: { display: 'flex', gap: '10px' } },
        button('btn-primary', 'fa-sync', 'Refresh', update),
        button('btn-warning', 'fa-eraser', 'Reset current cycle', () => reset(false)),
        button('btn-danger', 'fa-trash', 'Reset all cycles', () => reset(true)),
      ),
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
    ...aiStudioOriginField,
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
    'limits.ocr_usd': {
      type: 'number',
      props: {label: 'OCR USD', suffix: '$' },
    },
    'limits.ocr_pages': {
      type: 'number',
      props: {label: 'OCR Pages', suffix: 'pages' },
    },
    'limits.decision_tokens': {
      type: 'number',
      props: {label: 'Decision Tokens', suffix: 'tokens' },
    },
    'limits.decision_usd': {
      type: 'number',
      props: {label: 'Decision USD', suffix: '$' },
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
    'scope.always_apply_rules': {
      type: 'bool',
      props: {
        label: 'Always apply rules first',
        help: 'if not enabled, the budget will be applied if any predicate of the scope is true and will evaluate rules after everything else. But this flag let you always apply them and then try to apply all the other scope predicates'
      },
    },
    'scope.rules': {
      type: 'array',
      props: {
        label: 'Rules',
        component: RuleComponent,
      },
    },
    'scope.partition_by': {
      type: 'string',
      props: {
        label: 'Count apart by',
        placeholder: '${user.email || apikey.metadata.ai_studio_owner}',
        help: 'An expression of the Otoroshi expression language: the budget counts apart each value it takes, each one having the whole limit. ${user.email || apikey.metadata.ai_studio_owner} counts a person (their chats and the calls of the api keys they own), ${consumer.id} each user and each api key, ${apikey.metadata.team} each team. A call the expression finds nothing for is not counted by this budget: give a default value to count those together (${apikey.metadata.team:none}). Values are compared in lower case. Never use a value the caller chooses (a header, the body of the request): each new value would start with the whole limit. In a cluster, create such a budget once every node runs a version that knows it.',
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
    'action_on_exceed.error_message': {
      type: 'string',
      props: {label: 'Error message', placeholder: 'budget exceeded', help: 'Error returned to the client when the budget blocks a call' },
    },
    'extensions.enabled': {
      type: 'bool',
      props: {
        label: 'Allow extensions',
        help: 'The people this budget counts can extend their own limit, by the amounts below, for the current cycle. On a budget that does not count its consumers apart, only an administrator can, as the limit is shared.',
      },
    },
    'extensions.max_per_cycle': {
      type: 'number',
      props: {label: 'Extensions per cycle', help: 'How many extensions can be taken in a cycle, by each consumer when the budget counts them apart (1 by default)'},
    },
    ...Object.fromEntries(BUDGET_COUNTERS.map(([name, label, unit]) => [`extensions.limits.${name}`, {
      type: 'number',
      props: {label: `Extension of ${label}`, suffix: unit === 'toks.' ? 'tokens' : unit},
    }])),
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
    aiStudioColumn,
  ];


  formFlow = (state) => {
    return [
      'ai_studio_origin', '_loc', 
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
      'limits.ocr_usd',
      'limits.ocr_pages',
      'limits.decision_tokens',
      'limits.decision_usd',
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
      'scope.always_apply_rules',
      'scope.rules_match_mode',
      'scope.rules',
      'scope.partition_by',
      '>>>Action on exceed',
      'action_on_exceed.mode',
      'action_on_exceed.alert_on_exceed',
      'action_on_exceed.alert_on_almost_exceed',
      'action_on_exceed.alert_on_almost_exceed_percentage',
      'action_on_exceed.error_message',
      '>>>Extensions',
      'extensions.enabled',
      'extensions.max_per_cycle',
      ...BUDGET_COUNTERS.map(([name]) => `extensions.limits.${name}`),
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
            "ocr_usd" : null,
            "ocr_pages" : null,
            "decision_tokens" : null,
            "decision_usd" : null,
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
            "always_apply_rules": false,
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