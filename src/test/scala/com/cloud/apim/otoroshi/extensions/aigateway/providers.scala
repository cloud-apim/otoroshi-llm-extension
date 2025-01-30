package com.cloud.apim.otoroshi.extensions.aigateway

import play.api.libs.json.{JsObject, Json}

case class TestLlmProviderSettings(
  name: String, 
  envToken: String, 
  options: JsObject, 
  baseUrl: Option[String] = None,
  supportsChatCompletion: Boolean,
  supportsCompletion: Boolean,
  supportsModels: Boolean,
  supportsStreaming: Boolean,
  supportsTools: Boolean,
  supportsToolsStreaming: Boolean,
)

object LlmProviders {

  val maxTokens = 32

  val openai = TestLlmProviderSettings(
    "openai",
    "OPENAI_TOKEN",
    Json.obj("model" -> "gpt-4o-mini", "max_tokens" -> maxTokens),
    supportsModels = true,
    supportsChatCompletion = true,
    supportsCompletion = false,
    supportsTools = true,
    supportsStreaming = true,
    supportsToolsStreaming = true,
  )

  val openaiCompletion = TestLlmProviderSettings(
    "openai",
    "OPENAI_TOKEN",
    Json.obj("model" -> "gpt-3.5-turbo-instruct", "max_tokens" -> maxTokens),
    supportsModels = false,
    supportsChatCompletion = false,
    supportsCompletion = true,
    supportsTools = false,
    supportsStreaming = false,
    supportsToolsStreaming = false,
  )

  val ollama = TestLlmProviderSettings(
    "ollama",
    "OLLAMA_TOKEN",
    Json.obj("model" -> "llama3.2:latest", "num_predict" -> maxTokens),
    supportsModels = true,
    supportsChatCompletion = true,
    supportsCompletion = true,
    supportsTools = true,
    supportsStreaming = true,
    supportsToolsStreaming = false,
  )

  val deepseekBeta = TestLlmProviderSettings(
    "deepseek",
    "DEEPSEEK_TOKEN",
    Json.obj("model" -> "deepseek-chat", "max_tokens" -> maxTokens),
    baseUrl = Some("https://api.deepseek.com/beta"),
    supportsModels = false,
    supportsChatCompletion = true,
    supportsCompletion = false,
    supportsTools = true,
    supportsStreaming = true,
    supportsToolsStreaming = false,
  )

  val deepseek = TestLlmProviderSettings(
    "deepseek",
    "DEEPSEEK_TOKEN",
    Json.obj("model" -> "deepseek-chat", "max_tokens" -> maxTokens),
    baseUrl = Some("https://api.deepseek.com"),
    supportsModels = true,
    supportsChatCompletion = true,
    supportsCompletion = false,
    supportsTools = true,
    supportsStreaming = true,
    supportsToolsStreaming = false,
  )

  val mistral = TestLlmProviderSettings(
    "mistral",
    "MISTRAL_TOKEN",
    Json.obj("model" -> "mistral-large-latest", "max_tokens" -> maxTokens),
    supportsModels = true,
    supportsChatCompletion = true,
    supportsCompletion = false, // because FIM
    supportsTools = true,
    supportsStreaming = true,
    supportsToolsStreaming = false,
  )

  val mistralCompletion = TestLlmProviderSettings(
    "mistral",
    "MISTRAL_TOKEN",
    Json.obj("model" -> "codestral-latest", "max_tokens" -> maxTokens),
    supportsModels = false,
    supportsChatCompletion = false,
    supportsCompletion = true, // only testing FIM
    supportsTools = false,
    supportsStreaming = false,
    supportsToolsStreaming = false,
  )

  val gemini = TestLlmProviderSettings(
    "gemini",
    "GEMINI_API_KEY",
    Json.obj("maxOutputTokens" -> maxTokens),
    supportsModels = true,
    supportsChatCompletion = true,
    supportsCompletion = true,
    supportsTools = true,
    supportsStreaming = true,
    supportsToolsStreaming = true,
  )

  val scaleway = TestLlmProviderSettings(
    "scaleway",
    "SCALEWAY_TOKEN", Json.obj("model" -> "llama-3.1-8b-instruct", "max_tokens" -> maxTokens),
    supportsModels = true,
    supportsChatCompletion = true,
    supportsCompletion = true,
    supportsTools = true,
    supportsStreaming = true,
    supportsToolsStreaming = true,
  )

