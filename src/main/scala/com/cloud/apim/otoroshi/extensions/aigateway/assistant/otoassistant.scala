package com.cloud.apim.otoroshi.extensions.aigateway.assistant

import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatPrompt, InputChatMessage}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import otoroshi.env.Env
import otoroshi.models.{ApiKey, BackOfficeUser}
import otoroshi.next.extensions._
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json._
import play.api.mvc._

import scala.concurrent.Future

class OtoroshiAssistant(env: Env, ext: AiExtension) {

  def assistantProvider: Option[AiProvider] = ext.states.allProviders().find(_.isOtoroshiAssistant)

  def isEnabled: Boolean = assistantProvider.isDefined

  def handleAssistantCompletion(ctx: AdminExtensionRouterContext[AdminExtensionBackofficeAuthRoute], req: RequestHeader, user: Option[BackOfficeUser], body: Option[Source[ByteString, _]]): Future[Result] = {
    implicit val ec = env.otoroshiExecutionContext
    implicit val mat = env.otoroshiMaterializer
    implicit val ev = env
    if (isEnabled) {
      body match {
        case None => Results.BadRequest(Json.obj("error" -> "No body")).vfuture
        case Some(body) => body.runFold(ByteString.empty)(_ ++ _).map(_.utf8String).flatMap { bodyRaw =>
          val provider = assistantProvider.get
          provider.getChatClient() match {
            case None => Results.InternalServerError(Json.obj("error" -> "Unable to create llm client")).vfuture
            case Some(client) => {
              val bodyJson = bodyRaw.parseJson
              val messages = bodyJson.select("messages").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map { obj => InputChatMessage.fromJson(obj) }
              client.call(ChatPrompt(messages), TypedMap.empty, bodyJson).map {
                case Left(err) => Results.InternalServerError(Json.obj("error" -> err))
                case Right(resp) => Results.Ok(resp.openaiJson("model", env))
              }
            }
          }
        }
      }
    } else {
      Results.InternalServerError(Json.obj("error" -> "Access is disabled")).vfuture
    }
  }
}
