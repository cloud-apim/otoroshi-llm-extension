package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.plugins.{AiPluginRefsConfig, AiPluginsKeys}
import com.cloud.apim.otoroshi.extensions.aigateway.providers.AzureOpenAiApi
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatClient, ChatPrompt, ChatResponseChunk, InputChatMessage, Reasoning, ReasoningAccumulator}
import otoroshi.env.Env
import otoroshi.next.plugins.api.*
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.*
import play.api.mvc.Results

import java.io.File
import java.nio.file.Files
import scala.concurrent.{ExecutionContext, Future}

/**
 * Writes a streamed answer as the events of the Anthropic messages api: one content block after the other, the
 * reasoning (`thinking`, `redacted_thinking`), the text, then each tool call (`tool_use`), each closed before the
 * next one opens. The chunks come from one stream only: the state needs no synchronization.
 */
class AnthropicStreamWriter {

  private var current: Option[String] = None
  private var index: Int = -1
  // the reasoning of the current `thinking` block, signed at its end when it is not Anthropic's
  private var thinking = new ReasoningAccumulator()
  private var thinkingIndex: Option[Int] = None
  private var thinkingSigned = false
  private var toolIndex: Long = -1L
  private var stopReason: String = "end_turn"
  private var outputTokens: Long = 0L

  private def event(typ: String, data: JsObject): ByteString = s"event: ${typ}\ndata: ${(Json.obj("type" -> typ) ++ data).stringify}\n\n".byteString

  private def start(block: JsObject): Seq[ByteString] = {
    index = index + 1
    current = block.select("type").asOptString
    Seq(event("content_block_start", Json.obj("index" -> index, "content_block" -> block)))
  }

  private def delta(delta: JsObject): ByteString = event("content_block_delta", Json.obj("index" -> index, "delta" -> delta))

  private def close(): Seq[ByteString] = {
    val signature: Seq[ByteString] = if (current.contains("thinking") && !thinkingSigned) {
      thinking.result.headOption.map(d => delta(Json.obj("type" -> "signature_delta", "signature" -> Reasoning.foreignSignature(d)))).toSeq
    } else Seq.empty
    val stop = current.map(_ => event("content_block_stop", Json.obj("index" -> index))).toSeq
    current = None
    thinking = new ReasoningAccumulator()
    thinkingIndex = None
    thinkingSigned = false
    signature ++ stop
  }

  private def ensure(blockType: String, block: => JsObject): Seq[ByteString] = {
    if (current.contains(blockType)) Seq.empty else close() ++ start(block)
  }

  private def reasoningEvents(detail: JsObject): Seq[ByteString] = {
    val anthropic = Reasoning.formatOf(detail) == Reasoning.Formats.Anthropic
    if (Reasoning.isEncrypted(detail)) {
      val data = if (anthropic) Reasoning.dataOf(detail).getOrElse("") else Reasoning.wrap(Seq(detail))
      close() ++ start(Json.obj("type" -> "redacted_thinking", "data" -> data)) ++ close()
    } else {
      // a detail of another index is another block of reasoning
      val detailIndex = detail.select("index").asOpt[Int]
      val opening = if (current.contains("thinking") && thinkingIndex != detailIndex) close() ++ start(Json.obj("type" -> "thinking", "thinking" -> ""))
        else ensure("thinking", Json.obj("type" -> "thinking", "thinking" -> ""))
      thinkingIndex = detailIndex
      thinking.add(Seq(detail))
      val text = Reasoning.readableOf(detail).filter(_.nonEmpty).map(t => delta(Json.obj("type" -> "thinking_delta", "thinking" -> t))).toSeq
      val signature = Reasoning.signatureOf(detail).filter(_ => anthropic).map { s =>
        thinkingSigned = true
        delta(Json.obj("type" -> "signature_delta", "signature" -> s))
      }.toSeq
      opening ++ text ++ signature
    }
  }

