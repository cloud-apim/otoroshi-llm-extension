package com.cloud.apim.otoroshi.extensions.aigateway.entities

import com.cloud.apim.otoroshi.extensions.aigateway.EmbeddingModelClient
import com.cloud.apim.otoroshi.extensions.aigateway.decorators.EmbeddingModelClientDecorators
import com.cloud.apim.otoroshi.extensions.aigateway.providers._
import otoroshi.api._
import otoroshi.env.Env
import otoroshi.models._
import otoroshi.next.extensions.AdminExtensionId
import otoroshi.security.IdGenerator
import otoroshi.storage._
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway._
import play.api.libs.json._

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.{Failure, Success, Try}

case class EmbeddingModel(
  location: EntityLocation,
  id: String,
  name: String,
  description: String,
  tags: Seq[String],
  metadata: Map[String, String],
  provider: String,
  config: JsObject,
  models: ModelSettings = ModelSettings.empty,
) extends EntityLocationSupport {
  override def internalId: String               = id
  override def json: JsValue                    = EmbeddingModel.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata
  def slugName: String = metadata.getOrElse("endpoint_name", name).slugifyWithSlash.replaceAll("-+", "_")
  def getEmbeddingModelClient()(implicit env: Env): Option[EmbeddingModelClient] = {
    val connection = config.select("connection").asOpt[JsObject].getOrElse(Json.obj())
    val options = config.select("options").asOpt[JsObject].getOrElse(Json.obj())
    val baseUrl = connection.select("base_url").orElse(connection.select("base_domain")).asOpt[String]
    val _token = connection.select("token").asOpt[String].getOrElse("xxx")
    val token = if (_token.contains(",")) {
      val parts = _token.split(",").map(_.trim)
      val index = AiProvider.tokenCounter.incrementAndGet() % (if (parts.nonEmpty) parts.length else 1)
      parts(index)
    } else {
      _token
    }
    val timeout = connection.select("timeout").asOpt[Long].map(FiniteDuration(_, TimeUnit.MILLISECONDS))
    val rawClient = EmbeddingModel.clientBuilders
      .get(provider.toLowerCase())
      .flatMap(_.apply(EmbeddingModel.ClientContext(connection, baseUrl, token, timeout, options, id, env)))
    rawClient.map(c => EmbeddingModelClientDecorators(this, c, env))
  }
}

object EmbeddingModel {

  final case class ClientContext(connection: JsObject, baseUrl: Option[String], token: String, timeout: Option[FiniteDuration], options: JsObject, id: String, env: Env)

