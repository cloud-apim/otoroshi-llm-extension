// The budgets of a workspace, as the studio admin api shows them (`budgetJson` in studio/api.scala) and the
// budget form writes them: limits, period and consumers at the top level, `scope` being how the consumers
// are picked (the whole workspace, one api key, or custom). The api keeps every budget scoped to its
// workspace whatever the form says.

import { platform } from './platform';

// the budgets a person sees: every budget when they read the configuration, the ones counting their usage otherwise
export const budgetsListOp = (workspace) => (platform.can('config:read', workspace) ? 'budgets.list' : 'mybudgets.list');

// an extension the person takes for themselves: who writes the configuration takes it like an administrator would,
// the others through the operation forcing the consumer on them
export const extendOwnOp = (workspace) => (platform.can('config:write', workspace) ? 'budgets.extend' : 'mybudgets.extend');

// a budget "by consumer": each person has the whole limit, their chats and the keys they own sharing it
export const isByConsumer = (budget) => !!(budget && budget.by_consumer);

// a budget counted apart by an expression set outside of the studio
export const isCustomPartition = (budget) => !!(budget && budget.partition === 'custom');

// the extensions a consumption can still take, none when the budget allows none
export const extensionsLeft = (consumption) =>
  consumption && consumption.extensions_max !== undefined ? Math.max(0, consumption.extensions_max - (consumption.extensions_taken || 0)) : 0;

// what one extension adds, as the budget says it (`+$10`, `+100,000 tokens`)
export function extensionLabel(extension, fmtCost, fmtNumber) {
  if (!extension) return '';
  return [extension.total_usd ? `+${fmtCost(extension.total_usd)}` : null, extension.total_tokens ? `+${fmtNumber(extension.total_tokens)} tokens` : null].filter(Boolean).join(' and ');
}

// whether a consumption used its limit up, extensions included
export function usedUp(consumption) {
  if (!consumption) return false;
  const over = (consumed, allowed) => allowed !== undefined && allowed !== null && Number(consumed) >= Number(allowed);
  return over(consumption.consumed_total_usd, consumption.allowed_total_usd) || over(consumption.consumed_total_tokens, consumption.allowed_total_tokens);
}

export const PERIODS = [
  { value: 'lifetime', label: 'Never (lifetime)' },
  { value: 'daily', label: 'Daily' },
  { value: 'weekly', label: 'Weekly (7 days)' },
  { value: 'monthly', label: 'Monthly (30 days)' },
  { value: 'yearly', label: 'Yearly' },
];

// one of `PERIODS`, or `custom` for a duration set outside of the studio
export const periodOf = (budget) => (budget && budget.period) || 'custom';

export function periodLabel(budget) {
  const p = PERIODS.find((x) => x.value === periodOf(budget));
  if (p) return p.value === 'lifetime' ? 'lifetime' : p.label.toLowerCase();
  const d = budget.duration || {};
  return `every ${d.value} ${d.unit}${d.value > 1 ? 's' : ''}`;
}

// the conditions a user added on top of the rule scoping the budget to its workspace
export const extraRulesOf = (budget) => (budget && budget.rules) || [];

// how the consumers of a budget are picked: `workspace`, `apikey` or `custom`
export const scopeModeOf = (budget) => (budget && budget.scope) || 'workspace';

// whether a budget names a user: its users are regexes matched against the whole email, as the gateway does
export function budgetNamesUser(budget, email) {
  return (
    !!email &&
    (budget.users || []).some((u) => {
      try {
        return new RegExp(`^(?:${u})$`).test(email);
      } catch (e) {
        return u === email;
      }
    })
  );
}

// no key and no user among the consumers of a budget
const namesNobody = (budget) => (budget.apikeys || []).length === 0 && (budget.users || []).length === 0;

// workspace wide budgets: no key and no user among their consumers, counted as a whole
export const isWorkspaceWide = (budget) => !isByConsumer(budget) && !isCustomPartition(budget) && namesNobody(budget);

// the budgets counting the calls of an api key: workspace wide ones, the ones naming the key and the ones naming
// its owner. A budget by consumer counts a key once it has an owner, the person it counts
export function budgetsOfKey(budgets, apikey, owner) {
  return budgets.filter((b) => {
    const names = namesNobody(b) || (b.apikeys || []).includes(apikey) || budgetNamesUser(b, owner);
    return b.enabled && names && (!isByConsumer(b) || !!owner);
  });
}

// the budgets counting the usage of a person: the ones naming them, the ones of the whole workspace, and the
// budgets by consumer, where they have their own limit
export const budgetsOfConsumer = (budgets, email) => budgets.filter((b) => b.enabled && (namesNobody(b) || budgetNamesUser(b, email)));

// the budget dedicated to a single key, the "credit limit" of the api keys page
export const keyBudgetOf = (budgets, clientId) => budgets.find((b) => b.key_limit === clientId);
