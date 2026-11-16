package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.providers.ProviderTargets
import com.cloud.apim.otoroshi.extensions.aigateway.{AiMetrics, ChatCallKind, ChatClient, ChatPrompt, ChatResponseChunk}
import org.apache.pekko.Done
import org.apache.pekko.actor.Cancellable
import org.apache.pekko.stream.scaladsl.{Keep, Sink, SinkQueueWithCancel, Source}
import org.apache.pekko.stream.{KillSwitches, Materializer, UniqueKillSwitch}
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits.*
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.libs.typedmap.TypedKey

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

/**
 * The tool loops of the gateway absorb the chunks of the tool calls and run the tools before streaming the next
 * turn: the first token the caller sees can come long after the provider answered. A loop fires this signal as
 * soon as it gets the first chunk of a call, before running anything, so a slow tool is not taken for a provider
 * that does not answer.
 */
object FirstTokenSignal {
  val Key: TypedKey[Promise[Unit]] = TypedKey[Promise[Unit]]("cloud-apim.ai-gateway.FirstTokenSignal")
  def fire(attrs: TypedMap): Unit = attrs.get(Key).foreach(_.trySuccess(()))
}

object FirstTokenTimeout {

  val code: String = "first_token_timeout"

  // `connection.ttft_timeout`, in ms: absent or 0 means no deadline
  def of(connection: JsObject): Option[Long] = connection.select("ttft_timeout").asOpt[Long].filter(_ > 0L)

  def error(what: String, ttft: Long): JsObject = Json.obj(
    "error" -> Json.obj("type" -> code, "code" -> code, "message" -> s"no token from ${what} within ${ttft} ms"),
    "status" -> 504,
  )

  def isTimeout(err: JsValue): Boolean = err.select("error").select("code").asOpt[String].contains(code)

  // what the caller would see as an answer: content, reasoning, a refusal or a tool call. The empty
  // `role: assistant` chunk some providers start with is not one
  def carriesToken(chunk: ChatResponseChunk): Boolean = LlmCallTelemetry.carriesToken(chunk) ||
    chunk.choices.exists(c => c.delta.refusal.exists(_.nonEmpty) || c.delta.reasoning_details.nonEmpty)

  /**
   * Runs `call`, a streamed call, and gives its stream back once it carried a first token. When no token came
   * within `ttft` ms, the call fails with `first_token_timeout` and the stream of the provider is cancelled: nothing
   * was sent to the caller, so a fallback can still answer. `what` names the provider (and the target) in the error.
   */
  def guard(what: String, ttft: Long, attrs: TypedMap)(call: => Future[Either[JsValue, Source[ChatResponseChunk, ?]]])(using ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, ?]]] = {
    new FirstTokenWatch(what, ttft, attrs).run(call)
  }
}

// One streamed call, watched until its first token. Every decision (a token, the deadline, an error) goes through
// `decided`, under the lock: the first one wins, the others have no effect.
private final class FirstTokenWatch(what: String, ttft: Long, attrs: TypedMap)(using ec: ExecutionContext, env: Env) {

  private type Chunk = ChatResponseChunk
  private given mat: Materializer = env.otoroshiMaterializer

  private val result = Promise[Either[JsValue, Source[Chunk, ?]]]()
  private val lock = new Object
  private var decided = false
  private var signalled = false
  private var timer: Option[Cancellable] = None
  private var queue: Option[SinkQueueWithCancel[Chunk]] = None
  private var killSwitch: Option[UniqueKillSwitch] = None
  // the pull in progress, which the stream handed out reads first when a tool loop signalled the first token
  private var pending: Option[Future[Option[Chunk]]] = None
  private val buffer = scala.collection.mutable.ListBuffer.empty[Chunk]

  def run(call: => Future[Either[JsValue, Source[Chunk, ?]]]): Future[Either[JsValue, Source[Chunk, ?]]] = {
    val signal = Promise[Unit]()
    attrs.put(FirstTokenSignal.Key -> signal)
    timer = Some(env.otoroshiScheduler.scheduleOnce(ttft.millis)(onDeadline()))
    signal.future.foreach(_ => lock.synchronized {
      if (!decided) {
        signalled = true
        if (queue.isDefined) handOut(ended = false)
      }
    })
    Try(call).fold(e => Future.failed(e), identity).onComplete {
      case Failure(e) => if (decideNow()) result.tryFailure(e)
      case Success(Left(err)) => if (decideNow()) result.trySuccess(Left(err))
      case Success(Right(source)) =>
        val watching = lock.synchronized {
          if (decided) {
            false
          } else {
            val (ks, q) = source.viaMat(KillSwitches.single)(Keep.right).toMat(Sink.queue[Chunk]())(Keep.both).run()
            killSwitch = Some(ks)
            queue = Some(q)
            if (signalled) handOut(ended = false)
            true
          }
        }
        // the deadline passed before the headers: nobody will read this body
        if (!watching) source.runWith(Sink.cancelled)
        else pull()
    }
    result.future
  }

