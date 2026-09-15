import { useEffect, useRef, useState } from 'react';
import { useWorkspace } from '../App';
import { StackedBars } from '../components/charts';
import { Icon } from '../components/icons';
import { Badge, CopyButton, Drawer, Empty, ErrorAlert, Loading, PageHeader, Select, Tabs, useAsync } from '../components/ui';
import { itemsOf, NoExporterError, PERIODS, runQuery, seriesOf } from '../lib/analytics';
import { listApikeys } from '../lib/apikeys';
import { listBudgets } from '../lib/budgets';
import { fmtCost, fmtDate, fmtInt, fmtMs, fmtNumber } from '../lib/format';
import { Link, useRouter } from '../lib/router';

const PAGE = 50;
const DEFAULT_PERIOD = '24h';
const FINISH_REASONS = ['stop', 'length', 'tool_calls', 'content_filter'];

function StatusBadge({ call }) {
  if (call.err) return <Badge kind="negative">{call.error_kind || 'error'}</Badge>;
  if (call.cache_status === 'hit') return <Badge kind="info">cached</Badge>;
  return <Badge kind="positive">ok</Badge>;
}

const generated = (call) => Number(call.output_tokens || 0) + Number(call.reasoning_tokens || 0);

// generated tokens per second of generation, the wait for the first token excluded when it is known
function speedOf(call) {
  const duration = Number(call.duration_ms) - Number(call.ttft_ms || 0);
  return duration > 0 && generated(call) > 0 ? (generated(call) * 1000) / duration : null;
}

const fmtSpeed = (v) => (v === null ? '—' : `${v.toFixed(1)} tok/s`);

function FinishReason({ reason }) {
  if (!reason) return <span className="faint">—</span>;
  return reason === 'stop' ? <span className="muted">stop</span> : <Badge kind={reason === 'length' || reason === 'content_filter' ? 'warning' : 'info'}>{reason}</Badge>;
}

// the columns of the calls table, the first ones always shown
const COLUMNS = [
  { id: 'date', label: 'Date', fixed: true, render: (c) => <span style={{ whiteSpace: 'nowrap' }}>{fmtDate(c.ts)}</span> },
  { id: 'model', label: 'Model', fixed: true, className: 'mono truncate', style: { maxWidth: 240 }, render: (c) => c.model || '—' },
  { id: 'provider', label: 'Provider', render: (c) => c.provider_name || c.provider_kind || '—' },
  // who the call counts for: the person chatting, otherwise the owner of the key
  { id: 'user', label: 'User', className: 'truncate', style: { maxWidth: 200 }, render: (c) => c.user_email || c.apikey_owner || <span className="faint">—</span> },
  { id: 'apikey', label: 'API key', className: 'truncate', style: { maxWidth: 180 }, render: (c) => c.apikey_name || c.apikey_id || <span className="faint">AI Studio chat</span> },
  { id: 'end_user', label: 'End user', className: 'truncate', style: { maxWidth: 160 }, render: (c) => c.end_user || <span className="faint">—</span>, hidden: true },
  { id: 'input', label: 'Input', num: true, render: (c) => `${fmtInt(c.input_tokens)} tok` },
  { id: 'output', label: 'Output', num: true, render: (c) => `${fmtInt(generated(c))} tok` },
  { id: 'cost', label: 'Cost', num: true, render: (c) => (c.total_cost === null || c.total_cost === undefined ? '—' : fmtCost(c.total_cost)) },
  { id: 'speed', label: 'Speed', num: true, render: (c) => fmtSpeed(speedOf(c)) },
  { id: 'ttft', label: 'First token', num: true, render: (c) => fmtMs(c.ttft_ms), hidden: true },
  { id: 'latency', label: 'Latency', num: true, render: (c) => fmtMs(c.duration_ms) },
  { id: 'finish', label: 'Finish', render: (c) => <FinishReason reason={c.finish_reason} /> },
  { id: 'status', label: 'Status', fixed: true, render: (c) => <StatusBadge call={c} /> },
];

const COLUMNS_KEY = 'ai-studio.logs.columns';

