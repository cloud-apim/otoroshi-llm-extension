package com.cloud.apim.otoroshi.extensions.aigateway.providers

import com.cloud.apim.otoroshi.extensions.aigateway.*
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*
import play.api.libs.ws.WSResponse

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/////////                                  Decision Models (OpenAI api)                                  ///////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

/**
 * The decisions api of OpenAI (`POST /v1/decisions`). It asks what the System One api asks, in other words: a
 * predicate is a noul, the choices of a choice and the levels of a score are its criteria, the input is the state.
 *
 * A request in this format becomes a System One one, which every client, decorator and consumer of decision
 * models reads, and the answers come back in this format, in the order of the questions. What System One has no
 * word for is said here: an answer a model did not give is a refusal, as OpenAI says it.
 */
object OpenAiDecisions {

  val Predicate = "predicate"
  val Choice = "choice"
  val Score = "score"
  val Refusal = DecisionRequests.Refusal
  val knownTypes: Seq[String] = Seq(Predicate, Choice, Score)

  // the names a System One server takes for a question: Cloudflare documents letters, digits, `_`, `.` and `-`
  private val QuestionKey = "^[A-Za-z0-9_.-]{1,100}$".r

  def error(message: String, param: Option[String] = None, kind: String = "invalid_request_error", code: Option[String] = None): JsObject =
    Json.obj("error" -> Json.obj(
      "message" -> message,
      "type" -> kind,
      "param" -> param.map(JsString.apply).getOrElse(JsNull).asValue,
      "code" -> code.map(JsString.apply).getOrElse(JsNull).asValue,
    ))

  private def missing(param: String): JsObject = error(s"Missing required parameter: '${param}'.", param.some)

  private def all[A, B](items: Seq[A])(f: (A, Int) => Either[JsObject, B]): Either[JsObject, Seq[B]] =
    items.zipWithIndex.foldLeft[Either[JsObject, Seq[B]]](Right(Seq.empty)) {
      case (Right(acc), (item, idx)) => f(item, idx).map(acc :+ _)
      case (left, _) => left
    }

  private def number(value: JsValue): Option[JsNumber] = value match {
    case n: JsNumber => n.some
    case JsString(s) => Try(BigDecimal(s.trim)).toOption.map(JsNumber.apply)
    case _ => None
  }

  // a choice value is a string or a boolean, and a System One option is a name
  def optionOf(value: JsValue): String = value match {
    case JsString(s) => s
    case other => Json.stringify(other)
  }

  final case class ChoiceOption(value: JsValue, description: Option[String]) {
    val key: String = optionOf(value)
  }

  final case class Level(label: String, description: Option[String]) {
    def criterion: String = description.map(d => s"${label}: ${d}").getOrElse(label)
  }

