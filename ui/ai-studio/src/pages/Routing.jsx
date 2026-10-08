import { useState } from 'react';
import { useCan, useWorkspace } from '../App';
import { Badge, Checks, CopyButton, Empty, ErrorAlert, Field, LinesInput, Loading, Modal, NumberInput, PageHeader, Segmented, Select, TextInput, useAsync, useConfirm, useToast } from '../components/ui';
import { Icon } from '../components/icons';
import { backend } from '../lib/backend';
import { connectionName } from '../lib/connections';
import { Link } from '../lib/router';

// The routing of a workspace, as the studio api shows it (`routingJson`, `balancerJson` and `routerJson` in
// studio/api.scala): the real providers with their fallback chain, the load balancers and the routers, and the
// order in which the route serves them (the first one answers the requests naming no provider).

function LoadBalancerModal({ workspace, balancer, providers, existingNames, onClose, onSaved }) {
  const toast = useToast();
  const [form, setForm] = useState({
    name: balancer ? balancer.name : 'balanced',
    strategy: (balancer && balancer.strategy) || 'round_robin',
    targets: balancer ? balancer.targets.map((t) => ({ ref: t.ref, weight: t.weight || 1, model: t.model || '' })) : providers.slice(0, 2).map((p) => ({ ref: p.id, weight: 1, model: '' })),
  });
  const [saving, setSaving] = useState(false);
  const set = (patch) => setForm((f) => ({ ...f, ...patch }));
  const nameTaken = existingNames.includes(form.name) && (!balancer || balancer.name !== form.name);

  // the api serves the balancer on the workspace route as soon as it is saved
  const save = async () => {
    setSaving(true);
    try {
      const body = {
        name: form.name,
        strategy: form.strategy,
        targets: form.targets.filter((t) => t.ref).map((t) => ({ ref: t.ref, weight: Number(t.weight) || 1, model: (t.model || '').trim() || null })),
      };
      if (balancer) await backend.run('balancers.update', workspace.id, { lid: balancer.id, body });
      else await backend.run('balancers.create', workspace.id, { body });
      toast.success(balancer ? 'Load balancer saved' : 'Load balancer created');
      onSaved();
    } catch (e) {
      toast.error(e);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal
      open
      onClose={onClose}
      title={balancer ? `Edit ${balancer.name}` : 'New load balancer'}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button className="btn primary" disabled={!form.name || nameTaken || form.targets.filter((t) => t.ref).length < 1 || saving} onClick={save}>
            {saving ? 'Saving…' : balancer ? 'Save' : 'Create'}
          </button>
        </>
      }
    >
      <div className="form-grid">
        <Field label="Name" hint="Call it with the model `<name>/_default`." error={nameTaken ? 'already used in this workspace' : null}>
          <TextInput value={form.name} onChange={(v) => set({ name: connectionName(v) })} />
        </Field>
        <Field label="Strategy">
          <Select
            value={form.strategy}
            onChange={(v) => set({ strategy: v })}
            options={[
              { value: 'round_robin', label: 'Round robin (weighted)' },
              { value: 'random', label: 'Random' },
              { value: 'best_response_time', label: 'Best response time' },
            ]}
          />
        </Field>
      </div>
      <Field label="Targets" hint="Each target is called with the model set here, or with its own default model. A provider can be a target several times with different models.">
        <div className="stack tight">
          {form.targets.map((t, idx) => (
            <div key={idx} className="row">
              <Select className="grow" value={t.ref} onChange={(v) => set({ targets: form.targets.map((x, i) => (i === idx ? { ...x, ref: v } : x)) })} placeholder="Select a provider" options={providers.map((p) => ({ value: p.id, label: `${p.name} (${p.model || 'default'})` }))} />
              <div style={{ width: 170 }}>
                <TextInput value={t.model} onChange={(v) => set({ targets: form.targets.map((x, i) => (i === idx ? { ...x, model: v } : x)) })} placeholder="Default model" />
              </div>
              <div style={{ width: 90 }}>
                <NumberInput value={t.weight} onChange={(v) => set({ targets: form.targets.map((x, i) => (i === idx ? { ...x, weight: v } : x)) })} min="1" title="weight" />
              </div>
              <button className="copy-btn" onClick={() => set({ targets: form.targets.filter((_, i) => i !== idx) })} title="Remove">
                <Icon name="trash" />
              </button>
            </div>
          ))}
          <button className="btn sm" style={{ alignSelf: 'flex-start' }} onClick={() => set({ targets: [...form.targets, { ref: '', weight: 1, model: '' }] })}>
            <Icon name="plus" />
            Add target
          </button>
        </div>
      </Field>
    </Modal>
  );
}

