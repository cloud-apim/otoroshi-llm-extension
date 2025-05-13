package com.cloud.apim.otoroshi.extensions.aigateway

import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.cloud.apim.otoroshi.extensions.aigateway.decorators.{CostsOutput, ImpactsOutput}
import otoroshi.env.Env
import otoroshi.security.IdGenerator
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json._
import play.api.libs.typedmap.TypedKey

import java.nio.ByteOrder
import java.util.Base64
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

trait ChatOptions {
  def temperature: Float
  def topP: Float
  def topK: Int
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

  def fromJson(json: JsObject): ChatMessageContent = {
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
      case _ => TextContent(json.select("text").asString)
    }
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
  def input(role: String, content: String, prefix: Option[Boolean], raw: JsObject): InputChatMessage = {
    InputChatMessage(role, Seq(ChatMessageContent.TextContent(content)), prefix, None, raw)
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

  def json(flavor: ChatMessageContentFlavor): JsValue = Json.obj(
    "role" -> role,
  ).applyOnIf(isSingleTextContent) { obj =>
    val text: String = singleTextContentUnsafe
    obj ++ Json.obj("content" -> text)
  }.applyOnIf(!isSingleTextContent) { obj =>
    val arr: JsArray = JsArray(contentParts.map(_.json(flavor)))
    obj ++ Json.obj("content" -> arr)
  }.applyOnWithOpt(prefix) {
    case (obj, prefix) => obj ++ Json.obj("prefix" -> prefix)
  }.applyOnWithOpt(name) {
    case (obj, name) => obj ++ Json.obj("name" -> name)
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
    .orElse(raw.at("message.citations").asOpt[Seq[JsObject]])
    .orElse(raw.at("content.citations").asOpt[Seq[JsObject]])
  lazy val hasCitations = citations.isDefined

  lazy val reasoningDetails = raw.select("reasoning_content").asOpt[String]
    .orElse(raw.select("reasoning").asOpt[String])
    .orElse(raw.at("message.reasoning_content").asOpt[String])
    .orElse(raw.at("message.reasoning").asOpt[String])
    .orElse(raw.at("content.reasoning_content").asOpt[String])
    .orElse(raw.at("content.reasoning").asOpt[String])
  lazy val hasReasoningDetails = reasoningDetails.isDefined

  lazy val annotations = raw.select("message").select("annotations").asOpt[Seq[JsObject]]

  def json: JsValue = Json.obj(
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
  def openaiJson(idx: Int): JsValue = Json.obj(
    "index" -> idx,
    "message" -> message.json,
    "logprobs" -> JsNull,
    "finish_reason" -> "stop",
  )
  def openaiCompletionJson(idx: Int): JsValue = Json.obj(
    "index" -> idx,
    "text" -> message.content,
    "logprobs" -> JsNull,
    "finish_reason" -> "stop",
  )
}
case class ChatResponse(
  generations: Seq[ChatGeneration],
  metadata: ChatResponseMetadata,
) {
  def json(env: Env): JsValue = Json.obj(
    "generations" -> JsArray(generations.map(_.json)),
    "metadata" -> metadata.json(env),
  )
  def openaiJson(model: String, env: Env): JsValue = Json.obj(
    "id" -> s"chatcmpl-${IdGenerator.token(32)}",
    "object" -> "chat.completion",
    "created" -> (System.currentTimeMillis() / 1000).toLong,
    "model" -> model,
    "system_fingerprint" -> s"fp-${IdGenerator.token(32)}",
    "choices" -> JsArray(generations.zipWithIndex.map(t => t._1.openaiJson(t._2))),
    "usage" -> metadata.usage.openaiJson,
  ).applyOnWithOpt(metadata.impacts) {
    case (o, impacts) => o ++ Json.obj("impacts" -> impacts.json(env.adminExtensions.extension[AiExtension].get.llmImpactsSettings.embedDescriptionInJson))
  }.applyOnWithOpt(metadata.costs) {
    case (o, costs) => o ++ Json.obj("costs" -> costs.json)
  }
  def openaiCompletionJson(model: String, echo: Boolean, prompt: String, env: Env): JsValue = Json.obj(
    "id" -> s"cmpl-${IdGenerator.token(32)}",
    "object" -> "text_completion",
    "created" -> (System.currentTimeMillis() / 1000).toLong,
    "model" -> model,
    "system_fingerprint" -> s"fp_${IdGenerator.token(32)}",
    "choices" -> JsArray(generations.zipWithIndex.map(t => t._1.openaiCompletionJson(t._2))),
    "usage" -> metadata.usage.openaiJson,
  ).applyOnWithOpt(metadata.impacts) {
    case (o, impacts) => o ++ Json.obj("impacts" -> impacts.json(env.adminExtensions.extension[AiExtension].get.llmImpactsSettings.embedDescriptionInJson))
  }.applyOnWithOpt(metadata.costs) {
    case (o, costs) => o ++ Json.obj("costs" -> costs.json)
  }
  def toSource(model: String): Source[ChatResponseChunk, _] = {
    val id = s"chatgen-${IdGenerator.token(32)}"
    Source(generations.toList)
      .flatMapConcat { gen =>
        gen.message.content.chunks(5)
      }
      .map { chunk =>
        ChatResponseChunk(id, System.currentTimeMillis() / 1000, model, Seq(ChatResponseChunkChoice(0, ChatResponseChunkChoiceDelta(chunk.some), None)))
      }
      .concat(Source.single(
        ChatResponseChunk(id, System.currentTimeMillis() / 1000, model, Seq(ChatResponseChunkChoice(0, ChatResponseChunkChoiceDelta(None), Some("stop"))))
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

case class ChatResponseMetadata(rateLimit: ChatResponseMetadataRateLimit, usage: ChatResponseMetadataUsage, cache: Option[ChatResponseCache], impacts: Option[ImpactsOutput] = None, costs: Option[CostsOutput] = None) {
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
  }
}

object ChatResponseMetadata {
  val empty: ChatResponseMetadata = ChatResponseMetadata(
    ChatResponseMetadataRateLimit.empty,
    ChatResponseMetadataUsage.empty,
    None,
    None
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
}

case class ChatResponseChunkChoiceDelta(content: Option[String]) {
  def json: JsValue = content match {
    case None => Json.obj()
    case Some(content) => Json.obj("content" -> content)
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
}

case class ChatResponseChunk(id: String, created: Long, model: String, choices: Seq[ChatResponseChunkChoice], costs: Option[CostsOutput] = None, impacts: Option[ImpactsOutput] = None, usage: Option[ChatResponseMetadataUsage] = None) {
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
  }
  def eventSource(env: Env): ByteString = s"data: ${json(env).stringify}\n\n".byteString
  def openaiEventSource(env: Env): ByteString = s"data: ${openaiJson(env).stringify}\n\n".byteString
  def openaiCompletionEventSource(env: Env): ByteString = s"data: ${openaiCompletionJson(env).stringify}\n\n".byteString
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

trait EmbeddingModelClient {
  def embed(input: Seq[String], model: Option[String])(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, EmbeddingResponse]]
}

case class EmbeddingSearchMatch(score: Double, id: String, embedding: Embedding, embedded: String)
case class EmbeddingSearchResponse(matches: Seq[EmbeddingSearchMatch])

trait EmbeddingStoreClient {
  def add(id: String, input: String, embedding: Embedding)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Unit]]
  def remove(id: String)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Unit]]
  def search(embedding: Embedding, maxResults: Int, minScore: Double)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, EmbeddingSearchResponse]]
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/////////                                        Moderation Models                                       ///////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

case class ModerationResult(flagged: Boolean, categories: JsObject, categoryScored: JsObject) {
  def toOpenAiJson: JsValue = {
    Json.obj(
      "flagged" -> flagged,
      "categories" -> categories,
      "category_scores" -> categoryScored
    )
  }
}

case class ModerationResponse(
                              model: String,
                              moderationResults: Seq[ModerationResult],
                            ) {
  def toOpenAiJson: JsValue = {
    Json.obj(
      "results" -> moderationResults.map(_.toOpenAiJson),
      "model" -> model
    )
  }
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/////////                             Audio generation and transcription                                 ///////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
case class AudioTranscriptionResponse(
                                       transcribedText: String
                            ) {
  def toOpenAiJson: JsValue = {
    Json.obj(
      "text" -> transcribedText
    )
  }
}

trait ModerationModelClient {
  def moderate(promptInput: String, model: Option[String])(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ModerationResponse]]
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
        prompt = json.select("voice").asOptString,
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

trait AudioModelClient {

  def supportsTts: Boolean = true
  def supportsStt: Boolean = true
  def supportsTranslation: Boolean = false

  def speechToText(options: AudioModelClientSpeechToTextInputOptions, rawBody: JsObject)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, AudioTranscriptionResponse]]
  def textToSpeech(options: AudioModelClientTextToSpeechInputOptions, rawBody: JsObject)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, (Source[ByteString, _], String)]]
  def listVoices(raw: Boolean)(implicit ec: ExecutionContext): Future[Either[JsValue, List[AudioGenVoice]]] = Left(Json.obj("error" -> "models list not supported")).vfuture
}

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/////////                                       Images Gen                                               ///////////
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

