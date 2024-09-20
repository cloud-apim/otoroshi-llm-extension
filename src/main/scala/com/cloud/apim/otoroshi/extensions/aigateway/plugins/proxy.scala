package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.plugins.{AiPluginRefConfig, AiPluginsKeys, PromptValidatorConfig}
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatMessage, ChatPrompt}
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.ReplaceAllWith
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json._
import play.api.mvc.{Result, Results}

import scala.concurrent.{ExecutionContext, Future}

object AiLlmProxy {
  def applyTemplate(template: String, context: JsValue): Seq[JsObject] = {
    val expressionReplacer = ReplaceAllWith("\\@\\{([^}]*)\\}")
    val result = expressionReplacer.replaceOn(template) { path =>
      val lookup = if (path.startsWith("$")) {
        context.atPath(path).asOpt[JsValue].getOrElse(JsNull)
      } else {
        context.at(path).asOpt[JsValue].getOrElse(JsNull)
      }
      lookup match {
        case JsString(str) => str
        case JsNumber(str) => str.toString()
        case JsBoolean(str) => str.toString()
        case JsObject(str) => str.toString()
        case JsArray(str) => str.toString()
        case JsNull => "null"
        case _ => "undefined"
      }
    }
    result.parseJson.asOpt[Seq[JsObject]].getOrElse(Seq.empty)
  }
}

class AiLlmProxy extends NgBackendCall {

  override def name: String = "Cloud APIM - LLM Proxy"
  override def description: Option[String] = "Delegates call to a LLM provider".some

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
      ext.logger.info("the 'LLM Proxy' plugin is available !")
    }
    ().vfuture
  }

  def call(jsonBody: JsValue, config: AiPluginRefConfig, ctx: NgbBackendCallContext)(implicit ec: ExecutionContext, env: Env): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    // TODO: add analytics
    env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(config.ref).flatMap(_.getChatClient())) match {
      case None => Left(NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "provider not found")))).vfuture // TODO: rewrite error
      case Some(client) => {
        val stream = ctx.request.queryParam("stream").contains("true") || ctx.request.header("x-stream").contains("true") || jsonBody.select("stream").asOpt[Boolean].contains(true)
        val requestMessages = ctx.attrs.get(AiPluginsKeys.PromptTemplateKey) match {
          case None => jsonBody.select("messages").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
          case Some(template) => {
            val context = Json.obj(
              "request" -> (ctx.json.asObject ++ Json.obj(
                "route"              -> ctx.route.json,
                "request"            -> ctx.request.json
              )),
              "body" -> jsonBody,
              "properties" -> Json.obj(), // TODO: from conf
            )
            AiLlmProxy.applyTemplate(template, context)
          }
        }
        if (validate(requestMessages, ctx)) {
          val (preContextMessages, postContextMessages) = ctx.attrs.get(AiPluginsKeys.PromptContextKey).getOrElse((Seq.empty, Seq.empty))
          val messages = (preContextMessages ++ requestMessages ++ postContextMessages).map { obj =>
            val role = obj.select("role").asOpt[String].getOrElse("user")
            val content = obj.select("content").asOpt[String].getOrElse("")
            ChatMessage(role, content)
          }
          if (stream) {
            client.tryStream(ChatPrompt(messages), ctx.attrs).map {
              case Left(err) => Left(NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(err)))
              case Right(source) => {
                val finalSource = source.map(_.eventSource)
                Right(BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok.chunked(finalSource).as("text/event-stream")), None))
              }
            }
          } else {
            client.call(ChatPrompt(messages), ctx.attrs).map {
              case Left(err) => Left(NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(err)))
              case Right(response) => Right(BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok(response.json)), None))
            }
          }
        } else {
          Left(NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(Json.obj("error" -> "bad_request", "error_description" -> "invalid request")))).vfuture // TODO: rewrite error
        }
      }
    }
  }

  def validate(messages: Seq[JsObject], ctx: NgbBackendCallContext): Boolean = {
    ctx.attrs.get(AiPluginsKeys.PromptValidatorsKey) match {
      case None => true
      case Some(seq) => {
        val contents = messages.map(_.select("content").asOpt[String].getOrElse(""))
        contents.forall(content => seq.forall(_.validate(content)))
      }
    }
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    if (ctx.request.hasBody) {
      ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
        val jsonBody = bodyRaw.utf8String.parseJson
        val config = ctx.cachedConfig(internalName)(AiPluginRefConfig.format).getOrElse(AiPluginRefConfig.default)
        call(jsonBody, config, ctx)
      }
    } else {
      val config = ctx.cachedConfig(internalName)(AiPluginRefConfig.format).getOrElse(AiPluginRefConfig.default)
      call(Json.obj(), config, ctx)
    }
  }
}

