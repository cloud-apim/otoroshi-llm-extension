package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import com.cloud.apim.otoroshi.extensions.aigateway.catalog.{ModelEndpoints, ModelsListing, ModelsMetadata}
import com.cloud.apim.otoroshi.extensions.aigateway.decorators.{ModelConstraints, ModelTarget, RequiredCosts}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiProvider, AiProvidersCatalog, ModelSettings}
import com.cloud.apim.otoroshi.extensions.aigateway.plugins.*
import com.cloud.apim.otoroshi.extensions.aigateway.providers.SystemOneProviders
import otoroshi.env.Env
import otoroshi.next.plugins.api.*
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.{JsArray, JsLookupResult, JsObject, Json}
import play.api.mvc.Results

import java.util.Locale
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.util.*

class OpenAiCompatModels extends NgBackendCall {

  override def name: String = "Cloud APIM - LLM OpenAI Compat. Models list"
  override def description: Option[String] = "Delegates call to a LLM provider to retrieve supported models".some
  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))
  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)
  override def useDelegates: Boolean = false
  override def defaultConfigObject: Option[NgPluginConfig] = Some(AiPluginRefsConfig.default)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = AiPluginRefsConfig.configFlow
  override def configSchema: Option[JsObject] = AiPluginRefsConfig.configSchema("LLM provider", "providers")

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'LLM OpenAI Compat. Models list' plugin is available !")
    }
    ().vfuture
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(AiPluginRefsConfig.format).getOrElse(AiPluginRefsConfig.default)
    val ext = env.adminExtensions.extension[AiExtension].get
    val listing = ModelsListing(ctx)
    val now: Long = System.currentTimeMillis() / 1000
    Source.future(listing.prepare()).flatMapConcat(_ => Source(config.refs.toList))
      .map(ref => ext.states.provider(ref))
      .collect {
        case Some(provider) => provider
      }
      .map(p => (p, p.getChatClient()))
      .collect {
        case (provider, Some(client)) => (provider, client)
      }
      .mapAsync(1) {
        case (provider, client) => client.listModels(ctx.request.queryParam("raw").contains("true"), ctx.attrs).map(e => (provider, e))
      }
      .collect {
        case (provider, Right(list)) => list.flatMap { model =>
          val res = if (config.refs.size == 1) {
            model
          } else {
            if (model.contains("/")) s"${provider.slugName}###${model}" else s"${provider.slugName}/${model}"
          }
          listing.entry(provider, model, Json.obj(
            "id" -> res,
            "object" -> "model",
            "created" -> now,
            "owned_by" -> provider.slugName,
          ))
        }
      }
      .flatMapConcat(list => Source(list))
      .runWith(Sink.seq)
      .map { list =>
        Right(BackendCallResponse(NgPluginHttpResponse.fromResult(
          Results.Ok(Json.obj(
            "object" -> "list",
            "data" -> JsArray(list)
          ))
        ), None))
      }
  }
}

