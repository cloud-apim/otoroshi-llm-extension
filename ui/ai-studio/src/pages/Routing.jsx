import { useState } from 'react';
import { useWorkspace } from '../App';
import { Badge, Checks, CopyButton, Empty, ErrorAlert, Field, LinesInput, Loading, Modal, NumberInput, PageHeader, Segmented, Select, TextInput, useAsync, useConfirm, useToast } from '../components/ui';
import { Icon } from '../components/icons';
import { connectionName } from '../lib/connections';
import { META, Resources, randomId, workspaceFilter } from '../lib/entities';
import { Link } from '../lib/router';
import { findPlugin, OPENAI_COMPAT_PLUGIN, routeIdOf, syncWorkspaceRefs, updateWorkspaceRoute, setOpenAiConfig, workspaceLocation, workspaceMetadata } from '../lib/workspaces';

export const VIRTUAL_KINDS = ['loadbalancer', 'otoroshi'];

function chainOf(provider, byId) {
  const chain = [provider.name];
  const seen = new Set([provider.id]);
  let current = provider;
  while (current && current.provider_fallback && byId[current.provider_fallback] && !seen.has(current.provider_fallback)) {
    current = byId[current.provider_fallback];
    seen.add(current.id);
    chain.push(current.name);
  }
  return chain;
}