class AiPromptValidator extends NgAccessValidator {

  override def name: String = "Cloud APIM - LLM Proxy - prompt validator"
  override def description: Option[String] = "Validate request before hitting a LLM provider. MUST be used in addition to the LLM proxy plugin".some
  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))

  override def steps: Seq[NgStep] = Seq(NgStep.ValidateAccess)
  override def defaultConfigObject: Option[NgPluginConfig] = Some(PromptValidatorConfig.default)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = Seq("allow", "deny")
  override def configSchema: Option[JsObject] = Some(Json.obj(
    "allow" -> Json.obj(
      "type" -> "array",
      "array" -> true,
      "format" -> JsNull,
      "suffix" -> "regex",
      "label" -> "Allow regex",
    ),
    "deny" -> Json.obj(
      "type" -> "array",
      "array" -> true,
      "format" -> JsNull,
      "suffix" -> "regex",
      "label" -> "Deny regex"
    )
  ))

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'LLM Proxy - prompt validator' plugin is available !")
    }
    ().vfuture
  }

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config = ctx.cachedConfig(internalName)(PromptValidatorConfig.format).getOrElse(PromptValidatorConfig.default)
    ctx.attrs.get(AiPluginsKeys.PromptValidatorsKey) match {
      case None => ctx.attrs.put(AiPluginsKeys.PromptValidatorsKey -> Seq(config))
      case Some(seq) => ctx.attrs.put(AiPluginsKeys.PromptValidatorsKey -> (seq ++ Seq(config)))
    }
    NgAccess.NgAllowed.vfuture
  }
}

class AiPromptTemplate extends NgRequestTransformer {

  override def name: String = "Cloud APIM - LLM Proxy - prompt template"
  override def description: Option[String] = "Create a LLM request based on a template. MUST be used in addition to the LLM proxy plugin".some
  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))

  override def steps: Seq[NgStep] = Seq(NgStep.TransformRequest)
  override def defaultConfigObject: Option[NgPluginConfig] = Some(AiPluginRefConfig.default)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = AiPluginRefConfig.configFlow
  override def configSchema: Option[JsObject] = AiPluginRefConfig.configSchema("Prompt Template", "prompt-templates")

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'LLM Proxy - prompt template' plugin is available !")
    }
    ().vfuture
  }

  override def transformRequest(ctx: NgTransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val config = ctx.cachedConfig(internalName)(AiPluginRefConfig.format).getOrElse(AiPluginRefConfig.default)
    env.adminExtensions.extension[AiExtension].flatMap(_.states.template(config.ref)) match {
      case None => Left(Results.InternalServerError(Json.obj("error" -> "template not found"))).vfuture // TODO: rewrite error
      case Some(template) => {
        ctx.attrs.put(AiPluginsKeys.PromptTemplateKey -> template.template)
        ctx.otoroshiRequest.rightf
      }
    }
  }
}

class AiPromptContext extends NgRequestTransformer {

  override def name: String = "Cloud APIM - LLM Proxy - prompt context"

  override def description: Option[String] = "Enhance a LLM request based with a context. MUST be used in addition to the LLM proxy plugin".some

  override def core: Boolean = false

  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand

  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))

  override def steps: Seq[NgStep] = Seq(NgStep.TransformRequest)

  override def defaultConfigObject: Option[NgPluginConfig] = Some(AiPluginRefConfig.default)

  override def noJsForm: Boolean = true

  override def configFlow: Seq[String] = AiPluginRefConfig.configFlow

  override def configSchema: Option[JsObject] = AiPluginRefConfig.configSchema("Prompt Context", "prompt-contexts")

  override def transformsError: Boolean = false

  override def transformsResponse: Boolean = false

  override def transformsRequest: Boolean = true

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'LLM Proxy - prompt context' plugin is available !")
    }
    ().vfuture
  }

  override def transformRequest(ctx: NgTransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val config = ctx.cachedConfig(internalName)(AiPluginRefConfig.format).getOrElse(AiPluginRefConfig.default)
    env.adminExtensions.extension[AiExtension].flatMap(_.states.context(config.ref)) match {
      case None => Left(Results.InternalServerError(Json.obj("error" -> "context not found"))).vfuture // TODO: rewrite error
      case Some(context) => {
        ctx.attrs.get(AiPluginsKeys.PromptContextKey) match {
          case None => ctx.attrs.put(AiPluginsKeys.PromptContextKey -> (context.preMessages, context.postMessages))
          case Some((pre, post)) => ctx.attrs.put(AiPluginsKeys.PromptContextKey -> ((pre ++ context.preMessages), (post ++ context.postMessages)))
        }
        ctx.otoroshiRequest.rightf
      }
    }
  }
}