case class ImagesGen(b64Json: Option[String], revisedPrompt: Option[String], url: Option[String]) {
  def toOpenAiJson: JsValue = {
    Json.obj(
      "b64_json" -> b64Json,
      "revised_prompt" -> revisedPrompt,
      "url" -> url,
    )
  }
}

case class ImagesGenResponseMetadata(totalTokens: Long,tokenInput: Long, tokenOutput: Long, tokenText: Long, tokenImage: Long) {
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
case class ImagesGenResponse(
                              created: Long,
                              images: Seq[ImagesGen],
                              metadata: Option[ImagesGenResponseMetadata],
                            ) {
  def toOpenAiJson: JsValue = {
    if(metadata.nonEmpty){
      Json.obj(
        "created" -> created,
        "data" -> images.map(_.toOpenAiJson),
        "usage" -> metadata.get.toOpenAiJson
      )
    }else{
      Json.obj(
        "created" -> created,
        "data" -> images.map(_.toOpenAiJson)
      )
    }
  }
}

trait ImageModelClient {
  def generate(promptInput: String, model: Option[String], size: Option[String])(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ImagesGenResponse]]
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

case class VideosGenResponseMetadata(totalTokens: Long,tokenInput: Long, tokenOutput: Long, tokenText: Long, tokenImage: Long) {
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
case class VideosGenResponse(
                              created: Long,
                              videos: Seq[VideosGen],
                              metadata: Option[VideosGenResponseMetadata],
                            ) {
  def toOpenAiJson: JsValue = {
    if(metadata.nonEmpty){
      Json.obj(
        "created" -> created,
        "data" -> videos.map(_.toOpenAiJson),
        "usage" -> metadata.get.toOpenAiJson
      )
    }else{
      Json.obj(
        "created" -> created,
        "data" -> videos.map(_.toOpenAiJson)
      )
    }
  }
}

trait VideoModelClient {
  def generate(promptInput: String, model: Option[String], size: Option[String])(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, VideosGenResponse]]
}

trait ChatClient {

  def supportsStreaming: Boolean = false
  def supportsTools: Boolean = false
  def supportsCompletion: Boolean = false

  def model: Option[String]

  def listModels(raw: Boolean)(implicit ec: ExecutionContext): Future[Either[JsValue, List[String]]] = Left(Json.obj("error" -> "models list not supported")).vfuture

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
        case Right(resp) => Right(resp.toSource(model.getOrElse("none")))
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
        case Right(resp) => Right(resp.toSource(model.getOrElse("none")))
      }
    }
  }
}

object ChatClient {
  val ApiUsageKey = TypedKey[ChatResponseMetadata]("otoroshi-extensions.cloud-apim.ai.llm.ApiUsage")
}
