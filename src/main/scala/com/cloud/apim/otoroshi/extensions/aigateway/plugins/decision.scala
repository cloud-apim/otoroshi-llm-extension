package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import org.apache.pekko.stream.Materializer
import org.apache.pekko.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.decorators.ModelTarget
import com.cloud.apim.otoroshi.extensions.aigateway.entities.DecisionModel
import com.cloud.apim.otoroshi.extensions.aigateway.providers.OpenAiDecisions
import com.cloud.apim.otoroshi.extensions.aigateway.{DecisionErrors, DecisionModelClientInputOptions, DecisionRequests, DecisionResponse}
import otoroshi.env.Env
import otoroshi.next.plugins.api.*
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.*
import play.api.mvc.{Result, Results}

import java.util.concurrent.TimeoutException
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class DecisionModelsConfig(refs: Seq[String]) extends NgPluginConfig {
  def json: JsValue = DecisionModelsConfig.format.writes(this)
}

object DecisionModelsConfig {
  val configFlow: Seq[String] = Seq("refs")
  def configSchema: Option[JsObject] = Some(Json.obj(
    "refs" -> Json.obj(
      "type" -> "select",
      "array" -> true,
      "label" -> s"Decision models",
      "props" -> Json.obj(
        "optionsFrom" -> s"/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/decision-models",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      ),
    )
  ))
  val default = DecisionModelsConfig(Seq.empty)
  val format = new Format[DecisionModelsConfig] {
    override def writes(o: DecisionModelsConfig): JsValue = Json.obj("refs" -> o.refs)
    override def reads(json: JsValue): JsResult[DecisionModelsConfig] = Try {
      DecisionModelsConfig(
        refs = json.select("refs").asOpt[Seq[String]].getOrElse(Seq.empty)
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(value) => JsSuccess(value)
    }
  }
}

/**
 * Which decision model a request is for, and the model it asks it for.
 *
 * The other model types read `<entity>/<model>` and nothing else. That rule alone would misroute decisions:
 * the model ids of these providers have slashes of their own (`typesafe/jev-1.13` on OpenRouter,
 * `telnyx/decision-flash`), and an entity named "TypeSafe" has the slug `typesafe`. So a model that is the very
 * one an entity is configured with goes to that entity as it is, before any prefix is read in it.
 */
object DecisionModelsResolver {

  final case class Resolved(entity: DecisionModel, model: Option[String], body: JsObject)

  private def named(entities: Seq[DecisionModel], name: String): Option[DecisionModel] =
    entities.find(_.id == name).orElse(entities.find(_.slugName == name))

  private def modelOf(value: String): Option[String] =
    Some(value.trim).filter(v => v.nonEmpty && v != ModelTarget.DefaultModel)

  def resolve(body: JsObject, entities: Seq[DecisionModel]): Option[Resolved] = {
    val requested = body.select("model").asOptString.map(_.trim).filter(_.nonEmpty)
    // a string `provider` is the entity to use, the gateway's own way of choosing one. As an object it is the
    // routing preferences of OpenRouter, left in the body for the client to forward or not
    val providerName = body.select("provider").asOptString
    val cleaned = if (providerName.isDefined) body - "provider" else body
    def resolved(entity: DecisionModel, model: Option[String]): Resolved =
      Resolved(entity, model, model.map(m => cleaned ++ Json.obj("model" -> m)).getOrElse(cleaned - "model"))

    // 1. `<entity>###<model>` cannot be read any other way
    val explicit = requested.filter(_.contains("###")).flatMap { value =>
      val idx = value.indexOf("###")
      named(entities, value.substring(0, idx)).map(e => resolved(e, modelOf(value.substring(idx + 3))))
    }
    // 2. the entity named in `provider`
    def byProvider = providerName.flatMap(name => named(entities, name)).map(e => resolved(e, requested))
    // 3. the model an entity is configured with, whatever is in its name
    def byModel = requested.flatMap(m => entities.find(_.defaultModel.contains(m)).map(e => resolved(e, m.some)))
    // 4. `<entity>/<model>`
    def byPrefix = requested.filter(_.contains("/")).flatMap { value =>
      val idx = value.indexOf("/")
      named(entities, value.substring(0, idx)).map(e => resolved(e, modelOf(value.substring(idx + 1))))
    }
    // 5. the first decision model of the route
    def first = entities.headOption.map(e => resolved(e, requested))

    explicit.orElse(byProvider).orElse(byModel).orElse(byPrefix).orElse(first)
  }
}

object DecisionModels {

