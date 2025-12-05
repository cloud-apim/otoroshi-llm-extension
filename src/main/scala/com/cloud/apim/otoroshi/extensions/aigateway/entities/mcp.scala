package com.cloud.apim.otoroshi.extensions.aigateway.entities

import akka.stream.scaladsl.{Sink, Source}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.McpConnectorTransportKind.Stdio
import com.google.gson.Gson
import dev.langchain4j.agent.tool.{ToolExecutionRequest, ToolSpecification}
import dev.langchain4j.mcp.client.DefaultMcpClient
import dev.langchain4j.mcp.client.transport.http.{HttpMcpTransport, StreamableHttpMcpTransport}
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport
import dev.langchain4j.mcp.client.transport.websocket.WebSocketMcpTransport
import dev.langchain4j.model.chat.request.json._
import otoroshi.api.{GenericResourceAccessApiWithState, Resource, ResourceVersion}
import otoroshi.env.Env
import otoroshi.models.{EntityLocation, EntityLocationSupport}
import otoroshi.next.extensions.AdminExtensionId
import otoroshi.security.IdGenerator
import otoroshi.storage.{BasicStore, RedisLike, RedisLikeStore}
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.{AiExtension, AiGatewayExtensionDatastores, AiGatewayExtensionState}
import play.api.libs.json._

import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{Duration, DurationLong, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

sealed trait McpConnectorTransportKind {
  def name: String
}

object McpConnectorTransportKind {
  def apply(str: String): McpConnectorTransportKind = str.toLowerCase match {
    case "sse" => Sse
    case "ws" => Websocket
    case "http" => Http
    case _ => Stdio
  }

  case object Stdio extends McpConnectorTransportKind {
    def name: String = "stdio"
  }

  case object Sse extends McpConnectorTransportKind {
    def name: String = "sse"
  }

  case object Websocket extends McpConnectorTransportKind {
    def name: String = "ws"
  }

  case object Http extends McpConnectorTransportKind {
    def name: String = "http"
  }
}

case class McpConnectorTransportStdioOption(raw: JsObject) {
  lazy val command: String = raw.select("command").asString
  lazy val args: Seq[String] = raw.select("args").asOpt[Seq[String]].getOrElse(Seq.empty)
  lazy val env: Map[String, String] = raw.select("env").asOpt[Map[String, String]].getOrElse(Map.empty)
  lazy val log: Boolean = raw.select("log").asOptBoolean.getOrElse(false)
}

case class McpConnectorTransportSseOption(raw: JsObject) {
  lazy val url: String = raw.select("url").asString
  lazy val headers: Map[String, String] = raw.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty)
  lazy val timeout: FiniteDuration = raw.select("timeout").asOpt[Long].map(_.millis).getOrElse(3.minutes)
  lazy val log: Boolean = raw.select("log").asOptBoolean.getOrElse(false)
}

case class McpConnectorTransport(kind: McpConnectorTransportKind = McpConnectorTransportKind.Stdio, options: JsObject = Json.obj()) {
  lazy val isStdio: Boolean = kind == McpConnectorTransportKind.Stdio
  lazy val isSse: Boolean = kind == McpConnectorTransportKind.Sse
  lazy val stdioOptions: McpConnectorTransportStdioOption = McpConnectorTransportStdioOption(options)
  lazy val sseOptions: McpConnectorTransportSseOption = McpConnectorTransportSseOption(options)

  def json: JsValue = McpConnectorTransport.format.writes(this)
}

object McpConnectorTransport {
  val format = new Format[McpConnectorTransport] {
    override def reads(json: JsValue): JsResult[McpConnectorTransport] = Try {
      McpConnectorTransport(
        kind = McpConnectorTransportKind.apply(json.select("kind").asOpt[String].getOrElse("stdio")),
        options = json.select("options").asOpt[JsObject].getOrElse(Json.obj()),
      )
    } match {
      case Failure(ex) => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }

    override def writes(o: McpConnectorTransport): JsValue = Json.obj(
      "kind" -> o.kind.name,
      "options" -> o.options,
    )
  }
}

case class McpConnectorPoolSettings(size: Int = 1) {
  def json: JsValue = Json.obj("size" -> size)
}

