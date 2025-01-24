package com.cloud.apim.otoroshi.extensions.aigateway.guardrails

import com.cloud.apim.otoroshi.extensions.aigateway.decorators.{Guardrail, GuardrailResult}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiProvider, LlmValidationSettings}
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatClient, ChatMessage, ChatPrompt, InputChatMessage, OutputChatMessage}
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.{JsObject, Json}

import scala.concurrent.{ExecutionContext, Future}

class LLMGuardrail extends Guardrail {

  override def isBefore: Boolean = true

  override def isAfter: Boolean = true

  override def manyMessages: Boolean = true

  def pass(): Future[GuardrailResult] = GuardrailResult.GuardrailPass.vfuture

  def fail(idx: Int): Future[GuardrailResult] = GuardrailResult.GuardrailDenied(s"request content did not pass llm validation (${idx})").vfuture

  override def pass(_messages: Seq[ChatMessage], config: JsObject, provider: AiProvider, chatClient: ChatClient, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[GuardrailResult] = {
    val llmValidation = LlmValidationSettings.format.reads(config).getOrElse(LlmValidationSettings())
    llmValidation.provider match {
      case None => pass()
      case Some(ref) if ref == provider.id => pass()
      case Some(ref) => {
        llmValidation.prompt match {
          case None => pass()
          case Some(pref) => env.adminExtensions.extension[AiExtension].flatMap(_.states.prompt(pref)) match {
            case None => GuardrailResult.GuardrailDenied("validation prompt not found").vfuture
            case Some(prompt) => {
              env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(ref).flatMap(_.getChatClient())) match {
                case None => GuardrailResult.GuardrailDenied("validation provider not found").vfuture
                case Some(validationClient) => {
                  val messages = _messages.map {
                    case i: InputChatMessage => i
                    case o: OutputChatMessage => o.toInput()
                  }
                  validationClient.call(ChatPrompt(Seq(
                    ChatMessage.input("system", prompt.prompt, None)
                  ) ++ messages), attrs, Json.obj()).flatMap {
                    case Left(err) => fail(2)
                    case Right(resp) => {
                      val content = resp.generations.head.message.content.toLowerCase().trim.replace("\n", " ")
                      //  println(s"content: '${content}'")
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
