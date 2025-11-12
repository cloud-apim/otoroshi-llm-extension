package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import akka.util.ByteString
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json._
import play.api.mvc._

import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

case class PowConfig(
                      difficulty: Int = 8,
                      tokenTtlSeconds: Int = 300,
                      challengeTtlSeconds: Int = 120,
                      cookieName: String = "oto_llm__pow",
                      cookieDomain: Option[String] = None,
                      cookiePath: String = "/",
                      cookieSameSite: String = "Lax",
                      bindIp: Boolean = true,
                      bindUa: Boolean = true,
                      secret: String
                    ) extends NgPluginConfig {
  override def json: JsValue = PowConfig.format.writes(this)
}

object PowConfig {
  val default = PowConfig(secret = "veryveryverysecretysecret")
  val format: OFormat[PowConfig] = Json.format[PowConfig]
  val configFlow = Seq(
    "difficulty",
    "token_ttl_seconds",
    "challenge_ttl_seconds",
    "cookie_name",
    "cookie_domain",
    "cookie_path",
    "cookie_same_site",
    "bind_ip",
    "bind_ua",
    "secret",
  )
  val configSchema = Some(Json.obj())
}

object Pow {
  private def sha256(bytes: Array[Byte]): Array[Byte] = {
    val md = MessageDigest.getInstance("SHA-256")
    md.digest(bytes)
  }

  def sha256Hex(str: String): String =
    sha256(str.getBytes("UTF-8")).map("%02x".format(_)).mkString

  def leadingZeroBits(hexHash: String): Int = {
    hexHash.view.flatMap { c =>
      val n = Integer.parseInt(c.toString, 16)
      val bin = f"$n%4s".replace(' ', '0')
      bin
    }.takeWhile(_ == '0').length
  }

  private def hmacSha256(data: String, secret: String): String = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
    mac.doFinal(data.getBytes("UTF-8")).map("%02x".format(_)).mkString
  }

  case class PowToken(exp: Long, ip: Option[String], ua: Option[String])
  object PowToken {
    implicit val f: OFormat[PowToken] = Json.format[PowToken]
  }

  def signToken(tok: PowToken, secret: String): String = {
    val payload = Base64.getUrlEncoder.withoutPadding().encodeToString(Json.stringify(Json.toJson(tok)).getBytes("UTF-8"))
    val sig = hmacSha256(payload, secret)
    s"$payload.$sig"
  }

  def verifyToken(raw: String, secret: String, ip: Option[String], ua: Option[String]): Boolean = {
    raw.split("\\.") match {
      case Array(payload, sig) =>
        val expected = hmacSha256(payload, secret)
        if (!MessageDigest.isEqual(expected.getBytes("UTF-8"), sig.getBytes("UTF-8"))) return false
        val json = new String(Base64.getUrlDecoder.decode(payload), "UTF-8")
        val tok  = Json.parse(json).as[PowToken]
        if (Instant.now().getEpochSecond > tok.exp) return false
        if (tok.ip.exists(_ != ip.getOrElse(""))) return false
        if (tok.ua.exists(_ != ua.getOrElse(""))) return false
        true
      case _ => false
    }
  }
}

class ProofOfWorkPlugin extends NgRequestTransformer {

  private def ChallengePrefix(implicit env: Env) = s"${env.storageRoot}:extensions:${AiExtension.id.cleanup}:pow_challenge_tokens:"

