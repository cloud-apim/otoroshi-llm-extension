package com.cloud.apim.otoroshi.extensions.aigateway

import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import otoroshi.env.Env
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits._
import otoroshi.utils.{RegexPool, TypedMap}
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}
import play.api.libs.typedmap.TypedKey
import play.api.mvc.Results

import java.util.regex.{MatchResult, Matcher, Pattern}
import java.util.regex.Pattern.CASE_INSENSITIVE
import scala.concurrent.{ExecutionContext, Future}

trait ChatOptions {
  def temperature: Float
  def topP: Float
  def topK: Int
  def json: JsObject
}
case class ChatPrompt(messages: Seq[ChatMessage], options: Option[ChatOptions] = None) {
  def json: JsValue = JsArray(messages.map(_.json))
}
case class ChatMessage(role: String, content: String) {
  def json: JsValue = Json.obj(
    "role" -> role,
    "content" -> content,
  )
}
case class ChatGeneration(message: ChatMessage) {
  def json: JsValue = Json.obj(
    "message" -> message.json
  )
}
case class ChatResponse(
  generations: Seq[ChatGeneration],
  metadata: ChatResponseMetadata,
) {
  def json: JsValue = Json.obj(
    "generations" -> JsArray(generations.map(_.json)),
    "metadata" -> metadata.json,
  )
}

case class ChatResponseMetadata(rateLimit: ChatResponseMetadataRateLimit, usage: ChatResponseMetadataUsage) {
  def json: JsValue = Json.obj(
    "rate_limit" -> rateLimit.json,
    "usage" -> usage.json,
  )
}

object ChatResponseMetadata {
  val empty: ChatResponseMetadata = ChatResponseMetadata(
    ChatResponseMetadataRateLimit.empty,
    ChatResponseMetadataUsage.empty,
  )
}

object ChatResponseMetadataRateLimit {
  def empty: ChatResponseMetadataRateLimit = ChatResponseMetadataRateLimit(0L, 0L, 0L, 0L)
}

case class ChatResponseMetadataRateLimit(requestsLimit: Long, requestsRemaining: Long, tokensLimit: Long, tokensRemaining: Long) {
  def json: JsValue = Json.obj(
    "requests_limit" -> requestsLimit,
    "requests_remaining" -> requestsRemaining,
    "tokens_limit" -> tokensLimit,
    "tokens_remaining" -> tokensRemaining,
  )
}

object ChatResponseMetadataUsage {
  val empty: ChatResponseMetadataUsage = ChatResponseMetadataUsage(0L, 0L)
}

case class ChatResponseMetadataUsage(promptTokens: Long, generationTokens: Long) {
  def json: JsValue = Json.obj(
    "prompt_tokens" -> promptTokens,
    "generation_tokens" -> generationTokens,
  )
}

trait ChatClient {
  def call(prompt: ChatPrompt, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]]
}

object ChatClient {
  val ApiUsageKey = TypedKey[ChatResponseMetadata]("otoroshi-extensions.cloud-apim.ai.llm.ApiUsage")
}

class ChatClientWithValidation(originalProvider: AiProvider, chatClient: ChatClient) extends ChatClient {

  private val allow = originalProvider.allow
  private val deny = originalProvider.deny

  private def validate(content: String): Boolean = {
    val allowed = if (allow.isEmpty) true else allow.exists(al => RegexPool.regex(al).matches(content))
    val denied = if (deny.isEmpty) false else deny.exists(dn => RegexPool.regex(dn).matches(content))
    !denied && allowed
  }

  override def call(originalPrompt: ChatPrompt, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {

    def pass(): Future[Either[JsValue, ChatResponse]] = chatClient.call(originalPrompt, attrs)
    def fail(idx: Int): Future[Either[JsValue, ChatResponse]] = Left(Json.obj("error" -> "bad_request", "error_description" -> s"request content did not pass validation request (${idx})")).vfuture

    val contents = originalPrompt.messages.map(_.content)
    if (!contents.forall(content => validate(content))) {
      fail(1)
    } else {
      originalProvider.validatorRef match {
        case None => pass()
        case Some(ref) if ref == originalProvider.id => pass()
        case Some(ref) => {
          originalProvider.validatorPrompt match {
            case None => pass()
            case Some(pref) => env.adminExtensions.extension[AiExtension].flatMap(_.states.prompt(pref)) match {
              case None => Left(Json.obj("error" -> "validation prompt not found")).vfuture
              case Some(prompt) => {
                env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(ref).flatMap(_.getChatClient())) match {
                  case None => Left(Json.obj("error" -> "validation provider not found")).vfuture
                  case Some(validationClient) => {
                    validationClient.call(ChatPrompt(Seq(
                      ChatMessage("system", prompt.prompt)
                    ) ++ originalPrompt.messages), attrs).flatMap {
                      case Left(err) => fail(2)
                      case Right(resp) => {
                        val content = resp.generations.head.message.content.toLowerCase().trim.replace("\n", " ")
                        println(s"content: '${content}'")
                        if (content == "true") {
                          pass()
                        } else if (content == "false") {
                          fail(3)
                        } else if (content.startsWith("{") && content.endsWith("}")) {
                          if (Json.parse(content).select("result").asOpt[Boolean].getOrElse(false)) {
                            pass()
                          } else {
                            fail(4)
                          }
                        } else {
                          content.split(" ").headOption match {
                            case Some("true") => pass()
                            case _ => fail(5)
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}