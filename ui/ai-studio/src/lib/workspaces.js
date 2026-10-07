import { bootstrap } from './bootstrap';

// A workspace is not an entity by itself: it is the combination of a team, a route (the OpenAI-compatible
// endpoint) and the entities tagged with `metadata.ai_studio_workspace = <id>`, all of them built by the studio
// admin api (studio/api.scala). The front only needs to tell where a slug would be exposed.

export function exposureFor(slug) {
  const c = bootstrap.config;
  const routePath = c.route_path || '/v1';
  if (c.exposure === 'path') {
    return { host: c.domain, path: `/${slug}${routePath}` };
  }
  return { host: `${slug}.${c.domain}`, path: routePath };
}
