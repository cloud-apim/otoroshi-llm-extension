package com.cloud.apim.otoroshi.extensions.aigateway.entities

import akka.stream.scaladsl.{Sink, Source}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.McpConnectorTransportKind.Stdio
import com.google.gson.Gson
import dev.langchain4j.agent.tool.{ToolExecutionRequest, ToolSpecification}
import dev.langchain4j.mcp.client.DefaultMcpClient
import dev.langchain4j.mcp.client.transport.http.HttpMcpTransport
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport
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
  case object Stdio extends McpConnectorTransportKind { def name: String = "stdio" }
  case object Sse extends McpConnectorTransportKind { def name: String = "sse" }
  def apply(str: String): McpConnectorTransportKind = str.toLowerCase match {
    case "sse" => Sse
    case _ => Stdio
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
  lazy val timeout: FiniteDuration = raw.select("timeout").asOpt[Long].map(_.millis).getOrElse(30.seconds)
  lazy val log: Boolean = raw.select("log").asOptBoolean.getOrElse(false)
}

case class McpConnectorTransport(kind: McpConnectorTransportKind = McpConnectorTransportKind.Stdio, options: JsObject = Json.obj()) {
  def json: JsValue = McpConnectorTransport.format.writes(this)
  lazy val isStdio: Boolean = kind == McpConnectorTransportKind.Stdio
  lazy val isSse: Boolean = kind == McpConnectorTransportKind.Sse
  lazy val stdioOptions: McpConnectorTransportStdioOption = McpConnectorTransportStdioOption(options)
  lazy val sseOptions: McpConnectorTransportSseOption = McpConnectorTransportSseOption(options)
}
object McpConnectorTransport {
  val format = new Format[McpConnectorTransport] {
    override def reads(json: JsValue): JsResult[McpConnectorTransport] = Try {
      McpConnectorTransport(
        kind = McpConnectorTransportKind.apply(json.select("kind").asOpt[String].getOrElse("stdio")),
        options = json.select("options").asOpt[JsObject].getOrElse(Json.obj()),
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
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
                           name: String,
                           description: String = "",
                           tags: Seq[String] = Seq.empty,
                           metadata: Map[String, String] = Map.empty,
                           pool: McpConnectorPoolSettings = McpConnectorPoolSettings(),
                           transport: McpConnectorTransport = McpConnectorTransport(),
                         ) extends EntityLocationSupport {
  override def internalId: String               = id
  override def json: JsValue                    = McpConnector.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
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
    clientPool()
  }

  private def clientPool(): ConcurrentLinkedQueue[DefaultMcpClient] = synchronized {
    McpConnector.connectorsCache.get(id) match {
      case Some((cli, _, hash, _)) if hash == json.stringify.sha256 => cli
      case e => {
        val cli = buildClient()
        val pool = new ConcurrentLinkedQueue[DefaultMcpClient]()
        pool.add(cli)
        McpConnector.connectorsCache.put(id, (pool, new AtomicInteger(1), json.stringify.sha256, System.currentTimeMillis()))
        e.foreach(_._1.asScala.foreach(_.close()))
        pool
      }
    }
  }

  private def withClient[T](f: DefaultMcpClient => T)(implicit ec: ExecutionContext, env: Env): Future[T] = {
    f(McpConnector.connectorsCache.get(id).get._1.peek()).vfuture
    /*val promise = Promise.apply[T]()
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
    promise.future*/
  }

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
          .logRequests(opts.log)
          .logResponses(opts.log)
          .timeout(java.time.Duration.ofMillis(opts.timeout.toMillis))
          .build()
      }
    }
    new DefaultMcpClient.Builder()
      .transport(trsprt)
      .clientName(name)
      .clientVersion(metadata.get("version").getOrElse("0.0.0"))
      .toolExecutionTimeout(java.time.Duration.ofMillis(Duration.apply(metadata.get("timeout").getOrElse("30s")).toMillis))
      .build()
  }

  def listTools()(implicit ec: ExecutionContext, env: Env): Future[Seq[ToolSpecification]] = withClient(_.listTools().asScala)

  def listToolsBlocking()(implicit ec: ExecutionContext, env: Env): Seq[ToolSpecification] = Await.result(listTools(), 10.seconds)

  def call(name: String, args: String)(implicit ec: ExecutionContext, env: Env): Future[String] = {
    val request = ToolExecutionRequest.builder().id(UUID.randomUUID().toString()).name(name).arguments(args).build()
    withClient(_.executeTool(request))
  }
}

