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
        React.createElement("div", { className: "col-sm-10", style: { lineHeight: '33px' } }, `${remaining.remaining_usd} $`),
      ),
      React.createElement("div", { className: "row mb-3" },
        React.createElement("label", { className: "col-xs-12 col-sm-2 col-form-label" }, 'Consumed USD'),
        React.createElement("div", { className: "col-sm-10", style: { lineHeight: '33px' } }, `${remaining.consumed_usd} $`),
      ),
      React.createElement("div", { className: "row mb-3" },
        React.createElement("label", { className: "col-xs-12 col-sm-2 col-form-label" }, 'Remaining tokens'),
        React.createElement("div", { className: "col-sm-10", style: { lineHeight: '33px' } }, `${remaining.remaining_tokens} toks.`),
      ),
      React.createElement("div", { className: "row mb-3" },
        React.createElement("label", { className: "col-xs-12 col-sm-2 col-form-label" }, 'Consumed tokens'),
        React.createElement("div", { className: "col-sm-10", style: { lineHeight: '33px' } }, `${remaining.consumed_tokens} toks.`),
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

  console.log(props);

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
      type: 'boolean',
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
    'limits.tokens': {
      type: 'number',
      props: {label: 'Max Tokens', suffix: 'tokens' },
    },
    'limits.usd': {
      type: 'number',
      props: {label: 'Max USD', suffix: '$' },
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
    'scope.extract_from_user_auth_module_meta': {
      type: 'bool',
      props: {label: 'Extract from user auth module meta'},
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
      'renewals',
      '<<<Limits',
      'limits.tokens',
      'limits.usd',
      'limits.remaining',
      '>>>Scope',
      'scope.extract_from_apikey_meta',
      'scope.extract_from_apikey_group_meta',
      'scope.extract_from_user_meta',
      'scope.extract_from_user_auth_module_meta',
      'scope.rules_match_mode',
      'scope.rules',
      '>>>Action on exceed',
      'action_on_exceed.mode',
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
          "end_at" : moment().add(30, 'days').format('YYYY-MM-DDTHH:mm:ss.SSS'),
          "duration" : {
            "value" : 30,
            "unit" : "day"
          },
          "renewals" : null,
          "limits" : {
            "tokens" : 10000000,
            "usd" : 200
          },
          "scope" : {
            "extract_from_apikey_meta" : true,
            "extract_from_apikey_group_meta" : true,
            "extract_from_user_meta" : true,
            "extract_from_user_auth_module_meta" : true,
            "rules" : [ ],
            "rules_match_mode" : "all"
          },
          "action_on_exceed" : {
            "mode" : "block"
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