  private def errorResult(status: Int, body: JsValue, retryAfter: Option[String] = None): Result = {
    val result = Results.Status(status)(body)
    retryAfter.map(v => result.withHeaders("Retry-After" -> v)).getOrElse(result)
  }

  /**
   * The http answer of a failed decision call. What a provider answered is given back as it is, status
   * included: a TypeSafe sdk reads it the way it reads the provider, and retries what can be retried. A
   * budget is a 402 rather than a 429, which a client would keep retrying against a budget that is not
   * going to come back in the next seconds.
   */
  def resultOf(err: JsValue): Result = DecisionErrors.classify(err) match {
    case DecisionErrors.Kind.Upstream(status, body: JsString, retryAfter) => errorResult(status, DecisionErrors.detail("api_error", body.value), retryAfter)
    case DecisionErrors.Kind.Upstream(status, body, retryAfter) => errorResult(status, body, retryAfter)
    case DecisionErrors.Kind.Budget(message) => errorResult(402, DecisionErrors.detail("budget_exceeded", message))
    case DecisionErrors.Kind.Denied => errorResult(403, DecisionErrors.detail("permission_error", "you can't use this model"))
    case DecisionErrors.Kind.NotBillable(message) => errorResult(403, DecisionErrors.detail("permission_error", message))
    case DecisionErrors.Kind.Other(error) => errorResult(500, Json.obj("detail" -> Json.obj("error_type" -> "api_error", "message" -> "the decision model failed to answer", "error" -> error)))
  }

  private def failed(err: JsValue): Either[NgProxyEngineError, BackendCallResponse] =
    Left(NgProxyEngineError.NgResultProxyEngineError(resultOf(err)))

  /** A System One request, asked to the decision model of `refs` it is for. `openai` is the request it was made of, if any */
  def decide(refs: Seq[String], body: JsObject, attrs: TypedMap, openai: Option[JsObject] = None)(using env: Env, ec: ExecutionContext): Future[Either[JsValue, DecisionResponse]] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    DecisionModelsResolver.resolve(body, refs.flatMap(ref => ext.states.decisionModel(ref))) match {
      case None => DecisionErrors.gateway(500, "api_error", "no decision model is served here").leftf
      case Some(resolved) => resolved.entity.getDecisionModelClient() match {
        case None => DecisionErrors.gateway(500, "api_error", "failed to create the client of the decision model").leftf
        case Some(client) =>
          val options = DecisionModelClientInputOptions.format.reads(resolved.body).get.copy(openai = openai)
          client.decide(options, resolved.body, attrs).recover {
            // no answer at all, and nothing took over
            case e: TimeoutException => Left(DecisionErrors.gateway(504, "timeout_error", Option(e.getMessage).getOrElse("the decision model did not answer in time")))
            case e: Throwable => Left(DecisionErrors.gateway(502, "api_connection_error", Option(e.getMessage).getOrElse("the decision model could not be reached")))
          }
      }
    }
  }

  def handleRequest(config: DecisionModelsConfig, ctx: NgbBackendCallContext)(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
      Try(Json.parse(bodyRaw.utf8String)) match {
        case Failure(_) => failed(DecisionErrors.invalid(Seq(Json.obj("type" -> "json_invalid", "loc" -> Seq("body"), "msg" -> "JSON decode error")))).vfuture
        case Success(json) =>
          val issues = DecisionRequests.issues(json)
          if (issues.nonEmpty) {
            failed(DecisionErrors.invalid(issues)).vfuture
          } else {
            decide(config.refs, json.asObject, ctx.attrs).map {
              case Left(err) => failed(err)
              case Right(decision) => Right(BackendCallResponse.apply(NgPluginHttpResponse.fromResult(Results.Ok(decision.toJson(env))), None))
            }
          }
      }
    }
  }
}

class DecisionModels extends NgBackendCall {

