package com.cloud.apim.otoroshi.extensions.aigateway.providers

import akka.stream.scaladsl.Source
import com.cloud.apim.otoroshi.extensions.aigateway.ChatResponseMetadataUsage
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.{JsValue, Json}
import play.api.libs.ws.WSResponse

import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.{ExecutionContext, Future}

trait ApiClient[Resp, Chunk] {

  def supportsTools: Boolean
  def supportsStreaming: Boolean
  def supportsCompletion: Boolean

  def call(method: String, path: String, body: Option[JsValue], acc: UsageAccumulator)(implicit ec: ExecutionContext): Future[Either[JsValue, Resp]]
  def callWithToolSupport(method: String, path: String, body: Option[JsValue], mcpConnectors: Seq[String], attrs: TypedMap, nameToFunction: Map[String, String], maxCalls: Int, currentCallCounter: Int, acc: UsageAccumulator)(implicit ec: ExecutionContext): Future[Either[JsValue, Resp]] = {
    call(method, path, body, acc)
  }

  def stream(method: String, path: String, body: Option[JsValue], acc: UsageAccumulator)(implicit ec: ExecutionContext): Future[Either[JsValue, (Source[Chunk, _], WSResponse)]]
  def streamWithToolSupport(method: String, path: String, body: Option[JsValue], mcpConnectors: Seq[String], attrs: TypedMap, nameToFunction: Map[String, String], maxCalls: Int, currentCallCounter: Int, acc: UsageAccumulator)(implicit ec: ExecutionContext): Future[Either[JsValue, (Source[Chunk, _], WSResponse)]] = {
    stream(method, path, body, acc)
  }
}

trait NoStreamingApiClient[Resp] {

  def supportsTools: Boolean
  def supportsCompletion: Boolean
  final def supportsStreaming: Boolean = false

  def call(method: String, path: String, body: Option[JsValue], acc: UsageAccumulator)(implicit ec: ExecutionContext): Future[Either[JsValue, Resp]]
  def callWithToolSupport(method: String, path: String, body: Option[JsValue], mcpConnectors: Seq[String], attrs: TypedMap, nameToFunction: Map[String, String], maxCalls: Int, currentCallCounter: Int, acc: UsageAccumulator)(implicit ec: ExecutionContext): Future[Either[JsValue, Resp]] = {
    call(method, path, body, acc)
  }
}


object ProviderHelpers {
  def responseBody(resp: WSResponse): JsValue = {
    if (resp.contentType.contains("application/json")) {
      resp.json
    } else {
      Json.parse(resp.body)
    }
  }
  def logBadResponse(from: String, resp: WSResponse): Unit = {
    AiExtension.logger.error(s"Response with error from '${from}': ${resp.status} - ${resp.body}")
  }
  def logCall(from: String, method: String, url: String, body: Option[JsValue] = None, opts: Map[String, String] = Map.empty)(implicit env: Env): Unit = {
    if (env.isDev || AiExtension.logger.isDebugEnabled) {
      val msg = s"calling provider '${from}' - ${method} - ${url} - ${opts} - ${body.map(_.prettify).getOrElse("")}"
      if (env.isDev) {
        AiExtension.logger.info(msg)
      } else if (AiExtension.logger.isDebugEnabled) {
        AiExtension.logger.debug(msg)
      }
    }
  }
  def logStream(from: String, method: String, url: String, body: Option[JsValue] = None, opts: Map[String, String] = Map.empty)(implicit env: Env): Unit = {
    if (env.isDev || AiExtension.logger.isDebugEnabled) {
      val msg = s"stream provider '${from}' - ${method} - ${url} - ${opts} - ${body.map(_.prettify).getOrElse("")}"
      if (env.isDev) {
        AiExtension.logger.info(msg)
      } else if (AiExtension.logger.isDebugEnabled) {
        AiExtension.logger.debug(msg)
      }
    }
  }
  def wrapResponse[T](from: String, resp: WSResponse, env: Env)(f: WSResponse => T): Either[JsValue, T] = {
    if (env.isDev || AiExtension.logger.isDebugEnabled) {
      val msg = s"provider response '${from}' - ${resp.status} - ${resp.body}"
      if (env.isDev) {
        AiExtension.logger.info(msg)
      } else if (AiExtension.logger.isDebugEnabled) {
        AiExtension.logger.debug(msg)
      }
    }
    if (resp.status == 200) {
      f(resp).right
    } else {
      logBadResponse(from, resp)
      responseBody(resp).left
    }
  }
  def wrapStreamResponse[T](from: String, resp: WSResponse, env: Env)(f: WSResponse => T): Either[JsValue, T] = {
    if (env.isDev || AiExtension.logger.isDebugEnabled) {
      val msg = s"provider stream response '${from}' - ${resp.status} - ${if (resp.status != 200) resp.body else "stream"}"
      if (env.isDev) {
        AiExtension.logger.info(msg)
      } else if (AiExtension.logger.isDebugEnabled) {
        AiExtension.logger.debug(msg)
      }
    }
    if (resp.status == 200) {
      f(resp).right
    } else {
      logBadResponse(from, resp)
      responseBody(resp).left
    }
  }
}

