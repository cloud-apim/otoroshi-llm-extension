package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import akka.stream.Materializer
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.agents.{AgentConfig, AgentInput, AgentRunConfig}
import com.cloud.apim.otoroshi.extensions.aigateway.plugins.AiPluginsKeys
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatMessage, InputChatMessage}
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json._
import play.api.mvc.Results

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class AgentProxyConfig(agent: JsObject, runConfig: JsObject = Json.obj()) extends NgPluginConfig {
  def json: JsValue = Json.obj("agent" -> agent, "run_config" -> runConfig)
}

object AgentProxyConfig {
  val default: AgentProxyConfig = AgentProxyConfig(Json.obj())
  val configFlow: Seq[String] = Seq("agent", "run_config")
  def configSchema: Option[JsObject] = Some(Json.obj(
    "agent" -> Json.obj(
      "type" -> "any",
      "label" -> "Agent configuration",
      "props" -> Json.obj(
        "height" -> "400px",
        "description" -> "The agent configuration (name, instructions, provider, built_in_tools, handoffs, etc.)"
      )
    ),
    "run_config" -> Json.obj(
      "type" -> "any",
      "label" -> "Run configuration",
      "props" -> Json.obj(
        "height" -> "200px",
        "description" -> "Agent run configuration (max_turns, provider override, model override, etc.)"
      )
    ),
  ))
  val format: Format[AgentProxyConfig] = new Format[AgentProxyConfig] {
    override def writes(o: AgentProxyConfig): JsValue = o.json
    override def reads(json: JsValue): JsResult[AgentProxyConfig] = Try {
      AgentProxyConfig(
        agent = json.select("agent").asOpt[JsObject].getOrElse(Json.obj()),
        runConfig = json.select("run_config").asOpt[JsObject].getOrElse(Json.obj()),
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(v) => JsSuccess(v)
    }
  }
}

class AgentProxy extends NgBackendCall {

  override def name: String = "Cloud APIM - LLM Agent Proxy"
  override def description: Option[String] = "Exposes an AI Agent as an OpenAI chat/completions compatible endpoint".some

  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))
  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)
  override def useDelegates: Boolean = false
  override def defaultConfigObject: Option[NgPluginConfig] = Some(AgentProxyConfig.default)

  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = AgentProxyConfig.configFlow
  override def configSchema: Option[JsObject] = AgentProxyConfig.configSchema

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'LLM Agent Proxy' plugin is available !")
    }
    ().vfuture
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    if (ctx.request.hasBody) {
      ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
        try {
          val jsonBody = bodyRaw.utf8String.parseJson
          val config = ctx.cachedConfig(internalName)(AgentProxyConfig.format).getOrElse(AgentProxyConfig.default)
          call(jsonBody, config, ctx)
        } catch {
          case e: Throwable =>
            NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(Json.obj("error" -> "bad_request", "error_details" -> e.getMessage))).leftf
        }
      }
    } else {
      NgProxyEngineError.NgResultProxyEngineError(Results.BadRequest(Json.obj("error" -> "bad_request", "error_description" -> "request body is required"))).leftf
    }
  }

  private def call(jsonBody: JsValue, config: AgentProxyConfig, ctx: NgbBackendCallContext)(implicit ec: ExecutionContext, env: Env): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val agent = AgentConfig.from(config.agent)
    val rcfg = AgentRunConfig.from(config.runConfig)

    val requestMessages = ctx.attrs.get(AiPluginsKeys.PromptTemplateKey) match {
      case None => jsonBody.select("messages").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
      case Some(template) =>
        val context = Json.obj(
          "request" -> (ctx.json.asObject ++ Json.obj("route" -> ctx.route.json, "request" -> ctx.request.json)),
          "body" -> jsonBody,
          "properties" -> Json.obj(),
        )
        AiLlmProxy.applyTemplate(template, context)
    }

    val (preContextMessages, postContextMessages) = ctx.attrs.get(AiPluginsKeys.PromptContextKey).getOrElse((Seq.empty, Seq.empty))
    val messages = (preContextMessages ++ requestMessages ++ postContextMessages).map { obj =>
      InputChatMessage.fromJson(obj)
    }
    val input = AgentInput(messages)

    agent.run(input, rcfg, ctx.attrs, wfr = None).map {
      case Left(err) =>
        Left(NgProxyEngineError.NgResultProxyEngineError(Results.InternalServerError(err)))
      case Right(output) =>
        val model = agent.model.getOrElse(rcfg.model.getOrElse("agent"))
        Right(BackendCallResponse(NgPluginHttpResponse.fromResult(
          Results.Ok(output.response.openaiJson(model, env))
            .withHeaders(output.metadata.cacheHeaders.toSeq: _*)
        ), None))
    }
  }
}
