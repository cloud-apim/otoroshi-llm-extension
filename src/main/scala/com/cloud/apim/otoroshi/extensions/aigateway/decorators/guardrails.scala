package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import akka.stream.scaladsl.Source
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.{AiMetrics, ChatClient, ChatGeneration, ChatMessage, ChatPrompt, ChatResponse, ChatResponseChunk, ChatResponseMetadata, InputChatMessage, OutputChatMessage}
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
    "faithfulness" -> new FaithfulnessGuardrail(),
    "workflow" -> new WorkflowGuardrail(),
    "rampart" -> new RampartPiiGuardrail(),
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

    // runs a single guardrail over the current messages, returning either a terminal result
    // (deny/error) or the resulting messages (possibly rewritten through GuardrailTransform)
    def runOne(guardrail: Guardrail, config: JsObject, current: Seq[ChatMessage]): Future[Either[GuardrailResult, Seq[ChatMessage]]] = {
      if (guardrail.manyMessages) {
        guardrail.pass(current, config, originalProvider.some, chatClient.some, attrs).map {
          case GuardrailResult.GuardrailPass => Right(current)
          case GuardrailResult.GuardrailTransform(msgs) => Right(msgs)
          case d @ GuardrailResult.GuardrailDenied(_) => Left(d)
          case e @ GuardrailResult.GuardrailError(_) => Left(e)
        }
      } else {
        def loop(remaining: Seq[ChatMessage], acc: Seq[ChatMessage]): Future[Either[GuardrailResult, Seq[ChatMessage]]] = {
          if (remaining.isEmpty) {
            (Right(acc): Either[GuardrailResult, Seq[ChatMessage]]).vfuture
          } else {
            val head = remaining.head
            guardrail.pass(Seq(head), config, originalProvider.some, chatClient.some, attrs).flatMap {
              case GuardrailResult.GuardrailPass => loop(remaining.tail, acc :+ head)
              case GuardrailResult.GuardrailTransform(msgs) => loop(remaining.tail, acc ++ msgs)
              case d @ GuardrailResult.GuardrailDenied(_) => (Left(d): Either[GuardrailResult, Seq[ChatMessage]]).vfuture
              case e @ GuardrailResult.GuardrailError(_) => (Left(e): Either[GuardrailResult, Seq[ChatMessage]]).vfuture
            }
          }
        }
        loop(current, Seq.empty)
      }
    }

    def nextGuardrail(seq: Seq[(GuardrailItem, Guardrail)], current: Seq[ChatMessage]): Future[GuardrailResult] = {
      if (seq.isEmpty) {
        if (current != messages) GuardrailResult.GuardrailTransform(current).vfuture
        else GuardrailResult.GuardrailPass.vfuture
      } else {
        val head = seq.head
        val kind = head._1.guardrailId
        runOne(head._2, head._1.config, current).flatMap {
          case Left(d @ GuardrailResult.GuardrailDenied(_)) => AiMetrics.markGuardrail(kind, "deny"); (d: GuardrailResult).vfuture
          case Left(e @ GuardrailResult.GuardrailError(_)) => AiMetrics.markGuardrail(kind, "error"); (e: GuardrailResult).vfuture
          case Left(other) => other.vfuture
          case Right(newMessages) =>
            if (newMessages != current) AiMetrics.markGuardrail(kind, "transform") else AiMetrics.markGuardrail(kind, "pass")
            nextGuardrail(seq.tail, newMessages)
        }.recover {
          case e => AiMetrics.markGuardrail(kind, "error"); GuardrailResult.GuardrailDenied(e.getMessage)
        }
      }
    }

    nextGuardrail(allGuardrails, messages)
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
  // a guardrail can rewrite the messages it inspected (e.g. PII redaction) and let the
  // (possibly transformed) messages flow downstream instead of just passing/denying
  case class GuardrailTransform(messages: Seq[ChatMessage]) extends GuardrailResult
}

trait Guardrail {
  def isBefore: Boolean
  def isAfter: Boolean
  def manyMessages: Boolean
  def pass(messages: Seq[ChatMessage], config: JsObject, provider: Option[AiProvider], chatClient: Option[ChatClient], attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[GuardrailResult]
}

class ChatClientWithGuardrailsValidation(originalProvider: AiProvider, val chatClient: ChatClient) extends DecoratorChatClient {

  private def deniedResponse(msg: String): ChatResponse = ChatResponse(
    Seq(ChatGeneration(ChatMessage.output(role = "assistant", content = msg, prefix = None, raw = Json.obj()))),
    ChatResponseMetadata.empty,
    Json.obj(),
  )

  // resolves the Before phase: Left = short-circuit result to render (deny/error),
  // Right = the effective prompt to forward (original on pass, rewritten on transform)
  private def resolveBefore(originalPrompt: ChatPrompt, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[GuardrailResult, ChatPrompt]] = {
    originalProvider.guardrails.call(GuardrailsCallPhase.Before, originalPrompt.messages, originalProvider, chatClient, attrs).map {
      case GuardrailResult.GuardrailPass => Right(originalPrompt)
      case GuardrailResult.GuardrailTransform(newMessages) => Right(originalPrompt.copy(messages = newMessages.collect { case i: InputChatMessage => i }))
      case other => Left(other)
    }
  }

