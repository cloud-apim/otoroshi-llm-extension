package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import akka.stream.scaladsl.{Sink, Source}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.providers.LettuceRedisClientManager
import com.cloud.apim.otoroshi.extensions.aigateway._
import com.github.blemale.scaffeine.Scaffeine
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}

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

  def applyIfPossible(tuple: (AiProvider, ChatClient, Env)): ChatClient = {
    if (tuple._1.cache.strategy.contains("simple")) {
      new ChatClientWithSimpleCache(tuple._1, tuple._2)
    } else {
      tuple._2
    }
  }

  // --- JSON serialization helpers ---

  def serializeResponse(resp: ChatResponse, at: Long): String = {
    Json.stringify(Json.obj(
      "generations" -> JsArray(resp.generations.map(g => Json.obj(
        "role" -> g.message.role,
        "content" -> g.message.content,
        "prefix" -> g.message.prefix.map(JsBoolean.apply).getOrElse(JsNull).asValue,
        "raw" -> g.message.raw
      ))),
      "usage" -> resp.metadata.usage.json,
      "raw" -> resp.raw,
      "at" -> at
    ))
  }

  def deserializeResponse(value: String): (ChatResponse, Long) = {
    val json = Json.parse(value)
    val at = json.select("at").as[Long]
    val generations = json.select("generations").as[Seq[JsObject]].map { g =>
      ChatGeneration(OutputChatMessage(
        role = g.select("role").asString,
        content = g.select("content").asString,
        prefix = g.select("prefix").asOpt[Boolean],
        raw = g.select("raw").asOpt[JsObject].getOrElse(g)
      ))
    }
    val usage = json.select("usage").asOpt[JsObject].map { u =>
      ChatResponseMetadataUsage(
        promptTokens = u.select("prompt_tokens").asOpt[Long].getOrElse(0L),
        generationTokens = u.select("generation_tokens").asOpt[Long].getOrElse(0L),
        reasoningTokens = u.select("reasoning_tokens").asOpt[Long].getOrElse(0L),
      )
    }.getOrElse(ChatResponseMetadataUsage.empty)
    val raw = json.select("raw").asOpt[JsValue].getOrElse(Json.obj())
    val resp = ChatResponse(generations, ChatResponseMetadata(ChatResponseMetadataRateLimit.empty, usage, None), raw)
    (resp, at)
  }

  def serializeChunks(chunks: Seq[ChatResponseChunk], at: Long): String = {
    Json.stringify(Json.obj(
      "chunks" -> JsArray(chunks.map { c =>
        Json.obj(
          "id" -> c.id,
          "created" -> c.created,
          "model" -> c.model,
          "choices" -> JsArray(c.choices.map(_.json))
        )
      }),
      "at" -> at
    ))
  }

  def deserializeChunks(value: String): (Seq[ChatResponseChunk], Long) = {
    val json = Json.parse(value)
    val at = json.select("at").as[Long]
    val chunks = json.select("chunks").as[Seq[JsObject]].map { c =>
      ChatResponseChunk(
        id = c.select("id").asString,
        created = c.select("created").as[Long],
        model = c.select("model").asString,
        choices = c.select("choices").as[Seq[JsObject]].map { ch =>
          val delta = ch.select("delta").as[JsObject]
          ChatResponseChunkChoice(
            index = ch.select("index").asOpt[Long].getOrElse(0L),
            delta = ChatResponseChunkChoiceDelta(
              content = delta.select("content").asOptString,
              reasoning = delta.select("reasoning").asOptString,
              role = delta.select("role").asOptString.getOrElse("assistant"),
              refusal = delta.select("refusal").asOptString,
              tool_calls = delta.select("tool_calls").asOpt[Seq[JsObject]].map(_.map { tc =>
                val fn = tc.select("function").as[JsObject]
                ChatResponseChunkChoiceDeltaToolCall(
                  index = tc.select("index").asOpt[Long].getOrElse(0L),
                  id = tc.select("id").asOptString,
                  typ = tc.select("type").asOptString,
                  function = ChatResponseChunkChoiceDeltaToolCallFunction(
                    nameOpt = fn.select("name").asOptString,
                    arguments = fn.select("arguments").asOptString.getOrElse("")
                  )
                )
              }).getOrElse(Seq.empty)
            ),
            finishReason = ch.select("finish_reason").asOptString
          )
        }
      )
    }
    (chunks, at)
  }
}

class ChatClientWithSimpleCache(originalProvider: AiProvider, val chatClient: ChatClient) extends DecoratorChatClient {

  private val ttl = originalProvider.cache.ttl
  private val redisUrl: Option[String] = originalProvider.cache.redisUrl

  // --- Lettuce Future helper ---

  private def toFuture[T](stage: java.util.concurrent.CompletionStage[T]): Future[T] = {
    val promise = Promise[T]()
    stage.whenComplete(new java.util.function.BiConsumer[T, Throwable] {
      override def accept(result: T, error: Throwable): Unit = {
        if (error != null) promise.failure(error)
        else promise.success(result)
      }
    })
    promise.future
  }

  // --- cache get/put abstraction ---

