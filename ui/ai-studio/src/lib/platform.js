// What differs between the editions of AI Studio, set at startup (main.jsx): the links of the console, and what
// the signed-in user can do on a workspace (`can`: what the pages offer, the server still decides).
export const platform = {
  edition: 'oss',
  features: {},
  can: () => true,
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