  def events(chunk: ChatResponseChunk): Seq[ByteString] = {
    chunk.usage.foreach(u => outputTokens = u.generationTokens)
    chunk.choices.headOption.toSeq.flatMap { choice =>
      choice.finishReason.foreach {
        case "tool_calls" | "tool_use" => stopReason = "tool_use"
        case "length" | "max_tokens" => stopReason = "max_tokens"
        case _ =>
      }
      val reasoning = choice.delta.reasoningDetailsOrText.flatMap(reasoningEvents)
      val text = choice.delta.content.filter(_.nonEmpty).toSeq.flatMap { text =>
        ensure("text", Json.obj("type" -> "text", "text" -> "")) :+ delta(Json.obj("type" -> "text_delta", "text" -> text))
      }
      val tools = choice.delta.tool_calls.flatMap { tc =>
        // the pieces of a tool call share its index, a new index is the next tool call
        val opening = if (!current.contains("tool_use") || toolIndex != tc.index) {
          stopReason = "tool_use"
          val block = close() ++ start(Json.obj("type" -> "tool_use", "id" -> tc.id.getOrElse(s"toolu_${IdGenerator.token(24)}"), "name" -> tc.function.nameOpt.getOrElse(""), "input" -> Json.obj()))
          toolIndex = tc.index
          block
        } else Seq.empty
        val args = Option(tc.function.arguments).filter(_.nonEmpty).map(a => delta(Json.obj("type" -> "input_json_delta", "partial_json" -> a))).toSeq
        opening ++ args
      }
      reasoning ++ text ++ tools
    }
  }

  /** the events ending the message, once the answer is over */
  def end(): Seq[ByteString] = {
    // a message has at least one block
    val empty = if (index < 0) start(Json.obj("type" -> "text", "text" -> "")) else Seq.empty
    empty ++ close() ++ Seq(
      event("message_delta", Json.obj("delta" -> Json.obj("stop_reason" -> stopReason, "stop_sequence" -> JsNull), "usage" -> Json.obj("output_tokens" -> outputTokens))),
      event("message_stop", Json.obj()),
    )
  }
}

object AnthropicCompatProxy {

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





  // The name the limit of an answer is sent under. `max_tokens` is the one about every api knows, and the clients
  // translate it when theirs wants another: the OpenAI one renames it for OpenAI. Azure OpenAI is sent
  // `max_completion_tokens`, the only name its reasoning models take, when the api version of the provider knows it
  def maxTokensParam(provider: Option[AiProvider]): String = provider match {
    case Some(azure) if azure.provider.toLowerCase == "azure-openai" && AzureOpenAiApi.knowsMaxCompletionTokens(AzureOpenAiApi.versionOf(azure.connection)) => "max_completion_tokens"
    case _ => "max_tokens"
  }

  // what a `tool_result` block says, as the text of a `tool` message: its content is a string or a list of blocks
  private def toolResultContent(block: JsObject): String = block.select("content").asOpt[JsValue] match {
    case Some(JsString(text)) => text
    case Some(JsArray(parts)) => parts.flatMap(_.select("text").asOptString).mkString("\n")
    case Some(obj: JsObject) => obj.stringify
    case _ => ""
  }

