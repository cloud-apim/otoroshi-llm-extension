const compact = new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 });
const integer = new Intl.NumberFormat('en-US');

export function fmtNumber(v) {
  if (v === null || v === undefined || Number.isNaN(Number(v))) return '—';
  const n = Number(v);
  return Math.abs(n) >= 10000 ? compact.format(n) : integer.format(Math.round(n * 100) / 100);
}

export function fmtInt(v) {
  if (v === null || v === undefined || Number.isNaN(Number(v))) return '—';
  return integer.format(Math.round(Number(v)));
}

export function fmtCost(v) {
  if (v === null || v === undefined || Number.isNaN(Number(v))) return '—';
  const n = Number(v);
  if (n === 0) return '$0.00';
  if (Math.abs(n) < 0.01) return '$' + n.toPrecision(2);
  if (Math.abs(n) >= 10000) return '$' + compact.format(n);
  return '$' + n.toFixed(2);
}

export function fmtMs(v) {
  if (v === null || v === undefined || Number.isNaN(Number(v))) return '—';
  const n = Number(v);
  if (n >= 10000) return (n / 1000).toFixed(1) + ' s';
  return Math.round(n) + ' ms';
}

export function fmtPercent(v, digits = 1) {
  if (v === null || v === undefined || Number.isNaN(Number(v))) return '—';
  return (Number(v) * 100).toFixed(digits) + '%';
}

export function fmtDate(v) {
  if (!v) return '—';
  const d = new Date(v);
  if (Number.isNaN(d.getTime())) return String(v);
  return d.toLocaleString(undefined, { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

export function fmtShortDate(v, bucket) {
  const d = new Date(v);
  if (Number.isNaN(d.getTime())) return String(v);
  if (bucket && (bucket.endsWith('m') || bucket.endsWith('h')) && !bucket.endsWith('month')) {
    return d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
  }
  return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

export function initials(value) {
  const parts = (value || '?').replace(/\(.*\)/g, '').replace(/[^\p{L}\p{N}]+/gu, ' ').split(' ').filter(Boolean);
  if (parts.length === 0) return '?';
  if (parts.length === 1) return parts[0].substring(0, 2).toUpperCase();
  return (parts[0][0] + parts[1][0]).toUpperCase();
}
