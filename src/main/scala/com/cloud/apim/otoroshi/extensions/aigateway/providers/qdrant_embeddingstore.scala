package com.cloud.apim.otoroshi.extensions.aigateway.providers

import com.cloud.apim.otoroshi.extensions.aigateway._
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

object QdrantEmbeddingStoreClient {
  lazy val logger = Logger("QdrantEmbeddingStoreClient")
}

class QdrantEmbeddingStoreClient(val config: JsObject, _storeId: String) extends EmbeddingStoreClient {

  private lazy val connection: JsObject = config.select("connection").asOpt[JsObject].getOrElse(Json.obj())
  private lazy val baseUrl: String = connection.select("url").asOptString.getOrElse("http://localhost:6333")
  private lazy val collectionName: String = connection.select("collection").asOptString.getOrElse("default")
  private lazy val apiKey: Option[String] = connection.select("api_key").asOptString
  private lazy val dims: Int = connection.select("dims").asOpt[Int].getOrElse(384)
  private lazy val distance: String = connection.select("distance").asOptString.getOrElse("Cosine")
  private lazy val timeout = connection.select("timeout").asOpt[Long].map(v => FiniteDuration(v, TimeUnit.MILLISECONDS)).getOrElse(30.seconds)

  private def headers: Seq[(String, String)] = {
    val base = Seq("Content-Type" -> "application/json", "Accept" -> "application/json")
    apiKey match {
      case Some(key) => base :+ ("api-key" -> key)
      case None => base
    }
  }

  private def ensureCollection()(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Unit]] = {
    env.Ws
      .url(s"$baseUrl/collections/$collectionName")
      .withHttpHeaders(headers: _*)
      .withRequestTimeout(timeout)
      .get()
      .flatMap { resp =>
        if (resp.status == 200) {
          Right(()).vfuture
        } else {
          env.Ws
            .url(s"$baseUrl/collections/$collectionName")
            .withHttpHeaders(headers: _*)
            .withRequestTimeout(timeout)
            .put(Json.obj(
              "vectors" -> Json.obj(
                "size" -> dims,
                "distance" -> distance
              )
            ))
            .map { createResp =>
              if (createResp.status == 200 || createResp.status == 201) {
                Right(())
              } else {
                Left(Json.obj("error" -> s"Qdrant collection creation error: ${createResp.status}", "body" -> createResp.body))
              }
            }
        }
      }
  }

  override def add(options: EmbeddingAddOptions, raw: JsObject)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Unit]] = {
    ensureCollection().flatMap {
      case Left(err) => Left(err).vfuture
      case Right(_) =>
        env.Ws
          .url(s"$baseUrl/collections/$collectionName/points")
          .withHttpHeaders(headers: _*)
          .withRequestTimeout(timeout)
          .put(Json.obj(
            "points" -> Json.arr(Json.obj(
              "id" -> options.id,
              "vector" -> Json.toJson(options.embedding.vector),
              "payload" -> Json.obj(
                "text" -> options.input,
                "doc_id" -> options.id
              )
            ))
          ))
          .map { resp =>
            if (resp.status == 200) {
              Right(())
            } else {
              Left(Json.obj("error" -> s"Qdrant add error: ${resp.status}", "body" -> resp.body))
            }
          }
    }
  }

  override def remove(options: EmbeddingRemoveOptions, raw: JsObject)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Unit]] = {
    env.Ws
      .url(s"$baseUrl/collections/$collectionName/points/delete")
      .withHttpHeaders(headers: _*)
      .withRequestTimeout(timeout)
      .post(Json.obj(
        "points" -> Json.arr(options.id)
      ))
      .map { resp =>
        if (resp.status == 200) {
          Right(())
        } else {
          Left(Json.obj("error" -> s"Qdrant delete error: ${resp.status}", "body" -> resp.body))
        }
      }
  }

  override def search(options: EmbeddingSearchOptions, raw: JsObject)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, EmbeddingSearchResponse]] = {
    env.Ws
      .url(s"$baseUrl/collections/$collectionName/points/search")
      .withHttpHeaders(headers: _*)
      .withRequestTimeout(timeout)
      .post(Json.obj(
        "vector" -> Json.toJson(options.embedding.vector),
        "limit" -> options.maxResults,
        "score_threshold" -> options.minScore,
        "with_payload" -> true,
        "with_vectors" -> true
      ))
      .map { resp =>
        if (resp.status == 200) {
          val results = resp.json.select("result").as[Seq[JsObject]]
          val matches = results.map { r =>
            EmbeddingSearchMatch(
              score = r.select("score").as[Double],
              id = r.select("id").asOpt[String].getOrElse(r.select("id").as[Long].toString),
              embedding = Embedding(r.select("vector").asOpt[Seq[Float]].map(_.toArray).getOrElse(Array.empty[Float])),
              embedded = r.select("payload").select("text").asOpt[String].getOrElse("")
            )
          }
          Right(EmbeddingSearchResponse(matches))
        } else {
          Left(Json.obj("error" -> s"Qdrant search error: ${resp.status}", "body" -> resp.body))
        }
      }
  }
}
