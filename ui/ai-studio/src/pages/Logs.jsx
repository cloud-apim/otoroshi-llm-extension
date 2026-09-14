import { useEffect, useState } from 'react';
import { useWorkspace } from '../App';
import { StackedBars } from '../components/charts';
import { Icon } from '../components/icons';
import { Badge, CopyButton, Drawer, Empty, ErrorAlert, Loading, PageHeader, Select, useAsync } from '../components/ui';
import { itemsOf, NoExporterError, PERIODS, runQuery, seriesOf } from '../lib/analytics';
import { listApikeys } from '../lib/apikeys';
import { listBudgets } from '../lib/budgets';
import { fmtCost, fmtDate, fmtInt, fmtMs, fmtNumber } from '../lib/format';

const PAGE = 50;

function StatusBadge({ call }) {
  if (call.err) return <Badge kind="negative">{call.error_kind || 'error'}</Badge>;
  if (call.cache_status === 'hit') return <Badge kind="info">cached</Badge>;
  return <Badge kind="positive">ok</Badge>;
}

function CallDrawer({ workspace, call, period, onClose }) {
  const detail = useAsync(
    () => runQuery(workspace.id, 'cloudapim_llm_call_detail', { period: '90d', params: { id: call.id }, nocache: true }).then((r) => itemsOf(r)[0] || call),
    [call.id]
  );
  const budgets = useAsync(() => listBudgets(workspace.id), [workspace.id]);
  const d = detail.data || call;
  let raw = d.raw;
  if (typeof raw === 'string') {
    try {
      raw = JSON.parse(raw);
    } catch (e) {}
  }
  const budgetName = (id) => ((budgets.data || []).find((b) => b.id === id) || { name: id }).name;
  const rows = [
    ['Date', fmtDate(d.ts)],
    ['Request id', d.request_id],
    ['Model', d.model],
    ['Provider', `${d.provider_name || d.provider_id || '—'} (${d.provider_kind || '—'})`],
    ['Operation', d.consumed_using],
    ['Streaming', d.streaming ? 'yes' : 'no'],
    ['API key', d.apikey_name || d.apikey_id],
    ['User', d.user_email],
    ['Client ip', d.from_ip],
    ['Input tokens', fmtInt(d.input_tokens)],
    ['Output tokens', fmtInt(d.output_tokens)],
    ['Reasoning tokens', fmtInt(d.reasoning_tokens)],
    ['Cost', d.total_cost === null || d.total_cost === undefined ? 'not priced' : `${fmtCost(d.total_cost)} (${d.cost_source || '—'})`],
    ['Latency', fmtMs(d.duration_ms)],
    ['Cache', d.cache_status || '—'],
    ['Emissions', d.gwp_kgco2eq ? `${(Number(d.gwp_kgco2eq) * 1000).toFixed(3)} gCO2eq` : '—'],
    ['Budgets', (d.budget_ids || []).map(budgetName).join(', ') || '—'],
  ];
  return (
    <Drawer open title="Call details" onClose={onClose}>
      <div className="row">
        <StatusBadge call={d} />
        <span className="muted small">{d.id}</span>
      </div>
      {d.err && <div className="alert error">{d.error_message || d.error_kind}</div>}
      <dl className="kv" style={{ marginTop: 0 }}>
        {rows.map(([k, v]) => (
          <div key={k} style={{ display: 'contents' }}>
            <dt>{k}</dt>
            <dd className={k === 'Model' || k === 'Request id' ? 'mono' : ''}>{v || '—'}</dd>
          </div>
        ))}
      </dl>
      {detail.loading && <Loading />}
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

export function LogsPage() {
  const { workspace } = useWorkspace();
  const [period, setPeriod] = useState('24h');
  const [apikey, setApikey] = useState('');
  const [model, setModel] = useState('');
  const [status, setStatus] = useState('');
  const [search, setSearch] = useState('');
  const [debounced, setDebounced] = useState('');
  const [refresh, setRefresh] = useState(0);
  const [pages, setPages] = useState({ items: [], next: null, loadingMore: false });
  const [selected, setSelected] = useState(null);
  const keys = useAsync(() => listApikeys(workspace.id), [workspace.id]);

  useEffect(() => {
    const t = setTimeout(() => setDebounced(search), 350);
    return () => clearTimeout(t);
  }, [search]);

  const opts = { period, apikey: apikey || undefined, nocache: true };
  const params = { limit: PAGE, model: model || undefined, status: status || undefined, search: debounced || undefined };

  const first = useAsync(async () => {
    const [log, calls, models] = await Promise.all([
      runQuery(workspace.id, 'cloudapim_llm_calls_log', { ...opts, params }),
      runQuery(workspace.id, 'cloudapim_llm_requests_over_time', { ...opts, err: status === 'error' ? true : undefined }),
      runQuery(workspace.id, 'cloudapim_llm_top_models', { ...opts, params: { top_n: 50 } }),
    ]);
    return { log, calls, models };
  }, [workspace.id, period, apikey, model, status, debounced, refresh]);

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

  return (
    <div className="content wide" style={{ maxWidth: 1400 }}>
      <PageHeader title="Logs" description="Every call made through this workspace, newest first. Only metadata is recorded: prompts and outputs are never stored.">
        <button className="btn sm" onClick={() => setRefresh((r) => r + 1)} title="Refresh">
          <Icon name="refresh" />
        </button>
      </PageHeader>

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
            <input className="input search sm" style={{ maxWidth: 260 }} placeholder="Search model, key, error" value={search} onChange={(e) => setSearch(e.target.value)} />
            <Select className="sm" style={{ width: 'auto' }} value={model} onChange={setModel} placeholder="All models" options={modelOptions} />
            <Select className="sm" style={{ width: 'auto' }} value={apikey} onChange={setApikey} placeholder="All API keys" options={(keys.data || []).map((k) => ({ value: k.clientId, label: k.clientName }))} />
            <Select
              className="sm"
              style={{ width: 'auto' }}
              value={status}
              onChange={setStatus}
              placeholder="All statuses"
              options={[
                { value: 'ok', label: 'Success' },
                { value: 'error', label: 'Errors' },
                { value: 'cached', label: 'Cached' },
              ]}
            />
            <div className="grow" />
            <Select className="sm" style={{ width: 'auto' }} value={period} onChange={setPeriod} options={PERIODS.map((p) => ({ value: p.value, label: p.label }))} />
          </div>
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
                      <th>Date</th>
                      <th>Model</th>
                      <th>Provider</th>
                      <th>Consumer</th>
                      <th className="num">Input</th>
                      <th className="num">Output</th>
                      <th className="num">Cost</th>
                      <th className="num">Latency</th>
                      <th>Status</th>
                    </tr>
                  </thead>
                  <tbody>
                    {pages.items.map((call) => (
                      <tr key={call.id} className="clickable" onClick={() => setSelected(call)}>
                        <td style={{ whiteSpace: 'nowrap' }}>{fmtDate(call.ts)}</td>
                        <td className="mono truncate" style={{ maxWidth: 260 }}>
                          {call.model || '—'}
                        </td>
                        <td>{call.provider_name || call.provider_kind || '—'}</td>
                        <td className="truncate" style={{ maxWidth: 180 }}>
                          {call.user_email || call.apikey_name || call.apikey_id || '—'}
                        </td>
                        <td className="num">{fmtInt(call.input_tokens)} tok</td>
                        <td className="num">
                          {fmtInt(Number(call.output_tokens || 0) + Number(call.reasoning_tokens || 0))} tok
                        </td>
                        <td className="num">{call.total_cost === null || call.total_cost === undefined ? '—' : fmtCost(call.total_cost)}</td>
                        <td className="num">{fmtMs(call.duration_ms)}</td>
                        <td>
                          <StatusBadge call={call} />
                        </td>
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
        </div>
      )}
      {selected && <CallDrawer workspace={workspace} call={selected} period={period} onClose={() => setSelected(null)} />}
    </div>
  );
}
