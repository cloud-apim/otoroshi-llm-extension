# A2A (Agent-to-Agent) Protocol - Design Document

## Context

This document describes the design for integrating the A2A (Agent-to-Agent) protocol, initiated by Google and now under the Linux Foundation, into the Otoroshi LLM extension. The goal is twofold:

1. **A2A Server**: expose local agents (defined via `AgentConfig` or workflows) as A2A servers accessible by external clients/agents
2. **A2A Connector**: connect to remote A2A agents and use them as tools within local agents, similar to `McpConnector`

---

## The A2A protocol in a nutshell

### Core principles

- Agents communicate without exposing their internal state, memory, tools, or plans
- Discovery happens through **Agent Cards** (JSON metadata documents)
- Communication uses **JSON-RPC 2.0 over HTTPS**
- Work is organized around **Tasks** (stateful work units) and **Messages** (individual conversation turns)
- **contextId** logically groups multiple Tasks for multi-turn coherence

### Agent Card

Published at the well-known URI: `https://{domain}/.well-known/agent.json`

```json
{
  "name": "My Agent",
  "description": "Agent description",
  "version": "1.0.0",
  "supportedInterfaces": [
    {
      "url": "https://my-agent.example.com/a2a",
      "protocolBinding": "JSONRPC",
      "protocolVersion": "0.3.0"
    }
  ],
  "provider": {
    "organization": "Cloud APIM",
    "url": "https://www.cloud-apim.com"
  },
  "capabilities": {
    "streaming": true,
    "pushNotifications": false,
    "extendedAgentCard": false
  },
  "securitySchemes": {
    "bearer": {
      "httpAuthSecurityScheme": {
        "scheme": "Bearer"
      }
    }
  },
  "security": [{"bearer": []}],
  "defaultInputModes": ["text/plain", "application/json"],
  "defaultOutputModes": ["text/plain", "application/json"],
  "skills": [
    {
      "id": "math-tutor",
      "name": "Math Tutor",
      "description": "Help with math problems",
      "tags": ["math", "tutoring"],
      "examples": ["Solve this equation: 2x + 3 = 7"],
      "inputModes": ["text/plain"],
      "outputModes": ["text/plain"]
    }
  ]
}
```

### JSON-RPC Methods

| Method | Description | Priority |
|---|---|---|
| `message/send` | Synchronous message send, returns a Task or Message | **P0** |
| `message/stream` | Send with SSE streaming | **P0** |
| `tasks/get` | Retrieve task state | **P0** |
| `tasks/cancel` | Cancel an active task | **P1** |
| `tasks/list` | List tasks (with filters) | **P2** |
| `tasks/resubscribe` | Resume streaming for an existing task | **P2** |
| `tasks/pushNotificationConfig/*` | Notification webhook management | **P3** |
| `agent/getAuthenticatedExtendedCard` | Extended Agent Card with auth | **P3** |

### Task lifecycle

```
            ┌──────────┐
            │ submitted │
            └─────┬─────┘
                  │
            ┌─────▼─────┐
     ┌──────│  working   │──────┐
     │      └─────┬─────┘      │
     │            │             │
┌────▼────┐ ┌────▼─────┐ ┌────▼───┐
│  failed │ │completed │ │canceled│
└─────────┘ └──────────┘ └────────┘
     ▲            ▲
     │      ┌─────┴──────────┐
     └──────│ input_required │
            └────────────────┘
```

**Terminal states**: `completed`, `failed`, `canceled`, `rejected`
**Interactive states**: `input_required`, `auth_required` (client can send a follow-up message)

### Message and Part format

```json
{
  "messageId": "msg-001",
  "role": "user",
  "parts": [
    {"type": "text", "text": "Hello, help me with this problem"},
    {"type": "file", "file": {"uri": "https://...", "mimeType": "application/pdf"}},
    {"type": "data", "data": {"key": "value"}}
  ]
}
```

