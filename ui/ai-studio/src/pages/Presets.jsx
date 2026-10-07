import { useState } from 'react';
import { useWorkspace } from '../App';
import { Checks, CopyButton, Empty, ErrorAlert, Field, Loading, Modal, PageHeader, TextInput, useAsync, useConfirm, useToast } from '../components/ui';
import { backend } from '../lib/backend';

function PresetModal({ workspace, preset, providers, onClose, onSaved }) {
  const toast = useToast();
  const [form, setForm] = useState({
    name: preset ? preset.name : '',
    description: preset ? preset.description : '',
    system: preset ? preset.system : '',
    trailing: preset ? preset.trailing : '',
    providers: preset ? preset.providers : providers.map((p) => p.id),
  });
  const [saving, setSaving] = useState(false);
  const set = (patch) => setForm((f) => ({ ...f, ...patch }));

  // the api attaches the preset to the providers picked here, and serves it on the workspace route
  const save = async () => {
    setSaving(true);
    try {
      if (preset) await backend.run('presets.update', workspace.id, { pid: preset.id, body: form });
      else await backend.run('presets.create', workspace.id, { body: form });
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
  // the text providers a preset can be attached to, load balancers and routers included
  const data = useAsync(async () => {
    const [presets, entities] = await Promise.all([backend.run('presets.list', workspace.id), backend.run('modelEntities.list', workspace.id)]);
    return { presets: presets.sort((a, b) => a.name.localeCompare(b.name)), providers: entities.filter((e) => e.modality === 'text') };
  }, [workspace.id]);
  const presets = (data.data && data.data.presets) || [];
  const providers = (data.data && data.data.providers) || [];

  const remove = (preset) => {
    confirm({ title: `Delete ${preset.name}?`, message: 'Requests using this preset will no longer get its messages.', danger: true, confirmLabel: 'Delete' }).then(async (ok) => {
      if (!ok) return;
      try {
        await backend.run('presets.delete', workspace.id, { pid: preset.id });
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
          const attached = providers.filter((pr) => p.providers.includes(pr.id));
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
              {p.system && (
                <div className="preset-prompt">
                  <span className="label">System prompt</span>
                  <div className="clamp">{p.system}</div>
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