case class McpConnector(
  location: EntityLocation = EntityLocation.default,
  id: String,
  enabled: Boolean,
  name: String,
  description: String = "",
  tags: Seq[String] = Seq.empty,
  metadata: Map[String, String] = Map.empty,
  pool: McpConnectorPoolSettings = McpConnectorPoolSettings(),
  transport: McpConnectorTransport = McpConnectorTransport(),
  strict: Boolean = true,
  includeFunctions: Seq[String] = Seq.empty,
  excludeFunctions: Seq[String] = Seq.empty,
) extends EntityLocationSupport {
  override def internalId: String = id

  override def theName: String = name

  override def theDescription: String = description

  override def theTags: Seq[String] = tags

  override def theMetadata: Map[String, String] = metadata

  def isClientInCache(): Boolean = {
    McpConnector.connectorsCache.contains(id)
  }

  def hasClientChanged(): Boolean = {
    McpConnector.connectorsCache.get(id) match {
      case Some((_, _, hash, _)) if hash != json.stringify.sha256 => true
      case _ => false
    }
  }

  def restartIfNeeded(): Unit = {
    if (enabled) {
      clientPool()
    } else {
      McpConnector.connectorsCache.get(id).foreach { cli =>
        cli._1.asScala.map(_.close())
        McpConnector.connectorsCache.remove(id)
      }
    }
  }

  private def clientPool(): ConcurrentLinkedQueue[DefaultMcpClient] = synchronized {
    val transportSha = transport.json.stringify.sha256
    McpConnector.connectorsCache.get(id) match {
      case Some((cli, _, hash, _)) if hash == transportSha => cli
      case e => try {
        val cli = buildClient()
        val pool = new ConcurrentLinkedQueue[DefaultMcpClient]()
        pool.add(cli)
        McpConnector.connectorsCache.put(id, (pool, new AtomicInteger(1), transportSha, System.currentTimeMillis()))
        e.foreach(_._1.asScala.foreach(_.close()))
        pool
      } catch {
        case e: Throwable =>
          e.printStackTrace()
          new ConcurrentLinkedQueue[DefaultMcpClient]()
      }
    }
  }

  override def json: JsValue = McpConnector.format.writes(this)

  private def buildClient(): DefaultMcpClient = {
    val trsprt = transport.kind match {
      case McpConnectorTransportKind.Stdio => {
        val opts = transport.stdioOptions
        new StdioMcpTransport.Builder()
          .command((Seq(opts.command) ++ opts.args).asJava)
          .logEvents(opts.log)
          .environment(opts.env.asJava)
          .build()
      }
      case McpConnectorTransportKind.Sse => {
        val opts = transport.sseOptions
        new HttpMcpTransport.Builder()
          .sseUrl(opts.url)
          .customHeaders(opts.headers.asJava)
          .logRequests(opts.log)
          .logResponses(opts.log)
          .timeout(java.time.Duration.ofMillis(opts.timeout.toMillis))
          .build()
      }
      case McpConnectorTransportKind.Websocket => {
        val opts = transport.sseOptions
        new WebSocketMcpTransport.Builder()
          .url(opts.url)
          .logRequests(opts.log)
          .logResponses(opts.log)
          .timeout(java.time.Duration.ofMillis(opts.timeout.toMillis))
          .build()
        // new WebsocketHttpTransport(opts.url, opts.log, opts.log, java.time.Duration.ofMillis(opts.timeout.toMillis))
      }
      case McpConnectorTransportKind.Http => {
        val opts = transport.sseOptions
        new StreamableHttpMcpTransport.Builder()
          .url(opts.url)
          .customHeaders(opts.headers.asJava)
          .logRequests(opts.log)
          .logResponses(opts.log)
          .timeout(java.time.Duration.ofMillis(opts.timeout.toMillis))
          .build()
      }
    }
    new DefaultMcpClient.Builder()
      .transport(trsprt)
      .clientName(name)
      .clientVersion(metadata.getOrElse("version", "1.0"))
      .protocolVersion(metadata.getOrElse("protocol_version", "2025-06-18"))
      .toolExecutionTimeout(java.time.Duration.ofMillis(Duration.apply(metadata.get("timeout").getOrElse("180s")).toMillis))
      .build()
  }

  private def matchesTool(tool: ToolSpecification): Boolean = matches(tool.name())

  private def matches(name: String): Boolean = {
    if (!(includeFunctions.isEmpty && excludeFunctions.isEmpty)) {
      val canpass    = if (includeFunctions.isEmpty) true else includeFunctions.exists(p => otoroshi.utils.RegexPool.regex(p).matches(name))
      val cannotpass =
        if (excludeFunctions.isEmpty) false else excludeFunctions.exists(p => otoroshi.utils.RegexPool.regex(p).matches(name))
      canpass && !cannotpass
    } else {
      true
    }
  }

  def listTools()(implicit ec: ExecutionContext, env: Env): Future[Seq[ToolSpecification]] = withClient(_.listTools().asScala.filter(matchesTool))

  def listToolsBlocking()(implicit ec: ExecutionContext, env: Env): Seq[ToolSpecification] = Await.result(listTools(), 10.seconds)

  def call(name: String, args: String)(implicit ec: ExecutionContext, env: Env): Future[String] = {
    if (matches(name)) {
      val request = ToolExecutionRequest.builder().id(UUID.randomUUID().toString()).name(name).arguments(args).build()
      withClient(_.executeTool(request)).map { res =>
        if (res.isError) {
          s"an error occurred while executing request: ${res.resultText()}"
        } else {
          res.resultText()
        }
      }
    } else {
      s"you cannot call tool named: '${name}'".future
    }
  }

  private def withClient[T](f: DefaultMcpClient => T)(implicit ec: ExecutionContext, env: Env): Future[T] = {
    if (!enabled) {
      Future.failed(new RuntimeException("Mcp client is not enabled"))
    } else {
      //f(McpConnector.connectorsCache.get(id).get._1.peek()).vfuture
      val promise = Promise.apply[T]()
      McpConnector.connectorsCache.get(id) match {
        case None => {
          clientPool()
          withClient(f).andThen {
            case Failure(e) => promise.tryFailure(e)
            case Success(e) => promise.trySuccess(e)
          }
        }
        case Some((queue, counter, _, _)) => {
          val item = queue.poll()
          if (item == null) {
            if (counter.get() < pool.size) {
              counter.incrementAndGet()
              val cli = buildClient()
              try {
                val r = f(cli)
                promise.trySuccess(r)
              } catch {
                case e: Throwable => promise.tryFailure(e)
              } finally {
                queue.add(cli)
              }
            } else {
              env.otoroshiScheduler.scheduleOnce(100.millis) {
                withClient(f).andThen {
                  case Failure(e) => promise.tryFailure(e)
                  case Success(e) => promise.trySuccess(e)
                }
              }
            }
          } else {
            try {
              val r = f(item)
              promise.trySuccess(r)
            } catch {
              case e: Throwable => promise.tryFailure(e)
            } finally {
              queue.add(item)
            }
          }
        }
      }
      promise.future
    }
  }
}

