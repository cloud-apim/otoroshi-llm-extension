#!/usr/bin/env node
// Refreshes the data files bundled in src/main/resources/data from their upstream sources:
//
// - ltllm-prices.json   the LiteLLM price table (costs tracking)
// - catalog.json        the models.dev catalog (models metadata, costs tracking fallback)
// - eg-models.json      the EcoLogits models (environmental impacts)
// - eg-elec.json        the EcoLogits electricity mixes (environmental impacts)
// - coding-index.json   the Coding Index of Artificial Analysis (code router)
//
// Every file is downloaded, parsed and checked before it replaces the bundled one, and a file that lost more
// than 20% of its entries is refused: an upstream breakage should not silently empty a price table.
//
// usage: node scripts/update-data-files.mjs [--only litellm,models.dev,ecologits,artificialanalysis] [--check] [--force] [--out <dir>]
//
//   --only   the sources to refresh (default: all of them)
//   --check  downloads and checks everything, writes nothing
//   --force  writes a file even when it lost more than 20% of its entries
//   --out    writes the files in another directory than src/main/resources/data
//
// The Coding Index comes from the free data api of Artificial Analysis (https://artificialanalysis.ai/documentation),
// which needs a key: set ARTIFICIAL_ANALYSIS_API_KEY. Without it that file is left as it is, and the other ones are
// refreshed. Their data must be attributed to https://artificialanalysis.ai/, which the documentation of the router does.
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
const ARTIFICIAL_ANALYSIS_URL = 'https://artificialanalysis.ai/api/v2/data/llms/models';
const ARTIFICIAL_ANALYSIS_KEY = 'ARTIFICIAL_ANALYSIS_API_KEY';
const CODING_INDEX = 'artificial_analysis_coding_index';

// The models Artificial Analysis gave a coding index, as the bundled file keeps them: its api answers with
// `data`, a list of models with their `evaluations`, the bundled file is `{ models: [{ slug, coding_index }] }`
function codingIndexed(json) {
  if (!isObject(json)) invalid('not an object');
  if (Array.isArray(json.models)) return json.models.filter((m) => isObject(m) && typeof m.slug === 'string' && typeof m.coding_index === 'number');
  if (!Array.isArray(json.data)) invalid('no `data` array');
  if (json.data.some((m) => !isObject(m) || typeof m.slug !== 'string')) invalid('a model has no slug');
  return json.data
    .filter((m) => isObject(m.evaluations) && typeof m.evaluations[CODING_INDEX] === 'number')
    .map((m) => ({
      slug: m.slug,
      name: m.name,
      creator: isObject(m.model_creator) ? m.model_creator.slug : undefined,
      coding_index: m.evaluations[CODING_INDEX],
    }));
}

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
  {
    source: 'artificialanalysis',
    file: 'coding-index.json',
    url: process.env.ARTIFICIAL_ANALYSIS_API_URL || ARTIFICIAL_ANALYSIS_URL,
    // the name of the environment variable holding the api key this source cannot be read without
    key: ARTIFICIAL_ANALYSIS_KEY,
    headers: () => ({ 'x-api-key': process.env[ARTIFICIAL_ANALYSIS_KEY] }),
    count: (json) => codingIndexed(json).length,
    // the scores alone, the best coders first: what the router reads, in an order that does not move for nothing
    format: (json) => {
      const models = codingIndexed(json).sort((a, b) => b.coding_index - a.coding_index || a.slug.localeCompare(b.slug));
      return JSON.stringify({ origin: 'https://artificialanalysis.ai/', index: CODING_INDEX, models }, null, 2) + '\n';
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
      console.log('usage: node scripts/update-data-files.mjs [--only litellm,models.dev,ecologits,artificialanalysis] [--check] [--force] [--out <dir>]');
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

async function download(url, headers = {}) {
  let lastError;
  for (let attempt = 0; attempt <= RETRIES; attempt++) {
    try {
      const res = await fetch(url, { signal: AbortSignal.timeout(TIMEOUT_MS), headers: { 'User-Agent': 'otoroshi-llm-extension-data-update', ...headers } });
      if (res.status === 401 || res.status === 403) {
        // a refused key will be refused again
        lastError = new Error(`HTTP ${res.status}, the api key was refused`);
        break;
      }
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
  if (entry.key && !process.env[entry.key]) throw new Error(`${entry.key} is not set`);
  const target = join(args.out, entry.file);
  const text = await download(entry.url, entry.headers ? entry.headers() : {});
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
  const wanted = SOURCES.filter((s) => !args.only || args.only.includes(s.source));
  // a source that needs a key is left alone without it, unless it was asked for by name
  const locked = wanted.filter((s) => s.key && !process.env[s.key] && !args.only);
  const entries = wanted.filter((s) => !locked.includes(s));
  console.log(`${args.check ? 'checking' : 'refreshing'} ${entries.length} file(s) in ${args.out}`);
  locked.forEach((s) => console.log(`  skip  ${s.file.padEnd(18)} set ${s.key} to refresh it`));
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
    console.log('\nprices, catalogs or scores changed: run the costs, metadata and coding index suites before committing:');
    console.log('  sbt "testOnly *CodingIndexSuite *ModelsMetadataSuite *CostsTestSuite *RequiredCostsSuite *OpenRouterCostsSuite *NonTextCostsSuite *StudioApiSuite *ChatHandOverSuite *GuardrailsAccountingSuite *ModerationUsageSuite *DecisionModelsSuite *DecisionModelsResilienceSuite"');
  }
  process.exit(failures ? 1 : 0);
}

main();
