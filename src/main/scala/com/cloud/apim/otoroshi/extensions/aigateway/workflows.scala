package com.cloud.apim.otoroshi.extensions.aigateway

import akka.stream.scaladsl.FileIO
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.agents._
import com.cloud.apim.otoroshi.extensions.aigateway.decorators.{GuardrailResult, Guardrails}
import otoroshi.env.Env
import otoroshi.next.workflow._
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json._

import java.io.File
import java.nio.file.Files
import scala.concurrent.{ExecutionContext, Future}

object WorkflowFunctionsInitializer {
  def initDefaults(): Unit = {
    WorkflowFunction.registerFunction("extensions.com.cloud-apim.llm-extension.llm_call", new LlmCallFunction())
    WorkflowFunction.registerFunction("extensions.com.cloud-apim.llm-extension.audio_tts", new AudioTtsFunction())
    WorkflowFunction.registerFunction("extensions.com.cloud-apim.llm-extension.audio_stt", new AudioSttFunction())
    WorkflowFunction.registerFunction("extensions.com.cloud-apim.llm-extension.compute_embedding", new ComputeEmbeddingFunction())
    WorkflowFunction.registerFunction("extensions.com.cloud-apim.llm-extension.generate_image", new GenerateImageFunction())
    WorkflowFunction.registerFunction("extensions.com.cloud-apim.llm-extension.generate_video", new GenerateVideoFunction())
    WorkflowFunction.registerFunction("extensions.com.cloud-apim.llm-extension.tool_function_call", new CallToolFunctionFunction())
    WorkflowFunction.registerFunction("extensions.com.cloud-apim.llm-extension.mcp_function_call", new CallMcpFunctionFunction())
    WorkflowFunction.registerFunction("extensions.com.cloud-apim.llm-extension.moderation_call", new ModerationCallFunction())
    WorkflowFunction.registerFunction("extensions.com.cloud-apim.llm-extension.guardrail_call", new GuardrailCallFunction())
    WorkflowFunction.registerFunction("extensions.com.cloud-apim.llm-extension.vector_store_add", new VectorStoreAddFunction())
    WorkflowFunction.registerFunction("extensions.com.cloud-apim.llm-extension.vector_store_remove", new VectorStoreRemoveFunction())
    WorkflowFunction.registerFunction("extensions.com.cloud-apim.llm-extension.vector_store_search", new VectorStoreSearchFunction())
    WorkflowFunction.registerFunction("extensions.com.cloud-apim.llm-extension.memory_add_messages", new MemoryAddMessagesFunction())
    WorkflowFunction.registerFunction("extensions.com.cloud-apim.llm-extension.memory_get_messages", new MemoryGetMessagesFunction())
    WorkflowFunction.registerFunction("extensions.com.cloud-apim.llm-extension.memory_clear_messages", new MemoryClearMessagesFunction())
    WorkflowFunction.registerFunction("extensions.com.cloud-apim.llm-extension.agent", new AgentFunction())
    Node.registerNode("extensions.com.cloud-apim.llm-extension.router", json => new RouterNode(json))
    Node.registerNode("extensions.com.cloud-apim.llm-extension.ai_agent", json => new AiAgentNode(json))
    Node.registerNode("extensions.com.cloud-apim.llm-extension.ai_agent_mcp_tools", json => new AiAgentMcpToolsNode(json))
    // text chunking ;)
  }
}

class AgentFunction extends WorkflowFunction {

  override def documentationName: String = "extensions.com.cloud-apim.llm-extension.agent"
  override def documentationDisplayName: String = "AI Agent (function)"
  override def documentationIcon: String = "fas fa-robot"
  override def documentationDescription: String = "Deprecated way to do AI agents"
  override def documentationInputSchema: Option[JsObject] = Some(Json.obj(
    "type" -> "object",
    "required" -> Json.arr("name", "provider", "description", "instructions", "input"),
    "properties" -> Json.obj(
      "name" -> Json.obj("type" -> "string", "description" -> "Name of the agent"),
      "provider" -> Json.obj("type" -> "string", "description" -> "Id of the llm provider"),
      "description" -> Json.obj("type" -> "string", "description" -> "Description of the agent (useful for handoff)"),
      "instructions" -> Json.obj("type" -> "array", "description" -> "System instructions for the agent"),
      "input" -> Json.obj("type" -> "string", "description" -> "The agent input"),
      "tools" -> Json.obj("type" -> "array", "description" -> "List of tool function ids"),
      "inline_tools" -> Json.obj("type" -> "array", "description" -> "List of inline tool function"),
      "memory" -> Json.obj(),
      "guardrails" -> Json.obj(),
      "response_json_parse" -> Json.obj(),
      "handoffs" -> Json.obj("type" -> "array", "description" -> "List of handoff objects", "properties" -> Json.obj(
        "agent" -> Json.obj("type" -> "object", "description" -> "an agent config"),
        "enabled" -> Json.obj("type" -> "boolean", "description" -> "is handoff enabled"),
        "tool_name_override" -> Json.obj("type" -> "string", "description" -> "tool name override"),
        "tool_description_override" -> Json.obj("type" -> "string", "description" -> "tool description"),
      )),
    )
  ))
  /*override def documentationFormSchema: Option[JsObject] = Some(Json.obj(
    "provider" -> Json.obj(
      "type"  -> "select",
      "label" -> "LLM provider",
      "props" -> Json.obj(
        "description" -> "The LLM provider",
        "optionsFrom" -> s"/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/providers",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      )
    ),
    "name" -> Json.obj(
      "type"  -> "string",
      "label" -> "Name",
      "props" -> Json.obj(
        "description" -> "Name"
      )
    ),
    "description" -> Json.obj(
      "type" -> "any",
      "label" -> "Description",
      "props" -> Json.obj(
        "height" -> "200px"
      )
    ),
    "instructions" -> Json.obj(
      "type" -> "any",
      "label" -> "Instructions",
      "props" -> Json.obj(
        "height" -> "200px",
      )
    ),
    "input" -> Json.obj(
      "type" -> "any",
      "label" -> "Agent input",
      "props" -> Json.obj(
        "height" -> "200px"
      )
    ),
    "tools" -> Json.obj(
      "type"  -> "select",
      "array" -> true,
      "label" -> "Tools",
      "props" -> Json.obj(
        "description" -> "Tools",
        "optionsFrom" -> s"/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/tool-functions",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      )
    ),
    "memory" -> Json.obj(
      "type"  -> "select",
      "label" -> "Persistent memory",
      "props" -> Json.obj(
        "description" -> "Persistent memory",
        "optionsFrom" -> s"/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/persistent-memories",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      )
    ),
    "guardrails" -> Json.obj(
      "label" -> "Guardrails",
      "type" -> "array",
      "array" -> true,
      "format" -> "form",
      "schema" -> Json.obj(
        "id" -> Json.obj(
          "label" -> "Guardrail",
          "type" -> "select",
          "props" -> Json.obj(
            "possibleValues" -> Json.arr(
              Json.obj("label" -> "Regex", "value" -> "regex"),
              Json.obj("label" -> "Webhook", "value" -> "webhook"),
              Json.obj("label" -> "LLM", "value" -> "llm"),
              Json.obj("label" -> "Secrets leakage", "value" -> "secrets_leakage"),
              Json.obj("label" -> "Auto Secrets leakage", "value" -> "auto_secrets_leakage"),
              Json.obj("label" -> "No gibberish", "value" -> "gibberish"),
              Json.obj("label" -> "No personal information", "value" -> "pif"),
              Json.obj("label" -> "Language moderation", "value" -> "moderation"),
              Json.obj("label" -> "Moderation model", "value" -> "moderation_model"),
              Json.obj("label" -> "No toxic language", "value" -> "toxic_language"),
              Json.obj("label" -> "No racial bias", "value" -> "racial_bias"),
              Json.obj("label" -> "No gender bias", "value" -> "gender_bias"),
              Json.obj("label" -> "No personal health information", "value" -> "personal_health_information"),
              Json.obj("label" -> "No prompt injection/prompt jailbreak", "value" -> "prompt_injection"),
              Json.obj("label" -> "Faithfulness", "value" -> "faithfulness"),
              Json.obj("label" -> "Sentences count", "value" -> "sentences"),
              Json.obj("label" -> "Words count", "value" -> "words"),
              Json.obj("label" -> "Characters count", "value" -> "characters"),
              Json.obj("label" -> "Text contains", "value" -> "contains"),
              Json.obj("label" -> "Semantic contains", "value" -> "semantic_contains"),
              Json.obj("label" -> "QuickJS", "value" -> "quickjs"),
              Json.obj("label" -> "Wasm", "value" -> "wasm"),
            )
          )
        ),
        "before" -> Json.obj("type" -> "boolean", "label" -> "Before", "props" -> Json.obj()),
        "after" ->  Json.obj("type" -> "boolean", "label" -> "After", "props" -> Json.obj()),
        "config" -> Json.obj("type" -> "any", "label" -> "Config", "props" -> Json.obj("height" -> "200px")),
      ),
      "flow" -> Json.arr("id", "before", "after", "config"),
    ),
  ))
  override def documentationCategory: Option[String] = Some("Cloud APIM - LLM extension")
  override def documentationOutputSchema: Option[JsObject] = None*/
  override def documentationExample: Option[JsObject] = Some(Json.obj(
    "id" -> "math_tutor",
    "kind" -> "call",
    "function" -> "extensions.com.cloud-apim.llm-extension.agent",
    "args" -> Json.obj(
      "name" -> "math_tutor",
      "provider" -> "provider_10bbc76d-7cd8-4cb7-b760-61e749a1b691",
      "description" -> "Specialist agent for math questions",
      "instructions" -> Json.arr(
        "You provide help with math problems. Explain your reasoning at each step and include examples."
      ),
      "input" -> "${input.question}"
    )
  ))

