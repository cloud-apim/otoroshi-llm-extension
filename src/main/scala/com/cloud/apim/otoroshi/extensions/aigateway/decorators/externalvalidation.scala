package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import com.cloud.apim.otoroshi.extensions.aigateway.{ChatClient, ChatPrompt, ChatResponse}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.github.blemale.scaffeine.Scaffeine
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import play.api.libs.json.{JsValue, Json}
import otoroshi.utils.syntax.implicits._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration

object ChatClientWithHttpValidation {
  val cache = Scaffeine()
    .expireAfter[String, (FiniteDuration, Boolean)](
      create = (key, value) => value._1,
      update = (key, value, currentDuration) => currentDuration,
      read = (key, value, currentDuration) => currentDuration
    )
    .maximumSize(5000)
    .build[String, (FiniteDuration, Boolean)]()
  def applyIfPossible(tuple: (AiProvider, ChatClient)): ChatClient = {
    if (tuple._1.httpValidation.url.isDefined) {
      new ChatClientWithHttpValidation(tuple._1, tuple._2)
    } else {
      tuple._2
    }
  }
}

class ChatClientWithHttpValidation(originalProvider: AiProvider, chatClient: ChatClient) extends ChatClient {

  private val ttl = originalProvider.httpValidation.ttl


  override def call(originalPrompt: ChatPrompt, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {

    def pass(): Future[Either[JsValue, ChatResponse]] = chatClient.call(originalPrompt, attrs)

    def fail(): Future[Either[JsValue, ChatResponse]] = Left(Json.obj("error" -> "bad_request", "error_description" -> s"request content did not pass http validation")).vfuture

    val key = originalPrompt.messages.map(m => s"${m.role}:${m.content}").mkString(",").sha512
    ChatClientWithHttpValidation.cache.getIfPresent(key) match {
      case Some((_, true)) => pass()
      case Some((_, false)) => fail()
      case None => {
        env.Ws
          .url(originalProvider.httpValidation.url.get)
          .withHttpHeaders(originalProvider.httpValidation.headers.toSeq: _*)
          .post(originalPrompt.json).flatMap { resp =>
            if (resp.status != 200) {
              ChatClientWithHttpValidation.cache.put(key, (ttl, false))
              fail()
            } else {
              val value = resp.json.select("result").asOpt[Boolean].getOrElse(false)
              ChatClientWithHttpValidation.cache.put(key, (ttl, value))
              pass()
            }
          }
      }
    }
  }
}