package com.cloud.apim.otoroshi.extensions.aigateway

import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*

import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.util.Try

/**
 * The reasoning of a model, kept from the provider that wrote it to the provider that reads it back.
 *
 * Inside the gateway it is a list of `reasoning_details`, the format of OpenRouter, which every other one fits in:
 *
 *  - `{"type": "reasoning.text", "text": "...", "signature": "...", "format": "...", "index": 0}`: readable reasoning,
 *    signed by Anthropic (`anthropic-claude-v1`), plain for the others (`unknown`)
 *  - `{"type": "reasoning.summary", "summary": "...", "id": "rs_...", "format": "openai-responses-v1", "index": 0}`: what
 *    OpenAI shows of the reasoning of its models
 *  - `{"type": "reasoning.encrypted", "data": "...", "id": "...", "format": "...", "index": 0}`: reasoning only the
 *    provider can read, the `encrypted_content` of OpenAI or the `redacted_thinking` of Anthropic
 *
 * Callers send it back on their assistant messages, as `reasoning_details`, `reasoning_content`, `reasoning`, Anthropic
 * `thinking` blocks or `/responses` reasoning items: `Reasoning.of` reads all of them. Each provider client then writes
 * it the way its api takes it (`ReasoningInput`), and leaves out what its api can not read: a signature of Anthropic
 * means nothing to Mistral, the encrypted reasoning of OpenAI nothing to Anthropic. A message without reasoning is
 * written exactly as before.
 */
object Reasoning {

  object Types {
    val Text = "reasoning.text"
    val Summary = "reasoning.summary"
    val Encrypted = "reasoning.encrypted"
  }

  object Formats {
    val Unknown = "unknown"
    val Anthropic = "anthropic-claude-v1"
    val OpenAiResponses = "openai-responses-v1"
    val Gemini = "google-gemini-v1"
  }

  // what a format can not hold of the reasoning of another one (the signature of a Gemini thought in an Anthropic
  // `thinking` block, the signature of Anthropic in a `/responses` reasoning item) travels inside this envelope, in
  // the opaque field of the format: the caller sends it back untouched, and the gateway reads the original details
  val EnvelopePrefix = "otoroshi.reasoning.v1:"

  def typeOf(detail: JsObject): String = detail.select("type").asOptString.getOrElse(Types.Text)
  def formatOf(detail: JsObject): String = detail.select("format").asOptString.getOrElse(Formats.Unknown)
  def idOf(detail: JsObject): Option[String] = detail.select("id").asOptString.filter(_.nonEmpty)
  def textOf(detail: JsObject): Option[String] = detail.select("text").asOptString
  def summaryOf(detail: JsObject): Option[String] = detail.select("summary").asOptString
  def dataOf(detail: JsObject): Option[String] = detail.select("data").asOptString.filter(_.nonEmpty)
  def signatureOf(detail: JsObject): Option[String] = detail.select("signature").asOptString.filter(_.nonEmpty)

  def isText(detail: JsObject): Boolean = typeOf(detail) == Types.Text
  def isSummary(detail: JsObject): Boolean = typeOf(detail) == Types.Summary
  def isEncrypted(detail: JsObject): Boolean = typeOf(detail) == Types.Encrypted

  /** the readable part of a detail: its text, or its summary */
  def readableOf(detail: JsObject): Option[String] = typeOf(detail) match {
    case Types.Text => textOf(detail)
    case Types.Summary => summaryOf(detail)
    case _ => None
  }

  def textDetail(text: String, format: String = Formats.Unknown, signature: Option[String] = None, id: Option[String] = None): JsObject = {
    Json.obj("type" -> Types.Text, "text" -> text, "format" -> format)
      .applyOnWithOpt(signature.filter(_.nonEmpty)) { case (o, s) => o ++ Json.obj("signature" -> s) }
      .applyOnWithOpt(id) { case (o, i) => o ++ Json.obj("id" -> i) }
  }

