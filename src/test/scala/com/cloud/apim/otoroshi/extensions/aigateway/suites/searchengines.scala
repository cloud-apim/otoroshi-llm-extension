package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.{SearchEngineResponse, SearchEngineResult, SearchEngineSearchOptions}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{GenericApiResponseChoiceMessageToolCall, SearchEngine}
import otoroshi.models.EntityLocation
import play.api.libs.json.{JsObject, Json}

class SearchEngineModelSuite extends munit.FunSuite {

  test("SearchEngineSearchOptions reads query and common aliases") {
    val o1 = SearchEngineSearchOptions.format.reads(Json.obj("query" -> "hello", "max_results" -> 7, "include_domains" -> Json.arr("a.com"))).get
    assertEquals(o1.query, "hello")
    assertEquals(o1.maxResults, Some(7))
    assertEquals(o1.includeDomains, Seq("a.com"))
    val o2 = SearchEngineSearchOptions.format.reads(Json.obj("q" -> "world", "count" -> 3, "locale" -> "en-US")).get
    assertEquals(o2.query, "world")
    assertEquals(o2.maxResults, Some(3))
    assertEquals(o2.market, Some("en-US"))
  }

  test("SearchEngineResult.toJson only includes optional fields when present") {
    val minimal = SearchEngineResult("t", "u", "s").toJson
    assertEquals((minimal \ "score").toOption, None)
    assertEquals((minimal \ "published_date").toOption, None)
    val full = SearchEngineResult("t", "u", "s", Some(0.9), Some("2026-01-01")).toJson
    assertEquals((full \ "score").as[Double], 0.9)
    assertEquals((full \ "published_date").as[String], "2026-01-01")
  }

  test("SearchEngineResponse.toJson exposes the normalized shape") {
    val resp = SearchEngineResponse("staan", "q", Some("the answer"), Seq(SearchEngineResult("t", "u", "s")))
    val json = resp.toJson
    assertEquals((json \ "provider").as[String], "staan")
    assertEquals((json \ "query").as[String], "q")
    assertEquals((json \ "answer").as[String], "the answer")
    assertEquals((json \ "results").as[Seq[JsObject]].size, 1)
  }

  test("tool-call parser routes search___ tool names to the search engine path") {
    val se = GenericApiResponseChoiceMessageToolCall(Json.obj("id" -> "call_1", "function" -> Json.obj("name" -> "search___search-engine_x", "arguments" -> """{"query":"hi"}""")))
    assert(se.isSearchEngine, "search tool should be flagged isSearchEngine")
    assert(!se.isWasm, "search tool must not be wasm")
    assert(!se.isMcp, "search tool must not be mcp")
    assertEquals(se.function.searchEngineId, "search-engine_x")

    val mcp = GenericApiResponseChoiceMessageToolCall(Json.obj("id" -> "call_2", "function" -> Json.obj("name" -> "mcp___0___web_search", "arguments" -> "{}")))
    assert(mcp.isMcp)
    assert(!mcp.isSearchEngine)

    val wasm = GenericApiResponseChoiceMessageToolCall(Json.obj("id" -> "call_3", "function" -> Json.obj("name" -> "wasm___mytool", "arguments" -> "{}")))
    assert(wasm.isWasm)
    assert(!wasm.isSearchEngine)
  }

  test("SearchEngine entity json round-trips") {
    val se = SearchEngine(
      location = EntityLocation.default,
      id = "search-engine_test",
      name = "Test",
      description = "desc",
      tags = Seq("a"),
      metadata = Map("k" -> "v"),
      provider = "staan",
      config = Json.obj(
        "connection" -> Json.obj("base_url" -> "https://api.staan.ai", "token" -> "xxx"),
        "options" -> Json.obj("market" -> "fr-FR")
      ),
    )
    val parsed = SearchEngine.format.reads(se.json).get
    assertEquals(parsed.id, se.id)
    assertEquals(parsed.provider, "staan")
    assertEquals((parsed.config \ "connection" \ "base_url").as[String], "https://api.staan.ai")
  }
}
