package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiProvider, ToolsSelection}
import com.cloud.apim.otoroshi.extensions.aigateway.plugins.{AiPluginRefsConfig, AiPluginsKeys}
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatMessage, ChatPrompt, InputChatMessage, OpenAiResponsesBodyConverter, ResponsesStreamWriter}
import otoroshi.env.Env
import otoroshi.next.plugins.api.*
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import otoroshi.security.IdGenerator
import play.api.libs.json.{JsArray, JsNull, JsObject, JsValue, Json}
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}

object OpenAiCompatProxy {
  def validate(messages: Seq[JsObject], ctx: NgbBackendCallContext): Boolean = {
    ctx.attrs.get(AiPluginsKeys.PromptValidatorsKey) match {
      case None => true
      case Some(seq) => {
        val contents = messages.map(_.select("content").asOpt[String].getOrElse(""))
        contents.forall(content => seq.forall(_.validate(content)))
      }
    }
  }

  def call(_jsonBody: JsValue, config: AiPluginRefsConfig, ctx: NgbBackendCallContext)(using ec: ExecutionContext, env: Env): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    // the tools this call asks for, when it names them: the body goes on without the field
    val (jsonBody, allowedTools): (JsValue, Option[Seq[String]]) = ToolsSelection.extract(AiPluginRefsConfig.extractProviderFromModelInBody(_jsonBody, config))
    val provider: Option[AiProvider] = jsonBody.select("provider").asOpt[String].filter(v => config.refs.contains(v)).flatMap { r =>
      env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(r))
    }.orElse(
      config.refs.headOption.flatMap { r =>
        env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(r))
      }
    ).map(p => ToolsSelection.narrow(p, allowedTools))
    provider.flatMap(_.getChatClient()) match {
      case None => Left(NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "provider not found")))).vfuture
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
              "properties" -> Json.obj(),
            )
            AiLlmProxy.applyTemplate(template, context)
          }
        }

        if (validate(requestMessages, ctx)) {
          val (preContextMessages, postContextMessages) = ctx.attrs.get(AiPluginsKeys.PromptContextKey).getOrElse((Seq.empty, Seq.empty))
          val messages = (preContextMessages ++ requestMessages ++ postContextMessages).map { obj =>
            InputChatMessage.fromJson(obj)
          }
          if (stream) {
            client.tryStream(ChatPrompt(messages), ctx.attrs, jsonBody).map {
              case Left(err) => Left(NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(err)))
              case Right(source) => {
                val finalSource = source
                  .map(_.openaiEventSource(env))
                  .concat(Source.single("data: [DONE]\n\n".byteString))
                Right(BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok.chunked(finalSource).as("text/event-stream")), None))
              }
            }
          } else {
            client.call(ChatPrompt(messages), ctx.attrs, jsonBody).map {
              case Left(err) => Left(NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(err)))
              case Right(response) => Right(BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok(response.openaiJson(client.computeModel(jsonBody).getOrElse("none"), env))
                .withHeaders(response.metadata.cacheHeaders.toSeq*)), None))
            }
          }
        } else {
          Left(NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(Json.obj("error" -> "bad_request", "error_description" -> "invalid request")))).vfuture
        }
      }
    }
  }

  def handleRequest(config: AiPluginRefsConfig, ctx: NgbBackendCallContext)(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    if (ctx.request.hasBody) {
      ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
        try {
          val jsonBody = bodyRaw.utf8String.parseJson
          call(jsonBody, config, ctx)
        } catch {
          case e: Throwable =>
            e.printStackTrace()
            NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(Json.obj("error" -> "bad_request", "error_details" -> e.getMessage))).leftf
        }
      }
    } else {
      call(Json.obj(), config, ctx)
    }
  }
}

class OpenAiCompatProxy extends NgBackendCall {

  override def name: String = "Cloud APIM - LLM OpenAI chat/completions Proxy"
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
      ext.logger.info("the 'LLM OpenAI chat/completions Proxy' plugin is available !")
    }
    ().vfuture
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(AiPluginRefsConfig.format).getOrElse(AiPluginRefsConfig.default)
    OpenAiCompatProxy.handleRequest(config, ctx)
  }
}

class OpenAiCompletionProxy extends NgBackendCall {

