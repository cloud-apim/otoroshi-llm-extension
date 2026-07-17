package com.cloud.apim.otoroshi.extensions.aigateway.providers

import akka.stream.scaladsl.{Framing, Source}
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway._
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AnthropicApiResponseChoiceMessageToolCall, GenericApiResponseChoiceMessageToolCall, LlmFunctions}
import diffson.DiffOps
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}
import play.api.libs.ws.WSResponse

import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

case class AnthropicChatResponseChunkUsage(raw: JsObject) {
  lazy val input_tokens: Long = raw.select("input_tokens").asOptLong.getOrElse(0L)
  lazy val output_tokens: Long = raw.select("output_tokens").asOptLong.getOrElse(0L)
  lazy val reasoningTokens: Long = raw.at("reasoning_tokens").asOptLong.getOrElse(0L)
}

case class AnthropicChatResponseChunkChoice(raw: JsValue, role: String) {
  lazy val index: Option[Int] = raw.select("index").asOptInt
  lazy val typ: Option[String] = raw.at("content_block.type").asOptString.orElse(raw.at("delta.type").asOptString)
  lazy val content: Option[String] = raw.at("content_block.text").asOptString.orElse(raw.at("delta.text").asOptString)
  lazy val finish_reason: Option[String] = raw.at("delta.stop_reason").asOptString

  lazy val contentBlockObj: JsObject = raw.select("content_block").asObject

  lazy val tool_use = typ.contains("tool_use")
  lazy val input_json_delta = typ.contains("input_json_delta")
  lazy val stop_tool_use = finish_reason.contains("tool_use")

  lazy val partial_json: String = raw.at("delta.partial_json").asString
}

case class AnthropicApiResponseChunk(raw: JsValue, messageRef: Option[JsValue]) {
  // messageRef is the `message_start` event: `id`, `model`, `role` live under its `message` object,
  // not at the top-level (only `type` is top-level), hence we look there first to avoid "--" values.
  lazy val typ: String = messageRef.flatMap(_.select("type").asOptString).getOrElse("--")
  lazy val id: String = messageRef.flatMap(m => m.select("message").select("id").asOptString.orElse(m.select("id").asOptString)).getOrElse("--")
  lazy val created: Long = messageRef.flatMap(m => m.select("message").select("created").asOptLong.orElse(m.select("created").asOptLong)).getOrElse(0L)
  lazy val model: String = messageRef.flatMap(m => m.select("message").select("model").asOptString.orElse(m.select("model").asOptString)).getOrElse("--")
  lazy val role: String = messageRef.flatMap(m => m.select("message").select("role").asOptString.orElse(m.select("role").asOptString)).getOrElse("assistant")
  lazy val usage: Option[AnthropicChatResponseChunkUsage] = raw.select("usage").asOpt[JsObject].map { obj =>
    AnthropicChatResponseChunkUsage(obj)
  }
  lazy val choices: Seq[AnthropicChatResponseChunkChoice] = Seq(AnthropicChatResponseChunkChoice(raw, role))

  lazy val ztyp = raw.select("type").asString
  lazy val message_start: Boolean = ztyp == "message_start"
  lazy val content_block_start: Boolean = ztyp == "content_block_start"
  lazy val content_block_delta: Boolean = ztyp == "content_block_delta"
  lazy val content_block_stop: Boolean = ztyp == "content_block_stop"
  lazy val message_delta: Boolean = ztyp == "message_delta"
  lazy val message_stop: Boolean = ztyp == "message_stop"
}

case class AnthropicChatResponseChunkChoiceDeltaToolCall(raw: JsValue) {

}

case class AnthropicApiResponse(status: Int, headers: Map[String, String], body: JsValue) {
  def json: JsValue = Json.obj(
    "status" -> status,
    "headers" -> headers,
    "body" -> body,
  )
  def finishBecauseOfToolCalls: Boolean = {
    body.select("content").asOpt[Seq[JsObject]].exists(seq => seq.exists(o => o.select("type").asOptString.contains("tool_use")))
  }
}
object AnthropicModels {
  val CLAUDE_3_5_SONNET = "claude-3-5-sonnet-20240620"
  val CLAUDE_3_OPUS = "claude-3-opus-20240229"
  val CLAUDE_3_SONNET = "claude-3-sonnet-20240229"
  val CLAUDE_3_HAIKU = "claude-3-haiku-20240307"

  // sampling parameters (temperature, top_p, top_k) are removed starting from Claude Opus 4.7.
  // Sending any of them to such a model returns a 400, so we strip them automatically.
  // see https://platform.claude.com/docs/en/about-claude/models/migration-guide#migrating-to-claude-opus-4-7
  private val opusVersionRegex = """claude-opus-(\d+)-(\d+).*""".r

