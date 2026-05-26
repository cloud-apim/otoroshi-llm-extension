package com.cloud.apim.otoroshi.extensions.aigateway.mcp

import com.cloud.apim.otoroshi.extensions.aigateway.assistant.docsearch.{DocChunk, HybridSearcher, LexicalIndex, SemanticIndex}
import com.cloud.apim.otoroshi.extensions.aigateway.assistant.logic.ExpressionLanguage
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{McpConnector, McpSupport}
import dev.langchain4j.agent.tool.{ToolExecutionRequest, ToolSpecification}
import dev.langchain4j.invocation.InvocationContext
import dev.langchain4j.mcp.client._
import dev.langchain4j.model.chat.request.json._
import dev.langchain4j.service.tool.ToolExecutionResult
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json._

import java.{util => ju}
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

private final case class MetaToolEntry(serverSlug: String, serverName: String, serverId: String, tool: ToolSpecification) {
  def docId: String = s"$serverSlug:${tool.name()}"
  def description: String = Option(tool.description()).getOrElse("")
}

private final case class MetaSearchIndexEntry(
  lexical: LexicalIndex,
  semantic: Option[SemanticIndex],
  entries: Map[String, MetaToolEntry],
  fingerprint: String,
)

object MetaMcpClient {

  // Cache of (lexical/semantic search indices) per meta-connector id. Rebuilt when the
  // tool inventory fingerprint changes (i.e. sub-connectors added/removed or their tools change).
  private val searchIndices: TrieMap[String, MetaSearchIndexEntry] = new TrieMap()

  private val rrfK: Int = 60
  private val lexicalCandidates: Int = 50
  private val semanticCandidates: Int = 50
  private val defaultTopK: Int = 10

  def slug(name: String): String = {
    val lower = name.trim.toLowerCase
    val replaced = lower.replaceAll("[^a-z0-9]+", "-").stripPrefix("-").stripSuffix("-")
    if (replaced.isEmpty) "server" else replaced
  }

  def invalidate(connectorId: String): Unit = {
    searchIndices.remove(connectorId).foreach { e =>
      try e.lexical.close() catch { case _: Throwable => () }
    }
  }

  // ---------- Tool specifications ----------

  private def strField(desc: String): JsonStringSchema =
    JsonStringSchema.builder().description(desc).build()

  private val listServersTool: ToolSpecification = ToolSpecification.builder()
    .name("list_servers")
    .description(
      "List the MCP servers exposed by this meta connector. Returns a JSON array (as a string) of " +
        "`{slug, name, description}`. The `slug` is the identifier used in other tools (list_tools, " +
        "get_tool_schema, execute, search_tools)."
    )
    .parameters(JsonObjectSchema.builder().build())
    .build()

  private val listToolsTool: ToolSpecification = ToolSpecification.builder()
    .name("list_tools")
    .description(
      "List the tools exposed by one MCP server known to this meta connector. " +
        "Returns a JSON array (as a string) of `{name, description}`. Call `list_servers` first to obtain a valid `server` slug."
    )
    .parameters(
      JsonObjectSchema.builder()
        .addProperty("server", strField("Slug of the server (from list_servers)."))
        .required(ju.Arrays.asList("server"))
        .build()
    )
    .build()

  private val getToolSchemaTool: ToolSpecification = ToolSpecification.builder()
    .name("get_tool_schema")
    .description(
      "Return the JSON schema of a specific tool exposed by one MCP server. Returns a JSON object " +
        "(as a string) of shape `{server, tool, description, parameters}` where `parameters` follows JSON Schema."
    )
    .parameters(
      JsonObjectSchema.builder()
        .addProperty("server", strField("Slug of the server (from list_servers)."))
        .addProperty("tool", strField("Name of the tool (from list_tools)."))
        .required(ju.Arrays.asList("server", "tool"))
        .build()
    )
    .build()

