import { useMemo, useState } from 'react';
import { useWorkspace } from '../App';
import { CalendarHeatmap, StackedBars } from '../components/charts';
import { Icon } from '../components/icons';
import { Badge, Empty, ErrorAlert, Loading, PageHeader, Segmented, Select, StatusBadge, useAsync } from '../components/ui';
import { BudgetsCard, Delta, METRIC_OPTIONS, METRICS } from '../components/usage';
import { compareOf, itemsOf, NoExporterError, PERIODS, runQuery, scalarOf, seriesOf, totalPoints } from '../lib/analytics';
import { listApikeys, ownerOf } from '../lib/apikeys';
import { bootstrap } from '../lib/bootstrap';
import { budgetNamesUser, isWorkspaceWide, keyBudgetOf, listBudgets, periodLabel } from '../lib/budgets';
import { fmtCost, fmtInt, fmtNumber, initials } from '../lib/format';
import { Link, useRouter } from '../lib/router';

// People of a workspace. There is no member entity: a user is whoever the usage counts for, the person
// chatting from AI Studio or the owner of an API key.

const SUMMARY_PERIODS = PERIODS.filter((p) => ['7d', '30d', '90d'].includes(p.value));

// the scalar, ranking and daily series behind each metric
const METRIC_QUERIES = {
  spend: { total: 'cloudapim_llm_cost_total', byModel: 'cloudapim_llm_cost_by_model', daily: 'cloudapim_llm_cost_over_time' },
  tokens: { total: 'cloudapim_llm_tokens_total', byModel: 'cloudapim_llm_tokens_by_model', daily: 'cloudapim_llm_tokens_over_time' },
  requests: { total: 'cloudapim_llm_requests_total', byModel: 'cloudapim_llm_top_models', daily: 'cloudapim_llm_requests_over_time' },
};

const profilePath = (wsId, email) => `/workspaces/${wsId}/users/${encodeURIComponent(email)}`;

function NoExporter() {
  return (
    <div className="alert info">
      Usage needs an active <b>user analytics exporter</b> (PostgreSQL) in Otoroshi. Create one in{' '}
      <a className="link" href="/bo/dashboard/exporters" target="_blank" rel="noreferrer">
        data exporters
      </a>
      .
    </div>
  );
}

