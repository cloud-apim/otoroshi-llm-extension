package com.cloud.apim.otoroshi.extensions.aigateway.suites

import com.cloud.apim.otoroshi.extensions.aigateway.{ChatMessageContent, ChatMessageContentFlavor, InputChatMessage}
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*

// Pure unit tests for file content parts: openai `file` (chat/completions) and `input_file` (responses)
// shapes must be parsed and then serialized with the right flavor for the target provider.
class FileContentPartsSuite extends munit.FunSuite {

  val pdfBytes = "%PDF-1.4 fake pdf content".byteString
  val pdfBase64 = pdfBytes.encodeBase64.utf8String
  val textBase64 = "The grass is yellow.".byteString.encodeBase64.utf8String

  test("openai chat/completions `file` part is parsed as a pdf file content") {
    val content = ChatMessageContent.fromJson(Json.obj(
      "type" -> "file",
      "file" -> Json.obj(
        "filename" -> "releve-informations.pdf",
        "file_data" -> s"data:application/pdf;base64,${pdfBase64}",
      )
    ))
    content match {
      case pdf: ChatMessageContent.PdfFileContent =>
        assertEquals(pdf.filename, Some("releve-informations.pdf"))
        assertEquals(pdf.data.map(_.utf8String), Some(pdfBytes.utf8String))
        assert(pdf.url.isEmpty)
      case o => fail(s"expected PdfFileContent, got $o")
    }
  }

  test("openai responses `input_file` part is parsed as a pdf file content") {
    val content = ChatMessageContent.fromJson(Json.obj(
      "type" -> "input_file",
      "filename" -> "releve-informations.pdf",
      "file_data" -> s"data:application/pdf;base64,${pdfBase64}",
    ))
    content match {
      case pdf: ChatMessageContent.PdfFileContent =>
        assertEquals(pdf.filename, Some("releve-informations.pdf"))
        assertEquals(pdf.data.map(_.utf8String), Some(pdfBytes.utf8String))
      case o => fail(s"expected PdfFileContent, got $o")
    }
  }

  test("a text/plain file part is parsed as a text file content") {
    val content = ChatMessageContent.fromJson(Json.obj(
      "type" -> "file",
      "file" -> Json.obj(
        "filename" -> "notes.txt",
        "file_data" -> s"data:text/plain;base64,${textBase64}",
      )
    ))
    content match {
      case txt: ChatMessageContent.TextFileContent =>
        assertEquals(txt.filename, Some("notes.txt"))
        assertEquals(txt.data.map(_.utf8String), Some("The grass is yellow."))
      case o => fail(s"expected TextFileContent, got $o")
    }
  }

  test("media type is guessed from the filename when the payload is bare base64") {
    ChatMessageContent.fromJson(Json.obj(
      "type" -> "file",
      "file" -> Json.obj("filename" -> "notes.md", "file_data" -> textBase64)
    )) match {
      case txt: ChatMessageContent.TextFileContent => assertEquals(txt.data.map(_.utf8String), Some("The grass is yellow."))
      case o => fail(s"expected TextFileContent, got $o")
    }
    ChatMessageContent.fromJson(Json.obj(
      "type" -> "input_file",
      "filename" -> "doc.pdf",
      "file_data" -> pdfBase64,
    )) match {
      case pdf: ChatMessageContent.PdfFileContent => assertEquals(pdf.data.map(_.utf8String), Some(pdfBytes.utf8String))
      case o => fail(s"expected PdfFileContent, got $o")
    }
  }

  test("a file part referencing an url keeps the url") {
    ChatMessageContent.fromJson(Json.obj(
      "type" -> "file",
      "file" -> Json.obj("filename" -> "doc.pdf", "file_url" -> "https://foo.bar/doc.pdf")
    )) match {
      case pdf: ChatMessageContent.PdfFileContent =>
        assertEquals(pdf.url, Some("https://foo.bar/doc.pdf"))
        assert(pdf.data.isEmpty)
      case o => fail(s"expected PdfFileContent, got $o")
    }
  }

