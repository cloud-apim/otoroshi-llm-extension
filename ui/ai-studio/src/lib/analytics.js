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

// A period is either one of `PERIODS`, relative to now, or a fixed range written `<from>_<to>` in epoch
// milliseconds: a single token, so it travels through the query string and every `runQuery` call like a preset
export function customRange(period) {
  const m = /^(\d+)_(\d+)$/.exec(period || '');
  if (!m) return null;
  const from = Number(m[1]);
  const to = Number(m[2]);
  return from < to ? { from, to } : null;
}

export const rangePeriod = (from, to) => `${from}_${to}`;

export const isPeriod = (value) => PERIODS.some((p) => p.value === value) || customRange(value) !== null;

const UNITS = { h: 3600000, d: 86400000 };

// the length of a period in milliseconds
export function periodSpan(period) {
  const range = customRange(period);
  if (range) return range.to - range.from;
  const p = PERIODS.find((x) => x.value === period) || PERIODS[2];
  return Number(p.from.slice(4, -1)) * UNITS[p.from.slice(-1)];
}

// a day or less: hourly buckets rather than daily ones
export const isShortPeriod = (period) => periodSpan(period) <= UNITS.d;

const fmtRangeEnd = (ms) => new Date(ms).toLocaleString(undefined, { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });

export function periodLabelOf(period) {
  const range = customRange(period);
  if (range) return `${fmtRangeEnd(range.from)} → ${fmtRangeEnd(range.to)}`;
  return (PERIODS.find((p) => p.value === period) || PERIODS[2]).label;
}

// a period as it reads in a file name: `24h`, or `2026-09-01t10-00-2026-09-02t10-00` for a range
export function periodSlug(period) {
  const range = customRange(period);
  if (!range) return period;
  const iso = (ms) => new Date(ms).toISOString().substring(0, 16);
  return `${iso(range.from)}-${iso(range.to)}`;
}

export class NoExporterError extends Error {}

// `apikey` is a platform filter, `user` (a studio user email) a param every llm query of the extension understands
// `from` (`now-365d`) overrides the period, for views longer than the periods offered in the pickers
export async function runQuery(wsId, query, { period = '7d', from, apikey, user, err, params = {}, compare = false, nocache = false, bucket } = {}) {
  const range = customRange(period);
  const p = PERIODS.find((x) => x.value === period) || PERIODS[2];
  const filters = range
    ? { from: from || new Date(range.from).toISOString(), to: new Date(range.to).toISOString(), route_id: routeIdOf(wsId) }
    : { from: from || p.from, to: 'now', route_id: routeIdOf(wsId) };
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
