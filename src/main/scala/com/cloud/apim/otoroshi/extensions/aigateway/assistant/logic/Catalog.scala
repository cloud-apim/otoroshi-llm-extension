package com.cloud.apim.otoroshi.extensions.aigateway.assistant.logic

import otoroshi.api.OpenApi
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import scala.concurrent.duration._

object Catalog {

  val maxRefDepth: Int = 20
  val maxSchemaRenderDepth: Int = 3
  val schemaRefResolveDepthCutoff: Int = 2
  val maxEnumPreview: Int = 5
  val maxObjectProperties: Int = 8
  val maxUnionVariants: Int = 3

  val defaultSearchLimit: Int = 30
  private val scoreOperationId: Int = 5
  private val scorePath: Int = 3
  private val scoreTag: Int = 2
  private val scoreSummary: Int = 1

  private val httpMethods: Set[String] = Set("get", "post", "put", "patch", "delete", "options", "head")

  private val synonymGroups: Seq[Seq[String]] = Seq(
    Seq("list", "findall", "getall", "all"),
    Seq("get", "find", "read", "fetch", "retrieve", "findbyid"),
    Seq("create", "add", "new", "make"),
    Seq("update", "modify", "edit", "change"),
    Seq("delete", "remove", "destroy", "drop"),
    Seq("apikey", "apikeys", "apitoken", "token"),
    Seq("route", "routes"),
    Seq("service", "services", "servicedescriptor"),
    Seq("cert", "certificate", "certs", "certificates"),
    Seq("auth", "authentication", "authmodule"),
  )

  private val synonyms: Map[String, Seq[String]] =
    synonymGroups.flatMap(g => g.map(t => t -> g)).toMap

