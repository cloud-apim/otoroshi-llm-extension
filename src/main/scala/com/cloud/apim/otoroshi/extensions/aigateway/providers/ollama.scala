package com.cloud.apim.otoroshi.extensions.aigateway.providers

import akka.stream.scaladsl.{Framing, Source}
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway._
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{GenericApiResponseChoiceMessageToolCall, WasmFunction}
import org.joda.time.DateTime
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.libs.ws.WSResponse

import scala.concurrent._
import scala.concurrent.duration._

case class OllamaAiApiResponseChoiceMessageToolCallFunction(raw: JsObject) {
  lazy val name: String = raw.select("name").asString
  lazy val arguments: JsObject = raw.select("arguments").asObject
}

case class OllamaAiApiResponseChoiceMessageToolCall(raw: JsObject) {
  lazy val id: String = raw.select("id").asString
  lazy val function: OllamaAiApiResponseChoiceMessageToolCallFunction = OllamaAiApiResponseChoiceMessageToolCallFunction(raw.select("function").asObject)
}

case class OllamaAiApiResponseMessage(raw: JsValue) {
  lazy val role: String = raw.select("role").asOpt[String].getOrElse("assistant")
  lazy val content: Option[String] = raw.select("content").asOpt[String]
  lazy val toolCalls: Seq[OllamaAiApiResponseChoiceMessageToolCall] = raw.select("tool_calls").asOpt[Seq[JsObject]].map(_.map(v => OllamaAiApiResponseChoiceMessageToolCall(v))).getOrElse(Seq.empty)
  lazy val hasToolCalls: Boolean = toolCalls.nonEmpty
}

case class OllamaAiApiResponse(status: Int, headers: Map[String, String], body: JsValue, response: WSResponse) {
  lazy val finishBecauseOfToolCalls: Boolean = doneReason == "stop" && message.hasToolCalls
  lazy val message: OllamaAiApiResponseMessage = OllamaAiApiResponseMessage(body.select("message").asObject)
  lazy val doneReason: String = body.select("done_reason").asOpt[String].getOrElse("")
  def toolCalls: Seq[OllamaAiApiResponseChoiceMessageToolCall] = message.toolCalls
  def json: JsValue = Json.obj(
    "status" -> status,
    "headers" -> headers,
    "body" -> body,
  )
}

case class OllamaAiChatResponseChunkMessage(raw: JsValue) {
  lazy val role: String = raw.select("role").asOpt[String].getOrElse("assistant")
  lazy val content: String = raw.select("content").asOpt[String].getOrElse("")
}

case class OllamaAiChatResponseChunk(raw: JsValue) {
  lazy val model: String = raw.select("model").asString
  lazy val done: Boolean = raw.select("done").asOptBoolean.getOrElse(false)
  lazy val created_at: String = raw.select("created_at").asString
  lazy val created_at_datetime: DateTime = DateTime.parse(created_at)
  lazy val message: OllamaAiChatResponseChunkMessage = OllamaAiChatResponseChunkMessage(raw.select("message").asObject)
  lazy val total_duration: Option[Long] = raw.select("total_duration").asOpt[Long]
  lazy val load_duration: Option[Long] = raw.select("load_duration").asOpt[Long]
  lazy val prompt_eval_count: Option[Long] = raw.select("prompt_eval_count").asOpt[Long]
  lazy val prompt_eval_duration: Option[Long] = raw.select("prompt_eval_duration").asOpt[Long]
  lazy val eval_count: Option[Long] = raw.select("eval_count").asOpt[Long]
  lazy val eval_duration: Option[Long] = raw.select("eval_duration").asOpt[Long]
}

object OllamaAiApi {
  val baseUrl = "http://localhost:11434"
  val logger = Logger("ollama-logger")
}
class OllamaAiApi(baseUrl: String = OllamaAiApi.baseUrl, token: Option[String], timeout: FiniteDuration = 10.seconds, env: Env) extends ApiClient[OllamaAiApiResponse, OllamaAiChatResponseChunk] {

  override def supportsTools: Boolean = true
  override def supportsStreaming: Boolean = true