  /**
   * The messages of a request in the Anthropic format, in the OpenAI format the gateway works with, whatever the
   * provider: `tool_use` blocks become the `tool_calls` of the assistant message, along with its text and its
   * reasoning (`thinking` blocks), and `tool_result` blocks become `tool` messages. The clients write them back the
   * way their provider takes them, Anthropic included.
   */
  def messagesToOpenAi(messages: Seq[JsObject]): Seq[JsObject] = messages.flatMap { message =>
    val role = message.select("role").asOptString.getOrElse("user")
    val blocks = message.select("content").asOpt[Seq[JsObject]]
    def ofType(typ: String): Seq[JsObject] = blocks.getOrElse(Seq.empty).filter(_.select("type").asOptString.contains(typ))
    if (role == "user" && ofType("tool_result").nonEmpty) {
      val results = ofType("tool_result").map { block =>
        Json.obj("role" -> "tool", "tool_call_id" -> block.select("tool_use_id").asString, "content" -> toolResultContent(block))
      }
      // what the user says along with the results comes after them
      val others = blocks.getOrElse(Seq.empty).filterNot(_.select("type").asOptString.contains("tool_result"))
      results ++ (if (others.isEmpty) Seq.empty else Seq(Json.obj("role" -> "user", "content" -> JsArray(others))))
    } else if (role == "assistant" && ofType("tool_use").nonEmpty) {
      val text = ofType("text").flatMap(_.select("text").asOptString).mkString("")
      val reasoning = Reasoning.fromContentBlocks(blocks.getOrElse(Seq.empty))
      Seq(Json.obj(
        "role" -> "assistant",
        "tool_calls" -> ofType("tool_use").map { tc =>
          val input: String = tc.select("input").asOpt[JsObject].map(_.stringify).orElse(tc.select("input").asOptString).getOrElse("{}")
          Json.obj(
            "id" -> tc.select("id").asString,
            "type" -> "function",
            "function" -> Json.obj(
              "name" -> tc.select("name").asString,
              "arguments" -> input
            )
          )
        }
      ).applyOnIf(text.nonEmpty)(_ ++ Json.obj("content" -> text))
        .applyOnIf(reasoning.nonEmpty)(_ ++ Json.obj("reasoning_details" -> JsArray(Reasoning.indexed(reasoning)))))
    } else {
      // the `thinking` blocks of the other messages are read as their reasoning (see InputChatMessage)
      Seq(message)
    }
  }

