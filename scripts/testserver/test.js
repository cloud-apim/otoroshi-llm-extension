import { MCP } from 'mcp-js-server';
import { SSEServerTransport } from "@modelcontextprotocol/sdk/server/sse.js";
import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { CallToolRequestSchema, ListToolsRequestSchema } from "@modelcontextprotocol/sdk/types.js";
import { z } from "zod";
import { zodToJsonSchema } from "zod-to-json-schema";
import express from "express";

///////////////////////////////////////////////////////
///////////////////////////////////////////////////////
///////////////////////////////////////////////////////

const infos = {
  name: 'mcp-test-server',
  version: '0.1.0'
};

const tools = {
  what_movie_played: {
    description: 'This function return the name of the movie played in one room of the house. It take one parameter, the name of the room.',
    handler: async ({ room }) => {
      return `The movie currently playing in the ${room} is 'The Shawshank Redemption'`;
    },
    schema: {
      type: 'object',
      properties: {
        room: { type: 'string', description: 'the name of the room where the movie is played' },
      },
      required: ['room']
    }
  }
};

const stdioserver = new MCP(infos, {}, {}, tools);

///////////////////////////////////////////////////////
///////////////////////////////////////////////////////
///////////////////////////////////////////////////////

const AddSchema = z.object({
  a: z.string().describe("First number"),
  b: z.string().describe("Second number"),
});
const sseserver = new Server({
  name: "example-servers/everything",
  version: "1.0.0",
}, {
  capabilities: {
    tools: {},
    logging: {},
  },
});
sseserver.setRequestHandler(ListToolsRequestSchema, async () => {
  const tools = [
    {
      name: "add",
      description: "Adds two numbers",
      inputSchema: zodToJsonSchema(AddSchema),
    }
  ];
  return { tools };
});
sseserver.setRequestHandler(CallToolRequestSchema, (request) => {
  const { name, arguments: args } = request.params;
  if (name === "add") {
    const validatedArgs = AddSchema.parse(args);
    const sum = parseInt(validatedArgs.a, 10) + parseInt(validatedArgs.b, 10);
    return {
      content: [
        {
          type: "text",
          text: `The sum of ${validatedArgs.a} and ${validatedArgs.b} is ${sum}.`,
        },
      ],
    };
  }
  throw new Error(`Unknown tool: ${name}`);
});

const app = express();
let transport;
app.get("/sse", async (req, res) => {
  console.error("Received connection");
  transport = new SSEServerTransport("/message", res);
  await sseserver.connect(transport);
  sseserver.onclose = async () => {
    await sseserver.close();
    process.exit(0);
  };
});
app.post("/message", async (req, res) => {
  console.error("Received message");
  await transport.handlePostMessage(req, res);
});
const PORT = process.env.PORT || 3001;
app.listen(PORT, () => {
  console.error(`Sse Server is running on port ${PORT}`);
});