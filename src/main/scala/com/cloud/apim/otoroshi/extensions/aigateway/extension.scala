package otoroshi_plugins.com.cloud.apim.extensions.aigateway

import akka.stream.scaladsl.{Source, StreamConverters}
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.entities._
import com.cloud.apim.otoroshi.extensions.aigateway.guardrails.LLMGuardrailsHardcodedItems
import com.cloud.apim.otoroshi.extensions.aigateway.providers._
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatMessage, ChatPrompt}
import com.github.blemale.scaffeine.Scaffeine
import otoroshi.env.Env
import otoroshi.models._
import otoroshi.next.extensions._
import otoroshi.utils.TypedMap
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.AiLlmProxy
import play.api.Logger
import play.api.libs.json.{JsArray, JsError, JsObject, JsSuccess, Json}
import play.api.mvc.{RequestHeader, Result, Results}

import scala.concurrent.Future
import scala.concurrent.duration.{DurationInt, FiniteDuration}

class AiGatewayExtensionDatastores(env: Env, extensionId: AdminExtensionId) {
  val providersDatastore: AiProviderDataStore = new KvAiProviderDataStore(extensionId, env.datastores.redis, env)
  val promptTemplatesDatastore: PromptTemplateDataStore = new KvPromptTemplateDataStore(extensionId, env.datastores.redis, env)
  val promptContextDataStore: PromptContextDataStore = new KvPromptContextDataStore(extensionId, env.datastores.redis, env)
  val promptsDataStore: PromptDataStore = new KvPromptDataStore(extensionId, env.datastores.redis, env)
  val toolFunctionDataStore: LlmToolFunctionDataStore = new KvLlmToolFunctionDataStore(extensionId, env.datastores.redis, env)
  val embeddingModelsDataStore: EmbeddingModelsDataStore = new KvEmbeddingModelsDataStore(extensionId, env.datastores.redis, env)
  val embeddingStoresDataStore: EmbeddingStoresDataStore = new KvEmbeddingStoresDataStore(extensionId, env.datastores.redis, env)
  val mcpConnectorsDatastore: McpConnectorsDataStore = new KvMcpConnectorsDataStore(extensionId, env.datastores.redis, env)
}

class AiGatewayExtensionState(env: Env) {

  private val _providers = new UnboundedTrieMap[String, AiProvider]()
  def provider(id: String): Option[AiProvider] = _providers.get(id)
  def allProviders(): Seq[AiProvider]          = _providers.values.toSeq
  def updateProviders(values: Seq[AiProvider]): Unit = {
    _providers.addAll(values.map(v => (v.id, v))).remAll(_providers.keySet.toSeq.diff(values.map(_.id)))
  }

  private val _templates = new UnboundedTrieMap[String, PromptTemplate]()
  def template(id: String): Option[PromptTemplate] = _templates.get(id)
  def allTemplates(): Seq[PromptTemplate]          = _templates.values.toSeq
  def updateTemplates(values: Seq[PromptTemplate]): Unit = {
    _templates.addAll(values.map(v => (v.id, v))).remAll(_templates.keySet.toSeq.diff(values.map(_.id)))
  }

  private val _contexts = new UnboundedTrieMap[String, PromptContext]()
  def context(id: String): Option[PromptContext] = _contexts.get(id)
  def allContexts(): Seq[PromptContext]          = _contexts.values.toSeq
  def updateContexts(values: Seq[PromptContext]): Unit = {
    _contexts.addAll(values.map(v => (v.id, v))).remAll(_contexts.keySet.toSeq.diff(values.map(_.id)))
  }

  private val _prompts = new UnboundedTrieMap[String, Prompt]()
  def prompt(id: String): Option[Prompt] = _prompts.get(id)
  def allPrompts(): Seq[Prompt]          = _prompts.values.toSeq
  def updatePrompts(values: Seq[Prompt]): Unit = {
    _prompts.addAll(values.map(v => (v.id, v))).remAll(_prompts.keySet.toSeq.diff(values.map(_.id)))
  }

  private val _toolFunctions = new UnboundedTrieMap[String, LlmToolFunction]()
  def toolFunction(id: String): Option[LlmToolFunction] = _toolFunctions.get(id)
  def allToolFunctions(): Seq[LlmToolFunction]          = _toolFunctions.values.toSeq
  def updateToolFunctions(values: Seq[LlmToolFunction]): Unit = {
    _toolFunctions.addAll(values.map(v => (v.id, v))).remAll(_toolFunctions.keySet.toSeq.diff(values.map(_.id)))
  }

  private val _embeddingModels = new UnboundedTrieMap[String, EmbeddingModel]()
  def embeddingModel(id: String): Option[EmbeddingModel] = _embeddingModels.get(id)
  def allEmbeddingModels(): Seq[EmbeddingModel]          = _embeddingModels.values.toSeq
  def updateEmbeddingModels(values: Seq[EmbeddingModel]): Unit = {
    _embeddingModels.addAll(values.map(v => (v.id, v))).remAll(_embeddingModels.keySet.toSeq.diff(values.map(_.id)))
  }

  private val _embeddingStores = new UnboundedTrieMap[String, EmbeddingStore]()
  def embeddingStore(id: String): Option[EmbeddingStore] = _embeddingStores.get(id)
  def allEmbeddingStores(): Seq[EmbeddingStore]          = _embeddingStores.values.toSeq
  def updateEmbeddingStores(values: Seq[EmbeddingStore]): Unit = {
    _embeddingStores.addAll(values.map(v => (v.id, v))).remAll(_embeddingStores.keySet.toSeq.diff(values.map(_.id)))
  }

