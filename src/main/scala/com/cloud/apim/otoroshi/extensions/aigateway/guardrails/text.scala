package com.cloud.apim.otoroshi.extensions.aigateway.guardrails

import com.cloud.apim.otoroshi.extensions.aigateway.{ChatClient, ChatMessage}
import com.cloud.apim.otoroshi.extensions.aigateway.decorators.{Guardrail, GuardrailResult}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.JsObject

import scala.concurrent.{ExecutionContext, Future}

class ContainsGuardrail extends Guardrail {

  override def isBefore: Boolean = true

  override def isAfter: Boolean = true

  override def manyMessages: Boolean = false

  override def pass(messages: Seq[ChatMessage], config: JsObject, provider: AiProvider, chatClient: ChatClient, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[GuardrailResult] = {
    val message = messages.head
    val operation = config.select("operation").asOpt[String].getOrElse("contains_all")
    val values = config.select("values").asOpt[Seq[String]].getOrElse(Seq.empty)
    operation match {
      case "contains_all" if values.forall(value => message.content.contains(value)) => GuardrailResult.GuardrailPass.vfuture
      case "contains_none" if values.forall(value => !message.content.contains(value)) => GuardrailResult.GuardrailPass.vfuture
      case "contains_any" if values.exists(value => message.content.contains(value)) => GuardrailResult.GuardrailPass.vfuture
      case _ => GuardrailResult.GuardrailDenied(s"This message has been blocked by the 'contains' guardrails !").vfuture
    }
  }
}

class CharactersCountGuardrail extends Guardrail {

  override def isBefore: Boolean = true

  override def isAfter: Boolean = true

  override def manyMessages: Boolean = false

  override def pass(messages: Seq[ChatMessage], config: JsObject, provider: AiProvider, chatClient: ChatClient, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[GuardrailResult] = {
    val message = messages.head
    val min = config.select("min").asOpt[Long].getOrElse(0L)
    val max = config.select("max").asOpt[Long].getOrElse(Long.MaxValue)
    val count = message.content.length
    val pass = count >= min && count <= max
    if (pass) {
      GuardrailResult.GuardrailPass.vfuture
    } else {
      GuardrailResult.GuardrailDenied(s"This message has been blocked by the 'characters count' guardrail !").vfuture
    }
  }
}

class WordsCountGuardrail extends Guardrail {

  override def isBefore: Boolean = true

  override def isAfter: Boolean = true

  override def manyMessages: Boolean = false

  override def pass(messages: Seq[ChatMessage], config: JsObject, provider: AiProvider, chatClient: ChatClient, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[GuardrailResult] = {
    val message = messages.head
    val min = config.select("min").asOpt[Long].getOrElse(0L)
    val max = config.select("max").asOpt[Long].getOrElse(Long.MaxValue)
    val count = message.content.split("\\s+").length
    val pass = count >= min && count <= max
    if (pass) {
      GuardrailResult.GuardrailPass.vfuture
    } else {
      GuardrailResult.GuardrailDenied(s"This message has been blocked by the 'words count' guardrail !").vfuture
    }
  }
}

class SentencesCountGuardrail extends Guardrail {

  override def isBefore: Boolean = true

  override def isAfter: Boolean = true

  override def manyMessages: Boolean = false

  override def pass(messages: Seq[ChatMessage], config: JsObject, provider: AiProvider, chatClient: ChatClient, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[GuardrailResult] = {
    val message = messages.head
    val min = config.select("min").asOpt[Long].getOrElse(0L)
    val max = config.select("max").asOpt[Long].getOrElse(Long.MaxValue)
    val count = message.content.split("[.!?]").length
    val pass = count >= min && count <= max
    if (pass) {
      GuardrailResult.GuardrailPass.vfuture
    } else {
      GuardrailResult.GuardrailDenied(s"This message has been blocked by the 'sentences count' guardrail !").vfuture
    }
  }
}

