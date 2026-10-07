import { createContext, useContext, useEffect, useMemo } from 'react';
import { Topbar, WORKSPACE_PAGES, WorkspaceSidebar } from './components/layout';
import { ConfirmProvider, Empty, ErrorAlert, Loading, ToastProvider, useAsync } from './components/ui';
import { bootstrap } from './lib/bootstrap';
import { matchPath, RouterProvider, useRouter } from './lib/router';
import { useTheme } from './lib/theme';
import { backend } from './lib/backend';
import { hasPermission, platform } from './lib/platform';
import { WorkspacesPage } from './pages/Workspaces';
import { OverviewPage } from './pages/Overview';
import { ApiPage } from './pages/Api';
import { KeysPage } from './pages/Keys';
import { ProvidersPage } from './pages/Providers';
import { ModelsPage } from './pages/Models';
import { ChatPage } from './pages/Chat';
import { SettingsPage } from './pages/Settings';
import { ActivityPage } from './pages/Activity';
import { LogsPage } from './pages/Logs';
import { GuardrailsPage } from './pages/Guardrails';
import { RoutingPage } from './pages/Routing';
import { PresetsPage } from './pages/Presets';
import { ToolsPage } from './pages/Tools';
import { McpServerPage } from './pages/McpServer';
import { CreditsPage } from './pages/Credits';
import { UsersPage } from './pages/Users';

const StudioContext = createContext(null);
export const useStudio = () => useContext(StudioContext);

const WorkspaceContext = createContext(null);
export const useWorkspace = () => useContext(WorkspaceContext);

// what the signed-in user can do on the current workspace (the pages hide what they cannot, the api refuses it)
export function useCan() {
  const { workspace } = useWorkspace();
  return (permission) => platform.can(permission, workspace);
}

// a page opened from its url by someone it is not for
function NotAllowed({ children = 'Your role in this workspace does not give access to this page.' }) {
  return (
    <div className="content">
      <div className="card">
        <Empty title="Insufficient access">{children}</Empty>
      </div>
    </div>
  );
}

const PAGES = {
  home: OverviewPage,
  overview: OverviewPage,
  api: ApiPage,
  activity: ActivityPage,
  logs: LogsPage,
  keys: KeysPage,
  users: UsersPage,
  guardrails: GuardrailsPage,
  providers: ProvidersPage,
  routing: RoutingPage,
  presets: PresetsPage,
  tools: ToolsPage,
  'mcp-server': McpServerPage,
  credits: CreditsPage,
  settings: SettingsPage,
  models: ModelsPage,
};

function WorkspaceShell({ wsId, page, sub }) {
  const studio = useStudio();
  const { navigate } = useRouter();
  const ws = useAsync(() => backend.run('workspace.get', wsId), [wsId]);

  useEffect(() => {
    if (ws.error && ws.error.status === 404) navigate('/', { replace: true });
  }, [ws.error, navigate]);

  // routes created by an older studio miss some plugins or carry legacy ones: an empty update fixes them,
  // once, silently, when the user is allowed to
  useEffect(() => {
    if (ws.data && ws.data.needs_repair) backend.run('workspace.update', wsId, { body: {} }).catch(() => {});
  }, [ws.data, wsId]);

  const value = useMemo(
    () => ({
      workspace: ws.data,
      reload: () => {
        ws.reload();
        studio.reloadWorkspaces();
      },
    }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [ws.data]
  );

  if (!ws.data) {
    return (
      <div className="content">
        {ws.loading ? <Loading /> : <ErrorAlert error={ws.error} />}
      </div>
    );
  }

  // the pages of the edition, then the ones of the studio, each with the permission it needs
  const extra = platform.pages.find((p) => p.id === page);
  const own = WORKSPACE_PAGES.find((p) => p.id === page);
  const permission = extra ? extra.permission : page === 'chat' ? 'chat:use' : page === 'models' || page === 'home' ? 'workspace:read' : own && own.permission;
  // a user can always open their own usage
  const ownProfile = page === 'users' && sub === bootstrap.user.email;
  const allowed = !permission || platform.can(ownProfile ? ['activity:read', 'usage:own'] : permission, ws.data);
  const Page = (extra && extra.component) || PAGES[page] || OverviewPage;
  const sidebarPage = page === 'home' ? 'overview' : page;
  return (
    <WorkspaceContext.Provider value={value}>
      <div className="shell">
        <WorkspaceSidebar workspace={ws.data} workspaces={studio.workspaces} page={sidebarPage} />
        {!allowed ? <NotAllowed /> : page === 'chat' ? <ChatPage key={wsId} /> : <Page key={`${wsId}-${page}-${sub || ''}`} sub={sub} />}
      </div>
    </WorkspaceContext.Provider>
  );
}

function Root() {
  const theme = useTheme();
  const { path } = useRouter();
  const workspaces = useAsync(() => backend.workspaces.list(), []);

  // `sub` is the item of a page, e.g. the user of `/workspaces/:id/users/:email`
  const wsMatch = matchPath('/workspaces/:id/:page/:sub', path) || matchPath('/workspaces/:id/:page', path) || matchPath('/workspaces/:id', path);
  const currentWorkspace = wsMatch && workspaces.data ? workspaces.data.find((w) => w.id === wsMatch.id) : null;

  const studio = useMemo(
    () => ({ workspaces: workspaces.data || [], reloadWorkspaces: workspaces.reload, theme: theme.theme }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [workspaces.data, theme.theme]
  );

  // a page of the edition outside of the workspaces
  const route = wsMatch ? null : platform.routes.map((r) => ({ r, params: matchPath(r.path, path) })).find((x) => x.params);

  let content = null;
  if (wsMatch) {
    content = <WorkspaceShell wsId={wsMatch.id} page={wsMatch.page || 'overview'} sub={wsMatch.sub} />;
  } else if (route) {
    const Page = route.r.component;
    content = !route.r.permission || hasPermission(route.r.permission) ? <Page params={route.params} /> : <NotAllowed>You do not have access to this page.</NotAllowed>;
  } else {
    content = <WorkspacesPage loading={workspaces.loading} error={workspaces.error} />;
  }

  return (
    <StudioContext.Provider value={studio}>
      <Topbar theme={theme} workspaces={workspaces.data} currentWorkspace={currentWorkspace} />
      {content}
    </StudioContext.Provider>
  );
}

export function App() {
  return (
    <ToastProvider>
      <ConfirmProvider>
        <RouterProvider>
          <Root />
        </RouterProvider>
      </ConfirmProvider>
    </ToastProvider>
  );
}
