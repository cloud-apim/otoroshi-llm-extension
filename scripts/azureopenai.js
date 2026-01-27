// azure-openai-server.js
// Minimal Azure OpenAI-compatible API to test Otoroshi LLM

import http from "node:http";
import { URL } from "node:url";

const PORT = process.env.PORT || 3000;

function sendJson(res, statusCode, payload) {
  const data = JSON.stringify(payload);
  res.writeHead(statusCode, {
    "Content-Type": "application/json",
    "Content-Length": Buffer.byteLength(data),
  });
  res.end(data);
}

function notFound(res) {
  sendJson(res, 404, {
    error: {
      message: "Not found",
      type: "not_found",
    },
  });
}

function methodNotAllowed(res) {
  sendJson(res, 405, {
    error: {
      message: "Method not allowed",
      type: "method_not_allowed",
    },
  });
}

// azure /v1/models response
function handleModels(req, res) {
  if (req.method !== "GET") {
    return methodNotAllowed(res);
  }

  const payload = {
    object: "list",
    data: [
      {
        id: "azure-gpt-4.1-mini",
        object: "model",
        created: 1720000000,
        owned_by: "azure-openai",
      },
      {
        id: "azure-gpt-4o",
        object: "model",
        created: 1720000000,
        owned_by: "azure-openai",
      },
    ],
  };

  sendJson(res, 200, payload);
}

// Non-streaming azure /v1/chat/completions
function sendNonStreamingCompletion(res) {
  const now = Math.floor(Date.now() / 1000);

  const payload = {
    id: "chatcmpl-azure-123",
    object: "chat.completion",
    created: now,
    model: "azure-gpt-4.1-mini",
    choices: [
      {
        index: 0,
        message: {
          role: "assistant",
          content: "Hello from the azure OpenAI server 👋",
        },
        logprobs: null,
        finish_reason: "stop",
      },
    ],
    usage: {
      "completion_tokens": 11,
      "prompt_tokens": 9,
      "total_tokens": 20,
      "completion_tokens_details": {
        "accepted_prediction_tokens": 0,
        "audio_tokens": 0,
        "reasoning_tokens": 0,
        "rejected_prediction_tokens": 0
      },
      "prompt_tokens_details": {
        "audio_tokens": 0,
        "cached_tokens": 0
      }
    },
    system_fingerprint: "azure-fingerprint-123",
  };

  sendJson(res, 200, payload);
}

// Streaming azure /v1/chat/completions (SSE style)
function sendStreamingCompletion(res) {
  const now = Math.floor(Date.now() / 1000);

  // Standard OpenAI streaming headers
  res.writeHead(200, {
    "Content-Type": "text/event-stream; charset=utf-8",
    "Cache-Control": "no-cache, no-transform",
    Connection: "keep-alive",
  });

  function sendChunk(contentPart, isLast) {
    const chunkPayload = {
      id: "chatcmpl-azure-123",
      object: "chat.completion.chunk",
      created: now,
      model: "azure-gpt-4.1-mini",
      choices: [
        {
          index: 0,
          delta: {
            role: "assistant",
            content: contentPart,
          },
          finish_reason: isLast ? "stop" : null,
        },
      ],
    };

    if (isLast) {
      chunkPayload.usage = {
        "completion_tokens": 11,
        "prompt_tokens": 9,
        "total_tokens": 20,
        "completion_tokens_details": {
          "accepted_prediction_tokens": 0,
          "audio_tokens": 0,
          "reasoning_tokens": 0,
          "rejected_prediction_tokens": 0
        },
        "prompt_tokens_details": {
          "audio_tokens": 0,
          "cached_tokens": 0
        }
      };
    }

    const data = `data: ${JSON.stringify(chunkPayload)}\n\n`;
    res.write(data);
  }

  // Very simple "tokenization" in fixed chunks
  const text = "Hello from the azure OpenAI server 👋";
  const parts = ["Hello ", "from the ", "azure OpenAI server 👋"];

  parts.forEach((part, index) => {
    const isLast = index === parts.length - 1;
    sendChunk(part, isLast);
  });

  // End of stream marker as in OpenAI API
  res.write("data: [DONE]\n\n");
  res.end();
}

// Handle /v1/chat/completions (both streaming and non-streaming)
function handleChatCompletions(req, res) {
  if (req.method !== "POST") {
    return methodNotAllowed(res);
  }

  let body = "";

  req.on("data", (chunk) => {
    body += chunk.toString("utf8");
  });

  req.on("end", () => {
    let parsed = {};
    if (body.trim().length > 0) {
      try {
        parsed = JSON.parse(body);
      } catch (e) {
        return sendJson(res, 400, {
          error: {
            message: "Invalid JSON body",
            type: "invalid_request_error",
          },
        });
      }
    }

    const stream = !!parsed.stream;

    if (stream) {
      return sendStreamingCompletion(res);
    } else {
      return sendNonStreamingCompletion(res);
    }
  });

  req.on("error", () => {
    sendJson(res, 500, {
      error: {
        message: "Error reading request body",
        type: "internal_server_error",
      },
    });
  });
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);

  // Optional: very simple CORS if you ever test from a browser
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader(
    "Access-Control-Allow-Headers",
    "Content-Type, Authorization"
  );
  res.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");

  if (req.method === "OPTIONS") {
    res.writeHead(204);
    return res.end();
  }

  // Accept both /models and /v1/models to make testing easier
  const pathname = url.pathname;

  console.log(req.method, pathname)

  if (pathname === "/v1/models" || pathname === "/models" || pathname === "/openai/deployments/deployment_id/models") {
    return handleModels(req, res);
  }

  if (
    pathname === "/openai/deployments/deployment_id/chat/completions" ||
    pathname === "/v1/chat/completions" ||
    pathname === "/chat/completions"
  ) {
    return handleChatCompletions(req, res);
  }

  if (pathname === "/health") {
    return sendJson(res, 200, { status: "ok" });
  }

  return notFound(res);
});

server.listen(PORT, () => {
  console.log(`azure OpenAI server listening on http://localhost:${PORT}`);
});
