package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.LlmExtensionOneOtoroshiServerPerSuite
import com.cloud.apim.otoroshi.extensions.aigateway.catalog.ModelEndpoints
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AudioModel
import org.apache.pekko.util.ByteString
import otoroshi.models.EntityLocation
import otoroshi.next.models.*
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.OpenAICompatTranslation
import play.api.libs.json.*
import play.api.libs.ws.WSBodyWritables.given
import reactor.core.publisher.Mono

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.DurationInt

// `/audio/translations` writes down an audio file in English. The OpenAI client reads its own translation section,
// then the transcription one, and the Whisper models are known to translate even if no price table says so.
class TranslationsSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  val openAiForm = new AtomicReference[String]("")

  val (openAiPort, _) = createTestServerWithRoutes("openai-translations", routes => routes
    .post("/audio/translations", (req, response) => req.receive().aggregate().asString().flatMap { raw =>
      openAiForm.set(raw)
      response.status(200).addHeader("Content-Type", "application/json")
        .sendString(Mono.just(Json.obj("text" -> "Hello, how are you?").stringify)).`then`()
    })
  )

  lazy val audio: AudioModel = AudioModel(
    EntityLocation.default, "translations-openai", "openai translations", "", Seq.empty, Map.empty, "openai",
    Json.obj(
      "connection" -> Json.obj("base_url" -> s"http://localhost:${openAiPort}", "token" -> "xxx", "timeout" -> 30000),
      "stt" -> Json.obj("enabled" -> true, "model" -> "gpt-4o-transcribe", "temperature" -> 0.9),
      "translate" -> Json.obj("enabled" -> true, "model" -> "whisper-1", "temperature" -> 0.2, "response_format" -> "json"),
    ),
  )

  lazy val setup: Unit = {
    val route = NgRoute(
      location = EntityLocation.default,
      id = "translations-route",
      name = "translations route",
      description = "translations route",
      tags = Seq.empty,
      metadata = Map.empty,
      enabled = true,
      debugFlow = false,
      capture = false,
      exportReporting = false,
      frontend = NgFrontend.empty.copy(domains = Seq(NgDomainAndPath("translations.oto.tools"))),
      backend = NgBackend.empty.copy(targets = Seq(NgTarget.default)),
      plugins = NgPlugins(Seq(NgPluginInstance(
        plugin = s"cp:${classOf[OpenAICompatTranslation].getName}",
        config = NgPluginInstanceConfig(Json.obj("refs" -> Json.arr(audio.id))),
      )))
    )
    client.forEntity("ai-gateway.extensions.cloud-apim.com", "v1", "audio-models").upsertEntity(audio).awaitf(10.seconds)
    client.forEntity("proxy.otoroshi.io", "v1", "routes").upsertEntity(route).awaitf(10.seconds)
    await(10.seconds)
  }

  val boundary = "otoroshi-translations"

  test("a translation is asked with the model and the settings of the translation section") {
    setup
    val body = ByteString(s"--${boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"bonjour.mp3\"\r\nContent-Type: audio/mpeg\r\n\r\n") ++
      ByteString("fake-mp3") ++ ByteString(s"\r\n--${boundary}--\r\n")
    val resp = client.client.url(s"http://translations.oto.tools:${port}/v1/audio/translations")
      .withHttpHeaders("Content-Type" -> s"multipart/form-data; boundary=${boundary}")
      .post(body)
      .awaitf(30.seconds)
    assertEquals(resp.status, 200, resp.body)
    assertEquals(resp.json.select("text").asOpt[String], Some("Hello, how are you?"))
    val sent = openAiForm.get()
    // the value of each part of that name: what follows the first blank line after its headers
    def values(name: String): Seq[String] = s"name=\"${java.util.regex.Pattern.quote(name)}\"[^\\r\\n]*\\r?\\n(?:[^\\r\\n]+\\r?\\n)*?\\r?\\n([^\\r\\n]*)".r
      .findAllMatchIn(sent).map(_.group(1)).toSeq
    assertEquals(values("model"), Seq("whisper-1"), "the translation model, not the transcription one")
    assertEquals(values("temperature"), Seq("0.2"), "a temperature, not the response format")
    assertEquals(values("response_format"), Seq("json"))
  }

  test("the whisper models translate, their turbo variants and the other transcription models do not") {
    val transcribes = Seq(ModelEndpoints.AudioTranscriptions)
    assertEquals(ModelEndpoints.withTranslations(transcribes, "whisper-1"), transcribes :+ ModelEndpoints.AudioTranslations)
    assertEquals(ModelEndpoints.withTranslations(transcribes, "whisper-large-v3"), transcribes :+ ModelEndpoints.AudioTranslations)
    assertEquals(ModelEndpoints.withTranslations(transcribes, "whisper-large-v3-turbo"), transcribes)
    assertEquals(ModelEndpoints.withTranslations(transcribes, "gpt-4o-transcribe"), transcribes)
    assertEquals(ModelEndpoints.withTranslations(Seq("chat_completions"), "whisper-1"), Seq("chat_completions"))
  }
}
