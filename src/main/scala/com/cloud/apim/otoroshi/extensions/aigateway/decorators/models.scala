package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import akka.stream.scaladsl.Source
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiProvider, ModelSettings}
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatClient, ChatPrompt, ChatResponse, ChatResponseChunk}
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsValue, Json}

import scala.concurrent.{ExecutionContext, Future}

object ChatClientWithModelConstraints {
  def applyIfPossible(tuple: (AiProvider, ChatClient, Env)): ChatClient = {
    //if (tuple._1.models.isDefined) {
      new ChatClientWithModelConstraints(tuple._1, tuple._2)
    //} else {
    //  tuple._2
    //}
  }
}

class ChatClientWithModelConstraints(originalProvider: AiProvider, val chatClient: ChatClient) extends DecoratorChatClient {

  override def call(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val apikeyModels = ModelSettings.fromEntity(attrs.get(otoroshi.plugins.Keys.ApiKeyKey))
    val userModels = ModelSettings.fromEntity(attrs.get(otoroshi.plugins.Keys.UserKey))
    originalBody.select("model").asOptString.orElse(chatClient.computeModel(originalBody)) match {
      case Some(model) if originalProvider.models.matches(model) && apikeyModels.forall(_.matches(model)) && userModels.forall(_.matches(model)) => chatClient.call(prompt, attrs, originalBody)
      case _ => Json.obj("error" -> "you can't use this model").leftf
    }
  }

  override def stream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    val apikeyModels = ModelSettings.fromEntity(attrs.get(otoroshi.plugins.Keys.ApiKeyKey))
    val userModels = ModelSettings.fromEntity(attrs.get(otoroshi.plugins.Keys.UserKey))
    originalBody.select("model").asOptString.orElse(chatClient.computeModel(originalBody)) match {
      case Some(model) if originalProvider.models.matches(model) && apikeyModels.forall(_.matches(model)) && userModels.forall(_.matches(model)) => chatClient.stream(prompt, attrs, originalBody)
      case _ => Json.obj("error" -> "you can't use this model").leftf
    }
  }

  override def completion(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val apikeyModels = ModelSettings.fromEntity(attrs.get(otoroshi.plugins.Keys.ApiKeyKey))
    val userModels = ModelSettings.fromEntity(attrs.get(otoroshi.plugins.Keys.UserKey))
    originalBody.select("model").asOptString.orElse(chatClient.computeModel(originalBody)) match {
      case Some(model) if originalProvider.models.matches(model) && apikeyModels.forall(_.matches(model)) && userModels.forall(_.matches(model)) => chatClient.completion(prompt, attrs, originalBody)
      case _ => Json.obj("error" -> "you can't use this model").leftf
    }
  }

  override def completionStream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    val apikeyModels = ModelSettings.fromEntity(attrs.get(otoroshi.plugins.Keys.ApiKeyKey))
    val userModels = ModelSettings.fromEntity(attrs.get(otoroshi.plugins.Keys.UserKey))
    originalBody.select("model").asOptString.orElse(chatClient.computeModel(originalBody)) match {
      case Some(model) if originalProvider.models.matches(model) && apikeyModels.forall(_.matches(model)) && userModels.forall(_.matches(model)) => chatClient.completionStream(prompt, attrs, originalBody)
      case _ => Json.obj("error" -> "you can't use this model").leftf
    }
  }

  override def listModels(raw: Boolean, attrs: TypedMap)(implicit ec: ExecutionContext): Future[Either[JsValue, List[String]]] = {
    val apikeyModels = ModelSettings.fromEntity(attrs.get(otoroshi.plugins.Keys.ApiKeyKey))
    val userModels = ModelSettings.fromEntity(attrs.get(otoroshi.plugins.Keys.UserKey))
    chatClient.listModels(raw, attrs).map {
      case Left(err) => Left(err)
      case Right(models) => Right(
        models
          .filter(m => originalProvider.models.matches(m))
          .applyOnWithOpt(apikeyModels) {
            case (models, mm) => models.filter(m => mm.matches(m))
          }
          .applyOnWithOpt(userModels) {
            case (models, mm) => models.filter(m => mm.matches(m))
          }
      )
    }
  }
}