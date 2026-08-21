package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.plugins.{AiPluginRefsConfig, AiPluginsKeys}
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatMessageContent, ChatPrompt, ChatResponse, InputChatMessage, OpenAiResponsesBodyConverter, ResponsesStreamAccumulator}
import otoroshi.env.Env
import otoroshi.next.plugins.api.*
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.*
import play.api.mvc.Results

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{ExecutionContext, Future}

object OpenResponseCompatProxy {

  def handleRequest(config: AiPluginRefsConfig, ctx: NgbBackendCallContext)(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    if (ctx.request.hasBody) {
      ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
        try {
          val jsonBody = bodyRaw.utf8String.parseJson
          new OpenResponseCompatProxy().call(jsonBody, config, ctx)
        } catch {
          case e: Throwable =>
            NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(Json.obj("error" -> "bad_request", "error_details" -> e.getMessage))).leftf
        }
      }
    } else {
      new OpenResponseCompatProxy().call(Json.obj(), config, ctx)
    }
  }
}

class OpenResponseCompatProxy extends NgBackendCall {

  override def name: String = "Cloud APIM - LLM OpenResponse Proxy"
  override def description: Option[String] = "Delegates call to a LLM provider but with an OpenResponse like API".some

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
      ext.logger.info("the 'LLM OpenResponse Proxy' plugin is available !")
    }
    ().vfuture
  }

  private def buildResponseJson(response: com.cloud.apim.otoroshi.extensions.aigateway.ChatResponse, model: String, request: JsObject, tools: Seq[JsObject], env: Env): JsValue = {
    // a provider that answered natively on /responses already returns a complete payload: pass it
    // through rather than rebuilding a lossy envelope (response id, reasoning items, annotations)
    if (response.isNativeResponsePayload) {
      return response.openaiResponseJson(model, env)
    }
    val msgId = s"msg_${IdGenerator.token(32)}"

    val textOutputs: Seq[JsObject] = response.generations.filter(!_.message.has_tool_calls).map { gen =>
      Json.obj(
        "type" -> "message",
        "id" -> msgId,
        "status" -> "completed",
        "role" -> "assistant",
        "content" -> Json.arr(
          Json.obj(
            "type" -> "output_text",
            "text" -> gen.message.content,
            "annotations" -> gen.message.annotationsOrEmpty,
            "logprobs" -> Json.arr()
          )
        )
      )
    }

    val toolCallOutputs: Seq[JsObject] = response.generations.filter(_.message.has_tool_calls).flatMap { gen =>
      gen.message.tool_calls.getOrElse(Seq.empty).map(_.asObject).map { tc =>
        val fnName = tc.select("function").select("name").asOptString.getOrElse(tc.select("name").asOptString.getOrElse(""))
        val arguments = tc.select("function").select("arguments").asOptString.getOrElse(tc.select("arguments").asOptString.getOrElse("{}"))
        val callId = tc.select("id").asOptString.getOrElse(s"call_${IdGenerator.token(24)}")
        Json.obj(
          "type" -> "function_call",
          "id" -> s"fc_${IdGenerator.token(24)}",
          "call_id" -> callId,
          "name" -> fnName,
          "arguments" -> arguments,
          "status" -> "in_progress",
          "created_by" -> model
        )
      }
    }

    val imageOutputs: Seq[JsObject] = response.generations.flatMap { gen =>
      gen.message.images.map { images =>
        images.map { image =>
          val img = image.asInstanceOf[ChatMessageContent.ImageContent]
          Json.obj(
            "type" -> "image_generation_call",
            "id" -> s"gen_${IdGenerator.token(32)}",
            "status" -> "completed",
            "result" -> (img match {
              case _ if img.data.isDefined => s"data:${img.mediaType};base64,${img.data.get.encodeBase64.utf8String}".json
              case _ if img.url.isDefined => img.url.get.json
              case _ => "".json
            })
          )
        }
      }.toSeq.flatten
    }

    // If we have tool calls, also emit the assistant message (without tool_calls) before the function_call items
    val output: Seq[JsObject] = if (toolCallOutputs.nonEmpty) {
      val message = response.headGeneration
      val assistantMsg = Json.obj(
        "type" -> "message",
        "id" -> msgId,
        "status" -> "completed",
        "role" -> "assistant",
        "content" -> Json.arr(
          Json.obj(
            "type" -> "output_text",
            "text" -> message.message.wholeTextContent,
            "annotations" -> message.message.annotationsOrEmpty,
            "logprobs" -> Json.arr()
          )
        )
      )
      Seq(assistantMsg) ++ imageOutputs ++ toolCallOutputs
    } else {
      textOutputs ++ imageOutputs
    }

    val usage = Json.obj(
      "input_tokens" -> response.metadata.usage.promptTokens,
      "output_tokens" -> response.metadata.usage.generationTokens,
      "total_tokens" -> response.metadata.usage.totalTokens,
      "input_tokens_details" -> Json.obj(
        "cached_tokens" -> 0
      ),
      "output_tokens_details" -> Json.obj(
        "reasoning_tokens" -> response.metadata.usage.reasoningTokens
      ),
    )

    val requestParallelToolCalls = request.select("parallel_tool_calls").asOptBoolean.getOrElse(false)
    val requestBackground = request.select("background").asOptBoolean.getOrElse(false)
    val requestStore = request.select("store").asOptBoolean.getOrElse(false)
    val requestTruncation = request.select("truncation").asOptString.getOrElse("disabled")
    val requestPreviousResponseId = request.select("previous_response_id").asOptString
    val requestTopP = request.select("top_p").asOpt[Double].getOrElse(1.0)
    val requestTemperature = request.select("temperature").asOpt[Double].getOrElse(1.0)
    val requestTopLogprobs = request.select("top_logprobs").asOpt[Double].getOrElse(0.0)
    val requestServiceTier = request.select("service_tier").asOptString.getOrElse("auto")
    val requestFrequencyPenalty = request.select("frequency_penalty").asOpt[Double].getOrElse(0.0)
    val requestInstructions = request.select("instructions").asOptString
    val max_tool_calls = request.select("max_tool_calls").asOptLong
    val max_output_tokens = request.select("max_output_tokens").asOptLong
    val safety_identifier = request.select("safety_identifier").asOptString
    val prompt_cache_key = request.select("prompt_cache_key").asOptString
    val tool_choice = request.select("tool_choice").asOptString.getOrElse("auto")
    val text = request.select("text").asOpt[JsObject].getOrElse(Json.obj(
      "format" -> Json.obj(
        "type" -> "text",
      )
    ))
    val reasoning = request.select("reasoning").asOpt[JsObject].getOrElse(Json.obj(
      "effort" -> "none",
      "summary" -> "auto"
    ))

    val respId = s"resp_${IdGenerator.token(32)}"
    val createdAt = System.currentTimeMillis() / 1000
    Json.obj(
      "id" -> respId,
      "object" -> "response",
      "created_at" -> createdAt,
      "completed_at" -> createdAt,
      "previous_response_id" -> requestPreviousResponseId,
      "top_p" -> requestTopP,
      "presence_penalty" -> 0,
      "top_logprobs" -> requestTopLogprobs,
      "temperature" -> requestTemperature,
      "status" -> "completed",
      "truncation" -> requestTruncation,
      "store" -> requestStore,
      "model" -> model,
      "output" -> JsArray(output),
      "usage" -> usage,
      "service_tier" -> requestServiceTier,
      "frequency_penalty" -> requestFrequencyPenalty,
      "instructions" -> requestInstructions,
      "background" -> requestBackground,
      "parallel_tool_calls" -> requestParallelToolCalls,
      "max_tool_calls" -> max_tool_calls,
      "max_output_tokens" -> max_output_tokens,
      "safety_identifier" -> safety_identifier,
      "prompt_cache_key" -> prompt_cache_key,
      "error" -> JsNull,
      "incomplete_details" -> JsNull,
      "tools" -> tools,
      "tool_choice" -> tool_choice,
      "metadata" -> Json.obj(),
      "reasoning" -> reasoning,
      "text" -> text,
    )
  }

  private def sseEvent(eventType: String, data: JsValue): ByteString = {
    s"event: $eventType\ndata: ${data.stringify}\n\n".byteString
  }

  def call(_jsonBody: JsValue, config: AiPluginRefsConfig, ctx: NgbBackendCallContext)(using ec: ExecutionContext, env: Env): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
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
        // `input` becomes the prompt (the source of truth for the messages, see ChatClient.response),
        // the rest of the body stays a raw /responses body: the client converts it if it has to, which
        // is what keeps guardrails, budgets, caches and mock providers on the path
        val requestMessages = OpenAiResponsesBodyConverter.inputToMessages(jsonBody)
        val openAiBody = jsonBody.asObject - "input"

        if (validate(requestMessages, ctx)) {
          val (preContextMessages, postContextMessages) = ctx.attrs.get(AiPluginsKeys.PromptContextKey).getOrElse((Seq.empty, Seq.empty))
          val messages = (preContextMessages ++ requestMessages ++ postContextMessages).map { obj =>
            InputChatMessage.fromJson(obj)
          }
          if (stream) {
            val msgId = s"msg_${IdGenerator.token(32)}"
            val model = client.computeModel(openAiBody).getOrElse("none")
            val tools = OpenAiResponsesBodyConverter.normalizeResponsesTools(jsonBody.select("tools").asOpt[Seq[JsObject]].getOrElse(Seq.empty))

            def eventWithResponse(typ: String, seqNum: Int): JsObject = {
              Json.obj(
                "type" -> typ,
                "sequence_number" -> seqNum,
                "response" -> buildResponseJson(ChatResponse.empty, model, jsonBody.asObject, tools, env)
              )
            }

            val msgItem = Json.obj(
              "type" -> "message",
              "id" -> msgId,
              "status" -> "in_progress",
              "role" -> "assistant",
              "content" -> Json.arr()
            )

            client.tryResponseStream(ChatPrompt(messages), ctx.attrs, openAiBody).map {
              case Left(err) => Left(NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(err)))
              case Right(source) => {
                val counter = new AtomicInteger(0)
                val headerEvents = Source(List(
                  sseEvent("response.created", eventWithResponse("response.created", counter.getAndIncrement())),
                  sseEvent("response.in_progress", eventWithResponse("response.in_progress", counter.getAndIncrement())),
                  sseEvent("response.output_item.added", Json.obj(
                    "type" -> "response.output_item.added",
                    "output_index" -> 0,
                    "sequence_number" -> counter.getAndIncrement(),
                    "item" -> msgItem
                  )),
                  sseEvent("response.content_part.added", Json.obj(
                    "type" -> "response.content_part.added",
                    "item_id" -> msgId,
                    "output_index" -> 0,
                    "content_index" -> 0,
                    "sequence_number" -> counter.getAndIncrement(),
                    "part" -> Json.obj("type" -> "output_text", "text" -> "", "annotations" -> Json.arr(), "logprobs" -> Json.arr()),
                  ))
                ))

                // text, tool calls and usage are only complete once the stream ends
                val acc = new ResponsesStreamAccumulator()

                val deltaEvents = source.mapConcat { chunk =>
                  acc.accumulate(chunk)
                  chunk.choices.headOption.flatMap(_.delta.content).filter(_.nonEmpty).map { text =>
                    sseEvent("response.output_text.delta", Json.obj(
                      "type" -> "response.output_text.delta",
                      "item_id" -> msgId,
                      "output_index" -> 0,
                      "content_index" -> 0,
                      "logprobs" -> Json.arr(),
                      "sequence_number" -> counter.getAndIncrement(),
                      "delta" -> text
                    ))
                  }.toList
                }

                // materialized by `concatLazy` only once the delta events are done, which is what
                // makes the accumulated text, tool calls and usage available here
                val footerEvents = Source.lazySource { () =>
                  val text = acc.wholeText
                  val contentPart = Json.obj("type" -> "output_text", "text" -> text, "annotations" -> Json.arr(), "logprobs" -> Json.arr())
                  val messageItem = msgItem.deepMerge(Json.obj("status" -> "completed", "content" -> Json.arr(contentPart)))
                  // tool calls streamed as deltas are reported as `function_call` output items
                  val functionCallItems = acc.functionCallItems(_ => s"fc_${IdGenerator.token(24)}")
                  Source(List(
                    sseEvent("response.output_text.done", Json.obj(
                      "type" -> "response.output_text.done",
                      "item_id" -> msgId,
                      "output_index" -> 0,
                      "content_index" -> 0,
                      "sequence_number" -> counter.getAndIncrement(),
                      "text" -> text,
                      "logprobs" -> Json.arr(),
                    )),
                    sseEvent("response.content_part.done", Json.obj(
                      "type" -> "response.content_part.done",
                      "item_id" -> msgId,
                      "output_index" -> 0,
                      "content_index" -> 0,
                      "sequence_number" -> counter.getAndIncrement(),
                      "part" -> contentPart,
                    )),
                    sseEvent("response.output_item.done", Json.obj(
                      "type" -> "response.output_item.done",
                      "output_index" -> 0,
                      "sequence_number" -> counter.getAndIncrement(),
                      "item" -> messageItem,
                    )),
                  ) ++ functionCallItems.zipWithIndex.map { case (item, idx) =>
                    sseEvent("response.output_item.done", Json.obj(
                      "type" -> "response.output_item.done",
                      "output_index" -> (idx + 1),
                      "sequence_number" -> counter.getAndIncrement(),
                      "item" -> item,
                    ))
                  }.toList ++ List(
                    sseEvent("response.completed", eventWithResponse("response.completed", counter.getAndIncrement()).deepMerge(
                      Json.obj(
                        "status" -> "completed",
                        "response" -> Json.obj(
                          "output" -> JsArray(Seq(messageItem) ++ functionCallItems),
                          // reported by the final chunk of the stream, not by the provider payload
                          "usage" -> acc.usageJson,
                        ),
                      )
                    )),
                  ))
                }

                val finalSource = headerEvents.concat(deltaEvents).concatLazy(footerEvents)
                Right(BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok.chunked(finalSource).as("text/event-stream")), None))
              }
            }
          } else {
            client.tryResponse(ChatPrompt(messages), ctx.attrs, openAiBody).map {
              case Left(err) => Left(NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(err)))
              case Right(response) =>
                val model = client.computeModel(openAiBody).getOrElse("none")
                val tools = OpenAiResponsesBodyConverter.normalizeResponsesTools(jsonBody.select("tools").asOpt[Seq[JsObject]].getOrElse(Seq.empty))
                Right(BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok(buildResponseJson(response, model, jsonBody.asObject, tools, env))
                  .withHeaders(response.metadata.cacheHeaders.toSeq*)), None))
            }
          }
        } else {
          Left(NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(Json.obj("error" -> "bad_request", "error_description" -> "invalid request")))).vfuture
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

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    if (ctx.request.hasBody) {
      ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
        try {
          val jsonBody = bodyRaw.utf8String.parseJson
          val config = ctx.cachedConfig(internalName)(AiPluginRefsConfig.format).getOrElse(AiPluginRefsConfig.default)
          call(jsonBody, config, ctx)
        } catch {
          case e: Throwable =>
            NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(Json.obj("error" -> "bad_request", "error_details" -> e.getMessage))).leftf
        }
      }
    } else {
      val config = ctx.cachedConfig(internalName)(AiPluginRefsConfig.format).getOrElse(AiPluginRefsConfig.default)
      call(Json.obj(), config, ctx)
    }
  }
}
