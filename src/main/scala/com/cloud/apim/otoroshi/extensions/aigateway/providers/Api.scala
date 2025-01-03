package com.cloud.apim.otoroshi.extensions.aigateway.providers

import akka.stream.scaladsl.Source
import play.api.libs.json.JsValue
import play.api.libs.ws.WSResponse

import scala.concurrent.{ExecutionContext, Future}

trait ApiClient[Resp, Chunk] {

  def supportsTools: Boolean
  def supportsStreaming: Boolean

  def call(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[Resp]
  def callWithToolSupport(method: String, path: String, body: Option[JsValue], mcpConnectors: Seq[String])(implicit ec: ExecutionContext): Future[Resp] = {
    call(method, path, body)
  }

  def stream(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[(Source[Chunk, _], WSResponse)]
  def streamWithToolSupport(method: String, path: String, body: Option[JsValue], mcpConnectors: Seq[String])(implicit ec: ExecutionContext): Future[(Source[Chunk, _], WSResponse)] = {
    stream(method, path, body)
  }
}
