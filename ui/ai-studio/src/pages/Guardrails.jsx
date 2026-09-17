import { useEffect, useState } from 'react';
import { useWorkspace } from '../App';
import { Badge, Checks, Empty, ErrorAlert, Field, LinesInput, Loading, Modal, NumberInput, PageHeader, Select, TextInput, Toggle, useAsync, useToast } from '../components/ui';
import { Resources, workspaceFilter } from '../lib/entities';
import { GUARDRAIL_KINDS, kindOf, summaryOf } from '../lib/guardrails';
import { listBudgets, periodLabel } from '../lib/budgets';
import { fmtCost } from '../lib/format';
import { Link } from '../lib/router';
import { MODALITIES } from '../lib/workspaces';

function sameJson(a, b) {
  return JSON.stringify(a) === JSON.stringify(b);
}

function GuardrailModal({ initial, providers, moderationModels, onClose, onSave }) {
  const [item, setItem] = useState(
    initial || { enabled: true, before: true, after: false, id: 'regex', config: {} }
  );
  const kind = kindOf(item.id);
  const setConfig = (patch) => setItem((i) => ({ ...i, config: { ...i.config, ...patch } }));
  const changeKind = (id) => {
    const k = kindOf(id);
    setItem((i) => ({ ...i, id, config: { ...(k.defaults || {}), ...(k.llm && providers[0] ? { provider: providers[0].id } : {}) } }));
  };
  return (
    <Modal
      open
      size="wide"
      onClose={onClose}
      title={initial ? `Edit ${kind.label}` : 'New Guardrail'}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button className="btn primary" onClick={() => onSave(item)}>
            {initial ? 'Save' : 'Add'}
          </button>
        </>
      }
    >
      <Field label="Policy type" hint={kind.description}>
        <Select value={item.id} onChange={changeKind} options={GUARDRAIL_KINDS.map((k) => ({ value: k.id, label: k.label }))} disabled={!!initial} />
      </Field>
      <div className="form-grid">
        <Field label="Check user input">
          <Toggle value={item.before} onChange={(v) => setItem((i) => ({ ...i, before: v }))} />
        </Field>
        <Field label="Check model output">
          <Toggle value={item.after} onChange={(v) => setItem((i) => ({ ...i, after: v }))} />
        </Field>
        {kind.fields.map((f) => {
          const value = (item.config || {})[f.name];
          const full = f.kind === 'lines' || f.kind === 'checks' || f.kind === 'text' ? 'full' : '';
          return (
            <Field key={f.name} label={f.label} hint={f.hint} className={f.kind === 'lines' ? '' : full}>
              {f.kind === 'lines' && <LinesInput value={value || []} onChange={(v) => setConfig({ [f.name]: v })} rows={4} />}
              {f.kind === 'text' && <TextInput value={value} onChange={(v) => setConfig({ [f.name]: v })} />}
              {f.kind === 'number' && <NumberInput value={value} onChange={(v) => setConfig({ [f.name]: v })} step={f.step} />}
              {f.kind === 'select' && <Select value={value || f.options[0].value} onChange={(v) => setConfig({ [f.name]: v })} options={f.options} />}
              {f.kind === 'checks' && <Checks options={f.options.map((o) => ({ value: o, label: o }))} value={value || []} onChange={(v) => setConfig({ [f.name]: v })} />}
              {f.kind === 'provider' && (
                <Select value={value} onChange={(v) => setConfig({ [f.name]: v })} placeholder="Select a provider" options={providers.map((p) => ({ value: p.id, label: `${p.name} (${(p.options || {}).model || 'default model'})` }))} />
              )}
              {f.kind === 'moderation_model' && (
                <Select value={value} onChange={(v) => setConfig({ [f.name]: v })} placeholder={moderationModels.length ? 'Select a model' : 'No moderation model connected'} options={moderationModels.map((m) => ({ value: m.id, label: m.name }))} />
              )}
            </Field>
          );
        })}
      </div>
    </Modal>
  );
}

