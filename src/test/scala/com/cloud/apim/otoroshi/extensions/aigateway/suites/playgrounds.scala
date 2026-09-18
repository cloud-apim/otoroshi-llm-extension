package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.LlmExtensionOneOtoroshiServerPerSuite
import com.cloud.apim.otoroshi.extensions.aigateway.entities.OcrModel
import otoroshi.models.EntityLocation
import otoroshi.next.models.*
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.OpenAICompatOcr
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.libs.ws.WSResponse
import reactor.core.publisher.Mono

import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.concurrent.duration.DurationInt

// The playgrounds of AI Studio call the models a workspace serves outside of chat on their own endpoint.
// For that, naming a model has to pick the entity serving it — which ocr calls did not do.
class PlaygroundsSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  // one server, two base urls: whichever entity served the call says so in the extracted text
  val (ocrPort, _) = createTestServerWithRoutes("ocr-playground", routes => routes
    .post("/alpha/ocr", (req, response) => {
      req.receiveContent().ignoreElements().subscribe()
      response.status(200).addHeader("Content-Type", "application/json")
        .sendString(Mono.just("""{"model":"mistral-ocr-latest","pages":[{"index":0,"markdown":"read by alpha"}],"usage_info":{"pages_processed":1}}"""))
    })
    .post("/beta/ocr", (req, response) => {
      req.receiveContent().ignoreElements().subscribe()
      response.status(200).addHeader("Content-Type", "application/json")
        .sendString(Mono.just("""{"model":"mistral-ocr-latest","pages":[{"index":0,"markdown":"read by beta"}],"usage_info":{"pages_processed":1}}"""))
    })
  )

  def ocrModel(id: String, name: String, path: String): OcrModel = OcrModel(
    location = EntityLocation.default,
    id = id,
    name = name,
    description = name,
    tags = Seq.empty,
    metadata = Map.empty,
    provider = "mistral",
    config = Json.obj(
      "connection" -> Json.obj("base_url" -> s"http://localhost:$ocrPort/$path", "token" -> "xxx", "timeout" -> 30000),
      "options" -> Json.obj("model" -> "mistral-ocr-latest"),
    ),
  )

  val alpha: OcrModel = ocrModel("ocr_playground_alpha", "ocr alpha", "alpha")
  val beta: OcrModel = ocrModel("ocr_playground_beta", "ocr beta", "beta")

  lazy val setup: Unit = {
    Seq(alpha, beta).foreach(m => client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "ocr-models").upsertEntity(m).awaitf(10.seconds))
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(NgRoute(
      location = EntityLocation.default,
      id = "route_ocr_playground",
      name = "ocr playground",
      description = "ocr playground",
      tags = Seq.empty,
      metadata = Map.empty,
      enabled = true,
      debugFlow = false,
      capture = false,
      exportReporting = false,
      frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath("ocr-playground.oto.tools/ocr"))),
      backend = NgBackend.empty.copy(targets = Seq(NgTarget.default)),
      plugins = NgPlugins(Seq(NgPluginInstance(
        plugin = s"cp:${classOf[OpenAICompatOcr].getName}",
        config = NgPluginInstanceConfig(Json.obj("refs" -> Json.arr(alpha.id, beta.id))),
      ))),
    )).awaitf(10.seconds)
    await(10.seconds)
  }

  def extract(model: Option[String]): WSResponse = {
    setup
    val body: JsValue = Json.obj("document" -> Json.obj("type" -> "document_url", "document_url" -> "https://oto.tools/invoice.pdf"))
      .applyOnWithOpt(model) { case (obj, m) => obj ++ Json.obj("model" -> m) }
    client.call("POST", s"http://ocr-playground.oto.tools:${port}/ocr", Map.empty, Some(body)).awaitf(30.seconds)
  }

  def text(resp: WSResponse): String = {
    assertEquals(resp.status, 200, resp.body)
    resp.json.select("text").asString
  }

  test("an ocr call goes to the model entity its `model` names") {
    assertEquals(text(extract("ocr_beta/mistral-ocr-latest".some)), "read by beta")
    assertEquals(text(extract(s"${beta.id}###mistral-ocr-latest".some)), "read by beta")
    assertEquals(text(extract("ocr_alpha/mistral-ocr-latest".some)), "read by alpha")
    // the entity keeps the bare model name, the prefix was only there to pick it
    assertEquals(extract("ocr_beta/mistral-ocr-latest".some).json.select("model").asString, "mistral-ocr-latest")
  }

  test("an ocr call naming nothing, or something unknown, goes to the first model of the route") {
    assertEquals(text(extract(None)), "read by alpha")
    assertEquals(text(extract("mistral-ocr-latest".some)), "read by alpha")
    assertEquals(text(extract("nope/mistral-ocr-latest".some)), "read by alpha")
  }

  ///////////////////////////////////////////////////////////////////////////////////////////////////
  // what a workspace offers a playground: one model per endpoint, the audio ones being two models
  ///////////////////////////////////////////////////////////////////////////////////////////////////

  val basic: String = Base64.getEncoder.encodeToString("admin-api-apikey-id:admin-api-apikey-secret".getBytes(StandardCharsets.UTF_8))

  def studio(method: String, path: String, body: JsValue = null): WSResponse =
    client.call(method, s"http://otoroshi-api.oto.tools:$port/api/extensions/cloud-apim/extensions/ai-extension/studio$path", Map("Authorization" -> s"Basic $basic"), Option(body)).awaitf(30.seconds)

  test("a workspace lists a model for each way its connections can be called") {
    val ws = studio("POST", "/workspaces", Json.obj("name" -> "Playgrounds"))
    assertEquals(ws.status, 201, ws.body)
    val wsId = ws.json.select("id").asString
    // no text model: the listing of this workspace never leaves the process
    val created = studio("POST", s"/workspaces/$wsId/providers", Json.obj(
      "kind" -> "openai",
      "name" -> "lab",
      "token" -> "sk-test",
      "modalities" -> Json.obj(
        "text" -> Json.obj("enabled" -> false),
        "embedding" -> Json.obj("enabled" -> true, "model" -> "text-embedding-3-small"),
        "image" -> Json.obj("enabled" -> true, "model" -> "gpt-image-1"),
        "moderation" -> Json.obj("enabled" -> true, "model" -> "omni-moderation-latest"),
        // a voice and a transcription model, which are two different models
        "audio" -> Json.obj("enabled" -> true, "model" -> "gpt-4o-mini-tts", "stt_model" -> "whisper-1"),
      ),
    ))
    assertEquals(created.status, 201, created.body)
    val models = studio("GET", s"/workspaces/$wsId/models").json.select("models").as[Seq[JsObject]]
    assertEquals(models.map(m => (m.select("id").asString, m.select("metadata").select("endpoints").as[Seq[String]])).sortBy(_._1), Seq(
      ("gpt-4o-mini-tts", Seq("audio_speech")),
      ("gpt-image-1", Seq("images_generations")),
      ("omni-moderation-latest", Seq("moderations")),
      ("text-embedding-3-small", Seq("embeddings")),
      ("whisper-1", Seq("audio_transcriptions")),
    ))
    // and what each connection can be asked for, whichever model of its kind the call names: an image
    // entity draws every image model of its provider, not only the one it carries as a default
    val infos = studio("GET", s"/workspaces/$wsId/models").json.select("providers").as[Seq[JsObject]]
    assertEquals(infos.filter(_.select("modality").asString != "text").map(i => (i.select("modality").asString, i.select("endpoints").as[Seq[String]])).sortBy(_._1), Seq(
      ("audio", Seq("audio_speech", "audio_transcriptions")),
      ("embedding", Seq("embeddings")),
      ("image", Seq("images_generations")),
      ("moderation", Seq("moderations")),
    ))
    // a connection that only speaks keeps its voice, and stops listing a transcription model
    val updated = studio("PUT", s"/workspaces/$wsId/providers/${created.json.select("id").asString}", Json.obj(
      "modalities" -> Json.obj("audio" -> Json.obj("enabled" -> true, "model" -> "gpt-4o-mini-tts", "stt_model" -> "")),
    ))
    assertEquals(updated.status, 200, updated.body)
    val audio = studio("GET", s"/workspaces/$wsId/models").json.select("models").as[Seq[JsObject]]
      .filter(_.select("modality").asString == "audio")
    assertEquals(audio.map(_.select("id").asString), Seq("gpt-4o-mini-tts"))
    assertEquals(studio("DELETE", s"/workspaces/$wsId").status, 204)
  }
}
