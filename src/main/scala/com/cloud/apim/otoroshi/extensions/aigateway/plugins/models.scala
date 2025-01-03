package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.plugins._
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
  override def defaultConfigObject: Option[NgPluginConfig] = Some(AiPluginRefsConfig.default)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = AiPluginRefsConfig.configFlow
  override def configSchema: Option[JsObject] = AiPluginRefsConfig.configSchema("LLM provider", "providers")

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'LLM OpenAI Compat. Models list' plugin is available !")
    }
    ().vfuture
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(AiPluginRefsConfig.format).getOrElse(AiPluginRefsConfig.default)
    val provider: Option[AiProvider] = ctx.request.queryParam("provider").filter(v => config.refs.contains(v)).flatMap { r =>
      env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(r))
    }.orElse(
      config.refs.headOption.flatMap { r =>
        env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(r))
      }
    )
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
                      "pid" -> s"${provider.slugName}/${m}",
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

class OpenAiCompatProvidersWithModels extends NgBackendCall {

  override def name: String = "Cloud APIM - LLM OpenAI Compat. Provider with Models list"
  override def description: Option[String] = "Delegates call to LLM providers to retrieve supported models".some
  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))
  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)
  override def useDelegates: Boolean = false
  override def defaultConfigObject: Option[NgPluginConfig] = Some(AiPluginRefsConfig.default)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = AiPluginRefsConfig.configFlow
  override def configSchema: Option[JsObject] = AiPluginRefsConfig.configSchema("LLM provider", "providers")

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'LLM OpenAI Compat. Models list' plugin is available !")
    }
    ().vfuture
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(AiPluginRefsConfig.format).getOrElse(AiPluginRefsConfig.default)
    val ext = env.adminExtensions.extension[AiExtension].get
    val now: Long = System.currentTimeMillis() / 1000
    Source(config.refs.toList)
      .map(ref => ext.states.provider(ref))
      .collect {
        case Some(provider) => provider
      }
      .map(p => (p, p.getChatClient()))
      .collect {
        case (provider, Some(client)) => (provider, client)
      }
      .mapAsync(1) {
        case (provider, client) => client.listModels().map(e => (provider, e))
      }
      .collect {
        case (provider, Right(list)) => list.map { model =>
          Json.obj(
            "id" -> model,
            "combined_id" -> (if (model.contains("/")) s"${provider.slugName}###${model}" else s"${provider.slugName}/${model}"),
            "object" -> "model",
            "created" -> now,
            "owned_by" -> provider.computedName,
            "owned_by_with_model" -> s"${provider.computedName} / ${model}"
          )
        }
      }
      .flatMapConcat(list => Source(list))
      .runWith(Sink.seq)
      .map { list =>
        Right(BackendCallResponse(NgPluginHttpResponse.fromResult(
          Results.Ok(Json.obj(
            "object" -> "list",
            "data" -> JsArray(list)
          ))
        ), None))
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
                    "models" -> JsArray(list.map(_.json)),
                    "provider_models" -> JsArray(list.map(v => s"${provider.slugName}/${v}".json)),
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

class LlmProvidersWithModels extends NgBackendCall {

  override def name: String = "Cloud APIM - LLM Providers with Models list"
  override def description: Option[String] = "Delegates call to LLM providers to retrieve supported models".some
  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))
  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)
  override def useDelegates: Boolean = false
  override def defaultConfigObject: Option[NgPluginConfig] = Some(AiPluginRefsConfig.default)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = AiPluginRefsConfig.configFlow
  override def configSchema: Option[JsObject] = AiPluginRefsConfig.configSchema("LLM provider", "providers")

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'LLM Provider with Models list' plugin is available !")
    }
    ().vfuture
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(AiPluginRefsConfig.format).getOrElse(AiPluginRefsConfig.default)
    val ext = env.adminExtensions.extension[AiExtension].get
    Source(config.refs.toList)
      .map(ref => ext.states.provider(ref))
      .collect {
        case Some(provider) => provider
      }
      .map(p => (p, p.getChatClient()))
      .collect {
        case (provider, Some(client)) => (provider, client)
      }
      .mapAsync(1) {
        case (provider, client) => client.listModels().map(e => (provider, e))
      }
      .collect {
        case (provider, Right(list)) => list.map(model => (if (model.contains("/")) s"${provider.slugName}###${model}" else s"${provider.slugName}/${model}"))
      }
      .flatMapConcat(list => Source(list))
      .runWith(Sink.seq)
      .map { list =>
        Right(BackendCallResponse(NgPluginHttpResponse.fromResult(
          Results.Ok(Json.obj(
            "models" -> JsArray(list.map(_.json)),
          ))
        ), None))
      }
  }
}