// The models exposed by an Otoroshi router (provider kind `otoroshi`), with the options each one reads
export const ROUTER_MODES = [
  {
    id: 'code',
    model: 'code-router',
    label: 'Code router',
    refs: 'code_router_refs',
    description: 'Picks the cheapest candidate that still codes well enough, from a coding quality index and the price of each model.',
  },
  {
    id: 'auto',
    model: 'auto-router',
    label: 'Auto router',
    refs: 'auto_router_refs',
    description: 'A judge model reads each prompt and picks the best suited candidate, following your cost / quality tradeoff.',
  },
  {
    id: 'smart',
    model: 'smart-router',
    label: 'Smart router',
    refs: 'smart_router_refs',
    description: 'A decision model rates how demanding each request is, and the cheapest candidate that is good enough for it answers.',
  },
  {
    id: 'intent',
    model: 'intent-router',
    label: 'Intent router',
    refs: 'intent_router_refs',
    description: 'You describe what each candidate is good at, and a decision model picks the one that fits each request.',
  },
  {
    id: 'fusion',
    model: 'fusion-router',
    label: 'Fusion router',
    refs: 'fusion_router_refs',
    description: 'Asks a panel of models in parallel, a judge compares their answers and a synthesizer writes the final one.',
  },
];

const refsOf = (value) => (value || []).map((r) => (typeof r === 'string' ? r : r.ref)).filter(Boolean);

export const configuredModes = (router) => ROUTER_MODES.filter((m) => (router.modes || []).includes(m.id));

// the candidates of the intent router as the form edits them: a provider, the model it serves, what it is good at
const describedOf = (value) => (value || []).map((r) => (typeof r === 'string' ? { ref: r, model: '', description: '' } : { ...r, ref: r.ref || '', model: r.model || '', description: r.description || '' }));

