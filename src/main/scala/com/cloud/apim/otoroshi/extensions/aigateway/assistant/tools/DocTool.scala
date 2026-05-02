package com.cloud.apim.otoroshi.extensions.aigateway.assistant.tools

import com.cloud.apim.otoroshi.extensions.aigateway.assistant.logic.DocResource
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}

class DocTool extends AssistantTool {

  private val allowlistLabel: String = DocResource.Allowlist.toSeq.sorted.mkString(", ")

  override def definition: ToolDefinition = ToolDefinition(
    name = "doc",
    description =
      s"""Fetch Otoroshi conceptual documentation (architecture, concepts, tutorials, extension guides). Use this for *how does X work* questions, not for finding callable API operations — for those, use the 'search' tool.
         |
         |Two modes, provide exactly one (or neither to list everything):
         |- `topic`: list curated starting-point URLs, optionally filtered by keyword. Start here.
         |- `url`: fetch a documentation page and return its text content. URL host must be in the allowlist: $allowlistLabel.""".stripMargin,
    parameters = Json.obj(
      "type" -> "object",
      "properties" -> Json.obj(
        "topic" -> Json.obj(
          "type" -> "string",
          "description" -> "Optional keyword to filter starting points. Examples: \"biscuit\", \"llm\", \"apikey\", \"overview\". Mutually exclusive with `url`.",
        ),
        "url" -> Json.obj(
          "type" -> "string",
          "description" -> s"Full URL of a documentation page. Host must be one of: $allowlistLabel. Mutually exclusive with `topic`.",
        ),
      ),
    ),
  )

  override def call(arguments: JsValue, ctx: ToolCallContext)(implicit ec: ExecutionContext): Future[String] = {
    val topic = arguments.select("topic").asOpt[String].map(_.trim).filter(_.nonEmpty)
    val url = arguments.select("url").asOpt[String].map(_.trim).filter(_.nonEmpty)

    if (topic.isDefined && url.isDefined) Future.successful("Error: provide either 'topic' or 'url', not both.")
    else url match {
      case Some(u) => fetchUrl(u)(ec, ctx.env)
      case None => Future.successful(renderStartingPoints(topic))
    }
  }

  private def renderStartingPoints(topic: Option[String]): String = {
    val matches = DocResource.listStartingPoints(topic)
    if (matches.isEmpty) {
      val knownTopics = DocResource.StartingPoints.map(_.topic).mkString(", ")
      s"""No starting points match "${topic.getOrElse("")}". Known topics: $knownTopics"""
    } else {
      val body = matches.map(sp => s"- [${sp.topic}] ${sp.title}\n  ${sp.url}\n  ${sp.description}").mkString("\n\n")
      val header = s"${matches.size} starting point(s). Fetch one with `doc({ url })`. Allowlisted hosts: $allowlistLabel.\n\n"
      AssistantTool.truncate(header + body)
    }
  }

  private def fetchUrl(url: String)(implicit ec: ExecutionContext, env: otoroshi.env.Env): Future[String] = {
    DocResource.fetch(url).map {
      case DocResource.FetchResult.Ok(content) => AssistantTool.truncate(content)
      case DocResource.FetchResult.InvalidUrl(msg) => s"Error: $msg"
      case DocResource.FetchResult.NotAllowed(host) => s"""Error: host "$host" is not allowlisted. Allowed: $allowlistLabel"""
      case DocResource.FetchResult.Failed(msg) => s"Error: $msg. Try `doc({ topic })` to discover known-good URLs, or `search` if you wanted an API operation."
    }
  }
}
