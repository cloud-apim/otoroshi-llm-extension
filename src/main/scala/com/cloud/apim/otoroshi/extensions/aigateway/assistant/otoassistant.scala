package com.cloud.apim.otoroshi.extensions.aigateway.assistant

import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.assistant.tools.{AssistantTool, ToolCallContext, ToolRegistry}
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatClient, ChatPrompt, ChatResponse, InputChatMessage}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import otoroshi.env.Env
import otoroshi.models.{ApiKey, BackOfficeUser}
import otoroshi.next.extensions._
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json._
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}

object OtoroshiAssistant {
  private val basePrompt: String =
    """You are the Otoroshi Assistant, an expert AI helper embedded in the Otoroshi admin UI.
      |
      |## About Otoroshi
      |Otoroshi is an open-source, lightweight API gateway and HTTP reverse proxy developed by the MAIF team. It is designed for managing, securing, and observing HTTP traffic at the edge of microservices architectures. Core capabilities include:
      |- Routes and services: dynamic HTTP routing with rich matching (host, path, headers, method) and traffic shaping.
      |- Authentication and authorization: API keys, JWT verifiers, OAuth2/OIDC providers, LDAP, basic auth, biscuit tokens, mTLS.
      |- Security: rate limiting, quotas, IP allow/deny lists, WAF-style rules, request/response transformations, secrets management.
      |- Observability: events, audit logs, analytics, OpenTelemetry, alerts, data exporters (Elastic, Kafka, Webhooks, etc.).
      |- Extensibility: WASM plugins, Otoroshi plugins (NgPlugin / NgRequestTransformer / NgPreRouting / NgAccessValidator / NgBackend), workflows, scripts.
      |- Networking: TCP/UDP tunnels, TLS termination, certificates with auto-renewal (ACME/Let's Encrypt), HTTP/2, HTTP/3, websockets.
      |- Multi-tenancy via organizations and teams.
      |- This installation runs the Cloud APIM AI/LLM extension which adds an LLM gateway, providers, prompts, guardrails, semantic cache, embeddings, MCP connectors, AI agents, and more.
      |
      |## Your role
      |You help backoffice users master Otoroshi. Concretely you:
      |- Answer questions about Otoroshi concepts, configuration, and best practices.
      |- Guide users through common tasks: creating routes, services, API keys, certificates, JWT verifiers, auth modules, data exporters, plugins pipelines, LLM providers, etc.
      |- Explain plugins and how to chain them in a route's plugins pipeline (pre-routing, access-validation, transformer, backend-call).
      |- Help debug configurations: malformed routes, failing auth, plugin errors, certificate issues, rate-limit/quota questions.
      |- Search and reference Otoroshi documentation when relevant. The official manual is at https://www.otoroshi.io/docs/. When the user's question maps to a specific section, point them to the most relevant page.
      |- Suggest concrete JSON or YAML payloads that can be POSTed to the admin API (/api/...) when appropriate, and explain which endpoint to hit.
      |- Help with the AI/LLM extension features: providers, prompt templates, prompt contexts, guardrails, semantic cache, budgets, audio/image/video models, MCP connectors, tool functions, agents, workflows.
      |
      |## Style and tone
      |- Be concise, precise, and pragmatic. Prefer short answers with concrete examples over long theory dumps.
      |- Use Markdown: short paragraphs, bulleted lists, fenced code blocks with the right language tag (`json`, `yaml`, `bash`, `scala`, ...).
      |- When showing JSON, show only the fields that matter for the question — do not dump full entity schemas unless asked.
      |- When several approaches exist, recommend one and briefly mention the trade-off.
      |- Use the same language as the user (French if they write in French, English otherwise).
      |- Never invent Otoroshi APIs, fields, or plugin names. If you are unsure, say so and point the user to the manual or to the relevant admin UI page.
      |
      |## Safety and scope
      |- You are a guide, not an autonomous operator. Do not claim to have executed actions on the platform unless a tool call confirms it.
      |- Never ask for or repeat secrets (passwords, full API key clientSecrets, private keys, certificates). If the user pastes one, advise them to rotate it.
      |- If a request is unrelated to Otoroshi, APIs, web infrastructure, or development, politely steer the conversation back to the platform.
      |- If a question requires data you do not have access to (live cluster state, current entities), say so and suggest where in the UI or via which admin API endpoint the user can find it.
      |""".stripMargin

  def systemPrompt(user: Option[BackOfficeUser], currentUrl: Option[String], now: java.time.ZonedDateTime): String = {
    val userName  = user.map(_.name).filter(_.nonEmpty).getOrElse("unknown")
    val userEmail = user.map(_.email).filter(_.nonEmpty).getOrElse("unknown")
    val time      = now.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    val url       = currentUrl.filter(_.nonEmpty).getOrElse("unknown")
    val contextBlock =
      s"""
         |## Current session context
         |Use this only when relevant to the user's question. Do not echo it back unless asked.
         |- Current date/time: $time
         |- Backoffice user name: $userName
         |- Backoffice user email: $userEmail
         |- Page the user is currently viewing (URL): $url
         |
         |When the user asks something that depends on what they are currently looking at ("this route", "this provider", "here", ...), interpret it relative to the page URL above. The path segment after `/bo/dashboard/` typically identifies the section (e.g. `/bo/dashboard/routes/<id>` is the route editor for `<id>`, `/bo/dashboard/apikeys/<id>` for an API key, etc.).
         |""".stripMargin
    basePrompt + contextBlock
  }
}

