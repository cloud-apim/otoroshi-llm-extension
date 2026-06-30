package com.cloud.apim.otoroshi.extensions.aigateway.guardrails

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.djl.huggingface.tokenizers.jni.CharSpan
import ai.onnxruntime.{OnnxTensor, OrtEnvironment, OrtSession}
import com.cloud.apim.otoroshi.extensions.aigateway.decorators.{Guardrail, GuardrailResult}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatClient, ChatMessage, InputChatMessage, OutputChatMessage}
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.{JsObject, Json}
import play.api.libs.typedmap.TypedKey

import java.nio.LongBuffer
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

// a detected PII span, with char offsets relative to the text it was detected in
case class PiiSpan(entity: String, start: Int, end: Int, score: Float)

// allocates stable placeholders (e.g. [GIVEN_NAME_1]) for redacted values. Stable means the same value
// always maps to the same placeholder, which keeps redactions consistent across messages / turns.
class PlaceholderAllocator {
  private val counters = mutable.Map.empty[String, Int]
  private val byValue = mutable.LinkedHashMap.empty[(String, String), String]
  private val placeholderToValue = mutable.LinkedHashMap.empty[String, String]

  def placeholderFor(entity: String, value: String): String = {
    val key = (entity, value.trim.toLowerCase)
    byValue.get(key) match {
      case Some(ph) => ph
      case None =>
        val n = counters.getOrElse(entity, 0) + 1
        counters(entity) = n
        val ph = s"[${entity}_${n}]"
        byValue(key) = ph
        placeholderToValue(ph) = value
        ph
    }
  }

  def mapping: Map[String, String] = placeholderToValue.toMap
}

// The Rampart inference engine: ONNX token-classification model + deterministic recognizers + redaction.
// Loaded once and reused (the ONNX session and tokenizer are heavy and thread-safe for inference).
class RampartEngine(onnxModelBytes: Array[Byte], tokenizerJsonPath: java.nio.file.Path, configJsonString: String) {

  private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment
  private val session: OrtSession = ortEnv.createSession(onnxModelBytes, new OrtSession.SessionOptions())
  private val inputNames: java.util.Set[String] = session.getInputNames
  private val tokenizer: HuggingFaceTokenizer = HuggingFaceTokenizer.newInstance(tokenizerJsonPath)
  private val id2label: Array[String] = {
    val cfg = Json.parse(configJsonString).as[JsObject]
    val map = (cfg \ "id2label").as[JsObject]
    val arr = new Array[String](map.keys.size)
    map.fields.foreach { case (k, v) => arr(k.toInt) = v.as[String] }
    arr
  }

  // ---- detection -----------------------------------------------------------

  // all PII spans in a piece of text: deterministic recognizers (high precision) unioned with the model,
  // deterministic taking precedence on overlap. Only entities in `enabled` are returned.
  def detectAll(text: String, minScore: Float, enabled: Set[String]): Seq[PiiSpan] = {
    if (text == null || text.isEmpty) Seq.empty
    else {
      val det = deterministicDetect(text).filter(s => enabled.contains(s.entity))
      val mdl = modelDetect(text, minScore, enabled)
      resolveOverlaps(det.map(s => (s, 2)) ++ mdl.map(s => (s, 1)))
    }
  }

  private def deterministicDetect(text: String): Seq[PiiSpan] = {
    val out = mutable.ListBuffer.empty[PiiSpan]
    RampartEngine.simplePatterns.foreach { case (entity, rx) =>
      val m = rx.matcher(text)
      while (m.find()) out += PiiSpan(entity, m.start(), m.end(), 1f)
    }
    val m = RampartEngine.ccCandidate.matcher(text)
    while (m.find()) {
      if (RampartEngine.luhnValid(m.group())) out += PiiSpan("CREDIT_CARD", m.start(), m.end(), 1f)
    }
    out.toList
  }

  private def modelDetect(text: String, minScore: Float, enabled: Set[String]): Seq[PiiSpan] = {
    RampartEngine.chunk(text, RampartEngine.MaxChars).flatMap { case (offset, chunkText) =>
      detectChunk(chunkText, minScore, enabled).map(s => s.copy(start = s.start + offset, end = s.end + offset))
    }
  }

