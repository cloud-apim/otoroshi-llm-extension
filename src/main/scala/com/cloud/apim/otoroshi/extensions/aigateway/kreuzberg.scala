package com.cloud.apim.otoroshi.extensions.aigateway

import dev.kreuzberg.Kreuzberg
import dev.kreuzberg.config.{ExtractionConfig, OcrConfig}
import otoroshi.env.Env

import java.nio.file.Path
import scala.concurrent.{ExecutionContext, Future, blocking}

object KreuzbergHelper {

  private lazy val defaultConfig: ExtractionConfig =
    ExtractionConfig.builder()
      .outputFormat("markdown")
      .ocr(OcrConfig.builder().backend("tesseract").build())
      .forceOcr(false)
      .useCache(false)
      .build()

  def extractFromBytes(bytes: Array[Byte], mimeType: String)(implicit ec: ExecutionContext): Future[String] = {
    Future {
      blocking {
        val result = Kreuzberg.extractBytes(bytes, mimeType, defaultConfig)
        result.getContent
      }
    }
  }

  def extractFromFile(path: Path)(implicit ec: ExecutionContext): Future[String] = {
    Future {
      blocking {
        val result = Kreuzberg.extractFile(path, defaultConfig)
        result.getContent
      }
    }
  }

  def extractFromUrl(url: String, method: String = "GET", headers: Map[String, String] = Map.empty)(implicit env: Env, ec: ExecutionContext): Future[(String, String)] = {
    val req = env.Ws
      .url(url)
      .withFollowRedirects(true)
      .withRequestTimeout(scala.concurrent.duration.Duration(30000L, "millis"))
      .withHttpHeaders(headers.toSeq: _*)
    req.execute(method).flatMap { resp =>
      val contentType = resp.header("Content-Type").getOrElse("application/octet-stream")
      val mimeType = contentType.split(";").head.trim
      extractFromBytes(resp.bodyAsBytes.toArray, mimeType).map(md => (md, mimeType))
    }
  }
}
