package com.cloud.apim.otoroshi.extensions.aigateway.suites

import org.apache.pekko.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.LlmExtensionOneOtoroshiServerPerSuite
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import otoroshi.models.EntityLocation
import otoroshi.next.models.*
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.{OpenAiCompatProxy, OpenAiResponsesProxy, OpenResponseCompatProxy}
import play.api.libs.json.{JsObject, JsValue, Json}
import reactor.core.publisher.Mono

import java.util.UUID
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.DurationInt

// End to end tests for file content parts: a file sent to any of the chat/responses proxies must
// reach the provider (see issue #188 where it was silently replaced by an empty text part)
class FileContentPartsProxiesSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  val pdfBytes: ByteString = "%PDF-1.4 fake pdf content".byteString
  val pdfBase64: String = pdfBytes.encodeBase64.utf8String

  val bodies = new TrieMap[String, JsValue]()

  val (openaiPort, _) = createTestServerWithRoutes("openai", routes => routes.post("/chat/completions", (req, response) => {
    req.receive().retain().asString().flatMap { body =>
      bodies.put("latest", body.parseJson)
      response
        .status(200)
        .addHeader("Content-Type", "application/json")
        .sendString(Mono.just(
          s"""{
             |  "id": "chatcmpl-123",
             |  "object": "chat.completion",
             |  "created": 1700000000,
             |  "model": "gpt-4.1",
             |  "choices": [{
             |    "index": 0,
             |    "message": { "role": "assistant", "content": "the document is about grass" },
             |    "finish_reason": "stop"
             |  }],
             |  "usage": { "prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15 }
             |}""".stripMargin))
    }
  }))

  def openaiProvider(port: Int): AiProvider = AiProvider(
    id = UUID.randomUUID().toString,
    name = "fake openai",
    provider = "openai",
    connection = Json.obj(
      "base_url" -> s"http://localhost:${port}",
      "token" -> "xxx",
      "timeout" -> 30000,
    ),
    options = Json.obj("model" -> "gpt-4.1"),
  )

  def routeWith(plugin: String, path: String, providerId: String): NgRoute = NgRoute(
    location = EntityLocation.default,
    id = UUID.randomUUID().toString,
    name = s"test route ${path}",
    description = s"test route ${path}",
    tags = Seq.empty,
    metadata = Map.empty,
    enabled = true,
    debugFlow = false,
    capture = false,
    exportReporting = false,
    frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath(s"files.oto.tools${path}"))),
    backend = NgBackend.empty.copy(targets = Seq(NgTarget.default)),
    plugins = NgPlugins(Seq(NgPluginInstance(
      plugin = s"cp:${plugin}",
      config = NgPluginInstanceConfig(Json.obj("refs" -> Json.arr(providerId)))
    )))
  )

  val provider = openaiProvider(openaiPort)
  val chatRoute = routeWith(classOf[OpenAiCompatProxy].getName, "/chat", provider.id)
  val openResponsesRoute = routeWith(classOf[OpenResponseCompatProxy].getName, "/openresponses", provider.id)
  val openaiResponsesRoute = routeWith(classOf[OpenAiResponsesProxy].getName, "/oairesponses", provider.id)

  override def beforeAll(): Unit = {
    super.beforeAll()
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "providers").upsertEntity(provider).awaitf(10.seconds)
    Seq(chatRoute, openResponsesRoute, openaiResponsesRoute).foreach { route =>
      client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(route).awaitf(10.seconds)
    }
    await(2.seconds)
  }

  def assertFileHasBeenForwarded(name: String): Unit = {
    val body = bodies("latest")
    val parts = body.at("messages.0.content").as[Seq[JsObject]]
    val filePart = parts.find(_.select("type").asOptString.contains("file"))
      .getOrElse(fail(s"[${name}] no file content part in the provider request: ${body.stringify}"))
    assertEquals(filePart.at("file.filename").asString, "releve-informations.pdf", s"[${name}] bad filename")
    assertEquals(filePart.at("file.file_data").asString, s"data:application/pdf;base64,${pdfBase64}", s"[${name}] bad file payload")
    assert(parts.exists(p => p.select("text").asOptString.contains("summarize this document")), s"[${name}] text part is missing")
  }

  test("a `file` part sent to the chat/completions proxy is forwarded to the provider") {
    bodies.clear()
    val res = client.call("POST", s"http://files.oto.tools:${port}/chat", Map.empty, Some(Json.obj(
      "model" -> "gpt-4.1",
      "messages" -> Json.arr(Json.obj(
        "role" -> "user",
        "content" -> Json.arr(
          Json.obj("type" -> "text", "text" -> "summarize this document"),
          Json.obj("type" -> "file", "file" -> Json.obj(
            "filename" -> "releve-informations.pdf",
            "file_data" -> s"data:application/pdf;base64,${pdfBase64}",
          )),
        )
      ))
    ))).awaitf(30.seconds)
    assertEquals(res.status, 200, s"chat/completions proxy did not respond with 200: ${res.body}")
    assertFileHasBeenForwarded("chat/completions")
  }

  test("an `input_file` part sent to the OpenResponse proxy is forwarded to the provider") {
    bodies.clear()
    val res = client.call("POST", s"http://files.oto.tools:${port}/openresponses", Map.empty, Some(Json.obj(
      "model" -> "gpt-4.1",
      "input" -> Json.arr(Json.obj(
        "role" -> "user",
        "content" -> Json.arr(
          Json.obj("type" -> "input_text", "text" -> "summarize this document"),
          Json.obj("type" -> "input_file", "filename" -> "releve-informations.pdf", "file_data" -> s"data:application/pdf;base64,${pdfBase64}"),
        )
      ))
    ))).awaitf(30.seconds)
    assertEquals(res.status, 200, s"OpenResponse proxy did not respond with 200: ${res.body}")
    assertFileHasBeenForwarded("openresponse")
  }

  test("an `input_file` part sent to the OpenAI Responses proxy is forwarded to the provider") {
    bodies.clear()
    val res = client.call("POST", s"http://files.oto.tools:${port}/oairesponses", Map.empty, Some(Json.obj(
      "model" -> "gpt-4.1",
      "input" -> Json.arr(Json.obj(
        "type" -> "message",
        "role" -> "user",
        "content" -> Json.arr(
          Json.obj("type" -> "input_text", "text" -> "summarize this document"),
          Json.obj("type" -> "input_file", "filename" -> "releve-informations.pdf", "file_data" -> s"data:application/pdf;base64,${pdfBase64}"),
        )
      ))
    ))).awaitf(30.seconds)
    assertEquals(res.status, 200, s"OpenAI Responses proxy did not respond with 200: ${res.body}")
    assertFileHasBeenForwarded("openai responses")
  }
}
