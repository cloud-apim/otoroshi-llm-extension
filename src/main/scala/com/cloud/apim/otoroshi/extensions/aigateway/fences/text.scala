package com.cloud.apim.otoroshi.extensions.aigateway.fences

import com.cloud.apim.otoroshi.extensions.aigateway.{ChatClient, ChatMessage}
import com.cloud.apim.otoroshi.extensions.aigateway.decorators.{Fence, FenceResult}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.JsObject

import scala.concurrent.{ExecutionContext, Future}

class ContainsFence extends Fence {

  override def isBefore: Boolean = true

  override def isAfter: Boolean = true

  override def manyMessages: Boolean = false

  override def pass(messages: Seq[ChatMessage], config: JsObject, provider: AiProvider, chatClient: ChatClient, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[FenceResult] = {
    val message = messages.head
    val operation = config.select("operation").asOpt[String].getOrElse("contains_all")
    val values = config.select("values").asOpt[Seq[String]].getOrElse(Seq.empty)
    operation match {
      case "contains_all" if values.forall(value => message.content.contains(value)) => FenceResult.FencePass.vfuture
      case "contains_none" if values.forall(value => !message.content.contains(value)) => FenceResult.FencePass.vfuture
      case "contains_any" if values.exists(value => message.content.contains(value)) => FenceResult.FencePass.vfuture
      case _ => FenceResult.FenceDenied(s"This message has been blocked by the 'contains' fence !").vfuture
    }
  }
}

class CharactersCountFence extends Fence {

  override def isBefore: Boolean = true

  override def isAfter: Boolean = true

  override def manyMessages: Boolean = false

  override def pass(messages: Seq[ChatMessage], config: JsObject, provider: AiProvider, chatClient: ChatClient, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[FenceResult] = {
    val message = messages.head
    val min = config.select("min").asOpt[Long].getOrElse(0L)
    val max = config.select("max").asOpt[Long].getOrElse(Long.MaxValue)
    val count = message.content.length
    val pass = count >= min && count <= max
    if (pass) {
      FenceResult.FencePass.vfuture
    } else {
      FenceResult.FenceDenied(s"This message has been blocked by the 'characters count' fence !").vfuture
    }
  }
}

class WordsCountFence extends Fence {

  override def isBefore: Boolean = true

  override def isAfter: Boolean = true

  override def manyMessages: Boolean = false

  override def pass(messages: Seq[ChatMessage], config: JsObject, provider: AiProvider, chatClient: ChatClient, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[FenceResult] = {
    val message = messages.head
    val min = config.select("min").asOpt[Long].getOrElse(0L)
    val max = config.select("max").asOpt[Long].getOrElse(Long.MaxValue)
    val count = message.content.split("\\s+").length
    val pass = count >= min && count <= max
    if (pass) {
      FenceResult.FencePass.vfuture
    } else {
      FenceResult.FenceDenied(s"This message has been blocked by the 'words count' fence !").vfuture
    }
  }
}

class SentencesCountFence extends Fence {

  override def isBefore: Boolean = true

  override def isAfter: Boolean = true

  override def manyMessages: Boolean = false

  override def pass(messages: Seq[ChatMessage], config: JsObject, provider: AiProvider, chatClient: ChatClient, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[FenceResult] = {
    val message = messages.head
    val min = config.select("min").asOpt[Long].getOrElse(0L)
    val max = config.select("max").asOpt[Long].getOrElse(Long.MaxValue)
    val count = message.content.split("[.!?]").length
    val pass = count >= min && count <= max
    if (pass) {
      FenceResult.FencePass.vfuture
    } else {
      FenceResult.FenceDenied(s"This message has been blocked by the 'sentences count' fence !").vfuture
    }
  }
}