  private val executeTool: ToolSpecification = ToolSpecification.builder()
    .name("execute")
    .description(
      """Run one or more tool calls against MCP servers in order. Each `calls` entry is `{server, tool, args, name?}`:
        |- `server` (required): server slug (from list_servers).
        |- `tool` (required): tool name (from list_tools).
        |- `args` (optional): JSON object passed as the tool arguments.
        |- `name` (optional): label used as the key in the response. Defaults to `call_{idx}` (0-indexed).
        |
        |Returns a JSON object (as a string) keyed by each call's name. Each entry is `{ok, result}` for a successful call or `{error}` for a failed one. Non-error results stay in the response; execution continues regardless.
        |
        |### Inter-call references (expression language)
        |Inside any string field of a later call (any string inside `args`), use `${<name>.<path>}` to inject a value extracted from a previous call's result. The referenced call must have already executed (order is preserved).
        |- `<name>` is the previous call's `name` (or `call_<idx>`).
        |- `<path>` walks into the entry, which has shape `{ok, result}` (or `{error}`). Use dotted keys and `[idx]` for array indices. The tool's own JSON output is under `.result`.
        |- Example: `${first.result.id}`, `${list.result[0].name}`.
        |- A whole-string placeholder preserves the value's type; a partial placeholder splices a stringified form.
        |- An unresolved reference produces `{error}` for that call; subsequent calls still run.
        |""".stripMargin
    )
    .parameters(
      JsonObjectSchema.builder()
        .addProperty(
          "calls",
          JsonArraySchema.builder()
            .description("Ordered list of tool calls to run.")
            .items(
              JsonObjectSchema.builder()
                .addProperty("name", strField("Optional label used as the key in the response."))
                .addProperty("server", strField("Server slug (from list_servers)."))
                .addProperty("tool", strField("Tool name (from list_tools)."))
                .addProperty("args", JsonObjectSchema.builder().description("JSON arguments for the tool.").build())
                .required(ju.Arrays.asList("server", "tool", "name", "args"))
                .build()
            )
            .build(),
        )
        .required(ju.Arrays.asList("calls"))
        .build()
    )
    .build()

  private val searchToolsTool: ToolSpecification = ToolSpecification.builder()
    .name("search_tools")
    .description(
      "Search tools across all (or a filtered subset of) MCP servers using BM25 over tool name and description. " +
        "If semantic search is enabled on this meta connector, results are fused with embedding-based similarity (MiniLM-L6-v2). " +
        "Returns a JSON array (as a string) of `{server, name, description, score}` ranked by relevance."
    )
    .parameters(
      JsonObjectSchema.builder()
        .addProperty("query", strField("Free-text query (a few keywords work best)."))
        .addProperty(
          "servers",
          JsonArraySchema.builder()
            .description("Optional list of server slugs to restrict the search. Empty if none specifically searched.")
            .items(strField("Server slug"))
            .build(),
        )
        .required(ju.Arrays.asList("query", "servers"))
        .build()
    )
    .build()

  val toolSpecs: Seq[ToolSpecification] = Seq(
    listServersTool,
    listToolsTool,
    getToolSchemaTool,
    executeTool,
    searchToolsTool,
  )

  val toolSpecsByName: Map[String, ToolSpecification] = toolSpecs.map(t => t.name() -> t).toMap
}

class MetaMcpClient(connector: McpConnector, env: Env, ec: ExecutionContext) extends McpClient {

  private implicit val _env: Env = env
  private implicit val _ec: ExecutionContext = ec

  override def subscribeToResource(uri: String): Unit = ()

  override def unsubscribeFromResource(uri: String): Unit = ()

  override def key(): String = s"meta-${connector.id}"

  override def listTools(): ju.List[ToolSpecification] = MetaMcpClient.toolSpecs.asJava

