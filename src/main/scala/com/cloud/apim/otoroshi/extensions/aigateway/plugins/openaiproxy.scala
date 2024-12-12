package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.plugins.{AiPluginRefsConfig, AiPluginsKeys}
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatMessage, ChatPrompt}
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}

class OpenAiCompatProxy extends NgBackendCall {

  override def name: String = "Cloud APIM - LLM OpenAI Compat. Proxy"
  override def description: Option[String] = "Delegates call to a LLM provider but with an OpenAI like API".some

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
      ext.logger.info("the 'LLM OpenAI Compat. Proxy' plugin is available !")
    }
    ().vfuture
  }

  def call(_jsonBody: JsValue, config: AiPluginRefsConfig, ctx: NgbBackendCallContext)(implicit ec: ExecutionContext, env: Env): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    // println(s"\n\nreq: ${ctx.request.json.prettify}\n\n")
    // println(s"\n\nctx: ${Json.obj(
    //   "user" -> ctx.user.map(_.json).getOrElse(JsNull).asValue,
    //   "apikey" -> ctx.apikey.map(_.json).getOrElse(JsNull).asValue,
    // ).prettify}\n\n")
    // println(s"\n\nbody: ${jsonBody.prettify}\n\n")
    val jsonBody: JsValue = AiPluginRefsConfig.extractProviderFromModelInBody(_jsonBody, config)
    val provider: Option[AiProvider] = jsonBody.select("provider").asOpt[String].filter(v => config.refs.contains(v)).flatMap { r =>
      env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(r))
    }.orElse(
      config.refs.headOption.flatMap { r =>
        env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(r))
      }
    )
    provider.flatMap(_.getChatClient()) match {
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
            client.tryStream(ChatPrompt(messages), ctx.attrs, jsonBody).map {
              case Left(err) => Left(NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(err)))
              case Right(source) => {
                val finalSource = source.map(_.openaiEventSource).concat(Source.single("data: [DONE]\n\n".byteString))
                Right(BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok.chunked(finalSource).as("text/event-stream")), None))
              }
            }
          } else {
            client.call(ChatPrompt(messages), ctx.attrs, jsonBody).map {
              case Left(err) => Left(NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(err)))
              case Right(response) => Right(BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok(response.openaiJson(client.model.getOrElse("none")))
                .withHeaders(response.metadata.cacheHeaders.toSeq: _*)), None))
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
        try {
          val jsonBody = bodyRaw.utf8String.parseJson
          val config = ctx.cachedConfig(internalName)(AiPluginRefsConfig.format).getOrElse(AiPluginRefsConfig.default)
          call(jsonBody, config, ctx)
        } catch {
          case e: Throwable => NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(Json.obj("error" -> "bad_request", "error_details" -> e.getMessage))).leftf
        }
      }
    } else {
      val config = ctx.cachedConfig(internalName)(AiPluginRefsConfig.format).getOrElse(AiPluginRefsConfig.default)
      call(Json.obj(), config, ctx)
    }
  }
}