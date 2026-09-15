package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.decorators.{ChatClientWithSemanticCache, GuardrailItem, Guardrails}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiProvider, EmbeddingModel, ProviderHealthcheck}
import com.cloud.apim.otoroshi.extensions.aigateway.providers.ProviderHealthchecks
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatMessage, ChatPrompt, LlmExtensionOneOtoroshiServerPerSuite}
import otoroshi.models.EntityLocation
import otoroshi.next.models.*
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.LlmResponseEndpoint
import play.api.libs.json.{JsObject, JsValue, Json}
import reactor.core.publisher.Mono

import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

// Everything that references a provider (or an embedding / moderation model) to call it for its own needs can name
// the model to use instead of the default model of the provider: guardrails, fallbacks, load balancers, routers,
// plugins, caches and healthchecks.
class ModelOverrideSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  val chatModels = new ConcurrentLinkedQueue[String]()
  val embeddingModels = new ConcurrentLinkedQueue[String]()

  val (openaiPort, _) = createTestServerWithRoutes("openai-models", routes => routes
    .post("/v1/chat/completions", (req, response) => {
      req.receive().aggregate().asString().flatMap { body =>
        val model = body.parseJson.select("model").asOptString.getOrElse("--")
        chatModels.add(model)
        if (model == "broken") {
          response.status(500).addHeader("Content-Type", "application/json").sendString(Mono.just("""{"error":"boom"}""")).`then`()
        } else {
          response.status(200).addHeader("Content-Type", "application/json").sendString(Mono.just(Json.obj(
            "id" -> "chatcmpl-1",
            "object" -> "chat.completion",
            "created" -> 1700000000,
            "model" -> model,
            "choices" -> Json.arr(Json.obj("index" -> 0, "finish_reason" -> "stop", "message" -> Json.obj("role" -> "assistant", "content" -> "true"))),
            "usage" -> Json.obj("prompt_tokens" -> 1, "completion_tokens" -> 1, "total_tokens" -> 2),
          ).stringify)).`then`()
        }
      }
    })
    .post("/v1/embeddings", (req, response) => {
      req.receive().aggregate().asString().flatMap { body =>
        val model = body.parseJson.select("model").asOptString.getOrElse("--")
        embeddingModels.add(model)
        response.status(200).addHeader("Content-Type", "application/json").sendString(Mono.just(Json.obj(
          "object" -> "list",
          "model" -> model,
          "data" -> Json.arr(Json.obj("object" -> "embedding", "index" -> 0, "embedding" -> Json.arr(0.1, 0.2, 0.3))),
          "usage" -> Json.obj("prompt_tokens" -> 1, "total_tokens" -> 1),
        ).stringify)).`then`()
      }
    })
  )

  def openai(name: String, model: String, f: AiProvider => AiProvider = identity): AiProvider = f(AiProvider(
    id = s"provider_${UUID.randomUUID()}",
    name = name,
    provider = "openai",
    connection = Json.obj("base_url" -> s"http://localhost:$openaiPort/v1", "token" -> "sk-test", "timeout" -> 30000),
    options = Json.obj("model" -> model),
  ))

  def virtual(name: String, kind: String, options: JsObject): AiProvider = AiProvider(
    id = s"provider_${UUID.randomUUID()}",
    name = name,
    provider = kind,
    connection = Json.obj(),
    options = options,
  )

  def gibberish(config: JsObject): Guardrails = Guardrails(Seq(GuardrailItem(enabled = true, before = true, after = false, guardrailId = "gibberish", config = config)))

  def ext: AiExtension = otoroshi.env.adminExtensions.extension[AiExtension].get

  def save(providers: AiProvider*): Unit = {
    providers.foreach(p => client.forLlmEntity("providers").upsertEntity(p).awaitf(10.seconds))
  }

  // calls a provider in process and returns the models the fake provider received, in order
  def call(provider: AiProvider, body: JsObject = Json.obj()): Seq[String] = {
    chatModels.clear()
    given env: _root_.otoroshi.env.Env = otoroshi.env
    val chatClient = ext.states.provider(provider.id).get.getChatClient().get
    chatClient.call(ChatPrompt(Seq(ChatMessage.userStrInput("hello"))), TypedMap.empty, body)(using otoroshi.executionContext, env).awaitf(30.seconds)
    chatModels.asScala.toSeq
  }

  test("a model can be chosen everywhere a provider is referenced") {

    // guardrails judged by another provider, or by the guarded provider itself with another model
    val judge = openai("judge", "judge-default")
    val guarded = openai("guarded", "main", _.copy(guardrails = gibberish(Json.obj("provider" -> judge.id, "model" -> "judge-mini"))))
    val selfGuardedId = s"provider_${UUID.randomUUID()}"
    val selfGuarded = openai("self guarded", "self-main", _.copy(id = selfGuardedId, guardrails = gibberish(Json.obj("provider" -> selfGuardedId, "model" -> "self-mini"))))
    val selfSkipped = openai("self skipped", "skipped-main", p => p.copy(guardrails = gibberish(Json.obj("provider" -> p.id))))

    // fallback with its own model: the model asked for the primary is not sent to the fallback
    val fallback = openai("fallback", "fallback-default")
    val primary = openai("primary", "broken", _.copy(providerFallback = Some(fallback.id), providerFallbackModel = Some("fallback-model")))

    // one provider balanced between two of its models
    val target = openai("target", "target-default")
    val balancer = virtual("balancer", "loadbalancer", Json.obj(
      "refs" -> Json.arr(Json.obj("ref" -> target.id, "model" -> "lb-a"), Json.obj("ref" -> target.id, "model" -> "lb-b")),
      "loadbalancing" -> "round_robin",
    ))

    // router candidates and judge with their own models
    val router = virtual("router", "otoroshi", Json.obj(
      "code_router_refs" -> Json.arr(Json.obj("ref" -> target.id, "model" -> "claude-haiku-4-5")),
      "auto_router_refs" -> Json.arr(Json.obj("ref" -> target.id, "model" -> "auto-candidate")),
      "auto_router_classifier_ref" -> judge.id,
      "auto_router_classifier_model" -> "classifier-model",
    ))

    val embedding = EmbeddingModel(
      location = EntityLocation.default,
      id = s"embedding-model_${UUID.randomUUID()}",
      name = "embeddings",
      description = "",
      tags = Seq.empty,
      metadata = Map.empty,
      provider = "openai",
      config = Json.obj(
        "connection" -> Json.obj("base_url" -> s"http://localhost:$openaiPort/v1", "token" -> "sk-test", "timeout" -> 30000),
        "options" -> Json.obj("model" -> "embedding-default"),
      ),
    )

    val route = NgRoute(
      location = EntityLocation.default,
      id = s"route_${UUID.randomUUID()}",
      name = "model override route",
      description = "model override route",
      tags = Seq.empty,
      metadata = Map.empty,
      enabled = true,
      debugFlow = false,
      capture = false,
      exportReporting = false,
      frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath("model-override.oto.tools"))),
      backend = NgBackend.empty.copy(targets = Seq(NgTarget.default)),
      plugins = NgPlugins(Seq(NgPluginInstance(
        plugin = s"cp:${classOf[LlmResponseEndpoint].getName}",
        config = NgPluginInstanceConfig(Json.obj("ref" -> target.id, "model" -> "plugin-model", "prompt" -> "say hello"))
      )))
    )

    save(judge, guarded, selfGuarded, selfSkipped, fallback, primary, target, balancer, router)
    client.forLlmEntity("embedding-models").upsertEntity(embedding).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(route).awaitf(10.seconds)
    await(5.seconds)

    assertEquals(call(guarded), Seq("judge-mini", "main"))
    assertEquals(call(selfGuarded), Seq("self-mini", "self-main"))
    assertEquals(call(selfSkipped), Seq("skipped-main"))

    assertEquals(call(primary, Json.obj("model" -> "broken")), Seq("broken", "fallback-model"))

    val balanced = call(balancer, Json.obj("model" -> "asked-by-the-request")) ++ call(balancer, Json.obj("model" -> "asked-by-the-request"))
    assertEquals(balanced.toSet, Set("lb-a", "lb-b"))

    assertEquals(call(router, Json.obj("model" -> "code-router")), Seq("claude-haiku-4-5"))
    assertEquals(call(router, Json.obj("model" -> "auto-router")), Seq("classifier-model", "auto-candidate"))

    chatModels.clear()
    val resp = client.call("GET", s"http://model-override.oto.tools:$port/", Map.empty, None).awaitf(30.seconds)
    assertEquals(resp.status, 200, resp.body)
    assertEquals(chatModels.asScala.toSeq, Seq("plugin-model"))

    chatModels.clear()
    ProviderHealthchecks.probe(target.copy(healthcheck = ProviderHealthcheck(enabled = true, model = Some("probe-model"))))(using otoroshi.executionContext, otoroshi.env).awaitf(30.seconds)
    assertEquals(chatModels.asScala.toSeq, Seq("probe-model"))

    embeddingModels.clear()
    ChatClientWithSemanticCache.embedText("hello", Some(embedding.id), Some("embedding-override"))(using otoroshi.executionContext, otoroshi.env).awaitf(30.seconds)
    ChatClientWithSemanticCache.embedText("hello", Some(embedding.id))(using otoroshi.executionContext, otoroshi.env).awaitf(30.seconds)
    assertEquals(embeddingModels.asScala.toSeq, Seq("embedding-override", "embedding-default"))
  }
}
