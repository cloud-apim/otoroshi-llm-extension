package com.cloud.apim.otoroshi.extensions.aigateway.providers

import com.cloud.apim.otoroshi.extensions.aigateway.*
import com.cloud.apim.otoroshi.extensions.aigateway.decorators.{ChatClientWithCostsTracking, CostsOutput, ModelConstraints, ModelTarget, TokenBasedCosts}
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.*
import play.api.libs.ws.WSBodyWritables.given
import play.api.libs.ws.WSResponse

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/////////                                  Decision Models (System One)                                  ///////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

case class SystemOneProviderDef(id: String, name: String, baseUrl: String, defaultModel: String)

object SystemOneProviders {

  val Compatible = "systemone-compatible"
  val Cloudflare = "cloudflare"
  val LlmEmulation = "llm-emulation"

  val defaultPath = "/systemone"
  val cloudflareBaseUrl = "https://api.cloudflare.com/client/v4"
  val cloudflareDefaultModel = "@cf/cloudflare/clef-flash"

  // a decision is made to be waited for: past this, a fallback is a better answer than a late one
  val defaultTimeout: FiniteDuration = 30.seconds

  // The servers speaking the System One api natively. The base url is the one a TypeSafe sdk is given, version
  // included: the client only appends `/systemone` to it.
  val all: Seq[SystemOneProviderDef] = Seq(
    SystemOneProviderDef("typesafe",   "TypeSafe",          "https://api.typesafe.ai/v1",               "jev-latest"),
    SystemOneProviderDef("openrouter", "OpenRouter",        OpenRouterApi.baseUrl,                      "typesafe/jev-1.13"),
    SystemOneProviderDef("liquid",     "Liquid AI",         "https://api.liquid.ai/decisions/v1",       "d1:free"),
    SystemOneProviderDef("telnyx",     "Telnyx",            "https://api.telnyx.com/v2/ai/typesafe/v1", "telnyx/decision-flash"),
    SystemOneProviderDef("prem",       "Prem AI",           "https://gateway.prem.io/typesafe/v1",      "dgemma"),
    SystemOneProviderDef("vercel",     "Vercel AI Gateway", "https://ai-gateway.vercel.sh/typesafe/v1", "typesafe-ai/jev"),
  )

  def find(id: String): Option[SystemOneProviderDef] = all.find(_.id == id)

  // the providers taking the routing preferences of OpenRouter (the `provider` object of the request)
  val routingProviders: Set[String] = Set("openrouter")
}

class SystemOneApi(
  token: String,
  timeout: FiniteDuration,
  providerName: String,
  headers: Map[String, String] = Map("Authorization" -> "Bearer {api_key}"),
  env: Env,
  providerId: Option[String] = None,
) {

  private def resolvedHeaders: Seq[(String, String)] = {
    val hdrs = if (headers.isEmpty) Map("Authorization" -> "Bearer {api_key}") else headers
    hdrs.map { case (k, v) => k -> v.replace("{api_key}", token).replace("{token}", token) }.toSeq
  }

  def call(url: String, body: JsValue)(using ec: ExecutionContext): Future[WSResponse] = {
    ProviderHelpers.logCall(providerName, "POST", url, body.some)(using env)
    env.Ws
      .url(url)
      .withHttpHeaders(resolvedHeaders ++ Seq("Accept" -> "application/json", "Content-Type" -> "application/json")*)
      .withBody(body)
      .withMethod("POST")
      .withRequestTimeout(timeout)
      .execute()
      .observeQuotas(providerName, url, providerId)(using ec, env)
  }
}

case class DecisionModelClientOptions(raw: JsObject) {
  lazy val model: Option[String] = raw.select("model").asOptString.map(_.trim).filter(_.nonEmpty)
}

object DecisionModelClientOptions {
  def fromJson(raw: JsObject): DecisionModelClientOptions = DecisionModelClientOptions(raw)
}

object SystemOneResponses {

  val noModel: JsValue = DecisionErrors.invalid(Seq(Json.obj("type" -> "missing", "loc" -> Seq("body", "model"), "msg" -> "Field required")))

