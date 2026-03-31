package com.cloud.apim.otoroshi.extensions.aigateway.providers

import com.cloud.apim.otoroshi.extensions.aigateway._
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

object ChromaDbEmbeddingStoreClient {
  lazy val logger = Logger("ChromaDbEmbeddingStoreClient")
}

class ChromaDbEmbeddingStoreClient(val config: JsObject, _storeId: String) extends EmbeddingStoreClient {

  private lazy val connection: JsObject = config.select("connection").asOpt[JsObject].getOrElse(Json.obj())
  private lazy val baseUrl: String = connection.select("url").asOptString.getOrElse("http://localhost:8000")
  private lazy val collectionName: String = connection.select("collection").asOptString.getOrElse("default")
  private lazy val tenant: String = connection.select("tenant").asOptString.getOrElse("default_tenant")
  private lazy val database: String = connection.select("database").asOptString.getOrElse("default_database")
  private lazy val apiKey: Option[String] = connection.select("api_key").asOptString
  private lazy val timeout = connection.select("timeout").asOpt[Long].map(v => FiniteDuration(v, TimeUnit.MILLISECONDS)).getOrElse(30.seconds)

  private def headers: Seq[(String, String)] = {
    val base = Seq("Content-Type" -> "application/json", "Accept" -> "application/json")
    apiKey match {
      case Some(key) => base :+ ("Authorization" -> s"Bearer $key")
      case None => base
    }
  }

  private def getOrCreateCollection()(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, String]] = {
    env.Ws
      .url(s"$baseUrl/api/v1/collections/$collectionName?tenant=$tenant&database=$database")
      .withHttpHeaders(headers: _*)
      .withRequestTimeout(timeout)
      .get()
      .flatMap { resp =>
        if (resp.status == 200) {
          Right(resp.json.select("id").asString).vfuture
        } else {
          env.Ws
            .url(s"$baseUrl/api/v1/collections?tenant=$tenant&database=$database")
            .withHttpHeaders(headers: _*)
            .withRequestTimeout(timeout)
            .post(Json.obj(
              "name" -> collectionName,
              "get_or_create" -> true
            ))
            .map { createResp =>
              if (createResp.status == 200 || createResp.status == 201) {
                Right(createResp.json.select("id").asString)
              } else {
                Left(Json.obj("error" -> s"ChromaDB collection error: ${createResp.status}", "body" -> createResp.body))
              }
            }
        }
      }
  }

  override def add(options: EmbeddingAddOptions, raw: JsObject)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Unit]] = {
    getOrCreateCollection().flatMap {
      case Left(err) => Left(err).vfuture
      case Right(collectionId) =>
        env.Ws
          .url(s"$baseUrl/api/v1/collections/$collectionId/add")
          .withHttpHeaders(headers: _*)
          .withRequestTimeout(timeout)
          .post(Json.obj(
            "ids" -> Json.arr(options.id),
            "embeddings" -> Json.arr(Json.toJson(options.embedding.vector)),
            "documents" -> Json.arr(options.input)
          ))
          .map { resp =>
            if (resp.status == 200 || resp.status == 201) {
              Right(())
            } else {
              Left(Json.obj("error" -> s"ChromaDB add error: ${resp.status}", "body" -> resp.body))
            }
          }
    }
  }

  override def remove(options: EmbeddingRemoveOptions, raw: JsObject)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Unit]] = {
    getOrCreateCollection().flatMap {
      case Left(err) => Left(err).vfuture
      case Right(collectionId) =>
        env.Ws
          .url(s"$baseUrl/api/v1/collections/$collectionId/delete")
          .withHttpHeaders(headers: _*)
          .withRequestTimeout(timeout)
          .post(Json.obj(
            "ids" -> Json.arr(options.id)
          ))
          .map { resp =>
            if (resp.status == 200) {
              Right(())
            } else {
              Left(Json.obj("error" -> s"ChromaDB delete error: ${resp.status}", "body" -> resp.body))
            }
          }
    }
  }

  override def search(options: EmbeddingSearchOptions, raw: JsObject)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, EmbeddingSearchResponse]] = {
    getOrCreateCollection().flatMap {
      case Left(err) => Left(err).vfuture
      case Right(collectionId) =>
        env.Ws
          .url(s"$baseUrl/api/v1/collections/$collectionId/query")
          .withHttpHeaders(headers: _*)
          .withRequestTimeout(timeout)
          .post(Json.obj(
            "query_embeddings" -> Json.arr(Json.toJson(options.embedding.vector)),
            "n_results" -> options.maxResults,
            "include" -> Json.arr("embeddings", "documents", "distances")
          ))
          .map { resp =>
            if (resp.status == 200) {
              val ids = resp.json.select("ids").as[Seq[Seq[String]]].headOption.getOrElse(Seq.empty)
              val distances = resp.json.select("distances").as[Seq[Seq[Double]]].headOption.getOrElse(Seq.empty)
              val documents = resp.json.select("documents").as[Seq[Seq[String]]].headOption.getOrElse(Seq.empty)
              val embeddings = resp.json.select("embeddings").asOpt[Seq[Seq[Seq[Float]]]].flatMap(_.headOption).getOrElse(Seq.empty)
              val matches = ids.indices.flatMap { i =>
                // ChromaDB returns distances (lower is better), convert to score (higher is better)
                val distance = distances.lift(i).getOrElse(1.0)
                val score = 1.0 / (1.0 + distance)
                if (score >= options.minScore) {
                  Some(EmbeddingSearchMatch(
                    score = score,
                    id = ids(i),
                    embedding = Embedding(embeddings.lift(i).map(_.toArray).getOrElse(Array.empty[Float])),
                    embedded = documents.lift(i).getOrElse("")
                  ))
                } else None
              }
              Right(EmbeddingSearchResponse(matches))
            } else {
              Left(Json.obj("error" -> s"ChromaDB query error: ${resp.status}", "body" -> resp.body))
            }
          }
    }
  }
}
