package com.cloud.apim.otoroshi.extensions.aigateway

import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.decorators.{CostsOutput, ImpactsOutput}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.{AiBudget, AiBudgetConsumptions}
import diffson.DiffOps
import otoroshi.env.Env
import otoroshi.gateway.Errors.messages
import otoroshi.security.IdGenerator
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.AnthropicStreamResponseState
import play.api.libs.json._
import play.api.libs.typedmap.TypedKey
import play.api.mvc.RequestHeader

import java.nio.ByteOrder
import java.util.Base64
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

trait ChatOptions {
  //def temperature: Float
  //def topP: Float
  //def topK: Int
  def json: JsObject
  def jsonForCall: JsObject
  def allowConfigOverride: Boolean

  def optionsCleanup(options: JsObject): JsObject = {
    JsObject(options.value.filter {
      case (_, JsNull) => false
      case _ => true
    })
  }
}
sealed trait ChatMessageContentFlavor
object ChatMessageContentFlavor {
  case object Common extends ChatMessageContentFlavor
  case object OpenAi extends ChatMessageContentFlavor
  case object Anthropic extends ChatMessageContentFlavor
  case object Ollama extends ChatMessageContentFlavor
}
sealed trait ChatMessageContent { self =>
  def isSimpleText: Boolean = false
  def json(flavor: ChatMessageContentFlavor): JsValue
  def transformContent(f: String => String): ChatMessageContent = {
    self
  }
}
object ChatMessageContent {

  // fails on {"type":"tool_use","id":"call_T9ZK1A03AhQNoAw6toPo4mNz","name":"Glob","input":{"pattern":"README*","path":"/Users/mathieuancelin/projects/wines-api"}}
  def fromJson(json: JsObject): ChatMessageContent = try {
    val url = json.at("source.url").asOptString
    lazy val dataBase64 = json.at("source.data").asOptString.map(_.byteString.decodeBase64)
    lazy val dataText = json.at("source.data").asOptString.map(_.byteString)
    val mediaType = json.at("source.media_type").asOptString.getOrElse("application/octet-stream")
    json.select("type").asOpt[String] match {
      case Some("text") => TextContent(json.select("text").asString)
      case Some("document") => {
        val title = json.select("title").asOptString
        val context = json.select("context").asOptString
        val citations = json.at("citations.enabled").asOptBoolean
        json.at("source.media_type").asOptString match {
          case Some("text/plain") => TextFileContent(url, dataText, title, context, citations)
          case Some("application/pdf") => PdfFileContent(url, dataBase64, title, context, citations)
          case _ => TextFileContent(url, dataText, title, context, citations)
        }
      }
      case Some("video") => VideoContent(mediaType, url, dataBase64)
      case Some("image") => ImageContent(mediaType, url, dataBase64)
      case Some("audio") =>  AudioContent(mediaType, url, dataBase64)
      case Some("image_url") => {
        val (openaiUrl, openaiData, openaiMediaType) = json.at("image_url.url").asOpt[String] match {
          case Some(str) if str.startsWith("https://") || str.startsWith("http://") => (Some(str), None, "image/jpeg")
          case Some(str) if str.startsWith("data:") && str.contains("base64,")=> {
            val base = str.replaceFirst("data:", "")
            val media = base.split(";")(0)
            val base64 = base.split("base64,")(1).byteString.decodeBase64
            (None, base64.some, media)
          }
        }
        ImageContent(openaiMediaType, openaiUrl, openaiData)
      }
      case Some("input_audio") => {
        val data = json.at("input_audio.data").asString.byteString.decodeBase64
        val format = json.at("input_audio.format").asString match {
          case "wav" => "audio/x-wav"
          case "mp3" => "audio/mpeg3"
        }
        AudioContent(format, None, data.some)
      }
      case _ => TextContent(json.select("text").asOptString.getOrElse(""))
    }
  } catch {
    case t: Throwable =>
      t.printStackTrace()
      TextContent(s"error: ${t.getMessage}")
  }

  case class TextContent(text: String) extends ChatMessageContent {
    override def isSimpleText: Boolean = true
    override def json(flavor: ChatMessageContentFlavor): JsValue = flavor match {
      case _ => Json.obj("type" -> "text", "text" -> text)
    }
    override def transformContent(f: String => String): ChatMessageContent = {
      copy(text = f(text))
    }
  }
  case class TextFileContent(url: Option[String], data: Option[ByteString], title: Option[String], context: Option[String], citations: Option[Boolean]) extends ChatMessageContent {
    override def json(flavor: ChatMessageContentFlavor): JsValue = flavor match {
      case _ => Json.obj(
        "type" -> "document",
        "source" -> Json.obj(
          "type" -> "text",
          "media_type" -> "text/plain",
        ).applyOnWithOpt(url) {
          case (obj, url) => obj ++ Json.obj("url" -> url)
        }.applyOnWithOpt(data) {
          case (obj, data) => obj ++ Json.obj("data" -> data.utf8String)
        }
      ).applyOnWithOpt(title) {
        case (obj, title) => obj ++ Json.obj("title" -> title)
      }.applyOnWithOpt(context) {
        case (obj, context) => obj ++ Json.obj("context" -> context)
      }.applyOnWithOpt(citations) {
        case (obj, citations) => obj ++ Json.obj("citations" -> Json.obj("enabled" -> citations))
      }
    }
  }
  case class PdfFileContent(url: Option[String], data: Option[ByteString], title: Option[String], context: Option[String], citations: Option[Boolean]) extends ChatMessageContent {
    val kind: String = url match {
      case Some(_) => "url"
      case None => data match {
        case Some(_) => "base64"
        case None => "unknown"
      }
    }
    override def json(flavor: ChatMessageContentFlavor): JsValue = flavor match {
      case _ => Json.obj(
        "type" -> "document",
        "source" -> Json.obj(
          "type" -> kind,
          "media_type" -> "application/pdf",
        ).applyOnWithOpt(url) {
          case (obj, url) => obj ++ Json.obj("url" -> url)
        }.applyOnWithOpt(data) {
          case (obj, data) => obj ++ Json.obj("data" -> data.encodeBase64.utf8String)
        }
      ).applyOnWithOpt(title) {
        case (obj, title) => obj ++ Json.obj("title" -> title)
      }.applyOnWithOpt(context) {
        case (obj, context) => obj ++ Json.obj("context" -> context)
      }.applyOnWithOpt(citations) {
        case (obj, citations) => obj ++ Json.obj("citations" -> Json.obj("enabled" -> citations))
      }
    }
  }
  case class VideoContent(mediaType: String, url: Option[String], data: Option[ByteString]) extends ChatMessageContent {
    val kind: String = url match {
      case Some(_) => "url"
      case None => data match {
        case Some(_) => "base64"
        case None => "unknown"
      }
    }
    override def json(flavor: ChatMessageContentFlavor): JsValue = flavor match {
      case _ => Json.obj(
        "type" -> "video",
        "source" -> Json.obj(
          "type" -> kind,
          "media_type" -> mediaType,
        ).applyOnWithOpt(url) {
          case (obj, url) => obj ++ Json.obj("url" -> url)
        }.applyOnWithOpt(data) {
          case (obj, data) => obj ++ Json.obj("data" -> data.encodeBase64.utf8String)
        }
      )
    }
  }
  case class ImageContent(mediaType: String, url: Option[String], data: Option[ByteString]) extends ChatMessageContent {
    val kind: String = url match {
      case Some(_) => "url"
      case None => data match {
        case Some(_) => "base64"
        case None => "unknown"
      }
    }
    override def json(flavor: ChatMessageContentFlavor): JsValue = flavor match {
      case ChatMessageContentFlavor.OpenAi => {
        val computedUrl = data match {
          case Some(data) => s"data:${mediaType};base64,${data.encodeBase64.utf8String}"
          case _ => url.get
        }
        Json.obj(
          "type" -> "image_url",
          "image_url" -> Json.obj(
            "url" -> computedUrl,
          )
        )
      }
      case _ => Json.obj(
        "type" -> "image",
        "source" -> Json.obj(
          "type" -> kind,
          "media_type" -> mediaType,
        ).applyOnWithOpt(url) {
          case (obj, url) => obj ++ Json.obj("url" -> url)
        }.applyOnWithOpt(data) {
          case (obj, data) => obj ++ Json.obj("data" -> data.encodeBase64.utf8String)
        }
      )
    }
  }
  case class AudioContent(mediaType: String, url: Option[String], data: Option[ByteString]) extends ChatMessageContent {
    val kind: String = url match {
      case Some(_) => "url"
      case None => data match {
        case Some(_) => "base64"
        case None => "unknown"
      }
    }
    override def json(flavor: ChatMessageContentFlavor): JsValue = flavor match {
      case ChatMessageContentFlavor.OpenAi => Json.obj(
        "type" -> "input_audio",
        "input_audio" -> Json.obj(
          "data" -> data.get.encodeBase64.utf8String,
          "format" -> mediaType,
        )
      )
      case _ => Json.obj(
        "type" -> "audio",
        "source" -> Json.obj(
          "type" -> kind,
          "media_type" -> mediaType,
        ).applyOnWithOpt(url) {
          case (obj, url) => obj ++ Json.obj("url" -> url)
        }.applyOnWithOpt(data) {
          case (obj, data) => obj ++ Json.obj("data" -> data.encodeBase64.utf8String)
        }
      )
    }
  }
}

