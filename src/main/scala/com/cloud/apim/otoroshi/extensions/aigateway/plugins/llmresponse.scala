package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import akka.stream.Materializer
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatMessage, ChatPrompt, InputChatMessage}
import otoroshi.el.GlobalExpressionLanguage
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json._
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class LlmResponseConfig(ref: String = "", _prompt: String = "", promptRef: Option[String] = None, contextRef: Option[String] = None) extends NgPluginConfig {
  def json: JsValue = LlmResponseConfig.format.writes(this)
  def promptBeforeEl(implicit env: Env): String = promptRef match {
    case None => _prompt
    case Some(ref) => env.adminExtensions.extension[AiExtension] match {
      case None => _prompt
      case Some(ext) => ext.states.prompt(ref) match {
        case None => _prompt
        case Some(prompt) => prompt.prompt
      }
    }
  }
  def preChatMessages(implicit env: Env): Seq[InputChatMessage] = {
    contextRef match {
      case None => Seq.empty
      case Some(ref) => env.adminExtensions.extension[AiExtension] match {
        case None => Seq.empty
        case Some(ext) => ext.states.context(ref) match {
          case None => Seq.empty
          case Some(context) => context.preChatMessages()
        }
      }
    }
  }
  def postChatMessages(implicit env: Env): Seq[InputChatMessage] = {
    contextRef match {
      case None => Seq.empty
      case Some(ref) => env.adminExtensions.extension[AiExtension] match {
        case None => Seq.empty
        case Some(ext) => ext.states.context(ref) match {
          case None => Seq.empty
          case Some(context) => context.postChatMessages()
        }
      }
    }
  }
}

object LlmResponseConfig {
  val default = LlmResponseConfig()
  val format = new Format[LlmResponseConfig] {
    override def reads(json: JsValue): JsResult[LlmResponseConfig] = Try {
      LlmResponseConfig(
        ref = json.select("ref").asOpt[String].getOrElse(""),
        _prompt = json.select("prompt").asOpt[String].getOrElse(""),
        promptRef = json.select("prompt_ref").asOpt[String].filterNot(_.isBlank),
        contextRef = json.select("context_ref").asOpt[String].filterNot(_.isBlank)
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }
    override def writes(o: LlmResponseConfig): JsValue = Json.obj(
      "ref" -> o.ref,
      "prompt" -> o._prompt,
      "prompt_ref" -> o.promptRef.map(_.json).getOrElse(JsNull).asValue,
      "context_ref" -> o.contextRef.map(_.json).getOrElse(JsNull).asValue
    )
  }
  val configFlow: Seq[String] = Seq("ref", "prompt", "prompt_ref", "context_ref")
  val configSchema: Option[JsObject] = Some(Json.obj(
    "prompt" -> Json.obj(
      "type" -> "text",
      "label" -> "Modifier prompt"
    ),
    "prompt_ref" -> Json.obj(
      "type" -> "select",
      "label" -> s"Generator prompt ref.",
      "props" -> Json.obj(
        "isClearable" -> true,
        "optionsFrom" -> s"/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/prompts",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      ),
    ),
    "context_ref" -> Json.obj(
      "type" -> "select",
      "label" -> s"Generator context ref.",
      "props" -> Json.obj(
        "isClearable" -> true,
        "optionsFrom" -> s"/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/prompt-contexts",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      ),
    ),
    "ref" -> Json.obj(
      "type" -> "select",
      "label" -> s"AI LLM Provider",
      "props" -> Json.obj(
        "optionsFrom" -> s"/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/providers",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      ),
    )
  ))
}

class LlmResponseEndpoint extends NgBackendCall {

  override def name: String = "Cloud APIM - LLM Response endpoint"
  override def description: Option[String] = "Provides endpoints that returns LLM raw response from a predefined prompt with EL completion".some
  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))

  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)
  override def defaultConfigObject: Option[NgPluginConfig] = Some(LlmResponseConfig.default)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = LlmResponseConfig.configFlow
  override def configSchema: Option[JsObject] = LlmResponseConfig.configSchema

  override def useDelegates: Boolean = false

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'LLM Response endpoint' plugin is available !")
    }
    ().vfuture
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(LlmResponseConfig.format).getOrElse(LlmResponseConfig.default)
    env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(config.ref)) match {
      case None => Left(NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "provider not found")))).vfuture // TODO: rewrite error
      case Some(provider) => provider.getChatClient() match {
        case Some(client) => {
          def applyEl(value: String): String = {
            GlobalExpressionLanguage.apply(
              value = value,
              req = ctx.rawRequest.some,
              service = ctx.route.legacy.some,
              route = ctx.route.some,
              apiKey = ctx.apikey,
              user = ctx.user,
              context = Map.empty,
              attrs = ctx.attrs,
              env = env
            )
          }
          val prompt = applyEl(config.promptBeforeEl)
          client.call(ChatPrompt(config.preChatMessages.map(_.transformContent(applyEl)) ++ Seq(ChatMessage.input("user", prompt, None)) ++ config.postChatMessages.map(_.transformContent(applyEl))), ctx.attrs, Json.obj()).map {
            case Left(err) => Left(NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> err)))) // TODO: rewrite error
            case Right(resp) => {
              Right(BackendCallResponse(NgPluginHttpResponse.fromResult(
                Results.Ok(Json.obj("generations" -> JsArray(resp.generations.map(_.json))))
              ), None))
            }
          }
        }
        case None => Left(NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "client not found")))).vfuture // TODO: rewrite error
      }
    }
  }
}