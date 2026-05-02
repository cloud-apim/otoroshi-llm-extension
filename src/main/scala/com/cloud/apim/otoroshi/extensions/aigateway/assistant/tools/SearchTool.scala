package com.cloud.apim.otoroshi.extensions.aigateway.assistant.tools

import com.cloud.apim.otoroshi.extensions.aigateway.assistant.logic.Catalog
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}

class SearchTool extends AssistantTool {

  override def definition: ToolDefinition = ToolDefinition(
    name = "search",
    description =
      """Search Otoroshi Admin API operations loaded from the running instance's OpenAPI (core + installed extensions).
        |Returns matching operations with HTTP signature, parameters, request body, and response shape.
        |
        |Use this when you need to *call* an Otoroshi endpoint. For *conceptual* questions (how does X work?), use the 'doc' tool instead.
        |
        |Next step after search: pass the operationId to 'execute' as `otoroshi.call(operationId, { pathParams, query, body })`.""".stripMargin,
    parameters = Json.obj(
      "type" -> "object",
      "properties" -> Json.obj(
        "query" -> Json.obj(
          "type" -> "string",
          "description" -> "Keywords matched against operationId, method, path, summary, description, tags, parameter names. Space-separated terms are AND-combined. Examples: \"list routes\", \"apikey create\", \"llm provider\", \"route delete\".",
        ),
      ),
      "required" -> Json.arr("query"),
    ),
  )

  override def call(arguments: JsValue, ctx: ToolCallContext)(implicit ec: ExecutionContext): Future[String] = {
    val query = arguments.select("query").asOpt[String].map(_.trim).getOrElse("")
    if (query.isEmpty) return Future.successful("Error: missing 'query' argument.")
    println(s"call tool 'search': ${query}")
    val catalog = Catalog.cached(ctx.env)
    val (results, totalMatches) = catalog.search(query, Catalog.defaultSearchLimit)
    val response = if (results.isEmpty) {
      val sampleTags = catalog.tags.take(10).mkString(", ")
      val more = if (catalog.tags.size > 10) ", ..." else ""
      s"""No operations found for "$query". Try a simpler term, a tag name, or the 'doc' tool for conceptual help. Available tags (sample): $sampleTags$more"""
    } else {
      val formatted = results.map(op => formatDetails(catalog, op.operationId)).mkString("\n\n---\n\n")
      val capped = totalMatches > results.size
      val cappedNote = if (capped) s" (showing top ${results.size}, refine query for the rest)" else ""
      val header = s"""Found $totalMatches operation(s) for "$query"$cappedNote:\n\n"""
      AssistantTool.truncate(header + formatted)
    }
    println(s"call tool 'search' response: ${response}\n\n----------------------------------\n\n")
    Future.successful(response)
  }

  private def formatDetails(catalog: Catalog.Document, operationId: String): String = {
    catalog.describe(operationId) match {
      case None => s"(unknown operationId: $operationId)"
      case Some(d) =>
        val op = d.operation
        val sb = new StringBuilder
        sb.append(s"### ${op.operationId}\n")
        val tags = if (op.tags.nonEmpty) s" — tags: ${op.tags.mkString(", ")}" else ""
        sb.append(s"`${op.method.toUpperCase} ${op.path}`$tags\n")
        op.summary.foreach(s => sb.append(s).append('\n'))
        if (d.paramSignatures.nonEmpty) {
          sb.append("Parameters:\n")
          d.paramSignatures.foreach { p =>
            val flag = if (p.required) " (required)" else ""
            val hint = p.description.map(s => s" — $s").getOrElse("")
            sb.append(s"  - ${p.name} [${p.in} param]$flag: ${p.`type`}$hint\n")
          }
        }
        d.requestBodyType.foreach(t => sb.append(s"Body: $t\n"))
        d.responseTypes.find { case (s, _) => s.startsWith("2") }.foreach { case (status, t) =>
          sb.append(s"Returns ($status): $t\n")
        }
        sb.toString.trim
    }
  }
}
