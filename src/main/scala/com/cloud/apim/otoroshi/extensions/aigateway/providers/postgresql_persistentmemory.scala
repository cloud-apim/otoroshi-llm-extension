package com.cloud.apim.otoroshi.extensions.aigateway.providers

import com.cloud.apim.otoroshi.extensions.aigateway._
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}

object PostgresqlPersistentMemoryClient {
  lazy val logger = Logger("PostgresqlPersistentMemoryClient")
  private val verifiedTables = new TrieMap[String, Boolean]()
}

class PostgresqlPersistentMemoryClient(val config: JsObject, _memoryId: String) extends PersistentMemoryClient {

  import PgPoolManager._
  import PostgresqlPersistentMemoryClient._

  private lazy val connection: JsObject = config.select("connection").asOpt[JsObject].getOrElse(Json.obj())
  private lazy val uri: String = connection.select("uri").asOptString.getOrElse("postgresql://otoroshi:otoroshi@localhost:5432/otoroshi")
  private lazy val table: String = connection.select("table").asOptString.getOrElse("otoroshi_ai_memories")

  private def pool = PgPoolManager.getPool(uri)

  private def docId(sessionId: String): String = s"${_memoryId}_${sessionId}"

  private def ensureTable()(implicit ec: ExecutionContext): Future[Unit] = {
    val key = s"$uri:$table"
    if (verifiedTables.contains(key)) {
      Future.successful(())
    } else {
      exec(pool,
        s"""CREATE TABLE IF NOT EXISTS $table (
          id TEXT PRIMARY KEY,
          memory_id TEXT NOT NULL,
          session_id TEXT NOT NULL,
          messages TEXT NOT NULL
        )"""
      ).map { _ =>
        verifiedTables.put(key, true)
        ()
      }.recover {
        case e: Throwable =>
          logger.warn(s"Table ensure warning: ${e.getMessage}")
          verifiedTables.put(key, true)
          ()
      }
    }
  }

  override def updateMessages(sessionId: String, messages: Seq[PersistedChatMessage])(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Unit]] = {
    ensureTable().flatMap { _ =>
      val json = Json.stringify(JsArray(messages.map(_.raw)))
      exec(pool,
        s"INSERT INTO $table (id, memory_id, session_id, messages) VALUES ($$1, $$2, $$3, $$4) ON CONFLICT (id) DO UPDATE SET messages = EXCLUDED.messages",
        Seq(docId(sessionId), _memoryId, sessionId, json)
      ).map(_ => Right(()))
    }.recover { case e: Throwable =>
      Left(Json.obj("error" -> s"PostgreSQL update error: ${e.getMessage}"))
    }
  }

  override def getMessages(sessionId: String)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Seq[PersistedChatMessage]]] = {
    ensureTable().flatMap { _ =>
      query(pool,
        s"SELECT messages FROM $table WHERE id = $$1",
        Seq(docId(sessionId))
      ).map { rows =>
        rows.headOption match {
          case Some(row) =>
            val messagesStr = row.getString("messages")
            val messages = Json.parse(messagesStr).as[Seq[JsObject]]
            Right(messages.map(PersistedChatMessage(_)))
          case None =>
            Right(Seq.empty[PersistedChatMessage])
        }
      }
    }.recover { case e: Throwable =>
      Left(Json.obj("error" -> s"PostgreSQL get error: ${e.getMessage}"))
    }
  }

  override def clearMemory(sessionId: String)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Unit]] = {
    exec(pool, s"DELETE FROM $table WHERE id = $$1", Seq(docId(sessionId)))
      .map(_ => Right(()))
      .recover { case e: Throwable =>
        Left(Json.obj("error" -> s"PostgreSQL delete error: ${e.getMessage}"))
      }
  }
}