trait ChatMessage {
  def role: String
  def wholeTextContent: String
  // def content: String
}

object ChatMessage {
  def inputJson(raw: JsObject): InputChatMessage = {
    val role = raw.select("role").asOpt[String].getOrElse("user")
    val content = raw.select("content").asOpt[String].getOrElse("")
    val prefix = raw.select("prefix").asOpt[Boolean]
    val name = raw.select("name").asOpt[String]
    InputChatMessage(role, Seq(ChatMessageContent.TextContent(content)), prefix, name, raw)
  }
  def input(role: String, content: String, prefix: Option[Boolean], raw: JsObject): InputChatMessage = {
    InputChatMessage(role, Seq(ChatMessageContent.TextContent(content)), prefix, None, raw)
  }
  def userStrInput(content: String, raw: JsObject = Json.obj()): InputChatMessage = {
    InputChatMessage("user", Seq(ChatMessageContent.TextContent(content)), None, None, raw)
  }
  def output(role: String, content: String, prefix: Option[Boolean], raw: JsObject): OutputChatMessage = {
    OutputChatMessage(role, content, prefix, raw)
  }
}

case class ChatPrompt(messages: Seq[InputChatMessage], options: Option[ChatOptions] = None) {
  def json: JsValue = JsArray(messages.map(_.json(ChatMessageContentFlavor.Common)))
  def jsonWithFlavor(flavor: ChatMessageContentFlavor): JsValue = JsArray(messages.map(_.json(flavor)))
}
object InputChatMessage {
  def fromJson(json: JsValue): InputChatMessage = format.reads(json).get
  def fromJsonSafe(json: JsValue): Option[InputChatMessage] = format.reads(json).asOpt
  val format = new Format[InputChatMessage] {
    override def reads(json: JsValue): JsResult[InputChatMessage] = Try {
      val content: Seq[ChatMessageContent] = json.select("content").asOptString match {
        case Some(text) => Seq(ChatMessageContent.TextContent(text))
        case None => json.select("content").asOpt[Seq[JsObject]].getOrElse(Seq.empty).map { obj =>
          ChatMessageContent.fromJson(obj)
        }
      }
      InputChatMessage(
        role = json.select("role").asString,
        contentParts = content,
        prefix = json.select("prefix").asOptBoolean,
        name = json.select("name").asOptString,
        raw = json.asObject,
      )
    } match {
      case Failure(e) =>
        e.printStackTrace()
        JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }

    override def writes(o: InputChatMessage): JsValue = o.json(ChatMessageContentFlavor.Common)
  }
}
case class InputChatMessage(role: String, contentParts: Seq[ChatMessageContent], prefix: Option[Boolean], name: Option[String], raw: JsObject) extends ChatMessage {

  def isUser: Boolean = role == "user"
  def isAssistant: Boolean = role == "assistant"
  def isSystem: Boolean = role == "system"

  val tool_call_id = raw.select("tool_call_id").asOpt[String]
  val tool_calls = raw.select("tool_calls").asOpt[Seq[JsObject]]
  val hasContent = contentParts.nonEmpty

  def json(flavor: ChatMessageContentFlavor): JsValue = Json.obj(
    "role" -> role,
  ).applyOnIf(hasContent && isSingleTextContent) { obj =>
    val text: String = singleTextContentUnsafe
    obj ++ Json.obj("content" -> text)
  }.applyOnIf(hasContent && !isSingleTextContent) { obj =>
    val arr: JsArray = JsArray(contentParts.map(_.json(flavor)))
    obj ++ Json.obj("content" -> arr)
  }.applyOnWithOpt(prefix) {
    case (obj, prefix) => obj ++ Json.obj("prefix" -> prefix)
  }.applyOnWithOpt(name) {
    case (obj, name) => obj ++ Json.obj("name" -> name)
  }.applyOnWithOpt(tool_call_id) {
    case (obj, tool_call_id) => obj ++ Json.obj("tool_call_id" -> tool_call_id)
  }.applyOnWithOpt(tool_calls) {
    case (obj, tool_calls) => obj ++ Json.obj("tool_calls" -> tool_calls)
  }.applyOnIf(flavor == ChatMessageContentFlavor.Anthropic && isSystem) { obj =>
     Json.obj("type" -> "text", "text" -> wholeTextContent)
  }.applyOnIf(flavor == ChatMessageContentFlavor.Ollama && hasImage) { obj =>
    val aggContent = contentParts.collect {
      case ChatMessageContent.TextContent(text) => text
    }
    if (aggContent.isEmpty) {
      (obj - "content") ++ Json.obj("images" -> images)
    } else {
      (obj - "content") ++ Json.obj("images" -> images, "content" -> aggContent.mkString(". "))
    }
  }

  def content: String = singleTextContentUnsafe
  def images: JsArray = JsArray(contentParts.collect {
    case i: ChatMessageContent.ImageContent => i
  }.map { img =>
    img.data.get.encodeBase64.utf8String.json
  })

  lazy val wholeTextContent: String = {
    contentParts.collect {
      case p: ChatMessageContent.TextContent => p.text
    }.mkString(". ")
  }

  def transformContent(f: String => String): InputChatMessage = {
    copy(contentParts = contentParts.map(_.transformContent(f)))
  }

  def isSingleTextContent: Boolean = {
    val textParts = contentParts/*.collect {
      case t: ChatMessageContent.TextContent => t
    }*/
    if (textParts.size == 1) {
      textParts.head match {
        case ChatMessageContent.TextContent(_) => true
        case _ => false
      }
    } else {
      false
    }
  }

  def hasImage: Boolean = {
    contentParts.collect {
      case t: ChatMessageContent.ImageContent => t
    }.nonEmpty
  }

  def singleTextContentUnsafe: String = singleTextContent.getOrElse("no content")

  def singleTextContent: Option[String] = {
    if (isSingleTextContent) {
      contentParts.head match {
        case ChatMessageContent.TextContent(text) => text.some
        case _ => None
      }
    } else {
      None
    }
  }
}

