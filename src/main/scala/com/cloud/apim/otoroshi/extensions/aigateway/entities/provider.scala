package com.cloud.apim.otoroshi.extensions.aigateway.entities

import com.cloud.apim.otoroshi.extensions.aigateway.ChatClient
import com.cloud.apim.otoroshi.extensions.aigateway.decorators.{ChatClientDecorators, LoadBalancerChatClient}
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
)

case class HttpValidationSettings(
  url: Option[String] = None,
  headers: Map[String, String] = Map.empty,
  ttl: FiniteDuration = 5.minutes,
)


case class CacheSettings(
  strategy: String = "none",
  ttl: FiniteDuration = 24.hours,
  score: Double = 0.8
)

case class AiProvider(
                       location: EntityLocation,
                       id: String,
                       name: String,
                       description: String,
                       tags: Seq[String],
                       metadata: Map[String, String],
                       provider: String,
                       connection: JsObject,
                       options: JsObject,
                       providerFallback: Option[String] = None,
                       regexValidation: RegexValidationSettings = RegexValidationSettings(),
                       llmValidation: LlmValidationSettings = LlmValidationSettings(),
                       httpValidation: HttpValidationSettings = HttpValidationSettings(),
                       cache: CacheSettings = CacheSettings(),
                     ) extends EntityLocationSupport {
  override def internalId: String               = id
  override def json: JsValue                    = AiProvider.format.writes(this)
  override def theName: String                  = name
  override def theDescription: String           = description
  override def theTags: Seq[String]             = tags
  override def theMetadata: Map[String, String] = metadata
  def getChatClient()(implicit env: Env): Option[ChatClient] = {
    val baseUrl = connection.select("base_url").orElse(connection.select("base_domain")).asOpt[String]
    val token = connection.select("token").asOpt[String].getOrElse("xxx")
    val timeout = connection.select("timeout").asOpt[Long].map(FiniteDuration(_, TimeUnit.MILLISECONDS))
    val rawClient = provider.toLowerCase() match {
      case "openai" => {
        val api = new OpenAiApi(baseUrl.getOrElse(OpenAiApi.baseUrl), token, timeout.getOrElse(10.seconds), env = env)
        val opts = OpenAiChatClientOptions.fromJson(options)
        new OpenAiChatClient(api, opts, id).some
      }
      case "ovh-ai-endpoints" => {
        val api = new OVHAiEndpointsApi(baseUrl.getOrElse(OVHAiEndpointsApi.baseDomain), token, timeout.getOrElse(10.seconds), env = env)
        val opts = OVHAiEndpointsChatClientOptions.fromJson(options)
        new OVHAiEndpointsChatClient(api, opts, id).some
      }
      case "azure-openai" => {
        val resourceName = connection.select("resource_name").as[String]
        val deploymentId = connection.select("deployment_id").as[String]
        val apikey = connection.select("api_key").as[String]
        val api = new AzureOpenAiApi(resourceName, deploymentId, apikey, timeout.getOrElse(10.seconds), env = env)
        val opts = AzureOpenAiChatClientOptions.fromJson(options)
        new AzureOpenAiChatClient(api, opts, id).some
      }
      case "mistral" => {
        val api = new MistralAiApi(baseUrl.getOrElse(MistralAiApi.baseUrl), token, timeout.getOrElse(10.seconds), env = env)
        val opts = MistralAiChatClientOptions.fromJson(options)
        new MistralAiChatClient(api, opts, id).some
      }
      case "ollama" => {
        val api = new OllamaAiApi(baseUrl.getOrElse(OpenAiApi.baseUrl), token.some.filterNot(_ == "xxx"), timeout.getOrElse(10.seconds), env = env)
        val opts = OllamaAiChatClientOptions.fromJson(options)
        new OllamaAiChatClient(api, opts, id).some
      }
      case "cohere" => {
        val api = new CohereAiApi(baseUrl.getOrElse(CohereAiApi.baseUrl), token, timeout.getOrElse(10.seconds), env = env)
        val opts = CohereAiChatClientOptions.fromJson(options)
        new CohereAiChatClient(api, opts, id).some
      }
      case "anthropic" => {
        val api = new AnthropicApi(baseUrl.getOrElse(AnthropicApi.baseUrl), token, timeout.getOrElse(10.seconds), env = env)
        val opts = AnthropicChatClientOptions.fromJson(options)
        new AnthropicChatClient(api, opts, id).some
      }
      case "hugging-face" => {
        val api = new HuggingFaceApi(token, timeout.getOrElse(10.seconds), env = env)
        val opts = HuggingFaceChatClientOptions.fromJson(options)
        new HuggingFaceChatClient(api, opts, id).some
      }
      case "loadbalancer" => new LoadBalancerChatClient(this).some
      case _ => None
    }
    rawClient.map(c => ChatClientDecorators(this, c))
  }
}