  /** A question of the request, and `key`, the name it has in the System One request made of it. */
  final case class Question(
    index: Int,
    name: Option[String],
    kind: String,
    instructions: String,
    choices: Seq[ChoiceOption] = Seq.empty,
    levels: Seq[Level] = Seq.empty,
    key: String = "",
  ) {

    private def nameJson: JsValue = name.map(JsString.apply).getOrElse(JsNull)

    def systemOne: JsObject = {
      val base = Json.obj("instructions" -> instructions)
      kind match {
        case Choice => Json.obj("type" -> DecisionRequests.Choice) ++ base ++ Json.obj(
          "criteria" -> JsObject(choices.map(c => c.key -> JsString(c.description.getOrElse(c.key)))),
        )
        case Score => Json.obj("type" -> DecisionRequests.Score) ++ base ++ Json.obj("criteria" -> JsArray(levels.map(l => JsString(l.criterion))))
        case _ => Json.obj("type" -> DecisionRequests.Noul) ++ base
      }
    }

    def refusal: JsObject = Json.obj("type" -> Refusal, "name" -> nameJson)

    /** The answer to this question, from what the System One answer `stated`: a refusal when it is not there */
    def answer(stated: Option[JsValue]): JsObject = {
      stated.flatMap(_.asOpt[JsObject]).filterNot(DecisionAnswers.isRefusal).flatMap { stated =>
        kind match {
          case Predicate => stated.select("noul").asOpt[JsValue].flatMap(number).map { p =>
            Json.obj("type" -> Predicate, "name" -> nameJson, "probability" -> p)
          }
          case Choice => choiceOf(stated)
          case Score => scoreOf(stated)
          case _ => None
        }
      }.getOrElse(refusal)
    }

    private def choiceOf(stated: JsObject): Option[JsObject] = stated.select("probabilities").asOpt[JsObject].map { statedProbabilities =>
      val probabilities = choices.map(c => c -> statedProbabilities.value.get(c.key).flatMap(number).getOrElse(JsNumber(0)))
      val computed = DecisionAnswers.choice(probabilities.map { case (c, p) => c.key -> p.value.toDouble })
      // the option the model named, its most likely one otherwise, with the type it has in the question
      val chosen = stated.select("choice").asOpt[JsValue].map(optionOf).flatMap(key => choices.find(_.key == key))
        .orElse(choices.find(_.key == computed.select("choice").asString))
      Json.obj(
        "type" -> Choice,
        "name" -> nameJson,
        "choice" -> chosen.map(_.value).getOrElse(JsNull).asValue,
        "confidence" -> stated.select("confidence").asOpt[JsValue].flatMap(number).getOrElse(computed.select("confidence").as[JsNumber]),
        "probabilities" -> JsArray(probabilities.map { case (c, p) => Json.obj("value" -> c.value, "probability" -> p) }),
      )
    }

    private def scoreOf(stated: JsObject): Option[JsObject] = {
      val probabilities: Option[Seq[JsNumber]] = stated.select("probabilities").asOpt[JsValue] match {
        case Some(JsArray(values)) => levels.indices.map(i => values.lift(i).flatMap(number).getOrElse(JsNumber(0))).some
        case Some(JsObject(values)) => levels.indices.map(i => values.get(i.toString).flatMap(number).getOrElse(JsNumber(0))).some
        case _ => None
      }
      probabilities.map { ps =>
        lazy val computed = DecisionAnswers.score(levels.map(l => JsString(l.label)), ps.map(_.value.toDouble))
        Json.obj(
          "type" -> Score,
          "name" -> nameJson,
          "score" -> stated.select("score").asOpt[JsValue].flatMap(number).getOrElse(computed.select("score").as[JsNumber]),
          "confidence" -> stated.select("confidence").asOpt[JsValue].flatMap(number).getOrElse(computed.select("confidence").as[JsNumber]),
          "probabilities" -> JsArray(levels.zip(ps).zipWithIndex.map { case ((level, p), i) =>
            Json.obj("label" -> level.label, "value" -> i, "probability" -> p)
          }),
        )
      }
    }
  }

  /** A request in the OpenAI format: `state` and `images` are its input, as a System One server takes them. */
  final case class Request(raw: JsObject, state: JsValue, images: Seq[String], questions: Seq[Question]) {

    def model: Option[String] = raw.select("model").asOptString.map(_.trim).filter(_.nonEmpty)

    // `images` is how Cloudflare takes pictures (data urls), the other System One servers look at none
    def systemOne: JsObject = Json.obj("state" -> state, "questions" -> JsObject(questions.map(q => q.key -> q.systemOne)))
      .applyOnWithOpt(model) { case (obj, m) => obj ++ Json.obj("model" -> m) }
      .applyOnIf(images.nonEmpty)(_ ++ Json.obj("images" -> images))
      // the gateway's own way of naming the decision model to use
      .applyOnWithOpt(raw.select("provider").asOptString) { case (obj, p) => obj ++ Json.obj("provider" -> p) }

    /** The parameter of the request a System One location (`questions.<key>.criteria`) is about */
    def paramOf(loc: Seq[String]): String = loc.filterNot(_ == "body") match {
      case "questions" +: key +: rest => questions.find(_.key == key) match {
        case Some(q) =>
          val field = rest.headOption.map {
            case "criteria" if q.kind == Choice => "choices"
            case "criteria" if q.kind == Score => "levels"
            case other => other
          }
          (s"questions[${q.index}]" +: field.toSeq).mkString(".")
        case None => ("questions" +: key +: rest).mkString(".")
      }
      case "state" +: _ => "input"
      case other => other.mkString(".")
    }
  }

  def read(body: JsValue): Either[JsObject, Request] = body match {
    case obj: JsObject =>
      for {
        input <- inputOf(obj)
        questions <- questionsOf(obj)
      } yield Request(obj, JsString(input._1.mkString("\n\n")), input._2, questions)
    case _ => Left(error("The request body must be a JSON object."))
  }

