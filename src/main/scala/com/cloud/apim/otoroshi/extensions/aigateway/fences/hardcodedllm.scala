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

object LLMFencesHardcodedItems {

  val possibleModerationCategories = Seq(
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
    "profanity",

  )

  val possiblePersonalInformations = Seq(
    "EMAIL_ADDRESS",
    "PHONE_NUMBER",
    "LOCATION_ADDRESS",
    "NAME",
    "IP_ADDRESS",
    "CREDIT_CARD",
    "SSN",
  )

  val possibleSecretLeakage = Seq(
    "APIKEYS",
    "PASSWORDS",
    "TOKENS",
    "JWT_TOKENS",
    "PRIVATE_KEYS",
    "HUGE_RANDOM_VALUES",
  )

  def gibberishPrompt(): String = """If you encounter gibberish language in the following messages, please respond with the word "false". If not, please respond with the word "true". Do not add anything else"""
  def personalInformationsPrompt(items: Seq[String]): String = s"""If you encounter in the following messages any personal information of the following kind: ${items.mkString(", ")}, please respond with the word "false". If not, please respond with the word "true". Do not add anything else"""
  def languageModerationPrompt(items: Seq[String]): String = s"""If you encounter in the following messages any text of the following kind: ${items.mkString(", ")}, please respond with the word "false". If not, please respond with the word "true". Do not add anything else"""
  def secretsLeakagePrompt(items: Seq[String]): String = s"""If in the following messages you detect textual values such as ${items.mkString(", ")}, please respond with the word "false". If not, please respond with the word "true". Do not add anything else"""
  def alternativeSecretsLeakagePrompt(): String =
    s"""Analyze the following message and detect if it contains any sensitive IT secrets or confidential information. Sensitive IT data may include, but is not limited to:
       |
       |Credentials: Any type of credentials such as usernames, passwords, tokens, API keys, SSH keys, or other access credentials.
       |Secrets: Secret keys, environment variables, database connection strings, encryption keys, or OAuth tokens.
       |Configuration Details: Internal IP addresses, URLs of private services, database credentials, internal project names, or deployment configurations.
       |Personally Identifiable Information (PII): Usernames, IDs, emails, or other information that can identify individuals in a system context.
       |Proprietary Code or Scripts: Snippets of code or scripts that may contain business logic, internal processes, or private algorithms.
       |Cloud Infrastructure Details: AWS IAM roles, Azure access keys, GCP service account details, or cloud storage bucket URLs.
       |If you encounter such signs of secrets in the following messages, please respond with the word "false". If not, please respond with the word "true". Do not add anything else
       |""".stripMargin
  def personalHealthInformationPrompt(): String =
    s"""Analyze the following messages and detect if it contains any Personal Health Information (PHI).
       |PHI includes, but is not limited to: patient names, diagnoses, medical history, test results, treatments,
       |prescriptions, patient contact information, identification numbers (e.g., social security number),
       |specific dates (e.g., birth date, consultation date), and any other information that can identify
       |an individual and reveal details about their health condition. If you encounter such PHI in the following messages,
       |please respond with the word "false". If not, please respond with the word "true". Do not add anything else""".stripMargin
  def genderBiasDetectionPrompt(): String =
    s"""Analyze the following message and detect if it contains any signs of gender bias. Gender bias may include, but is not limited to:
       |
       |Stereotyping: Associating specific roles, characteristics, or behaviors with a particular gender (e.g., assuming women are more nurturing, men are more assertive).
       |Unequal Treatment: Indicating that one gender is superior/inferior or favoring one gender over another.
       |Discriminatory Language: Using language that devalues, diminishes, or excludes a particular gender (e.g., "man up", "women aren't fit for leadership").
       |Gendered Expectations: Imposing expectations based on gender (e.g., "As a man, you should be strong").
       |Microaggressions: Subtle comments or assumptions that reinforce gender stereotypes (e.g., expressing surprise when a woman is in a traditionally male-dominated field).
       |If you encounter such signs of gender bias in the following messages, please respond with the word "false". If not, please respond with the word "true". Do not add anything else
       |""".stripMargin
  def racialBiasDetectionPrompt(): String =
    s"""Analyze the following message and detect if it contains any signs of racial bias. Racial bias may include, but is not limited to:
       |
       |Stereotyping: Associating specific roles, behaviors, or characteristics with a particular racial or ethnic group (e.g., assuming people of certain races are naturally better at sports or academics).
       |Discriminatory Language: Using offensive terms, slurs, or language that demeans or marginalizes a racial group.
       |Microaggressions: Subtle comments or assumptions that reinforce stereotypes or racial hierarchy (e.g., “You’re so articulate for someone from your background”).
       |Unequal Treatment: Suggesting that one racial group is superior/inferior or should be treated differently from another.
       |Cultural Appropriation: Using elements of a marginalized culture (e.g., clothing, language, symbols) without respect or understanding.
       |Racialized Expectations: Imposing expectations based on race (e.g., “As an Asian, you must be good at math”).
       |If you encounter such signs of racial bias in the following messages, please respond with the word "false". If not, please respond with the word "true". Do not add anything else
       |""".stripMargin
  def toxicLanguageDetectionPrompt(): String =
    s"""Analyze the following message and determine if it contains any form of toxic language. Toxic language may include, but is not limited to:
       |
       |Hate Speech: Offensive or derogatory language targeting a specific group based on race, gender, ethnicity, religion, disability, or sexual orientation.
       |Insults: Personal attacks or name-calling intended to demean or belittle someone.
       |Threats: Statements that suggest harm, violence, or intimidation against a person or group.
       |Obscenity: Use of explicit or highly offensive language (e.g., excessive profanity, sexually explicit remarks).
       |Harassment: Repeated or persistent use of language meant to annoy, provoke, or distress an individual.
       |Discriminatory Language: Language that suggests prejudice or exclusion of a group based on their identity.
       |Gaslighting or Manipulation: Language that undermines someone's experiences, emotions, or sense of reality.
       |If you encounter such signs of toxic language in the following messages, please respond with the word "false". If not, please respond with the word "true". Do not add anything else
       |""".stripMargin

