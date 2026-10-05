import { useEffect, useRef, useState } from 'react';
import { Icon } from './icons';
import { CopyButton, ErrorAlert, Modal, Segmented } from './ui';
import { Markdown } from './Markdown';
import { DecisionForm, Decisions } from './decisions';
import { exampleQuestions, questionsReady, toQuestions } from '../lib/decisions';
import { MAX_EDIT_IMAGES, MAX_UPLOAD_BYTES, playgroundsOf, runPlayground } from '../lib/playgrounds';
import { imageFile } from '../lib/images';
import { modelLabel } from '../lib/modelmeta';
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

const baseName = (model) => `${(model || 'model').replace(/[^a-z0-9.-]+/gi, '-')}-${Date.now()}`;

function fileName(model, extension) {
  return `${baseName(model)}.${extension}`;
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

// an image of the answer seen large, on most of the screen, whatever its own size
function ImageViewer({ src, title, onDownload, onEdit, editLabel, onClose }) {
  return (
    <Modal
      open
      size="viewer"
      title={title}
      onClose={onClose}
      footer={
        <>
          {onEdit && (
            <button className="btn" onClick={onEdit}>
              <Icon name="edit" />
              {editLabel}
            </button>
          )}
          <button className="btn" onClick={onDownload}>
            <Icon name="download" />
            Download
          </button>
          <button className="btn primary" onClick={onClose}>
            Close
          </button>
        </>
      }
    >
      <img className="viewer-image" src={src} alt={title} />
    </Modal>
  );
}

// the images an edit starts from: dropped, picked, or taken from an answer to keep editing it
function ImagesPicker({ images, accept, onAdd, onRemove, disabled }) {
  const input = useRef(null);
  const [over, setOver] = useState(false);
  const full = images.length >= MAX_EDIT_IMAGES;
  const pick = () => !disabled && !full && input.current && input.current.click();
  const drop = (e) => {
    e.preventDefault();
    setOver(false);
    if (!disabled) onAdd([...e.dataTransfer.files].filter((f) => f.type.startsWith('image/')));
  };
  const dragging = {
    onDragOver: (e) => {
      e.preventDefault();
      setOver(true);
    },
    onDragLeave: () => setOver(false),
    onDrop: drop,
  };
  const picker = (
    <input
      ref={input}
      type="file"
      multiple
      accept={accept}
      style={{ display: 'none' }}
      onChange={(e) => {
        onAdd([...e.target.files]);
        e.target.value = '';
      }}
    />
  );
  if (images.length === 0) {
    return (
      <div className={`dropzone ${over ? 'over' : ''}`} {...dragging} onClick={pick}>
        {picker}
        <Icon name="image" size={18} />
        <b>Drop the images to edit here</b>
        <span className="faint small">PNG, JPEG or WebP, up to {MAX_EDIT_IMAGES} of them, {fmtBytes(MAX_UPLOAD_BYTES)} each at most.</span>
      </div>
    );
  }
  return (
    <div className={`edit-images ${over ? 'over' : ''}`} {...dragging}>
      {picker}
      {images.map((img, i) => (
        <div key={img.url} className="edit-image" title={`${img.file.name} · ${fmtBytes(img.file.size)}`}>
          <img src={img.url} alt={img.file.name} />
          <button className="btn sm ghost icon" title="Remove this image" disabled={disabled} onClick={() => onRemove(i)}>
            <Icon name="x" size={12} />
          </button>
        </div>
      ))}
      {!full && (
        <button className="edit-image add" title="Add images" disabled={disabled} onClick={pick}>
          <Icon name="plus" size={18} />
        </button>
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
  // the images to edit, each with the object url of its preview
  const [images, setImages] = useState([]);
  // a decision playground opens on questions ready to be asked, there to be rewritten
  const [questions, setQuestions] = useState(exampleQuestions);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [result, setResult] = useState(null);
  // the image of the answer seen large, with its index
  const [viewing, setViewing] = useState(null);
  const abort = useRef(null);
  const audioUrl = useRef(null);
  const previews = useRef([]);
  previews.current = images;

  const current = kinds.find((k) => k.id === kind) || kinds[0];

  const clear = () => {
    if (audioUrl.current) URL.revokeObjectURL(audioUrl.current);
    audioUrl.current = null;
    setResult(null);
    setError(null);
    setViewing(null);
  };

  const addImages = (files) => {
    const room = MAX_EDIT_IMAGES - previews.current.length;
    const added = files.slice(0, Math.max(room, 0)).map((f) => ({ file: f, url: URL.createObjectURL(f) }));
    if (added.length > 0) setImages((all) => [...all, ...added]);
  };
  const removeImage = (index) => {
    setImages((all) => {
      URL.revokeObjectURL(all[index].url);
      return all.filter((_, i) => i !== index);
    });
  };
  const resetImages = (files = []) => {
    previews.current.forEach((img) => URL.revokeObjectURL(img.url));
    setImages(files.map((f) => ({ file: f, url: URL.createObjectURL(f) })));
  };

  // a new model, or another way of using it, starts from a blank form
  useEffect(() => {
    clear();
    setText('');
    setFile(null);
    resetImages();
    setQuestions(exampleQuestions());
    setKind(kinds[0] ? kinds[0].id : null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [model.id, model.modality]);

  useEffect(
    () => () => {
      if (audioUrl.current) URL.revokeObjectURL(audioUrl.current);
      previews.current.forEach((img) => URL.revokeObjectURL(img.url));
    },
    [],
  );

  if (!current) return null;

  const editing = kinds.find((k) => k.id === 'image_edit');
  const ready =
    current.input === 'file'
      ? !!file
      : text.trim().length > 0 && (current.input !== 'decision' || questionsReady(questions)) && (current.input !== 'images' || images.length > 0);

  // an image of the answer becomes the one to edit, with a fresh prompt: the way to refine it step by step
  const editFurther = (src) => {
    imageFile(src, baseName(model.model || model.id))
      .then((f) => {
        clear();
        resetImages([f]);
        setText('');
        setKind(editing.id);
      })
      .catch(setError);
  };

  const run = () => {
    clear();
    setBusy(true);
    const controller = new AbortController();
    abort.current = controller;
    runPlayground(current.id, {
      workspace,
      model: current.model,
      text,
      file,
      images: images.map((img) => img.file),
      questions: current.input === 'decision' ? toQuestions(questions) : null,
      signal: controller.signal,
    })
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
      ) : current.input === 'decision' ? (
        <DecisionForm state={text} onState={setText} questions={questions} onQuestions={setQuestions} disabled={busy} />
      ) : current.input === 'images' ? (
        <>
          <ImagesPicker images={images} accept={current.accept} disabled={busy} onAdd={(files) => { clear(); addImages(files); }} onRemove={removeImage} />
          <textarea rows={4} placeholder={current.placeholder} value={text} onChange={(e) => setText(e.target.value)} />
        </>
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
                <div key={i} className="stack tight">
                  <button className="answer-image" title="Download this image" onClick={() => downloadDataUrl(fileName(model.model || model.id, 'png'), src)}>
                    <img src={src} alt={`Generated ${i + 1}`} />
                  </button>
                  <div className="answer-image-actions">
                    <button className="btn sm" title="See this image larger" onClick={() => setViewing({ src, index: i })}>
                      <Icon name="eye" />
                      View
                    </button>
                    {editing && (
                      <button className="btn sm" title="Edit this image with this model" onClick={() => editFurther(src)}>
                        <Icon name="edit" />
                        {current.id === 'image_edit' ? 'Keep editing' : 'Edit this image'}
                      </button>
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}
          {viewing && (
            <ImageViewer
              src={viewing.src}
              title={result.images.length > 1 ? `${modelLabel(model)} · image ${viewing.index + 1}` : modelLabel(model)}
              onDownload={() => downloadDataUrl(fileName(model.model || model.id, 'png'), viewing.src)}
              onEdit={editing ? () => editFurther(viewing.src) : null}
              editLabel={current.id === 'image_edit' ? 'Keep editing' : 'Edit this image'}
              onClose={() => setViewing(null)}
            />
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
          {result.answers && <Decisions answers={result.answers} />}
          {result.reasoning && (
            <details className="playground-reasoning">
              <summary className="muted small">Reasoning</summary>
              <Markdown text={result.reasoning} />
            </details>
          )}
          {/* a transcription, a translation, an extraction or an answer is the result itself, a revised prompt only shows when the model sent one */}
          {['stt', 'translation', 'ocr', 'responses'].includes(current.id) ? (
            <TextResult text={result.text} markdown={['ocr', 'responses'].includes(current.id)} />
          ) : result.text ? (
            <TextResult text={result.text} />
          ) : null}
          <RunMeta result={result} />
        </div>
      )}
    </div>
  );
}
