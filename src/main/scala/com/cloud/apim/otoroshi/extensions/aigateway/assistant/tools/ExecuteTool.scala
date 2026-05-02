package com.cloud.apim.otoroshi.extensions.aigateway.assistant.tools

import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.assistant.logic.{AdminClient, AdminCredentials, ExpressionLanguage}
import otoroshi.env.Env
import otoroshi.models.ApiKey
import otoroshi.next.proxy.{BackOfficeRequest, ProxyEngine, RelayRoutingRequest}
import otoroshi.script.RequestHandler
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.libs.typedmap.TypedMap
import play.api.mvc.request.{Cell, RemoteConnection, RequestAttrKey, RequestTarget}
import play.api.mvc.{Cookies, EssentialAction, Headers, Request, Results}

import scala.concurrent.{ExecutionContext, Future}

class ExecuteTool extends AssistantTool {

  override def definition: ToolDefinition = ToolDefinition(
    name = "execute",
    description =
      """Run a sequence of HTTP requests against the Otoroshi Admin API. Requests run **in order**, one after the other, against the admin API of this Otoroshi instance.
        |
        |Each entry in `requests` is an object with:
        |- `method` (required): HTTP method (`GET`, `POST`, `PUT`, `PATCH`, `DELETE`).
        |- `url` (required): **relative path** under the Otoroshi admin API (e.g. `/apis/proxy.otoroshi.io/v1/routes`). Absolute URLs and cross-origin paths are rejected.
        |- `headers` (optional): object of additional headers. `Authorization`, `Cookie`, `Host`, `Proxy-Authorization` are stripped.
        |- `body` (optional): JSON body for write methods.
        |- `name` (optional): label used as the key in the response. Defaults to `request_{idx}` (0-indexed).
        |
        |Returns a JSON object keyed by request name, each value `{ status, headers, body }`. If a request fails before reaching the server (refused URL, network error, unresolved reference), its entry is `{ error: "..." }`. Non-2xx responses are still returned (they are not failures), so the model can inspect them.
        |
        |### Inter-request references (expression language)
        |Inside any string field of a later request (`url`, header values, any string in `body`), use `${<name>.<path>}` to inject a value extracted from a previous request's result. The referenced request must have already executed (the engine runs in order).
        |- `<name>` is the previous request's `name` (or `request_<idx>`).
        |- `<path>` walks into the result object, which has shape `{ status, headers, body }`. Use dotted keys and `[idx]` for array indices.
        |- Examples: `${list.body[0].id}`, `${create.status}`, `${list.headers.x-ratelimit-remaining}`.
        |- A whole-string placeholder preserves the value's type (a number stays a number); a partial placeholder (e.g. `"/routes/${create.body.id}"`) splices a stringified form.
        |- An unresolved reference fails the request with `{ error: "${...}: ..." }` and execution continues with the next entry.
        |
        |Use the `search` tool first to discover endpoints (operationIds + paths + body schemas).
        |
        |Example:
        |```
        |{
        |  "requests": [
        |    { "name": "list", "method": "GET", "url": "/apis/proxy.otoroshi.io/v1/routes" },
        |    { "name": "create", "method": "POST", "url": "/apis/proxy.otoroshi.io/v1/routes",
        |      "body": { "name": "my-route", "enabled": true, "frontend": { "domains": ["api.example.com"] } } }
        |  ]
        |}
        |```
        |""".stripMargin,
    parameters = Json.obj(
      "type" -> "object",
      "properties" -> Json.obj(
        "requests" -> Json.obj(
          "type" -> "array",
          "description" -> "Ordered list of admin API requests to run sequentially.",
          "items" -> Json.obj(
            "type" -> "object",
            "properties" -> Json.obj(
              "name" -> Json.obj("type" -> "string", "description" -> "Optional label used as the key in the response object."),
              "method" -> Json.obj("type" -> "string", "description" -> "HTTP method (GET, POST, PUT, PATCH, DELETE)."),
              "url" -> Json.obj("type" -> "string", "description" -> "Relative path under the Otoroshi admin API origin."),
              "headers" -> Json.obj("type" -> "object", "description" -> "Optional additional request headers (string→string)."),
              "body" -> Json.obj("description" -> "Optional JSON body."),
            ),
            "required" -> Json.arr("method", "url"),
          ),
        ),
      ),
      "required" -> Json.arr("requests"),
    ),
  )

  override def call(arguments: JsValue, ctx: ToolCallContext)(implicit ec: ExecutionContext): Future[String] = {
    val requests = arguments.select("requests").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
    if (requests.isEmpty) return Future.successful("Error: missing or empty 'requests' array.")

    println(s"call tool 'execute': ${JsArray(requests).prettify}")

    val resultF = AdminCredentials.fetch(ctx.env, ctx.ext, ctx.user) match {
      case None =>
        Future.successful("Error: admin API credentials are not configured for the assistant. The 'execute' tool is unavailable until they are wired up. Use 'search' for discovery and answer with concrete payload examples instead.")
      case Some(creds) =>
        implicit val env = ctx.env
        runSequentially(creds, requests, 0, Vector.empty)
          .map(entries => JsObject(entries))
          .map(json => AssistantTool.truncate(Json.prettyPrint(json)))
    }
    resultF.map { response =>
      println(s"call tool 'execute' response: ${response}\n\n----------------------------------\n\n")
      response
    }
  }

