import { useMemo, useState } from 'react';
import { useWorkspace } from '../App';
import { Badge, CopyButton, Empty, ErrorAlert, Loading, PageHeader, useAsync } from '../components/ui';
import { Icon } from '../components/icons';
import { MODALITY_LABELS } from '../lib/connections';
import { listWorkspaceModels } from '../lib/models';
import { Link, useRouter } from '../lib/router';

export function ModelsPage() {
  const { workspace } = useWorkspace();
  const { navigate } = useRouter();
  const [force, setForce] = useState(0);
  const [q, setQ] = useState('');
  const [modality, setModality] = useState('');
  const [providers, setProviders] = useState([]);
  const data = useAsync(() => listWorkspaceModels(workspace, force > 0), [workspace.id, force]);

  const models = (data.data && data.data.models) || [];
  const infos = (data.data && data.data.providers) || [];
  const errors = infos.filter((p) => p.error);
  const modalities = [...new Set(models.map((m) => m.modality))];
  const providerNames = [...new Set(models.map((m) => m.provider))];

  const filtered = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return models.filter(
      (m) =>
        (!needle || m.id.toLowerCase().includes(needle)) && (!modality || m.modality === modality) && (providers.length === 0 || providers.includes(m.provider))
    );
  }, [models, q, modality, providers]);

  return (
    <div className="content wide">
      <PageHeader title="Models" description={`Every model reachable through ${workspace.baseUrl}. Use the id as the \`model\` field of your requests.`}>
        <button className="btn" onClick={() => setForce((f) => f + 1)} disabled={data.loading}>
          <Icon name="refresh" />
          Refresh
        </button>
      </PageHeader>
      <ErrorAlert error={data.error} />
      {errors.length > 0 && (
        <div className="alert warning mb">
          Some providers could not list their models: {errors.map((e) => `${e.name || e.id} (${typeof e.error === 'string' ? e.error : 'error'})`).join(', ')}. Their default model is still listed.
        </div>
      )}
      <div className="catalog">
        <div className="filters">
          <div className="field">
            <input className="input search" placeholder="Search models" value={q} onChange={(e) => setQ(e.target.value)} />
          </div>
          <div className="field">
            <label>Modality</label>
            <label className="check">
              <input type="radio" checked={!modality} onChange={() => setModality('')} /> All
            </label>
            {modalities.map((m) => (
              <label key={m} className="check">
                <input type="radio" checked={modality === m} onChange={() => setModality(m)} /> {MODALITY_LABELS[m] || m}
              </label>
            ))}
          </div>
          <div className="field">
            <label>Providers</label>
            {providerNames.map((p) => (
              <label key={p} className="check">
                <input
                  type="checkbox"
                  checked={providers.includes(p)}
                  onChange={(e) => setProviders(e.target.checked ? [...providers, p] : providers.filter((x) => x !== p))}
                />
                {p}
              </label>
            ))}
          </div>
        </div>
        <div>
          <p className="muted small" style={{ marginBottom: 10 }}>
            {filtered.length} model{filtered.length === 1 ? '' : 's'}
          </p>
          {data.loading && !data.data && <Loading label="Asking providers for their models…" />}
          {data.data && models.length === 0 && (
            <div className="card">
              <Empty title="No model yet">
                <Link className="link" to={`/workspaces/${workspace.id}/providers`}>
                  Connect a provider
                </Link>{' '}
                to expose its models here.
              </Empty>
            </div>
          )}
          <div className="stack tight">
            {filtered.slice(0, 500).map((m) => (
              <div key={`${m.modality}-${m.id}`} className="card model-card">
                <div className="row between">
                  <div className="name truncate">{m.id}</div>
                  <div className="row">
                    <CopyButton text={m.id} />
                    {m.modality === 'text' && (
                      <button className="btn sm" onClick={() => navigate(`/workspaces/${workspace.id}/chat?model=${encodeURIComponent(m.id)}`)}>
                        Chat
                      </button>
                    )}
                  </div>
                </div>
                <div className="meta">
                  <span>
                    <Badge kind="accent">{m.provider}</Badge>
                  </span>
                  <span>{MODALITY_LABELS[m.modality] || m.modality}</span>
                  <span className="mono">{m.model}</span>
                </div>
              </div>
            ))}
            {filtered.length > 500 && <p className="muted">Showing the first 500 models, refine the search to see more.</p>}
          </div>
        </div>
      </div>
    </div>
  );
}
