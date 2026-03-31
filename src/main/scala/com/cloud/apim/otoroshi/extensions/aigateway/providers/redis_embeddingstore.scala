package com.cloud.apim.otoroshi.extensions.aigateway.providers

import com.cloud.apim.otoroshi.extensions.aigateway._
import io.lettuce.core.codec.StringCodec
import io.lettuce.core.output.{IntegerOutput, NestedMultiOutput, StatusOutput}
import io.lettuce.core.protocol.{CommandArgs, ProtocolKeyword}
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._

import java.nio.charset.StandardCharsets
import java.nio.{ByteBuffer, ByteOrder}
import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters._

// ---------------------------------------------------------------------------
//  Redis Stack (RediSearch) backed EmbeddingStoreClient
//  Requires Redis with the Search module (redis-stack, redis-stack-server)
//  Reuses LettuceRedisClientManager for shared connections
// ---------------------------------------------------------------------------
object RedisEmbeddingStoreClient {
  lazy val logger = Logger("RedisEmbeddingStoreClient")

  // Track indices that have already been created/verified on this JVM
  private val verifiedIndices = new TrieMap[String, Boolean]()
  def resetVerifiedIndex(indexName: String): Unit = verifiedIndices.remove(indexName)

  private def cmd(name: String): ProtocolKeyword = new ProtocolKeyword {
    private val bytes = name.getBytes(StandardCharsets.US_ASCII)
    override def getBytes: Array[Byte] = bytes
    override def name(): String = name
  }

  val FT_CREATE: ProtocolKeyword = cmd("FT.CREATE")
  val FT_SEARCH: ProtocolKeyword = cmd("FT.SEARCH")
  val FT_DROPINDEX: ProtocolKeyword = cmd("FT.DROPINDEX")
  val HSET: ProtocolKeyword = cmd("HSET")
}

class RedisEmbeddingStoreClient(val config: JsObject, _storeId: String) extends EmbeddingStoreClient {

  import RedisEmbeddingStoreClient._

  private lazy val connection: JsObject = config.select("connection").asOpt[JsObject].getOrElse(Json.obj())
  private lazy val url: String = connection.select("url").asOptString.getOrElse("redis://localhost:6379")
  private lazy val prefix: String = connection.select("prefix").asOptString.getOrElse("otoroshi:ai:emb")
  private lazy val dims: Int = connection.select("dims").asOpt[Int].getOrElse(384)
  private lazy val distanceMetric: String = connection.select("distance_metric").asOptString.getOrElse("COSINE")

  private lazy val indexName: String = s"${prefix}:${_storeId}:idx"
  private lazy val keyPrefix: String = s"${prefix}:${_storeId}:"

  private def redisConn = LettuceRedisClientManager.getConnection(url)

  // ---- helpers ----

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

  private def floatsToBytes(floats: Array[Float]): Array[Byte] = {
    val buf = ByteBuffer.allocate(floats.length * 4).order(ByteOrder.LITTLE_ENDIAN)
    floats.foreach(buf.putFloat)
    buf.array()
  }

  // ---- index lifecycle ----

  private def ensureIndex()(implicit ec: ExecutionContext): Future[Unit] = {
    if (verifiedIndices.contains(indexName)) {
      Future.successful(())
    } else {
      val args = new CommandArgs[String, String](StringCodec.UTF8)
        .add(indexName)
        .add("ON").add("HASH")
        .add("PREFIX").add("1").add(keyPrefix)
        .add("SCHEMA")
        .add("doc_id").add("TAG")
        .add("text").add("TEXT")
        .add("embedding").add("VECTOR").add("FLAT")
        .add("6")
        .add("TYPE").add("FLOAT32")
        .add("DIM").add(dims.toString)
        .add("DISTANCE_METRIC").add(distanceMetric)

      toFuture(
        redisConn.async().dispatch(
          FT_CREATE,
          new StatusOutput[String, String](StringCodec.UTF8),
          args
        )
      ).map { _ =>
        verifiedIndices.put(indexName, true)
        ()
      }.recover {
        case e: Throwable if e.getMessage != null && e.getMessage.contains("Index already exists") =>
          verifiedIndices.put(indexName, true)
          ()
      }
    }
  }

