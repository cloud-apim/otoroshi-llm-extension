package com.cloud.apim.otoroshi.extensions.aigateway.mcp

import com.cloud.apim.otoroshi.extensions.aigateway.assistant.logic.Catalog
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{McpConnector, McpSupport}
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import dev.langchain4j.agent.tool.{ToolExecutionRequest, ToolSpecification}
import dev.langchain4j.invocation.InvocationContext
import dev.langchain4j.mcp.client._
import dev.langchain4j.model.chat.request.json._
import dev.langchain4j.service.tool.ToolExecutionResult
import otoroshi.env.Env
import otoroshi.utils.RegexPool
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import java.{util => ju}
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/**
 * Converts an OpenAPI / JSON-Schema fragment (Play JSON) into a langchain4j `JsonSchemaElement`.
 * This is the inverse of `McpSupport.schemaToJson`. langchain4j's own parser
 * (`dev.langchain4j.mcp.client.ToolSpecificationHelper.jsonNodeToJsonSchemaElement`) is package-private
 * and not reachable from here, hence this re-implementation.
 *
 * `$ref` are resolved eagerly via the catalog document, which handles the OpenAPI `#/components/schemas/...`
 * convention (langchain4j only knows `#/$defs/` and `#/definitions/`).
 */
object OpenApiSchema {

  // permissive object for unconstrained bodies / unknown schemas
  def permissiveObject: JsonObjectSchema = JsonObjectSchema.builder().additionalProperties(java.lang.Boolean.TRUE).build()

  def toJsonSchemaElement(schema: JsValue, doc: Catalog.Document, seen: Set[String] = Set.empty): JsonSchemaElement = {
    schema match {
      case obj: JsObject =>
        obj.value.get("$ref").flatMap(_.asOpt[String]) match {
          case Some(ref) =>
            if (seen.contains(ref)) permissiveObject
            else doc.resolveRef(ref).map(r => toJsonSchemaElement(r, doc, seen + ref)).getOrElse(permissiveObject)
          case None => fromObject(obj, doc, seen)
        }
      case _ => permissiveObject
    }
  }

  private def primitiveForType(t: String, description: String): JsonSchemaElement = t match {
    case "string" => { val b = JsonStringSchema.builder(); if (description != null) b.description(description); b.build() }
    case "integer" => { val b = JsonIntegerSchema.builder(); if (description != null) b.description(description); b.build() }
    case "number" => { val b = JsonNumberSchema.builder(); if (description != null) b.description(description); b.build() }
    case "boolean" => { val b = JsonBooleanSchema.builder(); if (description != null) b.description(description); b.build() }
    case "null" => new JsonNullSchema()
    case "object" => permissiveObject
    case "array" => JsonArraySchema.builder().items(JsonStringSchema.builder().build()).build()
    case _ => JsonStringSchema.builder().build()
  }

