package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import akka.stream.scaladsl.Source
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatClient, ChatMessage, ChatMessageContentFlavor, ChatPrompt, ChatResponse, ChatResponseChunk}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiProvider, PromptContext}
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.JsValue

import scala.concurrent.{ExecutionContext, Future}

object ChatClientWithContext {
  def applyIfPossible(tuple: (AiProvider, ChatClient)): ChatClient = {
    if (tuple._1.context.isDefined) {
      new ChatClientWithContext(tuple._1, tuple._2)
    } else {
      tuple._2
    }
  }
}

class ChatClientWithContext(originalProvider: AiProvider, val chatClient: ChatClient) extends DecoratorChatClient {

  private def getContext(ref: String, possibleContextsById: Map[String, PromptContext], possibleContextsByName: Map[String, PromptContext]): Option[PromptContext] = {
    possibleContextsById.get(ref)
      .orElse {
        possibleContextsByName.get(ref)
      }
  }

  private def getNewPrompt(prompt: ChatPrompt, originalBody: JsValue)(implicit env: Env): ChatPrompt = {
    val ext = env.adminExtensions.extension[AiExtension].get
    val possibleContextList = originalProvider.context.contexts.flatMap(ref => ext.states.context(ref))
    lazy val possibleContextById = possibleContextList.map(c => (c.id, c)).toMap
    lazy val possibleContextByName = possibleContextList.map(c => (c.name, c)).toMap
    val maybeContextFromProvider = originalProvider.context.default
    val maybeContextFromPrompt = originalBody.select("context").asOpt[String]
    maybeContextFromPrompt match {
      case Some(ref) => getContext(ref, possibleContextById, possibleContextByName).map { ctx =>
        val messages = (ctx.preMessages ++ prompt.messages.map(_.json(ChatMessageContentFlavor.Common)) ++ ctx.postMessages).map { obj =>
          val role = obj.select("role").asOpt[String].getOrElse("user")
          val content = obj.select("content").asOpt[String].getOrElse("")
          val prefix = obj.select("prefix").asOptBoolean
          ChatMessage.input(role, content, prefix)
        }
        prompt.copy(messages = messages)
      }.getOrElse(prompt)
      case None => maybeContextFromProvider match {
        case Some(ref) => ext.states.context(ref).map { ctx =>
          val messages = (ctx.preMessages ++ prompt.messages.map(_.json(ChatMessageContentFlavor.Common)) ++ ctx.postMessages).map { obj =>
            val role = obj.select("role").asOpt[String].getOrElse("user")
            val content = obj.select("content").asOpt[String].getOrElse("")
            val prefix = obj.select("prefix").asOptBoolean
            ChatMessage.input(role, content, prefix)
          }
          prompt.copy(messages = messages)
        }.getOrElse(prompt)
        case None => prompt
      }
    }
  }

  override def call(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val newPrompt = getNewPrompt(prompt, originalBody)
    chatClient.call(newPrompt, attrs, originalBody.asObject - "context")
  }

  override def stream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    val newPrompt = getNewPrompt(prompt, originalBody)
    chatClient.stream(newPrompt, attrs, originalBody.asObject - "context")
  }
}