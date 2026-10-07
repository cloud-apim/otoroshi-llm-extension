import { useEffect, useMemo, useRef, useState } from 'react';
import { bootstrap } from '../lib/bootstrap';
import { platform } from '../lib/platform';
import { initials } from '../lib/format';
import { Link, useRouter } from '../lib/router';
import { Icon } from './icons';
import cloudApimLogo from '../assets/cloud-apim-logo.svg';

// the pages of the workspace menu, each with the permission (or one of the permissions) it needs
export const WORKSPACE_PAGES = [
  { id: 'overview', label: 'Overview', icon: 'grid', permission: 'workspace:read' },
  { id: 'api', label: 'API', icon: 'book', permission: 'workspace:read' },
  { id: 'activity', label: 'Activity', icon: 'chart', permission: 'activity:read' },
  { id: 'logs', label: 'Logs', icon: 'list', permission: 'activity:read' },
  { id: 'keys', label: 'API Keys', icon: 'key', permission: ['config:read', 'keys:own'] },
  { id: 'users', label: 'Users', icon: 'users', permission: 'activity:read' },
  { id: 'guardrails', label: 'Guardrails', icon: 'shield', permission: 'config:read' },
  { id: 'providers', label: 'Providers (BYOK)', icon: 'database', permission: 'config:read' },
  { id: 'routing', label: 'Routing', icon: 'route', permission: 'config:read' },
  { id: 'presets', label: 'Presets', icon: 'sliders', permission: 'config:read' },
  { id: 'tools', label: 'Tools', icon: 'wrench', permission: 'config:read' },
  { id: 'mcp-server', label: 'MCP server', icon: 'plug', permission: 'config:read' },
  { id: 'credits', label: 'Credits', icon: 'wallet', permission: 'config:read' },
  { id: 'settings', label: 'Settings', icon: 'settings', permission: 'config:read' },
];

// the pages of the menu, with the ones the edition adds (each after the page it names), the user can open
export function menuOf(workspace) {
  const pages = [...WORKSPACE_PAGES];
  platform.pages.forEach((p) => {
    const idx = pages.findIndex((x) => x.id === p.after);
    pages.splice(idx >= 0 ? idx + 1 : pages.length, 0, p);
  });
  const visible = pages.filter((p) => !p.permission || platform.can(p.permission, workspace));
  // who does not see the activity of the workspace still sees their own usage, after the api
  if (!platform.can('activity:read', workspace) && platform.can('usage:own', workspace)) {
    visible.splice(visible.findIndex((p) => p.id === 'api') + 1, 0, { id: `users/${encodeURIComponent(bootstrap.user.email)}`, label: 'My usage', icon: 'chart' });
  }
  return visible;
}