function RouterModal({ workspace, router, providers, decisionModels, existingNames, onClose, onSaved }) {
  const toast = useToast();
  const o = router || {};
  const [mode, setMode] = useState((router && (configuredModes(router)[0] || {}).id) || 'auto');
  const [form, setForm] = useState({
    name: router ? router.name : 'router',
    code_router_refs: refsOf(o.code_router_refs),
    min_coding_score: o.min_coding_score ?? 0.5,
    auto_router_refs: refsOf(o.auto_router_refs),
    auto_router_classifier_ref: o.auto_router_classifier_ref || '',
    cost_quality_tradeoff: o.cost_quality_tradeoff ?? 7,
    allowed_models: o.allowed_models || [],
    decision_model_ref: o.decision_model_ref || '',
    decision_model_model: o.decision_model_model || '',
    smart_router_refs: refsOf(o.smart_router_refs),
    smart_router_min_score: o.smart_router_min_score ?? 0,
    smart_router_max_score: o.smart_router_max_score ?? 1,
    intent_router_refs: describedOf(o.intent_router_refs),
    intent_router_instructions: o.intent_router_instructions || '',
    intent_router_min_confidence: o.intent_router_min_confidence ?? '',
    fusion_router_refs: refsOf(o.fusion_router_refs),
    fusion_router_judge_ref: o.fusion_router_judge_ref || '',
    fusion_router_synthesizer_ref: o.fusion_router_synthesizer_ref || '',
  });
  const [saving, setSaving] = useState(false);
  const set = (patch) => setForm((f) => ({ ...f, ...patch }));
  const nameTaken = existingNames.includes(form.name) && (!router || router.name !== form.name);
  // a candidate of the intent router counts once it names a provider
  const countOf = (m) => form[m.refs].filter((r) => (typeof r === 'string' ? r : r.ref)).length;
  const hasCandidates = ROUTER_MODES.some((m) => countOf(m) > 0);
  const decisionOptions = (decisionModels || []).map((d) => ({ value: d.id, label: d.name }));
  const decisionDefault = ((decisionModels || []).find((d) => d.id === form.decision_model_ref) || {}).model;
  // the decision model of the smart and the intent routers, shared by both, and the model it answers with
  const decisionFields = (hint) => (
    <div className="form-grid">
      <Field label="Decision model" hint={hint}>
        <Select value={form.decision_model_ref} onChange={(v) => set({ decision_model_ref: v })} placeholder={decisionOptions.length ? 'Select a decision model' : 'No decision model in this workspace'} options={decisionOptions} />
      </Field>
      <Field label="Model" hint="Optional: the model it answers with, instead of its default one.">
        <TextInput value={form.decision_model_model} onChange={(v) => set({ decision_model_model: v })} placeholder={decisionDefault || 'Default model'} disabled={!form.decision_model_ref} />
      </Field>
    </div>
  );
  const setIntent = (idx, patch) => set({ intent_router_refs: form.intent_router_refs.map((c, i) => (i === idx ? { ...c, ...patch } : c)) });
  const candidateOptions = providers.map((p) => ({ value: p.id, label: `${p.name} (${p.model || 'default model'})` }));
  const providerOptions = providers.map((p) => ({ value: p.id, label: p.name }));

  // Candidates are sent by id: the api keeps the model already configured for each of them, and what the form
  // does not show (the models of the judges) as it is. It bounds the numbers.
  const save = async () => {
    setSaving(true);
    try {
      const num = (v, def) => (v === null || v === '' || Number.isNaN(Number(v)) ? def : Number(v));
      const body = {
        name: form.name,
        code_router_refs: form.code_router_refs,
        min_coding_score: num(form.min_coding_score, 0.5),
        auto_router_refs: form.auto_router_refs,
        auto_router_classifier_ref: form.auto_router_classifier_ref || null,
        cost_quality_tradeoff: num(form.cost_quality_tradeoff, 7),
        allowed_models: form.allowed_models.map((m) => m.trim()).filter(Boolean),
        decision_model_ref: form.decision_model_ref || null,
        decision_model_model: (form.decision_model_ref && form.decision_model_model.trim()) || null,
        smart_router_refs: form.smart_router_refs,
        smart_router_min_score: num(form.smart_router_min_score, 0),
        smart_router_max_score: num(form.smart_router_max_score, 1),
        intent_router_refs: form.intent_router_refs.filter((c) => c.ref),
        intent_router_instructions: form.intent_router_instructions.trim() || null,
        intent_router_min_confidence: form.intent_router_min_confidence === '' || form.intent_router_min_confidence === null ? null : num(form.intent_router_min_confidence, 0),
        fusion_router_refs: form.fusion_router_refs.slice(0, 8),
        fusion_router_judge_ref: form.fusion_router_judge_ref || null,
        fusion_router_synthesizer_ref: form.fusion_router_synthesizer_ref || null,
      };
      if (router) await backend.run('routers.update', workspace.id, { rid: router.id, body });
      else await backend.run('routers.create', workspace.id, { body });
      toast.success(router ? 'Router saved' : 'Router created');
      onSaved();
    } catch (e) {
      toast.error(e);
    } finally {
      setSaving(false);
    }
  };

  const current = ROUTER_MODES.find((m) => m.id === mode);
  return (
    <Modal
      open
      size="wide"
      onClose={onClose}
      title={router ? `Edit ${router.name}` : 'New router'}
      footer={
        <>
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button className="btn primary" disabled={!form.name || nameTaken || !hasCandidates || saving} onClick={save}>
            {saving ? 'Saving…' : router ? 'Save' : 'Create'}
          </button>
        </>
      }
    >
      <Field label="Name" hint="One router serves the models below: configure the ones you want to use." error={nameTaken ? 'already used in this workspace' : null}>
        <TextInput value={form.name} onChange={(v) => set({ name: connectionName(v) })} />
      </Field>
      <div className="row between wrap">
        <Segmented
          value={mode}
          onChange={setMode}
          options={ROUTER_MODES.map((m) => ({ value: m.id, label: `${m.label}${countOf(m) ? ` · ${countOf(m)}` : ''}` }))}
        />
        <span className="row">
          <code>
            {form.name || 'router'}/{current.model}
          </code>
          <CopyButton text={`${form.name || 'router'}/${current.model}`} />
        </span>
      </div>
      <p className="muted" style={{ margin: '12px 0 4px' }}>
        {current.description} When a candidate fails, the next best one is tried.
      </p>

      {mode === 'code' && (
        <>
          <Field label="Candidates" hint="Each candidate is called with its own default model. Models missing from the quality index are only used when no ranked candidate answers.">
            <Checks options={candidateOptions} value={form.code_router_refs} onChange={(v) => set({ code_router_refs: v })} />
          </Field>
          <Field label="Minimum coding quality" hint="From 0 to 1, relative to your best candidate: 0.5 accepts any model at least half as good. Callers can override it with a `min_coding_score` field.">
            <NumberInput value={form.min_coding_score} onChange={(v) => set({ min_coding_score: v })} min="0" max="1" step="0.05" />
          </Field>
        </>
      )}

      {mode === 'auto' && (
        <>
          <Field label="Candidates" hint="Each candidate is called with its own default model.">
            <Checks options={candidateOptions} value={form.auto_router_refs} onChange={(v) => set({ auto_router_refs: v })} />
          </Field>
          <div className="form-grid">
            <Field label="Judge" hint="A small, fast model is enough to route. Defaults to the cheapest candidate.">
              <Select value={form.auto_router_classifier_ref} onChange={(v) => set({ auto_router_classifier_ref: v })} placeholder="Cheapest candidate" options={providerOptions} />
            </Field>
            <Field label="Cost / quality tradeoff" hint="0 = best answer whatever the price, 10 = cheapest acceptable, 7 = balanced. Overridable with `cost_quality_tradeoff`.">
              <NumberInput value={form.cost_quality_tradeoff} onChange={(v) => set({ cost_quality_tradeoff: v })} min="0" max="10" step="1" />
            </Field>
          </div>
          <Field label="Allowed models" hint="Optional wildcard patterns matched on `<provider kind>/<model>` or the model alone, one per line (`openai/gpt-5*`, `*mini*`). Overridable with `allowed_models`.">
            <LinesInput value={form.allowed_models} onChange={(v) => set({ allowed_models: v })} rows={2} />
          </Field>
        </>
      )}

      {mode === 'smart' && (
        <>
          <Field label="Candidates" hint="Each candidate is called with its own default model. Models missing from the quality index are only used when no ranked candidate answers.">
            <Checks options={candidateOptions} value={form.smart_router_refs} onChange={(v) => set({ smart_router_refs: v })} />
          </Field>
          {decisionFields('Rates how demanding each request is, from trivial to expert. Without it, every request is of average difficulty.')}
          <div className="form-grid">
            <Field label="Quality for a trivial request" hint="From 0 to 1, relative to your best candidate. 0 lets the cheapest candidate answer trivial requests.">
              <NumberInput value={form.smart_router_min_score} onChange={(v) => set({ smart_router_min_score: v })} min="0" max="1" step="0.05" />
            </Field>
            <Field label="Quality for the most demanding request" hint="From 0 to 1, relative to your best candidate. 1 keeps the most demanding requests for your best candidate.">
              <NumberInput value={form.smart_router_max_score} onChange={(v) => set({ smart_router_max_score: v })} min="0" max="1" step="0.05" />
            </Field>
          </div>
        </>
      )}

      {mode === 'intent' && (
        <>
          <Field label="Candidates" hint="Say what each candidate is good at, the way you would brief someone doing the routing by hand. The first candidate answers when the decision model is not sure, or does not answer.">
            <div className="stack tight">
              {form.intent_router_refs.map((c, idx) => (
                <div key={idx} className="intent-candidate">
                  <div className="row">
                    <Select className="grow" value={c.ref} onChange={(v) => setIntent(idx, { ref: v })} placeholder="Select a provider" options={candidateOptions} />
                    <div style={{ width: 200 }}>
                      <TextInput value={c.model} onChange={(v) => setIntent(idx, { model: v })} placeholder="Default model" />
                    </div>
                    <button className="copy-btn" onClick={() => set({ intent_router_refs: form.intent_router_refs.filter((_, i) => i !== idx) })} title="Remove">
                      <Icon name="trash" />
                    </button>
                  </div>
                  <TextInput value={c.description} onChange={(v) => setIntent(idx, { description: v })} placeholder="What it is good at" />
                </div>
              ))}
              <button className="btn sm" style={{ alignSelf: 'flex-start' }} onClick={() => set({ intent_router_refs: [...form.intent_router_refs, { ref: '', model: '', description: '' }] })}>
                <Icon name="plus" />
                Add candidate
              </button>
            </div>
          </Field>
          {decisionFields('Picks the candidate whose description fits each request.')}
          <div className="form-grid">
            <Field label="Question" hint="Optional: the question asked to the decision model about each request.">
              <TextInput value={form.intent_router_instructions} onChange={(v) => set({ intent_router_instructions: v })} placeholder="Which of these options is the best suited to answer this request ?" />
            </Field>
            <Field label="Minimum confidence" hint="From 0 to 1, optional. Below it, the first candidate answers.">
              <NumberInput value={form.intent_router_min_confidence} onChange={(v) => set({ intent_router_min_confidence: v === null ? '' : v })} min="0" max="1" step="0.05" />
            </Field>
          </div>
        </>
      )}

      {mode === 'fusion' && (
        <>
          <Field label="Panel" hint="Up to 8 models asked in parallel. The ones that fail are left out of the deliberation.">
            <Checks options={candidateOptions} value={form.fusion_router_refs} onChange={(v) => set({ fusion_router_refs: v.slice(0, 8) })} />
          </Field>
          <div className="form-grid">
            <Field label="Judge" hint="Compares the panel answers: consensus, disagreements, unique insights, gaps. Defaults to the best panel member.">
              <Select value={form.fusion_router_judge_ref} onChange={(v) => set({ fusion_router_judge_ref: v })} placeholder="Best panel member" options={providerOptions} />
            </Field>
            <Field label="Synthesizer" hint="Writes the final answer from the analysis, streamed back to the caller. Defaults to the best panel member.">
              <Select value={form.fusion_router_synthesizer_ref} onChange={(v) => set({ fusion_router_synthesizer_ref: v })} placeholder="Best panel member" options={providerOptions} />
            </Field>
          </div>
        </>
      )}
    </Modal>
  );
}

