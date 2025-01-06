package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import akka.stream.scaladsl.{Sink, Source}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatClient, ChatPrompt, ChatResponse, ChatResponseCache, ChatResponseCacheStatus, ChatResponseChunk, ChatResponseMetadataUsage}
import com.github.blemale.scaffeine.Scaffeine
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.JsValue

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object ChatClientWithSimpleCache {

  val cache = Scaffeine()
    .expireAfter[String, (FiniteDuration, ChatResponse, Long)](
      create = (key, value) => value._1,
      update = (key, value, currentDuration) => currentDuration,
      read = (key, value, currentDuration) => currentDuration
    )
    .maximumSize(5000)
    .build[String, (FiniteDuration, ChatResponse, Long)]()

  val stream_cache = Scaffeine()
    .expireAfter[String, (FiniteDuration, Seq[ChatResponseChunk], Long)](
      create = (key, value) => value._1,
      update = (key, value, currentDuration) => currentDuration,
      read = (key, value, currentDuration) => currentDuration
    )
    .maximumSize(5000)
    .build[String, (FiniteDuration, Seq[ChatResponseChunk], Long)]()

  def applyIfPossible(tuple: (AiProvider, ChatClient)): ChatClient = {
    if (tuple._1.cache.strategy.contains("simple")) {
      new ChatClientWithSimpleCache(tuple._1, tuple._2)
    } else {
      tuple._2
    }
  }
}

class ChatClientWithSimpleCache(originalProvider: AiProvider, val chatClient: ChatClient) extends DecoratorChatClient {

  private val ttl = originalProvider.cache.ttl

  override def call(originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val key = originalPrompt.messages.map(m => s"${m.role}:${m.content}").mkString(",").sha512
    ChatClientWithSimpleCache.cache.getIfPresent(key) match {
      case Some((_, response, at)) =>
        // println("using simple cache response")
        val age = (System.currentTimeMillis() - at).millis
        response.copy(metadata = response.metadata.copy(
          usage = ChatResponseMetadataUsage.empty,
          cache = Some(ChatResponseCache(ChatResponseCacheStatus.Hit, key, ttl, age))
        )).rightf
      case None => {
        chatClient.call(originalPrompt, attrs, originalBody).map {
          case Left(err) => err.left
          case Right(resp) => {
            ChatClientWithSimpleCache.cache.put(key, (ttl, resp, System.currentTimeMillis()))
            resp.copy(metadata = resp.metadata.copy(
              cache = Some(ChatResponseCache(ChatResponseCacheStatus.Miss, key, ttl, 0.millis))
            )).right
          }
        }
      }
    }
  }

  override def stream(originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    val key = originalPrompt.messages.map(m => s"${m.role}:${m.content}").mkString(",").sha512
    ChatClientWithSimpleCache.stream_cache.getIfPresent(key) match {
      case Some((_, response, at)) => Source(response.toList).rightf
      case None => {
        chatClient.stream(originalPrompt, attrs, originalBody).map {
          case Left(err) => err.left
          case Right(resp) => {
            var chunks = Seq.empty[ChatResponseChunk]
            resp
              .alsoTo(Sink.foreach { chunk =>
                chunks = chunks :+ chunk
              })
              .alsoTo(Sink.onComplete { _ =>
                ChatClientWithSimpleCache.stream_cache.put(key, (ttl, chunks, System.currentTimeMillis()))
              })
            resp.right
          }
        }
      }
    }
  }

  override def completion(originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val key = originalPrompt.messages.map(m => s"${m.role}:${m.content}").mkString(",").sha512
    ChatClientWithSimpleCache.cache.getIfPresent(key) match {
      case Some((_, response, at)) =>
        // println("using simple cache response")
        val age = (System.currentTimeMillis() - at).millis
        response.copy(metadata = response.metadata.copy(
          usage = ChatResponseMetadataUsage.empty,
          cache = Some(ChatResponseCache(ChatResponseCacheStatus.Hit, key, ttl, age))
        )).rightf
      case None => {
        chatClient.completion(originalPrompt, attrs, originalBody).map {
          case Left(err) => err.left
          case Right(resp) => {
            ChatClientWithSimpleCache.cache.put(key, (ttl, resp, System.currentTimeMillis()))
            resp.copy(metadata = resp.metadata.copy(
              cache = Some(ChatResponseCache(ChatResponseCacheStatus.Miss, key, ttl, 0.millis))
            )).right
          }
        }
      }
    }
  }

  override def completionStream(originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    val key = originalPrompt.messages.map(m => s"${m.role}:${m.content}").mkString(",").sha512
    ChatClientWithSimpleCache.stream_cache.getIfPresent(key) match {
      case Some((_, response, at)) => Source(response.toList).rightf
      case None => {
        chatClient.completionStream(originalPrompt, attrs, originalBody).map {
          case Left(err) => err.left
          case Right(resp) => {
            var chunks = Seq.empty[ChatResponseChunk]
            resp
              .alsoTo(Sink.foreach { chunk =>
                chunks = chunks :+ chunk
              })
              .alsoTo(Sink.onComplete { _ =>
                ChatClientWithSimpleCache.stream_cache.put(key, (ttl, chunks, System.currentTimeMillis()))
              })
            resp.right
          }
        }
      }
    }
  }
}