  // the texts and the images of the input, in their order
  private def inputOf(obj: JsObject): Either[JsObject, (Seq[String], Seq[String])] = obj.value.get("input") match {
    case None | Some(JsNull) => Left(missing("input"))
    case Some(JsString(text)) => Right((Seq(text), Seq.empty))
    case Some(JsArray(messages)) => all(messages.toSeq)((m, i) => messageOf(m, s"input[${i}]")).map(parts => (parts.flatMap(_._1), parts.flatMap(_._2)))
    case Some(_) => Left(error("'input' must be a string or an array of user messages.", "input".some))
  }

  private def messageOf(message: JsValue, path: String): Either[JsObject, (Seq[String], Seq[String])] = message match {
    case obj: JsObject if !obj.select("role").asOptString.contains("user") =>
      Left(error("Only user messages are supported.", s"${path}.role".some))
    case obj: JsObject => obj.value.get("content") match {
      case Some(JsString(text)) => Right((Seq(text), Seq.empty))
      case Some(JsArray(parts)) => all(parts.toSeq)((p, i) => partOf(p, s"${path}.content[${i}]")).map(parts => (parts.flatMap(_._1), parts.flatMap(_._2)))
      case None | Some(JsNull) => Left(missing(s"${path}.content"))
      case Some(_) => Left(error("'content' must be a string or an array of parts.", s"${path}.content".some))
    }
    case _ => Left(error("A message must be a JSON object.", path.some))
  }

  private def partOf(part: JsValue, path: String): Either[JsObject, (Seq[String], Seq[String])] = part.select("type").asOptString match {
    case Some("input_text") => part.select("text").asOptString match {
      case Some(text) => Right((Seq(text), Seq.empty))
      case None => Left(missing(s"${path}.text"))
    }
    case Some("input_image") => part.select("image_url").asOptString match {
      case Some(url) if url.regionMatches(true, 0, "data:", 0, 5) => Right((Seq.empty, Seq(url)))
      case Some(_) => Left(error("Images must be data URLs: external URLs and file IDs are not supported.", s"${path}.image_url".some))
      case None => Left(missing(s"${path}.image_url"))
    }
    case _ => Left(error("A part is either 'input_text' or 'input_image'.", s"${path}.type".some))
  }

  private def questionsOf(obj: JsObject): Either[JsObject, Seq[Question]] = obj.value.get("questions") match {
    case None | Some(JsNull) => Left(missing("questions"))
    case Some(JsArray(items)) if items.isEmpty => Left(error("At least one question is required.", "questions".some))
    case Some(JsArray(items)) => all(items.toSeq)((q, i) => questionOf(q, i)).map(withKeys)
    case Some(_) => Left(error("'questions' must be an array.", "questions".some))
  }

  private def questionOf(value: JsValue, index: Int): Either[JsObject, Question] = {
    val path = s"questions[${index}]"
    value match {
      case question: JsObject =>
        val name = question.select("name").asOptString
        (question.select("type").asOptString, question.select("instructions").asOptString) match {
          case (None, _) => Left(missing(s"${path}.type"))
          case (Some(kind), _) if !knownTypes.contains(kind) =>
            Left(error(s"Invalid value: '${kind}'. Supported values are: ${knownTypes.map(t => s"'${t}'").mkString(", ")}.", s"${path}.type".some))
          case (_, None) => Left(missing(s"${path}.instructions"))
          case (Some(Choice), Some(instructions)) => choicesOf(question, path).map(cs => Question(index, name, Choice, instructions, choices = cs))
          case (Some(Score), Some(instructions)) => levelsOf(question, path).map(ls => Question(index, name, Score, instructions, levels = ls))
          case (Some(kind), Some(instructions)) => Right(Question(index, name, kind, instructions))
        }
      case _ => Left(error("A question must be a JSON object.", path.some))
    }
  }

  private def choicesOf(question: JsObject, path: String): Either[JsObject, Seq[ChoiceOption]] = question.select("choices").asOpt[JsValue] match {
    case Some(JsArray(items)) if items.nonEmpty =>
      all(items.toSeq) { (item, i) =>
        item.select("value").asOpt[JsValue] match {
          case Some(v @ (_: JsString | _: JsBoolean)) => Right(ChoiceOption(v, item.select("description").asOptString))
          case _ => Left(error("A choice value is a string or a boolean.", s"${path}.choices[${i}].value".some))
        }
      }.flatMap { options =>
        if (options.map(_.value).distinct.size != options.size) Left(error("Each choice must be unique.", s"${path}.choices".some))
        // `"true"` and `true` are two choices here, and one option name for a System One server
        else if (options.map(_.key).distinct.size != options.size) Left(error("A choice cannot be both a string and a boolean of the same text.", s"${path}.choices".some))
        else Right(options)
      }
    case Some(JsArray(_)) => Left(error("At least one choice is required.", s"${path}.choices".some))
    case None | Some(JsNull) => Left(missing(s"${path}.choices"))
    case Some(_) => Left(error("'choices' must be an array.", s"${path}.choices".some))
  }