  private def fromObject(obj: JsObject, doc: Catalog.Document, seen: Set[String]): JsonSchemaElement = {
    val description = obj.select("description").asOpt[String].orNull

    // enum → JsonEnumSchema (langchain4j enums are string-valued)
    obj.value.get("enum").flatMap(_.asOpt[Seq[JsValue]]) match {
      case Some(values) if values.nonEmpty =>
        val rendered = values.map { case JsString(s) => s; case other => Json.stringify(other) }
        val b = JsonEnumSchema.builder().enumValues(rendered.asJava)
        if (description != null) b.description(description)
        return b.build()
      case _ => ()
    }

    // anyOf / oneOf → JsonAnyOfSchema (langchain4j has no oneOf)
    obj.value.get("anyOf").orElse(obj.value.get("oneOf")).flatMap(_.asOpt[Seq[JsValue]]) match {
      case Some(variants) if variants.nonEmpty =>
        val b = JsonAnyOfSchema.builder().anyOf(variants.map(v => toJsonSchemaElement(v, doc, seen)).asJava)
        if (description != null) b.description(description)
        return b.build()
      case _ => ()
    }

    // allOf → best-effort merge of object members
    obj.value.get("allOf").flatMap(_.asOpt[Seq[JsValue]]) match {
      case Some(parts) if parts.nonEmpty =>
        val merged = parts.foldLeft(Json.obj("type" -> "object")) { (acc, part) =>
          val resolved: JsObject = part match {
            case p: JsObject =>
              p.value.get("$ref").flatMap(_.asOpt[String])
                .flatMap(r => doc.resolveRef(r)).flatMap(_.asOpt[JsObject]).getOrElse(p)
            case _ => Json.obj()
          }
          val accProps = (acc \ "properties").asOpt[JsObject].getOrElse(Json.obj())
          val partProps = (resolved \ "properties").asOpt[JsObject].getOrElse(Json.obj())
          val accReq = (acc \ "required").asOpt[Seq[String]].getOrElse(Seq.empty)
          val partReq = (resolved \ "required").asOpt[Seq[String]].getOrElse(Seq.empty)
          acc ++ Json.obj("properties" -> (accProps ++ partProps), "required" -> (accReq ++ partReq).distinct)
        }
        return fromTypedObject(merged, doc, seen, description)
      case _ => ()
    }

    obj.value.get("type") match {
      case Some(JsString("object")) => fromTypedObject(obj, doc, seen, description)
      case Some(JsString("array")) => fromArray(obj, doc, seen, description)
      case Some(JsString(prim)) => primitiveForType(prim, description)
      case Some(arr: JsArray) =>
        // type: ["string", "null"] → anyOf of each declared type
        val variants = arr.value.collect { case JsString(t) => primitiveForType(t, description) }.toSeq
        if (variants.isEmpty) permissiveObject
        else if (variants.size == 1) variants.head
        else {
          val b = JsonAnyOfSchema.builder().anyOf(variants.asJava)
          if (description != null) b.description(description)
          b.build()
        }
      case _ =>
        // no explicit type: treat as object when it has properties, otherwise permissive
        if (obj.value.contains("properties")) fromTypedObject(obj, doc, seen, description)
        else permissiveObject
    }
  }

  private def fromTypedObject(obj: JsObject, doc: Catalog.Document, seen: Set[String], description: String): JsonObjectSchema = {
    val b = JsonObjectSchema.builder()
    if (description != null) b.description(description)
    obj.select("properties").asOpt[JsObject].map(_.value).getOrElse(Map.empty[String, JsValue]).foreach {
      case (k, v) => b.addProperty(k, toJsonSchemaElement(v, doc, seen))
    }
    val required = obj.select("required").asOpt[Seq[String]].getOrElse(Seq.empty)
    if (required.nonEmpty) b.required(required.asJava)
    obj.value.get("additionalProperties") match {
      case Some(JsBoolean(bb)) => b.additionalProperties(java.lang.Boolean.valueOf(bb))
      case _ => ()
    }
    b.build()
  }

  private def fromArray(obj: JsObject, doc: Catalog.Document, seen: Set[String], description: String): JsonArraySchema = {
    val b = JsonArraySchema.builder()
    if (description != null) b.description(description)
    obj.value.get("items") match {
      case Some(items) => b.items(toJsonSchemaElement(items, doc, seen))
      case None => b.items(JsonStringSchema.builder().build())
    }
    b.build()
  }
}

object OpenApiMcpClient {

  // connectorId -> (timestamp, fingerprint, parsed document)
  private val specCache: TrieMap[String, (Long, String, Catalog.Document)] = new TrieMap()

  private lazy val yamlMapper: ObjectMapper = new ObjectMapper(new YAMLFactory())

  def invalidate(connectorId: String): Unit = specCache.remove(connectorId)

