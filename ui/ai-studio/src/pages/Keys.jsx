import { useState } from 'react';
import { useWorkspace } from '../App';
import { Badge, CopyButton, Empty, ErrorAlert, Field, LinesInput, Loading, Modal, NumberInput, PageHeader, Segmented, Select, TextInput, Toggle, useAsync, useConfirm, useToast } from '../components/ui';
import { BudgetModal } from '../components/BudgetModal';
import { Icon } from '../components/icons';
import { exactModel, isExpired, listApikeys, modelModeOf, modelOfPattern, modelRulesOf, OWNER_PATTERN, ownerOf, patternError, resetApikeySecret, saveApikey, usesWorkspaceQuotas, validUntilOf } from '../lib/apikeys';
import { bootstrap } from '../lib/bootstrap';
import { budgetsOfKey, keyBudgetOf, listBudgets, periodLabel, PERIODS, periodOf, saveBudget } from '../lib/budgets';
import { Resources, workspaceFilter } from '../lib/entities';
import { fmtCost, fmtDate, fmtDay, fmtInt, fmtRelative } from '../lib/format';
import { listWorkspaceModels } from '../lib/models';
import { Link } from '../lib/router';

const OWNER_KINDS = [
  { value: 'me', label: 'Me' },
  { value: 'teammate', label: 'Teammate' },
  { value: 'workspace', label: 'Workspace' },
];

const DAY = 24 * 3600 * 1000;

const EXPIRIES = [
  { value: 'never', label: 'Never' },
  { value: '7', label: 'In 7 days' },
  { value: '30', label: 'In 30 days' },
  { value: '90', label: 'In 90 days' },
  { value: '365', label: 'In 1 year' },
  { value: 'date', label: 'On a date…' },
];

const pad = (n) => String(n).padStart(2, '0');

