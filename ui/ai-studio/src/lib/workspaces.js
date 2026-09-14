import { bootstrap, currentTenant } from './bootstrap';
import { META, Resources, randomId, slugify, workspaceFilter } from './entities';

// A workspace is not an entity by itself: it is the combination of a team (owning every entity of
// the workspace), a route (the OpenAI-compatible endpoint) and all the entities tagged with
// `metadata.ai_studio_workspace = <id>`. Everything is created and read live through the admin api.

export const OPENAI_COMPAT_PLUGIN = 'cp:otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.OpenAiCompatApi';
export const CONSUMER_PRESET_PLUGIN = 'cp:otoroshi.next.plugins.MandatoryConsumerPreset';
export const IP_ALLOW_PLUGIN = 'cp:otoroshi.next.plugins.IpAddressAllowedList';
export const IP_BLOCK_PLUGIN = 'cp:otoroshi.next.plugins.IpAddressBlockList';
// added by earlier versions of the studio, removed from the routes on their next update
const LEGACY_STUDIO_CONSUMER_PLUGIN = 'cp:otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.AiStudioConsumer';

// modality -> resource + plugin ref field
export const MODALITIES = [
  { id: 'text', label: 'Text', resource: 'providers', refs: 'language_model_refs', kind: 'provider' },
  { id: 'embedding', label: 'Embedding', resource: 'embeddingModels', refs: 'embedding_model_refs', kind: 'embedding-model' },
  { id: 'image', label: 'Image', resource: 'imageModels', refs: 'image_model_refs', kind: 'image-model' },
  { id: 'audio', label: 'Audio', resource: 'audioModels', refs: 'audio_model_refs', kind: 'audio-model' },
  { id: 'moderation', label: 'Moderation', resource: 'moderationModels', refs: 'moderation_model_refs', kind: 'moderation-model' },
  { id: 'ocr', label: 'OCR', resource: 'ocrModels', refs: 'ocr_model_refs', kind: 'ocr-model' },
  { id: 'video', label: 'Video', resource: 'videoModels', refs: null, kind: 'video-model' },
];

export const teamIdOf = (wsId) => `team_ai_studio_${wsId}`;
export const routeIdOf = (wsId) => `route_ai_studio_${wsId}`;
export const consumerTagOf = (wsId) => `ai_studio_ws_${wsId}`;

export function workspaceMetadata(wsId, kind, extra = {}) {
  return { [META.flag]: 'true', [META.workspace]: wsId, [META.kind]: kind, ...extra };
}

// rights: tenant admins only need the workspace team, other users also keep the teams they can write
// so they can still see what they create
export function workspaceLocation(wsId) {
  const tenant = currentTenant();
  const teams = [teamIdOf(wsId)];
  const rights = (bootstrap.user && bootstrap.user.rights) || [];
  const tenantAdmin = bootstrap.user.superAdmin || rights.some((r) => (r.tenant === '*:rw' || r.tenant === `${tenant}:rw`) && (r.teams || []).includes('*:rw'));
  if (!tenantAdmin) {
    rights
      .filter((r) => r.tenant === '*:rw' || r.tenant === `${tenant}:rw` || r.tenant === '*:r' || r.tenant === `${tenant}:r`)
      .flatMap((r) => r.teams || [])
      .filter((t) => t.endsWith(':rw') && !t.startsWith('*'))
      .forEach((t) => teams.push(t.split(':')[0]));
  }
  return { tenant, teams: [...new Set(teams)] };
}

export function exposureFor(slug) {
  const c = bootstrap.config;
  const routePath = c.route_path || '/v1';
  if (c.exposure === 'path') {
    return { host: c.domain, path: `/${slug}${routePath}` };
  }
  return { host: `${slug}.${c.domain}`, path: routePath };
}

export function baseUrlOf(route) {
  const c = bootstrap.config;
  const domain = (route && route.frontend && route.frontend.domains && route.frontend.domains[0]) || '';
  const idx = domain.indexOf('/');
  const host = idx > -1 ? domain.substring(0, idx) : domain;
  const path = idx > -1 ? domain.substring(idx) : '';
  return `${c.public_scheme}://${host}${c.public_port || ''}${path}`;
}

