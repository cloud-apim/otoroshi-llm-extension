// Values injected by the html page served by the extension (see studio/studio.scala). When running
// on the vite dev server they are fetched from the extension instead (see `loadBootstrap`).

const DEFAULT_CONFIG = {
  enabled: true,
  domain: 'oto.tools',
  exposure: 'subdomain',
  route_path: '/v1',
  public_scheme: 'http',
  public_port: '',
  default_throttling_quota: 10000000,
  default_daily_quota: 10000000,
  default_monthly_quota: 10000000,
  conversations_store: 'redis',
};

export const bootstrap = {
  basePath: '/extensions/cloud-apim/ai-studio',
  adminUrl: '/bo/dashboard',
  extensionId: 'cloud-apim.extensions.LlmExtension',
  user: { email: 'unknown', name: 'unknown', superAdmin: false, rights: [] },
  config: DEFAULT_CONFIG,
  otoroshi: { version: 'dev' },
};

function apply(values) {
  Object.assign(bootstrap, values, { config: { ...DEFAULT_CONFIG, ...((values && values.config) || {}) } });
}

export async function loadBootstrap() {
  if (window.__AI_STUDIO__) {
    apply(window.__AI_STUDIO__);
    return;
  }
  const res = await fetch('/extensions/cloud-apim/extensions/ai-extension/studio/bootstrap', { credentials: 'include' });
  if (res.status === 401) {
    window.location.href = `${import.meta.env.DEV ? import.meta.env.VITE_OTOROSHI_URL || 'http://otoroshi.oto.tools:9999' : ''}/bo/dashboard`;
    throw new Error('not logged in');
  }
  apply(await res.json());
  if (import.meta.env.DEV) bootstrap.adminUrl = `${import.meta.env.VITE_OTOROSHI_URL || 'http://otoroshi.oto.tools:9999'}/bo/dashboard`;
}

export function currentTenant() {
  try {
    return window.localStorage.getItem('Otoroshi-Tenant') || 'default';
  } catch (e) {
    return 'default';
  }
}