object OpenAiCompatProvidersWithModels {
  /** `others` are the refs of the other model types of the unified api, whose models are listed too */
  def handleRequest(config: AiPluginRefsConfig, ctx: NgbBackendCallContext, others: Option[OpenAiCompatApiConfig] = None)(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    val now: Long = System.currentTimeMillis() / 1000
    val listing = ModelsListing(ctx)
    Source.future(listing.prepare()).flatMapConcat(_ => Source(config.refs.toList))
      .map(ref => ext.states.provider(ref))
      .collect {
        case Some(provider) => provider
      }
      .map(p => (p, p.getChatClient()))
      .collect {
        case (provider, Some(client)) => (provider, client)
      }
      .mapAsync(1) {
        case (provider, client) => client.listModels(ctx.request.queryParam("raw").contains("true"), ctx.attrs).map(e => (provider, e))
      }
      .collect {
        case (provider, Right(list)) => {
          list.flatMap { model =>
            val combined = if (config.refs.size == 1) {
              model
            } else {
              if (model.contains("/")) s"${provider.slugName}###${model}" else s"${provider.slugName}/${model}"
            }
            listing.entry(provider, model, Json.obj(
              "id" -> combined,
              "combined_id" -> combined,
              "provider_id" -> provider.slugName,
              "simple_id" -> model,
              "object" -> "model",
              "created" -> now,
              "owned_by" -> provider.computedName,
              "owned_by_with_model" -> s"${provider.computedName} / ${model}"
            ))
          }
        }
      }
      .flatMapConcat(list => Source(list))
      .runWith(Sink.seq)
      .flatMap { text =>
        others match {
          case None => Future.successful(text)
          case Some(all) => OpenAiCompatModalityModels.entries(all, listing, ctx.attrs, now, text).map(more => OpenAiCompatModalityModels.merged(text ++ more))
        }
      }
      .map { list =>
        Right(BackendCallResponse(NgPluginHttpResponse.fromResult(
          Results.Ok(Json.obj(
            "object" -> "list",
            "data" -> JsArray(list)
          ))
        ), None))
      }
  }
}

/**
 * The models of the other entities of the unified api (embeddings, images, audio, moderation, ocr, decisions),
 * listed next to the ones of its text providers.
 *
 * A text provider lists every model of its connection: an entity on that very connection, under the same name, is
 * not listed again, the ids of the provider route to it. It only adds the models it is set up with that the provider
 * does not list (OpenRouter lists no decision model on its `/models`). The other entities list their connection with
 * a text client of their kind, once per connection, and keep the models of their kind and the ones they are set up
 * with, within their model rules. A kind no text client lists gives its configured models.
 */
object OpenAiCompatModalityModels {

  final case class Listed(modality: String, id: String, name: String, slug: String, kind: String, metadata: Map[String, String], config: JsObject, settings: ModelSettings) {
    lazy val connection: JsObject = config.select("connection").asOpt[JsObject].getOrElse(Json.obj())
    lazy val connectionKey: (String, String, String) = OpenAiCompatModalityModels.connectionKey(kind, connection)
    def computedName: String = metadata.get("endpoint_name").orElse(metadata.get("provider_name")).getOrElse(name)
    def target: ModelTarget = ModelTarget(id, slug, settings)
    // decisions made by a text provider: they are asked of the model the entity names, not of its whole listing
    def emulated: Boolean = kind.toLowerCase(Locale.ROOT) == SystemOneProviders.LlmEmulation
    def listable: Boolean = !emulated && AiProvider.supportedProviders.contains(kind.toLowerCase(Locale.ROOT))
  }

  // what tells two connections apart: the kind of provider, where it is reached and with which key
  def connectionKey(kind: String, connection: JsObject): (String, String, String) = (
    kind.toLowerCase(Locale.ROOT),
    connection.select("base_url").orElse(connection.select("base_domain")).asOptString.map(_.trim.stripSuffix("/")).getOrElse(""),
    connection.select("token").asOptString.getOrElse(""),
  )

  def entitiesOf(config: OpenAiCompatApiConfig)(using env: Env): Seq[Listed] = {
    val states = env.adminExtensions.extension[AiExtension].get.states
    import AiProvidersCatalog.*
    config.embeddingModelRefs.flatMap(states.embeddingModel).map(e => Listed(Embedding, e.id, e.name, e.slugName, e.provider, e.metadata, e.config, e.models)) ++
      config.imageModelRefs.flatMap(states.imageModel).map(e => Listed(Image, e.id, e.name, e.slugName, e.provider, e.metadata, e.config, e.models)) ++
      config.audioModelRefs.flatMap(states.audioModel).map(e => Listed(Audio, e.id, e.name, e.slugName, e.provider, e.metadata, e.config, e.models)) ++
      config.moderationModelRefs.flatMap(states.moderationModel).map(e => Listed(Moderation, e.id, e.name, e.slugName, e.provider, e.metadata, e.config, e.models)) ++
      config.ocrModelRefs.flatMap(states.ocrModel).map(e => Listed(Ocr, e.id, e.name, e.slugName, e.provider, e.metadata, e.config, e.models)) ++
      config.decisionModelRefs.flatMap(states.decisionModel).map(e => Listed(Decision, e.id, e.name, e.slugName, e.provider, e.metadata, e.config, e.models))
  }

