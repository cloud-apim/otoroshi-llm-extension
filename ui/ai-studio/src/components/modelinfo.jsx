import { Badge, CopyButton } from './ui';
import {
  capabilitiesOf,
  chatUnavailableReason,
  completionPrice,
  contextOf,
  ENDPOINT_LABELS,
  fmtPrice,
  fmtTokens,
  hasCost,
  inputsOf,
  KIND_LABELS,
  kindsOf,
  metaOf,
  MODALITY_NAMES,
  pricedButNotBilled,
  outputsOf,
  perMillion,
  promptPrice,
} from '../lib/modelmeta';

// the prices that are not per token: per image, per second of audio or video, per character
const UNIT_PRICES = {
  image: { factor: 1, label: 'an image' },
  image_output: { factor: 1, label: 'an image' },
  input_second: { factor: 60, label: 'a minute' },
  output_second: { factor: 60, label: 'a minute' },
  input_character: { factor: 1000000, label: '/ 1M characters' },
  video_second: { factor: 1, label: 'a second' },
};

const PRICING_LABELS = {
  prompt: 'Input',
  completion: 'Output',
  input_cache_read: 'Cached input',
  input_cache_write: 'Cache write',
  internal_reasoning: 'Reasoning',
  audio: 'Audio input',
  audio_output: 'Audio output',
  image: 'Image input',
  image_output: 'Image output',
  input_second: 'Audio input',
  output_second: 'Audio output',
  input_character: 'Text input',
  video_second: 'Video output',
};


// the prices that are not per token, in the unit people compare them in
function unitPrice(key, value) {
  const unit = UNIT_PRICES[key];
  return unit ? `${fmtPrice(Number(value) * unit.factor)} ${unit.label}` : `${fmtPrice(perMillion(value))} / 1M tokens`;
}

export function ModelLabels({ model, compact = false }) {
  const meta = metaOf(model);
  const caps = capabilitiesOf(model);
  const kinds = kindsOf(model);
  const unavailable = chatUnavailableReason(model);
  const endpoints = meta.endpoints || [];
  return (
    <div className="badges">
      {kinds.map((k) => (
        <Badge key={k} kind="accent">
          {KIND_LABELS[k] || k}
        </Badge>
      ))}
      {(compact ? caps.slice(0, 4) : caps).map((c) => (
        <Badge key={c.id} title={c.title}>
          {c.label}
        </Badge>
      ))}
      {compact && caps.length > 4 && <Badge title={caps.slice(4).map((c) => c.label).join(', ')}>+{caps.length - 4}</Badge>}
      {model.modality === 'text' && kinds.includes('text') && unavailable && endpoints.length > 0 && (
        <Badge kind="info" title={unavailable}>
          {endpoints.map((e) => ENDPOINT_LABELS[e] || e).join(' / ')} only
        </Badge>
      )}
      {meta.status === 'deprecated' && <Badge kind="negative">Deprecated</Badge>}
      {meta.status === 'beta' && <Badge kind="info">Beta</Badge>}
      {meta.has_cost === false && (
        <Badge
          kind="warning"
          title={
            pricedButNotBilled(model)
              ? 'Its price is known, but in a unit the gateway cannot measure — the seconds of audio a voice produces, for instance. Its calls do not count against dollar budgets.'
              : 'Cost tracking knows no price for this model: its calls do not count against dollar budgets'
          }
        >
          {pricedButNotBilled(model) ? 'Not billed' : 'No known price'}
        </Badge>
      )}
    </div>
  );
}

export function PriceSummary({ model }) {
  const input = promptPrice(model);
  const output = completionPrice(model);
  if (input === null && output === null) {
    const pricing = metaOf(model).pricing || {};
    const units = Object.keys(pricing).filter((k) => UNIT_PRICES[k]);
    if (units.length === 0) return null;
    return <span title="This model is not billed per token">{[...new Set(units.map((k) => unitPrice(k, pricing[k])))].join(' · ')}</span>;
  }
  if (input === 0 && output === 0) return <span>Free</span>;
  return (
    <span title="Price per million tokens">
      {fmtPrice(input)} in · {fmtPrice(output)} out <span className="faint">/ 1M</span>
    </span>
  );
}

export function ModelFacts({ model, provider = true }) {
  const meta = metaOf(model);
  const context = contextOf(model);
  const output = meta.limits && meta.limits.output;
  const inputs = inputsOf(model);
  const outputs = outputsOf(model);
  return (
    <div className="meta">
      {provider && (
        <span>
          <Badge kind="accent">{model.provider}</Badge>
        </span>
      )}
      {context && <span title="Context window, in tokens">{fmtTokens(context)} context</span>}
      {output && <span title="Maximum output, in tokens">{fmtTokens(output)} output</span>}
      {meta.pricing && <PriceSummary model={model} />}
      {inputs.length > 0 && outputs.length > 0 && (
        <span title="What the model takes and gives">
          {inputs.map((i) => MODALITY_NAMES[i] || i).join(', ')} → {outputs.map((o) => MODALITY_NAMES[o] || o).join(', ')}
        </span>
      )}
      {model.model && model.model !== model.id && <span className="mono">{model.model}</span>}
    </div>
  );
}

function Row({ label, children }) {
  return (
    <>
      <div className="muted">{label}</div>
      <div>{children}</div>
    </>
  );
}

function yesNo(value) {
  if (value === undefined || value === null) return <span className="faint">Unknown</span>;
  return value ? 'Yes' : 'No';
}