object OutputChatMessage {
  val format = new Format[OutputChatMessage] {
    override def reads(json: JsValue): JsResult[OutputChatMessage] = Try {
      OutputChatMessage(
        role = json.select("role").asString,
        content = json.select("content").asString,
        prefix = json.select("prefix").asOptBoolean,
        raw = json.asOpt[JsObject].getOrElse(Json.obj())
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }

    override def writes(o: OutputChatMessage): JsValue = o.json
  }
}
case class OutputChatMessage(role: String, content: String, prefix: Option[Boolean], raw: JsObject) extends ChatMessage {

  override def wholeTextContent: String = content

  lazy val citations = raw.select("citations").asOpt[Seq[JsObject]]
    .orElse(raw.select("message").select("citations").asOpt[Seq[JsObject]])
    .orElse(raw.select("content").select("citations").asOpt[Seq[JsObject]])
  lazy val hasCitations = citations.isDefined

  lazy val reasoningDetails = raw.select("reasoning_content").asOpt[String]
    .orElse(raw.select("reasoning").asOpt[String])
    .orElse(raw.select("message").select("reasoning_content").asOpt[String])
    .orElse(raw.select("message").select("reasoning").asOpt[String])
    .orElse(raw.select("content").select("reasoning_content").asOpt[String])
    .orElse(raw.select("content").select("reasoning").asOpt[String])
  lazy val hasReasoningDetails = reasoningDetails.isDefined

  lazy val annotations = raw.select("message").select("annotations").asOpt[Seq[JsObject]]
  lazy val annotationsOrEmpty = annotations.getOrElse(Seq.empty[JsObject])
  lazy val audio = raw.select("message").select("audio").asOpt[JsObject]
  lazy val tool_calls = raw.select("message").select("tool_calls").asOpt[Seq[JsObject]]
  lazy val has_tool_calls = tool_calls.isDefined

  /// Anthropic responses
  lazy val is_server_tool_use = raw.select("type").asOpt[String].contains("server_tool_use")
  lazy val is_web_search_tool_result = raw.select("type").asOpt[String].contains("web_search_tool_result")
  /// Anthropic responses

  def json: JsValue = {
    if (is_server_tool_use || is_web_search_tool_result) {
      return raw
    }
    Json.obj(
      "role" -> role,
      "content" -> content,
    ).applyOnWithOpt(prefix) {
      case (obj, prefix) => obj ++ Json.obj("prefix" -> prefix)
    }.applyOnWithOpt(citations) {
      case (obj, citations) => obj ++ Json.obj("citations" -> citations)
    }.applyOnWithOpt(reasoningDetails) {
      case (obj, reasoningDetails) => obj ++ Json.obj("reasoning_details" -> reasoningDetails)
    }.applyOnWithOpt(annotations) {
      case (obj, annotations) => obj ++ Json.obj("annotations" -> annotations)
    }.applyOnWithOpt(audio) {
      case (obj, audio) => obj ++ Json.obj("audio" -> audio)
    }.applyOnWithOpt(tool_calls) {
      case (obj, tool_calls) => obj ++ Json.obj("tool_calls" -> tool_calls)
    }
  }

  def toInput(): InputChatMessage = InputChatMessage(role, Seq(ChatMessageContent.TextContent(content)), prefix, None, json.asObject)

  def transformContent(f: String => String): OutputChatMessage = {
    copy(content = f(content))
  }
}

case class ChatGeneration(message: OutputChatMessage) {
  def json: JsValue = Json.obj(
    "message" -> message.json
  )
  def openaiJson(idx: Int): JsValue = {
    val finish_reason = if (message.has_tool_calls) "tool_calls" else "stop"
    Json.obj(
      "index" -> idx,
      "message" -> message.json,
      "logprobs" -> JsNull,
      "finish_reason" -> finish_reason,
    )
  }
  def openaiCompletionJson(idx: Int): JsValue = {
    val finish_reason = if (message.has_tool_calls) "tool_calls" else "stop"
    Json.obj(
      "index" -> idx,
      "text" -> message.content,
      "logprobs" -> JsNull,
      "finish_reason" -> finish_reason,
    )
  }
}
case class ChatResponse(
  generations: Seq[ChatGeneration],
  metadata: ChatResponseMetadata,
  raw: JsValue,
) {
  def json(env: Env): JsValue = Json.obj(
    "generations" -> JsArray(generations.map(_.json)),
    "metadata" -> metadata.json(env),
  )
  def openaiJson(model: String, env: Env): JsValue = {
    val finalModel = raw.select("model").asOpt[String].getOrElse(model)
    Json.obj(
      "id" -> s"chatcmpl-${IdGenerator.token(32)}",
      "object" -> "chat.completion",
      "created" -> (System.currentTimeMillis() / 1000).toLong,
      "model" -> finalModel,
      "system_fingerprint" -> s"fp-${IdGenerator.token(32)}",
      "choices" -> JsArray(generations.zipWithIndex.map(t => t._1.openaiJson(t._2))),
      "usage" -> metadata.usage.openaiJson,
    ).applyOnWithOpt(metadata.impacts) {
      case (o, impacts) => o ++ Json.obj("impacts" -> impacts.json(env.adminExtensions.extension[AiExtension].get.llmImpactsSettings.embedDescriptionInJson))
    }.applyOnWithOpt(metadata.costs) {
      case (o, costs) => o ++ Json.obj("costs" -> costs.json)
    }.applyOnWithOpt(metadata.budget) {
      case (o, budget) => o ++ Json.obj("budget" -> budget._1.jsonWithRemaining(budget._2))
    }
  }
  def openaiCompletionJson(model: String, echo: Boolean, prompt: String, env: Env): JsValue = {
    val finalModel = raw.select("model").asOpt[String].getOrElse(model)
    Json.obj(
      "id" -> s"cmpl-${IdGenerator.token(32)}",
      "object" -> "text_completion",
      "created" -> (System.currentTimeMillis() / 1000).toLong,
      "model" -> finalModel,
      "system_fingerprint" -> s"fp_${IdGenerator.token(32)}",
      "choices" -> JsArray(generations.zipWithIndex.map(t => t._1.openaiCompletionJson(t._2))),
      "usage" -> metadata.usage.openaiJson,
    ).applyOnWithOpt(metadata.impacts) {
      case (o, impacts) => o ++ Json.obj("impacts" -> impacts.json(env.adminExtensions.extension[AiExtension].get.llmImpactsSettings.embedDescriptionInJson))
    }.applyOnWithOpt(metadata.costs) {
      case (o, costs) => o ++ Json.obj("costs" -> costs.json)
    }.applyOnWithOpt(metadata.budget) {
      case (o, budget) => o ++ Json.obj("budget" -> budget._1.jsonWithRemaining(budget._2))
    }
  }
  def anthropicJson(model: String, env: Env): JsValue = {
    val finalModel = raw.select("model").asOpt[String].getOrElse(model)
    val content = generations.flatMap { gen =>
      if (gen.message.has_tool_calls) {
        gen.message.tool_calls.getOrElse(Seq.empty).map(_.asObject).map { o =>
          val input: JsValue = o.select("input")
            .asOpt[JsObject]
            .orElse(o.select("function").select("arguments").asOptString.map(_.json))
            .getOrElse(Json.obj())
          val name: String = o.select("name").asOptString.orElse(o.select("function").select("name").asOptString).getOrElse("")
          Json.obj(
            "type" -> "tool_use",
            "id" -> o.select("id").asString,
            "name" -> name,
            "input" -> input,
          )
        }
      } else {
        Seq(Json.obj("type" -> "text", "text" -> gen.message.content))
      }
    }
    val stopReason = if (generations.exists(_.message.has_tool_calls)) "tool_use" else "end_turn"
    Json.obj(
      "id" -> s"msg_${IdGenerator.token(32)}",
      "type" -> "message",
      "role" -> "assistant",
      "model" -> finalModel,
      "content" -> JsArray(content),
      "stop_reason" -> stopReason,
      "stop_sequence" -> JsNull,
      "usage" -> metadata.usage.anthropicJson,
    ).applyOnWithOpt(metadata.impacts) {
      case (o, impacts) => o ++ Json.obj("impacts" -> impacts.json(env.adminExtensions.extension[AiExtension].get.llmImpactsSettings.embedDescriptionInJson))
    }.applyOnWithOpt(metadata.costs) {
      case (o, costs) => o ++ Json.obj("costs" -> costs.json)
    }.applyOnWithOpt(metadata.budget) {
      case (o, budget) => o ++ Json.obj("budget" -> budget._1.jsonWithRemaining(budget._2))
    }
  }
  def toSource(model: String): Source[ChatResponseChunk, _] = {
    val id = s"chatgen-${IdGenerator.token(32)}"
    val finalModel = raw.select("model").asOpt[String].getOrElse(model)
    Source(generations.toList)
      .flatMapConcat { gen =>
        gen.message.content.chunks(5)
      }
      .map { chunk =>
        ChatResponseChunk(id, System.currentTimeMillis() / 1000, finalModel, Seq(ChatResponseChunkChoice(0, ChatResponseChunkChoiceDelta(chunk.some, None), None)))
      }
      .concat(Source.single(
        ChatResponseChunk(id, System.currentTimeMillis() / 1000, finalModel, Seq(ChatResponseChunkChoice(0, ChatResponseChunkChoiceDelta(None, None), Some("stop"))))
      ))
  }
}
sealed trait ChatResponseCacheStatus {
  def name: String
}
object ChatResponseCacheStatus {
  case object Hit extends ChatResponseCacheStatus { def name: String = "Hit" }
  case object Miss extends ChatResponseCacheStatus { def name: String = "Miss" }
  case object Refresh extends ChatResponseCacheStatus { def name: String = "Refresh" }
  case object Bypass extends ChatResponseCacheStatus { def name: String = "Bypass" }
}
case class ChatResponseCache(status: ChatResponseCacheStatus, key: String, ttl: FiniteDuration, age: FiniteDuration) {
  def json: JsValue = Json.obj(
    "status" -> status.name, // Hit, Miss, Refresh, Bypass
    "key" -> key,
    "ttl" -> ttl.toSeconds,
    "age" -> age.toSeconds,
  )
  def toHeaders(): Map[String, String] = Map(
    "X-Cache-Status" -> status.name,
    "X-Cache-Key" -> key,
    "X-Cache-Ttl" -> ttl.toSeconds.toString,
    "Age" -> age.toSeconds.toString,
  )
}

case class ChatResponseMetadata(
  rateLimit: ChatResponseMetadataRateLimit,
  usage: ChatResponseMetadataUsage,
  cache: Option[ChatResponseCache],
  impacts: Option[ImpactsOutput] = None,
  costs: Option[CostsOutput] = None,
  budget: Option[(AiBudgetConsumptions, AiBudget)] = None,
) {
  def cacheHeaders: Map[String, String] = cache match {
    case None => Map.empty
    case Some(cache) => cache.toHeaders()
  }
  def json(env: Env): JsValue = Json.obj(
    "rate_limit" -> rateLimit.json,
    "usage" -> usage.json,
  ).applyOnWithOpt(cache) {
    case(obj, cache) => obj ++ Json.obj("cache" -> cache.json)
  }.applyOnWithOpt(impacts) {
    case(obj, impacts) => obj ++ Json.obj("impacts" -> impacts.json(env.adminExtensions.extension[AiExtension].get.llmImpactsSettings.embedDescriptionInJson))
  }.applyOnWithOpt(costs) {
    case(obj, costs) => obj ++ Json.obj("costs" -> costs.json)
  }.applyOnWithOpt(budget) {
    case(obj, budget) => obj ++ Json.obj("budget" -> budget._1.jsonWithRemaining(budget._2))
  }
}

object ChatResponseMetadata {
  val empty: ChatResponseMetadata = ChatResponseMetadata(
    ChatResponseMetadataRateLimit.empty,
    ChatResponseMetadataUsage.empty,
    None,
    None,
    None,
    None,
  )
}

object ChatResponseMetadataRateLimit {
  def empty: ChatResponseMetadataRateLimit = ChatResponseMetadataRateLimit(0L, 0L, 0L, 0L)
}

case class ChatResponseMetadataRateLimit(requestsLimit: Long, requestsRemaining: Long, tokensLimit: Long, tokensRemaining: Long) {
  def json: JsValue = Json.obj(
    "requests_limit" -> requestsLimit,
    "requests_remaining" -> requestsRemaining,
    "tokens_limit" -> tokensLimit,
    "tokens_remaining" -> tokensRemaining,
  )
}

object ChatResponseMetadataUsage {
  val empty: ChatResponseMetadataUsage = ChatResponseMetadataUsage(0L, 0L, 0L)
}

case class ChatResponseMetadataUsage(promptTokens: Long, generationTokens: Long, reasoningTokens: Long) {
  def totalTokens: Long = promptTokens + generationTokens + reasoningTokens
  def json: JsValue = Json.obj(
    "prompt_tokens" -> promptTokens,
    "generation_tokens" -> generationTokens,
    "reasoning_tokens" -> reasoningTokens,
  )
  def openaiJson: JsValue = Json.obj(
    "prompt_tokens" -> promptTokens,
    "completion_tokens" -> generationTokens,
    "total_tokens" -> (promptTokens + generationTokens),
    "completion_tokens_details" -> Json.obj(
      "reasoning_tokens" -> reasoningTokens
    )
  )
  def anthropicJson: JsValue = Json.obj(
    "input_tokens" -> promptTokens,
    "output_tokens" -> generationTokens,
  )
}

case class ChatResponseChunkChoiceDeltaToolCallFunction(nameOpt: Option[String], arguments: String) {
  def hasName: Boolean = nameOpt.isDefined
  def name: String = nameOpt.get
  def json: JsValue = Json.obj(
    "name" -> nameOpt.map(_.json).getOrElse(JsNull).asValue,
    "arguments" -> arguments,
  )
}

case class ChatResponseChunkChoiceDeltaToolCall(
  index: Long,
  id: Option[String],
  typ: Option[String],
  function: ChatResponseChunkChoiceDeltaToolCallFunction
) {
  def json: JsValue = Json.obj(
    "index" -> index,
    "id" -> id,
    "type" -> typ,
    "function" -> function.json,
  )
}

case class ChatResponseChunkChoiceDelta(
     content: Option[String],
     reasoning: Option[String] = None,
     role: String = "assistant",
     refusal: Option[String] = None,
     tool_calls: Seq[ChatResponseChunkChoiceDeltaToolCall] = Seq.empty, // TODO: fill it everywhere
) {
  def json: JsValue = Json.obj(
    "role" -> role,
  ).applyOnWithOpt(content) {
    case (o, content) => o ++ Json.obj("content" -> content)
  }.applyOnWithOpt(refusal) {
    case (o, content) => o ++ Json.obj("refusal" -> refusal)
  }.applyOnIf(tool_calls.nonEmpty) { o =>
    o ++ Json.obj("tool_calls" -> JsArray(tool_calls.map(_.json)))
  }
}

case class ChatResponseChunkChoice(index: Long, delta: ChatResponseChunkChoiceDelta, finishReason: Option[String]) {
  def json: JsValue = Json.obj(
    "index" -> index,
    "delta" -> delta.json,
    "finish_reason" -> finishReason.map(_.json).getOrElse(JsNull).asValue
  )
  def openaiJson: JsValue = Json.obj(
    "index" -> index,
    "delta" -> delta.json,
    "logprobs" -> JsNull,
    "finish_reason" -> finishReason.map(_.json).getOrElse(JsNull).asValue
  )
  def openaiCompletionJson: JsValue = Json.obj(
    "index" -> index,
    "text" -> delta.content.map(_.json).getOrElse(JsNull).asValue,
    "logprobs" -> JsNull,
    "finish_reason" -> finishReason.map(_.json).getOrElse(JsNull).asValue
  )
  def hasToolCall: Boolean = delta.tool_calls.nonEmpty
  def anthropicContentBlockDeltaJson(env: Env, state: AnthropicStreamResponseState): Source[ByteString, _] = {
    var list = Seq.empty[ByteString]
    if (delta.tool_calls.nonEmpty) {
      if (!state.textDone.get() && !state.toolCallsStarted.get()) {
        state.textDone.set(true)
        state.toolCallsStarted.set(true)
      }
      val tc = delta.tool_calls.head
      val hasName = tc.function.hasName
      if (hasName) {
        val doc = Json.obj(
          "type" -> "content_block_start",
          "index" -> index,
          "content_block" -> Json.obj(
            "type" -> "tool_use",
            "id" -> tc.id,
            "name" -> tc.function.name,
            "input" -> tc.function.arguments,
          )
        )
        list = list :+ ByteString(s"""event: content_block_stop\ndata: {"type":"content_block_stop","index":0}\n\nevent: content_block_start\ndata: ${doc.stringify}\n\n""")
      } else {
        val doc = Json.obj(
          "type" -> "content_block_delta",
          "index" -> index,
          "delta" -> Json.obj(
            "type" -> "input_json_delta",
            "partial_json" -> tc.function.arguments
          )
        )
        list = list :+ ByteString(s"event: content_block_delta\ndata: ${doc.stringify}\n\n")
      }
    } else {
      if (!state.textDone.get()) {
        if (!state.textStarted.get()) {
          state.textStarted.set(true)
        }
        val text: String = delta.content.getOrElse("")
        val doc = Json.obj(
          "type" -> "content_block_delta",
          "index" -> index,
          "delta" -> Json.obj(
            "type" -> "text_delta",
            "text" -> text
          )
        )
        list = list :+ ByteString(s"event: content_block_delta\ndata: ${doc.stringify}\n\n")
      }
    }
    Source(list.toList)
  }
}

case class ChatResponseChunk(id: String, created: Long, model: String, choices: Seq[ChatResponseChunkChoice], costs: Option[CostsOutput] = None, impacts: Option[ImpactsOutput] = None, budget: Option[(AiBudgetConsumptions, AiBudget)] = None, usage: Option[ChatResponseMetadataUsage] = None) {
  def json(env: Env): JsValue = Json.obj(
    "id" -> id,
    "created" -> created,
    "model" -> model,
    "choices" -> JsArray(choices.map(_.json))
  ).applyOnWithOpt(usage) {
    case (o, usage) => o ++ Json.obj("usage" -> usage.json)
  }.applyOnWithOpt(costs) {
    case (o, costs) => o ++ Json.obj("costs" -> costs.json)
  }.applyOnWithOpt(impacts) {
    case (o, impacts) => o ++ Json.obj("impacts" -> impacts.json(env.adminExtensions.extension[AiExtension].get.llmImpactsSettings.embedDescriptionInJson))
  }
  def openaiJson(env: Env): JsValue = Json.obj(
    "id" -> id,
    "object" -> "chat.completion.chunk",
    "created" -> created,
    "model" -> model,
    "system_fingerprint" -> JsNull,
    "choices" -> JsArray(choices.map(_.openaiJson))
  ).applyOnWithOpt(usage) {
    case (o, usage) => o ++ Json.obj("usage" -> usage.openaiJson)
  }.applyOnWithOpt(costs) {
    case (o, costs) => o ++ Json.obj("costs" -> costs.json)
  }.applyOnWithOpt(impacts) {
    case (o, impacts) => o ++ Json.obj("impacts" -> impacts.json(env.adminExtensions.extension[AiExtension].get.llmImpactsSettings.embedDescriptionInJson))
  }.applyOnWithOpt(budget) {
    case(obj, budget) => obj ++ Json.obj("budget" -> budget._1.jsonWithRemaining(budget._2))
  }
  def openaiCompletionJson(env: Env): JsValue = Json.obj(
    "id" -> id,
    "object" -> "text_completion",
    "created" -> created,
    "model" -> model,
    "system_fingerprint" -> JsNull,
    "choices" -> JsArray(choices.map(_.openaiCompletionJson))
  ).applyOnWithOpt(usage) {
    case (o, usage) => o ++ Json.obj("usage" -> usage.openaiJson)
  }.applyOnWithOpt(costs) {
    case (o, costs) => o ++ Json.obj("costs" -> costs.json)
  }.applyOnWithOpt(impacts) {
    case (o, impacts) => o ++ Json.obj("impacts" -> impacts.json(env.adminExtensions.extension[AiExtension].get.llmImpactsSettings.embedDescriptionInJson))
  }.applyOnWithOpt(budget) {
    case(obj, budget) => obj ++ Json.obj("budget" -> budget._1.jsonWithRemaining(budget._2))
  }
  def eventSource(env: Env): ByteString = s"data: ${json(env).stringify}\n\n".byteString
  def openaiEventSource(env: Env): ByteString = s"data: ${openaiJson(env).stringify}\n\n".byteString
  def openaiCompletionEventSource(env: Env): ByteString = s"data: ${openaiCompletionJson(env).stringify}\n\n".byteString
  def anthropicContentBlockDeltaEventSource(env: Env, state: AnthropicStreamResponseState): Source[ByteString, _] = {
    Source(choices.toList).flatMapConcat { choice =>
      choice.anthropicContentBlockDeltaJson(env, state)
    }
  }
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/////////                                      Embedding                                                 ///////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

case class Embedding(vector: Array[Float]) {
  def toOpenAiJson(format: String, index: Int): JsValue = {
    val vectorJson: JsValue = if (format == "base64") {
      val byteBuffer = java.nio.ByteBuffer.allocate(4 * vector.length)
      byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
      vector.foreach(float => byteBuffer.putFloat(float))
      Base64.getEncoder.encodeToString(byteBuffer.array).json
    } else {
      Json.toJson(vector)
    }
    Json.obj(
      "object" -> "embedding",
      "index" -> index,
      "embedding" -> vectorJson,
    )
  }
}
case class EmbeddingResponseMetadata(tokenUsage: Long) {
  def toOpenAiJson: JsValue = {
    Json.obj(
      "prompt_tokens" -> tokenUsage,
      "total_tokens" -> tokenUsage,
    )
  }
}
case class EmbeddingResponse(
  model: String,
  embeddings: Seq[Embedding],
  metadata: EmbeddingResponseMetadata,
) {
  def toOpenAiJson(format: String): JsValue = {
    Json.obj(
      "object" -> "list",
      "data" -> JsArray(embeddings.zipWithIndex.map(t => t._1.toOpenAiJson(format, t._2))),
      "model" -> model,
      "usage" -> metadata.toOpenAiJson
    )
  }
}

case class EmbeddingClientInputOptions(
  input: Seq[String],
  model: Option[String] = None,
  dimensions: Option[Int] = None,
  encoding_format: Option[String] = None,
  user: Option[String] = None,
) {
  def json: JsValue = EmbeddingClientInputOptions.format.writes(this)
}

object EmbeddingClientInputOptions {
  val format = new Format[EmbeddingClientInputOptions] {
    override def reads(json: JsValue): JsResult[EmbeddingClientInputOptions] = Try {
      EmbeddingClientInputOptions(
        input = json.select("input").asOpt[Seq[String]].orElse(json.select("input").asOptString.map(s => Seq(s))).getOrElse(Seq.empty),
        model = json.select("model").asOptString,
        dimensions = json.select("dimensions").asOptInt,
        encoding_format = json.select("encoding_format").asOptString,
        user = json.select("user").asOptString,
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }
    override def writes(o: EmbeddingClientInputOptions): JsValue = Json.obj(
      "input" -> o.input
    ).applyOnWithOpt(o.model) {
      case (obj, model) => obj ++ Json.obj("model" -> model)
    }.applyOnWithOpt(o.dimensions) {
      case (obj, dimensions) => obj ++ Json.obj("dimensions" -> dimensions)
    }.applyOnWithOpt(o.encoding_format) {
      case (obj, encoding_format) => obj ++ Json.obj("encoding_format" -> encoding_format)
    }.applyOnWithOpt(o.user) {
      case (obj, user) => obj ++ Json.obj("user" -> user)
    }
  }
}

trait EmbeddingModelClient {
  def embed(opts: EmbeddingClientInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, EmbeddingResponse]]
}

object EmbeddingModelClient {
  val ApiUsageKey = TypedKey[EmbeddingResponseMetadata]("otoroshi-extensions.cloud-apim.ai.llm.embedding.ApiUsage")
}

case class EmbeddingSearchMatch(score: Double, id: String, embedding: Embedding, embedded: String) {
  def json: JsValue = Json.obj(
    "score" -> score,
    "id" -> id,
    "embedded" -> embedded,
    "embedding" -> Json.obj(
      "vector" -> Json.toJson(embedding.vector)
    )
  )
}
case class EmbeddingSearchResponse(matches: Seq[EmbeddingSearchMatch]) {
  def json: JsValue = Json.obj(
    "matches" -> JsArray(matches.map(_.json))
  )
}

case class EmbeddingAddOptions(id: String, input: String, embedding: Embedding)

object EmbeddingAddOptions {
  val format = new Format[EmbeddingAddOptions] {
    override def reads(json: JsValue): JsResult[EmbeddingAddOptions] = Try {
      EmbeddingAddOptions(
        id = json.select("id").asString,
        input = json.select("input").asString,
        embedding = Embedding(json.select("embedding").select("vector").as[Array[Float]]),
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(s) => JsSuccess(s)
    }

    override def writes(o: EmbeddingAddOptions): JsValue = Json.obj(
      "id" -> o.id,
      "input" -> o.input,
      "embedding" -> Json.obj(
        "vector" -> Json.toJson(o.embedding.vector)
      ),
    )
  }
}
case class EmbeddingRemoveOptions(id: String)

object EmbeddingRemoveOptions {
  val format = new Format[EmbeddingRemoveOptions] {
    override def reads(json: JsValue): JsResult[EmbeddingRemoveOptions] = Try {
      EmbeddingRemoveOptions(
        id = json.select("id").asString,
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(s) => JsSuccess(s)
    }

    override def writes(o: EmbeddingRemoveOptions): JsValue = Json.obj(
      "id" -> o.id,
    )
  }
}
case class EmbeddingSearchOptions(embedding: Embedding, maxResults: Int, minScore: Double)

object EmbeddingSearchOptions {
  val format = new Format[EmbeddingSearchOptions] {
    override def reads(json: JsValue): JsResult[EmbeddingSearchOptions] = Try {
      EmbeddingSearchOptions(
        embedding = Embedding(json.select("embedding").select("vector").as[Array[Float]]),
        maxResults = json.select("max_results").asOpt[Int].getOrElse(10),
        minScore = json.select("min_score").asOpt[Double].getOrElse(0.9),
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(s) => JsSuccess(s)
    }

    override def writes(o: EmbeddingSearchOptions): JsValue = Json.obj(
      "max_results" -> o.maxResults,
      "min_score" -> o.minScore,
      "embedding" -> Json.obj(
        "vector" -> Json.toJson(o.embedding.vector)
      )
    )
  }
}

trait EmbeddingStoreClient {
  def add(options: EmbeddingAddOptions, raw: JsObject)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Unit]]
  def remove(options: EmbeddingRemoveOptions, raw: JsObject)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Unit]]
  def search(options: EmbeddingSearchOptions, raw: JsObject)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, EmbeddingSearchResponse]]
}