object AiProvider {
  val format = new Format[AiProvider] {
    override def writes(o: AiProvider): JsValue             = o.location.jsonWithKey ++ Json.obj(
      "id"               -> o.id,
      "name"             -> o.name,
      "description"      -> o.description,
      "metadata"         -> o.metadata,
      "tags"             -> JsArray(o.tags.map(JsString.apply)),
      "provider"         -> o.provider,
      "connection"       -> o.connection,
      "options"          -> o.options,
      "provider_fallback" -> o.providerFallback.map(_.json).getOrElse(JsNull).asValue,
      "regex_validation" -> Json.obj(
        "allow" -> o.regexValidation.allow,
        "deny" -> o.regexValidation.deny,
      ),
      "llm_validation" -> Json.obj(
        "provider" -> o.llmValidation.provider,
        "prompt" -> o.llmValidation.prompt,
      ),
      "http_validation" -> Json.obj(
        "url" -> o.httpValidation.url,
        "headers" -> o.httpValidation.headers,
        "ttl" -> o.httpValidation.ttl.toMillis,
      ),
      "cache" -> Json.obj(
        "strategy" -> o.cache.strategy,
        "ttl" -> o.cache.ttl.toMillis,
        "score" -> o.cache.score
      )
    )
    override def reads(json: JsValue): JsResult[AiProvider] = Try {
      AiProvider(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = (json \ "id").as[String],
        name = (json \ "name").as[String],
        description = (json \ "description").as[String],
        metadata = (json \ "metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        tags = (json \ "tags").asOpt[Seq[String]].getOrElse(Seq.empty[String]),
        provider = (json \ "provider").as[String],
        connection = (json \ "connection").asOpt[JsObject].getOrElse(Json.obj()),
        options = (json \ "options").asOpt[JsObject].getOrElse(Json.obj()),
        providerFallback = (json \ "provider_fallback").asOpt[String],
        regexValidation = RegexValidationSettings(
          allow = (json \ "regex_validation" \ "allow").asOpt[Seq[String]].getOrElse(Seq.empty),
          deny = (json \ "regex_validation" \ "deny").asOpt[Seq[String]].getOrElse(Seq.empty),
        ),
        llmValidation = LlmValidationSettings(
          provider = (json \ "llm_validation" \ "provider").asOpt[String],
          prompt = (json \ "llm_validation" \ "prompt").asOpt[String],
        ),
        httpValidation = HttpValidationSettings(
          url = (json \ "http_validation" \ "url").asOpt[String],
          headers = (json \ "http_validation" \ "headers").asOpt[Map[String, String]].getOrElse(Map.empty),
          ttl = (json \ "http_validation" \ "ttl").asOpt[Long].map(v => FiniteDuration(v, TimeUnit.MILLISECONDS)).getOrElse(5.minutes),
        ),
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
        tmpl = (v, p) => {
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
                "timeout" -> 10000,
              ),
              options = OpenAiChatClientOptions().json
            ).json
            case Some("mistral") => AiProvider(
              id = IdGenerator.namedId("provider", env),
              name = "Mistral provider",
              description = "A Mistral LLM api provider",
              metadata = Map.empty,
              tags = Seq.empty,
              location = EntityLocation.default,
              provider = "openai",
              connection = Json.obj(
                "base_url" -> MistralAiApi.baseUrl,
                "token" -> "xxxxx",
                "timeout" -> 10000,
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
                "timeout" -> 10000,
              ),
              options = OVHAiEndpointsChatClientOptions().json
            ).json
            case Some("hugging-face") => AiProvider(
              id = IdGenerator.namedId("provider", env),
              name = "Hugging Face provider",
              description = "An Hugging Face LLM api provider",
              metadata = Map.empty,
              tags = Seq.empty,
              location = EntityLocation.default,
              provider = "hugging-face",
              connection = Json.obj(
                "token" -> "xxxxx",
                "timeout" -> 10000,
              ),
              options = HuggingFaceChatClientOptions().json
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
                "timeout" -> 10000,
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
