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

class LocalEmbeddingStoreClient(val config: JsObject, storeId: String) extends EmbeddingStoreClient {

  private def getStore(id: String)(implicit ec: ExecutionContext, env: Env): Future[dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore[TextSegment]] = {
    LocalEmbeddingStoreClient.stores.get(id) match {
      case Some(store) => store.vfuture
      case None => {
        val headers = config.select("connection").asOpt[Map[String, String]].getOrElse(Map.empty)
        config.select("connection").select("init_content").asOptString match {
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

  override def add(id: String, input: String, embedding: Embedding)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Unit]] = {
    getStore(storeId).map { store =>
      val lcEmbedding = new dev.langchain4j.data.embedding.Embedding(embedding.vector)
      store.add(id, lcEmbedding, TextSegment.from(input))
      ().right
    }
  }

  override def remove(id: String)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Unit]] = {
    getStore(storeId).map { store =>
      store.remove(id)
      ().right
    }
  }

  override def search(embedding: Embedding, maxResults: Int, minScore: Double)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, EmbeddingSearchResponse]] = {
    getStore(storeId).flatMap { store =>
      try {
        val lcEmbedding = new dev.langchain4j.data.embedding.Embedding(embedding.vector)
        val relevant = store.search(
          dev.langchain4j.store.embedding.EmbeddingSearchRequest
            .builder()
            .queryEmbedding(lcEmbedding)
            .maxResults(maxResults)
            .minScore(minScore)
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



