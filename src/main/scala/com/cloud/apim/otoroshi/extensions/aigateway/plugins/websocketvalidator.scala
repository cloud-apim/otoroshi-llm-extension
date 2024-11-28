package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import com.cloud.apim.otoroshi.extensions.aigateway.plugins.AiPromptRequestConfig
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatMessage, ChatPrompt}
import otoroshi.env.Env
import otoroshi.next.plugins.RejectStrategy
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.http.websocket.{CloseCodes, TextMessage}
import play.api.libs.json.{JsObject, Json}

import java.util.regex.Pattern.CASE_INSENSITIVE
import java.util.regex.{MatchResult, Matcher, Pattern}
import scala.concurrent.{ExecutionContext, Future}

class AiWebsocketMessageValidator extends NgWebsocketValidatorPlugin {

  override def steps: Seq[NgStep] = Seq(NgStep.ValidateAccess)
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"), NgPluginCategory.Websocket)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = AiPromptRequestConfig.configFlow
  override def configSchema: Option[JsObject] = AiPromptRequestConfig.configSchema("Validator")
  override def name: String = "Cloud APIM - LLM Websocket message validator"
  override def description: Option[String] = """This plugin validates websocket messages using an LLM prompt.""".stripMargin.some
  override def defaultConfigObject: Option[NgPluginConfig] = AiPromptRequestConfig().some
  override def core: Boolean = false
  override def onResponseFlow: Boolean = false
  override def onRequestFlow: Boolean  = true
  override def rejectStrategy(ctx: NgWebsocketPluginContext): RejectStrategy = RejectStrategy.Drop

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'LLM Websocket message validator' plugin is available !")
    }
    ().vfuture
  }

  override def onRequestMessage(ctx: NgWebsocketPluginContext, message: WebsocketMessage)(implicit env: Env, ec: ExecutionContext): Future[Either[NgWebsocketError, WebsocketMessage]] = {
    val config = ctx.cachedConfig(internalName)(AiPromptRequestConfig.format).getOrElse(AiPromptRequestConfig())
    implicit val mat = env.otoroshiMaterializer
    if (message.isText) {
      message.str().flatMap { str =>
        env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(config.ref)) match {
          case None => Left(NgWebsocketError(CloseCodes.ProtocolError, "provider not found")).vfuture
          case Some(provider) => provider.getChatClient() match {
            case None => Left(NgWebsocketError(CloseCodes.ProtocolError, "client not found")).vfuture
            case Some(client) => {
              client.call(ChatPrompt(config.preChatMessages ++ Seq(
                ChatMessage("system", config.prompt),
                ChatMessage("user", str),
              ) ++ config.postChatMessages), ctx.attrs, Json.obj()).map {
                case Left(err) => Left(NgWebsocketError(CloseCodes.UnexpectedCondition, s"error: ${err.stringify}"))
                case Right(resp) => {
                  val content = resp.generations.head.message.content
                  if (content.trim.isBlank || content.trim.isEmpty) {
                    message.right
                  } else {
                    val (res: Boolean, reason: Option[String]) = config.extractor match {
                      case None if content == "true" => (true, None)
                      case None if content == "false" => (false, None)
                      case None => Json.parse(content).applyOn { jsValue =>
                        (jsValue.select("result").asOpt[Boolean].getOrElse(false), jsValue.select("reason").asOpt[String])
                      }
                      case Some(regex) => {
                        val pattern: Pattern = Pattern.compile(regex, CASE_INSENSITIVE)
                        val matcher: Matcher = pattern.matcher(resp.generations.head.message.content)
                        if (matcher.find()) {
                          val matchResult: MatchResult = matcher.toMatchResult
                          matchResult.group() match {
                            case res if res == "true" => (true, None)
                            case res if res == "false" => (false, None)
                            case res => Json.parse(res).applyOn { jsValue =>
                              (jsValue.select("result").asOpt[Boolean].getOrElse(false), jsValue.select("reason").asOpt[String])
                            }
                          }
                        } else {
                          (false, None)
                        }
                      }
                    }
                    if (res) {
                      message.right
                    } else {
                      reason match {
                        case None => Left(NgWebsocketError(CloseCodes.Unacceptable, s"validation did not pass"))
                        case Some(str) => WebsocketMessage.PlayMessage(TextMessage(str)).right
                      }

                    }
                  }
                }
              }
            }
          }
        }
      }
    } else {
      message.rightf
    }
  }
}
