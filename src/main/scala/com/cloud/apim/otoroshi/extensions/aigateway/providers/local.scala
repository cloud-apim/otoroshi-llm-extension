package com.cloud.apim.otoroshi.extensions.aigateway.providers

import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.alpakka.s3._
import akka.stream.scaladsl.Sink
import akka.stream.{Attributes, Materializer}
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway._
import dev.langchain4j.data.segment.TextSegment
import otoroshi.env.Env
import otoroshi.storage.drivers.inmemory.S3Configuration
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.regions.providers.AwsRegionProvider

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._

class AllMiniLmL6V2EmbeddingModelClient(val options: JsObject, id: String) extends EmbeddingModelClient {

  lazy val embeddingModel = new dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel()

  override def embed(opts: EmbeddingClientInputOptions, rawBody: JsObject)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, EmbeddingResponse]] = {
    val r = embeddingModel.embedAll(seqAsJavaList(opts.input.map(s => TextSegment.from(s))))
    try {
      Right(EmbeddingResponse(
        model = "all-minilm-l6-v2",
        embeddings = r.content().asScala.map(e => Embedding(e.vector())),
        metadata = EmbeddingResponseMetadata(-1L),
      )).vfuture
    } catch {
      case e: Throwable => Left(Json.obj("message" -> e.getMessage)).vfuture
    }
  }
}

object LocalEmbeddingStoreClient {
  lazy val logger = Logger("LocalEmbeddingStoreClient")
  lazy val stores = new TrieMap[String, dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore[TextSegment]]()
}

class LocalEmbeddingStoreClient(val config: JsObject, _storeId: String) extends EmbeddingStoreClient {

  private lazy val connection: JsObject = config.select("connection").asOpt[JsObject].getOrElse(Json.obj())
  private lazy val _sessionId: Option[String] = connection.select("session_id").asOptString

  private def getStore()(implicit ec: ExecutionContext, env: Env): Future[dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore[TextSegment]] = {
    val id = _sessionId match {
      case Some(sessionId) => s"${_storeId}:${sessionId}"
      case None => _storeId
    }
    LocalEmbeddingStoreClient.stores.get(id) match {
      case Some(store) => store.vfuture
      case None => {
        val headers = connection.asOpt[Map[String, String]].getOrElse(Map.empty)
        connection.select("init_content").asOptString match {
          case None => {
            val s = new dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore[TextSegment]()
            LocalEmbeddingStoreClient.stores.put(id, s)
            s.vfuture
          }
          case Some(path) => getInitialStoreContent(path, headers).map { c =>
            val s = dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore.fromJson(c)
            LocalEmbeddingStoreClient.stores.put(id, s)
            s
          }
        }
      }
    }
  }

