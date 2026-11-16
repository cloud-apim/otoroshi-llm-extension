package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.providers.{ProviderStates, PushedState, TargetStatus}
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatMessage, ChatPrompt, LlmExtensionOneOtoroshiServerPerSuite}
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.*
import play.api.libs.ws.WSResponse
import reactor.core.publisher.Mono

import java.nio.charset.StandardCharsets
import java.util.{Base64, UUID}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.DurationInt

// The admin api a monitoring tool pushes the state of the targets of a provider with, and what the gateway does
// with the state of a whole provider.
class ProviderStatesSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  class Upstream(val name: String) {
    val calls = new AtomicInteger(0)
    val (port, _) = createTestServerWithRoutes(s"states-$name", routes => routes
      .post("/v1/chat/completions", (req, response) => req.receive().aggregate().asString().flatMap { _ =>
        calls.incrementAndGet()
        response.status(200).addHeader("Content-Type", "application/json").sendString(Mono.just(Json.obj(
          "id" -> "chatcmpl-1", "object" -> "chat.completion", "created" -> 1700000000, "model" -> "gpt-4o-mini",
          "choices" -> Json.arr(Json.obj("index" -> 0, "finish_reason" -> "stop", "message" -> Json.obj("role" -> "assistant", "content" -> name))),
          "usage" -> Json.obj("prompt_tokens" -> 10, "completion_tokens" -> 5, "total_tokens" -> 15),
        ).stringify)).`then`()
      })
    )
    def url: String = s"http://localhost:${port}/v1"
  }

  def basicOf(id: String, secret: String): String = Base64.getEncoder.encodeToString(s"$id:$secret".getBytes(StandardCharsets.UTF_8))
  val basic: String = basicOf("admin-api-apikey-id", "admin-api-apikey-secret")
  val api = "/api/extensions/cloud-apim/extensions/ai-extension"

  def admin(method: String, path: String, body: JsValue = null, auth: String = basic): WSResponse =
    client.call(method, s"http://otoroshi-api.oto.tools:$port$api$path", Map("Authorization" -> s"Basic $auth"), Option(body)).awaitf(30.seconds)

  // an api key of the admin api with the rights of its `otoroshi-access-rights` metadata
  def adminApikey(id: String, rights: JsValue): String = {
    val url = s"http://otoroshi-api.oto.tools:$port/apis/apim.otoroshi.io/v1/apikeys"
    val template = client.call("GET", s"$url/_template", Map("Authorization" -> s"Basic $basic"), None).awaitf(30.seconds).json.as[JsObject]
    val created = client.call("POST", url, Map("Authorization" -> s"Basic $basic"), Some(template ++ Json.obj(
      "clientId" -> id,
      "clientSecret" -> s"$id-secret",
      "clientName" -> id,
      "authorizedEntities" -> Json.arr("group_admin-api-group"),
      "metadata" -> Json.obj("otoroshi-access-rights" -> rights.stringify),
    ))).awaitf(30.seconds)
    assert(created.status == 200 || created.status == 201, created.body)
    basicOf(id, s"$id-secret")
  }

  def provider(name: String, connection: JsObject, pushedState: Option[JsObject] = Some(Json.obj("enabled" -> true)), fallback: Option[String] = None, kind: String = "openai", options: JsObject = Json.obj("model" -> "gpt-4o-mini")): AiProvider = {
    val json = AiProvider(
      id = s"provider_${UUID.randomUUID()}",
      name = name,
      provider = kind,
      connection = Json.obj("token" -> "sk-test", "timeout" -> 10000) ++ connection,
      options = options,
      providerFallback = fallback,
    ).json.asObject ++ pushedState.map(p => Json.obj("pushed_state" -> p)).getOrElse(Json.obj())
    AiProvider.format.reads(json).get
  }

  def save(providers: AiProvider*): Unit = {
    providers.foreach(p => assert(client.forLlmEntity("providers").upsertEntity(p).awaitf(10.seconds).createdOrUpdated, s"${p.name} should be saved"))
    await(1.second)
  }

  def ext: AiExtension = otoroshi.env.adminExtensions.extension[AiExtension].get

  def call(p: AiProvider): Either[JsValue, String] = {
    given env: Env = otoroshi.env
    ext.states.provider(p.id).get.getChatClient().get
      .call(ChatPrompt(Seq(ChatMessage.userStrInput("hello"))), TypedMap.empty, Json.obj())(using otoroshi.executionContext, env)
      .awaitf(30.seconds).map(_.generations.head.message.content)
  }

  val targets = Json.obj(
    "base_url" -> "http://localhost:1/v1",
    "targets" -> Json.arr(Json.obj("id" -> "gpu-1", "base_url" -> "http://localhost:1/v1"), Json.obj("id" -> "gpu-2", "base_url" -> "http://localhost:2/v1")),
  )

  test("states are pushed by provider and in batch, each refused element on its own") {
    val p = provider("pushed by provider", targets, Some(Json.obj("enabled" -> true, "max_ttl" -> 60000)))
    val off = provider("pushed state not enabled", targets, None)
    save(p, off)
    val one = admin("PUT", s"/providers/${p.id}/states", Json.obj("states" -> Json.arr(
      Json.obj("target" -> "gpu-1", "status" -> "saturated", "load" -> 0.97, "ttl" -> 30000, "reason" -> "kv cache 97%"),
      Json.obj("target" -> "gpu-9", "status" -> "down", "ttl" -> 30000),
      Json.obj("target" -> "gpu-2", "status" -> "down", "ttl" -> 120000),
      Json.obj("target" -> "gpu-2", "status" -> "sleepy", "ttl" -> 30000),
      Json.obj("target" -> "gpu-2", "status" -> "down", "ttl" -> 10),
      Json.obj("status" -> "draining", "ttl" -> 30000),
    )))
    assertEquals(one.status, 200, one.body)
    assertEquals(one.json.select("accepted").asOpt[Int], Some(2))
    assertEquals(one.json.select("rejected").as[Seq[JsObject]].map(_.select("index").as[Int]), Seq(1, 2, 3, 4))
    assert(one.json.select("rejected").as[Seq[JsObject]].head.select("error").asString.contains("unknown target 'gpu-9'"))

    val many = admin("PUT", "/providers-states", Json.obj("states" -> Json.arr(
      Json.obj("provider" -> p.id, "target" -> "gpu-2", "status" -> "available", "ttl" -> 30000),
      Json.obj("provider" -> off.id, "target" -> "gpu-1", "status" -> "down", "ttl" -> 30000),
      Json.obj("provider" -> "provider_that_does_not_exist", "status" -> "down", "ttl" -> 30000),
      Json.obj("target" -> "gpu-1", "status" -> "down", "ttl" -> 30000),
    )))
    assertEquals(many.status, 200, many.body)
    assertEquals(many.json.select("accepted").asOpt[Int], Some(1))
    assertEquals(many.json.select("rejected").as[Seq[JsObject]].map(_.select("error").asString), Seq(
      "pushed state is not enabled on this provider", "forbidden", "provider is missing",
    ))
    assertEquals(admin("PUT", s"/providers/${p.id}/states", Json.obj("nope" -> true)).status, 400)
  }

  test("the effective state shows what was pushed, and is cleared by a delete") {
    val p = provider("read and cleared", targets)
    save(p)
    admin("PUT", s"/providers/${p.id}/states", Json.obj("states" -> Json.arr(
      Json.obj("target" -> "gpu-1", "status" -> "saturated", "load" -> 0.5, "ttl" -> 60000, "reason" -> "12 requests waiting"),
      Json.obj("target" -> "gpu-2", "status" -> "down", "ttl" -> 60000),
    )))
    val view = admin("GET", s"/providers/${p.id}/states")
    assertEquals(view.status, 200, view.body)
    val gpu1 = view.json.select("targets").as[Seq[JsObject]].find(_.select("id").asString == "gpu-1").get
    assertEquals(gpu1.at("pushed.status").asString, "saturated")
    assertEquals(gpu1.at("pushed.load").as[Double], 0.5)
    assertEquals(gpu1.at("pushed.reason").asString, "12 requests waiting")
    assert(gpu1.at("pushed.expires_in").as[Long] > 50000L)
    assertEquals(gpu1.select("circuit").asString, "closed")
    assertEquals(gpu1.select("usable").as[Boolean], false)
    assertEquals(view.json.at("provider.usable").as[Boolean], false, "no target of the provider is usable")

    assertEquals(admin("DELETE", s"/providers/${p.id}/states?target=gpu-2").status, 200)
    val partly = admin("GET", s"/providers/${p.id}/states").json.select("targets").as[Seq[JsObject]]
    assertEquals(partly.map(t => (t.select("id").asString, t.at("pushed.status").asString)), Seq("gpu-1" -> "saturated", "gpu-2" -> "available"))
    assertEquals(admin("DELETE", s"/providers/${p.id}/states").status, 200)
    val cleared = admin("GET", s"/providers/${p.id}/states").json
    assertEquals(cleared.select("targets").as[Seq[JsObject]].map(_.at("pushed.stale").asOpt[Boolean]), Seq(Some(true), Some(true)))
    assertEquals(cleared.at("provider.usable").as[Boolean], true)
    assertEquals(admin("GET", "/providers/provider_that_does_not_exist/states").status, 404)
  }

  test("a key that cannot write the provider cannot push its states") {
    val p = provider("rights", targets)
    save(p)
    val reader = adminApikey("states-reader", Json.arr(Json.obj("tenant" -> "default:r", "teams" -> Json.arr("default:r"))))
    await(3.seconds)
    val push = Json.obj("states" -> Json.arr(Json.obj("target" -> "gpu-1", "status" -> "down", "ttl" -> 30000)))
    assertEquals(admin("PUT", s"/providers/${p.id}/states", push, reader).status, 403)
    val batch = admin("PUT", "/providers-states", Json.obj("states" -> Json.arr(Json.obj("provider" -> p.id, "target" -> "gpu-1", "status" -> "down", "ttl" -> 30000))), reader)
    assertEquals(batch.json.select("rejected").as[Seq[JsObject]].map(_.select("error").asString), Seq("forbidden"))
    assertEquals(admin("GET", s"/providers/${p.id}/states", auth = reader).status, 200, "reading the state only needs to read the provider")
    assertEquals(admin("DELETE", s"/providers/${p.id}/states", auth = reader).status, 403)
  }

  test("the state of a whole provider: the fallback skips it, the load balancer leaves it out") {
    val primary = new Upstream("whole-primary")
    val backup = new Upstream("whole-backup")
    val other = new Upstream("whole-other")
    val fallback = provider("whole fallback", Json.obj("base_url" -> backup.url), None)
    val p = provider("whole provider", Json.obj("base_url" -> primary.url), fallback = Some(fallback.id))
    val lbOther = provider("balanced, available", Json.obj("base_url" -> other.url), None)
    val lb = provider("balancer", Json.obj(), None, kind = "loadbalancer", options = Json.obj("loadbalancing" -> "round_robin", "refs" -> Json.arr(p.id, lbOther.id)))
    save(fallback, p, lbOther, lb)
    val pushed = admin("PUT", s"/providers/${p.id}/states", Json.obj("states" -> Json.arr(Json.obj("status" -> "down", "ttl" -> 60000, "reason" -> "maintenance"))))
    assertEquals(pushed.json.select("accepted").asOpt[Int], Some(1), pushed.body)
    assertEquals(call(p), Right("whole-backup"))
    assertEquals((1 to 2).map(_ => call(lb).toOption.getOrElse("error")).toSeq, Seq("whole-other", "whole-other"))
    assertEquals(primary.calls.get(), 0, "a provider that is down gets no call")
  }

  test("a state received from a leader expires on the local clock, after what was left of its ttl") {
    // received "later" than any refresh of the table, so the agent of the node leaves it alone
    val now = System.currentTimeMillis() + 60000L
    val state = PushedState.fromWire(Json.obj("provider" -> "provider_cluster_test", "target" -> "gpu-1", "status" -> "saturated", "load" -> 0.4), ttl = 1500L, receivedAt = now).get
    assertEquals(state.expiresAt, now + 1500L)
    ProviderStates.put(state)
    assertEquals(ProviderStates.get("provider_cluster_test", Some("gpu-1"), now + 1000L).map(_.status), Some(TargetStatus.Saturated))
    assertEquals(ProviderStates.get("provider_cluster_test", Some("gpu-1"), now + 1600L), None)
    ProviderStates.remove("provider_cluster_test", Some("gpu-1"))

    val p = provider("given to the workers", targets)
    save(p)
    admin("PUT", s"/providers/${p.id}/states", Json.obj("states" -> Json.arr(Json.obj("target" -> "gpu-2", "status" -> "draining", "ttl" -> 60000))))
    val cluster = admin("GET", "/cluster/provider-states")
    assertEquals(cluster.status, 200, cluster.body)
    val shared = cluster.json.select("states").as[Seq[JsObject]].find(_.select("provider").asOpt[String].contains(p.id)).get
    assertEquals(shared.select("target").asString, "gpu-2")
    assertEquals(shared.select("status").asString, "draining")
    assert(shared.select("ttl").as[Long] > 50000L && shared.select("ttl").as[Long] <= 60000L, shared.stringify)
  }
}
