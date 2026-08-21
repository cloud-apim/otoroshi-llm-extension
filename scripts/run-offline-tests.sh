#!/bin/sh
# Runs only the suites that do not need a real LLM provider (no API keys, no network calls
# to openai/anthropic/...). Everything else in src/test needs credentials.
#
# NOTE: `testOnly` only understands `*` as a wildcard, so suites are matched with `*Name`.

set -e

sbt "testOnly *ResponsesSuite *OpenAiResponsesConverterSuite \
              *CacheSuite *suites.AuditingSuite *ResilienceSuite *LoadBalancerSuite \
              *QuickJsGuardrailSuite *ProviderContextSuite \
              *FileContentPartsProxiesSuite *FileContentPartsSuite \
              *CostsTestSuite *ProviderModelsOverrideSuite *SearchEngineModelSuite"

sbt "testOnly *A2ASuite *McpRegistrySuite *McpVirtualServerMergeSuite \
              *McpZeroTrustSuite *OpenApiMcpClientSuite *RampartEngineSuite"
