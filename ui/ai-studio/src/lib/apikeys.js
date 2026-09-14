import { bootstrap } from './bootstrap';
import { Resources, workspaceFilter } from './entities';
import { consumerTagOf, routeIdOf, workspaceLocation, workspaceMetadata } from './workspaces';

export function listApikeys(wsId) {
  return Resources.apikeys.list(workspaceFilter(wsId)).then((keys) => keys.sort((a, b) => (a.clientName || '').localeCompare(b.clientName || '')));
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
  const apikey = {
    ...base,
    _loc: (existing && existing._loc) || workspaceLocation(wsId),
    clientName: form.name,
    description: form.description || '',
    enabled: form.enabled !== false,
    // only this workspace route, never the defaults of the template
    authorizations: [{ kind: 'route', id: routeIdOf(wsId) }],
    authorizedEntities: [],
    authorizedGroup: null,
    tags: [...new Set([...((existing && existing.tags) || []), tag])],
    metadata: { ...((existing && existing.metadata) || {}), ...workspaceMetadata(wsId, 'apikey') },
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