  def dropsSamplingParams(model: String): Boolean = {
    model.toLowerCase.trim match {
      case opusVersionRegex(major, minor) =>
        val maj = major.toInt
        val min = minor.toInt
        maj > 4 || (maj == 4 && min >= 7)
      case _ => false
    }
  }
}
object AnthropicApi {
  val baseUrl = "https://api.anthropic.com"
}
class AnthropicApi(baseUrl: String = AnthropicApi.baseUrl, token: String, anthropicVersion: String = "2023-06-01", anthropicBeta: Option[String], timeout: FiniteDuration = 3.minutes, env: Env) extends ApiClient[AnthropicApiResponse, AnthropicApiResponseChunk] {

  val providerName = "anthropic"
  override def supportsTools: Boolean = true
  override def supportsCompletion: Boolean = true
  override def supportsStreaming: Boolean = true

  def rawCall(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[WSResponse] = {
    val url = s"${baseUrl}${path}"
    ProviderHelpers.logCall("Anthropic", method, url, body)(env)
    env.Ws
      .url(url)
      .withHttpHeaders(
        s"x-api-key" -> token,
        "anthropic-version" -> anthropicVersion,
        "Accept" -> "application/json",
      )
      .applyOnWithOpt(anthropicBeta) {
        case (builder, beta) => builder.addHttpHeaders("anthropic-beta" -> beta)
      }
      .applyOnWithOpt(body) {
        case (builder, body) => builder
          .addHttpHeaders("Content-Type" -> "application/json")
          .withBody(body)
      }
      .withMethod(method)
      .withRequestTimeout(timeout)
      .execute()
  }

  override def call(method: String, path: String, body: Option[JsValue], acc: UsageAccumulator)(implicit ec: ExecutionContext): Future[Either[JsValue, AnthropicApiResponse]] = {
    rawCall(method, path, body)
      .map(r => ProviderHelpers.wrapResponse("Anthropic", r, env) { resp =>
        acc.updateAnthropic(resp.json.select("usage").asOpt[JsObject])
        AnthropicApiResponse(resp.status, resp.headers.mapValues(_.last), resp.json)
      })
  }

  def raw_stream(method: String, path: String, body: Option[JsValue], acc: UsageAccumulator)(implicit ec: ExecutionContext): Future[Either[JsValue, (Source[AnthropicApiResponseChunk, _], WSResponse)]] = {
    val url = s"${baseUrl}${path}"
    ProviderHelpers.logStream(providerName, method, url, body)(env)
    val messageRef = new AtomicReference[JsValue]()
    env.Ws
      .url(s"${baseUrl}${path}")
      .withHttpHeaders(
        s"x-api-key" -> token,
        "anthropic-version" -> anthropicVersion,
        "Accept" -> "application/json",
      )
      .applyOnWithOpt(anthropicBeta) {
        case (builder, beta) => builder.addHttpHeaders("anthropic-beta" -> beta)
      }
      .applyOnWithOpt(body) {
        case (builder, body) => builder
          .addHttpHeaders("Content-Type" -> "application/json")
          .withBody(body.asObject ++ Json.obj(
            "stream" -> true,
          ))
      }
      .withMethod(method)
      .withRequestTimeout(timeout)
      .stream()
      .map(r => ProviderHelpers.wrapStreamResponse(providerName, r, env) { resp =>
        (resp.bodyAsSource
          .via(Framing.delimiter(ByteString("\n\n"), Int.MaxValue, false))
          .map(_.utf8String)
          .flatMapConcat { str =>
            Source(str.split("\n").toList)
          }
          .filter(_.startsWith("data: "))
          .map(_.replaceFirst("data: ", "").trim())
          .filter(_.nonEmpty)
          .map(str => Json.parse(str))
          .map { json =>
            if (json.select("type").asOptString.contains("message_start")) {
              messageRef.set(json)
            }
            json
          }
          .filterNot(_.select("type").asOptString.contains("ping"))
          // .filterNot(_.select("type").asOptString.contains("content_block_stop"))
          .takeWhile(!_.select("type").asOptString.contains("message_stop"), true)
          .map(json => AnthropicApiResponseChunk(json, Option(messageRef.get())))
          .map { c =>
            acc.updateAnthropicChunk(c.usage)
            c
          }
          , resp)
      })
  }

  override def stream(method: String, path: String, body: Option[JsValue], acc: UsageAccumulator)(implicit ec: ExecutionContext): Future[Either[JsValue, (Source[AnthropicApiResponseChunk, _], WSResponse)]] = {
    raw_stream(method, path, body, acc)
      .map {
        case Left(e) => Left(e)
        case Right((source, resp)) => {
          val nSource = source
            .filterNot(_.content_block_stop)
            .filterNot(_.message_stop)
          Right((nSource, resp))
        }
      }
  }

  override def callWithToolSupport(method: String, path: String, body: Option[JsValue], mcpConnectors: Seq[String], attrs: TypedMap, nameToFunction: Map[String, String], maxCalls: Int, currentCallCounter: Int, acc: UsageAccumulator)(implicit ec: ExecutionContext): Future[Either[JsValue, AnthropicApiResponse]] = {
    if (currentCallCounter >= maxCalls) {
      return call(method, path, body, acc)
    }
    // TODO: accumulate consumptions ???
    if (body.flatMap(_.select("tools").asOpt[JsArray]).exists(_.value.nonEmpty)) {
      call(method, path, body, acc).flatMap {
        case Left(err) => err.leftf
        case Right(resp) if resp.finishBecauseOfToolCalls => {
          body match {
            case None => resp.rightf
            case Some(body) => {
              val messages = body.select("messages").asOpt[Seq[JsObject]].getOrElse(Seq.empty) //.map(v => v.flatMap(o => ChatMessage.format.reads(o).asOpt)).getOrElse(Seq.empty)
              val toolCalls = resp.body.select("content").as[Seq[JsObject]].filter(o => o.select("type").asOptString.contains("tool_use"))
              LlmFunctions.callToolsAnthropic(toolCalls.map(tc => AnthropicApiResponseChoiceMessageToolCall(tc)), mcpConnectors, providerName, attrs, nameToFunction)(ec, env)
                .flatMap { callResps =>
                  val newMessages: Seq[JsValue] = messages ++ callResps
                  val newBody = body.asObject ++ Json.obj("messages" -> JsArray(newMessages))
                  callWithToolSupport(method, path, newBody.some, mcpConnectors, attrs, nameToFunction, maxCalls, currentCallCounter + 1, acc)
                }
            }
          }
        }
        case Right(resp) =>
          resp.rightf
      }
    } else {
      call(method, path, body, acc)
    }
  }

  override def streamWithToolSupport(method: String, path: String, body: Option[JsValue], mcpConnectors: Seq[String], attrs: TypedMap, nameToFunction: Map[String, String], maxCalls: Int, currentCallCounter: Int, acc: UsageAccumulator)(implicit ec: ExecutionContext): Future[Either[JsValue, (Source[AnthropicApiResponseChunk, _], WSResponse)]] = {
    if (currentCallCounter >= maxCalls) {
      return stream(method, path, body, acc)
    }
    if (body.flatMap(_.select("tools").asOpt[JsArray]).exists(_.value.nonEmpty)) {
      val messages = body.get.select("messages").asOpt[Seq[JsObject]].getOrElse(Seq.empty) //.map(v => v.flatMap(o => ChatMessage.format.reads(o).asOpt)).getOrElse(Seq.empty)
      raw_stream(method, path, body, acc).flatMap {
        case Left(err) => err.leftf
        case Right(res) => {
          var isToolCall = false
          var isToolCallEnded = false
          var toolCalls: Seq[AnthropicChatResponseChunkChoiceDeltaToolCall] = Seq.empty
          var toolCallArgs: scala.collection.mutable.ArraySeq[String] = scala.collection.mutable.ArraySeq.empty
          // var toolCallUsage: AnthropicChatResponseChunkUsage = null
          var index: Int = 0
          val newSource = res._1.flatMapConcat { chunk =>
            // println(s"chunk: ${chunk.raw.prettify}")
            if (!isToolCall &&  chunk.choices.exists(_.tool_use)) {
              // println("start tool_user")
              isToolCall = true
              toolCalls = toolCalls :+ AnthropicChatResponseChunkChoiceDeltaToolCall(chunk.choices.head.contentBlockObj)
              toolCallArgs = scala.collection.mutable.ArraySeq((0 to toolCallArgs.size).map(_ => ""): _*)
              Source.empty
            } else if (isToolCall && !isToolCallEnded) {
              if (chunk.choices.head.stop_tool_use) {
                // println("stopping tool_user")
                isToolCallEnded = true
              } else {
                if (chunk.choices.head.input_json_delta) {
                  // println("agg input")
                  val tc = chunk.choices.head
                  // val index: Int = tc.index.getOrElse(-1)
                  val arg = tc.partial_json
                  if (index >= toolCallArgs.size) {
                    toolCallArgs = toolCallArgs :+ ""
                  }
                  // if (tc.function.hasName && !toolCalls.exists(t => t.function.hasName && t.function.name == tc.function.name)) {
                  //   toolCalls = toolCalls :+ tc
                  // }
                  toolCallArgs.update(index, toolCallArgs.apply(index) + arg)
                } else {
                  //println("no agg")
                  if (chunk.content_block_stop) {
                    index = index + 1
                  }
                }
              }
              Source.empty
            } else if (isToolCall && isToolCallEnded && chunk.message_stop) {
              //println("end tool_use 1")
              //toolCallUsage = chunk.usage.get
              val calls = toolCalls.zipWithIndex.map {
                case (toolCall, idx) =>
                  //println(s"prep call ${idx} - ${toolCall.raw.prettify} - ${toolCallArgs(idx)}")
                  val a = AnthropicApiResponseChoiceMessageToolCall(toolCall.raw.asObject.deepMerge(Json.obj("input" -> toolCallArgs(idx).parseJson)))
                  //println(s"call ${idx}: ${a.raw.prettify}")
                  a
              }
              //println("calling !!!")
              val a: Future[Either[JsValue, (Source[AnthropicApiResponseChunk, _], WSResponse)]] = LlmFunctions.callToolsAnthropic(calls, mcpConnectors, providerName, attrs, nameToFunction)(ec, env)
                .flatMap { callResps =>
                  // val newMessages: Seq[JsValue] = messages.map(_.json) ++ callResps
                  val newMessages: Seq[JsValue] = messages ++ callResps
                  val newBody = body.get.asObject ++ Json.obj("messages" -> JsArray(newMessages))
                  streamWithToolSupport(method, path, newBody.some, mcpConnectors, attrs, nameToFunction, maxCalls, currentCallCounter + 1, acc)
                }
              Source.future(a).flatMapConcat {
                case Left(err) => Source.failed(new Throwable(err.stringify))
                case Right(tuple) => tuple._1
              }
            } else {
              // println(s"nothing: ${chunk.typ} ${chunk.raw.select("type").asString} ${isToolCall} && ${isToolCallEnded} && ${chunk.message_stop} - ${isToolCall && isToolCallEnded && chunk.message_stop}")
              Source.single(chunk)
            }
          }
          (newSource, res._2).rightf
        }
      }
    } else {
      stream(method, path, body, acc)
    }
  }
}

object AnthropicChatClientOptions {
  def fromJson(json: JsValue): AnthropicChatClientOptions = {
    AnthropicChatClientOptions(
      stream = json.select("stream").asOptBoolean,
      model = json.select("model").asOpt[String].getOrElse(OpenAiModels.GPT_3_5_TURBO),
      metadata = json.select("metadata").asOpt[JsObject],
      stop_sequences = json.select("stop_sequences").asOpt[Seq[String]],
      tools = json.select("tools").asOpt[Seq[JsValue]],
      tool_choice = json.select("tool_choice").asOpt[Seq[JsValue]],
      max_tokens = json.select("max_tokens").asOpt[Int].getOrElse(1024),
      temperature = json.select("temperature").asOpt[Float],
      topP = json.select("top_p").asOpt[Float],
      topK = json.select("top_k").asOpt[Int],
      system = json.select("system").asOpt[String],
      allowConfigOverride = json.select("allow_config_override").asOptBoolean.getOrElse(true),
      wasmTools = json.select("wasm_tools").asOpt[Seq[String]].filter(_.nonEmpty).orElse(json.select("tool_functions").asOpt[Seq[String]]).getOrElse(Seq.empty),
      mcpConnectors = json.select("mcp_connectors").asOpt[Seq[String]].getOrElse(Seq.empty),
      a2aConnectors = json.select("a2a_connectors").asOpt[Seq[String]].getOrElse(Seq.empty),
      searchEngines = json.select("search_engines").asOpt[Seq[String]].getOrElse(Seq.empty),
      mcpIncludeFunctions = json.select("mcp_include_functions").asOpt[Seq[String]].getOrElse(Seq.empty),
      mcpExcludeFunctions = json.select("mcp_exclude_functions").asOpt[Seq[String]].getOrElse(Seq.empty),
      maxFunctionCalls = json.select("max_function_calls").asOpt[Int].getOrElse(10),
    )
  }
}

case class AnthropicChatClientOptions(
  model: String = AnthropicModels.CLAUDE_3_HAIKU,
  max_tokens: Int = 1024,
  metadata: Option[JsObject] = None,
  stop_sequences: Option[Seq[String]] = None,
  stream: Option[Boolean] = Some(false),
  system: Option[String] = None,
  temperature: Option[Float] = None,
  tools: Option[Seq[JsValue]] = None,
  tool_choice: Option[Seq[JsValue]] =  None,
  topK: Option[Int] = None,
  topP: Option[Float] = None,
  allowConfigOverride: Boolean = true,
  wasmTools: Seq[String] = Seq.empty,
  mcpConnectors: Seq[String] = Seq.empty,
  a2aConnectors: Seq[String] = Seq.empty,
  searchEngines: Seq[String] = Seq.empty,
  mcpIncludeFunctions: Seq[String] = Seq.empty,
  mcpExcludeFunctions: Seq[String] = Seq.empty,
  maxFunctionCalls: Int = 10,
) extends ChatOptions {

  lazy val wasmToolsNoInline: Seq[String] = wasmTools.filterNot(_.startsWith("__inline_"))

  lazy val wasmToolsInline: Seq[String] = wasmTools.filter(_.startsWith("__inline_"))

  override def json: JsObject = Json.obj(
    "model" -> model,
    "max_tokens" -> max_tokens,
    "stream" -> stream,
    "system" -> system,
    "tools" -> tools,
    "tool_choice" -> tool_choice,
    "metadata" -> metadata,
    "stop_sequences" -> stop_sequences,
    "allow_config_override" -> allowConfigOverride,
    "tool_functions" -> JsArray(wasmTools.map(_.json)),
    "mcp_connectors" -> JsArray(mcpConnectors.map(_.json)),
    "a2a_connectors" -> JsArray(a2aConnectors.map(_.json)),
    "search_engines" -> JsArray(searchEngines.map(_.json)),
    "mcp_include_functions" -> JsArray(mcpIncludeFunctions.map(_.json)),
    "mcp_exclude_functions" -> JsArray(mcpExcludeFunctions.map(_.json)),
    "max_function_calls" -> maxFunctionCalls,
  )
  .applyOnWithOpt(temperature) {
    case (obj, _) if topK.isDefined || topP.isDefined => obj
    case (obj, temperature) => obj ++ Json.obj("temperature" -> temperature)
  }
  .applyOnWithOpt(topK) {
    case (obj, k) => obj ++ Json.obj("top_k" -> k)
  }
  .applyOnWithOpt(topP) {
    case (obj, p) => obj ++ Json.obj("top_p" -> p)
  }

  def jsonForCall: JsObject = optionsCleanup(json - "max_function_calls"  - "wasm_tools" - "tool_functions" - "mcp_connectors" - "a2a_connectors" - "search_engines" - "allow_config_override" - "mcp_include_functions" - "mcp_exclude_functions")
}

class AnthropicChatClient(api: AnthropicApi, options: AnthropicChatClientOptions, id: String) extends ChatClient {

