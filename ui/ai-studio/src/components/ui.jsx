import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { Icon } from './icons';

/* ---------- async data ---------- */

export function useAsync(fn, deps = []) {
  const [state, setState] = useState({ loading: true, error: null, data: null });
  const counter = useRef(0);
  const run = useCallback(() => {
    const current = ++counter.current;
    setState((s) => ({ ...s, loading: true, error: null }));
    return Promise.resolve()
      .then(fn)
      .then(
        (data) => {
          if (current === counter.current) setState({ loading: false, error: null, data });
          return data;
        },
        (error) => {
          if (current === counter.current) setState({ loading: false, error, data: null });
        }
      );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
  useEffect(() => {
    run();
  }, [run]);
  return { ...state, reload: run };
}

/* ---------- toasts ---------- */

const ToastContext = createContext(() => {});

export function ToastProvider({ children }) {
  const [toasts, setToasts] = useState([]);
  const push = useCallback((message, kind = 'info') => {
    const id = Math.random().toString(36).substring(2);
    setToasts((t) => [...t, { id, message: String(message), kind }]);
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), kind === 'error' ? 6000 : 3000);
  }, []);
  return (
    <ToastContext.Provider value={push}>
      {children}
      <div className="toasts">
        {toasts.map((t) => (
          <div key={t.id} className={`toast ${t.kind === 'error' ? 'error' : ''}`}>
            {t.message}
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast() {
  const push = useContext(ToastContext);
  return {
    info: (m) => push(m, 'info'),
    success: (m) => push(m, 'success'),
    error: (e) => push(e && e.message ? e.message : e, 'error'),
  };
}

/* ---------- layout pieces ---------- */

export function PageHeader({ title, description, children }) {
  return (
    <div className="page-header">
      <div>
        <h1>{title}</h1>
        {description && <p>{description}</p>}
      </div>
      {children && <div className="row">{children}</div>}
    </div>
  );
}

export function Card({ title, description, actions, children, className = '', onClick }) {
  return (
    <div className={`card ${onClick ? 'clickable' : ''} ${className}`} onClick={onClick}>
      {(title || actions) && (
        <div className="card-head">
          <div className="grow">
            {title && <h2>{title}</h2>}
            {description && <p>{description}</p>}
          </div>
          {actions && <div className="row">{actions}</div>}
        </div>
      )}
      {children}
    </div>
  );
}

export function Loading({ label = 'Loading…' }) {
  return (
    <div className="loading">
      <div className="spinner" />
      {label}
    </div>
  );
}

export function ErrorAlert({ error }) {
  if (!error) return null;
  return <div className="alert error">{error.message || String(error)}</div>;
}

export function Empty({ title, children, action }) {
  return (
    <div className="empty">
      {title && <h3>{title}</h3>}
      {children && <div>{children}</div>}
      {action}
    </div>
  );
}

export function Badge({ kind = '', children, dot, title }) {
  return (
    <span className={`badge ${kind}`} title={title}>
      {dot && <span className="dot" />}
      {children}
    </span>
  );
}

export function StatusBadge({ enabled, on = 'Enabled', off = 'Disabled' }) {
  return enabled ? <Badge kind="positive">{on}</Badge> : <Badge>{off}</Badge>;
}

export function Tabs({ tabs, value, onChange }) {
  return (
    <div className="tabs">
      {tabs.map((t) => (
        <button key={t.value} className={t.value === value ? 'active' : ''} onClick={() => onChange(t.value)}>
          {t.label}
        </button>
      ))}
    </div>
  );
}

export function Segmented({ options, value, onChange }) {
  return (
    <div className="segmented">
      {options.map((o) => (
        <button key={o.value} className={o.value === value ? 'active' : ''} onClick={() => onChange(o.value)}>
          {o.label}
        </button>
      ))}
    </div>
  );
}

/* ---------- forms ---------- */

export function Field({ label, hint, error, children, className = '' }) {
  return (
    <div className={`field ${className}`}>
      {label && <label>{label}</label>}
      {children}
      {hint && <div className="hint">{hint}</div>}
      {error && <div className="error">{error}</div>}
    </div>
  );
}

export function Toggle({ value, onChange, disabled, title }) {
  return (
    <button
      type="button"
      title={title}
      className={`toggle ${value ? 'on' : ''}`}
      disabled={disabled}
      onClick={() => onChange(!value)}
      aria-pressed={!!value}
    />
  );
}

export function TextInput({ value, onChange, className = '', ...props }) {
  return <input className={`input ${className}`} value={value ?? ''} onChange={(e) => onChange(e.target.value)} {...props} />;
}

export function NumberInput({ value, onChange, className = '', allowEmpty = true, ...props }) {
  return (
    <input
      type="number"
      className={`input ${className}`}
      value={value === null || value === undefined ? '' : value}
      onChange={(e) => {
        const v = e.target.value;
        if (v === '' && allowEmpty) onChange(null);
        else onChange(Number(v));
      }}
      {...props}
    />
  );
}

export function TextArea({ value, onChange, className = '', ...props }) {
  return <textarea className={className} value={value ?? ''} onChange={(e) => onChange(e.target.value)} {...props} />;
}

export function Select({ value, onChange, options, className = '', placeholder, ...props }) {
  return (
    <select className={className} value={value ?? ''} onChange={(e) => onChange(e.target.value)} {...props}>
      {placeholder !== undefined && <option value="">{placeholder}</option>}
      {options.map((o) => (
        <option key={o.value} value={o.value} disabled={o.disabled}>
          {o.label}
        </option>
      ))}
    </select>
  );
}

export function LinesInput({ value, onChange, ...props }) {
  const [text, setText] = useState((value || []).join('\n'));
  useEffect(() => {
    const current = text.split('\n').map((l) => l.trim()).filter(Boolean);
    if (JSON.stringify(current) !== JSON.stringify(value || [])) setText((value || []).join('\n'));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [JSON.stringify(value || [])]);
  return (
    <textarea
      className="mono"
      value={text}
      onChange={(e) => {
        setText(e.target.value);
        onChange(e.target.value.split('\n').map((l) => l.trim()).filter(Boolean));
      }}
      {...props}
    />
  );
}

export function JsonInput({ value, onChange, rows = 10 }) {
  const [text, setText] = useState(() => JSON.stringify(value ?? {}, null, 2));
  const [error, setError] = useState(null);
  return (
    <div className="field">
      <textarea
        className="mono"
        rows={rows}
        value={text}
        onChange={(e) => {
          setText(e.target.value);
          try {
            const parsed = JSON.parse(e.target.value);
            setError(null);
            onChange(parsed);
          } catch (err) {
            setError('invalid json');
          }
        }}
      />
      {error && <div className="error">{error}</div>}
    </div>
  );
}

export function Checks({ options, value = [], onChange }) {
  return (
    <div className="checks">
      {options.map((o) => (
        <label key={o.value} className="check">
          <input
            type="checkbox"
            checked={value.includes(o.value)}
            onChange={(e) => onChange(e.target.checked ? [...value, o.value] : value.filter((v) => v !== o.value))}
          />
          {o.label}
        </label>
      ))}
    </div>
  );
}

// `anchor` is the element that asked for the copy
export function copyToClipboard(text, anchor) {
  if (navigator.clipboard && window.isSecureContext) return navigator.clipboard.writeText(text);
  // plain http has no clipboard api: copy the selection of a hidden textarea. An open modal <dialog>
  // makes the rest of the page inert, so the textarea must live in the dialog of the button to be
  // focused and selected — outside it, the copy silently takes whatever else is selected
  const host = (anchor && anchor.closest && anchor.closest('dialog[open]')) || document.body;
  const previous = document.activeElement;
  const ta = document.createElement('textarea');
  ta.value = text;
  ta.setAttribute('readonly', '');
  ta.style.position = 'fixed';
  ta.style.top = '0';
  ta.style.opacity = '0';
  host.appendChild(ta);
  ta.focus();
  ta.select();
  const copied = document.execCommand('copy');
  host.removeChild(ta);
  if (previous && previous.focus) previous.focus();
  return copied ? Promise.resolve() : Promise.reject(new Error('unable to copy'));
}

export function CopyButton({ text, className = 'copy-btn', label }) {
  const [done, setDone] = useState(false);
  return (
    <button
      type="button"
      className={className}
      title="Copy"
      onClick={(e) => {
        e.stopPropagation();
        copyToClipboard(typeof text === 'function' ? text() : text, e.currentTarget)
          .then(() => {
            setDone(true);
            setTimeout(() => setDone(false), 1200);
          })
          // no check mark when nothing was copied
          .catch(() => {});
      }}
    >
      <Icon name={done ? 'check' : 'copy'} />
      {label}
    </button>
  );
}

export function Readonly({ value, mono = true, copy = true }) {
  return (
    <div className={`readonly ${mono ? 'mono' : ''} ${copy ? 'with-copy' : ''}`}>
      <span className="truncate">{value}</span>
      {copy && <CopyButton text={value} />}
    </div>
  );
}

export function SecretInput({ value, onChange, placeholder }) {
  const [visible, setVisible] = useState(false);
  return (
    <div className="input-with-btn">
      <input
        className="input"
        type={visible ? 'text' : 'password'}
        autoComplete="new-password"
        value={value ?? ''}
        placeholder={placeholder}
        onChange={(e) => onChange(e.target.value)}
      />
      <button type="button" className="copy-btn" onClick={() => setVisible(!visible)} title={visible ? 'Hide' : 'Show'}>
        <Icon name={visible ? 'eyeOff' : 'eye'} />
      </button>
    </div>
  );
}

/* ---------- modal ---------- */

export function Modal({ title, open, onClose, children, footer, size = '' }) {
  const ref = useRef(null);
  useEffect(() => {
    const d = ref.current;
    if (!d) return;
    if (open && !d.open) d.showModal();
    if (!open && d.open) d.close();
  }, [open]);
  if (!open) return null;
  return (
    <dialog
      ref={ref}
      className={`modal ${size}`}
      onCancel={(e) => {
        e.preventDefault();
        onClose();
      }}
      onMouseDown={(e) => {
        if (e.target === ref.current) onClose();
      }}
    >
      <div className="modal-head">
        <h2>{title}</h2>
        <button className="copy-btn" onClick={onClose} title="Close">
          <Icon name="x" />
        </button>
      </div>
      <div className="modal-body">{children}</div>
      {footer && <div className="modal-foot">{footer}</div>}
    </dialog>
  );
}

const ConfirmContext = createContext(() => Promise.resolve(false));

export function ConfirmProvider({ children }) {
  const [state, setState] = useState(null);
  const confirm = useCallback(
    (opts) =>
      new Promise((resolve) => {
        setState({ ...opts, resolve });
      }),
    []
  );
  const close = (result) => {
    state.resolve(result);
    setState(null);
  };
  return (
    <ConfirmContext.Provider value={confirm}>
      {children}
      {state && (
        <Modal
          open
          title={state.title || 'Are you sure?'}
          onClose={() => close(false)}
          footer={
            <>
              <button className="btn" onClick={() => close(false)}>
                Cancel
              </button>
              <button className={`btn ${state.danger ? 'danger' : 'primary'}`} onClick={() => close(true)}>
                {state.confirmLabel || 'Confirm'}
              </button>
            </>
          }
        >
          <p className="muted">{state.message}</p>
        </Modal>
      )}
    </ConfirmContext.Provider>
  );
}

export function useConfirm() {
  return useContext(ConfirmContext);
}

/* ---------- drawer ---------- */

export function Drawer({ title, open, onClose, children }) {
  useEffect(() => {
    if (!open) return;
    const onKey = (e) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);
  if (!open) return null;
  return (
    <>
      <div className="drawer-backdrop" onClick={onClose} />
      <div className="drawer">
        <div className="drawer-head">
          <h2>{title}</h2>
          <button className="copy-btn" onClick={onClose} title="Close">
            <Icon name="x" />
          </button>
        </div>
        <div className="drawer-body">{children}</div>
      </div>
    </>
  );
}

export function Progress({ value, max }) {
  const ratio = max > 0 ? Math.min(1, value / max) : 0;
  const cls = ratio >= 1 ? 'over' : ratio >= 0.8 ? 'warn' : '';
  return (
    <div className={`progress ${cls}`}>
      <i style={{ width: `${Math.round(ratio * 100)}%` }} />
    </div>
  );
}
