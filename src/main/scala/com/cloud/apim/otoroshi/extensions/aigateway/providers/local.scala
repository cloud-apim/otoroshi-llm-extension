package com.cloud.apim.otoroshi.extensions.aigateway.providers

import com.cloud.apim.otoroshi.extensions.aigateway._
import dev.langchain4j.data.segment.TextSegment
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._

class AllMiniLmL6V2EmbeddingModelClient(val options: JsObject, id: String) extends EmbeddingModelClient {

  lazy val embeddingModel = new dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel()

  override def embed(input: Seq[String])(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, EmbeddingResponse]] = {
    val r = embeddingModel.embedAll(seqAsJavaList(input.map(s => TextSegment.from(s))))
    try {
      Right(EmbeddingResponse(
        embeddings = r.content().asScala.toSeq.map(e => Embedding(e.vector())),
        metadata = EmbeddingResponseMetadata(),
      )).vfuture
    } catch {
      case e: Throwable => Left(Json.obj("message" -> e.getMessage)).vfuture
    }
  }
}