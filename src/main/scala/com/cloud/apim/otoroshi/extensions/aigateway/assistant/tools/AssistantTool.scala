package com.cloud.apim.otoroshi.extensions.aigateway.assistant.tools

import otoroshi.env.Env
import otoroshi.models.BackOfficeUser
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}

case class ToolCallContext(env: Env, ext: AiExtension, user: Option[BackOfficeUser])

case class ToolDefinition(name: String, description: String, parameters: JsObject)

trait AssistantTool {
  def definition: ToolDefinition
  def call(arguments: JsValue, ctx: ToolCallContext)(implicit ec: ExecutionContext): Future[String]

  def openaiJson: JsObject = Json.obj(
    "type" -> "function",
    "function" -> Json.obj(
      "name" -> definition.name,
      "description" -> definition.description,
      "parameters" -> definition.parameters,
    ),
  )
}

object AssistantTool {
  val defaultMaxOutput: Int = 24576

  def truncate(text: String, max: Int = defaultMaxOutput): String =
    if (text.length <= max) text
    else s"${text.substring(0, max)}\n\n... (truncated, ${text.length} total chars, limit $max)"
}
