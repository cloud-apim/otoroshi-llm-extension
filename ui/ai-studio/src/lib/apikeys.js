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
  const { [META.owner]: _previousOwner, ...metadata } = (existing && existing.metadata) || {};
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
    metadata: { ...metadata, ...workspaceMetadata(wsId, 'apikey'), ...(form.owner ? { [META.owner]: form.owner } : {}) },
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