  // operationId -> a valid MCP/OpenAI tool name ([a-zA-Z0-9_-]). Casing is preserved for readability.
  def slugifyName(s: String): String = {
    val replaced = s.trim.replaceAll("[^a-zA-Z0-9_-]+", "_").replaceAll("_+", "_").stripPrefix("_").stripSuffix("_")
    if (replaced.isEmpty) "op" else replaced
  }

  // ---------- meta-mode tool specifications ----------

  private def strField(desc: String): JsonStringSchema = JsonStringSchema.builder().description(desc).build()

  private val searchOperationsTool: ToolSpecification = ToolSpecification.builder()
    .name("search_operations")
    .description(
      "Search the operations exposed by this OpenAPI server (keyword search over operationId, path, tags and summary). " +
        "Returns a JSON array (as a string) of `{operationId, method, path, summary, tags}` ranked by relevance. " +
        "Use `get_operation_schema` next to learn an operation's parameters, then `execute` to call it."
    )
    .parameters(
      JsonObjectSchema.builder()
        .addProperty("query", strField("Free-text query (a few keywords work best, e.g. '<entity> <verb>')."))
        .addProperty("limit", JsonIntegerSchema.builder().description("Max number of results (optional).").build())
        .required(ju.Arrays.asList("query"))
        .build()
    )
    .build()

  private val getOperationSchemaTool: ToolSpecification = ToolSpecification.builder()
    .name("get_operation_schema")
    .description(
      "Return the JSON schema of one operation. Returns a JSON object (as a string) of shape " +
        "`{operationId, method, path, description, parameters}` where `parameters` follows JSON Schema. " +
        "Call `search_operations` first to obtain a valid `operationId`."
    )
    .parameters(
      JsonObjectSchema.builder()
        .addProperty("operationId", strField("operationId (from search_operations)."))
        .required(ju.Arrays.asList("operationId"))
        .build()
    )
    .build()

  private val executeOperationTool: ToolSpecification = ToolSpecification.builder()
    .name("execute")
    .description(
      """Call one operation of this OpenAPI server.
        |- `operationId`: the operation to run (from search_operations).
        |- `arguments`: a JSON-encoded **string** of the arguments object. Keys are the operation's path/query/header
        |  parameter names; the request body (when any) goes under a `body` key. Use `get_operation_schema` first to
        |  learn the exact shape. Use `"{}"` when the operation takes no arguments.
        |
        |Returns a JSON object (as a string) of shape `{status, body}`.
        |""".stripMargin
    )
    .parameters(
      JsonObjectSchema.builder()
        .addProperty("operationId", strField("operationId (from search_operations)."))
        .addProperty("arguments", strField("JSON-encoded string of the arguments object. Example: \"{\\\"id\\\": \\\"42\\\"}\". Use \"{}\" if none."))
        .required(ju.Arrays.asList("operationId", "arguments"))
        .build()
    )
    .build()

  val metaToolSpecs: Seq[ToolSpecification] = Seq(searchOperationsTool, getOperationSchemaTool, executeOperationTool)
}

class OpenApiMcpClient(connector: McpConnector, env: Env, ec: ExecutionContext) extends McpClient {

  private implicit val _env: Env = env
  private implicit val _ec: ExecutionContext = ec

  private def opts = connector.transport.openapiOptions

  // ---------- McpClient interface ----------

  override def key(): String = s"openapi-${connector.id}"