export function GuardrailsPage() {
  const { workspace } = useWorkspace();
  const toast = useToast();
  const [editing, setEditing] = useState(null);
  const [saving, setSaving] = useState(false);
  const data = useAsync(async () => {
    const filter = workspaceFilter(workspace.id);
    const lists = await Promise.all(MODALITIES.map((m) => Resources[m.resource].list(filter)));
    const budgets = await listBudgets(workspace.id);
    const byModality = Object.fromEntries(MODALITIES.map((m, i) => [m.id, lists[i]]));
    return { byModality, budgets };
  }, [workspace.id]);

  const providers = (data.data && data.data.byModality.text) || [];
  const moderationModels = (data.data && data.data.byModality.moderation) || [];
  const allEntities = data.data ? MODALITIES.flatMap((m) => data.data.byModality[m.id].map((e) => ({ entity: e, resource: m.resource }))) : [];

  const [access, setAccess] = useState({ include: [], exclude: [] });
  const [policies, setPolicies] = useState({ items: [], failOnDeny: true });

  useEffect(() => {
    if (!data.data) return;
    const first = allEntities[0] && allEntities[0].entity;
    setAccess({ include: (first && first.models && first.models.include) || [], exclude: (first && first.models && first.models.exclude) || [] });
    const p = providers[0];
    setPolicies({ items: (p && p.guardrails) || [], failOnDeny: p ? p.guardrails_fail_on_deny !== false : true });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [data.data]);

  const accessMixed = allEntities.some(({ entity }) => !sameJson((entity.models && entity.models.include) || [], allEntities[0].entity.models?.include || []) || !sameJson((entity.models && entity.models.exclude) || [], allEntities[0].entity.models?.exclude || []));
  const policiesMixed = providers.some((p) => !sameJson(p.guardrails || [], providers[0].guardrails || []));

  const saveAccess = async () => {
    setSaving(true);
    try {
      for (const { entity, resource } of allEntities) {
        await Resources[resource].update({ ...entity, models: { ...(entity.models || {}), include: access.include, exclude: access.exclude } });
      }
      toast.success('Model access saved');
      data.reload();
    } catch (e) {
      toast.error(e);
    } finally {
      setSaving(false);
    }
  };

  const savePolicies = async (items, failOnDeny) => {
    setSaving(true);
    try {
      for (const p of providers) {
        await Resources.providers.update({ ...p, guardrails: items, guardrails_fail_on_deny: failOnDeny });
      }
      setPolicies({ items, failOnDeny });
      toast.success('Content policies saved');
      data.reload();
    } catch (e) {
      toast.error(e);
    } finally {
      setSaving(false);
    }
  };

  const budgets = (data.data && data.data.budgets) || [];

  return (
    <div className="content">
      <PageHeader title="Guardrails" description="Model restrictions, content policies and budgets enforced by the gateway for every key of this workspace.">
        <button className="btn primary" disabled={providers.length === 0} onClick={() => setEditing({})}>
          New Guardrail
        </button>
      </PageHeader>
      <ErrorAlert error={data.error} />
      {data.loading && !data.data && <Loading />}
      {data.data && (
        <div className="stack">
          <div className="card">
            <div className="card-head">
              <div>
                <h2>Model & provider access</h2>
                <p>Regular expressions on model ids: model, provider/model or provider###model. Empty lists allow everything. Applied to every provider and model of the workspace, and to every key on top of its own models.</p>
              </div>
              <button className="btn sm primary" disabled={saving || allEntities.length === 0} onClick={saveAccess}>
                Save
              </button>
            </div>
            {accessMixed && <div className="alert warning mb">Providers currently have different lists, saving applies these ones to all of them.</div>}
            <div className="grid cols-2">
              <Field label="Allowed models" hint="e.g. gpt-4o.*, mistral-.* or openai/.*">
                <LinesInput value={access.include} onChange={(v) => setAccess((a) => ({ ...a, include: v }))} rows={4} />
              </Field>
              <Field label="Blocked models" hint="e.g. .*-preview, o1.* or azure###.*">
                <LinesInput value={access.exclude} onChange={(v) => setAccess((a) => ({ ...a, exclude: v }))} rows={4} />
              </Field>
            </div>
          </div>

          <div className="card">
            <h2>Content policies</h2>
            <p className="muted" style={{ margin: '4px 0 14px' }}>
              Applied to every text provider of this workspace.
            </p>
            {providers.length === 0 && (
              <Empty>
                <Link className="link" to={`/workspaces/${workspace.id}/providers`}>
                  Connect a text provider
                </Link>{' '}
                to add content policies.
              </Empty>
            )}
            {policiesMixed && <div className="alert warning mb">Providers currently have different policies, saving applies this list to all of them.</div>}
            {providers.length > 0 && policies.items.length === 0 && <p className="muted small">No policy yet: every message passes.</p>}
            {policies.items.length > 0 && (
              <div className="table-wrap">
                <table className="table">
                  <thead>
                    <tr>
                      <th>Policy</th>
                      <th>Phase</th>
                      <th>Configuration</th>
                      <th>Status</th>
                      <th />
                    </tr>
                  </thead>
                  <tbody>
                    {policies.items.map((item, idx) => (
                      <tr key={idx}>
                        <td>{kindOf(item.id).label}</td>
                        <td className="muted">{[item.before && 'input', item.after && 'output'].filter(Boolean).join(' + ') || '—'}</td>
                        <td className="muted">{summaryOf(item, providers)}</td>
                        <td>
                          <Toggle
                            value={item.enabled !== false}
                            onChange={(v) => savePolicies(policies.items.map((x, i) => (i === idx ? { ...x, enabled: v } : x)), policies.failOnDeny)}
                          />
                        </td>
                        <td className="actions">
                          <button className="btn sm" onClick={() => setEditing({ item, idx })}>
                            Edit
                          </button>
                          <button className="btn sm ghost" onClick={() => savePolicies(policies.items.filter((_, i) => i !== idx), policies.failOnDeny)}>
                            Remove
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            {providers.length > 0 && (
              <div className="setting-row" style={{ borderTop: '1px solid var(--border)', marginTop: 14 }}>
                <div>
                  <div style={{ fontWeight: 500 }}>Fail on deny</div>
                  <div className="help">On: a denied request returns an error to the caller. Off: the model answers with a refusal message instead.</div>
                </div>
                <div>
                  <Toggle value={policies.failOnDeny} onChange={(v) => savePolicies(policies.items, v)} />
                </div>
              </div>
            )}
          </div>

          <div className="card">
            <div className="card-head">
              <div>
                <h2>Budgets</h2>
                <p>Spending caps per key or for the whole workspace.</p>
              </div>
              <Link className="btn sm" to={`/workspaces/${workspace.id}/credits`}>
                Manage in Credits
              </Link>
            </div>
            {budgets.length === 0 && <p className="muted small">No budget.</p>}
            <div className="stack tight">
              {budgets.map((b) => (
                <div key={b.id} className="row">
                  <Badge kind={b.enabled ? 'positive' : ''}>{b.name}</Badge>
                  <span className="small">
                    {b.limits && b.limits.total_usd !== undefined && b.limits.total_usd !== null ? fmtCost(b.limits.total_usd) : ''}
                    {b.limits && b.limits.total_tokens ? ` ${b.limits.total_tokens} tokens` : ''} · {(b.scope && b.scope.apikeys && b.scope.apikeys.length) || 'all'} key(s) ·{' '}
                    {b.action_on_exceed && b.action_on_exceed.mode === 'soft' ? 'alert' : 'block'} · {periodLabel(b)}
                  </span>
                </div>
              ))}
            </div>
          </div>
        </div>
      )}
      {editing && (
        <GuardrailModal
          initial={editing.item}
          providers={providers}
          moderationModels={moderationModels}
          onClose={() => setEditing(null)}
          onSave={(item) => {
            const items = editing.item ? policies.items.map((x, i) => (i === editing.idx ? item : x)) : [...policies.items, item];
            setEditing(null);
            savePolicies(items, policies.failOnDeny);
          }}
        />
      )}
    </div>
  );
}
