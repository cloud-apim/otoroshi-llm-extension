package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway._
import otoroshi.el.GlobalExpressionLanguage
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.{JsObject, JsValue, Json}

import scala.concurrent.{ExecutionContext, Future}
import scala.tools.nsc.Global

object ChatClientWithPersistentMemory {
  def applyIfPossible(tuple: (AiProvider, ChatClient, Env)): ChatClient = {
    if (tuple._1.memory.isDefined) {
      new ChatClientWithPersistentMemory(tuple._1, tuple._2)
    } else {
      tuple._2
    }
  }
}

class ChatClientWithPersistentMemory(originalProvider: AiProvider, val chatClient: ChatClient) extends DecoratorChatClient {

  override def call(originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val ref = originalProvider.memory.get
    env.adminExtensions.extension[AiExtension].get.states.persistentMemory(ref) match {
      case None => Json.obj("error" -> "memory provider not found").leftf
      case Some(memory) => {
        val opts = memory.config.select("options").asOpt[JsObject].getOrElse(Json.obj())
        val sessionIdValue = opts.select("session_id").asOpt[String].getOrElse("${apikey.client_id || user.email :: default}")
        val sessionId: String = GlobalExpressionLanguage.apply(
          value = sessionIdValue,
          req = attrs.get(otoroshi.plugins.Keys.RequestKey),
          service = attrs.get(otoroshi.next.plugins.Keys.RouteKey).map(_.legacy),
          route = attrs.get(otoroshi.next.plugins.Keys.RouteKey),
          apiKey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey),
          user = attrs.get(otoroshi.plugins.Keys.UserKey),
          context = attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty),
          attrs = attrs,
          env = env,
        )
        memory.getPersistentMemoryClient() match {
          case None => Json.obj("error" -> "memory provider client not found").leftf
          case Some(memClient) => {
            memClient.getMessages(sessionId).flatMap {
              case Left(error) => error.leftf
              case Right(memoryMessages) => {
                val memoryMessagesForClient = memoryMessages.map(m => InputChatMessage.fromJson(m.raw))
                val addedMessagesForMemory = originalPrompt.messages.map(m => PersistedChatMessage.from(m.raw))
                chatClient.call(originalPrompt.copy(messages = memoryMessagesForClient ++ originalPrompt.messages), attrs, originalBody).flatMap {
                  case Left(error) => {
                    memClient.addMessages(sessionId, addedMessagesForMemory)
                    error.leftf
                  }
                  case Right(response) => {
                    val responseMessages = response.generations.map(m => PersistedChatMessage.from(m.message.raw))
                    memClient.addMessages(sessionId, addedMessagesForMemory ++ responseMessages)
                    response.rightf
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}