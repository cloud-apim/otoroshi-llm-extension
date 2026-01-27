package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.plugins.{AiPluginRefsConfig, AiPluginsKeys}
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatClient, ChatPrompt, InputChatMessage}
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json._
import play.api.mvc.Results

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.{ExecutionContext, Future}

case class AnthropicStreamResponseState(
  textStarted: AtomicBoolean = new AtomicBoolean(false),
  textDone: AtomicBoolean = new AtomicBoolean(false),
  toolCallsStarted: AtomicBoolean = new AtomicBoolean(false),
  toolCallsDone: AtomicBoolean = new AtomicBoolean(false),
)

class AnthropicCompatProxy extends NgBackendCall {

  override def name: String = "Cloud APIM - LLM Anthropic messages Proxy"
  override def description: Option[String] = "Delegates call to a LLM provider but with an Anthropic like API".some

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
      ext.logger.info("the 'LLM Anthropic messages Proxy' plugin is available !")
    }
    ().vfuture
  }

  private def anthropicMessageStart(model: String, messageId: String): ByteString = {
    val json = Json.obj(
      "type" -> "message_start",
      "message" -> Json.obj(
        "id" -> messageId,
        "type" -> "message",
        "role" -> "assistant",
        "model" -> model,
        "content" -> JsArray(),
        "stop_reason" -> JsNull,
        "stop_sequence" -> JsNull,
        "usage" -> Json.obj(
          "input_tokens" -> 0,
          "output_tokens" -> 0
        )
      )
    )
    s"event: message_start\ndata: ${json.stringify}\n\n".byteString
  }

  private def anthropicContentBlockStart(index: Int): ByteString = {
    val json = Json.obj(
      "type" -> "content_block_start",
      "index" -> index,
      "content_block" -> Json.obj(
        "type" -> "text",
        "text" -> ""
      )
    )
    s"event: content_block_start\ndata: ${json.stringify}\n\n".byteString
  }

  private def anthropicContentBlockStop(index: Int): ByteString = {
    val json = Json.obj(
      "type" -> "content_block_stop",
      "index" -> index
    )
    s"event: content_block_stop\ndata: ${json.stringify}\n\n".byteString
  }

  private def anthropicMessageDelta(stopReason: String, outputTokens: Long): ByteString = {
    val json = Json.obj(
      "type" -> "message_delta",
      "delta" -> Json.obj(
        "stop_reason" -> stopReason,
        "stop_sequence" -> JsNull
      ),
      "usage" -> Json.obj(
        "output_tokens" -> outputTokens
      )
    )
    s"event: message_delta\ndata: ${json.stringify}\n\n".byteString
  }

  private def anthropicMessageStop(): ByteString = {
    val json = Json.obj("type" -> "message_stop")
    s"event: message_stop\ndata: ${json.stringify}\n\n".byteString
  }

  private def fixBody(_jsonBody: JsObject, client: ChatClient, reqId: Long): JsObject = {
    if (client.isAnthropic) {
      _jsonBody - "system"
    } else if (client.isCohere) {
      _jsonBody - "system"
    } else {
      Files.writeString(new File(s"anthropic-${reqId}-raw-input.json").toPath, Json.prettyPrint(_jsonBody))
      val withTools = _jsonBody.select("tools").asOpt[Seq[JsObject]] match {
        case None => _jsonBody
        case Some(origTools) => {
          val newTools = origTools.map { origTool =>
            val name = origTool.select("name").asString
            val description = origTool.select("description").asOptString.getOrElse("")
            val strict = origTool.select("strict").asOptBoolean.getOrElse(false)
            val input_schema = origTool.select("input_schema").asObject
            Json.obj(
              "type" -> "function",
              "function" -> Json.obj(
                "name" -> name,
                "description" -> description,
                "strict" -> strict,
                "parameters" -> input_schema
              )
            )
          }
          _jsonBody ++ Json.obj(
            "tools" -> newTools
          )
        }
      }
      var additionalProperties = Json.obj()
      _jsonBody.select("max_tokens").asOpt[Long].foreach {
        case tokens if tokens > 1 =>  additionalProperties = additionalProperties ++ Json.obj("max_completion_tokens" -> tokens)
        case _ =>
      }
      _jsonBody.select("thinking").asOpt[JsObject].foreach { thinking =>
        val efforts = Seq("minimal", "low", "medium", "high", "xhigh")
        val effort = "medium"
        additionalProperties = additionalProperties ++ Json.obj("reasoning_effort" -> effort)
      }
      additionalProperties = additionalProperties ++ Json.obj("model" -> "gpt-5.2")
      withTools - "system" - "max_tokens" - "thinking" - "model" ++ additionalProperties
    }
  }

  def call(_jsonBody: JsValue, config: AiPluginRefsConfig, ctx: NgbBackendCallContext)(implicit ec: ExecutionContext, env: Env): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val reqId = System.currentTimeMillis
    val jsonBody: JsValue = AiPluginRefsConfig.extractProviderFromModelInBody(_jsonBody, config)
    val provider: Option[AiProvider] = jsonBody.select("provider").asOpt[String].filter(v => config.refs.contains(v)).flatMap { r =>
      env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(r))
    }.orElse(
      config.refs.headOption.flatMap { r =>
        env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(r))
      }
    )
    provider.flatMap(_.getChatClient()) match {
      case None => Left(NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj(
        "type" -> "error",
        "error" -> Json.obj(
          "type" -> "invalid_request_error",
          "message" -> "provider not found"
        )
      )))).vfuture
      case Some(client) => {
        val stream = ctx.request.queryParam("stream").contains("true") || ctx.request.header("x-stream").contains("true") || jsonBody.select("stream").asOpt[Boolean].contains(true)

        // Extract system message from Anthropic format (top-level "system" field)
        val systemMessage: Option[JsObject] = jsonBody.select("system").asOpt[String].map { sys =>
          Json.obj("role" -> "system", "content" -> sys)
        }.orElse(jsonBody.select("system").asOpt[Seq[JsObject]].map { sysBlocks =>
          Json.obj("role" -> "system", "content" -> JsArray(sysBlocks))
        })

        val requestMessages = ctx.attrs.get(AiPluginsKeys.PromptTemplateKey) match {
          case None => {
            val msgs = jsonBody.select("messages").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
            systemMessage.map(s => s +: msgs).getOrElse(msgs)
          }
          case Some(template) => {
            val context = Json.obj(
              "request" -> (ctx.json.asObject ++ Json.obj(
                "route" -> ctx.route.json,
                "request" -> ctx.request.json
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
          val finalJsonBody = fixBody(jsonBody.asObject, client, reqId)
          if (stream) {
            val messageId = s"msg_${IdGenerator.token(32)}"
            val model = client.computeModel(finalJsonBody).getOrElse("none")
            client.tryStream(ChatPrompt(messages), ctx.attrs, finalJsonBody).map {
              case Left(err) =>
                println("error: " + err)
                Files.writeString(new File(s"""anthropic-${reqId}-stream-error.json""").toPath, Json.prettyPrint(Json.obj("request" -> finalJsonBody, "response" -> err)).replaceAll("\\[ \\(", "[\n  {"))
                Left(NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(Json.obj(
                  "type" -> "error",
                  "error" -> Json.obj(
                    "type" -> "invalid_request_error",
                    "message" -> err.stringify
                  )
                ))))
              case Right(source) => {
                var chunks = Seq.empty[ByteString]
                val state = AnthropicStreamResponseState()
                val finalSource = Source.single(anthropicMessageStart(model, messageId))
                  .concat(Source.single(anthropicContentBlockStart(0)))
                  .concat(source.flatMapConcat(_.anthropicContentBlockDeltaEventSource(env, state)))
                  .concat(Source.single(anthropicContentBlockStop(0)))
                  .concat(Source.single(anthropicMessageDelta(if (state.toolCallsStarted.get()) "tool_use" else "end_turn", 0)))
                  .concat(Source.single(anthropicMessageStop()))
                  .map { bs =>
                    chunks = chunks :+ bs
                    bs
                  }
                  .alsoTo(Sink.onComplete {
                    case _ => Files.writeString(
                      new File(s"anthropic-${reqId}-stream-request.json").toPath,
                      Json.prettyPrint(Json.obj("request" -> finalJsonBody)) + "\n\n" + chunks.map(_.utf8String.json).mkString("\n"))
                  })
                Right(BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok.chunked(finalSource).as("text/event-stream")), None))
              }
            }
          } else {
            client.call(ChatPrompt(messages), ctx.attrs, finalJsonBody).map {
              case Left(err) =>
                Files.writeString(new File(s"""anthropic-${reqId}-block-error.json""").toPath, Json.prettyPrint(Json.obj("request" -> finalJsonBody, "response" -> err)).replaceAll("\\[ \\(", "[\n  {"))
                Left(NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(Json.obj(
                  "type" -> "error",
                  "error" -> Json.obj(
                    "type" -> "invalid_request_error",
                    "message" -> err.stringify
                  )
                ))))
              case Right(response) =>
                Files.writeString(new File(s"""anthropic-${reqId}-block-request.json""").toPath, Json.prettyPrint(Json.obj("request" -> finalJsonBody, "response" -> response.json(env))).replaceAll("\\[ \\(", "[\n  {"))
                Right(BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok(response.anthropicJson(client.computeModel(jsonBody).getOrElse("none"), env))
                  .withHeaders(response.metadata.cacheHeaders.toSeq: _*)), None))
            }
          }
        } else {
          Left(NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(Json.obj(
            "type" -> "error",
            "error" -> Json.obj(
              "type" -> "invalid_request_error",
              "message" -> "invalid request"
            )
          )))).vfuture
        }
      }
    }
  }

  def validate(messages: Seq[JsObject], ctx: NgbBackendCallContext): Boolean = {
    ctx.attrs.get(AiPluginsKeys.PromptValidatorsKey) match {
      case None => true
      case Some(seq) => {
        val contents = messages.flatMap { msg =>
          msg.select("content").asOpt[String].orElse(
            msg.select("content").asOpt[Seq[JsObject]].map { blocks =>
              blocks.flatMap(_.select("text").asOpt[String]).mkString(" ")
            }
          )
        }
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
          case e: Throwable =>
            e.printStackTrace()
            NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(Json.obj(
              "type" -> "error",
              "error" -> Json.obj(
                "type" -> "invalid_request_error",
                "message" -> e.getMessage
              )
            ))).leftf
        }
      }
    } else {
      val config = ctx.cachedConfig(internalName)(AiPluginRefsConfig.format).getOrElse(AiPluginRefsConfig.default)
      call(Json.obj(), config, ctx)
    }
  }
}