function UsersList() {
  const { workspace } = useWorkspace();
  const { navigate } = useRouter();
  const [period, setPeriod] = useState('30d');
  const keys = useAsync(() => listApikeys(workspace.id), [workspace.id]);
  const usage = useAsync(() => runQuery(workspace.id, 'cloudapim_llm_users_table', { period, params: { top_n: 200 } }).then(itemsOf), [workspace.id, period]);
  const me = bootstrap.user.email;

  const rows = useMemo(() => {
    const owned = new Map();
    (keys.data || []).forEach((k) => ownerOf(k) && owned.set(ownerOf(k), (owned.get(ownerOf(k)) || 0) + 1));
    const byEmail = new Map((usage.data || []).map((u) => [u.user, u]));
    const emails = new Set([...byEmail.keys(), ...owned.keys(), me]);
    return [...emails]
      .filter(Boolean)
      .map((email) => ({ email, keys: owned.get(email) || 0, ...(byEmail.get(email) || { calls: 0, tokens: 0, spend_usd: 0, errors: 0 }) }))
      .sort((a, b) => Number(b.spend_usd) - Number(a.spend_usd) || Number(b.tokens) - Number(a.tokens) || a.email.localeCompare(b.email));
  }, [keys.data, usage.data, me]);

  return (
    <div className="content">
      <PageHeader title="Users" description="The people using this workspace, from the AI Studio chat or through the API keys they own.">
        <Select className="sm" value={period} onChange={setPeriod} options={SUMMARY_PERIODS.map((p) => ({ value: p.value, label: p.label }))} />
      </PageHeader>
      <div className="stack">
        {usage.error instanceof NoExporterError ? <NoExporter /> : <ErrorAlert error={usage.error || keys.error} />}
        <div className="card flush">
          {(usage.loading || keys.loading) && !usage.data && !keys.data ? (
            <div style={{ padding: 20 }}>
              <Loading />
            </div>
          ) : (
            <div className="table-wrap">
              <table className="table">
                <thead>
                  <tr>
                    <th>User</th>
                    <th className="num">API keys</th>
                    <th className="num">Requests</th>
                    <th className="num">Tokens</th>
                    <th className="num">Spend</th>
                    <th className="num">Errors</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r) => (
                    <tr key={r.email} className="clickable" onClick={() => navigate(profilePath(workspace.id, r.email))}>
                      <td>
                        <span className="row" style={{ gap: 10 }}>
                          <span className="avatar">{initials(r.email)}</span>
                          <span className="truncate" style={{ maxWidth: 320 }}>
                            {r.email}
                          </span>
                          {r.email === me && <Badge kind="info">you</Badge>}
                        </span>
                      </td>
                      <td className="num">{r.keys ? fmtInt(r.keys) : <span className="faint">0</span>}</td>
                      <td className="num">{fmtInt(r.calls)}</td>
                      <td className="num">{fmtNumber(r.tokens)}</td>
                      <td className="num">{fmtCost(r.spend_usd)}</td>
                      <td className="num">{Number(r.errors) ? fmtInt(r.errors) : <span className="faint">0</span>}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
        <p className="faint small">
          Calls from the AI Studio chat count for the person chatting, calls made with an API key count for its owner. Workspace keys have no owner.
        </p>
      </div>
    </div>
  );
}

function UsageSummary({ workspace, email }) {
  const [metric, setMetric] = useState('spend');
  const [period, setPeriod] = useState('30d');
  const data = useAsync(async () => {
    const q = (id, extra = {}) => runQuery(workspace.id, id, { period, user: email, ...extra });
    const m = METRIC_QUERIES[metric];
    const [total, byModelTs, topModels] = await Promise.all([q(m.total, { compare: true }), q(`cloudapim_llm_${METRICS[metric].query}_by_model_over_time`, { params: { top_n: 7 } }), q(m.byModel, { params: { top_n: 6 } })]);
    return { total, byModelTs, topModels };
  }, [workspace.id, email, metric, period]);
  const d = data.data;
  const format = METRICS[metric].format;
  const models = d ? itemsOf(d.topModels) : [];
  const maxModel = Math.max(0, ...models.map((m) => Number(m.value) || 0));

  return (
    <div className="card">
      <div className="card-head">
        <div>
          <h2>Usage summary</h2>
        </div>
        <div className="row">
          <Select className="sm" style={{ width: 'auto' }} value={period} onChange={setPeriod} options={SUMMARY_PERIODS.map((p) => ({ value: p.value, label: p.label }))} />
          <Segmented value={metric} onChange={setMetric} options={METRIC_OPTIONS} />
        </div>
      </div>
      {data.error instanceof NoExporterError ? (
        <NoExporter />
      ) : (
        <>
          <ErrorAlert error={data.error} />
          {data.loading && !d && <Loading />}
          {d && (
            <div className="grid split">
              <div className="stack tight">
                <div className="row" style={{ alignItems: 'baseline', gap: 10 }}>
                  <span className="big-number">{format(scalarOf(d.total))}</span>
                  <Delta value={scalarOf(d.total)} previous={compareOf(d.total)} inverse={metric === 'spend'} />
                  <span className="faint small">vs prev period</span>
                </div>
                <StackedBars series={seriesOf(d.byModelTs)} bucket={d.byModelTs.meta && d.byModelTs.meta.bucket} format={format} height={220} empty="No usage in this period" />
              </div>
              <div>
                <h3 style={{ marginBottom: 8 }}>Top models</h3>
                {models.length === 0 ? (
                  <p className="muted small">No usage in this period.</p>
                ) : (
                  <div className="rank">
                    {models.map((m, i) => (
                      <div key={m.key} className="item">
                        <span className="pos">{i + 1}</span>
                        <div className="grow" style={{ minWidth: 0 }}>
                          <div className="row between">
                            <span className="mono truncate small">{m.label || m.key}</span>
                            <span className="small">{format(m.value)}</span>
                          </div>
                          <div className="bar">
                            <i style={{ width: `${maxModel ? ((Number(m.value) || 0) / maxModel) * 100 : 0}%` }} />
                          </div>
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}

function longestStreak(points) {
  let best = 0;
  let current = 0;
  points.forEach((p) => {
    current = Number(p.value) > 0 ? current + 1 : 0;
    best = Math.max(best, current);
  });
  return best;
}

function YearActivity({ workspace, email }) {
  const [metric, setMetric] = useState('requests');
  const data = useAsync(
    () => runQuery(workspace.id, METRIC_QUERIES[metric].daily, { from: 'now-365d', bucket: '1d', user: email }).then(totalPoints),
    [workspace.id, email, metric]
  );
  const points = data.data || [];
  const format = METRICS[metric].format;
  const total = points.reduce((acc, p) => acc + (Number(p.value) || 0), 0);
  const activeDays = points.filter((p) => Number(p.value) > 0).length;
  const streak = longestStreak(points);
  const stats = [
    ['Longest streak', `${fmtInt(streak)} day${streak === 1 ? '' : 's'}`],
    ['Active days', fmtInt(activeDays)],
    ['Avg / day', format(total / 365)],
    ['Avg / week', format((total / 365) * 7)],
    ['Total', format(total)],
  ];
  return (
    <div className="card">
      <div className="card-head">
        <div>
          <h2>Activity</h2>
          <p>The past year, day by day (UTC).</p>
        </div>
        <Segmented value={metric} onChange={setMetric} options={METRIC_OPTIONS} />
      </div>
      {data.error instanceof NoExporterError ? (
        <NoExporter />
      ) : (
        <>
          <ErrorAlert error={data.error} />
          {data.loading && !data.data ? (
            <Loading />
          ) : (
            <div className="stack">
              <div className="stats-row">
                {stats.map(([label, value]) => (
                  <div key={label}>
                    <div className="muted small">{label}</div>
                    <div className="stat">{value}</div>
                  </div>
                ))}
              </div>
              <CalendarHeatmap points={points} format={format} />
            </div>
          )}
        </>
      )}
    </div>
  );
}

function OwnedKeys({ workspace, email }) {
  const data = useAsync(async () => {
    const [keys, budgets, usage] = await Promise.all([
      listApikeys(workspace.id),
      listBudgets(workspace.id),
      runQuery(workspace.id, 'cloudapim_llm_apikeys_table', { period: '30d', user: email, params: { top_n: 200 } })
        .then(itemsOf)
        .catch(() => []),
    ]);
    return { keys: keys.filter((k) => ownerOf(k) === email), budgets, usage };
  }, [workspace.id, email]);
  const d = data.data;
  // the usage table is keyed by key name
  const usageOf = (k) => (d ? d.usage.find((u) => u.apikey === k.clientName || u.apikey === k.clientId) : null) || {};
  return (
    <div className="card flush">
      <div className="card-head" style={{ padding: '18px 22px 6px' }}>
        <div>
          <h2>API keys</h2>
          <p>The keys owned by {email}, with their usage over the past month.</p>
        </div>
        <Link className="btn sm" to={`/workspaces/${workspace.id}/keys`}>
          <Icon name="key" />
          Manage keys
        </Link>
      </div>
      <ErrorAlert error={data.error} />
      {data.loading && !d && (
        <div style={{ padding: 20 }}>
          <Loading />
        </div>
      )}
      {d && d.keys.length === 0 && <Empty>No API key is owned by {email}.</Empty>}
      {d && d.keys.length > 0 && (
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Client id</th>
                <th>Key limit</th>
                <th className="num">Requests</th>
                <th className="num">Tokens</th>
                <th className="num">Spend</th>
                <th>Status</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {d.keys.map((k) => {
                const u = usageOf(k);
                const b = keyBudgetOf(d.budgets, k.clientId);
                const limit = b && b.limits ? b.limits.total_usd : null;
                return (
                  <tr key={k.clientId}>
                    <td>{k.clientName}</td>
                    <td className="mono truncate" style={{ maxWidth: 220 }}>
                      {k.clientId}
                    </td>
                    <td>
                      {limit !== null && limit !== undefined ? (
                        <>
                          {fmtCost(limit)} <span className="muted small">· {periodLabel(b)}</span>
                        </>
                      ) : (
                        <span className="muted">unlimited</span>
                      )}
                    </td>
                    <td className="num">{fmtInt(u.calls || 0)}</td>
                    <td className="num">{fmtNumber(u.tokens || 0)}</td>
                    <td className="num">{fmtCost(u.spend_usd || 0)}</td>
                    <td>
                      <StatusBadge enabled={k.enabled} />
                    </td>
                    <td className="actions">
                      <Link className="btn sm" to={`/workspaces/${workspace.id}/activity?apikey=${encodeURIComponent(k.clientId)}`} title="Usage of this key">
                        <Icon name="chart" />
                        Activity
                      </Link>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function UserProfile({ email }) {
  const { workspace } = useWorkspace();
  const me = bootstrap.user.email === email;
  return (
    <div className="content wide" style={{ maxWidth: 1200 }}>
      <Link className="navlink muted" to={`/workspaces/${workspace.id}/users`}>
        <Icon name="arrowLeft" />
        All users
      </Link>
      <div className="profile-head">
        <span className="avatar lg">{initials(email)}</span>
        <div className="grow" style={{ minWidth: 0 }}>
          <h1 className="truncate">
            {email} {me && <Badge kind="info">you</Badge>}
          </h1>
          <p className="muted">Their chats in AI Studio and the calls of the API keys they own.</p>
        </div>
        <Link className="btn sm" to={`/workspaces/${workspace.id}/activity?user=${encodeURIComponent(email)}`}>
          <Icon name="chart" />
          Full activity
        </Link>
      </div>
      <div className="stack">
        <UsageSummary workspace={workspace} email={email} />
        <YearActivity workspace={workspace} email={email} />
        <OwnedKeys workspace={workspace} email={email} />
        <BudgetsCard
          workspace={workspace}
          description={`The budgets counting the usage of ${email}: the ones naming them and the ones of the whole workspace.`}
          filter={(b) => b.enabled && (budgetNamesUser(b, email) || isWorkspaceWide(b))}
          empty="No budget applies to this user."
        />
      </div>
    </div>
  );
}

export function UsersPage({ sub }) {
  return sub ? <UserProfile email={sub} /> : <UsersList />;
}
