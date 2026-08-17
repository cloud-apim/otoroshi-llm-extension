sbt "testOnly com.cloud.apim.otoroshi.extensions.aigateway.suites.ResponsesSuite \
                ...OpenAiResponsesConverterSuite \
                ...CacheSuite ...AuditingSuite ...ResilienceSuite ...LoadBalancerSuite \
                ...QuickJsGuardrailSuite ...ProviderContextSuite \
                ...FileContentPartsProxiesSuite ...FileContentPartsSuite \
                ...CostsTestSuite ...ProviderModelsOverrideSuite ...SearchEngineModelSuite"

sbt "testOnly ...A2ASuite ...McpRegistrySuite ...McpVirtualServerMergeSuite \
                ...McpZeroTrustSuite ...OpenApiMcpClientSuite ...RampartEngineSuite"