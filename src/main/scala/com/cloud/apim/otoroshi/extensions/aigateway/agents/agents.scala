package com.cloud.apim.otoroshi.extensions.aigateway.agents

import com.cloud.apim.otoroshi.extensions.aigateway.decorators.Guardrails
import com.cloud.apim.otoroshi.extensions.aigateway._
import otoroshi.env.Env
import otoroshi.next.workflow.{Node, NodeLike, NoopNode, WorkflowAdminExtension, WorkflowError, WorkflowOperator, WorkflowRun}
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json._
import play.api.libs.typedmap.TypedKey

import scala.concurrent.{ExecutionContext, Future}

object InlineFunctions {
  val InlineFunctionsKey = TypedKey[Map[String, InlineFunction]]("cloud-apim.ai-gateway.InlineFunctions")
  val InlineFunctionWfrKey = TypedKey[Option[WorkflowRun]]("cloud-apim.ai-gateway.InlineFunctionsWorkflowRun")
}

case class InlineFunctionDeclaration(name: String, description: String = "", strict: Boolean = true, parameters: JsObject, required: Seq[String] = Seq.empty)
case class InlineFunction(declaration: InlineFunctionDeclaration, call: Function4[String, TypedMap, Env, ExecutionContext, Future[String]])

case class ToolRef(kind: String, ref: Option[String], node: Option[JsValue])

case class Handoff(
  agent: AgentConfig,
  enabled: Boolean = true,
  tool_name_override: Option[String] = None,
  tool_description_override: Option[String] = None,
  on_handoff: Option[Function[AgentInput, Unit]] = None,
) {

  def functionName: String = tool_name_override.getOrElse(s"transfer_to_${agent.name.slugifyWithSlash}")
  def functionDescription: String = tool_description_override.getOrElse(s"Handoff to the ${agent.name} agent to handle the request. ${agent.description}")

  // TODO: call formatter for provider
  def toFunction: JsObject = Json.obj(
    "type" -> "function",
    "function" -> Json.obj(
      "name" -> functionName,
      "description" -> functionDescription,
      "parameters" -> Json.obj(
        "additionalProperties" -> false,
        "type" -> "object",
        "properties" -> Json.obj(),
        "required" -> Json.arr()
      )
    )
  )
}

object Handoff {
  def from(json: JsValue): Handoff = {
    Handoff(
      agent = AgentConfig.from(json.select("agent").asObject),
      enabled = json.select("enabled").asOpt[Boolean].getOrElse(true),
      tool_name_override = json.select("tool_name_override").asOpt[String],
      tool_description_override = json.select("tool_description_override").asOpt[String],
      on_handoff = None,
    )
  }
}

case class AgentConfig(
  name: String,
  description: String = "",
  instructions: Seq[String],
  provider: Option[String] = None,
  model: Option[String] = None,
  modelOptions: Option[JsObject] = None,
  tools: Seq[String] = Seq.empty,
  inlineTools: Seq[InlineFunction] = Seq.empty,
  handoffs: Seq[Handoff] = Seq.empty,
  memory: Option[String] = None,
  guardrails: Guardrails = Guardrails(Seq.empty),
) {
  def runStr(input: String, rcfg: AgentRunConfig = AgentRunConfig(), attrs: TypedMap = TypedMap.empty, wfr: Option[WorkflowRun])(implicit env: Env):  Future[Either[JsValue, String]] = {
    run(AgentInput.from(input), rcfg, attrs, wfr)
  }
  def run(input: AgentInput, rcfg: AgentRunConfig = AgentRunConfig(), attrs: TypedMap = TypedMap.empty, wfr: Option[WorkflowRun])(implicit env: Env):  Future[Either[JsValue, String]] = {
    new AgentRunner(env).run(this, input, rcfg, attrs, wfr)
  }
  def toHandoff(): Handoff = {
    Handoff(
      agent = this,
      enabled = true,
      tool_name_override = None,
      tool_description_override = None,
      on_handoff = None,
    )
  }
}

object AgentConfig {