  override def name: String = "Cloud APIM - Decision models backend"
  override def description: Option[String] = "Delegates call to a decision model (System One api): typed questions about a state, answered with probabilities".some
  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))
  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)
  override def useDelegates: Boolean = false
  override def defaultConfigObject: Option[NgPluginConfig] = Some(DecisionModelsConfig.default)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = DecisionModelsConfig.configFlow
  override def configSchema: Option[JsObject] = DecisionModelsConfig.configSchema

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'Decision models backend' plugin is available !")
    }
    ().vfuture
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(DecisionModelsConfig.format).getOrElse(DecisionModelsConfig.default)
    DecisionModels.handleRequest(config, ctx)
  }
}

/**
 * The decisions api of OpenAI, served by the same decision models: the request is read as a System One one
 * (`OpenAiDecisions`), and the answers, the usage and the errors are given back in the OpenAI format.
 */
object OpenAICompatDecisions {

  private def errorResult(status: Int, body: JsValue, retryAfter: Option[String] = None): Result = {
    val result = Results.Status(status)(body)
    retryAfter.map(v => result.withHeaders("Retry-After" -> v)).getOrElse(result)
  }

  /** The http answer of a failed decision call, with the statuses of `DecisionModels.resultOf` */
  def resultOf(err: JsValue, request: Option[OpenAiDecisions.Request] = None): Result = DecisionErrors.classify(err) match {
    case DecisionErrors.Kind.Upstream(status, body, retryAfter) => errorResult(status, OpenAiDecisions.errorOf(status, body, request), retryAfter)
    case DecisionErrors.Kind.Budget(message) => errorResult(402, OpenAiDecisions.error(message, None, "insufficient_quota", "budget_exceeded".some))
    case DecisionErrors.Kind.Denied => errorResult(403, OpenAiDecisions.error("you can't use this model", "model".some, "permission_error", "model_not_allowed".some))
    case DecisionErrors.Kind.NotBillable(message) => errorResult(403, OpenAiDecisions.error(message, "model".some, "permission_error", "model_not_allowed".some))
    case DecisionErrors.Kind.Other(_) => errorResult(500, OpenAiDecisions.error("the decision model failed to answer", None, "server_error"))
  }

  private def failed(result: Result): Either[NgProxyEngineError, BackendCallResponse] =
    Left(NgProxyEngineError.NgResultProxyEngineError(result))

  def handleRequest(config: DecisionModelsConfig, ctx: NgbBackendCallContext)(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    ctx.request.body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>
      val request = Try(Json.parse(bodyRaw.utf8String)) match {
        case Failure(_) => Left(OpenAiDecisions.error("We could not parse the JSON body of your request."))
        case Success(json) => OpenAiDecisions.read(json)
      }
      request match {
        case Left(error) => failed(Results.BadRequest(error)).vfuture
        case Right(request) =>
          DecisionModels.decide(config.refs, request.systemOne, ctx.attrs, request.raw.some).map {
            case Left(err) => failed(resultOf(err, request.some))
            case Right(decision) => Right(BackendCallResponse.apply(NgPluginHttpResponse.fromResult(Results.Ok(OpenAiDecisions.response(request, decision, env))), None))
          }
      }
    }
  }
}

class OpenAICompatDecisions extends NgBackendCall {

  override def name: String = "Cloud APIM - OpenAI decisions backend"
  override def description: Option[String] = "Delegates call to a decision model with the decisions api of OpenAI: typed questions about an input, answered with probabilities".some
  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))
  override def steps: Seq[NgStep] = Seq(NgStep.CallBackend)
  override def useDelegates: Boolean = false
  override def defaultConfigObject: Option[NgPluginConfig] = Some(DecisionModelsConfig.default)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = DecisionModelsConfig.configFlow
  override def configSchema: Option[JsObject] = DecisionModelsConfig.configSchema

  override def start(env: Env): Future[Unit] = {
    env.adminExtensions.extension[AiExtension].foreach { ext =>
      ext.logger.info("the 'OpenAI decisions backend' plugin is available !")
    }
    ().vfuture
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(DecisionModelsConfig.format).getOrElse(DecisionModelsConfig.default)
    OpenAICompatDecisions.handleRequest(config, ctx)
  }
}
