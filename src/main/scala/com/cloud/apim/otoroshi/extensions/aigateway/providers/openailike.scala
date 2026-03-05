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
                                ) {
  def json: JsObject = Json.obj(
    "id"             -> id,
    "name"           -> name,
    "base_url"       -> baseUrl,
    "param_mappings" -> JsObject(paramMappings.map { case (k, v) => k -> JsString(v) }),
    "headers"        -> JsObject(headers.map { case (k, v) => k -> JsString(v) }),
    "api_key_env"    -> apiKeyEnv.map(JsString.apply).getOrElse(JsNull).asValue,
  )
}

object OpenAiLikeProviders {
  val all: Seq[OpenAiLikeProviderDef] = Seq(
    OpenAiLikeProviderDef("helicone",     "Helicone",     "https://ai-gateway.helicone.ai/",                                                        apiKeyEnv = Some("HELICONE_API_KEY")),
    OpenAiLikeProviderDef("veniceai",     "Venice AI",    "https://api.venice.ai/api/v1",                                                           apiKeyEnv = Some("VENICE_AI_API_KEY")),
    OpenAiLikeProviderDef("xiaomi-mimo",  "Xiaomi Mimo",  "https://api.xiaomimimo.com/v1",            Map("max_completion_tokens" -> "max_tokens"), apiKeyEnv = Some("XIAOMI_MIMO_API_KEY")),
    OpenAiLikeProviderDef("synthetic",    "Synthetic",    "https://api.synthetic.new/openai/v1",      Map("max_completion_tokens" -> "max_tokens"), apiKeyEnv = Some("SYNTHETIC_API_KEY")),
    OpenAiLikeProviderDef("apertis",      "Apertis",      "https://api.stima.tech/v1",                Map("max_completion_tokens" -> "max_tokens"), apiKeyEnv = Some("STIMA_API_KEY")),
    OpenAiLikeProviderDef("nano-gpt",     "Nano GPT",     "https://nano-gpt.com/api/v1",              Map("max_completion_tokens" -> "max_tokens"), apiKeyEnv = Some("NANOGPT_API_KEY")),
    OpenAiLikeProviderDef("poe",          "Poe",          "https://api.poe.com/v1",                   Map("max_completion_tokens" -> "max_tokens"), apiKeyEnv = Some("POE_API_KEY")),
    OpenAiLikeProviderDef("chutes",       "Chutes",       "https://llm.chutes.ai/v1/",                Map("max_completion_tokens" -> "max_tokens"), apiKeyEnv = Some("CHUTES_API_KEY")),
    OpenAiLikeProviderDef("abliteration", "Abliteration", "https://api.abliteration.ai/v1",                                                         apiKeyEnv = Some("ABLITERATION_API_KEY")),
    OpenAiLikeProviderDef("llamagate",    "LlamaGate",    "https://api.llamagate.dev/v1",             Map("max_completion_tokens" -> "max_tokens"), apiKeyEnv = Some("LLAMAGATE_API_KEY")),
    OpenAiLikeProviderDef("gmi",          "GMI",          "https://api.gmi-serving.com/v1",                                                         apiKeyEnv = Some("GMI_API_KEY")),
    OpenAiLikeProviderDef("sarvam",       "Sarvam",       "https://api.sarvam.ai/v1",                 Map("max_completion_tokens" -> "max_tokens"), Map("api-subscription-key" -> "{api_key}"), Some("SARVAM_API_KEY")),
    OpenAiLikeProviderDef("assemblyai",   "AssemblyAI",   "https://llm-gateway.assemblyai.com/v1",                                                  apiKeyEnv = Some("ASSEMBLYAI_API_KEY")),
    OpenAiLikeProviderDef("minimax",      "Minimax",      "https://api.minimax.io/v1",                                                              apiKeyEnv = Some("MINIMAX_API_KEY")),
    OpenAiLikeProviderDef("morph",        "Morph",        "https://api.morphllm.com/v1",              Map("max_completion_tokens" -> "max_tokens"), apiKeyEnv = Some("MORPH_API_KEY")),
  )
  val allIds: Set[String] = all.map(_.id).toSet
  def json: JsArray = JsArray(all.map(_.json))
  def find(id: String): Option[OpenAiLikeProviderDef] = all.find(_.id == id)
}