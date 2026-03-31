package com.cloud.apim.otoroshi.extensions.aigateway.providers

import com.cloud.apim.otoroshi.extensions.aigateway._
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

// Generic HTTP backend for persistent memory
// The remote API is expected to support:
//   GET    {baseUrl}/{memoryId}/{sessionId}  -> { "messages": [...] }
//   PUT    {baseUrl}/{memoryId}/{sessionId}  <- { "messages": [...] }
//   DELETE {baseUrl}/{memoryId}/{sessionId}
object HttpPersistentMemoryClient {
  lazy val logger = Logger("HttpPersistentMemoryClient")
}

class HttpPersistentMemoryClient(val config: JsObject, _memoryId: String) extends PersistentMemoryClient {

  private lazy val connection: JsObject = config.select("connection").asOpt[JsObject].getOrElse(Json.obj())
  private lazy val baseUrl: String = connection.select("url").asString
  private lazy val extraHeaders: Map[String, String] = connection.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty)
  private lazy val apiKey: Option[String] = connection.select("api_key").asOptString
  private lazy val timeout = connection.select("timeout").asOpt[Long].map(v => FiniteDuration(v, TimeUnit.MILLISECONDS)).getOrElse(30.seconds)

  private def headers: Seq[(String, String)] = {
    val base = Seq("Content-Type" -> "application/json", "Accept" -> "application/json")
    val auth = apiKey match {
      case Some(key) => Seq("Authorization" -> s"Bearer $key")
      case None => Seq.empty
    }
    base ++ auth ++ extraHeaders.toSeq
  }

  private def sessionUrl(sessionId: String): String = s"$baseUrl/${_memoryId}/$sessionId"

  override def updateMessages(sessionId: String, messages: Seq[PersistedChatMessage])(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Unit]] = {
    env.Ws
      .url(sessionUrl(sessionId))
      .withHttpHeaders(headers: _*)
      .withRequestTimeout(timeout)
      .put(Json.obj(
        "messages" -> JsArray(messages.map(_.raw))
      ))
      .map { resp =>
        if (resp.status >= 200 && resp.status < 300) {
          Right(())
        } else {
          Left(Json.obj("error" -> s"HTTP memory update error: ${resp.status}", "body" -> resp.body))
        }
      }
  }

  override def getMessages(sessionId: String)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Seq[PersistedChatMessage]]] = {
    env.Ws
      .url(sessionUrl(sessionId))
      .withHttpHeaders(headers: _*)
      .withRequestTimeout(timeout)
      .get()
      .map { resp =>
        if (resp.status == 200) {
          val messages = resp.json.select("messages").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
          Right(messages.map(PersistedChatMessage(_)))
        } else if (resp.status == 404) {
          Right(Seq.empty[PersistedChatMessage])
        } else {
          Left(Json.obj("error" -> s"HTTP memory get error: ${resp.status}", "body" -> resp.body))
        }
      }
  }

  override def clearMemory(sessionId: String)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Unit]] = {
    env.Ws
      .url(sessionUrl(sessionId))
      .withHttpHeaders(headers: _*)
      .withRequestTimeout(timeout)
      .delete()
      .map { resp =>
        if (resp.status >= 200 && resp.status < 300 || resp.status == 404) {
          Right(())
        } else {
          Left(Json.obj("error" -> s"HTTP memory delete error: ${resp.status}", "body" -> resp.body))
        }
      }
  }
}
