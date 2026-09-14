import { api, STUDIO_API } from './api';

let catalogPromise = null;

// provider catalog enriched for the BYOK form (see studio/catalog.scala)
export function loadCatalog() {
  if (!catalogPromise) {
    catalogPromise = api.get(`${STUDIO_API}/catalog`).then((r) => r.providers || []).catch((e) => {
      catalogPromise = null;
      throw e;
    });
  }
  return catalogPromise;
}

// models reachable through the workspace endpoint, ids are the ones to use in api calls
export function listWorkspaceModels(workspace, force = false) {
  return api.get(`${STUDIO_API}/workspaces/${workspace.id}/models${force ? '?force=true' : ''}`);
}

export function listConversations(workspace) {
  return api.get(`${STUDIO_API}/workspaces/${workspace.id}/conversations`);
}

export function getConversation(workspace, id) {
  return api.get(`${STUDIO_API}/workspaces/${workspace.id}/conversations/${id}`);
}

export function saveConversation(workspace, conversation) {
  return api.put(`${STUDIO_API}/workspaces/${workspace.id}/conversations/${conversation.id}`, conversation);
}

export function deleteConversation(workspace, id) {
  return api.delete(`${STUDIO_API}/workspaces/${workspace.id}/conversations/${id}`);
}

export function proxyUrl(workspace, path) {
  return `${STUDIO_API}/workspaces/${workspace.id}/proxy${path}`;
}
