package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import com.cloud.apim.otoroshi.extensions.aigateway.{ChatClient, ChatGeneration, ChatMessage, ChatPrompt, ChatResponse, ChatResponseMetadata}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.github.benmanes.caffeine.cache.RemovalCause
import com.github.blemale.scaffeine.Scaffeine
import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel
import dev.langchain4j.store.embedding.EmbeddingSearchRequest
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore
import otoroshi.utils.syntax.implicits._
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import play.api.libs.json.JsValue

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters._

object ChatClientWithSemanticCache {
  val embeddingStores = new TrieMap[String, InMemoryEmbeddingStore[TextSegment]]()
  val embeddingModel = new AllMiniLmL6V2EmbeddingModel()
  val cache = Scaffeine()
    .expireAfter[String, (FiniteDuration, ChatResponse)](
      create = (key, value) => value._1,
      update = (key, value, currentDuration) => currentDuration,
      read = (key, value, currentDuration) => currentDuration
    )
    .maximumSize(5000) // TODO: custom ?
    .build[String, (FiniteDuration, ChatResponse)]()

  val cacheEmbeddingCleanup = Scaffeine()
    .expireAfter[String, (FiniteDuration, Function[String, Unit])](
      create = (key, value) => value._1,
      update = (key, value, currentDuration) => currentDuration,
      read = (key, value, currentDuration) => currentDuration
    )
    .removalListener((key: String, value: (FiniteDuration, Function[String, Unit]), reason: RemovalCause) => {
      value._2(key)
    })
    .maximumSize(5000) // TODO: custom ?
    .build[String, (FiniteDuration, Function[String, Unit])]()
  def applyIfPossible(tuple: (AiProvider, ChatClient)): ChatClient = {
    if (tuple._1.cache.strategy.contains("semantic")) {
      new ChatClientWithSemanticCache(tuple._1, tuple._2)
    } else {
      tuple._2
    }
  }
}

class ChatClientWithSemanticCache(originalProvider: AiProvider, chatClient: ChatClient) extends ChatClient {

  private val ttl = originalProvider.cache.ttl

  override def call(originalPrompt: ChatPrompt, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val query = originalPrompt.messages.filter(_.role.toLowerCase().trim == "user").map(_.content).mkString(", ")
    val key = query.sha512
    ChatClientWithSemanticCache.cache.getIfPresent(key) match {
      case Some((_, response)) =>
        // println("using semantic cached response")
        response.rightf
      case None => {
        val embeddingModel = ChatClientWithSemanticCache.embeddingModel
        val embeddingStore = ChatClientWithSemanticCache.embeddingStores.getOrUpdate(originalProvider.id) {
          new InMemoryEmbeddingStore[TextSegment]()
        }
        val queryEmbedding = embeddingModel.embed(query).content()
        val relevant = embeddingStore.search(EmbeddingSearchRequest.builder().queryEmbedding(queryEmbedding).maxResults(1).minScore(0.7).build())
        val matches = relevant.matches().asScala
        if (matches.nonEmpty) {
          val resp = matches.head
          val text = resp.embedded().text()
          // val score = resp.score()
          // println(s"using semantic response: ${score}")
          val chatResponse = ChatResponse(Seq(ChatGeneration(ChatMessage("assistant", text))), ChatResponseMetadata.empty)
          ChatClientWithSemanticCache.cache.put(key, (ttl, chatResponse))
          chatResponse.rightf
        } else {
          chatClient.call(originalPrompt, attrs).map {
            case Left(err) => err.left
            case Right(resp) => {
              val segment = TextSegment.from(resp.generations.head.message.content)
              val embedding = embeddingModel.embed(segment).content()
              embeddingStore.add(key, embedding, segment)
              ChatClientWithSemanticCache.cache.put(key, (ttl, resp))
              ChatClientWithSemanticCache.cacheEmbeddingCleanup.put(key, (ttl, (key) => {
                embeddingStore.remove(key)
              }))
              resp.right
            }
          }
        }
      }
    }
  }
}