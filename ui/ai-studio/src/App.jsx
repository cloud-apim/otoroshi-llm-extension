import { createContext, useContext, useEffect, useMemo } from 'react';
import { Topbar, WorkspaceSidebar } from './components/layout';
import { ConfirmProvider, ErrorAlert, Loading, ToastProvider, useAsync } from './components/ui';
import { matchPath, RouterProvider, useRouter } from './lib/router';
import { useTheme } from './lib/theme';
import { getWorkspace, listWorkspaces, routeNeedsRepair, updateWorkspaceRoute } from './lib/workspaces';
import { WorkspacesPage } from './pages/Workspaces';
import { OverviewPage } from './pages/Overview';
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

const PAGES = {
  home: OverviewPage,
  overview: OverviewPage,
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
  const ws = useAsync(() => getWorkspace(wsId), [wsId]);

  useEffect(() => {
    if (ws.error && ws.error.status === 404) navigate('/', { replace: true });
  }, [ws.error, navigate]);

  // routes created by an older studio miss some plugins or carry legacy ones: fix them once, silently,
  // when the user is allowed to
  useEffect(() => {
    if (ws.data && routeNeedsRepair(ws.data.route)) updateWorkspaceRoute(wsId, (route) => route).catch(() => {});
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
      <div className="content center">
        {ws.loading ? <Loading /> : <ErrorAlert error={ws.error} />}
      </div>
    );
  }

  const Page = PAGES[page] || OverviewPage;
  const sidebarPage = page === 'home' ? 'overview' : page;
  return (
    <WorkspaceContext.Provider value={value}>
      <div className="shell">
        <WorkspaceSidebar workspace={ws.data} workspaces={studio.workspaces} page={sidebarPage} />
        {page === 'chat' ? <ChatPage key={wsId} /> : <Page key={`${wsId}-${page}-${sub || ''}`} sub={sub} />}
      </div>
    </WorkspaceContext.Provider>
  );
}

function Root() {
  const theme = useTheme();
  const { path } = useRouter();
  const workspaces = useAsync(() => listWorkspaces(), []);

  // `sub` is the item of a page, e.g. the user of `/workspaces/:id/users/:email`
  const wsMatch = matchPath('/workspaces/:id/:page/:sub', path) || matchPath('/workspaces/:id/:page', path) || matchPath('/workspaces/:id', path);
  const currentWorkspace = wsMatch && workspaces.data ? workspaces.data.find((w) => w.id === wsMatch.id) : null;

  const studio = useMemo(
    () => ({ workspaces: workspaces.data || [], reloadWorkspaces: workspaces.reload, theme: theme.theme }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [workspaces.data, theme.theme]
  );

  let content = null;
  if (wsMatch) {
    content = <WorkspaceShell wsId={wsMatch.id} page={wsMatch.page || 'overview'} sub={wsMatch.sub} />;
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
