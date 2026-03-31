package com.cloud.apim.otoroshi.extensions.aigateway.providers

import com.cloud.apim.otoroshi.extensions.aigateway._
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}

object PostgresqlEmbeddingStoreClient {
  lazy val logger = Logger("PostgresqlEmbeddingStoreClient")
  private val verifiedTables = new TrieMap[String, Boolean]()
  def resetTable(table: String): Unit = verifiedTables.remove(table)
}

class PostgresqlEmbeddingStoreClient(val config: JsObject, _storeId: String) extends EmbeddingStoreClient {

  import PgPoolManager._
  import PostgresqlEmbeddingStoreClient._

  private lazy val connection: JsObject = config.select("connection").asOpt[JsObject].getOrElse(Json.obj())
  private lazy val uri: String = connection.select("uri").asOptString.getOrElse("postgresql://otoroshi:otoroshi@localhost:5432/otoroshi")
  private lazy val table: String = connection.select("table").asOptString.getOrElse("otoroshi_ai_embeddings")
  private lazy val dims: Int = connection.select("dims").asOpt[Int].getOrElse(384)

  private def pool = PgPoolManager.getPool(uri)

  private def vectorToString(vector: Array[Float]): String = s"[${vector.mkString(",")}]"

  private def parseVector(str: String): Array[Float] =
    str.stripPrefix("[").stripSuffix("]").split(",").map(_.trim.toFloat)

  private def ensureTable()(implicit ec: ExecutionContext): Future[Unit] = {
    val key = s"$uri:$table"
    if (verifiedTables.contains(key)) {
      Future.successful(())
    } else {
      exec(pool, "CREATE EXTENSION IF NOT EXISTS vector").flatMap { _ =>
        exec(pool, s"""CREATE TABLE IF NOT EXISTS $table (
          id TEXT PRIMARY KEY,
          content TEXT NOT NULL,
          embedding vector($dims) NOT NULL
        )""")
      }.map { _ =>
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

  override def add(options: EmbeddingAddOptions, raw: JsObject)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Unit]] = {
    ensureTable().flatMap { _ =>
      val vectorStr = vectorToString(options.embedding.vector)
      exec(pool,
        s"INSERT INTO $table (id, content, embedding) VALUES ($$1, $$2, $$3::vector) ON CONFLICT (id) DO UPDATE SET content = EXCLUDED.content, embedding = EXCLUDED.embedding",
        Seq(options.id, options.input, vectorStr)
      ).map(_ => Right(()))
    }.recover { case e: Throwable =>
      Left(Json.obj("error" -> s"PostgreSQL add error: ${e.getMessage}"))
    }
  }

  override def remove(options: EmbeddingRemoveOptions, raw: JsObject)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Unit]] = {
    exec(pool, s"DELETE FROM $table WHERE id = $$1", Seq(options.id))
      .map(_ => Right(()))
      .recover { case e: Throwable =>
        Left(Json.obj("error" -> s"PostgreSQL delete error: ${e.getMessage}"))
      }
  }

  override def search(options: EmbeddingSearchOptions, raw: JsObject)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, EmbeddingSearchResponse]] = {
    ensureTable().flatMap { _ =>
      val vectorStr = vectorToString(options.embedding.vector)
      query(pool,
        s"""SELECT id, content, embedding::text, 1 - (embedding <=> $$1::vector) AS score
           |FROM $table
           |WHERE 1 - (embedding <=> $$1::vector) >= $$2
           |ORDER BY embedding <=> $$1::vector
           |LIMIT $$3""".stripMargin,
        Seq(vectorStr, Double.box(options.minScore), Int.box(options.maxResults))
      ).map { rows =>
        val matches = rows.map { row =>
          EmbeddingSearchMatch(
            score = row.getDouble("score"),
            id = row.getString("id"),
            embedding = Embedding(parseVector(row.getString("embedding"))),
            embedded = row.getString("content")
          )
        }
        Right(EmbeddingSearchResponse(matches))
      }
    }.recover { case e: Throwable =>
      Left(Json.obj("error" -> s"PostgreSQL search error: ${e.getMessage}"))
    }
  }
}
