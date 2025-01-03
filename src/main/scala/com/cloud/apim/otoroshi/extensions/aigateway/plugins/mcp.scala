package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.entities.GenericApiResponseChoiceMessageToolCall
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.gson.Gson
import dev.langchain4j.agent.tool.{ToolExecutionRequest, ToolSpecification}
import dev.langchain4j.mcp.client.DefaultMcpClient
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport
import dev.langchain4j.model.chat.request.json._
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json._
import play.api.mvc.Results

import java.util.UUID
import java.util.concurrent.Executors
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.{asScalaBufferConverter, mapAsScalaMapConverter}
import scala.util.{Failure, Success, Try}

case class McpProxyEndpointConfig(refs: Seq[String]) extends NgPluginConfig {
  def json: JsValue = McpProxyEndpointConfig.format.writes(this)
}

object McpProxyEndpointConfig {
  val configFlow: Seq[String] = Seq("refs")
  val configSchema: Option[JsObject] = Some(Json.obj(
    "refs" -> Json.obj(
      "type" -> "select",
      "array" -> true,
      "label" -> s"LLM Tool Functions",
      "props" -> Json.obj(
        "optionsFrom" -> s"/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/tool-functions",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      ),
    )
  ))
  val default = McpProxyEndpointConfig(Seq.empty)
  val format = new Format[McpProxyEndpointConfig] {
    override def writes(o: McpProxyEndpointConfig): JsValue = Json.obj("refs" -> o.refs)
    override def reads(json: JsValue): JsResult[McpProxyEndpointConfig] = Try {
      val singleRef = json.select("ref").asOpt[String].map(r => Seq(r)).getOrElse(Seq.empty)
      val refs = json.select("refs").asOpt[Seq[String]].getOrElse(Seq.empty)
      val allRefs = refs ++ singleRef
      McpProxyEndpointConfig(
        refs = allRefs
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

class McpLocalProxyEndpoint extends NgBackendCall {

  override def name: String = "Cloud APIM - MCP Local Proxy Endpoint"
  override def description: Option[String] = "Exposes tool functions as an MCP server with a local proxy provided by @cloud-admin/otoroshi-mcp-proxy".some

  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))
  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)
  override def useDelegates: Boolean = false
  override def defaultConfigObject: Option[NgPluginConfig] = Some(McpProxyEndpointConfig.default)

  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = McpProxyEndpointConfig.configFlow
  override def configSchema: Option[JsObject] = McpProxyEndpointConfig.configSchema

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'MCP Local Proxy Endpoint' plugin is available !")
    }
    ().vfuture
  }

  def call(_jsonBody: JsValue, config: McpProxyEndpointConfig, ctx: NgbBackendCallContext)(implicit ec: ExecutionContext, env: Env): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val method = _jsonBody.select("method").asOpt[String].getOrElse("tools/get").toLowerCase()
    val params = _jsonBody.select("params").asOpt[JsObject].getOrElse(Json.obj())
    val ext = env.adminExtensions.extension[AiExtension].get
    val functions = config.refs.flatMap(r => ext.states.toolFunction(r))
    val functionsMap = functions.map(f => (f.name, f)).toMap

    method match {
      case "tools/get" => {
        val payload = JsArray(functions.map { wf =>
          val required: JsArray = wf.required.map(v => JsArray(v.map(_.json))).getOrElse(JsArray(wf.parameters.value.keySet.toSeq.map(_.json)))
          Json.obj(
            "name" -> wf.name,
            "description" -> wf.description,
            "inputSchema" -> Json.obj(
              "type" -> "object",
              "properties" -> wf.parameters,
              "required" -> required,
            ),
          )
        })
        BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok(payload)), None).rightf
      }
      case "tools/call" => {
        val name = params.select("name").asString
        val arguments = params.select("arguments").asOpt[JsObject].getOrElse(Json.obj())
        functionsMap.get(name) match {
          case None => NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(Json.obj("error" -> "bad_request", "error_details" -> "unknown function"))).leftf
          case Some(function) => {
            function.call(arguments.stringify).map { res =>
              BackendCallResponse(NgPluginHttpResponse.fromResult(Results.Ok(
                Json.obj(
                  "type" -> "text",
                  "text" -> res
                )
              )), None).right
            }
          }
        }
      }
      case _ => NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(Json.obj("error" -> "bad_request", "error_details" -> "unknown method"))).leftf
    }
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    if (ctx.request.hasBody) {
      ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
        try {
          val jsonBody = bodyRaw.utf8String.parseJson
          val config = ctx.cachedConfig(internalName)(McpProxyEndpointConfig.format).getOrElse(McpProxyEndpointConfig.default)
          call(jsonBody, config, ctx)
        } catch {
          case e: Throwable => NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(Json.obj("error" -> "bad_request", "error_details" -> e.getMessage))).leftf
        }
      }
    } else {
      val config = ctx.cachedConfig(internalName)(McpProxyEndpointConfig.format).getOrElse(McpProxyEndpointConfig.default)
      call(Json.obj(
        "method" -> "tools/get",
        "params" -> Json.obj(),
      ), config, ctx)
    }
  }
}

