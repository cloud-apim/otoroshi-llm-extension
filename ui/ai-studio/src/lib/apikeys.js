// The api keys of a workspace, as the studio admin api shows them (`apikeyJson` in studio/api.scala) and the
// key form writes them: owner, model rules, expiration, quotas and credit limit at the top level.

// same pattern as the studio api: an owner is always an email
export const OWNER_PATTERN = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+$/;

// the email the usage of the key counts for, null for a workspace key
export function ownerOf(apikey) {
  return (apikey && apikey.owner) || null;
}

// the models a key may use: regular expressions matched against `model`, `provider/model` and
// `provider###model`
export function modelRulesOf(apikey) {
  const models = (apikey && apikey.models) || {};
  return { include: models.include || [], exclude: models.exclude || [] };
}

const REGEX_SPECIALS = /[.*+?^${}()|[\]\\]/g;

// the expression matching exactly one model id
export const exactModel = (id) => id.replace(REGEX_SPECIALS, '\\$&');

// the model id an expression stands for, null for a real expression
export function modelOfPattern(pattern) {
  if (!/^(?:[^.*+?^${}()|[\]\\]|\\[.*+?^${}()|[\]\\])*$/.test(pattern)) return null;
  return pattern.replace(/\\(.)/g, '$1');
}

// `all` when nothing is restricted, `selected` for a list of model ids, `custom` for expressions
export function modelModeOf(rules) {
  if (rules.include.length === 0 && rules.exclude.length === 0) return 'all';
  if (rules.exclude.length === 0 && rules.include.every((p) => modelOfPattern(p) !== null)) return 'selected';
  return 'custom';
}

// the metadata is comma separated, and the gateway compiles the expressions
export function patternError(pattern) {
  if (pattern.includes(',')) return `commas are not supported: ${pattern}`;
  try {
    new RegExp(pattern);
    return null;
  } catch (e) {
    return `invalid regular expression: ${pattern}`;
  }
}

// the date the key stops working (epoch millis), null for a key that never expires
export function validUntilOf(apikey) {
  const v = apikey && apikey.valid_until;
  return v ? Date.parse(v) : null;
}

// otoroshi refuses an expired key, and reads it as disabled
export function isExpired(apikey, now = Date.now()) {
  const v = validUntilOf(apikey);
  return v !== null && v <= now;
}

// whether the key has the default quotas of the studio, the ones of the danger zone
export const usesWorkspaceQuotas = (apikey) => !!(apikey && apikey.uses_workspace_quotas);
