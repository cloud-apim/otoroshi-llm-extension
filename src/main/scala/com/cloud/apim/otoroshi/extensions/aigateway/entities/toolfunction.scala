package com.cloud.apim.otoroshi.extensions.aigateway.entities

import akka.stream.alpakka.s3._
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{Attributes, Materializer}
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.agents.InlineFunctions
import com.github.blemale.scaffeine.Scaffeine
import io.otoroshi.wasm4s.scaladsl.{WasmFunctionParameters, WasmSource, WasmSourceKind}
import otoroshi.api._
import otoroshi.el.GlobalExpressionLanguage
import otoroshi.env.Env
import otoroshi.models._
import otoroshi.next.extensions.AdminExtensionId
import otoroshi.next.models.NgTlsConfig
import otoroshi.next.plugins.BodyHelper
import otoroshi.next.workflow.{Node, WorkflowAdminExtension, WorkflowHelper}
import otoroshi.security.IdGenerator
import otoroshi.storage._
import otoroshi.storage.drivers.inmemory.S3Configuration
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import otoroshi.wasm.{WasmAuthorizations, WasmConfig}
import otoroshi_plugins.com.cloud.apim.extensions.aigateway._
import play.api.Logger
import play.api.libs.json._
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.regions.providers.AwsRegionProvider

import java.io.File
import java.nio.file.Files
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{DurationInt, DurationLong}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

sealed trait LlmToolFunctionBackendKind {
  def name: String
  def json: JsValue = JsString(name)
}
object LlmToolFunctionBackendKind {
  case object QuickJs extends LlmToolFunctionBackendKind { def name: String = "QuickJs" }
  case object WasmPlugin extends LlmToolFunctionBackendKind { def name: String = "WasmPlugin" }
  case object Http extends LlmToolFunctionBackendKind { def name: String = "Http" }
  case object Route extends LlmToolFunctionBackendKind { def name: String = "Route" }
  case object Workflow extends LlmToolFunctionBackendKind { def name: String = "Workflow" }
  def apply(str: String): LlmToolFunctionBackendKind = str match {
    case "QuickJs" => QuickJs
    case "WasmPlugin" => WasmPlugin
    case "Http" => Http
    case "Route" => Route
    case "Workflow" => Workflow
    case _ => QuickJs
  }
}

case class LlmToolFunctionBackend(kind: LlmToolFunctionBackendKind, options: LlmToolFunctionBackendOptions) {
  def json: JsValue = Json.obj(
    "kind" -> kind.json,
    "options" -> options.json
  )
}


sealed trait LlmToolFunctionBackendOptions {
  def json: JsValue
  def call(arguments: String, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[String]
}

object LlmToolFunctionBackendOptions {

  private def s3ClientSettingsAttrs(conf: S3Configuration): Attributes = {
    val awsCredentials = StaticCredentialsProvider.create(
      AwsBasicCredentials.create(conf.access, conf.secret)
    )
    val settings       = S3Settings(
      bufferType = MemoryBufferType,
      credentialsProvider = awsCredentials,
      s3RegionProvider = new AwsRegionProvider {
        override def getRegion: Region = Region.of(conf.region)
      },
      listBucketApiVersion = ApiVersion.ListBucketVersion2
    ).withEndpointUrl(conf.endpoint)
    S3Attributes.settings(settings)
  }

  private def fileContent(key: String, config: S3Configuration)(implicit
                                                                ec: ExecutionContext,
                                                                mat: Materializer
  ): Future[Option[(ObjectMetadata, ByteString)]] = {
    S3.download(config.bucket, key)
      .withAttributes(s3ClientSettingsAttrs(config))
      .runWith(Sink.headOption)
      .map(_.flatten)
      .flatMap { opt =>
        opt
          .map {
            case (source, om) => {
              source.runFold(ByteString.empty)(_ ++ _).map { content =>
                (om, content).some
              }
            }
          }
          .getOrElse(None.vfuture)
      }
  }

  def getDefaultToolCallCode(): Future[String] = {
    s"""'inline module';
       |exports.tool_call = function(args_str) {
       |  const args = JSON.parse(args_str);
       |  return "nothing";
       |};
       |""".stripMargin.vfuture
  }