  // runs the After phase on a (non-streamed) response, re-inflating placeholders when a guardrail transforms the output
  private def runAfter(r: ChatResponse, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    originalProvider.guardrails.call(GuardrailsCallPhase.After, r.generations.map(_.message), originalProvider, chatClient, attrs).map {
      case GuardrailResult.GuardrailError(err) => Left(Json.obj("error" -> "bad_request", "error_description" -> err, "phase" -> "after"))
      case GuardrailResult.GuardrailDenied(msg) if originalProvider.guardrailsFailOnDeny => Left(Json.obj("error" -> "guardrail_denied", "error_description" -> msg, "phase" -> "after"))
      case GuardrailResult.GuardrailDenied(msg) => Right(deniedResponse(msg))
      case GuardrailResult.GuardrailTransform(newMessages) =>
        val newOutputs = newMessages.collect { case o: OutputChatMessage => o }
        if (newOutputs.size == r.generations.size) {
          Right(r.copy(generations = r.generations.zip(newOutputs).map { case (g, m) => g.copy(message = m) }))
        } else {
          Right(r) // size mismatch: skip re-inflation rather than drop generations
        }
      case GuardrailResult.GuardrailPass => Right(r)
    }
  }

  override def call(originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    resolveBefore(originalPrompt, attrs).flatMap {
      case Left(GuardrailResult.GuardrailError(err)) => Left(Json.obj("error" -> "bad_request", "error_description" -> err, "phase" -> "before")).vfuture
      case Left(GuardrailResult.GuardrailDenied(msg)) if originalProvider.guardrailsFailOnDeny => Left(Json.obj("error" -> "guardrail_denied", "error_description" -> msg, "phase" -> "before")).vfuture
      case Left(GuardrailResult.GuardrailDenied(msg)) => Right(deniedResponse(msg)).vfuture
      case Left(_) => Left(Json.obj("error" -> "bad_request", "error_description" -> "unexpected guardrail result", "phase" -> "before")).vfuture
      case Right(effectivePrompt) => chatClient.call(effectivePrompt, attrs, originalBody).flatMap {
        case Left(err) => Left(err).vfuture
        case Right(r) => runAfter(r, attrs)
      }
    }
  }

  override def stream(originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    resolveBefore(originalPrompt, attrs).flatMap {
      case Left(GuardrailResult.GuardrailError(err)) => Left(Json.obj("error" -> "bad_request", "error_description" -> err, "phase" -> "before")).vfuture
      case Left(GuardrailResult.GuardrailDenied(msg)) => Right(deniedResponse(msg).toSource(originalBody.select("model").asOpt[String].getOrElse("model"))).vfuture
      case Left(_) => Left(Json.obj("error" -> "bad_request", "error_description" -> "unexpected guardrail result", "phase" -> "before")).vfuture
      case Right(effectivePrompt) => chatClient.stream(effectivePrompt, attrs, originalBody)
    }
  }

  override def completion(originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    resolveBefore(originalPrompt, attrs).flatMap {
      case Left(GuardrailResult.GuardrailError(err)) => Left(Json.obj("error" -> "bad_request", "error_description" -> err, "phase" -> "before")).vfuture
      case Left(GuardrailResult.GuardrailDenied(msg)) if originalProvider.guardrailsFailOnDeny => Left(Json.obj("error" -> "guardrail_denied", "error_description" -> msg, "phase" -> "before")).vfuture
      case Left(GuardrailResult.GuardrailDenied(msg)) => Right(deniedResponse(msg)).vfuture
      case Left(_) => Left(Json.obj("error" -> "bad_request", "error_description" -> "unexpected guardrail result", "phase" -> "before")).vfuture
      case Right(effectivePrompt) => chatClient.completion(effectivePrompt, attrs, originalBody).flatMap {
        case Left(err) => Left(err).vfuture
        case Right(r) => runAfter(r, attrs)
      }
    }
  }

  override def completionStream(originalPrompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    resolveBefore(originalPrompt, attrs).flatMap {
      case Left(GuardrailResult.GuardrailError(err)) => Left(Json.obj("error" -> "bad_request", "error_description" -> err, "phase" -> "before")).vfuture
      case Left(GuardrailResult.GuardrailDenied(msg)) => Right(deniedResponse(msg).toSource(originalBody.select("model").asOpt[String].getOrElse("model"))).vfuture
      case Left(_) => Left(Json.obj("error" -> "bad_request", "error_description" -> "unexpected guardrail result", "phase" -> "before")).vfuture
      case Right(effectivePrompt) => chatClient.completionStream(effectivePrompt, attrs, originalBody)
    }
  }
}