  override def name: String = "Cloud APIM - LLM OpenAI completions Proxy"
  override def description: Option[String] = "Delegates completion calls to a LLM provider but with an OpenAI like API".some

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
      ext.logger.info("the 'LLM OpenAI completions Proxy' plugin is available !")
    }
    ().vfuture
  }

  def call(_jsonBody: JsValue, config: AiPluginRefsConfig, ctx: NgbBackendCallContext)(using ec: ExecutionContext, env: Env): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    // the tools this call asks for, when it names them: the body goes on without the field
    val (jsonBody, allowedTools): (JsValue, Option[Seq[String]]) = ToolsSelection.extract(AiPluginRefsConfig.extractProviderFromModelInBody(_jsonBody, config))
    val provider: Option[AiProvider] = jsonBody.select("provider").asOpt[String].filter(v => config.refs.contains(v)).flatMap { r =>
      env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(r))
    }.orElse(
      config.refs.headOption.flatMap { r =>
        env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(r))
      }
    ).map(p => ToolsSelection.narrow(p, allowedTools))
    provider.flatMap(_.getChatClient()) match {
      case None => Left(NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "provider not found")))).vfuture // TODO: rewrite error
      case Some(client) => {
        val stream = ctx.request.queryParam("stream").contains("true") || ctx.request.header("x-stream").contains("true") || jsonBody.select("stream").asOpt[Boolean].contains(true)
        val echo = jsonBody.select("echo").asOptBoolean.getOrElse(false)

        val requestMessages: Seq[JsObject] = ctx.attrs.get(AiPluginsKeys.PromptTemplateKey) match {
          case None => Seq(Json.obj("role" -> "user", "content" -> jsonBody.select("prompt").asString))
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
          val messages = requestMessages.map { obj =>
            val role = obj.select("role").asOpt[String].getOrElse("user")
            val content = obj.select("content").asOpt[String].getOrElse("")
            val prefix = obj.select("prefix").asOptBoolean
            ChatMessage.input(role, content, prefix, obj)
          }
          if (stream) {
            client.tryCompletionStream(ChatPrompt(messages), ctx.attrs, jsonBody).map {
              case Left(err) => Left(NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(err)))
              case Right(source) => {
                val finalSource = source.map(_.openaiCompletionEventSource(env)).concat(Source.single("data: [DONE]\n\n".byteString))
                Right(BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok.chunked(finalSource).as("text/event-stream")), None))
              }
            }
          } else {
            client.tryCompletion(ChatPrompt(messages), ctx.attrs, jsonBody).map {
              case Left(err) => Left(NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(err)))
              case Right(response) => Right(BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok(response.openaiCompletionJson(client.computeModel(jsonBody).getOrElse("none"), echo, messages.head.content, env))
                .withHeaders(response.metadata.cacheHeaders.toSeq*)), None))
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

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
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

object OpenAiResponsesProxy {

