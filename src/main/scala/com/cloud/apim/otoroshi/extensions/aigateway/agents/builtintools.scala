package com.cloud.apim.otoroshi.extensions.aigateway.agents

import java.io.File
import java.nio.file.{Files, StandardOpenOption}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import otoroshi.env.Env
import otoroshi.next.workflow.WorkflowRun
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

object BuiltInToolsFactory {

  private def truncate(s: String, maxLen: Int): String = {
    if (s.length <= maxLen) s else s.take(maxLen) + s"\n... (truncated, ${s.length - maxLen} chars omitted)"
  }

  private def resolveSafePath(allowedPaths: Seq[String], requestedPath: String): Either[String, File] = {
    val file = new File(requestedPath)
    val resolved = if (file.isAbsolute) {
      file.getCanonicalFile
    } else {
      new File(allowedPaths.head, requestedPath).getCanonicalFile
    }
    val canonicalPath = resolved.getCanonicalPath
    if (allowedPaths.exists(root => canonicalPath.startsWith(new File(root).getCanonicalPath))) {
      Right(resolved)
    } else {
      Left(s"Access denied: path '${requestedPath}' is outside allowed directories")
    }
  }

  def createBuiltInTools(
    config: AgentBuiltInTools,
    scratchpadRef: AtomicReference[AgentScratchpad],
    agent: AgentConfig,
    rcfg: AgentRunConfig,
    env: Env,
    wfr: Option[WorkflowRun]
  ): Seq[InlineFunction] = {
    val tools = Seq.newBuilder[InlineFunction]
    val allowedPaths = config.allowedPaths

    // ======== Workspace tools ========

    if (config.isEnabled("list_files") && allowedPaths.nonEmpty) {
      tools += InlineFunction(
        InlineFunctionDeclaration(
          name = "list_files",
          description = "List files and directories at a given path",
          strict = false,
          parameters = Json.obj(
            "path" -> Json.obj("type" -> "string", "description" -> "Path to list (absolute or relative to workspace)"),
            "recursive" -> Json.obj("type" -> "boolean", "description" -> "List recursively (default: false)"),
            "maxEntries" -> Json.obj("type" -> "integer", "description" -> "Max entries to return (default: 300)")
          )
        ),
        call = (args, _, _, _) => {
          val json = Try(Json.parse(args)).getOrElse(Json.obj())
          val path = json.select("path").asOptString.getOrElse(".")
          val recursive = json.select("recursive").asOpt[Boolean].getOrElse(false)
          val maxEntries = json.select("maxEntries").asOpt[Int].getOrElse(300)
          resolveSafePath(allowedPaths, path) match {
            case Left(err) => Future.successful(Json.obj("error" -> err).stringify)
            case Right(dir) =>
              try {
                val entries = if (recursive) {
                  Files.walk(dir.toPath).iterator().asScala
                    .take(maxEntries + 1)
                    .filter(_ != dir.toPath)
                    .take(maxEntries)
                    .map { p =>
                      val f = p.toFile
                      Json.obj(
                        "path" -> p.toString,
                        "type" -> (if (f.isDirectory) "dir" else "file"),
                        "size" -> (if (f.isFile) f.length() else 0L)
                      )
                    }.toSeq
                } else {
                  Option(dir.listFiles()).map(_.toSeq).getOrElse(Seq.empty)
                    .take(maxEntries)
                    .map { f =>
                      Json.obj(
                        "path" -> f.getCanonicalPath,
                        "type" -> (if (f.isDirectory) "dir" else "file"),
                        "size" -> (if (f.isFile) f.length() else 0L)
                      )
                    }
                }
                Future.successful(Json.obj("root" -> dir.getCanonicalPath, "entries" -> entries).stringify)
              } catch {
                case e: Exception => Future.successful(Json.obj("error" -> e.getMessage).stringify)
              }
          }
        }
      )
    }

    if (config.isEnabled("read_file") && allowedPaths.nonEmpty) {
      tools += InlineFunction(
        InlineFunctionDeclaration(
          name = "read_file",
          description = "Read a text file content",
          strict = false,
          parameters = Json.obj(
            "path" -> Json.obj("type" -> "string", "description" -> "Path to the file"),
            "maxChars" -> Json.obj("type" -> "integer", "description" -> "Max characters to read (default: 30000)")
          ),
          required = Seq("path")
        ),
        call = (args, _, _, _) => {
          val json = Try(Json.parse(args)).getOrElse(Json.obj())
          val path = json.select("path").asOptString.getOrElse("")
          val maxChars = json.select("maxChars").asOpt[Int].getOrElse(30000)
          resolveSafePath(allowedPaths, path) match {
            case Left(err) => Future.successful(Json.obj("error" -> err).stringify)
            case Right(file) =>
              try {
                val content = new String(Files.readAllBytes(file.toPath), "UTF-8")
                Future.successful(Json.obj("path" -> file.getCanonicalPath, "content" -> truncate(content, maxChars)).stringify)
              } catch {
                case e: Exception => Future.successful(Json.obj("error" -> e.getMessage).stringify)
              }
          }
        }
      )
    }

    if (config.isEnabled("write_file") && allowedPaths.nonEmpty) {
      tools += InlineFunction(
        InlineFunctionDeclaration(
          name = "write_file",
          description = "Write content to a text file (creates parent directories if needed)",
          strict = false,
          parameters = Json.obj(
            "path" -> Json.obj("type" -> "string", "description" -> "Path to the file"),
            "content" -> Json.obj("type" -> "string", "description" -> "Content to write")
          ),
          required = Seq("path", "content")
        ),
        call = (args, _, _, _) => {
          val json = Try(Json.parse(args)).getOrElse(Json.obj())
          val path = json.select("path").asOptString.getOrElse("")
          val content = json.select("content").asOptString.getOrElse("")
          resolveSafePath(allowedPaths, path) match {
            case Left(err) => Future.successful(Json.obj("error" -> err).stringify)
            case Right(file) =>
              try {
                file.getParentFile.mkdirs()
                Files.write(file.toPath, content.getBytes("UTF-8"))
                Future.successful(Json.obj("ok" -> true, "path" -> file.getCanonicalPath).stringify)
              } catch {
                case e: Exception => Future.successful(Json.obj("error" -> e.getMessage).stringify)
              }
          }
        }
      )
    }

    if (config.isEnabled("append_file") && allowedPaths.nonEmpty) {
      tools += InlineFunction(
        InlineFunctionDeclaration(
          name = "append_file",
          description = "Append content to a file",
          strict = false,
          parameters = Json.obj(
            "path" -> Json.obj("type" -> "string", "description" -> "Path to the file"),
            "content" -> Json.obj("type" -> "string", "description" -> "Content to append")
          ),
          required = Seq("path", "content")
        ),
        call = (args, _, _, _) => {
          val json = Try(Json.parse(args)).getOrElse(Json.obj())
          val path = json.select("path").asOptString.getOrElse("")
          val content = json.select("content").asOptString.getOrElse("")
          resolveSafePath(allowedPaths, path) match {
            case Left(err) => Future.successful(Json.obj("error" -> err).stringify)
            case Right(file) =>
              try {
                file.getParentFile.mkdirs()
                Files.write(file.toPath, content.getBytes("UTF-8"), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                Future.successful(Json.obj("ok" -> true, "path" -> file.getCanonicalPath).stringify)
              } catch {
                case e: Exception => Future.successful(Json.obj("error" -> e.getMessage).stringify)
              }
          }
        }
      )
    }

    if (config.isEnabled("search_in_files") && allowedPaths.nonEmpty) {
      tools += InlineFunction(
        InlineFunctionDeclaration(
          name = "search_in_files",
          description = "Search for a text pattern across files in the workspace",
          strict = false,
          parameters = Json.obj(
            "query" -> Json.obj("type" -> "string", "description" -> "Text pattern to search for"),
            "path" -> Json.obj("type" -> "string", "description" -> "Directory to search in (default: workspace root)"),
            "maxResults" -> Json.obj("type" -> "integer", "description" -> "Max results to return (default: 100)")
          ),
          required = Seq("query")
        ),
        call = (args, _, _, _) => {
          val json = Try(Json.parse(args)).getOrElse(Json.obj())
          val query = json.select("query").asOptString.getOrElse("")
          val path = json.select("path").asOptString.getOrElse(".")
          val maxResults = json.select("maxResults").asOpt[Int].getOrElse(100)
          resolveSafePath(allowedPaths, path) match {
            case Left(err) => Future.successful(Json.obj("error" -> err).stringify)
            case Right(dir) =>
              try {
                val results = Seq.newBuilder[JsObject]
                var count = 0
                Files.walk(dir.toPath).iterator().asScala
                  .filter(p => p.toFile.isFile && count < maxResults)
                  .foreach { p =>
                    try {
                      val lines = new String(Files.readAllBytes(p), "UTF-8").split("\n")
                      lines.zipWithIndex.foreach { case (line, idx) =>
                        if (line.contains(query) && count < maxResults) {
                          results += Json.obj(
                            "path" -> p.toString,
                            "line" -> (idx + 1),
                            "snippet" -> truncate(line.trim, 500)
                          )
                          count += 1
                        }
                      }
                    } catch {
                      case _: Exception => // skip binary/unreadable files
                    }
                  }
                Future.successful(Json.obj("query" -> query, "results" -> results.result()).stringify)
              } catch {
                case e: Exception => Future.successful(Json.obj("error" -> e.getMessage).stringify)
              }
          }
        }
      )
    }

    // ======== Shell tool ========

    if (config.isEnabled("run_command") && allowedPaths.nonEmpty) {
      tools += InlineFunction(
        InlineFunctionDeclaration(
          name = "run_command",
          description = "Run a shell command in the workspace directory",
          strict = false,
          parameters = Json.obj(
            "command" -> Json.obj("type" -> "string", "description" -> "The shell command to execute"),
            "cwd" -> Json.obj("type" -> "string", "description" -> "Working directory (default: first allowed path)"),
            "timeoutMs" -> Json.obj("type" -> "integer", "description" -> s"Timeout in ms (default: ${config.commandTimeout})")
          ),
          required = Seq("command")
        ),
        call = (args, _, _, innerEc) => {
          implicit val ec: ExecutionContext = innerEc
          val json = Try(Json.parse(args)).getOrElse(Json.obj())
          val command = json.select("command").asOptString.getOrElse("")
          val cwd = json.select("cwd").asOptString.getOrElse(allowedPaths.head)
          val timeoutMs = json.select("timeoutMs").asOpt[Int].getOrElse(config.commandTimeout)
          resolveSafePath(allowedPaths, cwd) match {
            case Left(err) => Future.successful(Json.obj("error" -> err).stringify)
            case Right(workDir) =>
              try {
                val pb = new ProcessBuilder("/bin/sh", "-c", command)
                pb.directory(workDir)
                pb.redirectErrorStream(true)
                val process = pb.start()
                val outputFuture = Future {
                  scala.io.Source.fromInputStream(process.getInputStream)("UTF-8").mkString
                }
                val completed = process.waitFor(timeoutMs.toLong, TimeUnit.MILLISECONDS)
                if (!completed) {
                  process.destroyForcibly()
                  outputFuture.map { out =>
                    Json.obj("ok" -> false, "error" -> "Command timed out", "command" -> command, "output" -> truncate(out, 40000)).stringify
                  }
                } else {
                  val exitCode = process.exitValue()
                  outputFuture.map { out =>
                    Json.obj("ok" -> (exitCode == 0), "exitCode" -> exitCode, "command" -> command, "output" -> truncate(out, 40000)).stringify
                  }
                }
              } catch {
                case e: Exception => Future.successful(Json.obj("error" -> e.getMessage).stringify)
              }
          }
        }
      )
    }

    // ======== Task tools ========

    if (config.isEnabled("task_create")) {
      tools += InlineFunction(
        InlineFunctionDeclaration(
          name = "task_create",
          description = "Create a new task to track work progress",
          strict = false,
          parameters = Json.obj(
            "title" -> Json.obj("type" -> "string"),
            "description" -> Json.obj("type" -> "string"),
            "status" -> Json.obj("type" -> "string", "enum" -> Json.arr("todo", "in_progress", "done", "blocked"))
          ),
          required = Seq("title")
        ),
        call = (args, _, _, _) => {
          val json = Try(Json.parse(args)).getOrElse(Json.obj())
          val id = s"task_${java.util.UUID.randomUUID().toString.take(8)}"
          val task = ScratchpadTask(
            id = id,
            title = json.select("title").asOptString.getOrElse("Untitled"),
            description = json.select("description").asOptString.getOrElse(""),
            status = json.select("status").asOptString.getOrElse("todo")
          )
          scratchpadRef.updateAndGet(s => s.copy(tasks = s.tasks :+ task))
          Future.successful(Json.obj("ok" -> true, "task_id" -> id).stringify)
        }
      )
    }

    if (config.isEnabled("task_update")) {
      tools += InlineFunction(
        InlineFunctionDeclaration(
          name = "task_update",
          description = "Update a task's status or fields",
          strict = false,
          parameters = Json.obj(
            "id" -> Json.obj("type" -> "string"),
            "status" -> Json.obj("type" -> "string", "enum" -> Json.arr("todo", "in_progress", "done", "blocked")),
            "title" -> Json.obj("type" -> "string"),
            "description" -> Json.obj("type" -> "string")
          ),
          required = Seq("id")
        ),
        call = (args, _, _, _) => {
          val json = Try(Json.parse(args)).getOrElse(Json.obj())
          val id = json.select("id").asOptString.getOrElse("")
          val updated = scratchpadRef.updateAndGet { s =>
            s.copy(tasks = s.tasks.map { t =>
              if (t.id == id) {
                t.copy(
                  status = json.select("status").asOptString.getOrElse(t.status),
                  title = json.select("title").asOptString.getOrElse(t.title),
                  description = json.select("description").asOptString.getOrElse(t.description)
                )
              } else t
            })
          }
          val found = updated.tasks.exists(_.id == id)
          Future.successful(Json.obj("ok" -> found, "task_id" -> id).stringify)
        }
      )
    }

    if (config.isEnabled("task_get")) {
      tools += InlineFunction(
        InlineFunctionDeclaration(
          name = "task_get",
          description = "Get a task by id",
          strict = false,
          parameters = Json.obj(
            "id" -> Json.obj("type" -> "string")
          ),
          required = Seq("id")
        ),
        call = (args, _, _, _) => {
          val json = Try(Json.parse(args)).getOrElse(Json.obj())
          val id = json.select("id").asOptString.getOrElse("")
          val task = scratchpadRef.get().tasks.find(_.id == id)
          task match {
            case None => Future.successful(Json.obj("error" -> s"Task $id not found").stringify)
            case Some(t) => Future.successful(Json.obj("id" -> t.id, "title" -> t.title, "description" -> t.description, "status" -> t.status).stringify)
          }
        }
      )
    }

    if (config.isEnabled("task_list")) {
      tools += InlineFunction(
        InlineFunctionDeclaration(
          name = "task_list",
          description = "List all tasks",
          strict = false,
          parameters = Json.obj()
        ),
        call = (args, _, _, _) => {
          val tasks = scratchpadRef.get().tasks.map(t => Json.obj("id" -> t.id, "title" -> t.title, "description" -> t.description, "status" -> t.status))
          Future.successful(Json.obj("tasks" -> tasks).stringify)
        }
      )
    }

    // ======== Plan tools ========

    if (config.isEnabled("plan_set")) {
      tools += InlineFunction(
        InlineFunctionDeclaration(
          name = "plan_set",
          description = "Set or replace the current working plan",
          strict = false,
          parameters = Json.obj(
            "goal" -> Json.obj("type" -> "string"),
            "steps" -> Json.obj("type" -> "array", "items" -> Json.obj(
              "type" -> "object",
              "properties" -> Json.obj(
                "id" -> Json.obj("type" -> "string"),
                "title" -> Json.obj("type" -> "string"),
                "status" -> Json.obj("type" -> "string")
              ),
              "required" -> Json.arr("title")
            ))
          ),
          required = Seq("goal", "steps")
        ),
        call = (args, _, _, _) => {
          val json = Try(Json.parse(args)).getOrElse(Json.obj())
          val plan = ScratchpadPlan(
            goal = json.select("goal").asOptString.getOrElse(""),
            steps = json.select("steps").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map { s =>
              ScratchpadPlanStep(
                id = s.select("id").asOptString.getOrElse(java.util.UUID.randomUUID().toString.take(8)),
                title = s.select("title").asOptString.getOrElse(""),
                status = s.select("status").asOptString.getOrElse("todo")
              )
            }
          )
          scratchpadRef.updateAndGet(s => s.copy(plan = Some(plan)))
          Future.successful(Json.obj("ok" -> true).stringify)
        }
      )
    }

    if (config.isEnabled("plan_get")) {
      tools += InlineFunction(
        InlineFunctionDeclaration(
          name = "plan_get",
          description = "Get the current working plan",
          strict = false,
          parameters = Json.obj()
        ),
        call = (args, _, _, _) => {
          val plan = scratchpadRef.get().plan
          plan match {
            case None => Future.successful(Json.obj("plan" -> JsNull).stringify)
            case Some(p) => Future.successful(Json.obj(
              "goal" -> p.goal,
              "steps" -> p.steps.map(s => Json.obj("id" -> s.id, "title" -> s.title, "status" -> s.status))
            ).stringify)
          }
        }
      )
    }

    // ======== Memory tools ========

    if (config.isEnabled("memory_set")) {
      tools += InlineFunction(
        InlineFunctionDeclaration(
          name = "memory_set",
          description = "Store a key-value pair in the agent's working memory",
          strict = false,
          parameters = Json.obj(
            "key" -> Json.obj("type" -> "string"),
            "value" -> Json.obj("description" -> "Any JSON value")
          ),
          required = Seq("key", "value")
        ),
        call = (args, _, _, _) => {
          val json = Try(Json.parse(args)).getOrElse(Json.obj())
          val key = json.select("key").asOptString.getOrElse("_")
          val value = json.select("value").asOpt[JsValue].getOrElse(JsNull)
          scratchpadRef.updateAndGet(s => s.copy(notes = s.notes + (key -> value)))
          Future.successful(Json.obj("ok" -> true, "key" -> key).stringify)
        }
      )
    }

    if (config.isEnabled("memory_get")) {
      tools += InlineFunction(
        InlineFunctionDeclaration(
          name = "memory_get",
          description = "Read a value from the agent's working memory",
          strict = false,
          parameters = Json.obj(
            "key" -> Json.obj("type" -> "string")
          ),
          required = Seq("key")
        ),
        call = (args, _, _, _) => {
          val json = Try(Json.parse(args)).getOrElse(Json.obj())
          val key = json.select("key").asOptString.getOrElse("_")
          val value = scratchpadRef.get().notes.get(key)
          value match {
            case None => Future.successful(Json.obj("key" -> key, "found" -> false).stringify)
            case Some(v) => Future.successful(Json.obj("key" -> key, "found" -> true, "value" -> v).stringify)
          }
        }
      )
    }

    if (config.isEnabled("memory_list")) {
      tools += InlineFunction(
        InlineFunctionDeclaration(
          name = "memory_list",
          description = "List all working memory entries",
          strict = false,
          parameters = Json.obj()
        ),
        call = (args, _, _, _) => {
          val notes = scratchpadRef.get().notes
          Future.successful(Json.obj("entries" -> JsObject(notes.toSeq)).stringify)
        }
      )
    }

    // ======== Agent delegation tool ========

    if (config.isEnabled("delegate") && agent.handoffs.nonEmpty) {
      val availableAgents = agent.handoffs.filter(_.enabled)
      tools += InlineFunction(
        InlineFunctionDeclaration(
          name = "delegate",
          description = "Delegate a scoped subtask to a sub-agent. Available agents: " +
            availableAgents.map(h => s"'${h.agent.name}': ${h.agent.description}").mkString(", "),
          strict = false,
          parameters = Json.obj(
            "objective" -> Json.obj("type" -> "string", "description" -> "Clear description of what the sub-agent should accomplish"),
            "agent_name" -> Json.obj("type" -> "string", "description" -> "Name of the agent to delegate to")
          ),
          required = Seq("objective")
        ),
        call = (args, _, innerEnv, innerEc) => {
          implicit val implEc: ExecutionContext = innerEc
          val json = Try(Json.parse(args)).getOrElse(Json.obj())
          val objective = json.select("objective").asOptString.getOrElse("")
          val agentName = json.select("agent_name").asOptString
          val targetHandoff = agentName match {
            case Some(name) => availableAgents.find(_.agent.name.toLowerCase == name.toLowerCase)
            case None => availableAgents.headOption
          }
          targetHandoff match {
            case None => Future.successful(Json.obj("error" -> "No matching agent found").stringify)
            case Some(handoff) =>
              handoff.on_handoff.foreach(_.apply(AgentInput.from(objective)))
              new AgentRunner(innerEnv).run(
                handoff.agent,
                AgentInput.from(objective),
                rcfg.copy(
                  provider = agent.provider.orElse(rcfg.provider),
                  model = agent.model.orElse(rcfg.model),
                  modelOptions = agent.modelOptions.orElse(rcfg.modelOptions),
                ),
                TypedMap.empty,
                wfr
              ).map {
                case Left(err) => Json.obj("ok" -> false, "error" -> err).stringify
                case Right(result) => Json.obj("ok" -> true, "result" -> result).stringify
              }
          }
        }
      )
    }

    // ======== HTTP call tool ========

    if (config.isEnabled("http_call")) {
      tools += InlineFunction(
        InlineFunctionDeclaration(
          name = "http_call",
          description = "Make an HTTP request to a URL and return the response",
          strict = false,
          parameters = Json.obj(
            "url" -> Json.obj("type" -> "string", "description" -> "The URL to call"),
            "method" -> Json.obj("type" -> "string", "description" -> "HTTP method (default: GET)", "enum" -> Json.arr("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")),
            "headers" -> Json.obj("type" -> "object", "description" -> "Request headers as key-value pairs"),
            "body" -> Json.obj("description" -> "Request body (string or JSON object)"),
            "timeout" -> Json.obj("type" -> "integer", "description" -> "Timeout in ms (default: 30000)")
          ),
          required = Seq("url")
        ),
        call = (args, _, innerEnv, innerEc) => {
          implicit val implEc: ExecutionContext = innerEc
          val json = Try(Json.parse(args)).getOrElse(Json.obj())
          val url = json.select("url").asOptString.getOrElse("")
          val method = json.select("method").asOptString.getOrElse("GET").toUpperCase
          val headers = json.select("headers").asOpt[JsObject].getOrElse(Json.obj())
          val bodyOpt = json.select("body").asOpt[JsValue]
          val timeout = json.select("timeout").asOpt[Int].getOrElse(30000)

          try {
            val headerSeq = headers.fields.map { case (k, v) => (k, v.asOpt[String].getOrElse(v.toString())) }
            var req = innerEnv.Ws
              .url(url)
              .withRequestTimeout(scala.concurrent.duration.Duration(timeout.toLong, "millis"))
              .withHttpHeaders(headerSeq: _*)
              .withFollowRedirects(true)
            req = bodyOpt match {
              case Some(JsString(s)) => req.withBody(s)
              case Some(obj: JsObject) => req.addHttpHeaders("Content-Type" -> "application/json").withBody(obj)
              case Some(arr: JsArray) => req.addHttpHeaders("Content-Type" -> "application/json").withBody(arr)
              case _ => req
            }
            req.execute(method).map { resp =>
              val respHeaders = resp.headers.map { case (k, v) => k -> JsString(v.mkString(", ")) }
              Json.obj(
                "status" -> resp.status,
                "headers" -> JsObject(respHeaders.toSeq),
                "body" -> truncate(resp.body, 40000)
              ).stringify
            }.recover {
              case e: Exception => Json.obj("error" -> e.getMessage).stringify
            }
          } catch {
            case e: Exception => Future.successful(Json.obj("error" -> e.getMessage).stringify)
          }
        }
      )
    }

    // ======== Spawn agent tool ========

    if (config.isEnabled("spawn_agent")) {
      val profiles = config.subAgents
      tools += InlineFunction(
        InlineFunctionDeclaration(
          name = "spawn_agent",
          description = "Spawn a sub-agent to handle a scoped subtask autonomously. " +
            "You provide the instructions (system prompt) and objective. " +
            (if (profiles.nonEmpty) "Available agent profiles: " + profiles.map(p => s"'${p.name}': ${p.description}").mkString(", ")
             else "No profiles configured — provide instructions directly."),
          strict = false,
          parameters = Json.obj(
            "objective" -> Json.obj("type" -> "string", "description" -> "The task the sub-agent should accomplish"),
            "instructions" -> Json.obj("type" -> "string", "description" -> "System prompt / instructions for the sub-agent"),
            "agent_profile" -> Json.obj("type" -> "string", "description" -> "Name of a pre-configured agent profile (optional, provides tools/model config)")
          ),
          required = Seq("objective", "instructions")
        ),
        call = (args, _, innerEnv, innerEc) => {
          implicit val implEc: ExecutionContext = innerEc
          val json = Try(Json.parse(args)).getOrElse(Json.obj())
          val objective = json.select("objective").asOptString.getOrElse("")
          val instructions = json.select("instructions").asOptString.getOrElse("")
          val profileName = json.select("agent_profile").asOptString
          val profile = profileName.flatMap(name => profiles.find(_.name.toLowerCase == name.toLowerCase))

          // Build sub-agent config from profile (if any) + LLM-provided instructions
          val subAgentBuiltInTools = profile.flatMap(_.builtInTools).getOrElse(config.withoutSubAgents)
          val subAgent = AgentConfig(
            name = profile.map(_.name).getOrElse("sub_agent"),
            description = profile.map(_.description).getOrElse(""),
            instructions = Seq(instructions),
            provider = profile.flatMap(_.provider).orElse(agent.provider),
            model = profile.flatMap(_.model).orElse(agent.model),
            modelOptions = profile.flatMap(_.modelOptions).orElse(agent.modelOptions),
            tools = Seq.empty,
            mcpConnectors = Seq.empty,
            builtInTools = subAgentBuiltInTools,
          )
          val subRcfg = rcfg.copy(
            provider = subAgent.provider.orElse(rcfg.provider),
            model = subAgent.model.orElse(rcfg.model),
            modelOptions = subAgent.modelOptions.orElse(rcfg.modelOptions),
          )
          new AgentRunner(innerEnv).run(
            subAgent,
            AgentInput.from(objective),
            subRcfg,
            TypedMap.empty,
            wfr
          ).map {
            case Left(err) => Json.obj("ok" -> false, "error" -> err).stringify
            case Right(result) => Json.obj("ok" -> true, "agent" -> subAgent.name, "result" -> result).stringify
          }
        }
      )
    }

    // ======== Final answer tool ========

    if (config.isEnabled("final_answer")) {
      tools += InlineFunction(
        InlineFunctionDeclaration(
          name = "final_answer",
          description = "Call this when the objective is fully completed. Pass your final response.",
          strict = false,
          parameters = Json.obj(
            "answer" -> Json.obj("type" -> "string", "description" -> "The final answer/result")
          ),
          required = Seq("answer")
        ),
        call = (args, _, _, _) => {
          val json = Try(Json.parse(args)).getOrElse(Json.obj())
          val answer = json.select("answer").asOptString.getOrElse(args)
          Future.successful(Json.obj("final_answer" -> answer).stringify)
        }
      )
    }

    tools.result()
  }
}