function SearchBox({ workspaces, currentWorkspace }) {
  const { navigate } = useRouter();
  const [q, setQ] = useState('');
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const ref = useRef(null);

  const results = useMemo(() => {
    const items = [];
    (workspaces || []).forEach((ws) => {
      items.push({ label: ws.name, hint: 'Workspace', to: `/workspaces/${ws.id}/overview`, icon: 'box' });
    });
    if (currentWorkspace) {
      menuOf(currentWorkspace).forEach((p) => items.push({ label: p.label, hint: currentWorkspace.name, to: `/workspaces/${currentWorkspace.id}/${p.id}`, icon: p.icon }));
      items.push({ label: 'Models', hint: currentWorkspace.name, to: `/workspaces/${currentWorkspace.id}/models`, icon: 'box' });
      items.push({ label: 'Chat', hint: currentWorkspace.name, to: `/workspaces/${currentWorkspace.id}/chat`, icon: 'message' });
    }
    const needle = q.trim().toLowerCase();
    if (!needle) return [];
    return items.filter((i) => i.label.toLowerCase().includes(needle)).slice(0, 12);
  }, [q, workspaces, currentWorkspace]);

  useEffect(() => {
    const onKey = (e) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
        e.preventDefault();
        ref.current && ref.current.focus();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  const go = (item) => {
    setQ('');
    setOpen(false);
    navigate(item.to);
  };

  return (
    <div className="search" style={{ position: 'relative' }}>
      <input
        ref={ref}
        className="input search"
        placeholder="Search"
        value={q}
        onFocus={() => setOpen(true)}
        onBlur={() => setTimeout(() => setOpen(false), 150)}
        onChange={(e) => {
          setQ(e.target.value);
          setActive(0);
          setOpen(true);
        }}
        onKeyDown={(e) => {
          if (e.key === 'ArrowDown') setActive((a) => Math.min(a + 1, results.length - 1));
          if (e.key === 'ArrowUp') setActive((a) => Math.max(a - 1, 0));
          if (e.key === 'Enter' && results[active]) go(results[active]);
          if (e.key === 'Escape') setOpen(false);
        }}
      />
      {open && results.length > 0 && (
        <div className="search-results">
          {results.map((r, idx) => (
            <div key={r.to} className={`item ${idx === active ? 'active' : ''}`} onMouseDown={() => go(r)}>
              <Icon name={r.icon} />
              <span className="grow truncate">{r.label}</span>
              <span className="faint small">{r.hint}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

const THEME_OPTIONS = [
  { value: 'light', label: 'Light', icon: 'sun' },
  { value: 'dark', label: 'Dark', icon: 'moon' },
  { value: 'system', label: 'System', icon: 'monitor' },
];

function ThemeMenu({ theme }) {
  const [open, setOpen] = useState(false);
  const ref = useRef(null);
  useEffect(() => {
    if (!open) return;
    const onClick = (e) => ref.current && !ref.current.contains(e.target) && setOpen(false);
    document.addEventListener('mousedown', onClick);
    return () => document.removeEventListener('mousedown', onClick);
  }, [open]);
  const current = THEME_OPTIONS.find((o) => o.value === theme.preference) || THEME_OPTIONS[2];
  return (
    <div className="menu" ref={ref}>
      <button className="theme-btn" onClick={() => setOpen(!open)} title={`Theme: ${current.label}`}>
        <Icon name={current.icon} />
      </button>
      {open && (
        <div className="menu-items">
          {THEME_OPTIONS.map((o) => (
            <button
              key={o.value}
              className={o.value === theme.preference ? 'active' : ''}
              onClick={() => {
                theme.choose(o.value);
                setOpen(false);
              }}
            >
              <Icon name={o.icon} />
              <span className="grow">{o.label}</span>
              {o.value === theme.preference && <Icon name="check" />}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

export function Topbar({ theme, workspaces, currentWorkspace }) {
  const { path } = useRouter();
  const user = bootstrap.user;
  const wsBase = currentWorkspace ? `/workspaces/${currentWorkspace.id}` : null;
  const firstWs = !currentWorkspace && workspaces && workspaces.length > 0 ? workspaces[0] : null;
  const target = wsBase || (firstWs ? `/workspaces/${firstWs.id}` : null);
  const nav = [
    { label: 'Home', to: wsBase ? `${wsBase}/home` : '/', active: path === '/' || path.endsWith('/home') },
    target && { label: 'Models', to: `${target}/models`, active: path.endsWith('/models') },
    target && { label: 'Chat', to: `${target}/chat`, active: path.endsWith('/chat') },
  ].filter(Boolean);
  return (
    <header className="topbar">
      <Link to="/" className="brand" title="AI Studio, by Cloud APIM">
        <img className="brand-mark" src={cloudApimLogo} alt="Cloud APIM" />
        AI Studio
      </Link>
      {platform.experimental && (
        <span className="badge warning experimental" title="AI Studio is experimental: it does not cover everything the LLM extension can do yet, and may change in future releases">
          Experimental
        </span>
      )}
      <SearchBox workspaces={workspaces} currentWorkspace={currentWorkspace} />
      <nav>
        {nav.map((n) => (
          <Link key={n.label} to={n.to} className={n.active ? 'active' : ''}>
            {n.label}
          </Link>
        ))}
        <a href="https://cloud-apim.github.io/otoroshi-llm-extension/docs/ai-studio" target="_blank" rel="noreferrer">
          Docs
        </a>
      </nav>
      {platform.topbar.map((Item, idx) => (typeof Item === 'function' ? <Item key={idx} workspace={currentWorkspace} /> : <span key={idx}>{Item}</span>))}
      <ThemeMenu theme={theme} />
      {platform.links.admin && (
        <a className="btn sm" href={platform.links.admin} title="Back to the Otoroshi admin console">
          <Icon name="arrowLeft" />
          Back to Otoroshi
        </a>
      )}
      {currentWorkspace ? (
        <Link className="user" to={`/workspaces/${currentWorkspace.id}/users/${encodeURIComponent(user.email)}`} title={`${user.email}: my usage in ${currentWorkspace.name}`}>
          <span className="avatar">{initials(user.name || user.email)}</span>
          <span className="truncate" style={{ maxWidth: 140 }}>
            {user.name || user.email}
          </span>
        </Link>
      ) : (
        <div className="user" title={user.email}>
          <span className="avatar">{initials(user.name || user.email)}</span>
          <span className="truncate" style={{ maxWidth: 140 }}>
            {user.name || user.email}
          </span>
        </div>
      )}
    </header>
  );
}

export function WorkspaceSidebar({ workspace, workspaces, page }) {
  const { navigate } = useRouter();
  return (
    <aside className="sidebar">
      <Link to="/" className="navlink muted">
        <Icon name="arrowLeft" />
        Back to Workspaces
      </Link>
      <select className="ws-switch" value={workspace.id} onChange={(e) => navigate(`/workspaces/${e.target.value}/${page || 'overview'}`)}>
        {(workspaces || [workspace]).map((ws) => (
          <option key={ws.id} value={ws.id}>
            {ws.name}
          </option>
        ))}
      </select>
      {menuOf(workspace).map((p) => (
        <Link key={p.id} to={`/workspaces/${workspace.id}/${p.id}`} className={`navlink ${page === p.id || p.id.startsWith(`${page}/`) ? 'active' : ''}`}>
          <Icon name={p.icon} />
          {p.label}
        </Link>
      ))}
    </aside>
  );
}