  // supports tools: true
  // supports streaming: true

  override def isAnthropic: Boolean = true
  override def isOpenAi: Boolean = false
  override def isCohere: Boolean = false
  override def supportsTools: Boolean = api.supportsTools
  override def supportsStreaming: Boolean = api.supportsStreaming
  override def supportsCompletion: Boolean = false

  override def computeModel(payload: JsValue): Option[String] = payload.select("model").asOpt[String].orElse(options.model.some)

  override def listModels(raw: Boolean, attrs: TypedMap)(implicit ec: ExecutionContext): Future[Either[JsValue, List[String]]] = {
    api.rawCall("GET", "/v1/models", None).map { resp =>
      if (resp.status == 200) {
        Right(resp.json.select("data").as[List[JsObject]].map(obj => obj.select("id").asString))
      } else {
        Left(Json.obj("error" -> s"bad response code: ${resp.status}"))
      }
    }
  }

  override def transformOpenAIInputBodyToProviderInputBody(inputBody: JsObject): JsObject = {
    inputBody
      .applyOn { body =>
        body.select("max_completion_tokens").asOpt[Long] match {
          case None => body
          case Some(value) => body - "max_completion_tokens" ++ Json.obj("max_tokens" -> value)
        }
      }
      .applyOn(transformOpenAiToolsToAnthropic)
      .applyOn(transformOpenAiToolChoiceToAnthropic)
  }

