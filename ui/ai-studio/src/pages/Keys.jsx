import { useState } from 'react';
import { useWorkspace } from '../App';
import { Badge, CopyButton, Empty, ErrorAlert, Field, Loading, Modal, NumberInput, PageHeader, Select, TextInput, Toggle, useAsync, useConfirm, useToast } from '../components/ui';
import { BudgetModal } from '../components/BudgetModal';
import { Icon } from '../components/icons';
import { listApikeys, saveApikey, usesWorkspaceQuotas } from '../lib/apikeys';
import { bootstrap } from '../lib/bootstrap';
import { keyBudgetOf, listBudgets, periodLabel, PERIODS, periodOf, saveBudget } from '../lib/budgets';
import { Resources } from '../lib/entities';
import { fmtCost, fmtInt } from '../lib/format';
import { Link } from '../lib/router';

function KeyModal({ workspace, apikey, budget, onClose, onSaved }) {
  const toast = useToast();
  const c = bootstrap.config;
  const [form, setForm] = useState(() => ({
    name: apikey ? apikey.clientName : '',
    description: apikey ? apikey.description : '',
    enabled: apikey ? apikey.enabled : true,
    override: apikey ? !usesWorkspaceQuotas(apikey) : false,
    throttlingQuota: apikey ? apikey.throttlingQuota : c.default_throttling_quota,
    dailyQuota: apikey ? apikey.dailyQuota : c.default_daily_quota,
    monthlyQuota: apikey ? apikey.monthlyQuota : c.default_monthly_quota,
    credit: budget && budget.limits ? budget.limits.total_usd ?? null : null,
    period: budget ? periodOf(budget) : 'lifetime',
  }));
  const [saving, setSaving] = useState(false);
  const set = (patch) => setForm((f) => ({ ...f, ...patch }));

  const save = async () => {
    setSaving(true);
    try {
      const saved = await saveApikey(workspace.id, form, apikey);
      const hasCredit = form.credit !== null && form.credit !== '' && !Number.isNaN(Number(form.credit));
      if (hasCredit) {
        await saveBudget(
          workspace.id,
          {
            name: `${form.name} limit`,
            description: `Credit limit of the api key ${form.name}`,
            usd: form.credit,
            period: form.period,
            apikeys: [saved.clientId],
            mode: 'block',
            enabled: true,
            metadata: { ai_studio_key_limit: saved.clientId },
          },
          budget
        );
      } else if (budget) {
        await Resources.budgets.delete(budget.id);
      }
      toast.success(apikey ? 'API key saved' : 'API key created');
      onSaved(apikey ? null : saved);
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
      title={apikey ? `Edit ${apikey.clientName}` : 'Create API Key'}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button className="btn primary" disabled={!form.name.trim() || saving} onClick={save}>
            {saving ? 'Saving…' : apikey ? 'Save' : 'Create'}
          </button>
        </>
      }
    >
      <Field label="Name">
        <TextInput value={form.name} onChange={(v) => set({ name: v })} placeholder="My app" autoFocus />
      </Field>
      <div className="form-grid">
        <Field label="Credit limit (USD)" hint="Leave blank for unlimited. Enforced by a budget scoped to this key.">
          <NumberInput value={form.credit} onChange={(v) => set({ credit: v })} placeholder="Leave blank for unlimited" step="0.01" min="0" />
        </Field>
        <Field label="Reset limit every…">
          <Select value={form.period} onChange={(v) => set({ period: v })} options={PERIODS.map((p) => ({ value: p.value, label: p.label }))} />
        </Field>
      </div>
      <Field label="Quotas">
        <label className="check">
          <input type="checkbox" checked={form.override} onChange={(e) => set({ override: e.target.checked })} />
          Override the default quotas for this key
        </label>
      </Field>
      <div className="form-grid">
        <Field label="Requests per second" hint={`default: ${fmtInt(c.default_throttling_quota)}`}>
          <NumberInput value={form.throttlingQuota} disabled={!form.override} onChange={(v) => set({ throttlingQuota: v })} />
        </Field>
        <Field label="Requests per day" hint={`default: ${fmtInt(c.default_daily_quota)}`}>
          <NumberInput value={form.dailyQuota} disabled={!form.override} onChange={(v) => set({ dailyQuota: v })} />
        </Field>
        <Field label="Requests per month" hint={`default: ${fmtInt(c.default_monthly_quota)}`}>
          <NumberInput value={form.monthlyQuota} disabled={!form.override} onChange={(v) => set({ monthlyQuota: v })} />
        </Field>
        <Field label="Enabled">
          <Toggle value={form.enabled} onChange={(v) => set({ enabled: v })} />
        </Field>
      </div>
    </Modal>
  );
}

function RevealModal({ apikey, onClose }) {
  const [full, setFull] = useState(null);
  const key = useAsync(() => Resources.apikeys.get(apikey.clientId), [apikey.clientId]);
  const bearer = (key.data && key.data.bearer) || apikey.bearer;
  return (
    <Modal
      open
      onClose={onClose}
      title={`API key ${apikey.clientName}`}
      footer={
        <button className="btn primary" onClick={onClose}>
          Done
        </button>
      }
    >
      <p className="muted">Use this value as the bearer token of your OpenAI SDK. Keep it secret.</p>
      {key.loading && <Loading />}
      {bearer && (
        <>
          <div className="secret">{full ? bearer : bearer.substring(0, 24) + '••••••••••••••••••••••••'}</div>
          <div className="row">
            <CopyButton text={bearer} className="btn sm" label="Copy" />
            <button className="btn sm ghost" onClick={() => setFull(!full)}>
              <Icon name={full ? 'eyeOff' : 'eye'} />
              {full ? 'Hide' : 'Reveal'}
            </button>
          </div>
        </>
      )}
    </Modal>
  );
}

