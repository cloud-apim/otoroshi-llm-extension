package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import akka.stream.scaladsl.{Sink, Source}
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatClient, ChatPrompt, ChatResponse, ChatResponseChunk}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import io.azam.ulidj.ULID
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths, StandardOpenOption}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Future}
import otoroshi.utils.syntax.implicits._

object ChatClientWithRequestResponseLogging {

  val enabledEnvVar = "OTOROSHI_LLM_REQUEST_RESPONSE_LOGGING_ENABLED"
  val dirEnvVar     = "OTOROSHI_LLM_REQUEST_RESPONSE_LOGGING_DIR"

  def applyIfPossible(tuple: (AiProvider, ChatClient, Env)): ChatClient = {
    val enabled = sys.env.get(enabledEnvVar)
      .orElse(sys.props.get(enabledEnvVar))
      .exists(v => v == "true" || v == "1")
    if (enabled) {
      new ChatClientWithRequestResponseLogging(tuple._1, tuple._2)
    } else {
      tuple._2
    }
  }
}

/**
 * Decorator that logs every call/stream interaction to a file when
 * OTOROSHI_LLM_REQUEST_RESPONSE_LOGGING_ENABLED=true.
 *
 * Each log file is named:
 *   {provider_id}_{provider_name}_{llm_name}_{date}.log
 *
 * Each line in the file is a JSON object with:
 *   - "raw_request"  : the original body received from the client
 *   - "llm_request"  : the parsed prompt (messages) sent down the decorator chain to the LLM
 *   - "raw_response" : the raw JSON response returned by the LLM provider
 *   - "response"     : the normalised response (generations) sent back to the client
 *
 * Log directory can be customised with OTOROSHI_LLM_REQUEST_RESPONSE_LOGGING_DIR
 * (defaults to /tmp/llm-logs).
 */
class ChatClientWithRequestResponseLogging(originalProvider: AiProvider, val chatClient: ChatClient) extends DecoratorChatClient {

  private val dateFormatter      = DateTimeFormatter.ofPattern("yyyy-MM-dd")
  private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")

  private def logDir: String =
    sys.env.get(ChatClientWithRequestResponseLogging.dirEnvVar)
      .orElse(sys.props.get(ChatClientWithRequestResponseLogging.dirEnvVar))
      .getOrElse("/tmp/llm-logs")

  private def sanitize(s: String): String = s.replaceAll("[^a-zA-Z0-9_-]", "_")

  private def resolveLogFile(attrs: TypedMap): java.nio.file.Path = {
    val date         = LocalDateTime.now().format(dateFormatter)
    val providerId   = sanitize(originalProvider.id)
    val providerName = sanitize(originalProvider.name)
    val llmName      = sanitize(originalProvider.provider)
    val dir          = Paths.get(logDir)
    val reqId        = attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).getOrElse(ULID.random().toString)
    if (!Files.exists(dir)) Files.createDirectories(dir)
    dir.resolve(s"${providerId}_${providerName}_${llmName}_${date}_${reqId}.json")
  }

  private def writeEntry(entry: JsObject, attrs: TypedMap): Unit = {
    try {
      val line = entry.prettify + "\n"
      Files.write(
        resolveLogFile(attrs),
        line.getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND,
      )
    } catch {
      case _: Exception => // silently ignore write errors to avoid impacting the request
    }
  }

  private def now(): String = LocalDateTime.now().format(timestampFormatter)

  private def baseFields: JsObject = Json.obj(
    "provider_id"   -> originalProvider.id,
    "provider_name" -> originalProvider.name,
    "provider_llm"  -> originalProvider.provider,
  )

  // ---- call ---------------------------------------------------------------

  override def call(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val requestedAt = now()
    writeEntry(baseFields ++ Json.obj(
      "timestamp"   -> requestedAt,
      "type"        -> "request",
      "kind"        -> "call",
      "raw_request" -> originalBody,          // body as received from the client
      "llm_request" -> prompt.json,           // parsed messages forwarded to the LLM
    ), attrs)
    chatClient.call(prompt, attrs, originalBody).map {
      case Left(err) =>
        writeEntry(baseFields ++ Json.obj(
          "timestamp" -> now(),
          "type"      -> "error",
          "kind"      -> "call",
          "error"     -> err,
        ), attrs)
        Left(err)
      case Right(resp) =>
        writeEntry(baseFields ++ Json.obj(
          "timestamp"    -> now(),
          "type"         -> "response",
          "kind"         -> "call",
          "raw_response" -> resp.raw,                                           // raw JSON from the LLM
          "response"     -> JsArray(resp.generations.map(_.json)),              // normalised generations sent to client
        ), attrs)
        Right(resp)
    }
  }

  // ---- stream -------------------------------------------------------------

  override def stream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    val requestedAt = now()
    writeEntry(baseFields ++ Json.obj(
      "timestamp"   -> requestedAt,
      "type"        -> "request",
      "kind"        -> "stream",
      "raw_request" -> originalBody,          // body as received from the client
      "llm_request" -> prompt.json,           // parsed messages forwarded to the LLM
    ), attrs)
    chatClient.stream(prompt, attrs, originalBody).map {
      case Left(err) =>
        writeEntry(baseFields ++ Json.obj(
          "timestamp" -> now(),
          "type"      -> "error",
          "kind"      -> "stream",
          "error"     -> err,
        ), attrs)
        Left(err)
      case Right(src) =>
        val chunks = ListBuffer.empty[ChatResponseChunk]
        Right(
          src
            .map { chunk =>
              chunks += chunk
              chunk
            }
            .alsoTo(Sink.onComplete { _ =>
              // For streaming the chunks are the normalised objects from the provider;
              // they represent both the raw LLM output and what gets forwarded to the client.
              writeEntry(baseFields ++ Json.obj(
                "timestamp"    -> now(),
                "type"         -> "response",
                "kind"         -> "stream",
                "raw_response" -> JsArray(chunks.map(_.json(env)).toSeq),   // chunks as returned by the LLM provider
                "response"     -> JsArray(chunks.map(_.openaiJson(env)).toSeq), // chunks forwarded to the client (OpenAI format)
              ), attrs)
            })
        )
    }
  }
}