object McpConnector {
  val connectorsCache = new TrieMap[String, (ConcurrentLinkedQueue[DefaultMcpClient], AtomicInteger, String, Long)]()
  val format = new Format[McpConnector] {
    override def writes(o: McpConnector): JsValue = o.location.jsonWithKey ++ Json.obj(
      "id" -> o.id,
      "enabled" -> o.enabled,
      "name" -> o.name,
      "description" -> o.description,
      "metadata" -> o.metadata,
      "tags" -> JsArray(o.tags.map(JsString.apply)),
      "pool" -> o.pool.json,
      "transport" -> o.transport.json,
      "strict" -> o.strict,
      "exclude_functions" -> o.excludeFunctions,
      "include_functions" -> o.includeFunctions,
    )

    override def reads(json: JsValue): JsResult[McpConnector] = Try {
      McpConnector(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = (json \ "id").as[String],
        enabled = (json \ "enabled").asOpt[Boolean].getOrElse(true),
        name = (json \ "name").as[String],
        description = (json \ "description").as[String],
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        pool = McpConnectorPoolSettings((json \ "pool" \ "size").asOpt[Int].filter(_ > 0).getOrElse(1)),
        transport = (json \ "transport").asOpt(McpConnectorTransport.format).getOrElse(McpConnectorTransport()),
        strict = (json \ "strict").asOpt[Boolean].getOrElse(true),
        includeFunctions = json.select("include_functions").asOpt[Seq[String]].getOrElse(Seq.empty),
        excludeFunctions = json.select("exclude_functions").asOpt[Seq[String]].getOrElse(Seq.empty),
      )
    } match {
      case Failure(ex) => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }

  def resource(env: Env, datastores: AiGatewayExtensionDatastores, states: AiGatewayExtensionState): Resource = {
    Resource(
      "McpConnector",
      "mcp-connectors",
      "mcp-connectors",
      "ai-gateway.extensions.cloud-apim.com",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[McpConnector](
        format = McpConnector.format,
        clazz = classOf[McpConnector],
        keyf = id => datastores.mcpConnectorsDatastore.key(id),
        extractIdf = c => datastores.mcpConnectorsDatastore.extractId(c),
        extractIdJsonf = json => json.select("id").asString,
        idFieldNamef = () => "id",
        tmpl = (v, p, ctx) => {
          McpConnector(
            id = IdGenerator.namedId("mcp-connector", env),
            enabled = true,
            name = "MCP Connector",
            description = "A new MCP Connector",
            metadata = Map.empty,
            tags = Seq.empty,
            location = EntityLocation.default,
            pool = McpConnectorPoolSettings(),
            includeFunctions = Seq.empty,
            excludeFunctions = Seq.empty,
            transport = McpConnectorTransport(
              kind = Stdio,
              options = Json.obj(
                "command" -> "node",
                "args" -> Json.arr("/foo/bar/index.js"),
                "env" -> Json.obj(
                  "TOKEN" -> "secret"
                )
              )
            )
          ).json
        },
        canRead = true,
        canCreate = true,
        canUpdate = true,
        canDelete = true,
        canBulk = true,
        stateAll = () => states.allMcpConnectors(),
        stateOne = id => states.mcpConnector(id),
        stateUpdate = values => states.updateMcpConnectors(values)
      )
    )
  }
}

trait McpConnectorsDataStore extends BasicStore[McpConnector]

class KvMcpConnectorsDataStore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
  extends McpConnectorsDataStore
    with RedisLikeStore[McpConnector] {
  override def fmt: Format[McpConnector] = McpConnector.format

  override def redisLike(implicit env: Env): RedisLike = redisCli

  override def key(id: String): String = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:mcpconntr:$id"

  override def extractId(value: McpConnector): String = value.id
}

object McpSupportImplicits {
  implicit class BetterJsonBooleanSchema(val node: JsonBooleanSchema) extends AnyVal {
    def safeDescription(): String = {
      Option(node.description()).getOrElse("")
    }
  }

