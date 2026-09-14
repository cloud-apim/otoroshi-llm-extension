import { useState } from 'react';
import { useWorkspace } from '../App';
import { Checks, CopyButton, Empty, ErrorAlert, Field, Loading, Modal, PageHeader, TextInput, useAsync, useConfirm, useToast } from '../components/ui';
import { Resources, randomId, workspaceFilter } from '../lib/entities';
import { syncWorkspaceRefs, workspaceLocation, workspaceMetadata } from '../lib/workspaces';

function messageOf(list, role) {
  const m = (list || []).find((x) => x.role === role);
  return m ? m.content : '';
}

async function attachToProviders(providers, presetId, selectedIds) {
  for (const p of providers) {
    const contexts = (p.context && p.context.contexts) || [];
    const has = contexts.includes(presetId);
    const wants = selectedIds.includes(p.id);
    if (has === wants) continue;
    const current = p.context || {};
    const next = wants ? [...contexts, presetId] : contexts.filter((c) => c !== presetId);
    const def = !wants && current.default === presetId ? null : current.default || null;
    await Resources.providers.update({ ...p, context: { ...current, default: def, contexts: next } });
  }
}

function PresetModal({ workspace, preset, providers, onClose, onSaved }) {
  const toast = useToast();
  const [form, setForm] = useState({
    name: preset ? preset.name : '',
    description: preset ? preset.description : '',
    system: preset ? messageOf(preset.pre_messages, 'system') : '',
    trailing: preset ? messageOf(preset.post_messages, 'user') : '',
    providers: preset ? providers.filter((p) => ((p.context && p.context.contexts) || []).includes(preset.id)).map((p) => p.id) : providers.map((p) => p.id),
  });
  const [saving, setSaving] = useState(false);
  const set = (patch) => setForm((f) => ({ ...f, ...patch }));

  const save = async () => {
    setSaving(true);
    try {
      const entity = {
        ...(preset || {}),
        _loc: (preset && preset._loc) || workspaceLocation(workspace.id),
        id: (preset && preset.id) || `context_ais_${randomId(20)}`,
        name: form.name,
        description: form.description,
        tags: (preset && preset.tags) || [],
        metadata: { ...((preset && preset.metadata) || {}), ...workspaceMetadata(workspace.id, 'context') },
        pre_messages: form.system ? [{ role: 'system', content: form.system }] : [],
        post_messages: form.trailing ? [{ role: 'user', content: form.trailing }] : [],
      };
      if (preset) await Resources.contexts.update(entity);
      else await Resources.contexts.create(entity);
      await attachToProviders(providers, entity.id, form.providers);
      await syncWorkspaceRefs(workspace.id);
      toast.success(preset ? 'Preset saved' : 'Preset created');
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
      title={preset ? `Edit ${preset.name}` : 'New Preset'}
      size="wide"
      footer={
        <>
          <button className="btn" onClick={onClose}>
            Cancel
          </button>
          <button className="btn primary" disabled={!form.name.trim() || saving} onClick={save}>
            {saving ? 'Saving…' : preset ? 'Save' : 'Create'}
          </button>
        </>
      }
    >
      <div className="form-grid">
        <Field label="Name" hint="Referenced by the `context` field of API requests.">
          <TextInput value={form.name} onChange={(v) => set({ name: v.replace(/\s+/g, '-') })} placeholder="support-assistant" />
        </Field>
        <Field label="Description">
          <TextInput value={form.description} onChange={(v) => set({ description: v })} />
        </Field>
      </div>
      <Field label="System prompt" hint="Prepended to every conversation.">
        <textarea rows={6} value={form.system} placeholder="You are a concise assistant…" onChange={(e) => set({ system: e.target.value })} />
      </Field>
      <Field label="Trailing instruction" hint="Optional user message appended after the conversation (e.g. output format reminders).">
        <textarea rows={3} value={form.trailing} onChange={(e) => set({ trailing: e.target.value })} />
      </Field>
      <Field label="Available on providers" hint="Only text providers attached to a preset can apply it.">
        {providers.length === 0 ? <span className="muted">No text provider in this workspace.</span> : <Checks options={providers.map((p) => ({ value: p.id, label: p.name }))} value={form.providers} onChange={(v) => set({ providers: v })} />}
      </Field>
    </Modal>
  );
}

export function PresetsPage() {
  const { workspace } = useWorkspace();
  const toast = useToast();
  const confirm = useConfirm();
  const [editing, setEditing] = useState(null);
  const data = useAsync(async () => {
    const filter = workspaceFilter(workspace.id);
    const [presets, providers] = await Promise.all([Resources.contexts.list(filter), Resources.providers.list(filter)]);
    return { presets: presets.sort((a, b) => a.name.localeCompare(b.name)), providers };
  }, [workspace.id]);
  const presets = (data.data && data.data.presets) || [];
  const providers = (data.data && data.data.providers) || [];

  const remove = (preset) => {
    confirm({ title: `Delete ${preset.name}?`, message: 'Requests using this preset will no longer get its messages.', danger: true, confirmLabel: 'Delete' }).then(async (ok) => {
      if (!ok) return;
      try {
        await attachToProviders(providers, preset.id, []);
        await Resources.contexts.delete(preset.id);
        await syncWorkspaceRefs(workspace.id);
        toast.success('Preset deleted');
        data.reload();
      } catch (e) {
        toast.error(e);
      }
    });
  };

  return (
    <div className="content">
      <PageHeader title="Presets" description="Reusable system prompts and framing messages. Select a preset in the chat, or reference it with the `context` field in API requests.">
        <button className="btn primary" onClick={() => setEditing({})}>
          New Preset
        </button>
      </PageHeader>
      <ErrorAlert error={data.error} />
      {data.loading && !data.data && <Loading />}
      {data.data && presets.length === 0 && (
        <div className="card">
          <Empty
            title="No preset yet"
            action={
              <button className="btn primary" onClick={() => setEditing({})}>
                Create a preset
              </button>
            }
          >
            Presets let every app of the workspace share the same instructions.
          </Empty>
        </div>
      )}
      <div className="grid cols-2">
        {presets.map((p) => {
          const attached = providers.filter((pr) => ((pr.context && pr.context.contexts) || []).includes(p.id));
          const snippet = `"context": "${p.name}"`;
          return (
            <div key={p.id} className="card stack">
              <div className="card-title" style={{ marginBottom: 0 }}>
                <div>
                  <h2>{p.name}</h2>
                  {p.description && <p className="muted">{p.description}</p>}
                </div>
                <div className="row">
                  <button className="btn sm" onClick={() => setEditing({ preset: p })}>
                    Edit
                  </button>
                  <button className="btn sm ghost" onClick={() => remove(p)}>
                    Delete
                  </button>
                </div>
              </div>
              {messageOf(p.pre_messages, 'system') && (
                <div className="preset-prompt">
                  <span className="label">System prompt</span>
                  <div className="clamp">{messageOf(p.pre_messages, 'system')}</div>
                </div>
              )}
              <dl className="kv" style={{ marginTop: 0 }}>
                <dt>Providers</dt>
                <dd>{attached.length ? attached.map((a) => a.name).join(', ') : <span className="muted">none</span>}</dd>
                <dt>API reference</dt>
                <dd className="row">
                  <code>{snippet}</code>
                  <CopyButton text={snippet} />
                </dd>
              </dl>
            </div>
          );
        })}
      </div>
      {editing && (
        <PresetModal
          workspace={workspace}
          preset={editing.preset}
          providers={providers}
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
