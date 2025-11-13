package otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import otoroshi.cluster.Cluster
import otoroshi.env.Env
import otoroshi.gateway.Retry
import otoroshi.models.ApiKey
import otoroshi.next.extensions._
import otoroshi.next.plugins.api._
import otoroshi.utils.http.Implicits._
import otoroshi.utils.http.RequestImplicits._
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json._
import play.api.libs.ws.WSAuthScheme
import play.api.mvc._

import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.concurrent._
import scala.concurrent.duration._
import scala.util.Random

case class PowConfig(
  difficulty: Int = 8,
  tokenTtlSeconds: Int = 300,
  challengeTtlSeconds: Int = 120,
  cookieName: String = "oto_llm_pow",
  cookieDomain: Option[String] = None,
  cookiePath: String = "/",
  cookieSameSite: String = "Lax",
  bindIp: Boolean = true,
  bindUa: Boolean = true,
  secret: Option[String] = None,
) extends NgPluginConfig {
  override def json: JsValue = PowConfig.format.writes(this)
}

object PowConfig {
  val default = PowConfig(secret = "veryveryverysecretysecret".some)
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
  val configSchema = Some(Json.obj(
    "difficulty" -> Json.obj(
      "type" -> "number",
      "label" -> "Difficulty",
      "suffix" -> "bits",
    ),
    "token_ttl_seconds" -> Json.obj(
      "type" -> "number",
      "label" -> "Cookie TTL",
      "suffix" -> "seconds",
    ),
    "challenge_ttl_seconds" -> Json.obj(
      "type" -> "number",
      "label" -> "Challenge TTL",
      "suffix" -> "seconds",
    ),
    "cookie_name" -> Json.obj(
      "type" -> "string",
      "label" -> "Cookie name",
    ),
    "cookie_domain" -> Json.obj(
      "type" -> "string",
      "label" -> "Cookie domain",
    ),
    "cookie_path" -> Json.obj(
      "type" -> "string",
      "label" -> "Cookie path",
    ),
    "secret" -> Json.obj(
      "type" -> "string",
      "label" -> "Cookie signing secret",
      "props" -> Json.obj(
        "placeholder" -> "If none, using otoroshi secret"
      )
    ),
    "bind_ip" -> Json.obj(
      "type" -> "bool",
      "label" -> "Include client IP",
    ),
    "bind_ua" -> Json.obj(
      "type" -> "bool",
      "label" -> "Include user-agent",
    ),
    "cookie_same_site" -> Json.obj(
      "type" -> "select",
      "label" -> "Cookie Same Site",
      "props" -> Json.obj(
        "options" -> Json.arr(
          Json.obj("label" -> "LAX", "value" -> "lax"),
          Json.obj("label" -> "Strict", "value"   -> "strict"),
          Json.obj("label" -> "None", "value"   -> "none"),
        )
      )
    ),
  ))
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

object ProofOfWorkPlugin {

  private val counter = new AtomicInteger(0)

  private def prefixKey(implicit env: Env) = s"${env.storageRoot}:extensions:${AiExtension.id.cleanup}:pow_challenge_tokens:"

  private def otoroshiUrl(env: Env): String = {
    val config = env.clusterConfig
    val count = counter.incrementAndGet() % (if (config.leader.urls.nonEmpty) config.leader.urls.size else 1)
    config.leader.urls.zipWithIndex.find(t => t._2 == count).map(_._1).getOrElse(config.leader.urls.head)
  }