  private def fixBody(_jsonBody: JsObject, client: ChatClient, provider: Option[AiProvider], reqId: Long): JsObject = {
    val withMessages = _jsonBody.select("messages").asOpt[Seq[JsObject]] match {
      case None => _jsonBody
      case Some(messages) => _jsonBody ++ Json.obj("messages" -> JsArray(messagesToOpenAi(messages)))
    }
    if (client.isAnthropic) {
      withMessages - "system"
    } else if (client.isCohere) {
      withMessages - "system"
    } else {
      val withTools = withMessages.select("tools").asOpt[Seq[JsObject]] match {
        case None => withMessages
        case Some(origTools) => {
          val newTools = origTools.map { origTool =>
            val name = origTool.select("name").asString
            val description = origTool.select("description").asOptString.getOrElse("")
            val strict = origTool.select("strict").asOptBoolean.getOrElse(false)
            val input_schema = origTool.select("input_schema").asObject - "$schema"
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
          withMessages ++ Json.obj(
            "tools" -> newTools
          )
        }
      }
      var additionalProperties = Json.obj()
      _jsonBody.select("output_config").asOpt[JsObject].foreach { output =>
        val ftype = output.select("format").select("type").asOptString
        if (ftype.contains("json_schema")) {
          val schema = output.select("format").select("schema").asObject
          additionalProperties = additionalProperties ++ Json.obj(
            "response_format" -> Json.obj(
              "type" -> "json_schema",
              "json_schema" -> Json.obj(
                "name" -> "output_schema",
                "schema" -> schema
              )
            )
          )
        }
      }
      _jsonBody.select("max_tokens").asOpt[Long].foreach {
        case tokens if tokens > 1 =>  additionalProperties = additionalProperties ++ Json.obj(maxTokensParam(provider) -> tokens)
        case _ =>
      }
      _jsonBody.select("thinking").asOpt[JsObject].foreach { thinking =>
        val enabled = thinking.select("type").asOptString.contains("enabled")
        val budget = thinking.select("budget_tokens").asOptLong
        if (enabled && budget.isDefined) {
          val thinkingBudget = budget.get
          val effort = _jsonBody.select("max_tokens").asOptLong.map { maxBudget =>
            val ratio = thinkingBudget.toDouble / maxBudget.toDouble
            ratio match {
              case r if r <= 0.25 => "minimal"
              case r if r <= 0.50 => "low"
              case r if r <= 0.75 => "medium"
              case _ => "high"
            }
          }.getOrElse("medium")
          additionalProperties = additionalProperties ++ Json.obj("reasoning_effort" -> effort)
        }
      }
      withTools - "system" - "max_tokens" - "thinking" - "output_config" - "context_management" ++ additionalProperties
    }
  }

  def call(_jsonBody: JsValue, config: AiPluginRefsConfig, ctx: NgbBackendCallContext)(using ec: ExecutionContext, env: Env): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val reqId = System.currentTimeMillis
    val jsonBody: JsValue = AiPluginRefsConfig.extractProviderFromModelInBody(_jsonBody, config)
      .applyOnWithOpt(ctx.config.select("override_model").asOptString.filter(_.trim.nonEmpty)) {
        case (body, om) => body.asObject ++ Json.obj("model" -> om)
      }
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
        val finalJsonBody = fixBody(jsonBody.asObject, client, provider, reqId)

        val systemMessage: Option[JsObject] = jsonBody.select("system").asOpt[String].map { sys =>
          Json.obj("role" -> "system", "content" -> sys)
        }.orElse(jsonBody.select("system").asOpt[Seq[JsObject]].map { sysBlocks =>
          val content = sysBlocks.filter { b =>
            b.select("type").asOptString match {
              case Some("text") => true
              case _ => false
            }
          }.map(_.select("text").asString).mkString("\n\n")
          Json.obj("role" -> "system", "content" -> content)
        })

        val requestMessages = ctx.attrs.get(AiPluginsKeys.PromptTemplateKey) match {
          case None => {
            val msgs = finalJsonBody.select("messages").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
            systemMessage.map(s => s +: msgs).getOrElse(msgs)
          }
          case Some(template) => {
            val context = Json.obj(
              "request" -> (ctx.json.asObject ++ Json.obj(
                "route" -> ctx.route.json,
                "request" -> ctx.request.json
              )),
              "body" -> finalJsonBody,
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
            val messageId = s"msg_${IdGenerator.token(32)}"
            val model = client.computeModel(finalJsonBody).getOrElse("none")
            client.tryStream(ChatPrompt(messages), ctx.attrs, finalJsonBody).map {
              case Left(err) =>
                Left(NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(Json.obj(
                  "type" -> "error",
                  "error" -> Json.obj(
                    "type" -> "invalid_request_error",
                    "message" -> err.stringify
                  )
                ))))
              case Right(source) => {
                val writer = new AnthropicStreamWriter()
                val finalSource = Source.single(anthropicMessageStart(model, messageId))
                  .concat(source.mapConcat(chunk => writer.events(chunk).toList))
                  // materialized once the answer is over
                  .concatLazy(Source.lazySource(() => Source(writer.end().toList)))
                Right(BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok.chunked(finalSource).as("text/event-stream")), None))
              }
            }
          } else {
            client.call(ChatPrompt(messages), ctx.attrs, finalJsonBody).map {
              case Left(err) =>
                Left(NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(Json.obj(
                  "type" -> "error",
                  "error" -> Json.obj(
                    "type" -> "invalid_request_error",
                    "message" -> err.stringify
                  )
                ))))
              case Right(response) =>
                Right(BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok(response.anthropicJson(client.computeModel(jsonBody).getOrElse("none"), env))
                  .withHeaders(response.metadata.cacheHeaders.toSeq*)), None))
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

  def handleRequest(config: AiPluginRefsConfig, ctx: NgbBackendCallContext)(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    if (ctx.request.hasBody) {
      ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
        try {
          val jsonBody = bodyRaw.utf8String.parseJson
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
      call(Json.obj(), config, ctx)
    }
  }
}

/*
export ANTHROPIC_AUTH_TOKEN=otoroshi
export ANTHROPIC_API_KEY=""
export ANTHROPIC_BASE_URL=http://anthropic.oto.tools:9999
claude --model gpt-5.2
 */
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
  override def configFlow: Seq[String] = AiPluginRefsConfig.configFlow ++ Seq("override_model")
  override def configSchema: Option[JsObject] = AiPluginRefsConfig.configSchema("LLM provider", "providers").map(o => o ++ Json.obj(
    "override_model" -> Json.obj(
      "type" -> "string",
      "label" -> s"Override model",
    )
  ))

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'LLM Anthropic messages Proxy' plugin is available !")
    }
    ().vfuture
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(AiPluginRefsConfig.format).getOrElse(AiPluginRefsConfig.default)
    AnthropicCompatProxy.handleRequest(config, ctx)
  }
}