  def summaryDetail(summary: String, format: String, id: Option[String]): JsObject = {
    Json.obj("type" -> Types.Summary, "summary" -> summary, "format" -> format)
      .applyOnWithOpt(id) { case (o, i) => o ++ Json.obj("id" -> i) }
  }

  def encryptedDetail(data: String, format: String, id: Option[String] = None): JsObject = {
    Json.obj("type" -> Types.Encrypted, "data" -> data, "format" -> format)
      .applyOnWithOpt(id) { case (o, i) => o ++ Json.obj("id" -> i) }
  }

  /** numbers the details in their order, as OpenRouter does */
  def indexed(details: Seq[JsObject]): Seq[JsObject] = details.zipWithIndex.map { case (d, i) => d ++ Json.obj("index" -> i) }

  /** the whole readable reasoning, `None` when there is nothing to read */
  def text(details: Seq[JsObject]): Option[String] = {
    val parts = details.flatMap(readableOf).filter(_.nonEmpty)
    if (parts.isEmpty) None else parts.mkString("\n\n").some
  }

  // ---------------------------------------------------------------------------------------------------------------
  //  reading
  // ---------------------------------------------------------------------------------------------------------------

  /**
   * The reasoning a message carries, whatever shape the caller or the provider gave it: `reasoning_details` first,
   * as it is the richest, then `thinking` / `redacted_thinking` content blocks (Anthropic, Mistral), the reasoning
   * items kept from a `/responses` answer, and the plain text fields (`reasoning_content`, `reasoning`, the
   * `thinking` of Ollama, and the `reasoning_details` string the gateway used to answer with).
   */
  def of(message: JsObject): Seq[JsObject] = {
    message.select("reasoning_details").asOpt[Seq[JsObject]].filter(_.nonEmpty)
      .orElse(message.select("content").asOpt[Seq[JsValue]].map(blocks => fromContentBlocks(blocks.collect { case o: JsObject => o })).filter(_.nonEmpty))
      .orElse(message.select("reasoning_items").asOpt[Seq[JsObject]].map(fromResponsesItems).filter(_.nonEmpty))
      .orElse {
        Seq("reasoning_content", "reasoning", "thinking", "reasoning_details").view
          .flatMap(field => message.select(field).asOptString.filter(_.trim.nonEmpty))
          .headOption
          .map(text => Seq(textDetail(text)))
      }
      .map(indexed)
      .getOrElse(Seq.empty)
  }

  /** whether a content block is reasoning rather than content */
  def isReasoningBlock(block: JsObject): Boolean = block.select("type").asOptString.exists(t => t == "thinking" || t == "redacted_thinking")

  /** the reasoning of the `thinking` / `redacted_thinking` blocks of an Anthropic message (or the `thinking` chunks of a Mistral one) */
  def fromContentBlocks(blocks: Seq[JsObject]): Seq[JsObject] = {
    blocks.filter(isReasoningBlock).flatMap { block =>
      block.select("type").asOptString match {
        case Some("thinking") => block.select("thinking").asOpt[JsValue] match {
          case Some(JsString(thinking)) =>
            val signature = block.select("signature").asOptString.filter(_.nonEmpty)
            signature.flatMap(unwrap) match {
              // the envelope does not repeat the text of the block
              case Some(details) => details.map {
                case d if isText(d) && textOf(d).isEmpty => d ++ Json.obj("text" -> thinking)
                case d if isSummary(d) && summaryOf(d).isEmpty => d ++ Json.obj("summary" -> thinking)
                case d => d
              }
              case None => Seq(textDetail(thinking, if (signature.isDefined) Formats.Anthropic else Formats.Unknown, signature))
            }
          // a Mistral `thinking` chunk nests its reasoning in text chunks, and may be signed
          case Some(JsArray(parts)) =>
            val text = parts.flatMap {
              case JsString(t) => t.some
              case part => part.select("text").asOptString
            }.mkString("")
            val signature = block.select("signature").asOptString.filter(_.nonEmpty)
            if (text.isEmpty && signature.isEmpty) Seq.empty else Seq(textDetail(text, Formats.Unknown, signature))
          case _ => Seq.empty
        }
        case Some("redacted_thinking") => block.select("data").asOptString.filter(_.nonEmpty).toSeq.flatMap { data =>
          unwrap(data).getOrElse(Seq(encryptedDetail(data, Formats.Anthropic)))
        }
        case _ => Seq.empty
      }
    }
  }