### JSON-RPC `message/send` request format

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "message/send",
  "params": {
    "message": {
      "messageId": "msg-001",
      "role": "user",
      "parts": [{"type": "text", "text": "Plan a route from Paris to Lyon"}],
      "contextId": "ctx-optional",
      "taskId": "task-optional"
    },
    "configuration": {
      "acceptedOutputModes": ["text/plain"],
      "historyLength": 10
    }
  }
}
```

### JSON-RPC response format (Task)

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "id": "task-123",
    "contextId": "ctx-456",
    "status": {
      "state": "completed",
      "message": {
        "role": "agent",
        "messageId": "msg-resp-001",
        "parts": [{"type": "text", "text": "Here is the planned route..."}]
      },
      "timestamp": "2026-03-12T10:00:00.000Z"
    },
    "artifacts": [],
    "history": []
  }
}
```

### Streaming (SSE)

`message/stream` returns Server-Sent Events. Each `data:` line contains a complete JSON-RPC response wrapping a `StreamResponse` which can be:
- `task`: full Task object
- `message`: intermediate message
- `statusUpdate`: `TaskStatusUpdateEvent`
- `artifactUpdate`: `TaskArtifactUpdateEvent`

```
data: {"jsonrpc":"2.0","id":1,"result":{"statusUpdate":{"taskId":"task-123","contextId":"ctx-456","status":{"state":"working","message":{"role":"agent","messageId":"m1","parts":[{"type":"text","text":"Processing..."}]}}}}}

data: {"jsonrpc":"2.0","id":1,"result":{"artifactUpdate":{"taskId":"task-123","contextId":"ctx-456","artifact":{"artifactId":"art-1","parts":[{"type":"text","text":"chunk..."}]},"append":true,"lastChunk":false}}}

data: {"jsonrpc":"2.0","id":1,"result":{"statusUpdate":{"taskId":"task-123","contextId":"ctx-456","status":{"state":"completed"}}}}
```

### A2A error codes

| Error | JSON-RPC Code | Description |
|---|---|---|
| TaskNotFoundError | -32001 | Task does not exist |
| TaskNotCancelableError | -32002 | Task cannot be canceled |
| PushNotificationNotSupportedError | -32003 | Push notifications not supported |
| UnsupportedOperationError | -32004 | Operation not implemented |
| ContentTypeNotSupportedError | -32005 | Content type not supported |
| InvalidAgentResponseError | -32006 | Invalid agent response |

---

## Proposed design

### Overview

```
┌───────────────────────────────────────────────────────────────────┐
│                        Otoroshi                                   │
│                                                                   │
│  ┌──────────────────┐    ┌─────────────────────────────────────┐  │
│  │  A2A Server      │    │  Local agents                       │  │
│  │  (NgBackendCall) │───▶│  AgentConfig / Workflow             │  │
│  │                  │    │                                     │  │
│  │  - Agent Card    │    │  ┌───────────┐  ┌───────────────┐   │  │
│  │  - message/send  │    │  │ Provider  │  │ A2A Connector │   │  │
│  │  - message/stream│    │  │ (LLM)     │  │ (tool)        │───┼──┼──▶ Remote A2A Agent
│  │  - tasks/get     │    │  └───────────┘  └───────────────┘   │  │
│  │  - tasks/cancel  │    │                                     │  │
│  └────────┬─────────┘    └─────────────────────────────────────┘  │
│           │                                                       │
│  ┌────────▼─────────┐    ┌─────────────────────────────────────┐  │
│  │  A2AServer Entity│    │  A2AConnector Entity                │  │
│  │  (config)        │    │  (remote connection config)         │  │
│  └──────────────────┘    └─────────────────────────────────────┘  │
│                                                                   │
│  ┌────────────────────────────────────────────────────────────┐   │
│  │  A2ATask Store (Redis, TTL)                                │   │
│  │  Storage for active and completed tasks                    │   │
│  └────────────────────────────────────────────────────────────┘   │
└───────────────────────────────────────────────────────────────────┘
```

---

### Part 1: A2A Server

#### New entity: `A2AServer`

Stored in Redis, configured via the admin UI. Defines how an agent is exposed as A2A.

