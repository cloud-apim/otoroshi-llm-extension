#!/usr/bin/env bun

/**
 * Responses API - input_file smoke test
 *
 * Sends a PDF as an `input_file` content part to both responses proxies:
 *   - POST /v1/open-responses   (LLM OpenResponse Proxy)
 *   - POST /v1/oai-responses    (LLM OpenAI Responses Proxy)
 *
 * The generated PDF contains a reference code. If the answer quotes it, the document
 * really reached the model (and was not silently dropped on the way).
 *
 * usage:
 *   bun scripts/responses-input-file.js
 *   bun scripts/responses-input-file.js ./my-document.pdf
 *
 * env:
 *   BASE_URL               default http://unifiedai.oto.tools:9999/v1
 *   MODEL                  default openai/gpt-5.5
 *   AUTH_BEARER            sent as `Authorization: Bearer ...`
 *   OTOROSHI_CLIENT_ID     sent as `Otoroshi-Client-Id`
 *   OTOROSHI_CLIENT_SECRET sent as `Otoroshi-Client-Secret`
 *   VERBOSE=true           print the whole response payloads
 */

const BASE_URL = process.env.BASE_URL || "http://unifiedai.oto.tools:9999/v1";
const MODEL = process.env.MODEL || "openai/gpt-5.5";
const VERBOSE = process.env.VERBOSE === "true";

const QUESTION = "Summarize this document in 3 bullet points, then quote the reference code exactly as it appears.";
const REFERENCE_CODE = "ZK7-QUARTZ-4413";

const colors = {
  reset: "\x1b[0m",
  bright: "\x1b[1m",
  dim: "\x1b[2m",
  red: "\x1b[31m",
  green: "\x1b[32m",
  yellow: "\x1b[33m",
  cyan: "\x1b[36m",
};

const log = (msg = "") => console.log(msg);
const title = (msg) => log(`\n${colors.bright}${colors.cyan}${msg}${colors.reset}`);
const ok = (msg) => log(`${colors.green}✓${colors.reset} ${msg}`);
const ko = (msg) => log(`${colors.red}✗${colors.reset} ${msg}`);
const warn = (msg) => log(`${colors.yellow}!${colors.reset} ${msg}`);
const dim = (msg) => log(`${colors.dim}${msg}${colors.reset}`);

//////////////////////////////////////////////////////////////////////////////////////////////////
// a tiny, valid, one page pdf with the document text in it (ascii only, Helvetica standard encoding)
//////////////////////////////////////////////////////////////////////////////////////////////////

const DOCUMENT_LINES = [
  "PROJECT AURORA - QUARTERLY PLATFORM REPORT",
  "",
  "Reporting period: Q3, from July to September",
  "Owner: Platform Reliability team",
  "Peak throughput: 12400 requests per second",
  "Latency: 180 ms at the 99th percentile",
  "Two incidents, both mitigated in under 30 minutes",
  "Storage footprint down 14 percent after the compaction rollout",
  "Next milestone: multi region failover, target end of Q4",
  "",
  `Reference code: ${REFERENCE_CODE}`,
];

function escapePdfText(str) {
  return str.replace(/\\/g, "\\\\").replace(/\(/g, "\\(").replace(/\)/g, "\\)");
}