  val huggingface = TestLlmProviderSettings(
    "huggingface",
    "HUGGINGFACE_TOKEN",
    Json.obj("model" -> "google/gemma-2-2b-it", "max_tokens" -> maxTokens, "top_p" -> 0.9),
    supportsModels = false,
    supportsChatCompletion = true,
    supportsCompletion = true,
    supportsTools = false,
    supportsStreaming = true,
    supportsToolsStreaming = false,
  )

  val groq = TestLlmProviderSettings(
    "groq",
    "GROQ_TOKEN",
    Json.obj("model" -> "llama3-8b-8192", "max_tokens" -> maxTokens),
    supportsModels = true,
    supportsChatCompletion = true,
    supportsCompletion = true,
    supportsTools = true,
    supportsStreaming = true,
    supportsToolsStreaming = false,
  )

  val cohere = TestLlmProviderSettings(
    "cohere",
    "COHERE_TOKEN",
    Json.obj("model" -> "command-r7b-12-2024", "max_tokens" -> maxTokens),
    supportsModels = true,
    supportsChatCompletion = true,
    supportsCompletion = true,
    supportsTools = true,
    supportsStreaming = true,
    supportsToolsStreaming = false,
  )

  val azure = TestLlmProviderSettings( // TODO: no account
    "azure-openai",
    "AZURE_OPENAI_TOKEN",
    Json.obj("model" -> "gpt-4o-mini", "max_tokens" -> maxTokens),
    supportsModels = true,
    supportsChatCompletion = true,
    supportsCompletion = true,
    supportsTools = true,
    supportsStreaming = true,
    supportsToolsStreaming = true,
  )

  val cloudflare = TestLlmProviderSettings(  // TODO: no account
    "cloudflare",
    "CLOUDFLARE_TOKEN",
    Json.obj("model" -> "claude-3-5-sonnet-latest", "max_tokens" -> maxTokens),
    supportsModels = true,
    supportsChatCompletion = true,
    supportsCompletion = false, // TODO: checkit
    supportsTools = false, // TODO: checkit
    supportsStreaming = true,
    supportsToolsStreaming = false,
  )

  val ovh = TestLlmProviderSettings(
    "ovh-ai-endpoints",
    "OVH_AI_TOKEN",
    Json.obj("model" -> "Meta-Llama-3-70B-Instruct", "max_tokens" -> maxTokens),
    supportsModels = true,
    supportsChatCompletion = true,
    supportsCompletion = true,
    supportsTools = false,
    supportsStreaming = true,
    supportsToolsStreaming = false,
  )

  val anthropic = TestLlmProviderSettings(
    "anthropic",
    "ANTHROPIC_TOKEN",
    Json.obj("model" -> "claude-3-5-sonnet-latest", "max_tokens" -> maxTokens),
    supportsModels = true,
    supportsChatCompletion = true,
    supportsCompletion = true,
    supportsTools = true,
    supportsStreaming = true,
    supportsToolsStreaming = true,
  )

  val xai = TestLlmProviderSettings(
    "x-ai",
    "XAI_TOKEN",
    Json.obj("model" -> "grok-beta", "max_tokens" -> maxTokens),
    supportsModels = true,
    supportsChatCompletion = true,
    supportsCompletion = true,
    supportsTools = true,
    supportsStreaming = true,
    supportsToolsStreaming = true,
  )

  val all = List(
    // openai,
    // openaiCompletion,
    // ollama,
    // deepseek,
    // deepseekBeta,
    // mistral,
    // mistralCompletion,
    // gemini,
    // scaleway,
    // huggingface,
    // groq,
    cohere,
    // anthropic,
    // xai,
    // ovh,

    // azure,
    // cloudflare,
  )

  val listOfProvidersSupportingChatCompletions = all.filter(_.supportsChatCompletion)

  val listOfProvidersSupportingChatCompletionsStream = all.filter(v => v.supportsChatCompletion && v.supportsStreaming)

  val listOfProvidersSupportingCompletions = all.filter(_.supportsCompletion)

  val listOfProvidersSupportingModels = all.filter(_.supportsModels)

  val listOfProvidersSupportingTools = all.filter(_.supportsTools)

  val listOfProvidersSupportingToolsStream = all.filter(v => v.supportsTools && v.supportsToolsStreaming)

}