```json
{
  "id": "a2a-server_xxx",
  "name": "My A2A Agent",
  "description": "An agent exposed via the A2A protocol",
  "tags": [],
  "metadata": {},

  "agent_card": {
    "version": "1.0.0",
    "provider": {
      "organization": "Cloud APIM",
      "url": "https://www.cloud-apim.com"
    },
    "default_input_modes": ["text/plain", "application/json"],
    "default_output_modes": ["text/plain", "application/json"],
    "skills": [
      {
        "id": "main",
        "name": "Main Skill",
        "description": "The agent's main skill",
        "tags": ["general"],
        "examples": ["Example request"]
      }
    ]
  },

  "backend": {
    "kind": "agent",
    "agent": {
      "name": "My Agent",
      "description": "...",
      "instructions": ["..."],
      "provider": "provider_xxx",
      "model": "gpt-4",
      "tools": [],
      "mcp_connectors": [],
      "a2a_connectors": [],
      "handoffs": []
    }
  }
}
```

**Backend alternative**: instead of inlining the `AgentConfig`, we could reference a workflow:

```json
{
  "backend": {
    "kind": "workflow",
    "workflow_ref": "workflow_xxx"
  }
}
```

Or even reference an existing route (to reuse an already configured LLMProxy):

```json
{
  "backend": {
    "kind": "route",
    "route_ref": "route_xxx"
  }
}
```

**Open question**: do we want to support all 3 modes (inline agent, workflow, route) or start simple with just inline agent + workflow?

#### Plugin: `A2AServerPlugin` (NgBackendCall)

This plugin would be attached to an Otoroshi route and handle all A2A requests.

**Internal routing**:
- `GET /.well-known/agent.json` → returns the Agent Card
- `POST /` with `Content-Type: application/json` → JSON-RPC dispatch

**Plugin configuration**:
```json
{
  "ref": "a2a-server_xxx"
}
```

**JSON-RPC dispatch logic**:

```
method == "message/send"    → handleMessageSend(params)
method == "message/stream"  → handleMessageStream(params)
method == "tasks/get"       → handleTaskGet(params)
method == "tasks/cancel"    → handleTaskCancel(params)
method == "tasks/list"      → handleTaskList(params)
otherwise                   → error -32601 (Method not found)
```

#### Task management

**Storage**: in Redis with a configurable TTL (default 24h). Key: `{storageRoot}:extensions:{extId}:a2a-tasks:{taskId}`

**Task structure in Redis**:

```json
{
  "id": "task-uuid",
  "context_id": "ctx-uuid",
  "a2a_server_id": "a2a-server_xxx",
  "status": {
    "state": "completed",
    "timestamp": "2026-03-12T10:00:00.000Z"
  },
  "history": [
    {"messageId": "msg-1", "role": "user", "parts": [...]},
    {"messageId": "msg-2", "role": "agent", "parts": [...]}
  ],
  "artifacts": [],
  "created_at": 1741776000000,
  "updated_at": 1741776005000
}
```

**Open question**: for simple synchronous tasks (agent responds in a single call), should we still store in Redis? It would allow `tasks/get` after the fact, but adds overhead for simple cases. Option: always store but with a short TTL for completed tasks.

#### `message/send` flow

```
1. Parse JSON-RPC request
2. Extract message and configuration
3. Create a Task with "submitted" state, store in Redis
4. Map A2A Parts to InputChatMessage:
   - TextPart → text content
   - FilePart → InputImageContent or InputFileContent depending on MIME type
   - DataPart → serialize as JSON string
5. If taskId provided in the message → retrieve existing Task and add to context
6. If contextId provided → retrieve context history
7. Update Task state to "working"
8. Call the agent/workflow with the mapped input
9. Map the response to A2A Parts
10. Update Task to "completed" (or "failed" on error)
11. Return JSON-RPC response
```

#### `message/stream` flow

```
1-7. Same as message/send
8. Send an SSE statusUpdate with state="working"
9. Call the agent/workflow in streaming mode
10. For each response chunk, send an SSE artifactUpdate
11. Send a final SSE statusUpdate with state="completed"
```

#### A2A ↔ ChatMessage mapping

**Input (A2A → ChatMessage)**:

```scala
// A2A Part → ChatMessage content
TextPart("hello")           → InputTextContent("hello")
FilePart(uri, "image/png")  → InputImageContent(url = uri)
FilePart(bytes, "image/png")→ InputImageContent(base64 = bytes)
DataPart({...})             → InputTextContent(json.stringify)
```

**Output (ChatMessage → A2A)**:

