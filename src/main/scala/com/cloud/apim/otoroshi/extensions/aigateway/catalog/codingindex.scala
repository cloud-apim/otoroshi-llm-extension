package com.cloud.apim.otoroshi.extensions.aigateway.catalog

import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*

import scala.util.Try

/**
 * How good a model is at writing code, from the Coding Index of Artificial Analysis (https://artificialanalysis.ai/).
 * The scores are bundled in `data/coding-index.json`, refreshed by `scripts/update-data-files.mjs` from their data
 * api, and keyed by the slug Artificial Analysis gives a model (`gpt-5-5`, `claude-opus-4-8`).
 *
 * A model of a provider is rarely named exactly that way: it has a vendor path, a snapshot date, dots where the
 * slug has dashes, or is the name of a deployment. A model gets the score of the longest slug it carries, compared
 * word by word so that `o3` is not found in `gemini-pro-3`, and never the score of an earlier version (`gpt-5` is
 * not the score of `gpt-5.5`). A model nobody scored has no score: the router ranks it last.
 */
final class CodingIndex(scores: Seq[(String, Double)]) {

  import CodingIndex.*

  private case class Entry(words: Seq[String], compact: String, score: Double)

  // the longest slugs first: the most specific one wins
  private val entries: Seq[Entry] = scores
    .map { case (slug, score) => Entry(words(slug), compact(slug), score) }
    .filter(_.words.nonEmpty)
    .sortBy(e => (-e.words.size, -e.compact.length))

  val size: Int = entries.size

  def scoreFor(model: String): Option[Double] = {
    // without its vendor path, its routing tag and its snapshot date
    val name = ModelIds.name(model)
    val named = words(name)
    if (named.isEmpty) None
    else {
      entries.find(e => carries(named, e.words)(_ == _))
        // the same name, cut differently: `qwen-3.7-max` and `qwen3-7-max`
        .orElse(entries.find(_.compact == compact(name)))
        // the same words in another order: `claude-haiku-4-5` and `claude-4-5-haiku`
        .orElse(entries.find(e => carries(named, e.words)(_.sorted == _.sorted)))
        .map(_.score)
    }
  }
}

object CodingIndex {

  val resource = "data/coding-index.json"

  private def words(name: String): Seq[String] = name.toLowerCase.split("[^a-z0-9]+").toSeq.filter(_.nonEmpty)

  private def compact(name: String): String = name.toLowerCase.replaceAll("[^a-z0-9]", "")

  // one or two digits after a name are the rest of its version: `gpt-5` followed by `5` is another model
  private def versionPart(word: String): Boolean = word.length <= 2 && word.forall(_.isDigit)

  // the words of the slug are found together in the name, and what follows them is not the rest of a version
  private def carries(named: Seq[String], slug: Seq[String])(same: (Seq[String], Seq[String]) => Boolean): Boolean = {
    (0 to named.size - slug.size).exists { at =>
      same(named.slice(at, at + slug.size), slug) && !named.lift(at + slug.size).exists(versionPart)
    }
  }

  // `{ "models": [ { "slug": "gpt-5-5", "coding_index": 59.1 } ] }`
  def parse(json: JsValue): CodingIndex = new CodingIndex(
    json.select("models").asOpt[Seq[JsObject]].getOrElse(Seq.empty).flatMap { model =>
      for {
        slug <- model.select("slug").asOptString.map(_.trim).filter(_.nonEmpty)
        score <- model.select("coding_index").asOpt[Double]
      } yield (slug, score)
    }
  )

  // read once: an extension that cannot read its own data file routes on prices alone
  lazy val bundled: CodingIndex = Try {
    val stream = classOf[CodingIndex].getClassLoader.getResourceAsStream(resource)
    try parse(Json.parse(stream)) finally stream.close()
  }.getOrElse(new CodingIndex(Seq.empty))
}