  private def detectChunk(text: String, minScore: Float, enabled: Set[String]): Seq[PiiSpan] = {
    val enc = tokenizer.encode(text)
    var ids = enc.getIds
    var mask = enc.getAttentionMask
    var types = enc.getTypeIds
    val spans = enc.getCharTokenSpans
    var seq = ids.length
    if (seq > 512) { // safety net: chunking keeps us well under 512, but never feed the model more than it supports
      ids = ids.take(512); mask = mask.take(512); types = types.take(512); seq = 512
    }
    val shape = Array(1L, seq.toLong)
    val inputs = new java.util.HashMap[String, OnnxTensor]()
    val t1 = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(ids), shape)
    val t2 = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(mask), shape)
    inputs.put("input_ids", t1)
    inputs.put("attention_mask", t2)
    val t3 = if (inputNames.contains("token_type_ids")) {
      val t = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(types), shape)
      inputs.put("token_type_ids", t)
      t
    } else null
    val result = session.run(inputs)
    try {
      val logits = result.get(0).getValue.asInstanceOf[Array[Array[Array[Float]]]](0) // [seq][numLabels]
      decodeBio(logits, spans, minScore, enabled, seq)
    } finally {
      t1.close(); t2.close(); if (t3 != null) t3.close(); result.close()
    }
  }

  // merges consecutive B-/I- tokens of the same entity into char spans, applying the recall-biased floor
  private def decodeBio(logits: Array[Array[Float]], spans: Array[CharSpan], minScore: Float, enabled: Set[String], seq: Int): Seq[PiiSpan] = {
    val out = mutable.ListBuffer.empty[PiiSpan]
    var curEntity: String = null
    var curStart = -1
    var curEnd = -1
    var curScore = 1f
    def flush(): Unit = {
      if (curEntity != null) { out += PiiSpan(curEntity, curStart, curEnd, curScore); curEntity = null }
    }
    val n = math.min(seq, math.min(logits.length, spans.length))
    var i = 0
    while (i < n) {
      val (arg, p) = RampartEngine.softmaxArgmax(logits(i))
      val label = id2label(arg)
      val span = spans(i)
      if (label != "O" && p >= minScore && span != null && enabled.contains(label.substring(2))) {
        val entity = label.substring(2)
        val isB = label.charAt(0) == 'B'
        if (curEntity == entity && !isB && span.getStart >= curEnd) {
          curEnd = span.getEnd
          curScore = math.min(curScore, p)
        } else {
          flush()
          curEntity = entity; curStart = span.getStart; curEnd = span.getEnd; curScore = p
        }
      } else {
        flush()
      }
      i += 1
    }
    flush()
    out.toList
  }

  // keeps non-overlapping spans, preferring higher priority (deterministic > model), then longer, then earlier
  private def resolveOverlaps(spans: Seq[(PiiSpan, Int)]): Seq[PiiSpan] = {
    val sorted = spans.sortBy { case (s, prio) => (s.start, -prio, -(s.end - s.start)) }
    val kept = mutable.ListBuffer.empty[PiiSpan]
    var lastEnd = -1
    sorted.foreach { case (s, _) =>
      if (s.start >= lastEnd && s.start < s.end) { kept += s; lastEnd = s.end }
    }
    kept.toList
  }

  // ---- redaction -----------------------------------------------------------

  // rewrites `text`, replacing each detected span with its stable placeholder (left-to-right, reading offsets
  // from the original text so they stay valid). The allocator is shared across messages for cross-message stability.
  def redactText(text: String, spans: Seq[PiiSpan], alloc: PlaceholderAllocator): String = {
    if (spans.isEmpty) text
    else {
      val sorted = spans.sortBy(_.start)
      val sb = new StringBuilder()
      var cursor = 0
      sorted.foreach { s =>
        if (s.start >= cursor && s.end <= text.length && s.start < s.end) {
          sb.append(text.substring(cursor, s.start))
          sb.append(alloc.placeholderFor(s.entity, text.substring(s.start, s.end)))
          cursor = s.end
        }
      }
      sb.append(text.substring(cursor))
      sb.toString
    }
  }
}

object RampartEngine {

  val MaxChars: Int = 800 // ~< 512 tokens for Latin scripts, with a per-chunk safety truncation in detectChunk