```scala
// ChatMessage → A2A Part
"response text"             → TextPart("response text")
// If the response contains structured JSON, we could also generate a DataPart
```

---

### Part 2: A2A Connector

#### New entity: `A2AConnector`

Similar to `McpConnector`. Stored in Redis, allows connecting to a remote A2A agent.

```json
{
  "id": "a2a-connector_xxx",
  "name": "Remote planning agent",
  "description": "Connector to a remote A2A planning agent",
  "tags": [],
  "metadata": {},

  "url": "https://remote-agent.example.com",
  "agent_card_path": "/.well-known/agent.json",

  "authentication": {
    "kind": "bearer",
    "token": "sk-xxx"
  },

  "skills_filter": [],

  "timeout": 30000,
  "streaming": false,

  "tls": {
    "enabled": false,
    "trust_all": false,
    "client_cert_ref": null
  }
}
```

**Supported authentication schemes**:
- `none`: no auth
- `bearer`: `Authorization: Bearer <token>`
- `apikey`: API key in a header, query param, or configurable cookie
- `basic`: Basic auth
- `oauth2_client_credentials`: OAuth2 client credentials flow
- `custom_headers`: custom headers (for edge cases)

#### How the connector exposes skills as tools

When an `A2AConnector` is configured, on startup (or state sync), we:
1. Fetch the remote server's Agent Card
2. Parse the skills
3. For each skill, generate a tool function

**Skill → Tool Function mapping**:

```json
{
  "type": "function",
  "function": {
    "name": "a2a_<connector_id>_<skill_id>",
    "description": "skill description (from Agent Card)",
    "parameters": {
      "type": "object",
      "properties": {
        "message": {
          "type": "string",
          "description": "The message to send to the remote agent"
        }
      },
      "required": ["message"]
    }
  }
}
```

**Open question**: do we want more sophisticated parameter mapping? For example, if the skill accepts specific `inputModes` like `application/json`, we could enrich the parameter schema. To start, a simple `message` text field seems sufficient.

#### Remote A2A call execution

When a local agent calls an A2A tool:

```
1. Retrieve the connector and corresponding skill
2. Build the JSON-RPC message/send request:
   {
     "jsonrpc": "2.0",
     "id": 1,
     "method": "message/send",
     "params": {
       "message": {
         "messageId": "<uuid>",
         "role": "user",
         "parts": [{"type": "text", "text": "<the message>"}]
       }
     }
   }
3. Send the HTTP POST request to the connector URL
4. Parse the JSON-RPC response
5. Extract text from the response Parts
6. Return the result as tool output
```

**For streaming**: if `streaming: true` in the connector config, we use `message/stream` instead of `message/send`. SSE events are consumed and the final result is returned.

#### Integration with agents

A2A connectors would be usable just like MCP connectors in an `AgentConfig`:

```json
{
  "name": "My Agent",
  "provider": "provider_xxx",
  "instructions": ["..."],
  "mcp_connectors": ["mcp-connector_xxx"],
  "a2a_connectors": ["a2a-connector_xxx"],
  "tools": ["tool-function_xxx"]
}
```

In the `AgentConfig` code, we would add A2A connector resolution in the same way as MCP connectors.

#### Agent Card caching

The Agent Card doesn't change often. We can cache it with a configurable TTL (default 5 min). On state sync, we refresh the Agent Cards of all active connectors.

**Open question**: should we refresh the Agent Card on every state sync (~30s by default in Otoroshi)? That might be too frequent. A separate cache TTL would be more appropriate.

---

### Part 3: Entities and storage

#### New entities to create

| Entity | ID Prefix | Redis key | Description |
|---|---|---|---|
| `A2AServer` | `a2a-server_` | `...a2a-servers:{id}` | A2A server configuration |
| `A2AConnector` | `a2a-connector_` | `...a2a-connectors:{id}` | A2A connector configuration |

A2A Tasks are **not** standard Otoroshi entities (not in state sync) as they are ephemeral. They are stored directly in Redis with TTL.

#### Registration in the extension

In `extension.scala`, add:

```scala
override def entities(): Seq[AdminExtensionEntity[EntityLocationSupport]] = {
  Seq(
    // ... existing entities ...
    AdminExtensionEntity(A2AServer.resource(env, datastores, states)),
    AdminExtensionEntity(A2AConnector.resource(env, datastores, states)),
  )
}
```

#### State management

In `AiGatewayExtensionState`, add:

```scala
private val _a2aServers = new UnboundedTrieMap[String, A2AServer]()
private val _a2aConnectors = new UnboundedTrieMap[String, A2AConnector]()

def a2aServer(id: String): Option[A2AServer] = _a2aServers.get(id)
def allA2AServers(): Seq[A2AServer] = _a2aServers.values.toSeq
def updateA2AServers(values: Seq[A2AServer]): Unit = { ... }

def a2aConnector(id: String): Option[A2AConnector] = _a2aConnectors.get(id)
def allA2AConnectors(): Seq[A2AConnector] = _a2aConnectors.values.toSeq
def updateA2AConnectors(values: Seq[A2AConnector]): Unit = { ... }
```

---

### Part 4: Files to create/modify

#### New files

| File | Content |
|---|---|
| `entities/a2a.scala` | `A2AServer` and `A2AConnector` entities with Format, DataStore, resource() |
| `plugins/a2a.scala` | `A2AServerPlugin` plugin (NgBackendCall) |
| `a2a/a2a.scala` | A2A models (Task, Message, Part, AgentCard, JSON-RPC errors) |
| `a2a/client.scala` | A2A HTTP client for connectors (send message/send, message/stream) |

#### Files to modify

| File | Modification |
|---|---|
| `extension.scala` | Add entities, datastores, state management |
| `agents/agents.scala` | Add `a2aConnectors` to `AgentConfig`, resolve A2A tools |
| `workflows.scala` | Register new workflow functions if relevant |

---

### Part 5: Security

#### Server side (A2A Server)

Security is handled by Otoroshi at the route level:
- API keys
- JWT tokens
- mTLS
- etc.

The Agent Card declares `securitySchemes` matching the route configuration. This is informational for A2A clients.

#### Connector side (A2A Connector)

The connector handles authentication to the remote server:
- Token/credentials are stored in the connector config
- They are injected into outgoing HTTP requests
- Support for Otoroshi's secret vaulting to avoid storing secrets in plain text

---

### Part 6: Implementation plan

#### Phase 1: Foundations (MVP)

1. **A2A models** (`a2a/a2a.scala`)
   - Case classes for Task, Message, Part, AgentCard, Status
   - JSON serialization/deserialization
   - A2A JSON-RPC errors

2. **A2AServer entity** (`entities/a2a.scala`)
   - Case class, Format, DataStore, resource()
   - Registration in the extension

3. **A2AServerPlugin** (`plugins/a2a.scala`)
   - JSON-RPC dispatch
   - Synchronous `message/send` only
   - Basic A2A ↔ ChatMessage mapping (TextPart only)
   - Agent Card endpoint
   - Task storage in Redis

4. **Unit tests** for models and mapping

#### Phase 2: Streaming and multi-modal

5. **`message/stream`** with SSE
   - Reuse the existing streaming mechanism from providers
   - Map to A2A SSE events

6. **Multi-modal support**
   - FilePart (images, documents)
   - DataPart (structured data)

7. **`tasks/get` and `tasks/cancel`**

#### Phase 3: A2A Connector

8. **A2AConnector entity** (`entities/a2a.scala`)
   - Case class, Format, DataStore, resource()
   - Registration in the extension

9. **A2A client** (`a2a/client.scala`)
   - Fetch Agent Card
   - Call `message/send`
   - Agent Card caching

10. **Integration into AgentConfig**
    - Resolve skills as tool functions
    - Execute remote A2A calls

#### Phase 4: Advanced features

11. **`tasks/list`**
12. **`tasks/resubscribe`**
13. **Push notifications** (webhooks)
14. **Streaming A2A Connector** (`message/stream` on client side)
15. **OAuth2 client credentials** for connectors
16. **Multi-tenant** (`/{tenant}/` URL prefix)

---

### Part 7: Open questions (to decide)

#### Q1: A2A Server backend

**Options**:
- **(A)** Inline agent only (`AgentConfig` in the A2AServer config)
- **(B)** Inline agent + workflow reference
- **(C)** Inline agent + workflow + existing route reference