function useColumns() {
  const [shown, setShown] = useState(() => {
    try {
      const saved = JSON.parse(window.localStorage.getItem(COLUMNS_KEY));
      if (Array.isArray(saved)) return saved;
    } catch (e) {}
    return COLUMNS.filter((c) => !c.hidden).map((c) => c.id);
  });
  const toggle = (id) =>
    setShown((current) => {
      const next = current.includes(id) ? current.filter((c) => c !== id) : [...current, id];
      try {
        window.localStorage.setItem(COLUMNS_KEY, JSON.stringify(next));
      } catch (e) {}
      return next;
    });
  return [COLUMNS.filter((c) => c.fixed || shown.includes(c.id)), shown, toggle];
}

function ColumnsMenu({ shown, toggle }) {
  const [open, setOpen] = useState(false);
  const ref = useRef(null);
  useEffect(() => {
    if (!open) return;
    const onClick = (e) => ref.current && !ref.current.contains(e.target) && setOpen(false);
    document.addEventListener('mousedown', onClick);
    return () => document.removeEventListener('mousedown', onClick);
  }, [open]);
  return (
    <div className="menu" ref={ref}>
      <button className="btn sm" onClick={() => setOpen(!open)} title="Choose the columns">
        <Icon name="sliders" />
        Columns
      </button>
      {open && (
        <div className="menu-items" style={{ minWidth: 190 }}>
          {COLUMNS.filter((c) => !c.fixed).map((c) => (
            <label key={c.id} className="check" style={{ padding: '6px 10px' }}>
              <input type="checkbox" checked={shown.includes(c.id)} onChange={() => toggle(c.id)} />
              {c.label}
            </label>
          ))}
        </div>
      )}
    </div>
  );
}

function Stat({ label, value, hint }) {
  return (
    <div className="card tight stat-card">
      <div className="muted small">{label}</div>
      <div className="stat">{value}</div>
      {hint && <div className="faint small">{hint}</div>}
    </div>
  );
}

// every model call made while serving the request of this call: guardrails, failed attempts, fallbacks
function RequestTimeline({ workspace, call, onOpen }) {
  const data = useAsync(
    () => (call.request_id ? runQuery(workspace.id, 'cloudapim_llm_request_calls', { period: '90d', params: { request_id: call.request_id }, nocache: true }).then(itemsOf) : Promise.resolve([])),
    [call.request_id]
  );
  const calls = data.data || [];
  if (calls.length <= 1) return null;
  const start = Math.min(...calls.map((c) => Number(c.ts)));
  const span = Math.max(1, ...calls.map((c) => Number(c.ts) - start + Number(c.duration_ms || 0)));
  return (
    <div className="stack tight">
      <h3>Calls of this request</h3>
      <p className="muted small">Every model call made to serve the request: guardrails, failed attempts and fallbacks. Load balancers and routers re-report the call they delegated.</p>
      <div className="timeline">
        {calls.map((c) => {
          const offset = Number(c.ts) - start;
          return (
            <div key={c.id} className={`timeline-row ${c.id === call.id ? 'current' : ''}`}>
              <button className="link small truncate" onClick={() => !c.delegated && c.id !== call.id && onOpen(c)} disabled={c.delegated || c.id === call.id} title={c.delegated ? 're-reported by a load balancer, a router or a fallback' : 'Open this call'}>
                {c.provider_name || c.provider_kind} · <span className="mono">{c.model || '—'}</span>
              </button>
              <div className="timeline-bar">
                <i className={c.err ? 'err' : c.delegated ? 'delegated' : ''} style={{ left: `${(offset / span) * 100}%`, width: `${Math.max(1, (Number(c.duration_ms || 0) / span) * 100)}%` }} />
              </div>
              <span className="small num">{fmtMs(c.duration_ms)}</span>
              {c.delegated ? <Badge>re-report</Badge> : <StatusBadge call={c} />}
            </div>
          );
        })}
      </div>
    </div>
  );
}

