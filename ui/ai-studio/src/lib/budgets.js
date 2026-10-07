// The budgets of a workspace, as the studio admin api shows them (`budgetJson` in studio/api.scala) and the
// budget form writes them: limits, period and consumers at the top level, `scope` being how the consumers
// are picked (the whole workspace, one api key, or custom). The api keeps every budget scoped to its
// workspace whatever the form says.

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

// workspace wide budgets: no key and no user among their consumers
export const isWorkspaceWide = (budget) => (budget.apikeys || []).length === 0 && (budget.users || []).length === 0;

// the budgets counting the calls of an api key: workspace wide ones, the ones naming the key and the
// ones naming its owner
export function budgetsOfKey(budgets, apikey, owner) {
  return budgets.filter((b) => b.enabled && (isWorkspaceWide(b) || (b.apikeys || []).includes(apikey) || budgetNamesUser(b, owner)));
}

// the budget dedicated to a single key, the "credit limit" of the api keys page
export const keyBudgetOf = (budgets, clientId) => budgets.find((b) => b.key_limit === clientId);
