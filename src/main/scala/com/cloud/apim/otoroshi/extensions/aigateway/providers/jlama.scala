package com.cloud.apim.otoroshi.extensions.aigateway.providers

import akka.stream.scaladsl.Source
import com.cloud.apim.otoroshi.extensions.aigateway._
import io.azam.ulidj.ULID
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json.{JsObject, JsValue, Json}
import reactor.core.publisher.Sinks

import java.io.File
import java.lang.management.ManagementFactory
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BiConsumer
import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}

case class JlamaChatClientOptions(raw: JsObject) {
  lazy val model: String = raw.select("model").asString
  lazy val temperature: Float = raw.select("temperature").asOpt[Float].getOrElse(1.0f)
  lazy val max_completion_tokens: Option[Int] = raw.select("max_completion_tokens").asOptInt
  lazy val kind: String = raw.select("kind").asOptString.getOrElse("file")
  lazy val filePath: String = raw.select("file_path").asOptString.getOrElse("./jlama-models")
}

object JlamaChatClientOptions {
  def fromJson(raw: JsObject): JlamaChatClientOptions = JlamaChatClientOptions(raw)
}

class JlamaChatClient(options: JlamaChatClientOptions, id: String) extends ChatClient {

  override def supportsTools: Boolean = false
  override def supportsStreaming: Boolean = true
  override def supportsCompletion: Boolean = true
  override def model: Option[String] = options.model.some

  lazy val canExecuteJlama: Boolean = JlamaChatClient.canExecuteJlama
  lazy val errorMsg: String = JlamaChatClient.errorMsg

  override def listModels(raw: Boolean)(implicit ec: ExecutionContext): Future[Either[JsValue, List[String]]] = {
    if (canExecuteJlama) {
      JlamaChatClient.models.keys.toList.rightf
    } else {
      Left(Json.obj("error" -> errorMsg)).vfuture
    }
  }

  private def computeOptions(originalBody: JsValue): JlamaChatClientOptions = {
    val temp: Float = originalBody.select("temperature").asOpt[Float].getOrElse(options.temperature)
    val max: Option[Int] = originalBody.select("max_completion_tokens").asOpt[Int].orElse(originalBody.select("max_tokens").asOpt[Int]).orElse(options.max_completion_tokens)
    val opts = options.raw.asObject ++ Json.obj(
      "temperature" -> temp,
      "max_completion_tokens" -> max,
    )
    JlamaChatClientOptions(opts)
  }

  override def call(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    if (canExecuteJlama) {
      JlamaChatClient.generate(prompt, attrs, computeOptions(originalBody), id)
    } else {
      Left(Json.obj("error" -> errorMsg)).vfuture
    }
  }

  override def stream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    if (canExecuteJlama) {
      JlamaChatClient.stream(prompt, attrs, computeOptions(originalBody), id).future
    } else {
      Left(Json.obj("error" -> errorMsg)).vfuture
    }
  }

  override def completion(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    if (canExecuteJlama) {
      JlamaChatClient.generate(prompt, attrs, computeOptions(originalBody), id)
    } else {
      Left(Json.obj("error" -> errorMsg)).vfuture
    }
  }

  override def completionStream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    if (canExecuteJlama) {
      JlamaChatClient.stream(prompt, attrs, computeOptions(originalBody), id).future
    } else {
      Left(Json.obj("error" -> errorMsg)).vfuture
    }
  }
}

object JlamaChatClient {

