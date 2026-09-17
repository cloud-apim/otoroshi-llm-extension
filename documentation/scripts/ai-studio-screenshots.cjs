/*
 * Captures the AI Studio screenshots of the documentation (documentation/static/img/ai-studio-*.png).
 *
 * Full-page, 1393px wide, dark theme, from a running Otoroshi with the extension installed and a
 * workspace that has some traffic. The backoffice credentials are read from the environment and only
 * used to open a session:
 *
 *   OTO_USER=... OTO_PASSWORD=... \
 *   PLAYWRIGHT_MODULE=/path/to/node_modules/playwright \
 *   node documentation/scripts/ai-studio-screenshots.cjs
 *
 * Optional: OTO_URL (default http://otoroshi.oto.tools:9999), WS_ID (the workspace to shoot),
 * PROFILE_EMAIL (the user whose profile is captured), SESSION_ID (the session whose call is shown in
 * the log details), CHAT_TITLE and COMPARE_TITLE (a part of the title of the conversation shown in the
 * chat, and of the comparison shown side by side, the most recent ones otherwise), OTO_TENANT (default
 * `default`), ONLY=overview,logs (a subset).
 *
 * The chat shots show conversations of the signed-in user: have a plain conversation and a comparison of
 * two or three models in the workspace before running the script.
 */
const path = require('path');

// the documentation has no dependency of its own on playwright: any install will do
function loadPlaywright() {
  const candidates = [process.env.PLAYWRIGHT_MODULE, 'playwright'].filter(Boolean);
  for (const candidate of candidates) {
    try {
      return require(candidate);
    } catch (e) {
      // next one
    }
  }
  console.error(
    'playwright not found. Set PLAYWRIGHT_MODULE to an installed copy, on the same command line as node, e.g.\n' +
      '  PLAYWRIGHT_MODULE=/path/to/node_modules/playwright node documentation/scripts/ai-studio-screenshots.cjs'
  );
  process.exit(1);
}

const BASE = process.env.OTO_URL || 'http://otoroshi.oto.tools:9999';
const WS = process.env.WS_ID || 'v4e46mctfxxw';
const PROFILE_EMAIL = process.env.PROFILE_EMAIL || 'admin@otoroshi.io';
const SESSION = process.env.SESSION_ID || 'support-conversation-1';
const TENANT = process.env.OTO_TENANT || 'default';
const OUT = path.resolve(__dirname, '../static/img');
const WIDTH = 1393;
const HEIGHT = 911;
const ONLY = (process.env.ONLY || '').split(',').map((s) => s.trim()).filter(Boolean);

const studio = (p) => `${BASE}/extensions/cloud-apim/ai-studio${p}`;
const ws = (p) => studio(`/workspaces/${WS}${p}`);

const clickFirst = (selector) => async (page) => {
  await page.locator(selector).first().click();
  await page.waitForTimeout(1200);
};

const clickAll = (selector) => async (page) => {
  for (const button of await page.locator(selector).all()) {
    await button.click();
  }
  await page.waitForTimeout(1200);
};

// a workspace on self-hosted models spends nothing: the views opening on the spend show token volumes instead
const showTokens = '.segmented button:text-is("Tokens")';

// Opens the most recent conversation of the user that is a comparison (or not), or the first one whose
// title contains `title`. The rooms of the chat are listed in the order of the api, so the conversation
// is clicked by its position: titles often repeat.
const openConversation = ({ compare, title }) => async (page) => {
  const api = `${BASE}/extensions/cloud-apim/extensions/ai-extension/studio/workspaces/${WS}/conversations`;
  const headers = { Accept: 'application/json', 'Otoroshi-Tenant': TENANT };
  const res = await page.request.get(api, { headers });
  if (!res.ok()) throw new Error(`cannot list the conversations: HTTP ${res.status()}`);
  const rooms = await res.json();
  let index = -1;
  for (let i = 0; i < rooms.length && index < 0; i++) {
    if (title && !(rooms[i].title || '').toLowerCase().includes(title.toLowerCase())) continue;
    const conversation = await (await page.request.get(`${api}/${encodeURIComponent(rooms[i].id)}`, { headers })).json();
    const hasAnswer = (conversation.messages || []).some((m) => m.role === 'assistant');
    if (hasAnswer && !!conversation.compare === compare) index = i;
  }
  if (index < 0) {
    throw new Error(`no ${compare ? 'comparison' : 'plain conversation'} with an answer${title ? ` titled "${title}"` : ''} in the chat of the user`);
  }
  await page.locator('.room').nth(index).click();
  await page.waitForTimeout(1500);
};

const sortModels = (value) => async (page) => {
  await page.locator('select[aria-label="Sort models"]').selectOption(value);
  await page.waitForTimeout(800);
};

const steps = (...fns) => async (page) => {
  for (const fn of fns) await fn(page);
};

// the edit form of a key limited to two models and expiring in 90 days, closed without saving
const editKey = async (page) => {
  const modal = page.locator('dialog.modal');
  await modal.locator('select').first().selectOption('90');
  await modal.locator('.segmented button:text-is("Selected models")').click();
  const models = modal.locator('.model-checklist-items input[type="checkbox"]');
  await models.first().waitFor({ timeout: 20000 });
  for (let i = 0; i < Math.min(2, await models.count()); i++) await models.nth(i).check();
  await page.waitForTimeout(500);
};

