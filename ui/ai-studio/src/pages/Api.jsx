import { useEffect, useState } from 'react';
import { useWorkspace } from '../App';
import { Badge, ErrorAlert, PageHeader, Readonly, useAsync } from '../components/ui';
import { Icon } from '../components/icons';
import { Snippets } from '../components/snippets';
import { API_ENDPOINTS, API_GROUPS, DOCS_URL, endpointById, servingOf, snippetsOf } from '../lib/apidocs';
import { KIND_LABELS, labelOfModelId } from '../lib/modelmeta';
import { listWorkspaceModels } from '../lib/models';
import { Link, useQueryState } from '../lib/router';

// what to do for the workspace to serve an endpoint it does not serve yet
function Missing({ workspace, endpoint }) {
  const base = `/workspaces/${workspace.id}`;
  if (endpoint.needs.mcp) {
    return (
      <div className="alert info">
        The MCP server of this workspace is off. <Link className="link" to={`${base}/mcp-server`}>Enable it</Link> to serve this endpoint.
      </div>
    );
  }
  const capability = KIND_LABELS[endpoint.needs.modality] || endpoint.needs.modality;
  return (
    <div className="alert info">
      No provider of this workspace serves it yet. <Link className="link" to={`${base}/providers`}>Connect a provider</Link> with the {capability} capability, or turn
      it on for a provider you already have.
    </div>
  );
}

function Endpoint({ workspace, endpoint, serving, loaded, open, onToggle, lang, onLang }) {
  const missing = loaded && !serving.available;
  return (
    <div className={`api-endpoint ${open ? 'open' : ''}`} id={`endpoint-${endpoint.id}`}>
      <button type="button" className="api-endpoint-head" onClick={onToggle} aria-expanded={open}>
        <span className={`api-method ${endpoint.method.toLowerCase()}`}>{endpoint.method}</span>
        <span className="mono api-path">{endpoint.path}</span>
        <span className="api-text">
          <strong>{endpoint.title}</strong>
          <span className="muted">{endpoint.summary}</span>
        </span>
        <span className="api-state">{missing && <Badge title="No provider of this workspace serves this endpoint yet">Not served yet</Badge>}</span>
        <Icon name="chevron" />
      </button>
      {open && (
        <div className="api-endpoint-body">
          {missing && <Missing workspace={workspace} endpoint={endpoint} />}
          {endpoint.features.length > 0 && (
            <ul className="api-features">
              {endpoint.features.map((f) => (
                <li key={f}>{f}</li>
              ))}
            </ul>
          )}
          <Snippets snippets={snippetsOf(endpoint, workspace, serving.model)} lang={lang} onLang={onLang} />
          <div className="row between small">
            <span className="muted">
              {serving.model && serving.available ? (
                <>
                  Shown with <span className="mono" title={serving.model}>{labelOfModelId(serving.model)}</span>.{' '}
                  <Link className="link" to={`/workspaces/${workspace.id}/models`}>Pick another model</Link>
                </>
              ) : null}
            </span>
            <a className="link" href={`${DOCS_URL}${endpoint.docs}`} target="_blank" rel="noreferrer">
              Full reference
            </a>
          </div>
        </div>
      )}
    </div>
  );
}

const APPLIES = [
  { page: 'keys', icon: 'key', title: 'API key', text: 'Its quotas, its expiration and the models it may call.' },
  { page: 'guardrails', icon: 'shield', title: 'Guardrails', text: 'Prompts and answers are checked on their way through.' },
  { page: 'routing', icon: 'route', title: 'Routing', text: 'Fallbacks and routers choose the provider that answers.' },
  { page: 'credits', icon: 'wallet', title: 'Credits', text: 'Budgets stop the calls once their limit is reached.' },
  { page: 'logs', icon: 'list', title: 'Logs', text: 'Every call is kept, with its tokens, its cost and its latency.' },
];

export function ApiPage() {
  const { workspace } = useWorkspace();
  const [query, setQuery] = useQueryState();
  const asked = endpointById(query.endpoint);
  const [open, setOpen] = useState(asked ? asked.id : API_ENDPOINTS[0].id);
  const [lang, setLang] = useState('curl');
  const models = useAsync(() => listWorkspaceModels(workspace), [workspace.id]);
  const loaded = !!models.data;
  const base = `/workspaces/${workspace.id}`;

  // a link to one endpoint lands on it
  useEffect(() => {
    if (!asked) return;
    const el = document.getElementById(`endpoint-${asked.id}`);
    if (el) el.scrollIntoView({ block: 'center' });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const toggle = (id) => {
    const next = open === id ? null : id;
    setOpen(next);
    setQuery({ endpoint: next });
  };

  const example = servingOf(API_ENDPOINTS[0], workspace, models.data).model;

  return (
    <div className="content">
      <PageHeader title="API" description="One base URL and one API key for everything this workspace serves, in the formats your SDKs already speak.">
        <a className="btn" href={`${DOCS_URL}/llm-gateway/openai-compat-api`} target="_blank" rel="noreferrer">
          <Icon name="external" />
          Full reference
        </a>
      </PageHeader>
      <ErrorAlert error={models.error} />

      <div className="grid cols-3 mb api-connect">
        <div className="card">
          <h3>Base URL</h3>
          <Readonly value={workspace.base_url} />
          <p className="muted small">Every endpoint below is a path under it. An OpenAI SDK only needs this URL and a key.</p>
        </div>
        <div className="card">
          <h3>Authentication</h3>
          <Readonly value="Authorization: Bearer $API_KEY" />
          <p className="muted small">
            An API key of this workspace, sent as a bearer token. <Link className="link" to={`${base}/keys`}>Get an API key</Link>
          </p>
        </div>
        <div className="card">
          <h3>Model</h3>
          <Readonly value={example} />
          <p className="muted small">
            A request names its model by its id, the one <Link className="link" to={`${base}/models`}>Models</Link> lets you copy. It starts with the name of the provider when
            several of them serve that kind of model.
          </p>
        </div>
      </div>

      {API_GROUPS.map((group) => (
        <div key={group.id} className="card flush api-group">
          <div className="api-group-head">
            <h2>{group.label}</h2>
            <p>{group.description}</p>
          </div>
          {API_ENDPOINTS.filter((e) => e.group === group.id).map((endpoint) => (
            <Endpoint
              key={endpoint.id}
              workspace={workspace}
              endpoint={endpoint}
              serving={servingOf(endpoint, workspace, models.data)}
              loaded={loaded}
              open={open === endpoint.id}
              onToggle={() => toggle(endpoint.id)}
              lang={lang}
              onLang={setLang}
            />
          ))}
        </div>
      ))}

      <div className="card">
        <h2>On every call</h2>
        <p className="muted" style={{ margin: '4px 0 14px' }}>
          What this workspace decides applies to your applications without a line of code on their side.
        </p>
        <div className="api-applies">
          {APPLIES.map((a) => (
            <Link key={a.page} to={`${base}/${a.page}`} className="api-apply">
              <span className="row">
                <Icon name={a.icon} />
                <strong>{a.title}</strong>
              </span>
              <span className="muted small">{a.text}</span>
            </Link>
          ))}
        </div>
      </div>
    </div>
  );
}
