package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.decorators.DecisionModelClientWithAuditing
import com.cloud.apim.otoroshi.extensions.aigateway.entities.DecisionModel
import com.cloud.apim.otoroshi.extensions.aigateway.{DecisionImages, LlmExtensionOneOtoroshiServerPerSuite}
import com.github.luben.zstd.Zstd
import org.apache.pekko.util.ByteString
import otoroshi.models.EntityLocation
import otoroshi.next.models.*
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.{OpenAiCompatApi, SystemOneBodies}
import play.api.libs.json.*
import play.api.libs.ws.WSBodyWritables.{writeableOf_Bytes, writeableOf_String}
import reactor.core.publisher.Mono
import reactor.netty.http.server.HttpServerResponse

import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.zip.GZIPOutputStream
import java.util.{Base64, UUID}
import scala.concurrent.duration.DurationInt

// The pictures of a decision: `images` inside the gateway (data urls, the way Cloudflare takes them), TypeSafe
// `assets` on its own api since Jev 1.14. `/systemone` reads both, compressed and multipart bodies included, and
// a TypeSafe server is given its assets as the caller wrote them.
class DecisionImagesSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  val jev = "jev-1.14.0"
  // a png of one pixel, and the jpeg magic bytes: the fake servers look at nothing else
  val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
  val jpeg = "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAP"
  val pngBytes: Array[Byte] = Base64.getDecoder.decode(png)

  val question: JsObject = Json.obj("urgent" -> Json.obj("type" -> "noul", "instructions" -> "Is `photo` a broken screen ?"))

  def answer(model: String): JsObject = Json.obj(
    "model" -> model,
    "answers" -> Json.obj("urgent" -> Json.obj("type" -> "noul", "noul" -> 0.95)),
    "usage" -> Json.obj("input_tokens" -> 1200, "output_tokens" -> 0),
  )

  private def send(response: HttpServerResponse, body: JsValue) =
    response.status(200).addHeader("Content-Type", "application/json").sendString(Mono.just(body.stringify)).`then`()

  // TypeSafe: what it was given, headers included
  val typesafeCalls = new AtomicInteger(0)
  val typesafeBody = new AtomicReference[JsValue](JsNull)
  val typesafeHeaders = new AtomicReference[Map[String, String]](Map.empty)
  val (typesafePort, _) = createTestServerWithRoutes("decision-images-typesafe", routes => routes
    .post("/v1/systemone", (req, response) => {
      req.receive().aggregate().asString().flatMap { body =>
        typesafeCalls.incrementAndGet()
        typesafeBody.set(Json.parse(body))
        typesafeHeaders.set(Seq("TypeSafe-Danger-Zone-Images", "TypeSafe-Danger-Zone-Long-Context").flatMap(h => Option(req.requestHeaders().get(h)).map(h -> _)).toMap)
        send(response, answer(jev))
      }
    })
  )

  val cloudflareBody = new AtomicReference[JsValue](JsNull)
  val (cloudflarePort, _) = createTestServer("decision-images-cloudflare", (req, response) => {
    req.receive().aggregate().asString().flatMap { body =>
      cloudflareBody.set(Json.parse(body))
      send(response, Json.obj("result" -> answer("clef-flash"), "success" -> true, "errors" -> Json.arr()))
    }
  })

  def decisionModel(name: String, provider: String, connection: JsObject, model: String): DecisionModel = DecisionModel(
    EntityLocation.default, s"decision-model_${UUID.randomUUID()}", name, "", Seq.empty, Map.empty, provider,
    Json.obj("connection" -> (Json.obj("token" -> "xxx", "timeout" -> 30000) ++ connection), "options" -> Json.obj("model" -> model)),
  )

  lazy val typesafe = decisionModel("jev", "typesafe", Json.obj("base_url" -> s"http://localhost:${typesafePort}/v1"), jev)
  lazy val cloudflare = decisionModel("workers", "cloudflare", Json.obj("base_url" -> s"http://localhost:${cloudflarePort}/client/v4", "account_id" -> "acc123"), "@cf/cloudflare/clef-flash")

  lazy val setup: Unit = {
    Seq(typesafe, cloudflare).foreach(m => assert(client.forLlmEntity("decision-models").upsertEntity(m).awaitf(10.seconds).createdOrUpdated, s"${m.name} should be saved"))
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(NgRoute(
      location = EntityLocation.default,
      id = UUID.randomUUID().toString,
      name = "decision images",
      description = "decision images",
      tags = Seq.empty,
      metadata = Map.empty,
      enabled = true,
      debugFlow = false,
      capture = false,
      exportReporting = false,
      frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath("decision-images.oto.tools"))),
      backend = NgBackend.empty.copy(targets = Seq(NgTarget.default)),
      plugins = NgPlugins(Seq(NgPluginInstance(
        plugin = s"cp:${classOf[OpenAiCompatApi].getName}",
        config = NgPluginInstanceConfig(Json.obj("decision_model_refs" -> Json.arr(typesafe.id, cloudflare.id))),
      ))),
    )).awaitf(10.seconds)
    await(10.seconds)
  }

  def url(path: String) = s"http://decision-images.oto.tools:${port}/v1/${path}"

  def postBytes(path: String, body: ByteString, headers: (String, String)*) =
    client.client.url(url(path)).withHttpHeaders(headers*).post(body).awaitf(30.seconds)

  def gzip(bytes: Array[Byte]): Array[Byte] = {
    val out = new ByteArrayOutputStream()
    val gz = new GZIPOutputStream(out)
    gz.write(bytes)
    gz.close()
    out.toByteArray
  }

  val boundary = "jev-boundary-42"

  // a multipart body as a TypeSafe sdk writes it: the json in `request`, a part per picture
  def multipart(parts: (String, String, Array[Byte])*): ByteString = {
    val builder = ByteString.newBuilder
    parts.foreach { case (name, contentType, data) =>
      val filename = if (name == "request") "" else s"; filename=\"${name}.png\""
      builder.putBytes(s"--${boundary}\r\nContent-Disposition: form-data; name=\"${name}\"${filename}\r\nContent-Type: ${contentType}\r\n\r\n".getBytes("UTF-8"))
      builder.putBytes(data)
      builder.putBytes("\r\n".getBytes("UTF-8"))
    }
    builder.putBytes(s"--${boundary}--\r\n".getBytes("UTF-8"))
    builder.result()
  }

  test("the pictures of the gateway become TypeSafe assets, referenced before the state") {
    val body = Json.obj(
      "state" -> "The screen of the customer's phone.",
      "questions" -> question,
      "images" -> Json.arr(s"data:image/png;base64,${png}", Json.obj("content_type" -> "image/jpeg", "base64" -> jpeg), jpeg),
    )
    val written = DecisionImages.toAssets(body)
    assert(written.isRight, s"the pictures should be written as assets, got ${written}")
    val assets = written.toOption.get.select("assets").as[JsObject]
    assertEquals(assets.values.map(_.select("format").asString).toSeq, Seq("png", "jpeg", "jpeg"))
    assertEquals(assets.values.map(_.select("data_base64").asString).toSeq, Seq(png, jpeg, jpeg))
    assertEquals(assets.values.map(_.select("type").asString).toSeq, Seq("image", "image", "image"))
    // where the gateway shows its pictures: before the state
    assertEquals(written.toOption.get.select("state").as[JsArray], JsArray(assets.keys.toSeq.map(JsString.apply) :+ JsString("The screen of the customer's phone.")))
    assertEquals(written.toOption.get.select("images").asOpt[JsValue], None)
    assertEquals(written.toOption.get.select("questions").as[JsObject], question)
    // nothing to do without pictures
    assertEquals(DecisionImages.toAssets(Json.obj("state" -> "x", "questions" -> question)), Right(Json.obj("state" -> "x", "questions" -> question)))
    // the assets already written are kept, references in place
    val both = DecisionImages.toAssets(body ++ Json.obj("assets" -> Json.obj("ref-z" -> Json.obj("type" -> "image", "data_base64" -> png, "format" -> "png"))))
    assertEquals(both.toOption.map(_.select("assets").as[JsObject].keys.size), Some(4))
    assertEquals(both.toOption.flatMap(_.at("assets.ref-z.data_base64").asOpt[String]), Some(png))
    // TypeSafe takes PNG and JPEG only
    val webp = DecisionImages.toAssets(body ++ Json.obj("images" -> Json.arr("data:image/webp;base64,UklGRgAAAA")))
    assertEquals(webp.left.toOption.map(_.select("status").as[Int]), Some(400))
  }

  test("TypeSafe assets are read in the internal format, each reference named after its picture") {
    val body = Json.obj(
      "state" -> Json.obj("photo" -> "ref-a", "note" -> "compare ref-a with ref-b"),
      "questions" -> Json.obj("animal" -> Json.obj("type" -> "choice", "instructions" -> "Which one is `photo` ?", "criteria" -> Json.obj("ref-b" -> "ref-b", "dog" -> "a dog"))),
      "assets" -> Json.obj(
        "ref-a" -> Json.obj("type" -> "image", "data_base64" -> png, "format" -> "png"),
        "ref-b" -> Json.obj("data_base64" -> jpeg, "format" -> "JPG"),
      ),
    )
    val read = DecisionImages.fromAssets(body)
    assert(read.isRight, s"the assets should be read, got ${read}")
    val internal = read.toOption.get
    assertEquals(internal.select("images").as[Seq[String]], Seq(s"data:image/png;base64,${png}", s"data:image/jpeg;base64,${jpeg}"))
    assertEquals(internal.select("state").as[JsObject], Json.obj("photo" -> "[image 1]", "note" -> "compare [image 1] with [image 2]"))
    // an option is a name, never a picture: only the values are read
    assertEquals(internal.at("questions.animal.criteria").as[JsObject], Json.obj("ref-b" -> "[image 2]", "dog" -> "a dog"))
    assertEquals(internal.select("assets").asOpt[JsValue], None)
    // what TypeSafe refuses is refused here too, whatever the provider
    def status(assets: JsObject) = DecisionImages.fromAssets(body ++ Json.obj("assets" -> assets)).left.toOption.map(_.select("status").as[Int])
    assertEquals(status(Json.obj("ref-a" -> Json.obj("data_base64" -> png, "format" -> "png"), "nowhere" -> Json.obj("data_base64" -> png, "format" -> "png"))), Some(400))
    assertEquals(status(Json.obj("ref-a" -> Json.obj("data_base64" -> png, "format" -> "gif"))), Some(400))
    assertEquals(status(Json.obj("ref-a" -> Json.obj("data_base64" -> png))), Some(400))
    assertEquals(status(Json.obj("ref-a" -> Json.obj("part" -> "photo", "format" -> "png"))), Some(400))
    assertEquals(DecisionImages.fromAssets(Json.obj("state" -> "x")), Right(Json.obj("state" -> "x")))
  }

  test("bodies are read as the TypeSafe api reads them: gzip, zstd, multipart") {
    val json = Json.obj("state" -> "x", "questions" -> question)
    val bytes = ByteString(json.stringify)
    assertEquals(SystemOneBodies.decoded(ByteString(gzip(bytes.toArray)), Some("gzip")), Right(bytes))
    assertEquals(SystemOneBodies.decoded(ByteString(Zstd.compress(bytes.toArray)), Some("zstd")), Right(bytes))
    assertEquals(SystemOneBodies.decoded(bytes, Some("identity")), Right(bytes))
    assertEquals(SystemOneBodies.decoded(bytes, Some("br")).left.toOption.map(_.select("status").as[Int]), Some(415))
    assertEquals(SystemOneBodies.decoded(bytes, Some("gzip, zstd")).left.toOption.map(_.select("status").as[Int]), Some(415))
    // a plain body is read as it is, whatever a header says of it
    assertEquals(SystemOneBodies.decoded(bytes, None), Right(bytes))
    assertEquals(SystemOneBodies.decoded(bytes, Some("gzip")), Right(bytes))
    assertEquals(SystemOneBodies.decoded(bytes, Some("zstd")), Right(bytes))
    // a compressed body that cannot be read is an error
    val broken = ByteString(gzip(bytes.toArray).take(12))
    assertEquals(SystemOneBodies.decoded(broken, Some("gzip")).left.toOption.map(_.select("status").as[Int]), Some(400))
    // the bytes of a part become the base64 of its asset, its format said by its content type
    val request = json ++ Json.obj("assets" -> Json.obj("ref-a" -> Json.obj("type" -> "image", "part" -> "photo")))
    val assembled = SystemOneBodies.assembled(Seq(("request", "application/json", ByteString(request.stringify)), ("photo", "image/png", ByteString(pngBytes))))
    assertEquals(assembled.toOption.flatMap(_.select("assets").asOpt[JsObject]), Some(Json.obj("ref-a" -> Json.obj("type" -> "image", "data_base64" -> png, "format" -> "png"))))
    // a part without a content type is read from its bytes
    val untyped = SystemOneBodies.assembled(Seq(("request", "application/json", ByteString(request.stringify)), ("photo", "text/plain", ByteString(pngBytes))))
    assertEquals(untyped.toOption.flatMap(_.at("assets.ref-a.format").asOpt[String]), Some("png"))
    def status(parts: (String, String, ByteString)*) = SystemOneBodies.assembled(parts).left.toOption.map(_.select("status").as[Int])
    assertEquals(status(("photo", "image/png", ByteString(pngBytes))), Some(400))
    assertEquals(status(("request", "application/json", ByteString(request.stringify)), ("photo", "image/png", ByteString(pngBytes)), ("other", "image/png", ByteString(pngBytes))), Some(400))
    assertEquals(status(("request", "application/json", ByteString(request.stringify)), ("photo", "image/gif", ByteString(pngBytes))), Some(400))
    // an event says how many pictures there were, not what they were
    val audited = DecisionModelClientWithAuditing.auditedBody(request ++ Json.obj("images" -> Json.arr("a", "b")))
    assertEquals(audited.select("assets").asOpt[String], Some("1 asset(s)"))
    assertEquals(audited.select("images").asOpt[String], Some("2 image(s)"))
  }

  test("assets on /systemone reach TypeSafe as they were written, with the danger zone headers") {
    setup
    val body = Json.obj(
      "model" -> s"${typesafe.id}###${jev}",
      "state" -> Json.obj("photo" -> "ref-a", "ticket" -> "My screen is broken, see ref-a"),
      "questions" -> question,
      "assets" -> Json.obj("ref-a" -> Json.obj("type" -> "image", "data_base64" -> png, "format" -> "png")),
    )
    val resp = postBytes("systemone", ByteString(body.stringify), "Content-Type" -> "application/json", "TypeSafe-Danger-Zone-Long-Context" -> "true")
    assertEquals(resp.status, 200, s"status should be 200, got ${resp.body}")
    assertEquals(resp.json.at("answers.urgent.noul").as[BigDecimal], BigDecimal("0.95"))
    val got = typesafeBody.get()
    assertEquals(got.select("assets").as[JsObject], body.select("assets").as[JsObject])
    assertEquals(got.select("state").as[JsObject], body.select("state").as[JsObject])
    assertEquals(got.select("questions").as[JsObject], question)
    assertEquals(got.select("images").asOpt[JsValue], None, "the internal format is the gateway's business")
    assertEquals(got.select("model").asString, jev)
    assertEquals(typesafeHeaders.get(), Map("TypeSafe-Danger-Zone-Images" -> "true", "TypeSafe-Danger-Zone-Long-Context" -> "true"))
  }

  test("the same assets reach Cloudflare as pictures before the state, a gzip body being read all the same") {
    setup
    val body = Json.obj(
      "model" -> s"${cloudflare.id}###@cf/cloudflare/clef-flash",
      "state" -> Json.obj("photo" -> "ref-a", "ticket" -> "My screen is broken, see ref-a"),
      "questions" -> question,
      "assets" -> Json.obj("ref-a" -> Json.obj("type" -> "image", "data_base64" -> png, "format" -> "png")),
    )
    val resp = postBytes("systemone", ByteString(gzip(body.stringify.getBytes("UTF-8"))), "Content-Type" -> "application/json", "Content-Encoding" -> "gzip")
    assertEquals(resp.status, 200, s"status should be 200, got ${resp.body}")
    val got = cloudflareBody.get()
    assertEquals(got.select("images").as[Seq[String]], Seq(s"data:image/png;base64,${png}"))
    assertEquals(got.select("state").as[JsObject], Json.obj("photo" -> "[image 1]", "ticket" -> "My screen is broken, see [image 1]"))
    assertEquals(got.select("assets").asOpt[JsValue], None)
    // a plain request sent as text, as a hand written client may send it
    val plain = Json.obj("model" -> s"${cloudflare.id}###@cf/cloudflare/clef-flash", "state" -> "My screen is broken", "questions" -> question)
    val plainResp = client.client.url(url("systemone")).post(plain.stringify).awaitf(30.seconds)
    assertEquals(plainResp.status, 200, s"status should be 200, got ${plainResp.body}")
    assertEquals(cloudflareBody.get().select("state").asString, "My screen is broken")
    assertEquals(cloudflareBody.get().select("images").asOpt[JsValue], None)
  }

  test("a zstd multipart request, as the TypeSafe sdks send it, reaches TypeSafe as json") {
    setup
    val request = Json.obj(
      "model" -> s"${typesafe.id}###${jev}",
      "state" -> Json.obj("photo" -> "ref-a"),
      "questions" -> Json.obj("where" -> Json.obj("type" -> "box", "instructions" -> "The crack in `photo`")),
      "assets" -> Json.obj("ref-a" -> Json.obj("type" -> "image", "part" -> "photo")),
    )
    val body = multipart(("request", "application/json", request.stringify.getBytes("UTF-8")), ("photo", "image/png", pngBytes))
    val resp = postBytes("systemone", ByteString(Zstd.compress(body.toArray)), "Content-Type" -> s"multipart/form-data; boundary=${boundary}", "Content-Encoding" -> "zstd")
    assertEquals(resp.status, 200, s"status should be 200, got ${resp.body}")
    val got = typesafeBody.get()
    assertEquals(got.select("assets").as[JsObject], Json.obj("ref-a" -> Json.obj("type" -> "image", "data_base64" -> png, "format" -> "png")))
    assertEquals(got.select("state").as[JsObject], Json.obj("photo" -> "ref-a"))
    // a box question is behind the same header as the pictures, and the caller asked for no long context
    assertEquals(typesafeHeaders.get(), Map("TypeSafe-Danger-Zone-Images" -> "true"))
  }

  test("the pictures of an OpenAI request on /decisions reach TypeSafe as assets") {
    setup
    val body = Json.obj(
      "model" -> s"${typesafe.id}###${jev}",
      "input" -> Json.arr(Json.obj("role" -> "user", "content" -> Json.arr(
        Json.obj("type" -> "input_text", "text" -> "My screen is broken."),
        Json.obj("type" -> "input_image", "image_url" -> s"data:image/png;base64,${png}"),
      ))),
      "questions" -> Json.arr(Json.obj("type" -> "predicate", "name" -> "urgent", "instructions" -> "Is it a broken screen ?")),
    )
    val resp = postBytes("decisions", ByteString(body.stringify), "Content-Type" -> "application/json")
    assertEquals(resp.status, 200, s"status should be 200, got ${resp.body}")
    // the answer in the format of the request
    assertEquals(resp.json.select("answers").as[Seq[JsObject]].map(_.select("type").asString), Seq("predicate"))
    val got = typesafeBody.get()
    val assets = got.select("assets").as[JsObject]
    assertEquals(assets.values.toSeq, Seq(Json.obj("type" -> "image", "data_base64" -> png, "format" -> "png")))
    assertEquals(got.select("state").as[JsArray], Json.arr(assets.keys.head, "My screen is broken."))
    assertEquals(got.select("images").asOpt[JsValue], None)
    assertEquals(typesafeHeaders.get(), Map("TypeSafe-Danger-Zone-Images" -> "true"))
  }

  test("a body the TypeSafe api would refuse is refused before any provider is called") {
    setup
    val before = typesafeCalls.get()
    val json = Json.obj("model" -> s"${typesafe.id}###${jev}", "state" -> "x", "questions" -> question)
    val brotli = postBytes("systemone", ByteString(json.stringify), "Content-Type" -> "application/json", "Content-Encoding" -> "br")
    assertEquals(brotli.status, 415, s"status should be 415, got ${brotli.body}")
    val unreferenced = postBytes("systemone", ByteString((json ++ Json.obj("assets" -> Json.obj("ref-a" -> Json.obj("data_base64" -> png, "format" -> "png")))).stringify), "Content-Type" -> "application/json")
    assertEquals(unreferenced.status, 400, s"status should be 400, got ${unreferenced.body}")
    assertEquals(unreferenced.json.at("detail.error_type").asString, "invalid_request_error")
    val orphan = multipart(("request", "application/json", json.stringify.getBytes("UTF-8")), ("photo", "image/png", pngBytes))
    val orphanResp = postBytes("systemone", orphan, "Content-Type" -> s"multipart/form-data; boundary=${boundary}")
    assertEquals(orphanResp.status, 400, s"status should be 400, got ${orphanResp.body}")
    assertEquals(typesafeCalls.get(), before, "TypeSafe should not have been called")
  }
}
