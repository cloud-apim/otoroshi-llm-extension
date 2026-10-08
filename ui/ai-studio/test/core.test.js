// The operations registry and the secrets of its views (src/core), run with `npm test` (node --test).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { ops, PERMISSIONS, routeOf } from '../src/core/ops.js';
import { maskSecrets, SECRET_FIELDS, SECRET_SENTINEL } from '../src/core/secrets.js';

// the routes of the studio admin api, read from its source: `route("GET", "/workspaces/:id/providers")`
const API = readFileSync(new URL('../../../src/main/scala/com/cloud/apim/otoroshi/extensions/aigateway/studio/api.scala', import.meta.url), 'utf8');
const ROUTES = [...API.matchAll(/route\("(GET|POST|PUT|PATCH|DELETE)", "([^"]+)"/g)].map(([, method, path]) => `${method} ${path}`);

test('every operation declares a known access', () => {
  Object.entries(ops).forEach(([name, op]) => assert.ok(PERMISSIONS.includes(op.access), name));
});

test('every operation is served by a route of the studio admin api', () => {
  assert.ok(ROUTES.length > 40, 'the routes of api.scala are read');
  Object.entries(ops).forEach(([name, op]) => {
    const path = `/workspaces/:id${op.path}`.replace(/:(cid|kid|bid|lid|rid|pid|tid|kind)/g, ':$1');
    assert.ok(ROUTES.includes(`${op.method} ${path}`), `${name}: ${op.method} ${path}`);
  });
});

test('the route of an operation takes its params, its query and its body from the input', () => {
  const route = routeOf('tools.update', 'ws 1', { kind: 'functions', tid: 'tool/1', query: { force: true, enriched: false, none: null }, body: { name: 'x' } });
  assert.deepEqual(route, { method: 'PUT', path: '/workspaces/ws%201/tools/functions/tool%2F1?force=true', body: { name: 'x' } });
  assert.throws(() => routeOf('tools.update', 'ws', { kind: 'functions' }), /needs 'tid'/);
  assert.throws(() => routeOf('nope', 'ws'), /unknown operation/);
  assert.throws(() => routeOf('constructor', 'ws'), /unknown operation/);
});

test('an id never takes the route of an operation above its own', () => {
  assert.throws(() => routeOf('keys.delete', 'ws', { kid: '..' }), /invalid 'kid'/);
  assert.throws(() => routeOf('keys.delete', 'ws', { kid: ['..'] }), /invalid 'kid'/);
  assert.throws(() => routeOf('keys.delete', 'ws', { kid: '.' }), /invalid 'kid'/);
  assert.throws(() => routeOf('workspace.get', '..'), /invalid 'workspace'/);
  assert.equal(routeOf('keys.delete', 'ws', { kid: '../providers/x' }).path, '/workspaces/ws/apikeys/..%2Fproviders%2Fx');
  assert.equal(new URL(`http://h${routeOf('keys.delete', 'ws', { kid: '...' }).path}`).pathname, '/workspaces/ws/apikeys/...');
});

test('declared secrets apply to known operations', () => {
  Object.keys(SECRET_FIELDS).forEach((name) => assert.ok(ops[name], name));
});

test('the secrets of a view are masked, vault references and empty values are not', () => {
  const connections = [{ id: 'c1', token: 'sk-1', base_url: 'https://x' }, { id: 'c2', token: '${vault://env/KEY}' }, { id: 'c3', token: '' }];
  assert.deepEqual(maskSecrets('providers.list', connections), [
    { id: 'c1', token: SECRET_SENTINEL, base_url: 'https://x' },
    { id: 'c2', token: '${vault://env/KEY}' },
    { id: 'c3', token: '' },
  ]);
  const tool = { id: 't', url: 'https://x', headers: { Authorization: 'Bearer s', 'X-Trace': '1' } };
  assert.deepEqual(maskSecrets('tools.update', tool).headers, { Authorization: SECRET_SENTINEL, 'X-Trace': SECRET_SENTINEL });
});

test('the secret of an api key is shown on creation, on reset and on reveal only', () => {
  const key = { client_id: 'k', client_secret: 's', bearer: 'otoapk_x', name: 'app' };
  assert.equal(maskSecrets('keys.list', [key])[0].client_secret, SECRET_SENTINEL);
  assert.equal(maskSecrets('keys.get', key).bearer, SECRET_SENTINEL);
  assert.equal(maskSecrets('keys.create', key).client_secret, 's');
  assert.equal(maskSecrets('keys.resetSecret', key).bearer, 'otoapk_x');
  assert.equal(maskSecrets('keys.reveal', key).client_secret, 's');
  assert.equal(maskSecrets('mykeys.list', [key])[0].bearer, SECRET_SENTINEL);
  assert.equal(maskSecrets('mykeys.update', key).client_secret, SECRET_SENTINEL);
  assert.equal(maskSecrets('mykeys.create', key).client_secret, 's');
  assert.equal(maskSecrets('mykeys.reveal', key).bearer, 'otoapk_x');
});

test('what belongs to the person calling is read on the routes of the whole workspace', () => {
  const own = Object.entries(ops).filter(([, op]) => op.own);
  assert.deepEqual(own.map(([name]) => name).sort(), ['mybudgets.list', 'mykeys.create', 'mykeys.delete', 'mykeys.list', 'mykeys.resetSecret', 'mykeys.reveal', 'mykeys.update']);
  // never the access of who reads or writes the configuration
  own.forEach(([name, op]) => assert.ok(['keys:own', 'usage:own'].includes(op.access), name));
});

test('what reads the configuration has every field named like a secret masked, numbers aside', () => {
  const masked = maskSecrets('modelEntities.list', [{ id: 'e', name: 'x', api_key: 'leak', password: 'p', tokens: 1000, nested: { authorization: 'Bearer y' } }]);
  assert.deepEqual(masked, [{ id: 'e', name: 'x', api_key: SECRET_SENTINEL, password: SECRET_SENTINEL, tokens: 1000, nested: { authorization: SECRET_SENTINEL } }]);
  // what is not a configuration read is left alone
  assert.deepEqual(maskSecrets('analytics.query', { token: 'x' }), { token: 'x' });
});
