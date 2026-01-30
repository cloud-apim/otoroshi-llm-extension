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
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json._
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}

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

  private def transformInputToMessages(jsonBody: JsValue): Seq[JsObject] = {
    val instructionMessages: Seq[JsObject] = jsonBody.select("instructions").asOptString.map { instructions =>
      Seq(Json.obj("role" -> "system", "content" -> instructions))
    }.getOrElse(Seq.empty)

    val inputMessages: Seq[JsObject] = jsonBody.select("input").asOptString match {
      case Some(text) => Seq(Json.obj("role" -> "user", "content" -> text))
      case None => jsonBody.select("input").asOpt[Seq[JsObject]].getOrElse(Seq.empty).flatMap { item =>
        val itemType = item.select("type").asOptString.getOrElse("message")
        itemType match {
          case "message" =>
            val role = item.select("role").asOptString.getOrElse("user") match {
              case "developer" => "system"
              case other => other
            }
            val content: JsValue = item.select("content").asOptString match {
              case Some(text) => JsString(text).asValue
              case None => JsArray(item.select("content").asOpt[Seq[JsObject]].getOrElse(Seq.empty).flatMap { contentItem =>
                contentItem.select("type").asOptString match {
                  case Some("input_image") =>
                    val image_url = contentItem.select("image_url").asOptString
                    val detail = contentItem.select("detail").asOptString
                    Json.obj(
                      "type" -> "image_url",
                      "image_url" -> Json.obj(
                        "url" -> image_url,
                        "details" -> detail,
                      )
                    ).some
                  case Some("input_audio") =>
                    val data = contentItem.select("data").asOptString
                    val format = contentItem.select("format").asOptString
                    Json.obj(
                      "type" -> "input_audio",
                      "input_audio" -> Json.obj(
                        "data" -> data,
                        "format" -> format,
                      )
                    ).some
                  case Some("input_file") =>
                    val filename = contentItem.select("filename").asOptString
                    val file_data = contentItem.select("file_data").asOptString
                    //val file_url = contentItem.select("file_url").asOptString
                    Json.obj(
                      "type" -> "file",
                      "file" -> Json.obj(
                        "filename" -> filename,
                        "file_data" -> file_data,
                        // "file_url" -> file_url
                      )
                    ).some
                  case Some("input_video") =>
                    val data = contentItem.select("data").asOptString
                    val format = contentItem.select("format").asOptString
                    Json.obj(
                      "type" -> "input_video",
                      "input_video" -> Json.obj(
                        "data" -> data,
                        "format" -> format,
                      )
                    ).some
                  case Some("input_text") =>
                    val text = contentItem.select("text").asOptString.getOrElse("")
                    Json.obj("type" -> "text", "text" -> text).some
                  case _ => None
                }
              })
            }
            Seq(Json.obj("role" -> role, "content" -> content))
          case "function_call_output" =>
            val toolCallId = item.select("call_id").asOptString.getOrElse("")
            val output = item.select("output").asOptString.getOrElse("")
            Seq(Json.obj("role" -> "tool", "tool_call_id" -> toolCallId, "content" -> output))
          case "function_call" =>
            val callId = item.select("call_id").asOptString.getOrElse(item.select("id").asOptString.getOrElse(""))
            val fnName = item.select("name").asOptString.getOrElse("")
            val arguments = item.select("arguments").asOptString.getOrElse("{}")
            Seq(Json.obj(
              "role" -> "assistant",
              "tool_calls" -> Json.arr(Json.obj(
                "id" -> callId,
                "type" -> "function",
                "function" -> Json.obj(
                  "name" -> fnName,
                  "arguments" -> arguments
                )
              ))
            ))
          case _ => Seq.empty
        }
      }
    }
    instructionMessages ++ inputMessages
  }

  private def transformTools(jsonBody: JsValue): JsValue = {
    jsonBody.select("tools").asOpt[Seq[JsObject]] match {
      case None => JsNull
      case Some(tools) =>
        JsArray(tools.map { tool =>
          val toolType = tool.select("type").asOptString.getOrElse("function")
          if (toolType == "function") {
            val name = tool.select("name").asOptString.getOrElse("")
            val description = tool.select("description").asOptString.getOrElse("")
            val parameters = tool.select("parameters").asOpt[JsObject].getOrElse(Json.obj())
            val strict = tool.select("strict").asOptBoolean
            val fn = Json.obj(
              "name" -> name,
              "description" -> description,
              "parameters" -> parameters
            ).applyOnWithOpt(strict) {
              case (o, s) => o ++ Json.obj("strict" -> s)
            }
            Json.obj("type" -> "function", "function" -> fn)
          } else {
            tool
          }
        })
    }
  }

  private def buildOpenAiBody(jsonBody: JsValue, messages: Seq[JsObject]): JsObject = {
    var body = Json.obj("messages" -> messages)
    jsonBody.select("model").asOptString.foreach(m => body = body ++ Json.obj("model" -> m))
    jsonBody.select("temperature").asOpt[JsNumber].foreach(t => body = body ++ Json.obj("temperature" -> t))
    jsonBody.select("top_p").asOpt[JsNumber].foreach(t => body = body ++ Json.obj("top_p" -> t))
    jsonBody.select("max_output_tokens").asOpt[JsNumber].foreach(t => body = body ++ Json.obj("max_completion_tokens" -> t))
    jsonBody.select("tool_choice").asOpt[JsValue].foreach(t => body = body ++ Json.obj("tool_choice" -> t))
    jsonBody.select("parallel_tool_calls").asOpt[JsValue].foreach(t => body = body ++ Json.obj("parallel_tool_calls" -> t))
    jsonBody.select("store").asOpt[JsValue].foreach(t => body = body ++ Json.obj("store" -> t))
    jsonBody.select("metadata").asOpt[JsValue].foreach(t => body = body ++ Json.obj("metadata" -> t))
    jsonBody.select("provider").asOptString.foreach(p => body = body ++ Json.obj("provider" -> p))
    val tools = transformTools(jsonBody)
    if (tools != JsNull) {
      body = body ++ Json.obj("tools" -> tools)
    }
    body
  }

  private def buildResponseJson(response: com.cloud.apim.otoroshi.extensions.aigateway.ChatResponse, model: String, request: JsObject, tools: Seq[JsObject], env: Env): JsValue = {
    val respId = s"resp_${IdGenerator.token(32)}"
    val msgId = s"msg_${IdGenerator.token(32)}"
    val createdAt = System.currentTimeMillis() / 1000

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
            "annotations" -> gen.message.annotationsOrEmpty
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
          "arguments" -> arguments
        )
      }
    }

    // If we have tool calls, also emit the assistant message (without tool_calls) before the function_call items
    val output: Seq[JsObject] = if (toolCallOutputs.nonEmpty) {
      val message = response.generations.head
      val assistantMsg = Json.obj(
        "type" -> "message",
        "id" -> msgId,
        "status" -> "completed",
        "role" -> "assistant",
        "content" -> Json.arr(
          Json.obj(
            "type" -> "output_text",
            "text" -> message.message.wholeTextContent,
            "annotations" -> message.message.annotationsOrEmpty
          )
        )
      )
      Seq(assistantMsg) ++ toolCallOutputs
    } else {
      textOutputs
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

      // - previous_response_id: Invalid input
      // - instructions: Invalid input
      // - output.0: Invalid input
      // - error: Invalid input
      // - tools: Invalid input: expected array, received undefined
      // - tool_choice: Invalid input
      // - truncation: Invalid option: expected one of "auto"|"disabled"
      // - parallel_tool_calls: Invalid input: expected boolean, received undefined
      // - text: Invalid input: expected object, received undefined
      // - reasoning: Invalid input
      // - usage: Invalid input
      // - max_output_tokens: Invalid input
      // - max_tool_calls: Invalid input
      // - safety_identifier: Invalid input
      // - prompt_cache_key: Invalid input
    )
  }

  private def sseEvent(eventType: String, data: JsValue): ByteString = {
    s"event: $eventType\ndata: ${data.stringify}\n\n".byteString
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
        val requestMessages = transformInputToMessages(jsonBody)
        val openAiBody = buildOpenAiBody(jsonBody, requestMessages)

        if (validate(requestMessages, ctx)) {
          val (preContextMessages, postContextMessages) = ctx.attrs.get(AiPluginsKeys.PromptContextKey).getOrElse((Seq.empty, Seq.empty))
          val messages = (preContextMessages ++ requestMessages ++ postContextMessages).map { obj =>
            InputChatMessage.fromJson(obj)
          }
          if (stream) {
            val respId = s"resp_${IdGenerator.token(32)}"
            val msgId = s"msg_${IdGenerator.token(32)}"
            val model = client.computeModel(openAiBody).getOrElse("none")
            val createdAt = System.currentTimeMillis() / 1000

            val responseSnapshot = Json.obj(
              "id" -> respId,
              "object" -> "response",
              "created_at" -> createdAt,
              "status" -> "in_progress",
              "model" -> model,
              "output" -> Json.arr()
            )

            val msgItem = Json.obj(
              "type" -> "message",
              "id" -> msgId,
              "status" -> "in_progress",
              "role" -> "assistant",
              "content" -> Json.arr()
            )

            client.tryStream(ChatPrompt(messages), ctx.attrs, openAiBody).map {
              case Left(err) => Left(NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(err)))
              case Right(source) => {
                val headerEvents = Source(List(
                  sseEvent("response.created", responseSnapshot),
                  sseEvent("response.in_progress", responseSnapshot),
                  sseEvent("response.output_item.added", Json.obj(
                    "type" -> "response.output_item.added",
                    "output_index" -> 0,
                    "item" -> msgItem
                  )),
                  sseEvent("response.content_part.added", Json.obj(
                    "type" -> "response.content_part.added",
                    "item_id" -> msgId,
                    "output_index" -> 0,
                    "content_index" -> 0,
                    "part" -> Json.obj("type" -> "output_text", "text" -> "")
                  ))
                ))

                val deltaEvents = source.map { chunk =>
                  val text = chunk.choices.headOption.flatMap(_.delta.content).getOrElse("")
                  sseEvent("response.output_text.delta", Json.obj(
                    "type" -> "response.output_text.delta",
                    "item_id" -> msgId,
                    "output_index" -> 0,
                    "content_index" -> 0,
                    "delta" -> text
                  ))
                }

                val footerEvents = Source(List(
                  sseEvent("response.output_text.done", Json.obj(
                    "type" -> "response.output_text.done",
                    "item_id" -> msgId,
                    "output_index" -> 0,
                    "content_index" -> 0,
                    "text" -> ""
                  )),
                  sseEvent("response.content_part.done", Json.obj(
                    "type" -> "response.content_part.done",
                    "item_id" -> msgId,
                    "output_index" -> 0,
                    "content_index" -> 0,
                    "part" -> Json.obj("type" -> "output_text", "text" -> "")
                  )),
                  sseEvent("response.output_item.done", Json.obj(
                    "type" -> "response.output_item.done",
                    "output_index" -> 0,
                    "item" -> msgItem.deepMerge(Json.obj("status" -> "completed"))
                  )),
                  sseEvent("response.completed", responseSnapshot.deepMerge(Json.obj("status" -> "completed")))
                ))

                val finalSource = headerEvents.concat(deltaEvents).concat(footerEvents)
                Right(BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok.chunked(finalSource).as("text/event-stream")), None))
              }
            }
          } else {
            client.call(ChatPrompt(messages), ctx.attrs, openAiBody).map {
              case Left(err) => Left(NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(err)))
              case Right(response) =>
                val model = client.computeModel(openAiBody).getOrElse("none")
                val tools = openAiBody.select("tools").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
                Right(BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok(buildResponseJson(response, model, jsonBody.asObject, tools, env))
                  .withHeaders(response.metadata.cacheHeaders.toSeq: _*)), None))
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
            NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(Json.obj("error" -> "bad_request", "error_details" -> e.getMessage))).leftf
        }
      }
    } else {
      val config = ctx.cachedConfig(internalName)(AiPluginRefsConfig.format).getOrElse(AiPluginRefsConfig.default)
      call(Json.obj(), config, ctx)
    }
  }
}
