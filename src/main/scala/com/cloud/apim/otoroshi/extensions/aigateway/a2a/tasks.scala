package com.cloud.apim.otoroshi.extensions.aigateway.a2a

import otoroshi.env.Env
import otoroshi.storage.RedisLike
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json._

import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

// result of A2ATaskStore.listTasks: a page of tasks + cursor + total (decision Q2 / ListTasks pagination)
case class A2ATaskPage(tasks: Seq[A2ATask], nextPageToken: String, pageSize: Int, totalSize: Int)

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
  private def indexKey: String                   = s"${env.storageRoot}:extensions:${extId}:a2a-task-index"
  private def pushKey(taskId: String, cfgId: String): String = s"${env.storageRoot}:extensions:${extId}:a2a-push:${taskId}:${cfgId}"
  private def pushIndexKey(taskId: String): String          = s"${env.storageRoot}:extensions:${extId}:a2a-push-idx:${taskId}"

  private def ttlFor(state: TaskState): Long = if (state.isTerminal) terminalTtlSeconds else activeTtlSeconds

  def put(task: A2ATask): Future[A2ATask] = {
    val ttl = ttlFor(task.status.state)
    redis.set(taskKey(task.id), task.json.stringify, exSeconds = Some(ttl)).flatMap { _ =>
      redis.sadd(indexKey, task.id).flatMap { _ =>
        redis.sadd(contextKey(task.contextId), task.id).flatMap { _ =>
          redis.expire(contextKey(task.contextId), activeTtlSeconds.toInt).map(_ => task)
        }
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

  // ListTasks — filter + offset cursor pagination (no zset in RedisLike, so we sort/filter in memory).
  // pageToken is base64 of the next offset. Expired ids are self-healed (srem) from the index on read.
  def listTasks(contextId: Option[String], status: Option[TaskState], statusTimestampAfter: Option[String], pageSize: Int, pageToken: Option[String], includeArtifacts: Boolean): Future[A2ATaskPage] = {
    val size = math.max(1, math.min(100, if (pageSize <= 0) 50 else pageSize))
    val offset = pageToken.flatMap(decodeOffset).getOrElse(0)
    val sourceKey = contextId.map(contextKey).getOrElse(indexKey)
    redis.smembers(sourceKey).flatMap { members =>
      val ids = members.map(_.utf8String)
      Future.sequence(ids.map(id => get(id).map(id -> _))).flatMap { pairs =>
        val missing = pairs.collect { case (id, None) => id }
        val healed = if (missing.nonEmpty) redis.srem(sourceKey, missing: _*).map(_ => ()) else Future.successful(())
        healed.map { _ =>
          val all = pairs.collect { case (_, Some(t)) => t }
            .filter(t => status.forall(s => t.status.state == s))
            .filter(t => statusTimestampAfter.forall(after => t.status.timestamp.exists(_ >= after)))
            .sortBy(_.status.timestamp.getOrElse(""))(Ordering[String].reverse)
          val total = all.size
          val page = all.slice(offset, offset + size).map(t => if (includeArtifacts) t else t.copy(artifacts = Seq.empty))
          val next = if (offset + size < total) encodeOffset(offset + size) else ""
          A2ATaskPage(page, next, size, total)
        }
      }
    }
  }

  private def encodeOffset(offset: Int): String = Base64.getEncoder.encodeToString(offset.toString.getBytes("UTF-8"))
  private def decodeOffset(token: String): Option[Int] = Try(new String(Base64.getDecoder.decode(token), "UTF-8").toInt).toOption

  // ---- Push notification configs (per task) -------------------------------------------------------------------------
  def putPushConfig(cfg: A2APushConfig): Future[A2APushConfig] = {
    redis.set(pushKey(cfg.taskId, cfg.id), cfg.json.stringify, exSeconds = Some(activeTtlSeconds)).flatMap { _ =>
      redis.sadd(pushIndexKey(cfg.taskId), cfg.id).flatMap { _ =>
        redis.expire(pushIndexKey(cfg.taskId), activeTtlSeconds.toInt).map(_ => cfg)
      }
    }
  }

  def getPushConfig(taskId: String, cfgId: String): Future[Option[A2APushConfig]] = {
    redis.get(pushKey(taskId, cfgId)).map(_.flatMap(bs => Try(Json.parse(bs.utf8String)).toOption.map(A2APushConfig.from)))
  }

  def listPushConfigs(taskId: String): Future[Seq[A2APushConfig]] = {
    redis.smembers(pushIndexKey(taskId)).flatMap { members =>
      Future.sequence(members.map(_.utf8String).map(cid => getPushConfig(taskId, cid))).map(_.flatten)
    }
  }

  def deletePushConfig(taskId: String, cfgId: String): Future[Unit] = {
    redis.del(pushKey(taskId, cfgId)).flatMap(_ => redis.srem(pushIndexKey(taskId), cfgId)).map(_ => ())
  }
}