  // Single source of truth for the embedding modality: provider id -> client builder.
  // `supportedProviders` (and the providers catalog) is derived from these keys. The OpenAI-like
  // providers that advertise `supportsEmbeddings` are added automatically, so adding such a
  // provider in OpenAiLikeProviders is enough.
  val clientBuilders: Map[String, EmbeddingModel.ClientContext => Option[EmbeddingModelClient]] = {
    val explicit: Map[String, EmbeddingModel.ClientContext => Option[EmbeddingModelClient]] = Map(
      "openai" -> { (c: ClientContext) =>
        import c._
        val api = new OpenAiApi(baseUrl.getOrElse(OpenAiApi.baseUrl), token, timeout.getOrElse(30.seconds), providerName = "OpenAI", env = env)
        val opts = OpenAiEmbeddingModelClientOptions.fromJson(options)
        new OpenAiEmbeddingModelClient(api, opts, id).some
      },
      "azure-openai" -> { (c: ClientContext) =>
        import c._
        val resourceName = connection.select("resource_name").as[String]
        val deploymentId = connection.select("deployment_id").as[String]
        val version = connection.select("api_version").asOpt[String].getOrElse("v1")
        val apikey = connection.select("api_key").asOpt[String]
        val bearer = Some(token).filterNot(_ == "xxx")
        if (version == "v1") {
          val api = new OpenAiApi(baseUrl.getOrElse("https://<aoairesource>.openai.azure.com/openai/v1"), token, timeout.getOrElse(30.seconds), providerName = "Azure-OpenAI", env = env)
          val opts = OpenAiEmbeddingModelClientOptions.fromJson(options)
          new OpenAiEmbeddingModelClient(api, opts, id).some
        } else {
          val api = new AzureOpenAiApi(resourceName, deploymentId, version, apikey, bearer, timeout.getOrElse(3.minutes), env = env)
          new AzureOpenAiEmbeddingModelClient(api, options, id).some
        }
      },
      "azure-ai-foundry" -> { (c: ClientContext) =>
        import c._
        val api = new OpenAiApi(baseUrl.getOrElse(AzureAiFoundry.baseUrl), token, timeout.getOrElse(30.seconds), providerName = "Azure AI Foundry", env = env)
        val opts = OpenAiEmbeddingModelClientOptions.fromJson(options)
        new OpenAiEmbeddingModelClient(api, opts, id).some
      },
      "scaleway" -> { (c: ClientContext) =>
        import c._
        val api = new OpenAiApi(baseUrl.getOrElse(ScalewayApi.baseUrl), token, timeout.getOrElse(10.seconds), providerName = "Scaleway", env = env)
        val opts = OpenAiEmbeddingModelClientOptions.fromJson(options)
        new OpenAiEmbeddingModelClient(api, opts, id).some
      },
      "cloud-temple" -> { (c: ClientContext) =>
        import c._
        val api = new OpenAiApi(baseUrl.getOrElse(CloudTemple.baseUrl), token, timeout.getOrElse(10.seconds), providerName = "Cloud Temple", env = env)
        val opts = OpenAiEmbeddingModelClientOptions.fromJson(options)
        new OpenAiEmbeddingModelClient(api, opts, id).some
      },
      "deepseek" -> { (c: ClientContext) =>
        import c._
        val api = new OpenAiApi(baseUrl.getOrElse(DeepSeekApi.baseUrl), token, timeout.getOrElse(10.seconds), providerName = "Deepseek", env = env)
        val opts = OpenAiEmbeddingModelClientOptions.fromJson(options)
        new OpenAiEmbeddingModelClient(api, opts, id).some
      },
      "gemini" -> { (c: ClientContext) =>
        import c._
        val api = new OpenAiApi(baseUrl.getOrElse(GeminiApi.baseUrl), token, timeout.getOrElse(10.seconds), providerName = "gemini", env = env)
        val opts = OpenAiEmbeddingModelClientOptions.fromJson(options)
        new OpenAiEmbeddingModelClient(api, opts, id).some
      },
      "huggingface" -> { (c: ClientContext) =>
        import c._
        val api = new OpenAiApi(baseUrl.getOrElse(HuggingfaceApi.baseUrl), token, timeout.getOrElse(10.seconds), providerName = "huggingface", env = env)
        val opts = OpenAiEmbeddingModelClientOptions.fromJson(options)
        new OpenAiEmbeddingModelClient(api, opts, id).some
      },
      "mistral" -> { (c: ClientContext) =>
        import c._
        val api = new MistralAiApi(baseUrl.getOrElse(OpenAiApi.baseUrl), token, timeout.getOrElse(30.seconds), env = env)
        val opts = MistralAiEmbeddingModelClientOptions.fromJson(options)
        new MistralAiEmbeddingModelClient(api, opts, id).some
      },
      "ollama" -> { (c: ClientContext) =>
        import c._
        val api = new OllamaAiApi(baseUrl.getOrElse(OllamaAiApi.baseUrl), token.some.filterNot(_ == "xxx"), timeout.getOrElse(10.seconds), env = env)
        val opts = OllamaEmbeddingModelClientOptions.fromJson(options)
        new OllamaEmbeddingModelClient(api, opts, id).some
      },
      "x-ai" -> { (c: ClientContext) =>
        import c._
        val api = new XAiApi(baseUrl.getOrElse(XAiApi.baseUrl), token, timeout.getOrElse(10.seconds), env = env)
        val opts = XAiEmbeddingModelClientOptions.fromJson(options)
        new XAiEmbeddingModelClient(api, opts, id).some
      },
      "cohere" -> { (c: ClientContext) =>
        import c._
        val api = new CohereAiApi(baseUrl.getOrElse(CohereAiApi.baseUrl), token, timeout.getOrElse(10.seconds), env = env)
        val opts = CohereAiEmbeddingModelClientOptions.fromJson(options)
        new CohereAiEmbeddingModelClient(api, opts, id).some
      },
      "openai-compatible" -> { (c: ClientContext) =>
        import c._
        // generic OpenAI-compatible embedding endpoint: base url, display name, headers and param
        // mappings are all driven by the connection config (dynamic name).
        val providerName = connection.select("provider_name").asOpt[String]
          .orElse(connection.select("name").asOpt[String])
          .getOrElse("OpenAI Compatible")
        val paramMappings = connection.select("param_mappings").asOpt[Map[String, String]].getOrElse(Map.empty)
        val customHeaders = connection.select("headers").asOpt[Map[String, String]].getOrElse(Map("Authorization" -> "Bearer {api_key}"))
        val additionalBodyParams = connection.select("additional_body_params").asOpt[JsObject].getOrElse(Json.obj())
        val api = new OpenAiApi(
          _baseUrl = baseUrl.getOrElse(OpenAiApi.baseUrl),
          token = token,
          timeout = timeout.getOrElse(30.seconds),
          providerName = providerName,
          env = env,
          param_mappings = paramMappings,
          headers = customHeaders,
          additional_body_params = additionalBodyParams,
        )
        val opts = OpenAiEmbeddingModelClientOptions.fromJson(options)
        new OpenAiEmbeddingModelClient(api, opts, id).some
      },
      "ovh-ai-endpoints" -> { (c: ClientContext) =>
        import c._
        val api = new OpenAiApi(baseUrl.getOrElse(OVHAiEndpointsApi.unifiedUrl), token, timeout.getOrElse(10.seconds), providerName = "OVH", env = env)
        val opts = OpenAiEmbeddingModelClientOptions.fromJson(options)
        new OpenAiEmbeddingModelClient(api, opts, id).some
      },
      "all-minilm-l6-v2" -> { (c: ClientContext) =>
        import c._
        new AllMiniLmL6V2EmbeddingModelClient(options, id).some
      },
    )
    val likes: Map[String, EmbeddingModel.ClientContext => Option[EmbeddingModelClient]] =
      OpenAiLikeProviders.all.filter(_.supportsEmbeddings).map { provDef =>
        provDef.id -> { (c: ClientContext) =>
          import c._
          val api = new OpenAiApi(
            _baseUrl = baseUrl.getOrElse(provDef.baseUrl),
            token = token,
            timeout = timeout.getOrElse(3.minutes),
            providerName = provDef.name,
            env = env,
            headers = provDef.headers,
          )
          val opts = OpenAiEmbeddingModelClientOptions.fromJson(options)
          new OpenAiEmbeddingModelClient(api, opts, id).some
        }
      }.toMap
    likes ++ explicit
  }