export function slugOf(route) {
  const c = bootstrap.config;
  const domain = (route && route.frontend && route.frontend.domains && route.frontend.domains[0]) || '';
  if (c.exposure === 'path') {
    const parts = domain.split('/');
    return parts[1] || '';
  }
  return domain.split('/')[0].split('.')[0];
}

function toWorkspace(route) {
  const wsId = route.metadata[META.workspace];
  return {
    id: wsId,
    name: route.name,
    description: route.description,
    enabled: route.enabled,
    slug: slugOf(route),
    baseUrl: baseUrlOf(route),
    route,
  };
}

export async function listWorkspaces() {
  const routes = await Resources.routes.list({ [`metadata.${META.kind}`]: 'workspace' });
  return routes.map(toWorkspace).sort((a, b) => a.name.localeCompare(b.name));
}

export async function getWorkspace(wsId) {
  const route = await Resources.routes.get(routeIdOf(wsId));
  return toWorkspace(route);
}

export function findPlugin(route, plugin) {
  return (route.plugins || []).find((p) => p.plugin === plugin);
}

function pluginInstance(plugin, config, index = {}, enabled = true) {
  return { enabled, debug: false, plugin, include: [], exclude: [], config, bound_listeners: [], plugin_index: index };
}

// The plugins every workspace route carries. The ip lists stay on the route but are only enabled
// with at least one address (an empty allowed list would reject every call). Older routes get the
// missing plugins added, and the legacy ones removed, on their next update.
export function ensureStudioPlugins(route) {
  const plugins = (route.plugins || []).filter((x) => x.plugin !== LEGACY_STUDIO_CONSUMER_PLUGIN);
  const has = (p) => plugins.some((x) => x.plugin === p);
  const head = [];
  if (!has(IP_ALLOW_PLUGIN)) head.push(pluginInstance(IP_ALLOW_PLUGIN, { addresses: [] }, { validate_access: 0 }, false));
  if (!has(IP_BLOCK_PLUGIN)) head.push(pluginInstance(IP_BLOCK_PLUGIN, { addresses: [] }, { validate_access: 1 }, false));
  route.plugins = [...head, ...plugins];
  return route;
}

export function routeNeedsRepair(route) {
  const plugins = (route && route.plugins) || [];
  const has = (p) => plugins.some((x) => x.plugin === p);
  return !has(IP_ALLOW_PLUGIN) || !has(IP_BLOCK_PLUGIN) || has(LEGACY_STUDIO_CONSUMER_PLUGIN);
}

export function ipAddressesOf(route, plugin) {
  const p = findPlugin(route, plugin);
  return (p && p.config && p.config.addresses) || [];
}

export function setIpAddresses(route, plugin, addresses) {
  const p = findPlugin(ensureStudioPlugins(route), plugin);
  p.config = { ...(p.config || {}), addresses };
  p.enabled = addresses.length > 0;
  return route;
}

