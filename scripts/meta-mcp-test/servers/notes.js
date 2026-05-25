import { z } from "zod";
import { randomUUID } from "node:crypto";
import { startMcpHttpServer } from "../lib/mcp-http-server.js";

// In-memory store. Resets every server start — handy for tests.
const notes = new Map();
notes.set("seed-1", { id: "seed-1", title: "Welcome", content: "First note." });

const text = (v) => ({
  content: [{ type: "text", text: typeof v === "string" ? v : JSON.stringify(v) }],
});

await startMcpHttpServer({
  name: "notes-server",
  port: Number(process.env.NOTES_PORT ?? 7003),
  register: (server) => {
    server.tool(
      "list_notes",
      "List every note currently stored. Returns an array of {id, title}.",
      {},
      async () =>
        text([...notes.values()].map(({ id, title }) => ({ id, title }))),
    );

    server.tool(
      "get_note",
      "Read a single note by id.",
      { id: z.string() },
      async ({ id }) => {
        const n = notes.get(id);
        if (!n) return { isError: true, content: [{ type: "text", text: `unknown note '${id}'` }] };
        return text(n);
      },
    );

    server.tool(
      "create_note",
      "Create a note with the given title and content. Returns the new id.",
      { title: z.string(), content: z.string() },
      async ({ title, content }) => {
        const id = randomUUID();
        notes.set(id, { id, title, content });
        return text({ id });
      },
    );

    server.tool(
      "delete_note",
      "Delete a note by id. Returns true/false depending on whether something was removed.",
      { id: z.string() },
      async ({ id }) => text(notes.delete(id)),
    );

    server.tool(
      "search_notes",
      "Case-insensitive substring search over title and content. Returns {id, title} matches.",
      { query: z.string() },
      async ({ query }) => {
        const q = query.toLowerCase();
        const hits = [...notes.values()].filter(
          (n) => n.title.toLowerCase().includes(q) || n.content.toLowerCase().includes(q),
        );
        return text(hits.map(({ id, title }) => ({ id, title })));
      },
    );
  },
});
