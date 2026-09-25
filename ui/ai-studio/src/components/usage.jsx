import { useState } from 'react';
import { Sparkline, StackedBars, seriesColor } from './charts';
import { Empty, Loading, Progress, Segmented, useAsync } from './ui';
import { NoExporterError, runQuery, scalarOf, seriesOf } from '../lib/analytics';
import { Link } from '../lib/router';
import { budgetConsumption, listBudgets, periodLabel } from '../lib/budgets';
import { fmtCost, fmtInt, fmtNumber } from '../lib/format';

// Building blocks of the usage pages (activity, users): KPIs, consumer tables, usage over time, budgets.

export function Delta({ value, previous, inverse }) {
  if (previous === null || previous === undefined) return null;
  if (previous === 0) return value > 0 ? <span className="kpi delta up">new</span> : <span className="faint small">—</span>;
  const ratio = (value - previous) / previous;
  const up = ratio >= 0;
  const good = inverse ? !up : up;
  return (
    <span className={`delta ${good ? 'up' : 'down'}`}>
      {up ? '↑' : '↓'} {Math.abs(ratio * 100).toFixed(1)}%
    </span>
  );
}

export function Kpi({ label, value, previous, format, points, inverse }) {
  return (
    <div className="card tight kpi">
      <div className="top">
        <div>
          <div className="label">{label}</div>
          <div className="value">{format(value)}</div>
        </div>
        <Sparkline points={points} />
      </div>
      <div className="row between">
        <Delta value={value} previous={previous} inverse={inverse} />
        <span className="vs">vs prev period</span>
      </div>
    </div>
  );
}