  private def levelsOf(question: JsObject, path: String): Either[JsObject, Seq[Level]] = question.select("levels").asOpt[JsValue] match {
    case Some(JsArray(items)) if items.nonEmpty =>
      all(items.toSeq) { (item, i) =>
        item.select("label").asOptString match {
          case Some(label) => Right(Level(label, item.select("description").asOptString))
          case None => Left(missing(s"${path}.levels[${i}].label"))
        }
      }
    case Some(JsArray(_)) => Left(error("At least one level is required.", s"${path}.levels".some))
    case None | Some(JsNull) => Left(missing(s"${path}.levels"))
    case Some(_) => Left(error("'levels' must be an array.", s"${path}.levels".some))
  }

  // a question keeps its name when a System One server takes it and no other question has it already
  private def withKeys(questions: Seq[Question]): Seq[Question] = questions.foldLeft((Set.empty[String], Seq.empty[Question])) {
    case ((taken, acc), question) =>
      val key = question.name.filter(n => QuestionKey.matches(n) && !taken.contains(n))
        .getOrElse(Iterator.iterate(s"question_${question.index}")(_ + "_").find(k => !taken.contains(k)).get)
      (taken + key, acc :+ question.copy(key = key))
  }._2

  /** The usage of a decision, with the details OpenAI gives: the ones of the provider when it gave them */
  def usage(decision: DecisionResponse): JsObject = {
    val raw = decision.raw.select("usage").asOpt[JsObject].getOrElse(Json.obj())
    val input = decision.metadata.usage.input
    val output = decision.metadata.usage.output
    (raw - "prompt_tokens" - "completion_tokens") ++ Json.obj(
      "input_tokens" -> input,
      "input_tokens_details" -> raw.select("input_tokens_details").asOpt[JsObject].getOrElse(Json.obj("cached_tokens" -> 0, "cache_write_tokens" -> 0)),
      "output_tokens" -> output,
      "output_tokens_details" -> raw.select("output_tokens_details").asOpt[JsObject].getOrElse(Json.obj("reasoning_tokens" -> 0)),
      "total_tokens" -> (input + output),
    )
  }

  /** The answer to `request` in the OpenAI format, whatever the format of the provider that made the decision */
  def response(request: Request, decision: DecisionResponse, env: Env): JsObject = {
    val body = Json.obj(
      "model" -> decision.model,
      "answers" -> JsArray(request.questions.map(q => q.answer(decision.answers.value.get(q.key)))),
      "usage" -> usage(decision),
    ).applyOnWithOpt(decision.raw.select("id").asOptString) { case (obj, id) => Json.obj("id" -> id) ++ obj }
    decision.withExtras(body, env)
  }

  private def typeOf(status: Int): String = status match {
    case 401 => "authentication_error"
    case 402 => "insufficient_quota"
    case 403 => "permission_error"
    case 404 => "not_found_error"
    case 429 => "rate_limit_error"
    case s if s >= 500 => "server_error"
    case _ => "invalid_request_error"
  }

  /**
   * An error a decision model answered, in the OpenAI format: the one of an OpenAI server as it is, the one of a
   * System One server (`{"detail": ...}`, a list of issues for a request it refused) said in OpenAI's words.
   */
  def errorOf(status: Int, body: JsValue, request: Option[Request]): JsObject = body.select("error").asOpt[JsObject] match {
    case Some(_) => body.asObject
    case None =>
      val kind = typeOf(status)
      body.select("detail").asOpt[JsValue] match {
        case Some(detail: JsObject) =>
          error(detail.select("message").asOptString.getOrElse(detail.stringify), None, kind, detail.select("error_type").asOptString)
        case Some(JsArray(issues)) =>
          val described = issues.toSeq.map { issue =>
            val loc = issue.select("loc").asOpt[Seq[JsValue]].getOrElse(Seq.empty).map(optionOf)
            val param = request.map(_.paramOf(loc)).getOrElse(loc.filterNot(_ == "body").mkString("."))
            (param, issue.select("msg").asOptString.getOrElse(issue.stringify))
          }
          error(described.map { case (param, msg) => if (param.isEmpty) msg else s"${param}: ${msg}" }.mkString("; "), described.headOption.map(_._1).filter(_.nonEmpty), kind)
        case Some(JsString(message)) => error(message, None, kind)
        case _ => body match {
          case JsString(message) => error(message, None, kind)
          case other => error(other.stringify, None, kind)
        }
      }
  }
}

