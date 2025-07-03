package com.cloud.apim.otoroshi.extensions.aigateway

import akka.stream.scaladsl.FileIO
import akka.util.ByteString
import otoroshi.env.Env
import otoroshi.next.workflow._
import otoroshi.utils.TypedMap
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
    WorkflowFunction.registerFunction("extensions.com.cloud-apim.llm-extension.embedding_compute", new ComputeEmbeddingFunction())
    WorkflowFunction.registerFunction("extensions.com.cloud-apim.llm-extension.image_generate", new GenerateImageFunction())
    WorkflowFunction.registerFunction("extensions.com.cloud-apim.llm-extension.video_generate", new GenerateVideoFunction())
    WorkflowFunction.registerFunction("extensions.com.cloud-apim.llm-extension.tool_function_call", new CallToolFunctionFunction())
    WorkflowFunction.registerFunction("extensions.com.cloud-apim.llm-extension.mcp_function_call", new CallMcpFunctionFunction())
    WorkflowFunction.registerFunction("extensions.com.cloud-apim.llm-extension.moderation_call", new ModerationCallFunction())
    WorkflowFunction.registerFunction("extensions.com.cloud-apim.llm-extension.vector_store_add", new VectorStoreAddFunction())
    WorkflowFunction.registerFunction("extensions.com.cloud-apim.llm-extension.vector_store_remove", new VectorStoreRemoveFunction())
    WorkflowFunction.registerFunction("extensions.com.cloud-apim.llm-extension.vector_store_search", new VectorStoreSearchFunction())
    // text chunking ;)

  }
}

class VectorStoreAddFunction extends WorkflowFunction {
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
  override def call(args: JsObject)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val provider = args.select("provider").asString
    val payload = args.select("payload").asOpt[JsObject].getOrElse(Json.obj())
    val extension = env.adminExtensions.extension[AiExtension].get
    extension.states.moderationModel(provider) match {
      case None => WorkflowError(s"moderation model not found", Some(Json.obj("provider_id" -> provider)), None).leftf
      case Some(provider) => provider.getModerationModelClient() match {
        case None => WorkflowError(s"unable to instantiate client for moderation model", Some(Json.obj("provider_id" -> provider.id)), None).leftf
        case Some(client) => {
          val options = ModerationModelClientInputOptions.format.reads(payload).get
          client.moderate(options, payload).map {
            case Left(error) => WorkflowError(s"error while calling moderation model", Some(error.asOpt[JsObject].getOrElse(Json.obj("error" -> error))), None).left
            case Right(response) => response.toOpenAiJson.right
          }
        }
      }
    }
  }
}

class GenerateVideoFunction extends WorkflowFunction {
  override def call(args: JsObject)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val provider = args.select("provider").asString
    val payload = args.select("payload").asOpt[JsObject].getOrElse(Json.obj())
    val extension = env.adminExtensions.extension[AiExtension].get
    extension.states.videoModel(provider) match {
      case None => WorkflowError(s"video model not found", Some(Json.obj("provider_id" -> provider)), None).leftf
      case Some(provider) => provider.getVideoModelClient() match {
        case None => WorkflowError(s"unable to instantiate client for video model", Some(Json.obj("provider_id" -> provider.id)), None).leftf
        case Some(client) => {
          val options = VideoModelClientTextToVideoInputOptions.format.reads(payload).get
          client.generate(options, payload).map {
            case Left(error) => WorkflowError(s"error while calling video model", Some(error.asOpt[JsObject].getOrElse(Json.obj("error" -> error))), None).left
            case Right(response) => response.toOpenAiJson.right
          }
        }
      }
    }
  }
}

class CallMcpFunctionFunction extends WorkflowFunction {
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
  override def call(args: JsObject)(implicit env: Env, ec: ExecutionContext): Future[Either[WorkflowError, JsValue]] = {
    val provider = args.select("provider").asString
    val payload = args.select("payload").asOpt[JsObject].getOrElse(Json.obj())
    val extension = env.adminExtensions.extension[AiExtension].get
    extension.states.imageModel(provider) match {
      case None => WorkflowError(s"image model not found", Some(Json.obj("provider_id" -> provider)), None).leftf
      case Some(provider) => provider.getImageModelClient() match {
        case None => WorkflowError(s"unable to instantiate client for image provider", Some(Json.obj("provider_id" -> provider.id)), None).leftf
        case Some(client) => {
          val options = ImageModelClientGenerationInputOptions.format.reads(payload).get
          client.generate(options, payload).map {
            case Left(error) => WorkflowError(s"error while calling embedding model", Some(error.asOpt[JsObject].getOrElse(Json.obj("error" -> error))), None).left
            case Right(response) => response.toOpenAiJson.right
          }
        }
      }
    }
  }
}

class ComputeEmbeddingFunction extends WorkflowFunction {
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