  /** the reasoning of `/responses` reasoning items (`{"type": "reasoning", "id", "summary", "content", "encrypted_content"}`) */
  def fromResponsesItems(items: Seq[JsObject]): Seq[JsObject] = {
    items.filter(_.select("type").asOptString.contains("reasoning")).flatMap { item =>
      val id = item.select("id").asOptString.filter(_.nonEmpty)
      val encrypted = item.select("encrypted_content").asOptString.filter(_.nonEmpty)
      encrypted.flatMap(unwrap) match {
        case Some(details) => details
        case None =>
          val summaries = item.select("summary").asOpt[Seq[JsObject]].getOrElse(Seq.empty).flatMap(_.select("text").asOptString)
          val texts = item.select("content").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
            .filter(_.select("type").asOptString.forall(t => t == "reasoning_text" || t == "text"))
            .flatMap(_.select("text").asOptString)
          // an item replayed by its id alone (`store: true`) is still a reasoning item the provider needs: its
          // summary, even empty, is what keeps its id
          val summaryDetails = if (summaries.isEmpty && texts.isEmpty && encrypted.isEmpty) Seq(summaryDetail("", Formats.OpenAiResponses, id))
            else summaries.map(s => summaryDetail(s, Formats.OpenAiResponses, id))
          summaryDetails ++
            texts.map(t => textDetail(t, Formats.OpenAiResponses, None, id)) ++
            encrypted.map(e => encryptedDetail(e, Formats.OpenAiResponses, id)).toSeq
      }
    }
  }

  // ---------------------------------------------------------------------------------------------------------------
  //  envelope
  // ---------------------------------------------------------------------------------------------------------------

  def wrap(details: Seq[JsObject]): String = {
    val payload = JsArray(details.map(d => d - "index")).stringify
    EnvelopePrefix + Base64.getUrlEncoder.withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
  }

  def unwrap(value: String): Option[Seq[JsObject]] = {
    if (!value.startsWith(EnvelopePrefix)) None
    else Try {
      val payload = new String(Base64.getUrlDecoder.decode(value.substring(EnvelopePrefix.length)), StandardCharsets.UTF_8)
      Json.parse(payload).as[Seq[JsObject]]
    }.toOption
  }

  // ---------------------------------------------------------------------------------------------------------------
  //  writing
  // ---------------------------------------------------------------------------------------------------------------

  /** the `thinking` / `redacted_thinking` blocks of an Anthropic message */
  def toAnthropicBlocks(details: Seq[JsObject], forAnthropicApi: Boolean): Seq[JsObject] = {
    details.flatMap { detail =>
      val anthropic = formatOf(detail) == Formats.Anthropic
      typeOf(detail) match {
        case Types.Text if anthropic && signatureOf(detail).isDefined =>
          Seq(Json.obj("type" -> "thinking", "thinking" -> textOf(detail).getOrElse(""), "signature" -> signatureOf(detail).get))
        case Types.Encrypted if anthropic && dataOf(detail).isDefined =>
          Seq(Json.obj("type" -> "redacted_thinking", "data" -> dataOf(detail).get))
        // the Anthropic api only takes back what it signed
        case _ if forAnthropicApi => Seq.empty
        // a caller speaking the Anthropic format reads the reasoning of the other providers in a `thinking` block,
        // and the gateway reads the original back from its signature
        case Types.Text | Types.Summary =>
          Seq(Json.obj("type" -> "thinking", "thinking" -> readableOf(detail).getOrElse(""), "signature" -> foreignSignature(detail)))
        case _ => Seq(Json.obj("type" -> "redacted_thinking", "data" -> wrap(Seq(detail))))
      }
    }
  }

