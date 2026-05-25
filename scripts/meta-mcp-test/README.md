# meta-mcp-test

Three demo MCP servers + a thin test client to validate the Otoroshi "meta" MCP
connector end-to-end.

```
servers/
  math.js      → http://localhost:7001/mcp   (add, subtract, multiply, divide, power)
  weather.js   → http://localhost:7002/mcp   (list_cities, get_current_weather, get_forecast)
  notes.js     → http://localhost:7003/mcp   (list_notes, get_note, create_note, delete_note, search_notes)
```

Each server speaks the modern MCP Streamable HTTP transport (single `POST /mcp` endpoint,
stateless). Each one is independent — you can hit them with `curl` and a JSON-RPC body if
you want.

## Setup

```bash
cd scripts/meta-mcp-test
bun install
cp .env.example .env   # optional, then edit
```

## Run

In one terminal — start the 3 MCP servers:

```bash
bun run start
```

In Otoroshi (manually, for now):
1. Create three `McpConnector` of kind `http` pointing at `http://localhost:7001/mcp`,
   `…:7002/mcp`, `…:7003/mcp`.
2. Create a fourth `McpConnector` of kind `meta`, with `transport.options.connectors`
   set to the IDs of those three.
3. Create an `AiProvider` (e.g. `openai`, model `gpt-5.4`) with `mcp_connectors` set
   to the id of the meta connector.
4. Create a route fronted by `OpenAiCompatProxy` referencing that provider.

In another terminal — run the test client:

```bash
TEST_ENDPOINT=http://test.oto.tools:9999/chat/completions \
TEST_API_KEY=… \
bun run test
```

The test sends a handful of chat-completion requests that should make the LLM go
through `list_servers` → `list_tools` → `execute` and friends. It asserts on the
assistant's final reply (substring checks).

## Layout

| file                         | role                                                          |
|------------------------------|---------------------------------------------------------------|
| `lib/mcp-http-server.js`     | shared wrapper around `McpServer` + `StreamableHTTPServerTransport` |
| `servers/math.js`            | math MCP server                                               |
| `servers/weather.js`         | weather MCP server                                            |
| `servers/notes.js`           | notes MCP server (in-memory, resets on restart)               |
| `start-all.js`               | spawns the 3 servers and forwards their stdout/stderr         |
| `test.js`                    | drives an OpenAI-compatible endpoint with a few prompts       |