  implicit class BetterJsonEnumSchema(val node: JsonEnumSchema) extends AnyVal {
    def safeDescription(): String = {
      Option(node.description()).getOrElse("")
    }
  }

  implicit class BetterJsonIntegerSchema(val node: JsonIntegerSchema) extends AnyVal {
    def safeDescription(): String = {
      Option(node.description()).getOrElse("")
    }
  }

  implicit class BetterJsonNumberSchema(val node: JsonNumberSchema) extends AnyVal {
    def safeDescription(): String = {
      Option(node.description()).getOrElse("")
    }
  }

  implicit class BetterJsonStringSchema(val node: JsonStringSchema) extends AnyVal {
    def safeDescription(): String = {
      Option(node.description()).getOrElse("")
    }
  }

  implicit class BetterJsonObjectSchema(val node: JsonObjectSchema) extends AnyVal {
    def safeDescription(): String = {
      Option(node.description()).getOrElse("")
    }
  }

  implicit class BetterJsonAnyOfSchema(val node: JsonAnyOfSchema) extends AnyVal {
    def safeDescription(): String = {
      Option(node.description()).getOrElse("")
    }
  }

  implicit class BetterJsonArraySchema(val node: JsonArraySchema) extends AnyVal {
    def safeDescription(): String = {
      Option(node.description()).getOrElse("")
    }
  }

  implicit class BetterToolSpecification(val node: ToolSpecification) extends AnyVal {
    def safeDescription(): String = {
      Option(node.description()).getOrElse("")
    }
  }
}

object McpSupport {

  import McpSupportImplicits._

  private val gson = new Gson()

  def restartConnectorsIfNeeded()(implicit env: Env): Unit = {
    val ext = env.adminExtensions.extension[AiExtension].get
    ext.states.allMcpConnectors().foreach { connector =>
      connector.restartIfNeeded()
    }
  }

  def stopConnectorsIfNeeded()(implicit env: Env): Unit = {
    val ext = env.adminExtensions.extension[AiExtension].get
    McpConnector.connectorsCache.keySet.foreach { key =>
      ext.states.mcpConnector(key) match {
        case None => McpConnector.connectorsCache.remove(key).foreach(_._1.asScala.foreach(_.close()))
        case Some(_) => ()
      }
    }
  }

  def schemaToJson(el: JsonSchemaElement): JsObject = {
    el match {
      case s: JsonBooleanSchema => Json.obj("description" -> s.safeDescription(), "type" -> "boolean")
      case s: JsonEnumSchema => Json.obj("description" -> s.safeDescription(), "type" -> "string", "enum" -> (s.enumValues().asScala.toSeq))
      case s: JsonIntegerSchema => Json.obj("description" -> s.safeDescription(), "type" -> "integer")
      case s: JsonNumberSchema => Json.obj("description" -> s.safeDescription(), "type" -> "number")
      case s: JsonStringSchema => Json.obj("description" -> s.safeDescription(), "type" -> "string")
      case s: JsonObjectSchema => {
        val additionalProperties: scala.Boolean = Option(s.additionalProperties()).map(_.booleanValue()).getOrElse(false)
        val required: Seq[String] = Option(s.required()).map(_.asScala.toSeq).getOrElse(Seq.empty)
        val properties: JsObject = JsObject(Option(s.properties()).map(_.asScala).getOrElse(Map.empty[String, JsonSchemaElement]).mapValues { el =>
          schemaToJson(el)
        })
        val definitions: JsObject = JsObject(Option(s.definitions()).map(_.asScala).getOrElse(Map.empty[String, JsonSchemaElement]).mapValues { el =>
          schemaToJson(el)
        })
        Json.obj(
          "description" -> s.safeDescription(),
          "type" -> "object",
          "required" -> required,
          "properties" -> properties,
          "definitions" -> definitions,
          "additionalProperties" -> additionalProperties,
        )
      }
      case s: JsonAnyOfSchema => Json.obj("description" -> s.safeDescription(), "anyOf" -> JsArray(s.anyOf().asScala.toSeq.map(schemaToJson)))
      case s: JsonArraySchema => Json.obj("description" -> s.safeDescription(), "type" -> "array", "items" -> schemaToJson(s.items()))
      case s: JsonReferenceSchema => Json.obj("$ref" -> s.reference())
      case _ => Json.parse(gson.toJson(el)).asObject
    }
  }

