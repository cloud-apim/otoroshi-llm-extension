# Cloud APIM - Otoroshi LLM Extension

[![Otoroshi LLM Extension introduction](https://img.youtube.com/vi/M8sA9xuE3gs/0.jpg)](https://www.youtube.com/watch?v=M8sA9xuE3gs "🚀 Cloud APIM - AI LLM Gateway : Unlocking the Power of AI in API Management 🤖✨")

**Connect, setup, secure and seamlessly manage LLM models using an Universal/OpenAI compatible API**

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

All supported providers are available [here](https://cloud-apim.github.io/otoroshi-llm-extension/docs/providers/providers-list)

* Anthropic 
* Azure OpenAI
* Azure AI Foundry
* Cloudflare
* Cohere
* Gemini
* Groq
* Huggingface 🇫🇷 🇪🇺
* Mistral 🇫🇷 🇪🇺
* Ollama (Local Models)
* OpenAI
* OVH AI Endpoints 🇫🇷 🇪🇺
* Scaleway 🇫🇷 🇪🇺
* X.ai
* Deepseek

## Supported Moderation models

* OpenAI
  * omni-moderation-latest

## Supported Audio Text-to-Speech models

* OpenAI
    * gpt-4o-mini-tts
    * tts-1
    * tts-1-hd
* Groq
    * playai-tts
    * playai-tts-arabic
* ElevenLabs

## Supported Audio Speech-to-text models

* OpenAI
    * whisper-1

* Groq
  * whisper-large-v3

## Supported LLM Embeddings models

* OpenAI
  * text-embedding-3-small
  * text-embedding-3-large
  * text-embedding-ada-002

* Anthropic 
  * voyage-3-large
  * voyage-3
  * voyage-3-lite
  * voyage-code-3
  * voyage-finance-2
  * voyage-law-2 

* Mistral 🇫🇷 🇪🇺
  * mistral-embed

## Supported Image generation models

* OpenAI 
  * dall-e-2
  * dall-e-3
  * gpt-image-1
* Azure OpenAI
* Grok
  * grok-2-image
* Luma
  * photon-1 (default)
  * photon-flash-1
* Hive
  * black-forest-labs/flux-schnell

## Supported Video generation models

* Luma
  * ray-flash-2

## Requirements

**Run it on JDK17+**