// `element` narrows the capture to one block of the page; `viewport` keeps an overlay (modal, drawer)
// in frame instead of capturing the whole scrolled page
const SHOTS = [
  { name: 'workspaces', url: studio('/'), viewport: true },
  { name: 'overview', url: ws('/overview') },
  { name: 'providers', url: ws('/providers') },
  // a workspace easily reaches a hundred models: the first screen says it all
  { name: 'models', url: ws('/models'), viewport: true },
  // how the models behave in the workspace, the busiest first (needs user analytics)
  { name: 'models-health', url: ws('/models'), viewport: true, before: sortModels('calls') },
  // the estimate stays on for the next visits of the page (local storage): keep this shot after the other models ones
  {
    name: 'models-estimate',
    url: ws('/models'),
    viewport: true,
    before: steps(clickFirst('button:has-text("Estimate costs")'), clickFirst('.estimate-panel button:text-is("RAG answer")')),
  },
  { name: 'chat', url: ws('/chat'), viewport: true, before: openConversation({ compare: false, title: process.env.CHAT_TITLE }) },
  { name: 'chat-compare', url: ws('/chat'), viewport: true, before: openConversation({ compare: true, title: process.env.COMPARE_TITLE }) },
  { name: 'api-keys', url: ws('/keys') },
  { name: 'api-key-edit', url: ws('/keys'), viewport: true, before: steps(clickFirst('td.actions button:text-is("Edit")'), editKey) },
  { name: 'guardrails', url: ws('/guardrails') },
  { name: 'routing', url: ws('/routing') },
  { name: 'tools', url: ws('/tools') },
  { name: 'presets', url: ws('/presets') },
  { name: 'mcp-server', url: ws('/mcp-server') },
  { name: 'credits', url: ws('/credits') },
  { name: 'budget-edit', url: ws('/credits'), viewport: true, before: clickFirst('button:has-text("Edit")') },
  { name: 'activity', url: ws('/activity?period=24h') },
  { name: 'activity-impact', url: ws('/activity?period=24h'), element: 'div.stack:has(> div > h2:has-text("Environmental impact"))' },
  { name: 'activity-trends', url: ws('/activity?tab=trends&period=24h'), before: clickAll(showTokens) },
  { name: 'activity-explore', url: ws('/activity?tab=explore&period=24h&metric=tokens&by=model&sub=apikey') },
  { name: 'activity-mcp', url: ws('/activity?tab=mcp&period=24h') },
  { name: 'user-profile', url: ws(`/users/${encodeURIComponent(PROFILE_EMAIL)}`), before: clickFirst(showTokens) },
  { name: 'logs', url: ws('/logs?period=24h') },
  // a call of a session, so the details show more than dashes
  { name: 'log-details', url: ws(`/logs?period=24h&session=${SESSION}`), viewport: true, before: clickFirst('tr.clickable') },
  { name: 'settings', url: ws('/settings') },
];

async function settle(page) {
  await page.waitForLoadState('networkidle', { timeout: 20000 }).catch(() => {});
  await page
    .waitForFunction(() => !document.body.innerText.includes('Loading…'), null, { timeout: 60000 })
    .catch(() => console.warn('  still loading after 60s, capturing anyway'));
  // charts draw after their data, fonts after the first paint
  await page.waitForTimeout(1500);
}

(async () => {
  if (!process.env.OTO_USER || !process.env.OTO_PASSWORD) {
    console.error('OTO_USER and OTO_PASSWORD are required to open a backoffice session (on the same command line as node, or exported)');
    process.exit(1);
  }
  const { chromium } = loadPlaywright();
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: WIDTH, height: HEIGHT }, deviceScaleFactor: 1, colorScheme: 'dark' });
  // the same call as the login page: it sets the backoffice session cookie on the context
  const login = await context.request.post(`${BASE}/bo/simple/login`, {
    headers: { Accept: 'application/json' },
    data: { username: process.env.OTO_USER, password: process.env.OTO_PASSWORD },
  });
  if (!login.ok()) {
    console.error(`login failed: HTTP ${login.status()}`);
    process.exit(1);
  }
  const page = await context.newPage();
  const shots = ONLY.length ? SHOTS.filter((s) => ONLY.includes(s.name)) : SHOTS;
  for (const shot of shots) {
    const file = path.join(OUT, `ai-studio-${shot.name}.png`);
    process.stdout.write(`${shot.name.padEnd(18)} `);
    try {
      await page.goto(shot.url, { waitUntil: 'domcontentloaded' });
      // the saved preference of the user wins over the color scheme: pin the theme of the docs
      await page.evaluate(() => document.documentElement.setAttribute('data-theme', 'dark'));
      await settle(page);
      if (shot.before) {
        await shot.before(page);
        await settle(page);
      }
      if (shot.element) {
        await page.locator(shot.element).first().screenshot({ path: file });
      } else {
        await page.screenshot({ path: file, fullPage: !shot.viewport });
      }
      console.log(`-> ${path.relative(process.cwd(), file)}`);
    } catch (e) {
      console.log(`FAILED: ${e.message.split('\n')[0]}`);
    }
  }
  await browser.close();
})();
