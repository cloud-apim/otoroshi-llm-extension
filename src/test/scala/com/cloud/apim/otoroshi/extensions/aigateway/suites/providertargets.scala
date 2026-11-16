package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.providers.{ProviderTargetAttempts, ProviderTargets, TargetsChatClient}
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatMessage, ChatPrompt, LlmExtensionOneOtoroshiServerPerSuite}
import org.apache.pekko.stream.scaladsl.Sink
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.*
import reactor.core.publisher.{Flux, Mono}

import java.nio.charset.StandardCharsets
import java.util.{Base64, UUID}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.DurationInt

// Several targets for one provider: copies of the same server, one picked for each call among those that can take
// it, another one tried when it fails. `connection.targets`, or a `base_url` holding several urls.
class ProviderTargetsSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  // a server that answers with its own name, or fails with `status`. One per case: a server of the suite does not
  // take many calls
  class Upstream(val name: String, status: Int = 200, headers: Map[String, String] = Map.empty) {
    val calls = new AtomicInteger(0)
    val (port, _) = createTestServerWithRoutes(s"targets-$name", routes => routes
      .post("/v1/chat/completions", (req, response) => req.receive().aggregate().asString().flatMap { raw =>
        calls.incrementAndGet()
        val streamed = Json.parse(raw).select("stream").asOpt[Boolean].contains(true)
        val withHeaders = headers.foldLeft(response.status(status)) { case (r, (k, v)) => r.addHeader(k, v) }
        if (status != 200) {
          withHeaders.addHeader("Content-Type", "application/json").sendString(Mono.just(Json.obj("error" -> Json.obj("message" -> s"$name refuses", "type" -> "server_error")).stringify)).`then`()
        } else if (streamed) {
          def chunk(delta: JsObject, finish: JsValue = JsNull) = "data: " + Json.obj("id" -> "chatcmpl-1", "object" -> "chat.completion.chunk", "created" -> 1700000000, "model" -> "gpt-4o-mini",
            "choices" -> Json.arr(Json.obj("index" -> 0, "delta" -> delta, "finish_reason" -> finish))).stringify + "\n\n"
          withHeaders.addHeader("Content-Type", "text/event-stream").sendString(Flux.just(
            chunk(Json.obj("role" -> "assistant", "content" -> name)), chunk(Json.obj(), JsString("stop")), "data: [DONE]\n\n",
          )).`then`()
        } else {
          withHeaders.addHeader("Content-Type", "application/json").sendString(Mono.just(Json.obj(
            "id" -> "chatcmpl-1", "object" -> "chat.completion", "created" -> 1700000000, "model" -> "gpt-4o-mini",
            "choices" -> Json.arr(Json.obj("index" -> 0, "finish_reason" -> "stop", "message" -> Json.obj("role" -> "assistant", "content" -> name))),
            "usage" -> Json.obj("prompt_tokens" -> 10, "completion_tokens" -> 5, "total_tokens" -> 15),
          ).stringify)).`then`()
        }
      })
    )
    def url: String = s"http://localhost:${port}/v1"
    def target(weight: Int = 1): JsObject = Json.obj("id" -> name, "base_url" -> url, "weight" -> weight)
  }

  def provider(name: String, connection: JsObject, fallback: Option[String] = None, options: JsObject = Json.obj(), pushedState: Option[JsObject] = None): AiProvider = {
    val json = AiProvider(
      id = s"provider_${UUID.randomUUID()}",
      name = name,
      provider = "openai",
      connection = Json.obj("token" -> "sk-test", "timeout" -> 10000) ++ connection,
      options = Json.obj("model" -> "gpt-4o-mini") ++ options,
      providerFallback = fallback,
    ).json.asObject ++ pushedState.map(p => Json.obj("pushed_state" -> p)).getOrElse(Json.obj())
    AiProvider.format.reads(json).get
  }

  def save(providers: AiProvider*): Unit = {
    providers.foreach(p => assert(client.forLlmEntity("providers").upsertEntity(p).awaitf(10.seconds).createdOrUpdated, s"${p.name} should be saved"))
    await(1.second)
  }

  def ext: AiExtension = otoroshi.env.adminExtensions.extension[AiExtension].get

  val basic: String = Base64.getEncoder.encodeToString("admin-api-apikey-id:admin-api-apikey-secret".getBytes(StandardCharsets.UTF_8))

  def pushStates(providerId: String, states: JsObject*): JsValue = {
    val resp = client.call("PUT", s"http://otoroshi-api.oto.tools:$port/api/extensions/cloud-apim/extensions/ai-extension/providers/$providerId/states",
      Map("Authorization" -> s"Basic $basic"), Some(Json.obj("states" -> JsArray(states)))).awaitf(10.seconds)
    assertEquals(resp.status, 200, resp.body)
    resp.json
  }

  // the answer of a blocking call (the name of the target that served it), or its error, and the target audited
  def call(p: AiProvider): (Either[JsValue, String], Option[JsObject]) = {
    given env: Env = otoroshi.env
    val attrs = TypedMap.empty
    val chatClient = ext.states.provider(p.id).get.getChatClient().get
    val result = chatClient.call(ChatPrompt(Seq(ChatMessage.userStrInput("hello"))), attrs, Json.obj())(using otoroshi.executionContext, env).awaitf(30.seconds)
    (result.map(_.generations.head.message.content), ProviderTargetAttempts.of(attrs, p.id))
  }

  def stream(p: AiProvider): Either[JsValue, String] = {
    given env: Env = otoroshi.env
    val chatClient = ext.states.provider(p.id).get.getChatClient().get
    chatClient.stream(ChatPrompt(Seq(ChatMessage.userStrInput("hello"))), TypedMap.empty, Json.obj())(using otoroshi.executionContext, env).awaitf(30.seconds)
      .map(source => source.runWith(Sink.seq)(using otoroshi.materializer).awaitf(30.seconds).flatMap(_.choices.flatMap(_.delta.content)).mkString)
  }

  def code(err: JsValue): Option[String] = err.select("error").select("code").asOpt[String]

  test("a provider without targets keeps its single url and its client") {
    given env: Env = otoroshi.env
    val p = provider("one url", Json.obj("base_url" -> "http://localhost:1/v1"))
    assert(ProviderTargets.of(p).isEmpty)
    assert(!p.getRawChatClient().get.isInstanceOf[TargetsChatClient], "no targets client without targets")
    assertEquals(AiProvider.format.reads(p.json).get.json, p.json, "the json of a provider without the new fields does not change")
    assert(p.json.select("pushed_state").isEmpty, "no pushed_state is written when there is none")
  }

  test("weighted round robin over the targets, a weight of 0 takes a target out of the rotation") {
    val a = new Upstream("rr-a")
    val b = new Upstream("rr-b")
    val c = new Upstream("rr-c")
    val p = provider("weighted", Json.obj("base_url" -> a.url, "targets" -> Json.arr(a.target(1), b.target(2), c.target(0))))
    save(p)
    val served = (1 to 3).map(_ => call(p))
    assertEquals(served.map(_._1.toOption.get).sorted, Seq("rr-a", "rr-b", "rr-b"))
    served.foreach { case (answer, target) =>
      assertEquals(target.flatMap(_.select("id").asOpt[String]), answer.toOption)
      assertEquals(target.flatMap(_.select("attempts").asOpt[Int]), Some(1))
    }
    assertEquals(c.calls.get(), 0)
  }

  test("a base_url holding several urls makes as many targets of weight 1, named by their url") {
    val a = new Upstream("comma-a")
    val b = new Upstream("comma-b")
    val p = provider("comma separated", Json.obj("base_url" -> s"${a.url} , ${b.url},${a.url}"))
    assertEquals(ProviderTargets.of(p).map(t => (t.id, t.baseUrl, t.weight)), Seq((a.url, a.url, 1), (b.url, b.url, 1)))
    save(p)
    val served = (1 to 2).map(_ => call(p))
    assertEquals(served.map(_._1.toOption.get).sorted, Seq("comma-a", "comma-b"))
    served.foreach { case (answer, target) =>
      val url = if (answer.toOption.contains("comma-a")) a.url else b.url
      assertEquals(target.flatMap(_.select("id").asOpt[String]), Some(url))
    }
    // a targets list wins over the urls of the base url
    val listed = provider("listed and comma separated", Json.obj("base_url" -> s"${a.url},${b.url}", "targets" -> Json.arr(Json.obj("id" -> "only", "base_url" -> b.url))))
    assertEquals(ProviderTargets.of(listed).map(_.id), Seq("only"))
  }

  test("a target that fails leaves the call to the next one, up to max_attempts") {
    val failing = new Upstream("retry-failing", 503)
    val ok = new Upstream("retry-ok")
    val p = provider("retries", Json.obj("base_url" -> ok.url, "targets" -> Json.arr(failing.target(), ok.target())))
    save(p)
    val (answer, target) = call(p)
    assertEquals(answer, Right("retry-ok"))
    assertEquals(target.flatMap(_.select("id").asOpt[String]), Some("retry-ok"))
    assertEquals(target.flatMap(_.select("attempts").asOpt[Int]), Some(2))
    assertEquals(failing.calls.get(), 1)
    // a stream is taken over too, when its target failed before answering
    val streamed = provider("retries streamed", Json.obj("base_url" -> ok.url, "targets" -> Json.arr(failing.target(), ok.target())))
    save(streamed)
    assertEquals(stream(streamed), Right("retry-ok"))
  }

  test("the circuit and the quotas are per target: a failing target does not keep the other one out") {
    val failing = new Upstream("circuit-failing", 500)
    val ok = new Upstream("circuit-ok")
    val p = provider("circuit per target", Json.obj("base_url" -> ok.url, "targets" -> Json.arr(failing.target(), ok.target()), "targets_options" -> Json.obj("max_attempts" -> 1)),
      options = Json.obj("circuit_breaker" -> Json.obj("enabled" -> true, "consecutive_failures" -> 1, "cooldown" -> 60000)))
    save(p)
    val first = call(p)._1
    assert(first.isLeft, s"the first call goes to the failing target, and is not taken over: ${first}")
    assertEquals((1 to 2).map(_ => call(p)._1.toOption.getOrElse("error")).toSeq, Seq("circuit-ok", "circuit-ok"))
    assertEquals(failing.calls.get(), 1, "the failing target is out while its circuit is open")

    val throttled = new Upstream("quota-throttled", 429, Map("Retry-After" -> "60"))
    val other = new Upstream("quota-ok")
    val q = provider("quota per target", Json.obj("base_url" -> other.url, "targets" -> Json.arr(throttled.target(), other.target())))
    save(q)
    assertEquals((1 to 2).map(_ => call(q)._1.toOption.getOrElse("error")).toSeq, Seq("quota-ok", "quota-ok"))
    assertEquals(throttled.calls.get(), 1, "the throttled target is out until the provider said to come back")
  }

  test("a saturated target gets no call, and gets them again once its state expired") {
    val a = new Upstream("saturated-a")
    val b = new Upstream("saturated-b")
    val p = provider("pushed states", Json.obj("base_url" -> a.url, "targets" -> Json.arr(a.target(), b.target())), pushedState = Some(Json.obj("enabled" -> true)))
    save(p)
    val pushed = pushStates(p.id, Json.obj("target" -> "saturated-a", "status" -> "saturated", "ttl" -> 2000, "reason" -> "kv cache 97%"))
    assertEquals(pushed.select("accepted").asOpt[Int], Some(1))
    assertEquals((1 to 2).map(_ => call(p)._1.toOption.getOrElse("error")).toSeq, Seq("saturated-b", "saturated-b"))
    await(3.seconds)
    val after = (1 to 2).map(_ => call(p)._1.toOption.get).sorted
    assertEquals(after, Seq("saturated-a", "saturated-b"), "once its state expired, the target is in the rotation again")
  }

  test("no usable target: rejected at once, the fallback takes over, or a saturated target is tried") {
    val a = new Upstream("none-a")
    val backup = new Upstream("none-backup")
    val fallback = provider("fallback of targets", Json.obj("base_url" -> backup.url))
    // nothing was pushed and the targets are down until something says otherwise
    val down = Json.obj("enabled" -> true, "stale_as" -> "down")
    val withFallback = provider("no target, a fallback", Json.obj("base_url" -> a.url, "targets" -> Json.arr(a.target())), Some(fallback.id), pushedState = Some(down))
    val alone = provider("no target, no fallback", Json.obj("base_url" -> a.url, "targets" -> Json.arr(a.target())), pushedState = Some(down))
    val trySaturated = provider("saturated targets tried", Json.obj("base_url" -> a.url, "targets" -> Json.arr(a.target()), "targets_options" -> Json.obj("when_none_available" -> "try_saturated")),
      pushedState = Some(Json.obj("enabled" -> true)))
    save(fallback, withFallback, alone, trySaturated)
    assertEquals(call(withFallback)._1, Right("none-backup"))
    val rejected = call(alone)._1
    assertEquals(rejected.left.toOption.flatMap(code), Some("no_target_available"), s"${rejected}")
    assertEquals(rejected.left.toOption.flatMap(_.select("status").asOpt[Int]), Some(503))
    pushStates(trySaturated.id, Json.obj("target" -> "none-a", "status" -> "saturated", "ttl" -> 60000))
    assertEquals(call(trySaturated)._1, Right("none-a"))
    assertEquals(a.calls.get(), 1, "only the call allowed to try a saturated target reached it")
  }

  test("targets are refused on a kind that does not build its client on a base url, and are checked") {
    val azure = Json.obj(
      "id" -> s"provider_${UUID.randomUUID()}", "name" -> "azure with targets", "provider" -> "azure-openai",
      "connection" -> Json.obj("resource_name" -> "r", "deployment_id" -> "d", "api_key" -> "k", "targets" -> Json.arr(Json.obj("id" -> "one", "base_url" -> "https://one.example.com"))),
      "options" -> Json.obj(),
    )
    val refused = client.forLlmEntity("providers").createRaw(azure).awaitf(10.seconds)
    assertEquals(refused.status, 400, refused.state)
    assert(ProviderTargets.read("azure-openai", Json.obj("base_url" -> "https://a.example.com,https://b.example.com")).isEmpty, "its base url is not split either")
    val bad = Json.obj(
      "id" -> s"provider_${UUID.randomUUID()}", "name" -> "bad targets", "provider" -> "openai",
      "connection" -> Json.obj("targets" -> Json.arr(Json.obj("id" -> "one", "base_url" -> "not an url"), Json.obj("id" -> "one", "base_url" -> "https://one.example.com"))),
      "options" -> Json.obj(),
    )
    val invalid = client.forLlmEntity("providers").createRaw(bad).awaitf(10.seconds)
    assertEquals(invalid.status, 400, invalid.state)
  }
}