export async function createWorkspace({ name, description, slug }) {
  const wsId = randomId(12);
  const finalSlug = slugify(slug || name) || wsId;
  const loc = workspaceLocation(wsId);
  const { host, path } = exposureFor(finalSlug);

  const existing = await Resources.routes.list({ [`metadata.${META.kind}`]: 'workspace' });
  if (existing.some((r) => slugOf(r) === finalSlug)) {
    throw new Error(`a workspace already uses '${finalSlug}'`);
  }

  await Resources.teams.create({
    id: teamIdOf(wsId),
    tenant: loc.tenant,
    name: `AI Studio - ${name}`,
    description: description || '',
    tags: [],
    metadata: workspaceMetadata(wsId, 'team'),
  });

  const template = await Resources.routes.template();
  const route = {
    ...template,
    _loc: loc,
    id: routeIdOf(wsId),
    name,
    description: description || '',
    tags: [],
    metadata: workspaceMetadata(wsId, 'workspace'),
    enabled: true,
    debug_flow: false,
    capture: false,
    export_reporting: false,
    groups: ['default'],
    frontend: {
      ...(template.frontend || {}),
      domains: [`${host}${path}`],
      strip_path: true,
      exact: false,
    },
    backend: {
      ...(template.backend || {}),
      targets: [
        {
          id: 'target_1',
          hostname: 'request.otoroshi.io',
          port: 443,
          tls: true,
          weight: 1,
          backup: false,
          predicate: { type: 'AlwaysMatch' },
          protocol: 'HTTP/1.1',
          ip_address: null,
          tls_config: { certs: [], trusted_certs: [], enabled: false, loose: false, trust_all: false },
        },
      ],
      root: '/',
      rewrite: false,
      load_balancing: { type: 'RoundRobin' },
      client: {
        ...((template.backend && template.backend.client) || {}),
        call_timeout: 600000,
        call_and_stream_timeout: 600000,
        global_timeout: 600000,
        idle_timeout: 600000,
      },
    },
    plugins: [
      pluginInstance(IP_ALLOW_PLUGIN, { addresses: [] }, { validate_access: 0 }, false),
      pluginInstance(IP_BLOCK_PLUGIN, { addresses: [] }, { validate_access: 1 }, false),
      pluginInstance(CONSUMER_PRESET_PLUGIN, { ref: null, tags: [consumerTagOf(wsId)] }),
      pluginInstance(OPENAI_COMPAT_PLUGIN, {
        language_model_refs: [],
        audio_model_refs: [],
        image_model_refs: [],
        ocr_model_refs: [],
        embedding_model_refs: [],
        moderation_model_refs: [],
        context_refs: [],
        max_size_upload: 104857600,
        decode_images: false,
        use_open_response_for_responses: false,
        response_headers: false,
        response_headers_include_costs: true,
      }),
    ],
  };
  await Resources.routes.create(route);
  return wsId;
}

export async function updateWorkspaceRoute(wsId, mutate) {
  const route = await Resources.routes.get(routeIdOf(wsId));
  const updated = ensureStudioPlugins(mutate(structuredClone(route)) || route);
  await Resources.routes.update(updated);
  return updated;
}

export function setOpenAiConfig(route, patch) {
  const plugin = findPlugin(route, OPENAI_COMPAT_PLUGIN);
  if (plugin) plugin.config = { ...plugin.config, ...patch };
  return route;
}

// Recompute the refs of the OpenAI compatible plugin from the entities tagged for this workspace.
// Called after every mutation adding/removing a provider, a model or a preset.
export async function syncWorkspaceRefs(wsId) {
  const filter = workspaceFilter(wsId);
  const results = await Promise.all(MODALITIES.filter((m) => m.refs).map((m) => Resources[m.resource].list(filter)));
  const contexts = await Resources.contexts.list(filter);
  return updateWorkspaceRoute(wsId, (route) => {
    const current = (findPlugin(route, OPENAI_COMPAT_PLUGIN) || { config: {} }).config;
    // keep the existing order (the first text provider serves requests without an explicit model)
    const merge = (existing, ids) => [...(existing || []).filter((id) => ids.includes(id)), ...ids.filter((id) => !(existing || []).includes(id))];
    const patch = {};
    MODALITIES.filter((m) => m.refs).forEach((m, idx) => {
      const enabled = results[idx].filter((e) => !(e.metadata && e.metadata.ai_studio_disabled === 'true'));
      patch[m.refs] = merge(current[m.refs], enabled.map((e) => e.id));
    });
    patch.context_refs = merge(current.context_refs, contexts.map((c) => c.id));
    return setOpenAiConfig(route, patch);
  });
}

export async function deleteWorkspace(wsId) {
  const filter = workspaceFilter(wsId);
  const kinds = ['apikeys', 'budgets', ...MODALITIES.map((m) => m.resource), 'contexts', 'functions', 'mcpConnectors', 'searchEngines'];
  // the route first so nothing can be served while the rest is removed
  await Resources.routes.delete(routeIdOf(wsId)).catch(() => {});
  for (const kind of kinds) {
    const items = await Resources[kind].list(filter);
    await Promise.all(items.map((it) => Resources[kind].delete(it[Resources[kind].idField])));
  }
  await Resources.teams.delete(teamIdOf(wsId)).catch(() => {});
}

export async function workspaceCounts(wsId) {
  const filter = workspaceFilter(wsId);
  const [keys, providers] = await Promise.all([Resources.apikeys.list(filter), Resources.providers.list(filter)]);
  return { keys: keys.length, providers: providers.length };
}
