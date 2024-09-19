package com.cloud.apim.otoroshi.extensions.aigateway

import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import dev.langchain4j.data.message.AiMessage
import otoroshi.env.Env
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits._
import otoroshi.utils.{RegexPool, TypedMap}
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}
import play.api.libs.typedmap.TypedKey
import play.api.mvc.Results

import java.util.regex.{MatchResult, Matcher, Pattern}
import java.util.regex.Pattern.CASE_INSENSITIVE
import scala.concurrent.{ExecutionContext, Future}

trait ChatOptions {
  def temperature: Float
  def topP: Float
  def topK: Int
  def json: JsObject
}
case class ChatPrompt(messages: Seq[ChatMessage], options: Option[ChatOptions] = None) {
  def json: JsValue = JsArray(messages.map(_.json))
}
case class ChatMessage(role: String, content: String) {
  def json: JsValue = Json.obj(
    "role" -> role,
    "content" -> content,
  )
  def asLangchain4j: dev.langchain4j.data.message.ChatMessage = {
    role match {
      case "user" => new dev.langchain4j.data.message.UserMessage(content)
      case "assistant" => new dev.langchain4j.data.message.AiMessage(content)
      case "ai" => new dev.langchain4j.data.message.AiMessage(content)
      case "system" => new dev.langchain4j.data.message.SystemMessage(content)
      case _ => new dev.langchain4j.data.message.UserMessage(content)
    }
  }
}
case class ChatGeneration(message: ChatMessage) {
  def json: JsValue = Json.obj(
    "message" -> message.json
  )
}
case class ChatResponse(
  generations: Seq[ChatGeneration],
  metadata: ChatResponseMetadata,
) {
  def json: JsValue = Json.obj(
    "generations" -> JsArray(generations.map(_.json)),
    "metadata" -> metadata.json,
  )
}

case class ChatResponseMetadata(rateLimit: ChatResponseMetadataRateLimit, usage: ChatResponseMetadataUsage) {
  def json: JsValue = Json.obj(
    "rate_limit" -> rateLimit.json,
    "usage" -> usage.json,
  )
}

object ChatResponseMetadata {
  val empty: ChatResponseMetadata = ChatResponseMetadata(
    ChatResponseMetadataRateLimit.empty,
    ChatResponseMetadataUsage.empty,
  )
}

object ChatResponseMetadataRateLimit {
  def empty: ChatResponseMetadataRateLimit = ChatResponseMetadataRateLimit(0L, 0L, 0L, 0L)
}

case class ChatResponseMetadataRateLimit(requestsLimit: Long, requestsRemaining: Long, tokensLimit: Long, tokensRemaining: Long) {
  def json: JsValue = Json.obj(
    "requests_limit" -> requestsLimit,
    "requests_remaining" -> requestsRemaining,
    "tokens_limit" -> tokensLimit,
    "tokens_remaining" -> tokensRemaining,
  )
}

object ChatResponseMetadataUsage {
  val empty: ChatResponseMetadataUsage = ChatResponseMetadataUsage(0L, 0L)
}

case class ChatResponseMetadataUsage(promptTokens: Long, generationTokens: Long) {
  def json: JsValue = Json.obj(
    "prompt_tokens" -> promptTokens,
    "generation_tokens" -> generationTokens,
  )
}

trait ChatClient {
  def call(prompt: ChatPrompt, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]]
}

object ChatClient {
  val ApiUsageKey = TypedKey[ChatResponseMetadata]("otoroshi-extensions.cloud-apim.ai.llm.ApiUsage")
}
