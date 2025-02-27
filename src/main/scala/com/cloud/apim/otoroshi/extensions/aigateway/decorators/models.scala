package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import akka.stream.scaladsl.Source
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatClient, ChatPrompt, ChatResponse, ChatResponseChunk}
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsValue, Json}

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
    originalBody.select("model").asOptString.orElse(chatClient.model) match {
      case Some(model) if originalProvider.models.matches(model) => chatClient.call(prompt, attrs, originalBody)
      case _ => Json.obj("error" -> "you can't use this model").leftf
    }
  }

  override def stream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    originalBody.select("model").asOptString.orElse(chatClient.model) match {
      case Some(model) if originalProvider.models.matches(model) => chatClient.stream(prompt, attrs, originalBody)
      case _ => Json.obj("error" -> "you can't use this model").leftf
    }
  }

  override def completion(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    originalBody.select("model").asOptString.orElse(chatClient.model) match {
      case Some(model) if originalProvider.models.matches(model) => chatClient.completion(prompt, attrs, originalBody)
      case _ => Json.obj("error" -> "you can't use this model").leftf
    }
  }

  override def completionStream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    originalBody.select("model").asOptString.orElse(chatClient.model) match {
      case Some(model) if originalProvider.models.matches(model) => chatClient.completionStream(prompt, attrs, originalBody)
      case _ => Json.obj("error" -> "you can't use this model").leftf
    }
  }

  override def listModels(raw: Boolean)(implicit ec: ExecutionContext): Future[Either[JsValue, List[String]]] = {
    chatClient.listModels(raw).map {
      case Left(err) => Left(err)
      case Right(models) => Right(models.filter(m => originalProvider.models.matches(m)))
    }
  }
}