  override def callWithRun(args: JsObject)(implicit env: Env, ec: ExecutionContext, wfr: WorkflowRun): Future[Either[WorkflowError, JsValue]] = {
    val agent = AgentConfig.from(args)
    val rcfg = AgentRunConfig.from(args.select("run_config").asOpt[JsObject].getOrElse(Json.obj()))
    val input: AgentInput = args.select("input")
      .asOpt[JsValue]
      .map(v => WorkflowOperator.processOperators(v, wfr, env))
      .map {
        case JsString(str) => AgentInput.from(str)
        case JsArray(seq) => AgentInput(seq.map(v => ChatMessage.inputJson(v.asObject)))
        case obj @ JsObject(_) => AgentInput(Seq(ChatMessage.inputJson(obj)))
        case _ => AgentInput.empty
      }
      .getOrElse(AgentInput.empty)
    agent.run(input, rcfg, wfr.attrs, wfr.some).map {
      case Left(error) => Left(WorkflowError(s"Error executing workflow", error.asObject.some))
      case Right(resp) => resp.json.right
    }
  }
}

class MemoryAddMessagesFunction extends WorkflowFunction {

  override def documentationName: String = "extensions.com.cloud-apim.llm-extension.memory_add_messages"
  override def documentationDisplayName: String = "Memory add messages"
  override def documentationIcon: String = "fas fa-plus"
  override def documentationDescription: String = "Add messages to the memory for a session"
  override def documentationInputSchema: Option[JsObject] = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("provider", "payload"),
    "properties" -> Json.obj(
      "provider" -> Json.obj("type" -> "string", "description" -> "The provider id"),
      "payload" -> Json.obj("type" -> "object", "description" -> "The payload object", "properties" -> Json.obj(
        "session_id" -> Json.obj("type" -> "string", "description" -> "The session id"),
        "messages" -> Json.obj("type" -> "array", "description" -> "The messages", "items" -> Json.obj("type" -> "object", "properties" -> Json.obj(
          "role" -> Json.obj("type" -> "string", "description" -> "The role"),
          "content" -> Json.obj("type" -> "string", "description" -> "The content")
        )))
      ))
    )
  ))
  override def documentationFormSchema: Option[JsObject] = Some(Json.obj(
    "provider" -> Json.obj(
      "type" -> "select",
      "description" -> "The Memory provider id",
      "props" -> Json.obj(
        "description" -> "The Memory provider id",
        "optionsFrom" -> s"/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/persistent-memories",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      )
    ),
    "payload"  -> Json.obj(
      "type"  -> "any",
      "props" -> Json.obj(
        "description" -> "The payload object",
        "height" -> "200px"
      ),
      "label" -> "Payload"
    )
  ))
  override def documentationCategory: Option[String] = Some("Cloud APIM - LLM extension")
  override def documentationOutputSchema: Option[JsObject] = None
  override def documentationExample: Option[JsObject] = Some(Json.obj(
    "kind" -> "call",
    "function" -> "extensions.com.cloud-apim.llm-extension.memory_add_messages",
    "args" -> Json.obj(
      "provider" -> "memory_f141df8b-2642-4fba-82c8-5e050f62c920",
      "payload" -> Json.obj(
        "session_id" -> "session_id",
        "messages" -> Json.arr(
          Json.obj(
            "role" -> "user",
            "content" -> "Lorem ipsum dolor sit amet"
          )
        )
      )
    )
  ))

  override def callWithRun(args: JsObject)(implicit env: Env, ec: ExecutionContext, wfr: WorkflowRun): Future[Either[WorkflowError, JsValue]] = {
    val provider = args.select("provider").asString
    val payload = args.select("payload").asOpt[JsObject].getOrElse(Json.obj())
    val extension = env.adminExtensions.extension[AiExtension].get
    extension.states.persistentMemory(provider) match {
      case None => WorkflowError(s"embedding store not found", Some(Json.obj("provider_id" -> provider)), None).leftf
      case Some(provider) => provider.getPersistentMemoryClient() match {
        case None => WorkflowError(s"unable to instantiate client for memory", Some(Json.obj("provider_id" -> provider.id)), None).leftf
        case Some(client) => {
          val sessionId = payload.select("session_id").as[String]
          val messages = payload.select("messages").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map(o => PersistedChatMessage.from(o))
          client.addMessages(sessionId, messages).map {
            case Left(error) => WorkflowError(s"error while calling memory", Some(error.asOpt[JsObject].getOrElse(Json.obj("error" -> error))), None).left
            case Right(_) => JsNull.right
          }
        }
      }
    }
  }
}

class MemoryClearMessagesFunction extends WorkflowFunction {

  override def documentationName: String = "extensions.com.cloud-apim.llm-extension.memory_clear_messages"
  override def documentationDisplayName: String = "Memory clear messages"
  override def documentationIcon: String = "fas fa-trash"
  override def documentationDescription: String = "Clear messages from the memory for a session"
  override def documentationInputSchema: Option[JsObject] = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("provider", "payload"),
    "properties" -> Json.obj(
      "provider" -> Json.obj("type" -> "string", "description" -> "The provider id"),
      "payload" -> Json.obj("type" -> "object", "description" -> "The payload object", "properties" -> Json.obj(
        "session_id" -> Json.obj("type" -> "string", "description" -> "The session id")
      ))
    )
  ))
  override def documentationFormSchema: Option[JsObject] = Some(Json.obj(
    "provider" -> Json.obj(
      "type" -> "select",
      "description" -> "The Memory provider id",
      "props" -> Json.obj(
        "description" -> "The Memory provider id",
        "optionsFrom" -> s"/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/persistent-memories",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      )
    ),
    "payload"  -> Json.obj(
      "type"  -> "any",
      "props" -> Json.obj(
        "description" -> "The payload object",
        "height" -> "200px"
      ),
      "label" -> "Payload"
    )
  ))
  override def documentationCategory: Option[String] = Some("Cloud APIM - LLM extension")
  override def documentationOutputSchema: Option[JsObject] = None
  override def documentationExample: Option[JsObject] = Some(Json.obj(
    "kind" -> "call",
    "function" -> "extensions.com.cloud-apim.llm-extension.memory_clear_messages",
    "args" -> Json.obj(
      "provider" -> "memory_f141df8b-2642-4fba-82c8-5e050f62c920",
      "payload" -> Json.obj(
        "session_id" -> "session_id"
      )
    )
  ))

  override def callWithRun(args: JsObject)(implicit env: Env, ec: ExecutionContext, wfr: WorkflowRun): Future[Either[WorkflowError, JsValue]] = {
    val provider = args.select("provider").asString
    val payload = args.select("payload").asOpt[JsObject].getOrElse(Json.obj())
    val extension = env.adminExtensions.extension[AiExtension].get
    extension.states.persistentMemory(provider) match {
      case None => WorkflowError(s"embedding store not found", Some(Json.obj("provider_id" -> provider)), None).leftf
      case Some(provider) => provider.getPersistentMemoryClient() match {
        case None => WorkflowError(s"unable to instantiate client for memory", Some(Json.obj("provider_id" -> provider.id)), None).leftf
        case Some(client) => {
          val sessionId = payload.select("session_id").as[String]
          client.clearMemory(sessionId).map {
            case Left(error) => WorkflowError(s"error while calling memory", Some(error.asOpt[JsObject].getOrElse(Json.obj("error" -> error))), None).left
            case Right(_) => JsNull.right
          }
        }
      }
    }
  }
}

class MemoryGetMessagesFunction extends WorkflowFunction {

