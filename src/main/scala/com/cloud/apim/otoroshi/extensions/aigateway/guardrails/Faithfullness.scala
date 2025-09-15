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

case class FaithfulnessOutput(statement: String, reason: String, verdict: Int)

class FaithfulnessGuardrail extends Guardrail {

  override def isBefore: Boolean = true

  override def isAfter: Boolean = true

  override def manyMessages: Boolean = true

  def pass(): Future[GuardrailResult] = GuardrailResult.GuardrailPass.vfuture

  def fail(): Future[GuardrailResult] = GuardrailResult.GuardrailDenied(s"request content is not faithful to the provided context").vfuture

  def createStatements(userInput: String, ref: String, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Seq[String]] = {
    val instructions = """Given a question and an answer, analyze the complexity of each sentence in the answer.
        |Break down each sentence into one or more fully understandable statements.
        |Ensure that no pronouns are used in any statement.
        |Format the outputs in a JSON array containing simple strings.""".stripMargin
    env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(ref).flatMap(_.getChatClient())) match {
      case None => Future.failed(new RuntimeException(s"No chat client for $ref found"))
      case Some(validationClient) => {
        validationClient.call(ChatPrompt(Seq(
          ChatMessage.input("system", instructions, None, Json.obj()),
          ChatMessage.input("user", userInput, None, Json.obj()),
        )), attrs, Json.obj()).flatMap {
          case Left(err) => Future.failed(new RuntimeException(err.stringify))
          case Right(resp) => {
            val content = resp.generations.head.message.content
            // println("createStatements raw response: " + content)
            val cleanup = content.replace("```json", "").replace("```", "").trim
            val res = Json.parse(cleanup).asOpt[Seq[String]].getOrElse(Seq.empty)
            // println("createStatements response: " + res)
            res.vfuture
          }
        }
      }
    }
  }

  def createVerdicts(context: String, statements: Seq[String], outOfScope: Boolean, ref: String, attrs: TypedMap)(implicit env: Env, ec: ExecutionContext): Future[Seq[FaithfulnessOutput]] = {
    val instructions_notOutOfScope =
      """Your task is to judge the faithfulness of a series of statements based on a given context.
        |For each statement you must return verdict as 1 if the statement can be directly inferred based
        |on the context or 0 if the statement can not be directly inferred based on the context.
        |Respond with a JSON array containing objects like {"statement": "the original statement", "reason": "why you gave this verdict", "verdict": 1 }.
        |""".stripMargin
    val instructions_outOfScope =
      """Your task is to judge the faithfulness of a series of statements based on a given context.
        |For each statement referring to the provided context, you must return verdict as 1 if the statement can be directly inferred based
        |on the context or 0 if the statement can not be directly inferred based on the context. If a statement does not refer to the context at all, return 1.
        |Respond with a JSON array containing objects like {"statement": "the original statement", "reason": "why you gave this verdict", "verdict": 1 }.
        |""".stripMargin
    env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(ref).flatMap(_.getChatClient())) match {
      case None => Future.failed(new RuntimeException(s"No chat client for $ref found"))
      case Some(validationClient) => {
        val instructions = if (outOfScope) instructions_outOfScope else instructions_notOutOfScope
        validationClient.call(ChatPrompt(Seq(
          ChatMessage.input("system", instructions, None, Json.obj()),
          ChatMessage.input("user", s"<context>${context}</context>\n\n<statements>${statements.map(s => s"<statement>${s}</statement>").mkString("\n")}</statements>", None, Json.obj()),
        )), attrs, Json.obj()).flatMap {
          case Left(err) => Future.failed(new RuntimeException(err.stringify))
          case Right(resp) => {
            val content = resp.generations.head.message.content
            // println("createVerdicts raw response: " + content)
            val cleanup = content.replace("```json", "").replace("```", "").trim
            val res = Json.parse(cleanup).asOpt[Seq[JsObject]].getOrElse(Seq.empty).map(o => FaithfulnessOutput(
              statement = o.select("statement").asString,
              reason = o.select("reason").asString,
              verdict = o.select("verdict").asInt,
            ))
            // println("createVerdicts response: " + res)
            res.vfuture
          }
        }
      }
    }
  }

  def computeScore(verdicts: Seq[FaithfulnessOutput]): Double = {
    val sum = verdicts.foldLeft(0)((a, b) => a + b.verdict)
    sum.toDouble / verdicts.size
  }


  override def pass(_messages: Seq[ChatMessage], config: JsObject, provider: Option[AiProvider], chatClient: Option[ChatClient], attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[GuardrailResult] = {
    val provider = config.select("ref").asOpt[String].orElse(config.select("provider").asOpt[String]).get
    val outOfScope = config.select("exclude_out_of_scope_statements").asOptBoolean.getOrElse(true)
    val context = config.select("context").asOpt[Seq[String]].map(_.mkString(". ")).orElse(config.select("context").asOpt[String]).getOrElse("--")
    val userInput = _messages.map(_.wholeTextContent).mkString(". ")
    createStatements(userInput, provider, attrs).flatMap { statements =>
      createVerdicts(context, statements, outOfScope, provider, attrs).flatMap { verdicts =>
        val score = computeScore(verdicts)
        val threshold = config.select("threshold").asOpt[Double].getOrElse(0.8)
        // println(s"computed score: ${score} > ${threshold} = ${score > threshold}")
        if (score > threshold) {
          pass()
        } else {
          fail()
        }
      }
    }
  }
}
