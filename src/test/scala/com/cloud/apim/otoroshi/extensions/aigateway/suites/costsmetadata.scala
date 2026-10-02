package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.catalog.ModelsMetadata
import com.cloud.apim.otoroshi.extensions.aigateway.decorators.ChatClientWithCostsTracking
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiProvider, EmbeddingModel, ImageModel, ModelSettings}
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatMessage, ChatPrompt, EmbeddingClientInputOptions, ImageModelClientGenerationInputOptions, LlmExtensionOneOtoroshiServerPerSuite}
import otoroshi.env.Env
import otoroshi.models.EntityLocation
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.*
import reactor.core.publisher.Mono
import reactor.netty.http.server.HttpServerResponse

import java.util.UUID
import scala.concurrent.duration.DurationInt

// A model the price table does not know under its name — a self-hosted one, an alias, a provider the gateway has
// no prices for — is priced as the model it is: the `costs-tracking-provider` and `costs-tracking-model` metadata
// of its entity say which. Whatever the type of the model, and for the three things that read a price: the cost of
// a call, the models that may be called when costs are required, and `has_cost`.
class CostsMetadataSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  private def send(response: HttpServerResponse, body: JsValue) =
    response.status(200).addHeader("Content-Type", "application/json").sendString(Mono.just(body.stringify)).`then`()

  // answers as the model it was asked for, under a name no price table knows
  val (port1, _) = createTestServerWithRoutes("costs-metadata", routes => routes
    .post("/v1/chat/completions", (req, response) => req.receive().aggregate().asString().flatMap { body =>
      send(response, Json.obj(
        "id" -> "chatcmpl-1", "object" -> "chat.completion", "created" -> 1700000000, "model" -> Json.parse(body).select("model").asString,
        "choices" -> Json.arr(Json.obj("index" -> 0, "finish_reason" -> "stop", "message" -> Json.obj("role" -> "assistant", "content" -> "hello"))),
        "usage" -> Json.obj("prompt_tokens" -> 100, "completion_tokens" -> 50, "total_tokens" -> 150),
      ))
    })
    .post("/v1/embeddings", (req, response) => req.receive().aggregate().asString().flatMap { body =>
      send(response, Json.obj(
        "object" -> "list", "model" -> Json.parse(body).select("model").asString,
        "data" -> Json.arr(Json.obj("object" -> "embedding", "index" -> 0, "embedding" -> Json.arr(0.1, 0.2))),
        "usage" -> Json.obj("prompt_tokens" -> 1000, "total_tokens" -> 1000),
      ))
    })
    .post("/v1/images/generations", (req, response) => req.receive().aggregate().asString().flatMap { _ =>
      send(response, Json.obj(
        "created" -> 1, "data" -> Json.arr(Json.obj("b64_json" -> "aGV5")),
        "usage" -> Json.obj("total_tokens" -> 202, "input_tokens" -> 16, "output_tokens" -> 186, "input_tokens_details" -> Json.obj("text_tokens" -> 16, "image_tokens" -> 0)),
      ))
    })
  )

  def ext: AiExtension = otoroshi.env.adminExtensions.extension[AiExtension].get

  val connection = Json.obj("base_url" -> s"http://localhost:${port1}/v1", "token" -> "sk-test", "timeout" -> 10000)
  def pricedAs(provider: Option[String], model: String): Map[String, String] =
    provider.map(p => Map("costs-tracking-provider" -> p)).getOrElse(Map.empty) ++ Map("costs-tracking-model" -> model)

  // a chat model served by an api the gateway has no prices for
  lazy val chat = AiProvider(
    id = s"provider_${UUID.randomUUID()}", name = "self hosted chat", provider = "openai-compatible",
    metadata = pricedAs(Some("openai"), "gpt-4o-mini"),
    connection = connection, options = Json.obj("model" -> "my-chat-model"),
  )
  // an embedding model under a name of one's own, on a provider with prices, and the same on an api without any
  lazy val aliasedEmbedding = EmbeddingModel(
    EntityLocation.default, s"embedding-model_${UUID.randomUUID()}", "aliased embedding", "", Seq.empty, pricedAs(None, "text-embedding-3-small"), "openai",
    Json.obj("connection" -> connection, "options" -> Json.obj("model" -> "my-embedding-model")),
  )
  lazy val selfHostedEmbedding = EmbeddingModel(
    EntityLocation.default, s"embedding-model_${UUID.randomUUID()}", "self hosted embedding", "", Seq.empty, pricedAs(Some("openai"), "text-embedding-3-small"), "openai-compatible",
    Json.obj("connection" -> connection, "options" -> Json.obj("model" -> "my-embedding-model")),
    models = ModelSettings(requireKnownCosts = true),
  )
  // a model billed by its own unit
  lazy val image = ImageModel(
    EntityLocation.default, s"image-model_${UUID.randomUUID()}", "aliased image", "", Seq.empty, pricedAs(None, "gpt-image-2"), "openai",
    Json.obj("connection" -> connection, "options" -> Json.obj("generation" -> Json.obj("enabled" -> true, "model" -> "my-image-model"))),
  )

  lazy val setup: Unit = {
    assert(client.forLlmEntity("providers").upsertEntity(chat).awaitf(10.seconds).createdOrUpdated, "the provider should be saved")
    Seq(aliasedEmbedding, selfHostedEmbedding).foreach { m =>
      assert(client.forLlmEntity("embedding-models").upsertEntity(m).awaitf(10.seconds).createdOrUpdated, s"${m.name} should be saved")
    }
    assert(client.forLlmEntity("image-models").upsertEntity(image).awaitf(10.seconds).createdOrUpdated, "the image model should be saved")
    await(5.seconds)
  }

  def priced(model: String, input: Long, output: Long): BigDecimal = ext.costsTracking.computeCosts("openai", model, input, output, 0L).toOption.get.totalCost

  test("a chat model on an api without prices is billed as the model its metadata names") {
    setup
    given env: Env = otoroshi.env
    val attrs = TypedMap.empty
    val resp = ext.states.provider(chat.id).flatMap(_.getChatClient()).get
      .call(ChatPrompt(Seq(ChatMessage.userStrInput("hello"))), attrs, Json.obj())(using ec, env).awaitf(30.seconds)
    assert(resp.isRight, s"the call should succeed, got ${resp}")
    assertEquals(attrs.get(ChatClientWithCostsTracking.key).map(_.totalCost), Some(priced("gpt-4o-mini", 100, 50)))
    assert(ModelsMetadata.describe(chat, "my-chat-model").hasCost, "and it is listed as a model with a cost")
  }

  test("an embedding model is billed as the model its metadata names, on a provider with prices or without") {
    setup
    given env: Env = otoroshi.env
    Seq(aliasedEmbedding, selfHostedEmbedding).foreach { model =>
      val attrs = TypedMap.empty
      val resp = ext.states.embeddingModel(model.id).flatMap(_.getEmbeddingModelClient()).get
        .embed(EmbeddingClientInputOptions(input = Seq("hey")), Json.obj(), attrs)(using ec, env).awaitf(30.seconds)
      assert(resp.isRight, s"${model.name}: the call should succeed, even when costs are required, got ${resp}")
      assertEquals(attrs.get(ChatClientWithCostsTracking.key).map(_.totalCost), Some(priced("text-embedding-3-small", 1000, 0)), model.name)
      val listed = AiProvider(id = model.id, name = model.name, provider = model.provider, metadata = model.metadata, connection = Json.obj(), options = Json.obj())
      assert(ModelsMetadata.describe(listed, "my-embedding-model", "embedding").hasCost, s"${model.name}: and it is listed as a model with a cost")
    }
  }

  test("a model billed by its own unit is billed as the model its metadata names too") {
    setup
    given env: Env = otoroshi.env
    val attrs = TypedMap.empty
    val resp = ext.states.imageModel(image.id).flatMap(_.getImageModelClient()).get
      .generate(ImageModelClientGenerationInputOptions(prompt = "a red panda coding on a laptop"), Json.obj(), attrs)(using ec, env).awaitf(30.seconds)
    assert(resp.isRight, s"the call should succeed, got ${resp}")
    // 16 * 0.000005 + 186 * 0.00003, the price of gpt-image-2
    assertEquals(attrs.get(ChatClientWithCostsTracking.key).map(_.totalCost), Some(BigDecimal("0.005660")))
    val listed = AiProvider(id = image.id, name = image.name, provider = image.provider, metadata = image.metadata, connection = Json.obj(), options = Json.obj())
    assert(ModelsMetadata.describe(listed, "my-image-model", "image").hasCost, "and it is listed as a model with a cost")
  }

  test("a decision model priced by its metadata is listed as a model with a cost") {
    given env: Env = otoroshi.env
    val listed = AiProvider(id = "decision-model_x", name = "self hosted jev", provider = "systemone-compatible",
      metadata = pricedAs(Some("typesafe"), "jev-1.13.0"), connection = Json.obj(), options = Json.obj())
    assert(ModelsMetadata.describe(listed, "my-decision-model", "decision").hasCost)
  }

  test("without metadata, a name no price table knows has no cost") {
    given env: Env = otoroshi.env
    val listed = AiProvider(id = "embedding-model_x", name = "unknown", provider = "openai", connection = Json.obj(), options = Json.obj())
    assert(!ModelsMetadata.describe(listed, "my-embedding-model", "embedding").hasCost)
  }
}
