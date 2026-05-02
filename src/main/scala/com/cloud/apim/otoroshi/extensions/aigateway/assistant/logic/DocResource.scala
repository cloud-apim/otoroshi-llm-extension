package com.cloud.apim.otoroshi.extensions.aigateway.assistant.logic

import otoroshi.env.Env
import otoroshi.utils.syntax.implicits._

import java.net.URI
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object DocResource {

  case class StartingPoint(topic: String, title: String, url: String, description: String)

  val allowlist: Set[String] = Set("www.otoroshi.io", "cloud-apim.github.io", "maif.github.io")
  val fetchTimeout: FiniteDuration = 10.seconds
  val maxHtmlBytes: Int = 1000000

  val startingPoints: Seq[StartingPoint] = Seq(
    StartingPoint("overview", "Otoroshi documentation", "https://www.otoroshi.io/docs/index.html",
      "Entry point to the Otoroshi documentation (concepts, architecture, tutorials)."),
    StartingPoint("entities", "Otoroshi entities", "https://www.otoroshi.io/docs/entities/index.html",
      "Core entities: routes, services, apikeys, certificates, auth modules."),
    StartingPoint("admin-api", "Admin API usage", "https://www.otoroshi.io/docs/api",
      "How to authenticate and call the Otoroshi Admin API."),
    StartingPoint("admin-api-resources", "Admin API resources", "https://maif.github.io/otoroshi/devmanual/docs/topics/generic-resource-api",
      "How consume the Otoroshi Admin API."),
    StartingPoint("plugins", "Plugins & extensions", "https://www.otoroshi.io/docs/plugins/index.html",
      "Built-in plugins and how to write your own."),
    StartingPoint("llm-extension", "Otoroshi LLM Extension", "https://cloud-apim.github.io/otoroshi-llm-extension/docs/overview",
      "Cloud-APIM's LLM extension: providers, prompts, guardrails, semantic cache."),
    StartingPoint("biscuit", "Otoroshi Biscuit Studio", "https://cloud-apim.github.io/otoroshi-biscuit-studio/docs/overview",
      "Biscuit tokens: forges, verifiers, attenuators, key-pairs."),
  )

  def listStartingPoints(topic: Option[String]): Seq[StartingPoint] = {
    topic.map(_.toLowerCase) match {
      case None | Some("") => startingPoints
      case Some(t) => startingPoints.filter(sp => s"${sp.topic} ${sp.title} ${sp.description}".toLowerCase.contains(t))
    }
  }

  sealed trait FetchResult
  object FetchResult {
    case class Ok(content: String) extends FetchResult
    case class InvalidUrl(message: String) extends FetchResult
    case class NotAllowed(host: String) extends FetchResult
    case class Failed(message: String) extends FetchResult
  }

  def fetch(url: String)(implicit ec: ExecutionContext, env: Env): Future[FetchResult] = {
    val parsed = scala.util.Try(new URI(url)).toOption
    parsed match {
      case None => FetchResult.InvalidUrl(s"Invalid URL: $url").vfuture.asInstanceOf[Future[FetchResult]]
      case Some(uri) =>
        if (uri.getScheme != "https") Future.successful(FetchResult.InvalidUrl(s"Only https:// URLs are allowed (got ${uri.getScheme})"))
        else {
          val host = Option(uri.getHost).map(_.toLowerCase).getOrElse("")
          if (!allowlist.contains(host)) Future.successful(FetchResult.NotAllowed(host))
          else {
            env.Ws.url(url)
              .withHttpHeaders("Accept" -> "text/markdown, text/html;q=0.9, */*;q=0.1")
              .withFollowRedirects(false)
              .withRequestTimeout(fetchTimeout)
              .get()
              .map { resp =>
                if (resp.status >= 300 && resp.status < 400) FetchResult.Failed(s"Unexpected redirect (HTTP ${resp.status}) fetching $url")
                else if (resp.status < 200 || resp.status >= 400) FetchResult.Failed(s"HTTP ${resp.status} fetching $url")
                else {
                  val raw = resp.body
                  val capped = if (raw.length > maxHtmlBytes) raw.substring(0, maxHtmlBytes) else raw
                  val ct = resp.contentType
                  FetchResult.Ok(if (ct.contains("text/html")) htmlToText(capped) else capped)
                }
              }
              .recover { case t: Throwable => FetchResult.Failed(s"fetch failed: ${t.getMessage}") }
          }
        }
    }
  }

  def htmlToText(html: String): String = {
    html
      .replaceAll("(?is)<script[^>]*>.*?</script>", "")
      .replaceAll("(?is)<style[^>]*>.*?</style>", "")
      .replaceAll("(?is)<nav[^>]*>.*?</nav>", "")
      .replaceAll("(?is)<header[^>]*>.*?</header>", "")
      .replaceAll("(?is)<footer[^>]*>.*?</footer>", "")
      .replaceAll("(?i)<br\\s*/?>", "\n")
      .replaceAll("(?i)</p>", "\n\n")
      .replaceAll("(?i)</h[1-6]>", "\n\n")
      .replaceAll("(?i)</li>", "\n")
      .replaceAll("<[^>]+>", "")
      .replace("&nbsp;", " ")
      .replace("&amp;", "&")
      .replace("&lt;", "<")
      .replace("&gt;", ">")
      .replace("&quot;", "\"")
      .replace("&#39;", "'")
      .replaceAll("\n{3,}", "\n\n")
      .trim
  }
}
