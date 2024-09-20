package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatClient, ChatPrompt, ChatResponse}
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.{JsValue, Json}

import scala.concurrent.{ExecutionContext, Future}

object ChatClientWithProviderFallback {
  def applyIfPossible(tuple: (AiProvider, ChatClient)): ChatClient = {
    if (tuple._1.providerFallback.isDefined) {
      new ChatClientWithProviderFallback(tuple._1, tuple._2)
    } else {
      tuple._2
    }
  }
}

class ChatClientWithProviderFallback(originalProvider: AiProvider, chatClient: ChatClient) extends ChatClient {

  override def model: Option[String] = chatClient.model

  override def call(originalPrompt: ChatPrompt, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    chatClient.call(originalPrompt, attrs).flatMap {
      case Left(err) => {
        env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(originalProvider.providerFallback.get)) match {
          case None => err.leftf
          case Some(provider) => provider.getChatClient() match {
            case None => err.leftf
            case Some(fallbackClient) => fallbackClient.call(originalPrompt, attrs)
          }
        }
      }
      case Right(resp) => resp.rightf
    }.recoverWith {
      case t: Throwable => {
        env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(originalProvider.providerFallback.get)) match {
          case None => Json.obj("error" -> "fallback provider not found").leftf
          case Some(provider) => provider.getChatClient() match {
            case None => Json.obj("error" -> "fallback client not found").leftf
            case Some(fallbackClient) => fallbackClient.call(originalPrompt, attrs)
          }
        }
      }
    }
  }
}