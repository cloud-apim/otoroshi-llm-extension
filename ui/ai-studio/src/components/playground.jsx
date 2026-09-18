import { useEffect, useRef, useState } from 'react';
import { Icon } from './icons';
import { CopyButton, ErrorAlert, Segmented } from './ui';
import { Markdown } from './Markdown';
import { MAX_UPLOAD_BYTES, playgroundsOf, runPlayground } from '../lib/playgrounds';
import { fmtBytes } from '../lib/attachments';
import { fmtCost, fmtInt, fmtMs } from '../lib/format';
import { costOf } from '../lib/conversations';
import { download, downloadDataUrl } from '../lib/files';

// what a run cost, when the gateway priced it
function RunMeta({ result }) {
  const usage = result.usage || {};
  const tokens = usage.total_tokens || usage.prompt_tokens || usage.input_tokens || null;
  const cost = costOf(result);
  return (
    <div className="meta small">
      <span>{fmtMs(result.duration)}</span>
      {tokens ? <span>{fmtInt(tokens)} tokens</span> : null}
      {usage.pages_processed ? <span>{fmtInt(usage.pages_processed)} page{usage.pages_processed > 1 ? 's' : ''}</span> : null}
      {cost !== null ? <span title="What the gateway billed for this call">{fmtCost(cost)}</span> : null}
    </div>
  );
}

function fileName(model, extension) {
  return `${(model || 'model').replace(/[^a-z0-9.-]+/gi, '-')}-${Date.now()}.${extension}`;
}

// the text a transcription, an extraction or a revised prompt gives back
function TextResult({ text, markdown }) {
  if (!text) return <p className="muted">The model answered with no text.</p>;
  return (
    <div className="playground-text">
      {markdown ? <Markdown text={text} /> : <pre>{text}</pre>}
      <CopyButton text={text} />
    </div>
  );
}

function Vectors({ result }) {
  const preview = (v) => `[${v.slice(0, 6).map((n) => Number(n).toFixed(4)).join(', ')}${v.length > 6 ? ', …' : ''}]`;
  return (
    <div className="stack tight">
      {result.vectors.map((v, i) => (
        <div key={i} className="playground-vector">
          <div className="row between">
            <b>{v.length} dimensions</b>
            <CopyButton text={() => JSON.stringify(v)} className="btn sm ghost" label="Copy vector" />
          </div>
          {result.inputs[i] && <div className="faint small truncate">{result.inputs[i]}</div>}
          <div className="mono small">{preview(v)}</div>
        </div>
      ))}
    </div>
  );
}

