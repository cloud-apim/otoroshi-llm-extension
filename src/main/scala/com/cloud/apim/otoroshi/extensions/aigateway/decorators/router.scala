package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import akka.stream.scaladsl.Source
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatClient, ChatMessage, ChatPrompt, ChatResponse, ChatResponseChunk}
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}

// The "otoroshi" provider is a router: like the load balancer it references lists of existing otoroshi
// providers, but instead of round-robin it routes automatically to the "best" candidate. It exposes two
// models:
//   - "code-router" (à la openrouter/pareto-code): a strong coder without overspending. Quality comes from
//     a curated coding-index table (Artificial Analysis), cost from the litellm price catalog. Picks the
//     cheapest candidate above a quality floor (min_coding_score). Candidates: options.code_router_refs.
//   - "auto-router" (à la openrouter/auto): prompt-aware per-request routing. A judge LLM reads the prompt
//     and the candidate list (quality + cost) and picks the best-suited model, honoring a
//     cost_quality_tradeoff (0-10). Candidates: options.auto_router_refs, judge: options.auto_router_classifier_ref.
// Both cascade to the next-best candidate on failure, like the provider-fallback decorator.
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
    Right(List("code-router", "auto-router")).vfuture
  }

  private def candidateModel(p: AiProvider): String =
    p.options.select("model").asOptString.getOrElse("--")

  // blended price = 1 x input + 3 x output per token (generations are output-heavy)
  private def candidateCost(p: AiProvider, model: String)(implicit env: Env): Option[BigDecimal] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    val litellmProvider = ext.costsTracking.getProvider(p.provider)
    litellmProvider.flatMap(lp => ext.costsTracking.getModel(lp, model))
      .orElse(ext.costsTracking.searchModel(m => m.nameWithoutProvider.equalsIgnoreCase(model) || m.name.equalsIgnoreCase(model)))
      .map(m => m.input_cost_per_token + (m.output_cost_per_token * 3))
  }

  // resolve a refs list (Seq[String] of ids, or Seq[{ref}]) into candidates, skipping self-references
  private def resolveCandidates(refsKey: String, refKey: String)(implicit env: Env): Seq[RouterCandidate] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    val refs: Seq[String] = provider.options.select(refsKey).asOpt[Seq[String]]
      .orElse(provider.options.select(refsKey).asOpt[Seq[JsObject]].map(_.map(_.select(refKey).asString)))
      .getOrElse(Seq.empty)
    refs.flatMap(r => ext.states.provider(r))
      .filterNot(_.id == provider.id) // avoid routing to ourselves (infinite loop)
      .map { p =>
        val model = candidateModel(p)
        RouterCandidate(p, model, OtoroshiRouterChatClient.codingScoreFor(model), candidateCost(p, model))
      }
  }

  ////////////////////////////////////////////////////////////////////////////////////////////////////////
  //  code-router : cheapest candidate above a quality floor, then cascade by quality/cost
  ////////////////////////////////////////////////////////////////////////////////////////////////////////

  private def codeOrderedCandidates(originalBody: JsValue)(implicit env: Env): Seq[AiProvider] = {
    val resolved = resolveCandidates("code_router_refs", "code_router_ref")
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

  ////////////////////////////////////////////////////////////////////////////////////////////////////////
  //  auto-router : prompt-aware pick via a judge LLM, with a cost/quality tradeoff fallback ordering
  ////////////////////////////////////////////////////////////////////////////////////////////////////////

  // desirability ordering driven by cost_quality_tradeoff (0 = quality first, 10 = cheapest first)
  private def tradeoffOrdered(cands: Seq[RouterCandidate], tradeoff: Double): Seq[RouterCandidate] = {
    val maxScore = cands.flatMap(_.score).reduceOption(_ max _).getOrElse(1.0).max(1e-9)
    val maxCost = cands.flatMap(_.cost).reduceOption(_ max _).getOrElse(BigDecimal(1)).max(BigDecimal("0.000000000001"))
    val qW = (10.0 - tradeoff) / 10.0
    val cW = tradeoff / 10.0
    def desirability(c: RouterCandidate): Double = {
      val sN = c.score.map(_ / maxScore).getOrElse(0.0)
      val cN = c.cost.map(v => (v / maxCost).toDouble).getOrElse(1.0)
      (qW * sN) - (cW * cN)
    }
    cands.sortBy(c => -desirability(c))
  }

  private def judgeClient(cands: Seq[RouterCandidate])(implicit env: Env): Option[ChatClient] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    provider.options.select("auto_router_classifier_ref").asOptString
      .flatMap(r => ext.states.provider(r)).filterNot(_.id == provider.id).flatMap(_.getChatClient())
      .orElse {
        // fallback: use the cheapest candidate as the judge
        cands.filter(_.cost.isDefined).sortBy(_.cost.get).headOption.orElse(cands.headOption).flatMap(_.provider.getChatClient())
      }
  }

  private def parseIndex(text: String, size: Int): Option[Int] = {
    "\\d+".r.findFirstIn(text.trim).flatMap(s => scala.util.Try(s.toInt).toOption).filter(i => i >= 0 && i < size)
  }

  private def pickWithJudge(prompt: ChatPrompt, cands: Seq[RouterCandidate], tradeoff: Double)(implicit ec: ExecutionContext, env: Env): Future[Option[RouterCandidate]] = {
    judgeClient(cands) match {
      case None => Future.successful(None)
      case Some(jclient) =>
        val promptText = prompt.messages.map(m => s"${m.role}: ${m.wholeTextContent}").mkString("\n").take(4000)
        val candidateList = cands.zipWithIndex.map { case (c, i) =>
          val q = c.score.map(s => f"$s%.1f").getOrElse("unknown")
          val cost = c.cost.map(_.bigDecimal.toPlainString).getOrElse("unknown")
          s"[$i] model=${c.model} provider=${c.provider.provider} coding_quality=$q blended_cost_per_token=$cost"
        }.mkString("\n")
        val sys =
          s"""You are a model router. Pick the single best candidate model to answer the user prompt.
             |Consider what the prompt needs (coding, reasoning, math, creative writing, vision, long context, etc.) and the cost/quality tradeoff.
             |cost_quality_tradeoff = ${tradeoff.toInt} on a 0-10 scale (0 = best quality regardless of cost, 10 = cheapest acceptable, 7 = balanced).
             |Candidates:
             |${candidateList}
             |Answer with ONLY the index number of the chosen candidate (for example: 0). No other text.""".stripMargin
        val classifierPrompt = ChatPrompt(Seq(
          ChatMessage.input("system", sys, None, Json.obj("role" -> "system", "content" -> sys)),
          ChatMessage.userStrInput("User prompt to route:\n" + promptText)
        ))
        val jbody = Json.obj("temperature" -> 0, "max_tokens" -> 16)
        jclient.call(classifierPrompt, TypedMap.empty, jbody).map {
          case Right(resp) => parseIndex(resp.headGeneration.message.content, cands.size).map(cands.apply)
          case Left(_) => None
        }.recover { case _ => None }
    }
  }

  private def autoOrderedCandidates(prompt: ChatPrompt, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Seq[AiProvider]] = {
    val cands = resolveCandidates("auto_router_refs", "auto_router_ref")
    if (cands.isEmpty) {
      Future.successful(Seq.empty)
    } else {
      val rawTradeoff = originalBody.select("cost_quality_tradeoff").asOpt[Double]
        .orElse(provider.options.select("cost_quality_tradeoff").asOpt[Double])
        .getOrElse(7.0)
      val tradeoff = math.max(0.0, math.min(10.0, rawTradeoff))
      val fallbackOrder = tradeoffOrdered(cands, tradeoff)
      pickWithJudge(prompt, cands, tradeoff).map {
        case Some(chosen) => chosen.provider +: fallbackOrder.filterNot(_.provider.id == chosen.provider.id).map(_.provider)
        case None => fallbackOrder.map(_.provider)
      }
    }
  }

  ////////////////////////////////////////////////////////////////////////////////////////////////////////
  //  dispatch + cascade
  ////////////////////////////////////////////////////////////////////////////////////////////////////////

  private def execute[T](prompt: ChatPrompt, originalBody: JsValue)(f: (ChatClient, JsValue) => Future[Either[JsValue, T]])(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, T]] = {
    val requestedModel = originalBody.select("model").asOptString.getOrElse("code-router").toLowerCase
    val orderedF: Future[Seq[AiProvider]] =
      if (requestedModel.contains("auto")) autoOrderedCandidates(prompt, originalBody)
      else Future.successful(codeOrderedCandidates(originalBody))
    orderedF.flatMap { ordered =>
      if (ordered.isEmpty) {
        Json.obj("error" -> "no candidate provider configured for the otoroshi router").leftf
      } else {
        // strip router-only knobs and the router model so each candidate uses its own configured model
        val cleanBody = originalBody.asObject - "model" - "min_coding_score" - "cost_quality_tradeoff"
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
  }

  override def call(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    execute(prompt, originalBody)((client, body) => client.call(prompt, attrs, body))
  }

  override def stream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    execute(prompt, originalBody)((client, body) => client.stream(prompt, attrs, body))
  }

  override def completion(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    execute(prompt, originalBody)((client, body) => client.completion(prompt, attrs, body))
  }

  override def completionStream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    execute(prompt, originalBody)((client, body) => client.completionStream(prompt, attrs, body))
  }
}
