package com.cloud.apim.otoroshi.extensions.aigateway.assistant

import akka.stream.scaladsl.Source
import akka.util.ByteString
import otoroshi.env.Env
import otoroshi.models.{ApiKey, BackOfficeUser}
import otoroshi.next.extensions._
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json._
import play.api.mvc._

import scala.concurrent.Future

class OtoroshiAssistant(env: Env, ext: AiExtension) {

  def isEnabled: Boolean = ext.states.allProviders().exists(_.isOtoroshiAssistant)

  def handleAssistantCompletion(ctx: AdminExtensionRouterContext[AdminExtensionBackofficeAuthRoute], req: RequestHeader, user: Option[BackOfficeUser], body: Option[Source[ByteString, _]]): Future[Result] = {
    implicit val ec = env.otoroshiExecutionContext
    implicit val mat = env.otoroshiMaterializer
    implicit val ev = env
    if (isEnabled) {
      ???
    } else {
      Results.InternalServerError(Json.obj("error" -> "Access is disabled")).vfuture
    }
  }
}
