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
      (function.toolId, fid)
    }.toMap
  }

  def callToolsOpenai(functions: Seq[GenericApiResponseChoiceMessageToolCall], conns: Seq[String], providerName: String, attrs: TypedMap, nameToFunction: Map[String, String])(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    val wasmFunctions = functions.filter(_.isWasm)
    val searchFunctions = functions.filter(_.isSearchEngine)
    val mcpConnectors = functions.filterNot(f => f.isWasm || f.isSearchEngine)
    val inlineFunctionsF = LlmToolFunction._callInlineToolsOpenai(wasmFunctions.filter(_.isInline), providerName, attrs)(ec, env)
    val wasmFunctionsF = LlmToolFunction._callToolsOpenai(wasmFunctions.filterNot(_.isInline), providerName, attrs, nameToFunction)(ec, env)
    val mcpConnectorsF = McpSupport.callToolsOpenai(mcpConnectors, conns, providerName, attrs)(ec, env)
    val searchFunctionsF = SearchEngineSupport.callToolsOpenai(searchFunctions, providerName, attrs)(ec, env)
    for {
      wasmFunctionsR <- wasmFunctionsF
      mcpConnectorsR <- mcpConnectorsF
      inlineFunctionsR <- inlineFunctionsF
      searchFunctionsR <- searchFunctionsF
    } yield inlineFunctionsR ++ wasmFunctionsR ++ mcpConnectorsR ++ searchFunctionsR
  }

  def callToolsOllama(functions: Seq[GenericApiResponseChoiceMessageToolCall], conns: Seq[String], attrs: TypedMap, nameToFunction: Map[String, String])(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    val wasmFunctions = functions.filter(_.isWasm)
    val searchFunctions = functions.filter(_.isSearchEngine)
    val mcpConnectors = functions.filterNot(f => f.isWasm || f.isSearchEngine)
    val inlineFunctionsF = LlmToolFunction._callInlineToolsOllama(wasmFunctions.filter(_.isInline), attrs)(ec, env)
    val wasmFunctionsF = LlmToolFunction._callToolsOllama(wasmFunctions, attrs, nameToFunction)(ec, env)
    val mcpConnectorsF = McpSupport.callToolsOllama(mcpConnectors, conns, attrs)(ec, env)
    val searchFunctionsF = SearchEngineSupport.callToolsOllama(searchFunctions, attrs)(ec, env)
    for {
      wasmFunctionsR <- wasmFunctionsF
      mcpConnectorsR <- mcpConnectorsF
      inlineFunctionsR <- inlineFunctionsF
      searchFunctionsR <- searchFunctionsF
    } yield inlineFunctionsR ++ wasmFunctionsR ++ mcpConnectorsR ++ searchFunctionsR
  }

  def callToolsAnthropic(functions: Seq[AnthropicApiResponseChoiceMessageToolCall], conns: Seq[String], providerName: String, attrs: TypedMap, nameToFunction: Map[String, String])(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    val wasmFunctions = functions.filter(_.isWasm)
    val searchFunctions = functions.filter(_.isSearchEngine)
    val mcpConnectors = functions.filterNot(f => f.isWasm || f.isSearchEngine)
    val inlineFunctionsF = LlmToolFunction._callInlineToolsAnthropic(wasmFunctions.filter(_.isInline), providerName, attrs)(ec, env)
    val wasmFunctionsF = LlmToolFunction._callToolsAnthropic(wasmFunctions, providerName, attrs, nameToFunction)(ec, env)
    val mcpConnectorsF = McpSupport.callToolsAnthropic(mcpConnectors, conns, providerName, attrs)(ec, env)
    val searchFunctionsF = SearchEngineSupport.callToolsAnthropic(searchFunctions, providerName, attrs)(ec, env)
    for {
      wasmFunctionsR <- wasmFunctionsF
      mcpConnectorsR <- mcpConnectorsF
      inlineFunctionsR <- inlineFunctionsF
      searchFunctionsR <- searchFunctionsF
    } yield inlineFunctionsR ++ wasmFunctionsR ++ mcpConnectorsR ++ searchFunctionsR
  }

  def callToolsCohere(functions: Seq[GenericApiResponseChoiceMessageToolCall], conns: Seq[String], providerName: String, fmap: Map[String, String], attrs: TypedMap, nameToFunction: Map[String, String])(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    val wasmFunctions = functions.filter(_.isWasm)
    val searchFunctions = functions.filter(_.isSearchEngine)
    val mcpConnectors = functions.filterNot(f => f.isWasm || f.isSearchEngine)
    val inlineFunctionsF = LlmToolFunction.callInlineToolsCohere(wasmFunctions.filter(_.isInline), providerName, fmap, attrs)(ec, env)
    val wasmFunctionsF = LlmToolFunction.callToolsCohere(wasmFunctions, providerName, fmap, attrs, nameToFunction)(ec, env)
    val mcpConnectorsF = McpSupport.callToolsCohere(mcpConnectors, conns, providerName, fmap, attrs)(ec, env)
    val searchFunctionsF = SearchEngineSupport.callToolsCohere(searchFunctions, providerName, attrs)(ec, env)
    for {
      wasmFunctionsR <- wasmFunctionsF
      mcpConnectorsR <- mcpConnectorsF
      inlineFunctionsR <- inlineFunctionsF
      searchFunctionsR <- searchFunctionsF
    } yield inlineFunctionsR ++ wasmFunctionsR ++ mcpConnectorsR ++ searchFunctionsR
  }

  def toolsWithInline(wasmFunctions: Seq[String], inlineFunctions: Seq[String], mcpConnectors: Seq[String], includeFunctions: Seq[String], excludeFunctions: Seq[String], attrs: TypedMap, searchEngines: Seq[String] = Seq.empty)(implicit ec: ExecutionContext, env: Env): JsObject = {
    val tools: Seq[JsObject] = LlmToolFunction._tools(wasmFunctions) ++ LlmToolFunction._inlineTools(inlineFunctions, attrs) ++ McpSupport.tools(mcpConnectors, includeFunctions, excludeFunctions, attrs) ++ SearchEngineSupport.tools(searchEngines)
    Json.obj(
      "tools" -> tools
    )
  }

  def tools(wasmFunctions: Seq[String], mcpConnectors: Seq[String], includeFunctions: Seq[String], excludeFunctions: Seq[String], attrs: TypedMap, searchEngines: Seq[String] = Seq.empty)(implicit ec: ExecutionContext, env: Env): JsObject = {
    val tools: Seq[JsObject] = LlmToolFunction._tools(wasmFunctions) ++ McpSupport.tools(mcpConnectors, includeFunctions, excludeFunctions, attrs) ++ SearchEngineSupport.tools(searchEngines)
    Json.obj(
      "tools" -> tools
    )
  }

  def toolsAnthropic(wasmFunctions: Seq[String], mcpConnectors: Seq[String], includeFunctions: Seq[String], excludeFunctions: Seq[String], attrs: TypedMap, searchEngines: Seq[String] = Seq.empty)(implicit ec: ExecutionContext, env: Env): JsObject = {
    val tools: Seq[JsObject] = LlmToolFunction._toolsAnthropic(wasmFunctions) ++ McpSupport.toolsAnthropic(mcpConnectors, includeFunctions, excludeFunctions, attrs) ++ SearchEngineSupport.toolsAnthropic(searchEngines)
    Json.obj(
      "tools" -> tools
    )
  }

  def toolsAnthropicWithInline(wasmFunctions: Seq[String], inlineFunctions: Seq[String], mcpConnectors: Seq[String], includeFunctions: Seq[String], excludeFunctions: Seq[String], attrs: TypedMap, searchEngines: Seq[String] = Seq.empty)(implicit ec: ExecutionContext, env: Env): JsObject = {
    val tools: Seq[JsObject] = LlmToolFunction._toolsAnthropic(wasmFunctions) ++ LlmToolFunction._inlineToolsAnthropic(inlineFunctions, attrs) ++ McpSupport.toolsAnthropic(mcpConnectors, includeFunctions, excludeFunctions, attrs) ++ SearchEngineSupport.toolsAnthropic(searchEngines)
    Json.obj(
      "tools" -> tools
    )
  }

  def toolsCohere(wasmFunctions: Seq[String], mcpConnectors: Seq[String], includeFunctions: Seq[String], excludeFunctions: Seq[String], attrs: TypedMap, searchEngines: Seq[String] = Seq.empty)(implicit ec: ExecutionContext, env: Env): (JsObject, Map[String, String]) = {
    val (wasmTools, wasmMap) = LlmToolFunction.toolsCohere(wasmFunctions)
    val (mcpTools, mcpMap) =  McpSupport.toolsCohere(mcpConnectors, includeFunctions, excludeFunctions, attrs)
    val (searchTools, searchMap) = SearchEngineSupport.toolsCohere(searchEngines)
    val tools: Seq[JsObject] = wasmTools ++ mcpTools ++ searchTools
    val map = wasmMap ++ mcpMap ++ searchMap
    (Json.obj(
      "tools" -> tools
    ), map)
  }

  def toolsCohereWithInline(wasmFunctions: Seq[String], inlineFunctions: Seq[String], mcpConnectors: Seq[String], includeFunctions: Seq[String], excludeFunctions: Seq[String], attrs: TypedMap, searchEngines: Seq[String] = Seq.empty)(implicit ec: ExecutionContext, env: Env): (JsObject, Map[String, String]) = {
    val (wasmTools, wasmMap) = LlmToolFunction.toolsCohere(wasmFunctions)
    val (mcpTools, mcpMap) =  McpSupport.toolsCohere(mcpConnectors, includeFunctions, excludeFunctions, attrs)
    val (inlineTools, inlineMap) =  LlmToolFunction.inlineToolsCohere(inlineFunctions, attrs)
    val (searchTools, searchMap) = SearchEngineSupport.toolsCohere(searchEngines)
    val tools: Seq[JsObject] = wasmTools ++ inlineTools ++ mcpTools ++ searchTools
    val map = wasmMap ++ inlineMap ++ mcpMap ++ searchMap
    (Json.obj(
      "tools" -> tools
    ), map)
  }
}