object McpTester {

  private val ec: ExecutionContext = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(2))
  private val mapper = new ObjectMapper()
  private val gson = new Gson()

  def withClient[T](f: DefaultMcpClient => T): T = {
    val stdioTransport = new StdioMcpTransport.Builder()
      .command(java.util.List.of("/Users/mathieuancelin/.nvm/versions/node/v18.19.0/bin/node", "/Users/mathieuancelin/projects/clever-ai/mpc-test/mcp-otoroshi-proxy/bin/proxy.js"))
      .logEvents(true) // only if you want to see the traffic in the log
      .build()
    // val sseTransport = new HttpMcpTransport.Builder()
    //   .sseUrl("http://localhost:3001/sse")
    //   //.postUrl("http://localhost:3001/message")
    //   .logRequests(true) // if you want to see the traffic in the log
    //   .logResponses(true)
    //   .build()
    val mcpClient = new DefaultMcpClient.Builder()
      .transport(stdioTransport)
      .build()
    // mcpClient.listTools()
    // val res = mcpClient.executeTool()
    try {
      f(mcpClient)
    } finally {
      mcpClient.close()
    }
  }

  def listTools(): Seq[ToolSpecification] = {
    withClient(_.listTools().asScala)
  }

  def callAsync(name: String = "get-rooms", args: String = "{}"): Future[String] = {
    Future.apply {
      val request = ToolExecutionRequest.builder().id(UUID.randomUUID().toString()).name(name).arguments(args).build()
      withClient(_.executeTool(request))
    }(ec)
  }

  def call(name: String = "get-rooms", args: String = "{}"): String = {
    val request = ToolExecutionRequest.builder().id(UUID.randomUUID().toString()).name(name).arguments(args).build()
    withClient(_.executeTool(request))
  }

  private def schemaToJson(el: JsonSchemaElement): JsObject = {
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

  def tools(connectors: Seq[String]): Seq[JsObject] = {
    /*Json.obj(
      "tools" -> JsArray(*/listTools().map { function =>
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
            "name" -> s"mcp___${function.name()}",
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
      }/*)
    )*/
  }

  private def callTool(functions: Seq[GenericApiResponseChoiceMessageToolCall])(f: (String, GenericApiResponseChoiceMessageToolCall) => Source[JsValue, _])(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    Source(functions.toList)
      .mapAsync(1) { toolCall =>
        val fid = toolCall.function.name
        println(s"calling function '${fid}' with args: '${toolCall.function.arguments}'")
        callAsync(fid, toolCall.function.arguments).map { r =>
          (r, toolCall).some
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

  def callToolsOpenai(functions: Seq[GenericApiResponseChoiceMessageToolCall])(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    callTool(functions) { (resp, tc) =>
      Source(List(Json.obj("role" -> "assistant", "tool_calls" -> Json.arr(tc.raw)), Json.obj(
        "role" -> "tool",
        "content" -> resp,
        "tool_call_id" -> tc.id
      )))
    }
  }

  def callToolsOllama(functions: Seq[GenericApiResponseChoiceMessageToolCall])(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    callTool(functions) { (resp, tc) =>
      Source(List(Json.obj("role" -> "assistant", "content" -> "", "tool_calls" -> Json.arr(tc.raw)), Json.obj(
        "role" -> "tool",
        "content" -> resp,
      )))
    }
  }
}