  val supportedProviders: Set[String] = clientBuilders.keySet

  val format = new Format[EmbeddingModel] {
    override def writes(o: EmbeddingModel): JsValue             = o.location.jsonWithKey ++ Json.obj(
      "id"               -> o.id,
      "name"             -> o.name,
      "description"      -> o.description,
      "metadata"         -> o.metadata,
      "tags"             -> JsArray(o.tags.map(JsString.apply)),
      "provider"         -> o.provider,
      "config"           -> o.config,
      "models"           -> o.models.json
    )
    override def reads(json: JsValue): JsResult[EmbeddingModel] = Try {
      EmbeddingModel(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = (json \ "id").as[String],
        name = (json \ "name").as[String],
        description = (json \ "description").as[String],
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        provider = (json \ "provider").as[String],
        config = (json \ "config").asOpt[JsObject].getOrElse(Json.obj()),
        models = ModelSettings.format.reads((json \ "models").asOpt[JsObject].getOrElse(Json.obj())).getOrElse(ModelSettings.empty)
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
  def resource(env: Env, datastores: AiGatewayExtensionDatastores, states: AiGatewayExtensionState): Resource = {
    Resource(
      "EmbeddingModel",
      "embedding-models",
      "embedding-model",
      "ai-gateway.extensions.cloud-apim.com",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[EmbeddingModel](
        format = EmbeddingModel.format ,
        clazz = classOf[EmbeddingModel],
        keyf = id => datastores.embeddingModelsDataStore.key(id),
        extractIdf = c => datastores.embeddingModelsDataStore.extractId(c),
        extractIdJsonf = json => json.select("id").asString,
        idFieldNamef = () => "id",
        tmpl = (v, p, ctx) => {
          p.get("kind").map(_.toLowerCase()) match {
            case Some("openai") => EmbeddingModel(
              id = IdGenerator.namedId("embedding-model", env),
              name = "OpenAI text-embedding-3-small",
              description = "An OpenAI embedding model",
              metadata = Map.empty,
              tags = Seq.empty,
              location = EntityLocation.default,
              provider = "openai",
              config = Json.obj(
                "connection" -> Json.obj(
                  "base_url" -> OpenAiApi.baseUrl,
                  "token" -> "xxxxx",
                  "timeout" -> 3.minutes.toMillis,
                ),
                "options" -> Json.obj(
                  "model" -> "text-embedding-3-small"
                )
              ),
            ).json
            case Some("ollama") => EmbeddingModel(
              id = IdGenerator.namedId("embedding-model", env),
              name = "Ollama embedding model",
              description = "An Ollama embedding model",
              metadata = Map.empty,
              tags = Seq.empty,
              location = EntityLocation.default,
              provider = "ollama",
              config = Json.obj(
                "connection" -> Json.obj(
                  "base_url" -> OllamaAiApi.baseUrl,
                  "token" -> "xxxxx",
                  "timeout" -> 3.minutes.toMillis,
                ),
                "options" -> Json.obj(
                  "model" -> "snowflake-arctic-embed:22m"
                )
              ),
            ).json
            case _ => EmbeddingModel(
              id = IdGenerator.namedId("embedding-model", env),
              name = "Local embedding model",
              description = "A Local embedding model",
              metadata = Map.empty,
              tags = Seq.empty,
              location = EntityLocation.default,
              provider = "all-minilm-l6-v2",
              config = Json.obj(),
            ).json
          }

        },
        canRead = true,
        canCreate = true,
        canUpdate = true,
        canDelete = true,
        canBulk = true,
        stateAll = () => states.allEmbeddingModels(),
        stateOne = id => states.embeddingModel(id),
        stateUpdate = values => states.updateEmbeddingModels(values)
      )
    )
  }
}

trait EmbeddingModelsDataStore extends BasicStore[EmbeddingModel]

class KvEmbeddingModelsDataStore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
  extends EmbeddingModelsDataStore
    with RedisLikeStore[EmbeddingModel] {
  override def fmt: Format[EmbeddingModel]                  = EmbeddingModel.format
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def key(id: String): String                 = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:embmods:$id"
  override def extractId(value: EmbeddingModel): String    = value.id
}
