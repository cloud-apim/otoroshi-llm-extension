package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import akka.stream.scaladsl.Source
import com.cloud.apim.otoroshi.extensions.aigateway._
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import otoroshi.env.Env
import otoroshi.security.IdGenerator
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsValue, Json}

import scala.concurrent.{ExecutionContext, Future}

// Returns a canned response without calling the underlying provider when the request body
// contains a `mock_response` field. Useful to test the whole pipeline (guardrails, cache,
// budget, routing, fallbacks) without burning tokens or depending on a provider/api key.
//
//   { "model": "...", "messages": [...], "mock_response": "hello there" }
//
// If `mock_response` starts with "Exception:", an error is returned instead — handy to
// exercise error handling and provider fallbacks. Inspired by litellm's `mock_response`.
object ChatClientWithMockResponse {

  val exceptionPrefix = "Exception:"

  def applyIfPossible(tuple: (AiProvider, ChatClient, Env)): ChatClient = {
    new ChatClientWithMockResponse(tuple._2)
  }

  def mockError(message: String): JsValue = Json.obj(
    "error" -> Json.obj(
      "message" -> message,
      "type" -> "mock_error",
      "mock" -> true,
    )
  )
}

class ChatClientWithMockResponse(val chatClient: ChatClient) extends DecoratorChatClient {

  private def mockResponse(originalBody: JsValue): Option[String] = originalBody.select("mock_response").asOpt[String]

  override def call(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    mockResponse(originalBody) match {
      case Some(mock) if mock.startsWith(ChatClientWithMockResponse.exceptionPrefix) =>
        Future.successful(Left(ChatClientWithMockResponse.mockError(mock.drop(ChatClientWithMockResponse.exceptionPrefix.length).trim)))
      case Some(mock) =>
        val model = chatClient.computeModel(originalBody).getOrElse("mock")
        val metadata = ChatResponseMetadata.empty
        attrs.update(ChatClient.ApiUsageKey -> metadata)
        val message = ChatMessage.output("assistant", mock, None, Json.obj("role" -> "assistant", "content" -> mock))
        val response = ChatResponse(
          generations = Seq(ChatGeneration(message)),
          metadata = metadata,
          raw = Json.obj("model" -> model, "mock" -> true),
        )
        Future.successful(Right(response))
      case None => chatClient.call(prompt, attrs, originalBody)
    }
  }

  override def stream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    mockResponse(originalBody) match {
      case Some(mock) if mock.startsWith(ChatClientWithMockResponse.exceptionPrefix) =>
        Future.successful(Left(ChatClientWithMockResponse.mockError(mock.drop(ChatClientWithMockResponse.exceptionPrefix.length).trim)))
      case Some(mock) =>
        val model = chatClient.computeModel(originalBody).getOrElse("mock")
        val id = s"chatcmpl-mock-${IdGenerator.token(16)}"
        val created = System.currentTimeMillis() / 1000L
        // emit the canned text word by word to simulate a real token stream
        val words = mock.split(" ").toList
        val contentChunks = words.zipWithIndex.map { case (word, idx) =>
          val text = if (idx == words.length - 1) word else word + " "
          ChatResponseChunk(id, created, model, Seq(ChatResponseChunkChoice(0L, ChatResponseChunkChoiceDelta(Some(text)), None)))
        }
        val finalChunk = ChatResponseChunk(id, created, model, Seq(ChatResponseChunkChoice(0L, ChatResponseChunkChoiceDelta(None), Some("stop"))))
        Future.successful(Right(Source(contentChunks :+ finalChunk)))
      case None => chatClient.stream(prompt, attrs, originalBody)
    }
  }
}
