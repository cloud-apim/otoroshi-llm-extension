package com.cloud.apim.otoroshi.extensions.aigateway.entities

import com.cloud.apim.otoroshi.extensions.aigateway.DecisionModelClient
import com.cloud.apim.otoroshi.extensions.aigateway.decorators.{DecisionModelClientDecorators, ModelTarget}
import com.cloud.apim.otoroshi.extensions.aigateway.providers.*
import otoroshi.api.*
import otoroshi.env.Env
import otoroshi.models.*
import otoroshi.next.extensions.AdminExtensionId
import otoroshi.security.IdGenerator
import otoroshi.storage.*
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.*
import play.api.libs.json.*

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}

case class DecisionModel(
  location: EntityLocation,
  id: String,
  name: String,
  description: String,
  tags: Seq[String],
  metadata: Map[String, String],
  provider: String,
  config: JsObject,
  models: ModelSettings = ModelSettings.empty,
  // the decision model taking over when this one cannot answer, and the model it is asked for (its default
  // one when empty)
  fallbackRef: Option[String] = None,
  fallbackModel: Option[String] = None,
) extends EntityLocationSupport {
  override def internalId: String = id

  override def json: JsValue = DecisionModel.format.writes(this)

  override def theName: String = name

  override def theDescription: String = description

  override def theTags: Seq[String] = tags

  override def theMetadata: Map[String, String] = metadata

  def slugName: String = metadata.get("endpoint_name").orElse(metadata.get("provider_name")).getOrElse(name).slugifyWithSlash.replaceAll("-+", "_")

  def target: ModelTarget = ModelTarget(id, slugName, models)

  def defaultModel: Option[String] = config.at("options.model").asOptString.map(_.trim).filter(_.nonEmpty)

  // decisions made by a text provider, which bills and audits them itself
  def isEmulated: Boolean = provider.toLowerCase() == SystemOneProviders.LlmEmulation

  // whether a request can name another model than the one of the entity (`options.allow_config_override`).
  // A TypeSafe sdk always names one, `jev-latest` by default: turned off, the entity serves its own model
  // whatever the client asks for, which is what lets a model be swapped without touching the clients.
  def allowConfigOverride: Boolean = config.at("options.allow_config_override").asOptBoolean.getOrElse(true)

  /** The same decision model using `model` instead of its default model, when a model is given (set in its options). */
  def withModel(model: Option[String]): DecisionModel = model.map(_.trim).filter(_.nonEmpty) match {
    case None    => this
    case Some(m) => copy(config = config ++ Json.obj("options" -> (config.select("options").asOpt[JsObject].getOrElse(Json.obj()) ++ Json.obj("model" -> m))))
  }

  /**
   * `visited` are the decision models already tried for the call being served: a fallback is never built
   * towards one of them, so two models falling back on each other stop after one try each.
   */
  def getDecisionModelClient(visited: Set[String] = Set.empty)(using env: Env): Option[DecisionModelClient] = {
    val connection = config.select("connection").asOpt[JsObject].getOrElse(Json.obj())
    val options = config.select("options").asOpt[JsObject].getOrElse(Json.obj())
    val baseUrl = connection.select("base_url").orElse(connection.select("base_domain")).asOpt[String].map(_.trim).filter(_.nonEmpty)
    val _token = connection.select("token").asOpt[String].getOrElse("xxx")
    val token = if (_token.contains(",")) {
      val parts = _token.split(",").map(_.trim)
      val index = AiProvider.tokenCounter.incrementAndGet() % (if (parts.nonEmpty) parts.length else 1)
      parts(index)
    } else {
      _token
    }
    val timeout = connection.select("timeout").asOpt[Long].filter(_ > 0L).map(FiniteDuration(_, TimeUnit.MILLISECONDS))
    val rawClient = DecisionModel.clientBuilders
      .get(provider.toLowerCase())
      .flatMap(_.apply(DecisionModel.ClientContext(this, connection, baseUrl, token, timeout, options, id, env)))
    rawClient.map(c => DecisionModelClientDecorators(this, c, env, visited))
  }
}