  // deterministic, high-precision recognizers for structured identifiers the model does not label by itself
  private val simplePatterns: Seq[(String, Pattern)] = Seq(
    "EMAIL" -> Pattern.compile("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}"""),
    "URL" -> Pattern.compile("""(?:https?://|www\.)[^\s"'<>)\]}]+""", Pattern.CASE_INSENSITIVE), // stop at quotes/brackets to stay safe on raw JSON bodies
    "IP_ADDRESS" -> Pattern.compile("""\b(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)\b"""),
    "SSN" -> Pattern.compile("""\b\d{3}-\d{2}-\d{4}\b""")
  )
  private val ccCandidate: Pattern = Pattern.compile("""\b(?:\d[ -]?){13,19}\b""")

  def luhnValid(s: String): Boolean = {
    val d = s.filter(_.isDigit)
    if (d.length < 13 || d.length > 19) false
    else {
      var sum = 0
      var alt = false
      var i = d.length - 1
      while (i >= 0) {
        var n = d.charAt(i) - '0'
        if (alt) { n *= 2; if (n > 9) n -= 9 }
        sum += n
        alt = !alt
        i -= 1
      }
      sum % 10 == 0
    }
  }

  // softmax probability of the argmax label = exp(0) / sum(exp(x - max)) = 1 / sum(exp(x - max))
  def softmaxArgmax(row: Array[Float]): (Int, Float) = {
    var max = Float.NegativeInfinity
    var arg = 0
    var i = 0
    while (i < row.length) { if (row(i) > max) { max = row(i); arg = i }; i += 1 }
    var sum = 0.0
    i = 0
    while (i < row.length) { sum += math.exp(row(i) - max); i += 1 }
    (arg, (1.0 / sum).toFloat)
  }

  // splits text into windows of at most `maxChars`, breaking on whitespace to avoid splitting a token
  def chunk(text: String, maxChars: Int): Seq[(Int, String)] = {
    if (text.length <= maxChars) Seq((0, text))
    else {
      val buf = mutable.ListBuffer.empty[(Int, String)]
      var start = 0
      while (start < text.length) {
        var end = math.min(start + maxChars, text.length)
        if (end < text.length) {
          val lastWs = text.lastIndexWhere(_.isWhitespace, end - 1)
          if (lastWs > start) end = lastWs + 1
        }
        buf += ((start, text.substring(start, end)))
        start = end
      }
      buf.toList
    }
  }

  private def resourceBytes(path: String)(implicit env: Env): Array[Byte] = {
    val is = env.environment.resourceAsStream(path).getOrElse(throw new RuntimeException(s"rampart: resource not found '$path'"))
    try {
      val buffer = new java.io.ByteArrayOutputStream()
      val data = new Array[Byte](16384)
      var n = is.read(data)
      while (n != -1) { buffer.write(data, 0, n); n = is.read(data) }
      buffer.toByteArray
    } finally is.close()
  }

  // the DJL tokenizer loads from a filesystem path, so extract the bundled tokenizer.json to a temp file once
  private def resourceToTemp(path: String)(implicit env: Env): java.nio.file.Path = {
    val tmp = java.nio.file.Files.createTempFile("rampart-tokenizer-", ".json")
    java.nio.file.Files.write(tmp, resourceBytes(path))
    tmp.toFile.deleteOnExit()
    tmp
  }

  @volatile private var _instance: RampartEngine = _
  def get(implicit env: Env): RampartEngine = {
    val ref = _instance
    if (ref != null) ref
    else synchronized {
      if (_instance == null) {
        val base = "cloudapim/extensions/ai/models/rampart"
        _instance = new RampartEngine(
          resourceBytes(s"$base/model_q4.onnx"),
          resourceToTemp(s"$base/tokenizer.json"),
          new String(resourceBytes(s"$base/config.json"), StandardCharsets.UTF_8)
        )
      }
      _instance
    }
  }

  // builds an engine straight from files on disk (used by tests, no otoroshi Env required)
  def fromPaths(onnx: java.nio.file.Path, tokenizer: java.nio.file.Path, config: java.nio.file.Path): RampartEngine =
    new RampartEngine(
      java.nio.file.Files.readAllBytes(onnx),
      tokenizer,
      new String(java.nio.file.Files.readAllBytes(config), StandardCharsets.UTF_8)
    )
}

