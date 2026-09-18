package com.cloud.apim.otoroshi.extensions.aigateway.entities

import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.{JsObject, JsValue, Json}

import scala.concurrent.{ExecutionContext, Future}

/**
 * The tools of one call. The tools a model can use are attached to its provider, for all of its traffic:
 * a request names in `allowed_tools` the ones it wants this time, by entity id. It can only narrow what
 * the provider carries — an id it does not carry is ignored, so a caller cannot reach a tool the operator
 * did not attach — and an empty list asks for no tool at all. Without the field, the provider serves its
 * own tools, as it always did.
 */
object ToolsSelection {

  val field = "allowed_tools"

  // every option of a provider that holds tool entity ids
  private val options = Seq("wasm_tools", "tool_functions", "mcp_connectors", "a2a_connectors", "search_engines")

  // the selection of the request, and the body to go on with: the field never reaches the provider api
  def extract(body: JsValue): (JsValue, Option[Seq[String]]) = body match {
    case obj: JsObject => obj.select(field).asOpt[Seq[String]] match {
      case None      => (obj, None)
      case Some(ids) => (obj - field, Some(ids))
    }
    case other => (other, None)
  }

  def narrow(provider: AiProvider, allowed: Option[Seq[String]]): AiProvider = allowed match {
    case None => provider
    case Some(ids) =>
      val kept = options.foldLeft(Json.obj()) { case (acc, option) =>
        provider.options.select(option).asOpt[Seq[String]] match {
          case None             => acc
          case Some(configured) => acc ++ Json.obj(option -> configured.filter(ids.contains))
        }
      }
      provider.copy(options = provider.options ++ kept)
  }

  // the tools of a provider, as a request names them
  def attached(provider: AiProvider): Seq[String] = options.flatMap(o => provider.options.select(o).asOpt[Seq[String]].getOrElse(Seq.empty)).distinct
}

object LlmFunctions {

