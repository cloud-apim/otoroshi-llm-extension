package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiProvidersCatalog, ImageModel}
import com.cloud.apim.otoroshi.extensions.aigateway.{ImageModelClientGenerationInputOptions, LlmExtensionOneOtoroshiServerPerSuite}
import otoroshi.models.EntityLocation
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.{JsObject, Json}
import reactor.core.publisher.Mono

import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.DurationInt

// Arkane Cloud chat is OpenAI-like, but its image generation takes `width` / `height` and diffusion settings at the
// root of the body, and rejects the OpenAI fields it does not know (`size`, `n`, `quality`...)
class ArkaneCloudSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  val bodies = new ConcurrentLinkedQueue[JsObject]()
  val authorizations = new ConcurrentLinkedQueue[String]()

  val (arkanePort, _) = createTestServerWithRoutes("arkane-cloud", routes => routes
    .post("/api/v2/images/generations", (req, response) => {
      authorizations.add(req.requestHeaders().get("Authorization"))
      req.receive().aggregate().asString().flatMap { body =>
        bodies.add(body.parseJson.asObject)
        response.status(200).addHeader("Content-Type", "application/json").sendString(Mono.just(Json.obj(
          "id" -> "text2img-1",
          "data" -> Json.arr(Json.obj("b64_json" -> "aGV5")),
        ).stringify)).`then`()
      }
    })
  )

  def imageModel(generation: JsObject): ImageModel = ImageModel(
    EntityLocation.default, UUID.randomUUID().toString, "arkane images", "", Seq.empty, Map.empty, "arkane-cloud",
    Json.obj(
      "connection" -> Json.obj("base_url" -> s"http://localhost:${arkanePort}/api/v2", "token" -> "arkane-key", "timeout" -> 30000),
      "options" -> Json.obj("generation" -> generation),
    ),
  )

  def generate(model: ImageModel, opts: ImageModelClientGenerationInputOptions, rawBody: JsObject = Json.obj()): JsObject = {
    bodies.clear()
    val resp = model.getImageModelClient()(using otoroshi.env).get
      .generate(opts, rawBody, TypedMap.empty)(using ec, otoroshi.env)
      .awaitf(30.seconds)
    assert(resp.isRight, s"the call should succeed, got ${resp}")
    assertEquals(resp.toOption.get.images.flatMap(_.b64Json), Seq("aGV5"))
    bodies.peek()
  }

  test("arkane cloud serves text and images") {
    val entry = AiProvidersCatalog.all.find(_.id == "arkane-cloud")
    assert(entry.isDefined, "arkane cloud should be in the providers catalog")
    assert(entry.get.capabilities.contains("text"), "chat goes through the OpenAI-like client")
    assert(entry.get.capabilities.contains("image"), "images go through the dedicated client")
  }

  test("the OpenAI size becomes width and height, and the fields arkane does not know are not sent") {
    val model = imageModel(Json.obj("enabled" -> true, "model" -> "stability-ai/sdxl", "width" -> 512, "height" -> 512, "negative_prompt" -> "night sky"))
    val body = generate(model, ImageModelClientGenerationInputOptions(
      prompt = "an elephant in a desert",
      size = Some("1024x768"),
      n = Some(2),
      quality = Some("hd"),
      style = Some("vivid"),
      outputFormat = Some("webp"),
    ), Json.obj("seed" -> 42, "num_inference_steps" -> 30))
    assertEquals(body, Json.obj(
      "model" -> "stability-ai/sdxl",
      "prompt" -> "an elephant in a desert",
      "width" -> 1024,
      "height" -> 768,
      "response_extension" -> "webp",
      "num_inference_steps" -> 30,
      "seed" -> 42,
      "negative_prompt" -> "night sky",
    ))
    assertEquals(authorizations.peek(), "Bearer arkane-key")
  }

  test("without a size in the request, the configured dimensions are used") {
    val configured = generate(
      imageModel(Json.obj("enabled" -> true, "width" -> 512, "height" -> 640)),
      ImageModelClientGenerationInputOptions(prompt = "a cat", size = Some("auto")),
    )
    assertEquals(configured.select("model").asString, "stability-ai/sdxl")
    assertEquals((configured.select("width").asInt, configured.select("height").asInt), (512, 640))
    val defaults = generate(imageModel(Json.obj("enabled" -> true)), ImageModelClientGenerationInputOptions(prompt = "a cat"))
    assertEquals((defaults.select("width").asInt, defaults.select("height").asInt), (1024, 1024))
  }
}