export function ModelDetails({ model, baseUrl }) {
  const meta = metaOf(model);
  const caps = meta.capabilities || {};
  const pricing = meta.pricing || {};
  const limits = meta.limits || {};
  const sources = meta.sources || {};
  const unavailable = chatUnavailableReason(model);
  const reasoningOptions = caps.reasoning_options || [];
  const kinds = kindsOf(model);
  const endpoint = (meta.endpoints || [])[0];
  const path = endpoint === 'responses' ? '/responses' : endpoint === 'embeddings' ? '/embeddings' : '/chat/completions';
  const body =
    path === '/embeddings'
      ? { model: model.id, input: 'Hello!' }
      : path === '/responses'
      ? { model: model.id, input: 'Hello!' }
      : { model: model.id, messages: [{ role: 'user', content: 'Hello!' }] };
  const snippet = `curl ${baseUrl}${path} \\\n  -H "Authorization: Bearer $API_KEY" \\\n  -H "Content-Type: application/json" \\\n  -d '${JSON.stringify(body)}'`;
  return (
    <div className="stack">
      <div className="row between">
        <span className="mono truncate">{model.id}</span>
        <CopyButton text={model.id} />
      </div>
      <ModelLabels model={model} />

      <div className="details-section">
        <h3>Usage</h3>
        <div className="kv">
          <Row label="Provider">{model.provider}</Row>
          <Row label="Provider model">
            <span className="mono">{model.model || model.id}</span>
          </Row>
          <Row label="Types">{kinds.map((k) => KIND_LABELS[k] || k).join(', ') || <span className="faint">None</span>}</Row>
          <Row label="API">
            {meta.openai_compatible === true ? (
              (meta.endpoints || []).map((e) => ENDPOINT_LABELS[e] || e).join(', ') || 'OpenAI compatible'
            ) : meta.openai_compatible === false ? (
              <span title="The gateway translates the OpenAI requests for this provider">Native provider api, translated by the gateway</span>
            ) : (
              <span className="faint">Served by the providers of a router</span>
            )}
          </Row>
          {model.modality === 'text' && <Row label="Studio chat">{unavailable ? <span className="faint">{unavailable}</span> : 'Available'}</Row>}
          <Row label="Cost tracking">
            {hasCost(model) ? (
              'Calls are priced and count against dollar budgets'
            ) : pricedButNotBilled(model) ? (
              <span className="warning-text">Priced in a unit the gateway cannot measure, so calls are not billed</span>
            ) : (
              <span className="warning-text">No known price, calls are not priced</span>
            )}
          </Row>
        </div>
        {kinds.includes('text') || kinds.includes('embedding') ? <pre className="mt">{snippet}</pre> : null}
      </div>

      <div className="details-section">
        <h3>Capabilities</h3>
        <div className="kv">
          <Row label="Reasoning">
            {yesNo(caps.reasoning)}
            {reasoningOptions.length > 0 && (
              <div className="faint small">
                {reasoningOptions
                  .map((o) => (o.values ? `${o.type}: ${o.values.join(', ')}` : o.type === 'budget_tokens' ? 'token budget' : o.type))
                  .join(' · ')}
              </div>
            )}
          </Row>
          <Row label="Tool calling">{yesNo(caps.tool_call)}</Row>
          <Row label="Structured output">{yesNo(caps.structured_output)}</Row>
          <Row label="Temperature">{yesNo(caps.temperature)}</Row>
          <Row label="Attachments">{yesNo(caps.attachment)}</Row>
          <Row label="Prompt caching">{yesNo(caps.prompt_caching)}</Row>
          {caps.web_search !== undefined && <Row label="Web search">{yesNo(caps.web_search)}</Row>}
          <Row label="Input">{inputsOf(model).map((i) => MODALITY_NAMES[i] || i).join(', ') || <span className="faint">Unknown</span>}</Row>
          <Row label="Output">{outputsOf(model).map((o) => MODALITY_NAMES[o] || o).join(', ') || <span className="faint">Unknown</span>}</Row>
        </div>
      </div>

      <div className="details-section">
        <h3>Limits</h3>
        <div className="kv">
          <Row label="Context window">{limits.context ? `${fmtTokens(limits.context)} tokens` : <span className="faint">Unknown</span>}</Row>
          {limits.input && <Row label="Max input">{fmtTokens(limits.input)} tokens</Row>}
          <Row label="Max output">{limits.output ? `${fmtTokens(limits.output)} tokens` : <span className="faint">Unknown</span>}</Row>
          {meta.knowledge && <Row label="Knowledge cutoff">{meta.knowledge}</Row>}
          {meta.deprecation_date && <Row label="Deprecation">{meta.deprecation_date}</Row>}
        </div>
      </div>

      <div className="details-section">
        <h3>Pricing</h3>
        {Object.keys(pricing).length === 0 ? (
          <p className="muted">No price is known for this model.</p>
        ) : (
          <div className="kv">
            {Object.keys(pricing).map((k) => (
              <Row key={k} label={PRICING_LABELS[k] || k}>
                {unitPrice(k, pricing[k])}
              </Row>
            ))}
          </div>
        )}
      </div>

      {(sources.catalog || sources.pricing) && (
        <div className="details-section">
          <h3>Sources</h3>
          <div className="kv">
            {sources.catalog && (
              <Row label="Description">
                models.dev, <span className="mono">{sources.catalog.model}</span>
                {sources.catalog.provider ? ` on ${sources.catalog.provider}` : ''} <span className="faint">({sources.catalog.match} match)</span>
              </Row>
            )}
            {sources.pricing && (
              <Row label="Price">
                {sources.pricing.source === 'price-table' ? 'Price table' : sources.pricing.source}, <span className="mono">{sources.pricing.model}</span>
              </Row>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
