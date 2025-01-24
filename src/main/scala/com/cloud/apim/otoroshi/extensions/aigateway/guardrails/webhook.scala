package com.cloud.apim.otoroshi.extensions.aigateway.guardrails

import com.cloud.apim.otoroshi.extensions.aigateway.decorators.{Guardrail, GuardrailResult}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiProvider, HttpValidationSettings}
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatClient, ChatMessage, ChatMessageContentFlavor, InputChatMessage, OutputChatMessage}
import com.github.blemale.scaffeine.Scaffeine
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsArray, JsObject}

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

object WebhookGuardrail {
  val cache = Scaffeine()
    .expireAfter[String, (FiniteDuration, Boolean)](
      create = (key, value) => value._1,
      update = (key, value, currentDuration) => currentDuration,
      read = (key, value, currentDuration) => currentDuration
    )
    .maximumSize(5000)
    .build[String, (FiniteDuration, Boolean)]()
}

class WebhookGuardrail extends Guardrail {

  override def isBefore: Boolean = true

  override def isAfter: Boolean = false

  override def manyMessages: Boolean = true

  override def pass(messages: Seq[ChatMessage], config: JsObject, provider: AiProvider, chatClient: ChatClient, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[GuardrailResult] = {
    val key = messages.map(m => s"${m.role}:${m.wholeTextContent}").mkString(",").sha512
    val httpValidation = HttpValidationSettings.format.reads(config).getOrElse(HttpValidationSettings())
    httpValidation.url match {
      case None => GuardrailResult.GuardrailPass.vfuture
      case _ => {
        WebhookGuardrail.cache.getIfPresent(key) match {
          case Some((_, true)) => GuardrailResult.GuardrailPass.vfuture
          case Some((_, false)) => GuardrailResult.GuardrailDenied("request content did not pass http validation").vfuture
          case None => {
            val arr = messages.map {
              case i: InputChatMessage => i.json(ChatMessageContentFlavor.Common)
              case o: OutputChatMessage => o.json
            }
            env.Ws
              .url(httpValidation.url.get)
              .withHttpHeaders(httpValidation.headers.toSeq: _*)
              .post(JsArray(arr)).flatMap { resp =>
                if (resp.status != 200) {
                  WebhookGuardrail.cache.put(key, (httpValidation.ttl, false))
                  GuardrailResult.GuardrailDenied("request content did not pass http validation").vfuture
                } else {
                  val value = resp.json.select("result").asOpt[Boolean].getOrElse(false)
                  WebhookGuardrail.cache.put(key, (httpValidation.ttl, value))
                  GuardrailResult.GuardrailPass.vfuture
                }
              }
          }
        }
      }
    }
  }
}
