package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.OpenAiResponsesBodyConverter
import com.cloud.apim.otoroshi.extensions.aigateway.providers.*
import play.api.libs.json.{JsObject, JsValue, Json}

// Sampling parameters (`temperature`, `top_p`, `n`) that are neither in the provider config nor in the
// request must not reach the provider: reasoning models (o-series, gpt-5, ...) reject `top_p` outright
// (issue #197). Configured values are still forwarded.
class SamplingOptionsSuite extends munit.FunSuite {

  private val sampling = Seq("temperature", "top_p", "n")

  private case class Provider(name: String, template: JsObject, fromJson: JsValue => JsObject, topPKey: String, hasN: Boolean)

  private val providers = Seq(
    Provider("openai", OpenAiChatClientOptions().json, j => OpenAiChatClientOptions.fromJson(j).jsonForCall, "top_p", hasN = true),
    Provider("azure-openai", AzureOpenAiChatClientOptions().json, j => AzureOpenAiChatClientOptions.fromJson(j).jsonForCall, "top_p", hasN = true),
    Provider("xai", XAiChatClientOptions().json, j => XAiChatClientOptions.fromJson(j).jsonForCall, "top_p", hasN = true),
    Provider("mistral", MistralAiChatClientOptions().json, j => MistralAiChatClientOptions.fromJson(j).jsonForCall, "top_p", hasN = false),
    Provider("groq", GroqChatClientOptions().json, j => GroqChatClientOptions.fromJson(j).jsonForCall, "top_p", hasN = true),
    Provider("ovh", OVHAiEndpointsChatClientOptions().json, j => OVHAiEndpointsChatClientOptions.fromJson(j).jsonForCall, "topP", hasN = false),
  )

  providers.foreach { p =>
    test(s"${p.name}: absent sampling parameters are not sent") {
      val call = p.fromJson(Json.obj())
      sampling.foreach(k => assert(!call.keys.contains(k), s"'$k' should not be in $call"))
    }
    test(s"${p.name}: the provider template does not write sampling parameters") {
      sampling.foreach(k => assert(p.template.select(k).asOpt[JsValue].forall(_ == play.api.libs.json.JsNull), s"'$k' should not be in ${p.template}"))
      val call = p.fromJson(p.template)
      sampling.foreach(k => assert(!call.keys.contains(k), s"'$k' should not be in $call"))
    }
    test(s"${p.name}: configured sampling parameters are sent") {
      val call = p.fromJson(Json.obj("temperature" -> 0.2, p.topPKey -> 0.9, "n" -> 2))
      assertEquals(call.select("temperature").asOpt[Double].map(d => math.round(d * 100)), Some(20L))
      assertEquals(call.select("top_p").asOpt[Double].map(d => math.round(d * 100)), Some(90L))
      if (p.hasN) assertEquals(call.select("n").asOpt[Int], Some(2))
    }
  }

  test("azure-openai: a native /responses call without sampling parameters does not get any (#197)") {
    val options = AzureOpenAiChatClientOptions.fromJson(Json.obj("allow_config_override" -> true, "responses" -> true))
    val body = Json.obj(
      "model" -> "gpt-5",
      "reasoning" -> Json.obj("effort" -> "medium", "summary" -> "auto"),
      "stream" -> true,
    )
    val payload = OpenAiResponsesBodyConverter.toResponsesBody(options.jsonForCall.deepMerge(body))
    assert(!payload.keys.contains("temperature"), payload.toString)
    assert(!payload.keys.contains("top_p"), payload.toString)
  }

  extension (obj: JsObject) {
    private def select(key: String): play.api.libs.json.JsLookupResult = obj \ key
  }
}
