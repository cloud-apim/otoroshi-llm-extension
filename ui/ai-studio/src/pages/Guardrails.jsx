import { useEffect, useState } from 'react';
import { useCan, useWorkspace } from '../App';
import { Badge, Checks, Empty, ErrorAlert, Field, LinesInput, Loading, Modal, NumberInput, PageHeader, Select, TextInput, Toggle, useAsync, useToast } from '../components/ui';
import { Icon } from '../components/icons';
import { FILTER_OPERATORS, FILTER_SOURCES, filtersSummary, formatFilterValue, GUARDRAIL_KINDS, kindOf, operatorOf, parseFilterValue, summaryOf } from '../lib/guardrails';
import { backend } from '../lib/backend';
import { periodLabel } from '../lib/budgets';
import { fmtCost } from '../lib/format';
import { Link } from '../lib/router';

// One consumer filter: what is read on the call, how it is compared, and to what. A source the studio does
// not list (a header, a metadata) is edited as the expression itself.
function FilterRow({ filter, onChange, onRemove }) {
  const known = FILTER_SOURCES.some((s) => s.value === filter.from);
  const { operator, operand } = parseFilterValue(filter.value);
  const op = operatorOf(operator);
  return (
    <div className="filter-row">
      <Select
        value={known ? filter.from : 'custom'}
        onChange={(v) => onChange({ ...filter, from: v === 'custom' ? '' : v })}
        options={[...FILTER_SOURCES, { value: 'custom', label: 'Custom expression' }]}
      />
      {!known && <TextInput value={filter.from} onChange={(v) => onChange({ ...filter, from: v })} placeholder="${req.headers.x-team}" />}
      <Select value={operator} onChange={(v) => onChange({ ...filter, value: formatFilterValue(v, operand) })} options={FILTER_OPERATORS.map((o) => ({ value: o.value, label: o.label }))} />
      {!op.fixed && <TextInput value={operand} onChange={(v) => onChange({ ...filter, value: formatFilterValue(operator, v) })} placeholder={op.placeholder} />}
      <button className="btn sm ghost" onClick={onRemove} title="Remove this filter">
        Remove
      </button>
    </div>
  );
}

function GuardrailModal({ initial, providers, moderationModels, decisionModels, onClose, onSave }) {
  const [item, setItem] = useState(
    initial || { enabled: true, before: true, after: false, id: 'regex', config: {} }
  );
  const kind = kindOf(item.id);
  const filters = item.filters || [];
  const setFilters = (next) => setItem((i) => ({ ...i, filters: next }));
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
                <Select value={value} onChange={(v) => setConfig({ [f.name]: v })} placeholder="Select a provider" options={providers.map((p) => ({ value: p.id, label: `${p.name} (${p.model || 'default model'})` }))} />
              )}
              {f.kind === 'moderation_model' && (
                <Select value={value} onChange={(v) => setConfig({ [f.name]: v })} placeholder={moderationModels.length ? 'Select a model' : 'No moderation model connected'} options={moderationModels.map((m) => ({ value: m.id, label: m.name }))} />
              )}
              {f.kind === 'decision_model' && (
                <Select value={value} onChange={(v) => setConfig({ [f.name]: v })} placeholder={decisionModels.length ? 'Select a model' : 'No decision model connected'} options={decisionModels.map((m) => ({ value: m.id, label: m.name }))} />
              )}
            </Field>
          );
        })}
      </div>
      <Field
        label="Applies to"
        hint="Without a filter, the policy applies to every call of the workspace. With filters, only to the calls matching all of them. A call that carries none of what a filter reads — no API key, no signed-in user — is left alone."
      >
        <div className="filters">
          {filters.length === 0 && <p className="muted small">Every call.</p>}
          {filters.map((f, idx) => (
            <FilterRow
              key={idx}
              filter={f}
              onChange={(next) => setFilters(filters.map((x, i) => (i === idx ? next : x)))}
              onRemove={() => setFilters(filters.filter((_, i) => i !== idx))}
            />
          ))}
          <div>
            <button className="btn sm" onClick={() => setFilters([...filters, { from: FILTER_SOURCES[0].value, value: '' }])}>
              <Icon name="plus" />
              Add a filter
            </button>
          </div>
        </div>
      </Field>
    </Modal>
  );
}