case class PersistedChatMessage(raw: JsObject) {
  lazy val role: String = raw.select("role").asOptString.getOrElse("")
  lazy val isSystem: Boolean = role == "system"
  lazy val isUser: Boolean = role == "user"
  lazy val isAssistant: Boolean = role == "assistant"
  lazy val isTool: Boolean = role == "tool" || (isAssistant && raw.select("tool_calls").isDefined)
}

object PersistedChatMessage {
  def from(raw: JsObject): PersistedChatMessage = PersistedChatMessage(raw.select("message").asOpt[JsObject].getOrElse(raw))
}

trait PersistentMemoryClient {
  def config: JsObject
  def addMessages(sessionId: String, messages: Seq[PersistedChatMessage])(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Unit]] = {
    getMessages(sessionId).flatMap {
      case Left(error) => error.leftf
      case Right(oldMessages) => {
        val newMessages = oldMessages ++ messages
        applyStrategy(sessionId, newMessages).flatMap {
          case Left(error) => error.leftf
          case Right(finalMessages) => {
            updateMessages(sessionId, finalMessages)
          }
        }
      }
    }
  }
  def updateMessages(sessionId: String, messages: Seq[PersistedChatMessage])(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Unit]]
  def getMessages(sessionId: String)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Seq[PersistedChatMessage]]]
  def clearMemory(sessionId: String)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Unit]]
  def applyStrategy(sessionId: String, messages: Seq[PersistedChatMessage])(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Seq[PersistedChatMessage]]] = {
    val options = config.select("options")
    options.select("strategy").asOptString.getOrElse("message_window") match {
      case "message_window" => {
        val maxMessages = options.select("max_messages").asOpt[Int].getOrElse(100)
        val (systemMessages, otherMessages) = messages.partition(_.isSystem)
        val filteredMessages = otherMessages.filterNot(_.isTool)
        val maxWithoutSystem = maxMessages - systemMessages.size
        val finalMessages = filteredMessages.takeRight(maxWithoutSystem)
        (systemMessages ++ finalMessages).rightf
      }
      case strat =>
        env.adminExtensions.extension[AiExtension].get.logger.warn(s"unknown memory strategy: ${strat}")
        Seq.empty[PersistedChatMessage].rightf
    }
  }
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/////////                                        Moderation Models                                       ///////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