  override def subscribeToResource(uri: String): Unit = ()
  override def unsubscribeFromResource(uri: String): Unit = ()
  override def listResources(): ju.List[McpResource] = ju.Collections.emptyList()
  override def listResources(invocationContext: InvocationContext): ju.List[McpResource] = ju.Collections.emptyList()
  override def listResourceTemplates(): ju.List[McpResourceTemplate] = ju.Collections.emptyList()
  override def listResourceTemplates(invocationContext: InvocationContext): ju.List[McpResourceTemplate] = ju.Collections.emptyList()
  override def readResource(uri: String): McpReadResourceResult = throw new RuntimeException("openapi MCP connector does not expose resources")
  override def readResource(uri: String, invocationContext: InvocationContext): McpReadResourceResult = throw new RuntimeException("openapi MCP connector does not expose resources")
  override def listPrompts(): ju.List[McpPrompt] = ju.Collections.emptyList()
  override def getPrompt(name: String, arguments: ju.Map[String, AnyRef]): McpGetPromptResult = throw new RuntimeException("openapi MCP connector does not expose prompts")
  override def checkHealth(): Unit = ()
  override def setRoots(roots: ju.List[McpRoot]): Unit = ()
  override def close(): Unit = OpenApiMcpClient.invalidate(connector.id)

  override def listTools(): ju.List[ToolSpecification] = {
    try {
      if (opts.exposeAsMeta) {
        OpenApiMcpClient.metaToolSpecs.asJava
      } else {
        val doc = loadDocument()
        val ops = filteredOperations(doc)
        val nameMap = toolNameMap(ops)
        ops.map { op =>
          ToolSpecification.builder()
            .name(nameMap(op.operationId))
            .description(buildToolDescription(op))
            .parameters(buildToolParameters(op, doc))
            .build()
        }.asJava
      }
    } catch {
      case t: Throwable =>
        println(s"[openapi-mcp ${connector.id}] failed to list tools: ${t.getMessage}")
        ju.Collections.emptyList()
    }
  }

  override def listTools(invocationContext: InvocationContext): ju.List[ToolSpecification] = listTools()

  override def executeTool(request: ToolExecutionRequest): ToolExecutionResult = {
    val name = request.name()
    val rawArgs = Option(request.arguments()).getOrElse("{}")
    val args: JsObject = scala.util.Try(Json.parse(rawArgs)).toOption.flatMap(_.asOpt[JsObject]).getOrElse(Json.obj())
    val resultF: Future[(Boolean, String)] =
      try {
        if (opts.exposeAsMeta) handleMeta(name, args)
        else handleDirect(name, args)
      } catch {
        case t: Throwable => Future.successful((true, s"openapi tool '$name' failed: ${t.getMessage}"))
      }
    val (isError, text) = try Await.result(resultF, opts.timeout + 30.seconds) catch {
      case t: Throwable => (true, s"openapi tool '$name' failed: ${t.getMessage}")
    }
    ToolExecutionResult.builder().isError(isError).result(text).resultText(text).build()
  }

  override def executeTool(request: ToolExecutionRequest, invocationContext: InvocationContext): ToolExecutionResult = executeTool(request)

  // ---------- spec loading & caching ----------

  private def loadDocument(): Catalog.Document = {
    val now = System.currentTimeMillis()
    val fingerprint = (opts.specUrl.getOrElse("") + "|" + opts.spec.map(Json.stringify).getOrElse("")).sha256
    OpenApiMcpClient.specCache.get(connector.id) match {
      case Some((t, fp, doc)) if fp == fingerprint && (opts.spec.isDefined || (now - t) < opts.cacheTtl.toMillis) => doc
      case _ =>
        val specJson: JsObject = opts.spec match {
          case Some(js) => toSpecObject(js)
          case None => opts.specUrl match {
            case Some(url) =>
              val resp = Await.result(env.Ws.url(url).withFollowRedirects(true).withRequestTimeout(opts.timeout).get(), opts.timeout)
              parseSpecText(resp.body, resp.contentType, url)
            case None => throw new RuntimeException("openapi connector requires either an inline 'spec' or a 'spec_url'")
          }
        }
        val doc = Catalog.parse(specJson, dropBulk = false)
        OpenApiMcpClient.specCache.put(connector.id, (now, fingerprint, doc))
        doc
    }
  }

  private def toSpecObject(js: JsValue): JsObject = js match {
    case o: JsObject => o
    case JsString(s) => parseSpecText(s, "", "")
    case _ => throw new RuntimeException("invalid inline 'spec' (must be a JSON object or a JSON/YAML string)")
  }

