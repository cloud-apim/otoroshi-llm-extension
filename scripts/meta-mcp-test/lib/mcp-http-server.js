import express from "express";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";

// Mounts a stateless Streamable-HTTP MCP server at `/mcp` on the given port.
// `register(server)` is called once with an `McpServer`, where you should
// declare your tools via `server.tool(name, description, schema, handler)`.
export async function startMcpHttpServer({ name, version = "1.0.0", port, register }) {
  const server = new McpServer({ name, version });
  register(server);

  const app = express();
  app.use(express.json({ limit: "4mb" }));

  app.post("/mcp", async (req, res) => {
    console.log(`new connection on ${name}`)
    try {
      const transport = new StreamableHTTPServerTransport({
        sessionIdGenerator: undefined, // stateless mode
        enableJsonResponse: true,
      });
      res.on("close", () => { try { transport.close(); } catch {} });
      await server.connect(transport);
      await transport.handleRequest(req, res, req.body);
    } catch (err) {
      console.error(`[${name}] error`, err);
      if (!res.headersSent) {
        res.status(500).json({
          jsonrpc: "2.0",
          error: { code: -32603, message: "Internal server error" },
          id: null,
        });
      }
    }
  });

  // GET/DELETE are not used in stateless mode — return 405 explicitly.
  for (const verb of ["get", "delete"]) {
    app[verb]("/mcp", (_req, res) => {
      console.log(`${verb} /mcp request`);
      res.status(405).json({
        jsonrpc: "2.0",
        error: { code: -32000, message: "Method not allowed." },
        id: null,
      });
    });
  }

  app.get("/healthz", (_req, res) => res.json({ ok: true, name, version }));

  await new Promise((resolve) => app.listen(port, resolve));
  console.log(`[${name}] listening at http://localhost:${port}/mcp`);
}