case class ModerationResult(flagged: Boolean, categories: JsObject, categoryScored: JsObject) {
  lazy val isFlagged: Boolean = {
    if (flagged) {
      true
    } else {
      categories.value.exists {
        case (_, JsBoolean(true)) => true
        case _ => false
      }
    }
  }
  def toOpenAiJson: JsValue = {
    Json.obj(
      "flagged" -> isFlagged,
      "categories" -> categories,
      "category_scores" -> categoryScored
    )
  }
}

object ModerationResponseMetadataUsage {
  val empty: ModerationResponseMetadataUsage = ModerationResponseMetadataUsage(
    input = 0L, output = 0L, total = 0L
  )
}

case class ModerationResponseMetadataUsage(input: Long, output: Long, total: Long) {
  def toOpenAiJson: JsValue = {
    Json.obj(
      "total_tokens" -> total,
      "input_tokens" -> input,
      "output_tokens" -> output,
    )
  }
}

case class ModerationResponseMetadata(usage: ModerationResponseMetadataUsage, rateLimit: ChatResponseMetadataRateLimit, impacts: Option[ImpactsOutput] = None, costs: Option[CostsOutput] = None) {
  def toOpenAiJson(env: Env): JsObject = Json.obj(
    "usage" -> usage.toOpenAiJson,
    "rate_limit" -> rateLimit.json,
  ).applyOnWithOpt(impacts) {
    case (obj, impacts) => obj ++ Json.obj("impacts" -> impacts.json(env.adminExtensions.extension[AiExtension].get.llmImpactsSettings.embedDescriptionInJson))
  }.applyOnWithOpt(costs) {
    case (obj, costs) => obj ++ Json.obj("costs" -> costs.json)
  }
}


