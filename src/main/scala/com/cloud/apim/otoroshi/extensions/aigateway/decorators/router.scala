package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import akka.stream.scaladsl.Source
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatClient, ChatPrompt, ChatResponse, ChatResponseChunk}
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}

// The "otoroshi" provider is a router: like the load balancer it references a list of existing otoroshi
// providers (options.refs), but instead of round-robin it routes automatically to the "best" candidate.
// For now it exposes a single model "code-router" (à la openrouter/pareto-code): pick a strong coder
// without overspending. Quality comes from a curated coding-index table (Artificial Analysis), cost from
// the litellm price catalog (reused from the costs decorator). On failure it cascades to the next-best
// candidate (cheapest qualifying first), like the provider-fallback decorator.
object OtoroshiRouterChatClient {

  // Artificial Analysis Coding Index (curated snapshot). Keys are alphanumeric-normalized model-id
  // fragments (lowercase, only [a-z0-9]); a candidate matches a key if its normalized model contains it.
  // Sorted at use-time by descending key length so the most specific fragment wins.
  val codingScores: Seq[(String, Double)] = Seq(
    "gpt55" -> 59.1,
    "claudeopus48" -> 56.7,
    "gemini31pro" -> 55.5,
    "claudeopus47" -> 52.5,
    "gpt54mini" -> 51.5,
    "claudesonnet46" -> 50.9,
    "qwen37max" -> 50.1,
    "deepseekv4pro" -> 47.5,
    "musespark" -> 47.5,
    "kimik26" -> 47.1,
    "mimov25pro" -> 45.5,
    "gemini35flash" -> 45.0,
    "minimaxm3" -> 43.4,
    "glm51" -> 43.4,
    "minimaxm27" -> 41.9,
    "qwen35397b" -> 41.3,
    "grok43" -> 41.0,
    "gemma431b" -> 38.7,
    "deepseekv4flash" -> 38.7,
    "nemotron3ultra" -> 37.6,
    "mistralmedium35" -> 35.4,
    "claudehaiku45" -> 32.6,
    "nova20pro" -> 30.4,
    "gptoss120b" -> 28.6,
    "gptoss20b" -> 18.5,
    "k2thinkv2" -> 15.5,
    "solarpro3" -> 13.3,
  ).sortBy(-_._1.length)

  def normalize(s: String): String = s.toLowerCase.replaceAll("[^a-z0-9]", "")

  def codingScoreFor(model: String): Option[Double] = {
    val n = normalize(model)
    if (n.isEmpty) None else codingScores.collectFirst { case (pat, score) if n.contains(pat) => score }
  }
}

case class RouterCandidate(provider: AiProvider, model: String, score: Option[Double], cost: Option[BigDecimal])

class OtoroshiRouterChatClient(provider: AiProvider) extends ChatClient {

  override def computeModel(payload: JsValue): Option[String] = None
  override def isOpenAi: Boolean = true
  override def isCohere: Boolean = false
  override def isAnthropic: Boolean = false

  override def listModels(raw: Boolean, attrs: TypedMap)(implicit ec: ExecutionContext): Future[Either[JsValue, List[String]]] = {
    Right(List("code-router")).vfuture
  }

  private def candidateModel(p: AiProvider): String =
    p.options.select("model").asOptString.getOrElse("--")

  // blended price = 1 x input + 3 x output per token (coding generations are output-heavy)
  private def candidateCost(p: AiProvider, model: String)(implicit env: Env): Option[BigDecimal] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    val litellmProvider = ext.costsTracking.getProvider(p.provider)
    litellmProvider.flatMap(lp => ext.costsTracking.getModel(lp, model))
      .orElse(ext.costsTracking.searchModel(m => m.nameWithoutProvider.equalsIgnoreCase(model) || m.name.equalsIgnoreCase(model)))
      .map(m => m.input_cost_per_token + (m.output_cost_per_token * 3))
  }

  // resolve refs -> candidates, then order them by the routing policy:
  //   1. qualifying (score >= required), cheapest first   <- the rational "strong coder without overspending" pick
  //   2. remaining known-score candidates, best score first
  //   3. unknown-score candidates, in declared order       <- last-resort fallbacks
  private def orderedCandidates(originalBody: JsValue)(implicit env: Env): Seq[AiProvider] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    val refs: Seq[String] = provider.options.select("code_router_refs").asOpt[Seq[String]]
      .orElse(provider.options.select("code_router_refs").asOpt[Seq[JsObject]].map(_.map(_.select("code_router_ref").asString)))
      .getOrElse(Seq.empty)
    val resolved: Seq[RouterCandidate] = refs.flatMap(r => ext.states.provider(r)).map { p =>
      val model = candidateModel(p)
      RouterCandidate(p, model, OtoroshiRouterChatClient.codingScoreFor(model), candidateCost(p, model))
    }
    if (resolved.isEmpty) {
      Seq.empty
    } else {
      val rawMin = originalBody.select("min_coding_score").asOpt[Double]
        .orElse(provider.options.select("min_coding_score").asOpt[Double])
        .getOrElse(0.5)
      val minScore01 = math.max(0.0, math.min(1.0, rawMin))
      val knownScores = resolved.flatMap(_.score)
      val maxScore = if (knownScores.isEmpty) 0.0 else knownScores.max
      val requiredScore = minScore01 * maxScore
      val (qualifying, rest) = resolved.partition(_.score.exists(_ >= requiredScore))
      def costKey(c: RouterCandidate): BigDecimal = c.cost.getOrElse(BigDecimal(Double.MaxValue))
      val qualifyingOrdered = qualifying.sortBy(c => (costKey(c), -c.score.getOrElse(0.0)))
      val knownRest = rest.filter(_.score.isDefined).sortBy(c => (-c.score.get, costKey(c)))
      val unknownRest = rest.filter(_.score.isEmpty)
      (qualifyingOrdered ++ knownRest ++ unknownRest).map(_.provider)
    }
  }

  // try the ordered candidates one by one, cascading to the next on error/exception
  private def execute[T](originalBody: JsValue)(f: (ChatClient, JsValue) => Future[Either[JsValue, T]])(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, T]] = {
    val ordered = orderedCandidates(originalBody)
    if (ordered.isEmpty) {
      Json.obj("error" -> "no candidate provider configured for the otoroshi code-router").leftf
    } else {
      // strip router-only knobs and the "code-router" model so each candidate uses its own configured model
      val cleanBody = originalBody.asObject - "model" - "min_coding_score"
      def attempt(remaining: Seq[AiProvider], lastErr: JsValue): Future[Either[JsValue, T]] = remaining match {
        case Seq() => lastErr.leftf
        case p +: tail => p.getChatClient() match {
          case None => attempt(tail, Json.obj("error" -> s"no chat client for provider ${p.id}"))
          case Some(client) => f(client, cleanBody).flatMap {
            case Left(err) => attempt(tail, err)
            case Right(resp) => resp.rightf
          }.recoverWith {
            case t: Throwable => attempt(tail, Json.obj("error" -> s"router candidate failed: ${t.getMessage}"))
          }
        }
      }
      attempt(ordered, Json.obj("error" -> "no candidate succeeded"))
    }
  }

  override def call(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    execute(originalBody)((client, body) => client.call(prompt, attrs, body))
  }

  override def stream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    execute(originalBody)((client, body) => client.stream(prompt, attrs, body))
  }

  override def completion(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    execute(originalBody)((client, body) => client.completion(prompt, attrs, body))
  }

  override def completionStream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    execute(originalBody)((client, body) => client.completionStream(prompt, attrs, body))
  }
}