  def node_from(json: JsObject): Node = {
    val kind = json.select("kind").asOpt[String].getOrElse("--").toLowerCase()
    Node.nodes.get(kind) match {
      case None       => NoopNode(json)
      case Some(node) => node(json)
    }
  }

  def from(json: JsValue): AgentConfig = {
    AgentConfig(
      name = json.select("name").asString,
      description = json.select("description").asOptString.getOrElse(""),
      instructions = json.select("instructions").asOpt[Seq[String]].orElse(json.select("instructions").asOptString.map(s => Seq(s))).getOrElse(Seq.empty),
      provider = json.select("provider").asOpt[String],
      model = json.select("model").asOpt[String],
      modelOptions = json.select("model_options").asOpt[JsObject],
      tools = json.select("tools").asOpt[Seq[String]].getOrElse(Seq.empty),
      inlineTools = json.select("inline_tools").asOpt[Seq[JsObject]].map { seq =>
        seq.map { tool =>
          val nodeJson = tool.select("node").as[JsObject]
          val node = node_from(nodeJson)
          InlineFunction(
            InlineFunctionDeclaration(
              name = tool.select("name").asString,
              description = tool.select("description").asString,
              strict = tool.select("strict").asOptBoolean.getOrElse(true),
              parameters = tool.select("parameters").asOpt[JsObject].getOrElse(Json.obj()),
              required = tool.select("required").asOpt[Seq[String]].getOrElse(Seq.empty),
            ),
            call = (args, attrs, env, ec) => {
              attrs.get(InlineFunctions.InlineFunctionWfrKey).flatten match {
                case None => "Workflow runner not available".vfuture
                case Some(wfr) => {
                  wfr.attrs.get(otoroshi.next.workflow.WorkflowAdminExtension.liveUpdatesSourceKey).foreach { source =>
                    source.tryEmitNext(Json.obj("kind" -> "progress", "data" -> Json.obj(
                      "timestamp" -> System.currentTimeMillis(),
                      "message"   -> s"starting '${tool.select("id").asOpt[String].orElse(tool.select("name").asOpt[String]).getOrElse("--")}'",
                      "node"      -> tool,
                      "memory"    -> wfr.memory.json,
                      "error"     -> JsNull
                    )))
                  }
                  //println(s"callllllll: ${args}")
                  wfr.memory.set("tool_input", args.json)
                  node.internalRun(wfr, Seq.empty, Seq.empty)(env, ec).map {
                    case Left(err) =>
                      wfr.attrs.get(otoroshi.next.workflow.WorkflowAdminExtension.liveUpdatesSourceKey).foreach { source =>
                        source.tryEmitNext(Json.obj("kind" -> "progress", "data" -> Json.obj(
                          "timestamp" -> System.currentTimeMillis(),
                          "message"   -> s"stopping '${tool.select("id").asOpt[String].orElse(tool.select("name").asOpt[String]).getOrElse("--")}'",
                          "node"      -> tool,
                          "memory"    -> wfr.memory.json,
                          "error"     -> JsNull
                        )))
                      }
                      err.json.stringify
                    case Right(v) =>
                      wfr.attrs.get(otoroshi.next.workflow.WorkflowAdminExtension.liveUpdatesSourceKey).foreach { source =>
                        source.tryEmitNext(Json.obj("kind" -> "progress", "data" -> Json.obj(
                          "timestamp" -> System.currentTimeMillis(),
                          "message"   -> s"stopping '${tool.select("id").asOpt[String].orElse(tool.select("name").asOpt[String]).getOrElse("--")}'",
                          "node"      -> tool,
                          "memory"    -> wfr.memory.json,
                          "error"     -> JsNull
                        )))
                      }
                      //println(s"responssssss: ${v.stringify}")
                      v.stringify
                  }(ec)
                }
              }
            },
          )
        }
      }.getOrElse(Seq.empty),
      handoffs = json.select("handoffs").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map(o => Handoff.from(o)),
      memory = json.select("memory").asOptString,
      guardrails = json.select("guardrails").asOpt[JsArray].flatMap(seq => Guardrails.format.reads(seq).asOpt).getOrElse(Guardrails.empty),
    )
  }
}

