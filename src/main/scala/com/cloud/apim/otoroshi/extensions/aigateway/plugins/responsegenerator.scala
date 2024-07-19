package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import akka.stream.Materializer
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatMessage, ChatPrompt}
import otoroshi.env.Env
import otoroshi.next.plugins.BodyHelper
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json._
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class AiResponseGeneratorConfig(ref: String = "", _prompt: String = "", promptRef: Option[String] = None, contextRef: Option[String] = None, isResponse: Boolean = false, status: Int = 200, headers: Map[String, String] = Map.empty) extends NgPluginConfig {
  def json: JsValue = AiResponseGeneratorConfig.format.writes(this)
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
  def preChatMessages(implicit env: Env): Seq[ChatMessage] = {
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
  def postChatMessages(implicit env: Env): Seq[ChatMessage] = {
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

object AiResponseGeneratorConfig {
  val default = AiResponseGeneratorConfig()
  val format = new Format[AiResponseGeneratorConfig] {
    override def reads(json: JsValue): JsResult[AiResponseGeneratorConfig] = Try {
      AiResponseGeneratorConfig(
        ref = json.select("ref").asOpt[String].getOrElse(""),
        _prompt = json.select("prompt").asOpt[String].getOrElse(""),
        promptRef = json.select("prompt_ref").asOpt[String].filterNot(_.isBlank),
        contextRef = json.select("context_ref").asOpt[String].filterNot(_.isBlank),
        isResponse = json.select("is_response").asOpt[Boolean].getOrElse(false),
        status = json.select("status").asOpt[Int].getOrElse(200),
        headers = json.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty),
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }
    override def writes(o: AiResponseGeneratorConfig): JsValue = Json.obj(
      "ref" -> o.ref,
      "prompt" -> o._prompt,
      "prompt_ref" -> o.promptRef.map(_.json).getOrElse(JsNull).asValue,
      "context_ref" -> o.contextRef.map(_.json).getOrElse(JsNull).asValue,
      "is_response" -> o.isResponse,
      "status" -> o.status,
      "headers" -> o.headers,
    )
  }
  val configFlow: Seq[String] = Seq("ref", "prompt", "prompt_ref", "context_ref", "is_response", "status", "headers")
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
    "status" -> Json.obj(
      "type" -> "number",
      "label" -> "Http response status"
    ),
    "headers" -> Json.obj(
      "type" -> "object",
      "label" -> "Http response headers"
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

class AiResponseGenerator extends NgBackendCall {

  override def name: String = "Cloud APIM - LLM Response generator"
  override def description: Option[String] = "Can generate a response based on a prompt".some
  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))

  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)
  override def defaultConfigObject: Option[NgPluginConfig] = Some(AiResponseGeneratorConfig.default)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = AiResponseGeneratorConfig.configFlow
  override def configSchema: Option[JsObject] = AiResponseGeneratorConfig.configSchema

  override def useDelegates: Boolean = false

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'LLM Response generator' plugin is available !")
    }
    ().vfuture
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(AiResponseGeneratorConfig.format).getOrElse(AiResponseGeneratorConfig.default)
    env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(config.ref)) match {
      case None => Left(NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "provider not found")))).vfuture // TODO: rewrite error
      case Some(provider) => provider.getChatClient() match {
        case Some(client) => {
          // TODO: if hasBody, sink it ?
          client.call(ChatPrompt(config.preChatMessages ++ Seq(ChatMessage("user", config.prompt)) ++ config.postChatMessages), ctx.attrs).map {
            case Left(err) => Left(NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> err)))) // TODO: rewrite error
            case Right(resp) if config.isResponse => {
              val response = Json.parse(resp.generations.head.message.content)
              val body = BodyHelper.extractBodyFrom(response)
              val status = response.select("status").asOpt[Int].getOrElse(200)
              val headers = response
                .select("headers")
                .asOpt[Map[String, String]]
                .getOrElse(Map("Content-Type" -> "application/json"))
              val contentType = headers.getIgnoreCase("Content-Type").getOrElse("application/json")
              Right(BackendCallResponse(NgPluginHttpResponse.fromResult(
                Results.Status(status)(body).withHeaders(headers.toSeq: _*).as(contentType)
              ), None))
            }
            case Right(resp) if !config.isResponse => {
              val status = config.status
              val headers = config.headers
              val contentType = headers.getIgnoreCase("Content-Type").orElse(headers.get("content-type")).getOrElse("application/json")
              Right(BackendCallResponse(NgPluginHttpResponse.fromResult(
                Results.Status(status)(resp.generations.head.message.content.byteString).withHeaders(headers.toSeq: _*).as(contentType)
              ), None))
            }
          }
        }
        case None => Left(NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "client not found")))).vfuture // TODO: rewrite error
      }
    }
  }
}