  // POST /api/extensions/cloud-apim/extensions/ai-extension/pow-challenges/:key
  def handlePowChallengeCreate(ctx: AdminExtensionRouterContext[AdminExtensionAdminApiRoute], req: RequestHeader, apikey: ApiKey, body: Option[Source[ByteString, _]])(implicit env: Env): Future[Result] = {
    implicit val ec = env.otoroshiExecutionContext
    implicit val mat = env.otoroshiMaterializer
    implicit val ev = env
    ctx.named("key") match {
      case None => Results.BadRequest(Json.obj("error" -> "no key found")).vfuture
      case Some(challengeId) => {
        body match {
          case None => Results.BadRequest(Json.obj("error" -> "no body found")).vfuture
          case Some(body) => body.runFold(ByteString.empty)(_ ++ _).flatMap { body =>
            val bodyJson = body.utf8String.parseJson
            val challenge = bodyJson.select("challenge").asObject
            val conf = PowConfig.format.reads(bodyJson.select("conf").asObject).get
            setChallenge(challengeId, challenge, conf)(env, ec).map { _ =>
              Results.NoContent
            }
          }
        }
      }
    }
  }

  // GET /api/extensions/cloud-apim/extensions/ai-extension/pow-challenges/:key
  def handlePowChallengeRead(ctx: AdminExtensionRouterContext[AdminExtensionAdminApiRoute], req: RequestHeader, apikey: ApiKey, body: Option[Source[ByteString, _]])(implicit env: Env): Future[Result] = {
    implicit val ec = env.otoroshiExecutionContext
    implicit val mat = env.otoroshiMaterializer
    implicit val ev = env
    ctx.named("key") match {
      case None => Results.BadRequest(Json.obj("error" -> "no key found")).vfuture
      case Some(challengeId) => {
        getChallenge(challengeId, PowConfig.default)(env, ec).map {
          case Some(challenge) => Results.Ok(challenge)
          case None => Results.NotFound(Json.obj("error" -> "challenge not found"))
        }
      }
    }
  }

  // DELETE /api/extensions/cloud-apim/extensions/ai-extension/pow-challenges/:key
  def handlePowChallengeDelete(ctx: AdminExtensionRouterContext[AdminExtensionAdminApiRoute], req: RequestHeader, apikey: ApiKey, body: Option[Source[ByteString, _]])(implicit env: Env): Future[Result] = {
    implicit val ec = env.otoroshiExecutionContext
    implicit val mat = env.otoroshiMaterializer
    implicit val ev = env
    ctx.named("key") match {
      case None => Results.BadRequest(Json.obj("error" -> "no key found")).vfuture
      case Some(challengeId) => {
        deleteChallenge(challengeId)(env, ec).map { _ =>
          Results.NoContent
        }
      }
    }
  }

  def callLeaderChallengeRead(challengeId: String)(implicit env: Env, ec: ExecutionContext): Future[Option[JsObject]] = {
    val config = env.clusterConfig
    implicit val sc = env.otoroshiScheduler
    Retry
      .retry(
        times = config.worker.retries,
        delay = config.retryDelay,
        factor = config.retryFactor,
        ctx = "leader-read-challenge-token"
      ) { tryCount =>
        if (Cluster.logger.isDebugEnabled)
          Cluster.logger.debug(s"Reading challenge $challengeId from Otoroshi leader cluster")
        env.MtlsWs
          .url(
            otoroshiUrl(env) + s"/api/extensions/cloud-apim/extensions/ai-extension/pow-challenges/${challengeId}",
            config.mtlsConfig
          )
          .withHttpHeaders(
            "Host"                                             -> config.leader.host,
          )
          .withAuth(config.leader.clientId, config.leader.clientSecret, WSAuthScheme.BASIC)
          .withRequestTimeout(Duration(config.worker.timeout, TimeUnit.MILLISECONDS))
          .withMaybeProxyServer(config.proxy)
          .get()
          .map { resp =>
            if (resp.status == 200) {
              Json.parse(resp.body).asOpt[JsObject]
            } else {
              None
            }
          }
      }
      .recover { case e =>
        if (Cluster.logger.isDebugEnabled)
          Cluster.logger.debug(
            s"[${env.clusterConfig.mode.name}] Error while reading challenge $challengeId from Otoroshi leader cluster"
          )
        None
      }
  }

