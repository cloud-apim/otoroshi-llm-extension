#!/usr/bin/env node
// Refreshes the data files bundled in src/main/resources/data from their upstream sources:
//
// - ltllm-prices.json   the LiteLLM price table (costs tracking)
// - catalog.json        the models.dev catalog (models metadata, costs tracking fallback)
// - eg-models.json      the EcoLogits models (environmental impacts)
// - eg-elec.json        the EcoLogits electricity mixes (environmental impacts)
//
// Every file is downloaded, parsed and checked before it replaces the bundled one, and a file that lost more
// than 20% of its entries is refused: an upstream breakage should not silently empty a price table.
//
// usage: node scripts/update-data-files.mjs [--only litellm,models.dev,ecologits] [--check] [--force] [--out <dir>]
//
//   --only   the sources to refresh (default: all of them)
//   --check  downloads and checks everything, writes nothing
//   --force  writes a file even when it lost more than 20% of its entries
//   --out    writes the files in another directory than src/main/resources/data
//
// Requires node 18 or later (global fetch), no dependency.

import { existsSync, readFileSync, renameSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const DEFAULT_OUT = join(ROOT, 'src', 'main', 'resources', 'data');
const TIMEOUT_MS = 120_000;
const RETRIES = 2;
const MAX_SHRINK = 0.2;

const isObject = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);

function invalid(message) {
  throw new Error(`unexpected content: ${message}`);
}

const MODELS_DEV_URL = 'https://models.dev/catalog.json';

const SOURCES = [
  {
    source: 'litellm',
    file: 'ltllm-prices.json',
    url: 'https://raw.githubusercontent.com/BerriAI/litellm/refs/heads/main/model_prices_and_context_window.json',
    // models, the documentation entry aside
    count: (json) => {
      if (!isObject(json) || !isObject(json.sample_spec)) invalid('no `sample_spec` entry');
      const entries = Object.entries(json).filter(([name]) => name !== 'sample_spec');
      if (entries.some(([, v]) => !isObject(v))) invalid('an entry is not an object');
      return entries.length;
    },
  },
  {
    source: 'models.dev',
    file: 'catalog.json',
    url: MODELS_DEV_URL,
    // models served by the providers, the section the gateway reads first
    count: (json) => {
      if (!isObject(json) || !isObject(json.providers)) invalid('no `providers` object');
      if (json.models !== undefined && !isObject(json.models)) invalid('`models` is not an object');
      return Object.values(json.providers).reduce((acc, p) => {
        if (!isObject(p) || !isObject(p.models)) invalid('a provider has no `models` object');
        return acc + Object.keys(p.models).length;
      }, 0);
    },
    // served minified: kept readable, and tagged with where it comes from
    format: (json) => JSON.stringify({ origin: MODELS_DEV_URL, ...json }, null, 4) + '\n',
  },
  {
    source: 'ecologits',
    file: 'eg-models.json',
    url: 'https://raw.githubusercontent.com/mlco2/ecologits/refs/heads/main/ecologits/data/models.json',
    count: (json) => {
      if (!isObject(json) || !Array.isArray(json.models)) invalid('no `models` array');
      if (json.aliases !== undefined && !Array.isArray(json.aliases)) invalid('`aliases` is not an array');
      return json.models.length;
    },
  },
  {
    source: 'ecologits',
    file: 'eg-elec.json',
    url: 'https://raw.githubusercontent.com/mlco2/ecologits/refs/heads/main/ecologits/data/electricity_mixes.json',
    count: (json) => {
      if (!isObject(json) || !Array.isArray(json.electricity_mixes)) invalid('no `electricity_mixes` array');
      if (json.electricity_mixes.some((m) => !isObject(m) || typeof m.name !== 'string')) invalid('an electricity mix has no name');
      return json.electricity_mixes.length;
    },
  },
];