object AgentRunConfig {
  def from(json: JsValue): AgentRunConfig = {
    AgentRunConfig(
      provider = json.select("provider").asOpt[String],
      model = json.select("model").asOpt[String],
      modelOptions = json.select("model_options").asOpt[JsObject],
      maxTurns = json.select("max_turns").asOpt[Int].getOrElse(10),
    )
  }
}

case class AgentRunConfig(provider: Option[String] = None, model: Option[String] = None, modelOptions: Option[JsObject] = None, maxTurns: Int = 10)

case class AgentContext(iteration: Int = 0)

case class AgentInput(messages: Seq[InputChatMessage])

object AgentInput {
  val empty: AgentInput = AgentInput(Seq.empty)
  def from(str: String): AgentInput = AgentInput(Seq(ChatMessage.userStrInput(str)))
}

class AgentRunner(env: Env) {

  implicit val ev = env
  implicit val ec = env.otoroshiExecutionContext
  implicit val mat = env.otoroshiMaterializer

  lazy val ext = env.adminExtensions.extension[AiExtension].get

  def run(agent: AgentConfig, input: AgentInput, rcfg: AgentRunConfig = AgentRunConfig(), attrs: TypedMap = TypedMap.empty, wfr: Option[WorkflowRun]): Future[Either[JsValue, String]] = {
    internalRun(agent, input, rcfg, AgentContext(1), attrs, wfr)
  }

