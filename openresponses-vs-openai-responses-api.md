# OpenResponses vs OpenAI Responses API - Compatibility Analysis

## 1. Endpoints

| Endpoint | OpenAI | OpenResponses |
|---|---|---|
| `POST /responses` | Yes | Yes |
| `GET /responses/{id}` | Yes | No |
| `DELETE /responses/{id}` | Yes | No |
| `POST /responses/{id}/cancel` | Yes | No |
| `GET /responses/{id}/input_items` | Yes | No |
| `POST /responses/input_tokens` | Yes | No |
| `POST /responses/compact` | Yes | No |

**OpenResponses only exposes a single endpoint** (`POST /responses`) out of the 7 OpenAI API endpoints.

## 2. CreateResponse Fields (Request Body)

| Field | OpenAI | OpenResponses |
|---|---|---|
| `model` | Yes | Yes |
| `input` | Yes | Yes |
| `instructions` | Yes | Yes |
| `previous_response_id` | Yes | Yes |
| `store` | Yes | Yes |
| `stream` | Yes | Yes |
| `stream_options` | Yes | Yes |
| `temperature` | Yes | Yes |
| `top_p` | Yes | Yes |
| `top_logprobs` | Yes | Yes |
| `max_output_tokens` | Yes | Yes |
| `max_tool_calls` | Yes | Yes |
| `tools` | Yes | Yes |
| `tool_choice` | Yes | Yes |
| `parallel_tool_calls` | Yes | Yes |
| `metadata` | Yes | Yes |
| `truncation` | Yes | Yes |
| `reasoning` | Yes | Yes |
| `text` | Yes | Yes |
| `include` | Yes | Yes |
| `background` | Yes | Yes |
| `service_tier` | Yes | Yes |
| `presence_penalty` | Yes | Yes |
| `frequency_penalty` | Yes | Yes |
| `safety_identifier` | Yes | Yes |
| `prompt_cache_key` | Yes | Yes |
| `user` (deprecated) | Yes | No |
| `conversation` | Yes | No |
| `context_management` | Yes | No |
| `prompt_cache_retention` | Yes | No |
| `prompt` | Yes | No |

## 3. Supported Tools

| Tool Type | OpenAI | OpenResponses |
|---|---|---|
| `function` | Yes | Yes |
| `file_search` | Yes | No |
| `web_search` / `web_search_preview` | Yes | No |
| `code_interpreter` | Yes | No |
| `computer` / `computer_use_preview` | Yes | No |
| `image_generation` | Yes | No |
| `mcp` (MCP servers) | Yes | No |
| `local_shell` | Yes | No |
| `function_shell` | Yes | No |
| `custom` | Yes | No |
| `namespace` | Yes | No |
| `tool_search` | Yes | No |
| `apply_patch` | Yes | No |

**OpenResponses only supports `function` tools.** All OpenAI built-in tools (web search, file search, code interpreter, computer use, image generation, MCP) are missing.

## 4. IncludeEnum

| Value | OpenAI | OpenResponses |
|---|---|---|
| `reasoning.encrypted_content` | Yes | Yes |
| `message.output_text.logprobs` | Yes | Yes |
| `file_search_call.results` | Yes | No |
| `web_search_call.results` | Yes | No |
| `web_search_call.action.sources` | Yes | No |
| `message.input_image.image_url` | Yes | No |
| `computer_call_output.output.image_url` | Yes | No |
| `code_interpreter_call.outputs` | Yes | No |

## 5. Streaming Events

| Event | OpenAI | OpenResponses |
|---|---|---|
| `response.created` | Yes | Yes |
| `response.queued` | Yes | Yes |
| `response.in_progress` | Yes | Yes |
| `response.completed` | Yes | Yes |
| `response.failed` | Yes | Yes |
| `response.incomplete` | Yes | Yes |
| `response.output_item.added` | Yes | Yes |
| `response.output_item.done` | Yes | Yes |
| `response.content_part.added` | Yes | Yes |
| `response.content_part.done` | Yes | Yes |
| `response.output_text.delta` | Yes | Yes |
| `response.output_text.done` | No | Yes |
| `response.output_text.annotation.added` | Yes | Yes |
| `response.reasoning_summary_part.added` | Yes | Yes |
| `response.reasoning_summary_part.done` | Yes | Yes |
| `response.reasoning.delta` | Yes | Yes |
| `response.reasoning.done` | Yes | Yes |
| `response.reasoning_summary.delta` | Yes | Yes |
| `response.reasoning_summary.done` | Yes | Yes |
| `response.function_call_arguments.delta` | Yes | Yes |
| `response.function_call_arguments.done` | Yes | Yes |
| `response.refusal.delta` | Yes | Yes |
| `response.refusal.done` | Yes | Yes |
| `error` | Yes | Yes |
| `response.text.delta` | Yes | No |
| `response.text.done` | Yes | No |
| `response.audio.delta` | Yes | No |
| `response.audio.done` | Yes | No |
| `response.audio_transcript.delta` | Yes | No |
| `response.audio_transcript.done` | Yes | No |
| `response.code_interpreter_call.*` (5 events) | Yes | No |
| `response.file_search_call.*` (3 events) | Yes | No |
| `response.web_search_call.*` (3 events) | Yes | No |
| `response.image_gen_call.*` (4 events) | Yes | No |
| `response.mcp_call.*` (3 events) | Yes | No |
| `response.mcp_list_tools.*` (3 events) | Yes | No |
| `response.custom_tool_call.*` (2 events) | Yes | No |

## 6. Response Object (ResponseResource)

The response object properties are **nearly identical** between the two specs. OpenResponses has all the main fields (`id`, `object`, `status`, `output`, `usage`, `model`, etc.).

## 7. Conclusion

**No, OpenResponses is not fully compatible with the OpenAI `/v1/responses` API.** Here are the major gaps:

1. **6 out of 7 endpoints missing**: only `POST /responses` is implemented. No GET, DELETE, cancel, input_items listing, input_tokens counting, or compact.

2. **Tools limited to `function` tools only**: no built-in tools (web search, file search, code interpreter, computer use, image generation, MCP tools, etc.).

3. **Recent fields missing**: `conversation`, `context_management`, `prompt_cache_retention`, `prompt`.

4. **Reduced include enum**: only 2 out of 8 values.

5. **Partial streaming events**: the basic events (text, reasoning, function call) are present, but all events related to built-in tools (code_interpreter, file_search, web_search, image_gen, MCP, audio) are missing.

In summary, **OpenResponses covers the basic "text generation + function calling" use case with streaming**, but does not cover OpenAI's advanced features (built-in tools, persistent conversation management, compaction, etc.).