// Usage per API key or per user over the period, one row per consumer. Clicking a row narrows the whole
// page to that consumer.
export function ConsumersCard({ title, description, items, active, onPick, empty }) {
  const max = Math.max(1, ...items.map((i) => Number(i.tokens) || 0));
  return (
    <div className="card flush">
      <div style={{ padding: '18px 22px 6px' }}>
        <h2>{title}</h2>
        {description && (
          <p className="muted small" style={{ marginTop: 4 }}>
            {description}
          </p>
        )}
      </div>
      {items.length === 0 ? (
        <Empty>{empty}</Empty>
      ) : (
        <div className="table-wrap">
          <table className="table consumers">
            <thead>
              <tr>
                <th>Name</th>
                <th className="num">Requests</th>
                <th className="num">Tokens</th>
                <th className="num">Spend</th>
                <th className="num">Errors</th>
              </tr>
            </thead>
            <tbody>
              {items.map((it) => (
                <tr key={it.key} className={`clickable ${active === it.value ? 'selected' : ''}`} onClick={() => onPick(it.value)} title={active === it.value ? 'Clear this filter' : `Only show the usage of ${it.label}`}>
                  <td>
                    <div className="truncate" style={{ maxWidth: 260 }}>
                      {it.label}
                    </div>
                    <div className="rank">
                      <div className="bar">
                        <i style={{ width: `${((Number(it.tokens) || 0) / max) * 100}%` }} />
                      </div>
                    </div>
                  </td>
                  <td className="num">{fmtInt(it.calls)}</td>
                  <td className="num">{fmtNumber(it.tokens)}</td>
                  <td className="num">{fmtCost(it.spend_usd)}</td>
                  <td className="num">{Number(it.errors) ? fmtInt(it.errors) : <span className="faint">0</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

// Live consumption of the budgets of a workspace, optionally only some of them
export function BudgetsCard({ workspace, title = 'Budgets', description = 'Live consumption of the current window of each budget.', filter = () => true, empty = 'No budget in this workspace.' }) {
  const data = useAsync(async () => {
    const budgets = (await listBudgets(workspace.id)).filter(filter);
    const consumptions = await Promise.all(budgets.map((b) => budgetConsumption(b.id).catch(() => null)));
    return budgets.map((b, i) => ({ budget: b, consumption: consumptions[i] }));
  }, [workspace.id]);
  const list = data.data || [];
  if (data.loading && !data.data) return null;
  return (
    <div className="card">
      <h2>{title}</h2>
      <p className="muted" style={{ margin: '4px 0 12px' }}>
        {description}
      </p>
      {list.length === 0 && <p className="muted small">{empty}</p>}
      <div className="stack">
        {list.map(({ budget, consumption }) => {
          const usd = consumption ? Number(consumption.consumed_total_usd) || 0 : 0;
          const tokens = consumption ? Number(consumption.consumed_total_tokens) || 0 : 0;
          const limitUsd = budget.limits && budget.limits.total_usd;
          const limitTokens = budget.limits && budget.limits.total_tokens;
          return (
            <div key={budget.id} className="stack tight">
              <div className="row between">
                <span>
                  {budget.name} <span className="faint small">· {periodLabel(budget)}</span>
                </span>
                <span className="small">
                  {limitUsd !== undefined && limitUsd !== null ? `${fmtCost(usd)} / ${fmtCost(limitUsd)}` : fmtCost(usd)}
                  {' · '}
                  {limitTokens ? `${fmtNumber(tokens)} / ${fmtNumber(limitTokens)} tokens` : `${fmtNumber(tokens)} tokens`}
                </span>
              </div>
              {(limitUsd || limitTokens) && <Progress value={limitUsd ? usd : tokens} max={limitUsd || limitTokens} />}
            </div>
          );
        })}
      </div>
    </div>
  );
}

export const METRICS = {
  spend: { label: 'Spend', query: 'cost', format: fmtCost },
  tokens: { label: 'Tokens', query: 'tokens', format: fmtNumber },
  requests: { label: 'Requests', query: 'requests', format: fmtNumber },
};

export const METRIC_OPTIONS = Object.entries(METRICS).map(([value, m]) => ({ value, label: m.label }));

// "Usage by <dimension>" card: one stacked bar series per model, api key or user. The metric is its own, or the
// page's when it passes `metric` and `onMetric` (to keep it in its query string)
export function UsageCard({ title, description, dimension, run, deps, bucket, empty, metric: shared, onMetric }) {
  const [own, setOwn] = useState('tokens');
  const metric = onMetric ? (METRICS[shared] ? shared : 'tokens') : own;
  const setMetric = onMetric || setOwn;
  const data = useAsync(() => run(`cloudapim_llm_${METRICS[metric].query}_by_${dimension}_over_time`, { params: { top_n: 7 } }), [...deps, metric]);
  return (
    <div className="card">
      <div className="card-head">
        <div>
          <h2>{title}</h2>
          {description && <p>{description}</p>}
        </div>
        <Segmented value={metric} onChange={setMetric} options={METRIC_OPTIONS} />
      </div>
      {data.loading && !data.data ? <Loading /> : <StackedBars series={seriesOf(data.data)} bucket={bucket} format={METRICS[metric].format} height={240} empty={empty} />}
    </div>
  );
}

// The week of a workspace at a glance, on its overview: what it spent, how many calls it served and how
// many tokens it moved, each with the models behind it. Only shown once the workspace has been called,
// and only when the instance stores its usage (no analytics exporter, no panel).
const WEEK_CARDS = [
  { id: 'spend', label: 'Spend', metric: 'spend', total: 'cloudapim_llm_cost_total', format: fmtCost },
  { id: 'requests', label: 'Requests', metric: 'requests', total: 'cloudapim_llm_requests_total', format: fmtNumber },
  { id: 'tokens', label: 'Tokens', metric: 'tokens', total: 'cloudapim_llm_tokens_total', format: fmtNumber },
];

// the models behind a metric: the biggest three, then everything else as one line
function breakdown(series, total, max = 3) {
  const summed = series.map((s, idx) => ({ name: s.name, color: seriesColor(idx, s.name), value: (s.points || []).reduce((acc, p) => acc + (Number(p.value) || 0), 0) }));
  const top = summed.filter((s) => s.value > 0).slice(0, max);
  const others = total - top.reduce((acc, s) => acc + s.value, 0);
  // the series only carry the top models of the period, the rest is what the total has above them
  return others > total * 0.001 ? [...top, { name: 'Others', color: 'var(--chart-other)', value: others }] : top;
}

export function WeekUsage({ workspace, href }) {
  const data = useAsync(async () => {
    const q = (id, params) => runQuery(workspace.id, id, { period: '7d', ...(params ? { params } : {}) });
    try {
      const results = await Promise.all(
        WEEK_CARDS.flatMap((c) => [q(c.total), q('cloudapim_llm_explore', { metric: c.metric, group_by: 'model', rollup: 'day', top_n: 6 })])
      );
      return WEEK_CARDS.map((c, i) => ({ ...c, value: scalarOf(results[i * 2]), series: seriesOf(results[i * 2 + 1]) }));
    } catch (e) {
      // a workspace whose usage is not stored anywhere simply has no panel
      if (e instanceof NoExporterError) return null;
      throw e;
    }
  }, [workspace.id]);

  const cards = data.data;
  if (!cards || !cards.some((c) => c.value > 0)) return null;
  return (
    <>
      <div className="page-header" style={{ marginTop: 28, marginBottom: 14 }}>
        <div>
          <h2>This week</h2>
          <p>What this workspace served over the past 7 days.</p>
        </div>
        {href && (
          <Link className="btn sm" to={href}>
            View activity
          </Link>
        )}
      </div>
      <div className="grid cols-3">
        {cards.map((c) => (
          <div key={c.id} className="card week-card">
            <div className="label">{c.label}</div>
            <div className="value">{c.format(c.value)}</div>
            <StackedBars compact series={c.series} bucket="1d" format={c.format} height={68} empty=" " />
            <div className="week-models">
              {breakdown(c.series, c.value).map((row) => (
                <div key={row.name} className="row between" title={row.name}>
                  <span className="truncate">
                    <i style={{ background: row.color }} />
                    {row.name}
                  </span>
                  <b>{c.format(row.value)}</b>
                </div>
              ))}
            </div>
          </div>
        ))}
      </div>
    </>
  );
}