  test("a pdf file content part is serialized with the openai `file` shape") {
    val json = ChatMessageContent.PdfFileContent(None, pdfBytes.some, None, None, None, "releve-informations.pdf".some)
      .json(ChatMessageContentFlavor.OpenAi)
    assertEquals(json.select("type").asString, "file")
    assertEquals(json.at("file.filename").asString, "releve-informations.pdf")
    assertEquals(json.at("file.file_data").asString, s"data:application/pdf;base64,${pdfBase64}")
  }

  test("a text file content part is serialized with the openai `file` shape") {
    val json = ChatMessageContent.TextFileContent(None, "The grass is yellow.".byteString.some, None, None, None, "notes.txt".some)
      .json(ChatMessageContentFlavor.OpenAi)
    assertEquals(json.at("file.filename").asString, "notes.txt")
    assertEquals(json.at("file.file_data").asString, s"data:text/plain;base64,${textBase64}")
  }

  test("a file content part without filename gets a default one, urls are passed as file_url") {
    val json = ChatMessageContent.PdfFileContent("https://foo.bar/doc.pdf".some, None, None, None, None, None)
      .json(ChatMessageContentFlavor.OpenAi)
    assertEquals(json.at("file.filename").asString, "document.pdf")
    assertEquals(json.at("file.file_url").asString, "https://foo.bar/doc.pdf")
    assert(json.at("file.file_data").asOptString.isEmpty)
  }

  test("a file content part is serialized with the anthropic `document` shape") {
    val json = ChatMessageContent.PdfFileContent(None, pdfBytes.some, None, None, None, "releve-informations.pdf".some)
      .json(ChatMessageContentFlavor.Anthropic)
    assertEquals(json.select("type").asString, "document")
    assertEquals(json.at("source.type").asString, "base64")
    assertEquals(json.at("source.media_type").asString, "application/pdf")
    assertEquals(json.at("source.data").asString, pdfBase64)
    assertEquals(json.select("title").asString, "releve-informations.pdf")
  }

  test("the anthropic `document` shape is still parsed as before") {
    val content = ChatMessageContent.fromJson(Json.obj(
      "type" -> "document",
      "title" -> "My Document",
      "context" -> "This is a trustworthy document",
      "citations" -> Json.obj("enabled" -> true),
      "source" -> Json.obj(
        "type" -> "text",
        "media_type" -> "text/plain",
        "data" -> "The grass is yellow.",
      )
    ))
    content match {
      case txt: ChatMessageContent.TextFileContent =>
        assertEquals(txt.title, Some("My Document"))
        assertEquals(txt.context, Some("This is a trustworthy document"))
        assertEquals(txt.citations, Some(true))
        assertEquals(txt.data.map(_.utf8String), Some("The grass is yellow."))
      case o => fail(s"expected TextFileContent, got $o")
    }
  }

  test("a message with an input_file part is not dropped when sent to an openai provider") {
    val message = InputChatMessage.fromJson(Json.obj(
      "role" -> "user",
      "content" -> Json.arr(
        Json.obj("type" -> "input_text", "text" -> "summarize this document"),
        Json.obj("type" -> "input_file", "filename" -> "releve-informations.pdf", "file_data" -> s"data:application/pdf;base64,${pdfBase64}"),
      )
    ))
    assertEquals(message.contentParts.size, 2)
    val parts = message.json(ChatMessageContentFlavor.OpenAi).select("content").as[Seq[JsObject]]
    assertEquals(parts.size, 2)
    assertEquals(parts.last.select("type").asString, "file")
    assertEquals(parts.last.at("file.file_data").asString, s"data:application/pdf;base64,${pdfBase64}")
    // the anthropic serialization of the very same message must carry the document too
    val anthropicParts = message.json(ChatMessageContentFlavor.Anthropic).select("content").as[Seq[JsObject]]
    assertEquals(anthropicParts.last.select("type").asString, "document")
    assertEquals(anthropicParts.last.at("source.data").asString, pdfBase64)
  }

  test("an unsupported content part is still turned into an empty text part") {
    ChatMessageContent.fromJson(Json.obj("type" -> "something_else", "foo" -> "bar")) match {
      case ChatMessageContent.TextContent(text) => assertEquals(text, "")
      case o => fail(s"expected TextContent, got $o")
    }
  }
}
