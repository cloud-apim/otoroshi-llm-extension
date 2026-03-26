package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.plugins.{AiPluginRefsConfig, AiPluginsKeys}
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatMessage, ChatPrompt, InputChatMessage}
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits._
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

  def call(_jsonBody: JsValue, config: AiPluginRefsConfig, ctx: NgbBackendCallContext)(implicit ec: ExecutionContext, env: Env): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val jsonBody: JsValue = AiPluginRefsConfig.extractProviderFromModelInBody(_jsonBody, config)
    val provider: Option[AiProvider] = jsonBody.select("provider").asOpt[String].filter(v => config.refs.contains(v)).flatMap { r =>
      env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(r))
    }.orElse(
      config.refs.headOption.flatMap { r =>
        env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(r))
      }
    )
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
                .withHeaders(response.metadata.cacheHeaders.toSeq: _*)), None))
            }
          }
        } else {
          Left(NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(Json.obj("error" -> "bad_request", "error_description" -> "invalid request")))).vfuture
        }
      }
    }
  }

  def handleRequest(config: AiPluginRefsConfig, ctx: NgbBackendCallContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
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

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
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

  def call(_jsonBody: JsValue, config: AiPluginRefsConfig, ctx: NgbBackendCallContext)(implicit ec: ExecutionContext, env: Env): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
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
        val echo = jsonBody.select("echo").asOptBoolean.getOrElse(false)
        val suffix = jsonBody.select("suffix").asOptString

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

object OpenAiResponsesProxy {

  def convertInputToMessages(jsonBody: JsValue): Seq[JsObject] = {
    val instructions = jsonBody.select("instructions").asOptString
    val inputMessages: Seq[JsObject] = jsonBody.select("input").asOptString match {
      case Some(text) => Seq(Json.obj("role" -> "user", "content" -> text))
      case None => jsonBody.select("input").asOpt[Seq[JsValue]].getOrElse(Seq.empty).flatMap { item =>
        val itemType = item.select("type").asOptString
        itemType match {
          case Some("message") =>
            val role = item.select("role").asOptString.getOrElse("user")
            val content: JsValue = item.select("content").asOptString match {
              case Some(text) => text.json
              case None => item.select("content").asOpt[Seq[JsObject]] match {
                case Some(parts) =>
                  val convertedParts: Seq[JsValue] = parts.map { p =>
                    p.select("type").asOptString match {
                      case Some("input_text") =>
                        val text: String = p.select("text").asOptString.getOrElse("")
                        Json.obj("type" -> "text", "text" -> text)
                      case Some("input_image") =>
                        val url: String = p.select("image_url").asOptString.orElse(p.select("url").asOptString).getOrElse("")
                        Json.obj("type" -> "image_url", "image_url" -> Json.obj("url" -> url))
                      case Some("input_audio") =>
                        val data = p.select("data").asOptString.getOrElse("")
                        val format = p.select("format").asOptString.getOrElse("wav")
                        Json.obj("type" -> "input_audio", "input_audio" -> Json.obj("data" -> data, "format" -> format))
                      case _ => p
                    }
                  }
                  if (convertedParts.size == 1 && convertedParts.head.select("type").asOptString.contains("text")) {
                    convertedParts.head.select("text").asOptString.getOrElse("").json
                  } else {
                    JsArray(convertedParts)
                  }
                case None => "".json
              }
            }
            Seq(Json.obj("role" -> role, "content" -> content))
          case Some("function_call_output") =>
            val callId = item.select("call_id").asOptString.getOrElse("")
            val output = item.select("output").asOptString.getOrElse("")
            Seq(Json.obj("role" -> "tool", "tool_call_id" -> callId, "content" -> output))
          case _ =>
            val role = item.select("role").asOptString
            if (role.isDefined) {
              val content: JsValue = item.select("content").asOpt[JsValue].getOrElse(JsNull)
              Seq(Json.obj("role" -> role.get, "content" -> content))
            } else {
              Seq.empty
            }
        }
      }
    }
    val systemMessage = instructions.map(i => Seq(Json.obj("role" -> "system", "content" -> i))).getOrElse(Seq.empty)
    systemMessage ++ inputMessages
  }

  def call(_jsonBody: JsValue, config: AiPluginRefsConfig, ctx: NgbBackendCallContext)(implicit ec: ExecutionContext, env: Env): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val jsonBody: JsValue = AiPluginRefsConfig.extractProviderFromModelInBody(_jsonBody, config)
    val provider: Option[AiProvider] = jsonBody.select("provider").asOpt[String].filter(v => config.refs.contains(v)).flatMap { r =>
      env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(r))
    }.orElse(
      config.refs.headOption.flatMap { r =>
        env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(r))
      }
    )
    provider.flatMap(_.getChatClient()) match {
      case None => Left(NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "provider not found")))).vfuture
      case Some(client) => {
        val stream = ctx.request.queryParam("stream").contains("true") || ctx.request.header("x-stream").contains("true") || jsonBody.select("stream").asOpt[Boolean].contains(true)
        val requestMessages = convertInputToMessages(jsonBody)

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
                val messageId = s"msg_${IdGenerator.token(32)}"
                val modelStr = client.computeModel(jsonBody).getOrElse("none")
                val fullText = new StringBuilder()
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

                val messageItem = Json.obj(
                  "type" -> "message",
                  "id" -> messageId,
                  "status" -> "in_progress",
                  "role" -> "assistant",
                  "content" -> JsArray()
                )

                val contentPart = Json.obj(
                  "type" -> "output_text",
                  "text" -> "",
                  "annotations" -> JsArray()
                )

                val headerEvents = Source(List(
                  sseEvent("response.created", Json.obj("type" -> "response.created", "response" -> incompleteResponse)),
                  sseEvent("response.in_progress", Json.obj("type" -> "response.in_progress", "response" -> incompleteResponse)),
                  sseEvent("response.output_item.added", Json.obj("type" -> "response.output_item.added", "output_index" -> 0, "item" -> messageItem)),
                  sseEvent("response.content_part.added", Json.obj("type" -> "response.content_part.added", "item_id" -> messageId, "output_index" -> 0, "content_index" -> 0, "part" -> contentPart)),
                ))

                val contentEvents = source.map { chunk =>
                  val text = chunk.choices.headOption.flatMap(_.delta.content).getOrElse("")
                  fullText.append(text)
                  sseEvent("response.output_text.delta", Json.obj(
                    "type" -> "response.output_text.delta",
                    "item_id" -> messageId,
                    "output_index" -> 0,
                    "content_index" -> 0,
                    "delta" -> text
                  ))
                }

                val footerEvents = Source.lazily(() => {
                  val text = fullText.toString()
                  val completedContentPart = Json.obj(
                    "type" -> "output_text",
                    "text" -> text,
                    "annotations" -> JsArray()
                  )
                  val completedMessage = Json.obj(
                    "type" -> "message",
                    "id" -> messageId,
                    "status" -> "completed",
                    "role" -> "assistant",
                    "content" -> JsArray(Seq(completedContentPart))
                  )
                  val completedResponse = Json.obj(
                    "id" -> responseId,
                    "object" -> "response",
                    "created_at" -> createdAt,
                    "model" -> modelStr,
                    "status" -> "completed",
                    "output" -> JsArray(Seq(completedMessage)),
                    "usage" -> Json.obj(
                      "input_tokens" -> 0,
                      "output_tokens" -> 0,
                      "total_tokens" -> 0
                    )
                  )
                  Source(List(
                    sseEvent("response.output_text.done", Json.obj(
                      "type" -> "response.output_text.done",
                      "item_id" -> messageId,
                      "output_index" -> 0,
                      "content_index" -> 0,
                      "text" -> text
                    )),
                    sseEvent("response.content_part.done", Json.obj(
                      "type" -> "response.content_part.done",
                      "item_id" -> messageId,
                      "output_index" -> 0,
                      "content_index" -> 0,
                      "part" -> completedContentPart
                    )),
                    sseEvent("response.output_item.done", Json.obj(
                      "type" -> "response.output_item.done",
                      "output_index" -> 0,
                      "item" -> completedMessage
                    )),
                    sseEvent("response.completed", Json.obj(
                      "type" -> "response.completed",
                      "response" -> completedResponse
                    )),
                  ))
                })

                val finalSource = headerEvents.concat(contentEvents).concat(footerEvents)
                Right(BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok.chunked(finalSource).as("text/event-stream")), None))
              }
            }
          } else {
            client.response(ChatPrompt(messages), ctx.attrs, jsonBody).map {
              case Left(err) => Left(NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(err)))
              case Right(response) => Right(BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok(response.openaiResponseJson(client.computeModel(jsonBody).getOrElse("none"), env))
                .withHeaders(response.metadata.cacheHeaders.toSeq: _*)), None))
            }
          }
        } else {
          Left(NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(Json.obj("error" -> "bad_request", "error_description" -> "invalid request")))).vfuture
        }
      }
    }
  }

  def handleRequest(config: AiPluginRefsConfig, ctx: NgbBackendCallContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
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

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(AiPluginRefsConfig.format).getOrElse(AiPluginRefsConfig.default)
    OpenAiResponsesProxy.handleRequest(config, ctx)
  }
}