import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { bootstrap } from './bootstrap';

// Minimal history based router: the whole app lives under `bootstrap.basePath`, every path used in
// the code is relative to it.

const RouterContext = createContext(null);

function currentPath() {
  const { pathname } = window.location;
  const base = bootstrap.basePath;
  if (pathname === base) return '/';
  if (pathname.startsWith(base + '/')) return pathname.substring(base.length) || '/';
  return '/';
}

export function RouterProvider({ children }) {
  const [location, setLocation] = useState(() => ({ path: currentPath(), search: window.location.search }));

  useEffect(() => {
    const onPop = () => setLocation({ path: currentPath(), search: window.location.search });
    window.addEventListener('popstate', onPop);
    return () => window.removeEventListener('popstate', onPop);
  }, []);

  const navigate = useCallback((to, opts = {}) => {
    const url = to.startsWith('http') ? to : bootstrap.basePath + (to === '/' ? '' : to);
    if (opts.replace) window.history.replaceState({}, '', url);
    else window.history.pushState({}, '', url);
    const [p, q] = to.split('?');
    setLocation({ path: p || '/', search: q ? '?' + q : '' });
    if (!opts.keepScroll) window.scrollTo(0, 0);
  }, []);

  const value = useMemo(() => {
    const query = Object.fromEntries(new URLSearchParams(location.search).entries());
    return { path: location.path, query, navigate };
  }, [location, navigate]);

  return <RouterContext.Provider value={value}>{children}</RouterContext.Provider>;
}

export function useRouter() {
  return useContext(RouterContext);
}

// The query string as the state of a page: what it shows survives a reload and can be shared.
// `setQuery` merges a patch into the current query (`null`, `undefined` and `''` remove a key) and replaces the
// history entry rather than pushing one (unless `{ push: true }`), so the back button is not a list of every filter
// that was tried. It reads `window.location` rather than the rendered query so two patches in the same tick add up.
export function useQueryState() {
  const { path, query, navigate } = useRouter();
  const setQuery = useCallback(
    (patch, { push = false } = {}) => {
      const next = new URLSearchParams(window.location.search);
      Object.entries(patch).forEach(([k, v]) => {
        if (v === undefined || v === null || v === '') next.delete(k);
        else next.set(k, String(v));
      });
      const qs = next.toString();
      navigate(path + (qs ? '?' + qs : ''), { replace: !push, keepScroll: true });
    },
    [path, navigate]
  );
  return [query, setQuery];
}

// `/workspaces/:id/keys` matches `/workspaces/abc/keys` -> { id: 'abc' }. A trailing `/*` matches any sub path.
export function matchPath(pattern, path) {
  const pp = pattern.split('/').filter(Boolean);
  const ps = path.split('/').filter(Boolean);
  const params = {};
  for (let i = 0; i < pp.length; i++) {
    const seg = pp[i];
    if (seg === '*') {
      params['*'] = ps.slice(i).join('/');
      return params;
    }
    if (i >= ps.length) return null;
    if (seg.startsWith(':')) params[seg.substring(1)] = decodeURIComponent(ps[i]);
    else if (seg !== ps[i]) return null;
  }
  return pp.length === ps.length ? params : null;
}

export function Link({ to, className, children, onClick, title }) {
  const { navigate } = useRouter();
  return (
    <a
      href={bootstrap.basePath + (to === '/' ? '' : to)}
      className={className}
      title={title}
      onClick={(e) => {
        if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return;
        e.preventDefault();
        if (onClick) onClick(e);
        navigate(to);
      }}
    >
      {children}
    </a>
  );
}
