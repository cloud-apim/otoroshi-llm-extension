import { useState } from 'react';
import { useStudio, useWorkspace } from '../App';
import { LinesInput, NumberInput, PageHeader, Readonly, TextInput, Toggle, useConfirm, useToast } from '../components/ui';
import { Icon } from '../components/icons';
import { bootstrap } from '../lib/bootstrap';
import { Resources, slugify } from '../lib/entities';
import { useRouter } from '../lib/router';
import {
  deleteWorkspace,
  exposureFor,
  findPlugin,
  ipAddressesOf,
  IP_ALLOW_PLUGIN,
  IP_BLOCK_PLUGIN,
  setIpAddresses,
  OPENAI_COMPAT_PLUGIN,
  routeIdOf,
  setOpenAiConfig,
  slugOf,
  teamIdOf,
  updateWorkspaceRoute,
} from '../lib/workspaces';

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
  const toast = useToast();
  const confirm = useConfirm();
  const { navigate } = useRouter();
  const route = workspace.route;
  const compat = (findPlugin(route, OPENAI_COMPAT_PLUGIN) || { config: {} }).config;
  const client = (route.backend && route.backend.client) || {};
  const c = bootstrap.config;

  const [form, setForm] = useState({
    name: route.name,
    description: route.description,
    enabled: route.enabled,
    slug: slugOf(route),
    call_timeout: client.call_timeout || 600000,
    global_timeout: client.global_timeout || 600000,
    max_size_upload: compat.max_size_upload || 104857600,
    decode_images: !!compat.decode_images,
    allowed: ipAddressesOf(route, IP_ALLOW_PLUGIN),
    blocked: ipAddressesOf(route, IP_BLOCK_PLUGIN),
  });
  const [saving, setSaving] = useState(false);
  const set = (patch) => setForm((f) => ({ ...f, ...patch }));
  const slug = slugify(form.slug) || slugOf(route);
  const exposure = exposureFor(slug);
  const newBaseUrl = `${c.public_scheme}://${exposure.host}${c.public_port || ''}${exposure.path}`;

  const save = async () => {
    setSaving(true);
    try {
      if (slug !== slugOf(route) && studio.workspaces.some((w) => w.id !== workspace.id && w.slug === slug)) {
        throw new Error(`a workspace already uses '${slug}'`);
      }
      await updateWorkspaceRoute(workspace.id, (r) => {
        r.name = form.name;
        r.description = form.description;
        r.enabled = form.enabled;
        r.frontend.domains = [`${exposure.host}${exposure.path}`];
        r.backend.client = {
          ...(r.backend.client || {}),
          call_timeout: Number(form.call_timeout),
          call_and_stream_timeout: Number(form.call_timeout),
          global_timeout: Number(form.global_timeout),
        };
        setOpenAiConfig(r, { max_size_upload: Number(form.max_size_upload), decode_images: form.decode_images });
        setIpAddresses(r, IP_ALLOW_PLUGIN, form.allowed);
        setIpAddresses(r, IP_BLOCK_PLUGIN, form.blocked);
        return r;
      });
      const team = await Resources.teams.get(teamIdOf(workspace.id)).catch(() => null);
      if (team) await Resources.teams.update({ ...team, name: `AI Studio - ${form.name}`, description: form.description });
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
      deleteWorkspace(workspace.id)
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
          <Row title="Allowed addresses" top help="The studio chat calls the route from the gateway itself: add 127.0.0.1 to keep using it.">
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

        <details className="details">
          <summary>Technical details</summary>
          <dl className="kv">
            <dt>Workspace id</dt>
            <dd className="mono">{workspace.id}</dd>
            <dt>Route</dt>
            <dd className="mono">
              <a className="link" href={`/bo/dashboard/routes/${routeIdOf(workspace.id)}?tab=flow`} target="_blank" rel="noreferrer">
                {routeIdOf(workspace.id)} <Icon name="external" size={12} />
              </a>
            </dd>
            <dt>Team</dt>
            <dd className="mono">{teamIdOf(workspace.id)}</dd>
            <dt>Base URL</dt>
            <dd className="mono">{workspace.baseUrl}</dd>
          </dl>
        </details>

        <div className="card danger-zone row between">
          <div>
            <h3 className="negative-text">Delete workspace</h3>
            <p className="muted">Undeploys the route and deletes every key, provider and policy.</p>
          </div>
          <button className="btn danger" onClick={remove}>
            Delete
          </button>
        </div>
      </div>
    </div>
  );
}