function buildPdf(lines) {
  let content = "BT\n/F1 13 Tf\n16 TL\n60 780 Td\n";
  for (const line of lines) {
    content += `(${escapePdfText(line)}) Tj\nT*\n`;
  }
  content += "ET";
  const objects = [
    "<< /Type /Catalog /Pages 2 0 R >>",
    "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
    `<< /Length ${Buffer.byteLength(content, "latin1")} >>\nstream\n${content}\nendstream`,
    "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
  ];
  let pdf = "%PDF-1.4\n";
  const offsets = [];
  objects.forEach((body, idx) => {
    offsets.push(Buffer.byteLength(pdf, "latin1"));
    pdf += `${idx + 1} 0 obj\n${body}\nendobj\n`;
  });
  const startxref = Buffer.byteLength(pdf, "latin1");
  pdf += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  for (const offset of offsets) {
    pdf += `${String(offset).padStart(10, "0")} 00000 n \n`;
  }
  pdf += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${startxref}\n%%EOF\n`;
  return Buffer.from(pdf, "latin1");
}

async function resolveDocument() {
  const path = process.argv[2];
  if (path) {
    const file = Bun.file(path);
    if (!(await file.exists())) {
      ko(`file not found: ${path}`);
      process.exit(1);
    }
    const bytes = Buffer.from(await file.arrayBuffer());
    return { filename: path.split("/").pop(), bytes, generated: false };
  }
  return { filename: "quarterly-platform-report.pdf", bytes: buildPdf(DOCUMENT_LINES), generated: true };
}

//////////////////////////////////////////////////////////////////////////////////////////////////
// calls
//////////////////////////////////////////////////////////////////////////////////////////////////

function headers() {
  const hdrs = { "Content-Type": "application/json" };
  if (process.env.AUTH_BEARER) hdrs["Authorization"] = `Bearer ${process.env.AUTH_BEARER}`;
  if (process.env.OTOROSHI_CLIENT_ID) hdrs["Otoroshi-Client-Id"] = process.env.OTOROSHI_CLIENT_ID;
  if (process.env.OTOROSHI_CLIENT_SECRET) hdrs["Otoroshi-Client-Secret"] = process.env.OTOROSHI_CLIENT_SECRET;
  return hdrs;
}

function body(document) {
  return {
    model: MODEL,
    input: [
      {
        type: "message",
        role: "user",
        content: [
          { type: "input_text", text: QUESTION },
          {
            type: "input_file",
            filename: document.filename,
            file_data: `data:application/pdf;base64,${document.bytes.toString("base64")}`,
          },
        ],
      },
    ],
  };
}

// both the responses shape (output[].content[].text) and a chat shape are accepted
function extractText(payload) {
  if (typeof payload?.output_text === "string" && payload.output_text.length > 0) return payload.output_text;
  const fromOutput = (payload?.output || [])
    .flatMap((item) => item?.content || [])
    .map((part) => part?.text)
    .filter((text) => typeof text === "string" && text.length > 0);
  if (fromOutput.length > 0) return fromOutput.join("\n");
  const fromChoices = (payload?.choices || [])
    .map((choice) => choice?.message?.content)
    .filter((text) => typeof text === "string" && text.length > 0);
  if (fromChoices.length > 0) return fromChoices.join("\n");
  return null;
}

async function callRoute(name, path, document) {
  title(`${name} — POST ${BASE_URL}${path}`);
  const started = Date.now();
  let res;
  try {
    res = await fetch(`${BASE_URL}${path}`, {
      method: "POST",
      headers: headers(),
      body: JSON.stringify(body(document)),
    });
  } catch (e) {
    ko(`request failed: ${e.message}`);
    return { name, ok: false };
  }
  const duration = Date.now() - started;
  const raw = await res.text();
  let payload = null;
  try {
    payload = JSON.parse(raw);
  } catch (e) {
    // not json, keep raw
  }

  if (res.status !== 200) {
    ko(`status ${res.status} in ${duration}ms`);
    dim(raw.slice(0, 2000));
    return { name, ok: false, status: res.status };
  }
  ok(`status 200 in ${duration}ms`);

  const text = payload ? extractText(payload) : null;
  if (!text) {
    ko("no text in the response");
    dim((payload ? JSON.stringify(payload, null, 2) : raw).slice(0, 2000));
    return { name, ok: false, status: 200 };
  }

  log(`${colors.bright}answer:${colors.reset}`);
  log(text);

  const usage = payload?.usage;
  if (usage) dim(`usage: ${JSON.stringify(usage)}`);
  if (VERBOSE) dim(JSON.stringify(payload, null, 2));

  const quoted = document.generated ? text.includes(REFERENCE_CODE) : null;
  if (quoted === true) ok(`the answer quotes "${REFERENCE_CODE}": the pdf reached the model`);
  if (quoted === false) ko(`the answer does not quote "${REFERENCE_CODE}": the pdf was probably dropped`);
  if (quoted === null) warn("custom pdf: check the answer yourself, no marker to look for");

  return { name, ok: quoted !== false, status: 200, answered: true };
}

//////////////////////////////////////////////////////////////////////////////////////////////////
// main
//////////////////////////////////////////////////////////////////////////////////////////////////

const document = await resolveDocument();
title("setup");
dim(`base url : ${BASE_URL}`);
dim(`model    : ${MODEL}`);
dim(`document : ${document.filename} (${document.bytes.length} bytes${document.generated ? ", generated" : ""})`);
dim(`question : ${QUESTION}`);

const results = [];
results.push(await callRoute("OpenResponse proxy", "/open-responses", document));
results.push(await callRoute("OpenAI Responses proxy", "/oai-responses", document));

title("summary");
for (const result of results) {
  (result.ok ? ok : ko)(`${result.name}${result.status ? ` — status ${result.status}` : ""}`);
}
process.exit(results.every((r) => r.ok) ? 0 : 1);
