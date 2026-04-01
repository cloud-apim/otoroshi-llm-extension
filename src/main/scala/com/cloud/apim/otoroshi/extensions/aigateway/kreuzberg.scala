package com.cloud.apim.otoroshi.extensions.aigateway

import dev.kreuzberg.Kreuzberg
import dev.kreuzberg.config.{ExtractionConfig, OcrConfig}
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits._
import play.api.Logger

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.{ExecutionContext, Future}

object KreuzbergHelper {

  lazy val errorMsg: String = "Kreuzberg cannot run in this environment. Make sure you're using JDK 25 or above"

  private val canExecute = new AtomicBoolean(false)
  private val computed = new AtomicBoolean(false)

  def canExecuteKreuzberg: Boolean = {
    if (!computed.get()) {
      computeCanExecuteKreuzberg(None)
    }
    canExecute.get()
  }

  def computeCanExecuteKreuzberg(logger: Option[Logger]): Unit = {
    val javaVersion = Runtime.version().feature()
    val isAtLeastJava25 = javaVersion >= 25
    canExecute.set(isAtLeastJava25)
    computed.compareAndSet(false, true)
    if (!canExecute.get()) {
      logger.foreach(_.warn(errorMsg))
    }
  }

  private lazy val defaultConfig: ExtractionConfig =
    ExtractionConfig.builder()
      .outputFormat("markdown")
      .ocr(OcrConfig.builder().backend("tesseract").build())
      .forceOcr(false)
      .useCache(false)
      .build()

  def extractFromBytes(bytes: Array[Byte], mimeType: String)(implicit env: Env, ec: ExecutionContext): Future[String] = {
    if (!canExecuteKreuzberg) return Future.failed(new RuntimeException(errorMsg))
    env.metrics.withTimer("kreuzberg.extractFromBytes", display = true) {
      val result = Kreuzberg.extractBytes(bytes, mimeType, defaultConfig)
      result.getContent.vfuture
    }
  }

  def extractFromFile(path: Path)(implicit ec: ExecutionContext): Future[String] = {
    if (!canExecuteKreuzberg) return Future.failed(new RuntimeException(errorMsg))
      val result = Kreuzberg.extractFile(path, defaultConfig)
      result.getContent.vfuture
  }

  def extractFromUrl(url: String, method: String = "GET", headers: Map[String, String] = Map.empty)(implicit env: Env, ec: ExecutionContext): Future[(String, String)] = {
    if (!canExecuteKreuzberg) return Future.failed(new RuntimeException(errorMsg))
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