  def rawCall(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[WSResponse] = {
    // println("\n\n================================\n")
    // println(s"calling ${method} ${baseUrl}${path}: ${body.getOrElse(Json.obj()).prettify}")
    // println("calling ollama")
    OllamaAiApi.logger.debug(s"calling ollama: ${body.get.prettify}")
    env.Ws
      .url(s"${baseUrl}${path}")
      .withHttpHeaders(
        "Accept" -> "application/json",
      )
      .applyOnWithOpt(token) {
        case (builder, token) => builder
          .addHttpHeaders(
            "Authorization" -> s"Bearer ${token}",
          )
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

  def call(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[OllamaAiApiResponse] = {
    rawCall(method, path, body)
      .map { resp =>
        OllamaAiApiResponse(resp.status, resp.headers.mapValues(_.last), resp.json, resp)
      }
  }

  override def stream(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[(Source[OllamaAiChatResponseChunk, _], WSResponse)] = {
    OllamaAiApi.logger.debug(s"streaming ollama: ${body.map(_.prettify).getOrElse("")}")
    env.Ws
      .url(s"${baseUrl}${path}")
      .withHttpHeaders(
        "Accept" -> "application/json",
      )
      .applyOnWithOpt(token) {
        case (builder, token) => builder
          .addHttpHeaders(
            "Authorization" -> s"Bearer ${token}",
          )
      }
      .applyOnWithOpt(body) {
        case (builder, body) => builder
          .addHttpHeaders("Content-Type" -> "application/json")
          .withBody(body)
      }
      .withMethod(method)
      .withRequestTimeout(timeout)
      .stream()
      .map { resp =>
        (resp.bodyAsSource
          .via(Framing.delimiter(ByteString("\n"), Int.MaxValue, false))
          .map(_.utf8String)
          .filter(_.nonEmpty)
          .map(str => Json.parse(str))
          .map(json => OllamaAiChatResponseChunk(json))
          .takeWhile(!_.done, inclusive = true)
        , resp)
      }
  }

  override def callWithToolSupport(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[OllamaAiApiResponse] = {
    // TODO: accumulate consumptions ???
    if (body.flatMap(_.select("tools").asOpt[JsArray]).exists(_.value.nonEmpty)) {
      call(method, path, body).flatMap {
        case resp if resp.finishBecauseOfToolCalls => {
          body match {
            case None => resp.vfuture
            case Some(body) => {
              val messages = body.select("messages").asOpt[Seq[JsObject]].map(v => v.flatMap(o => ChatMessage.format.reads(o).asOpt)).getOrElse(Seq.empty)
              val toolCalls = resp.toolCalls
              WasmFunction.callToolsOllama(toolCalls.map(tc => GenericApiResponseChoiceMessageToolCall(tc.raw)))(ec, env)
                .flatMap { callResps =>
                  val newMessages: Seq[JsValue] = messages.map(_.json) ++ callResps
                  val newBody = body.asObject ++ Json.obj("messages" -> JsArray(newMessages))
                  callWithToolSupport(method, path, newBody.some)
                }
            }
          }
        }
        case resp => resp.vfuture
      }
    } else {
      call(method, path, body)
    }
  }

  override def streamWithToolSupport(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[(Source[OllamaAiChatResponseChunk, _], WSResponse)] = {
    callWithToolSupport(method, path, body).map { resp =>
      val source = resp.message.content.get.chunks(5).map { str =>
        OllamaAiChatResponseChunk(resp.body.asObject.deepMerge(Json.obj(
          "message" -> Json.obj("content" -> str),
          "done" -> false,
        )))
      }.concat(Source.single(OllamaAiChatResponseChunk(resp.body.asObject.deepMerge(Json.obj(
        "message" -> Json.obj("content" -> ""),
        "done" -> true
      )))))
      (source, resp.response)
    }
  }
}

object OllamaAiChatClientOptions {
  def fromJson(json: JsValue): OllamaAiChatClientOptions = {
    OllamaAiChatClientOptions(
      model = json.select("model").asOpt[String].getOrElse("llama3.2"),
      num_predict = json.select("num_predict").asOpt[Int],
      tfs_z = json.select("tfs_z").asOpt[Double],
      seed = json.select("seed").asOpt[Int],
      temper = json.select("temperature").asOpt[Double].getOrElse(0.7),
      top_p = json.select("top_p").asOpt[Double].getOrElse(0.9),
      top_k = json.select("top_k").asOpt[Int].getOrElse(40),
      repeat_penalty = json.select("repeat_penalty").asOpt[Double],
      repeat_last_n = json.select("repeat_last_n").asOpt[Int],
      num_thread = json.select("num_thread").asOpt[Int],
      num_gpu = json.select("num_gpu").asOpt[Int],
      num_gqa = json.select("num_gqa").asOpt[Int],
      num_ctx = json.select("num_ctx").asOpt[Int],
      wasmTools = json.select("wasm_tools").asOpt[Seq[String]].getOrElse(Seq.empty),
      allowConfigOverride = json.select("allow_config_override").asOptBoolean.getOrElse(true),
    )
  }
}

// https://github.com/ollama/ollama/blob/main/docs/modelfile.md#valid-parameters-and-values
case class OllamaAiChatClientOptions(
   model: String = "llama3.2",
   num_predict: Option[Int] = None,
   tfs_z: Option[Double] = None,
   seed: Option[Int] = None,
   temper: Double = 0.7,
   top_p: Double = 0.9,
   top_k: Int = 40,
   repeat_penalty: Option[Double] = None,
   repeat_last_n: Option[Int] = None,
   num_thread: Option[Int] = None,
   num_gpu: Option[Int] = None,
   num_gqa: Option[Int] = None,
   num_ctx: Option[Int] = None,
   wasmTools: Seq[String] = Seq.empty,
   allowConfigOverride: Boolean = true,
) extends ChatOptions {

  def temperature: Float = temper.toFloat
  def topP: Float = top_p.toFloat
  def topK: Int = top_k

  override def json: JsObject = Json.obj(
    "model" -> model,
    "num_predict" -> num_predict,
    "seed" -> seed,
    "temperature" -> temper,
    "top_p" -> top_p,
    "top_k" -> top_k,
    "repeat_penalty" -> repeat_penalty,
    "repeat_last_n" -> repeat_last_n,
    "num_thread" -> num_thread,
    "num_gpu" -> num_gpu,
    "num_gqa" -> num_gqa,
    "num_ctx" -> num_ctx,
    "wasm_tools" -> JsArray(wasmTools.map(_.json)),
    "allow_config_override" -> allowConfigOverride,
  )

  def jsonForCall: JsObject = json - "wasm_tools" - "allow_config_override"
}

class OllamaAiChatClient(api: OllamaAiApi, options: OllamaAiChatClientOptions, id: String) extends ChatClient {

  override def model: Option[String] = options.model.some
  override def supportsStreaming: Boolean = api.supportsStreaming
  override def supportsTools: Boolean = api.supportsTools

  override def listModels()(implicit ec: ExecutionContext): Future[Either[JsValue, List[String]]] = {
    api.rawCall("GET", "/api/tags", None).map { resp =>
      if (resp.status == 200) {
        Right(resp.json.select("models").as[List[JsObject]].map(obj => obj.select("name").asString))
      } else {
        Left(Json.obj("error" -> s"bad response code: ${resp.status}"))
      }
    }
  }

  override def call(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val obody = originalBody.asObject - "messages" - "provider"
    val mergedOptions = (if (options.allowConfigOverride) options.jsonForCall.deepMerge(obody) else options.json) - "model"
    val callF = if (api.supportsTools && options.wasmTools.nonEmpty) {
      val tools = WasmFunction.tools(options.wasmTools)
      api.callWithToolSupport("POST", "/api/chat", Some(Json.obj(
        "model" -> mergedOptions.select("model").asOptString.getOrElse(options.model).asInstanceOf[String],
        "stream" -> false,
        "messages" -> prompt.json,
        "options" -> mergedOptions,
      ) ++ tools))
    } else {
      api.call("POST", "/api/chat", Some(Json.obj(
        "model" -> options.model,
        "stream" -> false,
        "messages" -> prompt.json,
        "options" -> mergedOptions
      )))
    }
    callF.map { resp =>
      val usage = ChatResponseMetadata(
        ChatResponseMetadataRateLimit(
          requestsLimit = resp.headers.getIgnoreCase("x-ratelimit-limit-requests").map(_.toLong).getOrElse(-1L),
          requestsRemaining = resp.headers.getIgnoreCase("x-ratelimit-remaining-requests").map(_.toLong).getOrElse(-1L),
          tokensLimit = resp.headers.getIgnoreCase("x-ratelimit-limit-tokens").map(_.toLong).getOrElse(-1L),
          tokensRemaining = resp.headers.getIgnoreCase("x-ratelimit-remaining-tokens").map(_.toLong).getOrElse(-1L),
        ),
        ChatResponseMetadataUsage(
          promptTokens = resp.body.select("prompt_eval_count").asOpt[Long].getOrElse(-1L),
          generationTokens = resp.body.select("eval_count").asOpt[Long].getOrElse(-1L),
        ),
        None
      )
      val duration: Long = resp.body.select("total_duration").asOpt[Long].map(_ / 100000).getOrElse(-1L)
      val slug = Json.obj(
        "provider_kind" -> "ollama",
        "provider" -> id,
        "duration" -> duration,
        "model" -> options.model.json,
        "rate_limit" -> usage.rateLimit.json,
        "usage" -> usage.usage.json
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
      val role = resp.body.select("message").select("role").asOpt[String].getOrElse("user")
      val content = resp.body.select("message").select("content").asOpt[String].getOrElse("")
      val message = ChatGeneration(ChatMessage(role, content))
      Right(ChatResponse(Seq(message), usage))
    }
  }

  override def stream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    val obody = originalBody.asObject - "messages" - "provider"
    val mergedOptions = (if (options.allowConfigOverride) options.jsonForCall.deepMerge(obody) else options.json) - "model"
    api.stream("POST", "/api/chat", Some(Json.obj(
      "model" -> mergedOptions.select("model").asOptString.getOrElse(options.model).asInstanceOf[String],
      "stream" -> true,
      "messages" -> prompt.json,
      "options" -> mergedOptions
    ))).map {
      case (source, resp) =>
        source
          .filterNot { chunk =>
            if (chunk.eval_count.nonEmpty) {
              val usage = ChatResponseMetadata(
                ChatResponseMetadataRateLimit(
                  requestsLimit = resp.header("x-ratelimit-limit-requests").map(_.toLong).getOrElse(-1L),
                  requestsRemaining = resp.header("x-ratelimit-remaining-requests").map(_.toLong).getOrElse(-1L),
                  tokensLimit = resp.header("x-ratelimit-limit-tokens").map(_.toLong).getOrElse(-1L),
                  tokensRemaining = resp.header("x-ratelimit-remaining-tokens").map(_.toLong).getOrElse(-1L),
                ),
                ChatResponseMetadataUsage(
                  promptTokens = chunk.prompt_eval_count.getOrElse(-1L),
                  generationTokens = chunk.eval_count.getOrElse(-1L),
                ),
                None
              )
              val duration: Long = chunk.total_duration.getOrElse(0L)
              val slug = Json.obj(
                "provider_kind" -> "ollama",
                "provider" -> id,
                "duration" -> duration,
                "model" -> options.model.json,
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
          .zipWithIndex
          .map {
            case (chunk, idx) =>
              ChatResponseChunk(
                id = chunk.created_at.sha256,
                created = chunk.created_at_datetime.toDate.getTime / 1000,
                model = chunk.model,
                choices = Seq(ChatResponseChunkChoice(
                  index = idx,
                  delta = ChatResponseChunkChoiceDelta(
                    chunk.message.content.some
                  ),
                  finishReason = if (chunk.done) Some("stop") else None
                ))
              )
          }.right
    }
  }
}
