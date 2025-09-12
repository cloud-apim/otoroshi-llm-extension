package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import akka.stream.scaladsl.Source
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatClient, ChatGeneration, ChatMessage, ChatPrompt, ChatResponse, ChatResponseChunk, ChatResponseMetadata}
import com.cloud.apim.otoroshi.extensions.aigateway.guardrails._
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{Format, JsArray, JsError, JsObject, JsResult, JsSuccess, JsValue, Json}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

sealed trait GuardrailsCallPhase
object GuardrailsCallPhase {
  case object Before extends GuardrailsCallPhase
  case object After extends GuardrailsCallPhase
}

object Guardrails {
  val empty = Guardrails(Seq.empty)
  val format = new Format[Guardrails] {
    override def reads(json: JsValue): JsResult[Guardrails] = Try {
      Guardrails(json.asOpt[Seq[JsObject]].map(seq => seq.flatMap(i => GuardrailItem.format.reads(i).asOpt)).getOrElse(Seq.empty))
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(v) => JsSuccess(v)
    }
    override def writes(o: Guardrails): JsValue = JsArray(o.items.map(_.json))
  }
  def get(name: String): Option[Guardrail] = possibleGuardrails.get(name)
  val possibleGuardrails: Map[String, Guardrail] = Map(
    "regex" -> new RegexGuardrail(),
    "webhook" -> new WebhookGuardrail(),
    "llm" -> new LLMGuardrail(),
    "gibberish" -> new GibberishGuardrail(),
    "pif" -> new PersonalInformationsGuardrail(),
    "moderation" -> new LanguageModerationGuardrail(),
    "secrets_leakage" -> new SecretsLeakageGuardrail(),
    "prompt_injection" -> new PromptInjectionGuardrail(),
    "auto_secrets_leakage" -> new AutoSecretsLeakageGuardrail(),
    "toxic_language" -> new ToxicLanguageGuardrail(),
    "racial_bias" -> new RacialBiasGuardrail(),
    "gender_bias" -> new GenderBiasGuardrail(),
    "personal_health_information" -> new PersonalHealthInformationGuardrail(),
    "sentences" -> new SentencesCountGuardrail(),
    "words" -> new WordsCountGuardrail(),
    "characters" -> new CharactersCountGuardrail(),
    "contains" -> new ContainsGuardrail(),
    "semantic_contains" -> new SemanticContainsGuardrail(),
    "wasm" -> new WasmGuardrail(),
    "quickjs" -> new QuickJsGuardrail(),
    "moderation_model" -> new ModerationGuardrail(),
    "faithfullness" -> new FaithfullnessGuardrail(),
  )
/*
[
  {
    "enabled": true,
    "before": true,
    "after": true,
    "id": "gibberish",
    "config": {
      "provider": "provider_f98538b5-6d59-426c-8127-cb583a9fa763"
    }
  }
]
*/
}
case class Guardrails(items: Seq[GuardrailItem]) {

  def nonEmpty: Boolean = items.nonEmpty
  def isEmpty: Boolean = items.isEmpty
  def json: JsValue = Guardrails.format.writes(this)

  def call(callPhase: GuardrailsCallPhase, messages: Seq[ChatMessage], originalProvider: AiProvider, chatClient: ChatClient, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[GuardrailResult] = {

    val allGuardrails = originalProvider.guardrails.items.filter(_.enabled).flatMap { item =>
      Guardrails.possibleGuardrails.get(item.guardrailId).map(guardrail => (item, guardrail))
    }.filter { guardrail =>
      callPhase match {
        case GuardrailsCallPhase.Before => guardrail._1.before && guardrail._2.isBefore
        case GuardrailsCallPhase.After => guardrail._1.after && guardrail._2.isAfter
      }
    }

    def nextMessage(seq: Seq[ChatMessage], guardrail: Guardrail, config: JsObject): Future[GuardrailResult] = {
      if (seq.nonEmpty) {
        val head = seq.head
        guardrail.pass(Seq(head), config, originalProvider.some, chatClient.some, attrs).andThen {
          case Success(GuardrailResult.GuardrailPass) => nextMessage(seq.tail, guardrail, config)
          case Success(GuardrailResult.GuardrailDenied(err)) => GuardrailResult.GuardrailDenied(err).vfuture
          case Failure(e) => GuardrailResult.GuardrailDenied(e.getMessage).vfuture
        }
      } else {
        GuardrailResult.GuardrailPass.vfuture
      }
    }

    def nextGuardrail(seq: Seq[(GuardrailItem, Guardrail)]): Future[GuardrailResult] = {
      if (seq.nonEmpty) {
        val head = seq.head
        if (head._2.manyMessages) {
          head._2.pass(messages, head._1.config, originalProvider.some, chatClient.some, attrs).andThen {
            case Success(GuardrailResult.GuardrailPass) => nextGuardrail(seq.tail)
            case Success(GuardrailResult.GuardrailDenied(msg)) => GuardrailResult.GuardrailDenied(msg).vfuture
            case Success(GuardrailResult.GuardrailError(err)) => GuardrailResult.GuardrailError(err).vfuture
            case Failure(e) => GuardrailResult.GuardrailDenied(e.getMessage).vfuture
          }
        } else {
          nextMessage(messages, head._2, head._1.config).andThen {
            case Success(GuardrailResult.GuardrailPass) => nextGuardrail(seq.tail)
            case Success(GuardrailResult.GuardrailError(err)) => GuardrailResult.GuardrailError(err).vfuture
            case Success(GuardrailResult.GuardrailDenied(msg)) => GuardrailResult.GuardrailDenied(msg).vfuture
            case Failure(e) => GuardrailResult.GuardrailDenied(e.getMessage).vfuture
          }
        }
      } else {
        GuardrailResult.GuardrailPass.vfuture
      }
    }

    nextGuardrail(allGuardrails)
  }
}

object GuardrailItem {
  val format = new Format[GuardrailItem] {
    override def reads(json: JsValue): JsResult[GuardrailItem] = Try {
      GuardrailItem(
        enabled = json.select("enabled").asOpt[Boolean].getOrElse(false),
        before = json.select("before").asOpt[Boolean].getOrElse(false),
        after = json.select("after").asOpt[Boolean].getOrElse(false),
        guardrailId = json.select("id").asString,
        config = json.select("config").asOpt[JsObject].getOrElse(Json.obj()),
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(v) => JsSuccess(v)
    }
    override def writes(o: GuardrailItem): JsValue = Json.obj(
      "enabled" -> o.enabled,
      "before" -> o.before,
      "after" -> o.after,
      "id" -> o.guardrailId,
      "config" -> o.config,
    )
  }
}

case class GuardrailItem(enabled: Boolean, before: Boolean, after: Boolean, guardrailId: String, config: JsObject) {
  def json: JsValue = GuardrailItem.format.writes(this)
}

object ChatClientWithGuardrailsValidation {
  def applyIfPossible(tuple: (AiProvider, ChatClient, Env)): ChatClient = {
    if (tuple._1.guardrails.nonEmpty) {
      new ChatClientWithGuardrailsValidation(tuple._1, tuple._2)
    } else {
      tuple._2
    }
  }
}

sealed trait GuardrailResult

object GuardrailResult {
  case object GuardrailPass extends GuardrailResult
  case class GuardrailDenied(message: String) extends GuardrailResult
  case class GuardrailError(error: String) extends GuardrailResult
}

trait Guardrail {
  def isBefore: Boolean
  def isAfter: Boolean
  def manyMessages: Boolean
  def pass(messages: Seq[ChatMessage], config: JsObject, provider: Option[AiProvider], chatClient: Option[ChatClient], attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[GuardrailResult]
}

class ChatClientWithGuardrailsValidation(originalProvider: AiProvider, val chatClient: ChatClient) extends DecoratorChatClient {

  override def call(originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    originalProvider.guardrails.call(GuardrailsCallPhase.Before, originalPrompt.messages, originalProvider, chatClient, attrs).flatMap {
      case GuardrailResult.GuardrailError(err) => Left(Json.obj("error" -> "bad_request", "error_description" -> err, "phase" -> "before")).vfuture
      case GuardrailResult.GuardrailDenied(msg) if originalProvider.guardrailsFailOnDeny => Left(Json.obj("error" -> "guardrail_denied", "error_description" -> msg, "phase" -> "before")).vfuture
      case GuardrailResult.GuardrailDenied(msg) => Right(ChatResponse(
        Seq(ChatGeneration(ChatMessage.output(role = "assistant", content = msg, prefix = None, raw = Json.obj()))),
        ChatResponseMetadata.empty,
      )).vfuture
      case GuardrailResult.GuardrailPass => {
        chatClient.call(originalPrompt, attrs, originalBody).flatMap {
          case Left(err) => Left(err).vfuture
          case Right(r) => {
            originalProvider.guardrails.call(GuardrailsCallPhase.After, r.generations.map(_.message), originalProvider, chatClient, attrs).flatMap {
              case GuardrailResult.GuardrailError(err) => Left(Json.obj("error" -> "bad_request", "error_description" -> err, "phase" -> "after")).vfuture
              case GuardrailResult.GuardrailDenied(msg) if originalProvider.guardrailsFailOnDeny => Left(Json.obj("error" -> "guardrail_denied", "error_description" -> msg, "phase" -> "after")).vfuture
              case GuardrailResult.GuardrailDenied(msg) => Right(ChatResponse(
                Seq(ChatGeneration(ChatMessage.output(role = "assistant", content = msg, prefix = None, raw = Json.obj()))),
                ChatResponseMetadata.empty,
              )).vfuture
              case GuardrailResult.GuardrailPass => Right(r).vfuture
            }
          }
        }
      }
    }
  }

  override def stream(originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    originalProvider.guardrails.call(GuardrailsCallPhase.Before, originalPrompt.messages, originalProvider, chatClient, attrs).flatMap {
      case GuardrailResult.GuardrailError(err) => Left(Json.obj("error" -> "bad_request", "error_description" -> err, "phase" -> "before")).vfuture
      case GuardrailResult.GuardrailDenied(msg) => Right(ChatResponse(
        Seq(ChatGeneration(ChatMessage.output(role = "assistant", content = msg, prefix = None, raw = Json.obj()))),
        ChatResponseMetadata.empty,
      ).toSource(originalBody.select("model").asOpt[String].getOrElse("model"))).vfuture
      case GuardrailResult.GuardrailPass => {
        chatClient.stream(originalPrompt, attrs, originalBody).flatMap {
          case Left(err) => Left(err).vfuture
          case Right(r) => Right(r).vfuture
        }
      }
    }
  }

  override def completion(originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    originalProvider.guardrails.call(GuardrailsCallPhase.Before, originalPrompt.messages, originalProvider, chatClient, attrs).flatMap {
      case GuardrailResult.GuardrailError(err) => Left(Json.obj("error" -> "bad_request", "error_description" -> err, "phase" -> "before")).vfuture
      case GuardrailResult.GuardrailDenied(msg) => Right(ChatResponse(
        Seq(ChatGeneration(ChatMessage.output(role = "assistant", content = msg, prefix = None, raw = Json.obj()))),
        ChatResponseMetadata.empty,
      )).vfuture
      case GuardrailResult.GuardrailPass => {
        chatClient.completion(originalPrompt, attrs, originalBody).flatMap {
          case Left(err) => Left(err).vfuture
          case Right(r) => {
            originalProvider.guardrails.call(GuardrailsCallPhase.After, r.generations.map(_.message), originalProvider, chatClient, attrs).flatMap {
              case GuardrailResult.GuardrailError(err) => Left(Json.obj("error" -> "bad_request", "error_description" -> err, "phase" -> "after")).vfuture
              case GuardrailResult.GuardrailDenied(msg) => Right(ChatResponse(
                Seq(ChatGeneration(ChatMessage.output(role = "assistant", content = msg, prefix = None, raw = Json.obj()))),
                ChatResponseMetadata.empty,
              )).vfuture
              case GuardrailResult.GuardrailPass => Right(r).vfuture
            }
          }
        }
      }
    }
  }

  override def completionStream(originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    originalProvider.guardrails.call(GuardrailsCallPhase.Before, originalPrompt.messages, originalProvider, chatClient, attrs).flatMap {
      case GuardrailResult.GuardrailError(err) => Left(Json.obj("error" -> "bad_request", "error_description" -> err, "phase" -> "before")).vfuture
      case GuardrailResult.GuardrailDenied(msg) => Right(ChatResponse(
        Seq(ChatGeneration(ChatMessage.output(role = "assistant", content = msg, prefix = None, raw = Json.obj()))),
        ChatResponseMetadata.empty,
      ).toSource(originalBody.select("model").asOpt[String].getOrElse("model"))).vfuture
      case GuardrailResult.GuardrailPass => {
        chatClient.completionStream(originalPrompt, attrs, originalBody).flatMap {
          case Left(err) => Left(err).vfuture
          case Right(r) => Right(r).vfuture
        }
      }
    }
  }
}
