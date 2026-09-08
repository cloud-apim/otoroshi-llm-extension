package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import com.cloud.apim.otoroshi.extensions.aigateway.guardrails.{PlaceholderAllocator, RampartEngine, RampartPiiGuardrail}
import otoroshi.env.Env
import otoroshi.events.CustomDataExporterTransformer
import otoroshi.next.plugins.api.*
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class RampartEventRedactorConfig(
  // json paths whose whole subtree is scrubbed. Everything else in the event is left alone: an otoroshi event
  // is mostly technical fields, and redacting them all would eat urls, ips and identifiers the audit needs.
  paths: Seq[String] = RampartEventRedactorConfig.defaultPaths,
  minScore: Float = 0.4f,
  entities: Set[String] = RampartPiiGuardrail.defaultEntities,
  // the model is what costs: on a busy exporter, the deterministic recognizers alone may be the only
  // affordable option. They catch emails, ips, ssn and card numbers, but no names or addresses.
  deterministicOnly: Boolean = false,
) extends NgPluginConfig {
  def json: JsValue = RampartEventRedactorConfig.format.writes(this)
}

object RampartEventRedactorConfig {

  // where personal data actually sits in the events this extension emits
  val defaultPaths: Seq[String] = Seq("input_prompt", "output", "input_body")

  val default = RampartEventRedactorConfig()

  val format = new Format[RampartEventRedactorConfig] {
    override def reads(json: JsValue): JsResult[RampartEventRedactorConfig] = Try {
      RampartEventRedactorConfig(
        paths = json.select("paths").asOpt[Seq[String]].filter(_.nonEmpty).getOrElse(defaultPaths),
        minScore = json.select("min_score").asOpt[Double].orElse(json.select("min_score").asOpt[String].map(_.toDouble)).getOrElse(0.4).toFloat,
        entities = json.select("entities").asOpt[Seq[String]].map(_.toSet).getOrElse(RampartPiiGuardrail.defaultEntities),
        deterministicOnly = json.select("deterministic_only").asOpt[Boolean].getOrElse(false),
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(v) => JsSuccess(v)
    }
    override def writes(o: RampartEventRedactorConfig): JsValue = Json.obj(
      "paths" -> o.paths,
      "min_score" -> o.minScore.toDouble,
      "entities" -> o.entities.toSeq,
      "deterministic_only" -> o.deterministicOnly,
    )
  }

  val configFlow: Seq[String] = Seq("paths", "entities", "min_score", "deterministic_only")
  val configSchema: Option[JsObject] = Some(Json.obj(
    "paths" -> Json.obj("type" -> "array", "label" -> "Event paths to scrub"),
    "entities" -> Json.obj("type" -> "array", "label" -> "Entities to redact"),
    "min_score" -> Json.obj("type" -> "number", "label" -> "Min score", "props" -> Json.obj("step" -> 0.05)),
    "deterministic_only" -> Json.obj("type" -> "bool", "label" -> "Deterministic detectors only (no model)"),
  ))
}

object RampartEventRedactor {

  private val logger = play.api.Logger("cloud-apim-rampart-events")

  // what a field is replaced with when it could not be scrubbed. The event still leaves - losing an audit
  // line helps nobody - but nothing that was not scrubbed leaves with it.
  val redactionFailed = "**redaction-failed**"

  /** every string leaf under `json`, rewritten. Keys are left untouched: they are field names, not data. */
  def mapStrings(json: JsValue)(f: String => String): JsValue = json match {
    case JsString(value) => JsString(f(value))
    case JsArray(values) => JsArray(values.map(v => mapStrings(v)(f)))
    case JsObject(fields) => JsObject(fields.map { case (k, v) => (k, mapStrings(v)(f)) })
    case other => other
  }

  /** rewrites the value at a dotted path, doing nothing when the path is absent */
  def updateAt(json: JsValue, path: Seq[String])(f: JsValue => JsValue): JsValue = path.toList match {
    case Nil => f(json)
    case head :: rest =>
      json match {
        case obj: JsObject if obj.keys.contains(head) =>
          obj ++ Json.obj(head -> updateAt(obj.value(head), rest)(f))
        case other => other
      }
  }

  def redactText(text: String, config: RampartEventRedactorConfig, alloc: PlaceholderAllocator, engine: RampartEngine): String = {
    if (text.isEmpty) text else {
      val spans =
        if (config.deterministicOnly) engine.detectDeterministic(text, config.entities)
        else engine.detectAll(text, config.minScore, config.entities)
      if (spans.isEmpty) text else engine.redactText(text, spans, alloc)
    }
  }

  /**
   * Scrubs the configured paths of one event. A single allocator is shared across the whole event, so the same
   * person mentioned in the prompt and in the answer gets the same placeholder and the event stays readable.
   *
   * Fail-safe rather than fail-open: a plugin whose only job is to remove personal data must not hand the data
   * over when it breaks, so a path that could not be scrubbed is blanked instead of being passed through.
   */
  def redact(event: JsValue, config: RampartEventRedactorConfig)(using env: Env): JsValue =
    redact(event, config, RampartEngine.get)

  /** engine taken explicitly so the whole scrubbing logic can be exercised without an otoroshi instance */
  def redact(event: JsValue, config: RampartEventRedactorConfig, engine: RampartEngine): JsValue = {
    val alloc = new PlaceholderAllocator()
    redactWith(event, config.paths, text => redactText(text, config, alloc, engine))
  }

  /** the scrubbing loop itself, independent of how a string is scrubbed */
  def redactWith(event: JsValue, paths: Seq[String], scrub: String => String): JsValue = {
    paths.foldLeft(event) { (acc, path) =>
      val segments = path.split('.').toSeq.filter(_.nonEmpty)
      updateAt(acc, segments) { value =>
        Try(mapStrings(value)(scrub)) match {
          case Success(scrubbed) => scrubbed
          case Failure(err) =>
            logger.error(s"[rampart] could not scrub '${path}', blanking it: ${err.getMessage}", err)
            JsString(redactionFailed)
        }
      }
    }
  }
}

/**
 * Runs the local Rampart model over the sensitive parts of an event before a data exporter ships it out.
 *
 * Referenced from a data exporter as `customTransform: { kind: "plugin", ref: "cp:...RampartEventRedactor" }`.
 */
class RampartEventRedactor extends CustomDataExporterTransformer {

  override def name: String = "Cloud APIM - Rampart event redaction"
  override def description: Option[String] = "Removes personal data from events before a data exporter ships them out".some
  override def core: Boolean = false
  override def noJsForm: Boolean = true
  override def defaultConfigObject: Option[NgPluginConfig] = Some(RampartEventRedactorConfig.default)
  override def configFlow: Seq[String] = RampartEventRedactorConfig.configFlow
  override def configSchema: Option[JsObject] = RampartEventRedactorConfig.configSchema

  // the exporter's own `customTransform.config`, so two exporters can scrub differently: strict on the one
  // shipping outside, lighter on the internal one. A malformed config falls back to the defaults rather than
  // silently scrubbing nothing.
  override def project(evt: JsValue, config: JsValue)(using ec: ExecutionContext, env: Env): Future[JsValue] = {
    val cfg = RampartEventRedactorConfig.format.reads(config).asOpt.getOrElse(RampartEventRedactorConfig.default)
    RampartEventRedactor.redact(evt, cfg).vfuture
  }
}
