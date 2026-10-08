package com.cloud.apim.otoroshi.extensions.aigateway.guardrails

import com.cloud.apim.otoroshi.extensions.aigateway.decorators.{ChildCall, Guardrail, GuardrailResult}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatClient, ChatMessage, DecisionModelClientInputOptions, DecisionRequests}
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}

import scala.concurrent.{ExecutionContext, Future}

object DecisionGuardrail {

  val questionName = "guardrail"
  val defaultThreshold = 0.5
  val defaultMessage = "request content did not pass the decision model validation"

  /**
   * The question asked about the messages. The simple form is a yes/no one, `instructions` and a `threshold`;
   * `question` gives a whole System One question of any type, with its criteria.
   */
  def questionOf(config: JsObject): Option[JsObject] = {
    config.select("question").asOpt[JsObject].filter(_.value.nonEmpty).orElse {
      config.select("instructions").asOptString.map(_.trim).filter(_.nonEmpty).map { instructions =>
        Json.obj("type" -> DecisionRequests.Noul, "instructions" -> instructions)
      }
    }
  }

  // the messages as the state the question is asked about
  def stateOf(messages: Seq[ChatMessage]): JsValue = JsArray(messages.map(m => Json.obj("role" -> m.role, "content" -> m.wholeTextContent)))

  /**
   * Whether an answer denies the messages:
   * - noul: the probability of yes reaches `threshold`
   * - choice: the chosen option is one of `deny_choices`, with at least `min_confidence`
   * - score: the score reaches `max_score`
   * - a refusal to answer: what a model will not judge does not go through
   */
  def denies(config: JsObject, answer: JsValue): Boolean = answer.select("type").asOptString match {
    case Some(DecisionRequests.Refusal) => true
    case Some(DecisionRequests.Noul) =>
      answer.select("noul").asOpt[Double].exists(_ >= config.select("threshold").asOpt[Double].getOrElse(defaultThreshold))
    case Some(DecisionRequests.Choice) =>
      val denied = config.select("deny_choices").asOpt[Seq[String]].getOrElse(Seq.empty)
      val confident = answer.select("confidence").asOpt[Double].getOrElse(1.0) >= config.select("min_confidence").asOpt[Double].getOrElse(0.0)
      answer.select("choice").asOptString.exists(denied.contains) && confident
    case Some(DecisionRequests.Score) =>
      (for {
        score <- answer.select("score").asOpt[Double]
        max   <- config.select("max_score").asOpt[Double]
      } yield score >= max).getOrElse(false)
    case _ => false
  }
}

class DecisionGuardrail extends Guardrail {

  import DecisionGuardrail.*

  override def isBefore: Boolean = true

  override def isAfter: Boolean = true

  override def manyMessages: Boolean = true

  override def pass(messages: Seq[ChatMessage], config: JsObject, provider: Option[AiProvider], chatClient: Option[ChatClient], attrs: TypedMap)(using ec: ExecutionContext, env: Env): Future[GuardrailResult] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    (config.select("decision_model").asOptString.flatMap(ref => ext.states.decisionModel(ref)), questionOf(config)) match {
      case (None, _) => GuardrailResult.GuardrailError("Decision model not found").vfuture
      case (_, None) => GuardrailResult.GuardrailError("Decision guardrail has no question").vfuture
      case (Some(decision), Some(question)) => {
        // the model of the decision model entity to use, its default model when empty
        decision.withModel(config.select("model").asOptString).getDecisionModelClient() match {
          case None => GuardrailResult.GuardrailError("Decision client not found").vfuture
          case Some(client) => {
            val opts = DecisionModelClientInputOptions(stateOf(messages), Json.obj(questionName -> question))
            // its own attributes: auditing the decision sets the provider and the model budgets are scoped on,
            // and those of the chat call being guarded must stay the ones of that call
            client.decide(opts, opts.json.asObject, ChildCall.attrs(attrs)).map {
              case Left(err) => GuardrailResult.GuardrailError(err.stringify)
              case Right(res) => {
                if (denies(config, res.answers.select(questionName).asOpt[JsValue].getOrElse(Json.obj()))) {
                  GuardrailResult.GuardrailDenied(config.select("err_msg").asOptString.map(_.trim).filter(_.nonEmpty).getOrElse(defaultMessage))
                } else {
                  GuardrailResult.GuardrailPass
                }
              }
            }
          }
        }
      }
    }
  }
}
