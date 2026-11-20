package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.*
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import otoroshi.models.EntityLocation
import otoroshi.next.models.*
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.OpenAiCompatProxy
import play.api.libs.json.*
import reactor.core.publisher.Flux

import java.util.UUID
import scala.concurrent.duration.DurationInt

// A streamed answer keeps its text whatever chunk carries the `usage`. OpenAI sends it alone, in a last chunk
// with `choices: []`; Gemini's OpenAI compatible endpoint puts it on every chunk, its text chunks included. A
// chunk is read for the consumption, then dropped only when the usage is all it carries.
class StreamUsageChunksSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  private def chunk(choices: JsArray, usage: Option[Int]): String = "data: " + Json.obj(
    "id" -> "chatcmpl-1", "object" -> "chat.completion.chunk", "created" -> 1700000000, "model" -> "a-model",
    "choices" -> choices,
  ).applyOnWithOpt(usage) {
    case (obj, completion) => obj ++ Json.obj("usage" -> Json.obj("prompt_tokens" -> 6, "completion_tokens" -> completion, "total_tokens" -> (6 + completion)))
  }.stringify + "\n\n"

  private def choice(delta: JsObject, reason: Option[String] = None): JsArray = Json.arr(Json.obj(
    "index" -> 0, "delta" -> delta, "finish_reason" -> reason.map(JsString.apply).getOrElse(JsNull).asValue
  ))

  // Gemini: a usage on each text chunk
  val (geminiPort, _) = createTestServerWithRoutes("stream-usage-gemini", routes => routes
    .post("/v1beta/openai/chat/completions", (req, response) => req.receive().aggregate().asString().flatMap { _ =>
      response.status(200).addHeader("Content-Type", "text/event-stream").sendString(Flux.just(
        chunk(choice(Json.obj("role" -> "assistant", "content" -> "Hello")), Some(1)),
        chunk(choice(Json.obj("content" -> " world"), Some("stop")), Some(2)),
        "data: [DONE]\n\n",
      )).`then`()
    })
  )

  // OpenAI: text chunks without usage, then a usage only chunk
  val (openaiPort, _) = createTestServerWithRoutes("stream-usage-openai", routes => routes
    .post("/v1/chat/completions", (req, response) => req.receive().aggregate().asString().flatMap { _ =>
      response.status(200).addHeader("Content-Type", "text/event-stream").sendString(Flux.just(
        chunk(choice(Json.obj("role" -> "assistant", "content" -> "Hello")), None),
        chunk(choice(Json.obj("content" -> " world"), Some("stop")), None),
        chunk(Json.arr(), Some(2)),
        "data: [DONE]\n\n",
      )).`then`()
    })
  )

  lazy val gemini: AiProvider = AiProvider(
    id = s"provider_${UUID.randomUUID()}",
    name = "stream usage gemini",
    provider = "gemini",
    connection = Json.obj("base_url" -> s"http://localhost:${geminiPort}/v1beta/openai", "token" -> "test", "timeout" -> 10000),
    options = Json.obj("model" -> "a-model"),
  )

  lazy val openai: AiProvider = AiProvider(
    id = s"provider_${UUID.randomUUID()}",
    name = "stream usage openai",
    provider = "openai",
    connection = Json.obj("base_url" -> s"http://localhost:${openaiPort}/v1", "token" -> "sk-test", "timeout" -> 10000),
    options = Json.obj("model" -> "a-model"),
  )

  def route(name: String, target: AiProvider): NgRoute = NgRoute(
    location = EntityLocation.default,
    id = UUID.randomUUID().toString,
    name = s"stream usage ${name}",
    description = s"stream usage ${name}",
    tags = Seq.empty,
    metadata = Map.empty,
    enabled = true,
    debugFlow = false,
    capture = false,
    exportReporting = false,
    frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath(s"${name}.streamusage.oto.tools"))),
    backend = NgBackend.empty.copy(targets = Seq(NgTarget.default)),
    plugins = NgPlugins(Seq(NgPluginInstance(
      plugin = s"cp:${classOf[OpenAiCompatProxy].getName}",
      config = NgPluginInstanceConfig(Json.obj("refs" -> Json.arr(target.id)))
    )))
  )

  lazy val setup: Unit = {
    Seq(gemini, openai).foreach { p =>
      assert(client.forLlmEntity("providers").upsertEntity(p).awaitf(10.seconds).createdOrUpdated, s"${p.name} should be saved")
    }
    Seq("gemini" -> gemini, "openai" -> openai).foreach { case (name, target) =>
      client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(route(name, target)).awaitf(10.seconds)
    }
    await(5.seconds)
  }

  // the text of a streamed answer, put back together from the deltas
  def streamedText(name: String): String = {
    setup
    val body = Json.obj("model" -> "a-model", "stream" -> true, "messages" -> Json.arr(Json.obj("role" -> "user", "content" -> "Say hello")))
    val resp = client.call("POST", s"http://${name}.streamusage.oto.tools:${port}/", Map.empty, Some(body)).awaitf(30.seconds)
    assertEquals(resp.status, 200, resp.body)
    resp.body.split("\n").toSeq
      .filter(_.startsWith("data: {"))
      .map(l => Json.parse(l.stripPrefix("data: ")))
      .flatMap(_.select("choices").asOpt[Seq[JsObject]].getOrElse(Seq.empty))
      .flatMap(_.at("delta.content").asOpt[String])
      .mkString
  }

  test("a Gemini stream keeps the text of the chunks that carry a usage") {
    assertEquals(streamedText("gemini"), "Hello world")
  }

  test("an OpenAI stream still streams its text, its usage only chunk read and dropped") {
    assertEquals(streamedText("openai"), "Hello world")
  }
}
