package com.cloud.apim.otoroshi.extensions.aigateway

import akka.stream.scaladsl.{FileIO, Sink}
import otoroshi.env.Env
import otoroshi.next.workflow._
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json._

import java.io.File
import java.nio.file.Files
import scala.concurrent.{ExecutionContext, Future}

object WorkflowFunctionsInitializer {
  def initDefaults(): Unit = {
    WorkflowFunction.registerFunction("extensions.com.cloud-apim.llm-extension.llm_call", new LlmCallFunction())
    WorkflowFunction.registerFunction("extensions.com.cloud-apim.llm-extension.audio_tts", new AudioTtsFunction())
    // access otoroshi resources (apikeys, etc)
  }
}

class LlmCallFunction extends WorkflowFunction {
  override def call(args: JsObject)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val provider  = args.select("provider").asString
    val payload = args.select("payload").asOpt[JsObject].getOrElse(Json.obj())
    val messages = payload.select("messages").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map(obj => InputChatMessage.fromJson(obj))
    val extension = env.adminExtensions.extension[AiExtension].get
    extension.states.provider(provider) match {
      case None => WorkflowError(s"llm provider not found", Some(Json.obj("provider_id" -> provider)), None).leftf
      case Some(provider) => provider.getChatClient() match {
        case None => WorkflowError(s"unable to instanciate client for llm provider", Some(Json.obj("provider_id" -> provider.id)), None).leftf
        case Some(client) => client.call(ChatPrompt(messages, None), TypedMap.empty, payload).map {
          case Left(error) => WorkflowError(s"error while calling llm", Some(error.asOpt[JsObject].getOrElse(Json.obj("error" -> error))), None).left
          case Right(response) => response.json(env).right
        }
      }
    }
  }
}

class AudioTtsFunction extends WorkflowFunction {
  override def call(args: JsObject)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val provider  = args.select("provider").asString
    val payload = args.select("payload").asOpt[JsObject].getOrElse(Json.obj())
    val fileDest = new File(args.select("file_out").asOpt[String].getOrElse(Files.createTempFile("audio-out-", ".mp3").toFile.getAbsolutePath))
    val extension = env.adminExtensions.extension[AiExtension].get
    extension.states.audioModel(provider) match {
      case None => WorkflowError(s"audio provider not found", Some(Json.obj("provider_id" -> provider)), None).leftf
      case Some(provider) => provider.getAudioModelClient() match {
        case None => WorkflowError(s"unable to instanciate client for audio provider", Some(Json.obj("provider_id" -> provider.id)), None).leftf
        case Some(client) => client.textToSpeech(AudioModelClientTextToSpeechInputOptions.format.reads(payload).get, payload).flatMap {
          case Left(error) => WorkflowError(s"error while calling audio model", Some(error.asOpt[JsObject].getOrElse(Json.obj("error" -> error))), None).leftf
          case Right(response) =>
            response._1.runWith(FileIO.toPath(fileDest.toPath))(env.otoroshiMaterializer).map { res =>
              Json.obj("done" -> true, "file_out" -> fileDest.getAbsolutePath).right
            }
        }
      }
    }
  }
}