  private def refsCount(config: OpenAiCompatApiConfig, modality: String): Int = modality match {
    case AiProvidersCatalog.Embedding => config.embeddingModelRefs.size
    case AiProvidersCatalog.Image => config.imageModelRefs.size
    case AiProvidersCatalog.Audio => config.audioModelRefs.size
    case AiProvidersCatalog.Moderation => config.moderationModelRefs.size
    case AiProvidersCatalog.Ocr => config.ocrModelRefs.size
    case _ => config.decisionModelRefs.size
  }

  /** the models an entity is set up with: an audio entity has one per section, an image one may edit with another */
  def configured(modality: String, config: JsObject): Seq[String] = {
    val options = config.select("options")
    def modelIn(values: JsLookupResult*): Option[String] = values
      .filterNot(_.select("enabled").asOpt[Boolean].contains(false))
      .flatMap(v => v.select("model").asOptString.orElse(v.select("model_id").asOptString))
      .map(_.trim).find(_.nonEmpty)
    (modality match {
      case AiProvidersCatalog.Image => Seq(modelIn(options.select("generation"), options), modelIn(options.select("edition")))
      case AiProvidersCatalog.Audio => Seq(
        modelIn(config.select("tts"), options.select("tts")),
        modelIn(config.select("stt"), options.select("stt")),
        modelIn(config.select("translate"), options.select("translation")),
      )
      case _ => Seq(modelIn(options))
    }).flatten.distinct
  }

  // an entity covered by a text provider: same connection, same name, so the ids the provider lists route to it
  private def covered(entity: Listed, texts: Seq[AiProvider]): Boolean = texts.exists { p =>
    p.slugName == entity.slug && connectionKey(p.provider, p.connection) == entity.connectionKey
  }

  // enough of a provider to list and describe the models of an entity
  private def providerOf(entity: Listed, kind: String): AiProvider =
    AiProvider(id = entity.id, name = entity.name, metadata = entity.metadata, provider = kind, connection = entity.connection, options = Json.obj(), models = entity.settings)

  // every model of the connection of an entity, as a text client of its kind lists them: nothing when it cannot
  private def listingOf(entity: Listed, attrs: TypedMap)(using env: Env, ec: ExecutionContext): Future[Seq[String]] = {
    Try(providerOf(entity, entity.kind).getRawChatClient()).toOption.flatten match {
      case None => Future.successful(Seq.empty)
      case Some(client) => Try(client.listModels(false, attrs)).getOrElse(Future.successful(Left(Json.obj())))
        .map(_.toOption.getOrElse(List.empty))
        .recover { case _ => List.empty }
    }
  }

