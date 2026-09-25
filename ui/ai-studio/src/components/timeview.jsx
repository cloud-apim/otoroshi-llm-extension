import { useEffect, useMemo, useRef, useState } from 'react';
import { Icon } from './icons';
import { Select, Toggle } from './ui';
import { fmtDate } from '../lib/format';
import { customRange, isPeriod, periodLabelOf, periodSpan, PERIODS, rangePeriod } from '../lib/analytics';

// `<input type="datetime-local">` speaks local wall-clock time without a zone: `2026-09-25T14:30`
const pad = (n) => String(n).padStart(2, '0');
function toLocalInput(ms) {
  const d = new Date(ms);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
function fromLocalInput(v) {
  const t = v ? new Date(v).getTime() : NaN;
  return Number.isNaN(t) ? null : t;
}

const CUSTOM = '__custom';

// The presets, relative to now, and a fixed range from one date and time to another. The range is edited in a
// popover and only applied on "Apply", so a half typed date never fires a query. It opens on the range in place, or
// on the preset in place turned into dates: the usual way to a range is to start from "the past 24 hours" and narrow
// it down. `periods` narrows the presets offered, `align="left"` opens the popover to the right of the picker.
export function PeriodPicker({ value, onChange, periods = PERIODS, align = 'right', className = '' }) {
  const range = customRange(value);
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState({ from: '', to: '' });
  const ref = useRef(null);

  useEffect(() => {
    if (!open) return undefined;
    const onClick = (e) => ref.current && !ref.current.contains(e.target) && setOpen(false);
    const onKey = (e) => e.key === 'Escape' && setOpen(false);
    document.addEventListener('mousedown', onClick);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onClick);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const openRange = () => {
    const now = Date.now();
    const r = range || { from: now - periodSpan(value), to: now };
    setDraft({ from: toLocalInput(r.from), to: toLocalInput(r.to) });
    setOpen(true);
  };

  const from = fromLocalInput(draft.from);
  const to = fromLocalInput(draft.to);
  const error = from === null || to === null ? 'Both dates are needed' : from >= to ? 'The end must come after the start' : null;
  const apply = () => {
    if (error) return;
    onChange(rangePeriod(from, to));
    setOpen(false);
  };

  return (
    <div className={`period-picker menu ${className}`} ref={ref}>
      <Select
        className="sm"
        value={range ? CUSTOM : value}
        onChange={(v) => (v === CUSTOM ? openRange() : onChange(v))}
        options={[...periods.map((p) => ({ value: p.value, label: p.label })), { value: CUSTOM, label: range ? periodLabelOf(value) : 'Custom range…' }]}
        aria-label="Period"
      />
      <button className={`btn sm icon ${range ? 'active' : ''}`} onClick={() => (open ? setOpen(false) : openRange())} title="From a date and time to another">
        <Icon name="calendar" />
      </button>
      {open && (
        <div className={`period-range ${align === 'left' ? 'left' : ''}`}>
          <label>
            <span className="small muted">From</span>
            <input className="input" type="datetime-local" value={draft.from} max={draft.to || undefined} onChange={(e) => setDraft({ ...draft, from: e.target.value })} />
          </label>
          <label>
            <span className="small muted">To</span>
            <input className="input" type="datetime-local" value={draft.to} min={draft.from || undefined} onChange={(e) => setDraft({ ...draft, to: e.target.value })} />
          </label>
          {error && <div className="small negative-text">{error}</div>}
          <div className="period-range-actions">
            <button className="btn sm ghost" onClick={() => setOpen(false)}>
              Cancel
            </button>
            <button className="btn sm primary" disabled={!!error} onClick={apply}>
              Apply
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

const REFRESH_INTERVALS = [
  { value: '10', label: 'every 10s' },
  { value: '30', label: 'every 30s' },
  { value: '60', label: 'every minute' },
  { value: '300', label: 'every 5 min' },
];

const viewKey = (view) => `ai-studio.view.${view}`;

function readView(view) {
  try {
    return JSON.parse(window.localStorage.getItem(viewKey(view))) || {};
  } catch {
    return {};
  }
}

function writeView(view, patch) {
  try {
    window.localStorage.setItem(viewKey(view), JSON.stringify({ ...readView(view), ...patch }));
  } catch {
    // private window, blocked storage: the query string still carries it
  }
}

// The period and the auto reload of a view (`?period=7d&auto=true&every=30`), a fixed range being a period too
// (`?period=1758700000000_1758790000000`, see `customRange`). The query string wins, so a link shows what it was
// copied with. Without one, the view comes back as it was last left in this browser (remembered per view) and the
// query string is filled in from it, so a reload of the page keeps it too. `auto` is always written, `false`
// included: an absent key would fall back to the remembered value. `periods` narrows the presets the view accepts.
export function useTimeView(view, query, setQuery, defaultPeriod, periods = PERIODS) {
  const stored = useMemo(() => readView(view), [view]);
  const pick = (key) => (query[key] !== undefined ? query[key] : stored[key]);
  const valid = (v) => isPeriod(v) && (customRange(v) !== null || periods.some((p) => p.value === v));
  const period = valid(pick('period')) ? pick('period') : defaultPeriod;
  const refresh = {
    auto: pick('auto') === 'true',
    every: REFRESH_INTERVALS.some((i) => i.value === pick('every')) ? pick('every') : '30',
  };

  useEffect(() => {
    const missing = Object.fromEntries(['period', 'auto', 'every'].filter((k) => query[k] === undefined && stored[k] !== undefined).map((k) => [k, stored[k]]));
    if (Object.keys(missing).length > 0) setQuery(missing);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [view]);

  const update = (patch) => {
    writeView(view, patch);
    setQuery(patch);
  };
  return {
    period,
    refresh,
    setPeriod: (v) => update({ period: v }),
    setRefresh: (r) => update({ auto: r.auto ? 'true' : 'false', every: r.every }),
  };
}

// Reload now, or every so often. Sits next to a `PeriodPicker`: a period that ends now moves, and a console left open
// should follow it. Controlled, so the page keeps it in its query string (see `useTimeView`). A tick is skipped while
// the tab is hidden or the previous reload is still running, so a slow query never piles up behind itself.
// `onAutoRefresh`, when given, is what the ticks call instead of `onRefresh`: a lighter reload than the button's.
export function RefreshControl({ onRefresh, onAutoRefresh, busy = false, auto, every, onChange, loadedAt }) {
  const latest = useRef({ onRefresh, busy });
  useEffect(() => {
    latest.current = { onRefresh: onAutoRefresh || onRefresh, busy };
  });
  useEffect(() => {
    if (!auto) return undefined;
    const id = setInterval(() => {
      if (document.hidden || latest.current.busy) return;
      latest.current.onRefresh();
    }, Number(every) * 1000);
    return () => clearInterval(id);
  }, [auto, every]);
  return (
    <div className="refresh-control">
      {loadedAt && (
        <span className="refresh-at" title={`Data loaded ${fmtDate(loadedAt)}`}>
          {new Date(loadedAt).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', second: '2-digit' })}
        </span>
      )}
      <button className="btn sm icon" title="Reload now" disabled={busy} onClick={() => onRefresh()}>
        <Icon name="refresh" className={busy ? 'spinning' : ''} />
      </button>
      <span className="refresh-auto">
        <Toggle value={auto} onChange={(v) => onChange({ auto: v, every })} title="Reload automatically" />
        <span className="small muted">Auto</span>
      </span>
      <Select className="sm" value={every} onChange={(v) => onChange({ auto, every: v })} options={REFRESH_INTERVALS} disabled={!auto} title="Reload interval" />
    </div>
  );
}
