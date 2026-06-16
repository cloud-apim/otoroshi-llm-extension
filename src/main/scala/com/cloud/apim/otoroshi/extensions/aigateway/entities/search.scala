package com.cloud.apim.otoroshi.extensions.aigateway.entities

import akka.stream.scaladsl.{Sink, Source}
import com.cloud.apim.otoroshi.extensions.aigateway.{SearchEngineClient, SearchEngineSearchOptions}
import com.cloud.apim.otoroshi.extensions.aigateway.providers._
import otoroshi.api._
import otoroshi.env.Env
import otoroshi.models._
import otoroshi.next.extensions.AdminExtensionId
import otoroshi.security.IdGenerator
import otoroshi.storage._
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway._
import play.api.libs.json._

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class SearchEngine(
                         location: EntityLocation,
                         id: String,
                         name: String,
                         description: String,
                         tags: Seq[String],
                         metadata: Map[String, String],
                         provider: String,
                         config: JsObject
                       ) extends EntityLocationSupport {
  override def internalId: String = id

  override def json: JsValue = SearchEngine.format.writes(this)

  override def theName: String = name

  override def theDescription: String = description

  override def theTags: Seq[String] = tags

  override def theMetadata: Map[String, String] = metadata

  def slugName: String = metadata.getOrElse("endpoint_name", name).slugifyWithSlash.replaceAll("-+", "_")

  def getSearchEngineClient()(implicit env: Env): Option[SearchEngineClient] = {
    val connection = config.select("connection").asOpt[JsObject].getOrElse(Json.obj())
    val baseUrl = connection.select("base_url").orElse(connection.select("base_domain")).asOpt[String]
    val _token = connection.select("token").asOpt[String].getOrElse("xxx")
    val token = if (_token.contains(",")) {
      val parts = _token.split(",").map(_.trim)
      val index = AiProvider.tokenCounter.incrementAndGet() % (if (parts.nonEmpty) parts.length else 1)
      parts(index)
    } else {
      _token
    }
    val timeout = connection.select("timeout").asOpt[Long].map(FiniteDuration(_, TimeUnit.MILLISECONDS))
    val options = config.select("options").asOpt[JsObject].getOrElse(Json.obj())
    provider.toLowerCase() match {
      case "staan" => {
        val api = new StaanApi(baseUrl.getOrElse(StaanApi.baseUrl), token, timeout.getOrElse(30.seconds), env = env)
        new StaanSearchClient(api, StaanSearchOptions.fromJson(options), id).some
      }
      case "tavily" => {
        val api = new TavilyApi(baseUrl.getOrElse(TavilyApi.baseUrl), token, timeout.getOrElse(30.seconds), env = env)
        new TavilySearchClient(api, TavilySearchOptions.fromJson(options), id).some
      }
      case "brave" => {
        val api = new BraveSearchApi(baseUrl.getOrElse(BraveSearchApi.baseUrl), token, timeout.getOrElse(30.seconds), env = env)
        new BraveSearchClient(api, BraveSearchOptions.fromJson(options), id).some
      }
      case "searxng" => {
        val api = new SearXNGApi(baseUrl.getOrElse(SearXNGApi.baseUrl), token, timeout.getOrElse(30.seconds), env = env)
        new SearXNGSearchClient(api, SearXNGSearchOptions.fromJson(options), id).some
      }
      case "google" => {
        val api = new GoogleCseApi(baseUrl.getOrElse(GoogleCseApi.baseUrl), token, timeout.getOrElse(30.seconds), env = env)
        new GoogleCseSearchClient(api, GoogleCseSearchOptions.fromJson(options), id).some
      }
      case "searchapi" => {
        val api = new SearchApiApi(baseUrl.getOrElse(SearchApiApi.baseUrl), token, timeout.getOrElse(30.seconds), env = env)
        new SearchApiSearchClient(api, SearchApiSearchOptions.fromJson(options), id).some
      }
      case "duckduckgo" => {
        val api = new DuckDuckGoApi(baseUrl.getOrElse(DuckDuckGoApi.baseUrl), token, timeout.getOrElse(30.seconds), env = env)
        new DuckDuckGoSearchClient(api, DuckDuckGoSearchOptions.fromJson(options), id).some
      }
      case _ => None
    }
  }
}