// the local day of a date, as the value of a date input
function localDay(ts) {
  const d = new Date(ts);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

// a key valid until a day works through the end of that day
function endOfDay(day) {
  const [y, m, d] = day.split('-').map(Number);
  return new Date(y, m - 1, d, 23, 59, 59, 999).getTime();
}

// the validUntil of the form: null for no expiration, undefined while no date is picked
function expiryOf(form, apikey) {
  if (form.expiry === 'never') return null;
  if (form.expiry !== 'date') return Date.now() + Number(form.expiry) * DAY;
  if (!form.expiryDate) return undefined;
  const current = validUntilOf(apikey);
  // an untouched date keeps its exact time
  return current !== null && localDay(current) === form.expiryDate ? current : endOfDay(form.expiryDate);
}

function Expiry({ apikey }) {
  const v = validUntilOf(apikey);
  if (v === null) return <span className="muted">never</span>;
  if (isExpired(apikey)) {
    return (
      <Badge kind="negative" title={`Expired on ${fmtDate(v)}`}>
        Expired
      </Badge>
    );
  }
  if (v - Date.now() < 7 * DAY) {
    return (
      <Badge kind="warning" title={fmtDate(v)}>
        {fmtRelative(v)}
      </Badge>
    );
  }
  return <span title={fmtDate(v)}>{fmtDay(v)}</span>;
}

const MODEL_MODES = [
  { value: 'all', label: 'All models' },
  { value: 'selected', label: 'Selected models' },
  { value: 'custom', label: 'Custom rules' },
];

// the model restriction of a key, as a short label for the list
function ModelsBadge({ apikey }) {
  const rules = modelRulesOf(apikey);
  const mode = modelModeOf(rules);
  if (mode === 'all') return null;
  const title = [...rules.include.map((p) => `allowed: ${modelOfPattern(p) ?? p}`), ...rules.exclude.map((p) => `blocked: ${p}`)].join('\n');
  const count = rules.include.length;
  return (
    <Badge kind="info" title={title}>
      {mode === 'selected' ? `${count} model${count === 1 ? '' : 's'}` : 'model rules'}
    </Badge>
  );
}

// the models of the workspace to pick from, with the ids clients send; selected ids no longer listed stay visible
function ModelChecklist({ models, loading, value, onChange }) {
  const [q, setQ] = useState('');
  const byId = new Map(models.map((m) => [m.id, m]));
  const ids = [...new Set([...models.map((m) => m.id), ...value])];
  const needle = q.trim().toLowerCase();
  const shown = ids.filter((id) => !needle || id.toLowerCase().includes(needle));
  const toggle = (id, checked) => onChange(checked ? [...value, id] : value.filter((v) => v !== id));
  return (
    <div className="model-checklist">
      <TextInput value={q} onChange={setQ} placeholder="Filter models" />
      <div className="model-checklist-items">
        {loading && <Loading label="Loading the models of the workspace…" />}
        {!loading && shown.length === 0 && <div className="muted small">No model matches.</div>}
        {shown.map((id) => {
          const model = byId.get(id);
          return (
            <label key={id} className="check">
              <input type="checkbox" checked={value.includes(id)} onChange={(e) => toggle(id, e.target.checked)} />
              <span className="mono truncate">{id}</span>
              {model && model.modality !== 'text' && <span className="faint small">{model.modality}</span>}
              {!loading && !model && <Badge kind="warning">not listed</Badge>}
            </label>
          );
        })}
      </div>
      <div className="faint small">{value.length} selected</div>
    </div>
  );
}

function ownerKindOf(apikey) {
  const owner = ownerOf(apikey);
  if (!apikey) return 'me';
  if (!owner) return 'workspace';
  return owner === bootstrap.user.email ? 'me' : 'teammate';
}

function KeyModal({ workspace, apikey, budget, onClose, onSaved }) {
  const toast = useToast();
  const c = bootstrap.config;
  const [form, setForm] = useState(() => ({
    ownerKind: ownerKindOf(apikey),
    teammate: ownerKindOf(apikey) === 'teammate' ? ownerOf(apikey) : '',
    name: apikey ? apikey.clientName : '',
    description: apikey ? apikey.description : '',
    enabled: apikey ? apikey.enabled : true,
    expiry: validUntilOf(apikey) !== null ? 'date' : 'never',
    expiryDate: validUntilOf(apikey) !== null ? localDay(validUntilOf(apikey)) : '',
    modelMode: modelModeOf(modelRulesOf(apikey)),
    selectedModels: modelModeOf(modelRulesOf(apikey)) === 'selected' ? modelRulesOf(apikey).include.map(modelOfPattern) : [],
    includeRules: modelRulesOf(apikey).include,
    excludeRules: modelRulesOf(apikey).exclude,
    override: apikey ? !usesWorkspaceQuotas(apikey) : false,
    throttlingQuota: apikey ? apikey.throttlingQuota : c.default_throttling_quota,
    dailyQuota: apikey ? apikey.dailyQuota : c.default_daily_quota,
    monthlyQuota: apikey ? apikey.monthlyQuota : c.default_monthly_quota,
    credit: budget && budget.limits ? budget.limits.total_usd ?? null : null,
    period: budget ? periodOf(budget) : 'lifetime',
  }));
  const [saving, setSaving] = useState(false);
  const set = (patch) => setForm((f) => ({ ...f, ...patch }));
  const owner = form.ownerKind === 'me' ? bootstrap.user.email : form.ownerKind === 'teammate' ? form.teammate.trim() : null;
  const invalidOwner = form.ownerKind !== 'workspace' && !OWNER_PATTERN.test(owner || '');
  const validUntil = expiryOf(form, apikey);
  const wasExpired = !!apikey && isExpired(apikey);
  // an expired key can be saved as is, but a new date must be in the future
  const staysExpired = wasExpired && validUntil === validUntilOf(apikey);
  const pastDate = validUntil !== null && validUntil !== undefined && validUntil <= Date.now() && !staysExpired;
  const invalidExpiry = validUntil === undefined || pastDate;
  // the models of the workspace, only needed to pick some, and its load balancers (called `<name>/_default`)
  const models = useAsync(async () => {
    if (form.modelMode !== 'selected') return null;
    const [listing, providers] = await Promise.all([listWorkspaceModels(workspace), Resources.providers.list(workspaceFilter(workspace.id))]);
    const balancers = providers.filter((p) => p.provider === 'loadbalancer').map((p) => ({ id: `${p.name}/_default`, modality: 'load balancer' }));
    return [...(listing.models || []), ...balancers];
  }, [workspace.id, form.modelMode === 'selected']);
  const modelRules =
    form.modelMode === 'selected'
      ? { include: form.selectedModels.map(exactModel), exclude: [] }
      : form.modelMode === 'custom'
        ? { include: form.includeRules, exclude: form.excludeRules }
        : { include: [], exclude: [] };
  const rulesError = [...modelRules.include, ...modelRules.exclude].map(patternError).find(Boolean) || null;
  const invalidModels = (form.modelMode === 'selected' && form.selectedModels.length === 0) || !!rulesError;
  // an expired key reads as disabled: giving it a new date enables it again
  const setExpiry = (patch) =>
    setForm((f) => {
      const next = { ...f, ...patch };
      const v = expiryOf(next, apikey);
      return wasExpired && (v === null || (v !== undefined && v > Date.now())) ? { ...next, enabled: true } : next;
    });

  const save = async () => {
    setSaving(true);
    try {
      const saved = await saveApikey(workspace.id, { ...form, owner, validUntil, models: modelRules }, apikey);
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
          <button className="btn primary" disabled={!form.name.trim() || invalidOwner || invalidExpiry || invalidModels || saving} onClick={save}>
            {saving ? 'Saving…' : apikey ? 'Save' : 'Create'}
          </button>
        </>
      }
    >
      <Field label="Owner" hint="The usage of the key counts for its owner in Activity and Logs. Workspace keys have no individual owner: use them for shared apps and agents.">
        <div className="stack tight">
          <div>
            <Segmented value={form.ownerKind} onChange={(v) => set({ ownerKind: v })} options={OWNER_KINDS} />
          </div>
          {form.ownerKind === 'me' && <TextInput value={bootstrap.user.email} onChange={() => {}} disabled />}
          {form.ownerKind === 'teammate' && <TextInput value={form.teammate} onChange={(v) => set({ teammate: v })} placeholder="jane@company.com" type="email" />}
        </div>
      </Field>
      <Field label="Name">
        <TextInput value={form.name} onChange={(v) => set({ name: v })} placeholder="My app" autoFocus />
      </Field>
      <div className="form-grid">
        <Field
          label="Expires"
          hint={
            staysExpired
              ? `Expired on ${fmtDate(validUntil)}: the gateway refuses this key until it gets a new date.`
              : form.expiry === 'never'
                ? 'The key works until you disable or delete it.'
                : form.expiry === 'date'
                  ? 'The key works through the end of that day, then the gateway refuses it.'
                  : `On ${fmtDate(validUntil)}, then the gateway refuses it.`
          }
        >
          <Select value={form.expiry} onChange={(v) => setExpiry({ expiry: v, expiryDate: v === 'date' && !form.expiryDate ? localDay(Date.now() + 30 * DAY) : form.expiryDate })} options={EXPIRIES} />
        </Field>
        {form.expiry === 'date' && (
          <Field label="Valid until" error={pastDate ? 'Pick a date in the future.' : validUntil === undefined ? 'Pick a date.' : null}>
            <TextInput type="date" value={form.expiryDate} min={localDay(Date.now())} onChange={(v) => setExpiry({ expiryDate: v })} />
          </Field>
        )}
      </div>
      <Field
        label="Models"
        hint={
          form.modelMode === 'all'
            ? 'The key can call every model of the workspace, within the model access of its guardrails.'
            : form.modelMode === 'selected'
              ? 'The key can only call these models. Load balancers and routers can still send its calls to any of their targets.'
              : 'Regular expressions on the model ids: model, provider/model or provider###model. Empty lists allow everything.'
        }
        error={form.modelMode === 'selected' && form.selectedModels.length === 0 ? 'Select at least one model.' : rulesError}
      >
        <div className="stack tight">
          <div>
            <Segmented value={form.modelMode} onChange={(v) => set({ modelMode: v })} options={MODEL_MODES} />
          </div>
          {form.modelMode === 'selected' && (
            <ModelChecklist models={models.data || []} loading={models.loading} value={form.selectedModels} onChange={(v) => set({ selectedModels: v })} />
          )}
          {form.modelMode === 'custom' && (
            <div className="form-grid">
              <Field label="Allowed models" hint="e.g. gpt-4o.* or openai/.*">
                <LinesInput value={form.includeRules} onChange={(v) => set({ includeRules: v })} rows={3} />
              </Field>
              <Field label="Blocked models" hint="e.g. .*-preview or azure###.*">
                <LinesInput value={form.excludeRules} onChange={(v) => set({ excludeRules: v })} rows={3} />
              </Field>
            </div>
          )}
        </div>
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
        <Field label="Enabled" hint={staysExpired ? 'Expired keys stay disabled.' : null}>
          <Toggle value={form.enabled && !staysExpired} disabled={staysExpired} onChange={(v) => set({ enabled: v })} />
        </Field>
      </div>
    </Modal>
  );
}

function RevealModal({ apikey, onClose, onReset }) {
  const toast = useToast();
  const confirm = useConfirm();
  const [full, setFull] = useState(null);
  const [fresh, setFresh] = useState(null);
  const [resetting, setResetting] = useState(false);
  const key = useAsync(() => Resources.apikeys.get(apikey.clientId), [apikey.clientId]);
  const current = fresh || key.data || apikey;
  const bearer = current.bearer;
  const validUntil = validUntilOf(current);

  const reset = () => {
    confirm({
      title: `Reset the secret of ${apikey.clientName}?`,
      message: 'The key gets a new secret. Applications still using the current one receive 401 errors within a few seconds.',
      danger: true,
      confirmLabel: 'Reset secret',
    }).then(async (ok) => {
      if (!ok) return;
      setResetting(true);
      try {
        setFresh(await resetApikeySecret(apikey.clientId));
        setFull(true);
        toast.success('Secret reset: copy the new key');
        if (onReset) onReset();
      } catch (e) {
        toast.error(e);
      } finally {
        setResetting(false);
      }
    });
  };

  return (
    <Modal
      open
      onClose={onClose}
      title={`API key ${apikey.clientName}`}
      footer={
        <>
          <button className="btn danger left" disabled={resetting || key.loading} onClick={reset} title="Replace the secret of this key">
            <Icon name="refresh" />
            {resetting ? 'Resetting…' : 'Reset secret'}
          </button>
          <button className="btn primary" onClick={onClose}>
            Done
          </button>
        </>
      }
    >
      <p className="muted">
        {fresh ? 'This is the new key: update your applications with it.' : 'Use this value as the bearer token of your OpenAI SDK.'} Keep it secret.
      </p>
      {isExpired(current) ? (
        <p className="warning-text">This key expired on {fmtDate(validUntil)}: edit it to give it a new date.</p>
      ) : (
        validUntil !== null && <p className="muted small">Valid until {fmtDate(validUntil)}.</p>
      )}
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
                  <th>Owner</th>
                  <th>Client id</th>
                  <th>Key limit</th>
                  <th>Budgets</th>
                  <th>Quotas</th>
                  <th>Expires</th>
                  <th>Status</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {keys.map((k) => {
                  const b = keyBudgetOf(budgets, k.clientId);
                  return (
                    <tr key={k.clientId}>
                      <td>
                        <span className="row nowrap" style={{ gap: 8 }}>
                          {k.clientName}
                          <ModelsBadge apikey={k} />
                        </span>
                      </td>
                      <td className="truncate" style={{ maxWidth: 220 }}>
                        {ownerOf(k) ? (
                          <Link className="link" to={`/workspaces/${workspace.id}/users/${encodeURIComponent(ownerOf(k))}`} title={`Profile of ${ownerOf(k)}`}>
                            {ownerOf(k)}
                          </Link>
                        ) : (
                          <span className="muted">Workspace key</span>
                        )}
                      </td>
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
                          const applying = budgetsOfKey(budgets, k.clientId, ownerOf(k));
                          return applying.length ? <Badge title={applying.map((x) => x.name).join(', ')}>{applying.length}</Badge> : <span className="muted">none</span>;
                        })()}
                      </td>
                      <td className="muted">{usesWorkspaceQuotas(k) ? 'default' : `${fmtInt(k.throttlingQuota)}/s · ${fmtInt(k.dailyQuota)}/d`}</td>
                      <td className="nowrap">
                        <Expiry apikey={k} />
                      </td>
                      <td>
                        {isExpired(k) ? (
                          <Toggle value={false} disabled onChange={() => {}} title="Expired: edit the key to give it a new date" />
                        ) : (
                          <Toggle value={k.enabled} onChange={() => toggle(k)} title={k.enabled ? 'Enabled' : 'Disabled'} />
                        )}
                      </td>
                      {/* the secondary actions are icons: with an owner column, labels push Delete out of a laptop screen */}
                      <td className="actions">
                        <Link className="btn sm icon" to={`/workspaces/${workspace.id}/activity?apikey=${encodeURIComponent(k.clientId)}`} title="Usage of this key">
                          <Icon name="chart" />
                        </Link>
                        <button className="btn sm icon" onClick={() => setRevealing(k)} title="Show or reset the key">
                          <Icon name="key" />
                        </button>
                        <button className="btn sm icon" onClick={() => setBudgeting(k.clientId)} title="Create a budget for this key">
                          <Icon name="wallet" />
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
      {revealing && <RevealModal apikey={revealing} onClose={() => setRevealing(null)} onReset={() => data.reload()} />}
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
