package com.cloud.apim.otoroshi.extensions.aigateway.entities

import com.cloud.apim.otoroshi.extensions.aigateway.ChatClient
import com.cloud.apim.otoroshi.extensions.aigateway.decorators.{ChatClientDecorators, Guardrails, LoadBalancerChatClient}
import com.cloud.apim.otoroshi.extensions.aigateway.providers._
import otoroshi.api.{GenericResourceAccessApiWithState, Resource, ResourceVersion}
import otoroshi.env.Env
import otoroshi.models._
import otoroshi.next.extensions.AdminExtensionId
import otoroshi.security.IdGenerator
import otoroshi.storage._
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.{AiGatewayExtensionDatastores, AiGatewayExtensionState}
import play.api.libs.json._

import java.util.concurrent.TimeUnit
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

case class RegexValidationSettings(
  allow: Seq[String] = Seq.empty,
  deny: Seq[String] = Seq.empty,
)

case class LlmValidationSettings(
  provider: Option[String] = None,
  prompt: Option[String] = None,
) {
  def json: JsValue = LlmValidationSettings.format.writes(this)
}

object LlmValidationSettings {
  val format = new Format[LlmValidationSettings] {
    override def reads(json: JsValue): JsResult[LlmValidationSettings] = Try {
      LlmValidationSettings(
        provider = (json \ "provider").asOpt[String],
        prompt = (json \ "prompt").asOpt[String],
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
    override def writes(o: LlmValidationSettings): JsValue = Json.obj(
      "provider" -> o.provider,
      "prompt" -> o.prompt,
    )
  }
}

case class HttpValidationSettings(
  url: Option[String] = None,
  headers: Map[String, String] = Map.empty,
  ttl: FiniteDuration = 5.minutes,
) {
  def json: JsValue = HttpValidationSettings.format.writes(this)
}

object HttpValidationSettings {
  val format = new Format[HttpValidationSettings] {

    override def reads(json: JsValue): JsResult[HttpValidationSettings] = Try {
       HttpValidationSettings(
        url = (json \ "url").asOpt[String],
        headers = (json \ "headers").asOpt[Map[String, String]].getOrElse(Map.empty),
        ttl = (json \ "ttl").asOpt[Long].map(v => FiniteDuration(v, TimeUnit.MILLISECONDS)).getOrElse(5.minutes),
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }

    override def writes(o: HttpValidationSettings): JsValue = Json.obj(
      "url" -> o.url,
      "headers" -> o.headers,
      "ttl" -> o.ttl.toMillis,
    )
  }
}


case class CacheSettings(
  strategy: String = "none",
  ttl: FiniteDuration = 24.hours,
  score: Double = 0.8
)

case class ContextSettings(default: Option[String] = None, contexts: Seq[String] = Seq.empty) {
  def json: JsValue = ContextSettings.format.writes(this)
  def isDefined: Boolean = contexts.nonEmpty || default.nonEmpty
}

object ContextSettings {
  val empty = ContextSettings()
  val format = new Format[ContextSettings] {
    override def reads(json: JsValue): JsResult[ContextSettings] = Try {
      ContextSettings(
        default = json.select("default").asOpt[String].filterNot(_.isBlank),
        contexts = json.select("contexts").asOpt[Seq[String]].getOrElse(Seq.empty),
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }
    override def writes(o: ContextSettings): JsValue = Json.obj(
      "default" -> o.default.map(_.json).getOrElse(JsNull).asValue,
      "contexts" -> o.contexts,
    )
  }
}

case class ModelSettings(include: Seq[String] = Seq.empty, exclude: Seq[String] = Seq.empty) {
  def json: JsValue = ModelSettings.format.writes(this)
  def isDefined: Boolean = include.nonEmpty || exclude.nonEmpty
  def matches(model: String): Boolean = {
    if (!(include.isEmpty && exclude.isEmpty)) {
      val canpass    = if (include.isEmpty) true else include.exists(p => otoroshi.utils.RegexPool.regex(p).matches(model))
      val cannotpass =
        if (exclude.isEmpty) false else exclude.exists(p => otoroshi.utils.RegexPool.regex(p).matches(model))
      canpass && !cannotpass
    } else {
      true
    }
  }
}

object ModelSettings {
  val empty = ModelSettings()
  val format = new Format[ModelSettings] {
    override def reads(json: JsValue): JsResult[ModelSettings] = Try {
      ModelSettings(
        exclude = json.select("exclude").asOpt[Seq[String]].getOrElse(Seq.empty),
        include = json.select("include").asOpt[Seq[String]].getOrElse(Seq.empty),
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }
    override def writes(o: ModelSettings): JsValue = Json.obj(
      "include" -> o.include,
      "exclude" -> o.exclude,
    )
  }
}

case class AiProvider(
                       location: EntityLocation = EntityLocation.default,
                       id: String,
                       name: String,
                       description: String = "",
                       tags: Seq[String] = Seq.empty,
                       metadata: Map[String, String] = Map.empty,
                       provider: String,
                       connection: JsObject,
                       options: JsObject,
                       providerFallback: Option[String] = None,
                       cache: CacheSettings = CacheSettings(),
                       guardrails: Guardrails = Guardrails.empty,
                       guardrailsFailOnDeny: Boolean = false,
                       context: ContextSettings = ContextSettings.empty,
                       models: ModelSettings = ModelSettings.empty,
                     ) extends EntityLocationSupport {
  override def internalId: String               = id
  override def json: JsValue                    = AiProvider.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata
  def computedName: String = metadata.getOrElse("endpoint_name", name)
  def slugName: String = metadata.getOrElse("endpoint_name", name).slugifyWithSlash.replaceAll("-+", "_")
  def getChatClient()(implicit env: Env): Option[ChatClient] = {
    val baseUrl = connection.select("base_url").orElse(connection.select("base_domain")).asOpt[String]
    val token = connection.select("token").asOpt[String].getOrElse("xxx")
    val timeout = connection.select("timeout").asOpt[Long].map(FiniteDuration(_, TimeUnit.MILLISECONDS))
    val rawClient = provider.toLowerCase() match {
      case "openai" => {
        val api = new OpenAiApi(baseUrl.getOrElse(OpenAiApi.baseUrl), token, timeout.getOrElse(3.minutes), providerName = "OpenAI", env = env)
        val opts = OpenAiChatClientOptions.fromJson(options)
        new OpenAiChatClient(api, opts, id, "openai").some
      }
      case "scaleway" => {
        val api = new OpenAiApi(baseUrl.getOrElse(ScalewayApi.baseUrl), token, timeout.getOrElse(3.minutes), providerName = "Scaleway", env = env)
        val opts = OpenAiChatClientOptions.fromJson(options)
        new OpenAiChatClient(api, opts, id, "scaleway", accumulateStreamConsumptions = true).some
      }
      case "deepseek" => {
        val api = new OpenAiApi(baseUrl.getOrElse(DeepSeekApi.baseUrl), token, timeout.getOrElse(3.minutes), providerName = "Deepseek", env = env)
        val opts = OpenAiChatClientOptions.fromJson(options)
        new OpenAiChatClient(api, opts, id, "deepseek", "/models").some
      }
      case "x-ai" => {
        val api = new XAiApi(baseUrl.getOrElse(XAiApi.baseUrl), token, timeout.getOrElse(3.minutes), env = env)
        val opts = XAiChatClientOptions.fromJson(options)
        new XAiChatClient(api, opts, id).some
      }
      case "ovh-ai-endpoints" => {
        val unified = connection.select("unified").asOpt[Boolean].getOrElse(true)
        if (unified) {
          val api = new OpenAiApi(OVHAiEndpointsApi.unifiedUrl, token, timeout.getOrElse(3.minutes), providerName = "OVH", env = env)
          val opts = OpenAiChatClientOptions.fromJson(options)
          new OpenAiChatClient(api, opts, id, "OVH", "/models").some
        } else {
          val api = new OVHAiEndpointsApi(baseUrl.getOrElse(OVHAiEndpointsApi.baseDomain), token, timeout.getOrElse(3.minutes), env = env)
          val opts = OVHAiEndpointsChatClientOptions.fromJson(options)
          new OVHAiEndpointsChatClient(api, opts, id).some
        }
      }
      case "ovh-ai-endpoints-unified" => {
        val api = new OpenAiApi(baseUrl.getOrElse(OVHAiEndpointsApi.unifiedUrl), token, timeout.getOrElse(3.minutes), providerName = "OVH", env = env)
        val opts = OpenAiChatClientOptions.fromJson(options)
        new OpenAiChatClient(api, opts, id, "OVH", "/models").some
      }
      case "azure-openai" => {
        val resourceName = connection.select("resource_name").as[String]
        val deploymentId = connection.select("deployment_id").as[String]
        val apikey = connection.select("api_key").as[String]
        val api = new AzureOpenAiApi(resourceName, deploymentId, apikey, timeout.getOrElse(3.minutes), env = env)
        val opts = AzureOpenAiChatClientOptions.fromJson(options)
        new AzureOpenAiChatClient(api, opts, id).some
      }
      case "azure-ai-foundry" => {
        val api = new OpenAiApi(baseUrl.getOrElse(AzureAiFoundry.baseUrl), token, timeout.getOrElse(3.minutes), providerName = "Azure AI Foundry", env = env)
        val opts = OpenAiChatClientOptions.fromJson(options)
        new OpenAiChatClient(api, opts, id, "azure-ai-foundry", "/models").some
      }
      case "cloudflare" => {
        val accountId = connection.select("account_id").as[String]
        val modelName = connection.select("model_name").as[String]
        val api = new CloudflareApi(accountId, modelName, token, timeout.getOrElse(3.minutes), env = env)
        val opts = CloudflareChatClientOptions.fromJson(options)
        new CloudflareChatClient(api, opts, id).some
      }
      case "gemini" => {
        val model = connection.select("model").asOpt[String].getOrElse("gemini-1.5-flash")
        //-------
        //val api = new GeminiApi(model, token, timeout.getOrElse(3.minutes), env = env)
        //val opts = GeminiChatClientOptions.fromJson(options)
        //new GeminiChatClient(api, opts, id).some
        //-------
        val api = new OpenAiApi(baseUrl.getOrElse(GeminiApi.baseUrl), token, timeout.getOrElse(3.minutes), providerName = "gemini", env = env)
        val opts = OpenAiChatClientOptions.fromJson(options).copy(model = model)
        new OpenAiChatClient(api, opts, id, "gemini", "/models", completion = false, accumulateStreamConsumptions = true).some
      }
      case "huggingface" => {
        // val modelName = connection.select("model_name").as[String]
        // val api = new HuggingfaceApi(modelName, token, timeout.getOrElse(3.minutes), env)
        // val opts = HuggingfaceChatClientOptions.fromJson(options)
        // new HuggingfaceChatClient(api, opts, id).some
        val api = new OpenAiApi(baseUrl.getOrElse(HuggingfaceApi.baseUrl), token, timeout.getOrElse(3.minutes), providerName = "huggingface", env = env)
        val opts = OpenAiChatClientOptions.fromJson(options)
        new OpenAiChatClient(api, opts, id, "huggingface", "/models", completion = false).some
      }
      case "mistral" => {
        val api = new MistralAiApi(baseUrl.getOrElse(MistralAiApi.baseUrl), token, timeout.getOrElse(3.minutes), env = env)
        val opts = MistralAiChatClientOptions.fromJson(options)
        new MistralAiChatClient(api, opts, id).some
      }
      case "ollama" => {
        val api = new OllamaAiApi(baseUrl.getOrElse(OllamaAiApi.baseUrl), token.some.filterNot(_ == "xxx"), timeout.getOrElse(3.minutes), env = env)
        val opts = OllamaAiChatClientOptions.fromJson(options)
        new OllamaAiChatClient(api, opts, id).some
      }
      case "cohere" => {
        val api = new CohereAiApi(baseUrl.getOrElse(CohereAiApi.baseUrl), token, timeout.getOrElse(3.minutes), env = env)
        val opts = CohereAiChatClientOptions.fromJson(options)
        new CohereAiChatClient(api, opts, id).some
      }
      case "anthropic" => {
        val api = new AnthropicApi(baseUrl.getOrElse(AnthropicApi.baseUrl), token, timeout.getOrElse(3.minutes), env = env)
        val opts = AnthropicChatClientOptions.fromJson(options)
        new AnthropicChatClient(api, opts, id).some
      }
      case "groq" => {
        val api = new GroqApi(baseUrl.getOrElse(GroqApi.baseUrl), token, timeout.getOrElse(3.minutes), env = env)
        val opts = GroqChatClientOptions.fromJson(options)
        new GroqChatClient(api, opts, id).some
      }
      case "loadbalancer" => new LoadBalancerChatClient(this).some
      case _ => None
    }
    rawClient.map(c => ChatClientDecorators(this, c, env))
  }
}

object AiProvider {
  val format = new Format[AiProvider] {
    override def writes(o: AiProvider): JsValue = o.location.jsonWithKey ++ Json.obj(
      "id"                -> o.id,
      "name"              -> o.name,
      "description"       -> o.description,
      "metadata"          -> o.metadata,
      "tags"              -> JsArray(o.tags.map(JsString.apply)),
      "provider"          -> o.provider,
      "connection"        -> o.connection,
      "options"           -> o.options,
      "provider_fallback" -> o.providerFallback.map(_.json).getOrElse(JsNull).asValue,
      "context"           -> o.context.json,
      "models"            -> o.models.json,
      "guardrails"        -> o.guardrails.json,
      "guardrails_fail_on_deny" -> o.guardrailsFailOnDeny,
      "cache" -> Json.obj(
        "strategy" -> o.cache.strategy,
        "ttl"      -> o.cache.ttl.toMillis,
        "score"    -> o.cache.score
      )
    )
    override def reads(json: JsValue): JsResult[AiProvider] = Try {
      AiProvider(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = (json \ "id").as[String],
        name = (json \ "name").as[String],
        description = (json \ "description").asOpt[String].getOrElse(""),
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        provider = (json \ "provider").as[String],
        connection = (json \ "connection").asOpt[JsObject].getOrElse(Json.obj()),
        options = (json \ "options").asOpt[JsObject].getOrElse(Json.obj()),
        providerFallback = (json \ "provider_fallback").asOpt[String],
        guardrails = json.select("guardrails").asOpt[JsArray].orElse(json.select("fences").asOpt[JsArray]).flatMap(seq => Guardrails.format.reads(seq).asOpt).getOrElse(Guardrails.empty),
        guardrailsFailOnDeny = json.select("guardrails_fail_on_deny").asOpt[Boolean].getOrElse(false),
        context = json.select("context").asOpt[JsObject].flatMap(o => ContextSettings.format.reads(o).asOpt).getOrElse(ContextSettings.empty),
        models = json.select("models").asOpt[JsObject].flatMap(o => ModelSettings.format.reads(o).asOpt).getOrElse(ModelSettings.empty),
        cache = CacheSettings(
          strategy = (json \ "cache" \ "strategy").asOpt[String].getOrElse("none"),
          ttl = (json \ "cache" \ "ttl").asOpt[Long].map(v => FiniteDuration(v, TimeUnit.MILLISECONDS)).getOrElse(24.hours),
          score = (json \ "cache" \ "score").asOpt[Double].getOrElse(0.8),
        )
      )
    } match {
      case Failure(ex)    => JsError(ex.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
  def resource(env: Env, datastores: AiGatewayExtensionDatastores, states: AiGatewayExtensionState): Resource = {
    Resource(
      "AiProvider",
      "providers",
      "provider",
      "ai-gateway.extensions.cloud-apim.com",
      ResourceVersion("v1", true, false, true),
      GenericResourceAccessApiWithState[AiProvider](
        format = AiProvider.format ,
        clazz = classOf[AiProvider],
        keyf = id => datastores.providersDatastore.key(id),
        extractIdf = c => datastores.providersDatastore.extractId(c),
        extractIdJsonf = json => json.select("id").asString,
        idFieldNamef = () => "id",
        tmpl = (v, p, ctx) => {
          p.get("provider").map(_.toLowerCase()) match {
            case Some("openai") => AiProvider(
              id = IdGenerator.namedId("provider", env),
              name = "OpenAI provider",
              description = "An OpenAI LLM api provider",
              metadata = Map.empty,
              tags = Seq.empty,
              location = EntityLocation.default,
              provider = "openai",
              connection = Json.obj(
                "base_url" -> OpenAiApi.baseUrl,
                "token" -> "xxxxx",
                "timeout" -> 3.minutes.toMillis,
              ),
              options = OpenAiChatClientOptions().json
            ).json
            case Some("scaleway") => AiProvider(
              id = IdGenerator.namedId("provider", env),
              name = "Scaleway provider",
              description = "An Scaleway LLM api provider",
              metadata = Map.empty,
              tags = Seq.empty,
              location = EntityLocation.default,
              provider = "scaleway",
              connection = Json.obj(
                "base_url" -> ScalewayApi.baseUrl,
                "token" -> "xxxxx",
                "timeout" -> 3.minutes.toMillis,
              ),
              options = OpenAiChatClientOptions().copy(model = "llama-3.1-8b-instruct").json
            ).json
            case Some("x-ai") => AiProvider(
              id = IdGenerator.namedId("provider", env),
              name = "X.AI provider",
              description = "An X.AI LLM api provider",
              metadata = Map.empty,
              tags = Seq.empty,
              location = EntityLocation.default,
              provider = "x-ai",
              connection = Json.obj(
                "base_url" -> XAiApi.baseUrl,
                "token" -> "xxxxx",
                "timeout" -> 3.minutes.toMillis,
              ),
              options = XAiChatClientOptions().json
            ).json
            case Some("mistral") => AiProvider(
              id = IdGenerator.namedId("provider", env),
              name = "Mistral provider",
              description = "A Mistral LLM api provider",
              metadata = Map.empty,
              tags = Seq.empty,
              location = EntityLocation.default,
              provider = "mistral",
              connection = Json.obj(
                "base_url" -> MistralAiApi.baseUrl,
                "token" -> "xxxxx",
                "timeout" -> 3.minutes.toMillis,
              ),
              options = MistralAiChatClientOptions().json
            ).json
            case Some("ovh-ai-endpoints") => AiProvider(
              id = IdGenerator.namedId("provider", env),
              name = "OVH AI Endpoints provider",
              description = "An OVH AI Endpoints LLM api provider",
              metadata = Map.empty,
              tags = Seq.empty,
              location = EntityLocation.default,
              provider = "ovh-ai-endpoints",
              connection = Json.obj(
                "base_domain" -> OVHAiEndpointsApi.baseDomain,
                "token" -> "xxxxx",
                "timeout" -> 3.minutes.toMillis,
              ),
              options = OVHAiEndpointsChatClientOptions().json
            ).json
            case Some("gemini") => AiProvider(
              id = IdGenerator.namedId("provider", env),
              name = "Gemini provider",
              description = "A Gemini LLM api provider",
              metadata = Map.empty,
              tags = Seq.empty,
              location = EntityLocation.default,
              provider = "gemini",
              connection = Json.obj(
                "model" -> GeminiModels.GEMINI_1_5_FLASH,
                "token" -> "xxxxx",
                "timeout" -> 3.minutes.toMillis,
              ),
              options = MistralAiChatClientOptions().json
            ).json
            case Some("huggingface") => AiProvider(
              id = IdGenerator.namedId("provider", env),
              name = "Huggingface inference Endpoints provider",
              description = "An huggingface Endpoints LLM api provider",
              metadata = Map.empty,
              tags = Seq.empty,
              location = EntityLocation.default,
              provider = "huggingface",
              connection = Json.obj(
                "model" -> HuggingfaceModels.GOOGLE_GEMMA_2_2B,
                "token" -> "xxxxx",
                "timeout" -> 3.minutes.toMillis,
              ),
              options = OVHAiEndpointsChatClientOptions().json
            ).json
            case _ => AiProvider(
              id = IdGenerator.namedId("provider", env),
              name = "OpenAI provider",
              description = "An OpenAI LLM api provider",
              metadata = Map.empty,
              tags = Seq.empty,
              location = EntityLocation.default,
              provider = "openai",
              connection = Json.obj(
                "base_url" -> OpenAiApi.baseUrl,
                "token" -> "xxxxx",
                "timeout" -> 3.minutes.toMillis,
              ),
              options = OpenAiChatClientOptions().json
            ).json
          }

        },
        canRead = true,
        canCreate = true,
        canUpdate = true,
        canDelete = true,
        canBulk = true,
        stateAll = () => states.allProviders(),
        stateOne = id => states.provider(id),
        stateUpdate = values => states.updateProviders(values)
      )
    )
  }
}

trait AiProviderDataStore extends BasicStore[AiProvider]

class KvAiProviderDataStore(extensionId: AdminExtensionId, redisCli: RedisLike, _env: Env)
  extends AiProviderDataStore
    with RedisLikeStore[AiProvider] {
  override def fmt: Format[AiProvider]                  = AiProvider.format
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def key(id: String): String                 = s"${_env.storageRoot}:extensions:${extensionId.cleanup}:providers:$id"
  override def extractId(value: AiProvider): String    = value.id
}
