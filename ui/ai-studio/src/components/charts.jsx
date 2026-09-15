import { useEffect, useMemo, useRef, useState } from 'react';
import { fmtShortDate } from '../lib/format';

// Hand drawn svg charts: stacked bars, areas and sparklines. Series colors follow the entity name
// through a fixed categorical order, a series beyond the palette folds into "Other".

const SLOTS = 8;

export function seriesColor(index, name) {
  if (name === 'Other') return 'var(--chart-other)';
  return `var(--chart-${(index % SLOTS) + 1})`;
}

function useWidth() {
  const ref = useRef(null);
  const [width, setWidth] = useState(600);
  useEffect(() => {
    if (!ref.current) return;
    const obs = new ResizeObserver((entries) => {
      const w = Math.floor(entries[0].contentRect.width);
      if (w > 0) setWidth(w);
    });
    obs.observe(ref.current);
    return () => obs.disconnect();
  }, []);
  return [ref, width];
}

function niceMax(max) {
  if (max <= 0) return 1;
  const exp = Math.pow(10, Math.floor(Math.log10(max)));
  const f = max / exp;
  const nice = f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10;
  return nice * exp;
}

// merge `series` ([{name, points: [{ts, value}]}]) into buckets [{ts, values: {name: value}}]
function toBuckets(series) {
  const map = new Map();
  series.forEach((s) =>
    (s.points || []).forEach((p) => {
      if (!map.has(p.ts)) map.set(p.ts, { ts: p.ts, values: {} });
      map.get(p.ts).values[s.name] = Number(p.value) || 0;
    })
  );
  return [...map.values()].sort((a, b) => a.ts - b.ts);
}

function foldSeries(series, max = SLOTS - 1) {
  if (series.length <= max + 1) return series;
  const kept = series.slice(0, max);
  const rest = series.slice(max);
  const other = { name: 'Other', points: [] };
  const acc = new Map();
  rest.forEach((s) => s.points.forEach((p) => acc.set(p.ts, (acc.get(p.ts) || 0) + (Number(p.value) || 0))));
  other.points = [...acc.entries()].map(([ts, value]) => ({ ts, value }));
  return [...kept, other];
}

export function Legend({ series }) {
  if (!series || series.length < 2) return null;
  return (
    <div className="chart-legend">
      {series.map((s, idx) => (
        <span key={s.name}>
          <i style={{ background: seriesColor(idx, s.name) }} />
          {s.name}
        </span>
      ))}
    </div>
  );
}

function Tooltip({ x, y, width, title, rows, format }) {
  const left = Math.min(Math.max(x + 12, 0), width - 170);
  return (
    <div className="chart-tooltip" style={{ left, top: Math.max(0, y) }}>
      <div className="t">{title}</div>
      {rows.map((r) => (
        <div key={r.name} className="l">
          <span className="row" style={{ gap: 6 }}>
            <i style={{ background: r.color }} />
            {r.name}
          </span>
          <b>{format(r.value)}</b>
        </div>
      ))}
    </div>
  );
}

function Axes({ width, height, pad, max, format, buckets, bucket }) {
  const ticks = [0, 0.25, 0.5, 0.75, 1];
  const labelEvery = Math.max(1, Math.ceil(buckets.length / Math.max(2, Math.floor((width - pad.l) / 70))));
  const step = (width - pad.l - pad.r) / Math.max(1, buckets.length);
  const span = buckets.length > 1 ? buckets[buckets.length - 1].ts - buckets[0].ts : 0;
  const axisBucket = span > 2 * 24 * 3600 * 1000 ? '1d' : bucket;
  return (
    <g>
      {ticks.map((t) => {
        const y = pad.t + (height - pad.t - pad.b) * (1 - t);
        return (
          <g key={t}>
            <line className="grid-line" x1={pad.l} x2={width - pad.r} y1={y} y2={y} />
            <text className="axis-label" x={pad.l - 8} y={y + 4} textAnchor="end">
              {format(max * t)}
            </text>
          </g>
        );
      })}
      {buckets.map((b, i) =>
        i % labelEvery === 0 ? (
          <text key={b.ts} className="axis-label" x={pad.l + step * i + step / 2} y={height - 6} textAnchor="middle">
            {fmtShortDate(b.ts, axisBucket)}
          </text>
        ) : null
      )}
    </g>
  );
}

