package com.cloud.apim.otoroshi.extensions.aigateway.providers

import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsArray, JsNull, JsObject, JsString, Json}


////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/////////                                    OpenAI-like Providers                                       ///////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

case class OpenAiLikeProviderDef(
                                  id: String,
                                  name: String,
                                  baseUrl: String,
                                  paramMappings: Map[String, String] = Map.empty,
                                  headers: Map[String, String] = Map("Authorization" -> "Bearer {api_key}"),
                                  apiKeyEnv: Option[String] = None,
                                  additionalBodyParams: JsObject = Json.obj(),
                                ) {
  def json: JsObject = Json.obj(
    "id"                    -> id,
    "name"                  -> name,
    "base_url"              -> baseUrl,
    "param_mappings"        -> JsObject(paramMappings.map { case (k, v) => k -> JsString(v) }),
    "headers"               -> JsObject(headers.map { case (k, v) => k -> JsString(v) }),
    "api_key_env"           -> apiKeyEnv.map(JsString.apply).getOrElse(JsNull).asValue,
    "additional_body_params" -> additionalBodyParams,
  )
}

object OpenAiLikeProviders {
  private val mct = Map("max_completion_tokens" -> "max_tokens")
  private val sarvamHeaders =  Map("api-subscription-key" -> "{api_key}")

