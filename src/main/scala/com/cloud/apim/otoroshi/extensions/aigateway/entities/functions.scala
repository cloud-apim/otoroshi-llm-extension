package com.cloud.apim.otoroshi.extensions.aigateway.entities

import otoroshi.env.Env
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.McpTester
import play.api.libs.json.{JsObject, JsValue, Json}

import scala.concurrent.{ExecutionContext, Future}

object LlmFunctions {

  def callToolsOpenai(functions: Seq[GenericApiResponseChoiceMessageToolCall])(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    val (wasmFunctions, mcpConnectors) = functions.partition(_.isWasm)
    val wasmFunctionsF = WasmFunction._callToolsOpenai(wasmFunctions)(ec, env)
    val mcpConnectorsF = McpTester.callToolsOpenai(mcpConnectors)(ec, env)
    for {
      wasmFunctionsR <- wasmFunctionsF
      mcpConnectorsR <- mcpConnectorsF
    } yield wasmFunctionsR ++ mcpConnectorsR
  }

  def callToolsOllama(functions: Seq[GenericApiResponseChoiceMessageToolCall])(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    val (wasmFunctions, mcpConnectors) = functions.partition(_.isWasm)
    val wasmFunctionsF = WasmFunction._callToolsOllama(wasmFunctions)(ec, env)
    val mcpConnectorsF = McpTester.callToolsOllama(mcpConnectors)(ec, env)
    for {
      wasmFunctionsR <- wasmFunctionsF
      mcpConnectorsR <- mcpConnectorsF
    } yield wasmFunctionsR ++ mcpConnectorsR
  }

  def tools(wasmFunctions: Seq[String], mcpConnectors: Seq[String])(implicit env: Env): JsObject = {
    val tools: Seq[JsObject] = WasmFunction._tools(wasmFunctions) ++ McpTester.tools(mcpConnectors)
    Json.obj(
      "tools" -> tools
    )
  }
}
