package com.cloud.apim.otoroshi.extensions.aigateway.providers

import akka.stream.alpakka.s3._
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{Attributes, Materializer}
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway._
import otoroshi.env.Env
import otoroshi.storage.drivers.inmemory.S3Configuration
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.regions.providers.AwsRegionProvider

import scala.concurrent.{ExecutionContext, Future}

object S3PersistentMemoryClient {
  lazy val logger = Logger("S3PersistentMemoryClient")
}

class S3PersistentMemoryClient(val config: JsObject, _memoryId: String) extends PersistentMemoryClient {

  private lazy val connection: JsObject = config.select("connection").asOpt[JsObject].getOrElse(Json.obj())
  private lazy val s3Config: S3Configuration = S3Configuration.format.reads(connection).get
  private lazy val prefix: String = connection.select("prefix").asOptString.getOrElse("persistent-memories")

  private def s3Attrs: Attributes = {
    val awsCredentials = StaticCredentialsProvider.create(
      AwsBasicCredentials.create(s3Config.access, s3Config.secret)
    )
    val settings = S3Settings(
      bufferType = MemoryBufferType,
      credentialsProvider = awsCredentials,
      s3RegionProvider = new AwsRegionProvider {
        override def getRegion: Region = Region.of(s3Config.region)
      },
      listBucketApiVersion = ApiVersion.ListBucketVersion2
    ).withEndpointUrl(s3Config.endpoint)
    S3Attributes.settings(settings)
  }

  private def objectKey(sessionId: String): String = s"$prefix/${_memoryId}/$sessionId.json"

  override def updateMessages(sessionId: String, messages: Seq[PersistedChatMessage])(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Unit]] = {
    implicit val mat: Materializer = env.otoroshiMaterializer
    val payload = Json.obj("messages" -> JsArray(messages.map(_.raw)))
    val bytes = ByteString(Json.stringify(payload))
    val contentType = akka.http.scaladsl.model.ContentTypes.`application/json`
    Source.single(bytes)
      .runWith(
        S3.multipartUpload(s3Config.bucket, objectKey(sessionId), contentType)
          .withAttributes(s3Attrs)
      )
      .map(_ => Right(()))
      .recover { case e: Throwable =>
        Left(Json.obj("error" -> s"S3 upload error: ${e.getMessage}"))
      }
  }

  override def getMessages(sessionId: String)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Seq[PersistedChatMessage]]] = {
    implicit val mat: Materializer = env.otoroshiMaterializer
    S3.download(s3Config.bucket, objectKey(sessionId))
      .withAttributes(s3Attrs)
      .runWith(Sink.headOption)
      .map(_.flatten)
      .flatMap {
        case Some((source, _)) =>
          source.runFold(ByteString.empty)(_ ++ _).map { content =>
            val json = Json.parse(content.utf8String)
            val messages = json.select("messages").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
            Right(messages.map(PersistedChatMessage(_)))
          }
        case None =>
          Right(Seq.empty[PersistedChatMessage]).vfuture
      }
      .recover { case e: Throwable =>
        // NoSuchKey or similar -> empty
        Right(Seq.empty[PersistedChatMessage])
      }
  }

  override def clearMemory(sessionId: String)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Unit]] = {
    implicit val mat: Materializer = env.otoroshiMaterializer
    S3.deleteObject(s3Config.bucket, objectKey(sessionId))
      .withAttributes(s3Attrs)
      .runWith(Sink.head)
      .map(_ => Right(()))
      .recover { case _: Throwable => Right(()) }
  }
}
