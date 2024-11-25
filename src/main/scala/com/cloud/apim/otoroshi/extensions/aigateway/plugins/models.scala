package com.cloud.apim.otoroshi.extensions.aigateway.plugins

import akka.stream.Materializer
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.{JsArray, JsObject, Json}
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}
import scala.util._

class OpenAiCompatModels extends NgBackendCall {

  override def name: String = "Cloud APIM - LLM OpenAI Compat. Models list"
  override def description: Option[String] = "Delegates call to a LLM provider to retrieve supported models".some
  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))
  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)
  override def useDelegates: Boolean = false
  override def defaultConfigObject: Option[NgPluginConfig] = Some(AiPluginRefConfig.default)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = AiPluginRefConfig.configFlow
  override def configSchema: Option[JsObject] = AiPluginRefConfig.configSchema("LLM provider", "providers")

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'LLM OpenAI Compat. Models list' plugin is available !")
    }
    ().vfuture
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(AiPluginRefConfig.format).getOrElse(AiPluginRefConfig.default)
    val provider = env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(config.ref))
    provider match {
      case None => Right(BackendCallResponse(NgPluginHttpResponse.fromResult(Results.InternalServerError(Json.obj("error" -> "provider not found"))), None)).vfuture
      case Some(provider) => {
        provider.getChatClient() match {
          case None => Right(BackendCallResponse(NgPluginHttpResponse.fromResult(Results.InternalServerError(Json.obj("error" -> "client not found"))), None)).vfuture
          case Some(client) => {
            client.listModels().map {
              case Left(err) => Right(BackendCallResponse(NgPluginHttpResponse.fromResult(Results.InternalServerError(Json.obj("error" -> "operation error", "details" -> err))), None))
              case Right(list) => {
                val now: Long = System.currentTimeMillis() / 1000
                Right(BackendCallResponse(NgPluginHttpResponse.fromResult(
                  Results.Ok(Json.obj(
                    "object" -> "list",
                    "data" -> JsArray(list.map(m => Json.obj(
                      "id" -> m,
                      "object" -> "model",
                      "created" -> now,
                      "owned_by" -> "openai"
                    )))
                  ))
                ), None))
              }
            }
          }
        }
      }
    }
  }
}


class LlmProviderModels extends NgBackendCall {

  override def name: String = "Cloud APIM - LLM Models list"
  override def description: Option[String] = "Delegates call to a LLM provider to retrieve supported models".some
  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))
  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)
  override def useDelegates: Boolean = false
  override def defaultConfigObject: Option[NgPluginConfig] = Some(AiPluginRefConfig.default)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = AiPluginRefConfig.configFlow
  override def configSchema: Option[JsObject] = AiPluginRefConfig.configSchema("LLM provider", "providers")

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'LLM Models list' plugin is available !")
    }
    ().vfuture
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(AiPluginRefConfig.format).getOrElse(AiPluginRefConfig.default)
    val provider = env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(config.ref))
    provider match {
      case None => Right(BackendCallResponse(NgPluginHttpResponse.fromResult(Results.InternalServerError(Json.obj("error" -> "provider not found"))), None)).vfuture
      case Some(provider) => {
        provider.getChatClient() match {
          case None => Right(BackendCallResponse(NgPluginHttpResponse.fromResult(Results.InternalServerError(Json.obj("error" -> "client not found"))), None)).vfuture
          case Some(client) => {
            client.listModels().map {
              case Left(err) => Right(BackendCallResponse(NgPluginHttpResponse.fromResult(Results.InternalServerError(Json.obj("error" -> "operation error", "details" -> err))), None))
              case Right(list) => {
                Right(BackendCallResponse(NgPluginHttpResponse.fromResult(
                  Results.Ok(Json.obj(
                    "models" -> JsArray(list.map(_.json))
                  ))
                ), None))
              }
            }
          }
        }
      }
    }
  }
}