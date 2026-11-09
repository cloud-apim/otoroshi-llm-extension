package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.LlmExtensionOneOtoroshiServerPerSuite
import com.cloud.apim.otoroshi.extensions.aigateway.entities.LlmToolFunctionBackendOptions
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*
import reactor.core.publisher.Mono

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

// A tool function with the `http` backend fills the `${name}` placeholders of its url, headers and body with the
// arguments the model sent, whatever their json type
class HttpToolFunctionsSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  // answers what it received
  val (echoPort, _) = createTestServer("http-tool-functions-echo", (req, response) => {
    req.receive().aggregate().asString().defaultIfEmpty("").flatMap { body =>
      response
        .status(200)
        .addHeader("Content-Type", "application/json")
        .sendString(Mono.just(Json.obj(
          "method" -> req.method().name(),
          "uri" -> req.uri(),
          "query" -> req.requestHeaders().get("X-Query"),
          "body" -> body,
        ).stringify))
        .`then`()
    }
  })

  def call(options: JsObject, arguments: String): JsValue = {
    given env: Env = otoroshi.env
    given ec: ExecutionContext = env.otoroshiExecutionContext
    Json.parse(LlmToolFunctionBackendOptions.Http(options).call(arguments, TypedMap.empty).awaitf(10.seconds))
  }

  test("the arguments that are not strings fill the placeholders with their json") {
    val echo = call(Json.obj(
      "url" -> s"http://localhost:$echoPort/v1/forecast?latitude=$${latitude}&longitude=$${longitude}&current=$${current}",
      "method" -> "POST",
      "headers" -> Json.obj("Content-Type" -> "application/json", "X-Query" -> "${units}"),
      "body" -> "{\"days\": ${days}, \"tags\": ${tags}, \"where\": ${where}, \"nothing\": ${nothing}, \"units\": \"${units}\"}",
    ), Json.obj(
      "latitude" -> 48.85, "longitude" -> 2.35, "current" -> true, "days" -> 3, "units" -> "celsius",
      "tags" -> Json.arr("rain", 1), "where" -> Json.obj("city" -> "Paris"), "nothing" -> JsNull,
    ).stringify)
    assertEquals(echo.select("method").asString, "POST")
    assertEquals(echo.select("uri").asString, "/v1/forecast?latitude=48.85&longitude=2.35&current=true")
    assertEquals(echo.select("query").asString, "celsius")
    assertEquals(Json.parse(echo.select("body").asString), Json.obj(
      "days" -> 3, "tags" -> Json.arr("rain", 1), "where" -> Json.obj("city" -> "Paris"), "nothing" -> JsNull, "units" -> "celsius",
    ))
  }

  test("a string holding quotes or a backslash is put as it is") {
    val text = "say \"hi\" \\o/"
    val echo = call(Json.obj(
      "url" -> s"http://localhost:$echoPort/search",
      "method" -> "POST",
      "headers" -> Json.obj("X-Query" -> "${q}"),
      "body" -> "${q}",
    ), Json.obj("q" -> text).stringify)
    assertEquals(echo.select("query").asString, text)
    assertEquals(echo.select("body").asString, text)
  }

  test("arguments that are not a json object are given as `arguments`, a placeholder without argument says so") {
    val echo = call(Json.obj(
      "url" -> s"http://localhost:$echoPort/raw",
      "method" -> "POST",
      "body" -> "${arguments} ${missing}",
    ), "just some text")
    assertEquals(echo.select("body").asString, "just some text no-params-missing")
  }
}