  private def parseSpecText(text: String, contentType: String, url: String): JsObject = {
    val trimmed = text.trim
    val looksJson = trimmed.startsWith("{") || trimmed.startsWith("[")
    val hintsYaml = contentType.toLowerCase.contains("yaml") || url.toLowerCase.endsWith(".yaml") || url.toLowerCase.endsWith(".yml")
    val parsed: Option[JsValue] =
      if (looksJson && !hintsYaml) scala.util.Try(Json.parse(trimmed)).toOption
      else if (hintsYaml) yamlToJson(text)
      else scala.util.Try(Json.parse(trimmed)).toOption.orElse(yamlToJson(text))
    parsed.flatMap(_.asOpt[JsObject]).getOrElse(throw new RuntimeException("could not parse OpenAPI spec (expected a JSON or YAML object)"))
  }

  private def yamlToJson(text: String): Option[JsValue] =
    scala.util.Try(Json.parse(OpenApiMcpClient.yamlMapper.readTree(text).toString)).toOption

  // ---------- operation filtering ----------

  private def filteredOperations(doc: Catalog.Document): Seq[Catalog.Operation] = {
    doc.operations.filter { op =>
      val incOk = opts.includeOperationIds.isEmpty || opts.includeOperationIds.exists(p => RegexPool.regex(p).matches(op.operationId))
      val excOk = opts.excludeOperationIds.isEmpty || !opts.excludeOperationIds.exists(p => RegexPool.regex(p).matches(op.operationId))
      val tagIncOk = opts.includeTags.isEmpty || op.tags.exists(opts.includeTags.contains)
      val tagExcOk = opts.excludeTags.isEmpty || !op.tags.exists(opts.excludeTags.contains)
      incOk && excOk && tagIncOk && tagExcOk
    }
  }

  // operationId -> unique tool name. Deterministic so executeTool can rebuild it independently of listTools.
  private def toolNameMap(ops: Seq[Catalog.Operation]): Map[String, String] = {
    val seen = scala.collection.mutable.Map.empty[String, Int]
    ops.map { op =>
      val base = OpenApiMcpClient.slugifyName(op.operationId)
      val n = seen.getOrElse(base, 0)
      seen.update(base, n + 1)
      val finalName = if (n == 0) base else s"${base}_${n + 1}"
      op.operationId -> finalName
    }.toMap
  }

  // ---------- tool building (direct mode) ----------

  private def buildToolDescription(op: Catalog.Operation): String = {
    val sb = new StringBuilder
    op.summary.orElse(op.description).filter(_.nonEmpty).foreach(d => sb.append(d).append("\n"))
    sb.append(s"HTTP ${op.method.toUpperCase} ${op.path}")
    sb.toString()
  }

  private def buildToolParameters(op: Catalog.Operation, doc: Catalog.Document): JsonObjectSchema = {
    val b = JsonObjectSchema.builder()
    val required = scala.collection.mutable.ListBuffer.empty[String]
    op.parameters.filter(p => Set("path", "query", "header").contains(p.in.toLowerCase)).foreach { p =>
      val schemaJson: JsValue = p.schema match {
        case Some(o: JsObject) if !o.value.contains("description") && p.description.exists(_.nonEmpty) => o ++ Json.obj("description" -> p.description.get)
        case Some(other) => other
        case None => Json.obj("type" -> "string") ++ p.description.filter(_.nonEmpty).map(d => Json.obj("description" -> d)).getOrElse(Json.obj())
      }
      b.addProperty(p.name, OpenApiSchema.toJsonSchemaElement(schemaJson, doc))
      if (p.required || p.in.equalsIgnoreCase("path")) required += p.name
    }
    op.requestBody.foreach { rb =>
      val el = rb.schema.map(s => OpenApiSchema.toJsonSchemaElement(s, doc)).getOrElse(OpenApiSchema.permissiveObject)
      b.addProperty("body", el)
      if (rb.required) required += "body"
    }
    if (required.nonEmpty) b.required(required.distinct.asJava)
    b.build()
  }