function CallDrawer({ workspace, id, initial, onClose, onOpen, onSession, prev, next }) {
  const detail = useAsync(
    () => runQuery(workspace.id, 'cloudapim_llm_call_detail', { period: '90d', params: { id }, nocache: true }).then((r) => itemsOf(r)[0] || initial || null),
    [id]
  );
  const budgets = useAsync(() => listBudgets(workspace.id), [workspace.id]);
  const d = detail.data || initial;
  if (!d) {
    return (
      <Drawer open title="Call details" onClose={onClose}>
        {detail.loading ? <Loading /> : <Empty title="Call not found">It may be older than 90 days.</Empty>}
      </Drawer>
    );
  }
  let raw = d.raw;
  if (typeof raw === 'string') {
    try {
      raw = JSON.parse(raw);
    } catch (e) {}
  }
  const budgetName = (bid) => ((budgets.data || []).find((b) => b.id === bid) || { name: bid }).name;
  const priced = d.total_cost !== null && d.total_cost !== undefined;
  const section = (title, rows) => (
    <div className="stack tight">
      <h3>{title}</h3>
      <dl className="kv" style={{ marginTop: 0 }}>
        {rows
          .filter(([, v]) => v !== undefined)
          .map(([k, v, mono]) => (
            <div key={k} style={{ display: 'contents' }}>
              <dt>{k}</dt>
              <dd className={mono ? 'mono' : ''}>{v === null || v === '' ? '—' : v}</dd>
            </div>
          ))}
      </dl>
    </div>
  );
  return (
    <Drawer open title="Call details" onClose={onClose}>
      <div className="row between">
        <div className="row wrap" style={{ gap: 6 }}>
          <StatusBadge call={d} />
          <Badge>{d.provider_name || d.provider_kind || '—'}</Badge>
          <span className="mono small truncate">{d.model}</span>
        </div>
        <div className="row" style={{ gap: 4 }}>
          <button className="btn sm ghost" disabled={!prev} onClick={() => prev && onOpen(prev)} title="Newer call">
            ↑
          </button>
          <button className="btn sm ghost" disabled={!next} onClick={() => next && onOpen(next)} title="Older call">
            ↓
          </button>
          <CopyButton text={window.location.href} className="btn sm ghost" label="Link" />
        </div>
      </div>
      {d.err && <div className="alert error">{d.error_message || d.error_kind}</div>}
      <div className="grid cols-3">
        <Stat label="Latency" value={fmtMs(d.duration_ms)} hint={d.ttft_ms ? `first token after ${fmtMs(d.ttft_ms)}` : d.streaming ? 'streamed' : 'blocking'} />
        <Stat label="Speed" value={fmtSpeed(speedOf(d))} />
        <Stat label="Cost" value={priced ? fmtCost(d.total_cost) : 'not priced'} hint={priced ? d.cost_source : null} />
        <Stat label="Tokens" value={`${fmtNumber(d.input_tokens)} → ${fmtNumber(generated(d))}`} hint={Number(d.reasoning_tokens) ? `${fmtNumber(d.reasoning_tokens)} reasoning` : null} />
        <Stat label="Finish reason" value={d.finish_reason || '—'} />
        <Stat label="Cache" value={d.cache_status || '—'} />
      </div>
      {section('Request', [
        ['Date', fmtDate(d.ts)],
        ['Request id', d.request_id, true],
        [
          'Session',
          d.session_id ? (
            <button className="link mono" onClick={() => onSession(d.session_id)} title="Show the calls of this session">
              {d.session_id}
            </button>
          ) : null,
        ],
        ['Operation', d.consumed_using],
        ['Streaming', d.streaming ? 'yes' : 'no'],
      ])}
      {section('Consumer', [
        [
          'User',
          d.user_email || d.apikey_owner ? (
            <Link className="link" to={`/workspaces/${workspace.id}/users/${encodeURIComponent(d.user_email || d.apikey_owner)}`}>
              {d.user_email || d.apikey_owner}
            </Link>
          ) : null,
        ],
        ['API key', d.apikey_name || d.apikey_id || (d.user_email ? 'AI Studio chat' : null)],
        ['Key owner', d.apikey_id ? d.apikey_owner || 'workspace key' : undefined],
        ['End user', d.end_user],
        ['Client ip', d.from_ip, true],
      ])}
      {priced &&
        section('Cost', [
          ['Input', fmtCost(d.input_cost)],
          ['Output', fmtCost(d.output_cost)],
          ['Reasoning', Number(d.reasoning_cost) ? fmtCost(d.reasoning_cost) : undefined],
          ['Total', fmtCost(d.total_cost)],
          ['Emissions', d.gwp_kgco2eq ? `${(Number(d.gwp_kgco2eq) * 1000).toFixed(3)} gCO2eq` : undefined],
          ['Budgets', (d.budget_ids || []).map(budgetName).join(', ') || null],
        ])}
      <RequestTimeline workspace={workspace} call={d} onOpen={onOpen} />
      {raw && (
        <div className="stack tight">
          <div className="row between">
            <h3>Recorded event</h3>
            <CopyButton text={JSON.stringify(raw, null, 2)} />
          </div>
          <p className="muted small">Prompts and completions are never recorded, only the metadata of the call.</p>
          <pre style={{ maxHeight: 360 }}>
            <code>{JSON.stringify(raw, null, 2)}</code>
          </pre>
        </div>
      )}
    </Drawer>
  );
}

