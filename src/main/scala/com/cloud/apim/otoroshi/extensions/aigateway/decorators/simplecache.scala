package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatClient, ChatPrompt, ChatResponse}
import com.github.blemale.scaffeine.Scaffeine
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.JsValue

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

object ChatClientWithSimpleCache {
  val cache = Scaffeine()
    .expireAfter[String, (FiniteDuration, ChatResponse)](
      create = (key, value) => value._1,
      update = (key, value, currentDuration) => currentDuration,
      read = (key, value, currentDuration) => currentDuration
    )
    .maximumSize(5000)
    .build[String, (FiniteDuration, ChatResponse)]()
  def applyIfPossible(tuple: (AiProvider, ChatClient)): ChatClient = {
    if (tuple._1.cache.strategy.contains("simple")) {
      new ChatClientWithSimpleCache(tuple._1, tuple._2)
    } else {
      tuple._2
    }
  }
}

class ChatClientWithSimpleCache(originalProvider: AiProvider, chatClient: ChatClient) extends ChatClient {

  private val ttl = originalProvider.cache.ttl

  override def model: Option[String] = chatClient.model

  override def call(originalPrompt: ChatPrompt, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val key = originalPrompt.messages.map(m => s"${m.role}:${m.content}").mkString(",").sha512
    ChatClientWithSimpleCache.cache.getIfPresent(key) match {
      case Some((_, response)) =>
        // println("using simple cache response")
        response.rightf
      case None => {
        chatClient.call(originalPrompt, attrs).map {
          case Left(err) => err.left
          case Right(resp) => {
            ChatClientWithSimpleCache.cache.put(key, (ttl, resp))
            resp.right
          }
        }
      }
    }
  }
}