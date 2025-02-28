package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{Sink, Source}
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatClient, ChatGeneration, ChatPrompt, ChatResponse, ChatResponseChunk, ChatResponseMetadata, ChatResponseMetadataRateLimit, ChatResponseMetadataUsage, OutputChatMessage}
import otoroshi.env.Env
import otoroshi.events.AuditEvent
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.{JsArray, JsNull, JsObject, JsValue, Json}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object ChatClientWithAuditing {
  def applyIfPossible(tuple: (AiProvider, ChatClient)): ChatClient = {
    new ChatClientWithAuditing(tuple._1, tuple._2)
  }
}

class ChatClientWithAuditing(originalProvider: AiProvider, val chatClient: ChatClient) extends DecoratorChatClient {

  override def call(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val user = attrs.get(otoroshi.plugins.Keys.UserKey)
    val apikey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey)
    val route = attrs.get(otoroshi.next.plugins.Keys.RouteKey)
    // val request = attrs.get(otoroshi.plugins.Keys.RequestKey)
    chatClient.call(prompt, attrs, originalBody).andThen {
      case Failure(exception) => {
        AuditEvent.generic("LLMUsageAudit") {
          Json.obj(
            "error" -> Json.obj(
              "exception" -> exception.getMessage
            ),
            "consumed_using" -> "chat/completion/blocking",
            "user" -> user.map(_.json).getOrElse(JsNull).asValue,
            "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
            "route" -> route.map(_.json).getOrElse(JsNull).asValue,
            "input_prompt" -> prompt.json,
            "output" -> JsNull,
            "provider_details" -> originalProvider.json
            //"request" -> request.map(_.json).getOrElse(JsNull).asValue,
          )
        }.toAnalytics()
      }
      case Success(value) => value match {
        case Left(err) => {
          AuditEvent.generic("LLMUsageAudit") {
            Json.obj(
              "error" -> err,
              "consumed_using" -> "chat/completion/blocking",
              "user" -> user.map(_.json).getOrElse(JsNull).asValue,
              "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
              "route" -> route.map(_.json).getOrElse(JsNull).asValue,
              "input_prompt" -> prompt.json,
              "output" -> JsNull,
              "provider_details" -> originalProvider.json
              //"request" -> request.map(_.json).getOrElse(JsNull).asValue,
            )
          }.toAnalytics()
        }
        case Right(value) => {
          val usageSlug: JsObject = attrs.get(otoroshi.plugins.Keys.ExtraAnalyticsDataKey).flatMap(_.select("ai").asOpt[Seq[JsObject]]).flatMap(_.headOption).flatMap(_.asOpt[JsObject]).getOrElse(Json.obj())
          val provider = usageSlug.select("provider").asOpt[String].flatMap(id => env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(id)))
          AuditEvent.generic("LLMUsageAudit") {
            usageSlug ++ Json.obj(
              "error" -> JsNull,
              "consumed_using" -> "chat/completion/blocking",
              "user" -> user.map(_.json).getOrElse(JsNull).asValue,
              "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
              "route" -> route.map(_.json).getOrElse(JsNull).asValue,
              "input_prompt" -> prompt.json,
              "output" -> value.json,
              "provider_details" -> originalProvider.json //provider.map(_.json).getOrElse(JsNull).asValue,
              //"request" -> request.map(_.json).getOrElse(JsNull).asValue,
            )
          }.toAnalytics()
        }
      }
    }
  }

  override def stream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    val user = attrs.get(otoroshi.plugins.Keys.UserKey)
    val apikey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey)
    val route = attrs.get(otoroshi.next.plugins.Keys.RouteKey)
    // val request = attrs.get(otoroshi.plugins.Keys.RequestKey)
    chatClient.stream(prompt, attrs, originalBody).transformWith {
      case Failure(exception) => {
        AuditEvent.generic("LLMUsageAudit") {
          Json.obj(
            "error" -> Json.obj(
              "exception" -> exception.getMessage
            ),
            "consumed_using" -> "chat/completion/streaming",
            "user" -> user.map(_.json).getOrElse(JsNull).asValue,
            "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
            "route" -> route.map(_.json).getOrElse(JsNull).asValue,
            "input_prompt" -> prompt.json,
            "output" -> JsNull,
            "provider_details" -> originalProvider.json
            //"request" -> request.map(_.json).getOrElse(JsNull).asValue,
          )
        }.toAnalytics()
        FastFuture.failed(exception)
      }
      case Success(value) => value match {
        case Left(err) => {
          AuditEvent.generic("LLMUsageAudit") {
            Json.obj(
              "error" -> err,
              "consumed_using" -> "chat/completion/streaming",
              "user" -> user.map(_.json).getOrElse(JsNull).asValue,
              "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
              "route" -> route.map(_.json).getOrElse(JsNull).asValue,
              "input_prompt" -> prompt.json,
              "output" -> JsNull,
              "provider_details" -> originalProvider.json
              //"request" -> request.map(_.json).getOrElse(JsNull).asValue,
            )
          }.toAnalytics()
          FastFuture.successful(Left(err))
        }
        case Right(value) => {
          var seq = Seq.empty[ChatResponseChunk]
          val source = value
            .alsoTo(Sink.foreach { chunk =>
              seq = seq :+ chunk
            })
            .alsoTo(Sink.onComplete { _ =>
              val usageSlug: JsObject = attrs.get(otoroshi.plugins.Keys.ExtraAnalyticsDataKey).flatMap(_.select("ai").asOpt[Seq[JsObject]]).flatMap(_.headOption).flatMap(_.asOpt[JsObject]).getOrElse(Json.obj())
              val provider = usageSlug.select("provider").asOpt[String].flatMap(id => env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(id)))
              AuditEvent.generic("LLMUsageAudit") {
                usageSlug ++ Json.obj(
                  "error" -> JsNull,
                  "consumed_using" -> "chat/completion/streaming",
                  "user" -> user.map(_.json).getOrElse(JsNull).asValue,
                  "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
                  "route" -> route.map(_.json).getOrElse(JsNull).asValue,
                  "input_prompt" -> prompt.json,
                  "output_stream" -> JsArray(seq.map(_.json)),
                  "output" -> ChatResponse(
                    generations = Seq(ChatGeneration(OutputChatMessage("assistant", seq.flatMap(_.choices.flatMap(_.delta.content)).mkString(""), None, Json.obj()))),
                    metadata = ChatResponseMetadata(
                      rateLimit =  ChatResponseMetadataRateLimit(
                        requestsLimit = usageSlug.select("rate_limit").select("requests_limit").asOptLong.getOrElse(-1L),
                        requestsRemaining = usageSlug.select("rate_limit").select("requests_remaining").asOptLong.getOrElse(-1L),
                        tokensLimit = usageSlug.select("rate_limit").select("tokens_limit").asOptLong.getOrElse(-1L),
                        tokensRemaining = usageSlug.select("rate_limit").select("tokens_remaining").asOptLong.getOrElse(-1L),
                      ),
                      usage = ChatResponseMetadataUsage(
                        promptTokens = usageSlug.select("usage").select("prompt_tokens").asOptLong.getOrElse(-1L),
                        generationTokens = usageSlug.select("usage").select("generation_tokens").asOptLong.getOrElse(-1L),
                        reasoningTokens = usageSlug.select("usage").select("reasoning_tokens").asOptLong.getOrElse(-1L),
                      ),
                      cache = None
                    )
                  ).json.debug(_.prettify.debugPrintln),
                  "provider_details" -> originalProvider.json //provider.map(_.json).getOrElse(JsNull).asValue,
                  //"request" -> request.map(_.json).getOrElse(JsNull).asValue,
                )
              }.toAnalytics()
            })
          FastFuture.successful(Right(source))
        }
      }
    }
  }

  override def completion(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val user = attrs.get(otoroshi.plugins.Keys.UserKey)
    val apikey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey)
    val route = attrs.get(otoroshi.next.plugins.Keys.RouteKey)
    chatClient.completion(prompt, attrs, originalBody).andThen {
      case Failure(exception) => {
        AuditEvent.generic("LLMUsageAudit") {
          Json.obj(
            "error" -> Json.obj(
              "exception" -> exception.getMessage
            ),
            "consumed_using" -> "completion/blocking",
            "user" -> user.map(_.json).getOrElse(JsNull).asValue,
            "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
            "route" -> route.map(_.json).getOrElse(JsNull).asValue,
            "input_prompt" -> prompt.json,
            "output" -> JsNull,
            "provider_details" -> originalProvider.json
            //"request" -> request.map(_.json).getOrElse(JsNull).asValue,
          )
        }.toAnalytics()
      }
      case Success(value) => value match {
        case Left(err) => {
          AuditEvent.generic("LLMUsageAudit") {
            Json.obj(
              "error" -> err,
              "consumed_using" -> "completion/blocking",
              "user" -> user.map(_.json).getOrElse(JsNull).asValue,
              "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
              "route" -> route.map(_.json).getOrElse(JsNull).asValue,
              "input_prompt" -> prompt.json,
              "output" -> JsNull,
              "provider_details" -> originalProvider.json
              //"request" -> request.map(_.json).getOrElse(JsNull).asValue,
            )
          }.toAnalytics()
        }
        case Right(value) => {
          val usageSlug: JsObject = attrs.get(otoroshi.plugins.Keys.ExtraAnalyticsDataKey).flatMap(_.select("ai").asOpt[Seq[JsObject]]).flatMap(_.headOption).flatMap(_.asOpt[JsObject]).getOrElse(Json.obj())
          val provider = usageSlug.select("provider").asOpt[String].flatMap(id => env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(id)))
          AuditEvent.generic("LLMUsageAudit") {
            usageSlug ++ Json.obj(
              "error" -> JsNull,
              "consumed_using" -> "completion/blocking",
              "user" -> user.map(_.json).getOrElse(JsNull).asValue,
              "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
              "route" -> route.map(_.json).getOrElse(JsNull).asValue,
              "input_prompt" -> prompt.json,
              "output" -> value.json,
              "provider_details" -> originalProvider.json //provider.map(_.json).getOrElse(JsNull).asValue,
              //"request" -> request.map(_.json).getOrElse(JsNull).asValue,
            )
          }.toAnalytics()
        }
      }
    }
  }

  override def completionStream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    val user = attrs.get(otoroshi.plugins.Keys.UserKey)
    val apikey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey)
    val route = attrs.get(otoroshi.next.plugins.Keys.RouteKey)
    chatClient.completionStream(prompt, attrs, originalBody).andThen {
      case Failure(exception) => {
        AuditEvent.generic("LLMUsageAudit") {
          Json.obj(
            "error" -> Json.obj(
              "exception" -> exception.getMessage
            ),
            "consumed_using" -> "completion/streaming",
            "user" -> user.map(_.json).getOrElse(JsNull).asValue,
            "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
            "route" -> route.map(_.json).getOrElse(JsNull).asValue,
            "input_prompt" -> prompt.json,
            "output" -> JsNull,
            "provider_details" -> originalProvider.json
            //"request" -> request.map(_.json).getOrElse(JsNull).asValue,
          )
        }.toAnalytics()
      }
      case Success(value) => value match {
        case Left(err) => {
          AuditEvent.generic("LLMUsageAudit") {
            Json.obj(
              "error" -> err,
              "consumed_using" -> "completion/streaming",
              "user" -> user.map(_.json).getOrElse(JsNull).asValue,
              "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
              "route" -> route.map(_.json).getOrElse(JsNull).asValue,
              "input_prompt" -> prompt.json,
              "output" -> JsNull,
              "provider_details" -> originalProvider.json
              //"request" -> request.map(_.json).getOrElse(JsNull).asValue,
            )
          }.toAnalytics()
        }
        case Right(value) => {
          var seq = Seq.empty[ChatResponseChunk]
          value
            .alsoTo(Sink.foreach { chunk =>
              seq = seq :+ chunk
            })
            .alsoTo(Sink.onComplete { _ =>
              val usageSlug: JsObject = attrs.get(otoroshi.plugins.Keys.ExtraAnalyticsDataKey).flatMap(_.select("ai").asOpt[Seq[JsObject]]).flatMap(_.headOption).flatMap(_.asOpt[JsObject]).getOrElse(Json.obj())
              val provider = usageSlug.select("provider").asOpt[String].flatMap(id => env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(id)))
              AuditEvent.generic("LLMUsageAudit") {
                usageSlug ++ Json.obj(
                  "error" -> JsNull,
                  "consumed_using" -> "completion/streaming",
                  "user" -> user.map(_.json).getOrElse(JsNull).asValue,
                  "apikey" -> apikey.map(_.json).getOrElse(JsNull).asValue,
                  "route" -> route.map(_.json).getOrElse(JsNull).asValue,
                  "input_prompt" -> prompt.json,
                  "output" -> JsArray(seq.map(_.json)),
                  "provider_details" -> originalProvider.json //provider.map(_.json).getOrElse(JsNull).asValue,
                  //"request" -> request.map(_.json).getOrElse(JsNull).asValue,
                )
              }.toAnalytics()
            })
        }
      }
    }
  }
}