  private def decideNow(): Boolean = lock.synchronized {
    if (decided) false else {
      decided = true
      timer.foreach(_.cancel())
      true
    }
  }

  private def onDeadline(): Unit = {
    val abort = lock.synchronized {
      if (decided) None else {
        decided = true
        Some(killSwitch)
      }
    }
    abort.foreach { ks =>
      AiExtension.logger.warn(s"no token from ${what} within ${ttft} ms (${if (ks.isDefined) "after" else "before"} the headers of the response), the call is cancelled")
      AiMetrics.markFirstTokenTimeout()
      result.trySuccess(Left(FirstTokenTimeout.error(what, ttft)))
      ks.foreach(_.abort(new RuntimeException(s"no token from ${what} within ${ttft} ms")))
    }
  }

  private def pull(): Unit = {
    val next = lock.synchronized {
      if (decided) None else queue.map { q =>
        val f = q.pull()
        pending = Some(f)
        f
      }
    }
    next.foreach(_.onComplete(onPulled))
  }

  private def onPulled(r: Try[Option[Chunk]]): Unit = r match {
    case Success(Some(chunk)) =>
      val more = lock.synchronized {
        if (decided) false else {
          pending = None
          buffer += chunk
          if (FirstTokenTimeout.carriesToken(chunk)) {
            handOut(ended = false)
            false
          } else true
        }
      }
      if (more) pull()
    // the stream ended without a token: what it sent is the answer
    case Success(None) => lock.synchronized {
      if (!decided) {
        pending = None
        handOut(ended = true)
      }
    }
    // the stream failed before any token: nothing was sent, the error can be handled as an error of the call
    case Failure(e) => if (decideNow()) result.trySuccess(Left(FirstTokenWatch.errorOf(e)))
  }

  // under the lock, not decided yet
  private def handOut(ended: Boolean): Unit = {
    decided = true
    timer.foreach(_.cancel())
    val buffered = buffer.toList
    val source: Source[Chunk, ?] = (queue, ended) match {
      case (Some(q), false) =>
        val first = pending
        Source.unfoldResourceAsync[Chunk, FirstTokenWatch.Reader](
          () => Future.successful(new FirstTokenWatch.Reader(q, buffered, first)),
          reader => reader.read(),
          reader => {
            q.cancel()
            Future.successful(Done)
          },
        )
      case _ => Source(buffered)
    }
    result.trySuccess(Right(source))
  }
}

private object FirstTokenWatch {

  // reads what was kept, then the pull in progress, then the rest of the queue. The calls are never concurrent
  final class Reader(queue: SinkQueueWithCancel[ChatResponseChunk], buffered: List[ChatResponseChunk], pending: Option[Future[Option[ChatResponseChunk]]]) {
    private var kept = buffered
    private var inProgress = pending
    def read(): Future[Option[ChatResponseChunk]] = kept match {
      case head :: tail =>
        kept = tail
        Future.successful(Some(head))
      case Nil => inProgress match {
        case Some(f) =>
          inProgress = None
          f
        case None => queue.pull()
      }
    }
  }

  // a tool loop fails its stream with the json error of the provider as a message
  def errorOf(e: Throwable): JsValue = {
    val message = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
    Try(Json.parse(message)).toOption.collect { case o: JsObject => o }.getOrElse(Json.obj("error" -> message))
  }
}

object ChatClientWithFirstTokenTimeout {
  // a provider with targets applies the deadline to each target it tries (`TargetsChatClient`), not twice
  def applyIfPossible(tuple: (AiProvider, ChatClient, Env)): ChatClient = {
    FirstTokenTimeout.of(tuple._1.connection) match {
      case Some(ttft) if !ProviderTargets.has(tuple._1) => new ChatClientWithFirstTokenTimeout(tuple._1, tuple._2, ttft)
      case _ => tuple._2
    }
  }
}

/**
 * The deadline of the first token of a streamed call (`connection.ttft_timeout`). Just under the fallback, which
 * receives its error as a failure of the provider and takes over: no byte has been sent to the caller yet. Blocking
 * calls go through untouched.
 */
class ChatClientWithFirstTokenTimeout(provider: AiProvider, val chatClient: ChatClient, ttft: Long) extends DecoratorChatClient {
  override def invokeStream(kind: ChatCallKind, prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(using ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, ?]]] = {
    FirstTokenTimeout.guard(s"provider '${provider.name}'", ttft, attrs)(chatClient.invokeStream(kind, prompt, attrs, originalBody))
  }
}