  // a decision made by a text provider is described the way that provider serves its model
  private def describedKind(entity: Listed)(using env: Env): String = {
    if (!entity.emulated) entity.kind else entity.connection.select("provider").asOptString
      .flatMap(ref => env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(ref)))
      .map(_.provider).getOrElse(entity.kind)
  }

  /** `text` are the entries of the text providers, which list the models of the entities they cover */
  def entries(config: OpenAiCompatApiConfig, listing: ModelsListing, attrs: TypedMap, now: Long, text: Seq[JsObject])(using env: Env, ec: ExecutionContext): Future[Seq[JsObject]] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    val texts = config.languageModelRefs.flatMap(ref => ext.states.provider(ref))
    val entities = entitiesOf(config)
    val coveredIds = entities.filter(e => covered(e, texts)).map(_.id).toSet
    // what the text providers listed, by their name
    val listedByText = text.map(e => (e.select("provider_id").asOptString.getOrElse(""), e.select("simple_id").asOptString.getOrElse(""))).toSet
    if (entities.isEmpty) Future.successful(Seq.empty) else {
      // one call per connection, whatever the number of its entities
      val listings: Map[(String, String, String), Future[Seq[String]]] = entities.filter(e => e.listable && !coveredIds.contains(e.id)).groupBy(_.connectionKey).map {
        case (key, sharing) => key -> listingOf(sharing.head, attrs)
      }
      ext.modelsCatalog.load().flatMap { _ =>
        Future.sequence(entities.map { entity =>
          val isCovered = coveredIds.contains(entity.id)
          (if (isCovered) Future.successful(Seq.empty[String]) else listings.getOrElse(entity.connectionKey, Future.successful(Seq.empty))).map { listed =>
            val lister = providerOf(entity, entity.kind)
            val ofKind = listed.filter(m => ModelsMetadata.describe(lister, m).kinds.contains(entity.modality))
            val own = configured(entity.modality, entity.config).filterNot(m => isCovered && listedByText.contains((entity.slug, m)))
            val constrained = ModelConstraints.filter(entity.target, (own ++ ofKind).distinct.toList, attrs)
            // a decision made by a text provider is billed by that provider, and checked by it
            val models = if (entity.emulated) constrained else RequiredCosts.filterModels(RequiredCosts.billedAs(entity.kind, entity.metadata, None)._1, entity.kind, entity.settings, constrained)
            val describer = providerOf(entity, describedKind(entity))
            val single = refsCount(config, entity.modality) == 1
            models.flatMap { model =>
              val id = if (single) model else if (model.contains("/")) s"${entity.slug}###${model}" else s"${entity.slug}/${model}"
              listing.entry(describer, model, Json.obj(
                "id" -> id,
                "combined_id" -> id,
                "provider_id" -> entity.slug,
                "simple_id" -> model,
                "object" -> "model",
                "created" -> now,
                "owned_by" -> entity.computedName,
                "owned_by_with_model" -> s"${entity.computedName} / ${model}",
              ), entity.modality).map { entry =>
                // described as its text provider serves the model, an emulated decision is still a System One one first
                if (!entity.emulated) entry else entry.select("_metadata").asOpt[JsObject] match {
                  case Some(metadata) => entry ++ Json.obj("_metadata" -> (metadata ++ Json.obj("endpoints" -> ModelEndpoints.decisions(entity.kind))))
                  case None => entry
                }
              }
            }
          }
        }).map(_.flatten)
      }
    }
  }

  /** one entry per id: a model listed twice under the same id answers on the endpoints of both */
  def merged(entries: Seq[JsObject]): Seq[JsObject] = {
    val byId = mutable.LinkedHashMap.empty[String, JsObject]
    entries.foreach { entry =>
      val id = entry.select("id").asOptString.getOrElse("")
      byId.get(id) match {
        case None => byId.put(id, entry)
        case Some(first) => byId.put(id, mergedEntry(first, entry))
      }
    }
    byId.values.toSeq
  }

  private def mergedEntry(first: JsObject, other: JsObject): JsObject = (first.select("_metadata").asOpt[JsObject], other.select("_metadata").asOpt[JsObject]) match {
    case (Some(a), Some(b)) =>
      def union(field: String): Seq[String] = (a.select(field).asOpt[Seq[String]].getOrElse(Seq.empty) ++ b.select(field).asOpt[Seq[String]].getOrElse(Seq.empty)).distinct
      first ++ Json.obj("_metadata" -> (a ++ Json.obj("kinds" -> union("kinds"), "endpoints" -> union("endpoints"))))
    case _ => first
  }
}

class OpenAiCompatProvidersWithModels extends NgBackendCall {

