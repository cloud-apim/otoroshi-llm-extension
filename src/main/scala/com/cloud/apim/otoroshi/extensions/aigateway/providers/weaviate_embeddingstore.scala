package com.cloud.apim.otoroshi.extensions.aigateway.providers

import com.cloud.apim.otoroshi.extensions.aigateway._
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

object WeaviateEmbeddingStoreClient {
  lazy val logger = Logger("WeaviateEmbeddingStoreClient")
}

class WeaviateEmbeddingStoreClient(val config: JsObject, _storeId: String) extends EmbeddingStoreClient {

  private lazy val connection: JsObject = config.select("connection").asOpt[JsObject].getOrElse(Json.obj())
  private lazy val baseUrl: String = connection.select("url").asOptString.getOrElse("http://localhost:8080")
  private lazy val className: String = connection.select("class_name").asOptString.getOrElse("Embedding")
  private lazy val apiKey: Option[String] = connection.select("api_key").asOptString
  private lazy val timeout = connection.select("timeout").asOpt[Long].map(v => FiniteDuration(v, TimeUnit.MILLISECONDS)).getOrElse(30.seconds)

  private def headers: Seq[(String, String)] = {
    val base = Seq("Content-Type" -> "application/json", "Accept" -> "application/json")
    apiKey match {
      case Some(key) => base :+ ("Authorization" -> s"Bearer $key")
      case None => base
    }
  }

  private def ensureClass()(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Unit]] = {
    env.Ws
      .url(s"$baseUrl/v1/schema/$className")
      .withHttpHeaders(headers: _*)
      .withRequestTimeout(timeout)
      .get()
      .flatMap { resp =>
        if (resp.status == 200) {
          Right(()).vfuture
        } else {
          env.Ws
            .url(s"$baseUrl/v1/schema")
            .withHttpHeaders(headers: _*)
            .withRequestTimeout(timeout)
            .post(Json.obj(
              "class" -> className,
              "vectorizer" -> "none",
              "properties" -> Json.arr(
                Json.obj("name" -> "text", "dataType" -> Json.arr("text")),
                Json.obj("name" -> "doc_id", "dataType" -> Json.arr("text"))
              )
            ))
            .map { createResp =>
              if (createResp.status == 200 || createResp.status == 201) {
                Right(())
              } else {
                Left(Json.obj("error" -> s"Weaviate class creation error: ${createResp.status}", "body" -> createResp.body))
              }
            }
        }
      }
  }

  override def add(options: EmbeddingAddOptions, raw: JsObject)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Unit]] = {
    ensureClass().flatMap {
      case Left(err) => Left(err).vfuture
      case Right(_) =>
        env.Ws
          .url(s"$baseUrl/v1/objects")
          .withHttpHeaders(headers: _*)
          .withRequestTimeout(timeout)
          .post(Json.obj(
            "class" -> className,
            "id" -> options.id,
            "properties" -> Json.obj(
              "text" -> options.input,
              "doc_id" -> options.id
            ),
            "vector" -> Json.toJson(options.embedding.vector)
          ))
          .map { resp =>
            if (resp.status == 200 || resp.status == 201) {
              Right(())
            } else if (resp.status == 422) {
              // Object already exists, update it
              Right(())
            } else {
              Left(Json.obj("error" -> s"Weaviate add error: ${resp.status}", "body" -> resp.body))
            }
          }
    }
  }

  override def remove(options: EmbeddingRemoveOptions, raw: JsObject)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Unit]] = {
    env.Ws
      .url(s"$baseUrl/v1/objects/$className/${options.id}")
      .withHttpHeaders(headers: _*)
      .withRequestTimeout(timeout)
      .delete()
      .map { resp =>
        if (resp.status == 204 || resp.status == 404) {
          Right(())
        } else {
          Left(Json.obj("error" -> s"Weaviate delete error: ${resp.status}", "body" -> resp.body))
        }
      }
  }

  override def search(options: EmbeddingSearchOptions, raw: JsObject)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, EmbeddingSearchResponse]] = {
    val graphql = Json.obj(
      "query" ->
        s"""{
           |  Get {
           |    $className(
           |      nearVector: { vector: [${options.embedding.vector.mkString(",")}] }
           |      limit: ${options.maxResults}
           |    ) {
           |      text
           |      doc_id
           |      _additional {
           |        id
           |        certainty
           |        vector
           |      }
           |    }
           |  }
           |}""".stripMargin
    )
    env.Ws
      .url(s"$baseUrl/v1/graphql")
      .withHttpHeaders(headers: _*)
      .withRequestTimeout(timeout)
      .post(graphql)
      .map { resp =>
        if (resp.status == 200) {
          val results = resp.json.select("data").select("Get").select(className).asOpt[Seq[JsObject]].getOrElse(Seq.empty)
          val matches = results.flatMap { r =>
            val additional = r.select("_additional").as[JsObject]
            val certainty = additional.select("certainty").asOpt[Double].getOrElse(0.0)
            if (certainty >= options.minScore) {
              Some(EmbeddingSearchMatch(
                score = certainty,
                id = additional.select("id").asOpt[String].orElse(r.select("doc_id").asOptString).getOrElse(""),
                embedding = Embedding(additional.select("vector").asOpt[Seq[Float]].map(_.toArray).getOrElse(Array.empty[Float])),
                embedded = r.select("text").asOpt[String].getOrElse("")
              ))
            } else None
          }
          Right(EmbeddingSearchResponse(matches))
        } else {
          Left(Json.obj("error" -> s"Weaviate search error: ${resp.status}", "body" -> resp.body))
        }
      }
  }
}