  def getCode(path: String, headers: Map[String, String], defaultCode: () => Future[String])(implicit env: Env, ec: ExecutionContext): Future[String] = {

    LlmToolFunction.modulesCache.getIfPresent(path) match {
      case Some(code) => code.vfuture
      case None => {
        if (path.startsWith("https://") || path.startsWith("http://")) {
          env.Ws.url(path)
            .withFollowRedirects(true)
            .withRequestTimeout(30.seconds)
            .withHttpHeaders(headers.toSeq: _*)
            .get()
            .flatMap { response =>
              if (response.status == 200) {
                LlmToolFunction.modulesCache.put(path, response.body)
                response.body.vfuture
              } else {
                defaultCode().map { code =>
                  LlmToolFunction.modulesCache.put(path, code)
                  code
                }
              }
            }
        } else if (path.startsWith("file://")) {
          val file = new File(path.replace("file://", ""), "")
          if (file.exists()) {
            val code = Files.readString(file.toPath)
            LlmToolFunction.modulesCache.put(path, code)
            code.vfuture
          } else {
            defaultCode().map { code =>
              LlmToolFunction.modulesCache.put(path, code)
              code
            }
          }
        } else if (path.startsWith("'inline module';") || path.startsWith("\"inline module\";")) {
          LlmToolFunction.modulesCache.put(path, path)
          path.vfuture
        } else if (path.startsWith("s3://")) {
          LlmToolFunction.logger.info(s"fetching from S3: ${path}")
          val config = S3Configuration.format.reads(JsObject(headers.mapValues(_.json))).get
          fileContent(path.replaceFirst("s3://", ""), config)(env.otoroshiExecutionContext, env.otoroshiMaterializer).flatMap {
            case None => {
              LlmToolFunction.logger.info(s"unable to fetch from S3: ${path}")
              defaultCode().map { code =>
                LlmToolFunction.modulesCache.put(path, code)
                code
              }
            }
            case Some((_, codeRaw)) => {
              val code = codeRaw.utf8String
              LlmToolFunction.modulesCache.put(path, code)
              code.vfuture
            }
          }.recoverWith {
            case t: Throwable => {
              LlmToolFunction.logger.error(s"error when fetch from S3: ${path}", t)
              defaultCode().map { code =>
                LlmToolFunction.modulesCache.put(path, code)
                code
              }
            }
          }
        } else {
          defaultCode().map { code =>
            LlmToolFunction.modulesCache.put(path, code)
            code
          }
        }
      }
    }
  }

  object QuickJs {
    def apply(str: String): QuickJs = {
      new QuickJs(Json.obj("jsPath" -> str), Json.obj())
    }
  }

