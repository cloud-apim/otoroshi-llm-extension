package com.cloud.apim.otoroshi.extensions.aigateway.entities

import otoroshi.env.Env
import play.api.libs.json.{JsObject, JsValue, Json}

import scala.concurrent.{ExecutionContext, Future}

object LlmFunctions {

  def callToolsOpenai(functions: Seq[GenericApiResponseChoiceMessageToolCall], conns: Seq[String], providerName: String)(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    val (wasmFunctions, mcpConnectors) = functions.partition(_.isWasm)
    val wasmFunctionsF = WasmFunction._callToolsOpenai(wasmFunctions, providerName)(ec, env)
    val mcpConnectorsF = McpSupport.callToolsOpenai(mcpConnectors, conns, providerName)(ec, env)
    for {
      wasmFunctionsR <- wasmFunctionsF
      mcpConnectorsR <- mcpConnectorsF
    } yield wasmFunctionsR ++ mcpConnectorsR
  }

  def callToolsOllama(functions: Seq[GenericApiResponseChoiceMessageToolCall], conns: Seq[String])(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    val (wasmFunctions, mcpConnectors) = functions.partition(_.isWasm)
    val wasmFunctionsF = WasmFunction._callToolsOllama(wasmFunctions)(ec, env)
    val mcpConnectorsF = McpSupport.callToolsOllama(mcpConnectors, conns)(ec, env)
    for {
      wasmFunctionsR <- wasmFunctionsF
      mcpConnectorsR <- mcpConnectorsF
    } yield wasmFunctionsR ++ mcpConnectorsR
  }

  def tools(wasmFunctions: Seq[String], mcpConnectors: Seq[String])(implicit env: Env): JsObject = {
    val tools: Seq[JsObject] = WasmFunction._tools(wasmFunctions) ++ McpSupport.tools(mcpConnectors)
    Json.obj(
      "tools" -> tools
    )
  }
}
