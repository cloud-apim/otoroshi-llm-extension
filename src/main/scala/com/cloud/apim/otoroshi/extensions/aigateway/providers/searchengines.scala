package com.cloud.apim.otoroshi.extensions.aigateway.providers

import com.cloud.apim.otoroshi.extensions.aigateway._
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.libs.ws.WSResponse

import java.net.URLEncoder
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object SearchEngineHelpers {
  def enc(s: String): String = URLEncoder.encode(s, "UTF-8")
  def queryString(params: Seq[(String, String)]): String = params.collect {
    case (k, v) if v.nonEmpty => s"${enc(k)}=${enc(v)}"
  }.mkString("&")
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/////////                                          Staan.ai (Qwant)                                      ///////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

object StaanApi {
  val baseUrl = "https://api.staan.ai"
}

class StaanApi(baseUrl: String = StaanApi.baseUrl, token: String, timeout: FiniteDuration = 30.seconds, env: Env) {
  def rawCall(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[WSResponse] = {
    val url = s"${baseUrl}${path}"
    ProviderHelpers.logCall("Staan", method, url, body)(env)
    env.Ws
      .url(url)
      .withHttpHeaders(
        "Authorization" -> s"Bearer ${token}",
        "Accept" -> "application/json",
      ).applyOnWithOpt(body) {
        case (builder, b) => builder.addHttpHeaders("Content-Type" -> "application/json").withBody(b)
      }
      .withMethod(method)
      .withRequestTimeout(timeout)
      .execute()
  }
}

case class StaanSearchOptions(raw: JsObject) {
  lazy val market: Option[String] = raw.select("market").asOptString
  lazy val count: Option[Int] = raw.select("count").asOpt[Int]
  lazy val minScore: Option[Double] = raw.select("min_score").asOpt[Double]
  lazy val extraSnippets: Option[Boolean] = raw.select("extra_snippets").asOpt[Boolean]
  lazy val fullContent: Option[String] = raw.select("full_content").asOptString
}

object StaanSearchOptions {
  def fromJson(raw: JsObject): StaanSearchOptions = StaanSearchOptions(raw)
}

class StaanSearchClient(api: StaanApi, options: StaanSearchOptions, id: String) extends SearchEngineClient {
  override def search(opts: SearchEngineSearchOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, SearchEngineResponse]] = {
    val body = Json.obj("q" -> opts.query)
      .applyOnWithOpt(opts.market.orElse(options.market).orElse("fr-FR".some)) { case (o, v) => o ++ Json.obj("market" -> v) }
      .applyOnWithOpt(opts.maxResults.orElse(options.count)) { case (o, v) => o ++ Json.obj("count" -> v) }
      .applyOnWithOpt(opts.offset) { case (o, v) => o ++ Json.obj("offset" -> v) }
      .applyOnWithOpt(options.minScore) { case (o, v) => o ++ Json.obj("min_score" -> v) }
      .applyOnWithOpt(options.extraSnippets) { case (o, v) => o ++ Json.obj("extra_snippets" -> v) }
      .applyOnWithOpt(options.fullContent) { case (o, v) => o ++ Json.obj("full_content" -> v) }
      .applyOnWithOpt(opts.includeDomains.some.filter(_.nonEmpty)) { case (o, v) => o ++ Json.obj("include_domains" -> v) }
      .applyOnWithOpt(opts.excludeDomains.some.filter(_.nonEmpty)) { case (o, v) => o ++ Json.obj("exclude_domains" -> v) }
    api.rawCall("POST", "/v2/search/web", body.some).map { resp =>
      if (resp.status == 200 || resp.status == 201) {
        val json = resp.json
        val results = json.at("web.results").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map { r =>
          SearchEngineResult(
            title = r.select("title").asOpt[String].getOrElse(""),
            url = r.select("url").asOpt[String].getOrElse(""),
            snippet = r.select("snippet").asOpt[String].getOrElse(""),
            score = None,
            publishedDate = r.select("published_date").asOptString,
            raw = r,
          )
        }
        SearchEngineResponse("staan", opts.query, None, results, json.asOpt[JsObject].getOrElse(Json.obj())).right
      } else {
        Left(Json.obj("error" -> "bad response from staan", "status" -> resp.status, "body" -> resp.body))
      }
    }
  }
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/////////                                              Tavily                                            ///////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

object TavilyApi {
  val baseUrl = "https://api.tavily.com"
}

class TavilyApi(baseUrl: String = TavilyApi.baseUrl, token: String, timeout: FiniteDuration = 30.seconds, env: Env) {
  def rawCall(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[WSResponse] = {
    val url = s"${baseUrl}${path}"
    ProviderHelpers.logCall("Tavily", method, url, body)(env)
    env.Ws
      .url(url)
      .withHttpHeaders(
        "Authorization" -> s"Bearer ${token}",
        "Accept" -> "application/json",
      ).applyOnWithOpt(body) {
        case (builder, b) => builder.addHttpHeaders("Content-Type" -> "application/json").withBody(b)
      }
      .withMethod(method)
      .withRequestTimeout(timeout)
      .execute()
  }
}

case class TavilySearchOptions(raw: JsObject) {
  lazy val searchDepth: Option[String] = raw.select("search_depth").asOptString
  lazy val topic: Option[String] = raw.select("topic").asOptString
  lazy val includeAnswer: Option[Boolean] = raw.select("include_answer").asOpt[Boolean]
  lazy val maxResults: Option[Int] = raw.select("max_results").asOpt[Int]
}

object TavilySearchOptions {
  def fromJson(raw: JsObject): TavilySearchOptions = TavilySearchOptions(raw)
}

class TavilySearchClient(api: TavilyApi, options: TavilySearchOptions, id: String) extends SearchEngineClient {
  override def search(opts: SearchEngineSearchOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, SearchEngineResponse]] = {
    val body = Json.obj("query" -> opts.query)
      .applyOnWithOpt(opts.maxResults.orElse(options.maxResults)) { case (o, v) => o ++ Json.obj("max_results" -> v) }
      .applyOnWithOpt(options.searchDepth) { case (o, v) => o ++ Json.obj("search_depth" -> v) }
      .applyOnWithOpt(options.topic) { case (o, v) => o ++ Json.obj("topic" -> v) }
      .applyOnWithOpt(options.includeAnswer) { case (o, v) => o ++ Json.obj("include_answer" -> v) }
      .applyOnWithOpt(opts.includeDomains.some.filter(_.nonEmpty)) { case (o, v) => o ++ Json.obj("include_domains" -> v) }
      .applyOnWithOpt(opts.excludeDomains.some.filter(_.nonEmpty)) { case (o, v) => o ++ Json.obj("exclude_domains" -> v) }
    api.rawCall("POST", "/search", body.some).map { resp =>
      if (resp.status == 200) {
        val json = resp.json
        val results = json.select("results").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map { r =>
          SearchEngineResult(
            title = r.select("title").asOpt[String].getOrElse(""),
            url = r.select("url").asOpt[String].getOrElse(""),
            snippet = r.select("content").asOpt[String].getOrElse(""),
            score = r.select("score").asOpt[Double],
            publishedDate = r.select("published_date").asOptString,
            raw = r,
          )
        }
        SearchEngineResponse("tavily", opts.query, json.select("answer").asOptString, results, json.asOpt[JsObject].getOrElse(Json.obj())).right
      } else {
        Left(Json.obj("error" -> "bad response from tavily", "status" -> resp.status, "body" -> resp.body))
      }
    }
  }
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/////////                                              Brave                                             ///////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

object BraveSearchApi {
  val baseUrl = "https://api.search.brave.com"
}

class BraveSearchApi(baseUrl: String = BraveSearchApi.baseUrl, token: String, timeout: FiniteDuration = 30.seconds, env: Env) {
  def rawGet(path: String)(implicit ec: ExecutionContext): Future[WSResponse] = {
    val url = s"${baseUrl}${path}"
    ProviderHelpers.logCall("Brave", "GET", url, None)(env)
    env.Ws
      .url(url)
      .withHttpHeaders(
        "X-Subscription-Token" -> token,
        "Accept" -> "application/json",
      )
      .withMethod("GET")
      .withRequestTimeout(timeout)
      .execute()
  }
}

case class BraveSearchOptions(raw: JsObject) {
  lazy val country: Option[String] = raw.select("country").asOptString
  lazy val searchLang: Option[String] = raw.select("search_lang").asOptString
  lazy val safeSearch: Option[String] = raw.select("safesearch").asOptString
}

object BraveSearchOptions {
  def fromJson(raw: JsObject): BraveSearchOptions = BraveSearchOptions(raw)
}

class BraveSearchClient(api: BraveSearchApi, options: BraveSearchOptions, id: String) extends SearchEngineClient {
  override def search(opts: SearchEngineSearchOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, SearchEngineResponse]] = {
    val params = Seq(
      "q" -> opts.query,
      "count" -> opts.maxResults.map(_.toString).getOrElse(""),
      "offset" -> opts.offset.map(_.toString).getOrElse(""),
      "country" -> options.country.getOrElse(""),
      "search_lang" -> options.searchLang.getOrElse(""),
      "safesearch" -> opts.safeSearch.orElse(options.safeSearch).getOrElse(""),
    )
    api.rawGet(s"/res/v1/web/search?${SearchEngineHelpers.queryString(params)}").map { resp =>
      if (resp.status == 200) {
        val json = resp.json
        val results = json.at("web.results").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map { r =>
          SearchEngineResult(
            title = r.select("title").asOpt[String].getOrElse(""),
            url = r.select("url").asOpt[String].getOrElse(""),
            snippet = r.select("description").asOpt[String].getOrElse(""),
            score = None,
            publishedDate = r.select("page_age").asOptString.orElse(r.select("age").asOptString),
            raw = r,
          )
        }
        SearchEngineResponse("brave", opts.query, None, results, json.asOpt[JsObject].getOrElse(Json.obj())).right
      } else {
        Left(Json.obj("error" -> "bad response from brave", "status" -> resp.status, "body" -> resp.body))
      }
    }
  }
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/////////                                              SearXNG                                           ///////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

object SearXNGApi {
  val baseUrl = "http://localhost:8080"
}

class SearXNGApi(baseUrl: String = SearXNGApi.baseUrl, token: String, timeout: FiniteDuration = 30.seconds, env: Env) {
  def rawGet(path: String)(implicit ec: ExecutionContext): Future[WSResponse] = {
    val url = s"${baseUrl}${path}"
    ProviderHelpers.logCall("SearXNG", "GET", url, None)(env)
    env.Ws
      .url(url)
      .withHttpHeaders("Accept" -> "application/json")
      // SearXNG instances behind an auth proxy can use a bearer token, harmless otherwise
      .applyOnIf(token.nonEmpty && token != "xxx") { _.addHttpHeaders("Authorization" -> s"Bearer ${token}") }
      .withMethod("GET")
      .withRequestTimeout(timeout)
      .execute()
  }
}

case class SearXNGSearchOptions(raw: JsObject) {
  lazy val engines: Option[String] = raw.select("engines").asOptString
  lazy val categories: Option[String] = raw.select("categories").asOptString
  lazy val language: Option[String] = raw.select("language").asOptString
}

object SearXNGSearchOptions {
  def fromJson(raw: JsObject): SearXNGSearchOptions = SearXNGSearchOptions(raw)
}

class SearXNGSearchClient(api: SearXNGApi, options: SearXNGSearchOptions, id: String) extends SearchEngineClient {
  override def search(opts: SearchEngineSearchOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, SearchEngineResponse]] = {
    val params = Seq(
      "q" -> opts.query,
      "format" -> "json",
      "pageno" -> opts.offset.map(o => (o + 1).toString).getOrElse(""),
      "language" -> options.language.orElse(opts.market).getOrElse(""),
      "engines" -> options.engines.getOrElse(""),
      "categories" -> options.categories.getOrElse(""),
    )
    api.rawGet(s"/search?${SearchEngineHelpers.queryString(params)}").map { resp =>
      if (resp.status == 200) {
        val json = resp.json
        val all = json.select("results").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
        val limited = opts.maxResults.map(n => all.take(n)).getOrElse(all)
        val results = limited.map { r =>
          SearchEngineResult(
            title = r.select("title").asOpt[String].getOrElse(""),
            url = r.select("url").asOpt[String].getOrElse(""),
            snippet = r.select("content").asOpt[String].getOrElse(""),
            score = r.select("score").asOpt[Double],
            publishedDate = r.select("publishedDate").asOptString,
            raw = r,
          )
        }
        SearchEngineResponse("searxng", opts.query, json.select("answers").asOpt[Seq[String]].flatMap(_.headOption), results, json.asOpt[JsObject].getOrElse(Json.obj())).right
      } else {
        Left(Json.obj("error" -> "bad response from searxng", "status" -> resp.status, "body" -> resp.body))
      }
    }
  }
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/////////                                       Google Custom Search                                     ///////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

object GoogleCseApi {
  val baseUrl = "https://www.googleapis.com"
}

class GoogleCseApi(baseUrl: String = GoogleCseApi.baseUrl, token: String, timeout: FiniteDuration = 30.seconds, env: Env) {
  val apiKey: String = token
  def rawGet(path: String)(implicit ec: ExecutionContext): Future[WSResponse] = {
    val url = s"${baseUrl}${path}"
    ProviderHelpers.logCall("GoogleCse", "GET", url, None)(env)
    env.Ws
      .url(url)
      .withHttpHeaders("Accept" -> "application/json")
      .withMethod("GET")
      .withRequestTimeout(timeout)
      .execute()
  }
}

case class GoogleCseSearchOptions(raw: JsObject) {
  lazy val cx: Option[String] = raw.select("cx").asOptString
  lazy val lr: Option[String] = raw.select("lr").asOptString
}

object GoogleCseSearchOptions {
  def fromJson(raw: JsObject): GoogleCseSearchOptions = GoogleCseSearchOptions(raw)
}

class GoogleCseSearchClient(api: GoogleCseApi, options: GoogleCseSearchOptions, id: String) extends SearchEngineClient {
  override def search(opts: SearchEngineSearchOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, SearchEngineResponse]] = {
    val params = Seq(
      "key" -> api.apiKey,
      "cx" -> options.cx.getOrElse(""),
      "q" -> opts.query,
      "num" -> opts.maxResults.map(n => math.min(n, 10).toString).getOrElse(""),
      "start" -> opts.offset.map(o => (o + 1).toString).getOrElse(""),
      "lr" -> options.lr.getOrElse(""),
    )
    api.rawGet(s"/customsearch/v1?${SearchEngineHelpers.queryString(params)}").map { resp =>
      if (resp.status == 200) {
        val json = resp.json
        val results = json.select("items").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map { r =>
          SearchEngineResult(
            title = r.select("title").asOpt[String].getOrElse(""),
            url = r.select("link").asOpt[String].getOrElse(""),
            snippet = r.select("snippet").asOpt[String].getOrElse(""),
            score = None,
            publishedDate = None,
            raw = r,
          )
        }
        SearchEngineResponse("google", opts.query, None, results, json.asOpt[JsObject].getOrElse(Json.obj())).right
      } else {
        Left(Json.obj("error" -> "bad response from google custom search", "status" -> resp.status, "body" -> resp.body))
      }
    }
  }
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/////////                                             SearchApi                                          ///////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

object SearchApiApi {
  val baseUrl = "https://www.searchapi.io"
}

class SearchApiApi(baseUrl: String = SearchApiApi.baseUrl, token: String, timeout: FiniteDuration = 30.seconds, env: Env) {
  def rawGet(path: String)(implicit ec: ExecutionContext): Future[WSResponse] = {
    val url = s"${baseUrl}${path}"
    ProviderHelpers.logCall("SearchApi", "GET", url, None)(env)
    env.Ws
      .url(url)
      .withHttpHeaders(
        "Authorization" -> s"Bearer ${token}",
        "Accept" -> "application/json",
      )
      .withMethod("GET")
      .withRequestTimeout(timeout)
      .execute()
  }
}

case class SearchApiSearchOptions(raw: JsObject) {
  lazy val engine: String = raw.select("engine").asOptString.getOrElse("google")
  lazy val gl: Option[String] = raw.select("gl").asOptString
  lazy val hl: Option[String] = raw.select("hl").asOptString
}

object SearchApiSearchOptions {
  def fromJson(raw: JsObject): SearchApiSearchOptions = SearchApiSearchOptions(raw)
}

class SearchApiSearchClient(api: SearchApiApi, options: SearchApiSearchOptions, id: String) extends SearchEngineClient {
  override def search(opts: SearchEngineSearchOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, SearchEngineResponse]] = {
    val params = Seq(
      "engine" -> options.engine,
      "q" -> opts.query,
      "num" -> opts.maxResults.map(_.toString).getOrElse(""),
      "gl" -> options.gl.getOrElse(""),
      "hl" -> options.hl.getOrElse(""),
    )
    api.rawGet(s"/api/v1/search?${SearchEngineHelpers.queryString(params)}").map { resp =>
      if (resp.status == 200) {
        val json = resp.json
        val results = json.select("organic_results").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map { r =>
          SearchEngineResult(
            title = r.select("title").asOpt[String].getOrElse(""),
            url = r.select("link").asOpt[String].getOrElse(""),
            snippet = r.select("snippet").asOpt[String].getOrElse(""),
            score = None,
            publishedDate = r.select("date").asOptString,
            raw = r,
          )
        }
        SearchEngineResponse("searchapi", opts.query, None, results, json.asOpt[JsObject].getOrElse(Json.obj())).right
      } else {
        Left(Json.obj("error" -> "bad response from searchapi", "status" -> resp.status, "body" -> resp.body))
      }
    }
  }
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/////////                                            DuckDuckGo                                          ///////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

// NOTE: DuckDuckGo only exposes a free "Instant Answer" API (no full web result list). We map the abstract and the
// related topics to a best-effort list of results. It is not a full web search engine like the other providers.
object DuckDuckGoApi {
  val baseUrl = "https://api.duckduckgo.com"
}

class DuckDuckGoApi(baseUrl: String = DuckDuckGoApi.baseUrl, token: String, timeout: FiniteDuration = 30.seconds, env: Env) {
  def rawGet(path: String)(implicit ec: ExecutionContext): Future[WSResponse] = {
    val url = s"${baseUrl}${path}"
    ProviderHelpers.logCall("DuckDuckGo", "GET", url, None)(env)
    env.Ws
      .url(url)
      .withHttpHeaders("Accept" -> "application/json")
      .withMethod("GET")
      .withRequestTimeout(timeout)
      .execute()
  }
}

case class DuckDuckGoSearchOptions(raw: JsObject)

object DuckDuckGoSearchOptions {
  def fromJson(raw: JsObject): DuckDuckGoSearchOptions = DuckDuckGoSearchOptions(raw)
}

class DuckDuckGoSearchClient(api: DuckDuckGoApi, options: DuckDuckGoSearchOptions, id: String) extends SearchEngineClient {

  private def flattenTopics(items: Seq[JsObject]): Seq[JsObject] = items.flatMap { item =>
    item.select("Topics").asOpt[Seq[JsObject]] match {
      case Some(sub) => flattenTopics(sub)
      case None => Seq(item)
    }
  }

  override def search(opts: SearchEngineSearchOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, SearchEngineResponse]] = {
    val params = Seq(
      "q" -> opts.query,
      "format" -> "json",
      "no_html" -> "1",
      "no_redirect" -> "1",
      "skip_disambig" -> "1",
    )
    api.rawGet(s"/?${SearchEngineHelpers.queryString(params)}").map { resp =>
      if (resp.status == 200) {
        try {
          val json = resp.json
          val abstractResults: Seq[SearchEngineResult] = json.select("AbstractText").asOptString.filter(_.nonEmpty).map { txt =>
            SearchEngineResult(
              title = json.select("Heading").asOpt[String].getOrElse(opts.query),
              url = json.select("AbstractURL").asOpt[String].getOrElse(""),
              snippet = txt,
              raw = Json.obj("Heading" -> json.select("Heading").asOpt[String], "AbstractSource" -> json.select("AbstractSource").asOpt[String]),
            )
          }.toSeq
          val topicResults: Seq[SearchEngineResult] = flattenTopics(json.select("RelatedTopics").asOpt[Seq[JsObject]].getOrElse(Seq.empty)).map { r =>
            SearchEngineResult(
              title = r.select("Text").asOpt[String].getOrElse(""),
              url = r.select("FirstURL").asOpt[String].getOrElse(""),
              snippet = r.select("Text").asOpt[String].getOrElse(""),
              raw = r,
            )
          }.filter(_.url.nonEmpty)
          val all = abstractResults ++ topicResults
          val results = opts.maxResults.map(n => all.take(n)).getOrElse(all)
          SearchEngineResponse("duckduckgo", opts.query, json.select("AbstractText").asOptString.filter(_.nonEmpty), results, json.asOpt[JsObject].getOrElse(Json.obj())).right
        } catch {
          case t: Throwable => Left(Json.obj("error" -> "bad response from duckduckgo", "status" -> resp.status, "body" -> resp.body))
        }
      } else {
        Left(Json.obj("error" -> "bad response from duckduckgo", "status" -> resp.status, "body" -> resp.body))
      }
    }
  }
}