  override def documentationName: String = "extensions.com.cloud-apim.llm-extension.memory_get_messages"
  override def documentationDisplayName: String = "Memory get messages"
  override def documentationIcon: String = "fas fa-brain"
  override def documentationDescription: String = "Get messages from the memory for a session"
  override def documentationInputSchema: Option[JsObject] = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("provider", "payload"),
    "properties" -> Json.obj(
      "provider" -> Json.obj("type" -> "string", "description" -> "The provider id"),
      "payload" -> Json.obj("type" -> "object", "description" -> "The payload object", "properties" -> Json.obj(
        "session_id" -> Json.obj("type" -> "string", "description" -> "The session id")
      ))
    )
  ))
  override def documentationFormSchema: Option[JsObject] = Some(Json.obj(
    "provider" -> Json.obj(
      "type" -> "select",
      "description" -> "The Memory provider id",
      "props" -> Json.obj(
        "description" -> "The Memory provider id",
        "optionsFrom" -> s"/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/persistent-memories",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      )
    ),
    "payload"  -> Json.obj(
      "type"  -> "any",
      "props" -> Json.obj(
        "description" -> "The payload object",
        "height" -> "200px"
      ),
      "label" -> "Payload"
    )
  ))
  override def documentationCategory: Option[String] = Some("Cloud APIM - LLM extension")
  override def documentationOutputSchema: Option[JsObject] = Some(Json.obj(
    "type" -> "array",
    "items" -> Json.obj("type" -> "object")
  ))
  override def documentationExample: Option[JsObject] = Some(Json.obj(
    "kind" -> "call",
    "function" -> "extensions.com.cloud-apim.llm-extension.memory_get_messages",
    "args" -> Json.obj(
      "provider" -> "memory_f141df8b-2642-4fba-82c8-5e050f62c920",
      "payload" -> Json.obj(
        "session_id" -> "session_id"
      )
    )
  ))

  override def callWithRun(args: JsObject)(implicit env: Env, ec: ExecutionContext, wfr: WorkflowRun): Future[Either[WorkflowError, JsValue]] = {
    val provider = args.select("provider").asString
    val payload = args.select("payload").asOpt[JsObject].getOrElse(Json.obj())
    val extension = env.adminExtensions.extension[AiExtension].get
    extension.states.persistentMemory(provider) match {
      case None => WorkflowError(s"embedding store not found", Some(Json.obj("provider_id" -> provider)), None).leftf
      case Some(provider) => provider.getPersistentMemoryClient() match {
        case None => WorkflowError(s"unable to instantiate client for memory", Some(Json.obj("provider_id" -> provider.id)), None).leftf
        case Some(client) => {
          val sessionId = payload.select("session_id").as[String]
          client.getMessages(sessionId).map {
            case Left(error) => WorkflowError(s"error while calling memory", Some(error.asOpt[JsObject].getOrElse(Json.obj("error" -> error))), None).left
            case Right(messages) => JsArray(messages.map(_.raw)).right
          }
        }
      }
    }
  }
}

class GuardrailCallFunction extends WorkflowFunction {
  override def documentationName: String                   = "extensions.com.cloud-apim.llm-extension.guardrail_call"
  override def documentationDisplayName: String            = "Guardrail call"
  override def documentationIcon: String                   = "fas fa-shield-check"
  override def documentationDescription: String            = "This function calls a guardrail provider to validate the input"
  override def documentationInputSchema: Option[JsObject]  = Some(Json.obj(
    "type"       -> "object",
    "required"   -> Seq("kind", "config", "input"),
    "properties" -> Json.obj(
      "kind" -> Json.obj("type" -> "string", "description" -> "The guardrail kind"),
      "config"  -> Json.obj(
        "type" -> "object",
        "description" -> "The guardrail config",
      ),
      "input" -> Json.obj("type" -> "string", "description" -> "The input to validate"),
    )
  ))
  override def documentationFormSchema: Option[JsObject]   = Some(Json.obj(
    "kind" -> Json.obj(
      "type"  -> "string",
      "label" -> "Guardrail kind",
      "props" -> Json.obj(
        "description" -> "The guardrail kind"
      )
    ),
    "config"  -> Json.obj(
      "type"  -> "any",
      "props" -> Json.obj(
        "description" -> "The config object",
        "height" -> "200px"
      ),
      "label" -> "Payload"
    )
  ))
  override def documentationCategory: Option[String]       = Some("Cloud APIM - LLM extension")
  override def documentationOutputSchema: Option[JsObject] = None
  override def documentationExample: Option[JsObject]      = Some(Json.obj(
    "kind" -> "guardrail_call",
    "function" -> "extensions.com.cloud-apim.llm-extension.guardrail_call",
    "args" -> Json.obj(
      "kind" -> "regex",
      "config" -> Json.obj(
        "deny" -> Json.arr(".*bye.*")
      ),
      "input" -> "Hello there !",
    )
  ))

  override def callWithRun(args: JsObject)(implicit env: Env, ec: ExecutionContext, wfr: WorkflowRun): Future[Either[WorkflowError, JsValue]] = {
    val kind = args.select("kind").asString
    val config = args.select("config").asOpt[JsObject].getOrElse(Json.obj())
    val messages: Seq[ChatMessage] = args.select("input").asOptString match {
      case None => args.select("input").asOpt[JsObject] match {
        case None => args.select("input").asOpt[Seq[JsObject]] match {
          case None => Seq.empty
          case Some(arr) => arr.map(m => ChatMessage.inputJson(m))
        }
        case Some(m) => Seq(ChatMessage.inputJson(m))
      }
      case Some(str) => Seq(ChatMessage.input("user", str, None, Json.obj("role" -> "user", "content" -> str)))
    }
    Guardrails.get(kind) match {
      case None => Json.obj("pass" -> false, "cause" -> s"no guardrail of kind '${kind}' available").rightf
      case Some(guardrail) => {
        guardrail.pass(messages, config, None, None, wfr.attrs).map {
          case GuardrailResult.GuardrailPass => Json.obj("pass" -> true).right
          case GuardrailResult.GuardrailDenied(error) => Json.obj("pass" -> false, "cause" -> error).right
          case GuardrailResult.GuardrailError(error) => Json.obj("pass" -> false, "cause" -> "error", "error" -> error).right
        }
      }
    }
  }
}

class VectorStoreAddFunction extends WorkflowFunction {

  override def documentationName: String                   = "extensions.com.cloud-apim.llm-extension.vector_store_add"
  override def documentationDisplayName: String            = "Add to vector store"
  override def documentationIcon: String                   = "fas fa-plus"
  override def documentationDescription: String            = "This function adds an entry to a vector store"
  override def documentationInputSchema: Option[JsObject]  = Some(Json.obj(
    "type"       -> "object",
    "required"   -> Seq("provider", "payload"),
    "properties" -> Json.obj(
      "provider" -> Json.obj("type" -> "string", "description" -> "The embedding store provider id"),
      "payload"  -> Json.obj(
        "type" -> "object",
        "description" -> "The payload object",
        "properties" -> Json.obj(
          "id" -> Json.obj("type" -> "string", "description" -> "The id of the document"),
          "input" -> Json.obj("type" -> "string", "description" -> "The document content"),
          "embedding" -> Json.obj(
            "type" -> "object",
            "properties" -> Json.obj(
              "vector" -> Json.obj("type" -> "array", "description" -> "The vector representation of the document content"),
            )
          ),
        )
      )
    )
  ))
  override def documentationFormSchema: Option[JsObject]   = Some(Json.obj(
    "provider" -> Json.obj(
      "type" -> "select",
      "description" -> "The Embedding store provider id",
      "props" -> Json.obj(
        "description" -> "The Embedding store provider id",
        "optionsFrom" -> s"/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/embedding-stores",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      )
    ),
    "payload"  -> Json.obj(
      "type"  -> "any",
      "props" -> Json.obj(
        "description" -> "The payload object",
        "height" -> "200px"
      ),
      "label" -> "Payload"
    )
  ))
  override def documentationCategory: Option[String]       = Some("Cloud APIM - LLM extension")
  override def documentationOutputSchema: Option[JsObject] = None
  override def documentationExample: Option[JsObject]      = Some(Json.obj(
    "kind" -> "call",
    "function" -> "extensions.com.cloud-apim.llm-extension.vector_store_add",
    "args" -> Json.obj(
      "provider" -> "embedding-store_f141df8b-2642-4fba-82c8-5e050f62c920",
      "payload" -> Json.obj(
        "id" -> "document_id",
        "input" -> "Lorem ipsum dolor sit amet",
        "embedding" -> Json.obj(
          "vector" -> Json.arr(1.2, 2.3, 3.4, 4.5),
        )
      )
    )
  ))