**Recommendation**: start with **(B)** (inline agent + workflow). Route reference is more complex and less useful initially.

#### Q2: Task storage

**Options**:
- **(A)** Redis with TTL (default 24h), all tasks
- **(B)** Redis with short TTL for completed/failed (1h), long TTL for working/input_required (24h)
- **(C)** In-memory with bounded cache (e.g., max 10000 tasks), no persistence

**Recommendation**: **(B)** - Redis with differentiated TTL. Completed tasks don't need to be kept for long.

#### Q3: Skill-to-tool mapping

**Options**:
- **(A)** One tool function per skill, with a `message` text parameter
- **(B)** One tool function per skill, with parameters extracted from skill inputModes/schema
- **(C)** One tool function per connector with `skill_id` + `message` parameters

**Recommendation**: **(A)** to start. Simple and functional. Can be enriched later.

#### Q4: Agent Card refresh

**Options**:
- **(A)** On every state sync (~30s)
- **(B)** Cache with configurable TTL (default 5 min)
- **(C)** Fetch only on startup + endpoint to force refresh

**Recommendation**: **(B)** - cache with TTL. Good balance between freshness and performance.

#### Q5: Multi-turn / contextId management

When an A2A client sends a `contextId`, it expects the agent to have context from previous exchanges.

**Options**:
- **(A)** Store message history per contextId in Redis, pass as input to the agent
- **(B)** Use the existing `PersistentMemory` system, with contextId as sessionId
- **(C)** Don't support multi-turn initially (each message is independent)

**Recommendation**: **(A)** for MVP - store history in Redis associated with contextId. **(B)** would be good long-term to benefit from semantic memory.

#### Q6: A2A tool naming in local agents

When an A2A Connector exposes skills as tools, how to name them?

**Options**:
- **(A)** `a2a_{connector_name_slug}_{skill_id}` (e.g., `a2a_route_planner_optimize_route`)
- **(B)** `a2a_{connector_id}_{skill_id}` (e.g., `a2a_a2a-connector_xxx_optimize_route`)
- **(C)** Configurable in the connector (skill_id → tool_name mapping)

**Recommendation**: **(A)** with the option of **(C)** via an optional mapping field.

---

### Part 8: Usage examples

#### Exposing a math tutor agent as A2A

1. Create an A2AServer:
```json
{
  "id": "a2a-server_math",
  "name": "Math Tutor A2A",
  "agent_card": {
    "version": "1.0.0",
    "skills": [{"id": "math", "name": "Math Help", "description": "Help with math", "tags": ["math"]}]
  },
  "backend": {
    "kind": "agent",
    "agent": {
      "name": "Math Tutor",
      "instructions": ["You help with math problems..."],
      "provider": "provider_xxx"
    }
  }
}
```

2. Create an Otoroshi route pointing to this plugin with config `{"ref": "a2a-server_math"}`

3. An external A2A client can now:
   - Discover the agent via `GET https://my-otoroshi/math-tutor/.well-known/agent.json`
   - Send messages via `POST https://my-otoroshi/math-tutor/` with JSON-RPC

#### Using a remote A2A agent as a tool

1. Create an A2AConnector:
```json
{
  "id": "a2a-connector_planner",
  "name": "Route Planner",
  "url": "https://route-planner.example.com",
  "authentication": {"kind": "bearer", "token": "sk-xxx"}
}
```

2. In an AgentConfig, reference the connector:
```json
{
  "name": "Travel Assistant",
  "instructions": ["You help plan trips..."],
  "provider": "provider_xxx",
  "a2a_connectors": ["a2a-connector_planner"]
}
```

3. The local agent can now call the Route Planner's skills as tools.

#### Combining A2A Server + Connector for agent chaining

An agent exposed via A2A can itself use A2A Connectors to delegate work to other A2A agents, creating an interoperable agent network.

```
A2A Client ──▶ Otoroshi (A2A Server: Travel Agent)
                    │
                    ├──▶ A2A Connector → Weather Agent (external)
                    ├──▶ A2A Connector → Booking Agent (external)
                    └──▶ MCP Connector → Maps API (local)
```
