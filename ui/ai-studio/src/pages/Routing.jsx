import { useState } from 'react';
import { useWorkspace } from '../App';
import { Badge, CopyButton, Empty, ErrorAlert, Field, Loading, Modal, NumberInput, PageHeader, Select, TextInput, useAsync, useConfirm, useToast } from '../components/ui';
import { Icon } from '../components/icons';
import { connectionName } from '../lib/connections';
import { META, Resources, randomId, workspaceFilter } from '../lib/entities';
import { Link } from '../lib/router';
import { findPlugin, OPENAI_COMPAT_PLUGIN, routeIdOf, syncWorkspaceRefs, updateWorkspaceRoute, setOpenAiConfig, workspaceLocation, workspaceMetadata } from '../lib/workspaces';

export const VIRTUAL_KINDS = ['loadbalancer', 'otoroshi'];

function chainOf(provider, byId) {
  const chain = [provider.name];
  const seen = new Set([provider.id]);
  let current = provider;
  while (current && current.provider_fallback && byId[current.provider_fallback] && !seen.has(current.provider_fallback)) {
    current = byId[current.provider_fallback];
    seen.add(current.id);
    chain.push(current.name);
  }
  return chain;
}

function LoadBalancerModal({ workspace, balancer, providers, existingNames, onClose, onSaved }) {
  const toast = useToast();
  const refs = (balancer && balancer.options && balancer.options.refs) || [];
  const [form, setForm] = useState({
    name: balancer ? balancer.name : 'balanced',
    strategy: (balancer && balancer.options && balancer.options.loadbalancing) || 'round_robin',
    targets: refs.length ? refs.map((r) => (typeof r === 'string' ? { ref: r, weight: 1 } : { ref: r.ref, weight: r.weight || 1 })) : providers.slice(0, 2).map((p) => ({ ref: p.id, weight: 1 })),
  });
  const [saving, setSaving] = useState(false);
  const set = (patch) => setForm((f) => ({ ...f, ...patch }));
  const nameTaken = existingNames.includes(form.name) && (!balancer || balancer.name !== form.name);

  const save = async () => {
    setSaving(true);
    try {
      const id = (balancer && balancer.id) || `provider_ais_${randomId(20)}`;
      const entity = {
        models: { include: [], exclude: [] },
        guardrails: [],
        guardrails_fail_on_deny: false,
        ...(balancer || {}),
        _loc: (balancer && balancer._loc) || workspaceLocation(workspace.id),
        id,
        name: form.name,
        description: 'Load balancer',
        tags: (balancer && balancer.tags) || [],
        metadata: { ...((balancer && balancer.metadata) || {}), ...workspaceMetadata(workspace.id, 'provider', { [META.connection]: id }) },
        provider: 'loadbalancer',
        connection: {},
        options: { ...((balancer && balancer.options) || {}), refs: form.targets.filter((t) => t.ref).map((t) => ({ ref: t.ref, weight: Number(t.weight) || 1 })), loadbalancing: form.strategy },
      };
      if (balancer) await Resources.providers.update(entity);
      else await Resources.providers.create(entity);
      await syncWorkspaceRefs(workspace.id);
      toast.success(balancer ? 'Load balancer saved' : 'Load balancer created');
      onSaved();
    } catch (e) {
      toast.error(e);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal
      open
      onClose={onClose}
      title={balancer ? `Edit ${balancer.name}` : 'New load balancer'}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button className="btn primary" disabled={!form.name || nameTaken || form.targets.filter((t) => t.ref).length < 1 || saving} onClick={save}>
            {saving ? 'Saving…' : balancer ? 'Save' : 'Create'}
          </button>
        </>
      }
    >
      <div className="form-grid">
        <Field label="Name" hint="Call it with the model `<name>/_default`." error={nameTaken ? 'already used in this workspace' : null}>
          <TextInput value={form.name} onChange={(v) => set({ name: connectionName(v) })} />
        </Field>
        <Field label="Strategy">
          <Select
            value={form.strategy}
            onChange={(v) => set({ strategy: v })}
            options={[
              { value: 'round_robin', label: 'Round robin (weighted)' },
              { value: 'random', label: 'Random' },
              { value: 'best_response_time', label: 'Best response time' },
            ]}
          />
        </Field>
      </div>
      <Field label="Targets" hint="Each target is called with its own default model.">
        <div className="stack tight">
          {form.targets.map((t, idx) => (
            <div key={idx} className="row">
              <Select className="grow" value={t.ref} onChange={(v) => set({ targets: form.targets.map((x, i) => (i === idx ? { ...x, ref: v } : x)) })} placeholder="Select a provider" options={providers.map((p) => ({ value: p.id, label: `${p.name} (${(p.options || {}).model || 'default'})` }))} />
              <div style={{ width: 90 }}>
                <NumberInput value={t.weight} onChange={(v) => set({ targets: form.targets.map((x, i) => (i === idx ? { ...x, weight: v } : x)) })} min="1" title="weight" />
              </div>
              <button className="copy-btn" onClick={() => set({ targets: form.targets.filter((_, i) => i !== idx) })} title="Remove">
                <Icon name="trash" />
              </button>
            </div>
          ))}
          <button className="btn sm" style={{ alignSelf: 'flex-start' }} onClick={() => set({ targets: [...form.targets, { ref: '', weight: 1 }] })}>
            <Icon name="plus" />
            Add target
          </button>
        </div>
      </Field>
    </Modal>
  );
}