object SearchEngine {
  val format = new Format[SearchEngine] {
    override def writes(o: SearchEngine): JsValue = o.location.jsonWithKey ++ Json.obj(
      "id" -> o.id,
      "name" -> o.name,
      "description" -> o.description,
      "metadata" -> o.metadata,
      "tags" -> JsArray(o.tags.map(JsString.apply)),
      "provider" -> o.provider,
      "config" -> o.config,
    )

    override def reads(json: JsValue): JsResult[SearchEngine] = Try {
      SearchEngine(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = (json \ "id").as[String],
        name = (json \ "name").as[String],
        description = (json \ "description").as[String],
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        provider = (json \ "provider").as[String],
        config = (json \ "config").asOpt[JsObject].getOrElse(Json.obj()),
      )
    } match {
      case Failure(ex) => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }

  def template(provider: String, env: Env): SearchEngine = {
    val (theName, theDesc, theProvider, baseUrl, options) = provider match {
      case "tavily" => ("Tavily", "A Tavily web search engine", "tavily", TavilyApi.baseUrl, Json.obj("max_results" -> 5, "search_depth" -> "basic", "include_answer" -> false))
      case "brave" => ("Brave Search", "A Brave web search engine", "brave", BraveSearchApi.baseUrl, Json.obj())
      case "searxng" => ("SearXNG", "A SearXNG metasearch engine", "searxng", SearXNGApi.baseUrl, Json.obj())
      case "google" => ("Google Custom Search", "A Google Custom Search engine", "google", GoogleCseApi.baseUrl, Json.obj("cx" -> ""))
      case "searchapi" => ("SearchApi", "A SearchApi search engine", "searchapi", SearchApiApi.baseUrl, Json.obj("engine" -> "google"))
      case "duckduckgo" => ("DuckDuckGo", "A DuckDuckGo instant answer engine", "duckduckgo", DuckDuckGoApi.baseUrl, Json.obj())
      case _ => ("Staan.ai", "A Staan.ai (Qwant) sovereign web search engine", "staan", StaanApi.baseUrl, Json.obj("market" -> "fr-FR", "count" -> 10))
    }
    SearchEngine(
      id = IdGenerator.namedId("search-engine", env),
      name = theName,
      description = theDesc,
      metadata = Map.empty,
      tags = Seq.empty,
      location = EntityLocation.default,
      provider = theProvider,
      config = Json.obj(
        "connection" -> Json.obj(
          "base_url" -> baseUrl,
          "token" -> "xxxxx",
          "timeout" -> 30.seconds.toMillis,
        ),
        "options" -> options
      ),
    )
  }

  def resource(env: Env, datastores: AiGatewayExtensionDatastores, states: AiGatewayExtensionState): Resource = {
    Resource(
      "SearchEngine",
      "search-engines",
      "search-engine",
      "ai-gateway.extensions.cloud-apim.com",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[SearchEngine](
        format = SearchEngine.format,
        clazz = classOf[SearchEngine],
        keyf = id => datastores.searchEnginesDataStore.key(id),
        extractIdf = c => datastores.searchEnginesDataStore.extractId(c),
        extractIdJsonf = json => json.select("id").asString,
        idFieldNamef = () => "id",
        tmpl = (v, p, ctx) => SearchEngine.template(p.get("kind").map(_.toLowerCase()).getOrElse("staan"), env).json,
        canRead = true,
        canCreate = true,
        canUpdate = true,
        canDelete = true,
        canBulk = true,
        stateAll = () => states.allSearchEngines(),
        stateOne = id => states.searchEngine(id),
        stateUpdate = values => states.updateSearchEngines(values)
      )
    )
  }
}

// Exposes referenced SearchEngine entities as LLM tools (one tool per engine, named `search___<engineId>`), and
// executes the tool calls by delegating to the engine's SearchEngineClient. Mirrors McpSupport, but the engine id is
// self-encoded in the tool name so the executor needs no external list (no signature change to the provider tool loop).
object SearchEngineSupport {

  private def paramsSchema: JsObject = Json.obj(
    "type" -> "object",
    "required" -> Json.arr("query"),
    "additionalProperties" -> false,
    "properties" -> Json.obj(
      "query" -> Json.obj("type" -> "string", "description" -> "the web search query"),
      "max_results" -> Json.obj("type" -> "integer", "description" -> "the maximum number of results to return (optional)"),
    )
  )

  private def decl(se: SearchEngine, anthropic: Boolean): JsObject = {
    val desc = s"Search the web using ${se.name}. Returns a list of relevant web results (title, url, snippet) for the given query."
    if (anthropic) {
      Json.obj("name" -> s"search___${se.id}", "description" -> desc, "input_schema" -> paramsSchema)
    } else {
      Json.obj("type" -> "function", "function" -> Json.obj(
        "name" -> s"search___${se.id}",
        "description" -> desc,
        "strict" -> false,
        "parameters" -> paramsSchema,
      ))
    }
  }

  def tools(engines: Seq[String])(implicit env: Env): Seq[JsObject] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    engines.flatMap(ext.states.searchEngine).map(se => decl(se, anthropic = false))
  }