  private val _mcpConnectors = new UnboundedTrieMap[String, McpConnector]()
  def mcpConnector(id: String): Option[McpConnector] = _mcpConnectors.get(id)
  def allMcpConnectors(): Seq[McpConnector]          = _mcpConnectors.values.toSeq
  def updateMcpConnectors(values: Seq[McpConnector]): Unit = {
    _mcpConnectors.addAll(values.map(v => (v.id, v))).remAll(_mcpConnectors.keySet.toSeq.diff(values.map(_.id)))
  }
}

object AiExtension {
  val logger = Logger("cloud-apim-llm-extension")
}

class AiExtension(val env: Env) extends AdminExtension {

  private lazy val datastores = new AiGatewayExtensionDatastores(env, id)

  lazy val states = new AiGatewayExtensionState(env)

  val logger = AiExtension.logger

  val modelsCache = Scaffeine()
    .maximumSize(100)
    .expireAfterWrite(1.hour)
    .build[String, Seq[String]]()

  override def id: AdminExtensionId = AdminExtensionId("cloud-apim.extensions.LlmExtension")

  override def name: String = "LLM Extension"

  override def description: Option[String] = "This extensions provides several plugins and connector to enhance your experience using LLM apis through Otoroshi".some

  override def enabled: Boolean = env.isDev || configuration.getOptional[Boolean]("enabled").getOrElse(false)

  override def start(): Unit = {
    logger.info("the 'LLM Extension' is enabled !")
    implicit val ev = env
    implicit val ec = env.otoroshiExecutionContext
    env.datastores.wasmPluginsDataStore.findById(LlmToolFunction.wasmPluginId).flatMap {
      case Some(_) => ().vfuture
      case None => {
        env.datastores.wasmPluginsDataStore.set(WasmPlugin(
          id = LlmToolFunction.wasmPluginId,
          name = "Otoroshi LLM Extension - tool call runtime",
          description = "This plugin provides the runtime for the wasm/http/whatever backed LLM tool calls",
          config = LlmToolFunction.wasmConfig
        )).map(_ => ())
      }
    }
  }

  override def stop(): Unit = {
  }

  override def frontendExtensions(): Seq[AdminExtensionFrontendExtension] = Seq(
    AdminExtensionFrontendExtension(
      path = "/extensions/assets/cloud-apim/extensions/ai-extension/extension.js"
    )
  )

  def getResourceCode(path: String): String = {
    implicit val ec = env.otoroshiExecutionContext
    implicit val mat = env.otoroshiMaterializer
    env.environment.resourceAsStream(path)
      .map(stream => StreamConverters.fromInputStream(() => stream).runFold(ByteString.empty)(_++_).awaitf(10.seconds).utf8String)
      .getOrElse(s"'resource ${path} not found !'")
  }

  lazy val promptPageCode = getResourceCode("cloudapim/extensions/ai/PromptPage.js")
  lazy val mcpConnectorsPageCode = getResourceCode("cloudapim/extensions/ai/McpConnectorsPage.js")
  lazy val embeddingModelsPageCode = getResourceCode("cloudapim/extensions/ai/EmbeddingModelsPage.js")
  lazy val embeddingStoresPageCode = getResourceCode("cloudapim/extensions/ai/EmbeddingStoresPage.js")
  lazy val toolFunctionPageCode = getResourceCode("cloudapim/extensions/ai/ToolFunctionsPage.js")
  lazy val promptTemplatesPageCode = getResourceCode("cloudapim/extensions/ai/PromptTemplatesPage.js")
  lazy val promptContextsPageCode = getResourceCode("cloudapim/extensions/ai/PromptContextsPage.js")
  lazy val aiProvidersPageCode = getResourceCode("cloudapim/extensions/ai/AiProvidersPage.js")
  lazy val imgCode = getResourceCode("cloudapim/extensions/ai/undraw_visionary_technology_re_jfp7.svg")