export function KeysPage() {
  const { workspace } = useWorkspace();
  const toast = useToast();
  const confirm = useConfirm();
  const [editing, setEditing] = useState(null);
  const [revealing, setRevealing] = useState(null);
  const [budgeting, setBudgeting] = useState(null);
  const data = useAsync(async () => {
    const [keys, budgets] = await Promise.all([listApikeys(workspace.id), listBudgets(workspace.id)]);
    return { keys, budgets };
  }, [workspace.id]);
  const keys = (data.data && data.data.keys) || [];
  const budgets = (data.data && data.data.budgets) || [];

  const toggle = (apikey) => {
    Resources.apikeys
      .update({ ...apikey, enabled: !apikey.enabled })
      .then(() => data.reload())
      .catch(toast.error);
  };

  const remove = (apikey) => {
    confirm({ title: `Delete ${apikey.clientName}?`, message: 'Applications using this key will immediately receive 401 errors.', danger: true, confirmLabel: 'Delete' }).then(async (ok) => {
      if (!ok) return;
      try {
        const b = keyBudgetOf(budgets, apikey.clientId);
        if (b) await Resources.budgets.delete(b.id);
        await Resources.apikeys.delete(apikey.clientId);
        toast.success('API key deleted');
        data.reload();
      } catch (e) {
        toast.error(e);
      }
    });
  };

  return (
    <div className="content">
      <PageHeader title="API Keys" description="Create and manage API keys for this workspace.">
        <button className="btn primary" onClick={() => setEditing({})}>
          New Key
        </button>
      </PageHeader>
      <ErrorAlert error={data.error} />
      <div className="card flush">
        {data.loading && !data.data && (
          <div style={{ padding: 20 }}>
            <Loading />
          </div>
        )}
        {data.data && keys.length === 0 && (
          <Empty
            title="No API key yet"
            action={
              <button className="btn primary" onClick={() => setEditing({})}>
                Create a key
              </button>
            }
          >
            Keys authenticate the calls made to {workspace.baseUrl}
          </Empty>
        )}
        {keys.length > 0 && (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Client id</th>
                  <th>Key limit</th>
                  <th>Budgets</th>
                  <th>Quotas</th>
                  <th>Status</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {keys.map((k) => {
                  const b = keyBudgetOf(budgets, k.clientId);
                  return (
                    <tr key={k.clientId}>
                      <td>{k.clientName}</td>
                      <td className="mono truncate" style={{ maxWidth: 260 }}>
                        {k.clientId}
                      </td>
                      <td>
                        {b && b.limits && b.limits.total_usd !== undefined && b.limits.total_usd !== null ? (
                          <>
                            {fmtCost(b.limits.total_usd)} <span className="muted small">· {periodLabel(b)}</span>
                          </>
                        ) : (
                          <span className="muted">unlimited</span>
                        )}
                      </td>
                      <td>
                        {(() => {
                          // budgets counting the calls of this key: workspace wide ones and the ones naming it
                          const applying = budgets.filter((x) => x.enabled && (((x.scope && x.scope.apikeys) || []).length === 0 ? ((x.scope && x.scope.users) || []).length === 0 : x.scope.apikeys.includes(k.clientId)));
                          return applying.length ? <Badge title={applying.map((x) => x.name).join(', ')}>{applying.length}</Badge> : <span className="muted">none</span>;
                        })()}
                      </td>
                      <td className="muted">{usesWorkspaceQuotas(k) ? 'default' : `${fmtInt(k.throttlingQuota)}/s · ${fmtInt(k.dailyQuota)}/d`}</td>
                      <td>
                        <Toggle value={k.enabled} onChange={() => toggle(k)} title={k.enabled ? 'Enabled' : 'Disabled'} />
                      </td>
                      <td className="actions">
                        <Link className="btn sm" to={`/workspaces/${workspace.id}/activity?apikey=${encodeURIComponent(k.clientId)}`} title="Usage of this key">
                          <Icon name="chart" />
                          Activity
                        </Link>
                        <button className="btn sm" onClick={() => setRevealing(k)}>
                          <Icon name="key" />
                          Key
                        </button>
                        <button className="btn sm" onClick={() => setBudgeting(k.clientId)} title="Create a budget for this key">
                          <Icon name="wallet" />
                          Budget
                        </button>
                        <button className="btn sm" onClick={() => setEditing({ apikey: k, budget: b })}>
                          Edit
                        </button>
                        <button className="btn sm ghost" onClick={() => remove(k)}>
                          Delete
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>
      {editing && (
        <KeyModal
          workspace={workspace}
          apikey={editing.apikey}
          budget={editing.budget}
          onClose={() => setEditing(null)}
          onSaved={(created) => {
            setEditing(null);
            data.reload();
            if (created) setRevealing(created);
          }}
        />
      )}
      {revealing && <RevealModal apikey={revealing} onClose={() => setRevealing(null)} />}
      {budgeting && (
        <BudgetModal
          workspace={workspace}
          keys={keys}
          apikey={budgeting}
          onClose={() => setBudgeting(null)}
          onSaved={() => {
            setBudgeting(null);
            data.reload();
          }}
        />
      )}
      {keys.length > 0 && (
        <p className="faint small mt">
          <Badge kind="info">Tip</Badge> New keys and changes are picked up by the gateway within a few seconds.
        </p>
      )}
    </div>
  );
}
