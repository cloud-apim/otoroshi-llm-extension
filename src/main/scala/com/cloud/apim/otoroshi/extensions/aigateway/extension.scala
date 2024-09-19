package otoroshi_plugins.com.cloud.apim.extensions.aigateway

import akka.stream.scaladsl.{Source, StreamConverters}
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.entities._
import com.cloud.apim.otoroshi.extensions.aigateway.providers._
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatMessage, ChatPrompt}
import otoroshi.env.Env
import otoroshi.models._
import otoroshi.next.extensions._
import otoroshi.utils.TypedMap
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.AiLlmProxy
import play.api.Logger
import play.api.libs.json.{JsError, JsObject, JsSuccess, Json}
import play.api.mvc.{RequestHeader, Result, Results}

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

class AiGatewayExtensionDatastores(env: Env, extensionId: AdminExtensionId) {
  val providersDatastore: AiProviderDataStore = new KvAiProviderDataStore(extensionId, env.datastores.redis, env)
  val promptTemplatesDatastore: PromptTemplateDataStore = new KvPromptTemplateDataStore(extensionId, env.datastores.redis, env)
  val promptContextDataStore: PromptContextDataStore = new KvPromptContextDataStore(extensionId, env.datastores.redis, env)
  val promptsDataStore: PromptDataStore = new KvPromptDataStore(extensionId, env.datastores.redis, env)
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
}

class AiExtension(val env: Env) extends AdminExtension {

  private lazy val datastores = new AiGatewayExtensionDatastores(env, id)

  lazy val states = new AiGatewayExtensionState(env)

  val logger = Logger("cloud-apim-llm-extension")

  override def id: AdminExtensionId = AdminExtensionId("cloud-apim.extensions.LlmExtension")

  override def name: String = "LLM Extension"

  override def description: Option[String] = "This extensions provides several plugins and connector to enhance your experience using LLM apis through Otoroshi".some

  override def enabled: Boolean = env.isDev || configuration.getOptional[Boolean]("enabled").getOrElse(false)

  override def start(): Unit = {
    logger.info("the 'LLM Extension' is enabled !")
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
        bodyJson.select("provider").asOpt[String] match {
          case None => Results.Ok(Json.obj("done" -> false, "error" -> "no provider")).vfuture
          case Some(providerId) => env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(providerId)) match {
            case None => Results.Ok(Json.obj("done" -> false, "error" -> "no extension")).vfuture
            case Some(old_provider) => {
              val _edited = bodyJson.select("edited").asOpt[JsObject].getOrElse(Json.obj())
              env.vaults.fillSecretsAsync(providerId, _edited.stringify).flatMap { editedRaw =>
                val edited = editedRaw.parseJson
                AiProvider.format.reads(edited) match {
                  case JsError(errors) => Results.Ok(Json.obj("done" -> false, "error" -> "bad provider shape")).vfuture
                  case JsSuccess(provider, _) => {
                    provider.getChatClient() match {
                      case None => Results.Ok(Json.obj("done" -> false, "error" -> "no client")).vfuture
                      case Some(client) => {
                        val role = bodyJson.select("role").asOpt[String].getOrElse("user")
                        val content = bodyJson.select("content").asOpt[String].getOrElse("no input")
                        client.call(ChatPrompt(Seq(ChatMessage(role, content))), TypedMap.empty).map {
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
        }
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
                    client.call(ChatPrompt(messages.map(m => ChatMessage(m.select("role").asOpt[String].getOrElse("system"), m.select("content").asOpt[String].getOrElse(""))) ++ Seq(ChatMessage(role, content))), TypedMap.empty).map {
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
                    val messages = messagesRaw.map(m => ChatMessage(m.select("role").asOpt[String].getOrElse("system"), m.select("content").asOpt[String].getOrElse("")))
                    client.call(ChatPrompt(messages), TypedMap.empty).map {
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
                val messages = Seq(ChatMessage("user", prompt))
                client.call(ChatPrompt(messages), TypedMap.empty).map {
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
            |    const SelectInput = dependencies.Components.Inputs.SelectInput;
            |    const BackOfficeServices = dependencies.BackOfficeServices;
            |    const BaseUrls = {
            |      openai: '${OpenAiApi.baseUrl}',
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
            |      mistral: ${MistralAiChatClientOptions().json.stringify},
            |      ollama: ${OllamaAiChatClientOptions().json.stringify},
            |      groq: ${GroqChatClientOptions().json.stringify},
            |      gemini: ${GeminiChatClientOptions().json.stringify},
            |      'azure-openai': ${AzureOpenAiChatClientOptions().json.stringify},
            |      'cohere': ${CohereAiChatClientOptions().json.stringify},
            |      ovh: ${OVHAiEndpointsChatClientOptions().json.stringify},
            |      hugging: ${HuggingFaceLangchainChatClientOptions().json.stringify},
            |      huggingface: ${HuggingfaceChatClientOptions().json.stringify},
            |    };
            |
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
    } yield {
      states.updateProviders(providers)
      states.updateTemplates(templates)
      states.updateContexts(contexts)
      states.updatePrompts(prompts)
      ()
    }
  }

  override def entities(): Seq[AdminExtensionEntity[EntityLocationSupport]] = {
    Seq(
      AdminExtensionEntity(AiProvider.resource(env, datastores, states)),
      AdminExtensionEntity(PromptContext.resource(env, datastores, states)),
      AdminExtensionEntity(Prompt.resource(env, datastores, states)),
      AdminExtensionEntity(PromptTemplate.resource(env, datastores, states)),
    )
  }
}
