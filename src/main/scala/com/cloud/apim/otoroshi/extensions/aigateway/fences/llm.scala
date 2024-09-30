package com.cloud.apim.otoroshi.extensions.aigateway.fences

import com.cloud.apim.otoroshi.extensions.aigateway.decorators.{Fence, FenceResult}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiProvider, LlmValidationSettings}
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatClient, ChatMessage, ChatPrompt}
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.{JsObject, Json}

import scala.concurrent.{ExecutionContext, Future}

class LLMFence extends Fence {

  override def isBefore: Boolean = true

  override def isAfter: Boolean = true

  override def manyMessages: Boolean = true

  def pass(): Future[FenceResult] = FenceResult.FencePass.vfuture

  def fail(idx: Int): Future[FenceResult] = FenceResult.FenceDenied(s"request content did not pass llm validation (${idx})").vfuture

  override def pass(messages: Seq[ChatMessage], config: JsObject, provider: AiProvider, chatClient: ChatClient, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[FenceResult] = {
    val llmValidation = LlmValidationSettings.format.reads(config).getOrElse(LlmValidationSettings())
    llmValidation.provider match {
      case None => pass()
      case Some(ref) if ref == provider.id => pass()
      case Some(ref) => {
        llmValidation.prompt match {
          case None => pass()
          case Some(pref) => env.adminExtensions.extension[AiExtension].flatMap(_.states.prompt(pref)) match {
            case None => FenceResult.FenceDenied("validation prompt not found").vfuture
            case Some(prompt) => {
              env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(ref).flatMap(_.getChatClient())) match {
                case None => FenceResult.FenceDenied("validation provider not found").vfuture
                case Some(validationClient) => {
                  validationClient.call(ChatPrompt(Seq(
                    ChatMessage("system", prompt.prompt)
                  ) ++ messages), attrs).flatMap {
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