  // OpenAI tools are shaped as { "type": "function", "function": { "name", "description", "parameters" } }
  // while Anthropic expects { "name", "description", "input_schema" }. We translate any OpenAI-shaped
  // tool and leave tools already in the Anthropic format (no "function" wrapper) untouched.
  private def transformOpenAiToolsToAnthropic(body: JsObject): JsObject = {
    body.select("tools").asOpt[Seq[JsObject]] match {
      case None => body
      case Some(tools) =>
        val newTools = tools.map { tool =>
          tool.select("function").asOpt[JsObject] match {
            case None => tool // already in the Anthropic format
            case Some(function) =>
              val name = function.select("name").asString
              val parameters = function.select("parameters").asOpt[JsObject].getOrElse(Json.obj("type" -> "object", "properties" -> Json.obj()))
              Json.obj(
                "name" -> name,
                "input_schema" -> parameters,
              ).applyOnWithOpt(function.select("description").asOpt[String]) {
                case (obj, description) => obj ++ Json.obj("description" -> description)
              }
          }
        }
        body ++ Json.obj("tools" -> JsArray(newTools))
    }
  }

  // OpenAI tool_choice is either a string ("none" | "auto" | "required") or an object
  // ({ "type": "function", "function": { "name": "..." } }) while Anthropic always expects an object
  // ({ "type": "auto" | "any" | "tool" | "none", "name"?: "..." }). We translate it here. Values already
  // in the Anthropic format are left untouched.
  private def transformOpenAiToolChoiceToAnthropic(body: JsObject): JsObject = {
    body.select("tool_choice").asOptString match {
      case Some("none")     => body ++ Json.obj("tool_choice" -> Json.obj("type" -> "none"))
      case Some("auto")     => body ++ Json.obj("tool_choice" -> Json.obj("type" -> "auto"))
      case Some("required") => body ++ Json.obj("tool_choice" -> Json.obj("type" -> "any"))
      case Some(_)          => body // unknown string, leave as-is
      case None =>
        body.select("tool_choice").asOpt[JsObject] match {
          case None => body // no tool_choice provided
          case Some(obj) =>
            obj.select("type").asOptString match {
              case Some("function") =>
                obj.select("function").select("name").asOpt[String].orElse(obj.select("name").asOpt[String]) match {
                  case Some(name) => body ++ Json.obj("tool_choice" -> Json.obj("type" -> "tool", "name" -> name))
                  case None => body ++ Json.obj("tool_choice" -> Json.obj("type" -> "auto"))
                }
              case _ => body // already in the Anthropic format (auto | any | tool | none) or unknown
            }
        }
    }
  }