  override def executeTool(request: ToolExecutionRequest): ToolExecutionResult = {
    val attrs = TypedMap.empty
    val name = request.name()
    val rawArgs = Option(request.arguments()).getOrElse("{}")
    val args: JsValue = scala.util.Try(Json.parse(rawArgs)).getOrElse(JsObject.empty)
    val resultF: Future[(Boolean, String)] = name match {
      case "list_servers" => handleListServers(attrs)
      case "list_tools" => handleListTools(args, attrs)
      case "get_tool_schema" => handleGetToolSchema(args, attrs)
      case "execute" => handleExecute(args, attrs)
      case "search_tools" => handleSearchTools(args, attrs)
      case other => Future.successful((true, s"unknown meta tool: '$other'"))
    }
    val (isError, text) = try Await.result(resultF, 5.minutes) catch {
      case t: Throwable => (true, s"meta tool '$name' failed: ${t.getMessage}")
    }
    ToolExecutionResult.builder()
      .isError(isError)
      .result(text)
      .resultText(text)
      .build()
  }

  override def listResources(): ju.List[McpResource] = ju.Collections.emptyList()

  override def listResourceTemplates(): ju.List[McpResourceTemplate] = ju.Collections.emptyList()

  override def readResource(uri: String): McpReadResourceResult = throw new RuntimeException(s"meta MCP connector does not expose resources")

  override def listTools(invocationContext: InvocationContext): ju.List[ToolSpecification] = MetaMcpClient.toolSpecs.asJava

  override def listResources(invocationContext: InvocationContext): ju.List[McpResource] = ju.Collections.emptyList()

  override def listResourceTemplates(invocationContext: InvocationContext): ju.List[McpResourceTemplate] = ju.Collections.emptyList()

  override def listPrompts(): ju.List[McpPrompt] = ju.Collections.emptyList()

  override def readResource(uri: String, invocationContext: InvocationContext): McpReadResourceResult = throw new RuntimeException(s"meta MCP connector does not expose resources")

  override def getPrompt(name: String, arguments: ju.Map[String, AnyRef]): McpGetPromptResult = throw new RuntimeException(s"meta MCP connector does not expose prompts")

  override def checkHealth(): Unit = ()

  override def setRoots(roots: ju.List[McpRoot]): Unit = ()

  override def close(): Unit = MetaMcpClient.invalidate(connector.id)

  override def executeTool(request: ToolExecutionRequest, invocationContext: InvocationContext): ToolExecutionResult = {
    executeTool(request)
  }

  // ---------- Sub-connector resolution ----------

  private case class ResolvedServer(slug: String, connector: McpConnector)