  def call(_jsb: JsValue, config: AiPluginRefsConfig, ctx: NgbBackendCallContext)(using ec: ExecutionContext, env: Env): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    // the tools this call asks for, when it names them: the body goes on without the field
    val (_jsonBody, allowedTools): (JsValue, Option[Seq[String]]) = ToolsSelection.extract(AiPluginRefsConfig.extractProviderFromModelInBody(_jsb, config))
    val provider: Option[AiProvider] = _jsonBody.select("provider").asOpt[String].filter(v => config.refs.contains(v)).flatMap { r =>
      env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(r))
    }.orElse(
      config.refs.headOption.flatMap { r =>
        env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(r))
      }
    ).map(p => ToolsSelection.narrow(p, allowedTools))
    provider.flatMap(_.getChatClient()) match {
      case None => Left(NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "provider not found")))).vfuture
      case Some(client) => {
        val stream = ctx.request.queryParam("stream").contains("true") || ctx.request.header("x-stream").contains("true") || _jsonBody.select("stream").asOpt[Boolean].contains(true)
        // `input` becomes the prompt (the source of truth for the messages, see ChatClient.response),
        // the rest of the body stays a raw /responses body: the client converts it if it has to
        val requestMessages = OpenAiResponsesBodyConverter.inputToMessages(_jsonBody)
        val jsonBody = _jsonBody.asObject - "input"

        if (OpenAiCompatProxy.validate(requestMessages, ctx)) {
          val (preContextMessages, postContextMessages) = ctx.attrs.get(AiPluginsKeys.PromptContextKey).getOrElse((Seq.empty, Seq.empty))
          val messages = (preContextMessages ++ requestMessages ++ postContextMessages).map { obj =>
            InputChatMessage.fromJson(obj)
          }

          if (stream) {
            client.tryResponseStream(ChatPrompt(messages), ctx.attrs, jsonBody).map {
              case Left(err) => Left(NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(err)))
              case Right(source) => {
                val responseId = s"resp_${IdGenerator.token(32)}"
                val modelStr = client.computeModel(jsonBody).getOrElse("none")
                val createdAt = System.currentTimeMillis() / 1000

                def sseEvent(eventType: String, data: JsValue): ByteString = {
                  s"event: ${eventType}\ndata: ${data.stringify}\n\n".byteString
                }

                val incompleteResponse = Json.obj(
                  "id" -> responseId,
                  "object" -> "response",
                  "created_at" -> createdAt,
                  "model" -> modelStr,
                  "status" -> "in_progress",
                  "output" -> JsArray(),
                  "usage" -> JsNull
                )

                val headerEvents = Source(List(
                  sseEvent("response.created", Json.obj("type" -> "response.created", "response" -> incompleteResponse)),
                  sseEvent("response.in_progress", Json.obj("type" -> "response.in_progress", "response" -> incompleteResponse)),
                ))

                // the reasoning, the text and the tool calls as output items, in the order the model produced them
                val writer = new ResponsesStreamWriter(sequenced = false, logprobs = false, () => 0, prefix => s"${prefix}_${IdGenerator.token(32)}")
                val contentEvents = source.mapConcat(chunk => writer.events(chunk).toList)

                // materialized by `concatLazy` only once the content events are done, which is what
                // makes the accumulated text, tool calls and usage available here
                val footerEvents = Source.lazySource(() => {
                  val closing = writer.end()
                  val completedResponse = Json.obj(
                    "id" -> responseId,
                    "object" -> "response",
                    "created_at" -> createdAt,
                    "model" -> modelStr,
                    "status" -> "completed",
                    "output" -> JsArray(writer.output),
                    "usage" -> writer.acc.usageJson,
                  )
                  Source(closing.toList ++ List(
                    sseEvent("response.completed", Json.obj(
                      "type" -> "response.completed",
                      "response" -> completedResponse
                    )),
                  ))
                })

                val finalSource = headerEvents.concat(contentEvents).concatLazy(footerEvents)
                Right(BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok.chunked(finalSource).as("text/event-stream")), None))
              }
            }
          } else {
            client.tryResponse(ChatPrompt(messages), ctx.attrs, jsonBody).map {
              case Left(err) => Left(NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(err)))
              case Right(response) => Right(BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok(response.openaiResponseJson(client.computeModel(jsonBody).getOrElse("none"), env))
                .withHeaders(response.metadata.cacheHeaders.toSeq*)), None))
            }
          }
        } else {
          Left(NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(Json.obj("error" -> "bad_request", "error_description" -> "invalid request")))).vfuture
        }
      }
    }
  }

  def handleRequest(config: AiPluginRefsConfig, ctx: NgbBackendCallContext)(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    if (ctx.request.hasBody) {
      ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
        try {
          val jsonBody = bodyRaw.utf8String.parseJson
          call(jsonBody, config, ctx)
        } catch {
          case e: Throwable =>
            e.printStackTrace()
            NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(Json.obj("error" -> "bad_request", "error_details" -> e.getMessage))).leftf
        }
      }
    } else {
      call(Json.obj(), config, ctx)
    }
  }
}

class OpenAiResponsesProxy extends NgBackendCall {

  override def name: String = "Cloud APIM - LLM OpenAI Responses Proxy"
  override def description: Option[String] = "Delegates call to a LLM provider but with an OpenAI Responses API like interface".some

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
      ext.logger.info("the 'LLM OpenAI Responses Proxy' plugin is available !")
    }
    ().vfuture
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(AiPluginRefsConfig.format).getOrElse(AiPluginRefsConfig.default)
    OpenAiResponsesProxy.handleRequest(config, ctx)
  }
}