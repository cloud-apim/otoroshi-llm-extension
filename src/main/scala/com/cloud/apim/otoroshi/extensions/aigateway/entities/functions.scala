package com.cloud.apim.otoroshi.extensions.aigateway.entities

import otoroshi.env.Env
import play.api.libs.json.{JsObject, JsValue, Json}

import scala.concurrent.{ExecutionContext, Future}

object LlmFunctions {

  def callToolsOpenai(functions: Seq[GenericApiResponseChoiceMessageToolCall], conns: Seq[String], providerName: String)(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    val (wasmFunctions, mcpConnectors) = functions.partition(_.isWasm)
    val wasmFunctionsF = LlmToolFunction._callToolsOpenai(wasmFunctions, providerName)(ec, env)
    val mcpConnectorsF = McpSupport.callToolsOpenai(mcpConnectors, conns, providerName)(ec, env)
    for {
      wasmFunctionsR <- wasmFunctionsF
      mcpConnectorsR <- mcpConnectorsF
    } yield wasmFunctionsR ++ mcpConnectorsR
  }

  def callToolsOllama(functions: Seq[GenericApiResponseChoiceMessageToolCall], conns: Seq[String])(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    val (wasmFunctions, mcpConnectors) = functions.partition(_.isWasm)
    val wasmFunctionsF = LlmToolFunction._callToolsOllama(wasmFunctions)(ec, env)
    val mcpConnectorsF = McpSupport.callToolsOllama(mcpConnectors, conns)(ec, env)
    for {
      wasmFunctionsR <- wasmFunctionsF
      mcpConnectorsR <- mcpConnectorsF
    } yield wasmFunctionsR ++ mcpConnectorsR
  }

  def callToolsAnthropic(functions: Seq[AnthropicApiResponseChoiceMessageToolCall], conns: Seq[String], providerName: String)(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    val (wasmFunctions, mcpConnectors) = functions.partition(_.isWasm)
    val wasmFunctionsF = LlmToolFunction._callToolsAnthropic(wasmFunctions, providerName)(ec, env)
    val mcpConnectorsF = McpSupport.callToolsAnthropic(mcpConnectors, conns, providerName)(ec, env)
    for {
      wasmFunctionsR <- wasmFunctionsF
      mcpConnectorsR <- mcpConnectorsF
    } yield wasmFunctionsR ++ mcpConnectorsR
  }

  def callToolsCohere(functions: Seq[GenericApiResponseChoiceMessageToolCall], conns: Seq[String], providerName: String, fmap: Map[String, String])(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    val (wasmFunctions, mcpConnectors) = functions.partition(_.isWasm)
    val wasmFunctionsF = LlmToolFunction.callToolsCohere(wasmFunctions, providerName, fmap)(ec, env)
    val mcpConnectorsF = McpSupport.callToolsCohere(mcpConnectors, conns, providerName, fmap)(ec, env)
    for {
      wasmFunctionsR <- wasmFunctionsF
      mcpConnectorsR <- mcpConnectorsF
    } yield wasmFunctionsR ++ mcpConnectorsR
  }

  def tools(wasmFunctions: Seq[String], mcpConnectors: Seq[String], includeFunctions: Seq[String], excludeFunctions: Seq[String])(implicit ec: ExecutionContext, env: Env): JsObject = {
    val tools: Seq[JsObject] = LlmToolFunction._tools(wasmFunctions) ++ McpSupport.tools(mcpConnectors, includeFunctions, excludeFunctions)
    Json.obj(
      "tools" -> tools
    )
  }

  def toolsAnthropic(wasmFunctions: Seq[String], mcpConnectors: Seq[String], includeFunctions: Seq[String], excludeFunctions: Seq[String])(implicit ec: ExecutionContext, env: Env): JsObject = {
    val tools: Seq[JsObject] = LlmToolFunction._toolsAnthropic(wasmFunctions) ++ McpSupport.toolsAnthropic(mcpConnectors, includeFunctions, excludeFunctions)
    Json.obj(
      "tools" -> tools
    )
  }

  def toolsCohere(wasmFunctions: Seq[String], mcpConnectors: Seq[String], includeFunctions: Seq[String], excludeFunctions: Seq[String])(implicit ec: ExecutionContext, env: Env): (JsObject, Map[String, String]) = {
    val (wasmTools, wasmMap) = LlmToolFunction.toolsCohere(wasmFunctions)
    val (mcpTools, mcpMap) =  McpSupport.toolsCohere(mcpConnectors, includeFunctions, excludeFunctions)
    val tools: Seq[JsObject] = wasmTools ++ mcpTools
    val map = wasmMap ++ mcpMap
    (Json.obj(
      "tools" -> tools
    ), map)
  }
}