  // TODO: is concise ?
  // TODO: is helpful ?
  // TODO: is polite ?
  // TODO: no apologies
  // TODO: no prompt injection
  // TODO: allow semantic match
  // TODO: deny semantic match
}

class AutoSecretsLeakageFence extends HardCodedLLMFence {

  def name: String = "auto-secrets-leakage"

  override def systemPrompt(items: Seq[String]): String = LLMFencesHardcodedItems.alternativeSecretsLeakagePrompt()
}

class ToxicLanguageFence extends HardCodedLLMFence {

  def name: String = "toxic-language"

  override def systemPrompt(items: Seq[String]): String = LLMFencesHardcodedItems.toxicLanguageDetectionPrompt()
}

class RacialBiasFence extends HardCodedLLMFence {

  def name: String = "racial-bias"

  override def systemPrompt(items: Seq[String]): String = LLMFencesHardcodedItems.racialBiasDetectionPrompt()
}

class GenderBiasFence extends HardCodedLLMFence {

  def name: String = "gender-bias"

  override def systemPrompt(items: Seq[String]): String = LLMFencesHardcodedItems.genderBiasDetectionPrompt()
}

class PersonalHealthInformationFence extends HardCodedLLMFence {

  def name: String = "personal-health-information"

  override def systemPrompt(items: Seq[String]): String = LLMFencesHardcodedItems.personalHealthInformationPrompt()
}

class GibberishFence extends HardCodedLLMFence {

  def name: String = "gibberish"

  override def systemPrompt(items: Seq[String]): String = LLMFencesHardcodedItems.gibberishPrompt()
}

class PersonalInformationsFence extends HardCodedLLMFence {

  def name: String = "personal-information"

  override def systemPrompt(items: Seq[String]): String = LLMFencesHardcodedItems.personalInformationsPrompt(items)
}

class LanguageModerationFence extends HardCodedLLMFence {

  def name: String = "language-moderation"

  override def systemPrompt(items: Seq[String]): String = LLMFencesHardcodedItems.languageModerationPrompt(items)
}

class SecretsLeakageFence extends HardCodedLLMFence {

  def name: String = "secrets-leakage"

  override def systemPrompt(items: Seq[String]): String = LLMFencesHardcodedItems.secretsLeakagePrompt(items)
}

abstract class HardCodedLLMFence extends Fence {

  override def isBefore: Boolean = true

  override def isAfter: Boolean = true

  override def manyMessages: Boolean = true

  def systemPrompt(items: Seq[String]): String

  def name: String

  def pass(): Future[FenceResult] = FenceResult.FencePass.vfuture

  def fail(idx: Int, config: JsObject): Future[FenceResult] = {
    val msg = config.select("err_msg").asOpt[String].getOrElse(s"This message has been blocked by the '${name}' fence !")
    FenceResult.FenceDenied(msg).vfuture
  }

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
              ChatMessage("system", systemPrompt(
                config.select("items").asOpt[Seq[String]]
                  .orElse(config.select("pif_items").asOpt[Seq[String]])
                  .orElse(config.select("moderation_items").asOpt[Seq[String]])
                  .orElse(config.select("secrets_leakage_items").asOpt[Seq[String]])
                  .getOrElse(Seq.empty)))
            ) ++ messages), attrs).flatMap {
              case Left(err) => FenceResult.FenceDenied(err.stringify).vfuture
              case Right(resp) => {
                val content = resp.generations.head.message.content.toLowerCase().trim.replace("\n", " ")
                // println(s"content: '${content}'")
                if (content == "true") {
                  pass()
                } else if (content == "false") {
                  fail(3, config)
                } else if (content.startsWith("{") && content.endsWith("}")) {
                  if (Json.parse(content).select("result").asOpt[Boolean].getOrElse(false)) {
                    pass()
                  } else {
                    fail(4, config)
                  }
                } else {
                  content.split(" ").headOption match {
                    case Some("true") => pass()
                    case _ => fail(5, config)
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