  override def call(args: JsObject)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val provider = args.select("provider").asString
    val payload = args.select("payload").asOpt[JsObject].getOrElse(Json.obj())
    val extension = env.adminExtensions.extension[AiExtension].get
    extension.states.embeddingStore(provider) match {
      case None => WorkflowError(s"embedding store not found", Some(Json.obj("provider_id" -> provider)), None).leftf
      case Some(provider) => provider.getEmbeddingStoreClient() match {
        case None => WorkflowError(s"unable to instantiate client for embedding store", Some(Json.obj("provider_id" -> provider.id)), None).leftf
        case Some(client) => {
          val options = EmbeddingAddOptions.format.reads(payload).get
          client.add(options, payload).map {
            case Left(error) => WorkflowError(s"error while calling embedding store", Some(error.asOpt[JsObject].getOrElse(Json.obj("error" -> error))), None).left
            case Right(_) => JsNull.right
          }
        }
      }
    }
  }
}

class VectorStoreRemoveFunction extends WorkflowFunction {

  override def documentationName: String                   = "extensions.com.cloud-apim.llm-extension.vector_store_remove"
  override def documentationDisplayName: String            = "Remove from vector store"
  override def documentationIcon: String                   = "fas fa-minus"
  override def documentationDescription: String            = "This function removes an entry from a vector store"
  override def documentationInputSchema: Option[JsObject]  = Some(Json.obj(
    "type"       -> "object",
    "required"   -> Seq("provider", "payload"),
    "properties" -> Json.obj(
      "provider" -> Json.obj("type" -> "string", "description" -> "The embedding store provider id"),
      "payload"  -> Json.obj(
        "type" -> "object",
        "description" -> "The payload object",
        "properties" -> Json.obj(
          "id" -> Json.obj("type" -> "string", "description" -> "The id of the document"),
        )
      )
    )
  ))
  override def documentationFormSchema: Option[JsObject]   = Some(Json.obj(
    "provider" -> Json.obj(
      "type" -> "select",
      "description" -> "The Embedding store provider id",
      "props" -> Json.obj(
        "description" -> "The Embedding store provider id",
        "optionsFrom" -> s"/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/embedding-stores",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      )
    ),
    "payload"  -> Json.obj(
      "type"  -> "any",
      "props" -> Json.obj(
        "description" -> "The payload object",
        "height" -> "200px"
      ),
      "label" -> "Payload"
    )
  ))
  override def documentationCategory: Option[String]       = Some("Cloud APIM - LLM extension")
  override def documentationOutputSchema: Option[JsObject] = None
  override def documentationExample: Option[JsObject]      = Some(Json.obj(
    "kind" -> "call",
    "function" -> "extensions.com.cloud-apim.llm-extension.vector_store_remove",
    "args" -> Json.obj(
      "provider" -> "embedding-store_f141df8b-2642-4fba-82c8-5e050f62c920",
      "payload" -> Json.obj(
        "id" -> "document_id",
      )
    )
  ))

  override def call(args: JsObject)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val provider = args.select("provider").asString
    val payload = args.select("payload").asOpt[JsObject].getOrElse(Json.obj())
    val extension = env.adminExtensions.extension[AiExtension].get
    extension.states.embeddingStore(provider) match {
      case None => WorkflowError(s"embedding store not found", Some(Json.obj("provider_id" -> provider)), None).leftf
      case Some(provider) => provider.getEmbeddingStoreClient() match {
        case None => WorkflowError(s"unable to instantiate client for embedding store", Some(Json.obj("provider_id" -> provider.id)), None).leftf
        case Some(client) => {
          val options = EmbeddingRemoveOptions.format.reads(payload).get
          client.remove(options, payload).map {
            case Left(error) => WorkflowError(s"error while calling embedding store", Some(error.asOpt[JsObject].getOrElse(Json.obj("error" -> error))), None).left
            case Right(_) => JsNull.right
          }
        }
      }
    }
  }
}

class VectorStoreSearchFunction extends WorkflowFunction {

  override def documentationName: String                   = "extensions.com.cloud-apim.llm-extension.vector_store_search"
  override def documentationDisplayName: String            = "Search in vector store"
  override def documentationIcon: String                   = "fas fa-search"
  override def documentationDescription: String            = "This function searches for entries in a vector store"
  override def documentationInputSchema: Option[JsObject]  = Some(Json.obj(
    "type"       -> "object",
    "required"   -> Seq("provider", "payload"),
    "properties" -> Json.obj(
      "provider" -> Json.obj("type" -> "string", "description" -> "The embedding store provider id"),
      "payload"  -> Json.obj(
        "type" -> "object",
        "description" -> "The payload object",
        "properties" -> Json.obj(
          "embedding" -> Json.obj("type" -> "object", "description" -> "The embedding vector", "properties" -> Json.obj(
            "vector" -> Json.obj("type" -> "array", "description" -> "The vector representation of the document content"),
          )),
          "max_results" -> Json.obj("type" -> "integer", "description" -> "The maximum number of results to return"),
          "min_score" -> Json.obj("type" -> "number", "description" -> "The minimum score of the results"),
        )
      )
    )
  ))
  override def documentationFormSchema: Option[JsObject]   = Some(Json.obj(
    "provider" -> Json.obj(
      "type" -> "select",
      "description" -> "The Embedding store provider id",
      "props" -> Json.obj(
        "description" -> "The Embedding store provider id",
        "optionsFrom" -> s"/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/embedding-stores",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      )
    ),
    "payload"  -> Json.obj(
      "type"  -> "any",
      "props" -> Json.obj(
        "description" -> "The payload object",
        "height" -> "200px"
      ),
      "label" -> "Payload"
    )
  ))
  override def documentationCategory: Option[String]       = Some("Cloud APIM - LLM extension")
  override def documentationOutputSchema: Option[JsObject] = None
  override def documentationExample: Option[JsObject]      = Some(Json.obj(
    "kind" -> "call",
    "function" -> "extensions.com.cloud-apim.llm-extension.vector_store_search",
    "args" -> Json.obj(
      "provider" -> "embedding-store_f141df8b-2642-4fba-82c8-5e050f62c920",
      "payload" -> Json.obj(
        "embedding" -> Json.obj(
          "vector" -> Json.arr(1.2, 2.3, 3.4, 4.5),
        ),
        "max_results" -> 10,
        "min_score" -> 0.5,
      )
    )
  ))

  override def call(args: JsObject)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val provider = args.select("provider").asString
    val payload = args.select("payload").asOpt[JsObject].getOrElse(Json.obj())
    val extension = env.adminExtensions.extension[AiExtension].get
    extension.states.embeddingStore(provider) match {
      case None => WorkflowError(s"embedding store not found", Some(Json.obj("provider_id" -> provider)), None).leftf
      case Some(provider) => provider.getEmbeddingStoreClient() match {
        case None => WorkflowError(s"unable to instantiate client for embedding store", Some(Json.obj("provider_id" -> provider.id)), None).leftf
        case Some(client) => {
          val options = EmbeddingSearchOptions.format.reads(payload).get
          client.search(options, payload).map {
            case Left(error) => WorkflowError(s"error while calling embedding store", Some(error.asOpt[JsObject].getOrElse(Json.obj("error" -> error))), None).left
            case Right(response) => response.json.right
          }
        }
      }
    }
  }
}

class ModerationCallFunction extends WorkflowFunction {

  override def documentationName: String                   = "extensions.com.cloud-apim.llm-extension.moderation_call"
  override def documentationDisplayName: String            = "Moderation call"
  override def documentationIcon: String                   = "fas fa-shield-alt"
  override def documentationDescription: String            = "This function calls a moderation model"
  override def documentationInputSchema: Option[JsObject]  = Some(Json.obj(
    "type"       -> "object",
    "required"   -> Seq("input"),
    "properties" -> Json.obj(
      "model" -> Json.obj("type" -> "string", "description" -> "The moderation model name"),
      "input"  -> Json.obj(
        "type" -> "string",
        "description" -> "The input text"
      )
    )
  ))
  override def documentationFormSchema: Option[JsObject]   = Some(Json.obj(
    "model" -> Json.obj(
      "type" -> "select",
      "description" -> "The moderation model",
      "props" -> Json.obj(
        "description" -> "The moderation model",
        "optionsFrom" -> s"/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/moderation-models",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      )
    ),
    "input"  -> Json.obj(
      "type"  -> "any",
      "props" -> Json.obj(
        "description" -> "The input text",
        "height" -> "200px"
      ),
      "label" -> "Input"
    )
  ))
  override def documentationCategory: Option[String]       = Some("Cloud APIM - LLM extension")
  override def documentationOutputSchema: Option[JsObject] = Some(Json.obj(
    "type"       -> "object",
    "required"   -> Seq("results", "model"),
    "properties" -> Json.obj(
      "model" -> Json.obj("type" -> "string", "description" -> "The moderation model name"),
      "results"  -> Json.obj(
        "type" -> "array",
        "description" -> "The moderation results"
      )
    )
  ))
  override def documentationExample: Option[JsObject]      = Some(Json.obj(
    "kind" -> "call",
    "function" -> "extensions.com.cloud-apim.llm-extension.moderation_call",
    "args" -> Json.obj(
      "model" -> "moderation-model_f141df8b-2642-4fba-82c8-5e050f62c920",
      "input" -> "This is a test"
    )
  ))