  // ---------- dispatch ----------

  private def handleDirect(toolName: String, args: JsObject): Future[(Boolean, String)] = {
    val doc = loadDocument()
    val ops = filteredOperations(doc)
    val byToolName: Map[String, String] = toolNameMap(ops).map(_.swap)
    byToolName.get(toolName).flatMap(opId => ops.find(_.operationId == opId)) match {
      case None => Future.successful((true, s"unknown tool '$toolName'"))
      case Some(op) => executeOperation(op, args, doc)
    }
  }

  private def handleMeta(name: String, args: JsObject): Future[(Boolean, String)] = {
    val doc = loadDocument()
    val ops = filteredOperations(doc)
    val allowed = ops.map(_.operationId).toSet
    name match {
      case "search_operations" =>
        val query = args.select("query").asOpt[String].map(_.trim).getOrElse("")
        if (query.isEmpty) return Future.successful((true, "missing required argument 'query'"))
        val limit = args.select("limit").asOpt[Int].getOrElse(Catalog.defaultSearchLimit)
        val (results, total) = doc.search(query, limit)
        val filtered = results.filter(r => allowed.contains(r.operationId))
        val arr = JsArray(filtered.map { op =>
          Json.obj(
            "operationId" -> op.operationId,
            "method" -> op.method.toUpperCase,
            "path" -> op.path,
            "summary" -> op.summary,
            "tags" -> op.tags,
          )
        })
        Future.successful((false, Json.stringify(Json.obj("total" -> total, "results" -> arr))))
      case "get_operation_schema" =>
        val operationId = args.select("operationId").asOpt[String].map(_.trim).getOrElse("")
        if (operationId.isEmpty) return Future.successful((true, "missing required argument 'operationId'"))
        ops.find(_.operationId == operationId) match {
          case None => Future.successful((true, s"unknown or filtered-out operationId '$operationId'. Use search_operations first."))
          case Some(op) =>
            val params = buildToolParameters(op, doc)
            val obj = Json.obj(
              "operationId" -> op.operationId,
              "method" -> op.method.toUpperCase,
              "path" -> op.path,
              "description" -> op.summary.orElse(op.description),
              "parameters" -> McpSupport.schemaToJson(params),
            )
            Future.successful((false, Json.stringify(obj)))
        }
      case "execute" =>
        val operationId = args.select("operationId").asOpt[String].map(_.trim).getOrElse("")
        if (operationId.isEmpty) return Future.successful((true, "missing required argument 'operationId'"))
        ops.find(_.operationId == operationId) match {
          case None => Future.successful((true, s"unknown or filtered-out operationId '$operationId'. Use search_operations first."))
          case Some(op) =>
            val argStr = args.select("arguments").asOpt[String].getOrElse("{}")
            val argObj = scala.util.Try(Json.parse(if (argStr.trim.isEmpty) "{}" else argStr)).toOption.flatMap(_.asOpt[JsObject]).getOrElse(Json.obj())
            executeOperation(op, argObj, doc)
        }
      case other => Future.successful((true, s"unknown meta tool: '$other'"))
    }
  }

  // ---------- HTTP execution ----------

  private def asParamString(v: JsValue): Option[String] = v match {
    case JsNull => None
    case JsString(s) => Some(s)
    case JsBoolean(b) => Some(b.toString)
    case JsNumber(n) => Some(n.bigDecimal.toPlainString)
    case other => Some(Json.stringify(other))
  }

  private def serversBaseUrl(doc: Catalog.Document): Option[String] =
    doc.raw.select("servers").asOpt[Seq[JsObject]].flatMap(_.headOption)
      .flatMap(_.select("url").asOpt[String]).map(_.trim).filter(_.nonEmpty)

