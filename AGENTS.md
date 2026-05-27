# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Project Overview

Otoroshi LLM Extension is a set of Otoroshi plugins for building an AI/LLM gateway. It provides a unified interface to 20+ LLM providers with features like load balancing, fallbacks, semantic caching, guardrails, and budget management.

**Requirements**: JDK 17+ and Scala 2.12

## Build Commands

```bash
# Compile the project
sbt compile

# Run tests (tests run sequentially, not in parallel)
sbt test

# Run a single test suite
sbt "testOnly com.cloud.apim.otoroshi.extensions.aigateway.ProvidersSuite"

# Build fat JAR for deployment
sbt assembly
# Output: target/scala-2.12/otoroshi-llm-extension-assembly_2.12-dev.jar
```

## Architecture

### Package Structure

All source code lives under `src/main/scala/com/cloud/apim/otoroshi/extensions/aigateway/`:

- **extension.scala** - Main entry point (`AiExtension`), extends Otoroshi's `AdminExtension`
- **models.scala** - Core chat abstractions (`ChatMessage`, `ChatPrompt`, `ChatResponse`, `ChatClient`)
- **providers/** - LLM provider implementations (OpenAI, Anthropic, Azure, Mistral, Groq, etc.)
- **plugins/** - Otoroshi plugins (`LLMProxy`, `OpenAiCompatibleProxy`, rate limiting, audio, images, etc.)
- **decorators/** - Cross-cutting concerns using decorator pattern (caching, guardrails, memory, costs)
- **guardrails/** - Validation implementations (LLM-based, regex, moderation, webhook, WASM)
- **entities/** - Configuration entities stored in Redis (providers, prompts, templates, budgets)
- **agents/** - Agent implementations for agentic workflows
- **workflows.scala** - Otoroshi workflow function integrations

### Key Patterns

**Decorator Chain** (`decorators/decorators.scala`): Chat clients are wrapped with decorators for cross-cutting concerns. The chain is applied in `ChatClientDecorators.withDecorators()`:
1. Model Constraints → 2. Provider Fallback → 3. Persistent Memory → 4. Semantic Cache → 5. Simple Cache → 6. Guardrails → 7. Costs → 8. Auditing

**Provider Implementation**: Each provider in `providers/` follows this pattern:
- Options class (e.g., `OpenAiChatClientOptions`) extending `ChatOptions`
- Client class implementing `ChatClient` trait with `call()` and `stream()` methods
- Model enumeration and token counting

**Plugin System**: Plugins in `plugins/` extend Otoroshi's `NgRequestTransformer` or related traits. The main LLM proxy is `LLMProxy` in `proxy.scala`.

### State Management

`AiGatewayExtensionState` holds in-memory cache of all entities using `UnboundedTrieMap`. Data is persisted to Redis via `AiGatewayExtensionDatastores`.

### Multi-Modal Support

The system supports text, images, audio, and video through:
- `InputChatMessage` with multi-part content (`InputTextContent`, `InputImageContent`, etc.)
- Dedicated plugins: `audio.scala`, `imagesgen.scala`, `videosgen.scala`
- Provider-specific flavors for content formatting

## Testing

Tests are in `src/test/scala/` using MUnit. Key test suites in `suites/`:
- `providers.scala` - Provider functionality
- `plugins.scala` - Plugin behavior
- `cache.scala` - Caching mechanisms
- `guardrails.scala` - Validation rules
- `resilience.scala` - Error handling and fallbacks

Test configuration uses `domains/providers.scala` for test data factories.

## Dependencies

- **Otoroshi 17.11.0** - Base API gateway (provided dependency)
- **Langchain4j 1.9.1** - LLM integration library
- **Jlama 0.8.4** - Local LLM inference
- **Jackson 2.15.3** - JSON processing
