package com.cloud.apim.otoroshi.extensions.aigateway.guardrails

import com.cloud.apim.otoroshi.extensions.aigateway.decorators.{Guardrail, GuardrailResult}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.LlmToolFunctionBackendOptions.getCode
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiProvider, LlmToolFunction}
import com.cloud.apim.otoroshi.extensions.aigateway._
import io.otoroshi.wasm4s.scaladsl.{ResultsWrapper, WasmFunctionParameters}
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsArray, JsNull, JsObject, JsValue, Json}

import scala.concurrent.{ExecutionContext, Future}

object WasmGuardrail {
  def handleCallResult(from: String, res: Either[JsValue, (String, ResultsWrapper)])(implicit ec: ExecutionContext, env: Env): GuardrailResult = res match {
    case Left(err) =>
      err.prettify
      GuardrailResult.GuardrailError(err.stringify)
    case Right(output) => {
      val out = output._1
      println(s"the guardrail ${from} function output is: '${out}'")
      out match {
        case "pass" => GuardrailResult.GuardrailPass
        case "true" => GuardrailResult.GuardrailPass
        case "false" => GuardrailResult.GuardrailDenied("messages blocked with no reason")
        case "deny" => GuardrailResult.GuardrailDenied("messages blocked with no reason")
        case json_str if json_str.startsWith("{") && json_str.endsWith("}") => {
          val json = json_str.parseJson
          val error = json.select("error").asOpt[String]
          if (error.isDefined) {
            GuardrailResult.GuardrailError(error.get)
          } else {
            if (json.select("pass").asOpt[Boolean].getOrElse(false)) {
              GuardrailResult.GuardrailPass
            } else {
              val reason = json.select("reason").asOpt[String].getOrElse("messages blocked with no specified reason")
              GuardrailResult.GuardrailDenied(reason)
            }
          }
        }
        case _ => GuardrailResult.GuardrailDenied("messages blocked with no reason")
      }
    }
  }
}

class WasmGuardrail extends Guardrail {

  override def isBefore: Boolean = true

  override def isAfter: Boolean = true

  override def manyMessages: Boolean = true

  override def pass(messages: Seq[ChatMessage], config: JsObject, provider: Option[AiProvider], chatClient: Option[ChatClient], attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[GuardrailResult] = {
    val wasmPlugin: Option[String] = config.select("wasmPlugin").asOpt[String].orElse(config.select("plugin_ref").asOpt[String]).filter(_.trim.nonEmpty)
    wasmPlugin match {
      case None => GuardrailResult.GuardrailError("error, not wasm plugin ref").vfuture
      case Some(ref) => {
        env.proxyState.wasmPlugin(ref) match {
          case None => GuardrailResult.GuardrailError("error, wasm plugin not found").vfuture
          case Some(plugin) => {
            env.wasmIntegration.wasmVmFor(plugin.config).flatMap {
              case None => GuardrailResult.GuardrailError("unable to create wasm vm").vfuture
              case Some((vm, localconfig)) => {
                val arr = messages.map {
                  case i: InputChatMessage => i.json(ChatMessageContentFlavor.Common)
                  case o: OutputChatMessage => o.json
                }
                vm.call(
                  WasmFunctionParameters.ExtismFuntionCall(
                    plugin.config.functionName.orElse(localconfig.functionName).getOrElse("guardrail_call"),
                    Json.obj(
                      "config" -> config,
                      "provider" -> provider.map(_.json).getOrElse(JsNull).asValue,
                      "attrs" -> attrs.json,
                      "messages" -> JsArray(arr),
                    ).stringify
                  ),
                  None
                ).map(e => WasmGuardrail.handleCallResult("wasm plugin", e)).andThen {
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

class QuickJsGuardrail extends Guardrail {

  override def isBefore: Boolean = true

  override def isAfter: Boolean = true

  override def manyMessages: Boolean = true

  def getDefaultGuardrailCallCode(): Future[String] = {
    s"""'inline module';
       |
       |exports.guardrail_call = function(args) {
       |  const { messages } = JSON.parse(args);
       |  return JSON.stringify({
       |    pass: true,
       |    reason: "none"
       |  });
       |};
       |""".stripMargin.vfuture
  }

  override def pass(messages: Seq[ChatMessage], config: JsObject, provider: Option[AiProvider], chatClient: Option[ChatClient], attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[GuardrailResult] = {
    val jsPath: Option[String] = config.select("jsPath").asOpt[String].orElse(config.select("quickjs_path").asOpt[String]).filter(_.trim.nonEmpty)
    jsPath match {
      case None => GuardrailResult.GuardrailError("error, not wasm plugin ref").vfuture
      case Some(path) => {
        getCode(path, Map.empty, getDefaultGuardrailCallCode).flatMap { code =>
          env.wasmIntegration.wasmVmFor(LlmToolFunction.wasmConfigRef).flatMap {
            case None =>  GuardrailResult.GuardrailError("unable to create wasm vm").vfuture
            case Some((vm, localconfig)) => {
              val arr = messages.map {
                case i: InputChatMessage => i.json(ChatMessageContentFlavor.Common)
                case o: OutputChatMessage => o.json
              }
              vm.call(
                WasmFunctionParameters.ExtismFuntionCall(
                  "cloud_apim_module_plugin_execute_guardrail_call",
                  Json.obj(
                    "code" -> code,
                    "arguments" -> Json.obj(
                      "config" -> config,
                      "provider" -> provider.map(_.json).getOrElse(JsNull).asValue,
                      "attrs" -> attrs.json,
                      "messages" -> JsArray(arr),
                    ),
                  ).stringify
                ),
                None
              ).map(e => WasmGuardrail.handleCallResult("quickjs", e)).andThen {
                case _ => vm.release()
              }
            }
          }
        }
      }
    }

  }
}