  lazy val models = new TrieMap[String, Any]()
  lazy val ecccc = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(10))
  lazy val errorMsg: String = "JLama cannot run in this environment. Make sure you're using JDK above version 20 with --enable-preview and --add-modules=jdk.incubator.vector"

  private val canExecute = new AtomicBoolean(false)
  private val computed = new AtomicBoolean(false)

  def canExecuteJlama: Boolean = {
    if (!computed.get()) {
      computeCanExecuteJlama(None)
    }
    canExecute.get()
  }

  def computeCanExecuteJlama(logger: Option[Logger]): Unit = {
    val javaVersion = Runtime.version().feature()
    val inputArgs = ManagementFactory.getRuntimeMXBean().getInputArguments().toArray().toList.map(_.toString)
    val isAfterJava20 = javaVersion > 20
    val previewEnabled = inputArgs.contains("--enable-preview")
    val hasVector = inputArgs.exists(a => a.contains("add-modules") && a.contains("jdk.incubator.vector"))
    // Json.obj(
    //   "version" -> javaVersion,
    //   "args" -> inputArgs,
    //   "isAfterJava20" -> isAfterJava20,
    //   "previewEnabled" -> previewEnabled,
    //   "hasVector" -> hasVector,
    //   "res" -> (isAfterJava20 && previewEnabled && hasVector)
    // ).prettify.debugPrintln
    canExecute.set(isAfterJava20 && previewEnabled && hasVector)
    computed.compareAndSet(false, true)
    if (!canExecute.get()) {
      logger.foreach(_.warn(errorMsg))
    }
  }

  private def getModelAndContext(prompt: ChatPrompt, attrs: TypedMap, options: JlamaChatClientOptions): (Any, Any) = {

    import com.github.tjake.jlama.model.ModelSupport
    import com.github.tjake.jlama.safetensors.DType
    import com.github.tjake.jlama.safetensors.prompt.PromptContext
    import com.github.tjake.jlama.util.Downloader

    // try {
    //   val nativeClazz = Class.forName(
    //     "com.github.tjake.jlama.tensor.operations.NativeSimdTensorOperations"
    //   )
    //   val inst = nativeClazz.getConstructor().newInstance()
    //   println("inst = " + inst)
    // } catch {
    //   case t: Throwable => t.printStackTrace()
    // }

    val modelName = options.model
    val mod = models.get(modelName).map(_.asInstanceOf[com.github.tjake.jlama.model.AbstractModel]) match {
      case None => {
        val localModelPath = options.kind match {
          case "hf" => new Downloader(options.filePath, modelName).huggingFaceModel()
          case _ => new File(options.filePath)
        }
        val m = ModelSupport.loadModel(localModelPath, DType.F32, DType.I8)
        models.putIfAbsent(modelName, m)
        m
      }
      case Some(m) => m
    }
    val ctx = if (mod.promptSupport().isPresent()) {
      val systemMessages = prompt.messages.filter(_.isSystem)
      val userMessages = prompt.messages.filter(_.isUser)
      mod.promptSupport()
        .get()
        .builder()
        .applyOn { builder =>
          systemMessages.foreach(m => builder.addSystemMessage(m.content))
          builder
        }
        .applyOn { builder =>
          userMessages.foreach(m => builder.addUserMessage(m.content))
          builder
        }
        .build()
    } else {
      PromptContext.of(prompt.messages.map(_.wholeTextContent).mkString(". "))
    }
    (mod, ctx)
  }

  def generate(prompt: ChatPrompt, attrs: TypedMap, options: JlamaChatClientOptions, id: String): Future[Either[JsValue, ChatResponse]] = {
    Future {
      try {
        val (modAny, ctxAny) = getModelAndContext(prompt, attrs, options)
        val mod = modAny.asInstanceOf[com.github.tjake.jlama.model.AbstractModel]
        val ctx = ctxAny.asInstanceOf[com.github.tjake.jlama.safetensors.prompt.PromptContext]
        val response = mod.generate(UUID.randomUUID(), ctx, options.temperature, options.max_completion_tokens.getOrElse(-1))
        val usage = ChatResponseMetadata(
          rateLimit = ChatResponseMetadataRateLimit(
            requestsLimit = -1L,
            requestsRemaining = -1L,
            tokensLimit = -1L,
            tokensRemaining = -1L
          ),
          usage = ChatResponseMetadataUsage(
            promptTokens = response.promptTokens,
            generationTokens = response.generatedTokens,
            reasoningTokens = 0L
          ),
          cache = None
        )
        val duration: Long = response.generateTimeMs
        val slug = Json.obj(
          "provider_kind" -> "jlama",
          "provider" -> id,
          "duration" -> duration,
          "model" -> options.model.json,
          "rate_limit" -> usage.rateLimit.json,
          "usage" -> usage.usage.json
        ).applyOnWithOpt(usage.cache) {
          case (obj, cache) => obj ++ Json.obj("cache" -> cache.json)
        }
        attrs.update(ChatClient.ApiUsageKey -> usage)
        attrs.update(otoroshi.plugins.Keys.ExtraAnalyticsDataKey) {
          case Some(obj@JsObject(_)) => {
            val arr = obj.select("ai").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
            val newArr = arr ++ Seq(slug)
            obj ++ Json.obj("ai" -> newArr)
          }
          case Some(other) => other
          case None => Json.obj("ai" -> Seq(slug))
        }
        ChatResponse(
          generations = Seq(
            ChatGeneration(OutputChatMessage(
              role = "assistant",
              content = response.responseText,
              prefix = None,
              raw = Json.obj(
                "message" -> Json.obj(
                  "role" -> "assistant",
                  "content" -> response.responseText
                )
              )
            ))
          ),
          metadata = usage
        ).right
      } catch {
        case e: Throwable => Left(Json.obj("error" -> e.getMessage))
      }
    }(ecccc)
  }

  def stream(prompt: ChatPrompt, attrs: TypedMap, options: JlamaChatClientOptions, id: String): Either[JsValue, Source[ChatResponseChunk, _]] = {
    val hotSource = Sinks.many().unicast().onBackpressureBuffer[ChatResponseChunk]()
    val hotFlux   = hotSource.asFlux()
      try {
        val (modAny, ctxAny) = getModelAndContext(prompt, attrs, options)
        val mod = modAny.asInstanceOf[com.github.tjake.jlama.model.AbstractModel]
        val ctx = ctxAny.asInstanceOf[com.github.tjake.jlama.safetensors.prompt.PromptContext]
        Future {
          val response = mod.generate(UUID.randomUUID(), ctx, options.temperature, options.max_completion_tokens.getOrElse(-1), new BiConsumer[java.lang.String, java.lang.Float] {
            override def accept(t: java.lang.String, u: java.lang.Float): Unit = {
              // println(s"on: ${t} - ${u}")
              hotSource.tryEmitNext(ChatResponseChunk(
                id = ULID.random().toLowerCase(),
                created = System.currentTimeMillis(),
                model = options.model,
                choices = Seq(ChatResponseChunkChoice(
                  index = 0,
                  delta = ChatResponseChunkChoiceDelta(
                    content = Some(t)
                  ),
                  finishReason = None
                ))
              ))
            }
          })
          val usage = ChatResponseMetadata(
            rateLimit = ChatResponseMetadataRateLimit(
              requestsLimit = -1L,
              requestsRemaining = -1L,
              tokensLimit = -1L,
              tokensRemaining = -1L
            ),
            usage = ChatResponseMetadataUsage(
              promptTokens = response.promptTokens,
              generationTokens = response.generatedTokens,
              reasoningTokens = 0L
            ),
            cache = None
          )
          val duration: Long = response.generateTimeMs
          val slug = Json.obj(
            "provider_kind" -> "jlama",
            "provider" -> id,
            "duration" -> duration,
            "model" -> options.model.json,
            "rate_limit" -> usage.rateLimit.json,
            "usage" -> usage.usage.json
          ).applyOnWithOpt(usage.cache) {
            case (obj, cache) => obj ++ Json.obj("cache" -> cache.json)
          }
          attrs.update(ChatClient.ApiUsageKey -> usage)
          attrs.update(otoroshi.plugins.Keys.ExtraAnalyticsDataKey) {
            case Some(obj@JsObject(_)) => {
              val arr = obj.select("ai").asOpt[Seq[JsObject]].getOrElse(Seq.empty)
              val newArr = arr ++ Seq(slug)
              obj ++ Json.obj("ai" -> newArr)
            }
            case Some(other) => other
            case None => Json.obj("ai" -> Seq(slug))
          }
          hotSource.tryEmitComplete()
        }(ecccc)
      } catch {
        case t: Throwable => {
          hotSource.tryEmitError(t)
        }
      }

    Source.fromPublisher(hotFlux).right
  }
}