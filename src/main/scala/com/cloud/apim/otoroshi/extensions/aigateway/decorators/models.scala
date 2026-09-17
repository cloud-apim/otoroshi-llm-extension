package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.AiMetrics
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiProvider, AudioModel, EmbeddingModel, ImageModel, ModelSettings, ModerationModel, OcrModel, VideoModel}
import com.cloud.apim.otoroshi.extensions.aigateway.{AudioModelClient, AudioModelClientSpeechToTextInputOptions, AudioModelClientTextToSpeechInputOptions, AudioModelClientTranslationInputOptions, AudioTranscriptionResponse, ChatCallKind, ChatClient, ChatPrompt, ChatResponse, ChatResponseChunk, EmbeddingClientInputOptions, EmbeddingModelClient, EmbeddingResponse, ImageModelClient, ImageModelClientEditionInputOptions, ImageModelClientGenerationInputOptions, ImagesGenResponse, ModerationModelClient, ModerationModelClientInputOptions, ModerationResponse, OcrModelClient, OcrModelClientInputOptions, OcrModelClientResponse, VideoModelClient, VideoModelClientTextToVideoInputOptions, VideosGenResponse}
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import play.api.libs.typedmap.TypedKey
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.{JsObject, JsValue, Json}

import scala.concurrent.{ExecutionContext, Future}

// The entity serving a model (provider or model entity) and its own model restrictions. Through the unified
// apis, a model is called `model`, `<entity>/<model>` or `<entity>###<model>`, the entity being its id or its
// slug name: a restriction pattern may target any of these forms. A call without a model is a call to the
// default model of the entity, `<entity>/_default`.
final case class ModelTarget(id: String, slug: String, settings: ModelSettings) {
  def names(model: Option[String]): Seq[String] = {
    val m = model.getOrElse(ModelTarget.DefaultModel)
    Seq(m, s"$slug/$m", s"$slug###$m", s"$id/$m", s"$id###$m").distinct
  }
}

object ModelTarget {
  val DefaultModel = "_default"
  def of(provider: AiProvider): ModelTarget = ModelTarget(provider.id, provider.slugName, provider.models)
}

// The models a call may use: those of the provider (or model entity), of the calling api key and of the
// calling user (`ai_models_include` / `ai_models_exclude` metadata). Every level must allow the model: a
// level that restricts nothing lifts nothing on the others.
//
// Routing providers (load balancers, otoroshi routers, fallbacks) hand calls over to other providers. The
// consumers chose the routing provider and the model they asked it: that choice is what their restrictions
// are checked against. Where the call then goes is the choice of the operator, so the providers it is
// handed to only apply their own restrictions, for the model they are handed.
object ModelConstraints {

  val denied: JsValue = Json.obj("error" -> "you can't use this model")

  def isDenied(error: JsValue): Boolean = error == denied

  private val DelegatedKey = TypedKey[java.util.Set[String]]("cloud-apim.ai.model-constraints.delegated")

  private def delegationOf(target: ModelTarget, model: Option[String]): String = s"${target.id}\u0000${model.getOrElse("")}"

  private def delegated(attrs: TypedMap, target: ModelTarget, model: Option[String]): Boolean =
    attrs.get(DelegatedKey).exists(_.contains(delegationOf(target, model)))

  private def consumers(attrs: TypedMap): Seq[ModelSettings] =
    ModelSettings.fromEntity(attrs.get(otoroshi.plugins.Keys.ApiKeyKey)).toSeq ++
      ModelSettings.fromEntity(attrs.get(otoroshi.plugins.Keys.UserKey)).toSeq

  private def consumersAllow(target: ModelTarget, model: Option[String], attrs: TypedMap): Boolean = {
    val names = target.names(model)
    delegated(attrs, target, model) || consumers(attrs).forall(_.matchesAny(names))
  }

  // the model a chat call is about: the one of the body, or the one the client would use
  def requestedModel(client: ChatClient, body: JsValue): Option[String] =
    body.select("model").asOptString.orElse(client.computeModel(body))