  override def add(options: EmbeddingAddOptions, raw: JsObject)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Unit]] = {
    getStore().map { store =>
      val lcEmbedding = new dev.langchain4j.data.embedding.Embedding(options.embedding.vector)
      store.add(options.id, lcEmbedding, TextSegment.from(options.input))
      ().right
    }
  }

  override def remove(options: EmbeddingRemoveOptions, raw: JsObject)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Unit]] = {
    getStore().map { store =>
      store.remove(options.id)
      ().right
    }
  }

  override def search(options: EmbeddingSearchOptions, raw: JsObject)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, EmbeddingSearchResponse]] = {
    getStore().flatMap { store =>
      try {
        val lcEmbedding = new dev.langchain4j.data.embedding.Embedding(options.embedding.vector)
        val relevant = store.search(
          dev.langchain4j.store.embedding.EmbeddingSearchRequest
            .builder()
            .queryEmbedding(lcEmbedding)
            .maxResults(options.maxResults)
            .minScore(options.minScore)
            .build()
        )
        val matches = relevant.matches().asScala
        Right(EmbeddingSearchResponse(
          matches.toSeq.map(m => EmbeddingSearchMatch(
            score = m.score(),
            id = m.embeddingId(),
            embedding = Embedding(m.embedding().vector()),
            embedded = m.embedded().text()
          ))
        )).vfuture
      } catch {
        case e: Throwable => Left(Json.obj("error" -> e.getMessage)).vfuture
      }
    }
  }

  private def s3ClientSettingsAttrs(conf: S3Configuration): Attributes = {
    val awsCredentials = StaticCredentialsProvider.create(
      AwsBasicCredentials.create(conf.access, conf.secret)
    )
    val settings       = S3Settings(
      bufferType = MemoryBufferType,
      credentialsProvider = awsCredentials,
      s3RegionProvider = new AwsRegionProvider {
        override def getRegion: Region = Region.of(conf.region)
      },
      listBucketApiVersion = ApiVersion.ListBucketVersion2
    ).withEndpointUrl(conf.endpoint)
    S3Attributes.settings(settings)
  }

  private def fileContent(key: String, config: S3Configuration)(implicit
                                                                ec: ExecutionContext,
                                                                mat: Materializer
  ): Future[Option[(ObjectMetadata, ByteString)]] = {
    S3.download(config.bucket, key)
      .withAttributes(s3ClientSettingsAttrs(config))
      .runWith(Sink.headOption)
      .map(_.flatten)
      .flatMap { opt =>
        opt
          .map {
            case (source, om) => {
              source.runFold(ByteString.empty)(_ ++ _).map { content =>
                (om, content).some
              }
            }
          }
          .getOrElse(None.vfuture)
      }
  }

  private def getDefaultCode()(implicit env: Env, ec: ExecutionContext): Future[String] = {
    """{"entries":[]}""".vfuture
  }

  private def getInitialStoreContent(path: String, headers: Map[String, String])(implicit env: Env, ec: ExecutionContext): Future[String] = {
    import LocalEmbeddingStoreClient.logger
    if (path.startsWith("https://") || path.startsWith("http://")) {
      env.Ws.url(path)
        .withFollowRedirects(true)
        .withRequestTimeout(30.seconds)
        .withHttpHeaders(headers.toSeq: _*)
        .get()
        .flatMap { response =>
          if (response.status == 200) {
            response.body.vfuture
          } else {
            getDefaultCode()
          }
        }
    } else if (path.startsWith("file://")) {
      val file = new File(path.replace("file://", ""), "")
      if (file.exists()) {
        val code = Files.readString(file.toPath)
        code.vfuture
      } else {
        getDefaultCode()
      }
    } else if (path.startsWith("s3://")) {
      logger.info(s"fetching from S3: ${path}")
      val config = S3Configuration.format.reads(JsObject(headers.mapValues(_.json))).get
      fileContent(path.replaceFirst("s3://", ""), config)(env.otoroshiExecutionContext, env.otoroshiMaterializer).flatMap {
        case None => {
          logger.info(s"unable to fetch from S3: ${path}")
          getDefaultCode()
        }
        case Some((_, codeRaw)) => {
          val code = codeRaw.utf8String
          code.vfuture
        }
      }.recoverWith {
        case t: Throwable => {
          logger.error(s"error when fetch from S3: ${path}", t)
          getDefaultCode()
        }
      }
    } else {
      getDefaultCode()
    }
  }
}

case class LocalPersistentMemoryClientMemory(sessionId: String) {
  private val messages = new AtomicReference[Seq[PersistedChatMessage]](Seq.empty)
  def getMessages(): Seq[PersistedChatMessage] = messages.get()
  def updateMessages(m: Seq[PersistedChatMessage]): Seq[PersistedChatMessage] = messages.updateAndGet(_ => m)
  def clearMessages(): Unit = messages.set(Seq.empty)
}

object LocalPersistentMemoryClient {
  val memories = new TrieMap[String, TrieMap[String, LocalPersistentMemoryClientMemory]]()
}

class LocalPersistentMemoryClient(val config: JsObject, id: String) extends PersistentMemoryClient {

  private def getMemory(sessionId: String)(implicit env: Env, ec: ExecutionContext): LocalPersistentMemoryClientMemory = {
    val memories = LocalPersistentMemoryClient.memories.getOrElseUpdate(id, new TrieMap[String, LocalPersistentMemoryClientMemory]())
    memories.getOrElseUpdate(sessionId, LocalPersistentMemoryClientMemory(sessionId))
  }

  override def updateMessages(sessionId: String, messages: Seq[PersistedChatMessage])(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Unit]] = {
    getMemory(sessionId).updateMessages(messages)
    ().rightf
  }

  override def getMessages(sessionId: String)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Seq[PersistedChatMessage]]] = {
    getMemory(sessionId).getMessages().rightf
  }

  override def clearMemory(sessionId: String)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Unit]] = {
    getMemory(sessionId).clearMessages().rightf
  }
}


