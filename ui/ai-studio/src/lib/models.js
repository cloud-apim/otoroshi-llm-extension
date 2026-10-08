import { api } from './api';
import { backend } from './backend';

let catalogPromise = null;

// provider catalog enriched for the BYOK form (see studio/catalog.scala)
export function loadCatalog() {
  if (!catalogPromise) {
    catalogPromise = backend.catalog().then((r) => r.providers || []).catch((e) => {
      catalogPromise = null;
      throw e;
    });
  }
  return catalogPromise;
}

// models reachable through the workspace endpoint, ids are the ones to use in api calls
export function listWorkspaceModels(workspace, force = false) {
  return backend.run('models.list', workspace.id, { query: { force } });
}

export function listConversations(workspace) {
  return api.get(backend.urls.conversations(workspace.id));
}

export function getConversation(workspace, id) {
  return api.get(`${backend.urls.conversations(workspace.id)}/${id}`);
}

export function saveConversation(workspace, conversation) {
  return api.put(`${backend.urls.conversations(workspace.id)}/${conversation.id}`, conversation);
}

export function deleteConversation(workspace, id) {
  return api.delete(`${backend.urls.conversations(workspace.id)}/${id}`);
}

// A call of the studio on the workspace endpoint, which always shows what it cost: `embed_costs` puts the
// price of the call in the answer, whatever `embed-costs-tracking-in-responses` is set to on the instance.
export function billedProxyUrl(workspace, path) {
  const url = backend.urls.proxy(workspace.id, path);
  return `${url}${path.includes('?') ? '&' : '?'}embed_costs=true`;
}
