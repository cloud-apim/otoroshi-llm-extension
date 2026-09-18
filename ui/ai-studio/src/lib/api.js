import { currentTenant } from './bootstrap';

export class ApiError extends Error {
  constructor(message, status, body) {
    super(message);
    this.status = status;
    this.body = body;
  }
}

function errorMessage(body, status) {
  if (!body) return `request failed with status ${status}`;
  if (typeof body === 'string') return body.substring(0, 300);
  return body.error_description || body.message || body.error || JSON.stringify(body).substring(0, 300);
}

// every call goes through the backoffice session: the admin api is reached through `/bo/api/proxy`
// which applies the tenant/teams rights of the logged user
export async function request(method, url, body, opts = {}) {
  const headers = {
    Accept: 'application/json',
    'Otoroshi-Tenant': currentTenant(),
    ...(opts.headers || {}),
  };
  const init = { method, headers, credentials: 'include', signal: opts.signal };
  if (body !== undefined) {
    headers['Content-Type'] = opts.contentType || 'application/json';
    init.body = typeof body === 'string' ? body : JSON.stringify(body);
  }
  const res = await fetch(url, init);
  if (opts.raw) return res;
  const text = await res.text();
  let parsed = null;
  try {
    parsed = text ? JSON.parse(text) : null;
  } catch (e) {
    parsed = text;
  }
  if (res.status === 401) {
    window.location.reload();
  }
  if (!res.ok) {
    throw new ApiError(errorMessage(parsed, res.status), res.status, parsed);
  }
  return parsed;
}

export const api = {
  get: (url, opts) => request('GET', url, undefined, opts),
  post: (url, body, opts) => request('POST', url, body, opts),
  put: (url, body, opts) => request('PUT', url, body, opts),
  patch: (url, body, opts) => request('PATCH', url, body, opts),
  delete: (url, opts) => request('DELETE', url, undefined, opts),
};

/**
 * The error of a failed call on the workspace endpoint, as an `Error` to throw. Providers answer in the
 * OpenAI shape (`error.message`), the other endpoints in the otoroshi one, where `error` is a code and
 * `error_details` says what actually happened — a reader only showing `error` would say `internal_error`.
 */
export function gatewayError(text, status, statusText) {
  let message = text;
  try {
    const json = JSON.parse(text);
    const detail = json.error_details || json.error_description || json.message;
    const error = json.error && (json.error.message || json.error);
    const both = typeof error === 'string' && typeof detail === 'string' && detail ? `${error}: ${detail}` : null;
    message = both || detail || error || text;
    if (typeof message !== 'string') message = JSON.stringify(message);
  } catch (e) {}
  return new Error(`${status} - ${message || statusText}`);
}

export const STUDIO_API = '/extensions/cloud-apim/extensions/ai-extension/studio';
export const EXT_ADMIN_API = '/bo/api/proxy/api/extensions/cloud-apim/extensions/ai-extension';
export const EXT_BO_API = '/extensions/cloud-apim/extensions/ai-extension';