  case class Parameter(name: String, in: String, required: Boolean, description: Option[String], schema: Option[JsValue])
  case class RequestBody(required: Boolean, schema: Option[JsValue])
  case class Response(description: Option[String], schema: Option[JsValue])
  case class Operation(
    operationId: String,
    method: String,
    path: String,
    summary: Option[String],
    description: Option[String],
    tags: Seq[String],
    parameters: Seq[Parameter],
    requestBody: Option[RequestBody],
    responses: Map[String, Response],
  )
  case class Info(title: String, version: String, description: Option[String])
  case class Document(info: Info, operations: Seq[Operation], tags: Seq[String], raw: JsObject, flatSchemas: Map[String, JsValue]) {
    lazy val byOperationId: Map[String, Operation] = operations.map(o => o.operationId -> o).toMap
    lazy val operationCount: Int = operations.size

    def resolveRef(ref: String, visited: Set[String] = Set.empty): Option[JsValue] = {
      if (visited.contains(ref)) return Some(Json.obj("$ref" -> ref, "_note" -> "circular reference"))
      if (visited.size > maxRefDepth) return Some(Json.obj("$ref" -> ref, "_note" -> "max depth reached"))
      if (!ref.startsWith("#/")) return None
      val schemaPrefix = "#/components/schemas/"
      val resolved = if (ref.startsWith(schemaPrefix)) {
        val name = ref.substring(schemaPrefix.length)
        flatSchemas.get(name)
      } else {
        val parts = ref.stripPrefix("#/").split("/").toSeq.map(_.replace("~1", "/").replace("~0", "~"))
        parts.foldLeft(Option[JsValue](raw)) { (acc, part) =>
          acc.flatMap(_.asOpt[JsObject]).flatMap(_.value.get(part))
        }
      }
      resolved.flatMap {
        case JsNull => None
        case obj: JsObject if obj.value.get("$ref").flatMap(_.asOpt[String]).isDefined =>
          resolveRef(obj.select("$ref").asString, visited + ref)
        case other => Some(other)
      }
    }

    def summarizeSchema(schema: JsValue, depth: Int = 0, seenRefs: Set[String] = Set.empty): String = {
      if (depth > maxSchemaRenderDepth) return "unknown"
      schema match {
        case JsNull => "unknown"
        case obj: JsObject =>
          obj.value.get("$ref").flatMap(_.asOpt[String]) match {
            case Some(ref) =>
              val name = ref.split("/").lastOption.getOrElse("ref")
              if (depth >= schemaRefResolveDepthCutoff || seenRefs.contains(ref)) name
              else resolveRef(ref).map(r => summarizeSchema(r, depth + 1, seenRefs + ref)).getOrElse(name)
            case None =>
              obj.value.get("enum").flatMap(_.asOpt[Seq[JsValue]]) match {
                case Some(values) =>
                  val rendered = values.take(maxEnumPreview).map(v => v.asOpt[String].getOrElse(v.toString)).mkString("|")
                  s"enum($rendered)"
                case None =>
                  if (obj.value.contains("properties")) {
                    val props = obj.select("properties").asOpt[JsObject].map(_.value).getOrElse(Map.empty)
                    if (props.isEmpty) "object"
                    else {
                      val keys = props.keys.take(maxObjectProperties).toSeq
                      val inner = keys.map(k => s"$k: ${summarizeSchema(props(k), depth + 1, seenRefs)}").mkString(", ")
                      val extra = if (props.size > keys.size) ", ..." else ""
                      s"{ $inner$extra }"
                    }
                  } else obj.select("type").asOpt[String] match {
                    case Some("array") =>
                      val items = obj.value.get("items").getOrElse(JsNull)
                      s"${summarizeSchema(items, depth + 1, seenRefs)}[]"
                    case Some(t) => t
                    case None =>
                      val variants = obj.value.get("oneOf").orElse(obj.value.get("anyOf"))
                        .flatMap(_.asOpt[Seq[JsValue]]).getOrElse(Seq.empty)
                      if (variants.nonEmpty) variants.take(maxUnionVariants).map(v => summarizeSchema(v, depth + 1, seenRefs)).mkString(" | ")
                      else "unknown"
                  }
              }
          }
        case other => other.toString
      }
    }

    private lazy val haystacks: Map[String, String] =
      operations.map(o => o.operationId -> normalize(haystackOf(o))).toMap

    def search(query: String, limit: Int = defaultSearchLimit): (Seq[Operation], Int) = {
      val rawTerms = query.split("""\s+""").filter(_.nonEmpty).toSeq
      if (rawTerms.isEmpty) return (Seq.empty, 0)
      val expandedGroups = rawTerms.map(expandTerm).filter(_.nonEmpty)
      if (expandedGroups.isEmpty) return (Seq.empty, 0)

      val scored = operations.flatMap { op =>
        val haystack = haystacks.getOrElse(op.operationId, "")
        val nOpId = normalize(op.operationId)
        val nPath = normalize(op.path)
        val nSummary = normalize(op.summary.getOrElse(""))
        val nTags = op.tags.map(normalize)

        val allMatch = expandedGroups.forall(group => group.exists(haystack.contains))
        if (!allMatch) None
        else {
          val score = expandedGroups.foldLeft(0) { (acc, group) =>
            acc + group.foldLeft(0) { (s, term) =>
              s +
                (if (nOpId.contains(term)) scoreOperationId else 0) +
                (if (nPath.contains(term)) scorePath else 0) +
                (if (nTags.exists(_.contains(term))) scoreTag else 0) +
                (if (nSummary.contains(term)) scoreSummary else 0)
            }
          }
          Some((op, score))
        }
      }
      val sorted = scored.sortBy { case (_, s) => -s }
      (sorted.take(limit).map(_._1), sorted.size)
    }

    case class ParamSignature(name: String, in: String, required: Boolean, `type`: String, description: Option[String])
    case class Details(
      operation: Operation,
      paramSignatures: Seq[ParamSignature],
      requestBodyType: Option[String],
      responseTypes: Map[String, String],
    )

    def describe(operationId: String): Option[Details] = byOperationId.get(operationId).map { op =>
      Details(
        operation = op,
        paramSignatures = op.parameters.map(p => ParamSignature(
          name = p.name,
          in = p.in,
          required = p.required,
          `type` = p.schema.map(s => summarizeSchema(s)).getOrElse("unknown"),
          description = p.description,
        )),
        requestBodyType = op.requestBody.flatMap(_.schema).map(s => summarizeSchema(s)),
        responseTypes = op.responses.map { case (status, r) =>
          status -> r.schema.map(s => summarizeSchema(s)).getOrElse("")
        },
      )
    }
  }

  private def normalize(s: String): String = s.toLowerCase.replaceAll("[^a-z0-9]+", "")

  private def expandTerm(t: String): Seq[String] = {
    val n = normalize(t)
    if (n.isEmpty) Seq.empty
    else synonyms.getOrElse(n, Seq(n))
  }

  private def haystackOf(op: Operation): String = {
    val paramNames = op.parameters.map(_.name).mkString(" ")
    Seq(
      op.operationId,
      op.method,
      op.path,
      op.summary.getOrElse(""),
      op.description.getOrElse(""),
      op.tags.mkString(" "),
      paramNames,
    ).mkString(" ")
  }

  private def slugify(s: String): String =
    s.toLowerCase.replaceAll("[^a-z0-9]+", "_").stripPrefix("_").stripSuffix("_")

  private def deriveOperationId(method: String, path: String): String = {
    val slug = slugify(path.replaceAll("""\{([^}]+)\}""", "by_$1"))
    s"${method}_$slug"
  }

  private def pickSchema(content: Option[JsObject]): Option[JsValue] = content.flatMap { c =>
    c.value.get("application/json")
      .orElse(c.value.get("application/json; charset=utf-8"))
      .orElse(c.value.headOption.map(_._2))
      .flatMap(_.asOpt[JsObject].flatMap(_.value.get("schema")))
  }

