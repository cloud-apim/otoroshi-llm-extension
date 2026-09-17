import { api } from './api';
import { routeIdOf } from './workspaces';

// Usage data comes from the otoroshi user analytics (the LLM usage projection of the extension),
// always narrowed to the route of the workspace.

export const PERIODS = [
  { value: '1h', label: 'Past hour', from: 'now-1h' },
  { value: '24h', label: 'Past 24 hours', from: 'now-24h' },
  { value: '7d', label: 'Past 7 days', from: 'now-7d' },
  { value: '30d', label: 'Past month', from: 'now-30d' },
  { value: '90d', label: 'Past 3 months', from: 'now-90d' },
];

export class NoExporterError extends Error {}

// `apikey` is a platform filter, `user` (a studio user email) a param every llm query of the extension understands
// `from` (`now-365d`) overrides the period, for views longer than the periods offered in the pickers
export async function runQuery(wsId, query, { period = '7d', from, apikey, user, err, params = {}, compare = false, nocache = false, bucket } = {}) {
  const p = PERIODS.find((x) => x.value === period) || PERIODS[2];
  const filters = { from: from || p.from, to: 'now', route_id: routeIdOf(wsId) };
  if (apikey) filters.apikey_id = apikey;
  if (err !== undefined) filters.err = err;
  const allParams = user ? { ...params, user } : params;
  try {
    return await api.post('/bo/api/proxy/api/analytics/_query', { query, params: allParams, filters, compare, nocache, ...(bucket ? { bucket } : {}) });
  } catch (e) {
    if (e.status === 412) throw new NoExporterError('no active user analytics exporter');
    throw e;
  }
}

export function scalarOf(res) {
  return res && res.data ? Number(res.data.value) || 0 : 0;
}

export function compareOf(res) {
  return res && res.compare && res.compare.data ? Number(res.compare.data.value) || 0 : null;
}

// a timeseries result as a list of series, whatever its form (`points` or `series`)
export function seriesOf(res) {
  if (!res || !res.data) return [];
  if (res.data.series) return res.data.series.map((s) => ({ name: s.name.replace(/․/g, '.'), points: s.points }));
  if (res.data.points) return [{ name: 'value', points: res.data.points }];
  return [];
}

// sum every series of a timeseries, bucket by bucket
export function totalPoints(res) {
  const series = seriesOf(res);
  const acc = new Map();
  series.forEach((s) => s.points.forEach((p) => acc.set(p.ts, (acc.get(p.ts) || 0) + (Number(p.value) || 0))));
  return [...acc.entries()].sort((a, b) => a[0] - b[0]).map(([ts, value]) => ({ ts, value }));
}

// Every row of a log paged with `before` (the timestamp of the last row of a page), newest first, `max` at most.
// A page can end in the middle of rows sharing a timestamp: the next one starts again from that instant and
// skips the rows already fetched. `fetchPage(before)` gives `{ items, next }`, `next` null on the last page.
export async function fetchAllPages(fetchPage, { max = 10000, onProgress } = {}) {
  const seen = new Set();
  const rows = [];
  let before;
  for (;;) {
    const { items, next } = await fetchPage(before);
    let added = 0;
    items.forEach((row) => {
      if (!seen.has(row.id)) {
        seen.add(row.id);
        rows.push(row);
        added++;
      }
    });
    if (onProgress) onProgress(rows.length);
    if (next === null || next === undefined || rows.length >= max) break;
    // a page with nothing new, or ending on the instant it started from, moves strictly past that instant
    before = added > 0 && Number(next) + 1 !== before ? Number(next) + 1 : Number(next);
  }
  return { rows: rows.slice(0, max), truncated: rows.length >= max };
}

export function itemsOf(res) {
  return (res && res.data && res.data.items) || [];
}