  private def subConnectors(): Seq[McpConnector] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    connector.transport.metaOptions.connectors
      .filter(_ != connector.id)
      .flatMap(cid => ext.states.mcpConnector(cid))
      .filter(_.enabled)
  }

  // Build slug → connector map. If two sub-connectors share a slug, suffix with a counter.
  private def resolveServers(): Seq[ResolvedServer] = {
    val subs = subConnectors()
    val seen = scala.collection.mutable.Map[String, Int]()
    subs.map { sub =>
      val base = MetaMcpClient.slug(sub.name)
      val count = seen.getOrElse(base, 0)
      seen.update(base, count + 1)
      val finalSlug = if (count == 0) base else s"$base-$count"
      ResolvedServer(finalSlug, sub)
    }
  }

  private def findServer(servers: Seq[ResolvedServer], slug: String): Option[ResolvedServer] =
    servers.find(_.slug == slug)

  // ---------- list_servers ----------

  private def handleListServers(attrs: TypedMap): Future[(Boolean, String)] = Future.successful {
    val servers = resolveServers()
    val arr = JsArray(servers.map { s =>
      Json.obj(
        "slug" -> s.slug,
        "name" -> s.connector.name,
        "description" -> s.connector.description,
      )
    })
    (false, Json.stringify(arr))
  }

  // ---------- list_tools ----------

  private def handleListTools(args: JsValue, attrs: TypedMap): Future[(Boolean, String)] = {
    val serverSlug = args.select("server").asOpt[String].map(_.trim).getOrElse("")
    if (serverSlug.isEmpty) return Future.successful((true, "missing required argument 'server'"))
    val servers = resolveServers()
    findServer(servers, serverSlug) match {
      case None => Future.successful((true, s"unknown server slug '$serverSlug'. Call list_servers to discover available servers."))
      case Some(rs) =>
        rs.connector.listTools(attrs).map { tools =>
          val arr = JsArray(tools.map(t => Json.obj(
            "name" -> t.name(),
            "description" -> Option(t.description()).getOrElse("").toString,
          )))
          (false, Json.stringify(arr))
        }.recover { case t => (true, s"failed to list tools for '$serverSlug': ${t.getMessage}") }
    }
  }

  // ---------- get_tool_schema ----------

  private def handleGetToolSchema(args: JsValue, attrs: TypedMap): Future[(Boolean, String)] = {
    val serverSlug = args.select("server").asOpt[String].map(_.trim).getOrElse("")
    val toolName = args.select("tool").asOpt[String].map(_.trim).getOrElse("")
    if (serverSlug.isEmpty) return Future.successful((true, "missing required argument 'server'"))
    if (toolName.isEmpty) return Future.successful((true, "missing required argument 'tool'"))
    val servers = resolveServers()
    findServer(servers, serverSlug) match {
      case None => Future.successful((true, s"unknown server slug '$serverSlug'."))
      case Some(rs) =>
        rs.connector.listTools(attrs).map { tools =>
          tools.find(_.name() == toolName) match {
            case None => (true, s"unknown tool '$toolName' on server '$serverSlug'.")
            case Some(t) =>
              val obj = Json.obj(
                "server" -> serverSlug,
                "tool" -> t.name(),
                "description" -> Option(t.description()).getOrElse("").toString,
                "parameters" -> McpSupport.schemaToJson(t.parameters()),
              )
              (false, Json.stringify(obj))
          }
        }.recover { case t => (true, s"failed to get schema for '$serverSlug:$toolName': ${t.getMessage}") }
    }
  }

  // ---------- execute ----------

  private def handleExecute(args: JsValue, attrs: TypedMap): Future[(Boolean, String)] = {
    val calls = args.select("calls").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
    if (calls.isEmpty) return Future.successful((true, "missing or empty 'calls' array"))
    val servers = resolveServers()
    runSequentially(servers, calls, 0, attrs, Vector.empty).map { entries =>
      (false, Json.stringify(JsObject(entries)))
    }
  }

  private def runSequentially(
                               servers: Seq[ResolvedServer],
                               calls: Seq[JsObject],
                               idx: Int,
                               attrs: TypedMap,
                               acc: Vector[(String, JsValue)],
                             ): Future[Vector[(String, JsValue)]] = {
    if (idx >= calls.size) Future.successful(acc)
    else {
      val raw = calls(idx)
      val name = raw.select("name").asOpt[String].map(_.trim).filter(_.nonEmpty).getOrElse(s"call_$idx")
      runSingle(servers, raw, acc.toMap, attrs)
        .recover { case t: Throwable => Json.obj("error" -> s"unexpected error: ${t.getMessage}") }
        .flatMap(entry => runSequentially(servers, calls, idx + 1, attrs, acc :+ (name -> entry)))
    }
  }

  private def runSingle(
                         servers: Seq[ResolvedServer],
                         raw: JsObject,
                         refCtx: Map[String, JsValue],
                         attrs: TypedMap,
                       ): Future[JsValue] = {
    ExpressionLanguage.expandValue(raw, refCtx) match {
      case Left(err) => Future.successful(Json.obj("error" -> s"unresolved expression: $err"))
      case Right(expanded) =>
        val req = expanded.asOpt[JsObject].getOrElse(Json.obj())
        val serverSlug = req.select("server").asOpt[String].map(_.trim).getOrElse("")
        val toolName = req.select("tool").asOpt[String].map(_.trim).getOrElse("")
        val toolArgs = req.value.get("args") match {
          case Some(o: JsObject) => o
          case Some(other) if other != JsNull => Json.obj("value" -> other)
          case _ => Json.obj()
        }
        if (serverSlug.isEmpty) Future.successful(Json.obj("error" -> "missing 'server'"))
        else if (toolName.isEmpty) Future.successful(Json.obj("error" -> "missing 'tool'"))
        else findServer(servers, serverSlug) match {
          case None => Future.successful(Json.obj("error" -> s"unknown server '$serverSlug'"))
          case Some(rs) =>
            rs.connector.call(toolName, Json.stringify(toolArgs), attrs)
              .map(out => Json.obj("ok" -> true, "result" -> parseOrString(out)))
              .recover { case t: Throwable => Json.obj("error" -> s"call failed: ${t.getMessage}") }
        }
    }
  }

  private def parseOrString(s: String): JsValue =
    scala.util.Try(Json.parse(s)).getOrElse(JsString(s))

  // ---------- search_tools ----------

  private def handleSearchTools(args: JsValue, attrs: TypedMap): Future[(Boolean, String)] = {
    val query = args.select("query").asOpt[String].map(_.trim).getOrElse("")
    if (query.isEmpty) return Future.successful((true, "missing required argument 'query'"))
    val filterServers = args.select("servers").asOpt[Seq[String]].map(_.toSet).getOrElse(Set.empty[String])
    ensureSearchIndex().map { idx =>
      val filterFn: ((String, _)) => Boolean = {
        case (docId, _) =>
          if (filterServers.isEmpty) true else idx.entries.get(docId).exists(e => filterServers.contains(e.serverSlug))
      }
      val lex = idx.lexical.search(query, MetaMcpClient.lexicalCandidates).filter(filterFn)
      val sem = idx.semantic.map(_.search(query, MetaMcpClient.semanticCandidates).filter(filterFn)).getOrElse(Seq.empty)
      val chunkMap: Map[String, DocChunk] = idx.entries.iterator.map { case (id, e) =>
        id -> DocChunk(
          corpusId = e.serverSlug,
          docId = id,
          title = e.tool.name(),
          breadcrumb = Seq(e.serverName),
          heading = None,
          url = "",
          text = e.description,
        )
      }.toMap
      val fused = HybridSearcher.fuse(lex, sem, chunkMap, MetaMcpClient.rrfK, MetaMcpClient.defaultTopK)
      val arr = JsArray(fused.flatMap(r => idx.entries.get(r.chunk.docId).map { e =>
        Json.obj(
          "server" -> e.serverSlug,
          "name" -> e.tool.name(),
          "description" -> e.description,
          "score" -> r.score,
        )
      }))
      (false, Json.stringify(arr))
    }.recover { case t => (true, s"search failed: ${t.getMessage}") }
  }

  private def ensureSearchIndex(): Future[MetaSearchIndexEntry] = {
    val attrs = TypedMap.empty
    val servers = resolveServers()
    val semanticEnabled = connector.transport.metaOptions.semanticSearchEnabled
    val toolListFutures = servers.map { rs =>
      rs.connector.listTools(attrs).map(tools => rs -> tools).recover { case _ => rs -> Seq.empty[ToolSpecification] }
    }
    Future.sequence(toolListFutures).map { perServer =>
      val entries: Seq[MetaToolEntry] = perServer.flatMap { case (rs, tools) =>
        tools.map(t => MetaToolEntry(rs.slug, rs.connector.name, rs.connector.id, t))
      }
      val fingerprint = (entries.map(e => s"${e.docId}|${e.description}").mkString("\n") + s"|semantic=$semanticEnabled").sha256
      MetaMcpClient.searchIndices.get(connector.id) match {
        case Some(cached) if cached.fingerprint == fingerprint => cached
        case other =>
          val chunks: Seq[DocChunk] = entries.map(e => DocChunk(
            corpusId = e.serverSlug,
            docId = e.docId,
            title = e.tool.name(),
            breadcrumb = Seq(e.serverName),
            heading = None,
            url = "",
            text = e.description,
          ))
          val lex = LexicalIndex.build(chunks)
          val sem = if (semanticEnabled) Some(SemanticIndex.build(chunks)) else None
          val newEntry = MetaSearchIndexEntry(
            lexical = lex,
            semantic = sem,
            entries = entries.map(e => e.docId -> e).toMap,
            fingerprint = fingerprint,
          )
          MetaMcpClient.searchIndices.put(connector.id, newEntry)
          other.foreach(o => try o.lexical.close() catch {
            case _: Throwable => ()
          })
          newEntry
      }
    }
  }
}
