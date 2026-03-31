package com.cloud.apim.otoroshi.extensions.aigateway.providers

import com.cloud.apim.otoroshi.extensions.aigateway._
import io.lettuce.core.{RedisClient => LettuceRedisClient}
import io.lettuce.core.api.StatefulRedisConnection
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future, Promise}

// ---------------------------------------------------------------------------
//  Shared Lettuce Redis client manager
//  - One (RedisClient, StatefulRedisConnection) per distinct Redis URL
//  - Thread-safe: all mutations go through a synchronized block
//  - Automatically re-creates a connection if the previous one was closed
//  - cleanup(activeUrls) shuts down connections whose URL is no longer in use
// ---------------------------------------------------------------------------
object LettuceRedisClientManager {

  private val logger = Logger("LettuceRedisClientManager")

  private case class ManagedConnection(
    client: LettuceRedisClient,
    connection: StatefulRedisConnection[String, String]
  )

  private val connections = new TrieMap[String, ManagedConnection]()

  def getConnection(url: String): StatefulRedisConnection[String, String] = synchronized {
    connections.get(url) match {
      case Some(managed) if managed.connection.isOpen => managed.connection
      case existing =>
        // close stale entry if present
        existing.foreach { managed =>
          logger.info(s"Closing stale Redis connection for: $url")
          try { managed.connection.closeAsync() } catch { case _: Throwable => }
          try { managed.client.shutdownAsync() } catch { case _: Throwable => }
          connections.remove(url)
        }
        logger.info(s"Creating new Redis connection for: $url")
        val client = LettuceRedisClient.create(url)
        val conn = client.connect()
        connections.put(url, ManagedConnection(client, conn))
        conn
    }
  }

  /**
   * Shuts down every connection whose URL is NOT in the given active set.
   * Call this periodically or whenever the entity configuration changes.
   */
  def cleanup(activeUrls: Set[String]): Unit = synchronized {
    val staleUrls = connections.keys.filterNot(activeUrls.contains).toSeq
    staleUrls.foreach { url =>
      logger.info(s"Cleaning up unused Redis connection for: $url")
      connections.remove(url).foreach { managed =>
        try { managed.connection.closeAsync() } catch { case _: Throwable => }
        try { managed.client.shutdownAsync() } catch { case _: Throwable => }
      }
    }
  }

  /** Shuts down ALL connections – call on extension stop. */
  def shutdownAll(): Unit = synchronized {
    connections.keys.toSeq.foreach { url =>
      connections.remove(url).foreach { managed =>
        try { managed.connection.closeAsync() } catch { case _: Throwable => }
        try { managed.client.shutdownAsync() } catch { case _: Throwable => }
      }
    }
  }
}

// ---------------------------------------------------------------------------
//  Redis-backed PersistentMemoryClient
// ---------------------------------------------------------------------------
class RedisPersistentMemoryClient(val config: JsObject, _memoryId: String) extends PersistentMemoryClient {

  private lazy val connection: JsObject = config.select("connection").asOpt[JsObject].getOrElse(Json.obj())
  private lazy val url: String = connection.select("url").asOptString.getOrElse("redis://localhost:6379")
  private lazy val prefix: String = connection.select("prefix").asOptString.getOrElse("otoroshi:ai:memory")

  private def redisKey(sessionId: String): String = s"$prefix:${_memoryId}:$sessionId"

  private def conn: StatefulRedisConnection[String, String] = LettuceRedisClientManager.getConnection(url)

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

  override def updateMessages(sessionId: String, messages: Seq[PersistedChatMessage])(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Unit]] = {
    val json = Json.stringify(JsArray(messages.map(_.raw)))
    toFuture(conn.async().set(redisKey(sessionId), json))
      .map(_ => Right(()))
      .recover { case e: Throwable =>
        Left(Json.obj("error" -> s"Redis set error: ${e.getMessage}"))
      }
  }

  override def getMessages(sessionId: String)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Seq[PersistedChatMessage]]] = {
    toFuture(conn.async().get(redisKey(sessionId)))
      .map {
        case null => Right(Seq.empty[PersistedChatMessage])
        case value =>
          val messages = Json.parse(value).as[Seq[JsObject]]
          Right(messages.map(PersistedChatMessage(_)))
      }
      .recover { case e: Throwable =>
        Left(Json.obj("error" -> s"Redis get error: ${e.getMessage}"))
      }
  }

  override def clearMemory(sessionId: String)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Unit]] = {
    toFuture(conn.async().del(redisKey(sessionId)))
      .map(_ => Right(()))
      .recover { case e: Throwable =>
        Left(Json.obj("error" -> s"Redis del error: ${e.getMessage}"))
      }
  }
}
