package com.cloud.apim.otoroshi.extensions.aigateway.providers

import com.cloud.apim.otoroshi.extensions.aigateway._
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

object OpenSearchPersistentMemoryClient {
  lazy val logger = Logger("OpenSearchPersistentMemoryClient")
}

class OpenSearchPersistentMemoryClient(val config: JsObject, _memoryId: String) extends PersistentMemoryClient {

  private lazy val connection: JsObject = config.select("connection").asOpt[JsObject].getOrElse(Json.obj())
  private lazy val baseUrl: String = connection.select("url").asOptString.getOrElse("http://localhost:9200")
  private lazy val index: String = connection.select("index").asOptString.getOrElse("persistent-memories")
  private lazy val username: Option[String] = connection.select("username").asOptString
  private lazy val password: Option[String] = connection.select("password").asOptString
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

  private def docId(sessionId: String): String = s"${_memoryId}_${sessionId}"

  override def updateMessages(sessionId: String, messages: Seq[PersistedChatMessage])(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Unit]] = {
    env.Ws
      .url(s"$baseUrl/$index/_doc/${docId(sessionId)}")
      .withHttpHeaders(headers: _*)
      .withRequestTimeout(timeout)
      .put(Json.obj(
        "memory_id" -> _memoryId,
        "session_id" -> sessionId,
        "messages" -> JsArray(messages.map(_.raw))
      ))
      .map { resp =>
        if (resp.status == 200 || resp.status == 201) {
          Right(())
        } else {
          Left(Json.obj("error" -> s"OpenSearch update error: ${resp.status}", "body" -> resp.body))
        }
      }
  }

  override def getMessages(sessionId: String)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Seq[PersistedChatMessage]]] = {
    env.Ws
      .url(s"$baseUrl/$index/_doc/${docId(sessionId)}")
      .withHttpHeaders(headers: _*)
      .withRequestTimeout(timeout)
      .get()
      .map { resp =>
        if (resp.status == 200) {
          val source = resp.json.select("_source").as[JsObject]
          val messages = source.select("messages").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
          Right(messages.map(PersistedChatMessage(_)))
        } else if (resp.status == 404) {
          Right(Seq.empty[PersistedChatMessage])
        } else {
          Left(Json.obj("error" -> s"OpenSearch get error: ${resp.status}", "body" -> resp.body))
        }
      }
  }

  override def clearMemory(sessionId: String)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Unit]] = {
    env.Ws
      .url(s"$baseUrl/$index/_doc/${docId(sessionId)}")
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
}
