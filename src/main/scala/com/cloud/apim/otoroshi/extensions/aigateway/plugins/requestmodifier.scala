package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.plugins.AiPromptRequestConfig
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatMessage, ChatPrompt}
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.{JsObject, Json}
import play.api.mvc.{Result, Results}

import java.util.regex.Pattern.CASE_INSENSITIVE
import java.util.regex.{MatchResult, Matcher, Pattern}
import scala.concurrent.{ExecutionContext, Future, Promise}

class AiRequestBodyModifier extends NgRequestTransformer {

  override def name: String = "Cloud APIM - LLM Request body modifier"
  override def description: Option[String] = "Can modify request body based on a prompt".some
  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))

  override def steps: Seq[NgStep] = Seq(NgStep.TransformRequest)
  override def defaultConfigObject: Option[NgPluginConfig] = Some(AiPromptRequestConfig.default)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = AiPromptRequestConfig.configFlow
  override def configSchema: Option[JsObject] = AiPromptRequestConfig.configSchema("Modifier")

  override def transformsError: Boolean = false
  override def transformsResponse: Boolean = false
  override def transformsRequest: Boolean = true

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'LLM Request body modifier' plugin is available !")
    }
    ().vfuture
  }

  override def transformRequest(ctx: NgTransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val config = ctx.cachedConfig(internalName)(AiPromptRequestConfig.format).getOrElse(AiPromptRequestConfig.default)
    if (ctx.otoroshiRequest.hasBody) {
      env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(config.ref)) match {
        case None => Left(Results.InternalServerError(Json.obj("error" -> "provider not found"))).vfuture // TODO: rewrite error
        case Some(provider) => provider.getChatClient() match {
          case Some(client) => {
            val promise = Promise[Source[ByteString, NotUsed]]()
            ctx.otoroshiRequest.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
              client.call(ChatPrompt(config.preChatMessages ++ Seq(
                ChatMessage("system", config.prompt),
                ChatMessage("user", bodyRaw.utf8String),
              ) ++ config.postChatMessages), ctx.attrs).map {
                case Left(err) => promise.trySuccess(Source.single(err.stringify.byteString))
                case Right(resp) => {
                  config.extractor match {
                    case None => promise.trySuccess(resp.generations.head.message.content.byteString.chunks(32 * 1024))
                    case Some(regex) => {
                      val pattern: Pattern = Pattern.compile(regex, CASE_INSENSITIVE)
                      val matcher: Matcher = pattern.matcher(resp.generations.head.message.content)
                      if (matcher.find()) {
                        val matchResult: MatchResult = matcher.toMatchResult
                        val expression: String       = matchResult.group()
                        promise.trySuccess(expression.byteString.chunks(32 * 1024))
                      } else {
                        promise.trySuccess(resp.generations.head.message.content.byteString.chunks(32 * 1024))
                      }
                    }
                  }
                }
              }
            }
            ctx.otoroshiRequest.copy(
              headers = ctx.otoroshiRequest.headers - "Content-Length" - "content-length" ++ Map("Transfer-Encoding" -> "chunked"),
              body = Source.futureSource(promise.future)
            ).rightf
          }
          case None => Left(Results.InternalServerError(Json.obj("error" -> "client not found"))).vfuture // TODO: rewrite error
        }
      }
    } else {
      ctx.otoroshiRequest.rightf
    }
  }
}
