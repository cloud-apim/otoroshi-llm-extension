import { useState } from 'react';
import { useCan, useStudio, useWorkspace } from '../App';
import { LinesInput, NumberInput, PageHeader, Readonly, TextInput, Toggle, useConfirm, useToast } from '../components/ui';
import { Icon } from '../components/icons';
import { backend } from '../lib/backend';
import { bootstrap } from '../lib/bootstrap';
import { slugify } from '../lib/ids';
import { adminLink } from '../lib/platform';
import { useRouter } from '../lib/router';
import { exposureFor } from '../lib/workspaces';

function Row({ title, help, children, top }) {
  return (
    <div className={`setting-row ${top ? 'top' : ''}`}>
      <div>
        <div style={{ fontWeight: 500 }}>{title}</div>
        {help && <div className="help">{help}</div>}
      </div>
      <div>{children}</div>
    </div>
  );
}

export function SettingsPage() {
  const { workspace, reload } = useWorkspace();
  const studio = useStudio();
  const can = useCan();
  const toast = useToast();
  const confirm = useConfirm();
  const { navigate } = useRouter();
  const settings = workspace.settings || {};
  const c = bootstrap.config;

  const [form, setForm] = useState({
    name: workspace.name,
    description: workspace.description,
    enabled: workspace.enabled,
    slug: workspace.slug,
    call_timeout: settings.call_timeout,
    global_timeout: settings.global_timeout,
    max_size_upload: settings.max_size_upload,
    decode_images: !!settings.decode_images,
    allowed: settings.allowed_ip_addresses || [],
    blocked: settings.blocked_ip_addresses || [],
  });
  const [saving, setSaving] = useState(false);
  const set = (patch) => setForm((f) => ({ ...f, ...patch }));
  const slug = slugify(form.slug) || workspace.slug;
  const exposure = exposureFor(slug);
  const newBaseUrl = `${c.public_scheme}://${exposure.host}${c.public_port || ''}${exposure.path}`;
  const routeLink = adminLink(`/routes/${workspace.route_id}?tab=flow`);

  const save = async () => {
    setSaving(true);
    try {
      // the api checks the slug is free among every workspace, the ones this user cannot see included
      await backend.run('workspace.update', workspace.id, {
        body: {
          name: form.name,
          description: form.description,
          enabled: form.enabled,
          slug,
          call_timeout: Number(form.call_timeout),
          global_timeout: Number(form.global_timeout),
          max_size_upload: Number(form.max_size_upload),
          decode_images: form.decode_images,
          allowed_ip_addresses: form.allowed,
          blocked_ip_addresses: form.blocked,
        },
      });
      toast.success('Settings saved');
      reload();
    } catch (e) {
      toast.error(e);
    } finally {
      setSaving(false);
    }
  };

  const remove = () => {
    confirm({
      title: `Delete ${workspace.name}?`,
      message: 'This undeploys the route and deletes every key, provider, budget and policy of the workspace. This cannot be undone.',
      danger: true,
      confirmLabel: 'Delete workspace',
    }).then((ok) => {
      if (!ok) return;
      backend
        .run('workspace.delete', workspace.id)
        .then(() => {
          toast.success('Workspace deleted');
          studio.reloadWorkspaces();
          navigate('/');
        })
        .catch(toast.error);
    });
  };

  return (
    <div className="content narrow">
      <PageHeader title="Settings" description="Manage your workspace identity, exposure and gateway controls." />
      <div className="stack">
        {/* who can only read the configuration sees it, without changing it */}
        <fieldset className="bare stack" disabled={!can('config:write')}>
          <div className="card">
            <h2>General</h2>
            <p className="muted" style={{ marginBottom: 14 }}>
              Identity of this workspace.
            </p>
            <Row title="Name" help="Shown across the console.">
              <TextInput value={form.name} onChange={(v) => set({ name: v })} />
            </Row>
            <Row title="Description" help="A short note describing how this workspace is used.">
              <TextInput value={form.description} onChange={(v) => set({ description: v })} />
            </Row>
            <Row title="Enabled" help="Disabled workspaces reject every request.">
              <Toggle value={form.enabled} onChange={(v) => set({ enabled: v })} />
            </Row>
          </div>

          <div className="card">
            <h2>Domain</h2>
            <p className="muted" style={{ marginBottom: 14 }}>
              Where this workspace's OpenAI-compatible route is served.
            </p>
            <Row title="Managed domain" help="Domain provided by the gateway for every workspace.">
              <Readonly value={c.domain} copy={false} mono={false} />
            </Row>
            <Row title={c.exposure === 'path' ? 'Path' : 'Subdomain'} help="Lowercase letters, digits and hyphens.">
              <TextInput value={form.slug} onChange={(v) => set({ slug: v.toLowerCase() })} />
            </Row>
            <Row title="Base URL" help="Where clients send their requests.">
              <Readonly value={newBaseUrl} />
            </Row>
          </div>

          <div className="card">
            <h2>Gateway</h2>
            <p className="muted" style={{ marginBottom: 14 }}>
              Timeouts and request limits on the route.
            </p>
            <Row title="Call timeout (ms)" help="Applies to streaming calls as well.">
              <NumberInput value={form.call_timeout} onChange={(v) => set({ call_timeout: v })} />
            </Row>
            <Row title="Global timeout (ms)">
              <NumberInput value={form.global_timeout} onChange={(v) => set({ global_timeout: v })} />
            </Row>
            <Row title="Max upload size (bytes)">
              <NumberInput value={form.max_size_upload} onChange={(v) => set({ max_size_upload: v })} />
            </Row>
            <Row title="Decode images" help="Let the gateway decode image inputs before forwarding.">
              <Toggle value={form.decode_images} onChange={(v) => set({ decode_images: v })} />
            </Row>
          </div>

          <div className="card">
            <h2>IP access control</h2>
            <p className="muted" style={{ marginBottom: 14 }}>
              One address, regex or CIDR per line, enforced by the IpAddressAllowedList and IpAddressBlockList plugins of the route. Leave empty to allow all.
            </p>
            <Row title="Allowed addresses" top help="Only applies to applications: the studio chat is served for the signed-in user, whatever their address.">
              <LinesInput value={form.allowed} onChange={(v) => set({ allowed: v })} rows={3} />
            </Row>
            <Row title="Blocked addresses" top>
              <LinesInput value={form.blocked} onChange={(v) => set({ blocked: v })} rows={3} />
            </Row>
          </div>

          <div className="row end">
            <button className="btn primary" onClick={save} disabled={saving || !form.name.trim()}>
              {saving ? 'Saving…' : 'Save'}
            </button>
          </div>
        </fieldset>

        <details className="details">
          <summary>Technical details</summary>
          <dl className="kv">
            <dt>Workspace id</dt>
            <dd className="mono">{workspace.id}</dd>
            <dt>Route</dt>
            <dd className="mono">
              {routeLink ? (
                <a className="link" href={routeLink} target="_blank" rel="noreferrer">
                  {workspace.route_id} <Icon name="external" size={12} />
                </a>
              ) : (
                workspace.route_id
              )}
            </dd>
            <dt>Team</dt>
            <dd className="mono">{workspace.team_id}</dd>
            <dt>Base URL</dt>
            <dd className="mono">{workspace.base_url}</dd>
          </dl>
        </details>

        {can('workspace:delete') && (
          <div className="card danger-zone row between">
            <div>
              <h3 className="negative-text">Delete workspace</h3>
              <p className="muted">Undeploys the route and deletes every key, provider and policy.</p>
            </div>
            <button className="btn danger" onClick={remove}>
              Delete
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
