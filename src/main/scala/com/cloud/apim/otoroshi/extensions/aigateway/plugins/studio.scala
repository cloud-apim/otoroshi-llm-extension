package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import otoroshi.env.Env
import otoroshi.models.{EntityLocation, PrivateAppsUser}
import otoroshi.next.plugins.api.*
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.{JsObject, Json}

import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
 * The AI Studio chat calls the route of a workspace with one of its api keys. To report that usage
 * per studio user, the call carries a short-lived header signed with the otoroshi secret, turned
 * back into the request user by [[AiStudioConsumer]]. Nobody without the secret can forge it.
 */
object AiStudioUserHeader {

  val name = "Otoroshi-Ai-Studio-User"

  private val ttlMillis = 60000L

  private def mac(env: Env, payload: String): String = {
    val m = Mac.getInstance("HmacSHA256")
    m.init(new SecretKeySpec(s"${env.otoroshiSecret}:ai-studio-user".getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
    Base64.getUrlEncoder.withoutPadding().encodeToString(m.doFinal(payload.getBytes(StandardCharsets.UTF_8)))
  }

  def sign(email: String, userName: String, env: Env): String = {
    val json    = Json.obj("email" -> email, "name" -> userName, "exp" -> (System.currentTimeMillis() + ttlMillis)).stringify
    val payload = Base64.getUrlEncoder.withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8))
    s"$payload.${mac(env, payload)}"
  }

  def verify(value: String, env: Env): Option[(String, String)] = value.split('.') match {
    case Array(payload, signature) if java.security.MessageDigest.isEqual(mac(env, payload).getBytes, signature.getBytes) =>
      Try(Json.parse(new String(Base64.getUrlDecoder.decode(payload), StandardCharsets.UTF_8))).toOption
        .filter(json => json.select("exp").asOpt[Long].exists(_ > System.currentTimeMillis()))
        .flatMap(json => json.select("email").asOptString.map(email => (email, json.select("name").asOptString.getOrElse(email))))
    case _ => None
  }
}

class AiStudioConsumer extends NgAccessValidator {

  override def steps: Seq[NgStep]                          = Seq(NgStep.ValidateAccess)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory]          = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))
  override def name: String                                = "Cloud APIM - AI Studio consumer"
  override def description: Option[String]                 = "Identifies the AI Studio user behind a call made from the studio chat, so usage and budgets can be tracked per user".some
  override def defaultConfigObject: Option[NgPluginConfig] = None
  override def noJsForm: Boolean                           = true
  override def configFlow: Seq[String]                     = Seq.empty
  override def configSchema: Option[JsObject]              = Some(Json.obj())

  override def access(ctx: NgAccessContext)(using env: Env, ec: ExecutionContext): Future[NgAccess] = {
    ctx.request.headers.get(AiStudioUserHeader.name).flatMap(v => AiStudioUserHeader.verify(v, env)).foreach { case (email, userName) =>
      ctx.attrs.put(otoroshi.plugins.Keys.UserKey -> PrivateAppsUser(
        randomId = s"ai-studio-${email.sha256.take(16)}",
        name = userName,
        email = email,
        profile = Json.obj("email" -> email, "name" -> userName),
        realm = "ai-studio",
        authConfigId = "ai-studio",
        otoroshiData = None,
        tags = Seq("ai-studio"),
        metadata = Map("ai_studio_user" -> "true"),
        location = EntityLocation.default,
      ))
    }
    NgAccess.NgAllowed.vfuture
  }
}