export function StackedBars({ series: rawSeries, bucket, format = (v) => v, height = 220, empty = 'No data for this period' }) {
  const [ref, width] = useWidth();
  const [hover, setHover] = useState(null);
  const series = useMemo(() => foldSeries(rawSeries || []), [rawSeries]);
  const buckets = useMemo(() => toBuckets(rawSeries && rawSeries.length ? rawSeries : []), [rawSeries]);
  const pad = { l: 56, r: 8, t: 10, b: 24 };
  const totals = buckets.map((b) => series.reduce((acc, s) => acc + (b.values[s.name] || 0), 0));
  const max = niceMax(Math.max(0, ...totals));
  const plotH = height - pad.t - pad.b;
  const step = (width - pad.l - pad.r) / Math.max(1, buckets.length);
  const barW = Math.max(2, Math.min(28, step * 0.62));
  const hasData = totals.some((t) => t > 0);

  return (
    <div className="chart" ref={ref}>
      {!hasData ? (
        <div className="chart-empty" style={{ height }}>
          {empty}
        </div>
      ) : (
        <svg height={height} viewBox={`0 0 ${width} ${height}`} onMouseLeave={() => setHover(null)}>
          <Axes width={width} height={height} pad={pad} max={max} format={format} buckets={buckets} bucket={bucket} />
          {buckets.map((b, i) => {
            const x = pad.l + step * i + (step - barW) / 2;
            let acc = 0;
            const segments = series
              .map((s, idx) => ({ s, idx, v: b.values[s.name] || 0 }))
              .filter((seg) => seg.v > 0);
            return (
              <g key={b.ts}>
                {segments.map((seg, j) => {
                  const h = (seg.v / max) * plotH;
                  const y = pad.t + plotH - ((acc + seg.v) / max) * plotH;
                  acc += seg.v;
                  const top = j === segments.length - 1;
                  // 2px surface gap between stacked segments, rounded data end on the top segment
                  const gap = j > 0 ? 2 : 0;
                  const hh = Math.max(0.5, h - gap);
                  const r = top ? Math.min(4, barW / 2, hh) : 0;
                  const d = `M${x},${y + hh} V${y + r} Q${x},${y} ${x + r},${y} H${x + barW - r} Q${x + barW},${y} ${x + barW},${y + r} V${y + hh} Z`;
                  return <path key={seg.s.name} d={d} fill={seriesColor(seg.idx, seg.s.name)} opacity={hover && hover.i !== i ? 0.55 : 1} />;
                })}
                <rect
                  x={pad.l + step * i}
                  y={pad.t}
                  width={step}
                  height={plotH}
                  fill="transparent"
                  onMouseEnter={() => setHover({ i, x: pad.l + step * i + step / 2, b })}
                />
              </g>
            );
          })}
        </svg>
      )}
      {hover && (
        <Tooltip
          x={hover.x}
          y={10}
          width={width}
          title={new Date(hover.b.ts).toLocaleString()}
          format={format}
          rows={series
            .map((s, idx) => ({ name: s.name, value: hover.b.values[s.name] || 0, color: seriesColor(idx, s.name) }))
            .filter((r) => r.value > 0)
            .reverse()}
        />
      )}
      <Legend series={series} />
    </div>
  );
}

