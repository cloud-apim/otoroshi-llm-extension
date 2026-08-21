package com.cloud.apim.otoroshi.extensions.aigateway.providers

import io.vertx.pgclient.{PgBuilder, PgConnectOptions}
import io.vertx.sqlclient.{Pool, PoolOptions, Row, Tuple}
import play.api.Logger

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters.*

// ---------------------------------------------------------------------------
//  Shared PgPool manager — one pool per distinct PostgreSQL URI
//  Similar to LettuceRedisClientManager but for Vert.x reactive-pg
// ---------------------------------------------------------------------------
object PgPoolManager {

  private val logger = Logger("PgPoolManager")

  private val pools = new TrieMap[String, Pool]()

  // --- Vert.x Future → Scala Future conversion ---
  extension [A](future: io.vertx.core.Future[A]) {
    def scala: Future[A] = {
      val promise = Promise[A]()
      future.onSuccess(a => promise.trySuccess(a))
      future.onFailure(e => promise.tryFailure(e))
      promise.future
    }
  }

  def getPool(uri: String): Pool = synchronized {
    pools.get(uri) match {
      case Some(pool) => pool
      case None =>
        logger.info(s"Creating new PgPool for: $uri")
        val connectOptions = PgConnectOptions.fromUri(uri)
        val poolOptions = new PoolOptions().setMaxSize(10)
        // vertx 5 dropped `PgPool.pool(...)`: pools are now assembled through PgBuilder and
        // handed back as the generic io.vertx.sqlclient.Pool
        val pool = PgBuilder.pool().connectingTo(connectOptions).`with`(poolOptions).build()
        pools.put(uri, pool)
        pool
    }
  }

  def cleanup(activeUrls: Set[String]): Unit = synchronized {
    val staleUrls = pools.keys.filterNot(activeUrls.contains).toSeq
    staleUrls.foreach { url =>
      logger.info(s"Closing unused PgPool for: $url")
      pools.remove(url).foreach(_.close())
    }
  }

  def shutdownAll(): Unit = synchronized {
    pools.keys.toSeq.foreach { url =>
      pools.remove(url).foreach(_.close())
    }
  }

  // --- Query helpers ---

  def query(pool: Pool, sql: String, params: Seq[AnyRef] = Seq.empty)(using ec: ExecutionContext): Future[Seq[Row]] = {
    val tuple = if (params.isEmpty) Tuple.tuple() else Tuple.from(params.toArray)
    val isRead = sql.trim.toLowerCase.startsWith("select")
    val fut = if (isRead) {
      pool.withConnection(c => c.preparedQuery(sql).execute(tuple))
    } else {
      pool.preparedQuery(sql).execute(tuple)
    }
    fut.scala.map(_.asScala.toSeq)
  }

  def exec(pool: Pool, sql: String, params: Seq[AnyRef] = Seq.empty)(using ec: ExecutionContext): Future[Unit] = {
    val tuple = if (params.isEmpty) Tuple.tuple() else Tuple.from(params.toArray)
    pool.preparedQuery(sql).execute(tuple).scala.map(_ => ())
  }
}
