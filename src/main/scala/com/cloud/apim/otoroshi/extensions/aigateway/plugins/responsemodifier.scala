package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import akka.stream.Materializer
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatMessage, ChatPrompt, InputChatMessage}
import otoroshi.env.Env
import otoroshi.next.plugins.BodyHelper
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json._
import play.api.mvc.{Result, Results}

import java.util.regex.Pattern.CASE_INSENSITIVE
import java.util.regex.{MatchResult, Matcher, Pattern}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class AiResponseBodyModifierConfig(ref: String = "", _prompt: String = "", promptRef: Option[String] = None, contextRef: Option[String] = None, extractor: Option[String] = None, isResponse: Boolean = false) extends NgPluginConfig {
  def json: JsValue = AiResponseBodyModifierConfig.format.writes(this)
  def prompt(implicit env: Env): String = promptRef match {
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

object AiResponseBodyModifierConfig {
  val default = AiResponseBodyModifierConfig()
  val format = new Format[AiResponseBodyModifierConfig] {
    override def reads(json: JsValue): JsResult[AiResponseBodyModifierConfig] = Try {
      AiResponseBodyModifierConfig(
        ref = json.select("ref").asOpt[String].getOrElse(""),
        _prompt = json.select("prompt").asOpt[String].getOrElse(""),
        promptRef = json.select("prompt_ref").asOpt[String].filterNot(_.isBlank),
        contextRef = json.select("context_ref").asOpt[String].filterNot(_.isBlank),
        extractor = json.select("extractor").asOpt[String],
        isResponse = json.select("is_response").asOpt[Boolean].getOrElse(false),
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }
    override def writes(o: AiResponseBodyModifierConfig): JsValue = Json.obj(
      "ref" -> o.ref,
      "prompt" -> o._prompt,
      "is_response" -> o.isResponse,
      "prompt_ref" -> o.promptRef.map(_.json).getOrElse(JsNull).asValue,
      "context_ref" -> o.contextRef.map(_.json).getOrElse(JsNull).asValue,
      "extractor" -> o.extractor.map(_.json).getOrElse(JsNull).asValue,
    )
  }
  val configFlow: Seq[String] = Seq("ref", "prompt", "prompt_ref", "context_ref", "extractor", "is_response")
  val configSchema: Option[JsObject] = Some(Json.obj(
    "is_response" -> Json.obj(
      "type" -> "bool",
      "label" -> "LLM response is HTTP response"
    ),
    "prompt" -> Json.obj(
      "type" -> "text",
      "label" -> "Modifier prompt"
    ),
    "prompt_ref" -> Json.obj(
      "type" -> "select",
      "label" -> s"Modifier prompt ref.",
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
      "label" -> s"Modifier context ref.",
      "props" -> Json.obj(
        "isClearable" -> true,
        "optionsFrom" -> s"/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/prompt-contexts",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      ),
    ),
    "extractor" -> Json.obj(
      "type" -> "string",
      "suffix" -> "regex",
      "label" -> "Response extractor"
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

class AiResponseBodyModifier extends NgRequestTransformer {

  override def name: String = "Cloud APIM - LLM Response body modifier"
  override def description: Option[String] = "Can modify response body based on a prompt".some
  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))

  override def steps: Seq[NgStep] = Seq(NgStep.TransformResponse)
  override def defaultConfigObject: Option[NgPluginConfig] = Some(AiResponseBodyModifierConfig.default)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = AiResponseBodyModifierConfig.configFlow
  override def configSchema: Option[JsObject] = AiResponseBodyModifierConfig.configSchema

  override def transformsError: Boolean = false
  override def transformsResponse: Boolean = true
  override def transformsRequest: Boolean = false

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'LLM Response body modifier' plugin is available !")
    }
    ().vfuture
  }

  override def transformResponse(ctx: NgTransformerResponseContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val config = ctx.cachedConfig(internalName)(AiResponseBodyModifierConfig.format).getOrElse(AiResponseBodyModifierConfig.default)
    env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(config.ref)) match {
      case None => Left(Results.InternalServerError(Json.obj("error" -> "provider not found"))).vfuture // TODO: rewrite error
      case Some(provider) => provider.getChatClient() match {
        case Some(client) => {
          ctx.otoroshiResponse.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
            client.call(ChatPrompt(config.preChatMessages ++ Seq(
              ChatMessage.input("system", config.prompt, None, Json.obj()),
              ChatMessage.input("user", bodyRaw.utf8String, None, Json.obj()),
            ) ++ config.postChatMessages), ctx.attrs, Json.obj()).flatMap {
              case Left(err) => Left(Results.InternalServerError(Json.obj("error" -> err))).vfuture // TODO: rewrite error
              case Right(resp) => {
                config.extractor match {
                  case None if config.isResponse => {
                    val response = Json.parse(resp.generations.head.message.content)
                    val body = BodyHelper.extractBodyFrom(response)
                    ctx.otoroshiResponse.copy(
                      status = response.select("status").asOpt[Int].getOrElse(200),
                      headers = response
                        .select("headers")
                        .asOpt[Map[String, String]]
                        .getOrElse(Map("Content-Type" -> "application/json")),
                      body = body.chunks(32 * 1024)
                    ).rightf
                  }
                  case None if !config.isResponse => {
                    val content = resp.generations.head.message.content
                    ctx.otoroshiResponse.copy(
                      headers = ctx.otoroshiResponse.headers - "Content-Length" - "content-length" ++ Map("Content-Length" -> content.length.toString),
                      body = content.byteString.chunks(32 * 1024)
                    ).rightf
                  }
                  case Some(regex) => {
                    val pattern: Pattern = Pattern.compile(regex, CASE_INSENSITIVE)
                    val matcher: Matcher = pattern.matcher(resp.generations.head.message.content)
                    if (matcher.find()) {
                      val matchResult: MatchResult = matcher.toMatchResult
                      val expression: String       = matchResult.group()
                      if (config.isResponse) {
                        val response = Json.parse(expression)
                        val body = BodyHelper.extractBodyFrom(response)
                        ctx.otoroshiResponse.copy(
                          status = response.select("status").asOpt[Int].getOrElse(200),
                          headers = response
                            .select("headers")
                            .asOpt[Map[String, String]]
                            .getOrElse(Map("Content-Type" -> "application/json")),
                          body = body.chunks(32 * 1024)
                        ).rightf
                      } else {
                        ctx.otoroshiResponse.copy(
                          headers = ctx.otoroshiResponse.headers - "Content-Length" - "content-length" ++ Map("Content-Length" -> expression.length.toString),
                          body = expression.byteString.chunks(32 * 1024)
                        ).rightf
                      }
                    } else {
                      if (config.isResponse) {
                        val response = Json.parse(resp.generations.head.message.content)
                        val body = BodyHelper.extractBodyFrom(response)
                        ctx.otoroshiResponse.copy(
                          status = response.select("status").asOpt[Int].getOrElse(200),
                          headers = response
                            .select("headers")
                            .asOpt[Map[String, String]]
                            .getOrElse(Map("Content-Type" -> "application/json")),
                          body = body.chunks(32 * 1024)
                        ).rightf
                      } else {
                        ctx.otoroshiResponse.copy(
                          headers = ctx.otoroshiResponse.headers - "Content-Length" - "content-length" ++ Map("Transfer-Encoding" -> "chunked"),
                          body = resp.generations.head.message.content.byteString.chunks(32 * 1024)
                        ).rightf
                      }
                    }
                  }
                }
              }
            }
          }
        }
        case None => Left(Results.InternalServerError(Json.obj("error" -> "client not found"))).vfuture // TODO: rewrite error
      }
    }
  }
}
