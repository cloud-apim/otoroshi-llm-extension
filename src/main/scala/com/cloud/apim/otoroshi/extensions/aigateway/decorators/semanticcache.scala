package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import akka.stream.scaladsl.{Sink, Source}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.providers.LettuceRedisClientManager
import com.cloud.apim.otoroshi.extensions.aigateway._
import com.github.benmanes.caffeine.cache.RemovalCause
import com.github.blemale.scaffeine.Scaffeine
import dev.langchain4j.data.segment.TextSegment
import io.lettuce.core.codec.StringCodec
import io.lettuce.core.output.{IntegerOutput, NestedMultiOutput, StatusOutput}
import io.lettuce.core.protocol.{CommandArgs, ProtocolKeyword}
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.JsValue

import java.nio.charset.StandardCharsets
import java.nio.{ByteBuffer, ByteOrder}
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{DurationInt, DurationLong, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters._

// ---------------------------------------------------------------------------
//  Entry point — picks the right implementation based on redis_url
// ---------------------------------------------------------------------------
object ChatClientWithSemanticCache {

  lazy val embeddingModel = new dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel()

  def applyIfPossible(tuple: (AiProvider, ChatClient, Env)): ChatClient = {
    if (tuple._1.cache.strategy.contains("semantic")) {
      tuple._1.cache.redisUrl match {
        case Some(url) => new ChatClientWithSemanticCacheRedis(tuple._1, tuple._2, url)
        case None      => new ChatClientWithSemanticCacheMemory(tuple._1, tuple._2)
      }
    } else {
      tuple._2
    }
  }
}

// ---------------------------------------------------------------------------
//  In-memory implementation (Caffeine + LangChain4j InMemoryEmbeddingStore)
// ---------------------------------------------------------------------------
object ChatClientWithSemanticCacheMemory {
  lazy val embeddingStores = new TrieMap[String, dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore[TextSegment]]()
  lazy val cache = Scaffeine()
    .expireAfter[String, (FiniteDuration, ChatResponse, Long)](
      create = (key, value) => value._1,
      update = (key, value, currentDuration) => currentDuration,
      read = (key, value, currentDuration) => currentDuration
    )
    .maximumSize(5000)
    .build[String, (FiniteDuration, ChatResponse, Long)]()
  lazy val stream_cache = Scaffeine()
    .expireAfter[String, (FiniteDuration, Seq[ChatResponseChunk], Long)](
      create = (key, value) => value._1,
      update = (key, value, currentDuration) => currentDuration,
      read = (key, value, currentDuration) => currentDuration
    )
    .maximumSize(5000)
    .build[String, (FiniteDuration, Seq[ChatResponseChunk], Long)]()
  lazy val cacheEmbeddingCleanup = Scaffeine()
    .expireAfter[String, (FiniteDuration, Function[String, Unit])](
      create = (key, value) => value._1,
      update = (key, value, currentDuration) => currentDuration,
      read = (key, value, currentDuration) => currentDuration
    )
    .removalListener((key: String, value: (FiniteDuration, Function[String, Unit]), reason: RemovalCause) => {
      value._2(key)
    })
    .maximumSize(5000)
    .build[String, (FiniteDuration, Function[String, Unit])]()
}

class ChatClientWithSemanticCacheMemory(originalProvider: AiProvider, val chatClient: ChatClient) extends DecoratorChatClient {

  private val ttl = originalProvider.cache.ttl

  private def notInCache(key: String, originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue, completion: Boolean)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val embeddingModel = ChatClientWithSemanticCache.embeddingModel
    val embeddingStore = ChatClientWithSemanticCacheMemory.embeddingStores.getOrUpdate(originalProvider.id) {
      new dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore[TextSegment]()
    }
    val r = if (completion) chatClient.completion(originalPrompt, attrs, originalBody) else chatClient.call(originalPrompt, attrs, originalBody)
    r.map {
      case Left(err) => err.left
      case Right(resp) =>
        val segment = TextSegment.from(originalPrompt.messages.map(_.content).mkString(". "))
        val embedding = embeddingModel.embed(segment).content()
        embeddingStore.add(key, embedding, segment)
        ChatClientWithSemanticCacheMemory.cache.put(key, (ttl, resp, System.currentTimeMillis()))
        ChatClientWithSemanticCacheMemory.cacheEmbeddingCleanup.put(key, (ttl, (key) => {
          embeddingStore.remove(key)
        }))
        resp.copy(metadata = resp.metadata.copy(
          cache = Some(ChatResponseCache(ChatResponseCacheStatus.Miss, key, ttl, 0.millis))
        )).right
    }
  }

  private def notInCacheStream(key: String, originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue, completion: Boolean)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    val embeddingModel = ChatClientWithSemanticCache.embeddingModel
    val embeddingStore = ChatClientWithSemanticCacheMemory.embeddingStores.getOrUpdate(originalProvider.id) {
      new dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore[TextSegment]()
    }
    val r = if (completion) chatClient.completionStream(originalPrompt, attrs, originalBody) else chatClient.stream(originalPrompt, attrs, originalBody)
    r.map {
      case Left(err) => err.left
      case Right(resp) =>
        var chunks = Seq.empty[ChatResponseChunk]
        resp
          .alsoTo(Sink.foreach { chunk => chunks = chunks :+ chunk })
          .alsoTo(Sink.onComplete { _ =>
            val segment = TextSegment.from(originalPrompt.messages.map(_.content).mkString(". "))
            val embedding = embeddingModel.embed(segment).content()
            embeddingStore.add(key, embedding, segment)
            ChatClientWithSemanticCacheMemory.stream_cache.put(key, (ttl, chunks, System.currentTimeMillis()))
            ChatClientWithSemanticCacheMemory.cacheEmbeddingCleanup.put(key, (ttl, (key) => {
              embeddingStore.remove(key)
            }))
          }).right
    }
  }

  private def internalCall(originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue, completion: Boolean)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val query = originalPrompt.messages.filter(_.role.toLowerCase().trim == "user").map(_.content).mkString(", ")
    val key = query.sha512
    ChatClientWithSemanticCacheMemory.cache.getIfPresent(key) match {
      case Some((_, response, at)) =>
        response.copy(metadata = response.metadata.copy(
          usage = ChatResponseMetadataUsage.empty,
          cache = Some(ChatResponseCache(ChatResponseCacheStatus.Hit, key, ttl, (System.currentTimeMillis() - at).millis))
        )).rightf
      case None =>
        val embeddingModel = ChatClientWithSemanticCache.embeddingModel
        val embeddingStore = ChatClientWithSemanticCacheMemory.embeddingStores.getOrUpdate(originalProvider.id) {
          new dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore[TextSegment]()
        }
        val queryEmbedding = embeddingModel.embed(query).content()
        val relevant = embeddingStore.search(dev.langchain4j.store.embedding.EmbeddingSearchRequest.builder().queryEmbedding(queryEmbedding).maxResults(1).minScore(originalProvider.cache.score).build())
        val matches = relevant.matches().asScala
        if (matches.nonEmpty) {
          val id = matches.head.embeddingId()
          ChatClientWithSemanticCacheMemory.cache.getIfPresent(id) match {
            case None => notInCache(key, originalPrompt, attrs, originalBody, completion)
            case Some(cached) =>
              cached._2.copy(metadata = cached._2.metadata.copy(
                usage = ChatResponseMetadataUsage.empty,
                cache = Some(ChatResponseCache(ChatResponseCacheStatus.Hit, key, ttl, (System.currentTimeMillis() - cached._3).millis))
              )).rightf
          }
        } else {
          notInCache(key, originalPrompt, attrs, originalBody, completion)
        }
    }
  }

  private def internalStream(originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue, completion: Boolean)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    val query = originalPrompt.messages.filter(_.role.toLowerCase().trim == "user").map(_.content).mkString(", ")
    val key = query.sha512
    ChatClientWithSemanticCacheMemory.stream_cache.getIfPresent(key) match {
      case Some((_, response, _)) => Source(response.toList).rightf
      case None =>
        val embeddingModel = ChatClientWithSemanticCache.embeddingModel
        val embeddingStore = ChatClientWithSemanticCacheMemory.embeddingStores.getOrUpdate(originalProvider.id) {
          new dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore[TextSegment]()
        }
        val queryEmbedding = embeddingModel.embed(query).content()
        val relevant = embeddingStore.search(dev.langchain4j.store.embedding.EmbeddingSearchRequest.builder().queryEmbedding(queryEmbedding).maxResults(1).minScore(originalProvider.cache.score).build())
        val matches = relevant.matches().asScala
        if (matches.nonEmpty) {
          val id = matches.head.embeddingId()
          ChatClientWithSemanticCacheMemory.stream_cache.getIfPresent(id) match {
            case None => notInCacheStream(key, originalPrompt, attrs, originalBody, completion)
            case Some(cached) => Source(cached._2.toList).rightf
          }
        } else {
          notInCacheStream(key, originalPrompt, attrs, originalBody, completion)
        }
    }
  }

  override def call(originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] =
    internalCall(originalPrompt, attrs, originalBody, completion = false)

  override def stream(originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] =
    internalStream(originalPrompt, attrs, originalBody, completion = false)

  override def completion(originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] =
    internalCall(originalPrompt, attrs, originalBody, completion = true)

  override def completionStream(originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] =
    internalStream(originalPrompt, attrs, originalBody, completion = true)
}

