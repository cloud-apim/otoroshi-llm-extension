package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import akka.stream.scaladsl.Source
import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatClient, ChatPrompt, ChatResponse, ChatResponseChunk}
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.{JsBoolean, JsDefined, JsLookupResult, JsNull, JsNumber, JsObject, JsString, JsUndefined, JsValue, Json}

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import scala.concurrent.{ExecutionContext, Future}

trait LoadBalancing {
  def select(reqId: String, targets: Seq[AiProvider])(implicit env: Env): AiProvider
}

object RoundRobin extends LoadBalancing {
  private val reqCounter                   = new AtomicInteger(0)
  override def select(reqId: String, targets: Seq[AiProvider])(implicit env: Env): AiProvider = {
    val index: Int = reqCounter.incrementAndGet() % (if (targets.nonEmpty) targets.size else 1)
    targets.apply(index)
  }
}

object Random extends LoadBalancing {
  private val random = new scala.util.Random
  override def select(reqId: String, targets: Seq[AiProvider])(implicit env: Env): AiProvider = {
    val index = random.nextInt(targets.length)
    targets.apply(index)
  }
}

case class AtomicAverage(count: AtomicLong, sum: AtomicLong) {
  def incrBy(v: Long): Unit = {
    count.incrementAndGet()
    sum.addAndGet(v)
  }
  def average: Long = sum.get / count.get
}

object BestResponseTime extends LoadBalancing {

  private val random        = new scala.util.Random
  private val responseTimes = new UnboundedTrieMap[String, AtomicAverage]()

  def incrementAverage(desc: AiProvider, responseTime: Long): Unit = {
    val key = desc.id
    val avg = responseTimes.getOrElseUpdate(key, AtomicAverage(new AtomicLong(0), new AtomicLong(0)))
    avg.incrBy(responseTime)
  }

  override def select(reqId: String, targets: Seq[AiProvider])(implicit env: Env): AiProvider = {
    val keys                     = targets.map(t => t.id)
    val existing                 = responseTimes.toSeq.filter(t => keys.exists(k => t._1 == k))
    val nonExisting: Seq[String] = keys.filterNot(k => responseTimes.contains(k))
    if (existing.size != targets.size) {
      nonExisting.headOption.flatMap(h => targets.find(t => t.id == h)).getOrElse {
        val index = random.nextInt(targets.length)
        targets.apply(index)
      }
    } else {
      val possibleTargets: Seq[(String, Long)] = existing.map(t => (t._1, t._2.average))
      val (key, _)                             = possibleTargets.minBy(_._2)
      targets.find(t => t.id == key).getOrElse {
        val index = random.nextInt(targets.length)
        targets.apply(index)
      }
    }
  }
}

object LoadBalancerChatClient {
  val counter = new AtomicLong(0L)
}

case class LoadBalancingTarget(ref: String, weight: Int, selector: Option[String])

class LoadBalancerChatClient(provider: AiProvider) extends ChatClient {

  override def computeModel(payload: JsValue): Option[String] = None

  override def isCohere: Boolean = false
  override def isOpenAi: Boolean = true
  override def isAnthropic: Boolean = false

  def execute[T](prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(f: ChatClient => Future[Either[JsValue, T]])(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, T]] = {
    val refs: Seq[LoadBalancingTarget] = provider.options.select("refs")
      .asOpt[Seq[String]].map { seq =>
        seq.map(i => LoadBalancingTarget(i, 1, None))
      }
      .orElse(provider.options.select("refs").asOpt[Seq[JsObject]].map { seq =>
        seq.map { obj =>
          LoadBalancingTarget(
            obj.select("ref").asString,
            obj.select("weight").asOpt[Int].getOrElse(1),
            obj.select("selector_expected").asOptString
          )
        }
      })
      .getOrElse(Seq.empty)
    val loadBalancing: LoadBalancing = provider.options.select("loadbalancing").asOpt[String].map(_.toLowerCase()).getOrElse("round_robin") match {
      case "random" => Random
      case "best_response_time" => BestResponseTime
      case _ => RoundRobin
    }
    if (refs.isEmpty) {
      Json.obj("error" -> "no provider configured").leftf
    } else {
      val selector_expr = provider.options.select("selector_expr").asOpt[String]
      val all_providers: Seq[(AiProvider, Option[String])] = refs
        .flatMap(r => if (r.weight <= 1) Seq(r) else (0 to r.weight).map(_ => r))
        .flatMap(r => env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(r.ref)).map(v => (v, r.selector)))
      // val index = LoadBalancerChatClient.counter.incrementAndGet() % (if (providers.nonEmpty) providers.size else 1)

      def filterAll(body: JsObject, f: (JsObject, Option[String]) => JsLookupResult): Seq[(AiProvider, Option[String])] = {
        all_providers.filter {
          case (p, selector) =>
            val mergedOptions: JsObject = if (p.options.select("allow_config_override").asOptBoolean.getOrElse(true)) p.options.deepMerge(body) else p.options
            f(mergedOptions, selector) match {
              case _ if selector.isEmpty => true
              case JsUndefined() => true
              case JsDefined(JsNull) => true
              case JsDefined(JsString(str)) if selector.contains(str) => true // TODO: handle more complex expressions ?
              case JsDefined(JsBoolean(str)) if selector.contains(str.toString) => true
              case JsDefined(JsNumber(str)) if selector.contains(str.toString) => true
              case _ => false
            }
        }
      }

      val body = originalBody.asObject
      val providers = selector_expr match {
        case None => all_providers
        case Some("*") => all_providers
        case Some(str) if str.startsWith("JsonPointer(") => filterAll(body, (o, _) => o.atPointer(str.substring(12).init))
        case Some(str) if str.startsWith("JsonPath(") => filterAll(body, (o, _) => o.atPath(str.substring(9).init))
        case Some(str) => filterAll(body, (o, _) => o.at(str))
      }
      val selectedProvider = loadBalancing.select(LoadBalancerChatClient.counter.incrementAndGet().toString, providers.map(_._1))
      selectedProvider.getChatClient() match {
        case None => Json.obj("error" -> "no client found").leftf
        case Some(client) => f(client)
      }
    }
  }

  override def call(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    execute(prompt, attrs, originalBody) { client =>
      val start = System.currentTimeMillis()
      client.call(prompt, attrs, originalBody).map { resp =>
        val duration: Long = System.currentTimeMillis() - start
        BestResponseTime.incrementAverage(provider, duration)
        resp
      }
    }
  }

  override def stream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    execute(prompt, attrs, originalBody) { client =>
      val start = System.currentTimeMillis()
      client.stream(prompt, attrs, originalBody).map { resp =>
        val duration: Long = System.currentTimeMillis() - start
        BestResponseTime.incrementAverage(provider, duration)
        resp
      }
    }
  }

  override def completion(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    execute(prompt, attrs, originalBody) { client =>
      val start = System.currentTimeMillis()
      client.completion(prompt, attrs, originalBody).map { resp =>
        val duration: Long = System.currentTimeMillis() - start
        BestResponseTime.incrementAverage(provider, duration)
        resp
      }
    }
  }

  override def completionStream(prompt: ChatPrompt, attrs: TypedMap, originalBody: JsValue)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, Source[ChatResponseChunk, _]]] = {
    execute(prompt, attrs, originalBody) { client =>
      val start = System.currentTimeMillis()
      client.completionStream(prompt, attrs, originalBody).map { resp =>
        val duration: Long = System.currentTimeMillis() - start
        BestResponseTime.incrementAverage(provider, duration)
        resp
      }
    }
  }
}