  // ---- EmbeddingStoreClient impl ----

  override def add(options: EmbeddingAddOptions, raw: JsObject)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Unit]] = {
    ensureIndex().flatMap { _ =>
      val key = s"${keyPrefix}${options.id}"
      val vectorBytes = floatsToBytes(options.embedding.vector)

      // HSET key doc_id <id> text <input> embedding <binary blob>
      val args = new CommandArgs[String, String](StringCodec.UTF8)
        .addKey(key)
        .add("doc_id").add(options.id)
        .add("text").add(options.input)
        .add("embedding").add(vectorBytes)

      toFuture(
        redisConn.async().dispatch(
          HSET,
          new IntegerOutput[String, String](StringCodec.UTF8),
          args
        )
      ).map(_ => Right(()))
    }.recover { case e: Throwable =>
      Left(Json.obj("error" -> s"Redis add error: ${e.getMessage}"))
    }
  }

  override def remove(options: EmbeddingRemoveOptions, raw: JsObject)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Unit]] = {
    val key = s"${keyPrefix}${options.id}"
    toFuture(redisConn.async().del(key))
      .map(_ => Right(()))
      .recover { case e: Throwable =>
        Left(Json.obj("error" -> s"Redis remove error: ${e.getMessage}"))
      }
  }

  override def search(options: EmbeddingSearchOptions, raw: JsObject)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, EmbeddingSearchResponse]] = {
    ensureIndex().flatMap { _ =>
      val vectorBytes = floatsToBytes(options.embedding.vector)

      // FT.SEARCH idx "*=>[KNN k @embedding $vec AS vector_score]"
      //   PARAMS 2 vec <blob>
      //   SORTBY vector_score
      //   LIMIT 0 k
      //   RETURN 3 doc_id text vector_score
      //   DIALECT 2
      val query = s"*=>[KNN ${options.maxResults} @embedding $$vec AS vector_score]"
      val args = new CommandArgs[String, String](StringCodec.UTF8)
        .add(indexName)
        .add(query)
        .add("PARAMS").add("2").add("vec").add(vectorBytes)
        .add("SORTBY").add("vector_score")
        .add("LIMIT").add("0").add(options.maxResults.toString)
        .add("RETURN").add("3").add("doc_id").add("text").add("vector_score")
        .add("DIALECT").add("2")

      toFuture(
        redisConn.async().dispatch(
          FT_SEARCH,
          new NestedMultiOutput[String, String](StringCodec.UTF8),
          args
        )
      ).map { result =>
        val list = result.asScala.toList
        if (list.size < 2) {
          Right(EmbeddingSearchResponse(Seq.empty))
        } else {
          // FT.SEARCH returns: [totalCount, key1, [f1,v1,f2,v2,...], key2, [...], ...]
          val matches = (1 until list.size by 2).flatMap { i =>
            if (i + 1 < list.size) {
              val docKey = list(i).toString
              val fields = list(i + 1) match {
                case l: java.util.List[_] => l.asScala.map(_.toString).toList
                case _ => List.empty[String]
              }
              val fieldMap = fields.grouped(2).collect { case List(k, v) => k -> v }.toMap

              val distance = fieldMap.get("vector_score")
                .flatMap(s => scala.util.Try(s.toDouble).toOption)
                .getOrElse(Double.MaxValue)

              // convert distance to similarity score [0, 1]
              val score = distanceMetric.toUpperCase match {
                case "COSINE" => 1.0 - (distance / 2.0)
                case "L2"     => 1.0 / (1.0 + distance)
                case "IP"     => 1.0 - distance
                case _        => 1.0 - distance
              }

              if (score >= options.minScore) {
                Some(EmbeddingSearchMatch(
                  score = score,
                  id = fieldMap.getOrElse("doc_id", docKey.stripPrefix(keyPrefix)),
                  embedding = Embedding(Array.empty[Float]),
                  embedded = fieldMap.getOrElse("text", "")
                ))
              } else None
            } else None
          }
          Right(EmbeddingSearchResponse(matches))
        }
      }
    }.recover { case e: Throwable =>
      Left(Json.obj("error" -> s"Redis search error: ${e.getMessage}"))
    }
  }
}