export function AreaChart({ series: rawSeries, bucket, format = (v) => v, height = 220, stacked = false, empty = 'No data for this period' }) {
  const [ref, width] = useWidth();
  const [hover, setHover] = useState(null);
  const series = useMemo(() => foldSeries(rawSeries || []), [rawSeries]);
  const buckets = useMemo(() => toBuckets(series), [series]);
  const pad = { l: 56, r: 8, t: 10, b: 24 };
  const plotH = height - pad.t - pad.b;
  const step = (width - pad.l - pad.r) / Math.max(1, buckets.length);
  const values = buckets.map((b) => (stacked ? series.reduce((a, s) => a + (b.values[s.name] || 0), 0) : Math.max(0, ...series.map((s) => b.values[s.name] || 0))));
  const max = niceMax(Math.max(0, ...values));
  const hasData = values.some((v) => v > 0);
  const xOf = (i) => pad.l + step * i + step / 2;
  const yOf = (v) => pad.t + plotH - (v / max) * plotH;

  const paths = useMemo(() => {
    const base = buckets.map(() => 0);
    return series.map((s, idx) => {
      const tops = buckets.map((b, i) => (stacked ? base[i] : 0) + (b.values[s.name] || 0));
      const bottoms = buckets.map((b, i) => (stacked ? base[i] : 0));
      if (stacked) tops.forEach((t, i) => (base[i] = t));
      const line = tops.map((v, i) => `${i === 0 ? 'M' : 'L'}${xOf(i)},${yOf(v)}`).join(' ');
      const area = `${line} ${bottoms.map((v, i) => `L${xOf(buckets.length - 1 - i)},${yOf(bottoms[buckets.length - 1 - i])}`).join(' ')} Z`;
      return { name: s.name, idx, line, area };
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [series, buckets, width, max, stacked]);

  return (
    <div className="chart" ref={ref}>
      {!hasData ? (
        <div className="chart-empty" style={{ height }}>
          {empty}
        </div>
      ) : (
        <svg
          height={height}
          viewBox={`0 0 ${width} ${height}`}
          onMouseLeave={() => setHover(null)}
          onMouseMove={(e) => {
            const rect = e.currentTarget.getBoundingClientRect();
            const px = ((e.clientX - rect.left) / rect.width) * width;
            const i = Math.min(buckets.length - 1, Math.max(0, Math.floor((px - pad.l) / step)));
            if (buckets[i]) setHover({ i, x: xOf(i), b: buckets[i] });
          }}
        >
          <Axes width={width} height={height} pad={pad} max={max} format={format} buckets={buckets} bucket={bucket} />
          {paths.map((p) => (
            <g key={p.name}>
              <path d={p.area} fill={seriesColor(p.idx, p.name)} opacity={0.14} />
              <path d={p.line} fill="none" stroke={seriesColor(p.idx, p.name)} strokeWidth={2} strokeLinejoin="round" />
            </g>
          ))}
          {hover && <line x1={hover.x} x2={hover.x} y1={pad.t} y2={pad.t + plotH} stroke="var(--border-strong)" />}
          {hover &&
            series.map((s, idx) => {
              const v = stacked ? series.slice(0, idx + 1).reduce((a, x) => a + (hover.b.values[x.name] || 0), 0) : hover.b.values[s.name] || 0;
              return <circle key={s.name} cx={hover.x} cy={yOf(v)} r={4} fill={seriesColor(idx, s.name)} stroke="var(--surface)" strokeWidth={2} />;
            })}
        </svg>
      )}
      {hover && (
        <Tooltip
          x={hover.x}
          y={10}
          width={width}
          title={new Date(hover.b.ts).toLocaleString()}
          format={format}
          rows={series.map((s, idx) => ({ name: s.name, value: hover.b.values[s.name] || 0, color: seriesColor(idx, s.name) }))}
        />
      )}
      <Legend series={series} />
    </div>
  );
}

export function Sparkline({ points, width = 90, height = 30, color = 'var(--chart-1)' }) {
  const values = (points || []).map((p) => Number(p.value) || 0);
  if (values.length < 2) return <svg width={width} height={height} />;
  const max = Math.max(...values);
  const min = Math.min(...values);
  const range = max - min || 1;
  const d = values.map((v, i) => `${i === 0 ? 'M' : 'L'}${(i / (values.length - 1)) * width},${height - 2 - ((v - min) / range) * (height - 4)}`).join(' ');
  return (
    <svg className="sparkline" width={width} height={height} viewBox={`0 0 ${width} ${height}`} aria-hidden="true">
      <path className="line" d={d} stroke={color} />
    </svg>
  );
}

// A year of daily values as a week by weekday grid (github style). `points` are daily UTC buckets.
const DAY = 24 * 3600 * 1000;
const WEEKDAYS = ['Mon', '', 'Wed', '', 'Fri', '', ''];

function dayKey(ms) {
  return new Date(ms).toISOString().substring(0, 10);
}

export function CalendarHeatmap({ points, format = (v) => v, days = 365 }) {
  const [hover, setHover] = useState(null);
  const { cells, weeks, months, thresholds } = useMemo(() => {
    const values = new Map();
    (points || []).forEach((p) => values.set(dayKey(Number(p.ts)), (values.get(dayKey(Number(p.ts))) || 0) + (Number(p.value) || 0)));
    const today = new Date();
    const end = Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), today.getUTCDate());
    const first = end - (days - 1) * DAY;
    // weeks start on monday
    const start = first - ((new Date(first).getUTCDay() + 6) % 7) * DAY;
    const cells = [];
    const months = [];
    for (let t = start, i = 0; t <= end; t += DAY, i++) {
      const col = Math.floor(i / 7);
      const date = new Date(t);
      if (date.getUTCDate() === 1 && t >= first) months.push({ col, label: date.toLocaleDateString(undefined, { month: 'short', timeZone: 'UTC' }) });
      cells.push({ t, col, row: i % 7, value: t < first ? null : values.get(dayKey(t)) || 0 });
    }
    // four levels, from the quartiles of the active days
    const active = cells.map((c) => c.value).filter((v) => v > 0).sort((a, b) => a - b);
    const q = (f) => active[Math.min(active.length - 1, Math.floor(active.length * f))];
    return { cells, weeks: Math.ceil(cells.length / 7), months, thresholds: active.length ? [q(0.25), q(0.5), q(0.75)] : [] };
  }, [points, days]);
  const level = (v) => (!v ? 0 : 1 + thresholds.filter((th) => v > th).length);
  const pitch = 14;
  const size = 11;
  const left = 30;
  const top = 16;
  const width = left + weeks * pitch;
  const height = top + 7 * pitch;
  return (
    <div className="calendar">
      <div className="table-wrap">
        <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`} style={{ width: '100%', height: 'auto', minWidth: width }} onMouseLeave={() => setHover(null)}>
          {months.map((m) => (
            <text key={`${m.col}-${m.label}`} className="axis-label" x={left + m.col * pitch} y={10}>
              {m.label}
            </text>
          ))}
          {WEEKDAYS.map((d, i) =>
            d ? (
              <text key={d} className="axis-label" x={0} y={top + i * pitch + size - 1}>
                {d}
              </text>
            ) : null
          )}
          {cells
            .filter((c) => c.value !== null)
            .map((c) => (
              <rect
                key={c.t}
                className={`cal-cell l${level(c.value)}`}
                x={left + c.col * pitch}
                y={top + c.row * pitch}
                width={size}
                height={size}
                rx={2}
                onMouseEnter={() => setHover(c)}
              />
            ))}
        </svg>
      </div>
      <div className="row between small" style={{ marginTop: 8 }}>
        <span className="muted">{hover ? `${new Date(hover.t).toLocaleDateString(undefined, { weekday: 'short', year: 'numeric', month: 'short', day: 'numeric', timeZone: 'UTC' })}: ${format(hover.value)}` : ' '}</span>
        <span className="row faint" style={{ gap: 4 }}>
          Less
          <svg width={5 * pitch} height={size} aria-hidden="true">
            {[0, 1, 2, 3, 4].map((l) => (
              <rect key={l} className={`cal-cell l${l}`} x={l * pitch} y={0} width={size} height={size} rx={2} />
            ))}
          </svg>
          More
        </span>
      </div>
    </div>
  );
}