// ---------------------------------------------------------------------------
//  Redis implementation (Lettuce + RediSearch for vector similarity)
//  Embeddings stored via HSET + FT.SEARCH, responses via GET/PSETEX
//  TTL on both embedding hashes and response keys => auto-expiry
// ---------------------------------------------------------------------------
object ChatClientWithSemanticCacheRedis {

  private val DIMS = 384 // AllMiniLmL6V2 output dimension

  private val verifiedIndices = new TrieMap[String, Boolean]()
  def resetVerifiedIndex(indexName: String): Unit = verifiedIndices.remove(indexName)

  private def cmd(name: String): ProtocolKeyword = new ProtocolKeyword {
    private val bytes = name.getBytes(StandardCharsets.US_ASCII)
    override def getBytes: Array[Byte] = bytes
    override def name(): String = name
  }

  val FT_CREATE: ProtocolKeyword = cmd("FT.CREATE")
  val FT_SEARCH: ProtocolKeyword = cmd("FT.SEARCH")
  val HSET: ProtocolKeyword = cmd("HSET")
}

class ChatClientWithSemanticCacheRedis(originalProvider: AiProvider, val chatClient: ChatClient, redisUrl: String) extends DecoratorChatClient {

  import ChatClientWithSemanticCacheRedis._

  private val ttl = originalProvider.cache.ttl
  private val minScore = originalProvider.cache.score
  private val providerId = originalProvider.id
  private val indexName = s"sem-cache:$providerId:idx"
  private val embKeyPrefix = s"sem-cache:$providerId:"

