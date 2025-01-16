package com.cloud.apim.otoroshi.extensions.aigateway

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.{Framing, Sink, Source}
import akka.util.ByteString
import com.typesafe.config.ConfigFactory
import io.netty.buffer.Unpooled
import io.netty.util.CharsetUtil
import otoroshi.api.Otoroshi
import otoroshi.models.Entity
import otoroshi.utils.syntax.implicits._
import play.api.Configuration
import play.api.libs.json.{JsObject, JsValue}
import play.api.libs.ws.ahc.{AhcWSClient, AhcWSClientConfig}
import play.api.libs.ws.{WSClient, WSClientConfig, WSConfigParser, WSResponse}
import play.core.server.ServerConfig
import reactor.core.Disposable
import reactor.core.publisher.Sinks
import reactor.netty.http.client.HttpClient

import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64
import scala.concurrent.duration.{DurationInt, DurationLong, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.{Random, Try}

object Utils {

  private lazy implicit val actorSystem: ActorSystem   = ActorSystem(s"test-actor-system")
  private lazy implicit val materializer: Materializer = Materializer(actorSystem)

  val wsClientInstance: WSClient = {
    val parser: WSConfigParser         = new WSConfigParser(
      Configuration(
        ConfigFactory
          .parseString("""
                         |play {
                         |
                         |  # Configuration for Play WS
                         |  ws {
                         |
                         |    timeout {
                         |
                         |      # If non null, the connection timeout, this is how long to wait for a TCP connection to be made
                         |      connection = 2 minutes
                         |
                         |      # If non null, the idle timeout, this is how long to wait for any IO activity from the remote host
                         |      # while making a request
                         |      idle = 2 minutes
                         |
                         |      # If non null, the request timeout, this is the maximum amount of time to wait for the whole request
                         |      request = 2 minutes
                         |    }
                         |
                         |    # Whether redirects should be followed
                         |    followRedirects = true
                         |
                         |    # Whether the JDK proxy properties should be read
                         |    useProxyProperties = true
                         |
                         |    # If non null, will set the User-Agent header on requests to this
                         |    useragent = null
                         |
                         |    # Whether compression should be used on incoming and outgoing requests
                         |    compressionEnabled = false
                         |
                         |    # ssl configuration
                         |    ssl {
                         |
                         |      # Whether we should use the default JVM SSL configuration or not
                         |      default = false
                         |
                         |      # The ssl protocol to use
                         |      protocol = "TLSv1.2"
                         |
                         |      # Whether revocation lists should be checked, if null, defaults to platform default setting.
                         |      checkRevocation = null
                         |
                         |      # A sequence of URLs for obtaining revocation lists
                         |      revocationLists = []
                         |
                         |      # The enabled cipher suites. If empty, uses the platform default.
                         |      enabledCipherSuites = []
                         |
                         |      # The enabled protocols. If empty, uses the platform default.
                         |      enabledProtocols = ["TLSv1.2", "TLSv1.1", "TLSv1"]
                         |
                         |      # The disabled signature algorithms
                         |      disabledSignatureAlgorithms = ["MD2", "MD4", "MD5"]
                         |
                         |      # The disabled key algorithms
                         |      disabledKeyAlgorithms = ["RSA keySize < 2048", "DSA keySize < 2048", "EC keySize < 224"]
                         |
                         |      # The debug configuration
                         |      debug = []
                         |
                         |      # The hostname verifier class.
                         |      # If non null, should be the fully qualify classname of a class that implements HostnameVerifier, otherwise
                         |      # the default will be used
                         |      hostnameVerifierClass = null
                         |
                         |      # Configuration for the key manager
                         |      keyManager {
                         |        # The key manager algorithm. If empty, uses the platform default.
                         |        algorithm = null
                         |
                         |        # The key stores
                         |        stores = [
                         |        ]
                         |        # The key stores should look like this
                         |        prototype.stores {
                         |          # The store type. If null, defaults to the platform default store type, ie JKS.
                         |          type = null
                         |
                         |          # The path to the keystore file. Either this must be non null, or data must be non null.
                         |          path = null
                         |
                         |          # The data for the keystore. Either this must be non null, or path must be non null.
                         |          data = null
                         |
                         |          # The password for loading the keystore. If null, uses no password.
                         |          password = null
                         |        }
                         |      }
                         |
                         |      trustManager {
                         |        # The trust manager algorithm. If empty, uses the platform default.
                         |        algorithm = null
                         |
                         |        # The trust stores
                         |        stores = [
                         |        ]
                         |        # The key stores should look like this
                         |        prototype.stores {
                         |          # The store type. If null, defaults to the platform default store type, ie JKS.
                         |          type = null
                         |
                         |          # The path to the keystore file. Either this must be non null, or data must be non null.
                         |          path = null
                         |
                         |          # The data for the keystore. Either this must be non null, or path must be non null.
                         |          data = null
                         |        }
                         |
                         |      }
                         |
                         |      # The loose ssl options.  These allow configuring ssl to be more loose about what it accepts,
                         |      # at the cost of introducing potential security issues.
                         |      loose {
                         |
                         |        # Whether weak protocols should be allowed
                         |        allowWeakProtocols = false
                         |
                         |        # Whether weak ciphers should be allowed
                         |        allowWeakCiphers = false
                         |
                         |        # If non null, overrides the platform default for whether legacy hello messages should be allowed.
                         |        allowLegacyHelloMessages = null
                         |
                         |        # If non null, overrides the platform default for whether unsafe renegotiation should be allowed.
                         |        allowUnsafeRenegotiation = null
                         |
                         |        # Whether hostname verification should be disabled
                         |        disableHostnameVerification = false
                         |
                         |        # Whether any certificate should be accepted or not
                         |        acceptAnyCertificate = false
                         |
                         |        # Whether the SNI (Server Name Indication) TLS extension should be disabled
                         |        # This setting MAY be respected by client libraries.
                         |        #
                         |        # https://tools.ietf.org/html/rfc3546#sectiom-3.1
                         |        disableSNI = false
                         |      }
                         |
                         |      # Debug configuration
                         |      debug {
                         |
                         |        # Turn on all debugging
                         |        all = false
                         |
                         |        # Turn on ssl debugging
                         |        ssl = false
                         |
                         |        # Turn certpath debugging on
                         |        certpath = false
                         |
                         |        # Turn ocsp debugging on
                         |        ocsp = false
                         |
                         |        # Enable per-record tracing
                         |        record = false
                         |
                         |        # hex dump of record plaintext, requires record to be true
                         |        plaintext = false
                         |
                         |        # print raw SSL/TLS packets, requires record to be true
                         |        packet = false
                         |
                         |        # Print each handshake message
                         |        handshake = false
                         |
                         |        # Print hex dump of each handshake message, requires handshake to be true
                         |        data = false
                         |
                         |        # Enable verbose handshake message printing, requires handshake to be true
                         |        verbose = false
                         |
                         |        # Print key generation data
                         |        keygen = false
                         |
                         |        # Print session activity
                         |        session = false
                         |
                         |        # Print default SSL initialization
                         |        defaultctx = false
                         |
                         |        # Print SSLContext tracing
                         |        sslctx = false
                         |
                         |        # Print session cache tracing
                         |        sessioncache = false
                         |
                         |        # Print key manager tracing
                         |        keymanager = false
                         |
                         |        # Print trust manager tracing
                         |        trustmanager = false
                         |
                         |        # Turn pluggability debugging on
                         |        pluggability = false
                         |
                         |      }
                         |
                         |      sslParameters {
                         |        # translates to a setNeedClientAuth / setWantClientAuth calls
                         |        # "default" – leaves the (which for JDK8 means wantClientAuth and needClientAuth are set to false.)
                         |        # "none"    – `setNeedClientAuth(false)`
                         |        # "want"    – `setWantClientAuth(true)`
                         |        # "need"    – `setNeedClientAuth(true)`
                         |        clientAuth = "default"
                         |
                         |        # protocols (names)
                         |        protocols = []
                         |      }
                         |    }
                         |    ahc {
                         |      # Pools connections.  Replaces setAllowPoolingConnections and setAllowPoolingSslConnections.
                         |      keepAlive = true
                         |
                         |      # The maximum number of connections to make per host. -1 means no maximum.
                         |      maxConnectionsPerHost = -1
                         |
                         |      # The maximum total number of connections. -1 means no maximum.
                         |      maxConnectionsTotal = -1
                         |
                         |      # The maximum number of redirects.
                         |      maxNumberOfRedirects = 5
                         |
                         |      # The maximum number of times to retry a request if it fails.
                         |      maxRequestRetry = 5
                         |
                         |      # If non null, the maximum time that a connection should live for in the pool.
                         |      maxConnectionLifetime = null
                         |
                         |      # If non null, the time after which a connection that has been idle in the pool should be closed.
                         |      idleConnectionInPoolTimeout = 1 minute
                         |
                         |      # If non null, the frequency to cleanup timeout idle connections
                         |      connectionPoolCleanerPeriod = 1 second
                         |
                         |      # Whether the raw URL should be used.
                         |      disableUrlEncoding = false
                         |
                         |      # Whether to use LAX(no cookie name/value verification) or STRICT (verifies cookie name/value) cookie decoder
                         |      useLaxCookieEncoder = false
                         |
                         |      # Whether to use a cookie store
                         |      useCookieStore = false
                         |    }
                         |  }
                         |}
          """.stripMargin)
          .resolve()
      ).underlying,
      this.getClass.getClassLoader
    )
    val config: AhcWSClientConfig      = new AhcWSClientConfig(wsClientConfig = parser.parse()).copy(
      keepAlive = true
    )
    val wsClientConfig: WSClientConfig = config.wsClientConfig.copy(
      compressionEnabled = false,
      idleTimeout = (2 * 60 * 1000).millis,
      connectionTimeout = (2 * 60 * 1000).millis
    )
    AhcWSClient(
      config.copy(
        wsClientConfig = wsClientConfig
      )
    )(materializer)
  }

  def await(duration: FiniteDuration): Unit = {
    val p = Promise[Unit]
    actorSystem.scheduler.scheduleOnce(duration) {
      p.trySuccess(())
    }(actorSystem.dispatcher)
    Await.result(p.future, duration + 1.second)
  }

  def awaitF(duration: FiniteDuration)(implicit system: ActorSystem): Future[Unit] = {
    val p = Promise[Unit]
    system.scheduler.scheduleOnce(duration) {
      p.trySuccess(())
    }(actorSystem.dispatcher)
    p.future
  }

  def freePort: Int = {
    Try {
      val serverSocket = new ServerSocket(0)
      val port         = serverSocket.getLocalPort
      serverSocket.close()
      port
    }.toOption.getOrElse(Random.nextInt(1000) + 7000)
  }

  def startOtoroshi(port: Int = freePort): Otoroshi = {
    implicit val ec = actorSystem.dispatcher
    val cfg = s"""
                 |include "application.conf"
                 |
                 |datastax-java-driver.advanced.continuous-paging.page-size=1
                 |datastax-java-driver.advanced.continuous-paging.max-pages=1
                 |datastax-java-driver.advanced.continuous-paging.max-pages-per-second=1
                 |datastax-java-driver.advanced.continuous-paging.max-enqueued-pages=1
                 |datastax-java-driver.basic.request.page-size=1
                 |datastax-java-driver.advanced.connection.init-query-timeout=1
                 |datastax-java-driver.basic.request.timeout=1
                 |
                 |otoroshi.next.state-sync-interval=1
                 |http.port=${port}
                 |
                 |otoroshi.storage = "inmemory"
                 |otoroshi.env = "dev"
                 |otoroshi.domain = "oto.tools"
                 |otoroshi.rootScheme = "http"
                 |""".stripMargin
    val otoroshi = Otoroshi(
      ServerConfig(
        address = "0.0.0.0",
        port = Some(port),
        rootDir = Files.createTempDirectory("otoroshi-test-helper").toFile
      ),
      Configuration(ConfigFactory.parseString(cfg).resolve()).underlying
    )
    otoroshi.startAndStopOnShutdown()
    otoroshi.env.logger.debug("Starting !!!")
    Source
      .tick(1.second, 1.second, ())
      .mapAsync(1) { _ =>
        wsClientInstance
          .url(s"http://127.0.0.1:$port/health")
          .withRequestTimeout(1.second)
          .get()
          .map(r => r.status)
          .recover { case e =>
            0
          }
      }
      .filter(_ == 200)
      .take(1)
      .runForeach(_ => ())
      .awaitf(80.seconds)
    sys.addShutdownHook {
      otoroshi.stop()
    }
    otoroshi
  }

  def clientFor(port: Int): OtoroshiClient = {
    OtoroshiClient(port, wsClientInstance, actorSystem.dispatcher, materializer)
  }

}

case class OtoroshiClientStreamedResponse(resp: WSResponse, chunks: Seq[JsValue]) {
  def created: Boolean = resp.status == 201
  def success: Boolean = resp.status > 199 && resp.status < 300
  def status: Int = resp.status
  def headers: Map[String, String] = resp.headers.mapValues(_.last)
  def state: String = s"${status} - ${headers}"
  lazy val message: String = chunks.map { chunk =>
    val choices = chunk.select("choices").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
    choices.map(_.select("delta").select("content").asOpt[String].getOrElse("")).mkString("")
  }.mkString("")
}

case class OtoroshiClient(port: Int, client: WSClient, ec: ExecutionContext, mat: Materializer) {

  lazy val wsclient = HttpClient.create()

  def call(method: String, url: String, headers: Map[String, String], body: Option[JsValue]): Future[WSResponse] = {
    client.url(url).withMethod(method).withHttpHeaders(headers.toSeq:_*).applyOnWithOpt(body){
      case (builder, body) => builder.withBody(body)
    }.execute()
  }

  def ws(url: String)(f: Function[Sinks.Many[String], Function[String, Unit]]): (Sinks.Many[String], Disposable) = {
    val hotSource: Sinks.Many[String] = Sinks.many().unicast().onBackpressureBuffer[String]()
    val hotFlux = hotSource.asFlux()
    val thunk = f(hotSource)
    (hotSource, wsclient.websocket().uri(url).handle((in, out) => {
      in.receive().asString().subscribe((t: String) => thunk(t))
      out.send(hotFlux.map(str => Unpooled.wrappedBuffer(str.getBytes(CharsetUtil.ISO_8859_1))))
    }).subscribe())
  }

  def noop(in: String): Unit = ()

  def stream(method: String, url: String, headers: Map[String, String], body: Option[JsValue], timeout: FiniteDuration = 60.seconds, handler: Function[String, Unit] = noop): Future[OtoroshiClientStreamedResponse] = {
    client.url(url).withMethod(method).withHttpHeaders(headers.toSeq:_*).applyOnWithOpt(body){
      case (builder, body) => builder.withBody(body)
    }.withRequestTimeout(timeout).stream().flatMap { resp =>
      resp.bodyAsSource
        // .map(r => {
        //   println(s"r: ${r.utf8String}")
        //   r
        // })
        .via(Framing.delimiter("\n\n".byteString, 50000, true))
        .map(_.utf8String)
        .map(r => {
          handler(r)
          r
        })
        .filter(_.startsWith("data: "))
        .map(_.replaceFirst("data: ", ""))
        .filterNot(_.startsWith("[DONE]"))
        .map(_.parseJson)
        .takeWithin((timeout.toMillis - 10).millis)
        .runWith(Sink.seq)(mat)
        .map { chunks =>
          OtoroshiClientStreamedResponse(resp, chunks)
        }(ec)
    }(ec)
  }

  def forEntity(group: String, version: String, pluralName: String): OtoroshiEntityClient = {
    OtoroshiEntityClient(this, group, version, pluralName)
  }

  def forLlmEntity(pluralName: String): OtoroshiEntityClient = {
    OtoroshiEntityClient(this, "ai-gateway.extensions.cloud-apim.com", "v1", pluralName)
  }
}

case class OtoroshiEntityClient(client: OtoroshiClient, group: String, version: String, pluralName: String) {
  def raw_call(method: String, url: String, headers: Map[String, String], body: Option[JsValue]): Future[OtoroshiResponse] = {
    client.client.url(url).withMethod(method).withHttpHeaders(headers.toSeq:_*).applyOnWithOpt(body){
      case (builder, body) => builder.withBody(body)
    }.execute().map { resp =>
      OtoroshiResponse(client, group, pluralName, method, url, headers, body, resp)
    }(client.ec)
  }

  def call(method: String, id: Option[String] = None, body: Option[JsValue] = None): Future[OtoroshiResponse] = {
    val baseUrl = s"http://otoroshi-api.oto.tools:${client.port}/apis/${group}/${version}/${pluralName}"
    val url = id match {
      case None => baseUrl
      case Some(id) => s"${baseUrl}/${id}"
    }
    val pass: String = Base64.getEncoder.encodeToString(s"admin-api-apikey-id:admin-api-apikey-secret".getBytes(StandardCharsets.UTF_8))
    val headers = Map(
      "Authorization" -> s"Basic ${pass}"
    )
    raw_call(method, url, headers, body)
  }

  def createRaw(body: JsValue): Future[OtoroshiResponse] = {
    call("POST", None, Some(body))
  }

  def upsertRaw(id: String, body: JsValue): Future[OtoroshiResponse] = {
    call("POST", None, Some(body))
  }

  def deleteRaw(id: String): Future[OtoroshiResponse] = {
    call("DELETE", Some(id), None)
  }

  def createEntity[T <: Entity](body: T): Future[OtoroshiResponse] = {
    call("POST", None, Some(body.json))
  }

  def upsertEntity[T <: Entity](body: T): Future[OtoroshiResponse] = {
    call("POST", Some(body.theId), Some(body.json))
  }

  def deleteEntity[T <: Entity](body: T): Future[OtoroshiResponse] = {
    call("DELETE", Some(body.theId), None)
  }
}

case class OtoroshiResponse(client: OtoroshiClient, group: String, pluralName: String, method: String, url: String, inHeaders: Map[String, String], inBody: Option[JsValue], resp: WSResponse) {
  def created: Boolean = resp.status == 201
  def createdOrUpdated: Boolean = resp.status == 201 || resp.status == 200
  def success: Boolean = resp.status > 199 && resp.status < 300
  def status: Int = resp.status
  def headers: Map[String, String] = resp.headers.mapValues(_.last)
  def body: ByteString = resp.bodyAsBytes
  def bodyJson: JsValue = resp.json
  def state: String = s"${status} - ${resp.body}"
}


class LLmExtensionSuite extends munit.FunSuite {
  def await(duration: FiniteDuration): Unit = Utils.await(duration)
  def freePort: Int = Utils.freePort
  def startOtoroshiServer(port: Int = freePort): Otoroshi = Utils.startOtoroshi(port)
  def clientFor(port: Int): OtoroshiClient = Utils.clientFor(port)
}

class LlmExtensionOneOtoroshiServerPerSuite extends LLmExtensionSuite {

  val port: Int = freePort
  var otoroshi: Otoroshi = _
  var client: OtoroshiClient = _
  implicit var ec: ExecutionContext = _
  implicit var mat: Materializer = _

  override def beforeAll(): Unit = {
    otoroshi = startOtoroshiServer(port)
    client = clientFor(port)
    ec = otoroshi.executionContext
    mat = otoroshi.materializer
  }

  override def afterAll(): Unit = {
    otoroshi.stop()
  }
}

class LlmExtensionOneOtoroshiServerPerTest extends LLmExtensionSuite {

  val port: Int = freePort
  var otoroshi: Otoroshi = _
  var client: OtoroshiClient = _
  implicit var ec: ExecutionContext = _
  implicit var mat: Materializer = _

  override def beforeEach(context: BeforeEach): Unit = {
    otoroshi = startOtoroshiServer(port)
    client = clientFor(port)
    ec = otoroshi.executionContext
    mat = otoroshi.materializer
  }

  override def afterEach(context: AfterEach): Unit = {
    otoroshi.stop()
  }
}