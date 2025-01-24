package com.cloud.apim.otoroshi.extensions.aigateway.guardrails

import com.cloud.apim.otoroshi.extensions.aigateway.{ChatClient, ChatMessage, ChatMessageContent, InputChatMessage, OutputChatMessage}
import com.cloud.apim.otoroshi.extensions.aigateway.decorators.{Guardrail, GuardrailResult}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits._
import otoroshi.utils.{RegexPool, TypedMap}
import play.api.libs.json.JsObject

import scala.concurrent.{ExecutionContext, Future}

class RegexGuardrail extends Guardrail {

  override def isBefore: Boolean = true

  override def isAfter: Boolean = true

  override def manyMessages: Boolean = false

  def validate(content: String, allow: Seq[String], deny: Seq[String]): Boolean = {
    val allowed = if (allow.isEmpty) true else allow.exists(al => RegexPool.regex(al).matches(content))
    val denied = if (deny.isEmpty) false else deny.exists(dn => RegexPool.regex(dn).matches(content))
    !denied && allowed
  }

  override def pass(messages: Seq[ChatMessage], config: JsObject, provider: AiProvider, chatClient: ChatClient, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[GuardrailResult] = {
    val allow = config.select("allow").asOpt[Seq[String]].getOrElse(Seq.empty)
    val deny = config.select("deny").asOpt[Seq[String]].getOrElse(Seq.empty)

    messages.head match {
      case i: InputChatMessage => {
        val content: String = i.wholeTextContent
        if (validate(content, allow, deny)) {
          GuardrailResult.GuardrailPass.vfuture
        } else {
          GuardrailResult.GuardrailDenied("message does not match regex").vfuture
        }
      }
      case OutputChatMessage(_, content, _) => {
        if (validate(content, allow, deny)) {
          GuardrailResult.GuardrailPass.vfuture
        } else {
          GuardrailResult.GuardrailDenied("message does not match regex").vfuture
        }
      }
    }
  }
}
