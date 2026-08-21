package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import org.apache.pekko.stream.scaladsl.{Sink, Source}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.*
import otoroshi.el.GlobalExpressionLanguage
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.{JsObject, JsValue, Json}

import scala.concurrent.{ExecutionContext, Future}

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

  // resolves the memory client and the session id, then hands them to `f`
  private def withMemory[T](attrs: TypedMap)(f: (PersistentMemoryClient, String) => Future[Either[JsValue, T]])(using ec: ExecutionContext, env: Env): Future[Either[JsValue, T]] = {
    val ref = originalProvider.memory.get
    env.adminExtensions.extension[AiExtension].get.states.persistentMemory(ref) match {
      case None => Json.obj("error" -> "memory provider not found").leftf
      case Some(memory) => {
        val opts = memory.config.select("options").asOpt[JsObject].getOrElse(Json.obj())
        val sessionIdValue = opts.select("session_id").asOpt[String].getOrElse("${consumer.id || apikey.id || user.email || token.sub || req.ip :: default}")
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
          case Some(memClient) => f(memClient, sessionId)
        }
      }
    }
  }

  override def invoke(kind: ChatCallKind, originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    withMemory[ChatResponse](attrs) { (memClient, sessionId) =>
      memClient.getMessages(sessionId).flatMap {
        case Left(error) => error.leftf
        case Right(memoryMessages) => {
          val memoryMessagesForClient = memoryMessages.map(m => InputChatMessage.fromJson(m.raw))
          val addedMessagesForMemory = originalPrompt.messages.map(m => PersistedChatMessage.from(m.raw))
          chatClient.invoke(kind, originalPrompt.copy(messages = memoryMessagesForClient ++ originalPrompt.messages), attrs, originalBody).flatMap {
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

  override def invokeStream(kind: ChatCallKind, originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, ?]]] = {
    withMemory[Source[ChatResponseChunk, ?]](attrs) { (memClient, sessionId) =>
      memClient.getMessages(sessionId).flatMap {
        case Left(error) => error.leftf
        case Right(memoryMessages) => {
          val memoryMessagesForClient = memoryMessages.map(m => InputChatMessage.fromJson(m.raw))
          val addedMessagesForMemory = originalPrompt.messages.map(m => PersistedChatMessage.from(m.raw))
          chatClient.invokeStream(kind, originalPrompt.copy(messages = memoryMessagesForClient ++ originalPrompt.messages), attrs, originalBody).map {
            case Left(error) => {
              memClient.addMessages(sessionId, addedMessagesForMemory)
              error.left
            }
            case Right(source) => {
              // the answer is only known once the stream completes: aggregate the deltas and
              // persist the resulting assistant message
              val content = new StringBuilder()
              source
                .map { chunk =>
                  chunk.choices.flatMap(_.delta.content).foreach(content.append)
                  chunk
                }
                .alsoTo(Sink.onComplete { _ =>
                  val text = content.toString()
                  val raw = Json.obj("role" -> "assistant", "content" -> text)
                  memClient.addMessages(sessionId, addedMessagesForMemory :+ PersistedChatMessage.from(raw))
                }).right
            }
          }
        }
      }
    }
  }
}