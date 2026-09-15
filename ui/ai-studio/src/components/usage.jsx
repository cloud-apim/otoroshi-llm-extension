import { useState } from 'react';
import { Sparkline, StackedBars } from './charts';
import { Empty, Loading, Progress, Segmented, useAsync } from './ui';
import { seriesOf } from '../lib/analytics';
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

// "Usage by <dimension>" card: one stacked bar series per model, api key or user
export function UsageCard({ title, description, dimension, run, deps, bucket, empty }) {
  const [metric, setMetric] = useState('tokens');
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
