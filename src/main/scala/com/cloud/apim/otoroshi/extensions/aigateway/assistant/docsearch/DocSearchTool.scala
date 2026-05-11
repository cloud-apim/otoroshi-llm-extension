package com.cloud.apim.otoroshi.extensions.aigateway.assistant.docsearch

import com.cloud.apim.otoroshi.extensions.aigateway.assistant.tools.{AssistantTool, ToolCallContext, ToolDefinition}
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}

class DocSearchTool extends AssistantTool {

  override def definition: ToolDefinition = ToolDefinition(
    name = "doc_search",
    description =
      """Hybrid semantic + lexical search across the Otoroshi and Cloud APIM documentation sites (Otoroshi core, LLM extension, Biscuit Studio). Returns up to 8 ranked snippets with title, breadcrumb, URL (with anchor), and excerpt.
        |
        |Use this for *conceptual* questions: "how does X work", "what is Y", "where do I configure Z", "is there a way to ...". Free-form natural-language queries work — no need to compress to keywords.
        |
        |Companions (not replacements):
        | - `search`: callable Admin API operations (when you need an *endpoint* to call).
        | - `doc`: fetches the full text of one specific page, once you have its URL.
        |
        |Typical workflow: `doc_search` to find relevant pages → `doc` with one of the returned URLs if you need the full content.""".stripMargin,
    parameters = Json.obj(
      "type" -> "object",
      "properties" -> Json.obj(
        "query" -> Json.obj(
          "type" -> "string",
          "description" -> "Free-form natural-language question or keywords. Examples: \"how to configure semantic cache\", \"biscuit token attenuation\", \"llm guardrails regex\"."
        ),
        "corpus" -> Json.obj(
          "type" -> "string",
          "description" -> "Optional. Restrict to one corpus id: \"otoroshi\", \"llm-extension\", or \"biscuit-studio\". Omit to search everything."
        )
      ),
      "required" -> Json.arr("query")
    )
  )

  override def call(arguments: JsValue, ctx: ToolCallContext)(implicit ec: ExecutionContext): Future[String] = {
    val query = arguments.select("query").asOpt[String].map(_.trim).getOrElse("")
    if (query.isEmpty) return Future.successful("Error: missing 'query' argument.")
    val corpus = arguments.select("corpus").asOpt[String].map(_.trim).filter(_.nonEmpty)
    println(s"call tool 'doc_search': query=$query corpus=${corpus.getOrElse("<all>")}")
    implicit val env: otoroshi.env.Env = ctx.env
    DocSearchIndex.get().search(query, corpus).map {
      case Left(message) => s"doc_search: $message"
      case Right(results) if results.isEmpty =>
        s"""No results for "$query"${corpus.map(c => s" in corpus '$c'").getOrElse("")}. Try a different phrasing, drop the corpus filter, or use `doc({ topic })` to discover starting-point URLs."""
      case Right(results) =>
        val header = s"""Found ${results.size} result(s) for "$query"${corpus.map(c => s" in corpus '$c'").getOrElse("")}:\n\n"""
        val body = results.zipWithIndex.map { case (r, idx) => formatResult(idx + 1, r) }.mkString("\n\n---\n\n")
        AssistantTool.truncate(header + body)
    }.map { response =>
      println(s"call tool 'doc_search' response length: ${response.length}\n\n----------------------------------\n\n")
      response
    }
  }

  private val excerptMaxChars: Int = 400

  private def formatResult(rank: Int, r: DocSearchResult): String = {
    val chunk = r.chunk
    val sb = new StringBuilder
    sb.append(s"### $rank. ${chunk.title}")
    chunk.heading.foreach(h => if (h != chunk.title) sb.append(s" — $h"))
    sb.append('\n')
    if (chunk.breadcrumb.nonEmpty) sb.append(s"Breadcrumb: ${chunk.breadcrumb.mkString(" › ")}\n")
    sb.append(s"Corpus: ${chunk.corpusId}\n")
    sb.append(s"URL: ${chunk.url}\n")
    val excerpt = if (chunk.text.length <= excerptMaxChars) chunk.text
    else chunk.text.substring(0, excerptMaxChars).trim + "…"
    if (excerpt.nonEmpty && excerpt != chunk.title) sb.append('\n').append(excerpt)
    sb.toString.trim
  }
}
