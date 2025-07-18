package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
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
        case (provider, client) => client.listModels(ctx.request.queryParam("raw").contains("true")).map(e => (provider, e))
      }
      .collect {
        case (provider, Right(list)) => list.map { model =>
          val res = if (config.refs.size == 1) {
            model
          } else {
            if (model.contains("/")) s"${provider.slugName}###${model}" else s"${provider.slugName}/${model}"
          }
          (res, provider)
          //(model, combined)
        }
      }
      .flatMapConcat(list => Source(list))
      .runWith(Sink.seq)
      .map { list =>
        val now: Long = System.currentTimeMillis() / 1000
        Right(BackendCallResponse(NgPluginHttpResponse.fromResult(
          Results.Ok(Json.obj(
            "object" -> "list",
            "data" -> JsArray(list.map(m => Json.obj(
              "id" -> m._1,
              "object" -> "model",
              "created" -> now,
              "owned_by" -> m._2.slugName,
            )))
          ))
        ), None))
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
        case (provider, client) => client.listModels(ctx.request.queryParam("raw").contains("true")).map(e => (provider, e))
      }
      .collect {
        case (provider, Right(list)) => {
          list.map { model =>
            val combined = if (config.refs.size == 1) {
              model
            } else {
              if (model.contains("/")) s"${provider.slugName}###${model}" else s"${provider.slugName}/${model}"
            }
            Json.obj(
              "id" -> combined,
              "combined_id" -> combined,
              "provider_id" -> provider.slugName,
              "simple_id" -> model,
              "object" -> "model",
              "created" -> now,
              "owned_by" -> provider.computedName,
              "owned_by_with_model" -> s"${provider.computedName} / ${model}"
            )
          }
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
  override def defaultConfigObject: Option[NgPluginConfig] = Some(AiPluginRefsConfig.default)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = AiPluginRefsConfig.configFlow
  override def configSchema: Option[JsObject] = AiPluginRefsConfig.configSchema("LLM provider", "providers")

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'LLM Models list' plugin is available !")
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
        case (provider, client) => client.listModels(ctx.request.queryParam("raw").contains("true")).map(e => (provider, e))
      }
      .collect {
        case (provider, Right(list)) => list.map { model =>
          if (config.refs.size == 1) {
            model
          } else {
            if (model.contains("/")) s"${provider.slugName}###${model}" else s"${provider.slugName}/${model}"
          }
        }
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
        case (provider, client) => client.listModels(ctx.request.queryParam("raw").contains("true")).map(e => (provider, e))
      }
      .collect {
        case (provider, Right(list)) => list.map { model =>
          if (config.refs.size == 1) {
            model
          } else {
            if (model.contains("/")) s"${provider.slugName}###${model}" else s"${provider.slugName}/${model}"
          }
        }
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