  def callLeaderChallengeDelete(challengeId: String)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val config = env.clusterConfig
    implicit val sc = env.otoroshiScheduler
    Retry
      .retry(
        times = config.worker.retries,
        delay = config.retryDelay,
        factor = config.retryFactor,
        ctx = "leader-delete-challenge-token"
      ) { tryCount =>
        if (Cluster.logger.isDebugEnabled)
          Cluster.logger.debug(s"Deleting challenge $challengeId from Otoroshi leader cluster")
        env.MtlsWs
          .url(
            otoroshiUrl(env) + s"/api/extensions/cloud-apim/extensions/ai-extension/pow-challenges/${challengeId}",
            config.mtlsConfig
          )
          .withHttpHeaders(
            "Host"                                             -> config.leader.host,
          )
          .withAuth(config.leader.clientId, config.leader.clientSecret, WSAuthScheme.BASIC)
          .withRequestTimeout(Duration(config.worker.timeout, TimeUnit.MILLISECONDS))
          .withMaybeProxyServer(config.proxy)
          .delete()
          .map { resp =>
            ()
          }
      }
      .recover { case e =>
        if (Cluster.logger.isDebugEnabled)
          Cluster.logger.debug(
            s"[${env.clusterConfig.mode.name}] Error while deleting challenge $challengeId from Otoroshi leader cluster"
          )
        None
      }
  }

  def callLeaderChallengeWrite(challengeId: String, challenge: JsObject, conf: PowConfig)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val config = env.clusterConfig
    implicit val sc = env.otoroshiScheduler
    Retry
      .retry(
        times = config.worker.retries,
        delay = config.retryDelay,
        factor = config.retryFactor,
        ctx = "leader-push-challeng-token"
      ) { tryCount =>
        if (Cluster.logger.isDebugEnabled)
          Cluster.logger.debug(s"Pushing challenge $challengeId to Otoroshi leader cluster")
        env.MtlsWs
          .url(
            otoroshiUrl(env) + s"/api/extensions/cloud-apim/extensions/ai-extension/pow-challenges/${challengeId}",
            config.mtlsConfig
          )
          .withHttpHeaders(
            "Host"                                             -> config.leader.host,
          )
          .withAuth(config.leader.clientId, config.leader.clientSecret, WSAuthScheme.BASIC)
          .withRequestTimeout(Duration(config.worker.timeout, TimeUnit.MILLISECONDS))
          .withMaybeProxyServer(config.proxy)
          .post(Json.obj("challenge" -> challenge, "conf" -> conf.json))
          .map { resp =>
            ()
          }
      }
      .recover { case e =>
        if (Cluster.logger.isDebugEnabled)
          Cluster.logger.debug(
            s"[${env.clusterConfig.mode.name}] Error while pushing challenge $challengeId to Otoroshi leader cluster"
          )
        None
      }
  }

  def getChallenge(challengeId: String, conf: PowConfig)(implicit env: Env, ec: ExecutionContext): Future[Option[JsObject]] = {
    val key = s"$prefixKey$challengeId"
    env.datastores.rawDataStore.get(key) flatMap {
      case None if env.clusterConfig.mode.isWorker => ProofOfWorkPlugin.callLeaderChallengeRead(challengeId).map { r =>
        r.foreach { challenge =>
          env.datastores.rawDataStore.set(key, challenge.stringify.byteString, Some(conf.challengeTtlSeconds.seconds.toMillis))
        }
        r
      }
      case None => None.vfuture
      case Some(bytestring) => bytestring.utf8String.parseJson.asObject.some.vfuture
    }
  }

  def setChallenge(challengeId: String, challenge: JsObject, conf: PowConfig)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val key = s"$prefixKey$challengeId"
    env.datastores.rawDataStore.set(key, challenge.stringify.byteString, Some(conf.challengeTtlSeconds.seconds.toMillis)).flatMap {
      case _ if env.clusterConfig.mode.isWorker => ProofOfWorkPlugin.callLeaderChallengeWrite(challengeId, challenge, conf)
      case _ => ().vfuture
    }
  }

  def deleteChallenge(challengeId: String)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val key = s"$prefixKey$challengeId"
    env.datastores.rawDataStore.del(Seq(key)).flatMap {
      case _ if env.clusterConfig.mode.isWorker => ProofOfWorkPlugin.callLeaderChallengeDelete(challengeId)
      case _ => ().vfuture
    }
  }
}