function Moderation({ moderation }) {
  if (!moderation) return <p className="muted">The model returned no result.</p>;
  const scores = moderation.category_scores || {};
  const categories = moderation.categories || {};
  const rows = Object.keys({ ...categories, ...scores })
    .map((name) => ({ name, flagged: !!categories[name], score: Number(scores[name]) || 0 }))
    .sort((a, b) => Number(b.flagged) - Number(a.flagged) || b.score - a.score)
    .slice(0, 8);
  return (
    <div className="stack tight">
      <div className={`playground-verdict ${moderation.flagged ? 'flagged' : 'clean'}`}>
        <Icon name={moderation.flagged ? 'shield' : 'check'} />
        {moderation.flagged ? 'Flagged' : 'Nothing flagged'}
      </div>
      {rows.length > 0 && (
        <table className="table compact">
          <tbody>
            {rows.map((r) => (
              <tr key={r.name}>
                <td className={r.flagged ? 'warning-text' : ''}>{r.name.replace(/[_/]/g, ' ')}</td>
                <td className="num mono">{r.score < 0.0001 && r.score > 0 ? r.score.toExponential(1) : r.score.toFixed(4)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

function DropZone({ file, accept, hint, onPick, disabled }) {
  const input = useRef(null);
  const [over, setOver] = useState(false);
  return (
    <div
      className={`dropzone ${over ? 'over' : ''} ${file ? 'filled' : ''}`}
      onDragOver={(e) => {
        e.preventDefault();
        setOver(true);
      }}
      onDragLeave={() => setOver(false)}
      onDrop={(e) => {
        e.preventDefault();
        setOver(false);
        if (!disabled && e.dataTransfer.files[0]) onPick(e.dataTransfer.files[0]);
      }}
      onClick={() => !disabled && input.current && input.current.click()}
    >
      <input
        ref={input}
        type="file"
        accept={accept}
        style={{ display: 'none' }}
        onChange={(e) => {
          if (e.target.files[0]) onPick(e.target.files[0]);
          e.target.value = '';
        }}
      />
      <Icon name="upload" size={18} />
      {file ? (
        <>
          <b className="truncate">{file.name}</b>
          <span className="faint small">{fmtBytes(file.size)} · click to pick another one</span>
        </>
      ) : (
        <>
          <b>Drop a file here</b>
          <span className="faint small">{hint} {fmtBytes(MAX_UPLOAD_BYTES)} at most.</span>
        </>
      )}
    </div>
  );
}

/**
 * A small form to try a model that is not a chat model: a prompt or a file in, what the model answers out.
 * Every run is a real call on the workspace endpoint, so it is billed and audited like any other.
 */
export function Playground({ model, workspace, providers }) {
  const kinds = playgroundsOf(model, providers);
  const [kind, setKind] = useState(kinds[0] ? kinds[0].id : null);
  const [text, setText] = useState('');
  const [file, setFile] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [result, setResult] = useState(null);
  const abort = useRef(null);
  const audioUrl = useRef(null);

  const current = kinds.find((k) => k.id === kind) || kinds[0];

  const clear = () => {
    if (audioUrl.current) URL.revokeObjectURL(audioUrl.current);
    audioUrl.current = null;
    setResult(null);
    setError(null);
  };

  // a new model, or another way of using it, starts from a blank form
  useEffect(() => {
    clear();
    setText('');
    setFile(null);
    setKind(kinds[0] ? kinds[0].id : null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [model.id, model.modality]);

  useEffect(() => () => audioUrl.current && URL.revokeObjectURL(audioUrl.current), []);

  if (!current) return null;

  const ready = current.input === 'file' ? !!file : text.trim().length > 0;

  const run = () => {
    clear();
    setBusy(true);
    const controller = new AbortController();
    abort.current = controller;
    runPlayground(current.id, { workspace, model: current.model, text, file, signal: controller.signal })
      .then((r) => {
        if (r.audio) audioUrl.current = r.audio.url;
        setResult(r);
      })
      .catch((e) => {
        if (e.name !== 'AbortError') setError(e);
      })
      .finally(() => {
        abort.current = null;
        setBusy(false);
      });
  };

  return (
    <div className="playground stack">
      {kinds.length > 1 && <Segmented value={current.id} onChange={(v) => { clear(); setKind(v); }} options={kinds.map((k) => ({ value: k.id, label: k.label }))} />}
      {current.input === 'file' ? (
        <DropZone file={file} accept={current.accept} hint={current.hint} disabled={busy} onPick={(f) => { clear(); setFile(f); }} />
      ) : (
        <textarea rows={4} placeholder={current.placeholder} value={text} onChange={(e) => setText(e.target.value)} />
      )}
      <div className="row between">
        <span className="faint small">{current.input === 'file' ? `Sent to ${workspace.baseUrl}` : current.hint}</span>
        <div className="row">
          {busy && (
            <button className="btn sm" onClick={() => abort.current && abort.current.abort()}>
              <Icon name="stop" />
              Stop
            </button>
          )}
          <button className="btn primary" disabled={busy || !ready} onClick={run}>
            {busy ? 'Running…' : current.action}
          </button>
        </div>
      </div>
      <ErrorAlert error={error} />
      {result && (
        <div className="card playground-result">
          {result.images && (
            <div className="answer-images">
              {result.images.map((src, i) => (
                <button key={i} className="answer-image" title="Download this image" onClick={() => downloadDataUrl(fileName(model.model || model.id, 'png'), src)}>
                  <img src={src} alt={`Generated ${i + 1}`} />
                </button>
              ))}
            </div>
          )}
          {result.audio && (
            <div className="stack tight">
              <audio controls src={result.audio.url} style={{ width: '100%' }} />
              <div className="row between">
                <span className="faint small">{fmtBytes(result.audio.size)} · {result.audio.type}</span>
                <button className="btn sm" onClick={() => fetch(result.audio.url).then((r) => r.blob()).then((b) => download(fileName(model.model || model.id, (result.audio.type.split('/')[1] || 'mp3').replace('mpeg', 'mp3')), b, result.audio.type))}>
                  <Icon name="download" />
                  Download
                </button>
              </div>
            </div>
          )}
          {result.vectors && <Vectors result={result} />}
          {result.moderation !== undefined && <Moderation moderation={result.moderation} />}
          {/* a transcription or an extraction is the answer itself, a revised prompt only shows when the model sent one */}
          {['stt', 'ocr'].includes(current.id) ? <TextResult text={result.text} markdown={current.id === 'ocr'} /> : result.text ? <TextResult text={result.text} /> : null}
          <RunMeta result={result} />
        </div>
      )}
    </div>
  );
}
