package com.cloud.apim.otoroshi.extensions.aigateway.fences

import com.cloud.apim.otoroshi.extensions.aigateway.{ChatClient, ChatMessage, ChatPrompt}
import com.cloud.apim.otoroshi.extensions.aigateway.decorators.{Fence, FenceResult}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiProvider, LlmValidationSettings}
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.{JsObject, Json}

import scala.concurrent.{ExecutionContext, Future}

object Items {
  val moderationCategories = Seq(
    "hate",
    "hate/threatening",
    "harassment",
    "harassment/threatening",
    "self-harm",
    "self-harm/intent",
    "self-harm/instructions",
    "sexual",
    "sexual/minors",
    "violence",
    "violence/graphic",
  )

  val personalInformations = Seq(
    "EMAIL_ADDRESS",
    "PHONE_NUMBER",
    "LOCATION_ADDRESS",
    "NAME",
    "IP_ADDRESS",
    "CREDIT_CARD",
    "SSN",
  )

  val gibberishPrompt = """If you encounter gibberish language in the following messages, please respond with the word "false". If not, please respond with the word "true". Do not add anything else"""
  val personalInformationsPrompt = s"""If you encounter in the following messages any personal information of the following kind: ${personalInformations.mkString(", ")}, please respond with the word "false". If not, please respond with the word "true". Do not add anything else"""
  val languageModerationPrompt = s"""If you encounter in the following messages any text of the following kind: ${moderationCategories.mkString(", ")}, please respond with the word "false". If not, please respond with the word "true". Do not add anything else"""
}

class GibberishFence extends HardCodedLLMFence(Items.gibberishPrompt)
class PersonalInformationsFence extends HardCodedLLMFence(Items.personalInformationsPrompt)
class LanguageModerationFence extends HardCodedLLMFence(Items.languageModerationPrompt)

class HardCodedLLMFence(systemPrompt: String) extends Fence {

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
        env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(ref).flatMap(_.getChatClient())) match {
          case None => FenceResult.FenceDenied("validation provider not found").vfuture
          case Some(validationClient) => {
            validationClient.call(ChatPrompt(Seq(
              ChatMessage("system", systemPrompt)
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