function LoadBalancerModal({ workspace, balancer, providers, existingNames, onClose, onSaved }) {
  const toast = useToast();
  const refs = (balancer && balancer.options && balancer.options.refs) || [];
  const [form, setForm] = useState({
    name: balancer ? balancer.name : 'balanced',
    strategy: (balancer && balancer.options && balancer.options.loadbalancing) || 'round_robin',
    targets: refs.length ? refs.map((r) => (typeof r === 'string' ? { ref: r, weight: 1, model: '' } : { ref: r.ref, weight: r.weight || 1, model: r.model || '' })) : providers.slice(0, 2).map((p) => ({ ref: p.id, weight: 1, model: '' })),
  });
  const [saving, setSaving] = useState(false);
  const set = (patch) => setForm((f) => ({ ...f, ...patch }));
  const nameTaken = existingNames.includes(form.name) && (!balancer || balancer.name !== form.name);

  const save = async () => {
    setSaving(true);
    try {
      const id = (balancer && balancer.id) || `provider_ais_${randomId(20)}`;
      const entity = {
        models: { include: [], exclude: [] },
        guardrails: [],
        guardrails_fail_on_deny: false,
        ...(balancer || {}),
        _loc: (balancer && balancer._loc) || workspaceLocation(workspace.id),
        id,
        name: form.name,
        description: 'Load balancer',
        tags: (balancer && balancer.tags) || [],
        metadata: { ...((balancer && balancer.metadata) || {}), ...workspaceMetadata(workspace.id, 'provider', { [META.connection]: id }) },
        provider: 'loadbalancer',
        connection: {},
        options: { ...((balancer && balancer.options) || {}), refs: form.targets.filter((t) => t.ref).map((t) => ({ ref: t.ref, weight: Number(t.weight) || 1, ...(t.model && t.model.trim() ? { model: t.model.trim() } : {}) })), loadbalancing: form.strategy },
      };
      if (balancer) await Resources.providers.update(entity);
      else await Resources.providers.create(entity);
      await syncWorkspaceRefs(workspace.id);
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
              <Select className="grow" value={t.ref} onChange={(v) => set({ targets: form.targets.map((x, i) => (i === idx ? { ...x, ref: v } : x)) })} placeholder="Select a provider" options={providers.map((p) => ({ value: p.id, label: `${p.name} (${(p.options || {}).model || 'default'})` }))} />
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

// the selected candidates, keeping the entries (and their model) already configured for them
const keepEntries = (previous, ids) => ids.map((id) => (previous || []).find((r) => typeof r === 'object' && r && r.ref === id) || id);

export const configuredModes = (router) => ROUTER_MODES.filter((m) => refsOf((router.options || {})[m.refs]).length > 0);

// the candidates of the intent router as the form edits them: a provider, the model it serves, what it is good at
const describedOf = (value) => (value || []).map((r) => (typeof r === 'string' ? { ref: r, model: '', description: '' } : { ...r, ref: r.ref || '', model: r.model || '', description: r.description || '' }));

function RouterModal({ workspace, router, providers, decisionModels, existingNames, onClose, onSaved }) {
  const toast = useToast();
  const o = (router && router.options) || {};
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
  const setIntent = (idx, patch) => set({ intent_router_refs: form.intent_router_refs.map((c, i) => (i === idx ? { ...c, ...patch } : c)) });
  const candidateOptions = providers.map((p) => ({ value: p.id, label: `${p.name} (${(p.options || {}).model || 'default model'})` }));
  const providerOptions = providers.map((p) => ({ value: p.id, label: p.name }));

  const save = async () => {
    setSaving(true);
    try {
      const id = (router && router.id) || `provider_ais_${randomId(20)}`;
      const num = (v, def) => (v === null || v === '' || Number.isNaN(Number(v)) ? def : Number(v));
      const entity = {
        models: { include: [], exclude: [] },
        guardrails: [],
        guardrails_fail_on_deny: false,
        ...(router || {}),
        _loc: (router && router._loc) || workspaceLocation(workspace.id),
        id,
        name: form.name,
        description: 'Otoroshi router',
        tags: (router && router.tags) || [],
        metadata: { ...((router && router.metadata) || {}), ...workspaceMetadata(workspace.id, 'provider', { [META.connection]: id }) },
        provider: 'otoroshi',
        connection: {},
        options: {
          ...o,
          code_router_refs: keepEntries(o.code_router_refs, form.code_router_refs),
          min_coding_score: Math.min(1, Math.max(0, num(form.min_coding_score, 0.5))),
          auto_router_refs: keepEntries(o.auto_router_refs, form.auto_router_refs),
          auto_router_classifier_ref: form.auto_router_classifier_ref || null,
          cost_quality_tradeoff: Math.min(10, Math.max(0, num(form.cost_quality_tradeoff, 7))),
          allowed_models: form.allowed_models.map((m) => m.trim()).filter(Boolean),
          decision_model_ref: form.decision_model_ref || null,
          smart_router_refs: keepEntries(o.smart_router_refs, form.smart_router_refs),
          smart_router_min_score: Math.min(1, Math.max(0, num(form.smart_router_min_score, 0))),
          smart_router_max_score: Math.min(1, Math.max(0, num(form.smart_router_max_score, 1))),
          intent_router_refs: form.intent_router_refs
            .filter((c) => c.ref)
            .map((c) => ({ ...c, model: (c.model || '').trim() || undefined, description: (c.description || '').trim() || undefined })),
          intent_router_instructions: form.intent_router_instructions.trim() || null,
          intent_router_min_confidence: form.intent_router_min_confidence === '' || form.intent_router_min_confidence === null ? null : Math.min(1, Math.max(0, num(form.intent_router_min_confidence, 0))),
          fusion_router_refs: keepEntries(o.fusion_router_refs, form.fusion_router_refs.slice(0, 8)),
          fusion_router_judge_ref: form.fusion_router_judge_ref || null,
          fusion_router_synthesizer_ref: form.fusion_router_synthesizer_ref || null,
        },
      };
      if (router) await Resources.providers.update(entity);
      else await Resources.providers.create(entity);
      await syncWorkspaceRefs(workspace.id);
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
          <Field label="Decision model" hint="Rates how demanding each request is, from trivial to expert. Without it, every request is of average difficulty.">
            <Select value={form.decision_model_ref} onChange={(v) => set({ decision_model_ref: v })} placeholder={decisionOptions.length ? 'Select a decision model' : 'No decision model in this workspace'} options={decisionOptions} />
          </Field>
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
          <div className="form-grid">
            <Field label="Decision model" hint="Picks the candidate whose description fits each request.">
              <Select value={form.decision_model_ref} onChange={(v) => set({ decision_model_ref: v })} placeholder={decisionOptions.length ? 'Select a decision model' : 'No decision model in this workspace'} options={decisionOptions} />
            </Field>
            <Field label="Minimum confidence" hint="From 0 to 1, optional. Below it, the first candidate answers.">
              <NumberInput value={form.intent_router_min_confidence} onChange={(v) => set({ intent_router_min_confidence: v === null ? '' : v })} min="0" max="1" step="0.05" />
            </Field>
          </div>
          <Field label="Question" hint="Optional: the question asked to the decision model about each request.">
            <TextInput value={form.intent_router_instructions} onChange={(v) => set({ intent_router_instructions: v })} placeholder="Which of these options is the best suited to answer this request ?" />
          </Field>
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
  const toast = useToast();
  const confirm = useConfirm();
  const [editing, setEditing] = useState(null);
  const [editingRouter, setEditingRouter] = useState(null);
  const data = useAsync(async () => {
    const [providers, route, decisionModels] = await Promise.all([
      Resources.providers.list(workspaceFilter(workspace.id)),
      Resources.routes.get(routeIdOf(workspace.id)),
      Resources.decisionModels.list(workspaceFilter(workspace.id)),
    ]);
    return { providers, route, decisionModels };
  }, [workspace.id]);

  const all = (data.data && data.data.providers) || [];
  const real = all.filter((p) => !VIRTUAL_KINDS.includes(p.provider));
  const balancers = all.filter((p) => p.provider === 'loadbalancer');
  const routers = all.filter((p) => p.provider === 'otoroshi');
  const byId = Object.fromEntries(all.map((p) => [p.id, p]));
  const refs = data.data ? ((findPlugin(data.data.route, OPENAI_COMPAT_PLUGIN) || { config: {} }).config.language_model_refs || []) : [];
  const ordered = refs.map((id) => byId[id]).filter(Boolean);
  const defaultProvider = ordered[0];

  const setFallback = (provider, fallback) => {
    Resources.providers
      .update({ ...provider, provider_fallback: fallback || null })
      .then(() => {
        toast.success('Fallback saved');
        data.reload();
      })
      .catch(toast.error);
  };

  const makeDefault = (provider) => {
    updateWorkspaceRoute(workspace.id, (route) => {
      const current = (findPlugin(route, OPENAI_COMPAT_PLUGIN) || { config: {} }).config.language_model_refs || [];
      return setOpenAiConfig(route, { language_model_refs: [provider.id, ...current.filter((id) => id !== provider.id)] });
    })
      .then(() => {
        toast.success(`${provider.name} is now the default provider`);
        data.reload();
      })
      .catch(toast.error);
  };

  const removeVirtual = (b, label) => {
    confirm({ title: `Delete ${b.name}?`, message: `Requests using ${b.name}/… will fail.`, danger: true, confirmLabel: 'Delete' }).then((ok) => {
      if (!ok) return;
      Resources.providers
        .delete(b.id)
        .then(() => syncWorkspaceRefs(workspace.id))
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
                          {p.name} <span className="faint small">({p.provider})</span>
                        </td>
                        <td className="mono">{(p.options || {}).model || '—'}</td>
                        <td>
                          <Select className="sm" value={p.provider_fallback || ''} onChange={(v) => setFallback(p, v)} placeholder="None" options={all.filter((o) => o.id !== p.id).map((o) => ({ value: o.id, label: o.name }))} />
                        </td>
                        <td>
                          <div className="badges">
                            {chainOf(p, byId).map((n, i) => (
                              <Badge key={i} kind="accent">
                                {n}
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
              <button className="btn sm primary" disabled={real.length === 0} onClick={() => setEditing({})}>
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
                        <td className="muted">{((b.options || {}).loadbalancing || 'round_robin').replace(/_/g, ' ')}</td>
                        <td>
                          <div className="badges">
                            {((b.options || {}).refs || []).map((r, i) => {
                              const ref = typeof r === 'string' ? r : r.ref;
                              return (
                                <Badge key={i} kind="accent">
                                  {(byId[ref] || { name: ref }).name}
                                  {typeof r === 'object' && r.model ? ` · ${r.model}` : ''}
                                  {typeof r === 'object' && r.weight > 1 ? ` ×${r.weight}` : ''}
                                </Badge>
                              );
                            })}
                          </div>
                        </td>
                        <td>
                          <span className="row">
                            <code>{b.name}/_default</code>
                            <CopyButton text={`${b.name}/_default`} />
                          </span>
                        </td>
                        <td className="actions">
                          <button className="btn sm" onClick={() => setEditing({ balancer: b })}>
                            Edit
                          </button>
                          <button className="btn sm ghost" onClick={() => removeVirtual(b, 'Load balancer')}>
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
              <button className="btn sm primary" disabled={real.length === 0} onClick={() => setEditingRouter({})}>
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
                      const ids = [...new Set(modes.flatMap((m) => ((r.options || {})[m.refs] || []).map((x) => (typeof x === 'string' ? x : x.ref))))];
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
                            <button className="btn sm" onClick={() => setEditingRouter({ router: r })}>
                              Edit
                            </button>
                            <button className="btn sm ghost" onClick={() => removeVirtual(r, 'Router')}>
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
                <Field label="Provider" hint={`Serves requests with ${(defaultProvider.options || {}).model ? `\`${defaultProvider.options.model}\` when they do not ask for a model` : 'its default model when they do not ask for a model'}.`}>
                  <Select
                    value={defaultProvider.id}
                    onChange={(v) => v !== defaultProvider.id && byId[v] && makeDefault(byId[v])}
                    options={ordered.map((p) => ({ value: p.id, label: `${p.name} (${p.provider === 'loadbalancer' ? 'load balancer' : p.provider === 'otoroshi' ? 'router' : (p.options || {}).model || 'default model'})` }))}
                  />
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
