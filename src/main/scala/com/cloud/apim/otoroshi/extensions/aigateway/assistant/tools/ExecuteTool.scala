package com.cloud.apim.otoroshi.extensions.aigateway.assistant.tools

import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}

class ExecuteTool extends AssistantTool {

  override def definition: ToolDefinition = ToolDefinition(
    name = "execute",
    description =
      """Execute JavaScript/TypeScript against a pre-authenticated Otoroshi admin client. Code runs as an async function body — use `return` to return a result.
        |
        |In scope:
        |- otoroshi: { call(operationId, { pathParams?, query?, body?, headers? }), get(path, opts), post(path, body, opts), put, patch, delete, request({ method, path, ... }) }. All methods return { status, ok, headers, data }.
        |- console: captured (.log/.warn/.error)
        |- signal: AbortSignal that fires on timeout
        |
        |Discover operationIds via the 'search' tool first.
        |
        |Examples:
        |  const routes = await otoroshi.call("proxy.otoroshi.io.Route.findAll");
        |  return routes.data;
        |
        |  const route = await otoroshi.call("proxy.otoroshi.io.Route.findById", { pathParams: { id: "abc" } });
        |""".stripMargin,
    parameters = Json.obj(
      "type" -> "object",
      "properties" -> Json.obj(
        "code" -> Json.obj(
          "type" -> "string",
          "description" -> "JavaScript/TypeScript source. Runs as an async function body. Use `return` to return a result.",
        ),
      ),
      "required" -> Json.arr("code"),
    ),
  )

  // TODO: wire a JS runtime (QuickJS/Wasm host bindings or GraalJS) so the model can compose multiple admin operations in a single tool call.
  override def call(arguments: JsValue, ctx: ToolCallContext)(implicit ec: ExecutionContext): Future[String] = {
    Future.successful("Error: the 'execute' tool is not implemented yet. For now, only 'search' (operation discovery) and 'doc' (conceptual docs) are available. Suggest the user the steps and the JSON payloads instead of executing code.")
  }
}