export function GuardrailsPage() {
  const { workspace } = useWorkspace();
  const write = useCan()('config:write');
  const toast = useToast();
  const [editing, setEditing] = useState(null);
  const [saving, setSaving] = useState(false);
  // the model access and the content policies are the same on every model of the workspace: the api reads them
  // from the first one, says when they differ (`mixed`), and writes them on all of them
  const data = useAsync(async () => {
    const [guardrails, modelAccess, entities, budgets] = await Promise.all([
      backend.run('guardrails.get', workspace.id),
      backend.run('modelAccess.get', workspace.id),
      backend.run('modelEntities.list', workspace.id),
      backend.run('budgets.list', workspace.id),
    ]);
    const ofModality = (modality) => entities.filter((e) => e.modality === modality);
    return { guardrails, modelAccess, models: entities.length, providers: ofModality('text'), moderationModels: ofModality('moderation'), decisionModels: ofModality('decision'), budgets };
  }, [workspace.id]);

  const providers = (data.data && data.data.providers) || [];
  const moderationModels = (data.data && data.data.moderationModels) || [];
  const decisionModels = (data.data && data.data.decisionModels) || [];

  const [access, setAccess] = useState({ include: [], exclude: [] });
  const [policies, setPolicies] = useState({ items: [], failOnDeny: true });

  useEffect(() => {
    if (!data.data) return;
    setAccess({ include: data.data.modelAccess.include, exclude: data.data.modelAccess.exclude });
    setPolicies({ items: data.data.guardrails.items, failOnDeny: data.data.guardrails.fail_on_deny });
  }, [data.data]);

  const accessMixed = !!(data.data && data.data.modelAccess.mixed);
  const policiesMixed = !!(data.data && data.data.guardrails.mixed);

  const saveAccess = async () => {
    setSaving(true);
    try {
      await backend.run('modelAccess.save', workspace.id, { body: { include: access.include, exclude: access.exclude } });
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
      await backend.run('guardrails.save', workspace.id, { body: { items, fail_on_deny: failOnDeny } });
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
        {write && (
          <button className="btn primary" disabled={providers.length === 0} onClick={() => setEditing({})}>
            New Guardrail
          </button>
        )}
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
              {write && (
                <button className="btn sm primary" disabled={saving || !data.data || data.data.models === 0} onClick={saveAccess}>
                  Save
                </button>
              )}
            </div>
            {accessMixed && <div className="alert warning mb">Providers currently have different lists, saving applies these ones to all of them.</div>}
            <div className="grid cols-2">
              <Field label="Allowed models" hint="e.g. gpt-4o.*, mistral-.* or openai/.*">
                <LinesInput value={access.include} onChange={(v) => setAccess((a) => ({ ...a, include: v }))} rows={4} disabled={!write} />
              </Field>
              <Field label="Blocked models" hint="e.g. .*-preview, o1.* or azure###.*">
                <LinesInput value={access.exclude} onChange={(v) => setAccess((a) => ({ ...a, exclude: v }))} rows={4} disabled={!write} />
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
                      <th>Applies to</th>
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
                        <td className="muted">{filtersSummary(item.filters)}</td>
                        <td>
                          <Toggle
                            value={item.enabled !== false}
                            disabled={!write}
                            onChange={(v) => savePolicies(policies.items.map((x, i) => (i === idx ? { ...x, enabled: v } : x)), policies.failOnDeny)}
                          />
                        </td>
                        <td className="actions">
                          {write && (
                            <>
                              <button className="btn sm" onClick={() => setEditing({ item, idx })}>
                                Edit
                              </button>
                              <button className="btn sm ghost" onClick={() => savePolicies(policies.items.filter((_, i) => i !== idx), policies.failOnDeny)}>
                                Remove
                              </button>
                            </>
                          )}
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
                  <Toggle value={policies.failOnDeny} disabled={!write} onChange={(v) => savePolicies(policies.items, v)} />
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
                    {b.usd !== undefined && b.usd !== null ? fmtCost(b.usd) : ''}
                    {b.tokens ? ` ${b.tokens} tokens` : ''} · {b.apikeys.length || 'all'} key(s) · {b.mode === 'soft' ? 'alert' : 'block'} · {periodLabel(b)}
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
          decisionModels={decisionModels}
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
