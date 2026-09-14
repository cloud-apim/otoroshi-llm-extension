import { api, EXT_ADMIN_API } from './api';
import { Resources, randomId, workspaceFilter } from './entities';
import { workspaceLocation, workspaceMetadata } from './workspaces';

export const PERIODS = [
  { value: 'lifetime', label: 'Never (lifetime)', duration: { value: 100, unit: 'year' } },
  { value: 'daily', label: 'Daily', duration: { value: 1, unit: 'day' } },
  { value: 'weekly', label: 'Weekly (7 days)', duration: { value: 7, unit: 'day' } },
  { value: 'monthly', label: 'Monthly (30 days)', duration: { value: 30, unit: 'day' } },
  { value: 'yearly', label: 'Yearly', duration: { value: 1, unit: 'year' } },
];

export function periodOf(budget) {
  const d = (budget && budget.duration) || {};
  const found = PERIODS.find((p) => p.duration.value === d.value && p.duration.unit === d.unit);
  return found ? found.value : 'custom';
}

export function periodLabel(budget) {
  const p = PERIODS.find((x) => x.value === periodOf(budget));
  if (p) return p.value === 'lifetime' ? 'lifetime' : p.label.toLowerCase();
  const d = budget.duration || {};
  return `every ${d.value} ${d.unit}${d.value > 1 ? 's' : ''}`;
}

export function listBudgets(wsId) {
  return Resources.budgets.list(workspaceFilter(wsId));
}

export const WORKSPACE_RULE_PATH = '$.provider.metadata.ai_studio_workspace';

// the conditions a user added on top of the workspace rule
export function extraRulesOf(budget) {
  return ((budget && budget.scope && budget.scope.rules) || []).filter((r) => r.path !== WORKSPACE_RULE_PATH).map((r) => ({ path: r.path, value: r.value }));
}

// how the scope of an existing budget is presented: the whole workspace, one api key or custom
export function scopeModeOf(budget) {
  const scope = (budget && budget.scope) || {};
  const keys = scope.apikeys || [];
  const users = scope.users || [];
  if (users.length === 0 && extraRulesOf(budget).length === 0) {
    if (keys.length === 0) return 'workspace';
    if (keys.length === 1) return 'apikey';
  }
  return 'custom';
}

// Every budget of a workspace is scoped to the workspace first (a rule matching the workspace
// metadata of the provider serving the call, that the user cannot remove), then optionally narrowed
// with consumers (api keys, studio users), models and extra json path conditions, all of them
// combined with the workspace rule.
export function buildBudget(wsId, form, existing) {
  const period = PERIODS.find((p) => p.value === form.period) || PERIODS[0];
  const now = new Date();
  const limits = { ...((existing && existing.limits) || {}) };
  ['total_usd', 'total_tokens'].forEach((k) => delete limits[k]);
  if (form.usd !== null && form.usd !== undefined && form.usd !== '') limits.total_usd = Number(form.usd);
  if (form.tokens !== null && form.tokens !== undefined && form.tokens !== '') limits.total_tokens = Number(form.tokens);
  const prevScope = (existing && existing.scope) || {};
  return {
    ...(existing || {}),
    _loc: (existing && existing._loc) || workspaceLocation(wsId),
    id: (existing && existing.id) || `ai-budget_ais_${randomId(20)}`,
    name: form.name,
    description: form.description || '',
    tags: (existing && existing.tags) || [],
    metadata: { ...((existing && existing.metadata) || {}), ...(form.metadata || {}), ...workspaceMetadata(wsId, 'budget') },
    enabled: form.enabled !== false,
    start_at: (existing && existing.start_at) || now.toISOString(),
    end_at: (existing && existing.end_at && form.period === periodOf(existing)) ? existing.end_at : new Date(now.getTime() + 100 * 365 * 24 * 3600 * 1000).toISOString(),
    duration: form.period === 'custom' && existing ? existing.duration : period.duration,
    limits,
    scope: {
      extract_from_apikey_meta: false,
      extract_from_apikey_group_meta: false,
      extract_from_user_meta: false,
      extract_from_user_auth_module_meta: false,
      extract_from_provider_meta: false,
      groups: [],
      providers: [],
      ...prevScope,
      apikeys: form.apikeys || [],
      users: form.users !== undefined ? form.users : prevScope.users || [],
      models: form.models || [],
      always_apply_rules: true,
      rules: [
        { path: WORKSPACE_RULE_PATH, value: wsId },
        ...(form.rules !== undefined ? form.rules : extraRulesOf(existing))
          .filter((r) => r.path && r.path.trim() && r.path.trim() !== WORKSPACE_RULE_PATH)
          .map((r) => ({ path: r.path.trim(), value: r.value })),
      ],
      rules_match_mode: 'all',
    },
    action_on_exceed: {
      ...((existing && existing.action_on_exceed) || {}),
      mode: form.mode || 'block',
      alert_on_exceed: true,
      alert_on_almost_exceed: true,
      alert_on_almost_exceed_percentage: Number(form.alert) || 80,
    },
  };
}

export async function saveBudget(wsId, form, existing) {
  const budget = buildBudget(wsId, form, existing);
  if (existing && existing.id) await Resources.budgets.update(budget);
  else await Resources.budgets.create(budget);
  return budget;
}

export function budgetConsumption(id) {
  return api.get(`${EXT_ADMIN_API}/budgets/${encodeURIComponent(id)}/consumption`);
}

export function resetBudget(id) {
  return api.post(`${EXT_ADMIN_API}/budgets/${encodeURIComponent(id)}/consumption/_reset`, {});
}

export function apikeyQuotas(clientId) {
  return api.get(`/bo/api/proxy/api/apikeys/${encodeURIComponent(clientId)}/quotas`);
}

// budget dedicated to a single key (the "credit limit" of the api keys page)
export function keyBudgetOf(budgets, clientId) {
  return budgets.find((b) => b.metadata && b.metadata.ai_studio_key_limit === clientId);
}
