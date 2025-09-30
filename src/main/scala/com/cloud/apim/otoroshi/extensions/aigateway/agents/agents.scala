package com.cloud.apim.otoroshi.extensions.aigateway.agents

import com.cloud.apim.otoroshi.extensions.aigateway.decorators.Guardrails
import com.cloud.apim.otoroshi.extensions.aigateway._
import otoroshi.env.Env
import otoroshi.next.workflow.{Node, NodeLike, NoopNode, WorkflowError, WorkflowOperator, WorkflowRun}
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.{JsArray, JsNull, JsObject, JsValue, Json}

import scala.concurrent.{ExecutionContext, Future}

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
  handoffs: Seq[Handoff] = Seq.empty,
  memory: Option[String] = None,
  guardrails: Guardrails = Guardrails(Seq.empty),
) {
  def runStr(input: String, rcfg: AgentRunConfig = AgentRunConfig(), attrs: TypedMap = TypedMap.empty)(implicit env: Env):  Future[Either[JsValue, String]] = {
    run(AgentInput.from(input), rcfg, attrs)
  }
  def run(input: AgentInput, rcfg: AgentRunConfig = AgentRunConfig(), attrs: TypedMap = TypedMap.empty)(implicit env: Env):  Future[Either[JsValue, String]] = {
    new AgentRunner(env).run(this, input, rcfg, attrs)
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
  def from(json: JsValue): AgentConfig = {
    AgentConfig(
      name = json.select("name").asString,
      description = json.select("description").asOptString.getOrElse(""),
      instructions = json.select("instructions").asOpt[Seq[String]].orElse(json.select("instructions").asOptString.map(s => Seq(s))).getOrElse(Seq.empty),
      provider = json.select("provider").asOpt[String],
      model = json.select("model").asOpt[String],
      modelOptions = json.select("model_options").asOpt[JsObject],
      tools = json.select("tools").asOpt[Seq[String]].getOrElse(Seq.empty),
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

  def run(agent: AgentConfig, input: AgentInput, rcfg: AgentRunConfig = AgentRunConfig(), attrs: TypedMap = TypedMap.empty): Future[Either[JsValue, String]] = {
    internalRun(agent, input, rcfg, AgentContext(1), attrs)
  }

  private def internalRun(agent: AgentConfig, input: AgentInput, rcfg: AgentRunConfig = AgentRunConfig(), ctx: AgentContext = AgentContext(), attrs: TypedMap = TypedMap.empty): Future[Either[JsValue, String]] = {
    if (ctx.iteration > rcfg.maxTurns) {
      Json.obj("error" -> "Max turns reached").leftf
    } else {
      agent.provider.orElse(rcfg.provider) match {
        case None => Json.obj("error" -> "no provider ref").leftf
        case Some(pref) => {
          ext.states.provider(pref) match {
            case None => Json.obj("error" -> "no provider").leftf
            case Some(provider) => {
              val over = Json.obj()
                .applyOnWithOpt(agent.modelOptions.orElse(rcfg.modelOptions)) {
                  case (obj, options) => obj.deepMerge(options)
                }
                .applyOnWithOpt(agent.model.orElse(rcfg.model)) {
                  case (obj, model) => obj ++ Json.obj("model" -> model)
                }
                .applyOnIf(agent.tools.nonEmpty && agent.handoffs.isEmpty) { obj =>
                  obj ++ Json.obj("tools" -> agent.tools)
                }
              val hasHandoff = agent.handoffs.exists(_.enabled)
              val body = Json.obj()
                .applyOnIf(hasHandoff) { obj =>
                  val tools = agent.handoffs.filter(_.enabled).map(_.toFunction)
                  obj ++ Json.obj("tools" -> tools)
                }
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
                                ), ctx.copy(iteration = ctx.iteration + 1), attrs)
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

    run(triage_agent, AgentInput.from("who was the first president of the united states?"), AgentRunConfig(provider = "provider_10bbc76d-7cd8-4cb7-b760-61e749a1b691".some)).map {
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