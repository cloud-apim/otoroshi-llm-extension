package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiProvider, AudioModel, EmbeddingModel, ImageModel, ModelSettings, ModerationModel, VideoModel}
import com.cloud.apim.otoroshi.extensions.aigateway.{AudioGenModel, AudioGenVoice, AudioModelClient, AudioModelClientSpeechToTextInputOptions, AudioModelClientTextToSpeechInputOptions, AudioModelClientTranslationInputOptions, AudioTranscriptionResponse, ChatClient, ChatPrompt, ChatResponse, ChatResponseChunk, EmbeddingClientInputOptions, EmbeddingModelClient, EmbeddingResponse, ImageModelClient, ImageModelClientEditionInputOptions, ImageModelClientGenerationInputOptions, ImagesGenResponse, ModerationModelClient, ModerationModelClientInputOptions, ModerationResponse, VideoModelClient, VideoModelClientTextToVideoInputOptions, VideosGenResponse}
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsObject, JsValue, Json}

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

object EmbeddingModelClientWithModels {
  def applyIfPossible(tuple: (EmbeddingModel, EmbeddingModelClient, Env)): EmbeddingModelClient = {
    new EmbeddingModelClientWithModels(tuple._1, tuple._2)
  }
}

class EmbeddingModelClientWithModels(originalModel: EmbeddingModel, val embeddingModelClient: EmbeddingModelClient) extends DecoratorEmbeddingModelClient {
  override def embed(opts: EmbeddingClientInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, EmbeddingResponse]] = {
    val apikeyModels = ModelSettings.fromEntity(attrs.get(otoroshi.plugins.Keys.ApiKeyKey))
    val userModels = ModelSettings.fromEntity(attrs.get(otoroshi.plugins.Keys.UserKey))
    opts.model match {
      case Some(model) if originalModel.models.matches(model) && apikeyModels.forall(_.matches(model)) && userModels.forall(_.matches(model)) => embeddingModelClient.embed(opts, rawBody, attrs)
      case _ => Json.obj("error" -> "you can't use this model").leftf
    }
  }
}

object AudioModelClientWithModels {
  def applyIfPossible(tuple: (AudioModel, AudioModelClient, Env)): AudioModelClient = {
    new AudioModelClientWithModels(tuple._1, tuple._2)
  }
}

class AudioModelClientWithModels(originalModel: AudioModel, val audioModelClient: AudioModelClient) extends DecoratorAudioModelClient {

  override def speechToText(opts: AudioModelClientSpeechToTextInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, AudioTranscriptionResponse]] = {
    val apikeyModels = ModelSettings.fromEntity(attrs.get(otoroshi.plugins.Keys.ApiKeyKey))
    val userModels = ModelSettings.fromEntity(attrs.get(otoroshi.plugins.Keys.UserKey))
    opts.model match {
      case Some(model) if originalModel.models.matches(model) && apikeyModels.forall(_.matches(model)) && userModels.forall(_.matches(model)) => audioModelClient.speechToText(opts, rawBody, attrs)
      case _ => Json.obj("error" -> "you can't use this model").leftf
    }
  }

  override def textToSpeech(opts: AudioModelClientTextToSpeechInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, (Source[ByteString, _], String)]] = {
    val apikeyModels = ModelSettings.fromEntity(attrs.get(otoroshi.plugins.Keys.ApiKeyKey))
    val userModels = ModelSettings.fromEntity(attrs.get(otoroshi.plugins.Keys.UserKey))
    opts.model match {
      case Some(model) if originalModel.models.matches(model) && apikeyModels.forall(_.matches(model)) && userModels.forall(_.matches(model)) => audioModelClient.textToSpeech(opts, rawBody, attrs)
      case _ => Json.obj("error" -> "you can't use this model").leftf
    }
  }

  override def translate(opts: AudioModelClientTranslationInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, AudioTranscriptionResponse]] = {
    val apikeyModels = ModelSettings.fromEntity(attrs.get(otoroshi.plugins.Keys.ApiKeyKey))
    val userModels = ModelSettings.fromEntity(attrs.get(otoroshi.plugins.Keys.UserKey))
    opts.model match {
      case Some(model) if originalModel.models.matches(model) && apikeyModels.forall(_.matches(model)) && userModels.forall(_.matches(model)) => audioModelClient.translate(opts, rawBody, attrs)
      case _ => Json.obj("error" -> "you can't use this model").leftf
    }
  }
}

