package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import akka.stream.scaladsl.Source
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatClient, ChatPrompt, ChatResponse, ChatResponseChunk}
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import play.api.libs.json.JsValue

import scala.concurrent.{ExecutionContext, Future}

object ChatClientWithModelConstraints {
  def applyIfPossible(tuple: (AiProvider, ChatClient)): ChatClient = {
    if (tuple._1.models.isDefined) {
      new ChatClientWithModelConstraints(tuple._1, tuple._2)
    } else {
      tuple._2
    }
  }
}

class ChatClientWithModelConstraints(originalProvider: AiProvider, val chatClient: ChatClient) extends DecoratorChatClient {

  override def call(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    chatClient.call(prompt, attrs, originalBody)
  }

  override def stream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    chatClient.stream(prompt, attrs, originalBody)
  }

  override def completion(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    chatClient.completion(prompt, attrs, originalBody)
  }

  override def completionStream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    chatClient.completionStream(prompt, attrs, originalBody)
  }

  override def listModels(raw: Boolean)(implicit ec: ExecutionContext): Future[Either[JsValue, List[String]]] = {
    chatClient.listModels(raw).map {
      case Left(err) => Left(err)
      case Right(models) => {
        val include = originalProvider.models.include
        val exclude = originalProvider.models.exclude
        Right(models.filter { model =>
          if (!(include.isEmpty && exclude.isEmpty)) {
            val canpass    = if (include.isEmpty) true else include.exists(p => otoroshi.utils.RegexPool.regex(p).matches(model))
            val cannotpass =
              if (exclude.isEmpty) false else exclude.exists(p => otoroshi.utils.RegexPool.regex(p).matches(model))
            canpass && !cannotpass
          } else {
            true
          }
        })
      }
    }
  }
}