  private def runSequentially(
    creds: AdminCredentials,
    requests: Seq[JsObject],
    idx: Int,
    acc: Vector[(String, JsValue)],
  )(implicit ec: ExecutionContext, env: otoroshi.env.Env): Future[Vector[(String, JsValue)]] = {
    if (idx >= requests.size) Future.successful(acc)
    else {
      val rawReq = requests(idx)
      val name = rawReq.select("name").asOpt[String].map(_.trim).filter(_.nonEmpty).getOrElse(s"request_$idx")
      runSingle(creds, rawReq, acc.toMap)
        .recover { case t: Throwable => Json.obj("error" -> s"unexpected error: ${t.getMessage}") }
        .flatMap(entry => runSequentially(creds, requests, idx + 1, acc :+ (name -> entry)))
    }
  }

  private def runSingle(creds: AdminCredentials, rawReq: JsObject, refCtx: Map[String, JsValue])(implicit ec: ExecutionContext, env: otoroshi.env.Env): Future[JsValue] = {
    ExpressionLanguage.expandValue(rawReq, refCtx) match {
      case Left(err) => Future.successful(Json.obj("error" -> s"unresolved expression: $err"))
      case Right(expanded) =>
        val req = expanded.asOpt[JsObject].getOrElse(Json.obj())
        val method = req.select("method").asOpt[String].map(_.trim).filter(_.nonEmpty).getOrElse("GET")
        val url = req.select("url").asOpt[String].map(_.trim).getOrElse("")
        if (url.isEmpty) Future.successful(Json.obj("error" -> "missing 'url'"))
        else {
          val headers = req.select("headers").asOpt[JsObject].map(_.value.toMap.flatMap {
            case (k, JsString(v)) => Some(k -> v)
            case (k, v) if v != JsNull => Some(k -> v.toString)
            case _ => None
          }).getOrElse(Map.empty)
          val body = req.value.get("body").filter(_ != JsNull)
          val opts = AdminClient.CallOptions(
            pathParams = Map.empty,
            query = Map.empty,
            body = body,
            headers = headers,
          )
          println(s"LLM tools exec: ${method} ${url} ${opts.json.prettify}")
          val host               = env.adminApiHost
          val apikey = creds.apikey
          val request = new AssistantRequest(host, method, url, opts, apikey, env)
          val engine = env.scriptManager.getAnyScript[RequestHandler](s"cp:${classOf[ProxyEngine].getName}").right.get
          engine.handle(request, _ => Results.InternalServerError("bad default routing").vfuture).flatMap { resp =>
            resp.body.dataStream.runFold(ByteString.empty)(_ ++ _)(env.otoroshiMaterializer).map { bodyRaw =>
              val ctype = resp.header.headers.getIgnoreCase("content-type").getOrElse("text/plain")
              val body: JsValue = if (ctype.startsWith("application/json")) bodyRaw.utf8String.parseJson else bodyRaw.utf8String.json
              Json.obj(
                "status" -> resp.header.status,
                "headers" -> JsObject(resp.header.headers.map { case (k, v) => k -> JsString(v) }),
                "body" -> body,
              )
            }
          }
        }
    }
  }

}

class AssistantRequest(_host: String, m: String, _url: String, opts: AdminClient.CallOptions, apikey: ApiKey, env: Env) extends Request[Source[ByteString, _]] {


  val bodyBs  = opts.body.map(_.stringify.byteString)
  val bodyBsSize  = bodyBs.map(_.length).getOrElse(0L)
  val _cookies = Cookies(Seq.empty)

  lazy val attrs           = TypedMap.apply(
    RequestAttrKey.Id      -> 1L,
    RequestAttrKey.Cookies -> Cell(_cookies)
  )


  override def hasBody: Boolean = opts.body.isDefined

  override def body: Source[ByteString, _] = opts.body.map(json => Source.single(json.stringify.byteString)).getOrElse(Source.empty[ByteString])

  override def connection: RemoteConnection = RemoteConnection("127.0.0.1", true, None)

  override def method: String = m.toUpperCase()

  override def target: RequestTarget = RequestTarget(_url, Uri(_url).path.toString(), opts.query.mapValues(v => Seq(v)))

  override def version: String = "HTTP/1.1"

  override def headers: Headers = if (hasBody) {
    Headers.apply(
      Seq(
        "Host" -> _host,
        "Accept" -> "application/json",
        "Content-Type" -> "application/json",
        "Content-Length" -> bodyBsSize.toString,
        "Authorization" -> s"Bearer ${apikey.toBearer()}"
      ): _*
    )
  } else {
    Headers.apply(
      Seq(
        "Host" -> _host,
        "Accept" -> "application/json",
        "Authorization" -> s"Bearer ${apikey.toBearer()}"
      ): _*
    )
  }
}
