import { useState } from 'react';
import { useWorkspace } from '../App';
import { Badge, CopyButton, useAsync } from '../components/ui';
import { Snippets } from '../components/snippets';
import { WeekUsage } from '../components/usage';
import { QUICKSTARTS, servingOf, snippetsOf } from '../lib/apidocs';
import { backend } from '../lib/backend';
import { modelLabel } from '../lib/modelmeta';
import { Link, useRouter } from '../lib/router';
import { listWorkspaceModels } from '../lib/models';

export function OverviewPage() {
  const { workspace } = useWorkspace();
  const { navigate } = useRouter();
  const [lang, setLang] = useState('curl');
  const [quickstart, setQuickstart] = useState(QUICKSTARTS[0].id);
  const data = useAsync(async () => ({ providers: await backend.run('providers.list', workspace.id) }), [workspace.id]);
  const models = useAsync(() => listWorkspaceModels(workspace), [workspace.id]);

  const providers = (data.data && data.data.providers) || [];
  const modelList = (models.data && models.data.models) || [];
  // the quickstart shows the endpoints this workspace serves, the chat one whatever happens
  const quickstarts = QUICKSTARTS.filter((e, idx) => idx === 0 || servingOf(e, workspace, models.data).available);
  const endpoint = quickstarts.find((e) => e.id === quickstart) || quickstarts[0];
  const snippets = snippetsOf(endpoint, workspace, servingOf(endpoint, workspace, models.data).model);
  const apiPath = `/workspaces/${workspace.id}/api`;

  return (
    <div className="content">
      <div className="hero">
        <h1>One API for every model</h1>
        <p>
          Route requests to {providers.length} provider{providers.length === 1 ? '' : 's'} and {modelList.length} model{modelList.length === 1 ? '' : 's'} through{' '}
          <span className="hero-url">
            <code>{workspace.base_url.replace(/^https?:\/\//, '')}</code>
            <CopyButton text={workspace.base_url} title="Copy the base URL" />
          </span>
        </p>
        <div className="row">
          <button className="btn primary" onClick={() => navigate(`/workspaces/${workspace.id}/chat`)}>
            Open the chat
          </button>
          <button className="btn" onClick={() => navigate(`/workspaces/${workspace.id}/models`)}>
            Browse models
          </button>
          <button className="btn" onClick={() => navigate(`/workspaces/${workspace.id}/keys`)}>
            Get an API key
          </button>
        </div>
      </div>

      {data.data && providers.length === 0 && (
        <div className="alert info mb">
          This workspace has no provider yet. <Link className="link" to={`/workspaces/${workspace.id}/providers`}>Connect a provider</Link> to start routing requests.
        </div>
      )}

      <div className="grid cols-3 mb">
        <div className="card">
          <h3>1 · Get a key</h3>
          <p className="muted mt" style={{ marginTop: 6 }}>
            Create an API key in this workspace. Set quotas and budgets per key.
          </p>
        </div>
        <div className="card">
          <h3>2 · Pick a model</h3>
          <p className="muted" style={{ marginTop: 6 }}>
            Any model exposed by your connected providers, addressed by its id.
          </p>
        </div>
        <div className="card">
          <h3>3 · Call the API</h3>
          <p className="muted" style={{ marginTop: 6 }}>
            Drop-in OpenAI compatibility: change the base URL, keep your SDK.{' '}
            <Link className="link" to={apiPath}>See what the API serves</Link>
          </p>
        </div>
      </div>

      <div className="card mb">
        <div className="card-title">
          <h2>Quickstart</h2>
          <div className="row">
            {quickstarts.length > 1 && (
              <select className="sm" value={endpoint.id} onChange={(e) => setQuickstart(e.target.value)} title="What to call">
                {quickstarts.map((e) => (
                  <option key={e.id} value={e.id}>
                    {e.quickstart}
                  </option>
                ))}
              </select>
            )}
            <CopyButton text={workspace.base_url} className="btn sm" label="Copy URL" title="Copy the base URL" />
            <CopyButton text={() => snippets[snippets[lang] ? lang : 'curl']} className="btn sm" label="Copy code" title="Copy the code" />
          </div>
        </div>
        <Snippets snippets={snippets} lang={lang} onLang={setLang} copy={false} />
        <p className="muted small" style={{ marginTop: 10 }}>
          <span className="mono">
            {endpoint.method} {endpoint.path}
          </span>{' '}
          · {endpoint.summary}{' '}
          <Link className="link" to={`${apiPath}?endpoint=${endpoint.id}`}>
            See everything the API serves
          </Link>
        </p>
      </div>

      <WeekUsage workspace={workspace} href={`/workspaces/${workspace.id}/activity`} />

      <div className="page-header" style={{ marginTop: 28, marginBottom: 14 }}>
        <div>
          <h2>Featured models</h2>
          <p>Models available in this workspace.</p>
        </div>
        <button className="btn sm" onClick={() => navigate(`/workspaces/${workspace.id}/models`)}>
          See all
        </button>
      </div>
      <div className="grid cols-3">
        {modelList.slice(0, 6).map((m) => (
          <div key={m.id} className="card tight row between">
            <span className="truncate" title={m.id}>
              {modelLabel(m)}
            </span>
            <Badge kind="accent">{m.provider}</Badge>
          </div>
        ))}
        {models.data && modelList.length === 0 && <div className="muted">No model available yet.</div>}
      </div>
    </div>
  );
}
