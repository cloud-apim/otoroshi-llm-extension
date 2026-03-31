package com.cloud.apim.otoroshi.extensions.aigateway.providers

import com.cloud.apim.otoroshi.extensions.aigateway._
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

object PineconeEmbeddingStoreClient {
  lazy val logger = Logger("PineconeEmbeddingStoreClient")
}

class PineconeEmbeddingStoreClient(val config: JsObject, _storeId: String) extends EmbeddingStoreClient {

  private lazy val connection: JsObject = config.select("connection").asOpt[JsObject].getOrElse(Json.obj())
  private lazy val baseUrl: String = connection.select("url").asString // e.g. https://index-name-xxxxx.svc.environment.pinecone.io
  private lazy val apiKey: String = connection.select("api_key").asString
  private lazy val namespace: String = connection.select("namespace").asOptString.getOrElse("")
  private lazy val timeout = connection.select("timeout").asOpt[Long].map(v => FiniteDuration(v, TimeUnit.MILLISECONDS)).getOrElse(30.seconds)

  private def headers: Seq[(String, String)] = Seq(
    "Content-Type" -> "application/json",
    "Accept" -> "application/json",
    "Api-Key" -> apiKey
  )

  override def add(options: EmbeddingAddOptions, raw: JsObject)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Unit]] = {
    env.Ws
      .url(s"$baseUrl/vectors/upsert")
      .withHttpHeaders(headers: _*)
      .withRequestTimeout(timeout)
      .post(Json.obj(
        "vectors" -> Json.arr(Json.obj(
          "id" -> options.id,
          "values" -> Json.toJson(options.embedding.vector),
          "metadata" -> Json.obj(
            "text" -> options.input
          )
        )),
        "namespace" -> namespace
      ))
      .map { resp =>
        if (resp.status == 200) {
          Right(())
        } else {
          Left(Json.obj("error" -> s"Pinecone upsert error: ${resp.status}", "body" -> resp.body))
        }
      }
  }

  override def remove(options: EmbeddingRemoveOptions, raw: JsObject)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Unit]] = {
    env.Ws
      .url(s"$baseUrl/vectors/delete")
      .withHttpHeaders(headers: _*)
      .withRequestTimeout(timeout)
      .post(Json.obj(
        "ids" -> Json.arr(options.id),
        "namespace" -> namespace
      ))
      .map { resp =>
        if (resp.status == 200) {
          Right(())
        } else {
          Left(Json.obj("error" -> s"Pinecone delete error: ${resp.status}", "body" -> resp.body))
        }
      }
  }

  override def search(options: EmbeddingSearchOptions, raw: JsObject)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, EmbeddingSearchResponse]] = {
    env.Ws
      .url(s"$baseUrl/query")
      .withHttpHeaders(headers: _*)
      .withRequestTimeout(timeout)
      .post(Json.obj(
        "vector" -> Json.toJson(options.embedding.vector),
        "topK" -> options.maxResults,
        "includeMetadata" -> true,
        "includeValues" -> true,
        "namespace" -> namespace
      ))
      .map { resp =>
        if (resp.status == 200) {
          val results = resp.json.select("matches").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
          val matches = results.flatMap { r =>
            val score = r.select("score").asOpt[Double].getOrElse(0.0)
            if (score >= options.minScore) {
              Some(EmbeddingSearchMatch(
                score = score,
                id = r.select("id").asString,
                embedding = Embedding(r.select("values").asOpt[Seq[Float]].map(_.toArray).getOrElse(Array.empty[Float])),
                embedded = r.select("metadata").select("text").asOpt[String].getOrElse("")
              ))
            } else None
          }
          Right(EmbeddingSearchResponse(matches))
        } else {
          Left(Json.obj("error" -> s"Pinecone query error: ${resp.status}", "body" -> resp.body))
        }
      }
  }
}
