package com.cloud.apim.otoroshi.extensions.aigateway.assistant.logic

import play.api.libs.json._

import scala.util.matching.Regex

object ExpressionLanguage {

  private val ExprRegex: Regex = """\$\{([^}]+)\}""".r
  private val NameRegex: Regex = """^([a-zA-Z_][a-zA-Z0-9_-]*)""".r
  private val PathToken: Regex = """([a-zA-Z_][a-zA-Z0-9_-]*)|\[(\d+)\]""".r

  def expandValue(v: JsValue, ctx: Map[String, JsValue]): Either[String, JsValue] = v match {
    case JsString(s) => expandString(s, ctx)
    case obj: JsObject =>
      obj.fields.foldLeft[Either[String, Vector[(String, JsValue)]]](Right(Vector.empty)) {
        case (Left(err), _) => Left(err)
        case (Right(acc), (k, vv)) => expandValue(vv, ctx).map(nv => acc :+ (k -> nv))
      }.map(JsObject(_))
    case arr: JsArray =>
      arr.value.foldLeft[Either[String, Vector[JsValue]]](Right(Vector.empty)) {
        case (Left(err), _) => Left(err)
        case (Right(acc), it) => expandValue(it, ctx).map(nv => acc :+ nv)
      }.map(JsArray(_))
    case other => Right(other)
  }

  def expandString(s: String, ctx: Map[String, JsValue]): Either[String, JsValue] = {
    val matches = ExprRegex.findAllMatchIn(s).toList
    if (matches.isEmpty) Right(JsString(s))
    else if (matches.size == 1 && matches.head.matched == s) {
      resolve(matches.head.group(1).trim, ctx)
    } else {
      var firstError: Option[String] = None
      val replaced = ExprRegex.replaceAllIn(s, m => {
        val expr = m.group(1).trim
        resolve(expr, ctx) match {
          case Right(JsString(v)) => Regex.quoteReplacement(v)
          case Right(other) => Regex.quoteReplacement(Json.stringify(other))
          case Left(err) =>
            if (firstError.isEmpty) firstError = Some(err)
            ""
        }
      })
      firstError match {
        case Some(err) => Left(err)
        case None => Right(JsString(replaced))
      }
    }
  }

  def resolve(expr: String, ctx: Map[String, JsValue]): Either[String, JsValue] = {
    NameRegex.findFirstMatchIn(expr) match {
      case None => Left(s"invalid reference '$expr'")
      case Some(m) =>
        val name = m.group(1)
        val rest = expr.substring(m.end)
        ctx.get(name) match {
          case None => Left(s"unknown reference '$name' in '$${$expr}'")
          case Some(value) => walkPath(value, rest).left.map(err => s"$${$expr}: $err")
        }
    }
  }

  def walkPath(start: JsValue, path: String): Either[String, JsValue] = {
    if (path.isEmpty) Right(start)
    else {
      PathToken.findAllMatchIn(path).foldLeft[Either[String, JsValue]](Right(start)) {
        case (Left(err), _) => Left(err)
        case (Right(v), m) =>
          Option(m.group(1)) match {
            case Some(key) => v match {
              case obj: JsObject => obj.value.get(key).map(Right(_)).getOrElse(Left(s"key '$key' not found"))
              case _ => Left(s"cannot access key '$key' on non-object")
            }
            case None =>
              val idx = m.group(2).toInt
              v match {
                case arr: JsArray => arr.value.lift(idx).map(Right(_)).getOrElse(Left(s"index $idx out of bounds"))
                case _ => Left(s"cannot index non-array at [$idx]")
              }
          }
      }
    }
  }
}
