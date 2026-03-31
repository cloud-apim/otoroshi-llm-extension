package com.cloud.apim.otoroshi.extensions.aigateway.providers

import com.cloud.apim.otoroshi.extensions.aigateway._
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

object ElasticsearchEmbeddingStoreClient {
  lazy val logger = Logger("ElasticsearchEmbeddingStoreClient")
}

class ElasticsearchEmbeddingStoreClient(val config: JsObject, _storeId: String) extends EmbeddingStoreClient {

  private lazy val connection: JsObject = config.select("connection").asOpt[JsObject].getOrElse(Json.obj())
  private lazy val baseUrl: String = connection.select("url").asOptString.getOrElse("http://localhost:9200")
  private lazy val index: String = connection.select("index").asOptString.getOrElse("embeddings")
  private lazy val username: Option[String] = connection.select("username").asOptString
  private lazy val password: Option[String] = connection.select("password").asOptString
  private lazy val apiKey: Option[String] = connection.select("api_key").asOptString
  private lazy val dims: Int = connection.select("dims").asOpt[Int].getOrElse(384)
  private lazy val similarity: String = connection.select("similarity").asOptString.getOrElse("cosine")
  private lazy val timeout = connection.select("timeout").asOpt[Long].map(v => FiniteDuration(v, TimeUnit.MILLISECONDS)).getOrElse(30.seconds)

  private def headers: Seq[(String, String)] = {
    val base = Seq("Content-Type" -> "application/json", "Accept" -> "application/json")
    val auth = apiKey match {
      case Some(key) => Seq("Authorization" -> s"ApiKey $key")
      case None => (username, password) match {
        case (Some(u), Some(p)) =>
          val encoded = java.util.Base64.getEncoder.encodeToString(s"$u:$p".getBytes("UTF-8"))
          Seq("Authorization" -> s"Basic $encoded")
        case _ => Seq.empty
      }
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
              "mappings" -> Json.obj(
                "properties" -> Json.obj(
                  "embedding" -> Json.obj(
                    "type" -> "dense_vector",
                    "dims" -> dims,
                    "index" -> true,
                    "similarity" -> similarity
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
                Left(Json.obj("error" -> s"Elasticsearch index creation error: ${createResp.status}", "body" -> createResp.body))
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
              Left(Json.obj("error" -> s"Elasticsearch add error: ${resp.status}", "body" -> resp.body))
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
          Left(Json.obj("error" -> s"Elasticsearch delete error: ${resp.status}", "body" -> resp.body))
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
        "knn" -> Json.obj(
          "field" -> "embedding",
          "query_vector" -> Json.toJson(options.embedding.vector),
          "k" -> options.maxResults,
          "num_candidates" -> Math.max(options.maxResults * 10, 100)
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
          Left(Json.obj("error" -> s"Elasticsearch search error: ${resp.status}", "body" -> resp.body))
        }
      }
  }
}