  /** the signature of a `thinking` block holding the reasoning of another format: the detail without its text */
  def foreignSignature(detail: JsObject): String = wrap(Seq(detail - "text" - "summary"))

  /**
   * The reasoning items of a `/responses` payload. A native `/responses` api only gets back the items it wrote
   * (`openai-responses-v1`, with their id); a caller speaking the `/responses` format gets every detail, the ones of
   * another format in a single item whose `encrypted_content` keeps what the format can not hold.
   */
  def toResponsesItems(details: Seq[JsObject], forResponsesApi: Boolean, newId: () => String): Seq[JsObject] = {
    val (native, foreign) = details.partition(d => formatOf(d) == Formats.OpenAiResponses && idOf(d).isDefined)
    val nativeItems = native.groupBy(d => idOf(d).get).toSeq.sortBy { case (_, ds) => details.indexOf(ds.head) }.map { case (id, ds) =>
      val summaries = ds.filter(isSummary).flatMap(summaryOf).filter(_.nonEmpty)
      val texts = ds.filter(isText).flatMap(textOf).filter(_.nonEmpty)
      val encrypted = ds.filter(isEncrypted).flatMap(dataOf).headOption
      Json.obj(
        "type" -> "reasoning",
        "id" -> id,
        "summary" -> JsArray(summaries.map(s => Json.obj("type" -> "summary_text", "text" -> s))),
      // the raw reasoning of the open models (gpt-oss), which the apis serving them take back
      ).applyOnIf(texts.nonEmpty) { o =>
        o ++ Json.obj("content" -> JsArray(texts.map(t => Json.obj("type" -> "reasoning_text", "text" -> t))))
      }.applyOnWithOpt(encrypted) { case (o, e) => o ++ Json.obj("encrypted_content" -> e) }
    }
    val foreignItems = if (forResponsesApi || foreign.isEmpty) Seq.empty else {
      val texts = foreign.flatMap(readableOf).filter(_.nonEmpty)
      // plain text needs no envelope: the text of the item is all there is
      val needsEnvelope = foreign.exists(d => !isText(d) || signatureOf(d).isDefined || formatOf(d) != Formats.Unknown)
      Seq(Json.obj(
        "type" -> "reasoning",
        "id" -> newId(),
        "summary" -> JsArray(),
        "content" -> JsArray(texts.map(t => Json.obj("type" -> "reasoning_text", "text" -> t))),
      ).applyOnIf(needsEnvelope) { o => o ++ Json.obj("encrypted_content" -> wrap(foreign)) })
    }
    nativeItems ++ foreignItems
  }

  private val reasoningFields = Seq("reasoning", "reasoning_content", "reasoning_details", "thinking", "reasoning_items")