/**
 * The other way around, for the decision models of OpenAI: a System One request asked in the OpenAI format, and
 * the OpenAI answers read as System One ones, under the names of the questions they answer.
 */
object OpenAiDecisionRequests {

  val Provider = "openai"
  val defaultPath = "/decisions"
  val defaultModel = "gpt-6-luna"

  private def text(value: JsValue): String = value match {
    case JsString(str) => str
    case other => Json.prettyPrint(other)
  }

  // a picture of a state: a data url, or the `{content_type, base64}` object Cloudflare also takes
  private def imageUrlOf(image: JsValue): Option[String] = image match {
    case JsString(url) => url.some
    case obj: JsObject => for {
      contentType <- obj.select("content_type").asOptString
      base64 <- obj.select("base64").asOptString
    } yield s"data:${contentType};base64,${base64}"
    case _ => None
  }

  private def unsupported(name: String, kind: String): JsValue = DecisionErrors.invalid(Seq(Json.obj(
    "type" -> "literal_error",
    "loc" -> Seq("body", "questions", name, "type"),
    "msg" -> s"OpenAI answers predicates (noul), choices and scores, not '${kind}' questions",
  )))

  private def questionOf(name: String, question: JsObject): Either[JsValue, JsObject] = {
    val instructions = question.select("instructions").asOptString.getOrElse("")
    val base = Json.obj("name" -> name)
    question.select("type").asOptString.getOrElse("") match {
      case DecisionRequests.Noul =>
        // what a yes and a no mean, said along with the question: a predicate has nothing else to say it with
        val meanings = Seq("true" -> "Yes means", "false" -> "No means").flatMap { case (key, label) =>
          question.at(s"criteria.${key}").asOpt[JsValue].map(v => s"${label}: ${text(v)}")
        }
        Right(Json.obj("type" -> OpenAiDecisions.Predicate) ++ base ++ Json.obj("instructions" -> (instructions +: meanings).mkString("\n")))
      case DecisionRequests.Choice =>
        val options = question.select("criteria").asOpt[JsObject].getOrElse(Json.obj()).fields.map { case (option, description) =>
          // an option described by its own name, as the OpenAI requests are read, has nothing more to say
          Json.obj("value" -> option).applyOnIf(text(description) != option)(_ ++ Json.obj("description" -> text(description)))
        }
        Right(Json.obj("type" -> OpenAiDecisions.Choice) ++ base ++ Json.obj("instructions" -> instructions, "choices" -> options))
      case DecisionRequests.Score =>
        val levels = question.select("criteria").asOpt[Seq[JsValue]].getOrElse(Seq.empty).map(level => Json.obj("label" -> text(level)))
        Right(Json.obj("type" -> OpenAiDecisions.Score) ++ base ++ Json.obj("instructions" -> instructions, "levels" -> levels))
      case other => Left(unsupported(name, other))
    }
  }

  /**
   * The OpenAI body of a decision call: the request of the caller when it was written in this format, the System
   * One one said in OpenAI's words otherwise. The pictures of the state go before it, as Cloudflare places them.
   */
  def bodyOf(opts: DecisionModelClientInputOptions, rawBody: JsObject): Either[JsValue, JsObject] = opts.openai match {
    case Some(original) => Right(original - "model" - "provider")
    case None =>
      val questions = opts.questions.fields.foldLeft[Either[JsValue, Seq[JsObject]]](Right(Seq.empty)) {
        case (Right(acc), (name, question: JsObject)) => questionOf(name, question).map(acc :+ _)
        case (Right(_), (name, _)) => Left(unsupported(name, "?"))
        case (left, _) => left
      }
      val images = rawBody.select("images").asOpt[Seq[JsValue]].getOrElse(Seq.empty).flatMap(imageUrlOf)
      val state = text(opts.state)
      val input: JsValue =
        if (images.isEmpty) JsString(state)
        else Json.arr(Json.obj("role" -> "user", "content" -> JsArray(
          images.map(url => Json.obj("type" -> "input_image", "image_url" -> url)) ++
            Seq(state).filter(_.nonEmpty).map(t => Json.obj("type" -> "input_text", "text" -> t))
        )))
      questions.map(qs => Json.obj("input" -> input, "questions" -> qs))
  }

