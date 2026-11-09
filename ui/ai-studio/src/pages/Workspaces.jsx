import { useEffect, useState } from 'react';
import { useStudio } from '../App';
import { Badge, Empty, ErrorAlert, Field, Loading, Modal, PageHeader, Select, TextInput, useToast } from '../components/ui';
import { slugify } from '../lib/ids';
import { useRouter } from '../lib/router';
import { exposureFor } from '../lib/workspaces';
import { backend } from '../lib/backend';
import { hasPermission, platform } from '../lib/platform';
import { bootstrap, currentTenant } from '../lib/bootstrap';

// The organizations a new workspace can be created in: the ones of the integration, else the ones of the backend.
// None when neither gives any (an older backend, an edition without them): the workspace goes where it always did
function loadOrganizations() {
  const load = platform.organizations || (backend.workspaces && backend.workspaces.organizations);
  if (!load) return Promise.resolve([]);
  return Promise.resolve()
    .then(load)
    .then((list) => (Array.isArray(list) ? list.filter((o) => o && o.id) : []))
    .catch(() => []);
}

export function NewWorkspaceModal({ open, onClose }) {
  const studio = useStudio();
  const toast = useToast();
  const { navigate } = useRouter();
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [slug, setSlug] = useState('');
  const [saving, setSaving] = useState(false);
  const [organizations, setOrganizations] = useState([]);
  const [organization, setOrganization] = useState('');
  const finalSlug = slugify(slug || name);
  const { host, path } = exposureFor(finalSlug || 'my-workspace');
  const c = bootstrap.config;

  useEffect(() => {
    let alive = true;
    loadOrganizations().then((list) => {
      if (!alive) return;
      setOrganizations(list);
      setOrganization((list.find((o) => o.id === currentTenant()) || list[0] || {}).id || '');
    });
    return () => {
      alive = false;
    };
  }, []);

  const create = () => {
    setSaving(true);
    backend.workspaces
      .create({ name, description, slug: finalSlug, ...(organization ? { organization } : {}) })
      .then((ws) => {
        toast.success('Workspace created');
        studio.reloadWorkspaces();
        onClose();
        navigate(`/workspaces/${ws.id}/providers`);
      })
      .catch(toast.error)
      .finally(() => setSaving(false));
  };

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="New Workspace"
      footer={
        <>
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button className="btn primary" disabled={!name.trim() || saving} onClick={create}>
            {saving ? 'Creating…' : 'Create'}
          </button>
        </>
      }
    >
      {organizations.length > 1 && (
        <Field label="Organization">
          <Select value={organization} onChange={setOrganization} options={organizations.map((o) => ({ value: o.id, label: o.name || o.id }))} />
        </Field>
      )}
      <Field label="Name">
        <TextInput value={name} onChange={setName} placeholder="My product" autoFocus />
      </Field>
      <Field label="Description">
        <TextInput value={description} onChange={setDescription} placeholder="What is this workspace used for" />
      </Field>
      <Field label={c.exposure === 'path' ? 'Path' : 'Subdomain'} hint="Lowercase letters, digits and hyphens. Empty = derived from the name.">
        <TextInput value={slug} onChange={(v) => setSlug(v.toLowerCase())} placeholder={slugify(name) || 'my-product'} />
      </Field>
      <Field label="Base URL">
        <div className="readonly mono">
          <span className="truncate">{`${c.public_scheme}://${host}${c.public_port || ''}${path}`}</span>
        </div>
      </Field>
    </Modal>
  );
}

export function WorkspacesPage({ loading, error }) {
  const studio = useStudio();
  const { navigate } = useRouter();
  const [creating, setCreating] = useState(false);
  const workspaces = studio.workspaces;
  // the organization of each workspace, once they are in more than one
  const organizations = new Set(workspaces.map((ws) => ws.tenant)).size > 1;

  return (
    <div className="content">
      <PageHeader title="Workspaces" description="Each workspace has its own OpenAI-compatible base URL, API keys, providers and policies.">
        {hasPermission('workspaces:create') && (
          <button className="btn primary" onClick={() => setCreating(true)}>
            New Workspace
          </button>
        )}
      </PageHeader>
      <ErrorAlert error={error} />
      {loading && workspaces.length === 0 && <Loading />}
      {!loading && workspaces.length === 0 && !error && (
        <div className="card">
          <Empty
            title="No workspace yet"
            action={
              hasPermission('workspaces:create') ? (
                <button className="btn primary" onClick={() => setCreating(true)}>
                  Create your first workspace
                </button>
              ) : null
            }
          >
            A workspace gives your team an OpenAI-compatible endpoint backed by the providers you bring.
          </Empty>
        </div>
      )}
      <div className="grid cols-2">
        {workspaces.map((ws) => (
          <div key={ws.id} className="card clickable" onClick={() => navigate(`/workspaces/${ws.id}/overview`)}>
            <div className="card-title">
              <h2 className="truncate">{ws.name}</h2>
              {ws.enabled ? (
                <Badge kind="positive" dot>
                  Deployed
                </Badge>
              ) : (
                <Badge dot>Disabled</Badge>
              )}
            </div>
            <div className="muted" style={{ marginBottom: 10 }}>
              {ws.description || 'No description'}
            </div>
            <div className="mono truncate">{ws.base_url}</div>
            {organizations && (
              <div className="muted truncate" style={{ marginTop: 6 }}>
                {ws.tenant}
              </div>
            )}
          </div>
        ))}
      </div>
      {creating && <NewWorkspaceModal open onClose={() => setCreating(false)} />}
    </div>
  );
}