  // an error page is not json: it is given back as the text it is
  private def bodyOf(resp: WSResponse): JsValue = {
    val raw: String = resp.body
    Try(Json.parse(raw)).getOrElse(JsString(raw.take(ProviderHelpers.defaultMaxErrorBodySize)))
  }

  private def rateLimitOf(resp: WSResponse): ChatResponseMetadataRateLimit = {
    def header(name: String): Long = resp.header(name).flatMap(v => Try(v.toLong).toOption).getOrElse(-1L)
    ChatResponseMetadataRateLimit(
      requestsLimit = header("x-ratelimit-limit-requests"),
      requestsRemaining = header("x-ratelimit-remaining-requests"),
      tokensLimit = header("x-ratelimit-limit-tokens"),
      tokensRemaining = header("x-ratelimit-remaining-tokens"),
    )
  }

  /**
   * The answer of a System One server. Cloudflare Workers AI wraps what its models return in a
   * `{result, success, errors}` envelope: the answers are looked for in both places, so the server is read
   * right whichever way it answers.
   */
  def read(providerName: String, resp: WSResponse, requestedModel: String, env: Env): Either[JsValue, DecisionResponse] = {
    if (env.isDev || AiExtension.logger.isDebugEnabled) {
      val msg = s"provider response '${providerName}' - ${resp.status} - ${resp.body}"
      if (env.isDev) AiExtension.logger.info(msg) else AiExtension.logger.debug(msg)
    }
    val body = bodyOf(resp)
    if (resp.status == 200) {
      val payload = body.asOpt[JsObject].flatMap { obj =>
        if (obj.select("answers").asOpt[JsObject].isDefined) obj.some
        else obj.select("result").asOpt[JsObject].filter(_.select("answers").asOpt[JsObject].isDefined)
      }
      payload match {
        case Some(obj) =>
          val usage = obj.select("usage").asOpt[JsObject].getOrElse(Json.obj())
          Right(DecisionResponse(
            model = obj.select("model").asOptString.filter(_.nonEmpty).getOrElse(requestedModel),
            answers = obj.select("answers").as[JsObject],
            metadata = DecisionResponseMetadata(
              usage = DecisionResponseMetadataUsage.fromJson(usage),
              rateLimit = rateLimitOf(resp),
              providerCosts = CostsOutput.fromOpenAiLikeUsage(usage),
            ),
            raw = obj,
          ))
        case None =>
          ProviderHelpers.logBadResponse(providerName, resp)
          Left(DecisionErrors.upstream(502, Json.obj("detail" -> Json.obj(
            "error_type" -> "api_error",
            "message" -> s"${providerName} answered without any answers",
            "provider_response" -> body,
          ))))
      }
    } else {
      ProviderHelpers.logBadResponse(providerName, resp)
      Left(DecisionErrors.upstream(resp.status, body, resp.header("Retry-After")))
    }
  }
}

class SystemOneDecisionModelClient(
  api: SystemOneApi,
  url: String,
  providerName: String,
  options: DecisionModelClientOptions,
  // whether the `provider` object of the request (OpenRouter routing preferences) means something upstream
  forwardsRouting: Boolean,
) extends DecisionModelClient {

  override def decide(opts: DecisionModelClientInputOptions, rawBody: JsObject, attrs: TypedMap)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, DecisionResponse]] = {
    opts.model.orElse(options.model) match {
      case None => SystemOneResponses.noModel.leftf
      case Some(model) =>
        // the body goes through as the caller wrote it: the questions and their criteria are the provider's business
        val body = (if (forwardsRouting) rawBody else rawBody - "provider") ++ Json.obj("model" -> model)
        api.call(url, body).map(resp => SystemOneResponses.read(providerName, resp, model, env))
    }
  }
}

/**
 * Clef and Clef-flash on Workers AI. Same body as any System One server, but the model is in the url
 * (`.../ai/run/@cf/cloudflare/clef-flash`) and the body names it without its namespace.
 */