  /**
   * Writes the reasoning of an assistant message the way an api takes it back. `message` is the message as the
   * provider client serialized it; the reasoning fields the caller sent never reach the provider as they are.
   */
  def write(message: JsObject, details: Seq[JsObject], input: ReasoningInput): JsObject = {
    val cleaned = reasoningFields.foldLeft(message)(_ - _)
    // only the plain text of a reasoning goes to an api that reads plain text: a summary of OpenAI is not what the
    // model thought, and the encrypted reasoning can only be read by the provider that wrote it
    lazy val plainText = text(details.filter(isText))
    input match {
      // DeepSeek refuses a conversation with tools whose assistant messages lack the field, even empty
      case ReasoningInput.ContentAlways => cleaned ++ Json.obj("reasoning_content" -> plainText.getOrElse[String](""))
      case _ if details.isEmpty => cleaned
      case ReasoningInput.Skip | ReasoningInput.AnthropicBlocks | ReasoningInput.MistralChunks => cleaned
      case ReasoningInput.Details(model) =>
        val kept = forOpenRouterModel(details, model)
        if (kept.isEmpty) cleaned else cleaned ++ Json.obj("reasoning_details" -> JsArray(indexed(kept)))
      case ReasoningInput.Content => plainText.fold(cleaned)(t => cleaned ++ Json.obj("reasoning_content" -> t))
      case ReasoningInput.Field => plainText.fold(cleaned)(t => cleaned ++ Json.obj("reasoning" -> t))
      case ReasoningInput.Thinking => plainText.fold(cleaned)(t => cleaned ++ Json.obj("thinking" -> t))
    }
  }

  // the reasoning formats of the closed models, which only their own provider reads
  private val closedFormats: Map[String, Seq[String]] = Map(
    Formats.Anthropic -> Seq("anthropic/", "claude"),
    Formats.Gemini -> Seq("google/", "gemini"),
    Formats.OpenAiResponses -> Seq("openai/", "gpt-", "o1", "o3", "o4"),
    "azure-openai-responses-v1" -> Seq("openai/", "gpt-", "o1", "o3", "o4"),
    "xai-responses-v1" -> Seq("x-ai/", "grok"),
  )

  /**
   * What OpenRouter can be sent back for a model: the reasoning of a closed model only goes back to a model of the
   * same family, and the plain text of the others not to Claude nor Gemini, which OpenRouter would give it as an
   * unsigned thinking block they refuse.
   */
  def forOpenRouterModel(details: Seq[JsObject], model: Option[String]): Seq[JsObject] = {
    val name = model.getOrElse("").toLowerCase
    def isFamily(format: String): Boolean = closedFormats.get(format).exists(_.exists(name.contains))
    val signedOnly = isFamily(Formats.Anthropic) || isFamily(Formats.Gemini)
    details.filter { detail =>
      val format = formatOf(detail)
      // the text of Claude and of Gemini only goes back signed
      val unsignedText = isText(detail) && signatureOf(detail).isEmpty && (format == Formats.Anthropic || format == Formats.Gemini)
      if (closedFormats.contains(format)) isFamily(format) && !unsignedText
      else !signedOnly
    }
  }

  /** the `thinking` chunks of a Mistral message: the text of the reasoning, signed when Mistral signed it */
  def toMistralChunks(details: Seq[JsObject]): Seq[JsObject] = {
    details.filter(isText).flatMap { detail =>
      textOf(detail).filter(_.nonEmpty).map { text =>
        Json.obj("type" -> "thinking", "thinking" -> Json.arr(Json.obj("type" -> "text", "text" -> text)))
          .applyOnWithOpt(signatureOf(detail).filter(_ => formatOf(detail) == Formats.Unknown)) { case (o, s) => o ++ Json.obj("signature" -> s) }
      }
    }
  }

  /** the message with `details` as its only reasoning */
  def replaceIn(message: JsObject, details: Seq[JsObject]): JsObject = {
    val withoutReasoning = reasoningFields.foldLeft(message)(_ - _)
      .applyOnWithOpt(message.select("content").asOpt[Seq[JsValue]]) { case (obj, blocks) =>
        obj ++ Json.obj("content" -> JsArray(blocks.filterNot {
          case o: JsObject => isReasoningBlock(o)
          case _ => false
        }))
      }
    if (details.isEmpty) withoutReasoning else withoutReasoning ++ Json.obj("reasoning_details" -> JsArray(indexed(details)))
  }
}

/**
 * The tool calls the gateway runs itself (functions, MCP, A2A, search) come back as an assistant message and the
 * results, which are appended to the conversation before the provider is called again. The reasoning that led to
 * the calls goes with them: the models that think between tool calls need it, and Anthropic refuses the next call
 * without it when thinking is enabled.
 */