export function RoutingPage() {
  const { workspace } = useWorkspace();
  const toast = useToast();
  const confirm = useConfirm();
  const [editing, setEditing] = useState(null);
  const data = useAsync(async () => {
    const [providers, route] = await Promise.all([Resources.providers.list(workspaceFilter(workspace.id)), Resources.routes.get(routeIdOf(workspace.id))]);
    return { providers, route };
  }, [workspace.id]);

  const all = (data.data && data.data.providers) || [];
  const real = all.filter((p) => !VIRTUAL_KINDS.includes(p.provider));
  const balancers = all.filter((p) => p.provider === 'loadbalancer');
  const byId = Object.fromEntries(all.map((p) => [p.id, p]));
  const refs = data.data ? ((findPlugin(data.data.route, OPENAI_COMPAT_PLUGIN) || { config: {} }).config.language_model_refs || []) : [];
  const ordered = refs.map((id) => byId[id]).filter(Boolean);
  const defaultProvider = ordered[0];

  const setFallback = (provider, fallback) => {
    Resources.providers
      .update({ ...provider, provider_fallback: fallback || null })
      .then(() => {
        toast.success('Fallback saved');
        data.reload();
      })
      .catch(toast.error);
  };

  const makeDefault = (provider) => {
    updateWorkspaceRoute(workspace.id, (route) => {
      const current = (findPlugin(route, OPENAI_COMPAT_PLUGIN) || { config: {} }).config.language_model_refs || [];
      return setOpenAiConfig(route, { language_model_refs: [provider.id, ...current.filter((id) => id !== provider.id)] });
    })
      .then(() => {
        toast.success(`${provider.name} is now the default provider`);
        data.reload();
      })
      .catch(toast.error);
  };

  const removeBalancer = (b) => {
    confirm({ title: `Delete ${b.name}?`, danger: true, confirmLabel: 'Delete' }).then((ok) => {
      if (!ok) return;
      Resources.providers
        .delete(b.id)
        .then(() => syncWorkspaceRefs(workspace.id))
        .then(() => {
          toast.success('Load balancer deleted');
          data.reload();
        })
        .catch(toast.error);
    });
  };

  return (
    <div className="content">
      <PageHeader title="Routing" description="Decide which provider serves a request and what happens when a provider fails." />
      <ErrorAlert error={data.error} />
      {data.loading && !data.data && <Loading />}
      {data.data && (
        <div className="stack">
          <div className="card">
            <h2>Provider fallback</h2>
            <p className="muted" style={{ margin: '4px 0 14px' }}>
              When a provider returns an error, the gateway retries the request on its fallback provider. Chains are followed in order.
            </p>
            {real.length === 0 ? (
              <Empty>
                <Link className="link" to={`/workspaces/${workspace.id}/providers`}>
                  Connect a provider
                </Link>{' '}
                first.
              </Empty>
            ) : (
              <div className="table-wrap">
                <table className="table">
                  <thead>
                    <tr>
                      <th>Provider</th>
                      <th>Default model</th>
                      <th style={{ width: 240 }}>Falls back to</th>
                      <th>Resulting chain</th>
                    </tr>
                  </thead>
                  <tbody>
                    {real.map((p) => (
                      <tr key={p.id}>
                        <td>
                          {p.name} <span className="faint small">({p.provider})</span>
                        </td>
                        <td className="mono">{(p.options || {}).model || '—'}</td>
                        <td>
                          <Select className="sm" value={p.provider_fallback || ''} onChange={(v) => setFallback(p, v)} placeholder="None" options={all.filter((o) => o.id !== p.id).map((o) => ({ value: o.id, label: o.name }))} />
                        </td>
                        <td>
                          <div className="badges">
                            {chainOf(p, byId).map((n, i) => (
                              <Badge key={i} kind="accent">
                                {n}
                              </Badge>
                            ))}
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>

          <div className="card">
            <div className="card-head">
              <div>
                <h2>Load balancing</h2>
                <p>Spread the traffic over several providers. A load balancer is exposed like a provider of the workspace.</p>
              </div>
              <button className="btn sm primary" disabled={real.length === 0} onClick={() => setEditing({})}>
                New load balancer
              </button>
            </div>
            {balancers.length === 0 && <p className="muted small">No load balancer.</p>}
            {balancers.length > 0 && (
              <div className="table-wrap">
                <table className="table">
                  <thead>
                    <tr>
                      <th>Name</th>
                      <th>Strategy</th>
                      <th>Targets</th>
                      <th>Model to use</th>
                      <th />
                    </tr>
                  </thead>
                  <tbody>
                    {balancers.map((b) => (
                      <tr key={b.id}>
                        <td>{b.name}</td>
                        <td className="muted">{((b.options || {}).loadbalancing || 'round_robin').replace(/_/g, ' ')}</td>
                        <td>
                          <div className="badges">
                            {((b.options || {}).refs || []).map((r, i) => {
                              const ref = typeof r === 'string' ? r : r.ref;
                              return (
                                <Badge key={i} kind="accent">
                                  {(byId[ref] || { name: ref }).name}
                                  {typeof r === 'object' && r.weight > 1 ? ` ×${r.weight}` : ''}
                                </Badge>
                              );
                            })}
                          </div>
                        </td>
                        <td>
                          <span className="row">
                            <code>{b.name}/_default</code>
                            <CopyButton text={`${b.name}/_default`} />
                          </span>
                        </td>
                        <td className="actions">
                          <button className="btn sm" onClick={() => setEditing({ balancer: b })}>
                            Edit
                          </button>
                          <button className="btn sm ghost" onClick={() => removeBalancer(b)}>
                            Delete
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>

          <div className="card">
            <h2>Default provider</h2>
            <p className="muted" style={{ margin: '4px 0 14px' }}>
              Requests whose model does not name a provider (no <code>provider/</code> prefix) are served by the default provider, with the model they ask for or its default model.
            </p>
            {defaultProvider ? (
              <p style={{ marginBottom: 12 }}>
                Currently <b>{defaultProvider.name}</b> with <code>{(defaultProvider.options || {}).model || 'its default model'}</code>.
              </p>
            ) : (
              <p className="muted small">No provider served yet.</p>
            )}
            <div className="stack tight">
              {ordered.slice(1).map((p) => (
                <div key={p.id} className="row between">
                  <span>{p.name}</span>
                  <button className="btn sm" onClick={() => makeDefault(p)}>
                    Make default
                  </button>
                </div>
              ))}
            </div>
          </div>
        </div>
      )}
      {editing && (
        <LoadBalancerModal
          workspace={workspace}
          balancer={editing.balancer}
          providers={real}
          existingNames={all.map((p) => p.name)}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            data.reload();
          }}
        />
      )}
    </div>
  );
}
