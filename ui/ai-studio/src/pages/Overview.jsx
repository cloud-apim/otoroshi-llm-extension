import { useState } from 'react';
import { useWorkspace } from '../App';
import { Badge, CopyButton, Tabs, useAsync } from '../components/ui';
import { WeekUsage } from '../components/usage';
import { Resources, workspaceFilter } from '../lib/entities';
import { Link, useRouter } from '../lib/router';
import { listWorkspaceModels } from '../lib/models';

function snippets(baseUrl, model) {
  return {
    curl: `curl ${baseUrl}/chat/completions \\
  -H "Authorization: Bearer $API_KEY" \\
  -H "Content-Type: application/json" \\
  -d '{
    "model": "${model}",
    "messages": [{"role": "user", "content": "What is the meaning of life?"}]
  }'`,
    python: `from openai import OpenAI

client = OpenAI(
    base_url="${baseUrl}",
    api_key="$API_KEY",
)

completion = client.chat.completions.create(
    model="${model}",
    messages=[{"role": "user", "content": "What is the meaning of life?"}],
)
print(completion.choices[0].message.content)`,
    typescript: `import OpenAI from 'openai';

const client = new OpenAI({
  baseURL: '${baseUrl}',
  apiKey: process.env.API_KEY,
});

const completion = await client.chat.completions.create({
  model: '${model}',
  messages: [{ role: 'user', content: 'What is the meaning of life?' }],
});
console.log(completion.choices[0].message.content);`,
  };
}

export function OverviewPage() {
  const { workspace } = useWorkspace();
  const { navigate } = useRouter();
  const [lang, setLang] = useState('curl');
  const filter = workspaceFilter(workspace.id);
  const data = useAsync(async () => {
    const [providers, keys] = await Promise.all([Resources.providers.list(filter), Resources.apikeys.list(filter)]);
    return { providers, keys };
  }, [workspace.id]);
  const models = useAsync(() => listWorkspaceModels(workspace), [workspace.id]);

  const providers = (data.data && data.data.providers) || [];
  const modelList = (models.data && models.data.models) || [];
  const exampleModel = (modelList[0] && modelList[0].id) || 'provider/model';
  const code = snippets(workspace.baseUrl, exampleModel)[lang];

  return (
    <div className="content">
      <div className="hero">
        <h1>One API for every model</h1>
        <p>
          Route requests to {providers.length} provider{providers.length === 1 ? '' : 's'} and {modelList.length} model{modelList.length === 1 ? '' : 's'} through{' '}
          <code>{workspace.baseUrl.replace(/^https?:\/\//, '')}</code>.
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
            Drop-in OpenAI compatibility: change the base URL, keep your SDK.
          </p>
        </div>
      </div>

      <div className="card mb">
        <div className="card-title">
          <h2>Quickstart</h2>
          <CopyButton text={code} className="btn sm" label="Copy" />
        </div>
        <Tabs
          value={lang}
          onChange={setLang}
          tabs={[
            { value: 'curl', label: 'curl' },
            { value: 'python', label: 'Python' },
            { value: 'typescript', label: 'TypeScript' },
          ]}
        />
        <pre>
          <code>{code}</code>
        </pre>
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
              {m.id}
            </span>
            <Badge kind="accent">{m.provider}</Badge>
          </div>
        ))}
        {models.data && modelList.length === 0 && <div className="muted">No model available yet.</div>}
      </div>
    </div>
  );
}