  def tools(connectors: Seq[String], includeFunctions: Seq[String], excludeFunctions: Seq[String])(implicit env: Env, ec: ExecutionContext): Seq[JsObject] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    connectors.zipWithIndex.flatMap(tuple => ext.states.mcpConnector(tuple._1).map(v => (v, tuple._2))).flatMap {
      case (connector, idx) =>
        connector.copy(includeFunctions = connector.includeFunctions ++ includeFunctions, excludeFunctions = connector.excludeFunctions ++ excludeFunctions).listToolsBlocking().map { function =>
          val additionalProperties: scala.Boolean = Option(function.parameters().additionalProperties()).map(_.booleanValue()).getOrElse(false)
          val required: Seq[String] = Option(function.parameters().required()).map(_.asScala.toSeq).getOrElse(Seq.empty)
          val properties: JsObject = JsObject(Option(function.parameters().properties()).map(_.asScala).getOrElse(Map.empty[String, JsonSchemaElement]).mapValues { el =>
            schemaToJson(el)
          })
          val definitions: JsObject = JsObject(Option(function.parameters().definitions()).map(_.asScala).getOrElse(Map.empty[String, JsonSchemaElement]).mapValues { el =>
            schemaToJson(el)
          })
          Json.obj(
            "type" -> "function",
            "function" -> Json.obj(
              //"name" -> s"mcp___${connector.id}___${function.name()}",
              "name" -> s"mcp___${idx}___${function.name()}",
              "description" -> function.safeDescription(),
              "strict" -> connector.strict,
              "parameters" -> Json.obj(
                "type" -> "object",
                "required" -> required,
                "additionalProperties" -> additionalProperties,
                "properties" -> properties,
                "definitions" -> definitions,
              )
            )
          )
        }
    }
  }

  def toolsCohere(connectors: Seq[String], includeFunctions: Seq[String], excludeFunctions: Seq[String])(implicit env: Env, ec: ExecutionContext): (Seq[JsObject], Map[String, String]) = {
    val ext = env.adminExtensions.extension[AiExtension].get
    val map = new TrieMap[String, String]()
    (connectors.zipWithIndex.flatMap(tuple => ext.states.mcpConnector(tuple._1).map(v => (v, tuple._2))).flatMap {
      case (connector, idx) =>
        connector.copy(includeFunctions = connector.includeFunctions ++ includeFunctions, excludeFunctions = connector.excludeFunctions ++ excludeFunctions).listToolsBlocking().map { function =>
          val additionalProperties: scala.Boolean = Option(function.parameters().additionalProperties()).map(_.booleanValue()).getOrElse(false)
          val required: Seq[String] = Option(function.parameters().required()).map(_.asScala.toSeq).getOrElse(Seq.empty)
          val properties: JsObject = JsObject(Option(function.parameters().properties()).map(_.asScala).getOrElse(Map.empty[String, JsonSchemaElement]).mapValues { el =>
            schemaToJson(el)
          })
          val definitions: JsObject = JsObject(Option(function.parameters().definitions()).map(_.asScala).getOrElse(Map.empty[String, JsonSchemaElement]).mapValues { el =>
            schemaToJson(el)
          })
          val fname = ("mcp___" + s"${idx}___${function.name()}".sha256)
          map.put(s"${idx}___${function.name()}".sha256, s"${idx}___${function.name()}")
          Json.obj(
            "type" -> "function",
            "function" -> Json.obj(
              //"name" -> s"mcp___${connector.id}___${function.name()}",
              "name" -> fname,
              "description" -> function.safeDescription(),
              "strict" -> connector.strict,
              "parameters" -> Json.obj(
                "type" -> "object",
                "required" -> required,
                "additionalProperties" -> additionalProperties,
                "properties" -> properties,
                "definitions" -> definitions,
              )
            )
          )
        }
    }, map.toMap)
  }

