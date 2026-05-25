// Drives an OpenAI-compatible endpoint (the Otoroshi route fronting the meta MCP connector)
// through prompts that should exercise every meta tool: list_servers, list_tools,
// get_tool_schema, execute (single + chained), search_tools.
//
// Run the 3 MCP servers in another terminal first:
//   bun run start
//
// Then, with TEST_ENDPOINT pointing at your Otoroshi route:
//   bun run test
//
// The script does NOT call the MCP servers directly — it sends plain chat completions to
// the OpenAI-compatible endpoint and asserts on the assistant's final reply (which should
// contain values produced by the underlying tools).

const ENDPOINT = process.env.TEST_ENDPOINT ?? "http://testmcpmeta.oto.tools:9999/chat/completions";
const API_KEY  = process.env.TEST_API_KEY  ?? "";
const MODEL    = process.env.TEST_MODEL    ?? "gpt-5.4";

const SYSTEM_PROMPT = `You are an assistant connected to a "meta" MCP server. You have exactly five tools:
- list_servers(): inventory of the connected MCP servers (returns {slug, name, description}[]).
- list_tools({server}): tools exposed by one server.
- get_tool_schema({server, tool}): JSON schema for one tool.
- execute({calls: [{server, tool, args, name?}]}): run one or many tool calls in order. Use \${name.path} inside later calls to reference earlier results.
- search_tools({query, servers?}): rank tools by relevance.
Always discover via list_servers / list_tools before executing.`;

const tests = [
  {
    label: "list_servers + list_tools",
    user: "Which MCP servers are available, and what tools does each expose? Reply in a short bulleted list.",
    expect: (text) => /math/i.test(text) && /weather/i.test(text) && /notes/i.test(text),
  },
  {
    label: "simple math via execute",
    user: "Use the math tools to compute (7 * 8) + 3. Reply with just the number.",
    expect: (text) => /\b59\b/.test(text),
  },
  {
    label: "weather lookup",
    user: "What's the current weather in Tokyo? Reply with the temperature in Celsius and the condition.",
    expect: (text) => /19/.test(text) && /sun/i.test(text),
  },
  {
    label: "chained execute (create then list)",
    user: "Create a note titled 'shopping' with content 'milk, bread', then list all notes. Reply with the final list as JSON.",
    expect: (text) => /shopping/i.test(text),
  },
  {
    label: "search_tools",
    user: "Search for tools that have to do with arithmetic. Reply with just the tool names, comma-separated.",
    expect: (text) => /(add|multiply|divide|subtract|power)/i.test(text),
  },
  {
    label: "get_tool_schema",
    user: "What arguments does the weather server's get_forecast tool accept? Reply with just the parameter names.",
    expect: (text) => /city/i.test(text) && /days/i.test(text),
  },
];

let pass = 0;
let fail = 0;

for (const t of tests) {
  process.stdout.write(`\n▶ ${t.label}\n`);
  try {
    const text = await chat(t.user);
    const ok = t.expect(text);
    if (ok) {
      pass += 1;
      console.log(`  ✓ pass`);
    } else {
      fail += 1;
      console.log(`  ✗ fail`);
    }
    console.log(`  reply: ${truncate(text, 400)}`);
  } catch (err) {
    fail += 1;
    console.log(`  ✗ error: ${err?.message ?? err}`);
  }
}

console.log(`\n=== ${pass} passed, ${fail} failed (${tests.length} total) ===`);
process.exit(fail === 0 ? 0 : 1);

// ---------- helpers ----------

async function chat(userMessage) {
  const body = {
    model: MODEL,
    messages: [
      { role: "system", content: SYSTEM_PROMPT },
      { role: "user",   content: userMessage },
    ],
  };
  const headers = { "content-type": "application/json" };
  if (API_KEY) headers["authorization"] = `Bearer ${API_KEY}`;
  console.log('calling', 'POST', ENDPOINT);
  const res = await fetch(ENDPOINT, { method: "POST", headers, body: JSON.stringify(body) });
  if (!res.ok) {
    const raw = await res.text().catch(() => "");
    throw new Error(`HTTP ${res.status} ${res.statusText}: ${truncate(raw, 300)}`);
  }
  const json = await res.json();
  const content = json?.choices?.[0]?.message?.content;
  if (typeof content !== "string") {
    throw new Error(`unexpected response shape: ${truncate(JSON.stringify(json), 300)}`);
  }
  return content;
}

function truncate(s, n) {
  if (!s) return s;
  return s.length <= n ? s : s.slice(0, n) + `… (+${s.length - n} more chars)`;
}