function SessionsTab({ workspace, opts, onPick }) {
  const data = useAsync(() => runQuery(workspace.id, 'cloudapim_llm_sessions_table', { ...opts, params: { limit: 100 } }).then(itemsOf), [workspace.id, JSON.stringify(opts)]);
  const sessions = data.data || [];
  return (
    <div className="card flush">
      <ErrorAlert error={data.error} />
      {data.loading && !data.data && (
        <div style={{ padding: 20 }}>
          <Loading />
        </div>
      )}
      {data.data && sessions.length === 0 && (
        <Empty title="No session">
          Group the calls of a conversation or an agent run by sending a session id: the <span className="mono">x-session-id</span> header, or a <span className="mono">session_id</span> field in the body. The AI Studio chat sends one per conversation.
        </Empty>
      )}
      {sessions.length > 0 && (
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>Last activity</th>
                <th>Session</th>
                <th>User</th>
                <th>Primary model</th>
                <th className="num">Models</th>
                <th className="num">Requests</th>
                <th className="num">Tokens</th>
                <th className="num">Spend</th>
                <th className="num">Duration</th>
              </tr>
            </thead>
            <tbody>
              {sessions.map((s) => (
                <tr key={s.session_id} className="clickable" onClick={() => onPick(s.session_id)} title="Show the calls of this session">
                  <td style={{ whiteSpace: 'nowrap' }}>{fmtDate(s.last_ts)}</td>
                  <td className="mono truncate" style={{ maxWidth: 220 }}>
                    {s.session_id}
                  </td>
                  <td className="truncate" style={{ maxWidth: 200 }}>
                    {s.user_email || s.end_user || <span className="faint">—</span>}
                  </td>
                  <td className="mono truncate" style={{ maxWidth: 200 }}>
                    {s.primary_model || '—'}
                  </td>
                  <td className="num">{fmtInt(s.models)}</td>
                  <td className="num">
                    {fmtInt(s.calls)}
                    {Number(s.errors) ? <span className="faint small"> · {fmtInt(s.errors)} err</span> : null}
                  </td>
                  <td className="num">{fmtNumber(s.tokens)}</td>
                  <td className="num">{s.spend_usd === null || s.spend_usd === undefined ? '—' : fmtCost(s.spend_usd)}</td>
                  <td className="num">{fmtMs(Number(s.last_ts) - Number(s.first_ts))}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

export function LogsPage() {
  const { workspace } = useWorkspace();
  // filters, tab and opened call live in the url, so a filtered view or a call can be shared
  const { query, navigate } = useRouter();
  const period = PERIODS.some((p) => p.value === query.period) ? query.period : DEFAULT_PERIOD;
  const { apikey = '', user = '', model = '', status = '', finish = '', session = '', q = '', call = '', tab = 'calls' } = query;
  const setQuery = (patch) => {
    const next = { tab, period, apikey, user, model, status, finish, session, q, call, ...patch };
    const qs = new URLSearchParams(Object.entries(next).filter(([k, v]) => v && !(k === 'period' && v === DEFAULT_PERIOD) && !(k === 'tab' && v === 'calls'))).toString();
    navigate(`/workspaces/${workspace.id}/logs${qs ? `?${qs}` : ''}`, { replace: true, keepScroll: true });
  };
  const [search, setSearch] = useState(q);
  const [refresh, setRefresh] = useState(0);
  const [pages, setPages] = useState({ items: [], next: null, loadingMore: false });
  const [columns, shown, toggleColumn] = useColumns();
  const keys = useAsync(() => listApikeys(workspace.id), [workspace.id]);
  const users = useAsync(
    () =>
      runQuery(workspace.id, 'cloudapim_llm_users_table', { period, params: { top_n: 100 } })
        .then(itemsOf)
        .catch(() => []),
    [workspace.id, period]
  );

  useEffect(() => {
    const t = setTimeout(() => search !== q && setQuery({ q: search }), 350);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [search]);

  const opts = { period, apikey: apikey || undefined, user: user || undefined, nocache: true };
  const params = { limit: PAGE, model: model || undefined, status: status || undefined, finish_reason: finish || undefined, session_id: session || undefined, search: q || undefined };

  const first = useAsync(async () => {
    const [log, calls, models] = await Promise.all([
      runQuery(workspace.id, 'cloudapim_llm_calls_log', { ...opts, params }),
      runQuery(workspace.id, 'cloudapim_llm_requests_over_time', { ...opts, err: status === 'error' ? true : undefined }),
      runQuery(workspace.id, 'cloudapim_llm_top_models', { ...opts, params: { top_n: 50 } }),
    ]);
    return { log, calls, models };
  }, [workspace.id, period, apikey, user, model, status, finish, session, q, refresh]);

  useEffect(() => {
    if (first.data) setPages({ items: itemsOf(first.data.log), next: first.data.log.data.next_before, loadingMore: false });
  }, [first.data]);

  const loadMore = () => {
    setPages((p) => ({ ...p, loadingMore: true }));
    runQuery(workspace.id, 'cloudapim_llm_calls_log', { ...opts, params: { ...params, before: pages.next } })
      .then((res) => setPages((p) => ({ items: [...p.items, ...itemsOf(res)], next: res.data.next_before, loadingMore: false })))
      .catch(() => setPages((p) => ({ ...p, loadingMore: false })));
  };

  const noExporter = first.error instanceof NoExporterError;
  const modelOptions = first.data ? itemsOf(first.data.models).map((m) => ({ value: m.key, label: m.label || m.key })) : [];
  const userOptions = [...new Set([...(users.data || []).map((u) => u.user), ...(user ? [user] : [])])].map((u) => ({ value: u, label: u }));
  const index = pages.items.findIndex((c) => c.id === call);
  const filtered = apikey || user || model || status || finish || session || q;

  return (
    <div className="content wide" style={{ maxWidth: 1500 }}>
      <PageHeader title="Logs" description="Every call made through this workspace, newest first. Only metadata is recorded: prompts and outputs are never stored.">
        <Select className="sm" style={{ width: 'auto' }} value={period} onChange={(v) => setQuery({ period: v })} options={PERIODS.map((p) => ({ value: p.value, label: p.label }))} />
        <button className="btn sm" onClick={() => setRefresh((r) => r + 1)} title="Refresh">
          <Icon name="refresh" />
        </button>
      </PageHeader>

      <Tabs
        tabs={[
          { value: 'calls', label: 'Calls' },
          { value: 'sessions', label: 'Sessions' },
        ]}
        value={tab}
        onChange={(v) => setQuery({ tab: v, call: '' })}
      />

      {noExporter ? (
        <div className="alert info">
          Logs need an active <b>user analytics exporter</b> (PostgreSQL) in Otoroshi. Create one in{' '}
          <a className="link" href="/bo/dashboard/exporters" target="_blank" rel="noreferrer">
            data exporters
          </a>{' '}
          and every call of this workspace will be listed here.
        </div>
      ) : (
        <div className="stack">
          <div className="row wrap">
            {tab === 'calls' && <input className="input search sm" style={{ maxWidth: 240 }} placeholder="Search model, key, user, session, error" value={search} onChange={(e) => setSearch(e.target.value)} />}
            {tab === 'calls' && <Select className="sm" style={{ width: 'auto' }} value={model} onChange={(v) => setQuery({ model: v })} placeholder="All models" options={modelOptions} />}
            <Select className="sm" style={{ width: 'auto' }} value={apikey} onChange={(v) => setQuery({ apikey: v })} placeholder="All API keys" options={(keys.data || []).map((k) => ({ value: k.clientId, label: k.clientName }))} />
            <Select className="sm" style={{ width: 'auto' }} value={user} onChange={(v) => setQuery({ user: v })} placeholder="All users" options={userOptions} />
            {tab === 'calls' && (
              <Select
                className="sm"
                style={{ width: 'auto' }}
                value={status}
                onChange={(v) => setQuery({ status: v })}
                placeholder="All statuses"
                options={[
                  { value: 'ok', label: 'Success' },
                  { value: 'error', label: 'Errors' },
                  { value: 'cached', label: 'Cached' },
                ]}
              />
            )}
            {tab === 'calls' && <Select className="sm" style={{ width: 'auto' }} value={finish} onChange={(v) => setQuery({ finish: v })} placeholder="All finish reasons" options={[...new Set([...FINISH_REASONS, ...(finish ? [finish] : [])])].map((r) => ({ value: r, label: r }))} />}
            <div className="grow" />
            {tab === 'calls' && <ColumnsMenu shown={shown} toggle={toggleColumn} />}
          </div>
          {(session || filtered) && tab === 'calls' && (
            <div className="filter-chips" style={{ margin: 0 }}>
              {session && (
                <button className="chip" onClick={() => setQuery({ session: '' })} title="Remove this filter">
                  session <span className="mono">{session}</span>
                  <Icon name="x" />
                </button>
              )}
              <button
                className="btn sm ghost"
                onClick={() => {
                  setSearch('');
                  setQuery({ apikey: '', user: '', model: '', status: '', finish: '', session: '', q: '' });
                }}
              >
                Clear filters
              </button>
            </div>
          )}

          {tab === 'sessions' ? (
            <SessionsTab workspace={workspace} opts={opts} onPick={(s) => setQuery({ tab: 'calls', session: s })} />
          ) : (
            <>
              <ErrorAlert error={first.error} />
              {first.data && (
                <div className="card tight">
                  <StackedBars series={seriesOf(first.data.calls)} bucket={first.data.calls.meta && first.data.calls.meta.bucket} format={fmtNumber} height={110} />
                </div>
              )}
              <div className="card flush">
                {first.loading && !first.data && (
                  <div style={{ padding: 20 }}>
                    <Loading />
                  </div>
                )}
                {first.data && pages.items.length === 0 && <Empty title="No call">Nothing matches these filters for this period.</Empty>}
                {pages.items.length > 0 && (
                  <div className="table-wrap">
                    <table className="table logs-table">
                      <thead>
                        <tr>
                          {columns.map((col) => (
                            <th key={col.id} className={col.num ? 'num' : ''}>
                              {col.label}
                            </th>
                          ))}
                        </tr>
                      </thead>
                      <tbody>
                        {pages.items.map((c) => (
                          <tr key={c.id} className={`clickable ${c.id === call ? 'selected' : ''}`} onClick={() => setQuery({ call: c.id })}>
                            {columns.map((col) => (
                              <td key={col.id} className={`${col.num ? 'num' : ''} ${col.className || ''}`} style={col.style}>
                                {col.render(c)}
                              </td>
                            ))}
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </div>
              {pages.next && (
                <div className="row" style={{ justifyContent: 'center' }}>
                  <button className="btn" onClick={loadMore} disabled={pages.loadingMore}>
                    {pages.loadingMore ? 'Loading…' : 'Load more'}
                  </button>
                </div>
              )}
            </>
          )}
        </div>
      )}
      {call && (
        <CallDrawer
          key={call}
          workspace={workspace}
          id={call}
          initial={index >= 0 ? pages.items[index] : null}
          prev={index > 0 ? pages.items[index - 1] : null}
          next={index >= 0 && index < pages.items.length - 1 ? pages.items[index + 1] : null}
          onOpen={(c) => setQuery({ call: c.id })}
          onSession={(s) => setQuery({ tab: 'calls', session: s, call: '' })}
          onClose={() => setQuery({ call: '' })}
        />
      )}
    </div>
  );
}
