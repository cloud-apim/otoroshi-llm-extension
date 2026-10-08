import { routeOf, STUDIO_ADMIN_PATH } from '../core/ops';
import { api, request, STUDIO_API } from './api';

// What the pages call: the operations of a workspace (core/ops.js), the workspaces themselves, the provider
// catalog, and the urls the chat streams from. The edition decides how (main.jsx): the OSS studio installs the
// local backend below, AI Studio Enterprise one that asks its own server.
export const backend = {};

export function installBackend(impl) {
  Object.assign(backend, impl);
}

// the studio admin api, through the backoffice session: what it does is checked against the rights of the
// signed-in user
const ADMIN_API = `/bo/api/proxy${STUDIO_ADMIN_PATH}`;

export function localBackend() {
  return {
    run: (name, wsId, input) => {
      const route = routeOf(name, wsId, input);
      return request(route.method, `${ADMIN_API}${route.path}`, route.body);
    },
    workspaces: {
      list: () => api.get(`${ADMIN_API}/workspaces`),
      create: (form) => api.post(`${ADMIN_API}/workspaces`, form),
    },
    catalog: () => api.get(`${ADMIN_API}/catalog`),
    // the preferences of the backoffice user (the theme)
    // a json value: the api client sends a string body as it is, and `light` is no json
    prefs: { set: (key, value) => api.post(`/bo/api/me/preferences/${key}`, JSON.stringify(value)) },
    // the chat and the conversations of the signed-in user, served by the backoffice routes of the studio
    urls: {
      proxy: (wsId, path) => `${STUDIO_API}/workspaces/${wsId}/proxy${path}`,
      conversations: (wsId) => `${STUDIO_API}/workspaces/${wsId}/conversations`,
    },
  };
}
