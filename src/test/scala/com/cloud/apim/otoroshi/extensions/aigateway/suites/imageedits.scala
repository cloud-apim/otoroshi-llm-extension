package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.LlmExtensionOneOtoroshiServerPerSuite
import com.cloud.apim.otoroshi.extensions.aigateway.entities.ImageModel
import org.apache.pekko.util.ByteString
import otoroshi.models.EntityLocation
import otoroshi.next.models.*
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.OpenAICompatImagesEdit
import play.api.libs.json.*
import play.api.libs.ws.WSBodyWritables.given
import reactor.core.publisher.Mono

import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.DurationInt

// `/images/edits` takes a multipart form, the way the OpenAI sdks send it: a prompt, and the images to change as
// `image`, or as `image[]` when there are several. OpenAI is asked the same way; OpenRouter, which has no images
// endpoint, gets them as images of a chat message.
class ImageEditsSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  val prompt = "turn the sky into a sunset"
  val imageA = ByteString("fake-png-a")
  val imageB = ByteString("fake-png-b")
  val openRouterModel = "google/gemini-2.5-flash-image"

  val openAiForm = new AtomicReference[String]("")
  val openRouterBody = new AtomicReference[JsValue](JsNull)

  val (openAiPort, _) = createTestServerWithRoutes("openai-edits", routes => routes
    .post("/images/edits", (req, response) => req.receive().aggregate().asString().flatMap { raw =>
      openAiForm.set(raw)
      response.status(200).addHeader("Content-Type", "application/json").sendString(Mono.just(Json.obj(
        "created" -> 1,
        "data" -> Json.arr(Json.obj("b64_json" -> "ZWRpdGVk")),
        "usage" -> Json.obj("total_tokens" -> 300, "input_tokens" -> 100, "output_tokens" -> 200, "input_tokens_details" -> Json.obj("text_tokens" -> 10, "image_tokens" -> 90)),
      ).stringify)).`then`()
    })
  )

  val (openRouterPort, _) = createTestServerWithRoutes("openrouter-edits", routes => routes
    .post("/api/v1/chat/completions", (req, response) => req.receive().aggregate().asString().flatMap { raw =>
      openRouterBody.set(Json.parse(raw))
      response.status(200).addHeader("Content-Type", "application/json").sendString(Mono.just(Json.obj(
        "id" -> "gen-1", "created" -> 1, "model" -> openRouterModel,
        "choices" -> Json.arr(Json.obj("index" -> 0, "finish_reason" -> "stop", "message" -> Json.obj(
          "role" -> "assistant", "content" -> "here it is",
          "images" -> Json.arr(Json.obj("type" -> "image_url", "image_url" -> Json.obj("url" -> "data:image/png;base64,ZWRpdGVk"))),
        ))),
        "usage" -> Json.obj("prompt_tokens" -> 100, "completion_tokens" -> 200, "total_tokens" -> 300),
      ).stringify)).`then`()
    })
  )

  lazy val openAi: ImageModel = ImageModel(
    EntityLocation.default, "image-edits-openai", "openai edits", "", Seq.empty, Map.empty, "openai",
    Json.obj(
      "connection" -> Json.obj("base_url" -> s"http://localhost:${openAiPort}", "token" -> "xxx", "timeout" -> 30000),
      "options" -> Json.obj(
        "generation" -> Json.obj("enabled" -> true, "model" -> "gpt-image-2"),
        "edition" -> Json.obj("enabled" -> true, "model" -> "gpt-image-2", "size" -> "1024x1024"),
      ),
    ),
  )

  lazy val openRouter: ImageModel = ImageModel(
    EntityLocation.default, "image-edits-openrouter", "openrouter edits", "", Seq.empty, Map.empty, "openrouter",
    Json.obj(
      "connection" -> Json.obj("base_url" -> s"http://localhost:${openRouterPort}/api/v1", "token" -> "xxx", "timeout" -> 30000),
      "options" -> Json.obj(
        "generation" -> Json.obj("enabled" -> true, "model" -> openRouterModel),
        "edition" -> Json.obj("enabled" -> true, "model" -> openRouterModel),
      ),
    ),
  )

  lazy val setup: Unit = {
    val route = NgRoute(
      location = EntityLocation.default,
      id = "image-edits-route",
      name = "image edits route",
      description = "image edits route",
      tags = Seq.empty,
      metadata = Map.empty,
      enabled = true,
      debugFlow = false,
      capture = false,
      exportReporting = false,
      frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath("edits.oto.tools"))),
      backend = NgBackend.empty.copy(targets = Seq(NgTarget.default)),
      plugins = NgPlugins(Seq(NgPluginInstance(
        plugin = s"cp:${classOf[OpenAICompatImagesEdit].getName}",
        config = NgPluginInstanceConfig(Json.obj("refs" -> Json.arr(openAi.id, openRouter.id))),
      )))
    )
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "image-models").upsertEntity(openAi).awaitf(10.seconds)
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "image-models").upsertEntity(openRouter).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(route).awaitf(10.seconds)
    await(10.seconds)
  }

  val boundary = "otoroshi-image-edits"

  private def form(fields: Seq[(String, String)], files: Seq[(String, String, ByteString)]): ByteString = {
    val parts = fields.map { case (name, value) =>
      ByteString(s"--${boundary}\r\nContent-Disposition: form-data; name=\"${name}\"\r\n\r\n${value}\r\n")
    } ++ files.map { case (name, filename, bytes) =>
      ByteString(s"--${boundary}\r\nContent-Disposition: form-data; name=\"${name}\"; filename=\"${filename}\"\r\nContent-Type: image/png\r\n\r\n") ++ bytes ++ ByteString("\r\n")
    }
    parts.foldLeft(ByteString.empty)(_ ++ _) ++ ByteString(s"--${boundary}--\r\n")
  }

  private def edit(model: String, images: Seq[(String, String, ByteString)]): play.api.libs.ws.WSResponse = {
    client.client.url(s"http://edits.oto.tools:${port}/v1/images/edits")
      .withHttpHeaders("Content-Type" -> s"multipart/form-data; boundary=${boundary}")
      .post(form(Seq("model" -> model, "prompt" -> prompt), images))
      .awaitf(30.seconds)
  }

  private def base64(bytes: ByteString): String = Base64.getEncoder.encodeToString(bytes.toArray)

  test("several images sent as image[] reach OpenAI with the prompt, the model and the options of the entity") {
    setup
    val resp = edit("openai_edits/gpt-image-2", Seq(("image[]", "a.png", imageA), ("image[]", "b.png", imageB)))
    assertEquals(resp.status, 200, resp.body)
    assertEquals(resp.json.at("data.0.b64_json").asOpt[String], Some("ZWRpdGVk"))
    val sent = openAiForm.get()
    // the value of each part of that name: what follows the first blank line after its headers
    def values(name: String): Seq[String] = s"name=\"${java.util.regex.Pattern.quote(name)}\"[^\\r\\n]*\\r?\\n(?:[^\\r\\n]+\\r?\\n)*?\\r?\\n([^\\r\\n]*)".r
      .findAllMatchIn(sent).map(_.group(1)).toSeq
    assertEquals(values("prompt"), Seq(prompt), "the prompt is what the model is asked to change")
    assertEquals(values("model"), Seq("gpt-image-2"))
    assertEquals(values("size"), Seq("1024x1024"), "the size of the entity, once")
    assertEquals(values("image[]"), Seq("fake-png-a", "fake-png-b"), "both images, as image[]")
    assert(!sent.contains("name=\"file\""), "an image is not a file field")
  }

  test("an image sent to OpenRouter becomes an image of the chat message, next to the prompt") {
    setup
    val resp = edit(s"openrouter_edits###${openRouterModel}", Seq(("image", "a.png", imageA)))
    assertEquals(resp.status, 200, resp.body)
    assertEquals(resp.json.at("data.0.b64_json").asOpt[String], Some("ZWRpdGVk"))
    val body = openRouterBody.get()
    assertEquals(body.select("model").asOpt[String], Some(openRouterModel))
    val content = body.at("messages.0.content").as[Seq[JsObject]]
    assertEquals(content.flatMap(_.select("text").asOpt[String]), Seq(prompt))
    assertEquals(content.flatMap(_.at("image_url.url").asOpt[String]), Seq(s"data:image/png;base64,${base64(imageA)}"))
  }
}
