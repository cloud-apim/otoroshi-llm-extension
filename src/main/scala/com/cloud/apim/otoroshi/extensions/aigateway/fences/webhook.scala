package com.cloud.apim.otoroshi.extensions.aigateway.fences

import com.cloud.apim.otoroshi.extensions.aigateway.decorators.{Fence, FenceResult}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiProvider, HttpValidationSettings}
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatClient, ChatMessage}
import com.github.blemale.scaffeine.Scaffeine
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsArray, JsObject}

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

object WebhookFence {
  val cache = Scaffeine()
    .expireAfter[String, (FiniteDuration, Boolean)](
      create = (key, value) => value._1,
      update = (key, value, currentDuration) => currentDuration,
      read = (key, value, currentDuration) => currentDuration
    )
    .maximumSize(5000)
    .build[String, (FiniteDuration, Boolean)]()
}

class WebhookFence extends Fence {

  override def isBefore: Boolean = true

  override def isAfter: Boolean = false

  override def manyMessages: Boolean = true

  override def pass(messages: Seq[ChatMessage], config: JsObject, provider: AiProvider, chatClient: ChatClient, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[FenceResult] = {
    val key = messages.map(m => s"${m.role}:${m.content}").mkString(",").sha512
    val httpValidation = HttpValidationSettings.format.reads(config).getOrElse(HttpValidationSettings())
    httpValidation.url match {
      case None => FenceResult.FencePass.vfuture
      case _ => {
        WebhookFence.cache.getIfPresent(key) match {
          case Some((_, true)) => FenceResult.FencePass.vfuture
          case Some((_, false)) => FenceResult.FenceDenied("request content did not pass http validation").vfuture
          case None => {
            env.Ws
              .url(httpValidation.url.get)
              .withHttpHeaders(httpValidation.headers.toSeq: _*)
              .post(JsArray(messages.map(_.json))).flatMap { resp =>
                if (resp.status != 200) {
                  WebhookFence.cache.put(key, (httpValidation.ttl, false))
                  FenceResult.FenceDenied("request content did not pass http validation").vfuture
                } else {
                  val value = resp.json.select("result").asOpt[Boolean].getOrElse(false)
                  WebhookFence.cache.put(key, (httpValidation.ttl, value))
                  FenceResult.FencePass.vfuture
                }
              }
          }
        }
      }
    }
  }
}
