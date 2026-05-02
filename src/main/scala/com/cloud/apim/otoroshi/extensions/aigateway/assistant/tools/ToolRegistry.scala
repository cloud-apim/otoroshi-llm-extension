package com.cloud.apim.otoroshi.extensions.aigateway.assistant.tools

import play.api.libs.json._

class ToolRegistry {
  private val tools: Seq[AssistantTool] = Seq(
    new SearchTool(),
    new ExecuteTool(),
    new DocTool(),
  )

  def all: Seq[AssistantTool] = tools

  def find(name: String): Option[AssistantTool] = tools.find(_.definition.name == name)

  def openaiJson: JsArray = JsArray(tools.map(_.openaiJson))
}

object ToolRegistry {
  lazy val default: ToolRegistry = new ToolRegistry()
}