case class ModerationResponse(
                              model: String,
                              moderationResults: Seq[ModerationResult],
                              metadata: ModerationResponseMetadata,
                            ) {
  def toOpenAiJson(env: Env): JsValue = {
    Json.obj(
      "results" -> moderationResults.map(_.toOpenAiJson),
      "model" -> model
    )
  } ++ metadata.toOpenAiJson(env)
}


case class ModerationModelClientInputOptions(
  input: String,
  model: Option[String] = None,
) {
  def json: JsValue = ModerationModelClientInputOptions.format.writes(this)
}

object ModerationModelClientInputOptions {
  val format = new Format[ModerationModelClientInputOptions] {
    override def reads(json: JsValue): JsResult[ModerationModelClientInputOptions] = Try {
      ModerationModelClientInputOptions(
        input = json.select("input").asString,
        model = json.select("model").asOptString,
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }
    override def writes(o: ModerationModelClientInputOptions): JsValue = Json.obj(
      "input" -> o.input
    ).applyOnWithOpt(o.model) {
      case (obj, model) => obj ++ Json.obj("model" -> model)
    }
  }
}

trait ModerationModelClient {
  def moderate(opts: ModerationModelClientInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ModerationResponse]]
}

object ModerationModelClient {
  val ApiUsageKey = TypedKey[ModerationResponseMetadata]("otoroshi-extensions.cloud-apim.ai.llm.moderation.ApiUsage")
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/////////                             Audio generation and transcription                                 ///////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
case class AudioTranscriptionResponse(transcribedText: String, metadata: AudioTranscriptionResponseMetadata) {
  def toOpenAiJson(env: Env): JsValue = {
    Json.obj(
      "text" -> transcribedText,
    ) ++ metadata.toOpenAiJson(env)
  }
}

case class AudioTranscriptionResponseMetadata(usage: AudioTranscriptionResponseMetadataUsage, rateLimit: ChatResponseMetadataRateLimit, impacts: Option[ImpactsOutput] = None, costs: Option[CostsOutput] = None) {
  def toOpenAiJson(env: Env): JsObject = Json.obj(
    "usage" -> usage.toOpenAiJson,
    "rate_limit" -> rateLimit.json
  ).applyOnWithOpt(impacts) {
    case (obj, impacts) => obj ++ Json.obj("impacts" -> impacts.json(env.adminExtensions.extension[AiExtension].get.llmImpactsSettings.embedDescriptionInJson))
  }.applyOnWithOpt(costs) {
    case (obj, costs) => obj ++ Json.obj("costs" -> costs.json)
  }
}

object AudioTranscriptionResponseMetadata {
  def empty: AudioTranscriptionResponseMetadata = AudioTranscriptionResponseMetadata(
    usage = AudioTranscriptionResponseMetadataUsage.empty,
    rateLimit = ChatResponseMetadataRateLimit.empty,
    impacts = None, costs = None
  )
  def fromOpenAiResponse(raw: JsObject, headers: Map[String, String]): AudioTranscriptionResponseMetadata = {
    AudioTranscriptionResponseMetadata(
      usage = AudioTranscriptionResponseMetadataUsage(
        input = raw.select("usage").select("input_tokens").asOptLong.getOrElse(0L),
        output = raw.select("usage").select("output_tokens").asOptLong.getOrElse(0L),
        total = raw.select("usage").select("total_tokens").asOptLong.getOrElse(0L),
        input_details = raw.select("usage").select("total_tokens").asOpt[Map[String, Long]].getOrElse(Map.empty),
      ),
      rateLimit = ChatResponseMetadataRateLimit(
        requestsLimit = headers.getIgnoreCase("x-ratelimit-limit-requests").map(_.toLong).getOrElse(-1L),
        requestsRemaining = headers.getIgnoreCase("x-ratelimit-remaining-requests").map(_.toLong).getOrElse(-1L),
        tokensLimit = headers.getIgnoreCase("x-ratelimit-limit-tokens").map(_.toLong).getOrElse(-1L),
        tokensRemaining = headers.getIgnoreCase("x-ratelimit-remaining-tokens").map(_.toLong).getOrElse(-1L),
      ),
      impacts = None,
      costs = None
    )
  }
}

object AudioTranscriptionResponseMetadataUsage {
  val empty: AudioTranscriptionResponseMetadataUsage = AudioTranscriptionResponseMetadataUsage(
    input = 0L, output = 0L, total = 0L, input_details = Map.empty
  )
}

case class AudioTranscriptionResponseMetadataUsage(input: Long, output: Long, total: Long, input_details: Map[String, Long]) {
  def toOpenAiJson: JsObject = Json.obj(
    "type" -> "tokens",
    "input_tokens" -> input,
    "input_token_details" -> input_details,
    "output_tokens" -> output,
    "total_tokens" -> input,
  )
}

case class AudioGenVoice(
  voiceId: String,
  voiceName: String
){
  def toJson: JsValue = {
    Json.obj(
      "voice_id" -> voiceId,
      "voice_name" -> voiceName
    )
  }
}

case class AudioGenModel(
                          modelId: String,
                          modelName: String
                        ){
  def toJson: JsValue = {
    Json.obj(
      "model_id" -> modelId,
      "model_name" -> modelName
    )
  }
}

case class AudioModelClientTextToSpeechInputOptions(
  input: String,
  model: Option[String] = None,
  voice: Option[String] = None,
  instructions: Option[String] = None,
  responseFormat: Option[String] = None,
  speed: Option[Double] = None
) {
  def json: JsValue = AudioModelClientTextToSpeechInputOptions.format.writes(this)
}

object AudioModelClientTextToSpeechInputOptions {
  val format = new Format[AudioModelClientTextToSpeechInputOptions] {
    override def reads(json: JsValue): JsResult[AudioModelClientTextToSpeechInputOptions] = Try {
      AudioModelClientTextToSpeechInputOptions(
        input = json.select("input").asString,
        model = json.select("model").asOptString,
        voice = json.select("voice").asOptString,
        instructions = json.select("instructions").asOptString,
        responseFormat = json.select("response_format").asOptString,
        speed = json.select("speed").asOpt[Double],
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }
    override def writes(o: AudioModelClientTextToSpeechInputOptions): JsValue = Json.obj(
      "input" -> o.input
    ).applyOnWithOpt(o.model) {
      case (obj, model) => obj ++ Json.obj("model" -> model)
    }.applyOnWithOpt(o.voice) {
      case (obj, voice) => obj ++ Json.obj("voice" -> voice)
    }.applyOnWithOpt(o.instructions) {
      case (obj, instructions) => obj ++ Json.obj("instructions" -> instructions)
    }.applyOnWithOpt(o.responseFormat) {
      case (obj, responseFormat) => obj ++ Json.obj("response_format" -> responseFormat)
    }.applyOnWithOpt(o.speed) {
      case (obj, speed) => obj ++ Json.obj("speed" -> speed)
    }
  }
}

case class AudioModelClientSpeechToTextInputOptions(
  file: Source[ByteString, _],
  fileName: Option[String],
  fileContentType: String,
  fileLength: Long,
  model: Option[String] = None,
  language: Option[String] = None,
  prompt: Option[String] = None,
  responseFormat: Option[String] = None,
  temperature: Option[Double] = None
) {
  def json: JsValue = AudioModelClientSpeechToTextInputOptions.format.writes(this)
}

object AudioModelClientSpeechToTextInputOptions {
  val format = new Format[AudioModelClientSpeechToTextInputOptions] {
    override def reads(json: JsValue): JsResult[AudioModelClientSpeechToTextInputOptions] = Try {
      AudioModelClientSpeechToTextInputOptions(
        file = Source.empty,
        fileContentType = "",
        fileLength = 0L,
        fileName = None,
        model = json.select("model").asOptString,
        language = json.select("language").asOptString,
        prompt = json.select("prompt").asOptString,
        responseFormat = json.select("response_format").asOptString,
        temperature = json.select("temperature").asOpt[Double],
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }
    override def writes(o: AudioModelClientSpeechToTextInputOptions): JsValue = Json.obj()
      .applyOnWithOpt(o.language) {
        case (obj, language) => obj ++ Json.obj("language" -> language)
      }.applyOnWithOpt(o.model) {
        case (obj, model) => obj ++ Json.obj("model" -> model)
      }.applyOnWithOpt(o.prompt) {
        case (obj, prompt) => obj ++ Json.obj("prompt" -> prompt)
      }.applyOnWithOpt(o.responseFormat) {
        case (obj, responseFormat) => obj ++ Json.obj("response_format" -> responseFormat)
      }.applyOnWithOpt(o.temperature) {
        case (obj, temperature) => obj ++ Json.obj("temperature" -> temperature)
      }
  }
}


case class AudioModelClientTranslationInputOptions(
  file: Source[ByteString, _],
  fileName: Option[String],
  fileContentType: String,
  fileLength: Long,
  model: Option[String] = None,
  prompt: Option[String] = None,
  responseFormat: Option[String] = None,
  temperature: Option[Double] = None
) {
  def json: JsValue = AudioModelClientTranslationInputOptions.format.writes(this)
}

object AudioModelClientTranslationInputOptions {
  val format = new Format[AudioModelClientTranslationInputOptions] {
    override def reads(json: JsValue): JsResult[AudioModelClientTranslationInputOptions] = Try {
      AudioModelClientTranslationInputOptions(
        file = Source.empty,
        fileContentType = "",
        fileLength = 0L,
        fileName = None,
        model = json.select("model").asOptString,
        prompt = json.select("voice").asOptString,
        responseFormat = json.select("response_format").asOptString,
        temperature = json.select("temperature").asOpt[Double],
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }
    override def writes(o: AudioModelClientTranslationInputOptions): JsValue = Json.obj()
      .applyOnWithOpt(o.model) {
        case (obj, model) => obj ++ Json.obj("model" -> model)
      }.applyOnWithOpt(o.prompt) {
        case (obj, prompt) => obj ++ Json.obj("prompt" -> prompt)
      }.applyOnWithOpt(o.responseFormat) {
        case (obj, responseFormat) => obj ++ Json.obj("response_format" -> responseFormat)
      }.applyOnWithOpt(o.temperature) {
        case (obj, temperature) => obj ++ Json.obj("temperature" -> temperature)
      }
  }
}

trait AudioModelClient {