export function RoutingPage() {
  const { workspace } = useWorkspace();
  const write = useCan()('config:write');
  const toast = useToast();
  const confirm = useConfirm();
  const [editing, setEditing] = useState(null);
  const [editingRouter, setEditingRouter] = useState(null);
  const data = useAsync(async () => {
    const [routing, entities] = await Promise.all([backend.run('routing.get', workspace.id), backend.run('modelEntities.list', workspace.id)]);
    return { routing, decisionModels: entities.filter((e) => e.modality === 'decision') };
  }, [workspace.id]);

  const routing = (data.data && data.data.routing) || { providers: [], load_balancers: [], routers: [], order: [] };
  const real = routing.providers;
  const balancers = routing.load_balancers;
  const routers = routing.routers;
  // every text provider of the workspace, virtual ones included, with what tells them apart in a list
  const all = [
    ...real.map((p) => ({ ...p, label: p.model || 'default model' })),
    ...balancers.map((b) => ({ ...b, label: 'load balancer' })),
    ...routers.map((r) => ({ ...r, label: 'router' })),
  ];
  const byId = Object.fromEntries(all.map((p) => [p.id, p]));
  const ordered = routing.order.map((id) => byId[id]).filter(Boolean);
  const defaultProvider = ordered[0];

  const setFallback = (provider, fallback) => {
    backend
      .run('routing.save', workspace.id, { body: { fallbacks: { [provider.id]: fallback || null } } })
      .then(() => {
        toast.success('Fallback saved');
        data.reload();
      })
      .catch(toast.error);
  };

  const makeDefault = (provider) => {
    backend
      .run('routing.save', workspace.id, { body: { default_provider: provider.id } })
      .then(() => {
        toast.success(`${provider.name} is now the default provider`);
        data.reload();
      })
      .catch(toast.error);
  };

  const removeVirtual = (b, label) => {
    confirm({ title: `Delete ${b.name}?`, message: `Requests using ${b.name}/… will fail.`, danger: true, confirmLabel: 'Delete' }).then((ok) => {
      if (!ok) return;
      (label === 'Router' ? backend.run('routers.delete', workspace.id, { rid: b.id }) : backend.run('balancers.delete', workspace.id, { lid: b.id }))
        .then(() => {
          toast.success(`${label} deleted`);
          data.reload();
        })
        .catch(toast.error);
    });
  };

  return (
    <div className="content">
      <PageHeader title="Routing" description="Decide which provider serves a request and what happens when a provider fails." />
      <ErrorAlert error={data.error} />
      {data.loading && !data.data && <Loading />}
      {data.data && (
        <div className="stack">
          <div className="card">
            <h2>Provider fallback</h2>
            <p className="muted" style={{ margin: '4px 0 14px' }}>
              When a provider returns an error, the gateway retries the request on its fallback provider. Chains are followed in order.
            </p>
            {real.length === 0 ? (
              <Empty>
                <Link className="link" to={`/workspaces/${workspace.id}/providers`}>
                  Connect a provider
                </Link>{' '}
                first.
              </Empty>
            ) : (
              <div className="table-wrap">
                <table className="table">
                  <thead>
                    <tr>
                      <th>Provider</th>
                      <th>Default model</th>
                      <th style={{ width: 240 }}>Falls back to</th>
                      <th>Resulting chain</th>
                    </tr>
                  </thead>
                  <tbody>
                    {real.map((p) => (
                      <tr key={p.id}>
                        <td>
                          {p.name} <span className="faint small">({p.kind})</span>
                        </td>
                        <td className="mono">{p.model || '—'}</td>
                        <td>
                          <Select className="sm" disabled={!write} value={p.fallback || ''} onChange={(v) => setFallback(p, v)} placeholder="None" options={all.filter((o) => o.id !== p.id).map((o) => ({ value: o.id, label: o.name }))} />
                        </td>
                        <td>
                          <div className="badges">
                            {p.chain.map((c, i) => (
                              <Badge key={i} kind="accent">
                                {c.name}
                              </Badge>
                            ))}
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>

          <div className="card">
            <div className="card-head">
              <div>
                <h2>Load balancing</h2>
                <p>Spread the traffic over several providers. A load balancer is exposed like a provider of the workspace.</p>
              </div>
              <button className="btn sm primary" hidden={!write} disabled={real.length === 0} onClick={() => setEditing({})}>
                New load balancer
              </button>
            </div>
            {balancers.length === 0 && <p className="muted small">No load balancer.</p>}
            {balancers.length > 0 && (
              <div className="table-wrap">
                <table className="table">
                  <thead>
                    <tr>
                      <th>Name</th>
                      <th>Strategy</th>
                      <th>Targets</th>
                      <th>Model to use</th>
                      <th />
                    </tr>
                  </thead>
                  <tbody>
                    {balancers.map((b) => (
                      <tr key={b.id}>
                        <td>{b.name}</td>
                        <td className="muted">{(b.strategy || 'round_robin').replace(/_/g, ' ')}</td>
                        <td>
                          <div className="badges">
                            {b.targets.map((t, i) => (
                              <Badge key={i} kind="accent">
                                {(byId[t.ref] || { name: t.ref }).name}
                                {t.model ? ` · ${t.model}` : ''}
                                {t.weight > 1 ? ` ×${t.weight}` : ''}
                              </Badge>
                            ))}
                          </div>
                        </td>
                        <td>
                          <span className="row">
                            <code>{b.name}/_default</code>
                            <CopyButton text={`${b.name}/_default`} />
                          </span>
                        </td>
                        <td className="actions">
                          <button className="btn sm" hidden={!write} onClick={() => setEditing({ balancer: b })}>
                            Edit
                          </button>
                          <button className="btn sm ghost" hidden={!write} onClick={() => removeVirtual(b, 'Load balancer')}>
                            Delete
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>

          <div className="card">
            <div className="card-head">
              <div>
                <h2>Smart routing</h2>
                <p>Let the gateway pick the model: the cheapest good coder, the best model for each prompt, the one a decision model picks, or a panel of models answering together.</p>
              </div>
              <button className="btn sm primary" hidden={!write} disabled={real.length === 0} onClick={() => setEditingRouter({})}>
                New router
              </button>
            </div>
            {routers.length === 0 && <p className="muted small">No router.</p>}
            {routers.length > 0 && (
              <div className="table-wrap">
                <table className="table">
                  <thead>
                    <tr>
                      <th>Name</th>
                      <th>Candidates</th>
                      <th>Models to use</th>
                      <th />
                    </tr>
                  </thead>
                  <tbody>
                    {routers.map((r) => {
                      const modes = configuredModes(r);
                      const ids = [...new Set(modes.flatMap((m) => refsOf(r[m.refs])))];
                      return (
                        <tr key={r.id}>
                          <td>{r.name}</td>
                          <td>
                            <div className="badges">
                              {ids.map((id) => (
                                <Badge key={id} kind="accent">
                                  {(byId[id] || { name: id }).name}
                                </Badge>
                              ))}
                            </div>
                          </td>
                          <td>
                            {modes.length === 0 && <span className="muted">not configured</span>}
                            <div className="stack tight">
                              {modes.map((m) => (
                                <span key={m.id} className="row">
                                  <code>
                                    {r.name}/{m.model}
                                  </code>
                                  <CopyButton text={`${r.name}/${m.model}`} />
                                </span>
                              ))}
                            </div>
                          </td>
                          <td className="actions">
                            <button className="btn sm" hidden={!write} onClick={() => setEditingRouter({ router: r })}>
                              Edit
                            </button>
                            <button className="btn sm ghost" hidden={!write} onClick={() => removeVirtual(r, 'Router')}>
                              Delete
                            </button>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </div>

          <div className="card">
            <h2>Default provider</h2>
            <p className="muted" style={{ margin: '4px 0 14px' }}>
              Requests whose model does not name a provider (no <code>provider/</code> prefix) are served by the default provider, with the model they ask for or its default model.
            </p>
            {defaultProvider ? (
              <div className="form-grid">
                <Field label="Provider" hint={`Serves requests with ${defaultProvider.model ? `\`${defaultProvider.model}\` when they do not ask for a model` : 'its default model when they do not ask for a model'}.`}>
                  <Select disabled={!write} value={defaultProvider.id} onChange={(v) => v !== defaultProvider.id && byId[v] && makeDefault(byId[v])} options={ordered.map((p) => ({ value: p.id, label: `${p.name} (${p.label})` }))} />
                </Field>
              </div>
            ) : (
              <p className="muted small">No provider served yet.</p>
            )}
          </div>
        </div>
      )}
      {editingRouter && (
        <RouterModal
          workspace={workspace}
          router={editingRouter.router}
          providers={real}
          decisionModels={(data.data && data.data.decisionModels) || []}
          existingNames={all.map((p) => p.name)}
          onClose={() => setEditingRouter(null)}
          onSaved={() => {
            setEditingRouter(null);
            data.reload();
          }}
        />
      )}
      {editing && (
        <LoadBalancerModal
          workspace={workspace}
          balancer={editing.balancer}
          providers={real}
          existingNames={all.map((p) => p.name)}
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