object RampartPiiGuardrail {
  // entities redacted by default (the model's "redact by default" set + the deterministic structured ones).
  // CITY / STATE / ZIP_CODE are intentionally kept (coarse geo markers).
  val defaultEntities: Set[String] = Set(
    "GIVEN_NAME", "SURNAME", "PHONE", "TAX_ID", "BANK_ACCOUNT", "ROUTING_NUMBER",
    "GOVERNMENT_ID", "PASSPORT", "DRIVERS_LICENSE", "BUILDING_NUMBER", "STREET_NAME",
    "SECONDARY_ADDRESS", "EMAIL", "URL", "SSN", "CREDIT_CARD", "IP_ADDRESS"
  )
  // mapping placeholder -> original value, stored in attrs by the Before phase for After re-inflation
  val mappingKey: TypedKey[Map[String, String]] = TypedKey[Map[String, String]]("cloud-apim.ai-gateway.rampart.mapping")
  private val logger = play.api.Logger("cloud-apim-rampart-guardrail")
}

// Local, fast, deterministic PII guardrail backed by the Rampart ONNX model. Depending on `action` it can
// redact (rewrite the prompt with stable placeholders), block (deny when PII is found), or flag (log only).
// With `reinflate=true` and the After phase enabled, placeholders are restored in the (non-streamed) response.
class RampartPiiGuardrail extends Guardrail {

  override def isBefore: Boolean = true
  override def isAfter: Boolean = true
  override def manyMessages: Boolean = true // sees the whole conversation at once -> stable placeholders across turns

  override def pass(messages: Seq[ChatMessage], config: JsObject, provider: Option[AiProvider], chatClient: Option[ChatClient], attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[GuardrailResult] = {
    val action = config.select("action").asOpt[String].getOrElse("redact").toLowerCase.trim
    val minScore = config.select("min_score").asOpt[Double].orElse(config.select("min_score").asOpt[String].map(_.toDouble)).getOrElse(0.4).toFloat
    val entities = config.select("entities").asOpt[Seq[String]].map(_.toSet).getOrElse(RampartPiiGuardrail.defaultEntities)
    val reinflate = config.select("reinflate").asOpt[Boolean].getOrElse(false)

    val isAfterPhase = messages.nonEmpty && messages.forall(_.isInstanceOf[OutputChatMessage])
    val engine = RampartEngine.get

    if (isAfterPhase && reinflate) {
      attrs.get(RampartPiiGuardrail.mappingKey) match {
        case Some(mapping) if mapping.nonEmpty =>
          val nm = messages.map {
            case o: OutputChatMessage => o.transformContent(t => reinflateText(t, mapping))
            case m => m
          }
          if (nm == messages) GuardrailResult.GuardrailPass.vfuture
          else GuardrailResult.GuardrailTransform(nm).vfuture
        case _ => GuardrailResult.GuardrailPass.vfuture
      }
    } else {
      applyAction(messages, engine, action, minScore, entities, attrs, store = !isAfterPhase).vfuture
    }
  }

  private def applyAction(messages: Seq[ChatMessage], engine: RampartEngine, action: String, minScore: Float, entities: Set[String], attrs: TypedMap, store: Boolean)(implicit env: Env): GuardrailResult = {
    action match {
      case "block" =>
        if (messages.exists(m => engine.detectAll(m.wholeTextContent, minScore, entities).nonEmpty)) {
          GuardrailResult.GuardrailDenied("request blocked: personal information detected")
        } else {
          GuardrailResult.GuardrailPass
        }
      case "flag" =>
        if (messages.exists(m => engine.detectAll(m.wholeTextContent, minScore, entities).nonEmpty)) {
          RampartPiiGuardrail.logger.info("[rampart] personal information detected in messages")
        }
        GuardrailResult.GuardrailPass
      case _ => // redact
        val alloc = new PlaceholderAllocator()
        val nm = messages.map {
          case i: InputChatMessage => i.transformContent(t => engine.redactText(t, engine.detectAll(t, minScore, entities), alloc))
          case o: OutputChatMessage => o.transformContent(t => engine.redactText(t, engine.detectAll(t, minScore, entities), alloc))
          case m => m
        }
        if (alloc.mapping.isEmpty) {
          GuardrailResult.GuardrailPass
        } else {
          if (store) attrs.put(RampartPiiGuardrail.mappingKey -> alloc.mapping)
          GuardrailResult.GuardrailTransform(nm)
        }
    }
  }

  private def reinflateText(text: String, mapping: Map[String, String]): String = {
    mapping.foldLeft(text) { case (acc, (placeholder, original)) => acc.replace(placeholder, original) }
  }
}
