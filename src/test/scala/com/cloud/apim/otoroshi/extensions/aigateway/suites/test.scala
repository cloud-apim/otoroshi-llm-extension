package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.LlmExtensionOneOtoroshiServerPerSuite
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.Json
import reactor.core.publisher.Mono
import reactor.netty.ByteBufFlux

class GeminiOpenaiSuite extends LlmExtensionOneOtoroshiServerPerSuite {

  test("basic call") {
    client.wsclient
      .wiretap(true)
      .headers(hds => hds
        .set("Content-Type", "application/json")
        .set("Host", "generativelanguage.googleapis.com")
        .set("Authorization", s"Bearer ${sys.env("GEMINI_API_KEY")}")
      )
      .post()
      .uri("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions")
      .send(ByteBufFlux.fromString(Mono.just(Json.obj(
        "model" -> "gemini-1.5-flash",
        "stream" -> false,
        "messages" -> Json.arr(
          Json.obj(
            "role" -> "user",
            "content" -> "hey how are you ?"
          )
        )
      ).stringify)))
      .responseContent()
      .aggregate()
      .asString()
      .block()
      .debug(bb => {
        println(s"bb: $bb")
      })
  }

}
