// The guardrail kinds offered by the studio, with a form description of their config.
// Field kinds: lines (string[]), text, number, checks (string[] among options), select, provider, moderation_model

export const MODERATION_CATEGORIES = [
  'hate',
  'hate/threatening',
  'harassment',
  'harassment/threatening',
  'self-harm',
  'self-harm/intent',
  'self-harm/instructions',
  'sexual',
  'sexual/minors',
  'violence',
  'violence/graphic',
  'profanity',
];
export const PERSONAL_INFORMATIONS = ['EMAIL_ADDRESS', 'PHONE_NUMBER', 'LOCATION_ADDRESS', 'NAME', 'IP_ADDRESS', 'CREDIT_CARD', 'SSN'];
export const SECRETS = ['APIKEYS', 'PASSWORDS', 'TOKENS', 'JWT_TOKENS', 'PRIVATE_KEYS', 'HUGE_RANDOM_VALUES'];
export const RAMPART_ENTITIES = [
  'GIVEN_NAME',
  'SURNAME',
  'PHONE',
  'TAX_ID',
  'BANK_ACCOUNT',
  'ROUTING_NUMBER',
  'GOVERNMENT_ID',
  'PASSPORT',
  'DRIVERS_LICENSE',
  'BUILDING_NUMBER',
  'STREET_NAME',
  'SECONDARY_ADDRESS',
  'EMAIL',
  'URL',
  'SSN',
  'CREDIT_CARD',
  'IP_ADDRESS',
];

const validator = { name: 'provider', label: 'Validation provider', kind: 'provider', hint: 'Text provider of this workspace asked to judge the messages.' };
const validatorModel = { name: 'model', label: 'Model', kind: 'text', hint: 'Leave empty to use the default model of the provider. A provider can judge its own messages with another of its models.' };
const errMsg = { name: 'err_msg', label: 'Refusal message', kind: 'text', hint: 'Returned when the guardrail blocks a message.' };

export const GUARDRAIL_KINDS = [
  {
    id: 'regex',
    label: 'Pattern matching',
    description: 'Block requests or responses matching regular expressions (prompt-injection phrases, forbidden topics…). Free, no added latency.',
    fields: [
      { name: 'deny', label: 'Deny patterns', kind: 'lines', hint: 'One regex per line, matching the whole message: (?is).*ignore (all|previous) instructions.*' },
      { name: 'allow', label: 'Allow patterns', kind: 'lines', hint: 'Optional: only messages matching one of them pass.' },
    ],
    summary: (c) => `${(c.deny || []).length} deny · ${(c.allow || []).length} allow patterns`,
  },
  {
    id: 'contains',
    label: 'Forbidden words',
    description: 'Block messages containing (or missing) some words. Free, no added latency.',
    defaults: { operation: 'contains_none', values: [] },
    fields: [
      {
        name: 'operation',
        label: 'Rule',
        kind: 'select',
        options: [
          { value: 'contains_none', label: 'Must contain none of the words' },
          { value: 'contains_any', label: 'Must contain at least one word' },
          { value: 'contains_all', label: 'Must contain every word' },
        ],
      },
      { name: 'values', label: 'Words', kind: 'lines', hint: 'One word or expression per line.' },
    ],
    summary: (c) => `${(c.values || []).length} words · ${(c.operation || '').replace('_', ' ')}`,
  },
  {
    id: 'characters',
    label: 'Message length',
    description: 'Reject messages that are too short or too long, in characters.',
    fields: [
      { name: 'min', label: 'Min characters', kind: 'number' },
      { name: 'max', label: 'Max characters', kind: 'number' },
    ],
    summary: (c) => `${c.min ?? 0} – ${c.max ?? '∞'} characters`,
  },
  {
    id: 'rampart',
    label: 'PII redaction',
    description: 'Detects personal data with a local model and redacts or blocks it before it reaches the provider. No external call.',
    defaults: { action: 'redact', min_score: 0.4, entities: RAMPART_ENTITIES },
    fields: [
      {
        name: 'action',
        label: 'Action',
        kind: 'select',
        options: [
          { value: 'redact', label: 'Redact the values' },
          { value: 'deny', label: 'Block the message' },
        ],
      },
      { name: 'min_score', label: 'Minimum confidence', kind: 'number', step: 0.05 },
      { name: 'entities', label: 'Entities', kind: 'checks', options: RAMPART_ENTITIES },
    ],
    summary: (c) => `${c.action || 'redact'} · ${(c.entities || []).length} entities`,
  },
  {
    id: 'prompt_injection',
    label: 'Prompt injection',
    description: 'Asks a model to score how likely the message is an injection attempt.',
    defaults: { max_injection_score: 90 },
    fields: [validator, validatorModel, { name: 'max_injection_score', label: 'Max injection score (0-100)', kind: 'number' }, errMsg],
    llm: true,
  },
  {
    id: 'pif',
    label: 'Personal information',
    description: 'Asks a model to detect personal information in the messages.',
    defaults: { pif_items: PERSONAL_INFORMATIONS },
    fields: [validator, validatorModel, { name: 'pif_items', label: 'Information kinds', kind: 'checks', options: PERSONAL_INFORMATIONS }, errMsg],
    llm: true,
  },
  {
    id: 'secrets_leakage',
    label: 'Secrets leakage',
    description: 'Asks a model to detect api keys, passwords, tokens or private keys.',
    defaults: { secrets_leakage_items: SECRETS },
    fields: [validator, validatorModel, { name: 'secrets_leakage_items', label: 'Secret kinds', kind: 'checks', options: SECRETS }, errMsg],
    llm: true,
  },
  {
    id: 'moderation',
    label: 'Language moderation',
    description: 'Asks a model to detect hate, harassment, violence, sexual content…',
    defaults: { moderation_items: MODERATION_CATEGORIES },
    fields: [validator, validatorModel, { name: 'moderation_items', label: 'Categories', kind: 'checks', options: MODERATION_CATEGORIES }, errMsg],
    llm: true,
  },
  { id: 'toxic_language', label: 'Toxic language', description: 'Asks a model to detect toxic language.', fields: [validator, validatorModel, errMsg], llm: true },
  { id: 'gibberish', label: 'Gibberish', description: 'Asks a model to detect meaningless content.', fields: [validator, validatorModel, errMsg], llm: true },
  {
    id: 'moderation_model',
    label: 'Moderation model',
    description: 'Uses a moderation model of this workspace (e.g. omni-moderation) to flag harmful content.',
    fields: [
      { name: 'moderation_model', label: 'Moderation model', kind: 'moderation_model' },
      { name: 'model', label: 'Model', kind: 'text', hint: 'Leave empty to use the default model of the moderation model.' },
    ],
  },
  {
    id: 'webhook',
    label: 'Webhook',
    description: 'Sends the messages to your own service, which answers {"result": true|false}.',
    fields: [{ name: 'url', label: 'URL', kind: 'text' }],
    summary: (c) => c.url || 'no url',
  },
];

export function kindOf(id) {
  return GUARDRAIL_KINDS.find((k) => k.id === id) || { id, label: id, description: '', fields: [] };
}

export function summaryOf(item, providers) {
  const kind = kindOf(item.id);
  if (kind.summary) return kind.summary(item.config || {});
  if (kind.llm) {
    const p = providers.find((x) => x.id === (item.config || {}).provider);
    return p ? `judged by ${p.name}${(item.config || {}).model ? ` (${item.config.model})` : ''}` : 'no validation model';
  }
  if (item.id === 'moderation_model') return (item.config || {}).moderation_model ? 'moderation model set' : 'no moderation model';
  return '';
}