object McpConnector {
  val connectorsCache = new TrieMap[String, (ConcurrentLinkedQueue[DefaultMcpClient], AtomicInteger, String, Long)]()
  val format = new Format[McpConnector] {
    override def writes(o: McpConnector): JsValue             = o.location.jsonWithKey ++ Json.obj(
      "id"           -> o.id,
      "name"         -> o.name,
      "description"  -> o.description,
      "metadata"     -> o.metadata,
      "tags"         -> JsArray(o.tags.map(JsString.apply)),
      "pool"         -> o.pool.json,
      "transport"    -> o.transport.json,
    )
    override def reads(json: JsValue): JsResult[McpConnector] = Try {
      McpConnector(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = (json \ "id").as[String],
        name = (json \ "name").as[String],
        description = (json \ "description").as[String],
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        pool = McpConnectorPoolSettings((json \ "pool" \ "size").asOpt[Int].filter(_ > 0).getOrElse(1)),
        transport = (json \ "transport").asOpt(McpConnectorTransport.format).getOrElse(McpConnectorTransport()),
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
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
        tmpl = (v, p) => {
          McpConnector(
            id = IdGenerator.namedId("mcp-connector", env),
            name = "MCP Connector",
            description = "A new MCP Connector",
            metadata = Map.empty,
            tags = Seq.empty,
            location = EntityLocation.default,
            pool = McpConnectorPoolSettings(),
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
  override def fmt: Format[McpConnector]                  = McpConnector.format
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def key(id: String): String                 = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:mcpconntr:$id"
  override def extractId(value: McpConnector): String    = value.id
}

object McpSupport {

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
      case s: JsonBooleanSchema   => Json.obj("description" -> s.description(), "type" -> "boolean")
      case s: JsonEnumSchema      => Json.obj("description" -> s.description(), "type" -> "string", "enum" -> (s.enumValues().asScala.toSeq))
      case s: JsonIntegerSchema   => Json.obj("description" -> s.description(), "type" -> "integer")
      case s: JsonNumberSchema    => Json.obj("description" -> s.description(), "type" -> "number")
      case s: JsonStringSchema    => Json.obj("description" -> s.description(), "type" -> "string")
      case s: JsonObjectSchema    => {
        val additionalProperties: scala.Boolean = Option(s.additionalProperties()).map(_.booleanValue()).getOrElse(false)
        val required: Seq[String] = Option(s.required()).map(_.asScala.toSeq).getOrElse(Seq.empty)
        val properties: JsObject = JsObject(Option(s.properties()).map(_.asScala).getOrElse(Map.empty[String, JsonSchemaElement]).mapValues { el =>
          schemaToJson(el)
        })
        val definitions: JsObject = JsObject(Option(s.definitions()).map(_.asScala).getOrElse(Map.empty[String, JsonSchemaElement]).mapValues { el =>
          schemaToJson(el)
        })
        Json.obj(
          "description" -> s.description(),
          "type" -> "object",
          "required" -> required,
          "properties" -> properties,
          "definitions" -> definitions,
          "additionalProperties" -> additionalProperties,
        )
      }
      case s: JsonAnyOfSchema     => Json.obj("description" -> s.description(), "anyOf" -> JsArray(s.anyOf().asScala.toSeq.map(schemaToJson)))
      case s: JsonArraySchema     => Json.obj("description" -> s.description(), "type" -> "array", "items" ->schemaToJson(s.items()))
      case s: JsonReferenceSchema => Json.obj("$ref" -> s.reference())
      case _ => Json.parse(gson.toJson(el)).asObject
    }
  }

  def tools(connectors: Seq[String])(implicit env: Env, ec: ExecutionContext): Seq[JsObject] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    connectors.zipWithIndex.flatMap(tuple => ext.states.mcpConnector(tuple._1).map(v => (v, tuple._2))).flatMap {
      case (connector, idx) =>
        connector.listToolsBlocking().map { function =>
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
              "description" -> function.description(),
              "strict" -> true,
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

  def callToolsOllama(functions: Seq[GenericApiResponseChoiceMessageToolCall], connectors: Seq[String])(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    callTool(functions, connectors) { (resp, tc) =>
      Source(List(Json.obj("role" -> "assistant", "content" -> "", "tool_calls" -> Json.arr(tc.raw)), Json.obj(
        "role" -> "tool",
        "content" -> resp,
      )))
    }
  }
}
