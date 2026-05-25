// Spawns the 3 MCP servers as child processes and forwards their output.
// Ctrl-C kills all of them.
import { spawn } from "node:child_process";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const servers = [
  { name: "math",    file: resolve(here, "servers/math.js") },
  { name: "weather", file: resolve(here, "servers/weather.js") },
  { name: "notes",   file: resolve(here, "servers/notes.js") },
];

const procs = servers.map(({ name, file }) => {
  const p = spawn("bun", ["run", file], { stdio: ["ignore", "pipe", "pipe"], env: process.env });
  const tag = `[${name}]`;
  p.stdout.on("data", (d) => process.stdout.write(prefix(tag, d)));
  p.stderr.on("data", (d) => process.stderr.write(prefix(tag, d)));
  p.on("exit", (code) => {
    console.log(`${tag} exited with code ${code}`);
    shutdown(code ?? 0);
  });
  return p;
});

function prefix(tag, buf) {
  return buf
    .toString()
    .split("\n")
    .map((l, i, arr) => (l.length === 0 && i === arr.length - 1 ? l : `${tag} ${l}`))
    .join("\n");
}

let exiting = false;
function shutdown(code) {
  if (exiting) return;
  exiting = true;
  for (const p of procs) {
    try { p.kill("SIGTERM"); } catch {}
  }
  setTimeout(() => process.exit(code), 250);
}

process.on("SIGINT", () => shutdown(0));
process.on("SIGTERM", () => shutdown(0));
