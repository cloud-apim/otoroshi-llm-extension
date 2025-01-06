package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import com.cloud.apim.otoroshi.extensions.aigateway.plugins.AiPromptRequestConfig
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatMessage, ChatPrompt}
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.{JsObject, Json}
import play.api.mvc.Results

import java.util.regex.Pattern.CASE_INSENSITIVE
import java.util.regex.{MatchResult, Matcher, Pattern}
import scala.concurrent.{ExecutionContext, Future}

class AiContextValidator extends NgAccessValidator {

  override def steps: Seq[NgStep]                = Seq(NgStep.ValidateAccess)
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"), NgPluginCategory.AccessControl)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = AiPromptRequestConfig.configFlow
  override def configSchema: Option[JsObject] = AiPromptRequestConfig.configSchema("Validator")
  override def name: String = "Cloud APIM - LLM Context validator"
  override def description: Option[String] = """This plugin validates the current context using an LLM prompt.""".stripMargin.some
  override def defaultConfigObject: Option[NgPluginConfig] = AiPromptRequestConfig().some

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'LLM Context validator' plugin is available !")
    }
    ().vfuture
  }

  private def validate(ctx: NgAccessContext, config: AiPromptRequestConfig)(implicit env: Env, ec: ExecutionContext): Future[Boolean] = {
    env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(config.ref)) match {
      case None => false.vfuture // TODO: log it
      case Some(provider) => provider.getChatClient() match {
        case None => false.vfuture // TODO: log it
        case Some(client) => {
          client.call(ChatPrompt(config.preChatMessages ++ Seq(
            ChatMessage("system", config.prompt, None),
            ChatMessage("user", ctx.json.stringify, None),
          ) ++ config.postChatMessages), ctx.attrs, Json.obj()).map {
            case Left(err) => false // TODO: log it
            case Right(resp) => {
              val content = resp.generations.head.message.content
              config.extractor match {
                case None if content == "true" => true
                case None if content == "false" => false
                case None => Json.parse(content).select("result").asOpt[Boolean].getOrElse(false)
                case Some(regex) => {
                  val pattern: Pattern = Pattern.compile(regex, CASE_INSENSITIVE)
                  val matcher: Matcher = pattern.matcher(resp.generations.head.message.content)
                  if (matcher.find()) {
                    val matchResult: MatchResult = matcher.toMatchResult
                    matchResult.group() match {
                      case res if res == "true" => true
                      case res if res == "false" => false
                      case res => Json.parse(res).select("result").asOpt[Boolean].getOrElse(false)
                    }
                  } else {
                    false // TODO: log it
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config = ctx.cachedConfig(internalName)(AiPromptRequestConfig.format).getOrElse(AiPromptRequestConfig())
    validate(ctx, config) flatMap {
      case true => NgAccess.NgAllowed.vfuture
      case false => Errors
        .craftResponseResult(
          "forbidden",
          Results.Forbidden,
          ctx.request,
          None,
          None,
          duration = ctx.report.getDurationNow(),
          overhead = ctx.report.getOverheadInNow(),
          attrs = ctx.attrs,
          maybeRoute = ctx.route.some
        )
        .map(r => NgAccess.NgDenied(r))
    }
  }
}
