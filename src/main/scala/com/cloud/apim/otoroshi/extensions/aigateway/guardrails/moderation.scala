package com.cloud.apim.otoroshi.extensions.aigateway.guardrails

import com.cloud.apim.otoroshi.extensions.aigateway.{ChatClient, ChatMessage, ModerationModelClientInputOptions}
import com.cloud.apim.otoroshi.extensions.aigateway.decorators.{Guardrail, GuardrailResult}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.{JsBoolean, JsObject, Json}

import scala.concurrent.{ExecutionContext, Future}

class ModerationGuardrail extends Guardrail {

  override def isBefore: Boolean = true

  override def isAfter: Boolean = true

  override def manyMessages: Boolean = true

  override def pass(messages: Seq[ChatMessage], config: JsObject, provider: AiProvider, chatClient: ChatClient, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[GuardrailResult] = {
    val moderationModelRef = config.select("moderation_model").asString
    val ext = env.adminExtensions.extension[AiExtension].get
    ext.states.moderationModel(moderationModelRef) match {
      case None => GuardrailResult.GuardrailError("Moderation model not found").vfuture
      case Some(moderation) => {
        moderation.getModerationModelClient() match {
          case None => GuardrailResult.GuardrailError("Moderation client not found").vfuture
          case Some(client) => {
            val message = messages.map(_.wholeTextContent).mkString(". ")
            val opts = ModerationModelClientInputOptions(message)
            client.moderate(opts, Json.obj()).map {
              case Left(err) => GuardrailResult.GuardrailError(err.stringify)
              case Right(res) => {
                if (res.moderationResults.exists(_.isFlagged)) {
                  val categories: Seq[String] = res.moderationResults.flatMap(_.categories.value.collect {
                    case (key, JsBoolean(true)) => key
                  })
                  GuardrailResult.GuardrailDenied(s"Message has been flagged in the following categories: ${categories.mkString(", ")}")
                } else {
                  GuardrailResult.GuardrailPass
                }
              }
            }
          }
        }
      }
    }
  }
}