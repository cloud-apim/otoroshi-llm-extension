import { useState } from 'react';
import { useCan, useWorkspace } from '../App';
import { Badge, CopyButton, Empty, ErrorAlert, Field, LinesInput, Loading, Modal, NumberInput, PageHeader, Pager, Segmented, Select, TextInput, Toggle, useAsync, useConfirm, useToast, usePaged } from '../components/ui';
import { BudgetModal } from '../components/BudgetModal';
import { Icon } from '../components/icons';
import { exactModel, isExpired, keyOp, modelModeOf, modelOfPattern, modelRulesOf, OWNER_PATTERN, ownerOf, patternError, usesWorkspaceQuotas, validUntilOf } from '../lib/apikeys';
import { backend } from '../lib/backend';
import { bootstrap } from '../lib/bootstrap';
import { budgetsListOp, budgetsOfKey, keyBudgetOf, periodLabel, PERIODS } from '../lib/budgets';
import { fmtCost, fmtDate, fmtDay, fmtInt, fmtRelative } from '../lib/format';
import { labelOfModelId, modelLabel } from '../lib/modelmeta';
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
              <span className="mono truncate" title={id}>{model ? modelLabel(model) : labelOfModelId(id)}</span>
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

// `own`: a key of the person, who does not manage the keys of the workspace. They create it for themselves with
// its expiry and its models, and then only rename it: what restricts a key is set by who manages the keys.
function KeyModal({ workspace, apikey, own, onClose, onSaved }) {
  const toast = useToast();
  const c = bootstrap.config;
  const [form, setForm] = useState(() => ({
    ownerKind: ownerKindOf(apikey),
    teammate: ownerKindOf(apikey) === 'teammate' ? ownerOf(apikey) : '',
    name: apikey ? apikey.name : '',
    description: apikey ? apikey.description : '',
    enabled: apikey ? apikey.enabled : true,
    expiry: validUntilOf(apikey) !== null ? 'date' : 'never',
    expiryDate: validUntilOf(apikey) !== null ? localDay(validUntilOf(apikey)) : '',
    modelMode: modelModeOf(modelRulesOf(apikey)),
    selectedModels: modelModeOf(modelRulesOf(apikey)) === 'selected' ? modelRulesOf(apikey).include.map(modelOfPattern) : [],
    includeRules: modelRulesOf(apikey).include,
    excludeRules: modelRulesOf(apikey).exclude,
    override: apikey ? !usesWorkspaceQuotas(apikey) : false,
    throttlingQuota: apikey ? apikey.quotas.throttling_quota : c.default_throttling_quota,
    dailyQuota: apikey ? apikey.quotas.daily_quota : c.default_daily_quota,
    monthlyQuota: apikey ? apikey.quotas.monthly_quota : c.default_monthly_quota,
    credit: apikey && apikey.credit_limit ? apikey.credit_limit.usd ?? null : null,
    period: apikey && apikey.credit_limit ? apikey.credit_limit.period : 'lifetime',
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
    // the load balancers are a part of the configuration
    const [listing, balancers] = await Promise.all([listWorkspaceModels(workspace), own ? [] : backend.run('balancers.list', workspace.id)]);
    return [...(listing.models || []), ...balancers.map((b) => ({ id: `${b.name}/_default`, modality: 'load balancer' }))];
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

  // the credit limit is a budget scoped to the key, kept by the api with the key
  const save = async () => {
    setSaving(true);
    try {
      const hasCredit = form.credit !== null && form.credit !== '' && !Number.isNaN(Number(form.credit));
      const body = {
        name: form.name,
        description: form.description || '',
        enabled: form.enabled !== false,
        owner,
        valid_until: validUntil,
        models: modelRules,
        quotas: form.override ? { throttling_quota: Number(form.throttlingQuota), daily_quota: Number(form.dailyQuota), monthly_quota: Number(form.monthlyQuota) } : null,
        credit_limit: hasCredit ? { usd: Number(form.credit), period: form.period } : null,
      };
      // the api owns a key of their own to the person
      const ownBody = apikey ? { name: form.name } : { name: form.name, enabled: true, valid_until: validUntil, models: modelRules };
      const saved = apikey
        ? await backend.run(keyOp(workspace, 'update'), workspace.id, { kid: apikey.client_id, body: own ? ownBody : body })
        : await backend.run(keyOp(workspace, 'create'), workspace.id, { body: own ? ownBody : body });
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
      title={apikey ? `Edit ${apikey.name}` : 'Create API Key'}
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
      {!own && (
        <Field label="Owner" hint="The usage of the key counts for its owner in Activity and Logs. Workspace keys have no individual owner: use them for shared apps and agents.">
          <div className="stack tight">
            <div>
              <Segmented value={form.ownerKind} onChange={(v) => set({ ownerKind: v })} options={OWNER_KINDS} />
            </div>
            {form.ownerKind === 'me' && <TextInput value={bootstrap.user.email} onChange={() => {}} disabled />}
            {form.ownerKind === 'teammate' && <TextInput value={form.teammate} onChange={(v) => set({ teammate: v })} placeholder="jane@company.com" type="email" />}
          </div>
        </Field>
      )}
      <Field label="Name" hint={own ? 'The key is yours: its usage counts for you, and the budgets naming you apply to it.' : null}>
        <TextInput value={form.name} onChange={(v) => set({ name: v })} placeholder="My app" autoFocus />
      </Field>
      {own && apikey && <p className="muted small">Its expiry, its models and its limits are set by who manages the keys of the workspace.</p>}
      <fieldset className="bare" hidden={own && !!apikey}>
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
      </fieldset>
      <fieldset className="bare" hidden={own}>
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
      </fieldset>
    </Modal>
  );
}

function RevealModal({ workspace, apikey, onClose, onReset }) {
  const toast = useToast();
  const confirm = useConfirm();
  const [full, setFull] = useState(null);
  const [fresh, setFresh] = useState(null);
  const [resetting, setResetting] = useState(false);
  // the only read of a key giving its secret (AI Studio Enterprise records who revealed it)
  const key = useAsync(() => backend.run(keyOp(workspace, 'reveal'), workspace.id, { kid: apikey.client_id }), [apikey.client_id]);
  const current = fresh || key.data || apikey;
  const bearer = current.bearer;
  const validUntil = validUntilOf(current);

  const reset = () => {
    confirm({
      title: `Reset the secret of ${apikey.name}?`,
      message: 'The key gets a new secret. Applications still using the current one receive 401 errors within a few seconds.',
      danger: true,
      confirmLabel: 'Reset secret',
    }).then(async (ok) => {
      if (!ok) return;
      setResetting(true);
      try {
        setFresh(await backend.run(keyOp(workspace, 'resetSecret'), workspace.id, { kid: apikey.client_id }));
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
      title={`API key ${apikey.name}`}
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
  const can = useCan();
  // managing every key and seeing their secret, and making budgets for them
  const manage = can('keys:manage');
  const write = can('config:write');
  // without the configuration, the keys of the person only (AI Studio Enterprise)
  const own = !can('config:read');
  const mine = own && can('keys:own');
  const toast = useToast();
  const confirm = useConfirm();
  const [editing, setEditing] = useState(null);
  const [revealing, setRevealing] = useState(null);
  const [budgeting, setBudgeting] = useState(null);
  const data = useAsync(async () => {
    const [keys, budgets] = await Promise.all([backend.run(keyOp(workspace, 'list'), workspace.id), backend.run(budgetsListOp(workspace), workspace.id)]);
    return { keys, budgets };
  }, [workspace.id]);
  const keys = (data.data && data.data.keys) || [];
  const budgets = (data.data && data.data.budgets) || [];
  const [search, setSearch] = useState('');
  const needle = search.trim().toLowerCase();
  const found = needle ? keys.filter((k) => [k.name, ownerOf(k), k.client_id].some((v) => (v || '').toLowerCase().includes(needle))) : keys;
  const paged = usePaged(found, 20, needle);

  const toggle = (apikey) => {
    backend
      .run(keyOp(workspace, 'update'), workspace.id, { kid: apikey.client_id, body: { enabled: !apikey.enabled } })
      .then(() => data.reload())
      .catch(toast.error);
  };

  const remove = (apikey) => {
    confirm({ title: `Delete ${apikey.name}?`, message: 'Applications using this key will immediately receive 401 errors.', danger: true, confirmLabel: 'Delete' }).then(async (ok) => {
      if (!ok) return;
      try {
        // its credit limit goes with it
        await backend.run(keyOp(workspace, 'delete'), workspace.id, { kid: apikey.client_id });
        toast.success('API key deleted');
        data.reload();
      } catch (e) {
        toast.error(e);
      }
    });
  };

  return (
    <div className="content">
      <PageHeader title="API Keys" description={own ? 'Your API keys for this workspace.' : 'Create and manage API keys for this workspace.'}>
        {(manage || mine) && (
          <button className="btn primary" onClick={() => setEditing({})}>
            New Key
          </button>
        )}
      </PageHeader>
      <ErrorAlert error={data.error} />
      {keys.length > 0 && (
        <div className="row" style={{ marginBottom: 12 }}>
          <input className="input search sm" style={{ maxWidth: 280 }} placeholder="Search name, owner, client id" value={search} onChange={(e) => setSearch(e.target.value)} />
        </div>
      )}
      <div className="card flush">
        <Pager paged={paged} position="top" />
        {data.loading && !data.data && (
          <div style={{ padding: 20 }}>
            <Loading />
          </div>
        )}
        {data.data && keys.length === 0 && (
          <Empty
            title="No API key yet"
            action={
              manage || mine ? (
                <button className="btn primary" onClick={() => setEditing({})}>
                  Create a key
                </button>
              ) : null
            }
          >
            Keys authenticate the calls made to {workspace.base_url}
          </Empty>
        )}
        {keys.length > 0 && found.length === 0 && <Empty>No key matches “{search.trim()}”.</Empty>}
        {found.length > 0 && (
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
                {paged.shown.map((k) => {
                  const b = keyBudgetOf(budgets, k.client_id);
                  return (
                    <tr key={k.client_id}>
                      <td>
                        <span className="row nowrap" style={{ gap: 8 }}>
                          {k.name}
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
                        {k.client_id}
                      </td>
                      <td>
                        {k.credit_limit && k.credit_limit.usd !== null && k.credit_limit.usd !== undefined ? (
                          <>
                            {fmtCost(k.credit_limit.usd)} <span className="muted small">· {b ? periodLabel(b) : k.credit_limit.period}</span>
                          </>
                        ) : (
                          <span className="muted">unlimited</span>
                        )}
                      </td>
                      <td>
                        {(() => {
                          const applying = budgetsOfKey(budgets, k.client_id, ownerOf(k));
                          return applying.length ? <Badge title={applying.map((x) => x.name).join(', ')}>{applying.length}</Badge> : <span className="muted">none</span>;
                        })()}
                      </td>
                      <td className="muted">{usesWorkspaceQuotas(k) ? 'default' : `${fmtInt(k.quotas.throttling_quota)}/s · ${fmtInt(k.quotas.daily_quota)}/d`}</td>
                      <td className="nowrap">
                        <Expiry apikey={k} />
                      </td>
                      <td>
                        {isExpired(k) ? (
                          <Toggle value={false} disabled onChange={() => {}} title="Expired: edit the key to give it a new date" />
                        ) : (
                          // a key of their own is disabled by the person, enabled again by who manages the keys
                          <Toggle value={k.enabled} disabled={!manage && !(mine && k.enabled)} onChange={() => toggle(k)} title={k.enabled ? 'Enabled' : 'Disabled'} />
                        )}
                      </td>
                      {/* the secondary actions are icons: with an owner column, labels push Delete out of a laptop screen */}
                      <td className="actions">
                        {can('activity:read') && (
                          <Link className="btn sm icon" to={`/workspaces/${workspace.id}/activity?apikey=${encodeURIComponent(k.client_id)}`} title="Usage of this key">
                            <Icon name="chart" />
                          </Link>
                        )}
                        {(manage || mine) && (
                          <button className="btn sm icon" onClick={() => setRevealing(k)} title="Show or reset the key">
                            <Icon name="key" />
                          </button>
                        )}
                        {write && (
                          <button className="btn sm icon" onClick={() => setBudgeting(k.client_id)} title="Create a budget for this key">
                            <Icon name="wallet" />
                          </button>
                        )}
                        {(manage || mine) && (
                          <button className="btn sm" onClick={() => setEditing({ apikey: k })}>
                            Edit
                          </button>
                        )}
                        {(manage || mine) && (
                          <button className="btn sm ghost" onClick={() => remove(k)}>
                            Delete
                          </button>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
        <Pager paged={paged} position="bottom" />
      </div>
      {editing && (
        <KeyModal
          workspace={workspace}
          apikey={editing.apikey}
          own={own}
          onClose={() => setEditing(null)}
          onSaved={(created) => {
            setEditing(null);
            data.reload();
            if (created) setRevealing(created);
          }}
        />
      )}
      {revealing && <RevealModal workspace={workspace} apikey={revealing} onClose={() => setRevealing(null)} onReset={() => data.reload()} />}
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