  def supportsTts: Boolean
  def supportsStt: Boolean
  def supportsTranslation: Boolean

  def translate(options: AudioModelClientTranslationInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, AudioTranscriptionResponse]] = {
    Left(Json.obj("error" -> "audio translation not supported")).vfuture
  }
  def speechToText(options: AudioModelClientSpeechToTextInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, AudioTranscriptionResponse]] = {
    Left(Json.obj("error" -> "speech to text not supported")).vfuture
  }
  def textToSpeech(options: AudioModelClientTextToSpeechInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, (Source[ByteString, _], String)]] = {
    Left(Json.obj("error" -> "text to speech not supported")).vfuture
  }
  def listModels(raw: Boolean)(implicit ec: ExecutionContext): Future[Either[JsValue, List[AudioGenModel]]] = Left(Json.obj("error" -> "models list not supported")).vfuture
  def listVoices(raw: Boolean)(implicit ec: ExecutionContext): Future[Either[JsValue, List[AudioGenVoice]]] = Left(Json.obj("error" -> "voices list not supported")).vfuture
}

object AudioModelClient {
  val ApiUsageKey = TypedKey[AudioTranscriptionResponseMetadata]("otoroshi-extensions.cloud-apim.ai.llm.audio.ApiUsage")
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/////////                                       Images Gen                                               ///////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

case class ImageModelClientGenerationInputOptions(
   prompt: String,
   background: Option[String] = None,
   model: Option[String] = None,
   moderation: Option[String] = None,
   n: Option[Int] = None,
   outputCompression: Option[Int] = None,
   outputFormat: Option[String] = None,
   responseFormat: Option[String] = None,
   quality: Option[String] = None,
   size: Option[String] = None,
   style: Option[String] = None,
) {
  def json: JsValue = ImageModelClientGenerationInputOptions.format.writes(this)
}

object ImageModelClientGenerationInputOptions {
  val format = new Format[ImageModelClientGenerationInputOptions] {
    override def reads(json: JsValue): JsResult[ImageModelClientGenerationInputOptions] = Try {
      ImageModelClientGenerationInputOptions(
        prompt = json.select("prompt").asString,
        background = json.select("background").asOptString,
        model = json.select("model").asOptString,
        moderation = json.select("moderation").asOptString,
        n = json.select("n").asOptInt,
        outputCompression = json.select("output_compression").asOptInt,
        outputFormat = json.select("output_format").asOptString,
        responseFormat = json.select("response_format").asOptString,
        quality = json.select("quality").asOptString,
        size = json.select("size").asOptString,
        style = json.select("style").asOptString,
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }
    override def writes(o: ImageModelClientGenerationInputOptions): JsValue =
      Json.obj("prompt" -> o.prompt)
        .applyOnWithOpt(o.background) { case (obj, background) => obj ++ Json.obj("background" -> background) }
        .applyOnWithOpt(o.model) { case (obj, model) => obj ++ Json.obj("model" -> model) }
        .applyOnWithOpt(o.moderation) { case (obj, moderation) => obj ++ Json.obj("moderation" -> moderation) }
        .applyOnWithOpt(o.n) { case (obj, n) => obj ++ Json.obj("n" -> n) }
        .applyOnWithOpt(o.outputCompression) { case (obj, outputCompression) => obj ++ Json.obj("output_compression" -> outputCompression) }
        .applyOnWithOpt(o.outputFormat) { case (obj, outputFormat) => obj ++ Json.obj("output_format" -> outputFormat) }
        .applyOnWithOpt(o.responseFormat) { case (obj, responseFormat) => obj ++ Json.obj("response_format" -> responseFormat) }
        .applyOnWithOpt(o.quality) { case (obj, quality) => obj ++ Json.obj("quality" -> quality) }
        .applyOnWithOpt(o.size) { case (obj, size) => obj ++ Json.obj("size" -> size) }
        .applyOnWithOpt(o.style) { case (obj, style) => obj ++ Json.obj("style" -> style) }
  }
}

case class ImageFile(bytes: Source[ByteString, _],
                     name: Option[String],
                     contentType: String,
                     length: Long)

case class ImageModelClientEditionInputOptions(
   images: List[ImageFile],
   prompt: String,
   background: Option[String] = None,
   model: Option[String] = None,
   n: Option[Int] = None,
   responseFormat: Option[String] = None,
   quality: Option[String] = None,
   size: Option[String] = None,
) {
  def json: JsValue = ImageModelClientEditionInputOptions.format.writes(this)
}

object ImageModelClientEditionInputOptions {
  val format = new Format[ImageModelClientEditionInputOptions] {
    override def reads(json: JsValue): JsResult[ImageModelClientEditionInputOptions] = Try {
      ImageModelClientEditionInputOptions(
        images = List.empty,
        prompt = json.select("prompt").asString,
        background = json.select("background").asOptString,
        model = json.select("model").asOptString,
        n = json.select("n").asOptInt,
        responseFormat = json.select("response_format").asOptString,
        quality = json.select("quality").asOptString,
        size = json.select("size").asOptString
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }
    override def writes(o: ImageModelClientEditionInputOptions): JsValue =
      Json.obj("prompt" -> o.prompt)
        .applyOnWithOpt(o.background) { case (obj, background) => obj ++ Json.obj("background" -> background) }
        .applyOnWithOpt(o.model) { case (obj, model) => obj ++ Json.obj("model" -> model) }
        .applyOnWithOpt(o.n) { case (obj, n) => obj ++ Json.obj("n" -> n) }
        .applyOnWithOpt(o.responseFormat) { case (obj, responseFormat) => obj ++ Json.obj("response_format" -> responseFormat) }
        .applyOnWithOpt(o.quality) { case (obj, quality) => obj ++ Json.obj("quality" -> quality) }
        .applyOnWithOpt(o.size) { case (obj, size) => obj ++ Json.obj("size" -> size) }
  }
}

case class ImagesGen(b64Json: Option[String], revisedPrompt: Option[String], url: Option[String]) {
  def toOpenAiJson: JsValue = {
    Json.obj(
      "b64_json" -> b64Json,
      "revised_prompt" -> revisedPrompt,
      "url" -> url,
    )
  }
}

object ImagesGenResponseMetadataUsage {
  val empty: ImagesGenResponseMetadataUsage = ImagesGenResponseMetadataUsage(
    totalTokens = 0L, tokenInput = 0L, tokenOutput = 0L, tokenText = 0L, tokenImage = 0L
  )
}

case class ImagesGenResponseMetadataUsage(totalTokens: Long,tokenInput: Long, tokenOutput: Long, tokenText: Long, tokenImage: Long) {
  def toOpenAiJson: JsValue = {
    Json.obj(
      "total_tokens" -> totalTokens,
      "input_tokens" -> tokenInput,
      "output_tokens" -> tokenOutput,
      "input_tokens_details" -> Json.obj(
        "text_tokens" -> tokenText,
        "image_tokens" -> tokenImage
      )
    )
  }
}

case class ImagesGenResponseMetadata(usage: ImagesGenResponseMetadataUsage, rateLimit: ChatResponseMetadataRateLimit, impacts: Option[ImpactsOutput] = None, costs: Option[CostsOutput] = None) {
  def toOpenAiJson(env: Env): JsValue = Json.obj(
    "usage" -> usage.toOpenAiJson,
    "rate_limit" -> rateLimit.json,
  ).applyOnWithOpt(impacts) {
    case (obj, impacts) => obj ++ Json.obj("impacts" -> impacts.json(env.adminExtensions.extension[AiExtension].get.llmImpactsSettings.embedDescriptionInJson))
  }.applyOnWithOpt(costs) {
    case (obj, costs) => obj ++ Json.obj("costs" -> costs.json)
  }
}

case class ImagesGenResponse(
                              created: Long,
                              images: Seq[ImagesGen],
                              metadata: ImagesGenResponseMetadata,
                            ) {
  def toOpenAiJson(env: Env): JsValue = Json.obj(
    "created" -> created,
    "data" -> images.map(_.toOpenAiJson),
    "usage" -> metadata.toOpenAiJson(env)
  )
}

trait ImageModelClient {
  def supportsGeneration: Boolean
  def supportsEdit: Boolean
  def generate(opts: ImageModelClientGenerationInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ImagesGenResponse]] = Json.obj("error" -> "Image generation not supported").leftf
  def edit(opts: ImageModelClientEditionInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ImagesGenResponse]] = Json.obj("error" -> "Image edition not supported").leftf
}

object ImageModelClient {
  val ApiUsageKey = TypedKey[ImagesGenResponseMetadata]("otoroshi-extensions.cloud-apim.ai.llm.image.ApiUsage")
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/////////                                       Videos Gen                                               ///////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

case class VideosGen(b64Json: Option[String], revisedPrompt: Option[String], url: Option[String]) {
  def toOpenAiJson: JsValue = {
    Json.obj(
      "b64_json" -> b64Json,
      "revised_prompt" -> revisedPrompt,
      "url" -> url,
    )
  }
}

case class VideosGenResponseMetadataUsage(totalTokens: Long,tokenInput: Long, tokenOutput: Long, tokenText: Long, tokenImage: Long) {
  def toOpenAiJson: JsValue = {
    Json.obj(
      "total_tokens" -> totalTokens,
      "input_tokens" -> tokenInput,
      "output_tokens" -> tokenOutput,
      "input_tokens_details" -> Json.obj(
        "text_tokens" -> tokenText,
        "image_tokens" -> tokenImage
      )
    )
  }
}

case class VideosGenResponseMetadata(usage: VideosGenResponseMetadataUsage, rateLimit: ChatResponseMetadataRateLimit, impacts: Option[ImpactsOutput] = None, costs: Option[CostsOutput] = None) {
  def toOpenAiJson(env: Env): JsObject = Json.obj(
    "usage" -> usage.toOpenAiJson,
    "rate_limit" -> rateLimit.json,
  ).applyOnWithOpt(impacts) {
    case (obj, impacts) => obj ++ Json.obj("impacts" -> impacts.json(env.adminExtensions.extension[AiExtension].get.llmImpactsSettings.embedDescriptionInJson))
  }.applyOnWithOpt(costs) {
    case (obj, costs) => obj ++ Json.obj("costs" -> costs.json)
  }
}

case class VideosGenResponse(
                              created: Long,
                              videos: Seq[VideosGen],
                              metadata: VideosGenResponseMetadata,
                            ) {
  def toOpenAiJson(env: Env): JsValue = {
    Json.obj(
      "created" -> created,
      "data" -> videos.map(_.toOpenAiJson),
      "usage" -> metadata.toOpenAiJson(env)
    )
  }
}


case class VideoModelClientTextToVideoInputOptions(
                                                   prompt: String,
                                                   loop: Option[Boolean] = None,
                                                   model: Option[String] = None,
                                                   aspect_ratio: Option[String] = None,
                                                   resolution: Option[String] = None,
                                                   duration: Option[String] = None,
                                                 ) {
  def json: JsValue = VideoModelClientTextToVideoInputOptions.format.writes(this)
}

object VideoModelClientTextToVideoInputOptions {
  val format = new Format[VideoModelClientTextToVideoInputOptions] {
    override def reads(json: JsValue): JsResult[VideoModelClientTextToVideoInputOptions] = Try {
      VideoModelClientTextToVideoInputOptions(
        prompt = json.select("prompt").asString,
        loop = json.select("loop").asOptBoolean,
        model = json.select("model").asOptString,
        aspect_ratio = json.select("aspect_ratio").asOptString,
        resolution = json.select("resolution").asOptString,
        duration = json.select("duration").asOptString,
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(e) => JsSuccess(e)
    }
    override def writes(o: VideoModelClientTextToVideoInputOptions): JsValue =
      Json.obj("prompt" -> o.prompt)
        .applyOnWithOpt(o.loop) { case (obj, loop) => obj ++ Json.obj("loop" -> loop) }
        .applyOnWithOpt(o.model) { case (obj, model) => obj ++ Json.obj("model" -> model) }
        .applyOnWithOpt(o.aspect_ratio) { case (obj, aspect_ratio) => obj ++ Json.obj("aspect_ratio" -> aspect_ratio) }
        .applyOnWithOpt(o.resolution) { case (obj, resolution) => obj ++ Json.obj("resolution" -> resolution) }
        .applyOnWithOpt(o.duration) { case (obj, duration) => obj ++ Json.obj("duration" -> duration) }
  }
}

trait VideoModelClient {
  def supportsTextToVideo: Boolean
  def generate(opts: VideoModelClientTextToVideoInputOptions, rawBody: JsObject, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, VideosGenResponse]]
}

object VideoModelClient {
  val ApiUsageKey = TypedKey[VideosGenResponseMetadata]("otoroshi-extensions.cloud-apim.ai.llm.video.ApiUsage")
}

trait ChatClient {