object DecisionModel {

  final case class ClientContext(self: DecisionModel, connection: JsObject, baseUrl: Option[String], token: String, timeout: Option[FiniteDuration], options: JsObject, id: String, env: Env) {
    def headers: Map[String, String] = connection.select("headers").asOpt[Map[String, String]].getOrElse(Map("Authorization" -> "Bearer {api_key}"))
    def path: String = connection.select("path").asOptString.map(_.trim).filter(_.nonEmpty).getOrElse(SystemOneProviders.defaultPath)
    def api(providerName: String): SystemOneApi =
      new SystemOneApi(token, timeout.getOrElse(SystemOneProviders.defaultTimeout), providerName, headers, env, id.some)
  }

  // the servers speaking the System One api: only their base url tells them apart
  private val nativeBuilders: Map[String, DecisionModel.ClientContext => Option[DecisionModelClient]] =
    SystemOneProviders.all.map { provDef =>
      provDef.id -> { (c: ClientContext) =>
        val url = s"${c.baseUrl.getOrElse(provDef.baseUrl).stripSuffix("/")}${c.path}"
        new SystemOneDecisionModelClient(c.api(provDef.name), url, provDef.name, DecisionModelClientOptions.fromJson(c.options), SystemOneProviders.routingProviders.contains(provDef.id)).some
      }
    }.toMap

  // Single source of truth for the decision modality: provider id -> client builder.
  // `supportedProviders` (and the providers catalog) is derived from these keys, so adding a
  // provider here is the only change required.
  private val explicitBuilders: Map[String, DecisionModel.ClientContext => Option[DecisionModelClient]] = Map(
    // any server speaking the System One api (Laya, vLLM, a LiteLLM proxy...): base url, path, display name
    // and headers are all driven by the connection config.
    SystemOneProviders.Compatible -> { (c: ClientContext) =>
      c.baseUrl.map { baseUrl =>
        val providerName = c.connection.select("provider_name").asOptString
          .orElse(c.connection.select("name").asOptString)
          .getOrElse("System One Compatible")
        new SystemOneDecisionModelClient(c.api(providerName), s"${baseUrl.stripSuffix("/")}${c.path}", providerName, DecisionModelClientOptions.fromJson(c.options), forwardsRouting = false)
      }
    },
    SystemOneProviders.Cloudflare -> { (c: ClientContext) =>
      c.connection.select("account_id").asOptString.map(_.trim).filter(_.nonEmpty).map { accountId =>
        new CloudflareDecisionModelClient(c.api("Cloudflare"), c.baseUrl.getOrElse(SystemOneProviders.cloudflareBaseUrl).stripSuffix("/"), accountId, DecisionModelClientOptions.fromJson(c.options))
      }
    },
    SystemOneProviders.LlmEmulation -> { (c: ClientContext) =>
      c.connection.select("provider").asOptString.map(_.trim).filter(_.nonEmpty).map { providerRef =>
        new LlmDecisionModelClient(c.self.target, providerRef, DecisionModelClientOptions.fromJson(c.options))
      }
    },
  )

  val clientBuilders: Map[String, DecisionModel.ClientContext => Option[DecisionModelClient]] = nativeBuilders ++ explicitBuilders

  val supportedProviders: Set[String] = clientBuilders.keySet

