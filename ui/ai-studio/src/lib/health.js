import { itemsOf, NoExporterError, runQuery } from './analytics';

// How the providers and the models of a workspace behave, from the calls of a period: failures of the providers
// (the refusals of the gateway aside), latency and generation speed (cache hits aside).

// failure rates from which a provider or a model is degraded, then failing
const DEGRADED = 0.05;
const FAILING = 0.2;

export const HEALTH = [
  { value: 'healthy', label: 'Healthy', title: `Less than ${DEGRADED * 100}% of failed calls` },
  { value: 'degraded', label: 'Degraded', title: `${DEGRADED * 100}% to ${FAILING * 100}% of failed calls` },
  { value: 'failing', label: 'Failing', title: `${FAILING * 100}% of failed calls or more` },
  { value: 'idle', label: 'No calls', title: 'No call served in the period' },
];

// the rows of the period, per provider entity or per model of each provider entity; null without analytics
export async function loadHealth(wsId, period, groupBy) {
  try {
    const res = await runQuery(wsId, 'cloudapim_llm_health_table', { period, params: { group_by: groupBy, top_n: 1000 } });
    return itemsOf(res);
  } catch (e) {
    if (e instanceof NoExporterError) return null;
    throw e;
  }
}

const modelKey = (providerId, model) => JSON.stringify([providerId, model || '']);

// the rows by provider entity id, or by provider entity id and model as the models of the workspace name them
export function healthIndex(rows, byModel = true) {
  return new Map((rows || []).map((r) => [byModel ? modelKey(r.provider_id, r.model) : r.provider_id, r]));
}

export const healthOfModel = (index, model) => index.get(modelKey(model.provider_id, model.model)) || null;

export const healthNumber = (v) => (v === null || v === undefined || v === '' ? null : Number(v));

// the calls a provider was asked to serve: neither refused by the gateway nor answered from its cache
export const attempted = (h) => (h ? (healthNumber(h.calls) || 0) - (healthNumber(h.refusals) || 0) - (healthNumber(h.cached) || 0) : 0);

export function statusOf(h) {
  if (!h || attempted(h) <= 0) return 'idle';
  const rate = healthNumber(h.failure_rate) || 0;
  return rate >= FAILING ? 'failing' : rate >= DEGRADED ? 'degraded' : 'healthy';
}

// the entities of a connection as one: counts add up, times are the ones of its busiest entity
export function combine(rows) {
  const list = rows.filter(Boolean);
  if (list.length === 0) return null;
  const sum = (field) => list.reduce((acc, r) => acc + (healthNumber(r[field]) || 0), 0);
  const busiest = [...list].sort((a, b) => (healthNumber(b.calls) || 0) - (healthNumber(a.calls) || 0))[0];
  const combined = { ...busiest, calls: sum('calls'), failures: sum('failures'), refusals: sum('refusals'), cached: sum('cached') };
  const tried = attempted(combined);
  const lastFailure = list.filter((r) => r.last_failure).sort((a, b) => healthNumber(b.last_failure) - healthNumber(a.last_failure))[0];
  return {
    ...combined,
    failure_rate: tried > 0 ? combined.failures / tried : 0,
    last_call: Math.max(...list.map((r) => healthNumber(r.last_call) || 0)) || null,
    last_failure: lastFailure ? lastFailure.last_failure : null,
    last_failure_message: lastFailure ? lastFailure.last_failure_message : null,
  };
}

// 99.9% rather than a rounded 100% when some calls failed
export function fmtSuccess(h) {
  const ok = 100 * (1 - (healthNumber(h.failure_rate) || 0));
  if (ok >= 100) return '100%';
  return `${Math.floor(ok * 10) / 10}%`;
}

export function fmtSpeed(value) {
  const n = healthNumber(value);
  return n === null ? '—' : `${n >= 100 ? Math.round(n) : n.toFixed(1)} tok/s`;
}
