import { bootstrap } from './bootstrap';
import { META, Resources, workspaceFilter } from './entities';
import { consumerTagOf, routeIdOf, workspaceLocation, workspaceMetadata } from './workspaces';

export function listApikeys(wsId) {
  return Resources.apikeys.list(workspaceFilter(wsId)).then((keys) => keys.sort((a, b) => (a.clientName || '').localeCompare(b.clientName || '')));
}

// same pattern as the studio api: an owner is always an email
export const OWNER_PATTERN = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+$/;

// the email the usage of the key counts for, null for a workspace key
export function ownerOf(apikey) {
  return (apikey && apikey.metadata && apikey.metadata[META.owner]) || null;
}

// the models a key may use: regular expressions matched against `model`, `provider/model` and
// `provider###model`, stored comma separated in the metadata the gateway reads
export const MODELS_INCLUDE = 'ai_models_include';
export const MODELS_EXCLUDE = 'ai_models_exclude';

const patternsOf = (value) =>
  (value || '')
    .split(',')
    .map((p) => p.trim())
    .filter(Boolean);

export function modelRulesOf(apikey) {
  const metadata = (apikey && apikey.metadata) || {};
  return { include: patternsOf(metadata[MODELS_INCLUDE]), exclude: patternsOf(metadata[MODELS_EXCLUDE]) };
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
  const v = apikey && apikey.validUntil;
  return v === null || v === undefined || v === '' ? null : Number(v);
}

// otoroshi refuses an expired key, and reads it as disabled
export function isExpired(apikey, now = Date.now()) {
  const v = validUntilOf(apikey);
  return v !== null && v <= now;
}

const SECRET_ALPHABET = 'abcdefghijklmnopqrstuvwxyz0123456789';

// drawn like the secrets of the api key template, from the browser CSPRNG (bytes over 251 are dropped
// so every character is equally likely)
export function randomSecret(size = 64) {
  let secret = '';
  while (secret.length < size) {
    for (const b of crypto.getRandomValues(new Uint8Array(size))) {
      if (b < 252 && secret.length < size) secret += SECRET_ALPHABET[b % SECRET_ALPHABET.length];
    }
  }
  return secret;
}

// a new secret and no pending rotation secret: the previous bearer and basic credentials stop working
export async function resetApikeySecret(clientId) {
  const { bearer: _bearer, ...current } = await Resources.apikeys.get(clientId);
  const { nextSecret: _next, bearer: _nextBearer, ...rotation } = current.rotation || {};
  await Resources.apikeys.update({ ...current, clientSecret: randomSecret(), rotation });
  return Resources.apikeys.get(clientId);
}

export function usesWorkspaceQuotas(apikey) {
  const c = bootstrap.config;
  return (
    apikey.throttlingQuota === c.default_throttling_quota && apikey.dailyQuota === c.default_daily_quota && apikey.monthlyQuota === c.default_monthly_quota
  );
}

export async function saveApikey(wsId, form, existing) {
  const c = bootstrap.config;
  const base = existing || (await Resources.apikeys.template());
  const tag = consumerTagOf(wsId);
  const { [META.owner]: _previousOwner, [MODELS_INCLUDE]: _include, [MODELS_EXCLUDE]: _exclude, ...metadata } = (existing && existing.metadata) || {};
  const rules = form.models || modelRulesOf(existing);
  const modelsMetadata = {
    ...(rules.include.length ? { [MODELS_INCLUDE]: rules.include.join(',') } : {}),
    ...(rules.exclude.length ? { [MODELS_EXCLUDE]: rules.exclude.join(',') } : {}),
  };
  const apikey = {
    ...base,
    _loc: (existing && existing._loc) || workspaceLocation(wsId),
    clientName: form.name,
    description: form.description || '',
    enabled: form.enabled !== false,
    validUntil: form.validUntil ?? null,
    // only this workspace route, never the defaults of the template
    authorizations: [{ kind: 'route', id: routeIdOf(wsId) }],
    authorizedEntities: [],
    authorizedGroup: null,
    tags: [...new Set([...((existing && existing.tags) || []), tag])],
    metadata: { ...metadata, ...workspaceMetadata(wsId, 'apikey'), ...(form.owner ? { [META.owner]: form.owner } : {}), ...modelsMetadata },
    throttlingQuota: form.override ? Number(form.throttlingQuota) : c.default_throttling_quota,
    dailyQuota: form.override ? Number(form.dailyQuota) : c.default_daily_quota,
    monthlyQuota: form.override ? Number(form.monthlyQuota) : c.default_monthly_quota,
    readOnly: false,
    allowClientIdOnly: false,
  };
  delete apikey.bearer;
  if (existing) await Resources.apikeys.update(apikey);
  else await Resources.apikeys.create(apikey);
  return apikey;
}
