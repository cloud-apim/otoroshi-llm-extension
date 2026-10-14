import { useState } from 'react';
import { useCan, useWorkspace } from '../App';
import { BudgetModal } from '../components/BudgetModal';
import { Badge, Empty, ErrorAlert, Loading, PageHeader, Progress, useAsync, useConfirm, useToast } from '../components/ui';
import { backend } from '../lib/backend';
import { extensionLabel, extensionsLeft, extraRulesOf, isByConsumer, isCustomPartition, periodLabel } from '../lib/budgets';
import { fmtCost, fmtNumber } from '../lib/format';

// what a consumption used of its limits, the extensions taken included
function Usage({ label, consumed, allowed, format, extra }) {
  if (allowed === undefined || allowed === null) return null;
  return (
    <div className="stack tight">
      <div className="row between small">
        <span className="muted">{label}</span>
        <span>
          {format(consumed)} / {format(allowed)}
          {extra ? <span className="faint"> (+{format(extra)} extra)</span> : null}
        </span>
      </div>
      <Progress value={consumed} max={allowed} />
    </div>
  );
}

// The consumers a budget by consumer counted in the current window, and what an administrator can do for each one
function Consumers({ workspace, budget, write, onChange }) {
  const toast = useToast();
  const confirm = useConfirm();
  const data = useAsync(() => backend.run('budgets.consumers', workspace.id, { bid: budget.id, query: { limit: 100 } }), [workspace.id, budget.id]);
  const items = (data.data && data.data.items) || [];
  const act = (op, consumer, done, question) =>
    confirm(question).then((ok) => {
      if (!ok) return;
      backend
        .run(op, workspace.id, { bid: budget.id, query: { consumer } })
        .then(() => {
          toast.success(done);
          data.reload();
          onChange();
        })
        .catch(toast.error);
    });
  if (data.loading && !data.data) return <Loading />;
  if (items.length === 0) return <p className="muted small">Nobody consumed on this budget in the current window.</p>;
  return (
    <div className="table-wrap">
      <table className="table">
        <thead>
          <tr>
            <th>Consumer</th>
            {budget.usd !== null && budget.usd !== undefined && <th>Spend</th>}
            {budget.tokens !== null && budget.tokens !== undefined && <th>Tokens</th>}
            {budget.extensions_enabled && <th>Extensions</th>}
            {write && <th />}
          </tr>
        </thead>
        <tbody>
          {items.map(({ consumer, consumption: c }) => (
            <tr key={consumer}>
              <td className="mono small">{consumer}</td>
              {budget.usd !== null && budget.usd !== undefined && (
                <td className="small">
                  {fmtCost(Number(c.consumed_total_usd) || 0)} / {fmtCost(c.allowed_total_usd)}
                  {c.extra_total_usd ? <span className="faint"> (+{fmtCost(c.extra_total_usd)} extra)</span> : null}
                </td>
              )}
              {budget.tokens !== null && budget.tokens !== undefined && (
                <td className="small">
                  {fmtNumber(Number(c.consumed_total_tokens) || 0)} / {fmtNumber(c.allowed_total_tokens)}
                  {c.extra_total_tokens ? <span className="faint"> (+{fmtNumber(c.extra_total_tokens)} extra)</span> : null}
                </td>
              )}
              {budget.extensions_enabled && (
                <td className="small">
                  {c.extensions_taken || 0} / {c.extensions_max !== undefined ? c.extensions_max : budget.max_extensions}
                </td>
              )}
              {write && (
                <td className="actions">
                  <div className="row" style={{ justifyContent: 'flex-end' }}>
                    {c.extensions_max !== undefined && (
                      <button
                        className="btn sm"
                        disabled={extensionsLeft(c) === 0}
                        title={extensionsLeft(c) === 0 ? 'No extension left for this window' : `Extend the limit of ${consumer} by ${extensionLabel(c.extension, fmtCost, fmtNumber)}`}
                        onClick={() =>
                          act('budgets.extend', consumer, 'Extension taken', {
                            title: `Extend the limit of ${consumer}?`,
                            message: `${extensionLabel(c.extension, fmtCost, fmtNumber)} for the current window. ${extensionsLeft(c) - 1} extension(s) will be left.`,
                            confirmLabel: 'Extend',
                          })
                        }
                      >
                        Extend
                      </button>
                    )}
                    {(c.extra_total_usd || c.extra_total_tokens) && (
                      <button
                        className="btn sm ghost"
                        onClick={() =>
                          act('budgets.unextend', consumer, 'Extensions cancelled', {
                            title: `Cancel the extensions of ${consumer}?`,
                            message: 'The limit goes back to the one of the budget. The extensions taken are not given back.',
                            confirmLabel: 'Cancel extensions',
                            danger: true,
                          })
                        }
                      >
                        Cancel extensions
                      </button>
                    )}
                    <button
                      className="btn sm ghost"
                      onClick={() =>
                        act('budgets.reset', consumer, 'Window reset', {
                          title: `Reset the window of ${consumer}?`,
                          message: 'Their consumption and their extensions start over.',
                          confirmLabel: 'Reset',
                        })
                      }
                    >
                      Reset
                    </button>
                  </div>
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function CreditsPage() {
  const { workspace } = useWorkspace();
  const write = useCan()('config:write');
  const toast = useToast();
  const confirm = useConfirm();
  const [editing, setEditing] = useState(null);
  const [opened, setOpened] = useState(null);
  // every budget with the consumption of its current window, in one call
  const data = useAsync(async () => {
    const [budgets, keys] = await Promise.all([backend.run('budgets.list', workspace.id, { query: { consumption: true } }), backend.run('keys.list', workspace.id)]);
    return { budgets: budgets.map((b) => ({ budget: b, consumption: b.consumption || null })).sort((a, b) => a.budget.name.localeCompare(b.budget.name)), keys };
  }, [workspace.id]);
  const list = (data.data && data.data.budgets) || [];
  const keys = (data.data && data.data.keys) || [];

  const scopeLabel = (b) => {
    const ids = b.apikeys || [];
    const users = b.users || [];
    const models = b.models || [];
    const consumers = [
      ids.length ? `${ids.length} key${ids.length > 1 ? 's' : ''}: ${ids.map((id) => (keys.find((k) => k.client_id === id) || { name: id }).name).join(', ')}` : null,
      users.length ? `${users.length} user${users.length > 1 ? 's' : ''}: ${users.join(', ')}` : null,
    ].filter(Boolean);
    const rules = extraRulesOf(b);
    const who = consumers.length ? consumers.join(' and ') : 'every call of the workspace';
    const apart = isByConsumer(b) ? ', each person having the whole limit' : isCustomPartition(b) ? ', counted apart by an expression of the admin console' : '';
    return `Applies to ${who}${apart}${models.length ? `, models ${models.join(', ')}` : ''}${rules.length ? `, ${rules.length} extra condition${rules.length > 1 ? 's' : ''}` : ''}, ${periodLabel(b)}.`;
  };

  const extensionsRule = (b) =>
    b.extensions_enabled
      ? `Extensions: ${extensionLabel({ total_usd: b.extension_usd, total_tokens: b.extension_tokens }, fmtCost, fmtNumber)}, ${b.max_extensions === 1 ? 'once' : `${b.max_extensions} times`} per window${isByConsumer(b) ? ', taken by each person for themselves' : ''}.`
      : null;

  const extend = (b, c) => {
    confirm({
      title: `Extend ${b.name}?`,
      message: `${extensionLabel(c.extension, fmtCost, fmtNumber)} for the current window. ${extensionsLeft(c) - 1} extension(s) will be left.`,
      confirmLabel: 'Extend',
    }).then((ok) => {
      if (!ok) return;
      backend
        .run('budgets.extend', workspace.id, { bid: b.id })
        .then(() => {
          toast.success('Extension taken');
          data.reload();
        })
        .catch(toast.error);
    });
  };

  const reset = (b) => {
    backend
      .run('budgets.reset', workspace.id, { bid: b.id })
      .then(() => {
        toast.success('Window reset');
        data.reload();
      })
      .catch(toast.error);
  };

  const remove = (b) => {
    confirm({ title: `Delete ${b.name}?`, message: 'Requests will no longer be limited by this budget.', danger: true, confirmLabel: 'Delete' }).then((ok) => {
      if (!ok) return;
      backend
        .run('budgets.delete', workspace.id, { bid: b.id })
        .then(() => {
          toast.success('Budget deleted');
          data.reload();
        })
        .catch(toast.error);
    });
  };

  return (
    <div className="content">
      <PageHeader title="Credits" description="Spending budgets enforced by the gateway. Each budget tracks its own window; totals are not summed across budgets.">
        {write && (
          <button className="btn" disabled={keys.length === 0} onClick={() => setEditing({ apikey: keys[0] && keys[0].client_id })}>
            Budget for an API key
          </button>
        )}
        {write && (
          <button className="btn primary" onClick={() => setEditing({})}>
            Add budget
          </button>
        )}
      </PageHeader>
      <ErrorAlert error={data.error} />
      {data.loading && !data.data && <Loading />}
      {data.data && list.length === 0 && (
        <div className="card">
          <Empty
            title="No budget yet"
            action={
              write ? (
                <button className="btn primary" onClick={() => setEditing({})}>
                  Add a budget
                </button>
              ) : null
            }
          >
            Cap the spend or the tokens of the whole workspace, or of some keys.
          </Empty>
        </div>
      )}
      <div className="grid cols-2">
        {list.map(({ budget: b, consumption: c }) => {
          const usd = c ? Number(c.consumed_total_usd) || 0 : 0;
          const tokens = c ? Number(c.consumed_total_tokens) || 0 : 0;
          // the limits with the extensions taken in the window
          const limitUsd = c && c.allowed_total_usd !== undefined ? c.allowed_total_usd : b.usd;
          const limitTokens = c && c.allowed_total_tokens !== undefined ? c.allowed_total_tokens : b.tokens;
          const apart = isByConsumer(b) || isCustomPartition(b);
          return (
            <div key={b.id} className={`card stack ${opened === b.id ? 'span-2' : ''}`}>
              <div className="card-title" style={{ marginBottom: 0 }}>
                <h2 className="truncate">{b.name}</h2>
                <div className="badges">
                  {isByConsumer(b) && <Badge kind="accent">By consumer</Badge>}
                  {isCustomPartition(b) && <Badge kind="accent">Counted apart</Badge>}
                  {b.enabled ? <Badge kind="positive">Active</Badge> : <Badge>Disabled</Badge>}
                  {b.mode === 'soft' ? <Badge kind="warning">Alerts only</Badge> : <Badge kind="negative">Blocks</Badge>}
                </div>
              </div>
              <p className="muted small">{scopeLabel(b)}</p>
              {extensionsRule(b) && <p className="muted small">{extensionsRule(b)}</p>}
              {apart ? (
                <p className="small">
                  {b.consumers_count || 0} consumer{b.consumers_count === 1 ? '' : 's'} this window, limit {[b.usd !== null && b.usd !== undefined ? fmtCost(b.usd) : null, b.tokens ? `${fmtNumber(b.tokens)} tokens` : null].filter(Boolean).join(' and ')} each.
                </p>
              ) : (
                <>
                  <Usage label="total spend" consumed={usd} allowed={limitUsd} format={fmtCost} extra={c && c.extra_total_usd} />
                  <Usage label="total tokens" consumed={tokens} allowed={limitTokens} format={fmtNumber} extra={c && c.extra_total_tokens} />
                </>
              )}
              {apart && opened === b.id && <Consumers workspace={workspace} budget={b} write={write} onChange={data.reload} />}
              <div className="row">
                {write && (
                  <button className="btn sm" onClick={() => setEditing({ budget: b })}>
                    Edit
                  </button>
                )}
                {apart && (
                  <button className="btn sm" onClick={() => setOpened(opened === b.id ? null : b.id)}>
                    {opened === b.id ? 'Hide consumers' : 'View consumers'}
                  </button>
                )}
                {write && !apart && c && c.extensions_max !== undefined && (
                  <button className="btn sm" disabled={extensionsLeft(c) === 0} title={extensionsLeft(c) === 0 ? 'No extension left for this window' : null} onClick={() => extend(b, c)}>
                    Extend
                  </button>
                )}
                {write && (
                  <button className="btn sm" onClick={() => reset(b)}>
                    Reset window
                  </button>
                )}
                <div className="grow" />
                {write && (
                  <button className="btn sm ghost" onClick={() => remove(b)}>
                    Delete
                  </button>
                )}
              </div>
            </div>
          );
        })}
      </div>
      {editing && (
        <BudgetModal
          workspace={workspace}
          budget={editing.budget}
          apikey={editing.apikey}
          keys={keys}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            data.reload();
          }}
        />
      )}
    </div>
  );
}
