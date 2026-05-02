package com.cloud.apim.otoroshi.extensions.aigateway.assistant.tools

import com.cloud.apim.otoroshi.extensions.aigateway.assistant.logic.{AdminClient, AdminCredentials}
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

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
        |Returns a JSON object keyed by request name, each value `{ status, headers, body }`. If a request fails before reaching the server (refused URL, network error), its entry is `{ error: "..." }`. Non-2xx responses are still returned (they are not failures), so the model can inspect them.
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

  // TODO: expression language for inter-request references (e.g. `${request_0.body.id}` resolved before sending the next request).

  override def call(arguments: JsValue, ctx: ToolCallContext)(implicit ec: ExecutionContext): Future[String] = {
    val requests = arguments.select("requests").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
    if (requests.isEmpty) return Future.successful("Error: missing or empty 'requests' array.")

    AdminCredentials.fetch(ctx.env, ctx.ext, ctx.user) match {
      case None =>
        Future.successful("Error: admin API credentials are not configured for the assistant. The 'execute' tool is unavailable until they are wired up. Use 'search' for discovery and answer with concrete payload examples instead.")
      case Some(creds) =>
        implicit val env = ctx.env
        runSequentially(creds, requests, 0, Vector.empty)
          .map(entries => JsObject(entries))
          .map(json => AssistantTool.truncate(Json.prettyPrint(json)))
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
      val req = requests(idx)
      val name = req.select("name").asOpt[String].map(_.trim).filter(_.nonEmpty).getOrElse(s"request_$idx")
      runSingle(creds, req)
        .recover { case t: Throwable => Json.obj("error" -> s"unexpected error: ${t.getMessage}") }
        .flatMap(entry => runSequentially(creds, requests, idx + 1, acc :+ (name -> entry)))
    }
  }

  private def runSingle(creds: AdminCredentials, req: JsObject)(implicit ec: ExecutionContext, env: otoroshi.env.Env): Future[JsValue] = {
    val method = req.select("method").asOpt[String].map(_.trim).filter(_.nonEmpty).getOrElse("GET")
    val url = req.select("url").asOpt[String].map(_.trim).getOrElse("")
    if (url.isEmpty) return Future.successful(Json.obj("error" -> "missing 'url'"))
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
    AdminClient.request(creds, method, url, opts).map {
      case Left(err) => Json.obj("error" -> err)
      case Right(resp) => Json.obj(
        "status" -> resp.status,
        "headers" -> JsObject(resp.headers.map { case (k, v) => k -> JsString(v) }),
        "body" -> resp.data,
      )
    }
  }

}