class CloudflareDecisionModelClient(
  api: SystemOneApi,
  baseUrl: String,
  accountId: String,
  options: DecisionModelClientOptions,
) extends DecisionModelClient {

  override def decide(opts: DecisionModelClientInputOptions, rawBody: JsObject, attrs: TypedMap)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, DecisionResponse]] = {
    opts.model.orElse(options.model) match {
      case None => SystemOneResponses.noModel.leftf
      case Some(model) =>
        val body = (rawBody - "provider") ++ Json.obj("model" -> model.split("/").last)
        api.call(s"${baseUrl}/accounts/${accountId}/ai/run/${model}", body).map(resp => SystemOneResponses.read("Cloudflare", resp, model, env))
    }
  }
}

/**
 * Decisions made by a text provider: the questions are asked to a chat model, which states a probability for
 * every outcome, and the answers are computed from them the way a decision model reports its own.
 *
 * Those probabilities are what the model says of its own certainty. They are not the calibrated ones of a
 * model trained for it, and a threshold tuned on one does not carry over to the other.
 *
 * The chat call is a call of the text provider like any other: it is the one billed, counted against budgets
 * and audited. Nothing of it is accounted a second time here.
 */
class LlmDecisionModelClient(
  target: ModelTarget,
  providerRef: String,
  options: DecisionModelClientOptions,
) extends DecisionModelClient {

  import LlmDecisionModelClient.*

  override def decide(opts: DecisionModelClientInputOptions, rawBody: JsObject, attrs: TypedMap)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, DecisionResponse]] = {
    val issues = DecisionRequests.issues(Json.obj("state" -> opts.state, "questions" -> opts.questions), strict = true)
    if (issues.nonEmpty) {
      DecisionErrors.invalid(issues).leftf
    } else {
      val model = opts.model.orElse(options.model)
      val provider = env.adminExtensions.extension[AiExtension]
        .flatMap(_.states.provider(providerRef))
        // a semantic cache answers a state with the decision made for another one that looks like it
        .map(p => if (p.cache.strategy.contains("semantic")) p.copy(cache = p.cache.copy(strategy = "none")) else p)
        .map(_.withModel(model))
      provider.flatMap(p => p.getChatClient().map(c => (p, c))) match {
        case None => DecisionErrors.gateway(500, "api_error", "the text provider of this decision model was not found").leftf
        case Some((textProvider, client)) =>
          val body = Json.obj()
          val inner = DecisionModelClient.childAttrs(attrs)
          // the caller was allowed to ask this entity for a decision: the text provider behind it is not its choice
          ModelConstraints.delegate(inner, target, opts.model, textProvider, client, body)
          client.call(prompt(opts), inner, body).map {
            case Left(err) => DecisionErrors.classify(err) match {
              case _: DecisionErrors.Kind.Upstream | _: DecisionErrors.Kind.Other =>
                Left(DecisionErrors.upstream(502, Json.obj("detail" -> Json.obj(
                  "error_type" -> "api_error",
                  "message" -> "the text provider failed to answer",
                  "provider_response" -> err,
                ))))
              // a budget, a model restriction: said as it is
              case _ => Left(err)
            }
            case Right(resp) => answersOf(resp.headGeneration.message.wholeTextContent, opts.questions) match {
              case Left(message) => Left(DecisionErrors.gateway(502, "api_error", message))
              case Right(answers) => Right(DecisionResponse(
                model = resp.raw.select("model").asOptString.filter(_.nonEmpty).orElse(client.computeModel(body)).orElse(model).getOrElse("--"),
                answers = answers,
                metadata = DecisionResponseMetadata(
                  usage = DecisionResponseMetadataUsage(resp.metadata.usage.promptTokens.max(0L), (resp.metadata.usage.generationTokens + resp.metadata.usage.reasoningTokens).max(0L)),
                  // what the chat call cost, for a caller asking to see it: it was billed by the text provider
                  costs = if (TokenBasedCosts.embedInResponse(attrs)) inner.get(ChatClientWithCostsTracking.key) else None,
                  delegated = true,
                ),
              ))
            }
          }
      }
    }
  }
}

object LlmDecisionModelClient {

