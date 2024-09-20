package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatClient, ChatMessage, ChatPrompt, ChatResponse}
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.{JsValue, Json}

import scala.concurrent.{ExecutionContext, Future}

object ChatClientWithLlmValidation {
  def applyIfPossible(tuple: (AiProvider, ChatClient)): ChatClient = {
    if (tuple._1.llmValidation.provider.isDefined && tuple._1.llmValidation.prompt.isDefined) {
      new ChatClientWithLlmValidation(tuple._1, tuple._2)
    } else {
      tuple._2
    }
  }
}

class ChatClientWithLlmValidation(originalProvider: AiProvider, chatClient: ChatClient) extends ChatClient {

  override def model: Option[String] = chatClient.model

  override def call(originalPrompt: ChatPrompt, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {

    def pass(): Future[Either[JsValue, ChatResponse]] = chatClient.call(originalPrompt, attrs)

    def fail(idx: Int): Future[Either[JsValue, ChatResponse]] = Left(Json.obj("error" -> "bad_request", "error_description" -> s"request content did not pass llm validation (${idx})")).vfuture

    originalProvider.llmValidation.provider match {
      case None => pass()
      case Some(ref) if ref == originalProvider.id => pass()
      case Some(ref) => {
        originalProvider.llmValidation.prompt match {
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