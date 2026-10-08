import { useState } from 'react';
import { useCan, useWorkspace } from '../App';
import { BudgetModal } from '../components/BudgetModal';
import { Badge, Empty, ErrorAlert, Loading, PageHeader, Progress, useAsync, useConfirm, useToast } from '../components/ui';
import { backend } from '../lib/backend';
import { extraRulesOf, periodLabel } from '../lib/budgets';
import { fmtCost, fmtNumber } from '../lib/format';

export function CreditsPage() {
  const { workspace } = useWorkspace();
  const write = useCan()('config:write');
  const toast = useToast();
  const confirm = useConfirm();
  const [editing, setEditing] = useState(null);
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
    return `Applies to ${who}${models.length ? `, models ${models.join(', ')}` : ''}${rules.length ? `, ${rules.length} extra condition${rules.length > 1 ? 's' : ''}` : ''}, ${periodLabel(b)}.`;
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
          const limitUsd = b.usd;
          const limitTokens = b.tokens;
          return (
            <div key={b.id} className="card stack">
              <div className="card-title" style={{ marginBottom: 0 }}>
                <h2 className="truncate">{b.name}</h2>
                <div className="badges">
                  {b.enabled ? <Badge kind="positive">Active</Badge> : <Badge>Disabled</Badge>}
                  {b.mode === 'soft' ? <Badge kind="warning">Alerts only</Badge> : <Badge kind="negative">Blocks</Badge>}
                </div>
              </div>
              <p className="muted small">{scopeLabel(b)}</p>
              {limitUsd !== undefined && limitUsd !== null && (
                <div className="stack tight">
                  <div className="row between small">
                    <span className="muted">total spend</span>
                    <span>
                      {fmtCost(usd)} / {fmtCost(limitUsd)}
                    </span>
                  </div>
                  <Progress value={usd} max={limitUsd} />
                </div>
              )}
              {limitTokens !== undefined && limitTokens !== null && (
                <div className="stack tight">
                  <div className="row between small">
                    <span className="muted">total tokens</span>
                    <span>
                      {fmtNumber(tokens)} / {fmtNumber(limitTokens)}
                    </span>
                  </div>
                  <Progress value={tokens} max={limitTokens} />
                </div>
              )}
              <div className="row">
                {write && (
                  <button className="btn sm" onClick={() => setEditing({ budget: b })}>
                    Edit
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