  // temperature, top_p and top_k are deprecated/removed starting from Claude Opus 4.7 and
  // will make the API respond with a 400. We strip them automatically for the impacted models.
  def cleanupDeprecatedSamplingParams(body: JsObject): JsObject = {
    val model = body.select("model").asOptString.getOrElse("")
    if (AnthropicModels.dropsSamplingParams(model)) {
      body - "temperature" - "top_p" - "top_k"
    } else {
      body
    }
  }

  // Client-provided history uses the OpenAI shape: assistant messages carry a `tool_calls` array and tool
  // results come as a dedicated `tool` role message. Anthropic instead expects tool calls as `tool_use`
  // content blocks inside the assistant message and tool results as `tool_result` content blocks inside a
  // user message. We translate the serialized messages here (and group consecutive tool results into a
  // single user message, as Anthropic requires).
  private def transformOpenAiMessagesToAnthropic(messages: Seq[JsObject]): Seq[JsObject] = {
    def isToolResultUserMessage(m: JsObject): Boolean =
      m.select("role").asOptString.contains("user") &&
        m.select("content").asOpt[Seq[JsObject]].exists(blocks => blocks.nonEmpty && blocks.forall(_.select("type").asOptString.contains("tool_result")))
    val result = scala.collection.mutable.ArrayBuffer.empty[JsObject]
    messages.foreach { message =>
      message.select("role").asOptString.getOrElse("user") match {
        case "tool" =>
          val toolResultContent: String = message.select("content").asOptString.getOrElse("")
          val toolResult = Json.obj(
            "type" -> "tool_result",
            "tool_use_id" -> message.select("tool_call_id").asString,
            "content" -> toolResultContent,
          )
          result.lastOption match {
            case Some(last) if isToolResultUserMessage(last) =>
              val newContent = last.select("content").as[Seq[JsObject]] :+ toolResult
              result(result.length - 1) = last ++ Json.obj("content" -> JsArray(newContent))
            case _ =>
              result += Json.obj("role" -> "user", "content" -> Json.arr(toolResult))
          }
        case "assistant" if message.select("tool_calls").asOpt[Seq[JsObject]].exists(_.nonEmpty) =>
          val textBlocks: Seq[JsValue] = message.select("content").asOptString match {
            case Some(text) if text.nonEmpty => Seq(Json.obj("type" -> "text", "text" -> text))
            case _ => message.select("content").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
          }
          val toolUseBlocks: Seq[JsValue] = message.select("tool_calls").as[Seq[JsObject]].map { tc =>
            val arguments = tc.select("function").select("arguments").asOptString.getOrElse("{}")
            val input = scala.util.Try(arguments.parseJson).getOrElse(Json.obj())
            Json.obj(
              "type" -> "tool_use",
              "id" -> tc.select("id").asString,
              "name" -> tc.select("function").select("name").asString,
              "input" -> input,
            )
          }
          result += Json.obj("role" -> "assistant", "content" -> JsArray(textBlocks ++ toolUseBlocks))
        case _ =>
          result += (message - "tool_calls" - "tool_call_id")
      }
    }
    result.toList
  }