  def toolsAnthropic(connectors: Seq[String], includeFunctions: Seq[String], excludeFunctions: Seq[String])(implicit env: Env, ec: ExecutionContext): Seq[JsObject] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    connectors.zipWithIndex.flatMap(tuple => ext.states.mcpConnector(tuple._1).map(v => (v, tuple._2))).flatMap {
      case (connector, idx) =>
        connector.copy(includeFunctions = connector.includeFunctions ++ includeFunctions, excludeFunctions = connector.excludeFunctions ++ excludeFunctions).listToolsBlocking().map { function =>
          val additionalProperties: scala.Boolean = Option(function.parameters().additionalProperties()).map(_.booleanValue()).getOrElse(false)
          val required: Seq[String] = Option(function.parameters().required()).map(_.asScala.toSeq).getOrElse(Seq.empty)
          val properties: JsObject = JsObject(Option(function.parameters().properties()).map(_.asScala).getOrElse(Map.empty[String, JsonSchemaElement]).mapValues { el =>
            schemaToJson(el)
          })
          val definitions: JsObject = JsObject(Option(function.parameters().definitions()).map(_.asScala).getOrElse(Map.empty[String, JsonSchemaElement]).mapValues { el =>
            schemaToJson(el)
          })
          Json.obj(
            "name" -> s"mcp___${idx}___${function.name()}",
            "description" -> function.safeDescription(),
            "input_schema" -> Json.obj(
              "type" -> "object",
              "required" -> required,
              "additionalProperties" -> additionalProperties,
              "properties" -> properties,
              "definitions" -> definitions,
            )
          )
        }
    }
  }

  def callToolsOpenai(functions: Seq[GenericApiResponseChoiceMessageToolCall], connectors: Seq[String], providerName: String)(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    callTool(functions, connectors) { (resp, tc) =>
      Source(List(Json.obj("role" -> "assistant", "tool_calls" -> Json.arr(tc.raw)), Json.obj(
        "role" -> "tool",
        "content" -> resp,
        "tool_call_id" -> tc.id
      ))).applyOnIf(providerName.toLowerCase().contains("deepseek")) { s => // temporary fix for https://github.com/deepseek-ai/DeepSeek-V3/issues/15
        s.concat(Source(List(
          Json.obj("role" -> "user", "content" -> resp)
        )))
      }
    }
  }

  def callToolsCohere(functions: Seq[GenericApiResponseChoiceMessageToolCall], connectors: Seq[String], providerName: String, fmap: Map[String, String])(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    callToolCohere(functions, connectors, fmap) { (resp, tc) =>
      Source(List(Json.obj("role" -> "assistant", "tool_calls" -> Json.arr(tc.raw)), Json.obj(
        "role" -> "tool",
        "content" -> resp,
        "tool_call_id" -> tc.id
      ))).applyOnIf(providerName.toLowerCase().contains("deepseek")) { s => // temporary fix for https://github.com/deepseek-ai/DeepSeek-V3/issues/15
        s.concat(Source(List(
          Json.obj("role" -> "user", "content" -> resp)
        )))
      }
    }
  }

  private def callToolCohere(functions: Seq[GenericApiResponseChoiceMessageToolCall], connectors: Seq[String], fmap: Map[String, String])(f: (String, GenericApiResponseChoiceMessageToolCall) => Source[JsValue, _])(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    Source(functions.toList)
      .mapAsync(1) { _toolCall =>
        val fn = _toolCall.raw.select("function").asObject
        val nfn = fn ++ Json.obj("name" -> fmap(fn.select("name").asString))
        val toolCall = GenericApiResponseChoiceMessageToolCall(_toolCall.raw.asObject ++ Json.obj("function" -> nfn))

        val cid = toolCall.function.connectorId
        val connectorId = connectors(cid)
        val functionName = toolCall.function.connectorFunctionName
        val ext = env.adminExtensions.extension[AiExtension].get
        ext.states.mcpConnector(connectorId) match {
          case None => (s"undefined mcp connector ${connectorId}", toolCall).some.vfuture
          case Some(function) => {
            println(s"calling mcp function '${functionName}' with args: '${toolCall.function.arguments}'")
            function.call(functionName, toolCall.function.arguments).map { r =>
              (r, _toolCall).some
            }
          }
        }
      }
      .collect {
        case Some(t) => t
      }
      .flatMapConcat {
        case (resp, tc) => f(resp, tc)
      }
      .runWith(Sink.seq)(env.otoroshiMaterializer)
  }

  def callToolsAnthropic(functions: Seq[AnthropicApiResponseChoiceMessageToolCall], connectors: Seq[String], providerName: String)(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    callAnthropic(functions, connectors) { (resp, tc) =>
      Source(List(
        Json.obj("role" -> "assistant", "content" -> Json.arr(Json.obj(
          "type" -> "tool_use",
          "id" -> tc.id,
          "name" -> tc.name,
          "input" -> tc.input,
        ))),
        Json.obj("role" -> "user", "content" -> Json.arr(Json.obj(
          "type" -> "tool_result",
          "tool_use_id" -> tc.id,
          "content" -> resp
        )))))
    }
  }

  private def callAnthropic(functions: Seq[AnthropicApiResponseChoiceMessageToolCall], connectors: Seq[String])(f: (String, AnthropicApiResponseChoiceMessageToolCall) => Source[JsValue, _])(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    Source(functions.toList)
      .mapAsync(1) { toolCall =>
        val args = toolCall.arguments
        val cid: Int = toolCall.connectorId
        val functionName: String = toolCall.connectorFunctionName
        val connectorId = connectors(cid)
        val ext = env.adminExtensions.extension[AiExtension].get
        ext.states.mcpConnector(connectorId) match {
          case None => (s"undefined mcp connector ${connectorId}", toolCall).some.vfuture
          case Some(function) => {
            println(s"calling mcp function '${functionName}' with args: '${args}'")
            function.call(functionName, args).map { r =>
              (r, toolCall).some
            }
          }
        }
      }
      .collect {
        case Some(t) => t
      }
      .flatMapConcat {
        case (resp, tc) => f(resp, tc)
      }
      .runWith(Sink.seq)(env.otoroshiMaterializer)
  }

  def callToolsOllama(functions: Seq[GenericApiResponseChoiceMessageToolCall], connectors: Seq[String])(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    callTool(functions, connectors) { (resp, tc) =>
      Source(List(Json.obj("role" -> "assistant", "content" -> "", "tool_calls" -> Json.arr(tc.raw)), Json.obj(
        "role" -> "tool",
        "content" -> resp,
      )))
    }
  }

  private def callTool(functions: Seq[GenericApiResponseChoiceMessageToolCall], connectors: Seq[String])(f: (String, GenericApiResponseChoiceMessageToolCall) => Source[JsValue, _])(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    Source(functions.toList)
      .mapAsync(1) { toolCall =>
        val cid = toolCall.function.connectorId
        val connectorId = connectors(cid)
        val functionName = toolCall.function.connectorFunctionName
        val ext = env.adminExtensions.extension[AiExtension].get
        ext.states.mcpConnector(connectorId) match {
          case None => (s"undefined mcp connector ${connectorId}", toolCall).some.vfuture
          case Some(function) => {
            println(s"calling mcp function '${functionName}' with args: '${toolCall.function.arguments}'")
            function.call(functionName, toolCall.function.arguments).map { r =>
              (r, toolCall).some
            }
          }
        }
      }
      .collect {
        case Some(t) => t
      }
      .flatMapConcat {
        case (resp, tc) => f(resp, tc)
      }
      .runWith(Sink.seq)(env.otoroshiMaterializer)
  }
}