  val all: Seq[OpenAiLikeProviderDef] = Seq(
    // from litellm openai_like/providers.json
    OpenAiLikeProviderDef("helicone",      "Helicone",         "https://ai-gateway.helicone.ai/",                           apiKeyEnv = Some("HELICONE_API_KEY")),
    OpenAiLikeProviderDef("veniceai",      "Venice AI",        "https://api.venice.ai/api/v1",                              apiKeyEnv = Some("VENICE_AI_API_KEY")),
    OpenAiLikeProviderDef("xiaomi-mimo",   "Xiaomi Mimo",      "https://api.xiaomimimo.com/v1",         mct,                apiKeyEnv = Some("XIAOMI_MIMO_API_KEY")),
    OpenAiLikeProviderDef("synthetic",     "Synthetic",        "https://api.synthetic.new/openai/v1",   mct,                apiKeyEnv = Some("SYNTHETIC_API_KEY")),
    OpenAiLikeProviderDef("apertis",       "Apertis",          "https://api.stima.tech/v1",             mct,                apiKeyEnv = Some("STIMA_API_KEY")),
    OpenAiLikeProviderDef("nano-gpt",      "Nano GPT",         "https://nano-gpt.com/api/v1",           mct,                apiKeyEnv = Some("NANOGPT_API_KEY")),
    OpenAiLikeProviderDef("poe",           "Poe",              "https://api.poe.com/v1",                mct,                apiKeyEnv = Some("POE_API_KEY")),
    OpenAiLikeProviderDef("chutes",        "Chutes",           "https://llm.chutes.ai/v1/",             mct,                apiKeyEnv = Some("CHUTES_API_KEY")),
    OpenAiLikeProviderDef("abliteration",  "Abliteration",     "https://api.abliteration.ai/v1",                            apiKeyEnv = Some("ABLITERATION_API_KEY")),
    OpenAiLikeProviderDef("llamagate",     "LlamaGate",        "https://api.llamagate.dev/v1",          mct,                apiKeyEnv = Some("LLAMAGATE_API_KEY")),
    OpenAiLikeProviderDef("gmi",           "GMI",              "https://api.gmi-serving.com/v1",                            apiKeyEnv = Some("GMI_API_KEY")),
    OpenAiLikeProviderDef("sarvam",        "Sarvam",           "https://api.sarvam.ai/v1",              mct, sarvamHeaders, apiKeyEnv = Some("SARVAM_API_KEY")),
    OpenAiLikeProviderDef("assemblyai",    "AssemblyAI",       "https://llm-gateway.assemblyai.com/v1",                     apiKeyEnv = Some("ASSEMBLYAI_API_KEY")),
    OpenAiLikeProviderDef("minimax",       "Minimax",          "https://api.minimax.io/v1",                                 apiKeyEnv = Some("MINIMAX_API_KEY")),
    OpenAiLikeProviderDef("morph",         "Morph",            "https://api.morphllm.com/v1",           mct,                apiKeyEnv = Some("MORPH_API_KEY")),
    OpenAiLikeProviderDef("aiml",          "AI/ML API",        "https://api.aimlapi.com/v1",                                apiKeyEnv = Some("AIML_API_KEY")),
    OpenAiLikeProviderDef("cerebras",      "Cerebras",         "https://api.cerebras.ai/v1",            mct,                apiKeyEnv = Some("CEREBRAS_API_KEY")),
    OpenAiLikeProviderDef("cometapi",      "CometAPI",         "https://api.cometapi.com/v1",                               apiKeyEnv = Some("COMETAPI_API_KEY")),
    OpenAiLikeProviderDef("compactifai",   "CompactifAI",      "https://api.compactif.ai/v1",                               apiKeyEnv = Some("COMPACTIFAI_API_KEY")),
    OpenAiLikeProviderDef("deepinfra",     "DeepInfra",        "https://api.deepinfra.com/v1/openai",   mct,                apiKeyEnv = Some("DEEPINFRA_API_KEY")),
    OpenAiLikeProviderDef("empower",       "Empower",          "https://app.empower.dev/api/v1",                            apiKeyEnv = Some("EMPOWER_API_KEY")),
    OpenAiLikeProviderDef("featherless-ai","Featherless AI",   "https://api.featherless.ai/v1",         mct,                apiKeyEnv = Some("FEATHERLESS_AI_API_KEY")),
    OpenAiLikeProviderDef("fireworks-ai",  "Fireworks AI",     "https://api.fireworks.ai/inference/v1", mct,                apiKeyEnv = Some("FIREWORKS_API_KEY")),
    OpenAiLikeProviderDef("friendliai",    "Friendli AI",      "https://api.friendli.ai/serverless/v1",                     apiKeyEnv = Some("FRIENDLIAI_API_KEY")),
    OpenAiLikeProviderDef("galadriel",     "Galadriel",        "https://api.galadriel.com/v1",                              apiKeyEnv = Some("GALADRIEL_API_KEY")),
    OpenAiLikeProviderDef("hyperbolic",    "Hyperbolic",       "https://api.hyperbolic.xyz/v1",                             apiKeyEnv = Some("HYPERBOLIC_API_KEY")),
    OpenAiLikeProviderDef("lambda-ai",     "Lambda AI",        "https://api.lambda.ai/v1",                                  apiKeyEnv = Some("LAMBDA_API_KEY")),
    OpenAiLikeProviderDef("meta-llama",    "Meta Llama API",   "https://api.llama.com/compat/v1",                           apiKeyEnv = Some("LLAMA_API_KEY")),
    OpenAiLikeProviderDef("nebius",        "Nebius AI Studio", "https://api.studio.nebius.ai/v1",       mct,                apiKeyEnv = Some("NEBIUS_API_KEY")),
    OpenAiLikeProviderDef("novita",        "Novita AI",        "https://api.novita.ai/v3/openai",                           apiKeyEnv = Some("NOVITA_API_KEY")),
    OpenAiLikeProviderDef("nscale",        "Nscale",           "https://inference.api.nscale.com/v1",                       apiKeyEnv = Some("NSCALE_API_KEY")),
    OpenAiLikeProviderDef("nvidia-nim",    "Nvidia NIM",       "https://integrate.api.nvidia.com/v1",   mct,                apiKeyEnv = Some("NVIDIA_NIM_API_KEY")),
    OpenAiLikeProviderDef("perplexity",    "Perplexity",       "https://api.perplexity.ai",                                 apiKeyEnv = Some("PERPLEXITYAI_API_KEY")),
    OpenAiLikeProviderDef("sambanova",     "SambaNova",        "https://api.sambanova.ai/v1",           mct,                apiKeyEnv = Some("SAMBANOVA_API_KEY")),
    OpenAiLikeProviderDef("together-ai",   "Together AI",      "https://api.together.xyz/v1",                               apiKeyEnv = Some("TOGETHERAI_API_KEY")),
    OpenAiLikeProviderDef("zai",           "Z.AI",             "https://api.z.ai/api/paas/v4",                              apiKeyEnv = Some("ZAI_API_KEY")),
    OpenAiLikeProviderDef("openrouter",    "OpenRouter",       "https://openrouter.ai/api/v1",                              apiKeyEnv = Some("OPENROUTER_API_KEY"), additionalBodyParams = Json.obj("usage" -> Json.obj("include" -> true))),
  )
  val allIds: Set[String] = all.map(_.id).toSet
  def json: JsArray = JsArray(all.map(_.json))
  def find(id: String): Option[OpenAiLikeProviderDef] = all.find(_.id == id)
}