  def nameToFunction(functionIds: Seq[String])(using env: Env): Map[String, String] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    functionIds.map { fid =>
      val function = ext.states.toolFunction(fid).get
      (function.toolId, fid)
    }.toMap
  }

  def callToolsOpenai(functions: Seq[GenericApiResponseChoiceMessageToolCall], conns: Seq[String], providerName: String, attrs: TypedMap, nameToFunction: Map[String, String])(using ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    val wasmFunctions = functions.filter(_.isWasm)
    val searchFunctions = functions.filter(_.isSearchEngine)
    val a2aConnectors = functions.filter(_.isA2A)
    val mcpConnectors = functions.filterNot(f => f.isWasm || f.isSearchEngine || f.isA2A)
    val inlineFunctionsF = LlmToolFunction._callInlineToolsOpenai(wasmFunctions.filter(_.isInline), providerName, attrs)(using ec, env)
    val wasmFunctionsF = LlmToolFunction._callToolsOpenai(wasmFunctions.filterNot(_.isInline), providerName, attrs, nameToFunction)(using ec, env)
    val mcpConnectorsF = McpSupport.callToolsOpenai(mcpConnectors, conns, providerName, attrs)(using ec, env)
    val a2aConnectorsF = A2ASupport.callToolsOpenai(a2aConnectors, providerName, attrs)(using ec, env)
    val searchFunctionsF = SearchEngineSupport.callToolsOpenai(searchFunctions, providerName, attrs)(using ec, env)
    for {
      wasmFunctionsR <- wasmFunctionsF
      mcpConnectorsR <- mcpConnectorsF
      a2aConnectorsR <- a2aConnectorsF
      inlineFunctionsR <- inlineFunctionsF
      searchFunctionsR <- searchFunctionsF
    } yield inlineFunctionsR ++ wasmFunctionsR ++ mcpConnectorsR ++ a2aConnectorsR ++ searchFunctionsR
  }

  def callToolsOllama(functions: Seq[GenericApiResponseChoiceMessageToolCall], conns: Seq[String], attrs: TypedMap, nameToFunction: Map[String, String])(using ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    val wasmFunctions = functions.filter(_.isWasm)
    val searchFunctions = functions.filter(_.isSearchEngine)
    val a2aConnectors = functions.filter(_.isA2A)
    val mcpConnectors = functions.filterNot(f => f.isWasm || f.isSearchEngine || f.isA2A)
    val inlineFunctionsF = LlmToolFunction._callInlineToolsOllama(wasmFunctions.filter(_.isInline), attrs)(using ec, env)
    val wasmFunctionsF = LlmToolFunction._callToolsOllama(wasmFunctions, attrs, nameToFunction)(using ec, env)
    val mcpConnectorsF = McpSupport.callToolsOllama(mcpConnectors, conns, attrs)(using ec, env)
    val a2aConnectorsF = A2ASupport.callToolsOllama(a2aConnectors, attrs)(using ec, env)
    val searchFunctionsF = SearchEngineSupport.callToolsOllama(searchFunctions, attrs)(using ec, env)
    for {
      wasmFunctionsR <- wasmFunctionsF
      mcpConnectorsR <- mcpConnectorsF
      a2aConnectorsR <- a2aConnectorsF
      inlineFunctionsR <- inlineFunctionsF
      searchFunctionsR <- searchFunctionsF
    } yield inlineFunctionsR ++ wasmFunctionsR ++ mcpConnectorsR ++ a2aConnectorsR ++ searchFunctionsR
  }

  def callToolsAnthropic(functions: Seq[AnthropicApiResponseChoiceMessageToolCall], conns: Seq[String], providerName: String, attrs: TypedMap, nameToFunction: Map[String, String])(using ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    val wasmFunctions = functions.filter(_.isWasm)
    val searchFunctions = functions.filter(_.isSearchEngine)
    val a2aConnectors = functions.filter(_.isA2A)
    val mcpConnectors = functions.filterNot(f => f.isWasm || f.isSearchEngine || f.isA2A)
    val inlineFunctionsF = LlmToolFunction._callInlineToolsAnthropic(wasmFunctions.filter(_.isInline), providerName, attrs)(using ec, env)
    val wasmFunctionsF = LlmToolFunction._callToolsAnthropic(wasmFunctions, providerName, attrs, nameToFunction)(using ec, env)
    val mcpConnectorsF = McpSupport.callToolsAnthropic(mcpConnectors, conns, providerName, attrs)(using ec, env)
    val a2aConnectorsF = A2ASupport.callToolsAnthropic(a2aConnectors, attrs)(using ec, env)
    val searchFunctionsF = SearchEngineSupport.callToolsAnthropic(searchFunctions, providerName, attrs)(using ec, env)
    for {
      wasmFunctionsR <- wasmFunctionsF
      mcpConnectorsR <- mcpConnectorsF
      a2aConnectorsR <- a2aConnectorsF
      inlineFunctionsR <- inlineFunctionsF
      searchFunctionsR <- searchFunctionsF
    } yield inlineFunctionsR ++ wasmFunctionsR ++ mcpConnectorsR ++ a2aConnectorsR ++ searchFunctionsR
  }

  def callToolsCohere(functions: Seq[GenericApiResponseChoiceMessageToolCall], conns: Seq[String], providerName: String, fmap: Map[String, String], attrs: TypedMap, nameToFunction: Map[String, String])(using ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    val wasmFunctions = functions.filter(_.isWasm)
    val searchFunctions = functions.filter(_.isSearchEngine)
    val a2aConnectors = functions.filter(_.isA2A)
    val mcpConnectors = functions.filterNot(f => f.isWasm || f.isSearchEngine || f.isA2A)
    val inlineFunctionsF = LlmToolFunction.callInlineToolsCohere(wasmFunctions.filter(_.isInline), providerName, fmap, attrs)(using ec, env)
    val wasmFunctionsF = LlmToolFunction.callToolsCohere(wasmFunctions, providerName, fmap, attrs, nameToFunction)(using ec, env)
    val mcpConnectorsF = McpSupport.callToolsCohere(mcpConnectors, conns, providerName, fmap, attrs)(using ec, env)
    val a2aConnectorsF = A2ASupport.callToolsCohere(a2aConnectors, fmap, providerName, attrs)(using ec, env)
    val searchFunctionsF = SearchEngineSupport.callToolsCohere(searchFunctions, providerName, attrs)(using ec, env)
    for {
      wasmFunctionsR <- wasmFunctionsF
      mcpConnectorsR <- mcpConnectorsF
      a2aConnectorsR <- a2aConnectorsF
      inlineFunctionsR <- inlineFunctionsF
      searchFunctionsR <- searchFunctionsF
    } yield inlineFunctionsR ++ wasmFunctionsR ++ mcpConnectorsR ++ a2aConnectorsR ++ searchFunctionsR
  }

  def toolsWithInline(wasmFunctions: Seq[String], inlineFunctions: Seq[String], mcpConnectors: Seq[String], includeFunctions: Seq[String], excludeFunctions: Seq[String], attrs: TypedMap, searchEngines: Seq[String] = Seq.empty)(using ec: ExecutionContext, env: Env): JsObject = {
    val tools: Seq[JsObject] = LlmToolFunction._tools(wasmFunctions) ++ LlmToolFunction._inlineTools(inlineFunctions, attrs) ++ McpSupport.tools(mcpConnectors, includeFunctions, excludeFunctions, attrs) ++ A2ASupport.tools(attrs) ++ SearchEngineSupport.tools(searchEngines)
    Json.obj(
      "tools" -> tools
    )
  }

  def tools(wasmFunctions: Seq[String], mcpConnectors: Seq[String], includeFunctions: Seq[String], excludeFunctions: Seq[String], attrs: TypedMap, searchEngines: Seq[String] = Seq.empty)(using ec: ExecutionContext, env: Env): JsObject = {
    val tools: Seq[JsObject] = LlmToolFunction._tools(wasmFunctions) ++ McpSupport.tools(mcpConnectors, includeFunctions, excludeFunctions, attrs) ++ A2ASupport.tools(attrs) ++ SearchEngineSupport.tools(searchEngines)
    Json.obj(
      "tools" -> tools
    )
  }

  def toolsAnthropic(wasmFunctions: Seq[String], mcpConnectors: Seq[String], includeFunctions: Seq[String], excludeFunctions: Seq[String], attrs: TypedMap, searchEngines: Seq[String] = Seq.empty)(using ec: ExecutionContext, env: Env): JsObject = {
    val tools: Seq[JsObject] = LlmToolFunction._toolsAnthropic(wasmFunctions) ++ McpSupport.toolsAnthropic(mcpConnectors, includeFunctions, excludeFunctions, attrs) ++ A2ASupport.toolsAnthropic(attrs) ++ SearchEngineSupport.toolsAnthropic(searchEngines)
    Json.obj(
      "tools" -> tools
    )
  }

  def toolsAnthropicWithInline(wasmFunctions: Seq[String], inlineFunctions: Seq[String], mcpConnectors: Seq[String], includeFunctions: Seq[String], excludeFunctions: Seq[String], attrs: TypedMap, searchEngines: Seq[String] = Seq.empty)(using ec: ExecutionContext, env: Env): JsObject = {
    val tools: Seq[JsObject] = LlmToolFunction._toolsAnthropic(wasmFunctions) ++ LlmToolFunction._inlineToolsAnthropic(inlineFunctions, attrs) ++ McpSupport.toolsAnthropic(mcpConnectors, includeFunctions, excludeFunctions, attrs) ++ A2ASupport.toolsAnthropic(attrs) ++ SearchEngineSupport.toolsAnthropic(searchEngines)
    Json.obj(
      "tools" -> tools
    )
  }

  def toolsCohere(wasmFunctions: Seq[String], mcpConnectors: Seq[String], includeFunctions: Seq[String], excludeFunctions: Seq[String], attrs: TypedMap, searchEngines: Seq[String] = Seq.empty)(using ec: ExecutionContext, env: Env): (JsObject, Map[String, String]) = {
    val (wasmTools, wasmMap) = LlmToolFunction.toolsCohere(wasmFunctions)
    val (mcpTools, mcpMap) =  McpSupport.toolsCohere(mcpConnectors, includeFunctions, excludeFunctions, attrs)
    val (a2aTools, a2aMap) = A2ASupport.toolsCohere(attrs)
    val (searchTools, searchMap) = SearchEngineSupport.toolsCohere(searchEngines)
    val tools: Seq[JsObject] = wasmTools ++ mcpTools ++ a2aTools ++ searchTools
    val map = wasmMap ++ mcpMap ++ a2aMap ++ searchMap
    (Json.obj(
      "tools" -> tools
    ), map)
  }

  def toolsCohereWithInline(wasmFunctions: Seq[String], inlineFunctions: Seq[String], mcpConnectors: Seq[String], includeFunctions: Seq[String], excludeFunctions: Seq[String], attrs: TypedMap, searchEngines: Seq[String] = Seq.empty)(using ec: ExecutionContext, env: Env): (JsObject, Map[String, String]) = {
    val (wasmTools, wasmMap) = LlmToolFunction.toolsCohere(wasmFunctions)
    val (mcpTools, mcpMap) =  McpSupport.toolsCohere(mcpConnectors, includeFunctions, excludeFunctions, attrs)
    val (a2aTools, a2aMap) = A2ASupport.toolsCohere(attrs)
    val (inlineTools, inlineMap) =  LlmToolFunction.inlineToolsCohere(inlineFunctions, attrs)
    val (searchTools, searchMap) = SearchEngineSupport.toolsCohere(searchEngines)
    val tools: Seq[JsObject] = wasmTools ++ inlineTools ++ mcpTools ++ a2aTools ++ searchTools
    val map = wasmMap ++ inlineMap ++ mcpMap ++ a2aMap ++ searchMap
    (Json.obj(
      "tools" -> tools
    ), map)
  }
}
