import { bootstrap, currentTenant } from './bootstrap';
import { META, Resources, workspaceFilter } from './entities';

// A workspace is not an entity by itself: it is the combination of a team (owning every entity of
// the workspace), a route (the OpenAI-compatible endpoint) and all the entities tagged with
// `metadata.ai_studio_workspace = <id>`. The studio admin api (studio/api.scala) creates, reads and
// deletes workspaces; what is left here builds the entities of the pages not moved to it yet.

export const OPENAI_COMPAT_PLUGIN = 'cp:otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.OpenAiCompatApi';
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
  { id: 'decision', label: 'Decision', resource: 'decisionModels', refs: 'decision_model_refs', kind: 'decision-model' },
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