  private def conn = LettuceRedisClientManager.getConnection(redisUrl)

  // --- helpers ---

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

  // --- index lifecycle ---

  private def ensureIndex()(implicit ec: ExecutionContext): Future[Unit] = {
    if (verifiedIndices.contains(indexName)) {
      Future.successful(())
    } else {
      val args = new CommandArgs[String, String](StringCodec.UTF8)
        .add(indexName)
        .add("ON").add("HASH")
        .add("PREFIX").add("1").add(embKeyPrefix)
        .add("SCHEMA")
        .add("doc_id").add("TAG")
        .add("text").add("TEXT")
        .add("embedding").add("VECTOR").add("FLAT")
        .add("6")
        .add("TYPE").add("FLOAT32")
        .add("DIM").add(DIMS.toString)
        .add("DISTANCE_METRIC").add("COSINE")

      toFuture(conn.async().dispatch(FT_CREATE, new StatusOutput[String, String](StringCodec.UTF8), args))
        .map { _ => verifiedIndices.put(indexName, true); () }
        .recover {
          case e: Throwable if e.getMessage != null && e.getMessage.contains("Index already exists") =>
            verifiedIndices.put(indexName, true); ()
        }
    }
  }

  // --- embedding store operations ---

  private def storeEmbedding(key: String, query: String, vectorBytes: Array[Byte])(implicit ec: ExecutionContext): Future[Unit] = {
    val hashKey = s"$embKeyPrefix$key"
    val args = new CommandArgs[String, String](StringCodec.UTF8)
      .addKey(hashKey)
      .add("doc_id").add(key)
      .add("text").add(query)
      .add("embedding").add(vectorBytes)
    toFuture(conn.async().dispatch(HSET, new IntegerOutput[String, String](StringCodec.UTF8), args))
      .flatMap { _ =>
        // set TTL on the hash so it auto-expires from the index
        toFuture(conn.async().pexpire(hashKey, ttl.toMillis))
      }
      .map(_ => ())
  }