  override def callWithRun(args: JsObject)(implicit env: Env, ec: ExecutionContext, wfr: WorkflowRun): Future[Either[WorkflowError, JsValue]] = {
    val provider = args.select("provider").asString
    val payload = args.select("payload").asOpt[JsObject].getOrElse(Json.obj())
    val extension = env.adminExtensions.extension[AiExtension].get
    extension.states.moderationModel(provider) match {
      case None => WorkflowError(s"moderation model not found", Some(Json.obj("provider_id" -> provider)), None).leftf
      case Some(provider) => provider.getModerationModelClient() match {
        case None => WorkflowError(s"unable to instantiate client for moderation model", Some(Json.obj("provider_id" -> provider.id)), None).leftf
        case Some(client) => {
          val options = ModerationModelClientInputOptions.format.reads(payload).get
          client.moderate(options, payload, wfr.attrs).map {
            case Left(error) => WorkflowError(s"error while calling moderation model", Some(error.asOpt[JsObject].getOrElse(Json.obj("error" -> error))), None).left
            case Right(response) => response.toOpenAiJson(env).right
          }
        }
      }
    }
  }
}

class GenerateVideoFunction extends WorkflowFunction {

  override def documentationName: String                   = "extensions.com.cloud-apim.llm-extension.generate_video"
  override def documentationDisplayName: String            = "Generate video"
  override def documentationIcon: String                   = "fas fa-video"
  override def documentationDescription: String            = "This function calls a video generation model"
  override def documentationInputSchema: Option[JsObject]  = Some(Json.obj(
    "type"       -> "object",
    "required"   -> Seq("provider", "payload"),
    "properties" -> Json.obj(
      "provider" -> Json.obj("type" -> "string", "description" -> "The video generation provider"),
      "payload" -> Json.obj("type" -> "object", "description" -> "The video generation payload", "properties" -> Json.obj(
      "prompt" -> Json.obj("type" -> "string", "description" -> "The video generation prompt"),
      "loop" -> Json.obj("type" -> "boolean", "description" -> "The video generation loop"),
      "model" -> Json.obj("type" -> "string", "description" -> "The video generation model name"),
      "aspect_ratio" -> Json.obj("type" -> "string", "description" -> "The video generation aspect ratio"),
      "resolution" -> Json.obj("type" -> "string", "description" -> "The video generation resolution"),
      "duration" -> Json.obj("type" -> "string", "description" -> "The video generation duration")
    )),
    )
  ))
  override def documentationFormSchema: Option[JsObject]   = Some(Json.obj( 
    "provider" -> Json.obj(
      "type" -> "select",
      "description" -> "The video generation model",
      "props" -> Json.obj(
        "description" -> "The video generation model",
        "optionsFrom" -> s"/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/video-models",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      )
    ),
    "payload" -> Json.obj(
      "type"  -> "any",
      "props" -> Json.obj(
        "description" -> "The video generation payload",
        "height" -> "200px"
      ),
      "label" -> "Payload"
    )
  ))
  override def documentationCategory: Option[String]       = Some("Cloud APIM - LLM extension")
  override def documentationOutputSchema: Option[JsObject] = Some(Json.obj(
    "type"       -> "object",
    "required"   -> Seq("model", "input"),
    "properties" -> Json.obj(
      "model" -> Json.obj("type" -> "string", "description" -> "The video generation model name"),
      "input"  -> Json.obj(
        "type" -> "string",
        "description" -> "The input text"
      )
    )
  ))
  override def documentationExample: Option[JsObject]      = Some(Json.obj(
    "kind" -> "call",
    "function" -> "extensions.com.cloud-apim.llm-extension.generate_video",
    "args" -> Json.obj(
      "model" -> "video-model_f141df8b-2642-4fba-82c8-5e050f62c920",
      "input" -> "This is a test"
    )
  ))

  override def callWithRun(args: JsObject)(implicit env: Env, ec: ExecutionContext, wfr: WorkflowRun): Future[Either[WorkflowError, JsValue]] = {
    val provider = args.select("provider").asString
    val payload = args.select("payload").asOpt[JsObject].getOrElse(Json.obj())
    val extension = env.adminExtensions.extension[AiExtension].get
    extension.states.videoModel(provider) match {
      case None => WorkflowError(s"video model not found", Some(Json.obj("provider_id" -> provider)), None).leftf
      case Some(provider) => provider.getVideoModelClient() match {
        case None => WorkflowError(s"unable to instantiate client for video model", Some(Json.obj("provider_id" -> provider.id)), None).leftf
        case Some(client) => {
          val options = VideoModelClientTextToVideoInputOptions.format.reads(payload).get
          client.generate(options, payload, wfr.attrs).map {
            case Left(error) => WorkflowError(s"error while calling video model", Some(error.asOpt[JsObject].getOrElse(Json.obj("error" -> error))), None).left
            case Right(response) => response.toOpenAiJson(env).right
          }
        }
      }
    }
  }
}

class CallMcpFunctionFunction extends WorkflowFunction {

  override def documentationName: String                   = "extensions.com.cloud-apim.llm-extension.mcp_function_call"
  override def documentationDisplayName: String            = "Call MCP function"
  override def documentationIcon: String                   = "fas fa-code"
  override def documentationDescription: String            = "This function calls a MCP function"
  override def documentationInputSchema: Option[JsObject]  = Some(Json.obj(
    "type"       -> "object",
    "required"   -> Seq("provider", "function", "arguments"),
    "properties" -> Json.obj(
      "provider" -> Json.obj("type" -> "string", "description" -> "The MCP connector provider"),
      "function" -> Json.obj("type" -> "string", "description" -> "The MCP function name"),
      "arguments" -> Json.obj("type" -> "string", "description" -> "The MCP function arguments")
    )
  ))
  override def documentationFormSchema: Option[JsObject]   = Some(Json.obj( 
    "provider" -> Json.obj(
      "type" -> "select",
      "description" -> "The MCP Connector",
      "props" -> Json.obj(
        "description" -> "The MCP Connector",
        "optionsFrom" -> s"/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/mcp-connectors",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      )
    ),
    "function" -> Json.obj(
      "type"  -> "string",
      "props" -> Json.obj(
        "description" -> "The MCP function name"
      ),
      "label" -> "Function"
    ),
    "arguments" -> Json.obj(
      "type"  -> "string",
      "props" -> Json.obj(
        "description" -> "The MCP function arguments"
      ),
      "label" -> "Arguments"
    )
  ))
  override def documentationCategory: Option[String]       = Some("Cloud APIM - LLM extension")
  override def documentationOutputSchema: Option[JsObject] = Some(Json.obj(
    "type"       -> "string",
    "description" -> "The MCP function result"
  ))
  override def documentationExample: Option[JsObject]      = Some(Json.obj(
    "kind" -> "call",
    "function" -> "extensions.com.cloud-apim.llm-extension.mcp_function_call",
    "args" -> Json.obj(
      "provider" -> "mcp-connector",
      "function" -> "my_function",
      "arguments" -> "my_arguments"
    )
  ))

  override def call(args: JsObject)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val provider = args.select("provider").asString
    val function = args.select("function").asString
    val arguments = args.select("arguments").asOpt[JsObject].map(_.stringify)
      .orElse(args.select("arguments").asOpt[JsArray].map(_.stringify))
      .orElse(args.select("arguments").asOpt[JsNumber].map(_.stringify))
      .orElse(args.select("arguments").asOpt[JsBoolean].map(_.stringify))
      .orElse(args.select("arguments").asOpt[String])
      .getOrElse("")
    val extension = env.adminExtensions.extension[AiExtension].get
    extension.states.mcpConnector(provider) match {
      case None => WorkflowError(s"llm provider not found", Some(Json.obj("provider_id" -> provider)), None).leftf
      case Some(connector) => {
        connector.call(function, arguments).map { res =>
          res.json.right
        }
      }
    }
  }
}

class CallToolFunctionFunction extends WorkflowFunction {