  private def internalRun(agent: AgentConfig, input: AgentInput, rcfg: AgentRunConfig = AgentRunConfig(), ctx: AgentContext = AgentContext(), attrs: TypedMap = TypedMap.empty, wfr: Option[WorkflowRun]): Future[Either[JsValue, String]] = {
    if (ctx.iteration > rcfg.maxTurns) {
      Json.obj("error" -> "Max turns reached").leftf
    } else {
      agent.provider.orElse(rcfg.provider) match {
        case None => Json.obj("error" -> "no provider ref").leftf
        case Some(pref) => {
          ext.states.provider(pref) match {
            case None => Json.obj("error" -> "no provider").leftf
            case Some(provider) => {
              var additionToolFunctions = Seq.empty[String]
              var inlineFunctions = Map.empty[String, InlineFunction]
              if (agent.inlineTools.nonEmpty) {
                additionToolFunctions = additionToolFunctions ++ agent.inlineTools.map("__inline_" + _.declaration.name)
                inlineFunctions = inlineFunctions ++ agent.inlineTools.map(v => ("__inline_" + v.declaration.name, v)).toMap
              }
              if (agent.tools.nonEmpty) {
                additionToolFunctions = additionToolFunctions ++ agent.tools
              }
              val over = Json.obj()
                .applyOnWithOpt(agent.modelOptions.orElse(rcfg.modelOptions)) {
                  case (obj, options) => obj.deepMerge(options)
                }
                .applyOnWithOpt(agent.model.orElse(rcfg.model)) {
                  case (obj, model) => obj ++ Json.obj("model" -> model)
                }
                // .applyOnIf(agent.tools.nonEmpty && agent.handoffs.isEmpty) { obj =>
                //   obj ++ Json.obj("tool_functions" -> agent.tools)
                // }
                .applyOnIf(additionToolFunctions.nonEmpty && agent.handoffs.isEmpty) { obj =>
                  obj ++ Json.obj("tool_functions" -> additionToolFunctions)
                }
              val hasHandoff = agent.handoffs.exists(_.enabled)
              val body = Json.obj()
                .applyOnIf(hasHandoff) { obj =>
                  val tools = agent.handoffs.filter(_.enabled).map(_.toFunction)
                  obj ++ Json.obj("tools" -> tools)
                }
              attrs.put(
                InlineFunctions.InlineFunctionsKey -> inlineFunctions,
                InlineFunctions.InlineFunctionWfrKey -> wfr
              )
              provider.copy(
                options = provider.options.deepMerge(over),
                memory = agent.memory,
                guardrailsFailOnDeny = true,
                guardrails = agent.guardrails.copy(items = agent.guardrails.items.map { it =>
                  val actualProvider = it.config.select("provider").asOptString.orElse(agent.model.orElse(rcfg.model)).get
                  it.copy(config = it.config ++ Json.obj("provider" -> actualProvider))
                }),
              ).getChatClient() match {
                case None => Json.obj("error" -> "no client").leftf
                case Some(client) => {
                  client.call(
                    ChatPrompt(Seq(
                      ChatMessage.input("system", agent.instructions.mkString(" "), prefix = None, Json.obj()),
                    ) ++ input.messages),
                    attrs,
                    body
                  ).flatMap {
                    case Left(err) => Left(err).vfuture
                    case Right(resp) => {
                      resp.generations.headOption match {
                        case None => Json.obj("error" -> "no generated message").leftf
                        case Some(gen) => {
                          if (gen.message.has_tool_calls && hasHandoff) {
                            val possibleNames = agent.handoffs.map(_.functionName)
                            val handoff_call = gen.message.tool_calls.get.map { tool_call =>
                              val functionName = tool_call.select("function").select("name").asOpt[String].orElse(
                                tool_call.select("functionName").asOpt[String]
                              ).orElse(
                                tool_call.select("name").asOpt[String]
                              ).getOrElse("--")
                              (functionName, tool_call)
                            }.find(tuple => possibleNames.contains(tuple._1))
                            handoff_call match {
                              case None => Json.obj("error" -> "no handoff found").leftf
                              case Some((name, handoff_call)) => {
                                val handoff = agent.handoffs.find(_.functionName == name).get
                                handoff.on_handoff.foreach(_.apply(input))
                                internalRun(handoff.agent, input, rcfg.copy(
                                  provider = agent.provider.orElse(rcfg.provider),
                                  model = agent.model.orElse(rcfg.model),
                                  modelOptions = agent.modelOptions.orElse(rcfg.modelOptions),
                                ), ctx.copy(iteration = ctx.iteration + 1), attrs, wfr)
                              }
                            }
                          } else if (gen.message.has_tool_calls && hasHandoff) {
                            Json.obj("error" -> "pending tool_call").leftf
                          } else {
                            gen.message.wholeTextContent.rightf
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  def test(): Unit = {

    val math_tutor_agent = AgentConfig(
      name = "Math Tutor",
      description = "Specialist agent for math questions",
      instructions = Seq("You provide help with math problems. Explain your reasoning at each step and include examples"),
    )

    val history_tutor_agent = AgentConfig(
      name = "History Tutor",
      description = "Specialist agent for historical questions",
      instructions = Seq("You provide assistance with historical queries. Explain important events and context clearly."),
    )

    val triage_agent = AgentConfig(
      name = "Triage Agent",
      instructions = Seq("You determine which agent to use based on the user's homework question"),
      handoffs = Seq(
        math_tutor_agent.toHandoff(),
        history_tutor_agent.toHandoff(),
      )
    )

    run(triage_agent, AgentInput.from("who was the first president of the united states?"), AgentRunConfig(provider = "provider_10bbc76d-7cd8-4cb7-b760-61e749a1b691".some), wfr = None).map {
      case Left(err) => println(s"test error: ${err.prettify}")
      case Right(resp) => println(s"resp: ${resp}")
    }
  }
}

class RouterNode(val json: JsObject) extends Node {

  def from(json: JsObject): Node = {
    val kind = json.select("kind").asOpt[String].getOrElse("--").toLowerCase()
    Node.nodes.get(kind) match {
      case None       => NoopNode(json)
      case Some(node) => node(json)
    }
  }

  override def subNodes: Seq[NodeLike]                    =
    json.select("paths").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map(v => from(v))
  override def documentationName: String                  = "extensions.com.cloud-apim.llm-extension.router"
  override def documentationDisplayName: String           = "AI Agent Router"
  override def documentationIcon: String                  = "fas fa-exchange-alt"
  override def documentationDescription: String           = "This node uses an LLM to choose which path to follow"
  override def documentationInputSchema: Option[JsObject] = None /*Node.baseInputSchema
    .deepMerge(
      Json.obj(
        "properties" -> Json.obj(
          "paths" -> Json.obj(
            "type"        -> "array",
            "description" -> "the nodes to be executed",
            "items"       -> Json.obj(
              "type"       -> "object",
              "required" -> Json.arr("id", "description"),
              "properties" -> Json.obj(
                "id" -> Json.obj("type" -> "string", "description" -> "the id of the node"),
                "description" -> Json.obj("type" -> "string", "description" -> "the description of the node"),
              )
            )
          )
        )
      )
    )
    .some*/
  override def documentationExample: Option[JsObject]     = Some(
    Json.parse(s"""{
                  |  "kind": "extensions.com.cloud-apim.llm-extension.router",
                  |  "result": "call_res",
                  |  "provider": "provider_10bbc76d-7cd8-4cb7-b760-61e749a1b691",
                  |  "input": "$${input.question}",
                  |  "instructions": [
                  |    "You determine which agent to use based on the user's homework question"
                  |  ],
                  |  "paths": [
                  |    {
                  |      "id": "math_tutor",
                  |      "kind": "call",
                  |      "function": "extensions.com.cloud-apim.llm-extension.agent",
                  |      "args": {
                  |        "name": "math_tutor",
                  |        "provider": "provider_10bbc76d-7cd8-4cb7-b760-61e749a1b691",
                  |        "description": "Specialist agent for math questions",
                  |        "instructions": [
                  |          "You provide help with math problems. Explain your reasoning at each step and include examples."
                  |        ],
                  |        "input": "$${input.question}"
                  |      },
                  |      "result": "call_res"
                  |    },
                  |    {
                  |      "id": "history_tutor",
                  |      "kind": "call",
                  |      "function": "extensions.com.cloud-apim.llm-extension.agent",
                  |      "args": {
                  |        "name": "history_tutor",
                  |        "provider": "provider_10bbc76d-7cd8-4cb7-b760-61e749a1b691",
                  |        "description": "Specialist agent for historical questions",
                  |        "instructions": [
                  |          "You provide assistance with historical queries. Explain important events and context clearly."
                  |        ],
                  |        "input": "$${input.question}"
                  |      },
                  |      "result": "call_res"
                  |    }
                  |  ]
                  |}""".stripMargin).asObject
  )
  override def run(
                    wfr: WorkflowRun,
                    prefix: Seq[Int],
                    from: Seq[Int]
                  )(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    if (from.nonEmpty) {
      WorkflowError(
        s"Router Node (${prefix.mkString(".")}) does not support resume: ${from.mkString(".")}",
        None,
        None
      ).leftf
    } else {
      val paths: Seq[Node] = subNodes.map(_.asInstanceOf[Node])
      val ext = env.adminExtensions.extension[AiExtension].get
      val input = json.select("input")
        .asOpt[JsValue]
        .map(v => WorkflowOperator.processOperators(v, wfr, env))
        .orElse(wfr.memory.get("input"))
        .flatMap(_.asOptString)
        .getOrElse("--")
      val model = json.select("model").asOptString
      val instructions = json.select("instructions").as[Seq[String]]
      val modelOptions = json.select("model_options").asOpt[JsObject]
      json.select("provider").asOptString match {
        case None => WorkflowError("no provider ref").leftf
        case Some(pref) => {
          ext.states.provider(pref) match {
            case None => WorkflowError("no provider").leftf
            case Some(provider) => {
              val over = Json.obj()
                .applyOnWithOpt(modelOptions) {
                  case (obj, options) => obj.deepMerge(options)
                }
                .applyOnWithOpt(model) {
                  case (obj, model) => obj ++ Json.obj("model" -> model)
                }
              val tools = paths.map { node =>
                Json.obj(
                  "type" -> "function",
                  "function" -> Json.obj(
                    "name" -> node.id,
                    "description" -> node.description,
                    "parameters" -> Json.obj(
                      "additionalProperties" -> false,
                      "type" -> "object",
                      "properties" -> Json.obj(),
                      "required" -> Json.arr()
                    )
                  )
                )
              }
              val body = Json.obj("tools" -> tools)
              provider.copy(
                options = provider.options.deepMerge(over)
              ).getChatClient() match {
                case None => WorkflowError("no client").leftf
                case Some(client) => {
                  client.call(
                    ChatPrompt(Seq(
                      ChatMessage.input("system", instructions.mkString(" "), prefix = None, Json.obj()),
                      ChatMessage.input("user", input, prefix = None, Json.obj()),
                    )),
                    wfr.attrs,
                    body
                  ).flatMap {
                    case Left(err) => WorkflowError("LLM Call error", err.asObject.some).leftf
                    case Right(resp) => {
                      resp.generations.headOption match {
                        case None => WorkflowError("no generated message").leftf
                        case Some(gen) => {
                          if (gen.message.has_tool_calls) {
                            val possibleNames = paths.map(_.id)
                            val handoff_call = gen.message.tool_calls.get.zipWithIndex.map {
                              case (tool_call, idx) =>
                                val functionName = tool_call.select("function").select("name").asOpt[String].orElse(
                                  tool_call.select("functionName").asOpt[String]
                                ).orElse(
                                  tool_call.select("name").asOpt[String]
                                ).getOrElse("--")
                                (functionName, tool_call, idx)
                            }.find(tuple => possibleNames.contains(tuple._1))
                            handoff_call match {
                              case None => WorkflowError("no handoff found").leftf
                              case Some((name, handoff_call, idx)) => {
                                val handoff = paths.find(_.id == name).get
                                handoff.internalRun(wfr, prefix :+ idx, from).recover { case t: Throwable =>
                                  WorkflowError(s"caught exception on task '${id}' at path: '${handoff.id}'", None, Some(t)).left
                                }
                              }
                            }
                          } else {
                            WorkflowError("pending tool_call").leftf
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

class AiAgentNode(val json: JsObject) extends Node {

  def from(json: JsObject): Node = {
    val kind = json.select("kind").asOpt[String].getOrElse("--").toLowerCase()
    Node.nodes.get(kind) match {
      case None       => NoopNode(json)
      case Some(node) => node(json)
    }
  }

  override def subNodes: Seq[NodeLike]                    =
    json.select("inline_tools").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map(v => from(v.select("node").asObject))

  override def documentationName: String                  = "extensions.com.cloud-apim.llm-extension.ai_agent"
  override def documentationDisplayName: String           = "AI Agent (node)"
  override def documentationIcon: String                  = "fas fa-robot"
  override def documentationDescription: String           = "This node acts like an LLM Agen"
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
      "handoffs" -> Json.obj("type" -> "array", "description" -> "List of handoff objects", "properties" -> Json.obj(
        "agent" -> Json.obj("type" -> "object", "description" -> "an agent config"),
        "enabled" -> Json.obj("type" -> "boolean", "description" -> "is handoff enabled"),
        "tool_name_override" -> Json.obj("type" -> "string", "description" -> "tool name override"),
        "tool_description_override" -> Json.obj("type" -> "string", "description" -> "tool description"),
      )),
    )
  ))
  override def documentationFormSchema: Option[JsObject] = Some(Json.obj(
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
  override def run(
                    wfr: WorkflowRun,
                    prefix: Seq[Int],
                    from: Seq[Int]
                  )(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    if (from.nonEmpty) {
      WorkflowError(
        s"AI Agent Node (${prefix.mkString(".")}) does not support resume: ${from.mkString(".")}",
        None,
        None
      ).leftf
    } else {
      val agent = AgentConfig.from(json)
      val rcfg = AgentRunConfig.from(json.select("run_config").asOpt[JsObject].getOrElse(Json.obj()))
      val input: AgentInput = json.select("input")
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
}