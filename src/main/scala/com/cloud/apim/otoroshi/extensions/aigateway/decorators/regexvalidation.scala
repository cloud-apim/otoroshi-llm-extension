package com.cloud.apim.otoroshi.extensions.aigateway.decorators
/*
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatClient, ChatPrompt, ChatResponse}
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits._
import otoroshi.utils.{RegexPool, TypedMap}
import play.api.libs.json.{JsValue, Json}

import scala.concurrent.{ExecutionContext, Future}

object ChatClientWithRegexValidation {
  def applyIfPossible(tuple: (AiProvider, ChatClient)): ChatClient = {
    if (tuple._1.regexValidation.allow.nonEmpty || tuple._1.regexValidation.deny.nonEmpty) {
      new ChatClientWithRegexValidation(tuple._1, tuple._2)
    } else {
      tuple._2
    }
  }
}

class ChatClientWithRegexValidation(originalProvider: AiProvider, chatClient: ChatClient) extends ChatClient {

  private val allow = originalProvider.regexValidation.allow
  private val deny = originalProvider.regexValidation.deny

  private def validate(content: String): Boolean = {
    val allowed = if (allow.isEmpty) true else allow.exists(al => RegexPool.regex(al).matches(content))
    val denied = if (deny.isEmpty) false else deny.exists(dn => RegexPool.regex(dn).matches(content))
    !denied && allowed
  }

  override def model: Option[String] = chatClient.model

  override def call(originalPrompt: ChatPrompt, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {

    def pass(): Future[Either[JsValue, ChatResponse]] = chatClient.call(originalPrompt, attrs)

    def fail(): Future[Either[JsValue, ChatResponse]] = Left(Json.obj("error" -> "bad_request", "error_description" -> s"request content did not pass regex validation")).vfuture

    val contents = originalPrompt.messages.map(_.content)
    if (!contents.forall(content => validate(content))) {
      fail()
    } else {
      pass()
    }
  }
}
*/