  val format = new Format[DecisionModel] {
    override def writes(o: DecisionModel): JsValue = o.location.jsonWithKey ++ Json.obj(
      "id" -> o.id,
      "name" -> o.name,
      "description" -> o.description,
      "metadata" -> o.metadata,
      "tags" -> JsArray(o.tags.map(JsString.apply)),
      "provider" -> o.provider,
      "config" -> o.config,
      "models" -> o.models.json,
      "fallback_ref" -> o.fallbackRef.map(_.json).getOrElse(JsNull).asValue,
      "fallback_model" -> o.fallbackModel.map(_.json).getOrElse(JsNull).asValue,
    )

    override def reads(json: JsValue): JsResult[DecisionModel] = Try {
      DecisionModel(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = (json \ "id").as[String],
        name = (json \ "name").as[String],
        description = (json \ "description").asOpt[String].getOrElse(""),
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        provider = (json \ "provider").as[String],
        config = (json \ "config").asOpt[JsObject].getOrElse(Json.obj()),
        models = ModelSettings.format.reads((json \ "models").asOpt[JsObject].getOrElse(Json.obj())).getOrElse(ModelSettings.empty),
        fallbackRef = (json \ "fallback_ref").asOpt[String].map(_.trim).filter(_.nonEmpty),
        fallbackModel = (json \ "fallback_model").asOpt[String].map(_.trim).filter(_.nonEmpty),
      )
    } match {
      case Failure(ex) => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }

  // the config a new decision model starts from, for each kind of provider
  def defaultConfig(kind: String): JsObject = {
    val timeout = SystemOneProviders.defaultTimeout.toMillis
    kind match {
      case SystemOneProviders.Cloudflare => Json.obj(
        "connection" -> Json.obj("account_id" -> "xxxxx", "token" -> "xxxxx", "timeout" -> timeout),
        "options" -> Json.obj("model" -> SystemOneProviders.cloudflareDefaultModel),
      )
      case SystemOneProviders.LlmEmulation => Json.obj(
        "connection" -> Json.obj("provider" -> ""),
        "options" -> Json.obj(),
      )
      case SystemOneProviders.Compatible => Json.obj(
        "connection" -> Json.obj("base_url" -> "http://localhost:8000/v1", "path" -> SystemOneProviders.defaultPath, "token" -> "xxxxx", "timeout" -> timeout),
        "options" -> Json.obj("model" -> ""),
      )
      case other =>
        val provDef = SystemOneProviders.find(other).getOrElse(SystemOneProviders.all.head)
        Json.obj(
          "connection" -> Json.obj("base_url" -> provDef.baseUrl, "token" -> "xxxxx", "timeout" -> timeout),
          "options" -> Json.obj("model" -> provDef.defaultModel),
        )
    }
  }

  def resource(env: Env, datastores: AiGatewayExtensionDatastores, states: AiGatewayExtensionState): Resource = {
    Resource(
      "DecisionModel",
      "decision-models",
      "decision-model",
      "ai-gateway.extensions.cloud-apim.com",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[DecisionModel](
        format = DecisionModel.format,
        clazz = classOf[DecisionModel],
        keyf = id => datastores.decisionModelsDataStore.key(id),
        extractIdf = c => datastores.decisionModelsDataStore.extractId(c),
        extractIdJsonf = json => json.select("id").asString,
        idFieldNamef = () => "id",
        tmpl = (_, p, _) => {
          val kind = p.get("kind").map(_.toLowerCase()).filter(supportedProviders.contains).getOrElse(SystemOneProviders.all.head.id)
          val label = AiProvidersCatalog.labelFor(kind)
          DecisionModel(
            id = IdGenerator.namedId("decision-model", env),
            name = s"${label} decision model",
            description = s"A decision model served by ${label}",
            metadata = Map.empty,
            tags = Seq.empty,
            location = EntityLocation.default,
            provider = kind,
            config = defaultConfig(kind),
          ).json
        },
        canRead = true,
        canCreate = true,
        canUpdate = true,
        canDelete = true,
        canBulk = true,
        stateAll = () => states.allDecisionModels(),
        stateOne = id => states.decisionModel(id),
        stateUpdate = values => states.updateDecisionModels(values)
      )
    )
  }
}

trait DecisionModelsDataStore extends BasicStore[DecisionModel]

class KvDecisionModelsDataStore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
  extends DecisionModelsDataStore
    with RedisLikeStore[DecisionModel] {
  override def fmt: Format[DecisionModel] = DecisionModel.format

  override def redisLike(using env: Env): RedisLike = redisCli

  override def key(id: String): String = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:decisionmodels:$id"

  override def extractId(value: DecisionModel): String = value.id
}
