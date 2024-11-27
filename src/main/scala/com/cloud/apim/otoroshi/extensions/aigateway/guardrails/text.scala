package com.cloud.apim.otoroshi.extensions.aigateway.guardrails

import com.cloud.apim.otoroshi.extensions.aigateway.{ChatClient, ChatMessage}
import com.cloud.apim.otoroshi.extensions.aigateway.decorators.{Guardrail, GuardrailResult}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import dev.langchain4j.data.segment.TextSegment
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.JsObject

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.asScalaBufferConverter

class ContainsGuardrail extends Guardrail {

  override def isBefore: Boolean = true

  override def isAfter: Boolean = true

  override def manyMessages: Boolean = false

  override def pass(messages: Seq[ChatMessage], config: JsObject, provider: AiProvider, chatClient: ChatClient, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[GuardrailResult] = {
    val matchAllConversation = false //config.select("match_all_conversation").asOpt[Boolean].getOrElse(false)
    val operation = config.select("operation").asOpt[String].getOrElse("contains_all")
    val values = config.select("values").asOpt[Seq[String]].getOrElse(Seq.empty)
    val message = if (matchAllConversation) messages.filter(_.role.toLowerCase().trim == "user").map(_.content).mkString(". ") else messages.head.content
    operation match {
      case "contains_all" if values.forall(value => message.contains(value)) => GuardrailResult.GuardrailPass.vfuture
      case "contains_none" if values.forall(value => !message.contains(value)) => GuardrailResult.GuardrailPass.vfuture
      case "contains_any" if values.exists(value => message.contains(value)) => GuardrailResult.GuardrailPass.vfuture
      case _ => GuardrailResult.GuardrailDenied(s"This message has been blocked by the 'contains' guardrails !").vfuture
    }
  }
}

class SemanticContainsGuardrail extends Guardrail {

  override def isBefore: Boolean = true

  override def isAfter: Boolean = true

  override def manyMessages: Boolean = false

  override def pass(messages: Seq[ChatMessage], config: JsObject, provider: AiProvider, chatClient: ChatClient, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[GuardrailResult] = env.metrics.withTimer("semantic_contains") {
   // println(s"searching on ${messages.last.content}")
    val operation = config.select("operation").asOpt[String].getOrElse("contains_all")
    val values = config.select("values").asOpt[Seq[String]].getOrElse(Seq.empty)
    val score = config.select("score").asOpt[Double].getOrElse(0.8)
    val matchAllConversation = false // config.select("match_all_conversation").asOpt[Boolean].getOrElse(false)
    val embeddingModel = new dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel()
    val store = new dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore[TextSegment]()
    val segment = TextSegment.from(if (matchAllConversation) messages.filter(_.role.toLowerCase().trim == "user").map(_.content).mkString(". ") else messages.last.content)
    val embedding = embeddingModel.embed(segment).content()
    store.add(embedding, segment)

    def find(what: String): Boolean = {
      // println(s"try to find '${what}'")
      val queryEmbedding = embeddingModel.embed(what).content()
      val relevant = store.search(dev.langchain4j.store.embedding.EmbeddingSearchRequest.builder().queryEmbedding(queryEmbedding).maxResults(1).minScore(score).build())
      // val relevant = store.search(dev.langchain4j.store.embedding.EmbeddingSearchRequest.builder().queryEmbedding(queryEmbedding).maxResults(1).build())
      val matches = relevant.matches().asScala
      // matches.map { m =>
      //   println(s"${m.score()} - ${m.embedded().text()}")
      // }
      matches.nonEmpty
    }

    operation match {
      case "contains_all" if values.forall(value => find(value)) => GuardrailResult.GuardrailPass.vfuture
      case "contains_none" if values.forall(value => !find(value)) => GuardrailResult.GuardrailPass.vfuture
      case "contains_any" if values.exists(value => find(value)) => GuardrailResult.GuardrailPass.vfuture
      case _ => GuardrailResult.GuardrailDenied(s"This message has been blocked by the 'semantic_contains' guardrails !").vfuture
    }
  }
}

class CharactersCountGuardrail extends Guardrail {

  override def isBefore: Boolean = true

  override def isAfter: Boolean = true

  override def manyMessages: Boolean = false

  override def pass(messages: Seq[ChatMessage], config: JsObject, provider: AiProvider, chatClient: ChatClient, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[GuardrailResult] = {
    val message = messages.head
    val min = config.select("min").asOpt[Long].getOrElse(0L)
    val max = config.select("max").asOpt[Long].getOrElse(Long.MaxValue)
    val count = message.content.length
    val pass = count >= min && count <= max
    if (pass) {
      GuardrailResult.GuardrailPass.vfuture
    } else {
      GuardrailResult.GuardrailDenied(s"This message has been blocked by the 'characters count' guardrail !").vfuture
    }
  }
}

class WordsCountGuardrail extends Guardrail {

  override def isBefore: Boolean = true

  override def isAfter: Boolean = true

  override def manyMessages: Boolean = false

  override def pass(messages: Seq[ChatMessage], config: JsObject, provider: AiProvider, chatClient: ChatClient, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[GuardrailResult] = {
    val message = messages.head
    val min = config.select("min").asOpt[Long].getOrElse(0L)
    val max = config.select("max").asOpt[Long].getOrElse(Long.MaxValue)
    val count = message.content.split("\\s+").length
    val pass = count >= min && count <= max
    if (pass) {
      GuardrailResult.GuardrailPass.vfuture
    } else {
      GuardrailResult.GuardrailDenied(s"This message has been blocked by the 'words count' guardrail !").vfuture
    }
  }
}

class SentencesCountGuardrail extends Guardrail {

  override def isBefore: Boolean = true

  override def isAfter: Boolean = true

  override def manyMessages: Boolean = false

  override def pass(messages: Seq[ChatMessage], config: JsObject, provider: AiProvider, chatClient: ChatClient, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[GuardrailResult] = {
    val message = messages.head
    val min = config.select("min").asOpt[Long].getOrElse(0L)
    val max = config.select("max").asOpt[Long].getOrElse(Long.MaxValue)
    val count = message.content.split("[.!?]").length
    val pass = count >= min && count <= max
    if (pass) {
      GuardrailResult.GuardrailPass.vfuture
    } else {
      GuardrailResult.GuardrailDenied(s"This message has been blocked by the 'sentences count' guardrail !").vfuture
    }
  }
}

