package com.cloud.apim.otoroshi.extensions.aigateway.entities

import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.{Attributes, Materializer}
import akka.stream.alpakka.s3.{ApiVersion, MemoryBufferType, ObjectMetadata, S3Attributes, S3Settings}
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.providers.OpenAiApiResponseChoiceMessageToolCall
import com.github.blemale.scaffeine.Scaffeine
import io.otoroshi.wasm4s.scaladsl.{WasmFunctionParameters, WasmSource, WasmSourceKind}
import otoroshi.api._
import otoroshi.env.Env
import otoroshi.models._
import otoroshi.next.extensions.AdminExtensionId
import otoroshi.security.IdGenerator
import otoroshi.storage._
import otoroshi.storage.drivers.inmemory.S3Configuration
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
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class WasmFunction(
                           location: EntityLocation,
                           id: String,
                           name: String,
                           description: String,
                           tags: Seq[String],
                           metadata: Map[String, String],
                           strict: Boolean,
                           parameters: JsObject,
                           required: Option[Seq[String]],
                           wasmPlugin: Option[String],
                           jsPath: Option[String],
                         ) extends EntityLocationSupport {
  override def internalId: String               = id
  override def json: JsValue                    = WasmFunction.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata

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

  private def getDefaultCode()(implicit env: Env, ec: ExecutionContext): Future[String] = {
    s"""'inline module';
       |exports.tool_call = function(args_str) {
       |  const args = JSON.parse(args_str);
       |  return "nothing";
       |};
       |""".stripMargin.vfuture
  }

  private def getCode(path: String, headers: Map[String, String])(implicit env: Env, ec: ExecutionContext): Future[String] = {
    import WasmFunction.modulesCache
    import WasmFunction.logger

    modulesCache.getIfPresent(path) match {
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
                modulesCache.put(path, response.body)
                response.body.vfuture
              } else {
                getDefaultCode().map { code =>
                  modulesCache.put(path, code)
                  code
                }
              }
            }
        } else if (path.startsWith("file://")) {
          val file = new File(path.replace("file://", ""), "")
          if (file.exists()) {
            val code = Files.readString(file.toPath)
            modulesCache.put(path, code)
            code.vfuture
          } else {
            getDefaultCode().map { code =>
              modulesCache.put(path, code)
              code
            }
          }
        } else if (path.startsWith("'inline module';") || path.startsWith("\"inline module\";")) {
          modulesCache.put(path, path)
          path.vfuture
        } else if (path.startsWith("s3://")) {
          logger.info(s"fetching from S3: ${path}")
          val config = S3Configuration.format.reads(JsObject(headers.mapValues(_.json))).get
          fileContent(path.replaceFirst("s3://", ""), config)(env.otoroshiExecutionContext, env.otoroshiMaterializer).flatMap {
            case None => {
              logger.info(s"unable to fetch from S3: ${path}")
              getDefaultCode().map { code =>
                modulesCache.put(path, code)
                code
              }
            }
            case Some((_, codeRaw)) => {
              val code = codeRaw.utf8String
              modulesCache.put(path, code)
              code.vfuture
            }
          }.recoverWith {
            case t: Throwable => {
              logger.error(s"error when fetch from S3: ${path}", t)
              getDefaultCode().map { code =>
                modulesCache.put(path, code)
                code
              }
            }
          }
        } else {
          getDefaultCode().map { code =>
            modulesCache.put(path, code)
            code
          }
        }
      }
    }
  }


  def callWasmPlugin(ref: String, arguments: String)(implicit ec: ExecutionContext, env: Env): Future[String] = {
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

  def callJsPlugin(path: String, arguments: String)(implicit ec: ExecutionContext, env: Env): Future[String] = {
    getCode(path, Map.empty).flatMap { code =>
      env.wasmIntegration.wasmVmFor(WasmFunction.wasmConfigRef).flatMap {
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

  def call(arguments: String)(implicit ec: ExecutionContext, env: Env): Future[String] = {
    (wasmPlugin, jsPath) match {
      case (Some(ref), None) => callWasmPlugin(ref, arguments)
      case (None, Some(path)) => callJsPlugin(path, arguments)
      case (Some(ref), Some(_)) => callWasmPlugin(ref, arguments)
      case (None, None) => "error, nothing to call".vfuture
    }
  }
}

case class GenericApiResponseChoiceMessageToolCallFunction(raw: JsObject) {
  lazy val raw_name: String = raw.select("name").asString
  lazy val name: String = raw_name.replaceFirst("wasm___", "").replaceFirst("mcp___", "")
  lazy val isWasm: Boolean = raw_name.startsWith("wasm___")
  lazy val isMcp: Boolean = raw_name.startsWith("mcp___")
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
  lazy val isWasm: Boolean = function.isWasm
  lazy val isMcp: Boolean = function.isMcp
}

object WasmFunction {
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

  private val modulesCache = Scaffeine().maximumSize(1000).expireAfterWrite(120.seconds).build[String, String]
  val logger = Logger("WasmFunction")

  def _tools(functions: Seq[String])(implicit env: Env): Seq[JsObject] = {
    /*Json.obj(
      "tools" -> JsArray(*/functions.flatMap(id => env.adminExtensions.extension[AiExtension].flatMap(ext => ext.states.toolFunction(id))).map { function =>
        val required: JsArray = function.required.map(v => JsArray(v.map(_.json))).getOrElse(JsArray(function.parameters.value.keySet.toSeq.map(_.json)))
        Json.obj(
          "type" -> "function",
          "function" -> Json.obj(
            "name" -> s"wasm___${function.id}", //function.name,
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

  private def call(functions: Seq[GenericApiResponseChoiceMessageToolCall])(f: (String, GenericApiResponseChoiceMessageToolCall) => Source[JsValue, _])(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    Source(functions.toList)
      .mapAsync(1) { toolCall =>
        val fid = toolCall.function.name
        val ext = env.adminExtensions.extension[AiExtension].get
        ext.states.toolFunction(fid) match {
          case None => (s"undefined function ${fid}", toolCall).some.vfuture
          case Some(function) => {
            println(s"calling function '${function.name}' with args: '${toolCall.function.arguments}'")
            function.call(toolCall.function.arguments).map { r =>
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

  def _callToolsOpenai(functions: Seq[GenericApiResponseChoiceMessageToolCall])(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    call(functions) { (resp, tc) =>
      Source(List(Json.obj("role" -> "assistant", "tool_calls" -> Json.arr(tc.raw)), Json.obj(
        "role" -> "tool",
        "content" -> resp,
        "tool_call_id" -> tc.id
      )))
    }
  }

  def _callToolsOllama(functions: Seq[GenericApiResponseChoiceMessageToolCall])(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    call(functions) { (resp, tc) =>
      Source(List(Json.obj("role" -> "assistant", "content" -> "", "tool_calls" -> Json.arr(tc.raw)), Json.obj(
        "role" -> "tool",
        "content" -> resp,
      )))
    }
  }

  val format = new Format[WasmFunction] {
    override def writes(o: WasmFunction): JsValue = o.location.jsonWithKey ++ Json.obj(
      "id"          -> o.id,
      "name"        -> o.name,
      "description" -> o.description,
      "metadata"    -> o.metadata,
      "tags"        -> JsArray(o.tags.map(JsString.apply)),
      "strict" -> o.strict,
      "parameters" -> o.parameters,
      "required" -> o.required.map(v => JsArray(v.map(_.json))).getOrElse(JsNull).asValue,
      "wasmPlugin" -> o.wasmPlugin.map(_.json).getOrElse(JsNull).asValue,
      "jsPath" -> o.jsPath.map(_.json).getOrElse(JsNull).asValue,
    )
    override def reads(json: JsValue): JsResult[WasmFunction] = Try {
      WasmFunction(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = (json \ "id").as[String],
        name = (json \ "name").as[String],
        description = (json \ "description").as[String],
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        strict = json.select("strict").asOpt[Boolean].getOrElse(true),
        parameters = json.select("parameters").asOpt[JsObject].getOrElse(Json.obj()),
        required = json.select("required").asOpt[Seq[String]],
        wasmPlugin = json.select("wasmPlugin").asOpt[String].filter(_.trim.nonEmpty),
        jsPath = json.select("jsPath").asOpt[String].filter(_.trim.nonEmpty),
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
      "tool-functions",
      "ai-gateway.extensions.cloud-apim.com",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[WasmFunction](
        format = WasmFunction.format,
        clazz = classOf[WasmFunction],
        keyf = id => datastores.toolFunctionDataStore.key(id),
        extractIdf = c => datastores.toolFunctionDataStore.extractId(c),
        extractIdJsonf = json => json.select("id").asString,
        idFieldNamef = () => "id",
        tmpl = (v, p) => {
          WasmFunction(
            id = IdGenerator.namedId("tool-function", env),
            name = "tool function",
            description = "A new tool function",
            metadata = Map.empty,
            tags = Seq.empty,
            location = EntityLocation.default,
            strict = true,
            parameters = Json.obj(),
            required = None,
            wasmPlugin = None,
            jsPath = None
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

trait WasmFunctionDataStore extends BasicStore[WasmFunction]

class KvWasmFunctionDataStore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
  extends WasmFunctionDataStore
    with RedisLikeStore[WasmFunction] {
  override def fmt: Format[WasmFunction]                  = WasmFunction.format
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def key(id: String): String                 = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:toolfunctions:$id"
  override def extractId(value: WasmFunction): String    = value.id
}
