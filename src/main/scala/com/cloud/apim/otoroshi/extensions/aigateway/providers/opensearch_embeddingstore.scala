package com.cloud.apim.otoroshi.extensions.aigateway.providers

import com.cloud.apim.otoroshi.extensions.aigateway._
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

object OpenSearchEmbeddingStoreClient {
  lazy val logger = Logger("OpenSearchEmbeddingStoreClient")
}

class OpenSearchEmbeddingStoreClient(val config: JsObject, _storeId: String) extends EmbeddingStoreClient {

  private lazy val connection: JsObject = config.select("connection").asOpt[JsObject].getOrElse(Json.obj())
  private lazy val baseUrl: String = connection.select("url").asOptString.getOrElse("http://localhost:9200")
  private lazy val index: String = connection.select("index").asOptString.getOrElse("embeddings")
  private lazy val username: Option[String] = connection.select("username").asOptString
  private lazy val password: Option[String] = connection.select("password").asOptString
  private lazy val dims: Int = connection.select("dims").asOpt[Int].getOrElse(384)
  private lazy val engine: String = connection.select("engine").asOptString.getOrElse("lucene")
  private lazy val spaceType: String = connection.select("space_type").asOptString.getOrElse("cosinesimil")
  private lazy val timeout = connection.select("timeout").asOpt[Long].map(v => FiniteDuration(v, TimeUnit.MILLISECONDS)).getOrElse(30.seconds)

  private def headers: Seq[(String, String)] = {
    val base = Seq("Content-Type" -> "application/json", "Accept" -> "application/json")
    val auth = (username, password) match {
      case (Some(u), Some(p)) =>
        val encoded = java.util.Base64.getEncoder.encodeToString(s"$u:$p".getBytes("UTF-8"))
        Seq("Authorization" -> s"Basic $encoded")
      case _ => Seq.empty
    }
    base ++ auth
  }

  private def ensureIndex()(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Unit]] = {
    env.Ws
      .url(s"$baseUrl/$index")
      .withHttpHeaders(headers: _*)
      .withRequestTimeout(timeout)
      .head()
      .flatMap { resp =>
        if (resp.status == 200) {
          Right(()).vfuture
        } else {
          env.Ws
            .url(s"$baseUrl/$index")
            .withHttpHeaders(headers: _*)
            .withRequestTimeout(timeout)
            .put(Json.obj(
              "settings" -> Json.obj(
                "index" -> Json.obj(
                  "knn" -> true
                )
              ),
              "mappings" -> Json.obj(
                "properties" -> Json.obj(
                  "embedding" -> Json.obj(
                    "type" -> "knn_vector",
                    "dimension" -> dims,
                    "method" -> Json.obj(
                      "name" -> "hnsw",
                      "space_type" -> spaceType,
                      "engine" -> engine
                    )
                  ),
                  "text" -> Json.obj("type" -> "text"),
                  "doc_id" -> Json.obj("type" -> "keyword")
                )
              )
            ))
            .map { createResp =>
              if (createResp.status == 200 || createResp.status == 201) {
                Right(())
              } else {
                Left(Json.obj("error" -> s"OpenSearch index creation error: ${createResp.status}", "body" -> createResp.body))
              }
            }
        }
      }
  }

  override def add(options: EmbeddingAddOptions, raw: JsObject)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Unit]] = {
    ensureIndex().flatMap {
      case Left(err) => Left(err).vfuture
      case Right(_) =>
        env.Ws
          .url(s"$baseUrl/$index/_doc/${options.id}")
          .withHttpHeaders(headers: _*)
          .withRequestTimeout(timeout)
          .put(Json.obj(
            "doc_id" -> options.id,
            "text" -> options.input,
            "embedding" -> Json.toJson(options.embedding.vector)
          ))
          .map { resp =>
            if (resp.status == 200 || resp.status == 201) {
              Right(())
            } else {
              Left(Json.obj("error" -> s"OpenSearch add error: ${resp.status}", "body" -> resp.body))
            }
          }
    }
  }

  override def remove(options: EmbeddingRemoveOptions, raw: JsObject)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Unit]] = {
    env.Ws
      .url(s"$baseUrl/$index/_doc/${options.id}")
      .withHttpHeaders(headers: _*)
      .withRequestTimeout(timeout)
      .delete()
      .map { resp =>
        if (resp.status == 200 || resp.status == 404) {
          Right(())
        } else {
          Left(Json.obj("error" -> s"OpenSearch delete error: ${resp.status}", "body" -> resp.body))
        }
      }
  }

  override def search(options: EmbeddingSearchOptions, raw: JsObject)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, EmbeddingSearchResponse]] = {
    env.Ws
      .url(s"$baseUrl/$index/_search")
      .withHttpHeaders(headers: _*)
      .withRequestTimeout(timeout)
      .post(Json.obj(
        "size" -> options.maxResults,
        "min_score" -> options.minScore,
        "query" -> Json.obj(
          "knn" -> Json.obj(
            "embedding" -> Json.obj(
              "vector" -> Json.toJson(options.embedding.vector),
              "k" -> options.maxResults
            )
          )
        ),
        "_source" -> Json.arr("doc_id", "text", "embedding")
      ))
      .map { resp =>
        if (resp.status == 200) {
          val hits = resp.json.select("hits").select("hits").as[Seq[JsObject]]
          val matches = hits.flatMap { hit =>
            val score = hit.select("_score").asOpt[Double].getOrElse(0.0)
            val source = hit.select("_source").as[JsObject]
            val id = source.select("doc_id").asOptString.getOrElse(hit.select("_id").asString)
            val text = source.select("text").asOptString.getOrElse("")
            val emb = source.select("embedding").asOpt[Seq[Float]].map(_.toArray).getOrElse(Array.empty[Float])
            if (score >= options.minScore) {
              Some(EmbeddingSearchMatch(score = score, id = id, embedding = Embedding(emb), embedded = text))
            } else None
          }
          Right(EmbeddingSearchResponse(matches))
        } else {
          Left(Json.obj("error" -> s"OpenSearch search error: ${resp.status}", "body" -> resp.body))
        }
      }
  }
}
