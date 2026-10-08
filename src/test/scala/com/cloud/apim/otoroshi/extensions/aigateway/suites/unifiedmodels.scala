package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.LlmExtensionOneOtoroshiServerPerSuite
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiProvider, DecisionModel, EmbeddingModel, ImageModel}
import otoroshi.models.EntityLocation
import otoroshi.next.models.*
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.{OpenAiCompatApi, OpenAiCompatModalityModels}
import play.api.libs.json.*
import reactor.core.publisher.Mono
import reactor.netty.http.server.HttpServerResponse

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.DurationInt

// The `/models` of the unified api lists the models of every entity of its route: its text providers as they list
// them, and the entities of the other model types that no text provider lists already.
class UnifiedModelsSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  private def send(response: HttpServerResponse, models: String*) =
    response.status(200).addHeader("Content-Type", "application/json")
      .sendString(Mono.just(Json.obj("object" -> "list", "data" -> models.map(m => Json.obj("id" -> m, "object" -> "model"))).stringify)).`then`()

  // a connection shared by the text provider and the entities of other model types
  val sharedCalls = new AtomicInteger(0)
  val (sharedPort, _) = createTestServerWithRoutes("unified-models-shared", routes => routes
    .get("/v1/models", (_, response) => {
      sharedCalls.incrementAndGet()
      send(response, "gpt-4o-mini", "text-embedding-3-small", "gpt-image-1", "tts-1")
    })
  )

  // a connection only an image entity uses
  val imagesCalls = new AtomicInteger(0)
  val (imagesPort, _) = createTestServerWithRoutes("unified-models-images", routes => routes
    .get("/v1/models", (_, response) => {
      imagesCalls.incrementAndGet()
      send(response, "gpt-image-1", "dall-e-3", "gpt-4o")
    })
  )

  def connection(port: Int): JsObject = Json.obj("base_url" -> s"http://localhost:${port}/v1", "token" -> "xxx", "timeout" -> 30000)
  def id(kind: String): String = s"${kind}_${UUID.randomUUID()}"

  lazy val text = AiProvider(id = id("provider"), name = "OpenAI", provider = "openai", connection = connection(sharedPort), options = Json.obj("model" -> "gpt-4o-mini"))
  // the entity a studio connection creates next to its text provider: same connection, same name. Its model is
  // listed by the provider already
  lazy val coveredEmbeddings = EmbeddingModel(EntityLocation.default, id("embedding-model"), "OpenAI", "", Seq.empty, Map.empty, "openai",
    Json.obj("connection" -> connection(sharedPort), "options" -> Json.obj("model" -> "text-embedding-3-small")))
  // covered too, but the provider does not list its model, as OpenRouter does not list its decision models
  lazy val coveredImages = ImageModel(EntityLocation.default, id("image-model"), "OpenAI", "", Seq.empty, Map.empty, "openai",
    Json.obj("connection" -> connection(sharedPort), "options" -> Json.obj("generation" -> Json.obj("enabled" -> true, "model" -> "dall-e-2"))))
  // the same connection under another name: its ids are its own
  lazy val embeddings = EmbeddingModel(EntityLocation.default, id("embedding-model"), "Embeddings", "", Seq.empty, Map.empty, "openai",
    Json.obj("connection" -> connection(sharedPort), "options" -> Json.obj("model" -> "custom-embedder")))
  lazy val images = ImageModel(EntityLocation.default, id("image-model"), "Images", "", Seq.empty, Map.empty, "openai",
    Json.obj("connection" -> connection(imagesPort), "options" -> Json.obj("generation" -> Json.obj("enabled" -> true, "model" -> "gpt-image-1"))))
  // no text client lists the models of TypeSafe
  lazy val jev = DecisionModel(EntityLocation.default, id("decision-model"), "Jev", "", Seq.empty, Map.empty, "typesafe",
    Json.obj("connection" -> Json.obj("base_url" -> "http://localhost:1/v1", "token" -> "xxx"), "options" -> Json.obj("model" -> "jev-latest")))
  // decisions of a text provider: the model it is set up with, not every model of that provider
  lazy val emulated = DecisionModel(EntityLocation.default, id("decision-model"), "Emulated", "", Seq.empty, Map.empty, "llm-emulation",
    Json.obj("connection" -> Json.obj("provider" -> text.id), "options" -> Json.obj("model" -> "gpt-4o-mini")))

  lazy val setup: Unit = {
    assert(client.forLlmEntity("providers").upsertEntity(text).awaitf(10.seconds).createdOrUpdated)
    Seq(coveredEmbeddings, embeddings).foreach(e => assert(client.forLlmEntity("embedding-models").upsertEntity(e).awaitf(10.seconds).createdOrUpdated))
    Seq(coveredImages, images).foreach(e => assert(client.forLlmEntity("image-models").upsertEntity(e).awaitf(10.seconds).createdOrUpdated))
    Seq(jev, emulated).foreach(e => assert(client.forLlmEntity("decision-models").upsertEntity(e).awaitf(10.seconds).createdOrUpdated))
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(NgRoute(
      location = EntityLocation.default,
      id = UUID.randomUUID().toString,
      name = "unified models",
      description = "unified models",
      tags = Seq.empty,
      metadata = Map.empty,
      enabled = true,
      debugFlow = false,
      capture = false,
      exportReporting = false,
      frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath("unified-models.oto.tools"))),
      backend = NgBackend.empty.copy(targets = Seq(NgTarget.default)),
      plugins = NgPlugins(Seq(NgPluginInstance(plugin = s"cp:${classOf[OpenAiCompatApi].getName}", config = NgPluginInstanceConfig(Json.obj(
        "language_model_refs" -> Json.arr(text.id),
        "embedding_model_refs" -> Json.arr(coveredEmbeddings.id, embeddings.id),
        "image_model_refs" -> Json.arr(coveredImages.id, images.id),
        "decision_model_refs" -> Json.arr(jev.id, emulated.id),
      ))))),
    )).awaitf(10.seconds)
    await(10.seconds)
  }

  def models(query: String = ""): Seq[JsObject] = {
    val resp = client.call("GET", s"http://unified-models.oto.tools:${port}/v1/models${query}", Map.empty, None).awaitf(30.seconds)
    assertEquals(resp.status, 200, s"status should be 200, got ${resp.body}")
    resp.json.select("data").as[Seq[JsObject]]
  }

  test("the models an entity is set up with are read in every section of its config") {
    assertEquals(OpenAiCompatModalityModels.configured("embedding", Json.obj("options" -> Json.obj("model" -> "e5"))), Seq("e5"))
    assertEquals(OpenAiCompatModalityModels.configured("image", Json.obj("options" -> Json.obj(
      "generation" -> Json.obj("enabled" -> true, "model" -> "gpt-image-1"),
      "edition" -> Json.obj("enabled" -> true, "model" -> "dall-e-2"),
    ))), Seq("gpt-image-1", "dall-e-2"))
    // an audio entity speaks and transcribes with two models, a section turned off has none
    assertEquals(OpenAiCompatModalityModels.configured("audio", Json.obj(
      "tts" -> Json.obj("enabled" -> true, "model" -> "tts-1"),
      "stt" -> Json.obj("enabled" -> true, "model" -> "whisper-1"),
      "translate" -> Json.obj("enabled" -> false, "model" -> "whisper-1"),
    )), Seq("tts-1", "whisper-1"))
    assertEquals(OpenAiCompatModalityModels.configured("audio", Json.obj("options" -> Json.obj("tts" -> Json.obj("model_id" -> "eleven_multilingual_v2")))), Seq("eleven_multilingual_v2"))
  }

  test("a model listed twice under the same id is one entry, which answers on the endpoints of both") {
    val merged = OpenAiCompatModalityModels.merged(Seq(
      Json.obj("id" -> "gpt-image-1", "_metadata" -> Json.obj("kinds" -> Json.arr("text"), "endpoints" -> Json.arr("chat_completions"))),
      Json.obj("id" -> "other"),
      Json.obj("id" -> "gpt-image-1", "_metadata" -> Json.obj("kinds" -> Json.arr("image"), "endpoints" -> Json.arr("images_generations"))),
    ))
    assertEquals(merged.map(_.select("id").asString), Seq("gpt-image-1", "other"))
    assertEquals(merged.head.at("_metadata.kinds").as[Seq[String]], Seq("text", "image"))
    assertEquals(merged.head.at("_metadata.endpoints").as[Seq[String]], Seq("chat_completions", "images_generations"))
  }

  test("the unified api lists the models of every model type, each connection being listed once") {
    setup
    val listed = models()
    // the text provider as it always listed its models, then what the other entities add to it
    assertEquals(listed.map(_.select("id").asString), Seq(
      "gpt-4o-mini", "text-embedding-3-small", "gpt-image-1", "tts-1",
      "embeddings/custom-embedder", "embeddings/text-embedding-3-small",
      "openai/dall-e-2", "images/gpt-image-1", "images/dall-e-3",
      "jev/jev-latest", "emulated/gpt-4o-mini",
    ))
    // the text provider and the entity under another name share a connection: one call each, the covered ones none
    assertEquals(sharedCalls.get(), 2)
    assertEquals(imagesCalls.get(), 1)
    val entry = listed.find(_.select("id").asString == "embeddings/custom-embedder").get
    assertEquals(entry.select("owned_by").asString, "Embeddings")
    assertEquals(entry.select("simple_id").asString, "custom-embedder")
  }

  test("the decision models are found on the decision endpoints, the api of their provider first") {
    setup
    val decisions = models("?endpoint=decisions&enriched=true")
    assertEquals(decisions.map(_.select("id").asString), Seq("jev/jev-latest", "emulated/gpt-4o-mini"))
    assertEquals(decisions.map(_.at("_metadata.kinds").as[Seq[String]]), Seq(Seq("decision"), Seq("decision")))
    // a decision made by a text provider is a System One one, whatever that provider serves
    assertEquals(decisions.map(_.at("_metadata.endpoints").as[Seq[String]]), Seq(Seq("systemone", "decisions"), Seq("systemone", "decisions")))
  }
}