  override def call(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val obody = originalBody.asObject - "messages" - "provider"
    val mergedOptions = (if (options.allowConfigOverride) options.jsonForCall.deepMerge(obody) else options.jsonForCall).applyOn(transformOpenAIInputBodyToProviderInputBody).applyOn(cleanupDeprecatedSamplingParams)
    val finalModel = mergedOptions.select("model").asOptString.orElse(computeModel(mergedOptions)).getOrElse("--")
    val (system, otherMessages) = prompt.messages.partition(_.isSystem)
    val messages = JsArray(transformOpenAiMessagesToAnthropic(prompt.copy(messages = otherMessages).jsonWithFlavor(ChatMessageContentFlavor.Anthropic).as[Seq[JsObject]]))
    val systemMessages = JsArray(system.map(_.json(ChatMessageContentFlavor.Anthropic)))
    val startTime = System.currentTimeMillis()
    val acc = new UsageAccumulator()
    val hasToolsInRequest = obody.select("tools").asOpt[JsArray].exists(_.value.nonEmpty)
    val callF = if (!hasToolsInRequest && api.supportsTools && (options.wasmTools.nonEmpty || options.mcpConnectors.nonEmpty || options.a2aConnectors.nonEmpty || options.searchEngines.nonEmpty)) {
      attrs.put(com.cloud.apim.otoroshi.extensions.aigateway.entities.A2ASupport.A2AConnectorsKey -> options.a2aConnectors)
      val tools = LlmFunctions.toolsAnthropicWithInline(options.wasmToolsNoInline, options.wasmToolsInline, options.mcpConnectors, options.mcpIncludeFunctions, options.mcpExcludeFunctions, attrs, options.searchEngines)
      val nameToFunction = LlmFunctions.nameToFunction(options.wasmToolsNoInline)
      api.callWithToolSupport("POST", "/v1/messages", Some(mergedOptions ++ tools ++ Json.obj("messages" -> messages, "system" -> systemMessages)), options.mcpConnectors, attrs, nameToFunction, options.maxFunctionCalls, 0, acc)
    } else {
      api.call("POST", "/v1/messages", Some(mergedOptions ++ Json.obj("messages" -> messages, "system" -> systemMessages)), acc)
    }
    callF.map {
      case Left(err) => err.left
      case Right(resp) =>
        val usage = ChatResponseMetadata(
          ChatResponseMetadataRateLimit(
            requestsLimit = resp.headers.getIgnoreCase("anthropic-ratelimit-requests-limit").map(_.toLong).getOrElse(-1L),
            requestsRemaining = resp.headers.getIgnoreCase("anthropic-ratelimit-requests-remaining").map(_.toLong).getOrElse(-1L),
            tokensLimit = resp.headers.getIgnoreCase("anthropic-ratelimit-tokens-limit").map(_.toLong).getOrElse(-1L),
            tokensRemaining = resp.headers.getIgnoreCase("anthropic-ratelimit-tokens-remaining").map(_.toLong).getOrElse(-1L),
          ),
          acc.usage(),
          None
        )
        val duration: Long = System.currentTimeMillis() - startTime //resp.headers.getIgnoreCase("anthropic-processing-ms").map(_.toLong).getOrElse(0L)
        val slug = Json.obj(
          "provider_kind" -> "anthropic",
          "provider" -> id,
          "duration" -> duration,
          "model" -> finalModel.json,
          "rate_limit" -> usage.rateLimit.json,
          "usage" -> usage.usage.json,
        ).applyOnWithOpt(usage.cache) {
          case (obj, cache) => obj ++ Json.obj("cache" -> cache.json)
        }
        attrs.update(ChatClient.ApiUsageKey -> usage)
        attrs.update(otoroshi.plugins.Keys.ExtraAnalyticsDataKey) {
          case Some(obj @ JsObject(_)) => {
            val arr = obj.select("ai").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
            val newArr = arr ++ Seq(slug)
            obj ++ Json.obj("ai" -> newArr)
          }
          case Some(other) => other
          case None => Json.obj("ai" -> Seq(slug))
        }
        val role = resp.body.select("role").asOpt[String].getOrElse("assistant")
        val contentBlocks = resp.body.select("content").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
        val messages = if (contentBlocks.exists(_.select("type").asOptString.contains("tool_use"))) {
          // Anthropic returns tool calls as `tool_use` content blocks. We fold every block into a single
          // OpenAI-style assistant message (text blocks -> content, tool_use blocks -> tool_calls) so the
          // downstream OpenAI serialization emits `tool_calls` and a `tool_calls` finish_reason.
          val textContent = contentBlocks
            .filter(_.select("type").asOptString.contains("text"))
            .flatMap(_.select("text").asOpt[String])
            .mkString
          val toolCalls = contentBlocks
            .filter(_.select("type").asOptString.contains("tool_use"))
            .map { block =>
              Json.obj(
                "id" -> block.select("id").asString,
                "type" -> "function",
                "function" -> Json.obj(
                  "name" -> block.select("name").asString,
                  "arguments" -> block.select("input").asOpt[JsValue].getOrElse(Json.obj()).stringify,
                ),
              )
            }
          val messageObj = Json.obj(
            "role" -> role,
            "content" -> textContent,
            "tool_calls" -> JsArray(toolCalls),
          )
          Seq(ChatGeneration(ChatMessage.output(role, textContent, None, Json.obj("message" -> messageObj))))
        } else {
          contentBlocks.map { obj =>
            val content = obj.select("text").asOpt[String].getOrElse("")
            ChatGeneration(ChatMessage.output(role, content, None, obj))
          }
        }
        Right(ChatResponse(messages, usage, resp.body))
    }
  }