  private def parseParameter(j: JsValue): Option[Parameter] = j.asOpt[JsObject].map { o =>
    Parameter(
      name = o.select("name").asOpt[String].getOrElse(""),
      in = o.select("in").asOpt[String].getOrElse(""),
      required = o.select("required").asOpt[Boolean].getOrElse(false),
      description = o.select("description").asOpt[String],
      schema = o.value.get("schema"),
    )
  }

  private def parseOperation(method: String, path: String, raw: JsObject, seenIds: scala.collection.mutable.Set[String]): Operation = {
    var operationId = raw.select("operationId").asOpt[String].getOrElse(deriveOperationId(method, path))
    if (seenIds.contains(operationId)) {
      var suffix = 2
      while (seenIds.contains(s"${operationId}_$suffix")) suffix += 1
      operationId = s"${operationId}_$suffix"
    }
    seenIds.add(operationId)
    val parameters = raw.select("parameters").asOpt[Seq[JsValue]].getOrElse(Seq.empty).flatMap(parseParameter)
    val requestBody = raw.select("requestBody").asOpt[JsObject].map { rb =>
      RequestBody(
        required = rb.select("required").asOpt[Boolean].getOrElse(false),
        schema = pickSchema(rb.value.get("content").flatMap(_.asOpt[JsObject])),
      )
    }
    val responses = raw.select("responses").asOpt[JsObject].map(_.value).getOrElse(Map.empty).flatMap {
      case (status, v) => v.asOpt[JsObject].map { o =>
        status -> Response(
          description = o.select("description").asOpt[String],
          schema = pickSchema(o.value.get("content").flatMap(_.asOpt[JsObject])),
        )
      }
    }.toMap
    Operation(
      operationId = operationId,
      method = method,
      path = path,
      summary = raw.select("summary").asOpt[String],
      description = raw.select("description").asOpt[String],
      tags = raw.select("tags").asOpt[Seq[String]].getOrElse(Seq.empty),
      parameters = parameters,
      requestBody = requestBody,
      responses = responses,
    )
  }

  def parse(doc: JsObject): Document = {
    val info = doc.select("info").asOpt[JsObject].getOrElse(Json.obj())
    val paths = doc.select("paths").asOpt[JsObject].map(_.value).getOrElse(Map.empty)
    val seenIds = scala.collection.mutable.Set.empty[String]
    val operations = paths.toSeq.flatMap { case (pathStr, pathItem) =>
      pathItem.asOpt[JsObject].toSeq.flatMap { item =>
        item.value.toSeq.flatMap { case (method, opVal) =>
          val m = method.toLowerCase
          if (httpMethods.contains(m)) opVal.asOpt[JsObject].map(parseOperation(m, pathStr, _, seenIds))
          else None
        }
      }
    }.filterNot(op => op.operationId.toLowerCase.contains("bulk"))
    val tagSet = scala.collection.mutable.SortedSet.empty[String]
    operations.foreach(_.tags.foreach(tagSet.add))
    Document(
      info = Info(
        title = info.select("title").asOpt[String].getOrElse("Otoroshi"),
        version = info.select("version").asOpt[String].getOrElse("unknown"),
        description = info.select("description").asOpt[String],
      ),
      operations = operations,
      tags = tagSet.toSeq,
      raw = doc,
      flatSchemas = buildFlatSchemas(doc),
    )
  }

  private def buildFlatSchemas(doc: JsObject): Map[String, JsValue] = {
    val schemasObj = doc.select("components").select("schemas").asOpt[JsObject]
    schemasObj match {
      case None => Map.empty
      case Some(obj) =>
        val builder = scala.collection.mutable.Map.empty[String, JsValue]
        // First pass: register every root schema (unwrap when wrapped). Root entries always win over referencedSchemas duplicates.
        obj.value.foreach { case (name, entry) =>
          entry.asOpt[JsObject] match {
            case Some(eobj) if eobj.value.contains("schema") && eobj.value.contains("referencedSchemas") =>
              eobj.value.get("schema").foreach(s => builder(name) = s)
            case _ => builder(name) = entry
          }
        }
        // Second pass: hoist referencedSchemas into the global namespace, never overwriting a root entry.
        obj.value.foreach { case (_, entry) =>
          entry.asOpt[JsObject].foreach { eobj =>
            eobj.select("referencedSchemas").asOpt[JsObject].foreach { rs =>
              rs.value.foreach { case (subName, subVal) =>
                if (!builder.contains(subName)) builder(subName) = subVal
              }
            }
          }
        }
        builder.toMap
    }
  }

  private val ttl: Long = 10.minutes.toMillis
  @volatile private var cache: Option[(Long, Document)] = None

  def cached(env: Env): Document = {
    val now = System.currentTimeMillis()
    cache match {
      case Some((t, doc)) if now - t < ttl => doc
      case _ =>
        val raw = OpenApi.generate(env, None, None)
        val parsed = Json.parse(raw).asObject
        val doc = parse(parsed)
        cache = Some((now, doc))
        doc
    }
  }

  def invalidate(): Unit = cache = None
}