  def allows(target: ModelTarget, model: Option[String], attrs: TypedMap): Boolean =
    target.settings.matchesAny(target.names(model)) && consumersAllow(target, model, attrs)

  // `from` hands the call it received for `fromModel` over to `to`, with `toBody`. Only a call its consumers
  // were allowed to make is handed over: otherwise `to` checks them as for any call
  def delegate(attrs: TypedMap, from: ModelTarget, fromModel: Option[String], to: AiProvider, toClient: ChatClient, toBody: JsValue): Unit = {
    if (consumersAllow(from, fromModel, attrs)) {
      attrs.putIfAbsent(DelegatedKey -> java.util.concurrent.ConcurrentHashMap.newKeySet[String]())
      attrs.get(DelegatedKey).foreach(_.add(delegationOf(ModelTarget.of(to), requestedModel(toClient, toBody))))
    }
  }

  def check[A](target: ModelTarget, model: Option[String], attrs: TypedMap)(call: => Future[Either[JsValue, A]])(using env: Env): Future[Either[JsValue, A]] = {
    if (allows(target, model, attrs)) {
      call
    } else {
      AiMetrics.markModelConstraintDenied()
      denied.leftf[A]
    }
  }

  def filter(target: ModelTarget, models: List[String], attrs: TypedMap): List[String] = {
    val levels = target.settings +: consumers(attrs)
    models.filter { m =>
      val names = target.names(Some(m))
      levels.forall(_.matchesAny(names))
    }
  }
}

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

  private val target = ModelTarget.of(originalProvider)

  private def modelOf(originalBody: JsValue): Option[String] = ModelConstraints.requestedModel(chatClient, originalBody)

  override def invoke(kind: ChatCallKind, prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    ModelConstraints.check(target, modelOf(originalBody), attrs) {
      chatClient.invoke(kind, prompt, attrs, originalBody)
    }
  }

  override def invokeStream(kind: ChatCallKind, prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, ?]]] = {
    ModelConstraints.check(target, modelOf(originalBody), attrs) {
      chatClient.invokeStream(kind, prompt, attrs, originalBody)
    }
  }

  override def listModels(raw: Boolean, attrs: TypedMap)(using ec: ExecutionContext): Future[Either[JsValue, List[String]]] = {
    chatClient.listModels(raw, attrs).map(_.map(models => ModelConstraints.filter(target, models, attrs)))
  }
}

object EmbeddingModelClientWithModels {
  def applyIfPossible(tuple: (EmbeddingModel, EmbeddingModelClient, Env)): EmbeddingModelClient = {
    new EmbeddingModelClientWithModels(tuple._1, tuple._2)
  }
}

class EmbeddingModelClientWithModels(originalModel: EmbeddingModel, val embeddingModelClient: EmbeddingModelClient) extends DecoratorEmbeddingModelClient {
  override def embed(opts: EmbeddingClientInputOptions, rawBody: JsObject, attrs: TypedMap)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, EmbeddingResponse]] = {
    ModelConstraints.check(ModelTarget(originalModel.id, originalModel.slugName, originalModel.models), opts.model, attrs) {
      embeddingModelClient.embed(opts, rawBody, attrs)
    }
  }
}

object AudioModelClientWithModels {
  def applyIfPossible(tuple: (AudioModel, AudioModelClient, Env)): AudioModelClient = {
    new AudioModelClientWithModels(tuple._1, tuple._2)
  }
}

class AudioModelClientWithModels(originalModel: AudioModel, val audioModelClient: AudioModelClient) extends DecoratorAudioModelClient {