  case class QuickJs(options: JsValue, root: JsValue) extends LlmToolFunctionBackendOptions {

    private lazy val jsPath: Option[String] = root.select("jsPath").asOpt[String].orElse(options.select("jsPath").asOpt[String]).filter(_.trim.nonEmpty)

    def json: JsValue = Json.obj(
      "jsPath" -> jsPath
    )

    def call(arguments: String, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[String] = {
      jsPath match {
        case None => "error, not wasm plugin ref".vfuture
        case Some(path) => {
          getCode(path, Map.empty, LlmToolFunctionBackendOptions.getDefaultToolCallCode).flatMap { code =>
            env.wasmIntegration.wasmVmFor(LlmToolFunction.wasmConfigRef).flatMap {
              case None => "unable to create wasm vm".vfuture
              case Some((vm, localconfig)) => {
                vm.call(
                  WasmFunctionParameters.ExtismFuntionCall(
                    "cloud_apim_module_plugin_execute_tool_call",
                    Json.obj(
                      "code" -> code,
                      "arguments" -> arguments,
                    ).stringify
                  ),
                  None
                ).map {
                  case Left(err) =>
                    err.prettify.debugPrintln
                    err.stringify
                  case Right(output) =>
                    val out = output._1.debugPrintln
                    println(s"the function output is: '${out}'")
                    out
                }.andThen {
                  case _ => vm.release()
                }
              }
            }
          }
        }
      }
    }
  }

  case class WasmPlugin(options: JsValue, root: JsValue) extends LlmToolFunctionBackendOptions {

    private lazy val wasmPlugin: Option[String] = root.select("wasmPlugin").asOpt[String].orElse(options.select("wasmPlugin").asOpt[String]).filter(_.trim.nonEmpty)

    def json: JsValue = Json.obj(
      "wasmPlugin" -> wasmPlugin
    )

    def call(arguments: String, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[String] = {
      wasmPlugin match {
        case None => "error, not wasm plugin ref".vfuture
        case Some(ref) => {
          env.proxyState.wasmPlugin(ref) match {
            case None => "error, wasm plugin not found".vfuture
            case Some(plugin) => {
              env.wasmIntegration.wasmVmFor(plugin.config).flatMap {
                case None => "unable to create wasm vm".vfuture
                case Some((vm, localconfig)) => {
                  vm.call(
                    WasmFunctionParameters.ExtismFuntionCall(
                      plugin.config.functionName.orElse(localconfig.functionName).getOrElse("tool_call"),
                      arguments
                    ),
                    None
                  ).map {
                    case Left(err) => err.stringify
                    case Right(output) => output._1
                  }.andThen {
                    case _ => vm.release()
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  case class Http(options: JsValue) extends LlmToolFunctionBackendOptions {

    def json: JsValue = options

    def call(arguments: String, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[String] = {

      def replace(str: String, params: Map[String, String]): String = {
        if (str.contains("${")) {
          GlobalExpressionLanguage.expressionReplacer.replaceOn(str) { key =>
            params.getOrElse(key, s"no-params-$key")
          }
        } else {
          str
        }
      }

      val params: Map[String, String] = Try(Json.parse(arguments).as[Map[String, String]]) match {
        case Failure(_) => Map("arguments" -> arguments)
        case Success(map) => map
      }
      val origStr = options.stringify
      val finalStr = replace(origStr, params)
      val finalOptions = Json.parse(finalStr).asObject

      val url = finalOptions.select("url").asString
      val method = finalOptions.select("method").asOpt[String].getOrElse("POST")
      // val body: Option[String] = finalOptions.select("body").asOpt[String].filter(_.nonEmpty)
      val body: Option[ByteString] = BodyHelper.extractBodyFromOpt(finalOptions)
      val headers = finalOptions.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty)
      val timeout = finalOptions.select("timeout").asOpt[Long].filter(_ > 0L).getOrElse(10.seconds.toMillis)
      val followRedirect = finalOptions.select("followRedirect").asOpt[Boolean].getOrElse(true)
      val tls_enabled = finalOptions.at("tls.enabled").asOpt[Boolean].getOrElse(false)
      val tls_loose = finalOptions.at("tls.loose").asOpt[Boolean].getOrElse(false)
      val tls_trust_all = finalOptions.at("tls.trust_all").asOpt[Boolean].getOrElse(false)
      val tls_certs = finalOptions.at("tls.certs").asOpt[Seq[String]].getOrElse(Seq.empty)
      val tls_trusted_certs = finalOptions.at("tls.trusted_certs").asOpt[Seq[String]].getOrElse(Seq.empty)

      val tlsConfig = NgTlsConfig(
        certs = tls_certs,
        trustedCerts = tls_trusted_certs,
        enabled = tls_enabled,
        loose = tls_loose,
        trustAll = tls_trust_all,
      )
      env.MtlsWs
        .url(url, tlsConfig.legacy)
        .withMethod(method)
        .withHttpHeaders(headers.toSeq: _*)
        .withRequestTimeout(timeout.millis)
        .withFollowRedirects(followRedirect)
        .applyOnWithOpt(body) {
          case (builder, body) => builder.withBody(body)
        }
        .execute()
        .map { resp =>
          finalOptions.select("response_at").asOptString.filterNot(_.isEmpty) match {
            case None => finalOptions.select("response_path").asOptString.filterNot(_.isEmpty) match {
              case None => resp.body
              case Some(path) => resp.json.atPath(path).asValue.stringify
            }
            case Some(at) => resp.json.at(at).asValue.stringify
          }
        }
        .recover {
          case t: Throwable => t.getMessage
        }
    }
  }

  case class Route(options: JsValue) extends LlmToolFunctionBackendOptions {
    def json: JsValue = options
    def call(arguments: String, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[String] = Future.apply("Route backend not supported yet")
  }

  case class Workflow(options: JsValue, root: JsValue) extends LlmToolFunctionBackendOptions {

    private lazy val workflow_id: Option[String] = root.select("workflow_id").asOpt[String].orElse(options.select("workflow_id").asOpt[String]).filter(_.trim.nonEmpty)

    def json: JsValue = Json.obj(
      "workflow_id" -> workflow_id
    )

    def call(arguments: String, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[String] = {
      workflow_id match {
        case None => "error, no workflow ref".vfuture
        case Some(ref) => {
          val extension = env.adminExtensions.extension[WorkflowAdminExtension].get
          extension.workflow(ref) match {
            case None => "error, workflow not found".vfuture
            case Some(workflow) => {
              val input: JsObject = if (arguments.startsWith("{")) {
                Json.parse(arguments).as[JsObject]
              } else {
                Json.obj("input" -> arguments)
              }
              extension.engine.run(ref, Node.from(workflow.config), input, attrs, workflow.functions).map { res =>
                res.error match {
                  case Some(error) => Json.obj("error" -> error.json).stringify
                  case None => res.returned.getOrElse(Json.obj()).stringify
                }
              }
            }
          }
        }
      }
    }
  }
}

case class LlmToolFunction(
                           location: EntityLocation = EntityLocation.default,
                           id: String,
                           name: String,
                           description: String = "",
                           tags: Seq[String] = Seq.empty,
                           metadata: Map[String, String] = Map.empty,
                           // --------------------
                           strict: Boolean = true,
                           parameters: JsObject,
                           required: Option[Seq[String]] = None,
                           // --------------------
                           backend: LlmToolFunctionBackend
                         ) extends EntityLocationSupport {
  override def internalId: String               = id
  override def json: JsValue                    = LlmToolFunction.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata

  def toolId: String = name // was id, but could be problematic when using very long ids

  //def callWasmPlugin(ref: String, arguments: String)(implicit ec: ExecutionContext, env: Env): Future[String] = {
  //  env.proxyState.wasmPlugin(ref) match {
  //    case None => "error, wasm plugin not found".vfuture
  //    case Some(plugin) => {
  //      env.wasmIntegration.wasmVmFor(plugin.config).flatMap {
  //        case None => "unable to create wasm vm".vfuture
  //        case Some((vm, localconfig)) => {
  //          vm.call(
  //            WasmFunctionParameters.ExtismFuntionCall(
  //              plugin.config.functionName.orElse(localconfig.functionName).getOrElse("tool_call"),
  //              arguments
  //            ),
  //            None
  //          ).map {
  //            case Left(err) => err.stringify
  //            case Right(output) => output._1
  //          }.andThen {
  //            case _ => vm.release()
  //          }
  //        }
  //      }
  //    }
  //  }
  //}

  //def callJsPlugin(path: String, arguments: String)(implicit ec: ExecutionContext, env: Env): Future[String] = {
  //  getCode(path, Map.empty).flatMap { code =>
  //    env.wasmIntegration.wasmVmFor(LlmToolFunction.wasmConfigRef).flatMap {
  //      case None => "unable to create wasm vm".vfuture
  //      case Some((vm, localconfig)) => {
  //        vm.call(
  //          WasmFunctionParameters.ExtismFuntionCall(
  //            "cloud_apim_module_plugin_execute_tool_call",
  //            Json.obj(
  //              "code" -> code,
  //              "arguments" -> arguments,
  //            ).stringify
  //          ),
  //          None
  //        ).map {
  //          case Left(err) =>
  //            err.prettify.debugPrintln
  //            err.stringify
  //          case Right(output) =>
  //            val out = output._1.debugPrintln
  //            println(s"the function output is: '${out}'")
  //            out
  //        }.andThen {
  //          case _ => vm.release()
  //        }
  //      }
  //    }
  //  }
  //}

  def call(arguments: String, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[String] = {
    backend.kind match {
      case LlmToolFunctionBackendKind.QuickJs => backend.options.call(arguments, attrs)
      case LlmToolFunctionBackendKind.WasmPlugin => backend.options.call(arguments, attrs)
      case LlmToolFunctionBackendKind.Route => backend.options.call(arguments, attrs)
      case LlmToolFunctionBackendKind.Workflow => backend.options.call(arguments, attrs)
      case LlmToolFunctionBackendKind.Http => backend.options.call(arguments, attrs)
    }
  }
}

case class GenericApiResponseChoiceMessageToolCallFunction(raw: JsObject) {
  lazy val raw_name: String = raw.select("name").asString
  lazy val name: String = raw_name.replaceFirst("wasm___", "").replaceFirst("mcp___", "")
  lazy val isInline: Boolean = raw_name.startsWith("wasm_____inline_")
  lazy val isWasm: Boolean = raw_name.startsWith("wasm___")
  lazy val isMcp: Boolean = raw_name.startsWith("mcp___")
  lazy val connectorId: Int = if (isMcp) raw_name.split("___")(1).toInt else 0
  lazy val connectorFunctionName: String = if (isMcp) raw_name.split("___")(2) else name
  lazy val arguments: String = {
    raw.select("arguments").asValue match {
      case JsString(str) => str
      case obj @ JsObject(_) => obj.stringify
      case v => v.toString()
    }
  }
}

case class GenericApiResponseChoiceMessageToolCall(raw: JsObject) {
  lazy val id: String = raw.select("id").asOpt[String].getOrElse(raw.select("function").select("name").asString)
  lazy val function: GenericApiResponseChoiceMessageToolCallFunction = GenericApiResponseChoiceMessageToolCallFunction(raw.select("function").asObject)
  lazy val isInline: Boolean = function.isInline
  lazy val isWasm: Boolean = function.isWasm
  lazy val isMcp: Boolean = function.isMcp
}

case class AnthropicApiResponseChoiceMessageToolCall(raw: JsObject) {
  lazy val id: String = raw.select("id").asOpt[String].getOrElse(raw.select("function").select("name").asString)
  lazy val raw_name: String = raw.select("name").asString
  lazy val name: String = raw_name.replaceFirst("wasm___", "").replaceFirst("mcp___", "")
  lazy val isInline: Boolean = raw_name.startsWith("wasm_____inline_")
  lazy val isWasm: Boolean = raw_name.startsWith("wasm___")
  lazy val isMcp: Boolean = raw_name.startsWith("mcp___")
  lazy val connectorId: Int = if (isMcp) raw_name.split("___")(1).toInt else 0
  lazy val connectorFunctionName: String = if (isMcp) raw_name.split("___")(2) else name
  lazy val input: JsObject = raw.select("input").asObject
  lazy val arguments: String = {
    raw.select("input").asValue match {
      case JsString(str) => str
      case obj @ JsObject(_) => obj.stringify
      case v => v.toString()
    }
  }
}

object LlmToolFunction {
  val wasmPluginId = "wasm-plugin_cloud_apim_llm_extension_tool_call_runtime"
  val wasmConfigRef = WasmConfig(source = WasmSource(WasmSourceKind.Local, wasmPluginId, Json.obj()))
  val wasmConfig = WasmConfig(
    source = WasmSource(WasmSourceKind.ClassPath, "wasm/otoroshi-llm-extension-tool-function-runtime.wasm", Json.obj()),
    memoryPages = 200,
    wasi = true,
    allowedHosts = Seq("*"),
    authorizations = WasmAuthorizations().copy(httpAccess = true),
    instances = 4
  )

  val modulesCache = Scaffeine().maximumSize(1000).expireAfterWrite(120.seconds).build[String, String]
  val logger = Logger("LlmToolFunction")

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  def _tools(functions: Seq[String])(implicit env: Env): Seq[JsObject] = {
    /*Json.obj(
      "tools" -> JsArray(*/functions.flatMap(id => env.adminExtensions.extension[AiExtension].flatMap(ext => ext.states.toolFunction(id))).map { function =>
      val required: JsArray = function.required.map(v => JsArray(v.map(_.json))).getOrElse(JsArray(function.parameters.value.keySet.toSeq.map(_.json)))
      Json.obj(
        "type" -> "function",
        "function" -> Json.obj(
          "name" -> s"wasm___${function.toolId}",
          "description" -> function.description,
          "strict" -> function.strict,
          "parameters" -> Json.obj(
            "type" -> "object",
            "required" -> required,
            "additionalProperties" -> false,
            "properties" -> function.parameters
          )
        )
      )
    }/*)
    )*/
  }

  def _inlineTools(functions: Seq[String], attrs: TypedMap)(implicit env: Env): Seq[JsObject] = {
    attrs.get(InlineFunctions.InlineFunctionsKey) match {
      case None => Seq.empty
      case Some(inlineFunctions) => functions.flatMap(key => inlineFunctions.get(key)).map { function =>
        val required: JsArray = JsArray(function.declaration.required.map(_.json))
        Json.obj(
          "type" -> "function",
          "function" -> Json.obj(
            "name" -> s"wasm_____inline_${function.declaration.name}",
            "description" -> function.declaration.description,
            "strict" -> function.declaration.strict,
            "parameters" -> Json.obj(
              "type" -> "object",
              "required" -> required,
              "additionalProperties" -> false,
              "properties" -> function.declaration.parameters
            )
          )
        )
      }
    }
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  def toolsCohere(functions: Seq[String])(implicit env: Env): (Seq[JsObject], Map[String, String]) = {
    val map = new TrieMap[String, String]()
    (functions.flatMap(id => env.adminExtensions.extension[AiExtension].flatMap(ext => ext.states.toolFunction(id))).map { function =>
      val required: JsArray = function.required.map(v => JsArray(v.map(_.json))).getOrElse(JsArray(function.parameters.value.keySet.toSeq.map(_.json)))
      val fname = ("wasm___" + s"${function.toolId}".sha256)
      map.put(s"${function.toolId}".sha256, s"${function.toolId}")
      Json.obj(
        "type" -> "function",
        "function" -> Json.obj(
          "name" -> fname, //function.name,
          "description" -> function.description,
          "strict" -> function.strict,
          "parameters" -> Json.obj(
            "type" -> "object",
            "required" -> required,
            "additionalProperties" -> false,
            "properties" -> function.parameters
          )
        )
      )
    }, map.toMap)
  }

  def inlineToolsCohere(functions: Seq[String], attrs: TypedMap)(implicit env: Env): (Seq[JsObject], Map[String, String]) = {
    val map = new TrieMap[String, String]()
    (attrs.get(InlineFunctions.InlineFunctionsKey) match {
      case None => Seq.empty
      case Some(inlineFunctions) => functions.flatMap(key => inlineFunctions.get(key)).map { function =>
        val required: JsArray = JsArray(function.declaration.required.map(_.json))
        val fname = ("wasm___" + s"${function.declaration.name}".sha256)
        map.put(s"${function.declaration.name}".sha256, s"${function.declaration.name}")
        Json.obj(
          "type" -> "function",
          "function" -> Json.obj(
            "name" -> fname, //function.name,
            "description" -> function.declaration.description,
            "strict" -> function.declaration.strict,
            "parameters" -> Json.obj(
              "type" -> "object",
              "required" -> required,
              "additionalProperties" -> false,
              "properties" -> function.declaration.parameters
            )
          )
        )
      }
    }, map.toMap)
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  def _toolsAnthropic(functions: Seq[String])(implicit env: Env): Seq[JsObject] = {
    functions.flatMap(id => env.adminExtensions.extension[AiExtension].flatMap(ext => ext.states.toolFunction(id))).map { function =>
      val required: JsArray = function.required.map(v => JsArray(v.map(_.json))).getOrElse(JsArray(function.parameters.value.keySet.toSeq.map(_.json)))
      Json.obj(
        "name" -> s"wasm___${function.toolId}",
        "description" -> function.description,
        "input_schema" -> Json.obj(
          "type" -> "object",
          "required" -> required,
          "additionalProperties" -> false,
          "properties" -> function.parameters
        )
      )
    }
  }

  def _inlineToolsAnthropic(functions: Seq[String], attrs: TypedMap)(implicit env: Env): Seq[JsObject] = {
    attrs.get(InlineFunctions.InlineFunctionsKey) match {
      case None => Seq.empty
      case Some(inlineFunctions) => functions.flatMap(key => inlineFunctions.get(key)).map { function =>
        val required: JsArray = JsArray(function.declaration.required.map(_.json))
        Json.obj(
          "name" -> s"wasm___${function.declaration.name}", //function.name,
          "description" -> function.declaration.description,
          "input_schema" -> Json.obj(
            "type" -> "object",
            "required" -> required,
            "additionalProperties" -> false,
            "properties" -> function.declaration.parameters
          )
        )
      }
    }
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  private def callInline(functions: Seq[GenericApiResponseChoiceMessageToolCall], attrs: TypedMap)(f: (String, GenericApiResponseChoiceMessageToolCall) => Source[JsValue, _])(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    Source(functions.toList)
      .mapAsync(1) { toolCall =>
        val fid = toolCall.function.name.stripPrefix("wasm___")
        attrs.get(InlineFunctions.InlineFunctionsKey).flatMap(_.get(fid)) match {
          case None => (s"undefined function ${fid}", toolCall).some.vfuture
          case Some(function) => {
            println(s"calling function '${function.declaration.name}' with args: '${toolCall.function.arguments}'")
            function.call(toolCall.function.arguments, attrs, env, ec).map { r =>
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

  private def call(functions: Seq[GenericApiResponseChoiceMessageToolCall], attrs: TypedMap)(f: (String, GenericApiResponseChoiceMessageToolCall) => Source[JsValue, _])(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    Source(functions.toList)
      .mapAsync(1) { toolCall =>
        val fid = toolCall.function.name
        val ext = env.adminExtensions.extension[AiExtension].get
        ext.states.toolFunction(fid) match {
          case None => (s"undefined function ${fid}", toolCall).some.vfuture
          case Some(function) => {
            println(s"calling function '${function.name}' with args: '${toolCall.function.arguments}'")
            function.call(toolCall.function.arguments, attrs).map { r =>
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

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  private def callCohere(functions: Seq[GenericApiResponseChoiceMessageToolCall], fmap: Map[String, String], attrs: TypedMap)(f: (String, GenericApiResponseChoiceMessageToolCall) => Source[JsValue, _])(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    Source(functions.toList)
      .mapAsync(1) { toolCall =>
        val fid = toolCall.function.name
        val ext = env.adminExtensions.extension[AiExtension].get
        ext.states.toolFunction(fmap(fid)) match {
          case None => (s"undefined function ${fid}", toolCall).some.vfuture
          case Some(function) => {
            println(s"calling function '${function.name}' with args: '${toolCall.function.arguments}'")
            function.call(toolCall.function.arguments, attrs).map { r =>
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

  private def callInlineCohere(functions: Seq[GenericApiResponseChoiceMessageToolCall], fmap: Map[String, String], attrs: TypedMap)(f: (String, GenericApiResponseChoiceMessageToolCall) => Source[JsValue, _])(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    Source(functions.toList)
      .mapAsync(1) { toolCall =>
        val fid = toolCall.function.name.stripPrefix("wasm___")
        attrs.get(InlineFunctions.InlineFunctionsKey).flatMap(_.get(fid)) match {
          case None => (s"undefined function ${fid}", toolCall).some.vfuture
          case Some(function) => {
            println(s"calling function '${function.declaration.name}' with args: '${toolCall.function.arguments}'")
            function.call(toolCall.function.arguments, attrs, env, ec).map { r =>
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

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  private def callAnthropic(functions: Seq[AnthropicApiResponseChoiceMessageToolCall], attrs: TypedMap)(f: (String, AnthropicApiResponseChoiceMessageToolCall) => Source[JsValue, _])(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    Source(functions.toList)
      .mapAsync(1) { toolCall =>
        val fid = toolCall.name
        val ext = env.adminExtensions.extension[AiExtension].get
        ext.states.toolFunction(fid) match {
          case None => (s"undefined function ${fid}", toolCall).some.vfuture
          case Some(function) => {
            val args = toolCall.arguments
            println(s"calling function '${function.name}' with args: '${args}'")
            function.call(args, attrs).map { r =>
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

  private def callInlineAnthropic(functions: Seq[AnthropicApiResponseChoiceMessageToolCall], attrs: TypedMap)(f: (String, AnthropicApiResponseChoiceMessageToolCall) => Source[JsValue, _])(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    Source(functions.toList)
      .mapAsync(1) { toolCall =>
        val fid = toolCall.name.stripPrefix("wasm___")
        attrs.get(InlineFunctions.InlineFunctionsKey).flatMap(_.get(fid)) match {
          case None => (s"undefined function ${fid}", toolCall).some.vfuture
          case Some(function) => {
            println(s"calling function '${function.declaration.name}' with args: '${toolCall.arguments}'")
            function.call(toolCall.arguments, attrs, env, ec).map { r =>
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

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  def _callInlineToolsOpenai(functions: Seq[GenericApiResponseChoiceMessageToolCall], providerName: String, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    callInline(functions, attrs) { (resp, tc) =>
      Source(List(
        Json.obj("role" -> "assistant", "tool_calls" -> Json.arr(tc.raw)),
        Json.obj(
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

  def _callToolsOpenai(functions: Seq[GenericApiResponseChoiceMessageToolCall], providerName: String, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    call(functions, attrs) { (resp, tc) =>
      Source(List(
        Json.obj("role" -> "assistant", "tool_calls" -> Json.arr(tc.raw)),
        Json.obj(
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

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  def callToolsCohere(functions: Seq[GenericApiResponseChoiceMessageToolCall], providerName: String, fmap: Map[String, String], attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    callCohere(functions, fmap, attrs) { (resp, tc) =>
      Source(List(
        Json.obj("role" -> "assistant", "tool_calls" -> Json.arr(tc.raw)),
        Json.obj(
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

  def callInlineToolsCohere(functions: Seq[GenericApiResponseChoiceMessageToolCall], providerName: String, fmap: Map[String, String], attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    callInlineCohere(functions, fmap, attrs) { (resp, tc) =>
      Source(List(
        Json.obj("role" -> "assistant", "tool_calls" -> Json.arr(tc.raw)),
        Json.obj(
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

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  def _callToolsAnthropic(functions: Seq[AnthropicApiResponseChoiceMessageToolCall], providerName: String, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    callAnthropic(functions, attrs) { (resp, tc) =>
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

  def _callInlineToolsAnthropic(functions: Seq[AnthropicApiResponseChoiceMessageToolCall], providerName: String, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    callInlineAnthropic(functions, attrs) { (resp, tc) =>
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

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  def _callToolsOllama(functions: Seq[GenericApiResponseChoiceMessageToolCall], attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    call(functions, attrs) { (resp, tc) =>
      Source(List(
        Json.obj("role" -> "assistant", "content" -> "", "tool_calls" -> Json.arr(tc.raw)),
        Json.obj(
        "role" -> "tool",
        "content" -> resp,
      )))
    }
  }

  def _callInlineToolsOllama(functions: Seq[GenericApiResponseChoiceMessageToolCall], attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    callInline(functions, attrs) { (resp, tc) =>
      Source(List(
        Json.obj("role" -> "assistant", "content" -> "", "tool_calls" -> Json.arr(tc.raw)),
        Json.obj(
          "role" -> "tool",
          "content" -> resp,
        )))
    }
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  val format = new Format[LlmToolFunction] {
    override def writes(o: LlmToolFunction): JsValue = o.location.jsonWithKey ++ Json.obj(
      "id"          -> o.id,
      "name"        -> o.name,
      "description" -> o.description,
      "metadata"    -> o.metadata,
      "tags"        -> JsArray(o.tags.map(JsString.apply)),
      "strict" -> o.strict,
      "parameters" -> o.parameters,
      "required" -> o.required.map(v => JsArray(v.map(_.json))).getOrElse(JsNull).asValue,
      "backend" -> o.backend.json
    )
    override def reads(json: JsValue): JsResult[LlmToolFunction] = Try {
      val kind = LlmToolFunctionBackendKind(json.select("backend").select("kind").asOpt[String].getOrElse("QuickJs"))
      val options = json.select("backend").select("options").asOpt[JsObject].getOrElse(Json.obj())
      LlmToolFunction(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = (json \ "id").as[String],
        name = (json \ "name").as[String],
        description = (json \ "description").asOpt[String].getOrElse(""),
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        strict = json.select("strict").asOpt[Boolean].getOrElse(true),
        parameters = json.select("parameters").asOpt[JsObject].getOrElse(Json.obj()),
        required = json.select("required").asOpt[Seq[String]],
        backend = LlmToolFunctionBackend(
          kind = kind,
          options = kind match {
            case LlmToolFunctionBackendKind.WasmPlugin => LlmToolFunctionBackendOptions.WasmPlugin(options, json)
            case LlmToolFunctionBackendKind.QuickJs => LlmToolFunctionBackendOptions.QuickJs(options, json)
            case LlmToolFunctionBackendKind.Http => LlmToolFunctionBackendOptions.Http(options)
            case LlmToolFunctionBackendKind.Route => LlmToolFunctionBackendOptions.Route(options)
            case LlmToolFunctionBackendKind.Workflow => LlmToolFunctionBackendOptions.Workflow(options, json)
          }
        )
      )
    } match {
      case Failure(ex)    =>
        ex.printStackTrace()
        JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
  def resource(env: Env, datastores: AiGatewayExtensionDatastores, states: AiGatewayExtensionState): Resource = {
    Resource(
      "ToolFunction",
      "tool-functions",
      "tool-function",
      "ai-gateway.extensions.cloud-apim.com",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[LlmToolFunction](
        format = LlmToolFunction.format,
        clazz = classOf[LlmToolFunction],
        keyf = id => datastores.toolFunctionDataStore.key(id),
        extractIdf = c => datastores.toolFunctionDataStore.extractId(c),
        extractIdJsonf = json => json.select("id").asString,
        idFieldNamef = () => "id",
        tmpl = (v, p, ctx) => {
          LlmToolFunction(
            id = IdGenerator.namedId("tool-function", env),
            name = "tool function",
            description = "A new tool function",
            metadata = Map.empty,
            tags = Seq.empty,
            location = EntityLocation.default,
            strict = true,
            parameters = Json.obj(),
            required = None,
            backend = LlmToolFunctionBackend(
              kind = LlmToolFunctionBackendKind.QuickJs,
              options = LlmToolFunctionBackendOptions.QuickJs(Json.obj(), Json.obj())
            )
          ).json
        },
        canRead = true,
        canCreate = true,
        canUpdate = true,
        canDelete = true,
        canBulk = true,
        stateAll = () => states.allToolFunctions(),
        stateOne = id => states.toolFunction(id),
        stateUpdate = values => states.updateToolFunctions(values)
      )
    )
  }
}

trait LlmToolFunctionDataStore extends BasicStore[LlmToolFunction]

class KvLlmToolFunctionDataStore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
  extends LlmToolFunctionDataStore
    with RedisLikeStore[LlmToolFunction] {
  override def fmt: Format[LlmToolFunction]                  = LlmToolFunction.format
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def key(id: String): String                 = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:toolfunctions:$id"
  override def extractId(value: LlmToolFunction): String    = value.id
}