  override def documentationName: String                   = "extensions.com.cloud-apim.llm-extension.tool_function_call"
  override def documentationDisplayName: String            = "Call tool function"
  override def documentationIcon: String                   = "fas fa-code"
  override def documentationDescription: String            = "This function calls a tool function"
  override def documentationInputSchema: Option[JsObject]  = Some(Json.obj(
    "type"       -> "object",
    "required"   -> Seq("provider", "function", "arguments"),
    "properties" -> Json.obj(
      "provider" -> Json.obj("type" -> "string", "description" -> "The tool function provider"),
      "function" -> Json.obj("type" -> "string", "description" -> "The tool function name"),
      "arguments" -> Json.obj("type" -> "string", "description" -> "The tool function arguments")
    )
  ))
  override def documentationFormSchema: Option[JsObject]   = Some(Json.obj(
    "provider" -> Json.obj(
      "type" -> "select",
      "description" -> "The tool function",
      "props" -> Json.obj(
        "description" -> "The tool function",
        "optionsFrom" -> s"/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/tool-functions",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      )
    ),
    "function" -> Json.obj(
      "type"  -> "string",
      "props" -> Json.obj(
        "description" -> "The tool function name"
      ),
      "label" -> "Function"
    ),
    "arguments" -> Json.obj(
      "type"  -> "string",
      "props" -> Json.obj(
        "description" -> "The tool function arguments"
      ),
      "label" -> "Arguments"
    )
  ))
  override def documentationCategory: Option[String]       = Some("Cloud APIM - LLM extension")
  override def documentationOutputSchema: Option[JsObject] = Some(Json.obj(
    "type"       -> "string",
    "description" -> "The tool function result"
  ))
  override def documentationExample: Option[JsObject]      = Some(Json.obj(
    "kind" -> "call",
    "function" -> "extensions.com.cloud-apim.llm-extension.tool_function_call",
    "args" -> Json.obj(
      "provider" -> "tool-function",
      "function" -> "my_function",
      "arguments" -> "my_arguments"
    )
  ))

  override def callWithRun(args: JsObject)(implicit env: Env, ec: ExecutionContext, wfr: WorkflowRun): Future[Either[WorkflowError, JsValue]] = {
    val provider = args.select("provider").asString
    val arguments = args.select("arguments").asOpt[JsObject].map(_.stringify)
      .orElse(args.select("arguments").asOpt[JsArray].map(_.stringify))
      .orElse(args.select("arguments").asOpt[JsNumber].map(_.stringify))
      .orElse(args.select("arguments").asOpt[JsBoolean].map(_.stringify))
      .orElse(args.select("arguments").asOpt[String])
      .getOrElse("")
    val extension = env.adminExtensions.extension[AiExtension].get
    extension.states.toolFunction(provider) match {
      case None => WorkflowError(s"llm provider not found", Some(Json.obj("provider_id" -> provider)), None).leftf
      case Some(function) => {
        function.call(arguments, wfr.attrs).map { res =>
          res.json.right
        }
      }
    }
  }
}

class GenerateImageFunction extends WorkflowFunction {

  override def documentationName: String                   = "extensions.com.cloud-apim.llm-extension.generate_image"
  override def documentationDisplayName: String            = "Generate image"
  override def documentationIcon: String                   = "fas fa-image"
  override def documentationDescription: String            = "This function calls an image generation model"
  override def documentationInputSchema: Option[JsObject]  = Some(Json.obj(
    "type"       -> "object",
    "required"   -> Seq("provider", "model", "input"),
    "properties" -> Json.obj(
      "provider" -> Json.obj("type" -> "string", "description" -> "The image generation provider"),
      "payload" -> Json.obj("type" -> "object", "description" -> "The image generation payload", 
      "properties" -> Json.obj(
        "prompt" -> Json.obj("type" -> "string", "description" -> "The image generation prompt"),
        "background" -> Json.obj("type" -> "string", "description" -> "The image generation background"),
        "model" -> Json.obj("type" -> "string", "description" -> "The image generation model"),
        "moderation" -> Json.obj("type" -> "string", "description" -> "The image generation moderation"),
        "n" -> Json.obj("type" -> "integer", "description" -> "The image generation n"),
        "outputCompression" -> Json.obj("type" -> "integer", "description" -> "The image generation output compression"),
        "outputFormat" -> Json.obj("type" -> "string", "description" -> "The image generation output format"),
        "responseFormat" -> Json.obj("type" -> "string", "description" -> "The image generation response format"),
        "quality" -> Json.obj("type" -> "string", "description" -> "The image generation quality"),
        "size" -> Json.obj("type" -> "string", "description" -> "The image generation size"),
        "style" -> Json.obj("type" -> "string", "description" -> "The image generation style")
      )),
    )
  ))
  override def documentationFormSchema: Option[JsObject]   = Some(Json.obj(
    "provider" -> Json.obj(
      "type" -> "select",
      "description" -> "The image generation model",
      "props" -> Json.obj(
        "description" -> "The image generation model",
        "optionsFrom" -> s"/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/image-models",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      )
    ),
    "payload" -> Json.obj(
      "type"  -> "object",
      "props" -> Json.obj(
        "description" -> "The image generation payload"
      ),
      "label" -> "Payload"
    )
  ))
  override def documentationCategory: Option[String]       = Some("Cloud APIM - LLM extension")
  override def documentationOutputSchema: Option[JsObject] = Some(Json.obj(
    "type"       -> "string",
    "description" -> "The image generation result"
  ))
  override def documentationExample: Option[JsObject]      = Some(Json.obj(
    "kind" -> "call",
    "function" -> "extensions.com.cloud-apim.llm-extension.generate_image",
    "args" -> Json.obj(
      "provider" -> "image-generation",
      "payload" -> Json.obj(
        "prompt" -> "A beautiful landscape",
        "background" -> "white",
        "model" -> "dall-e-3",
        "moderation" -> "none",
        "n" -> 1,
        "outputCompression" -> 100,
        "outputFormat" -> "png",
        "responseFormat" -> "url",
        "quality" -> "standard",
        "size" -> "1024x1024",
        "style" -> "natural"
      )
    )
  ))

  override def callWithRun(args: JsObject)(implicit env: Env, ec: ExecutionContext, wfr: WorkflowRun): Future[Either[WorkflowError, JsValue]] = {
    val provider = args.select("provider").asString
    val payload = args.select("payload").asOpt[JsObject].getOrElse(Json.obj())
    val extension = env.adminExtensions.extension[AiExtension].get
    extension.states.imageModel(provider) match {
      case None => WorkflowError(s"image model not found", Some(Json.obj("provider_id" -> provider)), None).leftf
      case Some(provider) => provider.getImageModelClient() match {
        case None => WorkflowError(s"unable to instantiate client for image provider", Some(Json.obj("provider_id" -> provider.id)), None).leftf
        case Some(client) => {
          val options = ImageModelClientGenerationInputOptions.format.reads(payload).get
          client.generate(options, payload, wfr.attrs).map {
            case Left(error) => WorkflowError(s"error while calling embedding model", Some(error.asOpt[JsObject].getOrElse(Json.obj("error" -> error))), None).left
            case Right(response) => response.toOpenAiJson(env).right
          }
        }
      }
    }
  }
}

class ComputeEmbeddingFunction extends WorkflowFunction {

  override def documentationName: String                   = "extensions.com.cloud-apim.llm-extension.compute_embedding"
  override def documentationDisplayName: String            = "Compute embedding"
  override def documentationIcon: String                   = "fas fa-robot"
  override def documentationDescription: String            = "This function calls an embedding model"
  override def documentationInputSchema: Option[JsObject]  = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("provider", "payload"),
    "properties" -> Json.obj(
      "provider" -> Json.obj("type" -> "string", "description" -> "The embedding model provider id"),
      "payload" -> Json.obj("type" -> "object", "description" -> "The payload object", "properties" -> Json.obj(
        "input" -> Json.obj("type" -> "array", "description" -> "The input strings", "items" -> Json.obj("type" -> "string")),
        "model" -> Json.obj("type" -> "string", "description" -> "The embedding model"),
        "dimensions" -> Json.obj("type" -> "integer", "description" -> "The embedding dimensions"),
        "encoding_format" -> Json.obj("type" -> "string", "description" -> "The embedding encoding format"),
        "user" -> Json.obj("type" -> "string", "description" -> "The embedding user")
      ))
    )
  ))
  override def documentationFormSchema: Option[JsObject]   = Some(Json.obj(
    "provider" -> Json.obj(
      "type" -> "select",
      "description" -> "The embedding model",
      "props" -> Json.obj(
        "description" -> "The embedding model",
        "optionsFrom" -> s"/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/embedding-models",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      )
    ),
    "payload" -> Json.obj(
      "type"  -> "any",
      "label" -> "Payload",
      "props" -> Json.obj(
        "description" -> "The payload object",
        "height" -> "200px"
      )
    )
  ))
  override def documentationCategory: Option[String]       = Some("Cloud APIM - LLM extension")
  override def documentationOutputSchema: Option[JsObject] = Some(Json.obj(
    "type" -> "object",
    "properties" -> Json.obj(
      "data" -> Json.obj("type" -> "array", "description" -> "The embedding data", "items" -> Json.obj("type" -> "number")),
      "model" -> Json.obj("type" -> "string", "description" -> "The embedding model"),
      "usage" -> Json.obj("type" -> "object", "description" -> "The embedding usage", "properties" -> Json.obj(
        "prompt_tokens" -> Json.obj("type" -> "integer", "description" -> "The number of prompt tokens"),
        "total_tokens" -> Json.obj("type" -> "integer", "description" -> "The number of total tokens")
      ))
    )
  ))
  override def documentationExample: Option[JsObject]      = Some(Json.obj(
    "kind" -> "call",
    "function" -> "extensions.com.cloud-apim.llm-extension.compute_embedding",
    "args" -> Json.obj(
      "provider" -> "embedding-model",
      "payload" -> Json.obj(
        "input" -> Seq("Hello, world!"),
        "model" -> "text-embedding-ada-002",
        "dimensions" -> 1536,
        "encoding_format" -> "float",
        "user" -> "user-123"
      )
    )
  ))

  override def callWithRun(args: JsObject)(implicit env: Env, ec: ExecutionContext, wfr: WorkflowRun): Future[Either[WorkflowError, JsValue]] = {
    val provider = args.select("provider").asString
    val payload = args.select("payload").asOpt[JsObject].getOrElse(Json.obj())
    val extension = env.adminExtensions.extension[AiExtension].get
    extension.states.embeddingModel(provider) match {
      case None => WorkflowError(s"embedding model not found", Some(Json.obj("provider_id" -> provider)), None).leftf
      case Some(provider) => provider.getEmbeddingModelClient() match {
        case None => WorkflowError(s"unable to instantiate client for llm provider", Some(Json.obj("provider_id" -> provider.id)), None).leftf
        case Some(client) => {
          val options = EmbeddingClientInputOptions.format.reads(payload).get
          client.embed(options, payload, wfr.attrs).map {
            case Left(error) => WorkflowError(s"error while calling embedding model", Some(error.asOpt[JsObject].getOrElse(Json.obj("error" -> error))), None).left
            case Right(response) => response.toOpenAiJson(options.encoding_format.getOrElse("float")).right
          }
        }
      }
    }
  }
}

