package com.cloud.apim.otoroshi.extensions.aigateway.providers

import akka.stream.scaladsl.Source
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.{JsValue, Json}
import play.api.libs.ws.WSResponse

import scala.concurrent.{ExecutionContext, Future}

trait ApiClient[Resp, Chunk] {

  def supportsTools: Boolean
  def supportsStreaming: Boolean
  def supportsCompletion: Boolean

  def call(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[Either[JsValue, Resp]]
  def callWithToolSupport(method: String, path: String, body: Option[JsValue], mcpConnectors: Seq[String], attrs: TypedMap)(implicit ec: ExecutionContext): Future[Either[JsValue, Resp]] = {
    call(method, path, body)
  }

  def stream(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[Either[JsValue, (Source[Chunk, _], WSResponse)]]
  def streamWithToolSupport(method: String, path: String, body: Option[JsValue], mcpConnectors: Seq[String], attrs: TypedMap)(implicit ec: ExecutionContext): Future[Either[JsValue, (Source[Chunk, _], WSResponse)]] = {
    stream(method, path, body)
  }
}

trait NoStreamingApiClient[Resp] {

  def supportsTools: Boolean
  def supportsCompletion: Boolean
  final def supportsStreaming: Boolean = false

  def call(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[Either[JsValue, Resp]]
  def callWithToolSupport(method: String, path: String, body: Option[JsValue], mcpConnectors: Seq[String], attrs: TypedMap)(implicit ec: ExecutionContext): Future[Either[JsValue, Resp]] = {
    call(method, path, body)
  }
}


object ProviderHelpers {
  def responseBody(resp: WSResponse): JsValue = {
    if (resp.contentType.contains("application/json")) {
      resp.json
    } else {
      Json.parse(resp.body)
    }
  }
  def logBadResponse(from: String, resp: WSResponse): Unit = {
    AiExtension.logger.error(s"Response with error from '${from}': ${resp.status} - ${resp.body}")
  }
  def logCall(from: String, method: String, url: String, body: Option[JsValue] = None, opts: Map[String, String] = Map.empty)(implicit env: Env): Unit = {
    if (env.isDev || AiExtension.logger.isDebugEnabled) {
      val msg = s"calling provider '${from}' - ${method} - ${url} - ${opts} - ${body.map(_.prettify).getOrElse("")}"
      if (env.isDev) {
        AiExtension.logger.info(msg)
      } else if (AiExtension.logger.isDebugEnabled) {
        AiExtension.logger.debug(msg)
      }
    }
  }
  def logStream(from: String, method: String, url: String, body: Option[JsValue] = None, opts: Map[String, String] = Map.empty)(implicit env: Env): Unit = {
    if (env.isDev || AiExtension.logger.isDebugEnabled) {
      val msg = s"stream provider '${from}' - ${method} - ${url} - ${opts} - ${body.map(_.prettify).getOrElse("")}"
      if (env.isDev) {
        AiExtension.logger.info(msg)
      } else if (AiExtension.logger.isDebugEnabled) {
        AiExtension.logger.debug(msg)
      }
    }
  }
  def wrapResponse[T](from: String, resp: WSResponse, env: Env)(f: WSResponse => T): Either[JsValue, T] = {
    if (env.isDev || AiExtension.logger.isDebugEnabled) {
      val msg = s"provider response '${from}' - ${resp.status} - ${resp.body}"
      if (env.isDev) {
        AiExtension.logger.info(msg)
      } else if (AiExtension.logger.isDebugEnabled) {
        AiExtension.logger.debug(msg)
      }
    }
    if (resp.status == 200) {
      f(resp).right
    } else {
      logBadResponse(from, resp)
      responseBody(resp).left
    }
  }
  def wrapStreamResponse[T](from: String, resp: WSResponse, env: Env)(f: WSResponse => T): Either[JsValue, T] = {
    if (env.isDev || AiExtension.logger.isDebugEnabled) {
      val msg = s"provider stream response '${from}' - ${resp.status} - ${if (resp.status != 200) resp.body else "stream"}"
      if (env.isDev) {
        AiExtension.logger.info(msg)
      } else if (AiExtension.logger.isDebugEnabled) {
        AiExtension.logger.debug(msg)
      }
    }
    if (resp.status == 200) {
      f(resp).right
    } else {
      logBadResponse(from, resp)
      responseBody(resp).left
    }
  }
}