  override def stream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    val body = originalBody.asObject - "messages" - "provider"
    val mergedOptions = (if (options.allowConfigOverride) options.jsonForCall.deepMerge(body) else options.jsonForCall).applyOn(transformOpenAIInputBodyToProviderInputBody).applyOn(cleanupDeprecatedSamplingParams)
    val finalModel = mergedOptions.select("model").asOptString.orElse(computeModel(mergedOptions)).getOrElse("--")
    val (system, otherMessages) = prompt.messages.partition(_.isSystem)
    val messages = JsArray(transformOpenAiMessagesToAnthropic(prompt.copy(messages = otherMessages).jsonWithFlavor(ChatMessageContentFlavor.Anthropic).as[Seq[JsObject]]))
    val systemMessages = JsArray(system.map(_.json(ChatMessageContentFlavor.Anthropic)))
    val startTime = System.currentTimeMillis()
    val acc = new UsageAccumulator()
    val hasToolsInRequest = body.select("tools").asOpt[JsArray].exists(_.value.nonEmpty)
    val callF = if (!hasToolsInRequest && api.supportsTools && (options.wasmTools.nonEmpty || options.mcpConnectors.nonEmpty || options.a2aConnectors.nonEmpty || options.searchEngines.nonEmpty)) {
      attrs.put(com.cloud.apim.otoroshi.extensions.aigateway.entities.A2ASupport.A2AConnectorsKey -> options.a2aConnectors)
      val tools = LlmFunctions.toolsAnthropic(options.wasmTools, options.mcpConnectors, options.mcpIncludeFunctions, options.mcpExcludeFunctions, attrs, options.searchEngines)
      val nameToFunction = LlmFunctions.nameToFunction(options.wasmToolsNoInline)
      api.streamWithToolSupport("POST", "/v1/messages", Some(mergedOptions ++ tools ++ Json.obj("messages" -> messages, "system" -> systemMessages)), options.mcpConnectors, attrs, nameToFunction, options.maxFunctionCalls, 0, acc)
    } else {
      api.stream("POST", "/v1/messages", Some(mergedOptions ++ Json.obj("messages" -> messages, "system" -> systemMessages)), acc)
    }
    callF.map {
      case Left(err) => err.left
      case Right((source, resp)) =>
        // OpenAI streams tool calls as `tool_calls` deltas indexed by tool-call ordinal. Anthropic instead
        // streams a `content_block_start` (with the tool id/name) followed by `input_json_delta` fragments
        // for the arguments. We translate them and keep track of the current tool-call ordinal.
        val toolCallOrdinal = new java.util.concurrent.atomic.AtomicInteger(-1)
        source.filterNot { chunk =>
          if (chunk.usage.nonEmpty) {
            val usage = ChatResponseMetadata(
              ChatResponseMetadataRateLimit(
                requestsLimit = resp.header("x-ratelimit-limit-requests").map(_.toLong).getOrElse(-1L),
                requestsRemaining = resp.header("x-ratelimit-remaining-requests").map(_.toLong).getOrElse(-1L),
                tokensLimit = resp.header("x-ratelimit-limit-tokens").map(_.toLong).getOrElse(-1L),
                tokensRemaining = resp.header("x-ratelimit-remaining-tokens").map(_.toLong).getOrElse(-1L),
              ),
              acc.usage(),
              None
            )
            val duration: Long = System.currentTimeMillis() - startTime //resp.header("openai-processing-ms").map(_.toLong).getOrElse(0L)
            val slug = Json.obj(
              "provider_kind" -> "anthropic",
              "provider" -> id,
              "duration" -> duration,
              "model" -> finalModel.json,
              "rate_limit" -> usage.rateLimit.json,
              "usage" -> usage.usage.json
            ).applyOnWithOpt(usage.cache) {
              case (obj, cache) => obj ++ Json.obj("cache" -> cache.json)
            }
            attrs.update(ChatClient.ApiUsageKey -> usage)
            attrs.update(otoroshi.plugins.Keys.ExtraAnalyticsDataKey) {
              case Some(obj@JsObject(_)) => {
                val arr = obj.select("ai").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
                val newArr = arr ++ Seq(slug)
                obj ++ Json.obj("ai" -> newArr)
              }
              case Some(other) => other
              case None => Json.obj("ai" -> Seq(slug))
            }
            true
          } else {
            false
          }
        }
        .map { chunk =>
          ChatResponseChunk(
            id = chunk.id,
            created = if (chunk.created <= 0L) startTime / 1000L else chunk.created,
            model = chunk.model,
            choices = chunk.choices.map { choice =>
              val toolCalls: Seq[ChatResponseChunkChoiceDeltaToolCall] = if (choice.tool_use) {
                // content_block_start for a tool_use block: carries the tool id and name
                val idx = toolCallOrdinal.incrementAndGet()
                val block = choice.contentBlockObj
                Seq(ChatResponseChunkChoiceDeltaToolCall(
                  index = idx.toLong,
                  id = block.select("id").asOptString,
                  typ = Some("function"),
                  function = ChatResponseChunkChoiceDeltaToolCallFunction(
                    nameOpt = block.select("name").asOptString,
                    arguments = "",
                  ),
                ))
              } else if (choice.input_json_delta) {
                // content_block_delta with a JSON fragment of the current tool's arguments
                Seq(ChatResponseChunkChoiceDeltaToolCall(
                  index = math.max(toolCallOrdinal.get(), 0).toLong,
                  id = None,
                  typ = None,
                  function = ChatResponseChunkChoiceDeltaToolCallFunction(
                    nameOpt = None,
                    arguments = choice.partial_json,
                  ),
                ))
              } else {
                Seq.empty
              }
              ChatResponseChunkChoice(
                index = choice.index.map(_.toLong).getOrElse(0L),
                delta = ChatResponseChunkChoiceDelta(
                  content = if (toolCalls.nonEmpty) None else choice.content,
                  tool_calls = toolCalls,
                ),
                finishReason = choice.finish_reason.map(fr => if (fr == "tool_use") "tool_calls" else fr),
              )
            }
          )
        }.right
    }
  }
}