function parseArgs(argv) {
  const args = { only: null, check: false, force: false, out: DEFAULT_OUT };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--check') args.check = true;
    else if (arg === '--force') args.force = true;
    else if (arg === '--only') args.only = (argv[++i] || '').split(',').map((s) => s.trim()).filter(Boolean);
    else if (arg === '--out') args.out = resolve(argv[++i] || '');
    else if (arg === '-h' || arg === '--help') {
      console.log('usage: node scripts/update-data-files.mjs [--only litellm,models.dev,ecologits] [--check] [--force] [--out <dir>]');
      process.exit(0);
    } else {
      console.error(`unknown argument: ${arg}`);
      process.exit(2);
    }
  }
  const known = [...new Set(SOURCES.map((s) => s.source))];
  const unknown = (args.only || []).filter((s) => !known.includes(s));
  if (unknown.length) {
    console.error(`unknown source(s): ${unknown.join(', ')}, expected ${known.join(', ')}`);
    process.exit(2);
  }
  return args;
}

async function download(url) {
  let lastError;
  for (let attempt = 0; attempt <= RETRIES; attempt++) {
    try {
      const res = await fetch(url, { signal: AbortSignal.timeout(TIMEOUT_MS), headers: { 'User-Agent': 'otoroshi-llm-extension-data-update' } });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      return await res.text();
    } catch (e) {
      lastError = e;
      if (attempt < RETRIES) await new Promise((r) => setTimeout(r, 1000 * (attempt + 1)));
    }
  }
  throw new Error(`download failed: ${lastError.message}`);
}

function currentCount(entry, path) {
  if (!existsSync(path)) return null;
  try {
    return entry.count(JSON.parse(readFileSync(path, 'utf8')));
  } catch (e) {
    return null;
  }
}

const kb = (bytes) => `${Math.round(bytes / 1024)} kB`;

async function refresh(entry, args) {
  const target = join(args.out, entry.file);
  const text = await download(entry.url);
  let json;
  try {
    json = JSON.parse(text);
  } catch (e) {
    throw new Error(`not json: ${e.message}`);
  }
  const count = entry.count(json);
  if (count === 0) invalid('no entry at all');
  const before = currentCount(entry, target);
  if (before !== null && count < before * (1 - MAX_SHRINK) && !args.force) {
    throw new Error(`${count} entries instead of ${before}, refused (use --force to write it anyway)`);
  }
  const content = entry.format ? entry.format(json) : text;
  const previous = existsSync(target) ? readFileSync(target, 'utf8') : null;
  const changed = previous !== content;
  if (changed && !args.check) {
    mkdirSync(args.out, { recursive: true });
    const tmp = `${target}.tmp`;
    writeFileSync(tmp, content);
    renameSync(tmp, target);
  }
  const counts = before === null ? `${count} entries` : `${before} -> ${count} entries`;
  const status = !changed ? 'unchanged' : args.check ? 'would be updated' : 'updated';
  return { changed, message: `${counts}, ${kb(Buffer.byteLength(content))}, ${status}` };
}

async function main() {
  if (typeof fetch !== 'function') {
    console.error('node 18 or later is required');
    process.exit(2);
  }
  const args = parseArgs(process.argv.slice(2));
  const entries = SOURCES.filter((s) => !args.only || args.only.includes(s.source));
  console.log(`${args.check ? 'checking' : 'refreshing'} ${entries.length} file(s) in ${args.out}`);
  const results = await Promise.allSettled(entries.map((e) => refresh(e, args)));
  let failures = 0;
  let written = 0;
  results.forEach((r, i) => {
    const entry = entries[i];
    if (r.status === 'fulfilled') {
      if (r.value.changed) written++;
      console.log(`  ok    ${entry.file.padEnd(18)} ${r.value.message}`);
    } else {
      failures++;
      console.log(`  error ${entry.file.padEnd(18)} ${r.reason.message} (${entry.url})`);
    }
  });
  if (!args.check && written > 0) {
    console.log('\nprices and catalogs changed: run the costs and metadata suites before committing:');
    console.log('  sbt "testOnly *ModelsMetadataSuite *CostsTestSuite *RequiredCostsSuite *OpenRouterCostsSuite *NonTextCostsSuite *StudioApiSuite"');
  }
  process.exit(failures ? 1 : 0);
}

main();
