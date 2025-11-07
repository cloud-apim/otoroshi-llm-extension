package com.cloud.apim.otoroshi.extensions.aigateway.entities

import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.{JsObject, JsValue, Json}

import scala.concurrent.{ExecutionContext, Future}

object LlmFunctions {

  def nameToFunction(functionIds: Seq[String])(implicit env: Env): Map[String, String] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    functionIds.map { fid =>
      val function = ext.states.toolFunction(fid).get
      (fid, function.toolId)
    }.toMap
  }

  def callToolsOpenai(functions: Seq[GenericApiResponseChoiceMessageToolCall], conns: Seq[String], providerName: String, attrs: TypedMap, nameToFunction: Map[String, String])(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    val (wasmFunctions, mcpConnectors) = functions.partition(_.isWasm)
    val inlineFunctionsF = LlmToolFunction._callInlineToolsOpenai(wasmFunctions.filter(_.isInline), providerName, attrs)(ec, env)
    val wasmFunctionsF = LlmToolFunction._callToolsOpenai(wasmFunctions.filterNot(_.isInline), providerName, attrs, nameToFunction)(ec, env)
    val mcpConnectorsF = McpSupport.callToolsOpenai(mcpConnectors, conns, providerName)(ec, env)
    for {
      wasmFunctionsR <- wasmFunctionsF
      mcpConnectorsR <- mcpConnectorsF
      inlineFunctionsR <- inlineFunctionsF
    } yield inlineFunctionsR ++ wasmFunctionsR ++ mcpConnectorsR
  }

  def callToolsOllama(functions: Seq[GenericApiResponseChoiceMessageToolCall], conns: Seq[String], attrs: TypedMap, nameToFunction: Map[String, String])(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    val (wasmFunctions, mcpConnectors) = functions.partition(_.isWasm)
    val inlineFunctionsF = LlmToolFunction._callInlineToolsOllama(wasmFunctions.filter(_.isInline), attrs)(ec, env)
    val wasmFunctionsF = LlmToolFunction._callToolsOllama(wasmFunctions, attrs, nameToFunction)(ec, env)
    val mcpConnectorsF = McpSupport.callToolsOllama(mcpConnectors, conns)(ec, env)
    for {
      wasmFunctionsR <- wasmFunctionsF
      mcpConnectorsR <- mcpConnectorsF
      inlineFunctionsR <- inlineFunctionsF
    } yield inlineFunctionsR ++ wasmFunctionsR ++ mcpConnectorsR
  }

  def callToolsAnthropic(functions: Seq[AnthropicApiResponseChoiceMessageToolCall], conns: Seq[String], providerName: String, attrs: TypedMap, nameToFunction: Map[String, String])(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    val (wasmFunctions, mcpConnectors) = functions.partition(_.isWasm)
    val inlineFunctionsF = LlmToolFunction._callInlineToolsAnthropic(wasmFunctions.filter(_.isInline), providerName, attrs)(ec, env)
    val wasmFunctionsF = LlmToolFunction._callToolsAnthropic(wasmFunctions, providerName, attrs, nameToFunction)(ec, env)
    val mcpConnectorsF = McpSupport.callToolsAnthropic(mcpConnectors, conns, providerName)(ec, env)
    for {
      wasmFunctionsR <- wasmFunctionsF
      mcpConnectorsR <- mcpConnectorsF
      inlineFunctionsR <- inlineFunctionsF
    } yield inlineFunctionsR ++ wasmFunctionsR ++ mcpConnectorsR
  }

  def callToolsCohere(functions: Seq[GenericApiResponseChoiceMessageToolCall], conns: Seq[String], providerName: String, fmap: Map[String, String], attrs: TypedMap, nameToFunction: Map[String, String])(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    val (wasmFunctions, mcpConnectors) = functions.partition(_.isWasm)
    val inlineFunctionsF = LlmToolFunction.callInlineToolsCohere(wasmFunctions.filter(_.isInline), providerName, fmap, attrs)(ec, env)
    val wasmFunctionsF = LlmToolFunction.callToolsCohere(wasmFunctions, providerName, fmap, attrs, nameToFunction)(ec, env)
    val mcpConnectorsF = McpSupport.callToolsCohere(mcpConnectors, conns, providerName, fmap)(ec, env)
    for {
      wasmFunctionsR <- wasmFunctionsF
      mcpConnectorsR <- mcpConnectorsF
      inlineFunctionsR <- inlineFunctionsF
    } yield inlineFunctionsR ++ wasmFunctionsR ++ mcpConnectorsR
  }

  def toolsWithInline(wasmFunctions: Seq[String], inlineFunctions: Seq[String], mcpConnectors: Seq[String], includeFunctions: Seq[String], excludeFunctions: Seq[String], attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): JsObject = {
    val tools: Seq[JsObject] = LlmToolFunction._tools(wasmFunctions) ++ LlmToolFunction._inlineTools(inlineFunctions, attrs) ++ McpSupport.tools(mcpConnectors, includeFunctions, excludeFunctions)
    Json.obj(
      "tools" -> tools
    )
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

  def toolsAnthropicWithInline(wasmFunctions: Seq[String], inlineFunctions: Seq[String], mcpConnectors: Seq[String], includeFunctions: Seq[String], excludeFunctions: Seq[String], attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): JsObject = {
    val tools: Seq[JsObject] = LlmToolFunction._toolsAnthropic(wasmFunctions) ++ LlmToolFunction._inlineToolsAnthropic(inlineFunctions, attrs) ++ McpSupport.toolsAnthropic(mcpConnectors, includeFunctions, excludeFunctions)
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

  def toolsCohereWithInline(wasmFunctions: Seq[String], inlineFunctions: Seq[String], mcpConnectors: Seq[String], includeFunctions: Seq[String], excludeFunctions: Seq[String], attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): (JsObject, Map[String, String]) = {
    val (wasmTools, wasmMap) = LlmToolFunction.toolsCohere(wasmFunctions)
    val (mcpTools, mcpMap) =  McpSupport.toolsCohere(mcpConnectors, includeFunctions, excludeFunctions)
    val (inlineTools, inlineMap) =  LlmToolFunction.inlineToolsCohere(inlineFunctions, attrs)
    val tools: Seq[JsObject] = wasmTools ++ inlineTools ++ mcpTools
    val map = wasmMap ++ inlineMap ++ mcpMap
    (Json.obj(
      "tools" -> tools
    ), map)
  }
}