  override def name: String = "Cloud APIM - LLM OpenAI Compat. Provider with Models list"
  override def description: Option[String] = "Delegates call to LLM providers to retrieve supported models".some
  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))
  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)
  override def useDelegates: Boolean = false
  override def defaultConfigObject: Option[NgPluginConfig] = Some(AiPluginRefsConfig.default)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = AiPluginRefsConfig.configFlow
  override def configSchema: Option[JsObject] = AiPluginRefsConfig.configSchema("LLM provider", "providers")

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'LLM OpenAI Compat. Models list' plugin is available !")
    }
    ().vfuture
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(AiPluginRefsConfig.format).getOrElse(AiPluginRefsConfig.default)
    OpenAiCompatProvidersWithModels.handleRequest(config, ctx)
  }
}

class LlmProviderModels extends NgBackendCall {

  override def name: String = "Cloud APIM - LLM Models list"
  override def description: Option[String] = "Delegates call to a LLM provider to retrieve supported models".some
  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))
  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)
  override def useDelegates: Boolean = false
  override def defaultConfigObject: Option[NgPluginConfig] = Some(AiPluginRefsConfig.default)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = AiPluginRefsConfig.configFlow
  override def configSchema: Option[JsObject] = AiPluginRefsConfig.configSchema("LLM provider", "providers")

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'LLM Models list' plugin is available !")
    }
    ().vfuture
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(AiPluginRefsConfig.format).getOrElse(AiPluginRefsConfig.default)
    val ext = env.adminExtensions.extension[AiExtension].get
    Source(config.refs.toList)
      .map(ref => ext.states.provider(ref))
      .collect {
        case Some(provider) => provider
      }
      .map(p => (p, p.getChatClient()))
      .collect {
        case (provider, Some(client)) => (provider, client)
      }
      .mapAsync(1) {
        case (provider, client) => client.listModels(ctx.request.queryParam("raw").contains("true"), ctx.attrs).map(e => (provider, e))
      }
      .collect {
        case (provider, Right(list)) => list.map { model =>
          if (config.refs.size == 1) {
            model
          } else {
            if (model.contains("/")) s"${provider.slugName}###${model}" else s"${provider.slugName}/${model}"
          }
        }
      }
      .flatMapConcat(list => Source(list))
      .runWith(Sink.seq)
      .map { list =>
        Right(BackendCallResponse(NgPluginHttpResponse.fromResult(
          Results.Ok(Json.obj(
            "models" -> JsArray(list.map(_.json)),
          ))
        ), None))
      }
  }
}

class LlmProvidersWithModels extends NgBackendCall {

  override def name: String = "Cloud APIM - LLM Providers with Models list"
  override def description: Option[String] = "Delegates call to LLM providers to retrieve supported models".some
  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))
  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)
  override def useDelegates: Boolean = false
  override def defaultConfigObject: Option[NgPluginConfig] = Some(AiPluginRefsConfig.default)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = AiPluginRefsConfig.configFlow
  override def configSchema: Option[JsObject] = AiPluginRefsConfig.configSchema("LLM provider", "providers")

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'LLM Provider with Models list' plugin is available !")
    }
    ().vfuture
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(AiPluginRefsConfig.format).getOrElse(AiPluginRefsConfig.default)
    val ext = env.adminExtensions.extension[AiExtension].get
    Source(config.refs.toList)
      .map(ref => ext.states.provider(ref))
      .collect {
        case Some(provider) => provider
      }
      .map(p => (p, p.getChatClient()))
      .collect {
        case (provider, Some(client)) => (provider, client)
      }
      .mapAsync(1) {
        case (provider, client) => client.listModels(ctx.request.queryParam("raw").contains("true"), ctx.attrs).map(e => (provider, e))
      }
      .collect {
        case (provider, Right(list)) => list.map { model =>
          if (config.refs.size == 1) {
            model
          } else {
            if (model.contains("/")) s"${provider.slugName}###${model}" else s"${provider.slugName}/${model}"
          }
        }
      }
      .flatMapConcat(list => Source(list))
      .runWith(Sink.seq)
      .map { list =>
        Right(BackendCallResponse(NgPluginHttpResponse.fromResult(
          Results.Ok(Json.obj(
            "models" -> JsArray(list.map(_.json)),
          ))
        ), None))
      }
  }
}