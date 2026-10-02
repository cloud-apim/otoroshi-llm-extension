package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import org.apache.pekko.stream.scaladsl.Source
import com.cloud.apim.otoroshi.extensions.aigateway.catalog.CodingIndex
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatCallKind, ChatClient, ChatMessage, ChatPrompt, ChatResponse, ChatResponseChunk, DecisionModelClient, DecisionModelClientInputOptions, DecisionRequests, KindBasedChatClient}
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.*

import scala.concurrent.{ExecutionContext, Future}

// The "otoroshi" provider is a router: like the load balancer it references lists of existing otoroshi
// providers, but instead of round-robin it routes automatically to the "best" candidate. It exposes two
// models:
//   - "code-router" (à la openrouter/pareto-code): a strong coder without overspending. Quality comes from
//     the Coding Index of Artificial Analysis (bundled data file), cost from the litellm price catalog. Picks the
//     cheapest candidate above a quality floor (min_coding_score). Candidates: options.code_router_refs.
//   - "auto-router" (à la openrouter/auto): prompt-aware per-request routing. A judge LLM reads the prompt
//     and the candidate list (quality + cost) and picks the best-suited model, honoring a
//     cost_quality_tradeoff (0-10). Candidates: options.auto_router_refs, judge: options.auto_router_classifier_ref.
//   - "smart-router": a decision model (options.decision_model_ref) rates how demanding the request is, and the
//     router does the rest with what it knows of its candidates: the more demanding the request, the higher the
//     quality floor, and the cheapest candidate above it answers. Candidates: options.smart_router_refs.
//   - "intent-router": the candidates are described by whoever configures the router (options.intent_router_refs,
//     `{ ref, model, description }`), and the decision model picks the one whose description fits the request.
// All of them cascade to the next-best candidate on failure, like the provider-fallback decorator.
object OtoroshiRouterChatClient {

  // the Coding Index of Artificial Analysis, bundled in `data/coding-index.json` (see `CodingIndex`)
  def codingScoreFor(model: String): Option[Double] = CodingIndex.bundled.scoreFor(model)

  // smart-router: the question asked about a request, and its levels from the least to the most demanding
  val difficultyQuestion = "difficulty"
  val difficultyInstructions = "How demanding is this request for the language model that will answer it ?"
  val difficultyLevels: Seq[String] = Seq(
    "Trivial: a greeting, small talk, a short factual question, a simple reformulation",
    "Simple: a routine task with an obvious answer, a short text to write, translate or summarize",
    "Moderate: several steps, some domain knowledge, a function, a query or a structured document to write",
    "Hard: careful reasoning, a subtle bug, a design to work out, a long or technical document to analyse",
    "Expert: research level reasoning, a large or intricate piece of software, a problem with many constraints",
  )
  // the difficulty a request is given when the decision model could not rate it
  val defaultDifficulty = 0.5

  // how demanding a request is, from 0 to 1: where its score stands on the scale of the levels
  def difficultyOf(answer: JsValue): Option[Double] = {
    val probabilities = answer.select("probabilities").asOpt[JsObject].map(_.value.toSeq.flatMap { case (level, p) =>
      for (l <- level.toIntOption; v <- p.asOpt[Double]) yield (l, v)
    }).getOrElse(Seq.empty)
    val levels = math.max(difficultyLevels.size, probabilities.map(_._1 + 1).maxOption.getOrElse(0))
    answer.select("score").asOpt[Double]
      .orElse(if (probabilities.isEmpty) None else Some(probabilities.map { case (l, p) => l * p }.sum / probabilities.map(_._2).sum.max(1e-9)))
      .filterNot(_.isNaN)
      .map(score => math.max(0.0, math.min(1.0, score / (levels - 1))))
  }

  // intent-router: the question asked about a request, its options being the candidates as they were described
  val intentQuestion = "intent"
  val intentInstructions = "Which of these options is the best suited to answer this request ?"
}