class LlmCallFunction extends WorkflowFunction {

  override def documentationName: String                   = "extensions.com.cloud-apim.llm-extension.llm_call"
  override def documentationDisplayName: String            = "Call LLM provider"
  override def documentationIcon: String                   = "fas fa-brain"
  override def documentationDescription: String            = "This function calls an LLM provider"
  override def documentationInputSchema: Option[JsObject]  = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("provider", "payload"),
    "properties" -> Json.obj(
      "provider" -> Json.obj("type" -> "string", "description" -> "The LLM provider id"),
      "openai_format" -> Json.obj("type" -> "boolean", "description" -> "The openai format"),
      "payload" -> Json.obj("type" -> "object", "description" -> "The payload object", "properties" -> Json.obj(
        "messages" -> Json.obj("type" -> "array", "description" -> "The messages", "items" -> Json.obj("type" -> "object")),
        "tool_functions" -> Json.obj("type" -> "array", "description" -> "The tool functions", "items" -> Json.obj("type" -> "string")),
        "mcp_connectors" -> Json.obj("type" -> "array", "description" -> "The mcp connectors", "items" -> Json.obj("type" -> "string")),
        "wasm_tools" -> Json.obj("type" -> "array", "description" -> "The wasm tools", "items" -> Json.obj("type" -> "string"))
      ))
    )
  ))
  override def documentationFormSchema: Option[JsObject]   = Some(Json.obj(
    "provider" -> Json.obj(
      "type" -> "select",
      "description" -> "The LLM provider",
      "props" -> Json.obj(
        "description" -> "The LLM provider",
        "optionsFrom" -> s"/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/providers",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      )
    ),
    "openai_format" -> Json.obj(
      "type"  -> "boolean",
      "props" -> Json.obj(
        "description" -> "The openai format"
      ),
      "label" -> "OpenAI format"
    ),
    "payload" -> Json.obj(
      "type"  -> "any",
      "props" -> Json.obj(
        "description" -> "The payload object",
        "height" -> "200px"
      ),
      "label" -> "Payload"
    )
  ))
  override def documentationCategory: Option[String]       = Some("Cloud APIM - LLM extension")
  override def documentationOutputSchema: Option[JsObject] = Some(Json.obj(
    "type"       -> "object",
    "required"   -> Seq("model", "input"),
    "properties" -> Json.obj(
      "model" -> Json.obj("type" -> "string", "description" -> "The LLM model name"),
      "input"  -> Json.obj(
        "type" -> "string",
        "description" -> "The input text"
      )
    )
  ))
  override def documentationExample: Option[JsObject]      = Some(Json.obj(
    "kind" -> "call",
    "function" -> "extensions.com.cloud-apim.llm-extension.llm_call",
    "args" -> Json.obj(
      "provider" -> "llm-provider",
      "openai_format" -> true,
      "payload" -> Json.obj(
        "messages" -> Seq(Json.obj(
          "role" -> "user",
          "content" -> "Hello, world!"
        ))
      )
    )
  ))

  override def callWithRun(args: JsObject)(implicit env: Env, ec: ExecutionContext, wfr: WorkflowRun): Future[Either[WorkflowError, JsValue]] = {
    val provider  = args.select("provider").asString
    val openai  = args.select("openai_format").asOptBoolean.getOrElse(true)
    val payload = args.select("payload").asOpt[JsObject].getOrElse(Json.obj())
    val messages = payload.select("messages").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map(obj => InputChatMessage.fromJson(obj))
    val extension = env.adminExtensions.extension[AiExtension].get
    extension.states.provider(provider) match {
      case None => WorkflowError(s"llm provider not found", Some(Json.obj("provider_id" -> provider)), None).leftf
      case Some(provider) => {
        val inlineToolFunctions: Seq[String] = args.select("tool_functions").asOpt[Seq[String]].getOrElse(Seq.empty) ++
          payload.select("tool_functions").asOpt[Seq[String]].getOrElse(Seq.empty) ++
          provider.options.select("wasm_tools").asOpt[Seq[String]].getOrElse(Seq.empty) ++
          provider.options.select("tool_functions").asOpt[Seq[String]].getOrElse(Seq.empty)
        val inlineMcpConnectors: Seq[String] = args.select("mcp_connectors").asOpt[Seq[String]].getOrElse(Seq.empty) ++
          payload.select("mcp_connectors").asOpt[Seq[String]].getOrElse(Seq.empty) ++
          provider.options.select("mcp_connectors").asOpt[Seq[String]].getOrElse(Seq.empty)
        val added: JsObject = Json.obj()
          .applyOnIf(inlineToolFunctions.nonEmpty)(_ ++ Json.obj("tool_functions" -> inlineToolFunctions))
          .applyOnIf(inlineMcpConnectors.nonEmpty)(_ ++ Json.obj("mcp_connectors" -> inlineMcpConnectors))
        provider.copy(options = provider.options ++ added).getChatClient() match {
          case None => WorkflowError(s"unable to instantiate client for llm provider", Some(Json.obj("provider_id" -> provider.id)), None).leftf
          case Some(client) => client.call(ChatPrompt(messages, None), wfr.attrs, (payload - "tool_functions" - "mcp_connectors" - "wasm_tools")).map {
            case Left(error) => WorkflowError(s"error while calling llm", Some(error.asOpt[JsObject].getOrElse(Json.obj("error" -> error))), None).left
            case Right(response) if openai => response.openaiJson("--", env).right
            case Right(response) => response.json(env).right
          }
        }
      }
    }
  }
}

class AudioTtsFunction extends WorkflowFunction {