  override def name: String = "Cloud APIM - LLM Bots blocker"
  override def description: Option[String] = "Can block request coming from LLM Bots".some
  override def core: Boolean = false
  override def visibility: NgPluginVisibility = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Custom("Cloud APIM"), NgPluginCategory.Custom("AI - LLM"))

  override def steps: Seq[NgStep] = Seq(NgStep.TransformRequest)
  override def defaultConfigObject: Option[NgPluginConfig] = Some(PowConfig.default)
  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = PowConfig.configFlow
  override def configSchema: Option[JsObject] = PowConfig.configSchema

  override def transformsError: Boolean = false
  override def transformsResponse: Boolean = false
  override def transformsRequest: Boolean = true

  override def transformRequest(ctx: NgTransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val config = ctx.cachedConfig(internalName)(PowConfig.format).getOrElse(PowConfig.default)
    val path = ctx.request.path
    if (path == "/_pow") {
       powPage(ctx.request.getQueryString("state").get).leftf
    } else if (path == "/_pow/challenge") {
      powChallenge(ctx.request.getQueryString("state").get, config).map(r => r.left)
    } else if (path == "/_pow/verify") {
      ctx.otoroshiRequest.body.runFold(ByteString.empty)(_ ++ _).flatMap { raw =>
        powVerify(raw.utf8String.parseJson, config, ctx.request).map(r => r.left)
      }
    } else {
      val ip = ctx.request.theIpAddress.some
      val ua = ctx.request.headers.get("User-Agent")
      ctx.request.cookies.get(config.cookieName) match {
        case Some(c) if Pow.verifyToken(c.value, config.secret, if (config.bindIp) ip else None, if (config.bindUa) ua else None) =>
          ctx.otoroshiRequest.rightf
        case _ =>
          val state = java.net.URLEncoder.encode(ctx.request.uri, "UTF-8")
          val redirect = Results.Redirect(s"/_pow?state=$state")
          redirect.leftf
      }
    }
  }

  def powPage(state: String)(implicit env: Env): Result = {
    val html =
      s"""<!doctype html>
<meta charset="utf-8">
<title>Checking your browser…</title>
<style>
  body { font-family: system-ui, sans-serif; margin: 0; height: 100vh; display: grid; place-items: center; }
  .card { padding: 1.2rem 1.6rem; border: 1px solid #ddd; border-radius: 12px; max-width: 520px; text-align: center; }
  .small { color: #666; font-size: .9rem; margin-top: .5rem; }
  progress { width: 100%; height: 12px; }
</style>
<div class="card">
  <h1>Pending verification…</h1>
  <p>We are performing a lightweight anti LLM Bot check</p>
  <progress id="p" max="100" value="0"></progress>
  <div class="small">Should take less than 2 seconds.</div>
</div>
<script>
(async function(){
  const state = ${JsString(state).toString()};
  const cRes = await fetch('/_pow/challenge?state=' + encodeURIComponent(state), { credentials: 'same-origin' });
  if (!cRes.ok) return location.href = state;
  const { challenge, difficulty, alg, expiresIn, state: st } = await cRes.json();
  const workerCode = `
    self.onmessage = (e) => {
      const { challenge, difficulty } = e.data;
      function sha256Hex(str) {
        const enc = new TextEncoder();
        return crypto.subtle.digest('SHA-256', enc.encode(str)).then(buf => {
          const a = Array.from(new Uint8Array(buf));
          return a.map(b => b.toString(16).padStart(2,'0')).join('');
        });
      }
      function leadingZeroBits(hex) {
        let bits = 0;
        for (let i=0; i<hex.length; i++) {
          const n = parseInt(hex[i], 16);
          const bin = n.toString(2).padStart(4,'0');
          for (let j=0; j<4; j++) {
            if (bin[j] === '0') bits++; else return bits;
          }
        }
        return bits;
      }
      (async () => {
        let nonce = 0;
        while (true) {
          const h = await sha256Hex(challenge + ':' + nonce);
          if (leadingZeroBits(h) >= difficulty) {
            postMessage({ nonce, hash: h, attempts: nonce });
            break;
          }
          if (nonce % 1000 === 0) postMessage({ attempts: nonce });
          nonce++;
        }
      })();
    };
  `;
  const blob = new Blob([workerCode], { type: 'application/javascript' });
  const worker = new Worker(URL.createObjectURL(blob));

  const prog = document.getElementById('p');
  worker.onmessage = async (ev) => {
    if (ev.data.attempts != null && ev.data.nonce == null) {
      const est = Math.min(99, Math.floor(Math.log2(ev.data.attempts + 2) / difficulty * 100));
      prog.value = est;
      return;
    }
    const payload = JSON.stringify({ challenge, nonce: String(ev.data.nonce), hash: ev.data.hash, state });
    const vRes = await fetch('/_pow/verify', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      credentials: 'same-origin',
      body: payload
    });
    location.href = state;
  };

  worker.postMessage({ challenge, difficulty });
})();
</script>
"""
    Results.Ok(html).as("text/html")
  }

  def powChallenge(state: String, conf: PowConfig)(implicit env: Env, ec: ExecutionContext): Future[Result] = {
    val challenge = Random.alphanumeric.take(16).mkString
    val key       = s"$ChallengePrefix$challenge"
    val payload   = Json.obj(
      "state"      -> state,
      "issuedAt"   -> Instant.now().getEpochSecond,
      "difficulty" -> conf.difficulty
    )
    env.datastores.rawDataStore.set(key, payload.stringify.byteString, Some(conf.challengeTtlSeconds.seconds.toMillis))
      .map { _ =>
        Results.Ok(Json.obj(
          "challenge" -> challenge,
          "difficulty"-> conf.difficulty,
          "alg"       -> "sha256-leading-zero-bits",
          "expiresIn" -> conf.challengeTtlSeconds,
          "state"     -> state
        ))
      }
  }

  def powVerify(body: JsValue, conf: PowConfig, req: RequestHeader)(implicit env: Env, ec: ExecutionContext): Future[Result] = {
    val challenge = (body \ "challenge").as[String]
    val nonce     = (body \ "nonce").as[String]
    val hash      = (body \ "hash").as[String]
    val key       = s"$ChallengePrefix$challenge"

    env.datastores.rawDataStore.get(key).flatMap {
      case None => FastFuture.successful(Results.Forbidden("invalid or expired challenge"))
      case Some(js) =>
        val stored = Json.parse(js.utf8String)
        val diff   = (stored \ "difficulty").as[Int]
        val expected = Pow.sha256Hex(s"$challenge:$nonce")
        val ok = (expected == hash) && (Pow.leadingZeroBits(hash) >= diff)
        if (!ok) {
          FastFuture.successful(Results.Forbidden("invalid pow"))
        } else {
          env.datastores.rawDataStore.del(Seq(key)).flatMap { _ =>
            val now = Instant.now().getEpochSecond
            val exp = now + conf.tokenTtlSeconds
            val ip  = if (conf.bindIp) Some(req.theIpAddress) else None
            val ua  = if (conf.bindUa) req.headers.get("User-Agent") else None
            val tok = Pow.PowToken(exp = exp, ip = ip, ua = ua)
            val cookieValue = Pow.signToken(tok, conf.secret)

            val sameSite: Option[Cookie.SameSite] = conf.cookieSameSite.toLowerCase match {
              case "lax"   => Some(Cookie.SameSite.Lax)
              case "strict"=> Some(Cookie.SameSite.Strict)
              case "none"  => Some(Cookie.SameSite.None)
              case _       => Some(Cookie.SameSite.Lax)
            }
            val cookie = Cookie(
              name = conf.cookieName,
              value = cookieValue,
              maxAge = Some(conf.tokenTtlSeconds),
              path = conf.cookiePath,
              domain = conf.cookieDomain,
              secure = true,
              httpOnly = true,
              sameSite = sameSite
            )
            FastFuture.successful(Results.NoContent.withCookies(cookie))
          }
        }
    }
  }
}
