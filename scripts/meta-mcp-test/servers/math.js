import { z } from "zod";
import { startMcpHttpServer } from "../lib/mcp-http-server.js";

const text = (value) => ({ content: [{ type: "text", text: String(value) }] });

await startMcpHttpServer({
  name: "math-server",
  port: Number(process.env.MATH_PORT ?? 7001),
  register: (server) => {
    server.tool(
      "add",
      "Return the sum of two numbers.",
      { a: z.number().describe("First operand"), b: z.number().describe("Second operand") },
      async ({ a, b }) => text(a + b),
    );

    server.tool(
      "subtract",
      "Subtract the second number from the first.",
      { a: z.number(), b: z.number() },
      async ({ a, b }) => text(a - b),
    );

    server.tool(
      "multiply",
      "Multiply two numbers.",
      { a: z.number(), b: z.number() },
      async ({ a, b }) => text(a * b),
    );

    server.tool(
      "divide",
      "Divide the first number by the second. Errors when the divisor is 0.",
      { a: z.number(), b: z.number() },
      async ({ a, b }) => {
        if (b === 0) return { isError: true, content: [{ type: "text", text: "division by zero" }] };
        return text(a / b);
      },
    );

    server.tool(
      "power",
      "Raise base to the given exponent.",
      { base: z.number(), exponent: z.number() },
      async ({ base, exponent }) => text(Math.pow(base, exponent)),
    );
  },
});
