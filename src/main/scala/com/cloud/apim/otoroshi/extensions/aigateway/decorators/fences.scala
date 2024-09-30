package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatClient, ChatGeneration, ChatMessage, ChatPrompt, ChatResponse, ChatResponseMetadata}
import com.cloud.apim.otoroshi.extensions.aigateway.fences._
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{Format, JsArray, JsError, JsObject, JsResult, JsSuccess, JsValue, Json}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

sealed trait FencesCallPhase
object FencesCallPhase {
  case object Before extends FencesCallPhase
  case object After extends FencesCallPhase
}

object Fences {
  val empty = Fences(Seq.empty)
  val format = new Format[Fences] {
    override def reads(json: JsValue): JsResult[Fences] = Try {
      Fences(json.asOpt[Seq[JsObject]].map(seq => seq.flatMap(i => FenceItem.format.reads(i).asOpt)).getOrElse(Seq.empty))
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(v) => JsSuccess(v)
    }
    override def writes(o: Fences): JsValue = JsArray(o.items.map(_.json))
  }
  val possibleFences: Map[String, Fence] = Map(
    "regex" -> new RegexFence(),
    "webhook" -> new WebhookFence(),
    "llm" -> new LLMFence(),
    "gibberish" -> new GibberishFence(),
    "pif" -> new PersonalInformationsFence(),
    "moderation" -> new LanguageModerationFence(),
    "sentences" -> new SentencesCountFence(),
    "words" -> new WordsCountFence(),
    "characters" -> new CharactersCountFence(),
    "contains" -> new ContainsFence(),
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
case class Fences(items: Seq[FenceItem]) {

  def nonEmpty: Boolean = items.nonEmpty
  def isEmpty: Boolean = items.isEmpty
  def json: JsValue = Fences.format.writes(this)

  def call(callPhase: FencesCallPhase, messages: Seq[ChatMessage], originalProvider: AiProvider, chatClient: ChatClient, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[FenceResult] = {

    val allFences = originalProvider.fences.items.filter(_.enabled).flatMap { item =>
      Fences.possibleFences.get(item.fenceId).map(fence => (item, fence))
    }.filter { fence =>
      callPhase match {
        case FencesCallPhase.Before => fence._1.before && fence._2.isBefore
        case FencesCallPhase.After => fence._1.after && fence._2.isAfter
      }
    }

    def nextMessage(seq: Seq[ChatMessage], fence: Fence, config: JsObject): Future[FenceResult] = {
      if (seq.nonEmpty) {
        val head = seq.head
        fence.pass(Seq(head), config, originalProvider, chatClient, attrs).andThen {
          case Success(FenceResult.FencePass) => nextMessage(seq.tail, fence, config)
          case Success(FenceResult.FenceDenied(err)) => FenceResult.FenceDenied(err).vfuture
          case Failure(e) => FenceResult.FenceDenied(e.getMessage).vfuture
        }
      } else {
        FenceResult.FencePass.vfuture
      }
    }

    def nextFence(seq: Seq[(FenceItem, Fence)]): Future[FenceResult] = {
      if (seq.nonEmpty) {
        val head = seq.head
        if (head._2.manyMessages) {
          head._2.pass(messages, head._1.config, originalProvider, chatClient, attrs).andThen {
            case Success(FenceResult.FencePass) => nextFence(seq.tail)
            case Success(FenceResult.FenceDenied(msg)) => FenceResult.FenceDenied(msg).vfuture
            case Success(FenceResult.FenceError(err)) => FenceResult.FenceError(err).vfuture
            case Failure(e) => FenceResult.FenceDenied(e.getMessage).vfuture
          }
        } else {
          nextMessage(messages, head._2, head._1.config).andThen {
            case Success(FenceResult.FencePass) => nextFence(seq.tail)
            case Success(FenceResult.FenceError(err)) => FenceResult.FenceError(err).vfuture
            case Success(FenceResult.FenceDenied(msg)) => FenceResult.FenceDenied(msg).vfuture
            case Failure(e) => FenceResult.FenceDenied(e.getMessage).vfuture
          }
        }
      } else {
        FenceResult.FencePass.vfuture
      }
    }

    nextFence(allFences)
  }
}

object FenceItem {
  val format = new Format[FenceItem] {
    override def reads(json: JsValue): JsResult[FenceItem] = Try {
      FenceItem(
        enabled = json.select("enabled").asOpt[Boolean].getOrElse(false),
        before = json.select("before").asOpt[Boolean].getOrElse(false),
        after = json.select("after").asOpt[Boolean].getOrElse(false),
        fenceId = json.select("id").asString,
        config = json.select("config").asOpt[JsObject].getOrElse(Json.obj()),
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(v) => JsSuccess(v)
    }
    override def writes(o: FenceItem): JsValue = Json.obj(
      "enabled" -> o.enabled,
      "before" -> o.before,
      "after" -> o.after,
      "id" -> o.fenceId,
      "config" -> o.config,
    )
  }
}

case class FenceItem(enabled: Boolean, before: Boolean, after: Boolean, fenceId: String, config: JsObject) {
  def json: JsValue = FenceItem.format.writes(this)
}

object ChatClientWithFencesValidation {
  def applyIfPossible(tuple: (AiProvider, ChatClient)): ChatClient = {
    if (tuple._1.fences.nonEmpty) {
      new ChatClientWithFencesValidation(tuple._1, tuple._2)
    } else {
      tuple._2
    }
  }
}

sealed trait FenceResult

object FenceResult {
  case object FencePass extends FenceResult
  case class FenceDenied(message: String) extends FenceResult
  case class FenceError(error: String) extends FenceResult
}

trait Fence {
  def isBefore: Boolean
  def isAfter: Boolean
  def manyMessages: Boolean
  def pass(messages: Seq[ChatMessage], config: JsObject, provider: AiProvider, chatClient: ChatClient, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[FenceResult]
}

class ChatClientWithFencesValidation(originalProvider: AiProvider, chatClient: ChatClient) extends ChatClient {

  override def model: Option[String] = chatClient.model

  override def call(originalPrompt: ChatPrompt, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    originalProvider.fences.call(FencesCallPhase.Before, originalPrompt.messages, originalProvider, chatClient, attrs).flatMap {
      case FenceResult.FenceError(err) => Left(Json.obj("error" -> "bad_request", "error_description" -> err, "phase" -> "before")).vfuture
      case FenceResult.FenceDenied(msg) => Right(ChatResponse(
        Seq(ChatGeneration(ChatMessage(role = "assistant", content = msg))),
        ChatResponseMetadata.empty,
      )).vfuture
      case FenceResult.FencePass => {
        chatClient.call(originalPrompt, attrs).flatMap {
          case Left(err) => Left(err).vfuture
          case Right(r) => {
            originalProvider.fences.call(FencesCallPhase.After, r.generations.map(_.message), originalProvider, chatClient, attrs).flatMap {
              case FenceResult.FenceError(err) => Left(Json.obj("error" -> "bad_request", "error_description" -> err, "phase" -> "after")).vfuture
              case FenceResult.FenceDenied(msg) => Right(ChatResponse(
                Seq(ChatGeneration(ChatMessage(role = "assistant", content = msg))),
                ChatResponseMetadata.empty,
              )).vfuture
              case FenceResult.FencePass => Right(r).vfuture
            }
          }
        }
      }
    }
  }
}
