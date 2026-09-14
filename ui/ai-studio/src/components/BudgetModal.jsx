import { useState } from 'react';
import { extraRulesOf, PERIODS, periodOf, saveBudget, scopeModeOf } from '../lib/budgets';
import { Badge, Checks, Field, LinesInput, Modal, NumberInput, Segmented, Select, TextInput, Toggle, useToast } from './ui';
import { Icon } from './icons';

// Create or edit a budget of a workspace. The workspace itself is always part of the scope; the user
// narrows it to one api key, or to a custom set of consumers, models and conditions.
export function BudgetModal({ workspace, budget, keys, apikey, onClose, onSaved }) {
  const toast = useToast();
  const key = apikey && keys.find((k) => k.clientId === apikey);
  const [form, setForm] = useState(() => ({
    name: budget ? budget.name : key ? `${key.clientName} budget` : '',
    description: budget ? budget.description : '',
    enabled: budget ? budget.enabled : true,
    usd: budget && budget.limits ? budget.limits.total_usd ?? null : null,
    tokens: budget && budget.limits ? budget.limits.total_tokens ?? null : null,
    period: budget ? periodOf(budget) : 'monthly',
    mode: budget && budget.action_on_exceed ? budget.action_on_exceed.mode : 'block',
    alert: budget && budget.action_on_exceed ? budget.action_on_exceed.alert_on_almost_exceed_percentage : 80,
    scope: budget ? scopeModeOf(budget) : apikey ? 'apikey' : 'workspace',
    models: budget && budget.scope ? budget.scope.models || [] : [],
    apikeys: budget && budget.scope ? budget.scope.apikeys || [] : apikey ? [apikey] : [],
    users: budget && budget.scope ? budget.scope.users || [] : [],
    rules: budget ? extraRulesOf(budget) : [],
  }));
  const [saving, setSaving] = useState(false);
  const set = (patch) => setForm((f) => ({ ...f, ...patch }));
  const valid = form.name.trim() && (form.usd !== null || form.tokens !== null) && (form.scope !== 'apikey' || form.apikeys.length === 1);

  const save = () => {
    setSaving(true);
    const scoped =
      form.scope === 'workspace'
        ? { apikeys: [], users: [], rules: [] }
        : form.scope === 'apikey'
          ? { apikeys: form.apikeys.slice(0, 1), users: [], rules: [] }
          : { apikeys: form.apikeys, users: form.users, rules: form.rules };
    saveBudget(workspace.id, { ...form, ...scoped }, budget)
      .then(() => {
        toast.success(budget ? 'Budget saved' : 'Budget created');
        onSaved();
      })
      .catch(toast.error)
      .finally(() => setSaving(false));
  };

  const setRule = (idx, patch) => set({ rules: form.rules.map((r, i) => (i === idx ? { ...r, ...patch } : r)) });

  return (
    <Modal
      open
      size="wide"
      onClose={onClose}
      title={budget ? `Edit ${budget.name}` : 'New budget'}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button className="btn primary" disabled={!valid || saving} onClick={save}>
            {saving ? 'Saving…' : budget ? 'Save' : 'Create'}
          </button>
        </>
      }
    >
      <div className="form-grid">
        <Field label="Name">
          <TextInput value={form.name} onChange={(v) => set({ name: v })} placeholder="Monthly cap" />
        </Field>
        <Field label="Enabled">
          <Toggle value={form.enabled} onChange={(v) => set({ enabled: v })} />
        </Field>
        <Field label="Credit limit (USD)" hint="Leave blank for no dollar limit.">
          <NumberInput value={form.usd} onChange={(v) => set({ usd: v })} step="0.01" min="0" />
        </Field>
        <Field label="Token limit" hint="Leave blank for no token limit.">
          <NumberInput value={form.tokens} onChange={(v) => set({ tokens: v })} placeholder="1000000" min="0" />
        </Field>
        <Field label="Period" hint="Consumption resets at the end of each period.">
          <Select value={form.period} onChange={(v) => set({ period: v })} options={PERIODS.map((p) => ({ value: p.value, label: p.label }))} />
        </Field>
        <Field label="When exceeded">
          <Select
            value={form.mode}
            onChange={(v) => set({ mode: v })}
            options={[
              { value: 'block', label: 'Block requests' },
              { value: 'soft', label: 'Only alert' },
            ]}
          />
        </Field>
        <Field label="Alert threshold (%)">
          <NumberInput value={form.alert} onChange={(v) => set({ alert: v })} min="1" max="100" />
        </Field>
        <Field label="Restrict to models" hint="One model name (or regex) per line. Empty = every model.">
          <LinesInput value={form.models} onChange={(v) => set({ models: v })} rows={2} />
        </Field>
      </div>

      <div className="divider" />
      <div className="stack">
        <div className="row between wrap">
          <div>
            <h3>Scope</h3>
            <p className="muted small" style={{ marginTop: 4 }}>
              Always limited to the calls of <Badge kind="accent">{workspace.name}</Badge>, then to what you pick.
            </p>
          </div>
          <Segmented
            value={form.scope}
            onChange={(v) => set({ scope: v, apikeys: v === 'apikey' ? form.apikeys.slice(0, 1) : form.apikeys })}
            options={[
              { value: 'workspace', label: 'Whole workspace' },
              { value: 'apikey', label: 'One API key' },
              { value: 'custom', label: 'Custom' },
            ]}
          />
        </div>

        {form.scope === 'workspace' && <p className="muted">Every call of the workspace counts against this budget, whatever the key or the user.</p>}

        {form.scope === 'apikey' && (
          <Field label="API key">
            <Select value={form.apikeys[0] || ''} onChange={(v) => set({ apikeys: v ? [v] : [] })} placeholder="Select an API key" options={keys.map((k) => ({ value: k.clientId, label: k.clientName }))} />
          </Field>
        )}

        {form.scope === 'custom' && (
          <>
            <p className="muted small">The selected consumers share this budget. A call counts when it comes from one of them and matches every condition.</p>
            <Field label="API keys">
              {keys.length === 0 ? <span className="muted">No API key in this workspace.</span> : <Checks options={keys.map((k) => ({ value: k.clientId, label: k.clientName }))} value={form.apikeys} onChange={(v) => set({ apikeys: v })} />}
            </Field>
            <Field label="Studio users" hint="One email (or regex) per line: users chatting with the workspace from AI Studio.">
              <LinesInput value={form.users} onChange={(v) => set({ users: v })} rows={2} />
            </Field>
            <Field label="Conditions" hint="JSON path checked on the call context (apikey, user, route, provider, model, request). Every condition must match.">
              <div className="stack tight">
                <div className="row">
                  <input className="input mono" value="$.provider.metadata.ai_studio_workspace" disabled />
                  <input className="input mono" value={workspace.id} disabled style={{ maxWidth: 220 }} />
                  <span className="copy-btn" title="Always applied">
                    <Icon name="shield" />
                  </span>
                </div>
                {form.rules.map((r, idx) => (
                  <div key={idx} className="row">
                    <input className="input mono" value={r.path} placeholder="$.apikey.metadata.team" onChange={(e) => setRule(idx, { path: e.target.value })} />
                    <input className="input mono" value={typeof r.value === 'string' ? r.value : JSON.stringify(r.value)} placeholder="billing" onChange={(e) => setRule(idx, { value: e.target.value })} style={{ maxWidth: 220 }} />
                    <button className="copy-btn" title="Remove" onClick={() => set({ rules: form.rules.filter((_, i) => i !== idx) })}>
                      <Icon name="trash" />
                    </button>
                  </div>
                ))}
                <button className="btn sm" style={{ alignSelf: 'flex-start' }} onClick={() => set({ rules: [...form.rules, { path: '', value: '' }] })}>
                  <Icon name="plus" />
                  Add condition
                </button>
              </div>
            </Field>
          </>
        )}
      </div>
    </Modal>
  );
}