  override def documentationName: String                   = "extensions.com.cloud-apim.llm-extension.audio_tts"
  override def documentationDisplayName: String            = "Text to speech"
  override def documentationIcon: String                   = "fas fa-volume-up"
  override def documentationDescription: String            = "This function calls an audio model provider to convert text to audio"
  override def documentationInputSchema: Option[JsObject]  = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("provider", "payload"),
    "properties" -> Json.obj(
      "provider" -> Json.obj("type" -> "string", "description" -> "The audio model provider id"),
      "encode_base64" -> Json.obj("type" -> "boolean", "description" -> "Encode the audio in base64"),
      "file_out" -> Json.obj("type" -> "string", "description" -> "The file path to save the audio"),
      "payload" -> Json.obj("type" -> "object", "description" -> "The payload object", "properties" -> Json.obj(
        "input" -> Json.obj("type" -> "string", "description" -> "The input text"),
        "model" -> Json.obj("type" -> "string", "description" -> "The model name"),
        "voice" -> Json.obj("type" -> "string", "description" -> "The voice name"),
        "instructions" -> Json.obj("type" -> "string", "description" -> "The instructions"),
        "responseFormat" -> Json.obj("type" -> "string", "description" -> "The response format"),
        "speed" -> Json.obj("type" -> "number", "description" -> "The speed")
      ))
    )
  ))
  
  override def documentationFormSchema: Option[JsObject]   = Some(Json.obj(
    "provider" -> Json.obj(
      "type"  -> "string",
      "props" -> Json.obj(
        "description" -> "The audio model provider id"
      ),
      "label" -> "Provider"
    ),
    "provider" -> Json.obj(
      "type" -> "select",
      "description" -> "The audio model provider",
      "props" -> Json.obj(
        "description" -> "The audio model provider",
        "optionsFrom" -> s"/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/audio-models",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      )
    ),
    "payload" -> Json.obj(
      "type"  -> "any",
      "props" -> Json.obj(
        "description" -> "The payload object",
        "height" -> "200px"
      ),
      "label" -> "Payload"
    )
  ))
  override def documentationCategory: Option[String]       = Some("Cloud APIM - LLM extension")
  override def documentationOutputSchema: Option[JsObject] = Some(Json.obj(
    "type"       -> "object",
    "required"   -> Seq("content_type", "base64"),
    "properties" -> Json.obj(
      "content_type" -> Json.obj("type" -> "string", "description" -> "The content type"),
      "base64"  -> Json.obj(
        "type" -> "string",
        "description" -> "The base64 encoded audio"
      )
    )
  ))
  override def documentationExample: Option[JsObject]      = Some(Json.obj(
    "kind" -> "call",
    "function" -> "extensions.com.cloud-apim.llm-extension.audio_tts",
    "args" -> Json.obj(
      "provider" -> "audio-provider",
      "payload" -> Json.obj(
        "input" -> "Hello, world!",
        "model" -> "audio-model",
        "voice" -> "voice",
        "instructions" -> "instructions",
        "responseFormat" -> "responseFormat",
        "speed" -> 1.0
      )
    )
  ))

  override def callWithRun(args: JsObject)(implicit env: Env, ec: ExecutionContext, wfr: WorkflowRun): Future[Either[WorkflowError, JsValue]] = {
    val provider  = args.select("provider").asString
    val payload = args.select("payload").asOpt[JsObject].getOrElse(Json.obj())
    val base64Encode = args.select("encode_base64").asOpt[Boolean].getOrElse(false)
    val fileDest = new File(args.select("file_out").asOpt[String].getOrElse(Files.createTempFile("audio-out-", ".mp3").toFile.getAbsolutePath))
    val extension = env.adminExtensions.extension[AiExtension].get
    extension.states.audioModel(provider) match {
      case None => WorkflowError(s"audio provider not found", Some(Json.obj("provider_id" -> provider)), None).leftf
      case Some(provider) => provider.getAudioModelClient() match {
        case None => WorkflowError(s"unable to instantiate client for audio provider", Some(Json.obj("provider_id" -> provider.id)), None).leftf
        case Some(client) => client.textToSpeech(AudioModelClientTextToSpeechInputOptions.format.reads(payload).get, payload, wfr.attrs).flatMap {
          case Left(error) => WorkflowError(s"error while calling audio model", Some(error.asOpt[JsObject].getOrElse(Json.obj("error" -> error))), None).leftf
          case Right(response) if base64Encode => response._1.runFold(ByteString.empty)(_ ++ _)(env.otoroshiMaterializer).map { bs =>
            Json.obj("content_type" -> response._2, "base64" -> bs.encodeBase64.utf8String).right
          }
          case Right(response) =>
            response._1.runWith(FileIO.toPath(fileDest.toPath))(env.otoroshiMaterializer).map { res =>
              Json.obj("content_type" -> response._2, "file_out" -> fileDest.getAbsolutePath).right
            }
        }
      }
    }
  }
}

class AudioSttFunction extends WorkflowFunction {

  override def documentationName: String                   = "extensions.com.cloud-apim.llm-extension.audio_stt"
  override def documentationDisplayName: String            = "Speech to text"
  override def documentationIcon: String                   = "fas fa-volume-up"
  override def documentationDescription: String            = "This function calls an audio model provider to convert audio to text"
  override def documentationInputSchema: Option[JsObject]  = Some(Json.obj(
    "type" -> "object",
    "required" -> Seq("provider", "payload"),
    "properties" -> Json.obj(
      "provider" -> Json.obj("type" -> "string", "description" -> "The audio model provider id"),
      "decode_base64" -> Json.obj("type" -> "boolean", "description" -> "Decode the base64 encoded audio"),
      "file_in" -> Json.obj("type" -> "string", "description" -> "The file path to read the audio"),
      "payload" -> Json.obj("type" -> "object", "description" -> "The payload object", "properties" -> Json.obj(
        "file" -> Json.obj("type" -> "string", "description" -> "The file path to read the audio"),
        "fileName" -> Json.obj("type" -> "string", "description" -> "The file name"),
        "fileContentType" -> Json.obj("type" -> "string", "description" -> "The file content type"),
        "fileLength" -> Json.obj("type" -> "number", "description" -> "The file length"),
        "model" -> Json.obj("type" -> "string", "description" -> "The model name"),
        "language" -> Json.obj("type" -> "string", "description" -> "The language"),
        "prompt" -> Json.obj("type" -> "string", "description" -> "The prompt"),
        "responseFormat" -> Json.obj("type" -> "string", "description" -> "The response format"),
        "temperature" -> Json.obj("type" -> "number", "description" -> "The temperature")
      ))
    )
  ))
  override def documentationFormSchema: Option[JsObject]   = Some(Json.obj(
    "provider" -> Json.obj(
      "type" -> "select",
      "description" -> "The audio model provider",
      "props" -> Json.obj(
        "description" -> "The audio model provider",
        "optionsFrom" -> s"/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/audio-models",
        "optionsTransformer" -> Json.obj(
          "label" -> "name",
          "value" -> "id",
        ),
      )
    ),
    "payload" -> Json.obj(
      "type"  -> "any",
      "props" -> Json.obj(
        "description" -> "The payload object",
        "height" -> "200px"
      ),
      "label" -> "Payload"
    )
  ))
  override def documentationCategory: Option[String]       = Some("Cloud APIM - LLM extension")
  override def documentationOutputSchema: Option[JsObject] = Some(Json.obj(
    "type"       -> "object",
    "required"   -> Seq("text"),
    "properties" -> Json.obj(
      "text" -> Json.obj("type" -> "string", "description" -> "The text")
    )
  ))
  override def documentationExample: Option[JsObject]      = Some(Json.obj(
    "kind" -> "call",
    "function" -> "extensions.com.cloud-apim.llm-extension.audio_stt",
    "args" -> Json.obj(
      "provider" -> "audio-provider",
      "decode_base64" -> true,
      "file_in" -> "audio.mp3",
      "payload" -> Json.obj(
        "model" -> "audio-model",
        "language" -> "en",
        "prompt" -> "prompt",
        "responseFormat" -> "responseFormat",
        "temperature" -> 0.5
      )
    )
  ))

  override def callWithRun(args: JsObject)(implicit env: Env, ec: ExecutionContext, wfr: WorkflowRun): Future[Either[WorkflowError, JsValue]] = {
    val provider  = args.select("provider").asString
    val payload = args.select("payload").asOpt[JsObject].getOrElse(Json.obj())
    val base64Decode = args.select("decode_base64").asOpt[Boolean].getOrElse(false)
    val fileIn = args.select("file_in").asOpt[String]
    val extension = env.adminExtensions.extension[AiExtension].get
    extension.states.audioModel(provider) match {
      case None => WorkflowError(s"audio provider not found", Some(Json.obj("provider_id" -> provider)), None).leftf
      case Some(provider) => provider.getAudioModelClient() match {
        case None => WorkflowError(s"unable to instantiate client for audio provider", Some(Json.obj("provider_id" -> provider.id)), None).leftf
        case Some(client) => {
          val bytes: ByteString = fileIn match {
            case None if base64Decode => payload.select("audio").asString.byteString.decodeBase64
            case None => payload.select("audio").asOpt[Array[Byte]].map(v => ByteString(v)).getOrElse(ByteString.empty)
            case Some(file) => ByteString(Files.readAllBytes(new File(file).toPath))
          }
          val options = AudioModelClientSpeechToTextInputOptions.format.reads(payload).get.copy(
            file = bytes.chunks(32 * 1024),
            fileContentType = payload.select("content_type").asOptString.getOrElse("audio/mp3"),
            fileLength = bytes.length,
            fileName = payload.select("filename").asOptString,
          )
          client.speechToText(options, payload, wfr.attrs).map {
            case Left(error) => WorkflowError(s"error while calling audio model", Some(error.asOpt[JsObject].getOrElse(Json.obj("error" -> error))), None).left
            case Right(response) =>
              //println(s"transcribe: ${response.transcribedText}")
              response.transcribedText.json.right
          }
        }
      }
    }
  }
}