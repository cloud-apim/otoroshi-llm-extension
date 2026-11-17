package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import com.cloud.apim.otoroshi.extensions.aigateway.ChatErrors
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.{JsString, JsValue, Json}
import play.api.mvc.{Result, Results}

/**
 * The answer of a proxy plugin to a chat call that failed, with the status of `ChatErrors.answer`: the error of the
 * provider as it answered it, or the one of the gateway, with the Retry-After of the provider when it gave one.
 */
object ChatErrorResults {

  private def withRetryAfter(result: Result, retryAfter: Option[String]): Result =
    retryAfter.map(value => result.withHeaders("Retry-After" -> value)).getOrElse(result)

  /** the routes of the OpenAI api (and of the `/chat` of the gateway): the error as it is */
  def openai(err: JsValue): NgProxyEngineError = {
    val answer = ChatErrors.answer(err)
    NgProxyEngineError.NgResultProxyEngineError(withRetryAfter(Results.Status(answer.status)(answer.body), answer.retryAfter))
  }

  // the error types of the Anthropic api, by status
  private def anthropicType(status: Int): String = status match {
    case 404 => "not_found_error"
    case 413 => "request_too_large"
    case 429 => "rate_limit_error"
    case s if s >= 500 => "api_error"
    case _ => "invalid_request_error"
  }

  /** the routes of the Anthropic api: the error in its envelope, the error of the provider as its message */
  def anthropic(err: JsValue): NgProxyEngineError = {
    val answer = ChatErrors.answer(err)
    val message = answer.body match {
      case JsString(text) => text
      case other => other.stringify
    }
    NgProxyEngineError.NgResultProxyEngineError(withRetryAfter(Results.Status(answer.status)(Json.obj(
      "type" -> "error",
      "error" -> Json.obj(
        "type" -> anthropicType(answer.status),
        "message" -> message
      )
    )), answer.retryAfter))
  }
}
