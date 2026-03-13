package com.cloud.apim.otoroshi.extensions.aigateway.guardrails

import com.cloud.apim.otoroshi.extensions.aigateway.{ChatClient, ChatMessage, ChatMessageContentFlavor, InputChatMessage, OutputChatMessage}
import com.cloud.apim.otoroshi.extensions.aigateway.decorators.{Guardrail, GuardrailResult}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import otoroshi.env.Env
import otoroshi.next.workflow.{Node, WorkflowAdminExtension}
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsArray, JsNull, JsObject, Json}

import scala.concurrent.{ExecutionContext, Future}

class WorkflowGuardrail extends Guardrail {

  override def isBefore: Boolean = true

  override def isAfter: Boolean = true

  override def manyMessages: Boolean = true

  override def pass(messages: Seq[ChatMessage], config: JsObject, provider: Option[AiProvider], chatClient: Option[ChatClient], attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[GuardrailResult] = {
    val workflowId: Option[String] = config.select("workflow_id").asOpt[String].orElse(config.select("workflow_ref").asOpt[String]).filter(_.trim.nonEmpty)
    workflowId match {
      case None => GuardrailResult.GuardrailError("error, no workflow ref").vfuture
      case Some(ref) => {
        env.adminExtensions.extension[WorkflowAdminExtension] match {
          case None => GuardrailResult.GuardrailError("workflow extension not found").vfuture
          case Some(extension) => {
            extension.workflow(ref) match {
              case None => GuardrailResult.GuardrailError("workflow not found").vfuture
              case Some(workflow) => {
                val arr = messages.map {
                  case i: InputChatMessage => i.json(ChatMessageContentFlavor.Common)
                  case o: OutputChatMessage => o.json
                }
                val input = Json.obj(
                  "config" -> config,
                  "provider" -> provider.map(_.json).getOrElse(JsNull).asValue,
                  "attrs" -> attrs.json,
                  "messages" -> JsArray(arr),
                )
                extension.engine.run(ref, Node.from(workflow.config), input, attrs, workflow.functions).map { res =>
                  res.error match {
                    case Some(_) => GuardrailResult.GuardrailDenied(res.error.map(_.json.stringify).getOrElse("workflow execution error"))
                    case None => {
                      val returned = res.returned.getOrElse(Json.obj())
                      val pass = returned.select("pass").asOpt[Boolean].getOrElse(false)
                      if (pass) {
                        GuardrailResult.GuardrailPass
                      } else {
                        val reason = returned.select("reason").asOpt[String].getOrElse("message blocked by workflow guardrail")
                        GuardrailResult.GuardrailDenied(reason)
                      }
                    }
                  }
                }.recover {
                  case e: Exception => GuardrailResult.GuardrailError(s"workflow execution failed: ${e.getMessage}")
                }
              }
            }
          }
        }
      }
    }
  }
}