  def handleProviderTest(ctx: AdminExtensionRouterContext[AdminExtensionBackofficeAuthRoute], req: RequestHeader, user: Option[BackOfficeUser], body:  Option[Source[ByteString, _]]): Future[Result] = {
    implicit val ec = env.otoroshiExecutionContext
    implicit val mat = env.otoroshiMaterializer
    implicit val ev = env
    (body match {
      case None => Results.Ok(Json.obj("done" -> false, "error" -> "no body")).vfuture
      case Some(bodySource) => bodySource.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
        val bodyJson = bodyRaw.utf8String.parseJson
        // bodyJson.select("provider").asOpt[String] match {
        //   case None => Results.Ok(Json.obj("done" -> false, "error" -> "no provider in body")).vfuture
        //   case Some(providerId) => env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(providerId)) match {
        //     case None => Results.Ok(Json.obj("done" -> false, "error" -> "no provider")).vfuture
        //     case Some(old_provider) => {
              val _edited = bodyJson.select("edited").asOpt[JsObject].getOrElse(Json.obj())
              val providerId = _edited.select("id").asOpt[String].orElse(bodyJson.select("provider").asOpt[String]).getOrElse("new_llm_provider")
              env.vaults.fillSecretsAsync(providerId, _edited.stringify).flatMap { editedRaw =>
                val edited = editedRaw.parseJson
                AiProvider.format.reads(edited) match {
                  case JsError(errors) => Results.Ok(Json.obj("done" -> false, "error" -> "bad provider shape")).vfuture
                  case JsSuccess(provider, _) => {
                    provider.getChatClient() match {
                      case None => Results.Ok(Json.obj("done" -> false, "error" -> "no client")).vfuture
                      case Some(client) => {
                        //val role = bodyJson.select("role").asOpt[String].getOrElse("user")
                        //val content = bodyJson.select("content").asOpt[String].getOrElse("no input")
                        //val lastMessage = ChatMessage(role, content)
                        val historyMessages: Seq[ChatMessage] = bodyJson.select("history").asOpt[Seq[JsObject]].map(_.flatMap(o => ChatMessage.format.reads(o).asOpt)).getOrElse(Seq.empty)
                        val messages: Seq[ChatMessage] = historyMessages // ++ Seq(lastMessage)
                        client.call(ChatPrompt(messages), TypedMap.empty, Json.obj()).map {
                          case Left(err) => Results.Ok(Json.obj("done" -> false, "error" -> err))
                          case Right(response) => {
                            Results.Ok(Json.obj("done" -> true, "response" -> response.generations.map(_.json)))
                          }
                        }
                      }
                    }
                  }
                }
              }
          //    }
          //  }
          //}
        //}
      }
    }).recover {
      case e: Throwable => {
        Results.Ok(Json.obj("done" -> false, "error" -> e.getMessage))
      }
    }
  }

  def handleContextTest(ctx: AdminExtensionRouterContext[AdminExtensionBackofficeAuthRoute], req: RequestHeader, user: Option[BackOfficeUser], body:  Option[Source[ByteString, _]]): Future[Result] = {
    implicit val ec = env.otoroshiExecutionContext
    implicit val mat = env.otoroshiMaterializer
    implicit val ev = env
    (body match {
      case None => Results.Ok(Json.obj("done" -> false, "error" -> "no body")).vfuture
      case Some(bodySource) => bodySource.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
        val bodyJson = bodyRaw.utf8String.parseJson
        bodyJson.select("context").asOpt[Seq[JsObject]] match {
          case None => Results.Ok(Json.obj("done" -> false, "error" -> "no context")).vfuture
          case Some(messages) => {
            bodyJson.select("provider").asOpt[String] match {
              case None => Results.Ok(Json.obj("done" -> false, "error" -> "no provider")).vfuture
              case Some(providerId) => env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(providerId)) match {
                case None => Results.Ok(Json.obj("done" -> false, "error" -> "no provider found")).vfuture
                case Some(provider) => provider.getChatClient() match {
                  case None => Results.Ok(Json.obj("done" -> false, "error" -> "no client")).vfuture
                  case Some(client) => {
                    val role = bodyJson.select("role").asOpt[String].getOrElse("user")
                    val content = bodyJson.select("content").asOpt[String].getOrElse("no input")
                    val prefix = bodyJson.select("prefix").asOptBoolean
                    client.call(ChatPrompt(messages.map(m => ChatMessage(m.select("role").asOpt[String].getOrElse("system"), m.select("content").asOpt[String].getOrElse(""), m.select("prefix").asOptBoolean)) ++ Seq(ChatMessage(role, content, None))), TypedMap.empty, Json.obj()).map {
                      case Left(err) => Results.Ok(Json.obj("done" -> false, "error" -> err))
                      case Right(response) => {
                        Results.Ok(Json.obj("done" -> true, "response" -> response.generations.map(_.json)))
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }).recover {
      case e: Throwable => {
        Results.Ok(Json.obj("done" -> false, "error" -> e.getMessage))
      }
    }
  }

  def handleTemplateTest(ctx: AdminExtensionRouterContext[AdminExtensionBackofficeAuthRoute], req: RequestHeader, user: Option[BackOfficeUser], body:  Option[Source[ByteString, _]]): Future[Result] = {
    implicit val ec = env.otoroshiExecutionContext
    implicit val mat = env.otoroshiMaterializer
    implicit val ev = env
    (body match {
      case None => Results.Ok(Json.obj("done" -> false, "error" -> "no body")).vfuture
      case Some(bodySource) => bodySource.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
        val bodyJson = bodyRaw.utf8String.parseJson
        bodyJson.select("template").asOpt[String] match {
          case None => Results.Ok(Json.obj("done" -> false, "error" -> "no context")).vfuture
          case Some(template) => {
            bodyJson.select("provider").asOpt[String] match {
              case None => Results.Ok(Json.obj("done" -> false, "error" -> "no provider")).vfuture
              case Some(providerId) => env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(providerId)) match {
                case None => Results.Ok(Json.obj("done" -> false, "error" -> "no provider found")).vfuture
                case Some(provider) => provider.getChatClient() match {
                  case None => Results.Ok(Json.obj("done" -> false, "error" -> "no client")).vfuture
                  case Some(client) => {
                    val context = bodyJson.select("ctx").asOpt[JsObject].getOrElse(Json.obj())
                    val messagesRaw = AiLlmProxy.applyTemplate(template, context)
                    val messages = messagesRaw.map(m => ChatMessage(m.select("role").asOpt[String].getOrElse("system"), m.select("content").asOpt[String].getOrElse(""), m.select("prefix").asOptBoolean))
                    client.call(ChatPrompt(messages), TypedMap.empty, Json.obj()).map {
                      case Left(err) => Results.Ok(Json.obj("done" -> false, "error" -> err))
                      case Right(response) => {
                        Results.Ok(Json.obj("done" -> true, "response" -> response.generations.map(_.json)))
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }).recover {
      case e: Throwable => {
        Results.Ok(Json.obj("done" -> false, "error" -> e.getMessage))
      }
    }
  }

  def handlePromptTest(ctx: AdminExtensionRouterContext[AdminExtensionBackofficeAuthRoute], req: RequestHeader, user: Option[BackOfficeUser], body:  Option[Source[ByteString, _]]): Future[Result] = {
    implicit val ec = env.otoroshiExecutionContext
    implicit val mat = env.otoroshiMaterializer
    implicit val ev = env
    (body match {
      case None => Results.Ok(Json.obj("done" -> false, "error" -> "no body")).vfuture
      case Some(bodySource) => bodySource.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
        val bodyJson = bodyRaw.utf8String.parseJson
        bodyJson.select("provider").asOpt[String] match {
          case None => Results.Ok(Json.obj("done" -> false, "error" -> "no provider")).vfuture
          case Some(providerId) => env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(providerId)) match {
            case None => Results.Ok(Json.obj("done" -> false, "error" -> "no provider found")).vfuture
            case Some(provider) => provider.getChatClient() match {
              case None => Results.Ok(Json.obj("done" -> false, "error" -> "no client")).vfuture
              case Some(client) => {
                val prompt = bodyJson.select("prompt").asOpt[String].getOrElse("")
                val messages = Seq(ChatMessage("user", prompt, None))
                client.call(ChatPrompt(messages), TypedMap.empty, Json.obj()).map {
                  case Left(err) => Results.Ok(Json.obj("done" -> false, "error" -> err))
                  case Right(response) => {
                    Results.Ok(Json.obj("done" -> true, "response" -> response.generations.map(_.json)))
                  }
                }
              }
            }
          }
        }
      }
    }).recover {
      case e: Throwable => {
        Results.Ok(Json.obj("done" -> false, "error" -> e.getMessage))
      }
    }
  }

  def handleProviderModelsFetch(ctx: AdminExtensionRouterContext[AdminExtensionBackofficeAuthRoute], req: RequestHeader, user: Option[BackOfficeUser], body:  Option[Source[ByteString, _]]): Future[Result] = {
    implicit val ec = env.otoroshiExecutionContext
    implicit val mat = env.otoroshiMaterializer
    implicit val ev = env
    (body match {
      case None => Results.Ok(Json.obj("done" -> false, "error" -> "no body")).vfuture
      case Some(bodySource) => bodySource.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
        val bodyStr = bodyRaw.utf8String
        val bodyJson = bodyStr.parseJson
        bodyJson.select("id").asOptString match {
          case None => Results.Ok(Json.obj("done" -> false, "error" -> "no id")).vfuture
          case Some(providerId) => {
            env.vaults.fillSecretsAsync(providerId, bodyStr).flatMap { editedRaw =>
              val edited = editedRaw.parseJson
              AiProvider.format.reads(edited) match {
                case JsError(errors) => Results.Ok(Json.obj("done" -> false, "error" -> "bad provider format")).vfuture
                case JsSuccess(provider, _) => {
                  val token = provider.connection.select("token").asOptString.getOrElse("--")
                  val key = s"${provider.id}-${token}".sha256
                  val forceUpdate: Boolean = req.getQueryString("force").contains("true")
                  if (forceUpdate) {
                    logger.info(s"forcing models reload for ${provider.name} / ${provider.id}")
                  }
                  modelsCache.getIfPresent(key).filterNot(_ => forceUpdate) match {
                    case Some(models) => Results.Ok(Json.obj("done" -> true, "from_cache" -> true, "models" -> JsArray(models.map(_.json)))).vfuture
                    case None => {
                      provider.getChatClient() match {
                        case None => Results.Ok(Json.obj("done" -> false, "error" -> "no client")).vfuture
                        case Some(client) => {
                          client.listModels() map {
                            case Left(err) => Results.Ok(Json.obj("done" -> false, "error" -> "error fetching models", "error_details" -> err))
                            case Right(models) => {
                              modelsCache.put(key, models)
                              Results.Ok(Json.obj("done" -> true, "from_cache" -> false, "models" -> JsArray(models.map(_.json))))
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }).recover {
      case e: Throwable => {
        e.printStackTrace()
        Results.Ok(Json.obj("done" -> false, "error" -> e.getMessage))
      }
    }
  }

  override def backofficeAuthRoutes(): Seq[AdminExtensionBackofficeAuthRoute] = Seq(
    AdminExtensionBackofficeAuthRoute(
      method = "POST",
      path = "/extensions/cloud-apim/extensions/ai-extension/providers/_test",
      wantsBody = true,
      handle = handleProviderTest
    ),
    AdminExtensionBackofficeAuthRoute(
      method = "POST",
      path = "/extensions/cloud-apim/extensions/ai-extension/contexts/_test",
      wantsBody = true,
      handle = handleContextTest
    ),
    AdminExtensionBackofficeAuthRoute(
      method = "POST",
      path = "/extensions/cloud-apim/extensions/ai-extension/templates/_test",
      wantsBody = true,
      handle = handleTemplateTest
    ),
    AdminExtensionBackofficeAuthRoute(
      method = "POST",
      path = "/extensions/cloud-apim/extensions/ai-extension/prompts/_test",
      wantsBody = true,
      handle = handlePromptTest
    ),
    AdminExtensionBackofficeAuthRoute(
      method = "POST",
      path = "/extensions/cloud-apim/extensions/ai-extension/providers/_models",
      wantsBody = true,
      handle = handleProviderModelsFetch
    )
  )

  override def assets(): Seq[AdminExtensionAssetRoute] = Seq(
    AdminExtensionAssetRoute(
      path = "/extensions/assets/cloud-apim/extensions/ai-extension/undraw_visionary_technology_re_jfp7.svg",
      handle = (ctx: AdminExtensionRouterContext[AdminExtensionAssetRoute], req: RequestHeader) => {
        Results.Ok(imgCode).as("image/svg+xml").vfuture
      }
    ),
    AdminExtensionAssetRoute(
      path = "/extensions/assets/cloud-apim/extensions/ai-extension/extension.js",
      handle = (ctx: AdminExtensionRouterContext[AdminExtensionAssetRoute], req: RequestHeader) => {
        Results.Ok(
          s"""(function() {
            |  const extensionId = "${id.value}";
            |  Otoroshi.registerExtension(extensionId, false, (ctx) => {
            |
            |    const dependencies = ctx.dependencies;
            |
            |    const React     = dependencies.react;
            |    const _         = dependencies.lodash;
            |    const Component = React.Component;
            |    const uuid      = dependencies.uuid;
            |    const Table     = dependencies.Components.Inputs.Table;
            |    const Form      = dependencies.Components.Inputs.Form;
            |    const SelectInput = dependencies.Components.Inputs.SelectInput;
            |    const BackOfficeServices = dependencies.BackOfficeServices;
            |    const BaseUrls = {
            |      openai: '${OpenAiApi.baseUrl}',
            |      scaleway: '${ScalewayApi.baseUrl}',
            |      deepseek: '${DeepSeekApi.baseUrl}',
            |      xai: '${XAiApi.baseUrl}',
            |      mistral: '${MistralAiApi.baseUrl}',
            |      ollama: '${OllamaAiApi.baseUrl}',
            |      groq: '${GroqApi.baseUrl}',
            |      anthropic: '${AnthropicApi.baseUrl}',
            |      cohere: '${CohereAiApi.baseUrl}',
            |      ovh: '${OVHAiEndpointsApi.baseDomain}',
            |      hugging: ''
            |    };
            |    const ClientOptions = {
            |      anthropic: ${AnthropicChatClientOptions().json.stringify},
            |      openai: ${OpenAiChatClientOptions().json.stringify},
            |      scaleway: ${OpenAiChatClientOptions().copy(model = "llama-3.1-8b-instruct").json.stringify},
            |      deepseek: ${OpenAiChatClientOptions().copy(model = "deepseek-chat").json.stringify},
            |      xai: ${XAiChatClientOptions().json.stringify},
            |      mistral: ${MistralAiChatClientOptions().json.stringify},
            |      ollama: ${OllamaAiChatClientOptions().json.stringify},
            |      groq: ${GroqChatClientOptions().json.stringify},
            |      gemini: ${GeminiChatClientOptions().json.stringify},
            |      'azure-openai': ${AzureOpenAiChatClientOptions().json.stringify},
            |      'cohere': ${CohereAiChatClientOptions().json.stringify},
            |      ovh: ${OVHAiEndpointsChatClientOptions().json.stringify},
            |      huggingface: ${OpenAiChatClientOptions().copy(model = "google/gemma-2-2b-it".json.stringify).json.stringify},
            |    };
            |    const GuardrailsOptions = {
            |      possibleModerationCategories: ${JsArray(LLMGuardrailsHardcodedItems.possibleModerationCategories.map(_.json)).stringify},
            |      possiblePersonalInformations: ${JsArray(LLMGuardrailsHardcodedItems.possiblePersonalInformations.map(_.json)).stringify},
            |      possibleSecretLeakage: ${JsArray(LLMGuardrailsHardcodedItems.possibleSecretLeakage.map(_.json)).stringify},
            |    };
            |
            |    ${mcpConnectorsPageCode}
            |    ${embeddingModelsPageCode}
            |    ${embeddingStoresPageCode}
            |    ${toolFunctionPageCode}
            |    ${promptPageCode}
            |    ${promptTemplatesPageCode}
            |    ${promptContextsPageCode}
            |    ${aiProvidersPageCode}
            |
            |    return {
            |      id: extensionId,
            |      categories:[{
            |        title: 'AI',
            |        description: 'All the features provided the Cloud APIM AI extension',
            |        features: [
            |          {
            |            title: 'AI Providers',
            |            description: 'All your AI Providers',
            |            absoluteImg: '/extensions/assets/cloud-apim/extensions/ai-extension/undraw_visionary_technology_re_jfp7.svg',
            |            link: '/extensions/cloud-apim/ai-gateway/providers',
            |            display: () => true,
            |            icon: () => 'fa-brain',
            |          },
            |          {
            |            title: 'AI Prompt Templates',
            |            description: 'All your AI Prompt Templates',
            |            absoluteImg: '/extensions/assets/cloud-apim/extensions/ai-extension/undraw_visionary_technology_re_jfp7.svg',
            |            link: '/extensions/cloud-apim/ai-gateway/templates',
            |            display: () => true,
            |            icon: () => 'fa-brain',
            |          },
            |          {
            |            title: 'AI Prompt Contexts',
            |            description: 'All your AI Prompt Contexts',
            |            absoluteImg: '/extensions/assets/cloud-apim/extensions/ai-extension/undraw_visionary_technology_re_jfp7.svg',
            |            link: '/extensions/cloud-apim/ai-gateway/contexts',
            |            display: () => true,
            |            icon: () => 'fa-brain',
            |          },
            |          {
            |            title: 'AI Prompts',
            |            description: 'All your AI Prompts',
            |            absoluteImg: '/extensions/assets/cloud-apim/extensions/ai-extension/undraw_visionary_technology_re_jfp7.svg',
            |            link: '/extensions/cloud-apim/ai-gateway/prompts',
            |            display: () => true,
            |            icon: () => 'fa-brain',
            |          },
            |          {
            |            title: 'LLM Tool Function',
            |            description: 'All your LLM Tool functions',
            |            absoluteImg: '/extensions/assets/cloud-apim/extensions/ai-extension/undraw_visionary_technology_re_jfp7.svg',
            |            link: '/extensions/cloud-apim/ai-gateway/tool-functions',
            |            display: () => true,
            |            icon: () => 'fa-brain',
            |          },
            |          {
            |            title: 'Embedding models',
            |            description: 'All your embedding models',
            |            absoluteImg: '/extensions/assets/cloud-apim/extensions/ai-extension/undraw_visionary_technology_re_jfp7.svg',
            |            link: '/extensions/cloud-apim/ai-gateway/embedding-models',
            |            display: () => true,
            |            icon: () => 'fa-brain',
            |          },
            |          {
            |            title: 'Embedding stores',
            |            description: 'All your embedding stores',
            |            absoluteImg: '/extensions/assets/cloud-apim/extensions/ai-extension/undraw_visionary_technology_re_jfp7.svg',
            |            link: '/extensions/cloud-apim/ai-gateway/embedding-stores',
            |            display: () => true,
            |            icon: () => 'fa-brain',
            |          },
            |          {
            |            title: 'MCP Connectors',
            |            description: 'All your MCP Connectors',
            |            absoluteImg: '/extensions/assets/cloud-apim/extensions/ai-extension/undraw_visionary_technology_re_jfp7.svg',
            |            link: '/extensions/cloud-apim/ai-gateway/mcp-connectors',
            |            display: () => true,
            |            icon: () => 'fa-brain',
            |          }
            |        ]
            |      }],
            |      features: [
            |        {
            |          title: 'AI Providers',
            |          description: 'All your AI Providers',
            |          absoluteImg: '/extensions/assets/cloud-apim/extensions/ai-extension/undraw_visionary_technology_re_jfp7.svg',
            |          link: '/extensions/cloud-apim/ai-gateway/providers',
            |          display: () => true,
            |          icon: () => 'fa-brain',
            |        },
            |        {
            |          title: 'AI Prompt Templates',
            |          description: 'All your AI Prompt Templates',
            |          absoluteImg: '/extensions/assets/cloud-apim/extensions/ai-extension/undraw_visionary_technology_re_jfp7.svg',
            |          link: '/extensions/cloud-apim/ai-gateway/templates',
            |          display: () => true,
            |          icon: () => 'fa-brain',
            |        },
            |        {
            |          title: 'AI Prompt Contexts',
            |          description: 'All your AI Prompt Contexts',
            |          absoluteImg: '/extensions/assets/cloud-apim/extensions/ai-extension/undraw_visionary_technology_re_jfp7.svg',
            |          link: '/extensions/cloud-apim/ai-gateway/contexts',
            |          display: () => true,
            |          icon: () => 'fa-brain',
            |        },
            |        {
            |          title: 'AI Prompts',
            |          description: 'All your AI Prompts',
            |          absoluteImg: '/extensions/assets/cloud-apim/extensions/ai-extension/undraw_visionary_technology_re_jfp7.svg',
            |          link: '/extensions/cloud-apim/ai-gateway/prompts',
            |          display: () => true,
            |          icon: () => 'fa-brain',
            |        },
            |        {
            |          title: 'LLM Tool Functions',
            |          description: 'All your LLM Tool Functions',
            |          absoluteImg: '/extensions/assets/cloud-apim/extensions/ai-extension/undraw_visionary_technology_re_jfp7.svg',
            |          link: '/extensions/cloud-apim/ai-gateway/tool-functions',
            |          display: () => true,
            |          icon: () => 'fa-brain',
            |        },
            |        {
            |          title: 'Embedding models',
            |          description: 'All your embedding models',
            |          absoluteImg: '/extensions/assets/cloud-apim/extensions/ai-extension/undraw_visionary_technology_re_jfp7.svg',
            |          link: '/extensions/cloud-apim/ai-gateway/embedding-models',
            |          display: () => true,
            |          icon: () => 'fa-brain',
            |        },
            |        {
            |          title: 'Embedding stores',
            |          description: 'All your embedding stores',
            |          absoluteImg: '/extensions/assets/cloud-apim/extensions/ai-extension/undraw_visionary_technology_re_jfp7.svg',
            |          link: '/extensions/cloud-apim/ai-gateway/embedding-stores',
            |          display: () => true,
            |          icon: () => 'fa-brain',
            |        },
            |        {
            |          title: 'MCP Connectors',
            |          description: 'All your MCP Connexctors',
            |          absoluteImg: '/extensions/assets/cloud-apim/extensions/ai-extension/undraw_visionary_technology_re_jfp7.svg',
            |          link: '/extensions/cloud-apim/ai-gateway/mcp-connectors',
            |          display: () => true,
            |          icon: () => 'fa-brain',
            |        }
            |      ],
            |      sidebarItems: [
            |        {
            |          title: 'AI Providers',
            |          text: 'All your AI LLM Providers',
            |          path: 'extensions/cloud-apim/ai-gateway/providers',
            |          icon: 'brain'
            |        },
            |        {
            |          title: 'AI Prompt Templates',
            |          text: 'All your AI Prompt Templates',
            |          path: 'extensions/cloud-apim/ai-gateway/templates',
            |          icon: 'brain'
            |        },
            |        {
            |          title: 'AI Prompt Contexts',
            |          text: 'All your AI Prompt Contexts',
            |          path: 'extensions/cloud-apim/ai-gateway/contexts',
            |          icon: 'brain'
            |        },
            |        {
            |          title: 'AI Prompts',
            |          text: 'All your AI Prompts',
            |          path: 'extensions/cloud-apim/ai-gateway/prompts',
            |          icon: 'brain'
            |        },
            |        {
            |          title: 'LLM Tool Function',
            |          text: 'All your LLM Tool Functions',
            |          path: 'extensions/cloud-apim/ai-gateway/tool-functions',
            |          icon: 'brain'
            |        },
            |        {
            |          title: 'Embedding models',
            |          text: 'All your embedding models',
            |          path: 'extensions/cloud-apim/ai-gateway/embedding-models',
            |          icon: 'brain'
            |        },
            |        {
            |          title: 'Embedding stores',
            |          text: 'All your embedding stores',
            |          path: 'extensions/cloud-apim/ai-gateway/embedding-stores',
            |          icon: 'brain'
            |        },
            |        {
            |          title: 'MCP Connectors',
            |          text: 'All your MCP Connectors',
            |          path: 'extensions/cloud-apim/ai-gateway/mcp-connectors',
            |          icon: 'brain'
            |        }
            |      ],
            |      searchItems: [
            |        {
            |          action: () => {
            |            window.location.href = `/bo/dashboard/extensions/cloud-apim/ai-gateway/providers`
            |          },
            |          env: React.createElement('span', { className: "fas fa-brain" }, null),
            |          label: 'AI LLM providers',
            |          value: 'aillmproviders',
            |        },
            |        {
            |          action: () => {
            |            window.location.href = `/bo/dashboard/extensions/cloud-apim/ai-gateway/templates`
            |          },
            |          env: React.createElement('span', { className: "fas fa-brain" }, null),
            |          label: 'AI Prompt Templates',
            |          value: 'prompttemplates',
            |        },
            |        {
            |          action: () => {
            |            window.location.href = `/bo/dashboard/extensions/cloud-apim/ai-gateway/contexts`
            |          },
            |          env: React.createElement('span', { className: "fas fa-brain" }, null),
            |          label: 'AI Prompt Contexts',
            |          value: 'promptcontexts',
            |        },
            |        {
            |          action: () => {
            |            window.location.href = `/bo/dashboard/extensions/cloud-apim/ai-gateway/prompts`
            |          },
            |          env: React.createElement('span', { className: "fas fa-brain" }, null),
            |          label: 'AI Prompts',
            |          value: 'prompts',
            |        },
            |        {
            |          action: () => {
            |            window.location.href = `/bo/dashboard/extensions/cloud-apim/ai-gateway/tool-functions`
            |          },
            |          env: React.createElement('span', { className: "fas fa-brain" }, null),
            |          label: 'LLM Tool Functions',
            |          value: 'tool-functions',
            |        },
            |        {
            |          action: () => {
            |            window.location.href = `/bo/dashboard/extensions/cloud-apim/ai-gateway/embedding-models`
            |          },
            |          env: React.createElement('span', { className: "fas fa-brain" }, null),
            |          label: 'Embedding models',
            |          value: 'embedding-models',
            |        },
            |        {
            |          action: () => {
            |            window.location.href = `/bo/dashboard/extensions/cloud-apim/ai-gateway/embedding-stores`
            |          },
            |          env: React.createElement('span', { className: "fas fa-brain" }, null),
            |          label: 'Embedding stores',
            |          value: 'embedding-stores',
            |        },
            |        {
            |          action: () => {
            |            window.location.href = `/bo/dashboard/extensions/cloud-apim/ai-gateway/mcp-connectors`
            |          },
            |          env: React.createElement('span', { className: "fas fa-brain" }, null),
            |          label: 'MCP Connectors',
            |          value: 'mcp-connectors',
            |        }
            |      ],
            |      routes: [
            |        {
            |          path: '/extensions/cloud-apim/ai-gateway/providers/:taction/:titem',
            |          component: (props) => {
            |            return React.createElement(AiProvidersPage, props, null)
            |          }
            |        },
            |        {
            |          path: '/extensions/cloud-apim/ai-gateway/providers/:taction',
            |          component: (props) => {
            |            return React.createElement(AiProvidersPage, props, null)
            |          }
            |        },
            |        {
            |          path: '/extensions/cloud-apim/ai-gateway/providers',
            |          component: (props) => {
            |            return React.createElement(AiProvidersPage, props, null)
            |          }
            |        },
            |        {
            |          path: '/extensions/cloud-apim/ai-gateway/templates/:taction/:titem',
            |          component: (props) => {
            |            return React.createElement(PromptTemplatesPage, props, null)
            |          }
            |        },
            |        {
            |          path: '/extensions/cloud-apim/ai-gateway/templates/:taction',
            |          component: (props) => {
            |            return React.createElement(PromptTemplatesPage, props, null)
            |          }
            |        },
            |        {
            |          path: '/extensions/cloud-apim/ai-gateway/templates',
            |          component: (props) => {
            |            return React.createElement(PromptTemplatesPage, props, null)
            |          }
            |        },
            |        {
            |          path: '/extensions/cloud-apim/ai-gateway/contexts/:taction/:titem',
            |          component: (props) => {
            |            return React.createElement(PromptContextsPage, props, null)
            |          }
            |        },
            |        {
            |          path: '/extensions/cloud-apim/ai-gateway/contexts/:taction',
            |          component: (props) => {
            |            return React.createElement(PromptContextsPage, props, null)
            |          }
            |        },
            |        {
            |          path: '/extensions/cloud-apim/ai-gateway/contexts',
            |          component: (props) => {
            |            return React.createElement(PromptContextsPage, props, null)
            |          }
            |        },
            |        {
            |          path: '/extensions/cloud-apim/ai-gateway/prompts/:taction/:titem',
            |          component: (props) => {
            |            return React.createElement(PromptsPage, props, null)
            |          }
            |        },
            |        {
            |          path: '/extensions/cloud-apim/ai-gateway/prompts/:taction',
            |          component: (props) => {
            |            return React.createElement(PromptsPage, props, null)
            |          }
            |        },
            |        {
            |          path: '/extensions/cloud-apim/ai-gateway/prompts',
            |          component: (props) => {
            |            return React.createElement(PromptsPage, props, null)
            |          }
            |        },
            |        {
            |          path: '/extensions/cloud-apim/ai-gateway/tool-functions/:taction/:titem',
            |          component: (props) => {
            |            return React.createElement(ToolFunctionsPage, props, null)
            |          }
            |        },
            |        {
            |          path: '/extensions/cloud-apim/ai-gateway/tool-functions/:taction',
            |          component: (props) => {
            |            return React.createElement(ToolFunctionsPage, props, null)
            |          }
            |        },
            |        {
            |          path: '/extensions/cloud-apim/ai-gateway/tool-functions',
            |          component: (props) => {
            |            return React.createElement(ToolFunctionsPage, props, null)
            |          }
            |        },
            |        {
            |          path: '/extensions/cloud-apim/ai-gateway/embedding-models/:taction/:titem',
            |          component: (props) => {
            |            return React.createElement(EmbeddingModelsPage, props, null)
            |          }
            |        },
            |        {
            |          path: '/extensions/cloud-apim/ai-gateway/embedding-models/:taction',
            |          component: (props) => {
            |            return React.createElement(EmbeddingModelsPage, props, null)
            |          }
            |        },
            |        {
            |          path: '/extensions/cloud-apim/ai-gateway/embedding-models',
            |          component: (props) => {
            |            return React.createElement(EmbeddingModelsPage, props, null)
            |          }
            |        },
            |        {
            |          path: '/extensions/cloud-apim/ai-gateway/embedding-stores/:taction/:titem',
            |          component: (props) => {
            |            return React.createElement(EmbeddingStoresPage, props, null)
            |          }
            |        },
            |        {
            |          path: '/extensions/cloud-apim/ai-gateway/embedding-stores/:taction',
            |          component: (props) => {
            |            return React.createElement(EmbeddingStoresPage, props, null)
            |          }
            |        },
            |        {
            |          path: '/extensions/cloud-apim/ai-gateway/embedding-stores',
            |          component: (props) => {
            |            return React.createElement(EmbeddingStoresPage, props, null)
            |          }
            |        },
            |        {
            |          path: '/extensions/cloud-apim/ai-gateway/mcp-connectors/:taction/:titem',
            |          component: (props) => {
            |            return React.createElement(McpConnectorsPage, props, null)
            |          }
            |        },
            |        {
            |          path: '/extensions/cloud-apim/ai-gateway/mcp-connectors/:taction',
            |          component: (props) => {
            |            return React.createElement(McpConnectorsPage, props, null)
            |          }
            |        },
            |        {
            |          path: '/extensions/cloud-apim/ai-gateway/mcp-connectors',
            |          component: (props) => {
            |            return React.createElement(McpConnectorsPage, props, null)
            |          }
            |        }
            |      ]
            |    }
            |  });
            |})();
            |""".stripMargin).as("application/javascript").vfuture
      }
    )
  )

  override def syncStates(): Future[Unit] = {
    implicit val ec = env.otoroshiExecutionContext
    implicit val ev = env
    for {
      providers <- datastores.providersDatastore.findAllAndFillSecrets()
      templates <- datastores.promptTemplatesDatastore.findAllAndFillSecrets()
      contexts <- datastores.promptContextDataStore.findAllAndFillSecrets()
      prompts <- datastores.promptsDataStore.findAllAndFillSecrets()
      toolFunctions <- datastores.toolFunctionDataStore.findAllAndFillSecrets()
      embeddingModels <- datastores.embeddingModelsDataStore.findAllAndFillSecrets()
      embeddingStores <- datastores.embeddingStoresDataStore.findAllAndFillSecrets()
      mcpConnectors <- datastores.mcpConnectorsDatastore.findAllAndFillSecrets()
    } yield {
      states.updateProviders(providers)
      states.updateTemplates(templates)
      states.updateContexts(contexts)
      states.updatePrompts(prompts)
      states.updateToolFunctions(toolFunctions)
      states.updateEmbeddingModels(embeddingModels)
      states.updateEmbeddingStores(embeddingStores)
      states.updateMcpConnectors(mcpConnectors)
      Future {
        McpSupport.restartConnectorsIfNeeded()
        McpSupport.stopConnectorsIfNeeded()
      }
      ()
    }
  }

  override def entities(): Seq[AdminExtensionEntity[EntityLocationSupport]] = {
    Seq(
      AdminExtensionEntity(AiProvider.resource(env, datastores, states)),
      AdminExtensionEntity(PromptContext.resource(env, datastores, states)),
      AdminExtensionEntity(Prompt.resource(env, datastores, states)),
      AdminExtensionEntity(PromptTemplate.resource(env, datastores, states)),
      AdminExtensionEntity(LlmToolFunction.resource(env, datastores, states)),
      AdminExtensionEntity(EmbeddingModel.resource(env, datastores, states)),
      AdminExtensionEntity(EmbeddingStore.resource(env, datastores, states)),
      AdminExtensionEntity(McpConnector.resource(env, datastores, states)),
    )
  }
}