/*
object WebsocketHttpTransport {
  val logger = LoggerFactory.getLogger(classOf[WebsocketHttpTransport])
  val OBJECT_MAPPER = new ObjectMapper()
}

class WebsocketHttpTransport(wsUrl: String, logRequests: Boolean, logResponses: Boolean, timeout: java.time.Duration) extends McpTransport {

  private val log = WebsocketHttpTransport.logger
  private val OBJECT_MAPPER = WebsocketHttpTransport.OBJECT_MAPPER

  private val client = new OkHttpClient.Builder().build()
  private val websocket = new AtomicReference[WebSocket]()
  private val websocketOpen = new AtomicBoolean(false)
  private val handler = new AtomicReference[McpOperationHandler]()

  override def start(messageHandler: McpOperationHandler): Unit = {
    initWebSocket()
    handler.set(messageHandler)
  }

  override def initialize(request: McpInitializeRequest): CompletableFuture[JsonNode] = {
    val future = new CompletableFuture[JsonNode]()
    handler.get.startOperation(request.getId, future)
    val payload = OBJECT_MAPPER.writeValueAsString(request)
    if (logRequests) {
      log.info("request: {}", payload)
    }
    websocketClient().send(payload)
    future
  }

  override def listTools(request: McpListToolsRequest): CompletableFuture[JsonNode] = {
    val future = new CompletableFuture[JsonNode]()
    handler.get.startOperation(request.getId, future)
    val payload = OBJECT_MAPPER.writeValueAsString(request)
    if (logRequests) {
      log.info("request: {}", payload)
    }
    websocketClient().send(payload)
    future
  }

  private def websocketClient(): WebSocket = synchronized {
    if (!websocketOpen.get()) {
      initWebSocket()
    }
    websocket.get()
  }

  private def initWebSocket(): Unit = {
    val request = new Request.Builder()
      .url(wsUrl)
      .get()
      .build()
    val ws: WebSocket = client.newWebSocket(request, new WebSocketListener {
      override def onMessage(webSocket: WebSocket, bytes: ByteString): Unit = onWebsocketMessage(bytes.utf8().parseJson)

      override def onMessage(webSocket: WebSocket, text: String): Unit = onWebsocketMessage(text.parseJson)

      override def onOpen(webSocket: WebSocket, response: Response): Unit = websocketOpen.set(true)

      override def onClosed(webSocket: WebSocket, code: Int, reason: String): Unit = websocketOpen.set(false)

      override def onClosing(webSocket: WebSocket, code: Int, reason: String): Unit = ()

      override def onFailure(webSocket: WebSocket, t: Throwable, response: Response): Unit = ()
    })
    websocket.set(ws)
  }

  private def onWebsocketMessage(message: JsValue): Unit = {
    val payload = OBJECT_MAPPER.readTree(message.stringify)
    if (logResponses) {
      log.info("response: {}", message.stringify)
    }
    handler.get().handle(payload)
  }

  override def executeTool(request: McpCallToolRequest): CompletableFuture[JsonNode] = {
    val future = new CompletableFuture[JsonNode]()
    handler.get.startOperation(request.getId, future)
    val payload = OBJECT_MAPPER.writeValueAsString(request)
    if (logRequests) {
      log.info("request: {}", payload)
    }
    websocketClient().send(payload)
    future
  }

  override def cancelOperation(operationId: Long): Unit = {
    ()
  }

  override def close(): Unit = {
    Option(websocket.get()).foreach(_.close(1000, "close"))
    client.dispatcher.executorService.shutdown()
  }
}


object OtoroshiHttpTransport {
  val logger = LoggerFactory.getLogger(classOf[OtoroshiHttpTransport])
  val OBJECT_MAPPER = new ObjectMapper()
}

class OtoroshiHttpTransport(httpUrl: String, logRequests: Boolean, logResponses: Boolean, timeout: java.time.Duration) extends McpTransport {

  private val log = OtoroshiHttpTransport.logger
  private val OBJECT_MAPPER = OtoroshiHttpTransport.OBJECT_MAPPER

  private val client = new OkHttpClient.Builder().build()
  private val handler = new AtomicReference[McpOperationHandler]()

  override def start(messageHandler: McpOperationHandler): Unit = {
    handler.set(messageHandler)
  }

  override def initialize(request: McpInitializeRequest): CompletableFuture[JsonNode] = {
    try {
      val payload = OBJECT_MAPPER.writeValueAsString(request)
      if (logRequests) {
        log.info("request: {}", payload)
      }
      val httpRequest = new Request.Builder()
        .url(httpUrl)
        .header("Content-Type", "application/json")
        .post(RequestBody.create(OBJECT_MAPPER.writeValueAsBytes(request)))
        .build()
      execute(httpRequest, request.getId)
    } catch {
      case e: JsonProcessingException => CompletableFuture.failedFuture(e)
    }
  }

  private def execute(request: Request, id: Long): CompletableFuture[JsonNode] = {
    val future = new CompletableFuture[JsonNode]()
    handler.get().startOperation(id, future)
    client.newCall(request).enqueue(new Callback() {
      override def onFailure(call: Call, e: IOException): Unit = {
        future.completeExceptionally(e)
      }

      override def onResponse(call: Call, response: Response): Unit = {
        val statusCode = response.code
        if (!isExpectedStatusCode(statusCode)) {
          future.completeExceptionally(new RuntimeException("Unexpected status code: " + statusCode))
        } else {
          val payload = response.body().byteString().utf8()
          if (logResponses) {
            log.info("response: {}", payload)
          }
          handler.get().handle(OBJECT_MAPPER.readTree(payload))
        }
      }
    })
    future
  }

  private def isExpectedStatusCode(statusCode: Int) = statusCode >= 200 && statusCode < 300

  override def listTools(request: McpListToolsRequest): CompletableFuture[JsonNode] = {
    try {
      val payload = OBJECT_MAPPER.writeValueAsString(request)
      if (logRequests) {
        log.info("request: {}", payload)
      }
      val httpRequest = new Request.Builder()
        .url(httpUrl)
        .header("Content-Type", "application/json")
        .post(RequestBody.create(OBJECT_MAPPER.writeValueAsBytes(request)))
        .build()
      execute(httpRequest, request.getId)
    } catch {
      case e: JsonProcessingException => CompletableFuture.failedFuture(e)
    }
  }

  override def executeTool(request: McpCallToolRequest): CompletableFuture[JsonNode] = {
    try {
      val payload = OBJECT_MAPPER.writeValueAsString(request)
      if (logRequests) {
        log.info("request: {}", payload)
      }
      val httpRequest = new Request.Builder()
        .url(httpUrl)
        .header("Content-Type", "application/json")
        .post(RequestBody.create(OBJECT_MAPPER.writeValueAsBytes(request)))
        .build()
      execute(httpRequest, request.getId)
    } catch {
      case e: JsonProcessingException => CompletableFuture.failedFuture(e)
    }
  }

  override def cancelOperation(operationId: Long): Unit = {
    ()
  }

  override def close(): Unit = {
    client.dispatcher.executorService.shutdown()
  }
}
*/