  // a System One answer of what OpenAI answered
  private def answerOf(answer: JsObject): JsObject = answer.select("type").asOptString match {
    case Some(OpenAiDecisions.Predicate) =>
      Json.obj("type" -> DecisionRequests.Noul, "noul" -> answer.select("probability").asOpt[JsValue].getOrElse(JsNull).asValue)
    case Some(OpenAiDecisions.Choice) =>
      val probabilities = answer.select("probabilities").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
      Json.obj(
        "type" -> DecisionRequests.Choice,
        "choice" -> answer.select("choice").asOpt[JsValue].map(v => JsString(OpenAiDecisions.optionOf(v))).getOrElse(JsNull).asValue,
        "confidence" -> answer.select("confidence").asOpt[JsValue].getOrElse(JsNull).asValue,
        "probabilities" -> JsObject(probabilities.flatMap { p =>
          p.select("value").asOpt[JsValue].map(v => OpenAiDecisions.optionOf(v) -> p.select("probability").asOpt[JsValue].getOrElse(JsNumber(0)))
        }),
      )
    case Some(OpenAiDecisions.Score) =>
      val levels = answer.select("probabilities").asOpt[Seq[JsObject]].getOrElse(Seq.empty).zipWithIndex.map { case (p, i) =>
        (p.select("value").asOpt[Int].getOrElse(i).toString, p)
      }
      Json.obj(
        "type" -> DecisionRequests.Score,
        "score" -> answer.select("score").asOpt[JsValue].getOrElse(JsNull).asValue,
        "confidence" -> answer.select("confidence").asOpt[JsValue].getOrElse(JsNull).asValue,
        "legend" -> JsObject(levels.map { case (i, p) => i -> p.select("label").asOpt[JsValue].getOrElse(JsNull).asValue }),
        "probabilities" -> JsObject(levels.map { case (i, p) => i -> p.select("probability").asOpt[JsValue].getOrElse(JsNumber(0)) }),
      )
    case Some(OpenAiDecisions.Refusal) => DecisionAnswers.refusal
    case _ => answer
  }

  /** The answer of an OpenAI server: its answers come in the order of the questions, `names` */
  def read(resp: WSResponse, requestedModel: String, names: Seq[String], env: Env): Either[JsValue, DecisionResponse] = {
    val body = SystemOneResponses.bodyOf(resp)
    if (resp.status == 200 && body.select("answers").asOpt[JsArray].isDefined) {
      val obj = body.asObject
      val usage = obj.select("usage").asOpt[JsObject].getOrElse(Json.obj())
      val answers = obj.select("answers").as[Seq[JsValue]].zip(names).collect { case (answer: JsObject, name) => name -> answerOf(answer) }
      Right(DecisionResponse(
        model = obj.select("model").asOptString.filter(_.nonEmpty).getOrElse(requestedModel),
        answers = JsObject(answers),
        metadata = DecisionResponseMetadata(
          usage = DecisionResponseMetadataUsage.fromJson(usage),
          rateLimit = SystemOneResponses.rateLimitOf(resp),
        ),
        raw = obj,
      ))
    } else if (resp.status == 200) {
      ProviderHelpers.logBadResponse("OpenAI", resp)
      Left(DecisionErrors.upstream(502, OpenAiDecisions.error("OpenAI answered without any answers", None, "server_error")))
    } else {
      ProviderHelpers.logBadResponse("OpenAI", resp)
      Left(DecisionErrors.upstream(resp.status, body, resp.header("Retry-After")))
    }
  }
}

/** The decision models of OpenAI, on its decisions api. They look at pictures. */
class OpenAiDecisionModelClient(api: SystemOneApi, url: String, options: DecisionModelClientOptions) extends DecisionModelClient {

  override def decide(opts: DecisionModelClientInputOptions, rawBody: JsObject, attrs: TypedMap)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, DecisionResponse]] = {
    opts.model.orElse(options.model) match {
      case None => SystemOneResponses.noModel.leftf
      case Some(model) => OpenAiDecisionRequests.bodyOf(opts, rawBody) match {
        case Left(err) => err.leftf
        case Right(body) =>
          api.call(url, Json.obj("model" -> model) ++ body).map { resp =>
            OpenAiDecisionRequests.read(resp, model, opts.questions.fields.map(_._1).toSeq, env)
          }
      }
    }
  }
}
