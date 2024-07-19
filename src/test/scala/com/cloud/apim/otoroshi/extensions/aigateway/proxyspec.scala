package com.cloud.apim.otoroshi.extensions.aigateway

import akka.actor.ActorSystem
import akka.actor.setup.ActorSystemSetup
import akka.stream.Materializer
import akka.util.ByteString
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsValue, Json}

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.DurationInt

class AiProxySpec extends munit.FunSuite {
  test("basic test") {
    implicit val ec = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(4))
    val plugin = new AiLlmProxy()
    val payload = Json.parse(
      """{
        |  "messages": [
        |    {
        |      "role": "user",
        |      "content": "What is 1+1?"
        |    }
        |  ]
        |}""".stripMargin)
    val ref = new AtomicReference[JsValue]()
    val fu = plugin.call(payload, Some(ref)).map {
      case Left(err) =>
        println(s"error: ${err}")
        println(s"body: ${ref.get().prettify}")
      case Right(resp) => {
        println(s"status: ${resp.status}")
        println(s"headers: ${resp.headers}")
        println(s"body: ${ref.get().prettify}")
      }
    }
    Await.result(fu, 30.seconds)
  }
}