class UsageAccumulator(initialPromptTokens: Long = 0L, initialGenerationTokens: Long = 0L, initialReasoningTokens: Long = 0L) {
  private val promptTokensCounter: AtomicLong = new AtomicLong(initialPromptTokens)
  private val generationTokensCounter: AtomicLong = new AtomicLong(initialGenerationTokens)
  private val reasoningTokensCounter: AtomicLong = new AtomicLong(initialReasoningTokens)

  def update(promptTokens: Long, generationTokens: Long, reasoningTokens: Long): Unit = {
    promptTokensCounter.addAndGet(promptTokens)
    generationTokensCounter.addAndGet(generationTokens)
    reasoningTokensCounter.addAndGet(reasoningTokens)
  }

  def updateOpenai(usageOpt: Option[JsValue]): Unit = {
    usageOpt.foreach { usage =>
      promptTokensCounter.addAndGet(usage.select("prompt_tokens").asOpt[Long].getOrElse(0L))
      generationTokensCounter.addAndGet(usage.select("completion_tokens").asOpt[Long].getOrElse(0L))
      reasoningTokensCounter.addAndGet(usage.at("completion_tokens_details.reasoning_tokens").asOpt[Long].getOrElse(0L))
    }
  }

  def updateOllama(usageOpt: Option[JsValue]): Unit = {
    usageOpt.foreach { usage =>
      promptTokensCounter.addAndGet(usage.select("prompt_eval_count").asOpt[Long].getOrElse(0L))
      generationTokensCounter.addAndGet(usage.select("eval_count").asOpt[Long].getOrElse(0L))
      reasoningTokensCounter.addAndGet(usage.select("reasoning_count").asOpt[Long].getOrElse(0L))
    }
  }

  def updateCohere(usageOpt: Option[JsValue]): Unit = {
    usageOpt.foreach { usage =>
      promptTokensCounter.addAndGet(usage.select("tokens").select("input_tokens").asOpt[Long].getOrElse(0L))
      generationTokensCounter.addAndGet(usage.select("tokens").select("output_tokens").asOpt[Long].getOrElse(0L))
      generationTokensCounter.addAndGet(usage.select("tokens.reasoning_tokens").asOpt[Long].getOrElse(0L))
    }
  }

  def updateCohereChunk(usageOpt: Option[CohereChatResponseChunkUsage]): Unit = {
    usageOpt.foreach { usage =>
      promptTokensCounter.addAndGet(usage.input_tokens)
      generationTokensCounter.addAndGet(usage.output_tokens)
      reasoningTokensCounter.addAndGet(usage.reasoningTokens)
    }
  }

  def updateOpenaiChunk(usageOpt: Option[OpenAiChatResponseChunkUsage]): Unit = {
    usageOpt.foreach { usage =>
      promptTokensCounter.addAndGet(usage.prompt_tokens.getOrElse(0L))
      generationTokensCounter.addAndGet(usage.completion_tokens.getOrElse(0L))
      reasoningTokensCounter.addAndGet(usage.reasoningTokens.getOrElse(0L))
    }
  }

  def updateAzureOpenaiChunk(usageOpt: Option[AzureOpenAiChatResponseChunkUsage]): Unit = {
    usageOpt.foreach { usage =>
      promptTokensCounter.addAndGet(usage.prompt_tokens.getOrElse(0L))
      generationTokensCounter.addAndGet(usage.completion_tokens.getOrElse(0L))
      reasoningTokensCounter.addAndGet(usage.reasoning_tokens.getOrElse(0L))
    }
  }

  def updateAnthropic(usageOpt: Option[JsValue]): Unit = {
    usageOpt.foreach { usage =>
      promptTokensCounter.addAndGet(usage.select("input_tokens").asOpt[Long].getOrElse(0L))
      generationTokensCounter.addAndGet(usage.select("output_tokens").asOpt[Long].getOrElse(0L))
      reasoningTokensCounter.addAndGet(usage.select("reasoning_tokens").asOpt[Long].getOrElse(0L))
    }
  }

  def updateAnthropicChunk(usageOpt: Option[AnthropicChatResponseChunkUsage]): Unit = {
    usageOpt.foreach { usage =>
      promptTokensCounter.addAndGet(usage.input_tokens)
      generationTokensCounter.addAndGet(usage.output_tokens)
      reasoningTokensCounter.addAndGet(usage.reasoningTokens)
    }
  }

  def usage(): ChatResponseMetadataUsage = {
    ChatResponseMetadataUsage(
      promptTokens = promptTokensCounter.get(),
      generationTokens = generationTokensCounter.get(),
      reasoningTokens = reasoningTokensCounter.get(),
    )
  }
}
