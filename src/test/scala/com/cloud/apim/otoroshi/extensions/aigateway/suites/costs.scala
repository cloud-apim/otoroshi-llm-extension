package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.LlmExtensionOneOtoroshiServerPerSuite
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension

class CostsTestSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  test("basic") {
    val providersWithModel = Seq(
      "anthropic/claude-opus-4-7",
      "deepseek/deepseek-v4-flash",
      "gemini###models/antigravity-preview-05-2026",
      "gemini###models/deep-research-max-preview-04-2026",
      "gemini###models/deep-research-preview-04-2026",
      "gemini###models/deep-research-pro-preview-12-2025",
      "gemini###models/gemini-2.0-flash",
      "gemini###models/gemini-2.0-flash-001",
      "gemini###models/gemini-2.0-flash-lite",
      "gemini###models/gemini-2.0-flash-lite-001",
      "gemini###models/gemini-2.5-computer-use-preview-10-2025",
      "gemini###models/gemini-2.5-pro",
      "gemini###models/gemini-3-flash-preview",
      "gemini###models/gemini-3-pro-preview",
      "gemini###models/gemini-3.1-flash-lite",
      "gemini###models/gemini-3.1-flash-lite-preview",
      "gemini###models/gemini-3.1-pro-preview",
      "gemini###models/gemini-3.1-pro-preview-customtools",
      "gemini###models/gemini-3.5-flash",
      "gemini###models/gemini-pro-latest",
      "gemini###models/gemma-4-26b-a4b-it",
      "grok/grok-4.20-multi-agent-0309",
      "grok/grok-build-0.1",
      "mistral/codestral-2508",
      "mistral/codestral-latest",
      "mistral/devstral-2512",
      "mistral/devstral-latest",
      "mistral/devstral-medium-2507",
      "mistral/devstral-medium-latest",
      "mistral/devstral-small-2507",
      "mistral/labs-leanstral-2603",
      "mistral/magistral-medium-2509",
      "mistral/magistral-medium-latest",
      "mistral/magistral-small-2509",
      "mistral/magistral-small-latest",
      "mistral/ministral-14b-2512",
      "mistral/ministral-14b-latest",
      "mistral/ministral-3b-2512",
      "mistral/ministral-3b-latest",
      "mistral/ministral-8b-2512",
      "mistral/ministral-8b-latest",
      "mistral/mistral-large-2411",
      "mistral/mistral-large-2512",
      "mistral/mistral-large-2512",
      "mistral/mistral-large-latest",
      "mistral/mistral-large-latest",
      "mistral/mistral-large-pixtral-2411",
      "mistral/mistral-medium",
      "mistral/mistral-medium-3",
      "mistral/pixtral-large-2411",
      "mistral/pixtral-large-latest",
      "mistral/voxtral-mini-2507",
      "mistral/voxtral-mini-2507",
      "mistral/voxtral-mini-2602",
      "mistral/voxtral-mini-latest",
      "mistral/voxtral-mini-latest",
      "openai/gpt-4o-mini-search-preview",
      "openai/gpt-4o-mini-search-preview-2025-03-11",
      "openai/gpt-4o-search-preview",
      "openai/gpt-4o-search-preview-2025-03-11",
      "openai/gpt-5-codex",
      "openai/gpt-5-mini",
      "openai/gpt-5-mini-2025-08-07",
      "openai/gpt-5-nano",
      "openai/gpt-5-pro",
      "openai/gpt-5-pro-2025-10-06",
      "openai/gpt-5-search-api",
      "openai/gpt-5-search-api-2025-10-14",
      "openai/gpt-5.1-codex",
      "openai/gpt-5.1-codex-max",
      "openai/gpt-5.1-codex-mini",
      "openai/gpt-5.2-codex",
      "openai/gpt-5.2-pro",
      "openai/gpt-5.2-pro-2025-12-11",
      "openai/gpt-5.3-codex",
      "openai/gpt-5.4-pro",
      "openai/gpt-5.4-pro-2026-03-05",
      "openai/gpt-5.5-pro",
      "openai/gpt-5.5-pro-2026-04-23",
      "openai/gpt-audio",
      "openai/gpt-audio-1.5",
      "openai/gpt-audio-2025-08-28",
      "openai/gpt-audio-mini",
      "openai/gpt-audio-mini-2025-10-06",
      "openai/gpt-audio-mini-2025-12-15",
      "openai/o1-pro",
      "openai/o1-pro-2025-03-19",
      "ovhcloud/Llama-3.1-8B-Instruct",
      "ovhcloud/Meta-Llama-3_3-70B-Instruct",
      "ovhcloud/Mistral-Nemo-Instruct-2407",
      "ovhcloud/Qwen2.5-VL-72B-Instruct",
      "ovhcloud/Qwen3-32B",
      "ovhcloud/Qwen3-Coder-30B-A3B-Instruct",
      "ovhcloud/Qwen3.5-397B-A17B",
      "ovhcloud/Qwen3.5-9B",
      "ovhcloud/Qwen3Guard-Gen-0.6B",
      "ovhcloud/Qwen3Guard-Gen-8B",
      "ovhcloud/gpt-oss-120b",
      "ovhcloud/gpt-oss-20b",
    )

    val providersMatching = Map(
      "anthropic" -> "anthropic",
      "deepseek" -> "deepseek",
      "gemini" -> "gemini",
      "grok" -> "x-ai",
      "mistral" -> "mistral",
      "openai" -> "openai",
      "ovhcloud" -> "ovh-ai-endpoints",
    )

    providersWithModel.foreach { pwm =>
      val (provider, model) = if (pwm.contains("###")) {
        val parts = pwm.split("###")
        (parts(0), parts(1))
      } else {
        val parts = pwm.split("/")
        (parts(0), parts(1))
      }
      val otoLlmProvider = providersMatching.getOrElse(provider, provider)
      val ext = otoroshi.env.adminExtensions.extension[AiExtension].get
      val finalProvider = ext.costsTracking.getProvider(otoLlmProvider).getOrElse(otoLlmProvider)
      val costs = ext.costsTracking.computeCosts(finalProvider, model, 10L, 10L, 10L)
      costs match {
        case Left(err) => println(s"Error for ${pwm} ($finalProvider, $model): $err")
        case Right(_) =>
      }

    }
  }

}