  def isAnthropic: Boolean = false
  def isCohere: Boolean = false
  def isOpenAi: Boolean = true
  def supportsStreaming: Boolean = false
  def supportsTools: Boolean = false
  def supportsCompletion: Boolean = false

  def computeModel(payload: JsValue): Option[String]

  def listModels(raw: Boolean, attrs: TypedMap)(implicit ec: ExecutionContext): Future[Either[JsValue, List[String]]] = Left(Json.obj("error" -> "models list not supported")).vfuture

  def call(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]]

  def completion(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    Left(Json.obj("error" -> "completion not supported")).vfuture
  }

  def stream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    Left(Json.obj("error" -> "streaming not supported")).future
  }

  def completionStream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    Left(Json.obj("error" -> "streaming not supported")).future
  }

  final def tryStream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    if (supportsStreaming) {
      stream(prompt, attrs, originalBody)
    } else {
      call(prompt, attrs, originalBody).map {
        case Left(err) => Left(err)
        case Right(resp) => Right(resp.toSource(computeModel(originalBody).getOrElse("none")))
      }
    }
  }

  def tryCompletion(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    if (supportsCompletion) {
      completion(prompt, attrs, originalBody)
    } else {
      val cleanBody = originalBody.asObject - "prompt" - "suffix"
      call(prompt, attrs, cleanBody)
    }
  }

  final def tryCompletionStream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    if (supportsStreaming) {
      completionStream(prompt, attrs, originalBody)
    } else {
      val cleanBody = originalBody.asObject - "prompt" - "suffix"
      completion(prompt, attrs, cleanBody).map {
        case Left(err) => Left(err)
        case Right(resp) => Right(resp.toSource(computeModel(originalBody).getOrElse("none")))
      }
    }
  }
}

object ChatClient {
  val ApiUsageKey = TypedKey[ChatResponseMetadata]("otoroshi-extensions.cloud-apim.ai.llm.ApiUsage")
}
