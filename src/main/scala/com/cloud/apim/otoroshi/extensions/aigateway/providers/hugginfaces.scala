package com.cloud.apim.otoroshi.extensions.aigateway.providers

import com.cloud.apim.otoroshi.extensions.aigateway._
import dev.langchain4j.model.huggingface.{HuggingFaceChatModel, HuggingFaceModelName}
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsObject, JsValue, Json}

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

class HuggingFaceApi(val token: String, val timeout: FiniteDuration = 10.seconds, env: Env)

object HuggingFaceChatClientOptions {
  def fromJson(json: JsValue): HuggingFaceChatClientOptions = {
    HuggingFaceChatClientOptions(
      model = json.select("model").asOpt[String].getOrElse(HuggingFaceModelName.TII_UAE_FALCON_7B_INSTRUCT),
      max_tokens = json.select("max_tokens").asOpt[Int],
      temperature = json.select("temperature").asOpt[Float].getOrElse(1.0f),
      topP = json.select("top_p").asOpt[Float].getOrElse(1.0f),
      topK = json.select("top_k").asOpt[Int].getOrElse(0),
    )
  }
}

case class HuggingFaceChatClientOptions(
  model: String = HuggingFaceModelName.TII_UAE_FALCON_7B_INSTRUCT,
  max_tokens: Option[Int] = None,
  temperature: Float = 1,
  topK: Int = 0,
  topP: Float = 1,
) extends ChatOptions {
  override def json: JsObject = Json.obj(
    "model" -> model,
    "max_tokens" -> max_tokens,
    "temperature" -> temperature,
    "top_p" -> topP,
    "top_k" -> topK,
  )
}

class HuggingFaceChatClient(api: HuggingFaceApi, options: HuggingFaceChatClientOptions, id: String) extends ChatClient {

  private val maxTokens: Int = options.max_tokens.getOrElse(0)
  private val model = HuggingFaceChatModel.builder()
    .accessToken(api.token)
    .modelId(options.model)
    .timeout(java.time.Duration.ofMillis(api.timeout.toMillis))
    .temperature(options.temperature)
    .maxNewTokens(maxTokens)
    .waitForModel(true)
    .build();

  override def call(prompt: ChatPrompt, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    // val mergedOptions = options.json.deepMerge(prompt.options.map(_.json).getOrElse(Json.obj()))
    val start = System.currentTimeMillis()
    val response = model.generate(prompt.messages.map(_.asLangchain4j): _*)
    val usage = ChatResponseMetadata(
      ChatResponseMetadataRateLimit(
        requestsLimit = -1L,
        requestsRemaining = -1L,
        tokensLimit = -1L,
        tokensRemaining = -1L,
      ),
      ChatResponseMetadataUsage(
        promptTokens = response.tokenUsage().inputTokenCount().toLong,
        generationTokens = response.tokenUsage().outputTokenCount().toLong
      ),
    )
    val duration: Long = System.currentTimeMillis() - start
    val slug = Json.obj(
      "provider_kind" -> "hugging-face",
      "provider" -> id,
      "duration" -> duration,
      "model" -> options.model.json,
      "rate_limit" -> usage.rateLimit.json,
      "usage" -> usage.usage.json
    )
    attrs.update(ChatClient.ApiUsageKey -> usage)
    attrs.update(otoroshi.plugins.Keys.ExtraAnalyticsDataKey) {
      case Some(obj @ JsObject(_)) => {
        val arr = obj.select("ai").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
        val newArr = arr ++ Seq(slug)
        obj ++ Json.obj("ai" -> newArr)
      }
      case Some(other) => other
      case None => Json.obj("ai" -> Seq(slug))
    }
    val role = "assistant"
    val messages = Seq(ChatGeneration(ChatMessage(role, response.content().text())))
    Right(ChatResponse(messages, usage)).future
  }
}