  private def searchSimilar(vectorBytes: Array[Byte])(implicit ec: ExecutionContext): Future[Option[(String, Double)]] = {
    val query = s"*=>[KNN 1 @embedding $$vec AS vector_score]"
    val args = new CommandArgs[String, String](StringCodec.UTF8)
      .add(indexName)
      .add(query)
      .add("PARAMS").add("2").add("vec").add(vectorBytes)
      .add("SORTBY").add("vector_score")
      .add("LIMIT").add("0").add("1")
      .add("RETURN").add("2").add("doc_id").add("vector_score")
      .add("DIALECT").add("2")

    toFuture(conn.async().dispatch(FT_SEARCH, new NestedMultiOutput[String, String](StringCodec.UTF8), args))
      .map { result =>
        val list = result.asScala.toList
        if (list.size < 3) {
          None
        } else {
          // [totalCount, key1, [field, value, ...]]
          val fields = list(2) match {
            case l: java.util.List[_] => l.asScala.map(_.toString).toList
            case _ => List.empty[String]
          }
          val fieldMap = fields.grouped(2).collect { case List(k, v) => k -> v }.toMap
          val distance = fieldMap.get("vector_score").flatMap(s => scala.util.Try(s.toDouble).toOption).getOrElse(Double.MaxValue)
          val score = 1.0 - (distance / 2.0) // COSINE distance → similarity
          val docId = fieldMap.getOrElse("doc_id", "")
          if (score >= minScore && docId.nonEmpty) Some((docId, score)) else None
        }
      }
      .recover { case _ => None }
  }

  // --- response cache operations ---

  private def redisGetResponse(key: String)(implicit ec: ExecutionContext): Future[Option[(ChatResponse, Long)]] = {
    toFuture(conn.async().get(s"sem-cache:call:$key"))
      .map {
        case null => None
        case value => scala.util.Try(SimpleCacheSerialization.deserializeResponse(value)).toOption
      }
      .recover { case _ => None }
  }

  private def redisPutResponse(key: String, resp: ChatResponse)(implicit ec: ExecutionContext): Unit = {
    val json = SimpleCacheSerialization.serializeResponse(resp, System.currentTimeMillis())
    toFuture(conn.async().psetex(s"sem-cache:call:$key", ttl.toMillis, json))
  }

  private def redisGetChunks(key: String)(implicit ec: ExecutionContext): Future[Option[(Seq[ChatResponseChunk], Long)]] = {
    toFuture(conn.async().get(s"sem-cache:stream:$key"))
      .map {
        case null => None
        case value => scala.util.Try(SimpleCacheSerialization.deserializeChunks(value)).toOption
      }
      .recover { case _ => None }
  }

  private def redisPutChunks(key: String, chunks: Seq[ChatResponseChunk])(implicit ec: ExecutionContext): Unit = {
    val json = SimpleCacheSerialization.serializeChunks(chunks, System.currentTimeMillis())
    toFuture(conn.async().psetex(s"sem-cache:stream:$key", ttl.toMillis, json))
  }

  // --- cache miss handlers ---