  def toolsAnthropic(engines: Seq[String])(implicit env: Env): Seq[JsObject] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    engines.flatMap(ext.states.searchEngine).map(se => decl(se, anthropic = true))
  }

  // engine ids are id-safe so no name hashing is needed (unlike mcp/wasm); returns an empty name map.
  def toolsCohere(engines: Seq[String])(implicit env: Env): (Seq[JsObject], Map[String, String]) = {
    (tools(engines), Map.empty[String, String])
  }

  private def executeSearch(engineId: String, argsJson: String, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[String] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    ext.states.searchEngine(engineId).flatMap(_.getSearchEngineClient()) match {
      case None => s"unknown or unavailable search engine: ${engineId}".vfuture
      case Some(client) => {
        val args: JsObject = Try(Json.parse(argsJson).asOpt[JsObject].getOrElse(Json.obj())).getOrElse(Json.obj())
        val options = SearchEngineSearchOptions.format.reads(args).getOrElse(SearchEngineSearchOptions(args.select("query").asOptString.getOrElse("")))
        client.search(options, args, attrs).map {
          case Left(err) => Json.obj("error" -> err).stringify
          case Right(resp) => resp.toJson.stringify
        }
      }
    }
  }

  private def runGeneric(functions: Seq[GenericApiResponseChoiceMessageToolCall], attrs: TypedMap)(f: (String, GenericApiResponseChoiceMessageToolCall) => Source[JsValue, _])(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    Source(functions.toList)
      .mapAsync(1) { toolCall =>
        executeSearch(toolCall.function.searchEngineId, toolCall.function.arguments, attrs).map(r => (r, toolCall))
      }
      .flatMapConcat { case (resp, tc) => f(resp, tc) }
      .runWith(Sink.seq)(env.otoroshiMaterializer)
  }

  private def runAnthropic(functions: Seq[AnthropicApiResponseChoiceMessageToolCall], attrs: TypedMap)(f: (String, AnthropicApiResponseChoiceMessageToolCall) => Source[JsValue, _])(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    Source(functions.toList)
      .mapAsync(1) { toolCall =>
        executeSearch(toolCall.searchEngineId, toolCall.arguments, attrs).map(r => (r, toolCall))
      }
      .flatMapConcat { case (resp, tc) => f(resp, tc) }
      .runWith(Sink.seq)(env.otoroshiMaterializer)
  }

  def callToolsOpenai(functions: Seq[GenericApiResponseChoiceMessageToolCall], providerName: String, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    runGeneric(functions, attrs) { (resp, tc) =>
      Source(List(Json.obj("role" -> "assistant", "tool_calls" -> Json.arr(tc.raw)), Json.obj(
        "role" -> "tool",
        "content" -> resp,
        "tool_call_id" -> tc.id,
      ))).applyOnIf(providerName.toLowerCase().contains("deepseek")) { s =>
        s.concat(Source(List(Json.obj("role" -> "user", "content" -> resp))))
      }
    }
  }

  def callToolsOllama(functions: Seq[GenericApiResponseChoiceMessageToolCall], attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    runGeneric(functions, attrs) { (resp, tc) =>
      Source(List(Json.obj("role" -> "assistant", "content" -> "", "tool_calls" -> Json.arr(tc.raw)), Json.obj(
        "role" -> "tool",
        "content" -> resp,
      )))
    }
  }

  def callToolsCohere(functions: Seq[GenericApiResponseChoiceMessageToolCall], providerName: String, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    callToolsOpenai(functions, providerName, attrs)
  }

  def callToolsAnthropic(functions: Seq[AnthropicApiResponseChoiceMessageToolCall], providerName: String, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Seq[JsValue]] = {
    runAnthropic(functions, attrs) { (resp, tc) =>
      Source(List(
        Json.obj("role" -> "assistant", "content" -> Json.arr(Json.obj(
          "type" -> "tool_use",
          "id" -> tc.id,
          "name" -> tc.raw_name,
          "input" -> tc.input,
        ))),
        Json.obj("role" -> "user", "content" -> Json.arr(Json.obj(
          "type" -> "tool_result",
          "tool_use_id" -> tc.id,
          "content" -> resp,
        )))))
    }
  }
}

trait SearchEnginesDataStore extends BasicStore[SearchEngine]

class KvSearchEnginesDataStore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
  extends SearchEnginesDataStore
    with RedisLikeStore[SearchEngine] {
  override def fmt: Format[SearchEngine] = SearchEngine.format

  override def redisLike(implicit env: Env): RedisLike = redisCli

  override def key(id: String): String = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:searchengines:$id"

  override def extractId(value: SearchEngine): String = value.id
}
