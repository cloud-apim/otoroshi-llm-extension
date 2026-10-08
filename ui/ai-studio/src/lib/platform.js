// What differs between the editions of AI Studio, set at startup (main.jsx): the links of the console, the pages
// an edition adds, and what the signed-in user can do (`can`: what the pages offer, the api still decides).
export const platform = {
  edition: 'oss',
  features: {},
  // what the user can do outside of a workspace (`workspaces:create`, `admin`)
  permissions: ['workspaces:create'],
  // what the user can do on a workspace: the permissions the api gives with it (see PERMISSIONS in core/ops.js)
  can: (permission, workspace) => {
    const granted = (workspace && workspace.permissions) || [];
    return (Array.isArray(permission) ? permission : [permission]).some((p) => granted.includes(p));
  },
  // pages added to the workspace menu: { id, label, icon, component, permission, after }
  pages: [],
  // pages outside of the workspaces: { path, component, permission } (a global permission, see `permissions`)
  routes: [],
  // elements added to the top bar, before the theme menu
  topbar: [],
  // shown next to the logo
  experimental: false,
  // the Otoroshi admin console (routes, data exporters), none when the studio is served on its own
  links: { admin: null, logout: null },
};

export function installPlatform(values) {
  Object.assign(platform, values, { links: { ...platform.links, ...((values && values.links) || {}) } });
}

// a page of the Otoroshi admin console, when there is one
export const adminLink = (path) => (platform.links.admin ? `${platform.links.admin}${path}` : null);

export const hasPermission = (permission) => platform.permissions.includes(permission);
