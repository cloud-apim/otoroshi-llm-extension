package com.cloud.apim.otoroshi.extensions.aigateway.providers

import play.api.libs.json.JsValue

import scala.concurrent.{ExecutionContext, Future}

trait ApiClient[Resp] {
  def supportsTools: Boolean
  def call(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[Resp]
  def callWithToolSupport(method: String, path: String, body: Option[JsValue])(implicit ec: ExecutionContext): Future[Resp]
}