  private def getCachedResponse(key: String)(implicit ec: ExecutionContext): Future[Option[(ChatResponse, Long)]] = {
    redisUrl match {
      case None =>
        Future.successful(ChatClientWithSimpleCache.cache.getIfPresent(key).map(t => (t._2, t._3)))
      case Some(url) =>
        toFuture(LettuceRedisClientManager.getConnection(url).async().get(s"simple-cache:call:$key"))
          .map {
            case null => None
            case value => scala.util.Try(ChatClientWithSimpleCache.deserializeResponse(value)).toOption
          }
          .recover { case _ => None }
    }
  }

  private def putCachedResponse(key: String, response: ChatResponse)(implicit ec: ExecutionContext): Unit = {
    val now = System.currentTimeMillis()
    redisUrl match {
      case None =>
        ChatClientWithSimpleCache.cache.put(key, (ttl, response, now))
      case Some(url) =>
        val json = ChatClientWithSimpleCache.serializeResponse(response, now)
        toFuture(LettuceRedisClientManager.getConnection(url).async().psetex(s"simple-cache:call:$key", ttl.toMillis, json))
    }
  }

  private def getCachedChunks(key: String)(implicit ec: ExecutionContext): Future[Option[(Seq[ChatResponseChunk], Long)]] = {
    redisUrl match {
      case None =>
        Future.successful(ChatClientWithSimpleCache.stream_cache.getIfPresent(key).map(t => (t._2, t._3)))
      case Some(url) =>
        toFuture(LettuceRedisClientManager.getConnection(url).async().get(s"simple-cache:stream:$key"))
          .map {
            case null => None
            case value => scala.util.Try(ChatClientWithSimpleCache.deserializeChunks(value)).toOption
          }
          .recover { case _ => None }
    }
  }

  private def putCachedChunks(key: String, chunks: Seq[ChatResponseChunk])(implicit ec: ExecutionContext): Unit = {
    val now = System.currentTimeMillis()
    redisUrl match {
      case None =>
        ChatClientWithSimpleCache.stream_cache.put(key, (ttl, chunks, now))
      case Some(url) =>
        val json = ChatClientWithSimpleCache.serializeChunks(chunks, now)
        toFuture(LettuceRedisClientManager.getConnection(url).async().psetex(s"simple-cache:stream:$key", ttl.toMillis, json))
    }
  }

  // --- ChatClient methods ---

  override def call(originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val key = originalPrompt.messages.map(m => s"${m.role}:${m.content}").mkString(",").sha512
    getCachedResponse(key).flatMap {
      case Some((response, at)) =>
        val age = (System.currentTimeMillis() - at).millis
        response.copy(metadata = response.metadata.copy(
          usage = ChatResponseMetadataUsage.empty,
          cache = Some(ChatResponseCache(ChatResponseCacheStatus.Hit, key, ttl, age))
        )).rightf
      case None =>
        chatClient.call(originalPrompt, attrs, originalBody).map {
          case Left(err) => err.left
          case Right(resp) =>
            putCachedResponse(key, resp)
            resp.copy(metadata = resp.metadata.copy(
              cache = Some(ChatResponseCache(ChatResponseCacheStatus.Miss, key, ttl, 0.millis))
            )).right
        }
    }
  }

  override def stream(originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    val key = originalPrompt.messages.map(m => s"${m.role}:${m.content}").mkString(",").sha512
    getCachedChunks(key).flatMap {
      case Some((chunks, _)) => Source(chunks.toList).rightf
      case None =>
        chatClient.stream(originalPrompt, attrs, originalBody).map {
          case Left(err) => err.left
          case Right(resp) =>
            var chunks = Seq.empty[ChatResponseChunk]
            resp
              .alsoTo(Sink.foreach { chunk =>
                chunks = chunks :+ chunk
              })
              .alsoTo(Sink.onComplete { _ =>
                putCachedChunks(key, chunks)
              })
            resp.right
        }
    }
  }

  override def completion(originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val key = originalPrompt.messages.map(m => s"${m.role}:${m.content}").mkString(",").sha512
    getCachedResponse(key).flatMap {
      case Some((response, at)) =>
        val age = (System.currentTimeMillis() - at).millis
        response.copy(metadata = response.metadata.copy(
          usage = ChatResponseMetadataUsage.empty,
          cache = Some(ChatResponseCache(ChatResponseCacheStatus.Hit, key, ttl, age))
        )).rightf
      case None =>
        chatClient.completion(originalPrompt, attrs, originalBody).map {
          case Left(err) => err.left
          case Right(resp) =>
            putCachedResponse(key, resp)
            resp.copy(metadata = resp.metadata.copy(
              cache = Some(ChatResponseCache(ChatResponseCacheStatus.Miss, key, ttl, 0.millis))
            )).right
        }
    }
  }

  override def completionStream(originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    val key = originalPrompt.messages.map(m => s"${m.role}:${m.content}").mkString(",").sha512
    getCachedChunks(key).flatMap {
      case Some((chunks, _)) => Source(chunks.toList).rightf
      case None =>
        chatClient.completionStream(originalPrompt, attrs, originalBody).map {
          case Left(err) => err.left
          case Right(resp) =>
            var chunks = Seq.empty[ChatResponseChunk]
            resp
              .alsoTo(Sink.foreach { chunk =>
                chunks = chunks :+ chunk
              })
              .alsoTo(Sink.onComplete { _ =>
                putCachedChunks(key, chunks)
              })
            resp.right
        }
    }
  }
}
