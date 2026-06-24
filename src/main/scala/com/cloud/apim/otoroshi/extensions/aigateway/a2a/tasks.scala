package com.cloud.apim.otoroshi.extensions.aigateway.a2a

import otoroshi.env.Env
import otoroshi.storage.RedisLike
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

// Ephemeral A2A Task store (decision Q2=B). Tasks are NOT standard Otoroshi entities (not in state sync): they are
// stored directly in Redis with a differentiated TTL via the otoroshi RedisLike datastore. A `contextId` secondary
// index (Redis set) groups tasks for multi-turn coherence (decision Q5=A).
//
// Note: Q2 mentioned the Otoroshi stateful client API. That API returns a low-level (e.g. lettuce) connection scoped
// to node lifecycle; `env.datastores.redis` is the idiomatic RedisLike already used by every entity datastore and
// natively supports TTL, so we build on it here. A dedicated stateful client (separate Redis) can be swapped in later
// by providing an alternative RedisLike.
class A2ATaskStore(env: Env, redisOpt: Option[RedisLike] = None) {

  private implicit val ec: ExecutionContext = env.otoroshiExecutionContext

  private def redis: RedisLike = redisOpt.getOrElse(env.datastores.redis)

  private val extId: String = env.adminExtensions.extension[AiExtension].map(_.id.cleanup).getOrElse("cloud-apim-llm-extension")

  // TTL (seconds): short for terminal tasks, long for active/interrupted ones.
  val terminalTtlSeconds: Long = 3600L          // 1h
  val activeTtlSeconds: Long   = 24L * 3600L     // 24h

  private def taskKey(id: String): String        = s"${env.storageRoot}:extensions:${extId}:a2a-tasks:${id}"
  private def contextKey(ctxId: String): String  = s"${env.storageRoot}:extensions:${extId}:a2a-contexts:${ctxId}"

  private def ttlFor(state: TaskState): Long = if (state.isTerminal) terminalTtlSeconds else activeTtlSeconds

  def put(task: A2ATask): Future[A2ATask] = {
    val ttl = ttlFor(task.status.state)
    redis.set(taskKey(task.id), task.json.stringify, exSeconds = Some(ttl)).flatMap { _ =>
      redis.sadd(contextKey(task.contextId), task.id).flatMap { _ =>
        redis.expire(contextKey(task.contextId), activeTtlSeconds.toInt).map(_ => task)
      }
    }
  }

  def get(id: String): Future[Option[A2ATask]] = {
    redis.get(taskKey(id)).map { opt =>
      opt.flatMap { bs =>
        Try(Json.parse(bs.utf8String)).toOption.flatMap(js => A2ATask.format.reads(js).asOpt)
      }
    }
  }

  def delete(id: String): Future[Unit] = redis.del(taskKey(id)).map(_ => ())

  // all tasks attached to a contextId (for multi-turn history), most-recently-updated last is not guaranteed
  def tasksForContext(contextId: String): Future[Seq[A2ATask]] = {
    redis.smembers(contextKey(contextId)).flatMap { members =>
      val ids = members.map(_.utf8String)
      Future.sequence(ids.map(get)).map(_.flatten)
    }
  }

  // convenience: append a message to a task's history then persist
  def appendHistory(task: A2ATask, message: A2AMessage): Future[A2ATask] = {
    put(task.copy(history = task.history :+ message))
  }
}
