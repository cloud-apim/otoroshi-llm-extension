package com.cloud.apim.otoroshi.extensions.aigateway

import akka.stream.scaladsl.Source
import akka.util.ByteString
import otoroshi.env.Env
import otoroshi.events.AuditEvent
import otoroshi.security.IdGenerator
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.libs.typedmap.TypedKey

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

trait ChatOptions {
  def temperature: Float
  def topP: Float
  def topK: Int
  def json: JsObject
}
case class ChatPrompt(messages: Seq[ChatMessage], options: Option[ChatOptions] = None) {
  def json: JsValue = JsArray(messages.map(_.json))
}
object ChatMessage {
  val format = new Format[ChatMessage] {

    override def reads(json: JsValue): JsResult[ChatMessage] = Try {
      ChatMessage(
        role = json.select("role").asString,
        content = json.select("content").asString,
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }

    override def writes(o: ChatMessage): JsValue = o.json
  }
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
  def openaiJson(idx: Int): JsValue = Json.obj(
    "index" -> idx,
    "message" -> message.json,
    "logprobs" -> JsNull,
    "finish_reason" -> "stop",
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
  def openaiJson(model: String): JsValue = Json.obj(
    "id" -> s"chatcmpl-${IdGenerator.token(32)}",
    "object" -> "chat.completion",
    "created" -> (System.currentTimeMillis() / 1000).toLong,
    "model" -> model,
    "system_fingerprint" -> s"fp-${IdGenerator.token(32)}",
    "choices" -> JsArray(generations.zipWithIndex.map(t => t._1.openaiJson(t._2))),
    "usage" -> metadata.usage.openaiJson,
  )
  def toSource(model: String): Source[ChatResponseChunk, _] = {
    val id = s"chatgen-${IdGenerator.token(32)}"
    Source(generations.toList)
      .flatMapConcat { gen =>
        gen.message.content.chunks(5)
      }
      .map { chunk =>
        ChatResponseChunk(id, System.currentTimeMillis() / 1000, model, Seq(ChatResponseChunkChoice(0, ChatResponseChunkChoiceDelta(chunk.some), None)))
      }
      .concat(Source.single(
        ChatResponseChunk(id, System.currentTimeMillis() / 1000, model, Seq(ChatResponseChunkChoice(0, ChatResponseChunkChoiceDelta(None), Some("stop"))))
      ))
  }
}
sealed trait ChatResponseCacheStatus {
  def name: String
}
object ChatResponseCacheStatus {
  case object Hit extends ChatResponseCacheStatus { def name: String = "Hit" }
  case object Miss extends ChatResponseCacheStatus { def name: String = "Miss" }
  case object Refresh extends ChatResponseCacheStatus { def name: String = "Refresh" }
  case object Bypass extends ChatResponseCacheStatus { def name: String = "Bypass" }
}
case class ChatResponseCache(status: ChatResponseCacheStatus, key: String, ttl: FiniteDuration, age: FiniteDuration) {
  def json: JsValue = Json.obj(
    "status" -> status.name, // Hit, Miss, Refresh, Bypass
    "key" -> key,
    "ttl" -> ttl.toSeconds,
    "age" -> age.toSeconds,
  )
  def toHeaders(): Map[String, String] = Map(
    "X-Cache-Status" -> status.name,
    "X-Cache-Key" -> key,
    "X-Cache-Ttl" -> ttl.toSeconds.toString,
    "Age" -> age.toSeconds.toString,
  )
}

case class ChatResponseMetadata(rateLimit: ChatResponseMetadataRateLimit, usage: ChatResponseMetadataUsage, cache: Option[ChatResponseCache]) {
  def cacheHeaders: Map[String, String] = cache match {
    case None => Map.empty
    case Some(cache) => cache.toHeaders()
  }
  def json: JsValue = Json.obj(
    "rate_limit" -> rateLimit.json,
    "usage" -> usage.json,
  ).applyOnWithOpt(cache) {
    case(obj, cache) => obj ++ Json.obj("cache" -> cache.json)
  }
}

object ChatResponseMetadata {
  val empty: ChatResponseMetadata = ChatResponseMetadata(
    ChatResponseMetadataRateLimit.empty,
    ChatResponseMetadataUsage.empty,
    None
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
  def openaiJson: JsValue = Json.obj(
    "prompt_tokens" -> promptTokens,
    "completion_tokens" -> generationTokens,
    "total_tokens" -> (promptTokens + generationTokens),
    "completion_tokens_details" -> Json.obj()
  )
}

case class ChatResponseChunkChoiceDelta(content: Option[String]) {
  def json: JsValue = content match {
    case None => Json.obj()
    case Some(content) => Json.obj("content" -> content)
  }
}

case class ChatResponseChunkChoice(index: Int, delta: ChatResponseChunkChoiceDelta, finishReason: Option[String]) {
  def json: JsValue = Json.obj(
    "index" -> index,
    "delta" -> delta.json,
    "finish_reason" -> finishReason.map(_.json).getOrElse(JsNull).asValue
  )
  def openaiJson: JsValue = Json.obj(
    "index" -> index,
    "delta" -> delta.json,
    "logprobs" -> JsNull,
    "finish_reason" -> finishReason.map(_.json).getOrElse(JsNull).asValue
  )
}

case class ChatResponseChunk(id: String, created: Long, model: String, choices: Seq[ChatResponseChunkChoice]) {
  def json: JsValue = Json.obj(
    "id" -> id,
    "created" -> created,
    "model" -> model,
    "choices" -> JsArray(choices.map(_.json))
  )
  def openaiJson: JsValue = Json.obj(
    "id" -> id,
    "object" -> "chat.completion.chunk",
    "created" -> created,
    "model" -> model,
    "system_fingerprint" -> JsNull,
    "choices" -> JsArray(choices.map(_.openaiJson))
  )
  def eventSource: ByteString = s"data: ${json.stringify}\n\n".byteString
  def openaiEventSource: ByteString = s"data: ${openaiJson.stringify}\n\n".byteString
}

trait ChatClient {

  def supportsStreaming: Boolean = false
  def supportsTools: Boolean = false

  def model: Option[String]

  def listModels()(implicit ec: ExecutionContext): Future[Either[JsValue, List[String]]] = Left(Json.obj("error" -> "models list not supported")).vfuture

  def call(prompt: ChatPrompt, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]]

  def stream(prompt: ChatPrompt, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    Left(Json.obj("error" -> "streaming not supported")).future
  }

  def tryStream(prompt: ChatPrompt, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    if (supportsStreaming) {
      stream(prompt, attrs)
    } else {
      call(prompt, attrs).map {
        case Left(err) => Left(err)
        case Right(resp) => Right(resp.toSource(model.getOrElse("none")))
      }
    }
  }
}

object ChatClient {
  val ApiUsageKey = TypedKey[ChatResponseMetadata]("otoroshi-extensions.cloud-apim.ai.llm.ApiUsage")
}