  override def speechToText(opts: AudioModelClientSpeechToTextInputOptions, rawBody: JsObject, attrs: TypedMap)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, AudioTranscriptionResponse]] = {
    ModelConstraints.check(ModelTarget(originalModel.id, originalModel.slugName, originalModel.models), opts.model, attrs) {
      audioModelClient.speechToText(opts, rawBody, attrs)
    }
  }

  override def textToSpeech(opts: AudioModelClientTextToSpeechInputOptions, rawBody: JsObject, attrs: TypedMap)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, (Source[ByteString, ?], String)]] = {
    ModelConstraints.check(ModelTarget(originalModel.id, originalModel.slugName, originalModel.models), opts.model, attrs) {
      audioModelClient.textToSpeech(opts, rawBody, attrs)
    }
  }

  override def translate(opts: AudioModelClientTranslationInputOptions, rawBody: JsObject, attrs: TypedMap)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, AudioTranscriptionResponse]] = {
    ModelConstraints.check(ModelTarget(originalModel.id, originalModel.slugName, originalModel.models), opts.model, attrs) {
      audioModelClient.translate(opts, rawBody, attrs)
    }
  }
}

object ImageModelClientWithModels {
  def applyIfPossible(tuple: (ImageModel, ImageModelClient, Env)): ImageModelClient = {
    new ImageModelClientWithModels(tuple._1, tuple._2)
  }
}

class ImageModelClientWithModels(originalModel: ImageModel, val imageModelClient: ImageModelClient) extends DecoratorImageModelClient {

  override def edit(opts: ImageModelClientEditionInputOptions, rawBody: JsObject, attrs: TypedMap)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, ImagesGenResponse]] = {
    ModelConstraints.check(ModelTarget(originalModel.id, originalModel.slugName, originalModel.models), opts.model, attrs) {
      imageModelClient.edit(opts, rawBody, attrs)
    }
  }

  override def generate(opts: ImageModelClientGenerationInputOptions, rawBody: JsObject, attrs: TypedMap)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, ImagesGenResponse]] = {
    ModelConstraints.check(ModelTarget(originalModel.id, originalModel.slugName, originalModel.models), opts.model, attrs) {
      imageModelClient.generate(opts, rawBody, attrs)
    }
  }
}

object ModerationModelClientWithModels {
  def applyIfPossible(tuple: (ModerationModel, ModerationModelClient, Env)): ModerationModelClient = {
    new ModerationModelClientWithModels(tuple._1, tuple._2)
  }
}

class ModerationModelClientWithModels(originalModel: ModerationModel, val moderationModelClient: ModerationModelClient) extends DecoratorModerationModelClient {
  override def moderate(opts: ModerationModelClientInputOptions, rawBody: JsObject, attrs: TypedMap)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, ModerationResponse]] = {
    ModelConstraints.check(ModelTarget(originalModel.id, originalModel.slugName, originalModel.models), opts.model, attrs) {
      moderationModelClient.moderate(opts, rawBody, attrs)
    }
  }
}

object VideoModelClientWithModels {
  def applyIfPossible(tuple: (VideoModel, VideoModelClient, Env)): VideoModelClient = {
    new VideoModelClientWithModels(tuple._1, tuple._2)
  }
}

class VideoModelClientWithModels(originalModel: VideoModel, val videoModelClient: VideoModelClient) extends DecoratorVideoModelClient {
  override def generate(opts: VideoModelClientTextToVideoInputOptions, rawBody: JsObject, attrs: TypedMap)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, VideosGenResponse]] = {
    ModelConstraints.check(ModelTarget(originalModel.id, originalModel.slugName, originalModel.models), opts.model, attrs) {
      videoModelClient.generate(opts, rawBody, attrs)
    }
  }
}

object OcrModelClientWithModels {
  def applyIfPossible(tuple: (OcrModel, OcrModelClient, Env)): OcrModelClient = {
    new OcrModelClientWithModels(tuple._1, tuple._2)
  }
}

class OcrModelClientWithModels(originalModel: OcrModel, val ocrModelClient: OcrModelClient) extends DecoratorOcrModelClient {
  override def ocr(opts: OcrModelClientInputOptions, rawBody: JsObject, attrs: TypedMap)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, OcrModelClientResponse]] = {
    ModelConstraints.check(ModelTarget(originalModel.id, originalModel.slugName, originalModel.models), opts.model, attrs) {
      ocrModelClient.ocr(opts, rawBody, attrs)
    }
  }
}
