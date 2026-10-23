package com.cloud.apim.otoroshi.extensions.aigateway.providers

import com.cloud.apim.otoroshi.extensions.aigateway.*
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*
import play.api.libs.ws.WSResponse

import java.nio.charset.StandardCharsets
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

  // the limits OpenAI sets on the questions of a request
  val MaxQuestions = 400
  val MinChoices = 2
  val MaxChoices = 255

  // the items of an input, and the parts of their contents
  val Message = "message"
  val FunctionCall = "function_call"
  val FunctionCallOutput = "function_call_output"
  val CustomToolCall = "custom_tool_call"
  val CustomToolCallOutput = "custom_tool_call_output"
  val itemTypes: Seq[String] = Seq(Message, FunctionCall, FunctionCallOutput, CustomToolCall, CustomToolCallOutput)
  val roles: Seq[String] = Seq("user", "developer", "system", "assistant")
  val InputText = "input_text"
  val InputImage = "input_image"
  val InputJson = "input_json"
  val OutputText = "output_text"
  val imageDetails: Seq[String] = Seq("low", "high", "auto", "original")

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

  private def invalidValue(value: String, supported: Seq[String], param: String): JsObject =
    error(s"Invalid value: '${value}'. Supported values are: ${supported.map(v => s"'${v}'").mkString(", ")}.", param.some)

  // a string field of `obj`, the object at `path`
  private def stringOf(obj: JsObject, field: String, path: String): Either[JsObject, String] = obj.value.get(field) match {
    case Some(JsString(value)) => Right(value)
    case None | Some(JsNull) => Left(missing(s"${path}.${field}"))
    case Some(_) => Left(error(s"'${field}' must be a string.", s"${path}.${field}".some))
  }

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

  /** What a content is made of: texts, and pictures, which go along as the `images` of the System One request */
  sealed trait Piece
  final case class TextPiece(text: String) extends Piece
  final case class ImagePiece(url: String) extends Piece

  /** An item of the input: who wrote it (`label`) and what it holds. A tool call and its output share a `callId` */
  final case class Item(kind: String, label: String, pieces: Seq[Piece], callId: Option[String] = None) {
    def fromUser: Boolean = kind == Message && label == "user"
  }

  private def imagesOf(pieces: Seq[Piece]): Seq[String] = pieces.collect { case ImagePiece(url) => url }

  // the texts as they are, and each picture named by its place among the `images`, from `first` on
  private def rendered(pieces: Seq[Piece], first: Int): (Seq[String], Int) = pieces.foldLeft((Seq.empty[String], first)) {
    case ((texts, n), TextPiece(text)) => (texts :+ text, n)
    case ((texts, n), ImagePiece(_)) => (texts :+ s"[image ${n}]", n + 1)
  }

  /**
   * The state of the System One request. What a user wrote is the state as it is, the pictures being shown before
   * it; an input with other authors is a transcript saying who wrote each item, and where each picture was.
   */
  private def stateOf(items: Seq[Item]): String = {
    if (items.forall(_.fromUser)) items.flatMap(_.pieces).collect { case TextPiece(text) => text }.mkString("\n\n")
    else items.foldLeft((Seq.empty[String], 1)) { case ((blocks, n), item) =>
      val (texts, next) = rendered(item.pieces, n)
      (blocks :+ s"[${item.label}]\n${texts.mkString("\n\n")}", next)
    }._1.mkString("\n\n")
  }

  def read(body: JsValue): Either[JsObject, Request] = body match {
    case obj: JsObject =>
      for {
        items <- inputOf(obj)
        asked <- questionsOf(obj)
      } yield {
        // the pictures of the instructions come after the ones of the input, each named where it was
        val pictures = imagesOf(items.flatMap(_.pieces))
        val questions = asked.foldLeft((Seq.empty[Question], pictures.size + 1)) { case ((acc, n), (question, pieces)) =>
          val (texts, next) = rendered(pieces, n)
          (acc :+ question.copy(instructions = texts.mkString("\n\n")), next)
        }._1
        Request(obj, JsString(stateOf(items)), pictures ++ imagesOf(asked.flatMap(_._2)), withKeys(questions))
      }
    case _ => Left(error("The request body must be a JSON object."))
  }

  // the items of the input, in their order: a text is a message of a user
  private def inputOf(obj: JsObject): Either[JsObject, Seq[Item]] = obj.value.get("input") match {
    case None | Some(JsNull) => Left(missing("input"))
    case Some(JsString(text)) => Right(Seq(Item(Message, "user", Seq(TextPiece(text)))))
    case Some(JsArray(items)) => all(items.toSeq)((item, i) => itemOf(item, s"input[${i}]")).flatMap(answered)
    case Some(_) => Left(error("'input' must be a string or an array of input items.", "input".some))
  }

  private def itemOf(item: JsValue, path: String): Either[JsObject, Item] = item match {
    case obj: JsObject => obj.select("type").asOptString.getOrElse(Message) match {
      case Message => obj.select("role").asOptString match {
        case Some(role @ "user") => contentOf(obj, "content", path, Seq(InputText, InputImage, InputJson)).map(Item(Message, role, _))
        case Some(role @ ("developer" | "system")) => contentOf(obj, "content", path, Seq(InputText)).map(Item(Message, role, _))
        case Some(role @ "assistant") => contentOf(obj, "content", path, Seq(OutputText, Refusal)).map(Item(Message, role, _))
        case Some(role) => Left(invalidValue(role, roles, s"${path}.role"))
        case None => Left(missing(s"${path}.role"))
      }
      case kind @ (FunctionCall | CustomToolCall) => for {
        callId <- callIdOf(obj, path)
        name <- stringOf(obj, "name", path)
        arguments <- stringOf(obj, if (kind == FunctionCall) "arguments" else "input", path)
      } yield {
        val qualified = obj.select("namespace").asOptString.map(namespace => s"${namespace}.${name}").getOrElse(name)
        Item(kind, s"${kind.replace('_', ' ')} ${qualified} (${callId})", Seq(TextPiece(arguments)), callId.some)
      }
      case kind @ (FunctionCallOutput | CustomToolCallOutput) => for {
        callId <- callIdOf(obj, path)
        output <- contentOf(obj, "output", path, Seq(InputText, InputImage))
      } yield Item(kind, s"${kind.replace('_', ' ')} (${callId})", output, callId.some)
      case kind => Left(invalidValue(kind, itemTypes, s"${path}.type"))
    }
    case _ => Left(error("An input item must be a JSON object.", path.some))
  }

  private def callIdOf(obj: JsObject, path: String): Either[JsObject, String] =
    stringOf(obj, "call_id", path).filterOrElse(_.nonEmpty, error("'call_id' cannot be empty.", s"${path}.call_id".some))

  // each tool output answers a tool call of the input, wherever it is and whatever its kind, as OpenAI checks it
  private def answered(items: Seq[Item]): Either[JsObject, Seq[Item]] = {
    val calls = items.collect { case Item(FunctionCall | CustomToolCall, _, _, Some(callId)) => callId }.toSet
    items.zipWithIndex.collectFirst {
      case (Item(FunctionCallOutput | CustomToolCallOutput, _, _, Some(callId)), i) if !calls.contains(callId) =>
        error("A tool call output must reference a tool call in the input.", s"input[${i}].call_id".some)
    }.toLeft(items)
  }

  // what OpenAI says of an array of the wrong length
  private def tooShort(param: String, min: Int, length: Int): JsObject =
    if (length == 0) error(s"Invalid '${param}': empty array. Expected an array with minimum length ${min}, but got an empty array instead.", param.some, code = "empty_array".some)
    else error(s"Invalid '${param}': array too short. Expected an array with minimum length ${min}, but got an array with length ${length} instead.", param.some, code = "array_below_min_length".some)

  private def tooLong(param: String, max: Int, length: Int): JsObject =
    error(s"Invalid '${param}': array too long. Expected an array with maximum length ${max}, but got an array with length ${length} instead.", param.some, code = "array_above_max_length".some)

  // a text, or parts of the types the field takes
  private def contentOf(obj: JsObject, field: String, path: String, types: Seq[String]): Either[JsObject, Seq[Piece]] = obj.value.get(field) match {
    case Some(JsString(text)) => Right(Seq(TextPiece(text)))
    case Some(JsArray(parts)) => all(parts.toSeq)((part, i) => partOf(part, s"${path}.${field}[${i}]", types))
    case None | Some(JsNull) => Left(missing(s"${path}.${field}"))
    case Some(_) => Left(error(s"'${field}' must be a string or an array of parts.", s"${path}.${field}".some))
  }

  private def partOf(part: JsValue, path: String, types: Seq[String]): Either[JsObject, Piece] = part match {
    case obj: JsObject => obj.select("type").asOptString match {
      case None => Left(missing(s"${path}.type"))
      case Some(kind) if !types.contains(kind) => Left(invalidValue(kind, types, s"${path}.type"))
      case Some(InputImage) => imageOf(obj, path)
      case Some(InputJson) => jsonOf(obj, path)
      case Some(Refusal) => stringOf(obj, "refusal", path).map(TextPiece.apply)
      case Some(_) => stringOf(obj, "text", path).map(TextPiece.apply)
    }
    case _ => Left(error("A part must be a JSON object.", path.some))
  }

  // a picture as a data url, or the address OpenAI fetches it from: a file id is neither
  private def imageOf(obj: JsObject, path: String): Either[JsObject, Piece] = obj.value.get("detail") match {
    case Some(JsString(detail)) if !imageDetails.contains(detail) => Left(invalidValue(detail, imageDetails, s"${path}.detail"))
    case _ => stringOf(obj, "image_url", path).flatMap { url =>
      if (url.regionMatches(true, 0, "data:", 0, 5) || DecisionImages.isLink(url)) Right(ImagePiece(url))
      else Left(error("Images must be data URLs or HTTP(S) URLs: file IDs are not supported.", s"${path}.image_url".some))
    }
  }

  // a json object or an array of objects, which OpenAI reads as text
  private def jsonOf(obj: JsObject, path: String): Either[JsObject, Piece] = obj.value.get("data") match {
    case None | Some(JsNull) => Left(missing(s"${path}.data"))
    case Some(data) if !isJsonData(data) => Left(error("'data' must be a JSON object or an array of objects.", s"${path}.data".some))
    case Some(data) if !withinJsonLimits(data) =>
      Left(error("'data' is limited to 32 levels of containers, 10,000 values and 256 KiB of compact JSON.", s"${path}.data".some))
    case Some(data) => Right(TextPiece(Json.stringify(data)))
  }

  /** What an `input_json` part holds: an object, or an array of objects */
  def isJsonData(value: JsValue): Boolean = value match {
    case _: JsObject => true
    case JsArray(values) => values.forall(_.isInstanceOf[JsObject])
    case _ => false
  }

  // how deep the containers of a json value go, and how many values it holds
  private def shapeOf(value: JsValue): (Int, Int) = {
    val children = value match {
      case JsObject(fields) => fields.values.toSeq
      case JsArray(values) => values.toSeq
      case _ => Seq.empty
    }
    val own = if (value.isInstanceOf[JsObject] || value.isInstanceOf[JsArray]) 1 else 0
    children.map(shapeOf).foldLeft((own, 1)) { case ((depth, count), (d, c)) => (depth.max(d + 1), count + c) }
  }

  /** Whether OpenAI takes `value` in an `input_json` part: 32 levels of containers, 10,000 values, 256 KiB of compact json */
  def withinJsonLimits(value: JsValue): Boolean = {
    val (depth, count) = shapeOf(value)
    depth <= 32 && count <= 10000 && Json.stringify(value).getBytes(StandardCharsets.UTF_8).length <= 256 * 1024
  }

  private def questionsOf(obj: JsObject): Either[JsObject, Seq[(Question, Seq[Piece])]] = obj.value.get("questions") match {
    case None | Some(JsNull) => Left(missing("questions"))
    case Some(JsArray(items)) if items.isEmpty => Left(tooShort("questions", 1, 0))
    case Some(JsArray(items)) if items.size > MaxQuestions => Left(tooLong("questions", MaxQuestions, items.size))
    case Some(JsArray(items)) => all(items.toSeq)((q, i) => questionOf(q, i))
    case Some(_) => Left(error("'questions' must be an array.", "questions".some))
  }

  // a question, its instructions being written once the pictures of every question are numbered
  private def questionOf(value: JsValue, index: Int): Either[JsObject, (Question, Seq[Piece])] = {
    val path = s"questions[${index}]"
    value match {
      case question: JsObject =>
        (question.select("type").asOptString, question.value.get("name")) match {
          case (None, _) => Left(missing(s"${path}.type"))
          case (Some(kind), _) if !knownTypes.contains(kind) => Left(invalidValue(kind, knownTypes, s"${path}.type"))
          case (_, Some(name)) if !name.isInstanceOf[JsString] && name != JsNull => Left(error("'name' must be a string.", s"${path}.name".some))
          case (Some(kind), name) =>
            val asked = Question(index, name.flatMap(_.asOpt[String]), kind, "")
            contentOf(question, "instructions", path, Seq(InputText, InputImage, InputJson)).flatMap { pieces =>
              val typed = kind match {
                case Choice => choicesOf(question, path).map(cs => asked.copy(choices = cs))
                case Score => levelsOf(question, path).map(ls => asked.copy(levels = ls))
                case _ => Right(asked)
              }
              typed.map(_ -> pieces)
            }
        }
      case _ => Left(error("A question must be a JSON object.", path.some))
    }
  }

  private def choicesOf(question: JsObject, path: String): Either[JsObject, Seq[ChoiceOption]] = question.select("choices").asOpt[JsValue] match {
    case Some(JsArray(items)) =>
      all(items.toSeq) { (item, i) =>
        item.select("value").asOpt[JsValue] match {
          case Some(v @ (_: JsString | _: JsBoolean)) => Right(ChoiceOption(v, item.select("description").asOptString))
          case _ => Left(error("A choice value is a string or a boolean.", s"${path}.choices[${i}].value".some))
        }
      }.flatMap { options =>
        if (options.size < MinChoices) Left(tooShort(s"${path}.choices", MinChoices, options.size))
        else if (options.size > MaxChoices) Left(tooLong(s"${path}.choices", MaxChoices, options.size))
        else if (options.map(_.value).distinct.size != options.size) Left(error("Each choice must be unique.", s"${path}.choices".some))
        // `"true"` and `true` are two choices here, and one option name for a System One server
        else if (options.map(_.key).distinct.size != options.size) Left(error("A choice cannot be both a string and a boolean of the same text.", s"${path}.choices".some))
        else Right(options)
      }
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
      // a structured state is given as the json it is, when OpenAI takes it that way
      val state: Either[JsValue, String] = opts.state match {
        case json if OpenAiDecisions.isJsonData(json) && OpenAiDecisions.withinJsonLimits(json) => Left(json)
        case other => Right(text(other))
      }
      val input: JsValue = (images, state) match {
        case (Seq(), Right(text)) => JsString(text)
        case _ => Json.arr(Json.obj("role" -> "user", "content" -> JsArray(
          images.map(url => Json.obj("type" -> OpenAiDecisions.InputImage, "image_url" -> url)) ++ (state match {
            case Left(json) => Seq(Json.obj("type" -> OpenAiDecisions.InputJson, "data" -> json))
            case Right(text) => Seq(text).filter(_.nonEmpty).map(t => Json.obj("type" -> OpenAiDecisions.InputText, "text" -> t))
          })
        )))
      }
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