object ReasoningToolLoop {

  /**
   * The messages of one round of tool calls in the OpenAI format: one assistant message with every call and the
   * reasoning of the answer (`reasoning`, written for the api of the provider), then the results.
   */
  def openAi(callResps: Seq[JsValue], reasoning: JsObject): Seq[JsValue] = {
    val (assistants, others) = callResps.partition(_.select("role").asOptString.contains("assistant"))
    if (assistants.isEmpty) callResps else {
      val toolCalls = assistants.flatMap(_.select("tool_calls").asOpt[Seq[JsValue]].getOrElse(Seq.empty))
      val assistant = assistants.head.asObject ++ Json.obj("tool_calls" -> JsArray(toolCalls)) ++ reasoning
      // the results of the calls first, then what is not a result (what some providers need said again as a user)
      val (results, rest) = others.partition(_.select("role").asOptString.contains("tool"))
      Seq(assistant) ++ results ++ rest
    }
  }

  /**
   * The messages of one round of tool calls in the Anthropic format: one assistant message starting with the
   * reasoning blocks and holding every `tool_use` block, then one user message with every `tool_result` block, as
   * the Anthropic api wants them.
   */
  def anthropic(callResps: Seq[JsValue], reasoningBlocks: Seq[JsObject]): Seq[JsValue] = {
    val (assistants, users) = callResps.partition(_.select("role").asOptString.contains("assistant"))
    def blocks(messages: Seq[JsValue]): Seq[JsValue] = messages.flatMap { m =>
      m.select("content").asOpt[Seq[JsValue]].getOrElse(m.select("content").asOptString.map(t => Seq(Json.obj("type" -> "text", "text" -> t))).getOrElse(Seq.empty))
    }
    if (assistants.isEmpty) callResps else Seq(
      Json.obj("role" -> "assistant", "content" -> JsArray(reasoningBlocks ++ blocks(assistants))),
    ) ++ (if (users.isEmpty) Seq.empty else Seq(Json.obj("role" -> "user", "content" -> JsArray(blocks(users)))))
  }

  /** the reasoning blocks an Anthropic answer starts with, as it wrote them */
  def anthropicReasoningBlocks(content: Seq[JsObject]): Seq[JsObject] = content.filter(Reasoning.isReasoningBlock)
}

/**
 * How an api takes back the reasoning of the assistant messages of a conversation, when it does on its chat
 * endpoint. `Skip` for the apis that do not, or that refuse a field they do not know.
 */
enum ReasoningInput {
  /** nothing: the api does not read it back (OpenAI, Gemini), or refuses fields it does not know */
  case Skip
  /** `reasoning_details`, unchanged, the ones the model can read: OpenRouter */
  case Details(model: Option[String])
  /** `reasoning_content`: Moonshot, Z.ai and the servers built on vLLM or SGLang */
  case Content
  /** `reasoning_content` on every assistant message, even empty: DeepSeek */
  case ContentAlways
  /** `reasoning`: Groq, the OpenAI api of Ollama */
  case Field
  /** `thinking`: the native api of Ollama */
  case Thinking
  /** `thinking` / `redacted_thinking` content blocks, the ones Anthropic signed only */
  case AnthropicBlocks
  /** `thinking` chunks of the content: Mistral */
  case MistralChunks
}

object ReasoningInput {

  /** the names an operator can force on a provider with its `reasoning_input` option */
  def fromName(name: String, model: Option[String]): Option[ReasoningInput] = name.toLowerCase.trim match {
    case "none" | "skip" => ReasoningInput.Skip.some
    case "reasoning_details" | "details" => ReasoningInput.Details(model).some
    case "reasoning_content" | "content" => ReasoningInput.Content.some
    case "reasoning_content_always" => ReasoningInput.ContentAlways.some
    case "reasoning" | "field" => ReasoningInput.Field.some
    case "thinking" => ReasoningInput.Thinking.some
    case _ => None
  }

