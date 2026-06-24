package com.cloud.apim.otoroshi.extensions.aigateway.a2a

import otoroshi.env.Env
import otoroshi.next.models.NgTlsConfig
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

// HTTP client used by A2AConnector to talk to a remote A2A agent over the JSON-RPC binding.
// - fetches and caches the Agent Card (agent-card.json, fallback agent.json), detecting the protocol version
// - sends SendMessage (v1.0) or message/send (v0.3 compat, decision Q7=B)
// All outgoing calls go through env.MtlsWs so TLS / trustAll / client certs are honored.
object A2AClient {

  // agent card cache: cacheId -> (card, version, fetchedAtMillis) — TTL 5 min (decision Q4=B)
  private val cardCache = new TrieMap[String, (AgentCard, A2AVersion, Long)]()
  private val cardTtlMillis: Long = 5 * 60 * 1000L

  private def wsCall(url: String, method: String, headers: Seq[(String, String)], tls: NgTlsConfig, timeout: FiniteDuration, body: Option[JsValue])(implicit env: Env, ec: ExecutionContext): Future[(Int, String)] = {
    env.MtlsWs
      .url(url, tls.legacy)
      .withMethod(method)
      .withHttpHeaders(headers: _*)
      .withRequestTimeout(timeout)
      .applyOnWithOpt(body) { case (b, js) => b.withBody(js.stringify) }
      .execute()
      .map(r => (r.status, r.body))
  }

  def fetchAgentCard(cacheId: String, baseUrl: String, cardPath: String, fallbackPath: String, headers: Seq[(String, String)], tls: NgTlsConfig, timeout: FiniteDuration, forceRefresh: Boolean = false)(implicit env: Env, ec: ExecutionContext): Future[Either[JsValue, (AgentCard, A2AVersion)]] = {
    val now = System.currentTimeMillis()
    cardCache.get(cacheId) match {
      case Some((card, version, ts)) if !forceRefresh && (now - ts) < cardTtlMillis => Right((card, version)).vfuture
      case _ =>
        val base = baseUrl.trim.stripSuffix("/")
        def tryFetch(path: String): Future[Option[AgentCard]] = {
          val p = if (path.startsWith("/")) path else "/" + path
          wsCall(base + p, "GET", headers, tls, timeout, None).map { case (status, body) =>
            if (status >= 200 && status < 300) Try(Json.parse(body)).toOption.map(AgentCard.from).filter(_.name.nonEmpty) else None
          }.recover { case _ => None }
        }
        tryFetch(cardPath).flatMap {
          case Some(card) => completeCard(cacheId, card, now)
          case None => tryFetch(fallbackPath).flatMap {
            case Some(card) => completeCard(cacheId, card, now)
            case None => Json.obj("error" -> s"unable to fetch agent card from ${base}").leftf
          }
        }
    }
  }

  private def completeCard(cacheId: String, card: AgentCard, now: Long): Future[Either[JsValue, (AgentCard, A2AVersion)]] = {
    val version = A2AVersion.fromCard(card.supportedInterfaces.headOption.map(_.protocolVersion))
    cardCache.put(cacheId, (card, version, now))
    Future.successful(Right((card, version)))
  }

  def invalidateCache(cacheId: String): Unit = cardCache.remove(cacheId)

  // pick the JSON-RPC interface url from the card (fallback to the configured base url)
  def jsonRpcEndpoint(card: AgentCard, fallback: String): String = {
    card.supportedInterfaces.find(_.protocolBinding.equalsIgnoreCase("JSONRPC")).map(_.url)
      .orElse(card.supportedInterfaces.headOption.map(_.url))
      .getOrElse(fallback)
  }

  def sendMessage(endpointUrl: String, version: A2AVersion, headers: Seq[(String, String)], tls: NgTlsConfig, timeout: FiniteDuration, message: A2AMessage)(implicit env: Env, ec: ExecutionContext): Future[Either[JsValue, String]] = {
    val method = if (version.isLegacy) "message/send" else "SendMessage"
    val msgJson = if (version.isLegacy) legacyMessageJson(message) else message.json
    val payload = Json.obj(
      "jsonrpc" -> "2.0",
      "id" -> 1,
      "method" -> method,
      "params" -> Json.obj("message" -> msgJson),
    )
    val hdrs = Seq("Content-Type" -> "application/json", "Accept" -> "application/json, text/event-stream") ++ headers
    wsCall(endpointUrl, "POST", hdrs, tls, timeout, Some(payload)).map { case (status, body) =>
      Try(Json.parse(body)).toOption match {
        case None => Left(Json.obj("error" -> s"invalid a2a response (status $status)", "body" -> body))
        case Some(json) =>
          json.select("error").asOpt[JsValue] match {
            case Some(err) => Left(err)
            case None =>
              val result = json.select("result").asOpt[JsValue].getOrElse(Json.obj())
              Right(extractText(result))
          }
      }
    }.recover { case e: Throwable => Left(Json.obj("error" -> s"a2a call failed: ${e.getMessage}")) }
  }

  // result is a oneof { task | message } (v1.0). Extract the agent's textual content. Works for v0.3 too because
  // A2APart.reads picks up the `text`/`data` members regardless of any legacy `kind` discriminator.
  private def extractText(result: JsValue): String = {
    result.select("message").asOpt[JsValue].flatMap(m => A2AMessage.format.reads(m).asOpt).map(_.textContent).filter(_.nonEmpty)
      .orElse {
        result.select("task").asOpt[JsValue].flatMap(t => A2ATask.format.reads(t).asOpt).map { task =>
          val statusText = task.status.message.map(_.textContent).getOrElse("")
          val artifactText = task.artifacts.flatMap(_.parts.map(_.asText)).filter(_.nonEmpty).mkString("\n")
          Seq(statusText, artifactText).filter(_.nonEmpty).mkString("\n")
        }.filter(_.nonEmpty)
      }
      .getOrElse(Json.stringify(result))
  }

  private def legacyMessageJson(message: A2AMessage): JsValue = Json.obj(
    "messageId" -> message.messageId,
    "role" -> (if (message.role == A2ARole.User) "user" else "agent"),
    "parts" -> JsArray(message.parts.map { p =>
      if (p.text.isDefined) Json.obj("kind" -> "text", "text" -> p.text.get)
      else if (p.data.isDefined) Json.obj("kind" -> "data", "data" -> p.data.get)
      else Json.obj("kind" -> "text", "text" -> p.asText)
    })
  )
}
