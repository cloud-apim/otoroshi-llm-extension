package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import akka.stream.scaladsl.Source
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatClient, ChatPrompt, ChatResponse, ChatResponseChunk}
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.{JsValue, Json}

import scala.concurrent.{ExecutionContext, Future}

object ChatClientWithProviderFallback {
  def applyIfPossible(tuple: (AiProvider, ChatClient, Env)): ChatClient = {
    if (tuple._1.providerFallback.isDefined) {
      new ChatClientWithProviderFallback(tuple._1, tuple._2)
    } else {
      tuple._2
    }
  }
}

class ChatClientWithProviderFallback(originalProvider: AiProvider, val chatClient: ChatClient) extends DecoratorChatClient {

  override def call(originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    chatClient.call(originalPrompt, attrs, originalBody).flatMap {
      case Left(err) => {
        env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(originalProvider.providerFallback.get)) match {
          case None => err.leftf
          case Some(provider) => provider.getChatClient() match {
            case None => err.leftf
            case Some(fallbackClient) => fallbackClient.call(originalPrompt, attrs, originalBody)
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
            case Some(fallbackClient) => fallbackClient.call(originalPrompt, attrs, originalBody)
          }
        }
      }
    }
  }

  override def stream(originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    chatClient.stream(originalPrompt, attrs, originalBody).flatMap {
      case Left(err) => {
        env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(originalProvider.providerFallback.get)) match {
          case None => err.leftf
          case Some(provider) => provider.getChatClient() match {
            case None => err.leftf
            case Some(fallbackClient) => fallbackClient.stream(originalPrompt, attrs, originalBody)
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
            case Some(fallbackClient) => fallbackClient.stream(originalPrompt, attrs, originalBody)
          }
        }
      }
    }
  }

  override def completion(originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    chatClient.completion(originalPrompt, attrs, originalBody).flatMap {
      case Left(err) => {
        env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(originalProvider.providerFallback.get)) match {
          case None => err.leftf
          case Some(provider) => provider.getChatClient() match {
            case None => err.leftf
            case Some(fallbackClient) => fallbackClient.completion(originalPrompt, attrs, originalBody)
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
            case Some(fallbackClient) => fallbackClient.completion(originalPrompt, attrs, originalBody)
          }
        }
      }
    }
  }

  override def completionStream(originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    chatClient.completionStream(originalPrompt, attrs, originalBody).flatMap {
      case Left(err) => {
        env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(originalProvider.providerFallback.get)) match {
          case None => err.leftf
          case Some(provider) => provider.getChatClient() match {
            case None => err.leftf
            case Some(fallbackClient) => fallbackClient.completionStream(originalPrompt, attrs, originalBody)
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
            case Some(fallbackClient) => fallbackClient.completionStream(originalPrompt, attrs, originalBody)
          }
        }
      }
    }
  }
}