object ImageModelClientWithModels {
  def applyIfPossible(tuple: (ImageModel, ImageModelClient, Env)): ImageModelClient = {
    new ImageModelClientWithModels(tuple._1, tuple._2)
  }
}

class ImageModelClientWithModels(originalModel: ImageModel, val imageModelClient: ImageModelClient) extends DecoratorImageModelClient {

  override def edit(opts: ImageModelClientEditionInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ImagesGenResponse]] = {
    val apikeyModels = ModelSettings.fromEntity(attrs.get(otoroshi.plugins.Keys.ApiKeyKey))
    val userModels = ModelSettings.fromEntity(attrs.get(otoroshi.plugins.Keys.UserKey))
    opts.model match {
      case Some(model) if originalModel.models.matches(model) && apikeyModels.forall(_.matches(model)) && userModels.forall(_.matches(model)) => imageModelClient.edit(opts, rawBody, attrs)
      case _ => Json.obj("error" -> "you can't use this model").leftf
    }
  }

  override def generate(opts: ImageModelClientGenerationInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ImagesGenResponse]] = {
    val apikeyModels = ModelSettings.fromEntity(attrs.get(otoroshi.plugins.Keys.ApiKeyKey))
    val userModels = ModelSettings.fromEntity(attrs.get(otoroshi.plugins.Keys.UserKey))
    opts.model match {
      case Some(model) if originalModel.models.matches(model) && apikeyModels.forall(_.matches(model)) && userModels.forall(_.matches(model)) => imageModelClient.generate(opts, rawBody, attrs)
      case _ => Json.obj("error" -> "you can't use this model").leftf
    }
  }
}

object ModerationModelClientWithModels {
  def applyIfPossible(tuple: (ModerationModel, ModerationModelClient, Env)): ModerationModelClient = {
    new ModerationModelClientWithModels(tuple._1, tuple._2)
  }
}

class ModerationModelClientWithModels(originalModel: ModerationModel, val moderationModelClient: ModerationModelClient) extends DecoratorModerationModelClient {
  override def moderate(opts: ModerationModelClientInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ModerationResponse]] = {
   val apikeyModels = ModelSettings.fromEntity(attrs.get(otoroshi.plugins.Keys.ApiKeyKey))
    val userModels = ModelSettings.fromEntity(attrs.get(otoroshi.plugins.Keys.UserKey))
    opts.model match {
      case Some(model) if originalModel.models.matches(model) && apikeyModels.forall(_.matches(model)) && userModels.forall(_.matches(model)) => moderationModelClient.moderate(opts, rawBody, attrs)
      case _ => Json.obj("error" -> "you can't use this model").leftf
    }
  }
}

object VideoModelClientWithModels {
  def applyIfPossible(tuple: (VideoModel, VideoModelClient, Env)): VideoModelClient = {
    new VideoModelClientWithModels(tuple._1, tuple._2)
  }
}

class VideoModelClientWithModels(originalModel: VideoModel, val videoModelClient: VideoModelClient) extends DecoratorVideoModelClient {
  override def generate(opts: VideoModelClientTextToVideoInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, VideosGenResponse]] = {
    val apikeyModels = ModelSettings.fromEntity(attrs.get(otoroshi.plugins.Keys.ApiKeyKey))
    val userModels = ModelSettings.fromEntity(attrs.get(otoroshi.plugins.Keys.UserKey))
    opts.model match {
      case Some(model) if originalModel.models.matches(model) && apikeyModels.forall(_.matches(model)) && userModels.forall(_.matches(model)) => videoModelClient.generate(opts, rawBody, attrs)
      case _ => Json.obj("error" -> "you can't use this model").leftf
    }
  }
}