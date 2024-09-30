# Cloud APIM - Otoroshi LLM Extension

[![Otoroshi LLM Extension introduction](https://img.youtube.com/vi/M8sA9xuE3gs/0.jpg)](https://www.youtube.com/watch?v=M8sA9xuE3gs "🚀 Cloud APIM - AI LLM Gateway : Unlocking the Power of AI in API Management 🤖✨")

**Connect, setup, secure and seamlessly manage AI models using an Universal/OpenAI compatible API**

  - **Unified interface**: Simplify interactions and minimize integration hassles
  - **Use multiple providers**: 10+ LLM providers supported right now, a lot more coming
  - **Load balancing**: Ensure optimal performance by distributing workloads across multiple providers
  - **Fallbacks**: Automatically switch LLMs during failures to deliver uninterrupted & accurate performance
  - **Automatic retries**: LLM APIs often have inexplicable failures. You can rescue a substantial number of your requests with our in-built automatic retries feature.
  - **Semantic cache**: Speed up repeated queries, enhance response times, and reduce costs
  - **Custom quotas**: Manage LLM tokens quotas per consumer and optimise costs
  - **Key vault**: securely store your LLM API keys in Otoroshi vault or any other secret vault supported by Otoroshi.
  - **Observability and reporting**: every LLM request is audited with details about the consumer, the LLM provider and usage. All those audit events are exportable using multiple methods for further reporting
  - **Fine grained authorizations**: Use Otoroshi advanced fine grained authorizations capabilities to constrains model usage based on whatever you want: user identity, apikey, consumer metadata, request details, etc
  - **Prompt Fences**: Validate your prompts and prompts responses to avoid sensitive or personal informations leakage, irrelevant or unhelpful responses, gibberish content, etc
  - **Prompt engineering**: enhance your experience by providing contextual information to your prompts, storing them in a library for reusability, and using prompt templates for increased efficiency

Otoroshi LLM Extension is set of Otoroshi plugins and resources to interact with LLMs. To know more about it, go to [documentation](https://cloud-apim.github.io/otoroshi-llm-extension/)

## Supported LLM providers

* OpenAI
* Azure OpenAI
* Ollama
* Mistral
* Anthropic
* Cohere
* Gemini
* Groq
* Huggingface
* OVH AI Endpoints


