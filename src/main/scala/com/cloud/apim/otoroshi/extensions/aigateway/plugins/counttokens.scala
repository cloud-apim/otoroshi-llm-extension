package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import com.cloud.apim.otoroshi.extensions.aigateway.decorators.TokenCounting
import com.cloud.apim.otoroshi.extensions.aigateway.plugins.AiPluginRefsConfig
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatMessageContentFlavor, ChatPrompt, InputChatMessage}
import org.apache.pekko.stream.Materializer
import org.apache.pekko.util.ByteString
import otoroshi.env.Env
import otoroshi.next.plugins.api.*
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
 * How `POST /v1/messages/count_tokens` is answered when the provider has no route to count the tokens of a request.
 *
 *  - `none` (default): it is not, with a 501: a wrong figure would mislead a client trimming its context on it
 *  - `measure`: the request is sent with `max_tokens: 1`, and the prompt tokens the provider reports are the answer.
 *    Exact for every provider, but it is a call: it costs the processing of the prompt and one token, and counts in
 *    budgets and audits. Its answer is never cached nor kept in a persistent memory
 *  - `estimate`: a rough estimate from the length of the text, flagged by the `x-otoroshi-llm-tokens-count-method`
 *    header
 */
object CountTokensFallback {
  val Off = "none"
  val Measure = "measure"
  val Estimate = "estimate"
  val all: Seq[String] = Seq(Off, Measure, Estimate)
  def of(value: Option[String]): String = value.map(_.trim.toLowerCase).filter(all.contains).getOrElse(Off)

  def configSchema: JsObject = Json.obj(
    "type" -> "select",
    "label" -> "Count tokens without a provider route",
    "props" -> Json.obj(
      "help" -> "how /v1/messages/count_tokens is answered when the provider cannot count the tokens itself: not at all (501), measured with a one token call (it costs, and counts in budgets), or estimated from the length of the text",
      "options" -> Json.arr(
        Json.obj("label" -> "Not answered (501)", "value" -> Off),
        Json.obj("label" -> "Measured with a one token call", "value" -> Measure),
        Json.obj("label" -> "Estimated", "value" -> Estimate),
      ),
    ),
  )
}

/**
 * `POST /v1/messages/count_tokens` of the Anthropic messages api, for whatever provider serves the route. What is
 * counted is what the gateway would send: the system prompt, the template and the prompt contexts of the route, the
 * context and the memory of the provider, the tools. The provider counts first, when it has a route for that.
 */
object AnthropicTokenCounter {

  val methodHeader = "x-otoroshi-llm-tokens-count-method"

  // a rough figure: about 3.5 characters a token for english text and code, a little less for other languages
  private val charsPerToken = 3.5

  private def answer(tokens: Long, method: String): Either[NgProxyEngineError, BackendCallResponse] = {
    Right(BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok(Json.obj("input_tokens" -> tokens)).withHeaders(methodHeader -> method)), None))
  }

  // the text a model reads: everything but what points to images or files, which would only count their encoding
  private def textOf(value: JsValue): Long = value match {
    case JsString(str) => str.length.toLong
    case JsArray(items) => items.map(textOf).sum
    case JsObject(fields) => fields.collect {
      case (key, v) if !Set("url", "image_url", "data", "file_data", "source", "type", "role", "id", "tool_call_id").contains(key) => textOf(v)
    }.sum
    case _ => 0L
  }

  def estimate(messages: Seq[InputChatMessage], body: JsObject): Long = {
    val chars = messages.map(m => textOf(m.json(ChatMessageContentFlavor.Common))).sum + body.select("tools").asOpt[JsValue].map(textOf).getOrElse(0L)
    math.ceil(chars.toDouble / charsPerToken).toLong.max(1L)
  }

  private def measure(call: AnthropicCompatProxy.AnthropicCall, ctx: NgbBackendCallContext)(using ec: ExecutionContext, env: Env): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    TokenCounting.mark(ctx.attrs)
    // one token, no stream, no thinking: what is measured is the prompt, and nothing is worth waiting for
    val limit = if (call.client.isAnthropic) Json.obj("max_tokens" -> 1) else Json.obj(AnthropicCompatProxy.maxTokensParam(call.provider) -> 1)
    val body = call.body - "stream" - "thinking" - "reasoning_effort" - "max_tokens" - "max_completion_tokens" ++ limit
    call.client.call(ChatPrompt(call.messages), ctx.attrs, body).map {
      case Left(err) => Left(AnthropicCompatProxy.anthropicError(Results.BadGateway, "api_error", s"unable to measure the input tokens: ${err.stringify}"))
      case Right(response) if response.metadata.usage.promptTokens > 0 => answer(response.metadata.usage.promptTokens, CountTokensFallback.Measure)
      case Right(_) => Left(AnthropicCompatProxy.anthropicError(Results.BadGateway, "api_error", "the provider reported no input tokens"))
    }
  }

  def count(_jsonBody: JsValue, config: AiPluginRefsConfig, fallback: String, ctx: NgbBackendCallContext)(using ec: ExecutionContext, env: Env): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    AnthropicCompatProxy.prepare(_jsonBody, config, ctx) match {
      case Left(err) => Left(err).vfuture
      case Right(call) =>
        def withoutRoute(why: String): Future[Either[NgProxyEngineError, BackendCallResponse]] = fallback match {
          case CountTokensFallback.Measure => measure(call, ctx)
          case CountTokensFallback.Estimate => answer(estimate(call.messages, call.body), CountTokensFallback.Estimate).vfuture
          case _ => Left(AnthropicCompatProxy.anthropicError(Results.NotImplemented, "api_error", why)).vfuture
        }
        val name = call.provider.map(_.name).getOrElse("--")
        call.client.countTokens(ChatPrompt(call.messages), ctx.attrs, call.body).flatMap {
          case Some(Right(tokens)) => answer(tokens, "provider").vfuture
          case Some(Left(err)) if fallback == CountTokensFallback.Off =>
            Left(AnthropicCompatProxy.anthropicError(Results.BadGateway, "api_error", s"provider '${name}' could not count the input tokens: ${err.stringify}")).vfuture
          case Some(Left(_)) => withoutRoute(s"provider '${name}' could not count the input tokens")
          case None => withoutRoute(s"provider '${name}' has no route to count the input tokens of a request")
        }.recoverWith {
          case e: Throwable if fallback != CountTokensFallback.Off => withoutRoute(e.getMessage)
          case e: Throwable => Left(AnthropicCompatProxy.anthropicError(Results.BadGateway, "api_error", s"provider '${name}' could not count the input tokens: ${e.getMessage}")).vfuture
        }
    }
  }

  def handleRequest(config: AiPluginRefsConfig, fallback: String, ctx: NgbBackendCallContext)(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    if (ctx.request.hasBody) {
      ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
        Try(Json.parse(bodyRaw.utf8String)).toOption match {
          case Some(json) => count(json, config, fallback, ctx)
          case None => Left(AnthropicCompatProxy.anthropicError(Results.BadRequest, "invalid_request_error", "the body must be json")).vfuture
        }
      }
    } else {
      Left(AnthropicCompatProxy.anthropicError(Results.BadRequest, "invalid_request_error", "the body must be json")).vfuture
    }
  }
}