  val instructions: String =
    """You are a decision engine. You are given a STATE and QUESTIONS about it. You answer every question with probabilities only: no text, no explanation, no reasoning.
      |
      |Each question has a "type", "instructions" and, most of the time, "criteria":
      |- "noul" is a yes/no question. Its optional criteria describe what "true" and "false" mean.
      |- "choice" picks one option among the keys of its criteria, each key being described by its value.
      |- "score" rates the state on an ordered scale: its criteria are the levels, from the lowest (level 0) to the highest.
      |
      |Answer with one single JSON object and nothing else, holding one entry per question, under the very name of the question:
      |- for a "noul": {"noul": <probability between 0 and 1 that the answer is yes>}
      |- for a "choice": {"probabilities": {"<option>": <probability>, ...}} with every option of the criteria, summing to 1
      |- for a "score": {"probabilities": [<probability of level 0>, <probability of level 1>, ...]} with one probability per level, in order, summing to 1
      |
      |Spread the probabilities the way your certainty is spread: a clear cut answer is close to 0 or 1, a doubt is not.""".stripMargin

  private def text(value: JsValue): String = value match {
    case JsString(str) => str
    case other => Json.prettyPrint(other)
  }

  def prompt(opts: DecisionModelClientInputOptions): ChatPrompt = ChatPrompt(Seq(
    ChatMessage.input("system", instructions, None, Json.obj()),
    ChatMessage.input("user", s"STATE:\n${text(opts.state)}\n\nQUESTIONS:\n${Json.prettyPrint(opts.questions)}", None, Json.obj()),
  ))

  // a model wraps its json in a code fence or in a sentence more often than not
  def jsonOf(content: String): Option[JsObject] = {
    val start = content.indexOf('{')
    val end = content.lastIndexOf('}')
    if (start < 0 || end <= start) None else Try(Json.parse(content.substring(start, end + 1))).toOption.flatMap(_.asOpt[JsObject])
  }

  private def number(value: JsValue): Option[Double] = value match {
    case JsNumber(n) => n.toDouble.some
    case JsString(s) => Try(s.trim.toDouble).toOption
    case JsBoolean(b) => (if (b) 1.0 else 0.0).some
    case _ => None
  }

  private def answerOf(name: String, question: JsObject, stated: JsValue): Either[String, JsObject] = {
    val missing = Left(s"the model did not answer the question '${name}'")
    question.select("type").asString match {
      case DecisionRequests.Noul =>
        number(stated).orElse(stated.select("noul").asOpt[JsValue].flatMap(number)) match {
          case Some(p) => Right(DecisionAnswers.noul(p))
          case None => missing
        }
      case DecisionRequests.Choice =>
        val options = question.select("criteria").as[JsObject].keys.toSeq
        stated.select("probabilities").asOpt[JsObject] match {
          // an option the model left out is one it did not consider: no probability
          case Some(probabilities) => Right(DecisionAnswers.choice(options.map(o => o -> probabilities.value.get(o).flatMap(number).getOrElse(0.0))))
          case None => missing
        }
      case DecisionRequests.Score =>
        val levels = question.select("criteria").as[JsArray].value.toSeq
        val probabilities = stated.select("probabilities").asOpt[JsValue] match {
          case Some(JsArray(values)) => values.toSeq.map(v => number(v).getOrElse(0.0)).some
          case Some(JsObject(fields)) => levels.indices.map(i => fields.get(i.toString).flatMap(number).getOrElse(0.0)).some
          case _ => None
        }
        probabilities match {
          case Some(ps) => Right(DecisionAnswers.score(levels, levels.indices.map(i => ps.lift(i).getOrElse(0.0))))
          case None => missing
        }
      case other => Left(s"the question '${name}' has the unknown type '${other}'")
    }
  }

  /** the System One answers of what a chat model said, or what is wrong with it */
  def answersOf(content: String, questions: JsObject): Either[String, JsObject] = {
    jsonOf(content) match {
      case None => Left("the text provider did not answer with the expected json object")
      case Some(stated) =>
        questions.fields.foldLeft[Either[String, JsObject]](Right(Json.obj())) {
          case (Left(err), _) => Left(err)
          case (Right(answers), (name, question: JsObject)) =>
            stated.value.get(name) match {
              case None => Left(s"the model did not answer the question '${name}'")
              case Some(value) => answerOf(name, question, value).map(answer => answers ++ Json.obj(name -> answer))
            }
          case (Right(answers), _) => Right(answers)
        }
    }
  }
}
