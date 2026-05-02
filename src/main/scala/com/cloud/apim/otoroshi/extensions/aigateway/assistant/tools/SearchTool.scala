package com.cloud.apim.otoroshi.extensions.aigateway.assistant.tools

import com.cloud.apim.otoroshi.extensions.aigateway.assistant.logic.Catalog
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}

class SearchTool extends AssistantTool {

  override def definition: ToolDefinition = ToolDefinition(
    name = "search",
    description =
      """Search Otoroshi Admin API operations loaded from the running instance's OpenAPI (core + installed extensions). Returns matching operations with HTTP signature, parameters, request body, and response shape.
        |
        |### Query rules — read carefully
        |- **Keep queries SHORT: 1 to 3 keywords.** All terms must match (AND-combined). Long queries return nothing.
        |- **Pattern: `<entity> <verb>`** — e.g. `route findall`, `apikey create`, `certificate delete`. One word is also fine when listing a category: `route`, `certificate`.
        |- **Synonyms are handled automatically** — pick ONE verb, don't list them all. Built-in synonym groups: list↔findall↔getall↔all, get↔find↔read↔fetch↔retrieve, create↔add↔new, update↔modify↔edit, delete↔remove↔destroy. Same for entity plurals (route↔routes).
        |- **Don't include in the query**: HTTP methods (GET/POST/…), URL paths or path fragments (`/apis/...`, `proxy.otoroshi.io`, `v1`), parameter names, body field names. They add noise and rarely match.
        |- **If a search returns nothing, retry with FEWER terms, not more.** Drop one keyword at a time until something hits, then refine.
        |
        |### Examples
        |- ✅ `route findall` — list routes
        |- ✅ `apikey create` — create an apikey
        |- ✅ `certificate` — all certificate operations
        |- ✅ `llm provider` — LLM provider operations
        |- ❌ `create route POST frontend backend targets` — too many words, no match
        |- ❌ `list /apis/proxy.otoroshi.io/v1/routes` — paths don't match the haystack
        |- ❌ `list create get all routes` — mixed verbs cancel each other out
        |
        |Use this tool when you need to *call* an Otoroshi endpoint. For *conceptual* questions (how does X work?), use the 'doc' tool instead.
        |
        |Next step after search: pass the operationId to 'execute' to actually run the call.""".stripMargin,
    parameters = Json.obj(
      "type" -> "object",
      "properties" -> Json.obj(
        "query" -> Json.obj(
          "type" -> "string",
          "description" -> "1 to 3 keywords, typically `<entity> <verb>`. Examples: \"route findall\", \"apikey create\", \"certificate delete\", \"llm provider\". Do NOT include HTTP methods, URL paths, or field names. Synonyms (list/findall, create/add/new, delete/remove, ...) are expanded automatically — pick one. If a search misses, retry with fewer terms.",
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
      val termCount = query.split("""\s+""").count(_.nonEmpty)
      val sampleTags = catalog.tags.take(10).mkString(", ")
      val more = if (catalog.tags.size > 10) ", ..." else ""
      val retryHint = if (termCount > 2)
        s"""Your query has $termCount terms — too specific. RETRY with 1 or 2 keywords only, pattern `<entity> <verb>` (e.g. "route findall", "apikey create"). Drop HTTP methods, paths, and field names."""
      else
        "Try a different keyword (e.g. the entity name only, or a tag name)."
      s"""No operations found for "$query". $retryHint If the question is conceptual rather than about an API call, use the 'doc' tool. Available tags (sample): $sampleTags$more"""
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
