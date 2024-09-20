package com.cloud.apim.otoroshi.extensions.aigateway.decorators

import com.cloud.apim.otoroshi.extensions.aigateway.entities.AiProvider
import com.cloud.apim.otoroshi.extensions.aigateway.{ChatClient, ChatPrompt, ChatResponse}
import otoroshi.env.Env
import otoroshi.utils.TypedMap
import otoroshi.utils.cache.types.UnboundedTrieMap
import otoroshi.utils.syntax.implicits._
import otoroshi_plugins.com.cloud.apim.extensions.aigateway.AiExtension
import play.api.libs.json.{JsValue, Json}

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

class LoadBalancerChatClient(provider: AiProvider) extends ChatClient {

  override def model: Option[String] = None

  override def call(prompt: ChatPrompt, attrs: TypedMap)(implicit ec: ExecutionContext, env: Env): Future[Either[JsValue, ChatResponse]] = {
    val refs = provider.options.select("refs").asOpt[Seq[String]].getOrElse(Seq.empty)
    val loadBalancing: LoadBalancing = provider.options.select("loadbalancing").asOpt[String].map(_.toLowerCase()).getOrElse("round_robin") match {
      case "random" => Random
      case "best_response_time" => BestResponseTime
      case _ => RoundRobin
    }
    if (refs.isEmpty) {
      Json.obj("error" -> "no provider configured").leftf
    } else {
      val providers: Seq[AiProvider] = refs.flatMap(r => env.adminExtensions.extension[AiExtension].flatMap(_.states.provider(r)))
      // val index = LoadBalancerChatClient.counter.incrementAndGet() % (if (providers.nonEmpty) providers.size else 1)
      val provider = loadBalancing.select(LoadBalancerChatClient.counter.incrementAndGet().toString, providers)
      provider.getChatClient() match {
        case None => Json.obj("error" -> "no client found").leftf
        case Some(client) => {
          val start = System.currentTimeMillis()
          client.call(prompt, attrs).map { resp =>
            val duration: Long = System.currentTimeMillis() - start
            BestResponseTime.incrementAverage(provider, duration)
            resp
          }
        }
      }
    }
  }
}