  /**
   * How a provider served by the OpenAI client takes reasoning back, from its kind and the host it calls: a provider
   * of kind `openai` or `openai-compatible` pointed at OpenRouter is OpenRouter. The servers nobody knows get
   * `reasoning_content`, the field vLLM, SGLang, DeepSeek, Moonshot and Z.ai read.
   */
  def forOpenAiApi(kind: String, baseUrl: String, model: Option[String]): ReasoningInput = {
    val host = scala.util.Try(new java.net.URI(baseUrl).getHost).toOption.flatMap(Option.apply).getOrElse(baseUrl).toLowerCase
    val k = kind.toLowerCase
    if (host.endsWith("openrouter.ai") || k == "openrouter") ReasoningInput.Details(model)
    else if (host == "api.openai.com" || host.endsWith(".openai.azure.com") || host.endsWith("models.ai.azure.com") || host.endsWith("services.ai.azure.com")) ReasoningInput.Skip
    else if (host.endsWith("api.deepseek.com") || k == "deepseek") ReasoningInput.ContentAlways
    else if (host.endsWith("api.mistral.ai")) ReasoningInput.Skip
    else if (host.endsWith("api.groq.com")) ReasoningInput.Field
    else if (host.endsWith("api.x.ai")) ReasoningInput.Skip
    else if (host.endsWith("generativelanguage.googleapis.com") || k == "gemini") ReasoningInput.Skip
    else if (k == "openai" || k == "azure-ai-foundry") ReasoningInput.Skip
    else if (k == "ollama" || k == "ollama-openai" || baseUrl.contains(":11434")) ReasoningInput.Field
    else ReasoningInput.Content
  }
}

/**
 * Puts back together the reasoning of a streamed answer: the text arrives in pieces, the `reasoning_details` in
 * pieces of the same index (text, then signature for Anthropic).
 */
class ReasoningAccumulator {

  private val details = scala.collection.mutable.ArrayBuffer.empty[JsObject]
  private val plain = new StringBuilder()

  def add(chunkDetails: Seq[JsObject]): Unit = chunkDetails.foreach { detail =>
    val typ = Reasoning.typeOf(detail)
    val index = detail.select("index").asOpt[Int]
    // an encrypted reasoning arrives whole, two of them are two reasonings
    val existing = if (typ == Reasoning.Types.Encrypted) -1 else details.lastIndexWhere { d =>
      Reasoning.typeOf(d) == typ && d.select("index").asOpt[Int] == index && (Reasoning.idOf(detail).isEmpty || Reasoning.idOf(d) == Reasoning.idOf(detail))
    }
    if (existing < 0) {
      details += detail
    } else {
      val current = details(existing)
      def concat(field: String): JsObject = (current.select(field).asOptString, detail.select(field).asOptString) match {
        case (Some(a), Some(b)) => Json.obj(field -> (a + b))
        case (None, Some(b)) => Json.obj(field -> b)
        case _ => Json.obj()
      }
      val merged = (current ++ JsObject(detail.value.filter { case (k, v) => k != "text" && k != "summary" && k != "data" && v != JsNull && v != JsString("") }))
        ++ concat("text") ++ concat("summary") ++ concat("data")
      details.update(existing, merged)
    }
  }

  def addText(text: String): Unit = plain.append(text)

  /** what was accumulated: the details when the provider streamed some, its text otherwise */
  def result: Seq[JsObject] = {
    if (details.nonEmpty) Reasoning.indexed(details.toSeq.map(_ - "index"))
    else if (plain.nonEmpty) Seq(Reasoning.textDetail(plain.toString())).map(_ ++ Json.obj("index" -> 0))
    else Seq.empty
  }

  def isEmpty: Boolean = details.isEmpty && plain.isEmpty
}