case class RouterCandidate(provider: AiProvider, model: String, score: Option[Double], cost: Option[BigDecimal])

// a candidate of the intent-router: its name is the option the decision model picks, its description what it reads
case class IntentCandidate(name: String, description: String, provider: AiProvider)

class OtoroshiRouterChatClient(provider: AiProvider) extends KindBasedChatClient {

  override def computeModel(payload: JsValue): Option[String] = None
  override def isOpenAi: Boolean = true
  override def isCohere: Boolean = false
  override def isAnthropic: Boolean = false

  override def listModels(raw: Boolean, attrs: TypedMap)(using ec: ExecutionContext): Future[Either[JsValue, List[String]]] = {
    Right(List("code-router", "auto-router", "smart-router", "intent-router", "fusion-router")).vfuture
  }

  private def candidateModel(p: AiProvider): String =
    p.options.select("model").asOptString.getOrElse("--")

  // blended price = 1 x input + 3 x output per token (generations are output-heavy)
  private def candidateCost(p: AiProvider, model: String)(using env: Env): Option[BigDecimal] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    val litellmProvider = ext.costsTracking.getProvider(p.provider)
    litellmProvider.flatMap(lp => ext.costsTracking.getModel(lp, model))
      .orElse(ext.costsTracking.searchModel(m => m.nameWithoutProvider.equalsIgnoreCase(model) || m.name.equalsIgnoreCase(model)))
      .map(m => m.input_cost_per_token + (m.output_cost_per_token * 3))
  }

  // resolve a refs list (ids, or objects { ref, model }) into candidates, skipping self-references. A candidate
  // with a model is its provider serving that model instead of its default one, so a provider can be a candidate
  // several times with different models, each one scored and priced on its own model
  private def resolveCandidates(refsKey: String, refKey: String)(using env: Env): Seq[RouterCandidate] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    val refs: Seq[(String, Option[String])] = provider.options.select(refsKey).asOpt[Seq[JsValue]].getOrElse(Seq.empty).flatMap {
      case JsString(id) => Some((id, None))
      case obj: JsObject => obj.select("ref").asOptString.orElse(obj.select(refKey).asOptString).map(id => (id, obj.select("model").asOptString))
      case _ => None
    }
    refs.flatMap { case (r, model) => ext.states.provider(r).map(_.withModel(model)) }
      .filterNot(_.id == provider.id) // avoid routing to ourselves (infinite loop)
      .map { p =>
        val model = candidateModel(p)
        RouterCandidate(p, model, OtoroshiRouterChatClient.codingScoreFor(model), candidateCost(p, model))
      }
  }

  ////////////////////////////////////////////////////////////////////////////////////////////////////////
  //  code-router : cheapest candidate above a quality floor, then cascade by quality/cost
  ////////////////////////////////////////////////////////////////////////////////////////////////////////

  private def codeOrderedCandidates(originalBody: JsValue)(using env: Env): Seq[AiProvider] = {
    val resolved = resolveCandidates("code_router_refs", "code_router_ref")
    if (resolved.isEmpty) {
      Seq.empty
    } else {
      val rawMin = originalBody.select("min_coding_score").asOpt[Double]
        .orElse(provider.options.select("min_coding_score").asOpt[Double])
        .getOrElse(0.5)
      floorOrdered(resolved, rawMin).map(_.provider)
    }
  }

  // The cheapest candidate above a quality floor first (`floor` from 0 to 1, relative to the best candidate), then
  // the other ones above it by price, the ones below it by quality, and the ones nobody scored
  private def floorOrdered(resolved: Seq[RouterCandidate], floor: Double): Seq[RouterCandidate] = {
    val minScore01 = math.max(0.0, math.min(1.0, floor))
    val knownScores = resolved.flatMap(_.score)
    val maxScore = if (knownScores.isEmpty) 0.0 else knownScores.max
    val requiredScore = minScore01 * maxScore
    val (qualifying, rest) = resolved.partition(_.score.exists(_ >= requiredScore))
    def costKey(c: RouterCandidate): BigDecimal = c.cost.getOrElse(BigDecimal(Double.MaxValue))
    val qualifyingOrdered = qualifying.sortBy(c => (costKey(c), -c.score.getOrElse(0.0)))
    val knownRest = rest.filter(_.score.isDefined).sortBy(c => (-c.score.get, costKey(c)))
    val unknownRest = rest.filter(_.score.isEmpty)
    qualifyingOrdered ++ knownRest ++ unknownRest
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

  // a single provider reference of the router options (`<name>_ref`), with its optional model (`<name>_model`)
  private def auxProvider(refKey: String)(using env: Env): Option[AiProvider] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    provider.options.select(refKey).asOptString
      .flatMap(r => ext.states.provider(r)).filterNot(_.id == provider.id)
      .map(_.withModel(provider.options.select(refKey.stripSuffix("_ref") + "_model").asOptString))
  }

  private def judgeClient(cands: Seq[RouterCandidate])(using env: Env): Option[ChatClient] = {
    auxProvider("auto_router_classifier_ref").flatMap(_.getChatClient())
      .orElse {
        // fallback: use the cheapest candidate as the judge
        cands.filter(_.cost.isDefined).sortBy(_.cost.get).headOption.orElse(cands.headOption).flatMap(_.provider.getChatClient())
      }
  }

  private def parseIndex(text: String, size: Int): Option[Int] = {
    "\\d+".r.findFirstIn(text.trim).flatMap(s => scala.util.Try(s.toInt).toOption).filter(i => i >= 0 && i < size)
  }

  // OpenRouter-style allowed_models wildcard patterns (e.g. "anthropic/*", "openai/gpt-5*", "openai/gpt-5.1").
  // A candidate matches if a pattern matches either "<providerKind>/<model>" or the bare "<model>".
  private def globToRegex(glob: String): java.util.regex.Pattern = {
    val parts = glob.trim.split("\\*", -1).map(java.util.regex.Pattern.quote)
    java.util.regex.Pattern.compile("(?i)^" + parts.mkString(".*") + "$")
  }

  private def matchesAllowed(patterns: Seq[String], c: RouterCandidate): Boolean = {
    if (patterns.isEmpty) true
    else {
      val ids = Seq(s"${c.provider.provider}/${c.model}", c.model)
      patterns.exists { p =>
        val rx = globToRegex(p)
        ids.exists(id => rx.matcher(id).matches())
      }
    }
  }

  private def pickWithJudge(prompt: ChatPrompt, attrs: TypedMap, cands: Seq[RouterCandidate], tradeoff: Double)(using ec: ExecutionContext, env: Env): Future[Option[RouterCandidate]] = {
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
        jclient.call(classifierPrompt, ChildCall.attrs(attrs), jbody).map {
          case Right(resp) => parseIndex(resp.headGeneration.message.content, cands.size).map(cands.apply)
          case Left(_) => None
        }.recover { case _ => None }
    }
  }

  private def autoOrderedCandidates(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(using ec: ExecutionContext, env: Env): Future[Seq[AiProvider]] = {
    val allCands = resolveCandidates("auto_router_refs", "auto_router_ref")
    // optional allowed_models filter (wildcard patterns), from the request body or the provider config
    val allowed = originalBody.select("allowed_models").asOpt[Seq[String]]
      .orElse(provider.options.select("allowed_models").asOpt[Seq[String]])
      .getOrElse(Seq.empty).map(_.trim).filter(_.nonEmpty)
    val cands = if (allowed.isEmpty) allCands else allCands.filter(c => matchesAllowed(allowed, c))
    if (cands.isEmpty) {
      Future.successful(Seq.empty)
    } else {
      val rawTradeoff = originalBody.select("cost_quality_tradeoff").asOpt[Double]
        .orElse(provider.options.select("cost_quality_tradeoff").asOpt[Double])
        .getOrElse(7.0)
      val tradeoff = math.max(0.0, math.min(10.0, rawTradeoff))
      val fallbackOrder = tradeoffOrdered(cands, tradeoff)
      pickWithJudge(prompt, attrs, cands, tradeoff).map {
        case Some(chosen) => chosen.provider +: fallbackOrder.filterNot(c => c.provider.id == chosen.provider.id && c.model == chosen.model).map(_.provider)
        case None => fallbackOrder.map(_.provider)
      }
    }
  }

  ////////////////////////////////////////////////////////////////////////////////////////////////////////
  //  smart-router, intent-router : a decision model reads the request
  ////////////////////////////////////////////////////////////////////////////////////////////////////////

  // the decision model of the router (`decision_model_ref`), serving `decision_model_model` or its own model
  private def decisionClient()(using env: Env): Option[DecisionModelClient] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    provider.options.select("decision_model_ref").asOptString.map(_.trim).filter(_.nonEmpty)
      .flatMap(ref => ext.states.decisionModel(ref))
      .flatMap(_.withModel(provider.options.select("decision_model_model").asOptString).getDecisionModelClient())
  }

  // What the decision model reads of the request: the end of the conversation, which is where the request is.
  // A decision is priced per input token: a long conversation is not sent whole
  private def stateOf(prompt: ChatPrompt): JsValue = {
    val messages = prompt.messages.map(m => (m.role, m.wholeTextContent.take(4000))).filter(_._2.trim.nonEmpty)
    val sizes = messages.reverse.scanLeft(0)(_ + _._2.length).tail
    val kept = math.max(1, sizes.takeWhile(_ <= 8000).size)
    JsArray(messages.takeRight(kept).map { case (role, content) => Json.obj("role" -> role, "content" -> content) })
  }

  // The answer of the decision model to one question about the request, none when it could not be asked: the
  // routers then follow their fallback order. A call of its own, counted for the caller
  private def ask(prompt: ChatPrompt, attrs: TypedMap, name: String, question: JsObject)(using ec: ExecutionContext, env: Env): Future[Option[JsObject]] = {
    decisionClient() match {
      case None => Future.successful(None)
      case Some(client) =>
        val opts = DecisionModelClientInputOptions(stateOf(prompt), Json.obj(name -> question))
        client.decide(opts, opts.json.asObject, ChildCall.attrs(attrs)).map {
          case Right(res) => res.answers.select(name).asOpt[JsObject]
          case Left(_) => None
        }.recover { case _ => None }
    }
  }

  private def bounded(value: Option[Double], default: Double): Double = math.max(0.0, math.min(1.0, value.filterNot(_.isNaN).getOrElse(default)))

  // smart-router: the more demanding the request, the higher the quality floor, from `smart_router_min_score` for
  // a trivial request to `smart_router_max_score` for the most demanding one. The rest is the code-router
  private def smartOrderedCandidates(prompt: ChatPrompt, attrs: TypedMap)(using ec: ExecutionContext, env: Env): Future[Seq[AiProvider]] = {
    import OtoroshiRouterChatClient.*
    val resolved = resolveCandidates("smart_router_refs", "smart_router_ref")
    if (resolved.size < 2) {
      Future.successful(resolved.map(_.provider))
    } else {
      val question = Json.obj("type" -> DecisionRequests.Score, "instructions" -> difficultyInstructions, "criteria" -> difficultyLevels)
      ask(prompt, attrs, difficultyQuestion, question).map { answer =>
        val difficulty = answer.flatMap(difficultyOf).getOrElse(defaultDifficulty)
        val low = bounded(provider.options.select("smart_router_min_score").asOpt[Double], 0.0)
        val high = math.max(low, bounded(provider.options.select("smart_router_max_score").asOpt[Double], 1.0))
        floorOrdered(resolved, low + difficulty * (high - low)).map(_.provider)
      }
    }
  }

  // the candidates of the intent-router, in the order they were given: `{ ref, model, description, name }`
  private def intentCandidates()(using env: Env): Seq[IntentCandidate] = {
    val ext = env.adminExtensions.extension[AiExtension].get
    val described = provider.options.select("intent_router_refs").asOpt[Seq[JsValue]].getOrElse(Seq.empty).flatMap {
      case JsString(id) => Some((id, Json.obj()))
      case obj: JsObject => obj.select("ref").asOptString.orElse(obj.select("intent_router_ref").asOptString).map(id => (id, obj))
      case _ => None
    }
    described.flatMap { case (ref, entry) =>
      ext.states.provider(ref).filterNot(_.id == provider.id).map(_.withModel(entry.select("model").asOptString)).map(p => (p, entry))
    }.zipWithIndex.foldLeft(Seq.empty[IntentCandidate]) { case (candidates, ((p, entry), idx)) =>
      // the name is an option of the question: each candidate has its own
      val wanted = entry.select("name").asOptString.map(_.trim).filter(_.nonEmpty).getOrElse(s"option_${idx + 1}")
      val name = if (candidates.exists(_.name == wanted)) s"${wanted}_${idx + 1}" else wanted
      // a candidate nobody described is only known by its model
      val description = entry.select("description").asOptString.map(_.trim).filter(_.nonEmpty).getOrElse(s"The model ${candidateModel(p)}")
      candidates :+ IntentCandidate(name, description, p)
    }
  }

  // intent-router: the candidate the decision model picks, then the other ones from the most to the least probable.
  // Without an answer, or with one the model is not sure enough of (`intent_router_min_confidence`), the candidates
  // answer in the order they were given: the first one is the default
  private def intentOrderedCandidates(prompt: ChatPrompt, attrs: TypedMap)(using ec: ExecutionContext, env: Env): Future[Seq[AiProvider]] = {
    import OtoroshiRouterChatClient.*
    val candidates = intentCandidates()
    if (candidates.size < 2) {
      Future.successful(candidates.map(_.provider))
    } else {
      val instructions = provider.options.select("intent_router_instructions").asOptString.map(_.trim).filter(_.nonEmpty).getOrElse(intentInstructions)
      val question = Json.obj(
        "type" -> DecisionRequests.Choice,
        "instructions" -> instructions,
        "criteria" -> JsObject(candidates.map(c => c.name -> JsString(c.description))),
      )
      ask(prompt, attrs, intentQuestion, question).map { answer =>
        val confident = answer.exists { a =>
          provider.options.select("intent_router_min_confidence").asOpt[Double].forall(min => a.select("confidence").asOpt[Double].exists(_ >= min))
        }
        val chosen = answer.filter(_ => confident).flatMap(_.select("choice").asOptString).flatMap(name => candidates.find(_.name == name))
        chosen.fold(candidates.map(_.provider)) { first =>
          val probabilities = answer.flatMap(_.select("probabilities").asOpt[Map[String, Double]]).getOrElse(Map.empty)
          (first +: candidates.filterNot(_ == first).sortBy(c => -probabilities.getOrElse(c.name, 0.0))).map(_.provider)
        }
      }
    }
  }

  ////////////////////////////////////////////////////////////////////////////////////////////////////////
  //  fusion-router : panel (parallel) -> judge (structured analysis) -> synthesizer (final answer)
  ////////////////////////////////////////////////////////////////////////////////////////////////////////

  private def fusionBody(originalBody: JsValue): JsObject =
    originalBody.asObject - "model" - "min_coding_score" - "cost_quality_tradeoff" - "allowed_models" - "messages"

  private def promptText(prompt: ChatPrompt): String =
    prompt.messages.map(m => s"${m.role}: ${m.wholeTextContent}").mkString("\n").take(8000)

  // a configured aux provider (judge or synthesizer), falling back to the highest-quality panel member
  private def fusionAuxClient(refKey: String, panel: Seq[RouterCandidate])(using env: Env): Option[(AiProvider, ChatClient)] = {
    auxProvider(refKey).flatMap(p => p.getChatClient().map(c => (p, c)))
      .orElse(panel.sortBy(c => -c.score.getOrElse(0.0)).headOption.flatMap(c => c.provider.getChatClient().map(cl => (c.provider, cl))))
  }

  // run the whole fusion pipeline up to (but excluding) the final synthesis call, returning the
  // synthesizer client + the synthesis prompt + the body to call it with.
  private def prepareFusion(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, (AiProvider, ChatClient, ChatPrompt, JsObject)]] = {
    val panel = resolveCandidates("fusion_router_refs", "fusion_router_ref").take(8)
    if (panel.isEmpty) {
      Json.obj("error" -> "no panel provider configured for the otoroshi fusion-router (set options.fusion_router_refs)").leftf
    } else {
      val cleanBody = fusionBody(originalBody)
      val question = promptText(prompt)
      // 1. panel: query all members in parallel, keep the ones that succeed
      val panelF: Future[Seq[(String, String)]] = Future.sequence(panel.map { c =>
        c.provider.getChatClient() match {
          case None => Future.successful(Option.empty[(String, String)])
          case Some(client) => client.call(prompt, ChildCall.attrs(attrs), cleanBody).map {
            case Right(resp) => Some((s"${c.provider.provider}/${c.model}", resp.headGeneration.message.content))
            case Left(_) => None
          }.recover { case _ => None }
        }
      }).map(_.flatten)
      panelF.flatMap { panelResponses =>
        if (panelResponses.isEmpty) {
          Json.obj("error" -> "all fusion-router panel members failed").leftf
        } else {
          val panelText = panelResponses.zipWithIndex.map { case ((label, text), i) =>
            s"### Panel response ${i + 1} (${label})\n${text.take(6000)}"
          }.mkString("\n\n")
          // 2. judge: structured comparison of the panel responses (best-effort, falls back to raw panel text)
          val judgeAnalysisF: Future[String] = fusionAuxClient("fusion_router_judge_ref", panel) match {
            case None => Future.successful(panelText)
            case Some((_, judge)) =>
              val jsys = "You are an impartial judge in a multi-model deliberation. Compare the panel responses below — do NOT merge or rewrite them. Identify: (1) consensus points all/most agree on (higher confidence), (2) disagreements, (3) unique insights from individual responses, (4) gaps or blind spots none addressed. Return a concise structured analysis."
              val juser = s"User request:\n${question}\n\nPanel responses:\n${panelText}"
              val jprompt = ChatPrompt(Seq(
                ChatMessage.input("system", jsys, None, Json.obj("role" -> "system", "content" -> jsys)),
                ChatMessage.userStrInput(juser)
              ))
              judge.call(jprompt, ChildCall.attrs(attrs), cleanBody ++ Json.obj("temperature" -> 0)).map {
                case Right(resp) => resp.headGeneration.message.content
                case Left(_) => panelText
              }.recover { case _ => panelText }
          }
          // 3. synthesis: the outer model produces the final answer from the analysis, answering the original request
          judgeAnalysisF.map { analysis =>
            val result: Either[JsValue, (AiProvider, ChatClient, ChatPrompt, JsObject)] = fusionAuxClient("fusion_router_synthesizer_ref", panel) match {
              case None => Left(Json.obj("error" -> "no synthesizer available for the otoroshi fusion-router"))
              case Some((synthProvider, synth)) =>
                val ssys =
                  s"""You are the final synthesizer in a multi-model deliberation. Using the structured analysis below (consensus, disagreements, unique insights, gaps) from a panel of expert models, produce the best, most accurate and complete answer to the user's request. Prefer consensus, resolve disagreements with reasoning, incorporate unique insights, and fill the gaps. Do not mention the panel, the judge, or this deliberation process — just give the answer.
                     |
                     |Structured analysis:
                     |${analysis.take(12000)}""".stripMargin
                val synthMessages = ChatMessage.input("system", ssys, None, Json.obj("role" -> "system", "content" -> ssys)) +: prompt.messages
                Right((synthProvider, synth, ChatPrompt(synthMessages), cleanBody))
            }
            result
          }
        }
      }
    }
  }

  private def fusionCall(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    prepareFusion(prompt, attrs, originalBody).flatMap {
      case Left(err) => err.leftf
      case Right((synth, client, synthPrompt, body)) =>
        delegate(attrs, originalBody, synth, client, body)
        client.call(synthPrompt, attrs, body)
    }
  }

  private def fusionStream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, ?]]] = {
    prepareFusion(prompt, attrs, originalBody).flatMap {
      case Left(err) => err.leftf
      case Right((synth, client, synthPrompt, body)) =>
        delegate(attrs, originalBody, synth, client, body)
        client.stream(synthPrompt, attrs, body)
    }
  }

  ////////////////////////////////////////////////////////////////////////////////////////////////////////
  //  dispatch + cascade
  ////////////////////////////////////////////////////////////////////////////////////////////////////////

  // the candidate serves the call the consumers made to the router, if they were allowed to
  private def delegate(attrs: TypedMap, originalBody: JsValue, candidate: AiProvider, client: ChatClient, body: JsValue): Unit = {
    ModelConstraints.delegate(attrs, ModelTarget.of(provider), originalBody.select("model").asOptString, candidate, client, body)
    HandOver.mark(attrs, provider)
  }

  private def isFusion(originalBody: JsValue): Boolean =
    originalBody.select("model").asOptString.exists(_.toLowerCase.contains("fusion"))

  private def execute[T](prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(f: (ChatClient, JsValue) => Future[Either[JsValue, T]])(using ec: ExecutionContext, env: Env): Future[Either[JsValue, T]] = {
    val requestedModel = originalBody.select("model").asOptString.getOrElse("code-router").toLowerCase
    val (routerModel, refsField) =
      if (requestedModel.contains("auto")) ("auto-router", "auto_router_refs")
      else if (requestedModel.contains("smart")) ("smart-router", "smart_router_refs")
      else if (requestedModel.contains("intent")) ("intent-router", "intent_router_refs")
      else ("code-router", "code_router_refs")
    val orderedF: Future[Seq[AiProvider]] = routerModel match {
      case "auto-router" => autoOrderedCandidates(prompt, attrs, originalBody)
      case "smart-router" => smartOrderedCandidates(prompt, attrs)
      case "intent-router" => intentOrderedCandidates(prompt, attrs)
      case _ => Future.successful(codeOrderedCandidates(originalBody))
    }
    orderedF.flatMap { ordered =>
      if (ordered.isEmpty) {
        Json.obj("error" -> s"no candidate provider configured for the otoroshi $routerModel (set options.$refsField)").leftf
      } else {
        // strip router-only knobs and the router model so each candidate uses its own configured model
        val cleanBody = originalBody.asObject - "model" - "min_coding_score" - "cost_quality_tradeoff" - "allowed_models"
        def attempt(remaining: Seq[AiProvider], lastErr: JsValue): Future[Either[JsValue, T]] = remaining match {
          case Seq() => lastErr.leftf
          case p +: tail => p.getChatClient() match {
            case None => attempt(tail, Json.obj("error" -> s"no chat client for provider ${p.id}"))
            case Some(client) =>
              delegate(attrs, originalBody, p, client, cleanBody)
              f(client, cleanBody).flatMap {
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

  override def invoke(kind: ChatCallKind, prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    if (isFusion(originalBody)) fusionCall(prompt, attrs, originalBody)
    else execute(prompt, attrs, originalBody)((client, body) => client.invoke(kind, prompt, attrs, body))
  }

  override def invokeStream(kind: ChatCallKind, prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, ?]]] = {
    if (isFusion(originalBody)) fusionStream(prompt, attrs, originalBody)
    else execute(prompt, attrs, originalBody)((client, body) => client.invokeStream(kind, prompt, attrs, body))
  }
}