class OtoroshiAssistant(env: Env, ext: AiExtension) {

  private val maxToolCallIterations: Int = 20

  def assistantProvider: Option[AiProvider] = ext.states.allProviders().find(_.isOtoroshiAssistant)

  def isEnabled: Boolean = assistantProvider.isDefined

  def handleAssistantCompletion(ctx: AdminExtensionRouterContext[AdminExtensionBackofficeAuthRoute], req: RequestHeader, user: Option[BackOfficeUser], body: Option[Source[ByteString, _]]): Future[Result] = {
    implicit val ec = env.otoroshiExecutionContext
    implicit val mat = env.otoroshiMaterializer
    implicit val ev = env
    if (isEnabled) {
      body match {
        case None => Results.BadRequest(Json.obj("error" -> "No body")).vfuture
        case Some(body) => body.runFold(ByteString.empty)(_ ++ _).map(_.utf8String).flatMap { bodyRaw =>
          val provider = assistantProvider.get
          provider.getChatClient() match {
            case None => Results.InternalServerError(Json.obj("error" -> "Unable to create llm client")).vfuture
            case Some(client) => {
              val bodyJson = bodyRaw.parseJson.asObject
              val incoming = bodyJson.select("messages").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map { obj => InputChatMessage.fromJson(obj) }
              val currentUrl = req.headers.get("X-Otoroshi-Assistant-Current-Url")
              val now = java.time.ZonedDateTime.now()
              val systemMessage = InputChatMessage.fromJson(Json.obj(
                "role" -> "system",
                "content" -> OtoroshiAssistant.systemPrompt(user, currentUrl, now),
              ))
              val withoutClientSystem = incoming.filterNot(_.role == "system")
              val initialMessages = systemMessage +: withoutClientSystem

              val registry = ToolRegistry.default
              val toolCtx = ToolCallContext(env, ext, user)
              val baseBody = (bodyJson - "messages") ++ Json.obj(
                "tools" -> registry.openaiJson,
                "tool_choice" -> JsString("auto"),
              )

              chatLoop(client, initialMessages, baseBody, registry, toolCtx, 0).map {
                case Left(err) => Results.InternalServerError(Json.obj("error" -> err))
                case Right(resp) => Results.Ok(resp.openaiJson("model", env))
              }
            }
          }
        }
      }
    } else {
      Results.InternalServerError(Json.obj("error" -> "Access is disabled")).vfuture
    }
  }

  private def chatLoop(
    client: ChatClient,
    messages: Seq[InputChatMessage],
    baseBody: JsObject,
    registry: ToolRegistry,
    toolCtx: ToolCallContext,
    iteration: Int,
  )(implicit ec: ExecutionContext): Future[Either[JsValue, ChatResponse]] = {
    implicit val ev: Env = env
    if (iteration >= maxToolCallIterations) {
      Left[JsValue, ChatResponse](JsString(s"Max tool-call iterations reached ($maxToolCallIterations).")).vfuture
    } else {
      client.call(ChatPrompt(messages, None), TypedMap.empty, baseBody).flatMap {
        case Left(err) => Left[JsValue, ChatResponse](err).vfuture
        case Right(resp) =>
          val gen = resp.headGeneration
          val toolCalls = gen.message.tool_calls.getOrElse(Seq.empty)
          if (!gen.message.has_tool_calls || toolCalls.isEmpty) Right[JsValue, ChatResponse](resp).vfuture
          else {
            val assistantMsg = InputChatMessage.fromJson(Json.obj(
              "role" -> "assistant",
              "content" -> JsString(gen.message.content),
              "tool_calls" -> JsArray(toolCalls),
            ))
            val toolResultsF = Future.sequence(toolCalls.map(tc => runToolCall(tc, registry, toolCtx)))
            toolResultsF.flatMap { results =>
              val newMessages = (messages :+ assistantMsg) ++ results
              chatLoop(client, newMessages, baseBody, registry, toolCtx, iteration + 1)
            }
          }
      }
    }
  }

  private def runToolCall(tc: JsObject, registry: ToolRegistry, toolCtx: ToolCallContext)(implicit ec: ExecutionContext): Future[InputChatMessage] = {
    val callId = tc.select("id").asOpt[String].getOrElse(java.util.UUID.randomUUID().toString)
    val fn = tc.select("function").asOpt[JsObject].getOrElse(Json.obj())
    val name = fn.select("name").asOpt[String].getOrElse("")
    val argsRaw = fn.select("arguments").asValue match {
      case JsString(s) => s
      case obj: JsObject => obj.stringify
      case other => other.toString
    }
    val argsJson: JsValue = scala.util.Try(Json.parse(argsRaw)).getOrElse(Json.obj())
    val resultF: Future[String] = registry.find(name) match {
      case None => Future.successful(s"Error: unknown tool '$name'.")
      case Some(tool) => tool.call(argsJson, toolCtx).recover { case t: Throwable => s"Error: ${t.getMessage}" }
    }
    resultF.map { content =>
      InputChatMessage.fromJson(Json.obj(
        "role" -> "tool",
        "tool_call_id" -> callId,
        "content" -> AssistantTool.truncate(content),
      ))
    }
  }
}
