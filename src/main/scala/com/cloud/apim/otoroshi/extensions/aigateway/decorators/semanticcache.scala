package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway._
import com.github.benmanes.caffeine.cache.RemovalCause
import com.github.blemale.scaffeine.Scaffeine
import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel
import dev.langchain4j.store.embedding.EmbeddingSearchRequest
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.JsValue

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{DurationInt, DurationLong, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._

object ChatClientWithSemanticCache {
  val embeddingStores = new TrieMap[String, InMemoryEmbeddingStore[TextSegment]]()
  val embeddingModel = new AllMiniLmL6V2EmbeddingModel()
  val cache = Scaffeine()
    .expireAfter[String, (FiniteDuration, ChatResponse, Long)](
      create = (key, value) => value._1,
      update = (key, value, currentDuration) => currentDuration,
      read = (key, value, currentDuration) => currentDuration
    )
    .maximumSize(5000) // TODO: custom ?
    .build[String, (FiniteDuration, ChatResponse, Long)]()

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
  private val searchInPrompts = true

  override def model: Option[String] = chatClient.model

  private def notInCache(key: String, originalPrompt: ChatPrompt, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val embeddingModel = ChatClientWithSemanticCache.embeddingModel
    val embeddingStore = ChatClientWithSemanticCache.embeddingStores.getOrUpdate(originalProvider.id) {
      new InMemoryEmbeddingStore[TextSegment]()
    }
    chatClient.call(originalPrompt, attrs).map {
      case Left(err) => err.left
      case Right(resp) => {
        if (searchInPrompts) {
          val segment = TextSegment.from(originalPrompt.messages.map(_.content).mkString(". "))
          val embedding = embeddingModel.embed(segment).content()
          embeddingStore.add(key, embedding, segment)
          // println(s"putting in cache for ${ttl.toMillis} - ${key} - ${segment.text()}")
          ChatClientWithSemanticCache.cache.put(key, (ttl, resp, System.currentTimeMillis()))
          ChatClientWithSemanticCache.cacheEmbeddingCleanup.put(key, (ttl, (key) => {
            // println(s"removing ${segment.text()} - ${key}")
            embeddingStore.remove(key)
          }))
        } else {
          val segment = TextSegment.from(resp.generations.head.message.content)
          val embedding = embeddingModel.embed(segment).content()
          embeddingStore.add(key, embedding, segment)
          ChatClientWithSemanticCache.cache.put(key, (ttl, resp, System.currentTimeMillis()))
          ChatClientWithSemanticCache.cacheEmbeddingCleanup.put(key, (ttl, (key) => {
            embeddingStore.remove(key)
          }))
        }
        resp.copy(metadata = resp.metadata.copy(
          cache = Some(ChatResponseCache(ChatResponseCacheStatus.Miss, key, ttl, 0.millis))
        )).right
      }
    }
  }

  override def call(originalPrompt: ChatPrompt, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val query = originalPrompt.messages.filter(_.role.toLowerCase().trim == "user").map(_.content).mkString(", ")
    val key = query.sha512
    ChatClientWithSemanticCache.cache.getIfPresent(key) match {
      case Some((_, response, at)) =>
        // println("using semantic cached response")
        response.copy(metadata = response.metadata.copy(
          usage = ChatResponseMetadataUsage.empty,
          cache = Some(ChatResponseCache(ChatResponseCacheStatus.Hit, key, ttl, (System.currentTimeMillis() - at).millis))
        )).rightf
      case None => {
        val embeddingModel = ChatClientWithSemanticCache.embeddingModel
        val embeddingStore = ChatClientWithSemanticCache.embeddingStores.getOrUpdate(originalProvider.id) {
          new InMemoryEmbeddingStore[TextSegment]()
        }
        val queryEmbedding = embeddingModel.embed(query).content()
        // println(s"searching query: ${query}")
        val relevant = embeddingStore.search(EmbeddingSearchRequest.builder().queryEmbedding(queryEmbedding).maxResults(1).minScore(originalProvider.cache.score).build())
        val matches = relevant.matches().asScala
        // println(s"cache: ${matches.length}")
        if (matches.nonEmpty) {
          // println(s"found ${matches.length} results")
          // matches.foreach { it =>
          //   println(s" - ${it.score()} - ${it.embedded().text()}")
          // }
          val resp = matches.head
          if (searchInPrompts) {
            // println("searching prompt")
            val id = resp.embeddingId()
            val prompt = resp.embedded().text()
            val score = resp.score()
            // println(s"using semantic prompt with score: ${score} with prompt: ${prompt} and id: ${id}")
            ChatClientWithSemanticCache.cache.getIfPresent(id) match {
              case None => notInCache(key, originalPrompt, attrs) // TODO: key or id ???
              case Some(cached) => {
                val chatResponse = cached._2
                chatResponse.copy(metadata = chatResponse.metadata.copy(
                  usage = ChatResponseMetadataUsage.empty,
                  cache = Some(ChatResponseCache(ChatResponseCacheStatus.Hit, key, ttl, (System.currentTimeMillis() - cached._3).millis))
                )).rightf
              }
            }
          } else {
            // println("searching responses")
            val text = resp.embedded().text()
            val score = resp.score()
            // println(s"using semantic response: ${score} with text: ${text}")
            val chatResponse = ChatResponse(Seq(ChatGeneration(ChatMessage("assistant", text))), ChatResponseMetadata.empty)
            ChatClientWithSemanticCache.cache.put(key, (ttl, chatResponse, System.currentTimeMillis()))
            chatResponse.copy(metadata = chatResponse.metadata.copy(
              cache = Some(ChatResponseCache(ChatResponseCacheStatus.Miss, key, ttl, 0.millis))
            )).rightf
          }
        } else {
          // println("not in semantic cache")
          notInCache(key, originalPrompt, attrs)
        }
      }
    }
  }
}