  private def notInCache(key: String, originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue, completion: Boolean)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val r = if (completion) chatClient.completion(originalPrompt, attrs, originalBody) else chatClient.call(originalPrompt, attrs, originalBody)
    r.flatMap {
      case Left(err) => err.leftf
      case Right(resp) =>
        val query = originalPrompt.messages.map(_.content).mkString(". ")
        val embedding = ChatClientWithSemanticCache.embeddingModel.embed(query).content()
        val vectorBytes = floatsToBytes(embedding.vector())
        ensureIndex().flatMap { _ =>
          storeEmbedding(key, query, vectorBytes).map { _ =>
            redisPutResponse(key, resp)
            resp.copy(metadata = resp.metadata.copy(
              cache = Some(ChatResponseCache(ChatResponseCacheStatus.Miss, key, ttl, 0.millis))
            )).right
          }
        }.recover { case _ =>
          // if Redis fails, still return the response
          resp.copy(metadata = resp.metadata.copy(
            cache = Some(ChatResponseCache(ChatResponseCacheStatus.Miss, key, ttl, 0.millis))
          )).right
        }
    }
  }

  private def notInCacheStream(key: String, originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue, completion: Boolean)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    val r = if (completion) chatClient.completionStream(originalPrompt, attrs, originalBody) else chatClient.stream(originalPrompt, attrs, originalBody)
    r.map {
      case Left(err) => err.left
      case Right(resp) =>
        var chunks = Seq.empty[ChatResponseChunk]
        resp
          .alsoTo(Sink.foreach { chunk => chunks = chunks :+ chunk })
          .alsoTo(Sink.onComplete { _ =>
            val query = originalPrompt.messages.map(_.content).mkString(". ")
            val embedding = ChatClientWithSemanticCache.embeddingModel.embed(query).content()
            val vectorBytes = floatsToBytes(embedding.vector())
            ensureIndex().flatMap(_ => storeEmbedding(key, query, vectorBytes)).foreach { _ =>
              redisPutChunks(key, chunks)
            }
          }).right
    }
  }

  // --- main logic ---

  private def internalCall(originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue, completion: Boolean)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val query = originalPrompt.messages.filter(_.role.toLowerCase().trim == "user").map(_.content).mkString(", ")
    val key = query.sha512
    // 1. exact key lookup
    redisGetResponse(key).flatMap {
      case Some((response, at)) =>
        response.copy(metadata = response.metadata.copy(
          usage = ChatResponseMetadataUsage.empty,
          cache = Some(ChatResponseCache(ChatResponseCacheStatus.Hit, key, ttl, (System.currentTimeMillis() - at).millis))
        )).rightf
      case None =>
        // 2. semantic similarity search
        ensureIndex().flatMap { _ =>
          val embedding = ChatClientWithSemanticCache.embeddingModel.embed(query).content()
          val vectorBytes = floatsToBytes(embedding.vector())
          searchSimilar(vectorBytes).flatMap {
            case Some((matchedId, _)) =>
              redisGetResponse(matchedId).flatMap {
                case Some((cachedResp, at)) =>
                  cachedResp.copy(metadata = cachedResp.metadata.copy(
                    usage = ChatResponseMetadataUsage.empty,
                    cache = Some(ChatResponseCache(ChatResponseCacheStatus.Hit, key, ttl, (System.currentTimeMillis() - at).millis))
                  )).rightf
                case None => notInCache(key, originalPrompt, attrs, originalBody, completion)
              }
            case None => notInCache(key, originalPrompt, attrs, originalBody, completion)
          }
        }.recoverWith { case _ => notInCache(key, originalPrompt, attrs, originalBody, completion) }
    }
  }

  private def internalStream(originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue, completion: Boolean)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    val query = originalPrompt.messages.filter(_.role.toLowerCase().trim == "user").map(_.content).mkString(", ")
    val key = query.sha512
    // 1. exact key lookup
    redisGetChunks(key).flatMap {
      case Some((chunks, _)) => Source(chunks.toList).rightf
      case None =>
        // 2. semantic similarity search
        ensureIndex().flatMap { _ =>
          val embedding = ChatClientWithSemanticCache.embeddingModel.embed(query).content()
          val vectorBytes = floatsToBytes(embedding.vector())
          searchSimilar(vectorBytes).flatMap {
            case Some((matchedId, _)) =>
              redisGetChunks(matchedId).flatMap {
                case Some((cachedChunks, _)) => Source(cachedChunks.toList).rightf
                case None => notInCacheStream(key, originalPrompt, attrs, originalBody, completion)
              }
            case None => notInCacheStream(key, originalPrompt, attrs, originalBody, completion)
          }
        }.recoverWith { case _ => notInCacheStream(key, originalPrompt, attrs, originalBody, completion) }
    }
  }

  override def call(originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] =
    internalCall(originalPrompt, attrs, originalBody, completion = false)

  override def stream(originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] =
    internalStream(originalPrompt, attrs, originalBody, completion = false)

  override def completion(originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] =
    internalCall(originalPrompt, attrs, originalBody, completion = true)

  override def completionStream(originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] =
    internalStream(originalPrompt, attrs, originalBody, completion = true)
}