class ProofOfWorkPlugin extends NgRequestTransformer {

  override def name: String = "Cloud APIM - LLM Bots blocker"
  override def description: Option[String] = "Can block request coming from LLM Bots using a JS proof of work".some
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
    val ip = ctx.request.theIpAddress.some
    val ua = ctx.request.headers.get("User-Agent")
    ctx.request.headers.get("Oto-LLm-Pow").filter(_ => ctx.request.method.toLowerCase == "post") match {
      case Some(header) => powVerify(header.parseJson, config, ctx.request).map(r => r.left)
      case None => {
        ctx.request.cookies.get(config.cookieName) match {
          case Some(c) if Pow.verifyToken(c.value, config.secret.getOrElse(env.otoroshiSecret), if (config.bindIp) ip else None, if (config.bindUa) ua else None) =>
            ctx.otoroshiRequest.rightf
          case _ => {
            val state = java.net.URLEncoder.encode(ctx.request.uri, "UTF-8")
            powChallenge(state, config).map { challenge =>
              powPage(state, challenge).left
            }
          }
        }
      }
    }
  }

  private def powPage(state: String, challenge: JsObject)(implicit env: Env): Result = {
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
  const cRes = ${challenge.stringify};
  const { challenge, difficulty, alg, expiresIn, state: st } = cRes;
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
    fetch(window.location.pathname, {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'Oto-LLm-Pow': payload },
      credentials: 'same-origin',
      body: ''
    }).then(() => {
      window.location.reload();
    });
  };

  worker.postMessage({ challenge, difficulty });
})();
</script>
"""
    Results.Ok(html).as("text/html")
  }

  private def powChallenge(state: String, conf: PowConfig)(implicit env: Env, ec: ExecutionContext): Future[JsObject] = {
    val challenge = Random.alphanumeric.take(16).mkString
    val payload   = Json.obj(
      "state"      -> state,
      "issuedAt"   -> Instant.now().getEpochSecond,
      "difficulty" -> conf.difficulty
    )
    ProofOfWorkPlugin.setChallenge(challenge, payload, conf).map { _ =>
      Json.obj(
        "challenge" -> challenge,
        "difficulty"-> conf.difficulty,
        "alg"       -> "sha256-leading-zero-bits",
        "expiresIn" -> conf.challengeTtlSeconds,
        "state"     -> state
      )
    }
  }

  private def powVerify(body: JsValue, conf: PowConfig, req: RequestHeader)(implicit env: Env, ec: ExecutionContext): Future[Result] = {
    val challenge = (body \ "challenge").as[String]
    val nonce     = (body \ "nonce").as[String]
    val hash      = (body \ "hash").as[String]
    ProofOfWorkPlugin.getChallenge(challenge, conf).flatMap {
      case None => Results.Forbidden("invalid or expired challenge").vfuture
      case Some(stored) =>
        val diff   = (stored \ "difficulty").as[Int]
        val expected = Pow.sha256Hex(s"$challenge:$nonce")
        val ok = (expected == hash) && (Pow.leadingZeroBits(hash) >= diff)
        if (!ok) {
          Results.Forbidden("invalid pow").vfuture
        } else {
          ProofOfWorkPlugin.deleteChallenge(challenge).flatMap { _ =>
            val now = Instant.now().getEpochSecond
            val exp = now + conf.tokenTtlSeconds
            val ip  = if (conf.bindIp) Some(req.theIpAddress) else None
            val ua  = if (conf.bindUa) req.headers.get("User-Agent") else None
            val tok = Pow.PowToken(exp = exp, ip = ip, ua = ua)
            val cookieValue = Pow.signToken(tok, conf.secret.getOrElse(env.otoroshiSecret))
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
            Results.NoContent.withCookies(cookie).vfuture
          }
        }
    }
  }
}