  private def interpolatePath(path: String, params: Map[String, String]): String = {
    """\{([^}]+)\}""".r.replaceAllIn(path, m => {
      val key = m.group(1)
      val value = params.getOrElse(key, throw new IllegalArgumentException(s"missing path parameter '$key' for $path"))
      java.util.regex.Matcher.quoteReplacement(java.net.URLEncoder.encode(value, "UTF-8"))
    })
  }

  private def buildQuery(q: Map[String, String]): String = {
    val parts = q.collect { case (k, v) if v != null => s"${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}" }
    if (parts.isEmpty) "" else parts.mkString("?", "&", "")
  }

  private def executeOperation(op: Catalog.Operation, args: JsObject, doc: Catalog.Document): Future[(Boolean, String)] = {
    opts.baseUrl.orElse(serversBaseUrl(doc)) match {
      case None => Future.successful((true, "no 'base_url' configured and the OpenAPI spec has no servers[].url"))
      case Some(base) =>
        val byName: Map[String, Catalog.Parameter] = op.parameters.map(p => p.name -> p).toMap
        val pathParams = scala.collection.mutable.Map.empty[String, String]
        val query = scala.collection.mutable.Map.empty[String, String]
        val headerParams = scala.collection.mutable.Map.empty[String, String]
        args.value.foreach { case (k, v) =>
          if (k != "body") byName.get(k).foreach { p =>
            asParamString(v).foreach { s =>
              p.in.toLowerCase match {
                case "path" => pathParams.update(k, s)
                case "query" => query.update(k, s)
                case "header" => headerParams.update(k, s)
                case _ => ()
              }
            }
          }
        }
        val body = args.value.get("body").filterNot(_ == JsNull)
        val resolvedPathTry = scala.util.Try(interpolatePath(op.path, pathParams.toMap))
        resolvedPathTry match {
          case scala.util.Failure(e) => Future.successful((true, e.getMessage))
          case scala.util.Success(resolvedPath) =>
            val withSlash = if (resolvedPath.startsWith("/")) resolvedPath else s"/$resolvedPath"
            val finalUrl = s"${base.stripSuffix("/")}$withSlash${buildQuery(query.toMap)}"
            val host = scala.util.Try(new java.net.URI(finalUrl).getHost).toOption.getOrElse("")
            if (opts.allowedHosts.nonEmpty && !opts.allowedHosts.exists(h => host.equalsIgnoreCase(h) || host.toLowerCase.endsWith("." + h.toLowerCase))) {
              Future.successful((true, s"target host '$host' is not in allowed_hosts"))
            } else {
              val method = op.method.toUpperCase
              val hasBody = body.isDefined && method != "GET" && method != "HEAD"
              val headers = Map("Accept" -> "application/json") ++ opts.headers ++ headerParams.toMap ++ (if (hasBody) Map("Content-Type" -> "application/json") else Map.empty)
              var builder = env.Ws.url(finalUrl)
                .withHttpHeaders(headers.toSeq: _*)
                .withMethod(method)
                .withRequestTimeout(opts.timeout)
                .withFollowRedirects(false)
              if (hasBody) builder = builder.withBody(body.get)
              builder.execute().map { resp =>
                val ct = resp.contentType
                val bodyJson: JsValue =
                  if (resp.status == 204 || resp.body.isEmpty) JsNull
                  else if (ct.contains("application/json")) scala.util.Try(Json.parse(resp.body)).getOrElse(JsString(resp.body))
                  else JsString(resp.body)
                val isError = !(resp.status >= 200 && resp.status < 300)
                (isError, Json.stringify(Json.obj("status" -> resp.status, "body" -> bodyJson)))
              }.recover { case t: Throwable => (true, s"http call failed: ${t.getMessage}") }
            }
        }
    }
  }
}
